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
        projectPaths: List<String>,
        branchChoices: Map<String, Int> = emptyMap(),
        tensorDir: String? = null
    ): String {
        // The Python side can't tell datasets from transforms in a YAML list
        // (both have `_target:`). Ship the Kotlin-side-resolved set of valid
        // recursion paths so probe_script.py only recurses into actual datasets.
        val datasetPaths = mutableSetOf<String>()
        collectDatasetPaths(pipeline, datasetPaths)

        val config = mutableMapOf<String, Any?>(
            "yamlPath" to yamlFilePath,
            "datasetPath" to datasetPath,
            "projectPaths" to projectPaths,
            "branchChoices" to branchChoices,
            "datasetPaths" to datasetPaths.toList()
        )
        if (tensorDir != null) {
            config["tensorDir"] = tensorDir
        }
        return Gson().toJson(config)
    }

    /** Walk the DatasetNode tree, collecting paths the probe is allowed to recurse into. */
    private fun collectDatasetPaths(node: DatasetNode, out: MutableSet<String>) {
        node.wrappedDataset?.let {
            out.add(it.path)
            collectDatasetPaths(it, out)
        }
        for (branch in node.branches) {
            out.add(branch.path)
            collectDatasetPaths(branch, out)
        }
    }
}
