package com.example.codemap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text

/**
 * The call graph tab: callers above, the focus in the middle, callees below, stitched across every
 * analyzed file.
 *
 * Two honesty rules shape it. The header says out loud that this is drawn from notes over analyzed
 * files, because a graph is exactly the kind of picture people read as complete. And a node that leads
 * somewhere looks different from one that does not — a name recorded in `calls` but never analyzed is
 * drawn muted and is not clickable, rather than pretending to be a destination.
 */
private val G_NODE_W: Dp = 168.dp
private val G_NODE_H: Dp = 30.dp
private val G_ROW_GAP: Dp = 56.dp
private val G_COL_GAP: Dp = 16.dp

@Composable
fun CodemapGraphView(vm: GraphViewModel) {
    val p = rememberCodemapPalette()
    var tick by remember { mutableStateOf(0) }
    val graph = remember(tick) { vm.graph() }
    val analyzed = remember(tick) { vm.analyzedFiles() }

    Column(Modifier.fillMaxSize().padding(Space.lg)) {
        Text(
            vm.focus.substringAfterLast("::"),
            color = p.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "노트에 기록된 호출 관계 · 분석된 파일 %,d개 기준 — 전수 조사가 아닙니다".format(analyzed),
            color = p.mutedText,
            fontSize = Type.micro,
        )

        Row(
            Modifier.fillMaxWidth().padding(top = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("깊이", color = p.mutedText, fontSize = Type.micro)
            listOf(1, 2, 3).forEach { d ->
                ActionButton("$d", p, primary = vm.depth == d) { vm.setDepth(d); tick++ }
            }
            Box(Modifier.width(Space.md))
            graph.layers.getOrNull(graph.focusIndex)?.firstOrNull()?.let { self ->
                ActionButton("Rider로 정확히 찾기", p, primary = false) { vm.showUsages(self) }
                ActionButton("코드로 이동", p, primary = false) { vm.jumpTo(self) }
            }
        }

        Rule(p)

        if (graph.layers.isEmpty()) {
            EmptyState(
                "🕸",
                "그릴 호출 관계가 없습니다",
                "이 함수의 노트에 calls가 없고, 이 함수를 부른다고 기록한 노트도 없습니다.",
                p,
            )
            return@Column
        }

        Graph(graph, p, onOpen = { vm.refocus(it.name); tick++ }, onJump = vm::jumpTo)
    }
}

@Composable
private fun Graph(
    g: CallIndex.Graph,
    p: CodemapPalette,
    onOpen: (CallIndex.Node) -> Unit,
    onJump: (CallIndex.Node) -> Unit,
) {
    val widest = g.layers.maxOf { it.size }
    val totalW = (G_NODE_W + G_COL_GAP) * widest
    val totalH = G_NODE_H * g.layers.size + G_ROW_GAP * (g.layers.size - 1) + Space.xl

    fun x(i: Int, count: Int): Dp =
        (totalW - (G_NODE_W + G_COL_GAP) * count) / 2 + (G_NODE_W + G_COL_GAP) * i + G_COL_GAP / 2

    fun y(layer: Int): Dp = (G_NODE_H + G_ROW_GAP) * layer

    val pos = HashMap<String, Pair<Dp, Dp>>()
    g.layers.forEachIndexed { li, layer ->
        layer.forEachIndexed { ni, node -> pos[node.name] = x(ni, layer.size) to y(li) }
    }

    // The viewport is measured OUTSIDE the scrollers: inside them the incoming max width is unbounded,
    // so a centring offset computed there pushes the whole graph off screen.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val lead = ((maxWidth - totalW) / 2).coerceAtLeast(0.dp)
        Box(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Box(Modifier.padding(start = lead).width(totalW).height(totalH)) {
                Canvas(Modifier.width(totalW).height(totalH)) {
                    val nodeH = G_NODE_H.toPx()
                    val half = G_NODE_W.toPx() / 2
                    g.edges.forEach { (from, to) ->
                        val a = pos[from] ?: return@forEach
                        val b = pos[to] ?: return@forEach
                        val ax = a.first.toPx() + half
                        val bx = b.first.toPx() + half
                        // Leave from the lower edge of the upper box, arrive at the upper edge of the lower.
                        val downward = b.second > a.second
                        val y1 = a.second.toPx() + if (downward) nodeH else 0f
                        val y2 = b.second.toPx() + if (downward) 0f else nodeH
                        val ink = p.accent.copy(alpha = 0.45f)
                        drawLine(ink, Offset(ax, y1), Offset(bx, y2), 1.3f)
                        val dir = if (downward) 1f else -1f
                        drawLine(ink, Offset(bx, y2), Offset(bx - 4f, y2 - 6f * dir), 1.3f)
                        drawLine(ink, Offset(bx, y2), Offset(bx + 4f, y2 - 6f * dir), 1.3f)
                    }
                }

                g.layers.forEachIndexed { li, layer ->
                    layer.forEachIndexed { ni, node ->
                        GraphBox(
                            node = node,
                            focus = li == g.focusIndex,
                            palette = p,
                            modifier = Modifier.offset(x = x(ni, layer.size), y = y(li)),
                            onOpen = { onOpen(node) },
                            onJump = { onJump(node) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphBox(
    node: CallIndex.Node,
    focus: Boolean,
    palette: CodemapPalette,
    modifier: Modifier,
    onOpen: () -> Unit,
    onJump: () -> Unit,
) {
    val tint = when {
        focus -> palette.accent
        node.analyzed -> palette.text
        else -> palette.mutedText
    }
    Column(
        modifier
            .width(G_NODE_W).height(G_NODE_H)
            .background(
                if (focus) palette.accent.copy(alpha = 0.15f) else palette.subtle,
                RoundedCornerShape(Radii.sm),
            )
            .border(
                BorderStroke(1.dp, tint.copy(alpha = if (focus) 0.55f else 0.25f)),
                RoundedCornerShape(Radii.sm),
            )
            // An unanalyzed neighbour is not a destination; it says so by not being clickable.
            .let { m -> if (focus || node.analyzed) m.clickable { if (focus) onJump() else onOpen() } else m }
            .padding(horizontal = Space.sm, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            node.name.substringAfterLast("::"),
            color = tint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (focus) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
        Text(
            node.file.substringAfterLast('/').ifEmpty { "미분석" },
            color = palette.mutedText,
            fontSize = 8.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
