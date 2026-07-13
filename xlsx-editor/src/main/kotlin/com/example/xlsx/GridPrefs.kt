package com.example.xlsx

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persisted grid preferences: the LAST freeze-header state (Alt+Shift+F row count
 * + Alt+K key row). Game-data workbooks share one layout convention, so whatever the user froze
 * last is auto-applied to every sheet opened afterwards; unfreezing is remembered too (no default).
 */
@State(name = "XlsxGridPrefs", storages = [Storage("xlsx-grid-prefs.xml")])
class GridPrefs : PersistentStateComponent<GridPrefs.State> {

    class State {
        /** Rows frozen from the top (model rows 0 until frozenRows); 0 = nothing frozen. */
        var frozenRows: Int = 0

        /** The key row (names columns in the Alt+\ popup) within the frozen rows; -1 = none. */
        var keyRow: Int = -1
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) {
        state = s
    }

    fun frozenRows(): Int = state.frozenRows
    fun keyRow(): Int = state.keyRow

    /** Remember the freeze state a user just set (freeze, key-row cycle, or unfreeze with 0/-1). */
    fun remember(frozenRows: Int, keyRow: Int) {
        state.frozenRows = frozenRows
        state.keyRow = keyRow
    }

    companion object {
        fun getInstance(): GridPrefs = ApplicationManager.getApplication().getService(GridPrefs::class.java)
    }
}
