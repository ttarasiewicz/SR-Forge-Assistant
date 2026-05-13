package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLMapping
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
    val projectPaths: List<String>,
    /**
     * For each composite dataset path (e.g. `dataset.training.params.datasets`),
     * the user-picked branch index. Defaulted to 0 inside the probe script for
     * any composite not listed here.
     */
    val branchChoices: Map<String, Int> = emptyMap()
)

/**
 * Main panel for the Pipeline Probe tool window.
 * Displays a block-based flow diagram with expandable step and field blocks.
 * Renders blocks incrementally as streaming events arrive from the probe script.
 */
class ProbeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
    }
    private val scrollPane = JBScrollPane(contentPanel)
    private val headerLabel = JBLabel("No probe results yet.")

    /** Temp directory for .npy tensor files from the current probe run. */
    private var tensorTempDir: java.io.File? = null

    private val rerunButton = JButton("Re-run").apply {
        icon = AllIcons.Actions.Refresh
        toolTipText = "Re-run the last probe (same dataset and settings)"
        isEnabled = false
        addActionListener { rerunProbe() }
    }

    private val stopButton = JButton().apply {
        icon = AllIcons.Actions.Suspend
        text = "Stop"
        toolTipText = "Cancel any running probe and clear all results " +
            "(frees memory used by snapshots and tensor files)"
        isEnabled = false
        addActionListener { stopAndClear() }
    }

    private val configureButton = JButton("Configure...").apply {
        icon = ProbeIcons.Probe
        toolTipText = "Select dataset and configure paths"
        addActionListener { configureAndRun() }
    }

    /** Progress indicator of the currently-running probe, if any. */
    private var activeProbeIndicator: ProgressIndicator? = null

    /** Whether the panel has any results / overlay / tensor data to clear. */
    private val hasState: Boolean
        get() = lastRunConfig != null
            || pathOverlay != null
            || tensorTempDir != null
            || contentPanel.componentCount > 0

    /** Stored config from the last successful run. */
    var lastRunConfig: ProbeRunConfig? = null
        private set

    /** Active path overlay drawn on top of the YAML editor. */
    private var pathOverlay: YamlPathOverlay? = null
    private var overlayEditor: Editor? = null

    /**
     * Deepest dataset reachable in the most-recently-completed probe, with
     * the current branch choices applied. After a successful run, the path
     * overlay always shows root → this leaf; hovering a dropdown item
     * temporarily replaces it with a preview.
     */
    private var actualPathLeaf: DatasetNode? = null


    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 12, 6, 8)
        }
        headerPanel.add(headerLabel, BorderLayout.WEST)

        val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(stopButton)
            add(rerunButton)
            add(configureButton)
        }
        headerPanel.add(buttonBar, BorderLayout.EAST)

        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    // ── Streaming execution ────────────────────────────────────────────

    /**
     * Launch a probe execution with streaming UI updates.
     * Called by both [PipelineProbeAction] and internal re-run/configure.
     */
    fun executeProbe(script: String, configJson: String, config: ProbeRunConfig) {
        cleanupTensorDir()
        clearPathOverlay()
        val tDir = createTensorDir()

        // Inject tensorDir into the config JSON
        val gson = com.google.gson.Gson()
        val configObj = gson.fromJson(configJson, com.google.gson.JsonObject::class.java)
        configObj.addProperty("tensorDir", tDir.absolutePath)
        val enrichedConfigJson = gson.toJson(configObj)

        lastRunConfig = config
        rerunButton.isEnabled = false
        stopButton.isEnabled = true
        headerLabel.text = "Running probe..."
        headerLabel.icon = null
        contentPanel.removeAll()
        contentPanel.revalidate()
        contentPanel.repaint()

        // Build path→DatasetNode map for cache dir lookup on dataset_end
        val nodeMap = buildNodeMap(config.pipeline)

        // The deepest leaf is deterministic given the config + branch choices —
        // start the YAML path trace immediately so it animates in parallel with
        // the probe rather than waiting for the run to finish.
        val deepestLeaf = walkDeepestLeaf(config.pipeline, config.branchChoices)
        actualPathLeaf = deepestLeaf
        showPathForNode(deepestLeaf)

        // Streaming state — mutated on EDT by the onEvent callback
        var lastSnapshot: EntrySnapshot? = null
        var hasErrors = false

        val onEvent: (ProbeEvent) -> Unit = { event ->
            when (event) {
                is ProbeEvent.DatasetStart -> {
                    val node = nodeMap[event.datasetPath]
                    addDatasetBlock(event.datasetName, event.datasetTarget, node)
                }
                is ProbeEvent.Snapshot -> {
                    val snapshot = event.snapshot
                    val diffs = if (lastSnapshot != null) {
                        computeDiffs(lastSnapshot!!, snapshot)
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
                    lastSnapshot = snapshot
                }
                is ProbeEvent.StepError -> {
                    hasErrors = true
                    val errorSnapshot = EntrySnapshot(
                        stepLabel = event.stepLabel,
                        stepIndex = event.stepIndex,
                        fields = emptyList(),
                        isBatched = false,
                    )
                    addConnector(null)
                    addErrorStepBlock(errorSnapshot, event.errorMessage, event.errorTraceback)
                }
                is ProbeEvent.InitError -> {
                    hasErrors = true
                    addInitErrorBlock(event.errorMessage, event.errorTraceback)
                }
                is ProbeEvent.Connector -> {
                    // Keep lastSnapshot so the outer dataset's first snapshot
                    // diffs against the inner dataset's last step
                    addConnector(event.label)
                }
                is ProbeEvent.Skipped -> {
                    hasErrors = true
                    addSkippedNotice()
                }
                is ProbeEvent.DatasetEnd -> {
                    val node = nodeMap[event.datasetPath]
                    val cacheDir = node?.cacheDir
                    if (cacheDir != null) {
                        addConnector(null)
                        addCacheBlock(cacheDir)
                    }
                }
                is ProbeEvent.Complete -> {
                    if (hasErrors) {
                        headerLabel.text = "Pipeline probe failed at step (${event.executionTimeMs}ms)"
                        headerLabel.icon = UIUtil.getErrorIcon()
                    } else {
                        headerLabel.text = "Pipeline probe completed in ${event.executionTimeMs}ms"
                        headerLabel.icon = ProbeIcons.Probe
                    }
                    rerunButton.isEnabled = lastRunConfig != null
                    stopButton.isEnabled = hasState
                    activeProbeIndicator = null
                    // The path overlay was already started at executeProbe();
                    // nothing to do here — it stays put through completion
                    // and the pulse continues on the leaf.
                }
                is ProbeEvent.Error -> {
                    hasErrors = true
                    addTopLevelErrorBlock(event.errorMessage, event.errorTraceback)
                }
            }

            contentPanel.revalidate()
            contentPanel.repaint()

            // Auto-scroll to bottom
            SwingUtilities.invokeLater {
                val vsb = scrollPane.verticalScrollBar
                vsb.value = vsb.maximum
            }
        }

        object : Task.Backgroundable(project, "Running Pipeline Probe...", true) {
            override fun run(indicator: ProgressIndicator) {
                activeProbeIndicator = indicator
                try {
                    ProbeExecutor.executeStreaming(project, script, enrichedConfigJson, indicator, onEvent)
                } finally {
                    activeProbeIndicator = null
                }
            }
        }.queue()
    }

    // ── Re-run ──────────────────────────────────────────────────────────

    private fun rerunProbe() {
        val config = lastRunConfig ?: return

        // Save all documents so the Python probe reads the latest content from disk
        WriteAction.runAndWait<Throwable> {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

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
            projectPaths = freshConfig.projectPaths,
            branchChoices = freshConfig.branchChoices
        )

        executeProbe(script, configJson, freshConfig)
    }

    // ── Configure & Run ─────────────────────────────────────────────────

    private fun configureAndRun() {
        // Save all documents so the Python probe reads the latest content from disk
        WriteAction.runAndWait<Throwable> {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

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
            projectPaths = config.projectPaths,
            branchChoices = config.branchChoices
        )

        executeProbe(script, configJson, config)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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

    // ── Block rendering ────────────────────────────────────────────────

    private fun addDatasetBlock(name: String, target: String, node: DatasetNode? = null) {
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

        val nameLabel = JBLabel(name).apply {
            foreground = Color.WHITE
            font = font.deriveFont(Font.BOLD, font.size + 2f)
            icon = ProbeIcons.Probe
            iconTextGap = JBUI.scale(8)
        }
        block.add(nameLabel, BorderLayout.WEST)

        // Composite datasets (e.g. ConcatDataset) get an inline branch picker
        // in place of the target FQN label. Changing the selection re-runs the
        // probe with the new branch choice so the upstream pipeline rendered
        // above this block updates accordingly.
        if (node != null && node.branches.isNotEmpty()) {
            val compositeKey = "${node.path}.params.${node.branchesParamKey ?: "datasets"}"
            val currentIdx = (lastRunConfig?.branchChoices?.get(compositeKey) ?: 0)
                .coerceIn(0, node.branches.size - 1)
            val branchNames = node.branches.mapIndexed { i, b -> "[$i] ${b.displayName}" }
            val combo = ComboBox(branchNames.toTypedArray()).apply {
                selectedIndex = currentIdx
                toolTipText = target
            }
            combo.addActionListener {
                if (!rerunButton.isEnabled) return@addActionListener  // probe running
                val newIdx = combo.selectedIndex
                if (newIdx < 0) return@addActionListener
                val cfg = lastRunConfig ?: return@addActionListener
                if ((cfg.branchChoices[compositeKey] ?: 0) == newIdx) return@addActionListener
                val newChoices = cfg.branchChoices.toMutableMap()
                newChoices[compositeKey] = newIdx
                lastRunConfig = cfg.copy(branchChoices = newChoices)
                rerunProbe()
            }
            attachBranchPreviewOnHover(combo, node)
            block.add(combo, BorderLayout.EAST)
        } else {
            val targetLabel = JBLabel(target).apply {
                foreground = JBColor(Color(0xBBDEFB), Color(0x90CAF9))
                font = font.deriveFont(font.size - 1f)
            }
            block.add(targetLabel, BorderLayout.EAST)
        }

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
            val fieldDetail = FieldDetailPanel(diffs, project)
            fieldDetail.alignmentX = Component.LEFT_ALIGNMENT
            fieldsPanel.add(fieldDetail)
        }

        fieldsPanel.alignmentX = Component.LEFT_ALIGNMENT
        block.add(fieldsPanel)

        contentPanel.add(block)
    }

    private fun addErrorStepBlock(snapshot: EntrySnapshot, errorMessage: String, errorTraceback: String?) {
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
        block.add(JBLabel(errorMessage).apply {
            foreground = JBColor(Color(0xC62828), Color(0xEF5350))
            alignmentX = Component.LEFT_ALIGNMENT
        })

        // Traceback (collapsible)
        addTracebackToggle(block, errorTraceback)

        contentPanel.add(block)
    }

    private fun addInitErrorBlock(errorMessage: String, errorTraceback: String?) {
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

        val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        headerRow.add(JBLabel(UIUtil.getErrorIcon()).apply {
            border = JBUI.Borders.emptyRight(6)
        })
        headerRow.add(JBLabel("Instantiation failed").apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
            foreground = JBColor(Color(0xC62828), Color(0xEF5350))
        })
        block.add(headerRow)

        block.add(Box.createVerticalStrut(JBUI.scale(6)))
        block.add(JBLabel(errorMessage).apply {
            foreground = JBColor(Color(0xC62828), Color(0xEF5350))
            alignmentX = Component.LEFT_ALIGNMENT
        })

        addTracebackToggle(block, errorTraceback)

        contentPanel.add(block)
    }

    private fun addTopLevelErrorBlock(errorMessage: String, errorTraceback: String?) {
        val block = object : JPanel() {
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

        block.add(JBLabel(errorMessage).apply {
            foreground = JBColor(Color(0xC62828), Color(0xEF5350))
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        })

        if (errorTraceback != null) {
            block.add(Box.createVerticalStrut(JBUI.scale(8)))
            val traceArea = JTextArea(errorTraceback).apply {
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
            block.add(scrollable)
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

    /** Adds a collapsible traceback toggle to a block panel. */
    private fun addTracebackToggle(block: JPanel, errorTraceback: String?) {
        if (errorTraceback == null) return

        block.add(Box.createVerticalStrut(JBUI.scale(6)))
        val tracePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }
        val traceArea = JTextArea(errorTraceback).apply {
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

    override fun removeNotify() {
        super.removeNotify()
        cleanupTensorDir()
    }

    /** Create a fresh temp directory for tensor .npy files. */
    private fun createTensorDir(): java.io.File {
        val dir = java.nio.file.Files.createTempDirectory("srforge_probe_tensors_").toFile()
        tensorTempDir = dir
        return dir
    }

    /** Delete the tensor temp directory and all its contents. */
    private fun cleanupTensorDir() {
        tensorTempDir?.let { dir ->
            try {
                dir.walkBottomUp().forEach { it.delete() }
            } catch (_: Exception) { }
        }
        tensorTempDir = null
    }

    // ── YAML path overlay ─────────────────────────────────────────────────

    /**
     * Animate a traced path overlay in the source editor connecting every
     * ancestor key of [node] down to the leaf, then settle into a continuous
     * pulse at the leaf. Replaces any active path overlay. Scrolls the leaf
     * into view if it's currently off-screen.
     *
     * No-op if the YAML editor isn't open or the offset has gone stale.
     */
    private fun showPathForNode(node: DatasetNode) {
        val cfg = lastRunConfig ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(cfg.yamlFilePath) ?: return
        val editor = FileEditorManager.getInstance(project).getEditors(vf)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor
            ?: return

        val offsets = ReadAction.compute<List<Int>, Throwable> {
            val psiFile = PsiManager.getInstance(project).findFile(vf) as? YAMLFile
                ?: return@compute emptyList()
            collectPathOffsets(psiFile, node.yamlOffset)
        }
        if (offsets.isEmpty()) return

        // If the editor we're decorating changed (different file/editor),
        // discard the old overlay before constructing a new one.
        if (overlayEditor !== editor) {
            clearPathOverlay()
        } else {
            pathOverlay?.clear()
        }
        val overlay = pathOverlay ?: YamlPathOverlay(editor).also {
            pathOverlay = it
            overlayEditor = editor
        }
        overlay.show(offsets)

        editor.scrollingModel.scrollTo(
            editor.offsetToLogicalPosition(offsets.last()),
            ScrollType.MAKE_VISIBLE,
        )
    }

    private fun clearPathOverlay() {
        pathOverlay?.clear()
        pathOverlay = null
        overlayEditor = null
    }

    /**
     * Cancel any running probe and tear down everything the panel is holding
     * onto: visualization blocks, path overlay, tensor temp files, and the
     * stored run config. Used by the Stop button — the user invokes it to
     * free memory before launching training (large .npy snapshots and Swing
     * UI for a deep pipeline can add up).
     */
    private fun stopAndClear() {
        // Cancelling the indicator causes ProbeExecutor to destroyProcess()
        // on its next poll cycle (within ~100 ms).
        activeProbeIndicator?.cancel()
        activeProbeIndicator = null

        clearPathOverlay()
        actualPathLeaf = null

        contentPanel.removeAll()
        contentPanel.revalidate()
        contentPanel.repaint()

        cleanupTensorDir()

        lastRunConfig = null
        rerunButton.isEnabled = false
        stopButton.isEnabled = false
        headerLabel.text = "No probe results yet."
        headerLabel.icon = null
    }

    /**
     * Walk the dataset tree following single-child wrappers and the active
     * (or default 0) branch of any composite, returning the deepest reachable
     * leaf. This is what gets visualized in the probe panel for a given run.
     */
    private fun walkDeepestLeaf(node: DatasetNode, choices: Map<String, Int>): DatasetNode {
        val wrapped = node.wrappedDataset
        if (wrapped != null) return walkDeepestLeaf(wrapped, choices)
        if (node.branches.isNotEmpty()) {
            val key = "${node.path}.params.${node.branchesParamKey ?: "datasets"}"
            val idx = (choices[key] ?: 0).coerceIn(0, node.branches.size - 1)
            return walkDeepestLeaf(node.branches[idx], choices)
        }
        return node
    }

    /**
     * Attach hover-preview behavior to a branch-picker combo: opening the
     * popup hooks a MouseMotionListener onto its JList; hovering an item
     * temporarily previews the path that *would* be drawn if that branch
     * were chosen. Closing the popup restores the actual path (or lets a
     * pending re-probe take over).
     */
    private fun attachBranchPreviewOnHover(combo: ComboBox<String>, composite: DatasetNode) {
        var motion: java.awt.event.MouseMotionListener? = null
        var hookedList: javax.swing.JList<*>? = null

        combo.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                val list = comboPopupList(combo) ?: return
                hookedList = list
                val ml = object : java.awt.event.MouseMotionAdapter() {
                    override fun mouseMoved(e: java.awt.event.MouseEvent) {
                        val idx = list.locationToIndex(e.point)
                        if (idx < 0 || idx >= composite.branches.size) return
                        val cfg = lastRunConfig ?: return
                        val previewLeaf = walkDeepestLeaf(composite.branches[idx], cfg.branchChoices)
                        showPathForNode(previewLeaf)
                    }
                }
                list.addMouseMotionListener(ml)
                motion = ml
            }

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {
                motion?.let { hookedList?.removeMouseMotionListener(it) }
                motion = null
                hookedList = null
                // Restore the actual path. If the user picked a different
                // branch, the combo's ActionListener will fire next and
                // trigger a re-probe (which clears the overlay and then
                // sets a new one when the run completes).
                actualPathLeaf?.let { showPathForNode(it) }
            }

            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {
                popupMenuWillBecomeInvisible(e)
            }
        })
    }

    /**
     * Access the JList that the combo's popup is built around. Used to
     * attach mouse-hover listeners for branch path preview.
     */
    private fun comboPopupList(combo: ComboBox<*>): javax.swing.JList<*>? {
        val popup = combo.ui.getAccessibleChild(combo, 0)
            as? javax.swing.plaf.basic.BasicComboPopup
        return popup?.list
    }

    /**
     * Walk up from the target mapping at [yamlOffset], collecting the offset
     * of each ancestor key (or the dash of each ancestor sequence-item). Returns
     * the offsets root-first, with the leaf — the target mapping's containing
     * key or sequence-item — last.
     */
    private fun collectPathOffsets(psiFile: YAMLFile, yamlOffset: Int): List<Int> {
        val element = psiFile.findElementAt(yamlOffset)
            ?: psiFile.findElementAt(yamlOffset + 1)
            ?: return emptyList()
        val targetMapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java)
            ?: return emptyList()

        val out = mutableListOf<Int>()
        var current: com.intellij.psi.PsiElement? = targetMapping
        while (current != null) {
            when (current) {
                is org.jetbrains.yaml.psi.YAMLKeyValue -> {
                    val keyOffset = current.key?.textOffset ?: current.textOffset
                    out.add(keyOffset)
                }
                is org.jetbrains.yaml.psi.YAMLSequenceItem -> {
                    out.add(current.textOffset)
                }
            }
            current = current.parent
        }
        // Walked leaf -> root; flip so root is first, leaf is last.
        return out.asReversed()
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

        /** Build a path→DatasetNode map from a (potentially nested) pipeline node. */
        private fun buildNodeMap(node: DatasetNode): Map<String, DatasetNode> {
            val map = mutableMapOf(node.path to node)
            if (node.wrappedDataset != null) {
                map.putAll(buildNodeMap(node.wrappedDataset))
            }
            for (branch in node.branches) {
                map.putAll(buildNodeMap(branch))
            }
            return map
        }
    }
}
