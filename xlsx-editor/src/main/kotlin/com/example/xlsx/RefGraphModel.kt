package com.example.xlsx

import androidx.compose.ui.geometry.Offset

/**
 * View-model for the table-level relationship-map (ER) view. In production these are built from the
 * workbook's refs.json by [buildRefGraph] (see RelationshipSchema.kt); [mockRefGraph]/[mockDb] below
 * are only the fallback rendered when no refs.json is found (and were used to design the detail
 * level / interactions before the schema spec was locked).
 *
 * @param refTo       target table id this column references (null = plain column).
 * @param embedded    the reference lives inside a delimited/encoded string (shown as a badge).
 * @param conditional the ref applies only to rows matching its `when` condition (polymorphic column —
 *                    the same column appears once per target table).
 */
data class RefColumn(
    val name: String,
    val isId: Boolean = false,
    val refTo: String? = null,
    val embedded: Boolean = false,
    val conditional: Boolean = false,
)

data class RefTable(val id: String, val display: String, val columns: List<RefColumn>, val pos: Offset)

data class RefGraph(val tables: List<RefTable>) {
    fun table(id: String): RefTable? = tables.firstOrNull { it.id == id }
}

/** A small hand-built game-data-ish graph (items / skills / monsters …) to evaluate the visualization. */
fun mockRefGraph(): RefGraph = RefGraph(
    listOf(
        RefTable(
            "Skill", "Skill",
            listOf(
                RefColumn("Id", isId = true),
                RefColumn("Name"),
                RefColumn("RewardItemId", refTo = "Item"),
                RefColumn("EffectId", refTo = "Effect", embedded = true), // e.g. "fx_1;fx_2"
                RefColumn("Cooldown"),
            ),
            Offset(60f, 90f),
        ),
        RefTable(
            "Item", "Item",
            listOf(
                RefColumn("Id", isId = true),
                RefColumn("Name"),
                RefColumn("DropId", refTo = "Drop"),
                RefColumn("Price"),
            ),
            Offset(560f, 60f),
        ),
        RefTable(
            "Effect", "Effect",
            listOf(
                RefColumn("Id", isId = true),
                RefColumn("Power"),
            ),
            Offset(560f, 340f),
        ),
        RefTable(
            "Drop", "Drop",
            listOf(
                RefColumn("Id", isId = true),
                RefColumn("Gold"),
            ),
            Offset(980f, 220f),
        ),
        RefTable(
            "Monster", "Monster",
            listOf(
                RefColumn("Id", isId = true),
                RefColumn("Name"),
                RefColumn("SkillId", refTo = "Skill", embedded = true), // e.g. "10;11;12"
                RefColumn("DropTable", refTo = "Drop", embedded = true), // e.g. "101:50,102:30"
            ),
            Offset(60f, 400f),
        ),
        RefTable(
            "Quest", "Quest",
            listOf(
                RefColumn("Id", isId = true),
                RefColumn("RewardItemId", refTo = "Item"),
                RefColumn("MonsterId", refTo = "Monster"),
            ),
            Offset(60f, 640f),
        ),
    ),
)

// ---- Instance-level (actual data) model: records + resolved links between them ----

/**
 * One actual row, OR a **group** when [isGroup] (the reference resolves to a group key, not a single
 * row — then [members] are the rows that share that group key, shown inside the node).
 */
data class RefRecord(
    val table: String,
    val id: String,
    val name: String,
    val isGroup: Boolean = false,
    val members: List<String> = emptyList(),
    val broken: Boolean = false, // a reference resolved to this id/key but no such row exists
) {
    val label: String get() = if (isGroup) "$table ⟨$id⟩" else "$table #$id"
}

/** A resolved reference from one record to another (the actual edge), via [column]. */
data class RefLink(val from: RefRecord, val column: String, val to: RefRecord, val embedded: Boolean = false, val broken: Boolean = false)

/** One dangling reference: [from].[column] points at [toTable] [missingId], which does not exist. */
data class BrokenRef(val from: RefRecord, val column: String, val missingId: String, val toTable: String)

/** Workbook-wide integrity result: dangling refs + records nothing references (dead content). */
data class ValidationReport(val broken: List<BrokenRef>, val orphans: List<RefRecord>)

/**
 * What the data explorer needs from a record graph: the searchable record list, and a record's
 * outgoing / incoming links. Implementations may be eager ([MockDb]) or index-backed ([IndexRecordGraph]).
 */
interface RecordSource {
    val records: List<RefRecord>
    fun out(r: RefRecord): List<RefLink>
    fun inbound(r: RefRecord): List<RefLink>
    fun usageCount(r: RefRecord): Int // how many records reference r (reverse-index size)
    fun validate(): ValidationReport

    /** A good record to centre on first (cheap — must NOT force [records] to materialise). */
    fun defaultCenter(): RefRecord

    /** Resolve one record by table + display id (or group key) — O(1), without materialising [records]. */
    fun find(table: String, id: String): RefRecord?
}

/** Eager in-memory graph (all links precomputed) — used by the mock fallback. */
class MockDb(override val records: List<RefRecord>, val links: List<RefLink>) : RecordSource {
    override fun out(r: RefRecord): List<RefLink> = links.filter { it.from == r }
    override fun inbound(r: RefRecord): List<RefLink> = links.filter { it.to == r }
    override fun usageCount(r: RefRecord): Int = links.count { it.to == r }
    override fun defaultCenter(): RefRecord = records.firstOrNull() ?: RefRecord("", "", "")
    override fun find(table: String, id: String): RefRecord? = records.firstOrNull { it.table == table && it.id == id }
    override fun validate(): ValidationReport = ValidationReport(
        broken = links.filter { it.broken }.map { BrokenRef(it.from, it.column, it.to.id, it.to.table) },
        orphans = records.filter { rec -> !rec.isGroup && links.none { it.to == rec } },
    )
}

/** Mock records + links consistent with [mockRefGraph]; Skill #1 has a rich neighbourhood (good demo). */
fun mockDb(): MockDb {
    val skill1 = RefRecord("Skill", "1", "Fireball")
    val skill2 = RefRecord("Skill", "2", "Heal")
    val item5001 = RefRecord("Item", "5001", "HealthPotion")
    val item5002 = RefRecord("Item", "5002", "ManaPotion")
    val fx1 = RefRecord("Effect", "fx_1", "Burn")
    val fx2 = RefRecord("Effect", "fx_2", "Splash")
    val fx3 = RefRecord("Effect", "fx_3", "Regen")
    val d1 = RefRecord("Drop", "D1", "GoblinLoot")
    val d2 = RefRecord("Drop", "D2", "DragonHoard")
    // Group-keyed table: Monster.DropTable points at a DropTable GROUP (group key), not a single row.
    val dg1 = RefRecord("DropTable", "G1", "GoblinLoot", isGroup = true, members = listOf("Gold ×100", "Cloth ×2", "Dagger"))
    val dg2 = RefRecord("DropTable", "G2", "DragonHoard", isGroup = true, members = listOf("Gold ×500", "DragonScale ×3", "Excalibur"))
    val mon3 = RefRecord("Monster", "3", "Goblin")
    val mon4 = RefRecord("Monster", "4", "Dragon")
    val quest7 = RefRecord("Quest", "7", "SlayDragon")
    val records = listOf(skill1, skill2, item5001, item5002, fx1, fx2, fx3, d1, d2, dg1, dg2, mon3, mon4, quest7)
    val links = listOf(
        RefLink(skill1, "RewardItemId", item5001),
        RefLink(skill1, "EffectId", fx1, embedded = true),
        RefLink(skill1, "EffectId", fx2, embedded = true),
        RefLink(skill2, "RewardItemId", item5002),
        RefLink(skill2, "EffectId", fx3, embedded = true),
        RefLink(item5001, "DropId", d1),
        RefLink(item5002, "DropId", d2),
        RefLink(mon3, "SkillId", skill1, embedded = true),
        RefLink(mon3, "DropTable", dg1, embedded = true),
        RefLink(mon4, "SkillId", skill1, embedded = true),
        RefLink(mon4, "SkillId", skill2, embedded = true),
        RefLink(mon4, "DropTable", dg2, embedded = true),
        RefLink(quest7, "RewardItemId", item5001),
        RefLink(quest7, "MonsterId", mon4),
    )
    return MockDb(records, links)
}
