package com.example.logview

/**
 * Groups repeated ERROR lines by their "shape": variable parts (numbers, hex ids, GUIDs) are
 * collapsed to `#`, so "결제 실패 orderId=1234" and "결제 실패 orderId=9977" fall into one group.
 * Feeds the 에러 요약 popup: what blew up, how many times, first/last occurrence.
 *
 * Pure and headless-testable; the panel collects [Entry]s from the model (block-primary ERROR
 * lines only — stack-frame continuations would otherwise form bogus per-frame groups).
 */
object ErrorSummary {

    /** One ERROR occurrence: its model row (jump target), source line number, time, message text. */
    data class Entry(val modelRow: Int, val lineNumber: Int, val timestampMillis: Long, val message: String)

    data class Group(
        val pattern: String,
        val count: Int,
        val firstLine: Int,
        val lastLine: Int,
        /** Epoch millis of the first/last occurrence, or [LogLine.NO_TIME] when lines carry no time. */
        val firstTime: Long,
        val lastTime: Long,
        /** Model row of the MOST RECENT occurrence — the popup jumps here. */
        val lastModelRow: Int,
    )

    private val GUID = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
    private val HEX = Regex("\\b(?:0x)?[0-9a-fA-F]{8,}\\b")
    private val NUM = Regex("\\d+")
    private val WS = Regex("\\s+")

    /** Collapse the variable parts of [message] so repeats of one error normalize identically. */
    fun normalize(message: String): String =
        message.replace(GUID, "#").replace(HEX, "#").replace(NUM, "#").replace(WS, " ").trim().take(300)

    /** Group [entries] (ascending model order) by normalized message, most frequent group first. */
    fun summarize(entries: List<Entry>): List<Group> {
        class Acc(
            var count: Int = 0,
            var firstLine: Int = 0,
            var lastLine: Int = 0,
            var firstTime: Long = LogLine.NO_TIME,
            var lastTime: Long = LogLine.NO_TIME,
            var lastModelRow: Int = -1,
        )

        val groups = LinkedHashMap<String, Acc>()
        for (e in entries) {
            val acc = groups.getOrPut(normalize(e.message)) { Acc(firstLine = e.lineNumber) }
            acc.count++
            acc.lastLine = e.lineNumber
            acc.lastModelRow = e.modelRow
            if (e.timestampMillis != LogLine.NO_TIME) {
                if (acc.firstTime == LogLine.NO_TIME) acc.firstTime = e.timestampMillis
                acc.lastTime = e.timestampMillis
            }
        }
        return groups.map { (pattern, a) ->
            Group(pattern, a.count, a.firstLine, a.lastLine, a.firstTime, a.lastTime, a.lastModelRow)
        }.sortedByDescending { it.count }
    }
}
