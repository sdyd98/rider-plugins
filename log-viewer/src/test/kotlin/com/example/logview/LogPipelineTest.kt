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

    // The viewer no longer auto-analyzes without a format, so these pipeline tests pass an explicit one.
    private val timeFmt = listOf(LineFormat.of("%{time} %{level} %{message}"))

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
            ),
            timeFmt,
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
        model.appendRaw(listOf(raw), listOf(LineFormat.of("%{level} %{message}")))
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

    @Test
    fun `continuation line containing a stray level token is not truncated in the Message column`() {
        val model = LogTableModel()
        model.appendRaw(
            listOf(
                "2024-06-28 14:30:00.123 ERROR boom",
                "    {\"level\": \"ERROR\", \"x\": 1}", // continuation whose body contains a level token
            ),
            timeFmt,
        )
        val cont = model.lineAt(1)
        assertTrue(cont.isContinuation, "indented payload line should be a continuation")
        // A continuation is all body (messageStart forced to 0) — the message must be the WHOLE line,
        // not the truncated tail after the stray "ERROR" token.
        assertTrue(cont.message.contains("\"level\""), "continuation message was truncated: '${cont.message}'")
        assertTrue(cont.message.contains("\"x\": 1"), "continuation message was truncated: '${cont.message}'")
    }

    @Test
    fun `splitter decodes CP949 Korean bytes with the matching charset and garbles them with the wrong one`() {
        val cp949 = java.nio.charset.Charset.forName("MS949")
        val line = "한글 로그 테스트"
        val bytes = "$line\n".toByteArray(cp949)

        // Matching charset → exact round-trip.
        val (ok, okSink) = collect()
        ByteLineSplitter(charset = cp949).apply { feed(bytes, bytes.size, okSink); flush(okSink) }
        assertEquals(listOf(line), ok)

        // Wrong charset (UTF-8) → mojibake, NOT the original — exactly the case the encoding picker fixes.
        val (bad, badSink) = collect()
        ByteLineSplitter(charset = Charsets.UTF_8).apply { feed(bytes, bytes.size, badSink); flush(badSink) }
        assertEquals(1, bad.size)
        assertTrue(bad[0] != line, "CP949 bytes decoded as UTF-8 should not match the original Korean")
    }

    @Test
    fun `charset probe flags CP949 bytes decoded as UTF-8 and passes matching charsets`() {
        val cp949 = java.nio.charset.Charset.forName("MS949")
        val bytes = "한글 로그 감지 테스트\n".toByteArray(cp949)
        val sink: (List<String>) -> Unit = {}

        // CP949 bytes under the UTF-8 default → invalid sequences → the probe flags a wrong charset.
        assertTrue(ByteLineSplitter(charset = Charsets.UTF_8).apply { feed(bytes, bytes.size, sink) }.charsetLooksWrong())
        // The same bytes under the right charset are clean.
        assertFalse(ByteLineSplitter(charset = cp949).apply { feed(bytes, bytes.size, sink) }.charsetLooksWrong())
        // Genuine UTF-8 (Korean included) is clean under UTF-8.
        val utf8 = "한글 UTF-8 로그\n".toByteArray(Charsets.UTF_8)
        assertFalse(ByteLineSplitter(charset = Charsets.UTF_8).apply { feed(utf8, utf8.size, sink) }.charsetLooksWrong())
        // UTF-8 Korean under CP949 (odd byte count → a dangling lead byte) is flagged — the revert signal.
        val odd = "안\n".toByteArray(Charsets.UTF_8)
        assertTrue(ByteLineSplitter(charset = cp949).apply { feed(odd, odd.size, sink) }.charsetLooksWrong())
    }

    @Test
    fun `charset probe ignores a crash-truncated partial line flushed at EOF`() {
        // A tail cut mid-character is legitimate truncation, not charset evidence.
        val bytes = "한글".toByteArray(Charsets.UTF_8).copyOfRange(0, 4) // ends mid-syllable, no newline
        val s = ByteLineSplitter(charset = Charsets.UTF_8)
        s.feed(bytes, bytes.size) {}
        s.flushPartial {}
        assertFalse(s.charsetLooksWrong())
    }

    @Test
    fun `parseLogRow extracts level and time off-EDT and flags continuation candidates`() {
        // parseLogRow is pure (no model/Swing state) so the heavy parse can run off the EDT.
        val primary = parseLogRow("2024-06-28 14:30:00.123 ERROR boom", timeFmt)
        assertEquals(LogLevel.ERROR, primary.level)
        assertTrue(primary.timestampMillis != LogLine.NO_TIME, "timestamped line parses a time")
        assertFalse(primary.continuationCandidate, "a timestamped primary is not a continuation")

        val cont = parseLogRow("    at com.foo.Bar(Bar.kt:10)", timeFmt)
        assertTrue(cont.continuationCandidate, "an indented, timeless stack frame is a continuation candidate")

        // appendParsed(parseLogRow(...)) must match the eager appendRaw path (same blocks/levels).
        val model = LogTableModel()
        model.appendParsed(listOf(primary, cont))
        assertEquals(2, model.rowCount)
        assertEquals(LogLevel.ERROR, model.lineAt(1).level) // continuation inherits the block level
        assertEquals(0, model.lineAt(1).blockStart)
    }
}
