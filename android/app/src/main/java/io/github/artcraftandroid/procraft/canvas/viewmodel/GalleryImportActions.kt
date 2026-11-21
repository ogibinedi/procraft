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
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CANVAS_DRAWN_LIMIT_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CANVAS_POLL_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CLOSING_PICTURE_SIDE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.COLOR_NAMES
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.COPY_ROOM_SPARE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.EXPORT_DONE_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.EXPORT_POLL_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.FEEDBACK_SOUNDS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.FIT_INSIDE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.GROUP_NAME
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.IMPORT_COPY_SHARE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.IMPORT_CREEP
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.IMPORT_CREEP_TO
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.MODEL_EXTENSIONS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.NO_ROOM_TO_IMPORT
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.NO_TIME_LAPSE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.ORIENTATION_MEMORY
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.OWN_EXTENSION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.PACKAGE_EXTENSION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.PACKAGE_LABEL
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.PDF_DPI
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.PDF_MOST_SIDE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.PRESETS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SIMPLIFIED_UNDOS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SINGLE_TOUCH_COMPANION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SPEED_DISTANCE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.TAP_ASSISTANCE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.WIDE_COLOR
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.WINDOW_WAIT_MS

fun CanvasViewModel.refreshGallery() {
    viewModelScope.launch {
        val stack = openStack
        val entries = withContext(Dispatchers.IO) { store.list(stack?.dir ?: store.root) }
        galleryEntries = entries
        artworks = entries.filterIsInstance<Artwork>()
        // an emptied stack closes
        if (stack != null && entries.isEmpty()) {
            openStack = null
            refreshGallery()
        }
    }
    // the home screen's widget shows the artwork worked on last
    viewModelScope.launch(Dispatchers.IO) { io.github.artcraftandroid.procraft.gallery.RecentArtworkWidget.refresh(getApplication<Application>()) }
}

fun CanvasViewModel.showStack(s: io.github.artcraftandroid.procraft.gallery.Stack?) {
    openStack = s
    refreshGallery()
}

/**
 * Select's picked [entries] (artworks and stacks) put together in one stack where the first
 * was: the first stack picked takes in the rest; with none picked, a new stack.
 */
fun CanvasViewModel.stackPicked(entries: List<io.github.artcraftandroid.procraft.gallery.GalleryEntry>) {
    if (entries.size < 2 || openStack != null) return
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.stackPicked(entries) }
        refreshGallery()
    }
}

/** Each of [entries] duplicated right after itself (a stack with a copy of each of its artworks), one after another. */
fun CanvasViewModel.duplicateEntries(entries: List<io.github.artcraftandroid.procraft.gallery.GalleryEntry>) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            for (e in entries) {
                when (e) {
                    is Artwork -> store.duplicate(e)
                    is io.github.artcraftandroid.procraft.gallery.Stack -> store.duplicateStack(e)
                }
            }
        }
        refreshGallery()
    }
}

/** [entries] deleted, a stack with every artwork in it (Delete asks first). */
fun CanvasViewModel.deleteEntries(entries: List<io.github.artcraftandroid.procraft.gallery.GalleryEntry>) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            for (e in entries) {
                when (e) {
                    is Artwork -> store.delete(e)
                    is io.github.artcraftandroid.procraft.gallery.Stack -> store.deleteStack(e)
                }
            }
        }
        refreshGallery()
    }
}

fun CanvasViewModel.renameStack(s: io.github.artcraftandroid.procraft.gallery.Stack, name: String) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.renameStack(s, name) }
        if (openStack?.dir == s.dir) openStack = s.copy(name = name.trim().ifEmpty { "Stack" })
        refreshGallery()
    }
}

internal fun CanvasViewModel.readPresets(): List<CanvasPreset> = CanvasPreset.fromJson(presetPrefs.getString(PRESETS, null))

internal fun CanvasViewModel.savePresets(list: List<CanvasPreset>) {
    canvasPresets = list
    presetPrefs.edit().putString(PRESETS, CanvasPreset.toJson(list)).apply()
}

/** Opens Custom Canvas on the preset at [index], or on a new one. */
fun CanvasViewModel.openCustomCanvas(index: Int? = null) {
    editingPreset = index?.takeIf { it in canvasPresets.indices }
    customCanvasOpen = true
}

fun CanvasViewModel.closeCustomCanvas() {
    customCanvasOpen = false
    editingPreset = null
}

/**
 * Custom Canvas's Done: an edited preset is saved in its place; a new one goes at the end of
 * the list and is made into an artwork. True if an artwork was made.
 */
fun CanvasViewModel.finishCustomCanvas(p: CanvasPreset): Boolean {
    val index = editingPreset
    closeCustomCanvas()
    if (index != null && index in canvasPresets.indices) {
        savePresets(canvasPresets.toMutableList().also { it[index] = p })
        return false
    }
    savePresets(canvasPresets + p)
    newPresetArtwork(p)
    return true
}

fun CanvasViewModel.deletePreset(index: Int) {
    if (index in canvasPresets.indices) savePresets(canvasPresets.filterIndexed { i, _ -> i != index })
}

/** A new artwork made as preset [p] says, opened on the canvas. */
fun CanvasViewModel.newPresetArtwork(p: CanvasPreset) {
    if (opening || session != null) return
    opening = true
    val rgb = listOf(((p.background shr 16) and 255) / 255f, ((p.background shr 8) and 255) / 255f, (p.background and 255) / 255f)
    viewModelScope.launch {
        val s = withContext(Dispatchers.IO) {
            CanvasSession.newCustom(p.width.toUInt(), p.height.toUInt(), p.dpi, rgb, !p.backgroundHidden, "Untitled Artwork").also {
                it.setNewProfile(p.profile)
                loadBrushes(it)
            }
        }
        opening = false
        start(s, store.newFolder(galleryDir), brushesLoaded = true)
        timeLapse?.settings = p.timeLapse
        startedNew()
    }
}

/** Says the canvas holds no more layers, and how many it holds. */
internal fun CanvasViewModel.noRoom(): String {
    val (w, h) = canvasSize()
    return "This canvas holds at most ${layersFor(w, h)} layers."
}

/** The layers a canvas [width] × [height] holds on this device (the core's cap). */
fun CanvasViewModel.layersFor(width: Int, height: Int): Int =
    io.github.artcraftandroid.procraft.core.layersFor(width.coerceAtLeast(1).toUInt(), height.coerceAtLeast(1).toUInt())
        .coerceAtMost(Int.MAX_VALUE.toUInt()).toInt()

/** A new artwork of `width` × `height` pixels (the screen's size), in Display P3, opened on the canvas; of a plugin's [kind] (`<id>.<key>`), its plugin keeping it. */
fun CanvasViewModel.newArtwork(width: Int, height: Int, kind: String? = null) {
    if (opening || session != null) return
    opening = true
    viewModelScope.launch {
        val s = withContext(Dispatchers.IO) {
            val made = CanvasSession(width.coerceAtLeast(1).toUInt(), height.coerceAtLeast(1).toUInt()).also {
                it.setNewProfile(io.github.artcraftandroid.procraft.gallery.CanvasPreset.P3)
                loadBrushes(it)
            }
            if (kind == null) {
                made
            } else {
                val canvas = plugins.openCanvas(kind, width.coerceAtLeast(1).toUInt(), height.coerceAtLeast(1).toUInt(), null)
                made.takeIf { canvas != null && it.attachPluginCanvas(kind, canvas) }
            }
        }
        opening = false
        if (s == null) {
            message = "The plugin couldn't make this artwork."
            return@launch
        }
        start(s, store.newFolder(galleryDir), brushesLoaded = true)
        startedNew()
    }
}

fun CanvasViewModel.toggleCompanionMode(m: CompanionMode) {
    feedback()
    companionMode = if (companionMode == m) CompanionMode.NONE else m
}

fun CanvasViewModel.readAppSettings() {
    colorNames = interfacePrefs.getBoolean(COLOR_NAMES, false)
    feedbackSounds = interfacePrefs.getBoolean(FEEDBACK_SOUNDS, false)
    singleTouchCompanion = interfacePrefs.getBoolean(SINGLE_TOUCH_COMPANION, false)
    tapAssistance = interfacePrefs.getBoolean(TAP_ASSISTANCE, false)
    if (!singleTouchCompanion) companionMode = CompanionMode.NONE
    orientationMemory = interfacePrefs.getBoolean(ORIENTATION_MEMORY, true)
    fitInside = interfacePrefs.getBoolean(FIT_INSIDE, false)
    timeLapseDisabled = interfacePrefs.getBoolean(NO_TIME_LAPSE, false)
    session?.let(this::applyAppSettings)
    applyFitInsets()
}

/** The settings the canvas itself keeps: simplified undos, the speed distance, wide colour and orientation memory. */
internal fun CanvasViewModel.applyAppSettings(s: CanvasSession) {
    s.setOrientationMemory(orientationMemory)
    s.setSimplifiedUndos(interfacePrefs.getBoolean(SIMPLIFIED_UNDOS, true))
    s.setSpeedDistance(interfacePrefs.getFloat(SPEED_DISTANCE, 1f))
    s.setWideColour(wideColorAllowed())
    refreshWideColor(s)
}

/** Whether a canvas may show in wide colour: the screen takes it and Wide Color is on. */
internal fun CanvasViewModel.wideColorAllowed(): Boolean =
    getApplication<Application>().resources.configuration.isScreenWideColorGamut &&
        interfacePrefs.getBoolean(WIDE_COLOR, wideColorByDefault())

/** The window follows [s]'s canvas into wide colour and out of it. */
internal fun CanvasViewModel.refreshWideColor(s: CanvasSession) {
    wideColorWindow = s.showsWideColour()
}

/** What fitting the canvas keeps clear of: the top bar and the sidebar, with Fit Canvas Inside Interface. */
fun CanvasViewModel.applyFitInsets() {
    val s = session ?: return
    if (!fitInside) {
        s.setFitInsets(0f, 0f, 0f, 0f)
        return
    }
    val side = if (showSideBar) (io.github.artcraftandroid.procraft.ui.Pc.sideBarShown.value + io.github.artcraftandroid.procraft.ui.Pc.PopoverGap.value) * density else 0f
    s.setFitInsets(if (rightHanded) 0f else side, io.github.artcraftandroid.procraft.ui.Pc.barShown.value * density, if (rightHanded) side else 0f, 0f)
}

/** A new artwork just started: without time-lapse if it is turned off. */
internal fun CanvasViewModel.startedNew() {
    if (timeLapseDisabled) changeTimeLapseRecording(false)
}

/** A new artwork from a photo or picture file, its size, the picture as its one layer. */
fun CanvasViewModel.newFromPicture(uri: android.net.Uri) {
    viewModelScope.launch { pictureArtwork(uri) }
}

/** [newFromPicture], the artwork called [title] (an imported file's name). */
internal suspend fun CanvasViewModel.pictureArtwork(uri: android.net.Uri, title: String = "Untitled Artwork") {
    // in Display P3 if the picture is, else in sRGB
    val p3 = withContext(Dispatchers.IO) { pictureIsP3(uri) }
    val image = withContext(Dispatchers.IO) { decodePicture(uri, p3) }
    if (image == null) {
        message = "This picture can't be opened."
        return
    }
    val s = withContext(Dispatchers.IO) {
        CanvasSession.fromImage(image, title).also {
            if (p3) it.setNewProfile(io.github.artcraftandroid.procraft.gallery.CanvasPreset.P3)
            loadBrushes(it)
        }
    }
    start(s, store.newFolder(galleryDir), brushesLoaded = true)
    startedNew()
}

/**
 * A file from elsewhere into the gallery, with the import card: an artwork file (opened once
 * in, as the gallery's Import does), or a picture as a new artwork.
 */
fun CanvasViewModel.importFile(uri: android.net.Uri) {
    viewModelScope.launch { importWithCard(uri, open = true) }
}

/**
 * [uri] brought in with the import card showing its name; the one artwork it made opened if
 * [open]. Cancel throws away what it made.
 */
internal suspend fun CanvasViewModel.importWithCard(uri: android.net.Uri, open: Boolean) {
    if (importBusy || exportShown != null) return
    val name = withContext(Dispatchers.IO) { fileName(uri) } ?: "Untitled"
    val made = withImportCard(name) { importOne(uri) } ?: return
    if (open && session == null && made.size == 1) openImported(made.first())
}

/** A new gallery folder for an artwork being imported, kept as the import's. */
internal fun CanvasViewModel.importFolder(): File = store.newFolder(galleryDir).also { importMade += it }

/** The import card's Cancel: the card goes at once, and what the import makes is thrown away. */
fun CanvasViewModel.cancelImport() {
    importCancelled = true
    importShown = null
}

/**
 * Runs [work], bringing in the file [name], with the import card: its pie follows the copy in,
 * creeps on while the file is read, and is full with a check once done (none if the work told
 * why it couldn't: its message says so). The artwork folders it made in the gallery, or null
 * if Cancel was tapped (they are thrown away; a picture or model opened as a new canvas
 * meanwhile stays).
 */
internal suspend fun CanvasViewModel.withImportCard(name: String, work: suspend () -> Unit): List<File>? {
    importCancelled = false
    importBusy = true
    importMade.clear()
    importStep = 0f
    importShown = ImportShown(name, 0f)
    // counted, not compared: the canvas shows a message and clears it at once, which can come
    // before the work's end is looked at here
    val told = messagesGiven
    val following = viewModelScope.launch {
        var creep = 0f
        while (true) {
            creep += (IMPORT_CREEP_TO - creep) * IMPORT_CREEP
            if (!importCancelled) importShown = ImportShown(name, maxOf(importStep, creep))
            kotlinx.coroutines.delay(EXPORT_POLL_MS)
        }
    }
    try {
        work()
        following.cancel()
        val made = withContext(Dispatchers.IO) { importMade.toList().filter { it.isDirectory } }
        if (importCancelled) {
            withContext(Dispatchers.IO) { made.forEach { it.deleteRecursively() } }
            refreshGallery()
            return null
        }
        // full, then the check drawn in it before the card goes
        if (messagesGiven == told) {
            importShown = ImportShown(name, 1f, done = true)
            kotlinx.coroutines.delay(EXPORT_DONE_MS)
        }
        return made
    } finally {
        following.cancel()
        importShown = null
        importBusy = false
    }
}

/** The artwork just imported into [dir] opened, the gallery zooming into its cell. */
internal suspend fun CanvasViewModel.openImported(dir: File) {
    val entries = withContext(Dispatchers.IO) { store.list(galleryDir) }
    galleryEntries = entries
    artworks = entries.filterIsInstance<Artwork>()
    artworks.firstOrNull { it.dir.name == dir.name }?.let { openArtwork(it) }
}

/**
 * Files from elsewhere into the gallery, one after another as [importFile] takes each; a
 * model among them takes the rest as the files picked with it (an OBJ's material file and
 * the images it maps).
 */
fun CanvasViewModel.importFiles(uris: List<android.net.Uri>) {
    viewModelScope.launch {
        val names = withContext(Dispatchers.IO) { uris.map { fileName(it) } }
        val models = uris.indices.filter { i -> names[i]?.let { isModelName(it) } == true }
        // each one opened is kept (closed, so saved) as the next comes in
        if (models.isEmpty()) {
            for (uri in uris) {
                if (session != null) closeArtwork()
                importWithCard(uri, open = uris.size == 1)
            }
            return@launch
        }
        val picked = (uris.indices - models.toSet()).mapNotNull { i -> names[i]?.let { uris[i] to it } }
        for (i in models) {
            if (session != null) closeArtwork()
            newFromModel(uris[i], names[i] ?: continue, picked)
        }
    }
}

internal fun CanvasViewModel.isModelName(name: String) = MODEL_EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) }

internal suspend fun CanvasViewModel.importOne(uri: android.net.Uri) {
    val resolver = getApplication<Application>().contentResolver
    val head = withContext(Dispatchers.IO) { runCatching { resolver.openInputStream(uri)?.use { s -> ByteArray(4).also { s.read(it) } } }.getOrNull() }
    val name = withContext(Dispatchers.IO) { fileName(uri) }
    // a file that can't be read at all (one the app isn't allowed to) is no picture either
    if (head == null) {
        message = "This file can't be opened."
        return
    }
    // another program's palette
    val foreign = head != null && String(head, Charsets.US_ASCII) == "ASEF" || name?.endsWith(".aco", ignoreCase = true) == true
    val zip = head != null && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    // a zip is copied once and told by the names it holds, read from its end: a big artwork
    // isn't read into memory to find out what it is
    val zipFile = if (zip) withContext(Dispatchers.IO) { cacheCopy(uri) } else null
    if (zip && zipFile == null) {
        if (!importCancelled) message = "This file can't be opened."
        return
    }
    try {
        importOne(uri, head, name, foreign, zipFile)
    } finally {
        zipFile?.delete()
    }
}

/** [importOne] past the file's first bytes: [zipFile], a copy of a zip, read as what it holds. */
internal suspend fun CanvasViewModel.importOne(uri: android.net.Uri, head: ByteArray?, name: String?, foreign: Boolean, zipFile: File?) {
    val kind = zipFile?.let { f -> withContext(Dispatchers.IO) { io.github.artcraftandroid.procraft.core.zipFileKind(f.path) } }
    // the zip whole, read only for what needs all of it (a model's files)
    suspend fun zipBytes() = zipFile?.let { f -> withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() } }
    if (kind == io.github.artcraftandroid.procraft.core.ZipKind.Brushes && zipFile != null) {
        addBrushesFile(zipFile, name)
    } else if ((foreign || kind == io.github.artcraftandroid.procraft.core.ZipKind.Swatches) && importSwatches(uri)) {
        message = "Palette added."
    } else if (foreign) {
        message = "This palette can't be read."
    } else if (name != null && isModelName(name)) {
        newFromModel(uri, name)
    } else if (kind == io.github.artcraftandroid.procraft.core.ZipKind.ModelArtwork && zipFile != null) {
        // a model's artwork, whole
        val ok = withContext(Dispatchers.IO) {
            val dir = importFolder()
            io.github.artcraftandroid.procraft.core.unpackModelArtwork(zipFile.path, dir.path).also { if (!it) dir.deleteRecursively() }
        }
        if (ok) refreshGallery() else message = "This file can't be opened."
    } else if (kind is io.github.artcraftandroid.procraft.core.ZipKind.Model) {
        // a model zipped with its material file and images (as a folder of them)
        val title = kind.name.substringAfterLast('/').substringBeforeLast('.')
        newFromModel(uri, if (name?.endsWith(".zip", ignoreCase = true) == true) name else "${name ?: "model"}.zip", bytes = zipBytes(), title = title)
    } else if (name != null && isOtherBrushFile(name)) {
        // another app's brushes, read from a copy (a zip's is made already)
        val copy = zipFile ?: withContext(Dispatchers.IO) { cacheCopy(uri) }
        if (copy == null) {
            if (!importCancelled) message = if (copyNoRoom) NO_ROOM_TO_IMPORT else "This file has no brushes in it."
        } else {
            try {
                addBrushesFile(copy, name)
            } finally {
                if (copy !== zipFile) withContext(Dispatchers.IO) { copy.delete() }
            }
        }
    } else if (head != null && String(head, Charsets.ISO_8859_1) == "8BPS") {
        importPsd(uri, name)
    } else if (head != null && String(head, Charsets.ISO_8859_1) == "%PDF") {
        importPdf(uri, name)
    } else if (zipFile != null) {
        // an artwork file of ours, or a layered package
        val ok = withContext(Dispatchers.IO) {
            val dir = importFolder()
            val ours = runCatching { zipFile.inputStream().use { io.github.artcraftandroid.procraft.gallery.ArtworkFile.read(it, dir) } }.getOrNull() == true
            (ours || importPackage(zipFile, dir)).also { if (!it) dir.deleteRecursively() }
        }
        if (ok) refreshGallery() else message = "This file can't be opened."
    } else {
        // named after its file, as a picture opened on the computer is
        pictureArtwork(uri, name?.substringBeforeLast('.')?.trim()?.ifBlank { null } ?: "Untitled Artwork")
    }
}

/**
 * A PDF from [uri] made into a new artwork named after its file: each page a layer ("Page 1"
 * at the bottom), drawn on white at [PDF_DPI] (the long side kept within [PDF_MOST_SIDE]) and
 * fitted to the first page's size, with Page Assist on; a message says why if it can't be.
 */
internal suspend fun CanvasViewModel.importPdf(uri: android.net.Uri, name: String?) {
    val app = getApplication<Application>()
    val outcome = withContext(Dispatchers.IO) {
        val pages = File(app.cacheDir, "pdf-${System.nanoTime()}").apply { mkdirs() }
        val dir = importFolder()
        try {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                    if (pdf.pageCount == 0) return@use io.github.artcraftandroid.procraft.core.PagesOutcome.Unreadable
                    // the canvas: the first page's size at the resolution, within the most side
                    val (w, h) = pdf.openPage(0).use { p ->
                        val scale = minOf(PDF_DPI / 72f, PDF_MOST_SIDE / maxOf(p.width, p.height).toFloat())
                        (p.width * scale).roundToInt().coerceAtLeast(1) to (p.height * scale).roundToInt().coerceAtLeast(1)
                    }
                    // more pages than a canvas this size holds as layers: none drawn, as none will be taken
                    val most = io.github.artcraftandroid.procraft.core.layersFor(w.toUInt(), h.toUInt())
                    if (pdf.pageCount.toUInt() > most) return@use io.github.artcraftandroid.procraft.core.PagesOutcome.TooManyPages(most)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    for (i in 0 until pdf.pageCount) {
                        pdf.openPage(i).use { p ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            // each page fitted into the canvas, in its middle
                            val s = minOf(w / p.width.toFloat(), h / p.height.toFloat())
                            val m = android.graphics.Matrix().apply {
                                postScale(s, s)
                                postTranslate((w - p.width * s) / 2f, (h - p.height * s) / 2f)
                            }
                            p.render(bitmap, null, m, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                        File(pages, "page-${i + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    bitmap.recycle()
                    io.github.artcraftandroid.procraft.core.importPages(
                        pages.path, pdf.pageCount.toUInt(), w.toUInt(), h.toUInt(), PDF_DPI, dir.path,
                        name?.substringBeforeLast('.')?.trim().orEmpty(),
                    )
                }
            } ?: io.github.artcraftandroid.procraft.core.PagesOutcome.Unreadable
        } catch (e: Exception) {
            AppLog.e("ProCraft", "importing a PDF", e)
            io.github.artcraftandroid.procraft.core.PagesOutcome.Unreadable
        } finally {
            pages.deleteRecursively()
        }.also { if (it != io.github.artcraftandroid.procraft.core.PagesOutcome.Imported) dir.deleteRecursively() }
    }
    when (outcome) {
        io.github.artcraftandroid.procraft.core.PagesOutcome.Imported -> refreshGallery()
        is io.github.artcraftandroid.procraft.core.PagesOutcome.TooManyPages ->
            message = "This PDF has more pages than a canvas its size can hold as layers (${outcome.most})."
        io.github.artcraftandroid.procraft.core.PagesOutcome.Unreadable -> message = "This PDF can't be opened."
    }
}

/** A layered PSD from [uri] made into a new artwork, named after its file; a message says why if it can't be. */
internal suspend fun CanvasViewModel.importPsd(uri: android.net.Uri, name: String?) {
    val app = getApplication<Application>()
    val outcome = withContext(Dispatchers.IO) {
        val temp = File(app.cacheDir, "import-${System.nanoTime()}.psd")
        val dir = importFolder()
        try {
            app.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            io.github.artcraftandroid.procraft.core.importPsd(temp.path, dir.path, name?.substringBeforeLast('.')?.trim().orEmpty())
        } catch (e: Exception) {
            AppLog.e("ProCraft", "importing a PSD", e)
            io.github.artcraftandroid.procraft.core.PsdOutcome.Unreadable
        } finally {
            temp.delete()
        }.also { if (it != io.github.artcraftandroid.procraft.core.PsdOutcome.Imported) dir.deleteRecursively() }
    }
    when (outcome) {
        io.github.artcraftandroid.procraft.core.PsdOutcome.Imported -> refreshGallery()
        is io.github.artcraftandroid.procraft.core.PsdOutcome.TooManyLayers ->
            message = "This file has more layers than a canvas its size can hold (${outcome.most})."
        io.github.artcraftandroid.procraft.core.PsdOutcome.Unsupported -> message = "This kind of PSD can't be opened."
        io.github.artcraftandroid.procraft.core.PsdOutcome.Unreadable -> message = "This file can't be opened."
    }
}

/**
 * The file at [uri] copied whole into the cache (streamed through, never held in memory), or
 * none (with [copyNoRoom] set when there wasn't room for it); the import card's pie follows it,
 * and its Cancel stops it.
 */
internal fun CanvasViewModel.cacheCopy(uri: android.net.Uri): File? {
    val temp = File(getApplication<Application>().cacheDir, "import-${System.nanoTime()}.zip")
    val resolver = getApplication<Application>().contentResolver
    val size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
    copyNoRoom = false
    // a file the device has no room for isn't begun
    if (size > 0 && temp.parentFile?.usableSpace?.let { it < size + COPY_ROOM_SPARE } == true) {
        copyNoRoom = true
        return null
    }
    return runCatching {
        resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                var copied = 0L
                while (true) {
                    if (importCancelled) error("cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (size > 0) importStep = IMPORT_COPY_SHARE * copied / size
                }
            }
        } ?: error("nothing to read")
        temp
    }.getOrElse {
        // (the device filling up meanwhile, whatever else wrote to it)
        copyNoRoom = it is java.io.IOException && it.message.orEmpty().contains("ENOSPC")
        if (!importCancelled) AppLog.e("ProCraft", "copying a file in", it)
        temp.delete()
        null
    }
}

/**
 * The layered package in [source] (a copy in the cache) made into the artwork folder [dir]: its
 * layers, settings and preview (as the gallery's thumbnail until it is opened); false if it isn't one.
 */
internal fun CanvasViewModel.importPackage(source: File, dir: File): Boolean {
    return try {
        val s = runCatching { CanvasSession.openPackage(source.path) }.getOrNull() ?: return false
        s.save(dir.path)
        // its time-lapse: the settings, and its videos as they are
        val segments = File(getApplication<Application>().cacheDir, "segments-${System.nanoTime()}")
        try {
            runCatching { io.github.artcraftandroid.procraft.core.packageTimeLapse(source.path, segments.path) }
                .getOrNull()?.let { TimeLapse(dir).take(it) }
        } finally {
            segments.deleteRecursively()
        }
        java.util.zip.ZipFile(source).use { zip ->
            zip.getEntry("QuickLook/Thumbnail.png")?.let { e ->
                zip.getInputStream(e).use { input -> File(dir, "thumbnail.png").outputStream().use { input.copyTo(it) } }
            }
        }
        true
    } catch (e: Exception) {
        AppLog.e("ProCraft", "importing a package", e)
        false
    }
}

/** The open artwork as a layered package in the cache, to share, named with [extension] (ours or the other app's). */
suspend fun CanvasViewModel.exportPackageFile(extension: String = OWN_EXTENSION): File? = withExportCard("The artwork couldn't be exported.") { s ->
    withContext(Dispatchers.IO) {
        runCatching {
            val base = io.github.artcraftandroid.procraft.core.exportFileName(s.name(), true)
            val out = File(File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }, "$base.$extension")
            s.exportPackage(out.path, System.nanoTime().toULong(), timeLapse?.forPackage())
            out
        }.onFailure { AppLog.e("ProCraft", "exporting a package", it) }.getOrNull()
    }
}

/** Where [s]'s canvas is on screen through the view [m] (as `viewMatrix` gives it), window pixels. */
internal fun CanvasViewModel.canvasPlace(s: CanvasSession, m: List<Float>): CanvasPlace {
    val info = s.info()
    val (w, h) = info.width.toFloat() to info.height.toFloat()
    val centre = Offset(m[0] * w / 2f + m[2] * h / 2f + m[4], m[1] * w / 2f + m[3] * h / 2f + m[5])
    // the canvas's down axis on screen gives its scale and turn whether or not it is mirrored
    // (a mirror turns its across axis round)
    val scale = kotlin.math.hypot(m[2], m[3])
    val angle = kotlin.math.atan2(-m[2], m[3])
    val mirrored = m[0] * m[3] - m[1] * m[2] < 0f
    return CanvasPlace(centre, androidx.compose.ui.geometry.Size(w * scale, h * scale), angle, mirrored)
}

/**
 * Waits (off the main thread, as a big artwork's first frame holds the session a while) until
 * the open artwork's canvas has drawn a frame, or a few seconds have passed by the clock; if the
 * canvas can't draw (the device's GPU couldn't make it), says so at once. A call into the
 * session that blocks (its first frame holding it) can't hold the wait, and with it the
 * opening zoom's cover, past that.
 */
suspend fun CanvasViewModel.awaitCanvasDrawn() {
    val s = session ?: return
    val failed = askUntil(viewModelScope, CANVAS_DRAWN_LIMIT_MS, CANVAS_POLL_MS) {
        val info = runCatching { s.info() }.getOrNull()
        when {
            info == null -> false
            info.error != null -> true
            info.frames != 0uL -> false
            else -> null
        }
    } ?: false
    if (failed && session === s) message = "This device's graphics can't draw the canvas."
}

/**
 * The home screen's Recent artwork widget was tapped: the artwork worked on last opens, once the window
 * has its size (the app may only now be starting); nothing if one is already open.
 */
fun CanvasViewModel.openMostRecent() {
    if (opening || session != null) return
    viewModelScope.launch {
        var waited = 0L
        while (windowPx.width <= 1 && waited < WINDOW_WAIT_MS) {
            kotlinx.coroutines.delay(CANVAS_POLL_MS)
            waited += CANVAS_POLL_MS
        }
        val a = withContext(Dispatchers.IO) { store.mostRecent() } ?: return@launch
        openArtwork(a)
    }
}

/** Opens artwork [a], the gallery zooming into its cell; then [then]. */
fun CanvasViewModel.openArtwork(a: Artwork, then: (() -> Unit)? = null) {
    // one artwork open at a time: a second session on it would leave the screen on the first
    if (opening || session != null) return
    opening = true
    val cell = thumbAt[a.dir.name]
    viewModelScope.launch {
        val s = withContext(Dispatchers.IO) {
            runCatching {
                CanvasSession.open(a.dir.path).also {
                    loadBrushes(it)
                    // a plugin's own artwork goes to its plugin (on); else it shows what it last drew
                    if (it.pluginKind() != null) it.openPluginArtwork(plugins)
                }
            }
        }
        opening = false
        s.onSuccess {
            val m = it.openingViewMatrix(windowPx.width.toUInt(), windowPx.height.toUInt())
            galleryZoom = cell?.let { c -> GalleryZoom(c, canvasPlace(it, m), opening = true, turns = a.turns) }
            start(it, a.dir, brushesLoaded = true)
            then?.invoke()
        }.onFailure { message = "This artwork can't be opened." }
    }
}

/**
 * Artworks from the gallery written as [format] (as [shareableFiles]) with the export card, its
 * pie filling an artwork at a time; its Cancel stops after the artwork being written. Null if
 * cancelled or none could be written (that is told).
 */
suspend fun CanvasViewModel.shareFromGallery(list: List<Artwork>, format: String, pdfQuality: Int = 100, group: String = GROUP_NAME): Pair<List<File>, String>? {
    galleryShared = 0f
    return withCard("The artwork couldn't be exported.", { galleryShared }) {
        shareableFiles(list, format, pdfQuality, group) { written -> galleryShared = written / list.size.coerceAtLeast(1).toFloat() }
            .takeIf { it.first.isNotEmpty() }
    }
}

/**
 * Artworks from the gallery written as [format] (one of [SHARE_FORMATS] but the animated ones)
 * into the cache, to share; with the type they are. Several as a PDF are one file named
 * [group], a page each in their order. [written] hears how many are done after each; a
 * cancelled export stops before the next.
 */
suspend fun CanvasViewModel.shareableFiles(list: List<Artwork>, format: String, pdfQuality: Int = 100, group: String = GROUP_NAME, written: (Int) -> Unit = {}): Pair<List<File>, String> = withContext(Dispatchers.IO) {
    val (extension, mime) = when (format) {
        "PSD" -> "psd" to "image/vnd.adobe.photoshop"
        "PDF" -> "pdf" to "application/pdf"
        "JPEG" -> "jpg" to "image/jpeg"
        "PNG" -> "png" to "image/png"
        "TIFF" -> "tif" to "image/tiff"
        PACKAGE_LABEL -> PACKAGE_EXTENSION to "application/octet-stream"
        else -> OWN_EXTENSION to "application/octet-stream"
    }
    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
    if (format == "PDF" && list.size >= 2) return@withContext groupPdf(list, File(dir, "${io.github.artcraftandroid.procraft.core.exportFileName(group, false)}.$extension"), pdfQuality, written) to mime
    val used = mutableSetOf<String>()
    val files = mutableListOf<File>()
    for ((i, a) in list.withIndex()) {
        if (exportCancelled) break
        val base = io.github.artcraftandroid.procraft.core.exportFileName(a.name, false)
        var name = base
        var n = 2
        while (!used.add(name)) name = "$base ${n++}"
        File(dir, "$name.$extension").takeIf { io.github.artcraftandroid.procraft.gallery.exportArtwork(a.dir, format, it, pdfQuality) }?.let(files::add)
        written(i + 1)
    }
    files to mime
}

/**
 * Artworks [list] as one PDF at [out], a page each in their order (each its picture at its own
 * size, kept at [quality]); one that can't be read is left out. Nothing if cancelled or none
 * could be written. [written] hears how many are done after each.
 */
internal fun CanvasViewModel.groupPdf(list: List<Artwork>, out: File, quality: Int, written: (Int) -> Unit): List<File> =
    runCatching {
        io.github.artcraftandroid.procraft.core.PdfBook.create(out.path, quality.coerceIn(1, 100).toUByte()).use { book ->
            for ((i, a) in list.withIndex()) {
                // left unfinished, the book leaves nothing
                if (exportCancelled) return@runCatching emptyList<File>()
                runCatching { CanvasSession.open(a.dir.path).use { s -> if (s.prepareOffscreen()) book.add(s) } }
                    .onFailure { AppLog.e("ProCraft", "a page of a PDF", it) }
                written(i + 1)
            }
            if (book.pages() == 0u) return@runCatching emptyList<File>()
            book.finish()
            listOf(out)
        }
    }.onFailure { AppLog.e("ProCraft", "sharing a PDF", it) }.getOrDefault(emptyList())

/**
 * Opens artwork [a] to set up its animated export as [kind], going back to the gallery after;
 * the export shows once the canvas is made, as its frames and file are read from it.
 */
fun CanvasViewModel.openForAnimatedExport(a: Artwork, kind: Animated) {
    openArtwork(a) {
        exportingFromGallery = true
        val s = session ?: return@openArtwork
        viewModelScope.launch {
            while (session === s && !s.canvasMade()) kotlinx.coroutines.delay(16)
            if (session === s) animatedExport = kind
        }
    }
}

fun CanvasViewModel.deleteArtwork(a: Artwork) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.delete(a) }
        refreshGallery()
    }
}

fun CanvasViewModel.duplicateArtwork(a: Artwork) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.duplicate(a) }
        refreshGallery()
    }
}

/** Stack [s] deleted, with every artwork in it. */
fun CanvasViewModel.deleteStack(s: io.github.artcraftandroid.procraft.gallery.Stack) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.deleteStack(s) }
        refreshGallery()
    }
}

/** A copy of stack [s] right after it, each of its artworks copied. */
fun CanvasViewModel.duplicateStack(s: io.github.artcraftandroid.procraft.gallery.Stack) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.duplicateStack(s) }
        refreshGallery()
    }
}

/** [e] dragged to [to] in the gallery or stack showing (its place in the list without it), out of its stack if it was in one. */
fun CanvasViewModel.moveInGallery(e: io.github.artcraftandroid.procraft.gallery.GalleryEntry, to: Int) {
    // at once, so the cells move as the finger lifts
    val shown = galleryEntries.filter { it.dir != e.dir }.toMutableList()
    shown.add(to.coerceIn(0, shown.size), e)
    galleryEntries = shown
    artworks = shown.filterIsInstance<Artwork>()
    viewModelScope.launch {
        val into = galleryDir
        withContext(Dispatchers.IO) { store.move(e, to, into) }
        refreshGallery()
    }
}

/** [e] taken out of the stack open to the gallery's front (let go on the way back). */
fun CanvasViewModel.takeOutOfStack(e: io.github.artcraftandroid.procraft.gallery.GalleryEntry) {
    if (openStack == null) return
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.move(e, 0, store.root) }
        refreshGallery()
    }
}

/** Artwork [a] dropped onto [onto] in the gallery: with an artwork it makes a stack, [a] first; a stack takes it in first. */
fun CanvasViewModel.dropOnto(a: Artwork, onto: io.github.artcraftandroid.procraft.gallery.GalleryEntry) {
    if (openStack != null || a.dir == onto.dir) return
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.putOnto(a, onto) }
        refreshGallery()
    }
}

fun CanvasViewModel.renameArtwork(a: Artwork, name: String) {
    if (name.isBlank()) return
    viewModelScope.launch {
        withContext(Dispatchers.IO) { store.rename(a, name) }
        refreshGallery()
    }
}

/** Saves the open artwork and goes back to the gallery. */
fun CanvasViewModel.closeArtwork() {
    endFilling()
    endLayerOpacity()
    endTransform(true)
    // Color Fill fills as the selection tool closes
    closeSelecting()
    endAdjustment(true)
    val s = session ?: return
    noteTime()
    keepChoices(s)
    strokeSound.follow(null, false)
    s.detachProjection()
    val dir = artworkDir
    // the gallery zooms out of the artwork's cell, the canvas fading away over it; the cell as
    // it will show, turned as the canvas is left
    val turns = s.shownTurns().toInt()
    val cell = dir?.let { d ->
        val info = s.info()
        val shown = Artwork(d, "", info.width.toInt(), info.height.toInt(), 0, turns)
        thumbBoxAt[d.name]?.let { io.github.artcraftandroid.procraft.ui.fittedIn(it, shown.ratio) } ?: thumbAt[d.name]
    }
    galleryZoom = cell?.let { c ->
        val picture = s.canvasThumbnail(CLOSING_PICTURE_SIDE.toUInt())?.let { t ->
            val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
            bitmap.asImageBitmap()
        }
        GalleryZoom(c, canvasPlace(s, s.viewMatrix()), opening = false, picture = picture, turns = turns)
    }
    keepColour()
    // a plugin's panel goes with the artwork it was open over
    closePluginPanel()
    val lapse = timeLapse
    session = null
    wideColorWindow = false
    artworkDir = null
    panel = Panel.NONE
    screen = Screen.GALLERY
    forgetReference()
    closingSaves.incrementAndGet()
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                // after the autosave's save under way, if one is
                s.stopAutosave()
                dir?.let { told(runCatching { s.save(it.path) }.map { true }) }
                // its memory free at once, not when the object is collected
                s.releaseCanvas()
                // the frames it recorded made into one more video, their pictures then gone, as
                // the artwork file keeps a time-lapse
                lapse?.let { runCatching { it.forPackage() }; it.close() }
            }
        } finally {
            closingSaves.decrementAndGet()
        }
        galleryVersion++
        refreshGallery()
    }
}

/** Gallery Recovery, once what is being saved is kept: artworks the gallery can't show stacked as Recovered Artworks. */
suspend fun CanvasViewModel.recoverLostArtworks() {
    while (closingSaves.get() > 0) kotlinx.coroutines.delay(100)
    withContext(Dispatchers.IO) { store.recoverLost() }
    galleryVersion++
    refreshGallery()
}

/** Adds the time since it last started counting to the artwork's, and stops counting. */
internal fun CanvasViewModel.noteTime() {
    val s = session ?: return
    val now = android.os.SystemClock.elapsedRealtime()
    if (activeSince > 0) s.addTrackedTime((now - activeSince).coerceAtLeast(0).toULong())
    activeSince = 0
}

