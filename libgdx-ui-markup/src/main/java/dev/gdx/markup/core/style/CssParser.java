package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.TagSpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GL-free parser for the bounded CSS subset. Selectors are simple compounds
 * ({@code tag}, {@code .class}, {@code #id}, {@code tag.class}) with at most one pseudo-state;
 * there are no combinators. Every property is whitelisted and structurally validated here;
 * skin resolution happens later at build time.
 */
public final class CssParser {
    /** Maximum CSS input size. */
    public static final int MAX_INPUT_BYTES = 262_144;
    /** Maximum rule count. */
    public static final int MAX_RULES = 2_048;
    /** Maximum declarations per rule. */
    public static final int MAX_DECLARATIONS = 128;

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern LENGTH = Pattern.compile(
            "[0-9]+(?:\\.[0-9]+)?(?:px)?");
    private static final Pattern SIMPLE_SELECTOR = Pattern.compile(
            "^([a-z][a-z0-9]*)(?:\\.([A-Za-z0-9_-]+))?$");
    private static final Pattern CLASS_SELECTOR = Pattern.compile("^\\.([A-Za-z0-9_-]+)$");
    private static final Pattern ID_SELECTOR = Pattern.compile("^#([A-Za-z0-9_-]+)$");
    private static final Set<String> PSEUDO_STATES = Set.of("hover", "pressed", "checked",
            "disabled");
    private static final Set<String> TEXT_ALIGNS = Set.of("left", "center", "right");

    private static final Map<String, PropertyKind> PROPERTIES = properties();

    private enum PropertyKind {
        LENGTH,
        PADDING,
        MARGIN,
        COLOR,
        DRAWABLE,
        FONT,
        TEXT_ALIGN,
        BOOLEAN,
    }

    private static Map<String, PropertyKind> properties() {
        Map<String, PropertyKind> map = new LinkedHashMap<>();
        map.put("color", PropertyKind.COLOR);
        map.put("font", PropertyKind.FONT);
        map.put("font-color", PropertyKind.COLOR);
        map.put("background", PropertyKind.DRAWABLE);
        map.put("background-over", PropertyKind.DRAWABLE);
        map.put("background-down", PropertyKind.DRAWABLE);
        map.put("background-checked", PropertyKind.DRAWABLE);
        map.put("background-disabled", PropertyKind.DRAWABLE);
        map.put("padding", PropertyKind.PADDING);
        for (String edge : List.of("top", "right", "bottom", "left")) {
            map.put("padding-" + edge, PropertyKind.LENGTH);
            map.put("margin-" + edge, PropertyKind.LENGTH);
        }
        map.put("margin", PropertyKind.MARGIN);
        map.put("width", PropertyKind.LENGTH);
        map.put("height", PropertyKind.LENGTH);
        map.put("min-width", PropertyKind.LENGTH);
        map.put("min-height", PropertyKind.LENGTH);
        map.put("text-align", PropertyKind.TEXT_ALIGN);
        map.put("visible", PropertyKind.BOOLEAN);
        return Map.copyOf(map);
    }

    private final int maxInputBytes;
    private final int maxRules;

    /** Creates a parser with the default bounded limits. */
    public CssParser() {
        this(MAX_INPUT_BYTES, MAX_RULES);
    }

    /** Creates a parser with explicit bounded limits. */
    public CssParser(int maxInputBytes, int maxRules) {
        this.maxInputBytes = maxInputBytes;
        this.maxRules = maxRules;
    }

    /** Parses one bounded stylesheet into an immutable rule list. */
    public CssDocument parse(String css) {
        Objects.requireNonNull(css, "css");
        byte[] utf8 = css.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maxInputBytes) {
            throw styleError(0, "stylesheet of " + utf8.length
                    + " bytes exceeds the " + maxInputBytes + "-byte limit");
        }
        String withoutComments = css.replaceAll("(?s)/\\*.*?\\*/", "");
        ArrayList<CssRule> rules = new ArrayList<>();
        int cursor = 0;
        int ruleIndex = 0;
        int line = 1;
        while (true) {
            int open = withoutComments.indexOf('{', cursor);
            if (open < 0) {
                String trailing = withoutComments.substring(cursor).strip();
                if (!trailing.isEmpty()) {
                    throw styleError(ruleIndex, "unexpected content outside a rule: \"" + trailing
                            .substring(0, Math.min(trailing.length(), 40)) + "\"");
                }
                break;
            }
            String selectorText = withoutComments.substring(cursor, open).strip();
            if (selectorText.isEmpty()) {
                throw styleError(ruleIndex, "missing selector");
            }
            int close = withoutComments.indexOf('}', open);
            if (close < 0) {
                throw styleError(ruleIndex, "unterminated rule block");
            }
            String body = withoutComments.substring(open + 1, close);
            List<Selector> selectors = parseSelectors(selectorText, ruleIndex);
            Map<String, String> properties = parseDeclarations(body, ruleIndex);
            if (properties.isEmpty()) {
                throw styleError(ruleIndex, "rule declares no properties");
            }
            rules.add(new CssRule(selectors, properties, ruleIndex, line, 0));
            ruleIndex++;
            if (ruleIndex > maxRules) {
                throw styleError(ruleIndex, "stylesheet exceeds the " + maxRules + "-rule limit");
            }
            cursor = close + 1;
            line += countNewlines(withoutComments.substring(open, close + 1));
        }
        return new CssDocument(List.copyOf(rules), utf8.length);
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static List<Selector> parseSelectors(String text, int ruleIndex) {
        ArrayList<Selector> selectors = new ArrayList<>();
        for (String part : text.split(",")) {
            String candidate = part.strip();
            if (candidate.isEmpty()) {
                throw styleError(ruleIndex, "empty selector in \"" + text + "\"");
            }
            String pseudo = null;
            int pseudoAt = candidate.indexOf(':');
            if (pseudoAt >= 0) {
                pseudo = candidate.substring(pseudoAt + 1).strip().toLowerCase(Locale.ROOT);
                if (pseudo.isEmpty() || candidate.indexOf(':', pseudoAt + 1) >= 0) {
                    throw styleError(ruleIndex, "unparseable selector \"" + part.strip() + "\"");
                }
                if (!PSEUDO_STATES.contains(pseudo)) {
                    throw styleError(ruleIndex, "unknown pseudo-state \":" + pseudo
                            + "\" in \"" + part.strip() + "\"");
                }
                candidate = candidate.substring(0, pseudoAt).strip();
            }
            selectors.add(parseSimple(candidate, pseudo, ruleIndex));
        }
        return List.copyOf(selectors);
    }

    private static Selector parseSimple(String candidate, String pseudo, int ruleIndex) {
        Matcher tagClass = SIMPLE_SELECTOR.matcher(candidate);
        if (tagClass.matches()) {
            String tag = tagClass.group(1);
            if (!TagSpec.VOCABULARY.containsKey(tag)) {
                throw styleError(ruleIndex, "selector \"" + candidate + "\" references unknown "
                        + "tag <" + tag + ">");
            }
            return new Selector(tag, null, tagClass.group(2), pseudo);
        }
        Matcher idMatch = ID_SELECTOR.matcher(candidate);
        if (idMatch.matches()) {
            return new Selector(null, idMatch.group(1), null, pseudo);
        }
        Matcher classMatch = CLASS_SELECTOR.matcher(candidate);
        if (classMatch.matches()) {
            return new Selector(null, null, classMatch.group(1), pseudo);
        }
        throw styleError(ruleIndex, "unparseable selector \"" + candidate
                + "\" (supported: tag, .class, #id, tag.class, each with one pseudo-state)");
    }

    private static Map<String, String> parseDeclarations(String body, int ruleIndex) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        for (String declaration : body.split(";", -1)) {
            String statement = declaration.strip();
            if (statement.isEmpty()) {
                continue;
            }
            int colon = statement.indexOf(':');
            if (colon <= 0) {
                throw styleError(ruleIndex, "expected \"property: value\", got \""
                        + statement.substring(0, Math.min(statement.length(), 40)) + "\"");
            }
            String name = statement.substring(0, colon).strip()
                    .toLowerCase(Locale.ROOT);
            String value = statement.substring(colon + 1).strip();
            PropertyKind kind = PROPERTIES.get(name);
            if (kind == null) {
                throw styleError(ruleIndex, "unknown CSS property \"" + name + "\"");
            }
            if (value.isEmpty()) {
                throw styleError(ruleIndex, "property \"" + name + "\" has no value");
            }
            String failure = validate(kind, name, value);
            if (failure != null) {
                throw styleError(ruleIndex, "invalid value for \"" + name + "\": " + failure);
            }
            if (properties.size() >= MAX_DECLARATIONS) {
                throw styleError(ruleIndex, "rule exceeds the " + MAX_DECLARATIONS
                        + "-declaration limit");
            }
            properties.put(name, value);
        }
        return Map.copyOf(properties);
    }

    private static String validate(PropertyKind kind, String name, String value) {
        return switch (kind) {
            case LENGTH, PADDING, MARGIN -> validateLengths(value, name);
            case COLOR -> HEX_COLOR.matcher(value).matches() || IDENTIFIER.matcher(value).matches()
                    ? null : "expected #rrggbb, #rrggbbaa, or a color name; got \"" + value + "\"";
            case DRAWABLE -> IDENTIFIER.matcher(value).matches() ? null
                    : "expected a drawable name; got \"" + value + "\"";
            case FONT -> IDENTIFIER.matcher(value).matches() ? null
                    : "expected a font name; got \"" + value + "\"";
            case TEXT_ALIGN -> TEXT_ALIGNS.contains(value) ? null
                    : "expected left, center, or right; got \"" + value + "\"";
            case BOOLEAN -> ("true".equals(value) || "false".equals(value)) ? null
                    : "expected true or false; got \"" + value + "\"";
        };
    }

    private static String validateLengths(String value, String property) {
        String[] parts = value.split(",");
        if (parts.length != 1 && parts.length != 4) {
            return "expected one or four comma-separated lengths; got \"" + value + "\"";
        }
        for (String part : parts) {
            if (!LENGTH.matcher(part.strip()).matches()) {
                return "expected a non-negative length (optional px); got \"" + part + "\"";
            }
        }
        return null;
    }

    private static MarkupException styleError(int ruleIndex, String message) {
        return new MarkupException(MarkupException.Kind.STYLE_ERROR, "css", ruleIndex, 0, message);
    }
}
