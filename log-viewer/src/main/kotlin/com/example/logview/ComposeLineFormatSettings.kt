package com.example.logview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import org.jetbrains.jewel.ui.component.TextArea
import javax.swing.JComponent

/**
 * The ⚙ → "줄 형식" settings, in Compose/Jewel. The user edits the line-format list (one template or
 * regex per line) in a text area, and a **live preview** below splits the current log's first lines into
 * Time / Level / Message with whatever is typed — so a wrong pattern is obvious immediately. The owning
 * [TextFieldState] is held by the caller, which reads it back and saves when the popup closes.
 */
fun createLineFormatSettings(state: TextFieldState, sampleLines: List<String>): JComponent = JewelComposePanel {
    val palette = rememberLogPalette()
    Column(Modifier.width(560.dp).padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("줄 형식 (Time · Level · Message 분리)", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(
            "한 줄에 하나씩. 템플릿 예: %{time} [%{thread}] (%{level}) %{message}   ·   또는 정규식 (?<level>\\w+)…  " +
                "위에서부터 시도하고, 안 맞으면 자동 분석으로 폴백합니다.",
            color = palette.mutedText,
            fontSize = 11.sp,
        )
        TextArea(
            state = state,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )

        Text("미리보기 (현재 로그 앞부분)", color = palette.mutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        // Recompile the formats whenever the text changes; preview reflects the real parse pipeline.
        val formats by remember {
            derivedStateOf {
                state.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                    .map { LineFormat.of(it) }.filter { it.valid }.toList()
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (sampleLines.isEmpty()) {
                Text("(로그가 비어 있어 미리보기 없음)", color = palette.mutedText, fontSize = 11.sp)
            } else {
                sampleLines.forEach { PreviewRow(it, formats, palette) }
            }
        }
    }
}

@Composable
private fun PreviewRow(line: String, formats: List<LineFormat>, palette: LogPalette) {
    val parsed = remember(line, formats) { LogParser.parse(line, formats) }
    val time = if (parsed.timestampMillis != LogLine.NO_TIME) LogStructure.formatTime(parsed.timestampMillis) else ""
    val level = if (parsed.level != LogLevel.OTHER) parsed.level.label else ""
    val msg = line.substring(parsed.messageStart.coerceIn(0, line.length)).trimStart()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.sm)).background(palette.surfaceHover.copy(alpha = 0.4f))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(time, color = palette.mutedText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(96.dp))
        Text(level, color = palette.levelColor[parsed.level] ?: palette.mutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
        Text(msg, color = palette.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
