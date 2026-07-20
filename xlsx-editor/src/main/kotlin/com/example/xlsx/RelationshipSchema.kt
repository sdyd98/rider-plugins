package com.example.xlsx

import androidx.compose.ui.geometry.Offset
import com.google.gson.JsonParser
import java.io.File

/**
 * The reference SCHEMA (what the AI-from-code / MCP step produces, loaded from refs.json). Rows/cols are
 * keyed by FIELD CODE (the [headerRow] values), so it survives display rows / reordering. A ref whose
 * [byCols] is a strict subset of the target's id is a GROUP reference.
 */
data class RefSchema(
    val baseDir: File,
    val tables: List<SchemaTable>,
    /** Cell values meaning "no reference" (top-level `nullValues` in refs.json; default `["0"]`) —
     *  games use 0 / -1 / None etc. as the empty-FK placeholder. Never reported as broken refs. */
    val nullValues: Set<String> = setOf("0"),
    /** Table keys present in refs.json that are still SKELETONS (no `id` yet — build_refs output whose
     *  layout/id judgment hasn't been recorded). Excluded from [tables] — not parsed, not drawn, not
     *  validated — so a half-filled schema never poisons the graph with default-layout guesses. */
    val unfilledTables: List<String> = emptyList(),
    /** Refs on filled tables whose target is still a skeleton — dropped at load (they cannot resolve
     *  until the target's id is recorded; kept, they would all read as broken noise in validate). */
    val refsToUnfilled: Int = 0,
) {
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
    /** Table-level row filter (`"rowFilter"` in refs.json — same clause syntax as a ref's `when`):
     *  rows failing ANY clause are dropped at load, so they are neither indexed as records nor
     *  evaluated for refs (e.g. `{"Unused": ["", "0"]}` keeps only active rows). Null = keep all. */
    val rowFilter: List<WhenClause>? = null,
)

/**
 * One condition clause of a `when` / `rowFilter`: the row's [col] value must be in [values]
 * ([negated] = false) or must NOT be ([negated] = true — the `{"notIn": [...]}` form). A cell that is
 * empty or entirely absent from the row map reads as "" — so `""` in [values] matches blank and
 * missing cells alike (rows sparse-store only non-empty cells).
 */
data class WhenClause(val col: String, val values: Set<String>, val negated: Boolean = false) {
    fun matches(row: Map<String, String>): Boolean = ((row[col] ?: "").trim() in values) != negated
}

data class SchemaRef(
    val fromCols: List<String>,
    /** Target table(s). Usually one; SEVERAL model a logical table split across workbooks (e.g.
     *  Npc.xls#Basic + Npc2.xls#Basic loaded as one) — a token resolves against the UNION, landing on
     *  the first listed table that contains it (`"to": ["Npc", "Npc2"]` in refs.json). */
    val toTables: List<String>,
    val byCols: List<String>,
    val split: String? = null,
    val pattern: String? = null,
    /** Conditional (polymorphic) ref: the ref applies to a row only when EVERY clause matches
     *  (values OR-ed within a clause, clauses AND-ed). E.g. `"when": {"ParamType": ["3", "4"]}` makes
     *  `Param` reference this ref's target only on rows whose ParamType is 3 or 4; a sibling ref on
     *  the same column with a different `when` can target another table. `{"notIn": [...]}` negates a
     *  clause. Null = unconditional. */
    val whenCond: List<WhenClause>? = null,
) {
    /** The primary target — labels, group refs, and the common single-target case. */
    val toTable: String get() = toTables.first()

    /** Does this ref apply to the row with the given (field code → rendered value) map? */
    fun appliesTo(values: Map<String, String>): Boolean =
        whenCond == null || whenCond.all { it.matches(values) }
}

/**
 * Loads the reference schema from `<baseDir>/refs.json` — the format the AI-from-code / MCP step
 * produces. Returns null if the file is missing or invalid, so callers can fall back. A ref with no `by`
 * defaults to the target's id columns; a `by` that is a strict subset of the target id is a GROUP reference.
 *
 * Entries WITHOUT an `id` are unfilled build_refs skeletons: they are collected into
 * [RefSchema.unfilledTables] and excluded from [RefSchema.tables] (along with any ref pointing at them,
 * counted in [RefSchema.refsToUnfilled]) — otherwise every skeleton would be parsed with the default 1/4
 * layout and flood the index/validation with garbage while the schema is still being authored.
 */
fun loadRefSchema(baseDir: File): RefSchema? = loadRefSchemaResult(baseDir).getOrNull()

/**
 * [loadRefSchema] with the failure PRESERVED: a refs.json that stops parsing (usually a hand edit —
 * tool writes are shape-validated) must report WHY, not collapse into a generic "no valid refs.json".
 * The MCP tools surface `exceptionOrNull()?.message`; the viewer keeps using the nullable wrapper.
 */
fun loadRefSchemaResult(baseDir: File): Result<RefSchema> {
    val file = File(baseDir, "refs.json")
    if (!file.isFile) return Result.failure(java.io.FileNotFoundException("no refs.json in ${baseDir.path}"))
    return runCatching {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val tablesObj = root.getAsJsonObject("tables")
        val idColsOf = tablesObj.entrySet().associate { (id, el) ->
            id to (el.asJsonObject.getAsJsonArray("id")?.map { it.asString }.orEmpty())
        }
        val unfilled = idColsOf.filterValues { it.isEmpty() }.keys
        var refsToUnfilled = 0
        val tables = tablesObj.entrySet().filter { (id, _) -> id !in unfilled }.map { (tableId, el) ->
            val t = el.asJsonObject
            fun opt(name: String) = t.get(name)?.takeIf { !it.isJsonNull }
            val refs = t.getAsJsonArray("refs")?.mapNotNull { r ->
                val ro = r.asJsonObject
                fun roOpt(name: String) = ro.get(name)?.takeIf { !it.isJsonNull }
                // "to": "Npc" or ["Npc", "Npc2"] (a union of tables loaded as one logical table).
                // Unfilled targets are dropped from the union; a ref with NO filled target is dropped whole.
                val toEl = ro.get("to")
                val toAll = if (toEl.isJsonArray) toEl.asJsonArray.map { it.asString } else listOf(toEl.asString)
                val to = toAll.filter { it !in unfilled }
                if (to.isEmpty()) { refsToUnfilled++; return@mapNotNull null }
                SchemaRef(
                    fromCols = ro.getAsJsonArray("from").map { it.asString },
                    toTables = to,
                    byCols = roOpt("by")?.asJsonArray?.map { it.asString } ?: idColsOf[to.first()] ?: listOf("Id"),
                    split = roOpt("split")?.asString,
                    pattern = roOpt("pattern")?.asString,
                    whenCond = roOpt("when")?.asJsonObject?.let { parseWhenClauses(it) },
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
                rowFilter = opt("rowFilter")?.asJsonObject?.let { parseWhenClauses(it) },
            )
        }
        val nullValues = root.getAsJsonArray("nullValues")?.map { it.asString }?.toSet() ?: setOf("0")
        RefSchema(baseDir, tables, nullValues, unfilled.sorted(), refsToUnfilled)
    }
}

/**
 * Parse a `when` / `rowFilter` object into clauses. Accepted value forms per column:
 * `"v"` / `["v1", "v2"]` (value must be IN the set), or `{"in": ...}` / `{"notIn": ...}` with the
 * same scalar/array payload (`notIn` negates). Empty string `""` matches blank AND absent cells.
 * Internal (not private): check_ref reuses the exact same syntax with column-LETTER keys.
 */
internal fun parseWhenClauses(obj: com.google.gson.JsonObject): List<WhenClause> =
    obj.entrySet().map { (col, v) ->
        fun valuesOf(el: com.google.gson.JsonElement): Set<String> =
            if (el.isJsonArray) el.asJsonArray.map { it.asString }.toSet() else setOf(el.asString)
        if (v.isJsonObject) {
            val o = v.asJsonObject
            o.get("notIn")?.let { WhenClause(col, valuesOf(it), negated = true) }
                ?: WhenClause(col, valuesOf(o.get("in")))
        } else {
            WhenClause(col, valuesOf(v))
        }
    }

// ---- Builders: schema (+ data) -> the view models used by RefGraphView / DataGraphView ----

/** Table-level ER graph from the schema alone (nodes = tables w/ id + ref columns, edges = refs). */
fun buildRefGraph(schema: RefSchema): RefGraph {
    val tables = schema.tables.map { st ->
        // Keyed by column name — except CONDITIONAL refs, which get one row PER TARGET (a polymorphic
        // column references several tables, one per `when` branch), keyed "col→target".
        val cols = LinkedHashMap<String, RefColumn>()
        st.idCols.forEach { cols[it] = RefColumn(it, isId = true) }
        st.refs.forEach { r ->
            r.fromCols.forEach { fc ->
                val embedded = r.split != null || r.pattern != null
                // Union refs draw one edge per target table (same column, keyed "col→target").
                r.toTables.forEach { to ->
                    val key = if (r.whenCond == null && r.toTables.size == 1) fc else "$fc→$to"
                    cols[key] = RefColumn(
                        fc,
                        isId = cols[fc]?.isId == true,
                        refTo = to,
                        embedded = embedded,
                        conditional = r.whenCond != null,
                    )
                }
            }
        }
        RefTable(st.tableId, st.tableId, cols.values.toList(), Offset.Zero)
    }
    return RefGraph(tables)
}

internal fun parseTokens(raw: String, r: SchemaRef): List<String> = parseTokensCounted(raw, r).first

/** [parseTokens] plus how many non-empty parts the `pattern` regex failed to extract from —
 *  silently-dropped parts are exactly what validate_refs' stats must surface. */
internal fun parseTokensCounted(raw: String, r: SchemaRef): Pair<List<String>, Int> {
    var parts = if (r.split != null) raw.split(r.split).map { it.trim() } else listOf(raw.trim())
    var misses = 0
    if (r.pattern != null) {
        val rx = Regex(r.pattern)
        parts = parts.flatMap { p ->
            val groups = rx.find(p)?.groupValues?.drop(1)
            if (groups == null && p.isNotEmpty()) misses++
            groups ?: emptyList()
        }
    }
    return parts.filter { it.isNotEmpty() } to misses
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
    // out() may run from a background thread (the view loads neighbourhoods off the EDT); guard the LRU.
    private fun table(tableId: String): TableData? =
        synchronized(loaded) { loaded.getOrPut(tableId) { GameDataLoader.loadTable(schema, tableId) } }

    private fun isGroupRef(ref: SchemaRef): Boolean {
        if (ref.toTables.size > 1) return false // union refs resolve as single records, never groups
        val target = schema.table(ref.toTable) ?: return false
        return ref.byCols.size < target.idCols.size
    }
    /** Resolve [token] against a (usually 1-element) target union: the first table containing it wins;
     *  in none → a broken record labeled with the primary target. For a UNION the group index is
     *  consulted too, matching how buildIndex resolves union backrefs (a composite-id union member's
     *  group key must not read as broken here while the reverse index resolved it). */
    private fun single(toTables: List<String>, token: String): RefRecord {
        for (t in toTables) {
            index.idName["$t|$token"]?.let { return RefRecord(t, token, it) }
            if (toTables.size > 1) {
                index.groupMembers["$t|$token"]?.let { return RefRecord(t, token, "", isGroup = true, members = it) }
            }
        }
        return RefRecord(toTables.first(), token, "", broken = true)
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
            if (!ref.appliesTo(row.values)) return@forEach // conditional (when) ref inactive on this row
            val embedded = ref.split != null || ref.pattern != null
            val grp = isGroupRef(ref)
            parseTokens(row.values[ref.fromCols.first()].orEmpty(), ref).forEach { tok ->
                if (tok.isNotEmpty() && tok !in schema.nullValues) {
                    val to = if (grp) groupRec(ref.toTable, tok) else single(ref.toTables, tok)
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

    /** Schema-only (no row reads): distinct source columns of the table's refs — what the explorer
     *  shows as a neighbour's row count/chevron before its rows are lazily loaded. */
    override fun refColumnCount(table: String): Int =
        schema.table(table)?.refs?.mapNotNull { it.fromCols.firstOrNull() }?.distinct()?.size ?: 0

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

    /** Resolve a record by table + display id (full composite id) or by group key — O(1), no row reads. */
    override fun find(table: String, id: String): RefRecord? {
        index.idName["$table|$id"]?.let { return RefRecord(table, id, it) }
        index.groupMembers["$table|$id"]?.let { return RefRecord(table, id, "", isGroup = true, members = it) }
        return null
    }

    /** Dangling refs — computed entirely from the reverse index (no row reads). Orphan (unreferenced-
     *  record) detection was removed deliberately; see [ValidationReport]. */
    override fun validate(): ValidationReport {
        val broken = ArrayList<BrokenRef>()
        index.reverse.forEach { (key, backrefs) -> // a token referenced but in neither id-name nor group index = dangling
            if (key !in index.idName && key !in index.groupMembers) {
                val (toTable, token) = splitKey(key)
                backrefs.forEach { br -> broken.add(BrokenRef(RefRecord(br.srcTable, br.srcId, br.srcName), br.column, token, toTable)) }
            }
        }
        return ValidationReport(broken)
    }
}
