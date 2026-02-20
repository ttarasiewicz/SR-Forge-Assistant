package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import org.jetbrains.yaml.psi.YAMLScalar


class YamlTargetReference(element: YAMLScalar) : PsiReferenceBase<YAMLScalar>(element) {
    override fun resolve(): PsiElement? {
        val className = element.text.trim('"', '\'') // Extract class name
        val project = element.project

        // Find the corresponding Python class
        return PythonSignatureAnalyzer.findPythonClass(project, className)
    }

    override fun getVariants(): Array<Any> {
        return emptyArray() // No auto-complete yet, but we can add it later
    }
}

