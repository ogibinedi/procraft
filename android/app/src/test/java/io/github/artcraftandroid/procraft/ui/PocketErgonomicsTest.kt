package io.github.artcraftandroid.procraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketErgonomicsTest {
    @Test
    fun colorPanelShowsBothHistoryAndPaletteOnSmartphoneSheetsAndTallScreens() {
        // Smartphone portrait/landscape bottom sheet -> always shows both
        assertTrue(PocketErgonomics.showBothHistoryAndPalette(shortScreen = true, compactSheet = true))
        assertTrue(PocketErgonomics.showBothHistoryAndPalette(shortScreen = false, compactSheet = true))
        // Tall tablet popover -> shows both
        assertTrue(PocketErgonomics.showBothHistoryAndPalette(shortScreen = false, compactSheet = false))
        // Short landscape tablet popover -> preserves compact toggle
        assertFalse(PocketErgonomics.showBothHistoryAndPalette(shortScreen = true, compactSheet = false))
    }

    @Test
    fun recolorShownInAdjustmentsOnlyOn2dCanvas() {
        assertTrue(PocketErgonomics.showRecolorInAdjustments(modelOpen = false))
        assertFalse(PocketErgonomics.showRecolorInAdjustments(modelOpen = true))
    }

    @Test
    fun directGalleryMimeMatchesFormat() {
        assertEquals("image/png", PocketErgonomics.directGalleryMime(jpeg = false))
        assertEquals("image/jpeg", PocketErgonomics.directGalleryMime(jpeg = true))
    }

    @Test
    fun gestureGuideCatalogIsPopulatedAndUnique() {
        assertTrue(GESTURE_GUIDE_SECTIONS.size >= 4)
        val allItems = GESTURE_GUIDE_SECTIONS.flatMap { it.items }
        assertTrue(allItems.size >= 12)
        for (section in GESTURE_GUIDE_SECTIONS) {
            assertTrue(section.title.isNotBlank())
            assertTrue(section.items.isNotEmpty())
            for (item in section.items) {
                assertTrue(item.gesture.isNotBlank())
                assertTrue(item.action.isNotBlank())
            }
        }
        assertEquals(allItems.size, allItems.map { it.gesture }.distinct().size)
    }
}
