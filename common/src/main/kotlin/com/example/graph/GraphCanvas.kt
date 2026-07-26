package com.example.graph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------------
// The shared graph canvas.
//
// Both plugins draw a node graph over Compose Canvas — xlsx-editor's table relationships and codemap's
// call graph. They differ in what a node MEANS, not in how one is drawn, laid out, panned, zoomed or
// hovered; that machinery lives here so a graph in either plugin looks and behaves the same.
//
// What a caller supplies: nodes (a card with a title and rows), edges, and colors. What it gets back:
// measured cards that never clip their text, a layout that does not overlap, curved edges with arrow
// heads, pan/zoom/fit, drag, hover highlighting along connected edges, and a dotted infinite canvas.
// ---------------------------------------------------------------------------------------------------

/** One line inside a node card: a leading mark, the text, and an optional right-aligned badge. */
data class GraphRow(
    val text: String,
    val mark: String = "",
    val badge: String? = null,
    /** Tints the mark + text with the graph's accent; null uses the body color. */
    val accented: Boolean = false,
    /** Explicit color for the mark + text, winning over [accented] — for a caller's own semantics. */
    val tint: Color? = null,
    /** Small dot drawn left of the badge — the caller's identity color for whatever the badge names. */
    val badgeDot: Color? = null,
    /** Stable identity for hover, when two rows can share text. */
    val key: String = text,
    val mono: Boolean = false,
)

/**
 * A card in the graph.
 *
 * [rank] drives [GraphLayout.Layered]: nodes with a smaller rank are placed earlier along the flow
 * direction. Leave it null for force layout.
 */
data class GraphNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val rows: List<GraphRow> = emptyList(),
    /** Identity dot next to the title. */
    val dot: Color? = null,
    /** Drawn faded and reported as not-a-destination — for a name that is known of but not known. */
    val muted: Boolean = false,
    val rank: Int? = null,
    val titleMono: Boolean = false,
)

/** [fromRow] anchors the edge's start at that row of the source card instead of its title. */
data class GraphEdge(val from: String, val to: String, val fromRow: String? = null)

enum class GraphLayout {
    /** Fruchterman–Reingold seeded from ranks — organic clusters, no direction guaranteed. */
    Force,

    /** Strict rows/columns by [GraphNode.rank], ordered within a rank to reduce edge crossings. */
    Layered,
}

enum class GraphFlow { LeftToRight, TopToBottom }

/** IDE-LAF-derived colors. Passed as ARGB ints because that is what the LAF hands out. */
data class GraphColors(
    val fgArgb: Int,
    val bgArgb: Int,
    val accentArgb: Int = 0xFF3574F0.toInt(),
    val highlightArgb: Int = 0xFF8B5CF6.toInt(),
)

private const val PADX = 12f
private const val ROWH = 22f
private const val TITLEH = 30f
private const val SUBH = 14f

/**
 * Draw [nodes] and [edges] as an interactive graph filling the available space.
 *
 * [focusId] is the node the graph is about: it is outlined in the accent color and clicking it calls
 * [onFocusClick] instead of [onNodeClick] — the distinction every graph here needs, between "take me
 * to this" and "look at this instead".
 *
 * [toolbar] adds caller-specific controls to the built-in row (re-layout, fit, zoom).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GraphCanvas(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    colors: GraphColors,
    focusId: String? = null,
    /**
     * Nodes and edges belonging to the step being presented.
     *
     * When either is non-empty the graph enters a SPOTLIGHT: what is active keeps its colour and gains an
     * accent ring, everything else fades back. That is the whole mechanic behind stepping through a flow on
     * top of the diagram it happens on, rather than redrawing it as a second picture.
     */
    activeNodes: Set<String> = emptySet(),
    activeEdges: Set<GraphEdge> = emptySet(),
    layout: GraphLayout = GraphLayout.Force,
    flow: GraphFlow = GraphFlow.LeftToRight,
    modifier: Modifier = Modifier,
    onNodeClick: (String) -> Unit = {},
    onFocusClick: (String) -> Unit = {},
    toolbar: @Composable (RowScope.() -> Unit)? = null,
) {
    // Sized to the working set (a hub can be 100+ cards × ~3 layouts each); the default cache of 8
    // re-shaped every string every frame. Styles handed to measure() must stay CONSTANT across frames —
    // animated alpha and hover colors are applied at DRAW time via drawText(color = …).
    val measurer = rememberTextMeasurer(cacheSize = 2048)
    val fg = Color(colors.fgArgb)
    val bg = Color(colors.bgArgb)
    val accent = Color(colors.accentArgb)
    val pathHi = Color(colors.highlightArgb)
    val bgLum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val cardBg =
        if (bgLum > 0.5f) androidx.compose.ui.graphics.lerp(bg, Color.White, 0.6f)
        else androidx.compose.ui.graphics.lerp(bg, Color.White, 0.08f)

    val titleStyle = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val titleMonoStyle = titleStyle.copy(fontFamily = FontFamily.Monospace)
    val subStyle = TextStyle(color = fg.copy(alpha = 0.55f), fontSize = 10.5.sp)
    val rowStyle = TextStyle(color = fg.copy(alpha = 0.88f), fontSize = 12.sp)
    val rowMonoStyle = rowStyle.copy(fontFamily = FontFamily.Monospace)
    val rowStyleBold = rowStyle.copy(fontWeight = FontWeight.Bold)
    val rowMonoBold = rowMonoStyle.copy(fontWeight = FontWeight.Bold)
    val badgeStyle = TextStyle(color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
    fun fade(c: Color, a: Float) = c.copy(alpha = c.alpha * a)
    fun rowStyleFor(r: GraphRow, bold: Boolean) = when {
        r.mono && bold -> rowMonoBold
        r.mono -> rowMonoStyle
        bold -> rowStyleBold
        else -> rowStyle
    }

    /** Cards are measured, never truncated: a graph whose labels end in "…" answers nothing. */
    fun sizeOf(n: GraphNode): Size {
        var w = 26f + measurer.measure(n.title, if (n.titleMono) titleMonoStyle else titleStyle, softWrap = false)
            .size.width.toFloat()
        n.subtitle?.let { w = maxOf(w, 26f + measurer.measure(it, subStyle, softWrap = false).size.width.toFloat()) }
        n.rows.forEach { r ->
            var rowW = measurer.measure(r.mark + r.text, rowStyleFor(r, false), softWrap = false).size.width.toFloat()
            r.badge?.let { rowW += 22f + measurer.measure(it, badgeStyle, softWrap = false).size.width.toFloat() }
            w = maxOf(w, rowW)
        }
        val head = TITLEH + if (n.subtitle != null) SUBH else 0f
        return Size(w + PADX * 2, head + n.rows.size * ROWH + 8f)
    }

    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val sizes = remember(nodes) { nodes.associate { it.id to sizeOf(it) } }
    fun headOf(id: String) = TITLEH + if (byId[id]?.subtitle != null) SUBH else 0f

    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var didFit by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<String?>(null) }
    var lastHovered by remember { mutableStateOf<String?>(null) }
    var hoveredRow by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Layout is O(n²·iters). Computing it inside remember{} froze the EDT for seconds on a big graph, so
    // it runs off-thread and the canvas shows a placeholder until the first coordinates land.
    val positions = remember(nodes, layout, flow) { mutableStateMapOf<String, Offset>() }
    var layoutReady by remember(nodes, layout, flow) { mutableStateOf(false) }
    val prevPositions = remember { mutableStateMapOf<String, Offset>() }
    var layoutGen by remember { mutableStateOf(0) }
    val glide = remember { Animatable(1f) }
    LaunchedEffect(layoutGen) { if (layoutGen > 0) { glide.snapTo(0f); glide.animateTo(1f, tween(500)) } }

    fun drawnPos(id: String): Offset {
        val target = positions[id] ?: Offset.Zero
        return if (glide.value >= 1f) target else lerp(prevPositions[id] ?: target, target, glide.value)
    }
    fun rectOf(n: GraphNode): Rect = Rect(drawnPos(n.id), sizes[n.id] ?: Size(120f, 40f))
    fun toWorld(screen: Offset) = (screen - pan) / zoom
    fun nodeAt(screen: Offset): GraphNode? = toWorld(screen).let { w -> nodes.lastOrNull { rectOf(it).contains(w) } }

    fun fit() {
        if (canvasSize.width == 0 || nodes.isEmpty()) return
        val rs = nodes.map { rectOf(it) }
        val minX = rs.minOf { it.left }; val minY = rs.minOf { it.top }
        val maxX = rs.maxOf { it.right }; val maxY = rs.maxOf { it.bottom }
        val gw = (maxX - minX).coerceAtLeast(1f); val gh = (maxY - minY).coerceAtLeast(1f)
        val m = 56f
        zoom = minOf((canvasSize.width - m * 2) / gw, (canvasSize.height - m * 2) / gh).coerceIn(0.3f, 2f)
        pan = Offset(
            canvasSize.width / 2f - (minX + maxX) / 2f * zoom,
            canvasSize.height / 2f - (minY + maxY) / 2f * zoom,
        )
    }

    fun compute(): Map<String, Offset> = when (layout) {
        GraphLayout.Layered -> layeredLayout(nodes, edges, sizes, flow)
        GraphLayout.Force -> forceLayout(nodes, edges, sizes, flow)
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(nodes, layout) {
        appear.snapTo(0f)
        val computed = withContext(Dispatchers.Default) { compute() }
        positions.clear()
        positions.putAll(computed)
        layoutReady = true
        fit()
        appear.animateTo(1f, tween(380))
    }
    LaunchedEffect(hovered) { if (hovered != null) lastHovered = hovered }
    val focusNode = hovered ?: lastHovered
    val hoverFocus by animateFloatAsState(if (hovered != null) 1f else 0f, tween(140), label = "hover")

    val scope = rememberCoroutineScope()
    val nodeIdAt by rememberUpdatedState({ p: Offset -> nodeAt(p)?.id })
    val onHoverAt by rememberUpdatedState({ p: Offset ->
        val w = toWorld(p)
        val node = nodes.lastOrNull { rectOf(it).contains(w) }
        if (node?.id != hovered) hovered = node?.id
        var key: Pair<String, String>? = null
        if (node != null && node.rows.isNotEmpty()) {
            val rect = rectOf(node)
            val idx = ((w.y - (rect.top + headOf(node.id))) / ROWH).toInt()
            if (idx in node.rows.indices) key = node.id to node.rows[idx].key
        }
        if (key != hoveredRow) hoveredRow = key
    })
    val onTapAt by rememberUpdatedState({ p: Offset ->
        nodeAt(p)?.let { if (it.id == focusId) onFocusClick(it.id) else if (!it.muted) onNodeClick(it.id) }
    })
    // positions is recreated whenever the node set changes; route drags through the LATEST map so
    // dragging keeps working after a re-centre.
    val onDragDelta by rememberUpdatedState({ id: String?, delta: Offset ->
        if (id != null) positions[id] = (positions[id] ?: Offset.Zero) + delta / zoom else pan += delta
    })

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolbar?.invoke(this)
            Spacer(Modifier.width(14.dp))
            GraphIcon("↻", fg) {
                scope.launch { // off-EDT for the same reason as the initial layout
                    prevPositions.clear()
                    nodes.forEach { prevPositions[it.id] = drawnPos(it.id) }
                    val computed = withContext(Dispatchers.Default) { compute() }
                    positions.clear(); positions.putAll(computed)
                    layoutGen++; fit()
                }
            }
            Spacer(Modifier.width(12.dp))
            GraphIcon("⊡", fg) { fit() }
            Spacer(Modifier.width(14.dp))
            GraphIcon("－", fg) { zoom = (zoom * 0.9f).coerceIn(0.3f, 2.5f) }
            Spacer(Modifier.width(6.dp))
            Text("${(zoom * 100).toInt()}%", color = fg.copy(alpha = 0.6f), fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            GraphIcon("＋", fg) { zoom = (zoom * 1.1f).coerceIn(0.3f, 2.5f) }
        }

        Box(Modifier.weight(1f).fillMaxSize()) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { canvasSize = it; if (!didFit && it.width > 0) { fit(); didFit = true } }
                    .onPointerEvent(PointerEventType.Scroll) {
                        val dy = it.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) zoom = (zoom * if (dy < 0) 1.1f else 0.9f).coerceIn(0.3f, 2.5f)
                    }
                    .onPointerEvent(PointerEventType.Move) {
                        val pos = it.changes.firstOrNull()?.position ?: return@onPointerEvent
                        onHoverAt(pos)
                    }
                    .onPointerEvent(PointerEventType.Exit) { hovered = null; hoveredRow = null }
                    .pointerInput(Unit) {
                        var dragId: String? = null
                        detectDragGestures(
                            onDragStart = { dragId = nodeIdAt(it) },
                            onDragEnd = { dragId = null },
                            onDrag = { change, delta -> change.consume(); onDragDelta(dragId, delta) },
                        )
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { p -> onTapAt(p) }) },
            ) {
                drawRect(bg, size = size)
                val step = 26f * zoom // dotted infinite canvas
                if (step in 9f..130f) {
                    val dot = fg.copy(alpha = 0.06f)
                    var x = ((pan.x % step) + step) % step
                    while (x < size.width) {
                        var y = ((pan.y % step) + step) % step
                        while (y < size.height) { drawCircle(dot, 1.1f, Offset(x, y)); y += step }
                        x += step
                    }
                }
                if (!layoutReady) {
                    val lay = measurer.measure("레이아웃 계산 중…", badgeStyle, softWrap = false)
                    drawText(lay, topLeft = Offset((size.width - lay.size.width) / 2f, size.height / 2f))
                    return@Canvas
                }

                val a = appear.value
                val spotlight = activeNodes.isNotEmpty() || activeEdges.isNotEmpty()
                // Faded, not hidden: the rest of the diagram is the context that makes the active step
                // mean something, so it stays legible-but-quiet instead of disappearing.
                val offBeat = if (spotlight) 0.30f else 1f
                withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, pivot = Offset.Zero) }) {
                    // Which edges are lit: the row under the cursor if there is one, else every edge
                    // touching the hovered card — so hovering a node shows what flows through it.
                    val rowKey = hoveredRow
                    val liveEdges = edges.filter { e ->
                        if (rowKey != null) e.from == rowKey.first && e.fromRow == rowKey.second
                        else focusNode != null && (e.from == focusNode || e.to == focusNode)
                    }.toSet()
                    val liveRows = liveEdges.mapNotNull { e -> e.fromRow?.let { e.from to it } }.toSet()
                    val ringed = if (rowKey != null) liveEdges.map { it.to }.toSet() else emptySet()

                    edges.forEach { e ->
                        val src = byId[e.from] ?: return@forEach
                        val dst = byId[e.to] ?: return@forEach
                        if (src.id == dst.id) return@forEach
                        val sr = rectOf(src); val dr = rectOf(dst)
                        val rowIdx = e.fromRow?.let { k -> src.rows.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
                        val (start, end) = anchors(sr, dr, rowIdx, headOf(src.id), flow)
                        val on = e in liveEdges
                        val active = e in activeEdges
                        val ink = when {
                            active -> accent.copy(alpha = a)
                            on -> androidx.compose.ui.graphics.lerp(fg.copy(alpha = 0.24f * a), pathHi.copy(alpha = a), hoverFocus)
                            else -> fg.copy(alpha = 0.24f * a * offBeat)
                        }
                        val width = when {
                            active -> 2.4f
                            else -> 1.5f + (if (on) 0.9f else 0f) * hoverFocus
                        }
                        drawCurvedEdge(start, end, ink, width, flow)
                    }

                    val manyNodes = nodes.size > 40
                    nodes.forEach { n ->
                        val r = rectOf(n)
                        val isFocus = n.id == focusId
                        val active = n.id in activeNodes
                        val elevated = isFocus || active || (n.id == focusNode && hoverFocus > 0.3f)
                        val dim = if (spotlight && !active) offBeat else 1f
                        val alpha = (if (n.muted) a * 0.55f else a) * dim
                        drawShadow(r, elevated, a * dim, cheap = manyNodes && !elevated)
                        drawRoundRect(cardBg.copy(alpha = a * dim), r.topLeft, r.size, CornerRadius(12f))
                        drawRoundRect(
                            (if (isFocus || active) accent else fg.copy(alpha = 0.16f)).copy(alpha = a * dim),
                            r.topLeft, r.size, CornerRadius(12f),
                            style = Stroke(width = if (isFocus || active) 2f else 1f),
                        )

                        val head = headOf(n.id)
                        val cy0 = r.top + TITLEH / 2f
                        n.dot?.let { drawCircle(it.copy(alpha = alpha), 4.5f, Offset(r.left + 14f, cy0)) }
                        val tStyle = if (n.titleMono) titleMonoStyle else titleStyle
                        val nameLay = measurer.measure(n.title, tStyle, softWrap = false)
                        drawText(
                            nameLay,
                            color = fade(if (isFocus) accent else tStyle.color, alpha),
                            topLeft = Offset(r.left + if (n.dot != null) 26f else PADX, cy0 - nameLay.size.height / 2f),
                        )
                        n.subtitle?.let { sub ->
                            val sl = measurer.measure(sub, subStyle, softWrap = false)
                            drawText(
                                sl,
                                color = fade(subStyle.color, alpha),
                                topLeft = Offset(r.left + if (n.dot != null) 26f else PADX, r.top + TITLEH - 2f),
                            )
                        }
                        if (n.rows.isNotEmpty()) {
                            drawLine(
                                fg.copy(alpha = 0.10f * a),
                                Offset(r.left + 10f, r.top + head),
                                Offset(r.right - 10f, r.top + head),
                                1f,
                            )
                        }
                        n.rows.forEachIndexed { i, row ->
                            val y = r.top + head + i * ROWH
                            val live = (n.id to row.key) in liveRows
                            if (live) {
                                drawRoundRect(
                                    pathHi.copy(alpha = 0.12f * a * hoverFocus),
                                    Offset(r.left + 6f, y), Size(r.width - 12f, ROWH), CornerRadius(5f),
                                )
                            }
                            val base = row.tint ?: if (row.accented) accent else rowStyle.color
                            val txt = if (live) androidx.compose.ui.graphics.lerp(base, pathHi, hoverFocus) else base
                            // Weight DOES affect layout, so at most two cache entries per row style.
                            val hotBold = live && hoverFocus > 0.4f
                            val rowLay = measurer.measure(
                                row.mark + row.text,
                                rowStyleFor(row, hotBold),
                                overflow = TextOverflow.Visible,
                                softWrap = false,
                            )
                            drawText(rowLay, color = fade(txt, alpha), topLeft = Offset(r.left + PADX, y + 4f))
                            row.badge?.let { badge ->
                                val bl = measurer.measure(badge, badgeStyle, softWrap = false)
                                val bx = r.right - 12f - bl.size.width
                                val bcolor =
                                    if (live) androidx.compose.ui.graphics.lerp(badgeStyle.color, pathHi, hoverFocus)
                                    else badgeStyle.color
                                row.badgeDot?.let { d ->
                                    val dc = if (live) androidx.compose.ui.graphics.lerp(d, pathHi, hoverFocus) else d
                                    drawCircle(dc.copy(alpha = alpha), 3.5f, Offset(bx - 9f, y + ROWH / 2f))
                                }
                                drawText(bl, color = fade(bcolor, alpha), topLeft = Offset(bx, y + (ROWH - bl.size.height) / 2f))
                            }
                        }
                        if (n.id in ringed) {
                            val ir = r.inflate(3f)
                            drawRoundRect(
                                pathHi.copy(alpha = a * hoverFocus), ir.topLeft, ir.size,
                                CornerRadius(14f), style = Stroke(2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphIcon(glyph: String, fg: Color, onClick: () -> Unit) {
    Text(glyph, color = fg.copy(alpha = 0.75f), fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClick))
}

/** Where an edge leaves and lands, so a line never crosses the card it came from. */
private fun anchors(sr: Rect, dr: Rect, rowIdx: Int?, head: Float, flow: GraphFlow): Pair<Offset, Offset> =
    if (flow == GraphFlow.TopToBottom) {
        val down = dr.center.y >= sr.center.y
        Offset(sr.center.x, if (down) sr.bottom else sr.top) to Offset(dr.center.x, if (down) dr.top else dr.bottom)
    } else {
        val right = dr.center.x >= sr.center.x
        val sy = if (rowIdx != null) sr.top + head + rowIdx * ROWH + ROWH / 2 else sr.center.y
        Offset(if (right) sr.right else sr.left, sy) to Offset(if (right) dr.left else dr.right, dr.top + TITLEH / 2)
    }

private fun DrawScope.drawShadow(rect: Rect, elevated: Boolean, a: Float, cheap: Boolean) {
    if (cheap) { // one soft layer — the full stack is hundreds of rects per frame on a big graph
        val o = rect.inflate(1.5f).translate(0f, 5f)
        drawRoundRect(Color.Black.copy(alpha = 0.05f * a), o.topLeft, o.size, CornerRadius(14f))
        return
    }
    val layers = if (elevated) 6 else 4
    val maxOff = if (elevated) 13f else 7f
    for (i in 1..layers) {
        val t = i / layers.toFloat()
        val o = rect.inflate(t * (if (elevated) 3f else 1.8f)).translate(0f, maxOff * t)
        drawRoundRect(
            Color.Black.copy(alpha = (if (elevated) 0.055f else 0.04f) * (1f - t) * a),
            o.topLeft, o.size, CornerRadius(14f),
        )
    }
}

fun DrawScope.drawArrowHead(tip: Offset, from: Offset, color: Color) {
    val dir = tip - from
    val len = dir.getDistance().coerceAtLeast(0.001f)
    val u = dir / len
    val perp = Offset(-u.y, u.x)
    val base = tip - u * 10f
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + perp.x * 5f, base.y + perp.y * 5f)
        lineTo(base.x - perp.x * 5f, base.y - perp.y * 5f)
        close()
    }
    drawPath(path, color)
}

fun DrawScope.drawCurvedEdge(
    start: Offset,
    end: Offset,
    color: Color,
    width: Float,
    flow: GraphFlow = GraphFlow.LeftToRight,
) {
    val path: Path
    val c2: Offset
    if (flow == GraphFlow.TopToBottom) {
        val handle = (abs(end.y - start.y) * 0.5f).coerceIn(30f, 120f)
        val dir = if (end.y >= start.y) 1f else -1f
        val c1 = Offset(start.x, start.y + dir * handle)
        c2 = Offset(end.x, end.y - dir * handle)
        path = Path().apply { moveTo(start.x, start.y); cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y) }
    } else {
        val handle = (abs(end.x - start.x) * 0.5f).coerceIn(40f, 170f)
        val dir = if (end.x >= start.x) 1f else -1f
        val c1 = Offset(start.x + dir * handle, start.y)
        c2 = Offset(end.x - dir * handle, end.y)
        path = Path().apply { moveTo(start.x, start.y); cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y) }
    }
    drawPath(path, color, style = Stroke(width = width))
    drawArrowHead(end, c2, color)
}

// ---- layout ------------------------------------------------------------------------------------

/**
 * Ranks become rows (or columns), ordered within a rank by the average position of what they connect to.
 *
 * That ordering is the whole point: laying a rank out in note order puts connected cards at opposite ends
 * and every edge crosses the picture. Two barycentre sweeps get most of the way to a readable graph for a
 * fraction of what a proper crossing-minimisation costs.
 */
internal fun layeredLayout(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    sizes: Map<String, Size>,
    flow: GraphFlow,
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()
    fun sz(id: String) = sizes[id] ?: Size(140f, 40f)

    val order: Map<Int, MutableList<GraphNode>> =
        nodes.groupBy { it.rank ?: 0 }.toSortedMap().mapValues { (_, ns) -> ns.toMutableList() }

    val neighbours = HashMap<String, MutableList<String>>()
    edges.forEach { e ->
        neighbours.getOrPut(e.from) { mutableListOf() }.add(e.to)
        neighbours.getOrPut(e.to) { mutableListOf() }.add(e.from)
    }
    val index = HashMap<String, Int>()
    order.values.forEach { list -> list.forEachIndexed { i, n -> index[n.id] = i } }

    repeat(2) {
        order.values.forEach { list ->
            val scored = list.map { n ->
                val ns = neighbours[n.id].orEmpty().mapNotNull { index[it] }
                n to if (ns.isEmpty()) (index[n.id] ?: 0).toFloat() else ns.average().toFloat()
            }.sortedBy { it.second }.map { it.first }
            list.clear(); list.addAll(scored)
            scored.forEachIndexed { i, n -> index[n.id] = i }
        }
    }

    val gap = 30f
    val rankGap = if (flow == GraphFlow.TopToBottom) 74f else 120f
    val result = HashMap<String, Offset>()
    // Each rank is centred on the widest one, so the flow reads down (or across) a middle line.
    val extents = order.mapValues { (_, list) ->
        if (flow == GraphFlow.TopToBottom) list.sumOf { sz(it.id).width.toDouble() }.toFloat() + gap * (list.size - 1)
        else list.sumOf { sz(it.id).height.toDouble() }.toFloat() + gap * (list.size - 1)
    }
    val widest = extents.values.maxOrNull() ?: 0f
    var along = 40f
    order.forEach { (r, list) ->
        val thick = list.maxOf { if (flow == GraphFlow.TopToBottom) sz(it.id).height else sz(it.id).width }
        var across = 40f + (widest - (extents[r] ?: 0f)) / 2f
        list.forEach { n ->
            val s = sz(n.id)
            result[n.id] =
                if (flow == GraphFlow.TopToBottom) Offset(across, along).also { across += s.width + gap }
                else Offset(along, across).also { across += s.height + gap }
        }
        along += thick + rankGap
    }
    return result
}

/**
 * Fruchterman–Reingold seeded from [layeredLayout], then a rectangle-overlap relaxation pass — organic
 * clusters that aren't a rigid grid and don't overlap. Deterministic (the seed is structural, no
 * randomness), so re-running the layout is stable.
 */
internal fun forceLayout(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    sizes: Map<String, Size>,
    flow: GraphFlow,
): Map<String, Offset> {
    val ids = nodes.map { it.id }
    if (ids.isEmpty()) return emptyMap()
    fun sz(id: String) = sizes[id] ?: Size(120f, 60f)
    val center = HashMap<String, Offset>()
    val seed = layeredLayout(nodes, edges, sizes, flow)
    ids.forEach { id -> center[id] = (seed[id] ?: Offset.Zero) + Offset(sz(id).width / 2f, sz(id).height / 2f) }
    val pairs = edges.map { it.from to it.to }.filter { it.first != it.second }.distinct()
    val k = 280f
    var temp = 320f
    // O(n²) per iteration — tiered down for big graphs (runs off-EDT, but 500²×520 still hurts).
    val iters = when {
        ids.size > 300 -> 120
        ids.size > 120 -> 250
        else -> 520
    }
    repeat(iters) {
        val disp = HashMap<String, Offset>()
        ids.forEach { disp[it] = Offset.Zero }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]; val b = ids[j]
            var d = center[a]!! - center[b]!!
            var dist = d.getDistance()
            if (dist < 0.01f) { d = Offset(1f, 0.7f); dist = d.getDistance() }
            val u = d / dist
            disp[a] = disp[a]!! + u * (k * k / dist)
            disp[b] = disp[b]!! - u * (k * k / dist)
        }
        pairs.forEach { (a, b) ->
            if (center[a] == null || center[b] == null) return@forEach
            val d = center[a]!! - center[b]!!
            val dist = d.getDistance().coerceAtLeast(0.01f)
            val u = d / dist
            disp[a] = disp[a]!! - u * (dist * dist / k)
            disp[b] = disp[b]!! + u * (dist * dist / k)
        }
        ids.forEach { id ->
            val dsp = disp[id]!!
            val len = dsp.getDistance().coerceAtLeast(0.01f)
            center[id] = center[id]!! + dsp / len * minOf(len, temp)
        }
        temp = (temp * 0.965f).coerceAtLeast(2f)
    }
    repeat(80) {
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]; val b = ids[j]
            val push = overlapPush(rectFromCenter(center[a]!!, sz(a)), rectFromCenter(center[b]!!, sz(b)))
            if (push != Offset.Zero) {
                center[a] = center[a]!! + push / 2f
                center[b] = center[b]!! - push / 2f
            }
        }
    }
    val tl = ids.associateWith { center[it]!! - Offset(sz(it).width / 2f, sz(it).height / 2f) }
    val minX = tl.values.minOf { it.x }; val minY = tl.values.minOf { it.y }
    return tl.mapValues { it.value - Offset(minX - 40f, minY - 40f) }
}

private fun rectFromCenter(c: Offset, s: Size) = Rect(c - Offset(s.width / 2f, s.height / 2f), s)

/** Separation vector if [a] (inflated by [margin]) overlaps [b], pushing along the shallower axis. */
internal fun overlapPush(a: Rect, b: Rect, margin: Float = 26f): Offset {
    val ox = minOf(a.right + margin, b.right) - maxOf(a.left - margin, b.left)
    val oy = minOf(a.bottom + margin, b.bottom) - maxOf(a.top - margin, b.top)
    if (ox <= 0f || oy <= 0f) return Offset.Zero
    return if (ox < oy) Offset(if (a.center.x <= b.center.x) -ox else ox, 0f)
    else Offset(0f, if (a.center.y <= b.center.y) -oy else oy)
}
