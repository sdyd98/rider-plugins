package com.example.xlsx

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * P0 regression pin: refs.json entries carry EXPLICIT `"display": null` ("decided: no display
 * column"). Rewriting the file through the MCP tools must preserve those nulls on every UNTOUCHED
 * entry, and read_table_refs must show them — in the field, one write_table_refs call silently
 * stripped the key from 91 unrelated tables, flipping them back to "undecided".
 */
class RefsMcpNullPreservationTest {

    @Test
    fun `write_table_refs preserves explicit display-null on untouched entries`(@TempDir dir: File) = runBlocking {
        File(dir, "refs.json").writeText(
            """
            {
              "version": 1,
              "tables": {
                "Done": { "file": "a.xlsx", "sheet": "Done", "id": ["Id"], "display": null, "refs": [] },
                "Skel": { "file": "a.xlsx", "sheet": "Skel" }
              }
            }
            """.trimIndent(),
        )
        val tools = RefsMcpToolset()

        // A skeleton-only write of an unrelated table (no field codes claimed → no workbook lookup).
        val res = tools.write_table_refs(dir.path, "Other", """{"file": "b.xlsx", "sheet": "Other"}""")
        assertTrue(res.contains("\"ok\": true"), res)

        // The untouched entry keeps its explicit null — present as a key, valued null.
        val done = JsonParser.parseString(File(dir, "refs.json").readText())
            .asJsonObject.getAsJsonObject("tables").getAsJsonObject("Done")
        assertTrue(done.has("display") && done.get("display").isJsonNull, "explicit display:null was dropped")

        // read_table_refs shows the null too — "decided: none" stays distinguishable from "undecided".
        val read = tools.read_table_refs(dir.path, "Done")
        assertTrue(read.contains("\"display\": null"), read)

        // And the progress query still counts Done as DECIDED on display.
        val progress = tools.list_unfilled_tables(dir.path)
        assertTrue(progress.contains("\"undecidedDisplay\": 0"), progress)
    }
}
