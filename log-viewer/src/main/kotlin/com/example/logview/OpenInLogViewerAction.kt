package com.example.logview

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Context-menu action that opens the selected file as a live-tail session in the Log Viewer tool
 * window — works for any file, not just `.log` (e.g. a `.txt` trace or an extension-less log). Tailing
 * picks up appended lines, so it's useful even on a file already open in the editor.
 */
class OpenInLogViewerAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LOG_TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? LogToolWindowPanel
            panel?.openLocalPath(file.path, file.name)
        }
    }
}
