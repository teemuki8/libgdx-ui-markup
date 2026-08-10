package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.markup.core.ComponentTraceFrame;
import dev.gdx.markup.core.MarkupDiagnosticContext;
import dev.gdx.markup.core.MarkupException;
import dev.gdx.markup.core.MarkupSourceLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Schema v2 contract for the bounded {@code markup-status} line: versioned, typed fields,
 * JSON-escape-safe strings, and raw messages that never duplicate the element path or source
 * coordinates already carried in their own fields.
 */
final class MarkupStatusTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void successJsonCarriesSchemaVersionOkAndNodes() throws Exception {
        JsonNode node = JSON.readTree(MarkupStatus.ok(10).json());
        assertEquals(MarkupStatus.SCHEMA_VERSION, node.get("schemaVersion").asInt());
        assertTrue(node.get("ok").asBoolean());
        assertEquals(10, node.get("nodes").asInt());
        assertFalse(node.has("kind"));
        assertFalse(node.has("elementPath"));
        assertFalse(node.has("message"));
    }

    @Test
    void locatedInvalidValueErrorCarriesTypedFields() throws Exception {
        JsonNode node = JSON.readTree(MarkupStatus.error(locatedInvalidValue()).json());
        assertEquals(MarkupStatus.SCHEMA_VERSION, node.get("schemaVersion").asInt());
        assertFalse(node.get("ok").asBoolean());
        assertEquals("INVALID_VALUE", node.get("kind").asText());
        assertEquals("ui/table/button[2]", node.get("elementPath").asText());
        assertEquals(7, node.get("line").asInt());
        assertEquals(3, node.get("column").asInt());
        assertEquals("expected an integer for width", node.get("message").asText());
        assertFalse(node.has("nodes"));
    }

    @Test
    void componentFailureJsonCarriesBoundedStructuredContext() throws Exception {
        JsonNode node = JSON.readTree(MarkupStatus.error(componentFailure()).json());

        assertEquals(3, node.path("schemaVersion").asInt());
        assertEquals("hud.xml", node.path("source").asText());
        assertEquals("value", node.path("attribute").asText());
        assertEquals("finite float", node.path("expected").asText());
        assertEquals("fast", node.path("received").asText());
        assertEquals("HealthBar", node.path("componentTrace").get(0)
                .path("component").asText());
        assertEquals("ui/use", node.path("componentTrace").get(0)
                .path("elementPath").asText());
    }

    @Test
    void messageIsTheRawExceptionMessageNotFormattedProse() throws Exception {
        MarkupException failure = locatedInvalidValue();
        JsonNode node = JSON.readTree(MarkupStatus.error(failure).json());
        assertEquals(failure.getMessage(), node.get("message").asText());
        assertFalse(node.get("message").asText().equals(failure.formatted()),
                "message must be the raw diagnostic, not formatted path/coordinates prose");
    }

    @Test
    void messageExcludesDuplicatedPathAndCoordinates() throws Exception {
        JsonNode node = JSON.readTree(MarkupStatus.error(locatedInvalidValue()).json());
        String message = node.get("message").asText();
        assertFalse(message.contains("ui/table/button[2]"),
                "message must not duplicate the element path: " + message);
        assertFalse(message.contains("7:3"),
                "message must not duplicate the source coordinates: " + message);
    }

    @Test
    void everyStringIsBounded() throws Exception {
        String huge = "x".repeat(MarkupStatus.MAX_STRING_LENGTH + 10_000);
        MarkupException failure =
                new MarkupException(MarkupException.Kind.INVALID_VALUE, huge, 1, 1, huge);
        JsonNode node = JSON.readTree(MarkupStatus.error(failure).json());
        assertTrue(node.get("message").asText().length() <= MarkupStatus.MAX_STRING_LENGTH);
        assertTrue(node.get("elementPath").asText().length() <= MarkupStatus.MAX_STRING_LENGTH);
        assertEquals("INVALID_VALUE", node.get("kind").asText());
    }

    @Test
    void jsonEscapesQuotesBackslashesAndNewlines() throws Exception {
        String message = "quote \" slash \\ end\nline2";
        MarkupStatus status = MarkupStatus.error(
                new MarkupException(MarkupException.Kind.INVALID_VALUE, "ui", 1, 1, message));
        JsonNode node = JSON.readTree(status.json());
        assertEquals(message, node.get("message").asText());
    }

    @Test
    void genericFailureUsesStableKindAndNoLocation() throws Exception {
        JsonNode node = JSON.readTree(MarkupStatus.error("cannot read ui.xml").json());
        assertEquals(MarkupStatus.SCHEMA_VERSION, node.get("schemaVersion").asInt());
        assertFalse(node.get("ok").asBoolean());
        assertEquals(MarkupStatus.GENERIC_KIND, node.get("kind").asText());
        assertEquals("", node.get("elementPath").asText());
        assertEquals(0, node.get("line").asInt());
        assertEquals(0, node.get("column").asInt());
        assertEquals("cannot read ui.xml", node.get("message").asText());
        assertEquals("", node.get("source").asText());
        assertEquals("", node.get("attribute").asText());
        assertEquals(0, node.get("componentTrace").size());
    }

    @Test
    void successRejectsNegativeNodes() {
        assertThrows(IllegalArgumentException.class, () -> MarkupStatus.ok(-1));
    }

    @Test
    void successRejectsErrorIdentityOrLocation() {
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, true, "UNKNOWN_TAG", null, 0, 0, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, true, null, null, 5, 0, null, 1));
    }

    @Test
    void errorRejectsBlankOrMissingKind() {
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, false, "  ", "", 1, 1, "m", 0));
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, false, null, "", 1, 1, "m", 0));
    }

    @Test
    void errorRejectsMissingMessage() {
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, false, "UNKNOWN_TAG", "ui", 1, 1, null, 0));
    }

    @Test
    void errorRejectsNegativeLocationOrCarriedNodes() {
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, false, "UNKNOWN_TAG", "ui", -1, 1, "m", 0));
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, false, "UNKNOWN_TAG", "ui", 1, 1, "m", 3));
    }

    @Test
    void genericErrorRejectsNonEmptyElementPath() {
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION, false, MarkupStatus.GENERIC_KIND, "ui", 1, 1, "m", 0));
    }

    @Test
    void pathlessMarkupDiagnosticRemainsValid() throws Exception {
        // Real parse-level diagnostics (TOO_LARGE, MALFORMED_XML) and CSS property validation
        // (ResolvedStyle.INVALID_VALUE) carry an empty element path; the status must serialize.
        MarkupStatus status = MarkupStatus.error(new MarkupException(
                MarkupException.Kind.TOO_LARGE, "", 0, 0, "input exceeds the limit"));
        JsonNode node = JSON.readTree(status.json());
        assertEquals("TOO_LARGE", node.get("kind").asText());
        assertEquals("", node.get("elementPath").asText());
        assertEquals("input exceeds the limit", node.get("message").asText());
    }

    @Test
    void truncationNeverSplitsASurrogatePair() throws Exception {
        // Length MAX+1; the emoji's surrogate pair straddles the MAX cut.
        String message = "x".repeat(MarkupStatus.MAX_STRING_LENGTH - 1) + "😀";
        MarkupException failure =
                new MarkupException(MarkupException.Kind.INVALID_VALUE, "ui", 1, 1, message);
        String emitted = JSON.readTree(MarkupStatus.error(failure).json())
                .get("message").asText();
        assertTrue(emitted.length() <= MarkupStatus.MAX_STRING_LENGTH);
        char last = emitted.charAt(emitted.length() - 1);
        assertFalse(Character.isHighSurrogate(last) || Character.isLowSurrogate(last),
                "truncation must not leave a dangling surrogate (length " + emitted.length() + ")");
        assertEquals("x".repeat(MarkupStatus.MAX_STRING_LENGTH - 1), emitted);
    }

    @Test
    void truncationKeepsPairIntactWhenItEndsBeforeTheCut() throws Exception {
        // Length MAX+1; the pair ends exactly at the cut, so no back-off is needed.
        String message = "x".repeat(MarkupStatus.MAX_STRING_LENGTH - 2) + "😀" + "y";
        MarkupException failure =
                new MarkupException(MarkupException.Kind.INVALID_VALUE, "ui", 1, 1, message);
        String emitted = JSON.readTree(MarkupStatus.error(failure).json())
                .get("message").asText();
        assertEquals(MarkupStatus.MAX_STRING_LENGTH, emitted.length());
        assertTrue(emitted.endsWith("😀"),
                "a pair ending exactly at the cut must stay whole: " + emitted.length());
    }

    @Test
    void structuredStringsAndTraceFrameStringsAreSurrogateSafelyBounded() {
        String oversized = "x".repeat(MarkupStatus.MAX_STRING_LENGTH - 1) + "😀" + "tail";
        ComponentTraceFrame frame = new ComponentTraceFrame(
                "HealthBar", new MarkupSourceLocation("hud.xml", oversized, 18, 3));
        MarkupStatus status = new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION,
                false,
                "INVALID_VALUE",
                "hud.xml",
                "ui/progressbar",
                9,
                9,
                "value",
                oversized,
                "fast",
                "",
                "document rejected before Scene2D build",
                List.of(frame),
                "invalid value",
                0);

        assertTrue(status.expected().length() <= MarkupStatus.MAX_STRING_LENGTH);
        assertTrue(status.componentTrace().getFirst().invocation().elementPath().length()
                <= MarkupStatus.MAX_STRING_LENGTH);
        assertFalse(Character.isSurrogate(status.expected().charAt(status.expected().length() - 1)));
    }

    @Test
    void statusRejectsMoreThanSixteenFramesOrOversizedAggregateTrace() {
        ComponentTraceFrame shortFrame = new ComponentTraceFrame(
                "Panel", MarkupSourceLocation.memory("ui/use", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> statusWithTrace(
                java.util.stream.Stream.generate(() -> shortFrame).limit(17).toList()));

        List<ComponentTraceFrame> oversized = java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> new ComponentTraceFrame(
                        "Panel" + index,
                        new MarkupSourceLocation(
                                "screen.xml", "x".repeat(1_100) + index, 1, 1)))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> statusWithTrace(oversized));
    }

    @Test
    void successCannotCarryStructuredErrorContext() {
        assertThrows(IllegalArgumentException.class, () -> new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION,
                true,
                null,
                "screen.xml",
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                1));
    }

    private static MarkupException locatedInvalidValue() {
        return new MarkupException(
                MarkupException.Kind.INVALID_VALUE,
                "ui/table/button[2]",
                7,
                3,
                "expected an integer for width");
    }

    private static MarkupException componentFailure() {
        ComponentTraceFrame frame = new ComponentTraceFrame(
                "HealthBar", new MarkupSourceLocation("hud.xml", "ui/use", 18, 3));
        MarkupDiagnosticContext context = new MarkupDiagnosticContext(
                "hud.xml",
                "value",
                "finite float",
                "fast",
                "",
                "document rejected before Scene2D build",
                List.of(frame));
        return new MarkupException(
                MarkupException.Kind.INVALID_VALUE,
                "ui/table/progressbar",
                9,
                9,
                "invalid value",
                context);
    }

    private static MarkupStatus statusWithTrace(List<ComponentTraceFrame> trace) {
        return new MarkupStatus(
                MarkupStatus.SCHEMA_VERSION,
                false,
                "INVALID_VALUE",
                "screen.xml",
                "ui/label",
                1,
                1,
                "text",
                "non-blank text",
                "",
                "",
                "document rejected before Scene2D build",
                trace,
                "invalid value",
                0);
    }
}
