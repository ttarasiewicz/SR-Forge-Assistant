package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue


class YamlTargetAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is YAMLKeyValue) return
        val targetSection = element.getParentYamlTargetSection() ?: return
        if (targetSection.getTarget() == element) {
            val project = element.project
            val className = element.valueText

            // Check if the class exists
            val pyClass = PythonSignatureAnalyzer.findPythonClass(project, className)
            if (pyClass == null) {
                holder.newAnnotation(HighlightSeverity.ERROR, "⚠️ Class '$className' not found")
                    .range(element.value ?: element)
                    .create()
                return
            }

            if (PythonSignatureAnalyzer.isOnlyClassName(className)) {
                if (!ClassRegistry.isRegistered(project, className)) {
                    holder.newAnnotation(
                        HighlightSeverity.ERROR,
                        "⚠️ Class '$className' not found in class registry. Make sure that the class has been decorated with @register_class."
                    )
                        .range(element.value ?: element)
                        .create()
                }
            }

            // Retrieve `__init__` parameters
            val parameters = PythonSignatureAnalyzer.getFunctionParameters(pyClass, "__init__")
                .filter { it.name != "self" } // Exclude `self` parameter
                .mapNotNull { it.text }
                .joinToString("<br>") { "- $it" } // Each parameter on a new line

            val formattedParams = if (parameters.isEmpty()) "No parameters" else parameters

            // Tooltip message with proper multi-line formatting
            val tooltipText = "Class: $className<br>Parameters:<br>$formattedParams"

            // Inject tooltip into hover popup using `tooltip()`
            holder.newAnnotation(HighlightSeverity.INFORMATION, "")
                .range(element.value ?: element)
                .tooltip(tooltipText) // `.tooltip()` supports HTML-style formatting
                .create()
        }
        else {
            val section = targetSection  // our parent section
            val errors = section.getParamsValidationErrors()  // map from YAMLKeyValue (param) -> error message
            if (element in errors.keys) {
                val errorMessage = errors[element]
                holder.newAnnotation(HighlightSeverity.ERROR, errorMessage ?: "Invalid parameter")
                    .range(element)
                    .create()
            }
        }
    }
}