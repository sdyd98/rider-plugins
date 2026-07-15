package com.example.xlsx

import com.intellij.openapi.fileTypes.BinaryFileDecompiler
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Text "decompiler" for the Excel file type — the same platform mechanism that lets `.class`
 * files diff as decompiled source. Registered via `filetype.decompiler` in plugin.xml, it makes
 * VCS/diff windows show a spreadsheet as its [XlsxTextProjection] TSV instead of
 * "binary files differ". The editor tab is unaffected (our grid editor hides other editors).
 */
class XlsxDecompiler : BinaryFileDecompiler {

    override fun decompile(file: VirtualFile): CharSequence {
        val bytes = try {
            readWorkbookBytes(file)
        } catch (t: Throwable) {
            return "[${file.name}] 내용을 읽을 수 없습니다: ${t.message}"
        }
        if (bytes.isEmpty()) return ""
        return XlsxTextProjection.toText(bytes, isLegacyXls = file.extension.equals("xls", ignoreCase = true))
    }
}

/** Workbook bytes for diff-side files (shared by the text projection and the grid diff tool). */
internal fun readWorkbookBytes(file: VirtualFile): ByteArray {
    // contentsToByteArray honors the IDE's per-file size limit (~20 MB) — go through the io
    // file for big LOCAL workbooks. Local-FS check is load-bearing: VCS old-revision files
    // (ContentRevisionVirtualFile etc.) report the ORIGINAL workspace path, and
    // virtualToIoFile is just File(path) — without the check the old-revision pane would
    // silently read the CURRENT workbook and every diff would show zero changes.
    if (file.isInLocalFileSystem) {
        val io = runCatching { VfsUtilCore.virtualToIoFile(file) }.getOrNull()
        if (io != null && io.isFile) return io.readBytes()
    }
    return file.contentsToByteArray()
}
