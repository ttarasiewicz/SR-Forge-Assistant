package com.github.ttarasiewicz.srforgeassistant.probe

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Runs the generated Python probe script using the project's Python interpreter
 * and parses the JSON output.
 */
object ProbeExecutor {

    private const val TIMEOUT_MS = 120_000
    private const val RESULT_BEGIN = "===PROBE_RESULT_BEGIN==="
    private const val RESULT_END = "===PROBE_RESULT_END==="

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

    fun execute(
        project: Project,
        scriptContent: String,
        indicator: ProgressIndicator?
    ): ProbeExecutionResult {
        val sdk = getPythonSdk(project)
            ?: return errorResult("No Python SDK configured for this project")
        val pythonPath = getPythonPath(sdk)
            ?: return errorResult("Cannot determine Python interpreter path")

        val tempFile = Files.createTempFile("srforge_probe_", ".py").toFile()
        try {
            tempFile.writeText(scriptContent, StandardCharsets.UTF_8)

            val cmd = GeneralCommandLine(pythonPath, tempFile.absolutePath)
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment("PYTHONDONTWRITEBYTECODE", "1")
                .withEnvironment("PYTHONIOENCODING", "utf-8")

            project.basePath?.let { cmd.withWorkDirectory(it) }

            val handler = CapturingProcessHandler(cmd)
            indicator?.text = "Running pipeline probe..."

            val output = handler.runProcess(TIMEOUT_MS)

            if (output.isTimeout) {
                return errorResult("Probe timed out after ${TIMEOUT_MS / 1000} seconds")
            }

            val stdout = output.stdout
            val jsonStr = extractJson(stdout)

            if (jsonStr != null) {
                return parseResultJson(jsonStr)
            }

            val stderr = output.stderr.trim()
            return errorResult(
                if (stderr.isNotEmpty()) stderr.take(500) else "Script produced no output",
                stderr
            )
        } catch (e: Exception) {
            return errorResult("Failed to execute probe: ${e.message}", e.stackTraceToString())
        } finally {
            tempFile.delete()
        }
    }

    private fun extractJson(stdout: String): String? {
        val begin = stdout.indexOf(RESULT_BEGIN)
        val end = stdout.indexOf(RESULT_END)
        if (begin < 0 || end < 0 || end <= begin) return null
        return stdout.substring(begin + RESULT_BEGIN.length, end).trim()
    }

    private fun parseResultJson(json: String): ProbeExecutionResult {
        return try {
            val gson = Gson()
            val root = gson.fromJson(json, JsonObject::class.java)

            val success = root.get("success")?.asBoolean ?: false
            val errorMessage = root.get("errorMessage")?.takeIf { !it.isJsonNull }?.asString
            val errorTraceback = root.get("errorTraceback")?.takeIf { !it.isJsonNull }?.asString
            val executionTimeMs = root.get("executionTimeMs")?.asLong ?: 0

            val result = if (success && root.has("result") && !root.get("result").isJsonNull) {
                parseDatasetProbeResult(root.getAsJsonObject("result"))
            } else null

            ProbeExecutionResult(success, result, errorMessage, errorTraceback, executionTimeMs)
        } catch (e: Exception) {
            errorResult("Failed to parse probe output: ${e.message}", json)
        }
    }

    private fun parseDatasetProbeResult(obj: JsonObject): DatasetProbeResult {
        val datasetName = obj.get("datasetName")?.asString ?: "Unknown"
        val datasetTarget = obj.get("datasetTarget")?.asString ?: ""

        val snapshots = mutableListOf<EntrySnapshot>()
        val snapshotsArr = obj.getAsJsonArray("snapshots") ?: com.google.gson.JsonArray()
        for (elem in snapshotsArr) {
            val snap = elem.asJsonObject
            val fieldsArr = snap.getAsJsonArray("fields") ?: com.google.gson.JsonArray()
            val fields = fieldsArr.map { parseFieldSnapshot(it.asJsonObject) }
            snapshots.add(EntrySnapshot(
                stepLabel = snap.get("stepLabel")?.asString ?: "",
                stepIndex = snap.get("stepIndex")?.asInt ?: 0,
                fields = fields,
                isBatched = snap.get("isBatched")?.asBoolean ?: false
            ))
        }

        val innerResult = if (obj.has("innerResult") && !obj.get("innerResult").isJsonNull) {
            parseDatasetProbeResult(obj.getAsJsonObject("innerResult"))
        } else null

        return DatasetProbeResult(datasetName, datasetTarget, snapshots, innerResult)
    }

    private fun parseFieldSnapshot(fo: JsonObject): FieldSnapshot {
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

    private fun errorResult(message: String, traceback: String? = null) = ProbeExecutionResult(
        success = false,
        result = null,
        errorMessage = message,
        errorTraceback = traceback,
        executionTimeMs = 0
    )
}
