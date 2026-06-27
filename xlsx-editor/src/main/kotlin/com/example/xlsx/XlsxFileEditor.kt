package com.example.xlsx

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.JComponent

/** Property name fired so the IDE updates the "modified" marker on the editor tab. */
private const val PROP_MODIFIED = "modified"

/** Rows per streamed batch pushed to the EDT. */
private const val BATCH_SIZE = 2000

private val LOG = logger<XlsxFileEditor>()

/**
 * A [FileEditor] that renders an Excel workbook (`.xlsx` or `.xls`) as one editable, filterable
 * grid per sheet, with an optional vim mode.
 *
 * - `.xlsx` is read with the streaming SAX path ([XlsxStreamingReader]) — sheets parse on
 *   background threads (parallel) and rows stream into the grid in batches.
 * - `.xls` (BIFF, capped at 65,536 rows) is read fully via [XlsWorkbookReader] and fed through
 *   the same model.
 *
 * Editing is recorded as an overlay per sheet; the full editable [Workbook] is built lazily on
 * the first edit (via [WorkbookFactory], preserving the original format) and, on save, the overlay
 * is applied and the workbook written back.
 */
class XlsxFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val changeSupport = PropertyChangeSupport(this)
    private val loadingPanel = JBLoadingPanel(BorderLayout(), this)
    private var modified = false
    private var firstTable: JBTable? = null

    private var originalBytes: ByteArray? = null
    private var panels: List<SheetPanel> = emptyList()
    private var tabbedPane: JBTabbedPane? = null

    @Volatile private var disposed = false
    @Volatile private var saving = false
    @Volatile private var editableWorkbookFuture: CompletableFuture<Workbook>? = null

    // All POI workbook work (build + non-live save serialize) runs on this single dedicated thread:
    // serialized so the non-thread-safe workbook is only ever touched by one thread, and off the app
    // pool so blocking on the build never starves it (which deadlocked saves before).
    private val poiExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xlsx-poi").apply { isDaemon = true }
    }

    // Live formula evaluation: for files with formulas the editable workbook becomes the live source —
    // edits apply to it directly, dependents recompute, and results are pushed back to the grid.
    @Volatile private var liveRequested = false
    private var liveWorkbook: Workbook? = null
    private var liveEvaluator: FormulaEvaluator? = null
    private var liveActive = false
    private val liveFormatter = DataFormatter()

    init {
        LOG.info("XLSX editor opening: ${file.name}")
        loadingPanel.setLoadingText("Loading spreadsheet…")
        loadingPanel.startLoading()
        startBackgroundOpen()
    }

    private fun startBackgroundOpen() {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val started = System.currentTimeMillis()
            // Raise POI's anti-DoS per-record array cap (default ~5M) so large legacy .xls files can
            // be read — a user-opened spreadsheet is trusted; the real limit then becomes heap.
            try {
                org.apache.poi.util.IOUtils.setByteArrayMaxOverride(500_000_000)
            } catch (ignored: Throwable) {
            }
            if (file.extension?.equals("xls", ignoreCase = true) == true) {
                // .xls (HSSF) needs the whole workbook in memory — read the bytes now (from the local
                // io file when possible; stream read, not contentsToByteArray(), bypasses the IDE's
                // ~20MB FileTooBigException limit).
                val bytes = try {
                    val io = try { com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile(file) } catch (e: Exception) { null }
                    if (io != null && io.isFile) java.nio.file.Files.readAllBytes(io.toPath())
                    else file.inputStream.use { it.readBytes() }
                } catch (e: Throwable) {
                    fail(e)
                    return@executeOnPooledThread
                }
                originalBytes = bytes
                openLegacyXls(bytes, started)
            } else {
                // .xlsx: stream straight from the file — don't load the (possibly 100 MB) bytes
                // upfront; they're read lazily only if the user edits (for the editable workbook).
                openStreamingXlsx(started)
            }
        }
    }

    private fun openStreamingXlsx(started: Long) {
        val app = ApplicationManager.getApplication()
        val source: XlsxStreamingReader.Source
        try {
            // Open from the local file (random access — ~16 ms vs ~1.5 s from a byte stream for a
            // 100 MB file, and avoids loading the whole file into memory just to open it). Use
            // VfsUtilCore (NOT toNioPath().toFile(), which throws under the IDE's custom nio FS).
            val localFile = try { com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile(file) } catch (e: Exception) { null }
            source = if (localFile != null && localFile.isFile) {
                XlsxStreamingReader.open(localFile)
            } else {
                // Non-local file: fall back to the in-memory bytes (also kept for editing).
                XlsxStreamingReader.open(file.inputStream.use { it.readBytes() }.also { originalBytes = it })
            }
        } catch (e: Throwable) {
            fail(e)
            return
        }
        app.invokeLater(Runnable {
            if (!disposed) {
                buildTabs(source.names)
                LOG.info("XLSX first paint: ${file.name} — ${source.names.size} sheet(s) in ${System.currentTimeMillis() - started} ms")
            }
        }, ModalityState.any())
        // Read each sheet's XML lazily on one background thread (the active sheet — index 0 — first);
        // parse + scan each sheet in parallel as its XML arrives, so rows show without waiting for
        // every sheet's XML to be decompressed up front (~1.6 s for a 100 MB / 20-sheet file).
        app.executeOnPooledThread {
            source.readSheets { i, xml ->
                app.executeOnPooledThread { parseOneSheet(i, xml, source.sst, source.styles) }
                app.executeOnPooledThread { scanFormulas(i, xml) }
            }
        }
    }

    private fun scanFormulas(index: Int, xml: ByteArray) {
        val map = try {
            FormulaScanner.scan(xml)
        } catch (e: Throwable) {
            LOG.warn("XLSX formula scan failed: sheet $index — ${e.message}")
            emptyMap()
        }
        if (map.isEmpty()) return
        ApplicationManager.getApplication().invokeLater(
            Runnable {
                if (!disposed) {
                    panels.getOrNull(index)?.applyFormulas(map)
                    ensureLiveMode()
                }
            },
            ModalityState.any(),
        )
    }

    private fun parseOneSheet(index: Int, xml: ByteArray, sst: org.apache.poi.xssf.model.SharedStrings, styles: org.apache.poi.xssf.model.StylesTable) {
        val app = ApplicationManager.getApplication()
        val started = System.currentTimeMillis()
        try {
            XlsxStreamingReader.parseSheet(xml, sst, styles, BATCH_SIZE) { batch ->
                app.invokeLater(Runnable { if (!disposed) panels.getOrNull(index)?.model?.appendBatch(batch) }, ModalityState.any())
            }
        } catch (e: Throwable) {
            LOG.warn("XLSX sheet parse failed: sheet $index — ${e.message}")
        } finally {
            app.invokeLater(Runnable {
                if (disposed) return@Runnable
                val panel = panels.getOrNull(index) ?: return@Runnable
                panel.onStreamingFinished()
                LOG.info("XLSX sheet loaded: ${panel.model.sheetName} — ${panel.model.loadedRowCount()} row(s) in ${System.currentTimeMillis() - started} ms")
            }, ModalityState.any())
        }
    }

    private fun openLegacyXls(bytes: ByteArray, started: Long) {
        val app = ApplicationManager.getApplication()
        // Build the whole workbook (HSSF can't stream) — then show the tabs immediately, before
        // rendering every sheet's cells (which is the larger cost for a big .xls).
        val wb = try {
            XlsWorkbookReader.open(bytes)
        } catch (e: Throwable) {
            fail(e)
            return
        }
        val names = XlsWorkbookReader.sheetNames(wb)
        app.invokeLater(Runnable {
            if (!disposed) {
                buildTabs(names)
                LOG.info("XLS first paint: ${file.name} — ${names.size} sheet(s) in ${System.currentTimeMillis() - started} ms")
            }
        }, ModalityState.any())
        // Render sheets one at a time (the active sheet — index 0 — first) and fill each model as it
        // finishes, so the visible sheet appears right after the build instead of after all 20.
        app.executeOnPooledThread {
            try {
                for (i in names.indices) {
                    if (disposed) break
                    val data = XlsWorkbookReader.renderSheet(wb, i)
                    val sheetStarted = System.currentTimeMillis()
                    app.invokeLater(Runnable {
                        if (disposed) return@Runnable
                        val panel = panels.getOrNull(i) ?: return@Runnable
                        for (chunk in data.rows.chunked(BATCH_SIZE)) panel.model.appendBatch(chunk)
                        panel.onStreamingFinished()
                        if (data.formulas.isNotEmpty()) {
                            panel.applyFormulas(data.formulas)
                            ensureLiveMode()
                        }
                        LOG.info("XLS sheet loaded: ${data.name} — ${panel.model.loadedRowCount()} row(s) in ${System.currentTimeMillis() - sheetStarted} ms")
                    }, ModalityState.any())
                }
            } finally {
                withPoiClassLoader { try { wb.close() } catch (ignored: Exception) {} }
            }
        }
    }

    private fun fail(e: Throwable) {
        val msg = e.message ?: e.toString()
        LOG.warn("XLSX open failed: ${file.name} — $msg")
        ApplicationManager.getApplication().invokeLater(Runnable { if (!disposed) showError(msg) }, ModalityState.any())
    }

    private fun buildTabs(names: List<String>) {
        loadingPanel.stopLoading()
        val built = names.map { name ->
            SheetPanel(
                SheetTableModel(name) { onEdited() },
                onNextSheet = { switchSheet(1) },
                onPrevSheet = { switchSheet(-1) },
            )
        }
        panels = built
        firstTable = built.firstOrNull()?.table
        val center: JComponent = if (built.size <= 1) {
            built.firstOrNull()?.component ?: JBLabel("(empty workbook)")
        } else {
            JBTabbedPane().also { tabs ->
                built.forEach { tabs.addTab(it.model.sheetName, it.component) }
                tabbedPane = tabs
            }
        }
        loadingPanel.add(center, BorderLayout.CENTER)
        loadingPanel.revalidate()
        loadingPanel.repaint()
    }

    /** vim `gt`/`gT`: cycle to the next/previous sheet and focus its grid. */
    private fun switchSheet(delta: Int) {
        val tp = tabbedPane ?: return
        val n = tp.tabCount
        if (n <= 1) return
        val next = ((tp.selectedIndex + delta) % n + n) % n
        tp.selectedIndex = next
        panels.getOrNull(next)?.table?.requestFocusInWindow()
    }

    private fun showError(message: String) {
        loadingPanel.stopLoading()
        loadingPanel.add(JBLabel("Cannot open spreadsheet: $message"), BorderLayout.NORTH)
        loadingPanel.revalidate()
        loadingPanel.repaint()
    }

    private fun onEdited() {
        if (!modified) {
            modified = true
            LOG.info("XLSX modified: ${file.name}")
            changeSupport.firePropertyChange(PROP_MODIFIED, false, true)
        }
        ensureEditableWorkbookStarted()
        if (panels.any { it.model.hasFormulas() }) ensureLiveMode() // a formula was typed → go live
    }

    /** Kick off building the full editable workbook in the background (once). */
    private fun ensureEditableWorkbookStarted() {
        if (editableWorkbookFuture != null || disposed) return
        synchronized(this) {
            if (editableWorkbookFuture != null || disposed) return
            editableWorkbookFuture = CompletableFuture.supplyAsync(
                {
                    // Read the original bytes lazily — the .xlsx open path skips them for speed, so
                    // the (possibly 100 MB) read only happens here, on first edit.
                    val bytes = originalBytes ?: java.nio.file.Files.readAllBytes(
                        com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile(file).toPath(),
                    ).also { originalBytes = it }
                    withPoiClassLoader { WorkbookFactory.create(ByteArrayInputStream(bytes)) }
                },
                poiExecutor,
            )
        }
    }

    /**
     * Apply the edit overlay to the editable workbook and serialize it (preserving the original
     * format). Used by [requestSave] for live-formula files (runs on the EDT); may block briefly if
     * the background build is unfinished. The non-live path serializes inline in [saveAsync] instead.
     */
    private fun serializeBytes(): ByteArray? {
        if (!modified && panels.none { it.model.hasEdits() }) return null
        ensureEditableWorkbookStarted()
        val workbook = try {
            editableWorkbookFuture?.get()
        } catch (e: Exception) {
            LOG.warn("XLSX workbook build failed: ${file.name} — ${e.message}")
            null
        } ?: return null

        return withPoiClassLoader {
            for (panel in panels) {
                val model = panel.model
                if (!model.hasEdits()) continue
                if (liveActive) {
                    model.drainOps() // already applied to the live workbook during editing
                } else {
                    val sheet = workbook.getSheet(model.sheetName) ?: continue
                    replayOps(sheet, model.drainOps())
                }
            }
            recalcFormulas(workbook)
            // Pre-size: zipped (.xlsx) / BIFF (.xls) output is roughly the original file size, so
            // this avoids the doubling reallocations a default ByteArrayOutputStream would do.
            ByteArrayOutputStream(maxOf(originalBytes?.size ?: 0, 64 * 1024)).use { out ->
                workbook.write(out)
                out.toByteArray()
            }
        }
    }

    /**
     * Replay recorded edits in order, coalescing consecutive InsertRow / DeleteRow runs into a
     * single (expensive) `shiftRows` call — e.g. vim `5o` / `5dd` near the top of a 100k-row sheet
     * would otherwise do 5 full-sheet shifts.
     */
    private fun replayOps(sheet: Sheet, ops: List<EditOp>) {
        var i = 0
        while (i < ops.size) {
            try {
                when (val op = ops[i]) {
                    is EditOp.SetCell -> {
                        applySetCell(sheet, op)
                        i++
                    }
                    is EditOp.SetFormula -> {
                        val poiRow = sheet.getRow(op.row) ?: sheet.createRow(op.row)
                        val cell = poiRow.getCell(op.col) ?: poiRow.createCell(op.col)
                        try {
                            cell.setCellFormula(op.formula)
                        } catch (e: Exception) {
                            LOG.warn("XLSX invalid formula on save at ${sheet.sheetName} ${op.row},${op.col}: ${e.message}")
                            cell.setCellValue("=" + op.formula) // invalid formula: keep as text
                        }
                        i++
                    }
                    is EditOp.InsertRow -> {
                        var k = 1
                        while (i + k < ops.size) {
                            val n = ops[i + k]
                            if (n is EditOp.InsertRow && n.row == op.row) k++ else break
                        }
                        val last = sheet.lastRowNum
                        if (op.row <= last) sheet.shiftRows(op.row, last, k) // open k blank rows at op.row
                        i += k
                    }
                    is EditOp.DeleteRow -> {
                        // vim Ndd records a strictly-descending run (R, R-1, …).
                        var k = 1
                        while (i + k < ops.size) {
                            val n = ops[i + k]
                            if (n is EditOp.DeleteRow && n.row == op.row - k) k++ else break
                        }
                        val last = sheet.lastRowNum
                        val minRow = op.row - k + 1
                        if (minRow in 0..last) {
                            val hi = minOf(op.row, last)
                            for (r in minRow..hi) sheet.getRow(r)?.let { sheet.removeRow(it) }
                            if (hi < last) sheet.shiftRows(hi + 1, last, -(hi - minRow + 1))
                        }
                        i += k
                    }
                }
            } catch (e: IllegalArgumentException) {
                // e.g. .xls (BIFF8) row/col ceiling exceeded — skip and continue.
                LOG.warn("XLSX skip op in ${sheet.sheetName}: ${e.message}")
                i++
            }
        }
    }

    /** Recalculate dependent formulas so edits propagate — only when there are formulas at all. */
    private fun recalcFormulas(workbook: Workbook) {
        if (!liveActive && panels.none { it.model.hasFormulas() }) return // no formulas: skip (don't scan the workbook)
        try {
            workbook.creationHelper.createFormulaEvaluator().evaluateAll()
        } catch (e: Exception) {
            // Some Excel functions aren't implemented by POI — fall back to "Excel recalcs on open".
            LOG.warn("XLSX formula recalc failed (${e.message}); flagging recalc-on-open")
            try {
                workbook.setForceFormulaRecalculation(true)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun applySetCell(sheet: Sheet, op: EditOp.SetCell) {
        if (op.value.isEmpty()) {
            // Clearing: only touch a cell that already exists (don't bloat the file).
            sheet.getRow(op.row)?.getCell(op.col)?.setBlank()
        } else {
            val row = sheet.getRow(op.row) ?: sheet.createRow(op.row)
            val cell = row.getCell(op.col) ?: row.createCell(op.col)
            val number = op.value.toDoubleOrNull()
            if (number != null) cell.setCellValue(number) else cell.setCellValue(op.value)
        }
    }

    // ---- Live formula evaluation ----

    /** Build the editable workbook eagerly and activate live evaluation once it's ready. */
    private fun ensureLiveMode() {
        if (liveRequested || disposed) return
        liveRequested = true
        ensureEditableWorkbookStarted()
        val app = ApplicationManager.getApplication()
        // Non-blocking: activate when the build completes (don't tie up a thread on get()).
        editableWorkbookFuture?.thenAccept { wb ->
            app.invokeLater(Runnable { if (!disposed) activateLive(wb) }, ModalityState.any())
        }
    }

    private fun activateLive(wb: Workbook) {
        if (liveActive || disposed) return
        liveWorkbook = wb
        withPoiClassLoader {
            liveEvaluator = wb.creationHelper.createFormulaEvaluator()
            for (panel in panels) {
                val sheet = wb.getSheet(panel.model.sheetName) ?: continue
                replayOps(sheet, panel.model.drainOps()) // catch up edits made before the workbook was ready
            }
            refreshAllFormulaDisplays()
        }
        liveActive = true
        // Defer live work to the next EDT cycle so it never fires table events re-entrantly inside
        // the edit that triggered it (which desyncs the row sorter -> "Invalid range").
        val app = ApplicationManager.getApplication()
        for (panel in panels) {
            panel.model.liveHandler = { r, c ->
                app.invokeLater(Runnable { if (!disposed) onLiveCellEdit(panel, r, c) }, ModalityState.any())
            }
            panel.model.liveStructuralHandler = { op ->
                app.invokeLater(Runnable { if (!disposed) onLiveStructural(panel, op) }, ModalityState.any())
            }
        }
    }

    private fun onLiveCellEdit(panel: SheetPanel, row: Int, col: Int) {
        val wb = liveWorkbook ?: return
        val eval = liveEvaluator ?: return
        withPoiClassLoader {
            val sheet = wb.getSheet(panel.model.sheetName) ?: return@withPoiClassLoader
            val poiRow = sheet.getRow(row) ?: sheet.createRow(row)
            val cell = poiRow.getCell(col) ?: poiRow.createCell(col)
            val formula = panel.model.formulaText(row, col)
            if (formula != null) {
                try {
                    cell.setCellFormula(formula.removePrefix("="))
                } catch (e: Exception) {
                    LOG.warn("XLSX invalid formula at ${panel.model.sheetName} $row,$col: ${e.message}")
                    cell.setCellValue(formula)
                }
            } else {
                applyCellValue(cell, panel.model.getValueAt(row, col).toString())
            }
            eval.clearAllCachedResultValues()
            refreshAllFormulaDisplays()
        }
    }

    private fun onLiveStructural(panel: SheetPanel, op: EditOp) {
        val wb = liveWorkbook ?: return
        withPoiClassLoader {
            val sheet = wb.getSheet(panel.model.sheetName) ?: return@withPoiClassLoader
            when (op) {
                is EditOp.InsertRow -> {
                    val last = sheet.lastRowNum
                    if (op.row <= last) sheet.shiftRows(op.row, last, 1)
                }
                is EditOp.DeleteRow -> {
                    val last = sheet.lastRowNum
                    if (op.row in 0..last) {
                        sheet.getRow(op.row)?.let { sheet.removeRow(it) }
                        if (op.row < last) sheet.shiftRows(op.row + 1, last, -1)
                    }
                }
                else -> {}
            }
            liveEvaluator?.clearAllCachedResultValues()
            refreshAllFormulaDisplays()
        }
    }

    /** Re-evaluate formula cells (all sheets, for cross-sheet refs) and push results to the grid. */
    private fun refreshAllFormulaDisplays() {
        val wb = liveWorkbook ?: return
        val eval = liveEvaluator ?: return
        for (panel in panels) {
            val sheet = wb.getSheet(panel.model.sheetName) ?: continue
            for (key in panel.model.formulaKeys()) {
                val r = SheetTableModel.rowOf(key)
                val c = SheetTableModel.colOf(key)
                val cell = sheet.getRow(r)?.getCell(c) ?: continue
                val display = try {
                    eval.evaluateFormulaCell(cell)
                    formatCached(cell)
                } catch (e: Exception) {
                    null // unsupported function etc. — keep the previous (cached) display
                }
                if (display != null) panel.model.setDisplayValue(r, c, display)
            }
        }
    }

    private fun applyCellValue(cell: org.apache.poi.ss.usermodel.Cell, text: String) {
        if (text.isEmpty()) {
            cell.setBlank()
            return
        }
        val number = text.toDoubleOrNull()
        if (number != null) cell.setCellValue(number) else cell.setCellValue(text)
    }

    private fun formatCached(cell: org.apache.poi.ss.usermodel.Cell): String =
        formatCachedFormulaResult(cell, liveFormatter)

    private fun markSaved() {
        if (modified) {
            modified = false
            changeSupport.firePropertyChange(PROP_MODIFIED, true, false)
        }
    }

    /**
     * Persist the edits. Called from the save hook ([XlsxSaveListener]) on the EDT. Formula files
     * (live mode) serialize synchronously — their workbook is EDT-confined and they are small;
     * everything else serializes the heavy `workbook.write` on the background [poiExecutor] so the UI
     * never freezes (safe because non-live edits only touch the model + op log, not the workbook).
     */
    fun requestSave() {
        if (disposed || saving) return
        // Branch on liveRequested (set synchronously when a formula file is first edited), not the
        // async liveActive flag, so a formula file always takes the EDT-confined sync path — this
        // also avoids racing live activation against a background save during the workbook build.
        if (liveRequested) {
            val bytes = serializeBytes() ?: return
            if (writeToFile(bytes)) markSaved()
        } else {
            saveAsync()
        }
    }

    private fun saveAsync() {
        if (panels.none { it.model.hasEdits() }) {
            markSaved()
            return
        }
        ensureEditableWorkbookStarted()
        val future = editableWorkbookFuture
        if (future == null) {
            LOG.warn("XLSX saveAsync: editable workbook future is null (originalBytes=${originalBytes != null})")
            return
        }
        saving = true
        val app = ApplicationManager.getApplication()
        // Snapshot edits on the EDT so the background thread doesn't race ongoing edits. For sheets
        // with row insert/delete, also snapshot the rows: POI's XSSFSheet.shiftRows is O(n) with a
        // catastrophic constant (~minutes on 100k rows), so those sheets are rebuilt from the model
        // instead (value-only, but fast). Cell-only sheets keep the formatting-preserving op replay.
        val plans = panels.map { panel ->
            val ops = panel.model.drainOps()
            val structural = ops.any { it is EditOp.InsertRow || it is EditOp.DeleteRow }
            SavePlan(panel.model.sheetName, ops, if (structural) panel.model.snapshotRows() else null)
        }
        poiExecutor.execute {
            val bytes: ByteArray? = try {
                val workbook = future.get()
                withPoiClassLoader {
                    for (plan in plans) {
                        if (plan.snapshot != null) {
                            rebuildSheetFromRows(workbook, plan.name, plan.snapshot)
                        } else {
                            workbook.getSheet(plan.name)?.let { replayOps(it, plan.ops) }
                        }
                    }
                    recalcFormulas(workbook)
                    ByteArrayOutputStream(maxOf(originalBytes?.size ?: 0, 64 * 1024)).use { out ->
                        workbook.write(out)
                        out.toByteArray()
                    }
                }
            } catch (e: Throwable) {
                LOG.warn("XLSX async save failed: ${file.name} — ${e.message}")
                null
            }
            app.invokeLater(Runnable {
                // writeToFile never throws, so `saving` is always cleared (no stuck save state). Only
                // mark saved when the write succeeded AND no new edits landed during the save.
                if (!disposed && bytes != null && writeToFile(bytes) && panels.none { it.model.hasEdits() }) {
                    markSaved()
                }
                saving = false
            }, ModalityState.any())
        }
    }

    /**
     * Write [bytes] back to the file. Returns false — and leaves the editor "modified" — if the file
     * can't be written: it's read-only and the user declined to clear that (or VCS check-out failed),
     * or the write itself errored. Never throws, so the caller's save bookkeeping always completes.
     */
    private fun writeToFile(bytes: ByteArray): Boolean {
        // Make the file writable first: clears an OS read-only flag (prompting the user) or checks it
        // out from VCS. If it's still read-only afterward the user opted out, so don't write.
        if (!file.isWritable) {
            ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(file))
            if (!file.isWritable) {
                LOG.info("XLSX save skipped — read-only: ${file.name}")
                notifyWarn("저장하지 못했습니다 — '${file.name}'은(는) 읽기 전용입니다.")
                return false
            }
        }
        return try {
            ApplicationManager.getApplication().runWriteAction {
                file.setBinaryContent(bytes, -1L, -1L, this)
            }
            LOG.info("XLSX saved: ${file.name} (${bytes.size} bytes)")
            true
        } catch (e: Throwable) {
            LOG.warn("XLSX write failed: ${file.name} — ${e.message}")
            notifyWarn("저장하지 못했습니다 — '${file.name}': ${e.message}")
            false
        }
    }

    private fun notifyWarn(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("XLSX Grid Editor")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    private class SavePlan(val name: String, val ops: List<EditOp>, val snapshot: List<Array<String?>>?)

    /**
     * Rebuild a whole sheet from the model's current rows — the fast alternative to POI's
     * pathologically slow `shiftRows` (O(n) with a ~minute constant on 100k rows) when a large sheet
     * had rows inserted/deleted. Cell styles/number-formats for that sheet are not preserved (the
     * non-formula data-table files that take this path rarely have them); values keep their text vs
     * number type via a round-trip check so text like "01234" or long IDs are not mangled.
     */
    private fun rebuildSheetFromRows(workbook: Workbook, name: String, rows: List<Array<String?>>) {
        val idx = workbook.getSheetIndex(name)
        if (idx < 0) return
        workbook.removeSheetAt(idx)
        val sheet = workbook.createSheet(name)
        workbook.setSheetOrder(name, idx)
        for (r in rows.indices) {
            val data = rows[r]
            var poiRow: org.apache.poi.ss.usermodel.Row? = null
            for (c in data.indices) {
                val v = data[c]
                if (v.isNullOrEmpty()) continue
                if (poiRow == null) poiRow = sheet.createRow(r)
                val cell = poiRow.createCell(c)
                val num = numericIfRoundTrips(v)
                if (num != null) cell.setCellValue(num) else cell.setCellValue(v)
            }
        }
    }

    /**
     * Parse [v] as a number ONLY if its canonical form is exactly [v] — so text that merely looks
     * numeric ("01234" zip codes, 16+ digit IDs that lose precision as a double, "1,000" grouped
     * numbers) stays text instead of being silently coerced when a sheet is rebuilt.
     */
    private fun numericIfRoundTrips(v: String): Double? {
        val d = v.toDoubleOrNull() ?: return null
        val canonical = if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        return if (canonical == v) d else null
    }

    override fun getComponent(): JComponent = loadingPanel
    override fun getPreferredFocusedComponent(): JComponent? = firstTable
    override fun getName(): String = "Spreadsheet"
    override fun getFile(): VirtualFile = file
    override fun isModified(): Boolean = modified
    override fun isValid(): Boolean = file.isValid
    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
    override fun setState(state: FileEditorState) {}

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.removePropertyChangeListener(listener)

    override fun dispose() {
        disposed = true
        val future = editableWorkbookFuture
        editableWorkbookFuture = null
        // Close the workbook ON poiExecutor so it serializes AFTER any in-flight build/save — never
        // racing workbook.write on the same non-thread-safe workbook. shutdown() (not shutdownNow)
        // lets those queued tasks drain on the daemon thread; an in-flight save's bytes are dropped
        // by the disposed check in saveAsync's invokeLater, so nothing reaches disk after dispose.
        if (future != null) {
            poiExecutor.execute {
                try {
                    withPoiClassLoader { future.get().close() }
                } catch (e: Exception) {
                    // ignore on close
                }
            }
        }
        poiExecutor.shutdown()
    }
}
