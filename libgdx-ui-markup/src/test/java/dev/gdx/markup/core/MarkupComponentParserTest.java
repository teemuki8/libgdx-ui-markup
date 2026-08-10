package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MarkupComponentParserTest {
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

    private void assertKind(MarkupException.Kind kind, String xml) {
        MarkupException failure =
                assertThrows(MarkupException.class, () -> parser.parse(xml), xml);
        assertEquals(kind, failure.kind(), failure.formatted());
    }
}
