package com.github.ttarasiewicz.srforgeassistant.probe

import com.github.ttarasiewicz.srforgeassistant.TargetUtils
import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.types.TypeEvalContext
import org.jetbrains.yaml.psi.*

/**
 * Extracts pipeline topology from a YAML file by scanning for `_target:` values
 * that resolve to `srforge.dataset.Dataset` subclasses.
 */
object YamlPipelineParser {

    private const val DATASET_BASE_FQN = "srforge.dataset.Dataset"

    /**
     * Find all dataset definitions in the YAML file.
     * Recursively scans all mappings for `_target:` values that resolve
     * to subclasses of [DATASET_BASE_FQN].
     * Returns pairs of (display path, DatasetNode).
     */
    fun findDatasetNodes(yamlFile: YAMLFile, project: Project): List<Pair<String, DatasetNode>> {
        val results = mutableListOf<Pair<String, DatasetNode>>()
        val documents = yamlFile.documents
        if (documents.isEmpty()) return results

        val topMapping = documents[0].topLevelValue as? YAMLMapping ?: return results
        val text = yamlFile.text

        scanForDatasets(topMapping, "", text, project, results)
        return results
    }

    /**
     * Parse into a PipelineStructure.
     */
    fun parse(yamlFile: YAMLFile, project: Project): PipelineStructure? {
        val nodes = findDatasetNodes(yamlFile, project)
        if (nodes.isEmpty()) return null
        return PipelineStructure(
            datasets = nodes.associate { it },
            sourceFile = yamlFile.virtualFile?.path ?: ""
        )
    }

    /**
     * Recursively scan YAML mappings for `_target:` values pointing to Dataset subclasses.
     */
    private fun scanForDatasets(
        mapping: YAMLMapping,
        parentPath: String,
        text: String,
        project: Project,
        results: MutableList<Pair<String, DatasetNode>>
    ) {
        for (kv in mapping.keyValues) {
            val key = kv.keyText
            val path = if (parentPath.isEmpty()) key else "$parentPath.$key"
            val value = kv.value

            when (value) {
                is YAMLMapping -> {
                    val targetKv = value.keyValues.firstOrNull { it.keyText == "_target" }
                    if (targetKv != null) {
                        val fqn = (targetKv.value as? YAMLScalar)?.textValue?.trim() ?: ""
                        if (fqn.isNotEmpty() && isDatasetTarget(fqn, project)) {
                            val node = parseDatasetNode(path, value, text, project)
                            if (node != null) {
                                results.add(path to node)
                            }
                            // Don't recurse into dataset mappings — inner datasets
                            // are handled by parseDatasetNode as wrapped datasets
                            continue
                        }
                    }
                    // Not a dataset — recurse deeper
                    scanForDatasets(value, path, text, project, results)
                }
                is YAMLSequence -> {
                    // Check each item in the sequence
                    for ((i, item) in value.items.withIndex()) {
                        val itemValue = item.value
                        if (itemValue is YAMLMapping) {
                            scanForDatasets(itemValue, "$path[$i]", text, project, results)
                        }
                    }
                }
                else -> { /* scalar — skip */ }
            }
        }
    }

    /**
     * Check if a _target: FQN resolves to a subclass of srforge.dataset.Dataset.
     */
    fun isDatasetTarget(fqn: String, project: Project): Boolean {
        val cls = TargetUtils.resolveTargetClass(fqn, project) ?: return false
        return isDatasetSubclass(cls)
    }

    private fun isDatasetSubclass(cls: PyClass): Boolean {
        val visited = mutableSetOf<String>()
        val ctx = TypeEvalContext.codeAnalysis(cls.project, cls.containingFile)
        return walkMro(cls, ctx, visited)
    }

    private fun walkMro(cls: PyClass, ctx: TypeEvalContext, visited: MutableSet<String>): Boolean {
        val qn = cls.qualifiedName ?: return false
        if (!visited.add(qn)) return false

        if (qn == DATASET_BASE_FQN) return true
        // Also check torch.utils.data.Dataset as a fallback
        if (qn == "torch.utils.data.Dataset" || qn == "torch.utils.data.dataset.Dataset") return false

        for (superClass in cls.getSuperClasses(ctx)) {
            if (walkMro(superClass, ctx, visited)) return true
        }
        return false
    }

    /**
     * Parse a single dataset node from a YAML mapping containing _target:.
     */
    private fun parseDatasetNode(
        path: String,
        mapping: YAMLMapping,
        text: String,
        project: Project
    ): DatasetNode? {
        val targetKv = mapping.keyValues.firstOrNull { it.keyText == "_target" } ?: return null
        val target = (targetKv.value as? YAMLScalar)?.textValue?.trim() ?: return null
        val displayName = target.substringAfterLast('.')

        val paramsKv = mapping.keyValues.firstOrNull { it.keyText == "params" }
        val paramsMapping = paramsKv?.value as? YAMLMapping

        // Extract params as display strings
        val params = mutableMapOf<String, String>()
        paramsMapping?.keyValues?.forEach { kv ->
            val v = kv.value
            params[kv.keyText] = when (v) {
                is YAMLScalar -> v.textValue
                is YAMLMapping -> "{...}"
                is YAMLSequence -> "[...]"
                else -> v?.text?.take(50) ?: ""
            }
        }

        // Find data root
        val dataRoot = paramsMapping?.keyValues
            ?.firstOrNull { it.keyText == "root" }
            ?.let { (it.value as? YAMLScalar)?.textValue }

        // Find wrapped dataset inside params
        val wrappedDataset = findWrappedDataset(paramsMapping, path, text, project)

        // Find transforms
        val transforms = findTransforms(mapping, paramsMapping, text)

        return DatasetNode(
            path = path,
            target = target,
            displayName = displayName,
            params = params,
            transforms = transforms,
            wrappedDataset = wrappedDataset,
            dataRoot = dataRoot,
            yamlOffset = mapping.textOffset
        )
    }

    /**
     * Detect wrapped datasets: scan params for any value that is itself
     * a mapping containing _target: pointing to a Dataset subclass.
     */
    private fun findWrappedDataset(
        paramsMapping: YAMLMapping?,
        parentPath: String,
        text: String,
        project: Project
    ): DatasetNode? {
        if (paramsMapping == null) return null

        for (kv in paramsMapping.keyValues) {
            val value = kv.value as? YAMLMapping ?: continue
            val innerTarget = value.keyValues.firstOrNull { it.keyText == "_target" } ?: continue
            val fqn = (innerTarget.value as? YAMLScalar)?.textValue?.trim() ?: continue
            if (isDatasetTarget(fqn, project)) {
                return parseDatasetNode("$parentPath.params.${kv.keyText}", value, text, project)
            }
        }
        return null
    }

    /**
     * Find transforms for a dataset. Looks in:
     * 1. Direct `transforms:` key in the mapping
     * 2. `transforms:` key inside `params:`
     */
    private fun findTransforms(
        mapping: YAMLMapping,
        paramsMapping: YAMLMapping?,
        text: String
    ): List<TransformStep> {
        // Check mapping-level transforms
        val transformsKv = mapping.keyValues.firstOrNull { it.keyText == "transforms" }
            ?: paramsMapping?.keyValues?.firstOrNull { it.keyText == "transforms" }
            ?: return emptyList()

        return parseTransformsValue(transformsKv.value, text)
    }

    /**
     * Parse a transforms value, which may be:
     * - A YAML sequence (inline list of transforms)
     * - A scalar containing an interpolation reference like %{preprocessing.training}
     */
    private fun parseTransformsValue(value: YAMLValue?, text: String): List<TransformStep> {
        if (value == null) return emptyList()

        when (value) {
            is YAMLSequence -> return parseTransformSequence(value)
            is YAMLScalar -> {
                val scalarText = value.textValue.trim()
                // Check for interpolation reference
                val interpMatch = Regex("""^[%$]\{([^}]+)}$""").matchEntire(scalarText)
                if (interpMatch != null) {
                    val refPath = interpMatch.groupValues[1].trim()
                    // Try to resolve the reference via PSI navigation
                    val resolved = resolveInterpolationToPsi(refPath, value)
                    if (resolved is YAMLSequence) {
                        return parseTransformSequence(resolved)
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Parse a YAML sequence of transform definitions.
     */
    private fun parseTransformSequence(sequence: YAMLSequence): List<TransformStep> {
        val steps = mutableListOf<TransformStep>()
        for ((i, item) in sequence.items.withIndex()) {
            val itemMapping = item.value as? YAMLMapping ?: continue
            val step = parseTransformStep(i, itemMapping)
            if (step != null) steps.add(step)
        }
        return steps
    }

    /**
     * Parse a single transform step from a YAML mapping.
     * Handles two patterns:
     * 1. Direct: `_target: Foo, params: {...}, io: {...}`
     * 2. Wrapped: `transform: {_target: Foo, params: {...}}, apply_to: [...], io: {...}`
     */
    private fun parseTransformStep(index: Int, mapping: YAMLMapping): TransformStep? {
        // Pattern 1: Direct _target
        var targetMapping = mapping
        val hasDirectTarget = mapping.keyValues.any { it.keyText == "_target" }

        if (!hasDirectTarget) {
            // Pattern 2: Nested under "transform:" key
            val transformKv = mapping.keyValues.firstOrNull { it.keyText == "transform" }
            targetMapping = transformKv?.value as? YAMLMapping ?: return null
            if (targetMapping.keyValues.none { it.keyText == "_target" }) return null
        }

        val targetKv = targetMapping.keyValues.firstOrNull { it.keyText == "_target" } ?: return null
        val target = (targetKv.value as? YAMLScalar)?.textValue?.trim() ?: return null
        val displayName = target.substringAfterLast('.')

        // Extract params
        val params = mutableMapOf<String, String>()
        val paramsKv = targetMapping.keyValues.firstOrNull { it.keyText == "params" }
        (paramsKv?.value as? YAMLMapping)?.keyValues?.forEach { kv ->
            params[kv.keyText] = when (val v = kv.value) {
                is YAMLScalar -> v.textValue
                else -> v?.text?.take(50) ?: ""
            }
        }

        // IO raw text (from the outer mapping if pattern 2)
        val ioKv = mapping.keyValues.firstOrNull { it.keyText == "io" }
            ?: targetMapping.keyValues.firstOrNull { it.keyText == "io" }
        val ioRaw = ioKv?.value?.text?.take(200)

        return TransformStep(
            index = index,
            target = target,
            displayName = displayName,
            params = params,
            ioRaw = ioRaw,
            yamlOffset = mapping.textOffset
        )
    }

    /**
     * Resolve an interpolation reference (e.g., "preprocessing.training") to the
     * PSI node it points to, by walking the YAML document structure.
     */
    private fun resolveInterpolationToPsi(path: String, context: YAMLScalar): YAMLValue? {
        val file = context.containingFile as? YAMLFile ?: return null
        val documents = file.documents
        if (documents.isEmpty()) return null
        val topMapping = documents[0].topLevelValue as? YAMLMapping ?: return null

        val segments = path.split('.')
        var current: YAMLValue = topMapping

        for (segment in segments) {
            when (current) {
                is YAMLMapping -> {
                    val kv = current.keyValues.firstOrNull { it.keyText == segment }
                        ?: return null
                    current = kv.value ?: return null
                }
                is YAMLSequence -> {
                    val idx = segment.toIntOrNull() ?: return null
                    val items = current.items
                    if (idx < 0 || idx >= items.size) return null
                    current = items[idx].value ?: return null
                }
                else -> return null
            }
        }
        return current
    }
}
