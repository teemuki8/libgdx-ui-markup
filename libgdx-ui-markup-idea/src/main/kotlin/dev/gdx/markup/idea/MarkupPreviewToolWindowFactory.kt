package dev.gdx.markup.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Registers the "Markup Preview" tool window with its Swing panel. */
class MarkupPreviewToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MarkupPreviewPanel(project, toolWindow)
        // Project disposal owns both the child process and the file watcher: the owner is a
        // Disposable that terminates the child and its readers, the panel stops its watcher.
        Disposer.register(project, panel.owner)
        Disposer.register(project, panel)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
