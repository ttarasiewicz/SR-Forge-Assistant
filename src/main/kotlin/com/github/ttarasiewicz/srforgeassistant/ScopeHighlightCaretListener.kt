package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
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
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import java.awt.Font

/**
 * Two complementary highlights for YAML files with `_target:` blocks:
 *
 * 1. **Block highlight** — shades the background of the entire `_target:` mapping
 *    the caret is inside (subtle tint so you see scope boundaries).
 *
 * 2. **Parent key highlight** — highlights the line of the immediate parent YAML key
 *    so you always know "which key am I nested under?" at a glance.
 *
 * Colors and enabled state are read from [SrForgeHighlightSettings].
 */
class ScopeHighlightCaretListener : CaretListener {

    override fun caretPositionChanged(event: CaretEvent) {
        refreshHighlights(event.editor)
    }

    fun refreshHighlights(editor: Editor) {
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!file.name.endsWith(".yaml") && !file.name.endsWith(".yml")) return

        val project = editor.project ?: return
        val settings = SrForgeHighlightSettings.getInstance()
        val caretOffset = editor.caretModel.offset

        data class HighlightInfo(val blockRange: TextRange?, val parentKeyLineRange: TextRange?)

        val info = ReadAction.compute<HighlightInfo, Throwable> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc)
                ?: return@compute HighlightInfo(null, null)

            // Clamp offset to the last content character on the line so that
            // placing the caret at end-of-line gives the same result as on the text.
            val line = doc.getLineNumber(caretOffset)
            val lineStart = doc.getLineStartOffset(line)
            val lineEnd = doc.getLineEndOffset(line)
            val lineText = doc.getText(TextRange(lineStart, lineEnd))
            val lastContent = lineStart + lineText.trimEnd().length
            val offset = if (lastContent > lineStart) caretOffset.coerceAtMost(lastContent - 1) else caretOffset

            val element = psiFile.findElementAt(offset)
                ?: return@compute HighlightInfo(null, null)

            val blockRange = if (settings.state.blockEnabled) findEnclosingTargetMapping(element)?.textRange else null
            val parentKeyLineRange = if (settings.state.parentKeyEnabled) findParentKeyLineRange(element, doc) else null

            HighlightInfo(blockRange, parentKeyLineRange)
        }

        // ── Block highlight ────────────────────────────────────────────
        val oldBlockRange = editor.getUserData(BLOCK_RANGE_KEY)
        if (info.blockRange != oldBlockRange) {
            editor.getUserData(BLOCK_HIGHLIGHTER_KEY)?.let {
                editor.markupModel.removeHighlighter(it)
            }
            editor.putUserData(BLOCK_HIGHLIGHTER_KEY, null)
            editor.putUserData(BLOCK_RANGE_KEY, null)

            if (info.blockRange != null) {
                val h = editor.markupModel.addRangeHighlighter(
                    info.blockRange.startOffset,
                    info.blockRange.endOffset,
                    HighlighterLayer.SELECTION - 1,
                    blockAttributes(),
                    HighlighterTargetArea.LINES_IN_RANGE
                )
                editor.putUserData(BLOCK_HIGHLIGHTER_KEY, h)
                editor.putUserData(BLOCK_RANGE_KEY, info.blockRange)
            }
        }

        // ── Parent key line highlight ──────────────────────────────────
        val oldParentRange = editor.getUserData(PARENT_RANGE_KEY)
        if (info.parentKeyLineRange != oldParentRange) {
            editor.getUserData(PARENT_HIGHLIGHTER_KEY)?.let {
                editor.markupModel.removeHighlighter(it)
            }
            editor.putUserData(PARENT_HIGHLIGHTER_KEY, null)
            editor.putUserData(PARENT_RANGE_KEY, null)

            if (info.parentKeyLineRange != null) {
                val h = editor.markupModel.addRangeHighlighter(
                    info.parentKeyLineRange.startOffset,
                    info.parentKeyLineRange.endOffset,
                    HighlighterLayer.SELECTION - 1,
                    parentKeyAttributes(),
                    HighlighterTargetArea.LINES_IN_RANGE
                )
                editor.putUserData(PARENT_HIGHLIGHTER_KEY, h)
                editor.putUserData(PARENT_RANGE_KEY, info.parentKeyLineRange)
            }
        }
    }

    companion object {
        private val BLOCK_HIGHLIGHTER_KEY = Key.create<RangeHighlighter>("srforge.scope.block.hl")
        private val BLOCK_RANGE_KEY = Key.create<TextRange>("srforge.scope.block.range")
        private val PARENT_HIGHLIGHTER_KEY = Key.create<RangeHighlighter>("srforge.scope.parent.hl")
        private val PARENT_RANGE_KEY = Key.create<TextRange>("srforge.scope.parent.range")

        private fun blockAttributes(): TextAttributes {
            val s = SrForgeHighlightSettings.getInstance()
            val bg = JBColor(s.blockLight, s.blockDark)
            return TextAttributes(null, bg, null, null, Font.PLAIN)
        }

        private fun parentKeyAttributes(): TextAttributes {
            val s = SrForgeHighlightSettings.getInstance()
            val bg = JBColor(s.parentLight, s.parentDark)
            return TextAttributes(null, bg, null, null, s.state.parentKeyFontStyle)
        }

        fun clearHighlights(editor: Editor) {
            editor.getUserData(BLOCK_HIGHLIGHTER_KEY)?.let {
                editor.markupModel.removeHighlighter(it)
            }
            editor.putUserData(BLOCK_HIGHLIGHTER_KEY, null)
            editor.putUserData(BLOCK_RANGE_KEY, null)
            editor.getUserData(PARENT_HIGHLIGHTER_KEY)?.let {
                editor.markupModel.removeHighlighter(it)
            }
            editor.putUserData(PARENT_HIGHLIGHTER_KEY, null)
            editor.putUserData(PARENT_RANGE_KEY, null)
        }

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

        /**
         * Find the immediate parent YAML key for the element at the caret.
         * Returns a [TextRange] covering that key's full line.
         */
        private fun findParentKeyLineRange(
            element: PsiElement,
            doc: com.intellij.openapi.editor.Document
        ): TextRange? {
            var kv: YAMLKeyValue? = null
            var current: PsiElement? = element
            while (current != null) {
                if (current is YAMLKeyValue) {
                    kv = current
                    break
                }
                current = current.parent
            }
            if (kv == null) return null

            val parentMapping = kv.parent as? YAMLMapping ?: return null
            val parentKv = parentMapping.parent as? YAMLKeyValue ?: return null

            val line = doc.getLineNumber(parentKv.textOffset)
            return TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))
        }
    }
}

/**
 * Registers [ScopeHighlightCaretListener] globally on project startup
 * and subscribes to settings changes for live highlight updates.
 */
class ScopeHighlightStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val listener = ScopeHighlightCaretListener()
        EditorFactory.getInstance().eventMulticaster
            .addCaretListener(listener, project)

        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(SrForgeHighlightSettings.TOPIC, SrForgeHighlightSettings.SettingsListener {
                for (editor in EditorFactory.getInstance().allEditors) {
                    if (editor.project != project) continue
                    ScopeHighlightCaretListener.clearHighlights(editor)
                    listener.refreshHighlights(editor)
                }
            })
    }
}
