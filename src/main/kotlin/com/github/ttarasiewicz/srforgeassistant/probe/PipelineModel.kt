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
 * A dataset node in the pipeline.
 *
 * Wrapping semantics:
 *  - `wrappedDataset` — a single-child wrapper (e.g. `PatchedDataset` whose
 *    `params.dataset` is the inner dataset). Always traversed during probing.
 *  - `branches` — a multi-child composite (e.g. `ConcatDataset` whose
 *    `params.datasets` is a list of inner datasets). At runtime only one
 *    branch produces a given sample, so the probe walks exactly one — the
 *    user picks which index in the selector dialog.
 *
 * Both can coexist on the same node in principle. In practice a node has
 * either a single wrap or a branch list, never both.
 */
data class DatasetNode(
    val path: String,
    val target: String,
    val displayName: String,
    val params: Map<String, String>,
    val transforms: List<TransformStep>,
    val wrappedDataset: DatasetNode?,
    val branches: List<DatasetNode> = emptyList(),
    val branchesParamKey: String? = null,
    val dataRoot: String?,
    val cacheDir: String? = null,
    val yamlOffset: Int
)

/**
 * The full pipeline extracted from the YAML config.
 */
data class PipelineStructure(
    val datasets: Map<String, DatasetNode>,
    val sourceFile: String
)
