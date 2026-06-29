package com.example.logview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent

/** Snapshot of the selected line for the detail drawer (computed on the EDT from a [LogLine]). */
private data class LogDetailData(
    val lineNumber: Int,
    val timeText: String,
    val level: LogLevel,
    val message: String,
    val json: String?,
)

/**
 * The right-hand **line detail** drawer, in Compose/Jewel: a level badge + line/time meta, the full
 * line, and (when present) its JSON payload pretty-printed — each in a copyable card. Reuses the log
 * viewer's design tokens ([Space]/[Radii]/[LogPalette]) so it matches the rest of the chrome.
 */
class ComposeLogDetail {

    private val data = mutableStateOf<LogDetailData?>(null)

    /** The Swing component to drop into the splitter. */
    val component: JComponent = JewelComposePanel { DetailUi(data.value) }

    fun show(line: LogLine?) {
        data.value = line?.let {
            val json = LogStructure.extractJson(it.display)?.let { j -> runCatching { LogStructure.prettyJson(j) }.getOrNull() }
            LogDetailData(it.lineNumber, it.timeText, it.level, it.display, json)
        }
    }
}

@Composable
private fun DetailUi(data: LogDetailData?) {
    val palette = rememberLogPalette()
    if (data == null) {
        EmptyState("◧", "줄을 선택하세요", "왼쪽 목록에서 로그 줄을 선택하면 여기에 상세가 표시됩니다.")
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // Header: level badge + line number + timestamp.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LevelBadge(data.level, palette)
            Text("행 ${"%,d".format(data.lineNumber)}", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (data.timeText.isNotEmpty()) {
                Text("·", color = palette.mutedText, fontSize = 12.sp)
                Text(data.timeText, color = palette.mutedText, fontSize = 12.sp)
            }
        }

        Section("메시지", data.message, palette) {
            SelectionContainer {
                Text(data.message, color = palette.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        if (data.json != null) {
            Section("JSON", data.json, palette) {
                SelectionContainer {
                    Text(data.json, color = palette.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

/** A compact colored level badge (dot + label tinted with the level color). */
@Composable
private fun LevelBadge(level: LogLevel, palette: LogPalette) {
    val color = palette.levelColor[level] ?: palette.mutedText
    val label = if (level == LogLevel.OTHER) "LOG" else level.label
    Row(
        Modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(color.copy(alpha = 0.16f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), RoundedCornerShape(Radii.sm))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        SeverityDot(color, 7.dp)
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

/** A titled card with a "복사" action; [copyText] is placed on the clipboard when clicked. */
@Composable
private fun Section(title: String, copyText: String?, palette: LogPalette, content: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = palette.mutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            if (copyText != null) ToolButton("복사", palette) { clipboard.setText(AnnotatedString(copyText)) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.sm))
                .background(palette.text.copy(alpha = 0.04f))
                .border(BorderStroke(1.dp, palette.border), RoundedCornerShape(Radii.sm))
                .padding(Space.sm),
        ) { content() }
        Spacer(Modifier.height(Space.xxs))
    }
}
