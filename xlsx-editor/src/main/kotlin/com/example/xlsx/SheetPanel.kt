package com.example.xlsx

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.apache.poi.ss.util.CellReference
import java.awt.BorderLayout
import java.awt.Component
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.event.ListSelectionListener
import javax.swing.table.TableRowSorter

/**
 * One sheet's UI: the grid with an Excel-style row-number gutter + cross-highlight. The filter bar
 * and status bar are shared **editor** chrome (see [createFilterBar]/[createStatusBar] in
 * ComposeChrome.kt); this panel feeds them via [onChrome]. Owns the [SheetTableModel], [JBTable], a
 * filter-only [TableRowSorter], a [VimGridController], and a [ColumnFilterController].
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

    // The remembered freeze default (GridPrefs), snapshotted on the first streamed rows so every
    // sheet decides once at open time: -1 = not decided yet, 0 = nothing to auto-apply (either no
    // default, or the user already froze/unfroze THIS sheet manually — manual always wins).
    private var pendingAutoFreeze = -1

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
    var onChrome: ((ChromeData) -> Unit)? = null
    private var ready = false

    // Filter-derived state, recomputed only when the query / column filters / key row change (NOT on
    // every cursor move). updateStatus() reads these; rebuildRowFilter() reuses the compiled regex.
    private var compiledRegex: Regex? = null
    private var regexValid = true
    private var cachedChips: List<FilterChip> = emptyList()

    private var sorter: TableRowSorter<SheetTableModel>? = null

    private val columnFilter = ColumnFilterController(table, model) {
        recomputeFilterState()
        rebuildRowFilter()
        updateStatus()
        table.tableHeader.repaint()
    }

    /** Recompute the cached compiled regex (+ validity) and the column-filter chips. Call only when the
     *  text query, the column filters, or the key row actually change — never on a cursor move. */
    private fun recomputeFilterState() {
        val q = filterQueryText.trim()
        compiledRegex = if (q.isEmpty()) null else runCatching { Regex(q, RegexOption.IGNORE_CASE) }.getOrNull()
        regexValid = q.isEmpty() || compiledRegex != null
        cachedChips = columnFilter.activeFilters().entries.sortedBy { it.key }
            .map { (col, vals) -> FilterChip(col, chipLabel(col), vals.size) }
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
        // Ctrl+R: show the selected row's record in the relationship explorer (needs the action's project).
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = showRelationships(e.project)
        }.registerCustomShortcutSet(CustomShortcutSet.fromString("control R"), table)
        // Ctrl+F: centre the table-level ER map on THIS sheet's table (overrides IDE Find while the
        // grid is focused — the read-only grid has its own `/` filter instead).
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = showTableGraph(e.project)
        }.registerCustomShortcutSet(CustomShortcutSet.fromString("control F"), table)

        model.addTableModelListener {
            rowHeader.revalidate(); rowHeader.repaint()
            if (!ready) updateStatus() // show streaming progress (loaded-row count) in the status bar
            maybeAutoFreeze() // apply the remembered freeze as soon as enough rows have streamed in
        }
        // Excel-style cross-highlight: moving the active cell repaints both axes. Skip the status push
        // for valueIsAdjusting (mid-drag) events — the repaints still run for the live highlight.
        val selectionListener = ListSelectionListener { e ->
            if (!e.valueIsAdjusting) updateStatus()
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

    /** This sheet's schema table, resolved against the workbook's refs.json — the SAME source the tool
     *  window uses. Prefers the table from the OPEN workbook: recursion can surface the same sheet name in
     *  several files under the root (all sharing the plain `sheet`; only tableId is qualified). */
    private fun schemaTableForThisSheet(project: Project): SchemaTable? {
        val schema = resolveSchema(project) ?: return null
        val openRel = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?.let { runCatching { File(it.path).relativeTo(schema.baseDir).invariantSeparatorsPath }.getOrNull() }
        val candidates = schema.tables.filter { it.sheet == model.sheetName }
        return candidates.firstOrNull { it.file == openRel } ?: candidates.firstOrNull()
    }

    /** Ctrl+F: centre the table-level ER map on this sheet's table (if it is in the schema). */
    private fun showTableGraph(project: Project?) {
        if (project == null) return
        val st = schemaTableForThisSheet(project) ?: return
        RelationshipBus.showTable(st.tableId)
        ToolWindowManager.getInstance(project).getToolWindow("관계도")?.show()
    }

    /** Publish the selected row's record to the relationship explorer (if this sheet is in the schema). */
    private fun showRelationships(project: Project?) {
        if (project == null) return
        val st = schemaTableForThisSheet(project) ?: return
        val idColumns = st.idCols.map { columnForFieldCode(it) }
        if (idColumns.any { it < 0 }) return // a key field isn't in this sheet → can't identify the row
        val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return
        val mr = table.convertRowIndexToModel(viewRow)
        // Build the FULL composite display id ("groupKey·slot") so a composite-key row centres on itself,
        // not the first row sharing its first column.
        val displayId = idColumns.joinToString("·") { mc -> model.getValueAt(mr, mc)?.toString()?.trim().orEmpty() }
        if (displayId.isBlank() || displayId.all { it == '·' }) return
        RelationshipBus.show(st.tableId, displayId)
        ToolWindowManager.getInstance(project).getToolWindow("관계도")?.show()
    }

    // ---- Shared filter / status (driven by the editor's chrome) ----

    /** Apply a filter query (from the shared bar). Safe before streaming finishes — [rebuildRowFilter]
     *  no-ops until the sorter exists, and [onStreamingFinished] applies the pending query then. */
    fun applyFilter(query: String) {
        if (query == filterQueryText) return // already applied (e.g. re-selecting the same sheet's tab)
        filterQueryText = query
        recomputeFilterState()
        rebuildRowFilter()
        updateStatus()
    }

    fun filterText(): String = filterQueryText

    /** Remove a per-column filter (from a filter-bar chip's ✕). */
    fun clearColumnFilter(col: Int) = columnFilter.clearFilter(col)

    /** Clear the text query AND all column filters (from the filter bar's "clear all"). */
    fun clearAllFilters() {
        filterQueryText = ""
        columnFilter.clearAllSilently() // we rebuild once below, not via the controller's onChanged
        recomputeFilterState()
        rebuildRowFilter()
        updateStatus()
    }

    /** Recompute the status line and push it to the sink (e.g. when this sheet becomes active). */
    fun refreshStatus() = updateStatus()

    /** Focus the grid and ensure a selection (called after Enter in the shared filter). */
    fun focusGrid() {
        if (table.rowCount > 0 && table.selectedRow < 0) table.changeSelection(0, 0, false, false)
        table.requestFocusInWindow()
    }

    /**
     * Select the row whose [columnHeader] field (matched against the field-code header in model row 0)
     * holds [value], and scroll to it — used by the relationship graph's "open this record". Returns
     * false if the column/row isn't found. A blank [value] just focuses the sheet (no row).
     */
    /** Model column whose field-code header (model row 0) equals [code], or -1. O(columns). */
    private fun columnForFieldCode(code: String): Int =
        (0 until model.columnCount).firstOrNull { model.getValueAt(0, it)?.toString()?.trim() == code } ?: -1

    // Lazy value→row index per column so reveal is O(1) instead of an O(rows) EDT scan (a 60MB sheet can
    // hold 1M+ rows; scanning on every navigate would stall the UI). Rebuilt when the row count changes.
    private val revealIndex = HashMap<Int, HashMap<String, Int>>()
    private var revealIndexRows = -1
    private fun rowForValue(mc: Int, value: String): Int {
        if (revealIndexRows != model.rowCount) { revealIndex.clear(); revealIndexRows = model.rowCount }
        val byValue = revealIndex.getOrPut(mc) {
            val m = HashMap<String, Int>(model.rowCount.coerceAtLeast(16))
            for (r in 0 until model.rowCount) {
                val v = model.getValueAt(r, mc)?.toString()?.trim().orEmpty()
                if (v.isNotEmpty()) m.putIfAbsent(v, r) // first matching row wins (matches the old scan)
            }
            m
        }
        return byValue[value] ?: -1
    }

    fun revealRow(columnHeader: String, value: String): Boolean {
        if (value.isBlank()) { table.requestFocusInWindow(); return true }
        val mc = columnForFieldCode(columnHeader).takeIf { it >= 0 } ?: return false
        val mr = rowForValue(mc, value).takeIf { it >= 0 } ?: return false
        val vr = runCatching { table.convertRowIndexToView(mr) }.getOrDefault(-1)
        val vc = runCatching { table.convertColumnIndexToView(mc) }.getOrDefault(-1)
        if (vr in 0 until table.rowCount && vc in 0 until table.columnCount) {
            table.changeSelection(vr, vc, false, false)
            table.scrollRectToVisible(table.getCellRect(vr, vc, true))
        }
        table.requestFocusInWindow()
        return true
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
        // Apply any filter typed while streaming — the sorter didn't exist yet, so rebuildRowFilter no-op'd.
        recomputeFilterState()
        rebuildRowFilter()
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

    /** Toggle: if anything is frozen, unfreeze; otherwise freeze model rows 0..current as headers.
     *  Either way the result is REMEMBERED (GridPrefs) and auto-applied to sheets opened later. */
    private fun toggleFreezeAtCurrent() {
        pendingAutoFreeze = 0 // a manual toggle always wins over the remembered default
        if (frozenRowCount > 0) {
            frozenRowCount = 0
            keyRow = -1
        } else {
            val viewRow = table.selectedRow
            if (viewRow < 0) return
            frozenRowCount = table.convertRowIndexToModel(viewRow) + 1
            keyRow = frozenRowCount - 1 // default key = last (most specific) header row
        }
        GridPrefs.getInstance().remember(frozenRowCount, keyRow)
        applyFreeze()
    }

    /** Auto-apply the remembered freeze once this sheet has streamed enough rows. One-shot. */
    private fun maybeAutoFreeze() {
        if (frozenRowCount > 0) return // already frozen (manually or by an earlier auto-apply)
        if (pendingAutoFreeze == -1) pendingAutoFreeze = GridPrefs.getInstance().frozenRows() // decide once
        val rows = pendingAutoFreeze
        if (rows <= 0 || model.rowCount < rows) return // nothing remembered / not enough rows yet
        pendingAutoFreeze = 0
        frozenRowCount = rows
        keyRow = GridPrefs.getInstance().keyRow().coerceIn(0, rows - 1)
        applyFreeze()
    }

    /** Cycle the key row (whose values name columns in the jump popup) through the frozen rows. */
    private fun cycleKeyRow() {
        if (frozenRowCount <= 0) return
        keyRow = if (keyRow < 0) frozenRowCount - 1 else (keyRow + 1) % frozenRowCount
        GridPrefs.getInstance().remember(frozenRowCount, keyRow) // the key row is part of the remembered setup
        frozenTable.repaint()
        recomputeFilterState() // chip labels come from the key row
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
        recomputeFilterState() // chip labels come from the (now changed) key row
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
        // The text query is a vim-style regex search (case-insensitive substring match), compiled once
        // in recomputeFilterState() (not per cell — matters at 100k rows); an incomplete/invalid pattern
        // (compiledRegex == null) falls back to a literal case-insensitive contains so filtering never errors.
        val regex: Regex? = compiledRegex
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
        val sink = onChrome ?: return // only the active sheet drives the shared chrome
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
        val colFilters = columnFilter.activeFilters()
        val queryActive = filterQueryText.isNotBlank()
        val filterCount = colFilters.size + (if (queryActive) 1 else 0)
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
            if (filterCount > 0) add("$filterCount filter(s)")
            if (frozenRowCount > 0) add("❄ $frozenRowCount frozen · key r${keyRow + 1}")
        }
        sink(
            ChromeData(
                status = parts.joinToString("   •   "),
                visible = visible,
                total = total,
                filterActive = queryActive || colFilters.isNotEmpty(),
                regexValid = regexValid,
                chips = cachedChips,
                streaming = !ready,
            ),
        )
    }

    /** Chip label for a filtered column: the key-row name if any, else the column letter. */
    private fun chipLabel(col: Int): String =
        keyRowLabel(col).ifEmpty { CellReference.convertNumToColString(col) }
}
