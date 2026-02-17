package com.github.ttarasiewicz.srforgeassistant

import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiFile

class YamlTargetDocumentationProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val leaf = file.findElementAt(offset) ?: return emptyList()
        val scalar = TargetUtils.getTargetScalar(leaf) ?: return emptyList()

        val fqn = scalar.textValue.trim()
        if (fqn.isEmpty()) return emptyList()

        val cls = TargetUtils.resolveTargetClass(fqn, file.project)
        return if (cls != null) listOf(PyClassDocTarget(cls)) else listOf(NotFoundDocTarget(fqn))
    }
}
