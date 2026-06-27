package com.example.xlsx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import javax.swing.JComponent

/** A removable active column-filter chip ([count] = how many values are kept for that column). */
data class FilterChip(val col: Int, val label: String, val count: Int)

/** Everything the Compose chrome (filter bar + status bar) shows for the active sheet. */
data class ChromeData(
    val status: String = " ",
    val visible: Int = 0,
    val total: Int = 0,
    val filterActive: Boolean = false,
    val regexValid: Boolean = true,
    val chips: List<FilterChip> = emptyList(),
    val streaming: Boolean = false,
)

private fun Int.commas() = "%,d".format(this)

/**
 * Compose (Jewel) "chrome" around the Swing grid: the top **filter bar** and the bottom **status
 * bar**, one shared pair per editor (see XlsxFileEditor). The grid stays Swing for large-data
 * performance. [chrome] is the live state of the active sheet.
 */
fun createFilterBar(
    query: TextFieldState,
    focusRequester: FocusRequester,
    chrome: State<ChromeData>,
    onQueryChanged: () -> Unit,
    onEnter: () -> Unit,
    onClearChip: (Int) -> Unit,
    onClearAll: () -> Unit,
): JComponent = JewelComposePanel {
    // Mirror Swing's debounced filter: notify on every text change (the editor debounces).
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString() }.collect { onQueryChanged() }
    }
    val data = chrome.value
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filter:")
            Spacer(Modifier.width(8.dp))
            TextField(
                state = query,
                // Red outline when the query isn't a valid regex (it still filters as literal text).
                outline = if (data.regexValid) Outline.None else Outline.Error,
                placeholder = { Text("Filter rows (regex) across all columns…") },
                modifier = Modifier.weight(1f).focusRequester(focusRequester).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        onEnter()
                        true
                    } else {
                        false
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            // Live match count: visible/total while filtering, else just the row count.
            Text(
                if (data.filterActive) "${data.visible.commas()} / ${data.total.commas()}"
                else "${data.total.commas()} rows",
            )
            // Clear every filter (text query + all column filters) at once.
            if (data.filterActive) {
                Spacer(Modifier.width(10.dp))
                Text("✕ 해제", Modifier.clickable { onClearAll() })
            }
        }
        // Active per-column filters as removable chips.
        if (data.chips.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                data.chips.forEach { chip ->
                    Row(
                        Modifier
                            .background(Color(0x33808080), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${chip.label} (${chip.count})")
                        Spacer(Modifier.width(5.dp))
                        Text("✕", Modifier.clickable { onClearChip(chip.col) })
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

fun createStatusBar(chrome: State<ChromeData>): JComponent = JewelComposePanel {
    val data = chrome.value
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Text-only progress while streaming — the counting row total is the feedback (no spinner; the
        // file-open placeholder is already just a "Loading…" label, so a circle here is redundant).
        if (data.streaming) Text("로딩 중… ${data.visible.commas()}행") else Text(data.status)
    }
}
