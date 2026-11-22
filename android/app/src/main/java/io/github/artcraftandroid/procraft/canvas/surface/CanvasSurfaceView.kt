package io.github.artcraftandroid.procraft.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset
import androidx.input.motionprediction.MotionEventPredictor
import io.github.artcraftandroid.procraft.AppLog
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.MobileException
import io.github.artcraftandroid.procraft.core.PenPreferred
import io.github.artcraftandroid.procraft.core.PenSample
import io.github.artcraftandroid.procraft.core.CompanionMode
import io.github.artcraftandroid.procraft.core.PointerKind
import io.github.artcraftandroid.procraft.core.TouchCommand
import io.github.artcraftandroid.procraft.core.TouchMove
import io.github.artcraftandroid.procraft.core.TransformHandle
import kotlin.math.atan2
import kotlin.math.pow
import io.github.artcraftandroid.procraft.core.Adjustment

/**
 * The canvas: a surface the Rust renderer draws into, and the touch input that drives it.
 *
 * One pen or finger paints. A second finger turns a finger stroke into a gesture (the stroke is
 * dropped): two fingers move, zoom and turn the canvas; a quick tap with two fingers undoes, three
 * redo, four hide or show the controls. Fingers are ignored while the pen draws.
 */
@SuppressLint("ViewConstructor")
class CanvasSurfaceView(context: Context, private val vm: CanvasViewModel) : SurfaceView(context), SurfaceHolder.Callback {
    private var session: CanvasSession? = null
    private var attached = false

    // the window as last given to the session, and the display rotation it was in
    private var windowWidth = 0
    private var windowHeight = 0
    private var windowRotation = Surface.ROTATION_0

    /** Predicts where the pen is going, so the stroke can be drawn ahead of the latest input. */
    private val predictor = MotionEventPredictor.newInstance(this)

    /** The pen is hovering over the canvas. */
    private var penHovering = false

    /** The pen's side buttons as its events last said. */
    private val penButtons = PenButtons()

    /** Where the pen last was over the canvas: a side button that comes as a key says nothing of it. */
    private var penAt: Offset? = null

    private var strokePointer = -1

    /** True while Android is delivering a normal touch sequence (ACTION_DOWN..ACTION_UP). */
    private var realTouchActive = false

    /**
     * External USB/Bluetooth drawing tablets (XP-Pen, Huion, Wacom, Gaomon, Veikk, etc.) on some
     * hover-only Android kernels send pen contact as `ACTION_BUTTON_PRESS(BUTTON_PRIMARY)` +
     * `ACTION_HOVER_MOVE` instead of `ACTION_DOWN`/`ACTION_MOVE`. This bridges those events into
     * a real canvas stroke without interfering with normal touch-delivering tablets or Compose UI.
     */
    private var tabletSyntheticStroke = false
    private var tabletSyntheticPointerId = -1
    private var pendingSyntheticPress = false

    /** Defers hiding the brush cursor briefly on hover-exit / lift for external tablets to eliminate flicker. */
    private val deferredCursorClear = Runnable {
        if (!realTouchActive && !tabletSyntheticStroke) {
            vm.showCursor(null)
            vm.showLiquifyRing(null)
        }
    }

    private val pickSide get() = (CanvasViewModel.PICK_WINDOW_DP * vm.density).toInt() or 1

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val s = vm.session ?: return
        val rotation = display?.rotation ?: Surface.ROTATION_0
        if (attached && session === s) {
            if (rotation != windowRotation) followTurn(s, windowRotation, rotation)
            s.resizeWindow(width.toUInt(), height.toUInt())
            vm.refreshView()
            remember(width, height, rotation)
            return
        }
        val id = NativeWindows.acquire(holder.surface)
        if (id == 0L) {
            AppLog.e(TAG, "no native window for the surface")
            return
        }
        try {
            s.attachWindow(id.toULong(), width.toUInt(), height.toUInt())
            s.setDisplayScale(resources.displayMetrics.density)
            // the display turned while there was no window (the app away, the upright lock screen):
            // the view follows now, as it would have then, so the canvas stays put on the glass
            if (session === s && windowWidth > 0 && rotation != windowRotation) followTurn(s, windowRotation, rotation)
            session = s
            attached = true
            vm.refreshView()
            remember(width, height, rotation)
        } catch (e: MobileException) {
            NativeWindows.release(id)
            AppLog.e(TAG, "attaching the window", e)
        }
    }

    private fun remember(width: Int, height: Int, rotation: Int) {
        vm.session?.let { placeGrid(it, width, height, rotation) }
        // frames no more often than the screen can show them: its fastest refresh at this size
        display?.let { d ->
            val size = d.mode
            val fastest = d.supportedModes.filter { it.physicalWidth == size.physicalWidth && it.physicalHeight == size.physicalHeight }.maxOfOrNull { it.refreshRate }
            vm.session?.setRefreshRate(fastest ?: d.refreshRate)
        }
        vm.screenCentre = Offset(width / 2f, height / 2f)
        windowWidth = width
        windowHeight = height
        vm.windowSize = Offset(width.toFloat(), height.toFloat())
        vm.screenDensity = resources.displayMetrics.density
        windowRotation = rotation
    }

    /**
     * The surround's grid is fixed to the glass as the canvas is: its 1 pt lines lie every 16 pt
     * from the panel's own top-left corner in its natural orientation, so in the window a line's
     * middle is half a point in from whichever corner that is. The canvas moved in from the window's
     * corner (the interface keeping clear of a camera) has it that much further up and left.
     */
    private fun placeGrid(s: CanvasSession, width: Int, height: Int, rotation: Int) {
        val half = 0.5f * resources.displayMetrics.density
        val at = IntArray(2).also(::getLocationInWindow)
        val moved = at[0] != 0 || at[1] != 0
        val (w, h) = if (moved && rootView.width > 0 && rootView.height > 0) rootView.width to rootView.height else width to height
        val (nw, nh) = if (rotation % 2 == 0) w.toFloat() to h.toFloat() else h.toFloat() to w.toFloat()
        val (wx, wy) = when (rotation) {
            Surface.ROTATION_90 -> half to nw - half
            Surface.ROTATION_180 -> nw - half to nh - half
            Surface.ROTATION_270 -> nh - half to half
            else -> half to half
        }
        val (x, y) = wx - at[0] to wy - at[1]
        s.setGridOrigin(x, y)
        vm.gridOrigin = Offset(x, y)
    }

    /**
     * The display turned from rotation [from] to [to]: the view turns the other way, so the canvas
     * stays where it was on the glass while the bars turn upright.
     */
    private fun followTurn(s: CanvasSession, from: Int, to: Int) {
        // the panel's own size, in its natural orientation
        val (nw, nh) = if (from % 2 == 0) windowWidth.toFloat() to windowHeight.toFloat() else windowHeight.toFloat() to windowWidth.toFloat()
        fun toPanel(r: Int, x: Float, y: Float) = when (r) {
            Surface.ROTATION_90 -> nw - y to x
            Surface.ROTATION_180 -> nw - x to nh - y
            Surface.ROTATION_270 -> y to nh - x
            else -> x to y
        }
        fun fromPanel(r: Int, x: Float, y: Float) = when (r) {
            Surface.ROTATION_90 -> y to nw - x
            Surface.ROTATION_180 -> nw - x to nh - y
            Surface.ROTATION_270 -> nh - y to x
            else -> x to y
        }
        fun map(x: Float, y: Float) = toPanel(from, x, y).let { (px, py) -> fromPanel(to, px, py) }
        val (ox, oy) = map(0f, 0f)
        val (ux, uy) = map(1f, 0f)
        // (told apart from a turn of the fingers: the artwork's gallery turn counts only those)
        s.followScreenTurn(ox, oy, atan2(uy - oy, ux - ox))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // returns once the render thread has stopped drawing into the surface
        session?.detachWindow()
        attached = false
    }

    // every touch is the shared touches' (crates/core touch.rs): painting, picking, the snapped shape,
    // the gestures and every tool's touch, the same in every app

    /** Contacts too big for a fingertip (a palm, on a screen that says how big a touch is): the shared touches never see them, and one that grows that big is taken back. */
    private val palms = mutableSetOf<Int>()

    /** When the last pointer landed on the canvas (event time, ms). */
    private var landedAt = 0L

    /** Whether pointer [i] of [e] is a finger's contact [PALM_MM] or more across. External devices are never a resting palm. */
    private fun palmSized(e: MotionEvent, i: Int): Boolean {
        if (isExternalDevice(e) || kindOf(e, i) != PointerKind.FINGER) return false
        val dpi = resources.displayMetrics.xdpi.takeIf { it > 0f } ?: return false
        return e.getTouchMajor(i) / dpi * 25.4f >= PALM_MM
    }

    /** Hands [e] to the shared touches and does what they ask. */
    private fun route(e: MotionEvent, s: CanvasSession) {
        val out = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    strokePointer = e.getPointerId(i)
                    palms.clear()
                }
                landedAt = e.eventTime
                tellPointer(e, i, "down")
                if (palmSized(e, i)) {
                    palms += e.getPointerId(i)
                    emptyList()
                } else {
                    val kind = kindOf(e, i)
                    // a pen's stroke goes by its lean only if its device says how it leans
                    if (kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER) {
                        s.setPenTiltKnown(tiltKnown(e))
                        penStrokes.began(e.deviceId)
                        summedPointer = e.getPointerId(i)
                        sumPressures(e, i)
                        beginStrokePeak(e, i)
                    }
                    s.touchDown(e.getPointerId(i).toULong(), kind, samples(e, i))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (penStrokes.summing) e.findPointerIndex(summedPointer).takeIf { it >= 0 }?.let { sumPressures(e, it) }
                if (peakPointerId >= 0) e.findPointerIndex(peakPointerId).takeIf { it >= 0 }?.let { sampleStrokePeak(e, it) }
                val grown =(0 until e.pointerCount).filter { i -> e.getPointerId(i) !in palms && palmSized(e, i) }
                val back = grown.flatMap { i -> palms += e.getPointerId(i); s.touchCancelOne(e.getPointerId(i).toULong()) }
                back + s.touchMoves((0 until e.pointerCount).filter { i -> e.getPointerId(i) !in palms }.map { i -> TouchMove(e.getPointerId(i).toULong(), samples(e, i)) })
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = e.actionIndex
                val pid = e.getPointerId(i)
                val cancelled = e.flags and MotionEvent.FLAG_CANCELED != 0
                if (penStrokes.summing && pid == summedPointer) {
                    sumPressures(e, i)
                    penStrokes.lifted()?.let { AppLog.i(TAG, it) }
                }
                if (pid == peakPointerId) {
                    if (cancelled) {
                        cancelStrokePeak()
                    } else {
                        sampleStrokePeak(e, i)
                        finishStrokePeak()
                    }
                }
                when {
                    palms.remove(pid) -> emptyList()
                    // the system took the pointer back (a palm): what it did is undone, not ended
                    cancelled -> s.touchCancelOne(pid.toULong())
                    else -> s.touchUp(pid.toULong(), samples(e, i))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // the tablet taking three fingers for its own gesture: said once, with what to do
                val fingers = (0 until e.pointerCount).count { kindOf(e, it) == PointerKind.FINGER }
                if (systemTookFingers(fingers, e.eventTime - landedAt, hasWindowFocus())) vm.systemTookFingers()
                penStrokes.lifted()?.let { AppLog.i(TAG, "$it (called off by the system)") }
                cancelStrokePeak()
                palms.clear()
                s.touchCancel()
            }
            else -> emptyList()
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE) predict(s)
        obey(out)
        scheduleTouchTick(s)
    }

    /** Does what the shared touches asked. */
    private fun obey(out: List<TouchCommand>) {
        for (c in out) when (c) {
            TouchCommand.Undo -> vm.undo()
            TouchCommand.Redo -> vm.redo()
            // a pen button set to a QuickMenu action in Pen buttons
            is TouchCommand.Run -> vm.runQuickAction(c.action, Offset(c.x, c.y))
            TouchCommand.ClearLayer -> vm.clearCurrentLayer()
            TouchCommand.ToggleBars -> vm.toggleBars()
            TouchCommand.Fit -> vm.settleToFit()
            TouchCommand.CopyPaste -> vm.copyPasteOpen = true
            is TouchCommand.QuickMenu -> vm.toggleQuickMenu(Offset(c.x, c.y))
            is TouchCommand.LayerSelect -> vm.layerSelectAt(Offset(c.x, c.y))
            TouchCommand.QuickShapeLast -> vm.quickShapeLast()
            is TouchCommand.Pick -> vm.pickAt(c.x, c.y, pickSide)
            TouchCommand.PickDone -> vm.finishPick()
            TouchCommand.PickCancel -> vm.cancelPick()
            TouchCommand.ShapeSnapped -> vm.quickShapeSnapped()
            TouchCommand.ShapeChanged -> vm.quickShapeChanged()
            TouchCommand.ShapeEnded -> vm.quickShapeEnded()
            TouchCommand.StrokeEnded -> vm.strokeEnded()
            TouchCommand.ViewMoved -> vm.refreshView()
            TouchCommand.ViewSettles -> vm.followView()
            is TouchCommand.Cursor -> {
                removeCallbacks(deferredCursorClear)
                if (vm.externalTabletActive && !vm.isUiCoveringCanvas() && !vm.overBars(Offset(c.x, c.y))) {
                    vm.markCanvasPointerHover(true)
                }
                vm.showCursor(Offset(c.x, c.y))
                // cloning, the source follows the brush
                if (vm.adjustment == Adjustment.CLONE) vm.followClonePuck()
            }
            TouchCommand.CursorGone -> {
                vm.transformHeld = null
                if (vm.externalTabletActive && vm.canvasPointerOnCanvas && !vm.isUiCoveringCanvas()) {
                    removeCallbacks(deferredCursorClear)
                    postDelayed(deferredCursorClear, CURSOR_HIDE_DELAY_MS)
                } else {
                    removeCallbacks(deferredCursorClear)
                    vm.showCursor(null)
                }
            }
            // a pen touching at no pressure before it presses (some begin their touches above the glass; one
            // not known to give a pressure waits a moment for one, then paints at full pressure): as it hovering there
            is TouchCommand.PenHovers -> hovered(Offset(c.x, c.y))
            TouchCommand.TextLayer -> vm.askRasterize = true
            TouchCommand.BrushChanged -> vm.brushTouched()
            // Recolor's reticle dragged or tapped elsewhere (its fills on the view model's fill thread)
            is TouchCommand.RecolorMoved -> vm.recolorMoveTo(Offset(c.x, c.y))
            is TouchCommand.RecolorTapped -> vm.recolorTap(Offset(c.x, c.y))
            // Continue filling's threshold slid (redone on the view model's fill thread), and the fill let go
            is TouchCommand.FillThreshold -> vm.pourThreshold(c.threshold)
            TouchCommand.Filled -> vm.filled()
            // a transform's touch: its popovers close; the box as it now is; the node held, and let go
            // (a rotate or box-turning node springs back); a node tapped types its numbers beside it
            TouchCommand.TransformTouched -> vm.transformPopup = TransformPopup.NONE
            is TouchCommand.TransformMoved -> vm.transformMoved(c.transform)
            is TouchCommand.TransformHeld -> vm.transformHeld = c.handle to Offset(c.x, c.y)
            is TouchCommand.TransformLetGo -> {
                vm.transformHeld = null
                if (c.handle == TransformHandle.Rotate || c.handle == TransformHandle.TurnBox) vm.transformLetGo = c.handle to Offset(c.x, c.y)
            }
            is TouchCommand.TransformNumeric -> vm.transformNumeric = CanvasViewModel.TransformNumeric(c.angle, Offset(c.x, c.y), c.right)
            // the selection tool's touch: a shape dragged out, a colour selection's threshold slid, the
            // freehand path drawn, a selection made
            is TouchCommand.SelectionDrag -> vm.shapeDrag = Offset(c.x0, c.y0) to Offset(c.x1, c.y1)
            is TouchCommand.SelectionThreshold -> vm.colourSlid(Offset(c.x, c.y), c.threshold, c.done)
            TouchCommand.FreehandChanged -> vm.freehandChanged()
            TouchCommand.SelectionChanged -> vm.selectionTouched()
            // an adjustment's amount, liquify, the clone source and the like changed; a tap opens its menu
            TouchCommand.AdjustChanged -> vm.adjustTouched()
            is TouchCommand.AdjustMenu -> if (!closedAdjustMenu) vm.adjustMenuAt = Offset(c.x, c.y)
            // what a pen's own settings ask its double-tap or squeeze to show
            TouchCommand.ShowColours -> vm.toggle(Panel.COLOR)
            TouchCommand.ShowBrushes -> vm.toggle(Panel.BRUSHES)
            // a touch on the canvas with a panel open: dragged, it fades the panel; tapped, it only closes it
            TouchCommand.PanelFades -> {
                vm.swipedBrush = null
                vm.fadePanels()
            }
            TouchCommand.PanelTapped -> vm.tapOutsidePanel()
            // the guide editor: a touch came down (symmetry's options close), dragged a handle or
            // added a point; a handle tapped shows its menu
            TouchCommand.GuideTouched -> vm.guideTouched(touched = true)
            TouchCommand.GuideChanged -> vm.guideTouched(touched = false)
            is TouchCommand.GuideNodeTapped -> vm.guideNodeMenu = CanvasViewModel.GuideNodeMenu(c.point?.toInt(), Offset(c.x, c.y))
        }
    }

    // the shared touches' waits (a hold, a repeat, an airbrush held still), run when they run out;
    // a fault in the core then costs that touch, not the app, as one while it takes a touch does
    private val touchTick = Runnable {
        val s = session ?: return@Runnable
        try {
            obey(s.touchTick(android.os.SystemClock.uptimeMillis().toDouble()))
            scheduleTouchTick(s)
        } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
            AppLog.e("touch", "a touch's wait failed in the core; its touches let go", fault)
            runCatching { s.touchCancel() }
        }
    }

    private fun scheduleTouchTick(s: CanvasSession) {
        removeCallbacks(touchTick)
        val due = s.touchDeadline() ?: return
        postDelayed(touchTick, (due - android.os.SystemClock.uptimeMillis()).toLong().coerceAtLeast(0L))
    }

    /** Prepares session touch/tool state for the start of a new stroke (real touch or synthetic external tablet stroke). */
    private fun prepareStrokeSession(e: MotionEvent, s: CanvasSession): Boolean {
        // the strokes' sound, resting while none was drawn, wakes for this one
        vm.canvasTouched()
        // Continue filling on a layer that takes no fill: the dialog says why, and the touch does nothing
        if (vm.dropBand == CanvasViewModel.DropBand.FILLING && !vm.currentTakesChanges(paint = true)) {
            return false
        }
        // a touch on the canvas closes an adjustment's menu, its Layer / Pencil choice and liquify's popups
        closedAdjustMenu = vm.adjustMenuAt != null
        if (vm.adjustment != null) {
            vm.adjustMenuAt = null
            vm.adjustModesOpen = false
            vm.liquifyPopup = 0
        }
        // every sample as it arrives, not batched to the frame
        requestUnbufferedDispatch(e)
        s.setTouchSettings(vm.touchSettings())
        // with a panel open, the touch waits to be a tap or a drag
        s.setTouchPanelOpen(vm.panel != Panel.NONE)
        // Continue filling: each touch fills where it lands; slid, it changes the threshold
        s.setTouchFilling(vm.touchFill())
        // the selection tool's touch selects as the tool is set
        s.setTouchSelecting(vm.selectTouch())
        if (vm.selecting) vm.selectionPopup = SelectionPopup.NONE
        // a transform's touch takes the node it lands on (the warp's inner points with the advanced mesh)
        s.setTouchTransforming(if (vm.transforming) io.github.artcraftandroid.procraft.core.TransformTouch(vm.advancedMesh) else null)
        // the guide editor's touch drags a handle; fingers only move the view
        s.setTouchGuideEditing(vm.guideEditing)
        // the companion's Zoom or Move: a lone press zooms or moves the canvas
        s.setTouchCompanion(
            when (vm.companionMode) {
                CanvasViewModel.CompanionMode.ZOOM -> CompanionMode.ZOOM
                CanvasViewModel.CompanionMode.MOVE -> CompanionMode.MOVE
                CanvasViewModel.CompanionMode.NONE -> null
            },
        )
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = session ?: return true
        if (penButton(e)) return true
        predictor.record(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            removeCallbacks(deferredCursorClear)
            vm.devicesWithRealTouch += e.deviceId
            pendingSyntheticPress = false
            // Hand off cleanly if an external tablet synthetic stroke was open
            if (tabletSyntheticStroke) endSyntheticTabletStroke(s, e, cancel = true)
            realTouchActive = true
            if (isExternalDrawingTabletEvent(e, vm.drawingTabletMode)) {
                vm.markCanvasPointerHover(!vm.isUiCoveringCanvas() && !vm.overBars(Offset(e.x, e.y)))
            }
            // a mouse's other buttons do nothing on the canvas: only its primary button paints
            skipping = kindOf(e, 0) == PointerKind.MOUSE && !mousePressPaints(e.buttonState)
            if (!skipping) skipping = !prepareStrokeSession(e, s)
            if (skipping) return true
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            realTouchActive = false
        }
        if (skipping) return true
        // Liquify's brush shows as a ring of its reach where the touch presses, until it lifts
        if (vm.adjustment == Adjustment.LIQUIFY) {
            val lifted = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
            vm.showLiquifyRing(if (lifted || e.pointerCount > 1) null else Offset(e.x, e.y))
        }
        // a fault in the core as it takes a touch costs that touch, not the app: it goes in the log
        // and the touches start afresh
        try {
            route(e, s)
        } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
            io.github.artcraftandroid.procraft.AppLog.e("touch", "a touch failed in the core; its touches let go", fault)
            runCatching { s.touchCancel() }
        }
        // Layer opacity: the shared touches drag it; the band shows it as they go
        if (vm.opacityLayer != null) vm.layerOpacityTouched(e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
        return true
    }

    /** The touch went down where Continue filling takes no fill: the rest of it does nothing. */
    private var skipping = false

    /** The touch went down with an adjustment's menu open: a tap only closes it. */
    private var closedAdjustMenu = false

    /**
     * The pen's side buttons, hovering or touching: what the shared touches say Gesture controls set
     * them to do (the first, set to nothing, switches to the eraser and back; set to erase, it
     * erases while held).
     */
    private fun penButton(e: MotionEvent): Boolean {
        val isStylus0 = isPenKind(kindOf(e, 0))
        if (isStylus0) penAt = Offset(e.x, e.y)
        // by what the event says is held: some pens' drivers send no press of their own, only their
        // hover and touch events with the button in them
        val penIn = (0 until e.pointerCount).any { isPenKind(kindOf(e, it)) }
        val a = e.actionMasked
        val (pressed, letGo) = penButtons.next(e.buttonState, penIn, penTouching(a), penLifting(a), penLeaving(a))
        val own = e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || e.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        // let go, whatever is open: one held to erase gives the eraser back (before this event's
        // touch, so a stroke that begins as it is let go paints)
        session?.let { s ->
            if (letGo and FIRST_BUTTON != 0) penLetGo(s, second = false)
            if (letGo and SECOND_BUTTON != 0) penLetGo(s, second = true)
        }
        if (pressed == 0) return own && penIn && (e.actionButton and PEN_BUTTONS) != 0
        // under the brush studio the tool would change unseen
        if (vm.studioOpen) return own
        val s = session ?: return own
        // both at once (one event for the two): each does what it is set to
        if (pressed and FIRST_BUTTON != 0) penPressed(s, second = false, e.x, e.y)
        if (pressed and SECOND_BUTTON != 0) penPressed(s, second = true, e.x, e.y)
        return own
    }

    /** A side button went down with the pen at ([x], [y]); a fault in the core costs the press, not the app. */
    private fun penPressed(s: CanvasSession, second: Boolean, x: Float, y: Float) = penFault {
        // as they are now: nothing has told the session if no touch has come since they were set
        s.setTouchSettings(vm.touchSettings())
        obey(s.touchPenButton(second, x, y))
        vm.penButtonDone()
    }

    /** A side button let go: one held to erase gives the eraser back. */
    private fun penLetGo(s: CanvasSession, second: Boolean) = penFault {
        if (s.touchPenButtonUp(second)) vm.penButtonDone()
    }

    /** Runs [act], a pen button's: a fault in the core goes in the log, as one in a touch does. */
    private inline fun penFault(act: () -> Unit) {
        try {
            act()
        } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
            AppLog.e("touch", "a pen button failed in the core", fault)
        }
    }

    /**
     * A side button that comes as a key, pressed or let go, or a double-tap that does: at the pen, or
     * the middle of the canvas before it has shown. A double-tap is never held, so set to erase
     * while held it switches to the eraser and back, as when left alone.
     */
    private val penKeyHeard: (Boolean, PenKeyAction) -> Unit = { second, action ->
        val at = penAt ?: Offset(width / 2f, height / 2f)
        session?.let { s ->
            when (action) {
                PenKeyAction.PRESSED -> penPressed(s, second, at.x, at.y)
                PenKeyAction.LET_GO -> penLetGo(s, second)
                PenKeyAction.DOUBLE_TAPPED -> penFault {
                    s.setTouchSettings(vm.touchSettings())
                    obey(s.touchPenDoubleTap(PenPreferred.SWITCH_ERASER, at.x, at.y))
                    vm.penButtonDone()
                }
            }
        }
    }

    /** Touches passed on by what covers the canvas (the guide editor), in the canvas's own place. */
    private val passedTouch: (MotionEvent) -> Boolean = { e -> onTouchEvent(e) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        vm.penKeyHeard = penKeyHeard
        vm.canvasTouch = passedTouch
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(deferredCursorClear)
        removeCallbacks(touchTick)
        removeCallbacks(predictionOver)
        if (vm.penKeyHeard === penKeyHeard) vm.penKeyHeard = null
        if (vm.canvasTouch === passedTouch) vm.canvasTouch = null
        super.onDetachedFromWindow()
    }

    private fun isPenKind(kind: PointerKind) = kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER

    /** Begins a synthetic stroke when an external drawing tablet presses via generic motion or hover-with-pressure. */
    private fun beginSyntheticTabletStroke(s: CanvasSession, e: MotionEvent, kind: PointerKind) {
        if (!prepareStrokeSession(e, s)) return
        val pointerId = e.getPointerId(0)
        tabletSyntheticStroke = true
        tabletSyntheticPointerId = pointerId
        strokePointer = pointerId
        landedAt = e.eventTime
        tellPointer(e, 0, "tablet-down")
        if (isPenKind(kind)) {
            s.setPenTiltKnown(tiltKnown(e))
            penStrokes.began(e.deviceId)
            summedPointer = pointerId
            sumPressures(e, 0)
            beginStrokePeak(e, 0)
        }
        try {
            obey(s.touchDown(pointerId.toULong(), kind, samples(e, 0, isHovering = false)))
            scheduleTouchTick(s)
        } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
            AppLog.e("touch", "a tablet stroke start failed in the core", fault)
            tabletSyntheticStroke = false
            cancelStrokePeak()
            runCatching { s.touchCancel() }
        }
    }

    private fun moveSyntheticTabletStroke(s: CanvasSession, e: MotionEvent) {
        val i = e.findPointerIndex(tabletSyntheticPointerId).takeIf { it >= 0 } ?: 0
        if (penStrokes.summing) sumPressures(e, i)
        if (peakPointerId == tabletSyntheticPointerId) sampleStrokePeak(e, i)
        try {
            val out = s.touchMoves(listOf(TouchMove(tabletSyntheticPointerId.toULong(), samples(e, i, isHovering = false))))
            obey(out)
            scheduleTouchTick(s)
        } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
            AppLog.e("touch", "a tablet stroke move failed in the core", fault)
            tabletSyntheticStroke = false
            cancelStrokePeak()
            runCatching { s.touchCancel() }
        }
    }

    private fun endSyntheticTabletStroke(s: CanvasSession, e: MotionEvent, cancel: Boolean = false) {
        if (!tabletSyntheticStroke) return
        val pointerId = tabletSyntheticPointerId
        tabletSyntheticStroke = false
        tabletSyntheticPointerId = -1
        val i = e.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
        if (penStrokes.summing && pointerId == summedPointer) {
            sumPressures(e, i)
            penStrokes.lifted()?.let { AppLog.i(TAG, it) }
        }
        if (pointerId == peakPointerId) {
            if (cancel) {
                cancelStrokePeak()
            } else {
                sampleStrokePeak(e, i)
                finishStrokePeak()
            }
        }
        try {
            val out = if (cancel) s.touchCancelOne(pointerId.toULong()) else s.touchUp(pointerId.toULong(), samples(e, i, isHovering = false))
            obey(out)
            scheduleTouchTick(s)
        } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
            AppLog.e("touch", "a tablet stroke end failed in the core", fault)
            runCatching { s.touchCancel() }
        }
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (penButton(e)) return true
        val s = session
        if (s != null) {
            val at = Offset(e.x, e.y)
            val overUi = vm.isUiCoveringCanvas() || vm.overBars(at)
            when (e.actionMasked) {
                // External drawing tablet scroll wheel / dial zooms smoothly around the cursor only over open canvas
                MotionEvent.ACTION_SCROLL -> {
                    if (!overUi) {
                        val vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                        if (vScroll != 0f) {
                            val factor = 1.12f.pow(vScroll).coerceIn(0.5f, 2f)
                            s.viewGesture(e.x, e.y, 0f, 0f, factor, 0f)
                            vm.refreshView()
                            return true
                        }
                    }
                }
                // Hover-only kernels that never emit ACTION_DOWN: defer starting until hover-move or release
                // so normal ACTION_DOWN tablets and Compose UI button clicks are never disrupted.
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    val kind = kindOf(e, 0)
                    if (!overUi && !realTouchActive && !vm.activityTouchActive && !tabletSyntheticStroke &&
                        e.deviceId !in vm.devicesWithRealTouch && isPenKind(kind) &&
                        (e.actionButton == MotionEvent.BUTTON_PRIMARY || e.buttonState and MotionEvent.BUTTON_PRIMARY != 0)
                    ) {
                        pendingSyntheticPress = true
                    }
                }
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (tabletSyntheticStroke &&
                        (e.actionButton == MotionEvent.BUTTON_PRIMARY || e.buttonState and MotionEvent.BUTTON_PRIMARY == 0)
                    ) {
                        pendingSyntheticPress = false
                        endSyntheticTabletStroke(s, e, cancel = false)
                        return true
                    }
                    if (pendingSyntheticPress && !overUi && !realTouchActive && !vm.activityTouchActive &&
                        e.deviceId !in vm.devicesWithRealTouch
                    ) {
                        pendingSyntheticPress = false
                        val kind = kindOf(e, 0)
                        if (isPenKind(kind)) {
                            beginSyntheticTabletStroke(s, e, kind)
                            endSyntheticTabletStroke(s, e, cancel = false)
                            return true
                        }
                    }
                    pendingSyntheticPress = false
                }
            }
        }
        return super.onGenericMotionEvent(e)
    }

    /**
     * Resolves the Android pointer icon cleanly by context:
     * - Over bars, panels, sheets, menus, studios, floating windows, or dialogs: ALWAYS standard arrow (`TYPE_ARROW`)
     *   so UI navigation is never blind.
     * - Over the open painting canvas with `hideTabletArrowCursor` on and the brush cursor visibly shown: `TYPE_NULL`
     *   so the brush outline + pinpoint dot is unobstructed.
     * - Over the open canvas in non-painting tools (or when brush cursor is off): `TYPE_CROSSHAIR` or `TYPE_ARROW`.
     */
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val kind = kindOf(event, pointerIndex)
        if (!isPenKind(kind) && kind != PointerKind.MOUSE) {
            return super.onResolvePointerIcon(event, pointerIndex)
        }
        val at = Offset(event.getX(pointerIndex), event.getY(pointerIndex))
        val overOpenCanvas = vm.screen == Screen.CANVAS &&
            !vm.isUiCoveringCanvas() &&
            !vm.overBars(at) &&
            vm.canvasPointerOnCanvas
        val brushCursorVisible = painting() && vm.brushCursorOn && vm.cursorAt != null
        val iconType = resolveTabletPointerIconType(
            overOpenCanvas = overOpenCanvas,
            hideTabletArrowCursor = vm.hideTabletArrowCursor,
            brushCursorVisible = brushCursorVisible,
        )
        return PointerIcon.getSystemIcon(context, iconType)
    }

    /** The pen or the mouse hovering over the canvas shows the brush cursor where it would paint. */
    override fun onHoverEvent(e: MotionEvent): Boolean = try {
        hoverEvent(e)
    } catch (fault: io.github.artcraftandroid.procraft.core.InternalException) {
        // as for a touch: the hover is let go, the app keeps going, the log says why
        io.github.artcraftandroid.procraft.AppLog.e("touch", "a pen's hover failed in the core", fault)
        true
    }

    private fun hoverEvent(e: MotionEvent): Boolean {
        tellPointer(e, 0, "hovering")
        if (penButton(e)) return true
        val kind = kindOf(e, 0)
        val at = Offset(e.x, e.y)
        val rawP = rawPressureOf(e, 0)
        vm.tabletPressureTracker.recordHoverPressure(e.deviceId, rawP)
        reportTelemetry(e, 0, kind, isHovering = true)

        val overUi = vm.isUiCoveringCanvas() || vm.overBars(at)
        if (overUi) {
            vm.markCanvasPointerHover(false)
            pendingSyntheticPress = false
            val s = session
            if (tabletSyntheticStroke && s != null) endSyntheticTabletStroke(s, e, cancel = false)
            removeCallbacks(deferredCursorClear)
            vm.showCursor(null)
            vm.showLiquifyRing(null)
            return false
        }

        if (e.actionMasked != MotionEvent.ACTION_HOVER_EXIT) {
            vm.markCanvasPointerHover(true)
            removeCallbacks(deferredCursorClear)
        }

        // Bridge hover-only Android kernels (that never deliver ACTION_DOWN) into a real canvas stroke
        val s = session
        if (s != null && !realTouchActive && !vm.activityTouchActive &&
            e.deviceId !in vm.devicesWithRealTouch && isPenKind(kind)
        ) {
            val nibPressed = (e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) ||
                (isExternalDevice(e) && pressureRangeOf(e) != null && rawP > HOVER_PRESSURE_STROKE_THRESHOLD && vm.tabletPressureTracker.sawZeroHover(e.deviceId))
            if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE && nibPressed) {
                pendingSyntheticPress = false
                if (!tabletSyntheticStroke) beginSyntheticTabletStroke(s, e, kind) else moveSyntheticTabletStroke(s, e)
                return true
            }
            if (tabletSyntheticStroke && (!nibPressed || e.actionMasked == MotionEvent.ACTION_HOVER_EXIT)) {
                endSyntheticTabletStroke(s, e, cancel = false)
            }
        }

        if (kind == PointerKind.MOUSE) return mouseHover(e)
        if (!isPenKind(kind)) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                // (told each time: a session opened meanwhile knows it too); a hand already down is set aside
                session?.setTouchPenHovering(true)?.let(::obey)
                hovered(at)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                session?.setTouchPenHovering(false)?.let(::obey)
                penHovering = false
                vm.markCanvasPointerHover(false)
                if (vm.externalTabletActive) {
                    if (!realTouchActive && !tabletSyntheticStroke) {
                        removeCallbacks(deferredCursorClear)
                        postDelayed(deferredCursorClear, CURSOR_HIDE_DELAY_MS)
                    }
                } else {
                    removeCallbacks(deferredCursorClear)
                    vm.showCursor(null)
                    vm.showLiquifyRing(null)
                }
            }
        }
        return true
    }

    /**
     * A mouse (or a touchpad's pointer) moving over the canvas with no button held: the brush
     * cursor follows it, as it does the pen held over the glass, but it is no pen near (a hand
     * landing then still paints, and Hover's touch stays the pen's).
     */
    private fun mouseHover(e: MotionEvent): Boolean {
        val at = Offset(e.x, e.y)
        val gone = e.actionMasked == MotionEvent.ACTION_HOVER_EXIT || vm.overBars(at) || vm.isUiCoveringCanvas()
        if (gone) {
            vm.markCanvasPointerHover(false)
            if (vm.externalTabletActive && e.actionMasked == MotionEvent.ACTION_HOVER_EXIT && !realTouchActive) {
                removeCallbacks(deferredCursorClear)
                postDelayed(deferredCursorClear, CURSOR_HIDE_DELAY_MS)
            } else {
                removeCallbacks(deferredCursorClear)
                vm.showCursor(null)
                vm.showLiquifyRing(null)
            }
        } else {
            removeCallbacks(deferredCursorClear)
            if (painting()) vm.showCursor(at, hovering = true) else vm.showCursor(null)
            if (vm.adjustment == Adjustment.LIQUIFY) vm.showLiquifyRing(at)
        }
        return true
    }

    /** The pen is held over the canvas at [at]: hovering, or touching at no pressure before it presses. */
    private fun hovered(at: Offset) {
        removeCallbacks(deferredCursorClear)
        // a spray can's sound (a plugin's) rattles shaken above the canvas
        if (vm.strokeSoundOn) session?.soundHover(at.x, at.y)
        penHovering = true
        // not over the bars, where the gaps between their buttons come here and the
        // buttons don't: it came and went as the pen crossed them
        val overUi = vm.overBars(at) || vm.isUiCoveringCanvas()
        if (overUi) {
            vm.markCanvasPointerHover(false)
            vm.showCursor(null)
        } else if (painting()) {
            vm.showCursor(at, hovering = true)
        }
        // and Liquify's ring where the pen hovers
        if (vm.adjustment == Adjustment.LIQUIFY) vm.showLiquifyRing(at.takeUnless { overUi })
    }

    /** Whether a pen on the canvas now would paint (no tool of another kind open). */
    private fun painting() = vm.adjustment == null && !vm.selecting && !vm.transforming

    /** Input stopped coming, so the finger or pen has stopped: nothing runs ahead of it any more. */
    private val predictionOver = Runnable { session?.strokePredict(emptyList()) }

    /** Draws the predicted next samples of the stroke ahead of it (replaced at the next input). */
    private fun predict(s: CanvasSession) {
        removeCallbacks(predictionOver)
        val p = predictor.predict() ?: return
        try {
            val i = p.findPointerIndex(strokePointer)
            if (i >= 0) s.strokePredict(samples(p, i, predicted = true))
        } finally {
            p.recycle()
        }
        postDelayed(predictionOver, PREDICTION_LASTS_MS)
    }

    private fun kindOf(e: MotionEvent, index: Int): PointerKind = pointerKindOf(e, index, vm.drawingTabletMode)

    private fun calibratedPressure(
        e: MotionEvent,
        i: Int,
        historyIdx: Int = -1,
        isHovering: Boolean = false,
        predicted: Boolean = false,
    ): Float {
        val raw = rawPressureOf(e, i, historyIdx)
        val range = pressureRangeOf(e)
        if (!isHovering && !predicted) {
            vm.tabletPressureTracker.recordContactPressure(e.deviceId, raw, range != null)
        }
        val kind = kindOf(e, i)
        if (!isPenKind(kind)) return raw
        val ext = isExternalDevice(e)
        val primaryDown = e.buttonState and MotionEvent.BUTTON_PRIMARY != 0
        val extFallback = ext && !vm.tabletPressureTracker.hasAnalogPressure(e.deviceId)
        val learnedMax = vm.penPressureLearner.effectiveMax(devicePressureKeyOf(e.device))
        return calibratePenPressure(
            rawPressure = raw,
            axisMin = range?.start ?: 0f,
            axisMax = range?.endInclusive ?: 1f,
            hasPressureAxis = range != null,
            pressureEnabled = vm.penPressureEnabled,
            sensitivity = vm.penPressureSensitivity,
            minFloor = vm.penPressureMin,
            buttonPrimaryDown = primaryDown,
            isHovering = isHovering,
            fallbackFullWhenUnreported = extFallback,
            rawExceedsUnit = vm.tabletPressureTracker.usesRawRange(e.deviceId),
            externalActiveContact = ext && !isHovering,
            learnedMax = learnedMax,
        )
    }

    private fun reportTelemetry(e: MotionEvent, i: Int, kind: PointerKind, isHovering: Boolean = false) {
        if (e.device == null || (!vm.tabletMonitorActive && !isExternalDevice(e))) return
        val name = e.device?.name.orEmpty().ifBlank { if (isPenKind(kind)) "Stylus" else "Touchscreen" }
        val kindLabel = when (kind) {
            PointerKind.STYLUS -> if (isExternalDevice(e)) "Drawing Tablet (Stylus)" else "Stylus"
            PointerKind.STYLUS_ERASER -> "Stylus Eraser"
            PointerKind.MOUSE -> "External Pointer"
            PointerKind.FINGER -> "Finger"
        }
        val p = calibratedPressure(e, i, isHovering = isHovering, predicted = true)
        vm.recordTabletInput(name, kindLabel, p, pressureRangeOf(e) != null, tiltKnown(e), deviceKey = devicePressureKeyOf(e.device))
    }

    /** The kinds of pointer told in the log so far. */
    private val pointersTold = PointersTold()

    /** Each device's first pen stroke, summed up in the log as it lifts, and its pointer. */
    private val penStrokes = PenStrokesTold()
    private var summedPointer = -1

    /** Active pen stroke raw normalized pressure peak, fed into [CanvasViewModel.penPressureLearner] on stroke end. */
    private var peakPointerId = -1
    private var peakDeviceKey: String? = null
    private var peakAxisRange: ClosedFloatingPointRange<Float>? = null
    private var peakRawNorm = 0f

    private fun beginStrokePeak(e: MotionEvent, i: Int) {
        peakPointerId = e.getPointerId(i)
        peakDeviceKey = devicePressureKeyOf(e.device)
        peakAxisRange = pressureRangeOf(e)
        peakRawNorm = 0f
        sampleStrokePeak(e, i)
    }

    private fun sampleStrokePeak(e: MotionEvent, i: Int) {
        val range = peakAxisRange ?: pressureRangeOf(e)
        val hasAxis = range != null
        val min = range?.start ?: 0f
        val max = range?.endInclusive ?: 1f
        val usesRaw = vm.tabletPressureTracker.usesRawRange(e.deviceId)
        for (h in 0 until e.historySize) {
            val raw = rawPressureOf(e, i, historyIdx = h)
            val norm = normalizeRawPressure(raw, min, max, hasAxis, rawExceedsUnit = usesRaw)
            if (norm > peakRawNorm) peakRawNorm = norm
        }
        val raw = rawPressureOf(e, i)
        val norm = normalizeRawPressure(raw, min, max, hasAxis, rawExceedsUnit = usesRaw)
        if (norm > peakRawNorm) peakRawNorm = norm
    }

    private fun finishStrokePeak() {
        val key = peakDeviceKey
        val axis = peakAxisRange
        val peak = peakRawNorm
        peakPointerId = -1
        peakDeviceKey = null
        peakAxisRange = null
        peakRawNorm = 0f
        val learnedRef = key?.let { vm.penPressureLearner.onStrokeEnd(it, peak) }
        val effMax = vm.penPressureLearner.effectiveMax(key)
        if (key != null && key == vm.lastTabletDeviceKey) {
            vm.lastTabletLearnedRef = learnedRef
        }
        val axisLabel = axis?.let { "%.2f..%.2f".format(java.util.Locale.ROOT, it.start, it.endInclusive) } ?: "none"
        val refLabel = learnedRef?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "none"
        AppLog.i(
            TAG,
            "pen stroke: device %s, axis %s, raw peak %.3f, learned ref %s (effective max %.3f)".format(
                java.util.Locale.ROOT,
                key ?: "unknown",
                axisLabel,
                peak,
                refLabel,
                effMax,
            ),
        )
    }

    private fun cancelStrokePeak() {
        peakPointerId = -1
        peakDeviceKey = null
        peakAxisRange = null
        peakRawNorm = 0f
    }

    /** The pressures of pointer [i]'s samples in [e], into the first pen stroke's sum. */
    private fun sumPressures(e: MotionEvent, i: Int) {
        for (h in 0 until e.historySize) penStrokes.sample(calibratedPressure(e, i, historyIdx = h, predicted = true))
        penStrokes.sample(calibratedPressure(e, i, predicted = true))
    }

    /**
     * Pointer [i] of [e], [what] it does (down, or hovering): the first of its kind from its device
     * is told in the log, with what a report of a pen that doesn't paint needs.
     */
    private fun tellPointer(e: MotionEvent, i: Int, what: String) {
        val toolType = e.getToolType(i)
        if (!pointersTold.isNew(e.deviceId, toolType, e.source)) return
        val device = e.device
        // (a pen paired over Bluetooth may carry a name its owner gave it: only a built-in one's)
        val builtIn = device != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && !device.isExternal
        val named = if (builtIn) " \"${device?.name}\"" else ""
        val about = device?.let { "device %04x:%04x%s".format(java.util.Locale.ROOT, it.vendorId, it.productId, named) } ?: "no device"
        val axis = pressureRangeOf(e)
        val kind = kindOf(e, i)
        AppLog.i(TAG, pointerFacts(what, about, toolType, e.source, kind, rawPressureOf(e, i), axis, e.buttonState, e.getTouchMajor(i)))
        // every axis a pen's device says it gives (a barrel's turn has no axis of Android's own)
        if (isPenKind(kind)) {
            val axes = device?.motionRanges.orEmpty().filter { it.source and e.source != 0 }.map { MotionEvent.axisToString(it.axis) }.distinct()
            AppLog.i(TAG, "its axes: ${axes.joinToString().ifEmpty { "none said" }}")
        }
    }

    /** The pointer's samples in this event: the batched ones since the last event, then the latest. */
    private fun samples(e: MotionEvent, i: Int, isHovering: Boolean = false, predicted: Boolean = false): List<PenSample> {
        val kind = kindOf(e, i)
        if (!predicted) reportTelemetry(e, i, kind, isHovering = isHovering)
        val out = ArrayList<PenSample>(e.historySize + 1)
        for (h in 0 until e.historySize) {
            out += PenSample(
                e.getHistoricalX(i, h),
                e.getHistoricalY(i, h),
                calibratedPressure(e, i, historyIdx = h, isHovering = isHovering, predicted = predicted),
                penLean(e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h), e.getHistoricalOrientation(i, h)),
                e.getHistoricalOrientation(i, h),
                e.getHistoricalEventTime(h).toDouble(),
            )
        }
        out += PenSample(
            e.getX(i),
            e.getY(i),
            calibratedPressure(e, i, isHovering = isHovering, predicted = predicted),
            penLean(e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getOrientation(i)),
            e.getOrientation(i),
            e.eventTime.toDouble(),
        )
        return out
    }

    private companion object {
        const val TAG = "ProCraft"

        /** How long a prediction stands with no input after it, milliseconds: a few input intervals. */
        const val PREDICTION_LASTS_MS = 40L

        /** Grace period (ms) before hiding the external tablet brush cursor on hover-exit / lift. */
        const val CURSOR_HIDE_DELAY_MS = 180L

        /** A finger's contact this far across (mm) or more is a palm: a fingertip is 8 to 15, a flat thumb under 20. */
        const val PALM_MM = 25f
    }
}
