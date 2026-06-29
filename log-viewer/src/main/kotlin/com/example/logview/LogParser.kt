package com.example.logview

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parsed metadata for a single line: the level token, a timestamp (if any), and [messageStart] — the
 * offset in the raw line where the message body begins (just after the level token), so the columnar
 * view can show Time / Level / Message separately. 0 = show the whole line as the message.
 */
class ParsedLine(val level: LogLevel, val timestampMillis: Long, val messageStart: Int, val thread: String = "")

/**
 * Stateless, allocation-light heuristics that pull a [LogLevel] and an epoch-millis timestamp out of a
 * raw log line, and decide whether a line is a stack-trace / payload **continuation**. Tuned for the
 * common server/game-engine formats (Logback/Log4j/SLF4J, Serilog/NLog, Unity, syslog) rather than a
 * single fixed grammar — it scans for tokens instead of demanding an exact layout.
 *
 * Speed matters (this runs once per line on potentially millions of lines): each pattern is compiled
 * once and matched against only the head of the line.
 */
object LogParser {

    private const val HEAD = 160 // only the start of a line carries the timestamp + level

    // A level word as a standalone token. FATAL/SEVERE fold into ERROR, WARNING into WARN, the
    // java.util.logging FINE* family into DEBUG/TRACE.
    private val LEVEL = Pattern.compile(
        "(?i)\\b(FATAL|ERROR|ERR|SEVERE|WARN|WARNING|INFO|INFORMATION|DEBUG|DBG|TRACE|VERBOSE|FINEST|FINER|FINE)\\b",
    )

    private fun mapLevel(token: String): LogLevel = when (token.uppercase()) {
        "FATAL", "ERROR", "ERR", "SEVERE" -> LogLevel.ERROR
        "WARN", "WARNING" -> LogLevel.WARN
        "INFO", "INFORMATION" -> LogLevel.INFO
        "DEBUG", "DBG", "FINE" -> LogLevel.DEBUG
        "TRACE", "VERBOSE", "FINEST", "FINER" -> LogLevel.TRACE
        else -> LogLevel.OTHER
    }

    // --- Timestamp patterns. Each capturing group order is documented per pattern below. ---

    // 2024-06-28 14:03:11.123  /  2024-06-28T14:03:11,123  /  2024/06/28 14:03:11
    private val ISO = Pattern.compile(
        "(\\d{4})[-/](\\d{2})[-/](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?",
    )
    // 06/28 14:03:11.123  /  28-06 14:03:11 (year-less) — assume the current year.
    private val MD = Pattern.compile(
        "(\\d{2})[-/](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?",
    )
    // Bare clock 14:03:11.123 — assume today's date.
    private val CLOCK = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?",
    )
    // syslog: Jun 28 14:03:11 — assume the current year.
    private val SYSLOG = Pattern.compile(
        "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) +(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2})",
    )
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** The built-in heuristic on its own (no user format). Kept for reference/restore — the viewer no
     *  longer applies it: a line with no matching user format is shown raw (see [parse] with formats). */
    fun parse(raw: String): ParsedLine {
        val head = if (raw.length > HEAD) raw.substring(0, HEAD) else raw
        return parseHeuristic(head)
    }

    /**
     * Parse [raw] using the user [formats] (in order). The first format that matches wins and supplies
     * ONLY what it captures (no heuristic merge) — so a partial format shows partial columns and the live
     * picker visibly fills them in field by field. A line that matches NO format (or no formats at all) is
     * left RAW — the whole line is the message, no time/level/thread (no auto-analysis).
     */
    fun parse(raw: String, formats: List<LineFormat>): ParsedLine {
        val head = if (raw.length > HEAD) raw.substring(0, HEAD) else raw
        for (fmt in formats) {
            val m = fmt.apply(head) ?: continue
            val level = m.level?.let { mapLevel(it) } ?: LogLevel.OTHER
            val time = m.time?.let { parseTimestamp(it) } ?: LogLine.NO_TIME
            return ParsedLine(level, time, m.messageStart.coerceAtLeast(0), m.thread?.trim() ?: "")
        }
        return ParsedLine(LogLevel.OTHER, LogLine.NO_TIME, 0) // no match → raw whole line
    }

    private fun parseHeuristic(head: String): ParsedLine {
        val lm = LEVEL.matcher(head)
        val level = if (lm.find()) mapLevel(lm.group(1)) else LogLevel.OTHER
        // Message starts just after the level token (the timestamp + thread before it move to the Time
        // column); with no level we keep the whole line as the message.
        val messageStart = if (level != LogLevel.OTHER) lm.end() else 0
        return ParsedLine(level, parseTimestamp(head), messageStart)
    }

    /** Best-effort epoch millis from the head of a line, or [LogLine.NO_TIME] when none is found. */
    fun parseTimestamp(head: String): Long {
        ISO.matcher(head).let { m ->
            if (m.find()) return millis(
                m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt(),
                m.group(4).toInt(), m.group(5).toInt(), m.group(6).toInt(), frac(m, 7),
            )
        }
        SYSLOG.matcher(head).let { m ->
            if (m.find()) {
                val month = MONTHS.indexOf(m.group(1)) + 1
                return millis(
                    LocalDate.now().year, month, m.group(2).toInt(),
                    m.group(3).toInt(), m.group(4).toInt(), m.group(5).toInt(), 0,
                )
            }
        }
        MD.matcher(head).let { m ->
            // Only reached when ISO/SYSLOG miss, so a hit here is genuinely a year-less date.
            if (m.find()) {
                return millis(
                    LocalDate.now().year, m.group(1).toInt(), m.group(2).toInt(),
                    m.group(3).toInt(), m.group(4).toInt(), m.group(5).toInt(), frac(m, 6),
                )
            }
        }
        CLOCK.matcher(head).let { m ->
            if (m.find()) {
                val today = LocalDate.now()
                return millis(
                    today.year, today.monthValue, today.dayOfMonth,
                    m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt(), frac(m, 4),
                )
            }
        }
        return LogLine.NO_TIME
    }

    /** Normalize a fractional-seconds group (1–9 digits) to milliseconds. */
    private fun frac(m: Matcher, group: Int): Int {
        val s = if (m.groupCount() >= group) m.group(group) else null
        if (s.isNullOrEmpty()) return 0
        // Pad/truncate to exactly 3 digits (milliseconds).
        val ms = (s + "000").substring(0, 3)
        return ms.toIntOrNull() ?: 0
    }

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int): Long = try {
        // An out-of-range day (e.g. a false "30/02" match) throws → treated as "no timestamp".
        LocalDateTime.of(y, mo.coerceIn(1, 12), d, h % 24, mi % 60, s % 60)
            .atZone(zone).toInstant().toEpochMilli() + ms
    } catch (e: Exception) {
        LogLine.NO_TIME
    }

    // Continuation markers: stack frames and structured-payload fragments that belong to the line above.
    private val CONTINUATION = Pattern.compile(
        "^(\\s+|at \\S|\\.\\.\\. \\d+ more|Caused by:|Suppressed:|\\}|\\]|\"|\\| )",
    )

    /**
     * True when [raw] looks like part of a multi-line record (a stack frame, "... N more", a
     * `Caused by:`, or an indented / brace-/quote-led payload fragment). The loader treats such a line
     * as a continuation only when it ALSO carries no timestamp, so a genuinely new (timestamped) line
     * is never swallowed into the block above.
     */
    fun looksLikeContinuation(raw: String): Boolean {
        if (raw.isEmpty()) return false
        return CONTINUATION.matcher(raw).find()
    }
}
