package io.github.artcraftandroid.procraft.canvas

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasHapticsTest {
    @Test
    fun hapticGateRateLimitsSameCueWithoutBlockingOtherCues() {
        val gate = HapticGate()
        assertTrue(gate.shouldFire(HapticCue.SNAP, 1000L))
        // Within 60ms cooldown -> blocked
        assertFalse(gate.shouldFire(HapticCue.SNAP, 1030L))
        // Independent cue still fires immediately
        assertTrue(gate.shouldFire(HapticCue.STEP, 1030L))
        // After cooldown elapses -> fires again
        assertTrue(gate.shouldFire(HapticCue.SNAP, 1060L))

        gate.reset()
        assertTrue(gate.shouldFire(HapticCue.SNAP, 1065L))
    }

    @Test
    fun transformSnapTrackerFiresOnlyOnNewOrChangedSnapGuides() {
        val tracker = TransformSnapTracker()
        // No snap lines -> no cue
        assertFalse(tracker.update(emptyList()))
        // First snap onto vertical center line -> fires
        assertTrue(tracker.update(listOf(500f, 0f, 500f, 1000f)))
        // Tiny sub-2px jitter on same snap line -> suppressed
        assertFalse(tracker.update(listOf(500.5f, 0f, 500.5f, 1000f)))
        // Adding horizontal center snap line -> fires
        assertTrue(tracker.update(listOf(500f, 0f, 500f, 1000f, 0f, 500f, 1000f, 500f)))
        // Moving away from guides -> resets state without firing
        assertFalse(tracker.update(emptyList()))
        // Re-snapping onto the same line later -> fires again
        assertTrue(tracker.update(listOf(500f, 0f, 500f, 1000f)))
    }

    @Test
    fun sliderNotchTrackerFiresOncePerNotchEntry() {
        val tracker = SliderNotchTracker()
        assertFalse(tracker.update(null))
        // Crossing into notch 0.25 -> fires once
        assertTrue(tracker.update(0.25f))
        // Staying on notch 0.25 -> silent
        assertFalse(tracker.update(0.25f))
        // Sliding directly to another notch 0.50 -> fires
        assertTrue(tracker.update(0.50f))
        // Leaving notch -> silent
        assertFalse(tracker.update(null))
        // Re-entering notch 0.50 -> fires again
        assertTrue(tracker.update(0.50f))
    }

    @Test
    fun hapticConstantsMapCleanlyAcrossApiLevels() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstantFor(HapticCue.SNAP, sdkInt = 28))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstantFor(HapticCue.STEP, sdkInt = 34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstantFor(HapticCue.FLIP, sdkInt = 34))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, hapticConstantFor(HapticCue.PICK, sdkInt = 29))
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticConstantFor(HapticCue.PICK, sdkInt = 30))
    }
}
