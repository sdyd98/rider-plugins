package com.example.xlsx

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Headless stage-timing benchmark for the xlsx open path — pinpoints where a ~30 MB game-data-like
 * workbook spends its time: package open (shared strings + styles), per-sheet XML decompression,
 * SAX parse into display strings, and the separate formula scan. Prints timings to stdout
 * (testLogging.showStandardStreams). The generated workbook is cached under build/perf/.
 */
class XlsxOpenBenchmark {

    private fun ms(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000

    @Test
    fun `stage timings for a game-data-like 30MB workbook`() {
        val file = File("build/perf/big-gamedata.xlsx")
        if (!file.exists()) generate(file)
        println("BENCH file: ${file.length() / (1024 * 1024)} MB")

        var t = System.nanoTime()
        val source = XlsxStreamingReader.open(file)
        println("BENCH open (sst + styles + sheet names): ${ms(t)} ms")

        val xmls = ArrayList<ByteArray>()
        t = System.nanoTime()
        source.readSheets { _, xml -> xmls.add(xml) }
        println("BENCH readSheets (decompress all): ${ms(t)} ms — xml total ${xmls.sumOf { it.size } / (1024 * 1024)} MB")

        t = System.nanoTime()
        var rows = 0
        var cells = 0L
        for (xml in xmls) {
            XlsxStreamingReader.parseSheet(xml, source.sst, source.styles, 2000) { batch ->
                rows += batch.size
                for (r in batch) cells += r.size
            }
        }
        println("BENCH parseSheet (all sheets, serial): ${ms(t)} ms — $rows rows, $cells cells")

        t = System.nanoTime()
        var formulas = 0
        for (xml in xmls) formulas += FormulaScanner.scan(xml).size
        println("BENCH formulaScan (all sheets, serial): ${ms(t)} ms — $formulas formulas")

        // How much of parseSheet is NUMBER FORMATTING? Re-parse with a formatter whose raw-contents
        // path is a no-op — the difference to the run above is DataFormatter's share.
        t = System.nanoTime()
        var rows2 = 0
        for (xml in xmls) {
            parseWithTrivialFormatter(xml, source) { batch -> rows2 += batch.size }
        }
        println("BENCH parseSheet with NO-OP number formatting: ${ms(t)} ms — $rows2 rows")
    }

    private fun parseWithTrivialFormatter(
        xml: ByteArray,
        source: XlsxStreamingReader.Source,
        onBatch: (List<Array<String?>>) -> Unit,
    ) = withPoiClassLoader {
        val collector = object : org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler {
            var n = 0
            val rows = ArrayList<Array<String?>>(2000)
            override fun startRow(rowNum: Int) {}
            override fun endRow(rowNum: Int) {
                rows.add(EMPTY_ROW)
                if (rows.size >= 2000) { onBatch(ArrayList(rows)); rows.clear() }
            }
            override fun cell(ref: String?, v: String?, c: org.apache.poi.xssf.usermodel.XSSFComment?) { n++ }
        }
        val trivial = object : org.apache.poi.ss.usermodel.DataFormatter() {
            override fun formatRawCellContents(value: Double, formatIndex: Int, formatString: String?): String = "0"
        }
        val parser = org.apache.poi.util.XMLHelper.newXMLReader()
        parser.contentHandler = org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler(
            source.styles, null, source.sst, collector, trivial, false,
        )
        parser.parse(org.xml.sax.InputSource(java.io.ByteArrayInputStream(xml)))
    }

    private companion object {
        val EMPTY_ROW = arrayOfNulls<String>(0)
    }

    /** ~30 MB: 6 sheets x 60k rows x 15 cols, game-data-ish (ids, repeated Korean names, enums, floats). */
    private fun generate(file: File) {
        file.parentFile.mkdirs()
        val started = System.nanoTime()
        val categories = listOf("무기", "방어구", "소비", "재료", "장신구", "퀘스트")
        val grades = listOf("일반", "고급", "희귀", "영웅", "전설")
        withPoiClassLoader {
            val wb = SXSSFWorkbook(100)
            try {
                for (s in 0 until 6) {
                    val sheet = wb.createSheet("Table$s")
                    val header = sheet.createRow(0)
                    for (c in 0 until 15) header.createCell(c).setCellValue("Field$c")
                    for (r in 1..60_000) {
                        val row = sheet.createRow(r)
                        val id = s * 1_000_000 + r
                        row.createCell(0).setCellValue(id.toDouble())
                        row.createCell(1).setCellValue("아이템_${categories[r % 6]}_${r % 5000}")
                        row.createCell(2).setCellValue(categories[r % 6])
                        row.createCell(3).setCellValue(grades[(r / 7) % 5])
                        for (c in 4 until 10) {
                            // integral stats (attack/hp/level …) — the dominant numeric shape in game data
                            row.createCell(c).setCellValue((((id * 31 + c * 131) % 99_991)).toDouble())
                        }
                        for (c in 10 until 12) {
                            // a couple of fractional rate columns (deterministic, keeps zip entropy realistic)
                            row.createCell(c).setCellValue(((id * 31 + c * 131) % 99_991) + (id % 100) / 100.0)
                        }
                        row.createCell(12).setCellValue(if (r % 3 == 0) "TRUE" else "FALSE")
                        row.createCell(13).setCellValue("desc_${(id * 7) % 100_000}_설명 텍스트가 조금 길게 들어가는 컬럼")
                        row.createCell(14).setCellValue((r % 100).toDouble())
                    }
                }
                FileOutputStream(file).use { wb.write(it) }
            } finally {
                wb.dispose()
                wb.close()
            }
        }
        println("BENCH generated ${file.length() / (1024 * 1024)} MB in ${ms(started)} ms")
    }
}
