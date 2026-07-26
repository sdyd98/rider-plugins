package com.example.codemap

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing a recorded flow into presentable steps.
 *
 * The rule under test throughout: a step's kind comes from what the note RECORDED, and where it was not
 * recorded it is derived arithmetically (one end missing, or both ends the same) — never guessed from the
 * label's wording.
 */
class FlowModelTest {

    private fun parse(json: String) = FlowModel.parse(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `a recorded packet name makes the step a packet, and the id rides along`() {
        val f = parse(
            """{"name":"로그인","steps":[
                 {"from":"Client","to":"Session","packet":"ClientPacket::LoginReq","id":"0x01"},
                 {"from":"Session","to":"Client","packet":"ServerPacket::LoginAck","id":"0x81"}]}""",
        )
        assertEquals(listOf(FlowModel.Kind.PACKET, FlowModel.Kind.PACKET), f.steps.map { it.kind })
        assertEquals(listOf("0x01", "0x81"), f.steps.map { it.packetId })
        // The label is the packet constant, and both directions are packets — a server→client packet is not
        // a value coming back.
        assertEquals(listOf("ClientPacket::LoginReq", "ServerPacket::LoginAck"), f.steps.map { it.label })
        assertTrue(f.steps.all { it.isEdge })
    }

    @Test
    fun `a packet wins over a declared kind, so an older note reads the right way`() {
        val f = parse("""{"steps":[{"from":"S","to":"C","packet":"SC_ACK","kind":"return"}]}""")
        assertEquals(FlowModel.Kind.PACKET, f.steps[0].kind)
    }

    @Test
    fun `packets are the spine, the steps between them are not`() {
        val f = parse(
            """{"steps":[
                 {"from":"Client","to":"Session","packet":"CS_LOGIN_REQ"},
                 {"from":"Session","to":"Session","call":"ValidateToken","kind":"process"},
                 {"from":"Session","to":"Db","call":"LoadAccount"},
                 {"from":"Session","to":"Client","packet":"SC_LOGIN_ACK"}]}""",
        )
        assertEquals(listOf("CS_LOGIN_REQ", "SC_LOGIN_ACK"), FlowModel.packets(f).map { it.label })
        assertEquals(4, f.steps.size)
    }

    @Test
    fun `a missing id is empty, not invented`() {
        val f = parse("""{"steps":[{"from":"C","to":"S","packet":"CS_PING"}]}""")
        assertEquals("", f.steps[0].packetId)
    }

    @Test
    fun `a call between two objects is a message, and the objects become participants in order`() {
        val f = parse(
            """{"name":"로그인","steps":[
                 {"from":"Client","to":"Session","call":"CS_LOGIN_REQ"},
                 {"from":"Session","to":"Db","call":"LoadAccount"}]}""",
        )
        assertEquals("로그인", f.name)
        assertEquals(listOf(FlowModel.Kind.MESSAGE, FlowModel.Kind.MESSAGE), f.steps.map { it.kind })
        assertEquals(listOf("Client", "Session", "Db"), f.participants)
        assertTrue(f.steps.all { it.isEdge })
    }

    @Test
    fun `a declared return stays a return`() {
        val f = parse("""{"steps":[{"from":"Db","to":"Session","call":"Account","kind":"return"}]}""")
        assertEquals(FlowModel.Kind.RETURN, f.steps[0].kind)
        assertTrue(f.steps[0].isEdge)
    }

    @Test
    fun `a step with one end, or both ends the same, is something one object does alone`() {
        val f = parse(
            """{"steps":[
                 {"from":"Session","to":"Session","call":"ValidateToken"},
                 {"from":"Session","call":"ResetTimer"},
                 {"to":"Session","call":"Close"}]}""",
        )
        assertEquals(List(3) { FlowModel.Kind.PROCESS }, f.steps.map { it.kind })
        // A process has no edge to light up, and both ends name the one object involved.
        assertTrue(f.steps.none { it.isEdge })
        assertTrue(f.steps.all { it.from == "Session" && it.to == "Session" })
        assertEquals(listOf("Session"), f.participants)
    }

    @Test
    fun `an explicitly declared process keeps its kind even with two ends`() {
        val f = parse("""{"steps":[{"from":"World","to":"World","call":"Tick","kind":"process"}]}""")
        assertEquals(FlowModel.Kind.PROCESS, f.steps[0].kind)
    }

    @Test
    fun `a note carries text and no object at all`() {
        val f = parse(
            """{"steps":[
                 {"kind":"note","description":"인증을 마친 뒤부터."},
                 {"from":"Client","to":"Session","call":"REQ"}]}""",
        )
        assertEquals(FlowModel.Kind.NOTE, f.steps[0].kind)
        assertFalse(f.steps[0].isEdge)
        // A note must not invent a participant.
        assertEquals(listOf("Client", "Session"), f.participants)
    }

    @Test
    fun `the older string-chain shape still reads, as notes`() {
        val f = parse("""{"name":"틱","steps":["OnPacket","HandleLogin","Enter"]}""")
        assertEquals(List(3) { FlowModel.Kind.NOTE }, f.steps.map { it.kind })
        assertEquals(listOf("OnPacket", "HandleLogin", "Enter"), f.steps.map { it.label })
        // Nothing to draw, and nothing invented to draw it with.
        assertTrue(f.participants.isEmpty())
    }

    @Test
    fun `description is read from either description or note`() {
        val f = parse(
            """{"steps":[
                 {"from":"A","to":"B","call":"x","description":"이유"},
                 {"from":"A","to":"B","call":"y","note":"다른 이유"}]}""",
        )
        assertEquals(listOf("이유", "다른 이유"), f.steps.map { it.description })
    }

    @Test
    fun `repeated traffic between the same pair is one connection used twice`() {
        val f = parse(
            """{"steps":[
                 {"from":"A","to":"B","call":"x"},
                 {"from":"A","to":"B","call":"y"},
                 {"from":"B","to":"C","call":"z"}]}""",
        )
        assertEquals(listOf("A" to "B", "B" to "C"), FlowModel.connections(f))
    }

    @Test
    fun `steps keep their recorded order and their own numbering`() {
        val f = parse(
            """{"steps":[
                 {"kind":"note","description":"먼저"},
                 {"from":"A","to":"B","call":"x"},
                 {"from":"B","to":"A","call":"r","kind":"return"}]}""",
        )
        assertEquals(listOf(0, 1, 2), f.steps.map { it.index })
    }

    @Test
    fun `a flow with no steps parses to nothing rather than failing`() {
        assertEquals(0, parse("""{"name":"빈"}""").steps.size)
        assertEquals(0, parse("""{"name":"빈","steps":[]}""").participants.size)
    }

    @Test
    fun `a malformed step is dropped, not fatal`() {
        val f = parse("""{"steps":[{"from":"A","to":"B","call":"x"}, 42, {"from":"B","to":"C","call":"y"}]}""")
        // A bare number is a primitive, so it reads as a note rather than taking the flow down with it.
        assertEquals(3, f.steps.size)
        assertEquals(listOf("A", "B", "C"), f.participants)
    }
}
