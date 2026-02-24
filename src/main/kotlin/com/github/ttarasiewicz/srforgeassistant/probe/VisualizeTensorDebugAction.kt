package com.github.ttarasiewicz.srforgeassistant.probe

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import com.jetbrains.python.debugger.PyDebugValue
import java.nio.file.Files
import javax.swing.SwingUtilities

/**
 * Debugger context-menu action that saves a tensor/ndarray variable to a temp
 * .npy file and opens the interactive [TensorVisualizerDialog].
 *
 * Appears in the Variables/Watches right-click menu only when the selected
 * variable is a PyTorch Tensor or NumPy ndarray.
 *
 * Supports both classic PyDebugValue (older PyCharm) and the new
 * FrontendXNamedValue wrapper (PyCharm 2026+ with frontend/backend split).
 */
class VisualizeTensorDebugAction : AnAction(
    "Visualize Tensor",
    "Open tensor visualizer for this variable",
    ProbeIcons.SrForge
), DumbAware {

    private val log = Logger.getInstance(VisualizeTensorDebugAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val node = getSelectedNode(e)
        if (node == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Strategy 1: Classic PyDebugValue (older PyCharm / non-frontend mode)
        val pyValue = node.valueContainer as? PyDebugValue
        if (pyValue != null) {
            e.presentation.isEnabledAndVisible =
                isTensorType(pyValue.type) || isTensorType(pyValue.qualifiedType)
            return
        }

        // Strategy 2: Check rawValue string for tensor patterns
        // (PyCharm 2026+ wraps values in FrontendXNamedValue)
        val rawValue = node.rawValue
        if (rawValue != null) {
            e.presentation.isEnabledAndVisible = looksLikeTensor(rawValue)
            return
        }

        e.presentation.isEnabledAndVisible = false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val node = getSelectedNode(e)
        if (node == null) {
            notify(project, "Could not get variable from debugger", NotificationType.WARNING)
            return
        }

        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null) {
            notify(project, "No active debug session", NotificationType.WARNING)
            return
        }

        val evaluator = session.debugProcess.evaluator
        if (evaluator == null) {
            notify(project, "Debugger evaluator not available", NotificationType.WARNING)
            return
        }

        // Get variable expression: prefer PyDebugValue.evaluationExpression, fall back to node name
        val pyValue = node.valueContainer as? PyDebugValue
        val varExpr = pyValue?.evaluationExpression ?: node.name ?: "tensor"
        val varName = node.name ?: varExpr
        val varType = pyValue?.type ?: inferTypeFromRawValue(node.rawValue)

        val npyFile = Files.createTempFile("srforge_dbg_viz_", ".npy").toFile()
        val infoFile = Files.createTempFile("srforge_dbg_info_", ".json").toFile()
        npyFile.deleteOnExit()
        infoFile.deleteOnExit()
        val npyPath = npyFile.absolutePath.replace("\\", "\\\\")
        val infoPath = infoFile.absolutePath.replace("\\", "\\\\")

        // Single expression: saves .npy and writes shape/dtype info to a JSON file.
        // This avoids needing to extract string values from the XValue result,
        // which doesn't work with FrontendXNamedValue wrappers.
        val saveExpr = "(lambda _v, _np, _json, _npy, _info: (" +
                "_np.save(_npy, _v.detach().cpu().numpy() if hasattr(_v, 'detach') else _v), " +
                "open(_info, 'w').write(_json.dumps({" +
                "'shape': list(_v.shape) if hasattr(_v, 'shape') else [], " +
                "'dtype': str(_v.dtype).replace('torch.', '') if hasattr(_v, 'dtype') else 'unknown'" +
                "}))))" +
                "($varExpr, __import__('numpy'), __import__('json'), r'$npyPath', r'$infoPath')"

        evaluator.evaluate(saveExpr, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) {
                var shape: String? = null
                var dtype: String? = null
                try {
                    if (infoFile.exists()) {
                        val infoObj = Gson().fromJson(infoFile.readText().trim(), JsonObject::class.java)
                        shape = infoObj.getAsJsonArray("shape")?.toString()
                        dtype = infoObj.get("dtype")?.asString
                    }
                } catch (ex: Exception) {
                    log.warn("SR-Forge: Failed to read info file: ${ex.message}")
                } finally {
                    infoFile.delete()
                }
                openVisualizer(project, varName, varType, shape, dtype, npyFile)
            }

            override fun errorOccurred(errorMessage: String) {
                npyFile.delete()
                infoFile.delete()
                SwingUtilities.invokeLater {
                    notify(project, "Failed to save tensor: $errorMessage", NotificationType.ERROR)
                }
            }
        }, null)
    }

    private fun openVisualizer(
        project: Project,
        varName: String,
        varType: String,
        shape: String?,
        dtype: String?,
        npyFile: java.io.File
    ) {
        val snapshot = FieldSnapshot(
            key = varName,
            pythonType = varType,
            shape = shape,
            dtype = dtype,
            minValue = null,
            maxValue = null,
            meanValue = null,
            stdValue = null,
            preview = null,
            sizeBytes = npyFile.length(),
            npyPath = npyFile.absolutePath
        )
        SwingUtilities.invokeLater {
            val dialog = TensorVisualizerDialog(project, snapshot, cleanupNpyOnClose = true)
            dialog.show()
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun getSelectedNode(e: AnActionEvent): XValueNodeImpl? {
        val tree = XDebuggerTree.getTree(e.dataContext) ?: return null
        return tree.selectionPath?.lastPathComponent as? XValueNodeImpl
    }

    private fun isTensorType(type: String?): Boolean {
        if (type == null) return false
        val t = type.lowercase()
        return "tensor" in t || "ndarray" in t
    }

    /** Check if the rendered value string looks like a tensor or ndarray. */
    private fun looksLikeTensor(rawValue: String): Boolean {
        val lower = rawValue.lowercase().trimStart()
        return lower.startsWith("tensor(") ||
                lower.startsWith("array(") ||
                lower.contains("ndarray")
    }

    /** Infer Python type name from the rendered value string. */
    private fun inferTypeFromRawValue(rawValue: String?): String {
        if (rawValue == null) return "Tensor"
        val lower = rawValue.lowercase().trimStart()
        return when {
            lower.startsWith("tensor(") -> "Tensor"
            lower.startsWith("array(") || "ndarray" in lower -> "ndarray"
            else -> "Tensor"
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SR-Forge Assistant")
            .createNotification("SR-Forge Tensor Visualizer", message, type)
            .notify(project)
    }
}
