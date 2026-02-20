package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Paths
import com.google.gson.Gson
import com.google.gson.JsonObject

object ClassRegistry {
    private val classRegistry: MutableMap<String, String> = mutableMapOf()
    private val moduleRegistry: MutableMap<String, List<String>> = mutableMapOf()

    // Tracks which project we loaded & the timestamp
    private var lastLoadedProjectPath: String? = null
    private var lastModifiedTime: Long = -1

    fun loadRegistry(project: Project) {
        val projectBasePath = project.basePath ?: return
        val registryPath = Paths.get(projectBasePath, "metadata", "class_registry.json")
        val file = registryPath.toFile()

        if (!file.exists()) {
            println("Class registry file not found: $registryPath")
            return
        }

        try {
            // Check if we really need to reload
            val newModifiedTime = file.lastModified()
            if (projectBasePath == lastLoadedProjectPath && newModifiedTime == lastModifiedTime) {
                // No changes since last load
                return
            }

            // Clear old data
            classRegistry.clear()
            moduleRegistry.clear()

            // Actually load JSON
            val json = Files.readString(registryPath)
            val jsonObject = Gson().fromJson(json, JsonObject::class.java)

            // by_class
            val byClassObj = jsonObject.getAsJsonObject("by_class")
            byClassObj?.let {
                for ((key, value) in it.entrySet()) {
                    classRegistry[key] = value.asString
                }
            }

            // by_module array of objects
            val byModuleArray = jsonObject.getAsJsonArray("by_module") ?: return
            for (entry in byModuleArray) {
                val entryObj = entry.asJsonObject
                for ((moduleName, classListJson) in entryObj.entrySet()) {
                    val classList = classListJson.asJsonArray.map { it.asString }
                    moduleRegistry[moduleName] = classList
                }
            }

            // Update tracking
            lastLoadedProjectPath = projectBasePath
            lastModifiedTime = newModifiedTime

            println("Class registry reloaded. Last Modified: $newModifiedTime")

        } catch (e: Exception) {
            println("Error loading class registry: ${e.message}")
        }
    }

    // Example usage: check if a class is in 'by_class'
    fun isRegistered(project: Project, className: String): Boolean {
        ensureLoaded(project)
        return classRegistry.containsKey(className)
    }

    // Example usage: retrieve module for a given class
    fun getModuleForClass(project: Project, className: String): String? {
        ensureLoaded(project)
        return classRegistry[className]
    }

    fun getAllClasses(project: Project): Set<String> {
        ensureLoaded(project)
        return classRegistry.keys  // e.g. setOf("Preprocessor", "Multiply", "Divide", ...)
    }

    fun getAllClassesMap(project: Project): Map<String, String> {
        ensureLoaded(project)
        return classRegistry
    }

    private fun ensureLoaded(project: Project) {
        if (classRegistry.isEmpty() && moduleRegistry.isEmpty()) {
            loadRegistry(project)
        } else {
            // Check if file changed since last load
            loadRegistry(project)
        }
    }
}
