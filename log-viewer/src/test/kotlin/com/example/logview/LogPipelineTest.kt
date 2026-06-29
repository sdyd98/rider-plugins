package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Headless tests for the byte→line→parse→model data path — the parts the pre-release cleanup touched
 * ([ByteLineSplitter], lazy [LogLine] fields, [LogTableModel.appendRaw], ANSI handling). No IDE/Swing
 * UI is constructed, so this runs as a plain JUnit test.
 */
class LogPipelineTest {

    private val esc = 27.toChar() // ANSI escape (0x1B, the CSI introducer) — kept ASCII-only in source

    private fun collect(): Pair<MutableList<String>, (List<String>) -> Unit> {
        val out = mutableListOf<String>()
        return out to { batch -> out.addAll(batch) }
    }

    @Test
    fun `splitter emits complete LF lines and buffers the partial tail`() {
        val (out, sink) = collect()
        val s = ByteLineSplitter()
        // "hello\nwor" — one complete line, "wor" left buffered (no newline yet).
        "hello\nwor".toByteArray().let { s.feed(it, it.size, sink) }
        s.flush(sink)
        assertEquals(listOf("hello"), out)
        // The continuation arrives in a second feed; the buffered "wor" completes into "world".
        "ld\nbye\n".toByteArray().let { s.feed(it, it.size, sink) }
        s.flush(sink)
        assertEquals(listOf("hello", "world", "bye"), out)
    }

    @Test
    fun `splitter strips CR from CRLF and flushPartial emits a trailing line`() {
        val (out, sink) = collect()
        val s = ByteLineSplitter()
        "a\r\nb\r\nc".toByteArray().let { s.feed(it, it.size, sink) }
        s.flush(sink)
        assertEquals(listOf("a", "b"), out) // "c" has no newline → still buffered
        s.flushPartial(sink) // EOF without a trailing newline (the remote-stream case)
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test
    fun `splitter does not split a multi-byte UTF-8 char across the newline scan`() {
        val (out, sink) = collect()
        val s = ByteLineSplitter()
        val line = "한글 로그 ✓" // multi-byte; must round-trip intact
        "$line\n".toByteArray(Charsets.UTF_8).let { s.feed(it, it.size, sink) }
        s.flush(sink)
        assertEquals(listOf(line), out)
    }

    @Test
    fun `LocalLogReader reads whole lines and leaves a newline-less tail buffered`() {
        val file = Files.createTempFile("logview-test", ".log")
        try {
            Files.write(file, "line1\nline2\nline3".toByteArray(Charsets.UTF_8)) // no trailing newline
            val (out, sink) = collect()
            LocalLogReader(file).use { it.readInitial(sink) }
            // Local semantics: the last partial line stays buffered to resume on the next poll.
            assertEquals(listOf("line1", "line2"), out)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `appendRaw parses level + time and groups a stack-trace continuation into the block`() {
        val model = LogTableModel()
        model.appendRaw(
            listOf(
                "2024-06-28 14:30:00.123 ERROR something failed",
                "    at com.foo.Bar(Bar.kt:10)", // continuation (indented, no timestamp)
            )
        )
        assertEquals(2, model.rowCount)

        val primary = model.lineAt(0)
        assertEquals(LogLevel.ERROR, primary.level)
        assertTrue(primary.hasTime, "timestamped line should parse a time")
        assertTrue(
            primary.timeText.matches(Regex("""\d\d:\d\d:\d\d\.\d\d\d""")),
            "timeText should be a clock string, was '${primary.timeText}'",
        )
        assertTrue(primary.message.contains("something failed"), "message was '${primary.message}'")

        val cont = model.lineAt(1)
        assertTrue(cont.isContinuation, "indented stack frame should be a continuation")
        assertEquals(LogLevel.ERROR, cont.level, "continuation inherits its block's level")
        assertEquals(0, cont.blockStart, "continuation points at the primary line's index")
    }

    @Test
    fun `appendRaw parses on ANSI-stripped text and keeps colored message for rendering`() {
        val model = LogTableModel()
        val raw = "$esc[32mINFO$esc[0m green payload"
        model.appendRaw(listOf(raw))
        val line = model.lineAt(0)

        assertTrue(line.hasAnsi, "raw contains ANSI escape codes")
        assertFalse(line.display.contains(esc), "display (clean) must be ANSI-stripped")
        assertEquals(LogLevel.INFO, line.level, "level detected on the stripped 'INFO' token")
        // The lazy ANSI span caches must parse without error and yield at least one span.
        assertTrue(line.rawSpans.isNotEmpty(), "rawSpans should parse the colored whole line")
        assertTrue(line.messageSpans.isNotEmpty(), "messageSpans should parse the colored message body")
    }

    @Test
    fun `blank lines survive in the model (the view filters them, not the parser)`() {
        val model = LogTableModel()
        model.appendRaw(listOf("first", "", "second"))
        assertEquals(3, model.rowCount)
        assertTrue(model.lineAt(1).raw.isBlank())
    }
}
