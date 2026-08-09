package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import java.util.List;
import java.util.Objects;

/** Four physical pixel edges produced by CSS shorthand expansion. */
public record CssSpacing(float top, float right, float bottom, float left) {
    /** Validates all edges. */
    public CssSpacing {
        for (float value : new float[] {top, right, bottom, left}) {
            if (!Float.isFinite(value) || value < 0f) {
                throw new IllegalArgumentException("spacing must be finite and non-negative");
            }
        }
    }

    /** Parses CSS one-to-four whitespace values or the legacy one/four comma form. */
    public static CssSpacing parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String value = raw.strip();
        if (value.isEmpty()) {
            throw invalid(raw);
        }
        String[] parts;
        if (value.indexOf(',') >= 0) {
            parts = value.split(",", -1);
            if (parts.length != 1 && parts.length != 4) {
                throw invalid(raw);
            }
        } else {
            parts = value.split("\\s+");
            if (parts.length < 1 || parts.length > 4) {
                throw invalid(raw);
            }
        }
        float[] parsed = new float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            CssLength length;
            try {
                length = CssLength.parse(parts[index], false);
            } catch (MarkupException failure) {
                throw invalid(raw);
            }
            if (!(length instanceof CssLength.Pixels pixels)) {
                throw invalid(raw);
            }
            parsed[index] = pixels.value();
        }
        return switch (parsed.length) {
            case 1 -> new CssSpacing(parsed[0], parsed[0], parsed[0], parsed[0]);
            case 2 -> new CssSpacing(parsed[0], parsed[1], parsed[0], parsed[1]);
            case 3 -> new CssSpacing(parsed[0], parsed[1], parsed[2], parsed[1]);
            case 4 -> new CssSpacing(parsed[0], parsed[1], parsed[2], parsed[3]);
            default -> throw new AssertionError("validated spacing arity");
        };
    }

    /** Returns the edges in top/right/bottom/left order. */
    public List<Float> values() {
        return List.of(top, right, bottom, left);
    }

    private static MarkupException invalid(String raw) {
        return new MarkupException(MarkupException.Kind.INVALID_VALUE, "", 0, 0,
                "expected one to four non-negative pixel lengths; got \"" + raw + "\"");
    }
}
