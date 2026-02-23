package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
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
 * Two complementary highlights for YAML files:
 *
 * 1. **Block highlight** — shades the background of the parent key's entire scope
 *    (subtle tint so you see scope boundaries).
 *
 * 2. **Parent key highlight** — highlights the line of the immediate parent YAML key
 *    so you always know "which key am I nested under?" at a glance.
 *
 * On blank/whitespace-only lines, indentation-based detection is used since PSI
 * has no meaningful element. On content lines, PSI tree walking is used.
 *
 * Colors and enabled state are read from [SrForgeHighlightSettings].
 */
class ScopeHighlightCaretListener : CaretListener, DocumentListener {

    override fun caretPositionChanged(event: CaretEvent) {
        refreshHighlights(event.editor)
    }

    override fun documentChanged(event: DocumentEvent) {
        val doc = event.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!file.name.endsWith(".yaml") && !file.name.endsWith(".yml")) return
        // Defer to after the write action completes so PSI is committed
        ApplicationManager.getApplication().invokeLater {
            for (editor in EditorFactory.getInstance().getEditors(doc)) {
                refreshHighlights(editor)
            }
        }
    }

    fun refreshHighlights(editor: Editor) {
        val doc = editor.document
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!file.name.endsWith(".yaml") && !file.name.endsWith(".yml")) return

        val project = editor.project ?: return
        val settings = SrForgeHighlightSettings.getInstance()
        val caretOffset = editor.caretModel.offset
        val caretColumn = editor.caretModel.logicalPosition.column

        data class HighlightInfo(val blockRange: TextRange?, val parentKeyLineRange: TextRange?)

        val info = ReadAction.compute<HighlightInfo, Throwable> {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc)
                ?: return@compute HighlightInfo(null, null)

            val line = doc.getLineNumber(caretOffset)
            val lineStart = doc.getLineStartOffset(line)
            val lineEnd = doc.getLineEndOffset(line)
            val lineText = doc.getText(TextRange(lineStart, lineEnd))

            if (lineText.isBlank()) {
                // Indentation-based: PSI has no meaningful element on blank lines
                findHighlightsByIndentation(
                    caretColumn, line, doc,
                    settings.state.blockEnabled, settings.state.parentKeyEnabled
                ).let { HighlightInfo(it.first, it.second) }
            } else {
                // PSI-based
                val lastContent = lineStart + lineText.trimEnd().length
                val offset = caretOffset.coerceAtMost(lastContent - 1).coerceAtLeast(lineStart)
                val element = psiFile.findElementAt(offset)
                    ?: return@compute HighlightInfo(null, null)
                findHighlightsByPsi(
                    element, doc,
                    settings.state.blockEnabled, settings.state.parentKeyEnabled
                ).let { HighlightInfo(it.first, it.second) }
            }
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
         * PSI-based highlights for content lines.
         * Block = parent key-value's full scope. Parent key = that key's line.
         */
        private fun findHighlightsByPsi(
            element: PsiElement,
            doc: com.intellij.openapi.editor.Document,
            blockEnabled: Boolean,
            parentKeyEnabled: Boolean
        ): Pair<TextRange?, TextRange?> {
            var kv: YAMLKeyValue? = null
            var current: PsiElement? = element
            while (current != null) {
                if (current is YAMLKeyValue) {
                    kv = current
                    break
                }
                current = current.parent
            }
            if (kv == null) return null to null

            val parentMapping = kv.parent as? YAMLMapping ?: return null to null
            val parentKv = parentMapping.parent as? YAMLKeyValue ?: return null to null

            val blockRange = if (blockEnabled) parentKv.textRange else null

            val parentKeyLineRange = if (parentKeyEnabled) {
                val line = doc.getLineNumber(parentKv.textOffset)
                TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))
            } else null

            return blockRange to parentKeyLineRange
        }

        /**
         * Indentation-based highlights for blank/whitespace lines.
         * Scans upward for the nearest YAML key with indent < caret column (= parent),
         * then forward to find the block end (next line at ≤ parent indent).
         */
        private fun findHighlightsByIndentation(
            caretColumn: Int,
            caretLine: Int,
            doc: com.intellij.openapi.editor.Document,
            blockEnabled: Boolean,
            parentKeyEnabled: Boolean
        ): Pair<TextRange?, TextRange?> {
            var parentLineNum = -1
            var parentIndent = -1
            for (lineNum in (caretLine - 1) downTo 0) {
                val lineStart = doc.getLineStartOffset(lineNum)
                val lineEnd = doc.getLineEndOffset(lineNum)
                val lineText = doc.getText(TextRange(lineStart, lineEnd))
                val trimmed = lineText.trimStart()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                val indent = lineText.length - trimmed.length
                if (indent < caretColumn && trimmed.contains(':')) {
                    parentLineNum = lineNum
                    parentIndent = indent
                    break
                }
            }
            if (parentLineNum < 0) return null to null

            val parentLineStart = doc.getLineStartOffset(parentLineNum)
            val parentLineEnd = doc.getLineEndOffset(parentLineNum)

            val parentKeyLineRange = if (parentKeyEnabled) {
                TextRange(parentLineStart, parentLineEnd)
            } else null

            val blockRange = if (blockEnabled) {
                var blockEnd = doc.getLineEndOffset(doc.lineCount - 1)
                for (lineNum in (parentLineNum + 1) until doc.lineCount) {
                    val ls = doc.getLineStartOffset(lineNum)
                    val le = doc.getLineEndOffset(lineNum)
                    val lt = doc.getText(TextRange(ls, le))
                    val t = lt.trimStart()
                    if (t.isEmpty() || t.startsWith('#')) continue
                    val ind = lt.length - t.length
                    if (ind <= parentIndent) {
                        blockEnd = if (lineNum > 0) doc.getLineEndOffset(lineNum - 1) else ls
                        break
                    }
                }
                TextRange(parentLineStart, blockEnd)
            } else null

            return blockRange to parentKeyLineRange
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
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(listener, project)
        multicaster.addDocumentListener(listener, project)

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
