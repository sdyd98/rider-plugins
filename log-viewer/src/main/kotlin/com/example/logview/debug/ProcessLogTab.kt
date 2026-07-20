package com.example.logview.debug

import com.example.logview.LogViewerPanel
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ObservableConsoleView
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shared installer for the "로그 뷰어" tab inside a session's [RunnerLayoutUi] — used by BOTH the
 * debug tool window ([DebugLogTabListener]) and the run tool window ([RunLogTabListener]). One
 * [ProcessOutputBuffer] per session collects everything the session's consoles print; the tab renders
 * it through a [LogViewerPanel] (filters, highlight rules, error summary, folding, vim).
 *
 * SOURCE — observe the CONSOLES, not the process pipe, whenever possible: Rider's .NET debugger
 * prints target output (stdout/stderr), debug output (`Debug.WriteLine`) and debugger messages
 * straight into `ConsoleViewImpl.print(...)` via the RD protocol, never through the frontend
 * ProcessHandler. Every console implementing [ObservableConsoleView] is tapped with
 * `addChangeListener` (existing text seeded via `getText()`, both on the EDT — atomic, so nothing is
 * lost or duplicated); consoles that appear LATER (the Debug Output tab) are caught by a
 * [ContentManagerListener]. Only when a session has NO observable console do we fall back to tapping
 * the ProcessHandler directly.
 *
 * Lifecycle: closing the session tab disposes the panel (and its console taps, parented to it) and
 * stops the buffer's delivery thread. The tab stays after the process exits — like the console — so
 * the last lines stay readable.
 */
internal object ProcessLogTab {

    /** What [install] built — callers wire their own end-of-session flush to [buffer]. */
    class Installed(val panel: LogViewerPanel, val buffer: ProcessOutputBuffer)

    fun install(
        project: Project,
        ui: RunnerLayoutUi,
        sourceLabel: String,
        /** Per-source memory key (line format / charset), e.g. "debug:<config>" / "run:<config>" —
         *  the format chosen once applies to every future session of that run configuration. */
        sourceKey: String,
        mainConsole: ConsoleView?,
        processHandler: ProcessHandler?,
    ): Installed {
        val buffer = ProcessOutputBuffer()
        val sources = SourceControl()
        val panel = LogViewerPanel(
            project,
            sourceLabel,
            makeReader = { buffer.newReader(sources::accepts) },
            followByDefault = true, // behave like the console: stick to the newest lines
            sourceKey = sourceKey,
        )
        sources.onChanged = { panel.reparse() } // re-filter = replay the buffer through a new reader
        val wrapper = JPanel(BorderLayout()).apply {
            add(sources.bar, BorderLayout.NORTH)
            add(panel.component, BorderLayout.CENTER)
        }
        val content = ui.createContent("com.example.logview.debug", wrapper, "로그 뷰어", AllIcons.Debugger.Console, null)
        // Closing the session tab disposes the panel (and its console taps, parented to it)
        // and stops the buffer's delivery thread.
        content.setDisposer(
            Disposable {
                Disposer.dispose(panel)
                buffer.close()
            },
        )
        ui.addContent(content)
        panel.start()

        val taps = ConsoleTaps(buffer, panel, sources)
        taps.tapConsole(mainConsole, "콘솔")
        ui.contents.forEach { c -> if (c !== content) taps.tapAllIn(c.component, labelFor(c)) }
        ui.addListener(object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                if (event.content !== content) {
                    taps.tapAllIn(event.content.component, labelFor(event.content))
                }
            }
        }, panel)
        if (!taps.anyTapped) {
            taps.fallbackMode = true // late consoles must not double-feed on top of the pipe tap
            tapProcessHandlerFallback(processHandler, buffer, sources)
        }
        return Installed(panel, buffer)
    }

    private fun labelFor(content: Content): String =
        content.displayName?.takeIf { it.isNotBlank() } ?: "콘솔"

    /** Sessions whose consoles print through the ProcessHandler only (no observable console found). */
    private fun tapProcessHandlerFallback(
        handler: ProcessHandler?,
        buffer: ProcessOutputBuffer,
        sources: SourceControl,
    ) {
        if (handler == null) return
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
        private val sources: SourceControl,
    ) {
        private val tapped = java.util.IdentityHashMap<ObservableConsoleView, Unit>()
        val anyTapped: Boolean get() = tapped.isNotEmpty()
        var fallbackMode = false

        /** Tap the session's main console — directly if observable, else whatever its component wraps. */
        fun tapConsole(console: ConsoleView?, label: String) {
            when {
                console == null -> {}
                console is ObservableConsoleView -> tap(console, label)
                else -> tapAllIn(console.component, label)
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
    private class SourceControl {
        private val labelsByKey = HashMap<String, String>() // stream key -> display label (locked)
        @Volatile private var selectedLabel: String? = null // null = 모두
        var onChanged: () -> Unit = {}

        private val combo = ComboBox<String>().apply {
            addItem(ALL)
            addActionListener {
                val sel = (selectedItem as? String)?.takeIf { it != ALL }
                if (sel != selectedLabel) {
                    selectedLabel = sel
                    onChanged()
                }
            }
        }
        val bar: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            add(JBLabel("출력 소스:"))
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
