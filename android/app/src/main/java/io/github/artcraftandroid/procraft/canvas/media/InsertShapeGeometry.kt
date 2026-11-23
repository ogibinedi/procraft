package io.github.artcraftandroid.procraft.canvas

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class InsertShapeKind(val label: String) {
    RECTANGLE("Rectangle"),
    ROUNDED_RECT("Rounded"),
    CIRCLE("Circle"),
    TRIANGLE("Triangle"),
    POLYGON("Polygon"),
    STAR("Star"),
    ARROW("Arrow"),
    LINE("Line"),
}

enum class InsertShapeAspect(val label: String, val widthRatio: Float, val heightRatio: Float) {
    SQUARE("1:1", 1f, 1f),
    LANDSCAPE_4_3("4:3", 4f, 3f),
    WIDE_16_9("16:9", 16f, 9f),
    PORTRAIT_3_4("3:4", 3f, 4f),
}

data class ShapePoint(val x: Float, val y: Float)

data class InsertShapeSpec(
    val kind: InsertShapeKind = InsertShapeKind.RECTANGLE,
    val fillEnabled: Boolean = true,
    val fillRgb: Int? = null,
    val fillOpacity: Float = 1f,
    val strokeEnabled: Boolean = true,
    val strokeRgb: Int? = null,
    val strokeOpacity: Float = 1f,
    val strokeWidthPx: Float = 8f,
    val aspect: InsertShapeAspect = InsertShapeAspect.SQUARE,
    val cornerRadiusFraction: Float = 0.22f,
    val polygonSides: Int = 6,
    val starPoints: Int = 5,
    val starInnerRatio: Float = 0.45f,
    val opacity: Float = 1f,
) {
    val supportsFill: Boolean
        get() = kind != InsertShapeKind.LINE

    val supportsAspect: Boolean
        get() = kind != InsertShapeKind.LINE

    val effectiveFillEnabled: Boolean
        get() = supportsFill && fillEnabled

    val effectiveStrokeEnabled: Boolean
        get() = !supportsFill || strokeEnabled || !fillEnabled

    val clampedStrokeWidthPx: Float
        get() = strokeWidthPx.coerceIn(MIN_STROKE_PX, MAX_STROKE_PX)

    val clampedFillOpacity: Float
        get() = fillOpacity.coerceIn(MIN_CHANNEL_OPACITY, 1f)

    val clampedStrokeOpacity: Float
        get() = strokeOpacity.coerceIn(MIN_CHANNEL_OPACITY, 1f)

    val clampedCornerRadiusFraction: Float
        get() = cornerRadiusFraction.coerceIn(0f, 0.5f)

    val clampedPolygonSides: Int
        get() = polygonSides.coerceIn(MIN_POLYGON_SIDES, MAX_POLYGON_SIDES)

    val clampedStarPoints: Int
        get() = starPoints.coerceIn(MIN_STAR_POINTS, MAX_STAR_POINTS)

    val clampedStarInnerRatio: Float
        get() = starInnerRatio.coerceIn(MIN_STAR_INNER_RATIO, MAX_STAR_INNER_RATIO)

    val clampedOpacity: Float
        get() = opacity.coerceIn(MIN_OPACITY, 1f)

    val styleSummary: String
        get() = when {
            effectiveFillEnabled && effectiveStrokeEnabled -> "Fill & Stroke"
            effectiveFillEnabled -> "Fill"
            else -> "Stroke"
        }

    val layerName: String
        get() = when (kind) {
            InsertShapeKind.RECTANGLE -> if (aspect == InsertShapeAspect.SQUARE) "Square" else "Rectangle"
            InsertShapeKind.ROUNDED_RECT -> "Rounded Rectangle"
            InsertShapeKind.CIRCLE -> if (aspect == InsertShapeAspect.SQUARE) "Circle" else "Ellipse"
            InsertShapeKind.TRIANGLE -> "Triangle"
            InsertShapeKind.POLYGON -> polygonName(clampedPolygonSides)
            InsertShapeKind.STAR -> "${clampedStarPoints}-Point Star"
            InsertShapeKind.ARROW -> "Arrow"
            InsertShapeKind.LINE -> "Line"
        }

    fun resolvedFillRgb(activeRgb: Int): Int = (fillRgb ?: activeRgb) and 0xFFFFFF

    fun resolvedStrokeRgb(activeRgb: Int): Int = (
        strokeRgb ?: if (effectiveFillEnabled) {
            InsertShapeGeometry.contrastingStrokeRgb(resolvedFillRgb(activeRgb))
        } else {
            activeRgb
        }
        ) and 0xFFFFFF

    /** Keeps at least one of Fill or Stroke active so users never insert an invisible blank layer. */
    fun withFillToggled(enabled: Boolean): InsertShapeSpec = when {
        !supportsFill -> copy(fillEnabled = false, strokeEnabled = true)
        !enabled && !strokeEnabled -> copy(fillEnabled = false, strokeEnabled = true)
        else -> copy(fillEnabled = enabled)
    }

    fun withStrokeToggled(enabled: Boolean): InsertShapeSpec = when {
        !supportsFill -> copy(strokeEnabled = true)
        !enabled && !fillEnabled -> copy(strokeEnabled = false, fillEnabled = true)
        else -> copy(strokeEnabled = enabled)
    }

    fun withSwappedColors(activeRgb: Int): InsertShapeSpec {
        val currentFill = resolvedFillRgb(activeRgb)
        val currentStroke = resolvedStrokeRgb(activeRgb)
        return copy(fillRgb = currentStroke, strokeRgb = currentFill)
    }

    companion object {
        const val MIN_STROKE_PX = 0.1f
        const val MAX_STROKE_PX = 500f
        const val MIN_CHANNEL_OPACITY = 0.05f
        const val MIN_POLYGON_SIDES = 5
        const val MAX_POLYGON_SIDES = 12
        const val MIN_STAR_POINTS = 4
        const val MAX_STAR_POINTS = 12
        const val MIN_STAR_INNER_RATIO = 0.20f
        const val MAX_STAR_INNER_RATIO = 0.80f
        const val MIN_OPACITY = 0.10f

        fun polygonName(sides: Int): String = when (sides.coerceIn(MIN_POLYGON_SIDES, MAX_POLYGON_SIDES)) {
            5 -> "Pentagon"
            6 -> "Hexagon"
            7 -> "Heptagon"
            8 -> "Octagon"
            9 -> "Nonagon"
            10 -> "Decagon"
            11 -> "Hendecagon"
            12 -> "Dodecagon"
            else -> "$sides-gon"
        }
    }
}

/** Pure geometric path math, decimal stroke scaling, color parsing, and straight-RGBA compositing. */
object InsertShapeGeometry {
    private const val DEFAULT_CANVAS_FIT = 0.68f
    private const val MIN_BITMAP_SIDE = 128

    fun polygonVertices(cx: Float, cy: Float, rx: Float, ry: Float, sides: Int): List<ShapePoint> {
        val n = sides.coerceIn(3, 24)
        val startAngle = -PI / 2.0
        return List(n) { i ->
            val angle = startAngle + (2.0 * PI * i) / n
            ShapePoint(
                x = cx + (cos(angle) * rx).toFloat(),
                y = cy + (sin(angle) * ry).toFloat(),
            )
        }
    }

    fun starVertices(cx: Float, cy: Float, rx: Float, ry: Float, points: Int, innerRatio: Float): List<ShapePoint> {
        val n = points.coerceIn(InsertShapeSpec.MIN_STAR_POINTS, InsertShapeSpec.MAX_STAR_POINTS)
        val ratio = innerRatio.coerceIn(InsertShapeSpec.MIN_STAR_INNER_RATIO, InsertShapeSpec.MAX_STAR_INNER_RATIO)
        val total = n * 2
        val startAngle = -PI / 2.0
        return List(total) { i ->
            val angle = startAngle + (PI * i) / n
            val scale = if (i % 2 == 0) 1f else ratio
            ShapePoint(
                x = cx + (cos(angle) * rx * scale).toFloat(),
                y = cy + (sin(angle) * ry * scale).toFloat(),
            )
        }
    }

    fun triangleVertices(left: Float, top: Float, right: Float, bottom: Float): List<ShapePoint> {
        val cx = (left + right) * 0.5f
        return listOf(
            ShapePoint(cx, top),
            ShapePoint(right, bottom),
            ShapePoint(left, bottom),
        )
    }

    fun arrowVertices(left: Float, top: Float, right: Float, bottom: Float): List<ShapePoint> {
        val w = right - left
        val h = bottom - top
        val cy = top + h * 0.5f
        val headStart = left + w * 0.56f
        val shaftHalf = h * 0.20f
        return listOf(
            ShapePoint(left, cy - shaftHalf),
            ShapePoint(headStart, cy - shaftHalf),
            ShapePoint(headStart, top),
            ShapePoint(right, cy),
            ShapePoint(headStart, bottom),
            ShapePoint(headStart, cy + shaftHalf),
            ShapePoint(left, cy + shaftHalf),
        )
    }

    fun lineEndpoints(left: Float, top: Float, right: Float, bottom: Float): Pair<ShapePoint, ShapePoint> {
        val cy = (top + bottom) * 0.5f
        return ShapePoint(left, cy) to ShapePoint(right, cy)
    }

    /**
     * Computes crisp target pixel dimensions (~68% of canvas fit) matching [aspect] so the
     * inserted shape lands centered with comfortable room for Transform handles.
     */
    fun targetBitmapSize(
        canvasWidth: Int,
        canvasHeight: Int,
        aspect: InsertShapeAspect,
        maxDimension: Int = 1536,
    ): Pair<Int, Int> {
        val safeW = if (canvasWidth > 0) canvasWidth else 1024
        val safeH = if (canvasHeight > 0) canvasHeight else 1024
        val maxW = min(maxDimension, safeW).coerceAtLeast(MIN_BITMAP_SIDE)
        val maxH = min(maxDimension, safeH).coerceAtLeast(MIN_BITMAP_SIDE)
        val boxW = (safeW * DEFAULT_CANVAS_FIT).coerceIn(MIN_BITMAP_SIDE.toFloat(), maxW.toFloat())
        val boxH = (safeH * DEFAULT_CANVAS_FIT).coerceIn(MIN_BITMAP_SIDE.toFloat(), maxH.toFloat())
        val ratio = aspect.widthRatio / aspect.heightRatio
        val width: Float
        val height: Float
        if (boxW / boxH >= ratio) {
            height = boxH
            width = height * ratio
        } else {
            width = boxW
            height = width / ratio
        }
        return width.roundToInt().coerceIn(32, maxW) to height.roundToInt().coerceIn(32, maxH)
    }

    /** Auto-picks crisp dark or white default stroke color contrasting against [fillRgb]. */
    fun contrastingStrokeRgb(fillRgb: Int): Int {
        val r = ((fillRgb ushr 16) and 0xFF) / 255f
        val g = ((fillRgb ushr 8) and 0xFF) / 255f
        val b = (fillRgb and 0xFF) / 255f
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return if (luma < 0.28f) 0xFFFFFF else 0x141414
    }

    /** Logarithmic slider mapping giving fine decimal control from 0.1 px up to 500 px. */
    fun strokePxToSlider(px: Float, maxPx: Float = InsertShapeSpec.MAX_STROKE_PX): Float {
        val hi = maxPx.coerceAtLeast(InsertShapeSpec.MIN_STROKE_PX + 1f).toDouble()
        val lo = InsertShapeSpec.MIN_STROKE_PX.toDouble()
        val v = px.toDouble().coerceIn(lo, hi)
        return (ln(v / lo) / ln(hi / lo)).toFloat().coerceIn(0f, 1f)
    }

    fun sliderToStrokePx(fraction: Float, maxPx: Float = InsertShapeSpec.MAX_STROKE_PX): Float {
        val t = fraction.coerceIn(0f, 1f).toDouble()
        val lo = InsertShapeSpec.MIN_STROKE_PX.toDouble()
        val hi = maxPx.coerceAtLeast(InsertShapeSpec.MIN_STROKE_PX + 1f).toDouble()
        val raw = (lo * exp(t * ln(hi / lo))).toFloat()
        val snapped = when {
            raw < 10f -> (raw * 10f).roundToInt() / 10f
            raw < 30f -> (raw * 2f).roundToInt() / 2f
            else -> raw.roundToInt().toFloat()
        }
        return snapped.coerceIn(InsertShapeSpec.MIN_STROKE_PX, maxPx)
    }

    /** Adaptive stepper: 0.1 px under 1 px, 0.5 px under 5 px, 1 px under 20 px, then 5 / 10 px. */
    fun stepStrokePx(
        currentPx: Float,
        increase: Boolean,
        maxPx: Float = InsertShapeSpec.MAX_STROKE_PX,
    ): Float {
        val cur = currentPx.coerceIn(InsertShapeSpec.MIN_STROKE_PX, maxPx)
        val delta = if (increase) {
            when {
                cur < 1f - 1e-4f -> 0.1f
                cur < 5f - 1e-4f -> 0.5f
                cur < 20f - 1e-4f -> 1f
                cur < 100f - 1e-4f -> 5f
                else -> 10f
            }
        } else {
            when {
                cur <= 1f + 1e-4f -> -0.1f
                cur <= 5f + 1e-4f -> -0.5f
                cur <= 20f + 1e-4f -> -1f
                cur <= 100f + 1e-4f -> -5f
                else -> -10f
            }
        }
        val next = ((cur + delta) * 10f).roundToInt() / 10f
        return next.coerceIn(InsertShapeSpec.MIN_STROKE_PX, maxPx)
    }

    fun parseStrokeInput(text: String, maxPx: Float = InsertShapeSpec.MAX_STROKE_PX): Float? {
        val normalized = text.trim().replace(',', '.')
        val value = normalized.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        val rounded = (value * 10f).roundToInt() / 10f
        return rounded.coerceIn(InsertShapeSpec.MIN_STROKE_PX, maxPx)
    }

    fun formatStrokePx(px: Float): String {
        val clamped = px.coerceIn(InsertShapeSpec.MIN_STROKE_PX, InsertShapeSpec.MAX_STROKE_PX)
        val tenths = (clamped * 10f).roundToInt()
        return if (tenths >= 100 && tenths % 10 == 0) {
            (tenths / 10).toString()
        } else {
            String.format(Locale.ROOT, "%.1f", tenths / 10f)
        }
    }

    fun formatHexRgb(rgb: Int): String = String.format(Locale.ROOT, "#%06X", rgb and 0xFFFFFF)

    fun parseHexRgb(text: String): Int? {
        val cleaned = text.trim().removePrefix("#")
        return when (cleaned.length) {
            6 -> cleaned.toIntOrNull(16)
            3 -> {
                val r = cleaned[0].digitToIntOrNull(16) ?: return null
                val g = cleaned[1].digitToIntOrNull(16) ?: return null
                val b = cleaned[2].digitToIntOrNull(16) ?: return null
                ((r * 17) shl 16) or ((g * 17) shl 8) or (b * 17)
            }
            else -> null
        }
    }

    /**
     * Composites optional anti-aliased [fillMask] and [strokeMask] (stroke over fill) using exact
     * straight-alpha Porter-Duff SRC_OVER math so even 0.1 px strokes retain exact color channels
     * without double-premultiplication fringes on sRGB or Display P3 canvases.
     */
    fun compositeFillAndStrokeToStraightRgba(
        fillMask: ByteArray?,
        fillRgb: Int,
        fillOpacity: Float,
        strokeMask: ByteArray?,
        strokeRgb: Int,
        strokeOpacity: Float,
        globalOpacity: Float,
    ): ByteArray {
        val count = fillMask?.size ?: strokeMask?.size ?: return ByteArray(0)
        val fr = ((fillRgb ushr 16) and 0xFF).toFloat()
        val fg = ((fillRgb ushr 8) and 0xFF).toFloat()
        val fb = (fillRgb and 0xFF).toFloat()
        val sr = ((strokeRgb ushr 16) and 0xFF).toFloat()
        val sg = ((strokeRgb ushr 8) and 0xFF).toFloat()
        val sb = (strokeRgb and 0xFF).toFloat()
        val fScale = fillOpacity.coerceIn(0f, 1f) / 255f
        val sScale = strokeOpacity.coerceIn(0f, 1f) / 255f
        val gScale = globalOpacity.coerceIn(0f, 1f)

        val out = ByteArray(count * 4)
        for (i in 0 until count) {
            val fa = if (fillMask != null) (fillMask[i].toInt() and 0xFF) * fScale else 0f
            val sa = if (strokeMask != null) (strokeMask[i].toInt() and 0xFF) * sScale else 0f
            val outA = sa + fa * (1f - sa)
            if (outA <= 0f) continue
            val finalA = (outA * gScale * 255f).roundToInt().coerceIn(0, 255)
            if (finalA == 0) continue

            val invOutA = 1f / outA
            val fillWeight = fa * (1f - sa)
            val r = ((sr * sa + fr * fillWeight) * invOutA).roundToInt().coerceIn(0, 255)
            val g = ((sg * sa + fg * fillWeight) * invOutA).roundToInt().coerceIn(0, 255)
            val b = ((sb * sa + fb * fillWeight) * invOutA).roundToInt().coerceIn(0, 255)

            val base = i * 4
            out[base] = r.toByte()
            out[base + 1] = g.toByte()
            out[base + 2] = b.toByte()
            out[base + 3] = finalA.toByte()
        }
        return out
    }
}
