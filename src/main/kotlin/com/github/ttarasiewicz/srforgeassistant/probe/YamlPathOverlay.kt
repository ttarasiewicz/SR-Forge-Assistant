package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.JBColor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import javax.swing.Timer

/**
 * Animated overlay drawn on top of a YAML editor that traces the structural
 * path to a selected dataset target.
 *
 * Two animations:
 *  1. **Trace** — over ~600 ms, a smoothed orange spine grows from the
 *     topmost ancestor key down to the leaf, with diamond dots appearing at
 *     each waypoint as the spine reaches them.
 *  2. **Pulse** — once the trace completes, a continuous breathing halo
 *     animates around the leaf marker until the path is cleared.
 *
 * Repaints are driven by a Swing Timer ticking every ~33 ms while a path is
 * displayed; clearing the path stops the timer to avoid background CPU.
 */
class YamlPathOverlay(private val editor: Editor) {

    /** Logical Y position + indent column of each key on the path. Root first. */
    private var points: List<PathPoint> = emptyList()
    private var highlighter: RangeHighlighter? = null
    private var timer: Timer? = null
    private var traceStart: Long = 0L

    private data class PathPoint(val offset: Int, val isLeaf: Boolean)

    /**
     * Show the path defined by [offsets] (root first, leaf last). Each offset
     * should point to the start of an ancestor key (or sequence-item marker).
     */
    fun show(offsets: List<Int>) {
        clear()
        if (offsets.isEmpty()) return

        points = offsets.mapIndexed { i, off -> PathPoint(off, i == offsets.lastIndex) }
        traceStart = System.currentTimeMillis()

        val lo = offsets.min()
        val hi = offsets.max()
        val docLen = editor.document.textLength
        val start = lo.coerceIn(0, docLen)
        val end = (hi + 1).coerceIn(start, docLen)

        highlighter = editor.markupModel.addRangeHighlighter(
            start, end,
            HighlighterLayer.LAST + 100,
            null,
            HighlighterTargetArea.EXACT_RANGE,
        ).also {
            it.customRenderer = PathRenderer()
        }

        timer = Timer(33) { editor.contentComponent.repaint() }
        timer?.start()
    }

    fun clear() {
        timer?.stop()
        timer = null
        highlighter?.let {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Throwable) {
                // editor may have been disposed
            }
        }
        highlighter = null
        points = emptyList()
    }

    // ── Renderer ───────────────────────────────────────────────────────────

    private inner class PathRenderer : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val pts = points
            if (pts.isEmpty()) return

            val g2 = (g as Graphics2D).create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val lineHeight = editor.lineHeight
                val xy = pts.map { p ->
                    val vp = editor.offsetToVisualPosition(p.offset)
                    val pt = editor.visualPositionToXY(vp)
                    // Anchor at vertical center of the line.
                    Point2D.Double(pt.x.toDouble(), pt.y.toDouble() + lineHeight / 2.0)
                }

                val now = System.currentTimeMillis()
                val traceProgress = ((now - traceStart) / TRACE_DURATION_MS.toDouble())
                    .coerceIn(0.0, 1.0)
                val pulsePhase = ((now - traceStart - TRACE_DURATION_MS).coerceAtLeast(0L) /
                    PULSE_PERIOD_MS.toDouble())

                drawSpine(g2, xy, traceProgress)
                drawDots(g2, pts, xy, traceProgress)

                if (traceProgress >= 1.0) {
                    drawPulse(g2, xy.last(), pulsePhase)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private fun drawSpine(g: Graphics2D, xy: List<Point2D.Double>, progress: Double) {
        if (xy.size < 2) return

        g.color = SPINE_COLOR
        g.stroke = BasicStroke(
            2.2f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )

        // Animate by drawing the cumulative path up to `progress` of the
        // total segment count, interpolating the final partial segment.
        val totalSegments = xy.size - 1
        val advance = progress * totalSegments
        val fullSegments = advance.toInt().coerceAtMost(totalSegments)
        val partial = (advance - fullSegments).coerceIn(0.0, 1.0)

        val path = Path2D.Double()
        path.moveTo(xy[0].x, xy[0].y)

        for (i in 0 until fullSegments) {
            val a = xy[i]
            val b = xy[i + 1]
            // S-shaped Bezier through the two endpoints. Control points
            // sit at the same Y as their anchor and pulled toward the
            // midpoint X — gives a smooth diagonal feel without overshoot.
            val midY = (a.y + b.y) / 2.0
            path.curveTo(a.x, midY, b.x, midY, b.x, b.y)
        }

        // Partial final segment (the head of the trace mid-animation)
        if (fullSegments < totalSegments && partial > 0.0) {
            val a = xy[fullSegments]
            val b = xy[fullSegments + 1]
            val tipX = a.x + (b.x - a.x) * partial
            val tipY = a.y + (b.y - a.y) * partial
            val midY = (a.y + tipY) / 2.0
            path.curveTo(a.x, midY, tipX, midY, tipX, tipY)
        }

        g.draw(path)
    }

    private fun drawDots(
        g: Graphics2D,
        pts: List<PathPoint>,
        xy: List<Point2D.Double>,
        progress: Double,
    ) {
        val totalSegments = (xy.size - 1).coerceAtLeast(1)
        for ((i, p) in xy.withIndex()) {
            // A point appears when the trace reaches it.
            val reachedAt = if (xy.size == 1) 0.0 else i.toDouble() / totalSegments
            if (progress < reachedAt) continue

            // Scale-in over the first 100 ms of being "reached".
            val sinceReached = ((progress - reachedAt) * TRACE_DURATION_MS).coerceAtLeast(0.0)
            val scaleProgress = (sinceReached / 100.0).coerceIn(0.0, 1.0)
            val scale = easeOutBack(scaleProgress)

            val isLeaf = pts[i].isLeaf
            val baseR = if (isLeaf) 5.5 else 3.5
            val r = baseR * scale

            // Diamond for ancestors, filled circle for the leaf.
            g.color = if (isLeaf) LEAF_COLOR else ANCESTOR_COLOR
            if (isLeaf) {
                fillCircle(g, p.x, p.y, r)
            } else {
                fillDiamond(g, p.x, p.y, r)
            }
        }
    }

    private fun drawPulse(g: Graphics2D, leaf: Point2D.Double, phase: Double) {
        // Two concentric expanding rings, each riding a separate phase, so
        // the pulse feels organic rather than mechanical.
        for (i in 0..1) {
            val t = ((phase + i * 0.5) % 1.0)
            val alpha = ((1.0 - t) * 0.55).coerceIn(0.0, 1.0).toFloat()
            val ringR = 6.0 + t * 16.0
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g.color = LEAF_COLOR
            g.stroke = BasicStroke(1.8f)
            val d = ringR * 2.0
            g.drawOval(
                (leaf.x - ringR).toInt(),
                (leaf.y - ringR).toInt(),
                d.toInt(),
                d.toInt(),
            )
        }
        g.composite = AlphaComposite.SrcOver
    }

    private fun fillCircle(g: Graphics2D, cx: Double, cy: Double, r: Double) {
        val d = (r * 2.0).toInt()
        g.fillOval((cx - r).toInt(), (cy - r).toInt(), d, d)
    }

    private fun fillDiamond(g: Graphics2D, cx: Double, cy: Double, r: Double) {
        val xs = intArrayOf(cx.toInt(), (cx + r).toInt(), cx.toInt(), (cx - r).toInt())
        val ys = intArrayOf((cy - r).toInt(), cy.toInt(), (cy + r).toInt(), cy.toInt())
        g.fillPolygon(xs, ys, 4)
    }

    private fun easeOutBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = c1 + 1
        val x = t - 1
        return 1 + c3 * x * x * x + c1 * x * x
    }

    companion object {
        private const val TRACE_DURATION_MS = 600L
        private const val PULSE_PERIOD_MS = 1400L

        private val SPINE_COLOR = JBColor(Color(0xFF8C00), Color(0xFFAA33))
        private val ANCESTOR_COLOR = JBColor(Color(0xE67E22), Color(0xFFB570))
        private val LEAF_COLOR = JBColor(Color(0xD35400), Color(0xFFC880))
    }
}
