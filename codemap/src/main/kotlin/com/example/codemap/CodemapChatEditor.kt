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
    // Deliberately does NOT cancel: the conversation belongs to the file, not to this tab. Closing the tab
    // and reopening it — or asking the next question from the panel — continues the same talk.
    override fun dispose() = Unit
}

/** Opens the conversation for [rel], or brings the one already open for that file forward. */
fun openChat(project: Project, rel: String) {
    val manager = FileEditorManager.getInstance(project)
    val open = manager.openFiles.filterIsInstance<CodemapChatFile>().firstOrNull { it.rel == rel }
    manager.openFile(open ?: CodemapChatFile(rel), true)
}

/**
 * The tab's view of a conversation.
 *
 * Thin on purpose: the turns live in [ChatSessions], a project service, so the panel's inline box and this
 * tab are two windows onto one talk. A view model would have died with the tab and taken the conversation
 * with it.
 */
class ChatViewModel(private val project: Project, val rel: String) {

    private val sessions get() = project.getService(ChatSessions::class.java)
    private val session get() = sessions.of(rel)

    val turns get() = session.turns
    val running get() = session.running
    val error get() = session.error
    val step get() = session.step
    val writing get() = session.writing
    val wrote get() = session.wrote
    val revision get() = session.revision

    val engine: Engine get() = sessions.engine
    val fileName: String get() = rel.substringAfterLast('/')

    fun functionNames(): List<String> = sessions.functionNames(rel)

    fun ask(question: String) = sessions.ask(rel, question)
    fun updateNote() = sessions.updateNote(rel)
    fun cancel() = sessions.cancel(rel)
    fun clear() = sessions.clear(rel)
    fun pinAsGotcha(text: String) = sessions.pinAsGotcha(rel, text)
    fun pinAsPurpose(function: String, text: String) = sessions.pinAsPurpose(rel, function, text)
}
