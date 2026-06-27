package com.example.xlsx

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.apache.poi.ss.util.CellReference
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.event.ListSelectionListener
import javax.swing.table.TableRowSorter

/**
 * One sheet's UI: filter bar (top), grid with an Excel-style row-number gutter + cross-highlight
 * (center), and a status bar (bottom). Owns the [SheetTableModel], [JBTable], a filter-only
 * [TableRowSorter], a [VimGridController], and a [ColumnFilterController].
 *
 * **Frozen header rows:** the top `frozenRowCount` model rows are shown in a separate [frozenTable]
 * placed in the scroll pane's column-header view (so they stay pinned at the top while scrolling and
 * are never hidden by filtering — the main table's filter excludes them). [frozenTable] shares the
 * main table's column model, so column widths/order stay in sync. One frozen row is the **key row**
 * whose values name the columns in the `Alt+\` jump popup.
 */
class SheetPanel(
    val model: SheetTableModel,
    onNextSheet: () -> Unit = {},
    onPrevSheet: () -> Unit = {},
    onFocusFilter: () -> Unit = {},
) {

    val table: JBTable = object : JBTable(model) {
        override fun configureEnclosingScrollPane() {
            super.configureEnclosingScrollPane()
            // super resets the scroll pane's column header to the bare table header on every addNotify;
            // restore our composite header (column letters + frozen header rows) so frozen rows show.
            installCompositeColumnHeader()
        }
    }.apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        cellSelectionEnabled = true
        setDefaultRenderer(Any::class.java, GridCellRenderer())
        // Lean on zebra striping for rows; keep light vertical separators for columns.
        setShowHorizontalLines(false)
        setShowVerticalLines(true)
        gridColor = JBColor.namedColor("Table.gridColor", JBColor.border())
        intercellSpacing = JBUI.size(1, 0)
    }

    // ---- Frozen header rows ----
    private var frozenRowCount = 0
    private var keyRow = -1
    private val keyTint = ColorUtil.withAlpha(GRID_ACCENT, 0.18)

    /** Display-only table for the frozen rows; shares the main column model, lives in the header. */
    private val frozenTable = JBTable(model).apply {
        columnModel = table.columnModel // share widths/order with the main table
        setTableHeader(null)
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        isFocusable = false
        rowSelectionAllowed = false
        columnSelectionAllowed = false
        setShowHorizontalLines(false)
        setShowVerticalLines(true)
        gridColor = JBColor.namedColor("Table.gridColor", JBColor.border())
        intercellSpacing = JBUI.size(1, 0)
    }
    private var frozenSorter: TableRowSorter<SheetTableModel>? = null
    private val headerPanel = JPanel(BorderLayout())

    private val scrollPane = JBScrollPane(table)
    private val rowHeader = RowNumberHeader(table)

    // The filter bar + status bar are owned by the editor (one shared Compose pair, not one per
    // sheet — see XlsxFileEditor). This sheet holds its own filter query and a status sink that the
    // editor points at the active sheet so its status drives the shared status bar.
    private var filterQueryText: String = ""
    var statusSink: ((String) -> Unit)? = null
    private var ready = false

    private var sorter: TableRowSorter<SheetTableModel>? = null

    private val columnFilter = ColumnFilterController(table, model) {
        rebuildRowFilter()
        updateStatus()
        table.tableHeader.repaint()
    }

    // Vim mode is always on; mode is not displayed. `/` focuses the editor's shared filter bar.
    private val vim = VimGridController(
        table,
        onFocusFilter = onFocusFilter,
        onNextSheet = onNextSheet,
        onPrevSheet = onPrevSheet,
    )

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(scrollPane, BorderLayout.CENTER)
    }

    init {
        frozenTable.rowHeight = table.rowHeight
        frozenTable.setDefaultRenderer(Any::class.java, FrozenRowRenderer())
        // Attach the frozen-rows sorter now (filter excludes everything until rows are frozen) so the
        // header never shows the whole streaming sheet; it stays in sync as rows stream in.
        val fs = TableRowSorter(model).apply {
            sortsOnUpdates = false
            rowFilter = frozenRowFilter()
        }
        frozenTable.rowSorter = fs
        frozenSorter = fs
        installCompositeColumnHeader()
        scrollPane.setRowHeaderView(rowHeader)
        vim.setEnabled(true)

        registerShortcut("alt BACK_SLASH") { showColumnJumpPopup() }
        registerShortcut("alt shift F") { toggleFreezeAtCurrent() }
        registerShortcut("alt K") { cycleKeyRow() }

        model.addTableModelListener {
            rowHeader.revalidate(); rowHeader.repaint()
        }
        // Excel-style cross-highlight: moving the active cell repaints both axes.
        val selectionListener = ListSelectionListener {
            updateStatus()
            rowHeader.repaint()
            table.tableHeader.repaint()
        }
        table.selectionModel.addListSelectionListener(selectionListener)
        table.columnModel.selectionModel.addListSelectionListener(selectionListener)
        updateStatus()
    }

    /** Install our column-header = [table]'s header (A/B/C + funnels) on top of the [frozenTable]. */
    private fun installCompositeColumnHeader() {
        headerPanel.removeAll()
        headerPanel.add(table.tableHeader, BorderLayout.NORTH)
        headerPanel.add(frozenTable, BorderLayout.CENTER)
        scrollPane.setColumnHeaderView(headerPanel)
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    private fun registerShortcut(shortcut: String, run: () -> Unit) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = run()
        }.registerCustomShortcutSet(CustomShortcutSet.fromString(shortcut), table)
    }

    // ---- Shared filter / status (driven by the editor's chrome) ----

    /** Apply a filter query (from the shared bar). Safe before streaming finishes — [rebuildRowFilter]
     *  no-ops until the sorter exists. */
    fun applyFilter(query: String) {
        filterQueryText = query
        rebuildRowFilter()
        updateStatus()
    }

    fun filterText(): String = filterQueryText

    /** True once streaming finished and the filter sorter is attached. */
    fun isReady(): Boolean = ready

    /** Recompute the status line and push it to the sink (e.g. when this sheet becomes active). */
    fun refreshStatus() = updateStatus()

    /** Focus the grid and ensure a selection (called after Enter in the shared filter). */
    fun focusGrid() {
        if (table.rowCount > 0 && table.selectedRow < 0) table.changeSelection(0, 0, false, false)
        table.requestFocusInWindow()
    }

    /** Called on the EDT once the sheet has finished streaming. */
    fun onStreamingFinished() {
        val s = TableRowSorter(model).apply {
            sortsOnUpdates = false
            for (c in 0 until model.columnCount) setSortable(c, false) // filter-only; values are display strings
        }
        s.addRowSorterListener {
            rowHeader.revalidate(); rowHeader.repaint(); updateStatus()
        }
        table.rowSorter = s
        sorter = s
        columnFilter.install()
        autoSizeColumns(table)
        ready = true
        rowHeader.revalidate(); rowHeader.repaint()
        updateStatus()
    }

    /** Apply formula positions (from the reader/scanner) and refresh. EDT only. */
    fun applyFormulas(map: Map<Long, String>) {
        model.setFormulas(map)
        table.repaint()
        updateStatus()
    }

    // ---- Freeze header rows / key row ----

    private fun frozenRowFilter() = object : RowFilter<SheetTableModel, Int>() {
        override fun include(entry: Entry<out SheetTableModel, out Int>): Boolean = entry.identifier < frozenRowCount
    }

    /** Toggle: if anything is frozen, unfreeze; otherwise freeze model rows 0..current as headers. */
    private fun toggleFreezeAtCurrent() {
        if (frozenRowCount > 0) {
            frozenRowCount = 0
            keyRow = -1
        } else {
            val viewRow = table.selectedRow
            if (viewRow < 0) return
            frozenRowCount = table.convertRowIndexToModel(viewRow) + 1
            keyRow = frozenRowCount - 1 // default key = last (most specific) header row
        }
        applyFreeze()
    }

    /** Cycle the key row (whose values name columns in the jump popup) through the frozen rows. */
    private fun cycleKeyRow() {
        if (frozenRowCount <= 0) return
        keyRow = if (keyRow < 0) frozenRowCount - 1 else (keyRow + 1) % frozenRowCount
        frozenTable.repaint()
        updateStatus()
    }

    private fun applyFreeze() {
        frozenSorter?.rowFilter = frozenRowFilter()
        rebuildRowFilter() // main table now excludes the frozen rows
        // Force the frozen table's height so the column-header region actually grows to show the rows
        // (BorderLayout/JViewport won't infer it reliably for a header-embedded table).
        val width = maxOf(table.columnModel.totalColumnWidth, 1)
        frozenTable.preferredSize = java.awt.Dimension(width, frozenRowCount * frozenTable.rowHeight)
        frozenTable.revalidate()
        headerPanel.revalidate(); headerPanel.repaint()
        scrollPane.revalidate(); scrollPane.repaint()
        // Keep a sensible selection in the main table.
        if (table.rowCount > 0 && table.selectedRow < 0) table.changeSelection(0, table.selectedColumn.coerceAtLeast(0), false, false)
        updateStatus()
    }

    // ---- Alt+\ column jump ----

    private class ColItem(val index: Int, val label: String) {
        override fun toString() = label
    }

    private fun keyRowLabel(col: Int): String {
        if (keyRow < 0 || keyRow >= model.rowCount) return ""
        return model.getValueAt(keyRow, col).toString().trim()
    }

    private fun showColumnJumpPopup() {
        if (model.columnCount == 0) return
        val items = (0 until model.columnCount).map { c ->
            val letter = CellReference.convertNumToColString(c)
            val name = keyRowLabel(c)
            ColItem(c, if (name.isNotEmpty()) "$letter · $name" else letter)
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(if (keyRow >= 0) "Jump to column (names from row ${keyRow + 1})" else "Jump to column")
            .setNamerForFiltering { it.label }
            .setItemChosenCallback { jumpToColumn(it.index) }
            .createPopup()
            .showInCenterOf(table)
    }

    private fun jumpToColumn(modelCol: Int) {
        if (table.rowCount == 0) return
        val viewCol = table.convertColumnIndexToView(modelCol)
        if (viewCol < 0) return
        val viewRow = table.selectedRow.coerceAtLeast(0)
        table.changeSelection(viewRow, viewCol, false, false)
        table.scrollRectToVisible(table.getCellRect(viewRow, viewCol, true))
        table.requestFocusInWindow()
    }

    /** Renderer for the frozen header rows; tints the key row with the accent. */
    private inner class FrozenRowRenderer : GridCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, false, false, row, column)
            val mr = if (row >= 0 && row < table.rowCount) frozenTable.convertRowIndexToModel(row) else row
            if (mr == keyRow) c.background = keyTint
            return c
        }
    }

    /** Combine the global text query, the per-column value filters, and the frozen-row exclusion. */
    private fun rebuildRowFilter() {
        val s = sorter ?: return
        val query = filterQueryText.trim()
        val columns = columnFilter.activeFilters()
        val frozen = frozenRowCount
        if (query.isEmpty() && columns.isEmpty() && frozen == 0) {
            s.rowFilter = null
            return
        }
        // The text query is a vim-style regex search (case-insensitive substring match). Compile it
        // once here (not per cell — matters at 100k rows); an incomplete/invalid pattern typed mid-
        // keystroke falls back to a literal case-insensitive contains so filtering never errors.
        val regex: Regex? = if (query.isEmpty()) null else {
            try {
                Regex(query, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                null
            }
        }
        s.rowFilter = object : RowFilter<SheetTableModel, Int>() {
            override fun include(entry: Entry<out SheetTableModel, out Int>): Boolean {
                if (entry.identifier < frozen) return false // frozen header rows live in the frozen table
                for ((col, allowed) in columns) {
                    val value = if (col < entry.valueCount) entry.getStringValue(col) else ""
                    if (value !in allowed) return false
                }
                if (query.isNotEmpty()) {
                    var any = false
                    for (i in 0 until entry.valueCount) {
                        val v = entry.getStringValue(i)
                        val match = if (regex != null) regex.containsMatchIn(v) else StringUtil.containsIgnoreCase(v, query)
                        if (match) {
                            any = true
                            break
                        }
                    }
                    if (!any) return false
                }
                return true
            }
        }
    }

    private fun updateStatus() {
        val r = table.selectedRow
        val c = table.selectedColumn
        // Validate against the CURRENT view bounds: a selection can be momentarily stale (beyond the
        // new view) right after a filter/freeze change, and converting it would index an empty sorter.
        val valid = r in 0 until table.rowCount && c in 0 until table.columnCount
        val ref = if (valid) {
            CellReference.convertNumToColString(table.convertColumnIndexToModel(c)) + (table.convertRowIndexToModel(r) + 1)
        } else {
            "—"
        }
        val visible = table.rowCount
        val total = model.loadedRowCount()
        val rowsText = if (visible == total) "%,d rows".format(total) else "%,d / %,d rows".format(visible, total)
        val filters = columnFilter.activeFilters().size + (if (filterQueryText.isNotBlank()) 1 else 0)
        val formula = if (valid) {
            val mr = table.convertRowIndexToModel(r)
            val mc = table.convertColumnIndexToModel(c)
            if (model.isFormula(mr, mc)) model.formulaText(mr, mc) else null
        } else {
            null
        }
        val parts = buildList {
            add(ref)
            if (formula != null) add("ƒ $formula · Excel에서 계산")
            add(rowsText)
            if (filters > 0) add("$filters filter(s)")
            if (frozenRowCount > 0) add("❄ $frozenRowCount frozen · key r${keyRow + 1}")
        }
        statusSink?.invoke(parts.joinToString("   •   "))
    }
}
