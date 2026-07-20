package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Headless tests pinning [LogRowFilter]'s semantics: blank-row dropping, level filtering,
 * fold hiding, and the block-aware search (a match anywhere in a multi-line record keeps the
 * whole record visible) — including out-of-order evaluation against the block memo.
 */
class LogRowFilterTest {

    private val allLevels = (LogLevel.REAL + LogLevel.OTHER).toSet()

    private fun primary(msg: String, level: LogLevel = LogLevel.INFO) =
        ParsedRow(msg, msg, level, 1_000L, 0, hasAnsi = false, continuationCandidate = false)

    private fun cont(msg: String) =
        ParsedRow(msg, msg, LogLevel.OTHER, LogLine.NO_TIME, 0, hasAnsi = false, continuationCandidate = true)

    /** boom + 2 stack frames, then an unrelated INFO line, then a blank line. */
    private fun sampleModel(): LogTableModel {
        val m = LogTableModel()
        m.appendParsed(
            listOf(
                primary("결제 실패 orderId=7", LogLevel.ERROR), // 0 (block 0..2)
                cont("  at pay.Gateway.call(Gateway.java:107)"), // 1
                cont("  at pay.Retry.run(Retry.java:31)"),       // 2
                primary("heartbeat ok"),                          // 3
                primary(""),                                      // 4 (blank)
            )
        )
        return m
    }

    private fun filter(
        model: LogTableModel,
        query: String = "",
        regex: Boolean = false,
        levels: Set<LogLevel> = allLevels,
        context: Int = 0,
    ) = LogRowFilter(
        model,
        query,
        pattern = if (regex) Pattern.compile(query, Pattern.CASE_INSENSITIVE) else null,
        ignoreCase = true,
        enabledLevels = levels,
        contextLines = context,
    )

    @Test
    fun `no query - blank rows drop and levels filter per row`() {
        val m = sampleModel()
        val f = filter(m)
        assertEquals(listOf(true, true, true, true, false), (0..4).map { f.includeRow(it) })

        val errorsOnly = filter(m, levels = setOf(LogLevel.ERROR))
        // continuations inherit the block's ERROR level, so the whole record survives "에러만"
        assertEquals(listOf(true, true, true, false, false), (0..4).map { errorsOnly.includeRow(it) })
    }

    @Test
    fun `a match anywhere in a block keeps the whole block`() {
        val m = sampleModel()
        val onPrimary = filter(m, query = "orderId")
        assertTrue((0..2).all { onPrimary.includeRow(it) })
        assertFalse(onPrimary.includeRow(3))

        // matches ONLY a continuation line (a stack frame) — the primary must stay too
        val onFrame = filter(m, query = "Retry.java")
        assertTrue((0..2).all { onFrame.includeRow(it) })
        assertFalse(onFrame.includeRow(3))
    }

    @Test
    fun `regex and literal queries agree with their own semantics`() {
        val m = sampleModel()
        assertTrue(filter(m, query = "gateway.*107", regex = true).includeRow(0)) // regex spans the frame
        assertFalse(filter(m, query = "gateway.*107", regex = false).includeRow(0)) // literal: no such substring
        assertTrue(filter(m, query = "HEARTBEAT").includeRow(3)) // literal is case-insensitive
    }

    @Test
    fun `folded continuations stay hidden even when the query matches them`() {
        val m = sampleModel()
        m.toggleFold(0)
        val f = filter(m, query = "Retry.java")
        assertTrue(f.includeRow(0)) // the primary represents the (matching) folded block
        assertFalse(f.includeRow(1))
        assertFalse(f.includeRow(2))
    }

    @Test
    fun `block memo stays correct under out-of-order evaluation`() {
        val m = sampleModel()
        val f = filter(m, query = "orderId")
        // Jump around blocks: row 3 (no match), then into block 0, then back — memo must recompute.
        assertFalse(f.includeRow(3))
        assertTrue(f.includeRow(2))
        assertFalse(f.includeRow(3))
        assertTrue(f.includeRow(0))
    }

    // ---- Context mode (grep -C style) ----

    /** 8 single-line records; only row 3 matches "boom". */
    private fun flatModel(): LogTableModel {
        val m = LogTableModel()
        m.appendParsed(listOf("aaa", "bbb", "ccc", "boom failed", "ddd", "eee", "fff", "ggg").map { primary(it) })
        return m
    }

    @Test
    fun `context keeps rows within N of a match and drops the rest`() {
        val m = flatModel()
        val f = filter(m, query = "boom", context = 2)
        // rows 1..5 are within ±2 of the match at 3; rows 0, 6, 7 are not.
        assertEquals(
            listOf(false, true, true, true, true, true, false, false),
            (0..7).map { f.includeRow(it) },
        )
        // zero context = matches only (the existing behavior).
        val f0 = filter(m, query = "boom")
        assertEquals(listOf(3), (0..7).filter { f0.includeRow(it) })
    }

    @Test
    fun `context extends past block edges and blank rows stay dropped`() {
        val m = sampleModel() // block 0..2 (matching), 3 heartbeat, 4 blank
        val f = filter(m, query = "Retry.java", context = 1)
        // block rows 0..2 match; heartbeat (3) is within 1 of row 2; the blank row (4) still drops.
        assertEquals(listOf(true, true, true, true, false), (0..4).map { f.includeRow(it) })
    }

    @Test
    fun `context verdicts stay correct under out-of-order evaluation`() {
        val m = flatModel()
        val f = filter(m, query = "boom", context = 1)
        // Evaluate from the far end first: the lazy forward scan must still resolve earlier rows.
        assertFalse(f.includeRow(7))
        assertTrue(f.includeRow(2))
        assertFalse(f.includeRow(0))
        assertTrue(f.includeRow(4))
        assertFalse(f.includeRow(5))
    }
}
