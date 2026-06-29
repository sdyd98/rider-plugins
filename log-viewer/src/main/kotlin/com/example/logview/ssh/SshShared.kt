package com.example.logview.ssh

/** Connect/exec timeout for every JSch channel (session open, exec, sftp). One source of truth. */
internal const val CONNECT_TIMEOUT_MS = 15_000

/** POSIX single-quote [s] so spaces / shell metacharacters in a remote path are passed literally. */
internal fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
