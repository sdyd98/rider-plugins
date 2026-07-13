package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Headless tests for [ErrorSummary] — the grouping behind the 에러 요약 popup. */
class ErrorSummaryTest {

    @Test
    fun `normalize collapses numbers hex ids and guids`() {
        assertEquals("결제 실패 orderId=#", ErrorSummary.normalize("결제 실패 orderId=1234"))
        assertEquals("session # expired", ErrorSummary.normalize("session deadbeef01 expired"))
        assertEquals("req # timed out", ErrorSummary.normalize("req 3f2b8a10-aaaa-bbbb-cccc-123456789012 timed out"))
        assertEquals("worker-# stalled #ms", ErrorSummary.normalize("worker-3   stalled 5012ms"))
    }

    @Test
    fun `same-shape errors group and sort by count`() {
        val entries = listOf(
            ErrorSummary.Entry(0, 10, 1_000, "결제 실패 orderId=1"),
            ErrorSummary.Entry(5, 15, 2_000, "DB 연결 끊김 host=db1"),
            ErrorSummary.Entry(9, 19, 3_000, "결제 실패 orderId=2"),
            ErrorSummary.Entry(12, 22, 4_000, "결제 실패 orderId=777"),
        )
        val groups = ErrorSummary.summarize(entries)
        assertEquals(2, groups.size)

        val pay = groups[0] // most frequent first
        assertEquals("결제 실패 orderId=#", pay.pattern)
        assertEquals(3, pay.count)
        assertEquals(10, pay.firstLine)
        assertEquals(22, pay.lastLine)
        assertEquals(1_000, pay.firstTime)
        assertEquals(4_000, pay.lastTime)
        assertEquals(12, pay.lastModelRow) // jump target = most recent occurrence

        assertEquals(1, groups[1].count)
    }

    @Test
    fun `timeless lines keep NO_TIME markers`() {
        val g = ErrorSummary.summarize(
            listOf(ErrorSummary.Entry(0, 1, LogLine.NO_TIME, "boom"), ErrorSummary.Entry(1, 2, LogLine.NO_TIME, "boom"))
        ).single()
        assertEquals(LogLine.NO_TIME, g.firstTime)
        assertEquals(LogLine.NO_TIME, g.lastTime)
        assertEquals(2, g.count)
    }
}
