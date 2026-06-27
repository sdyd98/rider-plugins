package com.example.xlsx

import org.apache.poi.ss.util.CellReference
import javax.swing.table.AbstractTableModel

/** An ordered, replayable user edit. Replayed onto the workbook (from the original) on save. */
sealed interface EditOp {
    data class SetCell(val row: Int, val col: Int, val value: String) : EditOp
    data class SetFormula(val row: Int, val col: Int, val formula: String) : EditOp // without leading '='
    data class InsertRow(val row: Int) : EditOp
    data class DeleteRow(val row: Int) : EditOp
}

/**
 * Compact Swing model for one sheet: display strings + an ordered edit log ([EditOp]) + a formula
 * map (position → formula text incl. '=') + an [undo] stack.
 *
 * Public mutators ([setValueAt], [insertRow], [deleteRow], [insertRowWithData]) push an undo entry
 * and delegate to internal `*Internal` methods; [undo] replays the inverse via those internals
 * (which record ops + notify the live engine but do NOT push undo). Structural events are fired
 * BEFORE notifying the live engine so the row sorter never desyncs.
 */
class SheetTableModel(
    val sheetName: String,
    private val onEdit: () -> Unit,
) : AbstractTableModel() {

    private val rows = ArrayList<Array<String?>>()
    private var maxCol = 1
    private val ops = ArrayList<EditOp>()
    private val formulas = HashMap<Long, String>()
    private val undoStack = ArrayDeque<() -> Unit>()

    var liveHandler: ((row: Int, col: Int) -> Unit)? = null
    var liveStructuralHandler: ((EditOp) -> Unit)? = null

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = maxOf(maxCol, MIN_COLS) + EXTRA_COLS
    override fun getColumnName(column: Int): String = CellReference.convertNumToColString(column)
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        if (rowIndex < 0 || rowIndex >= rows.size) return ""
        val row = rows[rowIndex]
        return if (columnIndex < row.size) row[columnIndex] ?: "" else ""
    }

    private fun cellDisplay(r: Int, c: Int): String {
        if (r < 0 || r >= rows.size) return ""
        val row = rows[r]
        return if (c < row.size) row[c] ?: "" else ""
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (rowIndex < 0 || rowIndex >= rows.size || columnIndex < 0) return
        val text = aValue?.toString() ?: ""
        val oldDisplay = cellDisplay(rowIndex, columnIndex)
        val oldFormula = formulas[pack(rowIndex, columnIndex)]
        pushUndo { setCellInternal(rowIndex, columnIndex, oldDisplay, oldFormula) }
        if (text.length > 1 && text.startsWith("=")) {
            setCellInternal(rowIndex, columnIndex, text, text)
        } else {
            setCellInternal(rowIndex, columnIndex, text, null)
        }
    }

    /** Push an evaluated display value (no op, no formula change, no undo). Used by the live engine. */
    fun setDisplayValue(rowIndex: Int, columnIndex: Int, value: String) {
        if (rowIndex < 0 || rowIndex >= rows.size || columnIndex < 0) return
        ensureColumns(rowIndex, columnIndex)
        rows[rowIndex][columnIndex] = value
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun insertRow(at: Int) {
        val idx = at.coerceIn(0, rows.size)
        pushUndo { deleteRowInternal(idx) }
        insertRowInternal(idx)
    }

    /** Insert a row pre-filled with [values] (vim paste). One undo entry removes the whole row. */
    fun insertRowWithData(at: Int, values: Array<String?>) {
        val idx = at.coerceIn(0, rows.size)
        pushUndo { deleteRowInternal(idx) }
        insertRowInternal(idx)
        for (c in values.indices) {
            val v = values[c] ?: continue
            if (v.isEmpty()) continue
            if (v.length > 1 && v.startsWith("=")) setCellInternal(idx, c, v, v) else setCellInternal(idx, c, v, null)
        }
    }

    fun deleteRow(at: Int) {
        if (at < 0 || at >= rows.size) return
        val removedData = rows[at].copyOf()
        val removedFormulas = HashMap<Int, String>()
        for ((k, f) in formulas) if (rowOf(k) == at) removedFormulas[colOf(k)] = f
        pushUndo {
            insertRowInternal(at)
            for (c in removedData.indices) {
                val v = removedData[c]
                val f = removedFormulas[c]
                if (!v.isNullOrEmpty() || f != null) setCellInternal(at, c, v ?: "", f)
            }
        }
        deleteRowInternal(at)
    }

    /** Undo the most recent edit. Returns false if nothing to undo. EDT only. */
    fun undo(): Boolean {
        val action = undoStack.removeLastOrNull() ?: return false
        action()
        return true
    }

    // ---- internal apply (record op + notify live; no undo push) ----

    private fun setCellInternal(r: Int, c: Int, display: String, formula: String?) {
        ensureColumns(r, c)
        rows[r][c] = display
        if (formula != null) {
            formulas[pack(r, c)] = formula
            ops.add(EditOp.SetFormula(r, c, formula.removePrefix("=")))
        } else {
            formulas.remove(pack(r, c))
            ops.add(EditOp.SetCell(r, c, display))
        }
        onEdit()
        liveHandler?.invoke(r, c)
        fireTableCellUpdated(r, c)
    }

    private fun insertRowInternal(at: Int) {
        val idx = at.coerceIn(0, rows.size)
        rows.add(idx, arrayOfNulls(0))
        ops.add(EditOp.InsertRow(idx))
        shiftFormulas(idx, +1)
        onEdit()
        fireTableRowsInserted(idx, idx)
        liveStructuralHandler?.invoke(EditOp.InsertRow(idx))
    }

    private fun deleteRowInternal(at: Int) {
        if (at < 0 || at >= rows.size) return
        rows.removeAt(at)
        ops.add(EditOp.DeleteRow(at))
        shiftFormulas(at, -1)
        onEdit()
        fireTableRowsDeleted(at, at)
        liveStructuralHandler?.invoke(EditOp.DeleteRow(at))
    }

    private fun pushUndo(action: () -> Unit) {
        undoStack.addLast(action)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
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

    /** A defensive copy of the current display rows — used by the fast structural-save rebuild. EDT only. */
    fun snapshotRows(): List<Array<String?>> = rows.map { it.copyOf() }

    fun hasEdits(): Boolean = ops.isNotEmpty()

    fun drainOps(): List<EditOp> {
        val copy = ArrayList(ops)
        ops.clear()
        return copy
    }

    // ---- Formulas ----

    fun setFormulas(map: Map<Long, String>) {
        formulas.clear()
        formulas.putAll(map)
    }

    fun isFormula(rowIndex: Int, columnIndex: Int): Boolean = formulas.containsKey(pack(rowIndex, columnIndex))
    fun formulaText(rowIndex: Int, columnIndex: Int): String? = formulas[pack(rowIndex, columnIndex)]
    fun formulaKeys(): List<Long> = ArrayList(formulas.keys)
    fun hasFormulas(): Boolean = formulas.isNotEmpty()

    private fun shiftFormulas(at: Int, delta: Int) {
        if (formulas.isEmpty()) return
        val updated = HashMap<Long, String>(formulas.size)
        for ((key, f) in formulas) {
            val r = rowOf(key)
            val c = colOf(key)
            when {
                delta > 0 -> updated[pack(if (r >= at) r + delta else r, c)] = f
                delta < 0 -> if (r != at) updated[pack(if (r > at) r + delta else r, c)] = f
            }
        }
        formulas.clear()
        formulas.putAll(updated)
    }

    private fun ensureColumns(rowIndex: Int, columnIndex: Int) {
        val row = rows[rowIndex]
        if (columnIndex >= row.size) rows[rowIndex] = row.copyOf(columnIndex + 1)
    }

    companion object {
        private const val EXTRA_COLS = 5
        private const val MIN_COLS = 8
        private const val MAX_UNDO = 300

        fun pack(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xFFFFFFFFL)
        fun rowOf(packed: Long): Int = (packed ushr 32).toInt()
        fun colOf(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()
    }
}
