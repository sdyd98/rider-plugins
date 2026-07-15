package com.example.logview.debug

import com.example.logview.LogViewerPanel
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ObservableConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

private val LOG = logger<DebugLogTabListener>()

/**
 * Adds a "로그 뷰어" tab to every debug session's tool window, fed by what the session's consoles
 * print — rendered by [LogViewerPanel]: level/regex filtering, highlight rules, error summary,
 * folding, and vim navigation over the live stream. Zero configuration.
 *
 * SOURCE — observe the CONSOLES, not the process pipe: Rider's .NET debugger prints target output
 * (stdout/stderr), debug output (`Debug.WriteLine` — the separate "Debug Output" tab), and debugger
 * messages straight into `ConsoleViewImpl.print(...)` via the RD protocol, NEVER through the
 * frontend ProcessHandler. Every session console that implements [ObservableConsoleView] is tapped
 * with `addChangeListener` (existing text seeded via `getText()`, both on the EDT — atomic, so
 * nothing is lost or duplicated), and consoles that appear LATER (the Debug Output tab) are caught
 * by a [ContentManagerListener]. Only when a session has NO observable console do we fall back to
 * tapping the ProcessHandler directly.
 *
 * When a session has SEVERAL output sources (main console + Debug Output), a small "출력 소스" combo
 * appears above the grid — 모두 / per-source — since a line sent to both (e.g. `Console.WriteLine` +
 * `Debug.WriteLine`) legitimately shows twice in the merged view. Switching replays the buffer
 * through [LogViewerPanel.reparse] with the source filter applied.
 *
 * Lifecycle: the tab attaches once the session UI exists (created asynchronously — retried briefly);
 * console listeners are parented to the panel, which disposes with its Content when the session tab
 * closes. The tab stays after the process exits — like the console — so the last lines stay readable.
 */
class DebugLogTabListener : XDebuggerManagerListener {

    override fun processStarted(debugProcess: XDebugProcess) = attachTab(debugProcess, attempts = 20)

    /** The session tab (RunnerLayoutUi) is created asynchronously around process start — retry briefly. */
    private fun attachTab(debugProcess: XDebugProcess, attempts: Int) {
        if (attempts <= 0) return
        ApplicationManager.getApplication().invokeLater retry@{
            val session = debugProcess.session
            val ui = runCatching { session.ui }.getOrNull()
            if (ui == null) {
                // A stopped session whose UI never appeared won't grow one — stop retrying. A session
                // that merely finished fast still gets its tab (like the console, readable after exit).
                if (!session.isStopped) {
                    Timer(150) { attachTab(debugProcess, attempts - 1) }.apply { isRepeats = false }.start()
                }
                return@retry
            }
            runCatching {
                val buffer = ProcessOutputBuffer()
                val sources = DebugSourceControl()
                val panel = LogViewerPanel(
                    session.project,
                    "디버그: ${session.sessionName}",
                    makeReader = { buffer.newReader(sources::accepts) },
                    followByDefault = true, // behave like the console: stick to the newest lines
                    // Per-source memory (line format / charset) keyed by the run configuration name,
                    // so the format chosen once applies to every future session of that config.
                    sourceKey = "debug:${session.sessionName}",
                )
                sources.onChanged = { panel.reparse() } // re-filter = replay the buffer through a new reader
                val wrapper = JPanel(BorderLayout()).apply {
                    add(sources.bar, BorderLayout.NORTH)
                    add(panel.component, BorderLayout.CENTER)
                }
                val content = ui.createContent(
                    "com.example.logview.debug", wrapper, "로그 뷰어", AllIcons.Debugger.Console, null,
                )
                content.setDisposer(panel) // closing the session tab disposes the panel (and its taps)
                ui.addContent(content)
                panel.start()

                val taps = ConsoleTaps(buffer, panel, sources)
                taps.tapConsole(runCatching { session.consoleView }.getOrNull(), "콘솔")
                ui.contents.forEach { c -> if (c !== content) taps.tapAllIn(c.component as? JComponent, labelFor(c)) }
                ui.addListener(object : ContentManagerListener {
                    override fun contentAdded(event: ContentManagerEvent) {
                        if (event.content !== content) {
                            taps.tapAllIn(event.content.component as? JComponent, labelFor(event.content))
                        }
                    }
                }, panel)
                if (!taps.anyTapped) {
                    taps.fallbackMode = true // late consoles must not double-feed on top of the pipe tap
                    tapProcessHandlerFallback(debugProcess, buffer, sources)
                }
                // The console document may end mid-line when the session stops — flush the remainder.
                session.addSessionListener(object : XDebugSessionListener {
                    override fun sessionStopped() = buffer.flush()
                }, panel)
            }.onFailure { LOG.warn("log viewer debug tab failed to attach", it) }
        }
    }

    private fun labelFor(content: Content): String =
        content.displayName?.takeIf { it.isNotBlank() } ?: "콘솔"

    /** Sessions whose consoles print through the ProcessHandler only (no observable console found). */
    private fun tapProcessHandlerFallback(
        debugProcess: XDebugProcess,
        buffer: ProcessOutputBuffer,
        sources: DebugSourceControl,
    ) {
        val handler = runCatching { debugProcess.processHandler }.getOrNull() ?: return
        listOf("stdout", "stderr", "system").forEach { sources.register(it, "프로세스 출력") }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val stream = when {
                    ProcessOutputType.isStderr(outputType) -> "stderr"
                    ProcessOutputType.isStdout(outputType) -> "stdout"
                    else -> "system"
                }
                buffer.append(event.text ?: return, stream)
            }

            override fun processTerminated(event: ProcessEvent) = buffer.flush()
        })
    }

    /** Tracks which consoles are already tapped so re-scans (late tabs) never double-subscribe. */
    private class ConsoleTaps(
        private val buffer: ProcessOutputBuffer,
        private val panel: LogViewerPanel,
        private val sources: DebugSourceControl,
    ) {
        private val tapped = java.util.IdentityHashMap<ObservableConsoleView, Unit>()
        val anyTapped: Boolean get() = tapped.isNotEmpty()
        var fallbackMode = false

        /** Tap the session's main console — directly if observable, else whatever its component wraps. */
        fun tapConsole(console: ConsoleView?, label: String) {
            when {
                console == null -> {}
                console is ObservableConsoleView -> tap(console, label)
                else -> tapAllIn(console.component as? JComponent, label)
            }
        }

        /** Find and tap every observable console inside [root] (consoles are often wrapped). */
        fun tapAllIn(root: JComponent?, label: String) {
            if (root == null || fallbackMode) return
            if (root is ObservableConsoleView) tap(root, label)
            UIUtil.findComponentsOfType(root, JComponent::class.java)
                .forEach { c -> if (c is ObservableConsoleView) tap(c, label) }
        }

        private fun tap(console: ObservableConsoleView, label: String) {
            if (tapped.put(console, Unit) != null) return
            // ONE assembly stream per console: textAdded chunks arrive in document order on the EDT,
            // so a single accumulator reproduces the console text exactly (types only color it).
            val stream = "console#${tapped.size}"
            sources.register(stream, label)
            // Seed + subscribe back-to-back on the EDT — console flushes also run on the EDT, so no
            // text can slip between the two (getText = flushed history, textAdded = everything after).
            console.addChangeListener(
                object : ObservableConsoleView.ChangeListener {
                    override fun textAdded(text: String, type: ConsoleViewContentType) = buffer.append(text, stream)
                },
                panel,
            )
            (console as? ConsoleViewImpl)?.text?.takeIf { it.isNotEmpty() }?.let { buffer.append(it, stream) }
        }
    }

    /**
     * The "출력 소스" selector: 모두(default) or one source label. Hidden until the session actually has
     * two distinct sources (main console only → no chrome). [accepts] is read by reader threads.
     */
    private class DebugSourceControl {
        private val labelsByKey = HashMap<String, String>() // stream key -> display label (locked)
        @Volatile private var selectedLabel: String? = null // null = 모두
        var onChanged: () -> Unit = {}

        private val combo = JComboBox<String>().apply {
            addItem(ALL)
            addActionListener {
                val sel = (selectedItem as? String)?.takeIf { it != ALL }
                if (sel != selectedLabel) {
                    selectedLabel = sel
                    onChanged()
                }
            }
        }
        val bar: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("출력 소스:"))
            add(combo)
            isVisible = false
        }

        /** Reader-thread predicate: does the stream [key] pass the current source selection? */
        fun accepts(key: String): Boolean {
            val sel = selectedLabel ?: return true
            return synchronized(labelsByKey) { labelsByKey[key] } == sel
        }

        /** EDT: record a stream's display label; the bar appears once two distinct labels exist. */
        fun register(key: String, label: String) {
            val distinct = synchronized(labelsByKey) {
                labelsByKey[key] = label
                labelsByKey.values.toSortedSet()
            }
            if ((0 until combo.itemCount).none { combo.getItemAt(it) == label }) combo.addItem(label)
            bar.isVisible = distinct.size >= 2
        }

        companion object {
            private const val ALL = "모두"
        }
    }
}
