package io.github.artcraftandroid.procraft.canvas

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.gallery.Artwork
import io.github.artcraftandroid.procraft.gallery.ArtworkStore
import io.github.artcraftandroid.procraft.core.BackgroundInfo
import io.github.artcraftandroid.procraft.core.BlendModeInfo
import io.github.artcraftandroid.procraft.core.BrushInfo
import io.github.artcraftandroid.procraft.core.BrushSetInfo
import io.github.artcraftandroid.procraft.core.RecentBrush
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.ColourModel
import io.github.artcraftandroid.procraft.core.ColourPlace
import io.github.artcraftandroid.procraft.core.colourModelOfKey
import io.github.artcraftandroid.procraft.core.SourceKind
import io.github.artcraftandroid.procraft.core.ChromaticKind
import io.github.artcraftandroid.procraft.core.PerspectiveKind
import io.github.artcraftandroid.procraft.core.GlitchKind
import io.github.artcraftandroid.procraft.core.GuideInfo
import io.github.artcraftandroid.procraft.core.HalftoneKind
import io.github.artcraftandroid.procraft.core.NoiseKind
import io.github.artcraftandroid.procraft.core.Adjustment
import io.github.artcraftandroid.procraft.core.GesturePurpose
import io.github.artcraftandroid.procraft.core.QuickAction
import io.github.artcraftandroid.procraft.core.QuickShapeInfo
import io.github.artcraftandroid.procraft.core.GuideSettings
import io.github.artcraftandroid.procraft.core.Interpolation
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.TextInfo
import io.github.artcraftandroid.procraft.core.TextRunInfo
import io.github.artcraftandroid.procraft.core.SelectionJoin
import io.github.artcraftandroid.procraft.core.LiquifyKind
import io.github.artcraftandroid.procraft.core.ToolKind
import io.github.artcraftandroid.procraft.core.TransformBox
import io.github.artcraftandroid.procraft.core.TransformHandle
import io.github.artcraftandroid.procraft.core.TransformKind
import io.github.artcraftandroid.procraft.gallery.Palette
import io.github.artcraftandroid.procraft.core.blendModes
import io.github.artcraftandroid.procraft.core.brushBlendModes
import io.github.artcraftandroid.procraft.core.grainBlendModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer


class CanvasViewModel(internal val app: Application) : AndroidViewModel(app) {
    var session: CanvasSession? = null
        internal set

    /** Frosted panels open over the canvas, for the engine to blur what is behind them. */
    val backdrops = io.github.artcraftandroid.procraft.ui.Backdrops { regions, windows -> session?.setGlass(regions, windows) }

    var screen by mutableStateOf(Screen.GALLERY)
        internal set

    /**
     * Whether the app's window is in wide colour: only while the open canvas shows in it (a Display
     * P3, CMYK or imported-profile canvas, Wide Color on), so its window and the canvas's surface
     * always agree. MainActivity follows.
     */
    var wideColorWindow by mutableStateOf(false)
        internal set

    val store = ArtworkStore(File(app.filesDir, "artworks"))
    val trash = io.github.artcraftandroid.procraft.gallery.GalleryTrash(File(app.filesDir, "trash"), store.root)
    var artworks by mutableStateOf<List<Artwork>>(emptyList())
        internal set

    /** What the gallery shows: artworks and stacks (in the stack open, only its artworks). */
    var galleryEntries by mutableStateOf<List<io.github.artcraftandroid.procraft.gallery.GalleryEntry>>(emptyList())
        internal set

    /** Active search filter in the gallery (trimmed and matched against artwork/stack names). */
    var galleryQuery by mutableStateOf("")

    /** Active sort order in the gallery, persisted across sessions. */
    var gallerySort by mutableStateOf(io.github.artcraftandroid.procraft.gallery.GallerySort.CUSTOM)
        internal set

    /** Items currently in Recently Deleted, newest-deleted first. */
    var trashEntries by mutableStateOf<List<io.github.artcraftandroid.procraft.gallery.TrashedEntry>>(emptyList())
        internal set

    /** Number of active items in Recently Deleted, shown as a badge on the gallery trash control. */
    var trashCount by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    /** Recently Deleted full-screen view is open over the gallery. */
    var trashOpen by mutableStateOf(false)

    /** Backup & Restore full-screen view is open over the gallery. */
    var backupOpen by mutableStateOf(false)

    /** Live progress while creating or restoring a full backup archive; null when idle. */
    data class BackupProgress(val label: String, val progress: Float, val restoring: Boolean)

    var backupProgress by mutableStateOf<BackupProgress?>(null)
        internal set

    @Volatile
    internal var backupCancelled = false

    /** The stack open in the gallery, if any. */
    var openStack by mutableStateOf<io.github.artcraftandroid.procraft.gallery.Stack?>(null)
        internal set

    /** Bumped when artworks are saved, so thumbnails reload. */
    var galleryVersion by mutableStateOf(0)
        internal set
    var opening by mutableStateOf(false)
        internal set
    internal var messageText by mutableStateOf<String?>(null)

    /**
     * How many messages have been given: work that tells why it couldn't be done shows here even
     * once its message has been shown and cleared, or when it repeats the one still showing.
     */
    internal var messagesGiven = 0

    /** A passing message to show; each one given is counted (see [messagesGiven]). */
    var message: String?
        get() = messageText
        set(text) {
            if (text != null) messagesGiven++
            messageText = text
        }
    internal var artworkDir: File? = null

    /** A save failed and was told of: the next failure isn't, until one succeeds again. */
    internal var saveFailing = false

    var tool by mutableStateOf(ToolKind.PAINT)
        internal set
    var size by mutableFloatStateOf(0f)
        internal set
    var opacity by mutableFloatStateOf(1f)
        internal set

    /** The paint colour as hue (0..1), saturation and brightness. */
    var hue by mutableFloatStateOf(DEFAULT_HUE)
        internal set
    var saturation by mutableFloatStateOf(1f)
        internal set
    var brightness by mutableFloatStateOf(1f)
        internal set

    internal var secondaryState by mutableStateOf(0xFFFFFF)

    /**
     * The second colour (0xRRGGBB), beside the current one in the colour panel: swapped with it,
     * and mixed in by brushes whose colour dynamics say so.
     */
    var secondaryRgb: Int
        get() = secondaryState
        internal set(v) {
            secondaryState = v
            pushSecondary()
        }

    /** Colours painted with recently (0xRRGGBB), newest first, as the shared core keeps them. */
    var history by mutableStateOf<List<Int>>(emptyList())
        internal set

    /** The colour history, kept by the shared core across artworks: each canvas's paint strokes add to it. */
    internal val colourHistory = io.github.artcraftandroid.procraft.core.ColourHistory()

    /** Whether the size and opacity side bar shows. */
    var showSideBar by mutableStateOf(true)

    /** Whether the side bar is tucked into an edge pull tab on smartphone screens. */
    var sideBarCollapsed by mutableStateOf(false)
        internal set

    internal val interfacePrefs = app.getSharedPreferences("interface", android.content.Context.MODE_PRIVATE)

    /** Whether a mask is current, the colour then the one masks paint with. */
    internal var onMask = false

    /**
     * The colour not in use (hue, saturation, brightness): masks' (black at first) while a layer
     * of another kind is current, the brush colour while a mask is.
     */
    internal var otherColour = floatArrayOf(0f, 0f, 0f)

    init {
        // the colours as they were last left
        hue = interfacePrefs.getFloat(KEPT_HUE, DEFAULT_HUE).coerceIn(0f, 1f)
        saturation = interfacePrefs.getFloat(KEPT_SATURATION, 1f).coerceIn(0f, 1f)
        brightness = interfacePrefs.getFloat(KEPT_BRIGHTNESS, 1f).coerceIn(0f, 1f)
        otherColour = listOf(KEPT_MASK_HUE, KEPT_MASK_SATURATION, KEPT_MASK_BRIGHTNESS).map { interfacePrefs.getFloat(it, 0f).coerceIn(0f, 1f) }.toFloatArray()
    }

    /** The colour panel's mode, kept from one session to the next. */
    internal var colorModeState by mutableStateOf(ColorMode.entries.getOrElse(interfacePrefs.getInt(COLOR_MODE, ColorMode.DISC.ordinal)) { ColorMode.DISC })
    var colorMode: ColorMode
        get() = colorModeState
        set(mode) {
            colorModeState = mode
            interfacePrefs.edit().putInt(COLOR_MODE, mode.ordinal).apply()
        }

    /**
     * The colour model the colour panel's disc, Classic and Value work in (HSB, the panel's own,
     * unless another is chosen on its model button), kept from one session to the next.
     */
    var colourModel by mutableStateOf(interfacePrefs.getString(COLOUR_MODEL, null)?.let(::colourModelOfKey) ?: ColourModel.HSB)
        internal set

    /** Where the colour painted with stands in [colourModel], kept while it is still the colour. */
    val colourPlace = ColourPlace()

    /** The side bar on the right edge instead of the left. */
    var rightHanded by mutableStateOf(interfacePrefs.getBoolean(RIGHT_HANDED, false))
        internal set

    /** How long two fingers are held before undo repeats, seconds. */
    var rapidUndoDelay by mutableStateOf(interfacePrefs.getFloat(RAPID_UNDO_DELAY, 0.65f))
        internal set

    /** Tactile haptic feedback on key canvas snaps, picks, and undo/redo steps. */
    var hapticFeedback by mutableStateOf(interfacePrefs.getBoolean(HAPTIC_FEEDBACK, true))
        internal set

    /** Hardware Volume Up / Volume Down shortcut action on the canvas. */
    var volumeKeyMode by mutableStateOf(VolumeKeyMode.fromKey(interfacePrefs.getString(VOLUME_KEY_MODE, null)))
        internal set

    /** Auto-fading zoom % & rotation ° HUD pill when navigating the canvas. */
    var canvasViewHud by mutableStateOf(interfacePrefs.getBoolean(CANVAS_VIEW_HUD, true))
        internal set

    /** Optional pinned bottom-corner quick Flip Horizontal button on the canvas. */
    var quickFlipButton by mutableStateOf(interfacePrefs.getBoolean(QUICK_FLIP_BUTTON, false))
        internal set

    /** Automatically fades out top and side bars while actively stroking the canvas. */
    var autoHideUi by mutableStateOf(interfacePrefs.getBoolean(AUTO_HIDE_UI, false))
        internal set

    /** True while [autoHideUi] has temporarily tucked the bars away for an active stroke. */
    var autoHidingBars by mutableStateOf(false)
        internal set

    internal var autoHideRestoreJob: Job? = null

    /** Quietly checks GitHub Releases for newer builds when opening the Gallery. */
    var updateAutoCheck by mutableStateOf(interfacePrefs.getBoolean(UPDATE_AUTO_CHECK, true))
        internal set

    /** Opt-in to beta/RC pre-releases on GitHub (defaults to true when running a pre-release build). */
    var updateIncludePreReleases by mutableStateOf(
        interfacePrefs.getBoolean(UPDATE_INCLUDE_PRE, io.github.artcraftandroid.procraft.update.AppUpdater.defaultIncludePreReleases(appVersion())),
    )
        internal set

    /** Release tag dismissed from the Gallery banner until a newer version arrives. */
    var dismissedUpdateTag by mutableStateOf(interfacePrefs.getString(UPDATE_DISMISSED_TAG, null))
        internal set

    /** Live state of the GitHub Releases in-app updater. */
    var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
        internal set

    /** Full-screen Software Update sheet open over Gallery or Canvas. */
    var updateSheetOpen by mutableStateOf(false)
        internal set

    /** Full-screen Changelog sheet open over Gallery or Canvas. */
    var changelogSheetOpen by mutableStateOf(false)
        internal set

    fun openChangelogSheet() {
        changelogSheetOpen = true
    }

    fun closeChangelogSheet() {
        changelogSheetOpen = false
    }

    internal var updateDownloadJob: Job? = null

    @Volatile
    internal var updateDownloadCancelled = false

    /** Activity-supplied permission-free haptic trigger (`decorView.performHapticFeedback`). */
    internal var performHaptic: ((Int) -> Unit)? = null
    internal val hapticGate = HapticGate()
    internal val transformSnapTracker = TransformSnapTracker()

    /** Brush cursor: the brush's outline where the pen paints or hovers. */
    var brushCursorOn by mutableStateOf(interfacePrefs.getBoolean(BRUSH_CURSOR, true))
        internal set

    /** Where the brush cursor is (window pixels), and its radius there; none while not shown. */
    var cursorAt by mutableStateOf<Offset?>(null)
    var cursorRadius by mutableStateOf(0f)
        internal set

    /** The current brush's own outline, used when the cursor's style is per brush: 0 none, 1 contrast, 2 the colour. */
    var cursorOutline by mutableStateOf(1)
        internal set

    /** The brush's own shape for the cursor: loops of x, y in brush diameters (none: the round tip's circle). */
    var cursorShape by mutableStateOf<List<List<Float>>>(emptyList())
        internal set

    /** How far the cursor's shape is turned on the screen past its own rotation (radians, clockwise), as the stamps are: with the canvas. */
    var cursorTurn by mutableStateOf(0f)
        internal set

    /** The cursor shows where the pen hovers (not where it paints). */
    var cursorHovering by mutableStateOf(false)
        internal set

    /** The brush's stamp the cursor fills while the pen hovers (its Hover fill: none, or the shape it paints), as a coverage mask. */
    var cursorFill by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        internal set

    /**
     * How the fill shows: as the brush would paint at its estimated hover pressure, a share of the
     * cursor's diameter and of the opacity painted with (the core's).
     */
    var cursorFillLook by mutableStateOf(io.github.artcraftandroid.procraft.core.HoverFill(1f, 1f))
        internal set

    /** The stamp [cursorFill] was made from, and its side, to make it again only when it changes. */
    internal var cursorFillMade: Pair<Int, ByteArray>? = null

    /** When the brush cursor shows: while the pen hovers, while painting, or both. */
    enum class CursorShows { HOVERING, PAINTING, BOTH }

    /** How it is drawn: light and dark rings, in the colour painted with, or the brush's own grey ring. */
    enum class CursorStyle { CONTRAST, COLOR, BRUSH }

    var cursorShows by mutableStateOf(CursorShows.entries.getOrElse(interfacePrefs.getInt(CURSOR_SHOWS, 0)) { CursorShows.HOVERING })
        internal set
    var cursorStyle by mutableStateOf(CursorStyle.entries.getOrElse(interfacePrefs.getInt(CURSOR_STYLE, 2)) { CursorStyle.BRUSH })
        internal set

    /** The palettes show as cards (named tiles) rather than compact rows of swatches. */
    var paletteCards by mutableStateOf(interfacePrefs.getBoolean(PALETTE_CARDS, false))
        internal set

    /** The short colour panel shows the colour history under the picker, rather than the active palette. */
    var colorHistoryShown by mutableStateOf(interfacePrefs.getBoolean(COLOR_HISTORY_SHOWN, false))
        internal set

    /** Project canvas: the canvas, without the interface, on a second display when there is one. */
    var projectCanvas by mutableStateOf(interfacePrefs.getBoolean(PROJECT_CANVAS, false))
        internal set

    // the second display's window while it is shown, and its size
    internal var projection: Triple<android.view.Surface, Int, Int>? = null

    /** Brush scaling relative to the screen (else to the canvas). */
    var brushOnScreen by mutableStateOf(interfacePrefs.getBoolean(BRUSH_ON_SCREEN, false))
        internal set

    /** When the cursor's size was last read (uptime ms). */
    internal var cursorReadAt = 0L

    /** Where Liquify's ring is (window pixels), and its radius there: its reach while it is open. */
    var liquifyRingAt by mutableStateOf<Offset?>(null)
        internal set
    var liquifyRingRadius by mutableStateOf(0f)
        internal set

    /** The light interface: light grey panels and bars with dark text. */
    var lightInterface by mutableStateOf(interfacePrefs.getBoolean(LIGHT_INTERFACE, false).also { io.github.artcraftandroid.procraft.ui.Pc.light = it })
        internal set

    /** The plugins, their values kept with the interface settings, and what their page draws. */
    val plugins = keptPlugins(app, interfacePrefs)
    var pluginList by mutableStateOf(plugins.plugins())
        internal set

    /** Whether a plugin draws in-betweens (the frame options then offer In-between). */
    var tweensOn by mutableStateOf(false)
        internal set

    /** How many Reference windows may be open at once: as many as a plugin lets be, 1 with none. */
    var referenceWindowsAllowed by mutableStateOf(1)
        internal set

    /** Counts the plugins' changes to how the interface looks, so what is worked out from them is worked out again. */
    var pluginLooks by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    /**
     * Whether a plugin makes the strokes' sound, as last asked: only then does a hovering pen tell
     * the canvas where it is (each hover event would otherwise wait on the canvas for nothing).
     */
    var strokeSoundOn = false
        internal set

    // the interface's accent as the plugins give it
    init { followPluginLooks() }

    /** The strokes' sound (a plugin's), played while an artwork is open and the app in front. */
    internal val strokeSound = StrokeSound()

    /**
     * Tells why the open artwork's autosave failed, or what the GPU couldn't do (a smudge it had no
     * memory for, its device lost), a few seconds apart after each change and for as long as the
     * artwork has changes not yet saved; nothing while all is saved or the app is away.
     */
    internal val failureWatch = WhileNeeded(viewModelScope, SAVE_FAILURE_POLL_MS, Dispatchers.IO) {
        val s = session ?: return@WhileNeeded false
        s.takeSaveFailure()?.let { told(Result.failure(Exception(it))) }
        s.takeFailure()?.let { withContext(Dispatchers.Main) { showNotice(it, warning = true) } }
        session === s && s.editsSaved() != s.edits()
    }

    /**
     * Whether the app is in front (started): away, the canvas draws nothing, the autosave and the
     * failure watch rest, the strokes' sound and an animation playing stop, and the interface's
     * own timers (a plugin filter's poll, an animated preview) wait.
     */
    var inFront by mutableStateOf(true)
        internal set

    /** The open artwork's session's own work as last logged, and when (see [logActivity]). */
    internal var activityLogged: Pair<Long, io.github.artcraftandroid.procraft.core.Activity>? = null

    /** How big the interface shows now, as a plugin says (1: as measured). */
    var interfaceShown = 1f
        internal set

    /** Words for someone who changed a setting that now comes as a plugin, said once. */
    var movedNotice by mutableStateOf(plugins.takeMovedNotice())
        internal set

    /**
     * The 32-bit build running on a tablet that runs 64-bit apps: the 64-bit build is the one for
     * it (newer Android warns about 32-bit apps). Said once for each version.
     */
    var wrongBuildNotice by mutableStateOf(
        !android.os.Process.is64Bit() && android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
            interfacePrefs.getString(WRONG_BUILD_SAID, null) != appVersion(),
    )
        internal set

    /** A plugin file waiting on the one screen that asks about it, and what it holds. */
    var pluginOffer by mutableStateOf<io.github.artcraftandroid.procraft.core.PluginOffer?>(null)
        internal set
    internal var offeredPlugin: ByteArray? = null

    /** The panel a plugin's action showed, while it is open. */
    var pluginPanel by mutableStateOf<io.github.artcraftandroid.procraft.core.PluginPanel?>(null)
        internal set

    /** How strongly what isn't selected is banded, 0..1. */
    var selectionVisibility by mutableStateOf(interfacePrefs.getFloat(SELECTION_VISIBILITY, 0.25f))
        internal set

    // pressure and smoothing, for every stroke

    var inputStabilization by mutableStateOf(interfacePrefs.getFloat(INPUT_STABILIZATION, 0f))
        internal set
    var inputTipAttached by mutableStateOf(interfacePrefs.getBoolean(INPUT_TIP_ATTACHED, true))
        internal set
    var inputMotionFiltering by mutableStateOf(interfacePrefs.getFloat(INPUT_MOTION, 0f))
        internal set
    var inputMotionExpression by mutableStateOf(interfacePrefs.getFloat(INPUT_EXPRESSION, 0f))
        internal set
    var inputPressureSmoothing by mutableStateOf(interfacePrefs.getFloat(INPUT_PRESSURE_SMOOTHING, 0f))
        internal set
    var inputCurve by mutableStateOf(
        interfacePrefs.getString(INPUT_CURVE, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.chunked(2)?.filter { it.size == 2 }
            ?.map { Offset(it[0], it[1]) }?.takeIf { it.size >= 2 } ?: listOf(Offset(0f, 0f), Offset(1f, 1f)),
    )
        internal set

    // external drawing tablet (XP-Pen, Huion, Wacom, Gaomon, Veikk, etc.) & pen pressure calibration

    /** Treats external USB/Bluetooth drawing tablets & pointers as pressure-sensitive pens even when Android reports mouse mode. */
    var drawingTabletMode by mutableStateOf(interfacePrefs.getBoolean(DRAWING_TABLET_MODE, true))
        internal set

    /** Whether stylus/tablet pressure varies brush size & opacity (turn off for uniform strokes on pressureless devices). */
    var penPressureEnabled by mutableStateOf(interfacePrefs.getBoolean(PEN_PRESSURE_ENABLED, true))
        internal set

    /** Multiplier applied to raw hardware stylus pressure before the pressure curve (0.25x..3.0x). */
    var penPressureSensitivity by mutableStateOf(interfacePrefs.getFloat(PEN_PRESSURE_SENSITIVITY, 1f).coerceIn(0.25f, 3f))
        internal set

    /** Minimum pressure floor applied to active pen contact (0..0.5) so feather-light tablet strokes stay visible. */
    var penPressureMin by mutableStateOf(interfacePrefs.getFloat(PEN_PRESSURE_MIN, 0f).coerceIn(0f, 0.5f))
        internal set

    /** Replaces the Android system arrow pointer over the open painting canvas with the brush outline or a precision crosshair. */
    var hideTabletArrowCursor by mutableStateOf(interfacePrefs.getBoolean(HIDE_TABLET_ARROW, true))
        internal set

    /** Shows a high-contrast navigation pointer over UI, bars, panels, and gallery when using an external drawing tablet. */
    var showTabletNavPointer by mutableStateOf(interfacePrefs.getBoolean(SHOW_TABLET_NAV_POINTER, true))
        internal set

    /** True while the user is actively interacting with an external USB/Bluetooth drawing tablet. */
    var externalTabletActive by mutableStateOf(false)
        internal set

    /** Window coordinates of the external drawing tablet pointer across all screens (gallery, bars, panels, canvas). */
    var tabletPointerAt by mutableStateOf<Offset?>(null)
        internal set

    /** Whether the external drawing tablet's primary nib/button is currently pressed. */
    var tabletPointerPressed by mutableStateOf(false)
        internal set

    /** True when the external tablet's event source includes `SOURCE_MOUSE` (so Android may render an OS cursor). */
    var tabletPointerHasSystemCursor by mutableStateOf(false)
        internal set

    /** True only when the latest pointer event reached the uncovered canvas surface outside UI bars/panels. */
    var canvasPointerOnCanvas by mutableStateOf(false)
        internal set

    /** True while the Activity is delivering a real `ACTION_DOWN..ACTION_UP` touch sequence anywhere in the window. */
    internal var activityTouchActive = false

    /** Input device IDs that have delivered at least one real `ACTION_DOWN` (never need hover-only synthetic stroke bridging). */
    internal val devicesWithRealTouch = HashSet<Int>()

    /** Shared per-device pressure calibration tracker across canvas, brush studio pad, and pressure test card. */
    internal val tabletPressureTracker = TabletPressureTracker()

    /** Per-device auto-gain learner persisted in [interfacePrefs] for under-reporting tablets. */
    internal val penPressureLearner = PenPressureLearner.backedBy(interfacePrefs)

    /** Live drawing tablet diagnostics (only updated at frame rate while the Drawing Tablet settings page is open). */
    var tabletMonitorActive by mutableStateOf(false)
        internal set
    var lastTabletDeviceName by mutableStateOf("")
        internal set
    var lastTabletDeviceKey by mutableStateOf<String?>(null)
        internal set
    var lastTabletKind by mutableStateOf("")
        internal set
    var lastTabletPressure by mutableStateOf(0f)
        internal set
    var lastTabletHasPressureAxis by mutableStateOf(false)
        internal set
    var lastTabletHasTiltAxis by mutableStateOf(false)
        internal set
    var lastTabletLearnedRef by mutableStateOf<Float?>(null)
        internal set

    /** The actions panel's open tab, kept from one session to the next. */
    internal var actionsTabState by mutableStateOf(interfacePrefs.getInt(ACTIONS_TAB, 0))
    var actionsTab: Int
        get() = actionsTabState
        set(tab) {
            actionsTabState = tab
            interfacePrefs.edit().putInt(ACTIONS_TAB, tab).apply()
        }

    /** The eyedropper, while it is open. */
    var picking by mutableStateOf<PickState?>(null)
        internal set

    /** The modify button was pressed: the next touch on the canvas picks a colour (the touches know too). */
    internal var pickNextShown by mutableStateOf(false)
    var pickNext: Boolean
        get() = pickNextShown
        set(value) {
            pickNextShown = value
            session?.setPickNext(value)
        }

    /** Transform is open (with a box when the layer holds anything). */
    var transforming by mutableStateOf(false)
        internal set
    var transformBox by mutableStateOf<TransformBox?>(null)
        internal set
    internal val transformPrefs = app.getSharedPreferences("transform", android.content.Context.MODE_PRIVATE)
    /** The brushes in use and what has shipped, kept between artworks and launches. */
    internal val brushPrefs = app.getSharedPreferences("brushes", android.content.Context.MODE_PRIVATE)
    /** The brush library as edited, in a file: brought-in brushes' images make it too big for preferences. */
    internal val brushesDir = File(app.filesDir, "brushes")
    internal val brushLibraryFile = File(brushesDir, "library.json")
    /** Writes of the library, one at a time. */
    internal val brushLibraryWrites = Mutex()
    var transformKind by mutableStateOf(enumPref(TRANSFORM_KIND, TransformKind.FREEFORM))
        internal set
    var interpolation by mutableStateOf(enumPref(INTERPOLATION, Interpolation.BILINEAR))
        internal set
    var transformPopup by mutableStateOf(TransformPopup.NONE)
    /** Magnetics, snapping, and snapping's distance (1..50 points) and velocity (0..10). */
    var guides by mutableStateOf(
        GuideSettings(
            transformPrefs.getBoolean(MAGNETICS, false),
            transformPrefs.getBoolean(SNAPPING, false),
            transformPrefs.getFloat(SNAP_DISTANCE, 3f),
            transformPrefs.getFloat(SNAP_VELOCITY, 10f),
        ),
    )
        internal set

    /** A warp shows and takes all 16 of its control points, not only its corners. */
    var advancedMesh by mutableStateOf(transformPrefs.getBoolean(ADVANCED_MESH, false))
        internal set

    /** The selection tool is open. */
    var selecting by mutableStateOf(false)
        internal set
    var selectionKind by mutableStateOf(enumPref(SELECTION_KIND, SelectionKind.FREEHAND))
        internal set
    /** New shapes take away from the selection instead of adding to it. */
    var selectionRemoves by mutableStateOf(false)
        internal set
    /** Each new selection is filled with the paint colour. */
    var colorFill by mutableStateOf(false)
        internal set
    var hasSelection by mutableStateOf(false)
        internal set
    /** While the selection tool is open on a plugin's layer: the box round the strokes the selection takes (four corners, x then y, canvas pixels), else empty. */
    var takenBox by mutableStateOf<List<Float>>(emptyList())
        internal set
    internal var selectionPopupState by mutableStateOf(SelectionPopup.NONE)
    /** The popover over the selection toolbar; closing the feather popover keeps its feathering as one undo step. */
    var selectionPopup: SelectionPopup
        get() = selectionPopupState
        set(value) {
            if (selectionPopupState == SelectionPopup.FEATHER && value != SelectionPopup.FEATHER) {
                // after the feathering still on its way
                session?.let { s -> pourNow { s.selectFeatherDone() } }
                refreshFlags()
            }
            selectionPopupState = value
        }
    /** The feather popover's amount, 0..1. */
    var featherAmount by mutableFloatStateOf(0f)
        internal set
    var savedSelections by mutableStateOf(0)
        internal set
    /** Pictures of the kept selections, by index. */
    val savedThumbnails = mutableStateMapOf<Int, ImageBitmap>()
    /** The freehand path being drawn, in canvas pixels (closed by tapping its first point). */
    var freehand by mutableStateOf<List<Offset>>(emptyList())
        internal set
    /** A rectangle or ellipse being dragged out: its corners in window pixels. */
    var shapeDrag by mutableStateOf<Pair<Offset, Offset>?>(null)
    /** A colour selection's threshold being dragged: where (window pixels), and the threshold (0..1). */
    var thresholdDrag by mutableStateOf<Pair<Offset, Float>?>(null)
    /** The colour selection's threshold, kept from one selection to the next (0..1). */
    var selectionThreshold by mutableFloatStateOf(transformPrefs.getFloat(SELECTION_THRESHOLD, 0.5f))
        internal set

    /** The plugin's filter open, for its panel: its name and its controls. */
    var pluginFilter by mutableStateOf<io.github.artcraftandroid.procraft.core.PluginFilterShown?>(null)
        internal set

    /** The adjustment open on the current layer, and its values. */
    var adjustment by mutableStateOf<Adjustment?>(null)
        internal set
    var adjustAmount by mutableFloatStateOf(0f)
        internal set
    var hue50 by mutableFloatStateOf(0.5f)
        internal set
    var saturation50 by mutableFloatStateOf(0.5f)
        internal set
    var brightness50 by mutableFloatStateOf(0.5f)
        internal set
    /** The liquify brush: its way and sliders (0..1), and how much of it shows. */
    var liquifyKind by mutableStateOf(LiquifyKind.PUSH)
        internal set
    var liquifySize by mutableFloatStateOf(0.5f)
        internal set
    var liquifyPressure by mutableFloatStateOf(1f)
        internal set
    var liquifyDistortion by mutableFloatStateOf(0f)
        internal set
    var liquifyMomentum by mutableFloatStateOf(0.8f)
        internal set
    var liquifyAmount by mutableFloatStateOf(1f)
        internal set
    /** Liquify's mode list or adjust slider is open. */
    var liquifyPopup by mutableStateOf(0)
    var liquified by mutableStateOf(false)
        internal set

    /** The adjustment is painted on with the brush (Pencil), and the Layer / Pencil choice is open. */
    var adjustPencil by mutableStateOf(false)
        internal set
    var adjustModesOpen by mutableStateOf(false)
    /** Cloning: where the source is (canvas pixels). */
    var clonePuck by mutableStateOf<Offset?>(null)
        internal set
    /** The canvas window's size (pixels) and the screen's density, for what starts in its middle. */
    var windowSize = Offset.Zero
    var screenDensity = 2f

    /** Pixels a dp of the interface. */
    val density get() = screenDensity * interfaceShown

    /** Where the adjustment's menu is open (window pixels), after a tap on the canvas. */
    var adjustMenuAt by mutableStateOf<Offset?>(null)
    /** The layer shows as it was before the adjustment. */
    var adjustPreview by mutableStateOf(false)
        internal set
    /** Colour balance: the tone shown (shadows, midtones, highlights) and its nine sliders (0..1, 0.5 none). */
    var balanceTone by mutableStateOf(1)
        internal set
    var balance by mutableStateOf(List(9) { 0.5f })
        internal set
    /** Curves: the channel shown (all, red, green, blue) and each channel's points (0..1). */
    var curveChannel by mutableStateOf(0)
        internal set
    var curves by mutableStateOf(List(4) { listOf(Offset(0f, 0f), Offset(1f, 1f)) })
        internal set
    /** The gradient mapped by. */
    var gradientIndex by mutableStateOf(0)
        internal set
    /** The gradient library, kept by the shared core (crates/core gradients.rs), each change written at once. */
    internal var gradientLibrary = io.github.artcraftandroid.procraft.core.Gradients.open(File(app.filesDir, "gradients.json").path)
    /** The gradient library: ours, and those made or changed here. */
    var gradients by mutableStateOf(readGradients())
        internal set

    /** The gradient being edited, by its place in [gradients]. */
    var gradientEditing by mutableStateOf<Int?>(null)
        internal set
    /** Glitch: the kind shown and each kind's sliders (the last one its zoom). */
    var glitchKind by mutableStateOf(GlitchKind.ARTIFACT)
        internal set
    var glitchValues by mutableStateOf(GLITCH_DEFAULTS)
        internal set
    /** Noise: its kind, scale, octaves and turbulence (0..1), each channel its own, added or mixed in. */
    var noiseKind by mutableStateOf(NoiseKind.CLOUDS)
        internal set
    var noiseScale by mutableFloatStateOf(0f)
        internal set
    var noiseOctaves by mutableFloatStateOf(0f)
        internal set
    var noiseTurbulence by mutableFloatStateOf(0.7f)
        internal set
    var noiseMulti by mutableStateOf(false)
        internal set
    var noiseAdded by mutableStateOf(true)
        internal set
    /** Bloom: transition, size and burn (0..1). */
    var bloomTransition by mutableFloatStateOf(1f)
        internal set
    var bloomSize by mutableFloatStateOf(0.3f)
        internal set
    var bloomBurn by mutableFloatStateOf(0.35f)
        internal set
    var halftoneKind by mutableStateOf(HalftoneKind.FULL_COLOR)
        internal set
    /** Chromatic aberration: its kind and each kind's two sliders; displaced, how far (window pixels). */
    var chromaKind by mutableStateOf(ChromaticKind.PERSPECTIVE)
        internal set
    var chromaValues by mutableStateOf(mapOf(ChromaticKind.PERSPECTIVE to listOf(0f, 0f), ChromaticKind.DISPLACE to listOf(0f, 0f)))
        internal set
    /** Perspective blur: positional or directional, and (on the layer) its puck and a point along its arrow (canvas pixels). */
    var perspectiveKind by mutableStateOf(PerspectiveKind.POSITIONAL)
        internal set
    var perspectivePuck by mutableStateOf<Pair<Offset, Offset>?>(null)
        internal set

    /** The node held on the transform box and where the touch is (window pixels), while a drag lasts. */
    var transformHeld by mutableStateOf<Pair<TransformHandle, Offset>?>(null)
    /** The rotate or box-turning node just let go where the touch left it, springing back. */
    var transformLetGo by mutableStateOf<Pair<TransformHandle, Offset>?>(null)

    /** The view (canvas pixels to window pixels) as [a, b, c, d, e, f], read while something is drawn over the canvas. */
    var viewMatrix by mutableStateOf(listOf(1f, 0f, 0f, 1f, 0f, 0f))
        internal set

    internal var pickJob: Job? = null
    internal var pendingPick: Pair<Float, Float>? = null

    // the palettes, kept by the shared core
    internal var paletteStore = io.github.artcraftandroid.procraft.core.Palettes.open(File(app.filesDir, "palettes.json").path)
    var palettes by mutableStateOf<List<Palette>>(emptyList())
        internal set

    /** The palette shown under the history in the other colour modes. */
    var defaultPalette by mutableStateOf(0)
        internal set

    init {
        refreshPalettes()
        // colour profiles the user imported
        io.github.artcraftandroid.procraft.core.setProfileFolder(File(app.filesDir, "icc").path)
        // layers may take half the device's memory, their textures a quarter: past that, those not
        // worked on are kept compressed off the GPU until needed (set before Custom Canvas asks)
        val memory = android.app.ActivityManager.MemoryInfo()
        app.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
        if (memory.totalMem > 0) {
            io.github.artcraftandroid.procraft.core.setLayerBudget((memory.totalMem / 2).toULong())
            io.github.artcraftandroid.procraft.core.setResidentBudget((memory.totalMem / 4).toULong())
        }
    }

    /** A CMYK canvas's inks for a colour and back, for the colour panel; null on an RGB canvas. */
    var inks by mutableStateOf<io.github.artcraftandroid.procraft.ui.Inks?>(null)
        internal set

    var harmonyKind by mutableStateOf(HarmonyKind.COMPLEMENTARY)

    var barsHidden by mutableStateOf(false)
        internal set
    var panel by mutableStateOf(Panel.NONE)
        internal set

    /** The colour panel floating, dragged out by its grabber: its window's top-left (window pixels). */
    var floatingColorsAt by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)

    init {
        // the colour panel opened and closed: closed on another colour, the one it opened on is
        // the one the colour button's hold goes back to
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { panel == Panel.COLOR || floatingColorsAt != null }
                .collect { shown -> colourHistory.panelShown(shown, colorRgb().toUInt(), onMask) }
        }
    }

    /** The colour panel's grabber is being dragged out: the panel stays, unseen, until let go. */
    var colorsUndocking by mutableStateOf(false)

    /**
     * The Layers panel and the brush library, each as its window was last left when torn off into one of its own
     * (shown or closed), kept with the interface settings. A window shows while a plugin lets its panel float.
     */
    var floatingWindows by mutableStateOf(readFloatingWindows())
        internal set

    /** A panel's grabber is being dragged out: the panel stays, unseen, until let go, its window following the finger. */
    var panelUndocking by mutableStateOf<io.github.artcraftandroid.procraft.core.FloatingPanel?>(null)
        internal set

    /** Where the floating windows may go now (dp of the interface): the window, clear of the side bar. */
    var floatingRoom = io.github.artcraftandroid.procraft.core.FloatingRoom(0f, 0f, 1f, 1f)

    /** The Layers panel shows: hanging from its button, or floating. */
    val layersShown: Boolean get() = panel == Panel.LAYERS || floatingWindow(io.github.artcraftandroid.procraft.core.FloatingPanel.LAYERS) != null

    /** The panel just closed fades away (an adjustment picked from its list) instead of going at once. */
    var panelFading by mutableStateOf(false)

    var layers by mutableStateOf<List<LayerInfo>>(emptyList())
        internal set
    var background by mutableStateOf(BackgroundInfo(1f, 1f, 1f, true))
        internal set
    var canUndo by mutableStateOf(false)
        internal set
    var canRedo by mutableStateOf(false)
        internal set
    val thumbnails = mutableStateMapOf<ULong, ImageBitmap>()
    /** The pixels' version each thumbnail was read at: it is read again only once its layer's is new. */
    internal val thumbnailVersions = HashMap<ULong, ULong>()

    var brushSets by mutableStateOf<List<BrushSetInfo>>(emptyList())
        internal set
    var currentBrush by mutableStateOf<BrushInfo?>(null)
        internal set
    var shownSet by mutableStateOf(0)
        internal set
    /** The Recent set: the pinned brushes, then those last painted with, the latest first. */
    var recentBrushes by mutableStateOf<List<RecentBrush>>(emptyList())
        internal set
    /** A brush found from the Recent set ("set-index"), which its own set scrolls to. */
    var foundBrush by mutableStateOf<String?>(null)
    val previews = mutableStateMapOf<String, ImageBitmap>()

    /** Counts the library's changes: a sample stroke asked for before the last one isn't kept. */
    var previewRound by androidx.compose.runtime.mutableIntStateOf(0)
        internal set

    /** Previews whose stroke runs under the brush's name: the name then sits on a dark pill. */
    val shadedNames = mutableStateMapOf<String, Boolean>()

    /** The drawing guide, its editor open, and symmetry's options open. */
    var guide by mutableStateOf<GuideInfo?>(null)
        internal set
    var guideEditing by mutableStateOf(false)
        internal set
    var symmetryOptionsOpen by mutableStateOf(false)

    /**
     * What was last copied or cut, for pasting (in this artwork or another), kept as it is; it goes
     * on the system's clipboard too, in place of what that held, as a picture other apps paste.
     */
    internal var clipboard: io.github.artcraftandroid.procraft.core.ThumbnailImage? = null
        set(v) {
            // when something new was cut or copied here, against the system's clipboard
            if (v !== field) {
                clipboardAt = System.currentTimeMillis()
                v?.let(this::putOnSystemClipboard)
            }
            field = v
        }

    /** When [clipboard] last took something new (wall clock, as the system clipboard's time is). */
    internal var clipboardAt = 0L

    /** Which copy went to the system's clipboard last: one still on its way is left out once a newer one is made. */
    internal var clipTurn = 0L

    /** The picture of a copy made here that the system's clipboard holds, to tell it from another app's. */
    internal var clipUri: android.net.Uri? = null

    /** When the system's clipboard says the last copy put there from here went there (its clock), to tell it from another app's. */
    internal var clipPutAt: Long? = null

    /** One copy at a time goes on the system's clipboard, in the order they were made. */
    internal val clipWrites = Mutex()

    /** A copy the system's clipboard didn't take has been told of this run. */
    internal val clipRefusedTold = TellOnce()

    /** The system's clipboard held a picture (another app's, or one copied here before) when last looked at. */
    internal var systemPicture = false

    /** Paste has something to take: a copy made here, or a picture on the system's clipboard. */
    var canPaste by mutableStateOf(false)
        internal set

    internal val clipChanged = android.content.ClipboardManager.OnPrimaryClipChangedListener { lookAtSystemClipboard() }

    init {
        runCatching { systemClipboard?.addPrimaryClipChangedListener(clipChanged) }
    }

    /** The shape the last stroke snapped to (drawn and held), and whether its handles are shown. */
    var quickShape by mutableStateOf<QuickShapeInfo?>(null)
        internal set
    internal var quickShapeEditingShown by mutableStateOf(false)
    var quickShapeEditing: Boolean
        get() = quickShapeEditingShown
        set(value) {
            quickShapeEditingShown = value
            session?.setShapeEditing(value)
        }

    /** The brush studio: open, the tab shown, the brush's name, its controls' values, curve and sample stroke. */
    var studioOpen by mutableStateOf(false)
        internal set

    /**
     * The drawing pad's surface has drawn: until then the pad is covered, since its surface punches
     * through to the canvas's until it has a frame of its own.
     */
    var padShown by mutableStateOf(false)

    /** The brush studio's open page, whichever brush it opens on, kept from one session to the next. */
    internal var studioTabState by mutableStateOf(interfacePrefs.getInt(STUDIO_TAB, 0))
    var studioTab: Int
        get() = studioTabState
        set(tab) {
            studioTabState = tab
            interfacePrefs.edit().putInt(STUDIO_TAB, tab).apply()
        }
    var studioName by mutableStateOf("")
        internal set
    internal val studioValues = mutableStateMapOf<String, Float>()
    var studioCurve by mutableStateOf(listOf(Offset(0f, 0f), Offset(1f, 1f)))
        internal set
    var studioPreview by mutableStateOf<ImageBitmap?>(null)
        internal set
    internal var studioPreviewJob: Job? = null
    /** The studio's drawing pad, the colour it paints in (an index into [PAD_COLORS]), and its settings popover. */
    var pad: CanvasSession? = null
        internal set
    var padColor by mutableStateOf(PAD_WHITE)
        internal set
    var padMenuOpen by mutableStateOf(false)
    /** Where the pad's Drawing Pad button's middle is across the window (pixels): its menu opens under it. */
    var padButtonCentre by mutableStateOf(0f)
    val blendModeList: List<BlendModeInfo> = blendModes()
    /** The modes a brush and its burnt edges blend by, as the studio lists them (the layers' and behind). */
    val brushBlendModeList: List<BlendModeInfo> = brushBlendModes()
    /** The modes a brush's grain blends by, as the studio lists them. */
    val grainBlendModeList: List<BlendModeInfo> = grainBlendModes()
    val combineBlendModeList: List<BlendModeInfo> = io.github.artcraftandroid.procraft.core.combineBlendModes()

    init {
        gallerySort = io.github.artcraftandroid.procraft.gallery.GallerySort.fromKey(interfacePrefs.getString(GALLERY_SORT, null))
        refreshGallery()
        refreshTrash()
        // the GPU made while the gallery shows, so the first artwork opens at once
        viewModelScope.launch(Dispatchers.Default) { io.github.artcraftandroid.procraft.core.warmGpu() }
        // and the brushes read (with many imported, reading them took seconds at each open)
        viewModelScope.launch(Dispatchers.IO) { io.github.artcraftandroid.procraft.core.prepareBrushes(brushesDir.path) }
    }

    // ---- gallery ----

    /** Where new artworks go: the stack open, or the gallery. */
    internal val galleryDir get() = openStack?.dir ?: store.root

    internal val presetPrefs = app.getSharedPreferences("canvas_presets", android.content.Context.MODE_PRIVATE)

    /** The New canvas list under the screen's size: the standard presets, then those made since. */
    var canvasPresets by mutableStateOf(readPresets())
        internal set

    /** Custom Canvas is open, on a new preset or on [editingPreset]. */
    var customCanvasOpen by mutableStateOf(false)
        internal set

    /** The preset Custom Canvas edits (its place in [canvasPresets]); null for a new one. */
    var editingPreset by mutableStateOf<Int?>(null)
        internal set

    // the app's own settings (the settings page beside the canvas): read when the app comes back

    /** An artwork is kept turned as its canvas was left (to the nearest quarter turn), and opens so. */
    var orientationMemory = interfacePrefs.getBoolean(ORIENTATION_MEMORY, true)
        internal set

    /** Fitting the canvas keeps it clear of the top bar and the sidebar. */
    var fitInside = interfacePrefs.getBoolean(FIT_INSIDE, false)
        internal set

    /** New artworks don't record their time-lapse. */
    var timeLapseDisabled = interfacePrefs.getBoolean(NO_TIME_LAPSE, false)
        internal set

    /** Accessibility: the colour named in a message as it changes, and clicks for actions. */
    var colorNames = interfacePrefs.getBoolean(COLOR_NAMES, false)
        internal set
    var feedbackSounds = interfacePrefs.getBoolean(FEEDBACK_SOUNDS, false)
        internal set

    /** Accessibility: a floating bar standing in for gestures that need more than one finger. */
    var singleTouchCompanion by mutableStateOf(interfacePrefs.getBoolean(SINGLE_TOUCH_COMPANION, false))
        internal set

    /** Accessibility: a tap lands where the finger first touched, even if it drifts before lifting. */
    var tapAssistance = interfacePrefs.getBoolean(TAP_ASSISTANCE, false)
        internal set

    /** The companion's one-finger mode: a finger dragged on the canvas zooms it, or moves it. */
    enum class CompanionMode { NONE, ZOOM, MOVE }

    var companionMode by mutableStateOf(CompanionMode.NONE)
        internal set

    /** The open artwork paints on a model: one finger turns it. */
    var modelOpen by mutableStateOf(false)
        internal set

    /** The model's texture sets (by material), and the one painted on: its layers show. */
    var textureSets by mutableStateOf<List<String>>(emptyList())
        internal set
    var textureSet by mutableStateOf(0)
        internal set

    /** The texture set painted on shows flat, as a canvas, in place of the model (Show 2D texture). */
    var modelFlat by mutableStateOf(false)
        internal set

    /** The Lighting Studio is open over the model (Actions > 3D > Edit lighting & environment). */
    var lightingStudioOpen by mutableStateOf(false)
        internal set

    /** The environment the model is lit by (an asset in environments/), how bright, and whether it shows behind. */
    var modelEnvironment by mutableStateOf(DEFAULT_ENVIRONMENT)
        internal set
    var modelExposure by mutableStateOf(1f)
        internal set
    var modelEnvironmentShown by mutableStateOf(true)
        internal set

    /** The lights the model is lit by, as the studio shows them. */
    var modelLights by mutableStateOf<List<io.github.artcraftandroid.procraft.core.ModelLight>>(emptyList())
        internal set

    /** The lighting as the studio found it, put back by Cancel. */
    internal class StudioKept(val environment: String, val exposure: Float, val shown: Boolean, val lights: List<io.github.artcraftandroid.procraft.core.ModelLight>)

    internal var studioKept: StudioKept? = null

    /** Strokes on the model reach every surface under them, not only the nearest. */
    var paintThrough by mutableStateOf(false)
        internal set

    /** The model's meshes, each in its texture set; which one is open in the layers panel; which are hidden; their pictures. */
    var submeshes by mutableStateOf<List<io.github.artcraftandroid.procraft.core.SubmeshInfo>>(emptyList())
        internal set
    var openSubmesh by mutableStateOf<Int?>(null)
        internal set
    var submeshesHidden by mutableStateOf<List<Boolean>>(emptyList())
    var submeshesLocked by mutableStateOf<List<Boolean>>(emptyList())
    /** The meshes picked, which alone take strokes (none: the whole texture set). */
    var activeSubmeshes by mutableStateOf<List<Int>>(emptyList())
        internal set
    val submeshThumbnails = androidx.compose.runtime.mutableStateMapOf<Int, ImageBitmap>()

    /** Whether a selection drawn on a model reaches every surface under it, kept between artworks. */
    var selectionXRay by mutableStateOf(false)
        internal set

    /** A layer the meshes of a texture set share, asked about before it goes. */
    var deletingShared by mutableStateOf<LayerInfo?>(null)

    /** What a model newly opened warns of, while its dialog shows. */
    var modelWarnings by mutableStateOf<List<String>>(emptyList())

    /** The channel painted on the model's texture set: 0 its colour, 1 roughness, 2 metalness. */
    var modelChannel by mutableStateOf(0)
        internal set

    /** Each channel's own colour (hue, saturation, brightness), kept while another is painted. */
    internal val channelColors = arrayOfNulls<FloatArray>(3)

    /** A channel's tile was chosen (blue, the layer's row not): painting goes to that channel of the current layer. */
    var channelTileChosen by mutableStateOf(false)
        internal set

    /** The layer whose channels show under it in the layers panel, if one's do; and their pictures. */
    var channelTrayFor by mutableStateOf<ULong?>(null)
    val channelThumbnails = androidx.compose.runtime.mutableStateMapOf<Pair<Int, ULong>, ImageBitmap>()

    /** An import under way as its card shows it: the file's [name], how far it has come (0..1), and once [done]. */
    data class ImportShown(val name: String, val progress: Float, val done: Boolean = false)

    /** The import card, while a file is brought in (null without one). */
    var importShown by mutableStateOf<ImportShown?>(null)
        internal set

    @Volatile
    internal var importCancelled = false

    /** An import is under way (its card may have gone with Cancel while what it made is thrown away). */
    internal var importBusy = false

    /** The gallery folders the import under way has made. */
    internal val importMade = java.util.Collections.synchronizedList(mutableListOf<File>())

    /** How far the import has come as its work tells it (the copy in), 0..1. */
    @Volatile
    internal var importStep = 0f

    /** The last [cacheCopy] failed for want of room on the device. */
    @Volatile
    internal var copyNoRoom = false

    /**
     * The gallery zooming into an artwork as it opens ([opening]), or out of it as it closes: the
     * artwork's cell and where its canvas is (window pixels), and closing, a picture of the canvas;
     * the cell shows the artwork turned by [turns] quarter turns clockwise.
     */
    class GalleryZoom(
        val cell: androidx.compose.ui.geometry.Rect,
        val canvas: CanvasPlace,
        val opening: Boolean,
        val picture: androidx.compose.ui.graphics.ImageBitmap? = null,
        val turns: Int = 0,
    )

    /**
     * Where a canvas is on screen: its middle, its size as it is drawn (not turned; window pixels),
     * how far its view turns it (radians, clockwise on screen) and whether it shows mirrored.
     */
    class CanvasPlace(
        val centre: Offset,
        val size: androidx.compose.ui.geometry.Size,
        val angle: Float,
        val mirrored: Boolean,
    )

    var galleryZoom by mutableStateOf<GalleryZoom?>(null)

    /** Where each artwork's thumbnail was last shown in the gallery (window pixels), by its folder's name. */
    val thumbAt = mutableMapOf<String, androidx.compose.ui.geometry.Rect>()

    /** The box each artwork's thumbnail was last shown fitted in (window pixels), by its folder's name. */
    val thumbBoxAt = mutableMapOf<String, androidx.compose.ui.geometry.Rect>()

    /** The gallery's grid, kept so it is where it was when the gallery comes back. */
    val galleryGrid = androidx.compose.foundation.lazy.grid.LazyGridState()

    /** The window's size in pixels, as the gallery last saw it. */
    var windowPx = androidx.compose.ui.unit.IntSize(1, 1)

    /** How many of the gallery's artworks being shared are written, as a share of them. */
    @Volatile
    internal var galleryShared = 0f

    /** Artworks left that are still being saved. */
    internal val closingSaves = java.util.concurrent.atomic.AtomicInteger(0)

    /** When time on the open artwork last started counting (elapsed realtime), 0 while away. */
    internal var activeSince = 0L

    /** What each touch gesture does. */
    val gestures = GestureSettings(app.getSharedPreferences("gestures", android.content.Context.MODE_PRIVATE))

    /** The tablet's own three-finger gesture has been told of this run. */
    internal val fingersTakenTold = TellOnce()

    /** The canvas view's ear for a pen's side button that comes as a key (true: the second), and what it did. */
    var penKeyHeard: ((second: Boolean, action: PenKeyAction) -> Unit)? = null

    /** The canvas view's touch input, for what covers the canvas and passes its touches on (the guide editor). */
    var canvasTouch: ((android.view.MotionEvent) -> Boolean)? = null

    /**
     * Hides the system's bars again (set by the activity). A drag down from the top bar can show
     * them for a moment, and a second drag while they show would pull the notification shade
     * instead of the colour.
     */
    var hideSystemBars: (() -> Unit)? = null

    /** □ is held down: a canvas touch now does what □ + Touch (or □ + Pen) is set to. */
    var modifyHeld = false
        internal set

    // when □ went down, and whether a touch has used it since
    internal var modifyDownAt = 0L
    internal var modifyUsed = false

    /** The middle of the screen (window pixels), for menus a button opens. */
    var screenCentre = Offset(800f, 1280f)

    /** Gesture controls is open. */
    var gestureControlsOpen by mutableStateOf(false)

    /** Where the middle of one of the surround grid's lines falls (window pixels), for screens drawn over the canvas. */
    var gridOrigin by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)

    /** The page Gesture controls was last on: it opens there again. */
    var gesturePage by mutableStateOf(GesturePurpose.EYEDROPPER)

    /** Canvas information is open. */
    var canvasInfoOpen by mutableStateOf(false)

    /**
     * A handle tapped in the guide editor, at [at] (window pixels): a perspective guide's point
     * ([point], its place) offers Delete, the centre of the others Reset.
     */
    data class GuideNodeMenu(val point: Int?, val at: Offset)

    /** The menu of the guide editor's handle tapped, while it shows. */
    var guideNodeMenu by mutableStateOf<GuideNodeMenu?>(null)

    /** Layer Select: the layers drawn under a point (top first), listed there to choose from. */
    var layerChoice by mutableStateOf<Pair<Offset, List<ULong>>?>(null)

    /** The QuickMenu, open around this window point. */
    var quickMenuAt by mutableStateOf<Offset?>(null)

    /** The QuickMenu's menus, oldest first: each a name and six buttons, clockwise from the top, each set to an action (a hold changes it). */
    var quickMenus by mutableStateOf(
        io.github.artcraftandroid.procraft.core.quickMenus(interfacePrefs.getString(QUICK_MENUS, null), interfacePrefs.getString(QUICK_MENU, null)),
    )
        internal set

    /** The menu kept as the active one. */
    var quickMenuActive by mutableStateOf(interfacePrefs.getInt(QUICK_MENU_ACTIVE, 0).coerceIn(0, quickMenus.size - 1))
        internal set

    /** The menu the ring shows this time: a menu just added shows without becoming the active one. */
    var quickMenuShown by mutableStateOf(quickMenuActive)
        internal set

    /** The buttons the ring shows. */
    val quickMenuSlots: List<QuickAction> get() = quickMenus.getOrNull(quickMenuShown)?.slots.orEmpty()

    /**
     * Pen buttons: what the pen's first and second side buttons are set to do, a QuickMenu action
     * each, or null to do what Gesture controls say (as they start).
     */
    var penButtonActions by mutableStateOf(io.github.artcraftandroid.procraft.core.penButtonActions(interfacePrefs.getString(PEN_BUTTONS, null)))
        internal set

    /** How many onion skin frames there were before they were last turned off. */
    internal var onionKept = 1u

    // Layer opacity: a drag across the canvas changes the current layer's opacity, read in the band
    // under the top bar

    /** The layer whose opacity a drag changes, while the mode is on. */
    var opacityLayer by mutableStateOf<LayerInfo?>(null)
        internal set

    /** Its opacity as it is being dragged. */
    var opacityValue by mutableFloatStateOf(1f)
        internal set

    /** The Copy & Paste panel (three fingers swiped down) is open. */
    var copyPasteOpen by mutableStateOf(false)

    /** The corners of a picture being placed on a model, on the window (x, y pairs), while one is. */
    var modelPictureBox by mutableStateOf<List<Float>?>(null)
        internal set

    // ---- time-lapse ----

    internal var timeLapse: TimeLapse? = null
    var timeLapseRecording by mutableStateOf(true)
        internal set

    /** The replay shows over everything. */
    var timeLapseReplaying by mutableStateOf(false)

    /** Something has been recorded (Replay and Export need it). */
    var timeLapseRecorded by mutableStateOf(false)
        internal set

    /** Recording was turned off with something recorded: whether to delete it is asked. */
    var purgeAsking by mutableStateOf(false)
        internal set

    internal var frameJob: Job? = null
    internal var frameStale = false

    /** A time-lapse video is being made: another isn't started over it (they'd share its file). */
    var timeLapseExporting by mutableStateOf(false)
        internal set

    /** An export under way as its card shows it: how far it has come (0..1), and once [done]. */
    data class ExportShown(val progress: Float, val done: Boolean = false)

    /** The export card, while an export runs (null without one). */
    var exportShown by mutableStateOf<ExportShown?>(null)
        internal set

    @Volatile
    internal var exportCancelled = false

    // ---- crop and resize: the shared core edits it (crates/core crop_edit.rs); these show it ----

    /** The rectangle being kept (canvas pixels: left, top, width, height), or null outside crop. */
    var cropRect by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        internal set
    var cropAngle by mutableStateOf(0f)
        internal set
    var cropResample by mutableStateOf(false)
        internal set
    /** With resampling, the size what is kept becomes. */
    var cropSize by mutableStateOf(0 to 0)
        internal set
    var cropAspectLocked by mutableStateOf(false)
        internal set
    var cropSettingsOpen by mutableStateOf(false)
    var cropSnapping by mutableStateOf(false)
        internal set
    /** The canvas's dots per inch, as the crop's settings have it. */
    var cropDpi by mutableStateOf(132f)
        internal set
    /** While a corner or side of the frame is dragged: the size it frames, by the touch. */
    var cropReadout by mutableStateOf<io.github.artcraftandroid.procraft.core.CropReadout?>(null)
        internal set

    // ---- the reference windows ----

    /** What a window shows. A face needs face tracking, which this app doesn't have yet, so an
     *  artwork saved showing one opens on the canvas, and Face isn't offered. */
    enum class ReferenceMode { CANVAS, IMAGE, MODEL }

    /**
     * A Reference window: the artwork's own ([index] 0, which Reference in the Canvas tab shows and
     * hides), or one opened besides it while a plugin lets more than one be open. Each is kept by
     * the core with the artwork: where it is, its size, what it shows and its imported picture.
     */
    inner class ReferenceWindow(val index: Int) {
        var on by mutableStateOf(false)
            private set
        var mode by mutableStateOf(ReferenceMode.CANVAS)
            private set

        /** The whole canvas as it is, kept up to date while the window shows it. */
        var canvas by mutableStateOf<ImageBitmap?>(null)
            private set

        /** [canvas] is the canvas at its own size (not made smaller): its pixels are the canvas's. */
        var canvasWhole by mutableStateOf(false)
            private set
        var image by mutableStateOf<ImageBitmap?>(null)
            private set

        /** How the window shows the canvas and a picture imported, each zoomed and moved by two fingers on its own. */
        var canvasView by mutableStateOf(io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f))
            private set
        var imageView by mutableStateOf(io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f))
            private set

        /** Where the window is (window pixels), and the size of its picture. */
        var offset by mutableStateOf(Offset.Unspecified)
            private set

        /** The window's bars are slid out of the way. */
        var barsHidden by mutableStateOf(false)
            private set
        var size by mutableStateOf(DpSize(REFERENCE_WIDTH_DP.dp, REFERENCE_HEIGHT_DP.dp))
            private set

        /** The screen the window was last laid out on (pixels), and its bars' height together (pixels). */
        private var room by mutableStateOf(androidx.compose.ui.geometry.Size.Unspecified)
        private var bars = 0f

        /**
         * The window's picture as it shows: as big as it was sized, within the window's least and most
         * (the core's), and no bigger than the screen.
         */
        val shown: DpSize get() = fitted(size)

        private fun fitted(size: DpSize): DpSize {
            val room = room
            val (w, h) = if (room.isSpecified) room.width / density to room.height / density else Float.NaN to Float.NaN
            if (room.isSpecified && minOf(w, h) < 600f) {
                val unsized = size == DpSize(REFERENCE_WIDTH_DP.dp, REFERENCE_HEIGHT_DP.dp)
                val targetW = if (unsized) 210f else size.width.value
                val targetH = if (unsized) 170f else size.height.value
                return DpSize(
                    targetW.coerceIn(170f, (w - 16f).coerceAtLeast(170f)).dp,
                    targetH.coerceIn(120f, (h - 140f).coerceAtLeast(120f)).dp,
                )
            }
            val s = io.github.artcraftandroid.procraft.core.referenceWindowSize(size.width.value, size.height.value, w, h)
            return DpSize(s.width.dp, s.height.dp)
        }

        /** Where the window at [at] (pixels) goes to be all on the screen it was last laid out on (its top left, if it is bigger). */
        private fun onScreen(at: Offset): Offset {
            val room = room
            if (!room.isSpecified) return at
            val shown = shown
            val p = io.github.artcraftandroid.procraft.core.referenceWindowFitted(
                at.x, at.y, shown.width.value * density, shown.height.value * density + bars, room.width, room.height,
            )
            return Offset(p.x, p.y)
        }

        private var job: Job? = null
        private var stale = false

        /** The window as the artwork [s] left it, or none if it never had one. */
        fun restore(s: CanvasSession) {
            forget()
            val w = s.referenceWindowAt(index.toUInt()) ?: return
            mode = when (w.shows) {
                io.github.artcraftandroid.procraft.core.ReferenceShows.IMAGE -> ReferenceMode.IMAGE
                io.github.artcraftandroid.procraft.core.ReferenceShows.CANVAS,
                io.github.artcraftandroid.procraft.core.ReferenceShows.FACE -> ReferenceMode.CANVAS
                io.github.artcraftandroid.procraft.core.ReferenceShows.MODEL -> ReferenceMode.MODEL
            }
            if (w.sized) size = DpSize(w.width.dp, w.height.dp)
            if (w.placed) offset = Offset(w.x * density, w.y * density)
            canvasView = w.canvasView
            imageView = w.imageView
            on = w.shown
            viewModelScope.launch {
                val picture = withContext(Dispatchers.IO) {
                    s.referencePictureAt(index.toUInt())?.let { png ->
                        runCatching {
                            // no larger than a picture imported now would be
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
                            var sample = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE) sample *= 2
                            BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        }.getOrNull()
                    }
                }
                if (picture != null && session === s) image = picture.asImageBitmap()
            }
            refresh()
        }

        /** No artwork is open: the window belongs to the canvas, so nothing of it stays for the gallery or the next artwork. */
        fun forget() {
            job?.cancel()
            stale = false
            on = false
            mode = ReferenceMode.CANVAS
            canvas = null
            canvasWhole = false
            image = null
            canvasView = io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f)
            imageView = io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f)
            offset = Offset.Unspecified
            barsHidden = false
            size = DpSize(REFERENCE_WIDTH_DP.dp, REFERENCE_HEIGHT_DP.dp)
        }

        /** The window as it now is, for the core. */
        fun state(): io.github.artcraftandroid.procraft.core.ReferenceWindowState {
            val placed = offset != Offset.Unspecified
            return io.github.artcraftandroid.procraft.core.ReferenceWindowState(
                shown = on,
                shows = when (mode) {
                    ReferenceMode.CANVAS -> io.github.artcraftandroid.procraft.core.ReferenceShows.CANVAS
                    ReferenceMode.IMAGE -> io.github.artcraftandroid.procraft.core.ReferenceShows.IMAGE
                    ReferenceMode.MODEL -> io.github.artcraftandroid.procraft.core.ReferenceShows.MODEL
                },
                sized = size != DpSize(REFERENCE_WIDTH_DP.dp, REFERENCE_HEIGHT_DP.dp),
                width = size.width.value,
                height = size.height.value,
                placed = placed,
                x = if (placed) offset.x / density else 0f,
                y = if (placed) offset.y / density else 0f,
                canvasView = canvasView,
                imageView = imageView,
            )
        }

        /** The window as it now is, kept with the artwork. */
        private fun keep() {
            session?.setReferenceWindowAt(index.toUInt(), state())
        }

        fun changeOn(on: Boolean) {
            this.on = on
            keep()
            if (on) refresh()
        }

        fun changeMode(mode: ReferenceMode) {
            this.mode = mode
            keep()
            refresh()
        }

        /**
         * The window laid out on a screen [width] × [height] (pixels), its bars [bars] (pixels) tall
         * together: the first time in the middle, near the bottom; then where it was put, all of it on
         * the screen (an artwork may have left it on a screen of another shape, or the screen changed:
         * turned, or split). What the artwork keeps changes only as the window is moved or sized.
         */
        fun place(width: Float, height: Float, bars: Float) {
            val room = androidx.compose.ui.geometry.Size(width, height)
            if (this.room != room) this.room = room
            this.bars = bars
            if (offset == Offset.Unspecified) {
                val shown = shown
                val tall = shown.height.value * density + bars
                val compact = minOf(width / density, height / density) < 600f
                offset = if (compact) {
                    Offset(
                        (width - shown.width.value * density - 12f * density).coerceAtLeast(0f),
                        (60f * density).coerceAtMost((height - tall).coerceAtLeast(0f)),
                    )
                } else {
                    Offset((width - shown.width.value * density) / 2f, (height - REFERENCE_BOTTOM_DP * density - tall).coerceAtLeast(0f))
                }
                return
            }
            val at = onScreen(offset)
            if (at != offset) offset = at
        }

        fun move(by: Offset) {
            offset = onScreen(offset + by)
            keep()
        }

        /** A drag on a size handle, from the size the window shows at; from the left one the window's left edge moves. */
        fun resize(dx: Dp, dy: Dp, fromLeft: Boolean) {
            val old = shown
            val new = fitted(DpSize(old.width + if (fromLeft) -dx else dx, old.height + dy))
            if (fromLeft) offset = offset.copy(x = offset.x + (old.width - new.width).value * density)
            size = new
            offset = onScreen(offset)
            keep()
        }

        fun importPicture(uri: android.net.Uri) {
            val s = session
            viewModelScope.launch {
                val image = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolver = getApplication<Application>().contentResolver
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE) sample *= 2
                        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    }.getOrNull()
                }
                if (image == null) {
                    message = "This picture can't be opened."
                } else if (s != null && session === s) {
                    this@ReferenceWindow.image = image.asImageBitmap()
                    imageView = io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f)
                    keep()
                    // kept with the artwork as a PNG, whatever kind of file it came from
                    withContext(Dispatchers.IO) {
                        val png = java.io.ByteArrayOutputStream()
                        if (image.compress(Bitmap.CompressFormat.PNG, 100, png)) s.setReferencePictureAt(index.toUInt(), png.toByteArray())
                    }
                }
            }
        }

        /** A tap on the window's picture: its bars slide away, or back. */
        fun toggleBars() {
            barsHidden = !barsHidden
        }

        /** Two taps on the window's picture: it fits the window again, the bars out of the way. */
        fun fit() {
            val fitted = io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f)
            if (mode == ReferenceMode.IMAGE) imageView = fitted else canvasView = fitted
            barsHidden = true
            keep()
        }

        fun clear() {
            image = null
            imageView = io.github.artcraftandroid.procraft.core.ReferenceView(1f, 0f, 0f)
            session?.clearReferencePictureAt(index.toUInt())
            keep()
        }

        /**
         * Two fingers on the window's picture ([sizes]) moved from [from] to [to] (their middle, pixels
         * in the picture's area), [spread] times as far apart: the picture zooms and moves with them.
         */
        fun pinch(sizes: io.github.artcraftandroid.procraft.core.ReferenceSizes, from: Offset, to: Offset, spread: Float) {
            val image = mode == ReferenceMode.IMAGE
            val view = if (image) imageView else canvasView
            // the model turns under the finger, and doesn't move in the window
            if (mode == ReferenceMode.MODEL) {
                session?.referenceModelTurn(to.x - from.x, to.y - from.y, sizes.windowWidth)
                refresh()
                return
            }
            val next = io.github.artcraftandroid.procraft.core.referenceViewPinched(view, sizes, from.x, from.y, to.x, to.y, spread)
            if (image) imageView = next else canvasView = next
            keep()
        }

        /** Reads the canvas again for the window, at most every so often while changes come. */
        fun refresh() {
            if (!on || (mode != ReferenceMode.CANVAS && mode != ReferenceMode.MODEL)) return
            if (job?.isActive == true) {
                stale = true
                return
            }
            val s = session ?: return
            job = viewModelScope.launch {
                do {
                    stale = false
                    val made = withContext(Dispatchers.Default) {
                        val model = mode == ReferenceMode.MODEL
                        val t = (if (model) s.referenceModelPicture(MODEL_REFERENCE_PIXELS.toUInt()) else s.referenceCanvas(REFERENCE_PIXELS.toUInt()))
                            ?: return@withContext null
                        val bitmap = io.github.artcraftandroid.procraft.ui.CanvasSpace.tag(Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), Bitmap.Config.ARGB_8888))
                        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba))
                        bitmap.asImageBitmap() to (t.width == s.info().width)
                    }
                    if (made != null && session === s) {
                        canvas = made.first
                        canvasWhole = made.second
                    }
                    kotlinx.coroutines.delay(REFERENCE_INTERVAL_MS)
                } while (stale)
            }
        }
    }

    /** The artwork's Reference windows: its own first, then those opened besides it. */
    val referenceWindows = androidx.compose.runtime.mutableStateListOf(ReferenceWindow(0))

    /** Whether the artwork's own window shows (Reference in the Canvas tab). */
    val referenceOn: Boolean get() = referenceWindows.firstOrNull()?.on == true

    // ---- text ----

    /** The text being edited, as it stands; null when no text is being edited. */
    var textInfo by mutableStateOf<TextInfo?>(null)
        internal set

    /** What the hidden text field holds: the text with its caret and selection. */
    var textField by mutableStateOf(TextFieldValue(""))
        internal set

    /** The style sheet (fonts, styles, design, attributes) is open over the keyboard. */
    var textStyleOpen by mutableStateOf(false)

    /**
     * The look the style sheet shows for the letters chosen (the first one's; underline and outline
     * on only when all of them have it), or for what is typed next at the caret.
     */
    var textLook by mutableStateOf<TextRunInfo?>(null)
        internal set

    /** The text as it was when the style sheet opened, for its Cancel. */
    internal var textBeforeStyle: TextInfo? = null

    internal val systemClipboard get() = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)

    /** A stroke landed on a text layer: offer to rasterize it. */
    var askRasterize by mutableStateOf(false)

    internal var textJob: Job? = null
    internal var textGeneration = 0

    /** Whether the keyboard is wanted for the text being edited (a touch on the text asks for it). */
    var textKeyboard by mutableStateOf(true)

    /** Why the current layer turned a change away. */
    enum class Blocked { LOCKED, HIDDEN, EMPTY_GROUP, MESH_HIDDEN, MESH_LOCKED, GROUP_HIDDEN, GROUP, SEVERAL, ALPHA_LOCKED }

    /** The current layer turned a change away, and why: a dialog offers to unlock or show it, or open Layers. */
    var blocked by mutableStateOf<Pair<Blocked, LayerInfo>?>(null)
        internal set

    /** What hides the current layer, while [blocked] says it is hidden. */
    var blockedBy by mutableStateOf<io.github.artcraftandroid.procraft.core.HiddenBy?>(null)
        internal set

    // editing the library

    /** The set whose name is being edited in place. */
    var renamingSet by mutableStateOf<Int?>(null)

    /** The studio is open on a brush just made: cancelling throws it away. */
    internal var studioNew = false

    /** The brush whose name is being edited in place, from its menu (its place, "set-index"). */
    var renamingBrush by mutableStateOf<String?>(null)

    /** The brushes of the shown set picked by a swipe right, by place ("set-index"). */
    val pickedBrushes = androidx.compose.runtime.mutableStateListOf<String>()

    /** One brush is picked, in the current brush's set, and neither is a pair already. */
    val canCombinePicked: Boolean
        get() {
            val s = session ?: return false
            val c = currentBrush ?: return false
            val (set, index) = pickedBrushes.singleOrNull()?.let(this::pickedPlace) ?: return false
            return set == c.set && !s.brushIsDual(set, index) && !s.brushIsDual(c.set, c.index)
        }

    /** Every brush library, the one in use among them: for the libraries' cards and the title. */
    var brushLibraries by mutableStateOf<List<io.github.artcraftandroid.procraft.core.BrushLibraryInfo>>(emptyList())
        internal set

    /** The library popover shows every library's card rather than the one in use. */
    var showingLibraries by mutableStateOf(false)

    /** The library whose name is being edited (its place), if one is. */
    var renamingLibrary by mutableStateOf<Int?>(null)

    /** The library to delete (its place), once that is confirmed. */
    var deletingLibrary by mutableStateOf<Int?>(null)

    /** The library in use's name, and its place. */
    val libraryName: String get() = brushLibraries.firstOrNull { it.current }?.name ?: "Brush library"
    val libraryPlace: Int get() = brushLibraries.indexOfFirst { it.current }.coerceAtLeast(0)

    /** Library cards' brush strokes, drawn small, by "library-set-index". */
    val cardPreviews = mutableStateMapOf<String, ImageBitmap>()
    internal val cardPreviewsLoading = HashSet<String>()

    /** Brushes read from files, waiting to join the library. */
    internal val brushesRead = mutableListOf<io.github.artcraftandroid.procraft.core.BrushesRead>()

    /** The library's Delete question is up (it holds a brush or set by its place). */
    var brushQuestionOpen = false
        set(open) {
            field = open
            if (!open) addBrushesRead()
        }

    /** The reading of the files kept for a canvas, while it goes on. */
    internal var pendingBrushesReading: Job? = null

    internal val pendingBrushDir get() = File(getApplication<Application>().filesDir, "pending-brushes")

    /** The brush chosen before the current one (set, place in it), for Previous brush. */
    internal var lastBrush: Pair<UInt, UInt>? = null

    /** The library couldn't be kept and that was told: not again until it is kept once more. */
    internal var libraryFailing = false

    /** The studio's brush is a dual brush, and its second part shows. */
    var studioDual by mutableStateOf(false)
        internal set
    var studioSecond by mutableStateOf(false)
        internal set
    /** Its two parts' sample strokes, and how they combine. */
    var studioParts by mutableStateOf<List<ImageBitmap?>>(listOf(null, null))
        internal set
    var studioCombineMode by androidx.compose.runtime.mutableIntStateOf(0)
        internal set
    /** The page the part not shown was left on. */
    internal var otherPartTab = 0
    internal var studioPartsJob: Job? = null

    /** Who made the studio's brush, and when its reset point was made: its About page. */
    class BrushAbout(val author: String, val picture: ImageBitmap?, val signature: ImageBitmap?, val resetMade: Long?)

    var studioAbout by mutableStateOf<BrushAbout?>(null)
        internal set

    // the studio brush's shape, grain and material images

    /** The images the studio brush's sources use (white on black), by kind, for their rows; none for the round tip, no grain or a material's amount all over. */
    var studioSources by mutableStateOf<Map<SourceKind, ImageBitmap>>(emptyMap())
        internal set

    /** The editor open for the studio brush's source of this kind, if one is. */
    var sourceEditing by mutableStateOf<SourceKind?>(null)

    /** The shipped shape and grain images, by name, for the source library. */
    val shapeLibrary by lazy { io.github.artcraftandroid.procraft.core.sourceLibrary(false).map { it.name to grayBitmap(it.pixels) } }
    val grainLibrary by lazy { io.github.artcraftandroid.procraft.core.sourceLibrary(true).map { it.name to grayBitmap(it.pixels) } }

    /** The same images as big as the landscape studio's square shows them. */
    var studioSourcesBig by mutableStateOf<Map<SourceKind, ImageBitmap>>(emptyMap())
        internal set

    /** The pad shown as a sphere with the brush's materials (the Materials page, or the 3D preview on). */
    var padSphere = false
        internal set

    /** The pad menu's 3D preview: the pad a sphere on every page, kept from one session to the next. */
    internal var pad3dState by mutableStateOf(interfacePrefs.getBoolean(PAD_3D, false))
    var pad3d: Boolean
        get() = pad3dState
        set(on) {
            pad3dState = on
            interfacePrefs.edit().putBoolean(PAD_3D, on).apply()
        }

    /** Whether a plugin sets brushes at random: the studio shows Randomise. */
    val randomisesBrushes: Boolean
        get() {
            // read again as the plugins change
            pluginList
            return plugins.randomisesBrushes()
        }

    /** The sample strokes are drawn one at a time (the core draws them so), waited for here without holding a thread. */
    internal val previewTurn = Mutex()

    /** The current brush's saved sizes and opacities: the side sliders' notches (0..1). */
    var sizeNotches by mutableStateOf<List<Float>>(emptyList())
        internal set
    var opacityNotches by mutableStateOf<List<Float>>(emptyList())
        internal set

    /** The session whose library a write yet to start will keep. */
    internal val libraryKeepWaiting = java.util.concurrent.atomic.AtomicReference<CanvasSession?>(null)

    /** Which slider's panel shows beside it: the size's or the opacity's. */
    enum class SliderPanel { SIZE, OPACITY }

    /**
     * The panel beside a slider: it comes up as the slider is dragged or held, and stays up when
     * the finger lifts, for its + to be tapped, until the next touch off it and the side bar
     * ([keepsSliderPanel]), a tap on the slider's thumb or a tap elsewhere on its track.
     */
    var sliderPanel by mutableStateOf<SliderPanel?>(null)

    /** Where the side bar and a slider's panel last were on the window (its own pixels, from its corner). */
    var sideBarOnWindow = androidx.compose.ui.geometry.Rect.Zero
    var sliderPanelOnWindow = androidx.compose.ui.geometry.Rect.Zero

    /**
     * The flattened picture as a PNG (or a JPEG on white) in the cache's exports folder, for
     * sharing; null when it could not be made.
     */
    /** What Share can write besides JPEG and PNG. */
    enum class Export(val extension: String, val mime: String) {
        PSD("psd", "image/vnd.adobe.photoshop"),
        PDF("pdf", "application/pdf"),
        TIFF("tif", "image/tiff"),
        LAYERS_PDF("pdf", "application/pdf"),
        LAYER_PNGS("png", "image/png"),
        /** A model: OBJ, its material file and its textures, zipped. */
        MODEL_OBJ("zip", "application/zip"),
        MODEL_ARTWORK("procraft", "application/octet-stream"),
        /** A model as a USDZ package. */
        MODEL_USDZ("usdz", "model/vnd.usdz+zip"),
        /** A model's texture sets, a PNG each. */
        MODEL_TEXTURES("png", "image/png"),
        /** The model as shown, as a PNG, a JPEG or a TIFF. */
        MODEL_RENDER("png", "image/png"),
        MODEL_JPEG("jpg", "image/jpeg"),
        MODEL_TIFF("tif", "image/tiff"),
        /** A plugin's artwork, its shapes kept. */
        SVG("svg", "image/svg+xml"),
    }

    /** What stops the export under way, if it can be stopped (see [withCard]). */
    @Volatile
    internal var exportStops: (() -> Unit)? = null

    /** The model's turntable being set up to share (Share Image > Animated ...), if it is. */
    var modelAnimatedExport by mutableStateOf<Animated?>(null)

    /** How the model's turntable goes, and its size: 720p, 1080p, 4K or Square (kept while the app runs). */
    var turntable by mutableStateOf(io.github.artcraftandroid.procraft.core.Turntable(swing = false, seconds = 2f, zoom = 0.5f, ease = 1f, environment = true))
    var turntableSize by mutableStateOf(0)

    /** The layers as an animation, a frame each: the formats, by name, extension and type. */
    enum class Animated(val title: String, val extension: String, val mime: String) {
        GIF("Animated GIF", "gif", "image/gif"),
        PNG("Animated PNG", "png", "image/png"),
        MP4("Animated MP4", "mp4", "video/mp4"),
        HEVC("Animated HEVC", "mp4", "video/mp4"),
        ;

        /** Made by the system's encoder a frame at a time (its progress counted here), not by the core. */
        val video get() = this == MP4 || this == HEVC
    }

    /** The animated export being set up, if any. */
    var animatedExport by mutableStateOf<Animated?>(null)

    /** The artwork was opened from the gallery's Share only for [animatedExport]: closing it goes back. */
    internal var exportingFromGallery = false

    /** One animation export at a time: they write the same file. */
    internal val animationExports = Mutex()

    internal var naming: kotlinx.coroutines.Job? = null
    internal var namedAt = 0L

    /** The library's brush swiped open to its buttons ("set-index"), if one is. */
    var swipedBrush by mutableStateOf<String?>(null)

    /**
     * A passing message under the top bar (what undo and redo did, and the like); [id] tells
     * repeats apart; a [warning] stays longer.
     */
    data class Notice(val text: String, val id: Long, val warning: Boolean = false)

    var notice by mutableStateOf<Notice?>(null)
        internal set

    internal var notices = 0L

    /** How a selection joins the one there is: selecting by colour always adds (Remove greyed). */
    internal val join
        get() = if (selectionRemoves && selectionKind != SelectionKind.AUTOMATIC) SelectionJoin.REMOVE else SelectionJoin.ADD

    /** The freehand path is read at the next frame: a pen sends many moves a frame, each a longer path. */
    internal var freehandDue = false

    /** The threshold slid last and not yet shown: shown once a frame, however many moves come in one. */
    internal var colourDue: Pair<Offset, Float>? = null

    // ColorDrop: the colour dragged from the colour button (or a swatch) and dropped on the canvas
    // fills the area around where it lands

    /** What the ColorDrop band under the top bar shows. */
    enum class DropBand { NONE, DRAGGING, DROPPED, FILLING }

    var dropBand by mutableStateOf(DropBand.NONE)
        internal set

    /** The dragged colour's dot (window pixels) and its colour (0xRRGGBB), while it is dragged. */
    var dropDot by mutableStateOf<Pair<Offset, Int>?>(null)
        internal set

    /** The dragged colour as it is drawn (the core's spring), while it is dragged. */
    var dropBlob by mutableStateOf<io.github.artcraftandroid.procraft.core.ColourBlob?>(null)
        internal set

    /** The colour button's centre and the side bar, where they are (window pixels): over the bars a dragged colour shrinks the further it is from the button. */
    var dropButton: Offset? = null
    var sideBarBounds: androidx.compose.ui.geometry.Rect? = null

    /** The threshold the colour fills with (0..1), kept from one drop to the next. */
    var dropThreshold by mutableFloatStateOf(transformPrefs.getFloat(DROP_THRESHOLD, 0.5f))
        internal set

    /** Whether the threshold is being slid: its row shows under the band. */
    var dropSliding by mutableStateOf(false)
        internal set

    /** Where a dragged colour let go fills nothing (the colour panel, while open), window pixels. */
    var dropAvoid: androidx.compose.ui.geometry.Rect? = null

    /** The swatch's colour when a swatch was dropped (filling goes on with it); else the current colour. */
    internal var dropSwatch: Int? = null
    internal var dropBandTimer: kotlinx.coroutines.Job? = null

    /** The colour being dragged: where it is and whether it has come to rest (the core's rules). */
    internal var colourDrag: io.github.artcraftandroid.procraft.core.ColourDrag? = null

    /** The dragged colour came to rest and its slid threshold has begun filling (or tried to, and couldn't). */
    internal var dropPoured = false
    internal var dropPourRefused = false

    /** A fill a plugin is finishing, on a thread of its own: nothing is touched until it is done. */
    var fillFinishing by mutableStateOf(false)
        internal set

    /** A fill's work (and feathering's), in order, off the main thread (a slid threshold redoes the whole fill). */
    internal val pourThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** The latest of each kind of change waiting for the fill's thread (a threshold, a point, a colour). */
    internal val pourLatest = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    // Recolor: the area under a reticle takes the current colour. Dragged about, the fill follows it;
    // a tap keeps the fill and starts again where it lands

    /** Recolor is on. */
    var recoloring by mutableStateOf(false)
        internal set

    /** Where the reticle is, canvas pixels. */
    var recolorPoint by mutableStateOf<Offset?>(null)
        internal set

    /** Recolor's threshold (its Flood slider, 0..1), kept from one time to the next. */
    var recolorThreshold by mutableFloatStateOf(transformPrefs.getFloat(RECOLOR_THRESHOLD, 0f))
        internal set

    /**
     * Numeric entry open beside the node at window point [at]: for the box's angle (the rotation
     * handle) or its size; to the node's right when it is on the box's right side ([right]), else to its left.
     */
    data class TransformNumeric(val angle: Boolean, val at: Offset, val right: Boolean = false)

    var transformNumeric by mutableStateOf<TransformNumeric?>(null)

    internal var settling: kotlinx.coroutines.Job? = null

    // ---- Animation Assist ----

    /** Animation Assist's settings while it is on. */
    var animation by mutableStateOf<io.github.artcraftandroid.procraft.core.AnimationSettings?>(null)
        internal set

    /** The frames' layers, bottom first, and which one is current. */
    var animationFrames by mutableStateOf<List<ULong>>(emptyList())
        internal set

    /** The timeline's places that play: all but a background frame's and a foreground frame's. */
    var playedSlots by mutableStateOf<List<Int>>(emptyList())
        internal set
    var currentFrame by mutableStateOf<Int?>(null)
        internal set

    /** The timeline's places: each frame's index, again for each frame it is held. */
    var frameSlots by mutableStateOf<List<Int>>(emptyList())
        internal set

    /** The frame and how far into its hold the timeline was last left. */
    internal var heldAt by mutableStateOf(0 to 0)

    /** How far into its hold the current frame is shown (0: the frame itself). */
    val heldPlace: Int
        get() {
            val f = currentFrame ?: return 0
            return if (heldAt.first == f) heldAt.second.coerceAtMost(frameSlots.count { it == f } - 1).coerceAtLeast(0) else 0
        }

    /** The timeline's place in the middle. */
    val currentSlot: Int?
        get() = currentFrame?.let { f -> frameSlots.indexOf(f).takeIf { it >= 0 }?.plus(heldPlace) }

    /** Playing: the timeline's place shown. */
    var playingSlot by mutableStateOf<Int?>(null)
        internal set

    /** Each frame's picture for the timeline. */
    val frameThumbnails = mutableStateMapOf<ULong, ImageBitmap>()

    /** Playing: the frame shown. */
    var playingFrame by mutableStateOf<Int?>(null)
        internal set
    internal var playJob: Job? = null

    var animationSettingsOpen by mutableStateOf(false)
    var frameOptionsOpen by mutableStateOf(false)

    internal var frameThumbJob: Job? = null

    var holdVersion by mutableStateOf(0)
        internal set

    /** How long a dragged row is held over another to go onto it when let go, seconds. */
    val rowHoldSeconds = io.github.artcraftandroid.procraft.core.rowHoldSeconds()

    /**
     * Layers lifted out of the layers panel by a finger ([pointer]) that holds them while it goes
     * on, as the other hand goes to the gallery and opens another artwork, until it lets them go:
     * [bundle] holds them apart from the artwork they came from ([from], where they were the row
     * of [layer]). Shown as a row ([name], [thumbnail], [items] if several) held at [grab] from its
     * corner.
     */
    class HeldLayers(
        val bundle: io.github.artcraftandroid.procraft.core.LayerBundle,
        val layer: ULong,
        val from: File?,
        val pointer: androidx.compose.ui.input.pointer.PointerId,
        val name: String,
        val items: Int,
        val thumbnail: ImageBitmap?,
        val grab: Offset,
    )

    /** The layers held out of the panel, while a finger holds them. */
    var heldLayers by mutableStateOf<HeldLayers?>(null)
        internal set

    /** Where the finger holding [heldLayers] is, in the window. */
    var heldLayersAt by mutableStateOf(Offset.Zero)
        internal set

    /** Where each finger down is in the window, as the screen's root saw it last. */
    val fingersAt = HashMap<androidx.compose.ui.input.pointer.PointerId, Offset>()

    /** The layers panel's rows and its box in the window, while it is open: where held layers drop. */
    var layerRows: Map<ULong, androidx.compose.ui.geometry.Rect> = emptyMap()
    var layersBox: androidx.compose.ui.geometry.Rect? = null

    /** Delete on a locked layer's row, told about rather than done (as measured). */
    var lockedDeleteTold by mutableStateOf(false)

    /** Whether layers are picked to act on with the current one. */
    val anyPicked: Boolean get() = layers.any { it.picked }

    /** The Layers panel shows the colour panel, picking the background's colour. */
    var backgroundPicking by mutableStateOf(false)
        internal set

    override fun onCleared() {
        runCatching { systemClipboard?.removePrimaryClipChangedListener(clipChanged) }
        strokeSound.follow(null, false)
        saveNow()
        session?.stopAutosave()
        session = null
        pourThread.shutdown()
    }

    companion object {
        /** A finished export's check is drawn and shown this long before its card goes (ms, as measured). */
        const val EXPORT_DONE_MS = 450L

        /** What the replay and the export say when a frame isn't kept at all (not one this device can't decode). */
        const val TIME_LAPSE_MISSING = "Part of the time-lapse is missing from the artwork."

        /** How often the export card asks the core how far an export has come (ms). */
        internal const val EXPORT_POLL_MS = 50L

        /**
         * The import card's pie: the copy in fills this share of it; while the file is read it
         * creeps on towards [IMPORT_CREEP_TO], this share of the way left each poll.
         */
        internal const val IMPORT_COPY_SHARE = 0.6f

        /** Room left on the device past a file's size before it is copied in (bytes). */
        internal const val COPY_ROOM_SPARE = 64L shl 20

        /** Told once when the device has no room for a file brought in. */
        internal const val NO_ROOM_TO_IMPORT = "There isn't enough room on this device to import this file."
        internal const val IMPORT_CREEP_TO = 0.95f
        internal const val IMPORT_CREEP = 0.03f

        /** A model's turntable: its frame rate, and its sizes (720p, 1080p, 4K, Square). */
        const val TURNTABLE_FPS = 30
        val TURNTABLE_SIZES = listOf(1280 to 720, 1920 to 1080, 3840 to 2160, 1080 to 1080)

        /** Model files the gallery's Import opens to paint on. */
        val MODEL_EXTENSIONS = listOf("obj", "usdz", "usdc", "usda", "usd")

        /** The environment a model is lit by unless another is chosen (an asset in environments/). */
        const val DEFAULT_ENVIRONMENT = "mountain"

        /** The environments to light a model by, as the Lighting Studio lists them (assets in environments/). */
        val ENVIRONMENTS = listOf("studio", "coast", "auditorium", "city", "nightlife", "portside", "industrial", "sunrise", "beach", "mountain", "daytime")

        /** While the view settles, what is drawn over the canvas is moved this often (ms), and a little past its end (ns). */
        const val FRAME_MS = 16L
        const val SETTLE_MARGIN_NS = 50_000_000L

        /** The clone source's ring (points from its centre). */
        const val CLONE_PUCK_RADIUS_DP = 30f
        /** Each glitch kind's sliders as they start: its own two (diverge three), then the zoom. */
        val GLITCH_DEFAULTS = mapOf(
            GlitchKind.ARTIFACT to listOf(1f, 0.09f, 1f),
            GlitchKind.WAVE to listOf(0.2f, 0.3f, 1f),
            GlitchKind.SIGNAL to listOf(0.8f, 0.19f, 1f),
            GlitchKind.DIVERGE to listOf(0.98f, -0.5f, 0.85f, 0.5f),
        )
        const val BRUSH_CHANGES = "changes"
        /** Where earlier versions kept the library (now in a file). */
        const val BRUSH_LIBRARY = "library"
        /** How big the studio's Shape and Grain Source rows show their image (pixels across). */
        const val SOURCE_ROW_PX = 88
        /** And the landscape studio's square of it. */
        const val SOURCE_BOX_PX = 566
        /** Where earlier versions kept the brushes in use, each set's last choice and each brush's sliders. */
        const val BRUSH_CHOICES = "choices"

        /** And how many brushes of each shipped set the kept library had had the chance to hold. */
        const val BRUSHES_SEEN = "brushes_seen"
        const val MAX_CANVAS_EDGE = 16384
        /** How much smaller than fitted to the screen the canvas is while cropping. */
        /** The reference window: its first place and picture size, its limits, and how sharp and how often its canvas is read. */
        /** The reference window's picture at first, and its gap to the screen's bottom. */
        const val REFERENCE_WIDTH_DP = 350f
        const val REFERENCE_HEIGHT_DP = 423f
        const val REFERENCE_BOTTOM_DP = 50f
        const val REFERENCE_PIXELS = 700
        const val REFERENCE_INTERVAL_MS = 300L
        /** How far down and right of the window it is opened from another Reference window opens (dp). */
        const val REFERENCE_STEP_DP = 24f

        /** The window the eyedropper shows (magnified twice), dp across. */
        const val PICK_WINDOW_DP = 54f
        const val RIGHT_HANDED = "right_handed"
        const val COLOR_MODE = "color_mode"
        const val COLOUR_MODEL = "colour_model"

        /** How often a hovering brush cursor reads its size again, ms. */
        const val CURSOR_REREAD_MS = 100L

        /** The hover fill's stamp is made at most this many pixels across (larger, it is drawn bigger). */
        const val CURSOR_FILL_MOST = 256

        /** A hover fill whose stamp is nowhere stronger than this (of 255) is too faint to see. */
        const val CURSOR_FILL_FAINTEST = 8
        const val ACTIONS_TAB = "actions_tab"
        const val STUDIO_TAB = "studio_tab"
        const val PAD_3D = "pad_3d_preview"
        const val RAPID_UNDO_DELAY = "rapid_undo_delay"
        const val LIGHT_INTERFACE = "light_interface"
        const val BRUSH_CURSOR = "brush_cursor"
        const val CURSOR_SHOWS = "cursor_shows"
        const val CURSOR_STYLE = "cursor_style"
        const val BRUSH_ON_SCREEN = "brush_on_screen"
        const val PROJECT_CANVAS = "project_canvas"
        const val PALETTE_CARDS = "palette_cards"
        const val COLOR_HISTORY_SHOWN = "color_history_shown"
        const val ORIENTATION_MEMORY = "orientation_memory"
        const val FIT_INSIDE = "fit_inside"
        const val NO_TIME_LAPSE = "no_time_lapse"
        const val SIMPLIFIED_UNDOS = "simplified_undos"

        /** The picture of the canvas the gallery zooms out of, at most this many pixels on a side. */
        const val CLOSING_PICTURE_SIDE = 768
        const val COLOR_NAMES = "color_names"
        const val FEEDBACK_SOUNDS = "feedback_sounds"
        const val SINGLE_TOUCH_COMPANION = "single_touch_companion"
        const val TAP_ASSISTANCE = "tap_assistance"

        /** Wide Color: Display P3 canvases show in wide colour where the screen takes it (see [wideColorByDefault]). */
        const val WIDE_COLOR = "wide_color"

        /** The version whose start last said the 32-bit build isn't the one for this tablet. */
        const val WRONG_BUILD_SAID = "wrong_build_said"

        /**
         * The layered package's file extensions, and their names in the Share lists: ours, and the
         * other app's (the same package, so either opens in both). Files are told apart by what is
         * in them, so `.pcraft` ones from before and other apps' `.procraft` zips still open.
         */
        const val OWN_EXTENSION = "procraft"
        const val OWN_LABEL = "ProCraft"
        internal const val MODEL_REFERENCE_PIXELS = 720
        internal const val SELECTION_X_RAY = "selection_x_ray"
        const val PACKAGE_EXTENSION = "procreate"

        /** Brush and brush set files, as other painting apps share them. */
        const val BRUSH_EXTENSION = "brush"
        const val BRUSH_SET_EXTENSION = "brushset"
        const val BRUSH_LIBRARY_EXTENSION = "brushlibrary"
        /** Other apps' brush files: Photoshop's, GIMP's brushes, pipes and generated brushes, Krita's presets and bundles, Clip Studio's sub tools and their groups. */
        val OTHER_BRUSH_EXTENSIONS = listOf("abr", "gbr", "gih", "vbr", "kpp", "bundle", "sut", "sutg")
        val PACKAGE_LABEL = PACKAGE_EXTENSION.replaceFirstChar(Char::titlecase)

        /** Several artworks shared as one PDF from the gallery itself (not a stack) are named so. */
        const val GROUP_NAME = "Artworks"

        /** What the gallery's Share offers, in order: still pictures, then animations. */
        val SHARE_FORMATS = listOf(OWN_LABEL, PACKAGE_LABEL, "PSD", "PDF", "JPEG", "PNG", "WebP", "TIFF") + Animated.entries.map { it.title }

        /** How often, and for how long at most, opening waits for the canvas's first frame, milliseconds. */
        const val CANVAS_POLL_MS = 32L
        const val CANVAS_DRAWN_LIMIT_MS = 15_000L

        /** How often the open artwork's autosave is asked whether it failed. */
        const val SAVE_FAILURE_POLL_MS = 3_000L

        /** How near a slider value is on a notch (and is pulled onto it), and the most a slider takes. */
        const val NOTCH_NEAR = 0.04f

        /** The least time between two colour names while the colour keeps changing, milliseconds. */
        const val COLOR_NAME_GAP_MS = 150L

        /** How often an open artwork is saved when it has changed, milliseconds. */
        const val SPEED_DISTANCE = "speed_distance"

        /** What an artwork dragged out of the gallery becomes (an index into [DRAG_FORMATS]), and a picture's format when one is wanted. */
        const val DRAG_FORMAT = "drag_format"
        const val DRAG_IMAGE_FORMAT = "drag_image_format"
        val DRAG_FORMATS = listOf(OWN_LABEL, "PSD", "JPEG", "PNG", "TIFF")
        val DRAG_IMAGE_FORMATS = listOf("JPEG", "PNG", "TIFF")
        /** The QuickMenu's buttons as kept before there were several menus (read once, into the first). */
        const val QUICK_MENU = "quick_menu"
        const val QUICK_MENUS = "quick_menus"
        const val PEN_BUTTONS = "pen_buttons"
        const val QUICK_MENU_ACTIVE = "quick_menu_active"
        /** A press of □ this short, used by nothing, is a tap. */
        const val MODIFY_TAP_MS = 500L
        const val PRESETS = "presets"
        const val INPUT_STABILIZATION = "input_stabilization"
        const val INPUT_MOTION = "input_motion_filtering"
        const val INPUT_EXPRESSION = "input_motion_expression"
        const val INPUT_PRESSURE_SMOOTHING = "input_pressure_smoothing"
        const val INPUT_CURVE = "input_pressure_curve"
        const val INPUT_TIP_ATTACHED = "input_tip_attached"
        const val DRAWING_TABLET_MODE = "drawing_tablet_mode"
        const val PEN_PRESSURE_ENABLED = "pen_pressure_enabled"
        const val PEN_PRESSURE_SENSITIVITY = "pen_pressure_sensitivity"
        const val PEN_PRESSURE_MIN = "pen_pressure_min"
        const val HIDE_TABLET_ARROW = "hide_tablet_arrow"
        const val SHOW_TABLET_NAV_POINTER = "show_tablet_nav_pointer"
        const val GALLERY_SORT = "gallery_sort"
        const val HAPTIC_FEEDBACK = "haptic_feedback"
        const val VOLUME_KEY_MODE = "volume_key_mode"
        const val CANVAS_VIEW_HUD = "canvas_view_hud"
        const val QUICK_FLIP_BUTTON = "quick_flip_button"
        const val AUTO_HIDE_UI = "auto_hide_ui"
        const val UPDATE_AUTO_CHECK = "update_auto_check"
        const val UPDATE_INCLUDE_PRE = "update_include_prerelease"
        const val UPDATE_LAST_CHECK_MS = "update_last_check_ms"
        const val UPDATE_NEXT_ALLOWED_MS = "update_next_allowed_ms"
        const val UPDATE_RATE_LIMIT_RESET_MS = "update_rate_limit_reset_ms"
        const val UPDATE_DISMISSED_TAG = "update_dismissed_tag"
        /** Frames a second an animation plays at (Animation Assist's own when it is on). */
        const val ANIMATION_FPS = 15
        /** The timeline's frame pictures' longest side, pixels; a frame's longest hold. */
        const val FRAME_THUMB_SIDE = 96
        const val MAX_HOLD = 60
        /** A Web Ready animation's longest side, pixels. */
        const val WEB_READY_SIDE = 480
        /** An animated video's longest side at most, pixels. */
        const val MAX_VIDEO_SIDE = 4096
        const val RAPID_UNDO_MIN_S = 0.1f
        const val RAPID_UNDO_MAX_S = 1.5f
        const val SELECTION_VISIBILITY = "selection_visibility"
        /** The largest side a picture brought in is decoded at (pixels). */
        const val MAX_PICTURE = 4096
        /** How long a stroke is held still to snap (milliseconds), how still (points), and how near a handle a touch takes it. */
        const val QUICKSHAPE_MS = 650L
        const val QUICKSHAPE_STILL_DP = 6f
        const val QUICKSHAPE_REACH_DP = 30f

        /** The drawing pad's colours (0xRRGGBB), the white one, and its background's grey. */
        val PAD_COLORS = listOf(0xF83045, 0xFCBD03, 0x85D921, 0x2BB5E6, 0x4956A5, 0xDA3D92, 0xFFFFFF, 0x000000)
        const val PAD_WHITE = 6
        const val PAD_BLACK = 7
        const val PAD_BACK = 31f / 255f
        /** With the light interface the pad is light and paints in black. */
        const val PAD_BACK_LIGHT = 243f / 255f
        /** How long after a studio control stops moving its sample stroke is drawn again (milliseconds). */
        const val STUDIO_PREVIEW_DELAY_MS = 60L
        /** The first colour: a saturated blue. */
        const val DEFAULT_HUE = 0.59f
        const val KEPT_HUE = "colour.hue"
        const val KEPT_SATURATION = "colour.saturation"
        const val KEPT_BRIGHTNESS = "colour.brightness"
        const val KEPT_MASK_HUE = "colour.mask.hue"
        const val KEPT_MASK_SATURATION = "colour.mask.saturation"
        const val KEPT_MASK_BRIGHTNESS = "colour.mask.brightness"
        /** Layer thumbnails are read at about the size they show (93 dp at 2 px a dp). */
        const val THUMB_SIDE = 192
        const val HISTORY_SIZE = 10
        internal const val TRANSFORM_KIND = "kind"
        internal const val INTERPOLATION = "interpolation"
        internal const val MAGNETICS = "magnetics"
        internal const val SNAPPING = "snapping"
        internal const val SNAP_DISTANCE = "snapDistance"
        internal const val SNAP_VELOCITY = "snapVelocity"
        internal const val ADVANCED_MESH = "advancedMesh"
        internal const val SELECTION_KIND = "selectionKind"
        internal const val SELECTION_THRESHOLD = "selectionThreshold"
        internal const val DROP_THRESHOLD = "dropThreshold"

        /** How long a widget's tap waits for the window to be measured before opening anyway. */
        internal const val WINDOW_WAIT_MS = 3000L
        internal const val RECOLOR_THRESHOLD = "recolorThreshold"

        /** What undo and redo call a Recolor fill. */

        /** A video's frames come in at Animation Assist's starting frame rate unless it is on. */
        internal const val DEFAULT_FPS = 15
        /** The most frames a video brings in. */
        internal const val MAX_VIDEO_FRAMES = 999

        /** A PDF's pages come in at this resolution, their long side at most [PDF_MOST_SIDE] pixels. */
        internal const val PDF_DPI = 300f
        internal const val PDF_MOST_SIDE = 4096

        /** How long the band offers Continue filling after a drop. */
        internal const val DROP_BAND_MS = 2_500L
        /** How long a fill being finished by a plugin goes unmentioned. */
        internal const val FILL_SAYS_MS = 500L

        fun hsvToRgb(h: Float, s: Float, v: Float): Int {
            val hsv = floatArrayOf((h * 360f) % 360f, s, v)
            return android.graphics.Color.HSVToColor(hsv) and 0xFFFFFF
        }
    }
}
