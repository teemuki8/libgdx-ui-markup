package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-element style after the cascade: an ordered property-to-value map. Values are
 * kept as parsed strings; the builder interprets them against the skin (drawable names, colors,
 * lengths). Every winning value remembers the source rule that supplied it, so build-time
 * diagnostics for that value can report the selector's coordinates.
 */
public final class ResolvedStyle {
    private final Map<String, String> properties;
    private final Map<String, CssRule> sources;

    private ResolvedStyle(Map<String, String> properties, Map<String, CssRule> sources) {
        this.properties = Map.copyOf(properties);
        this.sources = Map.copyOf(sources);
    }

    /** Creates an empty resolved style. */
    public static ResolvedStyle empty() {
        return new ResolvedStyle(Map.of(), Map.of());
    }

    /** Returns the winning value for one property, or {@code null} when unmatched. */
    public String get(String property) {
        return properties.get(property);
    }

    /** Returns whether the element matched at least one rule declaring the property. */
    public boolean has(String property) {
        return properties.containsKey(property);
    }

    /**
     * Returns the source rule that supplied the winning value for one property, or {@code null}
     * when the property is unmatched. Non-null whenever {@link #has} is true.
     */
    public CssRule sourceRule(String property) {
        return sources.get(property);
    }

    /** Returns the declared boolean value, or the fallback when unmatched. */
    public boolean booleanValue(String property, boolean fallback) {
        String value = properties.get(property);
        if (value == null) {
            return fallback;
        }
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(property, value);
        };
    }

    /** Returns the declared length (with optional {@code px} suffix), or the fallback. */
    public float length(String property, float fallback) {
        String value = properties.get(property);
        if (value == null) {
            return fallback;
        }
        return parseLength(property, value);
    }

    /** Returns a typed responsive dimension, or {@code null} when unmatched. */
    public CssLength lengthValue(String property) {
        String value = properties.get(property);
        return value == null ? null : CssLength.parse(value, true);
    }

    /** Returns expanded top/right/bottom/left spacing, or {@code null} when unmatched. */
    public CssSpacing spacing(String property) {
        String value = properties.get(property);
        return value == null ? null : CssSpacing.parse(value);
    }

    /** Returns one or four declared lengths (padding/margin), or the fallback list. */
    public List<Float> lengths(String property, List<Float> fallback) {
        String value = properties.get(property);
        if (value == null) {
            return fallback;
        }
        return CssSpacing.parse(value).values();
    }

    private static float parseLength(String property, String raw) {
        String value = raw.strip().toLowerCase();
        if (value.endsWith("px")) {
            value = value.substring(0, value.length() - 2).strip();
        }
        try {
            float parsed = Float.parseFloat(value);
            if (Float.isFinite(parsed) && parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the typed failure
        }
        throw invalid(property, raw);
    }

    private static MarkupException invalid(String property, String value) {
        return new MarkupException(MarkupException.Kind.INVALID_VALUE, "", 0, 0,
                "invalid value for CSS property \"" + property + "\": \"" + value + "\"");
    }

    /** Ordered builder used by the cascade resolver. */
    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        private final LinkedHashMap<String, CssRule> sources = new LinkedHashMap<>();

        private Builder() {
        }

        Builder put(String property, String value, CssRule source) {
            values.put(Objects.requireNonNull(property, "property"),
                    Objects.requireNonNull(value, "value"));
            sources.put(property, Objects.requireNonNull(source, "source"));
            return this;
        }

        ResolvedStyle build() {
            return new ResolvedStyle(values, sources);
        }
    }
}
