package com.example.xlsx

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
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
)

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
    private const val CACHE_VERSION = 1
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
                GameIndex(reverse, idName, groupMembers, outDeg)
            }
        }.getOrNull()
    }

    /** Build the compact reverse / id-name / group / out-degree indices in a single streaming pass. */
    fun buildIndex(schema: RefSchema): GameIndex = withPoiClassLoader {
        val reverse = HashMap<String, MutableList<Backref>>()
        val idName = HashMap<String, String>()
        val groupMembers = HashMap<String, MutableList<String>>()
        val outDeg = HashMap<String, Int>()
        schema.tables.groupBy { it.file }.forEach { (fileName, sts) ->
            val file = File(schema.baseDir, fileName)
            if (!file.isFile) return@forEach
            runCatching {
                forEachSchemaSheet(file, sts) { td -> foldIntoIndex(td, schema.nullValues, reverse, idName, groupMembers, outDeg) }
            }.onFailure { LOG.warn("relationship index: failed to read $fileName (its tables are omitted from the graph)", it) }
        }
        GameIndex(reverse, idName, groupMembers, outDeg)
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
            WorkbookFactory.create(file, null, true).use { wb ->
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
    ) {
        val st = td.schema
        val skip = st.idCols.firstOrNull()
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
                if (!ref.appliesTo(row.values)) return@forEach // conditional (when) ref inactive on this row
                val embedded = ref.split != null || ref.pattern != null
                parseTokens(row.values[ref.fromCols.first()].orEmpty(), ref).forEach { tok ->
                    if (tok.isNotEmpty() && tok !in nullValues) {
                        deg++
                        reverse.getOrPut("${ref.toTable}|$tok") { ArrayList() }
                            .add(Backref(st.tableId, recId, name, ref.fromCols.first(), embedded))
                    }
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
    // including `when` condition columns, which must be present to evaluate conditional refs per row.
    private val needed: Set<String>? = if (st.idCols.size > 1) null else
        (st.idCols + st.refs.flatMap { it.fromCols + it.whenCond?.keys.orEmpty() } + listOfNotNull(st.displayCol)).toSet()
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
        if (rowNum >= firstDataIdx && cur.isNotEmpty()) rows.add(DataRow(st.tableId, cur))
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
