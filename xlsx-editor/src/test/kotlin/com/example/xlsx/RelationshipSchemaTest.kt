package com.example.xlsx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A half-authored refs.json (build_refs skeletons the AI hasn't filled yet) must not poison the graph:
 * entries without an `id` are excluded from the loaded schema — not parsed with the default 1/4 layout —
 * and refs pointing at them are dropped (they cannot resolve until the target's id is recorded).
 */
class RelationshipSchemaTest {

    private fun write(dir: File, json: String) = File(dir, "refs.json").writeText(json)

    @Test
    fun `unfilled skeletons are excluded and refs to them dropped`(@TempDir dir: File) {
        write(
            dir,
            """
            {
              "version": 1,
              "nullValues": ["0", "-1"],
              "tables": {
                "Item":  { "file": "core.xlsx", "sheet": "Item", "headerRow": 1, "dataStartRow": 4,
                           "id": ["Id"], "display": "Name", "refs": [] },
                "Skill": { "file": "core.xlsx", "sheet": "Skill", "id": ["Id"],
                           "refs": [
                             { "from": ["RewardItemId"], "to": "Item" },
                             { "from": ["EffectIds"], "to": "Effect", "split": ";" }
                           ] },
                "Effect":  { "file": "core.xlsx", "sheet": "Effect" },
                "Monster": { "file": "world.xlsx", "sheet": "Monster" }
              }
            }
            """.trimIndent(),
        )
        val schema = loadRefSchema(dir)!!

        assertEquals(listOf("Item", "Skill"), schema.tables.map { it.tableId })
        assertEquals(listOf("Effect", "Monster"), schema.unfilledTables)

        // Skill's ref to the unfilled Effect is dropped (and counted); the ref to filled Item survives.
        val skill = schema.table("Skill")!!
        assertEquals(listOf("Item"), skill.refs.map { it.toTable })
        assertEquals(1, schema.refsToUnfilled)

        assertNull(schema.table("Effect"))
        assertEquals(setOf("0", "-1"), schema.nullValues)
    }

    @Test
    fun `skeleton-only schema loads with zero tables, not garbage defaults`(@TempDir dir: File) {
        write(
            dir,
            """{ "version": 1, "tables": {
                 "A": { "file": "a.xlsx", "sheet": "A" },
                 "B": { "file": "a.xlsx", "sheet": "B" } } }""",
        )
        val schema = loadRefSchema(dir)!!
        assertTrue(schema.tables.isEmpty())
        assertEquals(listOf("A", "B"), schema.unfilledTables)
    }

    @Test
    fun `missing or invalid refs json returns null`(@TempDir dir: File) {
        assertNull(loadRefSchema(dir))
        write(dir, "not json at all {")
        assertNull(loadRefSchema(dir))
    }
}
