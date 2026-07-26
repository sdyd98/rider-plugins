package com.example.codemap

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The visual language of the 코드맵 tool window — spacing, a theme-aware palette, and the blocks the
 * note view is assembled from. Same split as the other two plugins: this file is the
 * direction-agnostic design system, [CodemapView] arranges it.
 *
 * The whole tool window is chrome (no data grid), so it is Compose/Jewel end to end.
 *
 * Two rules drive the look, both aimed at SKIMMING rather than reading:
 *  - **Code identifiers are monospace.** `OnPacket`, `m_sendLock`, `CS_LOGIN_REQ` are things you will
 *    search for in the editor; setting them apart from the prose around them is what makes the note
 *    scannable instead of a wall of text.
 *  - **Hierarchy comes from weight and rhythm, not from boxes.** Section headers carry an accent rule,
 *    body text is quieter than the identifiers it explains, and every block shares one spacing scale.
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
    val pill: Dp = 100.dp
}

/** Type scale. Kept tiny and explicit so sizes stay consistent instead of being guessed per call site. */
object Type {
    val title = 15.sp
    val metric = 17.sp
    val body = 12.5.sp
    val code = 12.sp
    val label = 11.sp
    val micro = 10.sp
}

/** Colors snapshotted from the IDE LAF per composition, so light/dark both come out right. */
class CodemapPalette(
    val accent: Color,
    val surface: Color,
    val surfaceHover: Color,
    /** Barely-there fill for grouped blocks — enough to separate, not enough to become a box. */
    val subtle: Color,
    val border: Color,
    val text: Color,
    val mutedText: Color,
    val warn: Color,
    /** Inbound packets (client → server). */
    val inbound: Color,
    /** Outbound packets (server → client). */
    val outbound: Color,
)

private fun rgb(c: java.awt.Color): Color = Color(c.red, c.green, c.blue)

/** Stale is a warning, not an error — amber in both themes, readable on either background. */
private val WARN = JBColor(0xB8730B, 0xE0A33E)

/** Packet direction. Chosen as a pair so inbound/outbound stay distinguishable in light AND dark. */
private val INBOUND = JBColor(0x2470B3, 0x589DF6)
private val OUTBOUND = JBColor(0x1F7A4D, 0x4FAE79)

@Composable
fun rememberCodemapPalette(): CodemapPalette {
    val accent = rgb(JBColor.namedColor("Link.activeForeground", JBColor(0x2470B3, 0x589DF6)))
    val text = rgb(UIUtil.getLabelForeground())
    return CodemapPalette(
        accent = accent,
        surface = rgb(UIUtil.getPanelBackground()),
        surfaceHover = accent.copy(alpha = 0.10f),
        subtle = text.copy(alpha = 0.045f),
        border = rgb(JBColor.border()),
        text = text,
        mutedText = rgb(UIUtil.getContextHelpForeground()),
        warn = rgb(WARN),
        inbound = rgb(INBOUND),
        outbound = rgb(OUTBOUND),
    )
}

// ---- text ----

/** A code identifier: monospace, so it reads as something you can search for, not as prose. */
@Composable
fun Mono(
    text: String,
    color: Color,
    size: androidx.compose.ui.unit.TextUnit = Type.code,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    Text(text, color = color, fontSize = size, fontFamily = FontFamily.Monospace, fontWeight = weight, modifier = modifier)
}

/** Prose inside a section. */
@Composable
fun Body(text: String, palette: CodemapPalette, color: Color = palette.text) {
    Text(text, color = color, fontSize = Type.body, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
}

// ---- structure ----

/**
 * A section header: an accent rule plus a title with real weight, so sections are findable at a glance.
 *
 * Deliberately content-sized rather than full-width, so a header can share its row with an action
 * (the function list's filter toggle) instead of pushing it off the edge.
 */
@Composable
fun SectionHeader(title: String, palette: CodemapPalette, color: Color = palette.accent) {
    Row(
        Modifier.padding(top = Space.lg, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.width(3.dp).height(12.dp).background(color, RoundedCornerShape(Radii.pill)))
        Text(title, color = palette.text, fontSize = Type.label, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A section that starts closed. The panel's default view is deliberately short — what the file is,
 * where to start, what bites — and everything else waits behind one of these. On a file with a dozen
 * recorded aspects, showing them all at once is the same as showing none.
 */
@Composable
fun CollapsibleSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    palette: CodemapPalette,
    onToggle: () -> Unit,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) palette.surfaceHover else Color.Transparent)
    Row(
        Modifier.fillMaxWidth()
            .padding(top = Space.md)
            .background(bg, RoundedCornerShape(Radii.sm))
            .hoverable(interaction)
            .clickable { onToggle() }
            .padding(horizontal = Space.xs, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(if (expanded) "▾" else "▸", color = palette.mutedText, fontSize = Type.micro)
        Text(title, color = palette.text, fontSize = Type.label, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotEmpty()) Text(subtitle, color = palette.mutedText, fontSize = Type.micro)
    }
    if (expanded) Column(Modifier.fillMaxWidth(), content = content)
}

/**
 * The function the caret is currently sitting in — the panel following your attention instead of
 * making you go find the right entry in a list.
 */
@Composable
fun FocusCard(title: String, palette: CodemapPalette, content: @Composable ColumnScopeAlias.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(top = Space.md)
            .background(palette.accent.copy(alpha = 0.07f), RoundedCornerShape(Radii.sm))
            .border(BorderStroke(1.dp, palette.accent.copy(alpha = 0.28f)), RoundedCornerShape(Radii.sm))
            .padding(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text("커서", color = palette.accent, fontSize = Type.micro)
            Mono(title, palette.accent, weight = FontWeight.SemiBold)
        }
        content()
    }
}

/** A hairline separator; quieter than a border, enough to break the page into regions. */
@Composable
fun Rule(palette: CodemapPalette, top: Dp = Space.md, bottom: Dp = Space.md) {
    Spacer(Modifier.height(top))
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border.copy(alpha = 0.5f)))
    Spacer(Modifier.height(bottom))
}


/**
 * An ordered entry: a numbered identifier with its explanation indented beneath. Stacked rather than
 * two-column because this panel is narrow and the explanations are sentences — a fixed label column
 * would squeeze them into a ribbon.
 */
@Composable
fun NumberedEntry(
    index: Int,
    symbol: String,
    note: String,
    palette: CodemapPalette,
    onJump: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered && onJump != null) palette.surfaceHover else Color.Transparent)
    Row(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(Radii.sm))
            .hoverable(interaction)
            .let { m -> if (onJump != null) m.clickable { onJump() } else m }
            .padding(vertical = Space.xxs, horizontal = Space.xxs),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(
            Modifier.size(16.dp)
                .background(palette.accent.copy(alpha = 0.15f), RoundedCornerShape(Radii.pill)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", color = palette.accent, fontSize = Type.micro, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxWidth()) {
            Mono(symbol, palette.accent, weight = FontWeight.Medium)
            if (note.isNotEmpty()) {
                Text(note, color = palette.mutedText, fontSize = Type.label, lineHeight = 16.sp)
            }
        }
    }
}

/** An identifier with an explanation under it — the unnumbered sibling of [NumberedEntry]. */
@Composable
fun DefEntry(symbol: String, note: String, palette: CodemapPalette, symbolColor: Color = palette.text) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxs)) {
        Mono(symbol, symbolColor, weight = FontWeight.Medium)
        if (note.isNotEmpty()) {
            Text(note, color = palette.mutedText, fontSize = Type.label, lineHeight = 16.sp)
        }
    }
}

/** A short `label  value` pair for attributes whose values are a few words (보호 / 순서). */
@Composable
fun AttrRow(label: String, value: String, palette: CodemapPalette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(label, color = palette.mutedText, fontSize = Type.micro, modifier = Modifier.width(30.dp))
        Text(value, color = palette.mutedText, fontSize = Type.label, lineHeight = 16.sp)
    }
}

/** A prose line prefixed by a quiet label, for one-off facts (스레드 소속 etc.). */
@Composable
fun NotedLine(label: String, value: String, palette: CodemapPalette) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxs)) {
        Text(label, color = palette.mutedText, fontSize = Type.micro)
        Text(value, color = palette.text, fontSize = Type.label, lineHeight = 16.sp)
    }
}

// ---- accents ----

/** A small outlined chip — freshness, request state. */
@Composable
fun Chip(label: String, color: Color, palette: CodemapPalette) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(Radii.pill))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(Radii.pill))
            .padding(horizontal = Space.sm, vertical = Space.xxs),
    ) {
        Text(label, color = color, fontSize = Type.micro, fontWeight = FontWeight.Medium)
    }
}

/** A filled direction badge (수신 / 송신). Fixed width so the packet ids beside it line up. */
@Composable
fun DirBadge(label: String, color: Color) {
    Box(
        Modifier.width(38.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(Radii.sm))
            .padding(vertical = Space.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = Type.micro, fontWeight = FontWeight.Medium)
    }
}

/** One packet: direction badge, the id in monospace, and who handles/sends it. */
@Composable
fun PacketRow(dirLabel: String, dirColor: Color, id: String, handler: String, palette: CodemapPalette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.xxs),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.Top,
    ) {
        if (dirLabel.isNotEmpty()) DirBadge(dirLabel, dirColor) else Spacer(Modifier.width(38.dp))
        Column(Modifier.fillMaxWidth()) {
            Mono(id, palette.text)
            if (handler.isNotEmpty()) Mono(handler, palette.mutedText, size = Type.micro)
        }
    }
}

/**
 * One function in the file's table of contents.
 *
 * The name is the click target and it jumps to the code — on a 6,000-line file that is the whole point
 * of the list. When the anchor no longer matches anything the row goes quiet and un-clickable rather
 * than jumping somewhere wrong: a stale location is worse than no location.
 */
@Composable
fun FunctionRow(
    name: String,
    purpose: String,
    badges: List<Pair<String, Color>>,
    located: Boolean,
    ambiguous: Boolean,
    current: Boolean,
    palette: CodemapPalette,
    onJump: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            current -> palette.accent.copy(alpha = 0.12f)
            hovered && located -> palette.surfaceHover
            else -> Color.Transparent
        },
    )
    Column(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(Radii.sm))
            .hoverable(interaction)
            .let { if (located) it.clickable { onJump() } else it }
            .padding(horizontal = Space.xs, vertical = Space.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (!located) Text("⚠", color = palette.warn, fontSize = Type.micro)
            Mono(
                name,
                if (located) palette.accent else palette.mutedText,
                weight = FontWeight.Medium,
            )
            badges.forEach { (label, color) -> MiniBadge(label, color) }
            if (ambiguous) MiniBadge("중복", palette.warn)
        }
        val detail = if (located) purpose else
            purpose.ifEmpty { "" }.let { if (it.isEmpty()) "시그니처를 못 찾음 — 코드가 바뀐 듯" else it }
        if (detail.isNotEmpty()) {
            Text(detail, color = palette.mutedText, fontSize = Type.label, lineHeight = 16.sp)
        }
        if (!located && purpose.isNotEmpty()) {
            Text("시그니처를 못 찾음 — 코드가 바뀐 듯", color = palette.warn, fontSize = Type.micro)
        }
    }
}

/** A very small inline tag (thread, lock) that rides next to a function name. */
@Composable
fun MiniBadge(label: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.13f), RoundedCornerShape(Radii.sm))
            .padding(horizontal = Space.xs, vertical = 1.dp),
    ) {
        Text(label, color = color, fontSize = Type.micro)
    }
}

/** A grouped block with a quiet fill — used for a lock and its attributes. */
@Composable
fun SubtleBlock(palette: CodemapPalette, content: @Composable ColumnScopeAlias.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = Space.xxs)
            .background(palette.subtle, RoundedCornerShape(Radii.sm))
            .padding(horizontal = Space.sm, vertical = Space.sm),
        content = content,
    )
}

/** Alias so call sites don't need the Compose layout import. */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * A gotcha: an amber rule down the left edge. These are the lines that stop a bug, so they carry weight.
 *
 * The rule is sized with `IntrinsicSize.Min` so it spans the wrapped text however tall it ends up — a
 * fixed-height bar stops short on a three-line warning and reads like a rendering glitch.
 */
@Composable
fun WarnCard(text: String, palette: CodemapPalette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.xxs)
            .background(palette.warn.copy(alpha = 0.07f), RoundedCornerShape(Radii.sm))
            .height(IntrinsicSize.Min)
            .padding(end = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(palette.warn.copy(alpha = 0.7f)))
        Text(
            text,
            color = palette.text,
            fontSize = Type.label,
            lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.sm),
        )
    }
}

/**
 * A piece of the note you can correct in place.
 *
 * Editing is entered deliberately (click the text) and left with Esc, for the same reason the question
 * field is behind a toggle: a Compose text field that is simply *present* takes focus when the tool
 * window opens and then eats the IDE's own shortcuts. Nothing here is focusable until you ask for it.
 */
@Composable
fun Editable(
    value: String,
    palette: CodemapPalette,
    fontSize: androidx.compose.ui.unit.TextUnit = Type.body,
    color: Color = palette.text,
    mono: Boolean = false,
    placeholder: String = "(비어 있음)",
    /** How the value looks when not being edited — so making a block editable never restyles it. */
    display: (@Composable (String) -> Unit)? = null,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var editing by remember(value) { mutableStateOf(false) }

    if (!editing) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val bg by animateColorAsState(if (hovered) palette.surfaceHover else Color.Transparent)
        Row(
            Modifier.fillMaxWidth()
                .background(bg, RoundedCornerShape(Radii.sm))
                .hoverable(interaction)
                .clickable { editing = true }
                .padding(horizontal = Space.xxs, vertical = Space.xxs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Box(Modifier.weight(1f)) {
                if (display != null && value.isNotEmpty()) {
                    display(value)
                } else {
                    Text(
                        value.ifEmpty { placeholder },
                        color = if (value.isEmpty()) palette.mutedText else color,
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.45f,
                        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    )
                }
            }
            if (hovered) Text("✎", color = palette.mutedText, fontSize = Type.micro)
        }
        return
    }

    val state = remember { TextFieldState(value) }
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxs)) {
        TextField(
            state = state,
            modifier = Modifier.fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        editing = false
                        true
                    } else {
                        false
                    }
                },
        )
        Row(
            Modifier.padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            ActionButton("저장", palette) {
                onSave(state.text.toString().trim())
                editing = false
            }
            ActionButton("취소", palette, primary = false) { editing = false }
            if (onDelete != null) ActionButton("삭제", palette, primary = false) {
                onDelete()
                editing = false
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

/** The stale banner: the one thing that must be impossible to miss when a note has drifted. */
@Composable
fun Banner(text: String, color: Color, palette: CodemapPalette) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radii.sm))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.4f)), RoundedCornerShape(Radii.sm))
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = Type.label, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
    }
}

/** An action button; [primary] is the accented one (분석 요청), secondary is quiet (질문 달기). */
@Composable
fun ActionButton(
    label: String,
    palette: CodemapPalette,
    enabled: Boolean = true,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (!enabled) palette.mutedText else if (primary) palette.accent else palette.mutedText
    val bg by animateColorAsState(if (hovered && enabled) tint.copy(alpha = 0.22f) else tint.copy(alpha = 0.12f))
    Box(
        Modifier
            .background(bg, RoundedCornerShape(Radii.sm))
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.45f)), RoundedCornerShape(Radii.sm))
            .hoverable(interaction)
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
        Text(label, color = tint, fontSize = Type.label, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(glyph: String, title: String, subtitle: String, palette: CodemapPalette) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(glyph, color = palette.mutedText, fontSize = 30.sp)
        Text(title, color = palette.text, fontSize = Type.body, fontWeight = FontWeight.Medium)
        Text(subtitle, color = palette.mutedText, fontSize = Type.label)
    }
}
