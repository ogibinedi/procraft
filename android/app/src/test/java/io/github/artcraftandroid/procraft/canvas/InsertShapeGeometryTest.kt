package io.github.artcraftandroid.procraft.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun strokeLogSliderSteppingAndInputSupportDecimalZeroPointOneToLarge() {
        assertEquals(0.1f, InsertShapeGeometry.sliderToStrokePx(0f), 0.001f)
        assertEquals(500f, InsertShapeGeometry.sliderToStrokePx(1f), 0.001f)
        assertEquals(0f, InsertShapeGeometry.strokePxToSlider(0.1f), 0.001f)
        assertEquals(1f, InsertShapeGeometry.strokePxToSlider(500f), 0.001f)

        // Sub-pixel range gets generous slider travel (>= 20% of track for 0.1..1.0 px)
        assertTrue(InsertShapeGeometry.strokePxToSlider(1f) >= 0.25f)

        // Fine adaptive stepping across decimal and large ranges
        assertEquals(0.2f, InsertShapeGeometry.stepStrokePx(0.1f, increase = true), 0.001f)
        assertEquals(0.9f, InsertShapeGeometry.stepStrokePx(1.0f, increase = false), 0.001f)
        assertEquals(1.5f, InsertShapeGeometry.stepStrokePx(1.0f, increase = true), 0.001f)
        assertEquals(6.0f, InsertShapeGeometry.stepStrokePx(5.0f, increase = true), 0.001f)
        assertEquals(25.0f, InsertShapeGeometry.stepStrokePx(20.0f, increase = true), 0.001f)
        assertEquals(110.0f, InsertShapeGeometry.stepStrokePx(100.0f, increase = true), 0.001f)
        assertEquals(0.1f, InsertShapeGeometry.stepStrokePx(0.1f, increase = false), 0.001f)

        // Parsing supports both dot and Indonesian comma decimals plus clamping
        assertEquals(0.1f, InsertShapeGeometry.parseStrokeInput("0.1")!!, 0.001f)
        assertEquals(0.5f, InsertShapeGeometry.parseStrokeInput("0,5")!!, 0.001f)
        assertEquals(0.1f, InsertShapeGeometry.parseStrokeInput("0.01")!!, 0.001f)
        assertEquals(500f, InsertShapeGeometry.parseStrokeInput("999")!!, 0.001f)
        assertNull(InsertShapeGeometry.parseStrokeInput("abc"))

        assertEquals("0.1", InsertShapeGeometry.formatStrokePx(0.1f))
        assertEquals("1.5", InsertShapeGeometry.formatStrokePx(1.5f))
        assertEquals("24", InsertShapeGeometry.formatStrokePx(24f))
    }

    @Test
    fun compositeFillAndStrokeProducesExactTwoColorStraightRgba() {
        // 3 pixels: [0]=empty, [1]=fill only, [2]=50% yellow stroke over 100% blue fill
        val fillMask = byteArrayOf(0, 255.toByte(), 255.toByte())
        val strokeMask = byteArrayOf(0, 0, 128.toByte())
        val rgba = InsertShapeGeometry.compositeFillAndStrokeToStraightRgba(
            fillMask = fillMask,
            fillRgb = 0x0000FF,
            fillOpacity = 1f,
            strokeMask = strokeMask,
            strokeRgb = 0xFFFF00,
            strokeOpacity = 1f,
            globalOpacity = 1f,
        )
        assertEquals(12, rgba.size)
        // Pixel 0: transparent
        assertEquals(0, rgba[3].toInt() and 0xFF)
        // Pixel 1: exact blue fill
        assertEquals(0, rgba[4].toInt() and 0xFF)
        assertEquals(0, rgba[5].toInt() and 0xFF)
        assertEquals(255, rgba[6].toInt() and 0xFF)
        assertEquals(255, rgba[7].toInt() and 0xFF)
        // Pixel 2: ~50% yellow over 100% blue -> opaque blend (R~128, G~128, B~127, A=255)
        assertEquals(128, rgba[8].toInt() and 0xFF)
        assertEquals(128, rgba[9].toInt() and 0xFF)
        assertEquals(127, rgba[10].toInt() and 0xFF)
        assertEquals(255, rgba[11].toInt() and 0xFF)

        // Sub-pixel 0.1 px stroke alone keeps exact RGB even at low alpha coverage
        val hairline = InsertShapeGeometry.compositeFillAndStrokeToStraightRgba(
            fillMask = null,
            fillRgb = 0,
            fillOpacity = 0f,
            strokeMask = byteArrayOf(26),
            strokeRgb = 0xE84F51,
            strokeOpacity = 1f,
            globalOpacity = 1f,
        )
        assertEquals(0xE8, hairline[0].toInt() and 0xFF)
        assertEquals(0x4F, hairline[1].toInt() and 0xFF)
        assertEquals(0x51, hairline[2].toInt() and 0xFF)
        assertEquals(26, hairline[3].toInt() and 0xFF)
    }

    @Test
    fun fillAndStrokeTogglesPreventBlankShapeAndAutoContrastWorks() {
        val strokeOnly = InsertShapeSpec(fillEnabled = true, strokeEnabled = false).withFillToggled(false)
        assertFalse(strokeOnly.effectiveFillEnabled)
        assertTrue(strokeOnly.effectiveStrokeEnabled)

        val fillOnly = InsertShapeSpec(fillEnabled = false, strokeEnabled = true).withStrokeToggled(false)
        assertTrue(fillOnly.effectiveFillEnabled)
        assertFalse(fillOnly.effectiveStrokeEnabled)

        val line = InsertShapeSpec(kind = InsertShapeKind.LINE, fillEnabled = true, strokeEnabled = false)
        assertFalse(line.effectiveFillEnabled)
        assertTrue(line.effectiveStrokeEnabled)
        assertFalse(line.supportsFill)

        // Auto-contrasting stroke: dark fill -> white stroke; light fill -> near-black stroke
        assertEquals(0xFFFFFF, InsertShapeGeometry.contrastingStrokeRgb(0x101010))
        assertEquals(0x141414, InsertShapeGeometry.contrastingStrokeRgb(0xF5F5F5))

        val swapped = InsertShapeSpec(fillRgb = 0xFF0000, strokeRgb = 0x00FF00).withSwappedColors(0x000000)
        assertEquals(0x00FF00, swapped.resolvedFillRgb(0x000000))
        assertEquals(0xFF0000, swapped.resolvedStrokeRgb(0x000000))

        assertEquals(0xFF5500, InsertShapeGeometry.parseHexRgb("#FF5500"))
        assertEquals(0xFF5500, InsertShapeGeometry.parseHexRgb("F50"))
        assertEquals("#0A1B2C", InsertShapeGeometry.formatHexRgb(0x0A1B2C))
        assertNull(InsertShapeGeometry.parseHexRgb("ZZZZZZ"))
    }
}
