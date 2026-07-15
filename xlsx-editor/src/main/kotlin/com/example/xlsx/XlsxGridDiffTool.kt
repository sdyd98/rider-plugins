package com.example.xlsx

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.PrevNextDifferenceIterable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JViewport
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

private val LOG = logger<XlsxGridDiffTool>()

/**
 * GRID diff for two spreadsheet revisions — the same comparison the TSV text projection feeds to the
 * platform's text diff, but rendered as two side-by-side grids per sheet with row/cell-level
 * highlighting. Opens by default for Excel diffs (order="first" in plugin.xml); [XlsxDiffModel] does
 * the loading/alignment (row alignment reuses the platform ComparisonManager over the same row keys
 * as the text projection, so both views always agree).
 *
 * Finding the changes is first-class: each sheet auto-scrolls to its FIRST change when shown, the
 * platform의 차이점 이동 (F7/⇧F7, toolbar arrows) works via [PrevNextDifferenceIterable], vim keys are
 * always on ([VimDiffGridController] — `]c`/`[c` jump changes), a "변경만 보기" toolbar toggle hides
 * unchanged rows, and the two sides mirror row selection.
 */
class XlsxGridDiffTool : FrameDiffTool {

    override fun getName(): String = "Excel 그리드"

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        val contents = (request as? ContentDiffRequest)?.contents ?: return false
        return contents.size == 2 && contents.all { isExcel(workbookFile(it)) }
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        XlsxGridDiffViewer(request as ContentDiffRequest)

    companion object {
        internal fun workbookFile(content: DiffContent): VirtualFile? = when (content) {
            is FileContent -> content.file
            is DocumentContent -> content.highlightFile
            else -> null
        }

        private fun isExcel(file: VirtualFile?): Boolean {
            val ext = file?.extension?.lowercase() ?: return false
            return ext == "xlsx" || ext == "xls"
        }
    }
}

private class XlsxGridDiffViewer(private val request: ContentDiffRequest) : FrameDiffTool.DiffViewer {

    private val root: JPanel = object : JPanel(BorderLayout()), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            currentPane()?.navigator?.let { sink.set(DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE, it) }
        }
    }
    private val status = JBLabel("")
    private var tabs: JTabbedPane? = null
    private var panes: List<SheetPane?> = emptyList() // index-aligned with tabs; null = notice tab
    private var changedOnly = false
    @Volatile private var disposed = false

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = currentPane()?.leftTable ?: root

    override fun init(): FrameDiffTool.ToolbarComponents {
        root.add(JBLabel("워크북 비교 중…", SwingConstants.CENTER), BorderLayout.CENTER)
        ApplicationManager.getApplication().executeOnPooledThread { loadAndBuild() }
        return FrameDiffTool.ToolbarComponents().apply {
            statusPanel = status
            toolbarActions = listOf(ChangedOnlyAction())
        }
    }

    override fun dispose() {
        disposed = true
    }

    private fun currentPane(): SheetPane? = tabs?.selectedIndex?.takeIf { it >= 0 }?.let { panes.getOrNull(it) }

    private fun loadAndBuild() {
        val built = runCatching {
            val (leftFile, rightFile) = request.contents.map { XlsxGridDiffTool.workbookFile(it)!! }
            val left = XlsxDiffModel.loadSheets(readWorkbookBytes(leftFile), leftFile.extension.equals("xls", true))
            val right = XlsxDiffModel.loadSheets(readWorkbookBytes(rightFile), rightFile.extension.equals("xls", true))
            XlsxDiffModel.diff(left, right, comparisonAligner())
        }
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            root.removeAll()
            built.fold(
                onSuccess = { sheets -> root.add(buildTabs(sheets), BorderLayout.CENTER) },
                onFailure = { t ->
                    LOG.warn("grid diff failed", t)
                    root.add(JBLabel("그리드 비교 실패: ${t.message} — 텍스트 디프를 사용하세요", SwingConstants.CENTER), BorderLayout.CENTER)
                },
            )
            root.revalidate(); root.repaint()
        }
    }

    /** Row alignment via the platform diff engine — the exact engine the text projection diff uses. */
    private fun comparisonAligner() = XlsxDiffModel.RowAligner { left, right ->
        try {
            ComparisonManager.getInstance()
                .compareLines(left.joinToString("\n"), right.joinToString("\n"), ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
                // compareLines works on split lines, and an EMPTY side still counts as 1 empty line —
                // clamp ranges to the actual row counts so a 0-row sheet reads as pure inserts/deletes.
                .map { f ->
                    intArrayOf(
                        f.startLine1.coerceAtMost(left.size), f.endLine1.coerceAtMost(left.size),
                        f.startLine2.coerceAtMost(right.size), f.endLine2.coerceAtMost(right.size),
                    )
                }
        } catch (e: DiffTooBigException) {
            // One whole-sheet changed range: everything pairs as modified/insert/delete — still usable.
            listOf(intArrayOf(0, left.size, 0, right.size))
        }
    }

    private fun buildTabs(sheets: List<XlsxDiffModel.SheetDiff>): JComponent {
        val changedSheets = sheets.count { it.changed }
        status.text = "시트 ${sheets.size}개 중 ${changedSheets}개 변경 · " +
            "~${sheets.sumOf { it.modified }} · +${sheets.sumOf { it.inserted }} · −${sheets.sumOf { it.deleted }}"

        if (sheets.isEmpty()) return JBLabel("시트가 없습니다", SwingConstants.CENTER)
        val tabbed = JTabbedPane()
        val builtPanes = ArrayList<SheetPane?>(sheets.size)
        sheets.forEach { sheet ->
            val title = (if (sheet.changed) "✱ " else "") + sheet.name + (sheet.onlySide?.let { " ($it)" } ?: "")
            if (sheet.tooBig) {
                builtPanes.add(null)
                tabbed.addTab(title, JBLabel("시트가 너무 큽니다 (${XlsxDiffModel.MAX_DIFF_ROWS}행 초과) — 텍스트 디프를 사용하세요", SwingConstants.CENTER))
            } else {
                val pane = SheetPane(sheet)
                builtPanes.add(pane)
                tabbed.addTab(title, pane.component)
            }
        }
        panes = builtPanes
        tabs = tabbed
        // Entering a sheet: jump to its first change once, and focus the grid so vim keys work.
        tabbed.addChangeListener {
            val pane = currentPane() ?: return@addChangeListener
            SwingUtilities.invokeLater {
                pane.onShown()
                pane.leftTable.requestFocusInWindow()
            }
        }
        sheets.indexOfFirst { it.changed }.takeIf { it >= 0 }?.let { tabbed.selectedIndex = it }
        SwingUtilities.invokeLater {
            currentPane()?.let { it.onShown(); it.leftTable.requestFocusInWindow() }
        }
        return tabbed
    }

    /** Toolbar: hide unchanged rows — the diff stops being a needle in a 50k-row haystack. */
    private inner class ChangedOnlyAction : ToggleAction("변경만 보기", "변경된 행만 표시합니다", AllIcons.General.Filter) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = changedOnly
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            changedOnly = state
            panes.forEach { it?.applyChangedOnly(state) }
        }
    }

    /** One sheet's side-by-side diff: two tables over the SAME aligned rows, shared scroll model,
     *  mirrored row selection, a change navigator (F7 + `]c`/`[c`), and always-on vim keys. */
    private class SheetPane(private val sheet: XlsxDiffModel.SheetDiff) {
        val leftTable: JBTable = diffTable(leftSide = true)
        private val rightTable: JBTable = diffTable(leftSide = false)
        val navigator = Navigator()
        val component: JComponent
        private var shownOnce = false
        private var syncing = false

        init {
            listOf(leftTable, rightTable).forEach { table ->
                VimDiffGridController(
                    table,
                    nextChange = { n -> repeat(n) { if (navigator.canGoNext()) navigator.goNext() } },
                    prevChange = { n -> repeat(n) { if (navigator.canGoPrev()) navigator.goPrev() } },
                ).setEnabled(true)
            }
            mirrorSelection(leftTable, rightTable)
            mirrorSelection(rightTable, leftTable)

            val leftScroll = JBScrollPane(leftTable)
            val rightScroll = JBScrollPane(rightTable)
            // One shared scroll model = perfect row alignment while scrolling; mirror horizontal too.
            rightScroll.verticalScrollBar.model = leftScroll.verticalScrollBar.model
            rightScroll.horizontalScrollBar.model = leftScroll.horizontalScrollBar.model
            leftScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            component = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leftScroll, rightScroll).apply {
                resizeWeight = 0.5
                border = null
            }
        }

        private fun diffTable(leftSide: Boolean): JBTable {
            val table = JBTable(DiffSideModel(sheet, leftSide))
            table.setDefaultRenderer(Any::class.java, DiffCellRenderer(sheet, leftSide))
            table.autoResizeMode = JTable.AUTO_RESIZE_OFF
            table.setShowGrid(true)
            table.gridColor = JBColor(Color(0xE3E3E3), Color(0x3C3F41))
            table.cellSelectionEnabled = true
            table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            table.tableHeader.reorderingAllowed = false
            // Filter-only sorter (sorting stays off — order IS the alignment) for "변경만 보기".
            table.rowSorter = TableRowSorter(table.model as AbstractTableModel).apply {
                for (c in 0 until table.model.columnCount) setSortable(c, false)
            }
            table.columnModel.getColumn(0).preferredWidth = 56
            for (c in 1 until table.columnCount) table.columnModel.getColumn(c).preferredWidth = 96
            return table
        }

        /** Keep the two sides' row selection in lockstep (model coords survive the filter). */
        private fun mirrorSelection(from: JTable, to: JTable) {
            from.selectionModel.addListSelectionListener {
                if (syncing || it.valueIsAdjusting) return@addListSelectionListener
                val viewRow = from.selectedRow.takeIf { r -> r >= 0 } ?: return@addListSelectionListener
                val modelRow = from.convertRowIndexToModel(viewRow)
                val target = runCatching { to.convertRowIndexToView(modelRow) }.getOrDefault(-1)
                if (target >= 0 && to.selectedRow != target) {
                    syncing = true
                    try {
                        to.setRowSelectionInterval(target, target)
                    } finally {
                        syncing = false
                    }
                }
            }
        }

        fun onShown() {
            if (shownOnce) return
            shownOnce = true
            if (navigator.canGoNext()) navigator.goNext() // land on the FIRST change, not row 0
        }

        fun applyChangedOnly(on: Boolean) {
            val filter = if (!on) null else object : RowFilter<AbstractTableModel, Int>() {
                override fun include(entry: Entry<out AbstractTableModel, out Int>): Boolean =
                    sheet.rows[entry.identifier].kind != XlsxDiffModel.Kind.EQUAL
            }
            listOf(leftTable, rightTable).forEach {
                @Suppress("UNCHECKED_CAST")
                (it.rowSorter as TableRowSorter<AbstractTableModel>).rowFilter = filter
            }
        }

        /** Change blocks (starts of contiguous non-EQUAL runs, MODEL rows) — F7/⇧F7 and `]c`/`[c`. */
        inner class Navigator : PrevNextDifferenceIterable {
            private val blocks: IntArray = run {
                val starts = ArrayList<Int>()
                var inBlock = false
                sheet.rows.forEachIndexed { i, row ->
                    val changed = row.kind != XlsxDiffModel.Kind.EQUAL
                    if (changed && !inBlock) starts.add(i)
                    inBlock = changed
                }
                starts.toIntArray()
            }

            private fun currentModelRow(): Int {
                val v = leftTable.selectedRow
                return if (v < 0) -1 else leftTable.convertRowIndexToModel(v)
            }

            override fun canGoNext(): Boolean = blocks.any { it > currentModelRow() }
            override fun canGoPrev(): Boolean = blocks.any { it < currentModelRow() }
            override fun goNext() { blocks.firstOrNull { it > currentModelRow() }?.let(::jumpTo) }
            override fun goPrev() { blocks.lastOrNull { it < currentModelRow() }?.let(::jumpTo) }

            private fun jumpTo(modelRow: Int) {
                val viewRow = runCatching { leftTable.convertRowIndexToView(modelRow) }.getOrDefault(-1)
                if (viewRow < 0) return
                val col = leftTable.selectedColumn.takeIf { it >= 1 } ?: 1
                leftTable.changeSelection(viewRow, col, false, false) // mirrors to the right table
                centerRow(viewRow)
            }

            private fun centerRow(viewRow: Int) {
                val viewport = leftTable.parent as? JViewport ?: return
                val rect = leftTable.getCellRect(viewRow, 0, true)
                val y = (rect.y - (viewport.height - rect.height) / 2)
                    .coerceIn(0, maxOf(0, leftTable.height - viewport.height))
                viewport.viewPosition = Point(viewport.viewPosition.x, y)
            }
        }
    }
}

/** One side of the aligned diff: column 0 = that side's ORIGINAL row number (blank on placeholder rows). */
private class DiffSideModel(private val sheet: XlsxDiffModel.SheetDiff, private val leftSide: Boolean) : AbstractTableModel() {
    override fun getRowCount(): Int = sheet.rows.size
    override fun getColumnCount(): Int = sheet.columnCount + 1
    override fun getColumnName(column: Int): String =
        if (column == 0) "" else XlsxDiffModel.columnLetter(column - 1)

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = sheet.rows[rowIndex]
        if (columnIndex == 0) {
            val idx = if (leftSide) row.leftIndex else row.rightIndex
            return idx?.plus(1)?.toString() ?: ""
        }
        val cells = (if (leftSide) row.left else row.right) ?: return ""
        return cells.getOrNull(columnIndex - 1) ?: ""
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
}

private class DiffCellRenderer(
    private val sheet: XlsxDiffModel.SheetDiff,
    private val leftSide: Boolean,
) : DefaultTableCellRenderer() {

    private val added = JBColor(Color(0xE6FFEC), Color(0x1B3620))
    private val removed = JBColor(Color(0xFFEBE9), Color(0x442222))
    private val modifiedRow = JBColor(Color(0xF2F7FF), Color(0x1E2833))
    private val changedCell = JBColor(Color(0xC9E1FF), Color(0x2E4B6E))
    private val placeholder = JBColor(Color(0xF2F2F2), Color(0x313335))
    private val numberFg = JBColor(Color(0x999999), Color(0x777777))

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, rowIndex: Int, columnIndex: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, false, rowIndex, columnIndex)
        val row = sheet.rows[table.convertRowIndexToModel(rowIndex)]
        val cells = if (leftSide) row.left else row.right
        font = font.deriveFont(Font.PLAIN)
        horizontalAlignment = if (columnIndex == 0) RIGHT else LEFT
        foreground = if (columnIndex == 0) numberFg else table.foreground

        background = when {
            isSelected -> table.selectionBackground
            cells == null -> placeholder // this side lacks the row — grey slot keeps alignment visible
            row.kind == XlsxDiffModel.Kind.INSERTED -> added
            row.kind == XlsxDiffModel.Kind.DELETED -> removed
            row.kind == XlsxDiffModel.Kind.MODIFIED -> {
                if (columnIndex > 0 && (columnIndex - 1) in row.changedCols) {
                    font = font.deriveFont(Font.BOLD)
                    changedCell
                } else modifiedRow
            }
            else -> table.background
        }
        if (isSelected) foreground = table.selectionForeground
        return c
    }

    private operator fun IntArray.contains(v: Int): Boolean {
        for (x in this) if (x == v) return true
        return false
    }
}
