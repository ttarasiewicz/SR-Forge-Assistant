package com.github.ttarasiewicz.srforgeassistant.probe

import com.github.ttarasiewicz.srforgeassistant.SrForgeHighlightSettings
import com.intellij.openapi.editor.Editor
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Animated overlay drawn on top of a YAML editor that traces a path tree
 * connecting dataset waypoints.
 *
 * Topology: the user-selected start node is the root; its descendants form a
 * tree of edges. A composite (e.g. ConcatDataset) fans out — the chosen
 * branch is the *primary* path, all other branches are *alternative*.
 *
 * Visual style:
 *  - Primary edges: gold Bezier spine, diamond markers at each ancestor,
 *    pulsing halo at primary leaves.
 *  - Alternative edges: darker gold (slightly more saturated/muted), no
 *    pulse at the leaf, smaller marker.
 *
 * Animation: ~600 ms entry trace where every edge grows together (each from
 * its parent toward its child); markers ease-out-back into place as the
 * trace reaches them; primary leaves keep pulsing afterward at a 1400 ms
 * period.
 */
class YamlPathOverlay(private val editor: Editor) {

    /**
     * A connection between two waypoints. [depth] is the 1-based depth of
     * the edge's *child* (so the root → first-child edge has depth 1).
     */
    data class PathEdge(val from: Int, val to: Int, val isPrimary: Boolean, val depth: Int)

    /**
     * A waypoint marker. [depth] is 0 for the start node, 1 for its direct
     * children, etc. Used to time the marker's appearance during the trace.
     */
    data class PathNode(val offset: Int, val isLeaf: Boolean, val isPrimary: Boolean, val depth: Int)

    private var edges: List<PathEdge> = emptyList()
    private var nodes: List<PathNode> = emptyList()
    private var timer: Timer? = null
    private var traceStart: Long = 0L

    // ── Overlay JComponent mount ─────────────────────────────────────────
    //
    // We render through a JComponent that's *added as a child of the editor's
    // contentComponent*. That places our paint pass strictly *after* the
    // editor's own paintComponent (the call order is paintComponent →
    // paintBorder → paintChildren), so indent guides and any other intrinsic
    // editor decorations cannot draw over us. The previous CustomHighlighter
    // approach was vulnerable to IntelliJ's intrinsic indent-guide pass.

    private val overlay: OverlayComponent = OverlayComponent()
    private var componentListener: ComponentListener? = null
    private var visibleAreaListener: com.intellij.openapi.editor.event.VisibleAreaListener? = null
    private var mounted: Boolean = false

    /**
     * Show the tree defined by [edges] and [nodes]. Restart the animation.
     * Pass empty inputs to clear.
     */
    fun show(edges: List<PathEdge>, nodes: List<PathNode>) {
        clear()
        if (edges.isEmpty() && nodes.isEmpty()) return

        this.edges = edges
        this.nodes = nodes
        traceStart = System.currentTimeMillis()

        mountOverlay()
        timer = Timer(33) { overlay.repaint() }.also { it.start() }
    }

    fun clear() {
        timer?.stop()
        timer = null
        unmountOverlay()
        edges = emptyList()
        nodes = emptyList()
    }

    private fun mountOverlay() {
        if (mounted) return
        mounted = true
        val cc = editor.contentComponent
        cc.add(overlay)
        overlay.setBounds(0, 0, cc.width, cc.height)
        componentListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                overlay.setBounds(0, 0, cc.width, cc.height)
            }
        }
        cc.addComponentListener(componentListener)
        // Scroll within the viewport doesn't invalidate the overlay's content
        // automatically (only the visible region moves) — request a repaint
        // so anchors that rely on offsetToXY stay aligned with the text.
        visibleAreaListener = com.intellij.openapi.editor.event.VisibleAreaListener {
            overlay.repaint()
        }.also { editor.scrollingModel.addVisibleAreaListener(it) }
    }

    private fun unmountOverlay() {
        if (!mounted) return
        mounted = false
        val cc = editor.contentComponent
        componentListener?.let { cc.removeComponentListener(it) }
        componentListener = null
        visibleAreaListener?.let { editor.scrollingModel.removeVisibleAreaListener(it) }
        visibleAreaListener = null
        try {
            cc.remove(overlay)
            cc.repaint()
        } catch (_: Throwable) {
            // editor may have been disposed
        }
    }

    // ── Overlay component ────────────────────────────────────────────────

    private inner class OverlayComponent : JComponent() {
        init {
            isOpaque = false
        }

        /** Don't intercept mouse events — pass them through to the editor. */
        override fun contains(x: Int, y: Int): Boolean = false

        override fun paintComponent(g: Graphics) {
            if (edges.isEmpty() && nodes.isEmpty()) return

            val g2 = (g as Graphics2D).create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                // Per-segment timing: each edge gets the same wall-clock
                // duration regardless of how deep the tree goes, so the
                // perceived animation speed stays uniform across long and
                // short paths alike. The setting is the time *per segment*;
                // total trace time scales with maxDepth.
                val perSegmentMs = SrForgeHighlightSettings.getInstance().state
                    .pathTraceDurationMs
                    .coerceAtLeast(50)
                    .toLong()
                val maxDepth = (nodes.maxOfOrNull { it.depth } ?: 0).coerceAtLeast(1)
                val totalMs = perSegmentMs * maxDepth

                val now = System.currentTimeMillis()
                val overall = ((now - traceStart) / totalMs.toDouble()).coerceIn(0.0, 1.0)
                val pulsePhase = ((now - traceStart - totalMs).coerceAtLeast(0L) /
                    PULSE_PERIOD_MS.toDouble())

                // Sequential animation: each edge animates within its own
                // [(depth-1)/maxDepth, depth/maxDepth] slice of the overall
                // duration. The trace flows outward from the start; at every
                // composite, all branches at the same depth fan out together.
                for (e in edges) if (!e.isPrimary) drawEdge(g2, e, edgeProgress(e, overall, maxDepth))
                for (e in edges) if (e.isPrimary) drawEdge(g2, e, edgeProgress(e, overall, maxDepth))

                // Markers ease in when the trace reaches their depth.
                for (n in nodes) drawMarker(g2, n, overall, maxDepth, pulsePhase)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun edgeProgress(edge: PathEdge, overall: Double, maxDepth: Int): Double {
        // Edge of depth d animates over the slice [(d-1)/maxDepth, d/maxDepth].
        val scaled = overall * maxDepth - (edge.depth - 1)
        return scaled.coerceIn(0.0, 1.0)
    }

    private fun anchor(offset: Int): Point2D.Double {
        val vp = editor.offsetToVisualPosition(offset)
        val pt = editor.visualPositionToXY(vp)
        return Point2D.Double(pt.x.toDouble(), pt.y.toDouble() + editor.lineHeight / 2.0)
    }

    private fun drawEdge(g: Graphics2D, edge: PathEdge, progress: Double) {
        val a = anchor(edge.from)
        val b = anchor(edge.to)

        // Route the curve through a rail to the LEFT of both anchors so the
        // path leaves the parent going left, drops/rises along the rail, then
        // approaches the child horizontally — never crossing the text in
        // between. Each anchor is now entered/exited *horizontally*.
        val railX = minOf(a.x, b.x) - RAIL_OFFSET
        val p0 = a
        val p1 = Point2D.Double(railX, a.y)
        val p2 = Point2D.Double(railX, b.y)
        val p3 = b

        // De Casteljau subdivision: take the first [progress] fraction of the
        // cubic Bezier so the curve grows along its own geometry rather than
        // tracking a linearly-interpolated endpoint (which would zigzag).
        val q0 = lerp(p0, p1, progress)
        val q1 = lerp(p1, p2, progress)
        val q2 = lerp(p2, p3, progress)
        val r0 = lerp(q0, q1, progress)
        val r1 = lerp(q1, q2, progress)
        val tip = lerp(r0, r1, progress)

        val color = if (edge.isPrimary) PRIMARY_SPINE else ALT_SPINE
        val width = if (edge.isPrimary) 2.2f else 1.6f
        g.color = color
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val path = Path2D.Double()
        path.moveTo(p0.x, p0.y)
        path.curveTo(q0.x, q0.y, r0.x, r0.y, tip.x, tip.y)
        g.draw(path)
    }

    private fun lerp(a: Point2D.Double, b: Point2D.Double, t: Double): Point2D.Double =
        Point2D.Double(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    private fun drawMarker(
        g: Graphics2D,
        node: PathNode,
        overall: Double,
        maxDepth: Int,
        pulsePhase: Double,
    ) {
        // A node at depth d appears as the trace reaches it: scale animates
        // over a short window starting at overall=d/maxDepth.
        val markerProgress = (overall * maxDepth - node.depth).coerceIn(0.0, 1.0)
        val scale = easeOutBack(markerProgress)
        if (scale <= 0.0) return
        // Leaves only get their pulse once the whole trace has settled.
        val pulseReady = overall >= 1.0

        // Paint a slightly-larger marker in the editor's background colour
        // BEFORE the coloured fill — it "cuts out" any curve passing under
        // the marker, so the dataset waypoint stays crisp even when the
        // path approaches it. Theme-aware via colorsScheme.defaultBackground.
        val bgCutout = editor.colorsScheme.defaultBackground
        val cutoutPad = 2.0

        val p = anchor(node.offset)
        when {
            node.isLeaf && node.isPrimary -> {
                val r = 5.5 * scale
                g.color = bgCutout
                fillCircle(g, p.x, p.y, r + cutoutPad)
                g.color = PRIMARY_LEAF
                fillCircle(g, p.x, p.y, r)
                if (pulseReady) drawPulse(g, p, pulsePhase)
            }
            node.isLeaf && !node.isPrimary -> {
                val r = 4.5 * scale
                g.color = bgCutout
                fillCircle(g, p.x, p.y, r + cutoutPad)
                g.color = ALT_LEAF
                fillCircle(g, p.x, p.y, r)
            }
            else -> {
                // Ancestor — diamond.
                val baseR = if (node.isPrimary) 3.6 else 3.0
                val r = baseR * scale
                g.color = bgCutout
                fillDiamond(g, p.x, p.y, r + cutoutPad)
                g.color = if (node.isPrimary) PRIMARY_NODE else ALT_NODE
                fillDiamond(g, p.x, p.y, r)
            }
        }
    }

    private fun drawPulse(g: Graphics2D, leaf: Point2D.Double, phase: Double) {
        // Two concentric expanding rings on offset phases for an organic feel.
        for (i in 0..1) {
            val t = ((phase + i * 0.5) % 1.0)
            val alpha = ((1.0 - t) * 0.55).coerceIn(0.0, 1.0).toFloat()
            val ringR = 6.0 + t * 16.0
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g.color = PRIMARY_LEAF
            g.stroke = BasicStroke(1.8f)
            val d = (ringR * 2.0).toInt()
            g.drawOval(
                (leaf.x - ringR).toInt(),
                (leaf.y - ringR).toInt(),
                d,
                d,
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
        private const val PULSE_PERIOD_MS = 1400L

        /**
         * How far left of the leftmost edge endpoint the routing rail sits.
         * Bezier control points use this so the curve leaves the parent
         * heading left and arrives at the child from the left, never
         * crossing the text between them.
         */
        private val RAIL_OFFSET: Double get() = JBUI.scale(24).toDouble()

        // Primary path — bright gold.
        private val PRIMARY_SPINE = JBColor(Color(0xFF8C00), Color(0xFFAA33))
        private val PRIMARY_NODE = JBColor(Color(0xE67E22), Color(0xFFB570))
        private val PRIMARY_LEAF = JBColor(Color(0xD35400), Color(0xFFC880))

        // Alternative branches — muted, darker / cooler gold so the primary
        // pops without making the alternatives look broken.
        private val ALT_SPINE = JBColor(Color(0xA0612C), Color(0xB0834D))
        private val ALT_NODE = JBColor(Color(0x8A5325), Color(0x9C7240))
        private val ALT_LEAF = JBColor(Color(0x754618), Color(0x88643A))
    }
}
