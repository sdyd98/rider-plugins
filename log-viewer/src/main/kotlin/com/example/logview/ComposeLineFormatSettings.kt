package com.example.logview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.jetbrains.jewel.ui.component.TextField
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
 * The ⚙ → "줄 형식" settings, in Compose/Jewel. Top: a **library** of saved formats — click one to apply
 * it (the active one is marked). Below: the **region picker** — click the tokens of a sample line to mark
 * Time/Level/Thread/Message and the **preview updates live**; name it and **저장** to add it to the library
 * (and apply). No regex is ever shown. [onApply] re-reads the grid when the active format changes.
 */
fun createLineFormatSettings(sampleLines: List<String>, onApply: () -> Unit, onClose: () -> Unit): JComponent =
    JewelComposePanel {
        val palette = rememberLogPalette()
        val store = remember { LineFormatStore.getInstance() }
        var version by remember { mutableStateOf(0) } // bump to re-read the store after a change
        val library = remember(version) { store.library().map { it.name } }
        val activeName = remember(version) { store.activeName() }
        fun changed() {
            version++
            onApply()
        }

        Column(Modifier.width(580.dp).padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("줄 형식 (Time · Level · Message 분리)", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)

            if (library.isNotEmpty()) {
                Text("저장된 포맷 — 클릭해서 적용", color = palette.mutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    library.forEach { name ->
                        val isActive = name == activeName
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.sm))
                                .background(if (isActive) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
                                .padding(horizontal = Space.sm, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            Text(
                                (if (isActive) "● " else "○ ") + name,
                                color = if (isActive) palette.accent else palette.text,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f).clickable { store.activate(name); changed() },
                            )
                            if (isActive) Text("적용중", color = palette.mutedText, fontSize = 10.sp)
                            ToolButton("삭제", palette) { store.remove(name); changed() }
                        }
                    }
                    if (activeName != null) {
                        Text(
                            "형식 끄기 (자동 분석으로)",
                            color = palette.mutedText,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { store.activate(null); changed() }.padding(horizontal = Space.sm, vertical = 2.dp),
                        )
                    }
                }
            }

            val pickerLine = sampleLines.firstOrNull { it.isNotBlank() }
            if (pickerLine == null) {
                Text("로그가 비어 있어 영역을 지정할 수 없습니다.", color = palette.mutedText, fontSize = 11.sp)
            } else {
                RegionPicker(pickerLine, sampleLines, palette) { name, pattern ->
                    store.save(name, pattern)
                    changed()
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ToolButton("닫기", palette, accent = true) { onClose() }
            }
        }
    }

/**
 * Click-to-mark region picker with a LIVE preview. Pick a field, click the tokens of [line] that belong to
 * it; chips tint with the field color and the preview re-parses instantly. "메시지" needs only its start
 * token — everything after it is the message. Name it + [onSave] adds it to the library.
 */
@Composable
private fun RegionPicker(line: String, sampleLines: List<String>, palette: LogPalette, onSave: (name: String, pattern: String) -> Unit) {
    val tokens = remember(line) { LineFormat.tokenize(line) }
    var selectedField by remember { mutableStateOf("time") }
    var assign by remember(line) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val nameState = remember { TextFieldState(LineFormatStore.getInstance().nextDefaultName()) }

    val msgStart = assign.filterValues { it == "message" }.keys.minOrNull()
    fun effectiveField(i: Int): String? = if (msgStart != null && i >= msgStart) "message" else assign[i]

    // Live format from the current assignments (null until something is assigned) → drives the preview now.
    val liveFormat = remember(assign, line) {
        if (assign.isEmpty()) null else LineFormat.of(LineFormat.buildRegex(line, tokens, assign)).takeIf { it.valid }
    }
    val previewFormats = if (liveFormat != null) listOf(liveFormat) else LineFormatStore.getInstance().active()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            "새 포맷 만들기 — 필드를 고르고 토큰 클릭 (메시지는 시작 토큰만 클릭하면 끝까지)",
            color = palette.mutedText,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tokens.forEachIndexed { i, tok ->
                val fld = effectiveField(i)
                val col = fld?.let { fieldColor(it) }
                Text(
                    tok.text,
                    color = col ?: palette.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(Radii.sm))
                        .background((col ?: palette.text).copy(alpha = if (fld != null) 0.22f else 0.05f))
                        .clickable {
                            assign = when {
                                selectedField == "message" -> assign.filterValues { it != "message" } + (i to "message")
                                assign[i] == selectedField -> assign - i
                                else -> assign + (i to selectedField)
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        Text("미리보기 (현재 로그 앞부분)", color = palette.mutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (sampleLines.isEmpty()) {
                Text("(로그가 비어 있어 미리보기 없음)", color = palette.mutedText, fontSize = 11.sp)
            } else {
                sampleLines.forEach { PreviewRow(it, previewFormats, palette) }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("이름", color = palette.mutedText, fontSize = 11.sp)
            TextField(state = nameState, modifier = Modifier.weight(1f))
            ToolButton("저장", palette, accent = true) {
                val name = nameState.text.toString().trim()
                if (liveFormat != null && name.isNotEmpty()) {
                    onSave(name, liveFormat.source)
                    assign = emptyMap() // clean slate for the next format; preview falls back to the active one
                    nameState.edit { replace(0, length, LineFormatStore.getInstance().nextDefaultName()) }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(line: String, formats: List<LineFormat>, palette: LogPalette) {
    // Did a format actually match this line? If not, show the ORIGINAL text so it's obvious which lines the
    // format doesn't cover — rather than a heuristic guess.
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
