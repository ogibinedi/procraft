package io.github.artcraftandroid.procraft.canvas

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.artcraftandroid.procraft.core.GestureAction
import io.github.artcraftandroid.procraft.core.PointerKind
import io.github.artcraftandroid.procraft.core.QuickAction
import io.github.artcraftandroid.procraft.core.TouchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenKeysTest {
    private val up = KeyEvent.KEYCODE_PAGE_UP
    private val down = KeyEvent.KEYCODE_PAGE_DOWN
    private val keyboard = InputDevice.SOURCE_KEYBOARD
    private val letters = InputDevice.KEYBOARD_TYPE_ALPHABETIC
    private val noLetters = InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC

    @Test
    fun aDoubleTapGivenTwiceCountsOnce() {
        val taps = PenDoubleTaps()
        assertEquals(true, taps.isNew(1_000))
        assertEquals(false, taps.isNew(1_020))
        // the next one, soon after, measured from the first of the pair
        assertEquals(true, taps.isNew(1_320))
        assertEquals(false, taps.isNew(1_340))
    }

    @Test
    fun aPensPageKeysAreItsFirstAndSecondButton() {
        val pen = keyboard or InputDevice.SOURCE_STYLUS
        assertEquals(false, penKeyButton(up, pen, noLetters, "touch"))
        assertEquals(true, penKeyButton(down, pen, noLetters, "touch"))
    }

    @Test
    fun aDeviceThatIsAStylusToo() {
        val touchScreen = keyboard or InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
        assertEquals(false, penKeyButton(up, touchScreen, letters, "digitizer"))
        val bluetooth = keyboard or InputDevice.SOURCE_BLUETOOTH_STYLUS
        assertEquals(true, penKeyButton(down, bluetooth, letters, "digitizer"))
    }

    @Test
    fun aKeyOnlyDeviceWithoutLetters() {
        assertEquals(false, penKeyButton(up, keyboard, noLetters, "hid-keys"))
    }

    @Test
    fun aDeviceNamedAsAPenThoughItListsAWholeKeyboard() {
        for (name in listOf("Redmi Smart Pen", "Xiaomi Smart Pen Keyboard", "pen", "S-PEN", "Active Stylus")) {
            assertEquals(name, true, penKeyButton(down, keyboard, letters, name))
        }
    }

    @Test
    fun aTypingKeyboardKeepsItsPageKeys() {
        for (name in listOf("Logitech K380", "AT Translated Set 2 keyboard", "Virtual", "Open Keyboard", "Pendragon 60", "Spen")) {
            assertNull(name, penKeyButton(up, keyboard, letters, name))
            assertNull(name, penKeyButton(down, keyboard, letters, name))
        }
    }

    @Test
    fun noOtherKeyIsAButton() {
        val pen = keyboard or InputDevice.SOURCE_STYLUS
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)) {
            assertNull(penKeyButton(key, pen, noLetters, "Smart Pen"))
        }
    }

    @Test
    fun aPensButtonsArePressedAndLetGoByWhatItsEventsSay() {
        val first = MotionEvent.BUTTON_STYLUS_PRIMARY
        val second = MotionEvent.BUTTON_STYLUS_SECONDARY
        val buttons = PenButtons()
        assertEquals(FIRST_BUTTON to 0, buttons.next(first, penIn = true))
        // still held: nothing new; another button's state that isn't a pen's counts for nothing
        assertEquals(0 to 0, buttons.next(first or MotionEvent.BUTTON_PRIMARY, penIn = true))
        // a hand landing while it is held reads as none held, and lets nothing go
        assertEquals(0 to 0, buttons.next(0, penIn = false))
        assertEquals(SECOND_BUTTON to FIRST_BUTTON, buttons.next(second, penIn = true))
        assertEquals(0 to SECOND_BUTTON, buttons.next(0, penIn = true))
    }

    @Test
    fun a_pen_s_second_button_is_heard_whichever_way_its_driver_gives_it() {
        val buttons = PenButtons()
        // as older drivers give a pen's buttons: the mouse's right and middle buttons
        assertEquals(SECOND_BUTTON to 0, buttons.next(MotionEvent.BUTTON_TERTIARY, penIn = true))
        assertEquals(0 to SECOND_BUTTON, buttons.next(0, penIn = true))
        assertEquals(FIRST_BUTTON to 0, buttons.next(MotionEvent.BUTTON_SECONDARY, penIn = true))
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn = true))
        // one button given both ways at once is still one press
        val both = MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_TERTIARY
        assertEquals(SECOND_BUTTON to 0, buttons.next(both, penIn = true))
        assertEquals(0 to 0, buttons.next(MotionEvent.BUTTON_TERTIARY, penIn = true))
        assertEquals(0 to SECOND_BUTTON, buttons.next(0, penIn = true))
        // the two pressed in one event are both pressed
        val two = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        assertEquals((FIRST_BUTTON or SECOND_BUTTON) to 0, buttons.next(two, penIn = true))
        assertEquals(0 to (FIRST_BUTTON or SECOND_BUTTON), buttons.next(0, penIn = true))
        // a mouse's right or middle button (no pen in the event) is no pen's
        assertEquals(0 to 0, buttons.next(MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY, penIn = false))
        assertEquals(0, penButtonsIn(MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_BACK or MotionEvent.BUTTON_FORWARD))
    }

    @Test
    fun a_pen_s_second_button_that_comes_as_a_key_is_its_second() {
        // as Redmi's Smart Pen gives its two buttons: Page Up, Page Down
        val pen = keyboard or InputDevice.SOURCE_STYLUS
        assertEquals(true, penKeyButton(down, pen, noLetters, "Redmi Smart Pen"))
        assertEquals(true, penKeyButton(down, keyboard, letters, "Redmi Smart Pen"))
        assertEquals(false, penKeyButton(up, keyboard, letters, "Redmi Smart Pen"))
    }

    @Test
    fun a_button_held_over_the_canvas_isn_t_let_go_by_touch_events_that_leave_it_out() {
        val first = MotionEvent.BUTTON_STYLUS_PRIMARY
        val buttons = PenButtons()
        // held while hovering
        assertEquals(FIRST_BUTTON to 0, buttons.next(first, penIn = true))
        // the pen touches and moves, its events without the button: still held
        assertEquals(0 to 0, buttons.next(0, penIn = true, touching = true))
        assertEquals(0 to 0, buttons.next(0, penIn = true, touching = true))
        // lifted (or hovering) without it: let go
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn = true))
        // pressed while touching still counts at once
        assertEquals(FIRST_BUTTON to 0, buttons.next(first, penIn = true, touching = true))
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn = true))
    }

    @Test
    fun a_button_held_through_a_stroke_whose_events_leave_it_out_is_one_press() {
        val first = MotionEvent.BUTTON_STYLUS_PRIMARY
        val buttons = PenButtons()
        // pressed hovering: once
        assertEquals(FIRST_BUTTON to 0, buttons.next(first, penIn = true))
        // the stroke, its lift too, without it: still held, not let go and pressed again as the pen
        // hovers on with it (which switched to the eraser and straight back)
        assertEquals(0 to 0, buttons.next(0, penIn = true, touching = true))
        assertEquals(0 to 0, buttons.next(0, penIn = true, lifting = true))
        assertEquals(0 to 0, buttons.next(first, penIn = true))
        // let go hovering
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn = true))
        // the same, let go during the stroke: the next hover says so
        assertEquals(FIRST_BUTTON to 0, buttons.next(first, penIn = true))
        assertEquals(0 to 0, buttons.next(0, penIn = true, touching = true))
        assertEquals(0 to 0, buttons.next(0, penIn = true, lifting = true))
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn = true))
    }

    /** The buttons pressed and let go by an event of [action] with [buttonState], as the canvas reads one. */
    private fun PenButtons.event(action: Int, buttonState: Int) =
        next(buttonState, penIn = true, penTouching(action), penLifting(action), penLeaving(action))

    @Test
    fun a_button_held_as_the_pen_goes_down_is_still_held_though_its_hover_exit_leaves_it_out() {
        // #322: the Galaxy Tab S6 Lite's S Pen, Erase's Pen button on: held while hovering, then
        // drawing. Its hover exit and touches leave the button out: it is held all through, one
        // press, let go only when a hover says so
        val first = MotionEvent.BUTTON_STYLUS_PRIMARY
        val b = PenButtons()
        assertEquals(0 to 0, b.event(MotionEvent.ACTION_HOVER_MOVE, 0))
        assertEquals(FIRST_BUTTON to 0, b.event(MotionEvent.ACTION_HOVER_MOVE, first))
        assertEquals(0 to 0, b.event(MotionEvent.ACTION_HOVER_EXIT, 0))
        assertEquals(0 to 0, b.event(MotionEvent.ACTION_DOWN, 0))
        assertEquals(0 to 0, b.event(MotionEvent.ACTION_MOVE, 0))
        assertEquals(0 to 0, b.event(MotionEvent.ACTION_UP, 0))
        assertEquals(0 to 0, b.event(MotionEvent.ACTION_HOVER_ENTER, first))
        assertEquals(0 to FIRST_BUTTON, b.event(MotionEvent.ACTION_HOVER_MOVE, 0))
        // a click while hovering is still a press and a let go
        assertEquals(FIRST_BUTTON to 0, b.event(MotionEvent.ACTION_HOVER_MOVE, first))
        assertEquals(0 to FIRST_BUTTON, b.event(MotionEvent.ACTION_HOVER_MOVE, 0))
        // let go out of range: the pen's next hover lets it go
        assertEquals(FIRST_BUTTON to 0, b.event(MotionEvent.ACTION_HOVER_MOVE, first))
        assertEquals(0 to 0, b.event(MotionEvent.ACTION_HOVER_EXIT, 0))
        assertEquals(0 to FIRST_BUTTON, b.event(MotionEvent.ACTION_HOVER_ENTER, 0))
        // a pen whose touches show the button: one press and one let go, never let go and pressed
        // again on the way down (which, left alone in Gesture controls, switched to the eraser and
        // straight back)
        val shows = PenButtons()
        assertEquals(FIRST_BUTTON to 0, shows.event(MotionEvent.ACTION_HOVER_MOVE, first))
        assertEquals(0 to 0, shows.event(MotionEvent.ACTION_HOVER_EXIT, 0))
        assertEquals(0 to 0, shows.event(MotionEvent.ACTION_DOWN, first))
        assertEquals(0 to 0, shows.event(MotionEvent.ACTION_MOVE, first))
        assertEquals(0 to FIRST_BUTTON, shows.event(MotionEvent.ACTION_UP, 0))
        assertEquals(true, penLeaving(MotionEvent.ACTION_HOVER_EXIT))
        for (a in listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            assertEquals(false, penLeaving(a))
        }
    }

    @Test
    fun a_pen_whose_touches_show_its_buttons_lets_go_as_it_lifts_without_one() {
        val first = MotionEvent.BUTTON_STYLUS_PRIMARY
        val buttons = PenButtons()
        // pressed and let go during strokes, a pen with no hover at all: each lift says
        assertEquals(FIRST_BUTTON to 0, buttons.next(first, penIn = true, touching = true))
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn = true, lifting = true))
        // held into the next stroke, let go before it lifts
        assertEquals(FIRST_BUTTON to 0, buttons.next(first, penIn = true, touching = true))
        assertEquals(0 to 0, buttons.next(0, penIn = true, touching = true))
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn = true, lifting = true))
    }

    @Test
    fun which_events_are_the_pen_on_the_glass_and_lifting() {
        for (a in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) {
            assertEquals(true, penTouching(a))
            assertEquals(false, penLifting(a))
        }
        assertEquals(true, penLifting(MotionEvent.ACTION_UP))
        assertEquals(false, penTouching(MotionEvent.ACTION_UP))
        for (a in listOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL)) {
            assertEquals(false, penTouching(a))
            assertEquals(false, penLifting(a))
        }
    }

    @Test
    fun a_mouse_and_a_touchpads_pointer_are_the_mouse_and_a_touchpads_swipes_fingers() {
        val screen = InputDevice.SOURCE_TOUCHSCREEN
        val mouse = InputDevice.SOURCE_MOUSE
        assertEquals(PointerKind.STYLUS, pointerKind(MotionEvent.TOOL_TYPE_STYLUS, screen or InputDevice.SOURCE_STYLUS))
        assertEquals(PointerKind.STYLUS_ERASER, pointerKind(MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS))
        assertEquals(PointerKind.FINGER, pointerKind(MotionEvent.TOOL_TYPE_FINGER, screen))
        assertEquals(PointerKind.MOUSE, pointerKind(MotionEvent.TOOL_TYPE_MOUSE, mouse))
        // a touchpad moving the pointer comes as a finger from the mouse's source
        assertEquals(PointerKind.MOUSE, pointerKind(MotionEvent.TOOL_TYPE_FINGER, mouse or InputDevice.SOURCE_TOUCHPAD))
        assertEquals(PointerKind.MOUSE, pointerKind(MotionEvent.TOOL_TYPE_UNKNOWN, mouse))
        // its two-finger swipe, its three-finger swipe and its pinch move the view as fingers do
        for (gesture in listOf(3, 4, 5)) assertEquals(PointerKind.FINGER, pointerKind(MotionEvent.TOOL_TYPE_FINGER, mouse, gesture))
        // a pen's own source says nothing of a mouse
        assertEquals(PointerKind.STYLUS, pointerKind(MotionEvent.TOOL_TYPE_STYLUS, mouse))
    }

    @Test
    fun only_a_mouses_primary_button_paints() {
        assertEquals(true, mousePressPaints(MotionEvent.BUTTON_PRIMARY))
        assertEquals(true, mousePressPaints(MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY))
        // a touchpad's tap, or a press made up by the system, says of no button
        assertEquals(true, mousePressPaints(0))
        for (other in listOf(MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD)) {
            assertEquals(false, mousePressPaints(other))
        }
    }

    /** The touch settings as the shared touches ship them: a mouse paints as the pen does. */
    private fun shipped(fingerPaint: Boolean, oneFingerPan: Boolean, modelFingerPaint: Boolean) = TouchSettings(
        fingerPaint = fingerPaint, oneFingerPan = oneFingerPan, undoTaps = true, rotate = true, modelFingerPaint = modelFingerPaint,
        drawAndHold = true, touch = null, pen = null, hold = GestureAction.EYEDROPPER, modifyTouch = null, modifyPen = null,
        fourTap = null, threeSwipe = null, penButton = null, penSecondButton = null, scrub = true, hoverTouch = false,
        holdMs = 400.0, quickshapeMs = 650.0, rapidUndoMs = 650.0,
    )

    @Test
    fun on_the_tablet_with_painting_with_a_finger_off_the_mouse_paints_nothing_as_a_finger() {
        assertEquals(false, shipped(fingerPaint = false, oneFingerPan = true, modelFingerPaint = false).pointerFollowsTouch)
        for ((paint, pan) in listOf(false to true, false to false, true to false)) {
            val tablet = tabletTouchSettings(shipped(paint, pan, modelFingerPaint = false))
            // the mouse does what a finger is set to: the finger's settings kept as they were
            assertEquals(true, tablet.pointerFollowsTouch)
            assertEquals(shipped(paint, pan, modelFingerPaint = false), tablet.copy(pointerFollowsTouch = false))
        }
    }

    @Test
    fun the_pen_buttons_set_in_pen_buttons_go_to_the_touches_and_nothing_else_changes() {
        val gestures = shipped(fingerPaint = true, oneFingerPan = false, modelFingerPaint = false)
        // as they start: what Gesture controls say
        val plain = tabletTouchSettings(gestures, listOf(null, null))
        assertNull(plain.penButtonDoes)
        assertNull(plain.penSecondButtonDoes)
        assertEquals(plain, tabletTouchSettings(gestures))
        // one button left to swap the eraser, the other set to undo
        val undo = tabletTouchSettings(gestures, listOf(null, QuickAction.UNDO))
        assertNull(undo.penButtonDoes)
        assertEquals(QuickAction.UNDO, undo.penSecondButtonDoes)
        assertEquals(plain, undo.copy(penSecondButtonDoes = null))
        val both = tabletTouchSettings(gestures, listOf(QuickAction.REDO, QuickAction.UNDO))
        assertEquals(QuickAction.REDO to QuickAction.UNDO, both.penButtonDoes to both.penSecondButtonDoes)
    }

    @Test
    fun a_key_a_pen_sends_that_isnt_taken_is_told_once() {
        assertEquals(true, maybePenKeys(keyboard or InputDevice.SOURCE_STYLUS, "Touch"))
        assertEquals(true, maybePenKeys(keyboard, "Xiaomi Smart Pen"))
        assertEquals(false, maybePenKeys(keyboard, "Virtual"))
        assertEquals(false, maybePenKeys(keyboard, "Open Keyboard"))
        val told = KeysTold()
        assertEquals(true, told.isNew(7, KeyEvent.KEYCODE_F21))
        assertEquals(false, told.isNew(7, KeyEvent.KEYCODE_F21))
        assertEquals(true, told.isNew(7, KeyEvent.KEYCODE_Z))
        assertEquals(true, told.isNew(8, KeyEvent.KEYCODE_F21))
    }

    @Test
    fun on_the_tablet_the_mouse_turns_a_model_as_a_finger_unless_a_finger_paints_it() {
        for (modelPaint in listOf(false, true)) {
            val tablet = tabletTouchSettings(shipped(fingerPaint = true, oneFingerPan = false, modelFingerPaint = modelPaint))
            assertEquals(true, tablet.pointerFollowsTouch)
            assertEquals(modelPaint, tablet.modelFingerPaint)
        }
    }

    @Test
    fun a_pen_whose_driver_gives_no_tool_type_is_still_the_pen() {
        val screen = InputDevice.SOURCE_TOUCHSCREEN
        val unknown = MotionEvent.TOOL_TYPE_UNKNOWN
        // a phone's built-in pen, its tool type left unknown, from the touchscreen and the stylus
        assertEquals(PointerKind.STYLUS, pointerKind(unknown, screen or InputDevice.SOURCE_STYLUS))
        assertEquals(PointerKind.STYLUS, pointerKind(unknown, InputDevice.SOURCE_STYLUS))
        // one paired over Bluetooth, its events marked so, or fused into the touchscreen's
        assertEquals(PointerKind.STYLUS, pointerKind(unknown, InputDevice.SOURCE_BLUETOOTH_STYLUS))
        assertEquals(PointerKind.STYLUS, pointerKind(unknown, screen or InputDevice.SOURCE_BLUETOOTH_STYLUS))
        // and one whose device is a mouse as well: the pen, not the mouse (which paints at no pressure)
        assertEquals(PointerKind.STYLUS, pointerKind(unknown, InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS))
        // a pen's tip and eraser end as ever, whatever else the source says
        for (source in listOf(screen, screen or InputDevice.SOURCE_STYLUS, InputDevice.SOURCE_BLUETOOTH_STYLUS, InputDevice.SOURCE_MOUSE)) {
            assertEquals(PointerKind.STYLUS, pointerKind(MotionEvent.TOOL_TYPE_STYLUS, source))
            assertEquals(PointerKind.STYLUS_ERASER, pointerKind(MotionEvent.TOOL_TYPE_ERASER, source))
        }
        // an unknown tool from the touchscreen alone, or a finger in an event the pen is in, stays a finger
        assertEquals(PointerKind.FINGER, pointerKind(unknown, screen))
        assertEquals(PointerKind.FINGER, pointerKind(MotionEvent.TOOL_TYPE_FINGER, screen or InputDevice.SOURCE_STYLUS))
        // (a palm's tool type is no pen's)
        assertEquals(PointerKind.FINGER, pointerKind(5, screen or InputDevice.SOURCE_STYLUS))
    }

    @Test
    fun a_pen_with_no_tool_type_hovers_and_has_its_buttons_heard() {
        val pen = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
        assertEquals(true, isPen(MotionEvent.TOOL_TYPE_UNKNOWN, pen))
        assertEquals(true, isPen(MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_TOUCHSCREEN))
        assertEquals(false, isPen(MotionEvent.TOOL_TYPE_UNKNOWN, InputDevice.SOURCE_TOUCHSCREEN))
        assertEquals(false, isPen(MotionEvent.TOOL_TYPE_FINGER, pen))
        assertEquals(false, isPen(MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE))
        // its first button held over the canvas and let go: one press, heard as the pen's
        val buttons = PenButtons()
        val penIn = isPen(MotionEvent.TOOL_TYPE_UNKNOWN, pen)
        assertEquals(FIRST_BUTTON to 0, buttons.next(MotionEvent.BUTTON_STYLUS_PRIMARY, penIn))
        assertEquals(0 to FIRST_BUTTON, buttons.next(0, penIn))
    }

    @Test
    fun a_pen_touching_with_no_button_held_presses_none() {
        // an S Pen's touch, its button state none (or only the touch's own primary bit): no button,
        // so it never switches to the eraser
        val buttons = PenButtons()
        for (state in listOf(0, MotionEvent.BUTTON_PRIMARY)) {
            assertEquals(0 to 0, buttons.next(state, penIn = true, touching = true))
            assertEquals(0 to 0, buttons.next(state, penIn = true, lifting = true))
            assertEquals(0 to 0, buttons.next(state, penIn = true))
        }
    }

    @Test
    fun the_log_tells_what_a_kind_of_pointer_is_and_gives() {
        val pen = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS
        assertEquals(
            "a pointer down: device 0000:0000 \"sec_e-pen\", tool unknown, source 0x5002, taken for stylus; " +
                "pressure 0.000 (its device gives 0.00 to 1.00); buttons 0x20; touch 3.5 px",
            pointerFacts("down", "device 0000:0000 \"sec_e-pen\"", MotionEvent.TOOL_TYPE_UNKNOWN, pen, PointerKind.STYLUS, 0f, 0f..1f, MotionEvent.BUTTON_STYLUS_PRIMARY, 3.5f),
        )
        assertEquals(
            "a pointer hovering: no device, tool 9, source 0x1002, taken for finger; pressure 1.000 (its device gives none); buttons 0x0; touch 0.0 px",
            pointerFacts("hovering", "no device", 9, InputDevice.SOURCE_TOUCHSCREEN, PointerKind.FINGER, 1f, null, 0, 0f),
        )
        val told = PointersTold()
        assertEquals(true, told.isNew(3, MotionEvent.TOOL_TYPE_STYLUS, pen))
        assertEquals(false, told.isNew(3, MotionEvent.TOOL_TYPE_STYLUS, pen))
        assertEquals(true, told.isNew(3, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN))
        assertEquals(true, told.isNew(4, MotionEvent.TOOL_TYPE_STYLUS, pen))
    }

    @Test
    fun each_device_s_first_pen_stroke_tells_the_pressures_it_gave() {
        val strokes = PenStrokesTold()
        // a pen that gives no pressure: its first stroke says so
        strokes.began(7)
        assertEquals(true, strokes.summing)
        for (p in listOf(0f, 0f, 0f)) strokes.sample(p)
        assertEquals("the first pen stroke from device 7: 3 samples, pressure 0.000 to 0.000", strokes.lifted())
        // its next strokes aren't summed up, nor told
        strokes.began(7)
        assertEquals(false, strokes.summing)
        strokes.sample(0.5f)
        assertNull(strokes.lifted())
        // another device's first is; one with no samples says so
        strokes.began(9)
        for (p in listOf(0.02f, 0.4f, 0.9f)) strokes.sample(p)
        assertEquals("the first pen stroke from device 9: 3 samples, pressure 0.020 to 0.900", strokes.lifted())
        assertNull(strokes.lifted())
        strokes.began(11)
        assertEquals("the first pen stroke from device 11: no samples", strokes.lifted())
    }

    @Test
    fun external_drawing_tablets_of_any_brand_are_detected_by_capabilities_vendor_id_or_name() {
        // Brand-agnostic hardware detection: stylus source or external pressure/tilt/distance axis
        assertEquals(true, isDrawingTabletDevice("Generic USB HID", InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS, hasPressureAxis = false, isExternal = true))
        assertEquals(true, isDrawingTabletDevice("Unknown Brand Pad", InputDevice.SOURCE_MOUSE, hasPressureAxis = true, isExternal = true))
        assertEquals(true, isDrawingTabletDevice("Unknown Tilt Digitizer", InputDevice.SOURCE_TOUCHSCREEN, hasPressureAxis = false, isExternal = true, hasTiltOrDistance = true))
        // USB Vendor ID detection for XP-Pen, Huion, UC-Logic, Wacom, Veikk, Waltop, Hanvon, Bosto, Xencelabs even with generic kernel names
        for (vid in listOf(0x28BD, 0x256C, 0x5543, 0x056A, 0x2FEB, 0x172F, 0x0B57, 0x2179, 0x34A0)) {
            assertEquals("VID 0x${vid.toString(16)}", true, isDrawingTabletDevice("HID ${vid.toString(16)}:0905", InputDevice.SOURCE_MOUSE, hasPressureAxis = false, isExternal = true, vendorId = vid))
        }
        // Name fallback for kernels that expose tablets only in hid-generic mouse mode
        for (name in listOf("XP-Pen Deco 01 V2", "UGTABLET 10 inch PenTablet", "HUION H640P", "Inspiroy H640P", "Wacom Intuos S", "GAOMON S620", "VEIKK A15", "Parblo A610", "Xencelabs Pen Tablet")) {
            assertEquals(name, true, isDrawingTabletDevice(name, InputDevice.SOURCE_MOUSE, hasPressureAxis = false, isExternal = true))
        }
        // Dedicated multi-touch overlay on a pen display or external trackpad is NOT misclassified as a pen
        assertEquals(false, isDrawingTabletDevice("Wacom Cintiq Touch", InputDevice.SOURCE_TOUCHSCREEN, hasPressureAxis = false, isExternal = true, vendorId = 0x056A))
        assertEquals(false, isDrawingTabletDevice("Apple Magic Trackpad", InputDevice.SOURCE_MOUSE, hasPressureAxis = true, isExternal = true))
        // Regular mouse without pressure axis or tablet name is not misclassified
        assertEquals(false, isDrawingTabletDevice("Logitech G305", InputDevice.SOURCE_MOUSE, hasPressureAxis = false, isExternal = true))
        assertEquals(false, isDrawingTabletDevice("Touchscreen", InputDevice.SOURCE_TOUCHSCREEN, hasPressureAxis = true, isExternal = false))
    }

    @Test
    fun pen_pressure_calibration_handles_ranges_sensitivity_floor_and_fallbacks() {
        // Default 1.0x sensitivity, 0.0 floor preserves 0..1 pressure cleanly
        assertEquals(0.4f, calibratePenPressure(0.4f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f), 1e-5f)
        // Normalizes genuinely unnormalized raw HID ranges (e.g. 8192-level XP-Pen / Huion raw values > 1.05)
        assertEquals(0.5f, calibratePenPressure(4095.5f, 0f, 8191f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f), 1e-4f)
        // CRITICAL: Already-normalized 0..1 pressure when kernel MotionRange.max reports 8191 must NOT be divided by 8191!
        assertEquals(0.5f, calibratePenPressure(0.5f, 0f, 8191f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, rawExceedsUnit = false), 1e-5f)
        // Once a device has emitted raw values > 1.05 (rawExceedsUnit = true), even tiny raw level 1.0 divides by 8191
        assertEquals(1f / 8191f, calibratePenPressure(1f, 0f, 8191f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, rawExceedsUnit = true), 1e-6f)
        // Applies sensitivity multiplier and minimum floor
        assertEquals(0.8f, calibratePenPressure(0.4f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 2f, minFloor = 0f), 1e-5f)
        assertEquals(0.28f, calibratePenPressure(0.1f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0.2f), 1e-5f)
        // Pressureless USB drawing tablet falls back to full pressure immediately on contact
        assertEquals(1f, calibratePenPressure(0f, 0f, 0f, hasPressureAxis = false, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, buttonPrimaryDown = true), 1e-5f)
        assertEquals(0f, calibratePenPressure(0f, 0f, 0f, hasPressureAxis = false, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, buttonPrimaryDown = false, isHovering = true), 1e-5f)
        // External tablet with AXIS_PRESSURE that always reports 0 (including TouchInputMapper buttonState == 0) falls back to 1.0 immediately
        assertEquals(1f, calibratePenPressure(0f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, buttonPrimaryDown = true, isHovering = false, fallbackFullWhenUnreported = true), 1e-5f)
        assertEquals(1f, calibratePenPressure(0f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, buttonPrimaryDown = false, isHovering = false, fallbackFullWhenUnreported = true, externalActiveContact = true), 1e-5f)
        // External tablet with known analog pressure whose ACTION_DOWN frame arrives at pressure == 0 before ramping up gets >= EXTERNAL_CONTACT_MIN_PRESSURE so stroke starts immediately
        assertEquals(EXTERNAL_CONTACT_MIN_PRESSURE, calibratePenPressure(0f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, buttonPrimaryDown = false, isHovering = false, fallbackFullWhenUnreported = false, externalActiveContact = true), 1e-5f)
        assertEquals(0.15f, calibratePenPressure(0f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0.15f, buttonPrimaryDown = false, isHovering = false, fallbackFullWhenUnreported = false, externalActiveContact = true), 1e-5f)
        // Built-in stylus touching at 0 pressure still returns 0 so hover-above-glass detection stays intact
        assertEquals(0f, calibratePenPressure(0f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, buttonPrimaryDown = false, isHovering = false, fallbackFullWhenUnreported = false, externalActiveContact = false), 1e-5f)
        // Pressure disabled gives uniform 1.0 while drawing and 0.0 while hovering
        assertEquals(1f, calibratePenPressure(0.2f, 0f, 1f, hasPressureAxis = true, pressureEnabled = false, sensitivity = 1f, minFloor = 0f), 1e-5f)
        assertEquals(0f, calibratePenPressure(0f, 0f, 1f, hasPressureAxis = true, pressureEnabled = false, sensitivity = 1f, minFloor = 0f, isHovering = true), 1e-5f)
    }

    @Test
    fun tablet_pressure_tracker_distinguishes_analog_pressure_from_hover_drift_and_mouse_clicks() {
        val tracker = TabletPressureTracker()
        // Hover drift (e.g. XP-Pen X3 low-IAF) does NOT poison analog detection or raw range
        tracker.recordHoverPressure(10, 0.02f)
        assertEquals(false, tracker.hasAnalogPressure(10))
        assertEquals(true, tracker.sawZeroHover(10))
        // Constant 1.0f mouse click pressure does NOT mark a pressureless mouse-mode tablet as analog
        tracker.recordContactPressure(11, 1.0f, hasPressureAxis = true)
        tracker.recordContactPressure(11, 1.0f, hasPressureAxis = true)
        assertEquals(false, tracker.hasAnalogPressure(11))
        assertEquals(false, tracker.usesRawRange(11))
        // Genuine fractional stylus pressure immediately enables analog mode without raw-range scaling
        tracker.recordContactPressure(12, 0.35f, hasPressureAxis = true)
        assertEquals(true, tracker.hasAnalogPressure(12))
        assertEquals(false, tracker.usesRawRange(12))
        // Unnormalized 8192-level HID raw value (> 1.05) activates raw range normalization and analog mode
        tracker.recordContactPressure(13, 2048f, hasPressureAxis = true)
        assertEquals(true, tracker.hasAnalogPressure(13))
        assertEquals(true, tracker.usesRawRange(13))
        // Constant 1.0 hover bug never sets sawZeroHover
        tracker.recordHoverPressure(14, 1.0f)
        assertEquals(false, tracker.sawZeroHover(14))
    }

    @Test
    fun calibrate_pen_pressure_rescales_with_learned_max_before_sensitivity_and_floor() {
        // Default learnedMax = 1f leaves existing behavior completely unchanged
        assertEquals(0.084f, calibratePenPressure(0.084f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f), 1e-5f)
        // XP-Pen 28bd:2904 observed peak 0.084 with headroom divisor 0.084 * 1.15 = 0.0966
        val learnedMax = 0.084f * PenPressureLearner.HEADROOM
        // Comfortable firm press (0.084) maps to ~0.87 (proportional, strong stroke without crushing nib)
        assertEquals(0.084f / learnedMax, calibratePenPressure(0.084f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, learnedMax = learnedMax), 1e-4f)
        // Light press (0.042) maps to ~0.435
        assertEquals(0.042f / learnedMax, calibratePenPressure(0.042f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, learnedMax = learnedMax), 1e-4f)
        // Harder press (0.12) clamps cleanly to 1.0 before sensitivity/floor
        assertEquals(1f, calibratePenPressure(0.12f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0.1f, learnedMax = learnedMax), 1e-5f)
        // Sensitivity and floor apply AFTER learnedMax rescaling
        val afterMax = (0.03f / learnedMax).coerceIn(0f, 1f)
        val expected = 0.2f + (afterMax * 1.5f).coerceIn(0f, 1f) * 0.8f
        assertEquals(expected, calibratePenPressure(0.03f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1.5f, minFloor = 0.2f, learnedMax = learnedMax), 1e-5f)
    }

    @Test
    fun pen_pressure_learner_auto_gains_under_reporting_tablets_and_leaves_normal_devices_untouched() {
        val persisted = LinkedHashMap<String, Float>()
        val learner = PenPressureLearner(
            loadAll = { persisted },
            saveRef = { k, v -> persisted[k] = v },
            clearStore = { persisted.clear() },
        )
        // Stable device key formatting with descriptor/name fallbacks
        val xpPen = devicePressureKey(0x28BD, 0x2904, "desc-1", "XP-Pen")!!
        assertEquals("28bd:2904", xpPen)
        assertEquals("desc:abc", devicePressureKey(0, 0, "abc", "Pen"))
        assertEquals("name:Built-in Pen", devicePressureKey(0, 0, null, "Built-in Pen"))
        assertNull(devicePressureKey(0, 0, "", " "))

        // Unseen device starts at 1.0 (untouched)
        assertEquals(1f, learner.effectiveMax(xpPen), 1e-5f)
        assertEquals(false, learner.isGainActive(xpPen))

        // First stroke on XP-Pen 28bd:2904 peaks at 0.084 -> ref = 0.084, effectiveMax = 0.084 * 1.15
        assertEquals(0.084f, learner.onStrokeEnd(xpPen, 0.084f)!!, 1e-5f)
        assertEquals(true, learner.isGainActive(xpPen))
        assertEquals(0.084f * PenPressureLearner.HEADROOM, learner.effectiveMax(xpPen), 1e-5f)
        assertEquals(0.084f, persisted[xpPen]!!, 1e-5f)

        // Higher peak raises ref immediately
        assertEquals(0.12f, learner.onStrokeEnd(xpPen, 0.12f)!!, 1e-5f)
        assertEquals(0.12f * PenPressureLearner.HEADROOM, learner.effectiveMax(xpPen), 1e-5f)

        // Lower peak decays smoothly via ref + (peak - ref) * 0.1
        val expectedDecayed = 0.12f + (0.06f - 0.12f) * 0.1f
        assertEquals(expectedDecayed, learner.onStrokeEnd(xpPen, 0.06f)!!, 1e-5f)

        // Tiny / zero noise strokes (< 0.005) are ignored
        assertEquals(expectedDecayed, learner.onStrokeEnd(xpPen, 0f)!!, 1e-5f)
        assertEquals(expectedDecayed, learner.onStrokeEnd(xpPen, 0.002f)!!, 1e-5f)

        // Clamping at lower bound (MIN_REF = 0.02f)
        val tinyKey = "1234:5678"
        assertEquals(PenPressureLearner.MIN_REF, learner.onStrokeEnd(tinyKey, 0.008f)!!, 1e-5f)

        // Normal stylus reaching >= 0.5 (e.g. Samsung S-Pen) MUST leave effectiveMax == 1.0f untouched!
        val sPen = "0000:0001"
        learner.onStrokeEnd(sPen, 0.85f)
        assertEquals(false, learner.isGainActive(sPen))
        assertEquals(1f, learner.effectiveMax(sPen), 1e-5f)
        // Even after many light shading strokes on S-Pen, ref never decays below 0.5 -> output stays untouched
        repeat(30) { learner.onStrokeEnd(sPen, 0.10f) }
        assertEquals(false, learner.isGainActive(sPen))
        assertEquals(1f, learner.effectiveMax(sPen), 1e-5f)
        assertEquals(0.4f, calibratePenPressure(0.4f, 0f, 1f, hasPressureAxis = true, pressureEnabled = true, sensitivity = 1f, minFloor = 0f, learnedMax = learner.effectiveMax(sPen)), 1e-5f)

        // Explicit Calibrate pressure overrides prior ref directly (even downward)
        assertEquals(0.09f, learner.setCalibratedPeak(xpPen, 0.09f)!!, 1e-5f)
        assertEquals(0.09f * PenPressureLearner.HEADROOM, learner.effectiveMax(xpPen), 1e-5f)

        // Persistence round-trip reloads stored refs into a fresh instance
        val reloaded = PenPressureLearner(loadAll = { persisted })
        assertEquals(0.09f, reloaded.refOf(xpPen)!!, 1e-5f)
        assertEquals(0.09f * PenPressureLearner.HEADROOM, reloaded.effectiveMax(xpPen), 1e-5f)

        // Reset clears both memory and persistence
        learner.clearAll()
        assertNull(learner.refOf(xpPen))
        assertEquals(1f, learner.effectiveMax(xpPen), 1e-5f)
        assertEquals(true, persisted.isEmpty())
    }

    @Test
    fun pointer_icon_always_shows_arrow_over_ui_and_only_hides_on_open_painting_canvas_with_visible_brush() {
        // Over bars, panels, sheets, menus, studios, or gallery: ALWAYS standard arrow for navigation!
        assertEquals(android.view.PointerIcon.TYPE_ARROW, resolveTabletPointerIconType(overOpenCanvas = false, hideTabletArrowCursor = true, brushCursorVisible = true))
        assertEquals(android.view.PointerIcon.TYPE_ARROW, resolveTabletPointerIconType(overOpenCanvas = false, hideTabletArrowCursor = true, brushCursorVisible = false))
        assertEquals(android.view.PointerIcon.TYPE_ARROW, resolveTabletPointerIconType(overOpenCanvas = false, hideTabletArrowCursor = false, brushCursorVisible = true))
        // Over open canvas with hideTabletArrowCursor OFF: standard arrow
        assertEquals(android.view.PointerIcon.TYPE_ARROW, resolveTabletPointerIconType(overOpenCanvas = true, hideTabletArrowCursor = false, brushCursorVisible = true))
        // Over open painting canvas with hideTabletArrowCursor ON and brush cursor visible: TYPE_NULL (unobstructed brush)
        assertEquals(android.view.PointerIcon.TYPE_NULL, resolveTabletPointerIconType(overOpenCanvas = true, hideTabletArrowCursor = true, brushCursorVisible = true))
        // Over open canvas with hideTabletArrowCursor ON but brush cursor NOT visible (tiny/off/selection/transform): crosshair, never invisible!
        assertEquals(android.view.PointerIcon.TYPE_CROSSHAIR, resolveTabletPointerIconType(overOpenCanvas = true, hideTabletArrowCursor = true, brushCursorVisible = false))
    }

    @Test
    fun brush_cursor_stays_visible_while_painting_on_external_drawing_tablets() {
        // Built-in stylus respects CursorShows.HOVERING (hides while painting)
        assertEquals(false, shouldShowBrushCursor(brushCursorOn = true, hasPosition = true, hovering = false, cursorShows = CanvasViewModel.CursorShows.HOVERING, externalTabletActive = false))
        assertEquals(true, shouldShowBrushCursor(brushCursorOn = true, hasPosition = true, hovering = true, cursorShows = CanvasViewModel.CursorShows.HOVERING, externalTabletActive = false))
        // External screenless drawing tablet keeps brush cursor visible while painting too
        assertEquals(true, shouldShowBrushCursor(brushCursorOn = true, hasPosition = true, hovering = false, cursorShows = CanvasViewModel.CursorShows.HOVERING, externalTabletActive = true))
        // Turning brush cursor off hides it everywhere
        assertEquals(false, shouldShowBrushCursor(brushCursorOn = false, hasPosition = true, hovering = true, cursorShows = CanvasViewModel.CursorShows.BOTH, externalTabletActive = true))
    }

    @Test
    fun drawing_tablet_mode_allows_external_pointer_to_paint_even_with_finger_painting_off() {
        val off = shipped(fingerPaint = false, oneFingerPan = true, modelFingerPaint = false)
        val tablet = tabletTouchSettings(off, pointerFollowsTouch = false)
        assertEquals(false, tablet.pointerFollowsTouch)
    }
}
