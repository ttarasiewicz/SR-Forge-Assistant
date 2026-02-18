package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Gutter icon on `_target:` lines that generates missing parameter stubs when clicked.
 * Only appears when the resolved target has parameters not yet written in `params:`.
 */
class TargetParamStubsLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only match on the leaf key text of a _target: key-value.
        // LineMarkerProvider contract: element must be a leaf.
        if (element.firstChild != null) return null
        if (DumbService.isDumb(element.project)) return null

        val kv = element.parent as? YAMLKeyValue ?: return null
        if (kv.keyText != "_target") return null
        // Ensure the leaf is actually the key (not the value)
        if (kv.key?.textRange?.contains(element.textRange) != true) return null

        val mapping = kv.parent as? YAMLMapping ?: return null

        val missingCount = try {
            ParamUtils.countMissingParams(mapping)
        } catch (_: Throwable) {
            null
        } ?: return null

        val tooltip = "Generate $missingCount missing parameter stub${if (missingCount > 1) "s" else ""}"

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Nodes.Parameter,
            { tooltip },
            { _, elt ->
                val project = elt.project
                if (DumbService.isDumb(project)) return@LineMarkerInfo
                // Re-resolve from the element at click time to avoid stale PSI references
                val kvAtClick = elt.parent as? YAMLKeyValue ?: return@LineMarkerInfo
                val mappingAtClick = kvAtClick.parent as? YAMLMapping ?: return@LineMarkerInfo
                WriteCommandAction.runWriteCommandAction(project, "Generate Parameter Stubs", null, {
                    try {
                        ParamUtils.generateMissingStubs(mappingAtClick)
                    } catch (_: Throwable) { }
                }, mappingAtClick.containingFile)
            },
            GutterIconRenderer.Alignment.RIGHT,
            { tooltip }
        )
    }
}
