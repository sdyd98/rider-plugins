package com.example.codemap

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Sequence diagrams as their own editor tab.
 *
 * A diagram inside the tool window is a diagram squeezed into a column: participants run out of room after
 * four, and every one after that has to be scrolled to. A tab has the width the drawing actually wants, so
 * that is where it belongs — the panel keeps the list, this shows the picture.
 *
 * One tab for the whole collection: the file's diagrams appear as chips along the top and switching between
 * them costs a click, because comparing two scenarios of the same file is the normal thing to want.
 */
class CodemapSequenceFile(val rel: String, val initialName: String) :
    LightVirtualFile("코드맵 시퀀스", "")

class CodemapSequenceEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is CodemapSequenceFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CodemapSequenceEditor(project, file as CodemapSequenceFile)

    override fun getEditorTypeId(): String = "codemap-sequence"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class CodemapSequenceEditor(project: Project, private val file: CodemapSequenceFile) :
    UserDataHolderBase(), FileEditor {

    private val vm = SequenceViewModel(project, file.rel, file.initialName)
    private val panel: JComponent = JewelComposePanel { CodemapSequenceView(vm) }

    /** Re-point this tab at another file's diagram instead of opening a second one. */
    fun show(rel: String, name: String) = vm.show(rel, name)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = "코드맵 시퀀스"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}

/**
 * Opens the sequence viewer on [name] of [rel], or re-points the one already open.
 *
 * Same reasoning as the call graph tab: `LightVirtualFile` compares by identity, so a new file per click
 * would stack up tabs that all claim the same name.
 */
fun openSequence(project: Project, rel: String, name: String) {
    val manager = FileEditorManager.getInstance(project)
    val open = manager.openFiles.filterIsInstance<CodemapSequenceFile>().firstOrNull()
    if (open != null) {
        manager.getEditors(open).filterIsInstance<CodemapSequenceEditor>().forEach { it.show(rel, name) }
        manager.openFile(open, true)
        return
    }
    manager.openFile(CodemapSequenceFile(rel, name), true)
}

/**
 * What the viewer needs: the note's diagrams and which one is on screen.
 *
 * Read fresh from the store on every change, so a diagram that just arrived from an analysis — or one
 * deleted here — is reflected without any subscription between the tab and the tool window.
 */
class SequenceViewModel(private val project: Project, rel: String, name: String) {

    var rel: String by mutableStateOf(rel)
        private set

    var selected: String by mutableStateOf(name)
        private set

    /** Bumped after a write, so the composable re-reads the note. */
    var revision: Int by mutableStateOf(0)
        private set

    private val store: NoteStore? get() = project.getService(CodemapStore::class.java).store

    val fileName: String get() = rel.substringAfterLast('/')

    fun show(rel: String, name: String) {
        this.rel = rel
        selected = name
        revision++
    }

    fun select(name: String) { selected = name }

    fun flows(): List<JsonObject> {
        val note = store?.readNote(rel) ?: return emptyList()
        return (note.get("flows") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    fun flow(name: String): JsonObject? = flows().firstOrNull { nameOf(it) == name }

    /**
     * Delete the diagram on screen.
     *
     * Deletion lives here rather than in the panel list because this is the only place you can see what you
     * are deleting — a diagram someone asked for should not be discarded from a row of names.
     */
    fun remove(name: String) {
        val s = store ?: return
        s.removeFlow(rel, name)
        revision++
        selected = flows().firstOrNull()?.let { nameOf(it) }.orEmpty()
    }

    private fun nameOf(o: JsonObject): String =
        o.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
