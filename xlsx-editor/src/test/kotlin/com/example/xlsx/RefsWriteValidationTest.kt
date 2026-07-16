package com.example.xlsx

import com.google.gson.JsonParser
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream

/**
 * The write-time guards of write_table_refs: wrong-TYPED fields must be rejected AT WRITE (a single
 * one nulls the whole schema at load — loadRefSchema's blanket runCatching), and the pure helpers
 * that back the referential/field-code checks must extract exactly what the entry claims.
 * SheetScanner.rowValues (the header lookup the field-code check runs on) is pinned against a real
 * workbook.
 */
class RefsWriteValidationTest {

    private fun entry(json: String) = JsonParser.parseString(json).asJsonObject

    // ---- shape(): the mistakes that used to pass validation and invalidate the WHOLE schema ----

    @Test
    fun `wrong-typed fields are rejected with the field named`() {
        val base = """"file": "a.xlsx", "sheet": "S""""
        assertNull(RefsWriteValidation.shape(entry("""{$base}""")))

        assertTrue(RefsWriteValidation.shape(entry("""{$base, "display": ["Name","Grade"]}"""))!!.contains("display"))
        assertNull(RefsWriteValidation.shape(entry("""{$base, "display": null}"""))) // explicit "no display" marker
        assertNull(RefsWriteValidation.shape(entry("""{$base, "display": "Name"}""")))

        assertTrue(RefsWriteValidation.shape(entry("""{$base, "headerRow": "first"}"""))!!.contains("headerRow"))
        assertNull(RefsWriteValidation.shape(entry("""{$base, "headerRow": 2, "dataStartRow": 4}""")))

        assertTrue(RefsWriteValidation.shape(entry("""{$base, "id": "Id"}"""))!!.contains("id"))
        assertTrue(RefsWriteValidation.shape(entry("""{$base, "id": [{"col": "Id"}]}"""))!!.contains("id"))
        assertNull(RefsWriteValidation.shape(entry("""{$base, "id": ["Id"]}""")))

        val refBase = """"id": ["Id"], "refs": [{"from": ["X"], "to": "T""""
        assertTrue(RefsWriteValidation.shape(entry("""{$base, $refBase, "by": "GroupId"}]}"""))!!.contains("by"))
        assertNull(RefsWriteValidation.shape(entry("""{$base, $refBase, "by": ["GroupId"]}]}""")))
        assertTrue(RefsWriteValidation.shape(entry("""{$base, $refBase, "split": {"d": ";"}}]}"""))!!.contains("split"))
        assertNull(RefsWriteValidation.shape(entry("""{$base, $refBase, "split": ";", "pattern": "i:(\\d+)"}]}""")))
    }

    @Test
    fun `underscore-prefixed AI metadata is never validated`() {
        assertNull(
            RefsWriteValidation.shape(
                entry("""{"file": "a.xlsx", "sheet": "S", "_note": ["any", {"shape": 1}], "_source": 42}"""),
            ),
        )
    }

    // ---- the pure extractors behind the referential / field-code / overwrite checks ----

    @Test
    fun `extractors collect exactly what the entry claims`() {
        val e = entry(
            """{
              "file": "a.xlsx", "sheet": "S", "headerRow": 2, "id": ["Id", "Slot"], "display": "Name",
              "refs": [
                {"from": ["ItemId"], "to": "Item", "when": {"Type": ["3"]}, "_source": "code"},
                {"from": ["GroupId"], "to": "Drop", "by": ["GKey"]}
              ]
            }""",
        )
        assertEquals(listOf("Id", "Slot", "Name", "ItemId", "Type", "GroupId"), RefsWriteValidation.ownSheetCodes(e))
        assertEquals(listOf("Item", "Drop"), RefsWriteValidation.refTargets(e))
        assertEquals(listOf("Drop" to listOf("GKey")), RefsWriteValidation.byCodesPerTarget(e))
        assertEquals(2, RefsWriteValidation.headerRowOf(e))
        assertTrue(RefsWriteValidation.isFilled(e))
        assertTrue(!RefsWriteValidation.isFilled(entry("""{"file": "a.xlsx", "sheet": "S"}"""))) // skeleton
        assertTrue(!RefsWriteValidation.isFilled(entry("""{"file": "a.xlsx", "sheet": "S", "id": []}""")))
    }

    // ---- classify(): the shape buckets behind list_unfilled_tables (progress tracking) ----

    @Test
    fun `classify buckets skeletons, undecided refs-and-display orthogonally, and malformed entries`() {
        val tables = entry(
            """{
              "Skeleton":    {"file": "a.xlsx", "sheet": "A"},
              "Complete":    {"file": "a.xlsx", "sheet": "B", "id": ["Id"], "display": "Name", "refs": []},
              "NoRefsKey":   {"file": "a.xlsx", "sheet": "C", "id": ["Id"], "display": "Name"},
              "NoDisplay":   {"file": "a.xlsx", "sheet": "D", "id": ["Id"], "refs": []},
              "NeitherYet":  {"file": "a.xlsx", "sheet": "E", "id": ["Id"]},
              "DisplayNull": {"file": "a.xlsx", "sheet": "F", "id": ["Id"], "display": null, "refs": []},
              "Broken":      "not an object"
            }""",
        )
        val p = RefsWriteValidation.classify(tables)
        assertEquals(listOf("Skeleton"), p.unfilled)
        assertEquals(listOf("NoRefsKey", "NeitherYet"), p.undecidedRefs)
        assertEquals(listOf("NoDisplay", "NeitherYet"), p.undecidedDisplay) // orthogonal: NeitherYet in both
        assertEquals(listOf("Broken"), p.malformed) // counted in NO bucket (used to inflate "filled")
        // display: null and refs: [] are explicit "decided: none" — Complete/DisplayNull in no list.
    }

    // ---- SheetScanner.rowValues: the header lookup the field-code check runs on ----

    @Test
    fun `rowValues returns exactly the requested header row`(@TempDir dir: File) {
        withPoiClassLoader {
            val wb = XSSFWorkbook()
            val s = wb.createSheet("Item")
            s.createRow(0).createCell(0).setCellValue("아이템 정의서") // title row above the header
            s.createRow(1).apply { // header row = 1-based row 2
                createCell(0).setCellValue("Id")
                createCell(1).setCellValue(" Name ") // must come back TRIMMED (loader compares trimmed)
                createCell(3).setCellValue("Price") // gap at C
            }
            s.createRow(3).apply { createCell(0).setCellValue(1.0); createCell(1).setCellValue("검") }
            FileOutputStream(File(dir, "core.xlsx")).use { wb.write(it) }
            wb.close()
        }

        val header = SheetScanner.rowValues(dir, "core.xlsx", "Item", 2)
        assertNotNull(header)
        assertEquals(mapOf("A" to "Id", "B" to "Name", "D" to "Price"), header)

        // A row with no values (wrong headerRow judgment) is an EMPTY map, not the next row's values.
        assertEquals(emptyMap<String, String>(), SheetScanner.rowValues(dir, "core.xlsx", "Item", 3))
        // Missing sheet/file → null (distinguishable from "empty header row").
        assertNull(SheetScanner.rowValues(dir, "core.xlsx", "Nope", 2))
        assertNull(SheetScanner.rowValues(dir, "missing.xlsx", "Item", 2))
    }
}
