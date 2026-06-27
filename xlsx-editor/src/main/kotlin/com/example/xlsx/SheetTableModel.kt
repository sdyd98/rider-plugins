package com.example.xlsx

import org.apache.poi.ss.util.CellReference
import javax.swing.table.AbstractTableModel

/**
 * Compact, **read-only** Swing model for one sheet: display strings + a formula map (cell position →
 * formula text incl. '='). The grid is a viewer — cells are not editable. Rows are streamed in via
 * [appendBatch]; the formula map is only used to show "this is a formula" in the renderer/status bar.
 */
class SheetTableModel(val sheetName: String) : AbstractTableModel() {

    private val rows = ArrayList<Array<String?>>()
    private var maxCol = 1
    private val formulas = HashMap<Long, String>()

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = maxOf(maxCol, MIN_COLS) + EXTRA_COLS
    override fun getColumnName(column: Int): String = CellReference.convertNumToColString(column)
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        if (rowIndex < 0 || rowIndex >= rows.size) return ""
        val row = rows[rowIndex]
        return if (columnIndex < row.size) row[columnIndex] ?: "" else ""
    }

    /** Append a streamed batch of rows. Must run on the EDT. */
    fun appendBatch(batch: List<Array<String?>>) {
        if (batch.isEmpty()) return
        val prevColumns = columnCount
        val start = rows.size
        rows.addAll(batch)
        for (row in batch) if (row.size > maxCol) maxCol = row.size
        if (columnCount != prevColumns) fireTableStructureChanged() else fireTableRowsInserted(start, rows.size - 1)
    }

    fun loadedRowCount(): Int = rows.size

    // ---- Formulas (display only) ----

    fun setFormulas(map: Map<Long, String>) {
        formulas.clear()
        formulas.putAll(map)
    }

    fun isFormula(rowIndex: Int, columnIndex: Int): Boolean = formulas.containsKey(pack(rowIndex, columnIndex))
    fun formulaText(rowIndex: Int, columnIndex: Int): String? = formulas[pack(rowIndex, columnIndex)]
    fun hasFormulas(): Boolean = formulas.isNotEmpty()

    companion object {
        private const val EXTRA_COLS = 5
        private const val MIN_COLS = 8

        fun pack(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)
        fun rowOf(packed: Long): Int = (packed ushr 32).toInt()
        fun colOf(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()
    }
}
