package dev.gdx.markup.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal synchronous MCP stdio client speaking real SDK JSON-RPC, mirroring the harness's
 * {@code HarnessMcpClient} against the published harness tool catalog (1.1.0).
 */
final class MarkupMcpClient implements Closeable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-11-25";

    private final BufferedReader input;
    private final BufferedWriter output;
    private long requestId;
    private boolean closed;

    private MarkupMcpClient(PreviewProcess process) {
        input = new BufferedReader(new InputStreamReader(
                process.mcpInput(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(
                process.mcpOutput(), StandardCharsets.UTF_8));
    }

    static MarkupMcpClient connect(PreviewProcess process) throws Exception {
        MarkupMcpClient client = new MarkupMcpClient(process);
        JsonNode initialized = client.request("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "markup-e2e", "version", "1.0")));
        if (!"libgdx-ui-harness".equals(initialized.at("/serverInfo/name").asText())) {
            client.close();
            throw new IllegalStateException("Unexpected MCP server identity: " + initialized);
        }
        client.notify("notifications/initialized", Map.of());
        JsonNode listed = client.request("tools/list", Map.of());
        List<String> names = new ArrayList<>();
        listed.path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        if (!names.containsAll(List.of(
                "ui_capabilities", "ui_query", "ui_action", "ui_wait", "ui_screenshot",
                "ui_runtime_compare"))) {
            client.close();
            throw new IllegalStateException(
                    "Expected the published harness tools the client exercises: " + listed);
        }
        return client;
    }

    List<String> capabilities(String sessionId) throws Exception {
        JsonNode content = call("ui_capabilities", Map.of("sessionId", sessionId));
        ArrayList<String> capabilities = new ArrayList<>();
        content.path("capabilities").forEach(item -> capabilities.add(item.asText()));
        return List.copyOf(capabilities);
    }

    JsonNode query(String sessionId, Map<String, Object> locator) throws Exception {
        JsonNode content = call("ui_query", Map.of(
                "sessionId", sessionId,
                "locator", locator));
        requireKind(content, "query-result");
        return content;
    }

    void click(String sessionId, Map<String, Object> locator) throws Exception {
        JsonNode content = call("ui_action", Map.of(
                "sessionId", sessionId,
                "locator", locator,
                "action", Map.of("kind", "click", "pointer", 0, "button", 0, "force", false)));
        requireKind(content, "action-result");
    }

    void fill(String sessionId, Map<String, Object> locator, String value) throws Exception {
        JsonNode content = call("ui_action", Map.of(
                "sessionId", sessionId,
                "locator", locator,
                "action", Map.of("kind", "fill", "value", value, "force", false)));
        requireKind(content, "action-result");
    }

    JsonNode waitFor(String sessionId, Map<String, Object> locator, String condition,
            long deadlineMillis) throws Exception {
        JsonNode content = call("ui_wait", Map.of(
                "sessionId", sessionId,
                "locator", locator,
                "condition", condition,
                "deadlineMillis", deadlineMillis));
        requireKind(content, "wait-result");
        return content;
    }

    JsonNode runtimeCompare(String sessionId, Map<String, Object> locator, long deadlineMillis)
            throws Exception {
        JsonNode content = call("ui_runtime_compare", Map.of(
                "sessionId", sessionId,
                "locator", locator,
                "maxDurationMillis", 5_000,
                "deadlineMillis", deadlineMillis));
        requireKind(content, "runtime-compare-result");
        return content;
    }

    Screenshot screenshot(String sessionId) throws Exception {
        JsonNode content = call("ui_screenshot", Map.of(
                "sessionId", sessionId,
                "maxWidth", 1280,
                "maxHeight", 720,
                "maxPixels", 1280L * 720,
                "maxPngBytes", 4 * 1_024 * 1_024));
        requireKind(content, "screenshot-result");
        JsonNode artifact = content.path("artifact");
        return new Screenshot(
                content.path("width").asInt(),
                content.path("height").asInt(),
                new Artifact(
                        artifact.path("reference").asText(),
                        artifact.path("mediaType").asText(),
                        artifact.path("byteLength").asLong(),
                        artifact.path("sha256").asText()));
    }

    private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
        JsonNode result = request("tools/call", Map.of("name", tool, "arguments", arguments));
        if (result.path("isError").asBoolean()) {
            throw new IllegalStateException("MCP tool failed: " + result);
        }
        JsonNode content = result.path("structuredContent");
        if (!content.isObject()) {
            throw new IllegalStateException("MCP tool omitted structured content: " + result);
        }
        return content;
    }

    private JsonNode request(String method, Map<String, Object> params) throws Exception {
        long id = ++requestId;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        JsonNode message;
        do {
            String line = input.readLine();
            if (line == null) {
                throw new IllegalStateException("MCP stdout closed while awaiting " + method);
            }
            message = JSON.readTree(line);
        } while (!message.has("id"));
        if (message.path("id").asLong() != id) {
            throw new IllegalStateException("Out-of-order MCP response: " + message);
        }
        if (message.has("error")) {
            throw new IllegalStateException("MCP request failed: " + message.path("error"));
        }
        return message.path("result");
    }

    private void notify(String method, Map<String, Object> params) throws Exception {
        send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
    }

    private void send(Map<String, Object> message) throws Exception {
        output.write(JSON.writeValueAsString(message));
        output.newLine();
        output.flush();
    }

    private static void requireKind(JsonNode content, String expected) {
        if (!Objects.equals(expected, content.path("kind").asText())) {
            throw new IllegalStateException("Expected " + expected + ": " + content);
        }
    }

    @Override public void close() throws java.io.IOException {
        if (closed) {
            return;
        }
        closed = true;
        output.close();
        input.close();
    }

    record Artifact(String reference, String mediaType, long byteLength, String sha256) {}

    record Screenshot(int width, int height, Artifact artifact) {}
}
