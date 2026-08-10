package dev.gdx.markup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class MarkupDiagnosticTest {
    @Test
    void structuredDiagnosticRetainsImmutableContext() {
        MarkupSourceLocation invocation =
                new MarkupSourceLocation("screen.xml", "ui/use", 12, 7);
        ComponentTraceFrame frame = new ComponentTraceFrame("ActionButton", invocation);
        MarkupDiagnosticContext context = new MarkupDiagnosticContext(
                "components.xml",
                "component",
                "a locally declared component name",
                "ActonButton",
                "ActionButton",
                "the component cannot be expanded",
                List.of(frame));

        MarkupException failure = new MarkupException(
                MarkupException.Kind.UNKNOWN_TAG,
                "ui/use",
                12,
                7,
                "unknown component",
                context);

        assertEquals(context, failure.context());
        assertEquals("components.xml", failure.source());
        assertEquals("component", failure.attribute());
        assertEquals("a locally declared component name", failure.expected());
        assertEquals("ActonButton", failure.received());
        assertEquals("ActionButton", failure.suggestion());
        assertEquals("the component cannot be expanded", failure.consequence());
        assertEquals(List.of(frame), failure.componentTrace());
        assertThrows(
                UnsupportedOperationException.class,
                () -> failure.componentTrace().add(frame));
    }

    @Test
    void documentAndElementProvenanceDefensivelyCopyCollections() {
        MarkupSourceLocation origin =
                new MarkupSourceLocation("components.xml", "ui/components/component/table", 6, 5);
        MarkupSourceLocation override =
                new MarkupSourceLocation("screen.xml", "ui/use", 20, 3);
        Map<String, MarkupSourceLocation> attributeOrigins = new HashMap<>();
        attributeOrigins.put("id", override);
        List<ComponentTraceFrame> trace = new ArrayList<>();
        trace.add(new ComponentTraceFrame("Panel", override));
        ElementProvenance elementProvenance =
                new ElementProvenance(origin, attributeOrigins, trace);

        Element root = new Element(
                "ui", null, null, null, null, Map.of(), List.of(), List.of(), 1, 1);
        Map<String, ElementProvenance> provenance = new HashMap<>();
        provenance.put("ui/table", elementProvenance);
        MarkupDocument document =
                new MarkupDocument(root, 5, "screen.xml", provenance);

        attributeOrigins.clear();
        trace.clear();
        provenance.clear();

        assertEquals(override, elementProvenance.locationFor("id"));
        assertEquals(origin, elementProvenance.locationFor("text"));
        assertEquals(origin, elementProvenance.locationFor(null));
        assertEquals(elementProvenance, document.provenanceFor("ui/table"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> elementProvenance.attributeOrigins().put("text", origin));
        assertThrows(
                UnsupportedOperationException.class,
                () -> document.provenance().put("ui", elementProvenance));
    }

    @Test
    void legacyConstructorsRemainCallable() {
        Element root = new Element(
                "ui", null, null, null, null, Map.of(), List.of(), List.of(), 1, 1);
        MarkupDocument document = new MarkupDocument(root, 5);
        MarkupException failure = new MarkupException(
                MarkupException.Kind.INVALID_VALUE, "ui", 1, 1, "bad");

        assertEquals("<memory>", document.source());
        assertTrue(document.provenance().isEmpty());
        assertNull(document.provenanceFor("ui"));
        assertEquals("", failure.source());
        assertTrue(failure.componentTrace().isEmpty());
    }

    @Test
    void sourceIdentityRejectsBlankControlAndOversizedValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarkupSourceLocation(" ", "ui", 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarkupSourceLocation("bad\nname", "ui", 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarkupSourceLocation("x".repeat(4097), "ui", 1, 1));
    }

    @Test
    void structuredValuesEnforceCoordinateFieldAndTraceBounds() {
        MarkupSourceLocation location = MarkupSourceLocation.memory("ui/use", 1, 1);
        ComponentTraceFrame frame = new ComponentTraceFrame("Panel", location);

        assertThrows(
                IllegalArgumentException.class,
                () -> new MarkupSourceLocation("screen.xml", "ui", -1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarkupDiagnosticContext(
                        "screen.xml", "", "x".repeat(4097), "", "", "", List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarkupDiagnosticContext(
                        "screen.xml",
                        "",
                        "",
                        "",
                        "",
                        "",
                        java.util.stream.Stream.generate(() -> frame).limit(17).toList()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ElementProvenance(
                        location,
                        Map.of(),
                        java.util.stream.Stream.generate(() -> frame).limit(17).toList()));

        MarkupDiagnosticContext normalized =
                new MarkupDiagnosticContext(null, null, null, null, null, null, List.of());
        assertEquals("", normalized.source());
        assertEquals("", normalized.attribute());
    }

    @Test
    void uniqueNearestSuggestionUsesSpecifiedThresholdAndTieRule() {
        assertEquals(
                Optional.of("HealthBar"),
                NearestSuggestion.unique("HealthBr", List.of("HealthBar", "ManaBar")));
        assertEquals(
                Optional.empty(),
                NearestSuggestion.unique("Cat", List.of("Bat", "Hat")));
        assertEquals(
                Optional.empty(),
                NearestSuggestion.unique("unrelated", List.of("HealthBar")));
        assertEquals("one of [Alpha, Beta]", NearestSuggestion.expected(List.of("Beta", "Alpha")));
        assertTrue(NearestSuggestion.expected(
                        java.util.stream.IntStream.range(0, 256)
                                .mapToObj(index -> "N" + index + "x".repeat(60))
                                .toList())
                .length() <= 4096);
    }
}
