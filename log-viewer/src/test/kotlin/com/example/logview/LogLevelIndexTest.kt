package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Headless tests for [LogTableModel]'s per-level row index ([LogTableModel.nextLevelRow] /
 * [LogTableModel.forEachLevelRow]) — the data behind the ]e/[e jumps and the heatmap stripe —
 * including the front-trim rebase.
 */
class LogLevelIndexTest {

    private fun row(level: LogLevel, msg: String) =
        ParsedRow(msg, msg, level, 1_000L, 0, hasAnsi = false, continuationCandidate = false)

    private fun model(vararg levels: LogLevel): LogTableModel {
        val m = LogTableModel()
        m.appendParsed(levels.mapIndexed { i, lv -> row(lv, "line$i") })
        return m
    }

    @Test
    fun `nextLevelRow finds the nearest row in either direction without wrapping`() {
        val m = model(
            LogLevel.INFO,  // 0
            LogLevel.ERROR, // 1
            LogLevel.INFO,  // 2
            LogLevel.ERROR, // 3
            LogLevel.WARN,  // 4
        )
        assertEquals(1, m.nextLevelRow(LogLevel.ERROR, 0, 1))
        assertEquals(3, m.nextLevelRow(LogLevel.ERROR, 1, 1)) // strictly after the current row
        assertEquals(-1, m.nextLevelRow(LogLevel.ERROR, 3, 1)) // no wrap
        assertEquals(3, m.nextLevelRow(LogLevel.ERROR, 4, -1))
        assertEquals(1, m.nextLevelRow(LogLevel.ERROR, 3, -1))
        assertEquals(-1, m.nextLevelRow(LogLevel.ERROR, 1, -1))
        assertEquals(-1, m.nextLevelRow(LogLevel.DEBUG, 0, 1)) // level absent entirely
    }

    @Test
    fun `continuations are indexed under their block's effective level`() {
        val m = LogTableModel()
        m.appendParsed(
            listOf(
                row(LogLevel.ERROR, "boom"),
                ParsedRow("  at a()", "  at a()", LogLevel.OTHER, LogLine.NO_TIME, 0, false, continuationCandidate = true),
                row(LogLevel.INFO, "next"),
            )
        )
        // The continuation inherits ERROR, so ]e from the primary lands on it.
        assertEquals(1, m.nextLevelRow(LogLevel.ERROR, 0, 1))
    }

    @Test
    fun `front-trim rebases the level index`() {
        val m = LogTableModel()
        m.maxLines = 8
        m.appendParsed((0 until 10).map { row(if (it % 3 == 0) LogLevel.ERROR else LogLevel.INFO, "l$it") })
        // 10 lines, cap 8 -> oldest 4 dropped; surviving ERRORs were at original rows 6, 9 -> now 2, 5.
        assertEquals(6, m.rowCount)
        val errors = ArrayList<Int>()
        m.forEachLevelRow(LogLevel.ERROR) { errors.add(it) }
        assertEquals(listOf(2, 5), errors)
        assertEquals(2, m.nextLevelRow(LogLevel.ERROR, -1, 1))
        assertEquals(5, m.nextLevelRow(LogLevel.ERROR, 2, 1))
    }
}
