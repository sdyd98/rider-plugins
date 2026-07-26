package com.example.codemap

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.File
import java.util.concurrent.TimeUnit

/** Where the Claude Code CLI lives, when it is not somewhere obvious. Empty = discover it. */
@Service(Service.Level.APP)
@State(name = "CodemapSettings", storages = [Storage("codemap.xml")])
class CodemapSettings : PersistentStateComponent<CodemapSettings.State> {
    data class State(var claudePath: String = "")

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    var claudePath: String
        get() = state.claudePath
        set(v) { state.claudePath = v }
}

/**
 * Runs one analysis: spawn the CLI, wait, hand the note to the store.
 *
 * The plugin remains the only writer. Claude is asked for JSON on stdout and never given a tool that
 * could touch the repository, so "analysis ran" and "the note changed" stay separate events with a
 * validation step in between.
 */
class AnalysisRunner(private val store: NoteStore) {

    sealed interface Result {
        data class Ok(val note: com.google.gson.JsonObject) : Result
        data class Failed(val reason: String) : Result
    }

    @Volatile private var process: Process? = null

    val running: Boolean get() = process?.isAlive == true

    /** Kill the current run. Used by the panel's 취소 while a long analysis is in flight. */
    fun cancel() {
        process?.destroy()
        process = null
    }

    /**
     * Analyze [relPath] and store the result. Blocking — callers run it on a pooled thread.
     *
     * [timeoutMinutes] exists because a headless agent that wedges would otherwise hold the panel in
     * "분석 중" forever; the process is killed and the failure is reported rather than hidden.
     */
    fun analyze(
        relPath: String,
        question: String,
        symbol: String = "",
        explicitPath: String? = null,
        timeoutMinutes: Long = 10,
    ): Result {
        val bin = ClaudeCli.discover(explicitPath)
            ?: return Result.Failed("claude 실행 파일을 찾지 못했습니다. 설정에서 경로를 지정하세요.")

        val existing = store.readNote(relPath) != null
        val cmd = ClaudeCli.command(bin, ClaudeCli.prompt(relPath, question, symbol, existing))

        val out = StringBuilder()
        val err = StringBuilder()
        try {
            val p = ProcessBuilder(cmd)
                .directory(store.root)
                .redirectErrorStream(false)
                .start()
                .also { process = it }

            // The CLI waits three seconds for piped stdin before giving up and warning about it.
            // Closing the stream signals EOF immediately, which is both faster and quieter.
            runCatching { p.outputStream.close() }

            val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            val errReader = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            reader.start(); errReader.start()

            if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                return Result.Failed("시간 초과 (${timeoutMinutes}분)")
            }
            reader.join(5_000); errReader.join(5_000)

            if (p.exitValue() != 0) {
                return Result.Failed(err.toString().trim().ifEmpty { "claude 가 종료 코드 ${p.exitValue()} 로 끝났습니다" })
            }
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e::class.java.simpleName)
        } finally {
            process = null
        }

        val raw = out.toString()
        ClaudeCli.errorOf(raw)?.let { return Result.Failed(it) }
        val note = ClaudeCli.extractNote(raw)
            ?: return Result.Failed("응답에서 노트 JSON을 찾지 못했습니다")

        return runCatching { Result.Ok(store.writeNote(relPath, note)) }
            .getOrElse { Result.Failed("노트 저장 실패: ${it.message}") }
    }

    /** For the settings UI and the failure message: is a CLI reachable at all? */
    fun cliPath(explicit: String? = null): File? = ClaudeCli.discover(explicit)
}
