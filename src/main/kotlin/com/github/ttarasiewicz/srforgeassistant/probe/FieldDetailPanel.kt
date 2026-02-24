package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        for (diff in diffs) {
            add(createFieldBlock(diff))
            add(Box.createVerticalStrut(JBUI.scale(3)))
        }
    }

    private fun createFieldBlock(diff: FieldDiff): JPanel {
        val field = diff.after ?: diff.before ?: return JPanel()
        val borderColor = diffBorderColor(diff.status)
        val bgColor = diffBackground(diff.status)
        val statusIcon = statusIcon(diff.status)

        val block = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, borderColor),
                JBUI.Borders.empty(0, 0)
            )
        }

        val headerPanel = createHeaderPanel(diff, field, statusIcon, bgColor)
        val bodyPanel = createBodyPanel(diff, field, bgColor)

        headerPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                bodyPanel.isVisible = !bodyPanel.isVisible
                val indicator = headerPanel.getClientProperty("collapseLabel") as? JBLabel
                indicator?.text = if (bodyPanel.isVisible) "\u25BE" else "\u25B8"
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
            border = JBUI.Borders.empty(3, 8, 3, 8)
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

        val collapseLabel = JBLabel("\u25B8").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(Font.PLAIN, font.size - 1f)
        }
        panel.putClientProperty("collapseLabel", collapseLabel)
        leftPanel.add(collapseLabel)

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

        val collapseLabel = JBLabel(if (startExpanded) "\u25BE" else "\u25B8").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = monoFont.deriveFont(Font.PLAIN, font.size - 1f)
        }
        headerPanel.add(collapseLabel)
        headerPanel.add(JBLabel(label).apply {
            font = monoFont.deriveFont(Font.BOLD)
            foreground = detailColor
        })

        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                contentPanel.isVisible = !contentPanel.isVisible
                collapseLabel.text = if (contentPanel.isVisible) "\u25BE" else "\u25B8"
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

            val borderColor = diffBorderColor(diff.status)
            val icon = statusIcon(diff.status)

            val block = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, borderColor),
                    JBUI.Borders.empty(0, 0)
                )
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
                border = JBUI.Borders.empty(3, 8, 3, 8)
                isOpaque = false
            }

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }

            val collapseLabel = JBLabel("\u25B8").apply {
                foreground = UIUtil.getInactiveTextColor()
                font = monoFont.deriveFont(Font.PLAIN, font.size - 1f)
            }
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
                    collapseLabel.text = if (bodyPanel.isVisible) "\u25BE" else "\u25B8"
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
