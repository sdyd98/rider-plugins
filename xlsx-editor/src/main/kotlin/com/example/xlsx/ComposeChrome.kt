package com.example.xlsx

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import javax.swing.JComponent

/**
 * Compose (Jewel) "chrome" around the Swing grid: the top **filter bar** and the bottom **status
 * bar**. The grid itself stays Swing (`JBTable`) for large-data performance; only these surrounding
 * strips are Compose. State is shared with [SheetPanel] — a [TextFieldState] for the query and
 * [State]s for the enabled flag + status line — so the existing filter/status logic is reused as-is.
 */
fun createFilterBar(
    query: TextFieldState,
    focusRequester: FocusRequester,
    onQueryChanged: () -> Unit,
    onEnter: () -> Unit,
): JComponent = JewelComposePanel {
    // Mirror Swing's debounced filter: notify on every text change (the editor debounces).
    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString() }.collect { onQueryChanged() }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Filter:")
        Spacer(Modifier.width(8.dp))
        TextField(
            state = query,
            placeholder = { Text("Filter rows (regex) across all columns…") },
            modifier = Modifier.weight(1f).focusRequester(focusRequester).onPreviewKeyEvent { e ->
                // Enter applies immediately and returns focus to the grid (handled by onEnter).
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                    onEnter()
                    true
                } else {
                    false
                }
            },
        )
    }
}

fun createStatusBar(text: State<String>): JComponent = JewelComposePanel {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text.value)
    }
}
