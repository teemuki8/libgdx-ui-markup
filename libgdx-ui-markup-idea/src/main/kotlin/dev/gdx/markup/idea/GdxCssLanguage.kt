package dev.gdx.markup.idea

import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType

object GdxCssLanguage : Language("GDXCSS")

internal object GdxCssTokenTypes {
    val COMMENT = IElementType("GDXCSS_COMMENT", GdxCssLanguage)
    val SELECTOR = IElementType("GDXCSS_SELECTOR", GdxCssLanguage)
    val PROPERTY = IElementType("GDXCSS_PROPERTY", GdxCssLanguage)
    val VALUE = IElementType("GDXCSS_VALUE", GdxCssLanguage)
    val LEFT_BRACE = IElementType("GDXCSS_LEFT_BRACE", GdxCssLanguage)
    val RIGHT_BRACE = IElementType("GDXCSS_RIGHT_BRACE", GdxCssLanguage)
    val COLON = IElementType("GDXCSS_COLON", GdxCssLanguage)
    val SEMICOLON = IElementType("GDXCSS_SEMICOLON", GdxCssLanguage)
}
