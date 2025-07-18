package com.github.ttarasiewicz.srforgeassistant.settings

import com.github.ttarasiewicz.srforgeassistant.settings.MyPluginSettings
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class MyPluginSettingsConfigurable(private val project: Project) : SearchableConfigurable {
    private var settingsPanel: MyPluginSettingsPanel? = null

    override fun getId(): String = "com.github.ttarasiewicz.srforgeassistant.settings"

    override fun getDisplayName(): String = "SR-Forge Assistant Settings"

    override fun createComponent(): JComponent? {
        settingsPanel = MyPluginSettingsPanel()
        return settingsPanel!!.panel
    }

    override fun isModified(): Boolean {
        val state = MyPluginSettings.getInstance(project).state
        val rootDir = settingsPanel!!.rootDirField.text.trim()
        val panelText = settingsPanel!!.excludeParamsField.text.trim()
        // Convert the stored map into a canonical string representation.
        val storedText = state.excludeParamsForClasses
            .map { "${it.key}:${it.value.joinToString(",")}" }
            .sorted()
            .joinToString(";")
        // Normalize the panel text by splitting and sorting its entries.
        val panelCanonical = panelText.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(";")
        return state.projectRootDir != rootDir || storedText != panelCanonical
    }

    override fun apply() {
        val state = MyPluginSettings.getInstance(project).state
        state.projectRootDir = settingsPanel!!.rootDirField.text.trim()
        // Parse the exclude params field: entries separated by semicolon,
        // each entry in the format "FullyQualifiedClass:param1,param2"
        val entries = settingsPanel!!.excludeParamsField.text.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val newMap = mutableMapOf<String, MutableList<String>>()
        for (entry in entries) {
            val parts = entry.split(":")
            if (parts.size == 2) {
                val className = parts[0].trim()
                val params = parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                newMap[className] = params
            }
        }
        state.excludeParamsForClasses = newMap
    }

    override fun reset() {
        val state = MyPluginSettings.getInstance(project).state
        settingsPanel?.rootDirField?.text = state.projectRootDir
        val text = state.excludeParamsForClasses
            .map { "${it.key}:${it.value.joinToString(",")}" }
            .sorted()
            .joinToString("; ")
        settingsPanel?.excludeParamsField?.text = text
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}