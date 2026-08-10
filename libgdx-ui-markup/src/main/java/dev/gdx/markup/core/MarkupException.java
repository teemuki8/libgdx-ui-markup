package dev.gdx.markup.core;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Typed diagnostic for every markup, CSS, and build failure. Never thrown as a bare exception:
 * every failure carries a kind, an element path, and a source location.
 */
public final class MarkupException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Stable failure taxonomy shared by the parser, CSS engine, and builder. */
    public enum Kind {
        MALFORMED_XML,
        UNKNOWN_TAG,
        UNKNOWN_ATTRIBUTE,
        DUPLICATE_ID,
        MISSING_ATTRIBUTE,
        INVALID_VALUE,
        TOO_LARGE,
        STYLE_ERROR,
        UNRESOLVED_STYLE,
        DUPLICATE_COMPONENT,
        UNKNOWN_COMPONENT,
        MISSING_PARAMETER,
        UNKNOWN_PARAMETER,
        DUPLICATE_SLOT,
        UNKNOWN_SLOT,
        MISSING_SLOT,
        COMPONENT_CYCLE,
    }

    private final Kind kind;
    private final String elementPath;
    private final int line;
    private final int column;
    private final MarkupDiagnosticContext context;

    /** Creates a typed diagnostic. Path, line, and column are always present; use 0 when unknown. */
    public MarkupException(
            Kind kind, String elementPath, int line, int column, String message) {
        this(kind, elementPath, line, column, message, MarkupDiagnosticContext.EMPTY);
    }

    /** Creates a typed diagnostic with structured, transport-neutral detail. */
    public MarkupException(
            Kind kind,
            String elementPath,
            int line,
            int column,
            String message,
            MarkupDiagnosticContext context) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.elementPath = elementPath == null ? "" : elementPath;
        this.line = line;
        this.column = column;
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Returns the stable failure kind. */
    public Kind kind() {
        return kind;
    }

    /** Returns the element path (for example {@code ui/table/button[2]}), or empty when unknown. */
    public String elementPath() {
        return elementPath;
    }

    /** Returns the 1-based source line, or 0 when the location is unknown. */
    public int line() {
        return line;
    }

    /** Returns the 1-based source column, or 0 when the location is unknown. */
    public int column() {
        return column;
    }

    /** Returns the immutable structured detail for this diagnostic. */
    public MarkupDiagnosticContext context() {
        return context;
    }

    /** Returns the exact source identity, or empty when it was not supplied. */
    public String source() {
        return context.source();
    }

    /** Returns the failing attribute or property name, or empty when not applicable. */
    public String attribute() {
        return context.attribute();
    }

    /** Returns the expected value description, or empty when not applicable. */
    public String expected() {
        return context.expected();
    }

    /** Returns the received value, or empty when not applicable. */
    public String received() {
        return context.received();
    }

    /** Returns the deterministic nearest alternative, or empty when none is unambiguous. */
    public String suggestion() {
        return context.suggestion();
    }

    /** Returns the semantic consequence of the failure, or empty when not supplied. */
    public String consequence() {
        return context.consequence();
    }

    /** Returns the bounded immutable component-expansion trace. */
    public List<ComponentTraceFrame> componentTrace() {
        return context.componentTrace();
    }

    /** Formats the diagnostic in the stable {@code elementPath:line: message} shape. */
    public String formatted() {
        String location = line > 0 ? line + (column > 0 ? ":" + column : "") : "?";
        String where = elementPath.isEmpty() ? location : elementPath + ":" + location;
        return where + ": " + getMessage();
    }
}
