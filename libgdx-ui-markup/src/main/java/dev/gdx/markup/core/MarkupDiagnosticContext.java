package dev.gdx.markup.core;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Immutable, transport-neutral detail attached to a typed markup diagnostic. */
public record MarkupDiagnosticContext(
        String source,
        String attribute,
        String expected,
        String received,
        String suggestion,
        String consequence,
        List<ComponentTraceFrame> componentTrace)
        implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    /** Empty context used by the compatibility {@link MarkupException} constructor. */
    public static final MarkupDiagnosticContext EMPTY =
            new MarkupDiagnosticContext("", "", "", "", "", "", List.of());

    private static final int MAX_FIELD_LENGTH = 4096;
    private static final int MAX_TRACE_FRAMES = 16;

    /** Validates and defensively copies all bounded context fields. */
    public MarkupDiagnosticContext {
        source = bounded("source", source);
        if (!source.isEmpty()) {
            MarkupSourceLocation.validateSource(source);
        }
        attribute = bounded("attribute", attribute);
        expected = bounded("expected", expected);
        received = bounded("received", received);
        suggestion = bounded("suggestion", suggestion);
        consequence = bounded("consequence", consequence);
        componentTrace = List.copyOf(componentTrace);
        if (componentTrace.size() > MAX_TRACE_FRAMES) {
            throw new IllegalArgumentException(
                    "componentTrace exceeds " + MAX_TRACE_FRAMES + " frames");
        }
    }

    private static String bounded(String name, String value) {
        String result = value == null ? "" : value;
        if (result.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException(
                    name + " exceeds " + MAX_FIELD_LENGTH + " characters");
        }
        return result;
    }
}
