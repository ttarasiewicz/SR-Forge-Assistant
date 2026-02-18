package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Alt+Enter intention that generates parameter stubs for the `_target:` class/function.
 * Works when the caret is on the `_target:` line or inside the `params:` mapping.
 */
class GenerateParamStubsIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "SR-Forge Assistant"
    override fun getText(): String = "Generate parameter stubs for _target"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        if (DumbService.isDumb(project)) return false
        return try {
            val context = findContext(element) ?: return false
            val allParams = ParamUtils.resolveParamsFromTargetMapping(context.targetMapping) ?: return false
            val existing = context.paramsMapping?.let { ParamUtils.existingParamNames(it) } ?: emptySet()
            allParams.keys.any { it !in existing }
        } catch (_: Throwable) {
            false
        }
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val context = findContext(element) ?: return
        val allParams = ParamUtils.resolveParamsFromTargetMapping(context.targetMapping) ?: return
        val existing = context.paramsMapping?.let { ParamUtils.existingParamNames(it) } ?: emptySet()

        val missing = allParams.filter { (name, _) -> name !in existing }
        if (missing.isEmpty()) return

        val generator = YAMLElementGenerator.getInstance(project)

        // Sort: required params first, then optional
        val sorted = missing.entries.sortedBy { if (it.value.defaultText == null) 0 else 1 }

        if (context.paramsMapping != null) {
            // params: key exists — add missing entries
            val mapping = context.paramsMapping
            for ((name, info) in sorted) {
                val value = stubValue(info)
                val tempFile = generator.createDummyYamlWithText("$name: $value")
                val kv = PsiTreeUtil.findChildOfType(tempFile, YAMLKeyValue::class.java) ?: continue
                mapping.putKeyValue(kv)
            }
        } else {
            // No params: key yet — create it as a sibling of _target:
            val yamlText = buildString {
                append("params:\n")
                for ((name, info) in sorted) {
                    append("  ").append(name).append(": ").append(stubValue(info)).append("\n")
                }
            }.trimEnd()

            val tempFile = generator.createDummyYamlWithText(yamlText)
            val paramsKv = PsiTreeUtil.findChildOfType(tempFile, YAMLKeyValue::class.java) ?: return
            context.targetMapping.putKeyValue(paramsKv)
        }
    }

    private fun stubValue(info: ParamInfo): String = when {
        info.defaultText != null -> info.defaultText
        info.typeText == "bool" || info.typeText == "Bool" -> "false"
        info.typeText == "int" || info.typeText == "Int" -> "0"
        info.typeText == "float" || info.typeText == "Float" -> "0.0"
        info.typeText == "str" || info.typeText == "String" -> "\"\""
        else -> "???"
    }

    /** Context for the intention: the mapping containing _target and optionally existing params. */
    private data class IntentionContext(
        val targetMapping: YAMLMapping,
        val paramsMapping: YAMLMapping?
    )

    /**
     * Walk up the PSI tree to find a mapping that contains `_target:`.
     * Works from these caret positions:
     *   - On the `_target:` key or its value
     *   - On the `params:` key
     *   - Inside the `params:` mapping (on a child key or value)
     */
    private fun findContext(element: PsiElement): IntentionContext? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is YAMLMapping) {
                val targetKv = current.keyValues.firstOrNull { it.keyText == "_target" }
                if (targetKv != null) {
                    val paramsKv = current.keyValues.firstOrNull { it.keyText == "params" }
                    val paramsMapping = paramsKv?.value as? YAMLMapping
                    return IntentionContext(current, paramsMapping)
                }
            }
            current = current.parent
        }
        return null
    }
}
