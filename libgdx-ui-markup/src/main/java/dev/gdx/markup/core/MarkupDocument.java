package dev.gdx.markup.core;

import java.util.Objects;

/** Immutable parse result: one validated element tree plus the source size. */
public record MarkupDocument(Element root, int byteLength) {
    /** Validates the immutable shape. */
    public MarkupDocument {
        Objects.requireNonNull(root, "root");
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be non-negative");
        }
    }
}
