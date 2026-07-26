package com.example.codemap

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.graph.GraphCanvas
import com.example.graph.GraphColors
import com.example.graph.GraphEdge
import com.example.graph.GraphFlow
import com.example.graph.GraphLayout
import com.example.graph.GraphNode
import com.example.graph.GraphRow
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.jetbrains.jewel.ui.component.Text

/**
 * The call graph tab: callers above, the focus in the middle, callees below, stitched across every
 * analyzed file.
 *
 * The drawing is [GraphCanvas] from `:common` — the same canvas xlsx-editor's relationship map uses, so a
 * graph looks and behaves the same in both plugins. What belongs here is only the translation from notes
 * to cards, and the two honesty rules the picture needs. The header says out loud that this is drawn from
 * notes over analyzed files, because a graph is exactly the kind of picture people read as complete. And a
 * node that leads somewhere looks different from one that does not: a name recorded in `calls` but never
 * analyzed is drawn faded and is not clickable, rather than pretending to be a destination.
 *
 * A layered layout, not the force layout xlsx uses: here the direction carries meaning (above calls
 * below), and an organic cluster would throw that away.
 */
@Composable
fun CodemapGraphView(vm: GraphViewModel) {
    val p = rememberCodemapPalette()
    var usages by remember { mutableStateOf<UsageFinder.Result?>(null) }
    var searching by remember { mutableStateOf(false) }
    // Keyed on the view model's own state, so re-centring from anywhere (a click here, or another
    // 호출 그래프 press in the tool window) rebuilds the graph without a manual refresh counter.
    val graph = remember(vm.focus, vm.depth) { vm.graph() }
    val analyzed = remember(vm.focus, vm.depth) { vm.analyzedFiles() }
    val details = remember(vm.focus, vm.depth) { vm.functionDetails() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, top = Space.md)) {
            Text(vm.focus, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "노트에 기록된 호출 관계 · 분석된 파일 %,d개 기준 — 전수 조사가 아닙니다".format(analyzed),
                color = p.mutedText,
                fontSize = Type.micro,
            )
        }

        if (graph.layers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Space.lg)) {
                EmptyState(
                    "🕸",
                    "그릴 호출 관계가 없습니다",
                    "이 함수의 노트에 calls가 없고, 이 함수를 부른다고 기록한 노트도 없습니다.",
                    p,
                )
            }
            return@Column
        }

        val self = graph.layers.getOrNull(graph.focusIndex)?.firstOrNull()
        val nodes = remember(graph, details) { cards(graph, details, p) }
        val edges = remember(graph) {
            val known = graph.layers.flatten().map { it.name }.toSet()
            graph.edges.filter { it.first in known && it.second in known }
                .map { (from, to) -> GraphEdge(from, to, fromRow = to) }
        }

        GraphCanvas(
            nodes = nodes,
            edges = edges,
            colors = GraphColors(fgArgb = p.text.toArgb(), bgArgb = p.surface.toArgb(), accentArgb = p.accent.toArgb()),
            focusId = self?.name,
            layout = GraphLayout.Layered,
            flow = GraphFlow.TopToBottom,
            modifier = Modifier.fillMaxSize(),
            // One click looks from that node; two clicks leave for its code. Two verbs, two gestures.
            onNodeClick = { name -> vm.refocus(name); usages = null },
            onFocusClick = { name -> graph.layers.flatten().firstOrNull { it.name == name }?.let(vm::jumpTo) },
            onNodeDoubleClick = { name ->
                graph.layers.flatten().firstOrNull { it.name == name }?.let(vm::jumpTo)
            },
        ) {
            // Segments, not a dropdown: three short options where seeing the range is itself the
            // information, and the dropdown's extra click would buy nothing.
            Text("깊이", color = p.mutedText, fontSize = Type.micro)
            Segments(listOf("1", "2", "3"), vm.depth - 1, p) { i -> vm.changeDepth(i + 1) }
            Box(Modifier.width(Space.md))
            self?.let { node ->
                HintedButton("사용처 찾기", "이 함수가 실제로 호출되는 곳 — 라이더가 찾고 아래에 보여줍니다", p) {
                    searching = true
                    vm.findUsages(node) { r -> searching = false; usages = r }
                }
                HintedButton("코드로 이동", "이 함수의 정의로 점프", p) { vm.jumpTo(node) }
            }
            if (searching) Text("찾는 중…", color = p.mutedText, fontSize = Type.micro)
        }

        usages?.let { result ->
            Usages(result, p, retry = self?.let { node -> { searching = true; usages = null
                vm.findUsages(node) { r -> searching = false; usages = r } } }) { hit -> vm.display(hit) }
        }
    }
}

/**
 * What Rider found, under the graph rather than in Rider's own window.
 *
 * This sits below the canvas because it answers a different question than the picture above it: the graph
 * is what the notes recorded, this is what the backend knows. Keeping both on one screen is the only
 * reason the search is here at all — Alt+F7 is otherwise the better tool.
 */
@Composable
private fun Usages(
    result: UsageFinder.Result,
    p: CodemapPalette,
    retry: (() -> Unit)?,
    display: (String) -> String,
) {
    Rule(p)
    Column(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, bottom = Space.sm)) {
        when (result) {
            is UsageFinder.Result.Failed -> Row(
                Modifier.padding(top = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(result.reason, color = p.warn, fontSize = Type.label)
                // The usual reason is that indexing had not finished, which makes retrying the fix.
                retry?.let { ActionButton("다시 시도", p, primary = false, onClick = it) }
            }

            is UsageFinder.Result.Found -> {
                Row(
                    Modifier.fillMaxWidth().padding(top = Space.xs),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("사용처", color = p.text, fontSize = Type.label, fontWeight = FontWeight.SemiBold)
                    Text("%,d곳".format(result.hits.size), color = p.mutedText, fontSize = Type.micro)
                    Text("Rider 검색", color = p.mutedText, fontSize = Type.micro)
                }
                if (result.hits.isEmpty()) {
                    Text("없음", color = p.mutedText, fontSize = Type.label)
                    return@Column
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    result.hits.groupBy { it.filePath }.forEach { (path, hits) ->
                        Text(
                            display(path),
                            color = p.mutedText,
                            fontSize = Type.micro,
                            modifier = Modifier.padding(top = Space.xs),
                        )
                        hits.forEach { UsageRow(it, p) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(hit: UsageFinder.Hit, p: CodemapPalette) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered && hit.canNavigate) p.surfaceHover else Color.Transparent)
    Row(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(Radii.sm))
            .hoverable(interaction)
            .let { m -> if (hit.canNavigate) m.clickable { hit.navigate() } else m }
            .padding(horizontal = Space.xs, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Mono("%4d".format(hit.line), p.mutedText, size = Type.micro)
        Mono(hit.text, if (hit.canNavigate) p.text else p.mutedText, size = Type.micro, modifier = Modifier.weight(1f))
    }
}

/**
 * Notes → cards.
 *
 * A card carries what the note already recorded about the function — one line of purpose, its thread, the
 * locks it takes, and a row per call that lands inside the visible graph. Those call rows are what make
 * hovering work: each one is the start of an edge, so pointing at a row lights the line and rings the card
 * it leads to.
 */
private fun cards(
    graph: CallIndex.Graph,
    details: Map<String, JsonObject>,
    p: CodemapPalette,
): List<GraphNode> {
    val visible = graph.layers.flatten().map { it.name }.toSet()
    val fileOf = graph.layers.flatten().associate { it.name to it.file }
    return graph.layers.flatMapIndexed { rank, layer ->
        layer.map { node ->
            val f = details[node.name]
            val rows = buildList {
                f?.str("purpose")?.let { add(GraphRow(text = it.short(), mark = "· ")) }
                f?.str("thread")?.let { add(GraphRow(text = it, mark = "스레드 ", tint = p.accent)) }
                f?.strs("locks")?.filter { it.isNotBlank() && it != "없음" }?.forEach {
                    add(GraphRow(text = it.short(), mark = "락 ", tint = p.warn))
                }
                graph.edges.filter { it.first == node.name && it.second in visible }.forEach { (_, to) ->
                    add(
                        GraphRow(
                            text = to.substringAfterLast("::"),
                            mark = "→ ",
                            badge = fileOf[to]?.substringAfterLast('/')?.ifEmpty { "미분석" },
                            key = to,
                            mono = true,
                        ),
                    )
                }
            }
            GraphNode(
                id = node.name,
                title = node.name,
                subtitle = node.file.substringAfterLast('/').ifEmpty { "미분석" },
                rows = rows,
                muted = !node.analyzed,
                rank = rank,
                titleMono = true,
            )
        }
    }
}

/** Prose on a card is a label, not a paragraph — long text would stretch the layout to uselessness. */
private fun String.short(limit: Int = 42): String =
    if (length <= limit) this else take(limit - 1).trimEnd() + "…"

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.strs(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
