package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.util.function.Function
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Shows a native-looking action toolbar at the top of YAML files
 * with SR-Forge Assistant quick-actions.
 */
class SrForgeEditorNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent?>? {
        val name = file.name
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) return null

        return Function { fileEditor ->
            val editor = (fileEditor as? TextEditor)?.editor ?: return@Function null

            val toggleAction = ActionManager.getInstance()
                .getAction("SrForgeAssistant.ToggleInterpolationFolds")
                ?: return@Function null

            val probeAction = ActionManager.getInstance()
                .getAction("SrForgeAssistant.PipelineProbe")

            val settingsAction = ActionManager.getInstance()
                .getAction("SrForgeAssistant.OpenSettings")

            val group = DefaultActionGroup().apply {
                add(toggleAction)
                if (probeAction != null) {
                    addSeparator()
                    add(probeAction)
                }
                if (settingsAction != null) {
                    addSeparator()
                    add(settingsAction)
                }
            }

            val toolbar = ActionManager.getInstance().createActionToolbar(
                "SrForgeAssistant.EditorToolbar",
                group,
                true
            )
            toolbar.targetComponent = editor.component

            val icon = IconLoader.getIcon("/icons/sr-forge.svg", javaClass)

            val label = JLabel("SR-Forge Assistant", icon, JLabel.LEFT).apply {
                border = JBUI.Borders.emptyLeft(8)
                foreground = UIUtil.getLabelForeground()
                font = font.deriveFont(Font.BOLD)
            }

            val separator = object : JComponent() {
                override fun getPreferredSize() = Dimension(JBUI.scale(1), JBUI.scale(20))
                override fun getMaximumSize() = preferredSize
                override fun getMinimumSize() = preferredSize
                override fun paintComponent(g: Graphics) {
                    g.color = UIUtil.getBoundsColor()
                    g.fillRect(0, 0, width, height)
                }
            }

            val leftPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(label)
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(separator)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
            }

            JPanel(BorderLayout()).apply {
                add(leftPanel, BorderLayout.WEST)
                add(toolbar.component, BorderLayout.CENTER)
                border = JBUI.Borders.customLineBottom(UIUtil.getBoundsColor())
            }
        }
    }
}
