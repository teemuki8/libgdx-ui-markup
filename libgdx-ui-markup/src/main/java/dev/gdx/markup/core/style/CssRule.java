package dev.gdx.markup.core.style;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One CSS rule: a comma-separated selector list plus its property map. Each comma part is a
 * separate {@link Selector} with the same properties; specificity is evaluated per part.
 */
public record CssRule(
        List<Selector> selectors,
        Map<String, String> properties,
        int ruleIndex,
        int line,
        int column) {
    /** Validates the immutable shape. */
    public CssRule {
        selectors = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }
}
