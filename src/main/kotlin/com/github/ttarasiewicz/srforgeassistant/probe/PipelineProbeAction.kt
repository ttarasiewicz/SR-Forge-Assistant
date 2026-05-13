package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Editor / toolbar action that opens the Pipeline Probe tool window and
 * activates the dataset-marker selection mode in the focused YAML editor.
 *
 * No dialog: every Dataset `_target:` value in the YAML lights up as a
 * clickable marker; clicking one runs the probe from that dataset down.
 */
class PipelineProbeAction : AnAction(
    "Run Pipeline Probe",
    "Probe dataset pipeline and visualize data flow",
    ProbeIcons.Probe,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Save documents so the probe (and the marker scan) read fresh PSI.
        FileDocumentManager.getInstance().saveAllDocuments()

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Pipeline Probe")
            ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.getContent(0)?.component as? ProbeToolWindowPanel
                ?: return@activate
            panel.enterMarkerMode()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabled = file == null || file is YAMLFile
    }
}
