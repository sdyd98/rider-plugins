package com.example.codemap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Driving the Claude Code CLI headlessly, so 분석 요청 can just *run* instead of queueing.
 *
 * The prompt itself is [NoteRequest]'s — shared with every other engine, because which agent ran must not
 * be visible in the note it produced.
 *
 * Two decisions shape this, both about staying inside the plugin's existing guarantees:
 *
 *  - **Claude gets no write tools.** It is asked for the note on stdout and the plugin writes it
 *    through [NoteStore]. That keeps provenance stamping in one place, keeps this plugin read-only
 *    towards your source (an agent with Edit could not promise that), and means a malformed answer is
 *    caught by a parse rather than landing on disk.
 *  - **No MCP.** The CLI spawned here has its own MCP configuration, which points at whatever IDE the
 *    user set up — not necessarily this one. Reading files and answering on stdout needs none of it.
 *
 * Pure functions here (discovery, command building, response extraction) so they are covered by
 * headless tests; the process execution lives in [AnalysisRunner].
 */
object ClaudeCli : AgentCli {

    override val label = "Claude Code"


    /** Where the CLI usually lands. The IDE does not inherit a login shell's PATH, so PATH alone is not enough. */
    val WELL_KNOWN = listOf(
        "~/.local/bin/claude",
        "/opt/homebrew/bin/claude",
        "/usr/local/bin/claude",
        "~/.claude/local/claude",
    )

    /**
     * Resolve the CLI: an explicit setting wins, then the well-known locations, then PATH.
     * Returns null when nothing is executable, so the UI can say so instead of failing at spawn time.
     */
    override fun discover(explicit: String?, pathEnv: String?): File? =
        discover(explicit, pathEnv, WELL_KNOWN)

    fun discover(
        explicit: String? = null,
        pathEnv: String? = System.getenv("PATH"),
        wellKnown: List<String> = WELL_KNOWN,
    ): File? = NoteRequest.discover("claude", explicit, pathEnv, wellKnown)

    /**
     * The headless invocation.
     *
     * `Read`/`Grep`/`Glob` only: enough to read the file, follow a symbol through the codebase and
     * answer, and not enough to change anything.
     */
    /** Claude answers on stdout, so the answer file is unused. The prompt goes on stdin. */
    override fun command(bin: File, answerFile: File): List<String> = command(bin)

    override fun noteFrom(stdout: String, answerFile: File): JsonObject? = extractNote(stdout)

    /** The model's text lives in the envelope's `result`. */
    override fun textFrom(stdout: String, answerFile: File): String? =
        (resultEvent(stdout) ?: NoteRequest.objectIn(stdout))
            ?.get("result")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }?.trim()

    override fun errorFrom(stdout: String): String? = errorOf(stdout)

    fun command(bin: File): List<String> = listOf(
        bin.absolutePath,
        // -p with no value: the prompt arrives on stdin.
        "-p",
        "--allowedTools", "Read", "Grep", "Glob",
        // stream-json rather than json: the same envelope arrives, but as it happens rather than only at
        // the end, which is what lets the panel say what the agent is reading right now. `--verbose` is
        // required by the CLI for streaming with -p.
        "--output-format", "stream-json",
        "--verbose",
    )

    /**
     * Pull the note out of a `--output-format json` response.
     *
     * The CLI answers with an envelope whose `result` holds the model's text; that text should be the
     * note, but a fenced block or a stray sentence around it is common enough to be worth surviving.
     * Anything that is not a JSON object in the end returns null — better a visible failure than a
     * half-parsed note on disk.
     */
    fun extractNote(stdout: String): JsonObject? {
        val outer = resultEvent(stdout) ?: NoteRequest.objectIn(stdout) ?: return null
        // An envelope must never be written as if it were the note: it parses perfectly well and would
        // land on disk as a "note" full of session ids and token counts.
        val inner = if (isEnvelope(outer)) {
            outer.get("result")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        } else {
            return outer
        }
        return NoteRequest.objectIn(inner)?.takeIf { !isEnvelope(it) }
    }

    /**
     * Is this the CLI's wrapper rather than the note?
     *
     * A string `result` is the decisive marker: that is where the CLI puts the model's text, and no
     * note in this schema has such a field. The other keys are belt and braces for shortened or
     * future envelope shapes.
     */
    private fun isEnvelope(o: JsonObject): Boolean =
        o.get("result")?.isJsonPrimitive == true ||
            o.has("session_id") || o.has("is_error") || o.has("subtype") || o.has("total_cost_usd")


    /** The failure text the final envelope carries, if it says it failed. */
    fun errorOf(stdout: String): String? = runCatching {
        val o = resultEvent(stdout) ?: JsonParser.parseString(stdout).asJsonObject
        if (o.get("is_error")?.asBoolean == true) o.get("result")?.asString ?: "알 수 없는 오류" else null
    }.getOrNull()

    /**
     * The `result` event out of a stream, or null when this output is not a stream.
     *
     * Streaming turns stdout into one JSON object per line, so the whole-text parse that a single
     * envelope allowed no longer works — the answer is the last line that says it is the result. Read
     * from the end: a `result` is final, and looking backwards finds it without walking the transcript.
     */
    private fun resultEvent(stdout: String): JsonObject? =
        stdout.lineSequence()
            .filter { it.trimStart().startsWith("{") }
            .toList()
            .asReversed()
            .asSequence()
            .mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            .firstOrNull { it.get("type")?.asStringOrNull() == "result" }

    /**
     * One streamed event as a line for the panel, or null for the events a person does not need.
     *
     * Tool calls are the useful ones: they say which file is being read and what is being searched for,
     * which is exactly the "is it stuck or is it working" question. The model's own prose is skipped —
     * it is the note being drafted, and half a note scrolling past is noise.
     */
    override fun progressOf(line: String): String? {
        val o = runCatching { JsonParser.parseString(line.trim()).asJsonObject }.getOrNull() ?: return null
        val content = o.getAsJsonObject("message")?.getAsJsonArray("content") ?: return null
        return content.mapNotNull { el ->
            val block = el.asJsonObjectOrNull() ?: return@mapNotNull null
            if (block.get("type")?.asStringOrNull() != "tool_use") return@mapNotNull null
            val input = block.getAsJsonObject("input")
            when (val tool = block.get("name")?.asStringOrNull()) {
                "Read" -> input?.get("file_path")?.asStringOrNull()?.substringAfterLast('/')?.let { "읽는 중 — $it" }
                "Grep" -> input?.get("pattern")?.asStringOrNull()?.let { "찾는 중 — ${it.take(50)}" }
                "Glob" -> input?.get("pattern")?.asStringOrNull()?.let { "파일 훑는 중 — ${it.take(50)}" }
                null -> null
                else -> tool
            }
        }.firstOrNull()
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive }?.asString

    private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject
}
