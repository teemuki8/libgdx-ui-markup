package dev.gdx.markup.core.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gdx.markup.core.Element;
import dev.gdx.markup.core.MarkupException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CssTest {
    private final CssParser parser = new CssParser();

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
        assertEquals("button", first.selectors().get(0).tag());
        assertEquals(2, first.properties().size());
        assertEquals("12px", first.properties().get("padding"));
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
    void unknownPropertyFailsWithRuleIndex() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                button { color: red; }
                label { display: none; }
                """));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertEquals("css", failure.elementPath());
        assertEquals(1, failure.line(), "failure must carry the zero-based rule index");
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
    void booleanValueAndMissingFallback() {
        CssDocument document = parser.parse("button { visible: false; }");
        ResolvedStyle style = new CssStyleResolver(document)
                .resolve(element("button", null, List.of()));
        assertFalse(style.booleanValue("visible", true));
        assertTrue(style.booleanValue("enabled", true));
        assertTrue(style.has("visible"));
        assertFalse(style.has("enabled"));
    }
}
