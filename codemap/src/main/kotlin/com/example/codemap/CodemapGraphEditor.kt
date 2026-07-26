package com.example.codemap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.fileEditor.FileEditor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.fileEditor.FileEditorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.fileEditor.FileEditorPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intellij.openapi.fileEditor.FileEditorProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
class CodemapGraphFile(val initialFocus: String) : LightVirtualFile("코드맵 그래프", "")

class CodemapGraphEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is CodemapGraphFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        CodemapGraphEditor(project, file as CodemapGraphFile)

    override fun getEditorTypeId(): String = "codemap-graph"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class CodemapGraphEditor(private val project: Project, private val file: CodemapGraphFile) :
    UserDataHolderBase(), FileEditor {

    private val vm = GraphViewModel(project, file.initialFocus)
    private val panel: JComponent = JewelComposePanel { CodemapGraphView(vm) }

    /** Re-centre this tab instead of opening another one — see [openCallGraph]. */
    fun focusOn(name: String) = vm.refocus(name)

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

/**
 * Opens the graph tab centred on [focus], or re-centres the one that is already open.
 *
 * One tab, not one per function: `LightVirtualFile` compares by identity, so a fresh file per click
 * stacked up tabs that all claimed the same name. The graph is a place you look from, not a document you
 * collect.
 */
fun openCallGraph(project: Project, focus: String) {
    val manager = FileEditorManager.getInstance(project)
    val open = manager.openFiles.filterIsInstance<CodemapGraphFile>().firstOrNull()
    if (open != null) {
        manager.getEditors(open).filterIsInstance<CodemapGraphEditor>().forEach { it.focusOn(focus) }
        manager.openFile(open, true) // brings the existing tab forward
        return
    }
    manager.openFile(CodemapGraphFile(focus), true)
}

/**
 * Everything the graph tab needs: the stitched index, the current focus and depth, and navigation.
 *
 * The index is rebuilt when the tab opens and whenever the focus moves, which keeps it honest against
 * notes written meanwhile and costs a walk over `.codemap/` — bounded by note count, not by codebase
 * size.
 */
class GraphViewModel(private val project: Project, initialFocus: String) {

    // Compose state: re-centring or changing the depth must repaint the canvas, and the tab can be
    // re-centred from outside (openCallGraph) as well as by a click inside it.
    var focus: String by mutableStateOf(initialFocus)
        private set

    var depth: Int by mutableStateOf(2)
        private set

    private val store: NoteStore? get() = project.getService(CodemapStore::class.java).store

    /** Rebuilt lazily; the tab is opened by a user gesture, so the walk happens off the critical path. */
    fun graph(): CallIndex.Graph {
        val s = store ?: return CallIndex.Graph(emptyList(), 0, emptySet())
        return CallIndex(s.allNotes()).around(focus, depth)
    }

    fun analyzedFiles(): Int = store?.allNotes()?.size ?: 0

    /**
     * Full name → the note's `functions` entry, across every analyzed file.
     *
     * The graph itself only needs names and edges; the cards want what the note already recorded about
     * each function (purpose, thread, locks), and this is where that lives.
     */
    fun functionDetails(): Map<String, com.google.gson.JsonObject> {
        val s = store ?: return emptyMap()
        val out = HashMap<String, com.google.gson.JsonObject>()
        s.allNotes().forEach { (_, note) ->
            (note.get("functions") as? com.google.gson.JsonArray)?.forEach { el ->
                val f = el as? com.google.gson.JsonObject ?: return@forEach
                val name = f.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                out.putIfAbsent(name, f)
            }
        }
        return out
    }

    fun refocus(name: String) { focus = name }

    fun changeDepth(d: Int) { depth = d }

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

    /**
     * Codemap-root-relative path for display.
     *
     * A bare file name is not an answer in a codebase this size — `World.cpp` exists in more than one
     * place — and an absolute path is mostly the part everything shares.
     */
    fun display(absolutePath: String): String {
        val root = store?.root?.path ?: return absolutePath
        return absolutePath.removePrefix(root).removePrefix("/")
    }

    /** The exhaustive answer, resolved by Rider and reported back here rather than in its own window. */
    fun findUsages(node: CallIndex.Node, onResult: (UsageFinder.Result) -> Unit) {
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
            UsageFinder.find(project, s.root, rel, hit.line, node.name, onResult)
            return
        }
    }
}
