package com.github.ttarasiewicz.srforgeassistant

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

class OpenSrForgeSettingsAction : AnAction(
    "SR-Forge Settings",
    "Open SR-Forge Assistant highlight settings",
    AllIcons.General.GearPlain
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            e.project,
            SrForgeSettingsConfigurable::class.java
        )
    }
}
