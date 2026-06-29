package com.example.logview.ssh

import com.intellij.openapi.project.Project
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager

/**
 * Bridges Rider's built-in **SSH Configurations** (Settings → Tools → SSH Configurations — shared with
 * remote interpreters, deployment, databases, …) into our [SshProfile]s, so the user can reuse a host
 * they already set up instead of re-entering it. Reads via the platform's unified SSH-config API; all
 * calls are wrapped so a platform that lacks the API degrades to "no configs" rather than failing.
 */
object RiderSshConfigs {

    /** The user's saved SSH configs (metadata is in-memory; safe on the EDT). Empty if unavailable. */
    fun list(project: Project): List<SshConfig> =
        runCatching { SshConfigManager.getInstance(project).configs }.getOrDefault(emptyList())

    /** (label, prefilled profile) pairs for the import picker — callers never touch the platform type. */
    fun importable(project: Project): List<Pair<String, SshProfile>> =
        list(project).map { label(it) to toProfile(it) }

    /** Rider's SSH configs as ready-to-use profiles (shown directly in the sources tree). Their secret
     *  (password / passphrase) is a PROTECTED Rider API we can't read, so it's prompted once on first
     *  connect and cached in our own [SshSecrets], keyed by the stable id below. */
    fun profiles(project: Project): List<SshProfile> = list(project).map { toProfile(it) }

    /** A short label for the picker, e.g. "prod-game (ubuntu@10.0.0.12)". */
    fun label(config: SshConfig): String {
        val name = config.presentableShortName.ifBlank { "" }
        val addr = "${config.username.orEmpty().ifBlank { "?" }}@${config.host}"
        return if (name.isNotBlank() && !name.contains(config.host)) "$name ($addr)" else addr
    }

    /** Map a platform [SshConfig] to an [SshProfile] — metadata only (no secret). The id is STABLE
     *  (host:port:user) so a prompted secret + browsed roots persist across tree rebuilds. */
    fun toProfile(config: SshConfig): SshProfile = SshProfile(
        id = "rider:${config.host}:${config.port}:${config.username.orEmpty()}",
        name = config.presentableShortName.ifBlank { config.host },
        host = config.host,
        port = config.port.takeIf { it in 1..65535 } ?: 22,
        user = config.username.orEmpty(),
        auth = if (config.authType.name.contains("KEY", ignoreCase = true)) SshAuth.KEY else SshAuth.PASSWORD,
        privateKeyPath = config.keyPath.orEmpty(),
        roots = mutableListOf("/"),
        tailLines = 5000,
    )
}
