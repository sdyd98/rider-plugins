package com.example.xlsx

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tells the platform that `.xlsx` / `.xls` files open in [XlsxFileEditor] (a read-only grid viewer).
 * [DumbAware] so it works during indexing; [FileEditorPolicy.HIDE_OTHER_EDITORS] so only our tab shows.
 */
class XlsxFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val ext = file.extension ?: return false
        return ext.equals("xlsx", ignoreCase = true) || ext.equals("xls", ignoreCase = true)
    }

    // accept() only checks the file name, so no read action is required.
    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        XlsxFileEditor(file)

    override fun getEditorTypeId(): String = "xlsx-grid-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS
}
