package dev.gdx.markup.idea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkupStatusLineParserTest {
    @Test
    fun parsesSuccessLine() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"nodes\":10,\"ok\":true}")
        assertEquals(true, parsed?.ok)
        assertEquals(10, parsed?.nodes)
        assertNull(parsed?.message)
        assertNull(parsed?.line)
    }

    @Test
    fun parsesErrorLine() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"ok\":false,\"message\":\"ui/table/bogus:5:13: unknown tag <bogus>\","
                + "\"line\":5,\"column\":13}")
        assertEquals(false, parsed?.ok)
        assertEquals("ui/table/bogus:5:13: unknown tag <bogus>", parsed?.message)
        assertEquals(5, parsed?.line)
        assertEquals(13, parsed?.column)
        assertNull(parsed?.nodes)
    }

    @Test
    fun unescapesJsonMessage() {
        val parsed = MarkupStatusLineParser.parse(
            "markup-status: {\"ok\":false,\"message\":\"quote \\\" and slash \\\\ end\"}")
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
            "  markup-status:  {\"ok\":true,\"nodes\":3}  ")
        assertEquals(true, parsed?.ok)
        assertEquals(3, parsed?.nodes)
    }
}
