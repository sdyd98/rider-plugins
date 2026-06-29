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
class LocalLogReader(private val path: Path, charset: Charset = Charsets.UTF_8) : LogReader {

    private val file = path.toFile()
    private var offset = 0L
    // One splitter persists across polls so a line split across two reads resumes correctly.
    private val splitter = ByteLineSplitter(charset = charset)

    @Volatile private var follow: Thread? = null
    @Volatile private var closed = false

    private val pollMs = 300L

    override fun readInitial(onBatch: (List<String>) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            offset = 0
            splitter.reset()
            pump(raf, onBatch)
            // Resume exactly where pump stopped reading (NOT length() — on an actively-written file the
            // length can already exceed it, which would skip the gap). The partial tail stays in `pending`.
            offset = raf.filePointer
        }
    }

    override fun startTail(onAppend: (List<String>) -> Unit, onError: (Throwable) -> Unit) {
        if (closed || follow != null) return
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
            pump(raf) { lines ->
                batch.addAll(lines)
            }
            offset = raf.filePointer
            if (batch.isNotEmpty()) onAppend(batch)
        }
    }

    /** Read everything from the raf's current pointer, emitting complete lines and buffering the tail. */
    private fun pump(raf: RandomAccessFile, onBatch: (List<String>) -> Unit) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val read = raf.read(buf)
            if (read <= 0) break
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
}
