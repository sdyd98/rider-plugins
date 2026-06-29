package com.example.xlsx

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import org.jetbrains.jewel.bridge.JewelComposePanel

/**
 * Hosts the relationship views (ER map / record lineage / integrity check) in a dockable tool window.
 * Driven by the real refs schema + workbook data when available; falls back to [mockRefGraph] otherwise.
 *
 * [DumbAware] so the view is NOT replaced by the "indexes are built" placeholder while the IDE indexes —
 * our content reads the workbook directly (POI), it never needs the IDE's indexes.
 */
class RefGraphToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val fg = UIUtil.getLabelForeground().rgb
        val bg = UIUtil.getPanelBackground().rgb
        val panel = JewelComposePanel { RelationshipTabs(project, fg, bg) }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
