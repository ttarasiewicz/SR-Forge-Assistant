package com.github.ttarasiewicz.srforgeassistant.settings

import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import net.miginfocom.swing.MigLayout

class MyPluginSettingsPanel {
    val panel: JPanel = JPanel(MigLayout("fill", "[grow, fill]", "[][]"))
    val rootDirField: JTextField = JTextField()
    val excludeParamsField: JTextField = JTextField()

    init {
        panel.add(JLabel("Project Root Directory (e.g. core):"), "wrap")
        panel.add(rootDirField, "wrap")
        panel.add(JLabel("Exclude Parameters (format: FullyQualifiedClass:param1,param2; ...):"), "wrap")
        panel.add(excludeParamsField, "wrap")
    }
}