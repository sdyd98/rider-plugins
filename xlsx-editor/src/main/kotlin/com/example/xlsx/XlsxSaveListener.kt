package com.example.xlsx

import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager

/**
 * A non-text editor has no Document, so Ctrl+S / "Save All" never calls a save method on it.
 * Instead we hook the global save: [beforeAllDocumentsSaving] fires on every save, where we ask each
 * modified [XlsxFileEditor] to persist itself ([XlsxFileEditor.requestSave] does the work — async
 * for large files so the UI never freezes).
 */
class XlsxSaveListener : FileDocumentManagerListener {

    override fun beforeAllDocumentsSaving() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val manager = FileEditorManager.getInstance(project)
            for (editor in manager.allEditors) {
                if (editor is XlsxFileEditor && editor.isModified) {
                    editor.requestSave()
                }
            }
        }
    }
}
