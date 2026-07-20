package com.example.logview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Headless tests for [LocalLogReader]'s tail-first open of huge files (seek + first-line alignment). */
class LocalLogReaderTest {

    @TempDir
    lateinit var dir: Path

    private fun writeLines(n: Int): Pair<Path, List<String>> {
        val lines = (0 until n).map { "line %04d payload".format(it) }
        val f = dir.resolve("test.log")
        Files.write(f, (lines.joinToString("\n") + "\n").toByteArray())
        return f to lines
    }

    private fun readAll(reader: LocalLogReader): List<String> {
        val out = ArrayList<String>()
        reader.readInitial { out.addAll(it) }
        return out
    }

    @Test
    fun `small file loads whole and skips nothing`() {
        val (f, lines) = writeLines(100)
        val reader = LocalLogReader(f)
        assertEquals(lines, readAll(reader))
        assertEquals(0, reader.skippedHeadBytes())
        assertEquals(1f, reader.initialProgress())
    }

    @Test
    fun `huge file opens from the tail on a complete line boundary`() {
        val (f, lines) = writeLines(1000)
        val reader = LocalLogReader(f, tailOpenBytes = 500) // "huge" for the test
        val got = readAll(reader)

        assertTrue(got.isNotEmpty() && got.size < lines.size)
        assertEquals(lines.takeLast(got.size), got) // an exact SUFFIX — no torn first line
        assertTrue(reader.skippedHeadBytes() > 0)
        assertEquals(Files.size(f) - reader.skippedHeadBytes(), got.sumOf { it.length + 1L })
        assertEquals(1f, reader.initialProgress())
    }

    @Test
    fun `cancelInitial stops the read early`() {
        val (f, _) = writeLines(50_000)
        val reader = LocalLogReader(f)
        val out = ArrayList<String>()
        reader.readInitial { batch ->
            out.addAll(batch)
            reader.cancelInitial() // ask to stop after the first delivered batch
        }
        assertTrue(out.isNotEmpty() && out.size < 50_000)
    }

    // ---- Idle flush of a final line with no trailing newline (maybeFlushIdleTail) ----

    private fun awaitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    /** Start a fast-polling reader (initial + tail) appending into a synchronized [out] list. */
    private fun tailToList(file: Path, out: MutableList<String>): LocalLogReader {
        val reader = LocalLogReader(file, pollMs = 20)
        reader.readInitial { out.addAll(it) }
        reader.startTail(onAppend = { out.addAll(it) }, onError = {})
        return reader
    }

    @Test
    fun `a final line without trailing newline is flushed once the file goes quiet`() {
        val f = dir.resolve("truncated.log")
        // Crash-truncated: the last (often most important) line has no trailing newline.
        Files.write(f, "one\ntwo\nlast words".toByteArray())
        val out = java.util.Collections.synchronizedList(mutableListOf<String>())
        tailToList(f, out).use {
            awaitUntil { out.size >= 3 }
            assertEquals(listOf("one", "two", "last words"), out.toList())
        }
    }

    @Test
    fun `a partial line completed before the quiet threshold arrives whole with no duplicate`() {
        val f = dir.resolve("resumed.log")
        Files.write(f, "one\npart".toByteArray())
        val out = java.util.Collections.synchronizedList(mutableListOf<String>())
        tailToList(f, out).use {
            // Complete the line well inside the quiet window — it must arrive as ONE whole line.
            Files.write(f, "ial\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
            awaitUntil { out.size >= 2 }
            assertEquals(listOf("one", "partial"), out.toList())
        }
    }
}
