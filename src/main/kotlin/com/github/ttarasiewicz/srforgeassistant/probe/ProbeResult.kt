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
    val children: List<FieldSnapshot>? = null,
    val npyPath: String? = null
)

/**
 * The state of an Entry after a particular pipeline step.
 */
data class EntrySnapshot(
    val stepLabel: String,
    val stepIndex: Int,
    val fields: List<FieldSnapshot>,
    val isBatched: Boolean,
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
 * Events emitted by the Python probe script during streaming execution.
 * Each event maps to a single UI update in the Pipeline Probe tool window.
 */
sealed class ProbeEvent {
    /** A new dataset is being probed. */
    data class DatasetStart(
        val datasetName: String,
        val datasetTarget: String,
        val datasetPath: String
    ) : ProbeEvent()

    /** Entry snapshot after a transform step (or after raw dataset load at stepIndex=0). */
    data class Snapshot(val snapshot: EntrySnapshot) : ProbeEvent()

    /** A transform step failed. */
    data class StepError(
        val stepLabel: String,
        val stepIndex: Int,
        val errorMessage: String,
        val errorTraceback: String?
    ) : ProbeEvent()

    /** Dataset instantiation failed (e.g. __init__ error). */
    data class InitError(
        val errorMessage: String,
        val errorTraceback: String?
    ) : ProbeEvent()

    /** Visual connector between inner and outer dataset blocks. */
    data class Connector(val label: String) : ProbeEvent()

    /** Outer dataset skipped because inner dataset failed. */
    data class Skipped(val reason: String) : ProbeEvent()

    /** All transforms for a dataset have been processed. */
    data class DatasetEnd(val datasetPath: String) : ProbeEvent()

    /** Probe completed (always the last event). */
    data class Complete(val executionTimeMs: Long) : ProbeEvent()

    /** Top-level fatal error before any dataset could be probed. */
    data class Error(
        val errorMessage: String,
        val errorTraceback: String?
    ) : ProbeEvent()
}
