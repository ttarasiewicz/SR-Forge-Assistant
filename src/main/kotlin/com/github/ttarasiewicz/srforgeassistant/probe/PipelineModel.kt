package com.github.ttarasiewicz.srforgeassistant.probe

/**
 * A single transform step parsed from the YAML config.
 */
data class TransformStep(
    val index: Int,
    val target: String,
    val displayName: String,
    val params: Map<String, String>,
    val ioRaw: String?,
    val yamlOffset: Int
)

/**
 * A dataset node in the pipeline. May contain a wrapped inner dataset.
 */
data class DatasetNode(
    val path: String,
    val target: String,
    val displayName: String,
    val params: Map<String, String>,
    val transforms: List<TransformStep>,
    val wrappedDataset: DatasetNode?,
    val dataRoot: String?,
    val yamlOffset: Int
)

/**
 * The full pipeline extracted from the YAML config.
 */
data class PipelineStructure(
    val datasets: Map<String, DatasetNode>,
    val sourceFile: String
)
