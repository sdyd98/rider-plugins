package com.example.xlsx

import androidx.compose.ui.graphics.Color

/**
 * Deterministic per-table identity color from a stable hash of the table id — O(1), no global table
 * list, so a node can be coloured from a local neighbourhood alone (important at 7000-table scale where
 * we never enumerate every table). Lightness tracks the IDE background luminance so the hue reads on
 * both light and dark themes. Same table id → same colour everywhere, across recenters.
 */
fun tableColor(tableId: String, bgArgb: Int): Color {
    val bg = Color(bgArgb)
    val bgLum = 0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue
    val l = if (bgLum < 0.5f) 0.62f else 0.45f // lighter hue on a dark bg, darker on a light bg
    val hue = ((tableId.hashCode().toLong() and 0xffffffffL) % 360L).toFloat()
    return Color.hsl(hue, 0.55f, l)
}
