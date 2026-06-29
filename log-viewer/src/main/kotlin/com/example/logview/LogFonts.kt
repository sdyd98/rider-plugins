package com.example.logview

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.util.ui.JBUI
import java.awt.Font

/**
 * One source of truth for the viewer's log-text font — grid cells, vim measuring, JSON popup.
 *
 * Follows the user's **editor font** (Settings → Editor → Font) via the global color scheme, so the
 * log reads in whatever monospaced font (and size) they picked — including one with good Hangul/CJK
 * glyphs. Resolved once at first use; an editor-font change in Settings applies after the next IDE
 * restart. Falls back to the logical "Monospaced" font if the scheme is somehow unavailable.
 */
object LogFonts {

    private val editorFont: Font =
        runCatching { EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN) }
            .getOrNull()
            ?: JBUI.Fonts.create(Font.MONOSPACED, JBUI.scaleFontSize(12.0f))

    /** Point size of the log text (the editor font's size). */
    val SIZE: Int = editorFont.size

    /** The shared log-text font (the editor font). */
    val MONO: Font = editorFont

    /** Bold variant of [MONO] (highlight-rule bold). */
    val MONO_BOLD: Font = MONO.deriveFont(Font.BOLD)
}
