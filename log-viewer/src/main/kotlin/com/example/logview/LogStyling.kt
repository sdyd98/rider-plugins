package com.example.logview

import com.intellij.ui.JBColor
import java.awt.Color

/** Theme-aware colors for log levels and viewer accents. */
object LogStyling {

    /** Accent used for the focused-line ring, the follow indicator, and active emphasis. */
    val ACCENT: JBColor = JBColor(0x3574F0, 0x589DF6)

    /** Translucent amber backing painted behind search-match substrings. */
    val SEARCH_MATCH: JBColor = JBColor(Color(255, 213, 79, 110), Color(200, 160, 54, 130))

    /** Very faint full-row wash for the top severities (the Level column carries the level cue, so
     *  this stays subtle — Better-Stack style). null = no tint, fall back to zebra. */
    fun levelBackground(level: LogLevel): Color? = when (level) {
        LogLevel.ERROR -> JBColor(Color(0xFC, 0xF1, 0xF1), Color(0x30, 0x25, 0x25))
        LogLevel.WARN -> JBColor(Color(0xFC, 0xF7, 0xEC), Color(0x2F, 0x2A, 0x20))
        else -> null
    }

    /** Dot color shown in the gutter next to each line's number. */
    fun levelDot(level: LogLevel): Color? = when (level) {
        LogLevel.ERROR -> JBColor(0xE05555, 0xE06C6C)
        LogLevel.WARN -> JBColor(0xD9883B, 0xE0A152)
        LogLevel.INFO -> JBColor(0x4C9F70, 0x5CB585)
        LogLevel.DEBUG -> JBColor(0x6F9FD8, 0x6F9FD8)
        LogLevel.TRACE -> JBColor(0x9AA0A6, 0x808488)
        LogLevel.OTHER -> null
    }
}
