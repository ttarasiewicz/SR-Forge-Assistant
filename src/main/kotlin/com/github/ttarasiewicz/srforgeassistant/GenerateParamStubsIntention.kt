package com.github.ttarasiewicz.srforgeassistant

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Alt+Enter intention that generates parameter stubs for the `_target:` class/function.
 * Works anywhere inside a YAML block that contains a `_target:` key.
 */
class GenerateParamStubsIntention : ModCommandAction {

    override fun getFamilyName(): String = "Generate parameter stubs for _target"

    override fun getPresentation(context: ActionContext): Presentation? {
        if (!SrForgeHighlightSettings.getInstance().state.paramStubsEnabled) return null
        if (DumbService.isDumb(context.project)) return null
        return try {
            val leaf = context.findLeaf() ?: return null
            val target = findContext(leaf) ?: return null
            val allParams = ParamUtils.resolveParamsFromTargetMapping(target.targetMapping) ?: return null
            val existing = target.paramsMapping?.let { ParamUtils.existingParamNames(it) } ?: emptySet()
            if (allParams.keys.none { it !in existing }) return null
            Presentation.of("Generate parameter stubs for _target")
        } catch (_: Throwable) {
            null
        }
    }

    override fun perform(context: ActionContext): ModCommand {
        val leaf = context.findLeaf() ?: return ModCommand.nop()
        val target = findContext(leaf) ?: return ModCommand.nop()
        return ModCommand.psiUpdate(target.targetMapping) { writable ->
            ParamUtils.generateMissingStubs(writable)
        }
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
