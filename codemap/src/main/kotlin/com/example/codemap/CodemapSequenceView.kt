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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.graph.GraphCanvas
import com.example.graph.readsAsCode
import com.example.graph.GraphColors
import com.example.graph.GraphEdge
import com.example.graph.GraphFlow
import com.example.graph.GraphLayout
import com.example.graph.GraphNode
import com.google.gson.JsonArray
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
            Column(Modifier.fillMaxSize().padding(PAD)) {
                EmptyState(
                    "🧭",
                    "이 파일에 패킷 시퀀스가 없습니다",
                    "코드맵 패널의 [＋ 패킷 시퀀스] 로 시나리오를 하나 부탁하세요.",
                    p,
                )
            }
            return@Column
        }

        val currentJson = raw.firstOrNull { it.name() == vm.selected } ?: raw.first()
        val flow = remember(currentJson) { FlowModel.parse(currentJson) }

        Flow(flow, raw, vm, p)
    }
}

@Composable
private fun Header(vm: SequenceViewModel, p: CodemapPalette) {
    Column(Modifier.fillMaxWidth().padding(start = PAD, end = PAD, top = Space.md)) {
        Text(vm.fileName, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("노트에 기록된 패킷 시퀀스 — 요청해서 모은 것입니다", color = p.mutedText, fontSize = Type.micro)
    }
}

/**
 * The file's flows, so switching between two scenarios costs a click.
 *
 * A dropdown rather than a row of chips: a scenario's name is a sentence ("로그인부터 월드 입장까지"), and
 * three of those already overflow into a horizontal scroll that hides the very options it is offering.
 * The step count rides along as each option's detail, which is the one number that says how big a read it is.
 */
@Composable
private fun FlowPicker(
    flows: List<JsonObject>,
    current: String,
    p: CodemapPalette,
    onSelect: (String) -> Unit,
) {
    Picker(
        label = "흐름",
        items = flows.map { f -> PickerItem(f.name(), "%,d단계".format(f.steps())) },
        selected = flows.indexOfFirst { it.name() == current }.coerceAtLeast(0),
        palette = p,
    ) { i -> onSelect(flows[i].name()) }
}

@Composable
private fun Flow(
    flow: FlowModel.Flow,
    all: List<JsonObject>,
    vm: SequenceViewModel,
    p: CodemapPalette,
) {
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
        Controls(flow, all, step, chart, vm, p, onGo = ::go, onChart = { chart = it })
        Rule(p)

        Row(Modifier.fillMaxSize()) {
            StepList(steps, step, p) { go(it) }
            Box(
                Modifier.width(1.dp).fillMaxHeight()
                    .background(p.border.copy(alpha = 0.5f)),
            )
            Box(Modifier.fillMaxSize()) {
                if (chart) LifelineChart(flow, p) else Diagram(flow, active, vm, p)
            }
        }
    }
}

/**
 * Which flow, where in it, and how to read it — one row.
 *
 * These were three stacked rows with three different insets, which is three chances to look crooked. The
 * flow picker belongs beside the step counter anyway: choosing a scenario and walking it are the same act.
 */
@Composable
private fun Controls(
    flow: FlowModel.Flow,
    all: List<JsonObject>,
    step: Int,
    chart: Boolean,
    vm: SequenceViewModel,
    p: CodemapPalette,
    onGo: (Int) -> Unit,
    onChart: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = PAD, end = PAD, top = Space.sm, bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowPicker(all, flow.name, p) { vm.select(it) }
        Box(Modifier.width(Space.sm))
        HintedButton("◀", "이전 단계 (← ↑)", p, enabled = step >= 0) { onGo(step - 1) }
        HintedButton("▶", "다음 단계 (→ ↓ Space)", p, enabled = step < flow.steps.lastIndex) { onGo(step + 1) }
        Text(
            if (step < 0) "소개 — 전체 %,d단계".format(flow.steps.size)
            else "%,d / %,d".format(step + 1, flow.steps.size),
            color = p.mutedText,
            fontSize = Type.micro,
        )
        if (step >= 0) HintedButton("처음으로", "소개 단계로 (Home)", p) { onGo(-1) }
        Box(Modifier.weight(1f))
        Text("←→ 이동", color = p.mutedText, fontSize = Type.micro)
        // The chart is a different reading of the same steps, not a different truth — one toggle, no menu.
        HintedButton(
            if (chart) "흐름 보기" else "시퀀스 차트",
            if (chart) "다이어그램 위에서 단계별로 재생" else "같은 단계를 라이프라인 차트로 — 남에게 넘길 그림",
            p,
        ) { onChart(!chart) }
    }
}

/**
 * One inset for the whole tab.
 *
 * Every row used to bring its own — the header at 16, the step list at 8 plus the card's own 4, the canvas
 * toolbar at 12 — so nothing shared a left edge with anything else. A single value is the only way that
 * stays true as rows get added.
 */
private val PAD = Space.lg

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
    Column(Modifier.width(LIST_W).fillMaxHeight()) {
        // A heading of its own, so this column starts at the same height as the canvas toolbar beside it
        // rather than half a row above it.
        Row(
            // 6.dp matches the canvas toolbar's own vertical padding, so the two columns' first rows are
            // the same height and the content below them starts on one line.
            Modifier.fillMaxWidth().padding(start = PAD, end = PAD, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("단계", color = p.mutedText, fontSize = Type.micro)
            Box(Modifier.weight(1f))
            Text("%,d".format(steps.size), color = p.mutedText, fontSize = Type.micro)
        }
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // The card adds Space.xs of its own, so its text lands on the same left edge as every
                // heading above it.
                .padding(start = PAD - Space.xs, end = PAD - Space.xs, bottom = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            steps.forEach { s -> StepCard(s, s.index == current, p) { onSelect(s.index) } }
        }
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
        // A packet's number is filled in, the connecting steps' are outlined: the spine reads at a glance
        // without having to read a single label.
        val packet = step.kind == FlowModel.Kind.PACKET
        Box(
            Modifier.size(18.dp)
                .background(
                    when {
                        packet && active -> p.inbound
                        packet -> p.inbound.copy(alpha = 0.35f)
                        active -> p.accent.copy(alpha = 0.22f)
                        else -> p.accent.copy(alpha = 0.10f)
                    },
                    RoundedCornerShape(Radii.pill),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${step.index + 1}",
                color = if (packet) p.surface else p.accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Column(Modifier.fillMaxWidth()) {
            when (step.kind) {
                // The spine of the flow. A packet gets the loudest treatment on the card — its constant in
                // fixed pitch and its id as a badge — because tracing packets is what this view is for.
                FlowModel.Kind.PACKET -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${step.from}  →  ${step.to}",
                            color = p.mutedText,
                            fontSize = Type.micro,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (step.packetId.isNotEmpty()) {
                            Mono(step.packetId, p.inbound, size = Type.micro, weight = FontWeight.Medium)
                        }
                    }
                    Text(
                        step.label,
                        color = p.text,
                        fontSize = Type.label,
                        fontFamily = if (readsAsCode(step.label)) FontFamily.Monospace else FontFamily.Default,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FlowModel.Kind.NOTE -> Text(
                    step.label,
                    color = p.text,
                    fontSize = Type.label,
                    lineHeight = 16.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                FlowModel.Kind.PROCESS -> {
                    Text(
                        step.from,
                        color = p.mutedText,
                        fontSize = Type.micro,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(step.label, color = p.text, fontSize = Type.label, lineHeight = 16.sp)
                }

                else -> {
                    // One line, not three composables in a Row: a recorded participant can be a long
                    // phrase, and a Row lets it eat the width until the arrow and the other end are
                    // squeezed into a one-character column running down the card. Ellipsis degrades; a
                    // vertical stack of single letters does not.
                    Text(
                        "${step.from}  ${if (step.kind == FlowModel.Kind.RETURN) "←" else "→"}  ${step.to}",
                        color = p.mutedText,
                        fontSize = Type.micro,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        step.label,
                        color = p.text,
                        fontSize = Type.label,
                        fontFamily = if (readsAsCode(step.label)) FontFamily.Monospace else FontFamily.Default,
                        lineHeight = 16.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
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
private fun Diagram(
    flow: FlowModel.Flow,
    active: FlowModel.Step?,
    vm: SequenceViewModel,
    p: CodemapPalette,
) {
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
                        mark = if (s.kind == FlowModel.Kind.RETURN) "← " else "→ ",
                        // A packet shows its id; everything else shows its place in the order.
                        badge = s.packetId.ifEmpty { "${s.index + 1}" },
                        accented = s.kind == FlowModel.Kind.PACKET,
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
        // A participant is a class, so "go there" means its file. Resolved by the same string rule the
        // cross-note index uses — the file whose name (or a class it records) the participant names.
        onNodeDoubleClick = { name -> vm.openParticipant(name) },
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

/** How many steps a flow holds — the one number that says how big a read it is. */
private fun JsonObject.steps(): Int = (get("steps") as? JsonArray)?.size() ?: 0
