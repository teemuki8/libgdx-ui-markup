package dev.gdx.markup.idea

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

internal class GdxCssSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GdxCssLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when {
        tokenType == GdxCssTokenTypes.COMMENT -> pack(COMMENT)
        tokenType == GdxCssTokenTypes.SELECTOR -> pack(SELECTOR)
        tokenType == GdxCssTokenTypes.PROPERTY -> pack(PROPERTY)
        tokenType == GdxCssTokenTypes.VALUE -> pack(VALUE)
        tokenType == GdxCssTokenTypes.LEFT_BRACE
            || tokenType == GdxCssTokenTypes.RIGHT_BRACE -> pack(BRACES)
        tokenType == GdxCssTokenTypes.COLON
            || tokenType == GdxCssTokenTypes.SEMICOLON -> pack(PUNCTUATION)
        else -> emptyArray()
    }

    private companion object {
        val COMMENT = TextAttributesKey.createTextAttributesKey(
            "GDXCSS_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
        val SELECTOR = TextAttributesKey.createTextAttributesKey(
            "GDXCSS_SELECTOR", DefaultLanguageHighlighterColors.CLASS_NAME)
        val PROPERTY = TextAttributesKey.createTextAttributesKey(
            "GDXCSS_PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val VALUE = TextAttributesKey.createTextAttributesKey(
            "GDXCSS_VALUE", DefaultLanguageHighlighterColors.STRING)
        val BRACES = TextAttributesKey.createTextAttributesKey(
            "GDXCSS_BRACES", DefaultLanguageHighlighterColors.BRACES)
        val PUNCTUATION = TextAttributesKey.createTextAttributesKey(
            "GDXCSS_PUNCTUATION", HighlighterColors.TEXT)
    }
}

class GdxCssSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(
        project: Project?,
        virtualFile: VirtualFile?,
    ): SyntaxHighlighter = GdxCssSyntaxHighlighter()
}
