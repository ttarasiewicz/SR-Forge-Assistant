package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/** Shared utilities for working with `params:` YAML mappings that are siblings of `_target:`. */
object ParamUtils {

    /**
     * Returns the [YAMLMapping] that is the value of the `params:` key,
     * if [element] is inside one.
     */
    fun getParamsMapping(element: PsiElement): YAMLMapping? {
        val mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java, false) ?: return null
        val parentKv = mapping.parent as? YAMLKeyValue ?: return null
        if (parentKv.keyText == "params") return mapping
        // element might be deeper — walk up
        return getParamsMapping(parentKv)
    }

    /**
     * From any element inside a `params:` mapping, find the sibling `_target:` value
     * and resolve the Python target (class or function).
     * Returns a flat map of all accepted parameter names to [ParamInfo].
     */
    fun resolveParamsFromContext(element: PsiElement): LinkedHashMap<String, ParamInfo>? {
        val paramsMapping = getParamsMapping(element) ?: return null
        return resolveParamsFromParamsMapping(paramsMapping)
    }

    /**
     * Given a `params:` [YAMLMapping], find sibling `_target:` and resolve parameters.
     */
    fun resolveParamsFromParamsMapping(paramsMapping: YAMLMapping): LinkedHashMap<String, ParamInfo>? {
        val paramsKv = paramsMapping.parent as? YAMLKeyValue ?: return null
        val parentMapping = paramsKv.parent as? YAMLMapping ?: return null
        return resolveParamsFromTargetMapping(parentMapping)
    }

    /**
     * Given a [YAMLMapping] that contains `_target:` (and possibly `params:`),
     * resolve the target and return a flat param map.
     */
    fun resolveParamsFromTargetMapping(mapping: YAMLMapping): LinkedHashMap<String, ParamInfo>? {
        val targetKv = mapping.keyValues.firstOrNull { it.keyText == "_target" } ?: return null
        val scalar = targetKv.value as? YAMLScalar ?: return null
        val fqn = scalar.textValue.trim()
        if (fqn.isEmpty()) return null

        val project = mapping.project

        // Try class first
        val cls = TargetUtils.resolveTargetClass(fqn, project)
        if (cls != null) {
            return flattenClassParams(cls)
        }

        // Try function
        val func = TargetUtils.resolveTargetFunction(fqn, project)
        if (func != null) {
            return buildFunctionParams(func)
        }

        return null
    }

    /**
     * Resolve params from a `_target:` mapping and also return the grouped-by-class sections.
     * Useful for documentation that needs to know which class owns each param.
     */
    fun resolveParamSectionsFromContext(element: PsiElement): LinkedHashMap<PyClass, LinkedHashMap<String, ParamInfo>>? {
        val paramsMapping = getParamsMapping(element) ?: return null
        val paramsKv = paramsMapping.parent as? YAMLKeyValue ?: return null
        val parentMapping = paramsKv.parent as? YAMLMapping ?: return null
        val targetKv = parentMapping.keyValues.firstOrNull { it.keyText == "_target" } ?: return null
        val scalar = targetKv.value as? YAMLScalar ?: return null
        val fqn = scalar.textValue.trim()
        if (fqn.isEmpty()) return null

        val cls = TargetUtils.resolveTargetClass(fqn, parentMapping.project) ?: return null
        return buildPropagatedParams(cls)
    }

    /** Returns set of param names already written in the [paramsMapping]. */
    fun existingParamNames(paramsMapping: YAMLMapping): Set<String> =
        paramsMapping.keyValues.mapNotNull { it.keyText.takeIf { k -> k.isNotBlank() } }.toSet()

    /** Returns a placeholder stub value for a given [ParamInfo]. */
    fun stubValue(info: ParamInfo): String = when {
        info.defaultText != null -> info.defaultText
        info.typeText == "bool" || info.typeText == "Bool" -> "false"
        info.typeText == "int" || info.typeText == "Int" -> "0"
        info.typeText == "float" || info.typeText == "Float" -> "0.0"
        info.typeText == "str" || info.typeText == "String" -> "\"\""
        else -> "???"
    }

    /**
     * Generates missing parameter stubs inside the given [targetMapping].
     * If a `params:` key already exists, missing entries are added to it.
     * If no `params:` key exists, one is created as a sibling of `_target:`.
     *
     * Must be called inside a write action.
     */
    fun generateMissingStubs(targetMapping: YAMLMapping) {
        val allParams = resolveParamsFromTargetMapping(targetMapping) ?: return
        val paramsKv = targetMapping.keyValues.firstOrNull { it.keyText == "params" }
        val paramsMapping = paramsKv?.value as? YAMLMapping
        val existing = paramsMapping?.let { existingParamNames(it) } ?: emptySet()

        val missing = allParams.filter { (name, _) -> name !in existing }
        if (missing.isEmpty()) return

        val generator = YAMLElementGenerator.getInstance(targetMapping.project)
        // Sort: required params first, then optional
        val sorted = missing.entries.sortedBy { if (it.value.defaultText == null) 0 else 1 }

        if (paramsMapping != null) {
            for ((name, info) in sorted) {
                val value = stubValue(info)
                val tempFile = generator.createDummyYamlWithText("$name: $value")
                val kv = PsiTreeUtil.findChildOfType(tempFile, YAMLKeyValue::class.java) ?: continue
                paramsMapping.putKeyValue(kv)
            }
        } else {
            val yamlText = buildString {
                append("params:\n")
                for ((name, info) in sorted) {
                    append("  ").append(name).append(": ").append(stubValue(info)).append("\n")
                }
            }.trimEnd()

            val tempFile = generator.createDummyYamlWithText(yamlText)
            val newParamsKv = PsiTreeUtil.findChildOfType(tempFile, YAMLKeyValue::class.java) ?: return
            targetMapping.putKeyValue(newParamsKv)
        }
    }

    /**
     * Returns the number of missing parameters for a `_target:` mapping,
     * or null if the target cannot be resolved.
     */
    fun countMissingParams(targetMapping: YAMLMapping): Int? {
        val allParams = resolveParamsFromTargetMapping(targetMapping) ?: return null
        val paramsKv = targetMapping.keyValues.firstOrNull { it.keyText == "params" }
        val paramsMapping = paramsKv?.value as? YAMLMapping
        val existing = paramsMapping?.let { existingParamNames(it) } ?: emptySet()
        val missing = allParams.keys.count { it !in existing }
        return if (missing > 0) missing else null
    }

    /** Flatten class param sections into a single ordered map (first occurrence wins). */
    private fun flattenClassParams(cls: PyClass): LinkedHashMap<String, ParamInfo>? {
        val sections = buildPropagatedParams(cls) ?: return null
        val flat = LinkedHashMap<String, ParamInfo>()
        for ((_, params) in sections) {
            for ((name, info) in params) {
                flat.putIfAbsent(name, info)
            }
        }
        return if (flat.isEmpty()) null else flat
    }
}
