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

    @Test
    fun smartphoneBottomSheetLiftsCleanlyAboveKeyboard() {
        // Without keyboard, bottom padding is 0 and sheet fits below top bar
        val (noKbHeight, noKbPad) = sheetKeyboardLayout(
            screenHeightDp = 820f,
            targetHeightDp = 450f,
            dragUpDp = 0f,
            topBarDp = 44f,
            keyboardDp = 0f,
        )
        assertEquals(450f, noKbHeight, 0.01f)
        assertEquals(0f, noKbPad, 0.01f)

        // Portrait smartphone with 300dp keyboard: sheet lifts by 300dp and stays inside viewport
        val (portraitHeight, portraitPad) = sheetKeyboardLayout(
            screenHeightDp = 820f,
            targetHeightDp = 450f,
            dragUpDp = 0f,
            topBarDp = 44f,
            keyboardDp = 300f,
        )
        assertEquals(300f, portraitPad, 0.01f)
        assertEquals(450f, portraitHeight, 0.01f)
        assertTrue(portraitHeight + portraitPad < 820f)

        // Landscape phone (380dp tall) with 190dp keyboard: clamps effective height cleanly above keyboard
        val (landscapeHeight, landscapePad) = sheetKeyboardLayout(
            screenHeightDp = 380f,
            targetHeightDp = 320f,
            dragUpDp = 0f,
            topBarDp = 44f,
            keyboardDp = 190f,
        )
        assertEquals(190f, landscapePad, 0.01f)
        assertEquals(182f, landscapeHeight, 0.01f)
        assertTrue(landscapeHeight + landscapePad <= 380f)
    }

    @Test
    fun bottomSheetGrabberStaysClearOfCompactHeaderActions() {
        // Compact ColorPanel header actions start at y = 20.dp; grabber overlay must not overlap them.
        assertTrue(SheetHandleHeight.value <= 20f)
        assertTrue(SheetHandleWidth.value <= 96f)
    }
}
