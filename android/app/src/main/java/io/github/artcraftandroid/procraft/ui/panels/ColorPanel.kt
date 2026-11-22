package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.ColorMode
import kotlin.math.max
import kotlin.math.roundToInt
import io.github.artcraftandroid.procraft.core.ColourModel
import io.github.artcraftandroid.procraft.core.ColourPlace
import io.github.artcraftandroid.procraft.core.ColourTrack
import io.github.artcraftandroid.procraft.core.HueControl
import io.github.artcraftandroid.procraft.core.colourModelHsv
import io.github.artcraftandroid.procraft.core.colourModelSlid
import io.github.artcraftandroid.procraft.core.colourModelSquarePicture
import io.github.artcraftandroid.procraft.core.colourModelSquareSide
import io.github.artcraftandroid.procraft.core.colourModelTracks
import io.github.artcraftandroid.procraft.core.hueAt
import io.github.artcraftandroid.procraft.core.huePosition
import io.github.artcraftandroid.procraft.core.hueStops

private val PanelWidth = 376.dp

/** Screens shorter than this (landscape) get the short colour panel: no history, the rest tighter. */
private val ShortScreen = 900.dp

/**
 * Short, the classic square takes the height left over: the screen less the bar and arrow (56),
 * the header (64), the sliders (108), the palette (142), the tabs (70) and a 25 margin.
 */
private val ShortClassicRest = 465.dp

/**
 * The palettes' list takes the height left over, up to its measured height: the screen less the
 * bar and arrow (56), the header (64), the Compact and Cards switch (44), the tabs (70) and a 25
 * margin.
 */
private val PalettesRest = 259.dp
internal val RingOuter = 182.dp
internal val RingInner = 124.dp
internal val DiscRadius = 105.dp
internal val HarmonyRadius = 149.dp

// The hue controls are not linear in hue: the core keeps the hues measured at evenly spaced
// points along each (the classic slider's 25, the disc's ring's 49), for drawing them
internal val SliderHues = hueStops(HueControl.SLIDER).toFloatArray()
internal val RingHues = hueStops(HueControl.RING).toFloatArray()

internal fun rgbColor(rgb: Int) = CanvasSpace.color(rgb)

/**
 * [colours] all in one colour space (the first that isn't sRGB, if any): a gradient's colours
 * must share one, and canvas colours may be Display P3 beside the panel's own sRGB greys.
 */
internal fun oneSpace(colours: List<Color>): List<Color> {
    val space = colours.firstOrNull { it.colorSpace != androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb }?.colorSpace ?: return colours
    return colours.map { it.convert(space) }
}

internal fun hsvColor(h: Float, s: Float, v: Float) = rgbColor(CanvasViewModel.hsvToRgb(h, s, v))

/** A colour the pickers show and change: the one painted with, or one of its own (a background). */
interface ColorPick {
    val hue: Float
    val saturation: Float
    val brightness: Float
    fun rgb(): Int
    fun setHsv(h: Float, s: Float, v: Float)
    fun setRgb(rgb: Int)
}

/** The colour painted with. */
internal class BrushColor(private val vm: CanvasViewModel) : ColorPick {
    override val hue get() = vm.hue
    override val saturation get() = vm.saturation
    override val brightness get() = vm.brightness
    override fun rgb() = vm.colorRgb()
    override fun setHsv(h: Float, s: Float, v: Float) = vm.setColor(h, s, v)
    override fun setRgb(rgb: Int) = vm.setRgb(rgb)
}

/**
 * A colour of its own, starting at [rgb]; [onChange] hears every change. Greys keep their hue, and
 * a grey opens at [greyHue] (180 degrees, as measured; some pickers open at 0). One picker can edit
 * several colours in turn ([edit]).
 */
class HeldColor(rgb: Int, greyHue: Float = 0.5f, private var onChange: (Int) -> Unit) : ColorPick {
    override var hue by mutableFloatStateOf(greyHue)
        private set
    override var saturation by mutableFloatStateOf(0f)
        private set
    override var brightness by mutableFloatStateOf(0f)
        private set

    init {
        take(rgb)
    }

    private fun take(rgb: Int) {
        val hsv = rgbToHsv(rgb)
        if (hsv[1] > 0f) hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    /**
     * From now on the picker edits [rgb], and [onChange] hears its changes: the same picker for
     * another colour (a grey keeps the hue shown).
     */
    fun edit(rgb: Int, onChange: (Int) -> Unit) {
        this.onChange = onChange
        take(rgb)
    }

    override fun rgb() = hsvToRgb(hue, saturation, brightness)

    override fun setHsv(h: Float, s: Float, v: Float) {
        hue = h.coerceIn(0f, 1f)
        saturation = s.coerceIn(0f, 1f)
        brightness = v.coerceIn(0f, 1f)
        onChange(rgb())
    }

    override fun setRgb(rgb: Int) {
        take(rgb)
        onChange(rgb)
    }
}

/**
 * [c] in the colour model [model] (chosen on the panel's model button; HSB is the panel's own):
 * where it stands there, which [place] keeps while it is still the colour, so a grey or black keeps
 * its hue and saturation. The maths are the shared core's.
 */
internal class ModelColor(val c: ColorPick, val model: ColourModel, private val place: ColourPlace) {
    /** The colour's hue, saturation and third channel in the model (0..1 each). */
    fun coords(): FloatArray = place.coords(model, c.hue, c.saturation, c.brightness, c.rgb().toUInt()).toFloatArray()

    /** The colour set to [coords] in the model. */
    fun set(coords: FloatArray) {
        val hsv = colourModelHsv(model, coords[0], coords[1], coords[2], c.hue)
        c.setHsv(hsv[0], hsv[1], hsv[2])
        place.keep(model, coords[0], coords[1], coords[2], c.rgb().toUInt())
    }
}

/** A slider track's colours, as the core gives them (0xRRGGBB), to paint with. */
internal fun ColourTrack.paints(): List<Color> = colours.map { rgbColor(it.toInt()) }

/**
 * The colour panel: the current and second colours, a picker per mode, the history and the mode
 * tabs. With [background] it sets the canvas's background colour instead, titled so, with a tick
 * that closes it. On a screen less than [ShortScreen] tall it leaves the history out and tightens
 * the pickers to fit.
 */
@Composable
fun ColorPanel(vm: CanvasViewModel, arrowX: Dp, screenHeight: Dp, modifier: Modifier = Modifier, background: Boolean = false) {
    val short = screenHeight < ShortScreen
    val compactSheet = LocalCompactSheet.current
    // short, the disc reaches up into the header's last 7; on compact sheets keep clear of the top 20.dp sheet grabber
    val headerHeight = if (compactSheet) 68.dp else if (short && vm.colorMode == ColorMode.DISC) 57.dp else 64.dp
    val c: ColorPick = if (background) {
        remember { HeldColor(vm.backgroundRgb()) { vm.setBackgroundRgb(it, done = false) } }
    } else {
        remember(vm) { BrushColor(vm) }
    }
    // the disc, Classic and Value work in the colour model chosen (the background's colour with a place of its own)
    val place = if (background) remember { ColourPlace() } else vm.colourPlace
    val m = remember(c, vm.colourModel, place) { ModelColor(c, vm.colourModel, place) }
    // a swatch dragged out and let go over the panel itself fills nothing
    if (!background) androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.dropAvoid = null } }
    // (on the canvas: in the window, less what the interface keeps clear of)
    val corner = LocalClearance.current.corner
    // dragged out by its grabber, it is not seen (nor its blur) until let go, the window floating
    val undocking = !background && vm.colorsUndocking
    val look = LocalPanelLook.current
    ProvidePanelLook(if (undocking) PanelLook(0f, look.scale, look.pivot) else look) {
    Popover(PanelWidth, arrowX, (if (background) modifier else modifier.onGloballyPositioned { vm.dropAvoid = it.boundsInRoot().translate(-corner) }).heightIn(max = screenHeight - Pc.BarHeight - 25.dp).alpha(if (undocking) 0f else 1f)) {
        Box {
            if (background) {
                PanelHeader("Background", start = 20.dp, end = 12.dp, height = headerHeight, chevron = false) {
                    Box(
                        Modifier.padding(top = 19.dp).size(44.dp).clip(CircleShape).background(Pc.Accent).clickable { vm.endBackgroundPick() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(PcIcons.DoneTick, contentDescription = "Done", tint = Color.White, modifier = Modifier.requiredSize(40.dp))
                    }
                }
            } else if (vm.colorMode == ColorMode.PALETTES) {
                PanelHeader("Palettes", start = if (compactSheet) 16.dp else 20.dp, end = if (compactSheet) 14.dp else 17.dp, height = headerHeight, chevron = false) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compactSheet) {
                            FloatColorButton(vm, Modifier.padding(top = 12.dp))
                        }
                        NewPaletteButton(vm)
                    }
                }
            } else PanelHeader("Colors", start = if (compactSheet) 16.dp else 20.dp, end = if (compactSheet) 14.dp else 20.dp, height = headerHeight, chevron = false) {
                Row(
                    Modifier.padding(top = if (compactSheet) 20.dp else 21.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (compactSheet) {
                        FloatColorButton(vm, Modifier.padding(end = 6.dp))
                    }
                    // the colour model, 8 left of the swatches, for the pages that work in one
                    if (showsModelButton(vm.colorMode)) {
                        ColourModelButton(vm)
                        Spacer(Modifier.width(if (compactSheet) 6.dp else 8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(if (compactSheet) 5.dp else 7.dp)) {
                        val swatchW = if (compactSheet) 34.dp else 43.dp
                        val swatch = Modifier.size(swatchW, 25.dp).clip(RoundedCornerShape(6.dp))
                        Box(swatch.background(rgbColor(vm.colorRgb())).border(1.dp, Pc.SwatchEdge, RoundedCornerShape(6.dp)))
                        Box(swatch.background(rgbColor(vm.secondaryRgb)).clickable { vm.swapColors() })
                    }
                }
            }
            if (!LocalCompactSheet.current) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(40.dp, 4.dp).clip(CircleShape).background(Pc.Grabber))
                if (!background) UndockHandle(vm, Modifier.align(Alignment.TopCenter))
            }
            if (vm.colorMode == ColorMode.HARMONY) HarmonyKindButton(vm)
        }
        if (vm.colorMode == ColorMode.PALETTES) {
            Palettes(vm, c, (screenHeight - PalettesRest).coerceIn(40.dp, if (short) 458.dp else 577.dp))
        } else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // on a screen too short for it all the picker scrolls, the tabs staying
            when (vm.colorMode) {
                ColorMode.DISC -> Disc(c, short = short, m = m)
                ColorMode.VALUE -> Value(c, short = short, inks = vm.inks, m = m)
                ColorMode.HARMONY -> Harmony(vm, c, short = short)
                else -> Classic(c, square = if (short) (screenHeight - ShortClassicRest).coerceIn(150.dp, 344.dp) else 344.dp, m = m)
            }
            if (PocketErgonomics.showBothHistoryAndPalette(short, LocalCompactSheet.current)) {
                History(vm, c, compact = LocalCompactSheet.current)
                vm.palettes.getOrNull(vm.defaultPalette)?.let { p ->
                    Box(Modifier.fillMaxWidth().height(if (LocalCompactSheet.current) 34.dp else 44.dp)) {
                        Text(
                            p.name,
                            color = Pc.PanelTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 21.dp, top = if (LocalCompactSheet.current) 9.dp else 13.dp),
                        )
                    }
                    SwatchGrid(vm, c, vm.defaultPalette, p, Modifier.padding(horizontal = 8.dp))
                }
            } else {
                ShortBottom(vm, c)
            }
        }
        ModeTabs(vm)
    }
    }
}

/** How far the grabber is dragged before the panel floats. */
private val UndockDrag = 30.dp
/** The floating window, and where its grabber is held from (its middle). */
private val FloatingWidth = 260.dp
private val FloatingGrab = DpOffset(130.dp, 7.5.dp)
private val FloatingGroup = Color.White.copy(alpha = 0.06f)
private val FloatingField = Color.White.copy(alpha = 0.06f)

/** The panel's grabber: dragged out past [UndockDrag], the panel floats and the window follows. */
@Composable
private fun UndockHandle(vm: CanvasViewModel, modifier: Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // where it is in the interface: in the window, less what the interface keeps clear of
    val corner = LocalClearance.current.corner
    Box(
        modifier.size(150.dp, 30.dp).onGloballyPositioned { origin = it.positionInRoot() - corner }.pointerInput(vm) {
            var total = Offset.Zero
            // (the window's grabber under the finger, at the size the window shows: this panel's
            // density undone, the floating windows' taken)
            val k = Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING) / Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.PANELS)
            val grab = with(density) { Offset(FloatingGrab.x.toPx(), FloatingGrab.y.toPx()) * k }
            fun letGo() {
                if (vm.colorsUndocking) {
                    vm.colorsUndocking = false
                    vm.closePanels()
                }
            }
            detectDragGestures(
                onDragStart = { total = Offset.Zero },
                onDragEnd = { letGo() },
                onDragCancel = { letGo() },
            ) { change, delta ->
                change.consume()
                total += delta
                if (vm.colorsUndocking || total.getDistance() > UndockDrag.toPx()) {
                    vm.colorsUndocking = true
                    vm.floatingColorsAt = origin + change.position - grab
                }
            }
        },
    )
}

/**
 * The colour panel floating: a window of its own, 260 wide, moved by its grabber and closed by its
 * Close (the colour button then opens the panel again). The same colour and mode as the panel; no
 * swatches, history or palette under the pickers, the modes as icons under them. A plugin may show
 * it smaller than the rest of the interface ([Pc.floatingScale]), or as Classic's picker alone
 * ([Pc.floatingPickerOnly]).
 */
@Composable
fun FloatingColors(vm: CanvasViewModel, maxWidth: Dp, maxHeight: Dp) {
    val at = vm.floatingColorsAt ?: return
    val c: ColorPick = remember(vm) { BrushColor(vm) }
    // the disc and Classic in the panel's colour model (Value stays HSB or RGB, as its switch picks)
    val m = remember(c, vm.colourModel) { ModelColor(c, vm.colourModel, vm.colourPlace) }
    val pluginScale = Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING)
    val k = AutoHideUiPolicy.floatingColorScale(maxWidth.value, pluginScale)
    if (Pc.floatingPickerOnly) {
        FloatingPicker(vm, c, m, maxWidth, maxHeight, at)
        return
    }
    val height = if (vm.colorMode == ColorMode.DISC) 351.dp else 390.dp
    // kept on the screen, and clear of the side bar as the panels are
    val (x, y) = floatingPlace(vm, at, FloatingWidth * k, height * k, maxWidth, maxHeight)
    val baseDensity = LocalDensity.current
    // all of it drawn at the floating windows' size, placed in the window's pixels
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * k, baseDensity.fontScale),
    ) {
    Popover(
        FloatingWidth,
        0.dp,
        Modifier.offset { androidx.compose.ui.unit.IntOffset(x, y) }.height(height).pointerInput(Unit) {
            // the canvas under it takes no touch (a move stays unconsumed: the grabber's drag gives up
            // on one consumed before it has gone far enough)
            awaitEachGesture { awaitFirstDown().consume() }
        },
        side = ArrowSide.NONE,
    ) {
        Box(Modifier.fillMaxWidth().height(height - 52.dp)) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 5.5.dp).size(40.dp, 4.dp).clip(CircleShape).background(Pc.Grabber))
            Box(
                Modifier.align(Alignment.TopCenter).size(150.dp, 30.dp).pointerInput(vm) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        vm.floatingColorsAt = (vm.floatingColorsAt ?: Offset.Zero) + delta
                    }
                },
            )
            val title = if (vm.colorMode == ColorMode.PALETTES) "Palettes" else "Colors"
            Box(Modifier.padding(start = 18.dp, top = 4.dp).height(40.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, color = Pc.PanelTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            FloatingClose(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) { vm.floatingColorsAt = null }
            Column(Modifier.padding(top = 44.dp)) {
                when (vm.colorMode) {
                    ColorMode.DISC -> {
                        Spacer(Modifier.height(5.dp))
                        Disc(c, compact = true, m = m)
                    }
                    ColorMode.CLASSIC -> {
                        Spacer(Modifier.height(6.dp))
                        Classic(c, compact = true, m = m)
                    }
                    ColorMode.HARMONY -> Box {
                        HarmonyKindButton(vm, start = 18.dp, top = 0.dp, menuX = 0.dp)
                        Box(Modifier.padding(top = 21.dp)) { Harmony(vm, c, compact = true, radius = 106.dp) }
                    }
                    ColorMode.VALUE -> {
                        Spacer(Modifier.height(4.dp))
                        CompactValue(c, FloatingGroup, FloatingField)
                    }
                    ColorMode.PALETTES -> FloatingPalettes(vm, c)
                }
            }
        }
        CompactTabs(vm.colorMode) { vm.colorMode = it }
    }
    }
}

/**
 * Where the floating window [width] × [height] (as shown) sits, in the window's pixels, for [at]: kept on the screen,
 * and clear of the side bar as the panels are.
 */
@Composable
private fun FloatColorButton(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    Box(
        modifier
            .height(44.dp)
            .clickable {
                val k = AutoHideUiPolicy.floatingColorScale(config.screenWidthDp.toFloat(), Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING))
                val (x, y) = with(density) {
                    AutoHideUiPolicy.initialFloatingOrigin(
                        screenWidthPx = config.screenWidthDp.dp.toPx(),
                        topBarBottomPx = Pc.barShown.toPx(),
                        floatingWidthPx = (FloatingWidth * k).toPx(),
                        marginPx = 12.dp.toPx(),
                        rightHanded = vm.rightHanded,
                    )
                }
                vm.floatingColorsAt = Offset(x, y)
                vm.closePanels()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Pc.ModelButton)
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Float", color = Pc.PanelTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun floatingPlace(vm: CanvasViewModel, at: Offset, width: Dp, height: Dp, maxWidth: Dp, maxHeight: Dp): Pair<Int, Int> {
    val density = LocalDensity.current
    val clear = when {
        !vm.showSideBar -> 0.dp
        vm.sideBarCollapsed -> 44.dp + 8.dp
        else -> Pc.sideBarShown + Pc.PopoverGap
    }
    val least = if (vm.rightHanded) 0.dp else clear
    val most = (maxWidth - width - if (vm.rightHanded) clear else 0.dp).coerceAtLeast(least)
    val x = with(density) { at.x.toDp() }.coerceIn(least, most)
    val y = with(density) { at.y.toDp() }.coerceIn(0.dp, (maxHeight - height).coerceAtLeast(0.dp))
    return with(density) { x.roundToPx() to y.roundToPx() }
}

/** The floating window's top as Classic's picker alone has it: its grabber, and the picker under it. */
private val FloatingPickerTop = 30.dp

/**
 * The floating window as Classic's picker alone, as a plugin asks: its grabber and Close over the picker (the hue
 * slider under it, unless the picker has the hue on a ring of its own), nothing else; at the plugin's scale.
 */
@Composable
private fun FloatingPicker(vm: CanvasViewModel, c: ColorPick, m: ModelColor, maxWidth: Dp, maxHeight: Dp, at: Offset) {
    val k = Pc.partScale(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING)
    val ringed = vm.colourModel == ColourModel.HSB && Pc.picker?.huePoint?.invoke(c.hue) != null
    val height = FloatingPickerTop + 248.dp + (if (ringed) 0.dp else 40.dp) + 6.dp
    val (x, y) = floatingPlace(vm, at, FloatingWidth * k, height * k, maxWidth, maxHeight)
    PartScaled(io.github.artcraftandroid.procraft.core.InterfacePart.FLOATING) {
        Popover(
            FloatingWidth,
            0.dp,
            Modifier.offset { androidx.compose.ui.unit.IntOffset(x, y) }.height(height).pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown().consume() }
            },
            side = ArrowSide.NONE,
        ) {
            Box(Modifier.fillMaxWidth().height(height)) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 5.5.dp).size(40.dp, 4.dp).clip(CircleShape).background(Pc.Grabber))
                Box(
                    Modifier.align(Alignment.TopCenter).size(150.dp, FloatingPickerTop).pointerInput(vm) {
                        detectDragGestures { change, delta ->
                            change.consume()
                            vm.floatingColorsAt = (vm.floatingColorsAt ?: Offset.Zero) + delta
                        }
                    },
                )
                Column(Modifier.padding(top = FloatingPickerTop)) { Classic(c, compact = true, m = m) }
                FloatingClose(Modifier.align(Alignment.TopEnd).padding(top = 0.dp, end = 0.dp)) { vm.floatingColorsAt = null }
            }
        }
    }
}

/** Close: a disc with a cross cut out of it. */
@Composable
internal fun FloatingClose(modifier: Modifier, onClose: () -> Unit) {
    Box(
        modifier.size(40.dp).clickable(onClick = onClose).drawBehind {
            val r = 8.5.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.15f), r)
            val d = 3.2.dp.toPx()
            val ink = Color.Black.copy(alpha = 0.3f)
            val w = 1.3.dp.toPx()
            drawLine(ink, center + Offset(-d, -d), center + Offset(d, d), w)
            drawLine(ink, center + Offset(d, -d), center + Offset(-d, d), w)
        },
    )
}

/** Palettes floating: Compact or Cards, then each palette as a card of swatches six to a row, or as named tiles two to a row. */
@Composable
private fun FloatingPalettes(vm: CanvasViewModel, c: ColorPick) {
    Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp)).background(FloatingGroup)) {
        for ((i, label) in listOf("Compact", "Cards").withIndex()) {
            val on = vm.paletteCards == (i == 1)
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(9.dp)).background(if (on) Pc.Accent else Color.Transparent).clickable { vm.changePaletteCards(i == 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.White else Pc.PanelTitle, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    LazyColumn(
        Modifier.padding(top = 6.dp).fillMaxWidth().height(246.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(vm.palettes) { i, p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FloatingGroup)) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i == vm.defaultPalette) {
                        Box(Modifier.padding(start = 8.5.dp).size(21.dp).clip(CircleShape).background(Pc.Accent), contentAlignment = Alignment.Center) {
                            Icon(PcIcons.Tick, contentDescription = "Default", tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                    Text(
                        p.name,
                        color = Pc.PanelTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = if (i == vm.defaultPalette) 6.dp else 12.dp),
                    )
                    PaletteMenu(vm, i)
                }
                if (vm.paletteCards) {
                    // as measured: the tiles' block with its top corners rounded
                    PaletteTiles(vm, c, i, p, across = 2, modifier = Modifier.clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)))
                } else for (row in 0 until (p.colors.size + 5) / 6) {
                    Row {
                        for (col in 0 until 6) {
                            val rgb = p.colors.getOrNull(row * 6 + col)
                            Box(
                                Modifier.weight(1f).height(40.dp).background(if (rgb != null) rgbColor(rgb) else Pc.EmptySlot)
                                    .then(if (rgb != null) Modifier.clickable { c.setRgb(rgb) } else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CompactWidth = 260.dp
/** How wide the compact colour picker is, for placing it. */
internal val CompactColorsWidth get() = CompactWidth
private val CompactFill = Color(0xFF1E1E1E)
internal val CompactGroup = Color(0xFF292929)
internal val CompactHexField = Color(0xFF343434)

/**
 * The compact colour picker other screens open (Custom Canvas's background): "Colors", a picker
 * per mode and the mode tabs as icons, 260 wide; no swatches, history or palette.
 */
@Composable
fun CompactColors(vm: CanvasViewModel, c: ColorPick, arrowX: Dp, modifier: Modifier = Modifier, side: ArrowSide = ArrowSide.TOP) {
    var mode by remember { mutableStateOf(ColorMode.DISC) }
    Popover(CompactWidth, arrowX, modifier, fill = CompactFill, side = side) {
        // a 16.5 cap, as measured over a gradient stop
        Text("Colors", color = Pc.PanelTitle, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 9.dp, bottom = 5.dp))
        CompactBody(vm, c, mode)
        CompactTabs(mode) { mode = it }
    }
}

/** The compact picker for [mode], 260 wide: the disc, the square, harmony, values or the palettes. */
@Composable
internal fun CompactBody(vm: CanvasViewModel, c: ColorPick, mode: ColorMode) {
    when (mode) {
        ColorMode.DISC -> Disc(c, compact = true)
        ColorMode.VALUE -> Value(c, compact = true)
        ColorMode.HARMONY -> Harmony(vm, c, compact = true)
        ColorMode.PALETTES -> CompactPalettes(vm, c)
        else -> Classic(c, compact = true)
    }
}

/** The compact picker's modes as a row of icons, the chosen one blue. */
@Composable
internal fun CompactTabs(mode: ColorMode, onMode: (ColorMode) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp)) {
        for ((m, icon, label) in Modes) {
            Box(Modifier.weight(1f).fillMaxSize().clickable { onMode(m) }, contentAlignment = Alignment.Center) {
                // the tabs' 36-unit glyphs, a little smaller here
                Icon(icon, contentDescription = label, tint = if (m == mode) Pc.Accent else Pc.PanelTitle, modifier = Modifier.requiredSize(33.dp))
            }
        }
    }
}

/** The palettes' swatches, one grid after another, a tap taking a colour. */
@Composable
private fun CompactPalettes(vm: CanvasViewModel, c: ColorPick) {
    LazyColumn(
        Modifier.fillMaxWidth().height(240.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(vm.palettes) { _, p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                for (row in 0 until 3) {
                    Row {
                        for (col in 0 until 10) {
                            val rgb = p.colors[row * 10 + col]
                            Box(
                                Modifier.weight(1f).height(24.dp).background(if (rgb != null) rgbColor(rgb) else Pc.EmptySlot)
                                    .then(if (rgb != null) Modifier.clickable { c.setRgb(rgb) } else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Classic(c: ColorPick, compact: Boolean = false, square: Dp = 344.dp, m: ModelColor? = null) {
    if (m != null && m.model != ColourModel.HSB) {
        ModelClassic(m, compact, square)
        return
    }
    Box {
        SaturationBrightness(c, compact, square)
        // plugins' menus, from a button in the square's lower left corner (the panel's, not a compact picker's)
        if (!compact) Pc.classicMenu?.let { ClassicMenuButton(it, Modifier.align(Alignment.BottomStart).padding(start = 16.dp)) }
    }
    // a picker plugin's with the hue on a ring of its own takes the hue slider's place
    val ringed = Pc.picker?.huePoint?.invoke(c.hue) != null
    if (compact) {
        // the hue only, 240 wide under the 248 square
        if (!ringed) Box(Modifier.padding(start = 10.dp, end = 10.dp).height(40.dp)) {
            ColorSliderRow(huePosition(HueControl.SLIDER, c.hue), SliderHues.map { hsvColor(it / 360f, 1f, 1f) }) { c.setHsv(hueAt(HueControl.SLIDER, it), c.saturation, c.brightness) }
        }
        return
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        val pure = hsvColor(c.hue, 1f, 1f)
        if (!ringed) ColorSliderRow(huePosition(HueControl.SLIDER, c.hue), SliderHues.map { hsvColor(it / 360f, 1f, 1f) }) { c.setHsv(hueAt(HueControl.SLIDER, it), c.saturation, c.brightness) }
        ColorSliderRow(c.saturation, listOf(Pc.SaturationGrey, pure)) { c.setHsv(c.hue, it, c.brightness) }
        ColorSliderRow(c.brightness, BrightnessTrack) { c.setHsv(c.hue, c.saturation, it) }
    }
}

/** The button for plugins' menus at Classic's corner: three dots on a dark disc, its touch 36 across. */
private val ClassicMenuDisc = Color.Black.copy(alpha = 0.45f)

/**
 * Plugins' menus at Classic's corner: three dots on a dark disc in the square's lower left corner, opening a small
 * menu above it of their choices' icons, the ones that stand now lit; picking one closes it.
 */
@Composable
private fun ClassicMenuButton(menu: ClassicMenu, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.size(36.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(ClassicMenuDisc).drawBehind {
                val r = 1.6.dp.toPx()
                for (i in -1..1) drawCircle(Color.White, r, center + Offset(i * 5.5.dp.toPx(), 0f))
            },
        )
        MenuPresence(open, androidx.compose.ui.graphics.TransformOrigin(0f, 1f)) { look ->
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            PcPopup(Above(gap), properties = PopupProperties(focusable = true), onDismissRequest = { open = false }) {
                Row(
                    look.clip(RoundedCornerShape(17.dp)).background(Pc.GlassMenuOverPanel)
                        .border(1.dp, Pc.GlassMenuEdge, RoundedCornerShape(17.dp)).padding(6.dp),
                ) {
                    for (choice in menu.choices) {
                        val icon = remember(choice.icon) { choiceIcon(choice.icon) }
                        Box(
                            Modifier.size(48.dp, 44.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (choice.chosen) Pc.Accent.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    open = false
                                    menu.choose(choice.key)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = choice.label, tint = if (choice.chosen) Pc.Accent else Pc.MenuInk, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A plugin's menu choice's icon: its path data on the icons' 24-unit grid, filled even-odd, tinted where it shows. */
private fun choiceIcon(d: String): ImageVector =
    ImageVector.Builder(name = "plugin choice", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = androidx.compose.ui.graphics.vector.addPathNodes(d),
            pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
            fill = androidx.compose.ui.graphics.SolidColor(Color.White),
        )
        .build()

/** Puts a popup above the anchor, [gap] over it, its left edge at the anchor's, inside the window. */
private class Above(private val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * Classic in another colour model: its square (saturation across, the model's third channel up),
 * then sliders for its hue, saturation and third; compact, the hue only.
 */
@Composable
private fun ModelClassic(m: ModelColor, compact: Boolean, square: Dp) {
    val coords = m.coords()
    ModelSquare(m, coords, compact, square)
    val tracks = colourModelTracks(m.model, coords[0], coords[1], coords[2])
    if (compact) {
        Box(Modifier.padding(start = 10.dp, end = 10.dp).height(40.dp)) { TrackSlider(m, coords, tracks, 0) }
        return
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        for (channel in 0..2) TrackSlider(m, coords, tracks, channel)
    }
}

/** The slider of channel [channel] (0 the hue) of the colour at [coords] in [m]'s model, its colours [tracks]' (the core's). */
@Composable
private fun TrackSlider(m: ModelColor, coords: FloatArray, tracks: List<ColourTrack>, channel: Int) {
    val track = tracks.getOrNull(channel) ?: return
    ColorSliderRow(track.place, track.paints()) {
        m.set(colourModelSlid(m.model, coords[0], coords[1], coords[2], channel.toUInt(), it).toFloatArray())
    }
}

/**
 * Classic's square in another colour model, as the core draws it: saturation across and the
 * third up, its picture at most the core's few pixels across and drawn up (a soft gradient, and a
 * hue drag draws it every frame).
 */
@Composable
private fun ModelSquare(m: ModelColor, coords: FloatArray, compact: Boolean, height: Dp) {
    val model = m.model
    val now by rememberUpdatedState(coords)
    val pick by rememberUpdatedState(m)
    val width = if (compact) 248.dp else 344.dp * ResponsiveLayout.colorPickerScale(LocalSheetWidth.current)
    val side = colourModelSquareSide(with(LocalDensity.current) { width.roundToPx() }.coerceAtLeast(1).toUInt()).toInt()
    val hue = coords[0]
    val picture = rememberPickerPicture(model to hue, side, CanvasSpace.p3) { n -> colourModelSquarePicture(model, hue, n.toUInt()) }
    val current = rgbColor(m.c.rgb())
    Box(Modifier.fillMaxWidth().padding(horizontal = if (compact) 6.dp else 16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width, if (compact) 248.dp else height).drawWithContent {
                drawContent()
                // the knob may hang over the square's edge
                knob(Offset(coords[1] * size.width, (1f - coords[2]) * size.height), 10.75.dp.toPx(), current)
            },
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .pointerInput(model) { track { x, y -> pick.set(floatArrayOf(now[0], x, 1f - y)) } }
                    .drawBehind {
                        // read, so a new picture draws
                        picture.version
                        drawImage(picture.image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    },
            )
        }
    }
}

/** Square (saturation across, brightness up, both -1..1 with y down) to disc, by the elliptical grid map (the core's). */
internal fun squareToDisc(x: Float, y: Float): Offset =
    io.github.artcraftandroid.procraft.core.squareToDiscPoint(x, y).let { Offset(it[0], it[1]) }

/** The inverse of [squareToDisc]. */
internal fun discToSquare(u: Float, v: Float): Offset =
    io.github.artcraftandroid.procraft.core.discToSquarePoint(u, v).let { Offset(it[0], it[1]) }
