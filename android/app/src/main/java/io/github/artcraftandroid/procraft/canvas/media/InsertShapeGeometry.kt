package io.github.artcraftandroid.procraft.canvas

import kotlin.math.PI
import kotlin.math.cos
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

enum class InsertShapeStyle(val label: String) {
    OUTLINE("Outline"),
    FILL("Fill"),
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
    val style: InsertShapeStyle = InsertShapeStyle.OUTLINE,
    val aspect: InsertShapeAspect = InsertShapeAspect.SQUARE,
    val strokeFraction: Float = 0.045f,
    val cornerRadiusFraction: Float = 0.22f,
    val polygonSides: Int = 6,
    val starPoints: Int = 5,
    val starInnerRatio: Float = 0.45f,
    val opacity: Float = 1f,
) {
    val effectiveStyle: InsertShapeStyle
        get() = if (kind == InsertShapeKind.LINE) InsertShapeStyle.OUTLINE else style

    val supportsStyle: Boolean
        get() = kind != InsertShapeKind.LINE

    val supportsAspect: Boolean
        get() = kind != InsertShapeKind.LINE

    val clampedStrokeFraction: Float
        get() = strokeFraction.coerceIn(MIN_STROKE_FRACTION, MAX_STROKE_FRACTION)

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

    companion object {
        const val MIN_STROKE_FRACTION = 0.008f
        const val MAX_STROKE_FRACTION = 0.16f
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

/** Pure geometric path math, target sizing, and straight-RGBA pixel packing for Insert Shape. */
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
     * inserted shape lands centered with comfortable breathing room for Transform handles.
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

    fun strokeWidthPx(width: Int, height: Int, strokeFraction: Float): Float {
        val minSide = min(width, height).coerceAtLeast(1).toFloat()
        val frac = strokeFraction.coerceIn(InsertShapeSpec.MIN_STROKE_FRACTION, InsertShapeSpec.MAX_STROKE_FRACTION)
        return (minSide * frac).coerceIn(2f, minSide * 0.25f)
    }

    /**
     * Converts an 8-bit anti-aliased coverage [alphaMask] into straight RGBA8 bytes for
     * `CanvasSession.insertImage`, avoiding double-premultiplication fringes and keeping exact
     * P3/sRGB channel values from [rgb].
     */
    fun alphaMaskToStraightRgba(alphaMask: ByteArray, rgb: Int, opacity: Float): ByteArray {
        val r = ((rgb ushr 16) and 0xFF).toByte()
        val g = ((rgb ushr 8) and 0xFF).toByte()
        val b = (rgb and 0xFF).toByte()
        val scale = opacity.coerceIn(0f, 1f)
        val out = ByteArray(alphaMask.size * 4)
        for (i in alphaMask.indices) {
            val rawA = alphaMask[i].toInt() and 0xFF
            val outA = (rawA * scale).roundToInt().coerceIn(0, 255)
            val base = i * 4
            if (outA > 0) {
                out[base] = r
                out[base + 1] = g
                out[base + 2] = b
                out[base + 3] = outA.toByte()
            }
        }
        return out
    }
}
