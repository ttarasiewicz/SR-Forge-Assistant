package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import org.jetbrains.yaml.psi.YAMLMapping
import java.awt.Color
import java.awt.Font

/**
 * Highlights the background of the `_target:` mapping block the caret is inside.
 * Moving between nested blocks (e.g. inner LazyDataset vs outer PatchedDataset)
 * changes which region is shaded, making it easy to see scope boundaries.
 */
class ScopeHighlightCaretListener : CaretListener {

    override fun caretPositionChanged(event: CaretEvent) {
        val editor = event.editor
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!file.name.endsWith(".yaml") && !file.name.endsWith(".yml")) return

        val project = editor.project ?: return
        val offset = editor.caretModel.offset

        val newRange = ReadAction.compute<TextRange?, Throwable> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return@compute null
            val element = psiFile.findElementAt(offset) ?: return@compute null
            findEnclosingTargetMapping(element)?.textRange
        }

        val oldRange = editor.getUserData(HIGHLIGHTED_RANGE_KEY)
        if (newRange == oldRange) return

        // Remove previous highlighter
        editor.getUserData(HIGHLIGHTER_KEY)?.let {
            editor.markupModel.removeHighlighter(it)
        }
        editor.putUserData(HIGHLIGHTER_KEY, null)
        editor.putUserData(HIGHLIGHTED_RANGE_KEY, null)

        // Add new highlighter if inside a _target block
        if (newRange != null) {
            val highlighter = editor.markupModel.addRangeHighlighter(
                newRange.startOffset,
                newRange.endOffset,
                HighlighterLayer.SELECTION - 1,
                SCOPE_ATTRIBUTES,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            editor.putUserData(HIGHLIGHTER_KEY, highlighter)
            editor.putUserData(HIGHLIGHTED_RANGE_KEY, newRange)
        }
    }

    companion object {
        private val HIGHLIGHTER_KEY = Key.create<RangeHighlighter>("srforge.scope.highlighter")
        private val HIGHLIGHTED_RANGE_KEY = Key.create<TextRange>("srforge.scope.range")

        private val SCOPE_BG = JBColor(Color(0xF0F4FF), Color(0x2D3036))
        private val SCOPE_ATTRIBUTES = TextAttributes(null, SCOPE_BG, null, null, Font.PLAIN)

        /**
         * Walk up the PSI tree to find the nearest [YAMLMapping] that
         * contains a `_target:` key-value pair.
         */
        fun findEnclosingTargetMapping(element: PsiElement): YAMLMapping? {
            var current: PsiElement? = element
            while (current != null) {
                if (current is YAMLMapping) {
                    if (current.keyValues.any { it.keyText == "_target" }) {
                        return current
                    }
                }
                current = current.parent
            }
            return null
        }
    }
}

/**
 * Registers [ScopeHighlightCaretListener] globally on project startup.
 */
class ScopeHighlightStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        EditorFactory.getInstance().eventMulticaster
            .addCaretListener(ScopeHighlightCaretListener(), project)
    }
}
