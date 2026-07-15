package com.example.xlsx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/** One row of a jump popup: [primary] is what you read/filter, [secondary] is grayed context. */
class JumpItem(val index: Int, val primary: String, val secondary: String = "", val current: Boolean = false)

/**
 * The MODERN jump popup shared by the sheet jump (`Alt+S`) and the column jump (`Alt+\`): a Compose
 * (Jewel) panel — matching the rest of the chrome — with an auto-focused search field, live
 * substring filtering with the **matched part highlighted**, full keyboard navigation (↑/↓ move,
 * Enter chooses, Esc closes via the popup), the current item marked with an accent dot, and a
 * virtualized list ([LazyColumn]) so hundreds of columns cost nothing.
 */
fun showJumpPopup(owner: JComponent, title: String, items: List<JumpItem>, onChosen: (JumpItem) -> Unit) {
    var popupRef: JBPopup? = null
    val content = createJumpPanel(items) { item ->
        popupRef?.closeOk(null)
        onChosen(item)
    }
    val popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(content, content)
        .setTitle(title)
        .setMovable(true)
        .setRequestFocus(true)
        .createPopup()
    popupRef = popup
    // Center over the VISIBLE grid area (the scroll pane), not the table itself: a JTable's size is
    // its full content height (rows × rowHeight), so centering over it puts the popup tens of
    // thousands of px down for a large sheet and the platform clamps it to a screen edge — that was
    // the "awkward floating" position. The scroll pane's bounds are the viewport, so its center is
    // the visible center.
    popup.showInCenterOf(centerAnchor(owner))
}

/** The enclosing scroll pane (its bounds = the visible viewport), else the owner itself. */
private fun centerAnchor(owner: JComponent): JComponent =
    (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, owner) as? JComponent) ?: owner

private const val JUMP_ROW_H = 26 // px per list row (pre-scale), for sizing the popup
private const val JUMP_MAX_ROWS = 12 // rows shown before the list scrolls

private fun createJumpPanel(items: List<JumpItem>, onChosen: (JumpItem) -> Unit): JComponent {
    val panel = JewelComposePanel { JumpPanelContent(items, onChosen) }
    // JewelComposePanel does NOT report its Compose content height to Swing, so without an explicit
    // preferredSize the popup shows at ~0 size and showInCenterOf mis-centers it (content then grows
    // down/right of centre). Pin a real size derived from the row count so centering is correct.
    val rows = items.size.coerceIn(1, JUMP_MAX_ROWS)
    panel.preferredSize = JBUI.size(360, 60 + rows * JUMP_ROW_H)
    return panel
}

@androidx.compose.runtime.Composable
private fun JumpPanelContent(items: List<JumpItem>, onChosen: (JumpItem) -> Unit) {
    val accent = Color(GRID_ACCENT.rgb) // theme-resolved at composition; the popup's lifetime is short
    val secondaryFg = Color(0xFF888888)
    val query = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    var selected by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    val q = query.text.toString().trim()
    val visible = remember(q) {
        if (q.isEmpty()) items
        else items.filter { it.primary.contains(q, true) || it.secondary.contains(q, true) }
    }
    // Effects run after composition, so `selected` is never written during composition.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(q) { selected = 0 } // a new filter snaps to the first match
    LaunchedEffect(selected, visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(selected.coerceIn(0, visible.lastIndex))
    }

    Column(Modifier.fillMaxSize().padding(10.dp)) { // fills the panel's pinned preferredSize
        TextField(
            state = query,
            placeholder = { Text("Type to filter…") },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { if (visible.isNotEmpty()) selected = (selected + 1) % visible.size; true }
                    Key.DirectionUp -> { if (visible.isNotEmpty()) selected = (selected - 1 + visible.size) % visible.size; true }
                    Key.Enter -> { visible.getOrNull(selected)?.let(onChosen); true }
                    else -> false // Esc falls through to the popup's own cancel handling
                }
            },
        )
        Spacer(Modifier.height(6.dp))
        if (visible.isEmpty()) {
            Text("No matches", Modifier.padding(6.dp), color = secondaryFg)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                itemsIndexed(visible) { i, item ->
                    val isSel = i == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSel) accent.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { onChosen(item) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(highlightMatch(item.primary, q, accent), Modifier.weight(1f), maxLines = 1)
                        if (item.secondary.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(highlightMatch(item.secondary, q, accent, base = secondaryFg), maxLines = 1)
                        }
                        if (item.current) {
                            Spacer(Modifier.width(8.dp))
                            Text("●", color = accent)
                        }
                    }
                }
            }
        }
    }
}

/** Bold+accent the first case-insensitive occurrence of [q]; [base] tints the rest (for grayed context). */
private fun highlightMatch(text: String, q: String, accent: Color, base: Color? = null): AnnotatedString {
    val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    return buildAnnotatedString {
        base?.let { pushStyle(SpanStyle(color = it)) }
        if (at < 0) {
            append(text)
        } else {
            append(text.substring(0, at))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent))
            append(text.substring(at, at + q.length))
            pop()
            append(text.substring(at + q.length))
        }
        base?.let { pop() }
    }
}
