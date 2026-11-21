package io.github.artcraftandroid.procraft.canvas

import io.github.artcraftandroid.procraft.AppLog
import io.github.artcraftandroid.procraft.ui.CanvasSpace
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.gallery.Artwork
import io.github.artcraftandroid.procraft.gallery.ArtworkStore
import io.github.artcraftandroid.procraft.gallery.CanvasPreset
import io.github.artcraftandroid.procraft.core.BackgroundInfo
import io.github.artcraftandroid.procraft.core.BlendModeInfo
import io.github.artcraftandroid.procraft.ui.GradientPreset
import io.github.artcraftandroid.procraft.core.BrushInfo
import io.github.artcraftandroid.procraft.core.BrushSetInfo
import io.github.artcraftandroid.procraft.core.RecentBrush
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.ColourModel
import io.github.artcraftandroid.procraft.core.ColourPlace
import io.github.artcraftandroid.procraft.core.colourModelKey
import io.github.artcraftandroid.procraft.core.colourModelOfKey
import io.github.artcraftandroid.procraft.core.ModelFile
import io.github.artcraftandroid.procraft.core.SourceKind
import io.github.artcraftandroid.procraft.core.ChromaticKind
import io.github.artcraftandroid.procraft.core.PerspectiveKind
import io.github.artcraftandroid.procraft.core.GlitchKind
import io.github.artcraftandroid.procraft.core.GuideInfo
import io.github.artcraftandroid.procraft.core.KeyShortcut
import io.github.artcraftandroid.procraft.core.ModeButton
import io.github.artcraftandroid.procraft.core.Modes
import io.github.artcraftandroid.procraft.core.modeSwitch
import io.github.artcraftandroid.procraft.core.modeSwitchDeselects
import io.github.artcraftandroid.procraft.core.GuideType
import io.github.artcraftandroid.procraft.core.GuideUnit
import io.github.artcraftandroid.procraft.core.SymmetryType
import io.github.artcraftandroid.procraft.core.HalftoneKind
import io.github.artcraftandroid.procraft.core.NoiseKind
import io.github.artcraftandroid.procraft.core.Adjustment
import io.github.artcraftandroid.procraft.core.GesturePurpose
import io.github.artcraftandroid.procraft.core.QuickAction
import io.github.artcraftandroid.procraft.core.QuickShapeInfo
import io.github.artcraftandroid.procraft.core.QuickShapeKind
import io.github.artcraftandroid.procraft.core.Trigger
import io.github.artcraftandroid.procraft.core.GuideSettings
import io.github.artcraftandroid.procraft.core.Interpolation
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.TextAlignment
import io.github.artcraftandroid.procraft.core.TextInfo
import io.github.artcraftandroid.procraft.core.TextRunInfo
import io.github.artcraftandroid.procraft.core.TextSide
import io.github.artcraftandroid.procraft.core.TextStyleChange
import io.github.artcraftandroid.procraft.core.textIndexFromUtf16
import io.github.artcraftandroid.procraft.core.textIndexToUtf16
import io.github.artcraftandroid.procraft.core.LayerType
import io.github.artcraftandroid.procraft.core.MobileException
import io.github.artcraftandroid.procraft.core.SelectionDisplay
import io.github.artcraftandroid.procraft.core.SelectionJoin
import io.github.artcraftandroid.procraft.core.SwipeAdjustment
import io.github.artcraftandroid.procraft.core.LiquifyBrush
import io.github.artcraftandroid.procraft.core.LiquifyKind
import io.github.artcraftandroid.procraft.core.ToolKind
import io.github.artcraftandroid.procraft.core.TransformAction
import io.github.artcraftandroid.procraft.core.TransformBox
import io.github.artcraftandroid.procraft.core.TransformHandle
import io.github.artcraftandroid.procraft.core.TransformKind
import io.github.artcraftandroid.procraft.gallery.PALETTE_SLOTS
import io.github.artcraftandroid.procraft.gallery.Palette
import io.github.artcraftandroid.procraft.core.blendModes
import io.github.artcraftandroid.procraft.core.brushBlendModes
import io.github.artcraftandroid.procraft.core.grainBlendModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.*
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.*

/**
 * A photo or picture file comes in as a new layer, fitted to the canvas, ready to transform; a
 * [private] one is left out of the gallery's thumbnail.
 */
fun CanvasViewModel.insertPicture(uri: android.net.Uri, private: Boolean = false) {
    if (session == null) return
    closePanels()
    val type = getApplication<Application>().contentResolver.getType(uri)
    if (type?.startsWith("video/") == true) {
        insertFrames(uri, private)
        return
    }
    viewModelScope.launch { insertPictureNow(uri, private, transform = true) }
}

/**
 * Files dragged onto the canvas from another app: a picture or a video comes in as
 * [insertPicture] puts it, a picture alone ready to transform (several each on a new layer of
 * their own, one after another, a video's as its frames); other files' brushes join the
 * library.
 */
fun CanvasViewModel.dropOnCanvas(uris: List<android.net.Uri>) {
    if (session == null || uris.isEmpty()) return
    val resolver = getApplication<Application>().contentResolver
    val types = uris.associateWith { runCatching { resolver.getType(it) }.getOrNull().orEmpty() }
    val video = { uri: android.net.Uri -> types[uri]?.startsWith("video/") == true }
    val (inserted, others) = uris.partition { types[it]?.startsWith("image/") == true || video(it) }
    others.forEach(this::importBrushes)
    if (inserted.isEmpty()) return
    if (transforming) endTransform(true)
    closePanels()
    viewModelScope.launch {
        for (uri in inserted) {
            val came = if (video(uri)) insertFramesNow(uri, private = false) else insertPictureNow(uri, private = false, transform = inserted.size == 1)
            if (!came) break
        }
    }
}

/**
 * The picture at [uri] on a new layer, fitted to the canvas (a moving GIF's frames on the
 * timeline instead), then with [transform] ready to transform; false, and a message, if it
 * couldn't be.
 */
internal suspend fun CanvasViewModel.insertPictureNow(uri: android.net.Uri, private: Boolean, transform: Boolean): Boolean {
    val s = session ?: return false
    val resolver = getApplication<Application>().contentResolver
    val type = withContext(Dispatchers.IO) { runCatching { resolver.getType(uri) }.getOrNull() }
    // a moving GIF: its frames, as a video's (a still one is a picture)
    if (type == "image/gif" || withContext(Dispatchers.IO) { fileName(uri) }?.endsWith(".gif", ignoreCase = true) == true) {
        val bytes = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
        val done = bytes?.let { withContext(Dispatchers.Default) { runCatching { s.insertGifFrames(it, private) }.getOrNull() } }
        if (done != null) {
            if (done.added == 0u) {
                message = if (done.full) noRoom() else "This picture can't be opened."
                return false
            }
            refresh()
            showDone("Frames saved to timeline")
            if (done.full) message = noRoom()
            return !done.full
        }
    }
    val image = withContext(Dispatchers.IO) { decodePicture(uri, io.github.artcraftandroid.procraft.ui.CanvasSpace.p3) }
    if (image == null) {
        message = "This picture can't be opened."
        return false
    }
    // on a model it is placed on the surface, not in the texture
    if (modelOpen && !modelFlat) {
        if (!s.modelPictureBegin(image, true)) {
            message = noRoom()
            return false
        }
        readModelPicture()
        refresh()
        return true
    }
    if (s.insertImage(image, private) == null) {
        message = noRoom()
        return false
    }
    refresh()
    if (transform) toggleTransform()
    return true
}

fun CanvasViewModel.readModelPicture() {
    modelPictureBox = session?.modelPictureBox()
}

fun CanvasViewModel.flipModelPicture(horizontal: Boolean) {
    session?.modelPictureFlip(horizontal)
}

/** Turns the picture being placed by an eighth of a turn. */
fun CanvasViewModel.turnModelPicture() {
    session?.modelPictureMove(0f, 0f, 1f, Math.PI.toFloat() / 4f)
}

fun CanvasViewModel.resetModelPicture() {
    session?.modelPictureReset()
}

/** Ends placing a picture on a model: it stays ([keep]) or goes, and its layer with it. */
fun CanvasViewModel.endModelPicture(keep: Boolean) {
    session?.modelPictureEnd(keep)
    modelPictureBox = null
    refresh()
}

/**
 * A video's frames come in as layers above the current one, each fitted to the canvas, at the
 * animation's frame rate (or its first one's), for as many as there is room; Animation Assist
 * comes on with them in its timeline.
 */
internal fun CanvasViewModel.insertFrames(uri: android.net.Uri, private: Boolean) {
    viewModelScope.launch { insertFramesNow(uri, private) }
}

/** [insertFrames] done here; whether any frame came in. */
internal suspend fun CanvasViewModel.insertFramesNow(uri: android.net.Uri, private: Boolean): Boolean {
    val s = session ?: return false
    val fps = (animation?.fps?.toInt() ?: DEFAULT_FPS).coerceIn(1, 60)
    var added = 0
    var full = false
    withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication<Application>(), uri)
            val ms = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = (ms * fps / 1000L).toInt().coerceIn(1, MAX_VIDEO_FRAMES)
            for (i in 0 until count) {
                val us = i * 1_000_000L / fps
                val frame = retriever.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val image = frameImage(frame)
                frame.recycle()
                if (s.insertImage(image, private) == null) {
                    full = true
                    break
                }
                added++
            }
        } catch (e: Exception) {
            AppLog.e("ProCraft", "a video's frames", e)
        } finally {
            retriever.release()
        }
    }
    if (added == 0) {
        message = if (full) noRoom() else "This video can't be opened."
        return false
    }
    if (animation == null || animation?.pages == true) changeAnimationAssist(true) else refresh()
    showDone("Frames saved to timeline")
    if (full) message = noRoom()
    return true
}

/** A video frame's straight RGBA pixels, at most [MAX_PICTURE] on a side. */
internal fun CanvasViewModel.frameImage(frame: Bitmap): io.github.artcraftandroid.procraft.core.ThumbnailImage {
    val scale = minOf(1f, MAX_PICTURE / maxOf(frame.width, frame.height).toFloat())
    val sized = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt().coerceAtLeast(1), (frame.height * scale).roundToInt().coerceAtLeast(1), true) else frame
    val argb = if (sized.config == Bitmap.Config.ARGB_8888) sized else sized.copy(Bitmap.Config.ARGB_8888, false)
    val buffer = ByteBuffer.allocate(argb.width * argb.height * 4)
    // frames are opaque, so premultiplied and straight are the same
    argb.copyPixelsToBuffer(buffer)
    val image = io.github.artcraftandroid.procraft.core.ThumbnailImage(argb.width.toUInt(), argb.height.toUInt(), buffer.array())
    if (argb !== frame) argb.recycle()
    if (sized !== frame && sized !== argb) sized.recycle()
    return image
}

/** Whether the picture at [uri] is in Display P3. */
internal fun CanvasViewModel.pictureIsP3(uri: android.net.Uri): Boolean = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    getApplication<Application>().contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    bounds.outColorSpace == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
}.getOrDefault(false)

/**
 * A picture's straight RGBA pixels, at most [MAX_PICTURE] pixels on a side, its colours turned
 * into Display P3 ([p3]) or sRGB.
 */
internal fun CanvasViewModel.decodePicture(uri: android.net.Uri, p3: Boolean): io.github.artcraftandroid.procraft.core.ThumbnailImage? = runCatching {
    val resolver = getApplication<Application>().contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPremultiplied = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = android.graphics.ColorSpace.get(if (p3) android.graphics.ColorSpace.Named.DISPLAY_P3 else android.graphics.ColorSpace.Named.SRGB)
    }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
    bitmap.copyPixelsToBuffer(buffer)
    io.github.artcraftandroid.procraft.core.ThumbnailImage(bitmap.width.toUInt(), bitmap.height.toUInt(), buffer.array())
}.getOrNull()

// QuickShape

/** What a stroke snapped to, for the band under the top bar. */
internal fun CanvasViewModel.snappedTo(kind: QuickShapeKind): String = "Snapped to " + when (kind) {
    QuickShapeKind.LINE -> "a line"
    QuickShapeKind.POLYLINE -> "a polyline"
    QuickShapeKind.ARC -> "an arc"
    QuickShapeKind.CURVE -> "a curve"
    QuickShapeKind.ELLIPSE -> "an ellipse"
    QuickShapeKind.CIRCLE -> "a circle"
    QuickShapeKind.TRIANGLE -> "a triangle"
    QuickShapeKind.EQUILATERAL_TRIANGLE -> "an equilateral triangle"
    QuickShapeKind.QUADRILATERAL -> "a quadrilateral"
    QuickShapeKind.RECTANGLE -> "a rectangle"
    QuickShapeKind.SQUARE -> "a square"
}

/** The stroke held still snapped to a shape (the canvas's touches did it). */
fun CanvasViewModel.quickShapeSnapped() {
    val info = session?.quickshapeInfo() ?: return
    quickShape = info
    quickShapeEditing = false
    showDone(snappedTo(info.kind))
}

/** The snapped shape changed (dragged, or a finger down while it is held). */
fun CanvasViewModel.quickShapeChanged() {
    quickShape = session?.quickshapeInfo() ?: quickShape
}

/** Undo or redo stepped through the snapped shape: as it now is, if it is still there. */
internal fun CanvasViewModel.followQuickShape() {
    quickShape = session?.quickshapeInfo()
    if (quickShape == null) quickShapeEditing = false
}

/** The snapped shape was let go of as it is (the canvas's touches did it). */
fun CanvasViewModel.quickShapeEnded() {
    quickShape = null
    quickShapeEditing = false
    refreshFlags()
}

/** The last stroke snaps to the shape it looks like, if nothing has been done since (a QuickShape trigger). */
fun CanvasViewModel.quickShapeLast() {
    val info = session?.quickshapeLast() ?: return
    quickShape = info
    quickShapeEditing = false
    showDone(snappedTo(info.kind))
    refreshFlags()
}

fun CanvasViewModel.chooseQuickShape(kind: QuickShapeKind) {
    quickShape = session?.quickshapeChoose(kind) ?: quickShape
}

/** Keeps the snapped shape as drawn. */
fun CanvasViewModel.endQuickShape() {
    if (quickShape == null) return
    session?.quickshapeDone()
    quickShape = null
    quickShapeEditing = false
    refreshFlags()
}

/** A tool's key: whatever mode is open ends, keeping what it made, as for the tool's button. */
internal fun CanvasViewModel.toolKey(tool: ToolKind) {
    switchModes(ModeButton.Tool)
    select(tool)
}

fun CanvasViewModel.toolPressed(tool: ToolKind) {
    // leaving a mode for a tool keeps what it made, and only picks the tool
    if (!switchModes(ModeButton.Tool)) {
        select(tool)
        return
    }
    if (this.tool == tool) {
        // the library opens on the current brush's set, or on the Recent set if it was shown
        if (shownSet != RECENT_SET) currentBrush?.let { shownSet = it.set.toInt() }
        readRecentBrushes()
        toggle(Panel.BRUSHES)
    } else {
        select(tool)
        if (panel == Panel.BRUSHES) panel = Panel.NONE
    }
}

/**
 * A tool button held: that tool takes the brush the current one has (erasing with the painting
 * brush, say), and becomes the tool.
 */
fun CanvasViewModel.toolHeld(target: ToolKind) {
    val s = session ?: return
    val brush = currentBrush ?: return
    if (target == tool || !switchModes(ModeButton.ToolHeld)) return
    select(target)
    if (s.selectBrush(brush.set, brush.index)) {
        currentBrush = s.currentBrush()
        readSliders()
        brushSets = s.brushLibrary()
        keepChoices(s)
    }
    if (panel == Panel.BRUSHES) panel = Panel.NONE
    showDone(
        when (target) {
            ToolKind.PAINT -> "Painting"
            ToolKind.SMUDGE -> "Smudging"
            ToolKind.ERASE -> "Erasing"
        } + " with the current brush",
    )
}

fun CanvasViewModel.showSet(index: Int) {
    shownSet = index
    pickedBrushes.clear()
    if (index == RECENT_SET) {
        readRecentBrushes()
        return
    }
    // the set's last chosen brush becomes the current one, as choosing it again would: the
    // brush it shows highlighted is the one that paints
    session?.brushSetChoice(index.toUInt())?.let { chooseBrush(it) }
}

/** Frames recorded, for the replay. */
fun CanvasViewModel.timeLapseFrames() = timeLapse?.count ?: 0

fun CanvasViewModel.timeLapseFrame(i: Int): Bitmap? = timeLapse?.frame(i)

/** Whether frame [i] is kept: one that can't be read but is, this device can't decode. */
fun CanvasViewModel.timeLapseFrameKept(i: Int): Boolean = timeLapse?.kept(i) ?: false

fun CanvasViewModel.changeTimeLapseRecording(on: Boolean) {
    timeLapse?.recording = on
    timeLapseRecording = on
    // the switch goes off at once; then what was recorded may be deleted
    if (!on && timeLapseFrames() > 0) purgeAsking = true
}

/** The answer to [purgeAsking]: what was recorded deleted, or kept. */
fun CanvasViewModel.answerPurge(purge: Boolean) {
    purgeAsking = false
    if (purge) timeLapse?.purge()
    timeLapseRecorded = timeLapseFrames() > 0
}

/** Records the canvas as a time-lapse frame, one at a time while changes come. */
internal fun CanvasViewModel.recordFrame() {
    val t = timeLapse ?: return
    if (!t.recording) return
    if (frameJob?.isActive == true) {
        frameStale = true
        return
    }
    val s = session ?: return
    frameJob = viewModelScope.launch {
        do {
            frameStale = false
            // read back at the size frames are kept, private layers left out, and kept by the
            // core without the picture coming here: a frame after each stroke (one the core
            // fails to read is left out, and goes in the log)
            withContext(Dispatchers.Default) {
                try {
                    t.recordFrom(s)
                } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
                    AppLog.e("timelapse", "a time-lapse frame failed in the core", fault)
                }
            }
            // one that couldn't be kept (the storage full) is told once
            t.takeProblem()?.let { message = it }
        } while (frameStale)
        timeLapseRecorded = t.count > 0
    }
}

/** The export card's Cancel: the export stops, and nothing is shared. */
fun CanvasViewModel.cancelExport() {
    exportCancelled = true
    exportStops?.invoke()
}

/**
 * The time-lapse as a video in the cache, to share: all of it, or [seconds] long; its card
 * shows how far it has come and a check once made. Null if it was cancelled or couldn't be
 * made (a notice says so).
 */
suspend fun CanvasViewModel.exportTimeLapse(seconds: Int?): File? {
    val t = timeLapse ?: return null
    val s = session ?: return null
    if (timeLapseExporting || exportShown != null) return null
    timeLapseExporting = true
    exportCancelled = false
    exportShown = ExportShown(0f)
    try {
        val made = withContext(Dispatchers.IO) {
            val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            val base = io.github.artcraftandroid.procraft.core.exportFileName(s.name(), false)
            val out = File(dir, "$base.mp4")
            out.delete()
            var shownAt = -1
            val ok = runCatching {
                t.export(out, seconds) { done ->
                    // a step a hundredth, so the card isn't redrawn for every frame
                    val step = (done * 100).toInt()
                    if (step != shownAt) {
                        shownAt = step
                        exportShown = ExportShown(done)
                    }
                    !exportCancelled
                }
            }.onFailure { AppLog.e(TAG, "exporting the time-lapse", it) }.getOrDefault(false)
            out.takeIf { ok }
        }
        if (exportCancelled) return null
        if (made == null) {
            // which of the three: they are different faults, in a report too
            val why = withContext(Dispatchers.IO) {
                when {
                    t.frame(0)?.also { it.recycle() } != null -> "The time-lapse video couldn't be made."
                    t.kept(0) -> "The time-lapse can't be read on this device."
                    else -> TIME_LAPSE_MISSING
                }
            }
            showNotice(why, warning = true)
            return null
        }
        // frames left in their place, not left out silently
        if (t.unread > 0) showNotice("${t.unread} frames of the time-lapse can't be read on this device: the one before each shows in its place.", warning = true)
        // full, then the check drawn in it before the card goes
        exportShown = ExportShown(1f, done = true)
        kotlinx.coroutines.delay(EXPORT_DONE_MS)
        return made
    } finally {
        exportShown = null
        timeLapseExporting = false
    }
}

/** The crop as the core has it now. */
internal fun CanvasViewModel.readCrop() {
    val c = session?.cropShown()
    cropRect = c?.let { androidx.compose.ui.geometry.Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
    cropReadout = c?.readout
    if (c == null) return
    cropAngle = c.angle
    cropResample = c.resample
    cropSize = c.sizeWidth.toInt() to c.sizeHeight.toInt()
    cropAspectLocked = c.aspectLocked
    cropSnapping = c.snapping
    cropDpi = c.dpi
}

fun CanvasViewModel.changeCropDpi(dpi: Float) {
    session?.cropSetDpi(dpi)
    readCrop()
}

/** The canvas's size, pixels. */
fun CanvasViewModel.canvasSize(): Pair<Int, Int> = session?.info()?.let { it.width.toInt() to it.height.toInt() } ?: (0 to 0)

fun CanvasViewModel.beginCrop() {
    closePanels()
    val (w, _) = canvasSize()
    if (w == 0) return
    // the canvas fitted to the screen and shrunk, in the middle, so the frame's handles are clear
    session?.cropBegin()
    refreshView()
    followView()
    readCrop()
    cropSettingsOpen = false
}

fun CanvasViewModel.resetCrop() = beginCrop()

/** A press on the frame at window point [at]: near a corner or a side's middle it takes that, else the whole frame. */
fun CanvasViewModel.cropPress(at: Offset) {
    session?.cropPress(at.x, at.y)
    readCrop()
}

/** The drag on the frame reached window point [at]. */
fun CanvasViewModel.cropDrag(at: Offset) {
    session?.cropDrag(at.x, at.y)
    readCrop()
}

fun CanvasViewModel.cropRelease() {
    session?.cropRelease()
    readCrop()
}

fun CanvasViewModel.changeCropAngle(radians: Float) {
    session?.cropSetAngle(radians)
    readCrop()
}

fun CanvasViewModel.changeCropResample(on: Boolean) {
    session?.cropSetResample(on)
    readCrop()
}

fun CanvasViewModel.changeCropAspectLocked(on: Boolean) {
    session?.cropSetAspectLocked(on)
    readCrop()
}

fun CanvasViewModel.changeCropSnapping(on: Boolean) {
    session?.cropSetSnapping(on)
    readCrop()
}

/** A width or height typed in: resampling, it is the new size; otherwise the kept rectangle's. */
fun CanvasViewModel.changeCropSize(width: Int?, height: Int?) {
    session?.cropSetSize(width?.coerceAtLeast(1)?.toUInt(), height?.coerceAtLeast(1)?.toUInt())
    readCrop()
}

fun CanvasViewModel.endCrop(keep: Boolean) {
    if (cropRect == null) return
    cropRect = null
    cropSettingsOpen = false
    val s = session ?: return
    // either way the canvas is fitted to the screen again: cancelled, growing back into place
    val changed = s.cropEnd(keep)
    // a crop the GPU couldn't make: the canvas is as it was, and says so
    s.takeFailure()?.let { showNotice(it, warning = true) }
    if (!keep) {
        refreshView()
        followView()
        return
    }
    if (changed) {
        thumbnails.clear()
        thumbnailVersions.clear()
    }
    refresh()
    refreshView()
    if (changed && layersShown) refreshThumbnails()
}

fun CanvasViewModel.changeReferenceOn(on: Boolean) {
    referenceWindows.firstOrNull()?.changeOn(on)
}

/**
 * The windows as the artwork [s] left them. Each artwork has its own, kept by the core with the
 * artwork: they stay with it through a close or the process ending.
 */
internal fun CanvasViewModel.restoreReference(s: CanvasSession) {
    forgetReference()
    referenceWindows.firstOrNull()?.restore(s)
    for (i in 1 until s.referenceWindowCount().toInt()) referenceWindows.add(ReferenceWindow(i).also { it.restore(s) })
}

/** No artwork is open: the windows belong to the canvas, so nothing of them stays for the gallery or the next artwork. */
internal fun CanvasViewModel.forgetReference() {
    referenceWindows.forEach { it.forget() }
    referenceWindows.removeAll { it.index > 0 }
}

/** Another window opened besides those open, a little down and right of [from], showing the canvas. */
fun CanvasViewModel.addReferenceWindow(from: ReferenceWindow) {
    val s = session ?: return
    if (referenceWindows.size >= referenceWindowsAllowed) return
    val step = REFERENCE_STEP_DP
    val placed = from.offset != Offset.Unspecified
    val state = from.state().copy(
        shown = true,
        shows = io.github.artcraftandroid.procraft.core.ReferenceShows.CANVAS,
        placed = placed,
        x = if (placed) from.offset.x / density + step else 0f,
        y = if (placed) from.offset.y / density + step else 0f,
        canvasView = io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f),
        imageView = io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f),
    )
    val index = s.addReferenceWindow(state)?.toInt() ?: return
    referenceWindows.add(ReferenceWindow(index).also { it.restore(s) })
}

/** [w] closed: the artwork's own hides; one opened besides it goes, with its picture. */
fun CanvasViewModel.closeReferenceWindow(w: ReferenceWindow) {
    if (w.index == 0) {
        w.changeOn(false)
        return
    }
    val s = session ?: return
    s.removeReferenceWindow(w.index.toUInt())
    // those after it each take the index before theirs: read again as the artwork has them
    referenceWindows.filter { it.index > 0 }.forEach { it.forget() }
    referenceWindows.removeAll { it.index > 0 }
    for (i in 1 until s.referenceWindowCount().toInt()) referenceWindows.add(ReferenceWindow(i).also { it.restore(s) })
}

/**
 * The eyedropper over a window's [picture] at its point ([px], [py]) (a window pixel spanning
 * [per] of its pixels), the finger (or the [pen]) at window point [at]: the colour there and what is around it.
 */
fun CanvasViewModel.pickReference(picture: ImageBitmap, px: Float, py: Float, per: Float, at: Offset, pen: Boolean = false) {
    val bitmap = picture.asAndroidBitmap()
    val x = px.toInt().coerceIn(0, bitmap.width - 1)
    val y = py.toInt().coerceIn(0, bitmap.height - 1)
    val rgb = bitmap.getPixel(x, y) and 0xFFFFFF
    // as much of the picture as the loupe's window shows, around the point
    val n = (PICK_WINDOW_DP * density * per).roundToInt().coerceIn(1, minOf(bitmap.width, bitmap.height))
    val around = Bitmap.createBitmap(bitmap, (x - n / 2).coerceIn(0, bitmap.width - n), (y - n / 2).coerceIn(0, bitmap.height - n), n, n)
    picking = PickState(at.x, at.y, around.asImageBitmap(), rgb, fromReference = true, lift = loupeLift(pen))
}

/** Reads the canvas again for the windows that show it. */
fun CanvasViewModel.refreshReference() {
    referenceWindows.forEach { it.refresh() }
}

