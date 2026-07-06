package com.example.xlsx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text

/** One expandable field row of a node: a reference [column] → its [targets] (empty = a group member). */
private data class RowInfo(val column: String, val targets: List<RefRecord>, val embedded: Boolean)

private fun groupRows(links: List<RefLink>): List<RowInfo> {
    val map = LinkedHashMap<String, Pair<MutableList<RefRecord>, Boolean>>()
    links.forEach { l -> map.getOrPut(l.column) { mutableListOf<RefRecord>() to l.embedded }.first.add(l.to) }
    return map.map { (col, v) -> RowInfo(col, v.first, v.second) }
}

/** Pre-computed neighbourhood for one centre — built off the EDT (db.out streams tables from disk). */
private class GraphData(
    val center: RefRecord,
    val outLinks: List<RefLink>,
    val inLinks: List<RefLink>,
    val outNodes: List<RefRecord>,
    val inNodes: List<RefRecord>,
    val nodes: List<RefRecord>,
    val nodeRows: Map<RefRecord, List<RowInfo>>,
)

/** Tool-window content: switch between the table-level ER map and the record-level data explorer. */
@Composable
fun RelationshipTabs(project: com.intellij.openapi.project.Project, fgArgb: Int, bgArgb: Int) {
    // Schema from refs.json next to the OPEN workbook, re-resolved REACTIVELY: on editor tab switches
    // (message bus) and whenever the nearest refs.json appears/changes on disk (2 s signature poll on the
    // UI dispatcher — a few file stats) — so a refs.json just written (e.g. by the MCP tools) shows up
    // without reopening the tool window. No refs.json → guidance; there is NO sample/mock fallback.
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val conn = project.messageBus.connect()
        conn.subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    tick++
                }
            },
        )
        onDispose { conn.disconnect() }
    }
    LaunchedEffect(Unit) {
        var last = schemaSignature(project)
        while (true) {
            kotlinx.coroutines.delay(2000)
            val sig = schemaSignature(project)
            if (sig != last) {
                last = sig
                tick++
            }
        }
    }
    val schema = remember(tick) { if (project.isDisposed) null else resolveSchema(project) }
    if (schema == null) {
        NoSchemaView(project, fgArgb)
        return
    }
    // Build the index OFF the EDT — first open streams every workbook, which must not freeze the UI.
    // (RefSchema is a data class: an unchanged refs.json re-resolves to an EQUAL schema, so the key
    // doesn't change and the index is not rebuilt.)
    val loaded by produceState<Result<Pair<RefGraph, RecordSource>>?>(null, schema) {
        value = withContext(Dispatchers.Default) {
            runCatching { buildRefGraph(schema) to IndexRecordGraph(schema, GameDataLoader.loadOrBuildIndex(schema)) }
        }
    }
    var mode by remember { mutableStateOf(0) }
    // The grid (Ctrl+R) can request a specific record → switch to the data view + centre on it.
    val req = RelationshipBus.request.value
    LaunchedEffect(req) { if (req != null) mode = 1 }
    // The grid (Ctrl+F) can request a table → switch to the ER map + centre on it.
    val tableReq = RelationshipBus.tableRequest.value
    LaunchedEffect(tableReq) { if (tableReq != null) mode = 0 }

    val result = loaded
    if (result == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("관계도 로딩 중…", color = Color(fgArgb).copy(alpha = 0.55f)) }
        return
    }
    val pair = result.getOrNull()
    if (pair == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("관계도 데이터를 읽지 못했습니다 — refs.json과 워크북 파일을 확인하세요", color = Color(fgArgb).copy(alpha = 0.7f))
        }
        return
    }
    val (graph, db) = pair
    val onOpenTable: (String) -> Unit = { id -> openTableInEditor(project, schema, id) }
    val onOpenRecord: (RefRecord) -> Unit = { rec -> openRecordInEditor(project, schema, rec) }
    val report = remember(db) { runCatching { db.validate() }.getOrNull() } // workbook integrity scan
    val requested = req?.let { r -> db.find(r.table, r.id) ?: db.find(r.table, r.id.substringBefore('·')) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (mode == 0) "● " else "○ ") + "테이블 관계도", Modifier.clickable { mode = 0 })
            Spacer(Modifier.width(16.dp))
            Text((if (mode == 1) "● " else "○ ") + "데이터 연결", Modifier.clickable { mode = 1 })
            Spacer(Modifier.width(16.dp))
            Text((if (mode == 2) "● " else "○ ") + "검사" + (report?.broken?.size?.takeIf { it > 0 }?.let { " ($it⚠)" } ?: ""), Modifier.clickable { mode = 2 })
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            when (mode) {
                0 -> RefGraphView(graph, fgArgb, bgArgb, onOpenTable, centerRequest = tableReq)
                1 -> DataGraphView(db, fgArgb, bgArgb, onOpenRecord, requested)
                else -> ValidationView(report, fgArgb, bgArgb, onOpenRecord)
            }
        }
    }
}

/** Shown when there is no usable schema — guidance instead of sample data. Re-resolution is reactive,
 *  so the moment a refs.json is created (or a workbook is opened) the real views replace this. */
@Composable
private fun NoSchemaView(project: com.intellij.openapi.project.Project, fgArgb: Int) {
    val fg = Color(fgArgb)
    val muted = fg.copy(alpha = 0.55f)
    val openFile = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (openFile == null) {
                Text("엑셀 파일을 열면 관계도를 표시합니다", color = fg.copy(alpha = 0.8f))
                Spacer(Modifier.height(8.dp))
                Text("열린 워크북 폴더(또는 상위)의 refs.json 스키마를 사용합니다", color = muted, fontSize = 12.sp)
            } else {
                Text("refs.json 스키마가 없습니다 (또는 유효하지 않습니다)", color = fg.copy(alpha = 0.8f))
                Spacer(Modifier.height(8.dp))
                Text("워크북 폴더(또는 상위)에 refs.json이 생기면 자동으로 표시됩니다", color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("AI 클라이언트(MCP)에서 build_refs로 시작하세요", color = muted, fontSize = 12.sp)
            }
        }
    }
}

/** Workbook integrity report: dangling references + unreferenced (orphan) records, click to open. */
@Composable
private fun ValidationView(report: ValidationReport?, fgArgb: Int, bgArgb: Int, onOpen: (RefRecord) -> Unit) {
    val fg = Color(fgArgb)
    if (report == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("검증할 데이터가 없습니다", color = fg.copy(alpha = 0.6f)) }
        return
    }
    val danger = Color(0xFFE5484D)
    val muted = fg.copy(alpha = 0.55f)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ValStatChip(report.broken.size, "깨진 참조", danger)
            Spacer(Modifier.width(10.dp))
            ValStatChip(report.orphans.size, "미참조", muted)
        }
        Spacer(Modifier.height(22.dp))

        ValSectionHeader("깨진 참조", report.broken.size, if (report.broken.isEmpty()) muted else danger, fg)
        Spacer(Modifier.height(8.dp))
        if (report.broken.isEmpty()) ValOkRow("깨진 참조 없음", fg)
        else report.broken.forEach { b -> ValBrokenRow(b, bgArgb, fg, danger, onOpen) }

        Spacer(Modifier.height(24.dp))
        ValSectionHeader("미참조 — 어디서도 참조되지 않는 레코드", report.orphans.size, muted, fg)
        Spacer(Modifier.height(8.dp))
        if (report.orphans.isEmpty()) ValOkRow("모든 레코드가 참조됨", fg)
        else {
            report.orphans.take(300).forEach { o -> ValOrphanRow(o, bgArgb, fg, muted, onOpen) }
            if (report.orphans.size > 300) Text("… 외 ${report.orphans.size - 300}개", color = muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

@Composable
private fun ValStatChip(count: Int, label: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(label, color = color.copy(alpha = 0.9f), fontSize = 12.sp)
    }
}

@Composable
private fun ValSectionHeader(title: String, count: Int, color: Color, fg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(title, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = color.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ValOkRow(text: String, fg: Color) {
    Row(Modifier.padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = Color(0xFF2E9E54), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun ValBrokenRow(b: BrokenRef, bgArgb: Int, fg: Color, danger: Color, onOpen: (RefRecord) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(danger.copy(alpha = 0.07f)).clickable { onOpen(b.from) }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(tableColor(b.from.table, bgArgb), CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(b.from.name.ifEmpty { "${b.from.table} #${b.from.id}" }, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(7.dp))
        Text(b.column, color = fg.copy(alpha = 0.5f), fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text("→", color = fg.copy(alpha = 0.4f), fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text("${b.toTable} #${b.missingId}", color = danger, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text("없음", Modifier.background(danger.copy(alpha = 0.18f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp), color = danger, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ValOrphanRow(o: RefRecord, bgArgb: Int, fg: Color, muted: Color, onOpen: (RefRecord) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = 0.04f)).clickable { onOpen(o) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(tableColor(o.table, bgArgb), CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(o.name.ifEmpty { o.id }, color = fg.copy(alpha = 0.9f), fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text("${o.table} #${o.id}", color = muted, fontSize = 11.sp)
    }
}

/**
 * Record-level **lineage explorer**: the [center] record in the middle, the records it references on
 * the RIGHT (outgoing) and the records that reference it on the LEFT (incoming). Each node is a
 * collapsible card — a pill when collapsed (title + count + chevron), or expanded to show its reference
 * FIELD ROWS (column → target table badge). Edges anchor at the specific source field row; hovering a
 * node lights its connection path (violet) and dims the rest. Soft floating cards, theme-aware.
 *
 * Click a neighbour = re-centre · click the chevron = expand/collapse · click the centre = open sheet.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DataGraphView(db: RecordSource, fgArgb: Int, bgArgb: Int, onOpen: (RefRecord) -> Unit = {}, requested: RefRecord? = null) {
    val HEADER = 28f; val SUBH = 16f; val ROWH = 20f

    var target by remember { mutableStateOf(requested ?: db.defaultCenter()) } // requested centre — drives the async load
    LaunchedEffect(requested) { if (requested != null) target = requested } // grid Ctrl+R re-centres here
    var hovered by remember { mutableStateOf<RefRecord?>(null) }
    var lastHovered by remember { mutableStateOf<RefRecord?>(null) }
    var hoveredKey by remember { mutableStateOf<Pair<RefRecord, String>?>(null) } // (node, ref column) under the cursor
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var didFit by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer(cacheSize = 64) // expanded nodes re-measure >8 strings/frame
    val fg = Color(fgArgb)
    val bg = Color(bgArgb)
    val accent = Color(0xFF3574F0)
    val pathHi = Color(0xFF8B5CF6) // violet focus path (echoes the reference)
    val danger = Color(0xFFE5484D) // broken-reference red (readable on light + dark)
    val bgLum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val isLight = bgLum > 0.5f
    val cardBg = if (isLight) androidx.compose.ui.graphics.lerp(bg, Color.White, 0.6f) else androidx.compose.ui.graphics.lerp(bg, Color.White, 0.08f)
    val nameStyle = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val subStyle = TextStyle(color = fg.copy(alpha = 0.6f), fontSize = 11.sp)
    val rowStyle = TextStyle(color = fg.copy(alpha = 0.88f), fontSize = 12.sp)
    val badgeStyle = TextStyle(color = fg.copy(alpha = 0.7f), fontSize = 11.sp)

    val colorCache = remember(bgArgb) { HashMap<String, Color>() } // table id -> identity colour (visited tables only)
    fun hueOf(t: String): Color = colorCache.getOrPut(t) { tableColor(t, bgArgb) }

    // Build the centre's neighbourhood OFF the EDT — db.out() streams the table from disk, so a re-center
    // must not block the UI. The previously-loaded graph stays on screen until the new one is ready.
    val graphData by produceState<GraphData?>(null, target, db) {
        value = withContext(Dispatchers.Default) {
            val out = db.out(target)
            val inb = db.inbound(target)
            val outN = out.map { it.to }.distinct()
            val inN = inb.map { it.from }.distinct()
            val ns = (listOf(target) + outN + inN).distinct()
            val rows = ns.associateWith { r -> if (r.isGroup) r.members.map { RowInfo(it, emptyList(), false) } else groupRows(db.out(r)) }
            GraphData(target, out, inb, outN, inN, ns, rows)
        }
    }
    val gd = graphData
    if (gd == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("관계도 로딩 중…", color = fg.copy(alpha = 0.55f)) }
        return
    }
    val center = gd.center // the centre actually on screen (lags `target` only while a load is in flight)
    val outLinks = gd.outLinks; val inLinks = gd.inLinks
    val outNodes = gd.outNodes; val inNodes = gd.inNodes
    val nodes = gd.nodes; val nodeRows = gd.nodeRows
    var expanded by remember(center) { mutableStateOf(setOf(center)) } // the centre starts expanded
    fun rowsOf(r: RefRecord): List<RowInfo> = nodeRows[r].orEmpty()
    fun isExpanded(r: RefRecord) = r in expanded && rowsOf(r).isNotEmpty()

    fun titleOf(r: RefRecord): String = when {
        r.isGroup -> "⟨${r.id}⟩"
        r.name.isNotEmpty() -> r.name
        else -> "#${r.id}"
    }
    fun hasName(r: RefRecord) = !r.isGroup && r.name.isNotEmpty()
    fun subtitleOf(r: RefRecord): String = r.table + (if (hasName(r)) "  #${r.id}" else "") +
        (db.usageCount(r).let { if (it > 0) "  ↩$it" else "" }) // ↩N = referenced by N records
    fun countOf(r: RefRecord): Int = if (r.isGroup) r.members.size else rowsOf(r).size

    fun rowMarker(ri: RowInfo) = if (ri.targets.isEmpty()) "· " else "→ "
    fun rowBadge(ri: RowInfo): String {
        if (ri.targets.isEmpty()) return ""
        val t = ri.targets.first()
        return if (ri.targets.any { it.broken }) "${t.table} 없음"
        else t.table + (if (ri.targets.size > 1) " +${ri.targets.size - 1}" else "") + (if (ri.embedded) " str" else "")
    }
    // Width is measured from the SAME strings drawCard renders (incl. the broken "없음" badge) so cards never under-size.
    fun rowText(ri: RowInfo): String = rowMarker(ri) + ri.column + rowBadge(ri).let { if (it.isEmpty()) "" else "   $it" }

    fun sizeOf(r: RefRecord): Size {
        val isC = r == center
        val nameW = measurer.measure(titleOf(r), nameStyle.copy(fontSize = if (isC) 15.sp else 14.sp), softWrap = false).size.width
        val countW = if (rowsOf(r).isNotEmpty()) measurer.measure("${countOf(r)}", subStyle, softWrap = false).size.width + 22 else 0
        var w = maxOf(26f + nameW + 12f + countW, 26f + measurer.measure(subtitleOf(r), subStyle, softWrap = false).size.width)
        val exp = isExpanded(r)
        if (exp) rowsOf(r).forEach { ri -> w = maxOf(w, 30f + measurer.measure(rowText(ri), rowStyle, softWrap = false).size.width) }
        val h = HEADER + SUBH + if (exp) rowsOf(r).size * ROWH + 8f else 4f
        return Size(w + 18f, h)
    }
    val sizes = remember(center, expanded) { nodes.associateWith { sizeOf(it) } }
    val positions = remember(center, expanded) {
        val m = HashMap<RefRecord, Offset>()
        val colGap = 360f; val gap = 22f
        val cw = sizes[center]?.width ?: 120f; val ch = sizes[center]?.height ?: 40f
        m[center] = Offset(-cw / 2f, -ch / 2f)
        fun place(list: List<RefRecord>, rightAligned: Boolean) {
            val hs = list.map { sizes[it]?.height ?: 40f }
            val total = hs.sum() + gap * maxOf(0, list.size - 1)
            var top = -total / 2f
            list.forEachIndexed { i, r ->
                val x = if (rightAligned) -colGap - (sizes[r]?.width ?: 120f) else colGap
                m[r] = Offset(x, top); top += hs[i] + gap
            }
        }
        place(outNodes, rightAligned = false)
        place(inNodes, rightAligned = true)
        m
    }

    fun rectOf(r: RefRecord): Rect = Rect(positions[r] ?: Offset.Zero, sizes[r] ?: Size(120f, 40f))
    fun toWorld(p: Offset) = (p - pan) / zoom
    fun fit() {
        if (canvasSize.width == 0 || nodes.isEmpty()) return
        val rs = nodes.map { rectOf(it) }
        val minX = rs.minOf { it.left }; val minY = rs.minOf { it.top }
        val maxX = rs.maxOf { it.right }; val maxY = rs.maxOf { it.bottom }
        val gw = (maxX - minX).coerceAtLeast(1f); val gh = (maxY - minY).coerceAtLeast(1f)
        val m = 64f
        zoom = minOf((canvasSize.width - m * 2) / gw, (canvasSize.height - m * 2) / gh).coerceIn(0.3f, 1.6f)
        pan = Offset(canvasSize.width / 2f - (minX + maxX) / 2f * zoom, canvasSize.height / 2f - (minY + maxY) / 2f * zoom)
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(center) { appear.snapTo(0f); fit(); appear.animateTo(1f, tween(durationMillis = 460, easing = FastOutSlowInEasing)) }
    // Smooth the hover focus so crossing node boundaries fades (no flicker); keep the last hovered
    // node during fade-out so it eases both ways.
    LaunchedEffect(hovered) { if (hovered != null) lastHovered = hovered }
    val focusNode = hovered ?: lastHovered
    val hoverFocus by animateFloatAsState(if (hovered != null) 1f else 0f, tween(140), label = "hoverFocus")

    fun dim(s: TextStyle, a: Float): TextStyle = s.copy(color = s.color.copy(alpha = s.color.alpha * a))
    fun animPos(r: RefRecord): Offset = lerp(Offset.Zero, positions[r] ?: Offset.Zero, appear.value)
    fun drawRectOf(r: RefRecord): Rect = Rect(animPos(r), sizes[r] ?: Size(120f, 40f))
    fun rowY(node: RefRecord, rect: Rect, column: String): Float {
        if (!isExpanded(node)) return rect.top + HEADER / 2f
        val idx = rowsOf(node).indexOfFirst { it.column == column }
        return if (idx < 0) rect.top + HEADER / 2f else rect.top + HEADER + SUBH + idx * ROWH + ROWH / 2f
    }

    fun drawShadow(scope: DrawScope, rect: Rect, elevated: Boolean, a: Float) {
        with(scope) {
            val layers = if (elevated) 6 else 4
            val maxOff = if (elevated) 13f else 7f
            for (i in 1..layers) {
                val t = i / layers.toFloat()
                val o = rect.inflate(t * (if (elevated) 3f else 1.8f)).translate(0f, maxOff * t)
                drawRoundRect(Color.Black.copy(alpha = (if (elevated) 0.06f else 0.045f) * (1f - t) * a), o.topLeft, o.size, CornerRadius(14f))
            }
        }
    }

    fun drawCard(scope: DrawScope, r: RefRecord, rect: Rect, a: Float, activeKeys: Set<Pair<RefRecord, String>>) {
        val isCenter = r == center; val h = hueOf(r.table); val exp = isExpanded(r)
        with(scope) {
            drawRoundRect(cardBg.copy(alpha = a), rect.topLeft, rect.size, CornerRadius(12f))
            val border = when {
                r.broken -> danger.copy(alpha = a)
                isCenter -> accent.copy(alpha = a)
                else -> fg.copy(alpha = 0.16f * a)
            }
            drawRoundRect(border, rect.topLeft, rect.size, CornerRadius(12f), style = Stroke(width = if (isCenter) 2f else if (r.broken) 1.6f else 1f))
            val cy0 = rect.top + HEADER / 2f
            drawCircle((if (r.broken) danger else h).copy(alpha = a), 4.5f, Offset(rect.left + 14f, cy0)) // table identity dot
            val nameLay = measurer.measure(titleOf(r), dim(nameStyle.copy(fontSize = if (isCenter) 15.sp else 14.sp), a), softWrap = false)
            drawText(nameLay, topLeft = Offset(rect.left + 26f, cy0 - nameLay.size.height / 2f))
            if (r.broken) { // dangling target: this id/key has no row
                val warn = measurer.measure("없음", dim(TextStyle(color = danger, fontSize = 11.sp, fontWeight = FontWeight.Bold), a), softWrap = false)
                drawText(warn, topLeft = Offset(rect.right - 8f - warn.size.width, cy0 - warn.size.height / 2f))
            }
            if (rowsOf(r).isNotEmpty()) { // count + chevron on the right of the header
                val chev = measurer.measure(if (exp) "▴" else "▾", dim(subStyle, a), softWrap = false)
                drawText(chev, topLeft = Offset(rect.right - 16f, cy0 - chev.size.height / 2f))
                val cnt = measurer.measure("${countOf(r)}", dim(subStyle.copy(fontSize = 11.sp), a), softWrap = false)
                drawText(cnt, topLeft = Offset(rect.right - 22f - cnt.size.width, cy0 - cnt.size.height / 2f))
            }
            // table + #id subtitle — always visible, so the table name shows even when collapsed
            drawText(measurer, subtitleOf(r), topLeft = Offset(rect.left + 26f, rect.top + HEADER - 1f), style = dim(subStyle.copy(fontSize = 10.sp), a), softWrap = false, overflow = TextOverflow.Visible)
            if (exp) {
                drawLine(fg.copy(alpha = 0.10f * a), Offset(rect.left + 10f, rect.top + HEADER + SUBH - 3f), Offset(rect.right - 10f, rect.top + HEADER + SUBH - 3f), 1f)
                rowsOf(r).forEachIndexed { i, ri ->
                    val y = rect.top + HEADER + SUBH + i * ROWH
                    // hot = active AND currently focused; scaled by hoverFocus so the row highlight FADES
                    // OUT when the cursor leaves a node (instead of the last value staying stuck on).
                    val active = ri.targets.isNotEmpty() && (r to ri.column) in activeKeys
                    val hot = active && hoverFocus > 0.01f
                    if (active) drawRoundRect(pathHi.copy(alpha = 0.12f * a * hoverFocus), Offset(rect.left + 6f, y), Size(rect.width - 12f, ROWH), CornerRadius(5f))
                    if (ri.targets.isEmpty()) {
                        drawText(measurer, rowMarker(ri) + ri.column, topLeft = Offset(rect.left + 16f, y + 3f), style = dim(subStyle, a), softWrap = false, overflow = TextOverflow.Visible)
                    } else {
                        val tBroken = ri.targets.any { it.broken }
                        val colStyle = when {
                            tBroken -> rowStyle.copy(color = danger, fontWeight = FontWeight.Bold)
                            hot -> rowStyle.copy(color = androidx.compose.ui.graphics.lerp(rowStyle.color, pathHi, hoverFocus), fontWeight = if (hoverFocus > 0.4f) FontWeight.Bold else FontWeight.Normal)
                            else -> rowStyle
                        }
                        drawText(measurer, rowMarker(ri) + ri.column, topLeft = Offset(rect.left + 14f, y + 3f), style = dim(colStyle, a), softWrap = false, overflow = TextOverflow.Visible)
                        val th = hueOf(ri.targets.first().table)
                        val badgeColor = if (tBroken) danger else if (hot) androidx.compose.ui.graphics.lerp(badgeStyle.color, pathHi, hoverFocus) else badgeStyle.color
                        val bl = measurer.measure(rowBadge(ri), dim(badgeStyle.copy(color = badgeColor), a), softWrap = false)
                        val bx = rect.right - 12f - bl.size.width
                        drawCircle((if (tBroken) danger else th).copy(alpha = a), 3.5f, Offset(bx - 9f, y + ROWH / 2f))
                        drawText(bl, topLeft = Offset(bx, y + (ROWH - bl.size.height) / 2f))
                    }
                }
            }
        }
    }

    // Pointer handlers run from a pointerInput(Unit) coroutine that captures values ONCE; wrap the
    // hit-testing in rememberUpdatedState so taps/hover always see the CURRENT nodes & layout (else
    // navigation breaks after the first recenter — the handler keeps hit-testing the old layout).
    val onTapAt by rememberUpdatedState({ p: Offset ->
        val w = toWorld(p)
        val node = nodes.lastOrNull { rectOf(it).contains(w) }
        if (node != null) {
            val rect = rectOf(node)
            val inChevron = rowsOf(node).isNotEmpty() && w.x >= rect.right - 24f && w.y <= rect.top + HEADER
            when {
                inChevron -> expanded = if (node in expanded) expanded - node else expanded + node
                node == center -> onOpen(node)
                else -> target = node
            }
        }
    })
    val onHoverAt by rememberUpdatedState({ p: Offset ->
        val w = toWorld(p)
        val n = nodes.lastOrNull { rectOf(it).contains(w) }
        if (n != hovered) hovered = n
        var key: Pair<RefRecord, String>? = null
        if (n != null && isExpanded(n)) { // which field ROW is the cursor over?
            val rect = rectOf(n); val rows = rowsOf(n)
            val idx = ((w.y - (rect.top + HEADER + SUBH)) / ROWH).toInt()
            if (idx in rows.indices && rows[idx].targets.isNotEmpty()) key = n to rows[idx].column
        }
        if (key != hoveredKey) hoveredKey = key
    })

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(center.name.ifEmpty { center.label }, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Text("📂", Modifier.clickable { onOpen(center) })
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
                    .onPointerEvent(PointerEventType.Move) {
                        val p = it.changes.firstOrNull()?.position ?: return@onPointerEvent
                        onHoverAt(p)
                    }
                    .onPointerEvent(PointerEventType.Exit) { hovered = null; hoveredKey = null }
                    .pointerInput(Unit) {
                        detectDragGestures(onDrag = { change, delta -> change.consume(); pan += delta })
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { p -> onTapAt(p) })
                    },
            ) {
                drawRect(bg, size = size)
                val step = 26f * zoom // dotted infinite-canvas background
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
                    val keyTargets = keyActive?.let { (n, c) -> rowsOf(n).firstOrNull { it.column == c }?.targets }.orEmpty()
                    // An edge (and its source key row) is "active" when you hover that exact key row, or
                    // hover either node it connects — so the connecting KEY STRING highlights both ways.
                    fun edgeActive(src: RefRecord, col: String, tgt: RefRecord): Boolean =
                        if (keyActive != null) src == keyActive.first && col == keyActive.second
                        else focusNode != null && (src == focusNode || tgt == focusNode)
                    val activeKeys = HashSet<Pair<RefRecord, String>>()
                    outLinks.forEach { if (edgeActive(center, it.column, it.to)) activeKeys.add(center to it.column) }
                    inLinks.forEach { if (edgeActive(it.from, it.column, center)) activeKeys.add(it.from to it.column) }
                    // Calm hover: only connected edges light up (violet); nothing dims. (Two loops to
                    // avoid allocating a merged Pair list every frame.)
                    fun drawEdge(scope: DrawScope, from: RefRecord, to: RefRecord, column: String) {
                        val fr = drawRectOf(from); val tr = drawRectOf(to)
                        val start = Offset(fr.right, rowY(from, fr, column))
                        val end = Offset(tr.left, tr.top + HEADER / 2f)
                        val onPath = edgeActive(from, column, to)
                        val edgeCol = if (onPath) androidx.compose.ui.graphics.lerp(fg.copy(alpha = 0.24f * a), pathHi.copy(alpha = a), hoverFocus) else fg.copy(alpha = 0.24f * a)
                        with(scope) { drawCurvedEdge(start, end, edgeCol, 1.4f + (if (onPath) 1f else 0f) * hoverFocus) }
                    }
                    outLinks.forEach { drawEdge(this, center, it.to, it.column) }
                    inLinks.forEach { drawEdge(this, it.from, center, it.column) }
                    // nodes: dimmed unless on the hovered path; soft shadow (lift on hover/centre) then card.
                    nodes.forEach { r ->
                        val rect = drawRectOf(r)
                        drawShadow(this, rect, elevated = (r == focusNode || r == center), a)
                        drawCard(this, r, rect, a, activeKeys)
                        if (r in keyTargets) { // hovering a key row → ring the connected node(s)
                            val ir = rect.inflate(3f)
                            drawRoundRect(pathHi.copy(alpha = a), ir.topLeft, ir.size, CornerRadius(14f), style = Stroke(2f))
                        }
                    }
                }
                if (outNodes.isEmpty() && inNodes.isEmpty()) {
                    val msg = "이 레코드와 연결된 데이터가 없어요"
                    val lay = measurer.measure(msg, subStyle, softWrap = false)
                    drawText(lay, topLeft = Offset((size.width - lay.size.width) / 2f, size.height * 0.66f))
                }
            }
        }
    }
}
