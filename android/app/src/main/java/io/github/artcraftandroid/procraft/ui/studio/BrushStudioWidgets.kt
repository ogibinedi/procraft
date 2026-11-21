package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.PadSurfaceView
import io.github.artcraftandroid.procraft.core.SourceKind
import io.github.artcraftandroid.procraft.core.curveSamples
import io.github.artcraftandroid.procraft.core.sliderExtras
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity

/**
 * The drawing pad's settings: preview size, the pad's colour, and clearing or resetting. It opens
 * under the Drawing Pad button, centred on it but kept 14 in from the screen's sides ([width]).
 */
@Composable
fun PadMenu(vm: CanvasViewModel, width: Dp) {
    if (!vm.studioOpen || !vm.padMenuOpen) return
    val centre = with(androidx.compose.ui.platform.LocalDensity.current) { vm.padButtonCentre.toDp() }
    val left = (centre - 160.dp).coerceIn(14.dp, (width - 334.dp).coerceAtLeast(14.dp))
    Column(
        Modifier.offset(left, 79.dp).width(320.dp).clip(RoundedCornerShape(26.dp)).background(PadMenuFill).padding(start = 10.dp, end = 10.dp, top = 17.dp, bottom = 10.dp),
    ) {
        Text("Settings", color = PadMenuText, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Text("Preview size", color = PadMenuText, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("${(vm.studio("preview_size") * 100).roundToInt()}%", color = PadMenuText, fontSize = 16.sp, maxLines = 1)
        }
        PadSizeSlider(vm.studio("preview_size")) { vm.setStudio("preview_size", it) }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CanvasViewModel.PAD_COLORS.forEachIndexed { i, c ->
                val chosen = vm.padColor == i
                Box(Modifier.size(37.dp).clip(CircleShape).background(if (chosen) Color(0xFFEBEBEB) else Color.Transparent).tap { vm.choosePadColor(i) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(19.dp).clip(CircleShape).background(CanvasSpace.color(c)))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PadMenuGroup)) {
            Box(Modifier.fillMaxWidth().height(45.dp).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("Stylus", color = PadMenuText, fontSize = 17.sp, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PadMenuLine))
            Row(
                Modifier.fillMaxWidth().height(45.dp).tap { vm.pad3d = !vm.pad3d }.padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("3D preview", color = PadMenuText, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Switch(vm.pad3d, enabled = true)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PadMenuGroup)) {
            Box(Modifier.fillMaxWidth().height(41.dp).tap { vm.clearPad() }.padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("Clear drawing pad", color = PadMenuText, fontSize = 17.sp, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PadMenuLine))
            Box(Modifier.fillMaxWidth().height(41.dp).tap { vm.resetStudio() }.padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text("Reset all brush settings", color = PadMenuText, fontSize = 17.sp, maxLines = 1)
            }
        }
    }
}

/** The pad menu's thin slider: blue up to a white knob, jumping to the touch. */
@Composable
private fun PadSizeSlider(value: Float, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 10.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                change((down.position.x / size.width).coerceIn(0f, 1f))
                while (true) {
                    val ev = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!ev.pressed) break
                    change((ev.position.x / size.width).coerceIn(0f, 1f))
                    ev.consume()
                }
            }
        }.drawBehind {
            val y = size.height / 2f
            val x = size.width * value.coerceIn(0f, 1f)
            drawLine(Color(0xFFA9A9A9), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Pc.Accent, Offset(0f, y), Offset(x, y), 3.dp.toPx(), cap = StrokeCap.Round)
            val knob = Size(28.dp.toPx(), 17.dp.toPx())
            val kx = x.coerceIn(knob.width / 2, size.width - knob.width / 2)
            drawRoundRect(Color(0x33000000), Offset(kx - knob.width / 2, y - knob.height / 2 + 1.dp.toPx()), knob, CornerRadius(knob.height / 2))
            drawRoundRect(Color.White, Offset(kx - knob.width / 2, y - knob.height / 2), knob, CornerRadius(knob.height / 2))
        },
    )
}

/**
 * What a slider's keypad offers besides typing: its own pressure curve ([curve], none while off) and
 * its own tilt (its switch, if it has one, and the angle the tilt starts from, 0..1 of a quarter
 * turn). Each setter changes the brush at once.
 */
class KeypadExtras(
    val pressure: Boolean,
    val curve: () -> List<Offset>?,
    val setCurve: (List<Offset>?) -> Unit,
    val tilt: Boolean,
    val tiltOn: () -> Boolean?,
    val setTiltOn: (Boolean) -> Unit,
    val tiltAngle: () -> Float,
    val setTiltAngle: (Float) -> Unit,
)

/** What studio control [key]'s keypad offers besides typing, as its brush holds it; none if nothing. */
internal fun keypadExtras(vm: CanvasViewModel, key: String): KeypadExtras? {
    val e = sliderExtras(key)
    if (!e.pressure && !e.tilt) return null
    return KeypadExtras(
        pressure = e.pressure,
        curve = { vm.studioSliderCurve(key) },
        setCurve = { vm.setStudioSliderCurve(key, it) },
        tilt = e.tilt,
        tiltOn = { e.tiltSwitch.takeIf { it.isNotEmpty() }?.let { vm.studio(it) >= 0.5f } },
        setTiltOn = { on -> if (e.tiltSwitch.isNotEmpty()) vm.setStudio(e.tiltSwitch, if (on) 1f else 0f) },
        tiltAngle = { vm.studio(e.tiltAngle) },
        setTiltAngle = { vm.setStudio(e.tiltAngle, it) },
    )
}

/** A labelled slider with its value in a pill: blue up to the knob, or out from the middle both ways. */
@Composable
internal fun StudioSlider(
    label: String,
    value: Float,
    shown: Shown,
    most: Float = 16f,
    enabled: Boolean = true,
    extras: KeypadExtras? = null,
    onChange: (Float) -> Unit,
) {
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    val both = shown == Shown.BOTH || shown == Shown.FOLLOW || shown == Shown.GRADE
    // a tap on the value types it on a keypad beside it (the pill blue meanwhile)
    val typed = typedNumber(shown, most)
    var typing by remember { mutableStateOf(false) }
    var pill by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Column(Modifier.fillMaxWidth().height(55.dp)) {
        // the name and value 2 below the top half's middle
        Row(Modifier.offset(y = 2.dp).fillMaxWidth().height(28.dp).padding(start = 10.dp, end = 3.5.dp), verticalAlignment = Alignment.CenterVertically) {
            FittedLabel(label, Modifier.weight(1f).padding(end = 12.dp))
            // as wide as its words need, 13 either side, and never narrower than 73
            Box(
                Modifier.height(28.dp).widthIn(min = 73.dp).onGloballyPositioned { pill = it.boundsInWindow() }.clip(RoundedCornerShape(14.dp))
                    .background(if (typing) Pc.Accent else if (enabled) StudioPill else StudioPillOff)
                    .then(if (enabled) Modifier.tap { typing = true } else Modifier)
                    .padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(shownValue(value, shown, most), color = if (typing) Color.White else if (enabled) StudioLabel else StudioValueOff, fontSize = 16.sp, maxLines = 1, softWrap = false)
                if (typing) SliderKeypad(label, pill, typed, { change(it) }, extras) { typing = false }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(20.dp).padding(start = 10.dp, end = 10.5.dp).pointerInput(both, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var last = down.position.x
                    var v = current
                    while (true) {
                        val ev = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ev.pressed) break
                        // relative: the knob follows the drag, as far as it travels
                        val span = if (both) 2f else 1f
                        val travel = (size.width - 2 * KNOB_INSET.toPx()).coerceAtLeast(1f)
                        v = (v + (ev.position.x - last) / travel * span).coerceIn(if (both) -1f else 0f, 1f)
                        last = ev.position.x
                        change(v)
                        ev.consume()
                    }
                }
            }.drawBehind {
                val y = size.height / 2f
                val t = (if (both) (value + 1f) / 2f else value).coerceIn(0f, 1f)
                // the knob's middle travels from 5 in at either end, so it overhangs the track by 3
                val inset = KNOB_INSET.toPx()
                val x = inset + (size.width - 2 * inset) * t
                val line = 3.dp.toPx()
                // a two-way slider's middle: a tick across the track
                if (both) drawLine(StudioTrack, Offset(size.width / 2f, y - 10.dp.toPx()), Offset(size.width / 2f, y + 10.dp.toPx()), 1.dp.toPx())
                drawLine(StudioTrack, Offset(0f, y), Offset(size.width, y), line)
                if (enabled) drawLine(Pc.Accent, Offset(if (both) size.width / 2f else 0f, y), Offset(x, y), line)
                val knob = Size(16.dp.toPx(), 10.dp.toPx())
                drawRoundRect(if (enabled) Color.White else StudioKnobOff, Offset(x - knob.width / 2, y - knob.height / 2), knob, CornerRadius(knob.height / 2))
            },
        )
    }
}

/**
 * How a slider's value is typed: the largest number, whether it may be negative (a "-" key), and what
 * a number makes of the value.
 */
private class Typed(val most: Int, val signed: Boolean, val toValue: (Int) -> Float)

/**
 * A slider's [Typed]: a percent of the slider (or of its doubled scale), both ways for the controls
 * going both ways, the count itself, or for a size the percent the slider reads (of [most]).
 */
private fun typedNumber(shown: Shown, most: Float): Typed = when (shown) {
    Shown.PERCENT, Shown.TIP, Shown.MOVEMENT, Shown.ZOOM, Shown.CHARGE, Shown.METAL, Shown.GLOSS -> Typed(100, false) { n -> n / 100f }
    Shown.DOUBLE, Shown.TWICE -> Typed(200, false) { n -> n / 200f }
    Shown.BOTH, Shown.FOLLOW, Shown.GRADE -> Typed(100, true) { n -> n / 100f }
    Shown.COUNT -> Typed(16, false) { n -> (n.coerceAtLeast(1) - 1) / 15f }
    Shown.SIZE -> Typed((most * 100f).roundToInt(), false) { n -> ((n / 100f) / most.coerceAtLeast(1e-3f)).coerceIn(0f, 1f).toDouble().pow(1.0 / 2.2).toFloat() }
}

private val KeypadFill = Color(0xFFD5D5D5)
private val KeypadKey = Color(0xFFE6E6E6)
private val KeypadInk = Color(0xFF1D1D1D)
private val KeypadWidth = 275.dp
private val KeypadHeight = 360.dp
/**
 * With pages to choose between, the tabs under the title push the keys down, 64 tall: the popover
 * is 410 tall with two tabs, 386 with three (as measured).
 */
private fun keypadHeight(tabs: Int) = when (tabs) {
    1 -> KeypadHeight
    2 -> 410.dp
    else -> 386.dp
}
private fun keypadKeyHeight(tabs: Int) = if (tabs > 1) 64.dp else 66.dp
private val KeypadTabs = Color(0xFFC1C1C1)
private val KeypadTabChosen = Color(0xFF0A8CFD)
private val KeypadTabInk = Color(0xFF393939)
private val KeypadGraph = Color(0xFFE2E2E2)
private val KeypadGraphEdge = Color(0xFF999999)
private val KeypadGraphGrid = Color(0xFFD0D0D0)
private val KeypadSwitchOff = Color(0xFFC2C1C5)

/** A slider keypad's pages: typing the value, the slider's own pressure curve, its own tilt. */
private enum class KeypadPage { NUMERIC, PRESSURE, TILT }

/**
 * A slider's keypad, a light popover to the right of its value ([pill], window pixels), its arrow
 * at the value: the slider's name, then 7 8 9 / 4 5 6 / 1 2 3 / 0 and backspace (81 x 66 keys),
 * "-" 0 and backspace for a value that may be negative. Each key sets the value at once: the digits
 * typed so far are the number (beyond the most, the most); "-" makes it negative; backspace takes
 * the last one off (none left: nothing).
 */
@Composable
private fun SliderKeypad(
    title: String,
    pill: androidx.compose.ui.geometry.Rect,
    typed: Typed,
    onValue: (Float) -> Unit,
    extras: KeypadExtras?,
    onDismiss: () -> Unit,
) {
    // tabs over the keys when the slider has a pressure curve or a tilt of its own
    val pages = buildList {
        add(KeypadPage.NUMERIC)
        if (extras?.pressure == true) add(KeypadPage.PRESSURE)
        if (extras?.tilt == true) add(KeypadPage.TILT)
    }
    var page by remember { mutableStateOf(KeypadPage.NUMERIC) }
    val height = keypadHeight(pages.size)
    var text by remember { mutableStateOf("") }
    fun type(next: String) {
        // a sign alone waits for its digits
        if (next == "-") {
            text = next
            return
        }
        val n = next.toIntOrNull()?.coerceIn(-typed.most, typed.most) ?: 0
        text = if (next.isEmpty()) "" else n.toString()
        onValue(typed.toValue(n))
    }
    val density = LocalDensity.current
    val rootView = androidx.compose.ui.platform.LocalView.current.rootView
    val screen = with(density) { rootView.height.toDp() }
    val screenW = with(density) { rootView.width.toDp() }
    val (top, arrowAt) = with(density) {
        val middle = (pill.top + pill.height / 2f).toDp()
        val top = (middle - height / 2).coerceIn(2.dp, (screen - height - 2.dp).coerceAtLeast(2.dp))
        top to (middle - top)
    }
    val openOnRight = with(density) { pill.right + (KeypadWidth + Pc.ArrowHeight).toPx() <= rootView.width - 4.dp.toPx() }
    val arrowSide = if (openOnRight) ArrowSide.LEFT else ArrowSide.RIGHT
    // when there is room to the right of the pill, open on its right; on smartphones where the pill is at the right edge, open to its left
    val offset = with(density) {
        val rawX = if (openOnRight) {
            (pill.width - (Pc.ArrowHeight - 3.dp).toPx()).roundToInt()
        } else {
            -((KeypadWidth + Pc.ArrowHeight - 3.dp).toPx()).roundToInt()
        }
        val minX = (4.dp.toPx() - pill.left).roundToInt()
        val maxX = (screenW.toPx() - (KeypadWidth + Pc.ArrowHeight + 4.dp).toPx() - pill.left).roundToInt()
        val clampedX = if (minX <= maxX) rawX.coerceIn(minX, maxX) else minX
        androidx.compose.ui.unit.IntOffset(clampedX, (top.toPx() - pill.top).roundToInt())
    }
    PcPopup(
        offset = offset,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Popover(KeypadWidth, arrowAt, Modifier.height(height), fill = KeypadFill, corner = 22.dp, side = arrowSide) {
            Text(title, color = KeypadInk, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 25.dp, top = 19.dp).height(if (pages.size > 1) 43.dp else 51.dp))
            if (pages.size > 1) {
                KeypadTabRow(pages, page) { page = it }
                Spacer(Modifier.height(12.dp))
            }
            when (page) {
                KeypadPage.NUMERIC -> Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val keyHeight = keypadKeyHeight(pages.size)
                    for (row in listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3"))) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (k in row) KeypadKey(k, 81.dp, keyHeight) { type(text + k) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (typed.signed) {
                            KeypadKey("-", 81.dp, keyHeight) { type(if (text.startsWith("-")) text.drop(1) else "-$text") }
                            KeypadKey("0", 81.dp, keyHeight) { type(text + "0") }
                        } else {
                            KeypadKey("0", 166.dp, keyHeight) { type(text + "0") }
                        }
                        KeypadKey("⌫", 81.dp, keyHeight) { type(text.dropLast(1)) }
                    }
                }
                KeypadPage.PRESSURE -> if (extras != null) KeypadPressure(extras)
                KeypadPage.TILT -> if (extras != null) KeypadTilt(extras)
            }
        }
    }
}

/** The keypad's tabs under its title: an icon for each page, the chosen one on blue. */
@Composable
private fun KeypadTabRow(pages: List<KeypadPage>, chosen: KeypadPage, onChoose: (KeypadPage) -> Unit) {
    Row(Modifier.padding(start = 12.dp).size(251.dp, 30.dp).clip(RoundedCornerShape(9.dp)).background(KeypadTabs).padding(2.dp)) {
        for (p in pages) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(if (p == chosen) KeypadTabChosen else Color.Transparent).tap { onChoose(p) },
                contentAlignment = Alignment.Center,
            ) {
                val icon = when (p) {
                    KeypadPage.NUMERIC -> PcIcons.Keypad
                    KeypadPage.PRESSURE -> PcIcons.PenPressure
                    KeypadPage.TILT -> PcIcons.PenTilt
                }
                Icon(icon, contentDescription = p.name.lowercase().replaceFirstChar { it.uppercase() }, tint = if (p == chosen) Color.White else KeypadTabInk, modifier = Modifier.requiredSize(30.dp))
            }
        }
    }
}

/** A page's name and its switch, in a row across the keypad. */
@Composable
private fun KeypadSwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(start = 18.dp, end = 8.dp).fillMaxWidth().height(28.dp).tap { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = KeypadInk, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Switch(on, enabled = true, offTrack = KeypadSwitchOff)
    }
}

/** The pressure page: the slider's own curve, on or off, drawn on a light grid. */
@Composable
private fun KeypadPressure(extras: KeypadExtras) {
    var curve by remember { mutableStateOf(extras.curve()) }
    Column {
        Spacer(Modifier.height(6.dp))
        KeypadSwitchRow("Pressure", curve != null) { on ->
            val next = if (on) listOf(Offset(0f, 0f), Offset(1f, 1f)) else null
            curve = next
            extras.setCurve(next)
        }
        Spacer(Modifier.height(12.dp))
        PressureCurve(
            curve ?: emptyList(),
            modifier = Modifier.padding(start = 18.dp).size(239.dp, 219.dp),
            grid = KeypadGraphGrid,
            edge = KeypadGraphEdge,
            fill = KeypadGraph,
            line = Pc.Accent,
            lineWidth = 1.5.dp,
        ) { points ->
            if (curve != null) {
                curve = points
                extras.setCurve(points)
            }
        }
    }
}

/** The tilt page: the slider's own tilt switch (if it has one) over the angle its tilt starts from. */
@Composable
private fun KeypadTilt(extras: KeypadExtras) {
    var on by remember { mutableStateOf(extras.tiltOn()) }
    var angle by remember { mutableStateOf(extras.tiltAngle()) }
    Column {
        Spacer(Modifier.height(6.dp))
        on?.let { now ->
            KeypadSwitchRow("Tilt", now) {
                on = it
                extras.setTiltOn(it)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(start = 10.dp).size(255.dp, 209.dp).background(KeypadFill).padding(5.dp)) {
            TiltAngle(angle, LightTilt, active = on ?: true) {
                angle = it
                extras.setTiltAngle(it)
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, width: Dp, height: Dp = 66.dp, onClick: () -> Unit) {
    Box(Modifier.size(width, height).clip(RoundedCornerShape(12.dp)).background(KeypadKey).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        if (label == "⌫") {
            Icon(PcIcons.Backspace, contentDescription = "Back space", tint = KeypadInk, modifier = Modifier.size(26.dp))
        } else {
            Text(label, color = KeypadInk, fontSize = 29.sp)
        }
    }
}

/** A slider's name, 17 sp, made smaller (to 60%) when it would not fit beside the value. */
@Composable
private fun FittedLabel(label: String, modifier: Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val size = remember(label, room) {
            fun wide(sp: Float) = measurer.measure(label, androidx.compose.ui.text.TextStyle(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width
            var sp = 17f
            while (sp > 17f * 0.6f && wide(sp) > room) sp -= 0.5f
            sp
        }
        Text(label, color = StudioLabel, fontSize = size.sp, maxLines = 1, softWrap = false)
    }
}

/** How far in from the track's ends a studio slider's knob stops. */
private val KNOB_INSET = 5.dp

/**
 * The taper bar: a box inset as far as the sliders' knobs travel, the bar across it thinning to
 * points at its ends as the start and end handles (on lines across the box) move in, each up to
 * half way.
 */
@Composable
internal fun TaperBar(start: Float, end: Float, onStart: (Float) -> Unit, onEnd: (Float) -> Unit) {
    val s by rememberUpdatedState(start)
    val e by rememberUpdatedState(end)
    val setStart by rememberUpdatedState(onStart)
    val setEnd by rememberUpdatedState(onEnd)
    Canvas(
        Modifier.fillMaxWidth().height(72.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val from = TAPER_TRAVEL.toPx()
                val half = (size.width - 2 * from) / 2f
                // the nearer handle
                val startX = from + s * half
                val endX = size.width - from - e * half
                val grabStart = kotlin.math.abs(down.position.x - startX) <= kotlin.math.abs(down.position.x - endX)
                while (true) {
                    val ev = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!ev.pressed) break
                    if (grabStart) setStart(((ev.position.x - from) / half).coerceIn(0f, 1f)) else setEnd(((size.width - from - ev.position.x) / half).coerceIn(0f, 1f))
                    ev.consume()
                }
            }
        },
    ) {
        val left = TAPER_TRAVEL.toPx() - 1.dp.toPx()
        val right = size.width - left
        drawRoundRect(TaperBox, Offset(left, 0f), Size(right - left, size.height), CornerRadius(5.dp.toPx()))
        val y = size.height / 2f
        val half = (size.width - 2 * TAPER_TRAVEL.toPx()) / 2f
        val startX = TAPER_TRAVEL.toPx() + start * half
        val endX = size.width - TAPER_TRAVEL.toPx() - end * half
        val thick = 7.dp.toPx()
        val bar = Path().apply {
            moveTo(left, y)
            lineTo(startX, y - thick)
            lineTo(endX, y - thick)
            lineTo(right, y)
            lineTo(endX, y + thick)
            lineTo(startX, y + thick)
            close()
        }
        drawPath(bar, TaperBarFill)
        for (x in listOf(startX, endX)) {
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            drawCircle(Color.White, 6.5.dp.toPx(), Offset(x, y))
            drawCircle(Pc.Accent, 4.dp.toPx(), Offset(x, y))
        }
    }
}

/** How far in from the row's ends the taper handles stop: where the sliders' knobs do. */
private val TAPER_TRAVEL = 15.dp
private val TaperBox get() = if (Pc.light) Color(0xFFEBEBEB) else Color(0xFF212121)
/** The studio's pressure curve: a darker box with a light edge, a grey curve, small handles. */
internal val StudioCurveFill get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF1B1B1B)
internal val StudioCurveEdge get() = if (Pc.light) Color(0xFFD8D8D8) else Color(0xFF353535)
internal val StudioCurveLine get() = if (Pc.light) Color(0xFF3A3A3A) else Color(0xFFD5D5D5)
private val TaperBarFill get() = if (Pc.light) Color(0xFFDCDCDC) else Color(0xFF363636)

/**
 * How the tilt angle's graph is coloured: its box, the quarter disc, the arc, the line, the angle's
 * label, and its handle's middle while the tilt is off.
 */
internal class TiltLook(val box: Color, val quarter: Color, val arc: Color, val line: Color, val label: Color, val off: Color, val fill: Boolean = true)

private val DarkTilt = TiltLook(Color(0xFF1B1B1B), Color(0xFF262626), Color(0xFF4A4A4A), Color(0xFF6E6E6E), Color(0xFF8E8E8E), Color(0xFF6E6E6E))
/** In the keypads: the disc darker than the box round it, as measured. */
private val LightTilt = TiltLook(Color(0xFFE6E6E6), Color(0xFFDADADA), Color(0xFFABABAB), Color(0xFF797979), Color(0xFFAAAAAA), Color(0xFFAAAAAA), fill = false)

/**
 * The tilt angle: a quarter disc from the bottom-left corner with a line at the angle (0 flat, 1
 * upright) and its handle on the arc; drag anywhere to turn it.
 */
@Composable
internal fun TiltAngle(value: Float, look: TiltLook = DarkTilt, active: Boolean = true, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val degrees = (value.coerceIn(0f, 1f) * 90f).roundToInt()
    Box(
        // not clipped: the handle sits on the box's edge, whole
        if (look.fill) Modifier.padding(horizontal = 5.dp).fillMaxWidth().aspectRatio(271.5f / 221f).background(look.box, RoundedCornerShape(6.dp))
        else Modifier.fillMaxSize().background(look.box),
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // the angle of the touch from the bottom-left corner, a quarter turn at most
                fun angleAt(p: Offset) = (kotlin.math.atan2((size.height - p.y).toDouble(), p.x.toDouble()) / (Math.PI / 2)).toFloat().coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change(angleAt(down.position))
                    while (true) {
                        val ev = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ev.pressed) break
                        change(angleAt(ev.position))
                        ev.consume()
                    }
                }
            },
        ) {
            val corner = Offset(0f, size.height)
            val r = size.height
            clipRect {
                drawCircle(look.quarter, r, corner)
                drawCircle(look.arc, r, corner, style = Stroke(1.dp.toPx()))
            }
            val a = value.coerceIn(0f, 1f) * (Math.PI / 2)
            val dir = Offset(kotlin.math.cos(a).toFloat(), -kotlin.math.sin(a).toFloat())
            // the line runs on to the box's edge; the handle sits on the arc
            val reach = minOf(if (dir.x > 1e-4f) size.width / dir.x else Float.MAX_VALUE, if (dir.y < -1e-4f) size.height / -dir.y else Float.MAX_VALUE)
            drawLine(look.line, corner, corner + dir * reach, 1.dp.toPx())
            drawCircle(Color.White, 5.5.dp.toPx(), corner + dir * r)
            drawCircle(if (active) Pc.Accent else look.off, 3.5.dp.toPx(), corner + dir * r)
        }
        Text("$degrees°", color = look.label, fontSize = 17.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 9.dp))
    }
}

/**
 * The shape's roundness and angle: an ellipse on crossed lines, its blue handles (the ends of its
 * short axis) dragged in and out for the roundness, its green handle (the end of its long axis)
 * dragged round for the angle, shown in degrees.
 */
@Composable
internal fun RoundnessGraph(roundness: Float, angle: Float, onRoundness: (Float) -> Unit, onAngle: (Float) -> Unit) {
    val setRound by rememberUpdatedState(onRoundness)
    val setAngle by rememberUpdatedState(onAngle)
    val round by rememberUpdatedState(roundness.coerceIn(0.05f, 1f))
    val turn by rememberUpdatedState(angle)
    val degrees = ((angle.coerceIn(0f, 1f) * 360f).roundToInt()) % 360
    Box(
        Modifier.fillMaxWidth().aspectRatio(283f / 231.5f)
            .clip(RoundedCornerShape(6.dp)).background(TiltBox),
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.height * 78f / 231.5f
                    val a = turn * 2f * Math.PI.toFloat()
                    val long = Offset(kotlin.math.cos(a), -kotlin.math.sin(a))
                    val short = Offset(-long.y, long.x)
                    // the green handle turns it; anything else squashes it
                    val turning = (down.position - (c + long * r)).getDistance() < 28.dp.toPx()
                    while (true) {
                        val ev = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ev.pressed) break
                        val d = ev.position - c
                        if (turning) {
                            val t = (kotlin.math.atan2(-d.y, d.x) / (2 * Math.PI)).toFloat()
                            setAngle(if (t < 0f) t + 1f else t)
                        } else {
                            val along = kotlin.math.abs(d.x * short.x + d.y * short.y)
                            setRound((along / r).coerceIn(0.05f, 1f))
                        }
                        ev.consume()
                    }
                }
            },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.height * 78f / 231.5f
            // the crossed lines show dimly through the ellipse's fill
            drawLine(RoundnessCross, Offset(0f, c.y), Offset(size.width, c.y), 1.dp.toPx())
            drawLine(RoundnessCross, Offset(c.x, 0f), Offset(c.x, size.height), 1.dp.toPx())
            val a = turn.coerceIn(0f, 1f) * 360f
            withTransform({ rotate(-a, c) }) {
                val oval = androidx.compose.ui.geometry.Size(2 * r, 2 * r * round)
                val at = Offset(c.x - r, c.y - r * round)
                drawOval(RoundnessFill, at, oval)
                drawOval(RoundnessEdge, at, oval, style = Stroke(1.dp.toPx()))
            }
            val rad = Math.toRadians(a.toDouble())
            val long = Offset(kotlin.math.cos(rad).toFloat(), -kotlin.math.sin(rad).toFloat())
            val short = Offset(-long.y, long.x)
            for (end in listOf(c + short * (r * round), c - short * (r * round))) {
                drawCircle(Color.White, 5.6.dp.toPx(), end)
                drawCircle(Pc.Accent, 3.7.dp.toPx(), end)
            }
            drawCircle(Color.White, 5.6.dp.toPx(), c + long * r)
            drawCircle(RoundnessGreen, 3.7.dp.toPx(), c + long * r)
        }
        Text("$degrees°", color = TiltLabel, fontSize = 17.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp))
    }
}

private val RoundnessGreen = Color(0xFF42E347)
private val RoundnessCross get() = if (Pc.light) Color(0xFFC0C0C0) else Color(0xFF4C4C4C)
private val RoundnessFill get() = if (Pc.light) Color(0xD6F0F0F0) else Color(0xD6232323)
private val RoundnessEdge get() = if (Pc.light) Color(0xFFC8C8C8) else Color(0xFF565656)
private val TiltBox get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF1B1B1B)
private val TiltLabel get() = if (Pc.light) Color(0xFF808080) else Color(0xFF8E8E8E)

@Composable
internal fun PressureCurve(
    points: List<Offset>,
    modifier: Modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth().aspectRatio(1f),
    grid: Color = Color(0xFF3A3A3A),
    edge: Color = grid,
    /** The box's own fill and corners, the curve's colour and width, and its handles' radii (ring, middle). */
    fill: Color = Color.Transparent,
    corner: Dp = 0.dp,
    line: Color = Color.White,
    lineWidth: Dp = 1.5.dp,
    handle: Pair<Dp, Dp> = 7.dp to 5.dp,
    onChange: (List<Offset>) -> Unit,
) {
    val pts by rememberUpdatedState(points)
    val change by rememberUpdatedState(onChange)
    val samples = remember(points) { curveSamples(points.flatMap { listOf(it.x, it.y) }, 129u) }
    // the node whose menu shows (a tap on a node), and the box's size for placing it
    var menu by remember { mutableStateOf<Int?>(null) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(modifier.onSizeChanged { box = it }) {
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                menu = null
                // no curve (switched off): nothing to touch
                if (pts.isEmpty()) return@awaitEachGesture
                fun toCurve(p: Offset) = Offset((p.x / size.width).coerceIn(0f, 1f), (1f - p.y / size.height).coerceIn(0f, 1f))
                val at = toCurve(down.position)
                var list = pts.toMutableList()
                var index = list.indexOfFirst { (it - at).getDistance() < 0.05f }
                val added = index < 0
                if (added) {
                    list.add(at)
                    list.sortBy { it.x }
                    index = list.indexOf(at)
                    change(list.toList())
                }
                var moved = false
                while (true) {
                    val ev = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!ev.pressed) break
                    if (!moved && (ev.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                    moved = true
                    val p = toCurve(ev.position)
                    val lo = list.getOrNull(index - 1)?.x ?: 0f
                    val hi = list.getOrNull(index + 1)?.x ?: 1f
                    list = list.toMutableList().also { it[index] = Offset(p.x.coerceIn(lo, hi), p.y) }
                    change(list.toList())
                    ev.consume()
                }
                // a tap on a node shows what can be done with it
                if (!moved && !added) menu = index
            }
        },
    ) {
        val w = size.width
        val h = size.height
        val round = CornerRadius(corner.toPx())
        drawRoundRect(fill, cornerRadius = round)
        for (k in 1..3) {
            drawLine(grid, Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1.dp.toPx())
            drawLine(grid, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1.dp.toPx())
        }
        drawRoundRect(edge, cornerRadius = round, style = Stroke(1.dp.toPx()))
        if (points.isEmpty()) return@Canvas
        val sorted = points.sortedBy { it.x }
        val path = Path()
        val last = (samples.size - 1).coerceAtLeast(1)
        samples.forEachIndexed { i, v ->
            val x = w * i / last
            val y = (1f - v) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(lineWidth.toPx(), cap = StrokeCap.Round))
        for (p in sorted) {
            drawCircle(Color.White, handle.first.toPx(), Offset(p.x * w, (1f - p.y) * h))
            drawCircle(Pc.Accent, handle.second.toPx(), Offset(p.x * w, (1f - p.y) * h))
        }
    }
    menu?.let { i -> NodeMenu(points.sortedBy { it.x }, i, box, { menu = null }, change) }
    }
}

/**
 * The menu over node [index] of a curve through [points] (the box [box] pixels): Delete (a node
 * between the ends) and Reset (the curve straight again).
 */
@Composable
private fun NodeMenu(points: List<Offset>, index: Int, box: androidx.compose.ui.unit.IntSize, close: () -> Unit, change: (List<Offset>) -> Unit) {
    val at = points.getOrNull(index) ?: return
    val end = index == 0 || index == points.lastIndex
    val rows = if (end) listOf("Reset") else listOf("Delete", "Reset")
    // centred over the node, its bottom 11 above it; in a window of its own, so the column's edge
    // doesn't cut it (with room round it for its shadow)
    val offset = with(LocalDensity.current) {
        androidx.compose.ui.unit.IntOffset(
            (at.x * box.width - (NodeMenuWidth / 2 + NodeMenuHalo).toPx()).roundToInt(),
            ((1f - at.y) * box.height - (11.dp + NodeMenuRow * rows.size + NodeMenuHalo).toPx()).roundToInt(),
        )
    }
    PcPopup(offset = offset, onDismissRequest = close, properties = androidx.compose.ui.window.PopupProperties(focusable = true, clippingEnabled = false)) {
    Column(
        Modifier.padding(NodeMenuHalo).width(NodeMenuWidth).shadow(14.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(12.dp)).background(NodeMenuFill),
    ) {
        for (row in rows) {
            Box(
                Modifier.fillMaxWidth().height(NodeMenuRow).tap {
                    close()
                    change(if (row == "Reset") listOf(Offset(0f, 0f), Offset(1f, 1f)) else points.filterIndexed { k, _ -> k != index })
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(row, color = Color.White, fontSize = 17.sp, maxLines = 1)
            }
        }
    }
    }
}

/** A node menu's look (a curve's, the guide editor's handles'): its fill, the room round it for its shadow, its width and its rows' height. */
internal val NodeMenuFill = Color(0xFF101010)
internal val NodeMenuHalo = 16.dp
internal val NodeMenuWidth = 70.dp
internal val NodeMenuRow = 36.dp
