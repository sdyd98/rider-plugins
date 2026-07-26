package com.example.codemap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text

/**
 * Flow drawings for a note's `flows`.
 *
 * Two shapes, chosen by what the AI actually recorded rather than by a setting:
 *  - `steps: ["OnPacket", "HandleLogin", …]` — a chain of stages, drawn as a **flow chart**.
 *  - `steps: [{from, to, call}, …]` — who calls whom, drawn as a **sequence diagram**.
 *
 * The chain form stays supported because it is what earlier notes contain and because plenty of flows
 * genuinely are a straight line; asking for participants there would only invite invented ones.
 *
 * Everything is laid out on a fixed column grid, so no text measuring is needed and the drawing cannot
 * disagree with the labels placed over it. Wide diagrams scroll horizontally rather than shrinking —
 * an unreadable diagram that fits is worse than a readable one you nudge sideways.
 */
// Sized so four participants still fit a tool window at its usual width; beyond that the diagram
// scrolls sideways rather than shrinking its text into unreadability.
private val COL_W: Dp = 94.dp
private val HEAD_H: Dp = 26.dp
private val STEP_H: Dp = 30.dp
private val BOX_W: Dp = 86.dp

/** A parsed sequence step. [ret] marks a value coming back rather than a call going out. */
data class SeqStep(val from: String, val to: String, val label: String, val ret: Boolean)

@Composable
fun SequenceDiagram(participants: List<String>, steps: List<SeqStep>, palette: CodemapPalette) {
    if (participants.isEmpty() || steps.isEmpty()) return
    val index = participants.withIndex().associate { (i, p) -> p to i }
    val totalW = COL_W * participants.size
    val totalH = HEAD_H + STEP_H * steps.size + Space.sm

    Box(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Space.xs),
    ) {
        Box(Modifier.width(totalW).height(totalH)) {
            // Lifelines and arrows: one canvas under everything, so the geometry is computed once.
            Canvas(Modifier.width(totalW).height(totalH)) {
                val colW = COL_W.toPx()
                val headH = HEAD_H.toPx()
                val stepH = STEP_H.toPx()
                val line = palette.border.copy(alpha = 0.75f)

                participants.indices.forEach { i ->
                    val x = colW * i + colW / 2f
                    drawLine(
                        color = line,
                        start = Offset(x, headH),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)),
                    )
                }

                steps.forEachIndexed { k, s ->
                    val fromI = index[s.from] ?: return@forEachIndexed
                    val toI = index[s.to] ?: return@forEachIndexed
                    val y = headH + stepH * k + stepH / 2f
                    val color = if (s.ret) palette.mutedText else palette.accent
                    val effect = if (s.ret) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null
                    val x1 = colW * fromI + colW / 2f
                    val x2 = colW * toI + colW / 2f

                    if (fromI == toI) {
                        // Self-call: a small bracket to the right of the lifeline.
                        val w = colW * 0.22f
                        val h = stepH * 0.34f
                        drawLine(color, Offset(x1, y - h), Offset(x1 + w, y - h), 1.4f, pathEffect = effect)
                        drawLine(color, Offset(x1 + w, y - h), Offset(x1 + w, y + h), 1.4f, pathEffect = effect)
                        drawLine(color, Offset(x1 + w, y + h), Offset(x1, y + h), 1.4f, pathEffect = effect)
                        arrowHead(x1, y + h, -1f, color)
                    } else {
                        drawLine(color, Offset(x1, y), Offset(x2, y), 1.4f, pathEffect = effect)
                        arrowHead(x2, y, if (x2 > x1) 1f else -1f, color)
                    }
                }
            }

            // Participant headers.
            Row(Modifier.height(HEAD_H)) {
                participants.forEach { name ->
                    Box(Modifier.width(COL_W), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.width(BOX_W)
                                .background(palette.subtle, RoundedCornerShape(Radii.sm))
                                .border(BorderStroke(1.dp, palette.border.copy(alpha = 0.6f)), RoundedCornerShape(Radii.sm))
                                .padding(horizontal = Space.xs, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name,
                                color = palette.text,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // Labels ride above their arrow, spanning the columns the arrow crosses.
            steps.forEachIndexed { k, s ->
                val fromI = index[s.from] ?: return@forEachIndexed
                val toI = index[s.to] ?: return@forEachIndexed
                val left = minOf(fromI, toI)
                val span = if (fromI == toI) 1 else maxOf(fromI, toI) - left + 1
                Box(
                    Modifier
                        .offset(x = COL_W * left, y = HEAD_H + STEP_H * k)
                        .width(COL_W * span)
                        .height(STEP_H / 2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.label,
                        color = if (s.ret) palette.mutedText else palette.text,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A short filled triangle at the end of an arrow. [dir] is +1 pointing right, -1 pointing left. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.arrowHead(
    x: Float,
    y: Float,
    dir: Float,
    color: Color,
) {
    val len = 5f
    drawLine(color, Offset(x, y), Offset(x - len * dir, y - 3.5f), 1.4f)
    drawLine(color, Offset(x, y), Offset(x - len * dir, y + 3.5f), 1.4f)
}

/**
 * A straight-line flow: each stage in a box, arrows down. Used when the note records stages rather
 * than who-calls-whom.
 */
@Composable
fun FlowChart(steps: List<String>, palette: CodemapPalette) {
    if (steps.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.xs),
        horizontalAlignment = Alignment.Start,
    ) {
        steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Box(
                    Modifier.size(width = 18.dp, height = 18.dp)
                        .background(palette.accent.copy(alpha = 0.12f), RoundedCornerShape(Radii.pill)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = palette.accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier
                        .background(palette.subtle, RoundedCornerShape(Radii.sm))
                        .border(BorderStroke(1.dp, palette.border.copy(alpha = 0.5f)), RoundedCornerShape(Radii.sm))
                        .padding(horizontal = Space.sm, vertical = 3.dp),
                ) {
                    Text(step, color = palette.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            if (i < steps.lastIndex) {
                Box(Modifier.padding(start = 8.dp)) {
                    Canvas(Modifier.width(2.dp).height(12.dp)) {
                        drawLine(
                            palette.border,
                            Offset(size.width / 2, 0f),
                            Offset(size.width / 2, size.height),
                            1.4f,
                        )
                    }
                }
            }
        }
    }
}

// ---- call graph ----

// Three callees are the common case; at this width they fit a tool window without scrolling.
private val NODE_W: Dp = 96.dp
private val NODE_H: Dp = 22.dp
private val ROW_GAP: Dp = 28.dp
private val NODE_GAP: Dp = 8.dp

/** A node in the call graph. [jump] is null when the note has no anchor to navigate to. */
data class CallNode(val label: String, val jump: (() -> Unit)?)

/**
 * Callers above, the function itself in the middle, callees below.
 *
 * Drawn from what the NOTE records — the functions whose `calls` mention this one, and its own `calls`
 * — which makes it a curated picture of the paths that matter, not an exhaustive one. It is scoped to
 * this file on purpose: the exhaustive answer is Rider's Find Usages, and pretending a note could
 * replace that would be the kind of confident-but-wrong drawing this plugin exists to avoid.
 */
@Composable
fun CallGraph(
    callers: List<CallNode>,
    focus: String,
    callees: List<CallNode>,
    palette: CodemapPalette,
) {
    if (callers.isEmpty() && callees.isEmpty()) return
    val rows = listOf(callers.size, 1, callees.size)
    val widest = rows.max()
    val totalW = (NODE_W + NODE_GAP) * widest
    val rowCount = listOf(callers.isNotEmpty(), true, callees.isNotEmpty()).count { it }
    val totalH = NODE_H * rowCount + ROW_GAP * (rowCount - 1)

    // Row y-centres, skipping the rows that have nothing in them.
    val yOf = HashMap<Int, Dp>()
    var y = NODE_H / 2
    listOf(0 to callers.isNotEmpty(), 1 to true, 2 to callees.isNotEmpty()).forEach { (row, present) ->
        if (!present) return@forEach
        yOf[row] = y
        y += NODE_H + ROW_GAP
    }

    fun xOf(index: Int, count: Int): Dp =
        (totalW - (NODE_W + NODE_GAP) * count) / 2 + (NODE_W + NODE_GAP) * index + NODE_GAP / 2

    Box(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = Space.xs),
    ) {
        Box(Modifier.width(totalW).height(totalH)) {
            Canvas(Modifier.width(totalW).height(totalH)) {
                val focusY = yOf[1]!!.toPx()
                val focusX = (xOf(0, 1) + NODE_W / 2).toPx()
                val h = (NODE_H / 2).toPx()
                callers.indices.forEach { i ->
                    val x = (xOf(i, callers.size) + NODE_W / 2).toPx()
                    val fromY = yOf[0]!!.toPx() + h
                    drawLine(palette.accent.copy(alpha = 0.55f), Offset(x, fromY), Offset(focusX, focusY - h), 1.3f)
                    arrowDown(focusX, focusY - h, palette.accent.copy(alpha = 0.55f))
                }
                callees.indices.forEach { i ->
                    val x = (xOf(i, callees.size) + NODE_W / 2).toPx()
                    val toY = yOf[2]!!.toPx() - h
                    drawLine(palette.accent.copy(alpha = 0.55f), Offset(focusX, focusY + h), Offset(x, toY), 1.3f)
                    arrowDown(x, toY, palette.accent.copy(alpha = 0.55f))
                }
            }

            callers.forEachIndexed { i, n ->
                GraphNode(n, palette, false, Modifier.offset(x = xOf(i, callers.size), y = yOf[0]!! - NODE_H / 2))
            }
            GraphNode(CallNode(focus, null), palette, true, Modifier.offset(x = xOf(0, 1), y = yOf[1]!! - NODE_H / 2))
            callees.forEachIndexed { i, n ->
                GraphNode(n, palette, false, Modifier.offset(x = xOf(i, callees.size), y = yOf[2]!! - NODE_H / 2))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.arrowDown(x: Float, y: Float, color: Color) {
    drawLine(color, Offset(x, y), Offset(x - 3.5f, y - 5f), 1.3f)
    drawLine(color, Offset(x, y), Offset(x + 3.5f, y - 5f), 1.3f)
}

@Composable
private fun GraphNode(node: CallNode, palette: CodemapPalette, focus: Boolean, modifier: Modifier) {
    val tint = if (focus) palette.accent else palette.text
    Box(
        modifier
            .width(NODE_W).height(NODE_H)
            .background(
                if (focus) palette.accent.copy(alpha = 0.14f) else palette.subtle,
                RoundedCornerShape(Radii.sm),
            )
            .border(
                BorderStroke(1.dp, tint.copy(alpha = if (focus) 0.5f else 0.25f)),
                RoundedCornerShape(Radii.sm),
            )
            .let { m -> if (node.jump != null) m.clickable { node.jump.invoke() } else m }
            .padding(horizontal = Space.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            node.label.substringAfterLast("::"),
            color = if (node.jump != null || focus) tint else palette.mutedText,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (focus) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
