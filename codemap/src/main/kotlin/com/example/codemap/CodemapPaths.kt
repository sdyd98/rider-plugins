package com.example.codemap

/**
 * Pure path arithmetic for the `.codemap` store — no IO, no interpretation, no guessing. Kept in its
 * own object so the mapping rules are covered by headless tests.
 *
 * Layout: notes are bundled PER SOURCE DIRECTORY, mirroring the source tree. On a 58M-line codebase a
 * note file per source file would mean hundreds of thousands of tiny JSONs; one bundle per directory
 * keeps the file count in line with the directory count and keeps a diff readable.
 *
 *     Source/Game/Net/PlayerSession.h  ->  .codemap/Source/Game/Net.json   (key: "PlayerSession.h")
 *     README.md (project root)         ->  .codemap/_root.json
 *
 * `.codemap/Source/Game.json` (a bundle) and `.codemap/Source/Game/` (a directory of deeper bundles)
 * coexist without collision — one is a file, the other a directory.
 */
object CodemapPaths {
    const val DIR = ".codemap"
    const val ROOT_BUNDLE = "_root.json"
    const val PENDING = "_pending.json"

    /**
     * Bundle paths (relative to `.codemap/`) that are the store's own bookkeeping rather than a
     * mirrored source directory. Matched EXACTLY, not by a `_` prefix — a source directory may
     * legitimately be named `_demo` or `_generated`, and its bundle (`_demo.json`) must still be
     * enumerated as notes.
     */
    fun isBookkeeping(bundleRelPath: String): Boolean = bundleRelPath == PENDING

    val HEADER_EXT = setOf("h", "hpp", "hxx", "hh", "inl")
    val SOURCE_EXT = setOf("cpp", "cc", "cxx", "c")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    fun isCodeFile(name: String): Boolean = ext(name).let { it in HEADER_EXT || it in SOURCE_EXT }

    /** The bundle path (relative to `.codemap/`) holding notes for the directory [relFile] lives in. */
    fun bundleFor(relFile: String): String {
        val dir = relFile.substringBeforeLast('/', "")
        return if (dir.isEmpty()) ROOT_BUNDLE else "$dir.json"
    }

    /** The source directory (project-relative, "" for the root) that a bundle path maps back to. */
    fun dirOfBundle(bundleRelPath: String): String =
        if (bundleRelPath == ROOT_BUNDLE) "" else bundleRelPath.removeSuffix(".json")

    /**
     * The key [relFile] is filed under inside its bundle: a `.h`/`.cpp` pair shares ONE note, keyed by
     * the header. Pairing is mechanical — same directory, same base name, one header extension and one
     * source extension. When no header sibling exists the file keys itself.
     *
     * [siblingNames] are the plain file names present in the same directory.
     */
    fun noteKey(relFile: String, siblingNames: Collection<String>): String {
        val name = relFile.substringAfterLast('/')
        if (ext(name) in HEADER_EXT) return name
        if (ext(name) !in SOURCE_EXT) return name
        val base = baseName(name)
        return siblingNames.firstOrNull { ext(it) in HEADER_EXT && baseName(it) == base } ?: name
    }

    /** Every file in the directory that belongs to [noteKey]'s pair, header first. Mechanical. */
    fun pairOf(noteKey: String, siblingNames: Collection<String>): List<String> {
        val base = baseName(noteKey)
        val members = siblingNames.filter { isCodeFile(it) && baseName(it) == base }
        return members.sortedBy { if (ext(it) in HEADER_EXT) 0 else 1 }
    }

    private val INCLUDE = Regex("""^[ \t]*#[ \t]*include[ \t]*[<"]([^">]+)[">]""")

    /**
     * The `#include` targets literally present in [text], in order, deduplicated. This is a verbatim
     * text extraction, NOT a preprocessor: lines inside `#if 0` / platform `#ifdef` blocks are included
     * just the same, and macro-built include paths are not resolved. Exact about what the file says;
     * it makes no claim about what the compiler actually includes.
     */
    fun includes(text: String): List<String> =
        text.lineSequence().mapNotNull { INCLUDE.find(it)?.groupValues?.get(1) }.distinct().toList()
}
