package com.github.ttarasiewicz.srforgeassistant.yaml

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.jetbrains.python.psi.PyClass

@State(name = "ParamsRegistryService", storages = [Storage("paramsRegistry.xml")])
@Service(Service.Level.PROJECT)
class ParamsRegistryService(private val project: com.intellij.openapi.project.Project) : PersistentStateComponent<ParamsRegistryState> {

    private var state = ParamsRegistryState()

    override fun getState(): ParamsRegistryState = state

    override fun loadState(state: ParamsRegistryState) {
        this.state = state
    }

    // Retrieve defaults for a given class (if any)
    fun getDefaultsForClass(pyClass: PyClass): Map<String, String>? {
        val qName = PythonSignatureAnalyzer.getFullyQuallfiedName(project, pyClass)
        return state.defaults[qName]
    }

    // Update the defaults for a given class
    fun updateDefaultsForClass(className: String, defaults: Map<String, String>) {
        state.defaults[className] = defaults.toMutableMap()
    }
}

data class ParamsRegistryState(
    var defaults: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
)


