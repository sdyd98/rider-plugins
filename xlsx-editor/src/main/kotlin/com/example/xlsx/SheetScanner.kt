package com.example.xlsx

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.usermodel.XSSFComment
import org.xml.sax.InputSource
import java.io.File

/**
 * Mechanical sheet access for the refs.json MCP tools ([RefsMcpToolset]). This object makes NO
 * interpretive decisions — there is no header/id/display/reference detection here. It only
 * enumerates sheets, returns raw cells, extracts one column's values, and computes value-overlap
 * NUMBERS for coordinates the AI client supplies (sheet, column letter, start row). Every judgment
 * — which row is the header, which column is the id, what references what — is the AI's, recorded
 * via the write tools.
 */
object SheetScanner {

    const val MAX_DISTINCT = 5000          // distinct values kept per column query
    private const val MAX_ID_SET = 500_000 // target-column value-set cap for overlap()

    private val IGNORED_DIRS = setOf(".git", ".idea", ".vs", ".gradle", "node_modules", "bin", "obj")

    /** Every .xlsx / .xls under [baseDir] (recursive), skipping VCS/build/hidden dirs and Excel lock files. */
    private fun spreadsheetFilesUnder(baseDir: File): List<File> =
        baseDir.walkTopDown()
            .onEnter { dir -> dir == baseDir || (!dir.name.startsWith(".") && dir.name.lowercase() !in IGNORED_DIRS) }
            .filter { it.isFile && (it.name.endsWith(".xlsx", true) || it.name.endsWith(".xls", true)) && !it.name.startsWith("~") }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

    /** [listSheets]' result: every readable sheet, plus the files POI could NOT read with the actual
     *  parser error — a swallowed error made an unreadable workbook look like it had no sheets. */
    class SheetListing(val sheets: List<Pair<String, String>>, val errors: List<Pair<String, String>>)

    /** (root-relative file, sheet name) for every sheet under [baseDir] — reads only sheet NAMES,
     *  never row data (sheet bodies are not decompressed for .xlsx). Unreadable files land in
     *  [SheetListing.errors] instead of silently vanishing. */
    fun listSheets(baseDir: File): SheetListing = withPoiClassLoader {
        val out = ArrayList<Pair<String, String>>()
        val errors = ArrayList<Pair<String, String>>()
        spreadsheetFilesUnder(baseDir).forEach { file ->
            val rel = file.relativeTo(baseDir).invariantSeparatorsPath
            runCatching {
                if (file.name.endsWith(".xls", true)) {
                    openXlsWorkbook(file).use { wb ->
                        for (i in 0 until wb.numberOfSheets) out.add(rel to wb.getSheetName(i))
                    }
                } else {
                    OPCPackage.open(file, PackageAccess.READ).use { pkg ->
                        val sheets = XSSFReader(pkg).sheetsData as XSSFReader.SheetIterator
                        while (sheets.hasNext()) {
                            sheets.next().close()
                            out.add(rel to sheets.sheetName)
                        }
                    }
                }
            }.onFailure { e -> errors.add(rel to (e.message ?: e.javaClass.simpleName)) }
        }
        SheetListing(out, errors)
    }

    /** The first [limit] non-empty rows at/after 1-based [startRow], VERBATIM: (1-based row number →
     *  column letter → value). Null if the file/sheet is missing. */
    fun rawRows(baseDir: File, relFile: String, sheet: String, startRow: Int, limit: Int): List<Pair<Int, Map<String, String>>>? {
        val collector = RowWindowCollector(startRow - 1, limit.coerceAtLeast(1))
        if (!parseOneSheet(baseDir, relFile, sheet, collector)) return null
        return collector.rows.map { (rn, cells) ->
            (rn + 1) to cells.entries.associate { (c, v) -> CellReference.convertNumToColString(c) to v }
        }
    }

    class ColumnValues(val distinct: List<String>, val nonEmptyCells: Int, val truncated: Boolean)

    /** Distinct values + non-empty count of ONE column ([columnLetter]) from 1-based [startRow] down.
     *  Null if the file/sheet is missing or the column letter is invalid. */
    fun columnValues(baseDir: File, relFile: String, sheet: String, columnLetter: String, startRow: Int): ColumnValues? {
        val col = colIndexOf(columnLetter) ?: return null
        val collector = ColumnCollector(col, startRow - 1, MAX_DISTINCT)
        if (!parseOneSheet(baseDir, relFile, sheet, collector)) return null
        return ColumnValues(collector.distinct.toList(), collector.nonEmpty, collector.truncated)
    }

    class Overlap(
        val fromTokens: Int,
        val matched: Int,
        val missingSample: List<String>,
        val toIds: Int,
        val toTruncated: Boolean,
        /** True when the FROM column had more than [MAX_DISTINCT] distinct cell values — the numbers
         *  then cover only the first 5000 and the caller must not read them as a full check. */
        val fromTruncated: Boolean,
        /** With a [WhenClause] condition: rows that passed / failed it (-1 = no condition given). */
        val rowsEvaluated: Int = -1,
        val rowsSkipped: Int = -1,
    )

    /** Value-overlap NUMBERS between a hypothesized reference column and a target column — pure set
     *  arithmetic on coordinates the AI supplies; no thresholds, no proposal. [split] (optional) splits
     *  multi-value cells; [ignore] holds the AI-declared no-reference placeholders to drop.
     *  [fromWhere] (optional) restricts the FROM side to rows matching clauses keyed by column LETTER
     *  (same semantics as a ref's `when` — "" matches empty/absent cells) so a conditional/polymorphic
     *  hypothesis can be checked per branch BEFORE it is written. */
    fun overlap(
        baseDir: File,
        fromFile: String, fromSheet: String, fromColumn: String, fromStartRow: Int, split: String?,
        toFile: String, toSheet: String, toColumn: String, toStartRow: Int,
        ignore: Set<String>,
        fromWhere: List<WhenClause>? = null,
    ): Overlap? {
        val toCol = colIndexOf(toColumn) ?: return null
        val fromCol = colIndexOf(fromColumn) ?: return null
        val target = ColumnCollector(toCol, toStartRow - 1, MAX_ID_SET)
        if (!parseOneSheet(baseDir, toFile, toSheet, target)) return null
        val source = if (fromWhere.isNullOrEmpty()) {
            ColumnCollector(fromCol, fromStartRow - 1, MAX_DISTINCT)
        } else {
            ConditionalColumnCollector(fromCol, fromStartRow - 1, MAX_DISTINCT, fromWhere)
        }
        if (!parseOneSheet(baseDir, fromFile, fromSheet, source)) return null
        val (distinct, truncated) = when (source) {
            is ColumnCollector -> source.distinct to source.truncated
            is ConditionalColumnCollector -> source.distinct to source.truncated
            else -> return null
        }
        val tokens = distinct
            .flatMap { v -> if (split.isNullOrEmpty()) listOf(v) else v.split(split).map(String::trim) }
            .filter { it.isNotEmpty() && it !in ignore }
            .toSet()
        val missing = tokens.filter { it !in target.distinct }
        val cond = source as? ConditionalColumnCollector
        return Overlap(
            tokens.size, tokens.size - missing.size, missing.take(10), target.distinct.size,
            target.truncated, truncated,
            rowsEvaluated = cond?.rowsEvaluated ?: -1,
            rowsSkipped = cond?.rowsSkipped ?: -1,
        )
    }

    /** The trimmed non-empty cells of EXACTLY 1-based [row] (column letter → value). Empty map when
     *  that row holds no values (e.g. a wrong headerRow judgment); null if the file/sheet is missing.
     *  Used by write_table_refs to verify recorded FIELD CODES actually exist in the header row. */
    fun rowValues(baseDir: File, relFile: String, sheet: String, row: Int): Map<String, String>? {
        val collector = SingleRowCollector(row - 1)
        if (!parseOneSheet(baseDir, relFile, sheet, collector)) return null
        return collector.cells.entries.associate { (c, v) -> CellReference.convertNumToColString(c) to v }
    }

    /** Column index from letters ("A" → 0, "AB" → 27); null if not pure letters. */
    fun colIndexOf(letters: String): Int? {
        if (letters.isEmpty()) return null
        var c = 0
        for (ch in letters) {
            val u = ch.uppercaseChar()
            if (u !in 'A'..'Z') return null
            c = c * 26 + (u - 'A' + 1)
        }
        return c - 1
    }

    /** Stream ONE sheet of [relFile] into [handler]. False = the file exists but has no such sheet;
     *  a missing or UNREADABLE file throws [SheetScanException] with the actual parser error (a
     *  swallowed error used to surface as a misleading "no such sheet"). */
    private fun parseOneSheet(baseDir: File, relFile: String, sheet: String, handler: XSSFSheetXMLHandler.SheetContentsHandler): Boolean = withPoiClassLoader {
        val file = File(baseDir, relFile)
        if (!file.isFile) throw SheetScanException("no such file: $relFile")
        try {
            if (file.name.endsWith(".xls", true)) {
                openXlsWorkbook(file).use { wb ->
                    val idx = (0 until wb.numberOfSheets).firstOrNull { wb.getSheetName(it) == sheet } ?: return@use false
                    runCatching { feedXlsSheet(wb.getSheetAt(idx), DataFormatter(), handler) }
                        .exceptionOrNull()?.let { if (it !is StopParsing) throw it } // StopParsing = collector done
                    true
                }
            } else {
                OPCPackage.open(file, PackageAccess.READ).use { pkg ->
                    val reader = XSSFReader(pkg)
                    val styles = reader.stylesTable
                    val strings = runCatching { ReadOnlySharedStringsTable(pkg) }.getOrNull()
                    val sheets = reader.sheetsData as XSSFReader.SheetIterator
                    var found = false
                    while (sheets.hasNext()) {
                        val stream = sheets.next()
                        if (sheets.sheetName == sheet) {
                            val xml = XMLHelper.newXMLReader()
                            xml.contentHandler = XSSFSheetXMLHandler(styles, strings, handler, DataFormatter(), false)
                            runCatching { stream.use { xml.parse(InputSource(it)) } }
                                .exceptionOrNull()?.let { if (it !is StopParsing) throw it }
                            found = true
                            break
                        }
                        stream.close()
                    }
                    found
                }
            }
        } catch (e: SheetScanException) {
            throw e
        } catch (e: Exception) {
            throw SheetScanException("failed to read $relFile: ${e.message ?: ""} (${e.javaClass.simpleName})", e)
        }
    }
}

/** A workbook the scanner could not read — carries the ACTUAL parser error to the MCP response. */
class SheetScanException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Open a legacy `.xls`, falling back from POI's random-access read-only open to a full in-memory
 * stream read — some BIFF files that fail the file-backed open parse fine from a stream. Throws
 * [SheetScanException] with BOTH errors when neither path works.
 */
internal fun openXlsWorkbook(file: File): Workbook =
    try {
        WorkbookFactory.create(file, null, true)
    } catch (first: Exception) {
        try {
            file.inputStream().buffered().use { WorkbookFactory.create(it) }
        } catch (second: Exception) {
            throw SheetScanException(
                "POI cannot read ${file.name}: ${first.message ?: first.javaClass.simpleName} " +
                    "(stream fallback also failed: ${second.message ?: second.javaClass.simpleName})",
                second,
            )
        }
    }

/** Aborts a streaming parse once a collector has what it needs. */
private class StopParsing : RuntimeException()

/** Captures exactly one 0-based row's non-empty cells, then aborts the parse. */
private class SingleRowCollector(private val rowIdx: Int) : XSSFSheetXMLHandler.SheetContentsHandler {
    val cells = java.util.TreeMap<Int, String>()
    private var rowNum = -1

    override fun startRow(rowNum: Int) {
        if (rowNum > rowIdx) throw StopParsing()
        this.rowNum = rowNum
    }

    override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
        if (rowNum != rowIdx) return
        val col = SheetScanner.colIndexOf((cellReference ?: return).takeWhile { it.isLetter() }) ?: return
        val v = formattedValue?.trim().orEmpty()
        if (v.isNotEmpty()) cells[col] = v
    }

    override fun endRow(rowNum: Int) {
        if (rowNum >= rowIdx) throw StopParsing()
    }

    override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}
}

/** Buffers non-empty rows at/after [fromIdx] (0-based), verbatim, up to [limit] rows. */
private class RowWindowCollector(private val fromIdx: Int, private val limit: Int) : XSSFSheetXMLHandler.SheetContentsHandler {
    val rows = ArrayList<Pair<Int, java.util.TreeMap<Int, String>>>()
    private var rowNum = -1
    private var cur = java.util.TreeMap<Int, String>()

    override fun startRow(rowNum: Int) {
        this.rowNum = rowNum
        cur = java.util.TreeMap()
    }

    override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
        val col = SheetScanner.colIndexOf((cellReference ?: return).takeWhile { it.isLetter() }) ?: return
        val v = formattedValue?.trim().orEmpty()
        if (v.isNotEmpty()) cur[col] = v
    }

    override fun endRow(rowNum: Int) {
        if (rowNum >= fromIdx && cur.isNotEmpty()) {
            rows.add(rowNum to cur)
            if (rows.size >= limit) throw StopParsing()
        }
    }

    override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}
}

/**
 * [ColumnCollector] restricted to rows matching [clauses] (columns keyed by LETTER — check_ref's
 * coordinate system). Rows are assembled cell-by-cell so a missing/blank condition cell reads as ""
 * — identical semantics to a ref's `when` at index time. Rows with no cells at all count in neither
 * evaluated nor skipped.
 */
private class ConditionalColumnCollector(
    private val colIdx: Int,
    private val fromIdx: Int,
    private val maxDistinct: Int,
    private val clauses: List<WhenClause>,
) : XSSFSheetXMLHandler.SheetContentsHandler {
    val distinct = LinkedHashSet<String>()
    var truncated = false
    var rowsEvaluated = 0
    var rowsSkipped = 0
    private val condCols: Map<Int, String> =
        clauses.mapNotNull { c -> SheetScanner.colIndexOf(c.col)?.let { it to c.col } }.toMap()
    private var rowNum = -1
    private var sawAny = false
    private var fromValue = ""
    private val condValues = HashMap<String, String>()

    override fun startRow(rowNum: Int) {
        this.rowNum = rowNum
        sawAny = false
        fromValue = ""
        condValues.clear()
    }

    override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
        if (rowNum < fromIdx) return
        val col = SheetScanner.colIndexOf((cellReference ?: return).takeWhile { it.isLetter() }) ?: return
        val v = formattedValue?.trim().orEmpty()
        if (v.isEmpty()) return
        sawAny = true
        if (col == colIdx) fromValue = v
        condCols[col]?.let { condValues[it] = v }
    }

    override fun endRow(rowNum: Int) {
        if (rowNum < fromIdx || !sawAny) return
        if (clauses.all { it.matches(condValues) }) {
            rowsEvaluated++
            if (fromValue.isNotEmpty()) {
                if (distinct.size < maxDistinct) distinct.add(fromValue) else if (fromValue !in distinct) truncated = true
            }
        } else {
            rowsSkipped++
        }
    }

    override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}
}

/** Collects one column's distinct values (capped) + non-empty count from [fromIdx] (0-based) down. */
private class ColumnCollector(private val colIdx: Int, private val fromIdx: Int, private val maxDistinct: Int) : XSSFSheetXMLHandler.SheetContentsHandler {
    val distinct = LinkedHashSet<String>()
    var nonEmpty = 0
    var truncated = false
    private var rowNum = -1

    override fun startRow(rowNum: Int) {
        this.rowNum = rowNum
    }

    override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
        if (rowNum < fromIdx) return
        val col = SheetScanner.colIndexOf((cellReference ?: return).takeWhile { it.isLetter() }) ?: return
        if (col != colIdx) return
        val v = formattedValue?.trim().orEmpty()
        if (v.isEmpty()) return
        nonEmpty++
        if (distinct.size < maxDistinct) distinct.add(v) else if (v !in distinct) truncated = true
    }

    override fun endRow(rowNum: Int) {}
    override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}
}
