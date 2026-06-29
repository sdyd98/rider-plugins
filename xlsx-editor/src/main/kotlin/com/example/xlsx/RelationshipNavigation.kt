package com.example.xlsx

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import javax.swing.Timer

/**
 * The reference schema for the project's currently-open workbook: the NEAREST `refs.json` at or above the
 * open file's folder drives the relationship views. Walking up means one top-level refs.json covers a whole
 * nested data tree (and the graph/validate cost scales with that schema's table count, not the number of
 * workbooks on disk). Returns null when no refs.json is found (callers fall back to mock data).
 */
fun resolveSchema(project: Project): RefSchema? {
    val open = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path ?: return null
    val root = generateSequence(File(open).parentFile) { it.parentFile }
        .firstOrNull { File(it, "refs.json").isFile } ?: return null
    return loadRefSchema(root)
}

/** Open the table that holds [record] and jump to that row (double-click in the data explorer). */
fun openRecordInEditor(project: Project, schema: RefSchema, record: RefRecord) {
    val st = schema.table(record.table) ?: return
    // For composite / group records the id is "a·b" / group key — navigate by the first id column.
    val value = record.id.substringBefore('·')
    openSheet(project, schema, st, st.idCols.first(), value)
}

/** Open a table's sheet (double-click a table node in the ER view) — no specific row. */
fun openTableInEditor(project: Project, schema: RefSchema, tableId: String) {
    val st = schema.table(tableId) ?: return
    openSheet(project, schema, st, st.idCols.first(), "")
}

private fun openSheet(project: Project, schema: RefSchema, st: SchemaTable, idCol: String, value: String) {
    val path = File(schema.baseDir, st.file).path.replace('\\', '/')
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return
    FileEditorManager.getInstance(project).openFile(vf, true)
    reveal(project, vf, st.sheet, idCol, value, attempts = 25)
}

/** The editor opens asynchronously (streaming); retry on the EDT until the sheet/row is ready. */
private fun reveal(project: Project, vf: VirtualFile, sheet: String, col: String, value: String, attempts: Int) {
    if (attempts <= 0 || project.isDisposed) return
    ApplicationManager.getApplication().invokeLater({
        val ok = FileEditorManager.getInstance(project).getEditors(vf)
            .filterIsInstance<XlsxFileEditor>().firstOrNull()
            ?.revealSheetRow(sheet, col, value) ?: false
        if (!ok) Timer(120) { reveal(project, vf, sheet, col, value, attempts - 1) }.apply { isRepeats = false }.start()
    }, ModalityState.any())
}
