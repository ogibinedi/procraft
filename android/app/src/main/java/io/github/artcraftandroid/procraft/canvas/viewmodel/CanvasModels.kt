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


/** Which panel is open over the canvas. */
private const val TAG = "ProCraft"

/** [CanvasViewModel.shownSet] while the library shows the Recent set. */
const val RECENT_SET = -1

/** Where the set at place [set] is once the set at [from] has moved to place [to]. */
internal fun placeAfterSetMove(set: Int, from: Int, to: Int): Int = when (set) {
    from -> to
    in (from + 1)..to -> set - 1
    in to until from -> set + 1
    else -> set
}

/**
 * Whether a touch at [at] leaves a slider's panel up: on the side bar ([bar]) or the panel beside
 * it ([panel]), which reaches past the bar. Anywhere else the panel goes, and the touch goes on to
 * do what it does there (a stroke on the canvas is drawn).
 */
internal fun sliderPanelKeeps(at: androidx.compose.ui.geometry.Offset, bar: androidx.compose.ui.geometry.Rect, panel: androidx.compose.ui.geometry.Rect): Boolean =
    bar.contains(at) || panel.contains(at)

/** Whether [b] is the brush in use ([current]): the one the library shows highlighted, and opens in the studio when tapped. */
internal fun isBrushInUse(b: BrushInfo, current: BrushInfo?): Boolean = current != null && current.set == b.set && current.index == b.index

enum class Panel { NONE, LAYERS, COLOR, BRUSHES, ACTIONS, ADJUSTMENTS }

/**
 * The eyedropper while it is open: the window point (pixels), what is shown around it, the colour it would pick, and
 * how far above the point its loupe is centred (dp: none for a pen, so its middle is under the tip).
 */
class PickState(val x: Float, val y: Float, val image: ImageBitmap?, val rgb: Int?, val fromReference: Boolean = false, val lift: Float = LOUPE_LIFT_DP)

/** How far above a finger the eyedropper's loupe is centred (dp), clear of it. */
const val LOUPE_LIFT_DP = 40f

/** How far above the point a [pen]'s eyedropper loupe is centred, or a finger's (dp). */
fun loupeLift(pen: Boolean) = if (pen) 0f else LOUPE_LIFT_DP

/** The colour panel's ways of choosing a colour. */
enum class ColorMode { DISC, CLASSIC, HARMONY, VALUE, PALETTES }

/** Companion colours in the harmony mode, as hue turns (degrees) from the chosen colour. */
enum class HarmonyKind(val label: String, val turns: List<Float>) {
    COMPLEMENTARY("Complementary", listOf(180f)),
    SPLIT_COMPLEMENTARY("Split Complementary", listOf(150f, 210f)),
    ANALOGOUS("Analogous", listOf(30f, -30f)),
    TRIADIC("Triadic", listOf(120f, 240f)),
    TETRADIC("Tetradic", listOf(90f, 180f, 270f)),
}

/** The gallery of artworks, or one artwork open on the canvas. */
enum class Screen { GALLERY, CANVAS }

/** A popover over the transform toolbar. */
enum class TransformPopup { NONE, SNAPPING, INTERPOLATION }

/** The selection tool's ways of making a selection. */
enum class SelectionKind(val label: String) { AUTOMATIC("Automatic"), FREEHAND("Freehand"), RECTANGLE("Rectangle"), ELLIPSE("Ellipse") }

/** A popover over the selection toolbar. */
enum class SelectionPopup { NONE, FEATHER, SAVED }

/**
 * A brush ([single]) or a brush set called [name] as a file it is shared in: its letters and
 * digits, in any script, its spaces, `_` and `-` (anything else a file name may not hold made `_`).
 */
internal fun brushFileName(name: String, single: Boolean): String {
    val kept = name.map { if (it.isLetterOrDigit() || it in " _-") it else '_' }.joinToString("").trim()
    val base = kept.ifEmpty { if (single) "Brush" else "Brush Set" }
    return base + if (single) ".${CanvasViewModel.BRUSH_EXTENSION}" else ".${CanvasViewModel.BRUSH_SET_EXTENSION}"
}

/**
 * The file another app's brushes from a file called [name] wait in for a canvas to open: [stamp]
 * (to keep them in order), `-`, then the name's letters and digits in any script, its spaces, `_`,
 * `-` and `.` (anything else made `_`). Read again by that name, its ending still says what the
 * file is, to the brush engines plugins bring (a Krita bundle or preset) as when it came.
 */
internal fun pendingBrushFileName(stamp: Long, name: String): String {
    val kept = name.map { if (it.isLetterOrDigit() || it in " _-.") it else '_' }.joinToString("").trim()
    return "$stamp-${kept.ifEmpty { "Imported" }}"
}

/** A clip of a picture: one of the kinds of thing it says it holds is an image. */
private fun isPicture(kinds: android.content.ClipDescription) = (0 until kinds.mimeTypeCount).any { kinds.getMimeType(it).startsWith("image/") }

/** The gallery, the open artwork and the state of the controls around it; outlives configuration changes. */
