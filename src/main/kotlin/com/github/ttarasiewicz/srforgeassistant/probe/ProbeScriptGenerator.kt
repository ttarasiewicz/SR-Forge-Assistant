package com.github.ttarasiewicz.srforgeassistant.probe

import com.google.gson.Gson

/**
 * Prepares the probe execution: loads the Python probe script from resources
 * and builds a JSON config with the dynamic parameters (YAML path, dataset
 * path, overrides, project source roots).
 */
object ProbeScriptGenerator {

    /**
     * Returns the probe script content read from the bundled resource file.
     */
    fun loadScript(): String {
        return ProbeScriptGenerator::class.java
            .getResourceAsStream("/probe/probe_script.py")!!
            .bufferedReader()
            .use { it.readText() }
    }

    /**
     * Returns the visualization script content read from the bundled resource file.
     */
    fun loadVizScript(): String {
        return ProbeScriptGenerator::class.java
            .getResourceAsStream("/probe/viz_script.py")!!
            .bufferedReader()
            .use { it.readText() }
    }

    /**
     * Builds the JSON config string that the probe script reads from `sys.argv[1]`.
     */
    fun generateConfig(
        yamlFilePath: String,
        datasetPath: String,
        pipeline: DatasetNode,
        pathOverrides: Map<String, String>,
        projectPaths: List<String>,
        tensorDir: String? = null
    ): String {
        val config = mutableMapOf<String, Any?>(
            "yamlPath" to yamlFilePath,
            "datasetPath" to datasetPath,
            "pathOverrides" to pathOverrides,
            "projectPaths" to projectPaths
        )
        if (tensorDir != null) {
            config["tensorDir"] = tensorDir
        }
        return Gson().toJson(config)
    }
}
