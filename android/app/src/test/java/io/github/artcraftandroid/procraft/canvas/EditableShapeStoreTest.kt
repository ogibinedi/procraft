package io.github.artcraftandroid.procraft.canvas

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableShapeStoreTest {
    @Test
    fun jsonRoundTripPreservesAllEditableShapeFields() {
        val records = mapOf(
            42UL to EditableShapeRecord(
                spec = InsertShapeSpec(
                    kind = InsertShapeKind.STAR,
                    fillEnabled = true,
                    fillRgb = 0xFFCC00,
                    fillOpacity = 0.85f,
                    strokeEnabled = true,
                    strokeRgb = 0x112233,
                    strokeOpacity = 0.9f,
                    strokeWidthPx = 0.1f,
                    aspect = InsertShapeAspect.LANDSCAPE_4_3,
                    cornerRadiusFraction = 0.25f,
                    polygonSides = 7,
                    starPoints = 6,
                    starInnerRatio = 0.42f,
                    opacity = 0.95f,
                ),
                placement = ShapePlacement(
                    centerX = 640.5f,
                    centerY = 420.25f,
                    width = 512f,
                    height = 384f,
                    rotationDegrees = -37.5f,
                ),
            ),
            99UL to EditableShapeRecord(
                spec = InsertShapeSpec(
                    kind = InsertShapeKind.LINE,
                    fillEnabled = false,
                    fillRgb = null,
                    strokeEnabled = true,
                    strokeRgb = null,
                    strokeWidthPx = 125f,
                ),
                placement = ShapePlacement(300f, 300f, 600f, 100f, 15f),
            ),
        )

        val json = EditableShapeStore.toJson(records)
        val restored = EditableShapeStore.fromJson(json)
        assertEquals(2, restored.size)

        val star = restored[42UL]!!
        assertEquals(InsertShapeKind.STAR, star.spec.kind)
        assertEquals(0xFFCC00, star.spec.fillRgb)
        assertEquals(0x112233, star.spec.strokeRgb)
        assertEquals(0.1f, star.spec.strokeWidthPx, 0.001f)
        assertEquals(6, star.spec.starPoints)
        assertEquals(0.42f, star.spec.starInnerRatio, 0.001f)
        assertEquals(640.5f, star.placement.centerX, 0.01f)
        assertEquals(-37.5f, star.placement.rotationDegrees, 0.01f)

        val line = restored[99UL]!!
        assertEquals(InsertShapeKind.LINE, line.spec.kind)
        assertNull(line.spec.fillRgb)
        assertNull(line.spec.strokeRgb)
        assertEquals(125f, line.spec.strokeWidthPx, 0.01f)
    }

    @Test
    fun diskSaveLoadPruneAndMalformedInputAreSafe() {
        val dir = Files.createTempDirectory("procraft_shapes_test_").toFile()
        try {
            val rec = EditableShapeRecord(
                spec = InsertShapeSpec(kind = InsertShapeKind.POLYGON, polygonSides = 8),
                placement = ShapePlacement(500f, 500f, 400f, 400f, 0f),
            )
            EditableShapeStore.save(dir, mapOf(10UL to rec, 20UL to rec))
            val file = File(dir, EditableShapeStore.FILE_NAME)
            assertTrue(file.isFile)
            assertEquals(2, EditableShapeStore.load(dir).size)

            val pruned = EditableShapeStore.pruneMissing(EditableShapeStore.load(dir), setOf(20UL))
            assertEquals(setOf(20UL), pruned.keys)

            // Saving empty map cleans up the sidecar file
            EditableShapeStore.save(dir, emptyMap())
            assertFalse(file.exists())

            // Corrupt JSON falls back cleanly to emptyMap
            file.writeText("{not valid json!!!")
            assertEquals(emptyMap<ULong, EditableShapeRecord>(), EditableShapeStore.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun transformDeltaAndRotatedBoundsStayAccurate() {
        val initial = ShapePlacement(500f, 500f, 400f, 400f, 0f)
        val start = TransformSnapshot(500f, 500f, 420f, 420f, 0f)
        val end = TransformSnapshot(650f, 450f, 630f, 630f, 30f)
        val updated = ShapePlacement.applyTransformDelta(initial, start, end)
        assertEquals(650f, updated.centerX, 0.01f)
        assertEquals(450f, updated.centerY, 0.01f)
        assertEquals(600f, updated.width, 0.1f)
        assertEquals(600f, updated.height, 0.1f)
        assertEquals(30f, updated.rotationDegrees, 0.01f)

        val bounds = updated.rotatedBounds(canvasWidth = 1000, canvasHeight = 1000, extraHalfStrokePx = 10f)
        assertNotNull(bounds)
        assertTrue(bounds!!.left >= 0 && bounds.top >= 0)
        assertTrue(bounds.right <= 1000 && bounds.bottom <= 1000)
        assertTrue(bounds.width > 600)

        val offCanvas = ShapePlacement(centerX = 5000f, centerY = 5000f, width = 100f, height = 100f)
        assertNull(offCanvas.rotatedBounds(1000, 1000, 5f))
    }
}
