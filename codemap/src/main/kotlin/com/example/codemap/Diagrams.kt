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
