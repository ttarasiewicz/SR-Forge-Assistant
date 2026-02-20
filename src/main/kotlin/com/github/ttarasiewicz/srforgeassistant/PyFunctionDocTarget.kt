package com.github.ttarasiewicz.srforgeassistant

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.util.ui.EmptyIcon
import com.jetbrains.python.psi.PyFunction
import javax.swing.Icon

/** Documentation target for a resolved top-level PyFunction. */
class PyFunctionDocTarget(pyFunction: PyFunction) : DocumentationTarget {
    private val project = pyFunction.project
    private val ptr = com.intellij.psi.SmartPointerManager.getInstance(project).createSmartPsiElementPointer(pyFunction)
    val element: PyFunction? get() = ptr.element

    override fun createPointer(): Pointer<out DocumentationTarget> =
        Pointer { ptr.element?.let { PyFunctionDocTarget(it) } }

    override fun computePresentation(): TargetPresentation {
        val element = ptr.element
        val qn = element?.qualifiedName ?: element?.name ?: "<function>"
        val icon: Icon = element?.getIcon(0) ?: EmptyIcon.ICON_16
        return TargetPresentation.builder(qn).icon(icon).presentation()
    }

    override fun computeDocumentation(): DocumentationResult? {
        val func = ptr.element ?: return null

        val params = buildFunctionParams(func)
        val paramsHtml = params?.let { DocHtmlRenderer.renderFunctionParams(it) }

        val providerHtml = try {
            val provider: DocumentationProvider = LanguageDocumentation.INSTANCE.forLanguage(func.language)
            provider.generateDoc(func, func) ?: provider.generateHoverDoc(func, func)
        } catch (_: Throwable) {
            null
        }

        val selfLink = DocHtmlRenderer.buildSelfSourceLinkForFunction(func)
        if (!providerHtml.isNullOrBlank()) {
            return DocumentationResult.documentation(selfLink + (paramsHtml ?: "") + providerHtml)
        }

        // Fallback: minimal header + our params + docstring
        val header = DocHtmlRenderer.buildFunctionHeader(func)
        val doc = func.docStringExpression?.stringValue
            ?.takeIf { it.isNotBlank() } ?: "<i>No docstring found.</i>"
        val modulePath = func.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")

        val html = buildString {
            append(DocumentationMarkup.DEFINITION_START)
            val qn = func.qualifiedName
            if (qn != null) {
                append("<code><a href=\"py-src:").append(DocHtmlRenderer.escape(qn)).append("\">")
                append(DocHtmlRenderer.escape(header)).append("</a></code>")
            } else {
                append("<code>").append(DocHtmlRenderer.escape(header)).append("</code>")
            }
            append(DocumentationMarkup.DEFINITION_END)

            if (paramsHtml != null) append(paramsHtml)

            append(DocumentationMarkup.CONTENT_START)
            append(DocHtmlRenderer.escapePreservingNewlines(doc))
            append(DocumentationMarkup.CONTENT_END)

            if (!modulePath.isNullOrBlank()) {
                append(DocumentationMarkup.SECTIONS_START)
                append(DocHtmlRenderer.sectionRow("Module:", modulePath))
                append(DocumentationMarkup.SECTIONS_END)
            }
        }
        return DocumentationResult.documentation(html)
    }

    override fun computeDocumentationHint(): String? {
        val element = ptr.element ?: return null
        val qn = element.qualifiedName ?: element.name ?: "function"
        return "<b>${DocHtmlRenderer.escape(qn)}</b>"
    }
}
