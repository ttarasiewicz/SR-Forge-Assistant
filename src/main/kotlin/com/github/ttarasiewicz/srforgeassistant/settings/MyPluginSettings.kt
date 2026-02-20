package com.github.ttarasiewicz.srforgeassistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

data class MyPluginSettingsState(
    var projectRootDir: String = "core", // the default root directory for project classes
    var excludeParamsForClasses: MutableMap<String, MutableList<String>> = mutableMapOf("torch.optim.adam.Adam" to mutableListOf("params")) // list of parameter names to disable autocompletion for each class
)

@State(name = "SR-Forge Assistant Settings", storages = [Storage("SRForgeAssistantSettings.xml")])
@Service(Service.Level.PROJECT)
class MyPluginSettings(private val project: com.intellij.openapi.project.Project) : PersistentStateComponent<MyPluginSettingsState> {

    private var myState = MyPluginSettingsState()

    override fun getState(): MyPluginSettingsState = myState

    override fun loadState(state: MyPluginSettingsState) {
        myState = state
    }

    companion object {
        fun getInstance(project: com.intellij.openapi.project.Project): MyPluginSettings {
            return project.getService(MyPluginSettings::class.java) // error: "project" is unresolved
        }
    }

    fun addExclusion(className: String, paramName: String) {
        val currentExclusions = myState.excludeParamsForClasses.getOrPut(className) { mutableListOf() }
        if (!currentExclusions.contains(paramName)) {
            currentExclusions.add(paramName)
        }
    }

    fun resetClassExclusions(className: String) {
        myState.excludeParamsForClasses.remove(className)
    }
}