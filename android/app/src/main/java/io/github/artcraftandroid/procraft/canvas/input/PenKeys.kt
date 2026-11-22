package io.github.artcraftandroid.procraft.canvas

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import io.github.artcraftandroid.procraft.core.PointerKind
import io.github.artcraftandroid.procraft.core.QuickAction
import io.github.artcraftandroid.procraft.core.TouchSettings

/** The key Huawei's M-Pencil gives for a double-tap, twice over for each one. */
internal const val PEN_DOUBLE_TAP_KEY = 718

/** Presses of that key closer than this (ms) are one double-tap given twice. */
private const val DOUBLE_TAP_GIVEN_TWICE_MS = 300L

/** What a pen's button that comes as a key did: pressed, let go, or (a double-tap's key) tapped, never held. */
enum class PenKeyAction { PRESSED, LET_GO, DOUBLE_TAPPED }

/**
 * A pen's first side button, as a motion event's button state has it: Android's own bit, or the
 * mouse's right button, which older pens' drivers give it as (Android did before it had its own).
 */
internal const val PEN_FIRST = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY

/** Its second, the same way: Android's own bit, or the mouse's middle button. */
internal const val PEN_SECOND = MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY

/** A pen's two side buttons, as a motion event's button state has them. */
internal const val PEN_BUTTONS = PEN_FIRST or PEN_SECOND

/** The pen's first and second side button, as [PenButtons] tells them: each whichever bit it came as. */
internal const val FIRST_BUTTON = 1
internal const val SECOND_BUTTON = 2

/** Which of the pen's side buttons ([FIRST_BUTTON], [SECOND_BUTTON]) [buttonState] has held. */
internal fun penButtonsIn(buttonState: Int): Int =
    (if (buttonState and PEN_FIRST != 0) FIRST_BUTTON else 0) or (if (buttonState and PEN_SECOND != 0) SECOND_BUTTON else 0)

/** Whether a pointer of [toolType] from [source] is a pen's (its tip or its eraser end): see [pointerKind]. */
internal fun isPen(toolType: Int, source: Int) = pointerKind(toolType, source).let { it == PointerKind.STYLUS || it == PointerKind.STYLUS_ERASER }

/** Whether an event's [source] says it came from a pen (a Bluetooth pen's says so too). */
private fun fromStylus(source: Int) = source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS

/**
 * A touchpad's gestures, as an event's classification says (Android 14's
 * `CLASSIFICATION_TWO_FINGER_SWIPE`, `CLASSIFICATION_MULTI_FINGER_SWIPE` and `CLASSIFICATION_PINCH`):
 * fingers on the pad moving the view, not the pointer.
 */
private val TOUCHPAD_GESTURES = setOf(3, 4, 5)

/**
 * What a pointer is to the canvas, by its [toolType], the [source] its event came from and the
 * event's [classification] (0 where the system has none): a pen's tip or eraser end, or a pointer
 * whose driver doesn't say what it is (an unknown tool type) in an event from a pen; a mouse, or a
 * touchpad moving the pointer (Android gives that one a finger's tool type), which does what a
 * touch is set to do, with no pressure, and is never taken for a hand resting by the pen; else a
 * finger. A touchpad's own swipes and pinches stay fingers'.
 */
internal fun pointerKind(toolType: Int, source: Int, classification: Int = 0): PointerKind = when {
    toolType == MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
    toolType == MotionEvent.TOOL_TYPE_ERASER -> PointerKind.STYLUS_ERASER
    toolType == MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
    toolType == MotionEvent.TOOL_TYPE_UNKNOWN && fromStylus(source) -> PointerKind.STYLUS
    source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE && classification !in TOUCHPAD_GESTURES -> PointerKind.MOUSE
    else -> PointerKind.FINGER
}

/** Known external drawing tablet / digitizer brand & firmware names (USB OTG & Bluetooth). */
private val DRAWING_TABLET_NAME = Regex(
    """\b(xp[- ]?pen|xppen|huion|inspiroy|kamvas|wacom|intuos|cintiq|gaomon|veikk|ugee|ugtablet|parblo|xencelabs|bosto|artisul|acepen|hanvon|hanwang|uclogic|waltop|turcom|monoprice|pentablet|digitizer|pen\s*tablet|pen\s*display|drawing\s*tablet|graphics\s*tablet|deco\s*(0?\d|mini|fun|pro|l|lw|m|mw)|star\s*g\d+)\b""",
    RegexOption.IGNORE_CASE,
)

/** Names that denote a multi-touch finger/trackpad interface rather than a pen digitizer. */
private val TOUCH_OVERLAY_NAME = Regex(
    """\b(touchpad|trackpad|touchscreen|finger|touch)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * USB Vendor IDs dedicated to graphics / drawing tablet manufacturers (USB OTG & wired/wireless
 * dongles). Used alongside hardware capability checks so generic kernel names like `HID 28bd:0905`
 * are still recognized reliably across all brands.
 */
internal val DRAWING_TABLET_VENDORS = setOf(
    0x28BD, // XP-Pen / Ugee / Hanvon Ugee / Xencelabs
    0x256C, // Huion / Gaomon / Parblo / Artisul / UC-Logic
    0x5543, // UC-Logic (legacy XP-Pen / Huion / Ugee / Turcom / Monoprice)
    0x056A, // Wacom (Intuos, One, Cintiq, Movink, Bamboo)
    0x2FEB, // VEIKK
    0x172F, // Waltop (OEM graphics tablets)
    0x0B57, // Hanvon
    0x2179, // Bosto
    0x34A0, // Xencelabs
)

/** Minimum positive pressure sent during active external-tablet contact so Rust never swallows a stroke as hover. */
internal const val EXTERNAL_CONTACT_MIN_PRESSURE = 0.02f

/** Minimum raw pressure during hover-only kernel mode to treat hover pressure as intentional contact (filters XP-Pen low-IAF drift). */
internal const val HOVER_PRESSURE_STROKE_THRESHOLD = 0.08f

internal fun isTouchOverlayName(deviceName: String?): Boolean {
    if (deviceName.isNullOrBlank()) return false
    return TOUCH_OVERLAY_NAME.containsMatchIn(deviceName) && !PEN_NAME.containsMatchIn(deviceName)
}

/**
 * Whether an input device is an external drawing tablet (XP-Pen, Huion, Wacom, Gaomon, Veikk,
 * Ugee, Parblo, Xencelabs, or any other brand): detected primarily by hardware capabilities
 * (stylus source or external pressure/tilt/distance axes), with USB Vendor ID and brand/driver
 * name fallbacks for kernels that expose tablets in generic HID mouse or touch mode.
 */
internal fun isDrawingTabletDevice(
    deviceName: String?,
    deviceSources: Int,
    hasPressureAxis: Boolean,
    isExternal: Boolean,
    vendorId: Int = 0,
    hasTiltOrDistance: Boolean = false,
): Boolean {
    if (fromStylus(deviceSources)) return true
    if (isExternal && (hasPressureAxis || hasTiltOrDistance) && !isTouchOverlayName(deviceName)) return true
    if (isExternal && vendorId in DRAWING_TABLET_VENDORS && !isTouchOverlayName(deviceName)) return true
    if (deviceName != null && DRAWING_TABLET_NAME.containsMatchIn(deviceName) && !isTouchOverlayName(deviceName)) return true
    return false
}

/** Whether [e]'s input device advertises an `AXIS_PRESSURE` motion range with positive span. */
internal fun pressureRangeOf(e: MotionEvent): ClosedFloatingPointRange<Float>? {
    val dev = e.device ?: return null
    val r = dev.getMotionRange(MotionEvent.AXIS_PRESSURE, e.source)
        ?: dev.getMotionRange(MotionEvent.AXIS_PRESSURE)
        ?: return null
    return if (r.range > 0f) r.min..r.max else null
}

/** Whether [e]'s input device advertises a pen tilt or hover distance axis. */
internal fun tiltOrDistanceRangeOf(e: MotionEvent): Boolean {
    val dev = e.device ?: return false
    return dev.getMotionRange(MotionEvent.AXIS_TILT) != null ||
        dev.getMotionRange(MotionEvent.AXIS_DISTANCE) != null
}

internal fun isExternalDevice(e: MotionEvent): Boolean {
    val dev = e.device ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        dev.isExternal
    } else {
        dev.vendorId != 0 || dev.productId != 0 || (dev.sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE)
    }
}

/** Reads the raw pressure of pointer [i] in [e] (or its historical sample [historyIdx]). */
internal fun rawPressureOf(e: MotionEvent, i: Int, historyIdx: Int = -1): Float {
    val p = if (historyIdx >= 0) e.getHistoricalPressure(i, historyIdx) else e.getPressure(i)
    if (p > 0f) return p
    return if (historyIdx >= 0) {
        e.getHistoricalAxisValue(MotionEvent.AXIS_PRESSURE, i, historyIdx)
    } else {
        e.getAxisValue(MotionEvent.AXIS_PRESSURE, i)
    }
}

/**
 * Whether [e] comes from an external USB/Bluetooth drawing tablet or external pointer (as opposed
 * to the device's built-in touchscreen or built-in stylus like an S-Pen).
 */
internal fun isExternalDrawingTabletEvent(e: MotionEvent, drawingTabletMode: Boolean = true): Boolean {
    if (e.pointerCount <= 0 || !isExternalDevice(e)) return false
    val toolType = e.getToolType(0)
    val classification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) e.classification else 0
    if (classification in TOUCHPAD_GESTURES) return false
    val base = pointerKind(toolType, e.source, classification)
    if (base == PointerKind.STYLUS || base == PointerKind.STYLUS_ERASER) return true
    val dev = e.device
    val hasPressure = pressureRangeOf(e) != null
    val hasTiltOrDist = tiltOrDistanceRangeOf(e)
    val devSources = dev?.sources ?: e.source
    val vendorId = dev?.vendorId ?: 0
    if (isDrawingTabletDevice(dev?.name, devSources, hasPressure, isExternal = true, vendorId = vendorId, hasTiltOrDistance = hasTiltOrDist)) {
        return true
    }
    return drawingTabletMode && base == PointerKind.MOUSE
}

/**
 * What pointer [index] of [e] is to the canvas (see [pointerKind]).
 *
 * When [drawingTabletMode] is enabled (or when hardware capabilities / USB VID / brand identify an
 * external drawing tablet), pointers that Android's HID driver reports as `TOOL_TYPE_MOUSE`,
 * `TOOL_TYPE_UNKNOWN`, or single-pointer Android-mode `TOOL_TYPE_FINGER` are promoted to
 * [PointerKind.STYLUS] so they paint with full pressure sensitivity, show hover cursors, and work
 * even when finger painting is disabled.
 */
internal fun pointerKindOf(e: MotionEvent, index: Int, drawingTabletMode: Boolean = false): PointerKind {
    val toolType = e.getToolType(index)
    val classification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) e.classification else 0
    val base = pointerKind(toolType, e.source, classification)
    if (base == PointerKind.STYLUS || base == PointerKind.STYLUS_ERASER) return base
    if (classification in TOUCHPAD_GESTURES) return PointerKind.FINGER
    if (!isExternalDevice(e)) return base
    val dev = e.device
    val hasPressure = pressureRangeOf(e) != null
    val hasTiltOrDist = tiltOrDistanceRangeOf(e)
    val devSources = dev?.sources ?: e.source
    val vendorId = dev?.vendorId ?: 0
    val isTablet = isDrawingTabletDevice(
        deviceName = dev?.name,
        deviceSources = devSources,
        hasPressureAxis = hasPressure,
        isExternal = true,
        vendorId = vendorId,
        hasTiltOrDistance = hasTiltOrDist,
    )
    if (base == PointerKind.MOUSE || toolType == MotionEvent.TOOL_TYPE_UNKNOWN) {
        if (isTablet) return PointerKind.STYLUS
        if (drawingTabletMode && base == PointerKind.MOUSE) return PointerKind.STYLUS
    } else if (base == PointerKind.FINGER && e.pointerCount == 1) {
        // Some XP-Pen / Huion / Gaomon / Veikk tablets in Android Phone Mode report TOOL_TYPE_FINGER
        // from an external digitizer device. Promote single-pointer strokes unless the device is a touch overlay.
        if (isTablet && (fromStylus(devSources) || hasTiltOrDist || vendorId in DRAWING_TABLET_VENDORS || drawingTabletMode)) {
            return PointerKind.STYLUS
        }
    }
    return base
}

/**
 * Tracks per-device pressure characteristics observed during actual pen contact (never poisoned
 * by hover drift or constant-1.0 mouse click pressure):
 * - whether a device with `axisMax > 1.5` actually emits raw unnormalized integer values (`> 1.05`)
 *   versus already-normalized `0..1` floats;
 * - whether a device has ever reported genuine analog pressure during contact;
 * - whether a device has reported zero hover pressure (filters constant-1.0 hover kernel bugs).
 */
internal class TabletPressureTracker {
    private val rawExceedsUnit = HashSet<Int>()
    private val analogDevices = HashSet<Int>()
    private val firstPositiveSample = HashMap<Int, Float>()
    private val zeroHoverSeen = HashSet<Int>()

    /** Records a raw pressure reading observed during non-hover contact on [deviceId]. */
    fun recordContactPressure(deviceId: Int, raw: Float, hasPressureAxis: Boolean) {
        if (!raw.isFinite() || raw <= 0f) return
        if (raw > 1.05f) {
            rawExceedsUnit += deviceId
            if (hasPressureAxis) analogDevices += deviceId
            return
        }
        if (!hasPressureAxis) return
        // Fractional pressure (strictly between 0 and 1) is unambiguous analog stylus data.
        // Constant 1.0f clicks (from Android's CursorInputMapper mouse mode) do not count as analog
        // unless another distinct positive pressure level is also observed.
        if (raw < 0.999f) {
            analogDevices += deviceId
        } else {
            val prev = firstPositiveSample.putIfAbsent(deviceId, raw)
            if (prev != null && kotlin.math.abs(prev - raw) > 0.005f) {
                analogDevices += deviceId
            }
        }
    }

    /** Records a raw pressure reading observed during hover on [deviceId]. */
    fun recordHoverPressure(deviceId: Int, raw: Float) {
        if (raw.isFinite() && raw <= 0.02f) zeroHoverSeen += deviceId
    }

    /** True once [deviceId] has emitted raw pressure values above 1.05 (unnormalized HID range). */
    fun usesRawRange(deviceId: Int): Boolean = deviceId in rawExceedsUnit

    /** True once [deviceId] has emitted genuine varying/analog pressure during contact. */
    fun hasAnalogPressure(deviceId: Int): Boolean = deviceId in analogDevices

    /** True once [deviceId] has emitted near-zero pressure while hovering. */
    fun sawZeroHover(deviceId: Int): Boolean = deviceId in zeroHoverSeen
}

/**
 * Normalizes a raw hardware pressure reading into `0f..1f` using the device's advertised axis
 * range, without applying per-device auto-gain, sensitivity, or floor.
 *
 * Handles already-normalized `0..1` values even when `axisMax > 1.5` (e.g. kernel reports `8191`
 * while `MotionEvent.getPressure()` is `0..1`), genuinely unnormalized raw ranges (`raw > 1.05` or
 * [rawExceedsUnit] true), and sub-unit or shifted ranges (`axisMax < 0.9` or `axisMin != 0`).
 */
internal fun normalizeRawPressure(
    rawPressure: Float,
    axisMin: Float,
    axisMax: Float,
    hasPressureAxis: Boolean,
    rawExceedsUnit: Boolean = false,
): Float {
    val cleanRaw = when {
        rawPressure.isNaN() -> 0f
        rawPressure.isInfinite() -> if (rawPressure > 0f) 1f else 0f
        else -> rawPressure
    }
    if (!hasPressureAxis) return cleanRaw.coerceIn(0f, 1f)
    val span = axisMax - axisMin
    val needsRangeNorm = when {
        span <= 0f -> false
        axisMax > 1.5f -> cleanRaw > 1.05f || rawExceedsUnit
        axisMax < 0.9f || axisMin != 0f -> true
        else -> false
    }
    return if (needsRangeNorm) {
        ((cleanRaw - axisMin) / span).coerceIn(0f, 1f)
    } else {
        cleanRaw.coerceIn(0f, 1f)
    }
}

/**
 * Normalizes and calibrates raw stylus/tablet pressure into `0f..1f`.
 *
 * Handles:
 * - Already-normalized `0..1` `MotionEvent.getPressure()` even when `MotionRange.max` reports raw
 *   HID max (e.g. `8191`, `4095`, `16383`) — avoids dividing `0.5` by `8191` into invisible dust!
 * - Genuinely unnormalized raw HID ranges (`raw > 1.05` or [rawExceedsUnit] true) and sub-unit
 *   ranges (`axisMax < 0.9` or `axisMin != 0`).
 * - Under-reporting devices whose real peak pressure is far below `1.0` (e.g. `0.000..0.084` on
 *   12/13/14-bit tablets inside a 16-bit HID descriptor) via [learnedMax] applied BEFORE
 *   sensitivity and floor.
 * - Missing or always-zero kernel pressure axes on external USB OTG drawing tablets (XP-Pen,
 *   Huion, Wacom, Gaomon, Veikk, etc.), even when `buttonState == 0` (`TouchInputMapper`).
 * - Active external-tablet contact (`externalActiveContact = true`) where the first `ACTION_DOWN`
 *   frame arrives with `pressure == 0` right before `ABS_PRESSURE` ramps up: returns at least
 *   [EXTERNAL_CONTACT_MIN_PRESSURE] so Rust starts the stroke immediately instead of hovering.
 * - Optional pressure disable, user sensitivity multiplier (`0.25x..3.0x`), and minimum floor.
 */
internal fun calibratePenPressure(
    rawPressure: Float,
    axisMin: Float,
    axisMax: Float,
    hasPressureAxis: Boolean,
    pressureEnabled: Boolean,
    sensitivity: Float,
    minFloor: Float,
    buttonPrimaryDown: Boolean = false,
    isHovering: Boolean = false,
    fallbackFullWhenUnreported: Boolean = false,
    rawExceedsUnit: Boolean = false,
    externalActiveContact: Boolean = false,
    learnedMax: Float = 1f,
): Float {
    val cleanRaw = when {
        rawPressure.isNaN() -> 0f
        rawPressure.isInfinite() -> if (rawPressure > 0f) 1f else 0f
        else -> rawPressure
    }
    if (!pressureEnabled) {
        return if (isHovering && !buttonPrimaryDown && cleanRaw <= 0f) 0f else 1f
    }
    if (!hasPressureAxis) {
        if (cleanRaw > 0f) {
            val scaled = (cleanRaw.coerceIn(0f, 1f) * sensitivity.coerceIn(0.25f, 3f)).coerceIn(0f, 1f)
            val floor = minFloor.coerceIn(0f, 0.5f)
            return (floor + scaled * (1f - floor)).coerceIn(0f, 1f)
        }
        return if (!isHovering || buttonPrimaryDown) 1f else 0f
    }
    val rawNorm = normalizeRawPressure(cleanRaw, axisMin, axisMax, hasPressureAxis = true, rawExceedsUnit = rawExceedsUnit)
    val divisor = learnedMax.takeIf { it.isFinite() && it > 0f } ?: 1f
    val norm = if (divisor < 1f) (rawNorm / divisor).coerceIn(0f, 1f) else rawNorm
    if (norm <= 0f) {
        if (isHovering) return 0f
        if (fallbackFullWhenUnreported && (buttonPrimaryDown || externalActiveContact)) return 1f
        if (externalActiveContact) return maxOf(minFloor.coerceIn(0f, 0.5f), EXTERNAL_CONTACT_MIN_PRESSURE)
        return 0f
    }
    val sens = sensitivity.coerceIn(0.25f, 3f)
    val floor = minFloor.coerceIn(0f, 0.5f)
    val scaled = (norm * sens).coerceIn(0f, 1f)
    return (floor + scaled * (1f - floor)).coerceIn(0f, 1f)
}

/**
 * Chooses the Android system [PointerIcon] type for a stylus or mouse event:
 * - Over any UI (bars, panels, bottom sheets, menus, studios, floating windows, dialogs): ALWAYS
 *   [PointerIcon.TYPE_ARROW] so UI navigation is never blind.
 * - Over the open painting canvas when [hideTabletArrowCursor] is on and our own brush cursor is
 *   actively visible: [PointerIcon.TYPE_NULL] so no OS arrow obstructs the brush tip.
 * - Over the open canvas otherwise: [PointerIcon.TYPE_CROSSHAIR] when [hideTabletArrowCursor] is
 *   enabled, or [PointerIcon.TYPE_ARROW] when disabled.
 */
internal fun resolveTabletPointerIconType(
    overOpenCanvas: Boolean,
    hideTabletArrowCursor: Boolean,
    brushCursorVisible: Boolean,
): Int = when {
    !overOpenCanvas -> PointerIcon.TYPE_ARROW
    !hideTabletArrowCursor -> PointerIcon.TYPE_ARROW
    brushCursorVisible -> PointerIcon.TYPE_NULL
    else -> PointerIcon.TYPE_CROSSHAIR
}

/**
 * Whether the brush outline cursor should be shown for the current state. Screenless external
 * drawing tablets keep the cursor visible while painting too so the artist never draws blind.
 */
internal fun shouldShowBrushCursor(
    brushCursorOn: Boolean,
    hasPosition: Boolean,
    hovering: Boolean,
    cursorShows: CanvasViewModel.CursorShows,
    externalTabletActive: Boolean,
): Boolean {
    if (!brushCursorOn || !hasPosition) return false
    return if (hovering) {
        cursorShows != CanvasViewModel.CursorShows.PAINTING
    } else {
        cursorShows != CanvasViewModel.CursorShows.HOVERING || externalTabletActive
    }
}

/**
 * [settings] as the tablet takes them: a mouse, or a touchpad moving the pointer, does what a finger
 * is set to do when [pointerFollowsTouch] is true. With drawing tablet mode enabled, setting
 * [pointerFollowsTouch] to false ensures external pointers always paint even when finger painting
 * is turned off. The pen's buttons do what Pen buttons set them to ([penButtons], the first
 * and the second; null, what Gesture controls say).
 */
internal fun tabletTouchSettings(
    settings: TouchSettings,
    penButtons: List<QuickAction?> = emptyList(),
    pointerFollowsTouch: Boolean = true,
): TouchSettings = settings.copy(
    pointerFollowsTouch = pointerFollowsTouch,
    penButtonDoes = penButtons.getOrNull(0),
    penSecondButtonDoes = penButtons.getOrNull(1),
)

/**
 * Whether a mouse's press with [buttonState] held paints: its primary button's does (and a press
 * that says of no button, as a touchpad's tap may), its others' do nothing on the canvas.
 */
internal fun mousePressPaints(buttonState: Int): Boolean = buttonState == 0 || buttonState and MotionEvent.BUTTON_PRIMARY != 0

/** The pen's side buttons as its events say they are held, from one event to the next. */
internal class PenButtons {
    private var held = 0

    /** The pen's touch events have shown a button held: its driver puts them in, so one missing as it lifts was let go. */
    private var touchesShowButtons = false

    /**
     * The buttons pressed and let go ([FIRST_BUTTON], [SECOND_BUTTON]) since the last event, by this
     * one's [buttonState]. An event without the pen in it ([penIn] false: a hand's touch, whose buttons read
     * as none held) changes nothing, or a hand landing while a button is held would let it go. While
     * the pen is [touching], a button that goes missing isn't let go yet: some pens' touch events
     * leave out a button their hover shows held, which would hand the eraser back as the stroke
     * begins. As the pen [lifting] lifts it is let go, unless its touches have never shown a button:
     * then the lift leaves it out too, and the next hover says whether it is still held (let go
     * there and pressed again as the pen hovers on, one press would switch to the eraser and
     * straight back, and the size slider with it). Nor is it let go as the pen [leaving] leaves
     * hover, as it does on its way down onto the glass: some pens' hover exit leaves out a button
     * still held (the Galaxy Tab S6 Lite's), which handed the eraser back just before the stroke,
     * so a press only switched to the eraser and back. Leaving for good, its next hover or touch
     * says.
     */
    fun next(buttonState: Int, penIn: Boolean, touching: Boolean = false, lifting: Boolean = false, leaving: Boolean = false): Pair<Int, Int> {
        if (!penIn) return 0 to 0
        val shown = penButtonsIn(buttonState)
        if ((touching || lifting) && shown != 0) touchesShowButtons = true
        val kept = touching || leaving || (lifting && !touchesShowButtons)
        val now = shown or (if (kept) held else 0)
        val change = (now and held.inv()) to (held and now.inv())
        held = now
        return change
    }
}

/** Whether a motion event of [action] is the pen on the glass ([PenButtons.next]'s touching), or lifting from it (its lifting). */
internal fun penTouching(action: Int): Boolean = when (action) {
    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> true
    else -> false
}

internal fun penLifting(action: Int): Boolean = action == MotionEvent.ACTION_UP

/** Whether a motion event of [action] is the pen leaving hover ([PenButtons.next]'s leaving). */
internal fun penLeaving(action: Int): Boolean = action == MotionEvent.ACTION_HOVER_EXIT

/** Tells a pen's next double-tap from the second press of the one just heard. */
internal class PenDoubleTaps {
    private var heardAt: Long? = null

    /** Whether a press at [at] (ms) is a double-tap of its own. */
    fun isNew(at: Long): Boolean {
        val last = heardAt
        if (last != null && at - last < DOUBLE_TAP_GIVEN_TWICE_MS) return false
        heardAt = at
        return true
    }
}

/** A device named as a pen, as a word of its own ("Smart Pen", not "Open Keyboard"). */
private val PEN_NAME = Regex("""\b(pen|stylus)\b""", RegexOption.IGNORE_CASE)

/**
 * Which of a pen's two side buttons [keyCode] is (true: the second), from a device that has
 * [sources], is of [keyboardType] and is called [name]; null if it is no pen's. Some pens, as
 * Redmi's Smart Pen, give their buttons as Page Up and Page Down. A key's own source says keyboard
 * whatever else its device is, so this goes by the device: a stylus, one without letters, or one
 * named as a pen (a pen paired over Bluetooth may list a whole keyboard's keys). Those keys stay a
 * typing keyboard's own.
 */
internal fun penKeyButton(keyCode: Int, sources: Int, keyboardType: Int, name: String): Boolean? {
    val second = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_UP -> false
        KeyEvent.KEYCODE_PAGE_DOWN -> true
        else -> return null
    }
    val stylus = sources and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS
    val pen = stylus || keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC || PEN_NAME.containsMatchIn(name)
    return second.takeIf { pen }
}

/**
 * Whether a key from a device with [sources] called [name] may be a pen's: a stylus, or one named
 * as a pen. What a pen's button sends that the canvas doesn't take goes in the log, once a key.
 */
internal fun maybePenKeys(sources: Int, name: String): Boolean =
    sources and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS || PEN_NAME.containsMatchIn(name)

/** Each key from each device told once in the log. */
internal class KeysTold {
    private val told = HashSet<Pair<Int, Int>>()

    /** Whether [keyCode] from device [deviceId] is yet to be told (it counts as told now). */
    fun isNew(deviceId: Int, keyCode: Int): Boolean = told.add(deviceId to keyCode)
}

/** Each kind of pointer the canvas has seen from each device, told once in the log. */
internal class PointersTold {
    private val told = HashSet<Triple<Int, Int, Int>>()

    /** Whether a pointer of [toolType] in an event from [source] of device [deviceId] is yet to be told (it counts as told now). */
    fun isNew(deviceId: Int, toolType: Int, source: Int): Boolean = told.add(Triple(deviceId, toolType, source))
}

/** Android's tool types by number. */
private val TOOL_NAMES = listOf("unknown", "finger", "stylus", "mouse", "eraser", "palm")

/**
 * What the log says of a kind of pointer the first time it comes ([what]: down, or hovering): its
 * [device] (its vendor and product, and a built-in one's name), [toolType] and event [source],
 * what the canvas takes it for ([kind]), its first [pressure] and the pressure its device says it
 * gives ([pressureAxis], least to most; null, none), the [buttons] held and how big its touch is
 * ([touchMajor] pixels): what a report of a pen that doesn't paint needs.
 */
internal fun pointerFacts(
    what: String,
    device: String,
    toolType: Int,
    source: Int,
    kind: PointerKind,
    pressure: Float,
    pressureAxis: ClosedFloatingPointRange<Float>?,
    buttons: Int,
    touchMajor: Float,
): String {
    val tool = TOOL_NAMES.getOrNull(toolType) ?: "$toolType"
    val axis = pressureAxis?.let { "%.2f to %.2f".format(java.util.Locale.ROOT, it.start, it.endInclusive) } ?: "none"
    return "a pointer %s: %s, tool %s, source 0x%x, taken for %s; pressure %.3f (its device gives %s); buttons 0x%x; touch %.1f px".format(
        java.util.Locale.ROOT, what, device, tool, source, kind.name.lowercase(java.util.Locale.ROOT), pressure, axis, buttons, touchMajor,
    )
}

/** Each device's first pen stroke, its pressures summed up for the log: whether the pen gives a pressure, and how much. */
internal class PenStrokesTold {
    private val told = HashSet<Int>()

    /** The device whose first stroke this is (none: not being summed up), its samples and its least and most pressure. */
    private var device: Int? = null
    private var count = 0
    private var least = 0f
    private var most = 0f

    /** A pen's stroke began on device [deviceId]: summed up if it is that device's first. */
    fun began(deviceId: Int) {
        device = deviceId.takeIf { it !in told }
        count = 0
        least = Float.POSITIVE_INFINITY
        most = Float.NEGATIVE_INFINITY
    }

    /** Whether the stroke's samples are being summed up. */
    val summing get() = device != null

    /** One of its samples' [pressure]. */
    fun sample(pressure: Float) {
        if (device == null) return
        count++
        least = minOf(least, pressure)
        most = maxOf(most, pressure)
    }

    /** The stroke lifted (or was called off): the line for the log, if it was its device's first. */
    fun lifted(): String? {
        val d = device ?: return null
        device = null
        told += d
        if (count == 0) return "the first pen stroke from device $d: no samples"
        return "the first pen stroke from device %d: %d samples, pressure %.3f to %.3f".format(java.util.Locale.ROOT, d, count, least, most)
    }
}
