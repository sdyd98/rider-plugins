package com.example.logview.rules

import com.example.logview.HighlightRule
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** A persisted (serializable) highlight rule. [fg]/[bg] use [NONE] to mean "leave the default color". */
class StoredRule(
    var pattern: String = "",
    var fg: Int = NONE,
    var bg: Int = NONE,
    var bold: Boolean = false,
    var enabled: Boolean = true,
    var caseInsensitive: Boolean = true,
) {
    fun toRule(): HighlightRule = HighlightRule(
        pattern = pattern,
        foregroundRgb = if (fg == NONE) null else fg,
        backgroundRgb = if (bg == NONE) null else bg,
        bold = bold,
        enabled = enabled,
        caseInsensitive = caseInsensitive,
    )

    fun copy(): StoredRule = StoredRule(pattern, fg, bg, bold, enabled, caseInsensitive)

    companion object {
        const val NONE: Int = Int.MIN_VALUE
    }
}

/**
 * Application-level persisted highlight rules (GrepConsole-style line coloring), shared across
 * projects. Seeded with a few high-signal defaults on first use. The compiled-and-converted runtime
 * list is cached and invalidated whenever the rules change ([replaceAll] / [loadState]).
 */
@State(name = "LogViewerHighlightRules", storages = [Storage("logviewer-highlight.xml")])
class HighlightRulesStore : PersistentStateComponent<HighlightRulesStore.State> {

    class State {
        var rules: MutableList<StoredRule> = defaults()
    }

    private var state = State()
    @Volatile private var cache: List<HighlightRule>? = null

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
        cache = null
    }

    /** Runtime rules for the renderer (cached). */
    fun rules(): List<HighlightRule> = cache ?: state.rules.map { it.toRule() }.also { cache = it }

    /** A deep copy of the stored rules for editing in the dialog. */
    fun editableCopy(): MutableList<StoredRule> = state.rules.map { it.copy() }.toMutableList()

    fun replaceAll(rules: List<StoredRule>) {
        state.rules = rules.map { it.copy() }.toMutableList()
        cache = null
    }

    companion object {
        fun getInstance(): HighlightRulesStore =
            ApplicationManager.getApplication().getService(HighlightRulesStore::class.java)

        private fun defaults(): MutableList<StoredRule> = mutableListOf(
            StoredRule(pattern = "\\b(Exception|Error|FAIL(ED|URE)?|panic|fatal)\\b", fg = 0xE05555, bold = true),
            StoredRule(pattern = "\\b(timeout|timed out|refused|unreachable|disconnect)\\b", fg = 0xD9883B),
            StoredRule(pattern = "\\bnull(ptr|reference| pointer)?\\b", fg = 0xC586C0),
        )
    }
}
