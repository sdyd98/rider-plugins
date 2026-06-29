package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests for the user-definable line format (template → regex + apply). Pure, headless. */
class LineFormatTest {

    @Test
    fun `template with bracket and paren literals splits time, thread, level, message`() {
        val fmt = LineFormat.of("%{time} [%{thread}] (%{level}) %{message}")
        assertTrue(fmt.valid, "template should compile: ${fmt.error}")
        assertFalse(fmt.isRegex)

        val line = "2026-06-29 09:00:01.001 [217220] (INFO) 플레이어 접속"
        val m = fmt.apply(line)!!
        assertEquals("2026-06-29 09:00:01.001", m.time)
        assertEquals("INFO", m.level)
        // message starts right after "(INFO) " → the Korean body, intact.
        assertEquals("플레이어 접속", line.substring(m.messageStart))
    }

    @Test
    fun `template with no delimiter still captures a space-containing timestamp via the time pattern`() {
        val fmt = LineFormat.of("%{time} %{level} %{message}")
        assertTrue(fmt.valid)
        val line = "2026-06-29 09:00:01.001 INFO hello world"
        val m = fmt.apply(line)!!
        assertEquals("2026-06-29 09:00:01.001", m.time) // not just "2026-06-29"
        assertEquals("INFO", m.level)
        assertEquals("hello world", line.substring(m.messageStart))
    }

    @Test
    fun `raw named-capture regex mode works and reports its groups`() {
        val fmt = LineFormat.of("""(?<level>\w+):\s*(?<message>.*)""")
        assertTrue(fmt.valid)
        assertTrue(fmt.isRegex)
        assertTrue("level" in fmt.groups && "message" in fmt.groups)
        assertFalse("time" in fmt.groups)
        val line = "WARN: disk almost full"
        val m = fmt.apply(line)!!
        assertNull(m.time)
        assertEquals("WARN", m.level)
        assertEquals("disk almost full", line.substring(m.messageStart))
    }

    @Test
    fun `a non-matching line returns null so the caller can fall back to the heuristic`() {
        val fmt = LineFormat.of("%{time} (%{level}) %{message}")
        assertNull(fmt.apply("    at com.foo.Bar(Bar.kt:10)")) // a stack frame doesn't match → null
    }

    @Test
    fun `an invalid regex is reported as not valid rather than throwing`() {
        val fmt = LineFormat.of("(?<level>[") // unbalanced
        assertFalse(fmt.valid)
        assertNull(fmt.apply("anything"))
    }

    @Test
    fun `LogParser applies a user format then falls back to the heuristic for non-matching lines`() {
        val fmts = listOf(LineFormat.of("%{time} (%{level}) %{message}"))

        val a = "2024-06-28 14:30:00 (WARN) low disk"
        val pa = LogParser.parse(a, fmts)
        assertEquals(LogLevel.WARN, pa.level)
        assertTrue(pa.timestampMillis != LogLine.NO_TIME)
        assertEquals("low disk", a.substring(pa.messageStart))

        // A stack frame doesn't match the format → falls back to the heuristic (no time, OTHER level).
        val pb = LogParser.parse("    at com.foo.Bar(Bar.kt:10)", fmts)
        assertEquals(LogLine.NO_TIME, pb.timestampMillis)
        assertEquals(LogLevel.OTHER, pb.level)
    }
}
