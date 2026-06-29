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
            thread = if ("thread" in groups) m.group("thread") else null,
            messageStart = if (msgStart >= 0) msgStart else -1,
        )
    }

    /** Captured pieces for one line; nulls/-1 mean "not provided — use the heuristic for this part". */
    class Match(val time: String?, val level: String?, val thread: String?, val messageStart: Int)

    companion object {
        private val FIELD = Pattern.compile("%\\{(time|level|thread|message)}")

        // Common timestamp shapes (ISO / bare clock / syslog) so `%{time}` captures the whole stamp even
        // when it contains a space and isn't followed by a distinctive literal.
        private const val TS =
            "(?:\\d{4}[-/]\\d\\d[-/]\\d\\d[ T]\\d\\d:\\d\\d:\\d\\d(?:[.,]\\d{1,9})?" +
                "|\\d\\d:\\d\\d:\\d\\d(?:[.,]\\d{1,9})?" +
                "|[A-Z][a-z]{2} +\\d{1,2} \\d\\d:\\d\\d:\\d\\d)"

        private fun defaultCapture(field: String): String = when (field) {
            "message" -> ".*"
            "time" -> TS
            else -> "[^\\s\\[\\]{}()|]+" // level / thread: bounded by whitespace OR any delimiter ([](){}|)
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

        // ---- Region picker: build a regex from clicked token → field assignments ----

        private val BRACKETS = mapOf('[' to ']', '(' to ')', '{' to '}', '<' to '>')

        /** Delimiters that also split tokens (so non-space-separated logs — `a|b`, `[x][y]` — are clickable).
         *  Timestamp-internal chars (`: - . / ,`) are deliberately NOT here, so a stamp stays one field. */
        private const val DELIMS = "[](){}|"

        /** Split [line] into tokens at whitespace AND [DELIMS]; each delimiter char is its own token. */
        fun tokenize(line: String): List<FormatToken> {
            val toks = ArrayList<FormatToken>()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c.isWhitespace()) {
                    i++
                    continue
                }
                if (c in DELIMS) {
                    toks.add(FormatToken(c.toString(), i, i + 1))
                    i++
                    continue
                }
                val start = i
                while (i < line.length && !line[i].isWhitespace() && line[i] !in DELIMS) i++
                toks.add(FormatToken(line.substring(start, i), start, i))
            }
            return toks
        }

        /**
         * Build a named-capture regex from a sample [line] and a token→field [assign] map (field ∈
         * time/level/thread/message), produced by clicking tokens in the region picker. Assigned tokens
         * become captures (contiguous same-field tokens merge; a single bracket-wrapped token keeps its
         * brackets as literals); gaps and unassigned tokens become literals; `message` captures to end.
         */
        fun buildRegex(line: String, tokens: List<FormatToken>, assign: Map<Int, String>): String {
            val sb = StringBuilder("^")
            val used = HashSet<String>()
            var pos = 0
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                appendLiteral(sb, line.substring(pos, tok.start)) // gap (whitespace/delimiters) before token
                val field = assign[i]
                when {
                    field == "message" && "message" !in used -> {
                        sb.append("(?<message>.*)")
                        used.add("message")
                        pos = line.length
                        i = tokens.size
                    }
                    field != null && field !in used -> { // time / level / thread
                        var j = i
                        while (j + 1 < tokens.size && assign[j + 1] == field) j++ // merge contiguous same-field
                        appendFieldCapture(sb, field, line.substring(tokens[i].start, tokens[j].end))
                        used.add(field)
                        pos = tokens[j].end
                        i = j + 1
                    }
                    else -> { // unassigned (or a duplicate/used) token
                        // A delimiter/punctuation token (e.g. `|` `[` `]`) is a literal; a value token is a
                        // wildcard, so skipping a field (e.g. thread) still generalizes to other lines.
                        if (tok.text.none { it.isLetterOrDigit() }) appendLiteral(sb, tok.text) else sb.append("\\S+")
                        pos = tok.end
                        i++
                    }
                }
            }
            if (pos < line.length) appendLiteral(sb, line.substring(pos))
            return sb.toString()
        }

        /** Emit one field capture for [span] (the joined text of the field's tokens). */
        private fun appendFieldCapture(sb: StringBuilder, field: String, span: String) {
            val open = span.firstOrNull()
            val close = open?.let { BRACKETS[it] }
            if (close != null && span.length >= 2 && span.last() == close) {
                // a single bracket-wrapped token, e.g. [worker-3] / (INFO) → brackets stay literal
                appendLiteral(sb, open.toString())
                sb.append("(?<").append(field).append(">[^\\").append(close).append("]+)")
                appendLiteral(sb, close.toString())
            } else {
                sb.append("(?<").append(field).append(">").append(defaultCapture(field)).append(")")
            }
        }
    }
}

/** One non-whitespace token of a sample line, with its char range — for the visual region picker. */
data class FormatToken(val text: String, val start: Int, val end: Int)
