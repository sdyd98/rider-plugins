package com.example.logview

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.KeyStroke

/**
 * Always-on vim **navigation** for the read-only log grid (one tall content column).
 *
 * `hjkl` move (h/l hop columns; inside Message a char cursor, `w`/`e`/`b` word motions) · `v` char-wise
 * select (Message) / `V` line select + `y` yank, `yy` yank line · counts (`5j`) · `0`/`$` · `gg`/`G` ·
 * `zz`/`zt`/`zb` ·
 * `Ctrl+D`/`Ctrl+U` half-page · `Ctrl+E`/`Ctrl+Y` scroll one line · `zz`/`zt`/`zb` · `H`/`M`/`L` ·
 * `{`/`}` blank-line block boundary · `/` focus filter · `n`/`N` next/prev search match ·
 * `]e`/`[e` (also `w` `i` `d` `t`) next/prev line of that level · `za` toggle fold, `zR`/`zM` open/close all ·
 * `m{a-z}`/`` `{a-z} `` marks · `J` pretty-print JSON on this line · `T` jump to a timestamp · `?` help.
 */
class VimLogController(
    private val table: JBTable,
    private val model: LogTableModel,
    private val onFocusFilter: () -> Unit,
    private val onToggleFold: () -> Unit,
    private val onFoldAll: (all: Boolean) -> Unit,
    private val onJumpLevel: (dir: Int, level: LogLevel) -> Unit,
    private val onSearchRepeat: (dir: Int) -> Unit,
    private val onSearchWord: (word: String, dir: Int) -> Unit,
    private val onShowJson: () -> Unit,
    private val onGotoTime: () -> Unit,
    /** Escape step: clear an active search/filter; returns true if something was cleared (else focus is released). */
    private val onEscape: () -> Boolean = { false },
    /** `h` past the first (Time) column — e.g. move focus into the sources tree. */
    private val onExitLeft: () -> Unit = {},
    /** `gt` / `gT` — switch to the next / previous file (session tab). */
    private val onSwitchTab: (dir: Int) -> Unit = {},
) {
    private var enabled = false
    private val count = StringBuilder()
    private var pending: Char? = null // 'g' | 'z' | '[' | ']' | 'y'
    private var pendingMark: Char? = null // 'm' set / '`' jump

    private val marks = HashMap<Char, Int>() // mark letter -> MODEL row
    private var shortcutsRegistered = false

    private var curCol = COL_TIME    // the vim "cursor" column — starts at Time when entering the viewer
    private var msgCharIndex = 0     // char-cursor position inside the Message column
    private var visual = false       // line-wise visual selection in progress
    private var visualAnchor = 0     // the view row where visual selection started
    private var cursorRow = 0        // the vim cursor's view row (authoritative while in visual)
    private var charVisual = false   // character-wise visual selection inside the Message column
    private var charAnchor = 0       // anchor char index of the character-wise selection

    private val monoCharW: Int by lazy {
        table.getFontMetrics(LogFonts.MONO).charWidth('m').coerceAtLeast(JBUI.scale(7))
    }

    private val keyChars = "abcdefghijklmnopqrstuvwxyzGHLMNTJRV0123456789\$^{}[]`/?*#"

    init {
        table.addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                if (!enabled) return
                curCol = firstVisibleCol() // always enter the viewer at the first column (Time)
                if (table.selectedRow < 0 && table.rowCount > 0) select(0) else refreshLead()
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
            im.put(KeyStroke.getKeyStroke(ch), "vimlog.$ch")
            am.put("vimlog.$ch", action { pressChar(ch) })
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "vimlog.esc")
        am.put("vimlog.esc", action { handleEscape() })

        if (!shortcutsRegistered) {
            shortcutsRegistered = true
            registerChord("ctrl D") { halfPage(1) }
            registerChord("ctrl U") { halfPage(-1) }
            registerChord("ctrl E") { scrollLines(1) }
            registerChord("ctrl Y") { scrollLines(-1) }
        }
    }

    private fun registerChord(shortcut: String, run: () -> Unit) {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) { if (enabled) run() }
        }.registerCustomShortcutSet(CustomShortcutSet.fromString(shortcut), table)
    }

    private fun action(run: () -> Unit) = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) { if (enabled) run() }
    }

    private fun pressChar(ch: Char) {
        if (pendingMark != null) {
            val mode = pendingMark; pendingMark = null
            if (ch.isLetter()) { if (mode == 'm') setMark(ch) else jumpToMark(ch) }
            reset(); return
        }
        // `]`/`[` then a level key jumps to the next/prev line of that level.
        if (pending == ']' || pending == '[') {
            val dir = if (pending == ']') 1 else -1
            levelForKey(ch)?.let { onJumpLevel(dir, it); reassertColumn() }
            reset(); return
        }
        if (pending == 'y') { val yy = ch == 'y'; reset(); if (yy) yankLines(curRow(), curRow()); return }
        if (ch.isDigit() && !(ch == '0' && count.isEmpty())) { count.append(ch); return }
        val n = count.toString().toIntOrNull() ?: 1
        when (ch) {
            'j' -> { move(n); reset() }
            'k' -> { move(-n); reset() }
            'h' -> { moveHoriz(-1, n); reset() }
            'l' -> { moveHoriz(1, n); reset() }
            '0' -> { if (curCol == COL_MSG) setCharIndex(0) else { curCol = firstVisibleCol(); scrollToX(0); refreshLead() }; reset() }
            '$' -> { curCol = lastVisibleCol(); refreshLead(); if (curCol == COL_MSG) setCharIndex(curMessageText().length) else scrollToX(Int.MAX_VALUE); reset() }
            '^' -> { if (curCol == COL_MSG) setCharIndex(0) else { curCol = firstVisibleCol(); scrollToX(0); refreshLead() }; reset() }
            'v' -> { reset(); if (curCol == COL_MSG) toggleCharVisual() else toggleVisual() }
            'V' -> { reset(); toggleVisual() }
            'w' -> { reset(); if (curCol == COL_MSG) wordForward(n) }
            'e' -> { reset(); if (curCol == COL_MSG) wordEnd(n) }
            'y' -> { when { charVisual -> yankCharVisual(); visual -> yankVisual(); else -> pending = 'y' } }
            'G' -> { gotoRow(if (count.isNotEmpty()) n - 1 else table.rowCount - 1); reset() }
            'g' -> if (pending == 'g') { gotoRow(0); reset() } else { pending = 'g' }
            'z' -> if (pending == 'z') { reset(); scrollCursorRow(ScrollTo.CENTER) } else { pending = 'z' }
            'a' -> { val z = pending == 'z'; reset(); if (z) onToggleFold() }
            't' -> { val p = pending; reset(); when (p) { 'z' -> scrollCursorRow(ScrollTo.TOP); 'g' -> onSwitchTab(1) } }
            'b' -> { val z = pending == 'z'; reset(); if (z) scrollCursorRow(ScrollTo.BOTTOM) else if (curCol == COL_MSG) wordBack(n) }
            'R' -> { val z = pending == 'z'; reset(); if (z) onFoldAll(false) } // zR open all folds
            'M' -> { val z = pending == 'z'; reset(); if (z) onFoldAll(true) else screenRow(ScreenPos.MIDDLE) } // zM close all / M middle
            'Z' -> { reset() }
            'H' -> { reset(); screenRow(ScreenPos.TOP) }
            'L' -> { reset(); screenRow(ScreenPos.BOTTOM) }
            'n' -> { onSearchRepeat(1); reassertColumn(); reset() }
            'N' -> { onSearchRepeat(-1); reassertColumn(); reset() }
            '{' -> { jumpBlankRow(-1); reset() }
            '}' -> { jumpBlankRow(1); reset() }
            '[' -> { pending = '[' }
            ']' -> { pending = ']' }
            'm' -> { reset(); pendingMark = 'm' }
            '`' -> { reset(); pendingMark = '`' }
            'J' -> { reset(); onShowJson() }
            'T' -> { val g = pending == 'g'; reset(); if (g) onSwitchTab(-1) else { onGotoTime(); reassertColumn() } }
            '*' -> { reset(); onSearchWord(wordUnderCursor(), 1) }
            '#' -> { reset(); onSearchWord(wordUnderCursor(), -1) }
            '/' -> { onFocusFilter(); reset() }
            '?' -> { reset(); showHelp() }
            else -> reset()
        }
    }

    private fun levelForKey(ch: Char): LogLevel? = when (ch) {
        'e' -> LogLevel.ERROR
        'w' -> LogLevel.WARN
        'i' -> LogLevel.INFO
        'd' -> LogLevel.DEBUG
        't' -> LogLevel.TRACE
        else -> null
    }

    private fun curRow() = if (visual) cursorRow.coerceIn(0, maxOf(0, table.rowCount - 1)) else table.selectedRow.coerceAtLeast(0)

    private fun move(d: Int) {
        if (table.rowCount == 0) return
        select((curRow() + d).coerceIn(0, table.rowCount - 1))
    }

    private fun gotoRow(row: Int) {
        if (table.rowCount == 0) return
        select(row.coerceIn(0, table.rowCount - 1))
    }

    private fun select(row: Int) {
        if (charVisual && row != cursorRow) exitCharVisual() // char-wise selection is single-line
        cursorRow = row.coerceIn(0, maxOf(0, table.rowCount - 1))
        if (visual && table.rowCount > 0) {
            table.setRowSelectionInterval(minOf(visualAnchor, cursorRow), maxOf(visualAnchor, cursorRow))
        } else {
            table.changeSelection(cursorRow, curCol.coerceIn(0, maxOf(0, table.columnCount - 1)), false, false)
        }
        // Vertical-only scroll, keeping the current horizontal position (no jitter on j/k over varying-length
        // lines). msgCharIndex is a "desired column": the renderer clamps it to each row for display, and
        // the next h/l clamps it for real — so we DON'T re-scroll horizontally here.
        val vp = viewport()
        val rect = table.getCellRect(cursorRow, 0, true)
        if (vp != null) table.scrollRectToVisible(Rectangle(vp.viewPosition.x, rect.y, 1, rect.height))
        else table.scrollRectToVisible(rect)
    }

    private fun viewport(): JViewport? = table.parent as? JViewport

    // ---- Column cursor + horizontal text scroll (h/l) ----

    private fun colWidth(c: Int) = if (c in 0 until table.columnCount) table.columnModel.getColumn(c).width else 0
    private fun firstVisibleCol(): Int { for (c in 0 until table.columnCount) if (colWidth(c) > 0) return c; return 0 }
    private fun lastVisibleCol(): Int { for (c in table.columnCount - 1 downTo 0) if (colWidth(c) > 0) return c; return 0 }
    private fun nextVisibleCol(c: Int, dir: Int): Int {
        var x = c + dir
        while (x in 0 until table.columnCount && colWidth(x) == 0) x += dir
        return if (x in 0 until table.columnCount) x else c
    }

    /** h/l: in the Message column move the **char cursor** (at the first char, h escapes to the previous
     *  column); in Time/Level it hops whole columns. */
    private fun moveHoriz(dir: Int, n: Int) {
        if (curCol == COL_MSG) {
            if (dir > 0) setCharIndex(msgCharIndex + n)
            else if (msgCharIndex > 0) setCharIndex(msgCharIndex - n)
            else {
                val p = nextVisibleCol(curCol, -1)
                if (p != curCol) { curCol = p; scrollToX(0); refreshLead(); repaintRow() }
                else onExitLeft() // raw view (Message is the only column) → leave to the left
            }
        } else {
            val p = nextVisibleCol(curCol, dir)
            if (p != curCol) { curCol = p; enterCol() }
            else if (dir < 0) onExitLeft() // already at the leftmost column → leave to the left (sources tree)
        }
    }

    /** After a column hop: enter char mode (cursor at 0) if it's the Message column, else show the column. */
    private fun enterCol() {
        if (curCol == COL_MSG) { msgCharIndex = 0; ensureCharVisible(0) } else scrollToX(0)
        refreshLead(); repaintRow()
    }

    private fun setCharIndex(i: Int) {
        msgCharIndex = i.coerceIn(0, curMessageText().length)
        ensureCharVisible(msgCharIndex)
        repaintRow()
    }

    /** The char index to draw the Message cursor at, or -1 when the cursor isn't in the Message column. */
    fun messageCursorIndex(): Int = if (enabled && !visual && curCol == COL_MSG) msgCharIndex else -1

    private fun curMessageText(): String {
        val r = curRow()
        if (r !in 0 until table.rowCount) return ""
        val mr = table.convertRowIndexToModel(r)
        if (mr !in 0 until model.rowCount) return ""
        return if (model.rawMode) model.rawAt(mr) else model.messageAt(mr)
    }

    /** Horizontally scroll so the char cursor at [index] stays visible (monospace-approx). */
    private fun ensureCharVisible(index: Int) {
        val vp = viewport() ?: return
        val msgColX = (0 until COL_MSG).sumOf { colWidth(it) }
        val caretX = msgColX + JBUI.scale(6) + monoCharW * index
        val margin = monoCharW * 3
        val left = vp.viewPosition.x
        val right = left + vp.width
        val maxX = maxOf(0, table.width - vp.width)
        val newX = when {
            caretX - margin < left -> caretX - margin
            caretX + margin > right -> caretX + margin - vp.width
            else -> left
        }.coerceIn(0, maxX)
        if (newX != left) vp.viewPosition = Point(newX, vp.viewPosition.y)
    }

    private fun repaintRow() {
        val r = curRow()
        if (r in 0 until table.rowCount) {
            val rect = table.getCellRect(r, COL_MSG, true)
            table.repaint(0, rect.y, table.width, rect.height)
        }
    }

    /** Re-point the focus ring at [curCol] without changing the row (normal mode only). */
    private fun refreshLead() {
        if (visual) return
        val r = table.selectedRow
        if (r >= 0 && table.columnCount > 0) table.changeSelection(r, curCol.coerceIn(0, table.columnCount - 1), false, false)
    }

    /** After an external row jump (search / level / time), restore the column cursor + ring. */
    private fun reassertColumn() {
        if (visual || charVisual) return
        refreshLead()
        if (curCol == COL_MSG) { msgCharIndex = msgCharIndex.coerceAtMost(curMessageText().length); ensureCharVisible(msgCharIndex); repaintRow() }
    }

    // ---- Word motions + character-wise selection (Message column) ----

    private fun afterCharMove() { ensureCharVisible(msgCharIndex); repaintRow() }

    private fun charClass(c: Char) = when {
        c.isWhitespace() -> 0
        c.isLetterOrDigit() || c == '_' -> 1
        else -> 2
    }

    /** `w`: jump to the start of the next word. */
    private fun wordForward(n: Int) {
        val s = curMessageText()
        repeat(n) {
            var p = msgCharIndex
            if (p < s.length) {
                val cls = charClass(s[p])
                if (cls != 0) while (p < s.length && charClass(s[p]) == cls) p++
                while (p < s.length && charClass(s[p]) == 0) p++
            }
            msgCharIndex = p.coerceAtMost(s.length)
        }
        afterCharMove()
    }

    /** `e`: jump to the end of the current/next word. */
    private fun wordEnd(n: Int) {
        val s = curMessageText()
        repeat(n) {
            var p = msgCharIndex + 1
            while (p < s.length && charClass(s[p]) == 0) p++
            if (p < s.length) { val cls = charClass(s[p]); while (p + 1 < s.length && charClass(s[p + 1]) == cls) p++ }
            msgCharIndex = p.coerceIn(0, maxOf(0, s.length - 1))
        }
        afterCharMove()
    }

    /** `b`: jump to the start of the previous word. */
    private fun wordBack(n: Int) {
        val s = curMessageText()
        repeat(n) {
            var p = msgCharIndex - 1
            while (p >= 0 && charClass(s[p]) == 0) p--
            if (p >= 0) { val cls = charClass(s[p]); while (p > 0 && charClass(s[p - 1]) == cls) p-- }
            msgCharIndex = p.coerceAtLeast(0)
        }
        afterCharMove()
    }

    private fun toggleCharVisual() {
        if (visual) exitVisual()
        if (charVisual) { exitCharVisual(); return }
        charVisual = true
        charAnchor = msgCharIndex
        repaintRow()
    }

    private fun exitCharVisual() { charVisual = false; repaintRow() }

    private fun yankCharVisual() {
        val s = curMessageText()
        val lo = minOf(charAnchor, msgCharIndex).coerceIn(0, s.length)
        val hi = (maxOf(charAnchor, msgCharIndex) + 1).coerceIn(0, s.length)
        if (hi > lo) CopyPasteManager.getInstance().setContents(StringSelection(s.substring(lo, hi)))
        exitCharVisual()
    }

    /** The selected char range on the Message cursor row (character-wise visual), or null. */
    fun messageSelectionRange(): IntRange? =
        if (enabled && charVisual && curCol == COL_MSG) minOf(charAnchor, msgCharIndex)..maxOf(charAnchor, msgCharIndex) else null

    /** `*`/`#`: the whole word at/after the char cursor in the Message column ("" when not applicable). */
    private fun wordUnderCursor(): String {
        if (curCol != COL_MSG) return ""
        val s = curMessageText()
        if (s.isEmpty()) return ""
        var p = msgCharIndex.coerceIn(0, s.length - 1)
        while (p < s.length && charClass(s[p]) != 1) p++ // step onto the next word char
        if (p >= s.length) return ""
        var a = p; while (a > 0 && charClass(s[a - 1]) == 1) a--
        var b = p; while (b + 1 < s.length && charClass(s[b + 1]) == 1) b++
        return s.substring(a, b + 1)
    }

    // ---- Visual mode + yank ----

    private fun toggleVisual() {
        if (charVisual) exitCharVisual()
        if (visual) { exitVisual(); return }
        if (table.rowCount == 0) return
        visual = true
        cursorRow = curRow()
        visualAnchor = cursorRow
        table.setRowSelectionInterval(cursorRow, cursorRow)
    }

    private fun exitVisual() {
        visual = false
        if (table.rowCount > 0) {
            val r = cursorRow.coerceIn(0, table.rowCount - 1)
            table.changeSelection(r, curCol.coerceIn(0, table.columnCount - 1), false, false)
        }
    }

    private fun yankVisual() {
        yankRows(table.selectedRows.toList())
        exitVisual()
    }

    private fun yankLines(a: Int, b: Int) {
        val lo = minOf(a, b).coerceAtLeast(0)
        val hi = maxOf(a, b).coerceAtMost(table.rowCount - 1)
        if (hi >= lo) yankRows((lo..hi).toList())
    }

    /** Copy the given view rows' (whole-line) text to the clipboard. */
    private fun yankRows(viewRows: List<Int>) {
        if (viewRows.isEmpty()) return
        val text = viewRows.joinToString("\n") { vr ->
            val mr = table.convertRowIndexToModel(vr)
            if (mr in 0 until model.rowCount) model.rawAt(mr) else ""
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun scrollToX(x: Int) {
        val vp = viewport() ?: return
        val maxX = maxOf(0, table.width - vp.width)
        vp.viewPosition = Point(x.coerceIn(0, maxX), vp.viewPosition.y)
    }

    private fun halfPage(direction: Int) {
        if (table.rowCount == 0) return
        val rowH = maxOf(1, table.rowHeight)
        val rows = maxOf(1, table.visibleRect.height / rowH)
        move(direction * maxOf(1, rows / 2))
    }

    private fun scrollLines(n: Int) {
        val vp = viewport() ?: return
        val rowH = maxOf(1, table.rowHeight)
        val maxY = maxOf(0, table.height - vp.height)
        val pos = vp.viewPosition
        pos.y = (pos.y + n * rowH).coerceIn(0, maxY)
        vp.viewPosition = pos
    }

    private enum class ScrollTo { TOP, CENTER, BOTTOM }

    private fun scrollCursorRow(where: ScrollTo) {
        val vp = viewport() ?: return
        val row = table.selectedRow
        if (row < 0) return
        val rect = table.getCellRect(row, 0, true)
        val viewH = vp.height
        val y = when (where) {
            ScrollTo.TOP -> rect.y
            ScrollTo.BOTTOM -> rect.y + rect.height - viewH
            ScrollTo.CENTER -> rect.y - (viewH - rect.height) / 2
        }
        val maxY = maxOf(0, table.height - viewH)
        vp.viewPosition = Point(vp.viewPosition.x, y.coerceIn(0, maxY))
    }

    private enum class ScreenPos { TOP, MIDDLE, BOTTOM }

    private fun screenRow(pos: ScreenPos) {
        val vp = viewport() ?: return
        if (table.rowCount == 0) return
        val rowH = maxOf(1, table.rowHeight)
        val first = (vp.viewPosition.y / rowH).coerceIn(0, table.rowCount - 1)
        val visibleRows = maxOf(1, vp.height / rowH)
        val last = (first + visibleRows - 1).coerceAtMost(table.rowCount - 1)
        select(when (pos) {
            ScreenPos.TOP -> first
            ScreenPos.BOTTOM -> last
            ScreenPos.MIDDLE -> (first + last) / 2
        })
    }

    private fun jumpBlankRow(dir: Int) {
        val n = table.rowCount
        if (n == 0) return
        var r = curRow()
        while (true) {
            r += dir
            if (r < 0 || r >= n) { gotoRow(if (dir > 0) n - 1 else 0); return }
            val mr = table.convertRowIndexToModel(r)
            if (model.rawAt(mr).isBlank()) { select(r); return }
        }
    }

    private fun setMark(ch: Char) {
        val r = table.selectedRow
        if (r < 0) return
        marks[ch] = table.convertRowIndexToModel(r)
    }

    private fun jumpToMark(ch: Char) {
        val mr = marks[ch] ?: return
        val vr = runCatching { table.convertRowIndexToView(mr) }.getOrDefault(-1)
        if (vr in 0 until table.rowCount) select(vr)
    }

    /** Rebase marks after the model trims [dropped] rows off the front (live-tail cap). */
    fun shiftMarks(dropped: Int) {
        val rebased = HashMap<Char, Int>()
        for ((k, v) in marks) if (v >= dropped) rebased[k] = v - dropped
        marks.clear(); marks.putAll(rebased)
    }

    /** `?` — Korean shortcut cheat sheet rendered with **Compose (Jewel)**; see [showLogHelpPopup]. */
    private fun showHelp() = showLogHelpPopup(table)

    /** Esc cancels in stages: char-visual → line-visual → pending/count → active search → release focus. */
    private fun handleEscape() {
        when {
            charVisual -> exitCharVisual()
            visual -> exitVisual()
            pending != null || pendingMark != null || count.isNotEmpty() -> reset()
            onEscape() -> {} // the panel cleared an active search/filter
            else -> KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
        }
    }

    private fun reset() {
        count.setLength(0)
        pending = null
        pendingMark = null
    }
}
