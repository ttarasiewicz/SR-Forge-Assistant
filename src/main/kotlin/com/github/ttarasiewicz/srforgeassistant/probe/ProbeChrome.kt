package com.github.ttarasiewicz.srforgeassistant.probe

import com.github.ttarasiewicz.srforgeassistant.PipelineDisplayMode
import com.github.ttarasiewicz.srforgeassistant.SrForgeHighlightSettings
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.border.Border

/**
 * Visual chrome strategy for the Pipeline Probe tool window. Every decision
 * that differs between display modes — paint, padding, animation lifecycle,
 * widget factory choices — lives behind this sealed hierarchy. Callers
 * never branch on the current [PipelineDisplayMode]; they ask the chrome.
 *
 * To add a new visual style:
 *   1. Add an entry to [PipelineDisplayMode].
 *   2. Add a new `object` (or class) subclass below.
 *   3. Wire it in [forCurrentMode].
 *
 * No other file needs to change.
 *
 * ## Hot-swap
 *
 * The chrome is intentionally stateless — per-component state (animators,
 * hover floats) is parked on the component itself via [installBlock] /
 * [installFieldCard] / etc. and torn down via the matching `uninstall*`
 * methods. This lets [ProbeToolWindowPanel] swap the chrome at runtime: it
 * uninstalls the old chrome from every live component, swaps the [current]
 * reference, then installs the new chrome.
 */
sealed class ProbeChrome {

    // ── Block (DATASET / STEP / CACHE / ERROR) ─────────────────────────

    /** Extra bottom padding the caller should bake into the block's border. */
    abstract fun blockExtraBottom(kind: BlockKind): Int

    /** Install per-instance chrome state (animator, listeners) on a block. */
    abstract fun installBlock(block: PipelineBlock)

    /** Tear down per-instance chrome state (stop timers, remove listeners). */
    abstract fun uninstallBlock(block: PipelineBlock)

    /** Paint the block's background (called from `paintComponent`). */
    abstract fun paintBlockBackground(block: PipelineBlock, g: Graphics)

    /**
     * Wrap the block's full paint chain (children included). Polished mode
     * uses this for the fade-in alpha; legacy mode just paints normally.
     */
    open fun paintBlock(block: PipelineBlock, g: Graphics, paintSuper: (Graphics) -> Unit) {
        paintSuper(g)
    }

    // ── Field card (one row in FieldDetailPanel) ───────────────────────

    abstract fun installFieldCard(card: FieldCard)
    abstract fun uninstallFieldCard(card: FieldCard)
    abstract fun paintFieldCardBackground(card: FieldCard, g: Graphics)

    /** Background colour for the per-row header in legacy mode; null in polished. */
    abstract fun fieldHeaderLegacyBg(status: FieldDiffStatus): Color?

    /** Background colour for nested child panels in legacy mode; null in polished. */
    abstract fun fieldChildLegacyBg(status: FieldDiffStatus): Color?

    /** Left/right/top/bottom inset of the per-row header content. */
    abstract val fieldHeaderPadding: Border

    // ── Chevron toggle ─────────────────────────────────────────────────

    /**
     * Factory for a chevron toggle. Returns a [JComponent] — either an
     * `AnimatedChevron` (polished) or a `JBLabel("▾"/"▸")` (legacy). Use
     * [setChevronExpanded] to flip its state polymorphically.
     */
    abstract fun newChevron(
        initialExpanded: Boolean = false,
        legacyFont: Font? = null,
        legacyRightPadding: Int = 0,
    ): JComponent

    abstract fun setChevronExpanded(chevron: JComponent, expanded: Boolean)

    // ── Progress bar ───────────────────────────────────────────────────

    abstract fun newProgressBar(): JComponent
    abstract fun disposeProgressBar(bar: JComponent)

    // ── Header bar ─────────────────────────────────────────────────────

    abstract val headerBorder: Border
    abstract val isHeaderOpaque: Boolean

    /** Returns `true` if the chrome painted its own background; `false` to fall back to default. */
    abstract fun paintHeaderBackground(g: Graphics, w: Int, h: Int): Boolean
    abstract fun applyHeaderLabelStyle(label: JBLabel)

    // ── Toolbar button ─────────────────────────────────────────────────

    abstract fun applyButtonStyle(button: JButton)

    // ── Connector ──────────────────────────────────────────────────────

    abstract fun paintConnector(g: Graphics, w: Int, h: Int)

    // ── Skipped notice ─────────────────────────────────────────────────

    abstract fun newSkippedNotice(message: String): JPanel

    // ── Traceback toggle ───────────────────────────────────────────────

    /**
     * Add a "Show traceback" toggle to [block] that controls [tracePanel]'s
     * visibility. The chrome is free to pick its preferred layout — legacy
     * uses a single label, polished uses a chevron + label row.
     */
    abstract fun installTracebackToggle(block: JPanel, tracePanel: JPanel)

    // ── Misc layout constants ──────────────────────────────────────────

    /** Horizontal gap in the step-block's "leftHeader" FlowLayout. */
    abstract val stepLeftHeaderHgap: Int

    companion object {
        /** Currently-active chrome. Mutated only by [ProbeToolWindowPanel] on settings change. */
        @Volatile
        var current: ProbeChrome = forCurrentMode()
            private set

        fun forCurrentMode(): ProbeChrome = when (
            SrForgeHighlightSettings.getInstance().state.pipelineDisplayMode
        ) {
            PipelineDisplayMode.LEGACY -> LegacyProbeChrome
            PipelineDisplayMode.POLISHED -> PolishedProbeChrome
        }

        /** Refresh [current] from settings. Returns true if it changed. */
        fun refresh(): Boolean {
            val next = forCurrentMode()
            if (next === current) return false
            current = next
            return true
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  LEGACY — preserves byte-for-byte parity with the 0.4.4 baseline.
// ════════════════════════════════════════════════════════════════════════

object LegacyProbeChrome : ProbeChrome() {

    override fun blockExtraBottom(kind: BlockKind): Int = 0

    override fun installBlock(block: PipelineBlock) {
        // No animator, no listeners.
    }

    override fun uninstallBlock(block: PipelineBlock) {}

    override fun paintBlockBackground(block: PipelineBlock, g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = block.kind.legacyBg
        g2.fillRoundRect(0, 0, block.width, block.height, JBUI.scale(8), JBUI.scale(8))
        val border = block.kind.legacyBorder
        if (border != null) {
            g2.color = border
            g2.drawRoundRect(0, 0, block.width - 1, block.height - 1, JBUI.scale(8), JBUI.scale(8))
        }
    }

    override fun installFieldCard(card: FieldCard) {
        // Default JPanel opacity + L&F-default background (no explicit
        // `background = …` setting) matches the 0.4.4 outer JPanel exactly:
        // `super.paintComponent` fills uncovered areas (notably the header
        // of UNCHANGED rows) with the panel-default grey.
        card.isOpaque = true
        card.border = BorderFactory.createMatteBorder(
            0, JBUI.scale(3), 0, 0, FieldDetailPanel.diffBorderColor(card.status),
        )
    }

    override fun uninstallFieldCard(card: FieldCard) {
        card.border = null
    }

    override fun paintFieldCardBackground(card: FieldCard, g: Graphics) {
        // Legacy: super.paintComponent already filled with the L&F default;
        // the matte-left border handles the colored stripe; nothing else needed.
    }

    override fun fieldHeaderLegacyBg(status: FieldDiffStatus): Color? =
        FieldDetailPanel.diffBackground(status)

    override fun fieldChildLegacyBg(status: FieldDiffStatus): Color? =
        FieldDetailPanel.diffBackground(status)

    override val fieldHeaderPadding: Border = JBUI.Borders.empty(3, 8, 3, 8)

    override fun newChevron(
        initialExpanded: Boolean,
        legacyFont: Font?,
        legacyRightPadding: Int,
    ): JComponent = JBLabel(if (initialExpanded) "▾" else "▸").apply {
        foreground = UIUtil.getInactiveTextColor()
        if (legacyFont != null) font = legacyFont
        if (legacyRightPadding > 0) border = JBUI.Borders.emptyRight(legacyRightPadding)
    }

    override fun setChevronExpanded(chevron: JComponent, expanded: Boolean) {
        if (chevron is JBLabel) chevron.text = if (expanded) "▾" else "▸"
    }

    override fun newProgressBar(): JComponent = JProgressBar().apply {
        isIndeterminate = true
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(3))
        preferredSize = Dimension(0, JBUI.scale(3))
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun disposeProgressBar(bar: JComponent) {
        // Stock JProgressBar has no Swing Timer of ours to stop.
    }

    override val headerBorder: Border = JBUI.Borders.empty(6, 12, 6, 8)
    override val isHeaderOpaque: Boolean = true

    override fun paintHeaderBackground(g: Graphics, w: Int, h: Int): Boolean = false

    override fun applyHeaderLabelStyle(label: JBLabel) {
        // Reset to L&F default so a previous chrome's font doesn't leak.
        label.font = UIUtil.getLabelFont()
    }

    override fun applyButtonStyle(button: JButton) {
        // Clear any client property a previous chrome may have set.
        button.putClientProperty("JButton.buttonType", null)
    }

    override fun paintConnector(g: Graphics, w: Int, h: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor.border()
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
        val cx = w / 2
        g2.drawLine(cx, 0, cx, h - JBUI.scale(6))
        val ay = h - JBUI.scale(2)
        val aw = JBUI.scale(4)
        g2.fillPolygon(
            intArrayOf(cx - aw, cx, cx + aw),
            intArrayOf(ay - aw * 2, ay, ay - aw * 2),
            3,
        )
    }

    override fun newSkippedNotice(message: String): JPanel {
        val notice = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 14)
        }
        notice.add(JBLabel(AllIcons.General.Warning).apply {
            border = JBUI.Borders.emptyRight(2)
        })
        notice.add(JBLabel(message).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(Font.ITALIC)
        })
        return notice
    }

    override fun installTracebackToggle(block: JPanel, tracePanel: JPanel) {
        val toggleLabel = JBLabel("Show traceback ▸").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(font.size - 1f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        toggleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                tracePanel.isVisible = !tracePanel.isVisible
                toggleLabel.text = if (tracePanel.isVisible) "Hide traceback ▾" else "Show traceback ▸"
                block.revalidate()
                block.repaint()
            }
        })
        block.add(toggleLabel)
    }

    override val stepLeftHeaderHgap: Int = 0
}

// ════════════════════════════════════════════════════════════════════════
//  POLISHED — gradient cards, drop shadows, hover lift, fade-in,
//  animated chevrons, gradient progress comet, the works.
// ════════════════════════════════════════════════════════════════════════

object PolishedProbeChrome : ProbeChrome() {

    override fun blockExtraBottom(kind: BlockKind): Int =
        kind.polishedStyle.maxShadowOffset + JBUI.scale(2)

    override fun installBlock(block: PipelineBlock) {
        block.animator = BlockAnimator(block, block.kind.polishedStyle)
    }

    override fun uninstallBlock(block: PipelineBlock) {
        block.animator?.dispose()
        block.animator = null
    }

    override fun paintBlockBackground(block: PipelineBlock, g: Graphics) {
        val animator = block.animator ?: return
        paintPolishedCard(g, block.width, block.height, block.kind.polishedStyle, animator.shadowOffset)
    }

    override fun paintBlock(block: PipelineBlock, g: Graphics, paintSuper: (Graphics) -> Unit) {
        val animator = block.animator
        if (animator != null) applyFade(g, animator) { paintSuper(it) } else paintSuper(g)
    }

    override fun installFieldCard(card: FieldCard) {
        card.isOpaque = false  // we paint our own rounded card on a transparent base
    }

    override fun uninstallFieldCard(card: FieldCard) {
        // Nothing to tear down — no animators or listeners on field cards.
    }

    override fun paintFieldCardBackground(card: FieldCard, g: Graphics) {
        val g2 = (g as Graphics2D).create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val radius = JBUI.scale(8)
            val statusBg = FieldDetailPanel.diffBackground(card.status) ?: UIUtil.getPanelBackground()
            g2.color = statusBg
            g2.fillRoundRect(0, 0, card.width, card.height, radius, radius)

            val priorClip = g2.clip
            g2.clip = RoundRectangle2D.Float(
                0f, 0f, card.width.toFloat(), card.height.toFloat(),
                radius.toFloat(), radius.toFloat(),
            )
            g2.color = FieldDetailPanel.diffBorderColor(card.status)
            g2.fillRect(0, 0, JBUI.scale(3), card.height)
            g2.clip = priorClip
        } finally {
            g2.dispose()
        }
    }

    override fun fieldHeaderLegacyBg(status: FieldDiffStatus): Color? = null
    override fun fieldChildLegacyBg(status: FieldDiffStatus): Color? = null

    override val fieldHeaderPadding: Border = JBUI.Borders.empty(3, 10, 3, 8)

    override fun newChevron(
        initialExpanded: Boolean,
        legacyFont: Font?,
        legacyRightPadding: Int,
    ): JComponent = AnimatedChevron().apply { expanded = initialExpanded }

    override fun setChevronExpanded(chevron: JComponent, expanded: Boolean) {
        if (chevron is AnimatedChevron) chevron.expanded = expanded
    }

    override fun newProgressBar(): JComponent =
        PolishedProgressBar().apply { alignmentX = Component.LEFT_ALIGNMENT }

    override fun disposeProgressBar(bar: JComponent) {
        if (bar is PolishedProgressBar) bar.dispose()
    }

    override val headerBorder: Border = JBUI.Borders.empty(8, 12, 8, 8)
    override val isHeaderOpaque: Boolean = false

    override fun paintHeaderBackground(g: Graphics, w: Int, h: Int): Boolean {
        paintPolishedHeaderBackground(g, w, h)
        return true
    }

    override fun applyHeaderLabelStyle(label: JBLabel) {
        val base = UIUtil.getLabelFont()
        label.font = base.deriveFont(Font.BOLD, base.size + 1f)
    }

    override fun applyButtonStyle(button: JButton) {
        button.putClientProperty("JButton.buttonType", "roundRect")
    }

    override fun paintConnector(g: Graphics, w: Int, h: Int) {
        paintPolishedConnector(
            g, w, h,
            topColor = JBColor(Color(0xB0BEC5), Color(0x5C6770)),
            bottomColor = JBColor(Color(0x546E7A), Color(0x90A4AE)),
        )
    }

    override fun newSkippedNotice(message: String): JPanel {
        val notice = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = (g as Graphics2D).create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val radius = JBUI.scale(8)
                    g2.color = JBColor(Color(0xFFF8E1), Color(0x3D3520))
                    g2.fillRoundRect(0, 0, width, height, radius, radius)
                    val priorClip = g2.clip
                    g2.clip = RoundRectangle2D.Float(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        radius.toFloat(), radius.toFloat(),
                    )
                    g2.color = JBColor(Color(0xFFA000), Color(0xFFCA28))
                    g2.fillRect(0, 0, JBUI.scale(3), height)
                    g2.clip = priorClip
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 12, 8, 12)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }
        notice.add(JBLabel(AllIcons.General.Warning))
        notice.add(JBLabel(message).apply {
            foreground = JBColor(Color(0x8B6A00), Color(0xFFD54F))
            font = font.deriveFont(Font.PLAIN)
        })
        return notice
    }

    override fun installTracebackToggle(block: JPanel, tracePanel: JPanel) {
        val toggleChevron = AnimatedChevron()
        val toggleText = JBLabel("Show traceback").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(font.size - 1f)
        }
        val toggleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(toggleChevron)
            add(toggleText)
        }
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                tracePanel.isVisible = !tracePanel.isVisible
                toggleChevron.expanded = tracePanel.isVisible
                toggleText.text = if (tracePanel.isVisible) "Hide traceback" else "Show traceback"
                block.revalidate()
                block.repaint()
            }
        }
        toggleRow.addMouseListener(listener)
        toggleChevron.addMouseListener(listener)
        toggleText.addMouseListener(listener)
        block.add(toggleRow)
    }

    override val stepLeftHeaderHgap: Int = JBUI.scale(6)
}
