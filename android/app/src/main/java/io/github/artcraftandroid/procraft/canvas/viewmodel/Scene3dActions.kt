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

fun CanvasViewModel.changeModelFlat(on: Boolean) {
    val s = session ?: return
    s.setModelFlat(on)
    modelFlat = s.modelFlat()
}

fun CanvasViewModel.openLightingStudio() {
    val s = session ?: return
    if (!modelOpen || modelFlat) return
    closePanels()
    modelLights = s.modelLights()
    studioKept = StudioKept(modelEnvironment, modelExposure, modelEnvironmentShown, modelLights)
    s.setLightingStudio(true)
    lightingStudioOpen = true
}

/** Leaves the studio, keeping what was changed or ([keep] false) putting the lighting back. */
fun CanvasViewModel.closeLightingStudio(keep: Boolean) {
    val s = session
    if (!keep) studioKept?.let { kept ->
        if (kept.environment != modelEnvironment) chooseEnvironment(kept.environment)
        changeExposure(kept.exposure)
        changeEnvironmentShown(kept.shown)
        s?.setModelLights(kept.lights)
    }
    s?.setLightingStudio(false)
    modelLights = s?.modelLights() ?: emptyList()
    studioKept = null
    lightingStudioOpen = false
}

/** Adds a light above the model; at the most there can be, says so instead. */
fun CanvasViewModel.addLight() {
    val s = session ?: return
    if (!s.addModelLight()) message = "A model can have four lights at most."
    modelLights = s.modelLights()
}

fun CanvasViewModel.duplicateLight(index: Int) {
    val s = session ?: return
    if (!s.duplicateModelLight(index.toUInt())) message = "A model can have four lights at most."
    modelLights = s.modelLights()
}

fun CanvasViewModel.deleteLight(index: Int) {
    val s = session ?: return
    s.deleteModelLight(index.toUInt())
    modelLights = s.modelLights()
}

/** Light [index] changed to [light] (its colour or intensity). */
fun CanvasViewModel.changeLight(index: Int, light: io.github.artcraftandroid.procraft.core.ModelLight) {
    val s = session ?: return
    s.setModelLight(index.toUInt(), light)
    modelLights = s.modelLights()
}

/** The light whose cube is under window point ([x], [y]) in the studio, if one is. */
fun CanvasViewModel.lightAt(x: Float, y: Float): Int? = session?.modelLightAt(x, y)?.toInt()

/** Light [index] in the studio: its middle on the window and how far its cube reaches (pixels). */
fun CanvasViewModel.lightOnScreen(index: Int): List<Float>? = session?.modelLightOnScreen(index.toUInt())

fun CanvasViewModel.moveLight(index: Int, dx: Float, dy: Float) {
    val s = session ?: return
    s.moveModelLight(index.toUInt(), dx, dy)
    modelLights = s.modelLights()
}

/** A drag across the studio: its camera goes round the model and up or down. */
fun CanvasViewModel.studioTurn(dx: Float, dy: Float) {
    session?.studioTurn(dx, dy)
}

fun CanvasViewModel.studioZoom(scale: Float) {
    session?.studioZoom(scale)
}

/** Lights the model by the environment [name] (an asset in environments/), read off the main thread. */
fun CanvasViewModel.chooseEnvironment(name: String) {
    val s = session ?: return
    modelEnvironment = name
    viewModelScope.launch {
        val assets = getApplication<Application>().assets
        val hdr = withContext(Dispatchers.IO) { runCatching { assets.open("environments/$name.hdr").use { it.readBytes() } }.getOrNull() }
        if (hdr != null && session === s && modelEnvironment == name) s.setModelEnvironment(name, hdr)
    }
}

fun CanvasViewModel.changeExposure(exposure: Float) {
    val s = session ?: return
    s.setModelExposure(exposure)
    modelExposure = s.modelExposure()
}

fun CanvasViewModel.changeEnvironmentShown(shown: Boolean) {
    val s = session ?: return
    s.setModelEnvironmentShown(shown)
    modelEnvironmentShown = s.modelEnvironmentShown()
}

/** One finger across the studio's view: the model turns. */
fun CanvasViewModel.turnModel(dx: Float, dy: Float) {
    session?.turnModel(dx, dy)
}

fun CanvasViewModel.changePaintThrough(on: Boolean) {
    val s = session ?: return
    s.setPaintThrough(on)
    paintThrough = s.paintThrough()
}

/** Opens submesh [index] in the layers panel (its texture set's layers under it), or closes it again. */
fun CanvasViewModel.openSubmesh(index: Int) {
    if (openSubmesh == index) {
        openSubmesh = null
        return
    }
    val m = submeshes.getOrNull(index) ?: return
    openSubmesh = index
    chooseTextureSet(m.textureSet.toInt())
    session?.pickSubmesh(index.toUInt())
    readMeshes()
}

/** Hides submesh [index] (or shows it again), saying which. */
fun CanvasViewModel.toggleSubmeshHidden(index: Int) {
    val s = session ?: return
    val hide = submeshesHidden.getOrNull(index) != true
    s.setSubmeshHidden(index.toUInt(), hide)
    readMeshes()
    showDone(if (hide) "Hide Submesh" else "Show Submesh")
}

/** Locks submesh [index] (or unlocks it), saying which. */
fun CanvasViewModel.toggleSubmeshLocked(index: Int) {
    val s = session ?: return
    val lock = submeshesLocked.getOrNull(index) != true
    s.setSubmeshLocked(index.toUInt(), lock)
    readMeshes()
    showDone(if (lock) "Lock mesh" else "Unlock mesh")
}

/** Adds submesh [index] to those picked, or takes it away; meshes picked share a texture set. */
fun CanvasViewModel.pickMoreSubmeshes(index: Int) {
    val s = session ?: return
    if (s.pickMoreSubmeshes(index.toUInt())) readMeshes()
    else showNotice("Those meshes aren't in the same texture set", warning = true)
}

/** What is hidden, locked and picked, as the core has it (after undo and redo too). */
internal fun CanvasViewModel.readMeshes() {
    val s = session ?: return
    submeshesHidden = s.submeshesHidden()
    submeshesLocked = s.submeshesLocked()
    activeSubmeshes = s.activeSubmeshes().map { it.toInt() }
}

/** The name a dialog about the meshes strokes are meant for gives them. */
fun CanvasViewModel.meantMeshName(): String = session?.meantMeshName().orEmpty()

fun CanvasViewModel.toggleSelectionXRay() {
    selectionXRay = !selectionXRay
    session?.setSelectionXRay(selectionXRay)
    interfacePrefs.edit().putBoolean(SELECTION_X_RAY, selectionXRay).apply()
}

/** Deletes [l], asking first when several meshes share it. */
fun CanvasViewModel.deleteAsking(l: LayerInfo) {
    val shared = modelOpen && submeshes.count { it.textureSet.toInt() == textureSet } > 1
    if (shared) deletingShared = l else delete(l)
}

/** Draws each submesh's picture again, as the model is seen now. */
fun CanvasViewModel.refreshSubmeshThumbnails() {
    val s = session ?: return
    for (i in submeshes.indices) {
        viewModelScope.launch {
            val image = withContext(Dispatchers.Default) {
                val t = s.submeshThumbnail(i.toUInt(), THUMB_SIDE.toUInt()) ?: return@withContext null
                val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
                bitmap.asImageBitmap()
            }
            if (image != null && session === s) submeshThumbnails[i] = image
        }
    }
}

/** Chooses a layer's channel tile: [channel] painted from now on (an undo step). */
fun CanvasViewModel.chooseChannelTile(channel: Int) {
    val s = session ?: return
    if (channel !in 0..2) return
    s.chooseChannelTile(channel.toUInt())
    followModelChannel()
}

/** Layer [l]'s content in [channel] replaced by the picture at [uri], stretched over the texture. */
fun CanvasViewModel.replaceMaterialMap(l: LayerInfo, channel: Int, uri: android.net.Uri) {
    val s = session ?: return
    viewModelScope.launch {
        val image = withContext(Dispatchers.IO) { decodePicture(uri, io.github.artcraftandroid.procraft.ui.CanvasSpace.p3) }
        if (image == null) {
            message = "This picture can't be opened."
            return@launch
        }
        if (s.replaceMaterialMap(l.id, channel.toUInt(), image)) showDone("Replace Material Map Content")
        followModelChannel()
        loadThumbnail(l.id)
        refreshChannelThumbnails(l.id)
        refreshSubmeshThumbnails()
        refresh()
    }
}

/** A layer's row tapped on a model: the layer itself (its colour) is painted, no tile chosen (an undo step). */
fun CanvasViewModel.leaveChannelTiles() {
    val s = session ?: return
    s.leaveChannelTiles()
    followModelChannel()
}

/**
 * Paints on [channel] of the texture set from now on (its layers stay the layers), with that
 * channel's own colour: roughness and metalness start black.
 */
fun CanvasViewModel.chooseChannel(channel: Int) {
    val s = session ?: return
    if (channel == modelChannel || channel !in 0..2) return
    s.setModelChannel(channel.toUInt())
    followModelChannel()
}

/** The channel and tile as the core has them now (a tile chosen, or its choice undone or redone), the channel's own colour shown. */
internal fun CanvasViewModel.followModelChannel() {
    val s = session ?: return
    channelTileChosen = s.channelTileChosen()
    val now = s.modelChannel().toInt()
    if (now == modelChannel) return
    channelColors[modelChannel] = floatArrayOf(hue, saturation, brightness)
    modelChannel = now
    val c = channelColors[now] ?: if (now == 0) null else floatArrayOf(0f, 0f, 0f)
    c?.let { setColor(it[0], it[1], it[2]) }
    refresh()
    refreshThumbnails()
}

/** Shows layer [id]'s channels under it (or hides them), drawn afresh. */
fun CanvasViewModel.toggleChannelTray(id: ULong) {
    channelTrayFor = if (channelTrayFor == id) null else id
    if (channelTrayFor == id) refreshChannelThumbnails(id)
}

/** What undo or redo changed on a model, in the pictures the open Layers panel shows of it. */
internal fun CanvasViewModel.refreshModelThumbnails() {
    if (!modelOpen || !layersShown) return
    channelTrayFor?.let { refreshChannelThumbnails(it) }
    refreshSubmeshThumbnails()
}

internal fun CanvasViewModel.refreshChannelThumbnails(id: ULong) {
    val s = session ?: return
    for (c in 0..2) {
        viewModelScope.launch {
            val image = withContext(Dispatchers.Default) {
                val t = s.channelLayerThumbnail(c.toUInt(), id, THUMB_SIDE.toUInt()) ?: return@withContext null
                val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
                bitmap.asImageBitmap()
            }
            if (image != null && session === s) channelThumbnails[c to id] = image
        }
    }
}

/** Paints on texture set [index] from now on: the layers become its layers. */
fun CanvasViewModel.chooseTextureSet(index: Int) {
    val s = session ?: return
    if (index == textureSet || index !in textureSets.indices) return
    // a texture set is opened on its colour, no tile chosen
    chooseChannel(0)
    channelTrayFor = null
    s.chooseTextureSet(index.toUInt())
    channelTileChosen = s.channelTileChosen()
    textureSet = index
    refresh()
    refreshThumbnails()
}

/**
 * A new artwork painting on the model in the file [uri] (named [name]: OBJ, USDZ or USDC, or
 * a zip holding one with its files), with the files picked along with it ([picked], by name:
 * an OBJ's material file and images). [bytes] are the file's, if already read; the artwork
 * is named [title], else after the file.
 */
internal suspend fun CanvasViewModel.newFromModel(
    uri: android.net.Uri,
    name: String,
    picked: List<Pair<android.net.Uri, String>> = emptyList(),
    bytes: ByteArray? = null,
    title: String? = null,
) {
    val resolver = getApplication<Application>().contentResolver
    var why: String? = null
    val s = withContext(Dispatchers.IO) {
        runCatching {
            val file = bytes ?: resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            val files = picked.mapNotNull { (u, n) ->
                runCatching { resolver.openInputStream(u)?.use { ModelFile(n, it.readBytes()) } }.getOrNull()
            }
            val artwork = (title ?: name.substringBeforeLast('.')).ifBlank { "Untitled Artwork" }
            CanvasSession.openModel(file, name, files, artwork, 0u)
                .also(this::loadModelTextures).also(this::loadBrushes)
        }.onFailure {
            AppLog.e("ProCraft", "opening a model", it)
            why = (it as? io.github.artcraftandroid.procraft.core.MobileException.File)?.reason?.substringAfter(": ", "")
        }.getOrNull()
    }
    if (s == null) {
        message = why?.takeIf { it.isNotEmpty() }?.let { "This model can't be opened: $it." } ?: "This model can't be opened."
        return
    }
    start(s, store.newFolder(galleryDir), brushesLoaded = true)
    startedNew()
}

/**
 * The images a model's file brings (a USDZ's), decoded and scaled to its texture sets' side
 * into their Base Layers by the core: colour as it is, roughness and metalness from the
 * component they name, as grey. An image that can't be read is left out.
 */
internal fun CanvasViewModel.loadModelTextures(s: CanvasSession) {
    s.loadModelTextures()
}

