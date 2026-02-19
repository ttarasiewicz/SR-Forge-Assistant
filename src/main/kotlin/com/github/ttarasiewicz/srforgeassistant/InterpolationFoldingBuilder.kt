package com.github.ttarasiewicz.srforgeassistant

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Folds `${...}` and `%{...}` interpolation expressions in YAML files,
 * replacing them with their resolved values from the same document.
 *
 * Folded by default so users see resolved values at a glance.
 * Click a fold to expand and see the original interpolation syntax.
 * Re-collapse with Ctrl+. (fold at caret) or Ctrl+Shift+. (collapse all).
 */
class InterpolationFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean
    ): Array<FoldingDescriptor> {
        if (!SrForgeHighlightSettings.getInstance().state.interpolationFoldingEnabled) return FoldingDescriptor.EMPTY_ARRAY
        val vFile = root.containingFile?.virtualFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val name = vFile.name
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) return FoldingDescriptor.EMPTY_ARRAY

        val text = document.text
        if (text.isEmpty()) return FoldingDescriptor.EMPTY_ARRAY

        // Use the file root node for all descriptors — avoids PSI fragmentation issues
        // caused by YAML parser interpreting ${ as flow mapping syntax
        val fileNode = root.node ?: return FoldingDescriptor.EMPTY_ARRAY

        val maxLen = SrForgeHighlightSettings.getInstance().state.foldPlaceholderMaxLength
        val descriptors = ArrayList<FoldingDescriptor>()

        for (match in INTERPOLATION_REGEX.findAll(text)) {
            val path = match.groupValues[1].trim()
            if (path.isEmpty()) continue

            val resolved = YamlInterpolationCompletionContributor.resolveValueFromText(text, path)
                ?: continue

            val truncated = if (resolved.length > maxLen) resolved.take(maxLen) + "..." else resolved

            val range = TextRange(match.range.first, match.range.last + 1)
            if (!fileNode.textRange.contains(range)) continue

            // No FoldingGroup — each interpolation fold is independent
            descriptors.add(FoldingDescriptor(fileNode, range, null, truncated))
        }

        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean =
        SrForgeHighlightSettings.getInstance().state.foldOnFileOpen

    companion object {
        private val INTERPOLATION_REGEX = Regex("""[$%]\{([^}]+)}""")
    }
}
