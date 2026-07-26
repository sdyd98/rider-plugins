package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage of the store against a real directory: write a note, read it back, watch it go
 * stale when the code actually changes, and drive the pending queue. This is the behaviour a user
 * sees, so it is tested on real files rather than mocks.
 */
class NoteStoreTest {

    @TempDir lateinit var tmp: File

    private fun store() = NoteStore(tmp) { "2026-07-26" }

    private fun write(rel: String, text: String): File =
        File(tmp, rel).apply { parentFile.mkdirs(); writeText(text) }

    private fun fns(vararg entries: Pair<String, String>): com.google.gson.JsonArray =
        com.google.gson.JsonArray().apply {
            entries.forEach { (name, purpose) ->
                add(JsonParser.parseString("""{"name":"$name","anchor":"void $name(","purpose":"$purpose"}"""))
            }
        }

    private fun note(purpose: String): JsonObject =
        JsonParser.parseString("""{"purpose":"$purpose"}""").asJsonObject

    private fun git(vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-C", tmp.absolutePath) + args).start()
        p.waitFor(30, TimeUnit.SECONDS)
    }

    @Test
    fun `a note round-trips and is stamped with the files it covers`() {
        write("Net/PlayerSession.h", "#pragma once\nclass PlayerSession {};\n")
        write("Net/PlayerSession.cpp", "#include \"PlayerSession.h\"\n")
        val s = store()

        val stamped = s.writeNote("Net/PlayerSession.cpp", note("세션 하나"))

        assertEquals(
            listOf("Net/PlayerSession.h", "Net/PlayerSession.cpp"),
            stamped.getAsJsonArray("files").map { it.asString },
        )
        assertEquals("2026-07-26", stamped.get("analyzedAt").asString)
        assertEquals(2, stamped.getAsJsonObject("hashes").size())
        // The bundle mirrors the directory, and BOTH halves of the pair resolve to the same note.
        assertTrue(File(tmp, ".codemap/Net.json").isFile)
        assertEquals("세션 하나", s.readNote("Net/PlayerSession.h")?.get("purpose")?.asString)
        assertEquals("세션 하나", s.readNote("Net/PlayerSession.cpp")?.get("purpose")?.asString)
    }

    @Test
    fun `the store self-ignores so it never lands in a commit`() {
        write("main.cpp", "int main(){}\n")
        store().writeNote("main.cpp", note("진입점"))
        assertEquals("*", File(tmp, ".codemap/.gitignore").readText().trim())
    }

    @Test
    fun `provenance the AI tries to author is discarded and re-stamped`() {
        write("main.cpp", "int main(){}\n")
        val forged = JsonParser.parseString(
            """{"purpose":"x","analyzedAt":"1999-01-01","hashes":{"main.cpp":"sha256:beef"},"files":["nope"]}""",
        ).asJsonObject

        val stamped = store().writeNote("main.cpp", forged)

        assertEquals("2026-07-26", stamped.get("analyzedAt").asString)
        assertEquals(listOf("main.cpp"), stamped.getAsJsonArray("files").map { it.asString })
        assertTrue(stamped.getAsJsonObject("hashes").get("main.cpp").asString.startsWith("sha256:"))
    }

    @Test
    fun `freshness follows content, not timestamps`() {
        val f = write("Net/Acceptor.h", "#pragma once\nclass Acceptor {};\n")
        val s = store()
        s.writeNote("Net/Acceptor.h", note("리스너"))
        assertEquals(NoteStore.Freshness.FRESH, s.freshness(s.readNote("Net/Acceptor.h")))

        // Touched but identical -> still fresh (mtime changed, content did not).
        f.setLastModified(System.currentTimeMillis() + 10_000)
        assertEquals(NoteStore.Freshness.FRESH, s.freshness(s.readNote("Net/Acceptor.h")))

        // Actually changed -> stale.
        f.writeText("#pragma once\nclass Acceptor { void Stop(); };\n")
        assertEquals(NoteStore.Freshness.STALE, s.freshness(s.readNote("Net/Acceptor.h")))

        // Reverted -> fresh again.
        f.writeText("#pragma once\nclass Acceptor {};\n")
        assertEquals(NoteStore.Freshness.FRESH, s.freshness(s.readNote("Net/Acceptor.h")))
    }

    @Test
    fun `a deleted file makes its note stale`() {
        val f = write("Net/Gone.h", "#pragma once\n")
        val s = store()
        s.writeNote("Net/Gone.h", note("사라질 것"))
        f.delete()
        assertEquals(NoteStore.Freshness.STALE, s.freshness(s.readNote("Net/Gone.h")))
    }

    @Test
    fun `a file with no note is NO_NOTE, not stale`() {
        write("Net/Fresh.h", "#pragma once\n")
        val s = store()
        assertNull(s.readNote("Net/Fresh.h"))
        assertEquals(NoteStore.Freshness.NO_NOTE, s.freshness(s.readNote("Net/Fresh.h")))
    }

    @Test
    fun `requests are keyed by note - asking twice updates the question`() {
        write("Net/World.h", "#pragma once\n")
        write("Net/World.cpp", "#include \"World.h\"\n")
        val s = store()

        s.addPending("Net/World.h", "틱이 어디서 도는지", "new")
        s.addPending("Net/World.cpp", "락 순서", "new") // same note, via the other half of the pair

        assertEquals(1, s.pending().size)
        assertEquals("락 순서", s.pending().first().question)
        assertEquals("Net/World.h", s.pending().first().path)
        assertEquals("락 순서", s.pendingFor("Net/World.cpp")?.question)
    }

    @Test
    fun `re-requesting without typing keeps the question already queued`() {
        write("Net/World.h", "#pragma once\n")
        val s = store()
        s.addPending("Net/World.h", "틱 스레드가 락을 어떤 순서로 잡는지", "new")

        s.addPending("Net/World.h", "", "stale") // the bare button press

        assertEquals(1, s.pending().size)
        assertEquals("틱 스레드가 락을 어떤 순서로 잡는지", s.pending().first().question)
        assertEquals("stale", s.pending().first().reason)

        // Typing a new question does replace it.
        s.addPending("Net/World.h", "Broadcast의 exceptPlayerId가 맞는지", "stale")
        assertEquals("Broadcast의 exceptPlayerId가 맞는지", s.pending().first().question)
    }

    @Test
    fun `writing a note answers - and clears - its request`() {
        write("Net/World.h", "#pragma once\n")
        val s = store()
        s.addPending("Net/World.h", "틱이 어디서 도는지", "new")
        assertEquals(1, s.pending().size)

        s.writeNote("Net/World.h", note("월드"))

        assertTrue(s.pending().isEmpty())
        assertNull(s.pendingFor("Net/World.h"))
    }

    @Test
    fun `queueing one file does not disturb another`() {
        write("A.h", "#pragma once\n")
        write("B.h", "#pragma once\n")
        val s = store()
        s.addPending("A.h", "질문 A", "new")
        s.addPending("B.h", "질문 B", "new")
        s.writeNote("A.h", note("에이"))
        assertEquals(listOf("B.h"), s.pending().map { it.path })
    }

    @Test
    fun `allNotes enumerates every bundle - including a source directory named with an underscore`() {
        write("main.cpp", "int main(){}\n")
        write("Net/PlayerSession.h", "#pragma once\n")
        write("_demo/Sample.h", "#pragma once\n")
        val s = store()
        s.writeNote("main.cpp", note("루트"))
        s.writeNote("Net/PlayerSession.h", note("세션"))
        s.writeNote("_demo/Sample.h", note("샘플"))
        s.addPending("Net/PlayerSession.h", "", "stale") // _pending.json must not be read as notes

        assertEquals(
            listOf("Net/PlayerSession.h", "_demo/Sample.h", "main.cpp"),
            s.allNotes().map { it.first }.sorted(),
        )
    }

    @Test
    fun `functions upsert by name, keeping the order already recorded`() {
        write("Net/PlayerSession.h", "#pragma once\n")
        val s = store()
        s.writeNote("Net/PlayerSession.h", note("세션"))
        s.writeFunctions("Net/PlayerSession.h", fns("OnPacket" to "관문", "Update" to "틱"))

        // Re-analyzing one function replaces it in place; a new one lands at the end.
        s.writeFunctions("Net/PlayerSession.h", fns("Update" to "틱 — 다시 봄", "Close" to "종료"))

        val out = s.readNote("Net/PlayerSession.h")!!.getAsJsonArray("functions")
        assertEquals(
            listOf("OnPacket", "Update", "Close"),
            out.map { it.asJsonObject.get("name").asString },
        )
        assertEquals("틱 — 다시 봄", out[1].asJsonObject.get("purpose").asString)
        // The rest of the note survives a functions-only write.
        assertEquals("세션", s.readNote("Net/PlayerSession.h")!!.get("purpose").asString)
    }

    @Test
    fun `writing functions into a file with no note yet creates one`() {
        write("main.cpp", "int main(){}\n")
        val s = store()
        s.writeFunctions("main.cpp", fns("main" to "진입점"))
        val n = s.readNote("main.cpp")!!
        assertEquals(1, n.getAsJsonArray("functions").size())
        assertEquals("2026-07-26", n.get("analyzedAt").asString)
    }

    @Test
    fun `a function request is queued separately from a whole-file request`() {
        write("Net/Big.h", "#pragma once\n")
        val s = store()
        s.addPending("Net/Big.h", "전체 봐줘", "new")
        s.addPending("Net/Big.h", "Dispatch만", "new", symbol = "Dispatch")

        assertEquals(2, s.pending().size)
        assertEquals("Dispatch", s.pending()[1].symbol)

        // Re-asking about the same function updates that entry only.
        s.addPending("Net/Big.h", "Dispatch 초기화 순서", "new", symbol = "Dispatch")
        assertEquals(2, s.pending().size)
        assertEquals("Dispatch 초기화 순서", s.pending().first { it.symbol == "Dispatch" }.question)
    }

    @Test
    fun `a human edit preserves provenance and does not answer the queued question`() {
        val f = write("Net/World.h", "#pragma once\n")
        val s = store()
        s.writeNote("Net/World.h", note("월드"))
        s.addPending("Net/World.h", "틱 스레드가 락을 어떤 순서로 잡는지", "new")
        val before = s.readNote("Net/World.h")!!
        val hashesBefore = before.getAsJsonObject("hashes").toString()

        s.editNote("Net/World.h", listOf("purpose"), JsonPrimitive("월드 — 표현을 다듬음"))

        val after = s.readNote("Net/World.h")!!
        assertEquals("월드 — 표현을 다듬음", after.get("purpose").asString)
        // Fixing a sentence is not a re-analysis: the note still describes the same source.
        assertEquals(before.get("analyzedAt").asString, after.get("analyzedAt").asString)
        assertEquals(before.get("analyzedCommit").asString, after.get("analyzedCommit").asString)
        assertEquals(hashesBefore, after.getAsJsonObject("hashes").toString())
        // …and it does not answer the question someone asked.
        assertEquals(1, s.pending().size)
        assertEquals(NoteStore.Freshness.FRESH, s.freshness(after))
        assertEquals("#pragma once\n", f.readText())
    }

    @Test
    fun `an edit cannot forge provenance even if it tries`() {
        write("main.cpp", "int main(){}\n")
        val s = store()
        s.writeNote("main.cpp", note("진입점"))
        val realHashes = s.readNote("main.cpp")!!.getAsJsonObject("hashes").toString()

        s.editNote("main.cpp", listOf("analyzedAt"), JsonPrimitive("2099-01-01"))
        s.editNote("main.cpp", listOf("hashes"), JsonParser.parseString("{\"main.cpp\":\"sha256:0\"}"))

        val after = s.readNote("main.cpp")!!
        assertEquals("2026-07-26", after.get("analyzedAt").asString)
        assertEquals(realHashes, after.getAsJsonObject("hashes").toString())
    }

    @Test
    fun `editing a list replaces exactly that list`() {
        write("Net/A.h", "#pragma once\n")
        val s = store()
        s.writeNote("Net/A.h", note("에이"))
        s.editNote("Net/A.h", listOf("gotchas"), JsonArray().apply { add("첫째"); add("둘째") })
        s.editNote("Net/A.h", listOf("gotchas"), JsonArray().apply { add("첫째만 남김") })

        val g = s.readNote("Net/A.h")!!.getAsJsonArray("gotchas").map { it.asString }
        assertEquals(listOf("첫째만 남김"), g)
        assertEquals("에이", s.readNote("Net/A.h")!!.get("purpose").asString)
    }

    @Test
    fun `editing a file with no note yet is a no-op, not a crash`() {
        write("Net/Untouched.h", "#pragma once\n")
        val s = store()
        assertNull(s.editNote("Net/Untouched.h", listOf("purpose"), JsonPrimitive("x")))
        assertNull(s.readNote("Net/Untouched.h"))
    }

    // ---- a re-analysis must not undo a person's work ----

    @Test
    fun `a re-analysis keeps the sentence a person typed`() {
        write("Net/World.h", "#pragma once\n")
        val s = store()
        s.writeNote("Net/World.h", note("월드 — AI가 쓴 요약"))
        s.editNote("Net/World.h", listOf("purpose"), JsonPrimitive("월드 — 내가 고친 요약"))

        // A fresh analysis that knows nothing about the correction.
        s.writeNote("Net/World.h", note("월드 — AI가 다시 쓴 요약"))

        val after = s.readNote("Net/World.h")!!
        assertEquals("월드 — 내가 고친 요약", after.get("purpose").asString)
        // The record itself survives too, so the NEXT analysis is bound by it as well.
        assertTrue(s.edited(after))
    }

    @Test
    fun `a re-analysis keeps a corrected function field and takes the rest from the analysis`() {
        write("Net/World.h", "#pragma once\n")
        val s = store()
        s.writeNote("Net/World.h", JsonParser.parseString(
            """{"functions":[{"name":"Tick","anchor":"void Tick()","purpose":"틱","thread":"메인"}]}""",
        ).asJsonObject)
        s.editNote("Net/World.h", listOf("functions", "Tick", "purpose"), JsonPrimitive("내가 고친 틱 설명"))

        s.writeNote("Net/World.h", JsonParser.parseString(
            """{"functions":[{"name":"Tick","anchor":"void Tick()","purpose":"AI가 다시 쓴 틱","thread":"워커"}]}""",
        ).asJsonObject)

        val tick = s.readNote("Net/World.h")!!.getAsJsonArray("functions")[0].asJsonObject
        assertEquals("내가 고친 틱 설명", tick.get("purpose").asString)
        // Only the corrected field is pinned — the analysis is still allowed to teach us the rest.
        assertEquals("워커", tick.get("thread").asString)
    }

    @Test
    fun `a partial analysis does not delete the functions it did not mention`() {
        write("Net/World.h", "#pragma once\n")
        val s = store()
        s.writeNote("Net/World.h", JsonParser.parseString(
            """{"functions":[{"name":"Tick","purpose":"틱"},{"name":"Join","purpose":"입장"}]}""",
        ).asJsonObject)

        s.writeNote("Net/World.h", JsonParser.parseString(
            """{"functions":[{"name":"Tick","purpose":"틱 — 다시 씀"}]}""",
        ).asJsonObject)

        val names = s.readNote("Net/World.h")!!.getAsJsonArray("functions").map { it.asJsonObject.get("name").asString }
        assertEquals(listOf("Tick", "Join"), names)
        assertEquals("틱 — 다시 씀", s.readNote("Net/World.h")!!.getAsJsonArray("functions")[0].asJsonObject.get("purpose").asString)
    }

    @Test
    fun `an analysis cannot claim a human edit by writing the manual record itself`() {
        write("main.cpp", "int main(){}\n")
        val s = store()
        s.writeNote("main.cpp", JsonParser.parseString(
            """{"purpose":"진입점","_manual":{"purpose":"AI가 사람 흉내를 냄"}}""",
        ).asJsonObject)

        val after = s.readNote("main.cpp")!!
        assertEquals("진입점", after.get("purpose").asString)
        assertFalse(s.edited(after))
    }

    @Test
    fun `an untouched note is not reported as edited`() {
        write("main.cpp", "int main(){}\n")
        val s = store()
        s.writeNote("main.cpp", note("진입점"))
        assertFalse(s.edited(s.readNote("main.cpp")))
        assertFalse(s.edited(null))
    }

    @Test
    fun `files outside the root have no place in the store`() {
        val s = store()
        assertNull(s.relativize(File("/etc/hosts")))
        assertNotNull(s.relativize(File(tmp, "Net/PlayerSession.h")))
    }

    @Test
    fun `the analyzed commit is recorded so drift can be measured`() {
        write("main.cpp", "int main(){}\n")
        git("init", "-q")
        git("-c", "user.email=t@t", "-c", "user.name=t", "add", ".")
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
        val head = FileFacts.headCommit(tmp)
        org.junit.jupiter.api.Assumptions.assumeTrue(head.isNotEmpty(), "git unavailable")

        val stamped = store().writeNote("main.cpp", note("진입점"))
        assertEquals(head, stamped.get("analyzedCommit").asString)
        assertEquals(0, FileFacts.commitsSince(tmp, "main.cpp", head))
    }
}
