package com.github.ttarasiewicz.srforgeassistant.probe

/**
 * Information about a single field in an Entry at a particular pipeline step.
 */
data class FieldSnapshot(
    val key: String,
    val pythonType: String,
    val shape: String?,
    val dtype: String?,
    val minValue: String?,
    val maxValue: String?,
    val meanValue: String?,
    val stdValue: String?,
    val preview: String?,
    val sizeBytes: Long?,
    val children: List<FieldSnapshot>? = null
)

/**
 * The state of an Entry after a particular pipeline step.
 */
data class EntrySnapshot(
    val stepLabel: String,
    val stepIndex: Int,
    val fields: List<FieldSnapshot>,
    val isBatched: Boolean,
    val errorMessage: String? = null,
    val errorTraceback: String? = null
)

enum class FieldDiffStatus {
    ADDED, REMOVED, MODIFIED, UNCHANGED
}

data class FieldDiff(
    val key: String,
    val status: FieldDiffStatus,
    val before: FieldSnapshot?,
    val after: FieldSnapshot?,
    val childDiffs: List<FieldDiff>? = null
)

/**
 * One level of a nested pipeline (one dataset with its transforms).
 */
data class DatasetProbeResult(
    val datasetName: String,
    val datasetTarget: String,
    val snapshots: List<EntrySnapshot>,
    val innerResult: DatasetProbeResult?
)

/**
 * Full result from a probe execution.
 */
data class ProbeExecutionResult(
    val success: Boolean,
    val result: DatasetProbeResult?,
    val errorMessage: String?,
    val errorTraceback: String?,
    val executionTimeMs: Long
)
