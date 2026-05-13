package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import javax.swing.JPanel

/**
 * The kinds of blocks the pipeline-probe visualization renders. Each kind
 * bundles its visual data for [LegacyProbeChrome] (the polished chrome paints
 * the timeline thread + node marker and doesn't read from these values).
 *
 * Future display modes can either reuse these legacy colours, ignore them, or
 * add their own data to the enum — call sites stay the same: they construct
 * `PipelineBlock(BlockKind.X)` and the active [ProbeChrome] decides how to paint.
 */
enum class BlockKind(
    val legacyBg: JBColor,
    val legacyBorder: JBColor? = null,
) {
    DATASET(
        legacyBg = JBColor(Color(0x1976D2), Color(0x1565C0)),
    ),
    STEP(
        legacyBg = JBColor(Color.WHITE, Color(0x3C3F41)),
        legacyBorder = JBColor(Color(0xD0D0D0), Color(0x555555)),
    ),
    CACHE(
        legacyBg = JBColor(Color(0xE0F2F1), Color(0x1B3B36)),
        legacyBorder = JBColor(Color(0x80CBC4), Color(0x00897B)),
    ),
    ERROR(
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

    /**
     * Optional per-block pulse animation (e.g. error halo loop or one-shot
     * cache-hit flash). Set by chrome microinteractions; null otherwise.
     */
    var pulse: BlockPulse? = null
        internal set

    val extraBottom: Int = chrome.blockExtraBottom(kind)
    val extraLeft: Int = chrome.blockExtraLeft(kind)

    /**
     * Step index displayed inside the polished timeline's numbered node marker
     * (e.g., "1", "2", ...). Set by callers that emit step/error blocks.
     * Other kinds (dataset, cache, top-level error) leave it null.
     */
    var stepIndex: Int? = null

    /**
     * YAML source offset this block's "Go to" button should navigate to.
     * Null when the block has no corresponding YAML location (e.g. a
     * top-level error before any dataset/transform was parsed).
     */
    var goToYamlOffset: Int? = null

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
