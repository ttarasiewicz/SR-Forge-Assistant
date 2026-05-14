package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFunction
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** Shared utilities for working with `_target:` YAML keys. */
object TargetUtils {

    /** Returns true if [element] is inside a `_target:` YAML scalar value. */
    fun isTargetValue(element: PsiElement): Boolean = getTargetScalar(element) != null

    /** Returns the enclosing [YAMLScalar] if [element] is inside a `_target:` value, null otherwise. */
    fun getTargetScalar(element: PsiElement): YAMLScalar? {
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return null
        val kv = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue::class.java, false) ?: return null
        return if (kv.keyText == "_target") scalar else null
    }

    /**
     * Resolves a fully-qualified `_target:` name to a [PyClass] using the same
     * semantics as sr-forge's runtime resolver. See [ConfigTargetResolver].
     */
    fun resolveTargetClass(fqn: String, project: Project): PyClass? =
        ConfigTargetResolver.resolveClass(fqn, project)

    /**
     * Resolves a fully-qualified `_target:` name to a top-level [PyFunction]
     * (not a method) using sr-forge runtime semantics. See [ConfigTargetResolver].
     */
    fun resolveTargetFunction(fqn: String, project: Project): PyFunction? =
        ConfigTargetResolver.resolveFunction(fqn, project)
}
