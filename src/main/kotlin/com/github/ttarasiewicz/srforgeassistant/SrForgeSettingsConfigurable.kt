package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ColorPanel
import com.intellij.util.ui.FormBuilder
import java.awt.Color
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class SrForgeSettingsConfigurable : Configurable {

    private var panel: JPanel? = null

    // Scope highlighting
    private lateinit var blockEnabledCheckbox: JCheckBox
    private lateinit var parentEnabledCheckbox: JCheckBox
    private lateinit var parentFontStyleCombo: JComboBox<String>
    private lateinit var blockLightPicker: ColorPanel
    private lateinit var blockDarkPicker: ColorPanel
    private lateinit var parentLightPicker: ColorPanel
    private lateinit var parentDarkPicker: ColorPanel

    // Interpolation folding
    private lateinit var foldOnFileOpenCheckbox: JCheckBox
    private lateinit var autoCollapseCheckbox: JCheckBox
    private lateinit var foldMaxLengthSpinner: JSpinner

    // Pipeline Probe
    private lateinit var probeTimeoutSpinner: JSpinner

    override fun getDisplayName(): String = "SR-Forge Assistant"

    override fun createComponent(): JComponent {
        val s = SrForgeHighlightSettings.getInstance().state

        // Scope highlighting
        blockEnabledCheckbox = JCheckBox("Enable block highlight", s.blockEnabled)
        parentEnabledCheckbox = JCheckBox("Enable parent key highlight", s.parentKeyEnabled)
        parentFontStyleCombo = JComboBox(FONT_STYLE_NAMES).apply {
            selectedIndex = fontStyleToIndex(s.parentKeyFontStyle)
        }
        blockLightPicker = ColorPanel().apply { selectedColor = Color(s.blockLightColor) }
        blockDarkPicker = ColorPanel().apply { selectedColor = Color(s.blockDarkColor) }
        parentLightPicker = ColorPanel().apply { selectedColor = Color(s.parentLightColor) }
        parentDarkPicker = ColorPanel().apply { selectedColor = Color(s.parentDarkColor) }

        // Interpolation folding
        foldOnFileOpenCheckbox = JCheckBox("Collapse interpolation folds on file open", s.foldOnFileOpen)
        autoCollapseCheckbox = JCheckBox("Auto-collapse when caret leaves interpolation", s.autoCollapseOnCaretExit)
        foldMaxLengthSpinner = JSpinner(SpinnerNumberModel(s.foldPlaceholderMaxLength, 10, 500, 10))

        // Pipeline Probe
        probeTimeoutSpinner = JSpinner(SpinnerNumberModel(s.probeTimeoutSeconds, 10, 600, 10))

        panel = FormBuilder.createFormBuilder()
            // ── Scope Highlighting ──
            .addSeparator()
            .addComponent(blockEnabledCheckbox)
            .addLabeledComponent("Block highlight (light theme):", blockLightPicker)
            .addLabeledComponent("Block highlight (dark theme):", blockDarkPicker)
            .addSeparator()
            .addComponent(parentEnabledCheckbox)
            .addLabeledComponent("Parent key highlight (light theme):", parentLightPicker)
            .addLabeledComponent("Parent key highlight (dark theme):", parentDarkPicker)
            .addLabeledComponent("Parent key font style:", parentFontStyleCombo)
            // ── Interpolation Folding ──
            .addSeparator()
            .addComponent(foldOnFileOpenCheckbox)
            .addComponent(autoCollapseCheckbox)
            .addLabeledComponent("Fold placeholder max length:", foldMaxLengthSpinner)
            // ── Pipeline Probe ──
            .addSeparator()
            .addLabeledComponent("Probe timeout (seconds):", probeTimeoutSpinner)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val s = SrForgeHighlightSettings.getInstance().state
        return blockEnabledCheckbox.isSelected != s.blockEnabled
            || parentEnabledCheckbox.isSelected != s.parentKeyEnabled
            || indexToFontStyle(parentFontStyleCombo.selectedIndex) != s.parentKeyFontStyle
            || colorInt(blockLightPicker) != s.blockLightColor
            || colorInt(blockDarkPicker) != s.blockDarkColor
            || colorInt(parentLightPicker) != s.parentLightColor
            || colorInt(parentDarkPicker) != s.parentDarkColor
            || foldOnFileOpenCheckbox.isSelected != s.foldOnFileOpen
            || autoCollapseCheckbox.isSelected != s.autoCollapseOnCaretExit
            || (foldMaxLengthSpinner.value as Int) != s.foldPlaceholderMaxLength
            || (probeTimeoutSpinner.value as Int) != s.probeTimeoutSeconds
    }

    override fun apply() {
        val settings = SrForgeHighlightSettings.getInstance()
        val s = settings.state
        s.blockEnabled = blockEnabledCheckbox.isSelected
        s.parentKeyEnabled = parentEnabledCheckbox.isSelected
        s.parentKeyFontStyle = indexToFontStyle(parentFontStyleCombo.selectedIndex)
        s.blockLightColor = colorInt(blockLightPicker, 0xF8FAFF)
        s.blockDarkColor = colorInt(blockDarkPicker, 0x2C2E33)
        s.parentLightColor = colorInt(parentLightPicker, 0xEDF2FC)
        s.parentDarkColor = colorInt(parentDarkPicker, 0x313438)
        s.foldOnFileOpen = foldOnFileOpenCheckbox.isSelected
        s.autoCollapseOnCaretExit = autoCollapseCheckbox.isSelected
        s.foldPlaceholderMaxLength = foldMaxLengthSpinner.value as Int
        s.probeTimeoutSeconds = probeTimeoutSpinner.value as Int

        ApplicationManager.getApplication().messageBus
            .syncPublisher(SrForgeHighlightSettings.TOPIC)
            .settingsChanged()
    }

    override fun reset() {
        val s = SrForgeHighlightSettings.getInstance().state
        blockEnabledCheckbox.isSelected = s.blockEnabled
        parentEnabledCheckbox.isSelected = s.parentKeyEnabled
        parentFontStyleCombo.selectedIndex = fontStyleToIndex(s.parentKeyFontStyle)
        blockLightPicker.selectedColor = Color(s.blockLightColor)
        blockDarkPicker.selectedColor = Color(s.blockDarkColor)
        parentLightPicker.selectedColor = Color(s.parentLightColor)
        parentDarkPicker.selectedColor = Color(s.parentDarkColor)
        foldOnFileOpenCheckbox.isSelected = s.foldOnFileOpen
        autoCollapseCheckbox.isSelected = s.autoCollapseOnCaretExit
        foldMaxLengthSpinner.value = s.foldPlaceholderMaxLength
        probeTimeoutSpinner.value = s.probeTimeoutSeconds
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun colorInt(picker: ColorPanel, default: Int = 0): Int =
        picker.selectedColor?.rgb?.and(0xFFFFFF) ?: default

    companion object {
        private val FONT_STYLE_NAMES = arrayOf("Plain", "Bold", "Italic")

        private fun fontStyleToIndex(style: Int): Int = when (style) {
            Font.BOLD -> 1
            Font.ITALIC -> 2
            else -> 0
        }

        private fun indexToFontStyle(index: Int): Int = when (index) {
            1 -> Font.BOLD
            2 -> Font.ITALIC
            else -> Font.PLAIN
        }
    }
}
