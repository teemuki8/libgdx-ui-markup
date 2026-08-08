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

    /**
     * Validates the schema-v2 invariants and truncates every string field to the bounded
     * length. A success status must not carry any error identity or location and needs
     * nonnegative nodes; an error status needs a stable nonblank kind, a non-null bounded
     * message, nonnegative line/column, zero nodes, and carries an element path only when the
     * kind is not the generic one (generic failures have no location). Pathless markup
     * diagnostics (parse-level {@code TOO_LARGE}/{@code MALFORMED_XML}, CSS property
     * validation) are accepted: their empty path means "no element context".
     */
    public MarkupStatus {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported status schema version " + schemaVersion);
        }
        kind = bound(kind);
        elementPath = bound(elementPath);
        message = bound(message);
        if (ok) {
            if (kind != null || elementPath != null || message != null
                    || line != 0 || column != 0) {
                throw new IllegalArgumentException(
                        "success status must not carry kind, path, message, or location");
            }
            if (nodes < 0) {
                throw new IllegalArgumentException(
                        "success status requires nonnegative nodes");
            }
        } else {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException(
                        "error status requires a stable nonblank kind");
            }
            if (message == null) {
                throw new IllegalArgumentException("error status requires a message");
            }
            if (line < 0 || column < 0) {
                throw new IllegalArgumentException(
                        "error status requires nonnegative line and column");
            }
            if (nodes != 0) {
                throw new IllegalArgumentException("error status must not carry nodes");
            }
            String path = elementPath == null ? "" : elementPath;
            if (GENERIC_KIND.equals(kind) && !path.isEmpty()) {
                throw new IllegalArgumentException(
                        "generic errors carry no element path");
            }
        }
    }

    /**
     * Truncates a value to {@link #MAX_STRING_LENGTH} UTF-16 units, backing off by one unit
     * when the cut would split a surrogate pair so the result never ends in a dangling
     * high or low surrogate.
     */
    private static String bound(String value) {
        if (value == null || value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        int cut = MAX_STRING_LENGTH;
        if (Character.isHighSurrogate(value.charAt(cut - 1))
                && Character.isLowSurrogate(value.charAt(cut))) {
            cut--;
        }
        return value.substring(0, cut);
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
