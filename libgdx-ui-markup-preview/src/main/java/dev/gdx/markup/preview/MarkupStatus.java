package dev.gdx.markup.preview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.markup.core.ComponentTraceFrame;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupSourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * One bounded {@code markup-status: {...}} line consumed by the IDEA plugin and CI harnesses.
 * Schema version 3 carries typed source, attribute, expected/received, suggestion, consequence,
 * and component-trace context for failures. Success carries only the concrete actor count.
 */
public record MarkupStatus(
        int schemaVersion,
        boolean ok,
        String kind,
        String source,
        String elementPath,
        int line,
        int column,
        String attribute,
        String expected,
        String received,
        String suggestion,
        String consequence,
        List<ComponentTraceFrame> componentTrace,
        String message,
        int nodes) {
    /** Current wire schema version; the IDEA parser accepts exactly this version. */
    public static final int SCHEMA_VERSION = 3;

    /** Stable kind for failures that are not markup diagnostics (for example I/O errors). */
    public static final String GENERIC_KIND = "GENERIC";

    /** Stable kind for terminal failures after which the preview stops serving. */
    public static final String TERMINAL_KIND = "TERMINAL";

    /** Upper bound for every serialized string field. */
    public static final int MAX_STRING_LENGTH = 2000;

    /** Upper bound for component-trace frames. */
    public static final int MAX_TRACE_FRAMES = 16;

    /** Upper bound for aggregate trace string content. */
    public static final int MAX_TRACE_LENGTH = 16_384;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Validates and normalizes the immutable schema-v3 status. */
    public MarkupStatus {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported status schema version " + schemaVersion);
        }
        kind = bound(kind);
        source = bound(source);
        elementPath = bound(elementPath);
        attribute = bound(attribute);
        expected = bound(expected);
        received = bound(received);
        suggestion = bound(suggestion);
        consequence = bound(consequence);
        message = bound(message);
        componentTrace = normalizeTrace(componentTrace);

        if (ok) {
            if (kind != null
                    || source != null
                    || elementPath != null
                    || attribute != null
                    || expected != null
                    || received != null
                    || suggestion != null
                    || consequence != null
                    || message != null
                    || !componentTrace.isEmpty()
                    || line != 0
                    || column != 0) {
                throw new IllegalArgumentException(
                        "success status must not carry error context or location");
            }
            if (nodes < 0) {
                throw new IllegalArgumentException("success status requires nonnegative nodes");
            }
        } else {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("error status requires a stable nonblank kind");
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
            source = empty(source);
            elementPath = empty(elementPath);
            attribute = empty(attribute);
            expected = empty(expected);
            received = empty(received);
            suggestion = empty(suggestion);
            consequence = empty(consequence);
            if (GENERIC_KIND.equals(kind) && !elementPath.isEmpty()) {
                throw new IllegalArgumentException("generic errors carry no element path");
            }
        }
    }

    /** Compatibility constructor for the legacy-shaped Java call site. */
    public MarkupStatus(
            int schemaVersion,
            boolean ok,
            String kind,
            String elementPath,
            int line,
            int column,
            String message,
            int nodes) {
        this(
                schemaVersion,
                ok,
                kind,
                ok ? null : "",
                elementPath,
                line,
                column,
                ok ? null : "",
                ok ? null : "",
                ok ? null : "",
                ok ? null : "",
                ok ? null : "",
                List.of(),
                message,
                nodes);
    }

    /** Builds the success status. */
    public static MarkupStatus ok(int nodes) {
        return new MarkupStatus(
                SCHEMA_VERSION,
                true,
                null,
                null,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                nodes);
    }

    /** Builds a typed failure status from a markup diagnostic. */
    public static MarkupStatus error(MarkupException failure) {
        Objects.requireNonNull(failure, "failure");
        return new MarkupStatus(
                SCHEMA_VERSION,
                false,
                failure.kind().name(),
                failure.source(),
                failure.elementPath(),
                failure.line(),
                failure.column(),
                failure.attribute(),
                failure.expected(),
                failure.received(),
                failure.suggestion(),
                failure.consequence(),
                failure.componentTrace(),
                failure.getMessage(),
                0);
    }

    /** Builds a generic non-markup failure with empty structured context. */
    public static MarkupStatus error(String message) {
        return generic(GENERIC_KIND, message);
    }

    /** Builds a terminal failure with empty structured context. */
    public static MarkupStatus terminal(String message) {
        return generic(TERMINAL_KIND, message);
    }

    private static MarkupStatus generic(String kind, String message) {
        return new MarkupStatus(
                SCHEMA_VERSION,
                false,
                kind,
                "",
                "",
                0,
                0,
                "",
                "",
                "",
                "",
                "",
                List.of(),
                message,
                0);
    }

    /**
     * Truncates a value to {@link #MAX_STRING_LENGTH} UTF-16 units without splitting a
     * surrogate pair. Package-visible so other preview output applies the identical bound.
     */
    static String bound(String value) {
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

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static List<ComponentTraceFrame> normalizeTrace(List<ComponentTraceFrame> trace) {
        List<ComponentTraceFrame> supplied = List.copyOf(trace);
        if (supplied.size() > MAX_TRACE_FRAMES) {
            throw new IllegalArgumentException(
                    "componentTrace exceeds " + MAX_TRACE_FRAMES + " frames");
        }
        List<ComponentTraceFrame> normalized = new ArrayList<>(supplied.size());
        int aggregateLength = 0;
        for (ComponentTraceFrame frame : supplied) {
            String component = bound(frame.component());
            String source = bound(frame.invocation().source());
            String path = bound(frame.invocation().elementPath());
            aggregateLength += component.length() + source.length() + path.length();
            if (aggregateLength > MAX_TRACE_LENGTH) {
                throw new IllegalArgumentException(
                        "componentTrace exceeds " + MAX_TRACE_LENGTH + " characters");
            }
            MarkupSourceLocation invocation = new MarkupSourceLocation(
                    source,
                    path,
                    frame.invocation().line(),
                    frame.invocation().column());
            normalized.add(new ComponentTraceFrame(component, invocation));
        }
        return List.copyOf(normalized);
    }

    /** Serializes the status without the {@code markup-status: } prefix. */
    public String json() {
        try {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            fields.put("schemaVersion", schemaVersion);
            fields.put("ok", ok);
            if (ok) {
                fields.put("nodes", nodes);
            } else {
                fields.put("kind", kind);
                fields.put("source", source);
                fields.put("elementPath", elementPath);
                fields.put("line", line);
                fields.put("column", column);
                fields.put("attribute", attribute);
                fields.put("expected", expected);
                fields.put("received", received);
                fields.put("suggestion", suggestion);
                fields.put("consequence", consequence);
                fields.put("componentTrace", traceJson());
                fields.put("message", message);
            }
            return JSON.writeValueAsString(fields);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("unable to encode status", failure);
        }
    }

    private List<LinkedHashMap<String, Object>> traceJson() {
        List<LinkedHashMap<String, Object>> frames = new ArrayList<>(componentTrace.size());
        for (ComponentTraceFrame frame : componentTrace) {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            fields.put("component", frame.component());
            fields.put("source", frame.invocation().source());
            fields.put("elementPath", frame.invocation().elementPath());
            fields.put("line", frame.invocation().line());
            fields.put("column", frame.invocation().column());
            frames.add(fields);
        }
        return List.copyOf(frames);
    }
}
