package io.github.artcraftandroid.procraft.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import io.github.artcraftandroid.procraft.core.BrushInfo
import androidx.compose.ui.unit.dp


/** Which panel is open over the canvas. */
internal const val TAG = "ProCraft"

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
internal fun isPicture(kinds: android.content.ClipDescription) = (0 until kinds.mimeTypeCount).any { kinds.getMimeType(it).startsWith("image/") }

/** The gallery, the open artwork and the state of the controls around it; outlives configuration changes. */
