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
 * skin resolution happens later at build time. The original source is scanned once with true
 * one-based line/column tracking, and selector length, per-group count, and the total selector
 * count are hard limits (enforced before split collections are allocated).
 */
public final class CssParser {
    /** Maximum CSS input size. */
    public static final int MAX_INPUT_BYTES = 262_144;
    /** Maximum rule count. */
    public static final int MAX_RULES = 2_048;
    /** Maximum declarations per rule. */
    public static final int MAX_DECLARATIONS = 128;
    /** Maximum length in characters of one selector (compound plus optional pseudo). */
    public static final int MAX_SELECTOR_LENGTH = 256;
    /** Maximum comma-separated selectors in one group. */
    public static final int MAX_SELECTORS_PER_GROUP = 64;
    /** Maximum total selectors across one stylesheet. */
    public static final int MAX_TOTAL_SELECTORS = 4_096;

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

    /**
     * Parses one bounded stylesheet into an immutable rule list. The original source is scanned
     * once; line/column advance through comments and whitespace so every {@link CssRule} and
     * every typed failure carries the true one-based source coordinates of its token. Selector
     * length, selectors per group, and the total selector count are enforced before split
     * collections are allocated.
     */
    public CssDocument parse(String css) {
        Objects.requireNonNull(css, "css");
        byte[] utf8 = css.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maxInputBytes) {
            throw styleError(1, 1, "stylesheet of " + utf8.length + " bytes exceeds the "
                    + maxInputBytes + "-byte limit");
        }
        Cursor cursor = new Cursor(css);
        ArrayList<CssRule> rules = new ArrayList<>();
        int ruleIndex = 0;
        int totalSelectors = 0;
        while (true) {
            cursor.skipSpaceAndComments();
            if (cursor.atEnd()) {
                break;
            }
            int ruleLine = cursor.line();
            int ruleColumn = cursor.column();
            int selectorStart = cursor.position();
            int open = cursor.indexOfSkippingComments('{');
            if (open < 0) {
                String trailing = removeComments(css.substring(selectorStart)).strip();
                if (!trailing.isEmpty()) {
                    throw styleError(ruleLine, ruleColumn,
                            "unexpected content outside a rule: \""
                                    + trailing.substring(0, Math.min(trailing.length(), 40))
                                    + "\"");
                }
                break;
            }
            String selectorText = css.substring(selectorStart, open);
            if (removeComments(selectorText).strip().isEmpty()) {
                throw styleError(ruleLine, ruleColumn, "missing selector");
            }
            cursor.advance();
            List<Selector> selectors = parseSelectors(selectorText, ruleLine, ruleColumn,
                    totalSelectors);
            totalSelectors += selectors.size();
            LinkedHashMap<String, String> properties = new LinkedHashMap<>();
            while (true) {
                cursor.skipSpaceAndComments();
                if (cursor.atEnd()) {
                    throw styleError(ruleLine, ruleColumn, "unterminated rule block");
                }
                if (cursor.peek() == '}') {
                    cursor.advance();
                    break;
                }
                int nameLine = cursor.line();
                int nameColumn = cursor.column();
                int nameStart = cursor.position();
                int terminator = cursor.indexOfSkippingAnyOf(';', '}');
                if (terminator < 0) {
                    throw styleError(ruleLine, ruleColumn, "unterminated rule block");
                }
                parseDeclaration(css.substring(nameStart, terminator), nameLine, nameColumn,
                        properties);
                if (cursor.peek() == ';') {
                    cursor.advance();
                }
            }
            if (properties.isEmpty()) {
                throw styleError(ruleLine, ruleColumn, "rule declares no properties");
            }
            rules.add(new CssRule(selectors, properties, ruleIndex, ruleLine, ruleColumn));
            ruleIndex++;
            if (ruleIndex > maxRules) {
                throw styleError(ruleLine, ruleColumn, "stylesheet exceeds the " + maxRules
                        + "-rule limit");
            }
        }
        return new CssDocument(List.copyOf(rules), utf8.length);
    }

    private static String removeComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static List<Selector> parseSelectors(String text, int line, int column,
            int totalSelectors) {
        String clean = removeComments(text).strip();
        int partCount = validateSelectorParts(clean, line, column);
        if (partCount > MAX_SELECTORS_PER_GROUP) {
            throw tooLarge(line, column, "selector group exceeds the "
                    + MAX_SELECTORS_PER_GROUP + "-selector limit");
        }
        if (totalSelectors + partCount > MAX_TOTAL_SELECTORS) {
            throw tooLarge(line, column, "stylesheet exceeds the " + MAX_TOTAL_SELECTORS
                    + "-total-selector limit");
        }
        ArrayList<Selector> selectors = new ArrayList<>(partCount);
        for (String part : clean.split(",", -1)) {
            String candidate = part.strip();
            if (candidate.isEmpty()) {
                throw styleError(line, column, "empty selector in \"" + clean + "\"");
            }
            String pseudo = null;
            int pseudoAt = candidate.indexOf(':');
            if (pseudoAt >= 0) {
                pseudo = candidate.substring(pseudoAt + 1).strip().toLowerCase(Locale.ROOT);
                if (pseudo.isEmpty() || candidate.indexOf(':', pseudoAt + 1) >= 0) {
                    throw styleError(line, column, "unparseable selector \"" + part.strip()
                            + "\"");
                }
                if (!PSEUDO_STATES.contains(pseudo)) {
                    throw styleError(line, column, "unknown pseudo-state \":" + pseudo
                            + "\" in \"" + part.strip() + "\"");
                }
                candidate = candidate.substring(0, pseudoAt).strip();
            }
            selectors.add(parseSimple(candidate, pseudo, line, column));
        }
        return List.copyOf(selectors);
    }

    /**
     * One pass over the comma-separated selector text that counts parts and validates each
     * trimmed part's length against {@link #MAX_SELECTOR_LENGTH} without allocating split
     * collections, so an overlong selector is rejected before {@code split} runs. Trimming
     * mirrors {@link String#strip()} (whitespace plus ISO control characters).
     */
    private static int validateSelectorParts(String text, int line, int column) {
        int partStart = 0;
        int partCount = 0;
        int index = 0;
        int length = text.length();
        while (index <= length) {
            if (index == length || text.charAt(index) == ',') {
                int start = partStart;
                while (start < index && (Character.isWhitespace(text.charAt(start))
                        || Character.isISOControl(text.charAt(start)))) {
                    start++;
                }
                int end = index;
                while (end > start && (Character.isWhitespace(text.charAt(end - 1))
                        || Character.isISOControl(text.charAt(end - 1)))) {
                    end--;
                }
                if (end - start > MAX_SELECTOR_LENGTH) {
                    throw tooLarge(line, column, "selector \"" + text.substring(start, end)
                            + "\" exceeds the " + MAX_SELECTOR_LENGTH + "-character limit");
                }
                partCount++;
                partStart = index + 1;
            }
            index++;
        }
        return partCount;
    }

    private static Selector parseSimple(String candidate, String pseudo, int line, int column) {
        Matcher tagClass = SIMPLE_SELECTOR.matcher(candidate);
        if (tagClass.matches()) {
            String tag = tagClass.group(1);
            if (!TagSpec.VOCABULARY.containsKey(tag)) {
                throw styleError(line, column, "selector \"" + candidate + "\" references unknown "
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
        throw styleError(line, column, "unparseable selector \"" + candidate
                + "\" (supported: tag, .class, #id, tag.class, each with one pseudo-state)");
    }

    private static void parseDeclaration(String raw, int line, int column,
            LinkedHashMap<String, String> properties) {
        String statement = removeComments(raw).strip();
        if (statement.isEmpty()) {
            return;
        }
        int colon = statement.indexOf(':');
        if (colon <= 0) {
            throw styleError(line, column, "expected \"property: value\", got \""
                    + statement.substring(0, Math.min(statement.length(), 40)) + "\"");
        }
        String name = statement.substring(0, colon).strip().toLowerCase(Locale.ROOT);
        String value = statement.substring(colon + 1).strip();
        PropertyKind kind = PROPERTIES.get(name);
        if (kind == null) {
            throw styleError(line, column, "unknown CSS property \"" + name + "\"");
        }
        if (value.isEmpty()) {
            throw styleError(line, column, "property \"" + name + "\" has no value");
        }
        String failure = validate(kind, name, value);
        if (failure != null) {
            throw styleError(line, column, "invalid value for \"" + name + "\": " + failure);
        }
        if (properties.size() >= MAX_DECLARATIONS) {
            throw styleError(line, column, "rule exceeds the " + MAX_DECLARATIONS
                    + "-declaration limit");
        }
        properties.put(name, value);
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

    private static MarkupException styleError(int line, int column, String message) {
        return new MarkupException(MarkupException.Kind.STYLE_ERROR, "css", line, column, message);
    }

    private static MarkupException tooLarge(int line, int column, String message) {
        return new MarkupException(MarkupException.Kind.TOO_LARGE, "css", line, column, message);
    }

    /**
     * Single-pass scanner over the original source. Position, line, and column advance together
     * through whitespace and comments, so token starts are recorded in true one-based
     * coordinates. Unterminated comments are left in place as literal text, matching the
     * previous comment-stripping regex.
     */
    private static final class Cursor {
        private final String source;
        private int position;
        private int line = 1;
        private int column = 1;

        Cursor(String source) {
            this.source = source;
        }

        boolean atEnd() {
            return position >= source.length();
        }

        int position() {
            return position;
        }

        int line() {
            return line;
        }

        int column() {
            return column;
        }

        char peek() {
            return source.charAt(position);
        }

        void advance() {
            char c = source.charAt(position);
            position++;
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }

        /** Skips whitespace and terminated comments, tracking position. */
        void skipSpaceAndComments() {
            while (!atEnd()) {
                char c = source.charAt(position);
                if (c == '\n') {
                    position++;
                    line++;
                    column = 1;
                } else if (Character.isWhitespace(c)) {
                    position++;
                    column++;
                } else if (c == '/' && position + 1 < source.length()
                        && source.charAt(position + 1) == '*') {
                    if (!skipComment()) {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        /**
         * Skips one comment at the current position; returns false when unterminated, leaving
         * the slash in place so it is scanned as literal content.
         */
        private boolean skipComment() {
            int start = position;
            int startLine = line;
            int startColumn = column;
            position += 2;
            column += 2;
            while (!atEnd()) {
                char c = source.charAt(position);
                position++;
                if (c == '\n') {
                    line++;
                    column = 1;
                } else if (c == '*' && !atEnd() && source.charAt(position) == '/') {
                    position++;
                    column++;
                    return true;
                } else {
                    column++;
                }
            }
            position = start;
            line = startLine;
            column = startColumn;
            return false;
        }

        /** Returns the index of the next {@code target}, skipping comments/whitespace; -1 at end. */
        int indexOfSkippingComments(char target) {
            return scanTo(target, '\0');
        }

        /** Returns the index of the next of {@code first}/{@code second}; -1 at end. */
        int indexOfSkippingAnyOf(char first, char second) {
            return scanTo(first, second);
        }

        private int scanTo(char first, char second) {
            while (!atEnd()) {
                char c = source.charAt(position);
                if (c == '\n') {
                    position++;
                    line++;
                    column = 1;
                } else if (Character.isWhitespace(c)) {
                    position++;
                    column++;
                } else if (c == '/' && position + 1 < source.length()
                        && source.charAt(position + 1) == '*') {
                    if (!skipComment()) {
                        position++;
                        column++;
                    }
                } else if (c == first || c == second) {
                    return position;
                } else {
                    position++;
                    column++;
                }
            }
            return -1;
        }
    }
}
