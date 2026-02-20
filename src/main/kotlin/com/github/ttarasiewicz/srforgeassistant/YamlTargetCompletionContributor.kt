package com.github.ttarasiewicz.srforgeassistant

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import org.jetbrains.yaml.YAMLLanguage

class YamlTargetCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(YAMLLanguage.INSTANCE),
            YamlTargetCompletionProvider()
        )
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(YAMLLanguage.INSTANCE),
            YamlParamCompletionProvider()
        )
    }
}
