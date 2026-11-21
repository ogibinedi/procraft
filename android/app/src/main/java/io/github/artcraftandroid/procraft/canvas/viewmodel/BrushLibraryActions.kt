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

/** After a change to the library: read it again, keep it, and draw previews again (they go by place). */
internal fun CanvasViewModel.libraryChanged() {
    val s = session ?: return
    // picks go by place, which may hold another brush now, and so do sample strokes being drawn
    pickedBrushes.clear()
    previewRound++
    brushSets = s.brushLibrary()
    readLibraries()
    currentBrush = s.currentBrush()
    if (shownSet != RECENT_SET) shownSet = shownSet.coerceIn(0, (brushSets.size - 1).coerceAtLeast(0))
    recentBrushes = s.recentBrushes()
    readSliders()
    previews.clear()
    shadedNames.clear()
    keepBrushLibrary(s)
}

fun CanvasViewModel.duplicateBrush(b: BrushInfo) {
    session?.duplicateBrush(b.set, b.index) ?: return
    libraryChanged()
}

fun CanvasViewModel.deleteBrush(b: BrushInfo) {
    if (session?.deleteBrush(b.set, b.index) == true) libraryChanged()
}

fun CanvasViewModel.renameBrush(b: BrushInfo, name: String) {
    renamingBrush = null
    if (session?.renameBrush(b.set, b.index, name) == true) libraryChanged()
}

fun CanvasViewModel.togglePickedBrush(b: BrushInfo) {
    val key = "${b.set}-${b.index}"
    if (!pickedBrushes.remove(key)) pickedBrushes.add(key)
}

internal fun CanvasViewModel.pickedPlace(key: String): Pair<UInt, UInt>? {
    val (set, index) = key.split('-').map { it.toUIntOrNull() ?: return null }
    return set to index
}

/** The picked brush becomes the current brush's second; a tool on either has the pair. */
fun CanvasViewModel.combinePicked() {
    val s = session ?: return
    val c = currentBrush ?: return
    val (_, index) = pickedBrushes.singleOrNull()?.let(this::pickedPlace) ?: return
    pickedBrushes.clear()
    if (s.combineBrushes(c.set, c.index, index)) {
        currentBrush = s.currentBrush()
        libraryChanged()
    }
}

fun CanvasViewModel.deletePickedBrushes() {
    val s = session ?: return
    // from the last up, so the places of the rest hold
    val places = pickedBrushes.mapNotNull(this::pickedPlace).sortedByDescending { it.second }
    pickedBrushes.clear()
    if (places.count { (set, index) -> s.deleteBrush(set, index) } > 0) libraryChanged()
}

/** Moves [b] to place [index] of set [set] (a brush dragged onto a set, or along its own). */
fun CanvasViewModel.moveBrush(b: BrushInfo, set: Int, index: Int) {
    if (set == b.set.toInt() && index == b.index.toInt()) return
    if (session?.moveBrush(b.set, b.index, set.toUInt(), index.toUInt()) == true) libraryChanged()
}

/**
 * Moves [brushes] (of one set) together, in their order, to place [index] of set [set]
 * (counted without them there): brushes picked and dragged onto a set, or along their own.
 */
fun CanvasViewModel.moveBrushes(brushes: List<BrushInfo>, set: Int, index: Int) {
    val first = brushes.firstOrNull() ?: return
    if (brushes.size == 1) return moveBrush(first, set, index)
    val indices = brushes.filter { it.set == first.set }.map { it.index }
    if (session?.moveBrushes(first.set, indices, set.toUInt(), index.toUInt()) == true) libraryChanged()
}

/** A new brush at the top of the shown set, opened in the studio. */
fun CanvasViewModel.newBrush() {
    val s = session ?: return
    if (shownSet == RECENT_SET) shownSet = currentBrush?.set?.toInt() ?: 0
    s.newBrush(shownSet.toUInt()) ?: return
    libraryChanged()
    studioNew = true
    openStudio()
}

internal fun CanvasViewModel.readLibraries() {
    brushLibraries = session?.brushLibraries().orEmpty()
    // the cards' strokes go by place, which may have changed
    cardPreviews.clear()
    cardPreviewsLoading.clear()
}

/** Renders brush [index] of set [set] of library [library] small for its card, once, off the main thread. */
fun CanvasViewModel.loadCardPreview(library: Int, set: Int, index: Int) {
    val key = "$library-$set-$index"
    if (cardPreviews.containsKey(key) || !cardPreviewsLoading.add(key)) return
    val s = session ?: return
    viewModelScope.launch {
        val image = withContext(Dispatchers.Default) {
            val t = s.brushLibraryPreview(library.toUInt(), set.toUInt(), index.toUInt(), 212u, 65u) ?: return@withContext null
            val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
            bitmap.asImageBitmap()
        }
        if (image != null && cardPreviewsLoading.contains(key)) cardPreviews[key] = image
    }
}

/**
 * Runs [work] on the libraries off the main thread (it reads and writes their files), then
 * reads them again; [changed]: the library in use may be another, so everything shown of it
 * is read again. Tells [failure] if it didn't work.
 */
internal fun CanvasViewModel.libraryWork(changed: Boolean, failure: String, work: (CanvasSession) -> Boolean) {
    val s = session ?: return
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { brushLibraryWrites.withLock { runCatching { work(s) }.getOrDefault(false) } }
        if (!ok) message = failure
        if (ok && changed) {
            libraryChanged()
            shownSet = currentBrush?.set?.toInt()?.coerceIn(0, (brushSets.size - 1).coerceAtLeast(0)) ?: 0
        }
        readLibraries()
    }
}

/** Brings library [i] into use and shows it. */
fun CanvasViewModel.openLibrary(i: Int) {
    showingLibraries = false
    if (brushLibraries.getOrNull(i)?.current == true) return
    libraryWork(true, "This library can't be opened.") { it.openBrushLibrary(i.toUInt()) }
}

fun CanvasViewModel.renameLibrary(i: Int, name: String) {
    renamingLibrary = null
    if (name.isBlank() || brushLibraries.getOrNull(i)?.name == name.trim()) return
    libraryWork(false, "The library couldn't be renamed.") { it.renameBrushLibrary(i.toUInt(), name) }
}

fun CanvasViewModel.newLibrary() = libraryWork(false, "A new library couldn't be made.") { it.newBrushLibrary() != null }

fun CanvasViewModel.duplicateLibrary(i: Int) = libraryWork(false, "The library couldn't be copied.") { it.duplicateBrushLibrary(i.toUInt()) != null }

fun CanvasViewModel.deleteLibrary(i: Int) {
    deletingLibrary = null
    libraryWork(brushLibraries.getOrNull(i)?.current == true, "The library couldn't be deleted.") { it.deleteBrushLibrary(i.toUInt()) }
}

/** The library as it ships, back beside the others. */
fun CanvasViewModel.restoreLibraries() = libraryWork(false, "The library couldn't be put back.") { it.restoreStockBrushLibrary() != null }

/** A new set at the top of the library, shown, with its name ready to edit. */
fun CanvasViewModel.newBrushSet() {
    val s = session ?: return
    shownSet = s.newBrushSet().toInt()
    libraryChanged()
    renamingSet = shownSet
}

fun CanvasViewModel.renameBrushSet(i: Int, name: String) {
    renamingSet = null
    if (session?.renameBrushSet(i.toUInt(), name) == true) libraryChanged()
}

fun CanvasViewModel.changeBrushSetIcon(i: Int, icon: Int) {
    if (session?.setBrushSetIcon(i.toUInt(), icon.toUInt()) == true) libraryChanged()
}

/**
 * Moves set [from] to place [to] (a set held and dragged onto another); the shown set stays
 * shown, and what is held by place goes with its set.
 */
fun CanvasViewModel.moveBrushSet(from: Int, to: Int) {
    if (from == to || session?.moveBrushSet(from.toUInt(), to.toUInt()) != true) return
    // past the end is the last place, as the core takes it
    val at = to.coerceAtMost(brushSets.lastIndex)
    val moved = { set: Int -> placeAfterSetMove(set, from, at) }
    if (shownSet != RECENT_SET) shownSet = moved(shownSet)
    lastBrush = lastBrush?.let { (set, index) -> moved(set.toInt()).toUInt() to index }
    swipedBrush = null
    // no brush changed: their sample strokes go with their sets, not drawn again
    val place = { key: String -> key.substringBefore('-').toIntOrNull()?.let { "${moved(it)}-${key.substringAfter('-')}" } ?: key }
    val strokes = previews.mapKeys { place(it.key) }
    val shaded = shadedNames.mapKeys { place(it.key) }
    libraryChanged()
    previews.putAll(strokes)
    shadedNames.putAll(shaded)
}

fun CanvasViewModel.duplicateBrushSet(i: Int) {
    shownSet = session?.duplicateBrushSet(i.toUInt())?.toInt() ?: return
    libraryChanged()
}

fun CanvasViewModel.deleteBrushSet(i: Int) {
    if (session?.deleteBrushSet(i.toUInt()) != true) return
    if (shownSet >= i) shownSet = (shownSet - 1).coerceAtLeast(0)
    libraryChanged()
}

/**
 * A brush, or (no brush) the shown set, written to a brush or set file to share. The core writes
 * the file itself, a brush at a time (nothing of it comes through here); a set shows the export
 * card meanwhile, its pie following the brushes written, and its Cancel stops it.
 */
suspend fun CanvasViewModel.brushFileToShare(b: BrushInfo?): File? {
    val s = session ?: return null
    val name = b?.name ?: brushSets.getOrNull(shownSet)?.name ?: return null
    val file = File(exportFolder(), brushFileName(name, single = b != null))
    if (b != null) {
        val written = withContext(Dispatchers.IO) {
            runCatching { s.writeBrushFile(b.set, b.index, file.path) }.onFailure { AppLog.e(TAG, "sharing a brush", it) }.isSuccess
        }
        if (!written) showNotice("This brush couldn't be shared.", warning = true)
        return file.takeIf { written }
    }
    val set = shownSet.toUInt()
    val writing = io.github.artcraftandroid.procraft.core.BrushFileWriting()
    return withCard("This brush set couldn't be shared.", writing::progress, writing::cancel) {
        withContext(Dispatchers.IO) {
            runCatching { s.writeBrushSetFile(set, file.path, writing) }
                .onFailure { if (!exportCancelled) AppLog.e(TAG, "sharing a brush set", it) }
                .getOrNull()?.let { file }
        }
    }
}

/**
 * The library in use written to a library file to share, as [brushFileToShare] writes a set:
 * the export card shows meanwhile. None (and told) if it has no brushes or couldn't be written.
 */
suspend fun CanvasViewModel.libraryFileToShare(): File? {
    val s = session ?: return null
    val safe = libraryName.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "Brushes" }
    val file = File(exportFolder(), "$safe.$BRUSH_LIBRARY_EXTENSION")
    val writing = io.github.artcraftandroid.procraft.core.BrushFileWriting()
    return withCard("This library couldn't be shared.", writing::progress, writing::cancel) {
        withContext(Dispatchers.IO) {
            runCatching { s.writeBrushLibraryFile(file.path, writing) }
                .onFailure { if (!exportCancelled) AppLog.e(TAG, "sharing a brush library", it) }
                .getOrNull()?.let { file }
        }
    }
}

/** A folder of its own in the cache for a file to share, so another of the same name can't write over it while it is shared. */
internal fun CanvasViewModel.exportFolder(): File = File(getApplication<Application>().cacheDir, "exports/${System.nanoTime()}").apply { mkdirs() }

/**
 * A brush, set or library file from elsewhere, with the import card: it joins the library and
 * its set is shown, or comes in as a library of its own, in use (not if the card's Cancel was
 * tapped meanwhile).
 */
fun CanvasViewModel.importBrushes(uri: android.net.Uri) {
    viewModelScope.launch {
        if (importBusy || exportShown != null) return@launch
        val name = withContext(Dispatchers.IO) { fileName(uri) }
        withImportCard(name ?: "Untitled") { importBrushesFrom(uri, name) }
    }
}

/**
 * The brushes in the file at [uri], called [name]: copied into the cache first (streamed
 * through, so a set of a gigabyte and more is never held in memory), then read from there a
 * brush at a time (see [addBrushesFile]).
 */
internal suspend fun CanvasViewModel.importBrushesFrom(uri: android.net.Uri, name: String?) {
    val copy = withContext(Dispatchers.IO) { cacheCopy(uri) }
    if (copy == null) {
        if (!importCancelled) message = if (copyNoRoom) NO_ROOM_TO_IMPORT else "This file could not be read."
        return
    }
    try {
        addBrushesFile(copy, name)
    } finally {
        withContext(Dispatchers.IO) { copy.delete() }
    }
}

/**
 * The brushes in [file] (a copy in the cache), called [name], into the library (a set of their
 * own, or a brush at the top of a set, shown), or with no canvas open the file kept (moved out
 * of the cache) until one opens; told when it has none. The shared core tells the kinds apart
 * and reads a brush, set or library file a brush at a time off the main thread, the import
 * card's pie following it; they join the library here, once nothing holds a place in it.
 */
internal suspend fun CanvasViewModel.addBrushesFile(file: File, name: String?) {
    val s = session
    if (s == null) {
        // ours are told by the names in the zip; another app's file is read to see that it holds any
        val ours = withContext(Dispatchers.IO) { io.github.artcraftandroid.procraft.core.zipFileKind(file.path) } ==
            io.github.artcraftandroid.procraft.core.ZipKind.Brushes
        val any = ours || readBrushesFile(file, name)?.also { it.destroy() } != null
        if (importCancelled) return
        if (!any) {
            message = "This file has no brushes in it."
            return
        }
        val kept = withContext(Dispatchers.IO) {
            pendingBrushDir.mkdirs()
            val to = File(pendingBrushDir, pendingBrushFileName(System.nanoTime(), name ?: "Imported.$BRUSH_EXTENSION"))
            file.renameTo(to) || runCatching { file.copyTo(to) }.isSuccess
        }
        message = if (kept) "Brushes will be added to the library when a canvas opens." else NO_ROOM_TO_IMPORT
        return
    }
    val read = readBrushesFile(file, name)
    if (importCancelled) {
        read?.destroy()
        return
    }
    if (read == null) {
        message = "This file has no brushes in it."
        return
    }
    // plugins' brush engines make theirs of the file here too, off the main thread
    withContext(Dispatchers.Default) { runCatching { s.makeEngineBrushes(read) } }
    brushesRead += read
    addBrushesRead()
}

/**
 * The brushes in [file], called [name], read off the main thread a brush at a time, the import
 * card's pie following from where the copy left it; its Cancel stops the reading. None if it
 * holds none (or it was stopped).
 */
internal suspend fun CanvasViewModel.readBrushesFile(file: File, name: String?): io.github.artcraftandroid.procraft.core.BrushesRead? {
    val reading = io.github.artcraftandroid.procraft.core.BrushFileReading()
    val from = importStep
    val following = viewModelScope.launch {
        while (true) {
            if (importCancelled) reading.cancel()
            importStep = from + (1f - from) * reading.progress()
            kotlinx.coroutines.delay(EXPORT_POLL_MS)
        }
    }
    return try {
        withContext(Dispatchers.Default) {
            runCatching { io.github.artcraftandroid.procraft.core.readBrushesFile(file.path, name, reading) }
                .onFailure { AppLog.e("ProCraft", "reading a brush file", it) }
                .getOrNull()
        }
    } finally {
        following.cancel()
        reading.destroy()
    }
}

/**
 * Brushes read meanwhile join the library (a set of their own at the top, or a brush at the
 * top of a set), that set shown; not while the studio or the Delete question holds a brush
 * by its place, which they would move: then once that ends.
 */
fun CanvasViewModel.addBrushesRead() {
    val s = session ?: return
    if (studioOpen || brushQuestionOpen || brushesRead.isEmpty()) return
    if (shownSet == RECENT_SET) shownSet = currentBrush?.set?.toInt() ?: 0
    for (read in brushesRead.toList()) {
        // a library of its own is brought into use off the main thread (its files written)
        if (read.isLibrary()) {
            libraryWork(true, "This library couldn't be added.") { it.addBrushesRead(read, 0u) != null }
            continue
        }
        s.addBrushesRead(read, shownSet.toUInt())?.let { shownSet = it.toInt() }
    }
    brushesRead.clear()
    libraryChanged()
}

/** Whether a file named [name] holds another app's brushes (see [OTHER_BRUSH_EXTENSIONS]). */
internal fun CanvasViewModel.isOtherBrushFile(name: String) = OTHER_BRUSH_EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) }

/**
 * Brush files kept while no canvas was open, each joining the library once read: off the main
 * thread, as a big set takes seconds. A file stays for the next canvas if this one closes first.
 */
internal fun CanvasViewModel.addPendingBrushes() {
    if (pendingBrushesReading?.isActive == true) return
    pendingBrushesReading = viewModelScope.launch {
        var any = false
        var unread = false
        while (session != null) {
            val f = withContext(Dispatchers.IO) { pendingBrushDir.listFiles()?.minByOrNull { it.name } } ?: break
            val read = withContext(Dispatchers.Default) {
                // named after the set, for another app's (kept as .abr by earlier versions); a
                // big set read a brush at a time, never whole
                runCatching {
                    io.github.artcraftandroid.procraft.core.readBrushesFile(f.path, f.name.substringAfter('-'), null)
                        ?.also { r -> session?.makeEngineBrushes(r) }
                }.getOrNull()
            }
            if (session == null) break
            if (read != null) {
                brushesRead += read
                addBrushesRead()
                any = true
            } else {
                unread = true
            }
            withContext(Dispatchers.IO) { f.delete() }
        }
        if (unread) {
            message = "A brush file couldn't be read."
        } else if (any) {
            message = "Brushes added to the library."
        }
    }
}

fun CanvasViewModel.chooseBrush(b: BrushInfo) {
    val s = session ?: return
    val before = currentBrush
    if (s.selectBrush(b.set, b.index)) {
        if (before != null && (before.set != b.set || before.index != b.index)) lastBrush = before.set to before.index
        currentBrush = s.currentBrush()
        readSliders()
        keepChoices(s)
    }
}

/** Reads the Recent set again: painting adds to it. */
fun CanvasViewModel.readRecentBrushes() {
    recentBrushes = session?.recentBrushes().orEmpty()
}

/** Pins a brush of the Recent set there, or unpins it. */
fun CanvasViewModel.pinRecentBrush(b: BrushInfo, pinned: Boolean) {
    val s = session ?: return
    if (!s.pinRecentBrush(b.set, b.index, pinned)) return
    recentBrushes = s.recentBrushes()
    keepChoices(s)
}

/** Takes a brush out of the Recent set. */
fun CanvasViewModel.clearRecentBrush(b: BrushInfo) {
    val s = session ?: return
    if (!s.clearRecentBrush(b.set, b.index)) return
    recentBrushes = s.recentBrushes()
    keepChoices(s)
}

/** Empties the Recent set, its pinned brushes too. */
fun CanvasViewModel.clearRecentBrushes() {
    val s = session ?: return
    s.clearRecentBrushes()
    recentBrushes = emptyList()
    keepChoices(s)
}

/** A brush of the Recent set shown in its own set, chosen there. */
fun CanvasViewModel.findBrush(b: BrushInfo) {
    shownSet = b.set.toInt()
    chooseBrush(b)
    foundBrush = "${b.set}-${b.index}"
}

/** Back to the brush chosen before this one (and Previous brush again comes back here). */
fun CanvasViewModel.previousBrush() {
    val s = session ?: return
    val (set, index) = lastBrush ?: return
    val now = currentBrush
    if (s.selectBrush(set, index)) {
        lastBrush = now?.let { it.set to it.index }
        currentBrush = s.currentBrush()
        readSliders()
        keepChoices(s)
    }
}

/** Keeps the tools' brushes, the sets' choices and each brush's sliders for the next session. */
internal fun CanvasViewModel.keepChoices(s: CanvasSession) {
    viewModelScope.launch(Dispatchers.IO) {
        // (begun, it ends even if the canvas goes meanwhile)
        val kept = withContext(NonCancellable) {
            brushLibraryWrites.withLock { runCatching { s.keepBrushChoices(brushesDir.path) }.getOrDefault(false) }
        }
        libraryKept(kept)
    }
}

/** Tells, once until the library is kept again, that it couldn't be (the storage full, say). */
internal suspend fun CanvasViewModel.libraryKept(kept: Boolean) = withContext(Dispatchers.Main) {
    if (kept) {
        libraryFailing = false
    } else if (!libraryFailing) {
        libraryFailing = true
        message = "The brush library couldn't be kept. The storage may be full."
    }
}

/**
 * Whether [b] shows highlighted: the brush in use alone. A set opened makes the brush last
 * chosen in it the one in use (see [showSet]), so another set's choice never shows chosen.
 */
fun CanvasViewModel.highlighted(b: BrushInfo): Boolean = isBrushInUse(b, currentBrush)

