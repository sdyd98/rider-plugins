package com.example.xlsx

import org.apache.poi.openxml4j.opc.OPCPackage
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

    /** Everything needed to parse sheets, fully detached from the (already closed) package. */
    class Source(
        val names: List<String>,
        val sheetXml: List<ByteArray>,
        val sst: SharedStrings,
        val styles: StylesTable,
    )

    /** Opens the package, loads shared strings + styles, and reads each sheet's raw XML bytes. */
    fun open(bytes: ByteArray): Source = withPoiClassLoader {
        OPCPackage.open(ByteArrayInputStream(bytes)).use { pkg ->
            val sst = ReadOnlySharedStringsTable(pkg)
            val reader = XSSFReader(pkg)
            val styles = reader.stylesTable
            val names = ArrayList<String>()
            val xmls = ArrayList<ByteArray>()
            val sheets = reader.sheetsData as XSSFReader.SheetIterator
            while (sheets.hasNext()) {
                val input = sheets.next()
                val name = sheets.sheetName
                input.use { xmls.add(it.readBytes()) }
                names.add(name)
            }
            Source(names, xmls, sst, styles)
        }
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
        parser.contentHandler = XSSFSheetXMLHandler(styles, null, sst, collector, DataFormatter(), false)
        parser.parse(InputSource(ByteArrayInputStream(xml)))
        collector.flush()
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
        cur[col] = formattedValue
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
