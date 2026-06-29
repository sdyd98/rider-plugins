package com.example.logview.ssh

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** One entry returned by an SFTP directory listing (for the remote file browser). */
class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long, val mtimeSec: Long)

/**
 * Application service that owns the live JSch [Session]s, one per [SshProfile] id, opened lazily and
 * reused across reads/tails. Secrets are pulled from [SshSecrets] at connect time. Sessions are torn
 * down on [dispose] (app shutdown).
 *
 * Host-key checking is disabled (`StrictHostKeyChecking=no`): this is a developer tool pointed at
 * machines the user already controls, and managing a known_hosts UI is out of scope for v1.
 */
class SshConnectionManager : Disposable {

    private val jsch = JSch()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val addedKeys = HashSet<String>()        // touched only under jschLock
    private val jschLock = Any()                     // serializes the shared JSch instance (addIdentity/getSession)
    private val connectLocks = ConcurrentHashMap<String, Any>() // per-profile so distinct hosts connect in parallel
    private val refCounts = ConcurrentHashMap<String, Int>()    // profile id -> open tail sessions using it
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun session(profile: SshProfile): Session {
        sessions[profile.id]?.let { if (it.isConnected) return it }
        // Lock per profile id (not the whole manager) so one slow/hung host doesn't head-of-line-block
        // every other tab's connect for up to the connect timeout.
        synchronized(connectLocks.computeIfAbsent(profile.id) { Any() }) {
            sessions[profile.id]?.let { if (it.isConnected) return it }
            val session = connect(profile)
            sessions[profile.id] = session
            fireStatusChanged()
            return session
        }
    }

    /** True if a live session for [profileId] is currently connected (for the sources-tree status dot). */
    fun isConnected(profileId: String): Boolean = sessions[profileId]?.isConnected == true

    /** A reader registers/unregisters its use of a host; the session is dropped when the count hits 0. */
    fun acquire(profileId: String) {
        refCounts.merge(profileId, 1) { a, b -> a + b }
    }

    fun release(profileId: String) {
        val remaining = refCounts.compute(profileId) { _, cur -> if (cur == null || cur <= 1) null else cur - 1 }
        if (remaining == null) disconnect(profileId) // last tail tab for this host closed → disconnect
    }

    fun addStatusListener(listener: () -> Unit) { statusListeners.add(listener) }
    fun removeStatusListener(listener: () -> Unit) { statusListeners.remove(listener) }
    private fun fireStatusChanged() { statusListeners.forEach { runCatching { it() } } }

    private fun connect(profile: SshProfile): Session {
        // Identity registration + Session creation touch the single shared JSch instance — serialize
        // just that, then do the blocking network connect OUTSIDE every lock.
        val session = synchronized(jschLock) {
            registerIdentity(profile)
            jsch.getSession(profile.user, profile.host, profile.port)
        }
        if (profile.auth == SshAuth.PASSWORD) {
            session.setPassword(SshSecrets.getSecret(profile.id) ?: "")
        }
        session.setConfig(Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "publickey,password,keyboard-interactive")
        })
        // Detect a silently dropped / half-open TCP link so isConnected flips false and session() rebuilds it.
        session.serverAliveInterval = 15_000
        session.serverAliveCountMax = 3
        session.connect(CONNECT_TIMEOUT_MS)
        return session
    }

    /** Register the profile's private key with JSch once. Caller must hold [jschLock]. */
    private fun registerIdentity(profile: SshProfile) {
        val keyPath = profile.privateKeyPath
        if (profile.auth != SshAuth.KEY || keyPath.isBlank() || keyPath in addedKeys) return
        val passphrase = SshSecrets.getSecret(profile.id)
        if (passphrase.isNullOrEmpty()) jsch.addIdentity(keyPath) else jsch.addIdentity(keyPath, passphrase)
        addedKeys.add(keyPath) // mark loaded only after addIdentity succeeds → a failed load stays retryable
    }

    /** Verify the credentials by connecting and immediately disconnecting (for the dialog's "Test"). */
    fun testConnection(profile: SshProfile) {
        val s = connect(profile)
        try {
            s.openChannel("session").also { it.connect(CONNECT_TIMEOUT_MS); it.disconnect() }
        } finally {
            s.disconnect()
        }
    }

    /** List a remote directory over SFTP (for the file browser). Blocks — call off-EDT. */
    fun listDir(profile: SshProfile, dir: String): List<RemoteEntry> {
        val channel = session(profile).openChannel("sftp") as ChannelSftp
        channel.connect(CONNECT_TIMEOUT_MS)
        try {
            val result = ArrayList<RemoteEntry>()
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(dir.ifBlank { "." }) as java.util.Vector<ChannelSftp.LsEntry>
            for (e in entries) {
                val name = e.filename
                if (name == "." || name == "..") continue
                result.add(RemoteEntry(name, e.attrs.isDir, e.attrs.size, e.attrs.mTime.toLong()))
            }
            // Directories first (by name), then files newest-first (rotated logs → current log on top).
            return result.sortedWith(
                compareByDescending<RemoteEntry> { it.isDirectory }
                    .thenBy { if (it.isDirectory) it.name.lowercase() else "" }
                    .thenByDescending { if (it.isDirectory) 0L else it.mtimeSec }
                    .thenBy { it.name.lowercase() },
            )
        } finally {
            channel.disconnect()
        }
    }

    /** Run a shell command and return its stdout (blocks until the command finishes — call off-EDT). */
    fun exec(profile: SshProfile, command: String): String {
        val channel = session(profile).openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setErrStream(null)
        val out = channel.inputStream
        channel.connect(CONNECT_TIMEOUT_MS)
        try {
            return out.readBytes().toString(Charsets.UTF_8)
        } finally {
            channel.disconnect()
        }
    }

    /** Absolute paths of `.log` (and rotated `.log.*`) files under [dir], up to [maxDepth] (via `find`). */
    fun findLogs(profile: SshProfile, dir: String, maxDepth: Int): List<String> {
        val target = shQuote(dir.ifBlank { "." })
        val cmd = "find $target -maxdepth $maxDepth -type f \\( -name '*.log' -o -name '*.log.*' \\) 2>/dev/null | head -n 5000"
        return exec(profile, cmd).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    fun disconnect(id: String) {
        val removed = sessions.remove(id)
        if (removed != null) {
            runCatching { removed.disconnect() }
            fireStatusChanged()
        }
    }

    override fun dispose() {
        sessions.values.forEach { runCatching { it.disconnect() } }
        sessions.clear()
    }

    companion object {
        fun getInstance(): SshConnectionManager =
            ApplicationManager.getApplication().getService(SshConnectionManager::class.java)
    }
}
