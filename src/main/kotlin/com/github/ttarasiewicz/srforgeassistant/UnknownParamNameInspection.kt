package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInspection.*
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YamlPsiElementVisitor

/**
 * Inspection that warns when a key inside a `params:` mapping does not match
 * any known parameter of the resolved `_target:` class/function.
 * Suggests the closest matching parameter name as a quick fix.
 */
class UnknownParamNameInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Unknown parameter name in params"
    override fun getGroupDisplayName(): String = "SR-Forge Assistant"
    override fun getShortName(): String = "SRForgeUnknownParamName"
    override fun isEnabledByDefault(): Boolean = true
    override fun getDefaultLevel(): com.intellij.codeHighlighting.HighlightDisplayLevel =
        com.intellij.codeHighlighting.HighlightDisplayLevel.WARNING

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) return PsiElementVisitor.EMPTY_VISITOR

        return object : YamlPsiElementVisitor() {
            override fun visitKeyValue(keyValue: YAMLKeyValue) {
                val paramName = keyValue.keyText
                if (paramName.isBlank()) return

                // Check if this kv is inside a params: mapping
                val paramsMapping = keyValue.parent as? YAMLMapping ?: return
                val paramsKv = paramsMapping.parent as? YAMLKeyValue ?: return
                if (paramsKv.keyText != "params") return

                val allParams = try {
                    ParamUtils.resolveParamsFromParamsMapping(paramsMapping) ?: return
                } catch (_: Throwable) { return }

                if (paramName in allParams) return

                // Unknown param — find closest match
                val closest = findClosestMatch(paramName, allParams.keys)
                val fixes = mutableListOf<LocalQuickFix>()

                val message = if (closest != null) {
                    fixes.add(RenameParamQuickFix(closest))
                    "Unknown parameter '$paramName'. Did you mean '$closest'?"
                } else {
                    "Unknown parameter '$paramName'"
                }

                val keyElement = keyValue.key ?: keyValue
                holder.registerProblem(
                    keyElement,
                    message,
                    ProblemHighlightType.WARNING,
                    *fixes.toTypedArray()
                )
            }
        }
    }

    /** Find the closest matching param name using Levenshtein distance. */
    private fun findClosestMatch(input: String, candidates: Set<String>): String? {
        if (candidates.isEmpty()) return null
        val threshold = (input.length / 2).coerceAtLeast(2)
        var bestName: String? = null
        var bestDist = Int.MAX_VALUE
        for (candidate in candidates) {
            val dist = levenshtein(input.lowercase(), candidate.lowercase())
            if (dist < bestDist && dist <= threshold) {
                bestDist = dist
                bestName = candidate
            }
        }
        return bestName
    }

    /** Simple Levenshtein distance implementation. */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    /** Quick fix that renames the param key to the suggested name. */
    private class RenameParamQuickFix(private val suggestedName: String) : LocalQuickFix {

        override fun getFamilyName(): String = "Rename parameter"
        override fun getName(): String = "Rename to '$suggestedName'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement
            val kv = element.parent as? YAMLKeyValue ?: (element as? YAMLKeyValue) ?: return
            val oldValue = kv.value?.text ?: ""

            val generator = org.jetbrains.yaml.YAMLElementGenerator.getInstance(project)
            val tempFile = generator.createDummyYamlWithText("$suggestedName: $oldValue")
            val newKv = com.intellij.psi.util.PsiTreeUtil.findChildOfType(tempFile, YAMLKeyValue::class.java) ?: return

            kv.replace(newKv)
        }
    }
}
