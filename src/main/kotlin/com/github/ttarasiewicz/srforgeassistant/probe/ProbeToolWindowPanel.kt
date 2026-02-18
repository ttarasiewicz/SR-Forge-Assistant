package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

/**
 * Main panel for the Pipeline Probe tool window.
 * Displays a vertical pipeline flow with field snapshots at each step.
 */
class ProbeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }
    private val scrollPane = JBScrollPane(contentPanel)
    private val headerLabel = JBLabel("No probe results yet. Run Pipeline Probe from the SR-Forge toolbar.")

    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
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
            headerLabel.text = "Probe completed in ${result.executionTimeMs}ms"
            headerLabel.icon = ProbeIcons.Probe
            displayDatasetResult(result.result)
        }

        contentPanel.revalidate()
        contentPanel.repaint()

        // Scroll to top
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
        // Show inner (wrapped) dataset first, collecting its last snapshot
        var innerLastSnapshot: EntrySnapshot? = previousSnapshot
        if (result.innerResult != null) {
            innerLastSnapshot = displayDatasetResult(result.innerResult, previousSnapshot)
            addArrow("Wrapped by ${result.datasetName}")
        }

        // Dataset header
        addDatasetHeader(result)

        // Show snapshots with diffs
        val snapshots = result.snapshots
        for (i in snapshots.indices) {
            val snapshot = snapshots[i]
            val diffs = if (i > 0) {
                computeDiffs(snapshots[i - 1], snapshot)
            } else if (innerLastSnapshot != null) {
                // Diff the wrapping dataset's initial state against inner dataset's final state
                computeDiffs(innerLastSnapshot, snapshot)
            } else {
                snapshot.fields.map { FieldDiff(it.key, FieldDiffStatus.UNCHANGED, null, it) }
            }

            if (i > 0) {
                addArrow(null)
            }

            addSnapshotCard(snapshot, diffs)
        }

        return snapshots.lastOrNull()
    }

    private fun addDatasetHeader(result: DatasetProbeResult) {
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            background = JBColor(Color(0x2196F3), Color(0x1A3A5C))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))

            val label = JBLabel("  ${result.datasetName}").apply {
                foreground = JBColor(Color.WHITE, Color(0xBBDEFB))
                font = font.deriveFont(Font.BOLD, font.size + 1f)
                icon = ProbeIcons.Probe
            }
            add(label, BorderLayout.WEST)

            val targetLabel = JBLabel(result.datasetTarget).apply {
                foreground = JBColor(Color(0xBBDEFB), Color(0x7BAAD4))
                font = font.deriveFont(font.size - 1f)
                border = JBUI.Borders.emptyRight(8)
            }
            add(targetLabel, BorderLayout.EAST)
        }
        header.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(header)
    }

    private fun addSnapshotCard(snapshot: EntrySnapshot, diffs: List<FieldDiff>) {
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(6, 8)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        // Step label
        val stepLabel = JBLabel(snapshot.stepLabel).apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        card.add(stepLabel)
        card.add(Box.createVerticalStrut(JBUI.scale(4)))

        if (diffs.isEmpty()) {
            card.add(JBLabel("(no fields)").apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            // Field table
            val fieldPanel = FieldDetailPanel(diffs)
            fieldPanel.alignmentX = Component.LEFT_ALIGNMENT
            card.add(fieldPanel)
        }

        contentPanel.add(card)
    }

    private fun addArrow(label: String?) {
        val arrowPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(if (label != null) 36 else 24))
            border = JBUI.Borders.empty(2, 0)
        }

        val arrowLabel = JBLabel(if (label != null) "  \u2193  $label" else "  \u2193").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(font.size - 1f)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(16)
        }
        arrowPanel.add(arrowLabel)
        contentPanel.add(arrowPanel)
    }

    private fun displayError(result: ProbeExecutionResult) {
        headerLabel.text = "Probe failed"
        headerLabel.icon = UIUtil.getErrorIcon()

        val errorPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val msgLabel = JBLabel(result.errorMessage ?: "Unknown error").apply {
            foreground = JBColor.RED
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        errorPanel.add(msgLabel)

        if (result.errorTraceback != null) {
            errorPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            val traceArea = JTextArea(result.errorTraceback).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
                foreground = UIUtil.getInactiveTextColor()
                background = UIUtil.getPanelBackground()
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4)
            }
            val scrollable = JBScrollPane(traceArea).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
                preferredSize = Dimension(0, JBUI.scale(200))
            }
            errorPanel.add(scrollable)
        }

        contentPanel.add(errorPanel)
    }

    companion object {
        fun computeDiffs(before: EntrySnapshot, after: EntrySnapshot): List<FieldDiff> {
            val beforeMap = before.fields.associateBy { it.key }
            val afterMap = after.fields.associateBy { it.key }
            val allKeys = LinkedHashSet<String>()
            allKeys.addAll(beforeMap.keys)
            allKeys.addAll(afterMap.keys)

            return allKeys.map { key ->
                val b = beforeMap[key]
                val a = afterMap[key]
                when {
                    b == null && a != null -> FieldDiff(key, FieldDiffStatus.ADDED, null, a)
                    b != null && a == null -> FieldDiff(key, FieldDiffStatus.REMOVED, b, null)
                    b != null && a != null && fieldChanged(b, a) ->
                        FieldDiff(key, FieldDiffStatus.MODIFIED, b, a)
                    else -> FieldDiff(key, FieldDiffStatus.UNCHANGED, b, a)
                }
            }
        }

        private fun fieldChanged(b: FieldSnapshot, a: FieldSnapshot): Boolean {
            return b.pythonType != a.pythonType ||
                    b.shape != a.shape ||
                    b.dtype != a.dtype ||
                    b.minValue != a.minValue ||
                    b.maxValue != a.maxValue ||
                    b.sizeBytes != a.sizeBytes ||
                    b.preview != a.preview
        }
    }
}
