package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless tests for [LogTableModel]'s O(1) block bookkeeping ([LogLine.contCount]): block sizes,
 * foldability, ranges, and the live-tail front-trim (orphaned continuations). The row filter and
 * fold toggling lean on these being exact.
 */
class LogBlockTest {

    private fun primary(msg: String) =
        ParsedRow(msg, msg, LogLevel.INFO, 1_000L, 0, hasAnsi = false, continuationCandidate = false)

    private fun cont(msg: String) =
        ParsedRow(msg, msg, LogLevel.OTHER, LogLine.NO_TIME, 0, hasAnsi = false, continuationCandidate = true)

    @Test
    fun `continuation counts and ranges are tracked as lines append`() {
        val model = LogTableModel()
        model.appendParsed(listOf(primary("boom"), cont("  at a()"), cont("  at b()"), primary("next")))

        assertEquals(2, model.blockContinuationCount(0))
        assertEquals(0, model.blockContinuationCount(3))
        assertEquals(0, model.blockContinuationCount(1)) // a continuation row owns no block
        assertEquals(0..2, model.blockRange(1)) // any member maps to the whole block
        assertEquals(3..3, model.blockRange(3))
        assertTrue(model.isFoldableStart(0))
        assertFalse(model.isFoldableStart(1))
        assertFalse(model.isFoldableStart(3))

        // A block keeps growing when continuations arrive in a LATER batch.
        model.appendParsed(listOf(cont("  detail"))) // next primary is now the open block
        assertEquals(1, model.blockContinuationCount(3))
        assertEquals(3..4, model.blockRange(4))
    }

    @Test
    fun `fold hides exactly the block's continuation rows`() {
        val model = LogTableModel()
        model.appendParsed(listOf(primary("boom"), cont("  at a()"), cont("  at b()"), primary("next")))
        assertEquals(0, model.toggleFold(1)) // toggling from any member folds the owning block
        assertTrue(model.isHiddenByFold(1))
        assertTrue(model.isHiddenByFold(2))
        assertFalse(model.isHiddenByFold(0))
        assertFalse(model.isHiddenByFold(3))
        assertEquals(0, model.toggleFold(0)) // toggle back open
        assertFalse(model.isHiddenByFold(1))
    }

    @Test
    fun `front-trim orphans continuations without breaking block queries`() {
        val model = LogTableModel()
        model.maxLines = 8
        var trimmed = -1
        model.onTrim = { trimmed = it }
        model.appendParsed(listOf(primary("boom")) + (1..9).map { cont("  at frame$it()") })

        assertEquals(4, trimmed) // 10 lines, cap 8 -> keep 6, drop the oldest 4
        assertEquals(6, model.rowCount)
        // The primary was dropped: survivors are orphaned continuations, each its own 1-row "block".
        assertEquals(-1, model.lineAt(0).blockStart)
        assertEquals(0..0, model.blockRange(0))
        assertFalse(model.isFoldableStart(0))
        assertEquals(0, model.blockContinuationCount(0))
    }
}
