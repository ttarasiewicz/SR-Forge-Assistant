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
 * Displays field rows with color-coded diff backgrounds.
 * Rows expand on click to show full details.
 */
class FieldDetailPanel(private val diffs: List<FieldDiff>) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        // Header row
        addHeaderRow()

        for (diff in diffs) {
            addFieldRow(diff)
        }
    }

    private fun addHeaderRow() {
        val row = JPanel(GridBagLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22))
            border = JBUI.Borders.empty(1, 4)
            isOpaque = false
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 2, 0, 2)
            gridy = 0
        }

        val headerFont = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getFontSize(UIUtil.FontSize.SMALL))
        val headerColor = UIUtil.getInactiveTextColor()

        gbc.gridx = 0; gbc.weightx = 0.0; gbc.ipadx = JBUI.scale(16)
        row.add(makeLabel("", headerFont, headerColor), gbc)

        gbc.gridx = 1; gbc.weightx = 0.2; gbc.ipadx = 0
        row.add(makeLabel("Key", headerFont, headerColor), gbc)

        gbc.gridx = 2; gbc.weightx = 0.2
        row.add(makeLabel("Type", headerFont, headerColor), gbc)

        gbc.gridx = 3; gbc.weightx = 0.15
        row.add(makeLabel("Shape", headerFont, headerColor), gbc)

        gbc.gridx = 4; gbc.weightx = 0.1
        row.add(makeLabel("DType", headerFont, headerColor), gbc)

        gbc.gridx = 5; gbc.weightx = 0.15
        row.add(makeLabel("Range", headerFont, headerColor), gbc)

        gbc.gridx = 6; gbc.weightx = 0.2
        row.add(makeLabel("Preview", headerFont, headerColor), gbc)

        row.alignmentX = Component.LEFT_ALIGNMENT
        add(row)
    }

    private fun addFieldRow(diff: FieldDiff) {
        val field = diff.after ?: diff.before ?: return
        val bg = diffBackground(diff.status)
        val statusChar = when (diff.status) {
            FieldDiffStatus.ADDED -> "+"
            FieldDiffStatus.REMOVED -> "-"
            FieldDiffStatus.MODIFIED -> "~"
            FieldDiffStatus.UNCHANGED -> " "
        }
        val statusColor = when (diff.status) {
            FieldDiffStatus.ADDED -> JBColor(Color(0x2E7D32), Color(0x66BB6A))
            FieldDiffStatus.REMOVED -> JBColor(Color(0xC62828), Color(0xEF5350))
            FieldDiffStatus.MODIFIED -> JBColor(Color(0xF57F17), Color(0xFFCA28))
            FieldDiffStatus.UNCHANGED -> UIUtil.getLabelForeground()
        }

        val typeName = field.pythonType.substringAfterLast('.')
        val range = buildRangeText(field)

        val row = JPanel(GridBagLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(22))
            border = JBUI.Borders.empty(1, 4)
            background = bg
            isOpaque = bg != null
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(0, 2, 0, 2)
            gridy = 0
        }

        val smallFont = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
        val fg = UIUtil.getLabelForeground()

        gbc.gridx = 0; gbc.weightx = 0.0; gbc.ipadx = JBUI.scale(16)
        row.add(makeLabel(statusChar, smallFont.deriveFont(Font.BOLD), statusColor), gbc)

        gbc.gridx = 1; gbc.weightx = 0.2; gbc.ipadx = 0
        row.add(makeLabel(field.key, smallFont.deriveFont(Font.BOLD), fg), gbc)

        gbc.gridx = 2; gbc.weightx = 0.2
        row.add(makeLabel(typeName, smallFont, fg), gbc)

        gbc.gridx = 3; gbc.weightx = 0.15
        row.add(makeLabel(field.shape ?: "", smallFont, fg), gbc)

        gbc.gridx = 4; gbc.weightx = 0.1
        row.add(makeLabel(field.dtype ?: "", smallFont, fg), gbc)

        gbc.gridx = 5; gbc.weightx = 0.15
        row.add(makeLabel(range, smallFont, fg), gbc)

        gbc.gridx = 6; gbc.weightx = 0.2
        row.add(makeLabel(field.preview?.take(60) ?: "", smallFont, UIUtil.getInactiveTextColor()), gbc)

        row.alignmentX = Component.LEFT_ALIGNMENT

        // Expandable detail on click
        val detailPanel = createDetailPanel(diff)
        detailPanel.isVisible = false
        detailPanel.alignmentX = Component.LEFT_ALIGNMENT

        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                detailPanel.isVisible = !detailPanel.isVisible
                revalidate()
                repaint()
            }
        })

        add(row)
        add(detailPanel)
    }

    private fun createDetailPanel(diff: FieldDiff): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 24, 4, 4)
            isOpaque = false
        }

        val smallFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
        val fg = UIUtil.getInactiveTextColor()

        val field = diff.after ?: diff.before ?: return panel

        panel.add(detailRow("Full type:", field.pythonType, smallFont, fg))
        if (field.shape != null) panel.add(detailRow("Shape:", field.shape, smallFont, fg))
        if (field.dtype != null) panel.add(detailRow("DType:", field.dtype, smallFont, fg))
        if (field.minValue != null) panel.add(detailRow("Min:", field.minValue, smallFont, fg))
        if (field.maxValue != null) panel.add(detailRow("Max:", field.maxValue, smallFont, fg))
        if (field.sizeBytes != null) panel.add(detailRow("Size:", formatBytes(field.sizeBytes), smallFont, fg))
        if (field.preview != null) panel.add(detailRow("Preview:", field.preview, smallFont, fg))

        if (diff.status == FieldDiffStatus.MODIFIED && diff.before != null) {
            panel.add(Box.createVerticalStrut(JBUI.scale(2)))
            val beforeLabel = JBLabel("Before:").apply {
                this.font = smallFont.deriveFont(Font.BOLD)
                foreground = fg
            }
            panel.add(beforeLabel)
            val b = diff.before
            if (b.shape != null) panel.add(detailRow("  Shape:", b.shape, smallFont, fg))
            if (b.dtype != null) panel.add(detailRow("  DType:", b.dtype, smallFont, fg))
            if (b.minValue != null) panel.add(detailRow("  Min:", b.minValue, smallFont, fg))
            if (b.maxValue != null) panel.add(detailRow("  Max:", b.maxValue, smallFont, fg))
            if (b.sizeBytes != null) panel.add(detailRow("  Size:", formatBytes(b.sizeBytes), smallFont, fg))
        }

        return panel
    }

    private fun detailRow(label: String, value: String, font: Font, color: Color): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(18))
            add(JBLabel(label).apply { this.font = font.deriveFont(Font.BOLD); foreground = color })
            add(JBLabel(value).apply { this.font = font; foreground = color })
        }
    }

    private fun buildRangeText(field: FieldSnapshot): String {
        val min = field.minValue ?: return ""
        val max = field.maxValue ?: return min
        return "[$min, $max]"
    }

    private fun makeLabel(text: String, font: Font, fg: Color): JBLabel {
        return JBLabel(text).apply {
            this.font = font
            foreground = fg
        }
    }

    companion object {
        private val BG_ADDED = JBColor(Color(0xE8F5E9), Color(0x1B3B1B))
        private val BG_REMOVED = JBColor(Color(0xFFEBEE), Color(0x3B1B1B))
        private val BG_MODIFIED = JBColor(Color(0xFFF8E1), Color(0x3B3B1B))

        fun diffBackground(status: FieldDiffStatus): Color? = when (status) {
            FieldDiffStatus.ADDED -> BG_ADDED
            FieldDiffStatus.REMOVED -> BG_REMOVED
            FieldDiffStatus.MODIFIED -> BG_MODIFIED
            FieldDiffStatus.UNCHANGED -> null
        }

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
