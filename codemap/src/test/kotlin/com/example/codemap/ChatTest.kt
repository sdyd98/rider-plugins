package com.example.codemap

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Assembling a conversation into one prompt.
 *
 * The transcript is ours, not the CLI's: `claude -r <id>` remembers a previous turn but
 * `codex exec resume <id>` does not — asked what it had just been told, it answers "알 수 없음". Sending the
 * whole conversation every turn is what makes the two engines behave the same, so these tests pin the
 * assembly rather than a session id.
 */
class ChatTest {

    private val note = JsonParser.parseString(
        """{"purpose":"세션","gotchas":["락 순서 주의"],
            "hashes":{"a.h":"deadbeef"},"analyzedAt":"2026-01-01","_manual":{"purpose":"손으로 고침"}}""",
    ).asJsonObject

    @Test
    fun `the note goes in as context, minus the bookkeeping`() {
        val p = Chat.prompt("Net/World.h", note, emptyList(), "락 순서가 왜 이래?")
        assertTrue(p.contains("Net/World.h"))
        assertTrue(p.contains("락 순서 주의"), "노트 내용이 문맥으로 안 들어감")
        assertTrue(p.contains("락 순서가 왜 이래?"))
        // Provenance and the human-edit record are the plugin's business.
        assertFalse(p.contains("deadbeef"))
        assertFalse(p.contains("손으로 고침"))
    }

    @Test
    fun `earlier turns are replayed, in order and labelled`() {
        val turns = listOf(
            Chat.Turn(Chat.Role.USER, "이 파일 뭐 하는 거야?"),
            Chat.Turn(Chat.Role.ASSISTANT, "세션 하나를 다룬다."),
        )
        val p = Chat.prompt("Net/World.h", null, turns, "그럼 락은?")
        assertTrue(p.contains("개발자: 이 파일 뭐 하는 거야?"))
        assertTrue(p.contains("너: 세션 하나를 다룬다."))
        assertTrue(p.indexOf("이 파일 뭐 하는 거야?") < p.indexOf("세션 하나를 다룬다."))
        // The new question comes last, so it is the thing being answered.
        assertTrue(p.lastIndexOf("그럼 락은?") > p.indexOf("세션 하나를 다룬다."))
    }

    @Test
    fun `a file with no note still starts a conversation`() {
        val p = Chat.prompt("Net/New.h", null, emptyList(), "뭐 하는 파일이야?")
        assertTrue(p.contains("Net/New.h"))
        assertFalse(p.contains("이미 기록된 노트"))
    }

    @Test
    fun `a long conversation drops its oldest turns, not its newest`() {
        val turns = (1..40).map { Chat.Turn(Chat.Role.USER, "질문 $it " + "가".repeat(1_000)) }
        val kept = Chat.trimmed(turns, budget = 5_000)
        assertTrue(kept.size < turns.size, "잘리지 않음")
        // What is still being talked about is the end of the conversation.
        assertEquals(turns.last(), kept.last())
        assertFalse(kept.contains(turns.first()))
    }

    @Test
    fun `one turn is never dropped, however long it is`() {
        val huge = listOf(Chat.Turn(Chat.Role.USER, "가".repeat(50_000)))
        assertEquals(1, Chat.trimmed(huge, budget = 100).size)
    }

    @Test
    fun `a conversation can be folded into the structured note`() {
        val turns = listOf(
            Chat.Turn(Chat.Role.USER, "이 락 순서 안전해?"),
            Chat.Turn(Chat.Role.ASSISTANT, "m_sessionLock 을 쥔 채 World::Leave 로 들어간다 — 역순 위험."),
        )
        val p = NoteRequest.prompt("Net/World.h", "", "", existing = null, conversation = turns)

        // The note prompt, with the conversation as context — not a chat prompt.
        assertTrue(p.contains("코드맵 노트"))
        assertTrue(p.contains("\"functions\""))
        assertTrue(p.contains("여기서 확인된 것을 노트에 반영해라"))
        assertTrue(p.contains("m_sessionLock 을 쥔 채 World::Leave"))
        assertTrue(p.contains("개발자: 이 락 순서 안전해?"))
    }

    @Test
    fun `without a conversation the note prompt is unchanged`() {
        val p = NoteRequest.prompt("Net/World.h", "", "", existing = null)
        assertFalse(p.contains("개발자와 나눈 대화"))
    }

    @Test
    fun `the answer is asked for as prose, and honesty is required`() {
        val p = Chat.prompt("a/B.h", null, emptyList(), "?")
        assertTrue(p.contains("확인 못 한 것은 모른다고 말해라"))
        assertTrue(p.contains("JSON 도 코드펜스도 필요 없다"))
    }
}
