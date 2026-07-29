package com.example.codemap

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * One conversation per file, shared by every screen that shows it.
 *
 * The panel and the tab are two views of the same talk: ask a quick question inline while reading, open the
 * tab when the answer needs room, and it is the same thread either way. Holding the turns in a project
 * service rather than in a view model is what makes that true — a view model dies with its tab.
 *
 * In memory only. The store is notes about code; a transcript is neither a fact nor a judgement about it, and
 * what deserves to be kept gets kept explicitly through the pin actions.
 */
@Service(Service.Level.PROJECT)
class ChatSessions(private val project: Project) {

    /** What one file's conversation is doing right now. */
    class Session {
        val turns = mutableStateListOf<Chat.Turn>()

        var running: Boolean by mutableStateOf(false)
            internal set

        var error: String? by mutableStateOf(null)
            internal set

        /** Non-null while the conversation is being folded into the note. */
        var writing: String? by mutableStateOf(null)
            internal set

        /** Set after a successful note update, so a screen can say it worked. */
        var wrote: String? by mutableStateOf(null)
            internal set

        /** Bumped when the note changed underneath, so views re-read it. */
        var revision: Int by mutableStateOf(0)
            internal set

        internal var runner: AnalysisRunner? = null
    }

    private val sessions = HashMap<String, Session>()

    @Synchronized
    fun of(rel: String): Session = sessions.getOrPut(rel) { Session() }

    private val settings get() = ApplicationManager.getApplication().getService(CodemapSettings::class.java)
    private val store: NoteStore? get() = project.getService(CodemapStore::class.java).store

    val engine: Engine get() = settings?.engine ?: Engine.CLAUDE

    fun note(rel: String) = store?.readNote(rel)

    /** One turn. The answer lands in [Session.turns] on the EDT; failures land in [Session.error]. */
    fun ask(rel: String, question: String) {
        val s = store ?: return
        val session = of(rel)
        if (session.running || question.isBlank()) return

        session.turns += Chat.Turn(Chat.Role.USER, question.trim())
        session.running = true
        session.error = null

        val prompt = Chat.prompt(rel, note(rel), session.turns.dropLast(1), question.trim())
        val chosen = engine
        val r = AnalysisRunner(s).also { session.runner = it }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = r.ask(prompt, engine = chosen, explicitPath = settings?.pathFor(chosen))
            ApplicationManager.getApplication().invokeLater {
                session.running = false
                when (result) {
                    is AnalysisRunner.Result.Answer ->
                        session.turns += Chat.Turn(Chat.Role.ASSISTANT, result.text)
                    is AnalysisRunner.Result.Failed -> session.error = result.reason
                    is AnalysisRunner.Result.Ok -> session.error = "예상하지 못한 응답 형태입니다"
                }
            }
        }
    }

    /**
     * Fold the conversation into the note.
     *
     * The structured analysis is what builds everything the panel draws, and a conversation cannot produce
     * any of it — so this runs that analysis with the talk attached as context rather than instead of it.
     */
    fun updateNote(rel: String) {
        val s = store ?: return
        val session = of(rel)
        if (session.running || session.writing != null || session.turns.isEmpty()) return

        session.writing = "대화를 노트에 반영하는 중"
        session.wrote = null
        session.error = null
        val chosen = engine
        val conversation = session.turns.toList()
        val r = AnalysisRunner(s).also { session.runner = it }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = r.analyze(
                rel,
                question = "",
                engine = chosen,
                explicitPath = settings?.pathFor(chosen),
                conversation = conversation,
            )
            ApplicationManager.getApplication().invokeLater {
                session.writing = null
                when (result) {
                    is AnalysisRunner.Result.Ok -> {
                        session.revision++
                        session.wrote = "노트를 갱신했습니다"
                    }
                    is AnalysisRunner.Result.Failed -> session.error = result.reason
                    is AnalysisRunner.Result.Answer -> session.error = "예상하지 못한 응답 형태입니다"
                }
            }
        }
    }

    fun cancel(rel: String) {
        val session = of(rel)
        session.runner?.cancel()
        session.running = false
        session.writing = null
    }

    fun clear(rel: String) {
        val session = of(rel)
        session.turns.clear()
        session.error = null
        session.wrote = null
    }

    /** Keep an answer as a 주의 — recorded as a human edit, so a re-analysis restores it. */
    fun pinAsGotcha(rel: String, text: String) = write(rel) {
        val existing = (note(rel)?.get("gotchas") as? com.google.gson.JsonArray)
            ?.mapNotNull { it.asString }.orEmpty()
        store?.editNote(
            rel,
            listOf("gotchas"),
            com.google.gson.JsonArray().apply { (existing + text.trim()).forEach(::add) },
        )
    }

    /** Keep an answer as one function's one-liner. */
    fun pinAsPurpose(rel: String, function: String, text: String) = write(rel) {
        store?.editNote(
            rel,
            listOf("functions", function, "purpose"),
            com.google.gson.JsonPrimitive(text.trim()),
        )
    }

    /** The functions this note records — the targets a pinned answer can become a one-liner for. */
    fun functionNames(rel: String): List<String> =
        (note(rel)?.get("functions") as? com.google.gson.JsonArray)
            ?.mapNotNull { el ->
                (el as? com.google.gson.JsonObject)?.get("name")
                    ?.takeIf { it.isJsonPrimitive }?.asString
            }.orEmpty()

    private fun write(rel: String, block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching(block)
            ApplicationManager.getApplication().invokeLater { of(rel).revision++ }
        }
    }
}
