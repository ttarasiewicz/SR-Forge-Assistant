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
import com.intellij.openapi.util.TextRange
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
 * Holds all parameters needed to re-run a probe.
 */
data class ProbeRunConfig(
    val yamlFilePath: String,
    val datasetPath: String,
    val pipeline: DatasetNode,
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

    /**
     * Active visual chrome. Read live via [ProbeChrome.current] so any
     * settings-change hot-swap is reflected next time a component asks for
     * style (factory methods, style appliers, etc.).
     */
    private val chrome: ProbeChrome get() = ProbeChrome.current

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(12)
    }

    /**
     * Anchor wrapper that pins [contentPanel] to the top of the viewport AND
     * forces it to track the viewport's width so no horizontal scrollbar
     * ever appears.
     *
     * **Vertical**: `BorderLayout.NORTH` keeps [contentPanel] at its preferred
     * height — without it, `ViewportLayout` would stretch the non-`Scrollable`
     * view to fill the viewport. The extra height would cascade through nested
     * `BoxLayout.Y_AXIS` whose children have `maxSize.height = MAX_VALUE`,
     * ballooning blocks and growing field rows.
     *
     * **Horizontal**: implementing `Scrollable.getScrollableTracksViewportWidth
     * = true` makes the viewport pin the view's width to the viewport's, so
     * long content (e.g. a verbose tensor preview) gets clipped or wrapped
     * within the visible width instead of pushing block-trailing buttons
     * ("Visualize", "Go to YAML") off the right edge.
     */
    private val contentAnchor: JPanel = object : JPanel(BorderLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle, orientation: Int, direction: Int,
        ): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle, orientation: Int, direction: Int,
        ): Int = visibleRect.height
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }.apply {
        isOpaque = false
        add(contentPanel, BorderLayout.NORTH)
    }
    private val scrollPane = JBScrollPane(contentAnchor)
    private val headerLabel = JBLabel("").also {
        chrome.applyHeaderLabelStyle(it)
    }

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
        isVisible = false
        addActionListener { stopAndClear() }
    }

    /**
     * Status label that appears as the last item in the content panel while
     * a probe is running. Sits just below the most-recently-emitted block so
     * the user sees where the next snapshot will land. Carries an animated
     * spinner icon plus the current step name. Theme-aware foreground keeps
     * it readable on both light and dark themes (which painting the string
     * directly on a JProgressBar would not).
     */
    private val progressLabel = JBLabel("").apply {
        icon = com.intellij.ui.AnimatedIcon.Default.INSTANCE
        iconTextGap = JBUI.scale(6)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** Thin indeterminate bar paired with [progressLabel]. Swapped on chrome change. */
    private var progressBar: JComponent = chrome.newProgressBar()

    /**
     * Bundled "step strip" widget — label + thin bar stacked vertically.
     * Added to [contentPanel] at probe start, moved to the bottom after each
     * block insertion, removed on Complete/Stop.
     */
    private val progressStrip = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 0, 4, 0)
        add(progressLabel)
        add(javax.swing.Box.createVerticalStrut(JBUI.scale(3)))
        add(progressBar)
    }

    private val configureButton = JButton("Pick dataset").apply {
        icon = ProbeIcons.Probe
        toolTipText = "Pick a dataset to probe from the open YAML " +
            "(hover gold markers to preview the path, click to run)"
        addActionListener { enterMarkerMode() }
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
     * Start node of the path overlay for the currently-running or
     * most-recently-completed probe. The overlay traces from this node down
     * to its deepest leaf. Hovering a dropdown item temporarily replaces it
     * with a preview; popup close restores this one.
     */
    private var actualPathStartNode: DatasetNode? = null

    /**
     * Top-of-tool-window header panel. Captured as a field so we can re-style
     * (border, opacity, repaint) on a chrome hot-swap.
     */
    private val headerPanel: JPanel = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            if (!chrome.paintHeaderBackground(g, width, height)) {
                super.paintComponent(g)
            }
        }
    }

    /** Chrome-supplied status indicator on the left of the header. */
    private var statusChip: StatusChip = chrome.newStatusChip()
    private val statusChipHolder: JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(statusChip.component, BorderLayout.CENTER)
    }

    /**
     * Holder for the WEST side of the header: status chip + a small details
     * label (current step name during run, etc). The chip is mutable via
     * [statusChipHolder]; the label is the existing [headerLabel].
     */
    private val headerLeft: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(statusChipHolder)
        add(headerLabel)
    }

    /** Toolbar buttons holder. Captured to apply chrome-specific styling on hot-swap. */
    private val toolbarButtons: JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(stopButton)
        add(rerunButton)
        add(configureButton)
    }

    /**
     * Two-card content area: "empty" shows the chrome's empty-state widget;
     * "content" shows the scrolling pipeline view. We swap by name when
     * [recordedActions] transitions between empty and non-empty.
     */
    private val contentCardLayout = CardLayout()
    private val contentCards: JPanel = JPanel(contentCardLayout).apply {
        isOpaque = false
    }
    private var emptyStateView: JComponent = chrome.newEmptyState { enterMarkerMode() }

    /** Wall-clock timestamp when the currently-running probe started. */
    private var probeStartTime: Long = 0L


    init {
        headerPanel.border = chrome.headerBorder
        headerPanel.isOpaque = chrome.isHeaderOpaque
        headerPanel.add(headerLeft, BorderLayout.WEST)
        headerPanel.add(toolbarButtons, BorderLayout.EAST)

        contentCards.add(emptyStateView, CARD_EMPTY)
        contentCards.add(scrollPane, CARD_CONTENT)
        contentCardLayout.show(contentCards, CARD_EMPTY)

        applyToolbarButtonStyles()

        add(headerPanel, BorderLayout.NORTH)
        add(contentCards, BorderLayout.CENTER)

        // Hot-swap: when the display-mode setting changes, refresh the chrome
        // and rebuild every block from the recorded actions. The probe data
        // (snapshots, diffs, config) is untouched — only the visual chrome.
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(
                com.github.ttarasiewicz.srforgeassistant.SrForgeHighlightSettings.TOPIC,
                com.github.ttarasiewicz.srforgeassistant.SrForgeHighlightSettings.SettingsListener {
                    if (ProbeChrome.refresh()) rebuildUiForChromeChange()
                },
            )
    }

    /**
     * Record of every block-emit that's currently visible. On a chrome
     * hot-swap we clear [contentPanel] and replay each entry through the
     * new chrome — the snapshot data is preserved, only the visuals change.
     */
    private val recordedActions = mutableListOf<RenderAction>()

    /**
     * Apply chrome-aware text/icon styling to every toolbar button. Icon-only
     * chromes hide the button label (icon + tooltip remain); text-bearing
     * chromes restore the original text from a stashed client property.
     */
    private fun applyToolbarButtonStyles() {
        styleToolbarButton(rerunButton, primary = false)
        styleToolbarButton(stopButton, primary = false)
        styleToolbarButton(configureButton, primary = true)
    }

    private fun styleToolbarButton(button: JButton, primary: Boolean) {
        chrome.applyButtonStyle(button)
        if (button.getClientProperty(ORIGINAL_TEXT_KEY) == null && button.text != null) {
            button.putClientProperty(ORIGINAL_TEXT_KEY, button.text)
        }
        val origText = button.getClientProperty(ORIGINAL_TEXT_KEY) as? String
        val shouldHideText = chrome.iconOnlyToolbarButtons && !primary && button.icon != null
        button.text = if (shouldHideText) null else origText
    }

    /**
     * Apply the current chrome to top-level widgets (header bar, buttons,
     * progress bar), then clear [contentPanel] and replay every block from
     * [recordedActions]. Called when the user toggles display mode while
     * results are showing.
     */
    private fun rebuildUiForChromeChange() {
        val actions = recordedActions.toList()
        recordedActions.clear()

        // Re-style top widgets.
        chrome.applyHeaderLabelStyle(headerLabel)
        applyToolbarButtonStyles()
        headerPanel.border = chrome.headerBorder
        headerPanel.isOpaque = chrome.isHeaderOpaque
        headerPanel.repaint()

        // Swap the status chip — preserve current state in the new instance.
        statusChip.dispose()
        statusChip = chrome.newStatusChip()
        statusChipHolder.removeAll()
        statusChipHolder.add(statusChip.component, BorderLayout.CENTER)
        // Re-emit the current state through the fresh chip.
        when {
            stopButton.isVisible && stopButton.isEnabled -> statusChip.setRunning(probeStartTime)
            // Other states (idle/complete/failed) — we don't track post-state,
            // so default to idle on chrome swap. Practical enough for now.
            else -> statusChip.setIdle()
        }
        statusChipHolder.revalidate()
        statusChipHolder.repaint()

        // Swap the empty-state view.
        contentCards.remove(emptyStateView)
        emptyStateView = chrome.newEmptyState { enterMarkerMode() }
        contentCards.add(emptyStateView, CARD_EMPTY)

        // Swap the progress-bar widget (each chrome owns its own type).
        val wasProgressShown = (0 until contentPanel.componentCount)
            .any { contentPanel.getComponent(it) === progressStrip }
        progressStrip.remove(progressBar)
        chrome.disposeProgressBar(progressBar)
        progressBar = chrome.newProgressBar()
        progressStrip.add(progressBar)

        // Throw away every block (their removeNotify will tear down their
        // chrome state) and replay through the new chrome.
        contentPanel.removeAll()
        for (action in actions) replay(action)
        if (wasProgressShown) {
            contentPanel.add(progressStrip)
        }
        contentPanel.revalidate()
        contentPanel.repaint()

        // If still nothing to show, swap back to the empty card.
        if (recordedActions.isEmpty() && !wasProgressShown) {
            contentCardLayout.show(contentCards, CARD_EMPTY)
        }
    }


    private fun replay(action: RenderAction) {
        when (action) {
            is RenderAction.Dataset ->
                addDatasetBlock(action.name, action.target, action.node)
            is RenderAction.Cache -> addCacheBlock(action.cacheDir, action.yamlOffset)
            is RenderAction.Step -> addStepBlock(action.snapshot, action.diffs, action.yamlOffset)
            is RenderAction.ErrorStep ->
                addErrorStepBlock(action.snapshot, action.errorMessage, action.errorTraceback, action.yamlOffset)
            is RenderAction.InitError ->
                addInitErrorBlock(action.errorMessage, action.errorTraceback, action.yamlOffset)
            is RenderAction.TopLevelError ->
                addTopLevelErrorBlock(action.errorMessage, action.errorTraceback)
            is RenderAction.Connector -> addConnector(action.label)
            is RenderAction.Skipped -> addSkippedNotice()
        }
    }

    /**
     * A compact icon button rendered on each block (when its YAML location is
     * known) that, when clicked, opens the YAML in the editor, scrolls to
     * the corresponding mapping, and flashes a 1-second highlight on the
     * whole block — useful for jumping between the pipeline view and the
     * source of truth.
     */
    private fun createGoToButton(offset: Int): JButton = JButton(AllIcons.Actions.EditSource).apply {
        toolTipText = "Go to YAML definition"
        preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
        maximumSize = preferredSize
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { navigateAndFlashYaml(offset) }
    }

    /**
     * Open the configured YAML in the editor, scroll-centre on the mapping
     * that contains [offset], and play a brief alpha-fading highlight over
     * the whole mapping (~1 s) so the user's eye lands on it.
     */
    private fun navigateAndFlashYaml(offset: Int) {
        val cfg = lastRunConfig ?: return
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(cfg.yamlFilePath)) ?: return
        val descriptor = com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile, offset)
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true) ?: return

        val psiFile = PsiManager.getInstance(project).findFile(vFile) as? YAMLFile
        val element = psiFile?.findElementAt(offset) ?: psiFile?.findElementAt(offset + 1)
        val mapping = element?.let { PsiTreeUtil.getParentOfType(it, YAMLMapping::class.java) }
        val range = mapping?.textRange ?: TextRange(offset, (offset + 1).coerceAtMost(editor.document.textLength))

        // Centre on the start of the mapping.
        editor.scrollingModel.scrollTo(
            editor.offsetToLogicalPosition(range.startOffset),
            ScrollType.CENTER,
        )

        flashYamlRange(editor, range)
    }

    /**
     * Add a temporary custom-rendered highlight over [range] and animate
     * its alpha (0 → peak → 0) over ~1 s, then remove. The highlighter is
     * painted via a [com.intellij.openapi.editor.markup.CustomHighlighterRenderer]
     * so we can drive the alpha from a Swing Timer without recreating the
     * highlighter every frame.
     */
    private fun flashYamlRange(editor: Editor, range: TextRange) {
        val markup = editor.markupModel
        val highlighter = markup.addRangeHighlighter(
            range.startOffset,
            range.endOffset,
            com.intellij.openapi.editor.markup.HighlighterLayer.LAST + 1,
            null,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE,
        )

        val accent = JBColor(Color(0xFFC107), Color(0xFFD54F))
        val durationMs = 1000L
        val startedAt = System.currentTimeMillis()

        highlighter.customRenderer = com.intellij.openapi.editor.markup.CustomHighlighterRenderer { ed, hl, g ->
            val elapsed = System.currentTimeMillis() - startedAt
            val t = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
            val alpha = when {
                t < 0.15 -> t / 0.15                 // fade-in
                t < 0.70 -> 1.0                       // hold
                else -> ((1.0 - t) / 0.30).coerceAtLeast(0.0)  // fade-out
            }
            val peak = 95
            val a = (alpha * peak).toInt().coerceIn(0, 255)
            if (a <= 0) return@CustomHighlighterRenderer

            val g2 = (g as Graphics2D).create() as Graphics2D
            try {
                g2.color = Color(accent.red, accent.green, accent.blue, a)
                val startPos = ed.offsetToLogicalPosition(hl.startOffset)
                val endPos = ed.offsetToLogicalPosition(hl.endOffset)
                val lineHeight = ed.lineHeight
                val startXY = ed.logicalPositionToXY(startPos)
                val endXY = ed.logicalPositionToXY(endPos)
                val visibleArea = ed.scrollingModel.visibleArea
                val left = visibleArea.x
                val right = visibleArea.x + visibleArea.width
                val top = startXY.y
                val bottomLine = (endPos.line - startPos.line + 1)
                val bottom = startXY.y + bottomLine * lineHeight
                g2.fillRoundRect(left + JBUI.scale(2), top, right - left - JBUI.scale(4), bottom - top, JBUI.scale(6), JBUI.scale(6))
                // endXY is not used directly for painting because we span the
                // viewport width and the line-based bottom suffices for a
                // block-style highlight; reference it to avoid an unused warning.
                @Suppress("UNUSED_VARIABLE") val unused = endXY
            } finally {
                g2.dispose()
            }
        }

        val animTimer = javax.swing.Timer(16) {
            val elapsed = System.currentTimeMillis() - startedAt
            editor.contentComponent.repaint()
            if (elapsed >= durationMs) {
                (it.source as javax.swing.Timer).stop()
                try { markup.removeHighlighter(highlighter) } catch (_: Throwable) {}
            }
        }
        animTimer.start()
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
        stopButton.isVisible = true
        probeStartTime = System.currentTimeMillis()
        statusChip.setRunning(probeStartTime)
        headerLabel.text = "Running probe..."
        headerLabel.icon = null
        progressLabel.text = "Starting..."
        contentPanel.removeAll()
        recordedActions.clear()
        contentPanel.add(progressStrip)
        contentPanel.revalidate()
        contentPanel.repaint()
        contentCardLayout.show(contentCards, CARD_CONTENT)

        // Build path→DatasetNode map for cache dir lookup on dataset_end
        val nodeMap = buildNodeMap(config.pipeline)

        // Path trace starts at the probe's root (the user-picked dataset) and
        // animates downward. It begins now, in parallel with the probe.
        actualPathStartNode = config.pipeline
        showPathForNode(config.pipeline)

        // Streaming state — mutated on EDT by the onEvent callback
        var lastSnapshot: EntrySnapshot? = null
        var hasErrors = false
        // Which dataset's pipeline we're currently emitting blocks from.
        // Used to resolve the YAML offset of each step's transform so the
        // "Go to" button can navigate to the right YAML mapping.
        var currentDataset: DatasetNode? = null

        fun transformOffsetFor(stepIndex: Int): Int? {
            val ds = currentDataset ?: return null
            if (stepIndex <= 0) return ds.yamlOffset  // entry → dataset itself
            return ds.transforms.getOrNull(stepIndex - 1)?.yamlOffset ?: ds.yamlOffset
        }

        val onEvent: (ProbeEvent) -> Unit = { event ->
            when (event) {
                is ProbeEvent.DatasetStart -> {
                    val node = nodeMap[event.datasetPath]
                    currentDataset = node
                    addDatasetBlock(event.datasetName, event.datasetTarget, node)
                    progressLabel.text = "Loading ${event.datasetName}..."
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
                    addStepBlock(snapshot, diffs, transformOffsetFor(snapshot.stepIndex))
                    lastSnapshot = snapshot
                    progressLabel.text = if (snapshot.stepIndex == 0) {
                        "Entry from ${snapshot.stepLabel}"
                    } else {
                        "Step ${snapshot.stepIndex}: ${snapshot.stepLabel}"
                    }
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
                    addErrorStepBlock(
                        errorSnapshot,
                        event.errorMessage,
                        event.errorTraceback,
                        transformOffsetFor(event.stepIndex),
                    )
                    progressLabel.text = "Error at step ${event.stepIndex}: ${event.stepLabel}"
                }
                is ProbeEvent.InitError -> {
                    hasErrors = true
                    addInitErrorBlock(event.errorMessage, event.errorTraceback, currentDataset?.yamlOffset)
                    progressLabel.text = "Dataset init failed"
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
                        addCacheBlock(cacheDir, node.yamlOffset)
                    }
                }
                is ProbeEvent.Complete -> {
                    if (hasErrors) {
                        statusChip.setFailed(event.executionTimeMs)
                        headerLabel.text = ""
                        headerLabel.icon = UIUtil.getErrorIcon()
                    } else {
                        statusChip.setComplete(event.executionTimeMs)
                        headerLabel.text = ""
                        headerLabel.icon = null
                    }
                    rerunButton.isEnabled = lastRunConfig != null
                    stopButton.isEnabled = hasState
                    stopButton.isVisible = hasState
                    activeProbeIndicator = null
                    progressLabel.text = ""
                    if (progressStrip.parent === contentPanel) {
                        contentPanel.remove(progressStrip)
                    }
                    // The path overlay was already started at executeProbe();
                    // nothing to do here — it stays put through completion
                    // and the pulse continues on the leaf.
                }
                is ProbeEvent.Error -> {
                    hasErrors = true
                    addTopLevelErrorBlock(event.errorMessage, event.errorTraceback)
                }
            }

            // Keep the running-probe strip pinned at the bottom of the
            // content panel so the user sees where the next block lands.
            if (progressStrip.parent === contentPanel) {
                contentPanel.remove(progressStrip)
                contentPanel.add(progressStrip)
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
            projectPaths = freshConfig.projectPaths,
            branchChoices = freshConfig.branchChoices
        )

        executeProbe(script, configJson, freshConfig)
    }

    // ── Pick & Run (marker mode) ────────────────────────────────────────

    /** The active dataset-marker selection mode, if any. */
    private var markerMode: DatasetMarkerMode? = null

    /**
     * Activate the marker overlay in the focused YAML editor: every Dataset
     * `_target:` value gets a clickable pulsing-dot + dotted-underline marker.
     * Hovering one previews the path; clicking one runs the probe from that
     * dataset down.
     */
    fun enterMarkerMode() {
        // Save all documents so the Python probe reads the latest content from disk.
        WriteAction.runAndWait<Throwable> {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val data = com.intellij.openapi.application.ReadAction.compute<MarkerData?, Throwable> {
            val editor = focusedYamlEditor() ?: return@compute null
            val yamlFile = PsiManager.getInstance(project).findFile(editor.virtualFile ?: return@compute null)
                as? YAMLFile ?: return@compute null
            val all = collectAllDatasets(YamlPipelineParser.findDatasetNodes(yamlFile, project))
            if (all.isEmpty()) return@compute null
            val markers = all.mapNotNull { node ->
                val range = findTargetValueRange(yamlFile, node) ?: return@mapNotNull null
                DatasetMarkerMode.Marker(node, range)
            }
            if (markers.isEmpty()) null else MarkerData(editor, markers)
        }

        if (data == null) {
            showNotification("No dataset definitions found in the focused YAML file.")
            return
        }

        val sdk = ProbeExecutor.getPythonSdk(project)
        if (sdk == null || ProbeExecutor.getPythonPath(sdk) == null) {
            showNotification("No Python interpreter configured for this project.")
            return
        }

        markerMode?.deactivate()
        markerMode = DatasetMarkerMode(
            editor = data.editor,
            markers = data.markers,
            onHover = { node ->
                if (node != null) showPathForNode(node) else clearPathOverlay()
            },
            onPick = { node, choices ->
                markerMode = null
                executeProbeFromNode(node, data.editor.virtualFile?.path, choices)
            },
            onExit = {
                markerMode = null
                clearPathOverlay()
            },
            onBranchChange = { node ->
                // Re-render the overlay with the user's new branch choice.
                showPathForNode(node)
            },
        ).also { it.activate() }
    }

    private data class MarkerData(
        val editor: Editor,
        val markers: List<DatasetMarkerMode.Marker>,
    )

    private fun executeProbeFromNode(
        node: DatasetNode,
        yamlFilePath: String?,
        branchChoices: Map<String, Int> = emptyMap(),
    ) {
        val path = yamlFilePath ?: return
        val projectPaths = ProbeExecutor.getProjectSourcePaths(project)
        val config = ProbeRunConfig(
            yamlFilePath = path,
            datasetPath = node.path,
            pipeline = node,
            projectPaths = projectPaths,
            branchChoices = branchChoices,
        )
        val script = ProbeScriptGenerator.loadScript()
        val configJson = ProbeScriptGenerator.generateConfig(
            yamlFilePath = config.yamlFilePath,
            datasetPath = config.datasetPath,
            pipeline = config.pipeline,
            projectPaths = config.projectPaths,
            branchChoices = config.branchChoices,
        )
        executeProbe(script, configJson, config)
    }

    private fun focusedYamlEditor(): Editor? {
        val fem = FileEditorManager.getInstance(project)
        val selected = fem.selectedEditor as? TextEditor
        if (selected != null && selected.file?.extension?.lowercase() in setOf("yaml", "yml")) {
            return selected.editor
        }
        // Fall back: any open YAML editor.
        return fem.allEditors.asSequence()
            .filterIsInstance<TextEditor>()
            .firstOrNull { it.file?.extension?.lowercase() in setOf("yaml", "yml") }
            ?.editor
    }

    /**
     * Flatten a tree of top-level [DatasetNode]s into a single list containing
     * every dataset reachable through `wrappedDataset` and `branches`.
     */
    private fun collectAllDatasets(
        top: List<Pair<String, DatasetNode>>
    ): List<DatasetNode> {
        val out = mutableListOf<DatasetNode>()
        fun walk(n: DatasetNode) {
            out.add(n)
            n.wrappedDataset?.let { walk(it) }
            for (b in n.branches) walk(b)
        }
        for ((_, t) in top) walk(t)
        return out
    }

    /**
     * Locate the `_target:` value's text range inside the YAML mapping at
     * [node]'s offset. Returns null if the mapping isn't found or doesn't
     * have a `_target:` key (shouldn't happen for parsed dataset nodes).
     */
    private fun findTargetValueRange(yamlFile: YAMLFile, node: DatasetNode): TextRange? {
        val element = yamlFile.findElementAt(node.yamlOffset)
            ?: yamlFile.findElementAt(node.yamlOffset + 1)
            ?: return null
        val mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java)
            ?: return null
        val targetKv = mapping.keyValues.firstOrNull { it.keyText == "_target" } ?: return null
        return (targetKv.value as? org.jetbrains.yaml.psi.YAMLScalar)?.textRange
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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
        recordedActions += RenderAction.Dataset(name, target, node)
        val isSubtitleLayout = chrome.datasetBranchPickerPlacement == BranchPickerPlacement.SUBTITLE
        val block = PipelineBlock(BlockKind.DATASET).apply {
            goToYamlOffset = node?.yamlOffset
            if (isSubtitleLayout) {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(12, 14 + extraLeft, 12 + extraBottom, 14)
            } else {
                layout = BorderLayout()
                border = JBUI.Borders.empty(10, 14 + extraLeft, 10 + extraBottom, 14)
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(48) + extraBottom)
            }
        }

        val nameLabel = JBLabel(name).apply {
            foreground = chrome.datasetNameForeground()
            font = font.deriveFont(Font.BOLD, font.size + 2f)
            icon = ProbeIcons.Probe
            iconTextGap = JBUI.scale(8)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val subtitleOrInline: JComponent = if (node != null && node.branches.isNotEmpty()) {
            val compositeKey = "${node.path}.params.${node.branchesParamKey ?: "datasets"}"
            val currentIdx = (lastRunConfig?.branchChoices?.get(compositeKey) ?: 0)
                .coerceIn(0, node.branches.size - 1)
            val branchNames = node.branches.mapIndexed { i, b -> "[$i] ${b.displayName}" }
            val combo = ComboBox(branchNames.toTypedArray()).apply {
                selectedIndex = currentIdx
                toolTipText = target
                alignmentX = Component.LEFT_ALIGNMENT
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
            combo
        } else {
            JBLabel(target).apply {
                foreground = chrome.datasetTargetForeground()
                font = font.deriveFont(font.size - 1f)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        }

        val goToBtn = block.goToYamlOffset?.let { createGoToButton(it) }
        if (isSubtitleLayout) {
            val nameRow = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(nameLabel, BorderLayout.WEST)
                if (goToBtn != null) add(goToBtn, BorderLayout.EAST)
            }
            block.add(nameRow)
            block.add(Box.createVerticalStrut(JBUI.scale(4)))
            block.add(subtitleOrInline)
        } else {
            block.add(nameLabel, BorderLayout.WEST)
            if (goToBtn != null) {
                val eastWrap = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    add(subtitleOrInline)
                    add(goToBtn)
                }
                block.add(eastWrap, BorderLayout.EAST)
            } else {
                block.add(subtitleOrInline, BorderLayout.EAST)
            }
        }

        contentPanel.add(block)
    }

    private fun addCacheBlock(cacheDir: String, yamlOffset: Int? = null) {
        recordedActions += RenderAction.Cache(cacheDir, yamlOffset)
        val block = PipelineBlock(BlockKind.CACHE).apply {
            goToYamlOffset = yamlOffset
            layout = BorderLayout()
            border = JBUI.Borders.empty(8, 14 + extraLeft, 8 + extraBottom, 14)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40) + extraBottom)
        }

        val label = JBLabel("Cached \u2192 $cacheDir").apply {
            foreground = chrome.cacheLabelForeground()
            font = font.deriveFont(Font.BOLD)
            icon = AllIcons.Actions.Lightning
            iconTextGap = JBUI.scale(6)
        }
        block.add(label, BorderLayout.WEST)
        block.goToYamlOffset?.let { block.add(createGoToButton(it), BorderLayout.EAST) }

        contentPanel.add(block)
    }

    private fun addStepBlock(snapshot: EntrySnapshot, diffs: List<FieldDiff>, yamlOffset: Int? = null) {
        recordedActions += RenderAction.Step(snapshot, diffs, yamlOffset)
        val block = PipelineBlock(BlockKind.STEP).apply {
            stepIndex = snapshot.stepIndex
            goToYamlOffset = yamlOffset
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12 + extraLeft, 10 + extraBottom, 12)
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

        val collapseLabel = chrome.newChevron(
            initialExpanded = true,
            legacyRightPadding = 6,
        )

        val stepNameLabel = JBLabel(snapshot.stepLabel).apply {
            font = font.deriveFont(Font.BOLD, font.size + 1f)
        }

        val leftHeader = JPanel(
            FlowLayout(FlowLayout.LEFT, chrome.stepLeftHeaderHgap, 0)
        ).apply {
            isOpaque = false
            add(collapseLabel)
            add(stepNameLabel)
        }
        headerRow.add(leftHeader, BorderLayout.WEST)
        val rightSide = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(chrome.newSummaryBadges(diffs))
            block.goToYamlOffset?.let { add(createGoToButton(it)) }
        }
        headerRow.add(rightSide, BorderLayout.EAST)

        headerRow.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                fieldsPanel.isVisible = !fieldsPanel.isVisible
                chrome.setChevronExpanded(collapseLabel, fieldsPanel.isVisible)
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

    private fun addErrorStepBlock(
        snapshot: EntrySnapshot,
        errorMessage: String,
        errorTraceback: String?,
        yamlOffset: Int? = null,
    ) {
        recordedActions += RenderAction.ErrorStep(snapshot, errorMessage, errorTraceback, yamlOffset)
        val block = PipelineBlock(BlockKind.ERROR).apply {
            stepIndex = snapshot.stepIndex
            goToYamlOffset = yamlOffset
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12 + extraLeft, 10 + extraBottom, 12)
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
        val errorRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel("step ${snapshot.stepIndex}").apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size - 1f)
                border = JBUI.Borders.emptyRight(4)
            })
            block.goToYamlOffset?.let { add(createGoToButton(it)) }
        }
        headerRow.add(errorRight, BorderLayout.EAST)
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

    private fun addInitErrorBlock(errorMessage: String, errorTraceback: String?, yamlOffset: Int? = null) {
        recordedActions += RenderAction.InitError(errorMessage, errorTraceback, yamlOffset)
        val block = PipelineBlock(BlockKind.ERROR).apply {
            goToYamlOffset = yamlOffset
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12 + extraLeft, 10 + extraBottom, 12)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
        }
        val leftHeader = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel(UIUtil.getErrorIcon()).apply {
                border = JBUI.Borders.emptyRight(6)
            })
            add(JBLabel("Instantiation failed").apply {
                font = font.deriveFont(Font.BOLD, font.size + 1f)
                foreground = JBColor(Color(0xC62828), Color(0xEF5350))
            })
        }
        headerRow.add(leftHeader, BorderLayout.WEST)
        block.goToYamlOffset?.let { headerRow.add(createGoToButton(it), BorderLayout.EAST) }
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
        recordedActions += RenderAction.TopLevelError(errorMessage, errorTraceback)
        val block = PipelineBlock(BlockKind.ERROR).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12, 12 + extraLeft, 12 + extraBottom, 12)
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
        recordedActions += RenderAction.Skipped
        contentPanel.add(chrome.newSkippedNotice("Skipped \u2014 inner dataset pipeline failed"))
    }

    private fun addConnector(label: String?) {
        recordedActions += RenderAction.Connector(label)
        contentPanel.add(chrome.newConnector(label))
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

        chrome.installTracebackToggle(block, tracePanel)
        block.add(tracePanel)
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
     * Animate a path overlay in the source editor that starts at [startNode]
     * and traces downward through `wrappedDataset` and the currently-chosen
     * branch of every composite to the deepest reachable leaf, where a
     * continuous pulse settles. Replaces any active overlay. Scrolls the
     * leaf into view if it's off-screen.
     *
     * The overlay anchor is always the path FROM [startNode] DOWN — the
     * outer YAML structure above [startNode] is not connected. This matches
     * the probe's own behavior: a probe rooted at [startNode] only
     * instantiates that dataset and its descendants.
     *
     * No-op if the YAML editor isn't open.
     */
    private fun showPathForNode(
        startNode: DatasetNode,
        choicesOverride: Map<String, Int>? = null,
    ) {
        val yamlPath = lastRunConfig?.yamlFilePath
            ?: focusedYamlEditor()?.virtualFile?.path
            ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(yamlPath) ?: return
        val editor = FileEditorManager.getInstance(project).getEditors(vf)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor
            ?: return

        // Precedence: explicit override (popup hover preview) > marker-mode
        // live choices (wheel-driven) > last run's saved choices > empty.
        val choices = choicesOverride
            ?: markerMode?.branchChoices
            ?: lastRunConfig?.branchChoices
            ?: emptyMap()
        val (edges, nodes) = computePathTree(startNode, choices)
        if (nodes.isEmpty()) return

        // Switching editors discards the previous overlay; otherwise just reset.
        if (overlayEditor !== editor) {
            clearPathOverlay()
        } else {
            pathOverlay?.clear()
        }
        val overlay = pathOverlay ?: YamlPathOverlay(editor).also {
            pathOverlay = it
            overlayEditor = editor
        }
        overlay.show(edges, nodes)

        // Scroll the primary leaf into view, if there is one; otherwise any leaf.
        val primaryLeafOffset = nodes.firstOrNull { it.isLeaf && it.isPrimary }?.offset
            ?: nodes.firstOrNull { it.isLeaf }?.offset
            ?: nodes.last().offset
        editor.scrollingModel.scrollTo(
            editor.offsetToLogicalPosition(primaryLeafOffset),
            ScrollType.MAKE_VISIBLE,
        )
    }

    /**
     * Build the path tree rooted at [start]: every wrapped-child becomes a
     * single linear edge, every composite fans out to *all* its branches.
     * The chosen branch of each composite is marked primary; siblings are
     * alternative. Inheritance: once you're on an alternative branch, all
     * descendants of that branch are alternative too.
     */
    private fun computePathTree(
        start: DatasetNode,
        choices: Map<String, Int>,
    ): Pair<List<YamlPathOverlay.PathEdge>, List<YamlPathOverlay.PathNode>> {
        val edges = mutableListOf<YamlPathOverlay.PathEdge>()
        val nodes = mutableListOf<YamlPathOverlay.PathNode>()

        fun walk(node: DatasetNode, isPrimary: Boolean, depth: Int) {
            val wrapped = node.wrappedDataset
            val branches = node.branches
            val hasChildren = wrapped != null || branches.isNotEmpty()
            nodes.add(YamlPathOverlay.PathNode(
                offset = node.yamlOffset,
                isLeaf = !hasChildren,
                isPrimary = isPrimary,
                depth = depth,
            ))
            if (wrapped != null) {
                edges.add(YamlPathOverlay.PathEdge(
                    from = node.yamlOffset,
                    to = wrapped.yamlOffset,
                    isPrimary = isPrimary,
                    depth = depth + 1,
                ))
                walk(wrapped, isPrimary, depth + 1)
            } else if (branches.isNotEmpty()) {
                val key = "${node.path}.params.${node.branchesParamKey ?: "datasets"}"
                val chosenIdx = (choices[key] ?: 0).coerceIn(0, branches.size - 1)
                for ((i, branch) in branches.withIndex()) {
                    val branchPrimary = isPrimary && i == chosenIdx
                    edges.add(YamlPathOverlay.PathEdge(
                        from = node.yamlOffset,
                        to = branch.yamlOffset,
                        isPrimary = branchPrimary,
                        depth = depth + 1,
                    ))
                    walk(branch, branchPrimary, depth + 1)
                }
            }
        }

        walk(start, isPrimary = true, depth = 0)
        return edges to nodes
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
        actualPathStartNode = null

        markerMode?.deactivate()
        markerMode = null

        contentPanel.removeAll()
        recordedActions.clear()
        contentPanel.revalidate()
        contentPanel.repaint()
        contentCardLayout.show(contentCards, CARD_EMPTY)

        cleanupTensorDir()

        lastRunConfig = null
        rerunButton.isEnabled = false
        stopButton.isEnabled = false
        stopButton.isVisible = false
        statusChip.setIdle()
        headerLabel.text = ""
        headerLabel.icon = null
        progressLabel.text = ""
        // The strip is a child of contentPanel; contentPanel.removeAll()
        // above already detached it, but null-out its parent reference
        // defensively in case anyone holds onto it.
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
        // Tracks the last item the cursor previewed. Reset to -1 each time
        // the popup opens so the first real hover always fires.
        var lastPreviewedIdx = -1

        combo.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                val list = comboPopupList(combo) ?: return
                hookedList = list
                lastPreviewedIdx = -1
                val ml = object : java.awt.event.MouseMotionAdapter() {
                    override fun mouseMoved(e: java.awt.event.MouseEvent) {
                        val idx = list.locationToIndex(e.point)
                        if (idx < 0 || idx >= composite.branches.size) return
                        // Only re-render when the hovered item actually changes;
                        // tiny mouse jitter within the same item shouldn't
                        // restart the trace animation.
                        if (idx == lastPreviewedIdx) return
                        lastPreviewedIdx = idx
                        val cfg = lastRunConfig ?: return
                        // Trace the FULL path from the probe's root (so the
                        // outer wrappers stay visible) with this composite's
                        // chosen branch overridden to the hovered index.
                        // Other branches will render in the darker "alt"
                        // color via the normal multi-branch logic.
                        val compositeKey = "${composite.path}.params." +
                            (composite.branchesParamKey ?: "datasets")
                        val temp = cfg.branchChoices.toMutableMap()
                        temp[compositeKey] = idx
                        showPathForNode(cfg.pipeline, choicesOverride = temp)
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
                actualPathStartNode?.let { showPathForNode(it) }
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

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_CONTENT = "content"
        private const val ORIGINAL_TEXT_KEY = "srforge.probe.originalText"

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

/**
 * One unit of "rendered visual" emitted into the probe tool window. Recorded
 * as the probe streams so that on a chrome hot-swap we can rebuild the UI
 * verbatim under the new chrome — no probe re-run needed.
 *
 * Adding a new visual element to the probe just means adding a new entry
 * here and handling it in [ProbeToolWindowPanel.replay].
 */
sealed class RenderAction {
    data class Dataset(
        val name: String,
        val target: String,
        val node: DatasetNode?,
    ) : RenderAction()

    data class Cache(val cacheDir: String, val yamlOffset: Int?) : RenderAction()

    data class Step(
        val snapshot: EntrySnapshot,
        val diffs: List<FieldDiff>,
        val yamlOffset: Int?,
    ) : RenderAction()

    data class ErrorStep(
        val snapshot: EntrySnapshot,
        val errorMessage: String,
        val errorTraceback: String?,
        val yamlOffset: Int?,
    ) : RenderAction()

    data class InitError(
        val errorMessage: String,
        val errorTraceback: String?,
        val yamlOffset: Int?,
    ) : RenderAction()

    data class TopLevelError(
        val errorMessage: String,
        val errorTraceback: String?,
    ) : RenderAction()

    data class Connector(val label: String?) : RenderAction()

    object Skipped : RenderAction()
}
