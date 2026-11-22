package io.github.artcraftandroid.procraft.canvas

/** Grace period after a stroke ends before auto-hidden bars glide back (milliseconds). */
const val AUTO_HIDE_RESTORE_DELAY_MS = 550L

/**
 * Pure policy deciding whether the canvas chrome (TopBar / SideBar / QuickFlip) should
 * temporarily auto-hide while the user is actively stroking the canvas.
 */
object AutoHideUiPolicy {
    fun shouldAutoHide(
        autoHideEnabled: Boolean,
        strokeActive: Boolean,
        manualBarsHidden: Boolean,
        panelOpen: Boolean,
        modeActive: Boolean,
    ): Boolean = autoHideEnabled && strokeActive && !manualBarsHidden && !panelOpen && !modeActive

    /**
     * Responsive scale factor for the 260.dp Floating Color companion on narrow smartphone screens
     * so it stays clear of the sidebar without clipping.
     */
    fun floatingColorScale(screenWidthDp: Float, pluginScale: Float = 1f): Float {
        val base = pluginScale.coerceIn(0.5f, 1.5f)
        if (screenWidthDp >= 380f) return base
        val fit = ((screenWidthDp - 64f) / 260f).coerceIn(0.76f, 1f)
        return minOf(base, fit)
    }

    /**
     * Default initial anchor `(xPx, yPx)` when undocking the Color panel via the smartphone Float
     * button: tucked below the top bar on the side opposite the sidebar.
     */
    fun initialFloatingOrigin(
        screenWidthPx: Float,
        topBarBottomPx: Float,
        floatingWidthPx: Float,
        marginPx: Float,
        rightHanded: Boolean,
    ): Pair<Float, Float> {
        val x = if (rightHanded) {
            marginPx
        } else {
            (screenWidthPx - floatingWidthPx - marginPx).coerceAtLeast(marginPx)
        }
        val y = topBarBottomPx + marginPx
        return x to y
    }
}
