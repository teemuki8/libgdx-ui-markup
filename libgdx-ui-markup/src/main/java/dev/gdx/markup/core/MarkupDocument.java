package dev.gdx.markup.core;

import java.util.Map;
import java.util.Objects;

/** Immutable parse result: one validated element tree plus source and provenance data. */
public record MarkupDocument(
        Element root,
        int byteLength,
        String source,
        Map<String, ElementProvenance> provenance) {
    /** Validates the immutable shape. */
    public MarkupDocument {
        root = Objects.requireNonNull(root, "root");
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be non-negative");
        }
        source = MarkupSourceLocation.validateSource(source);
        provenance = Map.copyOf(provenance);
    }

    /** Creates an in-memory document without provenance for compatibility with existing callers. */
    public MarkupDocument(Element root, int byteLength) {
        this(root, byteLength, "<memory>", Map.of());
    }

    /** Returns the provenance for a concrete element path, or {@code null} when unavailable. */
    public ElementProvenance provenanceFor(String path) {
        return provenance.get(path);
    }
}
