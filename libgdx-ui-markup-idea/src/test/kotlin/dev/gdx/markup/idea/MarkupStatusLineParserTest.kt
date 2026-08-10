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
            "markup-status: {\"schemaVersion\":3,\"nodes\":10,\"ok\":true}")
        assertEquals(3, parsed?.schemaVersion)
        assertEquals(true, parsed?.ok)
        assertEquals(10, parsed?.nodes)
        assertNull(parsed?.kind)
        assertTrue(parsed?.componentTrace.orEmpty().isEmpty())
    }

    @Test
    fun parsesSchemaThreeComponentContextAndTrace() {
        val parsed = MarkupStatusLineParser.parse(componentError())

        assertEquals(3, parsed?.schemaVersion)
        assertEquals(false, parsed?.ok)
        assertEquals("INVALID_VALUE", parsed?.kind)
        assertEquals("hud.xml", parsed?.source)
        assertEquals("ui/table/progressbar", parsed?.elementPath)
        assertEquals(9, parsed?.line)
        assertEquals("value", parsed?.attribute)
        assertEquals("finite float", parsed?.expected)
        assertEquals("fast", parsed?.received)
        assertEquals("HealthBar", parsed?.componentTrace?.single()?.component)
        assertEquals("ui/use", parsed?.componentTrace?.single()?.elementPath)
    }

    @Test
    fun unescapesJsonMessageAndUnicodeSurrogatePair() {
        val parsed = MarkupStatusLineParser.parse(error(
            message = "quote \\\" and slash \\\\ end \\uD83D\\uDE00",
        ))
        assertEquals("quote \" and slash \\ end 😀", parsed?.message)
    }

    @Test
    fun ignoresNonStatusOutputAndToleratesWhitespace() {
        assertNull(MarkupStatusLineParser.parse("[LWJGL] some warning"))
        assertNull(MarkupStatusLineParser.parse("Picked up JAVA_TOOL_OPTIONS: x"))
        assertNull(MarkupStatusLineParser.parse(""))
        val parsed = MarkupStatusLineParser.parse(
            "  markup-status:  {\"schemaVersion\":3,\"ok\":true,\"nodes\":3}  ")
        assertEquals(3, parsed?.nodes)
    }

    @Test
    fun rejectsFutureAndSchemaTwoWithActionableMessage() {
        for (version in listOf(2, 4)) {
            val parsed = MarkupStatusLineParser.parse(
                "markup-status: {\"schemaVersion\":$version,\"ok\":true,\"nodes\":1}")
            assertEquals(false, parsed?.ok)
            assertEquals("UNSUPPORTED_SCHEMA", parsed?.kind)
            val message = parsed?.message.orEmpty()
            assertTrue(message.contains("schema v$version"), message)
            assertTrue(message.contains("update") || message.contains("plugin"), message)
            assertNull(parsed?.nodes)
        }
    }

    @Test
    fun rejectsLegacySchemaOneLineWithActionableMessage() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"ok\":true,\"nodes\":10}")
        assertEquals(false, parsed?.ok)
        assertTrue(parsed?.message.orEmpty().contains("schema v1"))
    }

    @Test
    fun rejectsMalformedSuccessPayloads() {
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":3,\"ok\":true,\"nodes\":-5}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":3,\"ok\":true}"))
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":3,\"ok\":true,\"nodes\":5,"
                + "\"kind\":\"UNKNOWN_TAG\"}"))
    }

    @Test
    fun rejectsMalformedErrorPayloads() {
        assertNull(MarkupStatusLineParser.parse(
            "markup-status: {\"schemaVersion\":3,\"ok\":false,\"message\":\"boom\"}"))
        assertNull(MarkupStatusLineParser.parse(error(kind = "  ")))
        assertNull(MarkupStatusLineParser.parse(error(line = -1)))
        assertNull(MarkupStatusLineParser.parse(error(kind = "GENERIC", elementPath = "ui")))
        assertNull(MarkupStatusLineParser.parse(error(nodes = 3)))
    }

    @Test
    fun parsesPathlessGenericErrorWithEmptyContext() {
        val parsed = MarkupStatusLineParser.parse(error(
            kind = "GENERIC",
            source = "",
            elementPath = "",
            message = "cannot read ui.xml",
        ))
        assertEquals("GENERIC", parsed?.kind)
        assertEquals("", parsed?.elementPath)
        assertTrue(parsed?.componentTrace.orEmpty().isEmpty())
    }

    @Test
    fun rejectsOversizedStringsFramesAggregateAndNestedTraceValues() {
        assertNull(MarkupStatusLineParser.parse(error(message = "x".repeat(2_001))))

        val frame = "{\"component\":\"Panel\",\"source\":\"screen.xml\"," +
            "\"elementPath\":\"ui/use\",\"line\":1,\"column\":1}"
        assertNull(MarkupStatusLineParser.parse(error(trace = "[${List(17) { frame }.joinToString()}]")))

        val largePath = "x".repeat(1_100)
        val largeFrame = "{\"component\":\"Panel\",\"source\":\"screen.xml\"," +
            "\"elementPath\":\"$largePath\",\"line\":1,\"column\":1}"
        assertNull(MarkupStatusLineParser.parse(
            error(trace = "[${List(16) { largeFrame }.joinToString()}]"),
        ))

        val largeComponent = "P".repeat(1_100)
        val componentHeavyFrame = "{\"component\":\"$largeComponent\"," +
            "\"source\":\"screen.xml\",\"elementPath\":\"ui/use\",\"line\":1,\"column\":1}"
        assertNull(MarkupStatusLineParser.parse(
            error(trace = "[${List(16) { componentHeavyFrame }.joinToString()}]"),
        ))

        val nestedFrame = "{\"component\":\"Panel\",\"source\":\"screen.xml\"," +
            "\"elementPath\":\"ui/use\",\"line\":1,\"column\":1,\"extra\":{}}"
        assertNull(MarkupStatusLineParser.parse(error(trace = "[$nestedFrame]")))
    }

    @Test
    fun scannerDoesNotConfuseKeysInsideStringsOrTraceObjects() {
        val parsed = MarkupStatusLineParser.parse(error(
            message = "componentTrace: [{not the field}]",
            trace = "[{\"component\":\"Panel\",\"source\":\"screen.xml\"," +
                "\"elementPath\":\"ui/use\",\"line\":1,\"column\":1}]",
        ))
        assertEquals("componentTrace: [{not the field}]", parsed?.message)
        assertEquals("Panel", parsed?.componentTrace?.single()?.component)
    }

    private fun componentError() = "markup-status: {\"schemaVersion\":3,\"ok\":false," +
        "\"kind\":\"INVALID_VALUE\",\"source\":\"hud.xml\"," +
        "\"elementPath\":\"ui/table/progressbar\",\"line\":9,\"column\":9," +
        "\"attribute\":\"value\",\"expected\":\"finite float\"," +
        "\"received\":\"fast\",\"suggestion\":\"\"," +
        "\"consequence\":\"document rejected before Scene2D build\"," +
        "\"componentTrace\":[{\"component\":\"HealthBar\"," +
        "\"source\":\"hud.xml\",\"elementPath\":\"ui/use\"," +
        "\"line\":18,\"column\":3}],\"message\":\"invalid value\"}"

    private fun error(
        kind: String = "INVALID_VALUE",
        source: String = "screen.xml",
        elementPath: String = "ui/label",
        line: Int = 1,
        column: Int = 1,
        attribute: String = "text",
        expected: String = "non-blank text",
        received: String = "",
        suggestion: String = "",
        consequence: String = "document rejected before Scene2D build",
        trace: String = "[]",
        message: String = "invalid value",
        nodes: Int? = null,
    ): String {
        val nodesField = nodes?.let { ",\"nodes\":$it" }.orEmpty()
        return "markup-status: {\"schemaVersion\":3,\"ok\":false," +
            "\"kind\":\"$kind\",\"source\":\"$source\"," +
            "\"elementPath\":\"$elementPath\",\"line\":$line,\"column\":$column," +
            "\"attribute\":\"$attribute\",\"expected\":\"$expected\"," +
            "\"received\":\"$received\",\"suggestion\":\"$suggestion\"," +
            "\"consequence\":\"$consequence\",\"componentTrace\":$trace," +
            "\"message\":\"$message\"$nodesField}"
    }
}
