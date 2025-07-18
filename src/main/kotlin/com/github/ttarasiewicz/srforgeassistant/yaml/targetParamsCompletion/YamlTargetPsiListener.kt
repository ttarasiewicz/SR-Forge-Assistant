package com.github.ttarasiewicz.srforgeassistant.yaml.targetParamsCompletion

import com.github.ttarasiewicz.srforgeassistant.settings.MyPluginSettings
import com.github.ttarasiewicz.srforgeassistant.yaml.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLMapping
import com.intellij.openapi.command.CommandProcessor
import com.jetbrains.python.psi.PyClass

class YamlTargetPsiListener(private val project: Project) : PsiTreeChangeAdapter() {

    override fun childReplaced(event: PsiTreeChangeEvent) {
        val psiFile = event.file ?: return
        if (psiFile.fileType !is YAMLFileType) return
        ApplicationManager.getApplication().invokeLater {
            // Now it's safe to commit the document if needed.
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            // Process your event now, for example by re-reading the PSI.
            handleEvent(event.parent.parent, event)
        }
    }

    override fun childAdded(event: PsiTreeChangeEvent) {
        val psiFile = event.file ?: return
        if (psiFile.fileType !is YAMLFileType) return
        ApplicationManager.getApplication().invokeLater {
            // Now it's safe to commit the document if needed.
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            // Process your event now, for example by re-reading the PSI.
            handleEvent(event.parent, event)
        }
    }

    override fun childRemoved(event: PsiTreeChangeEvent) {
        val psiFile = event.file ?: return
        if (psiFile.fileType !is YAMLFileType) return
        ApplicationManager.getApplication().invokeLater {
            // Now it's safe to commit the document if needed.
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            // Process your event now, for example by re-reading the PSI.
//            handleEvent(event.parent.children[0], event)
        }
    }

    override fun childrenChanged(event: PsiTreeChangeEvent) {
        val psiFile = event.file ?: return
        if (psiFile.fileType !is YAMLFileType) return
        ApplicationManager.getApplication().invokeLater {
            // Now it's safe to commit the document if needed.
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            // Process your event now, for example by re-reading the PSI.
            handleEvent(event.parent, event)
        }
    }

    private fun handleEvent(element: PsiElement, event: PsiTreeChangeEvent? = null) {
        // Get the YAMLKeyValue that might have changed.
//        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java) ?: return
        val keyValue = element as? YAMLKeyValue ?: return
//        (keyValue.findParentOfType<YAMLKeyValue>()).keyText == "params"
//        (keyValue.parent.parent as YAMLKeyValueImpl).keyText - check if param
        val parentTargetSection = keyValue.getParentYamlTargetSection() ?: return


        // Schedule a write action to update the document.
        if (parentTargetSection.getTarget() == keyValue) {
            ApplicationManager.getApplication().invokeLater {
                CommandProcessor.getInstance().runUndoTransparentAction {
                    WriteCommandAction.runWriteCommandAction(project) {
                        processTargetChange(parentTargetSection)
                    }
                }
            }
            return
        }
        updateRegistryFromParams(parentTargetSection)

    }


    private fun processTargetChange(targetSection: YamlTargetSection) {
        val pyClass = targetSection.getClass()
        val target = targetSection.getTarget() ?: return

        val existingParams = targetSection.getParams()

        if (pyClass != null && existingParams == null) {
            // Insert a new "params" mapping.
            targetSection.generateParams()
        } else if (pyClass == null && existingParams != null) {
            // Remove the "params" mapping.
            WriteCommandAction.runWriteCommandAction(project) {
                var sibling = target.nextSibling
                while (sibling != null) {
                    // Capture the next sibling before deletion
                    val nextSibling = sibling.nextSibling

                    // Check if this sibling is either pure whitespace or a YAMLKeyValue with key "params"
                    val isWhitespace = sibling.text.trim().isEmpty()
                    val isParamsKey = sibling == existingParams

                    if (isWhitespace || isParamsKey) {
                        sibling.delete()
                        // Continue with the next sibling after deletion
                        sibling = nextSibling
                    } else {
                        break
                    }
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }

        }
    }

    fun updateRegistryFromParams(targetSection: YamlTargetSection) {
        // Assuming the params mapping contains key-value pairs for parameters
        val mapping = targetSection.getParamsMapping() ?: return

        val newDefaults = mutableMapOf<String, String>()
        for (child in mapping.keyValues) {
            val paramName = child.keyText
            val paramValue = child.value?.text ?: ""
            newDefaults[paramName] = paramValue
        }
        // Get the class name from the _target keyValue
        val pyClass = targetSection.getClass() ?: return
        val qName = PythonSignatureAnalyzer.getFullyQuallfiedName(project, pyClass) ?: return
        project.getService(ParamsRegistryService::class.java).updateDefaultsForClass(qName, newDefaults)
    }



}
