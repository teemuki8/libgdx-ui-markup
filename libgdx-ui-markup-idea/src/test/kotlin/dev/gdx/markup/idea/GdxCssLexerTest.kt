package dev.gdx.markup.idea

import kotlin.test.Test
import kotlin.test.assertEquals

class GdxCssLexerTest {
    @Test
    fun highlightsSelectorsDeclarationsAndCommentsWithoutParsingTheDialect() {
        val source = "/* theme */ .panel { color: red; width: 100%; }"

        assertEquals(listOf(
            GdxCssTokenKind.COMMENT to "/* theme */",
            GdxCssTokenKind.SELECTOR to ".panel",
            GdxCssTokenKind.LEFT_BRACE to "{",
            GdxCssTokenKind.PROPERTY to "color",
            GdxCssTokenKind.COLON to ":",
            GdxCssTokenKind.VALUE to "red",
            GdxCssTokenKind.SEMICOLON to ";",
            GdxCssTokenKind.PROPERTY to "width",
            GdxCssTokenKind.COLON to ":",
            GdxCssTokenKind.VALUE to "100%",
            GdxCssTokenKind.SEMICOLON to ";",
            GdxCssTokenKind.RIGHT_BRACE to "}",
        ), tokens(source))
    }

    @Test
    fun malformedInputAlwaysAdvancesToTheBufferEnd() {
        val source = ".panel { color: /* unterminated"

        val result = tokens(source)

        assertEquals(GdxCssTokenKind.COMMENT, result.last().first)
        assertEquals("/* unterminated", result.last().second)
    }

    private fun tokens(source: String): List<Pair<GdxCssTokenKind, String>> {
        val result = mutableListOf<Pair<GdxCssTokenKind, String>>()
        var iterations = 0
        var position = 0
        var mode = GdxCssScanner.SELECTOR_MODE
        while (position < source.length) {
            check(iterations++ <= source.length + 1) { "lexer did not make bounded progress" }
            val token = checkNotNull(GdxCssScanner.next(source, position, source.length, mode))
            if (token.kind != GdxCssTokenKind.WHITESPACE) {
                result += token.kind to source.substring(token.start, token.end)
            }
            position = token.end
            mode = token.nextMode
        }
        assertEquals(source.length, position)
        return result
    }
}
