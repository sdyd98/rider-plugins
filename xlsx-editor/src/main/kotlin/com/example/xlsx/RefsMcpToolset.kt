package com.example.xlsx

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MCP tools contributed to the IDE's integrated MCP server (Settings | Tools | MCP Server) so an AI client
 * (Claude Code, etc.) can author the relationship schema (refs.json). They ship inside this plugin.
 *
 * DIVISION OF LABOR — the tools are deliberately JUDGMENT-FREE: they enumerate sheets ([SheetScanner]),
 * show raw cells, extract one column's values, compute overlap numbers, and read/write/validate
 * refs.json. EVERY interpretive decision is the AI's: which row is the header, where data starts, which
 * column(s) form the id, the display column, and — above all — the references themselves, derived from
 * reading the game SOURCE (the only way to settle e.g. polymorphic param/paramType columns) and
 * cross-checked against the full data with check_ref.
 *
 * Typical flow: build_refs (skeleton: file+sheet per table) → per table: sample_rows (raw cells) → decide
 * headerRow/dataStartRow/id/display → read the game code for its refs (conditional "when" refs for
 * polymorphic columns) → check_ref to verify each hypothesis → write_table_refs (bulk-capable) →
 * validate_refs (scopable). On a large schema the work spans sessions: list_unfilled_tables is the
 * progress query (which skeletons still need judgments), and unfilled skeletons stay out of the
 * graph/index/validation until their id is recorded. All paths are absolute folders that hold the .xlsx
 * data (and refs.json). Every tool returns JSON text.
 */
class RefsMcpToolset : McpToolset {

    // ---- look at the data (no interpretation) ----

    @McpTool
    @McpDescription(
        "List every sheet in the .xlsx/.xls workbooks under <dir> (recursing): file + sheet name ONLY. " +
            "Nothing is interpreted — use sample_rows to look inside a sheet and decide its layout, id, and " +
            "refs yourself.",
    )
    suspend fun list_tables(@McpDescription("Absolute path to the folder holding the workbooks.") dir: String): String = io {
        val arr = JsonArray()
        SheetScanner.listSheets(File(dir)).forEach { (file, sheet) ->
            arr.add(JsonObject().apply { addProperty("file", file); addProperty("sheet", sheet) })
        }
        gson.toJson(arr)
    }

    @McpTool
    @McpDescription(
        "Raw cells of one sheet: the first <limit> non-empty rows at/after <startRow>, VERBATIM — 1-based " +
            "row numbers, column letters, no header/data assumption. Look at these rows and DECIDE the layout " +
            "yourself: which row holds the field codes (headerRow), where data begins (dataStartRow — rows in " +
            "between are usually type/description meta rows), and which column(s) form the id. Page deeper " +
            "with startRow.",
    )
    suspend fun sample_rows(
        @McpDescription("Absolute path to the data folder.") dir: String,
        @McpDescription("Workbook path relative to <dir> (as returned by list_tables).") file: String,
        @McpDescription("Sheet name.") sheet: String,
        @McpDescription("First row to return (1-based).") startRow: Int = 1,
        @McpDescription("Max rows.") limit: Int = 20,
    ): String = io {
        val rows = SheetScanner.rawRows(File(dir), file, sheet, startRow, limit)
            ?: return@io err("no such sheet: $file / $sheet")
        gson.toJson(JsonObject().apply {
            addProperty("file", file); addProperty("sheet", sheet)
            add("rows", JsonArray().apply {
                rows.forEach { (rn, cells) ->
                    add(JsonObject().apply {
                        addProperty("row", rn)
                        add("cells", JsonObject().apply { cells.forEach { (c, v) -> addProperty(c, v) } })
                    })
                }
            })
        })
    }

    @McpTool
    @McpDescription(
        "Distinct values + counts of ONE column, addressed by COLUMN LETTER, from <startRow> down (you " +
            "decide startRow from sample_rows). The tool only extracts; YOU judge the numbers: " +
            "nonEmptyCells == distinctCount suggests an id/key column; values that look like another table's " +
            "ids suggest a reference (verify with check_ref).",
    )
    suspend fun column_values(
        @McpDescription("Absolute path to the data folder.") dir: String,
        @McpDescription("Workbook path relative to <dir>.") file: String,
        @McpDescription("Sheet name.") sheet: String,
        @McpDescription("Column letter, e.g. \"A\" or \"AB\".") column: String,
        @McpDescription("First data row (1-based) — your judgment of where data begins.") startRow: Int,
        @McpDescription("Max distinct values to return.") limit: Int = 50,
    ): String = io {
        val cv = SheetScanner.columnValues(File(dir), file, sheet, column, startRow)
            ?: return@io err("no such sheet or invalid column letter: $file / $sheet ! $column")
        gson.toJson(JsonObject().apply {
            addProperty("file", file); addProperty("sheet", sheet)
            addProperty("column", column); addProperty("startRow", startRow)
            addProperty("nonEmptyCells", cv.nonEmptyCells)
            addProperty("distinctCount", cv.distinct.size)
            if (cv.truncated) addProperty("distinctTruncated", true)
            add("values", arrayOf(cv.distinct.take(limit)))
        })
    }

    @McpTool
    @McpDescription(
        "Verify YOUR hypothesized reference with pure set arithmetic — no thresholds, no proposals: how " +
            "many distinct tokens of the from-column exist among the to-column's values? Returns " +
            "fromTokens/matched/coverage and up to 10 missing tokens. CAPS: the from-side covers up to " +
            "5000 DISTINCT cell values (fromTruncated=true past that — the numbers are then a partial " +
            "check, not a full one) and the to-side up to 500k ids (toTruncated=true). YOU decide what " +
            "the numbers mean (low coverage may be a wrong target, a polymorphic column needing " +
            "per-\"when\" checks, or genuinely broken data). All coordinates are yours: column LETTERS " +
            "and 1-based data start rows; <split> for delimited multi-value cells; <ignore> for the " +
            "game's no-reference placeholders (comma-separated, e.g. \"0,-1\").",
    )
    suspend fun check_ref(
        @McpDescription("Absolute path to the data folder.") dir: String,
        @McpDescription("Referencing workbook path relative to <dir>.") fromFile: String,
        @McpDescription("Referencing sheet name.") fromSheet: String,
        @McpDescription("Referencing column letter.") fromColumn: String,
        @McpDescription("Referencing sheet's first data row (1-based).") fromStartRow: Int,
        @McpDescription("Target workbook path relative to <dir>.") toFile: String,
        @McpDescription("Target sheet name.") toSheet: String,
        @McpDescription("Target id column letter.") toColumn: String,
        @McpDescription("Target sheet's first data row (1-based).") toStartRow: Int,
        @McpDescription("Delimiter for multi-value cells (empty = single-value).") split: String = "",
        @McpDescription("Comma-separated values meaning \"no reference\" to drop, e.g. \"0,-1\".") ignore: String = "",
    ): String = io {
        val ignoreSet = ignore.split(',').map(String::trim).filter { it.isNotEmpty() }.toSet()
        val o = SheetScanner.overlap(
            File(dir),
            fromFile, fromSheet, fromColumn, fromStartRow, split.ifEmpty { null },
            toFile, toSheet, toColumn, toStartRow, ignoreSet,
        ) ?: return@io err("sheet or column not found — check the file/sheet names and column letters")
        gson.toJson(JsonObject().apply {
            addProperty("fromTokens", o.fromTokens)
            addProperty("matched", o.matched)
            addProperty("coverage", if (o.fromTokens == 0) 0.0 else Math.round(o.matched * 100.0 / o.fromTokens) / 100.0)
            add("missingSample", arrayOf(o.missingSample))
            addProperty("toDistinctIds", o.toIds)
            if (o.toTruncated) addProperty("toTruncated", true)
            if (o.fromTruncated) addProperty("fromTruncated", true) // >5000 distinct from-values: PARTIAL check
        })
    }

    // ---- read / write the schema (mechanical, validated) ----

    @McpTool
    @McpDescription(
        "Read the current refs.json in <dir> (the relationship schema). Returns its text, or an error if " +
            "absent. On a LARGE schema prefer read_table_refs — it returns one table's entry instead of the " +
            "whole file.",
    )
    suspend fun read_refs(@McpDescription("Absolute path to the data folder.") dir: String): String = io {
        File(dir, "refs.json").let { if (it.isFile) it.readText() else err("no refs.json in $dir") }
    }

    @McpTool
    @McpDescription(
        "Read ONE table's entry from refs.json in <dir>. Use instead of read_refs on large schemas so the " +
            "whole file never enters your context. Returns {\"<table>\": <entry>} or an error if the table " +
            "is not in the schema.",
    )
    suspend fun read_table_refs(
        @McpDescription("Absolute path to the data folder (holds refs.json).") dir: String,
        @McpDescription("Table id (the JSON key in \"tables\").") table: String,
    ): String = io {
        val refsFile = File(dir, "refs.json")
        if (!refsFile.isFile) return@io err("no refs.json in $dir")
        val tables = runCatching { JsonParser.parseString(refsFile.readText()) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject?.get("tables")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return@io err("refs.json does not parse as a JSON object with \"tables\"")
        val entry = tables.get(table) ?: return@io err("no such table in refs.json: $table")
        gson.toJson(JsonObject().apply { add(table, entry) })
    }

    @McpTool
    @McpDescription(
        "Write table entries in refs.json in <dir> — insert or replace; every other table is left untouched. " +
            "Single: pass <table> + <json> (one entry object). BULK: pass table=\"\" and <json> as " +
            "{\"<tableId>\": <entry>, ...} to write many at once (use this to fill a whole area's decisions " +
            "in one call). An entry is your COMPLETE judgment of a table: file, sheet, headerRow, " +
            "dataStartRow, id (array of field codes), display, refs. REF SYNTAX — every field is YOUR " +
            "judgment: mark each ref with _source: \"code\" (derived from reading the game source) or " +
            "\"data\" (hypothesized from values and check_ref numbers alone). POLYMORPHIC columns (target " +
            "depends on a type column — decided by reading the game code) are one CONDITIONAL ref per " +
            "target: {\"from\": [\"Param\"], \"to\": \"Item\", \"when\": {\"ParamType\": [\"3\", \"4\"]}} — " +
            "the ref applies only to rows where every \"when\" column has one of its accepted values (cell " +
            "display text; a scalar means one value); write sibling refs on the same column with different " +
            "\"when\" for the other targets. Grouped refs use \"by\" (array of the TARGET's id field " +
            "codes, a strict subset of its composite id); delimited multi-value cells use \"split\"; regex " +
            "extraction uses \"pattern\". Non-0 \"no reference\" placeholders (-1, None, …) go in the " +
            "top-level list via set_null_values. VALIDATED BEFORE WRITING (nothing is written on error): " +
            "field types (headerRow/dataStartRow numbers, display ONE string or null, id/from/by arrays of " +
            "strings); every ref \"to\" must name an existing (or same-call) table key; and every recorded " +
            "FIELD CODE (id, display, from, when-columns — and by, against the target) must appear in the " +
            "declared headerRow of the declared sheet — codes are header CELL TEXT, never column letters. " +
            "Overwriting an already-FILLED entry (one with an id) requires overwrite=true, so a stale " +
            "session can't clobber recorded judgments. Pass delete=true (with <table>) to REMOVE a " +
            "non-data sheet (notes/config) from the schema.",
    )
    suspend fun write_table_refs(
        @McpDescription("Absolute path to the data folder (holds refs.json).") dir: String,
        @McpDescription("Table id (the JSON key in \"tables\"); empty for bulk mode.") table: String,
        @McpDescription("The table entry object, or in bulk mode an object of {tableId: entry}. Ignored when delete=true.") json: String = "",
        @McpDescription("Remove the table's entry instead of writing one.") delete: Boolean = false,
        @McpDescription("Allow replacing entries that are already FILLED (have an id). Default false.") overwrite: Boolean = false,
    ): String = io {
        val refsFile = File(dir, "refs.json")
        val rootObj: JsonObject
        if (refsFile.isFile) {
            // Never clobber a present-but-broken file (it may hold manual refinements) — refuse instead.
            val parsed = runCatching { JsonParser.parseString(refsFile.readText()) }.getOrNull()
            if (parsed == null || !parsed.isJsonObject)
                return@io err("existing refs.json does not parse as a JSON object — refusing to modify; fix or remove it first")
            rootObj = parsed.asJsonObject
        } else {
            if (delete) return@io err("no refs.json in $dir")
            rootObj = JsonObject().apply { addProperty("version", 1) }
        }
        val tablesEl = rootObj.get("tables")
        if (tablesEl != null && !tablesEl.isJsonObject)
            return@io err("existing refs.json has a non-object \"tables\" — refusing to modify; fix or remove it first")
        val tablesObj = tablesEl?.asJsonObject ?: JsonObject().also { rootObj.add("tables", it) }

        val written = ArrayList<String>()
        if (delete) {
            if (table.isEmpty()) return@io err("delete needs a <table>")
            if (!tablesObj.has(table)) return@io err("no such table in refs.json: $table")
            tablesObj.remove(table)
        } else {
            val parsed = runCatching { JsonParser.parseString(json) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@io err("<json> is not a JSON object")
            // Normalize single vs bulk into one key→entry map, then validate EVERYTHING before any
            // mutation (all-or-nothing, same as the original bulk contract).
            val writes = LinkedHashMap<String, JsonObject>()
            if (table.isEmpty()) {
                if (parsed.size() == 0) return@io err("bulk write: <json> has no entries")
                for ((k, v) in parsed.entrySet()) {
                    writes[k] = v.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@io err("bulk write: \"$k\" is not an entry object — did you mean to pass <table> for a single write?")
                }
            } else {
                writes[table] = parsed
            }
            for ((k, e) in writes) RefsWriteValidation.shape(e)?.let { return@io err("\"$k\": $it") }
            // Overwrite guard: a FILLED entry (has an id) holds recorded judgments — never replace it
            // silently (a stale session that thinks the table is unfilled must be told, not obeyed).
            if (!overwrite) {
                val clobbered = writes.keys.filter { k ->
                    tablesObj.get(k)?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let { RefsWriteValidation.isFilled(it) } == true
                }
                if (clobbered.isNotEmpty()) {
                    return@io err(
                        "already FILLED (their judgments would be lost): ${clobbered.joinToString(", ")} — " +
                            "read_table_refs to inspect them, then pass overwrite=true to replace deliberately",
                    )
                }
            }
            // Every ref target must be a table key — existing, or written in this same call.
            val knownKeys = tablesObj.keySet() + writes.keys
            for ((k, e) in writes) {
                RefsWriteValidation.refTargets(e).firstOrNull { it !in knownKeys }?.let { unknown ->
                    return@io err("\"$k\": ref target \"$unknown\" is not a table in refs.json (typo? run list_unfilled_tables/read_refs to see keys)")
                }
            }
            // Every recorded FIELD CODE must exist in the declared header row — this is where a column
            // LETTER (or a typo'd code) written instead of header cell text gets caught, instead of the
            // table silently going invisible (id/from) or every name rendering blank (display).
            val headerCache = HashMap<String, Map<String, String>?>()
            fun headerOf(file: String, sheet: String, row: Int): Map<String, String>? =
                headerCache.getOrPut("$file|$sheet|$row") { SheetScanner.rowValues(File(dir), file, sheet, row) }
            for ((k, e) in writes) {
                val codes = RefsWriteValidation.ownSheetCodes(e)
                if (codes.isEmpty()) continue // bare skeleton — nothing claimed, nothing to verify
                val file = RefsWriteValidation.fileOf(e) ?: continue
                val sheet = RefsWriteValidation.sheetOf(e) ?: continue
                val headerRow = RefsWriteValidation.headerRowOf(e)
                val header = headerOf(file, sheet, headerRow)
                    ?: return@io err("\"$k\": workbook/sheet not found: $file / $sheet")
                if (header.isEmpty()) return@io err("\"$k\": header row $headerRow of \"$sheet\" has no values — check headerRow")
                val values = header.values.toSet()
                codes.firstOrNull { it !in values }?.let { missing ->
                    return@io err(
                        "\"$k\": field code \"$missing\" is not in header row $headerRow of \"$sheet\". " +
                            "Codes are header CELL TEXT (not column letters). Header: ${header.entries.joinToString(", ") { (c, v) -> "$c=$v" }}",
                    )
                }
                // `by` codes live in the TARGET's header. Only checkable when the target is FILLED
                // (its headerRow judgment exists) — checking a skeleton against the default row 1
                // could reject a correct `by`, which is worse than deferring to validate_refs.
                for ((to, byCodes) in RefsWriteValidation.byCodesPerTarget(e)) {
                    val target = writes[to]
                        ?: tablesObj.get(to)?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: continue
                    if (!RefsWriteValidation.isFilled(target)) continue
                    val tFile = RefsWriteValidation.fileOf(target) ?: continue
                    val tSheet = RefsWriteValidation.sheetOf(target) ?: continue
                    val tHeader = headerOf(tFile, tSheet, RefsWriteValidation.headerRowOf(target)) ?: continue
                    if (tHeader.isEmpty()) continue
                    byCodes.firstOrNull { it !in tHeader.values.toSet() }?.let { missing ->
                        return@io err("\"$k\": \"by\" code \"$missing\" is not in the header row of target \"$to\" (${tSheet})")
                    }
                }
            }
            writes.forEach { (k, v) -> tablesObj.add(k, v); written.add(k) }
        }
        refsFile.writeText(gson.toJson(rootObj))
        gson.toJson(JsonObject().apply {
            addProperty("ok", true)
            if (delete) addProperty("deleted", table) else add("written", arrayOf(written))
            addProperty("tablesInSchema", tablesObj.size())
            addProperty("path", refsFile.path.replace('\\', '/'))
        })
    }

    @McpTool
    @McpDescription(
        "Set the top-level \"nullValues\" list in refs.json — the placeholder values that mean \"no " +
            "reference\" (default [\"0\"]); they are never resolved nor reported as broken. Pass the FULL " +
            "comma-separated list (e.g. \"0,-1,None\") — it REPLACES the previous list; every other key in " +
            "refs.json is left untouched. YOU decide the placeholders (from the game source, or from " +
            "check_ref/validate_refs missing samples that are obviously sentinels, not ids).",
    )
    suspend fun set_null_values(
        @McpDescription("Absolute path to the data folder (holds refs.json).") dir: String,
        @McpDescription("Comma-separated placeholder values, e.g. \"0,-1\".") values: String,
    ): String = io {
        val refsFile = File(dir, "refs.json")
        if (!refsFile.isFile) return@io err("no refs.json in $dir")
        val parsed = runCatching { JsonParser.parseString(refsFile.readText()) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject)
            return@io err("existing refs.json does not parse as a JSON object — refusing to modify; fix or remove it first")
        val list = values.split(',').map(String::trim).filter { it.isNotEmpty() }
        if (list.isEmpty()) return@io err("<values> is empty — pass at least one placeholder, e.g. \"0\"")
        val rootObj = parsed.asJsonObject
        rootObj.add("nullValues", arrayOf(list))
        refsFile.writeText(gson.toJson(rootObj))
        gson.toJson(JsonObject().apply { addProperty("ok", true); add("nullValues", arrayOf(list)) })
    }

    @McpTool
    @McpDescription(
        "Validate refs.json against the data: dangling (broken) references — a cell pointing at an id " +
            "with no row. (No orphan/unreferenced-record detection — deliberate: unreferenced rows are " +
            "normal in game data.) Run after writing refs. Scope with <tables> (comma-separated table ids " +
            "— broken refs whose SOURCE or TARGET table is listed; empty = whole schema) and page the " +
            "examples with <limit>/<offset> — on a large schema validate the area you just filled, " +
            "not everything. Unfilled skeleton entries (no \"id\" yet) and refs pointing at them are " +
            "EXCLUDED from validation (counts reported back) — track them with list_unfilled_tables. " +
            "Reading the result is YOUR job: broken refs concentrated in one table usually mean that " +
            "entry's headerRow/dataStartRow/id is wrong (re-inspect with sample_rows), spread-out breaks " +
            "suggest a wrong target or a missing \"when\" condition, and placeholder ids showing up as " +
            "broken mean nullValues is incomplete (fix with set_null_values).",
    )
    suspend fun validate_refs(
        @McpDescription("Absolute path to the data folder.") dir: String,
        @McpDescription("Comma-separated table ids to scope the report to (empty = whole schema).") tables: String = "",
        @McpDescription("Max broken-ref examples returned.") limit: Int = 10,
        @McpDescription("Examples to skip first (paging through a long list).") offset: Int = 0,
    ): String = io {
        val schema = loadRefSchema(File(dir)) ?: return@io err("no valid refs.json in $dir")
        if (schema.tables.isEmpty())
            return@io err("refs.json has no filled tables yet (${schema.unfilledTables.size} skeletons) — nothing to validate; see list_unfilled_tables")
        val scope = tables.split(',').map(String::trim).filter { it.isNotEmpty() }.toSet()
        val unknown = scope.filter { schema.table(it) == null }
        if (unknown.isNotEmpty())
            return@io err("tables not filled in refs.json (or absent): ${unknown.joinToString(", ")}")
        val report = IndexRecordGraph(schema, GameDataLoader.loadOrBuildIndex(schema)).validate()
        val broken = if (scope.isEmpty()) report.broken else report.broken.filter { it.from.table in scope || it.toTable in scope }
        gson.toJson(JsonObject().apply {
            if (scope.isNotEmpty()) addProperty("scope", scope.joinToString(","))
            addProperty("broken", broken.size)
            add("brokenSample", JsonArray().apply { broken.drop(offset).take(limit).forEach { add("${it.from.table}#${it.from.id}.${it.column} -> ${it.toTable}#${it.missingId} (missing)") } })
            if (schema.unfilledTables.isNotEmpty()) {
                addProperty("unfilledTablesExcluded", schema.unfilledTables.size)
                addProperty("refsToUnfilledTargetsExcluded", schema.refsToUnfilled)
            }
        })
    }

    @McpTool
    @McpDescription(
        "Progress query for a large indexing job — mechanically classify every refs.json entry by SHAPE, " +
            "no interpretation: \"unfilled\" entries have NO \"id\" yet (build_refs skeletons whose " +
            "layout/id judgment isn't recorded; the viewer/validation exclude them until filled), " +
            "\"undecidedRefs\" entries have an id but NO \"refs\" key (write \"refs\": [] explicitly once " +
            "you decide a table has no outgoing refs, so done and not-yet-looked-at stay distinguishable). " +
            "Returns counts plus up to <limit> table keys per list (<offset> pages). Use this to pick the " +
            "next work batch across sessions instead of reading the whole file with read_refs.",
    )
    suspend fun list_unfilled_tables(
        @McpDescription("Absolute path to the data folder (holds refs.json).") dir: String,
        @McpDescription("Max table keys per list.") limit: Int = 100,
        @McpDescription("Table keys to skip first in each list (paging).") offset: Int = 0,
    ): String = io {
        val refsFile = File(dir, "refs.json")
        if (!refsFile.isFile) return@io err("no refs.json in $dir")
        val tablesObj = runCatching { JsonParser.parseString(refsFile.readText()) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject?.get("tables")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return@io err("refs.json does not parse as a JSON object with \"tables\"")
        val unfilled = ArrayList<String>()
        val undecided = ArrayList<String>()
        for ((key, el) in tablesObj.entrySet()) {
            val t = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val hasId = (t.get("id")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) > 0
            when {
                !hasId -> unfilled.add(key)
                t.get("refs") == null -> undecided.add(key)
            }
        }
        gson.toJson(JsonObject().apply {
            addProperty("tablesTotal", tablesObj.size())
            addProperty("filled", tablesObj.size() - unfilled.size)
            addProperty("unfilled", unfilled.size)
            addProperty("undecidedRefs", undecided.size)
            add("unfilledTables", arrayOf(unfilled.drop(offset).take(limit)))
            add("undecidedRefsTables", arrayOf(undecided.drop(offset).take(limit)))
        })
    }

    // ---- the entry point ----

    @McpTool
    @McpDescription(
        "Enumerate every sheet under <dir> into refs.json as SKELETON entries — {file, sheet} only, " +
            "ADDITIVE (existing entries are never touched; re-runs only ADD new sheets). This is the entry " +
            "point when the user asks to index / 색인 the tables. The tool decides NOTHING else: for every " +
            "skeleton entry YOU must (1) sample_rows to see the raw top rows and decide headerRow / " +
            "dataStartRow / id / display yourself; (2) read the game source that loads the table and design " +
            "its refs (\"when\" conditional refs for polymorphic columns, _source marks); (3) check_ref to " +
            "verify each hypothesis against the full data; (4) record via write_table_refs (bulk mode fills " +
            "a whole area in one call); (5) validate_refs at the end — and delete non-data sheets " +
            "(notes/config) via write_table_refs(delete=true). " +
            "INTERACTION POLICY (follow before running): if you do NOT already know the data ROOT folder, " +
            "ASK the user for the top folder path — do not guess it; for an INCREMENTAL build, ASK which " +
            "base/anchor table (or area) to start from — OR pass the whole root to enumerate everything at " +
            "once. Table keys: the sheet name, qualified as \"<file-without-extension>#<sheet>\" when the same sheet name " +
            "appears in several workbooks.",
    )
    suspend fun build_refs(
        @McpDescription("Absolute path to the root data folder; its refs.json is written/extended here.") dir: String,
    ): String = io {
        val root = File(dir)
        val sheets = SheetScanner.listSheets(root)
        if (sheets.isEmpty()) return@io err("no .xlsx/.xls sheets found under $dir")
        val refsFile = File(root, "refs.json")
        val rootObj: JsonObject
        if (refsFile.isFile) {
            // Never clobber a present-but-broken file (it may hold manual refinements) — refuse instead.
            val parsed = runCatching { JsonParser.parseString(refsFile.readText()) }.getOrNull()
            if (parsed == null || !parsed.isJsonObject)
                return@io err("existing refs.json does not parse as a JSON object — refusing to overwrite; fix or remove it first")
            rootObj = parsed.asJsonObject
        } else {
            rootObj = JsonObject().apply {
                addProperty("version", 1)
                addProperty("_note", "Authored by the AI via the refs MCP tools — the tools enumerate/extract/validate only and decide nothing; every headerRow/dataStartRow/id/display/ref is an AI judgment (_source marks its basis).")
            }
        }
        val tablesEl = rootObj.get("tables")
        if (tablesEl != null && !tablesEl.isJsonObject)
            return@io err("existing refs.json has a non-object \"tables\" — refusing to overwrite; fix or remove it first")
        val tablesObj = tablesEl?.asJsonObject ?: JsonObject().also { rootObj.add("tables", it) }

        // Deterministic NAMING only (not interpretation): a unique sheet name is the key; a sheet name that
        // appears in several workbooks is qualified with the workbook's relative path.
        val collides = sheets.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
        val addedKeys = ArrayList<String>()
        sheets.forEach { (file, sheet) ->
            val key = if (sheet in collides) file.substringBeforeLast('.') + "#" + sheet else sheet
            if (!tablesObj.has(key)) {
                tablesObj.add(key, JsonObject().apply { addProperty("file", file); addProperty("sheet", sheet) })
                addedKeys.add(key)
            }
        }
        refsFile.writeText(gson.toJson(rootObj))

        gson.toJson(JsonObject().apply {
            addProperty("refs_json", refsFile.path.replace('\\', '/'))
            addProperty("tablesInSchema", tablesObj.size())
            addProperty("tablesAddedThisRun", addedKeys.size)
            addProperty("_note", "SKELETON entries only ({file, sheet}) — the tool made no other decision. Re-runs only ADD new sheets (existing entries are never touched).")
            addProperty("_next", "Every added table needs YOUR decisions: (1) sample_rows → decide headerRow/dataStartRow/id/display; (2) read the game source → design refs (\"when\" for polymorphic columns, _source: \"code\"); (3) check_ref each hypothesis; (4) write_table_refs (bulk: table=\"\" + {tableId: entry, ...}); (5) validate_refs (scope it with <tables> to the area you filled). Delete non-data sheets via write_table_refs(delete=true). Across sessions, pick the next batch with list_unfilled_tables — skeletons stay out of the graph/validation until filled.")
        })
    }

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun err(message: String): String = """{"error": ${JsonPrimitive(message)}}"""
    private fun arrayOf(items: Iterable<String>): JsonArray = JsonArray().apply { items.forEach { add(it) } }
}
