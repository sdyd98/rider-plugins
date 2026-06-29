package com.example.logview

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Structure-analysis helpers used by the viewer: pretty-print an embedded JSON payload, jump to a
 * timestamp, and format parsed times. The JSON pretty-printer re-indents by structure (no parser /
 * external dependency) so it tolerates the truncated or slightly-malformed payloads common in logs.
 */
object LogStructure {

    private val clockFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(clockFmt)

    // ---- JSON ----

    /** Show the first JSON object/array embedded in [line], re-indented, anchored at [anchor]. */
    fun showJsonPopup(line: String, anchor: Component) {
        val json = extractJson(line)
        val text = if (json != null) prettyJson(json) else "이 줄에서 JSON을 찾지 못했습니다."
        val area = JBTextArea(text).apply {
            isEditable = false
            font = LogFonts.MONO
            caretPosition = 0
        }
        val scroll = JBScrollPane(area).apply {
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(360))
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, area)
            .setTitle("JSON")
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .createPopup()
            .showInCenterOf(anchor)
    }

    /** Extract the first balanced { … } (or [ … ]) substring, respecting quoted strings. Null if none. */
    fun extractJson(s: String): String? {
        val open = s.indexOfFirst { it == '{' || it == '[' }
        if (open < 0) return null
        val openCh = s[open]
        val closeCh = if (openCh == '{') '}' else ']'
        var depth = 0
        var inStr = false
        var escaped = false
        for (i in open until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                openCh -> depth++
                closeCh -> {
                    depth--
                    if (depth == 0) return s.substring(open, i + 1)
                }
            }
        }
        return null // unbalanced (truncated payload) — fall through to "not found"
    }

    /** Re-indent JSON by structure. Not a validator — just makes nesting readable in the popup. */
    fun prettyJson(s: String): String {
        val sb = StringBuilder(s.length * 2)
        var indent = 0
        var inStr = false
        var escaped = false
        fun newline() {
            sb.append('\n')
            repeat(indent) { sb.append("  ") }
        }
        for (c in s) {
            if (inStr) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> { inStr = true; sb.append(c) }
                '{', '[' -> { sb.append(c); indent++; newline() }
                '}', ']' -> { indent = (indent - 1).coerceAtLeast(0); newline(); sb.append(c) }
                ',' -> { sb.append(c); newline() }
                ':' -> sb.append(": ")
                ' ', '\t', '\n', '\r' -> {} // drop existing whitespace; we re-add it
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---- Time jump ----

    /** Ask the user for a time (e.g. `14:30:00` or `2024-06-28 14:30:00`) → epoch millis, or null. */
    fun askTimestamp(project: Project?): Long? {
        val input = Messages.showInputDialog(
            project,
            "이동할 시각 (예: 14:30:00 또는 2024-06-28 14:30:00)",
            "시각으로 이동",
            Messages.getQuestionIcon(),
        ) ?: return null
        val millis = LogParser.parseTimestamp(input.trim())
        return if (millis == LogLine.NO_TIME) null else millis
    }

    /** First model row whose parsed timestamp is ≥ [targetMillis] (logs are ~chronological). -1 if none. */
    fun firstAtOrAfter(model: LogTableModel, targetMillis: Long): Int {
        for (i in 0 until model.rowCount) {
            val ts = model.lineAt(i).timestampMillis
            if (ts != LogLine.NO_TIME && ts >= targetMillis) return i
        }
        return -1
    }
}
