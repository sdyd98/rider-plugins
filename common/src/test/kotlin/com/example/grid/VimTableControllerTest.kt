package com.example.grid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Headless tests for the shared vim state machine (counts, two-key mark sequences, gotoRow)
 * through a minimal subclass over a plain Swing [JTable] — no IDE runtime needed.
 */
class VimTableControllerTest {

    /** Just enough dispatch to exercise the base helpers the way the real controllers do. */
    private class TestController(table: JTable) : VimTableController(table) {
        val marks = HashMap<Char, Int>()
        override val keyChars = ""
        override fun pressChar(ch: Char) {
            if (handleMarkKey(ch)) return
            if (bufferCountDigit(ch)) return
            val n = countOr1()
            when (ch) {
                'j' -> { moveRows(n); reset() }
                'k' -> { moveRows(-n); reset() }
                'G' -> { gotoRow(if (hasCount()) n - 1 else table.rowCount - 1); reset() }
                'm' -> { reset(); pendingMark = 'm' }
                '`' -> { reset(); pendingMark = '`' }
                else -> reset()
            }
        }
        override fun handleEscape() = reset()
        override fun setMark(ch: Char) { marks[ch] = table.selectedRow }
        override fun jumpToMark(ch: Char) { marks[ch]?.let { selectRow(it) } }
        override fun moveRows(delta: Int) =
            selectRow((table.selectedRow.coerceAtLeast(0) + delta).coerceIn(0, table.rowCount - 1))
        override fun selectRow(row: Int) = table.changeSelection(row, 0, false, false)

        fun press(keys: String) = keys.forEach { pressChar(it) }
    }

    private fun controller(rows: Int = 100): Pair<TestController, JTable> {
        val table = JTable(DefaultTableModel(rows, 3))
        table.changeSelection(0, 0, false, false)
        return TestController(table) to table
    }

    @Test
    fun `count prefix multiplies a motion`() {
        val (vim, table) = controller()
        vim.press("5j")
        assertEquals(5, table.selectedRow)
        vim.press("12j")
        assertEquals(17, table.selectedRow)
        vim.press("3k")
        assertEquals(14, table.selectedRow)
    }

    @Test
    fun `motion without count moves one row and count does not leak to the next key`() {
        val (vim, table) = controller()
        vim.press("5jj")
        assertEquals(6, table.selectedRow)
    }

    @Test
    fun `zero only joins a count after a nonzero digit`() {
        val (vim, table) = controller()
        vim.press("10j") // '1' then '0' buffer as a count of 10
        assertEquals(10, table.selectedRow)
        vim.press("0j") // bare '0' is NOT a count digit — falls through to dispatch ('0' resets), then j moves 1
        assertEquals(11, table.selectedRow)
    }

    @Test
    fun `G goes to the last row without a count and to the count line with one`() {
        val (vim, table) = controller(rows = 50)
        vim.press("G")
        assertEquals(49, table.selectedRow)
        vim.press("10G") // vim line numbers are 1-based -> row index 9
        assertEquals(9, table.selectedRow)
        vim.press("999G") // clamps to the last row
        assertEquals(49, table.selectedRow)
    }

    @Test
    fun `mark set and jump round-trips and a non-letter mark name is ignored`() {
        val (vim, table) = controller()
        vim.press("5j")
        vim.press("ma") // set mark a at row 5
        vim.press("20jmb") // mark b at row 25
        vim.press("`a")
        assertEquals(5, table.selectedRow)
        vim.press("`b")
        assertEquals(25, table.selectedRow)
        vim.press("`1j") // '1' is no mark name — consumed without jumping, then j moves 1
        assertEquals(26, table.selectedRow)
    }
}
