package com.example.xlsx

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler
import org.apache.poi.xssf.model.SharedStrings
import org.apache.poi.xssf.model.StylesTable
import org.apache.poi.xssf.usermodel.XSSFComment
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream

/**
 * Streaming (SAX event-model) reader for `.xlsx`. Reads cells as already-formatted display
 * strings without building POI's heavyweight per-cell object graph, so memory stays low and
 * rows can be delivered to the UI incrementally. Shared strings + styles are loaded once and
 * are immutable afterward, so the per-sheet XML can be parsed on independent threads.
 */
object XlsxStreamingReader {

    /**
     * Sheet names + the (immutable) shared strings & styles, with the package still **open** so each
     * sheet's XML can be read lazily — see [readSheets]. This lets the tabs show before the (slow)
     * decompression of every sheet's XML.
     */
    class Source(
        val names: List<String>,
        val sst: SharedStrings,
        val styles: StylesTable,
        private val pkg: OPCPackage,
    ) {
        /**
         * Read each sheet's XML in document order (sheet 0 — the initially-shown tab — first),
         * invoking [onSheet] per sheet, then close the package. Run on a single background thread
         * (the package/iterator isn't safe for concurrent reads); callers parse each XML in parallel.
         */
        fun readSheets(onSheet: (index: Int, xml: ByteArray) -> Unit) = withPoiClassLoader {
            try {
                val sheets = XSSFReader(pkg).sheetsData as XSSFReader.SheetIterator
                var i = 0
                while (sheets.hasNext()) {
                    val xml = sheets.next().use { it.readBytes() }
                    onSheet(i, xml)
                    i++
                }
            } finally {
                try {
                    pkg.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    /**
     * Opens the package **from the file** (random access — ~67 ms vs ~1.5 s from a byte stream) and
     * reads only the shared strings, styles, and sheet **names**, NOT each sheet's XML. First paint
     * then needs only ~0.2 s; sheet XML is decompressed lazily via [Source.readSheets].
     */
    fun open(file: java.io.File): Source = withPoiClassLoader { openPackage(OPCPackage.open(file, PackageAccess.READ)) }

    /** Fallback for when the file isn't a plain local path: open from the in-memory bytes. */
    fun open(bytes: ByteArray): Source = withPoiClassLoader { openPackage(OPCPackage.open(ByteArrayInputStream(bytes))) }

    private fun openPackage(pkg: OPCPackage): Source {
        val sst = ReadOnlySharedStringsTable(pkg)
        val reader = XSSFReader(pkg)
        val styles = reader.stylesTable
        val names = ArrayList<String>()
        val sheets = reader.sheetsData as XSSFReader.SheetIterator
        while (sheets.hasNext()) {
            sheets.next().close() // get the name without decompressing the sheet body
            names.add(sheets.sheetName)
        }
        return Source(names, sst, styles, pkg)
    }

    /** SAX-parses one sheet's XML, delivering rows (as display strings) in [batchSize] batches. */
    fun parseSheet(
        xml: ByteArray,
        sst: SharedStrings,
        styles: StylesTable,
        batchSize: Int,
        onBatch: (List<Array<String?>>) -> Unit,
    ) = withPoiClassLoader {
        val collector = BatchingCollector(batchSize, onBatch)
        val parser = XMLHelper.newXMLReader()
        parser.contentHandler = XSSFSheetXMLHandler(styles, null, sst, collector, FastGeneralFormatter(), false)
        parser.parse(InputSource(ByteArrayInputStream(xml)))
        collector.flush()
    }
}

/**
 * [DataFormatter] with a fast path for the by-far most common cell shape in game data: an INTEGRAL
 * number in the General format. POI's General path rounds through BigDecimal per cell — measured at
 * ~50% of the whole sheet parse on a 30 MB workbook — while an integral double in Excel's General
 * simply renders as its plain integer digits, which `Long.toString` produces identically. Everything
 * else (fractions, dates, explicit formats) still goes through POI, so display stays byte-identical.
 */
internal class FastGeneralFormatter : DataFormatter() {
    override fun formatRawCellContents(value: Double, formatIndex: Int, formatString: String?): String {
        if (formatString == "General" || formatString == "@" || formatIndex == 0) {
            val asLong = value.toLong()
            // Integral (bit-exact, so -0.0 falls through to POI), and at most 11 digits — the range
            // Excel's General renders as plain digits (12+ digits switch to scientific, POI's job).
            if (asLong.toDouble().toRawBits() == value.toRawBits() &&
                asLong > -100_000_000_000L && asLong < 100_000_000_000L
            ) {
                return asLong.toString()
            }
            // Fractional fast path: when the double's SHORTEST decimal representation is plain
            // (no exponent) and within the 10-significant-digit budget POI's General rounds to
            // (measured: 376.99111842 → "376.9911184"), the rounding cannot change it — POI prints
            // exactly that representation. Counting every digit char (including non-significant
            // leading zeros) only narrows the fast path. -0.0 must delegate (POI prints "0").
            val s = value.toString()
            if (value != 0.0 && s.indexOf('E') < 0) {
                var digits = 0
                for (ch in s) if (ch in '0'..'9') digits++
                if (digits <= 10) return s
            }
        }
        return super.formatRawCellContents(value, formatIndex, formatString)
    }
}

/**
 * Accumulates streamed cells into row arrays and flushes them in batches. Pads gaps so the
 * emitted row index always equals the sheet row index.
 */
private class BatchingCollector(
    private val batchSize: Int,
    private val onBatch: (List<Array<String?>>) -> Unit,
) : SheetContentsHandler {

    private val buffer = ArrayList<Array<String?>>(batchSize)
    private var cur = arrayOfNulls<String>(8)
    private var curMax = 0
    private var nextExpected = 0

    // Dedup repeated display strings — see StringPool (~4% parse time for a large heap/GC saving).
    private val pool = StringPool()

    override fun startRow(rowNum: Int) {
        while (nextExpected < rowNum) {
            emit(EMPTY)
            nextExpected++
        }
        cur = arrayOfNulls(8)
        curMax = 0
    }

    override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
        val col = if (cellReference == null) curMax else CellReference(cellReference).col.toInt()
        if (col >= cur.size) cur = cur.copyOf(maxOf(col + 1, cur.size * 2))
        cur[col] = pool.dedup(formattedValue)
        if (col + 1 > curMax) curMax = col + 1
    }

    override fun endRow(rowNum: Int) {
        emit(if (curMax == 0) EMPTY else cur.copyOf(curMax))
        nextExpected = rowNum + 1
    }

    override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}

    private fun emit(row: Array<String?>) {
        buffer.add(row)
        if (buffer.size >= batchSize) {
            onBatch(ArrayList(buffer))
            buffer.clear()
        }
    }

    fun flush() {
        if (buffer.isNotEmpty()) {
            onBatch(ArrayList(buffer))
            buffer.clear()
        }
    }

    companion object {
        private val EMPTY = arrayOfNulls<String>(0)
    }
}
