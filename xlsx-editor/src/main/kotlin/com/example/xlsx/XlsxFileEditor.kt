package com.example.xlsx

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

/** Rows per streamed batch pushed to the EDT. */
private const val BATCH_SIZE = 2000

// Pinned heights (px, pre-scale) for the Compose chrome bars — JewelComposePanel doesn't report its
// content height to the Swing layout, so we set these explicitly. Filter bar gains a row for chips.
private const val FILTER_BAR_H = 38
private const val FILTER_BAR_CHIPS_H = 64
private const val STATUS_BAR_H = 26

private val LOG = logger<XlsxFileEditor>()

/**
 * A [FileEditor] that renders an Excel workbook (`.xlsx` or `.xls`) as one read-only, filterable
 * grid per sheet, with vim-style navigation.
 *
 * - `.xlsx` is read with the streaming SAX path ([XlsxStreamingReader]) — sheets parse on
 *   background threads (parallel) and rows stream into the grid in batches.
 * - `.xls` (BIFF, capped at 65,536 rows) is read fully via [XlsWorkbookReader] and fed through
 *   the same model.
 *
 * This is a viewer: cells are not editable and the file is never written back.
 */
class XlsxFileEditor(
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val changeSupport = PropertyChangeSupport(this)
    // Plain container (NOT JBLoadingPanel) — opens are fast, so we show a quiet centered "Loading…"
    // text instead of a spinner (multiple spinners when opening several files at once looked noisy).
    private val loadingPanel = JPanel(BorderLayout())
    private var firstTable: JBTable? = null

    private var panels: List<SheetPanel> = emptyList()
    private var tabbedPane: JBTabbedPane? = null

    @Volatile private var disposed = false

    // Shared Compose (Jewel) chrome — ONE filter bar + ONE status bar for the whole editor, bound to
    // the active sheet's tab (see ComposeChrome.kt). One Compose surface pair, not one per sheet, so a
    // 20-sheet workbook doesn't pay 40 Compose-panel setups on open.
    private val filterQuery = TextFieldState()
    private val filterFocus = FocusRequester()
    private val chrome = mutableStateOf(ChromeData())
    private var activeSheet: SheetPanel? = null
    private var filterBar: JComponent? = null // pinned-height holder for the Compose filter bar
    // Debounce so a keystroke at 100k rows doesn't re-filter on every char.
    private val filterDebounce = Timer(150) { activeSheet?.applyFilter(filterQuery.text.toString()) }
        .apply { isRepeats = false }

    init {
        LOG.info("XLSX editor opening: ${file.name}")
        loadingPanel.add(JBLabel("Loading spreadsheet…", SwingConstants.CENTER).apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.CENTER)
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
                openLegacyXls(bytes, started)
            } else {
                // .xlsx: stream straight from the file — never load the (possibly 100 MB) bytes into
                // memory; the streaming reader opens the package by random access for display only.
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
                // Non-local file: fall back to reading the bytes in memory.
                XlsxStreamingReader.open(file.inputStream.use { it.readBytes() })
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
        loadingPanel.removeAll() // drop the "Loading…" placeholder
        val built = names.map { name ->
            SheetPanel(
                SheetTableModel(name),
                onNextSheet = { switchSheet(1) },
                onPrevSheet = { switchSheet(-1) },
                onFocusFilter = { focusSharedFilter() },
            )
        }
        panels = built
        firstTable = built.firstOrNull()?.table
        val center: JComponent = if (built.size <= 1) {
            built.firstOrNull()?.component ?: JBLabel("(empty workbook)")
        } else {
            JBTabbedPane().also { tabs ->
                built.forEach { tabs.addTab(it.model.sheetName, it.component) }
                tabs.addChangeListener { panels.getOrNull(tabs.selectedIndex)?.let { setActiveSheet(it) } }
                tabbedPane = tabs
            }
        }
        // Wrap the grid(s) with ONE shared Compose filter bar (top) + status bar (bottom). A
        // JewelComposePanel doesn't report its Compose content height to Swing, so we pin explicit
        // heights (else the bars are sized 0 / one row and overflow the grid until a window resize).
        val fb = createFilterBar(
            filterQuery,
            filterFocus,
            chrome,
            onQueryChanged = { filterDebounce.restart() },
            onEnter = {
                filterDebounce.stop()
                activeSheet?.applyFilter(filterQuery.text.toString())
                activeSheet?.focusGrid()
            },
            onClearChip = { col -> activeSheet?.clearColumnFilter(col) },
            onClearAll = {
                filterQuery.edit { replace(0, length, "") }
                activeSheet?.clearAllFilters()
            },
        ).apply { preferredSize = Dimension(0, JBUI.scale(FILTER_BAR_H)) }
        filterBar = fb
        val sb = createStatusBar(chrome).apply { preferredSize = Dimension(0, JBUI.scale(STATUS_BAR_H)) }
        val chromePanel = JPanel(BorderLayout()).apply {
            add(fb, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            add(sb, BorderLayout.SOUTH)
        }
        loadingPanel.add(chromePanel, BorderLayout.CENTER)
        built.firstOrNull()?.let { setActiveSheet(it) }
        loadingPanel.revalidate()
        loadingPanel.repaint()
        revalidateChrome() // the Compose bars size up only after their first frame — re-lay-out then
    }

    /** Point the shared filter + status bars at [panel] (the active tab). */
    private fun setActiveSheet(panel: SheetPanel) {
        if (activeSheet === panel) return
        activeSheet?.onChrome = null
        activeSheet = panel
        panel.onChrome = { data ->
            val prev = chrome.value
            chrome.value = data
            // The filter bar grows a row when chips appear (and shrinks when they go). Re-pin its height
            // and re-lay-out, else the chips row overflows the grid until the window is resized.
            if (data.chips.isEmpty() != prev.chips.isEmpty()) {
                filterBar?.preferredSize = Dimension(0, JBUI.scale(if (data.chips.isEmpty()) FILTER_BAR_H else FILTER_BAR_CHIPS_H))
                revalidateChrome()
            }
        }
        // Show this sheet's own filter query in the shared bar, then refresh its status line.
        filterQuery.edit { replace(0, length, panel.filterText()) }
        panel.refreshStatus()
    }

    /** Re-lay-out the editor after the Compose chrome's size settles (next EDT tick). */
    private fun revalidateChrome() {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed) {
                loadingPanel.revalidate()
                loadingPanel.repaint()
            }
        }, ModalityState.any())
    }

    private fun focusSharedFilter() {
        try {
            filterFocus.requestFocus()
        } catch (ignored: Exception) {
        }
    }

    /**
     * Switch to the [sheetName] tab and select the row whose [columnHeader] field == [value]. Called by
     * the relationship graph (double-click a node). Returns false until the sheets are built / the row
     * exists, so the caller can retry while the file is still streaming open.
     */
    fun revealSheetRow(sheetName: String, columnHeader: String, value: String): Boolean {
        if (disposed) return true
        val idx = panels.indexOfFirst { it.model.sheetName == sheetName }
        if (idx < 0) return false
        tabbedPane?.let { if (it.selectedIndex != idx) it.selectedIndex = idx } // ChangeListener → setActiveSheet
        return panels[idx].revealRow(columnHeader, value)
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
        loadingPanel.removeAll()
        loadingPanel.add(JBLabel("Cannot open spreadsheet: $message", SwingConstants.CENTER), BorderLayout.CENTER)
        loadingPanel.revalidate()
        loadingPanel.repaint()
    }


    override fun getComponent(): JComponent = loadingPanel
    override fun getPreferredFocusedComponent(): JComponent? = firstTable
    override fun getName(): String = "Spreadsheet"
    override fun getFile(): VirtualFile = file
    override fun isModified(): Boolean = false // read-only viewer
    override fun isValid(): Boolean = file.isValid
    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
    override fun setState(state: FileEditorState) {}

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.removePropertyChangeListener(listener)

    override fun dispose() {
        disposed = true
        filterDebounce.stop()
    }
}
