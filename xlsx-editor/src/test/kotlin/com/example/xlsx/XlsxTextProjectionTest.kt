package com.example.xlsx

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * The VCS-diff text projection must be deterministic and line-stable: the same workbook always
 * projects to the same text, and editing ONE cell changes exactly ONE line — that's what makes
 * the IDE's text diff useful on binary spreadsheets.
 */
class XlsxTextProjectionTest {

    private fun xlsxBytes(cellB3: String): ByteArray = withPoiClassLoader {
        val wb = SXSSFWorkbook()
        val sheet = wb.createSheet("데이터")
        for (r in 0 until 5) {
            val row = sheet.createRow(r)
            row.createCell(0).setCellValue(r.toDouble())
            row.createCell(1).setCellValue(if (r == 2) cellB3 else "값$r")
            row.createCell(2).setCellValue("탭\t과 줄\n바꿈") // must not break line stability
        }
        val out = ByteArrayOutputStream()
        wb.write(out)
        wb.dispose(); wb.close()
        out.toByteArray()
    }

    @Test
    fun `deterministic, and one changed cell means one changed line`() {
        val a1 = XlsxTextProjection.toText(xlsxBytes("원본"), isLegacyXls = false).toString()
        val a2 = XlsxTextProjection.toText(xlsxBytes("원본"), isLegacyXls = false).toString()
        val b = XlsxTextProjection.toText(xlsxBytes("수정됨"), isLegacyXls = false).toString()

        assertEquals(a1, a2) // deterministic
        assertTrue(a1.startsWith("===== 시트: 데이터 =====\n"))

        val diff = a1.lines().zip(b.lines()).filter { (x, y) -> x != y }
        assertEquals(1, diff.size) // exactly the edited row differs
        assertTrue(diff[0].first.contains("원본") && diff[0].second.contains("수정됨"))
        assertTrue(a1.lines().none { it.contains("탭\t과") }) // cell-internal tab sanitized
    }

    @Test
    fun `legacy xls projects the same shape`() {
        val bytes = withPoiClassLoader {
            val wb = HSSFWorkbook()
            val s = wb.createSheet("Items")
            s.createRow(0).createCell(0).setCellValue("ID")
            s.createRow(1).apply {
                createCell(0).setCellValue(7.0)
                createCell(1).setCellValue("검")
            }
            val out = ByteArrayOutputStream()
            wb.write(out); wb.close()
            out.toByteArray()
        }
        val text = XlsxTextProjection.toText(bytes, isLegacyXls = true).toString()
        assertTrue(text.startsWith("===== 시트: Items =====\n"))
        assertTrue(text.contains("7\t검"))
    }
}
