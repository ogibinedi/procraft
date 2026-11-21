package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.unit.DpSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.ColorMode
import io.github.artcraftandroid.procraft.canvas.HarmonyKind
import io.github.artcraftandroid.procraft.gallery.Palette
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import io.github.artcraftandroid.procraft.core.ColourModel
import io.github.artcraftandroid.procraft.core.ColourPlace
import io.github.artcraftandroid.procraft.core.ColourTrack
import io.github.artcraftandroid.procraft.core.HueControl
import io.github.artcraftandroid.procraft.core.colourModelAlong
import io.github.artcraftandroid.procraft.core.colourModelDiscKey
import io.github.artcraftandroid.procraft.core.colourModelDiscPick
import io.github.artcraftandroid.procraft.core.colourModelDiscPicture
import io.github.artcraftandroid.procraft.core.colourModelDiscPoint
import io.github.artcraftandroid.procraft.core.colourModelHsv
import io.github.artcraftandroid.procraft.core.colourModelLabel
import io.github.artcraftandroid.procraft.core.colourModelLetters
import io.github.artcraftandroid.procraft.core.colourModelPure
import io.github.artcraftandroid.procraft.core.colourModelRing
import io.github.artcraftandroid.procraft.core.colourModelRingHue
import io.github.artcraftandroid.procraft.core.colourModelRingTurn
import io.github.artcraftandroid.procraft.core.colourModelSlid
import io.github.artcraftandroid.procraft.core.colourModelSquarePicture
import io.github.artcraftandroid.procraft.core.colourModelSquareSide
import io.github.artcraftandroid.procraft.core.colourModelTracks
import io.github.artcraftandroid.procraft.core.colourModels
import io.github.artcraftandroid.procraft.core.hueAt
import io.github.artcraftandroid.procraft.core.huePosition
import io.github.artcraftandroid.procraft.core.hueStops
import io.github.artcraftandroid.procraft.core.oklabOfHsv
import io.github.artcraftandroid.procraft.core.oklabValueSlid
import io.github.artcraftandroid.procraft.core.oklabValueTracks


/** A plain name for a colour (0xRRGGBB), as the shared core gives it. */
internal fun colourName(rgb: Int): String = io.github.artcraftandroid.procraft.core.colourName(rgb.toUInt() and 0xFFFFFFu)

/** A CMYK canvas's inks (cyan, magenta, yellow, black; 0..1) for a canvas colour, and back. */
class Inks(val of: (Int) -> FloatArray, val rgb: (FloatArray) -> Int)

/**
 * Number sliders: hue, saturation and brightness (or the colour model's three), then red, green
 * and blue (on a CMYK canvas: cyan, magenta, yellow and black, the groups closer together), then
 * the hex code.
 */
@Composable
internal fun Value(c: ColorPick, compact: Boolean = false, short: Boolean = false, inks: Inks? = null, m: ModelColor? = null) {
    val rgb = c.rgb()
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    if (compact) {
        CompactValue(c)
        return
    }
    if (inks != null) {
        InkValue(c, inks, m)
        return
    }
    Column(Modifier.padding(horizontal = 8.dp)) {
        ValueGroup(short = short) { ModelRows(c, m) }
        ValueGroup(Modifier.padding(top = 6.dp), short) {
            ValueRow("R", r / 255f, listOf(Color(0, g, b), Color(255, g, b)), "$r") { c.setRgb(((it * 255f).roundToInt() shl 16) or (g shl 8) or b) }
            ValueRow("G", g / 255f, listOf(Color(r, 0, b), Color(r, 255, b)), "$g") { c.setRgb((r shl 16) or ((it * 255f).roundToInt() shl 8) or b) }
            ValueRow("B", b / 255f, listOf(Color(r, g, 0), Color(r, g, 255)), "$b") { c.setRgb((r shl 16) or (g shl 8) or (it * 255f).roundToInt()) }
        }
        HexRow(rgb) { c.setRgb(it) }
        if (short) Spacer(Modifier.height(2.dp))
    }
}

/**
 * Value's first three sliders: hue, saturation and brightness, or the colour model's (OKLab's: its
 * own lightness, a and b, as the core works them out).
 */
@Composable
private fun ModelRows(c: ColorPick, m: ModelColor?) {
    if (m == null || m.model == ColourModel.HSB) {
        ValueRow("H", huePosition(HueControl.SLIDER, c.hue), SliderHues.map { hsvColor(it / 360f, 1f, 1f) }, "${(c.hue * 360f).roundToInt()}°") {
            c.setHsv(hueAt(HueControl.SLIDER, it), c.saturation, c.brightness)
        }
        ValueRow("S", c.saturation, listOf(Pc.SaturationGrey, hsvColor(c.hue, 1f, 1f)), "${(c.saturation * 100f).roundToInt()}%") {
            c.setHsv(c.hue, it, c.brightness)
        }
        ValueRow("B", c.brightness, BrightnessTrack, "${(c.brightness * 100f).roundToInt()}%") {
            c.setHsv(c.hue, c.saturation, it)
        }
        return
    }
    if (m.model == ColourModel.OKLAB) {
        // a and b run ±0.4 across their sliders
        val lab = oklabOfHsv(c.hue, c.saturation, c.brightness)
        val tracks = oklabValueTracks(lab[0], lab[1], lab[2])
        for ((i, letter) in listOf("L", "a", "b").withIndex()) {
            val track = tracks.getOrNull(i) ?: continue
            val shown = if (i == 0) "${(lab[0] * 100f).roundToInt()}" else signedShown(lab[i])
            ValueRow(letter, track.place, track.paints(), shown) {
                val hsv = oklabValueSlid(lab[0], lab[1], lab[2], i.toUInt(), it, c.hue)
                c.setHsv(hsv[0], hsv[1], hsv[2])
            }
        }
        return
    }
    val coords = m.coords()
    val tracks = colourModelTracks(m.model, coords[0], coords[1], coords[2])
    val letters = colourModelLetters(m.model)
    for (i in 0..2) {
        val track = tracks.getOrNull(i) ?: continue
        ValueRow(letters.getOrElse(i) { "" }, track.place, track.paints(), if (i == 0) degreesShown(coords[0]) else percentShown(coords[i])) {
            m.set(colourModelSlid(m.model, coords[0], coords[1], coords[2], i.toUInt(), it).toFloatArray())
        }
    }
}

/** Value on a CMYK canvas: the hue, saturation and brightness (or the colour model's three), the four inks, then the hex code. */
@Composable
private fun InkValue(c: ColorPick, inks: Inks, m: ModelColor?) {
    val rgb = c.rgb()
    // the inks dragged to, kept while the colour they make is the colour (not worked out again
    // from it, which could move the other inks)
    var held by remember { mutableStateOf<Pair<Int, FloatArray>?>(null) }
    val worked = remember(rgb) { inks.of(rgb) }
    val now = held?.takeIf { it.first == rgb }?.second ?: worked
    fun inksWith(k: Int, v: Float) = now.copyOf().also { it[k] = v.coerceIn(0f, 1f) }
    Column(Modifier.padding(horizontal = 8.dp)) {
        ValueGroup(height = 142.dp, gap = 4.dp, top = 1.dp) { ModelRows(c, m) }
        ValueGroup(Modifier.padding(top = 8.dp), height = 187.dp, gap = 3.dp, top = 1.dp) {
            for ((k, letter) in listOf("C", "M", "Y", "K").withIndex()) {
                val v = now.getOrElse(k) { 0f }
                ValueRow(letter, v, listOf(rgbColor(inks.rgb(inksWith(k, 0f))), rgbColor(inks.rgb(inksWith(k, 1f)))), "${(v * 100f).roundToInt()}%") {
                    val next = inksWith(k, it)
                    val made = inks.rgb(next)
                    held = made to next
                    c.setRgb(made)
                }
            }
        }
        HexRow(rgb, top = 8.dp, height = 42.dp) { c.setRgb(it) }
    }
}

/** Value, compact: HSB or RGB (a switch picks), then the hex code; 240 wide. */
@Composable
internal fun CompactValue(c: ColorPick, group: Color = CompactGroup, field: Color = CompactHexField) {
    var rgbShown by remember { mutableStateOf(false) }
    val rgb = c.rgb()
    val r = (rgb shr 16) and 0xFF
    val g = (rgb shr 8) and 0xFF
    val b = rgb and 0xFF
    Column(Modifier.padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp)).background(group)) {
            for ((i, label) in listOf("HSB", "RGB").withIndex()) {
                val on = rgbShown == (i == 1)
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(9.dp)).background(if (on) Pc.Accent else Color.Transparent).clickable { rgbShown = i == 1 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) Color.White else Pc.PanelTitle, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Column(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(188.dp).clip(RoundedCornerShape(10.dp)).background(group).padding(start = 12.dp, end = 12.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (rgbShown) {
                CompactValueRow("R", r / 255f, listOf(Color(0, g, b), Color(255, g, b)), "$r") { c.setRgb(((it * 255f).roundToInt() shl 16) or (g shl 8) or b) }
                CompactValueRow("G", g / 255f, listOf(Color(r, 0, b), Color(r, 255, b)), "$g") { c.setRgb((r shl 16) or ((it * 255f).roundToInt() shl 8) or b) }
                CompactValueRow("B", b / 255f, listOf(Color(r, g, 0), Color(r, g, 255)), "$b") { c.setRgb((r shl 16) or (g shl 8) or (it * 255f).roundToInt()) }
            } else {
                CompactValueRow("H", huePosition(HueControl.SLIDER, c.hue), SliderHues.map { hsvColor(it / 360f, 1f, 1f) }, "${(c.hue * 360f).roundToInt()}°") {
                    c.setHsv(hueAt(HueControl.SLIDER, it), c.saturation, c.brightness)
                }
                CompactValueRow("S", c.saturation, listOf(Pc.SaturationGrey, hsvColor(c.hue, 1f, 1f)), "${(c.saturation * 100f).roundToInt()}%") {
                    c.setHsv(c.hue, it, c.brightness)
                }
                CompactValueRow("B", c.brightness, BrightnessTrack, "${(c.brightness * 100f).roundToInt()}%") {
                    c.setHsv(c.hue, c.saturation, it)
                }
            }
        }
        HexRow(rgb, group, field) { c.setRgb(it) }
        Spacer(Modifier.height(10.dp))
    }
}

/** One compact number slider, 216 wide: its letter, the track and the value. */
@Composable
private fun CompactValueRow(letter: String, value: Float, colours: List<Color>, shown: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(letter, color = Pc.PanelTitle, fontSize = 15.sp, modifier = Modifier.width(20.dp))
        Box(Modifier.weight(1f)) { ColorSliderRow(value, colours, onChange) }
        Text(shown, color = Pc.PanelTitle, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun ValueGroup(modifier: Modifier = Modifier, short: Boolean = false, rows: @Composable () -> Unit) {
    // short, the rows are 51 apart instead of 53
    if (short) ValueGroup(modifier, 151.dp, 7.dp, 3.dp, rows) else ValueGroup(modifier, 167.dp, 9.dp, 9.dp, rows)
}

/** A group of number sliders [height] tall, [gap] between rows, [top] above the first. */
@Composable
private fun ValueGroup(modifier: Modifier = Modifier, height: Dp, gap: Dp, top: Dp, rows: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(Pc.ValueGroup).padding(top = top),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) { rows() }
}

/** One number slider: its letter, a track from 32 to 296 dp, and the value centred at 326.5. */
@Composable
private fun ValueRow(letter: String, value: Float, colours: List<Color>, shown: String, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 13.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(letter, color = Pc.PanelTitle, fontSize = 15.sp, modifier = Modifier.width(19.dp))
        Box(Modifier.weight(1f)) {
            ColorSliderRow(value, colours, onChange)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(45.dp), contentAlignment = Alignment.Center) {
            Text(shown, color = Pc.PanelTitle, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/**
 * The colour as a hex code: a tap opens the hex keypad beside the panel, and the code typed on it
 * (six digits) sets the colour.
 */
@Composable
private fun HexRow(rgb: Int, fill: Color = Pc.HexRow, field: Color = Pc.HexField, top: Dp = 6.dp, height: Dp = 44.dp, onSet: (Int) -> Unit) {
    // the digits typed since the keypad opened; null while it is shut
    var typed by remember { mutableStateOf<String?>(null) }
    Box(Modifier.padding(top = top).fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(fill)) {
        Text("Hexadecimal", color = Pc.PanelTitle, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 13.dp))
        val editing = typed != null
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(84.5.dp, 28.dp).clip(RoundedCornerShape(7.dp)).background(field)
                .then(if (editing) Modifier.border(1.dp, Pc.Accent, RoundedCornerShape(7.dp)) else Modifier)
                .clickable { typed = "" },
            contentAlignment = Alignment.Center,
        ) {
            Text("#" + (typed?.takeIf { it.isNotEmpty() } ?: "%06X".format(rgb)), color = Pc.PanelTitle, fontSize = 14.sp, maxLines = 1)
        }
        if (editing) {
            // beside the panel, its middle level with this row
            val gap = with(LocalDensity.current) { HexPadGap.roundToPx() }
            PcPopup(popupPositionProvider = remember(gap) { BesidePanel(gap) }, onDismissRequest = { typed = null }, properties = PopupProperties(focusable = true)) {
                HexPad(
                    onKey = { c ->
                        // a seventh digit starts a new code
                        val next = ((typed ?: "").let { if (it.length >= 6) "" else it } + c)
                        typed = next
                        if (next.length == 6) next.toIntOrNull(16)?.let(onSet)
                    },
                    onBack = { typed = typed?.dropLast(1) },
                )
            }
        }
    }
}

/** From the hex row to the keypad's right edge: the panel's padding, then a small gap. */
private val HexPadGap = 12.dp

/** Puts a popup to the left of the anchor (or above/below it on narrow smartphone screens), inside the window. */
private class BesidePanel(private val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val leftX = anchorBounds.left - gap - popupContentSize.width
        if (leftX >= 0) {
            val y = (anchorBounds.center.y - popupContentSize.height / 2).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
            return IntOffset(leftX, y)
        }
        val centeredX = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
        val aboveY = anchorBounds.top - gap - popupContentSize.height
        val y = if (aboveY >= 0) aboveY else (anchorBounds.bottom + gap).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(centeredX, y)
    }
}

/** The hex keypad: D E F, A B C, 7 8 9, 4 5 6, 1 2 3, then a wide 0 and backspace; 259 × 340. */
@Composable
private fun HexPad(onKey: (Char) -> Unit, onBack: () -> Unit) {
    val rows = listOf("DEF", "ABC", "789", "456", "123")
    Column(
        Modifier.size(259.dp, 340.dp).clip(RoundedCornerShape(30.dp)).background(HexPadFill).border(1.dp, HexPadEdge, RoundedCornerShape(30.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in row) HexKey(Modifier.width(77.dp), { onKey(c) }) { Text("$c", color = HexKeyInk, fontSize = 21.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HexKey(Modifier.width(158.dp), { onKey('0') }) { Text("0", color = HexKeyInk, fontSize = 21.sp) }
            HexKey(Modifier.width(77.dp), onBack) {
                Icon(PcIcons.HexBackspace, contentDescription = "Delete", tint = HexBackInk, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** One key of the hex keypad, 50 tall, lighter while held. */
@Composable
private fun HexKey(modifier: Modifier, onClick: () -> Unit, label: @Composable () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val held by press.collectIsPressedAsState()
    Box(
        modifier.height(50.dp).clip(RoundedCornerShape(21.dp)).background(if (held) HexKeyHeld else HexKeyFill)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { label() }
}

private val HexPadFill = Color(0xFF616161)
private val HexPadEdge = Color(0xFF262626)
private val HexKeyFill = Color(0xFF6C6C6C)
private val HexKeyHeld = Color(0xFF8A8A8A)
private val HexKeyInk = Color(0xFFEEEEEE)
private val HexBackInk = Color(0xFFBABABA)

/** The colour at [t] (0..1) along evenly spaced [colours]. */
internal fun colourAlong(colours: List<Color>, t: Float): Color {
    val p = t.coerceIn(0f, 1f) * (colours.size - 1)
    val i = p.toInt().coerceAtMost(colours.size - 2)
    return lerp(colours[i], colours[i + 1], p - i)
}

@Composable
internal fun History(vm: CanvasViewModel, c: ColorPick) {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Text("History", color = Pc.PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 21.dp, top = 13.dp))
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 16.dp).size(86.dp, 22.5.dp).clip(RoundedCornerShape(7.dp))
                .background(Pc.SetCell).clickable { vm.clearHistory() },
            contentAlignment = Alignment.Center,
        ) {
            Text("Clear", color = Pc.ClearText, fontSize = 13.sp)
        }
    }
    HistoryStrip(vm, c)
}

/** The last colours painted with, ten 36 swatches; a tap takes one. */
@Composable
private fun HistoryStrip(vm: CanvasViewModel, c: ColorPick) {
    Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))) {
        for (i in 0 until CanvasViewModel.HISTORY_SIZE) {
            val rgb = vm.history.getOrNull(i)
            Box(
                Modifier.weight(1f).height(36.dp)
                    .background(if (rgb != null) rgbColor(rgb) else Pc.EmptySwatch)
                    .then(if (rgb != null) Modifier.clickable { c.setRgb(rgb) } else Modifier),
            )
        }
    }
}

/**
 * The short panel's last block: the colour history (a 44 header and the strip) or the active
 * palette (a 34 header and its grid), whichever its "•••" menu picked.
 */
@Composable
internal fun ShortBottom(vm: CanvasViewModel, c: ColorPick) {
    val palette = vm.palettes.getOrNull(vm.defaultPalette)
    val history = vm.colorHistoryShown || palette == null
    Box(Modifier.fillMaxWidth().height(if (history) 44.dp else 34.dp)) {
        Text(
            if (history) "History" else palette?.name.orEmpty(),
            color = Pc.PanelTitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(start = 21.dp, top = if (history) 13.dp else 8.dp),
        )
        ShortBottomMenu(vm, history, Modifier.align(Alignment.CenterEnd))
    }
    if (history) {
        HistoryStrip(vm, c)
    } else if (palette != null) {
        SwatchGrid(vm, c, vm.defaultPalette, palette, Modifier.padding(horizontal = 8.dp))
    }
}

/**
 * Three dots 31 from the panel's right edge, opening a small menu under them: Color history or
 * Active palette (a radio each), and with the history shown, Clear history.
 */
@Composable
private fun ShortBottomMenu(vm: CanvasViewModel, history: Boolean, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier.padding(end = 9.dp).size(44.dp, 34.dp).clickable { open = true }.drawBehind {
            val r = 1.8.dp.toPx()
            for (i in -1..1) drawCircle(Pc.PanelTitle, r, center + Offset(i * 6.5.dp.toPx(), 0f))
        },
    ) {
        MenuPresence(open, androidx.compose.ui.graphics.TransformOrigin(1f, 0f)) { look ->
            // its right edge 16 left of the dots, its top 15 below them
            PcPopup(textScale = MATCHED_POPUP_TEXT, offset = with(LocalDensity.current) { IntOffset((-246).dp.roundToPx(), 32.dp.roundToPx()) }, properties = PopupProperties(focusable = true), onDismissRequest = { open = false }) {
                Column(
                    look.width(252.dp).clip(RoundedCornerShape(17.dp)).background(Pc.GlassMenuOverPanel)
                        .border(1.dp, Pc.GlassMenuEdge, RoundedCornerShape(17.dp)).padding(top = 6.dp, bottom = if (history) 9.5.dp else 7.dp),
                ) {
                    for ((label, on) in listOf("Color history" to true, "Active palette" to false)) {
                        Row(
                            Modifier.fillMaxWidth().height(40.dp).clickable {
                                vm.changeColorHistoryShown(on)
                                open = false
                            }.padding(start = 20.dp, end = 19.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, color = Pc.MenuInk, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Radio(on == history)
                        }
                    }
                    if (history) {
                        Box(Modifier.padding(start = 16.dp, end = 15.dp, top = 11.dp, bottom = 10.dp).fillMaxWidth().height(1.dp).background(Pc.GlassMenuEdge))
                        Row(
                            Modifier.fillMaxWidth().height(40.dp).clickable {
                                vm.clearHistory()
                                open = false
                            }.padding(start = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // a 41 frame centred 29.5 in
                            Icon(PcIcons.Sweep, contentDescription = null, tint = Pc.MenuDanger, modifier = Modifier.requiredSize(41.dp))
                            Text("Clear history", color = Pc.MenuDanger, fontSize = 18.sp, modifier = Modifier.padding(start = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A round choice mark, 18 across: a grey ring, or filled blue with a white tick when chosen. */
@Composable
internal fun Radio(chosen: Boolean) {
    Box(
        Modifier.size(18.dp).clip(CircleShape).then(if (chosen) Modifier.background(Pc.Accent) else Modifier.border(1.5.dp, Pc.RadioRing, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Icon(PcIcons.Tick, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

internal val Modes = listOf(
    Triple(ColorMode.DISC, PcIcons.ModeDisc, "Disc"),
    Triple(ColorMode.CLASSIC, PcIcons.ModeClassic, "Classic"),
    Triple(ColorMode.HARMONY, PcIcons.ModeHarmony, "Harmony"),
    Triple(ColorMode.VALUE, PcIcons.ModeValue, "Value"),
    Triple(ColorMode.PALETTES, PcIcons.ModePalettes, "Palettes"),
)

@Composable
internal fun ModeTabs(vm: CanvasViewModel) {
    Row(Modifier.fillMaxWidth().height(70.dp)) {
        for ((mode, icon, label) in Modes) {
            ModeTab(icon, label, selected = vm.colorMode == mode, Modifier.weight(1f)) { vm.colorMode = mode }
        }
    }
}

@Composable
private fun ModeTab(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // the chosen tab lights up as it is let go; held, its icon shrinks
    val press = rememberPressScale(Motion.TAB_PRESSED, Motion.highlightOn(), Motion.highlightOff())
    Box(modifier.fillMaxSize().then(press.touch).clickable(onClick = onClick)) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Pc.Accent else Pc.PanelTitle,
            // a 36 frame centred 27 down
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 9.dp).requiredSize(36.dp).scaledBy(press),
        )
        Text(
            label,
            // the chosen one white, or black with the light interface
            color = if (!selected) Pc.PanelTitle else if (Pc.light) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 41.dp),
        )
    }
}

/** Calls [onMove] with the touch position as 0..1 fractions of the area while it is held. */
internal suspend fun PointerInputScope.track(onMove: (Float, Float) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        fun send(p: Offset) = onMove((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
        send(down.position)
        while (true) {
            val event = awaitPointerEvent()
            val c = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            send(c.position)
            c.consume()
        }
    }
}
