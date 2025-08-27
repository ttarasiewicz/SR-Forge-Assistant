package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class YamlTargetReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // Match YAML scalar nodes; we’ll filter to _target values in the provider
        val pattern = PlatformPatterns.psiElement(YAMLScalar::class.java)

        registrar.registerReferenceProvider(pattern, object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
                // Ensure this scalar is the VALUE of a key-value pair whose key is "_target"
                val kv = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue::class.java, false) ?: return PsiReference.EMPTY_ARRAY
                if (kv.keyText != "_target") return PsiReference.EMPTY_ARRAY
                return arrayOf(TargetClassReference(scalar))
            }
        })
    }
}

private class TargetClassReference(element: YAMLScalar)
    : PsiReferenceBase<YAMLScalar>(element, fullRange(element), /* soft = */ true) {

    override fun resolve(): PsiElement? {
        val fqn = element.textValue.trim()
        if (fqn.isEmpty()) return null

        val project = element.project
        val scope = GlobalSearchScope.allScope(project)
        val simple = fqn.substringAfterLast('.')

        val candidates = ReadAction.compute<Collection<PyClass>, RuntimeException> {
            PyClassNameIndex.find(simple, project, scope)
        }

        // Prefer exact qualifiedName match; fall back to first candidate
        return candidates.firstOrNull { it.qualifiedName == fqn } ?: candidates.firstOrNull()
    }

    // Completion variants come from your completion contributor, so none here
    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        // Replace the scalar’s whole (unquoted) content with the new FQN
        val doc = element.containingFile?.viewProvider?.document ?: return element
        val start = element.textRange.startOffset
        val rangeInElement = fullRange(element).shiftRight(start)
        doc.replaceString(rangeInElement.startOffset, rangeInElement.endOffset, newElementName)
        return element
    }

    companion object {
        private fun fullRange(s: YAMLScalar): TextRange = TextRange(0, s.textLength)
    }
}
