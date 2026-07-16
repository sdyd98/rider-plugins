package com.example.xlsx

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.DataFormatter
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

    /** (root-relative file, sheet name) for every sheet under [baseDir] — reads only sheet NAMES,
     *  never row data (sheet bodies are not decompressed for .xlsx). */
    fun listSheets(baseDir: File): List<Pair<String, String>> = withPoiClassLoader {
        val out = ArrayList<Pair<String, String>>()
        spreadsheetFilesUnder(baseDir).forEach { file ->
            val rel = file.relativeTo(baseDir).invariantSeparatorsPath
            runCatching {
                if (file.name.endsWith(".xls", true)) {
                    WorkbookFactory.create(file, null, true).use { wb ->
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
            }
        }
        out
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
    )

    /** Value-overlap NUMBERS between a hypothesized reference column and a target column — pure set
     *  arithmetic on coordinates the AI supplies; no thresholds, no proposal. [split] (optional) splits
     *  multi-value cells; [ignore] holds the AI-declared no-reference placeholders to drop. */
    fun overlap(
        baseDir: File,
        fromFile: String, fromSheet: String, fromColumn: String, fromStartRow: Int, split: String?,
        toFile: String, toSheet: String, toColumn: String, toStartRow: Int,
        ignore: Set<String>,
    ): Overlap? {
        val toCol = colIndexOf(toColumn) ?: return null
        val fromCol = colIndexOf(fromColumn) ?: return null
        val target = ColumnCollector(toCol, toStartRow - 1, MAX_ID_SET)
        if (!parseOneSheet(baseDir, toFile, toSheet, target)) return null
        val source = ColumnCollector(fromCol, fromStartRow - 1, MAX_DISTINCT)
        if (!parseOneSheet(baseDir, fromFile, fromSheet, source)) return null
        val tokens = source.distinct
            .flatMap { v -> if (split.isNullOrEmpty()) listOf(v) else v.split(split).map(String::trim) }
            .filter { it.isNotEmpty() && it !in ignore }
            .toSet()
        val missing = tokens.filter { it !in target.distinct }
        return Overlap(tokens.size, tokens.size - missing.size, missing.take(10), target.distinct.size, target.truncated, source.truncated)
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

    /** Stream ONE sheet of [relFile] into [handler]; false when the file/sheet is missing/unreadable. */
    private fun parseOneSheet(baseDir: File, relFile: String, sheet: String, handler: XSSFSheetXMLHandler.SheetContentsHandler): Boolean = withPoiClassLoader {
        val file = File(baseDir, relFile)
        if (!file.isFile) return@withPoiClassLoader false
        runCatching {
            if (file.name.endsWith(".xls", true)) {
                WorkbookFactory.create(file, null, true).use { wb ->
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
        }.getOrDefault(false)
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
