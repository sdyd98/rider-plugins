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
 *     continuations or vice-versa.
 *
 * The block-aware search memoizes the per-block verdict: the sorter evaluates rows in ascending
 * model order and a block's rows are contiguous, so each block is regex-scanned once per rebuild.
 * (Naively re-scanning the block for every member row made a refilter O(k²) per k-line block.)
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
) : RowFilter<LogTableModel, Int>() {

    private val q = query.trim()
    private val levels = enabledLevels.toSet() // snapshot — the panel mutates its own set freely

    private var memoBlock = -1
    private var memoHit = false

    override fun include(entry: Entry<out LogTableModel, out Int>): Boolean = includeRow(entry.identifier)

    /** The filter decision for one MODEL row. Public so tests can drive it without a RowSorter. */
    fun includeRow(row: Int): Boolean {
        if (model.isHiddenByFold(row)) return false
        if (model.rawAt(row).isBlank()) return false
        if (model.levelAt(row) !in levels) return false
        if (q.isEmpty()) return true
        val block = model.blockRange(row)
        if (block.first != memoBlock) {
            memoBlock = block.first
            memoHit = false
            for (m in block) {
                val raw = model.rawAt(m)
                if (if (pattern != null) pattern.matcher(raw).find() else raw.contains(q, ignoreCase = ignoreCase)) {
                    memoHit = true
                    break
                }
            }
        }
        return memoHit
    }
}
