package com.example.logview.debug

import com.example.logview.TailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The debug-tab source: process output chunks are NOT line-aligned and stdout/stderr pump on separate
 * threads — assembly must be per-stream, and the reader's initial-read → tail handoff must lose and
 * duplicate nothing regardless of when lines arrive. Readers can filter by stream (the 출력 소스 toggle).
 */
class ProcessOutputBufferTest {

    @Test
    fun `fragments assemble into lines, per stream, with crlf stripped`() {
        val buf = ProcessOutputBuffer()
        val got = ArrayList<String>()
        buf.snapshotAndListen { _, lines -> got.addAll(lines) }

        buf.append("hel", "stdout")
        buf.append("lo\r\nwor", "stdout")
        buf.append("ERR-", "stderr") // interleaved fragment of the OTHER stream
        buf.append("ld\n", "stdout")
        buf.append("1\n", "stderr")

        assertEquals(listOf("hello", "world", "ERR-1"), got)

        buf.append("tail without newline", "stdout")
        assertEquals(3, got.size) // not delivered yet
        buf.flush()
        assertEquals("tail without newline", got.last())
    }

    @Test
    fun `late reader replays history then follows live, no gap no duplicate`() {
        val buf = ProcessOutputBuffer()
        buf.append("a\nb\n", "stdout")

        val r = buf.newReader()
        val initial = ArrayList<String>()
        r.readInitial { initial.addAll(it) }
        assertEquals(listOf("a", "b"), initial)

        // Lines arriving between readInitial and startTail must be queued, then drained in order.
        buf.append("c\n", "stdout")
        val tailed = ArrayList<String>()
        r.startTail(onAppend = { tailed.addAll(it) }, onError = { throw it }, onState = { assertEquals(TailState.LIVE, it) })
        buf.append("d\n", "stdout")
        assertEquals(listOf("c", "d"), tailed)

        r.close()
        buf.append("after close\n", "stdout")
        assertEquals(listOf("c", "d"), tailed) // closed readers receive nothing
    }

    @Test
    fun `source-filtered reader sees only its streams, initial and live`() {
        val buf = ProcessOutputBuffer()
        buf.append("console line\n", "console#1")
        buf.append("debug line\n", "console#2")

        val r = buf.newReader { it == "console#2" }
        val initial = ArrayList<String>()
        r.readInitial { initial.addAll(it) }
        assertEquals(listOf("debug line"), initial)

        val tailed = ArrayList<String>()
        r.startTail(onAppend = { tailed.addAll(it) }, onError = { throw it })
        buf.append("more console\n", "console#1")
        buf.append("more debug\n", "console#2")
        assertEquals(listOf("more debug"), tailed)
        r.close()
    }

    @Test
    fun `bounded buffer drops oldest and the reader reports it`() {
        val buf = ProcessOutputBuffer(maxLines = 3)
        buf.append("1\n2\n3\n4\n5\n", "stdout")

        val initial = ArrayList<String>()
        buf.newReader().readInitial { initial.addAll(it) }

        assertTrue(initial.first().contains("2줄")) // "… 이전 2줄이 버퍼 한도로 생략되었습니다"
        assertEquals(listOf("3", "4", "5"), initial.drop(1))
    }
}
