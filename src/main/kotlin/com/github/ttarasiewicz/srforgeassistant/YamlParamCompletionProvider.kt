package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Provides autocompletion for parameter names inside `params:` YAML mappings
 * that are siblings of a `_target:` key.
 */
class YamlParamCompletionProvider : CompletionProvider<CompletionParameters>() {

    /** Captures both the outer mapping (with _target:) and the optional inner params mapping. */
    private data class ParamsContext(
        val targetMapping: YAMLMapping,
        val paramsMapping: YAMLMapping?
    )

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        if (!SrForgeHighlightSettings.getInstance().state.targetCompletionEnabled) return
        val project = parameters.editor.project ?: return
        if (DumbService.isDumb(project)) return

        val position = parameters.position
        val ctx = findParamsContext(position) ?: return

        // Don't suggest param names when we're typing a value
        if (isInValuePosition(position, ctx.paramsMapping)) return

        val allParams = ParamUtils.resolveParamsFromTargetMapping(ctx.targetMapping) ?: return
        val existing = ctx.paramsMapping?.let { ParamUtils.existingParamNames(it) } ?: emptySet()

        // Strip the dummy identifier from existing names (the caret position has a dummy)
        val existingClean = existing.map { stripDummy(it) }.filter { it.isNotBlank() }.toSet()

        for ((name, info) in allParams) {
            if (name in existingClean) continue

            val isRequired = info.defaultText == null
            val typeText = info.typeText
            val tailText = if (info.defaultText != null) " = ${info.defaultText}" else ""

            val el = LookupElementBuilder.create(name)
                .withTypeText(typeText, true)
                .withTailText(tailText, true)
                .withIcon(AllIcons.Nodes.Parameter)
                .withBoldness(isRequired)
                .withInsertHandler(PARAM_KEY_INSERT_HANDLER)

            val priority = if (isRequired) 200.0 else 100.0
            result.addElement(PrioritizedLookupElement.withPriority(el, priority))
        }
    }

    /**
     * Walk up the PSI tree from [element] looking for a `params:` context.
     * Handles two cases:
     *   1. Element is inside a [YAMLMapping] whose parent KV is `params:`
     *   2. Element is inside the scalar value of a `params:` KV (empty params, dummy identifier)
     */
    private fun findParamsContext(element: PsiElement): ParamsContext? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                // Case 1: We're inside a YAMLMapping — check if its parent is params: KV
                is YAMLMapping -> {
                    val parentKv = current.parent as? YAMLKeyValue
                    if (parentKv != null && parentKv.keyText == "params") {
                        val targetMapping = parentKv.parent as? YAMLMapping ?: return null
                        if (targetMapping.keyValues.any { it.keyText == "_target" }) {
                            return ParamsContext(targetMapping, current)
                        }
                    }
                }
                // Case 2: We're inside a YAMLKeyValue that IS "params:" (dummy is its value)
                is YAMLKeyValue -> {
                    if (current.keyText == "params") {
                        val targetMapping = current.parent as? YAMLMapping ?: return null
                        if (targetMapping.keyValues.any { it.keyText == "_target" }) {
                            val paramsMapping = current.value as? YAMLMapping
                            return ParamsContext(targetMapping, paramsMapping)
                        }
                    }
                }
            }
            current = current.parent
        }
        return null
    }

    /**
     * Returns true if [element] is inside the VALUE part of an existing key-value
     * within the params mapping (i.e. user is typing a value, not a key).
     */
    private fun isInValuePosition(element: PsiElement, paramsMapping: YAMLMapping?): Boolean {
        if (paramsMapping == null) return false
        val kv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return false
        // Only check KVs that are direct children of the params mapping
        if (kv.parent != paramsMapping) return false
        val value = kv.value ?: return false
        return PsiTreeUtil.isAncestor(value, element, false)
    }

    private fun stripDummy(s: String): String =
        YamlTargetCompletionProvider.stripDummy(s)

    companion object {
        /** After inserting a param name, append ": " so the user can immediately type the value. */
        private val PARAM_KEY_INSERT_HANDLER = InsertHandler<LookupElement> { ctx, _ ->
            val doc = ctx.document
            val offset = ctx.tailOffset
            val remaining = doc.charsSequence.subSequence(offset, minOf(offset + 2, doc.textLength))
            if (!remaining.startsWith(":")) {
                doc.insertString(offset, ": ")
                ctx.editor.caretModel.moveToOffset(offset + 2)
            } else if (remaining.length >= 2 && remaining[0] == ':' && remaining[1] != ' ') {
                doc.insertString(offset + 1, " ")
                ctx.editor.caretModel.moveToOffset(offset + 2)
            } else {
                ctx.editor.caretModel.moveToOffset(offset + minOf(2, remaining.length))
            }
        }
    }
}
