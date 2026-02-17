package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.stubs.PyClassNameIndex
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
        result.stopHere()

        val out = result.withRelevanceSorter(buildSorter())

        val raw = scalar.textValue.orEmpty()
        val needle = stripDummy(raw)
        val prefix = CompletionUtil.findReferenceOrAlphanumericPrefix(parameters)

        val isDotted = needle.contains('.')
        val term = when {
            needle.isEmpty() -> prefix
            isDotted -> needle.substringAfterLast('.', "")
            else -> needle
        }

        val scopes = createScopes(project)
        val session = CompletionSession(project, scopes, term, needle, isDotted)

        ReadAction.run<RuntimeException> {
            val allKeys = PyClassNameIndex.allKeys(project)
            val filteredKeys = session.filterKeys(allKeys, prefix)

            session.processKeys(filteredKeys, scopes.project, { true }, buildFullEdges = true)
            session.processKeys(filteredKeys, scopes.srforgeLib, { q -> q.startsWith("srforge.") }, buildFullEdges = true)

            when {
                !isDotted && needle.isEmpty() ->
                    session.suggestChildPackages(base = "", partial = null)

                !isDotted && needle.isNotEmpty() -> {
                    var roots = session.matchingRoots(needle)
                    if (roots.isEmpty()) {
                        session.buildRootEdgesFast(allKeys, scopes.project, 1500)
                        session.buildRootEdgesFast(allKeys, scopes.srforgeLib, 1500)
                        roots = session.matchingRoots(needle)
                    }
                    for (root in roots) {
                        session.suggestPackage(root, root)
                        session.suggestChildPackages(base = root, partial = null)
                    }
                }

                isDotted -> {
                    val lastDot = needle.lastIndexOf('.')
                    val base = needle.substring(0, lastDot)
                    val partial = needle.substring(lastDot + 1)
                    session.suggestChildPackages(base, partial.ifEmpty { null })
                }
            }
        }

        for (el in session.batch) out.addElement(el)
        out.restartCompletionOnAnyPrefixChange()
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
        private val seenPackages = HashSet<String>()
        private val packageEdges = HashMap<String, MutableSet<String>>()
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

        fun processKeys(
            keys: Collection<String>,
            scope: GlobalSearchScope,
            acceptQualified: (String) -> Boolean,
            buildFullEdges: Boolean
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
                        addClassSuggestion(qualified)
                    }
                }
            }
        }

        fun buildRootEdgesFast(allKeys: Collection<String>, scope: GlobalSearchScope, keyLimit: Int) {
            var scanned = 0
            for (key in allKeys) {
                if (scanned++ >= keyLimit) break
                ProgressManager.checkCanceled()
                val classes = PyClassNameIndex.find(key, project, scope)
                if (classes.isEmpty()) continue
                for (cls in classes) {
                    val q = cls.qualifiedName ?: continue
                    val parts = q.split('.')
                    if (parts.size >= 2) addPackageEdge("", parts[0])
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
            packageEdges.computeIfAbsent(base) { hashSetOf() }.add(seg)
        }

        private fun addClassSuggestion(qualified: String) {
            if (!seenClasses.add(qualified)) return
            val simple = qualified.substringAfterLast('.')
            val tail = qualified.substringBeforeLast('.', "")
            val el = LookupElementBuilder.create(qualified)
                .withPresentableText(simple)
                .withTypeText(tail, true)
                .withCaseSensitivity(false)
                .withIcon(AllIcons.Nodes.Class)
                .withInsertHandler(classInsertHandler)
            el.putUserData(PRIORITY_KEY, matchPriority(qualified, simple))
            el.putUserData(TYPE_KEY, 1)
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

        fun stripDummy(s: String): String = s
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace(CompletionInitializationContext.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionInitializationContext.DUMMY_IDENTIFIER, "")
            .trim()
    }
}
