package com.example.xlsx

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text

private const val PADX = 12f
private const val ROWH = 22f
private const val TITLEH = 30f

/**
 * Compose-Canvas relationship map (ER style: a table's key + reference columns), shown as a LOCAL
 * neighbourhood — the centre table plus the tables it references and the tables that reference it.
 * Click a neighbour to re-centre, click the centre to open its sheet. Keeping the node count to a
 * handful means the force layout stays cheap even with thousands of tables in the schema. [fgArgb]/
 * [bgArgb] are the IDE LAF colors so the canvas is theme-aware.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RefGraphView(graph: RefGraph, fgArgb: Int, bgArgb: Int, onOpenTable: (String) -> Unit = {}) {
    val measurer = rememberTextMeasurer()
    val fg = Color(fgArgb)
    val bg = Color(bgArgb)
    val accent = Color(0xFF3574F0)
    val pathHi = Color(0xFF8B5CF6) // violet focus path (matches the data view)
    val bgLum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val cardBg = if (bgLum > 0.5f) androidx.compose.ui.graphics.lerp(bg, Color.White, 0.6f) else androidx.compose.ui.graphics.lerp(bg, Color.White, 0.08f)
    val titleStyle = TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val rowStyle = TextStyle(color = fg.copy(alpha = 0.88f), fontSize = 12.sp)
    val badgeStyle = TextStyle(color = fg.copy(alpha = 0.7f), fontSize = 11.sp)

    fun rowsOf(t: RefTable): List<RefColumn> = t.columns.filter { it.isId || it.refTo != null }
    fun sizeOf(t: RefTable): Size {
        var w = 26f + measurer.measure(t.display, titleStyle, softWrap = false).size.width.toFloat()
        rowsOf(t).forEach { c ->
            var rowW = measurer.measure((if (c.isId) "◆ " else "→ ") + c.name, rowStyle, softWrap = false).size.width.toFloat()
            if (c.refTo != null) rowW += 22f + measurer.measure(badgeText(c), badgeStyle, softWrap = false).size.width.toFloat()
            w = maxOf(w, rowW)
        }
        return Size(w + PADX * 2, TITLEH + rowsOf(t).size * ROWH + 8f)
    }

    // target id -> tables referencing it (built once); used to find a centre's incoming neighbours.
    val referrers = remember(graph) {
        val m = HashMap<String, MutableSet<String>>()
        graph.tables.forEach { s -> s.columns.forEach { c -> c.refTo?.let { m.getOrPut(it) { HashSet() }.add(s.id) } } }
        m
    }
    // Default centre = the most-connected table (out refs + referrers).
    var center by remember(graph) {
        mutableStateOf(graph.tables.maxByOrNull { t -> t.columns.count { it.refTo != null } + (referrers[t.id]?.size ?: 0) }?.id)
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

    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var didFit by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<String?>(null) }
    var lastHovered by remember { mutableStateOf<String?>(null) }
    var hoveredKey by remember { mutableStateOf<Pair<String, String>?>(null) } // (tableId, ref column) under the cursor

    val sizes = remember(sub) { sub.tables.associate { it.id to sizeOf(it) } }
    val positions = remember(sub) { mutableStateMapOf<String, Offset>().apply { putAll(forceLayout(sub, sizes)) } }
    val prevPositions = remember { mutableStateMapOf<String, Offset>() }
    var layoutGen by remember { mutableStateOf(0) }
    val glide = remember { Animatable(1f) }
    LaunchedEffect(layoutGen) { if (layoutGen > 0) { glide.snapTo(0f); glide.animateTo(1f, tween(500)) } }

    fun drawnPos(id: String): Offset {
        val target = positions[id] ?: Offset.Zero
        return if (glide.value >= 1f) target else lerp(prevPositions[id] ?: target, target, glide.value)
    }
    fun rectOf(t: RefTable): Rect = Rect(drawnPos(t.id), sizes[t.id] ?: Size(120f, 40f))
    fun toWorld(screen: Offset) = (screen - pan) / zoom
    fun nodeAt(screen: Offset): RefTable? = toWorld(screen).let { w -> sub.tables.lastOrNull { rectOf(it).contains(w) } }

    fun fit() {
        if (canvasSize.width == 0 || sub.tables.isEmpty()) return
        val rs = sub.tables.map { rectOf(it) }
        val minX = rs.minOf { it.left }; val minY = rs.minOf { it.top }
        val maxX = rs.maxOf { it.right }; val maxY = rs.maxOf { it.bottom }
        val gw = (maxX - minX).coerceAtLeast(1f); val gh = (maxY - minY).coerceAtLeast(1f)
        val m = 56f
        zoom = minOf((canvasSize.width - m * 2) / gw, (canvasSize.height - m * 2) / gh).coerceIn(0.3f, 2f)
        pan = Offset(canvasSize.width / 2f - (minX + maxX) / 2f * zoom, canvasSize.height / 2f - (minY + maxY) / 2f * zoom)
    }

    // Re-frame + fade when the centre changes (new subgraph).
    val appear = remember { Animatable(0f) }
    LaunchedEffect(center) { appear.snapTo(0f); fit(); appear.animateTo(1f, tween(380)) }
    LaunchedEffect(hovered) { if (hovered != null) lastHovered = hovered }
    val focusNode = hovered ?: lastHovered
    val hoverFocus by animateFloatAsState(if (hovered != null) 1f else 0f, tween(140), label = "hover")

    fun drawShadow(scope: DrawScope, rect: Rect, elevated: Boolean, a: Float) {
        with(scope) {
            val layers = if (elevated) 6 else 4
            val maxOff = if (elevated) 13f else 7f
            for (i in 1..layers) {
                val t = i / layers.toFloat()
                val o = rect.inflate(t * (if (elevated) 3f else 1.8f)).translate(0f, maxOff * t)
                drawRoundRect(Color.Black.copy(alpha = (if (elevated) 0.055f else 0.04f) * (1f - t) * a), o.topLeft, o.size, CornerRadius(14f))
            }
        }
    }

    val nodeIdAt by rememberUpdatedState({ p: Offset -> nodeAt(p)?.id })
    val onHoverAt by rememberUpdatedState({ p: Offset ->
        val w = toWorld(p)
        val node = sub.tables.lastOrNull { rectOf(it).contains(w) }
        if (node?.id != hovered) hovered = node?.id
        var key: Pair<String, String>? = null
        if (node != null) { // which ref ROW is the cursor over?
            val rect = rectOf(node)
            val idx = ((w.y - (rect.top + TITLEH)) / ROWH).toInt()
            val rows = rowsOf(node)
            if (idx in rows.indices && rows[idx].refTo != null) key = node.id to rows[idx].name
        }
        if (key != hoveredKey) hoveredKey = key
    })
    val onTapAt by rememberUpdatedState({ p: Offset -> nodeAt(p)?.let { if (it.id == center) onOpenTable(it.id) else center = it.id } })
    // positions is recreated on every re-center (remember(sub)); route drags through the LATEST map so node
    // dragging keeps working after a re-center (rememberUpdatedState re-captures positions each composition).
    val onDragDelta by rememberUpdatedState({ id: String?, delta: Offset ->
        if (id != null) positions[id] = (positions[id] ?: Offset.Zero) + delta / zoom else pan += delta
    })

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(center ?: "-", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Text("↻", Modifier.clickable {
                prevPositions.clear(); sub.tables.forEach { prevPositions[it.id] = drawnPos(it.id) }
                positions.clear(); positions.putAll(forceLayout(sub, sizes))
                layoutGen++; fit()
            })
            Spacer(Modifier.width(14.dp))
            Text("⊡", Modifier.clickable { fit() })
            Spacer(Modifier.width(16.dp))
            Text("－", Modifier.clickable { zoom = (zoom * 0.9f).coerceIn(0.3f, 2.5f) })
            Spacer(Modifier.width(8.dp))
            Text("${(zoom * 100).toInt()}%", color = fg.copy(alpha = 0.6f))
            Spacer(Modifier.width(8.dp))
            Text("＋", Modifier.clickable { zoom = (zoom * 1.1f).coerceIn(0.3f, 2.5f) })
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { canvasSize = it; if (!didFit && it.width > 0) { fit(); didFit = true } }
                    .onPointerEvent(PointerEventType.Scroll) {
                        val dy = it.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) zoom = (zoom * if (dy < 0) 1.1f else 0.9f).coerceIn(0.3f, 2.5f)
                    }
                    .onPointerEvent(PointerEventType.Move) { val p = it.changes.firstOrNull()?.position ?: return@onPointerEvent; onHoverAt(p) }
                    .onPointerEvent(PointerEventType.Exit) { hovered = null; hoveredKey = null }
                    .pointerInput(Unit) {
                        var dragId: String? = null
                        detectDragGestures(
                            onDragStart = { dragId = nodeIdAt(it) },
                            onDragEnd = { dragId = null },
                            onDrag = { change, delta -> change.consume(); onDragDelta(dragId, delta) },
                        )
                    }
                    .pointerInput(Unit) {
                        // Neighbour click = re-centre; clicking the centre opens its sheet.
                        detectTapGestures(onTap = { p -> onTapAt(p) })
                    },
            ) {
                drawRect(bg, size = size)
                val step = 26f * zoom // dotted infinite-canvas background (matches the data view)
                if (step in 9f..130f) {
                    val dot = fg.copy(alpha = 0.06f)
                    var x = ((pan.x % step) + step) % step
                    while (x < size.width) {
                        var y = ((pan.y % step) + step) % step
                        while (y < size.height) { drawCircle(dot, 1.1f, Offset(x, y)); y += step }
                        x += step
                    }
                }
                val a = appear.value
                withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, pivot = Offset.Zero) }) {
                    val keyActive = hoveredKey
                    // active (table, refColumn) keys: a specific hovered key row, else EVERY key on an edge
                    // touching the hovered NODE — so hovering a table lights up all keys that reference it too.
                    val activeKeys = HashSet<Pair<String, String>>()
                    sub.tables.forEach { src ->
                        rowsOf(src).forEach { c ->
                            val tgtId = c.refTo ?: return@forEach
                            val active = if (keyActive != null) src.id == keyActive.first && c.name == keyActive.second
                                else focusNode != null && (src.id == focusNode || tgtId == focusNode)
                            if (active) activeKeys.add(src.id to c.name)
                        }
                    }
                    val keyTargetId = keyActive?.let { (tid, col) -> sub.table(tid)?.columns?.firstOrNull { it.name == col }?.refTo }
                    // edges (behind nodes), one per FK column within the visible subgraph
                    sub.tables.forEach { src ->
                        rowsOf(src).forEachIndexed { i, c ->
                            val tgt = c.refTo?.let { sub.table(it) } ?: return@forEachIndexed
                            if (tgt.id == src.id) return@forEachIndexed
                            val sr = rectOf(src); val dr = rectOf(tgt)
                            val onRight = dr.center.x >= sr.center.x
                            val start = Offset(if (onRight) sr.right else sr.left, sr.top + TITLEH + i * ROWH + ROWH / 2)
                            val end = Offset(if (onRight) dr.left else dr.right, dr.top + TITLEH / 2)
                            val onPath = (src.id to c.name) in activeKeys
                            val edgeCol = if (onPath) androidx.compose.ui.graphics.lerp(fg.copy(alpha = 0.24f * a), pathHi.copy(alpha = a), hoverFocus) else fg.copy(alpha = 0.24f * a)
                            drawCurvedEdge(start, end, edgeCol, 1.5f + (if (onPath) 0.9f else 0f) * hoverFocus)
                        }
                    }
                    // nodes — same chrome as the data-connection cards (soft card + table-colour dot + hue badges)
                    sub.tables.forEach { t ->
                        val r = rectOf(t)
                        val isCenter = t.id == center
                        val hue = tableColor(t.id, bgArgb)
                        drawShadow(this, r, isCenter || (t.id == focusNode && hoverFocus > 0.3f), a)
                        drawRoundRect(cardBg.copy(alpha = a), r.topLeft, r.size, CornerRadius(12f))
                        drawRoundRect((if (isCenter) accent else fg.copy(alpha = 0.16f)).copy(alpha = a), r.topLeft, r.size, CornerRadius(12f), style = Stroke(width = if (isCenter) 2f else 1f))
                        val cy0 = r.top + TITLEH / 2f
                        drawCircle(hue.copy(alpha = a), 4.5f, Offset(r.left + 14f, cy0)) // table identity dot
                        val nameLay = measurer.measure(t.display, titleStyle.dim(a), softWrap = false)
                        drawText(nameLay, topLeft = Offset(r.left + 26f, cy0 - nameLay.size.height / 2f))
                        drawLine(fg.copy(alpha = 0.10f * a), Offset(r.left + 10f, r.top + TITLEH), Offset(r.right - 10f, r.top + TITLEH), 1f)
                        rowsOf(t).forEachIndexed { i, c ->
                            val y = r.top + TITLEH + i * ROWH
                            val rowActive = (t.id to c.name) in activeKeys
                            if (rowActive) drawRoundRect(pathHi.copy(alpha = 0.12f * a * hoverFocus), Offset(r.left + 6f, y), Size(r.width - 12f, ROWH), CornerRadius(5f))
                            val mark = if (c.isId) "◆ " else "→ "
                            val base = if (c.isId) accent else fg.copy(alpha = 0.88f)
                            val txt = if (rowActive) androidx.compose.ui.graphics.lerp(base, pathHi, hoverFocus) else base
                            drawText(measurer, mark + c.name, topLeft = Offset(r.left + PADX, y + 4f), style = TextStyle(color = txt, fontSize = 12.sp, fontWeight = if (rowActive && hoverFocus > 0.4f) FontWeight.Bold else FontWeight.Normal).dim(a), softWrap = false, overflow = TextOverflow.Visible)
                            if (c.refTo != null) {
                                val th = tableColor(c.refTo, bgArgb)
                                val badge = badgeText(c)
                                val bcolor = if (rowActive) androidx.compose.ui.graphics.lerp(badgeStyle.color, pathHi, hoverFocus) else badgeStyle.color
                                val bl = measurer.measure(badge, badgeStyle.copy(color = bcolor).dim(a), softWrap = false)
                                val bx = r.right - 12f - bl.size.width
                                drawCircle((if (rowActive) androidx.compose.ui.graphics.lerp(th, pathHi, hoverFocus) else th).copy(alpha = a), 3.5f, Offset(bx - 9f, y + ROWH / 2f))
                                drawText(bl, topLeft = Offset(bx, y + (ROWH - bl.size.height) / 2f))
                            }
                        }
                        if (t.id == keyTargetId) { // hovering a key row → ring the connected table
                            val ir = r.inflate(3f)
                            drawRoundRect(pathHi.copy(alpha = a * hoverFocus), ir.topLeft, ir.size, CornerRadius(14f), style = Stroke(2f))
                        }
                    }
                }
            }
        }
    }
}

/** Ref badge: target table + " str" for embedded refs + " if" for conditional (`when`) refs. */
private fun badgeText(c: RefColumn): String =
    (c.refTo ?: "") + (if (c.embedded) " str" else "") + (if (c.conditional) " if" else "")

private fun TextStyle.dim(a: Float): TextStyle = if (a >= 1f) this else copy(color = color.copy(alpha = color.alpha * a))

/** Layered left→right layout: pure referrers on the left, leaf tables (only referenced) on the right. */
private fun layeredLayout(graph: RefGraph, sizes: Map<String, Size>): Map<String, Offset> {
    val memo = HashMap<String, Int>()
    fun depth(id: String, stack: Set<String>): Int {
        memo[id]?.let { return it }
        if (id in stack) return 0
        val outs = graph.table(id)?.columns?.mapNotNull { it.refTo }?.distinct()?.filter { graph.table(it) != null }.orEmpty()
        val d = if (outs.isEmpty()) 0 else 1 + outs.maxOf { depth(it, stack + id) }
        memo[id] = d
        return d
    }
    val depths = graph.tables.associate { it.id to depth(it.id, emptySet()) }
    val maxD = depths.values.maxOrNull() ?: 0
    val colGap = 340f; val rowGap = 34f; val startX = 40f; val startY = 40f
    val byCol = graph.tables.groupBy { maxD - (depths[it.id] ?: 0) }.toSortedMap()
    val result = HashMap<String, Offset>()
    byCol.forEach { (col, tables) ->
        var y = startY
        tables.forEach { t ->
            result[t.id] = Offset(startX + col * colGap, y)
            y += (sizes[t.id]?.height ?: 60f) + rowGap
        }
    }
    return result
}

/**
 * Force-directed (Fruchterman–Reingold) layout seeded from [layeredLayout], then a rectangle-overlap
 * relaxation pass — organic clusters that aren't a rigid grid and don't overlap. Deterministic (the
 * seed is structural, no randomness), so re-running "자동정렬" is stable.
 */
private fun forceLayout(graph: RefGraph, sizes: Map<String, Size>): Map<String, Offset> {
    val ids = graph.tables.map { it.id }
    if (ids.isEmpty()) return emptyMap()
    fun sz(id: String) = sizes[id] ?: Size(120f, 60f)
    val center = HashMap<String, Offset>()
    val seed = layeredLayout(graph, sizes)
    ids.forEach { id -> center[id] = (seed[id] ?: Offset.Zero) + Offset(sz(id).width / 2f, sz(id).height / 2f) }
    val edges = graph.tables.flatMap { s ->
        s.columns.mapNotNull { it.refTo }.filter { it != s.id && graph.table(it) != null }.map { s.id to it }
    }.distinct()
    val k = 280f
    var temp = 320f
    val iters = if (ids.size > 120) 250 else 520
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
        edges.forEach { (a, b) ->
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
private fun overlapPush(a: Rect, b: Rect, margin: Float = 26f): Offset {
    val ox = minOf(a.right + margin, b.right) - maxOf(a.left - margin, b.left)
    val oy = minOf(a.bottom + margin, b.bottom) - maxOf(a.top - margin, b.top)
    if (ox <= 0f || oy <= 0f) return Offset.Zero
    return if (ox < oy) Offset(if (a.center.x <= b.center.x) -ox else ox, 0f)
    else Offset(0f, if (a.center.y <= b.center.y) -oy else oy)
}

internal fun DrawScope.drawArrowHead(tip: Offset, from: Offset, color: Color) {
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

internal fun DrawScope.drawCurvedEdge(start: Offset, end: Offset, color: Color, width: Float) {
    val handle = (kotlin.math.abs(end.x - start.x) * 0.5f).coerceIn(40f, 170f)
    val dir = if (end.x >= start.x) 1f else -1f
    val c1 = Offset(start.x + dir * handle, start.y)
    val c2 = Offset(end.x - dir * handle, end.y)
    val path = Path().apply { moveTo(start.x, start.y); cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y) }
    drawPath(path, color, style = Stroke(width = width))
    drawArrowHead(end, c2, color)
}
