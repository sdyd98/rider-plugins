package com.example.codemap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
