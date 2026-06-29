package com.example.xlsx

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.model.StylesTable
import org.apache.poi.xssf.usermodel.XSSFComment
import org.xml.sax.InputSource
import java.io.File
import java.io.InputStream

private const val MAX_ROWS = 5000       // rows sampled per sheet (bounds memory + time)
private const val MAX_DISTINCT = 5000   // distinct values kept per column (for overlap tests)
private const val COVER_MIN = 0.6       // value-overlap fraction needed to propose a reference
private const val COVER_MIN_NAMED = 0.4 // ...lowered when the column NAME points at the target table
private const val HEADER_ROW = 1        // 1-based; matches the game-data convention
private const val DATA_START_ROW = 4

/** One sampled sheet: detected id column(s) + a capped distinct-value set per column. Raw rows are
 *  discarded after sampling, so only these compact sets are held while inferring across tables.
 *  [file] is the workbook path RELATIVE to the sampled root (e.g. `item/Item.xlsx`) so nested layouts
 *  round-trip through refs.json. [tableId] is the sheet name, qualified only when it collides across files. */
class TableSample(
    var tableId: String,
    val file: String,
    val sheet: String,
    val columns: List<String>,
    val idCols: List<String>,
    val display: String?,
    val colValues: Map<String, Set<String>>,
)

/** A proposed foreign key: [from] column → [to] table. [by] (non-null) marks a GROUP reference keyed by a
 *  subset of the target's id; [split] marks a delimited multi-value column; [confidence] is the overlap. */
class InferredRef(val from: String, val to: String, val split: String?, val by: List<String>?, val confidence: Double)

class InferredTable(val sample: TableSample, val refs: List<InferredRef>)

/**
 * Deterministic refs.json drafter. Streams a sample of every sheet in a folder, detects each table's id
 * column(s) by leftmost-prefix uniqueness (so composite keys like [GroupId, Slot] are found), then proposes
 * foreign keys by VALUE OVERLAP — a column whose (optionally delimiter-split) values are covered by another
 * table's id set, or by a composite table's first-id-column GROUP set. The column NAME is used as a tie-break.
 * The result is a DRAFT: the AI / user refines naming, keys it missed, and embedded patterns.
 */
object SchemaInferencer {

    fun draftRefsJson(baseDir: File): String = toJson(infer(sample(baseDir)))

    // ---- 1. sample every sheet (streaming, capped), recursing into subfolders ----
    fun sample(baseDir: File): List<TableSample> = withPoiClassLoader {
        val out = ArrayList<TableSample>()
        xlsxFilesUnder(baseDir).forEach { file ->
            val rel = file.relativeTo(baseDir).invariantSeparatorsPath // root-relative, '/'-joined for refs.json
            runCatching {
                OPCPackage.open(file, PackageAccess.READ).use { pkg ->
                    val reader = XSSFReader(pkg)
                    val styles = reader.stylesTable
                    val strings = runCatching { ReadOnlySharedStringsTable(pkg) }.getOrNull()
                    val fmt = DataFormatter()
                    val sheets = reader.sheetsData as XSSFReader.SheetIterator
                    while (sheets.hasNext()) {
                        val stream = sheets.next()
                        out.add(collect(stream, rel, sheets.sheetName, styles, strings, fmt))
                    }
                }
            }
        }
        disambiguate(out)
    }

    private val IGNORED_DIRS = setOf(".git", ".idea", ".vs", ".gradle", "node_modules", "bin", "obj")

    /** Every .xlsx under [baseDir] (recursive), skipping VCS/build/hidden dirs and Excel lock files. */
    private fun xlsxFilesUnder(baseDir: File): List<File> =
        baseDir.walkTopDown()
            .onEnter { dir -> dir == baseDir || (!dir.name.startsWith(".") && dir.name.lowercase() !in IGNORED_DIRS) }
            .filter { it.isFile && it.name.endsWith(".xlsx", true) && !it.name.startsWith("~") }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

    /** Recursion can surface the same sheet name in different folders; the table id is the JSON key and a
     *  ref target, so it must be unique. Keep the plain sheet name when it's unique; qualify collisions with
     *  the workbook's relative path (always distinct across files). */
    private fun disambiguate(samples: List<TableSample>): List<TableSample> {
        val collides = samples.groupingBy { it.sheet }.eachCount().filterValues { it > 1 }.keys
        samples.forEach { s -> if (s.sheet in collides) s.tableId = s.file.substringBeforeLast('.') + "#" + s.sheet }
        return samples
    }

    private fun collect(stream: InputStream, file: String, sheet: String, styles: StylesTable?, strings: ReadOnlySharedStringsTable?, fmt: DataFormatter): TableSample {
        val collector = SampleCollector()
        parseSheet(stream, collector, styles, strings, fmt)
        return collector.toSample(file, sheet)
    }

    private fun parseSheet(stream: InputStream, collector: SampleCollector, styles: StylesTable?, strings: ReadOnlySharedStringsTable?, fmt: DataFormatter) {
        val xml = XMLHelper.newXMLReader().apply { contentHandler = XSSFSheetXMLHandler(styles, strings, collector, fmt, false) }
        runCatching { stream.use { xml.parse(InputSource(it)) } }
            .exceptionOrNull()?.let { if (it !is StopParsing) throw it } // StopParsing = sample cap reached
    }

    /** Resolve a table id to its (root-relative file, sheet) by reading only sheet NAMES — no row sampling —
     *  applying the same collision-qualification as [disambiguate]. Lets sample_rows locate one table without
     *  the full [sample] scan (which would parse every row of every workbook just to map id → file). */
    fun locate(baseDir: File, tableId: String): Pair<String, String>? = withPoiClassLoader {
        val entries = ArrayList<Pair<String, String>>() // relFile -> sheet
        xlsxFilesUnder(baseDir).forEach { file ->
            val rel = file.relativeTo(baseDir).invariantSeparatorsPath
            runCatching {
                OPCPackage.open(file, PackageAccess.READ).use { pkg ->
                    val sheets = XSSFReader(pkg).sheetsData as XSSFReader.SheetIterator
                    while (sheets.hasNext()) { sheets.next().close(); entries.add(rel to sheets.sheetName) }
                }
            }
        }
        val collides = entries.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
        entries.firstOrNull { (rel, sheet) ->
            (if (sheet in collides) rel.substringBeforeLast('.') + "#" + sheet else sheet) == tableId
        }
    }

    /** Preview the first [limit] data rows of [sheet] in the workbook at root-relative [relFile] (all
     *  columns) — for the MCP sample_rows tool. [relFile]/[sheet] come from [locate]/[sample], so this is
     *  collision-safe even when the same sheet name appears in several folders. */
    fun previewRows(baseDir: File, relFile: String, sheet: String, limit: Int): Pair<List<String>, List<Map<String, String>>> = withPoiClassLoader {
        val file = File(baseDir, relFile)
        if (!file.isFile) return@withPoiClassLoader emptyList<String>() to emptyList()
        runCatching {
            OPCPackage.open(file, PackageAccess.READ).use { pkg ->
                val reader = XSSFReader(pkg)
                val styles = reader.stylesTable
                val strings = runCatching { ReadOnlySharedStringsTable(pkg) }.getOrNull()
                val fmt = DataFormatter()
                val sheets = reader.sheetsData as XSSFReader.SheetIterator
                while (sheets.hasNext()) {
                    val stream = sheets.next()
                    if (sheets.sheetName == sheet) {
                        val c = SampleCollector(limit.coerceIn(1, MAX_ROWS))
                        parseSheet(stream, c, styles, strings, fmt)
                        return@use c.preview()
                    }
                    stream.close()
                }
                null
            }
        }.getOrNull() ?: (emptyList<String>() to emptyList())
    }

    // ---- 2. infer references by value overlap, via an INVERTED index ----
    // An inverted "id value -> tables that own it" index turns the match from O(tables² · tokens) into
    // ~O(total tokens): for a column we tally how many of its tokens any target table holds as an id,
    // instead of scanning every table. (Measured: a 7000-table draft's inference drops from minutes to
    // well under a second.) Semantics are unchanged — split/group(by)/name-threshold/best-coverage.
    fun infer(samples: List<TableSample>): List<InferredTable> {
        val index = HashMap<String, MutableList<String>>()      // id value -> owning table ids
        val targetBy = HashMap<String, List<String>?>()         // target table -> group key (null = single id)
        samples.forEach { u ->
            if (u.idCols.isEmpty()) return@forEach
            targetBy[u.tableId] = if (u.idCols.size == 1) null else listOf(u.idCols.first())
            u.colValues[u.idCols.first()].orEmpty().forEach { v ->        // first id col = single id OR group key
                if (v.isNotEmpty() && v != "0") index.getOrPut(v) { ArrayList() }.add(u.tableId)
            }
        }
        return samples.map { t ->
            val refs = ArrayList<InferredRef>()
            // display + a SINGLE primary key are never refs; composite-key COMPONENTS can be (e.g.
            // MonsterSpawn.MapId is part of its key AND → Map) — the self-table guard keeps a key from
            // referencing its own table.
            val skip = (listOfNotNull(t.display) + if (t.idCols.size == 1) t.idCols else emptyList()).toSet()
            t.columns.forEach { col ->
                if (col in skip) return@forEach
                val raw = t.colValues[col].orEmpty()
                val split = detectSplit(raw)
                val tokens = (if (split != null) raw.flatMap { it.split(split) }.map { it.trim() } else raw.toList())
                    .filter { it.isNotEmpty() && it != "0" }.toSet()
                if (tokens.isEmpty()) return@forEach
                val selfKey = col in t.idCols
                val tally = HashMap<String, Int>()
                tokens.forEach { tok ->
                    index[tok]?.forEach { owner ->
                        if (!(owner == t.tableId && selfKey)) tally[owner] = (tally[owner] ?: 0) + 1
                    }
                }
                var bestTable: String? = null
                var bestCov = 0.0
                tally.forEach { (tbl, hits) ->
                    val cov = hits.toDouble() / tokens.size
                    val min = if (namesMatch(col, tbl)) COVER_MIN_NAMED else COVER_MIN
                    if (cov >= min && (cov > bestCov || (cov == bestCov && tbl < (bestTable ?: "￿")))) {
                        bestCov = cov; bestTable = tbl
                    }
                }
                bestTable?.let { refs.add(InferredRef(col, it, split, targetBy[it], bestCov)) }
            }
            InferredTable(t, refs)
        }
    }

    private fun detectSplit(values: Set<String>): String? = when {
        values.any { it.contains(';') } -> ";"
        values.count { it.contains(',') } > values.size / 2 -> ","
        else -> null
    }

    private fun namesMatch(col: String, table: String): Boolean {
        val core = col.let { if (it.endsWith("Ids", true)) it.dropLast(3) else if (it.endsWith("Id", true)) it.dropLast(2) else it }
        if (core.length < 2) return false
        return core.contains(table, true) || table.contains(core, true)
    }

    // ---- 3. emit refs.json ----
    fun toJson(tables: List<InferredTable>): String {
        val root = JsonObject()
        root.addProperty("version", 1)
        root.addProperty("_note", "Auto-generated DRAFT (Generate refs.json draft). Review refs with _confidence below 1.0, tables with an empty id, and group/embedded refs; up to $MAX_ROWS rows are sampled per sheet.")
        val tablesObj = JsonObject()
        tables.forEach { entry ->
            val t = JsonObject()
            t.addProperty("file", entry.sample.file)
            t.addProperty("sheet", entry.sample.sheet)
            t.addProperty("headerRow", HEADER_ROW)
            t.addProperty("dataStartRow", DATA_START_ROW)
            t.add("id", JsonArray().apply { entry.sample.idCols.forEach { add(it) } })
            entry.sample.display?.let { t.addProperty("display", it) }
            if (entry.refs.isNotEmpty()) {
                t.add("refs", JsonArray().apply {
                    entry.refs.forEach { r ->
                        add(JsonObject().apply {
                            add("from", JsonArray().apply { add(r.from) })
                            addProperty("to", r.to)
                            r.by?.let { add("by", JsonArray().apply { it.forEach { c -> add(c) } }) }
                            r.split?.let { addProperty("split", it) }
                            addProperty("_confidence", Math.round(r.confidence * 100) / 100.0)
                        })
                    }
                })
            }
            tablesObj.add(entry.sample.tableId, t)
        }
        root.add("tables", tablesObj)
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }
}

private class StopParsing : RuntimeException()

/** SAX collector: reads the field-code header row, samples up to [MAX_ROWS] data rows (all columns), and
 *  detects id columns. Aborts the parse via [StopParsing] once the sample cap is hit. */
private class SampleCollector(private val maxRows: Int = MAX_ROWS) : XSSFSheetXMLHandler.SheetContentsHandler {
    private val headerIdx = HEADER_ROW - 1
    private val firstDataIdx = DATA_START_ROW - 1
    private val cols = sortedMapOf<Int, String>()
    private val rows = ArrayList<Map<String, String>>()
    private var rowNum = -1
    private var cur = HashMap<String, String>()

    override fun startRow(rowNum: Int) { this.rowNum = rowNum; cur = HashMap() }

    override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
        val col = colOf(cellReference ?: return)
        val v = formattedValue?.trim().orEmpty()
        if (rowNum == headerIdx) { if (v.isNotEmpty()) cols[col] = v }
        else if (rowNum >= firstDataIdx) { val name = cols[col] ?: return; if (v.isNotEmpty()) cur[name] = v }
    }

    override fun endRow(rowNum: Int) {
        if (rowNum >= firstDataIdx && cur.isNotEmpty()) {
            rows.add(cur)
            if (rows.size >= maxRows) throw StopParsing()
        }
    }

    override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}

    fun toSample(file: String, sheet: String): TableSample {
        val columns = cols.values.toList()
        val colValues = HashMap<String, MutableSet<String>>()
        rows.forEach { row -> row.forEach { (k, v) -> colValues.getOrPut(k) { LinkedHashSet() }.let { if (it.size < MAX_DISTINCT) it.add(v) } } }
        return TableSample(sheet, file, sheet, columns, detectIdCols(columns, rows), columns.firstOrNull { it.equals("Name", true) }, colValues)
    }

    /** (columns, rows) preview for the sample_rows tool — the raw sampled rows, undiscarded. */
    fun preview(): Pair<List<String>, List<Map<String, String>>> = cols.values.toList() to rows.toList()

    /** Id columns = an explicit unique "Id", else the shortest leftmost prefix of columns that is unique
     *  across the sample (so composite keys like [GroupId, Slot] are recovered). Empty if none is clear. */
    private fun detectIdCols(columns: List<String>, rows: List<Map<String, String>>): List<String> {
        if (rows.isEmpty() || columns.isEmpty()) return emptyList()
        columns.firstOrNull { it.equals("Id", true) }?.let { if (unique(rows, listOf(it))) return listOf(it) }
        val prefix = ArrayList<String>()
        for (col in columns.take(4)) { // keys live leftmost; cap width to avoid absurd keys
            prefix.add(col)
            if (unique(rows, prefix)) return prefix.toList()
        }
        return emptyList()
    }

    private fun unique(rows: List<Map<String, String>>, cols: List<String>): Boolean {
        val seen = HashSet<String>()
        for (row in rows) {
            if (cols.any { row[it].isNullOrEmpty() }) return false // a key column was blank → not a key
            if (!seen.add(cols.joinToString(" ") { row[it]!! })) return false
        }
        return seen.isNotEmpty()
    }
}

/** Column index from the letters of an A1 reference, e.g. "AB12" -> 27. */
private fun colOf(ref: String): Int {
    var c = 0
    for (ch in ref) {
        val u = ch.uppercaseChar()
        if (u in 'A'..'Z') c = c * 26 + (u - 'A' + 1) else break
    }
    return c - 1
}
