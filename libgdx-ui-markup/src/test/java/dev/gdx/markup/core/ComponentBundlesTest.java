package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ComponentBundlesTest {
    private static final String BUNDLE = """
            <ui><components>
              <component name="Action"><param name="id" required="true"/>
                <button id="${id}" text="Continue"/></component>
              <component name="Panel"><param name="id" required="true"/>
                <table id="${id}"><use component="Action" id="${id}-action"/></table>
              </component>
            </components></ui>
            """;

    @Test void sharesQualifiedDefinitionsAcrossScreensWithoutChangingExplicitIds() {
        MarkupParser parser = new MarkupParser().withComponentBundles(Map.of("Common", BUNDLE));
        MarkupDocument first = parser.parse("<ui><use component='Common.Panel' id='menu'/></ui>", "menu.xml");
        MarkupDocument second = parser.parse("<ui><use component='Common.Panel' id='pause'/></ui>", "pause.xml");
        assertEquals("menu-action", first.root().children().getFirst().children().getFirst().id());
        assertEquals("pause-action", second.root().children().getFirst().children().getFirst().id());
        assertEquals("Continue", second.root().children().getFirst().children().getFirst().text());
    }

    @Test void bundleConfigurationIsCopiedAndDoesNotLeakIntoOtherParsers() {
        Map<String, String> sources = new LinkedHashMap<>(Map.of("Common", BUNDLE));
        MarkupParser plain = new MarkupParser();
        MarkupParser configured = plain.withComponentBundles(sources);
        sources.clear();
        assertEquals("button", configured.parse("<ui><use component='Common.Action' id='ok'/></ui>")
                .root().children().getFirst().tag());
        assertEquals(MarkupException.Kind.UNKNOWN_COMPONENT, assertThrows(MarkupException.class,
                () -> plain.parse("<ui><use component='Common.Action' id='ok'/></ui>")).kind());
    }

    @Test void rejectsBundleBodiesAndCrossBundleCyclesWithSourceLocations() {
        MarkupException body = assertThrows(MarkupException.class, () -> new MarkupParser()
                .withComponentBundles(Map.of("Common", "<ui><components/><label/></ui>"))
                .parse("<ui/>"));
        assertEquals(MarkupException.Kind.INVALID_VALUE, body.kind());
        assertTrue(body.context().source().contains("Common"));
        Map<String, String> cycle = Map.of(
                "One", "<ui><components><component name='A'><use component='Two.B'/></component></components></ui>",
                "Two", "<ui><components><component name='B'><use component='One.A'/></component></components></ui>");
        assertEquals(MarkupException.Kind.COMPONENT_CYCLE, assertThrows(MarkupException.class,
                () -> new MarkupParser().withComponentBundles(cycle).parse("<ui/>")).kind());
    }

    @Test void rejectsLocalQualifiedCollisionAndDuplicateExplicitActorIds() {
        MarkupParser parser = new MarkupParser().withComponentBundles(Map.of("Common", BUNDLE));
        assertEquals(MarkupException.Kind.DUPLICATE_COMPONENT, assertThrows(MarkupException.class,
                () -> parser.parse("""
                        <ui><components><component name="Common.Action"><label/></component></components></ui>
                        """)).kind());
        assertEquals(MarkupException.Kind.DUPLICATE_ID, assertThrows(MarkupException.class,
                () -> parser.parse("""
                        <ui><use component="Common.Action" id="same"/>
                          <use component="Common.Action" id="same"/></ui>
                        """)).kind());
    }

    @Test void combinedSourcesShareByteAndElementBudgets() {
        MarkupParser bytes = new MarkupParser(100, 100, 64, 4096, 4096)
                .withComponentBundles(Map.of("Common", "<ui><components/></ui>"));
        assertEquals(MarkupException.Kind.TOO_LARGE, assertThrows(MarkupException.class,
                () -> bytes.parse("<ui>" + " ".repeat(80) + "</ui>")).kind());
        MarkupParser elements = new MarkupParser(4096, 7, 64, 4096, 4096)
                .withComponentBundles(Map.of("Common", "<ui><components><component name='A'><label/></component></components></ui>"));
        assertEquals(MarkupException.Kind.TOO_LARGE, assertThrows(MarkupException.class,
                () -> elements.parse("<ui><table><label/><label/></table></ui>")).kind());
    }

    @Test void namespaceAndDefinitionLimitsAndExternalEntitiesRemainClosed() {
        assertThrows(IllegalArgumentException.class, () -> new MarkupParser()
                .withComponentBundles(Map.of("../Common", BUNDLE)));
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 17; index++) {
            tooMany.put("Bundle" + index, "<ui><components/></ui>");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new MarkupParser().withComponentBundles(tooMany));
        StringBuilder definitions = new StringBuilder("<ui><components>");
        for (int index = 0; index < 257; index++) {
            definitions.append("<component name='Item").append(index).append("'><label/></component>");
        }
        definitions.append("</components></ui>");
        assertEquals(MarkupException.Kind.TOO_LARGE, assertThrows(MarkupException.class,
                () -> new MarkupParser().withComponentBundles(Map.of("Common", definitions.toString()))
                        .parse("<ui/>")).kind());
        assertEquals(MarkupException.Kind.MALFORMED_XML, assertThrows(MarkupException.class,
                () -> new MarkupParser().withComponentBundles(Map.of("Common", """
                        <!DOCTYPE ui [<!ENTITY x SYSTEM "file:///not-readable">]>
                        <ui><components>&x;</components></ui>
                        """)).parse("<ui/>")).kind());
    }
}
