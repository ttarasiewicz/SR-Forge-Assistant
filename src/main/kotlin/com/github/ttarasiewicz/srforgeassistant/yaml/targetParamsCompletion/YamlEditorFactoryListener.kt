package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.yaml.YAMLFileType

class YamlEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project: Project = editor.project ?: return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        if (psiFile?.fileType is YAMLFileType) {
            editor.document.addDocumentListener(YamlDocumentListener(project))
        }
    }
    // You can optionally implement editorReleased if needed.
}
