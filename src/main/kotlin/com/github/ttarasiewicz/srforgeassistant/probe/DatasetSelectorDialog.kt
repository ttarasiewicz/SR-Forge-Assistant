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
 * Dialog for selecting which dataset to probe and overriding data paths
 * that don't exist on the local machine.
 */
class DatasetSelectorDialog(
    private val project: Project,
    private val datasets: List<Pair<String, DatasetNode>>
) : DialogWrapper(project) {

    private val datasetCombo = ComboBox<String>()
    private val overridePanel = JPanel(GridBagLayout())
    private val overrideFields = mutableMapOf<String, TextFieldWithBrowseButton>()

    var selectedDatasetPath: String = ""
        private set
    var pathOverrides: Map<String, String> = emptyMap()
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
                "Data Path Overrides"
            )
        }
        val scrollPane = JScrollPane(overridePanel).apply {
            border = JBUI.Borders.empty()
        }
        overrideWrapper.add(scrollPane, BorderLayout.CENTER)
        panel.add(overrideWrapper, BorderLayout.CENTER)

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
            val needsOverride = originalPath != null && !File(originalPath).exists()

            gbc.gridx = 0; gbc.weightx = 0.0
            overridePanel.add(JLabel("$label:"), gbc)

            gbc.gridx = 1; gbc.weightx = 1.0
            if (needsOverride && originalPath != null) {
                val field = TextFieldWithBrowseButton().apply {
                    text = originalPath
                    @Suppress("DEPRECATION")
                    addBrowseFolderListener(
                        "Select Data Directory",
                        "Choose directory for: $label (original: $originalPath)",
                        project,
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    )
                }
                overrideFields[originalPath] = field
                overridePanel.add(field, gbc)
            } else {
                val statusText = if (originalPath != null) "$originalPath  (exists)" else "(no root path)"
                overridePanel.add(JLabel(statusText), gbc)
            }

            gbc.gridy++
        }

        if (roots.isEmpty()) {
            gbc.gridx = 0; gbc.weightx = 1.0; gbc.gridwidth = 2
            overridePanel.add(JLabel("No data paths to configure."), gbc)
        }

        overridePanel.revalidate()
        overridePanel.repaint()
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
                .filter { (_, field) -> field.text.isNotBlank() }
                .mapValues { (_, field) -> field.text }
        }
        super.doOKAction()
    }
}
