package com.example.xlsx

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Binary file type for `.xlsx` so the platform routes the file to our grid editor instead of
 * treating it as a generic ZIP archive or unknown binary.
 */
class XlsxFileType private constructor() : FileType {
    override fun getName(): String = "Excel Spreadsheet"
    override fun getDescription(): String = "Excel spreadsheet (.xlsx / .xls)"
    override fun getDefaultExtension(): String = "xlsx"
    override fun getIcon(): Icon? = null
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = false
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null

    companion object {
        @JvmField
        val INSTANCE: XlsxFileType = XlsxFileType()
    }
}
