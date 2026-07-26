package com.example.codemap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.graph.GraphCanvas
import com.example.graph.GraphColors
import com.example.graph.GraphEdge
import com.example.graph.GraphFlow
import com.example.graph.GraphLayout
import com.example.graph.GraphNode
import com.google.gson.JsonObject
import org.jetbrains.jewel.ui.component.Text

/**
 * The flow viewer: a recorded scenario, stepped through on top of the diagram it happens on.
 *
 * Built after IcePanel's flows rather than a UML lifeline chart. The argument that convinced the design:
 * a lifeline chart is a SECOND picture of the system, which has to be read separately and kept in step with
 * the first, while a flow is an ordered path over the objects themselves — so stepping through it shows the
 * same cards lighting up in turn. For someone learning a codebase file by file, watching the call travel is
 * worth more than reading a chart of it.
 *
 * What is cloned from that model: numbered step cards down the side, one active step at a time, the diagram
 * spotlighting the active connection while the rest fades, chronological ◀ ▶ controls plus arrow keys, and
 * clicking any step to jump to it non-linearly. The lifeline chart is still here behind a toggle, because
 * IcePanel's own users ask for one and a chart is genuinely better for handing to somebody else.
 */
@Composable
fun CodemapSequenceView(vm: SequenceViewModel) {
    val p = rememberCodemapPalette()
    val raw = remember(vm.rel, vm.revision) { vm.flows() }

    Column(Modifier.fillMaxSize()) {
        Header(vm, p)

        if (raw.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Space.lg)) {
                EmptyState(
                    "🧭",
                    "이 파일에 시퀀스가 없습니다",
                    "코드맵 패널의 [＋ 시퀀스 요청] 으로 시나리오를 하나 부탁하세요.",
                    p,
                )
            }
            return@Column
        }

        val currentJson = raw.firstOrNull { it.name() == vm.selected } ?: raw.first()
        val flow = remember(currentJson) { FlowModel.parse(currentJson) }

        FlowChips(raw, flow.name, p) { vm.select(it) }
        Flow(flow, vm, p)
    }
}

@Composable
private fun Header(vm: SequenceViewModel, p: CodemapPalette) {
    Column(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, top = Space.md)) {
        Text(vm.fileName, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("노트에 기록된 흐름 — 요청해서 모은 것입니다", color = p.mutedText, fontSize = Type.micro)
    }
}

/** The file's flows, so switching between two scenarios costs a click. */
@Composable
private fun FlowChips(
    flows: List<JsonObject>,
    current: String,
    p: CodemapPalette,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = Space.lg, end = Space.lg, top = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        flows.forEach { f ->
            val name = f.name()
            ActionButton(name, p, primary = name == current) { onSelect(name) }
        }
    }
}

@Composable
private fun Flow(flow: FlowModel.Flow, vm: SequenceViewModel, p: CodemapPalette) {
    // -1 is the introduction: every step visible at once, nothing spotlit — IcePanel's way of setting the
    // scene before walking it.
    var step by remember(flow) { mutableStateOf(-1) }
    var chart by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(flow) { focus.requestFocus() }

    val steps = flow.steps
    val active = steps.getOrNull(step)

    fun go(to: Int) { step = to.coerceIn(-1, steps.lastIndex) }

    Column(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.DirectionDown, Key.Spacebar -> { go(step + 1); true }
                    Key.DirectionLeft, Key.DirectionUp -> { go(step - 1); true }
                    Key.Home -> { step = -1; true }
                    else -> false
                }
            },
    ) {
        Controls(flow, step, chart, p, onGo = ::go, onChart = { chart = it })
        Rule(p)

        Row(Modifier.fillMaxSize()) {
            StepList(steps, step, p) { go(it) }
            Box(
                Modifier.width(1.dp).fillMaxHeight()
                    .background(p.border.copy(alpha = 0.5f)),
            )
            Box(Modifier.fillMaxSize()) {
                if (chart) LifelineChart(flow, p) else Diagram(flow, active, p)
            }
        }
    }
}

/** ◀ ▶ and where you are — plus the escape hatch to the chart. */
@Composable
private fun Controls(
    flow: FlowModel.Flow,
    step: Int,
    chart: Boolean,
    p: CodemapPalette,
    onGo: (Int) -> Unit,
    onChart: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, top = Space.xs, bottom = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton("◀", p, enabled = step >= 0, primary = false) { onGo(step - 1) }
        ActionButton("▶", p, enabled = step < flow.steps.lastIndex, primary = false) { onGo(step + 1) }
        Text(
            if (step < 0) "소개 — 전체 %,d단계".format(flow.steps.size)
            else "%,d / %,d".format(step + 1, flow.steps.size),
            color = p.mutedText,
            fontSize = Type.micro,
        )
        if (step >= 0) ActionButton("처음으로", p, primary = false) { onGo(-1) }
        Box(Modifier.weight(1f))
        Text("←→ 이동", color = p.mutedText, fontSize = Type.micro)
        // The chart is a different reading of the same steps, not a different truth — one toggle, no menu.
        ActionButton(if (chart) "흐름 보기" else "시퀀스 차트", p, primary = false) { onChart(!chart) }
    }
}

private val LIST_W = 260.dp

/**
 * Numbered step cards, the active one raised.
 *
 * The whole description lives here rather than on the diagram: an arrow can carry a function name, but the
 * sentence explaining why the call happens needs a line of prose, and prose on a canvas fights the drawing.
 */
@Composable
private fun StepList(
    steps: List<FlowModel.Step>,
    current: Int,
    p: CodemapPalette,
    onSelect: (Int) -> Unit,
) {
    Column(
        Modifier.width(LIST_W).fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        steps.forEach { s -> StepCard(s, s.index == current, p) { onSelect(s.index) } }
    }
}

@Composable
private fun StepCard(step: FlowModel.Step, active: Boolean, p: CodemapPalette, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            active -> p.accent.copy(alpha = 0.10f)
            hovered -> p.surfaceHover
            else -> Color.Transparent
        },
    )
    Row(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(Radii.sm))
            .border(
                BorderStroke(1.dp, if (active) p.accent.copy(alpha = 0.55f) else Color.Transparent),
                RoundedCornerShape(Radii.sm),
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.xs, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // The numbered pill, same as the flow chart's — the rhythm that makes an ordered list scannable.
        Box(
            Modifier.size(18.dp)
                .background(
                    if (active) p.accent.copy(alpha = 0.22f) else p.accent.copy(alpha = 0.10f),
                    RoundedCornerShape(Radii.pill),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("${step.index + 1}", color = p.accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.fillMaxWidth()) {
            when (step.kind) {
                FlowModel.Kind.NOTE ->
                    Text(step.label, color = p.text, fontSize = Type.label, lineHeight = 16.sp)

                FlowModel.Kind.PROCESS -> {
                    Mono(step.from, p.mutedText, size = Type.micro)
                    Text(step.label, color = p.text, fontSize = Type.label, lineHeight = 16.sp)
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Mono(step.from, p.mutedText, size = Type.micro)
                        Text(
                            if (step.kind == FlowModel.Kind.RETURN) "⟵" else "⟶",
                            color = if (step.kind == FlowModel.Kind.RETURN) p.mutedText else p.accent,
                            fontSize = Type.micro,
                        )
                        Mono(step.to, p.mutedText, size = Type.micro)
                    }
                    Text(
                        step.label,
                        color = p.text,
                        fontSize = Type.label,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            }
            if (step.description.isNotEmpty()) {
                Text(step.description, color = p.mutedText, fontSize = Type.micro, lineHeight = 15.sp)
            }
        }
    }
}

/**
 * The participants as a graph, with the active step spotlit.
 *
 * The same canvas the call graph uses, so a flow and a call graph look like one product. Nodes are the
 * participants in first-appearance order; the rows on each card are the messages it sends, which makes a
 * card readable on its own once the presentation has moved on.
 */
@Composable
private fun Diagram(flow: FlowModel.Flow, active: FlowModel.Step?, p: CodemapPalette) {
    val connections = remember(flow) { FlowModel.connections(flow) }
    val nodes = remember(flow) {
        flow.participants.mapIndexed { i, name ->
            GraphNode(
                id = name,
                title = name,
                subtitle = null,
                rows = flow.steps.filter { it.from == name && it.isEdge }.map { s ->
                    com.example.graph.GraphRow(
                        text = s.label,
                        mark = if (s.kind == FlowModel.Kind.RETURN) "⟵ " else "→ ",
                        badge = "${s.index + 1}",
                        key = "${s.index}",
                        mono = true,
                    )
                },
                rank = i,
                titleMono = true,
            )
        }
    }
    val edges = remember(flow) {
        flow.steps.filter { it.isEdge }.map { GraphEdge(it.from, it.to, fromRow = "${it.index}") }
    }

    val activeEdges = remember(active, edges) {
        val s = active ?: return@remember emptySet<GraphEdge>()
        if (!s.isEdge) emptySet() else edges.filter { it.fromRow == "${s.index}" }.toSet()
    }
    val activeNodes = remember(active) {
        val s = active ?: return@remember emptySet<String>()
        when (s.kind) {
            FlowModel.Kind.NOTE -> emptySet()
            FlowModel.Kind.PROCESS -> setOf(s.from)
            else -> setOf(s.from, s.to)
        }
    }

    GraphCanvas(
        nodes = nodes,
        edges = edges,
        colors = GraphColors(fgArgb = p.text.toArgb(), bgArgb = p.surface.toArgb(), accentArgb = p.accent.toArgb()),
        activeNodes = activeNodes,
        activeEdges = activeEdges,
        // Left-to-right: a flow reads the way the sentence describing it does, and the participants are
        // ordered by when they first appear rather than by any structural rank.
        layout = GraphLayout.Layered,
        flow = GraphFlow.LeftToRight,
        modifier = Modifier.fillMaxSize(),
    )
    if (connections.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(Space.lg)) {
            Text(
                "이 흐름에는 객체 간 연결이 기록되지 않았습니다 — 단계 목록으로 읽어주세요.",
                color = p.mutedText,
                fontSize = Type.micro,
            )
        }
    }
}

/** The classic reading of the same steps, for handing to somebody who wants a chart. */
@Composable
private fun LifelineChart(flow: FlowModel.Flow, p: CodemapPalette) {
    val steps = flow.steps.filter { it.kind != FlowModel.Kind.NOTE }.map { s ->
        SeqStep(from = s.from, to = s.to, label = s.label, ret = s.kind == FlowModel.Kind.RETURN)
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg)) {
        if (steps.isEmpty()) {
            Text("차트로 그릴 단계가 없습니다.", color = p.mutedText, fontSize = Type.micro)
        } else {
            SequenceDiagram(flow.participants, steps, p, scale = 1.6f)
        }
    }
}

private fun JsonObject.name(): String =
    get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
