package com.example.logview

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import org.jetbrains.jewel.ui.component.Text

/**
 * Design system for the log viewer's Compose/Jewel chrome — spacing, radii, a theme-aware palette, and
 * a kit of premium building blocks (severity dot, level pill, live badge, tool button, empty state).
 * The arrangement of these into the toolbar/status bar lives in [LogChrome]; this file is the
 * direction-agnostic visual language (so the look stays consistent across the whole plugin).
 */
object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}

object Radii {
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val pill: Dp = 100.dp
}

/** Theme-aware colors snapshotted from the IDE LAF (resolved per composition → correct for the theme). */
class LogPalette(
    val accent: Color,
    val surface: Color,
    val surfaceHover: Color,
    val border: Color,
    val text: Color,
    val mutedText: Color,
    val levelColor: Map<LogLevel, Color>,
)

private fun rgb(c: java.awt.Color): Color = Color(c.red, c.green, c.blue)

@Composable
fun rememberLogPalette(): LogPalette {
    val accent = rgb(LogStyling.ACCENT)
    val text = rgb(UIUtil.getLabelForeground())
    return LogPalette(
        accent = accent,
        surface = rgb(UIUtil.getPanelBackground()),
        surfaceHover = accent.copy(alpha = 0.10f),
        border = rgb(JBColor.border()),
        text = text,
        mutedText = rgb(UIUtil.getContextHelpForeground()),
        levelColor = LogLevel.entries.associateWith { lvl ->
            LogStyling.levelDot(lvl)?.let { rgb(it) } ?: rgb(JBColor.GRAY)
        },
    )
}

// ---- Building blocks ----

/** A small filled severity dot. */
@Composable
fun SeverityDot(color: Color, diameter: Dp = 8.dp) {
    Box(Modifier.size(diameter).clip(CircleShape).background(color))
}

/**
 * A colored severity pill that doubles as a filter toggle: a dot + level + count. Active = tinted +
 * bordered with the level color; inactive = muted. Animated hover/active so toggling feels alive.
 */
@Composable
fun LevelPill(
    label: String,
    count: Int,
    color: Color,
    active: Boolean,
    palette: LogPalette,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            active -> color.copy(alpha = 0.16f)
            hovered -> palette.surfaceHover
            else -> Color.Transparent
        },
    )
    val borderColor by animateColorAsState(if (active) color.copy(alpha = 0.55f) else palette.border.copy(alpha = 0.6f))
    Row(
        Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(bg)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(Radii.pill))
            .hoverable(interaction)
            .clickable { onClick() }
            .padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        SeverityDot(if (active) color else color.copy(alpha = 0.45f), 7.dp)
        Text(label, color = if (active) palette.text else palette.mutedText, fontSize = 11.sp)
        Text("%,d".format(count), color = palette.mutedText, fontSize = 11.sp)
    }
}

/** The live-tail indicator / toggle: a pulsing-style dot + "LIVE" when following, "일시정지" when paused. */
@Composable
fun LiveBadge(live: Boolean, palette: LogPalette, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val liveColor = palette.levelColor[LogLevel.INFO] ?: palette.accent
    val bg by animateColorAsState(
        when {
            live -> liveColor.copy(alpha = 0.16f)
            hovered -> palette.surfaceHover
            else -> Color.Transparent
        },
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(bg)
            .border(BorderStroke(1.dp, if (live) liveColor.copy(alpha = 0.5f) else palette.border.copy(alpha = 0.6f)), RoundedCornerShape(Radii.sm))
            .hoverable(interaction)
            .clickable { onToggle() }
            .padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (live) {
            SeverityDot(liveColor, 7.dp)
            Text("LIVE", color = liveColor, fontSize = 11.sp)
        } else {
            Text("⏸", color = palette.mutedText, fontSize = 11.sp)
            Text("일시정지", color = palette.mutedText, fontSize = 11.sp)
        }
    }
}

/** A subtle text/icon button for the toolbar (rules, help, clear). [accent] highlights primary ones. */
@Composable
fun ToolButton(label: String, palette: LogPalette, accent: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) palette.surfaceHover else Color.Transparent)
    Box(
        Modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(bg)
            .hoverable(interaction)
            .clickable { onClick() }
            .padding(horizontal = Space.sm, vertical = Space.xs),
    ) {
        Text(label, color = if (accent) palette.accent else palette.mutedText, fontSize = 11.sp)
    }
}

/** A friendly centered empty state: a big glyph, a title, a hint, and an optional primary action. */
@Composable
fun EmptyState(glyph: String, title: String, subtitle: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val palette = rememberLogPalette()
    Column(
        Modifier.fillMaxWidth().padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(glyph, color = palette.mutedText, fontSize = 34.sp)
        Text(title, color = palette.text, fontSize = 14.sp)
        Text(subtitle, color = palette.mutedText, fontSize = 12.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(Space.xs))
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(palette.accent.copy(alpha = 0.16f))
                    .border(BorderStroke(1.dp, palette.accent.copy(alpha = 0.5f)), RoundedCornerShape(Radii.sm))
                    .clickable { onAction() }
                    .padding(horizontal = Space.md, vertical = Space.sm),
            ) {
                Text(actionLabel, color = palette.accent, fontSize = 12.sp)
            }
        }
    }
}
