package com.github.ttarasiewicz.srforgeassistant

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.util.ui.EmptyIcon
import com.jetbrains.python.psi.PyClass
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

        val sections = buildPropagatedParams(cls)
        val paramsHtml = sections?.let { DocHtmlRenderer.renderParamSections(it) }

        val providerHtml = try {
            val provider: DocumentationProvider = LanguageDocumentation.INSTANCE.forLanguage(cls.language)
            provider.generateDoc(cls, cls) ?: provider.generateHoverDoc(cls, cls)
        } catch (_: Throwable) {
            null
        }

        val selfLink = DocHtmlRenderer.buildSelfSourceLink(cls)
        if (!providerHtml.isNullOrBlank()) {
            return DocumentationResult.documentation(selfLink + (paramsHtml ?: "") + providerHtml)
        }

        // Fallback: minimal header + our params + docstring
        val header = DocHtmlRenderer.buildClassHeader(cls)
        val doc = cls.docStringExpression?.stringValue
            ?.takeIf { it.isNotBlank() } ?: "<i>No docstring found.</i>"
        val modulePath = cls.qualifiedName?.substringBeforeLast('.', missingDelimiterValue = "")

        val html = buildString {
            append(DocumentationMarkup.DEFINITION_START)
            val qn = cls.qualifiedName
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
        val qn = element.qualifiedName ?: element.name ?: "class"
        return "<b>${DocHtmlRenderer.escape(qn)}</b>"
    }
}

/** Shown when `_target` points to a non-existing Python class. */
class NotFoundDocTarget(private val fqn: String) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer { NotFoundDocTarget(fqn) }
    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(fqn).presentation()

    override fun computeDocumentation(): DocumentationResult {
        val safe = DocHtmlRenderer.escape(fqn)
        return DocumentationResult.documentation(
            """
            <html><body>
              <h3>$safe</h3>
              <p><i>No Python class found for this fully-qualified name.</i></p>
              <p>Check your environment (project + libraries) or the spelling of the class.</p>
            </body></html>
            """.trimIndent()
        )
    }

    override fun computeDocumentationHint(): String {
        val safe = DocHtmlRenderer.escape(fqn)
        return "<b>$safe</b> — not found"
    }
}
