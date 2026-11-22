package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.groups
import io.github.artcraftandroid.procraft.canvas.label
import io.github.artcraftandroid.procraft.canvas.paintable
import io.github.artcraftandroid.procraft.canvas.swiped
import io.github.artcraftandroid.procraft.core.Adjustment
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import kotlin.math.roundToInt

// light: the band see-through (241 over white, 228 over the surround), the panel near white
// translucent over the canvas (as measured over white and over a colour)
internal val ModeBar get() = if (Pc.light) Color(0xD6EEEEEE) else Color(0xD63B3B3B)
internal val ModeText get() = if (Pc.light) Color(0xFF6A6A6A) else Color(0xFFC5C5C5)
internal val HsbPanelFill get() = if (Pc.light) Color(0xF7F1F1F1) else Color(0xF71E1E1E)
internal val HsbLabel get() = if (Pc.light) Color(0xFF595959) else Color(0xFF808080)
internal val HsbValue get() = if (Pc.light) Color(0xFF363636) else Color(0xFFEDEDED)

/** A row of the adjustments menu's groups. */
@Composable
private fun MenuRow(label: String, onTap: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).tap(onTap).padding(start = 15.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = MenuText, fontSize = 17.sp, maxLines = 1)
    }
}

/**
 * The adjustments menu hanging from its button, in groups: colour, blurs, effects, painting. With a
 * filter imported, or a plugin's action listed there, a group more: a row that turns the menu to
 * them (the filters, then the actions). With none, it isn't there.
 */
@Composable
fun AdjustmentsPanel(vm: CanvasViewModel, arrowX: Dp, modifier: Modifier = Modifier) {
    val filters = remember { vm.plugins.filters() }
    val actions = remember { vm.plugins.actionsIn(io.github.artcraftandroid.procraft.core.PluginPlace.ADJUSTMENTS) }
    var showFilters by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showFilters) { showFilters = false }
    Popover(330.dp, arrowX, modifier) {
        // a 52 header, the title's capitals from 28.5 below the panel's top; the groups 8 into the
        // list under it
        if (showFilters) {
            // turned to the filters, the way back is the panels' own, the title beside it
            Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, top = 17.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp, 34.dp).clickable { showFilters = false }, contentAlignment = Alignment.Center) {
                    Icon(PcIcons.ChevronLeft, contentDescription = "Back", tint = Pc.PanelTitle, modifier = Modifier.size(22.dp))
                }
                Text("Plugins", color = Pc.PanelTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        } else {
            Box(Modifier.fillMaxWidth().height(52.dp).padding(start = 24.dp, top = 22.5.dp)) {
                Text("Adjustments", color = Pc.PanelTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showFilters) {
                if (filters.isNotEmpty()) MenuGroup(filters) { filter -> MenuRow(filter.name) { vm.startPluginFilter(filter) } }
                if (actions.isNotEmpty()) MenuGroup(actions) { action -> MenuRow(action.label) { vm.runPluginAction(action.key) } }
            } else {
                val groups = Adjustment.groups
                groups.forEachIndexed { idx, group ->
                    if (idx == groups.lastIndex && PocketErgonomics.showRecolorInAdjustments(vm.modelOpen)) {
                        val withRecolor = group.map { a -> a.label to { vm.startAdjustment(a) } } +
                            ("Recolor" to { vm.toggleRecolor() })
                        MenuGroup(withRecolor) { (label, run) -> MenuRow(label, run) }
                    } else {
                        MenuGroup(group) { a -> MenuRow(a.label) { vm.startAdjustment(a) } }
                    }
                }
                if (filters.isNotEmpty() || actions.isNotEmpty()) {
                    MenuGroup(listOf("Plugins")) { label -> MenuRow(label) { showFilters = true } }
                }
            }
        }
    }
}

/** One of the adjustments menu's groups: [rows] apart, on its card. */
@Composable
internal fun <T> MenuGroup(rows: List<T>, row: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Pc.ActionsGroup)) {
        rows.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
            row(item)
        }
    }
}

/**
 * The bar under the top bar while an adjustment is open: its icon and name, its amount when it has
 * one, and the triangle that opens its options; and under it the amount as a thin blue bar. The
 * bar takes its touches: a tap anywhere on it opens Layer or Pencil, a swipe from it adjusts
 * nothing.
 */
@Composable
fun AdjustmentBar(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val a = vm.adjustment ?: return
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).background(ModeBar).pointerInput(a) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var slid = false
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) slid = true
                        c.consume()
                        if (!c.pressed) {
                            if (!slid && a.paintable) vm.adjustModesOpen = !vm.adjustModesOpen
                            break
                        }
                    }
                }
            },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(33.dp, 32.dp), contentAlignment = Alignment.Center) {
                // drawn in a 32 frame, shown as it is
                Icon(a.icon(), contentDescription = null, tint = ModeText, modifier = Modifier.requiredSize(32.dp))
            }
            Text(vm.pluginFilter?.name ?: a.label, color = ModeText, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (a.swiped) {
                Spacer(Modifier.width(3.dp))
                Text("${(vm.adjustAmount * 100).roundToInt()}%", color = ModeText, fontSize = 14.sp, maxLines = 1)
            }
            // Layer or Pencil, for the adjustments that can be painted on
            if (a.paintable) {
                Box(Modifier.size(33.dp, 32.dp), contentAlignment = Alignment.Center) {
                    Icon(PcIcons.Disclose, contentDescription = null, tint = ModeText, modifier = Modifier.requiredSize(32.dp))
                }
            }
        }
        if (a.swiped) {
            Box(Modifier.fillMaxWidth(vm.adjustAmount.coerceIn(0f, 1f)).height(4.dp).background(Pc.Accent))
        }
    }
}

/** Layer opacity's band: its glyph, "Opacity" and the opacity, the blue line across the screen under it. */
@Composable
fun LayerOpacityBar(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(32.dp).background(ModeBar), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(33.dp, 32.dp), contentAlignment = Alignment.Center) { OpacityGlyph(ModeText) }
            Text("Opacity", color = ModeText, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            Text("${(vm.opacityValue * 100).roundToInt()}%", color = ModeText, fontSize = 14.sp, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth(vm.opacityValue.coerceIn(0f, 1f)).height(4.dp).background(Pc.Accent))
    }
}

/**
 * Opacity's glyph, 14 across: a ring behind and up to the right, a disc in front, striped where
 * it covers the ring (the stripes cut through to what is behind).
 */
@Composable
private fun OpacityGlyph(ink: Color) {
    androidx.compose.foundation.Canvas(
        Modifier.size(14.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) {
        val u = size.width / 14f
        val back = Offset(8.7f * u, 5.3f * u)
        val front = Offset(5.4f * u, 8.6f * u)
        val r = 4.9f * u
        drawCircle(ink, r, back, style = androidx.compose.ui.graphics.drawscope.Stroke(1.1f * u))
        drawCircle(ink, r, front)
        // the stripes, only where the disc covers the ring's inside
        val inside = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(back, r)) }
        clipPath(inside) {
            for (k in -1..1) {
                val o = k * 2.2f * u
                drawLine(Color.Black, Offset(front.x + o - 5f * u, front.y - o - 5f * u), Offset(front.x + o + 5f * u, front.y - o + 5f * u), 1f * u, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            }
        }
    }
}

internal fun Adjustment.icon() = when (this) {
    Adjustment.HSB -> PcIcons.AdjustHsb
    Adjustment.COLOR_BALANCE -> PcIcons.AdjustBalance
    Adjustment.CURVES -> PcIcons.AdjustCurves
    Adjustment.GRADIENT_MAP -> PcIcons.AdjustGradient
    Adjustment.GAUSSIAN_BLUR -> PcIcons.AdjustBlur
    Adjustment.MOTION_BLUR -> PcIcons.AdjustMotion
    Adjustment.PERSPECTIVE_BLUR -> PcIcons.AdjustPerspective
    Adjustment.NOISE -> PcIcons.AdjustNoise
    Adjustment.SHARPEN -> PcIcons.AdjustSharpen
    Adjustment.BLOOM -> PcIcons.AdjustBloom
    Adjustment.GLITCH -> PcIcons.AdjustGlitch
    Adjustment.HALFTONE -> PcIcons.AdjustHalftone
    Adjustment.CHROMATIC_ABERRATION -> PcIcons.AdjustChromatic
    Adjustment.LIQUIFY -> PcIcons.AdjustLiquify
    Adjustment.CLONE -> PcIcons.AdjustClone
    Adjustment.PLUGIN -> PcIcons.Adjustments
}

/**
 * The window's width, which the adjustment toolbars and the animation bar lay themselves out by (not
 * its shape), less what the interface keeps clear of across it.
 */
@Composable
internal fun windowWidth(): Dp {
    val clear = LocalClearance.current
    val width = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width - clear.left - clear.right
    return with(androidx.compose.ui.platform.LocalDensity.current) { width.toDp() }
}

/**
 * Where each toolbar lays its sliders side by side (as measured in landscape; upright, stacked): the
 * window less 8 each side at least this. Liquify's whole row: the window itself at least this.
 */
private val HsbSideBySide = 800.dp
private val BalanceSideBySide = 820.dp
internal val GlitchSideBySide = 820.dp
internal val NoiseSideBySide = 904.dp
internal val BloomSideBySide = 800.dp
private val GradientSideBySide = 800.dp
internal val LiquifyFullFrom = 1000.dp

/** Whether a toolbar lays its sliders side by side in a window [window] wide, from [from]. */
internal fun sideBySide(window: Dp, from: Dp): Boolean = window - 16.dp >= from

/** A toolbar [natural] wide where there is room, in a window [window] wide: no wider than the window less 8 each side. */
internal fun toolbarWidth(natural: Dp, window: Dp): Dp = minOf(natural, window - 16.dp).coerceAtLeast(0.dp)

/** Each of [count] sliders [natural] wide in a toolbar [full] wide, now the toolbar is [width] wide: what it gave up shared among them. */
internal fun squeezed(natural: Dp, count: Int, full: Dp, width: Dp): Dp = natural - (full - width) / count

/**
 * Liquify's whole row in a window [window] wide: its four sliders a quarter of the window less 108
 * (at most 300 each), the row 40 less than the window (1093 on a 1133 window, as measured).
 */
internal fun liquifyFullWidth(window: Dp): Dp = minOf(window - 40.dp, 1268.dp)

/** The floating panel of hue, saturation and brightness, each 0..100% with 50% leaving the layer as it was. */
@Composable
fun HsbPanel(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val (h, s, b) = Triple(vm.hue50, vm.saturation50, vm.brightness50)
    val rows: @Composable (Modifier) -> Unit = { m ->
        HsbRow("Hue", h, Brush.horizontalGradient(HUE_STOPS.map { Color(it) }), { Color.hsv((it * 360f) % 360f, 1f, 1f) }, m) { vm.setHsb(it, s, b) }
        HsbRow("Saturation", s, Brush.horizontalGradient(listOf(Color(0xFF808080), Color(0xFFFF0000))), { lerp(Color(0xFF808080), Color.Red, it) }, m) { vm.setHsb(h, it, b) }
        HsbRow("Brightness", b, Brush.horizontalGradient(listOf(Color.Black, Color.White)), { lerp(Color.Black, Color.White, it) }, m) { vm.setHsb(h, s, it) }
    }
    val window = windowWidth()
    if (sideBySide(window, HsbSideBySide)) {
        // side by side, 301 wide and 13 apart (narrower where the window is)
        Row(
            modifier.offset(y = (-25).dp).size(toolbarWidth(961.dp, window), 95.dp).clip(RoundedCornerShape(33.dp)).background(HsbPanelFill).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) { rows(Modifier.weight(1f)) }
    } else {
        Column(
            modifier.offset(y = (-25).dp).size(toolbarWidth(532.dp, window), 220.dp).clip(RoundedCornerShape(33.dp)).background(HsbPanelFill).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { rows(Modifier.fillMaxWidth()) }
    }
}

private val HUE_STOPS = listOf(0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000)

/** A labelled slider over a coloured track, its knob taking the track's colour where it sits. */
@Composable
private fun HsbRow(label: String, value: Float, track: Brush, knobColor: (Float) -> Color, modifier: Modifier, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Column(modifier.height(55.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(label, color = HsbLabel, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", color = HsbValue, fontSize = 16.sp, maxLines = 1)
        }
        Box(
            Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        change((c.position.x / size.width).coerceIn(0f, 1f))
                        c.consume()
                    }
                }
            }.drawBehind {
                val y = size.height / 2f
                drawLine(track, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), cap = StrokeCap.Round)
                val knob = Size(24.dp.toPx(), 14.dp.toPx())
                val x = (size.width * value).coerceIn(knob.width / 2, size.width - knob.width / 2)
                drawRoundRect(Color(0x55000000), Offset(x - knob.width / 2, y - knob.height / 2 + 0.5.dp.toPx()), knob, CornerRadius(knob.height / 2))
                drawRoundRect(knobColor(value), Offset(x - knob.width / 2, y - knob.height / 2), knob, CornerRadius(knob.height / 2))
            },
        )
    }
}

internal val PanelButton get() = if (Pc.light) Color(0xFFF8F8F8) else Color(0xFF303030)
private val PanelButtonText get() = if (Pc.light) Color(0xFF515151) else Color(0xFFA6A6A6)
private val GridLine = Color(0xFF8A8A8A)

private val BALANCE_PAIRS = listOf(
    Triple("Cyan", "Red", listOf(Color(0xFF00FFFF), Color(0xFF808080), Color(0xFFFF0000))),
    Triple("Magenta", "Green", listOf(Color(0xFFFF00FF), Color(0xFF808080), Color(0xFF00FF00))),
    Triple("Yellow", "Blue", listOf(Color(0xFFFFFF00), Color(0xFF808080), Color(0xFF0000FF))),
)

private val BALANCE_TONES = listOf("Shadows", "Midtones", "Highlights")

/**
 * The colour balance panel: three sliders between opposite colours, for the tone chosen below them
 * (side by side on a wide screen, the tones then in the menu of the "•••" button after them).
 */
@Composable
fun ColorBalancePanel(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val tone = vm.balanceTone
    val window = windowWidth()
    if (sideBySide(window, BalanceSideBySide)) {
        val width = toolbarWidth(903.dp, window)
        Row(
            modifier.offset(y = (-25).dp).size(width, 95.dp).clip(RoundedCornerShape(33.dp)).background(HsbPanelFill).padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(19.dp),
        ) {
            BALANCE_PAIRS.forEachIndexed { k, (left, right, colours) ->
                BalanceRow(left, right, vm.balance[tone * 3 + k], Brush.horizontalGradient(colours), Modifier.width(squeezed(251.dp, 3, 903.dp, width))) { vm.setBalance(k, it) }
            }
            MoreButton("Color Balance") {
                OptionsGroup {
                    BALANCE_TONES.forEachIndexed { i, name ->
                        OptionsChoice(name, tone == i, 40.dp, first = i == 0) { vm.chooseBalanceTone(i) }
                    }
                }
            }
        }
        return
    }
    Column(
        modifier.offset(y = (-25).dp).size(toolbarWidth(532.dp, window), 272.dp).clip(RoundedCornerShape(33.dp)).background(HsbPanelFill).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BALANCE_PAIRS.forEachIndexed { k, (left, right, colours) ->
            val value = vm.balance[tone * 3 + k]
            BalanceRow(left, right, value, Brush.horizontalGradient(colours), Modifier.fillMaxWidth()) { vm.setBalance(k, it) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            BALANCE_TONES.forEachIndexed { i, name ->
                val chosen = tone == i
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (chosen) Pc.Accent else PanelButton).tap { vm.chooseBalanceTone(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name, color = if (chosen) Color.White else PanelButtonText, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A slider between two named colours, its middle leaving the colour as it was. */
@Composable
private fun BalanceRow(left: String, right: String, value: Float, track: Brush, modifier: Modifier, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Column(modifier.height(55.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(left, color = MenuText, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(right, color = HsbValue, fontSize = 16.sp, maxLines = 1)
        }
        Box(
            Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        change((c.position.x / size.width).coerceIn(0f, 1f))
                        c.consume()
                    }
                }
            }.drawBehind {
                val y = size.height / 2f
                drawLine(track, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), cap = StrokeCap.Round)
                val knob = Size(24.dp.toPx(), 14.dp.toPx())
                val x = (size.width * value).coerceIn(knob.width / 2, size.width - knob.width / 2)
                drawRoundRect(Color(0x55000000), Offset(x - knob.width / 2, y - knob.height / 2 + 0.5.dp.toPx()), knob, CornerRadius(knob.height / 2))
                drawRoundRect(Color(0xFF7A7A7A), Offset(x - knob.width / 2, y - knob.height / 2), knob, CornerRadius(knob.height / 2))
            },
        )
    }
}

/**
 * The curves panel: the chosen channel's curve over a grid (drag its points; touch elsewhere to add
 * one; tap a point for Delete over it), and the channels.
 */
@Composable
fun CurvesPanel(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val width = toolbarWidth(584.dp, windowWidth())
    Row(
        modifier.offset(y = (-25).dp).size(width, 183.dp).clip(RoundedCornerShape(33.dp)).background(HsbPanelFill).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (width < 420.dp) 10.dp else 16.dp),
    ) {
        val points = vm.curves[vm.curveChannel]
        // the point whose Delete shows, and the graph's size for placing it
        var tapped by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        var graph by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        Box(
            Modifier.weight(1f).fillMaxHeight().padding(8.dp).onSizeChanged { graph = it }.pointerInput(vm.curveChannel) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    tapped = null
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun toCurve(p: Offset) = Offset((p.x / w).coerceIn(0f, 1f), (1f - p.y / h).coerceIn(0f, 1f))
                    val reach = 22.dp.toPx()
                    val current = vm.curves[vm.curveChannel]
                    var index = current.indexOfFirst { (Offset(it.x * w, (1f - it.y) * h) - down.position).getDistance() <= reach }
                    val added = index < 0
                    if (added) index = vm.addCurvePoint(toCurve(down.position))
                    var moved = false
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (!moved && (c.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                        moved = true
                        index = vm.moveCurvePoint(index, toCurve(c.position))
                        c.consume()
                    }
                    // a tap on a point: Delete over it
                    if (!moved && !added) tapped = index
                }
            }.drawBehind {
                val w = size.width
                val h = size.height
                for (k in 0..4) {
                    drawLine(GridLine, Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1.dp.toPx())
                    drawLine(GridLine, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1.dp.toPx())
                }
                val curve = vm.curveShape(vm.curveChannel)
                for (i in 1 until curve.size) {
                    drawLine(CurveInk, Offset(curve[i - 1].x * w, (1f - curve[i - 1].y) * h), Offset(curve[i].x * w, (1f - curve[i].y) * h), 1.5.dp.toPx())
                }
                for (p in points) node(Offset(p.x * w, (1f - p.y) * h), CurveNode, 1f)
            },
        ) {
            tapped?.let { i -> points.getOrNull(i) }?.let { p ->
                // a pill 78 x 44 over the point, its bottom 14 above it
                val at = with(androidx.compose.ui.platform.LocalDensity.current) {
                    androidx.compose.ui.unit.IntOffset(
                        (p.x * graph.width - 39.dp.toPx()).roundToInt(),
                        ((1f - p.y) * graph.height - (14 + 44).dp.toPx()).roundToInt(),
                    )
                }
                PcPopup(offset = at, onDismissRequest = { tapped = null }, properties = androidx.compose.ui.window.PopupProperties(focusable = true, clippingEnabled = false)) {
                    Box(
                        Modifier.size(78.dp, 44.dp).clip(CircleShape).background(CurveMenuFill).tap {
                            tapped?.let(vm::deleteCurvePoint)
                            tapped = null
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Delete", color = Color.White, fontSize = 17.sp, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.width(111.dp).fillMaxHeight().clip(RoundedCornerShape(13.dp)), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            listOf("Gamma", "Red", "Green", "Blue").forEachIndexed { i, name ->
                val chosen = vm.curveChannel == i
                Box(Modifier.fillMaxWidth().weight(1f).background(if (chosen) Pc.Accent else PanelButton).tap { vm.chooseCurveChannel(i) }, contentAlignment = Alignment.Center) {
                    Text(name, color = if (chosen) Color.White else PanelButtonText, fontSize = 16.sp, maxLines = 1)
                }
            }
        }
    }
}

private val CurveNode = Color(0xFF0171EA)
/** The curve: white, or black with the light interface. */
private val CurveInk get() = if (Pc.light) Color.Black else Color.White
/** The Delete pill over a tapped point. */
private val CurveMenuFill get() = if (Pc.light) Color(0xFFF0F0F0) else Color(0xFF262626)

/**
 * The gradient map panel: the gradient library (a tap chooses one, a tap on the chosen one edits
 * it, a hold offers Delete and Duplicate, "+" makes a new one), or the gradient being edited.
 */
@Composable
fun GradientMapPanel(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val editing = vm.gradientEditing
    // wider in a wide window, showing more of the library
    val window = windowWidth()
    val width = if (sideBySide(window, GradientSideBySide)) toolbarWidth(835.dp, window) else toolbarWidth(544.dp, window)
    Column(modifier.offset(y = (-25).dp).size(width, 159.dp).clip(RoundedCornerShape(33.dp)).background(HsbPanelFill).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing != null) "Gradient Map" else "Gradient Library", color = MenuText, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 14.dp))
            if (editing != null) {
                Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(GradientDone).tap { vm.endGradientEdit() }, contentAlignment = Alignment.Center) {
                    Icon(PcIcons.DoneTick, contentDescription = "Done", tint = Color.White, modifier = Modifier.requiredSize(36.dp))
                }
            } else {
                val disc = androidx.compose.foundation.shape.CircleShape
                Box(Modifier.size(40.dp).clip(disc).background(PlusDisc).border(1.dp, PlusRim, disc).tap { vm.newGradient() }, contentAlignment = Alignment.Center) {
                    Icon(PcIcons.Add, contentDescription = "New gradient", tint = PlusGlyph, modifier = Modifier.requiredSize(34.dp))
                }
            }
        }
        if (editing != null) GradientEditor(vm, editing) else GradientCards(vm)
    }
}

/** The library's cards on a grey band: each dark, its name over a strip of it; the chosen one stands taller. */
@Composable
private fun GradientCards(vm: CanvasViewModel) {
    var held by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(top = 11.dp).height(81.dp).clip(RoundedCornerShape(12.dp)).background(GradientBand)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        vm.gradients.forEachIndexed { i, g ->
            val chosen = vm.gradientIndex == i
            Column(
                Modifier.padding(bottom = 5.5.dp).size(108.dp, if (chosen) 69.dp else 65.dp).clip(RoundedCornerShape(9.dp)).background(GradientCard)
                    .pointerInput(i, chosen) {
                        detectTapGestures(
                            onTap = { if (chosen) vm.editGradient(i) else vm.chooseGradient(i) },
                            onLongPress = { held = i },
                        )
                    },
            ) {
                // a hold: Delete and Duplicate above the card
                LibraryMenu(held == i, androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f), MenuPlace { a, size -> androidx.compose.ui.unit.IntOffset(a.center.x - size.width / 2, a.top - size.height) }, onDismiss = { held = null }, overPanel = true) {
                    MenuItem(PcIcons.MenuTrash, "Delete", Pc.MenuDanger) {
                        held = null
                        vm.deleteGradient(i)
                    }
                    MenuItem(PcIcons.MenuDuplicate, "Duplicate") {
                        held = null
                        vm.duplicateGradient(i)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(g.name, color = if (chosen) Color.White else GradientName, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 11.dp, bottom = 10.dp))
                Box(Modifier.fillMaxWidth().height(13.dp).background(gradientBrush(g)))
            }
        }
    }
}

/** A gradient's colours left to right, over [from]..[to] of the width (0..1). */
private fun gradientBrush(g: GradientPreset, from: Float = 0f, to: Float = 1f): Brush {
    val stops = g.stops.sortedBy { it.first }.map { (p, c) -> (from + p * (to - from)) to Color(c) }
    return if (stops.size >= 2) Brush.horizontalGradient(*stops.toTypedArray()) else androidx.compose.ui.graphics.SolidColor(stops.firstOrNull()?.second ?: Color.Black)
}

private val StopSize = 42.dp
/** Room round a popover shown in a popup of its own, for its shadow. */
private val PopupShadowRoom = 56.dp

/**
 * The gradient being edited: its name to rename, over its bar, with a disc for each stop. A tap on
 * the bar adds a stop; on a stop, its colours open above it. A stop drags along the bar; a hold
 * offers Delete.
 */
@Composable
private fun GradientEditor(vm: CanvasViewModel, index: Int) {
    val g = vm.gradients.getOrNull(index) ?: return
    val stops by rememberUpdatedState(g.stops)
    val density = androidx.compose.ui.platform.LocalDensity.current
    var colouring by androidx.compose.runtime.remember(index) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var holding by androidx.compose.runtime.remember(index) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var barWidth by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    // where the bar is in the window, for what opens over a stop
    var barAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val r = with(density) { StopSize.toPx() / 2f }
    // a stop's centre along the bar, and the stop under a point
    fun xOf(p: Float) = r + p * (barWidth - 2f * r)
    fun positionAt(x: Float) = ((x - r) / (barWidth - 2f * r).coerceAtLeast(1f)).coerceIn(0f, 1f)
    fun stopAt(at: Offset): Int? = stops.indices.filter { kotlin.math.abs(xOf(stops[it].first) - at.x) <= r }.minByOrNull { kotlin.math.abs(xOf(stops[it].first) - at.x) }
    Column(Modifier.padding(top = 11.dp).fillMaxWidth().height(81.dp).clip(RoundedCornerShape(12.dp)).background(EditorCard)) {
        var name by androidx.compose.runtime.remember(index) { androidx.compose.runtime.mutableStateOf(g.name) }
        androidx.compose.foundation.text.BasicTextField(
            name,
            { name = it.take(64); vm.renameGradient(name) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = EditorName, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Pc.Accent),
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp).fillMaxWidth().height(28.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .then(
                    Modifier.onGloballyPositioned {
                        barWidth = it.size.width.toFloat()
                        barAt = it.positionInWindow()
                    },
                )
                .drawBehind { drawRect(gradientBrush(g, r / size.width, 1f - r / size.width)) }
                .pointerInput(index) {
                    detectTapGestures(
                        onTap = { at ->
                            val hit = stopAt(at)
                            if (hit != null) colouring = hit else vm.addGradientStop(positionAt(at.x))
                        },
                        onLongPress = { at -> holding = stopAt(at) },
                    )
                }
                .pointerInput(index) {
                    var dragged: Int? = null
                    detectDragGestures(
                        onDragStart = { at -> dragged = stopAt(at) },
                        onDrag = { change, _ ->
                            dragged?.let {
                                change.consume()
                                vm.moveGradientStop(it, positionAt(change.position.x))
                            }
                        },
                        onDragEnd = { dragged = null },
                        onDragCancel = { dragged = null },
                    )
                },
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val ring = 2.dp.toPx()
                for ((p, c) in stops) {
                    val centre = Offset(xOf(p), size.height / 2f)
                    val fill = Color(c)
                    // the ring stands out from what it rings: white round a dark colour, black round a light one
                    val light = 0.299f * fill.red + 0.587f * fill.green + 0.114f * fill.blue > 0.45f
                    drawCircle(if (light) Color.Black else Color.White, r, centre)
                    drawCircle(fill, r - ring, centre)
                }
            }
            colouring?.let { i ->
                val rgb = (stops.getOrNull(i)?.second ?: 0xFF000000).toInt() and 0xFFFFFF
                val held = androidx.compose.runtime.remember(i) { HeldColor(rgb) { vm.setGradientStopColor(i, it) } }
                // over the stop, its arrow at the stop's top, kept inside the window
                val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
                val width = with(density) { CompactColorsWidth.toPx() }
                val centre = barAt.x + xOf(stops.getOrNull(i)?.first ?: 0f)
                val left = (centre - width / 2f).coerceIn(0f, (window.width - width).coerceAtLeast(0f))
                val tip = barAt.y + with(density) { (22.dp - StopSize / 2).toPx() }
                // room round it for its shadow to fade out in
                val room = with(density) { PopupShadowRoom.toPx() }
                PcPopup(
                    popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: androidx.compose.ui.unit.IntRect,
                            windowSize: androidx.compose.ui.unit.IntSize,
                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                            popupContentSize: androidx.compose.ui.unit.IntSize,
                        ) = androidx.compose.ui.unit.IntOffset((left - room).roundToInt(), (tip + room - popupContentSize.height).roundToInt())
                    },
                    onDismissRequest = { colouring = null },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = true, clippingEnabled = false),
                ) {
                    CompactColors(vm, held, arrowX = with(density) { (centre - left).toDp() }, modifier = Modifier.padding(PopupShadowRoom), side = ArrowSide.BOTTOM)
                }
            }
            holding?.let { i ->
                val stopX = xOf(stops.getOrNull(i)?.first ?: 0f)
                LibraryMenu(
                    true,
                    androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f),
                    MenuPlace { a, size -> androidx.compose.ui.unit.IntOffset(a.left + stopX.roundToInt() - size.width / 2, a.top + with(density) { (22.dp - StopSize / 2).roundToPx() } - size.height) },
                    onDismiss = { holding = null },
                    overPanel = true,
                ) {
                    MenuItem(PcIcons.MenuTrash, "Delete", Pc.MenuDanger) {
                        holding = null
                        vm.deleteGradientStop(i)
                    }
                }
            }
        }
    }
}

private val GradientBand get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF535353)
private val GradientDone get() = Pc.tinted(Color(0xFF0190FF))
private val PlusDisc get() = if (Pc.light) Color(0xFFFBFBFB) else Color(0xFF1B1B1B)
private val PlusRim get() = if (Pc.light) Color(0xFFE1E1E1) else Color(0xFF373737)
private val PlusGlyph get() = if (Pc.light) Color(0xFF595959) else Color(0xFFAAAAAA)
private val EditorCard get() = if (Pc.light) Color(0xFFEEEEEE) else Color(0xFF383838)
private val EditorName get() = if (Pc.light) Color(0xFF3A3A3A) else Color(0xFFC5C5C5)
private val GradientCard get() = if (Pc.light) Color(0xFFEEEEEE) else Color(0xFF282828)
private val GradientName get() = if (Pc.light) Color(0xFF5D5D5D) else Color(0xFFC1C1C1)

/** A gradient to map by: its name and colour stops (position, 0xAARRGGBB), in any order. */
data class GradientPreset(val name: String, val stops: List<Pair<Float, Long>>)

internal val MenuButton get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF3B3B3B)
internal val MenuButtonText get() = if (Pc.light) Color(0xFF414141) else Color(0xFFD8D8D8)
