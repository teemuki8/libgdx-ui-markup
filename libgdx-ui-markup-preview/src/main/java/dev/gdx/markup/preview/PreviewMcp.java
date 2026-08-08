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
import java.time.Duration;
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
    private final HarnessSemanticSink sink;
    private final HarnessMcpServer server;
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
        this.stage = Objects.requireNonNull(stage, "stage");
        clock = new ControlledStageClock(stage, FIXED_STEP);
        scheduler = new RenderThreadScheduler(128);
        session = new Scene2dSession(stage);
        sink = new HarnessSemanticSink(session.semantics(), CORRELATION_TOKEN);
        fence = new Lwjgl3FrameFence(64);
        Lwjgl3ScreenCapture capture = new Lwjgl3ScreenCapture(fence, session::snapshot);
        WaitEngine waits = new WaitEngine(this::snapshotForWait,
                new StrictResolution(), clock, clock);
        runtime = AgentRuntime.builder().sessionId(SessionId.of(PreviewApp.SESSION_ID)).build();
        runtime.start();
        CapabilitySet capabilities =
                new CapabilitySet(List.of("snapshot", "query", "action", "wait", "screenshot",
                        "ui_runtime_compare"));
        Scene2dHarness harness = new Scene2dHarness(stage, stage, session, scheduler, clock,
                clock::revision, clock::frame);
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
        HarnessProtocolService protocol = new HarnessProtocolService(
                Map.of(PreviewApp.SESSION_ID, protocolSession), clock,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("markup-protocol-", 0).factory()));
        server = HarnessMcpServer.open(protocol, new TmpDirArtifactPublisher(),
                System.in, System.out);
        terminator = Thread.ofPlatform().name("markup-mcp-terminator").daemon().start(() -> {
            server.awaitTermination();
            Gdx.app.postRunnable(Gdx.app::exit);
        });
    }

    /** Returns the semantic sink bridging markup metadata into the harness facade. */
    HarnessSemanticSink semanticSink() {
        return sink;
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
     * uncommitted attachment. On any failure the last-good registration is either preserved
     * untouched or reinstalled from the retained document/built; {@link #runtimeLost()} is
     * set when even reinstatement is impossible (the caller must stop the preview).
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
            // registration for restore), retry, and on retry failure reinstate the retained
            // last-good registration before reporting the candidate failure.
            try {
                retirementCloser.close(previous);
            } catch (RuntimeException closeFailure) {
                restoreOrMarkLost();
                throw closeFailure;
            }
            try {
                MarkupRuntimeSource candidate = register(document, built);
                return new PendingRuntime(candidate, previous, true, document, built);
            } catch (RuntimeException retryFailure) {
                restoreOrMarkLost();
                throw retryFailure;
            }
        }
    }

    /** Commits a pending attachment after the stage swap succeeded: retires the old
     * registration (fallible), then publishes the candidate as the live owner and retained
     * last-good. On retirement failure the candidate is closed, nothing is published, the old
     * registration is restored (or {@link #runtimeLost} is set), and the failure rethrown. */
    void commitRuntime(PendingRuntime pending) {
        if (!pending.previousRetired && pending.previous != null) {
            try {
                retirementCloser.close(pending.previous);
            } catch (RuntimeException failure) {
                pending.closeCandidate();
                restoreOrMarkLost();
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
     * the old ids had to be removed to acquire the candidate. */
    void rollbackRuntime(PendingRuntime pending) {
        pending.closeCandidate();
        if (pending.previousRetired && !pending.previousRestored) {
            pending.previousRestored = true;
            restoreOrMarkLost();
        }
    }

    private MarkupRuntimeSource register(MarkupDocument document, BuiltUi built) {
        return MarkupRuntimeSource.registerWidgetMirror(
                runtime, document, built, PreviewApp.SESSION_ID);
    }

    /**
     * Re-establishes the retained last-good registration after its ids had to be removed.
     * Returns when the runtime is consistent again — the old registration was reinstated, or
     * it is provably still live (a reinstatement collided with it) — and sets {@link
     * #runtimeLost} when neither is possible.
     */
    private void restoreOrMarkLost() {
        if (lastGoodDocument == null || lastGoodBuilt == null) {
            runtimeLost = true;
            return;
        }
        try {
            MarkupRuntimeSource restored = lastGoodRegistrar.register(
                    runtime, lastGoodDocument, lastGoodBuilt, PreviewApp.SESSION_ID);
            runtimeSource = restored;
            return;
        } catch (AgentRuntimeException duplicate) {
            if (duplicate.code() == RuntimeErrorCode.DUPLICATE_ENTITY) {
                return; // the old registration is still live; nothing to reinstate
            }
        } catch (RuntimeException ignored) {
            // fall through to mark the runtime lost
        }
        runtimeLost = true;
    }

    /** Returns the committed runtime registration, or {@code null} before the first commit. */
    MarkupRuntimeSource runtimeSource() {
        return runtimeSource;
    }

    /** Returns the agent runtime backing the preview session (test seam). */
    AgentRuntime runtime() {
        return runtime;
    }

    /** Whether the last-good registration can no longer be reinstated: the runtime cannot be
     * kept consistent with the retained scene, so the preview must stop. */
    boolean runtimeLost() {
        return runtimeLost;
    }

    /**
     * One acquired but uncommitted runtime attachment. Owns the candidate registration until
     * committed or rolled back, and tracks whether the old registration had to be removed to
     * acquire it (and is therefore retained for reinstatement).
     */
    static final class PendingRuntime {
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

        /** Closes the candidate registration exactly once (idempotent). */
        void closeCandidate() {
            if (!candidateClosed) {
                candidateClosed = true;
                candidate.close();
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

    /** Production retirement/restore behavior; replaced only by tests via the package-visible
     * seams above. */
    RetirementCloser retirementCloser = MarkupRuntimeSource::close;
    LastGoodRegistrar lastGoodRegistrar = MarkupRuntimeSource::registerWidgetMirror;

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

    @Override public void close() {
        if (runtimeSource != null) {
            runtimeSource.close();
            runtimeSource = null;
        }
        lastGoodDocument = null;
        lastGoodBuilt = null;
        runtime.close();
        server.close();
        session.close();
        scheduler.close();
        clock.close();
    }
}
