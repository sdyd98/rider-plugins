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
        if (symbol.isNotBlank()) return functionPrompt(relPath, symbol, question, existing)
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
            appendLine("이 저장소의 C++ 코드를 읽고, 요청된 시나리오의 '패킷 시퀀스'를 만들어라.")
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
            appendLine("이건 패킷 시퀀스다 — 뼈대가 패킷이어야 한다:")
            appendLine("- **받은 패킷에서 시작**해서, 그 패킷이 유발하는 패킷들을 순서대로 따라가라.")
            appendLine("- 패킷 단계에는 packet 에 코드에 있는 상수 이름을 원문 그대로 쓴다")
            appendLine("  (CS_LOGIN_REQ, ClientPacket::LoginReq 등). 이름을 만들어내지 마라.")
            appendLine("- id 는 코드가 값을 주는 경우에만 쓴다 (0x01, 12, enum 값). 없으면 생략해라.")
            appendLine("- 방향은 from/to 로 드러난다. 서버→클라이언트 패킷도 kind:\"return\" 이 아니라")
            appendLine("  그냥 반대 방향 패킷이다 — packet 을 쓰면 그렇게 읽힌다.")
            appendLine("- 패킷과 패킷 **사이의 내부 처리는 한 줄씩만** 넣어라. 다음에 어떤 패킷이 나가는지를")
            appendLine("  바꾸는 것만 (검증 실패로 끊김, DB 조회 결과에 따른 분기, 상태 전이). call 을 쓴다.")
            appendLine("- 패킷이 하나도 없는 시나리오라면 flows 를 빈 배열로 내보내라. 억지로 만들지 마라.")
            appendLine()
            appendLine("규칙:")
            appendLine("- name 은 이 흐름을 부를 짧은 이름. 요청된 시나리오를 그대로 써도 된다.")
            appendLine("- 참가자(from/to)는 실제 클래스·엔드포인트 이름을 써라 (Client, PlayerSession, World).")
            appendLine("- kind: process = 한 객체가 혼자 하는 일(to 는 비우거나 from 과 같게), note = 객체와")
            appendLine("  무관한 설명 한 줄(description 만).")
            appendLine("- description 은 그 단계가 왜 일어나는지 한 문장. 화면의 단계 목록에 그대로 나온다.")
            appendLine("- steps 는 실행 순서대로. 코드에서 확인할 수 없는 단계는 넣지 마라.")
            appendLine()
            appendLine(WRITING)
        }

    /**
     * One function, and only that function.
     *
     * A narrow request needs a narrow answer: the store upserts the `functions` entry and leaves the rest of
     * the note alone, so asking for the whole schema here would invite an agent to re-decide the file's
     * summary, packets and threading as a side effect of one question — and to lose whatever it did not
     * bother to repeat.
     */
    private fun functionPrompt(relPath: String, symbol: String, question: String, existing: JsonObject?): String =
        buildString {
            appendLine("이 저장소의 C++ 코드에서 함수 하나만 다시 분석해라.")
            appendLine()
            appendLine("파일: $relPath  (같은 이름의 .h/.cpp 짝이 있으면 둘 다 읽어라)")
            appendLine("대상 함수: $symbol")
            if (question.isNotBlank()) {
                appendLine()
                appendLine("개발자가 덧붙인 것 — 이것에 답하는 것이 최우선이다:")
                appendLine("  $question")
            }
            existingFunction(existing, symbol)?.let {
                appendLine()
                appendLine("현재 저장된 내용이다. 새로 쓰는 것이 아니라 이것을 고쳐라 —")
                appendLine("코드와 달라진 부분만 바로잡고, 여전히 맞는 내용은 그대로 다시 내보내라.")
                appendLine(it)
            }
            appendLine()
            appendLine("출력 형식 — 오직 이 JSON 객체 하나만. 이 함수 하나만 담아라.")
            appendLine(FUNCTION_SCHEMA)
            appendLine()
            appendLine("규칙:")
            appendLine("- anchor 는 시그니처 줄을 원문 그대로 복사해라. 이걸로 위치를 찾으므로 파일에 있어야 한다.")
            appendLine("- 다른 함수나 파일 요약을 손대지 마라. 플러그인이 이 함수만 갈아 끼운다.")
            appendLine("- 확인 못 한 키는 생략해라.")
            appendLine()
            appendLine(WRITING)
        }

    val FUNCTION_SCHEMA = """
        {
          "functions": [
            { "name": "HandleLogin",
              "anchor": "void PlayerSession::HandleLogin(const uint8_t* body, size_t len) {",
              "purpose": "한 줄",
              "thread": "", "locks": [], "calls": [], "effects": [], "gotchas": [] }
          ]
        }
    """.trimIndent()

    /** What the note already says about [symbol], so a re-analysis corrects instead of reinventing. */
    private fun existingFunction(existing: JsonObject?, symbol: String): String? =
        (existing?.get("functions") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.get("name")?.takeIf { n -> n.isJsonPrimitive }?.asString == symbol }
            ?.toString()

    private fun existingFlowNames(existing: JsonObject?): List<String> =
        (existing?.get("flows") as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        }.orEmpty()

    val FLOW_SCHEMA = """
        {
          "flows": [
            { "name": "로그인 → 월드 입장",
              "steps": [
                {"from":"Client",        "to":"PlayerSession", "packet":"ClientPacket::LoginReq", "id":"0x01",
                 "description":"디스패처가 헤더의 id 로 HandleLogin 을 고른다"},
                {"from":"PlayerSession", "to":"PlayerSession", "call":"ValidateToken", "kind":"process",
                 "description":"실패하면 Close 로 끊고 아래 패킷은 나가지 않는다"},
                {"from":"PlayerSession", "to":"AccountDb",     "call":"LoadAccount",
                 "description":"playerId 가 0 이면 인증 실패로 본다"},
                {"from":"PlayerSession", "to":"Client",        "packet":"ServerPacket::LoginAck", "id":"0x81",
                 "description":"m_state 를 Playing 으로 올린 직후에 보낸다"},
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
            appendLine("확인해서 틀린 게 아니라면 지우지 마라.")
            appendLine()
            // "고쳐라"만 말하면 불완전한 노트는 영원히 불완전한 채로 돌아온다 — 있는 내용이 맞으니까.
            // 비어 있는 것을 채우는 것도 고치는 일이라고 명시해야 한다.
            appendLine("**빠진 것을 채우는 것도 고치는 일이다.** 아래 노트에 없는 키 중 소스에서 확인할 수")
            appendLine("있는 것은 이번에 채워라. 특히:")
            appendLine(missingHint(existing))
            appendLine(condense(existing))
        }
        appendLine()
        appendLine("출력 형식 — 오직 JSON 객체 하나만. 설명도 코드펜스도 붙이지 마라.")
        appendLine(SCHEMA)
        appendLine()
        appendLine("규칙:")
        appendLine("- **functions 는 필수다.** 파일의 모든 함수를 선언 순서로, 각각 anchor 와 purpose 한 줄.")
        appendLine("  게터·생성자·소멸자도 빠뜨리지 마라. 목차에 구멍이 있으면 읽는 사람은 매번 \"분석이 안")
        appendLine("  된 건가, 별거 아닌 건가\"를 의심하게 된다.")
        appendLine("- 그 외에는 소스에서 확인한 것만 써라. 확인 못 한 키는 생략해라. 추측으로 채우지 마라.")
        appendLine("- anchor 는 시그니처 줄을 원문 그대로 복사해라. 이걸로 위치를 찾으므로 파일에 실제로 있어야 한다.")
        appendLine("- files/hashes/analyzedAt/analyzedCommit/_manual 은 쓰지 마라. 플러그인이 기록한다.")
        appendLine()
        appendLine(WRITING)
    }

    /**
     * How to write the one-liners, because the reader is a person mid-analysis.
     *
     * These fields are read while someone is trying to understand code they did not write, usually with a
     * concrete question in mind ("who mutates this?", "can this block?", "what happens if it fails?"). A
     * sentence that restates the signature costs them a line and answers nothing. The rules below are all
     * aimed at one thing: say what the reader cannot see at a glance.
     *
     * Examples do the work that adjectives cannot, so each rule carries a bad line and a good one.
     */
    val WRITING = """
        설명 쓰는 법 — 읽는 사람은 이 코드를 처음 보고, 머릿속에 구체적인 질문이 있다
        ("누가 이걸 바꾸나", "여기서 막힐 수 있나", "실패하면 어떻게 되나").

        1. 이름을 되풀이하지 마라. 시그니처에 이미 있는 것은 설명이 아니다.
           나쁨: "HandleLogin — 로그인을 처리한다"
           좋음: "토큰을 검증하고 m_playerId 를 채운 뒤 World::Enter 까지 간다"

        2. 무엇을 바꾸는지 이름으로 말해라. 멤버·상태·전송 패킷을 실제 식별자로.
           나쁨: "세션 상태를 갱신한다"
           좋음: "m_state 를 Authenticating → Playing 으로 바꾸고 LoginAck 을 보낸다"

        3. 빠져나가는 경로가 있으면 그것을 적어라. 조기 반환·예외·실패 분기는 읽는 사람이
           가장 먼저 알아야 하는 것이다.
           좋음: "m_closed 가 이미 true 면 아무것도 하지 않고 반환한다"

        4. 막히거나 잠그는 곳은 반드시 밝혀라. 어느 락을, 어디까지 쥐는지.
           좋음: "m_sessionLock 을 쥔 채로 World::Leave 를 호출한다 (락 순서 주의)"

        5. 한 줄로 써라. 두 문장이 필요하면 두 번째 문장은 gotchas 로 보내라.

        6. "이 함수는", "~하는 역할을 한다" 같은 군더더기는 빼라. 동사로 시작해라.

        7 최후의 기준: 그 줄을 읽고 소스를 열지 않아도 다음 질문을 고를 수 있으면 잘 쓴 것이다.
    """.trimIndent()

    /**
     * What the stored note is missing, named out loud.
     *
     * A correction prompt tells an agent to keep what is still right, and an incomplete note IS still right
     * — so without this it comes back just as incomplete, forever. Naming the empty keys turns "고쳐라" into
     * something that can close the gap. Only keys the source can actually answer are listed; the plugin does
     * not know whether a file has packets, so it says "if there are any".
     */
    private fun missingHint(existing: JsonObject): String {
        val functions = (existing.get("functions") as? JsonArray)?.size() ?: 0
        return buildList {
            if (functions == 0) {
                add("  - functions 가 비어 있다. 이 파일의 모든 함수를 이번에 채워라. 가장 중요하다.")
            } else {
                add("  - functions 가 $functions 개 기록돼 있다. 파일에 그보다 많으면 빠진 것을 추가해라.")
            }
            if (existing.get("threading") == null) add("  - threading 이 없다. 스레드/락이 있으면 채워라.")
            if ((existing.get("packets") as? JsonArray)?.size() ?: 0 == 0) {
                add("  - packets 가 없다. 이 파일이 패킷을 다루면 id·방향·핸들러를 채워라.")
            }
        }.joinToString("\n")
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
          "flows":      [{"name":"", "steps":[{"from":"","to":"","packet":"","id":"","description":""}]}],
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
