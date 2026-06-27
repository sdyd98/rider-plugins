package com.example.xlsx

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.table.JBTable
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.KeyStroke

/**
 * Always-on modal (vim-like) keybindings for a spreadsheet [JBTable].
 *
 * Normal: `hjkl` move · `0`/`$` · `gg`/`G` · counts (`5j`) · `Ctrl+D`/`Ctrl+U` half-page ·
 * `Ctrl+E`/`Ctrl+Y` scroll one line · `i`/`a`/Enter edit · `x` clear cell · `dd` delete row ·
 * `o`/`O` add row · `yy` yank row · `p`/`P` paste row below/above · `u` undo · `.` repeat last
 * change · `gt`/`gT` switch sheet · `/` filter · `V` visual-line mode (then `j`/`k` extend, `d`
 * delete, `y` yank, Esc cancel).
 */
class VimGridController(
    private val table: JBTable,
    private val onFocusFilter: () -> Unit,
    private val onNextSheet: () -> Unit = {},
    private val onPrevSheet: () -> Unit = {},
) {
    private var enabled = false
    private val count = StringBuilder()
    private var pending: Char? = null
    private var yanked: List<Array<String?>> = emptyList()
    private var lastChange: (() -> Unit)? = null

    private var visualMode = false
    private var visualAnchor = 0
    private var visualCurrent = 0

    private val keyChars = "hjkl0123456789\$gGiaxdypPoOtTuV./"
    private var shortcutsRegistered = false

    private val sheetModel: SheetTableModel?
        get() = table.model as? SheetTableModel

    init {
        table.addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                if (enabled) {
                    if (!visualMode) reset()
                    if (table.selectedRow < 0 && table.rowCount > 0) table.changeSelection(0, 0, false, false)
                }
            }
            override fun focusLost(e: FocusEvent) {}
        })
    }

    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        table.putClientProperty("JTable.autoStartsEdit", !on)
        if (on) installBindings() else removeBindings()
        reset()
    }

    private fun installBindings() {
        val im = table.getInputMap(JComponent.WHEN_FOCUSED)
        val am = table.actionMap
        for (ch in keyChars) {
            im.put(KeyStroke.getKeyStroke(ch), "vim.char.$ch")
            am.put("vim.char.$ch", action { pressChar(ch) })
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "vim.esc")
        am.put("vim.esc", action { escapePressed() })
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "vim.enter")
        am.put("vim.enter", action { startEdit() })
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "vim.edit")
        am.put("vim.edit", action { startEdit() })

        if (!shortcutsRegistered) {
            shortcutsRegistered = true
            // Chords via the IDE action system so they beat IDE-global bindings on the grid.
            registerChord("ctrl D") { halfPage(1) }
            registerChord("ctrl U") { halfPage(-1) }
            registerChord("ctrl E") { scrollLines(1) }
            registerChord("ctrl Y") { scrollLines(-1) }
        }
    }

    private fun registerChord(shortcut: String, run: () -> Unit) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (enabled) run()
            }
        }.registerCustomShortcutSet(CustomShortcutSet.fromString(shortcut), table)
    }

    private fun removeBindings() {
        val im = table.getInputMap(JComponent.WHEN_FOCUSED)
        for (ch in keyChars) im.remove(KeyStroke.getKeyStroke(ch))
        im.remove(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
        im.remove(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
        im.remove(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0))
    }

    private fun action(run: () -> Unit) = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) {
            if (enabled) run()
        }
    }

    private fun pressChar(ch: Char) {
        if (visualMode) {
            handleVisual(ch)
            return
        }
        if (ch.isDigit() && !(ch == '0' && count.isEmpty())) {
            count.append(ch)
            return
        }
        val n = count.toString().toIntOrNull() ?: 1
        when (ch) {
            'h' -> { move(0, -n); reset() }
            'l' -> { move(0, n); reset() }
            'j' -> { move(n, 0); reset() }
            'k' -> { move(-n, 0); reset() }
            '0' -> { moveToColumn(0); reset() }
            '$' -> { moveToColumn(lastDataColumn()); reset() }
            'G' -> { gotoRow(if (count.isNotEmpty()) n - 1 else table.rowCount - 1); reset() }
            'g' -> if (pending == 'g') { gotoRow(0); reset() } else { pending = 'g' }
            't' -> { val g = pending == 'g'; reset(); if (g) onNextSheet() }
            'T' -> { val g = pending == 'g'; reset(); if (g) onPrevSheet() }
            'i', 'a' -> { startEdit(); reset() }
            'x' -> { clearCells(n); lastChange = { clearCells(n) }; reset() }
            'd' -> if (pending == 'd') { deleteRows(n); lastChange = { deleteRows(n) }; reset() } else { pending = 'd' }
            'o' -> { openRow(below = true, n = n); reset() }
            'O' -> { openRow(below = false, n = n); reset() }
            'y' -> if (pending == 'y') { yankRow(); reset() } else { pending = 'y' }
            'p' -> { pasteRows(below = true); lastChange = { pasteRows(below = true) }; reset() }
            'P' -> { pasteRows(below = false); lastChange = { pasteRows(below = false) }; reset() }
            'u' -> { sheetModel?.undo(); reset() }
            '.' -> { val times = n; reset(); repeat(times) { lastChange?.invoke() } }
            'V' -> { reset(); enterVisual() }
            '/' -> { onFocusFilter(); reset() }
            else -> reset()
        }
    }

    private fun escapePressed() {
        if (visualMode) exitVisual() else reset()
    }

    private fun curRow() = table.selectedRow.coerceAtLeast(0)
    private fun curCol() = table.selectedColumn.coerceAtLeast(0)

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

    private fun gotoRow(row: Int) {
        if (table.rowCount == 0) return
        select(row.coerceIn(0, table.rowCount - 1), curCol())
    }

    private fun select(row: Int, col: Int) {
        table.changeSelection(row, col, false, false)
        table.scrollRectToVisible(table.getCellRect(row, col, true))
    }

    private fun halfPage(direction: Int) {
        if (table.rowCount == 0) return
        val rowHeight = maxOf(1, table.rowHeight)
        val visibleRows = maxOf(1, table.visibleRect.height / rowHeight)
        move(direction * maxOf(1, visibleRows / 2), 0)
    }

    /** Ctrl+E / Ctrl+Y: scroll the viewport one row without moving the cursor. */
    private fun scrollLines(n: Int) {
        val viewport = table.parent as? JViewport ?: return
        val rowHeight = maxOf(1, table.rowHeight)
        val maxY = maxOf(0, table.height - viewport.height)
        val pos = viewport.viewPosition
        pos.y = (pos.y + n * rowHeight).coerceIn(0, maxY)
        viewport.viewPosition = pos
    }

    private fun startEdit() {
        val r = table.selectedRow
        val c = table.selectedColumn
        if (r >= 0 && c >= 0 && table.editCellAt(r, c)) {
            table.editorComponent?.requestFocusInWindow()
        }
        reset()
    }

    private fun clearCells(n: Int) {
        val r = table.selectedRow
        if (r < 0) return
        val start = curCol()
        for (c in start until (start + n).coerceAtMost(table.columnCount)) table.setValueAt("", r, c)
    }

    private fun deleteRows(n: Int) {
        val model = sheetModel ?: return
        val viewStart = table.selectedRow
        if (viewStart < 0) return
        val modelRows = (viewStart until (viewStart + n).coerceAtMost(table.rowCount))
            .map { table.convertRowIndexToModel(it) }
            .filter { it >= 0 }
            .sortedDescending()
        for (mr in modelRows) model.deleteRow(mr)
        if (table.rowCount > 0) {
            table.changeSelection(viewStart.coerceIn(0, table.rowCount - 1), curCol(), false, false)
        }
    }

    private fun openRow(below: Boolean, n: Int) {
        val model = sheetModel ?: return
        val viewRow = table.selectedRow
        val modelRow = if (viewRow >= 0) table.convertRowIndexToModel(viewRow) else -1
        val at = if (modelRow < 0) model.rowCount else if (below) modelRow + 1 else modelRow
        repeat(n.coerceAtLeast(1)) { model.insertRow(at) }
        val viewAt = table.convertRowIndexToView(at)
        if (viewAt >= 0) {
            table.changeSelection(viewAt, curCol(), false, false)
            startEdit()
        }
    }

    private fun yankRow() {
        val r = table.selectedRow
        if (r < 0) return
        yanked = listOf(rowValues(r))
    }

    private fun rowValues(viewRow: Int): Array<String?> =
        Array(table.columnCount) { table.getValueAt(viewRow, it)?.toString() }

    /** Insert the yanked row(s) as new rows below (`p`) or above (`P`) the current row. */
    private fun pasteRows(below: Boolean) {
        if (yanked.isEmpty()) return
        val model = sheetModel ?: return
        val viewRow = table.selectedRow
        val modelRow = if (viewRow >= 0) table.convertRowIndexToModel(viewRow) else model.rowCount - 1
        val at = if (below) modelRow + 1 else modelRow.coerceAtLeast(0)
        for ((i, rowData) in yanked.withIndex()) model.insertRowWithData(at + i, rowData)
        val viewAt = table.convertRowIndexToView(at)
        if (viewAt >= 0) {
            table.changeSelection(viewAt, curCol(), false, false)
            table.scrollRectToVisible(table.getCellRect(viewAt, curCol(), true))
        }
    }

    // ---- Visual-line mode ----

    private fun enterVisual() {
        if (table.rowCount == 0) return
        visualMode = true
        visualAnchor = table.selectedRow.coerceAtLeast(0)
        visualCurrent = visualAnchor
        selectRowRange(visualAnchor, visualCurrent)
    }

    private fun exitVisual() {
        visualMode = false
        val row = visualCurrent.coerceIn(0, maxOf(0, table.rowCount - 1))
        if (table.rowCount > 0) table.changeSelection(row, curCol(), false, false)
        reset()
    }

    private fun handleVisual(ch: Char) {
        when (ch) {
            'j' -> extendVisual(1)
            'k' -> extendVisual(-1)
            'd', 'x' -> { deleteVisualRows(); exitVisual() } // vim: x deletes the selection, like d
            'y' -> { yankVisualRows(); exitVisual() }
            else -> exitVisual()
        }
    }

    private fun extendVisual(dir: Int) {
        if (table.rowCount == 0) return
        visualCurrent = (visualCurrent + dir).coerceIn(0, table.rowCount - 1)
        selectRowRange(visualAnchor, visualCurrent)
        table.scrollRectToVisible(table.getCellRect(visualCurrent, 0, true))
    }

    private fun selectRowRange(a: Int, b: Int) {
        if (table.rowCount == 0 || table.columnCount == 0) return
        val lo = minOf(a, b).coerceIn(0, table.rowCount - 1)
        val hi = maxOf(a, b).coerceIn(0, table.rowCount - 1)
        table.setRowSelectionInterval(lo, hi)
        table.setColumnSelectionInterval(0, table.columnCount - 1)
    }

    private fun deleteVisualRows() {
        val model = sheetModel ?: return
        val lo = minOf(visualAnchor, visualCurrent)
        val hi = maxOf(visualAnchor, visualCurrent)
        val modelRows = (lo..hi).map { table.convertRowIndexToModel(it) }.filter { it >= 0 }.sortedDescending()
        for (mr in modelRows) model.deleteRow(mr)
        lastChange = null // a range delete isn't meaningfully repeatable at a point
    }

    private fun yankVisualRows() {
        val lo = minOf(visualAnchor, visualCurrent)
        val hi = maxOf(visualAnchor, visualCurrent)
        yanked = (lo..hi).map { rowValues(it) }
    }

    private fun reset() {
        count.setLength(0)
        pending = null
    }
}
