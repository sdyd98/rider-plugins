package com.example.logview

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent

/** One display option: a title, a one-line hint, the current value, and a setter. */
class DisplayToggle(val title: String, val subtitle: String, val initial: Boolean, val onToggle: (Boolean) -> Unit)

/** A non-toggle row: a title + current value that opens a chooser (e.g. encoding) when clicked. */
class DisplayChoice(val title: String, val value: String, val onClick: () -> Unit)

/**
 * The ⚙ "표시 옵션" popup, in Compose/Jewel: a list of toggle rows (title + hint + animated switch) plus
 * optional choice rows (title + value, click to open a chooser), each applying immediately and keeping
 * the popup open. Replaces the old Swing `JCheckBoxMenuItem` menu so it matches the viewer's chrome.
 */
fun createDisplayOptionsPanel(
    toggles: List<DisplayToggle>,
    choices: List<DisplayChoice> = emptyList(),
): JComponent = JewelComposePanel {
    val palette = rememberLogPalette()
    Column(Modifier.width(290.dp).padding(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
        Text(
            "표시 옵션",
            color = palette.mutedText,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = Space.sm, top = Space.xs, bottom = Space.xs),
        )
        toggles.forEach { ToggleRow(it, palette) }
        choices.forEach { ChoiceRow(it, palette) }
    }
}

@Composable
private fun ChoiceRow(choice: DisplayChoice, palette: LogPalette) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) palette.surfaceHover else Color.Transparent)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(bg)
            .hoverable(interaction)
            .clickable { choice.onClick() }
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(choice.title, color = palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("${choice.value}  ▾", color = palette.mutedText, fontSize = 12.sp)
    }
}

@Composable
private fun ToggleRow(toggle: DisplayToggle, palette: LogPalette) {
    var checked by remember { mutableStateOf(toggle.initial) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) palette.surfaceHover else Color.Transparent)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(bg)
            .hoverable(interaction)
            .clickable { checked = !checked; toggle.onToggle(checked) }
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(toggle.title, color = palette.text, fontSize = 13.sp)
            Text(toggle.subtitle, color = palette.mutedText, fontSize = 11.sp)
        }
        Switch(checked, palette)
    }
}

/** A compact animated on/off switch (track tints to accent, knob slides). */
@Composable
private fun RowScope.Switch(checked: Boolean, palette: LogPalette) {
    val knobX by animateDpAsState(if (checked) 16.dp else 2.dp)
    val track by animateColorAsState(if (checked) palette.accent else palette.mutedText.copy(alpha = 0.35f))
    Box(Modifier.width(32.dp).height(18.dp).clip(RoundedCornerShape(Radii.pill)).background(track)) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = knobX)
                .size(14.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
