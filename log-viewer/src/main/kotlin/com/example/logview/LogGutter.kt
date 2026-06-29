package com.example.logview

import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Left gutter for the log grid: a severity dot, the source line number, and a fold triangle on
 * collapsible stack-trace blocks. Painted from the table's live geometry so it tracks
 * scroll/filter/streaming, and highlights the active row. Clicking a fold triangle toggles the block.
 */
class LogGutter(
    private val table: JBTable,
    private val model: LogTableModel,
) : JComponent() {

    /** Invoked with the MODEL row whose fold triangle was clicked. */
    var onToggleFold: ((modelRow: Int) -> Unit)? = null

    private val foldWidth = JBUI.scale(14)
    private val dotWidth = JBUI.scale(12)

    // Cached PLAIN/BOLD derivations of the base font; rederived only when the base font changes (the
    // gutter repaints on every scroll/selection/model event, so deriving per paint allocated 2 Fonts each).
    // Seeded from table.font (this component's own `font` isn't set until the init block below runs).
    private var fontBase: Font? = null
    private var fontPlain: Font = table.font
    private var fontBold: Font = table.font

    private fun ensureFonts() {
        val f = font ?: table.font
        if (f !== fontBase) {
            fontBase = f
            fontPlain = f.deriveFont(Font.PLAIN)
            fontBold = f.deriveFont(Font.BOLD)
        }
    }

    init {
        isOpaque = true
        font = table.font
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.x > foldWidth) return // only the triangle column toggles folds
                val viewRow = table.rowAtPoint(Point(0, e.y))
                if (viewRow < 0) return
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (model.isFoldableStart(modelRow)) onToggleFold?.invoke(modelRow)
            }
        })
    }

    override fun getPreferredSize(): Dimension {
        val rows = table.rowCount
        val height = if (rows > 0) table.getCellRect(rows - 1, 0, true).let { it.y + it.height } else 0
        val digits = maxOf(3, model.maxLineNumber().toString().length)
        val numWidth = getFontMetrics(font).charWidth('0') * digits
        return Dimension(foldWidth + dotWidth + numWidth + JBUI.scale(10), height)
    }

    override fun paintComponent(g: Graphics) {
        val clip = g.clipBounds
        g.color = UIUtil.getPanelBackground()
        g.fillRect(clip.x, clip.y, clip.width, clip.height)

        val rowCount = table.rowCount
        if (rowCount == 0) return
        var first = table.rowAtPoint(Point(0, clip.y))
        if (first < 0) first = 0
        var last = table.rowAtPoint(Point(0, clip.y + clip.height - 1))
        if (last < 0) last = rowCount - 1

        val selectionBg = UIUtil.getTableSelectionBackground(false)
        val selectionFg = UIUtil.getTableSelectionForeground(false)
        ensureFonts()
        val plain = fontPlain
        val bold = fontBold
        val fm = g.getFontMetrics(plain)
        val numRight = width - JBUI.scale(8)

        for (r in first..last) {
            if (r < 0 || r >= rowCount) continue
            val rect = table.getCellRect(r, 0, true)
            val modelRow = table.convertRowIndexToModel(r)
            if (modelRow < 0 || modelRow >= model.rowCount) continue
            val selected = table.selectionModel.isSelectedIndex(r)
            if (selected) {
                g.color = selectionBg
                g.fillRect(0, rect.y, width, rect.height)
            }

            // Fold triangle for collapsible blocks (level is shown in the Level column now).
            if (model.isFoldableStart(modelRow)) {
                g.color = JBColor.GRAY
                g.font = plain
                val tri = if (modelRow in model.foldedBlocks) "▸" else "▾"
                g.drawString(tri, JBUI.scale(3), rect.y + (rect.height + fm.ascent - fm.descent) / 2)
            }

            // Line number (source line, stable across filtering).
            val num = model.lineAt(modelRow).lineNumber.toString()
            g.font = if (selected) bold else plain
            g.color = if (selected) selectionFg else JBColor.GRAY
            g.drawString(num, numRight - fm.stringWidth(num), rect.y + (rect.height + fm.ascent - fm.descent) / 2)
        }
        g.color = JBColor.border()
        g.drawLine(width - 1, clip.y, width - 1, clip.y + clip.height)
    }
}
