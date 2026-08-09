package dev.gdx.markup.idea

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

internal class GdxCssLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var position = 0
    private var end = 0
    private var mode = GdxCssScanner.SELECTOR_MODE
    private var type: IElementType? = null
    private var token: GdxCssToken? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        position = startOffset
        end = startOffset
        mode = initialState.coerceIn(GdxCssScanner.SELECTOR_MODE, GdxCssScanner.VALUE_MODE)
        locateToken()
    }

    override fun getState(): Int = mode

    override fun getTokenType(): IElementType? = type

    override fun getTokenStart(): Int = position

    override fun getTokenEnd(): Int = end

    override fun advance() {
        mode = token?.nextMode ?: mode
        position = end
        locateToken()
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    private fun locateToken() {
        token = GdxCssScanner.next(buffer, position, bufferEnd, mode)
        val current = token
        if (current == null) {
            end = bufferEnd
            type = null
            return
        }
        end = current.end
        type = when (current.kind) {
            GdxCssTokenKind.COMMENT -> GdxCssTokenTypes.COMMENT
            GdxCssTokenKind.SELECTOR -> GdxCssTokenTypes.SELECTOR
            GdxCssTokenKind.PROPERTY -> GdxCssTokenTypes.PROPERTY
            GdxCssTokenKind.VALUE -> GdxCssTokenTypes.VALUE
            GdxCssTokenKind.LEFT_BRACE -> GdxCssTokenTypes.LEFT_BRACE
            GdxCssTokenKind.RIGHT_BRACE -> GdxCssTokenTypes.RIGHT_BRACE
            GdxCssTokenKind.COLON -> GdxCssTokenTypes.COLON
            GdxCssTokenKind.SEMICOLON -> GdxCssTokenTypes.SEMICOLON
            GdxCssTokenKind.WHITESPACE -> TokenType.WHITE_SPACE
        }
    }
}
