package com.example.logview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens `.log` files in [LogFileEditor]. Uses [FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR] so the
 * log viewer is the default tab while the plain-text editor stays available as a second tab (rather
 * than hijacking the file type entirely). [DumbAware] so it works during indexing.
 */
class LogFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val ext = file.extension ?: return false
        return ext.equals("log", ignoreCase = true)
    }

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = LogFileEditor(project, file)

    override fun getEditorTypeId(): String = "log-viewer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
