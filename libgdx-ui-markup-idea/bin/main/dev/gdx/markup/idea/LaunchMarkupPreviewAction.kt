package dev.gdx.markup.idea

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/** Launches the markup preview for the current XML file from the editor context menu. */
class LaunchMarkupPreviewAction : AnAction() {
    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = file != null && file.name.endsWith(".xml")
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show()
        val content = toolWindow.contentManager.getContent(0) ?: return
        (content.component as? MarkupPreviewPanel)?.launchForCurrentFile()
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Markup Preview"
    }
}
