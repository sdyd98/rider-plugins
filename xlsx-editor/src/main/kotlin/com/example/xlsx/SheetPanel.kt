package com.example.xlsx

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.apache.poi.ss.util.CellReference
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener
import javax.swing.table.TableRowSorter

/**
 * One sheet's UI: filter bar (top), grid with an Excel-style row-number gutter + cross-highlight
 * (center), and a status bar (bottom). Owns the [SheetTableModel], [JBTable], a filter-only
 * [TableRowSorter], a [VimGridController], and a [ColumnFilterController]. The global text filter
 * and the per-column value filters combine into one [RowFilter] (text = OR across columns; AND across columns).
 */
class SheetPanel(
    val model: SheetTableModel,
    onNextSheet: () -> Unit = {},
    onPrevSheet: () -> Unit = {},
) {

    val table = JBTable(model).apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        cellSelectionEnabled = true
        setDefaultRenderer(Any::class.java, GridCellRenderer())
        setDefaultEditor(Any::class.java, FormulaCellEditor()) // edit shows formula text for formula cells
        // Lean on zebra striping for rows; keep light vertical separators for columns.
        setShowHorizontalLines(false)
        setShowVerticalLines(true)
        gridColor = JBColor.namedColor("Table.gridColor", JBColor.border())
        intercellSpacing = JBUI.size(1, 0)
    }

    private val scrollPane = JBScrollPane(table)
    private val rowHeader = RowNumberHeader(table)
    private val statusBar = JBLabel(" ").apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(3, 8))
        foreground = UIUtil.getContextHelpForeground()
    }

    private var sorter: TableRowSorter<SheetTableModel>? = null

    private val filterField = SearchTextField().apply {
        isEnabled = false
        textEditor.emptyText.text = "Filter rows (regex) across all columns…"
    }

    private val columnFilter = ColumnFilterController(table, model) {
        rebuildRowFilter()
        updateStatus()
        table.tableHeader.repaint()
    }

    // Vim mode is always on; mode is not displayed.
    private val vim = VimGridController(
        table,
        onFocusFilter = { if (filterField.isEnabled) filterField.requestFocusInWindow() },
        onNextSheet = onNextSheet,
        onPrevSheet = onPrevSheet,
    )

    // Debounce so a keystroke at 100k rows doesn't re-filter on every char.
    private val debounce = Timer(150) { rebuildRowFilter(); updateStatus() }.apply { isRepeats = false }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(buildToolbar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
    }

    init {
        scrollPane.setRowHeaderView(rowHeader)
        vim.setEnabled(true)

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

    private fun buildToolbar(): JComponent {
        filterField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = debounce.restart()
            override fun removeUpdate(e: DocumentEvent) = debounce.restart()
            override fun changedUpdate(e: DocumentEvent) = debounce.restart()
        })
        // Enter in the filter applies immediately and returns focus to the grid (for vim navigation).
        filterField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    debounce.stop()
                    rebuildRowFilter()
                    updateStatus()
                    if (table.rowCount > 0 && table.selectedRow < 0) table.changeSelection(0, 0, false, false)
                    table.requestFocusInWindow()
                    e.consume()
                }
            }
        })
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6)
            add(JBLabel("Filter: "), BorderLayout.WEST)
            add(filterField, BorderLayout.CENTER)
        }
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
        filterField.isEnabled = true
        rowHeader.revalidate(); rowHeader.repaint()
        updateStatus()
    }

    /** Apply formula positions (from the reader/scanner) and refresh. EDT only. */
    fun applyFormulas(map: Map<Long, String>) {
        model.setFormulas(map)
        table.repaint()
        updateStatus()
    }

    /** Combine the global text query and the per-column value filters into one row filter. */
    private fun rebuildRowFilter() {
        val s = sorter ?: return
        val query = filterField.text.trim()
        val columns = columnFilter.activeFilters()
        if (query.isEmpty() && columns.isEmpty()) {
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
        val ref = if (r >= 0 && c >= 0) {
            CellReference.convertNumToColString(table.convertColumnIndexToModel(c)) + (table.convertRowIndexToModel(r) + 1)
        } else {
            "—"
        }
        val visible = table.rowCount
        val total = model.loadedRowCount()
        val rowsText = if (visible == total) "%,d rows".format(total) else "%,d / %,d rows".format(visible, total)
        val filters = columnFilter.activeFilters().size + (if (filterField.text.isNotBlank()) 1 else 0)
        val formula = if (r >= 0 && c >= 0) {
            val mr = table.convertRowIndexToModel(r)
            val mc = table.convertColumnIndexToModel(c)
            if (model.isFormula(mr, mc)) model.formulaText(mr, mc) else null
        } else {
            null
        }
        val parts = buildList {
            add(ref)
            if (formula != null) add("ƒ $formula")
            add(rowsText)
            if (filters > 0) add("$filters filter(s)")
        }
        statusBar.text = parts.joinToString("   •   ")
    }
}
