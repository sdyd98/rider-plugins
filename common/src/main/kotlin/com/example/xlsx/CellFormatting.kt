package com.example.xlsx

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FormulaError

/**
 * Display text for a formula cell's **cached result** (like Excel's normal view). Shared by the
 * `.xls` open path ([XlsWorkbookReader]) and the live-recalc path ([XlsxFileEditor]) so the two
 * never diverge.
 */
fun formatCachedFormulaResult(cell: Cell, formatter: DataFormatter): String = when (cell.cachedFormulaResultType) {
    CellType.NUMERIC -> formatter.formatRawCellContents(
        cell.numericCellValue, cell.cellStyle.dataFormat.toInt(), cell.cellStyle.dataFormatString,
    )
    CellType.STRING -> cell.stringCellValue
    CellType.BOOLEAN -> cell.booleanCellValue.toString().uppercase()
    CellType.ERROR -> FormulaError.forInt(cell.errorCellValue).string
    else -> ""
}
