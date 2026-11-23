package io.github.artcraftandroid.procraft.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.ThumbnailImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

/** Rasterizes [InsertShapeSpec] at an exact [ShapePlacement] to straight RGBA8 pixels. */
object InsertShapeRenderer {
    /**
     * Renders [spec] placed at [placement] on a [canvasWidth] × [canvasHeight] canvas.
     * Allocates only the tight rotated bounding-box bitmap on Android and blits straight-RGBA8
     * rows directly into the canvas-sized buffer so `CanvasSession.insertImage` places it 1:1.
     */
    fun renderOnCanvas(
        spec: InsertShapeSpec,
        placement: ShapePlacement,
        canvasWidth: Int,
        canvasHeight: Int,
        activeRgb: Int,
    ): ThumbnailImage {
        val cw = canvasWidth.coerceAtLeast(32)
        val ch = canvasHeight.coerceAtLeast(32)
        val strokePx = spec.clampedStrokeWidthPx
        val halfStroke = if (spec.effectiveStrokeEnabled) ceil(strokePx * 0.5f) else 0f
        val bounds = placement.rotatedBounds(cw, ch, halfStroke)
            ?: return ThumbnailImage(cw.toUInt(), ch.toUInt(), ByteArray(cw * ch * 4))

        val bw = bounds.width
        val bh = bounds.height
        val localCx = placement.centerX - bounds.left
        val localCy = placement.centerY - bounds.top
        val shapeW = placement.width.coerceAtLeast(2f)
        val shapeH = placement.height.coerceAtLeast(2f)
        val left = localCx - shapeW * 0.5f
        val top = localCy - shapeH * 0.5f
        val right = localCx + shapeW * 0.5f
        val bottom = localCy + shapeH * 0.5f

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bw * bh)
        try {
            val canvas = Canvas(bitmap)
            val fillMask = if (spec.effectiveFillEnabled) {
                bitmap.eraseColor(Color.TRANSPARENT)
                canvas.save()
                if (placement.rotationDegrees != 0f) {
                    canvas.rotate(placement.rotationDegrees, localCx, localCy)
                }
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                drawShape(canvas, spec, left, top, right, bottom, fillPaint)
                canvas.restore()
                extractAlphaMask(bitmap, pixels)
            } else {
                null
            }

            val strokeMask = if (spec.effectiveStrokeEnabled) {
                bitmap.eraseColor(Color.TRANSPARENT)
                canvas.save()
                if (placement.rotationDegrees != 0f) {
                    canvas.rotate(placement.rotationDegrees, localCx, localCy)
                }
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = strokePx
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                drawShape(canvas, spec, left, top, right, bottom, strokePaint)
                canvas.restore()
                extractAlphaMask(bitmap, pixels)
            } else {
                null
            }

            val boxRgba = InsertShapeGeometry.compositeFillAndStrokeToStraightRgba(
                fillMask = fillMask,
                fillRgb = spec.resolvedFillRgb(activeRgb),
                fillOpacity = spec.clampedFillOpacity,
                strokeMask = strokeMask,
                strokeRgb = spec.resolvedStrokeRgb(activeRgb),
                strokeOpacity = spec.clampedStrokeOpacity,
                globalOpacity = spec.clampedOpacity,
            )

            if (bounds.left == 0 && bounds.top == 0 && bw == cw && bh == ch) {
                return ThumbnailImage(cw.toUInt(), ch.toUInt(), boxRgba)
            }
            val fullRgba = ByteArray(cw * ch * 4)
            val rowBytes = bw * 4
            val canvasStride = cw * 4
            val xOff = bounds.left * 4
            for (y in 0 until bh) {
                System.arraycopy(
                    boxRgba,
                    y * rowBytes,
                    fullRgba,
                    (bounds.top + y) * canvasStride + xOff,
                    rowBytes,
                )
            }
            return ThumbnailImage(cw.toUInt(), ch.toUInt(), fullRgba)
        } finally {
            bitmap.recycle()
        }
    }

    /** Compact render used for 3D model surface placement. */
    fun renderCompact(
        spec: InsertShapeSpec,
        canvasWidth: Int,
        canvasHeight: Int,
        activeRgb: Int,
    ): ThumbnailImage {
        val effectiveAspect = if (spec.supportsAspect) spec.aspect else InsertShapeAspect.WIDE_16_9
        val (w, h) = InsertShapeGeometry.targetBitmapSize(canvasWidth, canvasHeight, effectiveAspect)
        val strokePx = spec.clampedStrokeWidthPx
        val inset = if (spec.effectiveStrokeEnabled) (ceil(strokePx * 0.5f) + 3f).coerceAtMost(min(w, h) * 0.44f) else 3f
        val placement = ShapePlacement(
            centerX = w * 0.5f,
            centerY = h * 0.5f,
            width = (w - inset * 2f).coerceAtLeast(2f),
            height = (h - inset * 2f).coerceAtLeast(2f),
            rotationDegrees = 0f,
        )
        return renderOnCanvas(spec, placement, w, h, activeRgb)
    }

    private fun extractAlphaMask(bitmap: Bitmap, scratchPixels: IntArray): ByteArray {
        bitmap.getPixels(scratchPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteArray(scratchPixels.size) { i -> (scratchPixels[i] ushr 24).toByte() }
    }

    private fun drawShape(
        canvas: Canvas,
        spec: InsertShapeSpec,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
    ) {
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

fun CanvasViewModel.isEditableShapeLayer(id: ULong): Boolean = id in shapeLayers

internal fun CanvasViewModel.loadShapeLayers(dir: File?) {
    val liveIds = layers.map { it.id }.toSet()
    val loaded = EditableShapeStore.pruneMissing(EditableShapeStore.load(dir), liveIds)
    shapeLayers = loaded
    transformStartShape = null
}

internal fun CanvasViewModel.persistShapeLayers() {
    val dir = artworkDir ?: return
    val snapshot = shapeLayers
    viewModelScope.launch(Dispatchers.IO) {
        EditableShapeStore.save(dir, snapshot)
    }
}

internal fun CanvasViewModel.removeShapeLayerMeta(ids: Collection<ULong>) {
    if (ids.none { it in shapeLayers }) return
    shapeLayers = shapeLayers - ids.toSet()
    persistShapeLayers()
}

internal fun CanvasViewModel.pruneShapeLayers() {
    if (shapeLayers.isEmpty()) return
    val liveIds = layers.map { it.id }.toSet()
    val pruned = EditableShapeStore.pruneMissing(shapeLayers, liveIds)
    if (pruned.size != shapeLayers.size) {
        shapeLayers = pruned
        persistShapeLayers()
    }
}

/** Opens the Insert Shape composer in Edit mode for an existing editable shape layer. */
fun CanvasViewModel.openEditShape(l: LayerInfo) {
    val record = shapeLayers[l.id] ?: return
    if (l.locked) {
        showNotice("Unlock layer to edit shape")
        return
    }
    if (hasMask(l)) {
        showNotice("Merge or remove mask before editing shape")
        return
    }
    closePanels()
    editingShapeLayerId = l.id
    editingShapeRecord = record
    insertShapeSheetOpen = true
}

/** Converts an editable shape layer into a regular paint layer without touching its pixels. */
fun CanvasViewModel.rasterizeShapeLayer(l: LayerInfo) {
    if (l.id !in shapeLayers) return
    shapeLayers = shapeLayers - l.id
    persistShapeLayers()
    haptic(HapticCue.SNAP)
    showDone("Rasterized ${l.name}")
}

/** Inserts a new editable shape layer (or places on a 3D model surface). */
fun CanvasViewModel.insertShape(spec: InsertShapeSpec, startTransform: Boolean) {
    if (insertShapeBusy) return
    val s = session ?: return
    lastInsertShape = spec
    insertShapeBusy = true
    val (cw, ch) = canvasSize()
    val rgb = colorRgb()
    val onModel = modelOpen && !modelFlat
    val placement = ShapePlacement.centeredDefault(cw, ch, spec)
    viewModelScope.launch {
        try {
            val image = withContext(Dispatchers.Default) {
                if (onModel) {
                    InsertShapeRenderer.renderCompact(spec, cw, ch, rgb)
                } else {
                    InsertShapeRenderer.renderOnCanvas(spec, placement, cw, ch, rgb)
                }
            }
            if (onModel) {
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
            shapeLayers = shapeLayers + (layerId to EditableShapeRecord(spec, placement))
            persistShapeLayers()
            refresh()
            if (layersShown) refreshThumbnails()
            closeInsertShapeSheet()
            haptic(HapticCue.SNAP)
            showDone("Inserted ${spec.layerName}")
            if (startTransform) toggleTransform()
        } finally {
            insertShapeBusy = false
        }
    }
}

/** Re-renders an existing editable shape layer in place, preserving order and layer settings. */
fun CanvasViewModel.updateShapeLayer(
    layerId: ULong,
    newSpec: InsertShapeSpec,
    newPlacement: ShapePlacement,
    startTransform: Boolean,
) {
    if (insertShapeBusy) return
    val s = session ?: return
    val oldLayer = layers.firstOrNull { it.id == layerId } ?: run {
        closeInsertShapeSheet()
        return
    }
    if (oldLayer.locked) {
        showNotice("Unlock layer to edit shape")
        return
    }
    if (hasMask(oldLayer)) {
        showNotice("Merge or remove mask before editing shape")
        return
    }
    val oldRecord = shapeLayers[layerId]
    lastInsertShape = newSpec
    insertShapeBusy = true
    val (cw, ch) = canvasSize()
    val rgb = colorRgb()
    viewModelScope.launch {
        try {
            val image = withContext(Dispatchers.Default) {
                InsertShapeRenderer.renderOnCanvas(newSpec, newPlacement, cw, ch, rgb)
            }
            // Select old layer first so insertImage places the updated shape immediately above it
            s.selectLayer(oldLayer.id)
            val newLayerId = s.insertImage(image, oldLayer.private)
            if (newLayerId == null) {
                message = noRoom()
                return@launch
            }
            val targetName = if (oldRecord == null || oldLayer.name == oldRecord.spec.layerName) {
                newSpec.layerName
            } else {
                oldLayer.name
            }
            s.renameLayer(newLayerId, targetName)
            s.setLayerBlend(newLayerId, oldLayer.blend)
            s.setLayerOpacity(newLayerId, oldLayer.opacity, true)
            if (oldLayer.clipped) s.setLayerClipped(newLayerId, true)
            if (oldLayer.alphaLock) s.setLayerAlphaLock(newLayerId, true)
            if (oldLayer.reference) s.setLayerReference(newLayerId, true)
            if (!oldLayer.visible) s.setLayerVisible(newLayerId, false)
            s.deleteLayer(oldLayer.id)
            s.selectLayer(newLayerId)

            shapeLayers = (shapeLayers - oldLayer.id) + (newLayerId to EditableShapeRecord(newSpec, newPlacement))
            persistShapeLayers()
            refresh()
            if (layersShown) refreshThumbnails()
            closeInsertShapeSheet()
            haptic(HapticCue.SNAP)
            showDone("Updated $targetName")
            if (startTransform) toggleTransform()
        } finally {
            insertShapeBusy = false
        }
    }
}
