package com.example.codemap

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cross-checks [FileFacts] against ground truth from the shell on a REAL C++ repository — the whole
 * value proposition of this plugin is that its facts are exactly true, so they are verified against
 * `wc -l`, `shasum`, and `git` rather than against our own reimplementation.
 *
 * Opt-in: pass -Dcodemap.testRepo=/path/to/a/git/cpp/repo. Skipped otherwise.
 */
class RealRepoFactsTest {

    // isNotBlank() first: File("") resolves to the process working directory on this JVM, so without
    // the blank guard an unset property silently points every test at the module folder.
    private val repo: File? = System.getProperty("codemap.testRepo")
        ?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(repo!!).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return out.trim()
    }

    @Test
    fun `line counts match wc -l`() {
        assumeTrue(repo != null)
        listOf("imgui.cpp", "imgui.h", "imgui_internal.h").forEach { rel ->
            val f = File(repo, rel)
            assumeTrue(f.isFile)
            val expected = sh("wc", "-l", rel).trim().split(Regex("\\s+")).first().toInt()
            assertEquals(expected, FileFacts.size(f).lines, "line count for $rel")
        }
    }

    @Test
    fun `sha256 matches shasum`() {
        assumeTrue(repo != null)
        val rel = "imgui.h"
        val f = File(repo, rel)
        assumeTrue(f.isFile)
        val expected = "sha256:" + sh("shasum", "-a", "256", rel).split(Regex("\\s+")).first()
        assertEquals(expected, FileFacts.sha256(f), "sha256 for $rel")
    }

    @Test
    fun `git facts match git`() {
        assumeTrue(repo != null)
        val rel = "imgui.cpp"
        assumeTrue(File(repo, rel).isFile)
        val g = FileFacts.git(repo!!, rel)
        assertEquals(sh("git", "log", "-n", "1", "--format=%H", "--", rel), g?.lastCommit)
        assertEquals(sh("git", "log", "-n", "1", "--format=%ad", "--date=short", "--", rel), g?.lastDate)
        assertEquals(sh("git", "rev-list", "--count", "HEAD", "--", rel).toInt(), g?.commitCount)
        assertEquals(sh("git", "rev-parse", "HEAD"), FileFacts.headCommit(repo))
    }

    @Test
    fun `include extraction matches the literal include lines in the file`() {
        assumeTrue(repo != null)
        val rel = "imgui.cpp"
        val f = File(repo, rel)
        assumeTrue(f.isFile)
        // Ground truth: every line that literally starts (after whitespace) with #include, deduplicated.
        val expected = f.readLines()
            .mapNotNull { Regex("""^\s*#\s*include\s*[<"]([^">]+)[">]""").find(it)?.groupValues?.get(1) }
            .distinct()
        val actual = FileFacts.includes(f)
        assertEquals(expected, actual)
        assert(actual.isNotEmpty()) { "expected some includes in $rel" }
        println("[facts] $rel: ${FileFacts.size(f).lines} lines, ${actual.size} includes, " +
            "${FileFacts.git(repo!!, rel)?.commitCount} commits")
    }

    @Test
    fun `the codemap root is the git working tree, not the folder a solution happens to sit in`() {
        assumeTrue(repo != null)
        val sub = File(repo, "examples")
        assumeTrue(sub.isDirectory)
        // Rider opens examples/imgui_examples.sln and reports examples/ as the project base path. If
        // that were the root, every source file at the top level would be "outside the project".
        assertEquals(repo!!.canonicalFile, FileFacts.gitToplevel(sub)?.canonicalFile)
        assertEquals(repo.canonicalFile, FileFacts.gitToplevel(repo)?.canonicalFile)
    }

    @Test
    fun `pairing finds the h-cpp pair in a real directory`() {
        assumeTrue(repo != null)
        val siblings = repo!!.list()!!.toList()
        assertEquals("imgui.h", CodemapPaths.noteKey("imgui.cpp", siblings))
        assertEquals(listOf("imgui.h", "imgui.cpp"), CodemapPaths.pairOf("imgui.h", siblings))
        // imgui_demo.cpp has no imgui_demo.h — it must key itself, not borrow a neighbour's header.
        assertEquals("imgui_demo.cpp", CodemapPaths.noteKey("imgui_demo.cpp", siblings))
    }
}
