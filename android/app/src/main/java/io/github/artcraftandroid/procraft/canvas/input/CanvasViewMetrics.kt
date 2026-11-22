package io.github.artcraftandroid.procraft.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Human-readable zoom percentage and rotation angle extracted from the canvas 2D affine view matrix
 * `[a, b, c, d, e, f]` (canvas pixels -> window pixels).
 */
data class CanvasViewMetrics(
    val zoomPercent: Int,
    val rotationDegrees: Int,
) {
    /** Formatted label such as `"100%"` or `"145% · 30°"`. */
    fun label(): String = if (rotationDegrees == 0) {
        "$zoomPercent%"
    } else {
        "$zoomPercent% · $rotationDegrees°"
    }
}

/**
 * Extracts zoom percentage and normalized rotation degrees (`-179..180`, snapped to `0°` within
 * `±0.5°`) from [viewMatrix]. Returns a neutral `100% / 0°` value for malformed matrices.
 */
fun extractCanvasViewMetrics(viewMatrix: List<Float>): CanvasViewMetrics {
    if (viewMatrix.size < 4) return CanvasViewMetrics(100, 0)
    val a = viewMatrix[0]
    val b = viewMatrix[1]
    if (!a.isFinite() || !b.isFinite()) return CanvasViewMetrics(100, 0)
    val scale = hypot(a.toDouble(), b.toDouble())
    if (scale < 1e-6) return CanvasViewMetrics(100, 0)

    val zoomPercent = (scale * 100.0).roundToInt().coerceIn(1, 99999)
    val rawDeg = atan2(b.toDouble(), a.toDouble()) * (180.0 / PI)
    var deg = rawDeg.roundToInt()
    while (deg <= -180) deg += 360
    while (deg > 180) deg -= 360
    if (abs(rawDeg) < 0.5) deg = 0
    return CanvasViewMetrics(zoomPercent = zoomPercent, rotationDegrees = deg)
}

/**
 * Whether [next] represents a user-visible zoom or rotation change relative to [previous].
 */
fun hasViewMetricsChanged(previous: CanvasViewMetrics?, next: CanvasViewMetrics): Boolean {
    if (previous == null) return false
    return previous.zoomPercent != next.zoomPercent || previous.rotationDegrees != next.rotationDegrees
}
