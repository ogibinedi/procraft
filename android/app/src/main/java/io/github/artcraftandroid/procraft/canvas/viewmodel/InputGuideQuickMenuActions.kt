package io.github.artcraftandroid.procraft.canvas

import io.github.artcraftandroid.procraft.ui.CanvasSpace
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.core.BrushInfo
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.KeyShortcut
import io.github.artcraftandroid.procraft.core.ModeButton
import io.github.artcraftandroid.procraft.core.GuideType
import io.github.artcraftandroid.procraft.core.GuideUnit
import io.github.artcraftandroid.procraft.core.SymmetryType
import io.github.artcraftandroid.procraft.core.Adjustment
import io.github.artcraftandroid.procraft.core.GesturePurpose
import io.github.artcraftandroid.procraft.core.QuickAction
import io.github.artcraftandroid.procraft.core.Trigger
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.LayerType
import io.github.artcraftandroid.procraft.core.ToolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.*
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.BRUSHES_SEEN
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.BRUSH_CHANGES
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.BRUSH_CHOICES
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.BRUSH_LIBRARY
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.DEFAULT_ENVIRONMENT
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.ENVIRONMENTS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.MODIFY_TAP_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.QUICK_MENUS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.QUICK_MENU_ACTIVE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SELECTION_X_RAY

/** The app is back in front: time on the open artwork counts again. */
fun CanvasViewModel.resumeTracking() {
    if (session != null) activeSince = android.os.SystemClock.elapsedRealtime()
}

/**
 * The pen's side button did what the shared touches set it to: the tool, the eyedropper and
 * drawing assist read again as they now are.
 */
fun CanvasViewModel.penButtonDone() {
    val s = session ?: return
    pickNextShown = s.pickNext()
    val now = s.info().tool
    if (now != tool) {
        feedback()
        endRecolor()
        tool = now
        readSliders()
        currentBrush = s.currentBrush()
    }
    guide = s.guide()
}

/**
 * The tablet took three fingers on the canvas for a gesture of its own (see [systemTookFingers]):
 * said once, with the way to have three-finger taps, scrubs and swipes back.
 */
fun CanvasViewModel.systemTookFingers() {
    if (!fingersTakenTold.first()) return
    showNotice("The tablet took that three-finger touch: turn off its three-finger screenshot to use three fingers here.", warning = true)
}

/** A pen's side button came as a key (see [penKeyButton]): true if a canvas took it. */
fun CanvasViewModel.penKey(second: Boolean, action: PenKeyAction = PenKeyAction.PRESSED): Boolean {
    if (screen != Screen.CANVAS || session == null) return false
    val heard = penKeyHeard ?: return false
    heard(second, action)
    return true
}

/** The modify button tapped: what Tap □ is set to do (open or close the eyedropper at first). */
fun CanvasViewModel.tapModify() {
    when (gestures.purposeOf(Trigger.TAP_MODIFY)) {
        GesturePurpose.EYEDROPPER -> pickNext = !pickNext
        GesturePurpose.QUICKMENU -> toggleQuickMenu(Offset(screenCentre.x, screenCentre.y))
        GesturePurpose.COPY_PASTE -> copyPasteOpen = true
        GesturePurpose.QUICKSHAPE -> quickShapeLast()
        else -> Unit
    }
}

fun CanvasViewModel.modifyPressed() {
    modifyHeld = true
    modifyUsed = false
    modifyDownAt = android.os.SystemClock.uptimeMillis()
    session?.setModifyHeld(true)
}

/** □ let go: a short press nothing used is a tap ([cancelled] when the finger slid off). */
fun CanvasViewModel.modifyReleased(cancelled: Boolean) {
    modifyHeld = false
    // a touch the canvas's touches took meanwhile used it too
    if (session?.setModifyHeld(false) == true) modifyUsed = true
    if (!cancelled && !modifyUsed && android.os.SystemClock.uptimeMillis() - modifyDownAt < MODIFY_TAP_MS) tapModify()
}

/** The gesture settings as the canvas's touches (in the shared core) take them. */
fun CanvasViewModel.touchSettings(): io.github.artcraftandroid.procraft.core.TouchSettings =
    tabletTouchSettings(gestures.touchSettings(rapidUndoDelay * 1000.0), penButtonActions)

/** The artwork's facts, its time counted up to now. */
fun CanvasViewModel.artworkInfo(): io.github.artcraftandroid.procraft.core.ArtworkInfo? {
    val s = session ?: return null
    if (activeSince > 0) {
        noteTime()
        resumeTracking()
    }
    return s.artworkInfo()
}

/** The artwork's folder (for its size and when it was saved). */
fun CanvasViewModel.artworkFolder(): File? = artworkDir

fun CanvasViewModel.timeLapseInfo(): Pair<Int, Long> = timeLapse?.let { it.count to it.bytes() } ?: (0 to 0L)

/** The time-lapse's video settings, kept with the artwork. */
fun CanvasViewModel.timeLapseSettings(): TimeLapseSettings = timeLapse?.settings ?: TimeLapseSettings()

/** Renames the artwork and sets its author, from Canvas information. */
fun CanvasViewModel.changeArtworkInfo(name: String, author: String) {
    val s = session ?: return
    if (name.isNotBlank() && name.trim() != s.name()) s.setName(name.trim())
    s.setAuthor(author)
}

/**
 * Keeps the author's photo (the picture at [photo], when one was picked) and the [signature]
 * (strokes of x, y across its box) in the artwork's folder, off the main thread; each takes
 * the old one's place only once written whole, and a notice says which couldn't be kept.
 */
fun CanvasViewModel.keepAbout(photo: android.net.Uri?, signature: List<List<Float>>) {
    val dir = artworkDir ?: return
    viewModelScope.launch {
        val failed = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val photoKept = photo == null || runCatching {
                val bytes = resolver.openInputStream(photo)?.use { it.readBytes() } ?: error("no photo")
                io.github.artcraftandroid.procraft.core.writePhoto(dir.path, bytes)
            }.isSuccess
            val signed = runCatching { io.github.artcraftandroid.procraft.core.writeSignature(dir.path, signature) }.isSuccess
            when {
                !photoKept -> "The photo couldn't be kept."
                !signed -> "The signature couldn't be kept."
                else -> null
            }
        }
        failed?.let { showNotice(it, warning = true) }
    }
}

/** Puts the canvas in colour profile [name], its colours and the current colour turned into it. */
fun CanvasViewModel.changeProfile(name: String) {
    val s = session ?: return
    val from = s.profile()
    val rgb = colorRgb()
    if (!s.changeProfile(name)) return
    refresh()
    setRgb(io.github.artcraftandroid.procraft.core.convertColor(rgb.toUInt(), from, name).toInt())
}

/** Saves the open artwork where it is (the app going into the background). */
fun CanvasViewModel.saveNow() {
    keepColour()
    val s = session ?: return
    val dir = artworkDir ?: return
    noteTime()
    keepChoices(s)
    viewModelScope.launch(Dispatchers.IO) {
        told(runCatching { s.save(dir.path) }.map { true })
        // its new picture on the home screen's widget
        io.github.artcraftandroid.procraft.gallery.RecentArtworkWidget.refresh(getApplication<Application>())
    }
}

/**
 * Gives [s] the app's brushes as they were kept: the library (else the edits earlier versions
 * kept), brushes shipped since joining it once, and the brushes in use. Reading the library
 * takes half a second, so callers off the main thread do this before [start].
 */
internal fun CanvasViewModel.loadBrushes(s: CanvasSession) {
    // earlier versions kept the library, what had shipped and the brushes in use in preferences:
    // moved into the brushes' folder once
    if (!brushLibraryFile.exists()) {
        val kept = brushPrefs.getString(BRUSH_LIBRARY, null)
        if (kept != null && s.loadBrushLibrary(kept) && s.saveBrushLibraryFile(brushLibraryFile.path)) {
            brushPrefs.edit().remove(BRUSH_LIBRARY).apply()
        }
    }
    if (brushPrefs.contains(BRUSHES_SEEN) || brushPrefs.contains(BRUSH_CHOICES)) {
        val seen = brushPrefs.getString(BRUSHES_SEEN, null)
        val choices = brushPrefs.getString(BRUSH_CHOICES, null)
        if (io.github.artcraftandroid.procraft.core.adoptKeptBrushes(brushesDir.path, seen, choices)) {
            brushPrefs.edit().remove(BRUSHES_SEEN).remove(BRUSH_CHOICES).apply()
        }
    }
    // the library as kept, with what shipped since joining it, and the brushes in use as left;
    // else edits kept by versions before the library was
    if (!s.openBrushes(brushesDir.path)) brushPrefs.getString(BRUSH_CHANGES, null)?.let { s.applyBrushChanges(it) }
}

internal fun CanvasViewModel.start(s: CanvasSession, dir: File, brushesLoaded: Boolean = false) {
    // Gradient Map maps by the library
    s.useGradients(gradientLibrary)
    // the canvas's colours are P3 or sRGB numbers: the colour panel and swatches follow
    refreshCanvasSpace(s)
    s.setSelectionVisibility(selectionVisibility)
    s.setBrushScaling(brushOnScreen)
    s.setLightInterface(lightInterface)
    s.setInterfaceSize(interfaceShown)
    // the system's animations off: a dropped colour's fill shows at once
    s.setReduceMotion(!android.animation.ValueAnimator.areAnimatorsEnabled())
    applyPlugins(s)
    // the controls kept with this artwork, as it keeps them
    readPlugins()
    s.setInputSettings(inputStabilization, inputMotionFiltering, inputMotionExpression, inputPressureSmoothing, inputTipAttached, inputCurve.flatMap { listOf(it.x, it.y) })
    applyAppSettings(s)
    timeLapse = TimeLapse(dir).also {
        timeLapseRecording = it.recording
        timeLapseRecorded = it.count > 0
    }
    if (!brushesLoaded) loadBrushes(s)
    s.useColourHistory(colourHistory)
    session = s
    val model = s.modelInfo()
    modelOpen = model != null
    textureSets = model?.textureSets ?: emptyList()
    textureSet = s.textureSet().toInt()
    modelFlat = s.modelFlat()
    paintThrough = s.paintThrough()
    submeshes = model?.submeshes ?: emptyList()
    readMeshes()
    openSubmesh = null
    submeshThumbnails.clear()
    selectionXRay = interfacePrefs.getBoolean(SELECTION_X_RAY, false)
    s.setSelectionXRay(selectionXRay)
    modelWarnings = s.takeModelWarnings().map { io.github.artcraftandroid.procraft.core.modelWarningName(it) }
    modelChannel = s.modelChannel().toInt()
    channelTileChosen = false
    channelColors.fill(null)
    channelTrayFor = null
    channelThumbnails.clear()
    modelExposure = s.modelExposure()
    modelEnvironmentShown = s.modelEnvironmentShown()
    modelLights = s.modelLights()
    lightingStudioOpen = false
    // a model is lit by the environment it was last lit by, else the default
    if (model != null) chooseEnvironment(s.modelEnvironment()?.takeIf { it in ENVIRONMENTS } ?: DEFAULT_ENVIRONMENT)
    // brush files that came while no canvas was open
    addPendingBrushes()
    pushSecondary()
    artworkDir = dir
    applyFitInsets()
    attachProjection()
    activeSince = android.os.SystemClock.elapsedRealtime()
    thumbnails.clear()
    thumbnailVersions.clear()
    previews.clear()
    shadedNames.clear()
    panel = Panel.NONE
    barsHidden = false
    restoreReference(s)
    transforming = false
    transformBox = null
    adjustment = null
    selecting = false
    hasSelection = false
    freehand = emptyList()
    tool = ToolKind.PAINT
    applyColor()
    readSliders()
    refresh()
    brushSets = s.brushLibrary()
    readLibraries()
    currentBrush = s.currentBrush()
    screen = Screen.CANVAS
    // with the canvas shown (before, the sound had no canvas to follow and stayed off until
    // the plugins next changed)
    followStrokeSound()
    // a new artwork exists in the gallery from the start (an opened one is there already)
    if (!File(dir, "artwork.json").exists()) viewModelScope.launch(Dispatchers.IO) { runCatching { s.save(dir.path) } }
    // kept saved by the core, a few seconds after it changes, once nothing is under way that
    // saving would cut short (only the layers that changed are written); a failure told
    s.startAutosave(dir.path)
    s.setShown(inFront)
    activityLogged = null
    logActivity(true)
    failureWatch.poke()
    // a canvas the device's graphics can't draw says so (one opened from its cell, by the zoom)
    if (galleryZoom == null) viewModelScope.launch { awaitCanvasDrawn() }
}

fun CanvasViewModel.select(tool: ToolKind) {
    feedback()
    endRecolor()
    session?.setTool(tool)
    this.tool = tool
    readSliders()
    currentBrush = session?.currentBrush()
}

/** A tool button: the first tap picks the tool, the next opens its brush library. */
// the drawing guide

fun CanvasViewModel.setGuideOn(on: Boolean) {
    guide = session?.setGuideOn(on)
}

/**
 * Opens the guide editor (turning the guide on), the canvas moving into the middle of the
 * screen; the canvas's touches work the guide (and move the view) until it closes.
 */
fun CanvasViewModel.openGuideEditor() {
    val s = session ?: return
    endQuickShape()
    closePanels()
    if (guide?.on != true) s.setGuideOn(true)
    guide = s.guideEditBegin()
    s.setTouchGuideEditing(true)
    guideEditing = true
    followView()
}

fun CanvasViewModel.closeGuideEditor(keep: Boolean) {
    session?.setTouchGuideEditing(false)
    guide = session?.guideEditEnd(keep)
    guideEditing = false
    symmetryOptionsOpen = false
    guideNodeMenu = null
    followView()
}

fun CanvasViewModel.chooseGuideKind(kind: GuideType) {
    guide = session?.setGuideKind(kind)
    symmetryOptionsOpen = false
    guideNodeMenu = null
}

/** What the guide editor's handle menu offers, picked: the point deleted, or the centre put back. */
fun CanvasViewModel.guideNodeAction(menu: GuideNodeMenu) {
    guideNodeMenu = null
    val s = session ?: return
    guide = if (menu.point != null) s.removeVanishingPoint(menu.point.toUInt()) else s.resetGuidePlace()
}

/** The shown kind's look, each left as it is when not given. */
fun CanvasViewModel.setGuideLook(opacity: Float? = null, thickness: Float? = null, size: Float? = null, hue: Float? = null) {
    val g = guide ?: return
    guide = session?.setGuideLook(opacity ?: g.opacity, thickness ?: g.thickness, size ?: g.size, hue ?: g.hue)
}

/** The shown grid's size typed on its keypad: [typed] so far, in [unit]. It changes at once. */
fun CanvasViewModel.setGuideLength(typed: String, unit: GuideUnit) {
    guide = session?.setGuideLength(typed, unit)
}

fun CanvasViewModel.setGuideSymmetry(kind: SymmetryType, rotational: Boolean) {
    guide = session?.setGuideSymmetry(kind, rotational)
}

/** Drawing assist on the current layer. */
fun CanvasViewModel.setGuideAssisted(on: Boolean) {
    guide = session?.setGuideAssisted(on)
    refresh()
}

/**
 * The guide editor's canvas touched ([touched]: a touch came down, closing symmetry's options)
 * or its handle dragged: the guide as it now is (a perspective tap may have added a point).
 */
fun CanvasViewModel.guideTouched(touched: Boolean) {
    if (touched) {
        symmetryOptionsOpen = false
        guideNodeMenu = null
    }
    guide = session?.guide() ?: guide
}

// copy and paste, flipping, pictures

/** Copies the current layer within the selection, to paste. */
fun CanvasViewModel.copyLayer() {
    layerCopied()?.let { clipboard = it }
    offerPaste()
}

/** The current layer within the selection, copied; null if nothing was. */
internal fun CanvasViewModel.layerCopied(): io.github.artcraftandroid.procraft.core.ThumbnailImage? {
    if (!currentTakesChanges(copy = true)) {
        closePanels()
        return null
    }
    val copied = session?.copyLayer()
    closePanels()
    if (copied != null) showDone("Layer copied")
    return copied
}

fun CanvasViewModel.copyCanvas() {
    val s = session ?: return
    closePanels()
    // off the main thread: the picture waits for the canvas to finish any remaking first
    viewModelScope.launch {
        val copied = withContext(Dispatchers.IO) { runCatching { s.copyCanvas() }.getOrNull() }
        clipboard = copied ?: clipboard
        offerPaste()
        if (copied != null) showDone("Canvas copied")
    }
}

/** Cuts the current layer within the selection, to paste. */
fun CanvasViewModel.cutLayer() {
    layerCut()?.let { clipboard = it }
    offerPaste()
}

/** The current layer within the selection, cut; null if nothing was. */
internal fun CanvasViewModel.layerCut(): io.github.artcraftandroid.procraft.core.ThumbnailImage? {
    if (!currentTakesChanges(cut = true)) {
        closePanels()
        return null
    }
    val cut = session?.cutLayer()
    closePanels()
    refresh()
    if (cut != null) showDone("Layer cut")
    return cut
}

/**
 * What was cut or copied last, here or in another app, on a new layer; a [private] one is left
 * out of the gallery's thumbnail. Another app's picture comes in as an inserted one does, ready
 * to transform.
 */
fun CanvasViewModel.paste(private: Boolean = false) {
    // a picture copied after the last copy here goes first (only then is the clip itself read)
    val kinds = runCatching { systemClipboard?.primaryClipDescription }.getOrNull()
    if (kinds != null && isPicture(kinds) && pastesSystemPicture(false, kinds.timestamp, clipboard != null, clipboardAt, clipPutAt)) {
        val uri = runCatching { systemClipboard?.primaryClip }.getOrNull()?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri != null && uri != clipUri) {
            pastePicture(uri, private)
            return
        }
    }
    paste(clipboard ?: return, private)
}

/** Whether the picture at [uri] can be read (another app's, which the system may not let this one have). */
internal fun CanvasViewModel.readable(uri: android.net.Uri): Boolean = runCatching {
    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
}.getOrDefault(false)

/**
 * Another app's picture at [uri], pasted: into the current mask if a plugin says so, else as an
 * inserted picture. One that can't be read leaves the copy made here to paste, if there is one.
 */
internal fun CanvasViewModel.pastePicture(uri: android.net.Uri, private: Boolean) {
    val s = session ?: return
    closePanels()
    val own = clipboard
    viewModelScope.launch {
        if (own != null && !withContext(Dispatchers.IO) { readable(uri) }) {
            paste(own, private)
            return@launch
        }
        // a mask that takes what is pasted has no use for a copy of the picture
        if (!private && !(modelOpen && !modelFlat) && s.pastesIntoMask()) {
            val image = withContext(Dispatchers.IO) { decodePicture(uri, io.github.artcraftandroid.procraft.ui.CanvasSpace.p3) }
            if (image != null && withContext(Dispatchers.Default) { s.pastePictureIntoMask(image) }) {
                refresh()
                return@launch
            }
        }
        insertPictureNow(uri, private, transform = true)
    }
}

/** A copy ([image]) on a new layer; a [private] one is left out of the gallery's thumbnail. */
internal fun CanvasViewModel.paste(image: io.github.artcraftandroid.procraft.core.ThumbnailImage, private: Boolean) {
    val s = session
    // on a model it is placed on the surface, not in the texture
    if (s != null && modelOpen && !modelFlat) {
        closePanels()
        if (s.modelPictureBegin(image, false)) readModelPicture() else message = noRoom()
        refresh()
        return
    }
    // through a plugin it takes a moment on a big canvas: the screen doesn't wait for it
    if (!private && s?.pastesIntoMask() == true) {
        closePanels()
        viewModelScope.launch {
            if (withContext(Dispatchers.Default) { s.paste(image) } == null) message = noRoom()
            refresh()
        }
        return
    }
    if ((if (private) s?.pastePrivate(image) else s?.paste(image)) == null) message = noRoom()
    closePanels()
    refresh()
    if (layersShown) refreshThumbnails()
}

/** Layer Select at window point [at]: the top layer drawn there becomes current, the others listed. */
fun CanvasViewModel.layerSelectAt(at: Offset) {
    // on a model, the mesh there is picked
    if (modelOpen && !modelFlat) {
        val said = session?.pickSubmeshAt(at.x, at.y) ?: return
        readMeshes()
        openSubmesh = activeSubmeshes.firstOrNull()
        textureSet = session?.textureSet()?.toInt() ?: textureSet
        refresh()
        showDone(said)
        return
    }
    val s = session ?: return
    // read back from the GPU off the main thread, as a finger set to pick layers asks on every touch
    viewModelScope.launch {
        val ids = withContext(Dispatchers.Default) { s.layersAt(at.x, at.y) }
        if (session !== s) return@launch
        if (ids.isEmpty()) {
            message = "Nothing is drawn there."
            return@launch
        }
        selectLayer(ids.first())
        layerChoice = if (ids.size > 1) at to ids else null
    }
}

fun CanvasViewModel.setQuickSlot(i: Int, a: QuickAction) = setQuickSlot(i, a, 0uL)

/** Sets button [i] of the ring to give brush [b] to the current tool. */
fun CanvasViewModel.setQuickSlotBrush(i: Int, b: BrushInfo) {
    val s = session ?: return
    val key = s.quickBrushKey(b.set, b.index)
    if (key == 0uL) return
    setQuickSlot(i, QuickAction.BRUSHES, key)
    // the library keeps the brush's key, following it about
    keepChoices(s)
}

internal fun CanvasViewModel.setQuickSlot(i: Int, a: QuickAction, brush: ULong) {
    val menu = quickMenus.getOrNull(quickMenuShown) ?: return
    if (i !in menu.slots.indices) return
    val brushes = MutableList(menu.slots.size) { menu.brushes.getOrElse(it) { 0uL } }.also { it[i] = brush }
    val slots = menu.slots.toMutableList().also { it[i] = a }
    quickMenus = quickMenus.toMutableList().also { it[quickMenuShown] = menu.copy(slots = slots, brushes = brushes) }
    keepQuickMenus()
}

/** The brush button [i] of the ring gives the current tool, if it is set to one still in the library. */
fun CanvasViewModel.quickSlotBrush(i: Int): BrushInfo? {
    val menu = quickMenus.getOrNull(quickMenuShown) ?: return null
    val key = menu.brushes.getOrElse(i) { 0uL }
    if (menu.slots.getOrNull(i) != QuickAction.BRUSHES || key == 0uL) return null
    return session?.quickBrush(key)
}

/** What button [i] of the ring says: the brush's name when it is set to one. */
fun CanvasViewModel.quickSlotLabel(i: Int): String = quickSlotBrush(i)?.name ?: quickMenuSlots.getOrNull(i)?.button.orEmpty()

/** Runs button [i] of the ring: its brush given to the current tool, else its action. */
fun CanvasViewModel.runQuickSlot(i: Int, at: Offset) {
    quickSlotBrush(i)?.let {
        chooseBrush(it)
        return
    }
    quickMenuSlots.getOrNull(i)?.let { runQuickAction(it, at) }
}

/** Adds a menu ("QuickMenu N", every button No Action) and shows it; the active one stays until a menu is chosen. Returns its index. */
fun CanvasViewModel.addQuickMenu(): Int {
    quickMenus = io.github.artcraftandroid.procraft.core.quickMenuAdded(quickMenus)
    quickMenuShown = quickMenus.size - 1
    keepQuickMenus()
    return quickMenuShown
}

/** Makes menu [i] the active one and shows it. */
fun CanvasViewModel.chooseQuickMenu(i: Int) {
    if (i !in quickMenus.indices) return
    quickMenuActive = i
    quickMenuShown = i
    keepQuickMenus()
}

/** Names menu [i] [name] (left empty, it keeps its name). */
fun CanvasViewModel.renameQuickMenu(i: Int, name: String) {
    if (i !in quickMenus.indices) return
    quickMenus = io.github.artcraftandroid.procraft.core.quickMenuRenamed(quickMenus, i.toUInt(), name)
    keepQuickMenus()
}

/** Deletes menu [i] (never the last one left); the ring shows the one before it if it showed it. */
fun CanvasViewModel.deleteQuickMenu(i: Int) {
    if (quickMenus.size < 2 || i !in quickMenus.indices) return
    quickMenus = quickMenus.toMutableList().also { it.removeAt(i) }
    quickMenuActive = io.github.artcraftandroid.procraft.core.quickMenuActiveAfter(quickMenuActive.toUInt(), i.toUInt()).toInt()
    quickMenuShown = io.github.artcraftandroid.procraft.core.quickMenuActiveAfter(quickMenuShown.toUInt(), i.toUInt()).toInt()
    keepQuickMenus()
}

internal fun CanvasViewModel.keepQuickMenus() {
    interfacePrefs.edit()
        .putString(QUICK_MENUS, io.github.artcraftandroid.procraft.core.quickMenusSaved(quickMenus))
        .putInt(QUICK_MENU_ACTIVE, quickMenuActive)
        .apply()
}

/** Sets the pen's [second] button (else its first) to do [a] (null: what Gesture controls say). */
fun CanvasViewModel.setPenButtonAction(second: Boolean, a: QuickAction?) {
    val i = if (second) 1 else 0
    penButtonActions = penButtonActions.toMutableList().also { if (i in it.indices) it[i] = a }
    interfacePrefs.edit().putString(CanvasViewModel.PEN_BUTTONS, io.github.artcraftandroid.procraft.core.penButtonActionsSaved(penButtonActions)).apply()
}

/**
 * A [press] of a hardware keyboard on the canvas (its key as the shared shortcuts name it,
 * crates/core keys.rs, which say what it does): true if it is a shortcut, done (as a plugin
 * rebinding keys takes it), or the key a plugin's panel asked for. Typing in a text field never
 * comes here. A key held down presses again ([repeat]): that only moves the view, as a plugin's
 * key for one of its own moves does.
 */
internal fun CanvasViewModel.keyShortcut(press: KeyPress, repeat: Boolean = false): Boolean {
    if (screen != Screen.CANVAS || session == null) return false
    val (name, command, shift, alt) = press
    if (pluginPanel?.keyPress == true) {
        if (repeat) return false
        pluginPanelKey(io.github.artcraftandroid.procraft.core.KeyCombo(name, command, shift, alt))
        return true
    }
    // the arrows move what is transformed, or the strokes the selection takes
    val nudges = transforming || takenBox.isNotEmpty()
    val shortcut = plugins.keyShortcut(name, command, shift, alt, nudges) ?: return pluginPress(press, repeat)
    if (repeat && !movesView(shortcut)) return false
    when (shortcut) {
        KeyShortcut.Undo -> undo()
        KeyShortcut.Redo -> redo()
        KeyShortcut.CopyLayer -> copyLayer()
        KeyShortcut.CutLayer -> cutLayer()
        KeyShortcut.Paste -> paste()
        KeyShortcut.Paint -> toolKey(ToolKind.PAINT)
        KeyShortcut.Erase -> toolKey(ToolKind.ERASE)
        KeyShortcut.Eyedropper -> pickNext = !pickNext
        KeyShortcut.Selection -> toggleSelecting()
        KeyShortcut.Transform -> toggleTransform()
        KeyShortcut.HueSaturationBrightness -> startAdjustment(Adjustment.HSB)
        KeyShortcut.Colours -> toggle(Panel.COLOR)
        KeyShortcut.Actions -> toggle(Panel.ACTIONS)
        KeyShortcut.Layers -> toggle(Panel.LAYERS)
        KeyShortcut.SwapColours -> swapColors()
        KeyShortcut.Deselect -> clearSelection()
        KeyShortcut.DuplicateLayer -> duplicateCurrent()
        KeyShortcut.Interface -> toggleBars()
        KeyShortcut.ClearLayer -> clearCurrentLayer()
        KeyShortcut.CopyCanvas -> runQuickAction(QuickAction.COPY_CANVAS, screenCentre)
        KeyShortcut.ColourBalance -> runQuickAction(QuickAction.COLOR_BALANCE, screenCentre)
        KeyShortcut.PerspectiveGuide -> runQuickAction(QuickAction.GUIDE_PERSPECTIVE, screenCentre)
        KeyShortcut.QuickMenu -> toggleQuickMenu(screenCentre)
        KeyShortcut.GreyView -> session?.let { it.setGreyView(!it.greyView()) }
        is KeyShortcut.BrushSize -> changeSize((size + shortcut.by).coerceIn(0f, 1f))
        is KeyShortcut.Nudge -> if (transforming) {
            session?.transformArrow(shortcut.dx, shortcut.dy)?.let { transformMoved(it) }
        } else if (session?.nudgePluginItems(shortcut.dx, shortcut.dy) == true) {
            refresh()
        }
        // the view's own moves, which a plugin gives keys to
        is KeyShortcut.ZoomView, is KeyShortcut.TurnView, is KeyShortcut.PanView -> {
            session?.keyView(shortcut)
            refreshView()
        }
        KeyShortcut.FitView -> {
            session?.keyView(shortcut)
            followView()
        }
    }
    return true
}

/** A press none of the app's shortcuts take, as a plugin gives it one of its switches (flipped, kept as the card keeps it) or actions (run as its row runs it); false if no plugin's. */
internal fun CanvasViewModel.pluginPress(press: KeyPress, repeat: Boolean): Boolean {
    if (repeat) return false
    val (name, command, shift, alt) = press
    // as a plugin rebinding keys takes it (it may make it no press at all)
    val taken = plugins.keyPress(io.github.artcraftandroid.procraft.core.KeyCombo(name, command, shift, alt)) ?: return false
    when (val pressed = plugins.pluginPress(taken) ?: return false) {
        is io.github.artcraftandroid.procraft.core.PluginPressed.Switched -> {
            val control = pressed.control
            if (control is io.github.artcraftandroid.procraft.core.PluginControl.Switch) {
                interfacePrefs.edit().putBoolean(control.control.key, control.control.on).apply()
            }
            pluginsChanged()
        }
        is io.github.artcraftandroid.procraft.core.PluginPressed.Action -> runPluginAction(pressed.key)
    }
    return true
}

/** Whether [shortcut] moves the view by a step, which a key held down does again and again. */
internal fun CanvasViewModel.movesView(shortcut: KeyShortcut) =
    shortcut is KeyShortcut.ZoomView || shortcut is KeyShortcut.TurnView || shortcut is KeyShortcut.PanView

/** The key [press] a plugin's panel asked for: told to the plugin off the main thread (it acts as a touch on the panel does), the panel shown as it answers. It asked for that press alone. */
internal fun CanvasViewModel.pluginPanelKey(press: io.github.artcraftandroid.procraft.core.KeyCombo) {
    val s = session ?: return
    pluginPanel = pluginPanel?.copy(keyPress = false)
    viewModelScope.launch {
        withContext(Dispatchers.Default) { plugins.panelKey(press, s) }
        pluginPanel = plugins.panel()
        s.takeFailure()?.let { showNotice(it) }
        readPlugins()
        refresh()
    }
}

/** Alt on a hardware keyboard went down or up: held it is □ held; it taps nothing, being in the system's own shortcuts. */
fun CanvasViewModel.modifyKey(down: Boolean): Boolean {
    if (screen != Screen.CANVAS || session == null) return false
    if (down) modifyPressed() else modifyReleased(true)
    return true
}

/** Does [a], a QuickMenu button opened at window point [at]. */
fun CanvasViewModel.runQuickAction(a: QuickAction, at: Offset) {
    val current = layers.firstOrNull { it.current }
    a.adjustment?.let {
        startAdjustment(it)
        return
    }
    when (a) {
        QuickAction.NEW_LAYER -> addLayer()
        QuickAction.DUPLICATE_LAYER -> current?.let(this::duplicate)
        QuickAction.DELETE_LAYER -> current?.let(this::delete)
        QuickAction.MERGE_DOWN -> current?.let(this::mergeDown)
        QuickAction.CLEAR_LAYER -> clearCurrentLayer()
        QuickAction.FILL_LAYER -> current?.let(this::fillLayer)
        QuickAction.SELECT_LAYER -> current?.let(this::selectLayerContents)
        QuickAction.ALPHA_LOCK -> current?.let(this::toggleAlphaLock)
        QuickAction.LAYER_VISIBILITY -> current?.let(this::toggleVisible)
        QuickAction.LAYER_OPACITY -> startLayerOpacity()
        QuickAction.SOLO -> current?.let(this::toggleSolo)
        QuickAction.COPY -> copyLayer()
        QuickAction.COPY_CANVAS -> copyCanvas()
        QuickAction.CUT -> cutLayer()
        QuickAction.PASTE -> paste()
        QuickAction.COPY_PASTE_MENU -> copyPasteOpen = true
        QuickAction.FLIP_HORIZONTAL -> flipCanvas(horizontal = true)
        QuickAction.FLIP_VERTICAL -> flipCanvas(horizontal = false)
        QuickAction.UNDO -> undo()
        QuickAction.REDO -> redo()
        QuickAction.PAINT -> select(ToolKind.PAINT)
        QuickAction.SMUDGE -> select(ToolKind.SMUDGE)
        QuickAction.ERASE -> select(ToolKind.ERASE)
        QuickAction.EYEDROPPER -> pickNext = true
        QuickAction.SWAP_COLORS -> previousColor()
        QuickAction.PREVIOUS_BRUSH -> previousBrush()
        QuickAction.SELECTION -> toggleSelecting()
        QuickAction.TRANSFORM -> toggleTransform()
        QuickAction.ADJUSTMENTS -> adjustmentsPressed()
        QuickAction.LIQUIFY -> startAdjustment(Adjustment.LIQUIFY)
        QuickAction.CLONE -> startAdjustment(Adjustment.CLONE)
        QuickAction.COLORS -> toggle(Panel.COLOR)
        QuickAction.LAYERS -> toggle(Panel.LAYERS)
        QuickAction.BRUSHES -> toggle(Panel.BRUSHES)
        QuickAction.INTERFACE -> toggleBars()
        QuickAction.FIT_CANVAS -> settleToFit()
        QuickAction.ACTUAL_SIZE -> {
            session?.settleToActualSize()
            followView()
        }
        QuickAction.ADD_TEXT -> addText()
        QuickAction.QUICKSHAPE -> quickShapeLast()
        QuickAction.QUICKSHAPE_ON -> gestures.set(GesturePurpose.QUICKSHAPE, "DRAW_HOLD", !gestures.isOn(GesturePurpose.QUICKSHAPE, "DRAW_HOLD"))
        QuickAction.LAYER_SELECT -> layerSelectAt(at)
        QuickAction.GUIDE2D -> toggleGuideKind(GuideType.GRID)
        QuickAction.GUIDE_ISOMETRIC -> toggleGuideKind(GuideType.ISOMETRIC)
        QuickAction.GUIDE_PERSPECTIVE -> toggleGuideKind(GuideType.PERSPECTIVE)
        QuickAction.GUIDE_SYMMETRY -> toggleGuideKind(GuideType.SYMMETRY)
        QuickAction.DRAWING_ASSIST -> guide?.let { setGuideAssisted(!it.assisted) }
        QuickAction.ANIMATION_ASSIST -> changeAnimationAssist(!(animation != null && animation?.pages == false))
        QuickAction.PAGE_ASSIST -> changePageAssist(animation?.pages != true)
        QuickAction.NEXT_FRAME -> stepFrame(1)
        QuickAction.PREVIOUS_FRAME -> stepFrame(-1)
        QuickAction.ONION_SKIN -> toggleOnionSkin()
        QuickAction.BRUSH_SCALING -> changeBrushScaling(!brushOnScreen)
        QuickAction.PROJECT_CANVAS -> changeProjectCanvas(!projectCanvas)
        QuickAction.REFERENCE -> changeReferenceOn(!referenceOn)
        QuickAction.COMPANION_MOVE -> toggleCompanionMode(CompanionMode.MOVE)
        QuickAction.COMPANION_ZOOM -> toggleCompanionMode(CompanionMode.ZOOM)
        QuickAction.RECOLOR -> toggleRecolor()
        // nothing, and the adjustments (done above)
        else -> Unit
    }
}

/** The drawing guide on as [kind], or off if it already is that. */
internal fun CanvasViewModel.toggleGuideKind(kind: GuideType) {
    val g = guide
    if (g?.on == true && g.kind == kind) {
        setGuideOn(false)
        return
    }
    if (g?.on != true) setGuideOn(true)
    chooseGuideKind(kind)
}

/** Animation Assist's next or previous frame drawn on ([by] frames along), staying in its frames. */
internal fun CanvasViewModel.stepFrame(by: Int) {
    val at = currentFrame ?: return
    val to = (at + by).coerceIn(0, animationFrames.size - 1)
    if (to != at) tapFrame(to)
}

/** Onion skins off, or back to as many as they were. */
internal fun CanvasViewModel.toggleOnionSkin() {
    changeAnimation {
        if (it.onionFrames > 0u) {
            onionKept = it.onionFrames
            it.copy(onionFrames = 0u)
        } else {
            it.copy(onionFrames = onionKept)
        }
    }
}

/** Layer [l] shown alone, or, already alone, every layer again; a message says which (the core's words). */
fun CanvasViewModel.toggleSolo(l: LayerInfo) {
    var said: String? = null
    // the other rows' boxes show them hidden while it is alone
    layerChange(thumbs = false) { said = it.holdVisibility(l.id) }
    said?.let { showDone(it) }
}

fun CanvasViewModel.startLayerOpacity() {
    val l = layers.firstOrNull { it.current } ?: return
    if (l.kind != LayerType.PAINT && l.kind != LayerType.GROUP) return
    switchModes(ModeButton.LayerOpacity)
    closePanels()
    opacityLayer = l
    opacityValue = l.opacity
    // the shared touches drag it, each drag one undo step
    session?.setTouchLayerOpacity(l.id)
}

/** The shared touches dragged the opacity: shown as it is; let go ([ended]), the layers read again. */
fun CanvasViewModel.layerOpacityTouched(ended: Boolean) {
    val s = session ?: return
    s.touchLayerOpacity()?.let { opacityValue = it }
    if (ended) refresh()
}

fun CanvasViewModel.endLayerOpacity() {
    if (opacityLayer != null) session?.setTouchLayerOpacity(null)
    opacityLayer = null
}

/** Does what a gesture set to the QuickMenu does: opens it at [at], or closes it if open. */
fun CanvasViewModel.toggleQuickMenu(at: Offset) {
    if (quickMenuAt == null) quickMenuShown = quickMenuActive
    quickMenuAt = if (quickMenuAt == null) at else null
}

/** Cut & Paste: what is selected (or the layer) cut out and put back as a new layer; what is on the clipboard stays there. */
fun CanvasViewModel.cutAndPaste() {
    layerCut()?.let { paste(it, private = false) }
}

/** Duplicate: the selection copied to a new layer (what is on the clipboard stays there), or without one the current layer. */
fun CanvasViewModel.duplicateCurrent() {
    if (hasSelection) {
        layerCopied()?.let { paste(it, private = false) }
    } else {
        layers.firstOrNull { it.current }?.let(this::duplicate)
    }
}

fun CanvasViewModel.flipCanvas(horizontal: Boolean) {
    endQuickShape()
    session?.flipCanvas(horizontal)
    refresh()
}

