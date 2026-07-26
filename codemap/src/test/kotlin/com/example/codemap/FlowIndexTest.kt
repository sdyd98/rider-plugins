package com.example.codemap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stitching flows across notes.
 *
 * The rule under test: a participant links to a file by STRING COMPARISON on its leading identifier — the
 * file's base name, or a class name that file's own note recorded. Nothing here may infer a type, and
 * nothing may match on a substring.
 */
class FlowIndexTest {

    private fun note(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val session = "Server/Net/PlayerSession.h" to note(
        """{"flows":[
             {"name":"로그인","steps":[
               {"from":"Client","to":"PlayerSession","call":"CS_LOGIN_REQ"},
               {"from":"PlayerSession","to":"World","call":"Enter"}]},
             {"name":"브로드캐스트","steps":[
               {"from":"PlayerSession","to":"World","call":"Broadcast"},
               {"from":"World","to":"PlayerSession(등록 세션들)","call":"Send"}]}]}""",
    )

    private val world = "Server/Game/World.h" to note(
        """{"classes":[{"name":"World"},{"name":"SpawnTable"}],
            "flows":[{"name":"틱 1회","steps":[{"from":"World","to":"World","call":"Tick"}]}]}""",
    )

    private val acceptor = "Server/Net/Acceptor.h" to note(
        """{"flows":[{"name":"세션 수락","steps":[
             {"from":"Acceptor","to":"PlayerSession","call":"make_shared"}]}]}""",
    )

    private val all = listOf(session, world, acceptor)

    @Test
    fun `every flow is indexed with the note that holds it`() {
        val entries = FlowIndex.index(all)
        assertEquals(
            listOf("로그인", "브로드캐스트", "틱 1회", "세션 수락"),
            entries.map { it.name },
        )
        assertEquals("Server/Net/PlayerSession.h", entries[0].owner)
        assertEquals("World.h", entries[2].ownerName)
        assertEquals(2, entries[0].steps)
    }

    @Test
    fun `a file appears in the flows another note holds`() {
        val found = FlowIndex.appearances(all, world.first, world.second)
        // World takes part in both of PlayerSession.h's flows — which is exactly what reading World.cpp
        // used to hide.
        assertEquals(listOf("로그인", "브로드캐스트"), found.map { it.name })
        assertTrue(found.all { it.owner == "Server/Net/PlayerSession.h" })
    }

    @Test
    fun `a file's own flows are not reported as appearances`() {
        val found = FlowIndex.appearances(all, world.first, world.second)
        assertTrue(found.none { it.name == "틱 1회" })
    }

    @Test
    fun `a decorated participant still matches the file it names`() {
        val found = FlowIndex.appearances(all, session.first, session.second)
        // `PlayerSession(등록 세션들)` is in PlayerSession's OWN flow, so it is not an appearance; the match
        // that matters here is Acceptor.h's plain `PlayerSession`.
        assertEquals(listOf("세션 수락"), found.map { it.name })
    }

    @Test
    fun `a class name the note records also links`() {
        val spawn = "Server/Game/Spawner.h" to note(
            """{"classes":[{"name":"SpawnTable"}]}""",
        )
        val notes = all + spawn
        val found = FlowIndex.appearances(notes, spawn.first, spawn.second)
        // Nothing mentions SpawnTable yet…
        assertTrue(found.isEmpty())

        val withMention = notes + ("Server/Game/Loot.h" to note(
            """{"flows":[{"name":"드롭","steps":[{"from":"Loot","to":"SpawnTable","call":"Roll"}]}]}""",
        ))
        assertEquals(
            listOf("드롭"),
            FlowIndex.appearances(withMention, spawn.first, spawn.second).map { it.name },
        )
    }

    @Test
    fun `a substring is not a match`() {
        val notes = listOf(
            "Server/Net/Session.h" to note("""{}"""),
            "Server/Net/Other.h" to note(
                """{"flows":[{"name":"x","steps":[{"from":"PlayerSession","to":"World","call":"y"}]}]}""",
            ),
        )
        // `Session` must not be found inside `PlayerSession` — that would attribute a flow to a file that
        // has nothing to do with it.
        assertTrue(FlowIndex.appearances(notes, "Server/Net/Session.h", note("{}")).isEmpty())
    }

    @Test
    fun `the leading identifier is what gets compared`() {
        assertEquals("PlayerSession", FlowIndex.leadingIdentifier("PlayerSession(등록 세션들)"))
        assertEquals("World", FlowIndex.leadingIdentifier("  World * "))
        assertEquals("m_state", FlowIndex.leadingIdentifier("m_state = Closing"))
        assertEquals("", FlowIndex.leadingIdentifier("→ OnPacket"))
    }

    @Test
    fun `a note with no flows contributes nothing and crashes nothing`() {
        val notes = listOf("a/B.h" to note("""{"purpose":"x"}"""))
        assertTrue(FlowIndex.index(notes).isEmpty())
        assertTrue(FlowIndex.appearances(notes, "a/B.h", notes[0].second).isEmpty())
    }

    @Test
    fun `string-chain flows have no participants, so they link to nothing`() {
        val notes = listOf(
            "a/A.h" to note("""{"flows":[{"name":"체인","steps":["OnPacket","HandleLogin"]}]}"""),
            "a/HandleLogin.h" to note("{}"),
        )
        // A stage in a chain is not a participant — inventing one would be the interpretation this
        // plugin does not do.
        assertTrue(FlowIndex.appearances(notes, "a/HandleLogin.h", note("{}")).isEmpty())
    }

    @Test
    fun `owners are compared by note path, so a cpp does not see its own flows as appearances`() {
        // The caller passes the NOTE path (the header). Passing the opened .cpp instead is what made a file
        // list its own three flows as belonging to somebody else.
        val asHeader = FlowIndex.appearances(all, "Server/Net/PlayerSession.h", session.second)
        assertTrue(asHeader.none { it.name in setOf("로그인", "브로드캐스트") })

        val asCpp = FlowIndex.appearances(all, "Server/Net/PlayerSession.cpp", session.second)
        assertEquals(listOf("로그인", "브로드캐스트", "세션 수락"), asCpp.map { it.name })
    }

    @Test
    fun `the file's own base name links even with no note`() {
        assertEquals(setOf("PlayerSession"), FlowIndex.namesOf("Server/Net/PlayerSession.h", null))
        assertEquals(
            setOf("World", "SpawnTable"),
            FlowIndex.namesOf("Server/Game/World.h", world.second),
        )
    }
}
