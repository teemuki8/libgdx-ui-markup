package dev.gdx.markup.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static tag vocabulary: the allowed tags, their per-tag attributes, required attributes, and
 * the canonical semantic role each actor tag emits. Custom widgets register through
 * {@link dev.gdx.markup.core.MarkupRegistry} at build time; this class is the parse-time
 * whitelist shared by every dialect consumer.
 */
public final class TagSpec {
    /** Smallest supported logical font size. */
    public static final int MIN_FONT_SIZE = 4;
    /** Largest supported logical font size. */
    public static final int MAX_FONT_SIZE = 256;

    private static final Pattern DATA_SUFFIX = Pattern.compile("[A-Za-z0-9_-]+");

    /** Attribute value grammar used by the strict validator. */
    public enum ValueKind {
        /** {@code true} or {@code false}. */
        BOOLEAN,
        /** Boolean, or the axis {@code x} / {@code y}. */
        BOOLEAN_OR_AXIS,
        /** Whitespace-separated {@code top|bottom|left|right|center} tokens. */
        ALIGN,
        /** Positive integer. */
        POSITIVE_INT,
        /** Non-negative float. */
        NON_NEGATIVE_FLOAT,
        /** One or four comma-separated non-negative floats. */
        PAD,
        /** Finite float. */
        FLOAT,
        /** Non-empty comma-separated item list. */
        ITEMS,
        /** Bounded non-empty text. */
        TEXT,
        /** Integer logical font size in the bounded supported range. */
        FONT_SIZE,
        /** {@code data-*} semantic property; validated by suffix pattern. */
        DATA_PREFIX,
    }

    private static final Set<String> COMMON = Set.of(
            "id", "name", "label", "class", "style", "disabled", "visible", "focusable",
            "width", "height", "min-width", "min-height", "expand", "fill", "align",
            "colspan", "pad", "pad-top", "pad-right", "pad-bottom", "pad-left", "space",
            "grow", "grow-x", "grow-y", "uniform");

    private static final Map<String, ValueKind> COMMON_KINDS = commonKinds();

    /** The parse-time vocabulary; iteration order is stable and canonical. */
    public static final Map<String, TagSpec> VOCABULARY = vocabulary();

    private final String tag;
    private final String role;
    private final Map<String, ValueKind> attributes;
    private final Set<String> required;

    private TagSpec(
            String tag, String role, Map<String, ValueKind> attributes, Set<String> required) {
        this.tag = tag;
        this.role = role;
        this.attributes = Map.copyOf(attributes);
        this.required = Set.copyOf(required);
    }

    /** Returns the tag name. */
    public String tag() {
        return tag;
    }

    /** Returns the canonical semantic role emitted for this tag, or {@code null} for no role. */
    public String role() {
        return role;
    }

    /** Returns the merged attribute whitelist (common plus tag-specific). */
    public Map<String, ValueKind> attributes() {
        return attributes;
    }

    /** Returns the required attribute names. */
    public Set<String> required() {
        return required;
    }

    /** Looks up one vocabulary entry, or throws a typed unknown-tag failure. */
    public static TagSpec require(String tag, String elementPath, int line, int column) {
        return require(tag, Set.of(), elementPath, line, column);
    }

    /**
     * Looks up one vocabulary entry, or treats {@code tag} as a custom actor with the common
     * attribute set when listed in {@code extraTags}. Unknown tags still fail typed.
     */
    public static TagSpec require(String tag, Set<String> extraTags, String elementPath,
            int line, int column) {
        TagSpec spec = VOCABULARY.get(tag);
        if (spec != null) {
            return spec;
        }
        if (extraTags.contains(tag)) {
            return new TagSpec(tag, null, COMMON_KINDS, Set.of());
        }
        throw new MarkupException(MarkupException.Kind.UNKNOWN_TAG, elementPath, line, column,
                "unknown tag <" + tag + ">");
    }

    /** Returns whether the attribute name is valid on this tag, including {@code data-*}. */
    public boolean allows(String attribute) {
        return attributes.containsKey(attribute)
                || (attribute.startsWith("data-") && DATA_SUFFIX.matcher(
                        attribute.substring("data-".length())).matches());
    }

    /** Returns whether an invocation may apply this common attribute to its expanded root. */
    static boolean isCommonAttribute(String attribute) {
        return COMMON_KINDS.containsKey(attribute);
    }

    /** Validates one attribute value against its grammar; returns the failure or {@code null}. */
    public static String validate(ValueKind kind, String value) {
        Objects.requireNonNull(value, "value");
        return switch (kind) {
            case BOOLEAN -> booleanValue(value);
            case BOOLEAN_OR_AXIS -> booleanOrAxis(value);
            case ALIGN -> align(value);
            case POSITIVE_INT -> positiveInt(value);
            case NON_NEGATIVE_FLOAT -> nonNegativeFloat(value);
            case PAD -> pad(value);
            case FLOAT -> finiteFloat(value);
            case ITEMS -> items(value);
            case TEXT -> text(value);
            case FONT_SIZE -> fontSize(value);
            case DATA_PREFIX -> DATA_SUFFIX.matcher(value).matches() ? null
                    : "invalid data-* suffix \"" + value + "\"";
        };
    }

    private static Map<String, TagSpec> vocabulary() {
        Map<String, TagSpec> map = new LinkedHashMap<>();
        map.put("ui", new TagSpec("ui", null, Map.of(), Set.of()));
        map.put("table", actor("table", null));
        map.put("row", new TagSpec("row", null, Map.of(), Set.of()));
        map.put("stack", actor("stack", null));
        map.put("group", actor("group", null));
        map.put("scrollpane", actor("scrollpane", null));
        map.put("label", textActorWith("label", null, Map.of("text", ValueKind.TEXT)));
        map.put("button", textActorWith("button", "button", Map.of("text", ValueKind.TEXT)));
        map.put("checkbox", textActorWith("checkbox", "checkbox",
                Map.of("text", ValueKind.TEXT, "checked", ValueKind.BOOLEAN)));
        map.put("textfield", textActorWith("textfield", "textfield",
                Map.of("text", ValueKind.TEXT, "editable", ValueKind.BOOLEAN)));
        map.put("selectbox", textActorWith("selectbox", "selectbox",
                Map.of("items", ValueKind.ITEMS), Set.of("items")));
        map.put("slider", actorWith("slider", "slider",
                Map.of("min", ValueKind.FLOAT, "max", ValueKind.FLOAT,
                        "step", ValueKind.FLOAT, "value", ValueKind.FLOAT),
                Set.of("min", "max")));
        map.put("progressbar", actorWith("progressbar", "progressbar",
                Map.of("min", ValueKind.FLOAT, "max", ValueKind.FLOAT, "value", ValueKind.FLOAT),
                Set.of("min", "max")));
        map.put("image", actorWith("image", null,
                Map.of("drawable", ValueKind.TEXT), Set.of("drawable")));
        map.put("window", textActorWith("window", "window",
                Map.of("title", ValueKind.TEXT), Set.of("title")));
        map.put("list", textActorWith("list", "list",
                Map.of("items", ValueKind.ITEMS), Set.of("items")));
        return Map.copyOf(map);
    }

    private static TagSpec actor(String tag, String role) {
        return new TagSpec(tag, role, COMMON_KINDS, Set.of());
    }

    private static TagSpec actorWith(
            String tag, String role, Map<String, ValueKind> specific) {
        return actorWith(tag, role, specific, Set.of());
    }

    private static TagSpec actorWith(
            String tag, String role, Map<String, ValueKind> specific, Set<String> required) {
        Map<String, ValueKind> merged = new LinkedHashMap<>(COMMON_KINDS);
        merged.putAll(specific);
        return new TagSpec(tag, role, merged, required);
    }

    private static TagSpec textActorWith(
            String tag, String role, Map<String, ValueKind> specific) {
        return textActorWith(tag, role, specific, Set.of());
    }

    private static TagSpec textActorWith(
            String tag, String role, Map<String, ValueKind> specific, Set<String> required) {
        Map<String, ValueKind> textAttributes = new LinkedHashMap<>(specific);
        textAttributes.put("font", ValueKind.TEXT);
        textAttributes.put("font-size", ValueKind.FONT_SIZE);
        return actorWith(tag, role, textAttributes, required);
    }

    private static Map<String, ValueKind> commonKinds() {
        Map<String, ValueKind> kinds = new LinkedHashMap<>();
        for (String attribute : COMMON) {
            kinds.put(attribute, kindOf(attribute));
        }
        kinds.put("data-*", ValueKind.DATA_PREFIX);
        return Map.copyOf(kinds);
    }

    private static ValueKind kindOf(String attribute) {
        return switch (attribute) {
            case "expand", "grow", "grow-x", "grow-y" -> ValueKind.BOOLEAN_OR_AXIS;
            case "fill" -> ValueKind.BOOLEAN_OR_AXIS;
            case "align" -> ValueKind.ALIGN;
            case "colspan" -> ValueKind.POSITIVE_INT;
            case "pad", "space" -> ValueKind.PAD;
            case "width", "height", "min-width", "min-height",
                    "pad-top", "pad-right", "pad-bottom", "pad-left" -> ValueKind.NON_NEGATIVE_FLOAT;
            case "disabled", "visible", "focusable", "uniform" -> ValueKind.BOOLEAN;
            case "id", "name", "label", "class", "style" -> ValueKind.TEXT;
            default -> throw new AssertionError(attribute);
        };
    }

    private static String booleanValue(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            return "expected true or false, got \"" + value + "\"";
        }
        return null;
    }

    private static String booleanOrAxis(String value) {
        String failure = booleanValue(value);
        if (failure != null && !"x".equals(value) && !"y".equals(value)) {
            return "expected true, false, x, or y; got \"" + value + "\"";
        }
        return null;
    }

    private static final Set<String> ALIGN_TOKENS =
            Set.of("top", "bottom", "left", "right", "center");

    private static String align(String value) {
        for (String token : value.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!ALIGN_TOKENS.contains(token)) {
                return "unknown align token \"" + token + "\"";
            }
        }
        return null;
    }

    private static String positiveInt(String value) {
        try {
            if (Integer.parseInt(value) >= 1) {
                return null;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the typed failure
        }
        return "expected a positive integer, got \"" + value + "\"";
    }

    private static String nonNegativeFloat(String value) {
        try {
            float parsed = Float.parseFloat(value);
            if (Float.isFinite(parsed) && parsed >= 0) {
                return null;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the typed failure
        }
        return "expected a non-negative number, got \"" + value + "\"";
    }

    private static String finiteFloat(String value) {
        try {
            if (Float.isFinite(Float.parseFloat(value))) {
                return null;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the typed failure
        }
        return "expected a number, got \"" + value + "\"";
    }

    private static String pad(String value) {
        String[] parts = value.split(",");
        if (parts.length != 1 && parts.length != 4) {
            return "expected one or four comma-separated numbers, got \"" + value + "\"";
        }
        for (String part : parts) {
            String failure = nonNegativeFloat(part);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static String items(String value) {
        if (value.isBlank()) {
            return "items must not be blank";
        }
        return null;
    }

    private static String text(String value) {
        if (value.isBlank()) {
            return "must not be blank";
        }
        return null;
    }

    private static String fontSize(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= MIN_FONT_SIZE && parsed <= MAX_FONT_SIZE) {
                return null;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the typed failure
        }
        return "expected an integer from " + MIN_FONT_SIZE + " through " + MAX_FONT_SIZE
                + ", got \"" + value + "\"";
    }

    /** Returns the canonical vocabulary tags in declaration order. */
    public static List<String> tagNames() {
        return List.copyOf(VOCABULARY.keySet());
    }
}
