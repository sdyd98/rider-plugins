package com.example.xlsx

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream

/**
 * Reader for legacy `.xls` (BIFF) files. There is no clean SAX streaming for HSSF, but `.xls` is
 * hard-capped at 65,536 rows × 256 cols, so a full usermodel load is fine. `WorkbookFactory`
 * auto-detects the format; this is used only for `.xls` (`.xlsx` uses the streaming reader).
 *
 * Split into [open] (build the workbook — the big, unavoidable cost) + [sheetNames] + [renderSheet]
 * so the caller can show the tabs right after the build and render each sheet's cells incrementally
 * (active sheet first) instead of rendering all sheets before anything appears.
 */
object XlsWorkbookReader {

    class SheetData(val name: String, val rows: List<Array<String?>>, val formulas: Map<Long, String>)

    /** Build the full workbook in memory (HSSF has no streaming). Caller must close it when done. */
    fun open(bytes: ByteArray): Workbook = withPoiClassLoader { WorkbookFactory.create(ByteArrayInputStream(bytes)) }

    fun sheetNames(wb: Workbook): List<String> = (0 until wb.numberOfSheets).map { wb.getSheetName(it) }

    /** Render one sheet's cells to display strings and collect its formula positions. */
    fun renderSheet(wb: Workbook, index: Int): SheetData = withPoiClassLoader {
        // Same tuning as the .xlsx path: fast General formatting (byte-identical to DataFormatter,
        // see FastGeneralFormatterTest) + repeated-display-string dedup.
        val formatter = FastGeneralFormatter()
        val pool = StringPool()
        val sheet = wb.getSheetAt(index)
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
                arr[c] = pool.dedup(render(cell, formatter))
                if (cell.cellType == CellType.FORMULA) formulas[SheetTableModel.pack(r, c)] = "=" + cell.cellFormula
            }
            rows.add(arr)
        }
        SheetData(sheet.sheetName, rows, formulas)
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
