package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.*

/**
 * Dialog for selecting which dataset to probe and overriding data paths.
 * All data root paths are editable; a checkbox controls whether changes
 * are written back to the YAML file.
 */
class DatasetSelectorDialog(
    private val project: Project,
    private val datasets: List<Pair<String, DatasetNode>>
) : DialogWrapper(project) {

    private val datasetCombo = ComboBox<String>()
    private val overridePanel = JPanel(GridBagLayout())
    private var overrideScrollPane: JScrollPane? = null
    private val overrideFields = mutableMapOf<String, TextFieldWithBrowseButton>()
    private val overwriteCheckbox = JCheckBox("Save path changes to YAML file")

    var selectedDatasetPath: String = ""
        private set
    var pathOverrides: Map<String, String> = emptyMap()
        private set
    var shouldOverwriteYaml: Boolean = false
        private set

    init {
        title = "Pipeline Probe - Select Dataset"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(500), JBUI.scale(300))
        }

        // Dataset selector
        val selectorPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }
        selectorPanel.add(JLabel("Dataset:"), BorderLayout.WEST)

        for ((path, node) in datasets) {
            datasetCombo.addItem("$path  (${node.displayName})")
        }
        datasetCombo.addActionListener { updateOverrides() }
        selectorPanel.add(datasetCombo, BorderLayout.CENTER)

        panel.add(selectorPanel, BorderLayout.NORTH)

        // Path overrides
        val overrideWrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(
                JBUI.Borders.empty(4),
                "Data Paths"
            )
        }
        overrideScrollPane = JScrollPane(overridePanel).apply {
            border = JBUI.Borders.empty()
        }
        overrideWrapper.add(overrideScrollPane, BorderLayout.CENTER)
        panel.add(overrideWrapper, BorderLayout.CENTER)

        // Overwrite checkbox at the bottom
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
        }
        bottomPanel.add(overwriteCheckbox, BorderLayout.WEST)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        // Initialize overrides for first dataset
        if (datasets.isNotEmpty()) {
            updateOverrides()
        }

        return panel
    }

    private fun updateOverrides() {
        val selectedIndex = datasetCombo.selectedIndex
        if (selectedIndex < 0 || selectedIndex >= datasets.size) return

        overridePanel.removeAll()
        overrideFields.clear()

        val (_, node) = datasets[selectedIndex]
        val roots = collectDataRoots(node)

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
            gridy = 0
        }

        for ((label, originalPath) in roots) {
            if (originalPath == null) continue

            val exists = File(originalPath).exists()

            gbc.gridx = 0; gbc.weightx = 0.0
            val pathLabel = JLabel("$label:").apply {
                if (!exists) foreground = java.awt.Color(0xCC6600)
            }
            overridePanel.add(pathLabel, gbc)

            gbc.gridx = 1; gbc.weightx = 1.0
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            @Suppress("DEPRECATION")
            val field = TextFieldWithBrowseButton().apply {
                text = originalPath
                addBrowseFolderListener("Select Data Directory", "Choose directory for: $label", project, descriptor)
            }
            overrideFields[originalPath] = field
            overridePanel.add(field, gbc)

            gbc.gridy++
        }

        if (roots.isEmpty() || roots.all { it.second == null }) {
            gbc.gridx = 0; gbc.weightx = 1.0; gbc.gridwidth = 2
            overridePanel.add(JLabel("No data paths to configure."), gbc)
        }

        overridePanel.revalidate()
        overridePanel.repaint()
        overrideScrollPane?.revalidate()
        overrideScrollPane?.repaint()
    }

    /**
     * Collect all data root paths from a dataset node and its wrapped datasets.
     */
    private fun collectDataRoots(node: DatasetNode): List<Pair<String, String?>> {
        val roots = mutableListOf<Pair<String, String?>>()
        collectDataRootsRecursive(node, roots)
        return roots
    }

    private fun collectDataRootsRecursive(node: DatasetNode, roots: MutableList<Pair<String, String?>>) {
        if (node.dataRoot != null) {
            roots.add(node.displayName to node.dataRoot)
        }
        if (node.wrappedDataset != null) {
            collectDataRootsRecursive(node.wrappedDataset, roots)
        }
    }

    override fun doOKAction() {
        val selectedIndex = datasetCombo.selectedIndex
        if (selectedIndex >= 0 && selectedIndex < datasets.size) {
            selectedDatasetPath = datasets[selectedIndex].first
            pathOverrides = overrideFields
                .filter { (original, field) -> field.text.isNotBlank() && field.text != original }
                .mapValues { (_, field) -> field.text }
            shouldOverwriteYaml = overwriteCheckbox.isSelected && pathOverrides.isNotEmpty()
        }
        super.doOKAction()
    }
}
