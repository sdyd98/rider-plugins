package com.example.logview.rules

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Edits the persisted [StoredRule] list: a small grid of enabled / pattern (regex) / foreground color
 * (6-digit hex, shown as a swatch) / bold, with add & remove. Saved to [HighlightRulesStore] on OK.
 */
class HighlightRulesDialog(project: Project?) : DialogWrapper(project) {

    private val working: MutableList<StoredRule> = HighlightRulesStore.getInstance().editableCopy()
    private val tableModel = RulesTableModel(working)
    private val table = JBTable(tableModel)

    init {
        title = "하이라이트 규칙"
        init()
    }

    override fun createCenterPanel(): JComponent {
        table.rowHeight = JBUI.scale(24)
        table.columnModel.getColumn(0).maxWidth = JBUI.scale(70)   // enabled
        table.columnModel.getColumn(2).maxWidth = JBUI.scale(110)  // color
        table.columnModel.getColumn(3).maxWidth = JBUI.scale(60)   // bold
        table.columnModel.getColumn(2).cellRenderer = SwatchRenderer()

        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { tableModel.add(StoredRule(pattern = "")) }
            .setRemoveAction {
                val r = table.selectedRow
                if (r >= 0) tableModel.removeAt(table.convertRowIndexToModel(r))
            }
            .createPanel()
        decorated.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(280))
        return decorated
    }

    override fun doOKAction() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        HighlightRulesStore.getInstance().replaceAll(working)
        super.doOKAction()
    }

    private class RulesTableModel(private val rows: MutableList<StoredRule>) : AbstractTableModel() {
        private val cols = arrayOf("사용", "패턴 (정규식)", "색상 (hex)", "굵게")

        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getColumnClass(c: Int): Class<*> = if (c == 0 || c == 3) java.lang.Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(r: Int, c: Int) = true

        override fun getValueAt(r: Int, c: Int): Any {
            val rule = rows[r]
            return when (c) {
                0 -> rule.enabled
                1 -> rule.pattern
                2 -> if (rule.fg == StoredRule.NONE) "" else "%06X".format(rule.fg and 0xFFFFFF)
                else -> rule.bold
            }
        }

        override fun setValueAt(value: Any?, r: Int, c: Int) {
            val rule = rows[r]
            when (c) {
                0 -> rule.enabled = value as? Boolean ?: true
                1 -> rule.pattern = value?.toString() ?: ""
                2 -> rule.fg = parseHex(value?.toString())
                3 -> rule.bold = value as? Boolean ?: false
            }
            fireTableRowsUpdated(r, r)
        }

        fun add(rule: StoredRule) { rows.add(rule); fireTableRowsInserted(rows.size - 1, rows.size - 1) }
        fun removeAt(i: Int) { if (i in rows.indices) { rows.removeAt(i); fireTableRowsDeleted(i, i) } }

        private fun parseHex(s: String?): Int {
            val t = s?.trim()?.removePrefix("#") ?: return StoredRule.NONE
            if (t.isEmpty()) return StoredRule.NONE
            return t.toIntOrNull(16)?.and(0xFFFFFF) ?: StoredRule.NONE
        }
    }

    /** Paints the foreground-color cell with its color as the background swatch. */
    private class SwatchRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val hex = value?.toString()?.takeIf { it.isNotBlank() }
            if (hex != null) {
                val rgb = hex.toIntOrNull(16)
                if (rgb != null) {
                    background = Color(rgb)
                    foreground = if (isReadableOnDark(rgb)) JBColor.WHITE else JBColor.BLACK
                    return c
                }
            }
            background = table.background
            foreground = table.foreground
            return c
        }

        private fun isReadableOnDark(rgb: Int): Boolean {
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000 < 140
        }
    }
}
