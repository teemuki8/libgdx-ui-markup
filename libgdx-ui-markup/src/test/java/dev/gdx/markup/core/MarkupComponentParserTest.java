package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MarkupComponentParserTest {
    private static final String NESTED_COMPONENT_XML = """
            <ui><components>
              <component name="Action"><button text="Act"/></component>
              <component name="Card"><table><label text="Card"/><use component="Action"/></table></component>
            </components><use component="Card"/></ui>
            """;

    private final MarkupParser parser = new MarkupParser();

    @Test
    void expandsParametersTextAndRootOverridesIntoOnlyConcreteElements() {
        MarkupDocument document = parser.parse("""
                <ui>
                  <components>
                    <component name="PrimaryButton">
                      <param name="id" required="true"/>
                      <param name="text" default="Continue"/>
                      <button class="component-button" id="${id}-template"
                              text="${text}" name="${text}" width="100"/>
                    </component>
                  </components>
                  <use component="PrimaryButton" id="save" text="Save"
                       class="wide component-button" width="180" data-screen="menu"/>
                </ui>
                """);

        Element button = document.root().children().getFirst();
        assertEquals("button", button.tag());
        assertEquals("save", button.id());
        assertEquals("Save", button.text());
        assertEquals("Save", button.name());
        assertEquals("180", button.attr("width"));
        assertEquals("menu", button.attr("data-screen"));
        assertEquals(List.of("component-button", "wide"), button.classes());
        assertTrue(document.root().children().stream()
                .noneMatch(child -> Set.of("components", "component", "param", "use")
                        .contains(child.tag())));
    }

    @Test
    void defaultAndOptionalParametersResolveDeterministically() {
        MarkupDocument document = parser.parse("""
                <ui><components><component name="Badge">
                  <param name="prefix"/>
                  <param name="text" default="Ready"/>
                  <label text="${prefix}${text}"/>
                </component></components><use component="Badge"/></ui>
                """);

        assertEquals("Ready", document.root().children().getFirst().text());
    }

    @Test
    void componentContractPublishesExactBounds() {
        assertEquals(256, MarkupParser.MAX_COMPONENTS);
        assertEquals(64, MarkupParser.MAX_COMPONENT_PARAMETERS);
        assertEquals(32, MarkupParser.MAX_COMPONENT_SLOTS);
        assertEquals(32, MarkupParser.MAX_SUBSTITUTIONS_PER_VALUE);
        assertEquals(16, MarkupParser.MAX_COMPONENT_EXPANSION_DEPTH);
        assertEquals(100_000, MarkupParser.MAX_EXPANSION_WORK);
    }

    @Test
    void componentsBlockMustBeTheFirstUiChildAndUnique() {
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<ui><label/><components/></ui>");
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<ui><components/><components/></ui>");
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<table><components/></table>");
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<ui><table><components/></table></ui>");
    }

    @Test
    void componentsBlockAcceptsOnlyComponentDefinitions() {
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<ui><components><label/></components></ui>");
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<ui><components>not a definition</components></ui>");
    }

    @Test
    void unusedDefinitionsStillValidateVocabularyReferencesAndCycles() {
        assertKind(
                MarkupException.Kind.UNKNOWN_TAG,
                """
                <ui><components><component name="Unused"><bogus/></component></components>
                  <label text="Ready"/>
                </ui>
                """);
        assertKind(
                MarkupException.Kind.UNKNOWN_COMPONENT,
                """
                <ui><components><component name="Unused">
                  <use component="Missing"/>
                </component></components><label text="Ready"/></ui>
                """);
        assertKind(
                MarkupException.Kind.UNKNOWN_PARAMETER,
                """
                <ui><components>
                  <component name="Target"><param name="value"/><label text="${value}"/></component>
                  <component name="Unused"><use component="Target" vale="bad"/></component>
                </components><label text="Ready"/></ui>
                """);
        assertKind(
                MarkupException.Kind.COMPONENT_CYCLE,
                """
                <ui><components>
                  <component name="First"><use component="Second"/></component>
                  <component name="Second"><use component="First"/></component>
                </components><label text="Ready"/></ui>
                """);
        assertKind(
                MarkupException.Kind.UNKNOWN_TAG,
                """
                <ui><components><component name="Panel"><table>
                  <slot><bogus/></slot>
                </table></component></components>
                <use component="Panel"><fill><label text="Used"/></fill></use></ui>
                """);
    }

    @Test
    void unusedDefinitionsHonorCustomTagAdmission() {
        MarkupDocument document = new MarkupParser(Set.of("inventory-slot")).parse("""
                <ui><components><component name="Unused">
                  <inventory-slot data-owner="inventory"/>
                </component></components><label text="Ready"/></ui>
                """);

        assertEquals("label", document.root().children().getFirst().tag());
    }

    @Test
    void unusedNestedFillContentUsesOrdinaryCallerGrammar() {
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components>
                  <component name="Wrapper"><table><slot/></table></component>
                  <component name="Unused">
                    <use component="Wrapper"><fill><slot/></fill></use>
                  </component>
                </components><label text="Ready"/></ui>
                """);
    }

    @Test
    void unusedNestedPassThroughExpansionUsesTheRealDepth() {
        StringBuilder xml = new StringBuilder("<ui><components>");
        for (int index = 0; index < 17; index++) {
            xml.append("<component name=\"W").append(index)
                    .append("\"><table><slot/></table></component>");
        }
        xml.append("<component name=\"Unused\">");
        for (int index = 0; index < 17; index++) {
            xml.append("<use component=\"W").append(index).append("\"><fill>");
        }
        xml.append("<label text=\"Deep\"/>");
        for (int index = 0; index < 17; index++) {
            xml.append("</fill></use>");
        }
        xml.append("</component></components><label text=\"Ready\"/></ui>");

        assertKind(MarkupException.Kind.TOO_LARGE, xml.toString());
    }

    @Test
    void componentNamesAreRequiredPascalCaseAndUnique() {
        assertKind(
                MarkupException.Kind.MISSING_ATTRIBUTE,
                "<ui><components><component><label/></component></components></ui>");
        for (String name : List.of("badge", "Bad-Name", "1Badge", "B".repeat(65))) {
            assertKind(
                    MarkupException.Kind.INVALID_VALUE,
                    "<ui><components><component name=\"" + name
                            + "\"><label/></component></components></ui>");
        }
        assertKind(
                MarkupException.Kind.DUPLICATE_COMPONENT,
                """
                <ui><components>
                  <component name="Badge"><label/></component>
                  <component name="Badge"><label/></component>
                </components></ui>
                """);
    }

    @Test
    void parameterDeclarationsAreOrderedNamedUniqueAndConsistent() {
        assertKind(
                MarkupException.Kind.MISSING_ATTRIBUTE,
                """
                <ui><components><component name="Badge">
                  <param/><label/>
                </component></components></ui>
                """);
        for (String name : List.of("Text", "bad_name", "1text", "p".repeat(65))) {
            assertKind(
                    MarkupException.Kind.INVALID_VALUE,
                    "<ui><components><component name=\"Badge\"><param name=\""
                            + name + "\"/><label/></component></components></ui>");
        }
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Badge">
                  <param name="text" required="maybe"/><label/>
                </component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Badge">
                  <param name="text" required="true" default="Ready"/><label/>
                </component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Badge">
                  <param name="text" default="${other}"/><label/>
                </component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Badge">
                  <param name="text"/><param name="text"/><label/>
                </component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Badge">
                  <label/><param name="late"/>
                </component></components></ui>
                """);
    }

    @Test
    void invocationRejectsMissingUnknownAndUndeclaredParameters() {
        String definitions = """
                <components>
                  <component name="Badge">
                    <param name="text" required="true"/>
                    <label text="${text}"/>
                  </component>
                </components>
                """;
        assertKind(
                MarkupException.Kind.MISSING_PARAMETER,
                "<ui>" + definitions + "<use component=\"Badge\"/></ui>");
        assertKind(
                MarkupException.Kind.UNKNOWN_COMPONENT,
                "<ui>" + definitions + "<use component=\"Badger\" text=\"Ready\"/></ui>");
        assertKind(
                MarkupException.Kind.UNKNOWN_PARAMETER,
                "<ui>" + definitions
                        + "<use component=\"Badge\" text=\"Ready\" typo=\"x\"/></ui>");
        assertKind(
                MarkupException.Kind.UNKNOWN_PARAMETER,
                """
                <ui><components><component name="Badge">
                  <label text="${missing}"/>
                </component></components><use component="Badge"/></ui>
                """);
    }

    @Test
    void componentDefinitionRequiresExactlyOneConcreteActorRoot() {
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<ui><components><component name=\"Empty\"/></components></ui>");
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Double">
                  <label/><button/>
                </component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="RowRoot">
                  <row/>
                </component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="DocumentRoot">
                  <ui/>
                </component></components><use component="DocumentRoot"/></ui>
                """);
    }

    @Test
    void substitutionPreservesLiteralDollarAndBackslashCharacters() {
        MarkupDocument document = parser.parse("""
                <ui><components><component name="Message">
                  <param name="text" required="true"/>
                  <label>${text}</label>
                </component></components>
                <use component="Message" text="cost$5\\path"/>
                </ui>
                """);

        assertEquals("cost$5\\path", document.root().children().getFirst().text());
    }

    @Test
    void rootOverridesAreValidatedOnTheExpandedConcreteTag() {
        MarkupException failure = assertThrows(
                MarkupException.class,
                () -> parser.parse("""
                        <ui><components><component name="Badge">
                          <label text="Ready"/>
                        </component></components>
                        <use component="Badge" expand="diagonal"/>
                        </ui>
                        """));

        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
        assertEquals("ui/label", failure.elementPath());
        assertEquals("expand", failure.attribute());
    }

    @Test
    void fillsNamedAndDefaultSlotsAndUsesFallbacks() {
        MarkupDocument filled = parser.parse("""
                <ui><components><component name="Panel">
                  <param name="title" required="true"/>
                  <table class="panel">
                    <label text="${title}"/>
                    <slot/>
                    <slot name="footer"><label text="Default footer"/></slot>
                  </table>
                </component></components>
                <use component="Panel" title="Inventory">
                  <fill><label id="item" text="Potion"/></fill>
                  <fill slot="footer"><button id="close" text="Close"/></fill>
                </use></ui>
                """);
        Element panel = filled.root().children().getFirst();
        assertEquals(
                List.of("Inventory", "Potion", "Close"),
                panel.children().stream().map(Element::text).toList());

        MarkupDocument fallback = parser.parse("""
                <ui><components><component name="Panel">
                  <param name="title" required="true"/>
                  <table><label text="${title}"/>
                    <slot name="footer"><label text="${title} footer"/></slot>
                  </table>
                </component></components><use component="Panel" title="Default"/></ui>
                """);
        assertEquals(
                List.of("Default", "Default footer"),
                fallback.root().children().getFirst().children().stream()
                        .map(Element::text).toList());
    }

    @Test
    void callerFillDoesNotInheritInvokedComponentParameters() {
        MarkupDocument document = parser.parse("""
                <ui><components><component name="Panel">
                  <param name="title" required="true"/>
                  <table><slot/></table>
                </component></components>
                <use component="Panel" title="Inventory">
                  <fill><label text="${title}"/></fill>
                </use></ui>
                """);

        assertEquals(
                "${title}",
                document.root().children().getFirst().children().getFirst().text());
    }

    @Test
    void nestedComponentsExpandDepthFirst() {
        MarkupDocument document = parser.parse(NESTED_COMPONENT_XML);

        assertEquals(
                List.of("label", "button"),
                document.root().children().getFirst().children().stream()
                        .map(Element::tag).toList());
    }

    @Test
    void outerParametersResolveBeforeNestedInvocation() {
        MarkupDocument document = parser.parse("""
                <ui><components>
                  <component name="Action"><param name="text" required="true"/>
                    <button text="${text}"/></component>
                  <component name="Card"><param name="action" required="true"/>
                    <use component="Action" text="${action}"/></component>
                </components><use component="Card" action="Save"/></ui>
                """);

        assertEquals("Save", document.root().children().getFirst().text());
    }

    @Test
    void slotDeclarationsMustBeNamedUniqueAndConsistent() {
        assertKind(
                MarkupException.Kind.DUPLICATE_SLOT,
                """
                <ui><components><component name="Panel"><table>
                  <slot/><slot/>
                </table></component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.DUPLICATE_SLOT,
                """
                <ui><components><component name="Panel"><table>
                  <slot name="footer"/><slot name="footer"/>
                </table></component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Panel"><table>
                  <slot name="Bad_Name"/>
                </table></component></components></ui>
                """);
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                """
                <ui><components><component name="Panel"><table>
                  <slot name="footer" required="true"><label/></slot>
                </table></component></components></ui>
                """);
    }

    @Test
    void fillsMustTargetKnownSlotsExactlyOnce() {
        String definition = """
                <components><component name="Panel"><table>
                  <slot/><slot name="footer"/>
                </table></component></components>
                """;
        assertKind(
                MarkupException.Kind.UNKNOWN_SLOT,
                "<ui>" + definition
                        + "<use component=\"Panel\"><fill slot=\"header\"><label/></fill></use></ui>");
        assertKind(
                MarkupException.Kind.DUPLICATE_SLOT,
                "<ui>" + definition
                        + "<use component=\"Panel\"><fill><label/></fill><fill><button/></fill></use></ui>");
        assertKind(
                MarkupException.Kind.INVALID_VALUE,
                "<ui>" + definition
                        + "<use component=\"Panel\"><label/></use></ui>");
    }

    @Test
    void requiredSlotMustBeFilled() {
        assertKind(
                MarkupException.Kind.MISSING_SLOT,
                """
                <ui><components><component name="Dialog"><table>
                  <slot name="actions" required="true"/>
                </table></component></components><use component="Dialog"/></ui>
                """);
    }

    @Test
    void directAndIndirectCyclesFailWithCompleteBoundedChain() {
        MarkupException direct = assertFailure("""
                <ui><components><component name="Menu">
                  <use component="Menu"/>
                </component></components><use component="Menu"/></ui>
                """);
        assertEquals(MarkupException.Kind.COMPONENT_CYCLE, direct.kind());
        assertTrue(direct.getMessage().contains("Menu -> Menu"));

        MarkupException indirect = assertFailure("""
                <ui><components>
                  <component name="Menu"><use component="MenuItem"/></component>
                  <component name="MenuItem"><use component="Menu"/></component>
                </components><use component="Menu"/></ui>
                """);
        assertEquals(MarkupException.Kind.COMPONENT_CYCLE, indirect.kind());
        assertTrue(indirect.getMessage().contains("Menu -> MenuItem -> Menu"));
    }

    @Test
    void componentExpansionDepthIsBoundedAtSixteen() {
        StringBuilder xml = new StringBuilder("<ui><components>");
        for (int index = 0; index < 17; index++) {
            xml.append("<component name=\"C").append(index).append("\">");
            if (index == 16) {
                xml.append("<label/>");
            } else {
                xml.append("<use component=\"C").append(index + 1).append("\"/>");
            }
            xml.append("</component>");
        }
        xml.append("</components><use component=\"C0\"/></ui>");

        MarkupException failure = assertFailure(xml.toString());
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertEquals(16, failure.componentTrace().size());
    }

    @Test
    void componentParameterAndSlotDeclarationCountsAreBounded() {
        StringBuilder components = new StringBuilder("<ui><components>");
        for (int index = 0; index < 257; index++) {
            components.append("<component name=\"C").append(index)
                    .append("\"><label/></component>");
        }
        components.append("</components></ui>");
        assertKind(MarkupException.Kind.TOO_LARGE, components.toString());

        StringBuilder parameters = new StringBuilder(
                "<ui><components><component name=\"Many\">");
        for (int index = 0; index < 65; index++) {
            parameters.append("<param name=\"p").append(index).append("\"/>");
        }
        parameters.append("<label/></component></components></ui>");
        assertKind(MarkupException.Kind.TOO_LARGE, parameters.toString());

        StringBuilder slots = new StringBuilder(
                "<ui><components><component name=\"Many\"><table>");
        for (int index = 0; index < 33; index++) {
            slots.append("<slot name=\"s").append(index).append("\"/>");
        }
        slots.append("</table></component></components></ui>");
        assertKind(MarkupException.Kind.TOO_LARGE, slots.toString());
    }

    @Test
    void substitutionsPerValueAreBounded() {
        String references = "${p}".repeat(33);
        assertKind(
                MarkupException.Kind.TOO_LARGE,
                "<ui><components><component name=\"Many\"><param name=\"p\"/>"
                        + "<label text=\"" + references
                        + "\"/></component></components></ui>");
    }

    @Test
    void finalConcreteElementCountIsBoundedAfterExpansion() {
        StringBuilder xml = new StringBuilder("""
                <ui><components><component name="Triplet">
                  <table><label/><label/></table>
                </component></components>
                """);
        for (int index = 0; index < 3_334; index++) {
            xml.append("<use component=\"Triplet\"/>");
        }
        xml.append("</ui>");

        assertKind(MarkupException.Kind.TOO_LARGE, xml.toString());
    }

    @Test
    void totalExpansionWorkIsBoundedIndependentlyOfFinalElements() {
        StringBuilder xml = new StringBuilder(
                "<ui><components><component name=\"Sparse\"><table>");
        for (int index = 0; index < 30; index++) {
            xml.append("<slot name=\"s").append(index).append("\"/>");
        }
        xml.append("</table></component></components>");
        for (int index = 0; index < 3_334; index++) {
            xml.append("<use component=\"Sparse\"/>");
        }
        xml.append("</ui>");

        MarkupException failure = assertFailure(xml.toString());
        assertEquals(MarkupException.Kind.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("visit"));
    }

    @Test
    void exactDeclarationAndDepthLimitsRemainAccepted() {
        StringBuilder components = new StringBuilder("<ui><components>");
        for (int index = 0; index < 256; index++) {
            components.append("<component name=\"C").append(index)
                    .append("\"><label/></component>");
        }
        components.append("</components></ui>");
        assertEquals("ui", parser.parse(components.toString()).root().tag());

        StringBuilder bounded = new StringBuilder(
                "<ui><components><component name=\"Bounded\">");
        for (int index = 0; index < 64; index++) {
            bounded.append("<param name=\"p").append(index).append("\"/>");
        }
        bounded.append("<table>");
        for (int index = 0; index < 32; index++) {
            bounded.append("<slot name=\"s").append(index).append("\"/>");
        }
        bounded.append("<label text=\"").append("${p0}".repeat(32))
                .append("\"/></table></component></components></ui>");
        assertEquals("ui", parser.parse(bounded.toString()).root().tag());

        StringBuilder depth = new StringBuilder("<ui><components>");
        for (int index = 0; index < 16; index++) {
            depth.append("<component name=\"D").append(index).append("\">");
            if (index == 15) {
                depth.append("<label text=\"deep\"/>");
            } else {
                depth.append("<use component=\"D").append(index + 1).append("\"/>");
            }
            depth.append("</component>");
        }
        depth.append("</components><use component=\"D0\"/></ui>");
        assertEquals("deep", parser.parse(depth.toString()).root().children().getFirst().text());
    }

    @Test
    void exactFinalElementAndExpansionWorkLimitsRemainAccepted() {
        StringBuilder elements = new StringBuilder("""
                <ui><components><component name="Triplet">
                  <table><label/><label/></table>
                </component></components>
                """);
        for (int index = 0; index < 3_333; index++) {
            elements.append("<use component=\"Triplet\"/>");
        }
        elements.append("</ui>");
        assertEquals(3_333, parser.parse(elements.toString()).root().children().size());

        StringBuilder work = new StringBuilder(
                "<ui><components><component name=\"Sparse\"><table>");
        for (int index = 0; index < 30; index++) {
            work.append("<slot name=\"s").append(index).append("\"/>");
        }
        work.append("</table></component></components>");
        for (int index = 0; index < 3_125; index++) {
            work.append("<use component=\"Sparse\"/>");
        }
        work.append("</ui>");
        assertEquals(3_125, parser.parse(work.toString()).root().children().size());
    }

    @Test
    void substitutedConcreteFailureCarriesTemplateOriginAndInvocationTrace() {
        MarkupException failure = assertThrows(
                MarkupException.class,
                () -> parser.parse("""
                        <ui><components><component name="HealthBar">
                          <param name="current" required="true"/>
                          <progressbar min="0" max="100" value="${current}"/>
                        </component></components>
                        <table><use component="HealthBar" current="fast"/></table></ui>
                        """, "hud.xml"));

        assertEquals(MarkupException.Kind.INVALID_VALUE, failure.kind());
        assertEquals("ui/table/progressbar", failure.elementPath());
        assertEquals("hud.xml", failure.source());
        assertEquals("value", failure.attribute());
        assertEquals("finite float", failure.expected());
        assertEquals("fast", failure.received());
        assertEquals("document rejected before Scene2D build", failure.consequence());
        assertEquals(
                List.of("HealthBar"),
                failure.componentTrace().stream().map(ComponentTraceFrame::component).toList());
        assertEquals(3, failure.line(), "the template attribute origin is reported");
    }

    @Test
    void rootOverrideAndFallbackFailuresChooseTheirExactOrigins() {
        MarkupException rootOverride = assertThrows(
                MarkupException.class,
                () -> parser.parse("""
                        <ui><components><component name="Badge">
                          <label text="Ready"/>
                        </component></components>
                        <use component="Badge" expand="diagonal"/>
                        </ui>
                        """, "screen.xml"));
        assertEquals(4, rootOverride.line(), "root override originates at the invocation");
        assertEquals(List.of("Badge"), rootOverride.componentTrace().stream()
                .map(ComponentTraceFrame::component).toList());

        MarkupException fallback = assertThrows(
                MarkupException.class,
                () -> parser.parse("""
                        <ui><components><component name="Panel"><table>
                          <slot><progressbar min="0" max="1" value="fast"/></slot>
                        </table></component></components>
                        <use component="Panel"/>
                        </ui>
                        """, "screen.xml"));
        assertEquals(2, fallback.line(), "fallback failure originates in the template");
        assertEquals("ui/table/progressbar", fallback.elementPath());
        assertEquals(List.of("Panel"), fallback.componentTrace().stream()
                .map(ComponentTraceFrame::component).toList());
    }

    @Test
    void slotFillFailuresUseCallerOriginAndNestedTracesAreOutermostFirst() {
        MarkupException unknownSlot = assertThrows(
                MarkupException.class,
                () -> parser.parse("""
                        <ui><components><component name="Panel"><table>
                          <slot name="footer"/>
                        </table></component></components>
                        <use component="Panel">
                          <fill slot="foote"><label/></fill>
                        </use></ui>
                        """, "screen.xml"));
        assertEquals(MarkupException.Kind.UNKNOWN_SLOT, unknownSlot.kind());
        assertEquals(5, unknownSlot.line());
        assertEquals("footer", unknownSlot.suggestion());

        MarkupException nested = assertThrows(
                MarkupException.class,
                () -> parser.parse("""
                        <ui><components>
                          <component name="Inner"><progressbar min="0" max="1" value="bad"/></component>
                          <component name="Outer"><use component="Inner"/></component>
                        </components><use component="Outer"/></ui>
                        """, "screen.xml"));
        assertEquals(
                List.of("Outer", "Inner"),
                nested.componentTrace().stream().map(ComponentTraceFrame::component).toList());
    }

    @Test
    void unknownComponentAndParameterOfferOnlyUniqueNearestNames() {
        String definition = """
                <components><component name="HealthBar">
                  <param name="current" required="true"/>
                  <label text="${current}"/>
                </component></components>
                """;
        MarkupException component = assertFailure(
                "<ui>" + definition + "<use component=\"HealthBr\" current=\"1\"/></ui>");
        assertEquals("HealthBar", component.suggestion());

        MarkupException parameter = assertFailure(
                "<ui>" + definition
                        + "<use component=\"HealthBar\" curent=\"1\"/></ui>");
        assertEquals("current", parameter.suggestion());
    }

    @Test
    void expandedProvenanceSeparatesTemplateOverridesAndCallerFills() {
        MarkupDocument document = parser.parse("""
                <ui><components><component name="Panel">
                  <table class="panel"><slot/></table>
                </component></components>
                <use component="Panel" id="inventory">
                  <fill><label id="item" text="Potion"/></fill>
                </use></ui>
                """, "screen.xml");

        ElementProvenance panel = document.provenanceFor("ui/table");
        assertEquals(2, panel.origin().line());
        assertEquals(4, panel.locationFor("id").line());
        assertEquals(List.of("Panel"), panel.componentTrace().stream()
                .map(ComponentTraceFrame::component).toList());

        ElementProvenance item = document.provenanceFor("ui/table/label");
        assertEquals(5, item.origin().line());
        assertEquals(5, item.locationFor("id").line());
        assertEquals(List.of("Panel"), item.componentTrace().stream()
                .map(ComponentTraceFrame::component).toList());
    }

    private void assertKind(MarkupException.Kind kind, String xml) {
        MarkupException failure = assertFailure(xml);
        assertEquals(kind, failure.kind(), failure.formatted());
    }

    private MarkupException assertFailure(String xml) {
        return assertThrows(MarkupException.class, () -> parser.parse(xml), xml);
    }
}
