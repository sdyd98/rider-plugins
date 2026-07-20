package com.example.logview

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Splits a byte stream into text lines without ever cutting a multi-byte character: bytes are only
 * decoded up to a `\n` (0x0A) and then decoded with [charset]. A trailing partial line is held in
 * [pending] until its newline arrives (or [flushPartial] is called at EOF).
 *
 * [charset] must be ASCII-transparent (a 0x0A byte always means a line feed, never a lead/trail byte):
 * UTF-8, CP949/EUC-KR, and the 8-bit Latin sets all qualify, but UTF-16/UTF-32 do NOT — see [LogCharsets].
 *
 * Shared by [LocalLogReader] (one splitter that persists across polls, so a line split across two
 * polls resumes correctly) and [RemoteLogReader] (a fresh splitter per exec stream). Not thread-safe:
 * use one splitter per pump loop.
 */
class ByteLineSplitter(private val batchSize: Int = 2000, private val charset: Charset = Charsets.UTF_8) {

    private val pending = ByteArrayOutputStream() // trailing partial line (raw bytes, no newline yet)
    private val out = ArrayList<String>(batchSize)

    // Charset-validity probe over the head of the stream (see charsetLooksWrong). Complete lines only:
    // a line never ends mid-character in a correctly-chosen charset, so a strict re-decode failing on
    // one is real evidence the charset is wrong — not a chunk boundary artifact.
    private var probeRemaining = PROBE_BYTES
    @Volatile private var sawInvalid = false

    /**
     * Scan `buf[0 until len]`, emitting each complete line (CRLF/LF) via [onBatch] in chunks of
     * [batchSize]; a tail with no `\n` stays buffered in [pending] until the next [feed].
     */
    fun feed(buf: ByteArray, len: Int, onBatch: (List<String>) -> Unit) {
        var start = 0
        for (i in 0 until len) {
            if (buf[i] == NL) {
                pending.write(buf, start, i - start)
                out.add(decode(pending.toByteArray()))
                pending.reset()
                start = i + 1
                if (out.size >= batchSize) flush(onBatch)
            }
        }
        if (start < len) pending.write(buf, start, len - start)
    }

    /** Hand off the complete lines accumulated so far (call after a read, or once when the loop ends). */
    fun flush(onBatch: (List<String>) -> Unit) {
        if (out.isNotEmpty()) { onBatch(ArrayList(out)); out.clear() }
    }

    /** Emit a buffered trailing line (EOF with no final newline), then flush. Use only when done. */
    fun flushPartial(onBatch: (List<String>) -> Unit) {
        // probe = false: a truncated tail may legitimately end mid-character — not charset evidence.
        if (pending.size() > 0) { out.add(decode(pending.toByteArray(), probe = false)); pending.reset() }
        flush(onBatch)
    }

    /** True while a trailing partial line (no newline yet) is buffered. */
    fun hasPartial(): Boolean = pending.size() > 0

    /** Drop any buffered partial line (e.g. after a log rotation/truncation restarts from the top). */
    fun reset() {
        pending.reset()
        out.clear()
    }

    /**
     * True when the head of the stream (first [PROBE_BYTES] of complete lines) contained byte
     * sequences that are invalid under [charset] — the panel's signal that the charset is wrong
     * (e.g. a CP949 file decoded as the UTF-8 default). Thread-safe to read after the initial stream.
     */
    fun charsetLooksWrong(): Boolean = sawInvalid

    private fun decode(bytes: ByteArray, probe: Boolean = true): String {
        var n = bytes.size
        if (n > 0 && bytes[n - 1] == CR) n-- // strip CR from CRLF (0x0D, same byte in every supported charset)
        if (probe && !sawInvalid && probeRemaining > 0 && n > 0) {
            probeRemaining -= n
            sawInvalid = !decodesCleanly(bytes, n)
        }
        return String(bytes, 0, n, charset)
    }

    private fun decodesCleanly(bytes: ByteArray, n: Int): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, n))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    companion object {
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()

        /** Only the head is probed — enough to judge the charset without taxing multi-GB streams. */
        private const val PROBE_BYTES = 64 * 1024
    }
}
