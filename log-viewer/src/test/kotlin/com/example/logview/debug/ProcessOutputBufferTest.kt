package com.example.logview.debug

import com.example.logview.TailState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The debug-tab source: process output chunks are NOT line-aligned and stdout/stderr pump on separate
 * threads — assembly must be per-stream, and the reader's initial-read → tail handoff must lose and
 * duplicate nothing regardless of when lines arrive. Readers can filter by stream (the 출력 소스 toggle).
 *
 * Listener delivery is ASYNCHRONOUS by design (producers may be the EDT and must never block on the
 * consumer — that combination froze the IDE), so assertions await the delivery thread.
 */
class ProcessOutputBufferTest {

    /** Await an async-delivery expectation (delivery runs on the buffer's daemon thread). */
    private fun <T> await(expected: T, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000
        while (actual() != expected && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(expected, actual())
    }

    @Test
    fun `fragments assemble into lines, per stream, with crlf stripped`() {
        val buf = ProcessOutputBuffer()
        val got = CopyOnWriteArrayList<String>()
        buf.snapshotAndListen { _, lines -> got.addAll(lines) }

        buf.append("hel", "stdout")
        buf.append("lo\r\nwor", "stdout")
        buf.append("ERR-", "stderr") // interleaved fragment of the OTHER stream
        buf.append("ld\n", "stdout")
        buf.append("1\n", "stderr")
        await(listOf("hello", "world", "ERR-1")) { got.toList() }

        buf.append("tail without newline", "stdout")
        Thread.sleep(50)
        assertEquals(3, got.size) // not delivered yet — no newline
        buf.flush()
        await(4) { got.size }
        assertEquals("tail without newline", got.last())
        buf.close()
    }

    @Test
    fun `late reader replays history then follows live, no gap no duplicate`() {
        val buf = ProcessOutputBuffer()
        buf.append("a\nb\n", "stdout")

        val r = buf.newReader()
        val initial = ArrayList<String>()
        r.readInitial { initial.addAll(it) }
        assertEquals(listOf("a", "b"), initial) // snapshot is synchronous and atomic with subscribe

        // Lines arriving between readInitial and startTail must be queued, then drained in order.
        buf.append("c\n", "stdout")
        val tailed = CopyOnWriteArrayList<String>()
        r.startTail(onAppend = { tailed.addAll(it) }, onError = { throw it }, onState = { assertEquals(TailState.LIVE, it) })
        buf.append("d\n", "stdout")
        await(listOf("c", "d")) { tailed.toList() }

        r.close()
        buf.append("after close\n", "stdout")
        Thread.sleep(50)
        assertEquals(listOf("c", "d"), tailed.toList()) // closed readers receive nothing
        buf.close()
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

        val tailed = CopyOnWriteArrayList<String>()
        r.startTail(onAppend = { tailed.addAll(it) }, onError = { throw it })
        buf.append("more console\n", "console#1")
        buf.append("more debug\n", "console#2")
        await(listOf("more debug")) { tailed.toList() }
        r.close()
        buf.close()
    }

    @Test
    fun `bounded buffer drops oldest and the reader reports it`() {
        val buf = ProcessOutputBuffer(maxLines = 3)
        buf.append("1\n2\n3\n4\n5\n", "stdout")

        val initial = ArrayList<String>()
        buf.newReader().readInitial { initial.addAll(it) }

        assertTrue(initial.first().contains("2줄")) // "… 이전 2줄이 버퍼 한도로 생략되었습니다"
        assertEquals(listOf("3", "4", "5"), initial.drop(1))
        buf.close()
    }

    @Test
    fun `producers never block - a slow consumer cannot stall append`() {
        val buf = ProcessOutputBuffer()
        val gate = java.util.concurrent.CountDownLatch(1)
        buf.snapshotAndListen { _, _ -> gate.await() } // consumer wedged (like a busy EDT)

        // With synchronous delivery this would deadlock the caller — append must return regardless.
        val t0 = System.currentTimeMillis()
        repeat(50) { buf.append("line $it\n", "stdout") }
        assertTrue(System.currentTimeMillis() - t0 < 2_000, "append blocked on a stuck consumer")
        gate.countDown()
        buf.close()
    }
}
