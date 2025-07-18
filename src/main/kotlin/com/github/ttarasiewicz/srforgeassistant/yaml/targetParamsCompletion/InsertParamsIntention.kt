package com.github.ttarasiewicz.srforgeassistant.yaml.targetParamsCompletion

import com.github.ttarasiewicz.srforgeassistant.yaml.PythonSignatureAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.Nls
import org.jetbrains.yaml.psi.YAMLKeyValue
import com.intellij.psi.PsiFileFactory
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLMapping
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement


class InsertParamsIntention : IntentionAction {
    override fun startInWriteAction(): Boolean = true

    @Nls
    override fun getFamilyName(): String = "Insert params from __init__"

    @Nls
    override fun getText(): String = "Insert 'params' key based on class signature"


    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file == null || editor == null) return false

        // Find the PSI element at caret and ensure it's within a YAMLKeyValue whose key is _target.
        val element = file.findElementAt(editor.caretModel.offset) ?: return false
        val keyValue = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java) ?: return false

        if (keyValue.keyText != "_target") return false
        // Ensure the _target value exists.
        if (keyValue.valueText.isNullOrBlank()) return false

        // Optionally, you can check if there's already a sibling "params" key.
        val parentMapping = keyValue.parent
        if (parentMapping != null && parentMapping.children.any {
                it is YAMLKeyValue && it.keyText == "params"
            }) {
            return false
        }

        // Check that the class exists.
        val className = keyValue.valueText.trim()
        val pyClass = PythonSignatureAnalyzer.findPythonClass(project, className)
        return pyClass != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file == null || editor == null) return

        // Get the _target YAML key-value node.
        val element = file.findElementAt(editor.caretModel.offset) ?: return
        val keyValue = ApplicationManager.getApplication().runReadAction<PsiElement?> {
            PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java)
        } as YAMLKeyValue? ?: return
        if (keyValue.keyText != "_target") return

        val className = keyValue.valueText.trim()
        val pyClass = PythonSignatureAnalyzer.findPythonClass(project, className) ?: return

        // Get the __init__ parameters (excluding "self")
        val paramNames = PythonSignatureAnalyzer.getFunctionParameters(pyClass, "__init__")
            .mapNotNull { it.name }

        val indent = detectIndent(keyValue)
        val builder = StringBuilder("\n")
        builder.append(indent).append("params:\n")
        for (param in paramNames) {
            builder.append(indent).append("  ").append(param).append(":\n")
        }
        val newMappingText = builder.toString()

        // Use PsiFileFactory helper to create a YAML mapping element
        val newMapping = createYamlMappingFromText(project, newMappingText)
        if (newMapping == null) return
        val psiFacade = com.intellij.psi.PsiParserFacade.getInstance(project)
        val whiteSpace = psiFacade.createWhiteSpaceFromText("\n" + indent)

        // Insert the whitespace after the _target key-value, then insert the new mapping after the whitespace.
        val parent = keyValue.parent
        parent.addAfter(whiteSpace, keyValue)
        parent.addAfter(newMapping, whiteSpace)
    }

    // A helper to detect the current indent of the _target key.
    private fun detectIndent(keyValue: YAMLKeyValue): String {
        // You can try to get the text of the keyValue and detect its leading whitespace.
        val text = keyValue.text
        val lines = text.lines()
        if (lines.isNotEmpty()) {
            val firstLine = lines.first()
            val indentMatch = Regex("^\\s*").find(firstLine)
            if (indentMatch != null) {
                return indentMatch.value
            }
        }
        return "  "  // Fallback indent (2 spaces)
    }

    fun createYamlMappingFromText(project: Project, text: String): YAMLMapping? {
        // Create a temporary YAML file from the given text.
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText("dummy.yaml", YAMLFileType.YML, text)
        // Typically, a YAML file has a document that contains a mapping.
        val document = PsiTreeUtil.findChildOfType(psiFile, YAMLDocument::class.java)
        return document?.topLevelValue as? YAMLMapping
    }
}