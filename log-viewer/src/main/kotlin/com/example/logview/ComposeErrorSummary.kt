package com.example.logview

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent

private const val MAX_GROUPS_SHOWN = 50

/**
 * The 에러 요약 popup body: repeated-error groups (see [ErrorSummary]) sorted by count. Each row
 * shows the count in the ERROR color with a proportional mini-bar (share of the largest group),
 * the normalized pattern in monospace, and a muted first–last range line; clicking jumps to the
 * group's most recent occurrence. Built from the LogUi kit (palette / Space / Radii / EmptyState)
 * so it matches the rest of the chrome in both themes.
 */
fun createErrorSummaryPanel(
    groups: List<ErrorSummary.Group>,
    totalErrors: Int,
    onJump: (modelRow: Int) -> Unit,
): JComponent = JewelComposePanel {
    val p = rememberLogPalette()
    val errColor = p.levelColor[LogLevel.ERROR] ?: p.accent
    Column(Modifier.width(640.dp).padding(Space.lg)) {
        // Header: severity dot + title, with the totals as a muted trailing segment.
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeverityDot(errColor, 8.dp)
            Spacer(Modifier.width(Space.sm))
            Text("에러 요약", color = p.text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(Space.sm))
            Text(
                "총 ${"%,d".format(totalErrors)}건 · ${"%,d".format(groups.size)}종류",
                color = p.mutedText,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border.copy(alpha = 0.6f)))
        Spacer(Modifier.height(Space.sm))

        if (groups.isEmpty()) {
            EmptyState("✓", "ERROR 없음", "이 로그에는 ERROR 레벨 줄이 없습니다")
        } else {
            val maxCount = groups.first().count // sorted descending — the 100% reference for the bars
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                groups.take(MAX_GROUPS_SHOWN).forEach { g -> SummaryRow(g, maxCount, errColor, p, onJump) }
                if (groups.size > MAX_GROUPS_SHOWN) {
                    Text(
                        "… 외 ${"%,d".format(groups.size - MAX_GROUPS_SHOWN)}종류 (상위 ${MAX_GROUPS_SHOWN}개만 표시)",
                        Modifier.padding(Space.sm),
                        color = p.mutedText,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    g: ErrorSummary.Group,
    maxCount: Int,
    errColor: Color,
    p: LogPalette,
    onJump: (Int) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) p.surfaceHover else Color.Transparent)
    val range = buildString {
        append("행 ${"%,d".format(g.firstLine)}–${"%,d".format(g.lastLine)}")
        if (g.firstTime != LogLine.NO_TIME) {
            append(" · ${LogStructure.formatTime(g.firstTime)}")
            if (g.lastTime != g.firstTime) append(" ~ ${LogStructure.formatTime(g.lastTime)}")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(background)
            .hoverable(interaction)
            .clickable { onJump(g.lastModelRow) } // jump to the MOST RECENT occurrence
            .padding(horizontal = Space.sm, vertical = Space.xs + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Count column: the number in the ERROR color over a mini-bar showing this group's share
        // of the largest one — the eye ranks groups without reading a single digit.
        Column(Modifier.width(76.dp)) {
            Text("${"%,d".format(g.count)}×", color = errColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(Space.xxs))
            val fraction = g.count.toFloat() / maxCount
            Box(
                Modifier
                    .width((60 * fraction).coerceAtLeast(3f).dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(errColor.copy(alpha = 0.45f)),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                g.pattern,
                color = p.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(range, color = p.mutedText, fontSize = 11.sp)
        }
        if (hovered) {
            Spacer(Modifier.width(Space.sm))
            Text("이동 ›", color = p.accent, fontSize = 11.sp)
        }
    }
}
