package com.example.logview

/**
 * One parsed source line of a log. The displayed text is always [raw] (authentic — copy fidelity);
 * [level] / [timestampMillis] are parsed metadata used only for coloring, filtering, and time
 * navigation.
 *
 * Multi-line records (stack traces, pretty-printed payloads) are modeled as a **block**: a primary
 * line ([isContinuation] == false) followed by its continuation lines ([isContinuation] == true).
 * [blockStart] points every line at its block's primary-line model index, so folding a block is just
 * hiding the continuation rows whose [blockStart] is collapsed.
 */
class LogLine(
    /** 1-based line number in the source stream (stable; survives filtering/folding). */
    val lineNumber: Int,
    val raw: String,
    /** The ANSI-stripped whole line (computed once by the model; parsing/filter/search use this). */
    val display: String,
    val level: LogLevel,
    /** Epoch millis parsed from the line, or [NO_TIME] when the line carries no timestamp. */
    val timestampMillis: Long,
    val isContinuation: Boolean,
    /** True if [raw] contains ANSI escape codes — passed in by the model, which already scanned [raw]. */
    val hasAnsi: Boolean,
    /** Offset in [display] (the clean text) where the message body begins (see [ParsedLine.messageStart]). */
    messageStart: Int = 0,
    /** Thread/context captured by a user format, shown in the Thread column ("" when none). */
    val thread: String = "",
) {
    /** Model index of this line's block-primary line; set by [LogTableModel] as lines are appended. */
    var blockStart: Int = -1

    val hasTime: Boolean get() = timestampMillis != NO_TIME

    // The display-derived fields below are lazy: the model holds every appended line, but only the ~50
    // visible rows are ever rendered, so non-rendered lines never pay to format their time/message.

    /** Formatted clock time for the Time column ("" when the line has no timestamp). */
    val timeText: String by lazy { if (hasTime) LogStructure.formatTime(timestampMillis) else "" }

    // The Message column shows the body after the parsed prefix (timestamp + thread + level go to their
    // own columns). [message] is the clean text; [messageRaw] keeps ANSI (recovered at the same offset).
    val message: String by lazy {
        (if (messageStart in 1..display.length) display.substring(messageStart) else display).trimStart()
    }
    val messageRaw: String by lazy {
        if (hasAnsi) AnsiText.rawSubstringFromClean(raw, messageStart).trimStart() else message
    }
    val messageHasAnsi: Boolean by lazy { hasAnsi && AnsiText.hasAnsi(messageRaw) }

    /** Cached ANSI spans for the parsed-view Message body (only computed for rendered ANSI lines). */
    val messageSpans: List<AnsiText.Span> by lazy { AnsiText.parse(messageRaw) }

    /** Cached ANSI spans for the raw-view whole line (only computed for rendered ANSI lines). */
    val rawSpans: List<AnsiText.Span> by lazy { AnsiText.parse(raw) }

    companion object {
        const val NO_TIME: Long = Long.MIN_VALUE
    }
}
