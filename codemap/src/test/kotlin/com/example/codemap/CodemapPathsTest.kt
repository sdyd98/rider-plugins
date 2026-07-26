package com.example.codemap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The store's path arithmetic — the rules that decide where a note lives and what it covers. */
class CodemapPathsTest {

    @Test
    fun `bundle mirrors the source directory`() {
        assertEquals("Source/Game/Net.json", CodemapPaths.bundleFor("Source/Game/Net/PlayerSession.h"))
        assertEquals("Source.json", CodemapPaths.bundleFor("Source/main.cpp"))
        assertEquals(CodemapPaths.ROOT_BUNDLE, CodemapPaths.bundleFor("main.cpp"))
    }

    @Test
    fun `bundle path round-trips to its source directory`() {
        val rel = "Source/Game/Net/PlayerSession.h"
        assertEquals("Source/Game/Net", CodemapPaths.dirOfBundle(CodemapPaths.bundleFor(rel)))
        assertEquals("", CodemapPaths.dirOfBundle(CodemapPaths.bundleFor("main.cpp")))
    }

    @Test
    fun `a cpp files itself under its header, so the pair shares one note`() {
        val siblings = listOf("PlayerSession.h", "PlayerSession.cpp", "Other.cpp")
        assertEquals("PlayerSession.h", CodemapPaths.noteKey("Net/PlayerSession.cpp", siblings))
        assertEquals("PlayerSession.h", CodemapPaths.noteKey("Net/PlayerSession.h", siblings))
    }

    @Test
    fun `a cpp with no header sibling keys itself`() {
        assertEquals("main.cpp", CodemapPaths.noteKey("Source/main.cpp", listOf("main.cpp", "Other.h")))
    }

    @Test
    fun `pair lists the header first and ignores unrelated files`() {
        val siblings = listOf("PlayerSession.cpp", "PlayerSession.h", "PlayerSession.txt", "Other.h")
        assertEquals(
            listOf("PlayerSession.h", "PlayerSession.cpp"),
            CodemapPaths.pairOf("PlayerSession.h", siblings),
        )
    }

    @Test
    fun `includes are extracted verbatim, deduplicated, in order`() {
        val text = """
            #pragma once
            #include "Net/Session.h"
            #include <vector>
            #ifdef _WIN32
            #  include <windows.h>
            #endif
            #include "Net/Session.h"
            // #include "Commented.h"
            const char* s = "#include \"NotAnInclude.h\"";
        """.trimIndent()
        assertEquals(
            listOf("Net/Session.h", "vector", "windows.h"),
            CodemapPaths.includes(text),
        )
    }

    @Test
    fun `only the queue is bookkeeping - an underscore source directory still holds notes`() {
        assert(CodemapPaths.isBookkeeping(CodemapPaths.PENDING))
        assert(!CodemapPaths.isBookkeeping("Net.json"))
        assert(!CodemapPaths.isBookkeeping(CodemapPaths.ROOT_BUNDLE))
        // A source directory named `_demo` maps to `_demo.json`; it is notes, not bookkeeping.
        assert(!CodemapPaths.isBookkeeping(CodemapPaths.bundleFor("_demo/PlayerSession.h")))
        assertEquals("_demo", CodemapPaths.dirOfBundle("_demo.json"))
    }
}
