package com.example.logview

import java.io.Closeable

/**
 * Source-agnostic line feed for a log: a one-shot initial read of the existing content followed by an
 * optional live "tail" of appended lines. Implementations are [LocalLogReader] (a polled byte cursor
 * over a local file) and [com.example.logview.ssh.RemoteLogReader] (SSH `tail -F`).
 *
 * All methods block / run on background threads — never call them on the EDT. Batches are raw lines
 * (no trailing newline); the consumer parses + appends them to the [LogTableModel] on the EDT.
 */
interface LogReader : Closeable {
    /** Read the current contents once, delivering raw lines in batches. Blocks until fully read. */
    fun readInitial(onBatch: (List<String>) -> Unit)

    /**
     * Begin following appended lines until [close]. New lines are delivered (possibly from a private
     * daemon thread) via [onAppend]; unrecoverable failures via [onError]. [onState] reports tail
     * connection transitions (remote readers reconnect with backoff and flip RECONNECTING ↔ LIVE;
     * local polling never disconnects). Idempotent-safe: calling it after [close] is a no-op.
     */
    fun startTail(
        onAppend: (List<String>) -> Unit,
        onError: (Throwable) -> Unit,
        onState: (TailState) -> Unit = {},
    )
}

/** Tail connection state, surfaced in the status bar. */
enum class TailState {
    /** The tail is connected and streaming (or idle-but-healthy). */
    LIVE,

    /** The tail lost its stream and is retrying with backoff; appended lines during the outage are lost. */
    RECONNECTING,
}
