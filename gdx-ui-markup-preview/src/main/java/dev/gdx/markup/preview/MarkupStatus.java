package dev.gdx.markup.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One bounded {@code markup-status: {...}} line on stdout, parsed by the IDEA plugin and by
 * CI harnesses. Success carries the node count; failure carries the typed message and location.
 */
public record MarkupStatus(boolean ok, String message, int line, int column, int nodes) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Builds the success status. */
    public static MarkupStatus ok(int nodes) {
        return new MarkupStatus(true, null, 0, 0, nodes);
    }

    /** Builds the failure status. */
    public static MarkupStatus error(String message, int line, int column) {
        return new MarkupStatus(false, message, line, column, 0);
    }

    /** Serializes the status line (without the {@code markup-status: } prefix). */
    public String json() {
        try {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            fields.put("ok", ok);
            if (ok) {
                fields.put("nodes", nodes);
            } else {
                fields.put("message", message == null ? "" : message);
                fields.put("line", line);
                fields.put("column", column);
            }
            return JSON.writeValueAsString(Map.copyOf(fields));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("unable to encode status", failure);
        }
    }
}
