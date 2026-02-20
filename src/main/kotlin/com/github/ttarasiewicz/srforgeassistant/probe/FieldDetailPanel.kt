package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Displays fields as individual expandable blocks with color-coded borders.
 * All blocks start expanded; clicking the header collapses/expands.
 */
class FieldDetailPanel(private val diffs: List<FieldDiff>) : JPanel() {

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

        // Header bar — field name + type summary (always visible)
        val headerPanel = createHeaderPanel(diff, field, statusIcon, bgColor)

        // Detail body — expanded by default
        val bodyPanel = createBodyPanel(diff, field, bgColor)

        headerPanel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                bodyPanel.isVisible = !bodyPanel.isVisible
                // Update collapse indicator
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

        // Collapse indicator (collapsed by default)
        val collapseLabel = JBLabel("\u25B8").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(Font.PLAIN, font.size - 1f)
        }
        panel.putClientProperty("collapseLabel", collapseLabel)
        leftPanel.add(collapseLabel)

        // Status icon
        if (statusIcon.isNotEmpty()) {
            val statusLabel = JBLabel(statusIcon).apply {
                foreground = diffStatusColor(diff.status)
                font = font.deriveFont(Font.BOLD)
            }
            leftPanel.add(statusLabel)
        }

        // Field name
        leftPanel.add(JBLabel(field.key).apply {
            font = font.deriveFont(Font.BOLD)
        })

        // Type + shape summary (muted)
        val childCount = diff.childDiffs?.size ?: 0
        val childHint = if (childCount > 0) "  ($childCount elements)" else ""
        leftPanel.add(JBLabel(typeName + shapeSummary + childHint).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(font.size - 1f)
        })

        panel.add(leftPanel, BorderLayout.WEST)

        // Right side: size badge
        if (field.sizeBytes != null) {
            panel.add(JBLabel(formatBytes(field.sizeBytes)).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size - 1f)
                border = JBUI.Borders.emptyRight(4)
            }, BorderLayout.EAST)
        }

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
            // Start collapsed — user clicks field header to expand
            isVisible = false
        }

        val monoFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
        val detailColor = UIUtil.getLabelForeground()
        val mutedColor = UIUtil.getInactiveTextColor()

        // Detail rows
        if (field.shape != null) addDetailRow(panel, "Shape", field.shape, monoFont, detailColor)
        if (field.dtype != null) addDetailRow(panel, "DType", field.dtype, monoFont, detailColor)
        if (field.minValue != null && field.maxValue != null) {
            addDetailRow(panel, "Range", "[${field.minValue}, ${field.maxValue}]", monoFont, detailColor)
        } else if (field.minValue != null) {
            addDetailRow(panel, "Min", field.minValue, monoFont, detailColor)
        }
        if (field.meanValue != null) addDetailRow(panel, "Mean", field.meanValue, monoFont, detailColor)
        if (field.stdValue != null) addDetailRow(panel, "Std", field.stdValue, monoFont, detailColor)
        if (field.preview != null) addDetailRow(panel, "Value", field.preview, monoFont, mutedColor)

        // Show "before" comparison for modified fields
        if (diff.status == FieldDiffStatus.MODIFIED && diff.before != null) {
            panel.add(Box.createVerticalStrut(JBUI.scale(3)))
            panel.add(JSeparator().apply {
                maximumSize = Dimension(Int.MAX_VALUE, 1)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            panel.add(Box.createVerticalStrut(JBUI.scale(3)))

            val b = diff.before
            val beforeColor = JBColor(Color(0x9E9E9E), Color(0x787878))
            panel.add(JBLabel("Before:").apply {
                font = monoFont.deriveFont(Font.BOLD)
                foreground = beforeColor
                alignmentX = Component.LEFT_ALIGNMENT
            })
            if (b.shape != null && b.shape != field.shape)
                addDetailRow(panel, "  Shape", b.shape, monoFont, beforeColor)
            if (b.dtype != null && b.dtype != field.dtype)
                addDetailRow(panel, "  DType", b.dtype, monoFont, beforeColor)
            if (b.minValue != null && (b.minValue != field.minValue || b.maxValue != field.maxValue))
                addDetailRow(panel, "  Range", "[${b.minValue}, ${b.maxValue ?: "?"}]", monoFont, beforeColor)
            if (b.meanValue != null && b.meanValue != field.meanValue)
                addDetailRow(panel, "  Mean", b.meanValue, monoFont, beforeColor)
            if (b.stdValue != null && b.stdValue != field.stdValue)
                addDetailRow(panel, "  Std", b.stdValue, monoFont, beforeColor)
            if (b.preview != null && b.preview != field.preview)
                addDetailRow(panel, "  Value", b.preview, monoFont, beforeColor)
            if (b.sizeBytes != null && b.sizeBytes != field.sizeBytes)
                addDetailRow(panel, "  Size", formatBytes(b.sizeBytes), monoFont, beforeColor)
        }

        // Nested children for containers (dicts, lists, tuples)
        if (!diff.childDiffs.isNullOrEmpty()) {
            panel.add(Box.createVerticalStrut(JBUI.scale(4)))
            val childPanel = FieldDetailPanel(diff.childDiffs)
            childPanel.alignmentX = Component.LEFT_ALIGNMENT
            panel.add(childPanel)
        }

        return panel
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
