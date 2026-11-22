package io.github.artcraftandroid.procraft.ui

/**
 * Pure layout & discoverability policies for Procreate Pocket-style smartphone ergonomics.
 */
object PocketErgonomics {
    /**
     * Whether the Color panel shows BOTH Color History and the active palette simultaneously
     * inside its scrollable column instead of toggling between them behind the "•••" menu.
     *
     * On smartphone bottom sheets ([compactSheet] == true), users rely heavily on quick Color
     * History swatches without extra menu taps; on tall screens (![shortScreen]) both fit
     * naturally as well. Only landscape tablet popovers keep the compact toggle.
     */
    fun showBothHistoryAndPalette(shortScreen: Boolean, compactSheet: Boolean): Boolean =
        !shortScreen || compactSheet

    /** Whether the Adjustments menu exposes the built-in interactive Recolor tool directly. */
    fun showRecolorInAdjustments(modelOpen: Boolean): Boolean = !modelOpen

    /** MIME type for 1-tap Save to Gallery exports. */
    fun directGalleryMime(jpeg: Boolean): String = if (jpeg) "image/jpeg" else "image/png"
}

/** One entry in the interactive Gesture Guide shown under Actions -> Help. */
data class GestureGuideItem(
    val gesture: String,
    val action: String,
)

/** A grouped section in the Gesture Guide. */
data class GestureGuideSection(
    val title: String,
    val items: List<GestureGuideItem>,
)

/**
 * Verified catalog of real gestures and shortcuts implemented in ProCraft.
 */
val GESTURE_GUIDE_SECTIONS: List<GestureGuideSection> = listOf(
    GestureGuideSection(
        title = "Canvas & Navigation",
        items = listOf(
            GestureGuideItem("Pinch & twist (2 fingers)", "Zoom and rotate canvas"),
            GestureGuideItem("Quick pinch", "Fit canvas to screen"),
            GestureGuideItem("4-finger tap", "Toggle full-screen mode"),
            GestureGuideItem("Volume buttons", "Undo/Redo, Brush Size, or Flip/Fit (set in Prefs)"),
        ),
    ),
    GestureGuideSection(
        title = "Undo, Redo & Clipboard",
        items = listOf(
            GestureGuideItem("2-finger tap", "Undo (hold for rapid undo)"),
            GestureGuideItem("3-finger tap", "Redo (hold for rapid redo)"),
            GestureGuideItem("3-finger swipe down", "Open Copy & Paste menu"),
            GestureGuideItem("3-finger scrub", "Clear current layer"),
        ),
    ),
    GestureGuideSection(
        title = "Drawing & Color",
        items = listOf(
            GestureGuideItem("Draw & hold still", "Snap into QuickShape (line, arc, circle, polygon)"),
            GestureGuideItem("Drag Color Dot → hold", "ColorDrop fill; slide left/right for threshold"),
            GestureGuideItem("Hold on canvas", "Eyedropper color picker"),
            GestureGuideItem("Hold Color Dot", "Swap back to previous color"),
        ),
    ),
    GestureGuideSection(
        title = "Layers",
        items = listOf(
            GestureGuideItem("Pinch layers together", "Merge all layers between fingers"),
            GestureGuideItem("2-finger swipe right", "Toggle Alpha Lock"),
            GestureGuideItem("2-finger tap on layer", "Slide on canvas to adjust layer opacity"),
            GestureGuideItem("Swipe right on layer", "Multi-select layers to group, merge, or move"),
        ),
    ),
)
