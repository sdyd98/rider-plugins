package com.example.logview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import javax.swing.JComponent

/** Live state the log viewer's premium Compose chrome (toolbar + severity strip + status bar) renders. */
data class LogChromeData(
    val status: String = " ",
    val visible: Int = 0,
    val total: Int = 0,
    val filterActive: Boolean = false,
    val regexValid: Boolean = true,
    val levelCounts: Map<LogLevel, Int> = emptyMap(),
    val enabledLevels: Set<LogLevel> = (LogLevel.REAL + LogLevel.OTHER).toSet(),
    val following: Boolean = false,
    val streaming: Boolean = false,
    val useRegex: Boolean = true,
    val caseSensitive: Boolean = false,
    val cursor: String = "—",
    val source: String = "",
)

private fun Int.commas() = "%,d".format(this)

/** Callbacks the filter bar invokes. Grouped so the panel wiring stays readable. */
class LogChromeActions(
    val onQueryChanged: () -> Unit,
    val onEnter: () -> Unit,
    val onToggleLevel: (LogLevel) -> Unit,
    val onAllLevels: () -> Unit,
    val onErrorsOnly: () -> Unit,
    val onToggleFollow: () -> Unit,
    val onToggleRegex: () -> Unit,
    val onToggleCase: () -> Unit,
    val onClear: () -> Unit,
    val onOpenRules: () -> Unit,
    val onDisplayMenu: () -> Unit,
    val onHelp: () -> Unit,
)

/**
 * The top chrome: a **command/query bar** (LIVE badge · search field with regex/case toggles · clear ·
 * match count · rules · help) over a **severity strip** of colored count pills that double as filters.
 * Synthesized from three premium directions (observability command bar + IDE-native restraint +
 * power-console compactness). The Swing grid stays Swing; this is the Jewel skin around it.
 */
fun createLogFilterBar(
    query: TextFieldState,
    focusRequester: FocusRequester,
    chrome: State<LogChromeData>,
    actions: LogChromeActions,
): JComponent = JewelComposePanel {
    LaunchedEffect(Unit) { snapshotFlow { query.text.toString() }.collect { actions.onQueryChanged() } }
    val data = chrome.value
    val p = rememberLogPalette()
    Column(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = Space.md, vertical = Space.sm)) {
        // --- Row 1: command / query bar ---
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LiveBadge(live = data.following, palette = p, onToggle = actions.onToggleFollow)

            Box(Modifier.weight(1f)) {
                TextField(
                    state = query,
                    outline = if (data.regexValid) Outline.None else Outline.Error,
                    placeholder = { Text(if (data.useRegex) "정규식으로 필터…  (Enter로 로그에 포커스)" else "텍스트로 필터…  (Enter로 로그에 포커스)") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { actions.onEnter(); true } else false
                    },
                )
            }

            ToolButton(".*", palette = p, accent = data.useRegex) { actions.onToggleRegex() }
            ToolButton("Aa", palette = p, accent = data.caseSensitive) { actions.onToggleCase() }
            if (query.text.isNotEmpty() || data.filterActive) ToolButton("✕", palette = p) { actions.onClear() }

            // match count (accent when a text query narrows the view)
            val matches = if (data.filterActive) "${data.visible.commas()} / ${data.total.commas()}" else "${data.total.commas()}줄"
            Text(matches, color = if (data.filterActive) p.accent else p.mutedText, fontSize = 11.sp)

            Spacer(Modifier.width(Space.xs))
            ToolButton("규칙", palette = p) { actions.onOpenRules() }
            ToolButton("⚙", palette = p) { actions.onDisplayMenu() }
            ToolButton("?", palette = p) { actions.onHelp() }
        }

        Spacer(Modifier.height(Space.sm))

        // --- Row 2: severity strip (colored count pills = filters) ---
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            val allOn = LogLevel.REAL.all { it in data.enabledLevels }
            ToolButton("전체", palette = p, accent = allOn) { actions.onAllLevels() }
            Spacer(Modifier.width(Space.xxs))
            LogLevel.REAL.forEach { level ->
                LevelPill(
                    label = level.label,
                    count = data.levelCounts[level] ?: 0,
                    color = p.levelColor[level] ?: p.mutedText,
                    active = level in data.enabledLevels,
                    palette = p,
                    onClick = { actions.onToggleLevel(level) },
                )
            }
            Spacer(Modifier.width(Space.sm))
            ToolButton("에러만", palette = p) { actions.onErrorsOnly() }
        }
    }
}

/** Bottom status bar — premium segmented: cursor · counts · live · source, with subtle separators. */
fun createLogStatusBar(chrome: State<LogChromeData>): JComponent = JewelComposePanel {
    val data = chrome.value
    val p = rememberLogPalette()
    Row(
        Modifier.fillMaxWidth().background(p.surface).padding(horizontal = Space.md, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (data.streaming) {
            SeverityDot(p.accent, 7.dp)
            Text("로딩 중… ${data.total.commas()}행", color = p.mutedText, fontSize = 11.sp)
        } else if (data.status.isNotBlank()) {
            // A read failure (set by setStatusError) — show it in place of the normal segments.
            val errColor = p.levelColor[LogLevel.ERROR] ?: p.text
            SeverityDot(errColor, 7.dp)
            Text(data.status, color = errColor, fontSize = 11.sp)
        } else {
            Text(data.cursor, color = p.text, fontSize = 11.sp)
            Separator(p)
            val counts = if (data.visible == data.total) "${data.total.commas()}줄" else "${data.visible.commas()} / ${data.total.commas()}줄"
            Text(counts, color = p.mutedText, fontSize = 11.sp)
            Separator(p)
            if (data.following) {
                SeverityDot(p.levelColor[LogLevel.INFO] ?: p.accent, 7.dp)
                Text("LIVE", color = p.levelColor[LogLevel.INFO] ?: p.accent, fontSize = 11.sp)
            } else {
                Text("일시정지", color = p.mutedText, fontSize = 11.sp)
            }
            if (data.source.isNotEmpty()) {
                Separator(p)
                Text(data.source, color = p.mutedText, fontSize = 11.sp)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Separator(p: LogPalette) {
    Text("·", color = p.border, fontSize = 11.sp)
}
