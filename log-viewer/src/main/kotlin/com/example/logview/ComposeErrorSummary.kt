package com.example.logview

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent

private val summaryHoverTint = Color(0xFF3574F0).copy(alpha = 0.16f)
private const val MAX_GROUPS_SHOWN = 50

/**
 * The 에러 요약 popup body: repeated-error groups (see [ErrorSummary]) sorted by count, each row
 * clickable to jump to the group's most recent occurrence. Compose/Jewel, matching the help popup.
 */
fun createErrorSummaryPanel(
    groups: List<ErrorSummary.Group>,
    totalErrors: Int,
    onJump: (modelRow: Int) -> Unit,
): JComponent = JewelComposePanel {
    Column(Modifier.width(620.dp).padding(16.dp)) {
        Text("에러 요약 — 총 ${"%,d".format(totalErrors)}건 · ${"%,d".format(groups.size)}종류", fontWeight = FontWeight.Bold)
        Spacer(Modifier.padding(top = 10.dp))
        if (groups.isEmpty()) {
            Text("표시할 ERROR 줄이 없습니다.", fontSize = 12.sp)
        } else {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                groups.take(MAX_GROUPS_SHOWN).forEach { g -> summaryRow(g, onJump) }
                if (groups.size > MAX_GROUPS_SHOWN) {
                    Text(
                        "… 외 ${groups.size - MAX_GROUPS_SHOWN}종류 (상위 ${MAX_GROUPS_SHOWN}개만 표시)",
                        Modifier.padding(8.dp),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun summaryRow(g: ErrorSummary.Group, onJump: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) summaryHoverTint else Color.Transparent)
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
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .hoverable(interaction)
            .clickable { onJump(g.lastModelRow) } // jump to the MOST RECENT occurrence
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text("${"%,d".format(g.count)}×", Modifier.width(72.dp), fontWeight = FontWeight.Bold)
        Column {
            Text(g.pattern, maxLines = 2)
            Text(range, fontSize = 11.sp)
        }
    }
}
