package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInspection.*
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLElementGenerator
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
                    AddMissingParamsQuickFix(missing, allParams)
                )
            }
        }
    }

    /** Quick fix that inserts the missing required parameters into `params:`. */
    private class AddMissingParamsQuickFix(
        private val missingNames: List<String>,
        private val allParams: LinkedHashMap<String, ParamInfo>
    ) : LocalQuickFix {

        override fun getFamilyName(): String = "Add missing required parameters"
        override fun getName(): String = "Add missing required parameters: ${missingNames.joinToString(", ")}"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val targetKv = descriptor.psiElement as? YAMLKeyValue ?: return
            val mapping = targetKv.parent as? YAMLMapping ?: return

            val generator = YAMLElementGenerator.getInstance(project)
            val paramsKv = mapping.keyValues.firstOrNull { it.keyText == "params" }
            val paramsMapping = paramsKv?.value as? YAMLMapping

            val stubs = missingNames.mapNotNull { name ->
                allParams[name]?.let { info -> name to info }
            }

            if (paramsMapping != null) {
                for ((name, info) in stubs) {
                    val value = stubValue(info)
                    val tempFile = generator.createDummyYamlWithText("$name: $value")
                    val kv = PsiTreeUtil.findChildOfType(tempFile, YAMLKeyValue::class.java) ?: continue
                    paramsMapping.putKeyValue(kv)
                }
            } else {
                val yamlText = buildString {
                    append("params:\n")
                    for ((name, info) in stubs) {
                        append("  ").append(name).append(": ").append(stubValue(info)).append("\n")
                    }
                }.trimEnd()

                val tempFile = generator.createDummyYamlWithText(yamlText)
                val newParamsKv = PsiTreeUtil.findChildOfType(tempFile, YAMLKeyValue::class.java) ?: return
                mapping.putKeyValue(newParamsKv)
            }
        }

        private fun stubValue(info: ParamInfo): String = when {
            info.defaultText != null -> info.defaultText
            info.typeText == "bool" || info.typeText == "Bool" -> "false"
            info.typeText == "int" || info.typeText == "Int" -> "0"
            info.typeText == "float" || info.typeText == "Float" -> "0.0"
            info.typeText == "str" || info.typeText == "String" -> "\"\""
            else -> "???"
        }
    }
}
