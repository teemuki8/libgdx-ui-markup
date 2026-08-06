package dev.gdx.markup.preview;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.harness.HarnessSemanticSink;
import dev.gdx.markup.runtime.MarkupRuntimeSource;
import dev.gdx.uiharness.core.locator.StrictResolution;
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
 * fixture wiring against the published 1.0.0 APIs.
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
    private MarkupRuntimeSource runtimeSource;

    PreviewMcp(Stage stage) {
        this.stage = Objects.requireNonNull(stage, "stage");
        clock = new ControlledStageClock(stage, FIXED_STEP);
        scheduler = new RenderThreadScheduler(128);
        session = new Scene2dSession(stage);
        sink = new HarnessSemanticSink(session.semantics());
        fence = new Lwjgl3FrameFence(64);
        Lwjgl3ScreenCapture capture = new Lwjgl3ScreenCapture(fence, session::snapshot);
        WaitEngine waits = new WaitEngine(
                () -> session.snapshot(clock.revision(), clock.frame()),
                new StrictResolution(), clock, clock);
        CapabilitySet capabilities =
                new CapabilitySet(List.of("snapshot", "query", "action", "wait", "screenshot"));
        Scene2dHarness harness = new Scene2dHarness(stage, stage, session, scheduler, clock,
                clock::revision, clock::frame);
        HarnessProtocolService.Session protocolSession = new HarnessProtocolService.Session(
                harness, new StrictResolution(), waits, capture, capabilities,
                HarnessProtocolService.TraceController.unsupported());
        HarnessProtocolService protocol = new HarnessProtocolService(
                Map.of(PreviewApp.SESSION_ID, protocolSession), clock,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("markup-protocol-", 0).factory()));
        server = HarnessMcpServer.open(protocol, new TmpDirArtifactPublisher(),
                System.in, System.out);
        runtime = AgentRuntime.builder().sessionId(SessionId.of(PreviewApp.SESSION_ID)).build();
        runtime.start();
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
     * Registers markup-declared {@code data-runtime-entity} actors as agent-runtime value
     * sources for the freshly built scene (render thread). Old registrations are closed first.
     */
    void attachRuntime(MarkupDocument document, BuiltUi built) {
        if (runtimeSource != null) {
            runtimeSource.close();
        }
        runtimeSource = MarkupRuntimeSource.register(runtime, document, built,
                PreviewApp.SESSION_ID);
        System.err.println("markup-runtime: {\"entities\":"
                + runtimeSource.registeredEntities().size() + ",\"bindings\":"
                + runtimeSource.registeredEntities().size() + "}");
        System.err.flush();
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
        runtime.close();
        server.close();
        session.close();
        scheduler.close();
        clock.close();
    }
}
