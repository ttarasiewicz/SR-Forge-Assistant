package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile

class PipelineProbeAction : AnAction(
    "Run Pipeline Probe",
    "Probe dataset pipeline and visualize data flow",
    ProbeIcons.Probe
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Try data context first, then fall back to the currently open editor
        val psiFile = (e.getData(CommonDataKeys.PSI_FILE) as? YAMLFile)
            ?: run {
                val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                vf?.let { PsiManager.getInstance(project).findFile(it) } as? YAMLFile
            }
            ?: return

        // Parse YAML for dataset nodes
        val datasets = YamlPipelineParser.findDatasetNodes(psiFile, project)
        if (datasets.isEmpty()) {
            showNotification(e, "No dataset definitions found in this YAML file.")
            return
        }

        // Show selector dialog
        val dialog = DatasetSelectorDialog(project, datasets)
        if (!dialog.showAndGet()) return

        val selectedPath = dialog.selectedDatasetPath
        val pathOverrides = dialog.pathOverrides

        val selectedNode = datasets.firstOrNull { it.first == selectedPath }?.second
        if (selectedNode == null) {
            showNotification(e, "Selected dataset not found.")
            return
        }

        // Validate Python SDK
        val sdk = ProbeExecutor.getPythonSdk(project)
        if (sdk == null) {
            showNotification(e, "No Python SDK configured for this project. Please configure a Python interpreter.")
            return
        }
        val pythonPath = ProbeExecutor.getPythonPath(sdk)
        if (pythonPath == null) {
            showNotification(e, "Cannot determine Python interpreter path from SDK.")
            return
        }

        val yamlFilePath = psiFile.virtualFile?.path ?: return
        val projectPaths = ProbeExecutor.getProjectSourcePaths(project)

        // Generate probe script
        val script = ProbeScriptGenerator.generate(
            yamlFilePath = yamlFilePath,
            datasetPath = selectedPath,
            pipeline = selectedNode,
            pathOverrides = pathOverrides,
            projectPaths = projectPaths
        )

        // Run in background
        object : Task.Backgroundable(project, "Running Pipeline Probe...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = ProbeExecutor.execute(project, script, indicator)

                // Show results in tool window
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow("Pipeline Probe")

                    if (toolWindow != null) {
                        toolWindow.activate {
                            val content = toolWindow.contentManager.getContent(0)
                            val panel = content?.component as? ProbeToolWindowPanel
                            panel?.displayResult(result)
                        }
                    }
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        // When used from the SR-Forge toolbar (which is only shown for YAML files),
        // CommonDataKeys.PSI_FILE may not be available in the data context.
        // Keep the action visible and validate in actionPerformed instead.
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabled = file == null || file is YAMLFile
    }

    private fun showNotification(e: AnActionEvent, message: String) {
        val project = e.project ?: return
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("SR-Forge Assistant")
                .createNotification(message, NotificationType.WARNING)
                .notify(project)
        } catch (_: Exception) {
            // Fallback: notification group might not be registered yet
            com.intellij.openapi.ui.Messages.showWarningDialog(project, message, "Pipeline Probe")
        }
    }
}
