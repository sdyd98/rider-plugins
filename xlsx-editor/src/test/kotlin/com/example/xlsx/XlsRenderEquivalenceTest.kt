package com.example.xlsx

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.DataFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * XlsWorkbookReader.renderSheet routes numeric cells through formatRawCellContents (for the
 * FastGeneralFormatter shortcut) instead of POI's formatCellValue convenience path — this pins
 * that both paths produce identical display text across the value/format shapes we care about.
 */
class XlsRenderEquivalenceTest {

    @Test
    fun `renderSheet output matches formatCellValue for every cell`() = withPoiClassLoader {
        val wb = HSSFWorkbook()
        val sheet = wb.createSheet("S")
        val dateStyle = wb.createCellStyle().apply { dataFormat = 14 } // m/d/yy builtin
        val moneyStyle = wb.createCellStyle().apply { dataFormat = wb.createDataFormat().getFormat("#,##0.00") }
        val pctStyle = wb.createCellStyle().apply { dataFormat = 9 } // 0%

        val numbers = listOf(
            0.0, 1.0, -1.0, 42.0, 12345.0, 99_999_999_999.0, 100_000_000_000.0,
            0.5, 123.45, 376.99111842, -0.25, 1.0 / 3.0, 1e-7, 1e15, 86_400.0,
        )
        var r = 0
        for (v in numbers) {
            val row = sheet.createRow(r++)
            row.createCell(0).setCellValue(v) // General
            row.createCell(1).apply { setCellValue(v); cellStyle = moneyStyle }
            row.createCell(2).apply { setCellValue(if (v in 1.0..60_000.0) v else 1234.5); cellStyle = dateStyle }
            row.createCell(3).apply { setCellValue(v / 100); cellStyle = pctStyle }
        }
        sheet.createRow(r++).apply {
            createCell(0).setCellValue("문자열 값")
            createCell(1).setCellValue("TRUE")
            createCell(2).setCellValue(true)
            createCell(3).setCellValue(false)
        }

        val rendered = XlsWorkbookReader.renderSheet(wb, 0)
        val plain = DataFormatter()
        for (rowIdx in 0 until r) {
            val row = sheet.getRow(rowIdx) ?: continue
            for (c in 0 until row.lastCellNum) {
                val cell = row.getCell(c) ?: continue
                assertEquals(
                    plain.formatCellValue(cell),
                    rendered.rows[rowIdx][c],
                    "row=$rowIdx col=$c",
                )
            }
        }
        wb.close()
    }
}
