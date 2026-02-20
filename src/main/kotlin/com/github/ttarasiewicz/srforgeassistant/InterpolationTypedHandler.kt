package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Triggers completion auto-popup when the user types `{` after `$` or `%`
 * (opening an interpolation expression), `.` inside an interpolation
 * (navigating to a child key), or `]` inside an interpolation
 * (after closing a list index like `[0]`).
 */
class InterpolationTypedHandler : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!SrForgeHighlightSettings.getInstance().state.interpolationCompletionEnabled) return Result.CONTINUE
        val name = file.virtualFile?.name ?: return Result.CONTINUE
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) return Result.CONTINUE

        val offset = editor.caretModel.offset
        val text = editor.document.text

        when (c) {
            '{' -> {
                if (offset >= 2) {
                    val prev = text[offset - 2]
                    if (prev == '$' || prev == '%') {
                        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                    }
                }
            }
            '.', ']' -> {
                if (YamlInterpolationCompletionContributor.isInsideInterpolation(text, offset)) {
                    AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                }
            }
        }
        return Result.CONTINUE
    }
}
