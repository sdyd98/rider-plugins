package com.example.xlsx

/**
 * Pure model for the GRID diff of two workbook revisions: load each side's sheets, align rows with a
 * pluggable [RowAligner] (production: the platform's ComparisonManager; tests: any fake), and compute
 * cell-level changes for modified pairs. No UI, no platform types — headless-testable; the platform
 * glue is [XlsxGridDiffTool].
 */
object XlsxDiffModel {

    /** Per-sheet row cap (per side) — beyond this, alignment cost stops being interactive; the sheet
     *  is marked [SheetDiff.tooBig] and the viewer points at the text diff instead. */
    const val MAX_DIFF_ROWS = 100_000

    enum class Kind { EQUAL, MODIFIED, INSERTED, DELETED }

    /** One aligned display row: original 0-based indices (null on the side that lacks the row). */
    class DiffRow(
        val leftIndex: Int?,
        val rightIndex: Int?,
        val left: Array<String?>?,
        val right: Array<String?>?,
        val kind: Kind,
        /** Column indices whose cell value differs — only for [Kind.MODIFIED] pairs. */
        val changedCols: IntArray = IntArray(0),
    )

    class SheetDiff(
        val name: String,
        val rows: List<DiffRow>,
        val columnCount: Int,
        val modified: Int,
        val inserted: Int,
        val deleted: Int,
        val tooBig: Boolean = false,
        /** null unless the sheet exists on only one side. */
        val onlySide: String? = null,
    ) {
        val changed: Boolean get() = tooBig || modified + inserted + deleted > 0 || onlySide != null
    }

    /** Changed line ranges between two row-key lists, as [s1, e1, s2, e2) quadruples in order.
     *  Production wraps ComparisonManager.compareLines; tests can hand-roll ranges. */
    fun interface RowAligner {
        fun changedRanges(left: List<String>, right: List<String>): List<IntArray>
    }

    /** Load a workbook's sheets (name → rows) from bytes — same readers as the grid viewer. */
    fun loadSheets(bytes: ByteArray, isLegacyXls: Boolean): LinkedHashMap<String, List<Array<String?>>> {
        val sheets = LinkedHashMap<String, List<Array<String?>>>()
        if (isLegacyXls) {
            val wb = XlsWorkbookReader.open(bytes)
            try {
                for (i in 0 until wb.numberOfSheets) {
                    val data = XlsWorkbookReader.renderSheet(wb, i)
                    sheets[data.name] = data.rows
                }
            } finally {
                withPoiClassLoader { runCatching { wb.close() } }
            }
        } else {
            val source = XlsxStreamingReader.open(bytes)
            source.readSheets { i, xml ->
                val rows = ArrayList<Array<String?>>()
                XlsxStreamingReader.parseSheet(xml, source.sst, source.styles, 4096) { batch -> rows.addAll(batch) }
                sheets[source.names.getOrNull(i) ?: "Sheet$i"] = rows
            }
        }
        return sheets
    }

    /** Diff two loaded workbooks: the sheet list is the LEFT order plus right-only sheets appended. */
    fun diff(
        left: Map<String, List<Array<String?>>>,
        right: Map<String, List<Array<String?>>>,
        aligner: RowAligner,
    ): List<SheetDiff> {
        val out = ArrayList<SheetDiff>()
        val names = LinkedHashSet(left.keys).apply { addAll(right.keys) }
        for (name in names) {
            val l = left[name]
            val r = right[name]
            out += when {
                l == null -> onlySide(name, r!!, "우측에만 있음", insertedSide = true)
                r == null -> onlySide(name, l, "좌측에만 있음", insertedSide = false)
                else -> diffSheet(name, l, r, aligner)
            }
        }
        return out
    }

    private fun onlySide(name: String, rows: List<Array<String?>>, label: String, insertedSide: Boolean): SheetDiff {
        val kind = if (insertedSide) Kind.INSERTED else Kind.DELETED
        val diffRows = rows.mapIndexed { i, row ->
            if (insertedSide) DiffRow(null, i, null, row, kind) else DiffRow(i, null, row, null, kind)
        }
        return SheetDiff(
            name, diffRows, columnCount = rows.maxOfOrNull { it.size } ?: 0,
            modified = 0,
            inserted = if (insertedSide) rows.size else 0,
            deleted = if (insertedSide) 0 else rows.size,
            onlySide = label,
        )
    }

    private fun diffSheet(name: String, l: List<Array<String?>>, r: List<Array<String?>>, aligner: RowAligner): SheetDiff {
        val cols = maxOf(l.maxOfOrNull { it.size } ?: 0, r.maxOfOrNull { it.size } ?: 0)
        if (l.size > MAX_DIFF_ROWS || r.size > MAX_DIFF_ROWS) {
            return SheetDiff(name, emptyList(), cols, 0, 0, 0, tooBig = true)
        }
        val ranges = aligner.changedRanges(l.map(::rowKey), r.map(::rowKey))

        val rows = ArrayList<DiffRow>(maxOf(l.size, r.size))
        var modified = 0; var inserted = 0; var deleted = 0
        var li = 0; var ri = 0
        fun equalUntil(lEnd: Int) {
            while (li < lEnd) { rows.add(DiffRow(li, ri, l[li], r[ri], Kind.EQUAL)); li++; ri++ }
        }
        for (range in ranges) {
            val (s1, e1, s2, e2) = range
            equalUntil(s1)
            ri = s2 // (always already true for well-formed ranges; assignment keeps us honest)
            val paired = minOf(e1 - s1, e2 - s2)
            repeat(paired) {
                val changed = changedCols(l[li], r[ri], cols)
                rows.add(DiffRow(li, ri, l[li], r[ri], if (changed.isEmpty()) Kind.EQUAL else Kind.MODIFIED, changed))
                if (changed.isNotEmpty()) modified++
                li++; ri++
            }
            while (li < e1) { rows.add(DiffRow(li, null, l[li], null, Kind.DELETED)); li++; deleted++ }
            while (ri < e2) { rows.add(DiffRow(null, ri, null, r[ri], Kind.INSERTED)); ri++; inserted++ }
        }
        equalUntil(l.size)
        return SheetDiff(name, rows, cols, modified, inserted, deleted)
    }

    private fun changedCols(a: Array<String?>, b: Array<String?>, cols: Int): IntArray {
        val changed = ArrayList<Int>(4)
        for (c in 0 until cols) {
            if ((a.getOrNull(c) ?: "") != (b.getOrNull(c) ?: "")) changed.add(c)
        }
        return changed.toIntArray()
    }

    /** Line-stable identity of a row for the aligner (same shape as the TSV text projection). */
    fun rowKey(row: Array<String?>): String {
        val sb = StringBuilder()
        for ((i, cell) in row.withIndex()) {
            if (i > 0) sb.append('\t')
            if (cell != null) for (ch in cell) sb.append(if (ch == '\t' || ch == '\n' || ch == '\r') ' ' else ch)
        }
        return sb.toString()
    }

    /** Excel-style column letters: 0 → A, 27 → AB. */
    fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }
}

private operator fun IntArray.component1() = this[0]
private operator fun IntArray.component2() = this[1]
private operator fun IntArray.component3() = this[2]
private operator fun IntArray.component4() = this[3]
