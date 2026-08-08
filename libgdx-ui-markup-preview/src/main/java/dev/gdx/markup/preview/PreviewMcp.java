package dev.gdx.markup.preview;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.harness.HarnessSemanticSink;
import dev.gdx.markup.runtime.MarkupRuntimeSource;
import dev.gdx.uiharness.agentruntime.AgentRuntimeObservationSource;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.runtime.RuntimeComparator;
import dev.gdx.uiharness.core.runtime.RuntimeObservationSource;
import dev.gdx.uiharness.core.time.Deadline;
import dev.gdx.uiharness.core.wait.WaitEngine;
import dev.gdx.uiharness.lwjgl3.Lwjgl3FrameFence;
import dev.gdx.uiharness.lwjgl3.Lwjgl3ScreenCapture;
import dev.gdx.uiharness.mcp.ArtifactReference;
import dev.gdx.uiharness.mcp.HarnessMcpServer;
import dev.gdx.uiharness.protocol.CapabilitySet;
import dev.gdx.uiharness.protocol.HarnessProtocolService;
import dev.gdx.uiharness.scene2d.ControlledStageClock;
import dev.gdx.uiharness.scene2d.RenderThreadScheduler;
import dev.gdx.uiharness.scene2d.Scene2dHarness;
import dev.gdx.uiharness.scene2d.Scene2dSession;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntimeException;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeErrorCode;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Harness MCP wiring for the preview ({@code --mcp}): binds a {@link Scene2dSession} to the
 * stage, advances a fixed-step {@link ControlledStageClock} each rendered frame, and serves the
 * harness stdio protocol so the preview becomes an agent-drivable target. Mirrors the harness
 * fixture wiring against the published 1.1.0 APIs, including the runtime-compare coordinator
 * that correlates markup-declared runtime entities through {@code ui_runtime_compare}.
 */
final class PreviewMcp implements AutoCloseable {
    /** Fixed render step used by the deterministic clock. */
    static final Duration FIXED_STEP = Duration.ofMillis(16);

    private static final String CORRELATION_TOKEN = "markup-preview-frame";

    private final Stage stage;
    private final ControlledStageClock clock;
    private final RenderThreadScheduler scheduler;
    private final Scene2dSession session;
    private final Lwjgl3FrameFence fence;
    private final Lwjgl3ScreenCapture capture;
    private final WaitEngine waits;
    private final HarnessSemanticSink sink;
    private final Scene2dHarness harness;
    private final java.util.concurrent.ExecutorService protocolExecutor;
    private final HarnessMcpServer server;
    private final InMemoryArtifactPublisher artifacts;
    private final AgentRuntime runtime;
    private final Thread terminator;
    /** Committed (live) runtime registration; replaced only by a committed candidate. */
    private MarkupRuntimeSource runtimeSource;
    /** The committed document/built retained so the last-good registration can be reinstalled
     * when a colliding candidate had to remove its ids before failing. */
    private MarkupDocument lastGoodDocument;
    private BuiltUi lastGoodBuilt;
    /** Set when the retained last-good registration could not be reinstated after its ids had
     * to be removed: the runtime can no longer be kept consistent, so the preview must stop. */
    private boolean runtimeLost;

    PreviewMcp(Stage stage) {
        this(stage, InMemoryArtifactPublisher::new,
                (protocol, artifacts) -> HarnessMcpServer.open(
                        protocol, artifacts, System.in, System.out),
                AgentRuntime::start, AutoCloseable::close);
    }

    /**
     * Test seam constructor: injectable artifact/server factories, a runtime starter, and an
     * acquired-resource closer so tests can prove staged ownership — every component is added
     * to the rollback set immediately upon acquisition (the runtime before {@code start},
     * the protocol executor as soon as it exists), and a partial start/open failure closes
     * every acquired resource in strictly reverse acquisition order, with cleanup failures
     * suppressed onto the primary.
     */
    PreviewMcp(Stage stage, ArtifactsFactory artifactsFactory, McpServerFactory serverFactory,
            RuntimeStarter runtimeStarter, ComponentCloser closer) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.componentCloser = Objects.requireNonNull(closer, "closer");
        List<AutoCloseable> acquired = new ArrayList<>();
        try {
            ControlledStageClock clock = new ControlledStageClock(stage, FIXED_STEP);
            acquired.add(clock);
            this.clock = clock;
            RenderThreadScheduler scheduler = new RenderThreadScheduler(128);
            acquired.add(scheduler);
            this.scheduler = scheduler;
            Scene2dSession session = new Scene2dSession(stage);
            acquired.add(session);
            this.session = session;
            sink = new HarnessSemanticSink(session.semantics(), CORRELATION_TOKEN);
            Lwjgl3FrameFence fence = new Lwjgl3FrameFence(64);
            acquired.add(fence);
            this.fence = fence;
            Lwjgl3ScreenCapture capture = new Lwjgl3ScreenCapture(fence, session::snapshot);
            acquired.add(capture);
            this.capture = capture;
            WaitEngine waits = new WaitEngine(this::snapshotForWait,
                    new StrictResolution(), clock, clock);
            acquired.add(waits);
            this.waits = waits;
            AgentRuntime runtime = AgentRuntime.builder()
                    .sessionId(SessionId.of(PreviewApp.SESSION_ID)).build();
            // Acquired BEFORE start: a partial start failure must still roll the runtime back.
            acquired.add(runtime);
            runtimeStarter.start(runtime);
            this.runtime = runtime;
            CapabilitySet capabilities =
                    new CapabilitySet(List.of("snapshot", "query", "action", "wait", "screenshot",
                            "ui_runtime_compare"));
            Scene2dHarness harness = new Scene2dHarness(stage, stage, session, scheduler, clock,
                    clock::revision, clock::frame);
            acquired.add(harness);
            this.harness = harness;
            RuntimeObservationSource runtimeObservation =
                    new AgentRuntimeObservationSource(runtime, PreviewApp.SESSION_ID);
            RuntimeComparator runtimeComparator = new RuntimeComparator(runtimeObservation);
            HarnessProtocolService.RuntimeCompareCoordinator runtimeCoordinator =
                    (locator, deadline) -> scheduler.submit(
                            () -> runtimeComparator.compare(
                                    session.snapshot(clock.revision(), clock.frame()),
                                    locator.toCore(), new StrictResolution()),
                            deadline);
            HarnessProtocolService.Session protocolSession = new HarnessProtocolService.Session(
                    harness, new StrictResolution(), waits, capture, capabilities,
                    HarnessProtocolService.TraceController.unsupported(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(runtimeCoordinator));
            // The protocol executor is acquired as soon as it exists, so an executor or
            // protocol construction failure rolls it back too.
            java.util.concurrent.ExecutorService protocolExecutor =
                    Executors.newThreadPerTaskExecutor(
                            Thread.ofVirtual().name("markup-protocol-", 0).factory());
            acquired.add(protocolExecutor);
            this.protocolExecutor = protocolExecutor;
            HarnessProtocolService protocol = new HarnessProtocolService(
                    Map.of(PreviewApp.SESSION_ID, protocolSession), clock, protocolExecutor);
            InMemoryArtifactPublisher artifacts = artifactsFactory.create();
            acquired.add(artifacts);
            this.artifacts = artifacts;
            HarnessMcpServer server = serverFactory.open(protocol, artifacts);
            acquired.add(server);
            this.server = server;
            terminator = Thread.ofPlatform().name("markup-mcp-terminator").daemon().start(() -> {
                server.awaitTermination();
                Gdx.app.postRunnable(Gdx.app::exit);
            });
        } catch (RuntimeException | Error failure) {
            // Staged ownership: every component acquired before the failure is closed in
            // strictly reverse acquisition order (each best-effort), and any cleanup failure
            // is suppressed onto the primary failure.
            RuntimeException cleanup = closeAcquired(acquired);
            if (cleanup != null) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Closes every acquired component in strictly reverse acquisition order (each close is
     * best-effort) and returns the aggregated cleanup failure, or {@code null} when all
     * closes succeeded. */
    private RuntimeException closeAcquired(List<AutoCloseable> acquired) {
        RuntimeException primary = null;
        for (int index = acquired.size() - 1; index >= 0; index--) {
            try {
                componentCloser.close(acquired.get(index));
            } catch (Exception failure) {
                RuntimeException wrapped = new IllegalStateException(
                        "failed to close acquired component", failure);
                if (primary == null) {
                    primary = wrapped;
                } else {
                    primary.addSuppressed(wrapped);
                }
            }
        }
        return primary;
    }

    /** Test seam: starts the agent runtime (injectable to prove partial-start rollback). */
    @FunctionalInterface
    interface RuntimeStarter {
        void start(AgentRuntime runtime);
    }

    /** Test seam: creates the artifact publisher (injectable to prove staged cleanup). */
    @FunctionalInterface
    interface ArtifactsFactory {
        InMemoryArtifactPublisher create();
    }

    /** Test seam: opens the MCP server (injectable to prove staged cleanup). */
    @FunctionalInterface
    interface McpServerFactory {
        HarnessMcpServer open(HarnessProtocolService protocol,
                ArtifactReference.Publisher artifacts);
    }

    /** Returns the semantic sink bridging markup metadata into the harness facade. */
    HarnessSemanticSink semanticSink() {
        return sink;
    }

    /** Returns the live in-memory artifact publisher (package-visible seam for the in-process
     * session readback E2E). */
    InMemoryArtifactPublisher artifacts() {
        return artifacts;
    }

    /**
     * Snapshots on the render thread: direct when already there, a scheduler hop otherwise.
     * The wait engine invokes this on the calling (MCP virtual) thread; reading the Stage
     * directly off the render thread is a silent confinement violation, so off-thread calls
     * submit the snapshot to the render-thread scheduler and block on the hop.
     */
    private SemanticSnapshot snapshotForWait() {
        if (scheduler.isOwnerThread()) {
            return session.snapshot(clock.revision(), clock.frame());
        }
        return scheduler.submit(
                () -> session.snapshot(clock.revision(), clock.frame()),
                Deadline.after(clock, Duration.ofSeconds(30)))
                .toCompletableFuture().join();
    }

    /** Registers markup-declared {@code data-runtime-entity} actors as agent-runtime value
     * sources for the freshly built scene (render thread), explicitly in widget-mirror mode: the
     * property supplier reads the widget's live state back, which validates transport and
     * correlation only and cannot detect a UI/domain divergence.
     *
     * <p>Attachment is a three-phase transaction split across the rebuild: {@link
     * #acquireRuntime} registers the candidate without publishing anything, the caller swaps
     * the stage, and {@link #commitRuntime} publishes the committed owner and retained
     * last-good only after the visible swap succeeded; {@link #rollbackRuntime} undoes an
     * uncommitted attachment. On any ordinary failure the last-good registration is either
     * preserved untouched or reinstated from the retained document/built. On any exceptional
     * cleanup failure — a fallible retirement/close that threw, a candidate close that threw,
     * or a reinstatement that failed (including a duplicate, which may be a partial-close
     * remnant) — the old owner's close is multi-handle and integrity is unknown, so {@link
     * #runtimeLost()} is set conservatively and the caller must stop the preview rather than
     * claim last-good.
     */
    PendingRuntime acquireRuntime(MarkupDocument document, BuiltUi built) {
        MarkupRuntimeSource previous = runtimeSource;
        try {
            MarkupRuntimeSource candidate = register(document, built);
            return new PendingRuntime(candidate, previous, false, document, built);
        } catch (RuntimeException failure) {
            if (!(failure instanceof AgentRuntimeException runtimeFailure)
                    || runtimeFailure.code() != RuntimeErrorCode.DUPLICATE_ENTITY
                    || previous == null) {
                throw failure; // last-good registration untouched
            }
            // The candidate shares entity ids with the live registration: the old ids must be
            // removed before the candidate can be proven. Remove them (retaining the old
            // registration for reinstatement), retry, and on retry failure reinstate the
            // retained last-good registration before reporting the candidate failure.
            try {
                retirementCloser.close(previous);
            } catch (RuntimeException closeFailure) {
                // A fallible multi-handle retirement that threw leaves integrity unknown:
                // reinstate what we can, then classify terminal.
                RuntimeException restoreFailure = attemptRestore();
                if (restoreFailure != null) {
                    closeFailure.addSuppressed(restoreFailure);
                }
                runtimeLost = true;
                throw closeFailure;
            }
            try {
                MarkupRuntimeSource candidate = register(document, built);
                return new PendingRuntime(candidate, previous, true, document, built);
            } catch (RuntimeException retryFailure) {
                RuntimeException restoreFailure = attemptRestore();
                if (restoreFailure != null) {
                    retryFailure.addSuppressed(restoreFailure);
                    runtimeLost = true;
                }
                throw retryFailure;
            }
        }
    }

    /** Commits a pending attachment after the stage swap succeeded: retires the old
     * registration (fallible), then publishes the candidate as the live owner and retained
     * last-good. On retirement failure the candidate is closed and the retained last-good
     * reinstatement attempted with finally-style aggregation (a candidate-close failure never
     * skips the reinstatement attempt); every cleanup failure is suppressed onto the primary
     * retirement failure, nothing is published, and the runtime is conservatively lost. */
    void commitRuntime(PendingRuntime pending) {
        if (!pending.previousRetired && pending.previous != null) {
            try {
                retirementCloser.close(pending.previous);
            } catch (RuntimeException failure) {
                RuntimeException cleanup = null;
                try {
                    pending.closeCandidate();
                } catch (RuntimeException candidateFailure) {
                    cleanup = candidateFailure;
                }
                RuntimeException restoreFailure = attemptRestore();
                if (restoreFailure != null) {
                    if (cleanup == null) {
                        cleanup = restoreFailure;
                    } else {
                        cleanup.addSuppressed(restoreFailure);
                    }
                }
                if (cleanup != null) {
                    failure.addSuppressed(cleanup);
                }
                runtimeLost = true;
                throw failure;
            }
        }
        // Failure-free state publication, after retirement succeeded:
        runtimeSource = pending.candidate;
        lastGoodDocument = pending.document;
        lastGoodBuilt = pending.built;
        pending.committed();
        System.err.println("markup-runtime: {\"mode\":\"widget-mirror\",\"entities\":"
                + pending.candidate.registeredEntities().size() + ",\"bindings\":"
                + pending.candidate.registeredEntities().size() + "}");
        System.err.flush();
    }

    /** Rolls back an uncommitted pending attachment after a later rebuild phase failed: closes
     * the candidate registration (never leaked) and reinstates the last-good registration when
     * the old ids had to be removed to acquire the candidate, with finally-style aggregation.
     * Returns any cleanup failure (candidate close or reinstatement) so the caller can
     * suppress it onto the primary rebuild failure; a cleanup failure leaves integrity unknown
     * and marks the runtime lost. */
    RuntimeException rollbackRuntime(PendingRuntime pending) {
        RuntimeException cleanup = null;
        try {
            pending.closeCandidate();
        } catch (RuntimeException candidateFailure) {
            cleanup = candidateFailure;
        }
        if (pending.previousRetired && !pending.previousRestored) {
            pending.previousRestored = true;
            RuntimeException restoreFailure = attemptRestore();
            if (restoreFailure != null) {
                if (cleanup == null) {
                    cleanup = restoreFailure;
                } else {
                    cleanup.addSuppressed(restoreFailure);
                }
            }
        }
        if (cleanup != null) {
            runtimeLost = true;
        }
        return cleanup;
    }

    private MarkupRuntimeSource register(MarkupDocument document, BuiltUi built) {
        return MarkupRuntimeSource.registerWidgetMirror(
                runtime, document, built, PreviewApp.SESSION_ID);
    }

    /**
     * Attempts to reinstate the retained last-good registration after its ids had to be
     * removed or its owner's close failed. Returns the reinstatement failure, or {@code null}
     * when the full retained set is live again. A duplicate is NOT treated as proof that the
     * old registration is complete — the old owner's close is multi-handle and may have
     * partially closed — so every failure here leaves integrity unknown; callers classify
     * terminal.
     */
    private RuntimeException attemptRestore() {
        if (lastGoodDocument == null || lastGoodBuilt == null) {
            return new IllegalStateException("no retained last-good registration to reinstate");
        }
        try {
            MarkupRuntimeSource restored = lastGoodRegistrar.register(
                    runtime, lastGoodDocument, lastGoodBuilt, PreviewApp.SESSION_ID);
            runtimeSource = restored;
            return null;
        } catch (RuntimeException restoreFailure) {
            return restoreFailure;
        }
    }

    /** Returns the committed runtime registration, or {@code null} before the first commit. */
    MarkupRuntimeSource runtimeSource() {
        return runtimeSource;
    }

    /** Returns the agent runtime backing the preview session (test seam). */
    AgentRuntime runtime() {
        return runtime;
    }

    /** Whether the last-good registration can no longer be trusted as complete: the runtime
     * cannot be kept consistent with the retained scene, so the preview must stop. */
    boolean runtimeLost() {
        return runtimeLost;
    }

    /**
     * One acquired but uncommitted runtime attachment. Owns the candidate registration until
     * committed or rolled back, and tracks whether the old registration had to be removed to
     * acquire it (and is therefore retained for reinstatement). The candidate-close ownership
     * is explicit ({@link #candidateClosed}), so the candidate is closed exactly once no
     * matter which path (commit, rollback, or cleanup aggregation) runs first.
     */
    final class PendingRuntime {
        private final MarkupRuntimeSource candidate;
        private final MarkupDocument document;
        private final BuiltUi built;
        private final MarkupRuntimeSource previous;
        private final boolean previousRetired;
        private boolean candidateClosed;
        private boolean previousRestored;

        PendingRuntime(MarkupRuntimeSource candidate, MarkupRuntimeSource previous,
                boolean previousRetired, MarkupDocument document, BuiltUi built) {
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            this.document = Objects.requireNonNull(document, "document");
            this.built = Objects.requireNonNull(built, "built");
            this.previous = previous;
            this.previousRetired = previousRetired;
        }

        /** Closes the candidate registration exactly once (idempotent) through the test seam. */
        void closeCandidate() {
            if (!candidateClosed) {
                candidateClosed = true;
                candidateCloser.close(candidate);
            }
        }

        /** Marks the candidate committed: ownership transferred to {@code PreviewMcp}. */
        void committed() {
            candidateClosed = true;
        }
    }

    /** Package-visible test seam: closes a retired registration. Production uses the real
     * close; tests inject failures to prove rollback. */
    @FunctionalInterface
    interface RetirementCloser {
        void close(MarkupRuntimeSource retired);
    }

    /** Package-visible test seam: reinstates the retained last-good scene. Production uses
     * {@link MarkupRuntimeSource#registerWidgetMirror}; tests inject failures to prove the
     * terminal path. */
    @FunctionalInterface
    interface LastGoodRegistrar {
        MarkupRuntimeSource register(AgentRuntime runtime, MarkupDocument document,
                BuiltUi built, String uiSessionId);
    }

    /** Package-visible test seam: closes a candidate registration during rollback/cleanup.
     * Production uses the real close; tests inject failures to prove cleanup aggregation and
     * the terminal path. */
    @FunctionalInterface
    interface CandidateCloser {
        void close(MarkupRuntimeSource candidate);
    }

    /** Package-visible test seam: closes one owned component during {@link #close()}. Production
     * uses the component's own close; tests inject failures to prove every close is still
     * attempted and aggregated. */
    @FunctionalInterface
    interface ComponentCloser {
        void close(AutoCloseable component) throws Exception;
    }

    /** Production retirement/restore/candidate-close/component-close behavior; replaced only by
     * tests via the package-visible seams above. */
    RetirementCloser retirementCloser = MarkupRuntimeSource::close;
    LastGoodRegistrar lastGoodRegistrar = MarkupRuntimeSource::registerWidgetMirror;
    CandidateCloser candidateCloser = MarkupRuntimeSource::close;
    ComponentCloser componentCloser = AutoCloseable::close;

    /** Advances the deterministic clock and drains render-thread commands (GL thread). */
    void beforeDraw() {
        scheduler.drain();
        clock.advance(FIXED_STEP);
    }

    /** Publishes the completed rendered frame to capture waiters and the runtime (GL thread). */
    void afterDraw() {
        fence.completedFrame(clock.revision(), clock.frame());
        runtime.beginFrame(PreviewMcp.FIXED_STEP.toNanos());
        runtime.endFrame();
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                runtime.currentEpoch(),
                runtime.latestFrame().orElseThrow().frameId(),
                PreviewApp.SESSION_ID,
                Optional.of(Long.toString(clock.frame())),
                Optional.of(CORRELATION_TOKEN)));
    }

    /** Whether this session has been closed; {@link #close()} is idempotent. */
    private boolean closed;

    @Override public void close() {
        if (closed) {
            return; // idempotent: never re-close owned components (a partially closed owner must
                    // not be closed a second time)
        }
        closed = true;
        RuntimeException primary = null;
        try {
            // Strictly reverse acquisition order (the runtime registration is the newest
            // acquisition): the MCP server, artifact publisher, protocol executor, harness,
            // agent runtime, wait engine, screen capture, frame fence, session, scheduler,
            // and clock each close best-effort, so a throwing close never skips the rest.
            primary = closeOwned(primary, runtimeSource, "runtime registration");
            primary = closeOwned(primary, server, "MCP server");
            primary = closeOwned(primary, artifacts, "artifact publisher");
            primary = closeOwned(primary, protocolExecutor, "protocol executor");
            primary = closeOwned(primary, harness, "scene2d harness");
            primary = closeOwned(primary, runtime, "agent runtime");
            primary = closeOwned(primary, waits, "wait engine");
            primary = closeOwned(primary, capture, "screen capture");
            primary = closeOwned(primary, fence, "frame fence");
            primary = closeOwned(primary, session, "scene2d session");
            primary = closeOwned(primary, scheduler, "render scheduler");
            primary = closeOwned(primary, clock, "controlled clock");
        } finally {
            // Ownership fields are detached regardless of any close failure, so a throwing close
            // can never leave a half-closed owner claimed live or re-closed.
            runtimeSource = null;
            lastGoodDocument = null;
            lastGoodBuilt = null;
        }
        if (primary != null) {
            throw primary;
        }
    }

    /** Closes one owned component best-effort: every component is attempted, the first failure
     * becomes primary, every later failure is suppressed onto it. */
    private RuntimeException closeOwned(RuntimeException primary, AutoCloseable owned,
            String name) {
        if (owned == null) {
            return primary;
        }
        try {
            componentCloser.close(owned);
        } catch (Exception failure) {
            RuntimeException wrapped = new IllegalStateException("failed to close " + name,
                    failure);
            if (primary == null) {
                return wrapped;
            }
            primary.addSuppressed(wrapped);
        }
        return primary;
    }
}
