package com.example.xlsx

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColorUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableCellRenderer

/**
 * Excel-style per-column value filter. Each header shows a funnel (dimmed when the column is not
 * filtered, full + accent-tinted when it is); clicking it — or Ctrl+Alt+F on the selected column —
 * opens a searchable checkbox list of distinct values. Distinct values are computed off the EDT so
 * opening the popup never blocks on a full scan. Keys are MODEL column indices; multiple columns AND.
 */
class ColumnFilterController(
    private val table: JBTable,
    private val model: SheetTableModel,
    private val onChanged: () -> Unit,
) {
    private val filters = HashMap<Int, Set<String>>()
    private val activeIcon = AllIcons.General.Filter
    private val inactiveIcon = IconLoader.getDisabledIcon(AllIcons.General.Filter)

    fun activeFilters(): Map<Int, Set<String>> = filters

    fun install() {
        val base = table.tableHeader.defaultRenderer
        table.tableHeader.defaultRenderer = TableCellRenderer { t, value, selected, focused, row, column ->
            val comp = base.getTableCellRendererComponent(t, value, selected, focused, row, column)
            if (comp is JLabel) {
                val baseFg = comp.foreground
                val baseFont = comp.font
                val baseOpaque = comp.isOpaque
                comp.horizontalTextPosition = SwingConstants.LEFT
                val modelCol = t.convertColumnIndexToModel(column)
                val active = filters.containsKey(modelCol)
                val isCurrent = t.selectedColumn in 0 until t.columnCount &&
                    t.convertColumnIndexToModel(t.selectedColumn) == modelCol
                comp.icon = if (active) activeIcon else inactiveIcon
                comp.foreground = if (active) GRID_ACCENT else baseFg
                comp.font = if (active) baseFont.deriveFont(Font.BOLD) else baseFont
                when {
                    active -> { comp.isOpaque = true; comp.background = ColorUtil.withAlpha(GRID_ACCENT, 0.16) }
                    isCurrent -> { comp.isOpaque = true; comp.background = ColorUtil.withAlpha(GRID_ACCENT, 0.08) }
                    else -> comp.isOpaque = baseOpaque
                }
            }
            comp
        }
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val header = table.tableHeader
                val viewCol = header.columnAtPoint(e.point)
                if (viewCol < 0) return
                val rect = header.getHeaderRect(viewCol)
                if (e.x >= rect.x + rect.width - 4) return // resize edge — ignore
                openFilter(viewCol, RelativePoint(e))
            }
        })

        // Ctrl+Alt+F opens the filter for the current column (overrides any IDE-global shortcut
        // while the grid/editor is focused).
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (table.columnCount == 0) return
                val viewCol = table.selectedColumn.let { if (it in 0 until table.columnCount) it else 0 }
                openFilter(viewCol, headerPoint(viewCol))
            }
        }.registerCustomShortcutSet(CustomShortcutSet.fromString("ctrl alt F"), table)
    }

    private fun headerPoint(viewCol: Int): RelativePoint {
        val rect = table.tableHeader.getHeaderRect(viewCol)
        return RelativePoint(table.tableHeader, Point(rect.x, rect.y + rect.height))
    }

    /** Compute distinct values off the EDT, then build + show the popup on the EDT. */
    private fun openFilter(viewCol: Int, where: RelativePoint) {
        val modelCol = table.convertColumnIndexToModel(viewCol)
        val title = table.getColumnName(viewCol)
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val (distinct, truncated) = distinctValues(modelCol)
            app.invokeLater({ showPopup(modelCol, title, distinct, truncated, where) }, ModalityState.any())
        }
    }

    private fun distinctValues(modelCol: Int): Pair<List<String>, Boolean> {
        val set = LinkedHashSet<String>()
        var truncated = false
        val rowCount = model.loadedRowCount()
        for (r in 0 until rowCount) {
            set.add(model.getValueAt(r, modelCol).toString())
            if (set.size >= MAX_DISTINCT) {
                truncated = true
                break
            }
        }
        return set.sortedWith(String.CASE_INSENSITIVE_ORDER) to truncated
    }

    private fun label(value: String): String = value.ifEmpty { "(blanks)" }

    private fun showPopup(modelCol: Int, title: String, distinct: List<String>, truncated: Boolean, where: RelativePoint) {
        val current = filters[modelCol]
        val checked = LinkedHashMap<String, Boolean>()
        for (v in distinct) checked[v] = current == null || v in current

        val list = CheckBoxList<String>()
        fun rebuild(query: String) {
            list.clear()
            val q = query.trim()
            for (v in distinct) {
                if (q.isEmpty() || v.contains(q, ignoreCase = true)) list.addItem(v, label(v), checked[v] == true)
            }
        }
        rebuild("")
        list.setCheckBoxListListener { index, value -> list.getItemAt(index)?.let { checked[it] = value } }

        val search = SearchTextField()
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild(search.text)
            override fun removeUpdate(e: DocumentEvent) = rebuild(search.text)
            override fun changedUpdate(e: DocumentEvent) = rebuild(search.text)
        })

        val all = JButton("All").apply { addActionListener { distinct.forEach { checked[it] = true }; rebuild(search.text) } }
        val none = JButton("None").apply { addActionListener { distinct.forEach { checked[it] = false }; rebuild(search.text) } }
        val ok = JButton("OK")
        val cancel = JButton("Cancel")

        val top = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(all); add(none) }, BorderLayout.EAST)
        }
        val north = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            if (truncated) add(JBLabel(" Showing first $MAX_DISTINCT values "), BorderLayout.SOUTH)
        }
        val south = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(ok); add(cancel) }
        val content = JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
            preferredSize = Dimension(260, 320)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, search.textEditor)
            .setTitle("Filter: $title")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()

        ok.addActionListener {
            val allowed = checked.filterValues { it }.keys.toSet()
            if (allowed.size == distinct.size) filters.remove(modelCol) else filters[modelCol] = allowed
            table.tableHeader.repaint()
            onChanged()
            popup.cancel()
        }
        cancel.addActionListener { popup.cancel() }

        popup.show(where)
    }

    companion object {
        private const val MAX_DISTINCT = 5000
    }
}
