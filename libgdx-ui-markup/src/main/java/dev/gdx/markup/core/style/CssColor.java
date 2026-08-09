package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, serializable, GL-free color accepted by GDXCSS paint properties. */
public sealed interface CssColor extends Serializable permits CssColor.Rgba, CssColor.Named {
    /** Explicit RGBA color with integer RGB channels and normalized alpha. */
    record Rgba(int red, int green, int blue, float alpha) implements CssColor {
        /** Validates all four bounded channels. */
        public Rgba {
            requireByte(red, "red");
            requireByte(green, "green");
            requireByte(blue, "blue");
            if (!Float.isFinite(alpha) || alpha < 0f || alpha > 1f) {
                throw new IllegalArgumentException("alpha must be finite and between 0 and 1");
            }
        }

        private static void requireByte(int value, String name) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException(name + " must be between 0 and 255");
            }
        }
    }

    /** Color name resolved from the caller-owned libGDX Skin on the render thread. */
    record Named(String name) implements CssColor {
        /** Validates the bounded identifier grammar. */
        public Named {
            Objects.requireNonNull(name, "name");
            if (!CssColorGrammar.IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid color name: \"" + name + "\"");
            }
        }
    }

    /** Parses the complete GDXCSS color value without touching libGDX or a Skin. */
    static CssColor parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String value = raw.strip();
        if ("transparent".equals(value)) {
            return new Rgba(0, 0, 0, 0f);
        }
        Matcher hex = CssColorGrammar.HEX.matcher(value);
        if (hex.matches()) {
            return parseHex(hex.group(1));
        }
        Matcher rgb = CssColorGrammar.RGB.matcher(value);
        if (rgb.matches()) {
            return rgba(rgb.group(1), rgb.group(2), rgb.group(3), "1", raw);
        }
        Matcher rgba = CssColorGrammar.RGBA.matcher(value);
        if (rgba.matches()) {
            return rgba(rgba.group(1), rgba.group(2), rgba.group(3), rgba.group(4), raw);
        }
        if (CssColorGrammar.IDENTIFIER.matcher(value).matches()) {
            return new Named(value);
        }
        throw invalid(raw);
    }

    private static Rgba parseHex(String digits) {
        if (digits.length() == 3 || digits.length() == 4) {
            int red = duplicateHexDigit(digits, 0);
            int green = duplicateHexDigit(digits, 1);
            int blue = duplicateHexDigit(digits, 2);
            int alpha = digits.length() == 4 ? duplicateHexDigit(digits, 3) : 255;
            return new Rgba(red, green, blue, alpha / 255f);
        }
        int red = Integer.parseInt(digits.substring(0, 2), 16);
        int green = Integer.parseInt(digits.substring(2, 4), 16);
        int blue = Integer.parseInt(digits.substring(4, 6), 16);
        int alpha = digits.length() == 8
                ? Integer.parseInt(digits.substring(6, 8), 16) : 255;
        return new Rgba(red, green, blue, alpha / 255f);
    }

    private static int duplicateHexDigit(String digits, int index) {
        int nibble = Character.digit(digits.charAt(index), 16);
        return nibble * 17;
    }

    private static Rgba rgba(String red, String green, String blue, String alpha, String raw) {
        try {
            return new Rgba(Integer.parseInt(red), Integer.parseInt(green),
                    Integer.parseInt(blue), Float.parseFloat(alpha));
        } catch (IllegalArgumentException failure) {
            throw invalid(raw);
        }
    }

    private static MarkupException invalid(String raw) {
        return new MarkupException(MarkupException.Kind.INVALID_VALUE, "", 0, 0,
                "expected #rgb, #rgba, #rrggbb, #rrggbbaa, rgb(), rgba(), transparent, "
                        + "or a color name; got \"" + raw + "\"");
    }
}

final class CssColorGrammar {
    static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}"
            + "|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    static final Pattern RGB = Pattern.compile("rgb\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)"
            + "\\s*,\\s*([0-9]+)\\s*\\)");
    static final Pattern RGBA = Pattern.compile("rgba\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)"
            + "\\s*,\\s*([0-9]+)\\s*,\\s*((?:[0-9]+(?:\\.[0-9]+)?)|(?:\\.[0-9]+))"
            + "\\s*\\)");
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    private CssColorGrammar() {
    }
}
