package com.example.logview

import java.nio.charset.Charset

/**
 * Charsets the viewer offers for decoding a log file/stream — **UTF-8 and the Korean encodings only**
 * (this is a Korean game-dev workspace; Latin/ASCII options were dropped as unneeded).
 *
 * All offered charsets are **ASCII-transparent** — a `0x0A` byte ALWAYS means a line feed (never a
 * lead/trail byte) and `0x0D` a carriage return — because the readers split lines by scanning the raw
 * bytes for `0x0A` ([ByteLineSplitter]). UTF-8 and CP949/EUC-KR satisfy this; UTF-16/UTF-32 do NOT
 * (their code units contain `0x0A` bytes), so they are excluded — picking one would mis-split lines.
 */
object LogCharsets {

    val DEFAULT: Charset = Charsets.UTF_8

    /**
     * (label, charset) in menu order. `MS949` = `x-windows-949` = the Korean Windows code page 949,
     * which is ALSO what "ANSI" means on a Korean Windows (`native.encoding`), so the CP949 entry doubles
     * as the system/ANSI choice. JVM-missing charsets are skipped via runCatching.
     */
    val OPTIONS: List<Pair<String, Charset>> = buildList {
        add("UTF-8" to Charsets.UTF_8)
        runCatching { add("CP949 / Windows-949 (한글 · ANSI)" to Charset.forName("MS949")) }
        runCatching { add("EUC-KR (한글)" to Charset.forName("EUC-KR")) }
    }

    /** Menu label for [cs] (its friendly name if offered, else the charset's canonical name). */
    fun label(cs: Charset): String = OPTIONS.firstOrNull { it.second == cs }?.first ?: cs.displayName()
}
