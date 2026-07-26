package com.example.xlsx

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.graph.GraphCanvas
import com.example.graph.GraphColors
import com.example.graph.GraphEdge
import com.example.graph.GraphFlow
import com.example.graph.GraphLayout
import com.example.graph.GraphNode
import com.example.graph.GraphRow
import org.jetbrains.jewel.ui.component.Text

/**
 * The table-level relationship map (ER style: a table's key + reference columns), shown as a LOCAL
 * neighbourhood — the centre table plus the tables it references and the tables that reference it. Click a
 * neighbour to re-centre, click the centre to open its sheet.
 *
 * The drawing is [GraphCanvas] from `:common` — the same canvas codemap's call graph and flow player use, so
 * a node graph looks and behaves the same everywhere in this repo. Everything that used to live here (card
 * measuring, the force layout, curved edges, pan/zoom/hover, the shadow stack) moved there unchanged; what
 * remains is this view's own meaning: which tables are in the neighbourhood, and what a row on a card is.
 *
 * Keeping the node count to a handful is still what makes the force layout cheap with thousands of tables in
 * the schema.
 */
@Composable
fun RefGraphView(
    graph: RefGraph,
    fgArgb: Int,
    bgArgb: Int,
    onOpenTable: (String) -> Unit = {},
    centerRequest: TableRef? = null,
) {
    // target id -> tables referencing it (built once); used to find a centre's incoming neighbours.
    val referrers = remember(graph) {
        val m = HashMap<String, MutableSet<String>>()
        graph.tables.forEach { s -> s.columns.forEach { c -> c.refTo?.let { m.getOrPut(it) { HashSet() }.add(s.id) } } }
        m
    }

    // Default centre = the most-connected table (out refs + referrers).
    var center by remember(graph) {
        mutableStateOf(
            graph.tables.maxByOrNull { t -> t.columns.count { it.refTo != null } + (referrers[t.id]?.size ?: 0) }?.id,
        )
    }

    // The grid (Ctrl+F) can request a centre — re-centre on it whenever a new request arrives.
    LaunchedEffect(centerRequest, graph) {
        centerRequest?.let { r -> if (graph.table(r.table) != null) center = r.table }
    }

    // Visible subgraph for the current centre: centre + tables it references + tables that reference it.
    val sub = remember(center, graph) {
        val c = center?.let { graph.table(it) } ?: return@remember graph
        val ids = LinkedHashSet<String>()
        ids.add(c.id)
        c.columns.forEach { col -> col.refTo?.let { if (graph.table(it) != null) ids.add(it) } }
        referrers[c.id]?.let { ids.addAll(it) }
        RefGraph(ids.mapNotNull { graph.table(it) })
    }

    val nodes = remember(sub, bgArgb) {
        sub.tables.map { t ->
            GraphNode(
                id = t.id,
                title = t.display,
                dot = tableColor(t.id, bgArgb),
                rows = rowsOf(t).map { c ->
                    GraphRow(
                        text = c.name,
                        // ◆ is the table's own key, → is a reference out of it. The same two marks the
                        // data-connection cards use, so one vocabulary covers both views.
                        mark = if (c.isId) "◆ " else "→ ",
                        badge = c.refTo?.let { badgeText(c) },
                        accented = c.isId,
                        badgeDot = c.refTo?.let { tableColor(it, bgArgb) },
                        key = c.name,
                    )
                },
            )
        }
    }

    val edges = remember(sub) {
        sub.tables.flatMap { src ->
            rowsOf(src).mapNotNull { c ->
                val target = c.refTo?.let { sub.table(it) } ?: return@mapNotNull null
                if (target.id == src.id) null else GraphEdge(src.id, target.id, fromRow = c.name)
            }
        }
    }

    GraphCanvas(
        nodes = nodes,
        edges = edges,
        colors = GraphColors(fgArgb = fgArgb, bgArgb = bgArgb),
        focusId = center,
        // Force, not layered: a schema neighbourhood has no direction to preserve, and organic clusters
        // read better than a rigid grid when a hub has a hundred referrers.
        layout = GraphLayout.Force,
        flow = GraphFlow.LeftToRight,
        // A neighbour re-centres the map on itself; the centre opens its sheet.
        onNodeClick = { center = it },
        onFocusClick = { onOpenTable(it) },
    ) {
        Text(center ?: "-", fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
    }
}

/** Only the columns an ER card is about: the table's key, and the columns that reference something. */
private fun rowsOf(t: RefTable): List<RefColumn> = t.columns.filter { it.isId || it.refTo != null }

/** Ref badge: target table + " str" for embedded refs + " if" for conditional (`when`) refs. */
private fun badgeText(c: RefColumn): String =
    (c.refTo ?: "") + (if (c.embedded) " str" else "") + (if (c.conditional) " if" else "")
