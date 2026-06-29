package com.example.logview.ssh

/** How an [SshProfile] authenticates. */
enum class SshAuth { PASSWORD, KEY }

/**
 * A saved remote host the user tails logs from. Secrets are NOT stored here — the password /
 * key passphrase live in the IDE's [com.intellij.ide.passwordSafe.PasswordSafe], keyed by [id]
 * (see [SshSecrets]). This bean is serialized by [RemoteConnectionStore]; every field has a default
 * so the platform's XML serializer can construct it with a no-arg constructor.
 */
class SshProfile(
    var id: String = "",
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var user: String = "",
    var auth: SshAuth = SshAuth.PASSWORD,
    /** Path to a private key file (OpenSSH/PEM) when [auth] == [SshAuth.KEY]. */
    var privateKeyPath: String = "",
    /** Root log directories under this host; each becomes a group in the sources tree whose children
     *  are the log files inside (listed newest-first). One connection can watch several log roots. */
    var roots: MutableList<String> = mutableListOf("/var/log/"),
    /** How many trailing lines to pull on the initial read (bounds huge remote logs). */
    var tailLines: Int = 5000,
) {
    /** Label shown in the sources tree, e.g. "prod-game (user@host)". */
    fun label(): String = if (name.isNotBlank()) "$name ($user@$host)" else "$user@$host"
}
