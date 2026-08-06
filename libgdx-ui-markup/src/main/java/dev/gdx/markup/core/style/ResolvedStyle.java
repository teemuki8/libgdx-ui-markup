package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-element style after the cascade: an ordered property-to-value map. Values are
 * kept as parsed strings; the builder interprets them against the skin (drawable names, colors,
 * lengths).
 */
public final class ResolvedStyle {
    private final Map<String, String> properties;

    private ResolvedStyle(Map<String, String> properties) {
        this.properties = Map.copyOf(properties);
    }

    /** Creates an empty resolved style. */
    public static ResolvedStyle empty() {
        return new ResolvedStyle(Map.of());
    }

    /** Returns the winning value for one property, or {@code null} when unmatched. */
    public String get(String property) {
        return properties.get(property);
    }

    /** Returns whether the element matched at least one rule declaring the property. */
    public boolean has(String property) {
        return properties.containsKey(property);
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

    /** Returns one or four declared lengths (padding/margin), or the fallback list. */
    public List<Float> lengths(String property, List<Float> fallback) {
        String value = properties.get(property);
        if (value == null) {
            return fallback;
        }
        String[] parts = value.split(",");
        ArrayList<Float> parsed = new ArrayList<>(parts.length);
        for (String part : parts) {
            parsed.add(parseLength(property, part));
        }
        return List.copyOf(parsed);
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

        private Builder() {
        }

        Builder put(String property, String value) {
            values.put(Objects.requireNonNull(property, "property"),
                    Objects.requireNonNull(value, "value"));
            return this;
        }

        ResolvedStyle build() {
            return new ResolvedStyle(values);
        }
    }
}
