package com.example.logview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persisted line formats: a **library** of named formats the user builds (via the
 * region picker) plus which one is currently **active** (applied to parsing). The active format's
 * compiled [LineFormat] is cached and invalidated on any change. Empty by default — with no active
 * format the viewer parses with the built-in heuristic exactly as before.
 */
@State(name = "LogViewerLineFormats", storages = [Storage("logviewer-line-formats.xml")])
class LineFormatStore : PersistentStateComponent<LineFormatStore.State> {

    /** A persisted named format (the pattern is a generated/typed regex). */
    class SavedFormat(var name: String = "", var pattern: String = "")

    class State {
        var library: MutableList<SavedFormat> = mutableListOf()
        var activeName: String? = null
    }

    private var state = State()
    @Volatile private var cache: List<LineFormat>? = null

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
        cache = null
    }

    /** The saved-format library, in insertion order (for the settings list). */
    fun library(): List<SavedFormat> = state.library

    fun activeName(): String? = state.activeName

    /** The active format compiled — what the parser tries per line (cached); empty if none active/valid. */
    fun active(): List<LineFormat> = cache ?: run {
        val fmt = state.library.firstOrNull { it.name == state.activeName }
            ?.let { LineFormat.of(it.pattern) }?.takeIf { it.valid }
        (if (fmt != null) listOf(fmt) else emptyList()).also { cache = it }
    }

    /** Save (add, or replace by name) a format and make it the active one. */
    fun save(name: String, pattern: String) {
        val i = state.library.indexOfFirst { it.name == name }
        if (i >= 0) state.library[i].pattern = pattern else state.library.add(SavedFormat(name, pattern))
        state.activeName = name
        cache = null
    }

    /** Make a saved format active (or pass null to apply none → heuristic only). */
    fun activate(name: String?) {
        state.activeName = name
        cache = null
    }

    fun remove(name: String) {
        state.library.removeAll { it.name == name }
        // If the active format was removed, fall back to the first remaining one (or none → heuristic).
        if (state.activeName == name) state.activeName = state.library.firstOrNull()?.name
        cache = null
    }

    /** A unique default name like "포맷 3" for a freshly saved format. */
    fun nextDefaultName(): String {
        var n = state.library.size + 1
        while (state.library.any { it.name == "포맷 $n" }) n++
        return "포맷 $n"
    }

    companion object {
        fun getInstance(): LineFormatStore =
            ApplicationManager.getApplication().getService(LineFormatStore::class.java)
    }
}
