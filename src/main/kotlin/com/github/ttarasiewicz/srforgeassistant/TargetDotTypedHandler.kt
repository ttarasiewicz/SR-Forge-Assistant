package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Triggers completion auto-popup when the user types '.' inside a `_target:` YAML value.
 * Without this, IntelliJ only auto-populates on alphanumeric characters in YAML,
 * so typing a dot after a package name would close the popup with no way to see child packages.
 */
class TargetDotTypedHandler : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '.') return Result.CONTINUE

        val offset = editor.caretModel.offset
        if (offset == 0) return Result.CONTINUE

        // Re-parse at the character just before the caret (the dot we just typed)
        val element = file.findElementAt(offset - 1) ?: return Result.CONTINUE
        if (TargetUtils.isTargetValue(element)) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }
}
