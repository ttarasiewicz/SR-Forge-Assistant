package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

class YamlTargetCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val project = parameters.editor.project ?: return
        if (DumbService.isDumb(project)) return

        // Only inside YAML _target value
        val scalar = PsiTreeUtil.getParentOfType(parameters.position, YAMLScalar::class.java, false) ?: return
        val kv = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue::class.java, false) ?: return
        if (kv.keyText != "_target") return
        result.stopHere()

        // Sort: packages first, then rank
        val sorter = CompletionSorter.emptySorter()
            .weigh(object : LookupElementWeigher("typeFirst") {
                override fun weigh(element: LookupElement): Comparable<*> = element.getUserData(TYPE_KEY) ?: 1 // 0 pkg, 1 class
            })
            .weigh(object : LookupElementWeigher("rank") {
                override fun weigh(element: LookupElement): Comparable<*> = -(element.getUserData(PRIORITY_KEY) ?: 0.0)
            })
        val out = result.withRelevanceSorter(sorter)
        val batch = ArrayList<LookupElement>(256)  // collect here; add after compute

        // Current text/state
        fun stripDummy(s: String) = s
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace(CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionInitializationContext.DUMMY_IDENTIFIER, "")
            .trim()

        val raw = scalar.textValue.orEmpty()
        val needle = stripDummy(raw)
        val prefix = CompletionUtil.findReferenceOrAlphanumericPrefix(parameters)

        val isDotted = needle.contains('.')
        val lastSeg = needle.substringAfterLast('.', "")
        val useContains = needle.isNotEmpty() && !isDotted

        val term = when {
            needle.isEmpty() -> prefix
            isDotted -> lastSeg
            else -> needle
        }
        val termLower = term.lowercase()

        // Scopes
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

        // Ranking
        fun classPriority(qualified: String, simple: String): Double {
            if (term.isEmpty()) return 0.0
            val qLower = qualified.lowercase()
            val sLower = simple.lowercase()
            val startsCS   = simple.startsWith(term)
            val startsCI   = !startsCS && sLower.startsWith(termLower)
            val containsCS = !startsCS && !startsCI && (simple.contains(term) || qualified.contains(term))
            val containsCI = !startsCS && !startsCI && !containsCS && (sLower.contains(termLower) || qLower.contains(termLower))
            val base = when {
                startsCS   -> 400.0
                startsCI   -> 300.0
                containsCS -> 200.0
                containsCI -> 100.0
                else       -> 0.0
            }
            return base - (simple.length / 1000.0)
        }

        fun packagePriority(pkgFqn: String, segment: String): Double {
            if (term.isEmpty()) return 0.0
            val qLower = pkgFqn.lowercase()
            val sLower = segment.lowercase()
            val startsCS   = segment.startsWith(term)
            val startsCI   = !startsCS && sLower.startsWith(termLower)
            val containsCS = !startsCS && !startsCI && (segment.contains(term) || pkgFqn.contains(term))
            val containsCI = !startsCS && !startsCI && !containsCS && (sLower.contains(termLower) || qLower.contains(termLower))
            val base = when {
                startsCS   -> 400.0
                startsCI   -> 300.0
                containsCS -> 200.0
                containsCI -> 100.0
                else       -> 0.0
            }
            return base - (segment.length / 1000.0)
        }

        ReadAction.run<RuntimeException> {
            val allKeys: Collection<String> = PyClassNameIndex.allKeys(project)

            // Data structures
            val seenClasses = HashSet<String>()
            val seenPackages = HashSet<String>()
            // True package graph: base -> child segment (only if that segment has something after it)
            val packageEdges = HashMap<String, MutableSet<String>>()

            fun addPackageEdge(base: String, seg: String) {
                packageEdges.computeIfAbsent(base) { hashSetOf() }.add(seg)
            }

            fun addClassSuggestion(qualified: String) {
                if (!seenClasses.add(qualified)) return
                val simple = qualified.substringAfterLast('.')
                val tail = qualified.substringBeforeLast('.', "")
                val el = LookupElementBuilder.create(qualified)
                    .withPresentableText(simple)
                    .withTypeText(tail, true)
                    .withCaseSensitivity(false)
                    .withIcon(AllIcons.Nodes.Class)
                    .withInsertHandler(classInsertHandler)
                el.putUserData(PRIORITY_KEY, classPriority(qualified, simple))
                el.putUserData(TYPE_KEY, 1) // class
                batch.add(el)
            }

            fun processKeys(
                keys: Collection<String>,
                scope: GlobalSearchScope,
                acceptQualified: (String) -> Boolean,
                buildFullEdges: Boolean
            ) {
                for (key in keys) {
                    ProgressManager.checkCanceled()
                    val classes: Collection<PyClass> = PyClassNameIndex.find(key, project, scope)
                    if (classes.isEmpty()) continue
                    for (cls in classes) {
                        val qualified = cls.qualifiedName ?: continue
                        if (!acceptQualified(qualified)) continue
                        // Dotted input: only classes under the typed prefix
                        if (isDotted && needle.isNotEmpty() && !qualified.lowercase().startsWith(needle.lowercase())) continue

                        val parts = qualified.split('.')
                        val lastIdx = parts.size - 1
                        // Root edges "" -> parts[0] if there is a dot
                        if (parts.size >= 2) addPackageEdge("", parts[0])
                        // Full edges for package graph (only when requested)
                        if (buildFullEdges) {
                            for (i in 0 until lastIdx) {
                                val base = if (i == 0) "" else parts.subList(0, i).joinToString(".")
                                val seg = parts[i]
                                addPackageEdge(base, seg)
                            }
                        }

                        if (buildFullEdges) {
                            addClassSuggestion(qualified)
                        }
                    }
                }
            }

            // Choose keys for main pass
            val filteredKeys: Collection<String> = when {
                // Dotted: do NOT prefilter by simple names (we need the full package graph)
                isDotted -> allKeys
                useContains -> {
                    val t = termLower
                    allKeys.asSequence().filter { it.contains(term, true) || it.lowercase().contains(t) }.toList()
                }
                else -> {
                    val p = prefix
                    if (p.isNotEmpty()) allKeys.asSequence().filter { it.startsWith(p, true) }.toList()
                    else allKeys
                }
            }

            // Main pass: build full graph + class suggestions from project + srforge libs
            processKeys(filteredKeys, projectScope, { true }, buildFullEdges = true)
            processKeys(filteredKeys, srforgeLibScope, { q -> q.startsWith("srforge.") }, buildFullEdges = true)

            // Helper to suggest package nodes with icon + ordering keys set
            fun suggestPackage(pkgFqn: String, seg: String) {
                if (!seenPackages.add(pkgFqn)) return
                val el = LookupElementBuilder.create(pkgFqn)
                    .withPresentableText(seg)
                    .withTypeText("(package)", true)
                    .withCaseSensitivity(false)
                    .withIcon(AllIcons.Nodes.Package)
                    .withInsertHandler(packageInsertHandler)
                el.putUserData(PRIORITY_KEY, packagePriority(pkgFqn, seg))
                el.putUserData(TYPE_KEY, 0) // package
                batch.add(el)
            }

            // Suggest child packages for base (optionally filtered by partial)
            fun suggestChildPackages(base: String, partial: String?) {
                val segs = packageEdges[base].orEmpty().sorted()
                for (seg in segs) {
                    if (!partial.isNullOrEmpty() && !seg.startsWith(partial, ignoreCase = true)) continue
                    val pkgFqn = if (base.isEmpty()) seg else "$base.$seg"
                    suggestPackage(pkgFqn, seg)
                }
            }

            when {
                // 1) empty input -> show top-level packages (roots)
                !isDotted && needle.isEmpty() -> {
                    suggestChildPackages(base = "", partial = null)
                }

                // 2) no dot but some text -> show ROOT itself (if matches) and its children.
                !isDotted && needle.isNotEmpty() -> {
                    var roots = packageEdges[""].orEmpty()
                        .filter { it.startsWith(needle, ignoreCase = true) }
                        .sorted()

                    // Fallback: if no roots yet (e.g., typing "srf"), build root edges quickly (no class additions)
                    if (roots.isEmpty()) {
                        buildRootEdgesFast(allKeys, projectScope, packageEdges, project, 1500)
                        buildRootEdgesFast(allKeys, srforgeLibScope, packageEdges, project, 1500)
                        roots = packageEdges[""].orEmpty()
                            .filter { it.startsWith(needle, ignoreCase = true) }
                            .sorted()
                    }

                    for (root in roots) {
                        // Suggest the ROOT itself so it doesn't disappear while typing
                        suggestPackage(root, root)
                        // And its direct children
                        suggestChildPackages(base = root, partial = null)
                    }
                }

                // 3) dotted -> base + partial (works for 'srforge.' and 'srforge.models.SI')
                isDotted -> {
                    val lastDot = needle.lastIndexOf('.')
                    val base = needle.substring(0, lastDot)
                    val partial = needle.substring(lastDot + 1)
                    suggestChildPackages(base, partial.ifEmpty { null })
                }
            }
        }
        for (el in batch) out.addElement(el)
//        if (parameters.invocationCount > 0) out.stopHere()
        out.restartCompletionOnAnyPrefixChange()
    }

    /** Fast pass to build ONLY root edges ("" -> first segment) within a cap of keys. */
    private fun buildRootEdgesFast(
        allKeys: Collection<String>,
        scope: GlobalSearchScope,
        packageEdges: MutableMap<String, MutableSet<String>>,
        project: com.intellij.openapi.project.Project,
        keyLimit: Int
    ) {
        var scanned = 0
        for (key in allKeys) {
            if (scanned++ >= keyLimit) break
            ProgressManager.checkCanceled()
            val classes: Collection<PyClass> = PyClassNameIndex.find(key, project, scope)
            if (classes.isEmpty()) continue
            for (cls in classes) {
                val q = cls.qualifiedName ?: continue
                val parts = q.split('.')
                if (parts.size >= 2) {
                    packageEdges.computeIfAbsent("") { hashSetOf() }.add(parts[0])
                }
            }
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

    // Replace entire YAML scalar (respect quotes)
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
        private val TYPE_KEY: Key<Int> = Key.create("srforge.yamlTargetType") // 0 package, 1 class
    }
}
