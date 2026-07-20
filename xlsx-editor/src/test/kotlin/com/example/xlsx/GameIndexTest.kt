package com.example.xlsx

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end pins for the field-reported refs bugs, on REAL workbooks through the streaming index:
 * a `when` accepting "" must match rows whose condition cell is EMPTY (sparse rows omit the cell
 * entirely), `rowFilter` must drop rows before indexing, union `to` must resolve across tables, and
 * validate_refs' per-relation stats must count what was actually evaluated.
 */
class GameIndexTest {

    /** dir/game.xlsx with Npc (ids 1,2) + Npc2 (ids 3,4) + Quest (see rows below) + refs.json. */
    private fun writeFixture(dir: File) {
        withPoiClassLoader {
            val wb = XSSFWorkbook()
            fun sheet(name: String, header: List<String>, rows: List<List<String?>>) {
                val s = wb.createSheet(name)
                s.createRow(0).apply { header.forEachIndexed { c, v -> createCell(c).setCellValue(v) } }
                rows.forEachIndexed { r, cells ->
                    val row = s.createRow(r + 1)
                    cells.forEachIndexed { c, v -> if (v != null) row.createCell(c).setCellValue(v) }
                }
            }
            sheet("Npc", listOf("Id", "Name"), listOf(listOf("1", "npc1"), listOf("2", "npc2")))
            sheet("Npc2", listOf("Id", "Name"), listOf(listOf("3", "npc3"), listOf("4", "npc4")))
            // Quest rows: active rows have an EMPTY Unused cell (cell absent — the sparse-row case);
            // the unused row has Unused=1. NpcId 3 lives only in Npc2 (union), 99 in neither.
            sheet(
                "Quest",
                listOf("Id", "Name", "NpcId", "Unused"),
                listOf(
                    listOf("10", "q10", "1", null),   // active → resolves in Npc
                    listOf("11", "q11", "3", null),   // active → resolves in Npc2 (union member 2)
                    listOf("12", "q12", "99", null),  // active → missing from the whole union
                    listOf("13", "q13", "2", "1"),    // unused → when excludes it (token NOT evaluated)
                ),
            )
            // Mob: rowFilter drops unused rows entirely (not indexed, no refs evaluated).
            sheet(
                "Mob",
                listOf("Id", "Name", "Unused"),
                listOf(listOf("70", "mob70", null), listOf("71", "mob71", "1")),
            )
            FileOutputStream(File(dir, "game.xlsx")).use { wb.write(it) }
            wb.close()
        }
        File(dir, "refs.json").writeText(
            """
            {
              "version": 1,
              "tables": {
                "Npc":  { "file": "game.xlsx", "sheet": "Npc", "headerRow": 1, "dataStartRow": 2,
                          "id": ["Id"], "display": "Name", "refs": [] },
                "Npc2": { "file": "game.xlsx", "sheet": "Npc2", "headerRow": 1, "dataStartRow": 2,
                          "id": ["Id"], "display": "Name", "refs": [] },
                "Quest": { "file": "game.xlsx", "sheet": "Quest", "headerRow": 1, "dataStartRow": 2,
                           "id": ["Id"], "display": "Name",
                           "refs": [
                             { "from": ["NpcId"], "to": ["Npc", "Npc2"], "when": {"Unused": ["", "0"]} }
                           ] },
                "Mob": { "file": "game.xlsx", "sheet": "Mob", "headerRow": 1, "dataStartRow": 2,
                         "id": ["Id"], "display": "Name", "rowFilter": {"Unused": ["", "0"]}, "refs": [] }
              }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `empty-cell when, union resolution, rowFilter, and per-relation stats`(@TempDir dir: File) {
        writeFixture(dir)
        val schema = loadRefSchema(dir)!!
        val index = GameDataLoader.buildIndex(schema)

        // when {"Unused": ["", "0"]}: the three ACTIVE rows (empty Unused cell) ARE evaluated —
        // the field bug excluded them all; the Unused=1 row is skipped.
        val stat = index.refStats.single { it.table == "Quest" }
        assertEquals("NpcId", stat.column)
        assertEquals("Npc,Npc2", stat.to)
        assertEquals(4, stat.rows)
        assertEquals(1, stat.skippedByWhen)
        assertEquals(3, stat.evaluatedRows)
        assertEquals(3, stat.tokens)

        // Union resolution: token 1 → Npc, token 3 → Npc2, token 99 → missing from the WHOLE union.
        assertTrue(index.reverse.containsKey("Npc|1"))
        assertTrue(index.reverse.containsKey("Npc2|3"))
        val broken = IndexRecordGraph(schema, index).validate().broken
        assertEquals(listOf("99"), broken.map { it.missingId })
        assertEquals("Npc,Npc2", broken.single().toTable)
        // The skipped row's token (2) must NOT appear — its when-condition excluded it.
        assertFalse(index.reverse.containsKey("Npc|2"))

        // rowFilter: the unused Mob row is not indexed at all; the active one is.
        assertTrue(index.idName.containsKey("Mob|70"))
        assertFalse(index.idName.containsKey("Mob|71"))
    }

    @Test
    fun `a schema workbook that cannot be indexed is reported, not silently omitted`(@TempDir dir: File) {
        writeFixture(dir)
        // Add a filled table pointing at a file that does not exist — its rows validate NOTHING,
        // which must surface as a loadError (validate_refs turns these into always-on warnings).
        val refs = File(dir, "refs.json")
        refs.writeText(
            refs.readText().replace(
                "\"Mob\":",
                """"Ghost": { "file": "gone.xlsx", "sheet": "Ghost", "headerRow": 1, "dataStartRow": 2,
                              "id": ["Id"], "display": null, "refs": [] },
                   "Mob":""",
            ),
        )
        val index = GameDataLoader.buildIndex(loadRefSchema(dir)!!)
        assertEquals(listOf("gone.xlsx" to "file not found"), index.loadErrors)
    }

    @Test
    fun `check_ref's where condition restricts the from side with when semantics`(@TempDir dir: File) {
        writeFixture(dir)
        // Quest: C = NpcId, D = Unused. The condition {"D": ["", "0"]} must keep the three ACTIVE
        // rows (D absent) and skip the Unused=1 row — so its token 2 never reaches the check.
        val o = SheetScanner.overlap(
            dir,
            "game.xlsx", "Quest", "C", 2, null,
            "game.xlsx", "Npc", "A", 2,
            ignore = emptySet(),
            fromWhere = listOf(WhenClause("D", setOf("", "0"))),
        )!!
        assertEquals(3, o.rowsEvaluated)
        assertEquals(1, o.rowsSkipped)
        assertEquals(3, o.fromTokens) // 1, 3, 99 — not the skipped row's 2
        assertEquals(1, o.matched)    // only "1" exists in Npc; 3 lives in Npc2, 99 nowhere
        assertTrue("2" !in o.missingSample, "the skipped row's token must not be evaluated")
        assertTrue("99" in o.missingSample)
    }
}
