package dev.gdx.markup.idea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkupStatusLineParserTest {
    @Test
    fun parsesSuccessLine() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"nodes\":10,\"ok\":true}")
        assertEquals(2, parsed?.schemaVersion)
        assertEquals(true, parsed?.ok)
        assertEquals(10, parsed?.nodes)
        assertNull(parsed?.kind)
        assertNull(parsed?.elementPath)
        assertNull(parsed?.message)
        assertNull(parsed?.line)
    }

    @Test
    fun parsesErrorLineFieldsDirectly() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,"
                + "\"kind\":\"INVALID_VALUE\",\"elementPath\":\"ui/table/button[2]\","
                + "\"line\":7,\"column\":3,\"message\":\"expected an integer for width\"}")
        assertEquals(2, parsed?.schemaVersion)
        assertEquals(false, parsed?.ok)
        assertEquals("INVALID_VALUE", parsed?.kind)
        assertEquals("ui/table/button[2]", parsed?.elementPath)
        assertEquals(7, parsed?.line)
        assertEquals(3, parsed?.column)
        assertEquals("expected an integer for width", parsed?.message)
        assertNull(parsed?.nodes)
    }

    @Test
    fun unescapesJsonMessage() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,"
                + "\"kind\":\"INVALID_VALUE\",\"message\":\"quote \\\" and slash \\\\ end\"}")
        assertEquals("quote \" and slash \\ end", parsed?.message)
    }

    @Test
    fun ignoresNonStatusOutput() {
        assertNull(MarkupStatusLineParser.parse("[LWJGL] some warning"))
        assertNull(MarkupStatusLineParser.parse("Picked up JAVA_TOOL_OPTIONS: x"))
        assertNull(MarkupStatusLineParser.parse(""))
    }

    @Test
    fun tolerantOfWhitespace() {
        val parsed = MarkupStatusLineParser.parse(
            "  markup-status:  {\"schemaVersion\":2,\"ok\":true,\"nodes\":3}  ")
        assertEquals(2, parsed?.schemaVersion)
        assertEquals(true, parsed?.ok)
        assertEquals(3, parsed?.nodes)
    }

    @Test
    fun rejectsUnsupportedFutureSchemaVersionWithActionableMessage() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":3,\"ok\":true,\"nodes\":1}")
        assertEquals(false, parsed?.ok)
        assertEquals("UNSUPPORTED_SCHEMA", parsed?.kind)
        val message = parsed?.message.orEmpty()
        assertTrue(message.contains("schema v3"), "message names the schema: $message")
        assertTrue(
            message.contains("update") || message.contains("plugin"),
            "message tells the user what to do: $message")
        assertNull(parsed?.nodes)
    }

    @Test
    fun rejectsLegacySchemaOneLineWithActionableMessage() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"ok\":true,\"nodes\":10}")
        assertEquals(false, parsed?.ok)
        val message = parsed?.message.orEmpty()
        assertTrue(message.contains("schema v1"), "message names the schema: $message")
        assertTrue(message.contains("update"), "message tells the user what to do: $message")
    }

    @Test
    fun rejectsMalformedSuccessPayloads() {
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":-5}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":true}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":5,"
                + "\"kind\":\"UNKNOWN_TAG\"}"))
    }

    @Test
    fun rejectsMalformedErrorPayloads() {
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,\"message\":\"boom\"}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,\"kind\":\"  \","
                + "\"message\":\"boom\"}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,\"kind\":\"UNKNOWN_TAG\","
                + "\"elementPath\":\"ui\",\"message\":\"m\",\"line\":-1}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,\"kind\":\"UNKNOWN_TAG\","
                + "\"elementPath\":\"ui\",\"message\":\"m\",\"nodes\":3}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,\"kind\":\"GENERIC\","
                + "\"elementPath\":\"ui\",\"message\":\"m\"}"))
    }

    @Test
    fun parsesPathlessMarkupError() {
        // Parse-level diagnostics (TOO_LARGE, MALFORMED_XML) carry an empty element path.
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,"
                + "\"kind\":\"TOO_LARGE\",\"elementPath\":\"\",\"line\":0,\"column\":0,"
                + "\"message\":\"markup input exceeds the limit\"}")
        assertEquals(false, parsed?.ok)
        assertEquals("TOO_LARGE", parsed?.kind)
        assertEquals("", parsed?.elementPath)
        assertEquals("markup input exceeds the limit", parsed?.message)
    }

    @Test
    fun unescapesUnicodeSurrogatePair() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":2,\"ok\":false,"
                + "\"kind\":\"INVALID_VALUE\",\"message\":\"\\uD83D\\uDE00\"}")
        assertEquals("😀", parsed?.message)
    }
}
