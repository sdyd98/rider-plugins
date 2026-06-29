package com.example.xlsx

import androidx.compose.runtime.mutableStateOf

/** A request to centre the data explorer on a record. [seq] makes each request distinct so re-selecting
 *  the same row re-triggers the observer. */
data class RecordRef(val table: String, val id: String, val seq: Int = 0)

/**
 * One-way bridge from the grid to the relationship tool window: the grid (Ctrl+R on a row) publishes the
 * record to show; [RelationshipTabs] observes [request] (a Compose state) and re-centres the explorer.
 */
object RelationshipBus {
    val request = mutableStateOf<RecordRef?>(null)
    private var seq = 0
    fun show(table: String, id: String) {
        request.value = RecordRef(table, id, seq++)
    }
}
