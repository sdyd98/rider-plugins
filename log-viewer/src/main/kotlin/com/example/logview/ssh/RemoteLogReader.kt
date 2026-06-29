package com.example.logview.ssh

import com.example.logview.ByteLineSplitter
import com.example.logview.LogReader
import com.jcraft.jsch.ChannelExec
import java.io.InputStream

/**
 * Tails a remote log over SSH. The initial read runs `tail -n N` (bounded — never pulls a multi-GB
 * remote file), and the live follow runs `tail -n 0 -F` so it keeps streaming across log rotation.
 * Both run in their own exec channel off the shared [SshConnectionManager] session.
 */
class RemoteLogReader(
    private val manager: SshConnectionManager,
    private val profile: SshProfile,
    private val remotePath: String,
    private val tailLines: Int,
) : LogReader {

    @Volatile private var closed = false
    @Volatile private var followChannel: ChannelExec? = null
    @Volatile private var followThread: Thread? = null
    private val batchSize = 2000
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        // Register this tab's use of the host; the session is dropped when the last reader closes.
        manager.acquire(profile.id)
    }

    override fun readInitial(onBatch: (List<String>) -> Unit) {
        exec("tail -n $tailLines -- ${shQuote(remotePath)}") { ins -> pump(ins, onBatch) }
    }

    override fun startTail(onAppend: (List<String>) -> Unit, onError: (Throwable) -> Unit) {
        if (closed || followThread != null) return
        val t = Thread({
            var channel: ChannelExec? = null
            try {
                if (closed) return@Thread // close() won the race before this thread ran — don't resurrect a session
                val session = manager.session(profile)
                val ch = session.openChannel("exec") as ChannelExec
                channel = ch
                // -F keeps following the path even when the server rotates the file; -n 0 = new lines only.
                ch.setCommand("tail -n 0 -F -- ${shQuote(remotePath)}")
                val ins = ch.inputStream
                followChannel = ch // publish BEFORE connect so a concurrent close() can always reach it
                ch.connect(CONNECT_TIMEOUT_MS)
                if (!closed) {
                    pump(ins, onAppend) // blocks until the channel is disconnected by close() or the stream dies
                    // pump returned but we weren't closed → the remote stream died (channel/connection lost).
                    if (!closed) onError(java.io.IOException("remote tail stream ended"))
                }
            } catch (e: Throwable) {
                // Do NOT disconnect the shared session here: sibling tabs may be tailing the same host on it,
                // and JSch Session.disconnect() would kill their channels too. A genuinely dead session is
                // dropped by the keepalive (serverAliveInterval) and rebuilt by session()'s isConnected re-check.
                if (!closed) onError(e)
            } finally {
                runCatching { channel?.disconnect() } // always reclaim the channel (covers the close()-during-connect race)
            }
        }, "logview-ssh-tail-${profile.host}")
        t.isDaemon = true
        followThread = t
        t.start()
    }

    private inline fun exec(command: String, block: (InputStream) -> Unit) {
        val channel = manager.session(profile).openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val ins = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        try {
            block(ins)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    /** Decode an stdout stream into raw lines (UTF-8), delivering each read's complete lines promptly. */
    private fun pump(ins: InputStream, onBatch: (List<String>) -> Unit) {
        val buf = ByteArray(1 shl 16)
        val splitter = ByteLineSplitter(batchSize)
        while (!closed) {
            val read = try {
                ins.read(buf)
            } catch (e: Exception) {
                break
            }
            if (read < 0) break
            if (read == 0) continue
            splitter.feed(buf, read, onBatch)
            splitter.flush(onBatch) // deliver each read's complete lines promptly (low tail latency)
        }
        splitter.flushPartial(onBatch) // EOF without trailing newline
    }

    override fun close() {
        closed = true
        followChannel?.let { runCatching { it.disconnect() } }
        followChannel = null
        followThread?.interrupt()
        followThread = null
        // Release the host once; when its ref count hits 0 the manager disconnects the SSH session.
        if (released.compareAndSet(false, true)) manager.release(profile.id)
    }
}
