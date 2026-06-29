package com.example.logview

import java.util.regex.Pattern

/**
 * A user-defined line format that maps a raw log line to the Time / Level / Message columns, applied
 * BEFORE the built-in heuristic ([LogParser]) — the heuristic stays as the fallback, so logs still
 * parse out of the box when no user format matches.
 *
 * Two input modes (auto-detected by [of]):
 *  - **template**: literal text + `%{field}` placeholders (field ∈ `time|level|thread|message`), e.g.
 *    `%{time} [%{thread}] (%{level}) %{message}`. Literal text matches literally, runs of whitespace
 *    flexibly (`\s+`), and each field gets a sensible default capture. Compiled to a named-capture regex.
 *  - **regex**: a raw named-capture regex with any subset of `(?<time>…)(?<level>…)(?<thread>…)(?<message>…)`.
 *
 * All fields are optional; only the groups present in the pattern feed their columns (others fall back
 * to the heuristic for that line).
 */
class LineFormat private constructor(
    /** The user's original template/regex text (shown in the settings list). */
    val source: String,
    val isRegex: Boolean,
    private val pattern: Pattern?,
    /** Which of time/level/thread/message the compiled pattern actually captures. */
    val groups: Set<String>,
    /** Compile error message, or null when [valid]. */
    val error: String?,
) {
    val valid: Boolean get() = pattern != null

    /** Apply to the head of a line. Null = no match → caller tries the next format, then the heuristic. */
    fun apply(head: String): Match? {
        val p = pattern ?: return null
        val m = p.matcher(head)
        if (!m.find()) return null
        val msgStart = if ("message" in groups) m.start("message") else -1
        return Match(
            time = if ("time" in groups) m.group("time") else null,
            level = if ("level" in groups) m.group("level") else null,
            messageStart = if (msgStart >= 0) msgStart else -1,
        )
    }

    /** Captured pieces for one line; nulls/-1 mean "not provided — use the heuristic for this part". */
    class Match(val time: String?, val level: String?, val messageStart: Int)

    companion object {
        private val FIELD = Pattern.compile("%\\{(time|level|thread|message)}")

        /** Pre-filled in the settings when the user has no formats yet — a common Logback-style template. */
        const val DEFAULT_TEMPLATE = "%{time} [%{thread}] %{level} %{message}"

        // Common timestamp shapes (ISO / bare clock / syslog) so `%{time}` captures the whole stamp even
        // when it contains a space and isn't followed by a distinctive literal.
        private const val TS =
            "(?:\\d{4}[-/]\\d\\d[-/]\\d\\d[ T]\\d\\d:\\d\\d:\\d\\d(?:[.,]\\d{1,9})?" +
                "|\\d\\d:\\d\\d:\\d\\d(?:[.,]\\d{1,9})?" +
                "|[A-Z][a-z]{2} +\\d{1,2} \\d\\d:\\d\\d:\\d\\d)"

        private fun defaultCapture(field: String): String = when (field) {
            "message" -> ".*"
            "time" -> TS
            else -> "[^\\s\\]\\)]+" // level / thread: a token bounded by whitespace or a ] / ) literal
        }

        /** Template if it contains a `%{…}` placeholder, else a raw regex. Blank text → an inert format. */
        fun of(text: String): LineFormat =
            if (text.isBlank()) LineFormat(text, false, null, emptySet(), "비어 있음")
            else if (text.contains("%{")) fromTemplate(text) else fromRegex(text)

        fun fromTemplate(template: String): LineFormat {
            val sb = StringBuilder("^")
            val groups = LinkedHashSet<String>()
            val m = FIELD.matcher(template)
            var last = 0
            while (m.find()) {
                appendLiteral(sb, template.substring(last, m.start()))
                val field = m.group(1)
                if (groups.add(field)) {
                    sb.append("(?<").append(field).append(">").append(defaultCapture(field)).append(")")
                } else {
                    appendLiteral(sb, m.group()) // a repeated field can't be a 2nd group — match it literally
                }
                last = m.end()
            }
            appendLiteral(sb, template.substring(last))
            val pat = runCatching { Pattern.compile(sb.toString()) }
            return LineFormat(template, false, pat.getOrNull(), groups, pat.exceptionOrNull()?.message)
        }

        private val NAMED_GROUP = Pattern.compile("\\(\\?<(time|level|thread|message)>")

        fun fromRegex(regex: String): LineFormat {
            val pat = runCatching { Pattern.compile(regex) }.getOrNull()
                ?: return LineFormat(regex, true, null, emptySet(), "정규식 오류")
            // Scan the regex SOURCE for our named groups (Matcher has no name-listing API pre-JDK20).
            val present = LinkedHashSet<String>()
            val nm = NAMED_GROUP.matcher(regex)
            while (nm.find()) present.add(nm.group(1))
            return LineFormat(regex, true, pat, present, null)
        }

        /** Append [lit] to the regex: whitespace runs → `\s+`, other chars escaped. */
        private fun appendLiteral(sb: StringBuilder, lit: String) {
            var i = 0
            while (i < lit.length) {
                val c = lit[i]
                if (c.isWhitespace()) {
                    sb.append("\\s+")
                    while (i < lit.length && lit[i].isWhitespace()) i++
                } else {
                    if (c in "\\.[]{}()*+-?^\$|") sb.append('\\')
                    sb.append(c)
                    i++
                }
            }
        }
    }
}
