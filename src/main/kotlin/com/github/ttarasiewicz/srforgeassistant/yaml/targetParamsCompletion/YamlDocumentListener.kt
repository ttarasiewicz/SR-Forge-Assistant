package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue

class YamlDocumentListener(private val project: Project) : DocumentListener {
    private val logger = Logger.getInstance(YamlDocumentListener::class.java)

    override fun documentChanged(event: DocumentEvent) {
        logger.info("YAML document changed: ${event.document.text}")

        // Commit the document so that PSI is updated
        PsiDocumentManager.getInstance(project).commitDocument(event.document)

        // Get the updated PSI file
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(event.document) ?: return

        // Process all YAMLKeyValue nodes with key "_target"
        val keyValues = PsiTreeUtil.collectElementsOfType(psiFile, YAMLKeyValue::class.java)
        for (kv in keyValues) {
            if (kv.keyText == "_target") {
                logger.info("Found _target key-value: ${kv.text}")
                // Process this _target element (e.g., insert or remove "params")
            }
        }
    }

    // Implement other methods as needed (empty bodies if not required)
    override fun beforeDocumentChange(event: DocumentEvent) {}
}