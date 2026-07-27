package com.example.codemap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * A conversation about one file, in its own tab.
 *
 * A tab and not the tool window, and that is a correctness choice: a chat needs an input field that is
 * always there, and a focused Compose text field in that panel swallows the IDE's own shortcuts. It is also
 * the only place a conversation has room to be read.
 *
 * What makes this worth having inside the plugin rather than in a terminal: the note is the context, and an
 * answer can be **kept**. A reply that turns out to matter becomes a 주의 or a function's one-liner with one
 * click, recorded as a human edit — so the next re-analysis preserves it instead of overwriting it.
 */
class CodemapChatFile(val rel: String) : LightVirtualFile("코드맵 대화 — ${rel.substringAfterLast('/')}", "")

class CodemapChatEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is CodemapChatFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CodemapChatEditor(project, file as CodemapChatFile)

    override fun getEditorTypeId(): String = "codemap-chat"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class CodemapChatEditor(project: Project, private val file: CodemapChatFile) :
    UserDataHolderBase(), FileEditor {

    private val vm = ChatViewModel(project, file.rel)
    private val panel: JComponent = JewelComposePanel { CodemapChatView(vm) }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = "코드맵 대화"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() { vm.cancel() }
}

/** Opens the conversation for [rel], or brings the one already open for that file forward. */
fun openChat(project: Project, rel: String) {
    val manager = FileEditorManager.getInstance(project)
    val open = manager.openFiles.filterIsInstance<CodemapChatFile>().firstOrNull { it.rel == rel }
    manager.openFile(open ?: CodemapChatFile(rel), true)
}

/**
 * The conversation's state and the one call that advances it.
 *
 * One conversation per file, held in memory for the life of the tab. Not written to `.codemap/`: the store
 * is notes about the code, and a transcript is neither a fact nor a judgement about it — what is worth
 * keeping gets kept explicitly, through [pin].
 */
class ChatViewModel(private val project: Project, val rel: String) {

    val turns = mutableStateListOf<Chat.Turn>()

    var running: Boolean by mutableStateOf(false)
        private set

    var error: String? by mutableStateOf(null)
        private set

    /** Non-null while the conversation is being folded into the note; the text names what is happening. */
    var writing: String? by mutableStateOf(null)
        private set

    /** What the last note update did, so the tab can say it worked without stealing the screen. */
    var wrote: String? by mutableStateOf(null)
        private set

    /** Bumped whenever the note changes underneath, so pinned text shows up in the panel too. */
    var revision: Int by mutableStateOf(0)
        private set

    private val settings get() = ApplicationManager.getApplication().getService(CodemapSettings::class.java)
    private val store: NoteStore? get() = project.getService(CodemapStore::class.java).store
    private var runner: AnalysisRunner? = null

    val engine: Engine get() = settings?.engine ?: Engine.CLAUDE
    val fileName: String get() = rel.substringAfterLast('/')

    fun note(): JsonObject? = store?.readNote(rel)

    /** The functions this note records — the targets a pinned answer can become a one-liner for. */
    fun functionNames(): List<String> =
        (note()?.get("functions") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("name")?.takeIf { n -> n.isJsonPrimitive }?.asString }
            .orEmpty()

    fun ask(question: String) {
        val s = store ?: return
        if (running || question.isBlank()) return

        turns += Chat.Turn(Chat.Role.USER, question.trim())
        running = true
        error = null

        val prompt = Chat.prompt(rel, note(), turns.dropLast(1), question.trim())
        val chosen = engine
        val r = AnalysisRunner(s).also { runner = it }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = r.ask(prompt, engine = chosen, explicitPath = settings?.pathFor(chosen))
            ApplicationManager.getApplication().invokeLater {
                running = false
                when (result) {
                    is AnalysisRunner.Result.Answer -> turns += Chat.Turn(Chat.Role.ASSISTANT, result.text)
                    is AnalysisRunner.Result.Failed -> error = result.reason
                    is AnalysisRunner.Result.Ok -> error = "예상하지 못한 응답 형태입니다"
                }
            }
        }
    }

    /**
     * Fold the conversation into the note.
     *
     * The structured analysis is what builds everything the panel draws — functions and their anchors, the
     * packet table, threading, flows — and a conversation cannot produce any of it. So this runs that same
     * analysis with the conversation attached as context: what was worked out here is the most expensive
     * knowledge available, and re-deriving it from scratch would waste it and risk contradicting it.
     */
    fun updateNote() {
        val s = store ?: return
        if (running || writing != null || turns.isEmpty()) return

        writing = "대화를 노트에 반영하는 중"
        wrote = null
        error = null
        val chosen = engine
        val conversation = turns.toList()
        val r = AnalysisRunner(s).also { runner = it }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = r.analyze(
                rel,
                question = "",
                engine = chosen,
                explicitPath = settings?.pathFor(chosen),
                conversation = conversation,
            )
            ApplicationManager.getApplication().invokeLater {
                writing = null
                when (result) {
                    is AnalysisRunner.Result.Ok -> {
                        revision++
                        wrote = "노트를 갱신했습니다"
                    }
                    is AnalysisRunner.Result.Failed -> error = result.reason
                    is AnalysisRunner.Result.Answer -> error = "예상하지 못한 응답 형태입니다"
                }
            }
        }
    }

    fun cancel() {
        runner?.cancel()
        running = false
        writing = null
    }

    fun clear() {
        turns.clear()
        error = null
    }

    /**
     * Keep an answer: append it to the note's 주의.
     *
     * Written through [NoteStore.editNote], which records it as a human edit — so the next re-analysis
     * restores it rather than deciding it was never there. Pinning is the developer's judgement, not the
     * agent's, and the store treats it that way.
     */
    fun pinAsGotcha(text: String) = write {
        val existing = (note()?.get("gotchas") as? JsonArray)?.mapNotNull { it.asString }.orEmpty()
        store?.editNote(rel, listOf("gotchas"), JsonArray().apply { (existing + text.trim()).forEach(::add) })
    }

    /** Keep an answer as one function's one-liner. */
    fun pinAsPurpose(function: String, text: String) = write {
        store?.editNote(rel, listOf("functions", function, "purpose"), JsonPrimitive(text.trim()))
    }

    private fun write(block: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching(block)
            ApplicationManager.getApplication().invokeLater { revision++ }
        }
    }
}
