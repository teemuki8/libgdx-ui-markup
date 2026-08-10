package dev.gdx.markup.core;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/** Immutable source identity and position for markup diagnostics and provenance. */
public record MarkupSourceLocation(String source, String elementPath, int line, int column)
        implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    static final int MAX_SOURCE_LENGTH = 4096;

    /** Validates and normalizes the source location. */
    public MarkupSourceLocation {
        source = validateSource(source);
        elementPath = Objects.requireNonNull(elementPath, "elementPath");
        if (line < 0) {
            throw new IllegalArgumentException("line must be non-negative");
        }
        if (column < 0) {
            throw new IllegalArgumentException("column must be non-negative");
        }
    }

    /** Creates a location for an in-memory source. */
    public static MarkupSourceLocation memory(String elementPath, int line, int column) {
        return new MarkupSourceLocation("<memory>", elementPath, line, column);
    }

    static String validateSource(String value) {
        String sourceValue = Objects.requireNonNull(value, "source");
        if (sourceValue.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (sourceValue.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                    "source exceeds " + MAX_SOURCE_LENGTH + " characters");
        }
        if (sourceValue.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("source must not contain control characters");
        }
        return sourceValue;
    }
}
