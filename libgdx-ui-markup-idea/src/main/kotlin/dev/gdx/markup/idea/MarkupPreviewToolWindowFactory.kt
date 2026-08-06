package dev.gdx.markup.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Registers the "Markup Preview" tool window with its Swing panel. */
class MarkupPreviewToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(
            MarkupPreviewPanel(project, toolWindow), "", false)
        toolWindow.contentManager.addContent(content)
    }
}
