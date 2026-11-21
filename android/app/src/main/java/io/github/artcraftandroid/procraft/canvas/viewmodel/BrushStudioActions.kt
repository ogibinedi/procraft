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

// the brush studio

fun CanvasViewModel.studio(key: String): Float = studioValues[key] ?: 0f

/** The rows the plugins that are on add to the brush studio, kept with each brush. */
fun CanvasViewModel.pluginBrushControls(): List<io.github.artcraftandroid.procraft.core.PluginBrushControl> =
    pluginList.filter { it.on && it.problem == null }.flatMap { it.brushControls }

/** Shows a dual brush's first or [second], each on the page it was left on. */
fun CanvasViewModel.showStudioPart(second: Boolean) {
    val s = session ?: return
    if (second == studioSecond) return
    studioSecond = second
    s.studioShowSecond(second)
    otherPartTab = studioTab.also { studioTab = otherPartTab }
    readStudio()
}

fun CanvasViewModel.changeStudioCombineMode(mode: Int) {
    val s = session ?: return
    s.setStudioCombineMode(mode.toUInt())
    studioCombineMode = mode
    refreshStudioPreview()
    syncPad()
}

/** Draws the parts' sample strokes again, a moment after the last change. */
internal fun CanvasViewModel.refreshStudioParts() {
    val s = session ?: return
    if (!studioDual) return
    studioPartsJob?.cancel()
    studioPartsJob = viewModelScope.launch {
        kotlinx.coroutines.delay(STUDIO_PREVIEW_DELAY_MS)
        val parts = withContext(Dispatchers.Default) {
            listOf(false, true).map { second ->
                previewOf { s.studioPartPreview(second, 680u, 222u) }?.let { t ->
                    val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
                    bitmap.asImageBitmap()
                }
            }
        }
        if (studioOpen) studioParts = parts
    }
}

/** Opens the studio on the current tool's brush. */
fun CanvasViewModel.openStudio() {
    val s = session ?: return
    // a brush a plugin's brush engine draws opens in that plugin's own studio
    s.brushStudioAction()?.let {
        runPluginAction(it)
        return
    }
    // the brush library stays open under it, so Cancel and Done go back to it
    if (panel != Panel.BRUSHES) closePanels()
    studioName = s.studioBegin()
    studioDual = s.studioIsDual()
    studioSecond = false
    otherPartTab = 0
    studioCombineMode = s.studioCombineMode().toInt()
    // each visit's pad paints in white, black with the light interface
    padColor = if (lightInterface) PAD_BLACK else PAD_WHITE
    readStudio()
    studioOpen = true
}

/** Names the studio's brush as it is typed: Done keeps the name, Cancel puts the old one back. */
fun CanvasViewModel.renameStudio(name: String) {
    session?.setStudioName(name)?.let { studioName = it }
}

internal fun CanvasViewModel.readStudio() {
    val s = session ?: return
    val keys = io.github.artcraftandroid.procraft.ui.STUDIO_KEYS + pluginBrushControls().map { it.key }
    s.studioSettings(keys).forEachIndexed { i, v -> studioValues[keys[i]] = v }
    studioCurve = s.studioCurve().chunked(2).filter { it.size == 2 }.map { Offset(it[0], it[1]) }
    readStudioSources()
    readStudioAbout()
    refreshStudioPreview()
}

internal fun CanvasViewModel.readStudioAbout() {
    val a = session?.studioAbout() ?: return
    val picture = { png: ByteArray? -> png?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    studioAbout = BrushAbout(a.author, picture(a.picture), picture(a.signature), a.resetMade)
}

/** Sets the studio's brush aside as it is now, for resetting it to go back to. */
fun CanvasViewModel.newResetPoint() {
    session?.studioNewResetPoint()
    readStudioAbout()
}

fun CanvasViewModel.setStudio(key: String, value: Float) {
    studioValues[key] = value
    session?.setStudioSetting(key, value)
    // the spacing sets how big the brush may be, so where the size controls sit
    if (key == "spacing") session?.let { s ->
        val keys = listOf("max_size", "min_size", "most_size")
        s.studioSettings(keys).forEachIndexed { i, v -> studioValues[keys[i]] = v }
    }
    refreshStudioPreview()
    syncPad()
}

/** Studio control [key]'s own pressure curve (none while it has none). */
fun CanvasViewModel.studioSliderCurve(key: String): List<Offset>? =
    session?.studioSliderCurve(key)?.takeIf { it.size >= 4 }?.chunked(2) { Offset(it[0], it[1]) }

/** Gives studio control [key] its own pressure curve through [points], or takes it away (none). */
fun CanvasViewModel.setStudioSliderCurve(key: String, points: List<Offset>?) {
    session?.setStudioSliderCurve(key, points?.flatMap { listOf(it.x, it.y) } ?: emptyList())
    refreshStudioPreview()
    syncPad()
}

internal fun CanvasViewModel.readStudioSources() {
    val s = session ?: return
    val images = SourceKind.entries.mapNotNull { k -> sourceBitmap(s.studioSourcePicture(k), shape = k == SourceKind.SHAPE)?.let { k to it } }
    studioSources = images.associate { (k, b) -> k to small(b, SOURCE_ROW_PX) }
    studioSourcesBig = images.associate { (k, b) -> k to small(b, SOURCE_BOX_PX) }
}

/** [image] shrunk by halves to about [side] pixels across, each step averaging, so fine detail evens out. */
internal fun CanvasViewModel.small(image: ImageBitmap, side: Int): ImageBitmap {
    var b = image.asAndroidBitmap()
    while (b.width >= side * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    return b.asImageBitmap()
}

/** A square greyscale image (white = paint) as a bitmap, white on black; none if it is empty. */
internal fun CanvasViewModel.grayBitmap(pixels: ByteArray): ImageBitmap? {
    val n = kotlin.math.sqrt(pixels.size.toDouble()).toInt()
    if (n <= 0 || n * n != pixels.size) return null
    val argb = IntArray(n * n) { i -> (pixels[i].toInt() and 0xFF).let { v -> (0xFF shl 24) or (v shl 16) or (v shl 8) or v } }
    return Bitmap.createBitmap(argb, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The image the studio brush's [kind] of source uses now: square greyscale, white = paint; empty for the round tip, no grain or the amount all over. */
fun CanvasViewModel.studioSourcePixels(kind: SourceKind): ByteArray = session?.studioSourcePicture(kind) ?: ByteArray(0)

/** A source image at the size a brush always holds, quick enough to make repeat as a slider moves. */
fun CanvasViewModel.smallSource(pixels: ByteArray): ByteArray = io.github.artcraftandroid.procraft.core.smallSource(pixels)

/** [pixels] made to repeat without seams (the grain editor's Auto repeat). */
fun CanvasViewModel.repeatSource(pixels: ByteArray, scale: Float, rotation: Float, overlap: Float, hardness: Float, mirror: Boolean, pyramid: Boolean): ByteArray =
    io.github.artcraftandroid.procraft.core.repeatableSource(pixels, scale, rotation, overlap, hardness, mirror, pyramid)

/** A square greyscale image as the editors show it: a [shape] white over what is behind, a grain as its greys. */
fun CanvasViewModel.sourceBitmap(pixels: ByteArray, shape: Boolean): ImageBitmap? {
    val n = kotlin.math.sqrt(pixels.size.toDouble()).toInt()
    if (n <= 0 || n * n != pixels.size) return null
    val argb = IntArray(n * n) { i ->
        val v = pixels[i].toInt() and 0xFF
        if (shape) (v shl 24) or 0xFFFFFF else (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(argb, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Library image [index]'s place among the shipped images: none for the grains' Blank, which isn't one. */
fun CanvasViewModel.shippedOf(grain: Boolean, index: Int): Int? = if (grain) (index - 1).takeIf { it >= 0 } else index

/** Shipped image [index] of the source library. */
fun CanvasViewModel.librarySource(grain: Boolean, index: Int): ByteArray =
    io.github.artcraftandroid.procraft.core.sourceLibrary(grain).getOrNull(index)?.pixels ?: ByteArray(0)

/** [bitmap] fitted into the square on black, by its lightness (the shared core's). */
internal fun CanvasViewModel.sourceFrom(bitmap: Bitmap): ByteArray {
    val (w, h) = bitmap.width to bitmap.height
    // straight RGBA, as the core takes it
    val argb = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    val rgba = ByteArray(w * h * 4)
    for ((i, c) in argb.withIndex()) {
        rgba[i * 4] = (c shr 16).toByte()
        rgba[i * 4 + 1] = (c shr 8).toByte()
        rgba[i * 4 + 2] = c.toByte()
        rgba[i * 4 + 3] = (c ushr 24).toByte()
    }
    return io.github.artcraftandroid.procraft.core.sourceFromPicture(rgba, w.toUInt(), h.toUInt())
}

/** A picture from elsewhere as a source image; none if it can't be read. */
suspend fun CanvasViewModel.decodeSource(uri: android.net.Uri): ByteArray? = sourceOrSay("importing a picture into a source editor") { decodedSource(uri) }

/**
 * What [make] gives, or none if it fails in any way (running out of memory too): the editor
 * says so and stays open rather than the app closing.
 */
internal suspend fun CanvasViewModel.sourceOrSay(doing: String, make: suspend () -> ByteArray?): ByteArray? = try {
    make()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    AppLog.e(TAG, doing, e)
    message = "This picture couldn't be used."
    null
}

internal suspend fun CanvasViewModel.decodedSource(uri: android.net.Uri): ByteArray? {
    val bitmap = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
    }
    if (bitmap == null) {
        message = "This picture can't be opened."
        return null
    }
    return withContext(Dispatchers.Default) { sourceFrom(bitmap) }
}

/** A picture on the clipboard as a source image; none if there isn't one. */
suspend fun CanvasViewModel.pastedSource(): ByteArray? = sourceOrSay("pasting into a source editor") { clipboardSource() }

internal suspend fun CanvasViewModel.clipboardSource(): ByteArray? {
    val clip = runCatching { systemClipboard?.primaryClip }.getOrNull()?.takeIf { it.itemCount > 0 }
    val uri = clip?.getItemAt(0)?.uri
    // what was cut or copied here, unless another app's picture went on the clipboard since
    val own = clipboard
    val others = uri != null && pastesSystemPicture(uri == clipUri, clip?.description?.timestamp ?: 0L, own != null, clipboardAt, clipPutAt)
    if (own != null && !others) {
        val source = withContext(Dispatchers.Default) { io.github.artcraftandroid.procraft.core.sourceFromCopy(own) }
        if (source.isNotEmpty()) return source
    }
    if (uri == null) {
        message = "There's no picture to paste."
        return null
    }
    return decodedSource(uri)
}

/** The editor's tick: the studio brush's [kind] of source becomes [pixels], or shipped image [shipped]. */
fun CanvasViewModel.keepStudioSource(kind: SourceKind, pixels: ByteArray, shipped: Int?) {
    val s = session ?: return
    val changed = when {
        shipped != null -> s.setStudioShippedSource(kind, shipped.toUInt())
        pixels.isNotEmpty() -> s.setStudioSource(kind, pixels)
        else -> false
    }
    sourceEditing = null
    if (changed) sourceChanged()
}

internal fun CanvasViewModel.sourceChanged() {
    readStudioSources()
    refreshStudioPreview()
    syncPad()
}

/** The drawing pad's canvas is made: dark, painting with the brush being edited, keeping its strokes. */
fun CanvasViewModel.startPad(p: CanvasSession) {
    pad = p
    val back = if (lightInterface) PAD_BACK_LIGHT else PAD_BACK
    p.setBackgroundColor(back, back, back, true)
    p.keepStrokes()
    syncPad()
}

/**
 * The pad has drawn its first frame: it shows the brush's sample scribble, flat or on the sphere
 * (drawn again here: a sphere asked for before now had nothing to draw on).
 */
fun CanvasViewModel.padReady(p: CanvasSession) {
    if (pad !== p) return
    p.showSphere(padSphere)
    p.drawSampleScribble()
}

fun CanvasViewModel.showPadSphere(shown: Boolean) {
    padSphere = shown
    pad?.showSphere(shown)
}

/** The pad paints with the brush as it is now, and what is on it is drawn again that way. */
internal fun CanvasViewModel.syncPad() {
    val p = pad ?: return
    val s = session ?: return
    val c = PAD_COLORS.getOrElse(padColor) { 0xFFFFFF }
    // drawn again on the core's own thread: a slider dragged doesn't wait for it
    p.syncPad(s, ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
}

fun CanvasViewModel.choosePadColor(i: Int) {
    padColor = i
    syncPad()
}

fun CanvasViewModel.clearPad() {
    pad?.forgetStrokes()
    pad?.clearLayer()
    padMenuOpen = false
}

fun CanvasViewModel.changeStudioCurve(points: List<Offset>) {
    studioCurve = points
    session?.setStudioCurve(points.flatMap { listOf(it.x, it.y) })
    refreshStudioPreview()
    syncPad()
}

/** The studio's brush set at random, as a plugin says. */
fun CanvasViewModel.randomiseStudio() {
    if (session?.studioRandomise() != true) return
    readStudio()
    syncPad()
}

/** Every control back to how the brush ships. */
fun CanvasViewModel.resetStudio() {
    session?.studioReset()
    readStudio()
    syncPad()
    padMenuOpen = false
}

/** The dual brush the studio has open parted into its two brushes: the studio closes keeping its edits, and the second follows the first in the set. */
fun CanvasViewModel.uncombineStudio() {
    val s = session ?: return
    val brush = currentBrush ?: return
    closeStudio(keep = true)
    if (s.uncombineBrush(brush.set, brush.index)) libraryChanged()
}

/** Closes the studio: [keep] keeps the edits, otherwise the brush goes back to how it opened. */
fun CanvasViewModel.closeStudio(keep: Boolean) {
    session?.studioEnd(keep)
    // a new brush cancelled is thrown away
    if (!keep && studioNew) currentBrush?.let { session?.deleteBrush(it.set, it.index) }
    studioNew = false
    studioOpen = false
    padShown = false
    padMenuOpen = false
    // the pad's canvas goes with the studio
    val p = pad
    pad = null
    p?.detachWindow()
    p?.destroy()
    studioPreview = null
    libraryChanged()
    readSliders()
    // brushes read while the studio was open join the library now
    addBrushesRead()
}

/**
 * Draws the brush's sample stroke again, a moment after the last change. The core draws only
 * the newest one asked for: those still waiting when a slider moves on come back with none
 * (the stroke shown stays until the newest is drawn).
 */
internal fun CanvasViewModel.refreshStudioPreview() {
    val s = session ?: return
    refreshStudioParts()
    studioPreviewJob?.cancel()
    studioPreviewJob = viewModelScope.launch {
        kotlinx.coroutines.delay(STUDIO_PREVIEW_DELAY_MS)
        val image = withContext(Dispatchers.Default) {
            val t = previewOf { s.studioPreview(1060u, 324u) } ?: return@withContext null
            val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
            bitmap.asImageBitmap()
        }
        if (image != null && studioOpen) studioPreview = image
    }
}

/**
 * A sample stroke from the core, or none if drawing it failed there (said in the log): a brush
 * shown without its preview, never the app closing for it.
 */
internal fun CanvasViewModel.previewOf(draw: () -> io.github.artcraftandroid.procraft.core.ThumbnailImage?) =
    try {
        draw()
    } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
        AppLog.e("ProCraft", "a brush's sample stroke failed in the core", fault)
        null
    }

/**
 * Renders the brush's sample stroke once, off the main thread. Called from the brush's row: a row
 * scrolled away before its turn comes draws none, so a fling through a long library doesn't
 * leave the rows it stops on waiting behind every row it passed.
 */
suspend fun CanvasViewModel.loadPreview(b: BrushInfo) {
    val key = "${b.set}-${b.index}"
    if (previews.containsKey(key)) return
    val s = session ?: return
    val round = previewRound
    previewTurn.withLock {
        if (previews.containsKey(key) || round != previewRound) return
        // once begun it is drawn and kept, even if the row has gone meanwhile
        withContext(kotlinx.coroutines.NonCancellable) {
            val made = withContext(Dispatchers.Default) {
                // half the sample stroke's own canvas: the cell shows it across its width
                val t = previewOf { s.brushPreview(b.set, b.index, 530u, 162u) } ?: return@withContext null
                // whether the stroke runs under the name: the name's box (19.5 in, 14 to 26 down, about
                // 7.7 a letter wide, in a cell the picture fills 80 high and 261.7 wide) covered more
                // than 2/255 on average
                val k = 162f / 80f
                val x0 = ((19.5f + 6.85f) * k).toInt()
                val x1 = ((19.5f + 6.85f + 7.7f * b.name.length) * k).toInt().coerceAtMost(530)
                val y0 = (14f * k).toInt()
                val y1 = (26f * k).toInt()
                var cover = 0L
                for (y in y0 until y1) for (x in x0 until x1) cover += t.rgba[(y * 530 + x) * 4 + 3].toInt() and 0xFF
                val under = x1 > x0 && cover > 2L * (x1 - x0) * (y1 - y0)
                val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
                bitmap.asImageBitmap() to under
            }
            if (made != null && round == previewRound) {
                shadedNames[key] = made.second
                previews[key] = made.first
            }
        }
    }
}

fun CanvasViewModel.changeSize(value: Float) {
    size = value
    session?.setBrushSize(value)
}

/** The notch [value] is on, of the size or opacity slider's ([size]), if any. */
fun CanvasViewModel.notchAt(size: Boolean, value: Float): Float? = session?.sliderNotchAt(size, value)

/** Whether the slider's panel offers to save its value: not when it is on a notch or the slider is full. */
fun CanvasViewModel.canSaveNotch(size: Boolean): Boolean = session?.canSaveSliderNotch(size) == true

/** The slider panel's button: saves the value as a notch, or takes away the notch it is on. */
fun CanvasViewModel.toggleNotch(size: Boolean) {
    val s = session ?: return
    s.toggleSliderNotch(size)
    readNotches()
    keepBrushLibrary(s)
}

/**
 * Keeps the library as it is now, with the brushes in use, in its folder, off the main thread.
 * Each write takes the library as it is when it runs, so one waiting already covers this
 * change: a run of them (sets dragged about) costs two writes of a large library, not one each,
 * and every write holds the core for as long as it reads the library.
 */
internal fun CanvasViewModel.keepBrushLibrary(s: CanvasSession) {
    if (libraryKeepWaiting.getAndSet(s) === s) return
    viewModelScope.launch(Dispatchers.IO) {
        // begun, it ends even if the canvas goes meanwhile (the app closed with it open): a
        // write waiting its turn isn't dropped, and brushes just brought in are kept
        val kept = withContext(NonCancellable) {
            brushLibraryWrites.withLock {
                libraryKeepWaiting.compareAndSet(s, null)
                runCatching { s.keepBrushes(brushesDir.path) }.getOrDefault(false)
            }
        }
        libraryKept(kept)
    }
}

internal fun CanvasViewModel.readNotches() {
    val s = session ?: return
    sizeNotches = s.sliderNotches(true)
    opacityNotches = s.sliderNotches(false)
}

/** Whether a touch at [at] (on the window) leaves a slider's panel up: on it or the side bar. */
fun CanvasViewModel.keepsSliderPanel(at: androidx.compose.ui.geometry.Offset): Boolean =
    sliderPanelKeeps(at, sideBarOnWindow, sliderPanelOnWindow)

/** The brush's diameter on the screen now, window pixels. */
fun CanvasViewModel.brushScreenDiameter(): Float = session?.info()?.let { it.brushDiameter * it.zoom } ?: 0f

/** The brush's own shape as loops across its diameter (none: the round tip's circle). */
fun CanvasViewModel.brushOutline(): List<List<Float>> = session?.brushOutline() ?: emptyList()

/** The brush's stamp as it paints, [side] pixels square, as a coverage mask (null without a canvas). */
fun CanvasViewModel.brushStamp(side: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val s = session ?: return null
    val n = side.coerceIn(1, 1024)
    val alpha = s.brushStamp(n.toUInt())
    if (alpha.size < n * n) return null
    // white with the coverage as its alpha (the panel tints it)
    val colors = IntArray(n * n) { i -> (alpha[i].toInt() and 0xFF shl 24) or 0xFFFFFF }
    val bitmap = android.graphics.Bitmap.createBitmap(colors, n, n, android.graphics.Bitmap.Config.ARGB_8888)
    return bitmap.asImageBitmap()
}

fun CanvasViewModel.changeOpacity(value: Float) {
    opacity = value
    session?.setBrushOpacity(value)
}

