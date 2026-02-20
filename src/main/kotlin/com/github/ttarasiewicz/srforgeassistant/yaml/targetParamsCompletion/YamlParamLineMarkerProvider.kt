package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import org.jetbrains.yaml.psi.YAMLKeyValue
import java.awt.event.MouseEvent

class YamlParamLineMarkerProvider : LineMarkerProvider {
    private val logger = Logger.getInstance(YamlParamLineMarkerProvider::class.java)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // We are interested only in YAMLKeyValue elements that represent parameters.
        if (element !is YAMLKeyValue) return null
        val targetSection = element.getParentYamlTargetSection() ?: return null
        if (targetSection.hasParam(element.keyText)){
            // Create a gutter icon that, when clicked, will let the user disable this parameter.
            val navigationHandler = GutterIconNavigationHandler<PsiElement> { e, elt ->
                showParamPopup(elt, e, targetSection)
            }

            return LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.Actions.Cancel, // icon to represent "disable parameter"
                { "Disable parameter '${element.keyText}'" },
                navigationHandler,
                GutterIconRenderer.Alignment.RIGHT,
                { "Disable parameter" }
            )
        }
        if (targetSection.getTarget() == element){
            val navigationHandler = GutterIconNavigationHandler<PsiElement> {
                e, elt -> showResetExclusionsPopup(elt, e, targetSection)
            }
            return LineMarkerInfo(
                element,
                element.textRange,
                AllIcons.General.Reset, // icon to represent "reset exclusions"
                { "Reset excluded parameters" },
                navigationHandler,
                GutterIconRenderer.Alignment.RIGHT,
                { "Reset exclusions" }
            )
        }
        return null
    }

    private fun showResetExclusionsPopup(element: PsiElement, e: MouseEvent, targetSection: YamlTargetSection){
        val relativePoint = RelativePoint(e)
        val step = object : BaseListPopupStep<String>(
            "Reset excluded parameters for this target?",
            listOf("Reset", "Cancel")
        ){
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue == "Reset"){
                    resetExclusions(targetSection)
                }
                return FINAL_CHOICE
            }
        }
        val popup: ListPopup = JBPopupFactory.getInstance().createListPopup(step)
        popup.show(relativePoint)
    }

    /**
     * Shows a popup at the given editor location with a confirmation to disable the parameter.
     */
    private fun showParamPopup(elt: PsiElement, e: MouseEvent, targetSection: YamlTargetSection) {
        // Ensure the element is a YAMLKeyValue.
        val keyValue = elt as? YAMLKeyValue ?: return
        // Create a RelativePoint from the MouseEvent.
        val relativePoint = RelativePoint(e)

        // Create a popup step with two options: "Disable" and "Cancel".
        val step = object : BaseListPopupStep<String>(
            "What do you want to do with '${keyValue.keyText}'?",
            listOf("Exclude from autocompletion", "Reset to default", "Cancel")
        ) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (selectedValue == "Exclude from autocompletion") {
                    disableParameter(keyValue, targetSection)
                }
                else if (selectedValue == "Reset to default"){
                    targetSection.resetParam(keyValue.keyText)
                }
                // Return FINAL_CHOICE to close the popup.
                return FINAL_CHOICE
            }
        }

        val popup: ListPopup = JBPopupFactory.getInstance().createListPopup(step)
        popup.show(relativePoint)
    }
    /**
     * Disables a parameter by removing it from the PSI and updating the persistent settings.
     */
    private fun disableParameter(paramKv: YAMLKeyValue, targetSection: YamlTargetSection) {
        val project = paramKv.project
        // Find the parent mapping that contains the parameter, and also find the _target key.
        val targetClass = targetSection.getClass() ?: return
        val qualifiedName = PythonSignatureAnalyzer.getFullyQuallfiedName(project, targetClass) ?: return
        // Remove the parameter key-value.
        WriteCommandAction.runWriteCommandAction(project) {
            // remove previous whitespaces
            var nextSibling = paramKv.nextSibling
            paramKv.delete()
            while (nextSibling != null && nextSibling.text.trim().isEmpty()) {
                val next = nextSibling.nextSibling
                nextSibling.delete()
                nextSibling = next
            }
            val settings = com.github.ttarasiewicz.srforgeassistant.settings.MyPluginSettings.getInstance(project)
            settings.addExclusion(qualifiedName, paramKv.keyText)
        }
    }

    private fun resetExclusions(targetSection: YamlTargetSection){
        val project = targetSection.project
        val targetClass = targetSection.getClass() ?: return
        val qualifiedName = PythonSignatureAnalyzer.getFullyQuallfiedName(project, targetClass) ?: return
        val settings = com.github.ttarasiewicz.srforgeassistant.settings.MyPluginSettings.getInstance(project)
        settings.resetClassExclusions(qualifiedName)
        targetSection.generateParams()
    }
}