package com.example.xlsx

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * The grid diff's model: sheet loading via the same readers as the viewer, and row pairing from
 * aligner ranges — modified pairs carry exact changed-column sets, unpaired rows become
 * inserts/deletes with placeholders on the other side, sheet-level add/remove is labeled.
 */
class XlsxDiffModelTest {

    /** Deterministic aligner for tests: rows are compared 1:1 by key, trailing extras are one range. */
    private val naiveAligner = XlsxDiffModel.RowAligner { left, right ->
        val ranges = ArrayList<IntArray>()
        var i = 0
        while (i < minOf(left.size, right.size)) {
            if (left[i] != right[i]) {
                val s = i
                while (i < minOf(left.size, right.size) && left[i] != right[i]) i++
                ranges.add(intArrayOf(s, i, s, i))
            } else i++
        }
        if (left.size != right.size) ranges.add(intArrayOf(minOf(left.size, right.size), left.size, minOf(left.size, right.size), right.size))
        ranges
    }

    private fun rows(vararg r: Array<String?>) = r.toList()

    @Test
    fun `modified pair carries exact changed columns`() {
        val left = mapOf("S" to rows(arrayOf<String?>("1", "sword", "10"), arrayOf<String?>("2", "shield", "5")))
        val right = mapOf("S" to rows(arrayOf<String?>("1", "sword", "12"), arrayOf<String?>("2", "shield", "5")))

        val sheet = XlsxDiffModel.diff(left, right, naiveAligner).single()
        assertEquals(1, sheet.modified); assertEquals(0, sheet.inserted); assertEquals(0, sheet.deleted)
        val changed = sheet.rows.single { it.kind == XlsxDiffModel.Kind.MODIFIED }
        assertEquals(listOf(2), changed.changedCols.toList()) // only the third column differs
        assertEquals(0, changed.leftIndex); assertEquals(0, changed.rightIndex)
    }

    @Test
    fun `unpaired rows become inserts with placeholders and original indices survive`() {
        val left = mapOf("S" to rows(arrayOf<String?>("a")))
        val right = mapOf("S" to rows(arrayOf<String?>("a"), arrayOf<String?>("b"), arrayOf<String?>("c")))

        val sheet = XlsxDiffModel.diff(left, right, naiveAligner).single()
        assertEquals(2, sheet.inserted)
        val inserted = sheet.rows.filter { it.kind == XlsxDiffModel.Kind.INSERTED }
        assertTrue(inserted.all { it.left == null && it.leftIndex == null }) // placeholder side
        assertEquals(listOf(1, 2), inserted.map { it.rightIndex })
    }

    @Test
    fun `sheet existing on one side only is labeled`() {
        val left = mapOf("Old" to rows(arrayOf<String?>("x")))
        val right = mapOf("New" to rows(arrayOf<String?>("y")))

        val sheets = XlsxDiffModel.diff(left, right, naiveAligner)
        assertEquals(listOf("Old", "New"), sheets.map { it.name })
        assertEquals("좌측에만 있음", sheets[0].onlySide)
        assertEquals("우측에만 있음", sheets[1].onlySide)
        assertTrue(sheets.all { it.changed })
    }

    @Test
    fun `loadSheets round-trips a real workbook and diff sees the one edited cell`() {
        fun workbook(cellB2: String): ByteArray = withPoiClassLoader {
            val wb = SXSSFWorkbook()
            val s = wb.createSheet("Data")
            s.createRow(0).apply { createCell(0).setCellValue("Id"); createCell(1).setCellValue("Name") }
            s.createRow(1).apply { createCell(0).setCellValue(1.0); createCell(1).setCellValue(cellB2) }
            val out = ByteArrayOutputStream(); wb.write(out); wb.dispose(); wb.close(); out.toByteArray()
        }

        val left = XlsxDiffModel.loadSheets(workbook("원본"), isLegacyXls = false)
        val right = XlsxDiffModel.loadSheets(workbook("수정"), isLegacyXls = false)
        val sheet = XlsxDiffModel.diff(left, right, naiveAligner).single()

        assertEquals("Data", sheet.name)
        assertEquals(1, sheet.modified)
        val row = sheet.rows.single { it.kind == XlsxDiffModel.Kind.MODIFIED }
        assertEquals("원본", row.left!![row.changedCols[0]])
        assertEquals("수정", row.right!![row.changedCols[0]])
        assertNull(sheet.onlySide)
    }

    @Test
    fun `column letters`() {
        assertEquals("A", XlsxDiffModel.columnLetter(0))
        assertEquals("Z", XlsxDiffModel.columnLetter(25))
        assertEquals("AA", XlsxDiffModel.columnLetter(26))
        assertEquals("AB", XlsxDiffModel.columnLetter(27))
    }
}
