package com.example.logview

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/** The shared per-row background (so Time / Level / Message cells read as one row): selection, a faint
 *  level wash, or zebra. Kept out of the renderers so all three columns stay perfectly in sync. */
internal fun logRowBg(table: JTable, model: LogTableModel, viewRow: Int, isSelected: Boolean, stripe: Color): Color {
    if (isSelected) return UIUtil.getTableSelectionBackground(true)
    val modelRow = if (viewRow < table.rowCount) table.convertRowIndexToModel(viewRow) else viewRow
    val level = if (modelRow in 0 until model.rowCount) model.levelAt(modelRow) else LogLevel.OTHER
    return LogStyling.levelBackground(level) ?: if (viewRow % 2 == 1) stripe else table.background
}

private val MONO: Font = LogFonts.MONO

/** The Time column — a muted monospace timestamp. */
class LogTimeRenderer(private val model: LogTableModel) : JComponent(), TableCellRenderer {
    private val stripe = UIUtil.getDecoratedRowColor()
    private var text = ""
    private var bg = UIUtil.getPanelBackground()
    private var fg = UIUtil.getContextHelpForeground()
    private var focused = false

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        text = value as? String ?: ""
        bg = logRowBg(table, model, row, isSelected, stripe)
        fg = if (isSelected) UIUtil.getTableSelectionForeground(true) else UIUtil.getContextHelpForeground()
        focused = hasFocus
        return this
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = bg; g2.fillRect(0, 0, width, height)
        GraphicsUtil.setupAntialiasing(g2)
        g2.font = MONO; g2.color = fg
        val fm = g2.fontMetrics
        g2.drawString(text, JBUI.scale(8), (height + fm.ascent - fm.descent) / 2)
        if (focused) { g2.color = LogStyling.ACCENT; g2.drawRect(0, 0, width - 1, height - 1) }
    }
}

/** The Level column — a filled severity dot + the level name, both in the level color. */
class LogLevelRenderer(private val model: LogTableModel) : JComponent(), TableCellRenderer {
    private val stripe = UIUtil.getDecoratedRowColor()
    private var text = ""
    private var bg = UIUtil.getPanelBackground()
    private var dot: Color? = null
    private var fg = UIUtil.getLabelForeground()
    private var focused = false

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        text = value as? String ?: ""
        bg = logRowBg(table, model, row, isSelected, stripe)
        val modelRow = if (row < table.rowCount) table.convertRowIndexToModel(row) else row
        val level = if (modelRow in 0 until model.rowCount) model.levelAt(modelRow) else LogLevel.OTHER
        dot = LogStyling.levelDot(level)
        fg = when {
            isSelected -> UIUtil.getTableSelectionForeground(true)
            dot != null -> dot!!
            else -> UIUtil.getContextHelpForeground()
        }
        focused = hasFocus
        return this
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = bg; g2.fillRect(0, 0, width, height)
        GraphicsUtil.setupAntialiasing(g2)
        var x = JBUI.scale(8)
        dot?.let {
            val d = JBUI.scale(7)
            g2.color = it
            g2.fillOval(x, (height - d) / 2, d, d)
            x += d + JBUI.scale(6)
        }
        g2.font = MONO; g2.color = fg
        val fm = g2.fontMetrics
        g2.drawString(text, x, (height + fm.ascent - fm.descent) / 2)
        if (focused) { g2.color = LogStyling.ACCENT; g2.drawRect(0, 0, width - 1, height - 1) }
    }
}
