package com.example.xlsx

import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream

/**
 * Lightweight SAX pass over a sheet's XML that records which cells are formulas (packed row/col →
 * formula text). Far cheaper than the value parse (no shared strings / styles / formatting), and
 * run in parallel with it, so marking formulas adds no real open latency. Used for `.xlsx`;
 * `.xls` gets formula info directly from the usermodel read.
 */
object FormulaScanner {

    fun scan(xml: ByteArray): Map<Long, String> = withPoiClassLoader {
        val handler = Handler()
        val parser = XMLHelper.newXMLReader()
        parser.contentHandler = handler
        parser.parse(InputSource(ByteArrayInputStream(xml)))
        handler.result
    }

    private class Handler : DefaultHandler() {
        val result = HashMap<Long, String>()
        private var row = -1
        private var col = -1
        private var inFormula = false
        private var hasFormula = false
        private val formula = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "c" -> {
                    val ref = attributes.getValue("r")
                    if (ref != null) {
                        val cr = CellReference(ref)
                        row = cr.row
                        col = cr.col.toInt()
                    } else {
                        row = -1; col = -1
                    }
                    hasFormula = false
                    formula.setLength(0)
                }
                "f" -> inFormula = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inFormula) formula.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "f" -> { inFormula = false; hasFormula = true }
                "c" -> if (hasFormula && row >= 0 && col >= 0) {
                    result[SheetTableModel.pack(row, col)] = "=" + formula.toString()
                }
            }
        }
    }
}
