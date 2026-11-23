package io.github.artcraftandroid.procraft.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.core.ThumbnailImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.min

/** Rasterizes [InsertShapeSpec] to crisp anti-aliased straight RGBA8 pixels for CanvasSession. */
object InsertShapeRenderer {
    fun render(
        spec: InsertShapeSpec,
        canvasWidth: Int,
        canvasHeight: Int,
        rgb: Int,
    ): ThumbnailImage {
        val effectiveAspect = if (spec.supportsAspect) spec.aspect else InsertShapeAspect.WIDE_16_9
        val (w, h) = InsertShapeGeometry.targetBitmapSize(canvasWidth, canvasHeight, effectiveAspect)
        val strokePx = InsertShapeGeometry.strokeWidthPx(w, h, spec.clampedStrokeFraction)
        val inset = if (spec.effectiveStyle == InsertShapeStyle.OUTLINE) {
            ceil(strokePx * 0.5f) + 3f
        } else {
            3f
        }
        val left = inset.coerceAtMost(w * 0.4f)
        val top = inset.coerceAtMost(h * 0.4f)
        val right = (w - inset).coerceAtLeast(left + 2f)
        val bottom = (h - inset).coerceAtLeast(top + 2f)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                if (spec.effectiveStyle == InsertShapeStyle.OUTLINE) {
                    style = Paint.Style.STROKE
                    strokeWidth = strokePx
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                } else {
                    style = Paint.Style.FILL
                }
            }

            when (spec.kind) {
                InsertShapeKind.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
                InsertShapeKind.ROUNDED_RECT -> {
                    val radius = min(right - left, bottom - top) * spec.clampedCornerRadiusFraction
                    canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
                }
                InsertShapeKind.CIRCLE -> canvas.drawOval(RectF(left, top, right, bottom), paint)
                InsertShapeKind.TRIANGLE -> canvas.drawPath(
                    polygonPath(InsertShapeGeometry.triangleVertices(left, top, right, bottom)),
                    paint,
                )
                InsertShapeKind.POLYGON -> {
                    val cx = (left + right) * 0.5f
                    val cy = (top + bottom) * 0.5f
                    canvas.drawPath(
                        polygonPath(InsertShapeGeometry.polygonVertices(cx, cy, (right - left) * 0.5f, (bottom - top) * 0.5f, spec.clampedPolygonSides)),
                        paint,
                    )
                }
                InsertShapeKind.STAR -> {
                    val cx = (left + right) * 0.5f
                    val cy = (top + bottom) * 0.5f
                    canvas.drawPath(
                        polygonPath(InsertShapeGeometry.starVertices(cx, cy, (right - left) * 0.5f, (bottom - top) * 0.5f, spec.clampedStarPoints, spec.clampedStarInnerRatio)),
                        paint,
                    )
                }
                InsertShapeKind.ARROW -> canvas.drawPath(
                    polygonPath(InsertShapeGeometry.arrowVertices(left, top, right, bottom)),
                    paint,
                )
                InsertShapeKind.LINE -> {
                    val (start, end) = InsertShapeGeometry.lineEndpoints(left, top, right, bottom)
                    canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                }
            }

            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val alphaMask = ByteArray(pixels.size) { i -> (pixels[i] ushr 24).toByte() }
            val rgba = InsertShapeGeometry.alphaMaskToStraightRgba(alphaMask, rgb, spec.clampedOpacity)
            return ThumbnailImage(w.toUInt(), h.toUInt(), rgba)
        } finally {
            bitmap.recycle()
        }
    }

    private fun polygonPath(vertices: List<ShapePoint>): Path = Path().apply {
        if (vertices.isEmpty()) return@apply
        moveTo(vertices[0].x, vertices[0].y)
        for (i in 1 until vertices.size) {
            lineTo(vertices[i].x, vertices[i].y)
        }
        close()
    }
}

/** Inserts [spec] on a new layer in the current brush color, optionally entering Transform immediately. */
fun CanvasViewModel.insertShape(spec: InsertShapeSpec, startTransform: Boolean) {
    if (insertShapeBusy) return
    val s = session ?: return
    lastInsertShape = spec
    insertShapeBusy = true
    val (cw, ch) = canvasSize()
    val rgb = colorRgb()
    viewModelScope.launch {
        try {
            val image = withContext(Dispatchers.Default) {
                InsertShapeRenderer.render(spec, cw, ch, rgb)
            }
            if (modelOpen && !modelFlat) {
                if (!s.modelPictureBegin(image, true)) {
                    message = noRoom()
                    return@launch
                }
                readModelPicture()
                refresh()
                closeInsertShapeSheet()
                haptic(HapticCue.SNAP)
                showDone("Inserted ${spec.layerName}")
                return@launch
            }
            val layerId = s.insertImage(image, false)
            if (layerId == null) {
                message = noRoom()
                return@launch
            }
            s.renameLayer(layerId, spec.layerName)
            refresh()
            closeInsertShapeSheet()
            haptic(HapticCue.SNAP)
            showDone("Inserted ${spec.layerName}")
            if (startTransform) toggleTransform()
        } finally {
            insertShapeBusy = false
        }
    }
}
