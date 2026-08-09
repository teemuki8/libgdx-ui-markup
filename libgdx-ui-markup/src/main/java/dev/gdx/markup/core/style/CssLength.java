package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, GL-free length accepted by responsive GDXCSS dimension properties. */
public sealed interface CssLength permits CssLength.Pixels, CssLength.Percent, CssLength.Auto {
    /** A finite non-negative pixel length. */
    record Pixels(float value) implements CssLength {
        /** Validates the bounded value. */
        public Pixels {
            requireNonNegativeFinite(value, "pixel length");
        }
    }

    /** A finite non-negative ratio, where {@code 1} represents {@code 100%}. */
    record Percent(float ratio) implements CssLength {
        /** Validates the bounded value. */
        public Percent {
            requireNonNegativeFinite(ratio, "percentage");
        }
    }

    /** Native Scene2D preferred sizing. */
    enum Auto implements CssLength {
        INSTANCE
    }

    /**
     * Parses unitless pixels, {@code px}, percentage, or optionally {@code auto}.
     * Invalid input is reported as a typed value error for callers outside the CSS parser.
     */
    static CssLength parse(String raw, boolean allowAuto) {
        Objects.requireNonNull(raw, "raw");
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (allowAuto && "auto".equals(value)) {
            return Auto.INSTANCE;
        }
        Matcher matcher = CssLengthGrammar.VALUE.matcher(value);
        if (!matcher.matches()) {
            throw invalid(raw, allowAuto);
        }
        try {
            float number = Float.parseFloat(matcher.group(1));
            if (!Float.isFinite(number)) {
                throw invalid(raw, allowAuto);
            }
            return "%".equals(matcher.group(2))
                    ? new Percent(number / 100f) : new Pixels(number);
        } catch (NumberFormatException failure) {
            throw invalid(raw, allowAuto);
        }
    }

    private static void requireNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static MarkupException invalid(String raw, boolean allowAuto) {
        String expected = allowAuto
                ? "non-negative pixels, percent, or auto"
                : "non-negative pixels or percent";
        return new MarkupException(MarkupException.Kind.INVALID_VALUE, "", 0, 0,
                "expected " + expected + "; got \"" + raw + "\"");
    }
}

final class CssLengthGrammar {
    static final Pattern VALUE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(px|%)?");

    private CssLengthGrammar() {
    }
}
