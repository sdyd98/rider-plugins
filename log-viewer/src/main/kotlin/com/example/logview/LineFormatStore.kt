package com.example.logview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persisted user line-format rules: an ordered list of template/regex strings tried
 * (in order) before the built-in [LogParser] heuristic when splitting a line into Time | Level |
 * Message. The compiled, valid [LineFormat]s are cached and invalidated whenever the list changes.
 *
 * Empty by default — with no user formats the viewer parses exactly as before (heuristic only).
 */
@State(name = "LogViewerLineFormats", storages = [Storage("logviewer-line-formats.xml")])
class LineFormatStore : PersistentStateComponent<LineFormatStore.State> {

    class State {
        var formats: MutableList<String> = mutableListOf()
    }

    private var state = State()
    @Volatile private var cache: List<LineFormat>? = null

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
        cache = null
    }

    /** The raw format strings, in order (for the settings list). */
    fun sources(): List<String> = state.formats.toList()

    /** Compiled VALID formats in order — what the parser tries per line (cached). */
    fun active(): List<LineFormat> =
        cache ?: state.formats.map { LineFormat.of(it) }.filter { it.valid }.also { cache = it }

    /** Replace the whole ordered list (blank entries dropped). */
    fun replaceAll(formats: List<String>) {
        state.formats = formats.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        cache = null
    }

    companion object {
        fun getInstance(): LineFormatStore =
            ApplicationManager.getApplication().getService(LineFormatStore::class.java)
    }
}
