package dev.gdx.markup.idea

internal enum class GdxCssTokenKind {
    COMMENT,
    SELECTOR,
    PROPERTY,
    VALUE,
    LEFT_BRACE,
    RIGHT_BRACE,
    COLON,
    SEMICOLON,
    WHITESPACE,
}

internal data class GdxCssToken(
    val kind: GdxCssTokenKind,
    val start: Int,
    val end: Int,
    val nextMode: Int,
)

internal object GdxCssScanner {
    const val SELECTOR_MODE = 0
    const val PROPERTY_MODE = 1
    const val VALUE_MODE = 2

    fun next(buffer: CharSequence, position: Int, bufferEnd: Int, mode: Int): GdxCssToken? {
        if (position >= bufferEnd) {
            return null
        }
        val current = buffer[position]
        if (current.isWhitespace()) {
            return token(GdxCssTokenKind.WHITESPACE, position,
                scanWhile(buffer, position + 1, bufferEnd, Char::isWhitespace), mode)
        }
        if (current == '/' && position + 1 < bufferEnd && buffer[position + 1] == '*') {
            val close = buffer.indexOf("*/", position + 2)
            val end = if (close < 0 || close + 2 > bufferEnd) bufferEnd else close + 2
            return token(GdxCssTokenKind.COMMENT, position, end, mode)
        }
        return when (current) {
            '{' -> token(GdxCssTokenKind.LEFT_BRACE, position, position + 1, PROPERTY_MODE)
            '}' -> token(GdxCssTokenKind.RIGHT_BRACE, position, position + 1, SELECTOR_MODE)
            ':' -> token(GdxCssTokenKind.COLON, position, position + 1, VALUE_MODE)
            ';' -> token(GdxCssTokenKind.SEMICOLON, position, position + 1, PROPERTY_MODE)
            else -> token(kindFor(mode), position,
                scanText(buffer, position + 1, bufferEnd), mode)
        }
    }

    private fun kindFor(mode: Int): GdxCssTokenKind = when (mode) {
        SELECTOR_MODE -> GdxCssTokenKind.SELECTOR
        PROPERTY_MODE -> GdxCssTokenKind.PROPERTY
        else -> GdxCssTokenKind.VALUE
    }

    private fun scanText(buffer: CharSequence, from: Int, bufferEnd: Int): Int {
        var index = from
        while (index < bufferEnd) {
            val value = buffer[index]
            if (value.isWhitespace() || value == '{' || value == '}' || value == ':'
                || value == ';' || value == '/' && index + 1 < bufferEnd
                && buffer[index + 1] == '*') {
                break
            }
            index++
        }
        return index
    }

    private fun scanWhile(
        buffer: CharSequence,
        from: Int,
        bufferEnd: Int,
        predicate: (Char) -> Boolean,
    ): Int {
        var index = from
        while (index < bufferEnd && predicate(buffer[index])) {
            index++
        }
        return index
    }

    private fun token(
        kind: GdxCssTokenKind,
        start: Int,
        end: Int,
        nextMode: Int,
    ): GdxCssToken = GdxCssToken(kind, start, end, nextMode)
}
