package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.project.DumbService
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

class YamlTargetDocumentationProvider : DocumentationTargetProvider {

    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (DumbService.isDumb(file.project)) return emptyList()

        val leaf = file.findElementAt(offset) ?: return emptyList()

        // Check if we're on a param key first
        try {
            val paramDoc = tryParamDocumentation(leaf)
            if (paramDoc != null) return listOf(paramDoc)
        } catch (_: Throwable) {
            // Don't let param doc failures block _target: documentation
        }

        // Existing _target: handling
        val scalar = TargetUtils.getTargetScalar(leaf) ?: return emptyList()

        val fqn = scalar.textValue.trim()
        if (fqn.isEmpty()) return emptyList()

        val cls = TargetUtils.resolveTargetClass(fqn, file.project)
        if (cls != null) return listOf(PyClassDocTarget(cls))

        val func = TargetUtils.resolveTargetFunction(fqn, file.project)
        if (func != null) return listOf(PyFunctionDocTarget(func))

        return listOf(NotFoundDocTarget(fqn))
    }

    /** If the leaf is a key inside a `params:` mapping, return a [ParamDocTarget]. */
    private fun tryParamDocumentation(leaf: PsiElement): ParamDocTarget? {
        val kv = PsiTreeUtil.getParentOfType(leaf, YAMLKeyValue::class.java, false) ?: return null
        // Check the leaf is actually in the key, not the value
        val key = kv.key ?: return null
        if (!PsiTreeUtil.isAncestor(key, leaf, false)) return null

        val paramsMapping = kv.parent as? YAMLMapping ?: return null
        val paramsKv = paramsMapping.parent as? YAMLKeyValue ?: return null
        if (paramsKv.keyText != "params") return null

        val paramName = kv.keyText
        if (paramName.isBlank()) return null

        // Try to find the owning class for this param (for richer docs)
        val sections = ParamUtils.resolveParamSectionsFromContext(leaf)
        if (sections != null) {
            for ((cls, params) in sections) {
                val info = params[paramName]
                if (info != null) return ParamDocTarget(paramName, info, cls)
            }
        }

        // Fall back to flat param resolution (covers functions too)
        val allParams = ParamUtils.resolveParamsFromContext(leaf) ?: return null
        val info = allParams[paramName] ?: return null
        return ParamDocTarget(paramName, info)
    }
}
