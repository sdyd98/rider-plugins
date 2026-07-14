package com.example.xlsx

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Stage-timing benchmark for the legacy `.xls` open path: full HSSF workbook build
 * ([XlsWorkbookReader.open]) vs per-sheet display rendering ([XlsWorkbookReader.renderSheet]).
 * The generated game-data-like workbook (4 sheets × 55k rows × 12 cols ≈ 2.6M cells — .xls is
 * hard-capped at 65,536 rows) is cached under build/perf/.
 */
class XlsOpenBenchmark {

    private fun ms(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000

    @Test
    fun `stage timings for a game-data-like xls workbook`() {
        val file = File("build/perf/big-gamedata.xls")
        if (!file.exists()) generate(file)
        println("BENCH xls file: ${file.length() / (1024 * 1024)} MB")

        val bytes = file.readBytes()

        var t = System.nanoTime()
        val wb = XlsWorkbookReader.open(bytes)
        println("BENCH xls open (full HSSF build): ${ms(t)} ms")

        t = System.nanoTime()
        val names = XlsWorkbookReader.sheetNames(wb)
        println("BENCH xls sheetNames: ${ms(t)} ms — $names")

        var rows = 0
        var cells = 0L
        t = System.nanoTime()
        for (i in names.indices) {
            val data = XlsWorkbookReader.renderSheet(wb, i)
            rows += data.rows.size
            for (r in data.rows) cells += r.size
        }
        println("BENCH xls renderSheet (all, serial): ${ms(t)} ms — $rows rows, $cells cells")

        // Pre-optimization baseline: plain DataFormatter, no string dedup (what renderSheet did
        // before the fast-General/StringPool port) — quantifies that change on the same data.
        t = System.nanoTime()
        var cells2 = 0L
        withPoiClassLoader {
            val plain = org.apache.poi.ss.usermodel.DataFormatter()
            for (i in names.indices) {
                val sheet = wb.getSheetAt(i)
                for (r in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(r) ?: continue
                    val last = row.lastCellNum.toInt()
                    for (c in 0 until last) {
                        val cell = row.getCell(c) ?: continue
                        plain.formatCellValue(cell)
                        cells2++
                    }
                }
            }
        }
        println("BENCH xls render with PLAIN DataFormatter (baseline): ${ms(t)} ms — $cells2 cells")
        withPoiClassLoader { wb.close() }
    }

    /** 4 sheets × 55k rows × 12 cols, game-data-ish (ids, repeated Korean names, enums, stats). */
    private fun generate(file: File) {
        file.parentFile.mkdirs()
        val started = System.nanoTime()
        val categories = listOf("무기", "방어구", "소비", "재료", "장신구", "퀘스트")
        val grades = listOf("일반", "고급", "희귀", "영웅", "전설")
        withPoiClassLoader {
            val wb = HSSFWorkbook()
            try {
                for (s in 0 until 4) {
                    val sheet = wb.createSheet("Table$s")
                    val header = sheet.createRow(0)
                    for (c in 0 until 12) header.createCell(c).setCellValue("Field$c")
                    for (r in 1..55_000) {
                        val row = sheet.createRow(r)
                        val id = s * 1_000_000 + r
                        row.createCell(0).setCellValue(id.toDouble())
                        row.createCell(1).setCellValue("아이템_${categories[r % 6]}_${r % 5000}")
                        row.createCell(2).setCellValue(categories[r % 6])
                        row.createCell(3).setCellValue(grades[(r / 7) % 5])
                        for (c in 4 until 10) {
                            row.createCell(c).setCellValue((((id * 31 + c * 131) % 99_991)).toDouble())
                        }
                        row.createCell(10).setCellValue(((id * 31) % 99_991) + (id % 100) / 100.0)
                        row.createCell(11).setCellValue(if (r % 3 == 0) "TRUE" else "FALSE")
                    }
                }
                FileOutputStream(file).use { wb.write(it) }
            } finally {
                wb.close()
            }
        }
        println("BENCH generated xls ${file.length() / (1024 * 1024)} MB in ${ms(started)} ms")
    }
}
