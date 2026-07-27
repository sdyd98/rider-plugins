package com.example.codemap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Anchors are how a note points at a function without storing a line number. The guarantees tested
 * here are the ones the UI relies on: the line is exact, indentation does not matter, a signature the
 * code no longer contains resolves to nothing (never to the wrong place), and an anchor that matches
 * more than once says so.
 */
class AnchorTest {

    @TempDir lateinit var tmp: File

    private fun src(text: String): File = File(tmp, "PlayerSession.cpp").apply { writeText(text) }

    private val file = """
        #include "PlayerSession.h"

        void PlayerSession::OnPacket(const PacketHeader& header, size_t len) {
            if (m_closed.load()) return;
        }

            void PlayerSession::Update(double dt) {
            m_idleSeconds += dt;
        }

        // void PlayerSession::Removed(int id) {
    """.trimIndent()

    // ---- what a Visual Studio codebase actually looks like ----
    //
    // Every one of these was found by opening a real .sln: the function index came up empty because the
    // matcher only ever looked inside a single line of UTF-8.

    private fun bytes(name: String, b: ByteArray): File = File(tmp, name).apply { writeBytes(b) }

    @Test
    fun `the brace on the next line still resolves — Visual Studio's own style`() {
        val f = bytes("A.cpp", "void PlayerSession::HandleLogin(int a)\r\n{\r\n\treturn;\r\n}\r\n".toByteArray())
        val hits = FileFacts.findAnchors(f, listOf("void PlayerSession::HandleLogin(int a) {"))
        assertEquals(1, hits.values.single().line)
    }

    @Test
    fun `a signature wrapped over several lines resolves to its first line`() {
        val f = bytes(
            "B.cpp",
            "int x;\r\nvoid PlayerSession::HandleLogin(\r\n\tconst uint8_t* body,\r\n\tsize_t len)\r\n{\r\n".toByteArray(),
        )
        val hits = FileFacts.findAnchors(
            f,
            listOf("void PlayerSession::HandleLogin(const uint8_t* body, size_t len)"),
        )
        assertEquals(2, hits.values.single().line)
    }

    @Test
    fun `UTF-16 resolves, with or without a BOM`() {
        val line = "void Ping() {\r\n"
        val withBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + line.toByteArray(Charsets.UTF_16LE)
        assertEquals(1, FileFacts.findAnchors(bytes("C.cpp", withBom), listOf("void Ping() {")).values.single().line)
        // Visual Studio does not always write the BOM; the zero bytes beside ASCII are the tell.
        val bare = line.toByteArray(Charsets.UTF_16LE)
        assertEquals(1, FileFacts.findAnchors(bytes("D.cpp", bare), listOf("void Ping() {")).values.single().line)
    }

    @Test
    fun `tolerance does not reach across a whole file`() {
        // Four lines is the window. A signature cannot be assembled out of scraps further apart than that,
        // or an anchor would eventually match something it has nothing to do with.
        val far = "void Foo(\n" + "// filler\n".repeat(6) + "int a)\n{\n"
        assertTrue(FileFacts.findAnchors(bytes("E.cpp", far.toByteArray()), listOf("void Foo(int a)")).isEmpty())
    }

    @Test
    fun `a blank line above a signature does not claim its hit`() {
        val f = bytes("F.cpp", "#include \"x.h\"\n\nvoid Bar() {\n".toByteArray())
        assertEquals(3, FileFacts.findAnchors(f, listOf("void Bar() {")).values.single().line)
    }

    @Test
    fun `an anchor resolves to its exact line`() {
        val f = src(file)
        val hits = FileFacts.findAnchors(f, listOf("void PlayerSession::OnPacket(const PacketHeader& header, size_t len) {"))
        assertEquals(3, hits.values.single().line)
        assertEquals(1, hits.values.single().occurrences)
    }

    @Test
    fun `indentation in the file does not break the anchor`() {
        val f = src(file)
        // Recorded without the leading spaces the file happens to have.
        val hits = FileFacts.findAnchors(f, listOf("void PlayerSession::Update(double dt) {"))
        assertEquals(7, hits.values.single().line)
    }

    @Test
    fun `a signature the file no longer contains resolves to nothing`() {
        val f = src(file)
        val hits = FileFacts.findAnchors(f, listOf("void PlayerSession::HandleLogin(const uint8_t* body) {"))
        assertNull(hits.values.firstOrNull())
    }

    @Test
    fun `an ambiguous anchor reports how many lines it matched`() {
        val f = src(file)
        // "void PlayerSession::" alone is a weak anchor — it appears on three lines, comment included.
        val hits = FileFacts.findAnchors(f, listOf("void PlayerSession::"))
        assertEquals(3, hits.values.single().line)
        assertEquals(3, hits.values.single().occurrences)
    }

    @Test
    fun `several anchors are resolved in one pass`() {
        val f = src(file)
        val hits = FileFacts.findAnchors(
            f,
            listOf(
                "void PlayerSession::OnPacket(const PacketHeader& header, size_t len) {",
                "void PlayerSession::Update(double dt) {",
                "void PlayerSession::Gone() {",
            ),
        )
        assertEquals(2, hits.size)
    }

    @Test
    fun `a blank anchor is ignored rather than matching every line`() {
        val f = src(file)
        assertEquals(emptyMap<String, FileFacts.Anchor>(), FileFacts.findAnchors(f, listOf("", "   ")))
    }
}
