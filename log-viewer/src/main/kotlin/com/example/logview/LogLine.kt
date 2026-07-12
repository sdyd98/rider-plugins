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
    /** Offset in [display] (the clean text) where the message body begins (see [ParsedLine.messageStart]).
     *  A field (not just a constructor param) because the cached-on-first-access getters below need it. */
    private val messageStart: Int = 0,
    /** Thread/context captured by a user format, shown in the Thread column ("" when none). */
    val thread: String = "",
) {
    /** Model index of this line's block-primary line; set by [LogTableModel] as lines are appended. */
    var blockStart: Int = -1

    /**
     * On a block-primary line: how many continuation lines follow it (maintained by [LogTableModel]
     * as lines are appended). Always 0 on continuation lines. Cached so block size / foldability /
     * range queries are O(1) — the row filter asks them for every row of every block, and a linear
     * rescan made filtering O(k²) per k-line stack trace.
     */
    var contCount: Int = 0

    val hasTime: Boolean get() = timestampMillis != NO_TIME

    // The display-derived fields below are computed on first access and cached in plain nullable
    // fields, NOT `by lazy`: only the ~50 visible rows are ever rendered, and every `by lazy` costs
    // a SynchronizedLazyImpl + a captured lambda PER FIELD PER LINE — at the 1M-line cap that was
    // ~380 MB of pure wrapper overhead (measured on a 3 GB load). They are only read on the EDT
    // (render / vim / detail), so unsynchronized caching is safe.
    private var _timeText: String? = null
    private var _message: String? = null
    private var _messageRaw: String? = null
    private var _messageHasAnsi: Boolean? = null
    private var _messageSpans: List<AnsiText.Span>? = null
    private var _rawSpans: List<AnsiText.Span>? = null

    /** Formatted clock time for the Time column ("" when the line has no timestamp). */
    val timeText: String
        get() = _timeText ?: (if (hasTime) LogStructure.formatTime(timestampMillis) else "").also { _timeText = it }

    // The Message column shows the body after the parsed prefix (timestamp + thread + level go to their
    // own columns). [message] is the clean text; [messageRaw] keeps ANSI (recovered at the same offset).
    val message: String
        get() = _message
            ?: (if (messageStart in 1..display.length) display.substring(messageStart) else display).trimStart()
                .also { _message = it }

    val messageRaw: String
        get() = _messageRaw
            ?: (if (hasAnsi) AnsiText.rawSubstringFromClean(raw, messageStart).trimStart() else message)
                .also { _messageRaw = it }

    val messageHasAnsi: Boolean
        get() = _messageHasAnsi ?: (hasAnsi && AnsiText.hasAnsi(messageRaw)).also { _messageHasAnsi = it }

    /** Cached ANSI spans for the parsed-view Message body (only computed for rendered ANSI lines). */
    val messageSpans: List<AnsiText.Span>
        get() = _messageSpans ?: AnsiText.parse(messageRaw).also { _messageSpans = it }

    /** Cached ANSI spans for the raw-view whole line (only computed for rendered ANSI lines). */
    val rawSpans: List<AnsiText.Span>
        get() = _rawSpans ?: AnsiText.parse(raw).also { _rawSpans = it }

    companion object {
        const val NO_TIME: Long = Long.MIN_VALUE
    }
}
