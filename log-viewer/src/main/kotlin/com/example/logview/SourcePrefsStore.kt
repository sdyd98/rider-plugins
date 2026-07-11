package com.example.logview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persisted per-SOURCE viewer preferences: which line format and charset a given
 * log source (local path, or `user@host:path` for SSH tails) was last viewed with. Lets each file
 * reopen with its own format/encoding instead of whatever the global active format happens to be.
 *
 * Entries are most-recently-used; the list is capped so years of one-off files don't accumulate.
 */
@State(name = "LogViewerSourcePrefs", storages = [Storage("logviewer-source-prefs.xml")])
class SourcePrefsStore : PersistentStateComponent<SourcePrefsStore.State> {

    class Entry(var key: String = "", var format: String? = null, var charset: String? = null)

    class State {
        var entries: MutableList<Entry> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) {
        state = s
    }

    /** The remembered format NAME for [key] (may no longer exist in the library), or null. */
    fun formatFor(key: String): String? = find(key)?.format

    /** The remembered charset name for [key], or null. */
    fun charsetFor(key: String): String? = find(key)?.charset

    /** Remember (or with null: forget) the format name last applied to [key]. */
    fun rememberFormat(key: String, format: String?) {
        touch(key).format = format
    }

    fun rememberCharset(key: String, charset: String) {
        touch(key).charset = charset
    }

    private fun find(key: String): Entry? = state.entries.firstOrNull { it.key == key }

    /** Get-or-create [key]'s entry, moving it to the MRU tail and evicting the oldest past the cap. */
    private fun touch(key: String): Entry {
        val e = find(key)?.also { state.entries.remove(it) } ?: Entry(key)
        state.entries.add(e)
        while (state.entries.size > MAX_ENTRIES) state.entries.removeAt(0)
        return e
    }

    companion object {
        private const val MAX_ENTRIES = 200

        fun getInstance(): SourcePrefsStore =
            ApplicationManager.getApplication().getService(SourcePrefsStore::class.java)
    }
}
