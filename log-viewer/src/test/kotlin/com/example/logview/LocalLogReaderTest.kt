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
}
