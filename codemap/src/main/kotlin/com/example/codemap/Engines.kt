package com.example.codemap

import com.google.gson.JsonObject
import java.io.File

/**
 * The agent CLIs the plugin can drive, behind one interface.
 *
 * Both are asked for the same thing — read the file, return the note as JSON on the way out — and neither
 * is given a tool that can change the repository. What differs is only mechanical: how the binary is
 * found, how the prompt is passed, and where the final answer ends up. Keeping that difference in one
 * small place is what makes the choice a genuine choice rather than two code paths that drift apart.
 */
interface AgentCli {
    /** Shown on the engine selector. */
    val label: String

    /** Resolve the executable, or null when it is not installed anywhere we look. */
    fun discover(explicit: String? = null, pathEnv: String? = System.getenv("PATH")): File?

    /**
     * The command line. The PROMPT IS NOT IN IT — it goes on stdin.
     *
     * An argument that starts with `-` is an option, and a note's text can start with anything: a recorded
     * gotcha beginning "-1의 값을 반환한다" made the whole prompt vanish into the parser as
     * `error: unknown option '-1의 …'`. Both CLIs take the prompt on stdin for exactly this reason, and it
     * removes the argument-length ceiling at the same time.
     *
     * [answerFile] is where the engine should leave its final message when it supports that; engines that
     * only write to stdout ignore it.
     */
    fun command(bin: File, answerFile: File): List<String>

    /** The note, read from whichever of [stdout] / [answerFile] this engine actually used. */
    fun noteFrom(stdout: String, answerFile: File): JsonObject?

    /**
     * The engine's answer as plain text — what a conversation needs, where an analysis wants JSON.
     *
     * Same wrapping, different unwrapping: the analysis path parses an object out of it and refuses anything
     * else, while a chat answer is prose and must arrive intact.
     */
    fun textFrom(stdout: String, answerFile: File): String?

    /** A failure the engine reported in its own output, rather than through the exit code. */
    fun errorFrom(stdout: String): String? = null

    /**
     * One line of the engine's output as something worth showing a person, or null to say nothing.
     *
     * An analysis of a 4,000-line file takes minutes, and a spinner that says only 분석 중 for that long
     * is indistinguishable from a hang — which is what the old ten-minute timeout was really there to
     * paper over. Showing what the agent is reading right now answers "is it stuck?" without a limit
     * that kills honest work.
     *
     * Pure, so the translation is covered by headless tests rather than by watching a spinner.
     */
    fun progressOf(line: String): String? = null
}

enum class Engine(val label: String, val cli: AgentCli) {
    CLAUDE("Claude Code", ClaudeCli),
    CODEX("Codex", CodexCli);

    companion object {
        fun of(id: String?): Engine = entries.firstOrNull { it.name == id } ?: CLAUDE
    }
}

/**
 * Codex, non-interactively.
 *
 * Better suited to this than the Claude path in two ways, both because the CLI offers them: `-s read-only`
 * enforces read-only at the sandbox level rather than by a list of allowed tools, and `-o` writes the final
 * message to a file — so the answer arrives separated from the progress log instead of wrapped in an
 * envelope that has to be unwrapped and guarded against.
 */
object CodexCli : AgentCli {

    override val label = "Codex"

    val WELL_KNOWN = listOf(
        "/opt/homebrew/bin/codex",
        "/usr/local/bin/codex",
        "~/.local/bin/codex",
        "~/.codex/bin/codex",
    )

    override fun discover(explicit: String?, pathEnv: String?): File? =
        NoteRequest.discover("codex", explicit, pathEnv, WELL_KNOWN)

    override fun command(bin: File, answerFile: File): List<String> = listOf(
        bin.absolutePath,
        "exec",
        // Read-only sandbox: the engine cannot write to the repository even if it decides to try.
        "-s", "read-only",
        // The codemap root is normally a git working tree, but it falls back to the project directory when
        // there is no repository — and Codex refuses to run outside one unless told this is fine.
        "--skip-git-repo-check",
        "--color", "never",
        "-o", answerFile.absolutePath,
        // `-` is codex's own way of saying "the instructions are on stdin".
        "-",
    )

    /** The `-o` file holds exactly the final message; stdout is the human-readable progress log. */
    override fun textFrom(stdout: String, answerFile: File): String? =
        runCatching { answerFile.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }?.trim()

    override fun noteFrom(stdout: String, answerFile: File): JsonObject? {
        val text = runCatching { answerFile.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return NoteRequest.objectIn(stdout)
        return NoteRequest.objectIn(text)
    }

    /**
     * Codex already prints a readable log; this only decides what is worth a line in the panel.
     *
     * Its timestamps and banners say nothing a person watching a spinner wants to know, and the final
     * answer is in the `-o` file rather than here — so what is left is exactly the activity log.
     */
    override fun progressOf(line: String): String? {
        val t = line.trim()
        if (t.isEmpty()) return null
        // "[2026-07-30T01:00:00] " and the startup banner: noise for this purpose.
        val body = TIMESTAMP.replace(t, "").trim()
        if (body.isEmpty() || BANNER.containsMatchIn(body)) return null
        return body.take(120)
    }

    private val TIMESTAMP = Regex("^\\[[0-9T:.+-]+Z?\\]\\s*")
    private val BANNER = Regex("^(OpenAI Codex|--------|workdir:|model:|provider:|approval:|sandbox:|reasoning)", RegexOption.IGNORE_CASE)
}
