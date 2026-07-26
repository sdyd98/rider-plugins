package com.example.codemap

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The facts side of the plugin — and the whole of it. Everything here is either read verbatim off disk
 * or asked of git; NOTHING is inferred. That line is deliberate and firm (same rule as the xlsx-editor
 * refs tools): C++ cannot be parsed correctly without a preprocessor and a compilation database, and
 * this project builds from a `.sln`, so there is no clang index to lean on. Rather than ship a
 * regex "parser" that is right most of the time, the plugin reports only what is exactly true and
 * leaves every interpretation — inheritance, call graphs, real references, roles — to the AI.
 */
object FileFacts {

    /** Line count and byte size, counted, not estimated. */
    data class Size(val lines: Int, val bytes: Long)

    data class Git(
        val lastCommit: String,
        val lastDate: String,
        val lastAuthor: String,
        val lastSubject: String,
        val commitCount: Int,
    )

    fun size(file: File): Size {
        var lines = 0
        file.bufferedReader(Charsets.UTF_8).use { r ->
            val buf = CharArray(1 shl 16)
            var n = r.read(buf)
            var sawAny = false
            var lastWasNewline = true
            while (n > 0) {
                for (i in 0 until n) {
                    sawAny = true
                    if (buf[i] == '\n') { lines++; lastWasNewline = true } else lastWasNewline = false
                }
                n = r.read(buf)
            }
            // A final line without a trailing newline still counts as a line.
            if (sawAny && !lastWasNewline) lines++
        }
        return Size(lines, file.length())
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 16)
            var n = ins.read(buf)
            while (n > 0) { md.update(buf, 0, n); n = ins.read(buf) }
        }
        return "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Where an anchor was found: 1-based line, and how many lines matched it at all. */
    data class Anchor(val line: Int, val occurrences: Int)

    /**
     * Locate each anchor in [file] by LITERAL text search — the mechanical answer to "where is this
     * function now".
     *
     * Notes store a verbatim copy of a function's signature line rather than a line number, because a
     * line number is wrong the moment anything above it changes. Searching for the text instead makes
     * the location self-correcting, and turns "not found" into an honest per-function staleness signal:
     * the signature was edited or the function is gone.
     *
     * Matching is `contains` on the trimmed anchor, so indentation and trailing whitespace don't break
     * it. [Anchor.occurrences] is reported rather than hidden — an anchor that matches several lines is
     * a weak anchor, and the caller should say so instead of silently picking one.
     */
    fun findAnchors(file: File, anchors: Collection<String>): Map<String, Anchor> {
        val wanted = anchors.mapNotNull { a -> a.trim().takeIf { it.isNotEmpty() }?.let { a to it } }
        if (wanted.isEmpty()) return emptyMap()
        val firstLine = HashMap<String, Int>()
        val counts = HashMap<String, Int>()
        runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    wanted.forEach { (original, needle) ->
                        if (line.contains(needle)) {
                            firstLine.putIfAbsent(original, idx + 1)
                            counts[original] = (counts[original] ?: 0) + 1
                        }
                    }
                }
            }
        }
        return firstLine.mapValues { (k, v) -> Anchor(v, counts[k] ?: 1) }
    }

    /** `#include` targets literally present in the file (see [CodemapPaths.includes] for the caveat). */
    fun includes(file: File): List<String> =
        runCatching { CodemapPaths.includes(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())

    /** Last commit + total commit count touching [relPath]. Null when git is unavailable or untracked. */
    fun git(root: File, relPath: String): Git? {
        val head = git(root, "log", "-n", "1", "--format=%H%x1f%ad%x1f%an%x1f%s", "--date=short", "--", relPath)
            ?.trim().orEmpty()
        if (head.isEmpty()) return null
        val parts = head.split('\u001f')
        if (parts.size < 4) return null
        val count = git(root, "rev-list", "--count", "HEAD", "--", relPath)?.trim()?.toIntOrNull() ?: 0
        return Git(parts[0], parts[1], parts[2], parts[3], count)
    }

    /** Commits touching [relPath] since [sinceCommit] — how far the code drifted from the note. */
    fun commitsSince(root: File, relPath: String, sinceCommit: String): Int? {
        if (sinceCommit.isBlank()) return null
        return git(root, "rev-list", "--count", "$sinceCommit..HEAD", "--", relPath)?.trim()?.toIntOrNull()
    }

    /** The commit HEAD points at, recorded on a note so drift can be measured later. */
    fun headCommit(root: File): String = git(root, "rev-parse", "HEAD")?.trim().orEmpty()

    /** The git working tree [dir] belongs to, or null when it is not in one. */
    fun gitToplevel(dir: File): File? =
        git(dir, "rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(::File)?.takeIf { it.isDirectory }

    private fun git(root: File, vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git", "-C", root.absolutePath) + args)
            .redirectErrorStream(false)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() != 0) null else out
    }.getOrNull()
}
