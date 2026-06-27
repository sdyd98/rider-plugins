package com.example.xlsx

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream

/**
 * Reader for legacy `.xls` (BIFF) files. There is no clean SAX streaming for HSSF, but `.xls` is
 * hard-capped at 65,536 rows × 256 cols, so a full usermodel load is fine. `WorkbookFactory`
 * auto-detects the format; this is used only for `.xls` (`.xlsx` uses the streaming reader).
 */
object XlsWorkbookReader {

    class SheetData(val name: String, val rows: List<Array<String?>>, val formulas: Map<Long, String>)

    fun read(bytes: ByteArray): List<SheetData> = withPoiClassLoader {
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { wb ->
            val formatter = DataFormatter()
            (0 until wb.numberOfSheets).map { sheetIndex ->
                val sheet = wb.getSheetAt(sheetIndex)
                val rows = ArrayList<Array<String?>>(sheet.lastRowNum + 1)
                val formulas = HashMap<Long, String>()
                for (r in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(r)
                    val lastCell = row?.lastCellNum?.toInt() ?: 0
                    if (row == null || lastCell <= 0) {
                        rows.add(EMPTY)
                        continue
                    }
                    val arr = arrayOfNulls<String>(lastCell)
                    for (c in 0 until lastCell) {
                        val cell = row.getCell(c) ?: continue
                        arr[c] = render(cell, formatter)
                        if (cell.cellType == CellType.FORMULA) formulas[SheetTableModel.pack(r, c)] = "=" + cell.cellFormula
                    }
                    rows.add(arr)
                }
                SheetData(sheet.sheetName, rows, formulas)
            }
        }
    }

    /**
     * Display text for a cell. For a formula cell, show its **cached result** (like Excel and like
     * our .xlsx streaming path) rather than the formula text that `formatCellValue(cell)` returns
     * when given no evaluator.
     */
    private fun render(cell: Cell, formatter: DataFormatter): String {
        if (cell.cellType != CellType.FORMULA) return formatter.formatCellValue(cell)
        return formatCachedFormulaResult(cell, formatter)
    }

    private val EMPTY = arrayOfNulls<String>(0)
}
