package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.stubs.PyClassNameIndex
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

    /** Resolves a fully-qualified name to a [PyClass].
     *  Tries exact qualifiedName match first, then handles the common Python
     *  re-export pattern where module name == class name
     *  (e.g., `pkg.Foo` → `pkg.Foo.Foo` when `__init__.py` does `from .Foo import Foo`). */
    fun resolveTargetClass(fqn: String, project: Project): PyClass? {
        val scope = GlobalSearchScope.allScope(project)
        val simple = fqn.substringAfterLast('.')
        val candidates = PyClassNameIndex.find(simple, project, scope)

        // 1. Exact qualifiedName match
        candidates.firstOrNull { it.qualifiedName == fqn }?.let { return it }

        // 2. Re-export: fqn omits the module when module name == class name
        //    e.g., "pkg.MagNAt" → "pkg.MagNAt.MagNAt"
        candidates.firstOrNull { it.qualifiedName == "$fqn.$simple" }?.let { return it }

        return null
    }
}
