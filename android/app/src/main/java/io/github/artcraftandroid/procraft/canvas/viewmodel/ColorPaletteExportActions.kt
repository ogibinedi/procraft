package io.github.artcraftandroid.procraft.canvas

import io.github.artcraftandroid.procraft.AppLog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.gallery.Artwork
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.MobileException
import io.github.artcraftandroid.procraft.core.ToolKind
import io.github.artcraftandroid.procraft.gallery.Palette
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.*
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.ANIMATION_FPS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.COLOR_NAME_GAP_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.EXPORT_DONE_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.EXPORT_POLL_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.MAX_VIDEO_SIDE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.TURNTABLE_FPS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.TURNTABLE_SIZES
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.WEB_READY_SIDE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.hsvToRgb

fun CanvasViewModel.setColor(h: Float, s: Float, v: Float) {
    hue = h.coerceIn(0f, 1f)
    saturation = s.coerceIn(0f, 1f)
    brightness = v.coerceIn(0f, 1f)
    applyColor()
}

/** The colour as 0xRRGGBB. */
fun CanvasViewModel.colorRgb(): Int = hsvToRgb(hue, saturation, brightness)

fun CanvasViewModel.setRgb(rgb: Int) {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, hsv)
    // a grey keeps the hue it had, so the hue slider does not jump
    setColor(if (hsv[1] > 0f) hsv[0] / 360f else hue, hsv[1], hsv[2])
}

/**
 * Moves the eyedropper to window point ([x], [y]) and reads what is shown there. The reading
 * happens off the main thread; points that arrive meanwhile replace each other.
 */
fun CanvasViewModel.pickAt(x: Float, y: Float, side: Int) {
    pendingPick = x to y
    // its loupe on a pen's tip, or clear above a finger, as the touches say it was opened
    if (picking == null) picking = PickState(x, y, null, null, lift = session?.pickLoupeLift() ?: LOUPE_LIFT_DP)
    if (pickJob?.isActive == true) return
    pickJob = viewModelScope.launch {
        while (true) {
            val (px, py) = pendingPick ?: break
            pendingPick = null
            val s = session ?: break
            val seen = withContext(Dispatchers.Default) {
                // a fault in the core reading it (a model's picture, say) picks nothing, and goes in the log
                val read = try {
                    s.pick(px, py, side.toUInt())
                } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
                    AppLog.e("pick", "reading the colour under the eyedropper failed in the core", fault)
                    null
                }
                read?.takeIf { it.pixels.size >= it.side.toInt() * it.side.toInt() * 4 }?.let { p ->
                    val n = p.side.toInt()
                    val bytes = p.pixels
                    val argb = IntArray(n * n) { i ->
                        (0xFF shl 24) or ((bytes[i * 4].toInt() and 0xFF) shl 16) or ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 4 + 2].toInt() and 0xFF)
                    }
                    Bitmap.createBitmap(argb, n, n, Bitmap.Config.ARGB_8888).asImageBitmap() to p.rgb?.toInt()
                }
            }
            val now = picking ?: break
            picking = PickState(px, py, seen?.first ?: now.image, seen?.second ?: now.rgb, lift = now.lift)
        }
    }
}

/** The eyedropper lets go: its colour becomes the current one. */
fun CanvasViewModel.finishPick() {
    pickNext = false
    // a quick tap ends before the colour under it has been read: it is taken once it has
    val job = pickJob
    if (job?.isActive == true) {
        viewModelScope.launch {
            job.join()
            endPick()
        }
    } else {
        endPick()
    }
}

internal fun CanvasViewModel.endPick() {
    pendingPick = null
    picking?.rgb?.let {
        val before = colorRgb()
        setRgb(it)
        // the colour it replaced is the one the colour button's hold goes back to
        colourHistory.chose(before.toUInt(), colorRgb().toUInt(), onMask)
    }
    picking = null
}

fun CanvasViewModel.cancelPick() {
    pendingPick = null
    picking = null
}

/**
 * The colour button held (or the quick menu's Previous colour): the colour and the previous
 * one trade places, so holding again comes back. As there, the paint tool is the tool then and
 * the panels close.
 */
fun CanvasViewModel.previousColor() {
    val before = colourHistory.swapPrevious(colorRgb().toUInt(), onMask)?.toInt() ?: return
    setRgb(before)
    closePanels()
    if (tool != ToolKind.PAINT) select(ToolKind.PAINT)
    showDone("Previous colour")
}

/** The current and second colours trade places. */
fun CanvasViewModel.swapColors() {
    val current = colorRgb()
    setRgb(secondaryRgb)
    secondaryRgb = current
}

fun CanvasViewModel.clearHistory() {
    colourHistory.clear()
    history = emptyList()
}

/**
 * The artwork written as [kind] into the cache, to share: one file, or a PNG per layer. The
 * export card shows how far it has come (as the core tells it) and a check once made; its
 * Cancel leaves the files unshared (none returned). A failure is told.
 */
suspend fun CanvasViewModel.export(kind: Export, pdfQuality: Int = 100): List<File> =
    withExportCard("The artwork couldn't be exported.") { s -> exportFiles(s, kind, pdfQuality).takeIf { it.isNotEmpty() } }.orEmpty()

/** The file a plugin makes of the artwork's layers of a kind it adds ([e]), written into the cache to share. */
suspend fun CanvasViewModel.exportPlugin(e: io.github.artcraftandroid.procraft.core.PluginExport): File? =
    withExportCard("The artwork couldn't be exported.") { s ->
        withContext(Dispatchers.IO) {
            val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            val base = io.github.artcraftandroid.procraft.core.exportFileName(s.name(), false)
            val file = File(dir, "$base.${e.extension}")
            try {
                s.exportPlugin(e.key, file.path)
                file
            } catch (x: MobileException) {
                AppLog.e(TAG, "exporting", x)
                null
            }
        }
    }

/**
 * Runs [work], an export by the core, with the export card showing how far it has come (as the
 * core tells it) and a check once made: what it made, or null if it was cancelled (its result
 * left unshared) or made nothing ([failed] is then told). One export at a time.
 */
internal suspend fun <T : Any> CanvasViewModel.withExportCard(failed: String, work: suspend (CanvasSession) -> T?): T? {
    val s = session ?: return null
    if (exportShown != null || timeLapseExporting) return null
    s.resetExportProgress()
    return withCard(failed, s::exportProgress) { work(s) }
}

/**
 * As [withExportCard], how far it has come told by [progress] (0..1); the card's Cancel calls
 * [cancel] too, if given, to stop the work.
 */
internal suspend fun <T : Any> CanvasViewModel.withCard(failed: String, progress: () -> Float, cancel: (() -> Unit)? = null, work: suspend () -> T?): T? {
    if (exportShown != null || timeLapseExporting) return null
    exportCancelled = false
    exportStops = cancel
    exportShown = ExportShown(0f)
    val following = viewModelScope.launch {
        while (true) {
            exportShown = ExportShown(progress())
            kotlinx.coroutines.delay(EXPORT_POLL_MS)
        }
    }
    try {
        val made = work()
        following.cancel()
        if (exportCancelled) return null
        if (made == null) {
            showNotice(failed, warning = true)
            return null
        }
        exportShown = ExportShown(1f, done = true)
        kotlinx.coroutines.delay(EXPORT_DONE_MS)
        return made
    } finally {
        following.cancel()
        exportStops = null
        exportShown = null
    }
}

internal suspend fun CanvasViewModel.exportFiles(s: CanvasSession, kind: Export, pdfQuality: Int): List<File> =
    withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val base = io.github.artcraftandroid.procraft.core.exportFileName(s.name(), false)
        val file = File(dir, "$base.${kind.extension}")
        try {
            when (kind) {
                Export.PSD -> s.exportPsd(file.path)
                Export.PDF -> s.exportPdf(file.path, false, pdfQuality.toUByte())
                Export.TIFF -> s.exportTiff(file.path)
                Export.MODEL_OBJ -> s.exportModelObj(file.path)
                Export.MODEL_USDZ -> s.exportModelUsdz(file.path)
                Export.MODEL_ARTWORK -> s.exportModelArtwork(artworkDir?.path ?: return@withContext emptyList(), file.path)
                Export.MODEL_RENDER -> s.exportModelRender(file.path, false)
                Export.MODEL_JPEG -> s.exportModelJpeg(file.path)
                Export.MODEL_TIFF -> s.exportModelTiff(file.path)
                Export.SVG -> s.exportSvg(file.path)
                Export.MODEL_TEXTURES -> {
                    val folder = File(dir, base).apply { deleteRecursively(); mkdirs() }
                    return@withContext s.exportModelTextures(folder.path).map { File(it) }
                }
                Export.LAYERS_PDF -> s.exportPdf(file.path, true, pdfQuality.toUByte())
                Export.LAYER_PNGS -> {
                    val folder = File(dir, base).apply { deleteRecursively(); mkdirs() }
                    return@withContext s.exportLayerPngs(folder.path).map { File(it) }
                }
            }
            listOf(file)
        } catch (e: MobileException) {
            AppLog.e(TAG, "exporting", e)
            emptyList()
        }
    }

/** The turntable's picture size for [turntableSize]: video's 16:9 sizes across, or a square. */
fun CanvasViewModel.turntablePixels(): Pair<Int, Int> = TURNTABLE_SIZES.getOrElse(turntableSize) { TURNTABLE_SIZES[0] }

/** The turntable's frames, each [side] square, [fps] a second, for the preview. */
suspend fun CanvasViewModel.turntablePreview(side: Int, fps: Int): List<Bitmap> {
    val s = session ?: return emptyList()
    val t = turntable
    return withContext(Dispatchers.Default) {
        val count = (t.seconds * fps).roundToInt().coerceAtLeast(1)
        (0 until count).mapNotNull { i ->
            val p = s.modelTurntablePicture(i.toUInt(), count.toUInt(), t, side.toUInt(), side.toUInt(), false) ?: return@mapNotNull null
            Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(p.rgba)) }
        }
    }
}

/** [exportModelAnimation] with the export card, how far it has come shown; Cancel stops a video. */
suspend fun CanvasViewModel.shareModelAnimation(format: Animated): File? {
    val s = session ?: return null
    s.resetExportProgress()
    videoDone = 0f
    return withCard("The animation could not be exported.", { if (format.video) videoDone else s.exportProgress() }) {
        exportModelAnimation(format) { exportCancelled }
    }
}

/** How far a video being made for the export card has come (its frames written, 0..1). */
@Volatile private var videoDone = 0f

/** The model's turntable written as [format] into the cache, to share; null if it can't be made (or [cancelled] said to stop). */
suspend fun CanvasViewModel.exportModelAnimation(format: Animated, cancelled: () -> Boolean = { false }): File? {
    val s = session ?: return null
    val t = turntable
    val (w, h) = turntablePixels()
    return withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val base = io.github.artcraftandroid.procraft.core.exportFileName(s.name(), false)
        val file = File(dir, "$base.${format.extension}")
        try {
            when (format) {
                Animated.GIF -> s.exportModelGif(file.path, t, w.toUInt(), h.toUInt(), TURNTABLE_FPS.toFloat())
                Animated.PNG -> s.exportModelApng(file.path, t, w.toUInt(), h.toUInt(), TURNTABLE_FPS.toFloat())
                Animated.MP4, Animated.HEVC -> {
                    val mime = if (format == Animated.HEVC) android.media.MediaFormat.MIMETYPE_VIDEO_HEVC else android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                    val (vw, vh) = VideoWriter.fit(w, h, maxOf(w, h), mime) ?: return@withContext null
                    val count = (t.seconds * TURNTABLE_FPS).roundToInt().coerceAtLeast(1)
                    val ok = VideoWriter.write(file, vw, vh, TURNTABLE_FPS.toFloat(), count, mime) { i, pixels ->
                        if (cancelled()) return@write false
                        videoDone = i.toFloat() / count
                        val p = s.modelTurntablePicture(i.toUInt(), count.toUInt(), t, vw.toUInt(), vh.toUInt(), true) ?: return@write false
                        // already ARGB, little-endian
                        ByteBuffer.wrap(p.rgba).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, vw * vh)
                        true
                    }
                    if (!ok) return@withContext null
                }
            }
            file
        } catch (e: MobileException) {
            AppLog.e(TAG, "exporting the turntable", e)
            null
        }
    }
}

/** Ends the animated export; an artwork opened for it from the gallery is closed again. */
fun CanvasViewModel.endAnimatedExport() {
    animatedExport = null
    if (exportingFromGallery) {
        exportingFromGallery = false
        closeArtwork()
    }
}

fun CanvasViewModel.animationFrameCount(): Int = session?.animationFrameCount()?.toInt() ?: 0

/** Frame [index] fitted to [side] pixels, without the background if [transparent]. */
fun CanvasViewModel.animationFrame(index: Int, side: Int, transparent: Boolean): Bitmap? {
    val t = session?.animationFrame(index.toUInt(), transparent, side.coerceAtLeast(1).toUInt()) ?: return null
    val w = t.width.toInt()
    val h = t.height.toInt()
    val argb = IntArray(w * h) { i ->
        val o = i * 4
        ((t.rgba[o + 3].toInt() and 255) shl 24) or ((t.rgba[o].toInt() and 255) shl 16) or ((t.rgba[o + 1].toInt() and 255) shl 8) or (t.rgba[o + 2].toInt() and 255)
    }
    return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
}

/** The artwork's name, or the name an unnamed one shows. */
fun CanvasViewModel.artworkTitle(): String = session?.name()?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled Artwork"

/** The artwork's own frame rate, which an animated export starts at: Animation Assist's, else 15. */
fun CanvasViewModel.artworkFps(): Float = (session?.animationSettings()?.fps?.toInt() ?: ANIMATION_FPS).toFloat()

/**
 * The animation written as [format] into the cache, to share, [fps] frames a second: Web Ready
 * fits 480 pixels, else the canvas size (a video as large as the encoder takes, up to 4096).
 */
suspend fun CanvasViewModel.exportAnimation(
    format: Animated,
    webReady: Boolean,
    dither: Boolean,
    perFramePalette: Boolean,
    transparent: Boolean,
    fps: Float,
    cancelled: () -> Boolean = { false },
): File? {
    val s = session ?: return null
    return animationExports.withLock { withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val base = io.github.artcraftandroid.procraft.core.exportFileName(s.name(), false)
        val file = File(dir, "$base.${format.extension}")
        val side = if (webReady) WEB_READY_SIDE else 0
        try {
            when (format) {
                Animated.GIF -> s.exportGif(file.path, side.toUInt(), fps, dither, perFramePalette, transparent)
                Animated.PNG -> s.exportApng(file.path, side.toUInt(), fps, transparent)
                Animated.MP4, Animated.HEVC -> {
                    val mime = if (format == Animated.HEVC) android.media.MediaFormat.MIMETYPE_VIDEO_HEVC else android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                    val (cw, ch) = canvasSize()
                    val (w, h) = VideoWriter.fit(cw, ch, if (webReady) WEB_READY_SIDE else MAX_VIDEO_SIDE, mime) ?: return@withContext null
                    val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    val ok = try {
                        val order = s.animationPlayOrder()
                        VideoWriter.write(file, w, h, fps, order.size, mime) { i, pixels ->
                            if (cancelled()) return@write false
                            videoDone = i.toFloat() / order.size
                            val picture = animationFrame(order[i].toInt(), maxOf(w, h), false) ?: return@write false
                            android.graphics.Canvas(frame).drawBitmap(picture, null, android.graphics.Rect(0, 0, w, h), paint)
                            picture.recycle()
                            frame.getPixels(pixels, 0, w, 0, 0, w, h)
                            true
                        }
                    } finally {
                        frame.recycle()
                    }
                    if (!ok) return@withContext null
                }
            }
            file
        } catch (e: MobileException) {
            AppLog.e(TAG, "exporting an animation", e)
            null
        }
    } }
}

/** [exportAnimation] with the export card (as every export has), how far it has come shown; Cancel stops a video. */
suspend fun CanvasViewModel.shareAnimation(format: Animated, webReady: Boolean, dither: Boolean, perFramePalette: Boolean, transparent: Boolean, fps: Float): File? {
    val s = session ?: return null
    s.resetExportProgress()
    videoDone = 0f
    return withCard("The animation could not be exported.", { if (format.video) videoDone else s.exportProgress() }) {
        exportAnimation(format, webReady, dither, perFramePalette, transparent, fps) { exportCancelled }
    }
}

/** The flattened picture as a JPEG or PNG in the cache, to share, with the export card. */
suspend fun CanvasViewModel.exportImage(jpeg: Boolean): File? = withExportCard("The picture couldn't be exported.") { s ->
    withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val base = io.github.artcraftandroid.procraft.core.exportFileName(s.name(), false)
        val file = File(dir, if (jpeg) "$base.jpg" else "$base.png")
        try {
            if (jpeg) s.exportJpeg(file.path) else s.exportPng(file.path)
        } catch (e: MobileException) {
            AppLog.e(TAG, "exporting", e)
            return@withContext null
        }
        file
    }
}

// ---- palettes ----

/** The palettes as the core keeps them now. */
internal fun CanvasViewModel.refreshPalettes() {
    palettes = paletteStore.list().map { p -> Palette(p.name, p.colors.map { it?.toInt() }) }
    // a change that couldn't be written out (the storage full, say) told, once until one is
    if (paletteStore.takeFailure() != null) message = "The palettes couldn't be kept."
    defaultPalette = paletteStore.defaultPalette().toInt()
}

fun CanvasViewModel.addPalette() {
    paletteStore.add()
    refreshPalettes()
}

/** Our example palettes back, those missing added at the end. */
fun CanvasViewModel.restoreExamplePalettes() {
    paletteStore.restoreExamples()
    refreshPalettes()
}

/** A palette of the main colours in the picture at [uri], light to dark, added at the end. */
fun CanvasViewModel.paletteFromImage(uri: android.net.Uri) {
    val resolver = getApplication<Application>().contentResolver
    viewModelScope.launch {
        val added = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                // read small: a few hundred pixels across is plenty to find its colours
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
                val bitmap = resolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching false
                val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                paletteStore.addFromPixels(pixels.map { it.toUInt() })
            }.getOrNull() == true
        }
        if (!added) {
            message = "This picture can't be read."
            return@launch
        }
        refreshPalettes()
    }
}

/**
 * Reads a palette file at [uri] (ours, or another program's colour swatch or swatch exchange
 * file) into a new palette at the end; false if it isn't one.
 */
suspend fun CanvasViewModel.importSwatches(uri: android.net.Uri): Boolean {
    val resolver = getApplication<Application>().contentResolver
    val added = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching false
            paletteStore.importFile(bytes, fileName(uri))
        }.getOrNull() == true
    }
    if (added) refreshPalettes()
    return added
}

/** The name of the file at [uri], if its provider says. */
internal fun CanvasViewModel.fileName(uri: android.net.Uri): String? = runCatching {
    getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** Palette [i] as a swatches file in the cache, to share. */
suspend fun CanvasViewModel.paletteFile(i: Int): File? {
    val p = palettes.getOrNull(i) ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = paletteStore.swatchesFile(i.toUInt()) ?: return@runCatching null
            val base = p.name.filter { it.isLetterOrDigit() || it in " _-" }.trim().ifEmpty { "Palette" }
            File(File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }, "$base.swatches").also { it.writeBytes(bytes) }
        }.onFailure { AppLog.e(TAG, "sharing a palette", it) }.getOrNull()
    }
}

fun CanvasViewModel.renamePalette(i: Int, name: String) {
    paletteStore.rename(i.toUInt(), name)
    refreshPalettes()
}

fun CanvasViewModel.duplicatePalette(i: Int) {
    paletteStore.duplicate(i.toUInt())
    refreshPalettes()
}

fun CanvasViewModel.deletePalette(i: Int) {
    paletteStore.delete(i.toUInt())
    refreshPalettes()
}

fun CanvasViewModel.chooseDefaultPalette(i: Int) {
    paletteStore.chooseDefault(i.toUInt())
    refreshPalettes()
}

/** Puts [rgb] in a palette's [slot], or empties it when null. */
fun CanvasViewModel.setSwatch(i: Int, slot: Int, rgb: Int?) {
    paletteStore.setSwatch(i.toUInt(), slot.toUInt(), rgb?.toUInt())
    refreshPalettes()
}

internal fun CanvasViewModel.applyColor() {
    val c = colorRgb()
    val (r, g, b) = Triple(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
    session?.setColor(r, g, b)
    // editing text, the letters chosen take it (and what is typed next): drawn again
    if (textInfo != null) session?.let { s ->
        s.editingText()?.let(s::layerText)?.let { now ->
            if (now != textInfo) {
                textInfo = now
                renderText()
            }
        }
    }
    if (colorNames) nameColorSoon()
    // recoloring, the area under the reticle takes the new colour
    val s = session
    if (recoloring && s != null) pourLater("colour") { s.recolorColour(r, g, b) }
}

/**
 * Names the colour in a message as it changes (Color Description Notifications): at once, or,
 * while it keeps changing, the latest colour at most every [COLOR_NAME_GAP_MS].
 */
internal fun CanvasViewModel.nameColorSoon() {
    naming?.cancel()
    val wait = (namedAt + COLOR_NAME_GAP_MS - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
    naming = viewModelScope.launch {
        kotlinx.coroutines.delay(wait)
        namedAt = android.os.SystemClock.uptimeMillis()
        message = io.github.artcraftandroid.procraft.ui.colourName(colorRgb())
    }
}

/** A click for an action done, with Feedback Sounds on. */
fun CanvasViewModel.feedback() {
    if (!feedbackSounds) return
    getApplication<Application>().getSystemService(android.media.AudioManager::class.java)
        ?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK)
}

