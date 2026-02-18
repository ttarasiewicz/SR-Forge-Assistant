package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLScalar

class YamlTargetReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val pattern = PlatformPatterns.psiElement(YAMLScalar::class.java)

        registrar.registerReferenceProvider(pattern, object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                if (!TargetUtils.isTargetValue(element)) return PsiReference.EMPTY_ARRAY
                return arrayOf(TargetClassReference(element as YAMLScalar))
            }
        })
    }
}

private class TargetClassReference(element: YAMLScalar)
    : PsiReferenceBase<YAMLScalar>(element, fullRange(element), /* soft = */ true) {

    override fun resolve(): PsiElement? {
        val fqn = element.textValue.trim()
        if (fqn.isEmpty()) return null
        return TargetUtils.resolveTargetClass(fqn, element.project)
            ?: TargetUtils.resolveTargetFunction(fqn, element.project)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
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
