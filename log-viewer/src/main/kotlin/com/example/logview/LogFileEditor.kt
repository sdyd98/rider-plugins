package com.example.logview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.nio.charset.Charset
import javax.swing.JComponent

/**
 * A [FileEditor] that opens a local log file in the [LogViewerPanel] (a second tab alongside the plain
 * text editor — see [LogFileEditorProvider]). The viewer tails the file as it grows; the **Follow**
 * toggle controls whether it sticks to the bottom.
 */
class LogFileEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val changeSupport = PropertyChangeSupport(this)
    private val panel: LogViewerPanel

    init {
        val ioFile = runCatching { VfsUtilCore.virtualToIoFile(file) }.getOrNull()
        val makeReader: (Charset) -> LogReader = if (ioFile != null && ioFile.isFile) {
            val path = ioFile.toPath()
            val local: (Charset) -> LogReader = { cs -> LocalLogReader(path, cs) }
            local
        } else {
            { cs -> VfsLogReader(file, cs) } // non-local VFS: one-shot read, no live tail
        }
        panel = LogViewerPanel(project, file.name, makeReader, followByDefault = false)
        Disposer.register(this, panel)
        panel.start()
    }

    override fun getComponent(): JComponent = panel.component
    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus
    override fun getName(): String = "로그 뷰"
    override fun getFile(): VirtualFile = file
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
    override fun setState(state: FileEditorState) {}

    override fun addPropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        changeSupport.removePropertyChangeListener(listener)

    override fun dispose() {
        // panel (and its reader) is disposed via the Disposer parent registration above
    }

    /** Fallback reader for non-local (e.g. remote-VFS) files: read the current bytes once, no tail. */
    private class VfsLogReader(private val file: VirtualFile, private val charset: Charset) : LogReader {
        override fun readInitial(onBatch: (List<String>) -> Unit) {
            file.inputStream.bufferedReader(charset).useLines { seq ->
                val batch = ArrayList<String>(2000)
                for (line in seq) {
                    batch.add(line)
                    if (batch.size >= 2000) { onBatch(ArrayList(batch)); batch.clear() }
                }
                if (batch.isNotEmpty()) onBatch(batch)
            }
        }
        override fun startTail(
            onAppend: (List<String>) -> Unit,
            onError: (Throwable) -> Unit,
            onState: (TailState) -> Unit,
        ) {} // not supported
        override fun close() {}
    }
}
