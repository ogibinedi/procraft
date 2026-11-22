package io.github.artcraftandroid.procraft.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.core.ChromaticKind
import io.github.artcraftandroid.procraft.core.PerspectiveKind
import io.github.artcraftandroid.procraft.core.GlitchKind
import io.github.artcraftandroid.procraft.core.ModeButton
import io.github.artcraftandroid.procraft.core.Modes
import io.github.artcraftandroid.procraft.core.modeSwitch
import io.github.artcraftandroid.procraft.core.modeSwitchDeselects
import io.github.artcraftandroid.procraft.core.HalftoneKind
import io.github.artcraftandroid.procraft.core.NoiseKind
import io.github.artcraftandroid.procraft.core.Adjustment
import io.github.artcraftandroid.procraft.core.LiquifyKind
import io.github.artcraftandroid.procraft.core.TransformAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.*

fun CanvasViewModel.toggle(p: Panel) {
    // with a brush swiped open, its button only closes the swipe
    if (p == Panel.BRUSHES && panel == Panel.BRUSHES && swipedBrush != null) {
        swipedBrush = null
        return
    }
    feedback()
    switchModes(ModeButton.Panel(p == Panel.COLOR))
    // the colour button with the panel floating: it goes back in its place
    if (p == Panel.COLOR && floatingColorsAt != null) {
        floatingColorsAt = null
        panel = Panel.NONE
    }
    // as do the Layers and the brush library's buttons with theirs floating
    val floating = when (p) {
        Panel.LAYERS -> io.github.artcraftandroid.procraft.core.FloatingPanel.LAYERS
        Panel.BRUSHES -> io.github.artcraftandroid.procraft.core.FloatingPanel.BRUSHES
        else -> null
    }
    if (floating != null && floatingWindow(floating) != null) {
        closeFloating(floating)
        panel = Panel.NONE
    }
    panel = if (panel == p) Panel.NONE else p
    if (layersShown) refreshThumbnails()
}

/** The modes open now, as the core's [modeSwitch] takes them. */
internal fun CanvasViewModel.openModes() = Modes(
    quickshape = quickShape != null,
    filling = dropBand == DropBand.FILLING,
    recolor = recoloring,
    layerOpacity = opacityLayer != null,
    transform = transforming,
    selection = selecting,
    adjustment = adjustment != null,
)

/** [button] pressed: the modes it leaves behind end first (keeping what they made), as the core says; true if it goes on to do what it does. */
internal fun CanvasViewModel.switchModes(button: ModeButton): Boolean {
    val open = openModes()
    val change = modeSwitch(button, open)
    val end = change.end
    // the selection button closing the selection tool lets go of the selection too (with Color Fill, the fill as it closes does)
    if (modeSwitchDeselects(button, open, colorFill)) clearSelection()
    if (end.quickshape) endQuickShape()
    if (end.filling) endDropBand()
    if (end.recolor) endRecolor()
    if (end.layerOpacity) endLayerOpacity()
    if (end.transform) endTransform(true)
    if (end.selection) closeSelecting()
    if (end.adjustment) endAdjustment(true)
    return change.goesOn
}

fun CanvasViewModel.closePanels() {
    panel = Panel.NONE
    swipedBrush = null
}

/** A tap outside the open panel closes it; with a brush swiped open, only the swipe. */
fun CanvasViewModel.tapOutsidePanel() {
    if (panel == Panel.BRUSHES && swipedBrush != null) swipedBrush = null else closePanels()
}

/** Closes the open panel by fading it away, as picking something that takes the screen does. */
fun CanvasViewModel.fadePanels() {
    if (panel != Panel.NONE) panelFading = true
    panel = Panel.NONE
}

fun CanvasViewModel.undo() {
    feedback()
    // a finished text's step goes back to editing that text first, as a step of its own
    if (textInfo == null && !transforming) session?.textUndoLayer()?.let { if (resumeText(it)) return }
    // editing text, a change to its box's width goes back a step at a time, still editing
    if (textInfo != null && stepTextBox(undo = true)) return
    // undoing while editing text otherwise undoes the whole edit
    if (textInfo != null) {
        textInfo = null
        textStyleOpen = false
    }
    if (transforming) {
        transformBox = session?.transformApply(TransformAction.UNDO) ?: transformBox
        return
    }
    // recoloring, the core takes the fill under the reticle back first (after the fills queued)
    val could = canUndo
    val name = session?.let { s -> if (recoloring) pourNow { s.undo() } else s.undo() }
    if (could || name != null) {
        haptic(HapticCue.STEP)
        session?.stepNotice(true, name)?.let { showNotice(it) }
    }
    followQuickShape()
    // a model's tile chosen before comes back, with its channel's colour
    if (modelOpen) {
        followModelChannel()
        readMeshes()
    }
    // cloning, the core put the source back where it was before the stroke; Liquify undone
    // past its first step closes, and the step that kept its work undone opens it again
    followAdjustmentStep()
    refresh()
    // the thumbnails of the layers it put back (only those) read again
    if (layersShown) refreshThumbnails()
    refreshModelThumbnails()
}

/** After an undo or redo: the adjustment open as the core has it (Liquify may open or close). */
internal fun CanvasViewModel.followAdjustmentStep() {
    val was = adjustment
    if (was == null && session?.adjustmentShown()?.adjustment == null) return
    readAdjust()
    if (adjustment == null) {
        liquifyRingAt = null
        adjustMenuAt = null
    } else if (was == null) {
        // opened again: its own bar, nothing else over it
        panel = Panel.NONE
        adjustModesOpen = false
        liquifyPopup = 0
    }
}

/**
 * Shows [text] in the band under the top bar, in place of any message there; a [warning] stays longer. Of [kind]: one
 * of something gone wrong always shows, one of something done unless a plugin hides it.
 */
fun CanvasViewModel.showNotice(text: String, warning: Boolean = false, kind: io.github.artcraftandroid.procraft.core.NoticeKind = io.github.artcraftandroid.procraft.core.NoticeKind.PROBLEM) {
    if (kind != io.github.artcraftandroid.procraft.core.NoticeKind.PROBLEM && kind !in io.github.artcraftandroid.procraft.ui.Pc.noticesShown) return
    notice = Notice(text, ++notices, warning)
}

/** Shows [text], the notice of something done (copied, cleared, applied, switched on), unless a plugin hides those. */
fun CanvasViewModel.showDone(text: String, warning: Boolean = false) = showNotice(text, warning, io.github.artcraftandroid.procraft.core.NoticeKind.DONE)

/** The message under the top bar has gone. */
fun CanvasViewModel.noticeGone(n: Notice) {
    if (notice == n) notice = null
}

fun CanvasViewModel.redo() {
    feedback()
    if (transforming) return
    if (textInfo != null && stepTextBox(undo = false)) return
    // text being edited is kept first
    val editing = textInfo != null
    textInfo = null
    textStyleOpen = false
    val could = canRedo
    // a text edit redone is edited again
    val text = if (!editing) session?.textRedoLayer() else null
    // recoloring, the core steps the open fill aside and makes it again over what comes back
    val name = session?.let { s -> if (recoloring) pourNow { s.redo() } else s.redo() }
    if (could) {
        haptic(HapticCue.STEP)
        session?.stepNotice(false, name)?.let { showNotice(it) }
    }
    followQuickShape()
    if (modelOpen) followModelChannel()
    refresh()
    if (layersShown) refreshThumbnails()
    refreshModelThumbnails()
    followAdjustmentStep()
    text?.let { resumeText(it) }
}

// ---- adjustments: the shared core keeps the one open and its values (crates/core adjusting.rs); these show them ----

/** The adjustment's values as the core has them now. */
internal fun CanvasViewModel.readAdjust() {
    val a = session?.adjustmentShown() ?: return
    adjustment = a.adjustment
    pluginFilter = a.plugin
    adjustAmount = a.amount
    adjustPencil = a.pencil
    adjustPreview = a.preview
    hue50 = a.hsb.getOrElse(0) { 0.5f }
    saturation50 = a.hsb.getOrElse(1) { 0.5f }
    brightness50 = a.hsb.getOrElse(2) { 0.5f }
    balanceTone = a.balanceTone.toInt()
    balance = a.balance
    curveChannel = a.curveChannel.toInt()
    curves = a.curves.map { c -> List(c.size / 2) { Offset(c[it * 2], c[it * 2 + 1]) } }
    gradientIndex = a.gradient.toInt()
    noiseKind = a.noiseKind
    noiseScale = a.noise.getOrElse(0) { 0f }
    noiseOctaves = a.noise.getOrElse(1) { 0f }
    noiseTurbulence = a.noise.getOrElse(2) { 0.7f }
    noiseMulti = a.noiseMulti
    noiseAdded = a.noiseAdded
    bloomTransition = a.bloom.getOrElse(0) { 1f }
    bloomSize = a.bloom.getOrElse(1) { 0.3f }
    bloomBurn = a.bloom.getOrElse(2) { 0.35f }
    halftoneKind = a.halftoneKind
    chromaKind = a.chromaKind
    chromaValues = chromaValues + (a.chromaKind to a.chroma)
    glitchKind = a.glitchKind
    glitchValues = glitchValues + (a.glitchKind to a.glitch)
    liquifyKind = a.liquifyKind
    liquifySize = a.liquify.getOrElse(0) { 0.5f }
    liquifyPressure = a.liquify.getOrElse(1) { 1f }
    liquifyDistortion = a.liquify.getOrElse(2) { 0f }
    liquifyMomentum = a.liquify.getOrElse(3) { 0.8f }
    liquifyAmount = a.liquifyAmount
    liquified = a.liquified
    clonePuck = a.clonePuck?.let { windowToCanvas(Offset(it[0], it[1])) }
    perspectiveKind = a.perspectiveKind
    perspectivePuck = a.perspectivePuck?.takeIf { it.size == 4 }?.let { p ->
        val origin = windowToCanvas(Offset(p[0], p[1]))
        val ahead = windowToCanvas(Offset(p[2], p[3]))
        if (origin != null && ahead != null) origin to ahead else null
    }
}

/** Opens adjustment [a] on the current layer (keeping one already open). */
fun CanvasViewModel.startAdjustment(a: Adjustment) {
    val s = session ?: return
    if (!currentTakesChanges(adjustment = a)) return
    switchModes(ModeButton.Adjustment)
    // the list fades away rather than going at once
    if (panel == Panel.ADJUSTMENTS) panelFading = true
    panel = Panel.NONE
    if (!s.adjustmentStart(a)) return
    adjustModesOpen = false
    liquifyPopup = 0
    readAdjust()
    // cloning paints with the brush: its sliders show
    if (a == Adjustment.CLONE) readSliders()
}

/**
 * Opens the plugin's filter [filter] on the current layer. The core saves the artwork ahead of the
 * session's first one and compiles it, which isn't for the main thread.
 */
fun CanvasViewModel.startPluginFilter(filter: io.github.artcraftandroid.procraft.core.PluginInfo) {
    val s = session ?: return
    if (!currentTakesChanges()) return
    switchModes(ModeButton.Adjustment)
    if (panel == Panel.ADJUSTMENTS) panelFading = true
    panel = Panel.NONE
    val dir = artworkDir?.path
    viewModelScope.launch {
        val why = withContext(Dispatchers.IO) { s.pluginFilterStart(plugins, filter.id, dir) }
        if (why != null) showNotice(why, warning = true)
        adjustModesOpen = false
        liquifyPopup = 0
        readAdjust()
        refresh()
        // stopped already: no panel comes up to look for that
        if (adjustment == null) s.takeFailure()?.let { showNotice(it, warning = true) }
    }
}

fun CanvasViewModel.setPluginFilterSlider(key: String, value: Float) {
    session?.pluginFilterSlider(key, value, false)
    readAdjust()
}

/** The finger left the slider [key]: a filter too slow to follow it redraws now. */
fun CanvasViewModel.letGoPluginFilter(key: String) {
    val slider = pluginFilter?.controls?.firstNotNullOfOrNull { (it as? io.github.artcraftandroid.procraft.core.PluginControl.Slider)?.control?.takeIf { s -> s.key == key } }
    if (slider != null) session?.pluginFilterSlider(key, slider.value, true)
}

fun CanvasViewModel.setPluginFilterSwitch(key: String, on: Boolean) {
    session?.pluginFilterSwitch(key, on)
    readAdjust()
}

/** A filter that stopped was closed by the core, which says why. */
fun CanvasViewModel.checkPluginFilter() {
    val s = session ?: return
    if (adjustment != Adjustment.PLUGIN || s.adjustmentShown().adjustment != null) return
    s.takeFailure()?.let { showNotice(it, warning = true) }
    adjustMenuAt = null
    readAdjust()
    refresh()
}

/** Ends the adjustment: [commit] keeps it as one undo step, otherwise the layer goes back. */
fun CanvasViewModel.endAdjustment(commit: Boolean) {
    liquifyRingAt = null
    if (adjustment == null) return
    // a gradient being edited is kept as it is
    if (gradientEditing != null) endGradientEdit()
    adjustMenuAt = null
    val painted = adjustPencil
    session?.adjustmentEnd(commit)
    if (commit) showDone("Adjustment applied")
    adjustModesOpen = false
    readAdjust()
    adjustment = null
    clonePuck = null
    perspectivePuck = null
    if (painted) readSliders()
    refresh()
    if (layersShown) refreshThumbnails()
}

/**
 * Layer or Pencil: painting the adjustment on starts it at its own amount, and only where the
 * brush goes shows it; back to the layer starts it again.
 */
fun CanvasViewModel.chooseAdjustMode(pencil: Boolean) {
    val a = adjustment ?: return
    adjustModesOpen = false
    if (pencil == adjustPencil || !a.paintable) return
    if (!pencil) {
        resetAdjustment()
        return
    }
    session?.adjustmentPencil(true)
    readAdjust()
    readSliders()
}

/** The canvas's (shared) touches changed the adjustment: its values shown as they are now. */
fun CanvasViewModel.adjustTouched() {
    adjustModesOpen = false
    readAdjust()
}

/** Cloning, the source as the core has it while the brush paints. */
fun CanvasViewModel.followClonePuck() {
    clonePuck = session?.adjustmentShown()?.clonePuck?.let { windowToCanvas(Offset(it[0], it[1])) }
}

fun CanvasViewModel.chooseBalanceTone(tone: Int) {
    session?.adjustmentBalanceTone(tone.coerceIn(0, 2).toUInt())
    readAdjust()
}

/** Slider [k] (red, green, blue) of the tone shown moved to [value] (0..1). */
fun CanvasViewModel.setBalance(k: Int, value: Float) {
    session?.adjustmentBalance(k.coerceIn(0, 2).toUInt(), value)
    readAdjust()
}

fun CanvasViewModel.chooseCurveChannel(channel: Int) {
    session?.adjustmentCurveChannel(channel.coerceIn(0, 3).toUInt())
    readAdjust()
}

/** Adds a point to the channel shown; returns its index. */
fun CanvasViewModel.addCurvePoint(p: Offset): Int {
    val i = session?.adjustmentCurveAdd(p.x, p.y)?.toInt() ?: -1
    readAdjust()
    return i
}

/** Moves point [index] of the channel shown to [p], kept between its neighbours; returns its index. */
fun CanvasViewModel.moveCurvePoint(index: Int, p: Offset): Int {
    if (index >= 0) session?.adjustmentCurveMove(index.toUInt(), p.x, p.y)
    readAdjust()
    return index
}

/** Takes point [index] off the channel shown (the curve keeps two at least). */
fun CanvasViewModel.deleteCurvePoint(index: Int) {
    if (index >= 0) session?.adjustmentCurveDelete(index.toUInt())
    readAdjust()
}

/** The curve of [channel] as points to draw (x, height). */
fun CanvasViewModel.curveShape(channel: Int): List<Offset> {
    val heights = session?.adjustmentCurveShape(channel.coerceIn(0, 3).toUInt()) ?: return emptyList()
    return heights.mapIndexed { i, h -> Offset(i / 255f, h) }
}

fun CanvasViewModel.chooseGradient(i: Int) {
    gradientIndex = i.coerceIn(0, gradients.size - 1)
    applyGradient()
}

/** A new gradient, black to white, just after the chosen one: chosen and opened to edit. */
fun CanvasViewModel.newGradient() {
    val at = gradientLibrary.addAfter(gradientIndex.toUInt()).toInt()
    gradients = readGradients()
    gradientIndex = at
    gradientEditing = at
    applyGradient()
}

/** Opens gradient [i] (chosen) to edit. */
fun CanvasViewModel.editGradient(i: Int) {
    chooseGradient(i)
    gradientEditing = gradientIndex
}

/** Done editing (each change was kept as it was made). */
fun CanvasViewModel.endGradientEdit() {
    gradientEditing = null
}

fun CanvasViewModel.deleteGradient(i: Int) {
    if (gradients.size <= 1 || i !in gradients.indices) return
    gradientLibrary.delete(i.toUInt())
    gradients = readGradients()
    if (gradientIndex > i || gradientIndex >= gradients.size) gradientIndex = (gradientIndex - 1).coerceAtLeast(0)
    applyGradient()
}

/** A copy of gradient [i] just after it, chosen. */
fun CanvasViewModel.duplicateGradient(i: Int) {
    if (i !in gradients.indices) return
    gradientIndex = gradientLibrary.duplicate(i.toUInt()).toInt()
    gradients = readGradients()
    applyGradient()
}

/** The gradient being edited changed by [f] (in the shared library), and mapped by again. */
internal fun CanvasViewModel.changeEdited(f: (UInt) -> Unit) {
    val i = gradientEditing ?: return
    if (i !in gradients.indices) return
    f(i.toUInt())
    gradients = readGradients()
    applyGradient()
}

fun CanvasViewModel.renameGradient(name: String) = changeEdited { gradientLibrary.rename(it, name) }

/** A stop at [position] (0..1), coloured as the gradient was there; returns its index. */
fun CanvasViewModel.addGradientStop(position: Float): Int {
    var stop = -1
    changeEdited { stop = gradientLibrary.addStop(it, position).toInt() }
    return stop
}

fun CanvasViewModel.moveGradientStop(i: Int, position: Float) = changeEdited { gradientLibrary.moveStop(it, i.toUInt(), position) }

fun CanvasViewModel.setGradientStopColor(i: Int, rgb: Int) = changeEdited { gradientLibrary.setStopColour(it, i.toUInt(), (rgb and 0xFFFFFF).toUInt()) }

/** Takes a stop out; a gradient keeps two. */
fun CanvasViewModel.deleteGradientStop(i: Int) = changeEdited { gradientLibrary.deleteStop(it, i.toUInt()) }

// noise, bloom, halftone and chromatic aberration's toolbars

fun CanvasViewModel.chooseNoiseKind(kind: NoiseKind) {
    session?.adjustmentNoiseKind(kind)
    readAdjust()
}

/** Noise's slider [index] (scale, octaves, turbulence) moved to [value]. */
fun CanvasViewModel.setNoiseValue(index: Int, value: Float) {
    session?.adjustmentNoiseValue(index.coerceIn(0, 2).toUInt(), value)
    readAdjust()
}

fun CanvasViewModel.chooseNoiseMulti(multi: Boolean) {
    session?.adjustmentNoiseWays(multi, noiseAdded)
    readAdjust()
}

fun CanvasViewModel.toggleNoiseAdded() {
    session?.adjustmentNoiseWays(noiseMulti, !noiseAdded)
    readAdjust()
}

/** Bloom's slider [index] (transition, size, burn) moved to [value]. */
fun CanvasViewModel.setBloomValue(index: Int, value: Float) {
    session?.adjustmentBloomValue(index.coerceIn(0, 2).toUInt(), value)
    readAdjust()
}

fun CanvasViewModel.chooseHalftoneKind(kind: HalftoneKind) {
    session?.adjustmentHalftoneKind(kind)
    readAdjust()
}

fun CanvasViewModel.chooseChromaKind(kind: ChromaticKind) {
    session?.adjustmentChromaKind(kind)
    readAdjust()
}

/** Perspective blur all round its puck, or only towards its arrow. */
fun CanvasViewModel.choosePerspectiveKind(kind: PerspectiveKind) {
    session?.adjustmentPerspectiveKind(kind)
    readAdjust()
}

/** Chromatic aberration's slider [index] of the kind shown moved to [value]. */
fun CanvasViewModel.setChromaValue(index: Int, value: Float) {
    if (index < 0) return
    session?.adjustmentChromaValue(index.toUInt(), value)
    readAdjust()
}

fun CanvasViewModel.chooseGlitchKind(kind: GlitchKind) {
    session?.adjustmentGlitchKind(kind)
    readAdjust()
}

/** Slider [index] of the glitch kind shown moved to [value] (0..1, or −1..1 for diverge's shifts). */
fun CanvasViewModel.setGlitchValue(index: Int, value: Float) {
    if (index < 0) return
    session?.adjustmentGlitchValue(index.toUInt(), value)
    readAdjust()
}

/** Gradient Map maps by the gradient chosen (also after the library changed). */
internal fun CanvasViewModel.applyGradient() {
    session?.adjustmentGradient(gradientIndex.toUInt())
}

// the menu a tap on the canvas opens during an adjustment

/** Keeps what the adjustment did as one undo step and starts it again from there. */
fun CanvasViewModel.applyAdjustment() {
    if (adjustment == null) return
    adjustMenuAt = null
    session?.adjustmentApply()
    showDone("Adjustment applied")
    readAdjust()
    refresh()
    if (layersShown) refreshThumbnails()
}

/** Takes back the adjustment's changes, or with none, the last one kept. */
fun CanvasViewModel.undoAdjustment() {
    if (adjustment == null) return
    session?.adjustmentUndo()
    readAdjust()
    refresh()
    if (layersShown) refreshThumbnails()
}

fun CanvasViewModel.togglePreview() {
    session?.adjustmentPreview(!adjustPreview)
    readAdjust()
    adjustMenuAt = null
}

fun CanvasViewModel.cancelAdjustment() = endAdjustment(false)

/** Every value back to where the adjustment started. */
fun CanvasViewModel.resetAdjustment() {
    if (adjustment == null) return
    val painted = adjustPencil
    session?.adjustmentReset()
    readAdjust()
    if (painted && !adjustPencil) readSliders()
    refresh()
}

fun CanvasViewModel.chooseLiquifyKind(kind: LiquifyKind) {
    session?.adjustmentLiquifyKind(kind)
    liquifyPopup = 0
    readAdjust()
}

/** Liquify's slider [index] (size, pressure, distortion, momentum) moved to [value]. */
fun CanvasViewModel.setLiquifyValue(index: Int, value: Float) {
    session?.adjustmentLiquifyValue(index.coerceIn(0, 3).toUInt(), value)
    readAdjust()
}

fun CanvasViewModel.changeLiquifyAmount(amount: Float) {
    session?.adjustmentLiquifyAmount(amount)
    readAdjust()
}

/** The finger let go of Adjust's slider: that drag is an undo step of its own. */
fun CanvasViewModel.letGoLiquifyAmount() {
    session?.adjustmentLiquifyAmountEnd()
}

fun CanvasViewModel.resetLiquify() {
    session?.adjustmentLiquifyReset()
    liquifyPopup = 0
    readAdjust()
}

fun CanvasViewModel.setHsb(h: Float, s: Float, b: Float) {
    session?.adjustmentHsb(h, s, b)
    readAdjust()
}

/** The Adjustments button: ends Layer Opacity, Recolor or the open adjustment, else opens its menu. */
fun CanvasViewModel.adjustmentsPressed() {
    if (switchModes(ModeButton.Adjustments)) toggle(Panel.ADJUSTMENTS)
}

