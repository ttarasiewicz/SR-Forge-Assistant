package com.github.ttarasiewicz.srforgeassistant

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
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
