package com.example.xlsx

import androidx.compose.ui.geometry.Offset

/**
 * View-model for the table-level relationship-map (ER) view, built from the workbook's refs.json by
 * [buildRefGraph] (see RelationshipSchema.kt). No refs.json → the tool window shows guidance instead
 * (there is deliberately no sample/mock data — the views only ever render the real schema).
 *
 * @param refTo       target table id this column references (null = plain column).
 * @param embedded    the reference lives inside a delimited/encoded string (shown as a badge).
 * @param conditional the ref applies only to rows matching its `when` condition (polymorphic column —
 *                    the same column appears once per target table).
 */
data class RefColumn(
    val name: String,
    val isId: Boolean = false,
    val refTo: String? = null,
    val embedded: Boolean = false,
    val conditional: Boolean = false,
)

data class RefTable(val id: String, val display: String, val columns: List<RefColumn>, val pos: Offset)

data class RefGraph(val tables: List<RefTable>) {
    fun table(id: String): RefTable? = tables.firstOrNull { it.id == id }
}

// ---- Instance-level (actual data) model: records + resolved links between them ----

/**
 * One actual row, OR a **group** when [isGroup] (the reference resolves to a group key, not a single
 * row — then [members] are the rows that share that group key, shown inside the node).
 */
data class RefRecord(
    val table: String,
    val id: String,
    val name: String,
    val isGroup: Boolean = false,
    val members: List<String> = emptyList(),
    val broken: Boolean = false, // a reference resolved to this id/key but no such row exists
) {
    val label: String get() = if (isGroup) "$table ⟨$id⟩" else "$table #$id"
}

/** A resolved reference from one record to another (the actual edge), via [column]. */
data class RefLink(val from: RefRecord, val column: String, val to: RefRecord, val embedded: Boolean = false, val broken: Boolean = false)

/** One dangling reference: [from].[column] points at [toTable] [missingId], which does not exist. */
data class BrokenRef(val from: RefRecord, val column: String, val missingId: String, val toTable: String)

/** Workbook-wide integrity result: dangling refs + records nothing references (dead content). */
data class ValidationReport(val broken: List<BrokenRef>, val orphans: List<RefRecord>)

/**
 * What the data explorer needs from a record graph: the searchable record list, and a record's
 * outgoing / incoming links. Implemented by the index-backed [IndexRecordGraph].
 */
interface RecordSource {
    val records: List<RefRecord>
    fun out(r: RefRecord): List<RefLink>
    fun inbound(r: RefRecord): List<RefLink>
    fun usageCount(r: RefRecord): Int // how many records reference r (reverse-index size)
    fun validate(): ValidationReport

    /** A good record to centre on first (cheap — must NOT force [records] to materialise). */
    fun defaultCenter(): RefRecord

    /** Resolve one record by table + display id (or group key) — O(1), without materialising [records]. */
    fun find(table: String, id: String): RefRecord?
}

