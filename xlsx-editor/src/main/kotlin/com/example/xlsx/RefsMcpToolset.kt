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
 * validate_refs. All paths are absolute folders that hold the .xlsx data (and refs.json). Every tool
 * returns JSON text.
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
        "Verify YOUR hypothesized reference with pure set arithmetic on the FULL data — no thresholds, no " +
            "proposals: how many distinct tokens of the from-column exist among the to-column's values? " +
            "Returns fromTokens/matched/coverage and up to 10 missing tokens. YOU decide what the numbers " +
            "mean (low coverage may be a wrong target, a polymorphic column needing per-\"when\" checks, or " +
            "genuinely broken data). All coordinates are yours: column LETTERS and 1-based data start rows; " +
            "<split> for delimited multi-value cells; <ignore> for the game's no-reference placeholders " +
            "(comma-separated, e.g. \"0,-1\").",
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
            "dataStartRow, id (array of field codes), display, refs — see write_refs for the ref syntax " +
            "(conditional \"when\", _source marks). Entries are shape-validated before writing (file+sheet " +
            "present; refs an array of {from[], to, when?}); nothing is written on error. Pass delete=true " +
            "(with <table>) to REMOVE a non-data sheet (notes/config) from the schema.",
    )
    suspend fun write_table_refs(
        @McpDescription("Absolute path to the data folder (holds refs.json).") dir: String,
        @McpDescription("Table id (the JSON key in \"tables\"); empty for bulk mode.") table: String,
        @McpDescription("The table entry object, or in bulk mode an object of {tableId: entry}. Ignored when delete=true.") json: String = "",
        @McpDescription("Remove the table's entry instead of writing one.") delete: Boolean = false,
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
            if (table.isEmpty()) {
                // bulk: {tableId: entry, ...} — validate everything first, then write all-or-nothing.
                if (parsed.size() == 0) return@io err("bulk write: <json> has no entries")
                for ((k, v) in parsed.entrySet()) {
                    val e = v.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@io err("bulk write: \"$k\" is not an entry object — did you mean to pass <table> for a single write?")
                    validateTableEntry(e)?.let { return@io err("\"$k\": $it") }
                }
                parsed.entrySet().forEach { (k, v) -> tablesObj.add(k, v); written.add(k) }
            } else {
                validateTableEntry(parsed)?.let { return@io err(it) }
                tablesObj.add(table, parsed)
                written.add(table)
            }
        }
        refsFile.writeText(gson.toJson(rootObj))
        gson.toJson(JsonObject().apply {
            addProperty("ok", true)
            if (delete) addProperty("deleted", table) else add("written", arrayOf(written))
            addProperty("tablesInSchema", tablesObj.size())
            addProperty("path", refsFile.path.replace('\\', '/'))
        })
    }

    /** Structural check of one table entry, so a bad write can't silently invalidate the whole schema
     *  (the viewer's loader rejects refs.json wholesale when a field has the wrong shape). */
    private fun validateTableEntry(entry: JsonObject): String? {
        if (entry.get("file")?.takeIf { it.isJsonPrimitive } == null) return "entry is missing \"file\" (workbook path relative to the data folder)"
        if (entry.get("sheet")?.takeIf { it.isJsonPrimitive } == null) return "entry is missing \"sheet\""
        val id = entry.get("id")
        if (id != null && !id.isJsonArray) return "\"id\" must be an array of column field codes"
        val refs = entry.get("refs") ?: return null
        if (!refs.isJsonArray) return "\"refs\" must be an array"
        val arr = refs.asJsonArray
        for (i in 0 until arr.size()) {
            val ro = arr[i].takeIf { it.isJsonObject }?.asJsonObject ?: return "refs[$i] is not an object"
            if (ro.get("from")?.takeIf { it.isJsonArray } == null) return "refs[$i] is missing \"from\" (array of column field codes)"
            if (ro.get("to")?.takeIf { it.isJsonPrimitive } == null) return "refs[$i] is missing \"to\" (target table id)"
            val w = ro.get("when")
            if (w != null) {
                if (!w.isJsonObject) return "refs[$i] \"when\" must be an object of column -> accepted value(s)"
                for ((c, v) in w.asJsonObject.entrySet()) {
                    if (!(v.isJsonPrimitive || (v.isJsonArray && v.asJsonArray.all { it.isJsonPrimitive })))
                        return "refs[$i] \"when\".$c must be a value or an array of values"
                }
            }
        }
        return null
    }

    @McpTool
    @McpDescription(
        "Write (overwrite) the WHOLE refs.json in <dir> — fine for small schemas; on a large one prefer " +
            "write_table_refs (per-table or bulk insert/replace, no whole-file round-trip). Validates that " +
            "the content parses as JSON with a top-level \"tables\" object before writing; nothing is " +
            "written on error. REF SYNTAX — every field is YOUR judgment: mark each ref with _source: " +
            "\"code\" (derived from reading the game source) or \"data\" (hypothesized from values and " +
            "check_ref numbers alone). POLYMORPHIC columns (target depends on a type column — decided by " +
            "reading the game code) are one CONDITIONAL ref per target: {\"from\": [\"Param\"], \"to\": " +
            "\"Item\", \"when\": {\"ParamType\": [\"3\", \"4\"]}} — the ref applies only to rows where every " +
            "\"when\" column has one of its accepted values (cell display text; a scalar means one value); " +
            "write sibling refs on the same column with different \"when\" for the other targets. If the game " +
            "uses a placeholder other than 0 for \"no reference\" (-1, None, …), set the top-level " +
            "\"nullValues\": [\"0\", \"-1\"] — those values are never resolved nor reported as broken.",
    )
    suspend fun write_refs(
        @McpDescription("Absolute path to the data folder.") dir: String,
        @McpDescription("Full refs.json content to write.") json: String,
    ): String = io {
        val parsed = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return@io err("content is not valid JSON")
        if (!parsed.has("tables")) return@io err("missing the top-level \"tables\" object")
        val file = File(dir, "refs.json")
        file.writeText(json)
        gson.toJson(JsonObject().apply { addProperty("ok", true); addProperty("path", file.path.replace('\\', '/')) })
    }

    @McpTool
    @McpDescription(
        "Validate the current refs.json against the data: counts of dangling (broken) references and " +
            "unreferenced (orphan) records, with a few examples each. Run after writing refs. Reading the " +
            "result is YOUR job: broken refs concentrated in one table usually mean that entry's " +
            "headerRow/dataStartRow/id is wrong (re-inspect with sample_rows), spread-out breaks suggest a " +
            "wrong target or a missing \"when\" condition, and placeholder ids showing up as broken mean " +
            "nullValues is incomplete.",
    )
    suspend fun validate_refs(@McpDescription("Absolute path to the data folder.") dir: String): String = io {
        val schema = loadRefSchema(File(dir)) ?: return@io err("no valid refs.json in $dir")
        val report = IndexRecordGraph(schema, GameDataLoader.buildIndex(schema)).validate()
        gson.toJson(JsonObject().apply {
            addProperty("broken", report.broken.size)
            addProperty("orphans", report.orphans.size)
            add("brokenSample", JsonArray().apply { report.broken.take(10).forEach { add("${it.from.table}#${it.from.id}.${it.column} -> ${it.toTable}#${it.missingId} (missing)") } })
            add("orphanSample", JsonArray().apply { report.orphans.take(10).forEach { add("${it.table}#${it.id}") } })
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
            "once. Table keys: the sheet name, qualified as \"<file>#<sheet>\" when the same sheet name " +
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
            addProperty("_next", "Every added table needs YOUR decisions: (1) sample_rows → decide headerRow/dataStartRow/id/display; (2) read the game source → design refs (\"when\" for polymorphic columns, _source: \"code\"); (3) check_ref each hypothesis; (4) write_table_refs (bulk: table=\"\" + {tableId: entry, ...}); (5) validate_refs. Delete non-data sheets via write_table_refs(delete=true).")
        })
    }

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun err(message: String): String = """{"error": ${JsonPrimitive(message)}}"""
    private fun arrayOf(items: Iterable<String>): JsonArray = JsonArray().apply { items.forEach { add(it) } }
}
