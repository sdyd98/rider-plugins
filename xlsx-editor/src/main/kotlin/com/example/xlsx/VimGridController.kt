package com.example.xlsx

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.table.JBTable
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.KeyStroke

/**
 * Always-on modal (vim-like) **navigation** for a read-only spreadsheet [JBTable].
 *
 * Normal: `hjkl` move · `0`/`$` (last data cell) · `gg`/`G` · counts (`5j`) · `Ctrl+D`/`Ctrl+U`
 * half-page · `Ctrl+E`/`Ctrl+Y` scroll one line · `zz`/`zt`/`zb` cursor row to center/top/bottom ·
 * `H`/`M`/`L` top/middle/bottom of screen · `0`/`^`/`$` row start / first / last data cell ·
 * `w`/`e`/`b` next-word-start / word-end / prev-word (a word = a run of non-empty cells) ·
 * `*`/`#` next/prev row with the same value in this column, `n`/`N` repeat · `{`/`}` prev/next blank row ·
 * `m{a-z}` set mark / `` `{a-z} `` jump to mark · `gt`/`gT` switch sheet · `/` filter ·
 * `V` visual-line select (then `j`/`k` extend, Esc cancel — use Ctrl+C to copy) · `?` help.
 * The grid is a viewer, so there are no editing commands.
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

    private var visualMode = false
    private var visualAnchor = 0
    private var visualCurrent = 0

    // a-z are all bound (read-only grid, so vim owns them) so a mark name after `m`/`` ` `` is captured;
    // plus the navigation symbols/uppercase. Unused letters are harmless no-ops.
    private val keyChars = "abcdefghijklmnopqrstuvwxyzGHLMNTV0123456789\$^*#{}`/?"
    private var shortcutsRegistered = false

    private var pendingMark: Char? = null // 'm' = waiting to set a mark, '`' = waiting to jump
    private val marks = HashMap<Char, Pair<Int, Int>>() // mark letter -> (model row, model col)
    private var searchValue: String? = null // last `*`/`#` value (repeated by n / N)
    private var searchCol = -1

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
        if (on) installBindings()
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
        // The key right after `m` / `` ` `` is the mark name (set / jump).
        if (pendingMark != null) {
            val mode = pendingMark
            pendingMark = null
            if (ch.isLetter()) {
                if (mode == 'm') setMark(ch) else jumpToMark(ch)
            }
            reset()
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
            '^' -> { moveToColumn(firstDataColumn()); reset() }
            '$' -> { moveToColumn(lastDataColumn()); reset() }
            'w' -> { wordForward(); reset() }
            'e' -> { wordEnd(); reset() }
            'G' -> { gotoRow(if (count.isNotEmpty()) n - 1 else table.rowCount - 1); reset() }
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
            'V' -> { reset(); enterVisual() }
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

    /** `n` / `N`: jump to the next/previous row matching the last `*`/`#` value in its column (wraps). */
    private fun repeatSearch(dir: Int) {
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

    private enum class ScreenPos { TOP, MIDDLE, BOTTOM }

    /** `H` / `M` / `L`: move the cursor to the top / middle / bottom row of the visible viewport. */
    private fun screenRow(pos: ScreenPos) {
        val viewport = table.parent as? JViewport ?: return
        if (table.rowCount == 0) return
        val rowH = maxOf(1, table.rowHeight)
        val first = (viewport.viewPosition.y / rowH).coerceIn(0, table.rowCount - 1)
        val visibleRows = maxOf(1, viewport.height / rowH)
        val last = (first + visibleRows - 1).coerceAtMost(table.rowCount - 1)
        val target = when (pos) {
            ScreenPos.TOP -> first
            ScreenPos.BOTTOM -> last
            ScreenPos.MIDDLE -> (first + last) / 2
        }
        select(target, curCol())
    }

    /** `m{a-z}`: remember the current cell (by MODEL coordinates so it survives filtering/streaming). */
    private fun setMark(ch: Char) {
        val r = table.selectedRow
        val c = table.selectedColumn
        if (r < 0 || c < 0) return
        marks[ch] = table.convertRowIndexToModel(r) to table.convertColumnIndexToModel(c)
    }

    /** `` `{a-z} ``: jump to a mark (no-op if it was never set or its row is currently filtered out). */
    private fun jumpToMark(ch: Char) {
        val (mr, mc) = marks[ch] ?: return
        val vr = runCatching { table.convertRowIndexToView(mr) }.getOrDefault(-1)
        val vc = runCatching { table.convertColumnIndexToView(mc) }.getOrDefault(-1)
        if (vr in 0 until table.rowCount && vc in 0 until table.columnCount) select(vr, vc)
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

    private enum class ScrollTo { TOP, CENTER, BOTTOM }

    /** vim `zz`/`zt`/`zb`: scroll so the cursor row sits at the center / top / bottom of the viewport. */
    private fun scrollCursorRow(where: ScrollTo) {
        val viewport = table.parent as? JViewport ?: return
        val row = table.selectedRow
        if (row < 0) return
        val rect = table.getCellRect(row, 0, true)
        val viewH = viewport.height
        val y = when (where) {
            ScrollTo.TOP -> rect.y
            ScrollTo.BOTTOM -> rect.y + rect.height - viewH
            ScrollTo.CENTER -> rect.y - (viewH - rect.height) / 2
        }
        val maxY = maxOf(0, table.height - viewH)
        viewport.viewPosition = Point(viewport.viewPosition.x, y.coerceIn(0, maxY))
    }

    // ---- Visual-line mode (selection only — copy the selection with Ctrl+C) ----

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

    private fun reset() {
        count.setLength(0)
        pending = null
        pendingMark = null
    }
}
