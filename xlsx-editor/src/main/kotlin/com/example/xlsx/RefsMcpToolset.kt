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
 * (Claude Code, etc.) can author the relationship schema (refs.json). They ship inside this plugin. The
 * deterministic [SchemaInferencer] does the heavy lifting (foreign keys by value overlap); the AI reads the
 * game SOURCE itself for the cases values alone can't decide (e.g. polymorphic `param`/`paramType` columns).
 *
 * Typical flow: list_tables → sample_rows / column_values → suggest_refs (draft) → write_refs → validate_refs.
 * All paths are absolute folders that hold the .xlsx data (and refs.json). Every tool returns JSON text.
 */
class RefsMcpToolset : McpToolset {

    @McpTool
    @McpDescription(
        "List every table (sheet) in the .xlsx workbooks in <dir>: file, sheet, detected id column(s), the " +
            "display column, and all field-code columns. Start here to understand the dataset.",
    )
    suspend fun list_tables(@McpDescription("Absolute path to the folder holding the .xlsx files.") dir: String): String = io {
        val arr = JsonArray()
        SchemaInferencer.sample(File(dir)).forEach { t ->
            arr.add(JsonObject().apply {
                addProperty("table", t.tableId); addProperty("file", t.file); addProperty("sheet", t.sheet)
                add("idCols", arrayOf(t.idCols))
                t.display?.let { addProperty("display", it) }
                add("columns", arrayOf(t.columns))
            })
        }
        gson.toJson(arr)
    }

    @McpTool
    @McpDescription("Distinct sample values of one column — judge whether it is an id, a foreign key, an enum, or free data.")
    suspend fun column_values(
        @McpDescription("Absolute path to the data folder.") dir: String,
        @McpDescription("Table id (sheet name).") table: String,
        @McpDescription("Column field code.") column: String,
        @McpDescription("Max distinct values to return.") limit: Int = 50,
    ): String = io {
        val t = SchemaInferencer.sample(File(dir)).firstOrNull { it.tableId == table } ?: return@io err("no such table: $table")
        val values = t.colValues[column] ?: return@io err("no such column: $column in $table")
        gson.toJson(JsonObject().apply {
            addProperty("table", table); addProperty("column", column)
            add("values", arrayOf(values.take(limit)))
        })
    }

    @McpTool
    @McpDescription("Preview the first data rows of a table (all columns) so you can see real values.")
    suspend fun sample_rows(
        @McpDescription("Absolute path to the data folder.") dir: String,
        @McpDescription("Table id (sheet name).") table: String,
        @McpDescription("Max rows.") limit: Int = 10,
    ): String = io {
        val (relFile, sheet) = SchemaInferencer.locate(File(dir), table) ?: return@io err("no such table: $table")
        val (columns, rows) = SchemaInferencer.previewRows(File(dir), relFile, sheet, limit)
        if (columns.isEmpty()) return@io err("no readable columns/rows for table: $table")
        gson.toJson(JsonObject().apply {
            add("columns", arrayOf(columns))
            add("rows", JsonArray().apply { rows.forEach { r -> add(JsonObject().apply { r.forEach { (k, v) -> addProperty(k, v) } }) } })
        })
    }

    @McpTool
    @McpDescription(
        "Infer a refs.json relationship-schema DRAFT from <dir> by sampling each sheet, detecting id columns " +
            "(composite keys included), and matching foreign keys by value overlap (split/group refs included). " +
            "Returns draft JSON with a _confidence per ref — a starting point to review and refine.",
    )
    suspend fun suggest_refs(@McpDescription("Absolute path to the data folder.") dir: String): String =
        io { SchemaInferencer.draftRefsJson(File(dir)) }

    @McpTool
    @McpDescription("Read the current refs.json in <dir> (the relationship schema). Returns its text, or an error if absent.")
    suspend fun read_refs(@McpDescription("Absolute path to the data folder.") dir: String): String = io {
        File(dir, "refs.json").let { if (it.isFile) it.readText() else err("no refs.json in $dir") }
    }

    @McpTool
    @McpDescription(
        "Write (overwrite) refs.json in <dir>. Validates that the content parses as JSON with a top-level " +
            "\"tables\" object before writing. Returns ok + path, or the validation error (nothing is written on error).",
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
            "unreferenced (orphan) records, with a few examples each. Run after write_refs to check the schema.",
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

    @McpTool
    @McpDescription(
        "Build (or additively extend) the WHOLE relationship schema for a folder tree in one pass. Samples " +
            "every sheet under <dir> (recursing), infers foreign keys by value overlap across ALL of them " +
            "(so cross-folder references are found), and writes <dir>/refs.json. Tables already in an existing " +
            "refs.json are kept as-is (re-runs only ADD newly-found tables, so manual refinements survive). " +
            "Returns a SUMMARY (counts + low-confidence refs to review), never the full schema — point this at " +
            "the top of a large dataset to draft the whole thing at once, then refine with read_refs/write_refs.",
    )
    suspend fun build_refs(
        @McpDescription("Absolute path to the root data folder; its refs.json is written/extended here.") dir: String,
        @McpDescription("Also validate the result against the data (broken/orphan counts). Slower on a huge schema.") validate: Boolean = false,
    ): String = io {
        val root = File(dir)
        val inferred = SchemaInferencer.infer(SchemaInferencer.sample(root))
        val draftTables = JsonParser.parseString(SchemaInferencer.toJson(inferred)).asJsonObject.getAsJsonObject("tables")
        val refsFile = File(root, "refs.json")
        val rootObj: JsonObject
        if (refsFile.isFile) {
            // Never clobber a present-but-broken file (it may hold manual refinements) — refuse instead.
            val parsed = runCatching { JsonParser.parseString(refsFile.readText()) }.getOrNull()
            if (parsed == null || !parsed.isJsonObject)
                return@io err("existing refs.json does not parse as a JSON object — refusing to overwrite; fix or remove it first")
            rootObj = parsed.asJsonObject
        } else {
            rootObj = JsonObject().apply { addProperty("version", 1) }
        }
        val tablesEl = rootObj.get("tables")
        if (tablesEl != null && !tablesEl.isJsonObject)
            return@io err("existing refs.json has a non-object \"tables\" — refusing to overwrite; fix or remove it first")
        val tablesObj = tablesEl?.asJsonObject ?: JsonObject().also { rootObj.add("tables", it) }

        val addedKeys = HashSet<String>()
        draftTables.entrySet().forEach { (k, v) -> if (!tablesObj.has(k)) { tablesObj.add(k, v); addedKeys.add(k) } }
        refsFile.writeText(gson.toJson(rootObj))

        // counts/examples scope to tables ADDED this run (so a no-op re-run reports 0, not the dataset).
        val addedTables = inferred.filter { it.sample.tableId in addedKeys }
        val low = addedTables.flatMap { t -> t.refs.filter { it.confidence < 1.0 }.map { t.sample.tableId to it } }
        val summary = JsonObject().apply {
            addProperty("refs_json", refsFile.path.replace('\\', '/'))
            addProperty("tablesInSchema", tablesObj.size())
            addProperty("tablesAddedThisRun", addedKeys.size)
            addProperty("refsAddedThisRun", addedTables.sumOf { it.refs.size })
            addProperty("lowConfidenceRefs", low.size)
            add("lowConfidenceSample", JsonArray().apply {
                low.take(10).forEach { (tbl, r) -> add("$tbl.${r.from} -> ${r.to} (${Math.round(r.confidence * 100) / 100.0})") }
            })
            addProperty("_note", "Re-runs only ADD new tables (existing entries are left untouched — delete a table from refs.json to re-infer it). Review lowConfidenceRefs + add refs values alone can't decide (e.g. polymorphic columns) via read_refs/write_refs.")
        }
        if (validate) {
            val schema = loadRefSchema(root)
            if (schema == null) summary.addProperty("validation", "skipped — the written refs.json did not reload as a valid schema")
            else {
                val report = IndexRecordGraph(schema, GameDataLoader.buildIndex(schema)).validate()
                summary.addProperty("broken", report.broken.size)
                summary.addProperty("orphans", report.orphans.size)
            }
        }
        gson.toJson(summary)
    }

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun err(message: String): String = """{"error": ${JsonPrimitive(message)}}"""
    private fun arrayOf(items: Iterable<String>): JsonArray = JsonArray().apply { items.forEach { add(it) } }
}
