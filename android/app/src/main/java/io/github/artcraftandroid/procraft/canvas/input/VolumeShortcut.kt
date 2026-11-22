package io.github.artcraftandroid.procraft.canvas

import android.view.KeyEvent
import kotlin.math.roundToInt

/** Configurable action mapping for Android hardware Volume Up / Volume Down buttons on the canvas. */
enum class VolumeKeyMode(val key: String, val label: String) {
    OFF("off", "Off"),
    UNDO_REDO("undo_redo", "Undo / Redo"),
    BRUSH_SIZE("brush_size", "Brush Size"),
    FLIP_FIT("flip_fit", "Flip / Fit Canvas");

    companion object {
        fun fromKey(key: String?): VolumeKeyMode =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Concrete canvas action resolved from a hardware volume button press. */
enum class VolumeAction(val allowsRepeat: Boolean) {
    UNDO(allowsRepeat = true),
    REDO(allowsRepeat = true),
    BRUSH_LARGER(allowsRepeat = true),
    BRUSH_SMALLER(allowsRepeat = true),
    FLIP_HORIZONTAL(allowsRepeat = false),
    FIT_CANVAS(allowsRepeat = false),
}

/** Brush size increment per volume button step (5% of slider travel). */
const val VOLUME_BRUSH_STEP = 0.05f

/**
 * Resolves a hardware volume key press to a [VolumeAction], or returns null so Android handles
 * system volume normally (e.g. when mode is [VolumeKeyMode.OFF], outside the canvas, while typing,
 * or while a modal/video screen covers the canvas).
 */
fun resolveVolumeKey(
    mode: VolumeKeyMode,
    keyCode: Int,
    onCanvas: Boolean,
    typing: Boolean,
    uiCoveringCanvas: Boolean,
): VolumeAction? {
    if (mode == VolumeKeyMode.OFF || !onCanvas || typing || uiCoveringCanvas) return null
    val isUp = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> true
        KeyEvent.KEYCODE_VOLUME_DOWN -> false
        else -> return null
    }
    return when (mode) {
        VolumeKeyMode.OFF -> null
        VolumeKeyMode.UNDO_REDO -> if (isUp) VolumeAction.REDO else VolumeAction.UNDO
        VolumeKeyMode.BRUSH_SIZE -> if (isUp) VolumeAction.BRUSH_LARGER else VolumeAction.BRUSH_SMALLER
        VolumeKeyMode.FLIP_FIT -> if (isUp) VolumeAction.FLIP_HORIZONTAL else VolumeAction.FIT_CANVAS
    }
}

/** Steps a normalized 0..1 brush size slider cleanly without floating-point drift. */
fun stepBrushSize(current: Float, delta: Float): Float {
    val next = (current + delta).coerceIn(0f, 1f)
    return (next * 100f).roundToInt() / 100f
}
