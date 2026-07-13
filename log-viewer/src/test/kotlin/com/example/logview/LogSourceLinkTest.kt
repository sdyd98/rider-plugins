package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Headless tests for [LogSourceLink.find] — the pure text-parsing half of the source jump. */
class LogSourceLinkTest {

    @Test
    fun `jvm stack frame`() {
        val t = "\tat com.example.pay.Gateway\$Stage3.call(Gateway.java:121)"
        val link = LogSourceLink.find(t)!!
        assertEquals("Gateway.java", link.file)
        assertEquals(121, link.line)
        assertEquals(false, link.isPath)
        assertEquals("Gateway.java:121", t.substring(link.span.first, link.span.last))
    }

    @Test
    fun `kotlin frame and korean text around it`() {
        val link = LogSourceLink.find("결제 실패 원인 at pay.Retry.run(Retry.kt:31) 재시도 예정")!!
        assertEquals("Retry.kt", link.file)
        assertEquals(31, link.line)
    }

    @Test
    fun `dotnet frame carries the full path`() {
        val link = LogSourceLink.find("   at Game.Server.Session.Handle() in /src/Server/Session.cs:line 88")!!
        assertEquals("/src/Server/Session.cs", link.file)
        assertEquals(88, link.line)
        assertTrue(link.isPath)
    }

    @Test
    fun `dotnet windows path`() {
        val link = LogSourceLink.find("at X.Y() in C:\\build\\Game\\Combat.cs:line 7")!!
        assertEquals("C:\\build\\Game\\Combat.cs", link.file)
        assertEquals(7, link.line)
    }

    @Test
    fun `python traceback`() {
        val link = LogSourceLink.find("  File \"/srv/app/worker.py\", line 42, in handle")!!
        assertEquals("/srv/app/worker.py", link.file)
        assertEquals(42, link.line)
        assertTrue(link.isPath)
    }

    @Test
    fun `plain lines have no link`() {
        assertNull(LogSourceLink.find("2026-07-13 09:00:01 INFO heartbeat ok seq=42"))
        assertNull(LogSourceLink.find("cache miss key=user:1234 (took 8ms)"))
    }
}
