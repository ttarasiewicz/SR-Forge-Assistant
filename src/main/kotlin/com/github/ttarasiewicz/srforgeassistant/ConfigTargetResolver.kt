package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import com.jetbrains.python.psi.stubs.PyFunctionNameIndex
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a YAML `_target:` FQN to a Python PSI element with the same
 * semantics as sr-forge's runtime `ConfigResolver._instantiate_target`:
 *
 *   module = '.'.join(_target.split('.')[:-1])
 *   class_name = _target.split('.')[-1]
 *   importlib.import_module(module)
 *   getattr(imported_module, class_name)
 *
 * Two-stage lookup:
 *   1. Fast path — PyCharm's PSI index (covers anything PyCharm already
 *      indexes: site-packages of the configured interpreter, plus modules
 *      under explicitly marked Source Roots). Strict exact-FQN match only.
 *   2. Slow path — filesystem walk through the project's content roots and
 *      source roots, mirroring Python's import-module algorithm:
 *        - locate `<root>/a/b/c.py` or `<root>/a/b/c/__init__.py`
 *        - look for the leaf name as a top-level class/function definition
 *        - if it's a re-export (`from .x import Y`), follow the chain
 *
 * Re-export cycles are broken by a per-resolution visited set.
 * Successful resolutions are memoized at the project level and invalidated
 * on any PSI modification.
 */
object ConfigTargetResolver {

    fun resolveClass(fqn: String, project: Project): PyClass? =
        classCache(project).getOrPut(fqn) { Cached(doResolveClass(fqn, project)) }.value

    fun resolveFunction(fqn: String, project: Project): PyFunction? =
        functionCache(project).getOrPut(fqn) { Cached(doResolveFunction(fqn, project)) }.value

    // ── Internals ─────────────────────────────────────────────────────

    private data class Cached<T>(val value: T?)

    private fun doResolveClass(fqn: String, project: Project): PyClass? =
        resolveElement(fqn, project) as? PyClass

    private fun doResolveFunction(fqn: String, project: Project): PyFunction? =
        (resolveElement(fqn, project) as? PyFunction)?.takeIf { it.containingClass == null }

    private fun resolveElement(fqn: String, project: Project): PsiNamedElement? {
        val lastDot = fqn.lastIndexOf('.')
        if (lastDot <= 0) return null
        val modulePath = fqn.substring(0, lastDot)
        val name = fqn.substring(lastDot + 1)
        if (modulePath.isEmpty() || name.isEmpty()) return null

        indexLookup(modulePath, name, project)?.let { return it }

        val visited = HashSet<Pair<VirtualFile, String>>()
        for (root in searchRoots(project)) {
            val moduleFile = loadModuleFile(root, modulePath, project) ?: continue
            resolveNameInModule(moduleFile, name, project, visited)?.let { return it }
        }
        return null
    }

    /**
     * Exact-FQN match against PyCharm's PSI indices. Strict — no heuristic
     * guessing about re-export module layouts.
     */
    private fun indexLookup(modulePath: String, name: String, project: Project): PsiNamedElement? {
        val fqn = "$modulePath.$name"
        val scope = GlobalSearchScope.allScope(project)

        PyClassNameIndex.find(name, project, scope)
            .firstOrNull { it.qualifiedName == fqn }
            ?.let { return it }

        PyFunctionNameIndex.find(name, project, scope)
            .firstOrNull { it.containingClass == null && it.qualifiedName == fqn }
            ?.let { return it }

        // Re-export resolution through the index: the symbol is at fqn, but the
        // class itself lives at fqn.<simple>.<simple> (parent package re-exports
        // a same-named submodule's class). This is the only pattern we keep
        // from the legacy heuristics because PyCharm's index produces it
        // naturally when the re-export sits in a properly-indexed package.
        PyClassNameIndex.find(name, project, scope)
            .firstOrNull { it.qualifiedName == "$fqn.$name" }
            ?.let { return it }

        return null
    }

    /**
     * PyCharm content roots + source roots. Mirrors the directories that
     * end up on `sys.path` when a user runs Python from the project dir:
     * the content root behaves like `sys.path[0]`, and explicitly-marked
     * source roots behave like additional PYTHONPATH entries.
     */
    private fun searchRoots(project: Project): List<VirtualFile> {
        val manager = ProjectRootManager.getInstance(project)
        return (manager.contentRoots.asSequence() + manager.contentSourceRoots.asSequence())
            .distinct()
            .toList()
    }

    /** Look for `a/b/c.py` then `a/b/c/__init__.py` under [root]. */
    private fun loadModuleFile(root: VirtualFile, modulePath: String, project: Project): PyFile? {
        val relative = modulePath.replace('.', '/')
        val vf = root.findFileByRelativePath("$relative.py")
            ?: root.findFileByRelativePath("$relative/__init__.py")
            ?: return null
        return PsiManager.getInstance(project).findFile(vf) as? PyFile
    }

    /**
     * Find [name] defined or re-exported in [file]. Returns the resolved
     * class/function PSI element, or null if not found / cycle hit.
     */
    private fun resolveNameInModule(
        file: PyFile,
        name: String,
        project: Project,
        visited: MutableSet<Pair<VirtualFile, String>>
    ): PsiNamedElement? {
        val vf = file.virtualFile ?: return null
        if (!visited.add(vf to name)) return null

        // Direct top-level class/function definition.
        // The PsiNamedElement cast routes `.name` to the stable
        // PsiNamedElement.getName() instead of the experimental
        // PyAstClass / PyAstFunction.getName() — same trick used in
        // ParamAnalysis.kt to keep the verifier report clean.
        file.topLevelClasses.firstOrNull { (it as PsiNamedElement).name == name }
            ?.let { return it }
        file.topLevelFunctions.firstOrNull { (it as PsiNamedElement).name == name }
            ?.let { return it }

        // Re-export: `from X import name` or `from X import Y as name`.
        for (stmt in file.fromImports) {
            val sourceFile = lazy { resolveFromImportSource(stmt, file, project) }
            for (element in stmt.importElements) {
                val visibleName = element.asNameElement?.name ?: element.visibleName ?: continue
                if (visibleName != name) continue

                val originalName = element.importedQName?.lastComponent ?: continue
                val source = sourceFile.value ?: continue
                resolveNameInModule(source, originalName, project, visited)?.let { return it }
            }
        }

        return null
    }

    /**
     * Resolve a `from X import ...` statement to the source [PyFile].
     * Handles absolute imports (`from pkg.x import ...`) and relative
     * imports (`from .x import ...`, `from ..x import ...`, `from . import x`).
     */
    private fun resolveFromImportSource(
        stmt: PyFromImportStatement,
        currentFile: PyFile,
        project: Project
    ): PyFile? {
        val relativeLevel = stmt.relativeLevel
        val sourceQName = stmt.importSource?.asQualifiedName()?.toString().orEmpty()

        if (relativeLevel == 0) {
            if (sourceQName.isEmpty()) return null
            return searchRoots(project).asSequence()
                .mapNotNull { loadModuleFile(it, sourceQName, project) }
                .firstOrNull()
        }

        // Relative: start at the current file's directory, walk up (level - 1)
        // times, then descend into sourceQName (or use the dir's __init__.py
        // if sourceQName is empty).
        var dir: VirtualFile? = currentFile.virtualFile?.parent
        repeat(relativeLevel - 1) { dir = dir?.parent }
        val baseDir = dir ?: return null

        return if (sourceQName.isEmpty()) {
            val initFile = baseDir.findFileByRelativePath("__init__.py") ?: return null
            PsiManager.getInstance(project).findFile(initFile) as? PyFile
        } else {
            loadModuleFile(baseDir, sourceQName, project)
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────

    private fun classCache(project: Project): MutableMap<String, Cached<PyClass>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, Cached<PyClass>>(),
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }

    private fun functionCache(project: Project): MutableMap<String, Cached<PyFunction>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(
                ConcurrentHashMap<String, Cached<PyFunction>>(),
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }
}
