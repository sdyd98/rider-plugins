package com.example.codemap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Answering "who else deals with this packet?" across notes.
 *
 * The rule under test: ids are compared through [PacketIndex.key], which normalises to the number a
 * hand-written id contains, because two analyses of one protocol write it differently
 * (`ClientPacket::LoginReq (1001)` vs `1001`). Nothing else is inferred.
 */
class PacketIndexTest {

    private fun note(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val session = "Server/Net/PlayerSession.h" to note(
        """{"packets":[
             {"id":"ClientPacket::LoginReq (1001)","dir":"in","handler":"HandleLogin"},
             {"id":"ServerPacket::Kick (2999)","dir":"out"}]}""",
    )

    private val world = "Server/Game/World.h" to note(
        """{"packets":[{"id":"2999","dir":"out","sentBy":"World::KickAll"}]}""",
    )

    private val dispatcher = "Server/Net/PacketDispatcher.h" to note(
        """{"packets":[{"id":"1001","dir":"in","handler":"Dispatch"}]}""",
    )

    private val all = listOf(session, world, dispatcher)

    @Test
    fun `every recorded packet is indexed with its note`() {
        val entries = PacketIndex.index(all)
        assertEquals(4, entries.size)
        assertEquals("PlayerSession.h", entries[0].ownerName)
        assertTrue(entries[0].inbound)
        assertEquals("HandleLogin", entries[0].symbol)
        // An outbound entry points at what sends it.
        assertEquals("World::KickAll", entries[2].symbol)
    }

    @Test
    fun `the same id written two ways still matches`() {
        val found = PacketIndex.elsewhere(all, session.first, session.second)
        assertEquals(
            setOf("ClientPacket::LoginReq (1001)", "ServerPacket::Kick (2999)"),
            found.keys,
        )
        // "누가 2999를 보내나" — answered from another note entirely.
        assertEquals(listOf("World.h"), found["ServerPacket::Kick (2999)"]!!.map { it.ownerName })
        assertEquals(listOf("PacketDispatcher.h"), found["ClientPacket::LoginReq (1001)"]!!.map { it.ownerName })
    }

    @Test
    fun `a file is not reported against itself`() {
        val found = PacketIndex.elsewhere(all, session.first, session.second)
        assertTrue(found.values.flatten().none { it.owner == session.first })
    }

    @Test
    fun `hex wins over decimal digits inside the same string`() {
        assertEquals("0x81", PacketIndex.key("ServerPacket::LoginAck (0x81)"))
        assertEquals("0xff", PacketIndex.key("0xFF"))
        assertEquals("1001", PacketIndex.key("ClientPacket::LoginReq (1001)"))
    }

    @Test
    fun `an id with no number stands on its own`() {
        // Nothing to normalise, so the two notes must have written it identically — the honest outcome.
        assertEquals("CS_LOGIN_REQ", PacketIndex.key("  CS_LOGIN_REQ "))
        val notes = listOf(
            "a/A.h" to note("""{"packets":[{"id":"CS_PING","dir":"in"}]}"""),
            "a/B.h" to note("""{"packets":[{"id":"CS_PING","dir":"out"}]}"""),
            "a/C.h" to note("""{"packets":[{"id":"CS_PONG","dir":"out"}]}"""),
        )
        val found = PacketIndex.elsewhere(notes, "a/A.h", notes[0].second)
        assertEquals(listOf("B.h"), found["CS_PING"]!!.map { it.ownerName })
    }

    @Test
    fun `a note with no packets asks nothing and crashes nothing`() {
        val notes = listOf("a/A.h" to note("""{"purpose":"x"}"""))
        assertTrue(PacketIndex.index(notes).isEmpty())
        assertTrue(PacketIndex.elsewhere(notes, "a/A.h", notes[0].second).isEmpty())
    }

    @Test
    fun `flows are found by the packets they trace`() {
        val notes = all + ("Server/Net/Login.h" to note(
            """{"flows":[{"name":"로그인 패킷","steps":[
                 {"from":"Client","to":"PlayerSession","packet":"ClientPacket::LoginReq","id":"1001"}]}]}""",
        ))
        // The flow writes the constant, the packets table writes the number — both must find each other.
        assertEquals(listOf("로그인 패킷"), FlowIndex.tracing(notes, "1001").map { it.name })
        assertEquals(listOf("로그인 패킷"), FlowIndex.tracing(notes, "ClientPacket::LoginReq (1001)").map { it.name })
        assertTrue(FlowIndex.tracing(notes, "2999").isEmpty())
    }
}
