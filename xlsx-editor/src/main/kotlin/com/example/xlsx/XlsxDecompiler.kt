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
            readBytes(file)
        } catch (t: Throwable) {
            return "[${file.name}] 내용을 읽을 수 없습니다: ${t.message}"
        }
        if (bytes.isEmpty()) return ""
        return XlsxTextProjection.toText(bytes, isLegacyXls = file.extension.equals("xls", ignoreCase = true))
    }

    private fun readBytes(file: VirtualFile): ByteArray {
        // contentsToByteArray honors the IDE's per-file size limit (~20 MB) — go through the io
        // file for big LOCAL workbooks; old-revision contents arrive as light files (no io path)
        // and are already in memory anyway.
        val io = runCatching { VfsUtilCore.virtualToIoFile(file) }.getOrNull()
        return if (io != null && io.isFile) io.readBytes() else file.contentsToByteArray()
    }
}
