package dev.gdx.markup.core.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.markup.core.Element;
import dev.gdx.markup.core.MarkupException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CssTest {
    private final CssParser parser = new CssParser();

    @TempDir
    Path tempDir;

    private static Element element(String tag, String id, List<String> classes) {
        return new Element(tag, id, null, null, null, Map.of(), classes, List.of(), 1, 1);
    }

    @Test
    void parsesRulesAndProperties() {
        CssDocument document = parser.parse("""
                button { padding: 12px; background: accent; }
                label { font-color: #f4f7ff; }
                """);
        assertEquals(2, document.rules().size());
        CssRule first = document.rules().get(0);
        assertEquals(0, first.ruleIndex());
        assertEquals(1, first.line(), "first rule starts on line 1");
        assertEquals(1, first.column(), "first rule starts at column 1");
        assertEquals("button", first.selectors().get(0).tag());
        assertEquals(2, first.properties().size());
        assertEquals("12px", first.properties().get("padding"));
        CssRule second = document.rules().get(1);
        assertEquals(1, second.ruleIndex(), "rule index stays zero-based source order");
        assertEquals(2, second.line(), "second rule starts on line 2");
        assertEquals(1, second.column());
    }

    @Test
    void multilineCommentsAndBlankLinesAdvanceCoordinates() {
        CssDocument document = parser.parse("""
                /* first line
                   second line */
                button {
                    color: red;
                }

                label { width: 2px; }
                """);
        assertEquals(2, document.rules().size());
        CssRule first = document.rules().get(0);
        assertEquals(0, first.ruleIndex());
        assertEquals(3, first.line(), "selector after a two-line comment starts on line 3");
        assertEquals(1, first.column());
        assertEquals("red", first.properties().get("color"));
        CssRule second = document.rules().get(1);
        assertEquals(1, second.ruleIndex());
        assertEquals(7, second.line(), "blank line advances the rule start to line 7");
        assertEquals(1, second.column());
    }

    @Test
    void malformedSelectorReportsSelectorCoordinates() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                label { color: red; }
                button > span { color: blue; }
                """));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertEquals(2, failure.line(), "malformed selector sits on the second line");
        assertEquals(1, failure.column(), "selector group starts at column 1");
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void malformedValueReportsPropertyCoordinates() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button {\n    width: abc;\n}"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertEquals(2, failure.line(), "property token sits on the second source line");
        assertEquals(5, failure.column(), "property token starts at column 5");
        assertTrue(failure.getMessage().contains("width"));
    }

    @Test
    void selectorMatchingMatrix() {
        CssDocument document = parser.parse("""
                button { width: 1px; }
                .primary { height: 2px; }
                #save { min-width: 3px; }
                button.primary { min-height: 4px; }
                """);
        Element plain = element("button", null, List.of());
        Element primary = element("button", null, List.of("primary"));
        Element save = element("button", "save", List.of());
        CssStyleResolver resolver = new CssStyleResolver(document);
        assertEquals("1px", resolver.resolve(plain).get("width"));
        assertNull(resolver.resolve(plain).get("height"));
        assertEquals("2px", resolver.resolve(primary).get("height"));
        assertEquals("4px", resolver.resolve(primary).get("min-height"));
        assertEquals("3px", resolver.resolve(save).get("min-width"));
        Element label = element("label", null, List.of());
        assertNull(resolver.resolve(label).get("width"));
    }

    @Test
    void specificityOrdering() {
        CssDocument document = parser.parse("""
                button { color: from-tag; }
                .primary { color: from-class; }
                #save { color: from-id; }
                button.primary { color: from-tag-class; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        Element plain = element("button", null, List.of());
        assertEquals("from-tag", resolver.resolve(plain).get("color"));
        Element primary = element("button", null, List.of("primary"));
        assertEquals("from-tag-class", resolver.resolve(primary).get("color"));
        Element idAndClass = element("button", "save", List.of("primary"));
        assertEquals("from-id", resolver.resolve(idAndClass).get("color"));
    }

    @Test
    void laterRuleWinsTies() {
        CssDocument document = parser.parse("""
                button { color: first; }
                button { color: second; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        assertEquals("second", resolver.resolve(element("button", null, List.of())).get("color"));
    }

    @Test
    void laterRuleWinsSameSpecificityAcrossCompoundAndSimple() {
        CssDocument document = parser.parse("""
                .primary { color: from-class-only; }
                button.primary { color: from-compound; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        assertEquals("from-compound",
                resolver.resolve(element("button", null, List.of("primary"))).get("color"));
    }

    @Test
    void pseudoStateSelectsVariantWithoutReplacingBase() {
        CssDocument document = parser.parse("""
                button { background: base; padding: 8px; }
                button:hover { background: hovered; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        Element button = element("button", null, List.of());
        ResolvedStyle base = resolver.resolve(button);
        assertEquals("base", base.get("background"));
        assertEquals("8px", base.get("padding"));
        ResolvedStyle hover = resolver.resolve(button, "hover");
        assertEquals("hovered", hover.get("background"));
        assertEquals("8px", hover.get("padding"), "base properties survive in the variant");
        assertEquals("hovered", resolver.resolve(button, "hover").get("background"));
    }

    @Test
    void baseStyleDoesNotLeakIntoPseudoVariants() {
        CssDocument document = parser.parse("button:hover { background: hovered; }");
        CssStyleResolver resolver = new CssStyleResolver(document);
        Element button = element("button", null, List.of());
        assertNull(resolver.resolve(button).get("background"));
        assertEquals("hovered", resolver.resolve(button, "hover").get("background"));
        assertNull(resolver.resolve(button, "pressed").get("background"));
    }

    @Test
    void commaGroupRulesApplyToEverySelector() {
        CssDocument document = parser.parse("button, .btn { padding: 4px; }");
        CssStyleResolver resolver = new CssStyleResolver(document);
        assertEquals("4px", resolver.resolve(element("button", null, List.of())).get("padding"));
        assertEquals("4px", resolver.resolve(element("label", null, List.of("btn"))).get("padding"));
        assertNull(resolver.resolve(element("label", null, List.of())).get("padding"));
    }

    @Test
    void unknownPropertyReportsPropertyCoordinates() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                button { color: red; }
                label { display: none; }
                """));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertEquals("css", failure.elementPath());
        assertEquals(2, failure.line(), "property token sits on the second source line");
        assertEquals(9, failure.column(), "property token starts after 'label { '");
        assertTrue(failure.getMessage().contains("display"));
    }

    @Test
    void unparseableSelectorFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button > span { color: red; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void unknownPseudoStateFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button:focus { color: red; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertTrue(failure.getMessage().contains("focus"));
    }

    @Test
    void unknownTagSelectorFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "foobar { color: red; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertTrue(failure.getMessage().contains("foobar"));
    }

    @Test
    void invalidValuesFail() {
        MarkupException width = assertThrows(MarkupException.class, () -> parser.parse(
                "button { width: abc; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, width.kind());

        MarkupException color = assertThrows(MarkupException.class, () -> parser.parse(
                "button { color: 12345; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, color.kind());

        MarkupException visible = assertThrows(MarkupException.class, () -> parser.parse(
                "button { visible: maybe; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, visible.kind());

        MarkupException padding = assertThrows(MarkupException.class, () -> parser.parse(
                "button { padding: 1px 2px; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, padding.kind());
    }

    @Test
    void lengthsAcceptPxAndCommaLists() {
        CssDocument document = parser.parse("""
                button { padding: 28px; margin: 1,2,3,4; width: 100; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        ResolvedStyle style = resolver.resolve(element("button", null, List.of()));
        assertEquals(28f, style.length("padding", -1));
        assertEquals(List.of(1f, 2f, 3f, 4f), style.lengths("margin", List.of()));
        assertEquals(100f, style.length("width", -1));
    }

    @Test
    void commentsAreIgnored() {
        CssDocument document = parser.parse("""
                /* header comment */
                button { color: red; /* inline */ }
                """);
        assertEquals(1, document.rules().size());
        assertEquals("red", document.rules().get(0).properties().get("color"));
    }

    @Test
    void unterminatedRuleFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button { color: red;"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
    }

    @Test
    void missingSelectorFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "{ color: red; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
    }

    @Test
    void emptyRuleBodyFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button { }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
    }

    @Test
    void trailingGarbageFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button { color: red; } garbage"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
    }

    @Test
    void ruleCountLimitIsEnforced() {
        CssParser tiny = new CssParser(CssParser.MAX_INPUT_BYTES, 2);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> tiny.parse("button { color: red; }\nbutton { color: blue; }\n"
                        + "button { color: green; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
    }

    @Test
    void overlongSelectorIsTooLarge() {
        String longClass = "c" + "x".repeat(CssParser.MAX_SELECTOR_LENGTH);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> parser.parse("button { color: red; }\n." + longClass
                        + " { width: 1px; }"));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertEquals(2, failure.line(), "overlong selector sits on the second line");
        assertEquals(1, failure.column(), "selector group starts at column 1");
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void overlongSelectorIsRejectedBeforeSplitAllocation() {
        // The leading empty part would only surface after split(","); the length pre-pass must
        // reject the trailing overlong selector first, so the failure is TOO_LARGE, not the
        // split-time "empty selector" STYLE_ERROR.
        String css = "," + "." + "c" + "x".repeat(CssParser.MAX_SELECTOR_LENGTH);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> parser.parse(css + " { color: red; }"));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind(),
                "length validation precedes split allocation");
        assertEquals(1, failure.line());
        assertEquals(1, failure.column());
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void overlongSelectorWithEdgeControlCharactersIsTooLarge() {
        // String.strip() keeps ISO controls that are not whitespace (verified: NUL survives
        // strip), so the pre-pass must count them: a 258-char part with edge NULs is over the
        // limit and must fail TOO_LARGE, not fall through to a split-time STYLE_ERROR.
        String edgeOverlong = "\u0000" + "x".repeat(CssParser.MAX_SELECTOR_LENGTH) + "\u0000";
        MarkupException failure = assertThrows(MarkupException.class,
                () -> parser.parse(edgeOverlong + " { color: red; }"));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind(),
                "edge control characters count toward the selector length");
        assertEquals(1, failure.line());
        assertEquals(1, failure.column());
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void excessiveCommaGroupIsTooLarge() {
        String group = String.join(",", Collections.nCopies(
                CssParser.MAX_SELECTORS_PER_GROUP + 1, "button"));
        MarkupException failure = assertThrows(MarkupException.class,
                () -> parser.parse(group + " { color: red; }"));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertEquals(1, failure.line());
        assertEquals(1, failure.column());
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void totalSelectorLimitIsTooLarge() {
        StringBuilder css = new StringBuilder();
        for (int rule = 0; rule <= CssParser.MAX_TOTAL_SELECTORS / 3; rule++) {
            css.append("button, .a, #b { color: red; }\n");
        }
        // 1366 rules x 3 selectors = 4098 > 4096, while 1366 rules stay below the 2048 rule cap.
        MarkupException failure = assertThrows(MarkupException.class,
                () -> parser.parse(css.toString()));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertEquals(CssParser.MAX_TOTAL_SELECTORS / 3 + 1, failure.line(),
                "cap fires on the rule that pushes the total past the limit");
        assertEquals(1, failure.column());
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void selectorBoundsAreInclusive() {
        String atLength = "." + "x".repeat(CssParser.MAX_SELECTOR_LENGTH - 1);
        CssDocument atLengthDocument = parser.parse(atLength + " { width: 1px; }");
        assertEquals(1, atLengthDocument.rules().size());

        String atGroup = String.join(",", Collections.nCopies(
                CssParser.MAX_SELECTORS_PER_GROUP, "button"));
        CssDocument atGroupDocument = parser.parse(atGroup + " { color: red; }");
        assertEquals(CssParser.MAX_SELECTORS_PER_GROUP,
                atGroupDocument.rules().get(0).selectors().size());

        StringBuilder css = new StringBuilder();
        for (int rule = 0; rule < CssParser.MAX_TOTAL_SELECTORS / 4; rule++) {
            css.append("button, .a, #b, .c { color: red; }\n");
        }
        CssDocument atTotal = parser.parse(css.toString());
        assertEquals(CssParser.MAX_TOTAL_SELECTORS / 4, atTotal.rules().size());
    }

    @Test
    void resolveComparisonLimitIsTooLarge() {
        CssDocument document = parser.parse("""
                button { width: 1px; }
                .a { height: 2px; }
                #b { min-width: 3px; }
                """);
        CssStyleResolver tiny = new CssStyleResolver(document, 2,
                CssStyleResolver.MAX_COMPARISONS_PER_BUILD);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> tiny.resolve(element("label", null, List.of())));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertEquals("label", failure.elementPath());
        assertEquals(1, failure.line(), "located at the element being resolved");
        assertEquals(1, failure.column());
    }

    @Test
    void buildWideComparisonLimitIsTooLarge() {
        CssDocument document = parser.parse("""
                button { width: 1px; }
                label { height: 2px; }
                """);
        CssStyleResolver tiny = new CssStyleResolver(document,
                CssStyleResolver.MAX_COMPARISONS_PER_RESOLVE, 3);
        tiny.resolve(element("button", null, List.of()));
        MarkupException failure = assertThrows(MarkupException.class,
                () -> tiny.resolve(element("label", null, List.of())));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertEquals(1, failure.line());
        assertEquals(1, failure.column());
    }

    @Test
    void buildComparisonCounterDoesNotWrapAtIntegerMax() {
        CssDocument document = parser.parse("""
                button { width: 1px; }
                label { height: 2px; }
                """);
        // Seed the per-build counter one below Integer.MAX_VALUE: the second comparison of the
        // first resolve must hit the limit and fail, proving the counter never wraps past it.
        CssStyleResolver nearLimit = new CssStyleResolver(document.rules(),
                CssStyleResolver.MAX_COMPARISONS_PER_RESOLVE, Integer.MAX_VALUE,
                Integer.MAX_VALUE - 1);
        MarkupException failure = assertThrows(MarkupException.class,
                () -> nearLimit.resolve(element("button", null, List.of())));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind(),
                "the per-build counter must saturate at its limit instead of wrapping");
        assertEquals("button", failure.elementPath());
    }

    @Test
    void commaGroupUsesMaximumMatchingSpecificity() {
        CssDocument document = parser.parse("""
                .primary { color: from-class; }
                button, #save { color: from-group; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        Element save = element("button", "save", List.of("primary"));
        assertEquals("from-group", resolver.resolve(save).get("color"),
                "the #save part (100) must outrank the class rule (10), not stop at button (1)");
    }

    @Test
    void selectorOrderWithinGroupDoesNotChangeResult() {
        CssDocument document = parser.parse("""
                .primary { color: from-class; }
                #save, button { color: from-group; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        Element save = element("button", "save", List.of("primary"));
        assertEquals("from-group", resolver.resolve(save).get("color"),
                "the strongest matching part wins regardless of its position in the group");
    }

    @Test
    void commaGroupTieBreakPreservesRuleSourceOrder() {
        CssDocument document = parser.parse("""
                #save, button { color: first; }
                button, #save { color: second; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        Element save = element("button", "save", List.of());
        assertEquals("second", resolver.resolve(save).get("color"),
                "equal maximum specificity defers to the later rule in source order");
    }

    @Test
    void mixedGroupOutranksCompoundSelector() {
        CssDocument document = parser.parse("""
                button.primary { color: from-compound; }
                button, #save { color: from-group; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        Element save = element("button", "save", List.of("primary"));
        assertEquals("from-group", resolver.resolve(save).get("color"),
                "the #save part (100) beats tag.class (11) even though button (1) also matches");
    }

    @Test
    void booleanValueAndMissingFallback() {
        CssDocument document = parser.parse("button { visible: false; }");
        ResolvedStyle style = new CssStyleResolver(document)
                .resolve(element("button", null, List.of()));
        assertFalse(style.booleanValue("visible", true));
        assertTrue(style.booleanValue("enabled", true));
        assertTrue(style.has("visible"));
        assertFalse(style.has("enabled"));
    }

    @Test
    void exactByteLimitPathStylesheetParses() throws Exception {
        // A comment-only stylesheet carries the whole payload: comments are skipped by the
        // scanner, so it parses to zero rules at exactly MAX_INPUT_BYTES.
        String css = "/*" + "x".repeat(CssParser.MAX_INPUT_BYTES - 4) + "*/";
        assertEquals(CssParser.MAX_INPUT_BYTES,
                css.getBytes(StandardCharsets.UTF_8).length);
        Path file = tempDir.resolve("exact.css");
        Files.write(file, css.getBytes(StandardCharsets.UTF_8));
        CssDocument document = parser.parse(file);
        assertTrue(document.rules().isEmpty());
        assertEquals(CssParser.MAX_INPUT_BYTES, document.byteLength());
    }

    @Test
    void limitPlusOneBytePathStylesheetIsRejectedBeforeDecoding() throws Exception {
        // The final byte starts a two-byte UTF-8 sequence, so a decode-first implementation
        // (like Files.readString) would fail with an IOException; the bounded reader must
        // reject on size before any decoding or String materialization.
        byte[] over = new byte[CssParser.MAX_INPUT_BYTES + 1];
        Arrays.fill(over, (byte) 'x');
        over[over.length - 1] = (byte) 0xC3;
        Path file = tempDir.resolve("over.css");
        Files.write(file, over);
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(file));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void truncatedMultibytePathStylesheetFailsDeterministically() throws Exception {
        byte[] truncated = "button { color: red; }".getBytes(StandardCharsets.UTF_8);
        byte[] withPartial = Arrays.copyOf(truncated, truncated.length + 1);
        withPartial[withPartial.length - 1] = (byte) 0xC3;
        Path file = tempDir.resolve("truncated.css");
        Files.write(file, withPartial);
        MarkupException first = assertThrows(MarkupException.class, () -> parser.parse(file));
        assertEquals(MarkupException.Kind.STYLE_ERROR, first.kind());
        MarkupException second = assertThrows(MarkupException.class, () -> parser.parse(file));
        assertEquals(first.kind(), second.kind());
        assertEquals(first.getMessage(), second.getMessage());
    }
}
