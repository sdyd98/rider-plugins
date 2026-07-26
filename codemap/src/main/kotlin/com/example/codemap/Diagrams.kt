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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
/** A parsed sequence step. [ret] marks a value coming back rather than a call going out. */
data class SeqStep(val from: String, val to: String, val label: String, val ret: Boolean)

// Base metrics at scale 1, sized for a tool window; the viewer tab scales them up.
private const val CARD_H = 24f
private const val CARD_PAD = 9f
private const val STEP_H = 34f
private const val GUTTER = 24f
private const val NAME_SP = 10.5f
private const val LABEL_SP = 10f
private const val NUM_SP = 8.5f
private const val COL_GAP = 26f
private const val EDGE = 10f

/**
 * A sequence diagram in the same visual language as [FlowChart]: numbered steps down the left, identifiers
 * in content-sized rounded cards, thin muted connectors.
 *
 * Laid out by MEASURING the text rather than on a fixed column grid. The grid version had to ellipsize
 * every participant whose name was longer than 86dp — which in C++ is most of them — and a diagram that
 * hides the names it is about answers nothing. Column spacing also grows to fit the widest label that
 * crosses it, so an arrow's caption never has to be truncated or overlap its neighbour.
 *
 * Everything is one Canvas: the drawing and the labels are placed by the same arithmetic, so they cannot
 * disagree. Wide diagrams scroll sideways instead of shrinking — an unreadable diagram that fits is worse
 * than a readable one you nudge across.
 */
@Composable
fun SequenceDiagram(
    participants: List<String>,
    steps: List<SeqStep>,
    palette: CodemapPalette,
    scale: Float = 1f,
) {
    if (participants.isEmpty() || steps.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val index = participants.withIndex().associate { (i, p) -> p to i }

    val nameStyle = TextStyle(
        color = palette.text,
        fontSize = (NAME_SP * scale).sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
    )
    val labelStyle = TextStyle(
        color = palette.text,
        fontSize = (LABEL_SP * scale).sp,
        fontFamily = FontFamily.Monospace,
    )
    val numStyle = TextStyle(
        color = palette.accent,
        fontSize = (NUM_SP * scale).sp,
        fontWeight = FontWeight.SemiBold,
    )

    val density = LocalDensity.current
    val layout = remember(participants, steps, scale, density) {
        sequenceLayout(participants, steps, scale, measurer, nameStyle, labelStyle)
    }

    Box(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = Space.xs),
    ) {
        Canvas(
            Modifier
                .width(with(density) { layout.width.toDp() })
                .height(with(density) { layout.height.toDp() }),
        ) {
            val cardH = CARD_H * scale
            val stepH = STEP_H * scale
            val top = cardH + EDGE * scale
            val lifeline = palette.border.copy(alpha = 0.55f)

            // Lifelines first, so everything else sits on top of them.
            layout.centers.forEach { x ->
                drawLine(
                    lifeline,
                    Offset(x, top),
                    Offset(x, size.height - 2f),
                    1f * scale,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * scale, 4f * scale)),
                )
            }

            steps.forEachIndexed { k, step ->
                val fromI = index[step.from] ?: return@forEachIndexed
                val toI = index[step.to] ?: return@forEachIndexed
                val y = top + stepH * k + stepH * 0.62f
                val ink = if (step.ret) palette.mutedText else palette.accent
                val dash = if (step.ret) PathEffect.dashPathEffect(floatArrayOf(5f * scale, 3f * scale)) else null
                val x1 = layout.centers[fromI]
                val x2 = layout.centers[toI]

                // The step number, in the gutter — the same numbered rhythm the flow chart reads with.
                val numLay = measurer.measure("${k + 1}", numStyle)
                val r = 8f * scale
                drawCircle(palette.accent.copy(alpha = 0.12f), r, Offset(GUTTER * scale / 2f, y))
                drawText(
                    numLay,
                    topLeft = Offset(
                        GUTTER * scale / 2f - numLay.size.width / 2f,
                        y - numLay.size.height / 2f,
                    ),
                )

                val labelLay = measurer.measure(step.label, labelStyle)
                val labelY: Float
                val labelCx: Float

                if (fromI == toI) {
                    // Self-call: a rounded bracket to the right of the lifeline, label beside it.
                    val w = 16f * scale
                    val h = stepH * 0.26f
                    val path = Path().apply {
                        moveTo(x1, y - h)
                        lineTo(x1 + w, y - h)
                        lineTo(x1 + w, y + h)
                        lineTo(x1 + 4f * scale, y + h)
                    }
                    drawPath(path, ink, style = Stroke(width = 1.3f * scale, pathEffect = dash))
                    arrowHead(Offset(x1 + 2f * scale, y + h), -1f, ink, scale, open = step.ret)
                    labelCx = x1 + w + 6f * scale + labelLay.size.width / 2f
                    labelY = y - labelLay.size.height / 2f
                } else {
                    drawLine(ink, Offset(x1, y), Offset(x2, y), 1.3f * scale, pathEffect = dash)
                    arrowHead(Offset(x2, y), if (x2 > x1) 1f else -1f, ink, scale, open = step.ret)
                    labelCx = (x1 + x2) / 2f
                    labelY = y - labelLay.size.height - 4f * scale
                }

                // A plate behind the caption: it sits above its arrow and would otherwise be crossed by
                // every lifeline it spans.
                if (step.label.isNotEmpty()) {
                    val padX = 4f * scale
                    val plate = Rect(
                        left = labelCx - labelLay.size.width / 2f - padX,
                        top = labelY - 1f * scale,
                        right = labelCx + labelLay.size.width / 2f + padX,
                        bottom = labelY + labelLay.size.height + 1f * scale,
                    )
                    drawRoundRect(
                        palette.surface,
                        plate.topLeft,
                        plate.size,
                        CornerRadius(3f * scale),
                    )
                    drawText(
                        labelLay,
                        color = if (step.ret) palette.mutedText else palette.text,
                        topLeft = Offset(labelCx - labelLay.size.width / 2f, labelY),
                    )
                }
            }

            // Participant cards last: they cap the lifelines they own.
            participants.forEachIndexed { i, name ->
                val lay = measurer.measure(name, nameStyle)
                val w = lay.size.width + CARD_PAD * scale * 2
                val left = layout.centers[i] - w / 2f
                val rect = Rect(left, 0f, left + w, cardH)
                drawRoundRect(palette.subtle, rect.topLeft, rect.size, CornerRadius(5f * scale))
                drawRoundRect(
                    palette.border.copy(alpha = 0.55f),
                    rect.topLeft,
                    rect.size,
                    CornerRadius(5f * scale),
                    style = Stroke(1f * scale),
                )
                drawText(
                    lay,
                    topLeft = Offset(
                        layout.centers[i] - lay.size.width / 2f,
                        cardH / 2f - lay.size.height / 2f,
                    ),
                )
            }
        }
    }
}

/** Pixel geometry for one diagram: where each lifeline sits, and how much room the whole thing needs. */
private class SequenceGeometry(val centers: FloatArray, val width: Float, val height: Float)

/**
 * Column centres, spaced so that every label fits between the two lifelines it crosses.
 *
 * A one-hop caption is the tight case — `AccountDb -> PlayerSession : Account` has only that gap to live
 * in — so adjacent columns are pushed apart by the widest single-hop label between them. Longer arrows get
 * that room for free.
 */
private fun sequenceLayout(
    participants: List<String>,
    steps: List<SeqStep>,
    scale: Float,
    measurer: TextMeasurer,
    nameStyle: TextStyle,
    labelStyle: TextStyle,
): SequenceGeometry {
    val halves = participants.map { measurer.measure(it, nameStyle).size.width / 2f + CARD_PAD * scale }
    val selfLabel = steps.filter { it.from == it.to }
        .groupBy { it.from }
        .mapValues { (_, group) ->
            group.maxOf { measurer.measure(it.label, labelStyle).size.width.toFloat() } + 24f * scale
        }

    // The widest one-hop caption crossing each gap.
    val gapLabel = FloatArray(maxOf(participants.size - 1, 0))
    val index = participants.withIndex().associate { (i, p) -> p to i }
    steps.forEach { st ->
        val a = index[st.from] ?: return@forEach
        val b = index[st.to] ?: return@forEach
        if (a == b || kotlin.math.abs(a - b) != 1) return@forEach
        val g = minOf(a, b)
        gapLabel[g] = maxOf(gapLabel[g], measurer.measure(st.label, labelStyle).size.width + 12f * scale)
    }

    val centers = FloatArray(participants.size)
    var x = GUTTER * scale + halves.firstOrNull().orZero()
    centers[0] = x
    for (i in 1 until participants.size) {
        val needed = maxOf(
            halves[i - 1] + halves[i] + COL_GAP * scale,
            gapLabel.getOrElse(i - 1) { 0f },
            // A self-call on the previous column draws its bracket and caption into this gap.
            selfLabel[participants[i - 1]] ?: 0f,
        )
        x += needed
        centers[i] = x
    }

    val lastSelf = selfLabel[participants.last()] ?: 0f
    val width = centers.last() + maxOf(halves.last(), lastSelf) + EDGE * scale
    val height = CARD_H * scale + EDGE * scale + STEP_H * scale * steps.size + EDGE * scale
    return SequenceGeometry(centers, width, height)
}

private fun Float?.orZero(): Float = this ?: 0f

/**
 * The head of an arrow. Filled for a call, open for a return — the convention that lets you tell the two
 * apart without reading the caption. [dir] is +1 pointing right, -1 pointing left.
 */
private fun DrawScope.arrowHead(tip: Offset, dir: Float, color: Color, scale: Float, open: Boolean) {
    val len = 6f * scale
    val half = 3.6f * scale
    if (open) {
        drawLine(color, tip, Offset(tip.x - len * dir, tip.y - half), 1.3f * scale)
        drawLine(color, tip, Offset(tip.x - len * dir, tip.y + half), 1.3f * scale)
        return
    }
    drawPath(
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - len * dir, tip.y - half)
            lineTo(tip.x - len * dir, tip.y + half)
            close()
        },
        color,
    )
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
