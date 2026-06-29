package com.example.logview

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Tool-window id (also the displayed title). Referenced by [OpenInLogViewerAction] to reveal it. */
const val LOG_TOOL_WINDOW_ID = "로그 뷰어"

/**
 * Hosts the [LogToolWindowPanel] — the hub for managing remote SSH connections and live-tailing
 * local/remote logs. [DumbAware] so it works during indexing.
 */
class LogToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LogToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel) // dispose all open sessions when the tool window closes
        content.preferredFocusableComponent = panel
        toolWindow.contentManager.addContent(content)
    }
}
