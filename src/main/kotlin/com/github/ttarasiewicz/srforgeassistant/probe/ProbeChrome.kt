package com.github.ttarasiewicz.srforgeassistant.probe

import com.github.ttarasiewicz.srforgeassistant.PipelineDisplayMode
import com.github.ttarasiewicz.srforgeassistant.SrForgeHighlightSettings
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
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
import javax.swing.Timer
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
/** Placement options for the dataset block's branch-picker combo. */
enum class BranchPickerPlacement { INLINE, SUBTITLE }

/**
 * Header status widget. Callers update it via [setRunning], [setIdle],
 * [setComplete], [setFailed]. The chrome owns the visual; the panel owns the
 * lifecycle and tells it when state changes.
 */
interface StatusChip {
    val component: JComponent
    fun setIdle()
    fun setRunning(startedAtMs: Long)
    fun setComplete(durationMs: Long)
    fun setFailed(durationMs: Long)
    /** Stop any internal timers; called when the chip is being thrown away. */
    fun dispose() {}
}

sealed class ProbeChrome {

    // ── Block (DATASET / STEP / CACHE / ERROR) ─────────────────────────

    /** Extra bottom padding the caller should bake into the block's border. */
    abstract fun blockExtraBottom(kind: BlockKind): Int

    /**
     * Extra left padding the caller should bake into the block's border, on
     * top of the kind-specific content padding. The polished timeline chrome
     * uses this for the gutter that hosts the thread + node marker; legacy
     * returns 0.
     */
    abstract fun blockExtraLeft(kind: BlockKind): Int

    /**
     * Text foreground colours for content baked into individual blocks.
     * They differ between chromes because legacy paints opaque coloured cards
     * (white-on-blue dataset, dark-teal-on-light-teal cache, etc.) while the
     * polished/timeline chrome paints no card and relies on theme colours.
     */
    abstract fun datasetNameForeground(): Color
    abstract fun datasetTargetForeground(): Color
    abstract fun cacheLabelForeground(): Color

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

    /**
     * Build the visual link between two stacked blocks. Each chrome controls
     * the whole component layout — legacy returns a panel with a vertical
     * line + arrowhead, polished returns a thread continuation aligned with
     * the block-side timeline thread.
     */
    abstract fun newConnector(label: String?): JComponent

    // ── Skipped notice ─────────────────────────────────────────────────

    abstract fun newSkippedNotice(message: String): JPanel

    // ── Traceback toggle ───────────────────────────────────────────────

    /**
     * Add a "Show traceback" toggle to [block] that controls [tracePanel]'s
     * visibility. The chrome is free to pick its preferred layout — legacy
     * uses a single label, polished uses a chevron + label row.
     */
    abstract fun installTracebackToggle(block: JPanel, tracePanel: JPanel)

    // ── New: information design + microinteractions ───────────────────

    /** Pills/text summarising the diff totals on a step header. */
    abstract fun newSummaryBadges(diffs: List<FieldDiff>): JComponent

    /** A status chip shown in the tool window header (idle / running / etc). */
    abstract fun newStatusChip(): StatusChip

    /** A "no probe yet" empty-state widget shown in the content area. */
    abstract fun newEmptyState(onPickDataset: () -> Unit): JComponent

    /** Whether to hide UNCHANGED fields by default with a "show all" toggle. */
    abstract val hideUnchangedFieldsByDefault: Boolean

    /**
     * Whether to render simple scalar modifications as a single inline row
     * (`name  before → after`) instead of an expandable After:/Before: pair.
     */
    abstract val inlineScalarDiff: Boolean

    /**
     * Branch-picker placement on the dataset block.
     * - INLINE: combo on the EAST side of the dataset header (legacy).
     * - SUBTITLE: combo on a second line below the dataset name (polished).
     */
    abstract val datasetBranchPickerPlacement: BranchPickerPlacement

    /** Whether toolbar buttons render icon-only with tooltips. */
    abstract val iconOnlyToolbarButtons: Boolean

    /**
     * Apply chrome-specific microinteractions to a freshly-installed block.
     * Polished emits a brief pulse on ERROR nodes and a flash on CACHE nodes;
     * legacy is a no-op. Called from [installBlock] after the animator is set.
     */
    open fun installMicrointeractions(block: PipelineBlock) {}

    // ── Misc layout constants ──────────────────────────────────────────

    /** Horizontal gap in the step-block's "leftHeader" FlowLayout. */
    abstract val stepLeftHeaderHgap: Int

    companion object {
        /** Currently-active chrome. Mutated only by [ProbeToolWindowPanel] on settings change. */
        @Volatile
        var current: ProbeChrome = forCurrentMode()
            private set

        /**
         * Resolve the active chrome from settings.
         *
         * The polished/timeline chrome is currently work-in-progress and
         * not yet ready for users — we deliberately short-circuit to
         * [LegacyProbeChrome] regardless of the stored display-mode value
         * so existing settings (and the entire polished codepath) stay
         * intact, but no user ever sees the unfinished view. When polished
         * is ready, restore the original `when` over [PipelineDisplayMode].
         */
        fun forCurrentMode(): ProbeChrome {
            @Suppress("UNUSED_VARIABLE")
            val storedMode = SrForgeHighlightSettings.getInstance().state.pipelineDisplayMode
            return LegacyProbeChrome
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

    override fun blockExtraLeft(kind: BlockKind): Int = 0

    override fun datasetNameForeground(): Color = Color.WHITE
    override fun datasetTargetForeground(): Color = JBColor(Color(0xBBDEFB), Color(0x90CAF9))
    override fun cacheLabelForeground(): Color = JBColor(Color(0x004D40), Color(0xB2DFDB))

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

    override fun newConnector(label: String?): JComponent {
        val connector = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            border = JBUI.Borders.empty(2, 0)
        }

        connector.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(24)))

        connector.add(object : JComponent() {
            override fun getPreferredSize() = Dimension(JBUI.scale(20), JBUI.scale(24))
            override fun getMinimumSize() = preferredSize
            override fun getMaximumSize() = preferredSize
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor.border()
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                val cx = width / 2
                g2.drawLine(cx, 0, cx, height - JBUI.scale(6))
                val ay = height - JBUI.scale(2)
                val aw = JBUI.scale(4)
                g2.fillPolygon(
                    intArrayOf(cx - aw, cx, cx + aw),
                    intArrayOf(ay - aw * 2, ay, ay - aw * 2),
                    3,
                )
            }
        })

        if (label != null) {
            connector.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(4)))
            connector.add(JBLabel(label).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(Font.ITALIC, font.size - 1f)
            })
        }
        return connector
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

    // ── New behaviours (preserve historical 0.4.4 visuals) ───────────

    override fun newSummaryBadges(diffs: List<FieldDiff>): JComponent {
        val summaryText = buildSummaryText(diffs)
        return JBLabel(summaryText).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(font.size - 1f)
            border = JBUI.Borders.emptyRight(4)
            isVisible = summaryText.isNotEmpty()
        }
    }

    override fun newStatusChip(): StatusChip = LegacyStatusChip()

    override fun newEmptyState(onPickDataset: () -> Unit): JComponent =
        JBLabel("No probe results yet.").apply {
            foreground = UIUtil.getInactiveTextColor()
        }

    override val hideUnchangedFieldsByDefault: Boolean = false
    override val inlineScalarDiff: Boolean = false
    override val datasetBranchPickerPlacement: BranchPickerPlacement = BranchPickerPlacement.INLINE
    override val iconOnlyToolbarButtons: Boolean = false
}

// ════════════════════════════════════════════════════════════════════════
//  POLISHED — modern vertical timeline. A single thread runs the entire
//  length of the pipeline with circular node markers at each block.
//  No cards, no shadows, no hover-lift — depth comes from typography,
//  whitespace, and the strong vertical thread metaphor.
// ════════════════════════════════════════════════════════════════════════

object PolishedProbeChrome : ProbeChrome() {

    /** No drop shadow → no extra bottom padding needed. */
    override fun blockExtraBottom(kind: BlockKind): Int = 0

    /** Reserve room on the left for the timeline thread + node marker. */
    override fun blockExtraLeft(kind: BlockKind): Int = TIMELINE_GUTTER

    /** Theme-aware indigo, matching the dataset node marker on the thread. */
    override fun datasetNameForeground(): Color = JBColor(Color(0x4338CA), Color(0xC7D2FE))
    override fun datasetTargetForeground(): Color = UIUtil.getInactiveTextColor()
    /** Theme-aware teal, matching the cache node ring on the thread. */
    override fun cacheLabelForeground(): Color = JBColor(Color(0x0F766E), Color(0x5EEAD4))

    override fun installBlock(block: PipelineBlock) {
        block.animator = BlockAnimator(block)
        installMicrointeractions(block)
    }

    override fun uninstallBlock(block: PipelineBlock) {
        block.animator?.dispose()
        block.animator = null
        block.pulse?.dispose()
        block.pulse = null
    }

    override fun paintBlockBackground(block: PipelineBlock, g: Graphics) {
        // No card fill. The timeline thread + node marker are the entire chrome.
        paintTimelineThread(g, 0, block.height)
        val pulseAlpha = block.pulse?.intensity ?: 0f
        paintTimelineNode(g, timelineNodeCenterY(block), block.kind, block.stepIndex, pulseAlpha)
    }

    override fun paintBlock(block: PipelineBlock, g: Graphics, paintSuper: (Graphics) -> Unit) {
        val animator = block.animator
        if (animator != null) applyFade(g, animator) { paintSuper(it) } else paintSuper(g)
    }

    override fun installFieldCard(card: FieldCard) {
        // No card chrome — fully transparent so the surrounding step bg shows.
        card.isOpaque = false
    }

    override fun uninstallFieldCard(card: FieldCard) {
        // No animators or listeners to tear down.
    }

    override fun paintFieldCardBackground(card: FieldCard, g: Graphics) {
        // Paint a small status dot at the left of the row for non-UNCHANGED
        // fields. No background fill, no accent stripe — minimal, typographic.
        val dotColor = when (card.status) {
            FieldDiffStatus.ADDED -> FIELD_DOT_ADDED
            FieldDiffStatus.MODIFIED -> FIELD_DOT_MODIFIED
            FieldDiffStatus.REMOVED -> FIELD_DOT_REMOVED
            FieldDiffStatus.UNCHANGED -> return
        }
        val g2 = (g as Graphics2D).create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = dotColor
            val r = JBUI.scale(3)
            val cx = JBUI.scale(4)
            val cy = JBUI.scale(13)  // aligns with first text line in header
            g2.fillOval(cx - r, cy - r, r * 2, r * 2)
        } finally {
            g2.dispose()
        }
    }

    override fun fieldHeaderLegacyBg(status: FieldDiffStatus): Color? = null
    override fun fieldChildLegacyBg(status: FieldDiffStatus): Color? = null

    /** Field-row inset on the left makes room for the status dot. */
    override val fieldHeaderPadding: Border = JBUI.Borders.empty(3, 14, 3, 8)

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

    /** Minimal header bar — single hairline divider at the bottom, no gradient. */
    override val headerBorder: Border = JBUI.Borders.empty(10, 14, 10, 10)
    override val isHeaderOpaque: Boolean = false

    override fun paintHeaderBackground(g: Graphics, w: Int, h: Int): Boolean {
        val g2 = (g as Graphics2D).create() as Graphics2D
        try {
            g2.color = TIMELINE_THREAD_COLOR
            g2.fillRect(0, h - 1, w, 1)
        } finally {
            g2.dispose()
        }
        return true
    }

    override fun applyHeaderLabelStyle(label: JBLabel) {
        // Medium weight, no upsize — clean and quiet.
        val base = UIUtil.getLabelFont()
        label.font = base.deriveFont(Font.BOLD)
    }

    override fun applyButtonStyle(button: JButton) {
        button.putClientProperty("JButton.buttonType", "roundRect")
    }

    /**
     * Build a connector that simply continues the timeline thread. No
     * arrowheads — the visual flow IS the thread. Optional label appears
     * to the right with generous spacing.
     */
    override fun newConnector(label: String?): JComponent {
        val connector = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                paintTimelineThread(g, 0, height)
            }
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(0, JBUI.scale(20))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
        }
        if (label != null) {
            connector.add(JBLabel(label).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(Font.ITALIC, font.size - 1f)
                border = JBUI.Borders.emptyLeft(TIMELINE_GUTTER + JBUI.scale(6))
            }, BorderLayout.WEST)
        }
        return connector
    }

    /** Skipped notice — a small inline label, no decorative card. */
    override fun newSkippedNotice(message: String): JPanel {
        val notice = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, TIMELINE_GUTTER + JBUI.scale(6), 4, 14)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        }
        notice.add(JBLabel(AllIcons.General.Warning))
        notice.add(JBLabel(message).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(Font.ITALIC, font.size - 1f)
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

    // ── New behaviours ────────────────────────────────────────────────

    override fun newSummaryBadges(diffs: List<FieldDiff>): JComponent {
        val added = diffs.count { it.status == FieldDiffStatus.ADDED }
        val removed = diffs.count { it.status == FieldDiffStatus.REMOVED }
        val modified = diffs.count { it.status == FieldDiffStatus.MODIFIED }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(4)
        }
        if (added > 0) row.add(makeStatusPill("+$added", FIELD_DOT_ADDED))
        if (modified > 0) row.add(makeStatusPill("~$modified", FIELD_DOT_MODIFIED))
        if (removed > 0) row.add(makeStatusPill("-$removed", FIELD_DOT_REMOVED))
        return row
    }

    override fun newStatusChip(): StatusChip = PolishedStatusChip()

    override fun newEmptyState(onPickDataset: () -> Unit): JComponent = PolishedEmptyState(onPickDataset)

    override val hideUnchangedFieldsByDefault: Boolean = true
    override val inlineScalarDiff: Boolean = true
    override val datasetBranchPickerPlacement: BranchPickerPlacement = BranchPickerPlacement.SUBTITLE
    override val iconOnlyToolbarButtons: Boolean = true

    /**
     * Polished microinteractions: a soft pulse on ERROR nodes (~1.5s loop)
     * and a one-shot teal flash on CACHE nodes when they appear. The
     * underlying [BlockPulse] handles all the timer plumbing and is
     * disposed by [uninstallBlock] alongside the fade-in animator.
     */
    override fun installMicrointeractions(block: PipelineBlock) {
        when (block.kind) {
            BlockKind.ERROR -> block.pulse = BlockPulse(block, loop = true, durationMs = 1500)
            BlockKind.CACHE -> block.pulse = BlockPulse(block, loop = false, durationMs = 700)
            else -> {}
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  Shared helpers used by the chrome implementations
// ════════════════════════════════════════════════════════════════════════

/** Tally the diff counts into the legacy compact text form ("+1 -2 ~3"). */
internal fun buildSummaryText(diffs: List<FieldDiff>): String {
    val added = diffs.count { it.status == FieldDiffStatus.ADDED }
    val removed = diffs.count { it.status == FieldDiffStatus.REMOVED }
    val modified = diffs.count { it.status == FieldDiffStatus.MODIFIED }
    val parts = mutableListOf<String>()
    if (added > 0) parts.add("+$added")
    if (removed > 0) parts.add("-$removed")
    if (modified > 0) parts.add("~$modified")
    return parts.joinToString(" ")
}

/** A small pill (rounded background + bold text in [color]). */
internal fun makeStatusPill(text: String, color: JBColor): JComponent {
    val pill = object : JBLabel(text) {
        override fun paintComponent(g: Graphics) {
            val g2 = (g as Graphics2D).create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(color.red, color.green, color.blue, 36)
                g2.fillRoundRect(0, 0, width, height, height, height)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
    pill.foreground = color
    pill.font = pill.font.deriveFont(java.awt.Font.BOLD, pill.font.size - 1f)
    pill.border = JBUI.Borders.empty(1, 8, 1, 8)
    pill.isOpaque = false
    return pill
}

/** Legacy status chip: a plain JBLabel matching the historical headerLabel UX. */
internal class LegacyStatusChip : StatusChip {
    private val label = JBLabel("")

    override val component: JComponent get() = label
    override fun setIdle() { label.text = ""; label.icon = null }
    override fun setRunning(startedAtMs: Long) { label.text = "" }
    override fun setComplete(durationMs: Long) { label.text = "" }
    override fun setFailed(durationMs: Long) { label.text = "" }
}

/**
 * Polished status chip: a coloured pill with an elapsed-time counter for
 * the running state. The pill background is tinted to the state colour;
 * a 200 ms-tick timer keeps the time fresh while running.
 */
internal class PolishedStatusChip : StatusChip {

    private enum class State { IDLE, RUNNING, COMPLETE, FAILED }

    private var state: State = State.IDLE
    private var runStartedAt: Long = 0
    private var runDurationMs: Long = 0

    private val pill = object : JBLabel("Idle") {
        override fun paintComponent(g: Graphics) {
            val (text, fg, bg) = renderingFor(state)
            this.text = if (state == State.RUNNING) {
                "$text  ${formatElapsed(System.currentTimeMillis() - runStartedAt)}"
            } else if (state == State.COMPLETE || state == State.FAILED) {
                "$text  ${formatElapsed(runDurationMs)}"
            } else text
            this.foreground = fg

            val g2 = (g as Graphics2D).create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(bg.red, bg.green, bg.blue, 40)
                g2.fillRoundRect(0, 0, width, height, height, height)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }.apply {
        font = font.deriveFont(java.awt.Font.BOLD, font.size - 1f)
        border = JBUI.Borders.empty(2, 10, 2, 10)
        isOpaque = false
    }

    private val timer: Timer = Timer(200) { pill.repaint() }

    override val component: JComponent get() = pill

    override fun setIdle() {
        state = State.IDLE
        timer.stop()
        pill.repaint()
    }

    override fun setRunning(startedAtMs: Long) {
        state = State.RUNNING
        runStartedAt = startedAtMs
        if (!timer.isRunning) timer.start()
        pill.repaint()
    }

    override fun setComplete(durationMs: Long) {
        state = State.COMPLETE
        runDurationMs = durationMs
        timer.stop()
        pill.repaint()
    }

    override fun setFailed(durationMs: Long) {
        state = State.FAILED
        runDurationMs = durationMs
        timer.stop()
        pill.repaint()
    }

    override fun dispose() {
        timer.stop()
    }

    private fun renderingFor(s: State): Triple<String, JBColor, JBColor> = when (s) {
        State.IDLE -> Triple("Idle", UIUtil.getInactiveTextColor() as? JBColor ?: JBColor(Color(0x71717A), Color(0xA1A1AA)), JBColor(Color(0x71717A), Color(0xA1A1AA)))
        State.RUNNING -> Triple("Running", JBColor(Color(0x2563EB), Color(0x60A5FA)), JBColor(Color(0x2563EB), Color(0x60A5FA)))
        State.COMPLETE -> Triple("Complete", JBColor(Color(0x059669), Color(0x34D399)), JBColor(Color(0x059669), Color(0x34D399)))
        State.FAILED -> Triple("Failed", JBColor(Color(0xDC2626), Color(0xF87171)), JBColor(Color(0xDC2626), Color(0xF87171)))
    }

    private fun formatElapsed(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(ms / 1000.0)
        else -> {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            "%d:%02d".format(min, sec)
        }
    }
}

/**
 * Polished empty-state widget: a centred icon, a one-line tagline, and a
 * primary action button. Shown when the probe panel has no results yet.
 */
internal class PolishedEmptyState(private val onPickDataset: () -> Unit) : JPanel() {
    init {
        layout = java.awt.GridBagLayout()
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT

        val inner = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val icon = JBLabel(ProbeIcons.Probe).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val title = JBLabel("No probe yet").apply {
            font = font.deriveFont(java.awt.Font.BOLD, font.size + 4f)
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.emptyTop(JBUI.scale(12))
        }

        val subtitle = JBLabel("Pick a dataset from the open YAML to trace its pipeline").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.emptyTop(JBUI.scale(4))
        }

        val cta = JButton("Pick dataset").apply {
            putClientProperty("JButton.buttonType", "roundRect")
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener { onPickDataset() }
        }
        cta.maximumSize = cta.preferredSize
        val ctaWrap = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
            border = JBUI.Borders.emptyTop(JBUI.scale(14))
            add(cta)
        }

        inner.add(icon)
        inner.add(title)
        inner.add(subtitle)
        inner.add(ctaWrap)

        add(inner)  // GridBag centres a single child by default
    }
}
