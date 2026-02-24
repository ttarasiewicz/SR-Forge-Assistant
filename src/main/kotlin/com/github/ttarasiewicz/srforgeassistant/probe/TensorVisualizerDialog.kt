package com.github.ttarasiewicz.srforgeassistant.probe

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Interactive tensor visualization dialog.
 *
 * Displays a tensor field as an image with per-dimension role assignment
 * (H, W, C, Index, Mean, Max, Min, Sum), display modes, colormaps,
 * histogram, zoom/pan, and pixel inspection.
 */
class TensorVisualizerDialog(
    private val project: Project,
    private val field: FieldSnapshot,
    private val cleanupNpyOnClose: Boolean = false
) : DialogWrapper(project, true) {

    // ── State ──────────────────────────────────────────────────
    private var currentImage: BufferedImage? = null
    private var currentHistogram: List<HistogramBin>? = null
    private var currentStats: VizStats? = null
    private var currentPixelFormat: String = "rgb"
    private var isLoading = false

    // Raw float pixel data for hover inspection (H×W×C float32)
    private var rawPixels: FloatArray? = null
    private var rawPixelH = 0
    private var rawPixelW = 0
    private var rawPixelC = 0

    data class HistogramBin(val binStart: Double, val binEnd: Double, val count: Int)
    data class VizStats(
        val min: Double, val max: Double, val mean: Double,
        val std: Double, val shape: List<Int>
    )

    // ── Dim analysis ───────────────────────────────────────────
    private val originalShape: List<Int> = parseShape(field.shape)
    private val dimCount = originalShape.size

    // ── Dim role rows ──────────────────────────────────────────
    private val dimRows = mutableListOf<DimRow>()
    private var updatingRoles = false

    private data class DimRow(
        val dimIndex: Int,
        val dimSize: Int,
        val roleCombo: ComboBox<String>,
        val slider: JSlider,
        val valueField: JTextField,
        val rowPanel: JPanel
    )

    // ── UI Components ──────────────────────────────────────────
    private val imagePanel = ImageViewPanel()
    private val histogramPanel = HistogramPanel()
    private val pixelInfoLabel = JBLabel(" ")
    private val statsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    // Controls
    private val displayModeCombo = ComboBox(arrayOf("Min-Max", "Histogram Eq", "CLAHE", "Custom Range"))
    private val colormapCombo = ComboBox(arrayOf("gray", "viridis", "jet", "inferno", "turbo"))
    private val channelModeCombo = ComboBox(arrayOf("RGB", "Custom RGB", "Single Channel"))
    private val customRSpinner = JSpinner(SpinnerNumberModel(0, 0, 0, 1))
    private val customGSpinner = JSpinner(SpinnerNumberModel(0, 0, 0, 1))
    private val customBSpinner = JSpinner(SpinnerNumberModel(0, 0, 0, 1))
    private val channelReduceCombo = ComboBox(arrayOf("Index", "Mean", "Max", "Min", "Sum"))
    private val channelSlider = JSlider(0, 0, 0).apply {
        preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
    }
    private val channelValueField = JTextField("0", 4).apply {
        horizontalAlignment = JTextField.CENTER
    }
    private val claheClipSpinner = JSpinner(SpinnerNumberModel(2.0, 0.1, 100.0, 0.5))
    private val claheTileSpinner = JSpinner(SpinnerNumberModel(8, 1, 128, 1))
    private val customMinField = JTextField("0.0", 8)
    private val customMaxField = JTextField("1.0", 8)
    private val binsSlider = JSlider(4, 1024, 256).apply {
        preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
    }
    private val binsSpinner = JSpinner(SpinnerNumberModel(256, 4, 4096, 1)).apply {
        preferredSize = Dimension(JBUI.scale(70), preferredSize.height)
    }

    // Channel controls panels (toggled when C role exists)
    private lateinit var channelRow: JPanel
    private lateinit var singleChannelRow: JPanel
    private lateinit var customRgbRow: JPanel
    private lateinit var colormapLabel: JBLabel

    init {
        title = "Tensor Visualizer \u2014 ${field.key}"
        setOKButtonText("Close")
        init()
        requestVisualization()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(920), JBUI.scale(660))
        }

        // ── Top info bar ────────────────────────────────────────
        val infoBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(4, 8)
        }
        infoBar.add(JBLabel("Field: ${field.key}").apply {
            font = font.deriveFont(Font.BOLD)
        })
        infoBar.add(JBLabel("Shape: ${field.shape ?: "?"}").apply {
            foreground = UIUtil.getInactiveTextColor()
        })
        infoBar.add(JBLabel("DType: ${field.dtype ?: "?"}").apply {
            foreground = UIUtil.getInactiveTextColor()
        })
        root.add(infoBar, BorderLayout.NORTH)

        // ── Center: image + side panel ──────────────────────────
        val sidePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(JBUI.scale(250), 0)
        }

        val monoFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())

        // Pixel info
        pixelInfoLabel.apply {
            font = monoFont
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }
        sidePanel.add(pixelInfoLabel)

        // Stats section
        sidePanel.add(JBLabel("Statistics").apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        sidePanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        statsPanel.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }
        sidePanel.add(statsPanel)

        // Histogram section
        sidePanel.add(Box.createVerticalStrut(JBUI.scale(12)))
        sidePanel.add(JBLabel("Histogram").apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        sidePanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        histogramPanel.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(230), JBUI.scale(140))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(140))
        }
        sidePanel.add(histogramPanel)

        // Bins control
        sidePanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        val binsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
        }
        binsRow.add(JBLabel("Bins:"))
        binsRow.add(binsSlider)
        binsRow.add(binsSpinner)
        sidePanel.add(binsRow)

        sidePanel.add(Box.createVerticalGlue())

        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            imagePanel,
            JScrollPane(sidePanel)
        ).apply {
            resizeWeight = 0.75
            dividerSize = JBUI.scale(4)
        }
        // ── Bottom controls ─────────────────────────────────────
        val controlsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        // Dimension role rows
        if (dimCount > 0) {
            val dimTitleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            dimTitleRow.add(JBLabel("Dimensions:").apply {
                font = font.deriveFont(Font.BOLD)
            })
            controlsPanel.add(dimTitleRow)

            for (i in 0 until dimCount) {
                val dimSize = originalShape[i]
                val row = createDimRow(i, dimSize)
                dimRows.add(row)
                controlsPanel.add(row.rowPanel)
            }
            controlsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        // Display controls row
        val displayRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        displayRow.add(JBLabel("Display:"))
        displayRow.add(displayModeCombo)
        colormapLabel = JBLabel("Colormap:")
        displayRow.add(colormapLabel)
        displayRow.add(colormapCombo)
        controlsPanel.add(displayRow)

        // Channel mode row (visible only when a C dim exists)
        channelRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        channelRow.add(JBLabel("Channel:"))
        channelRow.add(channelModeCombo)
        controlsPanel.add(channelRow)

        // Single Channel reduce row
        singleChannelRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }
        singleChannelRow.add(JBLabel("C reduce:"))
        singleChannelRow.add(channelReduceCombo)
        singleChannelRow.add(channelSlider)
        singleChannelRow.add(channelValueField)
        controlsPanel.add(singleChannelRow)

        // Custom RGB channel mapping row
        customRgbRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }
        val spinnerSize = Dimension(JBUI.scale(60), customRSpinner.preferredSize.height)
        customRgbRow.add(JBLabel("R:"))
        customRgbRow.add(customRSpinner.apply { preferredSize = spinnerSize })
        customRgbRow.add(JBLabel("G:"))
        customRgbRow.add(customGSpinner.apply { preferredSize = spinnerSize })
        customRgbRow.add(JBLabel("B:"))
        customRgbRow.add(customBSpinner.apply { preferredSize = spinnerSize })
        controlsPanel.add(customRgbRow)

        // CLAHE params row (visible only when CLAHE is selected)
        val claheRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }
        claheRow.add(JBLabel("CLAHE Clip Limit:"))
        claheRow.add(claheClipSpinner)
        claheRow.add(JBLabel("Tile Size:"))
        claheRow.add(claheTileSpinner)
        controlsPanel.add(claheRow)

        // Custom range row (visible only when custom_range is selected)
        val customRangeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isVisible = false
        }
        customRangeRow.add(JBLabel("Min:"))
        customRangeRow.add(customMinField)
        customRangeRow.add(JBLabel("Max:"))
        customRangeRow.add(customMaxField)
        controlsPanel.add(customRangeRow)

        // Wrap controls in a scroll pane with a draggable split against the image
        val controlsScroll = JScrollPane(controlsPanel).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
            minimumSize = Dimension(0, JBUI.scale(80))
        }

        val verticalSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            splitPane,
            controlsScroll
        ).apply {
            resizeWeight = 1.0
            dividerSize = JBUI.scale(5)
            // Dynamically position divider: give controls their preferred height,
            // clamped so the image always keeps a minimum area.
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    val total = height - dividerSize
                    val controlsPref = controlsPanel.preferredSize.height + JBUI.scale(8)
                    val minImage = JBUI.scale(200)
                    val controlsH = controlsPref.coerceAtMost(total - minImage).coerceAtLeast(JBUI.scale(80))
                    dividerLocation = total - controlsH
                }
            })
        }
        root.add(verticalSplit, BorderLayout.CENTER)

        // ── Set default roles ────────────────────────────────────
        setDefaultRoles()

        // ── Wire up control listeners ────────────────────────────
        val refreshAction = { requestVisualization() }
        displayModeCombo.addActionListener {
            claheRow.isVisible = displayModeCombo.selectedItem == "CLAHE"
            customRangeRow.isVisible = displayModeCombo.selectedItem == "Custom Range"
            refreshAction()
        }
        colormapCombo.addActionListener { refreshAction() }
        channelModeCombo.addActionListener {
            updateChannelSubControls()
            refreshAction()
        }
        channelReduceCombo.addActionListener {
            val isIndex = channelReduceCombo.selectedItem == "Index"
            channelSlider.isEnabled = isIndex
            channelValueField.isEnabled = isIndex
            refreshAction()
        }
        var channelSyncing = false
        channelSlider.addChangeListener {
            if (!channelSyncing) {
                channelSyncing = true
                channelValueField.text = channelSlider.value.toString()
                channelSyncing = false
                if (!channelSlider.valueIsAdjusting) refreshAction()
            }
        }
        channelValueField.addActionListener {
            if (!channelSyncing) {
                channelSyncing = true
                val v = (channelValueField.text.toIntOrNull() ?: 0).coerceIn(0, channelSlider.maximum)
                channelSlider.value = v
                channelValueField.text = v.toString()
                channelSyncing = false
                refreshAction()
            }
        }
        customRSpinner.addChangeListener { refreshAction() }
        customGSpinner.addChangeListener { refreshAction() }
        customBSpinner.addChangeListener { refreshAction() }
        claheClipSpinner.addChangeListener { refreshAction() }
        claheTileSpinner.addChangeListener { refreshAction() }
        customMinField.addActionListener { refreshAction() }
        customMaxField.addActionListener { refreshAction() }

        // Bins slider ↔ spinner sync
        var binsUpdating = false
        binsSlider.addChangeListener {
            if (!binsUpdating) {
                binsUpdating = true
                binsSpinner.value = binsSlider.value
                binsUpdating = false
                if (!binsSlider.valueIsAdjusting) refreshAction()
            }
        }
        binsSpinner.addChangeListener {
            if (!binsUpdating) {
                binsUpdating = true
                val v = (binsSpinner.value as Number).toInt().coerceIn(4, 4096)
                binsSlider.value = v.coerceIn(binsSlider.minimum, binsSlider.maximum)
                binsUpdating = false
                refreshAction()
            }
        }

        return root
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createLeftSideActions(): Array<Action> {
        val saveAction = object : AbstractAction("Save PNG") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                savePng()
            }
        }
        return arrayOf(saveAction)
    }

    override fun dispose() {
        super.dispose()
        if (cleanupNpyOnClose && field.npyPath != null) {
            try {
                File(field.npyPath).delete()
            } catch (_: Exception) { }
        }
    }

    // ── Default Role Assignment ────────────────────────────────

    private fun setDefaultRoles() {
        if (dimRows.isEmpty()) return
        updatingRoles = true
        for (row in dimRows) {
            val i = row.dimIndex
            val role = when {
                i == dimCount - 1 -> "W"
                i == dimCount - 2 -> "H"
                i == dimCount - 3 && dimCount > 2 && originalShape[i] <= 64 -> "C"
                else -> "Index"
            }
            row.roleCombo.selectedItem = role
            updateDimRowSliderVisibility(row)
        }
        updatingRoles = false
        updateChannelControlsVisibility()
    }

    // ── Dim Row Factory ────────────────────────────────────────

    private fun createDimRow(dimIndex: Int, dimSize: Int): DimRow {
        val roleCombo = ComboBox(arrayOf("H", "W", "C", "Index", "Mean", "Max", "Min", "Sum"))
        val maxIdx = maxOf(dimSize - 1, 0)
        val slider = JSlider(0, maxIdx, 0).apply {
            preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
        }
        val valueField = JTextField("0", 4).apply {
            horizontalAlignment = JTextField.CENTER
        }

        val rowPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        rowPanel.add(JBLabel("Dim $dimIndex [$dimSize]:"))
        rowPanel.add(roleCombo)
        rowPanel.add(slider)
        rowPanel.add(valueField)

        val dimRow = DimRow(dimIndex, dimSize, roleCombo, slider, valueField, rowPanel)

        // Role change listener
        roleCombo.addActionListener {
            if (!updatingRoles) {
                onRoleChanged(dimRow)
            }
        }

        // Slider ↔ value field sync
        var syncing = false
        slider.addChangeListener {
            if (!syncing) {
                syncing = true
                valueField.text = slider.value.toString()
                syncing = false
                if (!slider.valueIsAdjusting) requestVisualization()
            }
        }
        valueField.addActionListener {
            if (!syncing) {
                syncing = true
                val v = (valueField.text.toIntOrNull() ?: 0).coerceIn(0, maxIdx)
                slider.value = v
                valueField.text = v.toString()
                syncing = false
                requestVisualization()
            }
        }

        return dimRow
    }

    private fun onRoleChanged(changedRow: DimRow) {
        updatingRoles = true
        val newRole = changedRow.roleCombo.selectedItem as String

        // Enforce uniqueness for H, W, C: if another dim already has this role, set it to Index
        if (newRole == "H" || newRole == "W" || newRole == "C") {
            for (row in dimRows) {
                if (row !== changedRow && (row.roleCombo.selectedItem as String) == newRole) {
                    row.roleCombo.selectedItem = "Index"
                    updateDimRowSliderVisibility(row)
                }
            }
        }

        updateDimRowSliderVisibility(changedRow)
        updatingRoles = false
        updateChannelControlsVisibility()
        requestVisualization()
    }

    private fun updateDimRowSliderVisibility(row: DimRow) {
        val role = row.roleCombo.selectedItem as String
        val showSlider = (role == "Index")
        row.slider.isVisible = showSlider
        row.valueField.isVisible = showSlider
    }

    // ── Channel Controls Visibility ────────────────────────────

    private fun hasCDim(): Boolean {
        return dimRows.any { (it.roleCombo.selectedItem as String) == "C" }
    }

    private fun getChannelDimSize(): Int {
        val cRow = dimRows.find { (it.roleCombo.selectedItem as String) == "C" }
        return cRow?.dimSize ?: 1
    }

    private fun updateChannelControlsVisibility() {
        val hasC = hasCDim()
        channelRow.isVisible = hasC
        if (!hasC) {
            singleChannelRow.isVisible = false
            customRgbRow.isVisible = false
        } else {
            updateChannelSubControls()
        }

        // Colormap: visible when output is single channel
        val isSingleCh = !hasC || (channelModeCombo.selectedItem as String) == "Single Channel"
        colormapLabel.isVisible = isSingleCh
        colormapCombo.isVisible = isSingleCh

        // Update channel slider/spinner maxes from C dim size
        if (hasC) {
            val maxIdx = maxOf(getChannelDimSize() - 1, 0)
            channelSlider.maximum = maxIdx
            (customRSpinner.model as SpinnerNumberModel).maximum = maxIdx as Comparable<Int>
            (customGSpinner.model as SpinnerNumberModel).maximum = maxIdx as Comparable<Int>
            (customBSpinner.model as SpinnerNumberModel).maximum = maxIdx as Comparable<Int>
            // Set reasonable defaults for G/B spinners if they're at 0
            if ((customGSpinner.value as Number).toInt() == 0 && maxIdx >= 1) {
                customGSpinner.value = minOf(1, maxIdx)
            }
            if ((customBSpinner.value as Number).toInt() == 0 && maxIdx >= 2) {
                customBSpinner.value = minOf(2, maxIdx)
            }
        }
    }

    private fun updateChannelSubControls() {
        val mode = channelModeCombo.selectedItem as String
        singleChannelRow.isVisible = mode == "Single Channel" && hasCDim()
        customRgbRow.isVisible = mode == "Custom RGB" && hasCDim()
        val isSingleCh = !hasCDim() || mode == "Single Channel"
        colormapLabel.isVisible = isSingleCh
        colormapCombo.isVisible = isSingleCh
    }

    // ── Visualization Request ──────────────────────────────────

    private fun requestVisualization() {
        if (isLoading) return
        isLoading = true
        pixelInfoLabel.text = "Rendering..."

        val config = buildVizConfig()

        Thread {
            val result = runVizScript(config)
            SwingUtilities.invokeLater {
                isLoading = false
                applyVizResult(result)
            }
        }.start()
    }

    private fun buildVizConfig(): Map<String, Any?> {
        val dimRolesList = dimRows.map { row ->
            val role = when (row.roleCombo.selectedItem as String) {
                "H" -> "h"
                "W" -> "w"
                "C" -> "c"
                "Index" -> "index"
                "Mean" -> "mean"
                "Max" -> "max"
                "Min" -> "min"
                "Sum" -> "sum"
                else -> "index"
            }
            mutableMapOf<String, Any>(
                "dimIndex" to row.dimIndex,
                "role" to role
            ).also {
                if (role == "index") {
                    it["value"] = row.slider.value
                }
            }
        }

        val channelMode = if (!hasCDim()) {
            "single_channel" // No C dim → H×W grayscale
        } else {
            when (channelModeCombo.selectedItem as String) {
                "RGB" -> "rgb"
                "Custom RGB" -> "custom_rgb"
                "Single Channel" -> "single_channel"
                else -> "rgb"
            }
        }

        val channelReduceMode = when (channelReduceCombo.selectedItem as String) {
            "Index" -> "index"
            "Mean" -> "mean"
            "Max" -> "max"
            "Min" -> "min"
            "Sum" -> "sum"
            else -> "index"
        }

        val displayMode = when (displayModeCombo.selectedItem as String) {
            "Min-Max" -> "normalized"
            "Histogram Eq" -> "histogram_eq"
            "CLAHE" -> "clahe"
            "Custom Range" -> "custom_range"
            else -> "normalized"
        }

        return mapOf(
            "npyPath" to field.npyPath,
            "dimRoles" to dimRolesList,
            "channelMode" to channelMode,
            "channelAction" to mapOf(
                "mode" to channelReduceMode,
                "value" to channelSlider.value
            ),
            "customRgbChannels" to listOf(
                (customRSpinner.value as Number).toInt(),
                (customGSpinner.value as Number).toInt(),
                (customBSpinner.value as Number).toInt()
            ),
            "displayMode" to displayMode,
            "claheClipLimit" to (claheClipSpinner.value as Number).toDouble(),
            "claheTileSize" to (claheTileSpinner.value as Number).toInt(),
            "customMin" to (customMinField.text.toDoubleOrNull() ?: 0.0),
            "customMax" to (customMaxField.text.toDoubleOrNull() ?: 1.0),
            "colormap" to (colormapCombo.selectedItem as String),
            "outputWidth" to 1024,
            "numBins" to (binsSpinner.value as Number).toInt()
        )
    }

    // ── Viz Script Execution ───────────────────────────────────

    private fun runVizScript(config: Map<String, Any?>): JsonObject {
        val sdk = ProbeExecutor.getPythonSdk(project)
            ?: return errorResult("No Python SDK configured")
        val pythonPath = ProbeExecutor.getPythonPath(sdk)
            ?: return errorResult("Cannot determine Python interpreter path")

        var scriptFile: java.io.File? = null
        var configFile: java.io.File? = null
        try {
            scriptFile = Files.createTempFile("srforge_viz_", ".py").toFile()
            configFile = Files.createTempFile("srforge_viz_cfg_", ".json").toFile()

            scriptFile.writeText(ProbeScriptGenerator.loadVizScript(), StandardCharsets.UTF_8)
            configFile.writeText(Gson().toJson(config), StandardCharsets.UTF_8)

            val pb = ProcessBuilder(pythonPath, scriptFile.absolutePath, configFile.absolutePath)
            pb.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val process = pb.start()

            // Read stderr in a separate thread to prevent pipe deadlock
            val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
            }

            val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val completed = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return errorResult("Viz script timed out after 30s")
            }

            val stderr = try {
                stderrFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) { "" }

            val trimmed = stdout.trim()
            if (trimmed.isEmpty()) {
                val exitCode = process.exitValue()
                return errorResult("No output from viz script (exit $exitCode)\n${stderr.take(500)}")
            }

            return try {
                Gson().fromJson(trimmed, JsonObject::class.java)
            } catch (e: Exception) {
                errorResult("Invalid JSON: ${e.message}\nstdout: ${trimmed.take(300)}\nstderr: ${stderr.take(300)}")
            }
        } catch (e: Exception) {
            return errorResult("Failed to run viz script: ${e.message}")
        } finally {
            scriptFile?.delete()
            configFile?.delete()
        }
    }

    private fun errorResult(message: String): JsonObject {
        return JsonObject().apply {
            addProperty("error", message)
        }
    }

    // ── Apply Viz Result ───────────────────────────────────────

    private fun applyVizResult(result: JsonObject) {
        val error = result.get("error")?.takeIf { !it.isJsonNull }?.asString
        if (error != null) {
            pixelInfoLabel.text = "<html><b>Error:</b> ${error.replace("\n", "<br>")}</html>"
            imagePanel.setImage(null)
            return
        }

        // Decode image
        val imageB64 = result.get("image")?.takeIf { !it.isJsonNull }?.asString
        if (imageB64 != null) {
            val imageBytes = java.util.Base64.getDecoder().decode(imageB64)
            currentImage = ImageIO.read(ByteArrayInputStream(imageBytes))
            imagePanel.setImage(currentImage)
        }

        // Parse histogram
        val histArray = result.getAsJsonArray("histogram")
        if (histArray != null) {
            currentHistogram = histArray.map { bin ->
                val obj = bin.asJsonObject
                HistogramBin(
                    obj.get("binStart").asDouble,
                    obj.get("binEnd").asDouble,
                    obj.get("count").asInt
                )
            }
            histogramPanel.setData(currentHistogram!!)
        }

        // Parse stats
        val statsObj = result.getAsJsonObject("stats")
        if (statsObj != null) {
            currentStats = VizStats(
                min = statsObj.get("min").asDouble,
                max = statsObj.get("max").asDouble,
                mean = statsObj.get("mean").asDouble,
                std = statsObj.get("std").asDouble,
                shape = statsObj.getAsJsonArray("shape").map { it.asInt }
            )
            updateStatsPanel(currentStats!!)
        }

        currentPixelFormat = result.get("pixelFormat")?.takeIf { !it.isJsonNull }?.asString ?: "rgb"

        // Clamp channel controls to actual channel count from Python
        val totalChannels = result.get("totalChannels")?.takeIf { !it.isJsonNull }?.asInt
        if (totalChannels != null && totalChannels > 0) {
            val maxIdx = totalChannels - 1
            channelSlider.maximum = maxIdx
            (customRSpinner.model as SpinnerNumberModel).maximum = maxIdx as Comparable<Int>
            (customGSpinner.model as SpinnerNumberModel).maximum = maxIdx as Comparable<Int>
            (customBSpinner.model as SpinnerNumberModel).maximum = maxIdx as Comparable<Int>
        }

        // Parse raw float pixel data for hover
        val rawB64 = result.get("rawPixels")?.takeIf { !it.isJsonNull }?.asString
        val rawShape = result.getAsJsonArray("rawPixelShape")
        if (rawB64 != null && rawShape != null && rawShape.size() == 3) {
            rawPixelH = rawShape[0].asInt
            rawPixelW = rawShape[1].asInt
            rawPixelC = rawShape[2].asInt
            val bytes = java.util.Base64.getDecoder().decode(rawB64)
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            buf.asFloatBuffer().get(floats)
            rawPixels = floats
        } else {
            rawPixels = null
        }

        pixelInfoLabel.text = " "
    }

    private fun updateStatsPanel(stats: VizStats) {
        statsPanel.removeAll()
        val monoFont = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.SMALL).toInt())
        val color = UIUtil.getLabelForeground()

        fun addRow(label: String, value: String) {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(18))
            }
            row.add(JBLabel(label).apply { font = monoFont.deriveFont(Font.BOLD); foreground = color })
            row.add(JBLabel(value).apply { font = monoFont; foreground = color })
            statsPanel.add(row)
        }

        addRow("Shape", stats.shape.toString())
        addRow("Min  ", "%.6g".format(stats.min))
        addRow("Max  ", "%.6g".format(stats.max))
        addRow("Mean ", "%.6g".format(stats.mean))
        addRow("Std  ", "%.6g".format(stats.std))
        statsPanel.revalidate()
        statsPanel.repaint()
    }

    // ── Save PNG ───────────────────────────────────────────────

    private fun savePng() {
        val image = currentImage ?: return
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Save Tensor Visualization"
            selectedFile = File("${field.key}_visualization.png")
            fileFilter = FileNameExtensionFilter("PNG Images", "png")
        }
        if (fileChooser.showSaveDialog(contentPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                ImageIO.write(image, "PNG", fileChooser.selectedFile)
            } catch (_: Exception) { }
        }
    }

    // ── Image View Panel (zoom/pan/hover) ──────────────────────

    private inner class ImageViewPanel : JPanel() {
        private var image: BufferedImage? = null
        private var zoom = 1.0
        private var panX = 0.0
        private var panY = 0.0
        private var dragStart: Point? = null
        private var dragPanStart: Pair<Double, Double>? = null
        private var needsFit = false

        init {
            background = JBColor(Color(0xF0F0F0), Color(0x2B2B2B))

            // Re-fit image when panel is first shown or resized
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    if (needsFit) {
                        needsFit = false
                        fitImage()
                    }
                }
            })

            addMouseWheelListener { e: MouseWheelEvent ->
                val oldZoom = zoom
                zoom *= if (e.wheelRotation < 0) 1.15 else (1.0 / 1.15)
                zoom = zoom.coerceIn(0.05, 50.0)

                // Zoom toward cursor position
                val mx = e.x.toDouble()
                val my = e.y.toDouble()
                panX = mx - (mx - panX) * (zoom / oldZoom)
                panY = my - (my - panY) * (zoom / oldZoom)

                repaint()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragStart = e.point
                    dragPanStart = panX to panY
                }

                override fun mouseReleased(e: MouseEvent) {
                    dragStart = null
                }
            })

            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val start = dragStart ?: return
                    val (px, py) = dragPanStart ?: return
                    panX = px + (e.x - start.x)
                    panY = py + (e.y - start.y)
                    repaint()
                }

                override fun mouseMoved(e: MouseEvent) {
                    updatePixelInfo(e.point)
                }
            })
        }

        fun setImage(img: BufferedImage?) {
            image = img
            if (img != null) {
                if (width > 0 && height > 0) {
                    fitImage()
                } else {
                    needsFit = true
                }
            }
            repaint()
        }

        private fun fitImage() {
            val img = image ?: return
            if (width <= 0 || height <= 0) return
            val scaleX = width.toDouble() / img.width
            val scaleY = height.toDouble() / img.height
            zoom = minOf(scaleX, scaleY).coerceAtLeast(0.05)
            panX = (width - img.width * zoom) / 2
            panY = (height - img.height * zoom) / 2
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image ?: return
            val g2 = g as Graphics2D
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (zoom < 1.0) RenderingHints.VALUE_INTERPOLATION_BILINEAR
                else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            )

            val at = AffineTransform()
            at.translate(panX, panY)
            at.scale(zoom, zoom)
            g2.drawImage(img, at, null)
        }

        private fun updatePixelInfo(screenPoint: Point) {
            val img = image ?: return
            val imgX = ((screenPoint.x - panX) / zoom).toInt()
            val imgY = ((screenPoint.y - panY) / zoom).toInt()

            if (imgX < 0 || imgX >= img.width || imgY < 0 || imgY >= img.height) {
                pixelInfoLabel.text = " "
                return
            }

            // Map rendered image coords to raw data coords (may differ due to resize)
            val rp = rawPixels
            if (rp != null && rawPixelH > 0 && rawPixelW > 0) {
                val rawX = (imgX.toDouble() / img.width * rawPixelW).toInt().coerceIn(0, rawPixelW - 1)
                val rawY = (imgY.toDouble() / img.height * rawPixelH).toInt().coerceIn(0, rawPixelH - 1)
                val baseIdx = (rawY * rawPixelW + rawX) * rawPixelC

                pixelInfoLabel.text = if (rawPixelC == 1) {
                    val v = rp[baseIdx]
                    "Pixel ($rawX, $rawY)  val=${"%.6g".format(v)}"
                } else {
                    val vals = (0 until rawPixelC.coerceAtMost(4)).joinToString("  ") { c ->
                        "C$c=${"%.4g".format(rp[baseIdx + c])}"
                    }
                    "Pixel ($rawX, $rawY)  $vals"
                }
            } else {
                // Fallback to rendered RGB
                val rgb = img.getRGB(imgX, imgY)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                pixelInfoLabel.text = "Pixel ($imgX, $imgY)  R=$r G=$g B=$b"
            }
        }
    }

    // ── Histogram Panel ────────────────────────────────────────

    private inner class HistogramPanel : JPanel() {
        private var bins: List<HistogramBin> = emptyList()
        private var maxCount = 1

        init {
            background = JBColor(Color.WHITE, Color(0x2B2B2B))
            border = BorderFactory.createLineBorder(JBColor.border())
        }

        fun setData(data: List<HistogramBin>) {
            bins = data
            maxCount = data.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (bins.isEmpty()) return

            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val axisFont = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(9))
            val fm = g2.getFontMetrics(axisFont)
            val axisHeight = fm.height + JBUI.scale(4)

            val insetL = JBUI.scale(4)
            val insetR = JBUI.scale(4)
            val insetT = JBUI.scale(4)
            val chartW = width - insetL - insetR
            val chartH = height - insetT - axisHeight
            if (chartW <= 0 || chartH <= 0) return

            val barWidth = chartW.toDouble() / bins.size

            // Draw bars
            g2.color = JBColor(Color(0x42A5F5), Color(0x1E88E5))
            for ((i, bin) in bins.withIndex()) {
                val barHeight = if (maxCount > 0) {
                    (bin.count.toDouble() / maxCount * chartH).toInt()
                } else 0
                val x = insetL + (i * barWidth).toInt()
                val y = insetT + chartH - barHeight
                g2.fillRect(x, y, maxOf(barWidth.toInt(), 1), barHeight)
            }

            // Draw X axis labels
            val minVal = bins.first().binStart
            val maxVal = bins.last().binEnd
            g2.font = axisFont
            g2.color = UIUtil.getLabelForeground()

            val labelY = insetT + chartH + fm.ascent + JBUI.scale(2)
            val minText = formatAxisValue(minVal)
            val maxText = formatAxisValue(maxVal)
            val midVal = (minVal + maxVal) / 2.0
            val midText = formatAxisValue(midVal)

            // Left label
            g2.drawString(minText, insetL, labelY)
            // Right label
            val maxTextW = fm.stringWidth(maxText)
            g2.drawString(maxText, insetL + chartW - maxTextW, labelY)
            // Mid label (only if room)
            val midTextW = fm.stringWidth(midText)
            val midX = insetL + chartW / 2 - midTextW / 2
            val minTextW = fm.stringWidth(minText)
            if (midX > insetL + minTextW + JBUI.scale(8) &&
                midX + midTextW < insetL + chartW - maxTextW - JBUI.scale(8)
            ) {
                g2.drawString(midText, midX, labelY)
            }
        }

        private fun formatAxisValue(v: Double): String {
            val abs = kotlin.math.abs(v)
            return when {
                abs == 0.0 -> "0"
                abs >= 1000 || abs < 0.01 -> "%.2e".format(v)
                abs < 1.0 -> "%.4f".format(v)
                else -> "%.2f".format(v)
            }
        }
    }

    // ── Shape Parsing Helper ─────────────────────────────────

    companion object {
        fun parseShape(shapeStr: String?): List<Int> {
            if (shapeStr == null) return emptyList()
            val cleaned = shapeStr.trim()
                .removePrefix("[").removeSuffix("]")
                .removePrefix("(").removeSuffix(")")
            return cleaned.split(",", "x").mapNotNull { it.trim().toIntOrNull() }
        }
    }
}
