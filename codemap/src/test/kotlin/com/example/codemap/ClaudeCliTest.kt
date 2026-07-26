package com.example.codemap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The parts of the CLI integration that must not be discovered at runtime by a user: where the binary
 * is, what the process is asked to do, and what counts as an answer. A wrong answer here would end up
 * written into a note, so the parse is deliberately strict.
 */
class ClaudeCliTest {

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
        val cmd = ClaudeCli.command(exe("claude"), "hi")
        val tools = cmd.dropWhile { it != "--allowedTools" }.drop(1).takeWhile { !it.startsWith("--") }
        assertEquals(listOf("Read", "Grep", "Glob"), tools)
        assertTrue(cmd.none { it == "Write" || it == "Edit" || it == "Bash" })
        assertTrue(cmd.containsAll(listOf("-p", "--output-format", "json")))
    }

    @Test
    fun `the developer's question is carried into the prompt and marked as the priority`() {
        val p = ClaudeCli.prompt("Net/World.h", "락 순서가 맞는지", "", existing = false)
        assertTrue(p.contains("Net/World.h"))
        assertTrue(p.contains("락 순서가 맞는지"))
        assertTrue(p.contains("최우선"))
        // Provenance is the plugin's to write, and the prompt says so.
        assertTrue(p.contains("analyzedAt"))
    }

    @Test
    fun `a symbol scopes the prompt to one function`() {
        val p = ClaudeCli.prompt("Net/World.h", "", "Tick", existing = true)
        assertTrue(p.contains("Tick 함수만"))
        assertTrue(p.contains("기존 노트가 있으니"))
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
