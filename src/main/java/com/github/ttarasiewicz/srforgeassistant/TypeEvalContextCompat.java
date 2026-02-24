package com.github.ttarasiewicz.srforgeassistant;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.psi.types.TypeEvalContext;

/**
 * Java compatibility shim for {@link TypeEvalContext#codeAnalysis}.
 * <p>
 * In PyCharm 2026+ the class was converted from Java to Kotlin, moving static
 * factory methods into a companion object. Kotlin callers compile to
 * {@code TypeEvalContext.Companion.codeAnalysis(...)}, which doesn't exist in
 * older versions where it's a plain Java static method. This Java helper always
 * compiles to {@code invokestatic}, which works in both cases.
 */
public final class TypeEvalContextCompat {
    private TypeEvalContextCompat() {}

    public static TypeEvalContext codeAnalysis(Project project, PsiFile file) {
        return TypeEvalContext.codeAnalysis(project, file);
    }
}
