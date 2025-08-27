package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ui.EmptyIcon
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.types.TypeEvalContext
import javax.swing.Icon

/** Documentation target for a resolved PyClass. */
class PyClassDocTarget(pyClass: PyClass) : DocumentationTarget {
    private val project = pyClass.project
    private val ptr = com.intellij.psi.SmartPointerManager.getInstance(project).createSmartPsiElementPointer(pyClass)
    val element: PyClass? get() = ptr.element

    override fun createPointer(): Pointer<out DocumentationTarget> =
        Pointer { ptr.element?.let { PyClassDocTarget(it) } }

    override fun computePresentation(): TargetPresentation {
        val element = ptr.element
        val qn = element?.qualifiedName ?: element?.name ?: "<class>"
        val icon: Icon = element?.getIcon(0) ?: EmptyIcon.ICON_16
        return TargetPresentation.builder(qn).icon(icon).presentation()
    }

    override fun computeDocumentation(): DocumentationResult? {
        val cls = ptr.element ?: return null

        // Build our propagated __init__ parameters summary (child → parents that are actually forwarded)
        val paramsHtml = buildPropagatedInitParamsGroupedBlock(cls)  // may be null


        // Use the language-scoped Python provider for rich docs
        val providerHtml = try {
            val provider: DocumentationProvider = LanguageDocumentation.INSTANCE.forLanguage(cls.language)
            provider.generateDoc(cls, cls) ?: provider.generateHoverDoc(cls, cls)
        } catch (_: Throwable) {
            null
        }

        val selfLink = selfSourceLink(cls)
        if (!providerHtml.isNullOrBlank()) {
            val combined = selfLink + (paramsHtml ?: "") + providerHtml
            return DocumentationResult.documentation(combined)
        }

        // Fallback: minimal header + our params + docstring
        val header = buildHeader(cls)
        val doc = (cls.docStringExpression as? PyStringLiteralExpression)?.stringValue
            ?.takeIf { it.isNotBlank() } ?: "<i>No docstring found.</i>"
        val modulePath = cls.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")

        val html = buildString {
            append(DocumentationMarkup.DEFINITION_START)
//            append("<code>").append(escape(header)).append("</code>")
            val qn = cls.qualifiedName
            if (qn != null) {
                append("<code><a href=\"py-src:").append(escape(qn)).append("\">")
                append(escape(header)).append("</a></code>")
            } else {
                append("<code>").append(escape(header)).append("</code>")
            }
            append(DocumentationMarkup.DEFINITION_END)

            if (paramsHtml != null) append(paramsHtml)

            append(DocumentationMarkup.CONTENT_START)
            append(escapePreservingBasicMarkup(doc))
            append(DocumentationMarkup.CONTENT_END)

            if (!modulePath.isNullOrBlank()) {
                append(DocumentationMarkup.SECTIONS_START)
                append(sectionRow("Module:", modulePath))
                append(DocumentationMarkup.SECTIONS_END)
            }
        }
        return DocumentationResult.documentation(html)
    }

    // ---------- propagated __init__ parameter analysis ----------

    private data class ParamInfo(
        val name: String,
        val typeText: String = "Any",
        val defaultText: String? = null
    )

    private data class ForwardBag(
        val passAll: Boolean,          // does this level forward **kwargs upward?
        val names: Set<String>         // explicit keywords forwarded upward at this level
    ) {
        fun isEmpty() = !passAll && names.isEmpty()
    }

    /** Grouped Parameters block: own __init__ params + only those parent params that are actually forwarded.
     *  Sections are ordered: ThisClass, ParentClass, BaseClass, ...  */
    private fun buildPropagatedInitParamsGroupedBlock(cls: PyClass): String? {
        val ctx = TypeEvalContext.codeAnalysis(cls.project, cls.containingFile)

        // Find this class' own __init__/__new__
        val ownInit: PyFunction = (cls.findInitOrNew(false, ctx) ?: cls.findInitOrNew(true, ctx)) ?: return null

        // sections: className -> (paramName -> ParamInfo) preserving insertion order
        val sections = LinkedHashMap<PyClass, LinkedHashMap<String, ParamInfo>>()
        val seenNames = LinkedHashSet<String>() // prevent duplicate names; child wins

        fun sectionFor(klass: PyClass): LinkedHashMap<String, ParamInfo> =
            sections.getOrPut(klass) { LinkedHashMap() }

        // 1) Own params (excluding self/*args/**kwargs)
        ownInit.parameterList.parameters
            .filterIsInstance<PyNamedParameter>()
            .filter { !it.isSelf && !it.isPositionalContainer && !it.isKeywordContainer }
            .forEach { p ->
                val info = p.toParamInfo()
                if (info.name.isNotBlank() && seenNames.add(info.name)) {
                    sectionFor(cls)[info.name] = info
                }
            }

        // 2) Propagation bag from this class' super().__init__(...)
        var bag = analyzeSuperForwarding(ownInit)

        // 3) Walk up the MRO while something is being forwarded
        var current: PyClass? = cls
        while (!bag.isEmpty()) {
            val parent = nextClassWithInit(current, ctx) ?: break
            val parentInit = (parent.findInitOrNew(false, ctx) ?: parent.findInitOrNew(true, ctx)) ?: break

            val parentNamedParams = parentInit.parameterList.parameters
                .filterIsInstance<PyNamedParameter>()
                .filter { !it.isSelf && !it.isPositionalContainer && !it.isKeywordContainer }

            val targetSection = sectionFor(parent)

            if (bag.passAll) {
                // include all parent's regular named params
                for (p in parentNamedParams) {
                    val name = p.name ?: continue
                    if (seenNames.add(name)) {
                        targetSection[name] = p.toParamInfo()
                    }
                }
            } else if (bag.names.isNotEmpty()) {
                // include only explicitly forwarded names
                val needed = bag.names
                for (p in parentNamedParams) {
                    val name = p.name ?: continue
                    if (name in needed && seenNames.add(name)) {
                        targetSection[name] = p.toParamInfo()
                    }
                }
            } else {
                break
            }

            // Update bag for next level, accounting for what this parent consumed and what it forwards further
            val consumed = parentNamedParams.mapNotNull { it.name }.toSet()
            val parentForward = analyzeSuperForwarding(parentInit)
            val remainingNames = (bag.names - consumed) + parentForward.names
            val nextPassAll = bag.passAll && parentForward.passAll
            bag = ForwardBag(nextPassAll, remainingNames)

            current = parent
        }

        if (sections.isEmpty()) return null
        return renderParamSections(sections)
    }

    private fun selfSourceLink(cls: PyClass): String {
        val qn = cls.qualifiedName ?: return ""
        val name = cls.name ?: qn
        val href = DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + "py-src:" + qn
        return buildString {
            append(DocumentationMarkup.CONTENT_START)
            append("<b><a href=\"").append(href).append("\">").append(escape(name)).append("</a></b>")
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** Render own params first, then grouped parents under "Inherited Parameters" with clickable parent names. */
    private fun renderParamSections(
        sections: LinkedHashMap<PyClass, LinkedHashMap<String, ParamInfo>>
    ): String {
        if (sections.isEmpty()) return ""

        // First entry = the current class
        val iter = sections.entries.iterator()
        val ownEntry = iter.next()
        val ownClass = ownEntry.key
        val ownParams = ownEntry.value

        // Parent sections that actually have params
        val parents = buildList {
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.value.isNotEmpty()) add(e.key to e.value)
            }
        }
        val hasInherited = parents.isNotEmpty()

        fun renderLine(p: ParamInfo): String = buildString {
            append("<code><b>").append(escape(p.name)).append("</b>: ")
            append(escape(p.typeText))
            if (!p.defaultText.isNullOrBlank()) append(" = ").append(escape(p.defaultText!!))
            append("</code>")
        }

        fun joinLines(values: Collection<ParamInfo>) =
            values.joinToString("<br/>") { renderLine(it) }

        val ownBlock = if (ownParams.isEmpty()) "<i>None</i>" else joinLines(ownParams.values)

        // Helper to build a clickable label for a given PyClass
        fun clickableClassLabel(klass: PyClass): String {
            val qn = klass.qualifiedName
            val label = qn ?: (klass.name ?: "<class>")  // show full path if available
            // keep using FQN in the link payload so the handler resolves it reliably
            val href = if (qn != null)
                DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + "py:" + qn
            else
            // fallback: no FQN (local/builtins) – link to source instead
                DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + "py-src:" + (klass.name ?: "<class>")

            fun esc(s: String) = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(s)
//            return """<a href="$href">${esc(label)}</a>"""
            return """<a href="$href" title="${esc(qn ?: label)}">${esc(klass.name ?: label)}</a>"""
        }

        val html = buildString {
            append(DocumentationMarkup.CONTENT_START)

            // Own
            append("<b>Parameters</b>")
            if (ownBlock.isNotEmpty()) append("<br/>").append(ownBlock)

            // Parents
            if (hasInherited) {
                append("<br/><b>Inherited Parameters</b>")
                for ((parentCls, params) in parents) {
                    val lines = if (params.isEmpty()) "" else joinLines(params.values)
                    append("<br/><span style='margin-left:8px'>")
                    append("<b>").append(clickableClassLabel(parentCls)).append("</b>")
                    if (lines.isNotEmpty()) append("<br/>").append(lines)
                    append("</span>")
                }
            }

            append(DocumentationMarkup.CONTENT_END)
        }
        return html
    }


    /** Find next superclass in MRO (excluding 'object') that defines or inherits an __init__. */
    private fun nextClassWithInit(cls: PyClass?, ctx: TypeEvalContext): PyClass? {
        val supers = cls?.getSuperClasses(ctx).orEmpty()
        for (sup in supers) {
            val hasInit = sup.findInitOrNew(false, ctx) ?: sup.findInitOrNew(true, ctx)
            if (hasInit != null) return sup
        }
        return null
    }

    /** Convert parameter to ParamInfo using annotation/default from code text (fast, no heavy resolve). */
    private fun PyNamedParameter.toParamInfo(): ParamInfo {
        val name = this.name ?: "<param>"
        val raw = this.text.trim()
        val colonIdx = raw.indexOf(':')
        val eqIdx = raw.indexOf('=')
        val typeText = when {
            colonIdx != -1 -> raw.substring(colonIdx + 1, if (eqIdx != -1) eqIdx else raw.length).trim().ifBlank { "Any" }
            else -> "Any"
        }
        val defaultText = if (eqIdx != -1) raw.substring(eqIdx + 1).trim().ifBlank { null } else null
        return ParamInfo(name, typeText, defaultText)
    }

    /** Analyze this function's body for a call like super().__init__(...) and return which names are forwarded upward. */
    private fun analyzeSuperForwarding(func: PyFunction): ForwardBag {
        var passAll = false
        val names = LinkedHashSet<String>()

        val calls = PsiTreeUtil.findChildrenOfType(func, PyCallExpression::class.java)
        for (call in calls) {
            val calleeQ = call.callee as? PyQualifiedExpression ?: continue
            if (calleeQ.referencedName != "__init__") continue
            val qual = calleeQ.qualifier as? PyCallExpression ?: continue
            val superCallee = (qual.callee as? PyReferenceExpression)?.referencedName ?: continue
            if (superCallee != "super") continue

            // Now we have super().__init__( ...args... )
            val args = call.argumentList?.arguments.orEmpty()
            for (arg in args) {
                when (arg) {
                    is PyKeywordArgument -> {
                        val kwName = arg.keyword
                        if (!kwName.isNullOrBlank()) names.add(kwName)
                    }
                    is PyStarArgument -> {
                        // **something
                        if (arg.isKeyword) passAll = true
                    }
                    else -> {
                        // handle '**kwargs' represented as plain expression? (fallback heuristic)
                        val txt = arg.text.trim()
                        if (txt.startsWith("**")) passAll = true
                    }
                }
            }
            // We can break on the first matching super().__init__, or merge if there are multiple.
            // We'll merge; keep scanning.
        }

        return ForwardBag(passAll, names)
    }


    /** Try to find the class's own __init__ (or inherited if necessary) and render a compact "Parameters" block. */
    private fun buildInitParamsBlock(cls: com.jetbrains.python.psi.PyClass): String? {
        val ctx = TypeEvalContext.codeAnalysis(cls.project, cls.containingFile)

        // Try this class' __init__/__new__; if missing, allow inherited
        val initOpt: PyFunction? = try {
            cls.findInitOrNew(false, ctx) ?: cls.findInitOrNew(true, ctx)
        } catch (_: Throwable) {
            null
        }

        // Make it non-null (or bail) before dereferencing
        val init: PyFunction = initOpt ?: return null

        val params = init.parameterList.parameters
            .filterIsInstance<PyNamedParameter>()
            .filter { it.name != "self" }  // skip self

        if (params.isEmpty()) return null

        val lines = params.map { formatParamLine(it) }
        val body = buildString {
            append("<b>Parameters</b><br/>")
            for (ln in lines) append(ln).append("<br/>")
        }

        return DocumentationMarkup.CONTENT_START + body + DocumentationMarkup.CONTENT_END
    }


    /** Render a single parameter: name: Type (= default) */
    private fun formatParamLine(p: PyNamedParameter): String {
        val raw = p.text.trim()  // e.g., "name: str = 0", "*args: int", "**kwargs"
        val name = p.name ?: raw.substringBefore(':', raw).substringBefore('=').trim().trimStart('*')

        // Detect vararg markers from raw text
        val varPrefix = when {
            raw.startsWith("**") -> "**"
            raw.startsWith("*")  -> "*"
            else                 -> ""
        }

        // Parse annotation and default directly from code text
        val colonIdx = raw.indexOf(':')
        val eqIdx = raw.indexOf('=')
        val typeText = when {
            colonIdx != -1 -> raw.substring(colonIdx + 1, if (eqIdx != -1) eqIdx else raw.length).trim()
            else           -> "Any"
        }.ifBlank { "Any" }

        val defaultText = if (eqIdx != -1) raw.substring(eqIdx + 1).trim() else null

        val sb = StringBuilder()
        sb.append("<code>")
        if (varPrefix.isNotEmpty()) sb.append(escape(varPrefix))
        sb.append("<b>").append(escape(name)).append("</b>")
        sb.append(": ").append(escape(typeText))
        if (!defaultText.isNullOrBlank()) {
            sb.append(" = ").append(escape(defaultText))
        }
        sb.append("</code>")
        return sb.toString()
    }

    private fun buildHeader(cls: com.jetbrains.python.psi.PyClass): String {
        val name = cls.name ?: "<class>"
        val bases: List<String> = try {
            val ctx = TypeEvalContext.codeAnalysis(cls.project, cls.containingFile)
            val supers = cls.getSuperClasses(ctx)
            if (!supers.isNullOrEmpty()) supers.mapNotNull { it.qualifiedName ?: it.name }
            else cls.superClassExpressions.map { it.text }
        } catch (_: Throwable) {
            cls.superClassExpressions.map { it.text }
        }
        return "class $name(${bases.joinToString(", ")})"
    }

    private fun escape(s: String): String =
        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(s)

    private fun escapePreservingBasicMarkup(s: String): String =
        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(s).replace("\n", "<br/>")

    private fun sectionRow(label: String, value: String): String =
        DocumentationMarkup.SECTION_HEADER_START + escape(label) + DocumentationMarkup.SECTION_SEPARATOR +
                escape(value) + DocumentationMarkup.SECTION_END



    override fun computeDocumentationHint(): String? {
        val element = ptr.element ?: return null
        val qn = element.qualifiedName ?: element.name ?: "class"
        return "<b>$qn</b>"
    }

    private fun buildFallbackHtml(cls: PyClass): String {
        val qn = cls.qualifiedName ?: cls.name ?: "class"
        val doc = cls.docStringExpression?.text ?: ""
        val safeDoc = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(doc)
        return """
            <html>
              <body>
                <h3>$qn</h3>
                ${if (safeDoc.isBlank()) "<p><i>No docstring found.</i></p>" else "<pre>$safeDoc</pre>"}
                <p><small>Provided by SR-Forge Assistant</small></p>
              </body>
            </html>
        """.trimIndent()
    }
}

/** Shown when `_target` points to a non-existing Python class. */
class NotFoundDocTarget(private val fqn: String) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer { NotFoundDocTarget(fqn) }
    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(fqn).presentation()

    override fun computeDocumentation(): DocumentationResult =
        DocumentationResult.documentation(
            """
            <html><body>
              <h3>$fqn</h3>
              <p><i>No Python class found for this fully-qualified name.</i></p>
              <p>Check your environment (project + libraries) or the spelling of the class.</p>
            </body></html>
            """.trimIndent()
        )

    override fun computeDocumentationHint(): String = "<b>$fqn</b> — not found"
}
