package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Which side of a diff to display in a snapshot section.
 */
private enum class DiffSide { AFTER, BEFORE }

/**
 * Displays fields as individual expandable blocks with color-coded borders.
 * All blocks start collapsed; clicking the header expands.
 */
class FieldDetailPanel(private val diffs: List<FieldDiff>, private val project: Project) : JPanel() {

    /** Chrome bound at construction. Recreated panels get the fresh chrome. */
    private val chrome: ProbeChrome = ProbeChrome.current

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        val (visibleDiffs, hiddenCount) = partitionDiffs(diffs)
        for (diff in visibleDiffs) {
            add(createFieldBlock(diff))
            add(Box.createVerticalStrut(JBUI.scale(3)))
        }
        if (hiddenCount > 0) {
            add(createShowUnchangedToggle(hiddenCount))
        }
    }

    /**
     * Split [diffs] into the rows the chrome wants visible and a count of
     * what's stashed away. Polished chrome hides UNCHANGED rows behind a
     * "show all" toggle to keep the view focused on actual changes.
     */
    private fun partitionDiffs(diffs: List<FieldDiff>): Pair<List<FieldDiff>, Int> {
        if (!chrome.hideUnchangedFieldsByDefault) return diffs to 0
        val visible = diffs.filter { it.status != FieldDiffStatus.UNCHANGED }
        val hiddenCount = diffs.size - visible.size
        // If everything is unchanged, fall back to showing them — an empty
        // step view (with only a "show 3 unchanged" toggle) feels broken.
        if (visible.isEmpty()) return diffs to 0
        return visible to hiddenCount
    }

    /**
     * Returns a small clickable label that, when clicked, expands all hidden
     * UNCHANGED rows in place above itself. After expansion the toggle hides.
     */
    private fun createShowUnchangedToggle(hiddenCount: Int): JComponent {
        val label = JBLabel("Show $hiddenCount unchanged").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(Font.ITALIC, font.size - 1f)
            border = JBUI.Borders.empty(4, 14, 4, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val toggleIndex = (0 until componentCount).firstOrNull { getComponent(it) === label } ?: return
                // Insert hidden UNCHANGED diffs just before the toggle.
                val unchanged = diffs.filter { it.status == FieldDiffStatus.UNCHANGED }
                var insertAt = toggleIndex
                for (d in unchanged) {
                    add(createFieldBlock(d), insertAt++)
                    add(Box.createVerticalStrut(JBUI.scale(3)), insertAt++)
                }
                remove(label)
                revalidate()
                repaint()
            }
        })
        return label
    }

    private fun createFieldBlock(diff: FieldDiff): JPanel {
        val field = diff.after ?: diff.before ?: return JPanel()
        val statusIcon = statusIcon(diff.status)

        val block = FieldCard(diff.status).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val headerPanel = createHeaderPanel(diff, field, statusIcon, block.legacyHeaderBg)
        val bodyPanel = createBodyPanel(diff, field, block.legacyChildBg)

        val chevron = headerPanel.getClientProperty("chevronToggle") as JComponent
        headerPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                bodyPanel.isVisible = !bodyPanel.isVisible
                chrome.setChevronExpanded(chevron, bodyPanel.isVisible)
                block.revalidate()
                block.repaint()
            }
        })

        block.add(headerPanel)
        block.add(bodyPanel)
        return block
    }

    private fun createHeaderPanel(
        diff: FieldDiff,
        field: FieldSnapshot,
        statusIcon: String,
        bgColor: Color?
    ): JPanel {
        val typeName = field.pythonType.substringAfterLast('.')
        val shapeSummary = buildShapeSummary(field)

        val panel = JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
            border = chrome.fieldHeaderPadding
            if (bgColor != null) {
                background = bgColor
                isOpaque = true
            } else {
                isOpaque = false
            }
        }

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }

        val labelFont = UIUtil.getLabelFont()
        val chevron = chrome.newChevron(
            initialExpanded = false,
            legacyFont = labelFont.deriveFont(Font.PLAIN, labelFont.size - 1f),
        )
        panel.putClientProperty("chevronToggle", chevron)
        leftPanel.add(chevron)

        if (statusIcon.isNotEmpty()) {
            leftPanel.add(JBLabel(statusIcon).apply {
                foreground = diffStatusColor(diff.status)
                font = font.deriveFont(Font.BOLD)
            })
        }

        leftPanel.add(JBLabel(field.key).apply {
            font = font.deriveFont(Font.BOLD)
        })

        val childCount = diff.childDiffs?.size ?: 0
        val childHint = if (childCount > 0) "  ($childCount elements)" else ""
        leftPanel.add(JBLabel(typeName + shapeSummary + childHint).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(font.size - 1f)
        })

        panel.add(leftPanel, BorderLayout.WEST)

        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }

        if (field.npyPath != null) {
            val vizButton = JButton("Visualize").apply {
                font = font.deriveFont(font.size - 2f)
                isFocusPainted = false
                putClientProperty("JButton.buttonType", "roundRect")
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(JBUI.scale(76), JBUI.scale(20))
                addActionListener {
                    TensorVisualizerDialog(project, field).show()
                }
            }
            vizButton.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { e.consume() }
                override fun mousePressed(e: MouseEvent) { e.consume() }
            })
            rightPanel.add(vizButton)
        }

        if (field.sizeBytes != null) {
            rightPanel.add(JBLabel(formatBytes(field.sizeBytes)).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size - 1f)
                border = JBUI.Borders.emptyRight(4)
            })
        }

        panel.add(rightPanel, BorderLayout.EAST)

        return panel
    }

    private fun createBodyPanel(diff: FieldDiff, field: FieldSnapshot, bgColor: Color?): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 20, 6, 8)
            if (bgColor != null) {
                background = bgColor
                isOpaque = true
            } else {
                isOpaque = false
            }
            isVisible = false
        }

        when (diff.status) {
            FieldDiffStatus.MODIFIED -> {
                if (chrome.inlineScalarDiff
                    && diff.before != null && diff.after != null
                    && diff.childDiffs.isNullOrEmpty()
                ) {
                    // Inline "before → after" rows — one per changed stat —
                    // instead of an After:/Before: accordion.
                    addInlineChanges(panel, diff.before, diff.after)
                } else {
                    if (diff.after != null) {
                        panel.add(createCollapsibleSection(
                            "After:", diff.after, diff.childDiffs, DiffSide.AFTER, bgColor, startExpanded = true
                        ))
                    }
                    if (diff.before != null) {
                        panel.add(Box.createVerticalStrut(JBUI.scale(2)))
                        panel.add(createCollapsibleSection(
                            "Before:", diff.before, diff.childDiffs, DiffSide.BEFORE, bgColor, startExpanded = false
                        ))
                    }
                }
            }
            FieldDiffStatus.REMOVED -> {
                if (diff.before != null) {
                    panel.add(createCollapsibleSection(
                        "Before:", diff.before, diff.childDiffs, DiffSide.BEFORE, bgColor, startExpanded = true
                    ))
                }
            }
            else -> {
                // ADDED or UNCHANGED: show stats + children directly
                val monoFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
                addSnapshotStats(panel, field, monoFont, UIUtil.getLabelForeground())
                if (!diff.childDiffs.isNullOrEmpty()) {
                    panel.add(Box.createVerticalStrut(JBUI.scale(4)))
                    val childPanel = FieldDetailPanel(diff.childDiffs, project)
                    childPanel.alignmentX = Component.LEFT_ALIGNMENT
                    panel.add(childPanel)
                }
            }
        }

        return panel
    }

    /**
     * Render a compact list of `stat: before → after` rows for the changed
     * fields of a MODIFIED diff. Shape changes get highlighted dimension-by-
     * dimension so the eye lands on the actual size differences.
     */
    private fun addInlineChanges(parent: JPanel, before: FieldSnapshot, after: FieldSnapshot) {
        val monoFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
        val labelColor = UIUtil.getLabelForeground()
        val mutedColor = UIUtil.getInactiveTextColor()

        // Shape gets a dedicated dim-by-dim diff because it's the most common
        // and most meaningful change in ML pipelines.
        if (before.shape != null && after.shape != null && before.shape != after.shape) {
            parent.add(buildShapeDiffRow(before.shape, after.shape, monoFont, labelColor, mutedColor))
        } else if (before.shape != after.shape) {
            parent.add(buildInlineRow("shape", before.shape ?: "—", after.shape ?: "—", monoFont, labelColor, mutedColor))
        }

        // Other stats: dtype, min/max, mean, std, preview.
        val stats: List<Triple<String, String?, String?>> = listOf(
            Triple("dtype", before.dtype, after.dtype),
            Triple("min",   before.minValue, after.minValue),
            Triple("max",   before.maxValue, after.maxValue),
            Triple("mean",  before.meanValue, after.meanValue),
            Triple("std",   before.stdValue, after.stdValue),
            Triple("value", before.preview, after.preview),
        )
        for ((label, b, a) in stats) {
            if (b != a && !(b.isNullOrBlank() && a.isNullOrBlank())) {
                parent.add(buildInlineRow(label, b ?: "—", a ?: "—", monoFont, labelColor, mutedColor))
            }
        }
    }

    /**
     * Build a single `label  before  →  after` row using monospace formatting.
     * Visually compact; values are colour-coded when they're scalar so the
     * before reads as muted/strikethrough and the after as the value of record.
     */
    private fun buildInlineRow(
        label: String,
        before: String,
        after: String,
        monoFont: Font,
        labelColor: Color,
        mutedColor: Color,
    ): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
        }
        row.add(JBLabel(label).apply {
            font = monoFont.deriveFont(Font.BOLD)
            foreground = labelColor
        })
        row.add(JBLabel(before).apply {
            font = monoFont
            foreground = mutedColor
        })
        row.add(JBLabel("→").apply {
            font = monoFont
            foreground = mutedColor
        })
        row.add(JBLabel(after).apply {
            font = monoFont
            foreground = labelColor
        })
        return row
    }

    /**
     * Specialised shape-diff renderer. Each dimension is rendered as its own
     * small chip; dimensions that actually changed get a coloured tint so the
     * eye instantly lands on the changed axes.
     */
    private fun buildShapeDiffRow(
        beforeShape: String,
        afterShape: String,
        monoFont: Font,
        labelColor: Color,
        mutedColor: Color,
    ): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22))
        }
        row.add(JBLabel("shape").apply {
            font = monoFont.deriveFont(Font.BOLD)
            foreground = labelColor
        })
        row.add(buildShapeChips(beforeShape, afterShape, beforeSide = true, monoFont, labelColor, mutedColor))
        row.add(JBLabel("→").apply {
            font = monoFont
            foreground = mutedColor
        })
        row.add(buildShapeChips(afterShape, beforeShape, beforeSide = false, monoFont, labelColor, mutedColor))
        return row
    }

    private fun buildShapeChips(
        ownShape: String,
        otherShape: String,
        beforeSide: Boolean,
        monoFont: Font,
        labelColor: Color,
        mutedColor: Color,
    ): JComponent {
        val own = parseShapeDims(ownShape)
        val other = parseShapeDims(otherShape)
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
        }
        for ((i, dim) in own.withIndex()) {
            val changed = i >= other.size || other[i] != dim
            val chip = JBLabel(dim).apply {
                font = monoFont
                foreground = if (changed) {
                    if (beforeSide) FieldDetailPanel.diffStatusColor(FieldDiffStatus.REMOVED)
                    else FieldDetailPanel.diffStatusColor(FieldDiffStatus.ADDED)
                } else mutedColor
            }
            row.add(chip)
            if (i < own.size - 1) {
                row.add(JBLabel(",").apply {
                    font = monoFont
                    foreground = mutedColor
                })
            }
        }
        // Wrap with brackets.
        val wrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }
        wrap.add(JBLabel("(").apply { font = monoFont; foreground = mutedColor })
        wrap.add(row)
        wrap.add(JBLabel(")").apply { font = monoFont; foreground = mutedColor })
        return wrap
    }

    /** Parse "(3, 224, 224)" or "3 x 224 x 224" or "3,224,224" into ["3","224","224"]. */
    private fun parseShapeDims(s: String): List<String> {
        // Strip parens/brackets and split on common delimiters.
        val cleaned = s.trim().removePrefix("(").removeSuffix(")").removePrefix("[").removeSuffix("]")
        return cleaned.split(",", "x", "×").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Creates a collapsible "After:" or "Before:" section with full stats and color-coded children.
     *
     * Children are filtered and colored based on [side]:
     * - AFTER: shows children that have an `after` snapshot (ADDED=green, MODIFIED=yellow, UNCHANGED=grey)
     * - BEFORE: shows children that have a `before` snapshot (REMOVED=red, MODIFIED=yellow, UNCHANGED=grey)
     */
    private fun createCollapsibleSection(
        label: String,
        snapshot: FieldSnapshot,
        childDiffs: List<FieldDiff>?,
        side: DiffSide,
        bgColor: Color?,
        startExpanded: Boolean
    ): JPanel {
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }

        val monoFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
        val detailColor = UIUtil.getLabelForeground()

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyLeft(16)
            if (bgColor != null) {
                background = bgColor
                isOpaque = true
            } else {
                isOpaque = false
            }
            isVisible = startExpanded
        }

        addSnapshotStats(contentPanel, snapshot, monoFont, detailColor)

        // Render children with diff-aware coloring
        val visibleChildren = childDiffs?.filter { diff ->
            when (side) {
                DiffSide.AFTER -> diff.after != null
                DiffSide.BEFORE -> diff.before != null
            }
        }
        if (!visibleChildren.isNullOrEmpty()) {
            contentPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            val childPanel = SidedChildPanel(visibleChildren, side, bgColor)
            childPanel.alignmentX = Component.LEFT_ALIGNMENT
            contentPanel.add(childPanel)
        }

        // Section header
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val sectionChevronLegacyFont = monoFont.deriveFont(
            Font.PLAIN, UIUtil.getLabelFont().size - 1f
        )
        val collapseLabel = chrome.newChevron(
            initialExpanded = startExpanded,
            legacyFont = sectionChevronLegacyFont,
        )
        headerPanel.add(collapseLabel)
        headerPanel.add(JBLabel(label).apply {
            font = monoFont.deriveFont(Font.BOLD)
            foreground = detailColor
        })

        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                contentPanel.isVisible = !contentPanel.isVisible
                chrome.setChevronExpanded(collapseLabel, contentPanel.isVisible)
                wrapper.revalidate()
                wrapper.repaint()
            }
        })

        wrapper.add(headerPanel)
        wrapper.add(contentPanel)
        return wrapper
    }

    /**
     * Renders child diffs for one side (After or Before) with diff-aware color coding.
     * Each child shows the snapshot from the requested side, colored by its diff status.
     */
    private inner class SidedChildPanel(
        childDiffs: List<FieldDiff>,
        private val side: DiffSide,
        private val bgColor: Color?
    ) : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            for (diff in childDiffs) {
                add(createChildBlock(diff))
                add(Box.createVerticalStrut(JBUI.scale(2)))
            }
        }

        private fun createChildBlock(diff: FieldDiff): JPanel {
            val snapshot = when (side) {
                DiffSide.AFTER -> diff.after
                DiffSide.BEFORE -> diff.before
            } ?: return JPanel()

            val icon = statusIcon(diff.status)

            val block = FieldCard(diff.status).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            val monoFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
            val detailColor = UIUtil.getLabelForeground()

            // Header
            val typeName = snapshot.pythonType.substringAfterLast('.')
            val shapeSummary = buildShapeSummary(snapshot)
            val childCount = snapshot.children?.size ?: 0
            val childHint = if (childCount > 0) "  ($childCount elements)" else ""

            val headerPanel = JPanel(BorderLayout()).apply {
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(26))
                border = chrome.fieldHeaderPadding
                isOpaque = false
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }

            val childChevronLegacyFont = monoFont.deriveFont(
                Font.PLAIN, UIUtil.getLabelFont().size - 1f
            )
            val collapseLabel = chrome.newChevron(
                initialExpanded = false,
                legacyFont = childChevronLegacyFont,
            )
            leftPanel.add(collapseLabel)

            if (icon.isNotEmpty()) {
                leftPanel.add(JBLabel(icon).apply {
                    foreground = diffStatusColor(diff.status)
                    font = font.deriveFont(Font.BOLD)
                })
            }

            leftPanel.add(JBLabel(snapshot.key).apply { font = font.deriveFont(Font.BOLD) })
            leftPanel.add(JBLabel(typeName + shapeSummary + childHint).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size - 1f)
            })
            headerPanel.add(leftPanel, BorderLayout.WEST)

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }

            if (snapshot.npyPath != null) {
                val vizButton = JButton("Visualize").apply {
                    font = font.deriveFont(font.size - 2f)
                    isFocusPainted = false
                    putClientProperty("JButton.buttonType", "roundRect")
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    preferredSize = Dimension(JBUI.scale(76), JBUI.scale(20))
                    addActionListener {
                        TensorVisualizerDialog(this@FieldDetailPanel.project, snapshot).show()
                    }
                }
                vizButton.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { e.consume() }
                    override fun mousePressed(e: MouseEvent) { e.consume() }
                })
                rightPanel.add(vizButton)
            }

            if (snapshot.sizeBytes != null) {
                rightPanel.add(JBLabel(formatBytes(snapshot.sizeBytes)).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = font.deriveFont(font.size - 1f)
                    border = JBUI.Borders.emptyRight(4)
                })
            }

            headerPanel.add(rightPanel, BorderLayout.EAST)

            // Body
            val bodyPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(2, 20, 6, 8)
                isOpaque = false
                isVisible = false
            }

            addSnapshotStats(bodyPanel, snapshot, monoFont, detailColor)

            // Recursive nested children
            val nestedChildDiffs = diff.childDiffs?.filter { nested ->
                when (side) {
                    DiffSide.AFTER -> nested.after != null
                    DiffSide.BEFORE -> nested.before != null
                }
            }
            if (!nestedChildDiffs.isNullOrEmpty()) {
                bodyPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
                val nestedPanel = SidedChildPanel(nestedChildDiffs, side, bgColor)
                nestedPanel.alignmentX = Component.LEFT_ALIGNMENT
                bodyPanel.add(nestedPanel)
            }

            headerPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            headerPanel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    bodyPanel.isVisible = !bodyPanel.isVisible
                    chrome.setChevronExpanded(collapseLabel, bodyPanel.isVisible)
                    block.revalidate()
                    block.repaint()
                }
            })

            block.add(headerPanel)
            block.add(bodyPanel)
            return block
        }
    }

    private fun addSnapshotStats(panel: JPanel, snapshot: FieldSnapshot, monoFont: Font, color: Color) {
        val mutedColor = UIUtil.getInactiveTextColor()
        if (snapshot.shape != null) addDetailRow(panel, "Shape", snapshot.shape, monoFont, color)
        if (snapshot.dtype != null) addDetailRow(panel, "DType", snapshot.dtype, monoFont, color)
        if (snapshot.minValue != null && snapshot.maxValue != null) {
            addDetailRow(panel, "Range", "[${snapshot.minValue}, ${snapshot.maxValue}]", monoFont, color)
        } else if (snapshot.minValue != null) {
            addDetailRow(panel, "Min", snapshot.minValue, monoFont, color)
        }
        if (snapshot.meanValue != null) addDetailRow(panel, "Mean", snapshot.meanValue, monoFont, color)
        if (snapshot.stdValue != null) addDetailRow(panel, "Std", snapshot.stdValue, monoFont, color)
        if (snapshot.preview != null) addDetailRow(panel, "Value", snapshot.preview, monoFont, mutedColor)
        if (snapshot.sizeBytes != null) addDetailRow(panel, "Size", formatBytes(snapshot.sizeBytes), monoFont, mutedColor)
    }

    private fun addDetailRow(panel: JPanel, label: String, value: String, font: Font, color: Color) {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(18))
        }
        row.add(JBLabel(label).apply { this.font = font.deriveFont(Font.BOLD); foreground = color })
        row.add(JBLabel(value).apply { this.font = font; foreground = color })
        panel.add(row)
    }

    private fun buildShapeSummary(field: FieldSnapshot): String {
        val parts = mutableListOf<String>()
        if (field.shape != null) parts.add(field.shape)
        if (field.dtype != null) parts.add(field.dtype)
        return if (parts.isNotEmpty()) "  " + parts.joinToString(" ") else ""
    }

    companion object {
        private val BORDER_ADDED = JBColor(Color(0x4CAF50), Color(0x388E3C))
        private val BORDER_REMOVED = JBColor(Color(0xF44336), Color(0xD32F2F))
        private val BORDER_MODIFIED = JBColor(Color(0xFFC107), Color(0xFFA000))
        private val BORDER_UNCHANGED = JBColor(Color(0xBDBDBD), Color(0x616161))

        private val BG_ADDED = JBColor(Color(0xE8F5E9), Color(0x1B3B1B))
        private val BG_REMOVED = JBColor(Color(0xFFEBEE), Color(0x3B1B1B))
        private val BG_MODIFIED = JBColor(Color(0xFFF8E1), Color(0x3B3B1B))

        fun diffBorderColor(status: FieldDiffStatus): Color = when (status) {
            FieldDiffStatus.ADDED -> BORDER_ADDED
            FieldDiffStatus.REMOVED -> BORDER_REMOVED
            FieldDiffStatus.MODIFIED -> BORDER_MODIFIED
            FieldDiffStatus.UNCHANGED -> BORDER_UNCHANGED
        }

        fun diffBackground(status: FieldDiffStatus): Color? = when (status) {
            FieldDiffStatus.ADDED -> BG_ADDED
            FieldDiffStatus.REMOVED -> BG_REMOVED
            FieldDiffStatus.MODIFIED -> BG_MODIFIED
            FieldDiffStatus.UNCHANGED -> null
        }

        fun diffStatusColor(status: FieldDiffStatus): Color = when (status) {
            FieldDiffStatus.ADDED -> JBColor(Color(0x2E7D32), Color(0x66BB6A))
            FieldDiffStatus.REMOVED -> JBColor(Color(0xC62828), Color(0xEF5350))
            FieldDiffStatus.MODIFIED -> JBColor(Color(0xF57F17), Color(0xFFCA28))
            FieldDiffStatus.UNCHANGED -> UIUtil.getLabelForeground()
        }

        fun statusIcon(status: FieldDiffStatus): String = when (status) {
            FieldDiffStatus.ADDED -> "+"
            FieldDiffStatus.REMOVED -> "\u2212"
            FieldDiffStatus.MODIFIED -> "~"
            FieldDiffStatus.UNCHANGED -> ""
        }

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}

/**
 * Container for one diff row in the field-detail panel. Captures the
 * current [ProbeChrome] at construction and delegates every visual decision
 * (border, paint, hover animation, status colours) to it. The chrome
 * installs/uninstalls per-card state via [addNotify] / [removeNotify], so
 * timers and listeners are cleaned up automatically when the card is
 * detached from the tree (e.g. on a chrome hot-swap).
 *
 * Callers use [legacyHeaderBg] / [legacyChildBg] to learn whether the
 * chrome wants header/body panels to paint their own opaque background; in
 * polished chrome both are null because FieldCard paints the entire card.
 */
class FieldCard(val status: FieldDiffStatus) : JPanel() {

    val chrome: ProbeChrome = ProbeChrome.current

    val legacyHeaderBg: Color? = chrome.fieldHeaderLegacyBg(status)
    val legacyChildBg: Color? = chrome.fieldChildLegacyBg(status)

    init {
        // isOpaque is left to the chrome (set in installFieldCard) — legacy
        // wants the panel-default fill (opaque = true), polished paints its
        // own rounded card on a transparent base (opaque = false).
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    override fun addNotify() {
        super.addNotify()
        chrome.installFieldCard(this)
    }

    override fun removeNotify() {
        chrome.uninstallFieldCard(this)
        super.removeNotify()
    }

    final override fun paintComponent(g: Graphics) {
        // super first: if isOpaque, fills with the L&F panel default (this is
        // what legacy chrome relies on for UNCHANGED rows). If not opaque
        // (polished), super is a no-op and the chrome's custom paint takes over.
        super.paintComponent(g)
        chrome.paintFieldCardBackground(this, g)
    }
}

/**
 * Tiny chevron widget rendered by [PolishedProbeChrome]: a smoothly rotating
 * triangle that animates between "collapsed" (pointing right) and "expanded"
 * (pointing down) over ~140 ms. Legacy chrome doesn't use this — it returns
 * a `JBLabel` text glyph instead.
 */
class AnimatedChevron : JComponent() {

    var expanded: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    /** 0 = pointing right (collapsed); 1 = pointing down (expanded). */
    private var rotation: Float = 0f
    private var rotFrom = 0f
    private var rotTo = 0f
    private var animStart = 0L
    private val timer: Timer = Timer(16) { tick() }

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(12), JBUI.scale(14))
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    private fun animateTo(target: Float) {
        rotFrom = rotation
        rotTo = target
        animStart = System.currentTimeMillis()
        if (!timer.isRunning) timer.start()
    }

    private fun tick() {
        val t = ((System.currentTimeMillis() - animStart) / 140.0).coerceIn(0.0, 1.0)
        val x = 1.0 - t
        val eased = (1.0 - x * x * x).toFloat()
        rotation = rotFrom + (rotTo - rotFrom) * eased
        repaint()
        if (t >= 1.0) {
            rotation = rotTo
            timer.stop()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = (g as Graphics2D).create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getInactiveTextColor()
            val cx = width / 2.0
            val cy = height / 2.0
            val angle = Math.PI / 2 * rotation
            g2.translate(cx, cy)
            g2.rotate(angle)
            val s = JBUI.scale(3)
            val tri = Polygon(
                intArrayOf(-s, -s, (s * 1.2).toInt()),
                intArrayOf(-s, s, 0),
                3,
            )
            g2.fillPolygon(tri)
        } finally {
            g2.dispose()
        }
    }
}
