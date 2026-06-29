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

    @Test
    fun `buildRegex from clicked token regions produces a working, generalizing format`() {
        val line = "2026-06-29 09:00:01.001 [worker-3] ERROR com.game.Db - refused"
        val toks = LineFormat.tokenize(line)
        assertEquals("[worker-3]", toks[2].text) // brackets are part of the token

        // Simulate clicks: tokens 0+1 = time, 2 = thread, 3 = level, 4 = message-start.
        val assign = mapOf(0 to "time", 1 to "time", 2 to "thread", 3 to "level", 4 to "message")
        val rx = LineFormat.buildRegex(line, toks, assign)
        val fmt = LineFormat.fromRegex(rx)
        assertTrue(fmt.valid, "generated regex should compile: $rx")
        assertTrue(setOf("time", "thread", "level", "message").all { it in fmt.groups })

        val m = fmt.apply(line)!!
        assertEquals("ERROR", m.level)
        assertEquals("com.game.Db - refused", line.substring(m.messageStart))

        // The generated format generalizes to another line of the SAME shape (different values/lengths).
        val line2 = "2026-06-29 09:00:02.500 [main] WARN disk low"
        val m2 = fmt.apply(line2)!!
        assertEquals("WARN", m2.level)
        assertEquals("disk low", line2.substring(m2.messageStart))
    }

    @Test
    fun `buildRegex wildcards an unassigned token so a skipped field still generalizes`() {
        val line = "2026-06-29 09:00:01.001 [worker-3] ERROR boot done"
        val toks = LineFormat.tokenize(line)
        // Assign time (0,1), SKIP thread (2 unassigned → \S+), level (3), message (4).
        val assign = mapOf(0 to "time", 1 to "time", 3 to "level", 4 to "message")
        val fmt = LineFormat.fromRegex(LineFormat.buildRegex(line, toks, assign))
        assertTrue(fmt.valid)
        assertEquals("ERROR", fmt.apply(line)!!.level)

        // Generalizes even though the skipped token differs ([worker-3] vs [main]).
        val line2 = "2026-06-29 09:00:02.000 [main] WARN low disk"
        val m2 = fmt.apply(line2)!!
        assertEquals("WARN", m2.level)
        assertEquals("low disk", line2.substring(m2.messageStart))
    }
}
