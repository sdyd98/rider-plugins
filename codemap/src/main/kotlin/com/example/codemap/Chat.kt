package com.example.codemap

import com.google.gson.JsonObject

/**
 * A conversation about one file's note.
 *
 * **The transcript lives here, not in the CLI.** Both engines advertise session resume, and only one of them
 * actually remembers: `claude -r <id>` recalls the previous turn, while `codex exec resume <id>` answers
 * "알 수 없음" to a question it was told the answer to a moment earlier. Owning the transcript makes the two
 * behave identically, survives whatever those CLIs do with their session storage, and lets the context be
 * trimmed deliberately instead of by someone else's rules. The price is re-sending the conversation each
 * turn, which prompt caching absorbs on the paying side.
 *
 * Pure data — no IDE types, no Compose — so the prompt assembly is covered by headless tests.
 */
object Chat {

    enum class Role { USER, ASSISTANT }

    data class Turn(val role: Role, val text: String)

    /** How much conversation is worth re-sending before it costs more than it answers. */
    private const val BUDGET = 24_000

    /**
     * The whole conversation as one prompt.
     *
     * The note goes in first as context and is labelled as such: this is a conversation ABOUT a file, and an
     * agent that cannot see what is already recorded will happily re-derive it — or contradict it — instead
     * of building on it.
     */
    fun prompt(relPath: String, note: JsonObject?, turns: List<Turn>, question: String): String = buildString {
        appendLine("이 저장소의 C++ 코드에 대해 개발자와 대화 중이다.")
        appendLine()
        appendLine("대상 파일: $relPath  (같은 이름의 .h/.cpp 짝이 있으면 둘 다 읽어라)")
        note?.let {
            appendLine()
            appendLine("이 파일에 대해 이미 기록된 노트다. 이미 확인된 사실이니 다시 유도하지 말고 여기서 출발해라.")
            appendLine("노트가 코드와 다르면 그 사실을 먼저 알려라.")
            appendLine(condense(it))
        }
        if (turns.isNotEmpty()) {
            appendLine()
            appendLine("지금까지의 대화다:")
            trimmed(turns).forEach { t ->
                appendLine()
                appendLine(if (t.role == Role.USER) "개발자: ${t.text}" else "너: ${t.text}")
            }
        }
        appendLine()
        appendLine("개발자의 질문:")
        appendLine("  $question")
        appendLine()
        appendLine("답하는 법:")
        appendLine("- 소스를 읽고 답해라. 확인 못 한 것은 모른다고 말해라 — 추측을 사실처럼 말하지 마라.")
        appendLine("- 근거가 되는 파일과 함수 이름을 대라. 줄 번호는 바뀌므로 이름으로.")
        appendLine("- 산문으로 답해라. JSON 도 코드펜스도 필요 없다.")
        appendLine("- 짧게. 개발자는 코드를 읽는 중이지 글을 읽는 중이 아니다.")
    }

    /**
     * The tail of the conversation that fits the budget, oldest dropped first.
     *
     * The first exchange is usually "이 파일 뭐하는 거야" and the useful context is what came after, so
     * dropping from the front keeps the part that is still being talked about.
     */
    internal fun trimmed(turns: List<Turn>, budget: Int = BUDGET): List<Turn> {
        var total = 0
        val kept = ArrayDeque<Turn>()
        turns.asReversed().forEach { t ->
            total += t.text.length
            if (total > budget && kept.isNotEmpty()) return@forEach
            kept.addFirst(t)
        }
        return kept.toList()
    }

    /** The note, minus the bookkeeping the developer never asked about. */
    private fun condense(note: JsonObject): String {
        val trimmed = note.deepCopy()
        setOf("files", "hashes", "analyzedAt", "analyzedCommit", "_manual").forEach(trimmed::remove)
        val full = trimmed.toString()
        return if (full.length <= BUDGET) full else full.take(BUDGET) + "…"
    }
}
