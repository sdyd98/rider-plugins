package com.example.codemap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Driving the Claude Code CLI headlessly, so 분석 요청 can just *run* instead of queueing.
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
object ClaudeCli {

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
    fun discover(
        explicit: String? = null,
        pathEnv: String? = System.getenv("PATH"),
        wellKnown: List<String> = WELL_KNOWN,
    ): File? {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            return expand(p).takeIf { it.canExecute() }
        }
        wellKnown.forEach { p -> expand(p).takeIf { it.canExecute() }?.let { return it } }
        pathEnv.orEmpty().split(':').forEach { dir ->
            if (dir.isNotBlank()) File(dir, "claude").takeIf { it.canExecute() }?.let { return it }
        }
        return null
    }

    private fun expand(path: String): File =
        File(if (path.startsWith("~")) System.getProperty("user.home") + path.drop(1) else path)

    /**
     * The headless invocation.
     *
     * `Read`/`Grep`/`Glob` only: enough to read the file, follow a symbol through the codebase and
     * answer, and not enough to change anything.
     */
    fun command(bin: File, prompt: String): List<String> = listOf(
        bin.absolutePath,
        "-p", prompt,
        "--allowedTools", "Read", "Grep", "Glob",
        "--output-format", "json",
    )

    /** The instruction sent for one file. [question] is the developer's, and outranks everything else. */
    fun prompt(relPath: String, question: String, symbol: String, existing: Boolean): String = buildString {
        appendLine("이 저장소의 C++ 파일을 읽고 '코드맵 노트'를 JSON으로 만들어라.")
        appendLine()
        appendLine("대상 파일: $relPath  (같은 이름의 .h/.cpp 짝이 있으면 둘 다 읽어라)")
        if (symbol.isNotBlank()) appendLine("범위: $symbol 함수만")
        if (question.isNotBlank()) {
            appendLine()
            appendLine("개발자가 남긴 질문 — 이것에 답하는 것이 최우선이다:")
            appendLine("  $question")
            appendLine("일반적인 요약으로 때우지 말고, 코드에서 확인한 근거로 답하고 그 답을 gotchas에 넣어라.")
        }
        if (existing) appendLine("\n기존 노트가 있으니 코드가 바뀐 부분을 반영해 다시 써라.")
        appendLine()
        appendLine("출력 형식 — 오직 JSON 객체 하나만. 설명도 코드펜스도 붙이지 마라.")
        appendLine(SCHEMA)
        appendLine()
        appendLine("규칙:")
        appendLine("- 소스에서 확인한 것만 써라. 확인 못 한 키는 아예 생략해라. 추측으로 채우지 마라.")
        appendLine("- functions 는 선언 순서로, 모든 함수에 purpose 한 줄. 게터·생성자도 빠뜨리지 마라.")
        appendLine("- anchor 는 시그니처 줄을 원문 그대로 복사해라. 이걸로 위치를 찾으므로 파일에 실제로 있어야 한다.")
        appendLine("- files/hashes/analyzedAt/analyzedCommit 는 쓰지 마라. 플러그인이 기록한다.")
    }

    private val SCHEMA = """
        {
          "purpose": "이 파일이 뭐 하는 것인지 2~3줄",
          "roleInSystem": "시스템 어디에 앉아있는지",
          "classes":    [{"name":"", "role":""}],
          "entryPoints":[{"symbol":"", "note":""}],
          "keyState":   [{"member":"", "note":""}],
          "threading":  {"model":"", "affinity":"", "locks":[{"name":"","guards":"","order":""}]},
          "packets":    [{"id":"", "dir":"in|out", "handler":"", "sentBy":""}],
          "dependsOn":  [{"target":"", "why":""}],
          "usedBy":     [{"source":"", "context":""}],
          "flows":      [{"name":"", "steps":[{"from":"","to":"","call":"","kind":"return"}]}],
          "dataSources":[{"kind":"xlsx|db|config|proto", "ref":"", "note":""}],
          "gotchas":    ["함정, 호출 순서 제약, 수명 문제"],
          "functions":  [{"name":"", "anchor":"시그니처 줄 원문", "purpose":"한 줄",
                          "thread":"", "locks":[], "calls":[], "effects":[], "gotchas":[]}]
        }
    """.trimIndent()

    /**
     * Pull the note out of a `--output-format json` response.
     *
     * The CLI answers with an envelope whose `result` holds the model's text; that text should be the
     * note, but a fenced block or a stray sentence around it is common enough to be worth surviving.
     * Anything that is not a JSON object in the end returns null — better a visible failure than a
     * half-parsed note on disk.
     */
    fun extractNote(stdout: String): JsonObject? {
        val outer = objectIn(stdout) ?: return null
        // An envelope must never be written as if it were the note: it parses perfectly well and would
        // land on disk as a "note" full of session ids and token counts.
        val inner = if (isEnvelope(outer)) {
            outer.get("result")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        } else {
            return outer
        }
        return objectIn(inner)?.takeIf { !isEnvelope(it) }
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

    /** The outermost JSON object in [text], tolerating fences and prose around it. */
    private fun objectIn(text: String): JsonObject? {
        val t = text.trim().let { raw ->
            if (!raw.startsWith("```")) raw
            else raw.removePrefix("```json").removePrefix("```").substringBeforeLast("```").trim()
        }
        val start = t.indexOf('{').takeIf { it >= 0 } ?: return null
        val end = t.lastIndexOf('}').takeIf { it > start } ?: return null
        return runCatching { JsonParser.parseString(t.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    /** The failure text a `--output-format json` envelope carries, if it says it failed. */
    fun errorOf(stdout: String): String? = runCatching {
        val o = JsonParser.parseString(stdout).asJsonObject
        if (o.get("is_error")?.asBoolean == true) o.get("result")?.asString ?: "알 수 없는 오류" else null
    }.getOrNull()
}
