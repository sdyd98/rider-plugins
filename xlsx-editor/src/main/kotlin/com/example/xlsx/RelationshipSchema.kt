package com.example.xlsx

import androidx.compose.ui.geometry.Offset
import com.google.gson.JsonParser
import java.io.File

/**
 * The reference SCHEMA (what the AI-from-code / MCP step produces, loaded from refs.json). Rows/cols are
 * keyed by FIELD CODE (the [headerRow] values), so it survives display rows / reordering. A ref whose
 * [byCols] is a strict subset of the target's id is a GROUP reference.
 */
data class RefSchema(val baseDir: File, val tables: List<SchemaTable>) {
    fun table(id: String): SchemaTable? = tables.firstOrNull { it.tableId == id }
}

data class SchemaTable(
    val tableId: String,
    val file: String,
    val sheet: String,
    val idCols: List<String>,
    val refs: List<SchemaRef>,
    val headerRow: Int = 1,      // 1-based row holding field codes
    val dataStartRow: Int = 4,   // 1-based first data row
    val displayCol: String? = "Name",
)

data class SchemaRef(
    val fromCols: List<String>,
    val toTable: String,
    val byCols: List<String>,
    val split: String? = null,
    val pattern: String? = null,
)

/**
 * Loads the reference schema from `<baseDir>/refs.json` — the format the AI-from-code / MCP step
 * produces. Returns null if the file is missing or invalid, so callers can fall back. A ref with no `by`
 * defaults to the target's id columns; a `by` that is a strict subset of the target id is a GROUP reference.
 */
fun loadRefSchema(baseDir: File): RefSchema? {
    val file = File(baseDir, "refs.json")
    if (!file.isFile) return null
    return runCatching {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val tablesObj = root.getAsJsonObject("tables")
        val idColsOf = tablesObj.entrySet().associate { (id, el) ->
            id to (el.asJsonObject.getAsJsonArray("id")?.map { it.asString }.orEmpty())
        }
        val tables = tablesObj.entrySet().map { (tableId, el) ->
            val t = el.asJsonObject
            fun opt(name: String) = t.get(name)?.takeIf { !it.isJsonNull }
            val refs = t.getAsJsonArray("refs")?.map { r ->
                val ro = r.asJsonObject
                fun roOpt(name: String) = ro.get(name)?.takeIf { !it.isJsonNull }
                val to = ro.get("to").asString
                SchemaRef(
                    fromCols = ro.getAsJsonArray("from").map { it.asString },
                    toTable = to,
                    byCols = roOpt("by")?.asJsonArray?.map { it.asString } ?: idColsOf[to] ?: listOf("Id"),
                    split = roOpt("split")?.asString,
                    pattern = roOpt("pattern")?.asString,
                )
            }.orEmpty()
            SchemaTable(
                tableId = tableId,
                file = t.get("file").asString,
                sheet = t.get("sheet").asString,
                idCols = idColsOf[tableId].orEmpty(),
                refs = refs,
                headerRow = opt("headerRow")?.asInt ?: 1,
                dataStartRow = opt("dataStartRow")?.asInt ?: 4,
                displayCol = opt("display")?.asString,
            )
        }
        RefSchema(baseDir, tables)
    }.getOrNull()
}

// ---- Builders: schema (+ data) -> the view models used by RefGraphView / DataGraphView ----

/** Table-level ER graph from the schema alone (nodes = tables w/ id + ref columns, edges = refs). */
fun buildRefGraph(schema: RefSchema): RefGraph {
    val tables = schema.tables.map { st ->
        val cols = LinkedHashMap<String, RefColumn>()
        st.idCols.forEach { cols[it] = RefColumn(it, isId = true) }
        st.refs.forEach { r ->
            r.fromCols.forEach { fc ->
                val embedded = r.split != null || r.pattern != null
                cols[fc] = RefColumn(fc, isId = cols[fc]?.isId == true, refTo = r.toTable, embedded = embedded)
            }
        }
        RefTable(st.tableId, st.tableId, cols.values.toList(), Offset.Zero)
    }
    return RefGraph(tables)
}

internal fun parseTokens(raw: String, r: SchemaRef): List<String> {
    var parts = if (r.split != null) raw.split(r.split).map { it.trim() } else listOf(raw.trim())
    if (r.pattern != null) {
        val rx = Regex(r.pattern)
        parts = parts.flatMap { p -> rx.find(p)?.groupValues?.drop(1) ?: emptyList() }
    }
    return parts.filter { it.isNotEmpty() }
}

/**
 * Index-backed record graph. Every query is served from the compact [GameIndex] (built in one streaming
 * pass): [inbound], [usageCount] and [validate] need ZERO row reads (the reverse index caches each
 * source's id/name), and [out] lazily streams only the CENTRE's own table (bounded by a small LRU).
 * Nothing holds the full tables, and the searchable [records] list is materialised only on first use —
 * the default centre comes from index statistics, so opening never touches every record.
 */
class IndexRecordGraph(private val schema: RefSchema, private val index: GameIndex) : RecordSource {

    // LRU of recently-visited tables; only the centre's table is streamed, on demand, for out().
    private val loaded = object : LinkedHashMap<String, TableData?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TableData?>) = size > 6
    }
    private fun table(tableId: String): TableData? = loaded.getOrPut(tableId) { GameDataLoader.loadTable(schema, tableId) }

    private fun isGroupRef(ref: SchemaRef): Boolean {
        val target = schema.table(ref.toTable) ?: return false
        return ref.byCols.size < target.idCols.size
    }
    private fun single(toTable: String, token: String): RefRecord {
        val key = "$toTable|$token"
        return RefRecord(toTable, token, index.idName[key].orEmpty(), broken = key !in index.idName)
    }
    private fun groupRec(toTable: String, token: String): RefRecord {
        val members = index.groupMembers["$toTable|$token"]
        return RefRecord(toTable, token, "", isGroup = true, members = members.orEmpty(), broken = members == null)
    }
    private fun splitKey(key: String): Pair<String, String> {
        val i = key.indexOf('|'); return key.substring(0, i) to key.substring(i + 1)
    }

    /** Outgoing links — lazily streams the centre's own table to read its reference-column values. */
    override fun out(r: RefRecord): List<RefLink> {
        if (r.isGroup) return emptyList() // members are shown inside the group node
        val row = table(r.table)?.byDisplayId?.get(r.id) ?: return emptyList()
        val links = ArrayList<RefLink>()
        schema.table(r.table)?.refs?.forEach { ref ->
            val embedded = ref.split != null || ref.pattern != null
            val grp = isGroupRef(ref)
            parseTokens(row.values[ref.fromCols.first()].orEmpty(), ref).forEach { tok ->
                if (tok.isNotEmpty() && tok != "0") {
                    val to = if (grp) groupRec(ref.toTable, tok) else single(ref.toTable, tok)
                    links.add(RefLink(r, ref.fromCols.first(), to, embedded, broken = to.broken))
                }
            }
        }
        return links
    }

    /** Incoming links — straight from the reverse index (source id/name cached → no row reads). */
    override fun inbound(r: RefRecord): List<RefLink> =
        index.reverse["${r.table}|${r.id}"]?.map { br ->
            RefLink(RefRecord(br.srcTable, br.srcId, br.srcName), br.column, r, br.embedded)
        }.orEmpty()

    override fun usageCount(r: RefRecord): Int = index.reverse["${r.table}|${r.id}"]?.size ?: 0

    /** Search corpus — built from the index on first use (search/Ctrl+R), not eagerly at open. */
    override val records: List<RefRecord> by lazy {
        val recs = ArrayList<RefRecord>(index.idName.size + index.groupMembers.size)
        index.idName.forEach { (key, name) -> val (t, id) = splitKey(key); recs.add(RefRecord(t, id, name)) }
        index.groupMembers.forEach { (key, members) -> val (t, gk) = splitKey(key); recs.add(RefRecord(t, gk, "", isGroup = true, members = members)) }
        recs
    }

    /** A well-connected default centre (highest in-degree among records that also have outgoing refs). */
    override fun defaultCenter(): RefRecord {
        val best = index.outDeg.keys.maxByOrNull { index.reverse[it]?.size ?: 0 }
            ?: index.idName.keys.firstOrNull()
            ?: return RefRecord("", "", "")
        val (t, id) = splitKey(best)
        return RefRecord(t, id, index.idName[best].orEmpty())
    }

    /** Dangling refs + orphans — computed entirely from the indices (no row reads). */
    override fun validate(): ValidationReport {
        val broken = ArrayList<BrokenRef>()
        index.reverse.forEach { (key, backrefs) -> // a token referenced but in neither id-name nor group index = dangling
            if (key !in index.idName && key !in index.groupMembers) {
                val (toTable, token) = splitKey(key)
                backrefs.forEach { br -> broken.add(BrokenRef(RefRecord(br.srcTable, br.srcId, br.srcName), br.column, token, toTable)) }
            }
        }
        val referenced = schema.tables.flatMap { it.refs.map { r -> r.toTable } }.toSet()
        val orphans = ArrayList<RefRecord>()
        index.idName.forEach { (key, name) ->
            val (t, id) = splitKey(key)
            if (t !in referenced) return@forEach
            // Group-keyed tables have composite ids ("groupKey·slot") and are referenced via their GROUP
            // key, not the full row id. A row is in use if EITHER its full id or its group key is referenced;
            // otherwise group members would all be mis-flagged as unreferenced.
            val directlyUsed = (index.reverse[key]?.size ?: 0) > 0
            val groupUsed = id.contains('·') && (index.reverse["$t|${id.substringBefore('·')}"]?.size ?: 0) > 0
            if (!directlyUsed && !groupUsed) orphans.add(RefRecord(t, id, name))
        }
        return ValidationReport(broken, orphans)
    }
}
