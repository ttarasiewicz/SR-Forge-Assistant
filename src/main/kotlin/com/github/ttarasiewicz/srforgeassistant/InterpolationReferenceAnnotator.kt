package com.github.ttarasiewicz.srforgeassistant

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Highlights invalid interpolation references in YAML files:
 *
 * - `${path}` / `${ref:path}` / `%{path}` — red underline when the path
 *   cannot be resolved within the current YAML document.
 * - `${ef:path}` — warning when the resolver prefix is unrecognized
 *   (likely a typo of `ref:`).
 */
class InterpolationReferenceAnnotator :
    ExternalAnnotator<InterpolationReferenceAnnotator.Info, List<InterpolationReferenceAnnotator.Problem>>() {

    data class Info(val text: String, val refs: List<Ref>)

    data class Ref(
        val fullRange: TextRange,
        val pathRange: TextRange,
        val path: String,
        val type: RefType
    )

    enum class RefType { RESOLVABLE_PATH, UNKNOWN_RESOLVER }

    data class Problem(val range: TextRange, val message: String, val severity: HighlightSeverity)

    override fun collectInformation(file: PsiFile): Info? {
        if (!SrForgeHighlightSettings.getInstance().state.interpolationFoldingEnabled) return null
        val vFile = file.virtualFile ?: return null
        if (!vFile.name.endsWith(".yaml") && !vFile.name.endsWith(".yml")) return null
        val text = file.viewProvider.document?.text ?: return null

        val refs = mutableListOf<Ref>()

        for (match in InterpolationUtils.INTERPOLATION_REGEX.findAll(text)) {
            val content = match.groupValues[1].trim()
            if (content.isEmpty()) continue

            val fullRange = TextRange(match.range.first, match.range.last + 1)
            val contentStart = match.range.first + 2 // after ${ or %{
            val contentEnd = match.range.last        // before }

            val colonIdx = content.indexOf(':')

            if (colonIdx < 0) {
                // ${path} or %{path} — validate the path
                refs.add(Ref(fullRange, TextRange(contentStart, contentEnd), content, RefType.RESOLVABLE_PATH))
            } else {
                val prefix = content.substring(0, colonIdx).trim()
                if (prefix == "ref") {
                    // ${ref:path} — validate the path after ref:
                    val pathStr = content.substring(colonIdx + 1).trim()
                    if (pathStr.isEmpty()) continue
                    val leadingSpaces = content.substring(colonIdx + 1).length -
                            content.substring(colonIdx + 1).trimStart().length
                    val pathStart = contentStart + colonIdx + 1 + leadingSpaces
                    refs.add(Ref(fullRange, TextRange(pathStart, contentEnd), pathStr, RefType.RESOLVABLE_PATH))
                } else if (!InterpolationUtils.isKnownResolverPrefix(prefix)) {
                    // Unknown resolver (ef:, typo, etc.)
                    refs.add(Ref(fullRange, TextRange(contentStart, contentEnd), content, RefType.UNKNOWN_RESOLVER))
                }
                // Known but non-resolvable (oc.*) — skip
            }
        }

        return Info(text, refs)
    }

    override fun doAnnotate(info: Info): List<Problem> {
        val problems = mutableListOf<Problem>()
        for (ref in info.refs) {
            when (ref.type) {
                RefType.RESOLVABLE_PATH -> {
                    val resolved = YamlInterpolationCompletionContributor.resolveValueFromText(info.text, ref.path)
                    if (resolved == null) {
                        problems.add(Problem(
                            ref.pathRange,
                            "Cannot resolve reference '${ref.path}'",
                            HighlightSeverity.WARNING
                        ))
                    }
                }
                RefType.UNKNOWN_RESOLVER -> {
                    val prefix = ref.path.substringBefore(':')
                    problems.add(Problem(
                        ref.fullRange,
                        "Unknown interpolation resolver '$prefix'",
                        HighlightSeverity.WARNING
                    ))
                }
            }
        }
        return problems
    }

    override fun apply(file: PsiFile, problems: List<Problem>, holder: AnnotationHolder) {
        for (problem in problems) {
            holder.newAnnotation(problem.severity, problem.message)
                .range(problem.range)
                .create()
        }
    }
}
