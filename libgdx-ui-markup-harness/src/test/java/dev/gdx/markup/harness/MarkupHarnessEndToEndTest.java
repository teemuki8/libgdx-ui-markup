package dev.gdx.markup.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end proof that a UI declared entirely in markup is drivable through the harness MCP:
 * locate {@code role=button name=Save} with {@code testId=save} (semantics by construction),
 * click the checkbox through the real input path, observe its checked state, and capture a PNG.
 */
final class MarkupHarnessEndToEndTest {
    private static final String SESSION_ID = "markup-preview";

    @Test
    @Timeout(120)
    void markupDeclaredUiIsDrivableThroughTheHarnessMcp() throws Exception {
        try (PreviewProcess preview = PreviewProcess.launch()) {
            preview.awaitOkStatus(Duration.ofSeconds(30));
            assertTrue(preview.capturedStderr().contains(
                            "markup-runtime: {\"entities\":1,\"bindings\":1}"),
                    "the markup-declared runtime entity is registered, got: "
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
            }
            preview.awaitCleanExit();
        }
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
