package com.example.logview

import com.intellij.util.ui.JBUI
import java.awt.Font

/** One source of truth for the viewer's monospaced font — grid cells, gutter, vim measuring, JSON popup. */
object LogFonts {

    /** Scaled logical point size used wherever monospaced log text is shown. */
    val SIZE: Int = JBUI.scaleFontSize(12.0f)

    /** The shared monospaced font (UIResource so Swing treats it as a LaF default, not a user override). */
    val MONO: Font = JBUI.Fonts.create(Font.MONOSPACED, SIZE).asUIResource()

    /** Bold variant of [MONO] (highlight-rule bold). */
    val MONO_BOLD: Font = MONO.deriveFont(Font.BOLD)
}
