package com.example.logview

import java.nio.charset.Charset

/**
 * Charsets the viewer offers for decoding a log file/stream.
 *
 * Restricted to **ASCII-transparent** encodings — ones where a `0x0A` byte ALWAYS means a line feed
 * (never a lead/trail byte) and `0x0D` always a carriage return — because the readers split lines by
 * scanning the raw bytes for `0x0A` ([ByteLineSplitter]). UTF-8, CP949/EUC-KR (Korean), and the 8-bit
 * Latin sets all satisfy this; UTF-16/UTF-32 do NOT (their code units contain `0x0A` bytes), so they
 * are deliberately excluded — picking one would mis-split every line.
 */
object LogCharsets {

    val DEFAULT: Charset = Charsets.UTF_8

    /** (label, charset) in menu order — Korean-relevant encodings first. JVM-missing charsets are skipped. */
    val OPTIONS: List<Pair<String, Charset>> = buildList {
        add("UTF-8" to Charsets.UTF_8)
        runCatching { add("CP949 (한글 Windows)" to Charset.forName("MS949")) }
        runCatching { add("EUC-KR" to Charset.forName("EUC-KR")) }
        add("ISO-8859-1 (Latin-1)" to Charsets.ISO_8859_1)
        add("US-ASCII" to Charsets.US_ASCII)
    }

    /** Menu label for [cs] (its friendly name if offered, else the charset's canonical name). */
    fun label(cs: Charset): String = OPTIONS.firstOrNull { it.second == cs }?.first ?: cs.displayName()

    /** Resolve a menu [label] back to its charset, or null if not one of [OPTIONS]. */
    fun byLabel(label: String): Charset? = OPTIONS.firstOrNull { it.first == label }?.second
}
