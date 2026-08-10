package dev.gdx.markup.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable origin data for one expanded concrete element and its attributes. */
public record ElementProvenance(
        MarkupSourceLocation origin,
        Map<String, MarkupSourceLocation> attributeOrigins,
        List<ComponentTraceFrame> componentTrace) {
    private static final int MAX_TRACE_FRAMES = 16;

    /** Validates and defensively copies all provenance data. */
    public ElementProvenance {
        origin = Objects.requireNonNull(origin, "origin");
        attributeOrigins = Map.copyOf(attributeOrigins);
        componentTrace = List.copyOf(componentTrace);
        if (componentTrace.size() > MAX_TRACE_FRAMES) {
            throw new IllegalArgumentException(
                    "componentTrace exceeds " + MAX_TRACE_FRAMES + " frames");
        }
    }

    /** Returns an attribute's origin, falling back to the element origin. */
    public MarkupSourceLocation locationFor(String attribute) {
        return attribute == null ? origin : attributeOrigins.getOrDefault(attribute, origin);
    }
}
