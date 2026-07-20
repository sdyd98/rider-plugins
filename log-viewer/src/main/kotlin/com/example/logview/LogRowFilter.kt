package com.example.logview

import java.util.regex.Pattern
import javax.swing.RowFilter

/**
 * The log grid's row filter — one immutable instance per filter state (the panel rebuilds it on
 * every query/level/regex/case change). A row is visible when ALL of:
 *
 *  1. it is not a continuation hidden by a collapsed fold,
 *  2. it is not blank (newline-only rows are dropped entirely),
 *  3. its (effective) level is enabled,
 *  4. the query — if any — matches its BLOCK: a multi-line record (a stack trace) is kept intact
 *     when ANY of its lines matches, so a search never strands a primary line from its
 *     continuations or vice-versa. With [contextLines] > 0 (grep `-C` style), rows within that
 *     distance of a matching block also stay visible, so a match keeps its surroundings.
 *
 * The block-aware search memoizes the per-block verdict: the sorter evaluates rows in ascending
 * model order and a block's rows are contiguous, so each block is regex-scanned once per rebuild.
 * (Naively re-scanning the block for every member row made a refilter O(k²) per k-line block.)
 * Context mode swaps the single-block memo for a lazily-filled per-row verdict bitmap — still one
 * regex scan per block, plus an O(contextLines) window check per row.
 *
 * Extracted from LogViewerPanel so these semantics are headless-testable ([includeRow]).
 */
class LogRowFilter(
    private val model: LogTableModel,
    query: String,
    /** Compiled search pattern; null = [query] is matched as a literal (invalid regex fallback). */
    private val pattern: Pattern?,
    private val ignoreCase: Boolean,
    enabledLevels: Set<LogLevel>,
    /** Rows of context to keep visible around each match (grep `-C`); 0 = matching blocks only. */
    private val contextLines: Int = 0,
) : RowFilter<LogTableModel, Int>() {

    private val q = query.trim()
    private val levels = enabledLevels.toSet() // snapshot — the panel mutates its own set freely

    private var memoBlock = -1
    private var memoHit = false

    // Context mode: per-row block-match verdicts, filled lazily in ascending row order (each block
    // is regex-scanned exactly once — the same total cost as the zero-context memo path).
    private val hits = java.util.BitSet()
    private var scannedTo = -1

    override fun include(entry: Entry<out LogTableModel, out Int>): Boolean = includeRow(entry.identifier)

    /** The filter decision for one MODEL row. Public so tests can drive it without a RowSorter. */
    fun includeRow(row: Int): Boolean {
        if (model.isHiddenByFold(row)) return false
        if (model.rawAt(row).isBlank()) return false
        if (model.levelAt(row) !in levels) return false
        if (q.isEmpty()) return true
        if (contextLines <= 0) {
            val block = model.blockRange(row)
            if (block.first != memoBlock) {
                memoBlock = block.first
                memoHit = blockMatches(block)
            }
            return memoHit
        }
        // Context mode: visible when any row within ±contextLines belongs to a matching block.
        ensureScanned(row + contextLines)
        val from = (row - contextLines).coerceAtLeast(0)
        val hit = hits.nextSetBit(from)
        return hit >= 0 && hit <= row + contextLines
    }

    private fun blockMatches(block: IntRange): Boolean {
        for (m in block) {
            val raw = model.rawAt(m)
            if (if (pattern != null) pattern.matcher(raw).find() else raw.contains(q, ignoreCase = ignoreCase)) {
                return true
            }
        }
        return false
    }

    /** Fill [hits] for rows up to [upTo] (clamped to the model), advancing block by block. */
    private fun ensureScanned(upTo: Int) {
        val limit = minOf(upTo, model.rowCount - 1)
        var m = scannedTo + 1
        while (m <= limit) {
            val block = model.blockRange(m)
            if (blockMatches(block)) for (r in block) hits.set(r)
            m = block.last + 1
        }
        scannedTo = maxOf(scannedTo, m - 1)
    }
}
