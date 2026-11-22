package io.github.artcraftandroid.procraft.canvas

import android.content.SharedPreferences
import android.view.InputDevice
import java.util.Locale

/**
 * Builds a stable persistence key for an input device: `"vvvv:pppp"` when USB/BT vendor and
 * product IDs are present (e.g. `"28bd:2904"` for an XP-Pen tablet), falling back to the
 * system device descriptor or name when both IDs are zero.
 */
internal fun devicePressureKey(
    vendorId: Int,
    productId: Int,
    descriptor: String? = null,
    name: String? = null,
): String? = when {
    vendorId != 0 || productId != 0 -> "%04x:%04x".format(Locale.ROOT, vendorId and 0xFFFF, productId and 0xFFFF)
    !descriptor.isNullOrBlank() -> "desc:${descriptor.trim()}"
    !name.isNullOrBlank() -> "name:${name.trim()}"
    else -> null
}

/** Stable persistence key for [device], or null if unavailable. */
internal fun devicePressureKeyOf(device: InputDevice?): String? {
    device ?: return null
    return devicePressureKey(device.vendorId, device.productId, device.descriptor, device.name)
}

/**
 * Per-device, vendor-agnostic pressure auto-gain learner.
 *
 * Some USB OTG / Bluetooth drawing tablets (e.g. 12/13/14-bit ADCs inside a 16-bit HID logical
 * range) report raw normalized pressure values well below `1.0` even at firm press (for example
 * `0.000..0.084` on Android 15). Tracking the observed raw peak per device allows scaling
 * under-reporting devices into the full `0..1` brush dynamic range while leaving properly
 * calibrated styluses (whose peak reaches >= [ENGAGE_THRESHOLD]) completely untouched.
 */
internal class PenPressureLearner(
    private val loadAll: () -> Map<String, Float> = { emptyMap() },
    private val saveRef: (String, Float) -> Unit = { _, _ -> },
    private val clearStore: () -> Unit = {},
) {
    private val refs = HashMap<String, Float>()

    init {
        for ((k, v) in loadAll()) {
            if (v.isFinite() && v in MIN_REF..MAX_REF) {
                refs[k] = v
            }
        }
    }

    /** Stored reference peak for [deviceKey], or null if unseen. */
    fun refOf(deviceKey: String?): Float? = deviceKey?.let { refs[it] }

    /**
     * Effective maximum raw normalized pressure to divide by for [deviceKey].
     *
     * Auto-gain engages ONLY when the device's learned reference peak is strictly below
     * [ENGAGE_THRESHOLD] (`0.5`). Devices that reach `>= 0.5` return `1.0f` (unchanged).
     * When engaged, multiplies `ref` by [HEADROOM] (`1.15`) so a comfortable firm press reaches
     * near `1.0` without hard-clipping light/medium shading.
     */
    fun effectiveMax(deviceKey: String?): Float {
        val ref = refOf(deviceKey) ?: return MAX_REF
        if (ref >= ENGAGE_THRESHOLD) return MAX_REF
        return (ref * HEADROOM).coerceIn(MIN_REF, MAX_REF)
    }

    /** True when auto-gain is currently boosting pressure for [deviceKey]. */
    fun isGainActive(deviceKey: String?): Boolean {
        val ref = refOf(deviceKey) ?: return false
        return ref < ENGAGE_THRESHOLD
    }

    /**
     * Updates the learned reference peak for [deviceKey] at the end of a completed pen stroke
     * whose peak raw normalized pressure was [rawPeak].
     *
     * Update rule:
     * - Higher peaks raise `ref` immediately (`peak > ref -> peak`).
     * - Lower peaks blend smoothly (`ref + (peak - ref) * 0.1`), clamped to `[MIN_REF, MAX_REF]`.
     * - A device already proven to reach full range (`prev >= 0.5`) never decays below `0.5`,
     *   so sketching many light strokes on a normal S-Pen never triggers unintended boost.
     */
    fun onStrokeEnd(deviceKey: String, rawPeak: Float): Float? {
        if (!rawPeak.isFinite() || rawPeak < MIN_LEARNABLE_PEAK) return refs[deviceKey]
        val peak = rawPeak.coerceIn(MIN_REF, MAX_REF)
        val prev = refs[deviceKey]
        val next = when {
            prev == null -> peak
            peak > prev -> peak
            prev >= ENGAGE_THRESHOLD -> (prev + (peak - prev) * DECAY_RATE).coerceIn(ENGAGE_THRESHOLD, MAX_REF)
            else -> (prev + (peak - prev) * DECAY_RATE).coerceIn(MIN_REF, MAX_REF)
        }
        refs[deviceKey] = next
        saveRef(deviceKey, next)
        return next
    }

    /**
     * Explicitly calibrates [deviceKey] using a comfortable firm press peak [rawPeak] from the
     * Drawing Tablet settings "Calibrate pressure" flow, replacing any prior learned reference.
     */
    fun setCalibratedPeak(deviceKey: String, rawPeak: Float): Float? {
        if (!rawPeak.isFinite() || rawPeak < MIN_LEARNABLE_PEAK) return null
        val calibrated = rawPeak.coerceIn(MIN_REF, MAX_REF)
        refs[deviceKey] = calibrated
        saveRef(deviceKey, calibrated)
        return calibrated
    }

    /** Clears all learned per-device reference peaks (both in memory and persisted preferences). */
    fun clearAll() {
        refs.clear()
        clearStore()
    }

    companion object {
        const val PREF_PREFIX = "pen_pressure_ref."
        const val MIN_REF = 0.02f
        const val MAX_REF = 1.0f
        const val ENGAGE_THRESHOLD = 0.5f
        const val HEADROOM = 1.15f
        const val DECAY_RATE = 0.1f
        const val MIN_LEARNABLE_PEAK = 0.005f

        /** Creates a [PenPressureLearner] backed by [prefs]. */
        fun backedBy(prefs: SharedPreferences): PenPressureLearner = PenPressureLearner(
            loadAll = {
                buildMap {
                    for ((k, v) in prefs.all) {
                        if (k.startsWith(PREF_PREFIX) && v is Float) {
                            put(k.removePrefix(PREF_PREFIX), v)
                        }
                    }
                }
            },
            saveRef = { key, value ->
                prefs.edit().putFloat(PREF_PREFIX + key, value).apply()
            },
            clearStore = {
                val edit = prefs.edit()
                for (k in prefs.all.keys) {
                    if (k.startsWith(PREF_PREFIX)) edit.remove(k)
                }
                edit.apply()
            },
        )
    }
}
