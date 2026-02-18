package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import com.jetbrains.python.psi.stubs.PyFunctionNameIndex
import org.jetbrains.yaml.psi.YAMLScalar

class YamlTargetCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val project = parameters.editor.project ?: return
        if (DumbService.isDumb(project)) return

        val scalar = TargetUtils.getTargetScalar(parameters.position) ?: return

        // If inside an interpolation expression, let the interpolation contributor handle it
        if (YamlInterpolationCompletionContributor.isInsideInterpolation(
                parameters.editor.document.text, parameters.offset)) return

        result.stopHere()

        val raw = scalar.textValue.orEmpty()
        val needle = stripDummy(raw)
        val prefix = CompletionUtil.findReferenceOrAlphanumericPrefix(parameters)

        val isDotted = needle.contains('.')

        val matched = if (isDotted && needle.isNotEmpty())
            result.withPrefixMatcher(PlainPrefixMatcher(needle))
        else result
        val out = matched.withRelevanceSorter(buildSorter())

        val term = when {
            needle.isEmpty() -> prefix
            isDotted -> needle.substringAfterLast('.', "")
            else -> needle
        }

        val scopes = createScopes(project)
        val session = CompletionSession(project, scopes, term, needle, isDotted)

        ReadAction.run<RuntimeException> {
            if (isDotted) {
                // Fast package discovery from __init__.py files (VFS scan, no class resolution)
                session.discoverPackageEdges(scopes.project)
                session.discoverPackageEdges(scopes.libraries)

                // Discover .py module files in the current prefix package so they
                // appear as navigable targets (e.g. "lazy_datasets" under "srforge.dataset")
                session.discoverModulesInPrefix(scopes.project)
                session.discoverModulesInPrefix(scopes.libraries)

                if (term.isNotEmpty()) {
                    // User is typing a name after the dot — filter keys and resolve
                    val filteredClassKeys = PyClassNameIndex.allKeys(project)
                        .filter { it.contains(term, ignoreCase = true) }
                    session.processKeys(filteredClassKeys, scopes.project, { true }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)
                    session.processKeys(filteredClassKeys, scopes.srforgeLib, { q -> q.startsWith("srforge.") }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)
                    session.processKeys(filteredClassKeys, scopes.libraries, { true }, buildFullEdges = true)

                    val filteredFuncKeys = PyFunctionNameIndex.allKeys(project)
                        .filter { it.contains(term, ignoreCase = true) }
                    session.processFunctionKeys(filteredFuncKeys, scopes.project, { true }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)
                    session.processFunctionKeys(filteredFuncKeys, scopes.srforgeLib, { q -> q.startsWith("srforge.") }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)
                    session.processFunctionKeys(filteredFuncKeys, scopes.libraries, { true }, buildFullEdges = true)
                } else {
                    // Term is empty (e.g. "srforge.dataset.") — scan .py files in the
                    // target package/module for classes/functions. Fast and targeted.
                    session.discoverDirectContent(scopes.project, LOCAL_BOOST)
                    session.discoverDirectContent(scopes.srforgeLib, LOCAL_BOOST)
                    session.discoverDirectContent(scopes.libraries)
                }

                // Suggest all discovered packages + modules (prefix matcher handles filtering)
                session.suggestAllPackages()
            } else {
                val allKeys = PyClassNameIndex.allKeys(project)
                val filteredKeys = session.filterKeys(allKeys, prefix)

                session.processKeys(filteredKeys, scopes.project, { true }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)
                session.processKeys(filteredKeys, scopes.srforgeLib, { q -> q.startsWith("srforge.") }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)

                val allFuncKeys = PyFunctionNameIndex.allKeys(project)
                val filteredFuncKeys = session.filterKeys(allFuncKeys, prefix)

                session.processFunctionKeys(filteredFuncKeys, scopes.project, { true }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)
                session.processFunctionKeys(filteredFuncKeys, scopes.srforgeLib, { q -> q.startsWith("srforge.") }, buildFullEdges = true, sourceBoost = LOCAL_BOOST)

                if (needle.isNotEmpty()) {
                    // Discover library packages for root package suggestions + re-export detection
                    session.discoverPackageEdges(scopes.libraries)

                    // Search library classes (AdamW, Module, etc.) — require startsWith match
                    if (needle.length >= 3) {
                        val libClassKeys = allKeys.asSequence()
                            .filter { it.startsWith(term, ignoreCase = true) }
                            .toList()
                        session.processKeys(libClassKeys, scopes.libraries, { true }, buildFullEdges = true)
                    }
                    // Don't search library functions in non-dotted mode — too noisy
                }

                when {
                    needle.isEmpty() ->
                        session.suggestChildPackages(base = "", partial = null)

                    needle.isNotEmpty() -> {
                        val roots = session.matchingRoots(needle)
                        for (root in roots) {
                            session.suggestPackage(root, root)
                            session.suggestChildPackages(base = root, partial = null)
                        }
                    }
                }
            }
        }

        for (el in session.batch) out.addElement(el)
        // Only restart on prefix change for non-dotted case.
        // For dotted case IntelliJ filters the existing list — avoids expensive re-scans.
        if (!isDotted) out.restartCompletionOnAnyPrefixChange()
    }

    private fun buildSorter(): CompletionSorter =
        CompletionSorter.emptySorter()
            .weigh(object : LookupElementWeigher("typeFirst") {
                override fun weigh(element: LookupElement): Comparable<*> =
                    element.getUserData(TYPE_KEY) ?: 1
            })
            .weigh(object : LookupElementWeigher("rank") {
                override fun weigh(element: LookupElement): Comparable<*> =
                    -(element.getUserData(PRIORITY_KEY) ?: 0.0)
            })

    private data class Scopes(
        val project: GlobalSearchScope,
        val libraries: GlobalSearchScope,
        val srforgeLib: GlobalSearchScope
    )

    private fun createScopes(project: Project): Scopes {
        val projectScope = ProjectScope.getProjectScope(project)
        val librariesScope = ProjectScope.getLibrariesScope(project)
        val srforgeLibScope = object : GlobalSearchScope(project) {
            override fun contains(file: VirtualFile): Boolean {
                if (!librariesScope.contains(file)) return false
                val p = file.path
                return p.contains("/srforge/", ignoreCase = true) || p.contains("\\srforge\\", ignoreCase = true)
            }
            override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = false
            override fun isSearchInLibraries() = true
            override fun getDisplayName() = "srforgeLibScope"
        }
        return Scopes(projectScope, librariesScope, srforgeLibScope)
    }

    /** Holds mutable state for a single completion invocation. */
    private inner class CompletionSession(
        private val project: Project,
        private val scopes: Scopes,
        private val term: String,
        private val needle: String,
        private val isDotted: Boolean
    ) {
        val batch = ArrayList<LookupElement>(256)
        private val seenClasses = HashSet<String>()
        private val seenFunctions = HashSet<String>()
        private val seenPackages = HashSet<String>()
        private val packageEdges = HashMap<String, MutableSet<String>>()
        private val packageInitFiles = HashMap<String, VirtualFile>()
        private val reExportCache = HashMap<String, Set<String>>()
        private val termLower = term.lowercase()

        fun filterKeys(allKeys: Collection<String>, prefix: String): Collection<String> = when {
            isDotted -> allKeys
            needle.isNotEmpty() -> {
                val t = termLower
                allKeys.asSequence().filter { it.contains(term, true) || it.lowercase().contains(t) }.toList()
            }
            else -> {
                if (prefix.isNotEmpty()) allKeys.asSequence().filter { it.startsWith(prefix, true) }.toList()
                else allKeys
            }
        }

        /** Discover Python packages by scanning __init__.py files in [scope].
         *  This is orders of magnitude faster than iterating the class name index
         *  because it only reads VFS paths — no PSI resolution needed. */
        fun discoverPackageEdges(scope: GlobalSearchScope) {
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val initFiles = FilenameIndex.getVirtualFilesByName("__init__.py", scope)
            for (f in initFiles) {
                ProgressManager.checkCanceled()
                val dir = f.parent ?: continue
                val root = fileIndex.getSourceRootForFile(f)
                    ?: fileIndex.getClassRootForFile(f)
                    ?: continue
                val relativePath = VfsUtilCore.getRelativePath(dir, root) ?: continue
                if (relativePath.isEmpty()) continue
                val parts = relativePath.replace('\\', '/').split('/')
                val pkgFqn = parts.joinToString(".")
                packageInitFiles.putIfAbsent(pkgFqn, f)
                for (i in parts.indices) {
                    val base = if (i == 0) "" else parts.subList(0, i).joinToString(".")
                    addPackageEdge(base, parts[i])
                }
            }
        }

        /** Discover .py module files in the package matching the current prefix
         *  and add them as navigable edges (like packages but for module files).
         *  E.g. for needle "srforge.dataset.laz", discovers modules in "srforge.dataset". */
        fun discoverModulesInPrefix(scope: GlobalSearchScope) {
            val prefixPkg = needle.substringBeforeLast('.', "")
            if (prefixPkg.isEmpty()) return

            val pkgPath = prefixPkg.replace('.', '/')
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val initFiles = FilenameIndex.getVirtualFilesByName("__init__.py", scope)

            for (initFile in initFiles) {
                ProgressManager.checkCanceled()
                val dir = initFile.parent ?: continue
                val root = fileIndex.getSourceRootForFile(initFile)
                    ?: fileIndex.getClassRootForFile(initFile)
                    ?: continue
                val relativePath = VfsUtilCore.getRelativePath(dir, root)?.replace('\\', '/') ?: continue
                if (relativePath != pkgPath) continue

                // Found the package — add .py files as module edges
                for (child in dir.children) {
                    if (child.isDirectory || !child.name.endsWith(".py")) continue
                    if (child.name == "__init__.py") continue
                    val moduleName = child.name.removeSuffix(".py")
                    addPackageEdge(prefixPkg, moduleName)
                }
            }
        }

        /** Scan .py files for top-level classes/functions in the target indicated by [needle].
         *  Handles both package directories and module files. */
        fun discoverDirectContent(scope: GlobalSearchScope, sourceBoost: Double = 0.0) {
            val pkgFqn = needle.trimEnd('.')
            if (pkgFqn.isEmpty()) return

            val pkgPath = pkgFqn.replace('.', '/')
            val parentPath = pkgPath.substringBeforeLast('/', "")
            val lastSegment = pkgPath.substringAfterLast('/')
            val fileIndex = ProjectRootManager.getInstance(project).fileIndex
            val psiManager = PsiManager.getInstance(project)
            val initFiles = FilenameIndex.getVirtualFilesByName("__init__.py", scope)

            for (initFile in initFiles) {
                ProgressManager.checkCanceled()
                val dir = initFile.parent ?: continue
                val root = fileIndex.getSourceRootForFile(initFile)
                    ?: fileIndex.getClassRootForFile(initFile)
                    ?: continue
                val relativePath = VfsUtilCore.getRelativePath(dir, root)?.replace('\\', '/') ?: continue

                when (relativePath) {
                    // Target is a package directory — scan all .py files in it
                    pkgPath -> {
                        for (child in dir.children) {
                            if (child.isDirectory || !child.name.endsWith(".py")) continue
                            ProgressManager.checkCanceled()
                            scanPsiFileForContent(psiManager.findFile(child) ?: continue, sourceBoost)
                        }
                    }
                    // Target might be a module file inside this parent package
                    parentPath -> {
                        val moduleFile = dir.findChild("$lastSegment.py") ?: continue
                        scanPsiFileForContent(psiManager.findFile(moduleFile) ?: continue, sourceBoost)
                    }
                }
            }
        }

        private fun scanPsiFileForContent(psiFile: com.intellij.psi.PsiFile, sourceBoost: Double = 0.0) {
            for (cls in PsiTreeUtil.getChildrenOfType(psiFile, PyClass::class.java).orEmpty()) {
                val qualified = cls.qualifiedName ?: continue
                addClassSuggestion(qualified, sourceBoost)
            }
            for (func in PsiTreeUtil.getChildrenOfType(psiFile, PyFunction::class.java).orEmpty()) {
                if (func.containingClass != null) continue
                val qualified = func.qualifiedName ?: continue
                addFunctionSuggestion(qualified, sourceBoost)
            }
        }

        /** Add all discovered packages to the batch.
         *  The prefix matcher on the result set handles filtering. */
        fun suggestAllPackages() {
            for ((base, children) in packageEdges) {
                for (seg in children.sorted()) {
                    val pkgFqn = if (base.isEmpty()) seg else "$base.$seg"
                    suggestPackage(pkgFqn, seg)
                }
            }
        }

        fun processKeys(
            keys: Collection<String>,
            scope: GlobalSearchScope,
            acceptQualified: (String) -> Boolean,
            buildFullEdges: Boolean,
            sourceBoost: Double = 0.0
        ) {
            for (key in keys) {
                ProgressManager.checkCanceled()
                val classes = PyClassNameIndex.find(key, project, scope)
                if (classes.isEmpty()) continue
                for (cls in classes) {
                    val qualified = cls.qualifiedName ?: continue
                    if (!acceptQualified(qualified)) continue
                    if (isDotted && needle.isNotEmpty() && !qualified.lowercase().startsWith(needle.lowercase())) continue

                    val parts = qualified.split('.')
                    if (parts.size >= 2) addPackageEdge("", parts[0])

                    if (buildFullEdges) {
                        val lastIdx = parts.size - 1
                        for (i in 0 until lastIdx) {
                            val base = if (i == 0) "" else parts.subList(0, i).joinToString(".")
                            addPackageEdge(base, parts[i])
                        }
                        addClassSuggestion(qualified, sourceBoost)
                    }
                }
            }
        }

        fun matchingRoots(needle: String): List<String> =
            packageEdges[""].orEmpty()
                .filter { it.startsWith(needle, ignoreCase = true) }
                .sorted()

        fun suggestPackage(pkgFqn: String, seg: String) {
            if (!seenPackages.add(pkgFqn)) return
            val el = LookupElementBuilder.create(pkgFqn)
                .withPresentableText(seg)
                .withTypeText("(package)", true)
                .withCaseSensitivity(false)
                .withIcon(AllIcons.Nodes.Package)
                .withInsertHandler(packageInsertHandler)
            el.putUserData(PRIORITY_KEY, matchPriority(pkgFqn, seg))
            el.putUserData(TYPE_KEY, 0)
            batch.add(el)
        }

        fun suggestChildPackages(base: String, partial: String?) {
            for (seg in packageEdges[base].orEmpty().sorted()) {
                if (!partial.isNullOrEmpty() && !seg.startsWith(partial, ignoreCase = true)) continue
                val pkgFqn = if (base.isEmpty()) seg else "$base.$seg"
                suggestPackage(pkgFqn, seg)
            }
        }

        private fun addPackageEdge(base: String, seg: String) {
            val fqn = if (base.isEmpty()) seg else "$base.$seg"
            if (isJunkPath(fqn)) return
            packageEdges.computeIfAbsent(base) { hashSetOf() }.add(seg)
        }

        /** Filter out build artifacts, internal packages, and other noise. */
        private fun isJunkPath(qualified: String): Boolean {
            // Build artifacts (setup.py build creates build/lib/ mirrors)
            if (qualified.startsWith("build.") || qualified.contains(".build.lib.")) return true
            // Python internals and packaging tools
            val root = qualified.substringBefore('.')
            if (root in JUNK_ROOTS) return true
            return false
        }

        /** Get the names re-exported by a package's __init__.py (cached per session). */
        private fun getReExportedNames(pkgFqn: String): Set<String> {
            return reExportCache.getOrPut(pkgFqn) {
                val initVFile = packageInitFiles[pkgFqn] ?: return@getOrPut emptySet()
                val psiManager = PsiManager.getInstance(project)
                val psiFile = psiManager.findFile(initVFile) ?: return@getOrPut emptySet()
                val names = HashSet<String>()
                for (stmt in PsiTreeUtil.getChildrenOfType(psiFile, PyFromImportStatement::class.java).orEmpty()) {
                    if (stmt.isStarImport) continue
                    for (element in stmt.importElements) {
                        val name = element.visibleName ?: continue
                        names.add(name)
                    }
                }
                names
            }
        }

        /** If the parent package re-exports this name via __init__.py, return the shorter path.
         *  E.g. "torch.optim.adamw.AdamW" → "torch.optim.AdamW" when torch.optim.__init__
         *  has `from .adamw import AdamW`. */
        private fun preferredQualifiedName(canonicalQn: String): String {
            val parts = canonicalQn.split('.')
            if (parts.size < 3) return canonicalQn

            val simpleName = parts.last()
            val parentPkg = parts.subList(0, parts.size - 2).joinToString(".")
            if (parentPkg.isEmpty()) return canonicalQn

            val shortened = "$parentPkg.$simpleName"
            // Only shorten if the result still matches the user's current navigation prefix
            if (isDotted && needle.isNotEmpty()
                && !shortened.lowercase().startsWith(needle.lowercase())) {
                return canonicalQn
            }

            val reExported = getReExportedNames(parentPkg)
            return if (simpleName in reExported) shortened else canonicalQn
        }

        private fun addClassSuggestion(qualified: String, sourceBoost: Double = 0.0) {
            if (isJunkPath(qualified)) return
            val preferred = preferredQualifiedName(qualified)
            if (!seenClasses.add(preferred)) return
            val simple = preferred.substringAfterLast('.')
            val tail = preferred.substringBeforeLast('.', "")
            val el = LookupElementBuilder.create(preferred)
                .withPresentableText(simple)
                .withTypeText(tail, true)
                .withCaseSensitivity(false)
                .withIcon(AllIcons.Nodes.Class)
                .withInsertHandler(classInsertHandler)
            el.putUserData(PRIORITY_KEY, matchPriority(preferred, simple) + sourceBoost)
            el.putUserData(TYPE_KEY, 1)
            batch.add(el)
        }

        fun processFunctionKeys(
            keys: Collection<String>,
            scope: GlobalSearchScope,
            acceptQualified: (String) -> Boolean,
            buildFullEdges: Boolean,
            sourceBoost: Double = 0.0
        ) {
            for (key in keys) {
                ProgressManager.checkCanceled()
                val functions = PyFunctionNameIndex.find(key, project, scope)
                if (functions.isEmpty()) continue
                for (func in functions) {
                    if (func.containingClass != null) continue
                    val qualified = func.qualifiedName ?: continue
                    if (!acceptQualified(qualified)) continue
                    if (isDotted && needle.isNotEmpty() && !qualified.lowercase().startsWith(needle.lowercase())) continue

                    val parts = qualified.split('.')
                    if (parts.size >= 2) addPackageEdge("", parts[0])

                    if (buildFullEdges) {
                        val lastIdx = parts.size - 1
                        for (i in 0 until lastIdx) {
                            val base = if (i == 0) "" else parts.subList(0, i).joinToString(".")
                            addPackageEdge(base, parts[i])
                        }
                        addFunctionSuggestion(qualified, sourceBoost)
                    }
                }
            }
        }

        private fun addFunctionSuggestion(qualified: String, sourceBoost: Double = 0.0) {
            if (isJunkPath(qualified)) return
            val preferred = preferredQualifiedName(qualified)
            if (!seenFunctions.add(preferred)) return
            val simple = preferred.substringAfterLast('.')
            val tail = preferred.substringBeforeLast('.', "")
            val el = LookupElementBuilder.create(preferred)
                .withPresentableText(simple)
                .withTypeText(tail, true)
                .withCaseSensitivity(false)
                .withIcon(AllIcons.Nodes.Function)
                .withInsertHandler(classInsertHandler)
            el.putUserData(PRIORITY_KEY, matchPriority(preferred, simple) + sourceBoost)
            el.putUserData(TYPE_KEY, 2)
            batch.add(el)
        }

        private fun matchPriority(qualified: String, segment: String): Double {
            if (term.isEmpty()) return 0.0
            val qLower = qualified.lowercase()
            val sLower = segment.lowercase()
            val startsCS = segment.startsWith(term)
            val startsCI = !startsCS && sLower.startsWith(termLower)
            val containsCS = !startsCS && !startsCI && (segment.contains(term) || qualified.contains(term))
            val containsCI = !startsCS && !startsCI && !containsCS && (sLower.contains(termLower) || qLower.contains(termLower))
            val base = when {
                startsCS -> 400.0
                startsCI -> 300.0
                containsCS -> 200.0
                containsCI -> 100.0
                else -> 0.0
            }
            return base - (segment.length / 1000.0)
        }
    }

    // Insert handlers

    private val packageInsertHandler = InsertHandler<LookupElement> { ctx, item ->
        val full = item.lookupString
        val replaced = replaceWholeYamlScalar(ctx, full)
        if (replaced) {
            val doc = ctx.document
            val tail = ctx.editor.caretModel.offset
            if (!(tail < doc.textLength && doc.charsSequence[tail] == '.')) {
                doc.insertString(tail, ".")
                ctx.editor.caretModel.moveToOffset(tail + 1)
            }
            AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
            ctx.setAddCompletionChar(false)
        }
    }

    private val classInsertHandler = InsertHandler<LookupElement> { ctx, item ->
        val full = item.lookupString
        val replaced = replaceWholeYamlScalar(ctx, full)
        if (replaced) ctx.setAddCompletionChar(false)
    }

    private fun replaceWholeYamlScalar(ctx: InsertionContext, text: String): Boolean {
        val file = ctx.file
        val caret = ctx.startOffset
        val element: PsiElement? = file.findElementAt(caret) ?: if (caret > 0) file.findElementAt(caret - 1) else null
        val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return false
        val r = scalar.textRange
        val t = scalar.text
        val quoted = t.length >= 2 && (
                (t.first() == '"' && t.last() == '"') ||
                        (t.first() == '\'' && t.last() == '\'')
                )
        val start = if (quoted) r.startOffset + 1 else r.startOffset
        val end = if (quoted) r.endOffset - 1 else r.endOffset
        val doc = ctx.document
        doc.replaceString(start, end, text)
        ctx.editor.caretModel.moveToOffset(start + text.length)
        return true
    }

    companion object {
        private val PRIORITY_KEY: Key<Double> = Key.create("srforge.yamlTargetPriority")
        private val TYPE_KEY: Key<Int> = Key.create("srforge.yamlTargetType")

        /** Priority boost for project-local and srforge items over third-party libraries. */
        private const val LOCAL_BOOST = 1000.0

        /** Root package names that should never appear in _target: suggestions. */
        private val JUNK_ROOTS = setOf(
            "build", "dist", "egg", "install",
            "pip", "setuptools", "_distutils_hack", "distutils", "pkg_resources",
            "IPython", "ipykernel", "ipywidgets", "jupyter", "jupyter_client",
            "jupyter_core", "notebook", "nbformat", "nbconvert", "traitlets",
            "pygments", "pydevd", "debugpy", "_pydevd_bundle",
            "test", "tests", "_pytest", "pytest"
        )

        fun stripDummy(s: String): String = s
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace(CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionInitializationContext.DUMMY_IDENTIFIER, "")
            .trim()
    }
}
