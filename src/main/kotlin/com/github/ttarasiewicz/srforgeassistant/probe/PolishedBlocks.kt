package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Per-block fade-in animator. Each block instance owns one. The timer ticks
 * at ~60 fps for ~220 ms and then self-stops, so the steady-state CPU cost
 * is zero. No hover state — the timeline design has no hover-lift effect.
 */
class BlockAnimator(private val component: JComponent) {

    /** Current fade-in alpha (0..1). Apply to `Graphics2D` via AlphaComposite. */
    var fadeAlpha: Float = 0f
        private set

    private val fadeStart = System.currentTimeMillis()
    private val fadeTimer: Timer = Timer(16) {
        val t = ((System.currentTimeMillis() - fadeStart) / FADE_DURATION_MS.toDouble())
            .coerceIn(0.0, 1.0)
        val x = 1.0 - t
        fadeAlpha = (1.0 - x * x * x).toFloat()
        component.repaint()
        if (t >= 1.0) (it.source as Timer).stop()
    }

    init {
        fadeTimer.start()
    }

    /** Stop the timer. Safe to call more than once. */
    fun dispose() {
        fadeTimer.stop()
    }

    companion object {
        private const val FADE_DURATION_MS = 220L
    }
}

// ── Timeline helpers (used by the polished/modern chrome) ──────────────────

/** Width reserved on the block's left for the timeline gutter (thread + node). */
val TIMELINE_GUTTER: Int get() = JBUI.scale(30)

/** Horizontal center of the timeline thread within the block's local coords. */
val TIMELINE_THREAD_X: Int get() = JBUI.scale(15)

/** Theme-aware neutral colour of the timeline thread. */
val TIMELINE_THREAD_COLOR: JBColor get() = JBColor(Color(0xCBD5E1), Color(0x3F3F46))

/**
 * Paint the vertical thread segment that runs through the block (or
 * connector) for its full height. Call from `paintComponent` after fade
 * alpha has been applied.
 */
fun paintTimelineThread(g: Graphics, top: Int, bottom: Int) {
    val g2 = (g as Graphics2D).create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = TIMELINE_THREAD_COLOR
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine(TIMELINE_THREAD_X, top, TIMELINE_THREAD_X, bottom)
    } finally {
        g2.dispose()
    }
}

/**
 * Paint a node marker at the timeline thread for a block of the given kind.
 *
 *  - DATASET: filled indigo circle, larger diameter.
 *  - STEP: filled zinc circle with the step number rendered inside.
 *  - CACHE: hollow teal ring (optional one-shot flash via pulseAlpha).
 *  - ERROR: filled red circle with a halo that pulses if pulseAlpha > 0.
 *
 * [pulseAlpha] is a 0..1 microinteraction value: 0 = quiescent, 1 = at
 * peak intensity. Used for the error halo loop and cache-hit flash.
 */
fun paintTimelineNode(g: Graphics, cy: Int, kind: BlockKind, stepIndex: Int?, pulseAlpha: Float = 0f) {
    val g2 = (g as Graphics2D).create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val cx = TIMELINE_THREAD_X

        when (kind) {
            BlockKind.DATASET -> {
                val r = JBUI.scale(7)
                g2.color = TIMELINE_NODE_DATASET
                g2.fillOval(cx - r, cy - r, r * 2, r * 2)
                // Inner highlight ring for depth without a hard shadow.
                g2.color = Color(255, 255, 255, 60)
                g2.stroke = BasicStroke(1f)
                g2.drawOval(cx - r + 2, cy - r + 2, (r - 2) * 2, (r - 2) * 2)
            }

            BlockKind.STEP -> {
                val r = JBUI.scale(8)
                g2.color = TIMELINE_NODE_STEP
                g2.fillOval(cx - r, cy - r, r * 2, r * 2)
                if (stepIndex != null) {
                    val label = stepIndex.toString()
                    val font = JBUI.Fonts.smallFont().deriveFont(java.awt.Font.BOLD, JBUI.scale(9).toFloat())
                    g2.font = font
                    g2.color = JBColor(Color.WHITE, Color(0x18181B))
                    val fm = g2.fontMetrics
                    val tw = fm.stringWidth(label)
                    val th = fm.ascent
                    g2.drawString(label, cx - tw / 2, cy + th / 2 - JBUI.scale(1))
                }
            }

            BlockKind.CACHE -> {
                // Optional one-shot teal flash on appearance (pulseAlpha 1→0).
                if (pulseAlpha > 0f) {
                    val haloR = JBUI.scale(12)
                    val flashAlpha = (pulseAlpha * 110).toInt().coerceIn(0, 255)
                    g2.color = Color(TIMELINE_NODE_CACHE.red, TIMELINE_NODE_CACHE.green, TIMELINE_NODE_CACHE.blue, flashAlpha)
                    g2.fillOval(cx - haloR, cy - haloR, haloR * 2, haloR * 2)
                }
                val r = JBUI.scale(6)
                g2.color = TIMELINE_NODE_CACHE
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                g2.drawOval(cx - r, cy - r, r * 2, r * 2)
            }

            BlockKind.ERROR -> {
                // Pulsing halo: base intensity 40/255, +up to +90/255 at peak.
                val haloBase = 40
                val haloAdd = (pulseAlpha * 90).toInt()
                val haloAlpha = (haloBase + haloAdd).coerceIn(0, 255)
                val haloR = JBUI.scale(10) + (pulseAlpha * JBUI.scale(3)).toInt()
                g2.color = Color(TIMELINE_NODE_ERROR.red, TIMELINE_NODE_ERROR.green, TIMELINE_NODE_ERROR.blue, haloAlpha)
                g2.fillOval(cx - haloR, cy - haloR, haloR * 2, haloR * 2)
                val r = JBUI.scale(6)
                g2.color = TIMELINE_NODE_ERROR
                g2.fillOval(cx - r, cy - r, r * 2, r * 2)
            }
        }
    } finally {
        g2.dispose()
    }
}

/**
 * Per-block microinteraction pulse. Drives a 0..1 intensity that the chrome
 * reads in [paintTimelineNode]. Two modes:
 *
 *  - **One-shot** (`loop = false`): starts at 1.0 and decays to 0 over
 *    [durationMs], then stops. Used for cache-hit flash.
 *  - **Loop** (`loop = true`): smooth sin pulse on a [durationMs] cycle.
 *    Used for the persistent error halo.
 */
class BlockPulse(
    private val component: JComponent,
    private val loop: Boolean,
    private val durationMs: Long,
) {
    var intensity: Float = if (loop) 0f else 1f
        private set

    private val start = System.currentTimeMillis()
    private val timer: Timer = Timer(16) { tick() }

    init {
        timer.start()
    }

    private fun tick() {
        val elapsed = System.currentTimeMillis() - start
        if (loop) {
            val phase = (elapsed % durationMs).toDouble() / durationMs
            intensity = (0.5 + 0.5 * kotlin.math.sin(phase * 2 * Math.PI - Math.PI / 2)).toFloat()
        } else {
            val t = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
            val x = 1.0 - t
            intensity = (x * x * x).toFloat()
            if (t >= 1.0) {
                intensity = 0f
                timer.stop()
            }
        }
        component.repaint()
    }

    fun dispose() {
        timer.stop()
    }
}

/** Approximate vertical centre of a block's primary header line. */
fun timelineNodeCenterY(block: PipelineBlock): Int = when (block.kind) {
    BlockKind.DATASET, BlockKind.CACHE -> block.height / 2
    BlockKind.STEP, BlockKind.ERROR -> JBUI.scale(24)
}

private val TIMELINE_NODE_DATASET = JBColor(Color(0x6366F1), Color(0x818CF8))
private val TIMELINE_NODE_STEP = JBColor(Color(0x52525B), Color(0xA1A1AA))
private val TIMELINE_NODE_CACHE = JBColor(Color(0x14B8A6), Color(0x5EEAD4))
private val TIMELINE_NODE_ERROR = JBColor(Color(0xEF4444), Color(0xF87171))

/** Status dot palette for field rows (added/modified/removed). */
val FIELD_DOT_ADDED: JBColor get() = JBColor(Color(0x10B981), Color(0x34D399))
val FIELD_DOT_MODIFIED: JBColor get() = JBColor(Color(0xF59E0B), Color(0xFBBF24))
val FIELD_DOT_REMOVED: JBColor get() = JBColor(Color(0xEF4444), Color(0xF87171))

/**
 * Apply [animator]'s fade-in alpha to the rendering of [component] and its
 * children. Call from the block's overridden `paint(g)` method:
 *
 *     override fun paint(g: Graphics) = applyFade(g, animator) { super.paint(it) }
 */
inline fun applyFade(g: Graphics, animator: BlockAnimator, paint: (Graphics) -> Unit) {
    if (animator.fadeAlpha >= 1f) {
        paint(g)
        return
    }
    val g2 = (g as Graphics2D).create() as Graphics2D
    try {
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, animator.fadeAlpha)
        paint(g2)
    } finally {
        g2.dispose()
    }
}

/**
 * Indeterminate progress bar with a polished look: a rounded translucent
 * track plus a "comet" — a horizontally-sliding alpha-fading gradient that
 * loops continuously. Width-agnostic. Repaints at ~60 fps while it has a
 * parent (Swing Timer stops automatically on `removeNotify`).
 *
 * Instantiated by [PolishedProbeChrome.newProgressBar]; legacy chrome
 * returns a stock `JProgressBar` instead.
 */
class PolishedProgressBar : JComponent() {

    private var phase: Float = 0f
    private val timer: Timer = Timer(16) {
        phase = (phase + PHASE_STEP) % 1f
        repaint()
    }

    init {
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(BAR_HEIGHT))
    }

    override fun addNotify() {
        super.addNotify()
        timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    /** Explicit teardown for callers that hold a reference past removal. */
    fun dispose() {
        timer.stop()
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(BAR_HEIGHT))

    override fun paintComponent(g: Graphics) {
        val g2 = (g as Graphics2D).create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return

            // Translucent track
            g2.color = JBColor(Color(0, 0, 0, 24), Color(255, 255, 255, 24))
            g2.fillRoundRect(0, 0, w, h, h, h)

            // Comet: clip to the rounded track, then paint a horizontal
            // alpha-fade gradient that traverses the bar (with a tail that
            // starts off-screen left and a head that finishes off-screen
            // right so the entry/exit looks continuous).
            val cometWidth = (w * COMET_FRACTION).toInt().coerceAtLeast(JBUI.scale(40))
            val travel = w + cometWidth
            val cometX = (travel * phase).toInt() - cometWidth

            val priorClip = g2.clip
            g2.clip = RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), h.toFloat(), h.toFloat())

            val solid = JBColor(Color(0x2196F3), Color(0x4FC3F7))
            val solidColor = Color(solid.red, solid.green, solid.blue, 220)
            val transparentColor = Color(solid.red, solid.green, solid.blue, 0)
            g2.paint = LinearGradientPaint(
                cometX.toFloat(), 0f,
                (cometX + cometWidth).toFloat(), 0f,
                floatArrayOf(0f, 0.5f, 1f),
                arrayOf(transparentColor, solidColor, transparentColor),
            )
            g2.fillRect(cometX, 0, cometWidth, h)
            g2.clip = priorClip
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val PHASE_STEP = 0.011f      // ~1.5 s per full sweep
        private const val COMET_FRACTION = 0.35f    // width fraction of one comet
        private const val BAR_HEIGHT = 4
    }
}

