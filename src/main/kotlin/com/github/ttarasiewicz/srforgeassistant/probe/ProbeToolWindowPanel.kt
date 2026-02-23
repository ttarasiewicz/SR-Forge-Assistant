package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.jetbrains.yaml.psi.YAMLFile
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Holds all parameters needed to re-run a probe without showing the dialog.
 */
data class ProbeRunConfig(
    val yamlFilePath: String,
    val datasetPath: String,
    val pipeline: DatasetNode,
    val pathOverrides: Map<String, String>,
    val projectPaths: List<String>
)

/**
 * Main panel for the Pipeline Probe tool window.
 * Displays a block-based flow diagram with expandable step and field blocks.
 * Includes Re-run and Configure buttons in the header toolbar.
 */
class ProbeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
    }
    private val scrollPane = JBScrollPane(contentPanel)
    private val headerLabel = JBLabel("No probe results yet.")

    private val rerunButton = JButton("Re-run").apply {
        icon = AllIcons.Actions.Refresh
        toolTipText = "Re-run the last probe (same dataset and settings)"
        isEnabled = false
        addActionListener { rerunProbe() }
    }

    private val configureButton = JButton("Configure...").apply {
        icon = ProbeIcons.Probe
        toolTipText = "Select dataset and configure paths"
        addActionListener { configureAndRun() }
    }

    /** Stored config from the last successful run. */
    var lastRunConfig: ProbeRunConfig? = null
        private set


    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 12, 6, 8)
        }
        headerPanel.add(headerLabel, BorderLayout.WEST)

        val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(rerunButton)
            add(configureButton)
        }
        headerPanel.add(buttonBar, BorderLayout.EAST)

        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Store the run config (called by [PipelineProbeAction] after launching a probe)
     * and display the result.
     */
    fun displayResult(result: ProbeExecutionResult, config: ProbeRunConfig? = null) {
        if (config != null) {
            lastRunConfig = config
            rerunButton.isEnabled = true
        }

        contentPanel.removeAll()

        if (result.result != null) {
            // Show partial or full results — even if success=false, we display
            // whatever steps completed before the error
            val hasErrors = hasSnapshotErrors(result.result)
            if (hasErrors) {
                headerLabel.text = "Pipeline probe failed at step (${result.executionTimeMs}ms)"
                headerLabel.icon = UIUtil.getErrorIcon()
            } else {
                headerLabel.text = "Pipeline probe completed in ${result.executionTimeMs}ms"
                headerLabel.icon = ProbeIcons.Probe
            }
            displayDatasetResult(result.result, datasetNode = lastRunConfig?.pipeline)
        } else {
            displayError(result)
        }

        contentPanel.revalidate()
        contentPanel.repaint()

        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = 0
        }
    }

    // ── Re-run ──────────────────────────────────────────────────────────

    private fun rerunProbe() {
        val config = lastRunConfig ?: return

        // Re-parse the YAML to pick up any edits since the last run
        val freshConfig = com.intellij.openapi.application.ReadAction.compute<ProbeRunConfig?, Throwable> {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(config.yamlFilePath) ?: return@compute null
            val yamlFile = PsiManager.getInstance(project).findFile(vf) as? YAMLFile
                ?: return@compute null
            val datasets = YamlPipelineParser.findDatasetNodes(yamlFile, project)
            val freshNode = datasets.firstOrNull { it.first == config.datasetPath }?.second
                ?: return@compute null
            config.copy(pipeline = freshNode)
        } ?: config

        val script = ProbeScriptGenerator.loadScript()
        val configJson = ProbeScriptGenerator.generateConfig(
            yamlFilePath = freshConfig.yamlFilePath,
            datasetPath = freshConfig.datasetPath,
            pipeline = freshConfig.pipeline,
            pathOverrides = freshConfig.pathOverrides,
            projectPaths = freshConfig.projectPaths
        )

        executeProbe(script, configJson, freshConfig)
    }

    // ── Configure & Run ─────────────────────────────────────────────────

    private fun configureAndRun() {
        // PSI access requires a read action
        val (yamlFile, datasets) = com.intellij.openapi.application.ReadAction.compute<Pair<YAMLFile?, List<Pair<String, DatasetNode>>>, Throwable> {
            val file = findYamlFile()
            val nodes = if (file != null) YamlPipelineParser.findDatasetNodes(file, project) else emptyList()
            file to nodes
        }

        if (yamlFile == null) {
            showNotification("No YAML file is open. Open an SR-Forge config file first.")
            return
        }
        if (datasets.isEmpty()) {
            showNotification("No dataset definitions found in this YAML file.")
            return
        }

        val dialog = DatasetSelectorDialog(project, datasets)
        if (!dialog.showAndGet()) return

        val selectedPath = dialog.selectedDatasetPath
        val pathOverrides = dialog.pathOverrides
        val selectedNode = datasets.firstOrNull { it.first == selectedPath }?.second ?: return

        val sdk = ProbeExecutor.getPythonSdk(project)
        if (sdk == null) {
            showNotification("No Python SDK configured for this project.")
            return
        }
        if (ProbeExecutor.getPythonPath(sdk) == null) {
            showNotification("Cannot determine Python interpreter path from SDK.")
            return
        }

        val yamlFilePath = yamlFile.virtualFile?.path ?: return
        val projectPaths = ProbeExecutor.getProjectSourcePaths(project)

        val config = ProbeRunConfig(
            yamlFilePath = yamlFilePath,
            datasetPath = selectedPath,
            pipeline = selectedNode,
            pathOverrides = pathOverrides,
            projectPaths = projectPaths
        )

        val script = ProbeScriptGenerator.loadScript()
        val configJson = ProbeScriptGenerator.generateConfig(
            yamlFilePath = config.yamlFilePath,
            datasetPath = config.datasetPath,
            pipeline = config.pipeline,
            pathOverrides = config.pathOverrides,
            projectPaths = config.projectPaths
        )

        executeProbe(script, configJson, config)
    }

    // ── Shared execution ────────────────────────────────────────────────

    private fun executeProbe(script: String, configJson: String, config: ProbeRunConfig) {
        rerunButton.isEnabled = false
        headerLabel.text = "Running probe..."
        headerLabel.icon = null

        object : Task.Backgroundable(project, "Running Pipeline Probe...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = ProbeExecutor.execute(project, script, configJson, indicator)
                ApplicationManager.getApplication().invokeLater {
                    displayResult(result, config)
                }
            }
        }.queue()
    }

    private fun findYamlFile(): YAMLFile? {
        // Try the last run's file first
        val lastPath = lastRunConfig?.yamlFilePath
        if (lastPath != null) {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(lastPath)
            val psi = vf?.let { PsiManager.getInstance(project).findFile(it) }
            if (psi is YAMLFile) return psi
        }
        // Fall back to the currently open editor
        val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return vf?.let { PsiManager.getInstance(project).findFile(it) } as? YAMLFile
    }

    private fun showNotification(message: String) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("SR-Forge Assistant")
                .createNotification(message, NotificationType.WARNING)
                .notify(project)
        } catch (_: Exception) {
            com.intellij.openapi.ui.Messages.showWarningDialog(project, message, "Pipeline Probe")
        }
    }

    // ── Result visualization ────────────────────────────────────────────

    /**
     * Display a dataset result and return its last snapshot,
     * so a wrapping dataset can diff against it.
     */
    private fun displayDatasetResult(
        result: DatasetProbeResult,
        previousSnapshot: EntrySnapshot? = null,
        datasetNode: DatasetNode? = null
    ): EntrySnapshot? {
        var innerLastSnapshot: EntrySnapshot? = previousSnapshot
        if (result.innerResult != null) {
            innerLastSnapshot = displayDatasetResult(
                result.innerResult, previousSnapshot, datasetNode?.wrappedDataset
            )
            addConnector("Wrapped by ${result.datasetName}")
        }

        // Dataset block
        addDatasetBlock(result)

        // If inner pipeline failed, this dataset was never reached — show skip notice
        val snapshots = result.snapshots
        if (snapshots.isEmpty() && result.innerResult != null && hasSnapshotErrors(result.innerResult)) {
            addSkippedNotice()
            return null
        }

        // Step blocks
        for (i in snapshots.indices) {
            val snapshot = snapshots[i]

            // Error snapshot — render as an error block
            if (snapshot.errorMessage != null) {
                addConnector(null)
                addErrorStepBlock(snapshot)
                continue
            }

            val diffs = if (i > 0) {
                // Find the previous NON-error snapshot for diffing
                val prevSnapshot = snapshots.take(i).lastOrNull { it.errorMessage == null }
                if (prevSnapshot != null) computeDiffs(prevSnapshot, snapshot) else {
                    snapshot.fields.map { f ->
                        val childDiffs = f.children?.map { c ->
                            FieldDiff(c.key, FieldDiffStatus.UNCHANGED, null, c)
                        }
                        FieldDiff(f.key, FieldDiffStatus.UNCHANGED, null, f, childDiffs)
                    }
                }
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

        // Cache block — show after all transforms if this dataset has caching configured
        val cacheDir = datasetNode?.cacheDir
        if (cacheDir != null) {
            addConnector(null)
            addCacheBlock(cacheDir)
        }

        return snapshots.lastOrNull { it.errorMessage == null }
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

    private fun addCacheBlock(cacheDir: String) {
        val block = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                g2.color = CACHE_BLOCK_BORDER
                g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
            }
        }.apply {
            isOpaque = false
            background = CACHE_BLOCK_BG
            border = JBUI.Borders.empty(8, 14)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40))
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val label = JBLabel("Cached \u2192 $cacheDir").apply {
            foreground = JBColor(Color(0x004D40), Color(0xB2DFDB))
            font = font.deriveFont(Font.BOLD)
            icon = AllIcons.Actions.Lightning
            iconTextGap = JBUI.scale(6)
        }
        block.add(label, BorderLayout.WEST)

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

    private fun addErrorStepBlock(snapshot: EntrySnapshot) {
        val block = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ERROR_BLOCK_BG
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(8), JBUI.scale(8))
                g2.color = ERROR_BLOCK_BORDER
                g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(10, 12)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        // Header: error icon + transform name
        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }
        leftHeader.add(JBLabel(UIUtil.getErrorIcon()).apply {
            border = JBUI.Borders.emptyRight(6)
        })
        leftHeader.add(JBLabel(snapshot.stepLabel).apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
            foreground = JBColor(Color(0xC62828), Color(0xEF5350))
        })
        headerRow.add(leftHeader, BorderLayout.WEST)
        headerRow.add(JBLabel("step ${snapshot.stepIndex}").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(font.size - 1f)
            border = JBUI.Borders.emptyRight(4)
        }, BorderLayout.EAST)
        block.add(headerRow)

        // Error message
        block.add(Box.createVerticalStrut(JBUI.scale(6)))
        block.add(JBLabel(snapshot.errorMessage ?: "Unknown error").apply {
            foreground = JBColor(Color(0xC62828), Color(0xEF5350))
            alignmentX = Component.LEFT_ALIGNMENT
        })

        // Traceback (collapsible)
        if (snapshot.errorTraceback != null) {
            block.add(Box.createVerticalStrut(JBUI.scale(6)))
            val tracePanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                isVisible = false
            }
            val traceArea = JTextArea(snapshot.errorTraceback).apply {
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
            tracePanel.add(scrollable)

            val toggleLabel = JBLabel("Show traceback \u25B8").apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size - 1f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            toggleLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    tracePanel.isVisible = !tracePanel.isVisible
                    toggleLabel.text = if (tracePanel.isVisible) "Hide traceback \u25BE" else "Show traceback \u25B8"
                    block.revalidate()
                    block.repaint()
                }
            })
            block.add(toggleLabel)
            block.add(tracePanel)
        }

        contentPanel.add(block)
    }

    private fun addSkippedNotice() {
        val notice = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 14)
        }
        notice.add(JBLabel(AllIcons.General.Warning).apply {
            border = JBUI.Borders.emptyRight(2)
        })
        notice.add(JBLabel("Skipped \u2014 inner dataset pipeline failed").apply {
            foreground = UIUtil.getInactiveTextColor()
            font = font.deriveFont(Font.ITALIC)
        })
        contentPanel.add(notice)
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

        // Error step blocks
        private val ERROR_BLOCK_BG = JBColor(Color(0xFFEBEE), Color(0x3B1B1B))
        private val ERROR_BLOCK_BORDER = JBColor(Color(0xEF9A9A), Color(0xC62828))

        // Cache blocks — teal/cyan
        private val CACHE_BLOCK_BG = JBColor(Color(0xE0F2F1), Color(0x1B3B36))
        private val CACHE_BLOCK_BORDER = JBColor(Color(0x80CBC4), Color(0x00897B))

        /** Check whether any snapshot in the result tree contains an error. */
        private fun hasSnapshotErrors(result: DatasetProbeResult): Boolean {
            if (result.snapshots.any { it.errorMessage != null }) return true
            return result.innerResult?.let { hasSnapshotErrors(it) } == true
        }

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
