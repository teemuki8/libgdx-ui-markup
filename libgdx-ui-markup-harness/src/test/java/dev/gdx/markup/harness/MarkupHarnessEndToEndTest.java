package dev.gdx.markup.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.fasterxml.jackson.databind.JsonNode;
import dev.gdx.markup.core.BuiltUi;
import dev.gdx.markup.core.DefaultSkin;
import dev.gdx.markup.core.MarkupBuilder;
import dev.gdx.markup.core.MarkupDocument;
import dev.gdx.markup.core.MarkupParser;
import dev.gdx.markup.core.NoopSink;
import dev.gdx.markup.core.style.CssParser;
import dev.gdx.markup.runtime.MarkupRuntimeSource;
import dev.gdx.uiharness.agentruntime.AgentRuntimeObservationSource;
import dev.gdx.uiharness.core.locator.StrictResolution;
import dev.gdx.uiharness.core.locator.TestIdLocator;
import dev.gdx.uiharness.core.model.Bounds;
import dev.gdx.uiharness.core.model.Role;
import dev.gdx.uiharness.core.model.RuntimeBinding;
import dev.gdx.uiharness.core.model.SemanticNode;
import dev.gdx.uiharness.core.model.SemanticSnapshot;
import dev.gdx.uiharness.core.model.SemanticState;
import dev.gdx.uiharness.core.runtime.DisplayedRuntimeComparison;
import dev.gdx.uiharness.core.runtime.RuntimeComparator;
import io.github.teemuki8.libgdx.agent.runtime.core.AgentRuntime;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityId;
import io.github.teemuki8.libgdx.agent.runtime.core.EntityType;
import io.github.teemuki8.libgdx.agent.runtime.core.RuntimeValues;
import io.github.teemuki8.libgdx.agent.runtime.core.SessionId;
import io.github.teemuki8.libgdx.agent.runtime.core.UiFrameCorrelation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end proof that a UI declared entirely in markup is drivable through the harness MCP:
 * locate {@code role=button name=Save} with {@code testId=save} (semantics by construction),
 * click the checkbox through the real input path, observe its checked state, and capture a PNG.
 *
 * <p>The in-process tests exercise the published {@link AgentRuntimeObservationSource} directly
 * (the adapter the preview wires into {@code ui_runtime_compare}) to pin which correlation
 * statuses are actually reachable: a token mismatch or a correlation recorded against a frame
 * that is no longer the latest leaves the binding unprovable, so the adapter emits no
 * observation and the comparator reports {@code UNAVAILABLE} — never {@code STALE} or
 * {@code UNCORRELATED} through this source. One test registers a real markup-built actor tree
 * through {@code MarkupRuntimeSource.registerAuthoritative} and proves a deliberate UI/domain
 * divergence reports {@code MISMATCH} with the expected displayed and runtime values.
 */
final class MarkupHarnessEndToEndTest {
    private static final String SESSION_ID = "markup-preview";
    /** The correlation token the preview records every frame under (sink == correlation). */
    private static final String CORRELATION_TOKEN = "markup-preview-frame";

    @Test
    @Timeout(120)
    void markupDeclaredUiIsDrivableThroughTheHarnessMcp() throws Exception {
        try (PreviewProcess preview = PreviewProcess.launch()) {
            preview.awaitOkStatus(Duration.ofSeconds(30));
            assertTrue(preview.capturedStderr().contains(
                            "markup-runtime: {\"mode\":\"widget-mirror\",\"entities\":1,\"bindings\":1}"),
                    "the preview registers the markup-declared entity in explicit widget-mirror mode, got: "
                            + preview.capturedStderr());
            try (MarkupMcpClient client = MarkupMcpClient.connect(preview)) {
                assertTrue(client.capabilities(SESSION_ID).containsAll(
                        java.util.List.of("query", "action", "wait", "screenshot")));

                JsonNode query = client.query(SESSION_ID, Map.of(
                        "kind", "filter",
                        "locator", Map.of("kind", "role", "role", "button"),
                        "filter", Map.of("kind", "name",
                                "match", Map.of("mode", "exact", "source", "Save"))));
                assertEquals(1, query.path("matchCount").asInt(),
                        "exactly one Save button is found by role and accessible name");
                JsonNode match = query.path("matches").get(0);
                assertEquals("save", match.path("testId").asText(),
                        "the markup id became the harness test identifier");
                assertEquals("button", match.path("role").asText());

                clickCheckboxAndObserveState(client);

                // The same live value the agent-runtime entity reads is harness-visible:
                // fill the markup textfield through the real input path and observe the text.
                Map<String, Object> username = Map.of("kind", "test-id", "testId", "username");
                client.fill(SESSION_ID, username, "Alice");
                JsonNode userNode = client.query(SESSION_ID, username);
                assertEquals(1, userNode.path("matchCount").asInt());
                assertEquals("Alice", userNode.path("matches").get(0).path("text").asText(),
                        "the runtime entity's value source is drivable through the harness");

                MarkupMcpClient.Screenshot screenshot = client.screenshot(SESSION_ID);
                assertEquals(1280, screenshot.width());
                assertEquals(720, screenshot.height());
                assertTrue(screenshot.artifact().byteLength() > 100,
                        "screenshot payload is non-trivial");
                byte[] png = readBack(screenshot.artifact());
                assertEquals(screenshot.artifact().byteLength(), png.length);
                assertTrue(isPng(png), "the screenshot payload is a PNG");
            }
            preview.awaitCleanExit();
        }
    }

    /**
     * The final hop of the three-library story: a {@code data-runtime-entity} widget's displayed
     * value compares EQUAL against its agent-runtime observation through the harness
     * {@code ui_runtime_compare} tool, with typed frame correlation.
     */
    @Test
    @Timeout(120)
    void markupRuntimeEntityComparesThroughHarnessMcp() throws Exception {
        try (PreviewProcess preview = PreviewProcess.launch()) {
            preview.awaitOkStatus(Duration.ofSeconds(30));
            try (MarkupMcpClient client = MarkupMcpClient.connect(preview)) {
                Map<String, Object> username = Map.of("kind", "test-id", "testId", "username");
                client.fill(SESSION_ID, username, "Ada");

                JsonNode comparison = client.runtimeCompare(SESSION_ID, username, 5_000);
                assertEquals("EQUAL", comparison.path("status").asText(),
                        "the displayed text correlates with the runtime value on the proven frame");
                assertEquals("user", comparison.path("entityId").asText(),
                        "the markup data-runtime-entity became the harness entity id");
                assertEquals("value", comparison.path("propertyId").asText());
                assertEquals("Ada", comparison.path("displayedValue").asText());
                assertEquals("Ada", comparison.path("runtimeValue").asText());
                assertEquals(comparison.path("displayedFrame").asLong(),
                        comparison.path("runtimeFrame").asLong(),
                        "the exposed frames prove the correlation on the EQUAL path");
            }
            preview.awaitCleanExit();
        }
    }

    /**
     * Positive baseline: when the binding's correlation token equals the recorded correlation
     * token and the frame was correlated before the observation, the adapter proves the frame
     * and the comparator reports {@code EQUAL}.
     */
    @Test
    void provenCorrelationComparesEqualThroughTheAgentRuntimeSource() {
        try (AgentRuntime runtime = runtimeWithUserEntity()) {
            captureCorrelatedFrame(runtime, 1);
            DisplayedRuntimeComparison comparison = compareThroughSource(runtime, 1, "Ada");
            assertEquals(DisplayedRuntimeComparison.Status.EQUAL, comparison.status(),
                    "a provably correlated frame compares EQUAL through AgentRuntimeObservationSource");
            assertEquals(CORRELATION_TOKEN, comparison.correlationId());
            assertEquals(1L, comparison.displayedFrame());
            assertEquals(1L, comparison.runtimeFrame());
            assertTrue(comparison.details().isEmpty(),
                    "the status model exposes no extra message for the proven path");
        }
    }

    /**
     * Token mismatch: the sink's correlation token differs from the token the application
     * recorded its {@code UiFrameCorrelation}s under. The adapter can prove no frame, emits no
     * observation, and the comparator reports {@code UNAVAILABLE} — never {@code STALE} or
     * {@code UNCORRELATED} through this source.
     */
    @Test
    void correlationTokenMismatchIsUnavailableNotStaleOrUncorrelated() {
        try (AgentRuntime runtime = runtimeWithUserEntity()) {
            captureCorrelatedFrame(runtime, 1, "application-owned-token");
            DisplayedRuntimeComparison comparison = compareThroughSource(runtime, 2, "Ada");
            assertEquals(DisplayedRuntimeComparison.Status.UNAVAILABLE, comparison.status(),
                    "a token mismatch leaves the binding unprovable: the source emits no observation");
            assertNull(comparison.runtimeFrame(),
                    "no runtime frame is claimed when the correlation cannot be proven");
            assertEquals(CORRELATION_TOKEN, comparison.correlationId(),
                    "the result still names the binding's correlation token so the consumer "
                            + "can verify it against the recorded correlations");
            assertTrue(comparison.details().isEmpty(),
                    "the status model exposes no extra message for the unprovable path");
        }
    }

    /**
     * Reversed drain/frame-order: the correlation is recorded against a frame that is no longer
     * the latest when the observation runs (the frame capture outpaced the correlation
     * recording). The adapter can prove no frame for the latest runtime frame, emits no
     * observation, and the comparator reports {@code UNAVAILABLE} — never {@code STALE} or
     * {@code UNCORRELATED} through this source.
     */
    @Test
    void reversedFrameCorrelationOrderIsUnavailableNotStaleOrUncorrelated() {
        try (AgentRuntime runtime = runtimeWithUserEntity()) {
            captureCorrelatedFrame(runtime, 1);
            captureFrame(runtime);
            DisplayedRuntimeComparison comparison = compareThroughSource(runtime, 2, "Ada");
            assertEquals(DisplayedRuntimeComparison.Status.UNAVAILABLE, comparison.status(),
                    "a correlation recorded against a frame that is no longer the latest leaves "
                            + "the latest frame unprovable: the source emits no observation");
            assertNull(comparison.runtimeFrame(),
                    "no runtime frame is claimed when the correlation cannot be proven");
            assertEquals(CORRELATION_TOKEN, comparison.correlationId());
            assertTrue(comparison.details().isEmpty());
        }
    }

    /**
     * Issue #9 acceptance proof at the integration level: a markup UI registered through
     * {@code MarkupRuntimeSource.registerAuthoritative} publishes the supplied domain value
     * ("Carol") while the actor displays a different value ("Alice"). Driving the real
     * {@link AgentRuntimeObservationSource} and {@link RuntimeComparator} over an explicitly
     * correlated frame reports {@code MISMATCH} with the expected displayed and runtime values —
     * widget mirror could never detect this divergence.
     */
    @Test
    void authoritativeMismatchIsReportedThroughTheRuntimeComparator() throws Exception {
        HarnessGdxTestHost.run(() -> {
            Skin skin = DefaultSkin.create();
            MarkupDocument document = new MarkupParser().parse("""
                    <ui>
                      <table>
                        <textfield id="user" data-runtime-entity="user"/>
                      </table>
                    </ui>
                    """);
            BuiltUi built = MarkupBuilder.build(
                    document, new CssParser().parse(""), skin, new NoopSink());
            Group root = built.root();
            TextField field = (TextField) root.findActor("user");
            field.setText("Alice");

            try (AgentRuntime runtime = newRuntime()) {
                try (MarkupRuntimeSource source = MarkupRuntimeSource.registerAuthoritative(
                        runtime, document, built, SESSION_ID,
                        (entityId, propertyId, actor) -> {
                            assertEquals("user", entityId);
                            assertEquals("value", propertyId);
                            assertSame(field, actor,
                                    "the resolver receives the built actor for correlation");
                            return () -> RuntimeValues.string("Carol");
                        })) {
                    assertEquals(List.of("user"), source.registeredEntities(),
                            "authoritative mode registers the markup entity");
                    captureCorrelatedFrame(runtime, 1);
                    DisplayedRuntimeComparison comparison = compareThroughSource(
                            runtime, 1, "Alice");
                    assertEquals(DisplayedRuntimeComparison.Status.MISMATCH, comparison.status(),
                            "a UI/domain divergence reports MISMATCH on a proven frame");
                    assertEquals("user", comparison.entityId());
                    assertEquals("value", comparison.propertyId());
                    assertEquals("Alice", comparison.displayedValue());
                    assertEquals("Carol", comparison.runtimeValue());
                    assertEquals(1L, comparison.displayedFrame());
                    assertEquals(1L, comparison.runtimeFrame(),
                            "the correlated frame proves both sides of the comparison");
                }
            }
        });
    }

    private static AgentRuntime newRuntime() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of(SESSION_ID)).build();
        runtime.start();
        return runtime;
    }

    private static AgentRuntime runtimeWithUserEntity() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of(SESSION_ID)).build();
        runtime.start();
        runtime.entities().register(
                EntityId.of("user"), EntityType.of("widget"), () -> "user",
                inspector -> inspector.property("value", () -> RuntimeValues.string("Ada")));
        return runtime;
    }

    private static void captureFrame(AgentRuntime runtime) {
        runtime.beginFrame(Duration.ofMillis(16).toNanos());
        runtime.endFrame();
    }

    private static void captureCorrelatedFrame(AgentRuntime runtime, long harnessFrame) {
        captureCorrelatedFrame(runtime, harnessFrame, CORRELATION_TOKEN);
    }

    private static void captureCorrelatedFrame(AgentRuntime runtime, long harnessFrame,
            String token) {
        captureFrame(runtime);
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                runtime.currentEpoch(),
                runtime.latestFrame().orElseThrow().frameId(),
                SESSION_ID,
                Optional.of(Long.toString(harnessFrame)),
                Optional.of(token)));
    }

    /** Compares the {@code user} binding's displayed text against the adapter's observation. */
    private static DisplayedRuntimeComparison compareThroughSource(
            AgentRuntime runtime, long snapshotFrame, String displayedText) {
        AgentRuntimeObservationSource source =
                new AgentRuntimeObservationSource(runtime, SESSION_ID);
        RuntimeBinding binding =
                new RuntimeBinding("user", "value", null, null, CORRELATION_TOKEN);
        SemanticNode node = new SemanticNode("user", null, List.of(), Role.TEXT_FIELD,
                "user", displayedText, null, "user", null, "TextField",
                new SemanticState(true, true, Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        false, true, 1.0, false, true, true),
                new Bounds(0, 0, 100, 30), new Bounds(0, 0, 100, 30),
                new Bounds(0, 0, 100, 30), 0, Map.of(), binding);
        SemanticSnapshot snapshot =
                new SemanticSnapshot(1, snapshotFrame, "user", Map.of("user", node));
        return new RuntimeComparator(source).compare(
                snapshot, new TestIdLocator("user"), new StrictResolution());
    }

    private static void clickCheckboxAndObserveState(MarkupMcpClient client) throws Exception {
        Map<String, Object> remember = Map.of("kind", "test-id", "testId", "remember");
        JsonNode before = client.query(SESSION_ID, remember);
        assertEquals(1, before.path("matchCount").asInt());

        client.click(SESSION_ID, remember);

        JsonNode waited = client.waitFor(SESSION_ID, Map.of(
                "kind", "filter",
                "locator", remember,
                "filter", Map.of("kind", "state", "state", "checked", "expected", true)),
                "present", 5_000);
        assertEquals(1, waited.path("matchCount").asInt(),
                "the checkbox became checked through the real input path");
    }

    private static byte[] readBack(MarkupMcpClient.Artifact artifact) throws Exception {
        String sha256 = artifact.sha256();
        assertNotNull(sha256);
        Path payload = Path.of(System.getProperty("java.io.tmpdir"),
                "gdx-ui-markup-artifacts", sha256);
        assertTrue(Files.isRegularFile(payload), "artifact persisted by digest: " + payload);
        return Files.readAllBytes(payload);
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }
}
