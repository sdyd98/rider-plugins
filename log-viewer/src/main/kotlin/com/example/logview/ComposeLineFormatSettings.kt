package com.example.logview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** The four assignable fields, in left-to-right order, with their Korean labels and chip colors. */
private val FIELD_LABELS = listOf("time" to "시간", "level" to "레벨", "thread" to "스레드", "message" to "메시지")

private fun fieldColor(field: String): Color = when (field) {
    "time" -> Color(0xFF4D9DE0)
    "level" -> Color(0xFFE0883B)
    "thread" -> Color(0xFF9B86C9)
    else -> Color(0xFF5AAE6B) // message
}

/**
 * The ⚙ → "줄 형식" settings, in Compose/Jewel. Two ways to define the split: the visual **region picker**
 * (click tokens of a sample line to mark Time/Level/Message → generates a regex) and a text area (one
 * template/regex per line). A **live preview** below splits the current log's first lines with whatever is
 * set, so a wrong pattern is obvious immediately. The owning [TextFieldState] is held by the caller, which
 * reads it back and saves when the popup closes.
 */
fun createLineFormatSettings(state: TextFieldState, sampleLines: List<String>): JComponent = JewelComposePanel {
    val palette = rememberLogPalette()
    Column(Modifier.width(560.dp).padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text("줄 형식 (Time · Level · Message 분리)", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)

        // Visual region picker — the easy path: click tokens instead of typing a template/regex.
        val pickerLine = sampleLines.firstOrNull { it.isNotBlank() }
        if (pickerLine != null) RegionPicker(pickerLine, state, palette)

        Text(
            "또는 직접 입력 (한 줄에 하나): 템플릿 %{time} [%{thread}] (%{level}) %{message}  ·  또는 정규식 (?<level>\\w+)…",
            color = palette.mutedText,
            fontSize = 11.sp,
        )
        TextArea(
            state = state,
            modifier = Modifier.fillMaxWidth().height(80.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )

        Text("미리보기 (현재 로그 앞부분)", color = palette.mutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        // Read state.text directly so this recomposes on EVERY keystroke → the preview is real-time.
        val text = state.text.toString()
        val formats = remember(text) {
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .map { LineFormat.of(it) }.filter { it.valid }.toList()
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

/**
 * Click-to-mark region picker: pick a field, then click the tokens of [line] that belong to it (chips tint
 * with the field color). "형식 만들기" turns the marks into a named-capture regex via [LineFormat.buildRegex]
 * and writes it into [state] (which feeds the preview + is saved on close).
 */
@Composable
private fun RegionPicker(line: String, state: TextFieldState, palette: LogPalette) {
    val tokens = remember(line) { LineFormat.tokenize(line) }
    var selectedField by remember { mutableStateOf("time") }
    var assign by remember(line) { mutableStateOf<Map<Int, String>>(emptyMap()) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            "영역 지정 — 필드를 고르고 샘플 줄의 토큰을 클릭하세요 (같은 필드 여러 토큰 OK · 메시지는 시작 토큰)",
            color = palette.mutedText,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        // Field selector chips.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            FIELD_LABELS.forEach { (f, label) ->
                val active = selectedField == f
                val col = fieldColor(f)
                Text(
                    label,
                    color = if (active) Color.White else col,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(Radii.sm))
                        .background(if (active) col else col.copy(alpha = 0.15f))
                        .clickable { selectedField = f }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        // The sample line as clickable token chips (horizontally scrollable for long lines).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tokens.forEachIndexed { i, tok ->
                val fld = assign[i]
                val col = fld?.let { fieldColor(it) }
                Text(
                    tok.text,
                    color = col ?: palette.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(Radii.sm))
                        .background((col ?: palette.text).copy(alpha = if (fld != null) 0.22f else 0.05f))
                        .clickable { assign = if (assign[i] == selectedField) assign - i else assign + (i to selectedField) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            ToolButton("형식 만들기", palette, accent = true) {
                if (assign.isNotEmpty()) {
                    state.edit { replace(0, length, LineFormat.buildRegex(line, tokens, assign)) }
                }
            }
            ToolButton("지우기", palette) { assign = emptyMap() }
        }
    }
}

@Composable
private fun PreviewRow(line: String, formats: List<LineFormat>, palette: LogPalette) {
    // Did a user format actually match this line? If not (none / all cleared), show the ORIGINAL text so
    // it's obvious which lines the format doesn't cover — rather than a heuristic guess.
    val matched = remember(line, formats) { formats.any { it.apply(line) != null } }
    if (!matched) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.sm))
                .background(palette.mutedText.copy(alpha = 0.06f)).padding(horizontal = Space.sm, vertical = 3.dp),
        ) {
            Text(
                line,
                color = palette.mutedText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
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
