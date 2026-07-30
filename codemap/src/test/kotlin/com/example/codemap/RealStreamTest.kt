package com.example.codemap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The stream parser against a transcript the real CLI actually produced.
 *
 * Hand-written fixtures only prove the parser matches what I assumed the events look like. This one is
 * `claude -p --output-format stream-json --verbose` output, captured verbatim — so if a CLI update
 * changes the envelope, this fails instead of the panel silently going quiet.
 */
class RealStreamTest {

    private val transcript: String =
        javaClass.getResourceAsStream("/claude-stream.jsonl")!!.bufferedReader().readText()

    @Test
    fun `the answer is read from the streamed result event`() {
        assertEquals("Session, Queue", ClaudeCli.textFrom(transcript, File("/nonexistent")))
        assertNull(ClaudeCli.errorOf(transcript))
    }

    @Test
    fun `tool calls in the real stream become progress lines`() {
        val steps = transcript.lines().mapNotNull { ClaudeCli.progressOf(it) }
        assertEquals(listOf("읽는 중 — Net.h"), steps)
    }
}
