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

    @Test
    fun `loadRefSchemaResult preserves the parse failure for the MCP tools to report`(@TempDir dir: File) {
        // Missing file names the folder; a broken file carries the parser's actual message —
        // validate_refs used to collapse both into a generic "no valid refs.json".
        assertTrue(loadRefSchemaResult(dir).exceptionOrNull()!!.message!!.contains("no refs.json"))
        write(dir, """{ "version": 1, "tables": 42 }""") // tables must be an object
        val e = loadRefSchemaResult(dir).exceptionOrNull()
        assertTrue(e != null && !e.message.isNullOrBlank(), "parse failure must carry a message")
    }

    @Test
    fun `when clauses match empty AND absent cells, support notIn, and rowFilter parses`(@TempDir dir: File) {
        write(
            dir,
            """
            {
              "version": 1,
              "tables": {
                "Npc":   { "file": "a.xlsx", "sheet": "Npc", "id": ["Id"], "display": null, "refs": [] },
                "Quest": { "file": "a.xlsx", "sheet": "Quest", "id": ["Id"], "display": null,
                           "rowFilter": {"Unused": ["", "0"]},
                           "refs": [
                             { "from": ["NpcId"],  "to": "Npc", "when": {"Unused": ["", "0"]} },
                             { "from": ["ItemId"], "to": "Npc", "when": {"Type": {"notIn": ["9"]}} }
                           ] }
              }
            }
            """.trimIndent(),
        )
        val quest = loadRefSchema(dir)!!.table("Quest")!!

        // Rows sparse-store only NON-EMPTY cells, so "" must match an ABSENT key too — the field bug:
        // when {"Unused": ["", "0"]} excluded every active row (whose Unused cell is empty).
        val whenEmpty = quest.refs[0]
        assertTrue(whenEmpty.appliesTo(emptyMap()), "absent cell must match \"\"")
        assertTrue(whenEmpty.appliesTo(mapOf("Unused" to "0")))
        assertTrue(whenEmpty.appliesTo(mapOf("Unused" to " ")), "blank cell must match \"\"")
        assertTrue(!whenEmpty.appliesTo(mapOf("Unused" to "1")))

        // notIn negates: Type 9 excluded, anything else (including absent) applies.
        val notIn = quest.refs[1]
        assertTrue(!notIn.appliesTo(mapOf("Type" to "9")))
        assertTrue(notIn.appliesTo(mapOf("Type" to "3")))
        assertTrue(notIn.appliesTo(emptyMap()))

        // Table-level rowFilter parses into the same clause semantics.
        val rf = quest.rowFilter!!
        assertTrue(rf.all { it.matches(emptyMap()) }, "active row (empty Unused) passes the filter")
        assertTrue(!rf.all { it.matches(mapOf("Unused" to "1")) }, "unused row is dropped")
    }

    @Test
    fun `union to parses, keeps order, and drops unfilled members`(@TempDir dir: File) {
        write(
            dir,
            """
            {
              "version": 1,
              "tables": {
                "Npc":   { "file": "a.xlsx", "sheet": "Npc", "id": ["Id"], "display": null, "refs": [] },
                "Npc2":  { "file": "b.xlsx", "sheet": "Npc2", "id": ["Id"], "display": null, "refs": [] },
                "Skel":  { "file": "c.xlsx", "sheet": "Skel" },
                "Quest": { "file": "a.xlsx", "sheet": "Quest", "id": ["Id"], "display": null,
                           "refs": [
                             { "from": ["NpcId"], "to": ["Npc", "Npc2"] },
                             { "from": ["HalfId"], "to": ["Npc", "Skel"] },
                             { "from": ["GoneId"], "to": ["Skel"] }
                           ] }
              }
            }
            """.trimIndent(),
        )
        val schema = loadRefSchema(dir)!!
        val quest = schema.table("Quest")!!

        assertEquals(listOf("Npc", "Npc2"), quest.refs[0].toTables)
        assertEquals("Npc", quest.refs[0].toTable) // primary target = first listed
        // An unfilled union member is dropped from the union; a ref with NO filled member is dropped whole.
        assertEquals(listOf("Npc"), quest.refs[1].toTables)
        assertEquals(2, quest.refs.size)
        assertEquals(1, schema.refsToUnfilled)
    }
}
