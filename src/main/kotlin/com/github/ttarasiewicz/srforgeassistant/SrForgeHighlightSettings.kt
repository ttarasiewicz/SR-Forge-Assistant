package com.github.ttarasiewicz.srforgeassistant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import java.awt.Color

@State(
    name = "SrForgeHighlightSettings",
    storages = [Storage("SrForgeAssistant.xml")]
)
@Service(Service.Level.APP)
class SrForgeHighlightSettings : PersistentStateComponent<SrForgeHighlightSettings.SettingsState> {

    data class SettingsState(
        // Feature toggles
        var targetCompletionEnabled: Boolean = true,
        var targetNavigationEnabled: Boolean = true,
        var targetDocumentationEnabled: Boolean = true,
        var interpolationCompletionEnabled: Boolean = true,
        var interpolationFoldingEnabled: Boolean = true,
        var paramStubsEnabled: Boolean = true,

        // Scope highlighting
        var blockEnabled: Boolean = true,
        var parentKeyEnabled: Boolean = true,
        var parentKeyFontStyle: Int = java.awt.Font.BOLD,
        var blockLightColor: Int = 0xF8FAFF,
        var blockDarkColor: Int = 0x2C2E33,
        var parentLightColor: Int = 0xEDF2FC,
        var parentDarkColor: Int = 0x313438,

        // Interpolation folding
        var foldOnFileOpen: Boolean = true,
        var autoCollapseOnCaretExit: Boolean = true,
        var foldPlaceholderMaxLength: Int = 60,

        // Pipeline Probe
        var probeTimeoutSeconds: Int = 120
    )

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState
    override fun loadState(state: SettingsState) {
        myState = state
    }

    val blockLight: Color get() = Color(myState.blockLightColor)
    val blockDark: Color get() = Color(myState.blockDarkColor)
    val parentLight: Color get() = Color(myState.parentLightColor)
    val parentDark: Color get() = Color(myState.parentDarkColor)

    companion object {
        @JvmStatic
        fun getInstance(): SrForgeHighlightSettings =
            ApplicationManager.getApplication().getService(SrForgeHighlightSettings::class.java)

        val TOPIC: Topic<SettingsListener> =
            Topic.create("SrForge Highlight Settings Changed", SettingsListener::class.java)
    }

    fun interface SettingsListener {
        fun settingsChanged()
    }
}
