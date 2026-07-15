package com.example.xlsx

import com.example.grid.VimTableController
import javax.swing.JTable

/**
 * Vim navigation for one side of the grid DIFF: `hjkl` + counts, `gg`/`G`, `0`/`$`, `zz`/`zt`/`zb`,
 * `H`/`M`/`L`, `Ctrl+D/U/E/Y` (base class), `gt`/`gT` sheet-tab switching (same semantics as the
 * grid viewer), and vim diff-mode's **`]c` / `[c`** next/previous-change jumps (wired to the same
 * navigator F7 uses). No marks/visual/yank — the diff grid is a comparison view, not the data
 * browser; column 0 is the row-number gutter, so the cell cursor floors at 1.
 */
internal class VimDiffGridController(
    table: JTable,
    private val nextChange: (Int) -> Unit,
    private val prevChange: (Int) -> Unit,
    private val switchSheet: (Int) -> Unit,
) : VimTableController(table) {

    override val keyChars = "0123456789hjklgGztbTHML\$[]c"

    override fun pressChar(ch: Char) {
        val p = pending
        if (p != null) {
            pending = null
            when {
                p == 'g' && ch == 'g' -> gotoRow(0)
                p == 'g' && ch == 't' -> switchSheet(1)
                p == 'g' && ch == 'T' -> switchSheet(-1)
                p == 'z' && ch == 'z' -> scrollCursorRow(ScrollTo.CENTER)
                p == 'z' && ch == 't' -> scrollCursorRow(ScrollTo.TOP)
                p == 'z' && ch == 'b' -> scrollCursorRow(ScrollTo.BOTTOM)
                p == ']' && ch == 'c' -> nextChange(countOr1())
                p == '[' && ch == 'c' -> prevChange(countOr1())
            }
            reset()
            return
        }
        if (bufferCountDigit(ch)) return
        when (ch) {
            'g', 'z', '[', ']' -> { pending = ch; return }
            'h' -> moveCols(-countOr1())
            'l' -> moveCols(countOr1())
            'j' -> moveRows(countOr1())
            'k' -> moveRows(-countOr1())
            '0' -> moveToCol(1)
            '$' -> moveToCol(table.columnCount - 1)
            'G' -> gotoRow(if (hasCount()) countOr1() - 1 else table.rowCount - 1)
            'H' -> screenRow(ScreenPos.TOP)
            'M' -> screenRow(ScreenPos.MIDDLE)
            'L' -> screenRow(ScreenPos.BOTTOM)
        }
        reset()
    }

    override fun handleEscape() = reset()
    override fun setMark(ch: Char) {}
    override fun jumpToMark(ch: Char) {}

    override fun moveRows(delta: Int) = gotoRow((table.selectedRow.takeIf { it >= 0 } ?: 0) + delta)

    override fun selectRow(row: Int) {
        if (table.rowCount == 0) return
        table.changeSelection(row, currentCol(), false, false)
    }

    private fun currentCol(): Int = table.selectedColumn.takeIf { it >= 1 } ?: 1

    private fun moveCols(delta: Int) = moveToCol(currentCol() + delta)

    private fun moveToCol(col: Int) {
        if (table.rowCount == 0 || table.columnCount <= 1) return
        val row = table.selectedRow.takeIf { it >= 0 } ?: 0
        table.changeSelection(row, col.coerceIn(1, table.columnCount - 1), false, false)
    }
}
