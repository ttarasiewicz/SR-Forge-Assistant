package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import javax.swing.JPanel

/**
 * The kinds of blocks the pipeline-probe visualization renders. Each kind
 * bundles its visual specification for every display mode that needs one:
 *  - [polishedStyle] — gradient + shadow + accent used by [PolishedProbeChrome].
 *  - [legacyBg] / [legacyBorder] — flat fill (and optional border) used by
 *    [LegacyProbeChrome].
 *
 * Future display modes can add their own fields without touching the call
 * sites. Each call site just constructs `PipelineBlock(BlockKind.X)` and the
 * active [ProbeChrome] decides how to paint it.
 */
enum class BlockKind(
    val polishedStyle: PolishedStyle,
    val legacyBg: JBColor,
    val legacyBorder: JBColor? = null,
) {
    DATASET(
        polishedStyle = PolishedStyle(
            topColor = JBColor(Color(0x2196F3), Color(0x1976D2)),
            bottomColor = JBColor(Color(0x1565C0), Color(0x1259AE)),
            borderColor = JBColor(Color(0x0D47A1), Color(0x0A3C8C)),
            leftAccent = JBColor(Color(0x64B5F6), Color(0x90CAF9)),
        ),
        legacyBg = JBColor(Color(0x1976D2), Color(0x1565C0)),
    ),
    STEP(
        polishedStyle = PolishedStyle(
            topColor = JBColor(Color.WHITE, Color(0x42464A)),
            bottomColor = JBColor(Color(0xF2F4F7), Color(0x3A3D40)),
            borderColor = JBColor(Color(0xCFD3D8), Color(0x4E5256)),
        ),
        legacyBg = JBColor(Color.WHITE, Color(0x3C3F41)),
        legacyBorder = JBColor(Color(0xD0D0D0), Color(0x555555)),
    ),
    CACHE(
        polishedStyle = PolishedStyle(
            topColor = JBColor(Color(0xE0F7F5), Color(0x1F4A45)),
            bottomColor = JBColor(Color(0xC9EEEA), Color(0x1B3F3B)),
            borderColor = JBColor(Color(0x80CBC4), Color(0x00897B)),
            leftAccent = JBColor(Color(0x26A69A), Color(0x4DB6AC)),
        ),
        legacyBg = JBColor(Color(0xE0F2F1), Color(0x1B3B36)),
        legacyBorder = JBColor(Color(0x80CBC4), Color(0x00897B)),
    ),
    ERROR(
        polishedStyle = PolishedStyle(
            topColor = JBColor(Color(0xFFEFF1), Color(0x4A2024)),
            bottomColor = JBColor(Color(0xFFDDE0), Color(0x3D1B1F)),
            borderColor = JBColor(Color(0xEF9A9A), Color(0xC62828)),
            leftAccent = JBColor(Color(0xE53935), Color(0xEF5350)),
        ),
        legacyBg = JBColor(Color(0xFFEBEE), Color(0x3B1B1B)),
        legacyBorder = JBColor(Color(0xEF9A9A), Color(0xC62828)),
    ),
}

/**
 * Pipeline block container. Hosts the dataset/step/cache/error chrome but
 * delegates *every* visual decision to [chrome]. The block itself owns no
 * paint logic — it captures the chrome at construction time, calls
 * `chrome.installBlock` when attached to the UI, and `chrome.uninstallBlock`
 * when detached. This lets the host swap chromes by tearing down and
 * recreating blocks; live blocks keep their original chrome until removed.
 *
 * [extraBottom] is the chrome-specific extra bottom padding the caller must
 * fold into its own border (so the drop shadow has somewhere to render).
 * In legacy chrome it is 0; in polished it accounts for the shadow.
 */
open class PipelineBlock(val kind: BlockKind) : JPanel() {

    /** Chrome bound at construction; never reassigned. */
    val chrome: ProbeChrome = ProbeChrome.current

    /**
     * Per-block animator created by [PolishedProbeChrome]; remains null in
     * [LegacyProbeChrome]. Mutated only via `installBlock`/`uninstallBlock`.
     */
    var animator: BlockAnimator? = null
        internal set

    val extraBottom: Int = chrome.blockExtraBottom(kind)

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun addNotify() {
        super.addNotify()
        chrome.installBlock(this)
    }

    override fun removeNotify() {
        chrome.uninstallBlock(this)
        super.removeNotify()
    }

    final override fun paintComponent(g: Graphics) {
        chrome.paintBlockBackground(this, g)
    }

    final override fun paint(g: Graphics) {
        chrome.paintBlock(this, g) { super.paint(it) }
    }
}
