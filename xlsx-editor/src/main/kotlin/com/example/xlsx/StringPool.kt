package com.example.xlsx

/**
 * Per-parse display-string dedup: game-data columns repeat enum-ish values ("TRUE", grades,
 * category names) across thousands of rows, and rendering each occurrence as a fresh String costs
 * hundreds of MB of heap + GC on multi-million-cell workbooks. One hash probe per cell; cleared
 * when unique-heavy columns (ids) fill the cap. Used by both the .xlsx and .xls read paths.
 * Not thread-safe — use one pool per sheet parse.
 */
internal class StringPool {
    private val interned = HashMap<String, String>(4096)

    fun dedup(value: String?): String? {
        if (value == null || value.length > 64) return value
        if (interned.size >= 65_536) interned.clear()
        return interned.getOrPut(value) { value }
    }
}
