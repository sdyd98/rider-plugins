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
) : Disposable {

    val model = LogTableModel()

    // The decoding charset for this source; changing it re-reads from scratch (see reopenWith).
    private var charset: java.nio.charset.Charset = LogCharsets.DEFAULT
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

    private val sorter = TableRowSorter(model).apply {
        sortsOnUpdates = false
        setSortable(0, false)
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
    )

    val component: JComponent = JPanel(BorderLayout())

    init {
        table.rowHeight = renderer.rowHeight()
        table.rowSorter = sorter
        sorter.rowFilter = buildRowFilter()
        setupColumns()
        scrollPane.setRowHeaderView(gutter)
        scrollPane.border = BorderFactory.createEmptyBorder()
        gutter.onToggleFold = { modelRow -> toggleFold(modelRow) }
        vim.setEnabled(true)

        model.onTrim = { dropped -> vim.shiftMarks(dropped) }
        model.addTableModelListener {
            gutter.revalidate(); gutter.repaint()
            if (streaming) pushChrome()
        }
        table.selectionModel.addListSelectionListener(ListSelectionListener { e ->
            if (!e.valueIsAdjusting) { pushChrome(); updateDetail() }
            gutter.repaint()
        })
        // Re-fit the content column to the viewport width when the panel is resized (e.g. a narrow dock).
        scrollPane.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = fitColumnWidth()
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

        val statusBar = createLogStatusBar(chrome).apply {
            preferredSize = Dimension(0, JBUI.scale(STATUS_BAR_H))
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0) // divider above the status bar
        }

        val split = com.intellij.ui.OnePixelSplitter(false, 0.72f).apply {
            firstComponent = scrollPane
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
        val formatCount = LineFormatStore.getInstance().sources().size
        val choices = listOf(
            DisplayChoice("인코딩", LogCharsets.label(charset)) { showCharsetChooser() },
            DisplayChoice("줄 형식", if (formatCount == 0) "자동" else "${formatCount}개") { showFormatSettings() },
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

    private val lineFormatState = androidx.compose.foundation.text.input.TextFieldState()

    /** ⚙ → "줄 형식": edit user line-format rules with a live preview; re-read on close if they changed. */
    private fun showFormatSettings() {
        displayPopup?.cancel()
        val store = LineFormatStore.getInstance()
        // Seed with the current saved formats (none → empty); the region picker writes the generated rule in.
        lineFormatState.edit { replace(0, length, store.sources().joinToString("\n")) }
        val sample = (0 until minOf(model.loadedRowCount(), 8)).map { model.rawAt(it) }
        var popupRef: com.intellij.openapi.ui.popup.JBPopup? = null
        val panel = createLineFormatSettings(lineFormatState, sample) { popupRef?.cancel() }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(false) // accidental outside-clicks shouldn't close + re-read; use 적용하고 닫기 / Esc
            .setTitle("줄 형식")
            .createPopup()
        popupRef = popup
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                val newFormats = lineFormatState.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                if (newFormats != store.sources()) {
                    store.replaceAll(newFormats)
                    reparse()
                }
            }
        })
        popup.showInCenterOf(component)
    }

    /** Re-read the source under [cs] (charset change). No-op if unchanged. */
    private fun reopenWith(cs: java.nio.charset.Charset) {
        if (cs == charset) return
        charset = cs
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
        val line = if (r in 0 until table.rowCount) model.lineAt(table.convertRowIndexToModel(r)) else null
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
                sizeContentColumn()
                pushChrome()
                ensureFollow() // we always tail appended lines; the Follow toggle only controls auto-scroll
            }
        }
    }

    private fun ensureFollow() {
        if (tailing) return
        tailing = true
        val gen = readerGeneration
        reader.startTail(
            onAppend = { batch -> onReaderBatch(batch, gen) },
            onError = { e -> onEdt { LOG.info("Log tail ended: $sourceLabel — ${e.message}") } },
        )
    }

    /**
     * Reader callback — runs on the reader's OFF-EDT thread. Do the heavy per-line parsing (5 regexes +
     * ANSI strip) HERE, off the UI thread, then hop to the EDT to insert. This keeps the grid responsive
     * during large initial loads and charset re-reads (re-parsing 50k lines on the EDT would freeze it).
     */
    private fun onReaderBatch(batch: List<String>, gen: Int) {
        if (gen != readerGeneration || batch.isEmpty()) return
        val formats = LineFormatStore.getInstance().active() // user line-formats, fetched once per batch
        appendOnEdt(batch.map { parseLogRow(it, formats) }, gen)
    }

    private fun appendOnEdt(rows: List<ParsedRow>, gen: Int) = onEdt {
        if (gen != readerGeneration) return@onEdt // stale batch from a superseded reader (charset change)
        val atBottom = isViewAtBottom()
        model.appendParsed(rows)
        if (following && atBottom) scrollToBottom()
        sizeContentColumnFor(rows)
        // Data is flowing again → a prior initial-read error is stale; clear it so the bar shows LIVE.
        if (rows.isNotEmpty() && statusError != null) statusError = null
        if (!streaming) pushChromeThrottled()
    }

    // ---- Filtering ----

    private fun applyFilter(query: String) {
        if (query == queryText) return
        queryText = query
        recompileSearch()
        sorter.rowFilter = buildRowFilter()
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

    private fun buildRowFilter(): RowFilter<LogTableModel, Int> {
        // Hoist the query/pattern/case state once per filter rebuild — every mutator reassigns
        // sorter.rowFilter after changing these, so they're invariant for this instance's lifetime
        // (avoids a per-row String.trim() allocation and repeated @Volatile reads inside include()).
        val q = queryText.trim()
        val pat = searchPattern
        val ignoreCase = !caseSensitive
        return object : RowFilter<LogTableModel, Int>() {
            override fun include(entry: Entry<out LogTableModel, out Int>): Boolean {
                val row = entry.identifier
                if (model.isHiddenByFold(row)) return false
                if (model.rawAt(row).isBlank()) return false // drop blank (newline-only) rows entirely
                if (model.levelAt(row) !in enabledLevels) return false
                if (q.isEmpty()) return true
                // Block-aware search: a multi-line record (a stack trace) is kept intact when ANY of its
                // lines matches, so a search never strands a primary line from its continuations or vice-versa.
                for (m in model.blockRange(row)) {
                    val raw = model.rawAt(m)
                    val hit = if (pat != null) pat.matcher(raw).find() else raw.contains(q, ignoreCase = ignoreCase)
                    if (hit) return true
                }
                return false
            }
        }
    }

    private fun toggleLevel(level: LogLevel) {
        if (!enabledLevels.add(level)) enabledLevels.remove(level)
        sorter.rowFilter = buildRowFilter()
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
        sorter.rowFilter = buildRowFilter()
        table.repaint(); pushChrome()
    }

    private fun toggleRegex() {
        useRegex = !useRegex
        recompileSearch()
        sorter.rowFilter = buildRowFilter()
        table.repaint(); pushChrome()
    }

    private fun toggleCase() {
        caseSensitive = !caseSensitive
        recompileSearch()
        sorter.rowFilter = buildRowFilter()
        table.repaint(); pushChrome()
    }

    private fun setAllLevels() {
        enabledLevels.clear()
        enabledLevels.addAll(LogLevel.REAL + LogLevel.OTHER)
        sorter.rowFilter = buildRowFilter()
        table.repaint(); gutter.repaint(); pushChrome()
    }

    /** One-click "errors only": show just ERROR (the highest-signal triage view). */
    private fun errorsOnly() {
        enabledLevels.clear()
        enabledLevels.add(LogLevel.ERROR)
        sorter.rowFilter = buildRowFilter()
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
        if (model.toggleFold(modelRow) < 0) return
        sorter.rowFilter = buildRowFilter()
        // folding changes the visible row count via a RowSorterEvent (not a TableModelEvent), so the
        // gutter's preferred height must be re-queried explicitly here.
        gutter.revalidate(); gutter.repaint()
        // keep the block-start selected/visible
        seekToModelRow(model.lineAt(modelRow.coerceIn(0, model.rowCount - 1)).blockStart.coerceAtLeast(0))
    }

    private fun foldAll(all: Boolean) {
        if (all) model.foldAll() else model.unfoldAll()
        sorter.rowFilter = buildRowFilter()
        gutter.revalidate(); gutter.repaint(); table.repaint()
    }

    // ---- Level jump (]e / [e) and search repeat (n / N) ----

    // Iterate over VISIBLE (view) rows, not raw model rows, so filtered/folded lines are skipped and
    // the cursor only ever lands on a line the user can actually see.
    private fun jumpToLevel(dir: Int, level: LogLevel) {
        val n = table.rowCount
        if (n == 0) return
        var v = table.selectedRow.coerceAtLeast(0) + dir
        while (v in 0 until n) {
            if (model.levelAt(table.convertRowIndexToModel(v)) == level) { selectView(v); return }
            v += dir
        }
    }

    /** Esc step: clear the active search query (keeps level filters). Returns true if it cleared one. */
    private fun clearSearchQuery(): Boolean {
        if (queryText.isBlank()) return false
        filterQuery.edit { replace(0, length, "") }
        queryText = ""
        searchPattern = null
        regexValid = true
        sorter.rowFilter = buildRowFilter()
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
        sorter.rowFilter = buildRowFilter()
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

    private fun sizeContentColumnFor(rows: List<ParsedRow>) {
        var w = maxLineWidth
        // Always measure (batches are small): a glyph-width heuristic would miss double-width CJK lines.
        for (p in rows) w = maxOf(w, renderer.lineWidth(p.raw))
        if (w != maxLineWidth) {
            maxLineWidth = w
            fitColumnWidth()
        }
    }

    /** Install the per-column renderers once, then size the columns for the current view mode. */
    private fun setupColumns() {
        val cm = table.columnModel
        if (cm.columnCount < 3) return
        cm.getColumn(COL_TIME).cellRenderer = LogTimeRenderer(model)
        cm.getColumn(COL_LEVEL).cellRenderer = LogLevelRenderer(model)
        cm.getColumn(COL_MSG).cellRenderer = renderer
        applyColumnLayout()
    }

    /** Parsed view = fixed Time + Level + flexible Message; raw view = Time/Level collapsed, Message only. */
    private fun applyColumnLayout() {
        val cm = table.columnModel
        if (cm.columnCount < 3) return
        if (rawMode) {
            for (c in intArrayOf(COL_TIME, COL_LEVEL)) cm.getColumn(c).apply { minWidth = 0; maxWidth = 0; preferredWidth = 0 }
        } else {
            cm.getColumn(COL_TIME).apply { minWidth = JBUI.scale(70); maxWidth = JBUI.scale(190); preferredWidth = JBUI.scale(108) }
            cm.getColumn(COL_LEVEL).apply { minWidth = JBUI.scale(56); maxWidth = JBUI.scale(120); preferredWidth = JBUI.scale(84) }
        }
        fitColumnWidth()
    }

    /** Size the Message column to fill the remaining width (fill; scroll when content is wider).
     *  Truncate mode: exactly the remaining width so long lines clip instead of adding a horizontal scrollbar. */
    private fun fitColumnWidth() {
        val cm = table.columnModel
        if (cm.columnCount < 3) return
        val fixed = cm.getColumn(COL_TIME).preferredWidth + cm.getColumn(COL_LEVEL).preferredWidth
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
        val cursor = if (viewRow in 0 until table.rowCount) {
            val mr = table.convertRowIndexToModel(viewRow)
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
        )
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
