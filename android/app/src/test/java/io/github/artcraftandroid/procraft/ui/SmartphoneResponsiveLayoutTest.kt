package io.github.artcraftandroid.procraft.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.procraft.canvas.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartphoneResponsiveLayoutTest {
    @Test
    fun smartphoneScreensUseBottomSheetPosture() {
        // Portrait smartphones (320dp..430dp wide)
        for (width in listOf(320.dp, 360.dp, 390.dp, 412.dp, 430.dp)) {
            assertTrue("$width", ResponsiveLayout.isSmartphoneLayout(width, 800.dp))
        }
        // Landscape smartphones (height < 500dp)
        assertTrue(ResponsiveLayout.isSmartphoneLayout(800.dp, 360.dp))
        assertTrue(ResponsiveLayout.isSmartphoneLayout(896.dp, 414.dp))
        // Large tablets remain in desktop/popover mode
        assertFalse(ResponsiveLayout.isSmartphoneLayout(744.dp, 1133.dp))
        assertFalse(ResponsiveLayout.isSmartphoneLayout(1133.dp, 744.dp))
    }

    @Test
    fun brushSetsColumnAdaptsOnNarrowSmartphoneSheets() {
        assertEquals(168.dp, ResponsiveLayout.brushSetsColumnWidth(Dp.Unspecified))
        assertEquals(168.dp, ResponsiveLayout.brushSetsColumnWidth(440.dp))
        assertEquals(136.dp, ResponsiveLayout.brushSetsColumnWidth(390.dp))
        val compact360 = ResponsiveLayout.brushSetsColumnWidth(360.dp)
        assertTrue("$compact360", compact360 in 112.dp..132.dp)
        // Leaves at least 220dp for brush preview cards on a 360dp phone
        assertTrue(360.dp - compact360 >= 220.dp)
    }

    @Test
    fun colorPickerScalesDownOnlyWhenNarrowerThan376Dp() {
        assertEquals(1f, ResponsiveLayout.colorPickerScale(Dp.Unspecified), 1e-4f)
        assertEquals(1f, ResponsiveLayout.colorPickerScale(390.dp), 1e-4f)
        assertEquals(1f, ResponsiveLayout.colorPickerScale(376.dp), 1e-4f)
        val scale360 = ResponsiveLayout.colorPickerScale(360.dp)
        assertTrue("$scale360", scale360 < 1f && 364.dp * scale360 <= 360.dp - 16.dp + 0.1.dp)
    }

    @Test
    fun bottomSheetFractionsLeaveCanvasVisibleInPeekMode() {
        for (panel in listOf(Panel.BRUSHES, Panel.LAYERS, Panel.COLOR, Panel.ACTIONS, Panel.ADJUSTMENTS)) {
            val peek = ResponsiveLayout.sheetFraction(panel, expanded = false, isLandscape = false)
            val full = ResponsiveLayout.sheetFraction(panel, expanded = true, isLandscape = false)
            assertTrue("$panel peek=$peek", peek in 0.50f..0.65f)
            assertTrue("$panel full=$full", full > peek && full <= 0.90f)
        }
    }
}
