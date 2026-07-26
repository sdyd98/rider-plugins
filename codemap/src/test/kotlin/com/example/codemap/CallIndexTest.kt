package com.example.codemap

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The call graph stitched out of the notes. What is tested here is exactly what the drawing claims:
 * links come from recorded `calls`, they cross files, ambiguity is left unlinked rather than guessed,
 * and cycles terminate.
 */
class CallIndexTest {

    private fun note(vararg fns: Pair<String, List<String>>): JsonObject {
        val body = fns.joinToString(",") { (name, calls) ->
            val c = calls.joinToString(",") { "\"" + it + "\"" }
            "{\"name\":\"$name\",\"calls\":[$c]}"
        }
        return JsonParser.parseString("{\"functions\":[$body]}").asJsonObject
    }

    private val index = CallIndex(
        listOf(
            "Net/PlayerSession.h" to note(
                "PlayerSession::OnPacket" to listOf("HandleLogin", "Close"),
                "PlayerSession::HandleLogin" to listOf("AccountDb::LoadAccount", "World::Enter"),
                "PlayerSession::Close" to listOf("World::Leave"),
            ),
            "Db/AccountDb.h" to note("AccountDb::LoadAccount" to emptyList()),
            "Game/World.h" to note("World::Enter" to emptyList(), "World::Leave" to emptyList()),
        ),
    )

    @Test
    fun `a bare call name links to the function it uniquely identifies`() {
        // "HandleLogin" was recorded bare, but only one function ends with it.
        assertEquals("PlayerSession::HandleLogin", index.resolve("HandleLogin"))
        assertEquals("AccountDb::LoadAccount", index.resolve("AccountDb::LoadAccount"))
    }

    @Test
    fun `an ambiguous bare name is not guessed - it becomes an unanalyzed node`() {
        val ambiguous = CallIndex(
            listOf(
                "A.h" to note("Foo::Send" to emptyList()),
                "B.h" to note("Bar::Send" to listOf("Send")),
            ),
        )
        // Two functions end with "Send", so the call is not bound to either of them…
        assertNull(ambiguous.resolve("Send"))

        // …but the edge survives as a leaf the view draws muted: "calls something called Send, unknown
        // which". Dropping it would make the graph look more complete than it is.
        val g = ambiguous.around("Bar::Send", 2)
        assertEquals(setOf("Bar::Send" to "Send"), g.edges)
        val leaf = g.layers.flatten().first { it.name == "Send" }
        assert(!leaf.analyzed) { "an unresolved target must not claim a file" }
    }

    @Test
    fun `a call to a function nobody analyzed still appears`() {
        val partial = CallIndex(listOf("A.h" to note("A::run" to listOf("Db::Load"))))
        val g = partial.around("A::run", 1)
        assertEquals(listOf("A::run", "Db::Load"), g.layers.flatten().map { it.name })
        assert(!g.layers.flatten().first { it.name == "Db::Load" }.analyzed)
        assertEquals(setOf("A::run" to "Db::Load"), g.edges)
    }

    @Test
    fun `the graph spans files`() {
        val g = index.around("PlayerSession::HandleLogin", 1)
        val names = g.layers.flatten().map { it.name }
        assert(names.contains("AccountDb::LoadAccount")) { names.toString() }
        assert(names.contains("World::Enter")) { names.toString() }
        // Each node carries the file its note lives in, so the view can navigate.
        assertEquals("Db/AccountDb.h", g.layers.flatten().first { it.name == "AccountDb::LoadAccount" }.file)
    }

    @Test
    fun `callers sit above the focus and callees below`() {
        val g = index.around("PlayerSession::HandleLogin", 1)
        assertEquals(3, g.layers.size)
        assertEquals(listOf("PlayerSession::OnPacket"), g.layers[0].map { it.name })
        assertEquals(listOf("PlayerSession::HandleLogin"), g.layers[1].map { it.name })
        assertEquals(1, g.focusIndex)
        assertEquals(
            listOf("AccountDb::LoadAccount", "World::Enter"),
            g.layers[2].map { it.name }.sorted(),
        )
    }

    @Test
    fun `depth limits how far the graph walks`() {
        val one = index.around("PlayerSession::OnPacket", 1).layers.flatten().map { it.name }
        assert(!one.contains("World::Enter")) { one.toString() }

        val two = index.around("PlayerSession::OnPacket", 2).layers.flatten().map { it.name }
        assert(two.contains("World::Enter")) { two.toString() }
    }

    @Test
    fun `a cycle terminates instead of expanding forever`() {
        val cyclic = CallIndex(
            listOf("A.h" to note("A::ping" to listOf("A::pong"), "A::pong" to listOf("A::ping"))),
        )
        val g = cyclic.around("A::ping", 10)
        assertEquals(listOf("A::ping", "A::pong"), g.layers.flatten().map { it.name }.sorted())
    }

    @Test
    fun `a function nobody records a call to still shows its own callees`() {
        val g = index.around("PlayerSession::OnPacket", 1)
        assertEquals(0, g.focusIndex) // nothing above it
        assertEquals(
            listOf("PlayerSession::Close", "PlayerSession::HandleLogin"),
            g.layers[1].map { it.name }.sorted(),
        )
    }
}
