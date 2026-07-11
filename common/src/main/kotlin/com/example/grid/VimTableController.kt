package com.example.grid

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.JViewport
import javax.swing.KeyStroke

/**
 * Shared machinery for the always-on modal vim **navigation** controllers over a read-only
 * table (`VimGridController` in xlsx-editor, `VimLogController` in log-viewer): key
 * installation on the WHEN_FOCUSED InputMap, the count / pending / mark-name state machine, and
 * the viewport scroll family (`Ctrl+D/U/E/Y`, `zz`/`zt`/`zb`, `H`/`M`/`L`, `gg`/`G`).
 *
 * Subclasses own the key dispatch ([pressChar]) and everything domain-specific — cell vs char
 * cursor, word motions, visual modes, marks storage (store MODEL coordinates so marks survive
 * filtering/streaming).
 *
 * The base only needs plain-Swing [JTable] APIs (the plugins pass a `JBTable`), so the state
 * machine is unit-testable without an IDE runtime.
 */
abstract class VimTableController(protected val table: JTable) {

    protected var enabled = false
        private set

    private val count = StringBuilder()

    /** First key of a two-key sequence awaiting its second (`g`, `z`, … — subclass-defined). */
    protected var pending: Char? = null

    /** `'m'` = the next key names a mark to set, `` '`' `` = a mark to jump to. */
    protected var pendingMark: Char? = null

    private var shortcutsRegistered = false

    /** Every plain character bound on the table's WHEN_FOCUSED InputMap (unused letters are no-ops). */
    protected abstract val keyChars: String

    /** One key press. The subclass owns ordering + dispatch; use [handleMarkKey], [bufferCountDigit], [countOr1]. */
    protected abstract fun pressChar(ch: Char)

    /** Escape pressed (cancel visual/pending state, release focus, …). */
    protected abstract fun handleEscape()

    /** `m{a-z}` — remember the current position under [ch]. */
    protected abstract fun setMark(ch: Char)

    /** `` `{a-z} `` — jump to the mark named [ch] (no-op if unset or filtered out). */
    protected abstract fun jumpToMark(ch: Char)

    /** Move the cursor [delta] rows (`Ctrl+D`/`Ctrl+U`). */
    protected abstract fun moveRows(delta: Int)

    /** Put the cursor on view [row], keeping the subclass's column state. */
    protected abstract fun selectRow(row: Int)

    /** The grid gained focus while vim is enabled. */
    protected open fun focusGained() {}

    init {
        table.addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                if (enabled) this@VimTableController.focusGained()
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
            im.put(KeyStroke.getKeyStroke(ch), "vim.$ch")
            am.put("vim.$ch", action { pressChar(ch) })
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "vim.esc")
        am.put("vim.esc", action { handleEscape() })

        if (!shortcutsRegistered) {
            shortcutsRegistered = true
            // Chords via the IDE action system so they beat IDE-global bindings on the grid.
            registerChord("ctrl D") { halfPage(1) }
            registerChord("ctrl U") { halfPage(-1) }
            registerChord("ctrl E") { scrollLines(1) }
            registerChord("ctrl Y") { scrollLines(-1) }
        }
    }

    protected fun registerChord(shortcut: String, run: () -> Unit) {
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

    // ---- count / mark-name state machine (helpers for the subclass dispatch) ----

    /** The key right after `m` / `` ` `` names the mark; returns true if [ch] was consumed. */
    protected fun handleMarkKey(ch: Char): Boolean {
        val mode = pendingMark ?: return false
        pendingMark = null
        if (ch.isLetter()) {
            if (mode == 'm') setMark(ch) else jumpToMark(ch)
        }
        reset()
        return true
    }

    /** Buffer a count digit (`5j`); returns true if [ch] was consumed. */
    protected fun bufferCountDigit(ch: Char): Boolean {
        if (ch.isDigit() && !(ch == '0' && count.isEmpty())) {
            count.append(ch)
            return true
        }
        return false
    }

    /** The buffered count, or 1 when none was typed. */
    protected fun countOr1(): Int = count.toString().toIntOrNull() ?: 1

    /** Whether a count is buffered (vim `G` vs `5G`). */
    protected fun hasCount(): Boolean = count.isNotEmpty()

    protected fun reset() {
        count.setLength(0)
        pending = null
        pendingMark = null
    }

    // ---- viewport scroll family ----

    protected fun viewport(): JViewport? = table.parent as? JViewport

    protected fun gotoRow(row: Int) {
        if (table.rowCount == 0) return
        selectRow(row.coerceIn(0, table.rowCount - 1))
    }

    /** Ctrl+D / Ctrl+U: move the cursor half a viewport of rows. */
    protected fun halfPage(direction: Int) {
        if (table.rowCount == 0) return
        val rowHeight = maxOf(1, table.rowHeight)
        val visibleRows = maxOf(1, table.visibleRect.height / rowHeight)
        moveRows(direction * maxOf(1, visibleRows / 2))
    }

    /** Ctrl+E / Ctrl+Y: scroll the viewport one row without moving the cursor. */
    protected fun scrollLines(n: Int) {
        val viewport = viewport() ?: return
        val rowHeight = maxOf(1, table.rowHeight)
        val maxY = maxOf(0, table.height - viewport.height)
        val pos = viewport.viewPosition
        pos.y = (pos.y + n * rowHeight).coerceIn(0, maxY)
        viewport.viewPosition = pos
    }

    protected enum class ScrollTo { TOP, CENTER, BOTTOM }

    /** vim `zz`/`zt`/`zb`: scroll so the cursor row sits at the center / top / bottom of the viewport. */
    protected fun scrollCursorRow(where: ScrollTo) {
        val viewport = viewport() ?: return
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

    protected enum class ScreenPos { TOP, MIDDLE, BOTTOM }

    /** `H` / `M` / `L`: move the cursor to the top / middle / bottom row of the visible viewport. */
    protected fun screenRow(pos: ScreenPos) {
        val viewport = viewport() ?: return
        if (table.rowCount == 0) return
        val rowHeight = maxOf(1, table.rowHeight)
        val first = (viewport.viewPosition.y / rowHeight).coerceIn(0, table.rowCount - 1)
        val visibleRows = maxOf(1, viewport.height / rowHeight)
        val last = (first + visibleRows - 1).coerceAtMost(table.rowCount - 1)
        selectRow(when (pos) {
            ScreenPos.TOP -> first
            ScreenPos.BOTTOM -> last
            ScreenPos.MIDDLE -> (first + last) / 2
        })
    }
}
