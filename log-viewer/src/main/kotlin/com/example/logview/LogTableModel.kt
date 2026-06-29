package com.example.logview

import javax.swing.table.AbstractTableModel

/** Column indices for the Better-Stack-style Time | Level | Message layout. */
const val COL_TIME = 0
const val COL_LEVEL = 1
const val COL_MSG = 2

/**
 * The model behind the Time | Level | Message log grid: each row is one [LogLine] whose Message text
 * is the authentic [LogLine.raw]. Level/time are parsed at append time for coloring and filtering.
 * Multi-line records are grouped into blocks (see [LogLine.blockStart]) so [toggleFold] can collapse
 * stack traces.
 *
 * For an unbounded live tail the model keeps at most [maxLines] rows; past that it drops the oldest
 * quarter and shifts every block/fold index down (notifying [onTrim] so the view can rebase its marks).
 */
class LogTableModel : AbstractTableModel() {

    private val lines = ArrayList<LogLine>()
    private var nextLineNumber = 1
    private var currentBlockStart = -1
    private val counts = IntArray(LogLevel.entries.size)

    /** Model indices of currently-collapsed block-start lines (their continuation rows are hidden). */
    val foldedBlocks: MutableSet<Int> = HashSet()

    /** Soft cap for live tail (0 = unbounded). Trimming drops the oldest lines. */
    var maxLines: Int = 1_000_000

    /** When true the Message column shows the whole original line (raw view) instead of the body. */
    var rawMode: Boolean = false

    /** Called after a front-trim with the number of rows dropped, so the view can shift marks. */
    var onTrim: ((dropped: Int) -> Unit)? = null

    override fun getRowCount(): Int = lines.size
    override fun getColumnCount(): Int = 3 // Time | Level | Message (Better-Stack-style columns)
    override fun getColumnName(column: Int): String = when (column) {
        COL_TIME -> "Time"
        COL_LEVEL -> "Level"
        else -> "Message"
    }
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val l = lines[rowIndex]
        return when (columnIndex) {
            COL_TIME -> l.timeText
            COL_LEVEL -> if (l.level == LogLevel.OTHER) "" else l.level.label
            else -> if (rawMode) l.display else l.message
        }
    }
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    fun lineAt(row: Int): LogLine = lines[row]
    fun levelAt(row: Int): LogLevel = lines[row].level
    /** The whole ANSI-stripped line (used for filtering/search across every column). */
    fun rawAt(row: Int): String = lines[row].display
    /** Just the message-column text (for sizing the Message column and search highlighting). */
    fun messageAt(row: Int): String = lines[row].message
    fun loadedRowCount(): Int = lines.size
    fun countOf(level: LogLevel): Int = counts[level.ordinal]

    /** The largest source line number seen so far (keeps growing across trims) — for gutter sizing. */
    fun maxLineNumber(): Int = nextLineNumber - 1

    /** Parse [rawLines] into [LogLine]s (assigning block ownership) and append them. EDT only. */
    fun appendRaw(rawLines: List<String>) {
        if (rawLines.isEmpty()) return
        val start = lines.size
        for (raw in rawLines) {
            // Parse on the ANSI-STRIPPED text: ANSI codes like `…[32m` glue onto the level word ("mINFO")
            // and break the \b word-boundary level detection. clean == raw for the (common) no-ANSI case.
            val hasAnsi = AnsiText.hasAnsi(raw)
            val clean = if (hasAnsi) AnsiText.strip(raw) else raw
            val parsed = LogParser.parse(clean)
            val isCont = parsed.timestampMillis == LogLine.NO_TIME &&
                lines.isNotEmpty() && currentBlockStart >= 0 && LogParser.looksLikeContinuation(clean)
            val level = if (isCont) lines[currentBlockStart].level else parsed.level
            // A continuation line (stack frame / payload fragment) is ALL body — it has no parsed
            // level/timestamp prefix to strip. If LogParser found a stray level token inside it (e.g.
            // `"level": "ERROR",`), messageStart would point past it and truncate the displayed text,
            // so force messageStart = 0 for continuations.
            val messageStart = if (isCont) 0 else parsed.messageStart
            val line = LogLine(nextLineNumber++, raw, clean, level, parsed.timestampMillis, isCont, hasAnsi, messageStart)
            if (isCont) {
                line.blockStart = currentBlockStart
            } else {
                line.blockStart = lines.size
                currentBlockStart = lines.size
            }
            lines.add(line)
            counts[level.ordinal]++
        }
        fireTableRowsInserted(start, lines.size - 1)
        trimIfNeeded()
    }

    /** True if [row] is a continuation line hidden because its block is collapsed. */
    fun isHiddenByFold(row: Int): Boolean {
        val l = lines[row]
        return l.isContinuation && l.blockStart in foldedBlocks
    }

    /** True if [row] begins a block that has at least one continuation line (i.e. is foldable). */
    fun isFoldableStart(row: Int): Boolean {
        if (row < 0 || row >= lines.size) return false
        if (lines[row].isContinuation) return false
        val next = row + 1
        return next < lines.size && lines[next].isContinuation && lines[next].blockStart == row
    }

    /** Toggle the fold of the block owning [row]. Returns the block-start index, or -1 if not foldable. */
    fun toggleFold(row: Int): Int {
        if (row < 0 || row >= lines.size) return -1
        val blockStart = lines[row].blockStart.takeIf { it >= 0 } ?: return -1
        if (!isFoldableStart(blockStart)) return -1
        if (!foldedBlocks.add(blockStart)) foldedBlocks.remove(blockStart)
        return blockStart
    }

    /** The model-row range [blockStart .. last continuation] of the block owning [row]. */
    fun blockRange(row: Int): IntRange {
        val start = lines[row].blockStart.takeIf { it in lines.indices } ?: row
        return start..(start + blockContinuationCount(start))
    }

    /** Number of continuation lines belonging to the block that starts at [blockStart]. */
    fun blockContinuationCount(blockStart: Int): Int {
        if (blockStart < 0 || blockStart >= lines.size) return 0
        var n = 0
        var i = blockStart + 1
        while (i < lines.size && lines[i].isContinuation && lines[i].blockStart == blockStart) {
            n++; i++
        }
        return n
    }

    fun foldAll() {
        foldedBlocks.clear()
        for (i in lines.indices) if (isFoldableStart(i)) foldedBlocks.add(i)
    }

    fun unfoldAll() = foldedBlocks.clear()

    private fun trimIfNeeded() {
        if (maxLines <= 0 || lines.size <= maxLines) return
        val drop = lines.size - (maxLines * 3 / 4)
        if (drop <= 0) return
        for (i in 0 until drop) counts[lines[i].level.ordinal]--
        lines.subList(0, drop).clear()
        for (l in lines) l.blockStart = if (l.blockStart >= drop) l.blockStart - drop else -1
        currentBlockStart = if (currentBlockStart >= drop) currentBlockStart - drop else -1
        val rebased = foldedBlocks.mapNotNull { if (it >= drop) it - drop else null }.toHashSet()
        foldedBlocks.clear()
        foldedBlocks.addAll(rebased)
        fireTableDataChanged()
        onTrim?.invoke(drop)
    }
}
