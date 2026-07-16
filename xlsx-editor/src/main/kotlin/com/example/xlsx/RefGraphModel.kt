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
 * row — then [members] are the rows that share that group key, shown inside the node), OR a fan-in
 * **cluster** when [clusterRecords] is non-empty (many rows of ONE table referencing the centre,
 * folded into a single card so a hub record doesn't explode into hundreds of nodes).
 */
data class RefRecord(
    val table: String,
    val id: String,
    val name: String,
    val isGroup: Boolean = false,
    val members: List<String> = emptyList(),
    val broken: Boolean = false, // a reference resolved to this id/key but no such row exists
    val clusterRecords: List<RefRecord> = emptyList(),
) {
    val isCluster: Boolean get() = clusterRecords.isNotEmpty()
    val label: String get() = if (isGroup) "$table ⟨$id⟩" else "$table #$id"
}

/** A resolved reference from one record to another (the actual edge), via [column]. */
data class RefLink(val from: RefRecord, val column: String, val to: RefRecord, val embedded: Boolean = false, val broken: Boolean = false)

/** Rows of one table referencing the centre fold into a cluster once they outnumber this. */
const val FANIN_CLUSTER_THRESHOLD = 3

/** The synthetic edge "column" of a cluster→centre link (never a real field row, so the edge
 *  anchors at the cluster card's header). */
const val FANIN_CLUSTER_EDGE = "≡"

/**
 * Fan-in clustering for the record explorer's INCOMING side: per referencing table, keep records as
 * individual nodes while there are ≤ [FANIN_CLUSTER_THRESHOLD] of them; past that, fold the table's
 * records into one cluster node (placed at the table's first occurrence, preserving order) with a
 * single aggregated edge to [center]. Outgoing links are untouched — fan-in is where hub records
 * (a common item, a base skill) explode into hundreds of nodes.
 */
internal fun clusterFanIn(center: RefRecord, inbound: List<RefLink>): Pair<List<RefRecord>, List<RefLink>> {
    val records = inbound.map { it.from }.distinct()
    val clustered = records.groupBy { it.table }.filterValues { it.size > FANIN_CLUSTER_THRESHOLD }
    if (clustered.isEmpty()) return records to inbound

    val nodes = ArrayList<RefRecord>(records.size)
    val links = ArrayList<RefLink>(inbound.size)
    val placed = HashSet<String>()
    for (r in records) {
        val group = clustered[r.table]
        if (group == null) {
            nodes.add(r)
        } else if (placed.add(r.table)) {
            val cluster = RefRecord(r.table, FANIN_CLUSTER_EDGE, "", clusterRecords = group)
            nodes.add(cluster)
            links.add(RefLink(cluster, FANIN_CLUSTER_EDGE, center))
        }
    }
    inbound.forEach { if (it.from.table !in clustered.keys) links.add(it) }
    return nodes to links
}

/** One dangling reference: [from].[column] points at [toTable] [missingId], which does not exist. */
data class BrokenRef(val from: RefRecord, val column: String, val missingId: String, val toTable: String)

/** Workbook-wide integrity result: dangling refs + records nothing references (dead content). */
// No orphan (unreferenced-record) detection — firm user decision: unreferenced rows are normal in
// game data (content authored ahead of use), so flagging them was noise, not signal.
data class ValidationReport(val broken: List<BrokenRef>)

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

    /** How many REFERENCE COLUMNS [table]'s rows can have — from the schema alone, NO row reads.
     *  Lets the explorer show a neighbour's expandability/count without streaming its table
     *  (out() loads the whole table from disk; neighbour rows are lazy — loaded on first expand). */
    fun refColumnCount(table: String): Int = 0

    /** A good record to centre on first (cheap — must NOT force [records] to materialise). */
    fun defaultCenter(): RefRecord

    /** Resolve one record by table + display id (or group key) — O(1), without materialising [records]. */
    fun find(table: String, id: String): RefRecord?
}

