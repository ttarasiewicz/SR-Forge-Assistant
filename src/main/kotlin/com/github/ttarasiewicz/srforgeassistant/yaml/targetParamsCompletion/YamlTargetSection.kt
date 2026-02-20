package com.github.ttarasiewicz.srforgeassistant.yaml

import com.github.ttarasiewicz.srforgeassistant.settings.MyPluginSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiParserFacade

class YamlTargetSection(private val mapping: YAMLMapping, val project: Project) {

    // Assumes the mapping has a _target key and a params key
    fun getTargetValue(): String? {
        return getTarget()?.valueText
    }

    fun getTarget(): YAMLKeyValue? {
        val targetKv = mapping.keyValues.find { it.keyText == "_target" } ?: return null
        return targetKv
    }

    fun getParams(): YAMLKeyValue? {
        val paramsKv = mapping.keyValues.find { it.keyText == "params" } ?: return null
        return paramsKv
    }

    fun getParamsMapping(): YAMLMapping? {
        return getParams()?.value as? YAMLMapping
    }

    fun getClass(): PyClass? {
        val target = getTargetValue() ?: return null
        return PythonSignatureAnalyzer.findPythonClass(project, target)
    }

    fun getRootMapping(): YAMLMapping {
        return mapping
    }

    fun isStructureValid(): Boolean{
        if (getTargetValue() == null || mapping.keyValues.size > 2) {
            return false
        }
        return true
    }

    fun generateParams() {
        val pyClass = getClass() ?: return
        val qName = PythonSignatureAnalyzer.getFullyQuallfiedName(project, pyClass) ?: return
        val exclusions = MyPluginSettings.getInstance(project).state.excludeParamsForClasses[qName] ?: emptyList()
        val classParams = PythonSignatureAnalyzer.getFunctionParameters(pyClass, "__init__")
            .filter { it.name != "self" }
            .filter { it.name !in exclusions }
            .filter { it.name != null }

        val target = getTarget() ?: return
        val indent = detectIndent(target)

        // Compute which parameters are missing (as pairs: parameter name and its default text)
        val missingParams = mutableListOf<Pair<String, String>>()
        for (param in classParams) {
            val paramName = param.name ?: continue
            if (hasParam(paramName)) continue
            val defaultText = param.defaultValue?.text ?: ""
            missingParams.add(paramName to defaultText)
        }
        if (missingParams.isEmpty()) return

        WriteCommandAction.runWriteCommandAction(project) {
            if (getParams() == null) {
                // Case 1: No "params" key exists.
                // Build a new params block with a header and all missing parameters.
                val builder = StringBuilder("\n")
                builder.append(indent).append("params:\n")
                for ((paramName, defaultText) in missingParams) {
                    builder.append(indent).append("  ").append("$paramName:")
                    if (defaultText.isNotBlank()) {
                        builder.append(" ").append(defaultText)
                    }
                    builder.append("\n")
                }
                val paramsBlockText = builder.toString()
                // Create a new YAMLKeyValue for the params block.
                val newParamsKv = createYamlKeyValueFromText(project, paramsBlockText)
                if (newParamsKv != null) {
                    val whitespace = PsiParserFacade.getInstance(project).createWhiteSpaceFromText("\n" + indent)
                    getRootMapping().addAfter(whitespace, target)
                    getRootMapping().addAfter(newParamsKv, whitespace)
                }
            } else {
                // Case 2 & 3: A "params" key exists.
                val paramsKv = getParams() ?: return@runWriteCommandAction
                var paramsMapping = paramsKv.value as? YAMLMapping

                // If the params key exists but its value is not a mapping, create a new empty mapping.
                if (paramsMapping == null) {
                    // Create an empty mapping from text, e.g. "{}"
                    val emptyMappingText = "{}"
                    val emptyMappingElement = createYamlMappingFromText(project, emptyMappingText)
                    if (emptyMappingElement != null) {
                        // Replace the existing value with the new mapping.
                        val newValue = paramsKv.value?.replace(emptyMappingElement)
                        paramsMapping = newValue as? YAMLMapping
                    }
                }
                if (paramsMapping == null) return@runWriteCommandAction

                // For each missing parameter, create a new key-value element and add it.
                for ((paramName, defaultText) in missingParams) {
                    val newParamKv = createYamlKeyValueFromText(project, "$indent$paramName: $defaultText") ?: continue
                    val whitespace = PsiParserFacade.getInstance(project).createWhiteSpaceFromText("\n  " + indent)
                    paramsMapping.add(whitespace)
                    paramsMapping.add(newParamKv)
                }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    fun hasParam(paramName: String): Boolean {
        return getParamsMapping()?.keyValues?.any { it.keyText == paramName } ?: false
    }

    fun resetParam(paramName: String) {
        // Get the Python class from the _target value.
        val pyClass = getClass() ?: return
        // Retrieve the __init__ parameters (excluding "self") from the class.
        val classParams = PythonSignatureAnalyzer.getFunctionParameters(pyClass, "__init__")
            .filter { it.name != "self" }
        // Find the parameter in the class signature matching paramName.
        val classParam = classParams.firstOrNull { it.name == paramName } ?: return

        // Get the default value from the parameter in the Python class.
        // If there is no default, we'll use an empty string.
        val defaultText = classParam.defaultValue?.text ?: ""

        // Get the current params mapping from the YAML.
        // Note: getParamsMapping() returns the YAMLMapping that is the value of the "params" key.
        val paramsMapping = getParamsMapping()
        if (paramsMapping == null) {
            // If no params block exists, nothing to reset.
            return
        }

        // Find the YAMLKeyValue in the mapping corresponding to the parameter.
        val paramKv = paramsMapping.keyValues.find { it.keyText == paramName }
        if (paramKv != null) {
            // Create a new YAMLKeyValue from text using the default value.
            // For example, if paramName is "lr" and defaultText is "1e-3", we want "lr: 1e-3".
            val newParamText = "$paramName: $defaultText"
            val newParamKv = createYamlKeyValueFromText(project, newParamText)
            if (newParamKv != null) {
                WriteCommandAction.runWriteCommandAction(project) {
                    // Replace the existing parameter PSI element with the new one.
                    paramKv.replace(newParamKv)
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                }
            }
        }
    }

    fun areParamsValid(): Boolean {
        if (!isStructureValid()) {
            return false
        }
        val pyClass = getClass()
        val params = getParamsMapping()
        val classParams = PythonSignatureAnalyzer.getFunctionParameters(pyClass, "__init__")
        val requiredParams = classParams.filter { it.defaultValue == null }
        if (params == null) {
            return requiredParams.isEmpty()
        }
        for (param in params.keyValues) {
            if (classParams.find { it.name == param.keyText } == null) {
                return false
            }
        }
        for (param in requiredParams) {
            if ((params.keyValues.find { it.keyText == param.name } == null) || (params.keyValues.find { it.keyText == param.name }?.valueText.isNullOrBlank())) {
                return false
            }
        }
        return true
    }

    private fun createYamlKeyValueFromText(project: Project, text: String): YAMLKeyValue? {
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText("dummy.yaml", YAMLFileType.YML, text)
        return PsiTreeUtil.findChildOfType(psiFile, YAMLKeyValue::class.java)
    }

    /**
     * Creates a YAMLMapping element from the given text.
     * Typically, the text should represent a YAML document with a mapping.
     */
    private fun createYamlMappingFromText(project: Project, text: String): YAMLMapping? {
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText("dummy.yaml", YAMLFileType.YML, text)
        val document = PsiTreeUtil.findChildOfType(psiFile, YAMLDocument::class.java)
        return document?.topLevelValue as? YAMLMapping
    }

    /**
     * Validate each parameter in the params mapping against the __init__ signature.
     * Returns a map of parameter YAMLKeyValue to its error message (if any).
     * If a parameter is valid, it is not included in the map.
     */
    fun getParamsValidationErrors(): Map<YAMLKeyValue, String> {
        val errors = mutableMapOf<YAMLKeyValue, String>()
        val pyClass = getClass()  // The Python class referenced by _target.
        // If we cannot resolve the class, we cannot validate parameters.
        if (pyClass == null) return errors

        // Get the __init__ parameters from the class.
        val classParams = PythonSignatureAnalyzer.getFunctionParameters(pyClass, "__init__")
        // Create a map for easier lookup by name.
        val classParamsMap = classParams.associateBy { it.name }

        // Get the params mapping from the YAML.
        val paramsMapping = getParamsMapping() ?: return errors

        // For each key-value in the params mapping:
        for (paramKv in paramsMapping.keyValues) {
            val paramName = paramKv.keyText
            val classParam = classParamsMap[paramName]
            if (classParam == null) {
                // The parameter is not in the class signature.
                errors[paramKv] = "Parameter '$paramName' is not defined in the __init__ signature."
            } else {
                // If this parameter is required (i.e. has no default value) and its value is blank:
                if (classParam.defaultValue == null && paramKv.valueText.trim().isEmpty()) {
                    // if not args or kwargs
                    if (paramName != "args" && paramName != "kwargs"){
                        errors[paramKv] = "Required parameter '$paramName' must have a value."
                    }

                }
                // Optionally, you can also check for type correctness if type hints are available.
            }
        }

        // Additionally, check if there are any required parameters missing from the params mapping.
        val paramsNamesInYaml = paramsMapping.keyValues.map { it.keyText }.toSet()
        for (classParam in classParamsMap.values) {
            if (classParam.defaultValue == null && !paramsNamesInYaml.contains(classParam.name)) {
                // You might want to flag missing parameters.
                // TODO: Correct the logic with the missing parameters error below.
//                errors[classParam] = "Required parameter '${classParam.name}' is missing."
                // Here you could add a synthetic error, or annotate the _target key.
                // For example, add an error keyed by the _target YAMLKeyValue or create a separate mechanism.
                // For now, we'll skip missing parameters.
            }
        }
        return errors
    }
}

fun PsiElement.toYamlTargetSection(): YamlTargetSection? {
    if (this is YAMLMapping) {
        return if (YamlTargetSection(this, project).isStructureValid())
            YamlTargetSection(this, project) else null
    }
    return null
}

fun PsiElement.getParentYamlTargetSection(): YamlTargetSection? {
    var current: PsiElement? = this
    while (current != null) {
        val section = current.toYamlTargetSection()
        if (section?.isStructureValid() == true) {
            return section
        }
        current = current.parent
    }
    return null
}

// A helper to detect the indent of the keyValue.
fun detectIndent(keyValue: YAMLKeyValue): String {
    // For example, use the whitespace at the beginning of the _target line.
    val text = keyValue.text
    val match = Regex("^\\s*").find(text)
    return match?.value ?: ""
}