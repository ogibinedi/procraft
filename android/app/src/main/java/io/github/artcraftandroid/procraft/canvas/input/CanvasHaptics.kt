package io.github.artcraftandroid.procraft.canvas

import android.os.Build
import android.view.HapticFeedbackConstants

/** Tactile feedback cues triggered on key canvas interactions. */
enum class HapticCue(val cooldownMs: Long) {
    /** Crisp tick for QuickShape snap, transform guide snap, and slider notch snap. */
    SNAP(60L),
    /** Subtle light tick for Undo / Redo steps. */
    STEP(35L),
    /** Medium confirmation pulse for Eyedropper color pick and ColorDrop fill. */
    PICK(80L),
    /** Crisp tick for quick canvas flip and fit-to-screen reset. */
    FLIP(60L),
}

/**
 * Pure-Kotlin rate limiter preventing rapid continuous gestures from buzzing the haptic motor
 * excessively while keeping discrete snaps crisp.
 */
class HapticGate {
    private val lastFired = HashMap<HapticCue, Long>()

    /** Returns true if [cue] should fire at [nowMs], recording the firing time. */
    fun shouldFire(cue: HapticCue, nowMs: Long): Boolean {
        val prev = lastFired[cue]
        if (prev != null && nowMs - prev < cue.cooldownMs) return false
        lastFired[cue] = nowMs
        return true
    }

    fun reset() {
        lastFired.clear()
    }
}

/**
 * Tracks active transform snap guide lines so haptic snap fires only when snapping onto a new
 * guide (not every frame while sliding along the same guide).
 */
class TransformSnapTracker {
    private var lastSignature: List<Int> = emptyList()

    /** Returns true when [snapLines] newly snaps onto one or more guides or switches guide lines. */
    fun update(snapLines: List<Float>): Boolean {
        if (snapLines.size < 4) {
            lastSignature = emptyList()
            return false
        }
        // Quantize coordinates to nearest 2px so tiny floating-point noise doesn't re-trigger.
        val sig = snapLines.map { (it / 2f).toInt() }
        val changed = sig != lastSignature
        lastSignature = sig
        return changed
    }

    fun clear() {
        lastSignature = emptyList()
    }
}

/**
 * Tracks which slider notch the thumb is currently snapped to, firing once upon entering a notch.
 */
class SliderNotchTracker {
    private var activeNotch: Float? = null

    /** Returns true if [snappedNotch] is non-null and different from the previously active notch. */
    fun update(snappedNotch: Float?): Boolean {
        if (snappedNotch == null) {
            activeNotch = null
            return false
        }
        val prev = activeNotch
        activeNotch = snappedNotch
        return prev == null || kotlin.math.abs(prev - snappedNotch) > 1e-4f
    }

    fun clear() {
        activeNotch = null
    }
}

/** Maps [HapticCue] to a permission-free Android [HapticFeedbackConstants] constant. */
internal fun hapticConstantFor(cue: HapticCue, sdkInt: Int = Build.VERSION.SDK_INT): Int = when (cue) {
    HapticCue.SNAP -> HapticFeedbackConstants.CLOCK_TICK
    HapticCue.STEP -> HapticFeedbackConstants.CLOCK_TICK
    HapticCue.FLIP -> HapticFeedbackConstants.CLOCK_TICK
    HapticCue.PICK -> if (sdkInt >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
}
