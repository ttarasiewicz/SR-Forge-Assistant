package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Main panel for the Pipeline Probe tool window.
 * Displays a block-based flow diagram with expandable step and field blocks.
 */
class ProbeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
    }
    private val scrollPane = JBScrollPane(contentPanel)
    private val headerLabel = JBLabel("No probe results yet. Run Pipeline Probe from the SR-Forge toolbar.")

    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            add(headerLabel, BorderLayout.WEST)
        }
        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    fun displayResult(result: ProbeExecutionResult) {
        contentPanel.removeAll()

        if (!result.success || result.result == null) {
            displayError(result)
        } else {
            headerLabel.text = "Pipeline probe completed in ${result.executionTimeMs}ms"
            headerLabel.icon = ProbeIcons.Probe
            displayDatasetResult(result.result)
        }

        contentPanel.revalidate()
        contentPanel.repaint()

        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = 0
        }
    }

    /**
     * Display a dataset result and return its last snapshot,
     * so a wrapping dataset can diff against it.
     */
    private fun displayDatasetResult(
        result: DatasetProbeResult,
        previousSnapshot: EntrySnapshot? = null
    ): EntrySnapshot? {
        var innerLastSnapshot: EntrySnapshot? = previousSnapshot
        if (result.innerResult != null) {
            innerLastSnapshot = displayDatasetResult(result.innerResult, previousSnapshot)
            addConnector("Wrapped by ${result.datasetName}")
        }

        // Dataset block
        addDatasetBlock(result)

        // Step blocks
        val snapshots = result.snapshots
        for (i in snapshots.indices) {
            val snapshot = snapshots[i]
            val diffs = if (i > 0) {
                computeDiffs(snapshots[i - 1], snapshot)
            } else if (innerLastSnapshot != null) {
                computeDiffs(innerLastSnapshot, snapshot)
            } else {
                snapshot.fields.map { f ->
                    val childDiffs = f.children?.map { c ->
                        FieldDiff(c.key, FieldDiffStatus.UNCHANGED, null, c)
                    }
                    FieldDiff(f.key, FieldDiffStatus.UNCHANGED, null, f, childDiffs)
                }
            }

            addConnector(null)
            addStepBlock(snapshot, diffs)
        }

        return snapshots.lastOrNull()
    }

    private fun addDatasetBlock(result: DatasetProbeResult) {
        val block = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
            }
        }.apply {
            isOpaque = false
            background = JBColor(Color(0x1976D2), Color(0x1565C0))
            border = JBUI.Borders.empty(10, 14)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(48))
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val nameLabel = JBLabel(result.datasetName).apply {
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD, font.size + 2f)
            icon = ProbeIcons.Probe
            iconTextGap = JBUI.scale(8)
        }
        block.add(nameLabel, BorderLayout.WEST)

        val targetLabel = JBLabel(result.datasetTarget).apply {
            foreground = JBColor(Color(0xBBDEFB), Color(0x90CAF9))
            font = font.deriveFont(font.size - 1f)
        }
        block.add(targetLabel, BorderLayout.EAST)

        contentPanel.add(block)
    }

    private fun addStepBlock(snapshot: EntrySnapshot, diffs: List<FieldDiff>) {
        val block = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = STEP_BLOCK_BG
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                g2.color = STEP_BLOCK_BORDER
                g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(10, 12)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        // Step header with collapse toggle
        val fieldsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val collapseLabel = JBLabel("\u25BE").apply {
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.emptyRight(6)
        }

        val stepNameLabel = JBLabel(snapshot.stepLabel).apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
        }

        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(collapseLabel)
            add(stepNameLabel)
        }
        headerRow.add(leftHeader, BorderLayout.WEST)

        // Summary badges on the right
        val summaryText = buildSummaryBadges(diffs)
        if (summaryText.isNotEmpty()) {
            headerRow.add(JBLabel(summaryText).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size - 1f)
                border = JBUI.Borders.emptyRight(4)
            }, BorderLayout.EAST)
        }

        headerRow.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                fieldsPanel.isVisible = !fieldsPanel.isVisible
                collapseLabel.text = if (fieldsPanel.isVisible) "\u25BE" else "\u25B8"
                block.revalidate()
                block.repaint()
            }
        })

        block.add(headerRow)
        block.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Field blocks
        if (diffs.isEmpty()) {
            fieldsPanel.add(JBLabel("(no fields)").apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyLeft(4)
            })
        } else {
            val fieldDetail = FieldDetailPanel(diffs)
            fieldDetail.alignmentX = Component.LEFT_ALIGNMENT
            fieldsPanel.add(fieldDetail)
        }

        fieldsPanel.alignmentX = Component.LEFT_ALIGNMENT
        block.add(fieldsPanel)

        contentPanel.add(block)
    }

    private fun addConnector(label: String?) {
        val connector = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            border = JBUI.Borders.empty(2, 0)
        }

        connector.add(Box.createHorizontalStrut(JBUI.scale(24)))

        // Vertical line + arrow
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
                // Vertical line
                g2.drawLine(cx, 0, cx, height - JBUI.scale(6))
                // Arrowhead
                val ay = height - JBUI.scale(2)
                val aw = JBUI.scale(4)
                g2.fillPolygon(
                    intArrayOf(cx - aw, cx, cx + aw),
                    intArrayOf(ay - aw * 2, ay, ay - aw * 2),
                    3
                )
            }
        })

        if (label != null) {
            connector.add(Box.createHorizontalStrut(JBUI.scale(4)))
            connector.add(JBLabel(label).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(Font.ITALIC, font.size - 1f)
            })
        }

        contentPanel.add(connector)
    }

    private fun buildSummaryBadges(diffs: List<FieldDiff>): String {
        val added = diffs.count { it.status == FieldDiffStatus.ADDED }
        val removed = diffs.count { it.status == FieldDiffStatus.REMOVED }
        val modified = diffs.count { it.status == FieldDiffStatus.MODIFIED }
        val parts = mutableListOf<String>()
        if (added > 0) parts.add("+$added")
        if (removed > 0) parts.add("-$removed")
        if (modified > 0) parts.add("~$modified")
        val total = diffs.size
        parts.add("$total fields")
        return parts.joinToString("  ")
    }

    private fun displayError(result: ProbeExecutionResult) {
        headerLabel.text = "Probe failed"
        headerLabel.icon = UIUtil.getErrorIcon()

        val errorBlock = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(Color(0xFFEBEE), Color(0x3B1B1B))
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                g2.color = JBColor(Color(0xEF9A9A), Color(0xC62828))
                g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(12)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        errorBlock.add(JBLabel(result.errorMessage ?: "Unknown error").apply {
            foreground = JBColor(Color(0xC62828), Color(0xEF5350))
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        })

        if (result.errorTraceback != null) {
            errorBlock.add(Box.createVerticalStrut(JBUI.scale(8)))
            val traceArea = JTextArea(result.errorTraceback).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
                foreground = UIUtil.getInactiveTextColor()
                background = Color(0, 0, 0, 0)
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
            }
            val scrollable = JBScrollPane(traceArea).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
                preferredSize = Dimension(0, JBUI.scale(200))
                isOpaque = false
                viewport.isOpaque = false
            }
            errorBlock.add(scrollable)
        }

        contentPanel.add(errorBlock)
    }

    companion object {
        // Elevated surface for step blocks — white in light theme, slightly lighter in dark
        private val STEP_BLOCK_BG = JBColor(Color.WHITE, Color(0x3C3F41))
        private val STEP_BLOCK_BORDER = JBColor(Color(0xD0D0D0), Color(0x555555))

        fun computeDiffs(before: EntrySnapshot, after: EntrySnapshot): List<FieldDiff> {
            return computeFieldListDiffs(before.fields, after.fields)
        }

        fun computeFieldListDiffs(
            before: List<FieldSnapshot>,
            after: List<FieldSnapshot>
        ): List<FieldDiff> {
            val beforeMap = before.associateBy { it.key }
            val afterMap = after.associateBy { it.key }
            val allKeys = LinkedHashSet<String>()
            allKeys.addAll(beforeMap.keys)
            allKeys.addAll(afterMap.keys)

            return allKeys.map { key ->
                val b = beforeMap[key]
                val a = afterMap[key]
                val childDiffs = computeChildDiffs(b?.children, a?.children)
                when {
                    b == null && a != null ->
                        FieldDiff(key, FieldDiffStatus.ADDED, null, a, childDiffs)
                    b != null && a == null ->
                        FieldDiff(key, FieldDiffStatus.REMOVED, b, null, childDiffs)
                    b != null && a != null && (fieldChanged(b, a) || hasChildChanges(childDiffs)) ->
                        FieldDiff(key, FieldDiffStatus.MODIFIED, b, a, childDiffs)
                    else ->
                        FieldDiff(key, FieldDiffStatus.UNCHANGED, b, a, childDiffs)
                }
            }
        }

        private fun computeChildDiffs(
            before: List<FieldSnapshot>?,
            after: List<FieldSnapshot>?
        ): List<FieldDiff>? {
            if (before == null && after == null) return null
            return computeFieldListDiffs(before ?: emptyList(), after ?: emptyList())
        }

        private fun hasChildChanges(childDiffs: List<FieldDiff>?): Boolean {
            return childDiffs?.any { it.status != FieldDiffStatus.UNCHANGED } == true
        }

        private fun fieldChanged(b: FieldSnapshot, a: FieldSnapshot): Boolean {
            return b.pythonType != a.pythonType ||
                    b.shape != a.shape ||
                    b.dtype != a.dtype ||
                    b.minValue != a.minValue ||
                    b.maxValue != a.maxValue ||
                    b.meanValue != a.meanValue ||
                    b.stdValue != a.stdValue ||
                    b.sizeBytes != a.sizeBytes ||
                    b.preview != a.preview
        }
    }
}
