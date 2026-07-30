package com.example.codemap

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.File

/**
 * Which agent runs an analysis, and where its CLI lives when that is not somewhere obvious.
 *
 * Remembered across sessions because the choice is a habit, not a per-file decision: whoever prefers Codex
 * prefers it for the next file too, and being asked again every time would be the annoying kind of
 * flexibility. Empty paths mean "find it yourself".
 */
@Service(Service.Level.APP)
@State(name = "CodemapSettings", storages = [Storage("codemap.xml")])
class CodemapSettings : PersistentStateComponent<CodemapSettings.State> {
    data class State(
        var claudePath: String = "",
        var codexPath: String = "",
        var engine: String = Engine.CLAUDE.name,
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    var claudePath: String
        get() = state.claudePath
        set(v) { state.claudePath = v }

    var codexPath: String
        get() = state.codexPath
        set(v) { state.codexPath = v }

    var engine: Engine
        get() = Engine.of(state.engine)
        set(v) { state.engine = v.name }

    /** The path setting that belongs to [engine], so the runner does not have to know which is which. */
    fun pathFor(engine: Engine): String = when (engine) {
        Engine.CLAUDE -> claudePath
        Engine.CODEX -> codexPath
    }
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
        /** A conversational turn's prose answer. */
        data class Answer(val text: String) : Result
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
     * **No time limit.** There used to be ten minutes, which was a guess about how long a real file takes
     * and killed the honest runs along with the wedged ones — on a 6,000-line file with a question
     * attached, ten minutes is not obviously enough. What that limit was really protecting against is
     * "the panel says 분석 중 forever and I cannot tell if it is alive", and [onProgress] answers that
     * directly: the panel shows which file the agent is reading right now. 취소 remains the way out, and
     * it is a decision rather than a timer.
     */
    fun analyze(
        relPath: String,
        question: String,
        symbol: String = "",
        /** A scenario to draw as a sequence diagram; the result is ADDED to the note's `flows`. */
        flow: String = "",
        engine: Engine = Engine.CLAUDE,
        explicitPath: String? = null,
        /** Called on a reader thread for each line worth showing; see [AgentCli.progressOf]. */
        onProgress: (String) -> Unit = {},
        /** A conversation about this file, folded in as context — see [NoteRequest.prompt]. */
        conversation: List<Chat.Turn> = emptyList(),
    ): Result {
        val cli = engine.cli
        val bin = cli.discover(explicitPath)
            ?: return Result.Failed("${engine.label} 실행 파일을 찾지 못했습니다. 설정에서 경로를 지정하세요.")

        // The existing note goes into the prompt: a re-analysis should correct what is there, not replace
        // it with whatever this run happened to notice.
        val prompt = NoteRequest.prompt(relPath, question, symbol, store.readNote(relPath), flow, conversation)
        val answerFile = File.createTempFile("codemap-note", ".json").also { it.deleteOnExit() }
        val cmd = cli.command(bin, answerFile)

        val out = StringBuilder()
        val err = StringBuilder()
        try {
            val p = ProcessBuilder(cmd)
                .directory(store.root)
                .redirectErrorStream(false)
                .start()
                .also { process = it }

            // The prompt goes here, not on the command line: an argument starting with `-` is an option,
            // and note text can start with anything. Closing the stream is what tells the CLI the prompt is
            // complete — without the close it waits for more.
            runCatching { p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) } }

            val reader = Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    out.appendLine(line)
                    cli.progressOf(line)?.let(onProgress)
                }
            }
            val errReader = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            reader.start(); errReader.start()

            // Waits as long as the agent takes. cancel() destroys the process, which ends this.
            p.waitFor()
            reader.join(5_000); errReader.join(5_000)

            if (p.exitValue() != 0) {
                val reason = err.toString().trim().ifEmpty { out.toString().trim().takeLast(400) }
                return Result.Failed(reason.ifEmpty { "${engine.label} 가 종료 코드 ${p.exitValue()} 로 끝났습니다" })
            }
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e::class.java.simpleName)
        } finally {
            process = null
        }

        val raw = out.toString()
        cli.errorFrom(raw)?.let { return Result.Failed(it) }
        val note = cli.noteFrom(raw, answerFile)
            ?: return Result.Failed("응답에서 노트 JSON을 찾지 못했습니다")
        answerFile.delete()

        // A scenario request touches only `flows`, and only by adding: the answer is one diagram among
        // however many have been collected, never a new version of the whole note.
        if (flow.isNotBlank()) {
            val flows = note.get("flows") as? com.google.gson.JsonArray
                ?: return Result.Failed("응답에 flows 배열이 없습니다")
            if (flows.isEmpty) return Result.Failed("시퀀스를 만들지 못했습니다")
            return runCatching { Result.Ok(store.writeFlows(relPath, flows)) }
                .getOrElse { Result.Failed("시퀀스 저장 실패: ${it.message}") }
        }

        // A function-scoped answer touches only that function. Going through writeNote would take the
        // incoming object as the whole note and drop every key the answer did not repeat — the file's
        // summary, its packets, its threading. writeFunctions upserts by name and leaves the rest alone.
        if (symbol.isNotBlank()) {
            val functions = note.get("functions") as? com.google.gson.JsonArray
                ?: return Result.Failed("응답에 functions 배열이 없습니다")
            if (functions.isEmpty) return Result.Failed("$symbol 을 분석하지 못했습니다")
            return runCatching { Result.Ok(store.writeFunctions(relPath, functions)) }
                .getOrElse { Result.Failed("함수 저장 실패: ${it.message}") }
        }

        return runCatching { Result.Ok(store.writeNote(relPath, note)) }
            .getOrElse { Result.Failed("노트 저장 실패: ${it.message}") }
    }

    /**
     * One turn of a conversation: same process contract as [analyze], prose out instead of a note.
     *
     * Deliberately the same code path — the prompt on stdin, the read-only tool policy, no time limit,
     * the cancel — because a chat that could reach further into the repository than an analysis would be a
     * quiet privilege escalation. The only difference is how the answer is read.
     */
    fun ask(
        prompt: String,
        engine: Engine = Engine.CLAUDE,
        explicitPath: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result {
        val cli = engine.cli
        val bin = cli.discover(explicitPath)
            ?: return Result.Failed("${engine.label} 실행 파일을 찾지 못했습니다. Settings | Tools | 코드맵 에서 경로를 지정하세요.")

        val answerFile = File.createTempFile("codemap-chat", ".txt").also { it.deleteOnExit() }
        val cmd = cli.command(bin, answerFile)

        val out = StringBuilder()
        val err = StringBuilder()
        try {
            val p = ProcessBuilder(cmd).directory(store.root).redirectErrorStream(false).start()
                .also { process = it }
            runCatching { p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) } }

            val reader = Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    out.appendLine(line)
                    cli.progressOf(line)?.let(onProgress)
                }
            }
            val errReader = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            reader.start(); errReader.start()

            p.waitFor()
            reader.join(5_000); errReader.join(5_000)
            if (p.exitValue() != 0) {
                val reason = err.toString().trim().ifEmpty { out.toString().trim().takeLast(400) }
                return Result.Failed(reason.ifEmpty { "${engine.label} 가 종료 코드 ${p.exitValue()} 로 끝났습니다" })
            }
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e::class.java.simpleName)
        } finally {
            process = null
        }

        val raw = out.toString()
        cli.errorFrom(raw)?.let { return Result.Failed(it) }
        val text = cli.textFrom(raw, answerFile) ?: return Result.Failed("답을 읽지 못했습니다")
        answerFile.delete()
        return Result.Answer(text)
    }

    /** For the settings UI and the failure message: is this engine's CLI reachable at all? */
    fun cliPath(engine: Engine, explicit: String? = null): File? = engine.cli.discover(explicit)
}
