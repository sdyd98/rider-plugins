package com.example.xlsx

import com.example.grid.VimTableController
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.table.JBTable
import java.awt.datatransfer.StringSelection

/**
 * Always-on modal (vim-like) **navigation** for a read-only spreadsheet [JBTable].
 *
 * Normal: `hjkl` move · `0`/`$` (last data cell) · `gg`/`G` · counts (`5j`) · `Ctrl+D`/`Ctrl+U`
 * half-page · `Ctrl+E`/`Ctrl+Y` scroll one line · `zz`/`zt`/`zb` cursor row to center/top/bottom ·
 * `H`/`M`/`L` top/middle/bottom of screen · `0`/`^`/`$` row start / first / last data cell ·
 * `w`/`e`/`b` next-word-start / word-end / prev-word (a word = a run of non-empty cells) ·
 * `*`/`#` next/prev row with the same value in this column, `n`/`N` repeat · `{`/`}` prev/next blank row ·
 * `m{a-z}` set mark / `` `{a-z} `` jump to mark · `gt`/`gT` switch sheet · `/` filter ·
 * `yy` copy the current cell · `v` visual-block select cells / `V` visual-line select rows
 * (extend with `hjkl`, then `y` copies — cells joined with tabs, rows with newlines, so the
 * clipboard pastes straight into Excel; Esc cancels, Ctrl+C also works on any selection) · `?` help.
 * The grid is a viewer, so there are no editing commands.
 *
 * Key installation, count/mark state, and the scroll family live in [VimTableController] (shared
 * with log-viewer's VimLogController); this class owns the 2D cell cursor and dispatch.
 */
class VimGridController(
    table: JBTable,
    private val onFocusFilter: () -> Unit,
    private val onNextSheet: () -> Unit = {},
    private val onPrevSheet: () -> Unit = {},
    /** Visual mode entered/left — the panel shows "-- VISUAL --" in the status bar. */
    private val onVisualModeChanged: () -> Unit = {},
    /** `n`/`N` first offer the jump to the panel's highlight-search (returns true when it consumed
     *  the key — an active search pattern outranks the last `*`/`#` value, like vim's `/` search). */
    private val onPatternSearch: (dir: Int) -> Boolean = { false },
) : VimTableController(table) {

    private enum class VisualMode { NONE, LINE, CELL }

    private var visual = VisualMode.NONE

    /** Status label for the active visual mode ("VISUAL" / "V-LINE"), or null in normal mode. */
    fun visualModeLabel(): String? = when (visual) {
        VisualMode.CELL -> "VISUAL"
        VisualMode.LINE -> "V-LINE"
        VisualMode.NONE -> null
    }
    private var vAnchorRow = 0
    private var vAnchorCol = 0
    private var vRow = 0 // the visual cursor (view coordinates, like the table selection)
    private var vCol = 0

    // a-z are all bound (read-only grid, so vim owns them) so a mark name after `m`/`` ` `` is captured;
    // plus the navigation symbols/uppercase. Unused letters are harmless no-ops.
    override val keyChars = "abcdefghijklmnopqrstuvwxyzGHLMNTV0123456789\$^*#{}`/?"

    private val marks = HashMap<Char, Pair<Int, Int>>() // mark letter -> (model row, model col)
    private var searchValue: String? = null // last `*`/`#` value (repeated by n / N)
    private var searchCol = -1

    override fun focusGained() {
        if (visual == VisualMode.NONE) reset()
        if (table.selectedRow < 0 && table.rowCount > 0) table.changeSelection(0, 0, false, false)
    }

    override fun pressChar(ch: Char) {
        if (visual != VisualMode.NONE) {
            handleVisual(ch)
            return
        }
        if (handleMarkKey(ch)) return
        if (bufferCountDigit(ch)) return
        val n = countOr1()
        when (ch) {
            'h' -> { move(0, -n); reset() }
            'l' -> { move(0, n); reset() }
            'j' -> { move(n, 0); reset() }
            'k' -> { move(-n, 0); reset() }
            '0' -> { moveToColumn(0); reset() }
            '^' -> { moveToColumn(firstDataColumn()); reset() }
            '$' -> { moveToColumn(lastDataColumn()); reset() }
            'w' -> { wordForward(); reset() }
            'e' -> { wordEnd(); reset() }
            'G' -> { gotoRow(if (hasCount()) n - 1 else table.rowCount - 1); reset() }
            'g' -> if (pending == 'g') { gotoRow(0); reset() } else { pending = 'g' }
            'z' -> if (pending == 'z') { scrollCursorRow(ScrollTo.CENTER); reset() } else { pending = 'z' }
            't' -> { val p = pending; reset(); when (p) { 'g' -> onNextSheet(); 'z' -> scrollCursorRow(ScrollTo.TOP) } }
            'T' -> { val g = pending == 'g'; reset(); if (g) onPrevSheet() }
            'b' -> { val z = pending == 'z'; reset(); if (z) scrollCursorRow(ScrollTo.BOTTOM) else wordBackward() }
            'H' -> { reset(); screenRow(ScreenPos.TOP) }
            'M' -> { reset(); screenRow(ScreenPos.MIDDLE) }
            'L' -> { reset(); screenRow(ScreenPos.BOTTOM) }
            '*' -> { startSameValueSearch(1); reset() }
            '#' -> { startSameValueSearch(-1); reset() }
            'n' -> { repeatSearch(1); reset() }
            'N' -> { repeatSearch(-1); reset() }
            '{' -> { jumpBlankRow(-1); reset() }
            '}' -> { jumpBlankRow(1); reset() }
            'm' -> { reset(); pendingMark = 'm' }
            '`' -> { reset(); pendingMark = '`' }
            'y' -> if (pending == 'y') { reset(); yankCurrentCell() } else { pending = 'y' }
            'v' -> { reset(); enterCellVisual() }
            'V' -> { reset(); enterLineVisual() }
            '/' -> { onFocusFilter(); reset() }
            '?' -> { reset(); showHelp() }
            else -> reset()
        }
    }

    /** `?` — shortcut cheat sheet rendered with **Compose (Jewel)**; see [createComposeHelpPanel]. */
    private fun showHelp() {
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(createComposeHelpPanel(), null)
            .setTitle("단축키")
            .setRequestFocus(true)
            .setMovable(true)
            .createPopup()
            .showInCenterOf(table)
    }

    override fun handleEscape() {
        if (visual != VisualMode.NONE) exitVisual() else reset()
    }

    private fun curRow() = table.selectedRow.coerceAtLeast(0)
    private fun curCol() = table.selectedColumn.coerceAtLeast(0)

    override fun moveRows(delta: Int) = move(delta, 0)

    override fun selectRow(row: Int) = select(row, curCol())

    private fun move(dr: Int, dc: Int) {
        if (table.rowCount == 0 || table.columnCount == 0) return
        val nr = (curRow() + dr).coerceIn(0, table.rowCount - 1)
        val nc = (curCol() + dc).coerceIn(0, table.columnCount - 1)
        select(nr, nc)
    }

    private fun moveToColumn(col: Int) {
        if (table.rowCount == 0) return
        select(curRow(), col.coerceIn(0, table.columnCount - 1))
    }

    /** The last column holding data in the current row (for `$`); 0 if the row is empty. */
    private fun lastDataColumn(): Int {
        val r = table.selectedRow
        if (r < 0) return 0
        for (c in table.columnCount - 1 downTo 0) {
            val v = table.getValueAt(r, c)
            if (v != null && v.toString().isNotEmpty()) return c
        }
        return 0
    }

    /** The first column holding data in the current row (for `^`); 0 if the row is empty. */
    private fun firstDataColumn(): Int {
        val r = table.selectedRow
        if (r < 0) return 0
        for (c in 0 until table.columnCount) {
            val v = table.getValueAt(r, c)
            if (v != null && v.toString().isNotEmpty()) return c
        }
        return 0
    }

    /** `*` / `#`: remember the current cell's value + column, then jump to the next/previous match. */
    private fun startSameValueSearch(dir: Int) {
        val col = curCol()
        val cur = table.getValueAt(curRow(), col)?.toString().orEmpty()
        if (cur.isEmpty()) return
        searchValue = cur
        searchCol = col
        repeatSearch(dir)
    }

    /** `n` / `N`: jump within the active highlight search if any, else repeat the last `*`/`#` value. */
    private fun repeatSearch(dir: Int) {
        if (onPatternSearch(dir)) return
        val value = searchValue ?: return
        val n = table.rowCount
        if (n == 0 || searchCol !in 0 until table.columnCount) return
        var r = curRow()
        repeat(n) {
            r = (r + dir + n) % n
            if (table.getValueAt(r, searchCol)?.toString() == value) {
                select(r, searchCol)
                return
            }
        }
    }

    private fun cellEmpty(row: Int, col: Int): Boolean {
        val v = table.getValueAt(row, col)
        return v == null || v.toString().isEmpty()
    }

    /** `w`: move to the start of the next "word" (run of non-empty cells) in the row. */
    private fun wordForward() {
        val r = curRow(); val n = table.columnCount
        if (r < 0 || n == 0) return
        var i = curCol()
        while (i < n && !cellEmpty(r, i)) i++ // skip the rest of the current word
        while (i < n && cellEmpty(r, i)) i++ // skip the gap
        moveToColumn(if (i < n) i else n - 1)
    }

    /** `e`: move to the end (last non-empty cell) of the next word in the row. */
    private fun wordEnd() {
        val r = curRow(); val n = table.columnCount
        if (r < 0 || n == 0) return
        var i = curCol() + 1
        while (i < n && cellEmpty(r, i)) i++ // skip a gap to the next word
        if (i >= n) { moveToColumn(n - 1); return }
        while (i < n - 1 && !cellEmpty(r, i + 1)) i++ // to the end of that word
        moveToColumn(i)
    }

    /** `b`: move to the start of the current/previous word in the row. */
    private fun wordBackward() {
        val r = curRow()
        if (r < 0) return
        var i = curCol() - 1
        while (i >= 0 && cellEmpty(r, i)) i-- // skip a gap leftward
        if (i < 0) { moveToColumn(0); return }
        while (i > 0 && !cellEmpty(r, i - 1)) i-- // to the start of that word
        moveToColumn(i)
    }

    /** `{` / `}`: jump to the previous/next fully-blank row (data-block boundary); clamps at the ends. */
    private fun jumpBlankRow(dir: Int) {
        val n = table.rowCount
        if (n == 0) return
        var r = curRow()
        while (true) {
            r += dir
            if (r < 0 || r >= n) { gotoRow(if (dir > 0) n - 1 else 0); return }
            if (isRowBlank(r)) { select(r, curCol()); return }
        }
    }

    private fun isRowBlank(viewRow: Int): Boolean {
        for (c in 0 until table.columnCount) {
            val v = table.getValueAt(viewRow, c)
            if (v != null && v.toString().isNotEmpty()) return false
        }
        return true
    }

    /** `m{a-z}`: remember the current cell (by MODEL coordinates so it survives filtering/streaming). */
    override fun setMark(ch: Char) {
        val r = table.selectedRow
        val c = table.selectedColumn
        if (r < 0 || c < 0) return
        marks[ch] = table.convertRowIndexToModel(r) to table.convertColumnIndexToModel(c)
    }

    /** `` `{a-z} ``: jump to a mark (no-op if it was never set or its row is currently filtered out). */
    override fun jumpToMark(ch: Char) {
        val (mr, mc) = marks[ch] ?: return
        val vr = runCatching { table.convertRowIndexToView(mr) }.getOrDefault(-1)
        val vc = runCatching { table.convertColumnIndexToView(mc) }.getOrDefault(-1)
        if (vr in 0 until table.rowCount && vc in 0 until table.columnCount) select(vr, vc)
    }

    private fun select(row: Int, col: Int) {
        table.changeSelection(row, col, false, false)
        table.scrollRectToVisible(table.getCellRect(row, col, true))
    }

    // ---- Visual modes + yank ----
    // `V` = line-wise (whole rows, j/k extend) · `v` = cell-wise (a rectangular block, hjkl extend).
    // `y` copies the selection as tab-separated cells / newline-separated rows (pastes into Excel);
    // Ctrl+C (IDE copy on the JTable selection) still works too.

    private fun enterLineVisual() {
        if (table.rowCount == 0 || table.columnCount == 0) return
        visual = VisualMode.LINE
        vAnchorRow = table.selectedRow.coerceAtLeast(0)
        vAnchorCol = 0
        vRow = vAnchorRow
        vCol = curCol()
        applyVisualSelection()
        onVisualModeChanged()
    }

    private fun enterCellVisual() {
        if (table.rowCount == 0 || table.columnCount == 0) return
        visual = VisualMode.CELL
        vAnchorRow = table.selectedRow.coerceAtLeast(0)
        vAnchorCol = table.selectedColumn.coerceAtLeast(0)
        vRow = vAnchorRow
        vCol = vAnchorCol
        applyVisualSelection()
        onVisualModeChanged()
    }

    private fun exitVisual() {
        visual = VisualMode.NONE
        if (table.rowCount > 0 && table.columnCount > 0) {
            table.changeSelection(
                vRow.coerceIn(0, table.rowCount - 1),
                vCol.coerceIn(0, table.columnCount - 1),
                false,
                false,
            )
        }
        reset()
        onVisualModeChanged()
    }

    private fun handleVisual(ch: Char) {
        when (ch) {
            'y' -> { yankVisual(); exitVisual() }
            'j' -> extendVisual(1, 0)
            'k' -> extendVisual(-1, 0)
            'h' -> if (visual == VisualMode.CELL) extendVisual(0, -1) else exitVisual()
            'l' -> if (visual == VisualMode.CELL) extendVisual(0, 1) else exitVisual()
            else -> exitVisual()
        }
    }

    private fun extendVisual(dr: Int, dc: Int) {
        if (table.rowCount == 0) return
        vRow = (vRow + dr).coerceIn(0, table.rowCount - 1)
        if (visual == VisualMode.CELL && table.columnCount > 0) vCol = (vCol + dc).coerceIn(0, table.columnCount - 1)
        applyVisualSelection()
        table.scrollRectToVisible(table.getCellRect(vRow, vCol, true))
    }

    /** Select the anchor↔cursor range: whole rows in LINE mode, the rectangle in CELL mode. */
    private fun applyVisualSelection() {
        if (table.rowCount == 0 || table.columnCount == 0) return
        table.setRowSelectionInterval(
            minOf(vAnchorRow, vRow).coerceIn(0, table.rowCount - 1),
            maxOf(vAnchorRow, vRow).coerceIn(0, table.rowCount - 1),
        )
        if (visual == VisualMode.CELL) {
            table.setColumnSelectionInterval(
                minOf(vAnchorCol, vCol).coerceIn(0, table.columnCount - 1),
                maxOf(vAnchorCol, vCol).coerceIn(0, table.columnCount - 1),
            )
        } else {
            table.setColumnSelectionInterval(0, table.columnCount - 1)
        }
        // set*SelectionInterval leaves the LEAD (the focus ring) on its second argument — the far
        // corner of the range — so entering V snapped the ring to the last column, and extending
        // left/up put it on the wrong corner. Re-adding the cursor cell (already selected) moves
        // anchor+lead back onto the vim cursor WITHOUT changing the selection.
        val leadRow = vRow.coerceIn(0, table.rowCount - 1)
        val leadCol = vCol.coerceIn(0, table.columnCount - 1)
        table.addRowSelectionInterval(leadRow, leadRow)
        table.addColumnSelectionInterval(leadCol, leadCol)
    }

    /** `yy`: copy just the current cell's text. */
    private fun yankCurrentCell() {
        val r = table.selectedRow
        val c = table.selectedColumn
        if (r < 0 || c < 0) return
        copyToClipboard(table.getValueAt(r, c)?.toString().orEmpty())
    }

    /** `y` in visual mode: copy the selected cells — tabs between cells, newlines between rows. */
    private fun yankVisual() {
        val rows = table.selectedRows
        val cols = table.selectedColumns
        if (rows.isEmpty() || cols.isEmpty()) return
        val text = rows.joinToString("\n") { r ->
            cols.joinToString("\t") { c -> table.getValueAt(r, c)?.toString().orEmpty() }
        }
        copyToClipboard(text)
    }

    private fun copyToClipboard(text: String) =
        CopyPasteManager.getInstance().setContents(StringSelection(text))
}
