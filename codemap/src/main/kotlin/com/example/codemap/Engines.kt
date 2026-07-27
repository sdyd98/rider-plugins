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

    /** A failure the engine reported in its own output, rather than through the exit code. */
    fun errorFrom(stdout: String): String? = null
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
    override fun noteFrom(stdout: String, answerFile: File): JsonObject? {
        val text = runCatching { answerFile.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return NoteRequest.objectIn(stdout)
        return NoteRequest.objectIn(text)
    }
}
