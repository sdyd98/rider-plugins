package com.example.xlsx

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.model.StylesTable
import org.apache.poi.xssf.usermodel.XSSFComment
import org.xml.sax.InputSource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/** One loaded data row, keyed columns -> rendered string value. */
class DataRow(val table: String, val values: Map<String, String>) {
    /** Internal index key (full id, joined). */
    fun key(idCols: List<String>): String = idCols.joinToString("|") { values[it].orEmpty() }

    /** Human id shown on a node, e.g. "2002·1" for a composite key. */
    fun displayId(idCols: List<String>): String = idCols.joinToString("·") { values[it].orEmpty() }
}

class TableData(
    val schema: SchemaTable,
    val columns: List<String>,
    val rows: List<DataRow>,
    val byId: Map<String, DataRow>,          // key() -> row (internal "|"-joined key)
    val byGroup: Map<String, List<DataRow>>, // first id col -> member rows
    val byDisplayId: Map<String, DataRow>,   // displayId() -> row (the RefRecord id)
)

/** A reference into a record: who references it, with the source's display id/name cached (so [inbound]
 *  needs zero row reads). */
data class Backref(val srcTable: String, val srcId: String, val srcName: String, val column: String, val embedded: Boolean)

/**
 * Compact, query-ready indices built by ONE streaming pass — small relative to the raw rows, so the
 * relationship views never hold the full tables. [reverse] answers incoming links; [idName] answers a
 * record's existence + display name (so outgoing targets resolve without loading the target table);
 * [groupMembers] renders group nodes; [outDeg] picks a well-connected default centre.
 */
class GameIndex(
    val reverse: Map<String, List<Backref>>,    // "toTable|token" -> referencing rows
    val idName: Map<String, String>,            // "table|id" -> display name
    val groupMembers: Map<String, List<String>>, // "table|groupKey" -> member display strings
    val outDeg: Map<String, Int>,               // "table|id" -> outgoing ref count
    val refStats: List<RefStat> = emptyList(),  // per-relation evaluation tallies (validate_refs)
    /** Schema workbooks that could not be indexed (file → reason): a missing or unreadable file's
     *  tables silently vanish from the graph otherwise — validate_refs surfaces these as warnings. */
    val loadErrors: List<Pair<String, String>> = emptyList(),
)

/**
 * Per-relation tallies from the index pass — validate_refs' visibility into what was actually
 * evaluated. Without these, a `when`/`rowFilter` that excludes EVERY row reads as "0 broken refs =
 * success"; with them the caller sees 0 evaluated rows and knows the condition is wrong.
 */
class RefStat(
    val table: String,
    val column: String,
    /** Target table id, or the comma-joined union ("Npc,Npc2") for a multi-target ref. */
    val to: String,
    var rows: Int = 0,              // source rows seen (non-empty id, AFTER the table's rowFilter)
    var skippedByWhen: Int = 0,     // rows this ref's when-condition excluded
    var tokens: Int = 0,            // tokens kept → edges (non-empty, non-placeholder)
    var placeholderTokens: Int = 0, // tokens dropped as nullValues placeholders
    var patternMisses: Int = 0,     // non-empty parts the "pattern" regex failed to extract from
) {
    val evaluatedRows: Int get() = rows - skippedByWhen
}

/**
 * Reads the xlsx tables named by a [RefSchema] with POI's **streaming** reader (XSSFReader + SAX) rather
 * than the usermodel DOM, so a 60 MB sheet is processed a row at a time. A [DataFormatter] is wired into
 * the SAX handler so each cell renders to exactly the display string the usermodel path produced
 * (ids/tokens match → refs resolve the same). Only schema-needed columns are kept (id + ref + display;
 * group-keyed tables keep all columns for their member rows). Use [buildIndex] for the one-pass indices
 * and [loadTable] to stream a single table on demand.
 */
object GameDataLoader {
    private const val CACHE_MAGIC = 0x52465849 // "RFXI" — on-disk index cache marker
    private const val CACHE_VERSION = 2 // v2: + refStats (per-relation validate_refs tallies)
    private val LOG = Logger.getInstance(GameDataLoader::class.java)

    /** Build the index, or load it from the on-disk cache when the source files are unchanged
     *  (cache key = each file's path + last-modified + size). Subsequent opens skip the streaming pass. */
    fun loadOrBuildIndex(schema: RefSchema): GameIndex {
        val key = cacheKey(schema)
        val cacheFile = cacheFileFor(schema)
        readIndexCache(cacheFile, key)?.let { return it } // fresh cache → skip the streaming pass
        val idx = buildIndex(schema)
        runCatching { writeIndexCache(cacheFile, key, idx) }
        return idx
    }

    private fun cacheKey(schema: RefSchema): String =
        // Fingerprint the data workbooks AND refs.json — editing the schema must invalidate the cache too.
        (listOf("refs.json") + schema.tables.map { it.file }).distinct().sorted().joinToString("|") { f ->
            val file = File(schema.baseDir, f); "$f:${file.lastModified()}:${file.length()}"
        }

    private fun cacheFileFor(schema: RefSchema): File =
        File(File(PathManager.getSystemPath(), "refsgraph"), Integer.toHexString(schema.baseDir.path.hashCode()) + ".idx")

    private fun writeIndexCache(file: File, key: String, idx: GameIndex) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(CACHE_MAGIC); out.writeInt(CACHE_VERSION); out.writeUTF(key)
            out.writeInt(idx.reverse.size)
            idx.reverse.forEach { (k, list) ->
                out.writeUTF(k); out.writeInt(list.size)
                list.forEach { b -> out.writeUTF(b.srcTable); out.writeUTF(b.srcId); out.writeUTF(b.srcName); out.writeUTF(b.column); out.writeBoolean(b.embedded) }
            }
            out.writeInt(idx.idName.size); idx.idName.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
            out.writeInt(idx.groupMembers.size)
            idx.groupMembers.forEach { (k, m) -> out.writeUTF(k); out.writeInt(m.size); m.forEach { out.writeUTF(it) } }
            out.writeInt(idx.outDeg.size); idx.outDeg.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v) }
            out.writeInt(idx.refStats.size)
            idx.refStats.forEach { s ->
                out.writeUTF(s.table); out.writeUTF(s.column); out.writeUTF(s.to)
                out.writeInt(s.rows); out.writeInt(s.skippedByWhen); out.writeInt(s.tokens)
                out.writeInt(s.placeholderTokens); out.writeInt(s.patternMisses)
            }
            out.writeInt(idx.loadErrors.size)
            idx.loadErrors.forEach { (f, m) -> out.writeUTF(f); out.writeUTF(m) }
        }
    }

    private fun readIndexCache(file: File, key: String): GameIndex? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                if (inp.readInt() != CACHE_MAGIC || inp.readInt() != CACHE_VERSION) return@runCatching null
                if (inp.readUTF() != key) return@runCatching null // stale: a source file changed → rebuild
                val reverse = HashMap<String, List<Backref>>()
                repeat(inp.readInt()) {
                    val k = inp.readUTF(); val n = inp.readInt(); val list = ArrayList<Backref>(n)
                    repeat(n) { list.add(Backref(inp.readUTF(), inp.readUTF(), inp.readUTF(), inp.readUTF(), inp.readBoolean())) }
                    reverse[k] = list
                }
                val idName = HashMap<String, String>(); repeat(inp.readInt()) { idName[inp.readUTF()] = inp.readUTF() }
                val groupMembers = HashMap<String, List<String>>()
                repeat(inp.readInt()) {
                    val k = inp.readUTF(); val n = inp.readInt(); val m = ArrayList<String>(n)
                    repeat(n) { m.add(inp.readUTF()) }; groupMembers[k] = m
                }
                val outDeg = HashMap<String, Int>(); repeat(inp.readInt()) { outDeg[inp.readUTF()] = inp.readInt() }
                val refStats = ArrayList<RefStat>()
                repeat(inp.readInt()) {
                    refStats.add(
                        RefStat(
                            inp.readUTF(), inp.readUTF(), inp.readUTF(),
                            rows = inp.readInt(), skippedByWhen = inp.readInt(), tokens = inp.readInt(),
                            placeholderTokens = inp.readInt(), patternMisses = inp.readInt(),
                        ),
                    )
                }
                val loadErrors = ArrayList<Pair<String, String>>()
                repeat(inp.readInt()) { loadErrors.add(inp.readUTF() to inp.readUTF()) }
                GameIndex(reverse, idName, groupMembers, outDeg, refStats, loadErrors)
            }
        }.getOrNull()
    }

    /** Build the compact reverse / id-name / group / out-degree indices in a single streaming pass. */
    fun buildIndex(schema: RefSchema): GameIndex = withPoiClassLoader {
        val reverse = HashMap<String, MutableList<Backref>>()
        val idName = HashMap<String, String>()
        val groupMembers = HashMap<String, MutableList<String>>()
        val outDeg = HashMap<String, Int>()
        val stats = LinkedHashMap<String, RefStat>()
        // Union-target backrefs can't be keyed during the pass (target membership is only known once
        // EVERY table has streamed) — buffer them and resolve after the pass.
        val unionPending = ArrayList<Triple<List<String>, String, Backref>>()
        val loadErrors = ArrayList<Pair<String, String>>()
        schema.tables.groupBy { it.file }.forEach { (fileName, sts) ->
            val file = File(schema.baseDir, fileName)
            if (!file.isFile) {
                loadErrors.add(fileName to "file not found")
                return@forEach
            }
            runCatching {
                forEachSchemaSheet(file, sts) { td -> foldIntoIndex(td, schema.nullValues, reverse, idName, groupMembers, outDeg, stats, unionPending) }
            }.onFailure {
                LOG.warn("relationship index: failed to read $fileName (its tables are omitted from the graph)", it)
                loadErrors.add(fileName to (it.message ?: it.javaClass.simpleName))
            }
        }
        // Resolve union backrefs: the first target table containing the token wins; a token in NONE
        // keys under the joined union label, so validate reports it missing from the whole union.
        unionPending.forEach { (toTables, tok, br) ->
            val hit = toTables.firstOrNull { "$it|$tok" in idName || "$it|$tok" in groupMembers }
            reverse.getOrPut("${hit ?: toTables.joinToString(",")}|$tok") { ArrayList() }.add(br)
        }
        GameIndex(reverse, idName, groupMembers, outDeg, stats.values.toList(), loadErrors)
    }

    /** Load a single table on demand (needed columns only). Null if the file/sheet is missing. */
    fun loadTable(schema: RefSchema, tableId: String): TableData? = withPoiClassLoader {
        val st = schema.table(tableId) ?: return@withPoiClassLoader null
        val file = File(schema.baseDir, st.file)
        if (!file.isFile) return@withPoiClassLoader null
        runCatching {
            var result: TableData? = null
            forEachSchemaSheet(file, listOf(st)) { result = it }
            result
        }.getOrNull()
    }

    /** Read each schema table in [file], building a [TableData] per matching sheet. Streams `.xlsx`
     *  (XSSFReader + SAX); for legacy `.xls` (BIFF, no streaming) it drives the SAME collector from a
     *  full HSSF usermodel load (capped at 65,536 rows by the format, so memory is bounded). */
    private inline fun forEachSchemaSheet(file: File, sts: List<SchemaTable>, body: (TableData) -> Unit) {
        val bySheet = sts.associateBy { it.sheet }
        if (file.name.endsWith(".xls", true)) {
            openXlsWorkbook(file).use { wb -> // shared read-only→stream fallback (see SheetScanner)
                val fmt = DataFormatter()
                for (i in 0 until wb.numberOfSheets) {
                    val st = bySheet[wb.getSheetName(i)] ?: continue
                    val collector = SheetCollector(st)
                    feedXlsSheet(wb.getSheetAt(i), fmt, collector)
                    body(collector.build())
                }
            }
        } else {
            openWorkbook(file) { reader, styles, strings, fmt ->
                val sheetIt = reader.sheetsData as XSSFReader.SheetIterator
                while (sheetIt.hasNext()) {
                    val stream = sheetIt.next()
                    val st = bySheet[sheetIt.sheetName]
                    if (st == null) { stream.close(); continue }
                    body(collectSheet(stream, st, styles, strings, fmt))
                }
            }
        }
    }

    private inline fun <R> openWorkbook(file: File, body: (XSSFReader, StylesTable?, ReadOnlySharedStringsTable?, DataFormatter) -> R): R =
        OPCPackage.open(file, PackageAccess.READ).use { pkg ->
            val reader = XSSFReader(pkg)
            body(reader, reader.stylesTable, runCatching { ReadOnlySharedStringsTable(pkg) }.getOrNull(), DataFormatter())
        }

    private fun collectSheet(stream: InputStream, st: SchemaTable, styles: StylesTable?, strings: ReadOnlySharedStringsTable?, fmt: DataFormatter): TableData {
        val collector = SheetCollector(st)
        val handler = XSSFSheetXMLHandler(styles, strings, collector, fmt, false)
        val xml = XMLHelper.newXMLReader()
        xml.contentHandler = handler
        stream.use { xml.parse(InputSource(it)) }
        return collector.build()
    }

    private fun foldIntoIndex(
        td: TableData,
        nullValues: Set<String>,
        reverse: HashMap<String, MutableList<Backref>>,
        idName: HashMap<String, String>,
        groupMembers: HashMap<String, MutableList<String>>,
        outDeg: HashMap<String, Int>,
        stats: LinkedHashMap<String, RefStat>,
        unionPending: ArrayList<Triple<List<String>, String, Backref>>,
    ) {
        val st = td.schema
        val skip = st.idCols.firstOrNull()
        val statOf = st.refs.associateWith { ref ->
            val toLabel = ref.toTables.joinToString(",")
            stats.getOrPut("${st.tableId}|${ref.fromCols.first()}|$toLabel") { RefStat(st.tableId, ref.fromCols.first(), toLabel) }
        }
        td.rows.forEach { row ->
            val recId = if (st.idCols.size == 1) row.values[st.idCols.first()].orEmpty() else row.displayId(st.idCols)
            if (recId.isEmpty()) return@forEach
            val recKey = "${st.tableId}|$recId"
            val name = st.displayCol?.let { row.values[it] }.orEmpty()
            idName[recKey] = name
            if (st.idCols.size > 1) {
                val gk = row.values[st.idCols.first()].orEmpty()
                groupMembers.getOrPut("${st.tableId}|$gk") { ArrayList() }
                    .add(td.columns.filter { it != skip }.joinToString(" · ") { row.values[it].orEmpty() })
            }
            var deg = 0
            st.refs.forEach { ref ->
                val stat = statOf.getValue(ref)
                stat.rows++
                if (!ref.appliesTo(row.values)) { // conditional (when) ref inactive on this row
                    stat.skippedByWhen++
                    return@forEach
                }
                val embedded = ref.split != null || ref.pattern != null
                val (tokens, misses) = parseTokensCounted(row.values[ref.fromCols.first()].orEmpty(), ref)
                stat.patternMisses += misses
                tokens.forEach { tok ->
                    if (tok.isEmpty()) return@forEach
                    if (tok in nullValues) {
                        stat.placeholderTokens++
                        return@forEach
                    }
                    deg++
                    stat.tokens++
                    val br = Backref(st.tableId, recId, name, ref.fromCols.first(), embedded)
                    if (ref.toTables.size == 1) reverse.getOrPut("${ref.toTable}|$tok") { ArrayList() }.add(br)
                    else unionPending.add(Triple(ref.toTables, tok, br)) // resolved after the pass
                }
            }
            if (deg > 0) outDeg[recKey] = deg
        }
    }
}

/**
 * SAX row collector for one sheet. Captures the field-code header row, then keeps only the needed
 * columns of each data row (or all columns for group-keyed tables, whose member rows show everything).
 */
private class SheetCollector(private val st: SchemaTable) : XSSFSheetXMLHandler.SheetContentsHandler {
    // null = keep every column (group tables render all member columns); else the id/ref/display set —
    // including `when`/`rowFilter` condition columns, which must be present to evaluate them per row.
    private val needed: Set<String>? = if (st.idCols.size > 1) null else
        (st.idCols + st.refs.flatMap { r -> r.fromCols + r.whenCond.orEmpty().map { it.col } } +
            st.rowFilter.orEmpty().map { it.col } + listOfNotNull(st.displayCol)).toSet()
    private val headerIdx = st.headerRow - 1
    private val firstDataIdx = st.dataStartRow - 1
    private val colNames = HashMap<Int, String>()
    private val orderedCols = sortedMapOf<Int, String>()
    private val rows = ArrayList<DataRow>()
    private var rowNum = -1
    private var cur = HashMap<String, String>()

    override fun startRow(rowNum: Int) { this.rowNum = rowNum; cur = HashMap() }

    override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
        val col = colOf(cellReference ?: return)
        val v = formattedValue?.trim().orEmpty()
        if (rowNum == headerIdx) {
            if (v.isNotEmpty()) { colNames[col] = v; orderedCols[col] = v }
        } else if (rowNum >= firstDataIdx) {
            val name = colNames[col] ?: return
            if ((needed == null || name in needed) && v.isNotEmpty()) cur[name] = v
        }
    }

    override fun endRow(rowNum: Int) {
        if (rowNum < firstDataIdx || cur.isEmpty()) return
        if (st.rowFilter?.all { it.matches(cur) } == false) return // table-level rowFilter drops the row
        rows.add(DataRow(st.tableId, cur))
    }

    override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}

    fun build(): TableData {
        val byId = LinkedHashMap<String, DataRow>()
        val byGroup = LinkedHashMap<String, MutableList<DataRow>>()
        val byDisplayId = LinkedHashMap<String, DataRow>()
        rows.forEach { row ->
            byId[row.key(st.idCols)] = row
            byDisplayId[row.displayId(st.idCols)] = row
            if (st.idCols.size > 1) byGroup.getOrPut(row.values[st.idCols.first()].orEmpty()) { ArrayList() }.add(row)
        }
        return TableData(st, orderedCols.values.toList(), rows, byId, byGroup, byDisplayId)
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
}

/**
 * Drive a streaming [XSSFSheetXMLHandler.SheetContentsHandler] from a legacy `.xls` (HSSF) sheet via the
 * usermodel, so the SAME collectors that parse `.xlsx` work for `.xls` too. Each cell renders to the same
 * display string the `.xlsx` SAX path produces — a formula cell shows its CACHED result (matching the
 * viewer and the streaming reader), so ids/FK tokens compare identically across formats.
 */
internal fun feedXlsSheet(sheet: Sheet, fmt: DataFormatter, handler: XSSFSheetXMLHandler.SheetContentsHandler) {
    for (r in 0..sheet.lastRowNum) {
        handler.startRow(r)
        sheet.getRow(r)?.let { row ->
            val lastCell = row.lastCellNum.toInt()
            for (c in 0 until lastCell) {
                val cell = row.getCell(c) ?: continue
                val v = if (cell.cellType != CellType.FORMULA) fmt.formatCellValue(cell) else formatCachedFormulaResult(cell, fmt)
                handler.cell(CellReference(r, c).formatAsString(), v, null)
            }
        }
        handler.endRow(r)
    }
}
