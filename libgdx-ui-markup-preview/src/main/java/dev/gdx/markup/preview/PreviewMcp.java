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
     * <p>Transactional: on success returns a live owner (the committed registration, whose
     * lifecycle the caller adopts) with the old registration retired. On failure it either
     * leaves the last-good registration untouched — when the candidate never collided with it —
     * or, when the candidate shared entity ids with the live registration and the old ids had
     * to be removed first, reinstalls the retained last-good registration before rethrowing.
     * A thrown call never leaves candidate handles behind (the runtime's own registration is
     * transactional and rolls back its acquisitions).
     */
    MarkupRuntimeSource attachRuntime(MarkupDocument document, BuiltUi built) {
        MarkupRuntimeSource previous = runtimeSource;
        try {
            return commitCandidate(document, built);
        } catch (RuntimeException failure) {
            if (!(failure instanceof AgentRuntimeException runtimeFailure)
                    || runtimeFailure.code() != RuntimeErrorCode.DUPLICATE_ENTITY
                    || previous == null) {
                throw failure;
            }
            // The candidate shares entity ids with the live registration: remove the old ids,
            // retry, and on retry failure restore the retained last-good registration before
            // reporting the candidate failure.
            previous.close();
            runtimeSource = null;
            try {
                return commitCandidate(document, built);
            } catch (RuntimeException retryFailure) {
                restoreLastGood(retryFailure);
                throw retryFailure;
            }
        }
    }

    /** Registers the candidate and, on success, retires the previous registration and adopts
     * the candidate as the committed owner. */
    private MarkupRuntimeSource commitCandidate(MarkupDocument document, BuiltUi built) {
        MarkupRuntimeSource candidate = MarkupRuntimeSource.registerWidgetMirror(
                runtime, document, built, PreviewApp.SESSION_ID);
        MarkupRuntimeSource retired = runtimeSource;
        runtimeSource = candidate;
        lastGoodDocument = document;
        lastGoodBuilt = built;
        if (retired != null) {
            retired.close();
        }
        System.err.println("markup-runtime: {\"mode\":\"widget-mirror\",\"entities\":"
                + candidate.registeredEntities().size() + ",\"bindings\":"
                + candidate.registeredEntities().size() + "}");
        System.err.flush();
        return candidate;
    }

    /** Re-registers the retained last-good document/built and adopts it as the committed owner;
     * always rethrows the original failure (with any restore failure suppressed). */
    private void restoreLastGood(RuntimeException original) {
        if (lastGoodDocument == null || lastGoodBuilt == null) {
            throw original;
        }
        try {
            MarkupRuntimeSource restored = MarkupRuntimeSource.registerWidgetMirror(
                    runtime, lastGoodDocument, lastGoodBuilt, PreviewApp.SESSION_ID);
            runtimeSource = restored;
        } catch (RuntimeException restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
        throw original;
    }

    /** Returns the committed runtime registration, or {@code null} before the first commit. */
    MarkupRuntimeSource runtimeSource() {
        return runtimeSource;
    }

    /** Returns the agent runtime backing the preview session (test seam). */
    AgentRuntime runtime() {
        return runtime;
    }

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
