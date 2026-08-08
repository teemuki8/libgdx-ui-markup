package dev.gdx.markup.preview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.markup.core.MarkupException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One bounded {@code markup-status: {...}} line on stdout, parsed by the IDEA plugin and by CI
 * harnesses. Schema version 2 carries versioned, typed fields: {@code schemaVersion}, {@code ok},
 * {@code kind}, {@code elementPath}, {@code line}, {@code column}, {@code message}, {@code nodes}.
 * Success carries the actor count; failures carry the typed kind, element path, source location,
 * and the raw diagnostic message — never a pre-formatted string that duplicates the path or
 * coordinates already present in their own fields. Every string is truncated to
 * {@link #MAX_STRING_LENGTH} before serialization, so the emitted line is always bounded.
 */
public record MarkupStatus(
        int schemaVersion,
        boolean ok,
        String kind,
        String elementPath,
        int line,
        int column,
        String message,
        int nodes) {

    /** Current wire schema version; the IDEA parser accepts exactly this version. */
    public static final int SCHEMA_VERSION = 2;

    /** Stable kind for failures that are not markup diagnostics (for example I/O errors). */
    public static final String GENERIC_KIND = "GENERIC";

    /** Upper bound for every string field; longer values are truncated before serialization. */
    public static final int MAX_STRING_LENGTH = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Builds the success status. */
    public static MarkupStatus ok(int nodes) {
        return new MarkupStatus(SCHEMA_VERSION, true, null, null, 0, 0, null, nodes);
    }

    /** Builds a typed failure status from a markup diagnostic. */
    public static MarkupStatus error(MarkupException failure) {
        return new MarkupStatus(
                SCHEMA_VERSION,
                false,
                failure.kind().name(),
                failure.elementPath(),
                failure.line(),
                failure.column(),
                failure.getMessage(),
                0);
    }

    /** Builds a generic (non-markup) failure status with the stable kind and no location. */
    public static MarkupStatus error(String message) {
        return new MarkupStatus(SCHEMA_VERSION, false, GENERIC_KIND, "", 0, 0, message, 0);
    }

    /** Validates the version and truncates every string field to the bounded length. */
    public MarkupStatus {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported status schema version " + schemaVersion);
        }
        kind = bound(kind);
        elementPath = bound(elementPath);
        message = bound(message);
    }

    private static String bound(String value) {
        if (value == null || value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STRING_LENGTH);
    }

    /** Serializes the status line (without the {@code markup-status: } prefix). */
    public String json() {
        try {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            fields.put("schemaVersion", schemaVersion);
            fields.put("ok", ok);
            if (ok) {
                fields.put("nodes", nodes);
            } else {
                fields.put("kind", kind == null ? "" : kind);
                fields.put("elementPath", elementPath == null ? "" : elementPath);
                fields.put("line", line);
                fields.put("column", column);
                fields.put("message", message == null ? "" : message);
            }
            return JSON.writeValueAsString(Map.copyOf(fields));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to encode status", failure);
        }
    }
}
