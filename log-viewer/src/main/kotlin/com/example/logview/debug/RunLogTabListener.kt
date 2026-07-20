package com.example.logview.debug

import com.intellij.execution.ExecutionListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import javax.swing.Timer

private val LOG = logger<RunLogTabListener>()

/**
 * Adds the same "로그 뷰어" tab ([ProcessLogTab]) to plain **Run** sessions (Shift+F10 — and any
 * non-debug executor such as coverage/profiling) that [DebugLogTabListener] adds to debug sessions:
 * the run configuration's console output with level/regex filters, highlight rules, error summary,
 * folding, and vim navigation. Zero configuration.
 *
 * Debug sessions are explicitly skipped here — the debug tool window's tab is installed by
 * [DebugLogTabListener] (a second install would duplicate it).
 */
class RunLogTabListener : ExecutionListener {

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (executorId == DefaultDebugExecutor.EXECUTOR_ID) return // the debug listener owns debug sessions
        attachTab(env, handler, attempts = 20)
    }

    /** The run content (RunnerLayoutUi) is created asynchronously around process start — retry briefly. */
    private fun attachTab(env: ExecutionEnvironment, handler: ProcessHandler, attempts: Int) {
        if (attempts <= 0) return
        ApplicationManager.getApplication().invokeLater retry@{
            val project = env.project
            if (project.isDisposed) return@retry
            val descriptor = RunContentManager.getInstance(project).allDescriptors
                .firstOrNull { it.processHandler === handler }
            val ui = descriptor?.runnerLayoutUi
            if (ui == null) {
                // Custom run contents without a RunnerLayoutUi never grow one — the retries just
                // cover the async window between process start and content creation.
                Timer(150) { attachTab(env, handler, attempts - 1) }.apply { isRepeats = false }.start()
                return@retry
            }
            runCatching {
                val name = env.runProfile.name
                val installed = ProcessLogTab.install(
                    project,
                    ui,
                    sourceLabel = "실행: $name",
                    sourceKey = "run:$name",
                    mainConsole = descriptor.executionConsole as? ConsoleView,
                    processHandler = handler,
                )
                // The console may end mid-line when the process exits — flush the remainder AFTER the
                // already-queued EDT console events have landed (hence the extra invokeLater hop).
                handler.addProcessListener(
                    object : ProcessListener {
                        override fun processTerminated(event: ProcessEvent) {
                            ApplicationManager.getApplication().invokeLater { installed.buffer.flush() }
                        }
                    },
                    installed.panel,
                )
            }.onFailure { LOG.warn("log viewer run tab failed to attach", it) }
        }
    }
}
