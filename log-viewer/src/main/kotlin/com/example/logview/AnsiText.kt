package com.example.logview

/**
 * Minimal ANSI SGR (color) parsing so logs that embed escape codes (colored app/console output) render
 * as actual colors — a Better-Stack-style "native ANSI rendering" — instead of showing raw `ESC[31m`
 * garbage. Handles the common subset: reset(0), bold(1/22), 16 standard+bright foregrounds (30-37,
 * 90-97, 39 default), and 256/truecolor foregrounds (38;5;n, 38;2;r;g;b). Background codes are ignored.
 *
 * Cheap when absent: [hasAnsi] is a single `indexOf`, and [strip]/[parse] short-circuit when there is
 * no escape char, so the (overwhelmingly common) plain-text lines pay almost nothing.
 */
object AnsiText {

    private const val ESC = ''

    /** A run of text sharing one style. [fgRgb] null = use the renderer's default foreground. */
    class Span(val text: String, val fgRgb: Int?, val bold: Boolean)

    fun hasAnsi(s: String): Boolean = s.indexOf(ESC) >= 0

    /** If a CSI escape (`ESC [`) begins at [i] in [s], return the index just past it; else return [i]. */
    private fun skipEscape(s: String, i: Int): Int {
        if (!(i + 1 < s.length && s[i] == ESC && s[i + 1] == '[')) return i
        var j = i + 2
        while (j < s.length && !s[j].isLetter()) j++ // skip params
        if (j < s.length) j++ // skip the final letter
        return j
    }

    /** The text with all ANSI/CSI escape sequences removed (for filtering, search, display width). */
    fun strip(s: String): String {
        if (s.indexOf(ESC) < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val j = skipEscape(s, i)
            if (j != i) i = j else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    /** The substring of [raw] (KEEPING ANSI codes) that begins at clean-text index [cleanStart] — used to
     *  recover the colored message portion after the prefix was located on the ANSI-stripped text. */
    fun rawSubstringFromClean(raw: String, cleanStart: Int): String {
        if (cleanStart <= 0) return raw
        if (raw.indexOf(ESC) < 0) return raw.substring(cleanStart.coerceIn(0, raw.length))
        var clean = 0
        var i = 0
        while (i < raw.length && clean < cleanStart) {
            val j = skipEscape(raw, i)
            if (j != i) i = j else { clean++; i++ }
        }
        return raw.substring(i)
    }

    /** Parse [s] into styled spans. For a plain line returns a single default-styled span. */
    fun parse(s: String): List<Span> {
        if (s.indexOf(ESC) < 0) return listOf(Span(s, null, false))
        val spans = ArrayList<Span>()
        val buf = StringBuilder()
        var fg: Int? = null
        var bold = false
        var i = 0
        fun flush() { if (buf.isNotEmpty()) { spans.add(Span(buf.toString(), fg, bold)); buf.setLength(0) } }
        while (i < s.length) {
            val c = s[i]
            if (c == ESC && i + 1 < s.length && s[i + 1] == '[') {
                val end = s.indexOf('m', i)
                if (end < 0) { i += 2; continue } // not an SGR sequence — skip the '[' and move on
                flush()
                val params = s.substring(i + 2, end)
                var j = 0
                val codes = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
                while (j < codes.size) {
                    when (val code = codes[j]) {
                        0 -> { fg = null; bold = false }
                        1 -> bold = true
                        22 -> bold = false
                        39 -> fg = null
                        in 30..37 -> fg = STD[code - 30]
                        in 90..97 -> fg = BRIGHT[code - 90]
                        38 -> {
                            if (j + 2 < codes.size && codes[j + 1] == 5) { fg = xterm256(codes[j + 2]); j += 2 }
                            else if (j + 4 < codes.size && codes[j + 1] == 2) { fg = rgb(codes[j + 2], codes[j + 3], codes[j + 4]); j += 4 }
                        }
                        else -> {} // backgrounds / others ignored
                    }
                    j++
                }
                i = end + 1
            } else {
                buf.append(c); i++
            }
        }
        flush()
        return if (spans.isEmpty()) listOf(Span("", null, false)) else spans
    }

    private fun rgb(r: Int, g: Int, b: Int) = (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

    private fun xterm256(n: Int): Int = when {
        n < 16 -> if (n < 8) STD[n] else BRIGHT[n - 8]
        n in 16..231 -> {
            val v = n - 16
            val r = v / 36; val g = (v % 36) / 6; val b = v % 6
            fun ch(x: Int) = if (x == 0) 0 else 55 + x * 40
            rgb(ch(r), ch(g), ch(b))
        }
        else -> { val l = 8 + (n - 232) * 10; rgb(l, l, l) }
    }

    // Standard + bright 16-color palette (slightly muted so they read on both light and dark themes).
    private val STD = intArrayOf(0x3B3B3B, 0xD24545, 0x4C9F70, 0xC8A03B, 0x4C7FD2, 0xB060C0, 0x3FA6A6, 0xBFBFBF)
    private val BRIGHT = intArrayOf(0x808080, 0xE06C6C, 0x5CB585, 0xE0B84A, 0x6F9FD8, 0xC586C0, 0x56C4C4, 0xF0F0F0)
}
