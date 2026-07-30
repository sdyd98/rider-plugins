package com.example.codemap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The parts of the agent-CLI integration that must not be discovered at runtime by a user: where the binary
 * is, what the process is asked to do, and what counts as an answer. A wrong answer here would end up
 * written into a note, so the parse is deliberately strict.
 */
class AgentCliTest {

    @TempDir lateinit var tmp: File

    private fun exe(name: String): File =
        File(tmp, name).apply { writeText("#!/bin/sh\n"); setExecutable(true) }

    @Test
    fun `an explicit path wins over everything else`() {
        val bin = exe("claude")
        assertEquals(bin.canonicalFile, ClaudeCli.discover(bin.path, pathEnv = "/nowhere")?.canonicalFile)
    }

    @Test
    fun `PATH is searched when no explicit path is given`() {
        val bin = exe("claude")
        // wellKnown is emptied so the test does not depend on whether this machine has a real CLI.
        assertEquals(
            bin.canonicalFile,
            ClaudeCli.discover(null, pathEnv = tmp.path, wellKnown = emptyList())?.canonicalFile,
        )
    }

    @Test
    fun `well-known locations are tried before PATH`() {
        val wellKnown = exe("claude")
        val onPath = File(tmp, "path-bin").apply { mkdirs() }
        File(onPath, "claude").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals(
            wellKnown.canonicalFile,
            ClaudeCli.discover(null, pathEnv = onPath.path, wellKnown = listOf(wellKnown.path))?.canonicalFile,
        )
    }

    @Test
    fun `a path that is not executable is not accepted`() {
        val notExe = File(tmp, "claude").apply { writeText("x"); setExecutable(false) }
        assertNull(ClaudeCli.discover(notExe.path, pathEnv = "/nowhere"))
    }

    @Test
    fun `nothing found reports null rather than a broken command`() {
        assertNull(ClaudeCli.discover("/definitely/not/here", pathEnv = "/nowhere", wellKnown = emptyList()))
    }

    @Test
    fun `the command gives Claude no way to modify the repository`() {
        val cmd = ClaudeCli.command(exe("claude"))
        val tools = cmd.dropWhile { it != "--allowedTools" }.drop(1).takeWhile { !it.startsWith("--") }
        assertEquals(listOf("Read", "Grep", "Glob"), tools)
        assertTrue(cmd.none { it == "Write" || it == "Edit" || it == "Bash" })
        // Streaming, not a single blob at the end: the panel shows what the agent is reading, which is
        // what replaced the old ten-minute timeout as the way to tell working from wedged.
        assertTrue(cmd.containsAll(listOf("-p", "--output-format", "stream-json", "--verbose")))
    }

    @Test
    fun `the developer's question is carried into the prompt and marked as the priority`() {
        val p = NoteRequest.prompt("Net/World.h", "락 순서가 맞는지", "", existing = null)
        assertTrue(p.contains("Net/World.h"))
        assertTrue(p.contains("락 순서가 맞는지"))
        assertTrue(p.contains("최우선"))
        // Provenance is the plugin's to write, and the prompt says so.
        assertTrue(p.contains("analyzedAt"))
    }

    @Test
    fun `a symbol asks for one function and only that function`() {
        val existing = JsonParser.parseString(
            """{"purpose":"월드","packets":[{"id":"2999","dir":"out"}],
                "functions":[{"name":"Tick","anchor":"void World::Tick() {","purpose":"틱"}]}""",
        ).asJsonObject
        val p = NoteRequest.prompt("Net/World.h", "", "Tick", existing)

        assertTrue(p.contains("대상 함수: Tick"))
        // What the note already says about THAT function is sent, so this corrects rather than reinvents.
        assertTrue(p.contains("void World::Tick() {"))
        // The whole-note schema has no business here: the store upserts one function and leaves the rest
        // alone, so inviting an agent to re-decide the summary and the packets would only lose them.
        assertFalse(p.contains("roleInSystem"))
        assertFalse(p.contains("\"packets\""))
        assertTrue(p.contains("이 함수만 갈아 끼운다"))
    }

    @Test
    fun `an existing note is sent so a re-analysis corrects instead of replacing`() {
        val existing = JsonParser.parseString(
            """{"purpose":"세션","functions":[{"name":"Tick","purpose":"틱"}],
                "analyzedAt":"2026-01-01","hashes":{"a.h":"deadbeef"},"_manual":{"purpose":"손으로 고침"}}""",
        ).asJsonObject
        val p = NoteRequest.prompt("Net/World.h", "", "", existing)
        assertTrue(p.contains("이것을 고쳐라"))
        assertTrue(p.contains("\"Tick\""))
        // Provenance and the record of human edits are the plugin's business, not the agent's — the note
        // that gets sent carries neither. (The rules still NAME those keys, to forbid writing them.)
        assertFalse(p.contains("deadbeef"))
        assertFalse(p.contains("\"_manual\":"))
        assertFalse(p.contains("손으로 고침"))
    }

    @Test
    fun `an incomplete note is told what to fill in, not just what to correct`() {
        // "고쳐라" alone leaves an incomplete note incomplete forever: what is there is still right, so a
        // faithful agent hands the same shape back. This was real — AccountDb.h kept coming back with no
        // functions at all, and the panel had nothing but 주의 to show for it.
        val thin = JsonParser.parseString(
            """{"purpose":"계정 조회 래퍼","gotchas":["동기 호출이다"]}""",
        ).asJsonObject
        val p = NoteRequest.prompt("Db/AccountDb.h", "", "", thin)

        assertTrue(p.contains("빠진 것을 채우는 것도 고치는 일이다"))
        assertTrue(p.contains("functions 가 비어 있다"))
        assertTrue(p.contains("threading 이 없다"))
        assertTrue(p.contains("packets 가 없다"))
        // And the rule itself has to be a requirement, not a suggestion.
        assertTrue(p.contains("functions 는 필수다"))
    }

    @Test
    fun `a note that already has functions is asked to top it up, not told it is empty`() {
        val full = JsonParser.parseString(
            """{"functions":[{"name":"A"},{"name":"B"}],
                "threading":{"model":"IOCP"},"packets":[{"id":"1001"}]}""",
        ).asJsonObject
        val p = NoteRequest.prompt("Net/S.h", "", "", full)

        assertTrue(p.contains("functions 가 2 개 기록돼 있다"))
        assertFalse(p.contains("functions 가 비어 있다"))
        // Nothing to nag about for the keys that are already answered.
        assertFalse(p.contains("threading 이 없다"))
        assertFalse(p.contains("packets 가 없다"))
    }

    @Test
    fun `a huge note keeps every function name rather than being cut off`() {
        val functions = (1..400).joinToString(",") {
            """{"name":"Fn$it","anchor":"void Fn$it()","purpose":"${"설명".repeat(20)}",
                "effects":["${"효과".repeat(20)}"],"calls":["${"호출".repeat(20)}"]}"""
        }
        val existing = JsonParser.parseString("""{"functions":[$functions]}""").asJsonObject
        val p = NoteRequest.prompt("Net/World.h", "", "", existing)
        assertTrue(p.contains("\"Fn1\""))
        assertTrue(p.contains("\"Fn400\""))
        // The deep fields are what got dropped to make room.
        assertFalse(p.contains("효과효과"))
    }

    @Test
    fun `a scenario request asks for one packet sequence, not a note`() {
        val p = NoteRequest.prompt("Net/World.h", "", "", existing = null, flow = "로그인부터 월드 입장까지")
        assertTrue(p.contains("로그인부터 월드 입장까지"))
        assertTrue(p.contains("flows"))
        // Packets are the spine, and the schema example must show one with an id or the field goes unused.
        assertTrue(p.contains("받은 패킷에서 시작"))
        assertTrue(p.contains("\"packet\""))
        assertTrue(p.contains("ClientPacket::LoginReq"))
        assertTrue(p.contains("사이의 내부 처리는 한 줄씩만"))
        // The whole-note schema has no business here: this request must not re-decide the rest of the note.
        assertFalse(p.contains("roleInSystem"))
        assertFalse(p.contains("anchor 는 시그니처 줄"))
    }

    @Test
    fun `a scenario request names the diagrams that already exist so they are not redone`() {
        val existing = JsonParser.parseString(
            """{"flows":[{"name":"로그인","steps":[]},{"name":"퇴장","steps":[]}]}""",
        ).asJsonObject
        val p = NoteRequest.prompt("Net/World.h", "", "", existing, flow = "아이템 획득")
        assertTrue(p.contains("이미 있는 시퀀스"))
        assertTrue(p.contains("로그인, 퇴장"))
        assertTrue(p.contains("아이템 획득"))
    }

    @Test
    fun `both prompts carry the writing rules, with the examples that make them stick`() {
        val note = NoteRequest.prompt("Net/World.h", "", "", existing = null)
        val flow = NoteRequest.prompt("Net/World.h", "", "", existing = null, flow = "로그인")

        listOf(note, flow).forEach { p ->
            // The reader is a person mid-analysis; the rules exist to stop one-liners that restate the name.
            assertTrue(p.contains("이름을 되풀이하지 마라"), "규칙 없음")
            // A rule without an example does not change what gets written.
            assertTrue(p.contains("HandleLogin — 로그인을 처리한다"), "나쁜 예 없음")
            assertTrue(p.contains("m_state 를 Authenticating → Playing"), "좋은 예 없음")
            assertTrue(p.contains("빠져나가는 경로"), "조기 반환 규칙 없음")
            assertTrue(p.contains("락을, 어디까지 쥐는지"), "락 규칙 없음")
        }
    }

    @Test
    fun `codex is asked for a read-only sandbox and answers into a file`() {
        val answer = File.createTempFile("codemap-test", ".json")
        val cmd = CodexCli.command(exe("codex"), answer)
        assertEquals("exec", cmd[1])
        assertTrue(cmd.containsAll(listOf("-s", "read-only")))
        assertEquals(answer.absolutePath, cmd[cmd.indexOf("-o") + 1])
        // "-" is codex's own way of saying the instructions are on stdin.
        assertEquals("-", cmd.last())
    }

    @Test
    fun `neither engine takes the prompt as an argument`() {
        val answer = File.createTempFile("codemap-test", ".json")
        // A note's text can start with anything. One that began "-1의 값을 반환한다" was parsed as an option
        // and the analysis died with `error: unknown option '-1의 …'` before it ever ran. Nothing that could
        // hold note text may appear in argv.
        val prompt = NoteRequest.prompt("Net/World.h", "-1의 값을 반환한다", "", existing = null)
        listOf(ClaudeCli.command(exe("claude"), answer), CodexCli.command(exe("codex"), answer)).forEach { cmd ->
            assertTrue(cmd.none { it.contains("-1의") }, "프롬프트가 argv 에 실림: $cmd")
            assertTrue(cmd.none { it.length > 200 }, "argv 에 긴 텍스트가 있음: $cmd")
        }
        // The prompt itself still carries the developer's words — it just travels on stdin.
        assertTrue(prompt.contains("-1의 값을 반환한다"))
    }

    @Test
    fun `codex reads the note from its answer file, not the progress log`() {
        val answer = File.createTempFile("codemap-test", ".json")
        answer.writeText("""{"purpose":"세션"}""")
        val log = "OpenAI Codex v0.145.0\nworkdir: /x\ntokens used 15,754"
        assertEquals("세션", CodexCli.noteFrom(log, answer)?.get("purpose")?.asString)
    }

    @Test
    fun `an engine that writes nothing to its answer file falls back to stdout`() {
        val answer = File.createTempFile("codemap-test", ".json")
        assertEquals("세션", CodexCli.noteFrom("""{"purpose":"세션"}""", answer)?.get("purpose")?.asString)
    }

    @Test
    fun `the note is extracted from the CLI envelope`() {
        val envelope = """{"type":"result","is_error":false,"result":"{\"purpose\":\"세션\"}"}"""
        assertEquals("세션", ClaudeCli.extractNote(envelope)?.get("purpose")?.asString)
    }

    @Test
    fun `a fenced or chatty answer still yields the note`() {
        val fenced = """{"result":"```json\n{\"purpose\":\"세션\"}\n```"}"""
        assertEquals("세션", ClaudeCli.extractNote(fenced)?.get("purpose")?.asString)

        val chatty = """{"result":"여기 노트입니다:\n{\"purpose\":\"세션\"}\n필요하면 더 알려주세요."}"""
        assertEquals("세션", ClaudeCli.extractNote(chatty)?.get("purpose")?.asString)
    }

    @Test
    fun `an answer with no JSON object is rejected instead of half-parsed`() {
        assertNull(ClaudeCli.extractNote("""{"result":"파일을 못 찾겠습니다"}"""))
        assertNull(ClaudeCli.extractNote("완전히 깨진 출력"))
    }

    @Test
    fun `a streamed transcript yields the note from its result event`() {
        // What --output-format stream-json actually produces: one object per line, the answer last.
        val stream = listOf(
            """{"type":"system","subtype":"init","session_id":"abc"}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/repo/src/Session.cpp"}}]}}""",
            """{"type":"result","is_error":false,"result":"{\"purpose\":\"세션\"}"}""",
        ).joinToString("\n")

        assertEquals("세션", ClaudeCli.extractNote(stream)?.get("purpose")?.asString)
        assertNull(ClaudeCli.errorOf(stream))
    }

    @Test
    fun `a streamed failure is reported rather than parsed as a note`() {
        val stream = listOf(
            """{"type":"system","subtype":"init"}""",
            """{"type":"result","is_error":true,"result":"rate limited"}""",
        ).joinToString("\n")

        assertEquals("rate limited", ClaudeCli.errorOf(stream))
        assertNull(ClaudeCli.extractNote(stream))
    }

    @Test
    fun `streamed tool calls become something a person can read`() {
        // The point of these lines: with no time limit on an analysis, this is what distinguishes
        // "reading a 6,000-line file" from "wedged".
        assertEquals(
            "읽는 중 — Session.cpp",
            ClaudeCli.progressOf("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"/repo/src/Session.cpp"}}]}}"""),
        )
        assertEquals(
            "찾는 중 — HandleLogin",
            ClaudeCli.progressOf("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Grep","input":{"pattern":"HandleLogin"}}]}}"""),
        )
        // The model's own prose is the note being drafted; half a note scrolling past is noise.
        assertNull(ClaudeCli.progressOf("""{"type":"assistant","message":{"content":[{"type":"text","text":"이 파일은 세션을"}]}}"""))
        assertNull(ClaudeCli.progressOf("""{"type":"result","is_error":false,"result":"{}"}"""))
        assertNull(ClaudeCli.progressOf("not json at all"))
    }

    @Test
    fun `codex progress keeps the activity and drops the banner`() {
        assertEquals("Reading Session.cpp", CodexCli.progressOf("[2026-07-30T01:00:00] Reading Session.cpp"))
        assertNull(CodexCli.progressOf("   "))
        assertNull(CodexCli.progressOf("--------"))
        assertNull(CodexCli.progressOf("workdir: /repo"))
        assertNull(CodexCli.progressOf("model: gpt-5"))
    }

    @Test
    fun `an error envelope is reported as an error`() {
        val err = """{"type":"result","is_error":true,"result":"rate limited"}"""
        assertEquals("rate limited", ClaudeCli.errorOf(err))
        assertNull(ClaudeCli.errorOf("""{"is_error":false,"result":"{}"}"""))
    }

    @Test
    fun `the CLI envelope is never written as if it were the note`() {
        // Real shape, trimmed: the envelope parses fine, so nothing but an explicit check stops it
        // from being stored as a note full of session ids and token counts.
        val envelope = """{"is_error":false,"session_id":"abc","total_cost_usd":0.11,"subtype":"success",
            "usage":{"output_tokens":841},"result":"{\"purpose\":\"프로토콜 정의\"}"}"""
        val note = ClaudeCli.extractNote(envelope)!!
        assertEquals("프로토콜 정의", note.get("purpose")?.asString)
        assertNull(note.get("session_id"))
        assertNull(note.get("usage"))
    }

    @Test
    fun `an envelope whose result is not a note yields nothing`() {
        val envelope = """{"is_error":false,"session_id":"abc","result":"파일을 못 찾겠습니다"}"""
        assertNull(ClaudeCli.extractNote(envelope))
    }
}
