package io.github.artcraftandroid.procraft.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.procraft.canvas.Panel

/**
 * Smartphone-first responsive layout tokens and posture detection.
 *
 * Devices with a narrow viewport (< 600.dp width, or phones in landscape with height < 500.dp)
 * use Procreate Pocket-style bottom sheets, collapsible side controls, and adaptive column widths
 * so every interactive element maintains a >= 44.dp touch target and zero horizontal overflow.
 */
object ResponsiveLayout {
    val CompactBreakpoint = 600.dp
    val LandscapePhoneHeightBreakpoint = 500.dp
    val MinTouchTarget = 44.dp
    val BottomSheetCorner = 28.dp
    val BottomSheetHandleHeight = 28.dp

    /** Whether the current window should use smartphone (Procreate Pocket) bottom-sheet chrome. */
    fun isSmartphoneLayout(screenWidth: Dp, screenHeight: Dp = screenWidth): Boolean =
        screenWidth < CompactBreakpoint || minOf(screenWidth, screenHeight) < LandscapePhoneHeightBreakpoint

    /**
     * Responsive width for the Brush Library's left sets column so both brush sets and brush
     * preview cards fit comfortably on 320dp-412dp smartphone screens without clipping.
     */
    fun brushSetsColumnWidth(panelWidth: Dp): Dp = when {
        panelWidth == Dp.Unspecified || panelWidth >= 430.dp -> 168.dp
        panelWidth >= 380.dp -> 136.dp
        else -> (panelWidth * 0.35f).coerceIn(112.dp, 132.dp)
    }

    /**
     * Scale factor for the 364.dp colour disc / 344.dp classic square when hosted in a narrow
     * smartphone bottom sheet (< 376.dp).
     */
    fun colorPickerScale(panelWidth: Dp): Float =
        if (panelWidth == Dp.Unspecified || panelWidth >= 376.dp) 1f
        else ((panelWidth - 16.dp) / 364.dp).coerceIn(0.72f, 1f)

    /**
     * Default peek fraction and expanded fraction of screen height for a smartphone bottom sheet.
     */
    fun sheetFraction(panel: Panel, expanded: Boolean, isLandscape: Boolean): Float {
        if (isLandscape) return if (expanded) 0.92f else 0.78f
        return when (panel) {
            Panel.BRUSHES, Panel.COLOR -> if (expanded) 0.88f else 0.62f
            Panel.LAYERS -> if (expanded) 0.86f else 0.58f
            Panel.ACTIONS, Panel.ADJUSTMENTS -> if (expanded) 0.86f else 0.60f
            Panel.NONE -> 0f
        }
    }
}

/** True when a panel is rendered inside a Smartphone Bottom Sheet instead of a floating popover. */
val LocalCompactSheet = staticCompositionLocalOf { false }

/** The active width of the current sheet or panel container. */
val LocalSheetWidth = staticCompositionLocalOf { Dp.Unspecified }
