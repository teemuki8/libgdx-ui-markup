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
    void parsesExpandedColorValuesAndBackgroundTint() {
        CssDocument document = parser.parse("""
                label { color: #abc; font-color: rgb(1, 2, 3); }
                button { background-color: rgba(4, 5, 6, 0.5); }
                .hidden { color: transparent; }
                """);
        assertEquals("#abc", document.rules().get(0).properties().get("color"));
        assertEquals("rgb(1, 2, 3)",
                document.rules().get(0).properties().get("font-color"));
        assertEquals("rgba(4, 5, 6, 0.5)",
                document.rules().get(1).properties().get("background-color"));
        assertEquals("transparent", document.rules().get(2).properties().get("color"));
    }

    @Test
    void normalizesFontFamilyAliasByDeclarationSourceOrder() {
        CssDocument legacyLast = parser.parse("""
                label { font-family: heading; font: inter; }
                """);
        assertEquals(Map.of("font", "inter"), legacyLast.rules().getFirst().properties());

        CssDocument standardLast = parser.parse("""
                label { font: inter; font-family: heading; }
                """);
        assertEquals(Map.of("font", "heading"), standardLast.rules().getFirst().properties());
        assertFalse(standardLast.rules().getFirst().properties().containsKey("font-family"));
    }

    @Test
    void parsesClosedTextAndImagePropertyValues() {
        CssDocument document = parser.parse("""
                label { white-space: normal; text-overflow: ellipsis; }
                image { object-fit: cover; object-position: right bottom; }
                """);
        assertEquals("normal", document.rules().get(0).properties().get("white-space"));
        assertEquals("ellipsis", document.rules().get(0).properties().get("text-overflow"));
        assertEquals("cover", document.rules().get(1).properties().get("object-fit"));
        assertEquals("right bottom",
                document.rules().get(1).properties().get("object-position"));

        for (String value : List.of("pre", "pre-wrap")) {
            assertThrows(MarkupException.class,
                    () -> parser.parse("label { white-space: " + value + "; }"));
        }
        assertThrows(MarkupException.class,
                () -> parser.parse("label { text-overflow: fade; }"));
        assertThrows(MarkupException.class,
                () -> parser.parse("image { object-fit: scale-down; }"));
        assertThrows(MarkupException.class,
                () -> parser.parse("image { object-position: top left; }"));
        assertThrows(MarkupException.class,
                () -> parser.parse("image:hover { object-fit: contain; }"));
    }

    @Test
    void parsesClosedActorPaintInputAndTransformValues() {
        CssDocument document = parser.parse("""
                image {
                  opacity: 0.25;
                  pointer-events: none;
                  scale: 2 0.5;
                  rotate: -30deg;
                  transform-origin: right bottom;
                }
                """);
        Map<String, String> properties = document.rules().getFirst().properties();
        assertEquals("0.25", properties.get("opacity"));
        assertEquals("none", properties.get("pointer-events"));
        assertEquals("2 0.5", properties.get("scale"));
        assertEquals("-30deg", properties.get("rotate"));
        assertEquals("right bottom", properties.get("transform-origin"));

        for (String opacity : List.of("-0.1", "1.1", "50%", "NaN", "0.5 trailing")) {
            assertThrows(MarkupException.class,
                    () -> parser.parse("image { opacity: " + opacity + "; }"));
        }
        for (String scale : List.of("0", "-1", "1 0", "1 2 3", "NaN", "1px")) {
            assertThrows(MarkupException.class,
                    () -> parser.parse("image { scale: " + scale + "; }"));
        }
        for (String rotate : List.of("30", "deg", "NaNdeg", "Infinitydeg", "3rad")) {
            assertThrows(MarkupException.class,
                    () -> parser.parse("image { rotate: " + rotate + "; }"));
        }
        assertThrows(MarkupException.class,
                () -> parser.parse("image { pointer-events: painted; }"));
        assertThrows(MarkupException.class,
                () -> parser.parse("image { transform-origin: top left; }"));
        assertThrows(MarkupException.class,
                () -> parser.parse("image:hover { opacity: 0.5; }"));
        assertThrows(MarkupException.class,
                () -> parser.parse("image:pressed { scale: 2; }"));
    }

    @Test
    void malformedColorReportsExactDeclarationCoordinates() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                button {
                  width: 10px;
                  background-color: rgb(256, 0, 0);
                }
                """));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertEquals(3, failure.line());
        assertEquals(3, failure.column());
        assertTrue(failure.getMessage().contains("background-color"));
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
    void parsesBoundedCompoundAndStructuralSelectorAst() {
        CssDocument document = parser.parse("""
                * { color: text; }
                table.shell > group.content.primary label#title.emphasis:hover { color: accent; }
                button:active { color: pressed; }
                textfield:focus { color: accent; }
                table/* separator */>/* separator */button { opacity: 1; }
                """);

        Selector universal = document.rules().get(0).selectors().getFirst();
        assertEquals(1, universal.parts().size());
        assertNull(universal.tag());
        assertEquals(0, universal.specificity());

        Selector structural = document.rules().get(1).selectors().getFirst();
        assertEquals(3, structural.parts().size());
        assertEquals("label", structural.tag());
        assertEquals("title", structural.id());
        assertEquals("emphasis", structural.className());
        assertEquals("hover", structural.pseudo());
        assertEquals(153, structural.specificity());
        assertEquals(SelectorPart.Combinator.SELF,
                structural.parts().get(0).combinator());
        assertEquals(List.of("emphasis"), structural.parts().get(0).classNames());
        assertEquals(SelectorPart.Combinator.DESCENDANT,
                structural.parts().get(1).combinator());
        assertEquals(List.of("content", "primary"),
                structural.parts().get(1).classNames());
        assertEquals(SelectorPart.Combinator.CHILD,
                structural.parts().get(2).combinator());

        assertEquals("pressed", document.rules().get(2).selectors().getFirst().pseudo(),
                ":active is the source-compatible spelling of the pressed state");
        assertEquals("focus", document.rules().get(3).selectors().getFirst().pseudo());
        assertEquals(2, document.rules().get(4).selectors().getFirst().parts().size(),
                "comments are selector whitespace, including around child combinators");
    }

    @Test
    void selectorStructureAndGrammarRemainClosedAndBounded() {
        String eight = "ui table group table group table group button";
        assertEquals(8, parser.parse(eight + " { color: text; }")
                .rules().getFirst().selectors().getFirst().parts().size());

        MarkupException tooDeep = assertThrows(MarkupException.class, () -> parser.parse(
                "ui table group table group table group table button { color: text; }"));
        assertEquals(MarkupException.Kind.TOO_LARGE, tooDeep.kind());

        for (String selector : List.of(
                "button + label", "button ~ label", "button[disabled]", "button::after",
                "button:not(.primary)", "button:has(label)", "button >",
                "> button", "table button:hover label", "button#one#two")) {
            MarkupException failure = assertThrows(MarkupException.class,
                    () -> parser.parse(selector + " { color: text; }"), selector);
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind(), selector);
        }
    }

    @Test
    void structuralResolutionUsesAncestryChildAndDescendantSemantics() {
        CssDocument document = parser.parse("""
                table button { color: descendant; }
                table > button { background: direct; }
                table > group button { font-color: backtracked; }
                """);
        Element table = element("table", null, List.of());
        Element outerGroup = element("group", null, List.of());
        Element innerGroup = element("group", null, List.of());
        Element button = element("button", null, List.of());
        CssStyleResolver resolver = new CssStyleResolver(document);

        ResolvedStyle nested = resolver.resolve(button,
                List.of(table, outerGroup, innerGroup), null, "ui/table/group/group/button");
        assertEquals("descendant", nested.get("color"));
        assertNull(nested.get("background"));
        assertEquals("backtracked", nested.get("font-color"),
                "descendant matching backtracks when a later child part requires it");
        assertNull(new CssStyleResolver(document).resolve(button).get("color"),
                "legacy overload has empty ancestry and cannot match structural selectors");

        ResolvedStyle direct = new CssStyleResolver(document).resolve(button,
                List.of(table), null, null);
        assertEquals("direct", direct.get("background"));
    }

    @Test
    void structuralMatchingCountsEveryAttemptAgainstWorkLimit() {
        CssDocument document = parser.parse("table group button { color: text; }");
        Element table = element("table", null, List.of());
        Element group = element("group", null, List.of());
        Element button = element("button", null, List.of());
        assertThrows(MarkupException.class, () -> new CssStyleResolver(
                document.rules(), 2, 100).resolve(button, List.of(table, group), null, "button"));
        assertEquals("text", new CssStyleResolver(document.rules(), 3, 100)
                .resolve(button, List.of(table, group), null, "button").get("color"));
    }

    @Test
    void rootVariablesResolveForwardReferencesBeforePropertyValidation() {
        CssDocument document = parser.parse("""
                :root {
                  --panel-width: var(--space-lg);
                  --space-lg: 24px;
                  --surface: #182026;
                }
                .panel { width: var(--panel-width); background-color: var(--surface); }
                """);
        assertEquals("24px", document.variables().get("--panel-width"));
        assertEquals("24px", document.variables().get("--space-lg"));
        assertEquals("#182026", document.variables().get("--surface"));
        assertEquals("24px", document.rules().getFirst().properties().get("width"));
        assertEquals("#182026",
                document.rules().getFirst().properties().get("background-color"));
    }

    @Test
    void rootVariablesRemainClosedBoundedAndLocated() {
        for (String cssText : List.of(
                ":root { --a: var(--missing); } button { width: var(--a); }",
                ":root { --a: var(--b); --b: var(--a); } button { width: 1px; }",
                ":root { --a: 1px; } :root { --b: 2px; } button { width: 1px; }",
                ":root { --bad.name: 1px; } button { width: 1px; }",
                ":root { --a: 1px; } button { width: calc(var(--a) + 1px); }",
                ":root { --a: 1px; } button { width: var(--a, 2px); }")) {
            assertThrows(MarkupException.class, () -> parser.parse(cssText), cssText);
        }

        StringBuilder variables = new StringBuilder(":root {");
        for (int index = 0; index <= CssVariables.MAX_VARIABLES; index++) {
            variables.append("--v").append(index).append(": 1px;");
        }
        variables.append("} button { width: 1px; }");
        MarkupException tooMany = assertThrows(MarkupException.class,
                () -> parser.parse(variables.toString()));
        assertEquals(MarkupException.Kind.TOO_LARGE, tooMany.kind());

        MarkupException postValidation = assertThrows(MarkupException.class, () -> parser.parse("""
                :root { --bad-width: #fff; }
                button {
                  width: var(--bad-width);
                }
                """));
        assertEquals(3, postValidation.line());
        assertEquals(3, postValidation.column());

        StringBuilder depth16 = new StringBuilder(":root {");
        for (int index = 1; index < 16; index++) {
            depth16.append("--v").append(index).append(": var(--v")
                    .append(index + 1).append(");");
        }
        depth16.append("--v16: 8px; } button { width: var(--v1); }");
        assertEquals("8px", parser.parse(depth16.toString()).rules().getFirst()
                .properties().get("width"));

        StringBuilder depth17 = new StringBuilder(":root {");
        for (int index = 1; index < 17; index++) {
            depth17.append("--v").append(index).append(": var(--v")
                    .append(index + 1).append(");");
        }
        depth17.append("--v17: 8px; } button { width: var(--v1); }");
        assertThrows(MarkupException.class, () -> parser.parse(depth17.toString()));
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
                label { grid-template: none; }
                """));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertEquals("css", failure.elementPath());
        assertEquals(2, failure.line(), "property token sits on the second source line");
        assertEquals(9, failure.column(), "property token starts after 'label { '");
        assertTrue(failure.getMessage().contains("grid-template"));
    }

    @Test
    void unparseableSelectorFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button + label { color: red; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void unknownPseudoStateFails() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                "button:visited { color: red; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertTrue(failure.getMessage().contains("visited"));
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
                "button { padding: 1px 2px 3px 4px 5px; }"));
        assertEquals(MarkupException.Kind.STYLE_ERROR, padding.kind());
    }

    @Test
    void fontSizeAcceptsBoundedIntegersWithOptionalPxSuffix() {
        CssDocument document = parser.parse("""
                label.title { font: inter; font-size: 28px; }
                button { font-size: 16; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);

        ResolvedStyle label = resolver.resolve(element("label", null, List.of("title")));
        assertEquals("inter", label.get("font"));
        assertEquals("28px", label.get("font-size"));
        assertEquals("16", resolver.resolve(element("button", null, List.of()))
                .get("font-size"));
    }

    @Test
    void fontSizeRejectsFractionsAndValuesOutsideTheBoundedRange() {
        for (String value : List.of("3px", "257px", "16.5px", "-1px", "big")) {
            MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                    "label { font-size: " + value + "; }"), value);
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind(), value);
            assertTrue(failure.getMessage().contains("font-size"), value);
        }
    }

    @Test
    void pseudoStateCannotChangeFontSize() {
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse("""
                button:hover {
                    font-size: 20px;
                }
                """));

        assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind());
        assertEquals(2, failure.line());
        assertEquals(5, failure.column());
        assertTrue(failure.getMessage().contains("font-size"));
        assertTrue(failure.getMessage().contains("pseudo-state"));
    }

    @Test
    void lengthsAcceptPxAndCommaLists() {
        CssDocument document = parser.parse("""
                button { padding: 28px; margin: 1,2,3,4; width: 100; }
                """);
        CssStyleResolver resolver = new CssStyleResolver(document);
        ResolvedStyle style = resolver.resolve(element("button", null, List.of()));
        assertEquals(28f, style.length("padding", -1));
        assertEquals(List.of(28f), style.lengths("padding", List.of()),
                "the compatibility accessor preserves declared shorthand arity");
        assertEquals(List.of(1f, 2f, 3f, 4f), style.lengths("margin", List.of()));
        assertEquals(100f, style.length("width", -1));
    }

    @Test
    void responsiveLayoutPropertiesAcceptOnlyTheirClosedValueSets() {
        CssDocument document = parser.parse("""
                table {
                    max-width: 90%; max-height: auto;
                    gap: 4px 8px; row-gap: 5; column-gap: 6px;
                    display: initial; visibility: hidden;
                    overflow: hidden; vertical-align: middle;
                }
                """);
        ResolvedStyle style = new CssStyleResolver(document)
                .resolve(element("table", null, List.of()));
        assertEquals("90%", style.get("max-width"));
        assertEquals("auto", style.get("max-height"));
        assertEquals("4px 8px", style.get("gap"));
        assertEquals("hidden", style.get("visibility"));
        assertEquals("middle", style.get("vertical-align"));

        for (String declaration : List.of(
                "gap: 10%", "display: block", "visibility: collapse",
                "overflow: scroll", "vertical-align: baseline")) {
            MarkupException failure = assertThrows(MarkupException.class,
                    () -> parser.parse("table { " + declaration + "; }"), declaration);
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind(), declaration);
        }
    }

    @Test
    void baseOnlyLayoutPropertiesAreRejectedInPseudoStateRulesAtDeclarationLocation() {
        for (String declaration : List.of(
                "width: 100%", "max-height: 10px", "display: none", "gap: 4px",
                "row-gap: 4px", "column-gap: 4px", "visibility: hidden",
                "overflow: hidden", "vertical-align: bottom")) {
            MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(
                    "button:hover {\n    " + declaration + ";\n}"), declaration);
            assertEquals(MarkupException.Kind.STYLE_ERROR, failure.kind(), declaration);
            assertEquals(2, failure.line(), declaration);
            assertEquals(5, failure.column(), declaration);
            assertTrue(failure.getMessage().contains("pseudo-state"), declaration);
        }
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

    @Test
    void limitPlusOneByteStringStylesheetIsRejectedAsTooLarge() {
        String css = "/*" + "x".repeat(CssParser.MAX_INPUT_BYTES - 3) + "*/";
        MarkupException failure = assertThrows(MarkupException.class, () -> parser.parse(css));
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind(),
                "the String entry point must share the same TOO_LARGE kind as parse(Path)");
        assertTrue(failure.getMessage().contains("limit"));
    }

    @Test
    void nextBufferGrowthDoublesBelowHalfCapacity() {
        assertEquals(8192, CssParser.nextBufferLength(4096, 10_000));
    }

    @Test
    void nextBufferGrowthCapsAtCapacityFromHalfWay() {
        assertEquals(10_000, CssParser.nextBufferLength(8192, 10_000));
    }

    @Test
    void nextBufferGrowthNeverOverflowsNearIntegerMax() {
        // current * 2 would wrap to Integer.MIN_VALUE around 2^30; the growth must cap at
        // capacity instead of overflowing (a negative length would throw NegativeArraySizeException).
        assertEquals(1_073_741_824, CssParser.nextBufferLength(1_073_741_824, 1_073_741_824));
        assertEquals(2_147_483_647, CssParser.nextBufferLength(1_073_741_824, 2_147_483_647));
        assertEquals(2_147_483_647, CssParser.nextBufferLength(1_073_741_823, 2_147_483_647));
        assertEquals(2_147_483_644, CssParser.nextBufferLength(1_073_741_822, 2_147_483_647));
    }
}
