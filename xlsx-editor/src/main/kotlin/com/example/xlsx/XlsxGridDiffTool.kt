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
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

private val LOG = logger<XlsxGridDiffTool>()

/**
 * GRID diff for two spreadsheet revisions — the same comparison the TSV text projection feeds to the
 * platform's text diff, but rendered as two side-by-side grids per sheet with row/cell-level
 * highlighting (added/removed rows, changed cells). Appears in the diff window's viewer switcher next
 * to the text diff; [XlsxDiffModel] does the loading/alignment (row alignment reuses the platform
 * ComparisonManager over the same row keys as the text projection, so both views always agree).
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

    private val root = JPanel(BorderLayout())
    private val status = JBLabel("")
    @Volatile private var disposed = false

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = root

    override fun init(): FrameDiffTool.ToolbarComponents {
        root.add(JBLabel("워크북 비교 중…", SwingConstants.CENTER), BorderLayout.CENTER)
        ApplicationManager.getApplication().executeOnPooledThread { loadAndBuild() }
        return FrameDiffTool.ToolbarComponents().apply { statusPanel = status }
    }

    override fun dispose() {
        disposed = true
    }

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
                .map { intArrayOf(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
                // compareLines works on split lines: an EMPTY side still counts 1 empty line — clamp
                // those phantom ranges so an empty sheet vs N rows reads as N inserts, not 1 modify.
                .map { r ->
                    if (left.size == 1 && left[0].isEmpty() && r[0] == 0) intArrayOf(0, 0, r[2], r[3])
                    else if (right.size == 1 && right[0].isEmpty() && r[2] == 0) intArrayOf(r[0], r[1], 0, 0)
                    else r
                }
        } catch (e: DiffTooBigException) {
            // One whole-sheet changed range: everything pairs as modified/insert/delete — still usable.
            listOf(intArrayOf(0, left.size, 0, right.size))
        }
    }

    private fun buildTabs(sheets: List<XlsxDiffModel.SheetDiff>): JComponent {
        val changedSheets = sheets.count { it.changed }
        val totals = sheets.sumOf { it.modified } to (sheets.sumOf { it.inserted } to sheets.sumOf { it.deleted })
        status.text = "시트 ${sheets.size}개 중 ${changedSheets}개 변경 · ~${totals.first} · +${totals.second.first} · −${totals.second.second}"

        if (sheets.isEmpty()) return JBLabel("시트가 없습니다", SwingConstants.CENTER)
        val tabs = JTabbedPane()
        sheets.forEach { sheet ->
            val title = (if (sheet.changed) "✱ " else "") + sheet.name +
                (sheet.onlySide?.let { " ($it)" } ?: "")
            tabs.addTab(title, buildSheetPane(sheet))
        }
        sheets.indexOfFirst { it.changed }.takeIf { it >= 0 }?.let { tabs.selectedIndex = it }
        return tabs
    }

    private fun buildSheetPane(sheet: XlsxDiffModel.SheetDiff): JComponent {
        if (sheet.tooBig) {
            return JBLabel("시트가 너무 큽니다 (${XlsxDiffModel.MAX_DIFF_ROWS}행 초과) — 텍스트 디프를 사용하세요", SwingConstants.CENTER)
        }
        val leftTable = diffTable(sheet, leftSide = true)
        val rightTable = diffTable(sheet, leftSide = false)

        val leftScroll = JBScrollPane(leftTable)
        val rightScroll = JBScrollPane(rightTable)
        // One shared vertical model = perfect row alignment while scrolling; mirror horizontal too.
        rightScroll.verticalScrollBar.model = leftScroll.verticalScrollBar.model
        rightScroll.horizontalScrollBar.model = leftScroll.horizontalScrollBar.model
        leftScroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER

        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leftScroll, rightScroll).apply {
            resizeWeight = 0.5
            border = null
        }
    }

    private fun diffTable(sheet: XlsxDiffModel.SheetDiff, leftSide: Boolean): JTable {
        val table = JBTable(DiffSideModel(sheet, leftSide))
        table.setDefaultRenderer(Any::class.java, DiffCellRenderer(sheet, leftSide))
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.setShowGrid(true)
        table.gridColor = JBColor(Color(0xE3E3E3), Color(0x3C3F41))
        table.columnModel.getColumn(0).preferredWidth = 56
        for (c in 1 until table.columnCount) table.columnModel.getColumn(c).preferredWidth = 96
        table.tableHeader.reorderingAllowed = false
        return table
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
        val row = sheet.rows[rowIndex]
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
