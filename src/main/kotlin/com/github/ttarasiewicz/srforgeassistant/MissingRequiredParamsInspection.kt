package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInspection.*
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

/**
 * Inspection that warns when a `_target:` references a Python class/function
 * but its sibling `params:` mapping is missing required parameters (those with no default value).
 */
class MissingRequiredParamsInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "SR-Forge Assistant"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR

        return object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                if (keyValue.keyText != "_target") return

                val scalar = keyValue.value as? YAMLScalar ?: return
                val fqn = scalar.textValue.trim()
                if (fqn.isEmpty()) return

                val mapping = keyValue.parent as? YAMLMapping ?: return
                val allParams = try {
                    ParamUtils.resolveParamsFromTargetMapping(mapping) ?: return
                } catch (_: Throwable) { return }

                val requiredParams = allParams.filter { (_, info) -> info.defaultText == null }
                if (requiredParams.isEmpty()) return

                val paramsKv = mapping.keyValues.firstOrNull { it.keyText == "params" }
                val paramsMapping = paramsKv?.value as? YAMLMapping
                val existing = paramsMapping?.let { ParamUtils.existingParamNames(it) } ?: emptySet()

                val missing = requiredParams.keys.filter { it !in existing }
                if (missing.isEmpty()) return

                val message = "Missing required parameter(s): ${missing.joinToString(", ")}"
                holder.registerProblem(
                    keyValue,
                    message,
                    ProblemHighlightType.WARNING,
                    AddMissingParamsQuickFix(missing)
                )
            }
        }
    }

    /** Quick fix that inserts the missing required parameters into `params:`. */
    private class AddMissingParamsQuickFix(
        private val missingNames: List<String>
    ) : LocalQuickFix {

        override fun getFamilyName(): String = "Add missing required parameters"
        override fun getName(): String = "Add missing required parameters: ${missingNames.joinToString(", ")}"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val targetKv = descriptor.psiElement as? YAMLKeyValue ?: return
            val mapping = targetKv.parent as? YAMLMapping ?: return
            ParamUtils.generateMissingStubs(mapping)
        }
    }
}
