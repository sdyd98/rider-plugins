package com.example.logview

import java.io.ByteArrayOutputStream

/**
 * Splits a byte stream into UTF-8 text lines without ever cutting a multi-byte character: bytes are
 * only decoded up to a `\n` (0x0A, which never appears inside a multi-byte UTF-8 sequence). A trailing
 * partial line is held in [pending] until its newline arrives (or [flushPartial] is called at EOF).
 *
 * Shared by [LocalLogReader] (one splitter that persists across polls, so a line split across two
 * polls resumes correctly) and [RemoteLogReader] (a fresh splitter per exec stream). Not thread-safe:
 * use one splitter per pump loop.
 */
class ByteLineSplitter(private val batchSize: Int = 2000) {

    private val pending = ByteArrayOutputStream() // trailing partial line (raw bytes, no newline yet)
    private val out = ArrayList<String>(batchSize)

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
        if (pending.size() > 0) { out.add(decode(pending.toByteArray())); pending.reset() }
        flush(onBatch)
    }

    /** Drop any buffered partial line (e.g. after a log rotation/truncation restarts from the top). */
    fun reset() {
        pending.reset()
        out.clear()
    }

    private fun decode(bytes: ByteArray): String {
        var n = bytes.size
        if (n > 0 && bytes[n - 1] == CR) n-- // strip CR from CRLF
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    companion object {
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
