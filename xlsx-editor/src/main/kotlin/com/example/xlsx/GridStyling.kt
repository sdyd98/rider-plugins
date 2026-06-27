package com.example.xlsx

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Point
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.border.Border
import javax.swing.table.DefaultTableCellRenderer
import com.intellij.ui.table.JBTable

/** Theme-aware accent used for the active-cell ring and active-filter emphasis. */
internal val GRID_ACCENT: JBColor = JBColor(0x3574F0, 0x589DF6)

/**
 * Cell renderer: zebra striping, right-aligned numbers, a bold/tinted first (header) data row, and
 * a 2px accent ring on the focused cell (important since always-on vim has no blinking caret).
 *
 * Fonts and the stripe color are cached, and numeric detection bails on the first char, so the
 * per-cell paint (the hot path during scroll) does almost no work and no allocation for the common
 * no-formula case (the boxing `isFormula` lookup is skipped unless the sheet actually has formulas).
 */
class GridCellRenderer : DefaultTableCellRenderer() {

    private var seenFont: Font? = null
    private var plainFont: Font? = null
    private var boldFont: Font? = null
    private val stripe = UIUtil.getDecoratedRowColor()
    private val formulaBg = ColorUtil.withAlpha(GRID_ACCENT, 0.12)
    private val focusBorder: Border = JBUI.Borders.customLine(GRID_ACCENT, 2, 2, 2, 2)

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as DefaultTableCellRenderer
        val text = value as? String ?: ""
        c.horizontalAlignment = if (looksNumeric(text)) SwingConstants.RIGHT else SwingConstants.LEFT

        val current = c.font
        if (current !== seenFont && current !== plainFont && current !== boldFont) {
            seenFont = current
            plainFont = current.deriveFont(Font.PLAIN)
            boldFont = current.deriveFont(Font.BOLD)
        }
        val modelRow = if (row < table.rowCount) table.convertRowIndexToModel(row) else row
        c.font = if (modelRow == 0) boldFont else plainFont

        val sheetModel = table.model as? SheetTableModel
        // hasFormulas() is a non-boxing isEmpty() check; gate the boxing isFormula() lookup behind it
        // so files without any formulas pay nothing per painted cell.
        val isFormula = sheetModel != null && modelRow >= 0 && sheetModel.hasFormulas() &&
            sheetModel.isFormula(modelRow, table.convertColumnIndexToModel(column))
        if (!isSelected) {
            c.background = when {
                modelRow == 0 -> UIUtil.getPanelBackground() // header row joins the chrome
                isFormula -> formulaBg
                row % 2 == 1 -> stripe
                else -> table.background
            }
        }
        if (hasFocus) c.border = focusBorder // else: super already set the no-focus border
        return c
    }

    /** Cheap numeric check: bail before the (regex-backed) parse unless the first char could start a number. */
    private fun looksNumeric(s: String): Boolean {
        if (s.isEmpty()) return false
        val ch = s[0]
        if (ch !in '0'..'9' && ch != '-' && ch != '+' && ch != '.') return false
        return s.toDoubleOrNull() != null
    }
}

/**
 * Excel-style row-number gutter shown as the scroll pane's row header. Paints numbers from the
 * table's live geometry (so it tracks scroll/filter/edits) and highlights the active row, like
 * Excel lighting up the current row number.
 */
class RowNumberHeader(private val table: JBTable) : JComponent() {

    init {
        font = table.font
        isOpaque = true
    }

    override fun getPreferredSize(): Dimension {
        val rows = table.rowCount
        val height = if (rows > 0) table.getCellRect(rows - 1, 0, true).let { it.y + it.height } else 0
        val digits = maxOf(2, table.rowCount.toString().length)
        val width = getFontMetrics(font).charWidth('0') * digits + JBUI.scale(14)
        return Dimension(width, height)
    }

    override fun paintComponent(g: Graphics) {
        val clip = g.clipBounds
        g.color = UIUtil.getPanelBackground()
        g.fillRect(clip.x, clip.y, clip.width, clip.height)

        val rowCount = table.rowCount
        if (rowCount == 0) return
        var first = table.rowAtPoint(Point(0, clip.y))
        if (first < 0) first = 0
        var last = table.rowAtPoint(Point(0, clip.y + clip.height - 1))
        if (last < 0) last = rowCount - 1

        val selectionBg = UIUtil.getTableSelectionBackground(false)
        val selectionFg = UIUtil.getTableSelectionForeground(false)
        val plain = font.deriveFont(Font.PLAIN)
        val bold = font.deriveFont(Font.BOLD)
        val fm = g.getFontMetrics(plain)

        for (r in first..last) {
            if (r < 0 || r >= rowCount) continue
            val rect = table.getCellRect(r, 0, true)
            val selected = table.selectionModel.isSelectedIndex(r)
            if (selected) {
                g.color = selectionBg
                g.fillRect(0, rect.y, width, rect.height)
            }
            val num = (table.convertRowIndexToModel(r) + 1).toString()
            g.font = if (selected) bold else plain
            g.color = if (selected) selectionFg else JBColor.GRAY
            val tx = width - fm.stringWidth(num) - JBUI.scale(7)
            val ty = rect.y + (rect.height + fm.ascent - fm.descent) / 2
            g.drawString(num, tx, ty)
        }
        g.color = JBColor.border()
        g.drawLine(width - 1, clip.y, width - 1, clip.y + clip.height)
    }
}

/**
 * Cell editor that shows the **formula text** (e.g. `=A2+B2`) when you edit a formula cell, instead
 * of its computed value — so you can read and edit the formula like in Excel. Committing a value
 * that starts with `=` is treated as a formula by [SheetTableModel.setValueAt].
 */
class FormulaCellEditor : DefaultCellEditor(JBTextField()) {
    override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
        val model = table.model as? SheetTableModel
        val initial = if (model != null) {
            val mr = table.convertRowIndexToModel(row)
            val mc = table.convertColumnIndexToModel(column)
            if (model.isFormula(mr, mc)) model.formulaText(mr, mc) else value
        } else {
            value
        }
        return super.getTableCellEditorComponent(table, initial, isSelected, row, column)
    }
}

/** Size each column to fit its header + a sample of rows (cheap even for huge sheets). */
fun autoSizeColumns(table: JBTable, sampleRows: Int = 200) {
    val fm = table.getFontMetrics(table.font.deriveFont(Font.BOLD))
    val rowsToSample = minOf(table.rowCount, sampleRows)
    for (viewCol in 0 until table.columnCount) {
        var w = fm.stringWidth(table.getColumnName(viewCol)) + JBUI.scale(34) // header text + funnel icon room
        for (r in 0 until rowsToSample) {
            val text = table.getValueAt(r, viewCol)?.toString() ?: ""
            if (text.isNotEmpty()) w = maxOf(w, fm.stringWidth(text) + JBUI.scale(16))
        }
        table.columnModel.getColumn(viewCol).preferredWidth = w.coerceIn(JBUI.scale(44), JBUI.scale(420))
    }
}
