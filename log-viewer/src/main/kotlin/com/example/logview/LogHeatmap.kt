package com.example.logview

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTable

/**
 * A thin file-overview stripe on the right edge of the log grid (like the editor's error stripe):
 * every ERROR / WARN line paints a mark at its relative position in the WHOLE model — independent
 * of the active filter, so error clusters stay visible even while filtered out — and the rows
 * currently in the viewport are shown as a translucent window. Click or drag to jump (the panel
 * maps the spot to the nearest visible row).
 *
 * Painting is O(marked rows + height): marks are aggregated into per-pixel buckets first (ERROR
 * wins over WARN inside a bucket), then drawn as vertical runs.
 */
class LogHeatmap(
    private val table: JTable,
    private val model: LogTableModel,
    private val onJump: (modelRow: Int) -> Unit,
) : JComponent() {

    init {
        isOpaque = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "에러/경고 분포 — 클릭해서 이동"
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = jump(e.y)
            override fun mouseDragged(e: MouseEvent) = jump(e.y)
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun jump(y: Int) {
        val total = model.rowCount
        if (total == 0 || height <= 0) return
        onJump((y.toLong() * total / height).toInt().coerceIn(0, total - 1))
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(9), 0)

    override fun paintComponent(g: Graphics) {
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, width, height)
        g.color = JBColor.border()
        g.drawLine(0, 0, 0, height)

        val total = model.rowCount
        val h = height
        if (total == 0 || h <= 0) return

        // 0 = none, 1 = WARN, 2 = ERROR (error wins inside a shared pixel bucket).
        val buckets = ByteArray(h)
        model.forEachLevelRow(LogLevel.WARN) { row ->
            val y = (row.toLong() * h / total).toInt().coerceAtMost(h - 1)
            if (buckets[y].toInt() == 0) buckets[y] = 1
        }
        model.forEachLevelRow(LogLevel.ERROR) { row ->
            buckets[(row.toLong() * h / total).toInt().coerceAtMost(h - 1)] = 2
        }

        val x = JBUI.scale(2)
        val w = (width - JBUI.scale(3)).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            val b = buckets[y]
            if (b.toInt() == 0) { y++; continue }
            var end = y + 1
            while (end < h && buckets[end] == b) end++
            g.color = LogStyling.levelDot(if (b.toInt() == 2) LogLevel.ERROR else LogLevel.WARN)
            g.fillRect(x, y, w, end - y)
            y = end
        }

        paintViewportWindow(g, total, h)
    }

    /** Translucent window over the model range currently visible in the viewport. */
    private fun paintViewportWindow(g: Graphics, total: Int, h: Int) {
        val viewRows = table.rowCount
        if (viewRows == 0) return
        val vr = table.visibleRect
        var firstV = table.rowAtPoint(Point(0, vr.y))
        if (firstV < 0) firstV = 0
        var lastV = table.rowAtPoint(Point(0, vr.y + vr.height - 1))
        if (lastV < 0) lastV = viewRows - 1
        val mTop = table.convertRowIndexToModel(firstV.coerceIn(0, viewRows - 1))
        val mBot = table.convertRowIndexToModel(lastV.coerceIn(firstV, viewRows - 1))
        val y1 = (mTop.toLong() * h / total).toInt()
        val y2 = ((mBot + 1L) * h / total).toInt().coerceAtLeast(y1 + JBUI.scale(4)).coerceAtMost(h)
        val a = LogStyling.ACCENT
        g.color = Color(a.red, a.green, a.blue, 36)
        g.fillRect(0, y1, width, y2 - y1)
        g.color = Color(a.red, a.green, a.blue, 110)
        g.drawRect(0, y1, width - 1, (y2 - y1 - 1).coerceAtLeast(1))
    }
}
