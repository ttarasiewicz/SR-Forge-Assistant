package com.github.ttarasiewicz.srforgeassistant.probe

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.github.ttarasiewicz.srforgeassistant.SrForgeHighlightSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Runs the Python probe script using the project's Python interpreter
 * and streams parsed events to a callback on the EDT.
 */
object ProbeExecutor {

    private val TIMEOUT_MS: Int
        get() = SrForgeHighlightSettings.getInstance().state.probeTimeoutSeconds * 1000
    private const val EVENT_MARKER = "===PROBE_EVENT==="

    fun getPythonSdk(project: Project): Sdk? {
        return ProjectRootManager.getInstance(project).projectSdk
    }

    fun getPythonPath(sdk: Sdk): String? {
        return sdk.homePath
    }

    fun getProjectSourcePaths(project: Project): List<String> {
        return ProjectRootManager.getInstance(project)
            .contentSourceRoots
            .map { it.path }
    }

    /**
     * Execute the probe script and stream events to [onEvent] on the EDT.
     * This method blocks the calling thread until the process completes or times out.
     * Must be called from a background thread (e.g. inside [com.intellij.openapi.progress.Task.Backgroundable]).
     */
    fun executeStreaming(
        project: Project,
        scriptContent: String,
        configJson: String,
        indicator: ProgressIndicator?,
        onEvent: (ProbeEvent) -> Unit
    ) {
        val sdk = getPythonSdk(project)
        if (sdk == null) {
            emitOnEdt(onEvent, ProbeEvent.Error("No Python SDK configured for this project", null))
            emitOnEdt(onEvent, ProbeEvent.Complete(0))
            return
        }
        val pythonPath = getPythonPath(sdk)
        if (pythonPath == null) {
            emitOnEdt(onEvent, ProbeEvent.Error("Cannot determine Python interpreter path", null))
            emitOnEdt(onEvent, ProbeEvent.Complete(0))
            return
        }

        val scriptFile = Files.createTempFile("srforge_probe_", ".py").toFile()
        val configFile = Files.createTempFile("srforge_probe_cfg_", ".json").toFile()
        try {
            scriptFile.writeText(scriptContent, StandardCharsets.UTF_8)
            configFile.writeText(configJson, StandardCharsets.UTF_8)

            val cmd = GeneralCommandLine(pythonPath, scriptFile.absolutePath, configFile.absolutePath)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment("PYTHONDONTWRITEBYTECODE", "1")
                .withEnvironment("PYTHONIOENCODING", "utf-8")

            project.basePath?.let { cmd.withWorkDirectory(it) }

            val handler = OSProcessHandler(cmd)
            val buffer = StringBuilder()
            val receivedComplete = java.util.concurrent.atomic.AtomicBoolean(false)

            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType != ProcessOutputTypes.STDOUT) return
                    buffer.append(event.text)

                    while (true) {
                        val nl = buffer.indexOf('\n')
                        if (nl < 0) break
                        val line = buffer.substring(0, nl).trimEnd('\r')
                        buffer.delete(0, nl + 1)

                        if (line.startsWith(EVENT_MARKER)) {
                            val json = line.substring(EVENT_MARKER.length)
                            val probeEvent = parseEvent(json)
                            if (probeEvent != null) {
                                if (probeEvent is ProbeEvent.Complete) receivedComplete.set(true)
                                emitOnEdt(onEvent, probeEvent)
                            }
                        }
                    }
                }

                override fun processTerminated(event: ProcessEvent) {}
                override fun startNotified(event: ProcessEvent) {}
            })

            indicator?.text = "Running pipeline probe..."
            handler.startNotify()

            if (!handler.waitFor(TIMEOUT_MS.toLong())) {
                handler.destroyProcess()
                emitOnEdt(onEvent, ProbeEvent.Error(
                    "Probe timed out after ${TIMEOUT_MS / 1000} seconds", null
                ))
                emitOnEdt(onEvent, ProbeEvent.Complete(TIMEOUT_MS.toLong()))
            } else if (!receivedComplete.get()) {
                // Process exited but never sent a complete event — something went wrong
                val exitCode = handler.exitCode ?: -1
                emitOnEdt(onEvent, ProbeEvent.Error(
                    "Probe process exited unexpectedly (exit code: $exitCode)", null
                ))
                emitOnEdt(onEvent, ProbeEvent.Complete(0))
            }
        } catch (e: Exception) {
            emitOnEdt(onEvent, ProbeEvent.Error(
                "Failed to execute probe: ${e.message}", e.stackTraceToString()
            ))
            emitOnEdt(onEvent, ProbeEvent.Complete(0))
        } finally {
            scriptFile.delete()
            configFile.delete()
        }
    }

    private fun emitOnEdt(onEvent: (ProbeEvent) -> Unit, event: ProbeEvent) {
        ApplicationManager.getApplication().invokeLater {
            onEvent(event)
        }
    }

    // ── Event parsing ─────────────────────────────────────────────────

    private fun parseEvent(json: String): ProbeEvent? {
        return try {
            val gson = com.google.gson.Gson()
            val obj = gson.fromJson(json, JsonObject::class.java)
            val type = obj.get("type")?.asString ?: return null

            when (type) {
                "dataset_start" -> ProbeEvent.DatasetStart(
                    datasetName = obj.get("datasetName")?.asString ?: "Unknown",
                    datasetTarget = obj.get("datasetTarget")?.asString ?: "",
                    datasetPath = obj.get("datasetPath")?.asString ?: ""
                )
                "snapshot" -> {
                    val fields = (obj.getAsJsonArray("fields") ?: com.google.gson.JsonArray())
                        .map { parseFieldSnapshot(it.asJsonObject) }
                    ProbeEvent.Snapshot(EntrySnapshot(
                        stepLabel = obj.get("stepLabel")?.asString ?: "",
                        stepIndex = obj.get("stepIndex")?.asInt ?: 0,
                        fields = fields,
                        isBatched = obj.get("isBatched")?.asBoolean ?: false,
                    ))
                }
                "step_error" -> ProbeEvent.StepError(
                    stepLabel = obj.get("stepLabel")?.asString ?: "",
                    stepIndex = obj.get("stepIndex")?.asInt ?: 0,
                    errorMessage = obj.get("errorMessage")?.asString ?: "Unknown error",
                    errorTraceback = obj.get("errorTraceback")?.takeIf { !it.isJsonNull }?.asString
                )
                "init_error" -> ProbeEvent.InitError(
                    errorMessage = obj.get("errorMessage")?.asString ?: "Unknown error",
                    errorTraceback = obj.get("errorTraceback")?.takeIf { !it.isJsonNull }?.asString
                )
                "connector" -> ProbeEvent.Connector(
                    label = obj.get("label")?.asString ?: ""
                )
                "skipped" -> ProbeEvent.Skipped(
                    reason = obj.get("reason")?.asString ?: ""
                )
                "dataset_end" -> ProbeEvent.DatasetEnd(
                    datasetPath = obj.get("datasetPath")?.asString ?: ""
                )
                "complete" -> ProbeEvent.Complete(
                    executionTimeMs = obj.get("executionTimeMs")?.asLong ?: 0
                )
                "error" -> ProbeEvent.Error(
                    errorMessage = obj.get("errorMessage")?.asString ?: "Unknown error",
                    errorTraceback = obj.get("errorTraceback")?.takeIf { !it.isJsonNull }?.asString
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseFieldSnapshot(fo: JsonObject): FieldSnapshot {
        val children = if (fo.has("children") && !fo.get("children").isJsonNull) {
            fo.getAsJsonArray("children").map { parseFieldSnapshot(it.asJsonObject) }
        } else null

        return FieldSnapshot(
            key = fo.get("key")?.asString ?: "",
            pythonType = fo.get("pythonType")?.asString ?: "unknown",
            shape = fo.get("shape")?.takeIf { !it.isJsonNull }?.asString,
            dtype = fo.get("dtype")?.takeIf { !it.isJsonNull }?.asString,
            minValue = fo.get("minValue")?.takeIf { !it.isJsonNull }?.asString,
            maxValue = fo.get("maxValue")?.takeIf { !it.isJsonNull }?.asString,
            meanValue = fo.get("meanValue")?.takeIf { !it.isJsonNull }?.asString,
            stdValue = fo.get("stdValue")?.takeIf { !it.isJsonNull }?.asString,
            preview = fo.get("preview")?.takeIf { !it.isJsonNull }?.asString,
            sizeBytes = fo.get("sizeBytes")?.takeIf { !it.isJsonNull }?.asLong,
            children = children
        )
    }
}
