package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.types.TypeEvalContext

/** HTML rendering utilities for documentation popups. */
object DocHtmlRenderer {

    fun escape(s: String): String = StringUtil.escapeXmlEntities(s)

    fun escapePreservingNewlines(s: String): String = escape(s).replace("\n", "<br/>")

    fun sectionRow(label: String, value: String): String =
        DocumentationMarkup.SECTION_HEADER_START + escape(label) + DocumentationMarkup.SECTION_SEPARATOR +
                escape(value) + DocumentationMarkup.SECTION_END

    /** Build a clickable self-source link for a class. */
    fun buildSelfSourceLink(cls: PyClass): String {
        val qn = cls.qualifiedName ?: return ""
        val name = cls.name ?: qn
        val href = DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + "py-src:" + qn
        return buildString {
            append(DocumentationMarkup.CONTENT_START)
            append("<b><a href=\"").append(href).append("\">").append(escape(name)).append("</a></b>")
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** Build a header like "class Foo(Base1, Base2)". */
    fun buildClassHeader(cls: PyClass): String {
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

    /** Build a header like "def func_name(p1: T1, ...) -> ReturnType". */
    fun buildFunctionHeader(func: PyFunction): String {
        val name = func.name ?: "<function>"
        val params = func.parameterList.parameters
            .filter { p -> p.name?.let { it != "self" } ?: true }
            .joinToString(", ") { it.text.trim() }
        val retAnnotation = func.annotation?.text?.removePrefix("->")?.trim()
        return if (retAnnotation != null) "def $name($params) -> $retAnnotation"
        else "def $name($params)"
    }

    /** Build a clickable self-source link for a function. */
    fun buildSelfSourceLinkForFunction(func: PyFunction): String {
        val qn = func.qualifiedName ?: return ""
        val name = func.name ?: qn
        val href = DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + "py-src:" + qn
        return buildString {
            append(DocumentationMarkup.CONTENT_START)
            append("<b><a href=\"").append(href).append("\">").append(escape(name)).append("</a></b>")
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** Render a flat parameter list for a function (no inheritance sections). */
    fun renderFunctionParams(params: LinkedHashMap<String, ParamInfo>): String {
        if (params.isEmpty()) return ""

        fun renderLine(p: ParamInfo): String = buildString {
            append("<code><b>").append(escape(p.name)).append("</b>: ")
            append(escape(p.typeText))
            if (!p.defaultText.isNullOrBlank()) append(" = ").append(escape(p.defaultText))
            append("</code>")
        }

        val lines = params.values.joinToString("<br/>") { renderLine(it) }

        return buildString {
            append(DocumentationMarkup.CONTENT_START)
            append("<b>Parameters</b><br/>")
            append(lines)
            append(DocumentationMarkup.CONTENT_END)
        }
    }

    /** Build a clickable label for a PyClass (used in inherited param sections). */
    fun clickableClassLabel(klass: PyClass): String {
        val qn = klass.qualifiedName
        val label = qn ?: (klass.name ?: "<class>")
        val href = if (qn != null)
            DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + "py:" + qn
        else
            DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL + "py-src:" + (klass.name ?: "<class>")
        return """<a href="$href" title="${escape(qn ?: label)}">${escape(klass.name ?: label)}</a>"""
    }

    /** Render param sections: own params first, then inherited grouped by parent class. */
    fun renderParamSections(sections: LinkedHashMap<PyClass, LinkedHashMap<String, ParamInfo>>): String {
        if (sections.isEmpty()) return ""

        val iter = sections.entries.iterator()
        val ownEntry = iter.next()
        val ownParams = ownEntry.value

        val parents = buildList {
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.value.isNotEmpty()) add(e.key to e.value)
            }
        }

        fun renderLine(p: ParamInfo): String = buildString {
            append("<code><b>").append(escape(p.name)).append("</b>: ")
            append(escape(p.typeText))
            if (!p.defaultText.isNullOrBlank()) append(" = ").append(escape(p.defaultText))
            append("</code>")
        }

        fun joinLines(values: Collection<ParamInfo>) =
            values.joinToString("<br/>") { renderLine(it) }

        val ownBlock = if (ownParams.isEmpty()) "<i>None</i>" else joinLines(ownParams.values)

        return buildString {
            append(DocumentationMarkup.CONTENT_START)

            append("<b>Parameters</b>")
            if (ownBlock.isNotEmpty()) append("<br/>").append(ownBlock)

            if (parents.isNotEmpty()) {
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
    }
}
