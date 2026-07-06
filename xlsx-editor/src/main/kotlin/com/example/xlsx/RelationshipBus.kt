package com.example.xlsx

import androidx.compose.runtime.mutableStateOf

/** A request to centre the data explorer on a record. [seq] makes each request distinct so re-selecting
 *  the same row re-triggers the observer. */
data class RecordRef(val table: String, val id: String, val seq: Int = 0)

/** A request to centre the table-level ER map on a table ([seq] as in [RecordRef]). */
data class TableRef(val table: String, val seq: Int = 0)

/**
 * One-way bridge from the grid to the relationship tool window: the grid publishes what to show —
 * Ctrl+R a row's record ([request] → data explorer), Ctrl+F the sheet's table ([tableRequest] → ER
 * map); [RelationshipTabs] observes the Compose states and re-centres the matching view.
 */
object RelationshipBus {
    val request = mutableStateOf<RecordRef?>(null)
    val tableRequest = mutableStateOf<TableRef?>(null)
    private var seq = 0
    fun show(table: String, id: String) {
        request.value = RecordRef(table, id, seq++)
    }
    fun showTable(table: String) {
        tableRequest.value = TableRef(table, seq++)
    }
}
