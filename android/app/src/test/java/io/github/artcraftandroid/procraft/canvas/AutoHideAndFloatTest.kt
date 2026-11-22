package io.github.artcraftandroid.procraft.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHideAndFloatTest {
    @Test
    fun autoHideEngagesOnlyWhenEnabledAndActivelyStrokingCleanCanvas() {
        assertTrue(
            AutoHideUiPolicy.shouldAutoHide(
                autoHideEnabled = true,
                strokeActive = true,
                manualBarsHidden = false,
                panelOpen = false,
                modeActive = false,
            ),
        )
        assertFalse(
            AutoHideUiPolicy.shouldAutoHide(
                autoHideEnabled = false,
                strokeActive = true,
                manualBarsHidden = false,
                panelOpen = false,
                modeActive = false,
            ),
        )
        assertFalse(
            AutoHideUiPolicy.shouldAutoHide(
                autoHideEnabled = true,
                strokeActive = false,
                manualBarsHidden = false,
                panelOpen = false,
                modeActive = false,
            ),
        )
        assertFalse(
            AutoHideUiPolicy.shouldAutoHide(
                autoHideEnabled = true,
                strokeActive = true,
                manualBarsHidden = true,
                panelOpen = false,
                modeActive = false,
            ),
        )
        assertFalse(
            AutoHideUiPolicy.shouldAutoHide(
                autoHideEnabled = true,
                strokeActive = true,
                manualBarsHidden = false,
                panelOpen = true,
                modeActive = false,
            ),
        )
        assertFalse(
            AutoHideUiPolicy.shouldAutoHide(
                autoHideEnabled = true,
                strokeActive = true,
                manualBarsHidden = false,
                panelOpen = false,
                modeActive = true,
            ),
        )
    }

    @Test
    fun floatingColorScaleShrinksGracefullyOnNarrowPhones() {
        assertEquals(1f, AutoHideUiPolicy.floatingColorScale(412f, 1f), 1e-4f)
        assertEquals(0.85f, AutoHideUiPolicy.floatingColorScale(412f, 0.85f), 1e-4f)
        val narrow = AutoHideUiPolicy.floatingColorScale(320f, 1f)
        assertTrue(narrow in 0.76f..0.99f)
    }

    @Test
    fun initialFloatingOriginClearsSidebarOnBothHandedness() {
        val (leftHandX, leftHandY) = AutoHideUiPolicy.initialFloatingOrigin(
            screenWidthPx = 1080f,
            topBarBottomPx = 132f,
            floatingWidthPx = 700f,
            marginPx = 36f,
            rightHanded = false,
        )
        assertEquals(344f, leftHandX, 1e-3f)
        assertEquals(168f, leftHandY, 1e-3f)

        val (rightHandX, rightHandY) = AutoHideUiPolicy.initialFloatingOrigin(
            screenWidthPx = 1080f,
            topBarBottomPx = 132f,
            floatingWidthPx = 700f,
            marginPx = 36f,
            rightHanded = true,
        )
        assertEquals(36f, rightHandX, 1e-3f)
        assertEquals(168f, rightHandY, 1e-3f)
    }
}
