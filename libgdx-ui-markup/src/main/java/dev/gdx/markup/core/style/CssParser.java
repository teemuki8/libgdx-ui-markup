package dev.gdx.markup.core.style;

import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.TagSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final Pattern FONT_SIZE = Pattern.compile("([0-9]+)(?:px)?");
    private static final Pattern SIMPLE_SELECTOR = Pattern.compile(
            "^([a-z][a-z0-9]*)(?:\\.([A-Za-z0-9_-]+))?$");
    private static final Pattern CLASS_SELECTOR = Pattern.compile("^\\.([A-Za-z0-9_-]+)$");
    private static final Pattern ID_SELECTOR = Pattern.compile("^#([A-Za-z0-9_-]+)$");
    private static final Set<String> PSEUDO_STATES = Set.of("hover", "pressed", "checked",
            "disabled");
    private static final Set<String> TEXT_ALIGNS = Set.of("left", "center", "right");
    private static final Set<String> DISPLAYS = Set.of("initial", "none");
    private static final Set<String> VISIBILITIES = Set.of("visible", "hidden");
    private static final Set<String> OVERFLOWS = Set.of("visible", "hidden");
    private static final Set<String> VERTICAL_ALIGNS = Set.of("top", "middle", "bottom");
    private static final Set<String> RESPONSIVE_DIMENSIONS = Set.of(
            "width", "height", "min-width", "min-height", "max-width", "max-height");
    private static final Set<String> BASE_STATE_ONLY = Set.of(
            "font-size", "display", "gap", "row-gap", "column-gap", "visibility",
            "overflow", "vertical-align");

    private static final Map<String, PropertyKind> PROPERTIES = properties();

    private enum PropertyKind {
        LENGTH,
        RESPONSIVE_LENGTH,
        SPACING,
        COLOR,
        DRAWABLE,
        FONT,
        FONT_SIZE,
        TEXT_ALIGN,
        BOOLEAN,
        DISPLAY,
        VISIBILITY,
        OVERFLOW,
        VERTICAL_ALIGN,
    }

    private static Map<String, PropertyKind> properties() {
        Map<String, PropertyKind> map = new LinkedHashMap<>();
        map.put("color", PropertyKind.COLOR);
        map.put("font", PropertyKind.FONT);
        map.put("font-size", PropertyKind.FONT_SIZE);
        map.put("font-color", PropertyKind.COLOR);
        map.put("background", PropertyKind.DRAWABLE);
        map.put("background-over", PropertyKind.DRAWABLE);
        map.put("background-down", PropertyKind.DRAWABLE);
        map.put("background-checked", PropertyKind.DRAWABLE);
        map.put("background-disabled", PropertyKind.DRAWABLE);
        map.put("padding", PropertyKind.SPACING);
        for (String edge : List.of("top", "right", "bottom", "left")) {
            map.put("padding-" + edge, PropertyKind.LENGTH);
            map.put("margin-" + edge, PropertyKind.LENGTH);
        }
        map.put("margin", PropertyKind.SPACING);
        for (String dimension : RESPONSIVE_DIMENSIONS) {
            map.put(dimension, PropertyKind.RESPONSIVE_LENGTH);
        }
        map.put("gap", PropertyKind.SPACING);
        map.put("row-gap", PropertyKind.LENGTH);
        map.put("column-gap", PropertyKind.LENGTH);
        map.put("display", PropertyKind.DISPLAY);
        map.put("visibility", PropertyKind.VISIBILITY);
        map.put("overflow", PropertyKind.OVERFLOW);
        map.put("vertical-align", PropertyKind.VERTICAL_ALIGN);
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
            throw tooLarge(1, 1, "stylesheet of " + utf8.length + " bytes exceeds the "
                    + maxInputBytes + "-byte limit");
        }
        return parseUtf8(utf8.length, css);
    }

    /**
     * Parses one bounded stylesheet read from {@code path}. At most {@code maxInputBytes + 1}
     * bytes are read, so an oversized file is rejected with a typed {@code TOO_LARGE} failure
     * before its content is decoded into a String. The bytes are decoded as strict UTF-8;
     * malformed or truncated sequences fail with a typed {@code STYLE_ERROR} diagnostic instead
     * of a replacement character.
     *
     * @param path the stylesheet file to read
     * @return the parsed stylesheet
     * @throws IOException if the file cannot be read
     */
    public CssDocument parse(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        byte[] utf8;
        try (InputStream in = Files.newInputStream(path)) {
            utf8 = readBounded(in, maxInputBytes);
        }
        if (utf8.length > maxInputBytes) {
            throw tooLarge(1, 1, "stylesheet exceeds the " + maxInputBytes + "-byte limit");
        }
        return parseUtf8(utf8.length, decodeUtf8(utf8));
    }

    /** Shared parse body for in-bounds UTF-8 stylesheets, whether from a String or a file. */
    private CssDocument parseUtf8(int byteLength, String css) {
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
                        selectors, properties);
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
        return new CssDocument(List.copyOf(rules), byteLength);
    }

    /**
     * Reads at most {@code maxBytes + 1} bytes from {@code in}, stopping as soon as the
     * limit-plus-one sentinel is reached so an oversized input is never materialized. The
     * returned array holds exactly the bytes read.
     */
    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        int capacity = maxBytes == Integer.MAX_VALUE ? maxBytes : maxBytes + 1;
        byte[] buffer = new byte[Math.min(capacity, 4 * 1024)];
        int total = 0;
        while (total < capacity) {
            if (total == buffer.length) {
                buffer = Arrays.copyOf(buffer, nextBufferLength(buffer.length, capacity));
            }
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total == buffer.length ? buffer : Arrays.copyOf(buffer, total);
    }

    /**
     * Next length when growing {@code current} toward {@code capacity}: doubles while below
     * half of capacity, then jumps to capacity. Comparing against {@code capacity / 2} before
     * doubling keeps the product strictly below {@code Integer.MAX_VALUE}, so growth never
     * overflows the int range for any configured limit.
     */
    static int nextBufferLength(int current, int capacity) {
        return current < capacity / 2 ? current * 2 : capacity;
    }

    /** Decodes strict UTF-8; malformed or truncated input fails with a typed diagnostic. */
    private static String decodeUtf8(byte[] utf8) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw styleError(1, 1, "stylesheet is not valid UTF-8: " + failure.getMessage());
        }
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
     * matches {@link String#strip()} exactly: only {@link Character#isWhitespace} characters
     * are removed (ISO controls that are not whitespace, such as NUL, survive strip and count
     * toward the length).
     */
    private static int validateSelectorParts(String text, int line, int column) {
        int partStart = 0;
        int partCount = 0;
        int index = 0;
        int length = text.length();
        while (index <= length) {
            if (index == length || text.charAt(index) == ',') {
                int start = partStart;
                while (start < index && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
                int end = index;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
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
            List<Selector> selectors, LinkedHashMap<String, String> properties) {
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
        if ((BASE_STATE_ONLY.contains(name) || RESPONSIVE_DIMENSIONS.contains(name))
                && selectors.stream().anyMatch(selector -> selector.pseudo() != null)) {
            throw styleError(line, column,
                    "property \"" + name + "\" is not allowed in a pseudo-state rule");
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
            case LENGTH -> validatePixelLength(value);
            case RESPONSIVE_LENGTH -> validateResponsiveLength(value);
            case SPACING -> validateSpacing(value);
            case COLOR -> HEX_COLOR.matcher(value).matches() || IDENTIFIER.matcher(value).matches()
                    ? null : "expected #rrggbb, #rrggbbaa, or a color name; got \"" + value + "\"";
            case DRAWABLE -> IDENTIFIER.matcher(value).matches() ? null
                    : "expected a drawable name; got \"" + value + "\"";
            case FONT -> IDENTIFIER.matcher(value).matches() ? null
                    : "expected a font name; got \"" + value + "\"";
            case FONT_SIZE -> validateFontSize(value);
            case TEXT_ALIGN -> TEXT_ALIGNS.contains(value) ? null
                    : "expected left, center, or right; got \"" + value + "\"";
            case BOOLEAN -> ("true".equals(value) || "false".equals(value)) ? null
                    : "expected true or false; got \"" + value + "\"";
            case DISPLAY -> enumValue(value, DISPLAYS, "initial or none");
            case VISIBILITY -> enumValue(value, VISIBILITIES, "visible or hidden");
            case OVERFLOW -> enumValue(value, OVERFLOWS, "visible or hidden");
            case VERTICAL_ALIGN -> enumValue(value, VERTICAL_ALIGNS,
                    "top, middle, or bottom");
        };
    }

    private static String validatePixelLength(String value) {
        try {
            return CssLength.parse(value, false) instanceof CssLength.Pixels ? null
                    : "expected a non-negative pixel length; got \"" + value + "\"";
        } catch (MarkupException failure) {
            return "expected a non-negative pixel length; got \"" + value + "\"";
        }
    }

    private static String validateResponsiveLength(String value) {
        try {
            CssLength.parse(value, true);
            return null;
        } catch (MarkupException failure) {
            return "expected non-negative pixels, percent, or auto; got \"" + value + "\"";
        }
    }

    private static String validateSpacing(String value) {
        try {
            CssSpacing.parse(value);
            return null;
        } catch (MarkupException failure) {
            return "expected one to four non-negative pixel lengths; got \"" + value + "\"";
        }
    }

    private static String enumValue(String value, Set<String> accepted, String expected) {
        return accepted.contains(value) ? null
                : "expected " + expected + "; got \"" + value + "\"";
    }

    private static String validateFontSize(String value) {
        Matcher matcher = FONT_SIZE.matcher(value);
        if (matcher.matches()) {
            try {
                int parsed = Integer.parseInt(matcher.group(1));
                if (parsed >= TagSpec.MIN_FONT_SIZE && parsed <= TagSpec.MAX_FONT_SIZE) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the typed failure
            }
        }
        return "expected an integer from " + TagSpec.MIN_FONT_SIZE + " through "
                + TagSpec.MAX_FONT_SIZE + " with optional px suffix; got \"" + value + "\"";
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
            return scanTo(target, false, '\0');
        }

        /** Returns the index of the next of {@code first}/{@code second}; -1 at end. */
        int indexOfSkippingAnyOf(char first, char second) {
            return scanTo(first, true, second);
        }

        private int scanTo(char first, boolean matchSecond, char second) {
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
                } else if (c == first || (matchSecond && c == second)) {
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
