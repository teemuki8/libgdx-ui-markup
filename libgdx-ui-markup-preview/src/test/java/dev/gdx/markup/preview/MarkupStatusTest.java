package dev.gdx.markup.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gdx.markup.core.MarkupException;
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
    }

    private static MarkupException locatedInvalidValue() {
        return new MarkupException(
                MarkupException.Kind.INVALID_VALUE,
                "ui/table/button[2]",
                7,
                3,
                "expected an integer for width");
    }
}
