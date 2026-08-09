package dev.gdx.markup.idea

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object GdxCssFileType : LanguageFileType(GdxCssLanguage) {
    override fun getName(): String = "GDXCSS"

    override fun getDescription(): String = "Bounded Scene2D stylesheet"

    override fun getDefaultExtension(): String = "gdxcss"

    override fun getIcon(): Icon? = null
}
