package com.example.xlsx

/**
 * Deterministic TEXT projection of a workbook — one TSV line per row, sheets separated by a
 * `===== 시트: name =====` header — so the IDE's text diff can show ROW-LEVEL changes for a
 * binary spreadsheet (VCS diff previously said only "binary files differ"). Same projection for
 * both versions of a file ⇒ the diff lines up cell-for-cell.
 *
 * Pure bytes → CharSequence (headless-testable); [XlsxDecompiler] is the platform glue.
 */
object XlsxTextProjection {

    /** Hard output cap (~40 MB of chars) so a pathological workbook can't OOM a diff window. */
    private const val MAX_CHARS = 20_000_000

    fun toText(bytes: ByteArray, isLegacyXls: Boolean): CharSequence {
        val sb = StringBuilder(1 shl 20)
        try {
            if (isLegacyXls) projectXls(bytes, sb) else projectXlsx(bytes, sb)
        } catch (t: Throwable) {
            sb.append("\n[스프레드시트 텍스트 변환 실패: ${t.message}]\n")
        }
        return sb
    }

    private fun projectXlsx(bytes: ByteArray, sb: StringBuilder) {
        val source = XlsxStreamingReader.open(bytes)
        source.readSheets { i, xml ->
            if (sb.length < MAX_CHARS) {
                appendSheetHeader(sb, source.names.getOrNull(i) ?: "Sheet$i")
                XlsxStreamingReader.parseSheet(xml, source.sst, source.styles, 4096) { batch ->
                    if (sb.length < MAX_CHARS) for (row in batch) appendRow(sb, row)
                }
                truncateNoticeIfNeeded(sb)
            }
        }
    }

    private fun projectXls(bytes: ByteArray, sb: StringBuilder) {
        val wb = XlsWorkbookReader.open(bytes)
        try {
            for (i in 0 until wb.numberOfSheets) {
                if (sb.length >= MAX_CHARS) break
                val data = XlsWorkbookReader.renderSheet(wb, i)
                appendSheetHeader(sb, data.name)
                for (row in data.rows) {
                    if (sb.length >= MAX_CHARS) break
                    appendRow(sb, row)
                }
                truncateNoticeIfNeeded(sb)
            }
        } finally {
            withPoiClassLoader { runCatching { wb.close() } }
        }
    }

    private fun appendSheetHeader(sb: StringBuilder, name: String) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append("===== 시트: ").append(name).append(" =====\n")
    }

    private fun appendRow(sb: StringBuilder, row: Array<String?>) {
        for ((i, cell) in row.withIndex()) {
            if (i > 0) sb.append('\t')
            if (cell != null) appendSanitized(sb, cell)
        }
        sb.append('\n')
    }

    /** Keep the projection line-stable: cell-internal tabs/newlines become spaces. */
    private fun appendSanitized(sb: StringBuilder, s: String) {
        for (ch in s) sb.append(if (ch == '\t' || ch == '\n' || ch == '\r') ' ' else ch)
    }

    private fun truncateNoticeIfNeeded(sb: StringBuilder) {
        if (sb.length >= MAX_CHARS) sb.append("\n… (이후 생략 — 워크북이 너무 큽니다)\n")
    }
}
