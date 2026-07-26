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
    fun prompt(
        relPath: String,
        question: String,
        symbol: String,
        existing: JsonObject?,
        flow: String = "",
    ): String {
        if (flow.isNotBlank()) return sequencePrompt(relPath, flow, question, existing)
        return notePrompt(relPath, question, symbol, existing)
    }

    /**
     * One scenario, drawn as a sequence diagram.
     *
     * Deliberately a NARROW request: the answer carries a single `flows` entry and nothing else, because
     * the store adds it to whatever diagrams are already there. Asking for a whole note here would put an
     * agent in the position of re-deciding the rest of the note as a side effect of one question.
     */
    private fun sequencePrompt(relPath: String, flow: String, question: String, existing: JsonObject?): String =
        buildString {
            appendLine("이 저장소의 C++ 코드를 읽고, 요청된 시나리오 하나를 시퀀스 다이어그램으로 만들어라.")
            appendLine()
            appendLine("기준 파일: $relPath  (같은 이름의 .h/.cpp 짝이 있으면 둘 다 읽어라)")
            appendLine("시나리오: $flow")
            if (question.isNotBlank()) {
                appendLine()
                appendLine("개발자가 덧붙인 것 — 이것에 답하는 것이 최우선이다:")
                appendLine("  $question")
            }
            existingFlowNames(existing).takeIf { it.isNotEmpty() }?.let { names ->
                appendLine()
                appendLine("이미 있는 시퀀스: ${names.joinToString(", ")}")
                appendLine("이것들을 다시 만들지 마라. 요청된 시나리오 하나만 내보내라 (같은 이름이면 교체된다).")
            }
            appendLine()
            appendLine("출력 형식 — 오직 이 JSON 객체 하나만. 설명도 코드펜스도 붙이지 마라.")
            appendLine(FLOW_SCHEMA)
            appendLine()
            appendLine("규칙:")
            appendLine("- name 은 이 흐름을 부를 짧은 이름. 요청된 시나리오를 그대로 써도 된다.")
            appendLine("- 참가자(from/to)는 실제 클래스·모듈 이름을 써라. 추측한 이름을 만들지 마라.")
            appendLine("- call 은 실제 함수 이름이나 패킷 이름. 코드에서 확인한 것만.")
            appendLine("- kind 로 단계 종류를 밝혀라:")
            appendLine("    (생략)   A가 B를 호출 — 가장 흔한 경우")
            appendLine("    return   값이 돌아옴 (점선 화살표)")
            appendLine("    process  한 객체가 혼자 하는 일 (검증, 상태 변경 등). to 는 from 과 같게 두거나 비워라")
            appendLine("    note     객체와 무관한 설명 한 줄. from/to/call 없이 description 만 채워라")
            appendLine("- description 은 그 단계가 왜 일어나는지 한 문장. 화면의 단계 목록에 그대로 나온다.")
            appendLine("  call 이 '무엇을'이라면 description 은 '왜'다. 자명한 단계는 생략해도 된다.")
            appendLine("- steps 는 실행 순서대로. 코드에서 확인할 수 없는 단계는 넣지 마라.")
        }

    private fun existingFlowNames(existing: JsonObject?): List<String> =
        (existing?.get("flows") as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        }.orEmpty()

    val FLOW_SCHEMA = """
        {
          "flows": [
            { "name": "로그인 → 월드 입장",
              "steps": [
                {"kind":"note",    "description":"클라이언트가 접속을 마치고 인증을 시작하는 지점부터."},
                {"from":"Client",        "to":"PlayerSession", "call":"CS_LOGIN_REQ",
                 "description":"패킷 헤더까지 읽은 뒤 디스패처가 이 핸들러를 고른다"},
                {"from":"PlayerSession", "to":"PlayerSession", "call":"ValidateToken", "kind":"process",
                 "description":"실패하면 여기서 끊고 아래 단계로 가지 않는다"},
                {"from":"PlayerSession", "to":"AccountDb",     "call":"LoadAccount"},
                {"from":"AccountDb",     "to":"PlayerSession", "call":"Account",       "kind":"return"},
                {"from":"PlayerSession", "to":"World",         "call":"Enter",
                 "description":"m_sessionLock 을 쥔 채로 들어간다"}
              ] }
          ]
        }
    """.trimIndent()

    private fun notePrompt(relPath: String, question: String, symbol: String, existing: JsonObject?): String = buildString {
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
