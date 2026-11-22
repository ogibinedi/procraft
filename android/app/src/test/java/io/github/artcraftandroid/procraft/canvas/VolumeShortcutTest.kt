package io.github.artcraftandroid.procraft.canvas

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeShortcutTest {
    @Test
    fun modeKeyRoundTripsAndFallsBackToOff() {
        for (mode in VolumeKeyMode.entries) {
            assertEquals(mode, VolumeKeyMode.fromKey(mode.key))
        }
        assertEquals(VolumeKeyMode.OFF, VolumeKeyMode.fromKey(null))
        assertEquals(VolumeKeyMode.OFF, VolumeKeyMode.fromKey("invalid"))
    }

    @Test
    fun offOrInactiveContextAlwaysPassesVolumeThrough() {
        assertNull(resolveVolumeKey(VolumeKeyMode.OFF, KeyEvent.KEYCODE_VOLUME_UP, onCanvas = true, typing = false, uiCoveringCanvas = false))
        assertNull(resolveVolumeKey(VolumeKeyMode.UNDO_REDO, KeyEvent.KEYCODE_VOLUME_UP, onCanvas = false, typing = false, uiCoveringCanvas = false))
        assertNull(resolveVolumeKey(VolumeKeyMode.UNDO_REDO, KeyEvent.KEYCODE_VOLUME_DOWN, onCanvas = true, typing = true, uiCoveringCanvas = false))
        assertNull(resolveVolumeKey(VolumeKeyMode.UNDO_REDO, KeyEvent.KEYCODE_VOLUME_UP, onCanvas = true, typing = false, uiCoveringCanvas = true))
        assertNull(resolveVolumeKey(VolumeKeyMode.UNDO_REDO, KeyEvent.KEYCODE_BACK, onCanvas = true, typing = false, uiCoveringCanvas = false))
    }

    @Test
    fun eachModeMapsUpAndDownErgonomically() {
        assertEquals(
            VolumeAction.UNDO,
            resolveVolumeKey(VolumeKeyMode.UNDO_REDO, KeyEvent.KEYCODE_VOLUME_DOWN, onCanvas = true, typing = false, uiCoveringCanvas = false),
        )
        assertEquals(
            VolumeAction.REDO,
            resolveVolumeKey(VolumeKeyMode.UNDO_REDO, KeyEvent.KEYCODE_VOLUME_UP, onCanvas = true, typing = false, uiCoveringCanvas = false),
        )
        assertEquals(
            VolumeAction.BRUSH_LARGER,
            resolveVolumeKey(VolumeKeyMode.BRUSH_SIZE, KeyEvent.KEYCODE_VOLUME_UP, onCanvas = true, typing = false, uiCoveringCanvas = false),
        )
        assertEquals(
            VolumeAction.BRUSH_SMALLER,
            resolveVolumeKey(VolumeKeyMode.BRUSH_SIZE, KeyEvent.KEYCODE_VOLUME_DOWN, onCanvas = true, typing = false, uiCoveringCanvas = false),
        )
        assertEquals(
            VolumeAction.FLIP_HORIZONTAL,
            resolveVolumeKey(VolumeKeyMode.FLIP_FIT, KeyEvent.KEYCODE_VOLUME_UP, onCanvas = true, typing = false, uiCoveringCanvas = false),
        )
        assertEquals(
            VolumeAction.FIT_CANVAS,
            resolveVolumeKey(VolumeKeyMode.FLIP_FIT, KeyEvent.KEYCODE_VOLUME_DOWN, onCanvas = true, typing = false, uiCoveringCanvas = false),
        )
    }

    @Test
    fun repeatPolicyAllowsUndoRedoAndSizeButBlocksFlipAndFit() {
        assertTrue(VolumeAction.UNDO.allowsRepeat)
        assertTrue(VolumeAction.REDO.allowsRepeat)
        assertTrue(VolumeAction.BRUSH_LARGER.allowsRepeat)
        assertTrue(VolumeAction.BRUSH_SMALLER.allowsRepeat)
        assertFalse(VolumeAction.FLIP_HORIZONTAL.allowsRepeat)
        assertFalse(VolumeAction.FIT_CANVAS.allowsRepeat)
    }

    @Test
    fun stepBrushSizeClampsAndRoundsCleanly() {
        assertEquals(0.25f, stepBrushSize(0.20f, VOLUME_BRUSH_STEP), 1e-5f)
        assertEquals(0.15f, stepBrushSize(0.20f, -VOLUME_BRUSH_STEP), 1e-5f)
        assertEquals(1.0f, stepBrushSize(0.98f, VOLUME_BRUSH_STEP), 1e-5f)
        assertEquals(0.0f, stepBrushSize(0.02f, -VOLUME_BRUSH_STEP), 1e-5f)
    }
}
