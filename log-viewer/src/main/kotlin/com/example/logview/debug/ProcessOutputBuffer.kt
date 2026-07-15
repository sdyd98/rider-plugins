package com.example.logview.debug

import com.example.logview.LogReader
import com.example.logview.TailState

/**
 * Line buffer for ONE debug session's process output (what the built-in Console shows): fed raw text
 * chunks by a ProcessListener adapter ([DebugLogTabListener]), it assembles complete lines and keeps
 * the whole session history (bounded by [maxLines]) so a [ProcessLogReader] attached late — or rebuilt
 * by the panel on a charset change — replays everything and then follows live.
 *
 * Chunks are NOT line-aligned and stdout/stderr are pumped by different threads, so partial lines are
 * assembled **per base stream** — fragments of one stream can never corrupt a line of the other.
 * Listener callbacks run under the buffer lock: ordering is guaranteed, and a slow consumer
 * back-pressures the process pump exactly like the built-in console does.
 *
 * Pure JVM (no platform types) so line assembly is headless-testable.
 */
class ProcessOutputBuffer(private val maxLines: Int = DEFAULT_MAX_LINES) {

    companion object {
        const val DEFAULT_MAX_LINES = 1_000_000
    }

    private val lock = Any()
    private val lines = ArrayDeque<Pair<String, String>>() // (stream, line) — stream tags enable source filtering
    private var dropped = 0L
    private val partial = HashMap<String, StringBuilder>()
    private val listeners = ArrayList<(String, List<String>) -> Unit>()

    /** Append one raw output chunk. [stream] keys partial-line assembly (fragments of different
     *  streams can't corrupt each other) AND tags the stored lines for source filtering. */
    fun append(text: String, stream: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            val acc = partial.getOrPut(stream) { StringBuilder() }
            val complete = ArrayList<String>()
            var start = 0
            while (true) {
                val nl = text.indexOf('\n', start)
                if (nl < 0) break
                acc.append(text, start, nl)
                if (acc.isNotEmpty() && acc.last() == '\r') acc.setLength(acc.length - 1)
                complete.add(acc.toString())
                acc.setLength(0)
                start = nl + 1
            }
            if (start < text.length) acc.append(text, start, text.length)
            if (complete.isNotEmpty()) deliverLocked(stream, complete)
        }
    }

    /** Flush unterminated partial lines (the process ended mid-line). */
    fun flush() {
        synchronized(lock) {
            partial.forEach { (stream, sb) ->
                if (sb.isNotEmpty()) deliverLocked(stream, listOf(sb.toString().also { sb.setLength(0) }))
            }
        }
    }

    private fun deliverLocked(stream: String, batch: List<String>) {
        batch.forEach { lines.addLast(stream to it) }
        while (lines.size > maxLines) { lines.removeFirst(); dropped++ }
        listeners.forEach { it(stream, batch) }
    }

    /** ATOMIC snapshot + subscribe: the returned (stream, line) pairs plus every future [listener]
     *  batch is exactly the full (bounded) session output — no gap, no duplicate. */
    fun snapshotAndListen(listener: (String, List<String>) -> Unit): Pair<List<Pair<String, String>>, Long> =
        synchronized(lock) {
            listeners.add(listener)
            ArrayList(lines) to dropped
        }

    fun removeListener(listener: (String, List<String>) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    /** A fresh [LogReader] over this buffer, restricted to streams accepted by [include] — the panel's
     *  `makeReader` (charset is irrelevant: the platform already decoded the process output; a rebuild
     *  replays the accepted history, which is how the debug tab's source toggle re-filters). */
    fun newReader(include: (String) -> Boolean = { true }): LogReader = ProcessLogReader(this, include)
}

/**
 * [LogReader] over a [ProcessOutputBuffer]. `readInitial` atomically snapshots the history and starts
 * queueing live lines; `startTail` drains the queue and then forwards batches directly — the
 * initial-read/tail handoff loses and duplicates nothing. The tail never disconnects (the "source" is
 * an in-memory buffer); when the process exits, lines simply stop arriving.
 */
private class ProcessLogReader(
    private val buffer: ProcessOutputBuffer,
    private val include: (String) -> Boolean,
) : LogReader {

    private val lock = Any()
    private var pending: ArrayList<String>? = ArrayList() // non-null until startTail; then direct mode
    private var onAppend: ((List<String>) -> Unit)? = null
    private var closed = false

    // Called under the BUFFER lock; our own lock nests inside it (never the other way round).
    private val listener: (String, List<String>) -> Unit = { stream, batch ->
        if (include(stream)) {
            synchronized(lock) {
                if (!closed) {
                    val p = pending
                    if (p != null) p.addAll(batch) else onAppend?.invoke(batch)
                }
            }
        }
    }

    override fun readInitial(onBatch: (List<String>) -> Unit) {
        val (snapshot, dropped) = buffer.snapshotAndListen(listener)
        if (dropped > 0) onBatch(listOf("… [로그 뷰어] 이전 ${dropped}줄이 버퍼 한도로 생략되었습니다"))
        snapshot.asSequence().filter { include(it.first) }.map { it.second }
            .chunked(2000).forEach(onBatch)
    }

    override fun startTail(
        onAppend: (List<String>) -> Unit,
        onError: (Throwable) -> Unit,
        onState: (TailState) -> Unit,
    ) {
        onState(TailState.LIVE)
        synchronized(lock) {
            if (closed) return
            this.onAppend = onAppend
            val drained = pending
            pending = null
            // Delivered inside the lock so a concurrent listener batch cannot overtake the backlog.
            if (!drained.isNullOrEmpty()) onAppend(drained)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            onAppend = null
            pending = null
        }
        buffer.removeListener(listener)
    }
}
