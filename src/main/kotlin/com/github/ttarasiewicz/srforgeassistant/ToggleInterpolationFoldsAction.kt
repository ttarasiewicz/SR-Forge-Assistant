package com.github.ttarasiewicz.srforgeassistant

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Toggles fold state of all `${...}` and `%{...}` interpolation expressions
 * in the current YAML file, without affecting other folds (mappings, etc.).
 *
 * If any interpolation fold is expanded, collapses all.
 * If all are collapsed, expands all.
 *
 * Icon changes dynamically to reflect current state.
 */
class ToggleInterpolationFoldsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = editor.document.text

        val interpolationRegions = editor.foldingModel.allFoldRegions.filter { region ->
            InterpolationFoldCaretListener.isInterpolationRegion(text, region)
        }
        if (interpolationRegions.isEmpty()) return

        val anyExpanded = interpolationRegions.any { it.isExpanded }

        editor.foldingModel.runBatchFoldingOperation {
            for (region in interpolationRegions) {
                region.isExpanded = !anyExpanded
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val isYaml = file != null &&
            (file.name.endsWith(".yaml") || file.name.endsWith(".yml"))
        e.presentation.isEnabledAndVisible = isYaml

        if (isYaml) {
            val editor = e.getData(CommonDataKeys.EDITOR)
            if (editor != null) {
                val text = editor.document.text
                val anyExpanded = editor.foldingModel.allFoldRegions.any { region ->
                    region.isExpanded &&
                        InterpolationFoldCaretListener.isInterpolationRegion(text, region)
                }
                if (anyExpanded) {
                    e.presentation.icon = AllIcons.Actions.Collapseall
                    e.presentation.text = "Collapse Interpolation Folds"
                } else {
                    e.presentation.icon = AllIcons.Actions.Expandall
                    e.presentation.text = "Expand Interpolation Folds"
                }
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
