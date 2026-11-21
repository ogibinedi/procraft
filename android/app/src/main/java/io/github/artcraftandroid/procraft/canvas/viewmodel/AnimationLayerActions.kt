package io.github.artcraftandroid.procraft.canvas

import io.github.artcraftandroid.procraft.AppLog
import io.github.artcraftandroid.procraft.ui.CanvasSpace
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.LayerType
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.*
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.FRAME_THUMB_SIDE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.MAX_HOLD
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.THUMB_SIDE

fun CanvasViewModel.changeAnimationAssist(on: Boolean) {
    stopPlaying()
    session?.setAnimationAssist(on)
    refresh()
}

fun CanvasViewModel.changePageAssist(on: Boolean) {
    stopPlaying()
    session?.setPageAssist(on)
    refresh()
}

/** Re-reads Animation Assist from the session (after any change to the layers). */
internal fun CanvasViewModel.refreshAnimation() {
    val s = session ?: return
    val settings = s.animationSettings()
    animation = settings
    if (settings == null) {
        animationFrames = emptyList()
        frameSlots = emptyList()
        playedSlots = emptyList()
        currentFrame = null
        animationSettingsOpen = false
        frameOptionsOpen = false
        return
    }
    animationFrames = s.animationFrames()
    frameSlots = s.animationSequence().map { it.toInt() }
    playedSlots = s.animationPlayedPlaces().map { it.toInt() }
    currentFrame = s.currentFrame()?.toInt()
    refreshFrameThumbnails()
}

internal fun CanvasViewModel.refreshFrameThumbnails() {
    val s = session ?: return
    val ids = animationFrames
    frameThumbJob?.cancel()
    frameThumbJob = viewModelScope.launch {
        for (id in ids) {
            val image = withContext(Dispatchers.Default) {
                val t = s.frameThumbnail(id, FRAME_THUMB_SIDE.toUInt()) ?: return@withContext null
                val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
                bitmap.asImageBitmap()
            }
            if (image != null) frameThumbnails[id] = image else frameThumbnails.remove(id)
        }
    }
}

fun CanvasViewModel.changeAnimation(f: (io.github.artcraftandroid.procraft.core.AnimationSettings) -> io.github.artcraftandroid.procraft.core.AnimationSettings) {
    val s = session ?: return
    val a = animation ?: return
    s.setAnimationSettings(f(a))
    animation = s.animationSettings()
}

/** Place [slot] comes to the middle, its frame the one drawn on (a tap on the current one opens its options). */
fun CanvasViewModel.tapFrame(slot: Int) {
    if (playingFrame != null) stopPlaying()
    if (slot == currentSlot) {
        frameOptionsOpen = !frameOptionsOpen
        animationSettingsOpen = false
        return
    }
    frameOptionsOpen = false
    goToSlot(slot)
}

internal fun CanvasViewModel.goToSlot(slot: Int) {
    val frame = frameSlots.getOrNull(slot) ?: return
    heldAt = frame to slot - frameSlots.indexOf(frame)
    if (frame != currentFrame) animationFrames.getOrNull(frame)?.let { selectLayer(it) }
}

/** The timeline dragged: playing stops and the frame's options close. */
fun CanvasViewModel.beginScrub() {
    stopPlaying()
    frameOptionsOpen = false
}

/** The timeline dragged past frame [i]: it shows alone, as playing shows frames. */
fun CanvasViewModel.scrubFrame(i: Int) {
    if (i in animationFrames.indices) session?.setPlayFrame(i.toUInt())
}

/**
 * The timeline let go with place [slot] in its middle: its frame becomes the current one, as
 * a tap makes it (null: nothing chosen, the current frame shows again).
 */
fun CanvasViewModel.endScrub(slot: Int?) {
    if (slot != null) goToSlot(slot)
    session?.setPlayFrame(null)
}

fun CanvasViewModel.addFrame() {
    stopPlaying()
    frameOptionsOpen = false
    layerChange { if (it.addFrame() == 0uL) message = noRoom() }
}

internal fun CanvasViewModel.currentFrameLayer(): LayerInfo? = currentFrame?.let { animationFrames.getOrNull(it) }?.let { id -> layers.firstOrNull { it.id == id } }

/** The frame copied; on a held place it splits there, the copy taking over. */
fun CanvasViewModel.duplicateFrame() {
    frameOptionsOpen = false
    val held = heldPlace
    val layer = currentFrameLayer() ?: return
    if (held > 0) layerChange { if (it.splitHeldFrame(layer.id, held.toUInt()) == null) message = noRoom() } else duplicate(layer)
}

/** A plugin's In-between: a frame drawn between this frame and the next by the plugin (off the main thread: it takes a moment), made current. */
fun CanvasViewModel.addInbetween() {
    frameOptionsOpen = false
    val layer = currentFrameLayer() ?: return
    val s = session ?: return
    viewModelScope.launch {
        val made = withContext(Dispatchers.Default) { s.addInbetween(layer.id) }
        if (made == null) message = "An in-between goes between two frames of one layer each, with something drawn." else layerChange { }
    }
}

/** A plugin's action [key] from the frame options, run with that frame's layer current. */
fun CanvasViewModel.runFrameAction(key: String) {
    frameOptionsOpen = false
    val layer = currentFrameLayer() ?: return
    session?.selectLayer(layer.id)
    runPluginAction(key)
}

/** The frame deleted; on a held place it is held one frame less. */
fun CanvasViewModel.deleteFrame() {
    frameOptionsOpen = false
    val layer = currentFrameLayer() ?: return
    if (heldPlace > 0) layerChange { it.dropHeldFrame(layer.id) } else delete(layer)
}

fun CanvasViewModel.frameHold(): Int = currentFrame?.let { animationFrames.getOrNull(it) }?.let { session?.frameHold(it)?.toInt() } ?: 0

fun CanvasViewModel.changeFrameHold(frames: Int) {
    val id = currentFrame?.let { animationFrames.getOrNull(it) } ?: return
    session?.changeFrameHold(id, frames.coerceIn(0, MAX_HOLD).toUInt(), false)
    frameSlots = session?.animationSequence()?.map { it.toInt() }.orEmpty()
    playedSlots = session?.animationPlayedPlaces()?.map { it.toInt() }.orEmpty()
    holdVersion++
}

/** The hold's slider let go: the whole drag is one undo step. */
fun CanvasViewModel.endFrameHold() {
    val id = currentFrame?.let { animationFrames.getOrNull(it) } ?: return
    session?.changeFrameHold(id, frameHold().toUInt(), true)
    refresh()
}

fun CanvasViewModel.togglePlaying() = if (playingFrame != null) stopPlaying() else play()

internal fun CanvasViewModel.play() {
    val s = session ?: return
    val a = animation ?: return
    // the timeline's places that play, in order, each frame as often as it is held (the shared
    // core's): a background or foreground frame shows with each, never on its own
    val places = s.animationSequence().map { it.toInt() }
    val order = s.animationPlayedPlaces().map { it.toInt() }.filter { it in places.indices }
    if (order.isEmpty()) return
    animationSettingsOpen = false
    frameOptionsOpen = false
    playJob?.cancel()
    playJob = viewModelScope.launch {
        var at = 0
        var step = 1
        while (true) {
            val frame = places[order[at]]
            playingFrame = frame
            playingSlot = order[at]
            s.setPlayFrame(frame.toUInt())
            kotlinx.coroutines.delay(1000L / (animation?.fps ?: a.fps).toInt().coerceAtLeast(1))
            // round again, turned back, or (a single pass) done
            val next = io.github.artcraftandroid.procraft.core.animationNextStep(a.playback, order.size.toUInt(), at.toUInt(), step) ?: break
            at = next.at.toInt()
            step = next.step
        }
        // a single pass ends on its last frame, still shown
    }
}

fun CanvasViewModel.stopPlaying() {
    playJob?.cancel()
    playJob = null
    if (playingFrame != null) {
        playingFrame = null
        playingSlot = null
        session?.setPlayFrame(null)
    }
}

// ---- layers ----

fun CanvasViewModel.addLayer() = layerChange { if (it.addLayer() == 0uL) message = noRoom() }

/** The kinds of layer the plugins on add: the Layers panel's + offers them beside a plain layer. */
fun CanvasViewModel.layerKinds(): List<io.github.artcraftandroid.procraft.core.PluginKind> = plugins.layerKinds()

/** A new layer of a plugin's [kind] above the current one. */
fun CanvasViewModel.addPluginLayer(kind: String) = layerChange { if (it.addPluginLayer(kind) == 0uL) message = noRoom() }

// no layer's pixels change, so the thumbnails stay (an artwork's hundreds would load again)
fun CanvasViewModel.selectLayer(id: ULong) = layerChange(thumbs = false) { it.selectLayer(id) }

/**
 * [l]'s box tapped: it shows or hides, saying so in the notice band as measured ("Hide layer"), or, with a
 * layer shown alone, every layer shows again (the core decides and words it).
 */
fun CanvasViewModel.toggleVisible(l: LayerInfo) {
    var said: String? = null
    layerChange { said = it.tapVisibility(l.id) }
    said?.let { showDone(it) }
}

fun CanvasViewModel.setLayerOpacity(l: LayerInfo, value: Float, done: Boolean) = layerChange(thumbs = false) { it.setLayerOpacity(l.id, value, done) }

fun CanvasViewModel.setBlend(l: LayerInfo, blend: UInt) = layerChange(thumbs = false) { it.setLayerBlend(l.id, blend) }

fun CanvasViewModel.previewBlend(owner: CanvasSession?, id: ULong, blend: UInt) {
    if (session === owner && owner?.previewLayerBlend(id, blend) == true) refresh()
}

fun CanvasViewModel.finishBlend(owner: CanvasSession?) {
    if (owner?.finishLayerBlend() == true && session === owner) refresh()
}

fun CanvasViewModel.toggleAlphaLock(l: LayerInfo) = layerChange(thumbs = false) { it.setLayerAlphaLock(l.id, !l.alphaLock) }

/** A mask's layer; anything else itself. */
fun CanvasViewModel.ownerOf(l: LayerInfo): LayerInfo = if (l.kind == LayerType.MASK) layers.firstOrNull { it.id == l.parent } ?: l else l

/** Whether [l] is somewhere inside group [group]. */
fun CanvasViewModel.isInside(l: LayerInfo, group: ULong): Boolean {
    var at = ownerOf(l).parent
    repeat(layers.size) {
        val id = at ?: return false
        if (id == group) return true
        at = layers.firstOrNull { it.id == id }?.parent
    }
    return false
}

/** Whether a closed group hides [l]'s row. */
fun CanvasViewModel.hidden(l: LayerInfo) = layers.any { it.kind == LayerType.GROUP && it.collapsed && isInside(l, it.id) }

/** Layer [l]'s options menu, as the core lists it. */
fun CanvasViewModel.layerOptions(l: LayerInfo): List<io.github.artcraftandroid.procraft.core.LayerOptionRow> = session?.layerOptions(l.id).orEmpty()

fun CanvasViewModel.hasMask(l: LayerInfo) = layers.any { it.kind == LayerType.MASK && it.parent == l.id }

/** The rows a drag of [l] takes along, it among them (the shared core decides). */
fun CanvasViewModel.movingWith(l: LayerInfo): Set<ULong> = session?.movingWith(l.id)?.toSet() ?: setOf(l.id)

/**
 * Where layer [id] (with what goes with it), dragged in the layers panel, lands if let go with
 * its middle at window height [y], by the panel's [rows] where they are laid out; [held]: the
 * row it has been held over for [rowHoldSeconds] (the shared core decides). With no [id],
 * layers brought in from elsewhere.
 */
fun CanvasViewModel.rowDrop(id: ULong?, rows: Map<ULong, androidx.compose.ui.geometry.Rect>, y: Float, held: ULong?): io.github.artcraftandroid.procraft.core.RowDrop {
    val shown = rows.map { (k, r) -> io.github.artcraftandroid.procraft.core.ShownRow(k, r.top, r.bottom) }
    return session?.rowDrop(id, shown, y, held) ?: io.github.artcraftandroid.procraft.core.RowDrop.Stays
}

/**
 * Moves [l] (with what goes with it) to [place], as [rowDrop] placed it; a chosen layer with
 * others picked takes them along, in their order (the shared core decides).
 */
fun CanvasViewModel.dropRow(l: LayerInfo, place: io.github.artcraftandroid.procraft.core.RowDrop) = layerChange(thumbs = false) { it.dropRow(l.id, place) }

/**
 * Row [l] dragged out of the layers panel by finger [pointer], at [at] in the window with the
 * row's corner at [corner]: it and what a drag of it takes along (its mask, a group's layers,
 * the others chosen with it) are lifted out to be held.
 */
fun CanvasViewModel.liftLayers(l: LayerInfo, pointer: androidx.compose.ui.input.pointer.PointerId, at: Offset, corner: Offset) {
    val s = session ?: return
    if (heldLayers != null || modelOpen || l.kind == LayerType.MASK) return
    val bundle = runCatching { s.liftLayers(l.id) }.getOrNull() ?: return
    heldLayers = HeldLayers(bundle, l.id, artworkDir, pointer, bundle.name(), bundle.items().toInt(), thumbnails[l.id], at - corner)
    heldLayersAt = at
}

fun CanvasViewModel.moveHeld(at: Offset) {
    heldLayersAt = at
}

/** Whether the layers held would land if let go now: over an artwork open on the canvas. */
fun CanvasViewModel.heldLands(): Boolean = heldLayers != null && screen == Screen.CANVAS && session != null && !modelOpen

/**
 * The layers held let go at [at] in the window. Over a row of the layers panel they go next to
 * it (back among their own rows, moved there); over another artwork's canvas, above its current
 * layer, one ready to transform as a picture brought in is; over the canvas they came from,
 * copied to the top. Anywhere else (the gallery) they go nowhere.
 */
fun CanvasViewModel.dropHeld(at: Offset) {
    val h = heldLayers ?: return
    heldLayers = null
    h.bundle.use { bundle ->
        val s = session ?: return
        if (!(screen == Screen.CANVAS && !modelOpen)) return
        val same = artworkDir != null && artworkDir == h.from
        val overRows = layersShown && layersBox?.contains(at) == true
        if (same && overRows) {
            val l = layers.firstOrNull { it.id == h.layer } ?: return
            dropRow(l, rowDrop(l.id, layerRows, at.y, null))
            return
        }
        val place = when {
            overRows -> io.github.artcraftandroid.procraft.core.LayerDrop.Row(rowDrop(null, layerRows, at.y, null))
            same -> io.github.artcraftandroid.procraft.core.LayerDrop.Top
            else -> io.github.artcraftandroid.procraft.core.LayerDrop.AboveCurrent
        }
        if (transforming) endTransform(true)
        if (runCatching { s.dropLayers(bundle, place) }.getOrNull() == null) {
            message = noRoom()
            return
        }
        refresh()
        if (layersShown) refreshThumbnails()
        if (place == io.github.artcraftandroid.procraft.core.LayerDrop.AboveCurrent && h.items == 1) toggleTransform()
    }
}

/** The layers held let go of where they can't land (the finger taken away by the system). */
fun CanvasViewModel.cancelHeld() {
    heldLayers?.bundle?.close()
    heldLayers = null
}

fun CanvasViewModel.addMask(l: LayerInfo) = layerChange { if (it.addMask(l.id) == null && !hasMask(l)) message = noRoom() }

fun CanvasViewModel.combineDown(l: LayerInfo) = layerChange { it.combineDown(l.id) }

fun CanvasViewModel.ungroup(l: LayerInfo) = layerChange { it.ungroup(l.id) }

fun CanvasViewModel.flattenGroup(l: LayerInfo) = layerChange { it.flattenGroup(l.id) }

fun CanvasViewModel.toggleCollapsed(l: LayerInfo) = layerChange(thumbs = false) { it.setGroupCollapsed(l.id, !l.collapsed) }

fun CanvasViewModel.selectLayerContents(l: LayerInfo) {
    session?.selectLayerContents(l.id)
    refreshFlags()
}

fun CanvasViewModel.copyLayerContents(l: LayerInfo) {
    clipboard = session?.copyLayerContents(l.id) ?: clipboard
    offerPaste()
}

fun CanvasViewModel.fillLayer(l: LayerInfo) = layerChange { it.fillLayer(l.id) }

fun CanvasViewModel.invertLayer(l: LayerInfo) = layerChange { it.invertLayer(l.id) }

fun CanvasViewModel.toggleReference(l: LayerInfo) = layerChange(thumbs = false) { it.setLayerReference(l.id, !l.reference) }

fun CanvasViewModel.toggleClipped(l: LayerInfo) = layerChange(thumbs = false) { it.setLayerClipped(l.id, !l.clipped) }

/** Locks or unlocks [l], saying so in the notice band as the core words it ("Lock layer", "Unlock Layer Group"). */
fun CanvasViewModel.toggleLocked(l: LayerInfo) {
    val on = !l.locked
    val said = session?.lockSaid(l.id, on) ?: return
    var done = false
    layerChange(thumbs = false) { done = it.setLayerLocked(l.id, on) }
    if (done) showDone(said)
}

fun CanvasViewModel.duplicate(l: LayerInfo) = layerChange { if (it.duplicateLayer(l.id) == null) message = noRoom() }

fun CanvasViewModel.delete(l: LayerInfo) {
    if (l.locked) {
        lockedDeleteTold = true
        return
    }
    layerChange { it.deleteLayer(l.id) }
}

fun CanvasViewModel.mergeDown(l: LayerInfo) = layerChange { it.mergeDown(l.id) }

fun CanvasViewModel.mergeMask(l: LayerInfo) = layerChange { it.mergeMask(l.id) }

/** Picks layer [l] to act on with the current one, or unpicks it. */
fun CanvasViewModel.pickLayer(l: LayerInfo) = layerChange(thumbs = false) { it.pickLayer(l.id) }

fun CanvasViewModel.pickAllLayers() = layerChange(thumbs = false) { it.pickAllLayers() }

fun CanvasViewModel.groupPicked() = layerChange { it.groupPicked() }

fun CanvasViewModel.mergePicked() = layerChange { it.mergePicked() }

/** Two rows pinched together: layers [a] and [b] and every one between merge into the lowest. */
fun CanvasViewModel.mergeBetween(a: ULong, b: ULong) = layerChange { it.mergeBetween(a, b) }

fun CanvasViewModel.duplicatePicked() = layerChange { if (!it.duplicatePicked()) message = noRoom() }

fun CanvasViewModel.deletePicked() = layerChange { it.deletePicked() }

/** Whether the chosen layers include a locked one (Delete then removes nothing). */
fun CanvasViewModel.chosenLocked(): Boolean = session?.chosenLocked() == true

/** The current and picked layers each as a PNG in the cache, to share. */
suspend fun CanvasViewModel.chosenLayerFiles(): List<File> {
    val s = session ?: return emptyList()
    return withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(File(getApplication<Application>().cacheDir, "exports"), "layers").apply {
                deleteRecursively()
                mkdirs()
            }
            s.exportChosenPngs(folder.path).map { File(it) }
        }.onFailure { AppLog.e(TAG, "exporting layers", it) }.getOrDefault(emptyList())
    }
}

fun CanvasViewModel.clearCurrentLayer() {
    refresh()
    layers.firstOrNull { it.current }?.let {
        if (currentTakesChanges()) {
            clear(it)
            showDone("Layer cleared")
        }
    }
}

fun CanvasViewModel.clear(l: LayerInfo) = layerChange {
    it.selectLayer(l.id)
    it.clearLayer()
}

fun CanvasViewModel.rename(l: LayerInfo, name: String) = layerChange(thumbs = false) { it.renameLayer(l.id, name) }

/** Moves a layer to `index` in the panel's top-first order. */
fun CanvasViewModel.moveTo(l: LayerInfo, index: Int) = layerChange(thumbs = false) {
    val fromBottom = (layers.size - 1 - index).coerceAtLeast(0)
    it.moveLayer(l.id, fromBottom.toUInt())
}

fun CanvasViewModel.toggleBackground() = layerChange(thumbs = false) { it.setBackgroundVisible(!background.visible) }

fun CanvasViewModel.pickBackground() {
    backgroundPicking = true
    // from the Layers panel floating: the background's colours hang from the Layers button
    panel = Panel.LAYERS
}

/** The background's colour as 0xRRGGBB. */
fun CanvasViewModel.backgroundRgb(): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
    return (c(background.r) shl 16) or (c(background.g) shl 8) or c(background.b)
}

/** Sets the background's colour, live while a picker moves; [done] makes the change one undo step. */
fun CanvasViewModel.setBackgroundRgb(rgb: Int, done: Boolean) {
    val s = session ?: return
    s.setBackgroundColor(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, done)
    if (done) refresh() else background = s.background()
}

/** The background picker closes (its tick, or the panel going away): the colour is kept as one undo step. */
fun CanvasViewModel.endBackgroundPick() {
    if (!backgroundPicking) return
    backgroundPicking = false
    setBackgroundRgb(backgroundRgb(), done = true)
}

internal fun CanvasViewModel.layerChange(thumbs: Boolean = true, f: (CanvasSession) -> Any?) {
    val s = session ?: return
    f(s)
    refresh()
    if (thumbs && layersShown) refreshThumbnails()
}

/** Re-reads the layers and undo state from the session. */
fun CanvasViewModel.refresh() {
    val s = session ?: return
    // what this change may make fail (its save, a smudge the GPU has no memory for) is told
    failureWatch.poke()
    watchFill(s)
    refreshReference()
    recordFrame()
    // undo and redo keep a snapped shape as drawn first
    if (quickShape != null && s.quickshapeInfo() == null) {
        quickShape = null
        quickShapeEditing = false
    }
    layers = s.layers()
    followMaskColour()
    // drawing assist is the current layer's
    if (guide != null) guide = s.guide()
    background = s.background()
    // undoing may put the canvas back in another profile
    refreshCanvasSpace(s)
    refreshFlags()
    refreshAnimation()
}

internal fun CanvasViewModel.refreshFlags() {
    val info = session?.info() ?: return
    canUndo = info.canUndo
    canRedo = info.canRedo
    hasSelection = session?.hasSelection() ?: false
    refreshTakenBox()
}

/** Reads again the thumbnails of the layers whose pixels changed since theirs was read (or have none). */
/** The layers' thumbnails drawn again, while the Layers panel shows (a floating one opening). */
fun CanvasViewModel.refreshThumbnailsShown() {
    if (layersShown) refreshThumbnails()
}

internal fun CanvasViewModel.refreshThumbnails() {
    val s = session ?: return
    viewModelScope.launch {
        val versions = withContext(Dispatchers.Default) { runCatching { s.layerVersions() }.getOrNull() } ?: return@launch
        if (session !== s) return@launch
        for (v in versions) {
            if (v.id !in thumbnails || thumbnailVersions[v.id] != v.version) loadThumbnail(v.id, v.version)
        }
    }
}

/** Reads layer [id]'s thumbnail; [version], its pixels' version when asked (none: not known). */
internal fun CanvasViewModel.loadThumbnail(id: ULong, version: ULong? = null) {
    val s = session ?: return
    viewModelScope.launch {
        val image = withContext(Dispatchers.Default) {
            // one the core fails to read keeps the one shown, and goes in the log
            val t = try {
                s.layerThumbnail(id, THUMB_SIDE.toUInt())
            } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
                AppLog.e("layers", "a layer's thumbnail failed in the core", fault)
                null
            }
            if (t == null || t.width == 0u || t.height == 0u || t.rgba.size < t.width.toInt() * t.height.toInt() * 4) return@withContext null
            val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
            bitmap.asImageBitmap()
        }
        if (image != null && session === s) {
            thumbnails[id] = image
            if (version != null) thumbnailVersions[id] = version else thumbnailVersions.remove(id)
        }
    }
}

/** The canvas's (shared) touches changed the brush's size or opacity (Hover's): the sliders show it. */
fun CanvasViewModel.brushTouched() = readSliders()

internal fun CanvasViewModel.readSliders() {
    val info = session?.info() ?: return
    size = info.size
    opacity = info.opacity
    readNotches()
}

