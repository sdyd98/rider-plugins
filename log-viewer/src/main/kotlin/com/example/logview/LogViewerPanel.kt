package com.example.logview

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import com.example.logview.rules.HighlightRulesDialog
import com.example.logview.rules.HighlightRulesStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.util.regex.Pattern
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.Timer
import javax.swing.event.ListSelectionListener
import javax.swing.table.TableRowSorter

private val LOG = logger<LogViewerPanel>()

private const val FILTER_BAR_H = 82 // command/query row + severity-pill row
private const val STATUS_BAR_H = 24

// Format auto-detection over the loaded head (first open of a source with nothing remembered).
private const val DETECT_SAMPLE = 200
private const val DETECT_MIN_LINES = 20
private const val DETECT_THRESHOLD_PCT = 60

/**
 * At most this many parsed batches may be in flight to the EDT at once; the READER thread blocks
 * past that. Without the bound, a fast reader floods the invokeLater queue — a 3 GB file piled up
 * 1,700+ batches (3.5M ParsedRows, hundreds of MB) and GC-thrashed the IDE into a freeze.
 */
private const val MAX_INFLIGHT_BATCHES = 4

/**
 * The reusable log view: a virtualized [JBTable] grid (one row = one source line) wrapped in the
 * Compose filter/status chrome, with a severity gutter. Used both by the [LogFileEditor] (a local
 * file opened in the editor area) and by the tool window's live-tail tabs.
 *
 * Lines stream in from a [LogReader] off the EDT; the model always tails appended lines, and the
 * **Follow** toggle controls whether the view sticks to the bottom as new lines arrive. A filter-only
 * [TableRowSorter] hides lines by level / regex / fold, exactly like the xlsx grid.
 */
class LogViewerPanel(
    private val project: Project?,
    private val sourceLabel: String,
    private val makeReader: (java.nio.charset.Charset) -> LogReader, // rebuilt when the charset changes
    followByDefault: Boolean,
    private val onExitLeft: () -> Unit = {}, // moving left past the first column (e.g. into the sources tree)
    private val onSwitchTab: (dir: Int) -> Unit = {}, // gt / gT — switch session tab
    /** Stable identity of this source for per-source prefs (local path or user@host:path).
     *  The tool window's sourceLabel already IS that key; the file editor passes file.path. */
    private val sourceKey: String = sourceLabel,
) : Disposable {

    val model = LogTableModel()

    // ---- per-source memory (SourcePrefsStore): last-used line format + charset for THIS source ----

    /** Remembered format name: null = nothing remembered (fall back to the global active format),
     *  "" = explicit "없음 (원본 그대로)", else a LineFormatStore library name. */
    private var sourceFormatName: String? = SourcePrefsStore.getInstance().formatFor(sourceKey)
    private var sourceFormat: List<LineFormat>? = compileSaved(sourceFormatName)

    /** Formats to parse THIS source with: per-source memory first, then the global active format. */
    private fun effectiveFormats(): List<LineFormat> = sourceFormat ?: LineFormatStore.getInstance().active()

    private fun compileSaved(name: String?): List<LineFormat>? = when {
        name == null -> null
        name.isEmpty() -> emptyList()
        else -> LineFormatStore.getInstance().library().firstOrNull { it.name == name }
            ?.let { LineFormat.of(it.pattern) }?.takeIf { it.valid }?.let { listOf(it) }
    }

    // The decoding charset for this source; changing it re-reads from scratch (see reopenWith).
    private var charset: java.nio.charset.Charset =
        SourcePrefsStore.getInstance().charsetFor(sourceKey)
            ?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }
            ?: LogCharsets.DEFAULT
    private var reader: LogReader = makeReader(charset)
    // Bumped on every reopen (charset change); batches tagged with a stale generation are dropped so a
    // superseded reader can't append into the freshly-cleared model.
    @Volatile private var readerGeneration = 0

    private val table = object : JBTable(model) {
        // AUTO_RESIZE_OFF already makes this false; explicit for clarity. The Message column is sized to
        // at least the viewport width (see fitColumnWidth), so rows fill the width with no empty-space scrollbar.
        override fun getScrollableTracksViewportWidth(): Boolean = false
    }.apply {
        setShowGrid(false)
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        rowSelectionAllowed = true
        columnSelectionAllowed = false
        cellSelectionEnabled = false
        setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION) // contiguous range for vim visual mode
        intercellSpacing = Dimension(0, 0)
        background = JBColor.background()
    }

    // Search/highlight state shared by the row filter (hide) and the renderer (highlight + bold).
    @Volatile private var searchPattern: Pattern? = null
    private var regexValid = true
    private var queryText = ""
    private var useRegex = true        // .* toggle — when off, the query is a literal substring
    private var caseSensitive = false  // Aa toggle
    private val enabledLevels: MutableSet<LogLevel> = (LogLevel.REAL + LogLevel.OTHER).toMutableSet()

    private val renderer = LogCellRenderer(
        model,
        rules = { HighlightRulesStore.getInstance().rules() },
        searchPattern = { searchPattern },
        messageCursor = { vim.messageCursorIndex() },
        messageSelection = { vim.messageSelectionRange() },
    )

    private val gutter = LogGutter(table, model)
    private val scrollPane = JBScrollPane(table)
    private val heatmap = LogHeatmap(table, model) { modelRow -> seekToModelRow(modelRow) }

    private var following = followByDefault
    @Volatile private var streaming = true
    @Volatile private var tailing = false
    private var statusError: String? = null // a read failure to surface in the status bar; null = none
    private var maxLineWidth = JBUI.scale(200) // widest measured line; the column is max(this, viewport width)

    // Display options (⚙ menu): raw (unparsed) view, long-line handling. (Density is always comfortable.)
    private var rawMode = false    // show the original whole line instead of the Time|Level|Message split
    private var truncateLines = false
    private var centerPanel: JPanel? = null
    private var filterBarComp: JComponent? = null

    // Right-hand line-detail drawer (Compose/Jewel; toggled in the ⚙ menu).
    private val detail = ComposeLogDetail()
    private var detailVisible = false
    private var centerSplit: com.intellij.ui.OnePixelSplitter? = null

    // ---- Compose chrome ----
    private val filterQuery = TextFieldState()
    private val filterFocus = FocusRequester()
    private val chrome = mutableStateOf(LogChromeData())
    private val filterDebounce = Timer(150) { applyFilter(filterQuery.text.toString()) }.apply { isRepeats = false }

    // Filter-only sorter: sorting is disabled on EVERY column so the view always keeps model
    // (chronological) order — which also makes `sortsOnUpdates = true` safe: appends can never
    // reshuffle, and a rowsUpdated event re-runs the filter for JUST the updated rows. That is what
    // lets toggleFold re-filter one block instead of rebuilding the filter over the whole model.
    private val sorter = TableRowSorter(model).apply {
        sortsOnUpdates = true
        for (c in 0 until model.columnCount) setSortable(c, false)
    }

    // vim search marks store level-jump state; the controller calls back into this panel.
    private val vim = VimLogController(
        table = table,
        model = model,
        onFocusFilter = { focusFilter() },
        onToggleFold = { toggleFoldAtCursor() },
        onFoldAll = { all -> foldAll(all) },
        onJumpLevel = { dir, level -> jumpToLevel(dir, level) },
        onSearchRepeat = { dir -> repeatSearch(dir) },
        onSearchWord = { word, dir -> searchWord(word, dir) },
        onShowJson = { showJsonAtCursor() },
        onGotoTime = { gotoTime() },
        onEscape = { clearSearchQuery() },
        onExitLeft = onExitLeft,
        onSwitchTab = onSwitchTab,
        onOpenSource = { openSourceAtCursor() },
    )

    val component: JComponent = JPanel(BorderLayout())

    // The sorter is attached only AFTER the initial stream completes (or on first filter/fold use):
    // with a filter installed, every batch insert makes DefaultRowSorter rebuild O(model) index
    // arrays — over a 3 GB / 33M-line load that was the EDT's main cost and let the reader outrun
    // it. Detached, appends are plain O(batch) model inserts. (Same pattern as the xlsx grid.)
    private var sorterAttached = false

    private fun ensureSorterAttached() {
        if (sorterAttached) return
        sorterAttached = true
        table.rowSorter = sorter
        sorter.setRowFilter(buildRowFilter())
    }

    /** (Re)install the row filter, attaching the sorter first if the initial stream deferred it. */
    private fun refilter() {
        if (!sorterAttached) ensureSorterAttached() // attaching already installs the current filter
        else sorter.rowFilter = buildRowFilter()
    }

    init {
        table.rowHeight = renderer.rowHeight()
        setupColumns()
        scrollPane.setRowHeaderView(gutter)
        scrollPane.border = BorderFactory.createEmptyBorder()
        gutter.onToggleFold = { modelRow -> toggleFold(modelRow) }
        vim.setEnabled(true)

        model.onTrim = { dropped -> vim.shiftMarks(dropped) }
        model.addTableModelListener {
            gutter.revalidate(); gutter.repaint()
            heatmap.repaint()
            if (streaming) pushChrome()
        }
        // The heatmap's viewport window follows scrolling; its marks follow filter changes (any
        // rowFilter reassignment or rowsUpdated lands here as a RowSorterEvent).
        scrollPane.verticalScrollBar.addAdjustmentListener { heatmap.repaint() }
        sorter.addRowSorterListener { heatmap.repaint() }
        table.selectionModel.addListSelectionListener(ListSelectionListener { e ->
            if (!e.valueIsAdjusting) { pushChrome(); updateDetail() }
            gutter.repaint()
        })
        // Re-fit the content column to the viewport width when the panel is resized (e.g. a narrow dock).
        scrollPane.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = fitColumnWidth()
        })
        // Double-click a line with a source reference (stack frame) → open it in the editor.
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0) openSourceAtViewRow(row)
            }
        })

        buildChrome()
        pushChrome()
    }

    private fun buildChrome() {
        val actions = LogChromeActions(
            onQueryChanged = { filterDebounce.restart() },
            onEnter = {
                filterDebounce.stop()
                applyFilter(filterQuery.text.toString())
                focusGrid()
            },
            onToggleLevel = { level -> toggleLevel(level) },
            onAllLevels = { setAllLevels() },
            onErrorsOnly = { errorsOnly() },
            onToggleFollow = { toggleFollow() },
            onToggleRegex = { toggleRegex() },
            onToggleCase = { toggleCase() },
            onClear = { clearFilters() },
            onOpenRules = { openRulesDialog() },
            onDisplayMenu = { showDisplayMenu() },
            onHelp = { showHelp() },
        )
        val filterBar = createLogFilterBar(filterQuery, filterFocus, chrome, actions).apply {
            preferredSize = Dimension(0, JBUI.scale(FILTER_BAR_H))
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0) // divider under the toolbar
        }
        filterBarComp = filterBar

        val statusBar = createLogStatusBar(chrome, onCancelLoad = { reader.cancelInitial() }).apply {
            preferredSize = Dimension(0, JBUI.scale(STATUS_BAR_H))
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0) // divider above the status bar
        }

        val split = com.intellij.ui.OnePixelSplitter(false, 0.72f).apply {
            // The grid plus the error-stripe heatmap on its right edge.
            firstComponent = JPanel(BorderLayout()).apply {
                add(scrollPane, BorderLayout.CENTER)
                add(heatmap, BorderLayout.EAST)
            }
            secondComponent = null // the detail drawer is attached on demand
        }
        centerSplit = split
        val center = JPanel(BorderLayout()).apply {
            add(split, BorderLayout.CENTER)
        }
        centerPanel = center
        component.add(filterBar, BorderLayout.NORTH)
        component.add(center, BorderLayout.CENTER)
        component.add(statusBar, BorderLayout.SOUTH)
    }

    // ---- Display options (⚙ menu) ----

    private var displayPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    private fun showDisplayMenu() {
        val toggles = listOf(
            DisplayToggle("원본 로그 보기", "파싱 없이 원문 그대로", rawMode) { setRawMode(it) },
            DisplayToggle("긴 줄 자르기", "가로 스크롤 끄고 잘라 보기", truncateLines) { setTruncate(it) },
            DisplayToggle("디테일 패널", "선택한 줄의 상세를 오른쪽에", detailVisible) { setDetailVisible(it) },
        )
        // The format label reflects what THIS source is parsed with (per-source memory beats global).
        val formatLabel = when {
            sourceFormatName == null -> LineFormatStore.getInstance().activeName()
            sourceFormatName!!.isEmpty() -> null // explicit "없음" remembered for this source
            else -> sourceFormatName
        }
        val choices = listOf(
            DisplayChoice("인코딩", LogCharsets.label(charset)) { showCharsetChooser() },
            DisplayChoice("줄 형식", formatLabel ?: "자동") { showFormatSettings() },
        )
        val panel = createDisplayOptionsPanel(toggles, choices)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        displayPopup = popup
        // Drop it from right under the ⚙ icon (right-aligned to where the user clicked).
        val mouse = java.awt.MouseInfo.getPointerInfo()?.location
        if (mouse != null) {
            popup.showInScreenCoordinates(component, Point(mouse.x - JBUI.scale(280), mouse.y + JBUI.scale(12)))
        } else {
            val anchor = filterBarComp ?: component
            popup.show(RelativePoint(anchor, Point(anchor.width - JBUI.scale(290), anchor.height)))
        }
    }

    private var charsetPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    /** Pick the decoding charset via a Compose/Jewel popup (CP949/EUC-KR Korean logs) and re-read. */
    private fun showCharsetChooser() {
        displayPopup?.cancel() // close the ⚙ popup first so the picker isn't nested under it
        val panel = createCharsetMenu(LogCharsets.OPTIONS, charset) { cs ->
            charsetPopup?.cancel()
            reopenWith(cs)
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        charsetPopup = popup
        val mouse = java.awt.MouseInfo.getPointerInfo()?.location
        if (mouse != null) {
            popup.showInScreenCoordinates(component, Point(mouse.x - JBUI.scale(250), mouse.y + JBUI.scale(12)))
        } else {
            popup.showInCenterOf(component)
        }
    }

    /** ⚙ → "줄 형식": pick a saved format or build one with the region picker; applying re-reads the grid. */
    private fun showFormatSettings() {
        displayPopup?.cancel()
        val sample = (0 until minOf(model.loadedRowCount(), 60)).map { model.rawAt(it) }
        var popupRef: com.intellij.openapi.ui.popup.JBPopup? = null
        // onPreview applies a format LIVE to the real grid (token-building / picking a saved one).
        val panel = createLineFormatSettings(sample, onPreview = { fmt -> previewFormatLive(fmt) }) { popupRef?.cancel() }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(false) // accidental outside-clicks shouldn't close; use 닫기 / Esc
            .setTitle("줄 형식")
            .createPopup()
        popupRef = popup
        // On close, drop the live preview and settle the grid on the saved active format.
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) = endFormatPreview()
        })
        // Anchor near the top so the grid below stays visible while the preview updates live.
        val anchor = runCatching { component.locationOnScreen }.getOrNull()
        if (anchor != null) {
            popup.showInScreenCoordinates(component, Point(anchor.x + JBUI.scale(16), anchor.y + JBUI.scale(16)))
        } else {
            popup.showInCenterOf(component)
        }
    }

    /** Re-read the source under [cs] (charset change). No-op if unchanged. Remembered per source. */
    private fun reopenWith(cs: java.nio.charset.Charset) {
        if (cs == charset) return
        charset = cs
        SourcePrefsStore.getInstance().rememberCharset(sourceKey, cs.name())
        reread()
    }

    /** Re-read the source with the current charset — e.g. after the line-format rules change. */
    fun reparse() = reread()

    /** Close the old reader, clear the grid, rebuild, and stream again (charset/format change). */
    private fun reread() {
        val old = reader
        readerGeneration++ // invalidate any in-flight batches from the old reader
        tailing = false
        streaming = true
        statusError = null
        // Detach the sorter for the re-stream (same reason as the initial load); reattached on completion.
        table.rowSorter = null
        sorterAttached = false
        model.clear()
        reader = makeReader(charset) // new reader acquires its host BEFORE old releases → SSH session stays warm
        pushChrome()
        // Close the previous reader OFF the EDT: RemoteLogReader.close() does SSH channel/session
        // disconnects (network I/O) that would otherwise freeze the UI thread.
        ApplicationManager.getApplication().executeOnPooledThread { runCatching { old.close() } }
        start()
    }

    /** Toggle between the parsed Time|Level|Message columns and the original whole-line view. */
    private fun setRawMode(on: Boolean) {
        if (rawMode == on) return
        rawMode = on
        model.rawMode = on
        applyColumnLayout()
        model.fireTableDataChanged() // the Message cells switch between the message and the full line
        gutter.revalidate(); gutter.repaint(); table.repaint()
    }

    private fun setTruncate(on: Boolean) {
        truncateLines = on
        fitColumnWidth()
        table.repaint()
    }

    /** Show/hide the right-hand line-detail drawer. */
    private fun setDetailVisible(on: Boolean) {
        detailVisible = on
        centerSplit?.secondComponent = if (on) detail.component else null
        centerSplit?.revalidate(); centerSplit?.repaint()
        if (on) updateDetail()
    }

    private fun updateDetail() {
        if (!detailVisible) return
        val r = table.selectedRow
        val mr = if (r in 0 until table.rowCount) table.convertRowIndexToModel(r) else -1
        val line = if (mr in 0 until model.rowCount) model.lineAt(mr) else null // guard model row (stale during clear)
        detail.show(line)
    }

    // ---- Lifecycle ----

    /** Begin reading (off-EDT). Streams the initial content, then tails appended lines. */
    fun start() {
        val gen = readerGeneration
        val r = reader
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                r.readInitial { batch -> onReaderBatch(batch, gen) }
            } catch (e: Throwable) {
                LOG.warn("Log initial read failed: $sourceLabel — ${e.message}")
                onEdt { if (gen == readerGeneration) setStatusError(e) }
            }
            onEdt {
                if (gen != readerGeneration) return@onEdt // superseded by a newer reopen (charset change)
                streaming = false
                ensureSorterAttached() // deferred during the initial stream — appends stay O(batch)
                sizeContentColumn()
                pushChrome()
                ensureFollow() // we always tail appended lines; the Follow toggle only controls auto-scroll
                autoDetectFormat()
            }
        }
    }

    private fun ensureFollow() {
        if (tailing) return
        tailing = true
        val gen = readerGeneration
        reader.startTail(
            onAppend = { batch -> onReaderBatch(batch, gen) },
            onError = { e ->
                onEdt {
                    LOG.info("Log tail ended: $sourceLabel — ${e.message}")
                    if (gen == readerGeneration) setStatusError(e)
                }
            },
            onState = { st -> onEdt { if (gen == readerGeneration) onTailState(st) } },
        )
    }

    /** Surface remote-tail reconnects in the status bar (the reader retries with backoff by itself). */
    private fun onTailState(st: TailState) {
        when (st) {
            TailState.RECONNECTING -> {
                statusError = "원격 연결 끊김 — 재연결 중… (끊긴 동안의 줄은 누락될 수 있음)"
                pushChrome()
            }
            TailState.LIVE -> if (statusError != null) {
                statusError = null
                pushChrome()
            }
        }
    }

    /**
     * Reader callback — runs on the reader's OFF-EDT thread. Do the heavy per-line parsing (5 regexes +
     * ANSI strip) HERE, off the UI thread, then hop to the EDT to insert. This keeps the grid responsive
     * during large initial loads and charset re-reads (re-parsing 50k lines on the EDT would freeze it).
     */
    // Backpressure between the reader thread and the EDT (see MAX_INFLIGHT_BATCHES).
    private val inflight = java.util.concurrent.Semaphore(MAX_INFLIGHT_BATCHES)

    private fun onReaderBatch(batch: List<String>, gen: Int) {
        if (gen != readerGeneration || batch.isEmpty()) return
        // previewFormat (transient, while the 줄 형식 picker is open) wins; then this source's
        // remembered format; then the global active format.
        val formats = previewFormat ?: effectiveFormats()
        val rows = batch.map { parseLogRow(it, formats) }
        inflight.acquire() // reader thread — blocks until the EDT digests an earlier batch
        appendOnEdt(rows, gen)
    }

    /** Non-null while the 줄 형식 picker previews a format live on the grid (overrides the saved active one). */
    @Volatile private var previewFormat: List<LineFormat>? = null

    /** Re-parse the already-loaded lines under [formats] (off the EDT) and swap them in atomically. */
    private fun applyFormatToLoaded(formats: List<LineFormat>) {
        val raws = model.rawSnapshot()
        val gen = readerGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val rows = raws.map { parseLogRow(it, formats) }
            onEdt {
                if (gen != readerGeneration) return@onEdt
                model.replaceAllParsed(rows)
                syncThreadColumn() // show/hide the Thread column for the new format
                sizeContentColumn()
            }
        }
    }

    /**
     * First open of a source with nothing remembered: try every saved format against the loaded head
     * and adopt the best one when it matches convincingly (≥[DETECT_THRESHOLD_PCT]% of the sample).
     * The scoring runs off the EDT; a hit is remembered so the next open skips detection entirely.
     */
    private fun autoDetectFormat() {
        if (SourcePrefsStore.getInstance().formatFor(sourceKey) != null) return // remembered — nothing to detect
        val store = LineFormatStore.getInstance()
        val candidates = store.library().mapNotNull { s ->
            LineFormat.of(s.pattern).takeIf { it.valid }?.let { s.name to it }
        }
        if (candidates.isEmpty()) return
        val sample = (0 until minOf(model.loadedRowCount(), DETECT_SAMPLE))
            .map { model.rawAt(it) }.filter { it.isNotBlank() }
        if (sample.size < DETECT_MIN_LINES) return
        val gen = readerGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val scored = candidates.map { (name, fmt) -> Triple(name, fmt, sample.count { fmt.apply(it) != null }) }
            val best = scored.maxByOrNull { it.third } ?: return@executeOnPooledThread
            if (best.third * 100 < sample.size * DETECT_THRESHOLD_PCT) return@executeOnPooledThread
            onEdt {
                if (gen != readerGeneration) return@onEdt
                if (SourcePrefsStore.getInstance().formatFor(sourceKey) != null) return@onEdt // user chose meanwhile
                val wasEffective = effectiveFormats()
                sourceFormatName = best.first
                sourceFormat = listOf(best.second)
                SourcePrefsStore.getInstance().rememberFormat(sourceKey, best.first)
                LOG.info("Auto-detected line format '${best.first}' for $sourceKey (${best.third}/${sample.size} lines)")
                // Already parsed with this format when it was the global active — skip the re-parse then.
                if (wasEffective.map { it.source } != listOf(best.second.source)) applyFormatToLoaded(effectiveFormats())
            }
        }
    }

    private var threadColShown = false

    /** Re-lay-out columns only when the Thread column's visibility flips (cheap; preserves manual resizes). */
    private fun syncThreadColumn() {
        if (model.hasThread() != threadColShown) {
            threadColShown = model.hasThread()
            applyColumnLayout()
        }
    }

    /** Live-preview [fmt] on the real grid (null → the currently applied format). Transient — not persisted. */
    fun previewFormatLive(fmt: LineFormat?) {
        val formats = if (fmt != null) listOf(fmt) else effectiveFormats()
        previewFormat = formats
        applyFormatToLoaded(formats)
    }

    /**
     * End the live preview: settle the grid on the format the picker left active, and REMEMBER that
     * choice for this source (SourcePrefsStore) so reopening the same file restores it — the picker
     * dialog is the per-source format choice.
     */
    fun endFormatPreview() {
        previewFormat = null
        val store = LineFormatStore.getInstance()
        sourceFormatName = store.activeName() ?: "" // "" = explicit 없음
        sourceFormat = store.active().takeIf { it.isNotEmpty() } ?: emptyList()
        SourcePrefsStore.getInstance().rememberFormat(sourceKey, sourceFormatName)
        applyFormatToLoaded(effectiveFormats())
    }

    private fun appendOnEdt(rows: List<ParsedRow>, gen: Int) = onEdt {
        try {
            if (gen != readerGeneration) return@onEdt // stale batch from a superseded reader (charset change)
            val atBottom = isViewAtBottom()
            model.appendParsed(rows)
            if (following && atBottom) scrollToBottom()
            syncThreadColumn()
            sizeContentColumnFor(rows)
            // Data is flowing again → a prior initial-read error is stale; clear it so the bar shows LIVE.
            if (rows.isNotEmpty() && statusError != null) statusError = null
            if (!streaming) pushChromeThrottled()
        } finally {
            inflight.release() // frees the reader to hand over the next batch (even for stale batches)
        }
    }

    // ---- Filtering ----

    private fun applyFilter(query: String) {
        if (query == queryText) return
        queryText = query
        recompileSearch()
        refilter()
        table.repaint()
        pushChrome()
    }

    private fun recompileSearch() {
        val q = queryText.trim()
        // UNICODE_CHARACTER_CLASS so \b / \w honor non-ASCII letters — without it `\b한글\b` (built by the
        // * / # word search) matches nothing because \b defaults to the ASCII word class. UNICODE_CASE
        // makes case-insensitive matching Unicode-aware too.
        val flags = Pattern.UNICODE_CHARACTER_CLASS or
            (if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        searchPattern = when {
            q.isEmpty() -> null
            !useRegex -> Pattern.compile(Pattern.quote(q), flags)
            else -> runCatching { Pattern.compile(q, flags) }.getOrNull()
        }
        // In literal mode the pattern always compiles; in regex mode an invalid pattern falls back to literal contains.
        regexValid = q.isEmpty() || !useRegex || searchPattern != null
    }

    // The filter semantics live in LogRowFilter (headless-testable); this just snapshots the state.
    private fun buildRowFilter(): RowFilter<LogTableModel, Int> =
        LogRowFilter(model, queryText, searchPattern, ignoreCase = !caseSensitive, enabledLevels = enabledLevels)

    private fun toggleLevel(level: LogLevel) {
        if (!enabledLevels.add(level)) enabledLevels.remove(level)
        refilter()
        table.repaint(); gutter.repaint()
        pushChrome()
    }

    private fun clearFilters() {
        filterQuery.edit { replace(0, length, "") }
        queryText = ""
        searchPattern = null
        regexValid = true
        enabledLevels.clear()
        enabledLevels.addAll(LogLevel.REAL + LogLevel.OTHER)
        refilter()
        table.repaint(); pushChrome()
    }

    private fun toggleRegex() {
        useRegex = !useRegex
        recompileSearch()
        refilter()
        table.repaint(); pushChrome()
    }

    private fun toggleCase() {
        caseSensitive = !caseSensitive
        recompileSearch()
        refilter()
        table.repaint(); pushChrome()
    }

    private fun setAllLevels() {
        enabledLevels.clear()
        enabledLevels.addAll(LogLevel.REAL + LogLevel.OTHER)
        refilter()
        table.repaint(); gutter.repaint(); pushChrome()
    }

    /** One-click "errors only": show just ERROR (the highest-signal triage view). */
    private fun errorsOnly() {
        enabledLevels.clear()
        enabledLevels.add(LogLevel.ERROR)
        refilter()
        table.repaint(); gutter.repaint(); pushChrome()
    }

    private fun showHelp() = showLogHelpPopup(table)

    private fun openRulesDialog() {
        if (HighlightRulesDialog(project).showAndGet()) {
            table.repaint()
        }
    }

    // ---- Follow / scroll ----

    private fun toggleFollow() {
        following = !following
        if (following) scrollToBottom()
        pushChrome()
    }

    private fun isViewAtBottom(): Boolean {
        val bar = scrollPane.verticalScrollBar
        return bar.value + bar.visibleAmount >= bar.maximum - table.rowHeight
    }

    private fun scrollToBottom() {
        val last = table.rowCount - 1
        if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true))
    }

    private fun seekToModelRow(modelRow: Int) {
        val view = nearestVisibleView(modelRow)
        if (view >= 0) selectView(view)
    }

    private fun selectView(viewRow: Int) {
        if (viewRow in 0 until table.rowCount) {
            table.changeSelection(viewRow, 0, false, false)
            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
        }
    }

    /** Map a model row to the nearest currently-visible view row (it may be filtered/folded out). */
    private fun nearestVisibleView(modelRow: Int): Int {
        var m = modelRow
        while (m < model.rowCount) {
            val v = runCatching { table.convertRowIndexToView(m) }.getOrDefault(-1)
            if (v >= 0) return v
            m++
        }
        m = modelRow
        while (m >= 0) {
            val v = runCatching { table.convertRowIndexToView(m) }.getOrDefault(-1)
            if (v >= 0) return v
            m--
        }
        return -1
    }

    // ---- Folding ----

    private fun toggleFoldAtCursor() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        toggleFold(table.convertRowIndexToModel(viewRow))
    }

    private fun toggleFold(modelRow: Int) {
        val blockStart = model.toggleFold(modelRow)
        if (blockStart < 0) return
        ensureSorterAttached() // fold hiding is the row filter's job (deferred during streaming)
        // Re-filter just this block's rows (sortsOnUpdates) — NOT a whole-model filter rebuild.
        model.notifyBlockUpdated(blockStart)
        // folding changes the visible row count, so the gutter's preferred height must be re-queried.
        gutter.revalidate(); gutter.repaint()
        // keep the block-start selected/visible
        seekToModelRow(blockStart)
    }

    private fun foldAll(all: Boolean) {
        if (all) model.foldAll() else model.unfoldAll()
        refilter()
        gutter.revalidate(); gutter.repaint(); table.repaint()
    }

    // ---- Level jump (]e / [e) and search repeat (n / N) ----

    // Binary-search the model's per-level row index, then skip candidates the filter/fold hides, so
    // the cursor only ever lands on a line the user can actually see. (Scanning every view row per
    // keypress was an O(model) EDT stall on 200k+ line logs.)
    private fun jumpToLevel(dir: Int, level: LogLevel) {
        val n = table.rowCount
        if (n == 0) return
        val v = table.selectedRow.coerceIn(0, n - 1)
        var m = table.convertRowIndexToModel(v)
        while (true) {
            m = model.nextLevelRow(level, m, dir)
            if (m < 0) return
            val cand = runCatching { table.convertRowIndexToView(m) }.getOrDefault(-1)
            if (cand >= 0) { selectView(cand); return }
        }
    }

    /** Esc step: clear the active search query (keeps level filters). Returns true if it cleared one. */
    private fun clearSearchQuery(): Boolean {
        if (queryText.isBlank()) return false
        filterQuery.edit { replace(0, length, "") }
        queryText = ""
        searchPattern = null
        regexValid = true
        refilter()
        table.repaint(); pushChrome()
        return true
    }

    /** `*`/`#`: filter+search the whole word under the cursor and jump to the next/prev occurrence. */
    private fun searchWord(word: String, dir: Int) {
        if (word.isBlank()) return
        useRegex = true // \b word boundary needs regex mode (the word itself is alnum/_ — no escaping)
        val q = "\\b$word\\b"
        filterQuery.edit { replace(0, length, q) }
        // Apply directly rather than via applyFilter: applyFilter early-returns when the query text is
        // unchanged, which would skip recompileSearch after we flipped useRegex and leave a stale
        // (literal-quoted) pattern from a prior search of the same word.
        queryText = q
        recompileSearch()
        refilter()
        table.repaint()
        pushChrome()
        repeatSearch(dir)
        focusGrid()
    }

    private fun repeatSearch(dir: Int) {
        val q = queryText.trim()
        if (q.isEmpty()) return
        val pat = searchPattern // null when the query isn't a valid regex — fall back to a literal contains
        val n = table.rowCount
        if (n == 0) return
        var v = table.selectedRow.coerceAtLeast(0)
        repeat(n) {
            v = (v + dir + n) % n
            val raw = model.rawAt(table.convertRowIndexToModel(v))
            val hit = if (pat != null) pat.matcher(raw).find() else raw.contains(q, ignoreCase = !caseSensitive)
            if (hit) { selectView(v); return }
        }
    }

    // ---- JSON pretty-print + time jump ----

    // ---- Source jump (Enter / double-click on a stack frame) ----

    private fun openSourceAtCursor() {
        val r = table.selectedRow
        if (r in 0 until table.rowCount) openSourceAtViewRow(r)
    }

    private fun openSourceAtViewRow(viewRow: Int): Boolean {
        val mr = table.convertRowIndexToModel(viewRow)
        if (mr !in 0 until model.rowCount) return false
        // Same text the Message cell renders, so the parsed link matches the underline the user sees.
        val text = model.getValueAt(mr, COL_MSG) as? String ?: return false
        val link = LogSourceLink.find(text) ?: return false
        return LogSourceLink.open(project, link)
    }

    private fun showJsonAtCursor() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        val raw = model.rawAt(table.convertRowIndexToModel(viewRow))
        LogStructure.showJsonPopup(raw, table)
    }

    private fun gotoTime() {
        val target = LogStructure.askTimestamp(project) ?: return
        val m = LogStructure.firstAtOrAfter(model, target)
        if (m >= 0) seekToModelRow(m)
    }

    // ---- Column width + status ----

    private fun sizeContentColumn() {
        if (model.columnCount == 0) return
        var w = maxLineWidth
        val sample = minOf(model.loadedRowCount(), 500)
        for (i in 0 until sample) w = maxOf(w, renderer.lineWidth(if (rawMode) model.rawAt(i) else model.messageAt(i)))
        maxLineWidth = w
        fitColumnWidth()
    }

    // Upper bound of one glyph's advance (CJK-wide) — lets the streaming path skip the real
    // FontMetrics measurement for every line that can't possibly beat the current max width.
    private val maxCharW: Int by lazy { renderer.maxCharWidth() }

    private fun sizeContentColumnFor(rows: List<ParsedRow>) {
        var w = maxLineWidth
        val cap = maxCharW
        val pad = JBUI.scale(24) // lineWidth()'s padding — keep the guard's bound consistent with it
        // This runs on the EDT for EVERY streamed line; measuring each one froze large loads. The
        // length×maxCharWidth bound is conservative (assumes every glyph is double-width), so only
        // genuine new-maximum candidates pay for a real measurement.
        for (p in rows) {
            if (p.raw.length * cap + pad <= w) continue
            w = maxOf(w, renderer.lineWidth(p.raw))
        }
        if (w != maxLineWidth) {
            maxLineWidth = w
            fitColumnWidth()
        }
    }

    /** Install the per-column renderers once, then size the columns for the current view mode. */
    private fun setupColumns() {
        val cm = table.columnModel
        if (cm.columnCount < 4) return
        cm.getColumn(COL_TIME).cellRenderer = LogTimeRenderer(model)
        cm.getColumn(COL_THREAD).cellRenderer = LogTimeRenderer(model) // reuse the muted-text renderer
        cm.getColumn(COL_LEVEL).cellRenderer = LogLevelRenderer(model)
        cm.getColumn(COL_MSG).cellRenderer = renderer
        applyColumnLayout()
    }

    /** Parsed view = fixed Time + (Thread) + Level + flexible Message; raw view = all collapsed but Message. */
    private fun applyColumnLayout() {
        val cm = table.columnModel
        if (cm.columnCount < 4) return
        if (rawMode) {
            for (c in intArrayOf(COL_TIME, COL_THREAD, COL_LEVEL)) cm.getColumn(c).apply { minWidth = 0; maxWidth = 0; preferredWidth = 0 }
        } else {
            cm.getColumn(COL_TIME).apply { minWidth = JBUI.scale(70); maxWidth = JBUI.scale(190); preferredWidth = JBUI.scale(108) }
            // Thread column only takes space when a format actually captures threads.
            if (model.hasThread()) {
                cm.getColumn(COL_THREAD).apply { minWidth = JBUI.scale(48); maxWidth = JBUI.scale(280); preferredWidth = JBUI.scale(120) }
            } else {
                cm.getColumn(COL_THREAD).apply { minWidth = 0; maxWidth = 0; preferredWidth = 0 }
            }
            cm.getColumn(COL_LEVEL).apply { minWidth = JBUI.scale(56); maxWidth = JBUI.scale(120); preferredWidth = JBUI.scale(84) }
        }
        fitColumnWidth()
    }

    /** Size the Message column to fill the remaining width (fill; scroll when content is wider).
     *  Truncate mode: exactly the remaining width so long lines clip instead of adding a horizontal scrollbar. */
    private fun fitColumnWidth() {
        val cm = table.columnModel
        if (cm.columnCount < 4) return
        val fixed = cm.getColumn(COL_TIME).preferredWidth + cm.getColumn(COL_THREAD).preferredWidth + cm.getColumn(COL_LEVEL).preferredWidth
        val viewport = maxOf(scrollPane.viewport.width - fixed, 1)
        val w = if (truncateLines) viewport else maxOf(maxLineWidth, viewport)
        val column = cm.getColumn(COL_MSG)
        if (column.preferredWidth != w) column.preferredWidth = w
    }

    private fun focusFilter() = runCatching { filterFocus.requestFocus() }

    private fun focusGrid() {
        if (table.rowCount > 0 && table.selectedRow < 0) table.changeSelection(0, 0, false, false)
        table.requestFocusInWindow()
    }

    private fun pushChromeThrottled() {
        if (chromePushScheduled) return
        chromePushScheduled = true
        onEdt { chromePushScheduled = false; pushChrome() }
    }

    @Volatile private var chromePushScheduled = false

    private fun pushChrome() {
        val visible = table.rowCount
        val total = model.loadedRowCount()
        val counts = LogLevel.entries.associateWith { model.countOf(it) }
        val viewRow = table.selectedRow
        // Guard the MODEL row too, not just the view row: clear() fires this through a TableModelListener
        // while the row sorter still maps to now-gone model rows → model.lineAt would throw (froze the UI).
        val mr = if (viewRow in 0 until table.rowCount) table.convertRowIndexToModel(viewRow) else -1
        val cursor = if (mr in 0 until model.rowCount) {
            val line = model.lineAt(mr)
            val time = if (line.hasTime) LogStructure.formatTime(line.timestampMillis) + " · " else ""
            "${line.lineNumber}행 · $time${line.level.label}"
        } else {
            "—"
        }
        val filterActive = queryText.isNotBlank() || enabledLevels.size < (LogLevel.REAL.size + 1)
        chrome.value = LogChromeData(
            status = statusError ?: " ",
            visible = visible,
            total = total,
            filterActive = filterActive,
            regexValid = regexValid,
            levelCounts = counts,
            enabledLevels = enabledLevels.toSet(),
            following = following,
            streaming = streaming,
            useRegex = useRegex,
            caseSensitive = caseSensitive,
            cursor = cursor,
            source = sourceLabel,
            progress = if (streaming) reader.initialProgress() else -1f,
            skipped = reader.skippedHeadBytes().takeIf { it > 0 }?.let(::fmtBytes).orEmpty(),
        )
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.0f MB".format(b.toDouble() / (1L shl 20))
        else -> "$b B"
    }

    private fun setStatusError(e: Throwable) {
        streaming = false
        statusError = "오류: ${e.message}"
        pushChrome() // surfaced by createLogStatusBar; survives the follow-up pushChrome in start()
    }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    val preferredFocus: JComponent get() = table

    override fun dispose() {
        filterDebounce.stop()
        runCatching { reader.close() }
    }
}
