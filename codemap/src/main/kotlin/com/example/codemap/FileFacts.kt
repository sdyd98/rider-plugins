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
        val wanted = anchors.mapNotNull { a ->
            squeeze(a).removeSuffix("{").takeIf { it.isNotEmpty() }?.let { a to it }
        }
        if (wanted.isEmpty()) return emptyMap()

        val lines = runCatching { readLines(file) }.getOrNull() ?: return emptyMap()
        val norm = lines.map { squeeze(it) }

        val firstLine = HashMap<String, Int>()
        val counts = HashMap<String, Int>()
        norm.indices.forEach { i ->
            // A signature is not always one line. Visual Studio's own brace style puts `{` on the next line,
            // and a long parameter list wraps across several — so the anchor is looked for in a WINDOW that
            // starts here, with whitespace flattened so tabs and wrapping stop mattering.
            val window = buildString {
                for (j in i until minOf(i + WINDOW, norm.size)) append(norm[j])
            }
            val firstLen = norm[i].length
            wanted.forEach { (original, needle) ->
                val at = window.indexOf(needle)
                // The match has to BEGIN on this line — strictly inside it. At exactly firstLen the match
                // starts where this line ends, which means it belongs to the next one; allowing it let a
                // blank line above a signature claim the signature's hit.
                if (at in 0 until firstLen) {
                    firstLine.putIfAbsent(original, i + 1)
                    counts[original] = (counts[original] ?: 0) + 1
                }
            }
        }
        return firstLine.mapValues { (k, v) -> Anchor(v, counts[k] ?: 1) }
    }

    /** How many lines a signature may span before we stop looking. Four covers a wrapped parameter list. */
    private const val WINDOW = 4

    /**
     * Whitespace removed entirely, so the shape of the file stops mattering.
     *
     * Tabs versus spaces, a parameter list wrapped over four lines, two spaces after a comma — none of those
     * change which function a signature names, and every one of them used to mean the anchor was never
     * found. Dropping whitespace on both sides rather than collapsing it is what lets a wrapped signature
     * match one written on a single line: joining `HandleLogin(` to `const uint8_t* body` cannot help
     * inventing a space that the recorded anchor does not have.
     */
    private fun squeeze(text: String): String = text.filterNot { it.isWhitespace() }

    /**
     * The file's lines, decoded by what the bytes say they are.
     *
     * A codebase opened from a `.sln` is a Windows codebase, and Windows editors save UTF-16 often enough
     * that hard-coding UTF-8 turns a whole file into replacement characters — after which no anchor matches
     * anything. Only the BOM is trusted; guessing a legacy code page from content is the kind of judgement
     * this plugin does not make.
     */
    /**
     * UTF-16 recognised by its NUL bytes when there is no BOM to say so.
     *
     * Not a guess about the text: C++ source is overwhelmingly ASCII, and ASCII in UTF-16 puts a zero byte
     * beside every character — a pattern no single-byte encoding produces. Which half holds the zeros gives
     * the endianness. Legacy code pages are NOT guessed at; there is no comparable tell for those, and
     * inventing one would be the kind of judgement this plugin refuses to make.
     */
    private fun utf16WithoutBom(bytes: ByteArray): java.nio.charset.Charset {
        val sample = minOf(bytes.size, 1024)
        if (sample < 4) return Charsets.UTF_8
        var evenNul = 0
        var oddNul = 0
        for (i in 0 until sample) if (bytes[i] == 0.toByte()) if (i % 2 == 0) evenNul++ else oddNul++
        val threshold = sample / 4
        return when {
            oddNul > threshold && evenNul <= oddNul / 4 -> Charsets.UTF_16LE
            evenNul > threshold && oddNul <= evenNul / 4 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
    }

    private fun readLines(file: File): List<String> {
        val bytes = file.readBytes()
        val (charset, offset) = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            else -> utf16WithoutBom(bytes) to 0
        }
        return String(bytes, offset, bytes.size - offset, charset).split('\n').map { it.removeSuffix("\r") }
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
