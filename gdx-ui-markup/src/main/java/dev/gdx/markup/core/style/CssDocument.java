package dev.gdx.markup.core.style;

import java.util.List;
import java.util.Objects;

/** Immutable parse result for the bounded CSS subset. */
public record CssDocument(List<CssRule> rules, int byteLength) {
    /** Validates the immutable shape. */
    public CssDocument {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be non-negative");
        }
    }
}
