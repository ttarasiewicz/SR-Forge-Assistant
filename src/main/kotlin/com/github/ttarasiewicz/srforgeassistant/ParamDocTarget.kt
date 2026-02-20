package com.github.ttarasiewicz.srforgeassistant

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.icons.AllIcons
import com.jetbrains.python.psi.PyClass

/**
 * Documentation target for a parameter key inside a `params:` YAML mapping.
 * Shows the parameter's type, default value, and which class defines it.
 */
class ParamDocTarget(
    private val paramName: String,
    private val info: ParamInfo,
    private val ownerClass: PyClass? = null
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> =
        Pointer { ParamDocTarget(paramName, info, ownerClass) }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(paramName)
            .icon(AllIcons.Nodes.Parameter)
            .presentation()

    override fun computeDocumentation(): DocumentationResult {
        val html = buildString {
            // Definition: parameter name with type
            append(DocumentationMarkup.DEFINITION_START)
            append("<code><b>").append(DocHtmlRenderer.escape(paramName)).append("</b>")
            append(": ").append(DocHtmlRenderer.escape(info.typeText))
            append("</code>")
            append(DocumentationMarkup.DEFINITION_END)

            // Content section
            append(DocumentationMarkup.CONTENT_START)
            if (info.defaultText != null) {
                append("<b>Default:</b> <code>").append(DocHtmlRenderer.escape(info.defaultText)).append("</code><br/>")
            } else {
                append("<b>Required</b> (no default value)<br/>")
            }
            append(DocumentationMarkup.CONTENT_END)

            // Sections: owner class
            if (ownerClass != null) {
                append(DocumentationMarkup.SECTIONS_START)
                val classLabel = DocHtmlRenderer.clickableClassLabel(ownerClass)
                append(DocumentationMarkup.SECTION_HEADER_START)
                append("Defined in:")
                append(DocumentationMarkup.SECTION_SEPARATOR)
                append(classLabel)
                append(DocumentationMarkup.SECTION_END)
                append(DocumentationMarkup.SECTIONS_END)
            }
        }
        return DocumentationResult.documentation(html)
    }

    override fun computeDocumentationHint(): String =
        "<b>${DocHtmlRenderer.escape(paramName)}</b>: ${DocHtmlRenderer.escape(info.typeText)}"
}
