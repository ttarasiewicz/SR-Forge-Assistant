package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Key

/**
 * Auto-collapses interpolation fold regions when the caret moves
 * out of a `${...}` or `%{...}` expression.
 *
 * This gives the user a smooth workflow: type an interpolation, move away,
 * and it folds automatically. Expand a fold to inspect or edit, move away,
 * and it re-folds.
 */
class InterpolationFoldCaretListener : CaretListener {

    override fun caretPositionChanged(event: CaretEvent) {
        val editor = event.editor
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!file.name.endsWith(".yaml") && !file.name.endsWith(".yml")) return

        val offset = editor.caretModel.offset
        val text = doc.text

        val wasInside = editor.getUserData(WAS_INSIDE_KEY) ?: false
        val prevOffset = editor.getUserData(PREV_OFFSET_KEY) ?: -1

        val isInside = YamlInterpolationCompletionContributor.isInsideInterpolation(text, offset)
        editor.putUserData(WAS_INSIDE_KEY, isInside)
        editor.putUserData(PREV_OFFSET_KEY, offset)

        if (wasInside && !isInside && prevOffset >= 0
            && SrForgeHighlightSettings.getInstance().state.autoCollapseOnCaretExit) {
            collapseInterpolationAt(editor, text, prevOffset)
        }
    }

    companion object {
        private val WAS_INSIDE_KEY = Key.create<Boolean>("interpolation.fold.wasInside")
        private val PREV_OFFSET_KEY = Key.create<Int>("interpolation.fold.prevOffset")
        private val INTERPOLATION_REGEX = Regex("""[$%]\{[^}]+}""")

        internal fun isInterpolationRegion(text: String, region: FoldRegion): Boolean {
            if (region.startOffset < 0 || region.endOffset > text.length) return false
            val regionText = text.substring(region.startOffset, region.endOffset)
            return INTERPOLATION_REGEX.matches(regionText)
        }

        private fun collapseInterpolationAt(editor: Editor, text: String, offset: Int) {
            val regions = editor.foldingModel.allFoldRegions.filter { region ->
                region.isExpanded &&
                    region.startOffset <= offset && offset <= region.endOffset &&
                    isInterpolationRegion(text, region)
            }
            if (regions.isEmpty()) return
            editor.foldingModel.runBatchFoldingOperation {
                for (region in regions) {
                    region.isExpanded = false
                }
            }
        }
    }
}

/**
 * Registers [InterpolationFoldCaretListener] globally on project startup.
 */
class InterpolationFoldStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        EditorFactory.getInstance().eventMulticaster
            .addCaretListener(InterpolationFoldCaretListener(), project)
    }
}
