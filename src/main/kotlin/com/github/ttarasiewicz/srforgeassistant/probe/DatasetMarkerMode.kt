package com.github.ttarasiewicz.srforgeassistant.probe

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.editor.Editor
import java.awt.AWTEvent
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.impl.EditorMouseHoverPopupControl
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Interactive "pick a dataset to probe" overlay.
 *
 * For every Dataset-typed `_target:` value in the YAML editor we render two
 * visual cues: a soft pulsing gold dot to the left of the value and a gold
 * dotted underline under it. Hovering a marker stops the pulse on that
 * marker, solidifies its underline, switches the cursor to a hand, and fires
 * [onHover] — which the panel uses to draw the path-trace overlay from this
 * dataset down to its deepest leaf. Clicking a marker fires [onPick] and
 * tears the mode down. Esc, clicking outside any marker, or losing editor
 * focus deactivates the mode silently via [onExit].
 *
 * The mode owns its own animation timer and renderer; deactivation cleans
 * up every listener it attached.
 */
class DatasetMarkerMode(
    private val editor: Editor,
    val markers: List<Marker>,
    private val onHover: (DatasetNode?) -> Unit,
    private val onPick: (DatasetNode, Map<String, Int>) -> Unit,
    private val onExit: () -> Unit,
    private val onBranchChange: (DatasetNode) -> Unit = {},
) {

    data class Marker(val node: DatasetNode, val valueRange: TextRange)

    /**
     * Live branch choices accumulated while the user scrolls the wheel over
     * composite markers. Each entry maps a composite's path key (the same
     * format the probe script uses to look it up) to the picked branch
     * index. Passed back to the probe by the panel when the user clicks.
     */
    val branchChoices: MutableMap<String, Int> = mutableMapOf()

    private var highlighter: RangeHighlighter? = null
    private var timer: Timer? = null
    private var mouseListener: EditorMouseListener? = null
    private var mouseMotionListener: EditorMouseMotionListener? = null
    private var wheelDispatcher: IdeEventQueue.NonLockedEventDispatcher? = null
    private var keyDispatcher: IdeEventQueue.NonLockedEventDispatcher? = null
    private var visibleAreaListener: com.intellij.openapi.editor.event.VisibleAreaListener? = null
    private var startTime: Long = 0L
    private var hoverIndex: Int = -1
    private var active: Boolean = false

    // The editor's existing header (if any) is saved here on activate and
    // restored on deactivate so we don't trample built-in headers like the
    // breadcrumb bar or a Find toolbar.
    private var savedHeader: javax.swing.JComponent? = null

    /**
     * Install renderers + listeners. If [markers] is empty, fires onExit
     * immediately without doing anything.
     */
    fun activate() {
        if (markers.isEmpty()) {
            onExit()
            return
        }
        if (active) return
        active = true
        startTime = System.currentTimeMillis()

        // Stop IntelliJ from showing its documentation popup while the user
        // is hovering markers — it would otherwise cover the path overlay.
        EditorMouseHoverPopupControl.disablePopups(editor)

        // Install instruction strip at the top of the editor so the actions
        // are unmissable. Saves the existing header so we can restore it.
        savedHeader = editor.headerComponent
        editor.headerComponent = buildInstructionPanel()

        // Span the highlighter across the whole document so the renderer is
        // invoked whenever any part of the editor is visible — that way the
        // dim overlay tracks every line as the user scrolls.
        val docLen = editor.document.textLength
        highlighter = editor.markupModel.addRangeHighlighter(
            0, docLen,
            HighlighterLayer.LAST + 90,
            null,
            HighlighterTargetArea.EXACT_RANGE,
        ).also { it.customRenderer = MarkerRenderer() }

        mouseMotionListener = object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                setHoverIndex(markerIndexAt(e.mouseEvent.point))
            }
        }.also { editor.addEditorMouseMotionListener(it) }

        // After a scroll the cursor stays put in screen space, but the marker
        // under it changes (because the editor content slid). Re-evaluate the
        // hover from the current mouse position whenever the visible area
        // updates, so the cursor/highlight don't get stuck on a marker that's
        // no longer under the pointer.
        visibleAreaListener = com.intellij.openapi.editor.event.VisibleAreaListener {
            setHoverIndex(markerIndexAtCurrentMouse())
        }.also { editor.scrollingModel.addVisibleAreaListener(it) }

        mouseListener = object : EditorMouseListener {
            override fun mousePressed(e: EditorMouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e.mouseEvent)) return
                val idx = markerIndexAt(e.mouseEvent.point)
                if (idx >= 0) {
                    // Consume so the editor doesn't move the caret on the way out.
                    e.consume()
                    val picked = markers[idx].node
                    // Snapshot the choices BEFORE deactivate() runs onExit (which
                    // typically nulls the panel's reference to this mode).
                    val pickedChoices = branchChoices.toMap()
                    deactivate()
                    onPick(picked, pickedChoices)
                } else {
                    // Click outside any marker — exit silently.
                    deactivate()
                }
            }
        }.also { editor.addEditorMouseListener(it) }

        // Esc-to-exit: intercept the key event at the IDE level before the
        // editor's own bindings (clear selection, dismiss intentions, etc.)
        // consume it. Returning true from the dispatcher suppresses further
        // dispatch — one Esc press exits the mode, exactly.
        keyDispatcher = object : IdeEventQueue.NonLockedEventDispatcher {
            override fun dispatch(e: AWTEvent): Boolean {
                if (active &&
                    e is KeyEvent &&
                    e.id == KeyEvent.KEY_PRESSED &&
                    e.keyCode == KeyEvent.VK_ESCAPE
                ) {
                    deactivate()
                    return true
                }
                return false
            }
        }.also { IdeEventQueue.getInstance().addDispatcher(it, null as com.intellij.openapi.Disposable?) }

        // Mouse wheel handling lives at the IDE event-queue level: when the
        // cursor is over a composite marker with >=2 branches we consume the
        // event and cycle the branch; otherwise we return false to let the
        // editor's normal scroll dispatch run. Attaching a regular
        // MouseWheelListener to the content component blocks the editor's
        // scroll dispatcher even without consume(), so it's not an option.
        wheelDispatcher = object : IdeEventQueue.NonLockedEventDispatcher {
            override fun dispatch(e: AWTEvent): Boolean {
                if (!active || e !is MouseWheelEvent) return false
                return handleWheel(e)
            }
        }.also { IdeEventQueue.getInstance().addDispatcher(it, null as com.intellij.openapi.Disposable?) }

        timer = Timer(33) { editor.contentComponent.repaint() }.also { it.start() }
    }

    /**
     * Return `true` only if the wheel event's screen position lands on a
     * composite-marker with more than one branch — in that case we cycle the
     * active branch and suppress editor scroll. Anything else (off-editor,
     * not on a marker, leaf marker, or single-branch "composite") returns
     * `false` so the editor's own wheel dispatcher scrolls normally.
     *
     * Uses screen coordinates because `MouseWheelEvent.source` is usually the
     * scroll pane / viewport rather than the editor's content component, so
     * `isDescendingFrom`-style filtering doesn't work reliably.
     */
    private fun handleWheel(e: MouseWheelEvent): Boolean {
        val cc = editor.contentComponent
        if (!cc.isShowing) return false
        val ccScreen = try {
            cc.locationOnScreen
        } catch (_: Throwable) {
            return false
        }
        val screen = try {
            e.locationOnScreen
        } catch (_: Throwable) {
            return false
        }
        if (screen.x < ccScreen.x || screen.x >= ccScreen.x + cc.width) return false
        if (screen.y < ccScreen.y || screen.y >= ccScreen.y + cc.height) return false
        val pt = java.awt.Point(screen.x - ccScreen.x, screen.y - ccScreen.y)

        val idx = markerIndexAt(pt)
        if (idx < 0) return false
        val node = markers[idx].node
        if (node.branches.size < 2) return false

        val key = "${node.path}.params.${node.branchesParamKey ?: "datasets"}"
        val n = node.branches.size
        val current = branchChoices[key] ?: 0
        val direction = if (e.wheelRotation > 0) 1 else -1
        val newIdx = ((current + direction) % n + n) % n
        if (newIdx == current) return true  // same branch — still consume to avoid scroll
        branchChoices[key] = newIdx
        onBranchChange(node)
        return true
    }

    /** Tear down listeners, renderer, and timer. Safe to call multiple times. */
    fun deactivate() {
        if (!active) return
        active = false

        EditorMouseHoverPopupControl.enablePopups(editor)

        timer?.stop()
        timer = null
        highlighter?.let {
            try {
                editor.markupModel.removeHighlighter(it)
            } catch (_: Throwable) {
                // editor may have been disposed
            }
        }
        highlighter = null
        editor.headerComponent = savedHeader
        savedHeader = null
        editor.contentComponent.cursor = Cursor.getDefaultCursor()
        mouseListener?.let { editor.removeEditorMouseListener(it) }
        mouseListener = null
        mouseMotionListener?.let { editor.removeEditorMouseMotionListener(it) }
        mouseMotionListener = null
        visibleAreaListener?.let { editor.scrollingModel.removeVisibleAreaListener(it) }
        visibleAreaListener = null
        wheelDispatcher?.let { IdeEventQueue.getInstance().removeDispatcher(it) }
        wheelDispatcher = null
        keyDispatcher?.let { IdeEventQueue.getInstance().removeDispatcher(it) }
        keyDispatcher = null
        hoverIndex = -1
        onExit()
    }

    /**
     * Centralised hover-state mutation: keeps cursor, callback firing, and
     * repaint in sync. Both mouseMoved and the scroll listener route through
     * this so the cursor never sticks on a marker that's no longer under the
     * pointer.
     */
    private fun setHoverIndex(idx: Int) {
        if (idx == hoverIndex) return
        hoverIndex = idx
        editor.contentComponent.cursor = if (idx >= 0)
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        else
            Cursor.getDefaultCursor()
        onHover(if (idx >= 0) markers[idx].node else null)
        editor.contentComponent.repaint()
    }

    /**
     * Read the current screen-space cursor position and translate it into a
     * marker index in the editor's content component, or -1 if the cursor is
     * outside the editor / off any marker.
     */
    private fun markerIndexAtCurrentMouse(): Int {
        val pi = java.awt.MouseInfo.getPointerInfo() ?: return -1
        val cc = editor.contentComponent
        if (!cc.isShowing) return -1
        val ccScreen = try {
            cc.locationOnScreen
        } catch (_: Throwable) {
            return -1
        }
        val mouse = pi.location
        if (mouse.x < ccScreen.x || mouse.x >= ccScreen.x + cc.width) return -1
        if (mouse.y < ccScreen.y || mouse.y >= ccScreen.y + cc.height) return -1
        return markerIndexAt(java.awt.Point(mouse.x - ccScreen.x, mouse.y - ccScreen.y))
    }

    /**
     * Hit-test against the *screen bounding box* of each marker's underlined
     * value text, not the document offset under [pt]. Offset-based testing
     * counts trailing-whitespace clicks past end-of-text as hits, which lets
     * the user click anywhere on the `_target:` line — undesirable. We want
     * exactly the value text to be clickable.
     */
    private fun markerIndexAt(pt: Point): Int {
        val lineHeight = editor.lineHeight
        for ((i, m) in markers.withIndex()) {
            val start = editor.offsetToXY(m.valueRange.startOffset)
            val end = editor.offsetToXY(m.valueRange.endOffset)
            // Single-line values only — if the YAML wraps the target value
            // onto two lines (rare), this rejects the click. Acceptable.
            if (start.y != end.y) continue
            if (pt.x in start.x..end.x && pt.y in start.y until (start.y + lineHeight)) {
                return i
            }
        }
        return -1
    }

    // ── Renderer ─────────────────────────────────────────────────────────

    private inner class MarkerRenderer : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            if (markers.isEmpty()) return
            val g2 = (g as Graphics2D).create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val now = System.currentTimeMillis()
                val pulsePhase = ((now - startTime) % PULSE_PERIOD_MS) / PULSE_PERIOD_MS.toDouble()
                val lineHeight = editor.lineHeight

                drawDimOverlay(g2, editor, lineHeight)

                for ((i, m) in markers.withIndex()) {
                    val startVp = editor.offsetToVisualPosition(m.valueRange.startOffset)
                    val endVp = editor.offsetToVisualPosition(m.valueRange.endOffset)
                    val startXY = editor.visualPositionToXY(startVp)
                    val endXY = editor.visualPositionToXY(endVp)
                    val baselineY = startXY.y + lineHeight - 2
                    val isHovered = i == hoverIndex

                    drawUnderline(g2, startXY.x, endXY.x, baselineY, isHovered)
                    drawDot(g2, startXY.x, startXY.y + lineHeight / 2, isHovered, pulsePhase)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * Fade every visible line that doesn't contain a marker toward the
     * editor's default background colour. The fade is rendered as a
     * translucent rectangle over each non-marker line — at ~55% opacity it
     * keeps the dimmed text legible enough for context while making the
     * marker lines pop visually.
     */
    private fun drawDimOverlay(g: Graphics2D, editor: Editor, lineHeight: Int) {
        val visible = editor.scrollingModel.visibleArea
        val doc = editor.document
        val markerLines = HashSet<Int>(markers.size)
        for (m in markers) {
            val off = m.valueRange.startOffset.coerceIn(0, doc.textLength)
            markerLines.add(doc.getLineNumber(off))
        }
        val firstLine = (visible.y / lineHeight).coerceAtLeast(0)
        val lastLine = ((visible.y + visible.height) / lineHeight + 1).coerceAtMost(doc.lineCount - 1)

        val bg = editor.colorsScheme.defaultBackground
        g.color = Color(bg.red, bg.green, bg.blue, 140)
        for (line in firstLine..lastLine) {
            if (line in markerLines) continue
            val y = editor.visualLineToY(line)
            g.fillRect(visible.x, y, visible.width, lineHeight)
        }
    }

    private fun drawUnderline(
        g: Graphics2D,
        x1: Int,
        x2: Int,
        y: Int,
        isHovered: Boolean,
    ) {
        g.color = if (isHovered) HOVER_COLOR else MARKER_COLOR
        if (isHovered) {
            g.stroke = BasicStroke(2f)
        } else {
            // Dotted gold underline — short dashes, gentle.
            g.stroke = BasicStroke(
                1.3f,
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER,
                4f,
                floatArrayOf(2.5f, 3f),
                0f,
            )
        }
        g.drawLine(x1, y, x2, y)
    }

    private fun drawDot(
        g: Graphics2D,
        valueStartX: Int,
        centerY: Int,
        isHovered: Boolean,
        pulsePhase: Double,
    ) {
        // Center the dot just to the left of the value text.
        val dotR = if (isHovered) 4.0 else 3.0
        val cx = (valueStartX - 8).toDouble()

        if (isHovered) {
            // Solid bright dot when hovered (no pulse).
            g.color = HOVER_COLOR
            fillCircle(g, cx, centerY.toDouble(), dotR)
            return
        }

        // Pulsing dot otherwise: core + breathing halo.
        val haloT = pulsePhase
        val haloAlpha = ((1.0 - haloT) * 0.45).coerceIn(0.0, 1.0).toFloat()
        val haloR = dotR + haloT * 5.0

        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, haloAlpha)
        g.color = MARKER_COLOR
        g.stroke = BasicStroke(1.3f)
        val haloD = (haloR * 2.0).toInt()
        g.drawOval((cx - haloR).toInt(), (centerY - haloR).toInt(), haloD, haloD)

        g.composite = AlphaComposite.SrcOver
        g.color = MARKER_COLOR
        fillCircle(g, cx, centerY.toDouble(), dotR)
    }

    /**
     * Build the styled "Pick a dataset" strip placed at the top of the editor
     * while the mode is active. Theme-aware foreground and a warm gold tint
     * that ties visually to the path overlay.
     */
    private fun buildInstructionPanel(): javax.swing.JComponent {
        val bg = JBColor(Color(0xFFF4DD), Color(0x3C2F18))
        val borderColor = JBColor(Color(0xE6B26A), Color(0x6E5325))
        val fg = JBColor(Color(0x3E2A0A), Color(0xEFD9B0))
        val accent = JBColor(Color(0xC15B05), Color(0xFFB570))

        val html = "<html><span style='color:rgb(${accent.red},${accent.green},${accent.blue})'>" +
            "<b>● Pick a dataset to probe</b></span>" +
            "&nbsp;&nbsp;&nbsp;<b>Click</b> a marker to run from there" +
            "&nbsp;&nbsp;·&nbsp;&nbsp;<b>Scroll</b> over a composite to switch branch" +
            "&nbsp;&nbsp;·&nbsp;&nbsp;<b>Esc</b> to cancel" +
            "</html>"

        return javax.swing.JPanel(java.awt.BorderLayout()).apply {
            background = bg
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                com.intellij.util.ui.JBUI.Borders.empty(6, 14),
            )
            add(
                com.intellij.ui.components.JBLabel(html).apply {
                    foreground = fg
                },
                java.awt.BorderLayout.WEST,
            )
        }
    }

    private fun fillCircle(g: Graphics2D, cx: Double, cy: Double, r: Double) {
        val d = (r * 2.0).toInt()
        g.fillOval((cx - r).toInt(), (cy - r).toInt(), d, d)
    }

    companion object {
        private const val PULSE_PERIOD_MS = 1600L

        // Matches the path-trace gold so the visuals feel related.
        private val MARKER_COLOR = JBColor(Color(0xE67E22), Color(0xFFB570))
        private val HOVER_COLOR = JBColor(Color(0xD35400), Color(0xFFC880))
    }
}
