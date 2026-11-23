package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.core.QuickShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShapeUiTest {
    @Test
    fun smartphoneMetricsMeetMinimumTapTargetsAndStayWithinScreenBounds() {
        for (width in listOf(320f, 360f, 412f, 540f)) {
            val m = QuickShapeLayout.metrics(screenWidthDp = width, choiceCount = 3)
            assertTrue("Expected compact mode at ${width}dp", m.compact)
            assertTrue("Header height must be >= 44dp on phones", m.headerHeightDp >= 44f)
            assertTrue("Chip height must be >= 40dp on phones", m.chipHeightDp >= 40f)
            assertTrue("Choice strip max width must stay inside screen bounds", m.maxChoiceStripWidthDp <= width - m.horizontalMarginDp * 2f)
            assertTrue("Smartphone handles should be larger than tablet handles", m.handleOuterRadiusDp > 6.5f)
        }
    }

    @Test
    fun narrowPhonesUseCompactLabelsOnlyWhenThreeOrMoreChoicesShow() {
        assertTrue(QuickShapeLayout.metrics(360f, choiceCount = 3).useCompactLabels)
        assertFalse(QuickShapeLayout.metrics(360f, choiceCount = 2).useCompactLabels)
        assertFalse(QuickShapeLayout.metrics(430f, choiceCount = 3).useCompactLabels)
        assertFalse(QuickShapeLayout.metrics(800f, choiceCount = 3).useCompactLabels)
    }

    @Test
    fun tabletMetricsKeepDesktopScale() {
        val m = QuickShapeLayout.metrics(screenWidthDp = 1024f, choiceCount = 3)
        assertFalse(m.compact)
        assertEquals(6.5f, m.handleOuterRadiusDp, 0.01f)
        assertEquals(5f, m.handleInnerRadiusDp, 0.01f)
    }

    @Test
    fun allQuickShapeKindsHaveValidFullAndCompactLabels() {
        for (kind in QuickShapeKind.entries) {
            val full = QuickShapeLayout.label(kind, compact = false)
            val compact = QuickShapeLayout.label(kind, compact = true)
            assertTrue(full.isNotBlank())
            assertTrue(compact.isNotBlank())
            assertTrue("Compact label should not be longer than full label for $kind", compact.length <= full.length)
        }
    }
}
