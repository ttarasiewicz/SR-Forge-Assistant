package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.application.ReadAction
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar


class YamlTargetDocumentationProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val leaf = file.findElementAt(offset) ?: return emptyList()
        val scalar = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, /* strict = */ false)
            ?: return emptyList()

        // Ensure the parent is a key-value and the key is exactly "_target"
        val kv = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue::class.java, false) ?: return emptyList()
        if (kv.keyText != "_target") return emptyList()

        // Extract dotted FQN (unquoted value)
        val fqn = scalar.textValue.trim()
        if (fqn.isEmpty()) return emptyList()

        val project = file.project
        val scope = GlobalSearchScope.allScope(project)

        // Resolve by simple name first, then check qualifiedName to match exactly
        val simple = fqn.substringAfterLast('.')
        val candidates = ReadAction.compute<Collection<PyClass>, RuntimeException> {
            PyClassNameIndex.find(simple, project, scope)
        }

        val exact = candidates.firstOrNull { it.qualifiedName == fqn }
        val target = exact ?: candidates.firstOrNull()

        return if (target != null) {
            listOf(PyClassDocTarget(target))
        } else {
            listOf(NotFoundDocTarget(fqn))
        }
    }
}



