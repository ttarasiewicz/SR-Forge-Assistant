package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Visual style for a polished pipeline block — used by the rendering helpers
 * below so each block type can describe its colours without duplicating the
 * shadow / gradient / border drawing code.
 */
data class PolishedStyle(
    val topColor: Color,
    val bottomColor: Color = topColor,
    val borderColor: Color? = null,
    val leftAccent: Color? = null,
    val cornerRadius: Int = JBUI.scale(12),
    val baseShadowOffset: Int = JBUI.scale(4),
    val maxShadowOffset: Int = JBUI.scale(7),
)

/**
 * Per-block animator handling two things:
 *
 *  1. **Fade-in** (alpha 0 → 1 with ease-out over ~220 ms). Runs once when
 *     the block is first painted, then the timer self-stops.
 *  2. **Hover lift** — animates [shadowOffset] from base → max (and back)
 *     when the cursor enters / leaves the block, over ~120 ms. The block
 *     keeps the hovered state if the cursor moves onto a child component.
 *
 * Each block instance owns one of these. The repaint cost is bounded — at
 * 60 fps for ~220 ms the fade timer fires ~13 times total, and hover
 * animations are one-shot too.
 */
class BlockAnimator(private val component: JComponent, private val style: PolishedStyle) {

    /** Current fade-in alpha (0..1). Apply to `Graphics2D` via AlphaComposite. */
    var fadeAlpha: Float = 0f
        private set

    /** Current drop-shadow vertical offset in pixels. Reads animate on hover. */
    var shadowOffset: Int = style.baseShadowOffset
        private set

    private val fadeStart = System.currentTimeMillis()
    private val fadeTimer: Timer
    private val hoverTimer: Timer
    private var hoverAnimStart = 0L
    private var hoverFrom = style.baseShadowOffset
    private var hoverTo = style.baseShadowOffset

    private val hoverMouseListener = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) = startHoverAnim(style.maxShadowOffset)
        override fun mouseExited(e: MouseEvent) {
            // Don't un-hover if the cursor moved onto a child component;
            // getMousePosition(true) is non-null when the cursor is over
            // the component OR any of its descendants.
            if (component.getMousePosition(true) == null) {
                startHoverAnim(style.baseShadowOffset)
            }
        }
    }

    init {
        fadeTimer = Timer(16) {
            val t = ((System.currentTimeMillis() - fadeStart) / FADE_DURATION_MS.toDouble())
                .coerceIn(0.0, 1.0)
            fadeAlpha = easeOut(t).toFloat()
            component.repaint()
            if (t >= 1.0) (it.source as Timer).stop()
        }.also { it.start() }

        hoverTimer = Timer(16) { tickHover() }

        component.addMouseListener(hoverMouseListener)
    }

    /** Stop all timers and detach listeners. Safe to call more than once. */
    fun dispose() {
        fadeTimer.stop()
        hoverTimer.stop()
        component.removeMouseListener(hoverMouseListener)
    }

    private fun startHoverAnim(target: Int) {
        if (hoverTo == target && !hoverTimer.isRunning) return
        hoverFrom = shadowOffset
        hoverTo = target
        hoverAnimStart = System.currentTimeMillis()
        if (!hoverTimer.isRunning) hoverTimer.start()
    }

    private fun tickHover() {
        val t = ((System.currentTimeMillis() - hoverAnimStart) / HOVER_DURATION_MS.toDouble())
            .coerceIn(0.0, 1.0)
        val eased = easeOut(t)
        shadowOffset = (hoverFrom + (hoverTo - hoverFrom) * eased).toInt()
        component.repaint()
        if (t >= 1.0) {
            shadowOffset = hoverTo
            (hoverTimer).stop()
        }
    }

    private fun easeOut(t: Double): Double {
        val x = 1.0 - t
        return 1.0 - x * x * x
    }

    /** How much extra vertical padding the block needs to host the shadow. */
    val verticalPadding: Int
        get() = style.maxShadowOffset + JBUI.scale(2)

    companion object {
        private const val FADE_DURATION_MS = 220L
        private const val HOVER_DURATION_MS = 120L
    }
}

/**
 * Paint a polished card background — soft 4-layer drop shadow, rounded
 * gradient fill, optional left accent stripe, optional border. Call from
 * the host component's `paintComponent` BEFORE adding children's content;
 * children render on top via the usual Swing paint chain.
 *
 * Coordinates are local to the component (0..width, 0..height).
 */
fun paintPolishedCard(
    g: Graphics,
    width: Int,
    height: Int,
    style: PolishedStyle,
    shadowOffset: Int = style.baseShadowOffset,
) {
    val g2 = (g as Graphics2D).create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val cardHeight = height - shadowOffset
        if (cardHeight <= 0) return

        // ── Soft drop shadow: stack 4 translucent rounded rects each one
        //    pixel inset and one pixel further down. Approximates a small
        //    Gaussian blur at a tiny fraction of the cost.
        for (i in 0 until SHADOW_LAYERS) {
            g2.color = Color(0, 0, 0, shadowAlphaForLayer(i, shadowOffset))
            g2.fillRoundRect(
                i,
                shadowOffset - SHADOW_LAYERS + 1 + i,
                width - 2 * i,
                cardHeight + (SHADOW_LAYERS - 1 - i) * 2,
                style.cornerRadius,
                style.cornerRadius,
            )
        }

        // ── Card fill with vertical gradient.
        if (style.topColor == style.bottomColor) {
            g2.color = style.topColor
        } else {
            g2.paint = GradientPaint(
                0f, 0f, style.topColor,
                0f, cardHeight.toFloat(), style.bottomColor,
            )
        }
        g2.fillRoundRect(0, 0, width, cardHeight, style.cornerRadius, style.cornerRadius)

        // ── Optional left accent stripe — clipped to the card shape so its
        //    corners follow the rounded rect.
        if (style.leftAccent != null) {
            val priorClip = g2.clip
            g2.clip = RoundRectangle2D.Float(
                0f, 0f,
                width.toFloat(), cardHeight.toFloat(),
                style.cornerRadius.toFloat(), style.cornerRadius.toFloat(),
            )
            g2.color = style.leftAccent
            g2.fillRect(0, 0, JBUI.scale(4), cardHeight)
            g2.clip = priorClip
        }

        // ── Border (single hairline at 25% alpha by convention).
        if (style.borderColor != null) {
            g2.color = style.borderColor
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(
                0, 0, width - 1, cardHeight - 1,
                style.cornerRadius, style.cornerRadius,
            )
        }
    } finally {
        g2.dispose()
    }
}

/** Higher shadow layers get lighter alpha; offsets tweak based on lift. */
private fun shadowAlphaForLayer(layer: Int, offset: Int): Int {
    val base = intArrayOf(22, 14, 8, 5)[layer]
    // Slight boost when hovered (offset > base) so the lift feels real.
    val lift = (offset - 4).coerceAtLeast(0)
    return (base + lift).coerceAtMost(60)
}

private const val SHADOW_LAYERS = 4

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
 * Paint a polished connector between two stacked blocks: a vertical line
 * with a small downward arrowhead at the bottom, gradient-coloured.
 *
 * Centres horizontally inside [width]; spans [height] vertically.
 */
/**
 * Paint the polished tool-window header background: a gentle vertical
 * gradient plus a 1 px hairline divider at the bottom for separation from
 * the scrollable content below. Coordinates are local (0..width, 0..height).
 *
 * Designed to be called from a custom `paintComponent` on the header
 * container. In legacy mode the caller should skip calling this entirely
 * and rely on the parent JPanel's default opaque fill.
 */
fun paintPolishedHeaderBackground(g: Graphics, width: Int, height: Int) {
    val g2 = (g as Graphics2D).create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val top = JBColor(Color(0xF7F8FA), Color(0x3C3F41))
        val bottom = JBColor(Color(0xECEFF3), Color(0x35383A))
        g2.paint = GradientPaint(0f, 0f, top, 0f, height.toFloat(), bottom)
        g2.fillRect(0, 0, width, height)

        g2.color = JBColor(Color(0xD8DCE0), Color(0x2B2D2F))
        g2.fillRect(0, height - 1, width, 1)
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

fun paintPolishedConnector(
    g: Graphics,
    width: Int,
    height: Int,
    topColor: Color,
    bottomColor: Color,
) {
    val g2 = (g as Graphics2D).create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val cx = width / 2
        val arrowHeight = JBUI.scale(6)
        val arrowHalfWidth = JBUI.scale(4)
        val lineEnd = height - arrowHeight

        g2.paint = GradientPaint(
            cx.toFloat(), 0f, topColor,
            cx.toFloat(), lineEnd.toFloat(), bottomColor,
        )
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine(cx, 0, cx, lineEnd)

        // Arrowhead — filled triangle pointing down, in the bottom colour.
        g2.color = bottomColor
        val arrow = Polygon(
            intArrayOf(cx - arrowHalfWidth, cx + arrowHalfWidth, cx),
            intArrayOf(lineEnd, lineEnd, lineEnd + arrowHeight),
            3,
        )
        g2.fillPolygon(arrow)
    } finally {
        g2.dispose()
    }
}
