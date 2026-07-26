package com.example.codemap

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.jewel.bridge.JewelComposePanel
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.JComponent

/**
 * The call graph as a full editor tab.
 *
 * A tab rather than another tool window: a graph wants the widest surface in the IDE, and the tool
 * window is already spent on the note. It rides on a [LightVirtualFile] so no file type has to be
 * registered and nothing is written to disk — the "document" is just the function we are centred on.
 */
class CodemapGraphFile(val focus: String) :
    LightVirtualFile("코드맵 그래프 — ${focus.substringAfterLast("::")}", "")

class CodemapGraphEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is CodemapGraphFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CodemapGraphEditor(project, file as CodemapGraphFile)

    override fun getEditorTypeId(): String = "codemap-graph"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class CodemapGraphEditor(private val project: Project, private val file: CodemapGraphFile) :
    UserDataHolderBase(), FileEditor {

    private val vm = GraphViewModel(project, file.focus)
    private val panel: JComponent = JewelComposePanel { CodemapGraphView(vm) }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = "코드맵 그래프"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}

/** Opens (or re-focuses) the graph tab centred on [focus]. */
fun openCallGraph(project: Project, focus: String) {
    FileEditorManager.getInstance(project).openFile(CodemapGraphFile(focus), true)
}

/**
 * Everything the graph tab needs: the stitched index, the current focus and depth, and navigation.
 *
 * The index is rebuilt when the tab opens and whenever the focus moves, which keeps it honest against
 * notes written meanwhile and costs a walk over `.codemap/` — bounded by note count, not by codebase
 * size.
 */
class GraphViewModel(private val project: Project, initialFocus: String) {

    var focus: String = initialFocus
        private set

    var depth: Int = 2
        private set

    private val store: NoteStore? get() = project.getService(CodemapStore::class.java).store

    /** Rebuilt lazily; the tab is opened by a user gesture, so the walk happens off the critical path. */
    fun graph(): CallIndex.Graph {
        val s = store ?: return CallIndex.Graph(emptyList(), 0, emptySet())
        return CallIndex(s.allNotes()).around(focus, depth)
    }

    fun analyzedFiles(): Int = store?.allNotes()?.size ?: 0

    fun refocus(name: String) { focus = name }

    fun setDepth(d: Int) { depth = d }

    /** Jump to a node's definition, resolving its anchor the same way the tool window does. */
    fun jumpTo(node: CallIndex.Node) {
        val s = store ?: return
        val note = s.readNote(node.file) ?: return
        val anchor = (note.get("functions") as? com.google.gson.JsonArray)
            ?.mapNotNull { it as? com.google.gson.JsonObject }
            ?.firstOrNull { it.get("name")?.asString == node.name }
            ?.get("anchor")?.asString ?: return
        val covered = (note.get("files") as? com.google.gson.JsonArray)?.mapNotNull { it.asString }.orEmpty()
        covered.forEach { rel ->
            val f = s.resolve(rel).takeIf { it.isFile } ?: return@forEach
            val hit = FileFacts.findAnchors(f, listOf(anchor)).values.firstOrNull() ?: return@forEach
            val vf = LocalFileSystem.getInstance().findFileByPath(File(s.root, rel).path) ?: return
            OpenFileDescriptor(project, vf, (hit.line - 1).coerceAtLeast(0), 0).navigate(true)
            return
        }
    }

    /** Hand the exhaustive question to Rider, from the graph as well as from the panel. */
    fun showUsages(node: CallIndex.Node) {
        val s = store ?: return
        val note = s.readNote(node.file) ?: return
        val anchor = (note.get("functions") as? com.google.gson.JsonArray)
            ?.mapNotNull { it as? com.google.gson.JsonObject }
            ?.firstOrNull { it.get("name")?.asString == node.name }
            ?.get("anchor")?.asString ?: return
        val covered = (note.get("files") as? com.google.gson.JsonArray)?.mapNotNull { it.asString }.orEmpty()
        covered.forEach { rel ->
            val f = s.resolve(rel).takeIf { it.isFile } ?: return@forEach
            val hit = FileFacts.findAnchors(f, listOf(anchor)).values.firstOrNull() ?: return@forEach
            CodemapViewModel(project).showUsages(CodemapState.FnLoc(rel, hit.line, hit.occurrences), node.name)
            return
        }
    }
}
