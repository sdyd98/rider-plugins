package com.example.logview

import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.util.regex.Pattern
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Fully custom-painted renderer for a log line: a severity/rule background tint, translucent
 * highlights behind every search-match substring, the line text in a monospaced font, a focus ring
 * (always-on vim has no caret), and a "⊕ N more" hint on collapsed stack-trace blocks.
 *
 * Custom paint (rather than `DefaultTableCellRenderer` + HTML) keeps substring highlighting fast — only
 * the ~50 visible rows ever paint, and match positions are measured with [java.awt.FontMetrics] so they
 * stay correct for any glyph width (CJK included).
 */
class LogCellRenderer(
    private val model: LogTableModel,
    private val rules: () -> List<HighlightRule>,
    private val searchPattern: () -> Pattern?,
    private val messageCursor: () -> Int = { -1 }, // vim char-cursor index in the Message column, or -1
    private val messageSelection: () -> IntRange? = { null }, // vim char-wise visual range, or null
) : JComponent(), TableCellRenderer {

    private val mono: Font = LogFonts.MONO
    private val monoBold: Font = LogFonts.MONO_BOLD
    private val stripe: Color = UIUtil.getDecoratedRowColor()
    private val hintColor: Color = JBColor.GRAY

    private var text = ""
    private var bg: Color = JBColor.background()
    private var fg: Color = JBColor.foreground()
    private var useFont: Font = mono
    private var focused = false
    private var foldHint: String? = null
    private var ansiSpans: List<AnsiText.Span>? = null // non-null when this line has ANSI colors to render

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        text = value as? String ?: "" // the Message column text
        focused = hasFocus
        val modelRow = if (row < table.rowCount) table.convertRowIndexToModel(row) else row
        val line = if (modelRow in 0 until model.rowCount) model.lineAt(modelRow) else null

        val rule = firstMatchingRule(text)
        bg = if (rule?.backgroundRgb != null && !isSelected) Color(rule.backgroundRgb!!)
        else logRowBg(table, model, row, isSelected, stripe)
        fg = when {
            isSelected -> UIUtil.getTableSelectionForeground(true)
            rule?.foregroundRgb != null -> JBColor(Color(rule.foregroundRgb!!), Color(rule.foregroundRgb!!))
            else -> table.foreground
        }
        useFont = if (rule?.bold == true) monoBold else mono

        foldHint = if (model.isFoldableStart(modelRow) && modelRow in model.foldedBlocks) {
            " ⊕ ${model.blockContinuationCount(modelRow)} more"
        } else {
            null
        }

        // Render real ANSI colors (skip when selected — selection fg wins). In raw view color the whole
        // line; in parsed view color the message body only.
        ansiSpans = when {
            isSelected || line == null || rule != null -> null
            model.rawMode -> if (line.hasAnsi) line.rawSpans else null
            else -> if (line.messageHasAnsi) line.messageSpans else null
        }
        return this
    }

    private fun firstMatchingRule(line: String): HighlightRule? {
        val rs = rules()
        if (rs.isEmpty()) return null
        return rs.firstOrNull { it.enabled && it.matches(line) }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = bg
        g2.fillRect(0, 0, width, height)
        GraphicsUtil.setupAntialiasing(g2)
        g2.font = useFont
        val fm = g2.getFontMetrics(useFont)
        val pad = JBUI.scale(6)
        val baseline = (height + fm.ascent - fm.descent) / 2

        val spans = ansiSpans
        when {
            spans != null -> {
                // ANSI-colored line: draw each styled span (no substring search-highlight here — the colors
                // already segment the line, and mapping match offsets across spans isn't worth the cost).
                var x = pad
                for (sp in spans) {
                    val f = if (sp.bold) monoBold else useFont
                    g2.font = f
                    g2.color = sp.fgRgb?.let { Color(it) } ?: fg
                    g2.drawString(sp.text, x, baseline)
                    x += g2.getFontMetrics(f).stringWidth(sp.text)
                }
            }
            else -> {
                // Translucent backing behind every search match (positions measured for exact width).
                val pattern = searchPattern()
                if (pattern != null && text.isNotEmpty()) {
                    val m = pattern.matcher(text)
                    g2.color = LogStyling.SEARCH_MATCH
                    val hl = fm.height
                    val top = (height - hl) / 2
                    // Walk matches with a running x cursor: measure only the gap since the last match end,
                    // not substring(0, start) every time (glyph advances are additive, no kerning enabled).
                    var measuredTo = 0
                    var runX = pad
                    while (m.find()) {
                        if (m.end() == m.start()) { if (!m.hitEnd()) continue else break }
                        runX += fm.stringWidth(text.substring(measuredTo, m.start()))
                        val w = fm.stringWidth(text.substring(m.start(), m.end()))
                        g2.fillRect(runX, top, w, hl)
                        runX += w
                        measuredTo = m.end()
                    }
                }
                g2.color = fg
                g2.font = useFont
                g2.drawString(text, pad, baseline)
            }
        }

        foldHint?.let {
            g2.color = hintColor
            g2.font = monoBold
            g2.drawString(it, pad + fm.stringWidth(text), baseline)
        }

        if (focused) {
            drawMessageSelection(g2, fm, pad)
            drawCharCursor(g2, fm, pad)
            g2.color = LogStyling.ACCENT
            g2.drawRect(0, 0, width - 1, height - 1)
        }
    }

    /** Translucent highlight over the character-wise visual selection (vim `v` in the Message column). */
    private fun drawMessageSelection(g2: Graphics2D, fm: java.awt.FontMetrics, pad: Int) {
        val range = messageSelection() ?: return
        val lo = range.first.coerceIn(0, text.length)
        val hi = (range.last + 1).coerceIn(0, text.length)
        if (hi <= lo) return
        val x1 = pad + fm.stringWidth(text.substring(0, lo))
        val x2 = pad + fm.stringWidth(text.substring(0, hi))
        val ch = fm.height
        val a = LogStyling.ACCENT
        g2.color = Color(a.red, a.green, a.blue, 80)
        g2.fillRect(x1, (height - ch) / 2, x2 - x1, ch)
    }

    /** A vim block cursor over the char at [messageCursor], or a thin caret past the end of the text. */
    private fun drawCharCursor(g2: Graphics2D, fm: java.awt.FontMetrics, pad: Int) {
        val idx = messageCursor()
        if (idx < 0) return
        val safe = idx.coerceIn(0, text.length)
        val cx = pad + fm.stringWidth(text.substring(0, safe))
        val ch = fm.height
        val top = (height - ch) / 2
        val a = LogStyling.ACCENT
        if (safe < text.length) {
            val cw = fm.charWidth(text[safe]).coerceAtLeast(JBUI.scale(6))
            g2.color = Color(a.red, a.green, a.blue, 140)
            g2.fillRect(cx, top, cw, ch)
        } else {
            g2.color = a
            g2.fillRect(cx, top, JBUI.scale(2), ch)
        }
    }

    /** Preferred (comfortable-density) row height: monospaced line height + padding. */
    fun rowHeight(): Int = getFontMetrics(mono).height + JBUI.scale(9)

    /** Pixel width needed to show [line] fully (for sizing the Message column). */
    fun lineWidth(line: String): Int = getFontMetrics(mono).stringWidth(line) + JBUI.scale(24)

    /** Upper bound of one glyph's advance in the mono font (double-width CJK included) — for cheap
     *  "this line can't possibly be the widest" guards before a real [lineWidth] measurement. */
    fun maxCharWidth(): Int = getFontMetrics(mono).let { maxOf(it.charWidth('W'), it.charWidth('한')) }
}
