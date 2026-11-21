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
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.BRUSH_CURSOR
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.BRUSH_ON_SCREEN
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.COLOR_HISTORY_SHOWN
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.COLOUR_MODEL
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CURSOR_FILL_FAINTEST
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CURSOR_FILL_MOST
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CURSOR_REREAD_MS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CURSOR_SHOWS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.CURSOR_STYLE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.INPUT_CURVE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.INPUT_EXPRESSION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.INPUT_MOTION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.INPUT_PRESSURE_SMOOTHING
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.INPUT_STABILIZATION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.INPUT_TIP_ATTACHED
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.KEPT_BRIGHTNESS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.KEPT_HUE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.KEPT_MASK_BRIGHTNESS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.KEPT_MASK_HUE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.KEPT_MASK_SATURATION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.KEPT_SATURATION
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.LIGHT_INTERFACE
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.PALETTE_CARDS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.PROJECT_CANVAS
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.RAPID_UNDO_DELAY
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.RAPID_UNDO_MAX_S
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.RAPID_UNDO_MIN_S
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.RIGHT_HANDED
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.SELECTION_VISIBILITY
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel.Companion.WRONG_BUILD_SAID

/**
 * What a save came to, told when it failed (the first time since one last succeeded: autosave
 * would say it again each time); false (nothing saved, nothing wrong) says nothing.
 */
internal suspend fun CanvasViewModel.told(result: Result<Boolean>) {
    val e = result.exceptionOrNull()
    withContext(Dispatchers.Main) {
        when {
            e == null -> if (result.getOrDefault(false)) saveFailing = false
            !saveFailing -> {
                saveFailing = true
                message = io.github.artcraftandroid.procraft.core.saveFailureSaid(e.message.orEmpty())
            }
        }
    }
}

internal fun CanvasViewModel.pushSecondary() {
    val c = secondaryState
    session?.setSecondaryColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
}

/** Keeps the brush colour and the masks' for the next session. */
internal fun CanvasViewModel.keepColour() {
    val now = floatArrayOf(hue, saturation, brightness)
    val (brush, mask) = if (onMask) otherColour to now else now to otherColour
    interfacePrefs.edit()
        .putFloat(KEPT_HUE, brush[0]).putFloat(KEPT_SATURATION, brush[1]).putFloat(KEPT_BRIGHTNESS, brush[2])
        .putFloat(KEPT_MASK_HUE, mask[0]).putFloat(KEPT_MASK_SATURATION, mask[1]).putFloat(KEPT_MASK_BRIGHTNESS, mask[2])
        .apply()
}

/**
 * A mask became current, or stopped being: the colour trades places with the other kind's, as
 * masks paint with a colour of their own.
 */
internal fun CanvasViewModel.followMaskColour() {
    val mask = layers.firstOrNull { it.current }?.kind == LayerType.MASK
    if (mask == onMask) return
    onMask = mask
    val now = floatArrayOf(hue, saturation, brightness)
    setColor(otherColour[0], otherColour[1], otherColour[2])
    otherColour = now
}

fun CanvasViewModel.changeColourModel(model: ColourModel) {
    colourModel = model
    interfacePrefs.edit().putString(COLOUR_MODEL, colourModelKey(model)).apply()
}

fun CanvasViewModel.changeBrushCursor(on: Boolean) {
    brushCursorOn = on
    if (!on) cursorAt = null
    interfacePrefs.edit().putBoolean(BRUSH_CURSOR, on).apply()
}

fun CanvasViewModel.changeCursorShows(v: CursorShows) {
    cursorShows = v
    interfacePrefs.edit().putInt(CURSOR_SHOWS, v.ordinal).apply()
}

fun CanvasViewModel.changeCursorStyle(v: CursorStyle) {
    cursorStyle = v
    interfacePrefs.edit().putInt(CURSOR_STYLE, v.ordinal).apply()
}

fun CanvasViewModel.changePaletteCards(on: Boolean) {
    paletteCards = on
    interfacePrefs.edit().putBoolean(PALETTE_CARDS, on).apply()
}

fun CanvasViewModel.changeColorHistoryShown(on: Boolean) {
    colorHistoryShown = on
    interfacePrefs.edit().putBoolean(COLOR_HISTORY_SHOWN, on).apply()
}

fun CanvasViewModel.changeProjectCanvas(on: Boolean) {
    projectCanvas = on
    interfacePrefs.edit().putBoolean(PROJECT_CANVAS, on).apply()
}

/** The second display's window came ([surface], [width] × [height]) or went (null). */
fun CanvasViewModel.setProjectionSurface(surface: android.view.Surface?, width: Int = 0, height: Int = 0) {
    val same = surface != null && projection?.first == surface
    projection = surface?.let { Triple(it, width, height) }
    if (same) session?.resizeProjection(width.toUInt(), height.toUInt()) else attachProjection()
}

/** The open artwork shows on the second display, if there is one. */
internal fun CanvasViewModel.attachProjection() {
    val s = session ?: return
    val (surface, w, h) = projection ?: return s.detachProjection()
    val id = NativeWindows.acquire(surface)
    if (id != 0L) runCatching { s.attachProjection(id.toULong(), w.toUInt(), h.toUInt()) }
}

fun CanvasViewModel.changeBrushScaling(onScreen: Boolean) {
    brushOnScreen = onScreen
    session?.setBrushScaling(onScreen)
    interfacePrefs.edit().putBoolean(BRUSH_ON_SCREEN, onScreen).apply()
}

/**
 * Shows the brush cursor at window point [p] (or hides it), sized for the current brush and
 * zoom, as long as it shows when [hovering] (or painting).
 */
fun CanvasViewModel.showCursor(p: Offset?, hovering: Boolean = false) {
    val wanted = if (hovering) cursorShows != CursorShows.PAINTING else cursorShows != CursorShows.HOVERING
    if (!brushCursorOn || p == null || !wanted) {
        cursorAt = null
        return
    }
    // sized as it shows, and while hovering again now and then: the size may have changed
    // meanwhile (painting, it keeps the size the stroke began at)
    val now = android.os.SystemClock.uptimeMillis()
    if (cursorAt == null || (hovering && now - cursorReadAt >= CURSOR_REREAD_MS)) session?.let { s ->
        cursorRadius = s.info().let { it.brushDiameter * it.zoom / 2f }
        cursorOutline = s.currentBrushSetting("cursor_outline").roundToInt()
        // a shape or a fill that can't be read leaves the round tip's circle, in lines: the
        // cursor still shows
        cursorShape = runCatching { s.brushOutline() }.getOrDefault(emptyList())
        // turned with the canvas, as its stamps are (unless the brush keeps to the screen)
        cursorTurn = if (s.currentBrushSetting("orient_to_screen") < 0.5f) s.viewMatrix().let { m -> kotlin.math.atan2(m[1], m[0]) } else 0f
        val look = if (hovering) runCatching { s.hoverFill() }.getOrNull() else null
        cursorFill = look?.let { runCatching { fillStamp(s) }.getOrNull() }
        look?.let { cursorFillLook = it }
        cursorReadAt = now
    }
    cursorHovering = hovering
    cursorAt = p
}

/**
 * The brush's stamp at the cursor's size on the screen, white with its coverage as alpha; made
 * again only when it changes. None for a stamp too faint to see there: the outline shows instead.
 */
internal fun CanvasViewModel.fillStamp(s: CanvasSession): androidx.compose.ui.graphics.ImageBitmap? {
    val n = (cursorRadius * 2f).roundToInt().coerceIn(2, CURSOR_FILL_MOST)
    val alpha = s.brushStamp(n.toUInt())
    if (alpha.size < n * n || alpha.none { (it.toInt() and 0xFF) >= CURSOR_FILL_FAINTEST }) return null
    cursorFillMade?.let { (side, made) -> if (side == n && made.contentEquals(alpha)) return cursorFill }
    cursorFillMade = n to alpha
    val colors = IntArray(n * n) { i -> (alpha[i].toInt() and 0xFF shl 24) or 0xFFFFFF }
    return android.graphics.Bitmap.createBitmap(colors, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Shows Liquify's ring at window point [p] (none hides it), sized for its brush and the zoom. */
fun CanvasViewModel.showLiquifyRing(p: Offset?) {
    val s = session
    if (p == null || s == null || adjustment != Adjustment.LIQUIFY) {
        liquifyRingAt = null
        return
    }
    liquifyRingRadius = io.github.artcraftandroid.procraft.core.liquifyReach(liquifySize) * s.info().zoom
    liquifyRingAt = p
}

fun CanvasViewModel.changeLightInterface(on: Boolean) {
    lightInterface = on
    io.github.artcraftandroid.procraft.ui.Pc.light = on
    session?.setLightInterface(on)
    interfacePrefs.edit().putBoolean(LIGHT_INTERFACE, on).apply()
}

/** What the plugins change in how the interface looks: its accent, Classic's picker and menu, the floating colours and panels, the notices; and whether strokes are heard. */
internal fun CanvasViewModel.followPluginLooks() {
    io.github.artcraftandroid.procraft.ui.Pc.accentGiven = io.github.artcraftandroid.procraft.ui.Pc.accentOf(plugins)
    io.github.artcraftandroid.procraft.ui.Pc.colourTriangle = plugins.colourTriangle()
    io.github.artcraftandroid.procraft.ui.Pc.picker = if (plugins.pickerOn()) {
        io.github.artcraftandroid.procraft.ui.ClassicPicker(
            pick = { x, y -> plugins.pickerPick(x, y) },
            point = { s, v -> plugins.pickerPoint(s, v) },
            picture = { hue, n -> session?.pickerPicture(hue, n.toUInt()) },
            hue = { x, y -> plugins.pickerHue(x, y) },
            huePoint = { h -> plugins.pickerHuePoint(h) },
        )
    } else {
        null
    }
    io.github.artcraftandroid.procraft.ui.Pc.partScales = io.github.artcraftandroid.procraft.core.InterfacePart.entries.map { plugins.interfacePartScale(it) }
    io.github.artcraftandroid.procraft.ui.Pc.floatingPickerOnly = plugins.floatingPickerOnly()
    val choices = plugins.menuChoices(io.github.artcraftandroid.procraft.core.PluginMenuPlace.CLASSIC)
    io.github.artcraftandroid.procraft.ui.Pc.classicMenu = choices.takeIf { it.isNotEmpty() }?.let { io.github.artcraftandroid.procraft.ui.ClassicMenu(it, this::choosePluginChoice) }
    io.github.artcraftandroid.procraft.ui.Pc.floatingPanels = io.github.artcraftandroid.procraft.core.FloatingPanel.entries.filter { plugins.floats(it) }.toSet()
    io.github.artcraftandroid.procraft.ui.Pc.noticesShown = io.github.artcraftandroid.procraft.core.NoticeKind.entries.filter { plugins.showsNotice(it) }.toSet()
    tweensOn = plugins.tweens()
    pluginLooks++
    referenceWindowsAllowed = plugins.referenceWindows().toInt().coerceAtLeast(1)
    followStrokeSound()
}

/**
 * What the library's sample strokes are tinted with as a plugin colours them by the colour now:
 * each brush's, then the brush in use's (on the accent); none, as the interface draws them.
 */
fun CanvasViewModel.previewTints(): Pair<androidx.compose.ui.graphics.Color?, androidx.compose.ui.graphics.Color?> {
    val s = session ?: return null to null
    val tint = { chosen: Boolean -> s.brushPreviewColour(chosen)?.let { androidx.compose.ui.graphics.Color(0xFF000000L or it.toLong()) } }
    return tint(false) to tint(true)
}

/** Plays the open artwork's strokes while a plugin makes their sound and the app is in front. */
internal fun CanvasViewModel.followStrokeSound() {
    // set up before the session is, the first time
    @Suppress("SENSELESS_COMPARISON")
    if (strokeSound == null) return
    strokeSoundOn = plugins.makesStrokeSound()
    strokeSound.follow(strokeSoundHeard(session, inFront, screen == Screen.CANVAS), strokeSoundOn)
}

/** The app came to the front ([front]) or went behind: everything that runs by itself follows. */
fun CanvasViewModel.appInFront(front: Boolean) {
    if (front == inFront) return
    inFront = front
    followStrokeSound()
    session?.setShown(front)
    pad?.setShown(front)
    if (front) {
        failureWatch.resume()
    } else {
        failureWatch.pause()
        stopPlaying()
    }
    logActivity(front)
}

/** A touch landed on the canvas: the strokes' sound wakes if it rests. */
fun CanvasViewModel.canvasTouched() = strokeSound.wake()

/**
 * Puts in the log what the open artwork's session did by itself while the app was in front
 * (now going away, not [front]) or away (now back): frames drawn, the render thread's wakeups
 * and retries, the autosave's looks. Away, all should be none.
 */
internal fun CanvasViewModel.logActivity(front: Boolean) {
    val s = session ?: run { activityLogged = null; return }
    val now = android.os.SystemClock.elapsedRealtime() to runCatching { s.activity() }.getOrNull()
    val (at, before) = activityLogged ?: (now.first to null)
    val after = now.second
    if (before != null && after != null) {
        val seconds = (now.first - at) / 1000
        AppLog.i(
            "activity",
            "${if (front) "away" else "in front"} $seconds s: ${after.frames - before.frames} frames drawn, " +
                "${after.wakeups - before.wakeups} render wakeups, ${after.retries - before.retries} retries, " +
                "${after.autosaveLooks - before.autosaveLooks} autosave looks",
        )
    }
    activityLogged = after?.let { now.first to it }
}

/** Turns a plugin on or off: it goes on or off its hooks at once, and its values wait for it. */
fun CanvasViewModel.setPluginOn(plugin: io.github.artcraftandroid.procraft.core.PluginInfo, on: Boolean) {
    if (!plugins.setOn(plugin.id, on)) return
    interfacePrefs.edit().putBoolean(plugin.onKey, on).apply()
    pluginsChanged()
}

fun CanvasViewModel.movedNoticeRead() {
    movedNotice = null
}

fun CanvasViewModel.wrongBuildNoticeRead() {
    wrongBuildNotice = false
    interfacePrefs.edit().putString(WRONG_BUILD_SAID, appVersion()).apply()
}

internal fun CanvasViewModel.appVersion(): String =
    getApplication<Application>().let { a -> runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() } ?: ""

/** Whether [uri] is a plugin file, by its name. */
fun CanvasViewModel.isPluginFile(uri: android.net.Uri) = fileName(uri)?.let { it.endsWith(".procraft-plugin", ignoreCase = true) || it.endsWith(".procraftplugin", ignoreCase = true) } == true

/** Asks about the plugin file at [uri]: nothing in it runs, and nothing is kept until it is accepted. What can't be imported says why. */
fun CanvasViewModel.offerPlugin(uri: android.net.Uri) {
    viewModelScope.launch {
        val most = io.github.artcraftandroid.procraft.core.pluginFileMostBytes().toLong() + 1
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    // no further than a file that can be taken, whatever it is
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (out.size() < most) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        out.write(chunk, 0, read)
                    }
                    out.toByteArray()
                }
            }.getOrNull()
        }
        if (bytes == null) {
            message = "This file can't be read."
            return@launch
        }
        val offer = plugins.inspect(bytes)
        if (offer.refused != null) {
            message = offer.refused
            return@launch
        }
        offeredPlugin = bytes
        pluginOffer = offer
    }
}

fun CanvasViewModel.declinePlugin() {
    offeredPlugin = null
    pluginOffer = null
}

/** Imports the file asked about; it shows at once. */
fun CanvasViewModel.acceptPlugin() {
    val bytes = offeredPlugin
    declinePlugin()
    message = bytes?.let(plugins::install)
    pluginsChanged()
}

/** Moves plugin [id] to place [to] on the Plugins page, the order kept for the next start; said once if it couldn't be. */
fun CanvasViewModel.movePlugin(id: String, to: Int) {
    plugins.movePlugin(id, to.coerceAtLeast(0).toUInt())?.let { message = it }
    pluginList = plugins.plugins()
}

/** Removes an imported plugin, its folder and its values (the page asks first); it goes at once. */
fun CanvasViewModel.removePlugin(plugin: io.github.artcraftandroid.procraft.core.PluginInfo) {
    val forget = plugins.remove(plugin.id)
    if (forget == null) {
        message = "This plugin couldn't be removed."
        return
    }
    val edit = interfacePrefs.edit()
    forget.forEach(edit::remove)
    edit.apply()
    pluginsChanged()
}

/** A plugin's action [key] run on the artwork, off the main thread (it may take seconds): the panel closes so what it does shows, and what it says, or its stopping, is told. */
fun CanvasViewModel.runPluginAction(key: String) {
    val s = session ?: return
    closePanels()
    viewModelScope.launch {
        withContext(Dispatchers.Default) { plugins.runAction(key, s) }
        pluginPanel = plugins.panel()
        s.takeFailure()?.let { showNotice(it) }
        readPlugins()
        if (s.takeBrushesChanged()) libraryChanged()
        refresh()
        // an action may turn the view, settling into place
        followView()
    }
}

/** Row [row] of the plugin's panel touched with [value]: told to the plugin off the main thread, and the panel shown as it answers (or closed). */
fun CanvasViewModel.touchPluginPanel(row: Int, value: Float) {
    val s = session ?: return
    viewModelScope.launch {
        withContext(Dispatchers.Default) { plugins.panelTouched(row.toUInt(), value, s) }
        pluginPanel = plugins.panel()
        s.takeFailure()?.let { showNotice(it) }
        readPlugins()
        // a plugin's studio changed a brush: read again and kept
        if (s.takeBrushesChanged()) libraryChanged()
        refresh()
    }
}

fun CanvasViewModel.closePluginPanel() {
    plugins.closePanel()
    pluginPanel = null
}

/** A plugin's switch flipped; a key no control has changes nothing. */
fun CanvasViewModel.setPluginSwitch(key: String, on: Boolean) {
    if (!plugins.setSwitch(key, on)) return
    interfacePrefs.edit().putBoolean(key, on).apply()
    pluginsChanged()
}

/** A choice picked from a plugin's menu by its [key]: the controls it set kept as the card keeps them; one no plugin on offers changes nothing. */
fun CanvasViewModel.choosePluginChoice(key: String) {
    val changed = plugins.choose(key)
    if (changed.isEmpty()) return
    val edit = interfacePrefs.edit()
    for (c in changed) {
        when (c) {
            is io.github.artcraftandroid.procraft.core.PluginControl.Switch -> edit.putBoolean(c.control.key, c.control.on)
            is io.github.artcraftandroid.procraft.core.PluginControl.Slider ->
                if (io.github.artcraftandroid.procraft.core.pluginSliderWhole(c.control)) edit.putInt(c.control.key, c.control.value.roundToInt()) else edit.putFloat(c.control.key, c.control.value)
            is io.github.artcraftandroid.procraft.core.PluginControl.File -> Unit
        }
    }
    edit.apply()
    pluginsChanged()
}

/** A plugin's slider let go at [value]; a key no control has changes nothing. A count stays kept as a whole number. */
fun CanvasViewModel.setPluginSlider(slider: io.github.artcraftandroid.procraft.core.PluginSlider, value: Float) {
    val landed = plugins.setSlider(slider.key, value) ?: return
    val edit = interfacePrefs.edit()
    if (io.github.artcraftandroid.procraft.core.pluginSliderWhole(slider)) edit.putInt(slider.key, landed.roundToInt()) else edit.putFloat(slider.key, landed)
    edit.apply()
    pluginsChanged()
}

/** A sound picked at [uri] for a plugin's file control [key]: read in the background (any sound Android can play, a minute of it at most), then kept with the plugin; told if it can't be used. */
fun CanvasViewModel.pickPluginSound(key: String, uri: android.net.Uri) {
    viewModelScope.launch {
        val kept = withContext(Dispatchers.IO) {
            val wav = decodedSound(getApplication(), uri) ?: return@withContext false
            plugins.setSoundFile(key, fileName(uri) ?: "Recording", wav)
        }
        if (kept) pluginsChanged() else message = "This sound couldn't be used."
    }
}

/** A plugin's file control [key] lets its file go. */
fun CanvasViewModel.clearPluginFile(key: String) {
    if (plugins.clearFile(key)) pluginsChanged()
}

/** The list as it stands: a plugin may have stopped on a stroke since it was read. */
fun CanvasViewModel.readPlugins() {
    pluginList = plugins.plugins()
    followPluginLooks()
}

/** What the plugins do now; one nothing answers to does nothing. */
internal fun CanvasViewModel.pluginsChanged() {
    readPlugins()
    session?.let(this::applyPlugins)
    // the bars as they show now
    applyFitInsets()
}

/** [s] goes by the plugins that reach a canvas. */
internal fun CanvasViewModel.applyPlugins(s: CanvasSession) {
    s.usePlugins(plugins)
}

/** The interface shows at [size] now: what hit-tests its handles, and what clears its bars, go with it. */
fun CanvasViewModel.interfaceShownAt(size: Float) {
    if (size == interfaceShown) return
    interfaceShown = size
    session?.setInterfaceSize(size)
    applyFitInsets()
}

fun CanvasViewModel.changeRapidUndoDelay(seconds: Float) {
    rapidUndoDelay = seconds.coerceIn(RAPID_UNDO_MIN_S, RAPID_UNDO_MAX_S)
    interfacePrefs.edit().putFloat(RAPID_UNDO_DELAY, rapidUndoDelay).apply()
}

fun CanvasViewModel.changeSelectionVisibility(v: Float) {
    selectionVisibility = v.coerceIn(0f, 1f)
    session?.setSelectionVisibility(selectionVisibility)
    interfacePrefs.edit().putFloat(SELECTION_VISIBILITY, selectionVisibility).apply()
}

fun CanvasViewModel.changeInput(
    stabilization: Float = inputStabilization,
    motion: Float = inputMotionFiltering,
    expression: Float = inputMotionExpression,
    smoothing: Float = inputPressureSmoothing,
    curve: List<Offset> = inputCurve,
    tipAttached: Boolean = inputTipAttached,
) {
    inputStabilization = stabilization
    inputMotionFiltering = motion
    inputMotionExpression = expression
    inputPressureSmoothing = smoothing
    inputCurve = curve
    inputTipAttached = tipAttached
    interfacePrefs.edit()
        .putFloat(INPUT_STABILIZATION, stabilization).putFloat(INPUT_MOTION, motion)
        .putFloat(INPUT_EXPRESSION, expression).putFloat(INPUT_PRESSURE_SMOOTHING, smoothing)
        .putString(INPUT_CURVE, curve.joinToString(",") { "${it.x},${it.y}" })
        .putBoolean(INPUT_TIP_ATTACHED, tipAttached)
        .apply()
    applyInput()
}

fun CanvasViewModel.resetInput() = changeInput(0f, 0f, 0f, 0f, listOf(Offset(0f, 0f), Offset(1f, 1f)), true)

internal fun CanvasViewModel.applyInput() {
    session?.setInputSettings(inputStabilization, inputMotionFiltering, inputMotionExpression, inputPressureSmoothing, inputTipAttached, inputCurve.flatMap { listOf(it.x, it.y) })
}

fun CanvasViewModel.changeRightHanded(on: Boolean) {
    rightHanded = on
    interfacePrefs.edit().putBoolean(RIGHT_HANDED, on).apply()
}

fun CanvasViewModel.toggleSideBarCollapsed() {
    sideBarCollapsed = !sideBarCollapsed
}

internal fun CanvasViewModel.readGradients(): List<GradientPreset> = gradientLibrary.list().map { g ->
    GradientPreset(g.name, g.stops.map { it.position to (0xFF000000 or it.rgb.toLong()) })
}

/** How the colour panel and swatches show the canvas's colours: its space, its press. */
internal fun CanvasViewModel.refreshCanvasSpace(s: CanvasSession) {
    // a canvas put in another profile may go into wide colour or out of it
    refreshWideColor(s)
    val name = s.profile()
    val space = io.github.artcraftandroid.procraft.ui.CanvasSpace
    val p3 = io.github.artcraftandroid.procraft.gallery.CanvasPreset.P3
    when {
        s.isCmyk() -> {
            space.set(true) { s.proofColor(it.toUInt()).toInt() }
            inks = io.github.artcraftandroid.procraft.ui.Inks(
                { rgb -> s.cmykOf(rgb.toUInt()).toFloatArray().takeIf { it.size == 4 } ?: FloatArray(4) },
                { v -> s.rgbOfCmyk(v.toList()).toInt() },
            )
            return
        }
        io.github.artcraftandroid.procraft.core.profileIsBuiltIn(name) -> space.set(name == p3)
        else -> space.set(true) { io.github.artcraftandroid.procraft.core.convertColor(it.toUInt(), name, p3).toInt() }
    }
    inks = null
}

/** Adds the colour profile file at [uri] to those a canvas can be in; its name, or null if it isn't one. */
suspend fun CanvasViewModel.importProfile(uri: android.net.Uri): String? {
    val resolver = getApplication<Application>().contentResolver
    val name = withContext(Dispatchers.IO) {
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes() }?.let { io.github.artcraftandroid.procraft.core.importProfile(it) } }.getOrNull()
    }
    if (name == null) message = "This file isn't a color profile."
    return name
}

internal fun CanvasViewModel.readFloatingWindows(): Map<io.github.artcraftandroid.procraft.core.FloatingPanel, io.github.artcraftandroid.procraft.core.FloatingWindow> =
    io.github.artcraftandroid.procraft.core.FloatingPanel.entries.mapNotNull { p ->
        interfacePrefs.getString(io.github.artcraftandroid.procraft.core.floatingKey(p), null)?.let { io.github.artcraftandroid.procraft.core.floatingRead(it) }?.let { p to it }
    }.toMap()

/** [panel]'s window, while it shows: torn off and not closed, and a plugin lets the panel float. */
fun CanvasViewModel.floatingWindow(panel: io.github.artcraftandroid.procraft.core.FloatingPanel): io.github.artcraftandroid.procraft.core.FloatingWindow? =
    floatingWindows[panel]?.takeIf { it.open && panel in io.github.artcraftandroid.procraft.ui.Pc.floatingPanels }

internal fun CanvasViewModel.setFloating(panel: io.github.artcraftandroid.procraft.core.FloatingPanel, window: io.github.artcraftandroid.procraft.core.FloatingWindow) {
    floatingWindows = floatingWindows + (panel to window)
}

/** [panel]'s window as it is now, kept for the next start. */
fun CanvasViewModel.keepFloatingWindow(panel: io.github.artcraftandroid.procraft.core.FloatingPanel) {
    val window = floatingWindows[panel] ?: return
    interfacePrefs.edit().putString(io.github.artcraftandroid.procraft.core.floatingKey(panel), io.github.artcraftandroid.procraft.core.floatingKept(window)).apply()
}

/**
 * [panel]'s grabber dragged out past its start, the finger at ([x], [y]) (dp of the interface): its window follows it,
 * as big as it was last left, its grabber under the finger.
 */
fun CanvasViewModel.undockPanel(panel: io.github.artcraftandroid.procraft.core.FloatingPanel, x: Float, y: Float) {
    panelUndocking = panel
    val k = io.github.artcraftandroid.procraft.ui.Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING)
    val first = io.github.artcraftandroid.procraft.core.floatingAt(panel, x, y - io.github.artcraftandroid.procraft.ui.FloatingGrabY * k, k)
    val sized = floatingWindows[panel]?.let { was -> first.copy(x = x - was.width * k / 2, width = was.width, height = was.height) } ?: first
    setFloating(panel, io.github.artcraftandroid.procraft.core.floatingFitted(panel, sized, floatingRoom, k))
}

/** [panel]'s grabber let go: it floats where it is, kept, and the panel that hung from its button closes. */
fun CanvasViewModel.undockedPanel(panel: io.github.artcraftandroid.procraft.core.FloatingPanel) {
    if (panelUndocking != panel) return
    panelUndocking = null
    keepFloatingWindow(panel)
    closePanels()
    if (panel == io.github.artcraftandroid.procraft.core.FloatingPanel.LAYERS) refreshThumbnails()
}

/** [panel]'s window moved by ([dx], [dy]) (dp of the interface), inside its room. */
fun CanvasViewModel.moveFloating(panel: io.github.artcraftandroid.procraft.core.FloatingPanel, dx: Float, dy: Float) {
    val window = floatingWindows[panel] ?: return
    val k = io.github.artcraftandroid.procraft.ui.Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING)
    setFloating(panel, io.github.artcraftandroid.procraft.core.floatingMoved(panel, window, dx, dy, floatingRoom, k))
}

/** [panel]'s window sized from its corner, dragged by ([dx], [dy]) (dp of the interface), within what it may be. */
fun CanvasViewModel.resizeFloating(panel: io.github.artcraftandroid.procraft.core.FloatingPanel, dx: Float, dy: Float) {
    val window = floatingWindows[panel] ?: return
    val k = io.github.artcraftandroid.procraft.ui.Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING)
    setFloating(panel, io.github.artcraftandroid.procraft.core.floatingResized(panel, window, dx, dy, floatingRoom, k))
}

/** [panel]'s window closed by its Close: where it was and its size kept for when it floats again. */
fun CanvasViewModel.closeFloating(panel: io.github.artcraftandroid.procraft.core.FloatingPanel) {
    val window = floatingWindows[panel] ?: return
    setFloating(panel, window.copy(open = false))
    keepFloatingWindow(panel)
}

/**
 * Puts [copy] (premultiplied, as kept here) on the system's clipboard in place of what it held: a
 * PNG other apps paste, its transparency kept, from the app's files. Made off the main thread, as
 * a big canvas takes a moment. Whatever goes wrong on the way (a picture too big to make, a
 * system that refuses it) costs only other apps' paste, told once: the copy stays here to paste.
 */
internal fun CanvasViewModel.putOnSystemClipboard(copy: io.github.artcraftandroid.procraft.core.ThumbnailImage) {
    val turn = ++clipTurn
    val app = getApplication<Application>()
    // the colour profile its colours are in, which the picture carries (none if it can't be read)
    val profile = runCatching { session?.copyProfile() }.getOrNull() ?: ByteArray(0)
    viewModelScope.launch {
        clipWrites.withLock {
            if (turn != clipTurn) return@launch
            val dir = File(app.cacheDir, "exports/clipboard")
            val file = File(dir, "copy-${System.currentTimeMillis()}.png")
            val written = withContext(Dispatchers.Default) {
                runCatching {
                    dir.mkdirs()
                    io.github.artcraftandroid.procraft.core.writeCopyPng(copy, profile, file.path)
                }.getOrDefault(false)
            }
            // a newer copy is on its way: it goes there instead
            val superseded = turn != clipTurn
            val uri = if (written && !superseded) runCatching { androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file) }.getOrNull() else null
            val board = systemClipboard
            val put = uri != null && board != null && runCatching { board.setPrimaryClip(android.content.ClipData.newUri(app.contentResolver, "Picture", uri)) }.isSuccess
            if (put) {
                clipUri = uri
                clipPutAt = runCatching { board?.primaryClipDescription?.timestamp }.getOrNull()
            } else if (!superseded && clipRefusedTold.first()) {
                showNotice("Copied to paste here: the system clipboard didn't take the picture.", warning = true)
            }
            // only the one on the clipboard is kept
            withContext(Dispatchers.IO) { runCatching { dir.listFiles()?.filter { if (put) it != file else it == file }?.forEach { it.delete() } } }
        }
    }
}

internal fun CanvasViewModel.offerPaste() {
    canPaste = clipboard != null || systemPicture
}

/**
 * Looks again whether the system's clipboard holds a picture, by the kinds of thing it says it
 * holds: nothing is read from it, so the system doesn't tell the user it was.
 */
fun CanvasViewModel.lookAtSystemClipboard() {
    val kinds = runCatching { systemClipboard?.primaryClipDescription }.getOrNull()
    systemPicture = kinds != null && isPicture(kinds)
    offerPaste()
}

