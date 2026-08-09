package dev.gdx.markup.core.style;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable parse result for the bounded CSS subset. */
public record CssDocument(List<CssRule> rules, int byteLength, Map<String, String> variables) {
    /** Validates the immutable shape. */
    public CssDocument {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be non-negative");
        }
    }

    /** Source-compatible construction for a document without variables. */
    public CssDocument(List<CssRule> rules, int byteLength) {
        this(rules, byteLength, Map.of());
    }
}
