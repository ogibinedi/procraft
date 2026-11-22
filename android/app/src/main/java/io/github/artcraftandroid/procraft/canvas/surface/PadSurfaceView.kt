package io.github.artcraftandroid.procraft.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.artcraftandroid.procraft.AppLog
import io.github.artcraftandroid.procraft.core.CanvasSession
import io.github.artcraftandroid.procraft.core.MobileException
import io.github.artcraftandroid.procraft.core.PenSample
import io.github.artcraftandroid.procraft.core.PointerKind

/**
 * The brush studio's drawing pad: a small canvas of its own, as big as the view, painted with the
 * brush being edited. Pen and finger both paint; there are no gestures.
 */
/** How often (ms) the pad is asked whether it has drawn yet, and for how long at most. */
private const val PAD_POLL_MS = 32L
private const val PAD_POLL_LIMIT_MS = 4000L

class PadSurfaceView(context: Context, private val vm: CanvasViewModel) : SurfaceView(context), SurfaceHolder.Callback {
    private var attached = false
    private var strokePointer = -1
    private var realTouchActive = false
    private var tabletSyntheticStroke = false
    private var pendingSyntheticPress = false

    init {
        // over the canvas's own surface, under the studio's controls
        setZOrderMediaOverlay(true)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val pad = vm.pad ?: CanvasSession(width.toUInt(), height.toUInt()).also { vm.startPad(it) }
        if (attached) {
            pad.resizeWindow(width.toUInt(), height.toUInt())
            return
        }
        val id = NativeWindows.acquire(holder.surface)
        if (id == 0L) return
        try {
            pad.attachWindow(id.toULong(), width.toUInt(), height.toUInt())
            pad.setDisplayScale(resources.displayMetrics.density * vm.interfaceShown)
            attached = true
            // uncovered once it has drawn a frame of its own
            uncoverWhenDrawn(pad)
        } catch (e: MobileException) {
            NativeWindows.release(id)
            AppLog.e("ProCraft", "attaching the pad", e)
        }
    }

    /**
     * Once the pad has presented a frame (or after a while regardless), draws its sample scribble,
     * and uncovers it (see [CanvasViewModel.padShown]) a poll later, the scribble drawn.
     */
    private fun uncoverWhenDrawn(pad: CanvasSession, waited: Long = 0L, scribbled: Boolean = false) {
        if (vm.pad !== pad) return
        if (scribbled) {
            vm.padShown = true
            return
        }
        if (waited >= PAD_POLL_LIMIT_MS || runCatching { pad.info().frames > 0uL }.getOrDefault(true)) {
            vm.padReady(pad)
            postDelayed({ uncoverWhenDrawn(pad, waited, scribbled = true) }, PAD_POLL_MS)
            return
        }
        postDelayed({ uncoverWhenDrawn(pad, waited + PAD_POLL_MS) }, PAD_POLL_MS)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        vm.pad?.detachWindow()
        attached = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = vm.pad ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vm.devicesWithRealTouch += e.deviceId
                pendingSyntheticPress = false
                if (tabletSyntheticStroke) {
                    tabletSyntheticStroke = false
                    pad.strokeCancel()
                }
                realTouchActive = true
                requestUnbufferedDispatch(e)
                vm.padMenuOpen = false
                strokePointer = e.getPointerId(0)
                // the first stroke of one's own takes the sample scribble's place
                pad.beginOwnStroke()
                val kind = pointerKindOf(e, 0, vm.drawingTabletMode)
                if (kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER) pad.setPenTiltKnown(tiltKnown(e))
                pad.strokeBegin(kind, samples(e, 0))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(strokePointer)
                if (i >= 0) pad.strokeAdd(samples(e, i))
            }
            MotionEvent.ACTION_UP -> {
                realTouchActive = false
                val i = e.findPointerIndex(strokePointer)
                pad.strokeEnd(if (i >= 0) samples(e, i) else emptyList())
            }
            MotionEvent.ACTION_CANCEL -> {
                realTouchActive = false
                pad.strokeCancel()
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val pad = vm.pad
        if (pad != null && !realTouchActive && !vm.activityTouchActive && e.deviceId !in vm.devicesWithRealTouch) {
            val kind = pointerKindOf(e, 0, vm.drawingTabletMode)
            val isStylus = kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER
            if (isStylus && e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                (e.actionButton == MotionEvent.BUTTON_PRIMARY || e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) &&
                !tabletSyntheticStroke
            ) {
                pendingSyntheticPress = true
            }
            if (e.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
                if (tabletSyntheticStroke &&
                    (e.actionButton == MotionEvent.BUTTON_PRIMARY || e.buttonState and MotionEvent.BUTTON_PRIMARY == 0)
                ) {
                    pendingSyntheticPress = false
                    tabletSyntheticStroke = false
                    pad.strokeEnd(samples(e, 0))
                    return true
                }
                if (pendingSyntheticPress && isStylus) {
                    pendingSyntheticPress = false
                    vm.padMenuOpen = false
                    pad.beginOwnStroke()
                    pad.setPenTiltKnown(tiltKnown(e))
                    pad.strokeBegin(kind, samples(e, 0))
                    pad.strokeEnd(samples(e, 0))
                    return true
                }
                pendingSyntheticPress = false
            }
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val pad = vm.pad
        val rawP = rawPressureOf(e, 0)
        vm.tabletPressureTracker.recordHoverPressure(e.deviceId, rawP)
        if (pad != null && !realTouchActive && !vm.activityTouchActive && e.deviceId !in vm.devicesWithRealTouch) {
            val kind = pointerKindOf(e, 0, vm.drawingTabletMode)
            val isStylus = kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER
            if (isStylus) {
                val nibPressed = (e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) ||
                    (isExternalDevice(e) && pressureRangeOf(e) != null && rawP > HOVER_PRESSURE_STROKE_THRESHOLD && vm.tabletPressureTracker.sawZeroHover(e.deviceId))
                if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE && nibPressed) {
                    pendingSyntheticPress = false
                    if (!tabletSyntheticStroke) {
                        tabletSyntheticStroke = true
                        vm.padMenuOpen = false
                        pad.beginOwnStroke()
                        pad.setPenTiltKnown(tiltKnown(e))
                        pad.strokeBegin(kind, samples(e, 0))
                    } else {
                        pad.strokeAdd(samples(e, 0))
                    }
                    return true
                }
                if (tabletSyntheticStroke && (!nibPressed || e.actionMasked == MotionEvent.ACTION_HOVER_EXIT)) {
                    tabletSyntheticStroke = false
                    pad.strokeEnd(samples(e, 0))
                    return true
                }
            }
        }
        return super.onHoverEvent(e)
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val kind = pointerKindOf(event, pointerIndex, vm.drawingTabletMode)
        if (kind == PointerKind.STYLUS || kind == PointerKind.STYLUS_ERASER || kind == PointerKind.MOUSE) {
            val iconType = if (vm.hideTabletArrowCursor) PointerIcon.TYPE_CROSSHAIR else PointerIcon.TYPE_ARROW
            return PointerIcon.getSystemIcon(context, iconType)
        }
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    private fun calibratedPressure(e: MotionEvent, i: Int, historyIdx: Int = -1): Float {
        val raw = rawPressureOf(e, i, historyIdx)
        val range = pressureRangeOf(e)
        vm.tabletPressureTracker.recordContactPressure(e.deviceId, raw, range != null)
        val kind = pointerKindOf(e, i, vm.drawingTabletMode)
        if (kind != PointerKind.STYLUS && kind != PointerKind.STYLUS_ERASER) return raw
        val ext = isExternalDevice(e)
        val learnedMax = vm.penPressureLearner.effectiveMax(devicePressureKeyOf(e.device))
        return calibratePenPressure(
            rawPressure = raw,
            axisMin = range?.start ?: 0f,
            axisMax = range?.endInclusive ?: 1f,
            hasPressureAxis = range != null,
            pressureEnabled = vm.penPressureEnabled,
            sensitivity = vm.penPressureSensitivity,
            minFloor = vm.penPressureMin,
            buttonPrimaryDown = e.buttonState and MotionEvent.BUTTON_PRIMARY != 0,
            isHovering = false,
            fallbackFullWhenUnreported = ext && !vm.tabletPressureTracker.hasAnalogPressure(e.deviceId),
            rawExceedsUnit = vm.tabletPressureTracker.usesRawRange(e.deviceId),
            externalActiveContact = ext,
            learnedMax = learnedMax,
        )
    }

    private fun samples(e: MotionEvent, i: Int): List<PenSample> {
        val out = ArrayList<PenSample>(e.historySize + 1)
        for (h in 0 until e.historySize) {
            out += PenSample(
                e.getHistoricalX(i, h),
                e.getHistoricalY(i, h),
                calibratedPressure(e, i, historyIdx = h),
                penLean(e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h), e.getHistoricalOrientation(i, h)),
                e.getHistoricalOrientation(i, h),
                e.getHistoricalEventTime(h).toDouble(),
            )
        }
        out += PenSample(
            e.getX(i),
            e.getY(i),
            calibratedPressure(e, i),
            penLean(e.getAxisValue(MotionEvent.AXIS_TILT, i), e.getOrientation(i)),
            e.getOrientation(i),
            e.eventTime.toDouble(),
        )
        return out
    }
}
