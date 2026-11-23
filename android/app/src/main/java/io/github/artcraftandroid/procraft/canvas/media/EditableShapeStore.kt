package io.github.artcraftandroid.procraft.canvas

import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class ShapeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class TransformSnapshot(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val angleDegrees: Float,
) {
    companion object {
        fun fromBox(corners: List<Float>, width: Float, height: Float, angle: Float): TransformSnapshot? {
            if (corners.size < 8 || width <= 0f || height <= 0f) return null
            val cx = (corners[0] + corners[2] + corners[4] + corners[6]) * 0.25f
            val cy = (corners[1] + corners[3] + corners[5] + corners[7]) * 0.25f
            if (!cx.isFinite() || !cy.isFinite() || !width.isFinite() || !height.isFinite() || !angle.isFinite()) {
                return null
            }
            return TransformSnapshot(cx, cy, width, height, angle)
        }
    }
}

/** Canvas-space placement of an editable shape layer (pixels & clockwise degrees). */
data class ShapePlacement(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float = 0f,
) {
    fun normalizedRotation(): Float {
        var r = rotationDegrees % 360f
        if (r > 180f) r -= 360f
        if (r < -180f) r += 360f
        return r
    }

    /** Adjusts width/height to [aspect] while preserving center and approximate visual area. */
    fun withAspect(aspect: InsertShapeAspect): ShapePlacement {
        val ratio = aspect.widthRatio / aspect.heightRatio
        val area = (width.coerceAtLeast(16f) * height.coerceAtLeast(16f))
        val nextH = sqrt(area / ratio).coerceAtLeast(16f)
        val nextW = (nextH * ratio).coerceAtLeast(16f)
        return copy(width = nextW, height = nextH)
    }

    /** Computes tight integer canvas bounds covering the rotated shape plus [extraHalfStrokePx]. */
    fun rotatedBounds(canvasWidth: Int, canvasHeight: Int, extraHalfStrokePx: Float): ShapeBounds? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val pad = extraHalfStrokePx.coerceAtLeast(0f) + 4f
        val hw = width.coerceAtLeast(2f) * 0.5f + pad
        val hh = height.coerceAtLeast(2f) * 0.5f + pad
        val rad = rotationDegrees * (PI / 180.0)
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val boundHalfW = hw * c + hh * s
        val boundHalfH = hw * s + hh * c

        val left = floor(centerX - boundHalfW).toInt().coerceIn(0, canvasWidth)
        val top = floor(centerY - boundHalfH).toInt().coerceIn(0, canvasHeight)
        val right = ceil(centerX + boundHalfW).toInt().coerceIn(0, canvasWidth)
        val bottom = ceil(centerY + boundHalfH).toInt().coerceIn(0, canvasHeight)
        if (right <= left || bottom <= top) return null
        return ShapeBounds(left, top, right, bottom)
    }

    companion object {
        fun centeredDefault(canvasWidth: Int, canvasHeight: Int, spec: InsertShapeSpec): ShapePlacement {
            val cw = if (canvasWidth > 0) canvasWidth else 1024
            val ch = if (canvasHeight > 0) canvasHeight else 1024
            val effectiveAspect = if (spec.supportsAspect) spec.aspect else InsertShapeAspect.WIDE_16_9
            val (w, h) = InsertShapeGeometry.targetBitmapSize(cw, ch, effectiveAspect)
            return ShapePlacement(
                centerX = cw * 0.5f,
                centerY = ch * 0.5f,
                width = w.toFloat(),
                height = h.toFloat(),
                rotationDegrees = 0f,
            )
        }

        /** Updates [initial] using the move/scale/rotate delta observed across a Transform session. */
        fun applyTransformDelta(
            initial: ShapePlacement,
            start: TransformSnapshot,
            end: TransformSnapshot,
        ): ShapePlacement {
            val dx = end.centerX - start.centerX
            val dy = end.centerY - start.centerY
            val dAngle = end.angleDegrees - start.angleDegrees
            val sx = (end.width / start.width.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
            val sy = (end.height / start.height.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
            // When the shape was already rotated, scale uniformly by geometric mean to avoid skew drift
            val wasRotated = abs(initial.normalizedRotation()) > 1f
            val (effSx, effSy) = if (wasRotated && abs(sx - sy) > 0.02f) {
                val u = hypot(sx, sy) / sqrt(2f)
                u to u
            } else {
                sx to sy
            }
            return ShapePlacement(
                centerX = initial.centerX + dx,
                centerY = initial.centerY + dy,
                width = (initial.width * effSx).coerceAtLeast(16f),
                height = (initial.height * effSy).coerceAtLeast(16f),
                rotationDegrees = (initial.rotationDegrees + dAngle).let { deg ->
                    var r = deg % 360f
                    if (r > 180f) r -= 360f
                    if (r < -180f) r += 360f
                    r
                },
            )
        }
    }
}

data class EditableShapeRecord(
    val spec: InsertShapeSpec,
    val placement: ShapePlacement,
)

/**
 * Pure Kotlin sidecar JSON store (`<artworkDir>/shapes.json`) persisting editable shape metadata
 * per layer ID across app restarts, artwork duplication, and `.procraft-backup` archives.
 */
object EditableShapeStore {
    const val FILE_NAME = "shapes.json"

    fun load(artworkDir: File?): Map<ULong, EditableShapeRecord> {
        if (artworkDir == null) return emptyMap()
        val file = File(artworkDir, FILE_NAME)
        if (!file.isFile) return emptyMap()
        return runCatching { fromJson(file.readText()) }.getOrDefault(emptyMap())
    }

    fun save(artworkDir: File?, records: Map<ULong, EditableShapeRecord>) {
        if (artworkDir == null || !artworkDir.isDirectory) return
        val file = File(artworkDir, FILE_NAME)
        if (records.isEmpty()) {
            if (file.exists()) runCatching { file.delete() }
            return
        }
        runCatching {
            val tmp = File(artworkDir, "$FILE_NAME.tmp")
            tmp.writeText(toJson(records))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun pruneMissing(
        records: Map<ULong, EditableShapeRecord>,
        liveLayerIds: Set<ULong>,
    ): Map<ULong, EditableShapeRecord> = records.filterKeys { it in liveLayerIds }

    fun toJson(records: Map<ULong, EditableShapeRecord>): String = buildString {
        append("{\"v\":1,\"shapes\":[")
        var first = true
        for ((id, rec) in records.entries.sortedBy { it.key }) {
            if (!first) append(',')
            first = false
            val s = rec.spec
            val p = rec.placement
            append('{')
            append("\"id\":\"").append(id.toString()).append("\",")
            append("\"kind\":\"").append(s.kind.name).append("\",")
            append("\"fillOn\":").append(s.fillEnabled).append(',')
            append("\"fillRgb\":").append(s.fillRgb?.toString() ?: "null").append(',')
            append("\"fillOp\":").append(fmtFloat(s.clampedFillOpacity)).append(',')
            append("\"strokeOn\":").append(s.strokeEnabled).append(',')
            append("\"strokeRgb\":").append(s.strokeRgb?.toString() ?: "null").append(',')
            append("\"strokeOp\":").append(fmtFloat(s.clampedStrokeOpacity)).append(',')
            append("\"strokePx\":").append(fmtFloat(s.clampedStrokeWidthPx)).append(',')
            append("\"aspect\":\"").append(s.aspect.name).append("\",")
            append("\"corner\":").append(fmtFloat(s.clampedCornerRadiusFraction)).append(',')
            append("\"sides\":").append(s.clampedPolygonSides).append(',')
            append("\"starPts\":").append(s.clampedStarPoints).append(',')
            append("\"starIn\":").append(fmtFloat(s.clampedStarInnerRatio)).append(',')
            append("\"opacity\":").append(fmtFloat(s.clampedOpacity)).append(',')
            append("\"cx\":").append(fmtFloat(p.centerX)).append(',')
            append("\"cy\":").append(fmtFloat(p.centerY)).append(',')
            append("\"w\":").append(fmtFloat(p.width)).append(',')
            append("\"h\":").append(fmtFloat(p.height)).append(',')
            append("\"rot\":").append(fmtFloat(p.normalizedRotation()))
            append('}')
        }
        append("]}")
    }

    fun fromJson(json: String): Map<ULong, EditableShapeRecord> {
        val arrayMatch = Regex("""\"shapes\"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL).find(json) ?: return emptyMap()
        val body = arrayMatch.groupValues[1]
        val objectRegex = Regex("""\{([^{}]*)}""")
        val fieldRegex = Regex("""\"([A-Za-z0-9_]+)\"\s*:\s*(\"[^\"]*\"|true|false|null|-?[0-9]+(?:\.[0-9]+)?)""")
        val out = LinkedHashMap<ULong, EditableShapeRecord>()
        for (obj in objectRegex.findAll(body)) {
            val map = HashMap<String, String>()
            for (f in fieldRegex.findAll(obj.groupValues[1])) {
                map[f.groupValues[1]] = f.groupValues[2].removeSurrounding("\"")
            }
            val id = map["id"]?.toULongOrNull() ?: continue
            val kind = map["kind"]?.let { name -> InsertShapeKind.entries.firstOrNull { it.name == name } } ?: continue
            val aspect = map["aspect"]?.let { name -> InsertShapeAspect.entries.firstOrNull { it.name == name } } ?: InsertShapeAspect.SQUARE
            val spec = InsertShapeSpec(
                kind = kind,
                fillEnabled = map["fillOn"]?.toBooleanStrictOrNull() ?: true,
                fillRgb = map["fillRgb"]?.takeIf { it != "null" }?.toIntOrNull(),
                fillOpacity = map["fillOp"]?.toFloatOrNull() ?: 1f,
                strokeEnabled = map["strokeOn"]?.toBooleanStrictOrNull() ?: true,
                strokeRgb = map["strokeRgb"]?.takeIf { it != "null" }?.toIntOrNull(),
                strokeOpacity = map["strokeOp"]?.toFloatOrNull() ?: 1f,
                strokeWidthPx = map["strokePx"]?.toFloatOrNull() ?: 8f,
                aspect = aspect,
                cornerRadiusFraction = map["corner"]?.toFloatOrNull() ?: 0.22f,
                polygonSides = map["sides"]?.toIntOrNull() ?: 6,
                starPoints = map["starPts"]?.toIntOrNull() ?: 5,
                starInnerRatio = map["starIn"]?.toFloatOrNull() ?: 0.45f,
                opacity = map["opacity"]?.toFloatOrNull() ?: 1f,
            )
            val cx = map["cx"]?.toFloatOrNull() ?: continue
            val cy = map["cy"]?.toFloatOrNull() ?: continue
            val w = map["w"]?.toFloatOrNull()?.takeIf { it > 0f } ?: continue
            val h = map["h"]?.toFloatOrNull()?.takeIf { it > 0f } ?: continue
            val rot = map["rot"]?.toFloatOrNull() ?: 0f
            out[id] = EditableShapeRecord(spec, ShapePlacement(cx, cy, w, h, rot))
        }
        return out
    }

    private fun fmtFloat(v: Float): String = String.format(Locale.ROOT, "%.4f", if (v.isFinite()) v else 0f)
}
