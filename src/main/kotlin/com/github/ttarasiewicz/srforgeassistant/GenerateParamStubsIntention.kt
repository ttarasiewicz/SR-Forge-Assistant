package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Alt+Enter intention that generates parameter stubs for the `_target:` class/function.
 * Works anywhere inside a YAML block that contains a `_target:` key.
 */
class GenerateParamStubsIntention : IntentionAction {

    override fun getFamilyName(): String = "SR-Forge Assistant"
    override fun getText(): String = "Generate parameter stubs for _target"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (!SrForgeHighlightSettings.getInstance().state.paramStubsEnabled) return false
        if (file == null || editor == null) return false
        if (DumbService.isDumb(project)) return false
        return try {
            val element = file.findElementAt(editor.caretModel.offset) ?: return false
            val context = findContext(element) ?: return false
            val allParams = ParamUtils.resolveParamsFromTargetMapping(context.targetMapping) ?: return false
            val existing = context.paramsMapping?.let { ParamUtils.existingParamNames(it) } ?: emptySet()
            allParams.keys.any { it !in existing }
        } catch (_: Throwable) {
            false
        }
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file == null || editor == null) return
        val element = file.findElementAt(editor.caretModel.offset) ?: return
        val context = findContext(element) ?: return
        ParamUtils.generateMissingStubs(context.targetMapping)
    }

    /** Context for the intention: the mapping containing _target and optionally existing params. */
    private data class IntentionContext(
        val targetMapping: YAMLMapping,
        val paramsMapping: YAMLMapping?
    )

    /**
     * Walk up the PSI tree to find a mapping that contains `_target:`.
     * Works from any position within the block: _target key/value, params key,
     * individual param keys/values, or whitespace between them.
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
