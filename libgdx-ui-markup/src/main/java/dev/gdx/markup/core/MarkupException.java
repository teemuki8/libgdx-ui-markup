package dev.gdx.markup.core;

import java.io.Serial;
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
    }

    private final Kind kind;
    private final String elementPath;
    private final int line;
    private final int column;

    /** Creates a typed diagnostic. Path, line, and column are always present; use 0 when unknown. */
    public MarkupException(
            Kind kind, String elementPath, int line, int column, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.elementPath = elementPath == null ? "" : elementPath;
        this.line = line;
        this.column = column;
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

    /** Formats the diagnostic in the stable {@code elementPath:line: message} shape. */
    public String formatted() {
        String location = line > 0 ? line + (column > 0 ? ":" + column : "") : "?";
        String where = elementPath.isEmpty() ? location : elementPath + ":" + location;
        return where + ": " + getMessage();
    }
}
