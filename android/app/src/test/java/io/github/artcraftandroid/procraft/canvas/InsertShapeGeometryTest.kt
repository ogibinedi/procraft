package io.github.artcraftandroid.procraft.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class InsertShapeGeometryTest {
    @Test
    fun polygonVerticesStartAtTopAndRespectSideCount() {
        val hex = InsertShapeGeometry.polygonVertices(cx = 100f, cy = 100f, rx = 50f, ry = 50f, sides = 6)
        assertEquals(6, hex.size)
        assertEquals(100f, hex[0].x, 0.01f)
        assertEquals(50f, hex[0].y, 0.01f)
        for (p in hex) {
            assertEquals(50f, hypot(p.x - 100f, p.y - 100f), 0.05f)
        }
    }

    @Test
    fun starVerticesAlternateOuterAndInnerRadii() {
        val star = InsertShapeGeometry.starVertices(cx = 100f, cy = 100f, rx = 60f, ry = 60f, points = 5, innerRatio = 0.5f)
        assertEquals(10, star.size)
        star.forEachIndexed { idx, p ->
            val expected = if (idx % 2 == 0) 60f else 30f
            assertEquals(expected, hypot(p.x - 100f, p.y - 100f), 0.05f)
        }
    }

    @Test
    fun triangleArrowAndLineBoundsStayInsideRect() {
        val tri = InsertShapeGeometry.triangleVertices(10f, 20f, 110f, 120f)
        assertEquals(3, tri.size)
        assertEquals(60f, tri[0].x, 0.01f)
        assertEquals(20f, tri[0].y, 0.01f)

        val arrow = InsertShapeGeometry.arrowVertices(0f, 0f, 200f, 100f)
        assertEquals(7, arrow.size)
        assertTrue(arrow.all { it.x in 0f..200f && it.y in 0f..100f })
        assertEquals(200f, arrow[3].x, 0.01f)
        assertEquals(50f, arrow[3].y, 0.01f)

        val (start, end) = InsertShapeGeometry.lineEndpoints(10f, 20f, 210f, 80f)
        assertEquals(50f, start.y, 0.01f)
        assertEquals(50f, end.y, 0.01f)
        assertEquals(10f, start.x, 0.01f)
        assertEquals(210f, end.x, 0.01f)
    }

    @Test
    fun targetBitmapSizePreservesAspectRatioAndClampsLargeCanvases() {
        val (sqW, sqH) = InsertShapeGeometry.targetBitmapSize(2000, 2000, InsertShapeAspect.SQUARE)
        assertEquals(sqW, sqH)
        assertTrue(sqW in 1000..1536)

        val (wideW, wideH) = InsertShapeGeometry.targetBitmapSize(4096, 4096, InsertShapeAspect.WIDE_16_9)
        assertTrue(wideW <= 1536)
        assertEquals(16f / 9f, wideW.toFloat() / wideH.toFloat(), 0.03f)

        val (portW, portH) = InsertShapeGeometry.targetBitmapSize(1080, 1920, InsertShapeAspect.PORTRAIT_3_4)
        assertTrue(portH > portW)
        assertEquals(3f / 4f, portW.toFloat() / portH.toFloat(), 0.03f)

        val (fallbackW, fallbackH) = InsertShapeGeometry.targetBitmapSize(0, 0, InsertShapeAspect.SQUARE)
        assertTrue(fallbackW >= 128 && fallbackH >= 128)
    }

    @Test
    fun alphaMaskToStraightRgbaAvoidsDoublePremultiplication() {
        val mask = byteArrayOf(0, 128.toByte(), 255.toByte())
        val rgba = InsertShapeGeometry.alphaMaskToStraightRgba(mask, rgb = 0xE84F51, opacity = 0.5f)
        assertEquals(12, rgba.size)
        // Pixel 0: transparent -> zeroed RGB and A
        assertEquals(0, rgba[0].toInt() and 0xFF)
        assertEquals(0, rgba[3].toInt() and 0xFF)
        // Pixel 1: anti-aliased edge -> preserves exact straight RGB while scaling alpha
        assertEquals(0xE8, rgba[4].toInt() and 0xFF)
        assertEquals(0x4F, rgba[5].toInt() and 0xFF)
        assertEquals(0x51, rgba[6].toInt() and 0xFF)
        assertEquals(64, rgba[7].toInt() and 0xFF)
        // Pixel 2: solid coverage at 50% opacity -> alpha ~128, exact RGB
        assertEquals(0xE8, rgba[8].toInt() and 0xFF)
        assertEquals(0x4F, rgba[9].toInt() and 0xFF)
        assertEquals(0x51, rgba[10].toInt() and 0xFF)
        assertEquals(128, rgba[11].toInt() and 0xFF)
    }

    @Test
    fun specNamingAndLineConstraintsWorkCorrectly() {
        val lineSpec = InsertShapeSpec(kind = InsertShapeKind.LINE, style = InsertShapeStyle.FILL)
        assertEquals(InsertShapeStyle.OUTLINE, lineSpec.effectiveStyle)
        assertFalse(lineSpec.supportsStyle)
        assertFalse(lineSpec.supportsAspect)

        assertEquals("Square", InsertShapeSpec(kind = InsertShapeKind.RECTANGLE, aspect = InsertShapeAspect.SQUARE).layerName)
        assertEquals("Rectangle", InsertShapeSpec(kind = InsertShapeKind.RECTANGLE, aspect = InsertShapeAspect.LANDSCAPE_4_3).layerName)
        assertEquals("Circle", InsertShapeSpec(kind = InsertShapeKind.CIRCLE, aspect = InsertShapeAspect.SQUARE).layerName)
        assertEquals("Ellipse", InsertShapeSpec(kind = InsertShapeKind.CIRCLE, aspect = InsertShapeAspect.WIDE_16_9).layerName)
        assertEquals("Hexagon", InsertShapeSpec(kind = InsertShapeKind.POLYGON, polygonSides = 6).layerName)
        assertEquals("Octagon", InsertShapeSpec(kind = InsertShapeKind.POLYGON, polygonSides = 8).layerName)
        assertEquals("5-Point Star", InsertShapeSpec(kind = InsertShapeKind.STAR, starPoints = 5).layerName)
    }
}
