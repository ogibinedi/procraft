package io.github.artcraftandroid.procraft.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CanvasViewMetricsTest {
    private fun matrix(scale: Float, degrees: Float): List<Float> {
        val rad = degrees * (PI / 180.0)
        val c = (cos(rad) * scale).toFloat()
        val s = (sin(rad) * scale).toFloat()
        return listOf(c, s, -s, c, 0f, 0f)
    }

    @Test
    fun extractsZoomAndRotationFromViewMatrix() {
        val identity = extractCanvasViewMetrics(listOf(1f, 0f, 0f, 1f, 120f, 80f))
        assertEquals(CanvasViewMetrics(100, 0), identity)
        assertEquals("100%", identity.label())

        val zoomed = extractCanvasViewMetrics(matrix(2.5f, 45f))
        assertEquals(CanvasViewMetrics(250, 45), zoomed)
        assertEquals("250% · 45°", zoomed.label())

        val rotatedNeg = extractCanvasViewMetrics(matrix(0.5f, -90f))
        assertEquals(CanvasViewMetrics(50, -90), rotatedNeg)
        assertEquals("50% · -90°", rotatedNeg.label())

        val inverted = extractCanvasViewMetrics(matrix(1f, 180f))
        assertEquals(CanvasViewMetrics(100, 180), inverted)
        assertEquals("100% · 180°", inverted.label())
    }

    @Test
    fun nearZeroAngleSnapsToZeroAndMalformedMatricesFallbackSafely() {
        val tinyTilt = extractCanvasViewMetrics(matrix(1.25f, 0.25f))
        assertEquals(0, tinyTilt.rotationDegrees)
        assertEquals("125%", tinyTilt.label())

        assertEquals(CanvasViewMetrics(100, 0), extractCanvasViewMetrics(emptyList()))
        assertEquals(CanvasViewMetrics(100, 0), extractCanvasViewMetrics(listOf(0f, 0f, 0f, 0f, 0f, 0f)))
        assertEquals(CanvasViewMetrics(100, 0), extractCanvasViewMetrics(listOf(Float.NaN, 0f, 0f, 1f, 0f, 0f)))
    }

    @Test
    fun changeDetectionIgnoresFirstObservationAndDetectsZoomOrRotationChanges() {
        val a = CanvasViewMetrics(100, 0)
        assertFalse(hasViewMetricsChanged(null, a))
        assertFalse(hasViewMetricsChanged(a, CanvasViewMetrics(100, 0)))
        assertTrue(hasViewMetricsChanged(a, CanvasViewMetrics(101, 0)))
        assertTrue(hasViewMetricsChanged(a, CanvasViewMetrics(100, 1)))
    }
}
