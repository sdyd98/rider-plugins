package com.example.logview

import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * Reads a local log file and tails appended lines with a polled byte cursor.
 *
 * Why a byte cursor (not a `BufferedReader` or `WatchService`): we must (a) resume exactly where the
 * last read stopped, (b) buffer a trailing partial line until its newline arrives, and (c) detect log
 * **rotation/truncation** (the file shrinking) and re-read from the top. Polling the length is the
 * most portable way to do all three across local disks and mapped network drives.
 *
 * UTF-8 safety: bytes are only consumed up to a `\n` (0x0A, which never appears inside a multi-byte
 * UTF-8 sequence), so every emitted line decodes cleanly; the remainder is held as raw bytes.
 */
class LocalLogReader(
    private val path: Path,
    charset: Charset = Charsets.UTF_8,
    /** Tail-first threshold override (tests use a tiny value; production keeps the default). */
    private val tailOpenBytes: Long = TAIL_OPEN_BYTES,
) : LogReader {

    private val file = path.toFile()
    private var offset = 0L
    // One splitter persists across polls so a line split across two reads resumes correctly.
    private val splitter = ByteLineSplitter(charset = charset)

    @Volatile private var follow: Thread? = null
    @Volatile private var closed = false
    @Volatile private var initialCancelled = false

    // Initial-read progress + tail-first bookkeeping (read by the panel via the LogReader interface).
    @Volatile private var initialTotal = 0L
    @Volatile private var initialRead = 0L
    @Volatile private var skippedHead = 0L

    private val pollMs = 300L

    override fun readInitial(onBatch: (List<String>) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            offset = 0
            // Tail-first open: a huge file starts from its LAST chunk instead of parsing tens of
            // millions of lines the 1M-line model cap would immediately trim away (the remote reader
            // already opens with a bounded `tail -n N` — this is the local equivalent).
            if (len > tailOpenBytes) {
                raf.seek(len - tailOpenBytes)
                skipToNextLine(raf) // drop the partial line at the seek point
                offset = raf.filePointer
                skippedHead = offset
            }
            initialRead = 0
            initialTotal = maxOf(1, len - offset)
            splitter.reset()
            pump(raf, onBatch, honorCancel = true)
            // Resume exactly where pump stopped reading (NOT length() — on an actively-written file the
            // length can already exceed it, which would skip the gap). The partial tail stays in `pending`.
            offset = raf.filePointer
        }
    }

    override fun initialProgress(): Float =
        if (initialTotal > 0) (initialRead.toFloat() / initialTotal).coerceIn(0f, 1f) else -1f

    override fun skippedHeadBytes(): Long = skippedHead

    override fun cancelInitial() {
        initialCancelled = true
    }

    /** Advance the file pointer just past the next `\n` (start of the first complete line). */
    private fun skipToNextLine(raf: RandomAccessFile) {
        val buf = ByteArray(8192)
        while (true) {
            val mark = raf.filePointer
            val n = raf.read(buf)
            if (n <= 0) return
            for (i in 0 until n) {
                if (buf[i] == '\n'.code.toByte()) {
                    raf.seek(mark + i + 1)
                    return
                }
            }
        }
    }

    override fun startTail(
        onAppend: (List<String>) -> Unit,
        onError: (Throwable) -> Unit,
        onState: (TailState) -> Unit,
    ) {
        if (closed || follow != null) return
        // Local polling never "disconnects" — a missing file is just re-checked next tick — so the
        // state is LIVE for the tail's whole life.
        onState(TailState.LIVE)
        // A cancelled initial read leaves an unread gap; the tail skips it and follows from the end.
        if (initialCancelled) {
            offset = file.length()
            splitter.reset()
            initialCancelled = false
        }
        val t = Thread({
            while (!closed) {
                try {
                    Thread.sleep(pollMs)
                    if (closed) break
                    pollOnce(onAppend)
                } catch (ie: InterruptedException) {
                    break // normal shutdown
                } catch (e: Exception) {
                    // Transient file error (e.g. FileNotFound / sharing violation during the log-rotation
                    // rename window, common on Windows) — skip this poll and retry next tick rather than
                    // killing the tail permanently. A genuine deletion is handled by file.exists() in pollOnce.
                }
            }
        }, "logview-tail-${file.name}")
        t.isDaemon = true
        follow = t
        t.start()
    }

    private fun pollOnce(onAppend: (List<String>) -> Unit) {
        if (!file.exists()) return
        val len = file.length()
        if (len < offset) {
            // File rotated / truncated — restart from the top so the new content shows.
            offset = 0
            splitter.reset()
        }
        if (len == offset) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val batch = ArrayList<String>()
            pump(raf, onBatch = { lines -> batch.addAll(lines) })
            offset = raf.filePointer
            if (batch.isNotEmpty()) onAppend(batch)
        }
    }

    /** Read everything from the raf's current pointer, emitting complete lines and buffering the tail.
     *  [honorCancel] lets the initial read stop early on [cancelInitial] (tail polls ignore it). */
    private fun pump(raf: RandomAccessFile, onBatch: (List<String>) -> Unit, honorCancel: Boolean = false) {
        val buf = ByteArray(1 shl 16)
        while (!closed && !(honorCancel && initialCancelled)) {
            val read = raf.read(buf)
            if (read <= 0) break
            initialRead += read
            splitter.feed(buf, read, onBatch)
        }
        // Don't flush the partial tail: it stays buffered in the splitter so the next poll resumes it.
        splitter.flush(onBatch)
    }

    override fun close() {
        closed = true
        follow?.interrupt()
        follow = null
    }

    private companion object {
        /** Files larger than this open from their last chunk (~1M lines at ~100 B/line — the model
         *  cap) instead of parsing the whole file; the skipped size is surfaced in the status bar. */
        const val TAIL_OPEN_BYTES = 100L * 1024 * 1024
    }
}
