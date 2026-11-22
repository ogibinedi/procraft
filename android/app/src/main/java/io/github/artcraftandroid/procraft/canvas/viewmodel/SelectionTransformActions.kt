package io.github.artcraftandroid.procraft.canvas

import io.github.artcraftandroid.procraft.ui.CanvasSpace
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.ModeButton
import io.github.artcraftandroid.procraft.core.GuideSettings
import io.github.artcraftandroid.procraft.core.Interpolation
import io.github.artcraftandroid.procraft.core.SelectionDisplay
import io.github.artcraftandroid.procraft.core.TransformAction
import io.github.artcraftandroid.procraft.core.TransformBox
import io.github.artcraftandroid.procraft.core.TransformKind
import kotlin.math.abs
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.*
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.ADVANCED_MESH
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.DROP_BAND_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.DROP_THRESHOLD
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.FILL_SAYS_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.FRAME_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.INTERPOLATION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.MAGNETICS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.RECOLOR_THRESHOLD
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SELECTION_KIND
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SELECTION_THRESHOLD
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SETTLE_MARGIN_NS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SNAPPING
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SNAP_DISTANCE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SNAP_VELOCITY
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.TRANSFORM_KIND

// ---- selection ----

/** The selection button: opens the selection tool, or closes it letting go of the selection. */
fun CanvasViewModel.toggleSelecting() {
    // open, it closes; else the other modes end
    if (!switchModes(ModeButton.Selection)) return
    panel = Panel.NONE
    pickNext = false
    // each time the tool opens, shapes add to the selection
    selectionRemoves = false
    selecting = true
    refreshView()
    showSelectionLook()
}

fun CanvasViewModel.closeSelecting() {
    if (!selecting) return
    // a freehand path still open closes into the selection; with Color Fill, what is selected
    // is filled and the selection ends with it (one undo step, which brings it back)
    val s = session
    freehand = emptyList()
    if (s != null && s.closeSelectionTool(colorFill)) {
        refresh()
        if (layersShown) refreshThumbnails()
    }
    selecting = false
    selectionPopup = SelectionPopup.NONE
    showSelectionLook()
}

fun CanvasViewModel.chooseSelectionKind(kind: SelectionKind) {
    closeFreehand()
    selectionKind = kind
    transformPrefs.edit().putString(SELECTION_KIND, kind.name).apply()
    showSelectionLook()
}

fun CanvasViewModel.chooseSelectionRemoves(removes: Boolean) {
    selectionRemoves = removes
}

/** How the canvas's (shared) touches select while the selection tool is open; none otherwise. */
fun CanvasViewModel.selectTouch(): io.github.artcraftandroid.procraft.core.SelectTouch? {
    if (!selecting) return null
    val kind = when (selectionKind) {
        SelectionKind.AUTOMATIC -> io.github.artcraftandroid.procraft.core.SelectKind.AUTOMATIC
        SelectionKind.FREEHAND -> io.github.artcraftandroid.procraft.core.SelectKind.FREEHAND
        SelectionKind.RECTANGLE -> io.github.artcraftandroid.procraft.core.SelectKind.RECTANGLE
        SelectionKind.ELLIPSE -> io.github.artcraftandroid.procraft.core.SelectKind.ELLIPSE
    }
    return io.github.artcraftandroid.procraft.core.SelectTouch(kind, join, selectionThreshold)
}

/** The freehand path as the touches have drawn it (canvas pixels), read once a frame. */
fun CanvasViewModel.freehandChanged() {
    if (freehandDue) return
    freehandDue = true
    android.view.Choreographer.getInstance().postFrameCallback {
        freehandDue = false
        freehand = session?.freehandPath().orEmpty().chunked(2).mapNotNull { (x, y) -> windowToCanvas(Offset(x, y)) }
    }
}

/** The touches made a selection (or a shape dragged out ended). */
fun CanvasViewModel.selectionTouched() {
    shapeDrag = null
    selectionChanged()
}

/** A colour selection's threshold slid from window point [p]: shown as it goes; [done], kept for the next one. */
fun CanvasViewModel.colourSlid(p: Offset, threshold: Float, done: Boolean) {
    if (!done) {
        val waiting = colourDue != null
        colourDue = p to threshold
        if (!waiting) {
            android.view.Choreographer.getInstance().postFrameCallback {
                val (at, slid) = colourDue ?: return@postFrameCallback
                colourDue = null
                selectByColour(at, slid, live = true)
            }
        }
        return
    }
    // the touches made the selection as it was let go (a move still waiting is left out)
    colourDue = null
    thresholdDrag = null
    selectionThreshold = threshold
    transformPrefs.edit().putFloat(SELECTION_THRESHOLD, threshold).apply()
}

/** Closes the freehand path into a selection (a path of fewer than three points is dropped). */
fun CanvasViewModel.closeFreehand() {
    val s = session ?: return
    // (one drawn within a frame may not have been read yet)
    val drawn = freehand.isNotEmpty() || freehandDue
    freehand = emptyList()
    s.closeFreehand()
    if (drawn) selectionChanged()
}

/** Selects by colour at window point [p] with [threshold], shown filled while the threshold is dragged. */
internal fun CanvasViewModel.selectByColour(p: Offset, threshold: Float, live: Boolean) {
    val s = session ?: return
    thresholdDrag = if (live) p to threshold else null
    s.setSelectionDisplay(if (live) SelectionDisplay.FILL else SelectionDisplay.TINT)
    s.selectByColour(p.x, p.y, threshold, join, live)
    selectionChanged()
}

/** Whether window point [p] is over the top bar or the side bar while they show. */
fun CanvasViewModel.overBars(p: Offset): Boolean =
    io.github.artcraftandroid.procraft.ui.overBars(p, io.github.artcraftandroid.procraft.ui.Pc.barShown.value * density, sideBarBounds, barsHidden)

/**
 * A colour starts being dragged from the colour button, or [swatch] from the palette, at
 * uptime [ms], by a pen or not ([pen]), pulled out [from] the button's centre or the swatch.
 */
fun CanvasViewModel.dropDragStart(at: Offset, swatch: Int? = null, pen: Boolean = false, ms: Long = android.os.SystemClock.uptimeMillis(), from: Offset = at) {
    if (swatch == null) {
        closePanels()
        // pulled down from the top edge: the system's bars it may show go again
        hideSystemBars?.invoke()
    }
    dropBandTimer?.cancel()
    dropSwatch = swatch
    dropDot = at to (swatch ?: colorRgb())
    dropBand = DropBand.DRAGGING
    dropPoured = false
    dropPourRefused = false
    colourDrag = io.github.artcraftandroid.procraft.core.ColourDrag(at.x / density, at.y / density, ms.toDouble(), pen, dropThreshold)
    // with the system's animations off it neither stretches nor wobbles
    val still = !android.animation.ValueAnimator.areAnimatorsEnabled()
    dropBlob = io.github.artcraftandroid.procraft.core.ColourBlob(from.x / density, from.y / density, swatch == null, pen, still, ms.toDouble())
    blobTo(at)
}

/** The drawn colour goes after window point [p]. */
internal fun CanvasViewModel.blobTo(p: Offset) {
    val button = dropButton ?: p
    val overBar = p.y <= io.github.artcraftandroid.procraft.ui.Pc.barShown.value * density || sideBarBounds?.contains(p) == true
    dropBlob?.pointTo(p.x / density, p.y / density, overBar, button.x / density, button.y / density)
}

/** The dragged colour's pointer moved to [at] at uptime [ms]: the dot follows, or, resting, the threshold slides. */
fun CanvasViewModel.dropDragMove(at: Offset, ms: Long = android.os.SystemClock.uptimeMillis()) {
    val drag = colourDrag ?: return
    val step = drag.moved(at.x / density, at.y / density, ms.toDouble())
    val dot = Offset(step.dotX * density, step.dotY * density)
    dropDot = dropDot?.let { dot to it.second }
    blobTo(dot)
    if (!step.slid) return
    if (dropPoured) {
        pourThreshold(step.threshold)
    } else if (!dropPourRefused) {
        dropThreshold = step.threshold
        dropSliding = true
        dropPoured = pourBegin(dot)
        dropPourRefused = !dropPoured
        if (dropPoured) dropDot = null
    }
}

/**
 * When (uptime ms) the dragged colour comes to rest if its pointer stays; null once it has, or
 * while it waits for a move after a rest off the artwork.
 */
fun CanvasViewModel.dropRestDue(): Long? = colourDrag?.restDue()?.toLong()

/**
 * The time is uptime [ms]: the dragged colour comes to rest if it is due, on the artwork off
 * the bars, and fills there at once, the fill showing instead of the dot (a slide then sets
 * the threshold).
 */
fun CanvasViewModel.dropDragRest(ms: Long) {
    val drag = colourDrag ?: return
    val step = drag.now()
    val dot = Offset(step.dotX * density, step.dotY * density)
    val rests = drag.rest(
        ms.toDouble(),
        dot.y > io.github.artcraftandroid.procraft.ui.Pc.barShown.value * density &&
            dropAvoid?.contains(dot) != true &&
            session?.pourLands(dot.x, dot.y) == true,
    )
    if (!rests || dropPoured || dropPourRefused) return
    dropPoured = pourBegin(dot)
    dropPourRefused = !dropPoured
    if (dropPoured) dropDot = null
}

/** The dragged colour let go at [at] (null: somewhere it doesn't fill, like the top bar). */
fun CanvasViewModel.dropDragEnd(at: Offset?) {
    if (dropSwatch == null) hideSystemBars?.invoke()
    val step = colourDrag?.now()
    colourDrag = null
    dropBlob = null
    // come to rest, it fills where it rests (already, if its threshold was slid)
    val rested = step?.takeIf { it.resting }?.let { Offset(it.dotX * density, it.dotY * density) }
    if (dropPoured) {
        dropPoured = false
        dropDot = null
        pourEnd(keep = true)
        dropBand = DropBand.DROPPED
        dropBandTimer = viewModelScope.launch {
            kotlinx.coroutines.delay(DROP_BAND_MS)
            if (dropBand == DropBand.DROPPED) dropBand = DropBand.NONE
        }
        return
    }
    val at = if (at != null) rested ?: at else null
    dropDot = null
    if (at != null && dropAvoid?.contains(at) != true && pourBegin(at)) {
        pourEnd(keep = true)
        // the band offers to go on filling for a moment
        dropBand = DropBand.DROPPED
        dropBandTimer = viewModelScope.launch {
            kotlinx.coroutines.delay(DROP_BAND_MS)
            if (dropBand == DropBand.DROPPED) dropBand = DropBand.NONE
        }
    } else {
        dropBand = DropBand.NONE
    }
}

/** Continue filling: each touch on the canvas fills, until [endFilling]. */
fun CanvasViewModel.continueFilling() {
    dropBandTimer?.cancel()
    dropBand = DropBand.FILLING
}

/** ColorDrop's filling ends, and Recolor with it. */
fun CanvasViewModel.endFilling() {
    endDropBand()
    endRecolor()
}

/** ColorDrop's band goes, and Continue filling with it. */
internal fun CanvasViewModel.endDropBand() {
    dropBandTimer?.cancel()
    if (dropBand != DropBand.NONE) dropBand = DropBand.NONE
    dropSliding = false
}

/** Fills from window point [p] with the dropped colour; false if nothing was filled. */
fun CanvasViewModel.pourBegin(p: Offset): Boolean {
    val s = session ?: return false
    if (!currentTakesChanges(paint = true)) return false
    // text takes no colour until it is rasterized
    if (currentIsText()) {
        askRasterize = true
        return false
    }
    val c = dropSwatch ?: colorRgb()
    val t = dropThreshold
    // made at once, and shown spreading in from where it landed
    val poured = pourNow { s.pourDrop(p.x, p.y, ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, t) }
    if (poured) haptic(HapticCue.PICK)
    return poured
}

/** Continue filling: the colour and threshold the canvas's (shared) touches fill with; none when not filling. */
fun CanvasViewModel.touchFill(): io.github.artcraftandroid.procraft.core.Fill? {
    if (dropBand != DropBand.FILLING) return null
    val c = dropSwatch ?: colorRgb()
    return io.github.artcraftandroid.procraft.core.Fill(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, dropThreshold)
}

/** The touches' fill was let go of (kept, or taken back by a second finger): a slid threshold is kept for the next. */
fun CanvasViewModel.filled() {
    if (dropSliding) transformPrefs.edit().putFloat(DROP_THRESHOLD, dropThreshold).apply()
    dropSliding = false
    haptic(HapticCue.PICK)
    refresh()
}

/** The fill under way redone with [threshold], which is kept for the next. */
fun CanvasViewModel.pourThreshold(threshold: Float) {
    val s = session ?: return
    val t = threshold.coerceIn(0f, 1f)
    dropThreshold = t
    dropSliding = true
    pourLater("threshold") { s.pourThreshold(t) }
}

/** Waits, the screen free, for a fill a plugin is finishing, saying so if it takes a while. */
internal fun CanvasViewModel.watchFill(s: CanvasSession) {
    if (fillFinishing || !s.pourFinishing()) return
    fillFinishing = true
    viewModelScope.launch {
        val since = android.os.SystemClock.uptimeMillis()
        var said = false
        while (s.pourFinishing()) {
            kotlinx.coroutines.delay(16)
            if (!said && android.os.SystemClock.uptimeMillis() - since >= FILL_SAYS_MS) {
                said = true
                showNotice("Finishing the fill…")
            }
        }
        fillFinishing = false
        refresh()
    }
}

/** The fill under way ends: [keep] makes it an undo step, else it is taken back. */
fun CanvasViewModel.pourEnd(keep: Boolean) {
    val s = session ?: return
    pourNow { s.pourEnd(keep, null) }
    if (dropSliding) transformPrefs.edit().putFloat(DROP_THRESHOLD, dropThreshold).apply()
    dropSliding = false
    refresh()
}

/** Runs [block] on the fill's thread, unless another of its [kind] is given before it gets there. */
internal fun CanvasViewModel.pourLater(kind: String, block: () -> Unit) {
    if (pourLatest.put(kind, block) == null) pourThread.execute { pourLatest.remove(kind)?.invoke() }
}

/** Runs [block] on the fill's thread once what is queued there is done, and waits for it. */
internal fun <T> CanvasViewModel.pourNow(block: () -> T): T = pourThread.submit(java.util.concurrent.Callable(block)).get()

/** The reticle where the core has it (canvas pixels). */
internal fun CanvasViewModel.readRecolorPoint() {
    recolorPoint = session?.recolorPoint()?.let { windowToCanvas(Offset(it[0], it[1])) }
}

/** Recolor on, the reticle in the middle of the screen, or off (keeping what it filled). */
fun CanvasViewModel.toggleRecolor() {
    if (recoloring) {
        switchModes(ModeButton.Recolor)
        return
    }
    val s = session ?: return
    if (!currentTakesChanges()) return
    // text takes no colour until it is rasterized
    if (currentIsText()) {
        askRasterize = true
        return
    }
    switchModes(ModeButton.Recolor)
    closePanels()
    recoloring = true
    val c = colorRgb()
    val (r, g, b) = Triple(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    val t = recolorThreshold
    // the shared core keeps it (crates/core recolor.rs); its fills run on the fill thread
    pourNow { s.recolorStart(screenCentre.x, screenCentre.y, r, g, b, t) }
    readRecolorPoint()
    refresh()
}

/** The touches dragged the reticle to window point [at]: shown there at once, the fill following on the fill thread. */
fun CanvasViewModel.recolorMoveTo(at: Offset) {
    val s = session ?: return
    recolorPoint = windowToCanvas(at) ?: return
    pourLater("point") { s.recolorMoveTo(at.x, at.y) }
}

/** A tap while recoloring: the fill so far is kept, and the reticle starts again at [p]. */
fun CanvasViewModel.recolorTap(p: Offset) {
    val s = session ?: return
    pourNow { s.recolorTap(p.x, p.y) }
    readRecolorPoint()
    refresh()
}

/** The Flood slider moved to [t]: the fill redone with it. */
fun CanvasViewModel.recolorThresholdTo(t: Float) {
    val s = session ?: return
    val v = t.coerceIn(0f, 1f)
    recolorThreshold = v
    if (recoloring) pourLater("threshold") { s.recolorThreshold(v) }
}

/** Recolor ends, keeping what it filled. */
fun CanvasViewModel.endRecolor() {
    if (!recoloring) return
    val s = session
    if (s != null) pourNow { s.recolorEnd() }
    recoloring = false
    recolorPoint = null
    transformPrefs.edit().putFloat(RECOLOR_THRESHOLD, recolorThreshold).apply()
    refresh()
}

fun CanvasViewModel.invertSelection() {
    session?.selectInvert()
    selectionChanged()
}

fun CanvasViewModel.copySelectionToLayer() {
    val s = session ?: return
    s.copySelectionToLayer() ?: return
    s.selectNone()
    selectionChanged()
    refresh()
    if (layersShown) refreshThumbnails()
}

/** The feather popover's amount (0..1): the selection's edges softened from how they were before. */
fun CanvasViewModel.featherTo(amount: Float) {
    featherAmount = amount
    // a big selection takes a while: off the main thread, the newest amount when it gets there
    val s = session ?: return
    pourLater("feather") { s.selectFeather(amount) }
}

fun CanvasViewModel.openSelectionPopup(p: SelectionPopup) {
    selectionPopup = if (selectionPopup == p) SelectionPopup.NONE else p
    if (selectionPopup == SelectionPopup.FEATHER) featherAmount = 0f
    session?.let { savedSelections = it.savedSelectionCount().toInt() }
    if (selectionPopup == SelectionPopup.SAVED) loadSavedThumbnails()
}

fun CanvasViewModel.saveSelection() {
    savedSelections = session?.saveSelection()?.toInt() ?: savedSelections
    loadSavedThumbnails()
}

internal fun CanvasViewModel.loadSavedThumbnails() {
    val s = session ?: return
    savedThumbnails.clear()
    for (i in 0 until savedSelections) {
        val t = s.savedSelectionThumbnail(i.toUInt(), 128u, 92u) ?: continue
        val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
        savedThumbnails[i] = bitmap.asImageBitmap()
    }
}

fun CanvasViewModel.loadSelection(i: Int) {
    session?.loadSelection(i.toUInt())
    selectionChanged()
}

fun CanvasViewModel.deleteSavedSelection(i: Int) {
    val s = session ?: return
    s.deleteSavedSelection(i.toUInt())
    savedSelections = s.savedSelectionCount().toInt()
    loadSavedThumbnails()
}

/** Color Fill on or off: what is selected shows covered by the colour, filled as the tool closes. */
fun CanvasViewModel.toggleColorFill() {
    colorFill = !colorFill
    showSelectionLook()
}

fun CanvasViewModel.clearSelection() {
    closeFreehand()
    session?.selectNone()
    selectionChanged()
}

internal fun CanvasViewModel.selectionChanged() {
    val s = session ?: return
    hasSelection = s.hasSelection()
    showSelectionLook()
}

/**
 * Bands over what is not selected, except while selecting by colour, which tints what is, and
 * with Color Fill on in the selection tool, which covers it with the colour.
 */
internal fun CanvasViewModel.showSelectionLook() {
    val look = when {
        selecting && colorFill -> SelectionDisplay.COLOUR_FILL
        selecting && selectionKind == SelectionKind.AUTOMATIC -> SelectionDisplay.TINT
        else -> SelectionDisplay.STRIPES
    }
    session?.setSelectionDisplay(look)
    refreshTakenBox()
}

/** The box round the strokes the selection takes, read again while the selection tool is open. */
internal fun CanvasViewModel.refreshTakenBox() {
    takenBox = if (selecting && hasSelection) session?.takenPluginItemsBox().orEmpty() else emptyList()
}

/** Window pixels to canvas pixels through the view, or none when it cannot be inverted. */
fun CanvasViewModel.windowToCanvas(p: Offset): Offset? {
    val (a, b, c, d) = viewMatrix
    val e = viewMatrix[4]
    val f = viewMatrix[5]
    val det = a * d - b * c
    if (kotlin.math.abs(det) < 1e-9f) return null
    val x = p.x - e
    val y = p.y - f
    return Offset((d * x - c * y) / det, (a * y - b * x) / det)
}

fun CanvasViewModel.canvasToWindow(p: Offset): Offset {
    val m = viewMatrix
    return Offset(m[0] * p.x + m[2] * p.y + m[4], m[1] * p.x + m[3] * p.y + m[5])
}

// ---- transform ----

/** The transform button: opens transform on the current layer, or keeps it and closes. */
fun CanvasViewModel.toggleTransform() {
    // a picture being placed on a model is kept
    if (modelPictureBox != null) {
        endModelPicture(keep = true)
        return
    }
    if (transforming) {
        switchModes(ModeButton.Transform)
        return
    }
    val s = session ?: return
    if (!currentTakesChanges()) return
    switchModes(ModeButton.Transform)
    panel = Panel.NONE
    pickNext = false
    s.transformSetInterpolation(interpolation)
    s.transformSetGuides(guides)
    transformBox = s.transformBegin()
    // nothing there to move: it says so and stays out of transform
    if (transformBox == null) {
        showNotice(s.transformRefusal())
        return
    }
    // a warp lays its mesh over what began
    transformBox = s.transformSetKind(transformKind) ?: transformBox
    transformPopup = TransformPopup.NONE
    refreshView()
    transforming = true
}

/** Ends transform: [commit] keeps the change as one undo step, otherwise the layer goes back. */
fun CanvasViewModel.endTransform(commit: Boolean) {
    if (!transforming) return
    session?.transformEnd(commit)
    transforming = false
    transformBox = null
    transformPopup = TransformPopup.NONE
    transformNumeric = null
    transformSnapTracker.clear()
    refresh()
    if (layersShown) refreshThumbnails()
}

fun CanvasViewModel.chooseTransformKind(kind: TransformKind) {
    transformKind = kind
    session?.transformSetKind(kind)?.let { transformBox = it }
    transformPrefs.edit().putString(TRANSFORM_KIND, kind.name).apply()
}

fun CanvasViewModel.chooseInterpolation(i: Interpolation) {
    interpolation = i
    session?.transformSetInterpolation(i)
    transformPrefs.edit().putString(INTERPOLATION, i.name).apply()
}

fun CanvasViewModel.toggleAdvancedMesh() {
    advancedMesh = !advancedMesh
    transformPrefs.edit().putBoolean(ADVANCED_MESH, advancedMesh).apply()
    showDone("Advanced mesh " + if (advancedMesh) "on" else "off")
}

fun CanvasViewModel.changeGuides(g: GuideSettings) {
    if (g.snapping != guides.snapping) showDone("Snapping " + if (g.snapping) "on" else "off")
    else if (g.magnetics != guides.magnetics) showDone("Magnetics " + if (g.magnetics) "on" else "off")
    guides = g
    session?.transformSetGuides(g)
    transformPrefs.edit()
        .putBoolean(MAGNETICS, g.magnetics)
        .putBoolean(SNAPPING, g.snapping)
        .putFloat(SNAP_DISTANCE, g.distance)
        .putFloat(SNAP_VELOCITY, g.velocity)
        .apply()
}

internal inline fun <reified E : Enum<E>> CanvasViewModel.enumPref(key: String, default: E): E =
    transformPrefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

fun CanvasViewModel.transformAction(action: TransformAction) {
    transformPopup = TransformPopup.NONE
    transformBox = session?.transformApply(action) ?: transformBox
}

/** The box after a drag step on the canvas. */
fun CanvasViewModel.transformMoved(box: TransformBox?) {
    if (box != null) {
        transformBox = box
        if (transformSnapTracker.update(box.snapLines)) haptic(HapticCue.SNAP)
    }
}

fun CanvasViewModel.transformSetSize(width: Float, height: Float) = transformMoved(session?.transformSetSize(width, height))

fun CanvasViewModel.transformSetAngle(degrees: Float) = transformMoved(session?.transformSetAngle(degrees))

/** Re-reads the view, after a gesture moved it, while something is drawn over the canvas. */
/** Fits the canvas to the screen, settling into place; what is drawn over it follows each frame. */
fun CanvasViewModel.settleToFit() {
    session?.settleToFit()
    haptic(HapticCue.FLIP)
    followView()
}

/** What is drawn over the canvas follows the view each frame while it moves into place. */
fun CanvasViewModel.followView() {
    val s = session ?: return
    settling?.cancel()
    settling = viewModelScope.launch {
        val until = System.nanoTime() + (s.viewSettleSeconds() * 1e9).toLong() + SETTLE_MARGIN_NS
        while (System.nanoTime() < until && s.viewMoving()) {
            refreshView()
            kotlinx.coroutines.delay(FRAME_MS)
        }
        refreshView()
    }
}

fun CanvasViewModel.refreshView() {
    session?.let {
        viewMatrix = it.viewMatrix()
        // the guide's lines follow the canvas
        guide = it.guide()
    }
    // the shape's handles follow the canvas
    if (quickShape != null) quickShape = session?.quickshapeInfo()
}

/** The interface hidden or shown again (not while transforming). */
fun CanvasViewModel.toggleBars() {
    if (!switchModes(ModeButton.Bars)) return
    barsHidden = !barsHidden
    if (barsHidden) panel = Panel.NONE
}

/** A stroke finished: its layer's thumbnail and the undo state changed. */
fun CanvasViewModel.strokeEnded() {
    // a snapped shape the pen moved: its handles where it now is
    if (quickShape != null) quickShape = session?.quickshapeInfo() ?: quickShape
    // a stroke on a locked or hidden layer, or a group, drew nothing: say why (text layers ask to rasterize instead)
    if (adjustment == null && !currentIsText()) currentTakesChanges(paint = true)
    refreshFlags()
    if (animation != null) refreshFrameThumbnails()
    refreshReference()
    recordFrame()
    // the stroke's colour went into the history (painting with the brush, not an adjustment on)
    history = colourHistory.colours().map { it.toInt() }
    if (layersShown) layers.firstOrNull { it.current }?.let { loadThumbnail(it.id) }
}

