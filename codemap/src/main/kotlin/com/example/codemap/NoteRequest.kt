package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What we ask an agent for, and how we read the answer back — identical for every engine.
 *
 * The prompt is the plugin's only opinion about note contents, and it is deliberately all instruction and
 * no interpretation: read the file, answer the developer's question, fill only what the source shows.
 * Whether Claude Code or Codex runs it changes nothing here, which is the point — a note written by one
 * must be indistinguishable from a note written by the other.
 */
object NoteRequest {

    /**
     * The instruction for one file. [question] is the developer's and outranks everything else.
     *
     * [existing] is the note already on disk, sent verbatim when there is one. That is what turns
     * 재분석 into a correction rather than a rewrite: an agent that cannot see the current note has no
     * choice but to produce a whole new one, and everything it happens not to mention would be lost.
     */
    fun prompt(relPath: String, question: String, symbol: String, existing: JsonObject?): String = buildString {
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
        if (existing != null) {
            appendLine()
            appendLine("현재 저장된 노트다. 새로 쓰는 것이 아니라 이것을 고쳐라 —")
            appendLine("코드와 달라진 부분만 바로잡고, 여전히 맞는 내용은 그대로 유지해서 다시 내보내라.")
            appendLine("빠뜨린 함수를 추가하는 것은 좋지만, 확인해서 틀린 게 아니라면 지우지 마라.")
            appendLine(condense(existing))
        }
        appendLine()
        appendLine("출력 형식 — 오직 JSON 객체 하나만. 설명도 코드펜스도 붙이지 마라.")
        appendLine(SCHEMA)
        appendLine()
        appendLine("규칙:")
        appendLine("- 소스에서 확인한 것만 써라. 확인 못 한 키는 아예 생략해라. 추측으로 채우지 마라.")
        appendLine("- functions 는 선언 순서로, 모든 함수에 purpose 한 줄. 게터·생성자도 빠뜨리지 마라.")
        appendLine("- anchor 는 시그니처 줄을 원문 그대로 복사해라. 이걸로 위치를 찾으므로 파일에 실제로 있어야 한다.")
        appendLine("- files/hashes/analyzedAt/analyzedCommit/_manual 은 쓰지 마라. 플러그인이 기록한다.")
    }

    /** How much of an existing note is worth sending before it costs more than it saves. */
    private const val BUDGET = 12_000

    /**
     * The existing note, trimmed to fit a prompt.
     *
     * Provenance and the record of human edits are dropped (the plugin owns both). A note that is still
     * too large loses the deep per-function fields but keeps every function's name, anchor and purpose —
     * so the agent still knows what exists and cannot silently drop half the index.
     */
    private fun condense(note: JsonObject): String {
        val trimmed = note.deepCopy()
        (setOf("files", "hashes", "analyzedAt", "analyzedCommit", "_manual")).forEach(trimmed::remove)
        val full = trimmed.toString()
        if (full.length <= BUDGET) return full

        val slim = JsonArray()
        (trimmed.get("functions") as? JsonArray)?.forEach { el ->
            val f = el as? JsonObject ?: return@forEach
            slim.add(JsonObject().apply {
                listOf("name", "anchor", "purpose").forEach { k -> f.get(k)?.let { add(k, it) } }
            })
        }
        trimmed.add("functions", slim)
        return trimmed.toString()
    }

    val SCHEMA = """
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

    /** The outermost JSON object in [text], tolerating fences and prose around it. */
    fun objectIn(text: String): JsonObject? {
        val t = text.trim().let { raw ->
            if (!raw.startsWith("```")) raw
            else raw.removePrefix("```json").removePrefix("```").substringBeforeLast("```").trim()
        }
        val start = t.indexOf('{').takeIf { it >= 0 } ?: return null
        val end = t.lastIndexOf('}').takeIf { it > start } ?: return null
        return runCatching { JsonParser.parseString(t.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    /** A binary in an explicit setting, then the usual install locations, then PATH. */
    fun discover(name: String, explicit: String?, pathEnv: String?, wellKnown: List<String>): java.io.File? {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            return expand(p).takeIf { it.canExecute() }
        }
        wellKnown.forEach { p -> expand(p).takeIf { it.canExecute() }?.let { return it } }
        pathEnv.orEmpty().split(':').forEach { dir ->
            if (dir.isNotBlank()) java.io.File(dir, name).takeIf { it.canExecute() }?.let { return it }
        }
        return null
    }

    private fun expand(path: String): java.io.File =
        java.io.File(if (path.startsWith("~")) System.getProperty("user.home") + path.drop(1) else path)
}
