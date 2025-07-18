package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.YAMLTokenTypes

class YamlTargetCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            // We look for YAML *scalar text* that is a child of a YAMLKeyValue with `_target` as the key
            PlatformPatterns.psiElement(YAMLTokenTypes.TEXT)
                .withSuperParent(2, PlatformPatterns.psiElement(YAMLKeyValue::class.java).withName("_target")),
            YamlTargetCompletionProvider()
        )
    }
}