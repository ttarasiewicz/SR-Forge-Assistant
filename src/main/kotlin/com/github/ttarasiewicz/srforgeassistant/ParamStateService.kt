// ParamStateService.kt
package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ParamStateService(val project: Project) {
    private val toggles = ConcurrentHashMap<String, Boolean>() // key: "$cls::$param" -> checked

    fun isChecked(cls: String, param: String): Boolean =
        toggles["$cls::$param"] == true

    fun toggle(cls: String, param: String) {
        val k = "$cls::$param"
        toggles[k] = !(toggles[k] ?: false)
    }
}