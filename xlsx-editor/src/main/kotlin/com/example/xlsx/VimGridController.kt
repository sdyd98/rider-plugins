package com.example.xlsx

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
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
 * Always-on modal (vim-like) **navigation** for a read-only spreadsheet [JBTable].
 *
 * Normal: `hjkl` move · `0`/`$` (last data cell) · `gg`/`G` · counts (`5j`) · `Ctrl+D`/`Ctrl+U`
 * half-page · `Ctrl+E`/`Ctrl+Y` scroll one line · `gt`/`gT` switch sheet · `/` filter ·
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

    private val keyChars = "hjkl0123456789\$gGtTV/?"
    private var shortcutsRegistered = false

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
    }
}
