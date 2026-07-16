package com.example.xlsx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fan-in clustering for the record explorer: a hub record referenced by hundreds of rows must not
 * draw hundreds of nodes — per referencing table, records fold into ONE cluster node (with a single
 * aggregated edge) once they outnumber FANIN_CLUSTER_THRESHOLD; small tables stay individual.
 */
class FanInClusterTest {

    private val center = RefRecord("Item", "4021", "롱소드")

    private fun link(table: String, id: Int) =
        RefLink(RefRecord(table, "$id", "$table$id"), "ItemId", center)

    @Test
    fun `small fan-in stays individual — nodes and links untouched`() {
        val inbound = (1..3).map { link("Quest", it) }
        val (nodes, links) = clusterFanIn(center, inbound)
        assertEquals(inbound.map { it.from }, nodes)
        assertEquals(inbound, links)
    }

    @Test
    fun `large fan-in folds into one cluster with one aggregated edge`() {
        val inbound = (1..47).map { link("Monster", it) }
        val (nodes, links) = clusterFanIn(center, inbound)

        val cluster = nodes.single()
        assertTrue(cluster.isCluster)
        assertEquals("Monster", cluster.table)
        assertEquals(47, cluster.clusterRecords.size)
        assertEquals(inbound.map { it.from }, cluster.clusterRecords) // member order preserved

        val edge = links.single()
        assertEquals(cluster, edge.from)
        assertEquals(FANIN_CLUSTER_EDGE, edge.column)
        assertEquals(center, edge.to)
    }

    @Test
    fun `mixed tables — clustered table folds at its first occurrence, small tables keep order`() {
        val inbound = listOf(link("Quest", 1), link("Monster", 1), link("Quest", 2)) +
            (2..10).map { link("Monster", it) }
        val (nodes, links) = clusterFanIn(center, inbound)

        // Quest (2 records) stays individual; Monster (10) clusters where it first appeared.
        assertEquals(listOf("Quest", "Monster", "Quest"), nodes.map { it.table })
        assertTrue(nodes[1].isCluster)
        assertEquals(10, nodes[1].clusterRecords.size)

        assertEquals(1, links.count { it.from.isCluster }) // one aggregated Monster edge
        assertEquals(2, links.count { it.from.table == "Quest" }) // Quest links pass through unchanged
    }

    @Test
    fun `same record referencing via two columns is one member, links deduped only by clustering`() {
        val monster = RefRecord("Monster", "1", "고블린")
        val inbound = listOf(
            RefLink(monster, "DropItemId", center),
            RefLink(monster, "RareDropId", center),
        ) + (2..6).map { link("Monster", it) }
        val (nodes, _) = clusterFanIn(center, inbound)
        assertEquals(6, nodes.single().clusterRecords.size) // distinct records, not distinct links
    }
}
