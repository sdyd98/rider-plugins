package com.example.logview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import javax.swing.JComponent

/** The assignable fields, in left-to-right order, with their Korean labels and chip colors. */
private val FIELD_LABELS = listOf("time" to "시간", "thread" to "스레드", "level" to "레벨", "message" to "메시지")

private fun fieldColor(field: String): Color = when (field) {
    "time" -> Color(0xFF4D9DE0)
    "thread" -> Color(0xFF9B86C9)
    "level" -> Color(0xFFE0883B)
    else -> Color(0xFF5AAE6B) // message
}

private fun buildLive(line: String, tokens: List<FormatToken>, assign: Map<Int, String>): LineFormat? =
    if (assign.isEmpty()) null else LineFormat.of(LineFormat.buildRegex(line, tokens, assign)).takeIf { it.valid }

/**
 * The ⚙ → "줄 형식" settings, in Compose/Jewel. Top: a **dropdown** of saved formats — pick one to apply.
 * Below: the **region picker** — click the tokens of a sample line to mark Time/Level/Thread/Message and
 * **the real log grid re-splits live** ([onPreview]); name it and **저장** to add it to the library. No
 * regex or mini-preview — the actual viewer IS the preview.
 */
fun createLineFormatSettings(sampleLines: List<String>, onPreview: (LineFormat?) -> Unit, onClose: () -> Unit): JComponent =
    JewelComposePanel {
        val palette = rememberLogPalette()
        val store = remember { LineFormatStore.getInstance() }
        var version by remember { mutableStateOf(0) } // bump to re-read the store after a change
        val library = remember(version) { store.library().map { it.name } }
        val activeName = remember(version) { store.activeName() }
        fun changed() {
            version++
            onPreview(store.active().firstOrNull()) // reflect the saved active format on the grid
        }

        Column(Modifier.width(560.dp).padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text("줄 형식 (Time · Level · Message 분리)", color = palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)

            if (library.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("적용 형식", color = palette.mutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Dropdown(
                        modifier = Modifier.weight(1f),
                        menuContent = {
                            library.forEach { name ->
                                selectableItem(selected = name == activeName, onClick = { store.activate(name); changed() }) {
                                    Text(name)
                                }
                            }
                        },
                    ) {
                        Text(activeName ?: "선택")
                    }
                    if (activeName != null) ToolButton("삭제", palette) { store.remove(activeName); changed() }
                }
            }

            val pickerLine = sampleLines.firstOrNull { it.isNotBlank() }
            if (pickerLine == null) {
                Text("로그가 비어 있어 영역을 지정할 수 없습니다.", color = palette.mutedText, fontSize = 11.sp)
            } else {
                RegionPicker(pickerLine, palette, onPreview) { name, pattern ->
                    store.save(name, pattern)
                    changed()
                }
            }

            // A separated footer with its own close button.
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ToolButton("창 닫기", palette, accent = true) { onClose() }
            }
        }
    }

/**
 * Click-to-mark region picker. Pick a field, click the tokens of [line]; the chips tint and **the real
 * log grid re-splits live** via [onPreview]. "메시지" needs only its start token — everything after it is
 * the message. Name it + [onSave] to add it to the library.
 */
@Composable
private fun RegionPicker(line: String, palette: LogPalette, onPreview: (LineFormat?) -> Unit, onSave: (name: String, pattern: String) -> Unit) {
    val tokens = remember(line) { LineFormat.tokenize(line) }
    var selectedField by remember { mutableStateOf("time") }
    var assign by remember(line) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val nameState = remember { TextFieldState(LineFormatStore.getInstance().nextDefaultName()) }

    val msgStart = assign.filterValues { it == "message" }.keys.minOrNull()
    fun effectiveField(i: Int): String? = if (msgStart != null && i >= msgStart) "message" else assign[i]

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            "새 포맷 만들기 — 필드를 고르고 토큰을 클릭하면 아래 실제 로그가 바로 나뉩니다 (메시지는 시작 토큰만)",
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
                            val next = when {
                                selectedField == "message" -> assign.filterValues { it != "message" } + (i to "message")
                                assign[i] == selectedField -> assign - i
                                else -> assign + (i to selectedField)
                            }
                            assign = next
                            onPreview(buildLive(line, tokens, next)) // re-split the real grid now
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("이름", color = palette.mutedText, fontSize = 11.sp)
            TextField(state = nameState, modifier = Modifier.weight(1f))
            ToolButton("저장", palette, accent = true) {
                val name = nameState.text.toString().trim()
                val fmt = buildLive(line, tokens, assign)
                if (fmt != null && name.isNotEmpty()) {
                    onSave(name, fmt.source)
                    assign = emptyMap() // clean slate for the next format
                    nameState.edit { replace(0, length, LineFormatStore.getInstance().nextDefaultName()) }
                }
            }
        }
    }
}
