package com.example.logview.debug

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import javax.swing.Timer

private val LOG = logger<DebugLogTabListener>()

/**
 * Adds a "로그 뷰어" tab to every debug session's tool window — the shared tab itself (console taps,
 * source combo, panel lifecycle) lives in [ProcessLogTab]; this listener only finds the debug
 * session's [com.intellij.execution.ui.RunnerLayoutUi] and wires the end-of-session flush. Zero
 * configuration. Non-debug Run sessions get the same tab from [RunLogTabListener].
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
                val installed = ProcessLogTab.install(
                    session.project,
                    ui,
                    sourceLabel = "디버그: ${session.sessionName}",
                    sourceKey = "debug:${session.sessionName}",
                    mainConsole = runCatching { session.consoleView }.getOrNull(),
                    processHandler = runCatching { debugProcess.processHandler }.getOrNull(),
                )
                // The console document may end mid-line when the session stops — flush the remainder.
                session.addSessionListener(object : XDebugSessionListener {
                    override fun sessionStopped() = installed.buffer.flush()
                }, installed.panel)
            }.onFailure { LOG.warn("log viewer debug tab failed to attach", it) }
        }
    }
}
