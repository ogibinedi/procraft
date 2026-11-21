package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.HarmonyKind
import io.github.artcraftandroid.procraft.gallery.Palette
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import io.github.artcraftandroid.procraft.core.ColourModel
import io.github.artcraftandroid.procraft.core.HueControl
import io.github.artcraftandroid.procraft.core.colourModelAlong
import io.github.artcraftandroid.procraft.core.colourModelDiscKey
import io.github.artcraftandroid.procraft.core.colourModelDiscPick
import io.github.artcraftandroid.procraft.core.colourModelDiscPicture
import io.github.artcraftandroid.procraft.core.colourModelDiscPoint
import io.github.artcraftandroid.procraft.core.colourModelLabel
import io.github.artcraftandroid.procraft.core.colourModelLetters
import io.github.artcraftandroid.procraft.core.colourModelPure
import io.github.artcraftandroid.procraft.core.colourModelRing
import io.github.artcraftandroid.procraft.core.colourModelRingHue
import io.github.artcraftandroid.procraft.core.colourModelRingTurn
import io.github.artcraftandroid.procraft.core.colourModels
import io.github.artcraftandroid.procraft.core.hueAt
import io.github.artcraftandroid.procraft.core.huePosition

/**
 * A picker's picture, [side] pixels square, kept in one Bitmap for its size: the first made at
 * once, each after it off the main thread, so a drag never waits on the core making it. Read
 * [version] where it is drawn, so a new one draws.
 */
internal class PickerPicture(side: Int) {
    val bitmap: android.graphics.Bitmap =
        CanvasSpace.tag(android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888))
    val image: ImageBitmap = bitmap.asImageBitmap()
    var version by mutableIntStateOf(0)

    fun fill(rgba: ByteArray) {
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
        version++
    }
}

/**
 * The picture [make] draws for [key] at [side] pixels, as [PickerPicture] keeps it. While a drag
 * moves the key faster than a slow device makes pictures, each one made is shown and the next is
 * for the key as it is by then: one dropped whenever the key moved on would leave the picture as
 * it was until the drag stops.
 */
@Composable
internal fun rememberPickerPicture(key: Any, side: Int, p3: Boolean, make: (Int) -> ByteArray): PickerPicture {
    val n = side.coerceAtLeast(1)
    val picture = remember(n, p3) { PickerPicture(n).also { it.fill(make(n)) } }
    val latestKey by rememberUpdatedState(key)
    val latestMake by rememberUpdatedState(make)
    LaunchedEffect(picture) {
        // the first was made with the picture itself
        var made: Any = latestKey
        snapshotFlow { latestKey }.conflate().collect {
            val k = latestKey
            val makeNow = latestMake
            if (k == made) return@collect
            val rgba = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { makeNow(n) }
            made = k
            picture.fill(rgba)
        }
    }
    return picture
}

/**
 * The disc picker, 364 square: a hue ring (radii 124 to 182, the hue scale running anticlockwise
 * from the right) around the saturation/brightness disc (radius 105).
 */
@Composable
internal fun Disc(c: ColorPick, compact: Boolean = false, short: Boolean = false, m: ModelColor? = null) {
    if (m != null && m.model != ColourModel.HSB) {
        ModelDisc(m, compact, short)
        return
    }
    val hue by rememberUpdatedState(c.hue)
    val sat by rememberUpdatedState(c.saturation)
    val bri by rememberUpdatedState(c.brightness)
    // the colour a touch sets: the one passed now (another may come in mid-way, as the onion
    // skins' second colour does)
    val pick by rememberUpdatedState(c)
    val density = LocalDensity.current
    val sheetScale = ResponsiveLayout.colorPickerScale(LocalSheetWidth.current)
    // compact, the whole picker is 240 across instead of 364
    val k = if (compact) 240f / 364f else sheetScale
    val discRadius = DiscRadius * k
    val ringInner = RingInner * k
    val ringOuter = RingOuter * k
    // the saturation/brightness disc for the hue, with a soft edge, as the core draws it
    val disc = rememberPickerPicture(c.hue, with(density) { (discRadius * 2).roundToPx() }, CanvasSpace.p3) { n ->
        io.github.artcraftandroid.procraft.core.discPicture(c.hue, n.toUInt())
    }
    val ring = remember {
        val stops = Array(RingHues.size) { i -> (1f - i / (RingHues.size - 1f)) to hsvColor(RingHues[i] / 360f, 1f, 1f) }
        Brush.sweepGradient(*stops.reversedArray())
    }
    Box(
        Modifier.fillMaxWidth().then(
            when {
                compact -> Modifier.padding(top = 4.dp, bottom = 6.dp)
                short -> Modifier.padding(bottom = 3.dp)
                else -> Modifier.padding(top = 8.dp, bottom = 17.dp)
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
    Box(
        Modifier.size(364.dp * k)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val onRing = (down.position - centre).getDistance() > ((discRadius + ringInner) / 2).toPx()
                    fun send(p: Offset) {
                        val d = p - centre
                        if (onRing) {
                            val turn = (atan2(-d.y, d.x) / (2f * PI.toFloat()) + 1f) % 1f
                            pick.setHsv(hueAt(HueControl.RING, turn), sat, bri)
                        } else {
                            val r = discRadius.toPx()
                            var uv = Offset(d.x / r, d.y / r)
                            val len = uv.getDistance()
                            if (len > 1f) uv = Offset(uv.x / len, uv.y / len)
                            val sq = discToSquare(uv.x, uv.y)
                            pick.setHsv(hue, (sq.x + 1f) / 2f, (1f - sq.y) / 2f)
                        }
                    }
                    send(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        send(ch.position)
                        ch.consume()
                    }
                }
            }
            .drawBehind {
                val o = center
                val inner = ringInner.toPx()
                val outer = ringOuter.toPx()
                drawCircle(ring, (inner + outer) / 2f, o, style = Stroke(outer - inner))
                val r = discRadius.toPx()
                disc.version
                drawImage(disc.image, topLeft = Offset(o.x - r, o.y - r))
                // the ring knob sits on the middle of the ring, where the hue is
                val turn = huePosition(HueControl.RING, c.hue) * 2f * PI.toFloat()
                val mid = (inner + outer) / 2f
                knob(o + Offset(cos(turn) * mid, -sin(turn) * mid), 26.2.dp.toPx() * k, hsvColor(c.hue, 1f, 1f))
                val p = squareToDisc(c.saturation * 2f - 1f, 1f - c.brightness * 2f)
                knob(o + Offset(p.x * r, p.y * r), 10.75.dp.toPx(), rgbColor(c.rgb()))
            },
    )
    }
}

/**
 * The disc picker in another colour model, as the core works it out: the ring's hues as the model
 * shows them at their purest (evenly spaced in OKLab, else as HSB's ring), round its disc
 * (saturation across and the third up, or HSY′'s wheel at the colour's linear luma). In the panel,
 * HSY′'s saturation and luma follow on sliders under it.
 */
@Composable
private fun ModelDisc(m: ModelColor, compact: Boolean, short: Boolean) {
    val model = m.model
    val coords = m.coords()
    val now by rememberUpdatedState(coords)
    val pick by rememberUpdatedState(m)
    val density = LocalDensity.current
    val sheetScale = ResponsiveLayout.colorPickerScale(LocalSheetWidth.current)
    // compact, the whole picker is 240 across instead of 364
    val k = if (compact) 240f / 364f else sheetScale
    val discRadius = DiscRadius * k
    val ringInner = RingInner * k
    val ringOuter = RingOuter * k
    val (h, s, t) = Triple(coords[0], coords[1], coords[2])
    val disc = rememberPickerPicture(model to colourModelDiscKey(model, h, s, t), with(density) { (discRadius * 2).roundToPx() }, CanvasSpace.p3) { n ->
        colourModelDiscPicture(model, h, s, t, n.toUInt())
    }
    val ring = remember(model) {
        val colours = colourModelRing(model, RingHues.size.toUInt())
        val stops = Array(colours.size) { i -> (1f - i / (colours.size - 1f)) to rgbColor(colours[i].toInt()) }
        Brush.sweepGradient(*stops.reversedArray())
    }
    val turn = colourModelRingTurn(model, h) * 2f * PI.toFloat()
    val pure = rgbColor(colourModelPure(model, h).toInt())
    val at = colourModelDiscPoint(model, h, s, t)
    val current = rgbColor(m.c.rgb())
    val luma = model == ColourModel.HSY && !compact
    Box(
        Modifier.fillMaxWidth().then(
            when {
                compact -> Modifier.padding(top = 4.dp, bottom = 6.dp)
                // HSY′'s sliders start 4 under the picker
                short -> Modifier.padding(bottom = if (luma) 4.dp else 3.dp)
                else -> Modifier.padding(top = 8.dp, bottom = if (luma) 4.dp else 17.dp)
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(364.dp * k)
                .pointerInput(model) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val onRing = (down.position - centre).getDistance() > ((discRadius + ringInner) / 2).toPx()
                        fun send(p: Offset) {
                            val d = p - centre
                            val was = now
                            if (onRing) {
                                val turnAt = (atan2(-d.y, d.x) / (2f * PI.toFloat()) + 1f) % 1f
                                pick.set(floatArrayOf(colourModelRingHue(model, turnAt), was[1], was[2]))
                            } else {
                                val r = discRadius.toPx()
                                pick.set(colourModelDiscPick(model, was[0], was[1], was[2], d.x / r, d.y / r).toFloatArray())
                            }
                        }
                        send(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            send(ch.position)
                            ch.consume()
                        }
                    }
                }
                .drawBehind {
                    val o = center
                    val inner = ringInner.toPx()
                    val outer = ringOuter.toPx()
                    drawCircle(ring, (inner + outer) / 2f, o, style = Stroke(outer - inner))
                    val r = discRadius.toPx()
                    disc.version
                    drawImage(disc.image, topLeft = Offset(o.x - r, o.y - r))
                    // the ring knob sits on the middle of the ring, where the hue is
                    val mid = (inner + outer) / 2f
                    knob(o + Offset(cos(turn) * mid, -sin(turn) * mid), 26.2.dp.toPx() * k, pure)
                    knob(o + Offset(at[0] * r, at[1] * r), 10.75.dp.toPx(), current)
                },
        )
    }
    if (luma) {
        LumaSliders(m, coords)
        Spacer(Modifier.height(if (short) 3.dp else 17.dp))
    }
}

/**
 * HSY′'s saturation and luma on sliders under its wheel, each row 36 tall: its letter 13 in, the
 * track from 40 to 56 short of the right, the percentage 10 short of it.
 */
@Composable
private fun LumaSliders(m: ModelColor, coords: FloatArray) {
    val letters = colourModelLetters(m.model)
    for (channel in 1..2) {
        val v = coords[channel]
        val colours = colourModelAlong(m.model, coords[0], coords[1], coords[2], channel.toUInt(), 12u).map { rgbColor(it.toInt()) }
        Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(36.dp)) {
            Text(letters.getOrElse(channel) { "" }, color = Pc.PanelTitle, fontSize = 15.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 13.dp))
            Box(Modifier.align(Alignment.CenterStart).padding(start = 40.dp, end = 56.dp)) {
                ColorSliderRow(v, colours) { to -> m.set(coords.copyOf().also { it[channel] = to }) }
            }
            Text(percentShown(v), color = Pc.PanelTitle, fontSize = 15.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
        }
    }
}

/** A colour knob: [fill] inside a thin white ring, over a soft shadow. */
internal fun DrawScope.knob(c: Offset, r: Float, fill: Color) {
    drawIntoCanvas { canvas ->
        val shadow = Paint().apply {
            color = Color(0x59000000)
            asFrameworkPaint().maskFilter = BlurMaskFilter(4.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(c + Offset(0f, 2.dp.toPx()), r, shadow)
    }
    drawCircle(fill, r, c)
    drawCircle(Color.White, r - 0.6.dp.toPx(), c, style = Stroke(1.2.dp.toPx()))
}

@Composable
internal fun SaturationBrightness(c: ColorPick, compact: Boolean, height: Dp = 344.dp) {
    // a plugin's: its picker, or a triangle, in its place
    if (Pc.colourTriangle || Pc.picker != null) {
        ColourTriangle(c, compact, height)
        return
    }
    val hue by rememberUpdatedState(c.hue)
    val pick by rememberUpdatedState(c)
    val pure = hsvColor(c.hue, 1f, 1f)
    val current = rgbColor(c.rgb())
    val squareWidth = if (compact) 248.dp else 344.dp * ResponsiveLayout.colorPickerScale(LocalSheetWidth.current)
    Box(Modifier.fillMaxWidth().padding(horizontal = if (compact) 6.dp else 16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(squareWidth, if (compact) 248.dp else height).drawWithContent {
                drawContent()
                // the knob may hang over the square's edge
                knob(Offset(c.saturation * size.width, (1f - c.brightness) * size.height), 10.75.dp.toPx(), current)
            },
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .pointerInput(Unit) { track { x, y -> pick.setHsv(hue, x, 1f - y) } }
                    .drawBehind {
                        drawRect(Brush.horizontalGradient(oneSpace(listOf(Color.White, pure))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    },
            )
        }
    }
}

/**
 * A plugin's picker in Classic's square, as big as fits: a picker plugin's picture, touches and knob, or else a
 * triangle (saturation and brightness between the pure hue at the right, white at the top left and black at the
 * bottom left) drawn by the core. A picker plugin's may pick the hue too, on a ring of its own: a touch that starts
 * where it says a hue moves the hue, and the hue's knob sits where it says.
 */
@Composable
private fun ColourTriangle(c: ColorPick, compact: Boolean, height: Dp) {
    val hue by rememberUpdatedState(c.hue)
    val saturation by rememberUpdatedState(c.saturation)
    val brightness by rememberUpdatedState(c.brightness)
    val pick by rememberUpdatedState(c)
    val picker by rememberUpdatedState(Pc.picker)
    val current = rgbColor(c.rgb())
    val box = if (compact) 248.dp else 344.dp
    val side = if (compact) 248.dp else minOf(344.dp, height)
    val px = with(LocalDensity.current) { side.roundToPx() }
    // (a new picker is its controls changed: its picture drawn again)
    val picture = rememberPickerPicture(c.hue to picker, px, false) { n ->
        val drawn = picker?.let { it.picture(hue, n) ?: ByteArray(n * n * 4) }
        drawn ?: io.github.artcraftandroid.procraft.core.trianglePicture(hue, n.toUInt())
    }
    Box(Modifier.padding(horizontal = if (compact) 6.dp else 16.dp).size(box, if (compact) 248.dp else height), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(side)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun share(p: Offset) = Offset(p.x / size.width, p.y / size.height)
                        // on the picker's ring, the hue for the whole drag; else saturation and brightness
                        val onRing = share(down.position).let { picker?.hue?.invoke(it.x, it.y) } != null
                        fun send(p: Offset) {
                            val at = share(p)
                            if (onRing) {
                                picker?.hue?.invoke(at.x, at.y)?.let { pick.setHsv(it, saturation, brightness) }
                                return
                            }
                            val (x, y) = at.x.coerceIn(0f, 1f) to at.y.coerceIn(0f, 1f)
                            val sv = picker?.pick?.invoke(x, y) ?: io.github.artcraftandroid.procraft.core.triangleSv(x, y)
                            pick.setHsv(hue, sv[0], sv[1])
                        }
                        send(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            send(ch.position)
                            ch.consume()
                        }
                    }
                }
                .drawWithContent {
                    // read, so a new picture draws
                    picture.version
                    drawImage(picture.image, dstSize = androidx.compose.ui.unit.IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    picker?.huePoint?.invoke(c.hue)?.let { knob(Offset(it[0] * size.width, it[1] * size.height), 10.75.dp.toPx(), hsvColor(c.hue, 1f, 1f)) }
                    val at = picker?.point?.invoke(c.saturation, c.brightness)
                        ?: io.github.artcraftandroid.procraft.core.trianglePoint(c.saturation, c.brightness)
                    knob(Offset(at[0] * size.width, at[1] * size.height), 10.75.dp.toPx(), current)
                },
        )
    }
}

/** A brightness slider runs from black to white whatever the colour, its knob grey at the value. */
internal val BrightnessTrack = listOf(Color.Black, Color.White)

/**
 * A 36-tall slider row: a 4-dp track through [colours] and a pill showing the colour under it.
 * Touching anywhere on the row moves the pill there.
 */
@Composable
internal fun ColorSliderRow(value: Float, colours: List<Color>, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) { track { x, _ -> change(x) } }
            .drawBehind {
                val trackH = 4.dp.toPx()
                val y = size.height / 2f
                drawRoundRect(Brush.horizontalGradient(oneSpace(colours)), Offset(0f, y - trackH / 2f), Size(size.width, trackH), CornerRadius(trackH / 2f))
                val pill = Size(16.dp.toPx(), 10.dp.toPx())
                val x = (value * size.width).coerceIn(pill.width / 2f, size.width - pill.width / 2f)
                drawRoundRect(colourAlong(colours, value), Offset(x - pill.width / 2f, y - pill.height / 2f), pill, CornerRadius(pill.height / 2f))
            },
    )
}

// The harmony disc is a hue/saturation wheel in Display P3 (the core's): on an sRGB canvas its
// colours are turned into sRGB (clipped), on a P3 one they are taken as they are.

/** P3 hue (0..1), saturation and value to a canvas colour (0xRRGGBB). */
private fun p3HsvToRgb(h: Float, s: Float, v: Float): Int =
    io.github.artcraftandroid.procraft.core.p3HsvToRgb(h, s, v, CanvasSpace.p3).toInt()

/** A canvas colour (0xRRGGBB) as P3 hue (0..1), saturation and value. */
private fun rgbToP3Hsv(rgb: Int): FloatArray =
    io.github.artcraftandroid.procraft.core.rgbToP3Hsv(rgb.toUInt(), CanvasSpace.p3).toFloatArray()

/** The colour model's button, and its menu's width. */
private val ModelButtonSize = DpSize(70.dp, 25.dp)
private val ModelMenuWidth = 160.dp

/**
 * The colour model's button left of the swatches: the model's name in a small box with a chevron;
 * a tap lists the models (HSB, HLS, HSY′, OKLab) under it, its right edge level with the button's,
 * the one in use ticked.
 */
@Composable
internal fun ColourModelButton(vm: CanvasViewModel) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(ModelButtonSize).clip(RoundedCornerShape(6.dp)).background(Pc.ModelButton).clickable { open = true }.drawBehind {
            // a small downward chevron, 9 in from the right
            val c = Offset(size.width - 9.dp.toPx(), size.height / 2f)
            val (w, h) = 3.5.dp.toPx() to 1.8.dp.toPx()
            val stroke = 1.4.dp.toPx()
            drawLine(Pc.PanelTitle, c + Offset(-w, -h), c + Offset(0f, h), stroke)
            drawLine(Pc.PanelTitle, c + Offset(0f, h), c + Offset(w, -h), stroke)
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(colourModelLabel(vm.colourModel), color = Pc.PanelTitle, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        // it grows from under the button's middle
        MenuPresence(open, androidx.compose.ui.graphics.TransformOrigin(1f - ModelButtonSize.width / 2 / ModelMenuWidth, 0f)) { look ->
            val offset = with(LocalDensity.current) { IntOffset((ModelButtonSize.width - ModelMenuWidth).roundToPx(), (ModelButtonSize.height + 6.dp).roundToPx()) }
            PcPopup(textScale = MATCHED_POPUP_TEXT, offset = offset, properties = PopupProperties(focusable = true), onDismissRequest = { open = false }) {
                Column(look.width(ModelMenuWidth).clip(RoundedCornerShape(18.dp)).background(Pc.MenuFill).padding(vertical = 9.dp)) {
                    for (model in colourModels()) {
                        Row(
                            Modifier.fillMaxWidth().height(38.dp).clickable {
                                vm.changeColourModel(model)
                                open = false
                            }.padding(start = 18.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(colourModelLabel(model), color = Pc.MenuInk, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            if (model == vm.colourModel) Icon(PcIcons.Tick, contentDescription = null, tint = Pc.MenuInk, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The harmony kind under the title; tapping it lists the kinds. */
@Composable
internal fun HarmonyKindButton(vm: CanvasViewModel, start: Dp = 20.dp, top: Dp = 50.dp, menuX: Dp = (-204).dp) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = start, top = top).height(28.dp).clickable { open = true }, contentAlignment = Alignment.CenterStart) {
        Text(vm.harmonyKind.label, color = Pc.PanelTitle, fontSize = 17.sp)
        MenuPresence(open, androidx.compose.ui.graphics.TransformOrigin(1f, 0f)) { look ->
            // the menu opens to the left of the panel, its top level with the wheel
            PcPopup(textScale = MATCHED_POPUP_TEXT, offset = with(LocalDensity.current) { IntOffset(menuX.roundToPx(), 36.dp.roundToPx()) }, properties = PopupProperties(focusable = true), onDismissRequest = { open = false }) {
                Column(look.width(195.dp).clip(RoundedCornerShape(24.dp)).background(Pc.MenuFill).padding(vertical = 9.dp)) {
                    for (kind in HarmonyKind.entries) {
                        Row(
                            Modifier.fillMaxWidth().height(38.dp).clickable {
                                vm.harmonyKind = kind
                                open = false
                            }.padding(start = 20.dp, end = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(kind.label, color = Pc.MenuInk, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            if (kind == vm.harmonyKind) Icon(PcIcons.Tick, contentDescription = null, tint = Pc.MenuInk, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The harmony picker: a 298 dp wheel (hue around, saturation out, in Display P3) with a knob for
 * the colour and one for each companion of the harmony kind, and a brightness slider below.
 */
@Composable
internal fun Harmony(vm: CanvasViewModel, c: ColorPick, compact: Boolean = false, short: Boolean = false, radius: Dp? = null) {
    val rgb = c.rgb()
    // the hue, saturation and brightness dragged to, kept while the colour they make is the colour
    // (not worked out again from it: a dark or greyish colour's would wander, and black's be lost)
    var held by remember { mutableStateOf<Pair<Int, FloatArray>?>(null) }
    val worked = remember(rgb) { rgbToP3Hsv(rgb) }
    val hsv = held?.takeIf { it.first == rgb }?.second ?: worked
    val state by rememberUpdatedState(hsv)
    val pick by rememberUpdatedState(c)
    fun set(h: Float, s: Float, v: Float) {
        pick.setRgb(p3HsvToRgb(h, s, v))
        held = pick.rgb() to floatArrayOf(h, s, v)
    }
    val density = LocalDensity.current
    // compact, the wheel is 200 across instead of 298; short, 296
    val radius = radius ?: if (compact) 100.dp else if (short) HarmonyRadius - 1.dp else HarmonyRadius
    // the harmony wheel at the colour's value, as the core draws it
    val p3 = CanvasSpace.p3
    val wheel = rememberPickerPicture(hsv[2], with(density) { radius.roundToPx() }, p3) { n ->
        io.github.artcraftandroid.procraft.core.harmonyWheel(hsv[2], n.toUInt(), p3)
    }
    // the header is 78 tall here, with the kind under the title; the wheel starts 88 down (short, 82)
    Column(Modifier.padding(top = if (short) 4.dp else 10.dp)) {
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(radius * 2)
                    .pointerInput(Unit) {
                        track { x, y ->
                            val u = x * 2f - 1f
                            val w = y * 2f - 1f
                            val h = (atan2(-w, u) / (2f * PI.toFloat()) + 1f) % 1f
                            set(h, sqrt(u * u + w * w).coerceAtMost(1f), state[2])
                        }
                    }
                    .drawBehind {
                        wheel.version
                        drawImage(wheel.image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                        val r = size.width / 2f
                        fun at(turn: Float) = center + Offset(cos(turn) * hsv[1] * r, -sin(turn) * hsv[1] * r)
                        for (t in vm.harmonyKind.turns) {
                            val h = ((hsv[0] + t / 360f) % 1f + 1f) % 1f
                            knob(at(h * 2f * PI.toFloat()), 10.75.dp.toPx(), rgbColor(p3HsvToRgb(h, hsv[1], hsv[2])))
                        }
                        knob(at(hsv[0] * 2f * PI.toFloat()), 14.5.dp.toPx(), rgbColor(rgb))
                    },
            )
        }
        Box(Modifier.padding(start = if (compact) 10.dp else 20.dp, end = if (compact) 10.dp else 20.dp, top = if (compact) 8.dp else if (short) 10.dp else 31.dp)) {
            ColorSliderRow(hsv[2], BrightnessTrack) { set(state[0], state[1], it) }
        }
    }
}

/** All palettes as compact cards: the default one ticked, each with its menu and its swatches. */
@Composable
internal fun Palettes(vm: CanvasViewModel, c: ColorPick, listHeight: Dp) {
    var renaming by remember { mutableStateOf<Int?>(null) }
    val cards = vm.paletteCards
    Row(Modifier.padding(horizontal = 8.5.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp)).background(Pc.SetCell).padding(2.dp)) {
        for ((on, icon, label) in listOf(Triple(!cards, PcIcons.CardCompact, "Compact"), Triple(cards, PcIcons.CardStack, "Cards"))) {
            Row(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(7.dp)).background(if (on) Pc.Accent else Color.Transparent)
                    .clickable { vm.changePaletteCards(label == "Cards") },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // drawn in a 21 x 32 frame, shown as it is
                Icon(icon, contentDescription = null, tint = if (on) Color.White else Pc.PanelTitle, modifier = Modifier.requiredSize(21.dp, 32.dp))
                Text(label, color = if (on) Color.White else Pc.PanelTitle, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
    LazyColumn(
        Modifier.padding(top = 8.dp).fillMaxWidth().height(listHeight),
        // cards: three 120 tiles across
        contentPadding = if (cards) PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp) else PaddingValues(start = 8.5.dp, end = 8.5.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(vm.palettes) { i, p ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pc.SetCell)) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i == vm.defaultPalette) {
                        Box(Modifier.padding(start = 11.5.dp).size(20.dp).clip(CircleShape).background(Pc.Accent), contentAlignment = Alignment.Center) {
                            Icon(PcIcons.Tick, contentDescription = "Default", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    // a tap on the name renames it in place
                    Box(Modifier.weight(1f).padding(start = if (i == vm.defaultPalette) 4.dp else 12.dp)) {
                        if (renaming == i) {
                            InlineName(p.name, Pc.PanelTitle, 14.sp, Modifier.fillMaxWidth()) {
                                vm.renamePalette(i, it)
                                renaming = null
                            }
                        } else {
                            Text(p.name, color = Pc.PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.clickable { renaming = i })
                        }
                    }
                    PaletteMenu(vm, i)
                }
                if (cards) PaletteTiles(vm, c, i, p) else SwatchGrid(vm, c, i, p, Modifier, droppable = false)
            }
        }
    }
}

/**
 * The ... button of a palette card and its menu: Set as active (unless it is), Duplicate, Share;
 * Delete.
 */
@Composable
internal fun PaletteMenu(vm: CanvasViewModel, i: Int) {
    var open by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the menu's right edge 5 left of the button, its top 25 below the button's (2.5 below its middle)
    val (gap, drop) = with(androidx.compose.ui.platform.LocalDensity.current) { 5.dp.roundToPx() to 25.dp.roundToPx() }
    Box(Modifier.padding(end = 8.5.dp).size(30.dp, 44.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
        Icon(PcIcons.More, contentDescription = "More", tint = Pc.PanelTitle, modifier = Modifier.size(20.dp))
        LibraryMenu(open, androidx.compose.ui.graphics.TransformOrigin(1f, 0f), MenuPlace { a, size -> IntOffset(a.left - gap - size.width, a.top + drop) }, onDismiss = { open = false }, overPanel = true) {
            if (vm.defaultPalette != i) {
                MenuItem(PcIcons.MenuCheckCircle, "Set as active") {
                    open = false
                    vm.chooseDefaultPalette(i)
                }
            }
            MenuItem(PcIcons.MenuDuplicate, "Duplicate") {
                open = false
                vm.duplicatePalette(i)
            }
            MenuItem(PcIcons.MenuShare, "Share") {
                open = false
                scope.launch { vm.paletteFile(i)?.let { shareFile(context, it, "application/octet-stream") } }
            }
            if (vm.palettes.size > 1) {
                MenuDivider()
                MenuItem(PcIcons.MenuTrash, "Delete", Pc.MenuDanger) {
                    open = false
                    vm.deletePalette(i)
                }
            }
        }
    }
}

/**
 * The palettes' "+" and its menu: a new palette, empty or from a photo taken, a file or a picture,
 * and our example palettes back.
 */
@Composable
internal fun NewPaletteButton(vm: CanvasViewModel) {
    var open by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val photo = remember {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(dir, "palette-camera.jpg"))
    }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { taken ->
        if (taken) vm.paletteFromImage(photo)
    }
    // a file: a palette's, or a picture to take colours from
    val file = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { if (!vm.importSwatches(it)) vm.paletteFromImage(it) } }
    }
    val photos = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::paletteFromImage)
    }
    Box(Modifier.padding(top = 12.dp).size(40.dp, 41.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
        Icon(PcIcons.PlusMedium, contentDescription = "New palette", tint = Pc.PanelGlyph, modifier = Modifier.requiredSize(41.dp))
        LibraryMenu(open, androidx.compose.ui.graphics.TransformOrigin(1f, 0f), MenuPlace { a, size -> IntOffset(a.left - size.width, a.bottom) }, onDismiss = { open = false }, overPanel = true) {
            MenuItem(PcIcons.MenuGrid, "Create new palette") {
                open = false
                vm.addPalette()
            }
            MenuItem(PcIcons.MenuCamera, "New from camera") {
                open = false
                runCatching { camera.launch(photo) }.onFailure { vm.message = "No camera app is available." }
            }
            MenuItem(PcIcons.MenuFolder, "New from file") {
                open = false
                file.launch(arrayOf("*/*"))
            }
            MenuItem(PcIcons.MenuPhoto, "New from photos") {
                open = false
                photos.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            MenuDivider()
            MenuItem(PcIcons.MenuRestore, "Restore example palettes") {
                open = false
                vm.restoreExamplePalettes()
            }
        }
    }
}

/**
 * A palette's swatches, ten by three, 36 dp each. Tap a colour to paint with it, tap an empty
 * slot to keep the current colour there, hold a colour to take it out. Where it doesn't scroll
 * ([droppable]), a colour dragged out onto the canvas fills there (SwatchDrop).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SwatchGrid(vm: CanvasViewModel, c: ColorPick, index: Int, p: Palette, modifier: Modifier, droppable: Boolean = true) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
        for (row in 0 until 3) {
            Row {
                for (col in 0 until 10) {
                    val slot = row * 10 + col
                    val rgb = p.colors[slot]
                    val touch = if (rgb != null && droppable && c is BrushColor) {
                        Modifier.dropSource(vm, swatch = { rgb }, onHold = { vm.setSwatch(index, slot, null) }) { c.setRgb(rgb) }
                    } else {
                        Modifier.combinedClickable(
                            onClick = { if (rgb != null) c.setRgb(rgb) else vm.setSwatch(index, slot, c.rgb()) },
                            onLongClick = { if (rgb != null) vm.setSwatch(index, slot, null) },
                        )
                    }
                    Box(Modifier.weight(1f).height(36.dp).pressScale().background(if (rgb != null) rgbColor(rgb) else Pc.EmptySlot).then(touch))
                }
            }
        }
    }
}

/**
 * A palette's colours as named tiles, [across] to a row (three docked, two floating), 120 square:
 * a tap takes the colour, a hold takes it out of the palette.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PaletteTiles(vm: CanvasViewModel, c: ColorPick, index: Int, p: Palette, across: Int = 3, modifier: Modifier = Modifier) {
    val filled = p.colors.withIndex().mapNotNull { (slot, rgb) -> rgb?.let { slot to it } }
    Column(modifier) {
        for (row in filled.chunked(across)) {
            Row {
                for ((slot, rgb) in row) {
                    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(rgb, it) }
                    Box(
                        Modifier.size(120.dp).pressScale().background(rgbColor(rgb)).combinedClickable(
                            onClick = { c.setRgb(rgb) },
                            onLongClick = { vm.setSwatch(index, slot, null) },
                        ),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        // dark lettering on bright colours, its baseline 11.5 above the bottom as measured
                        Text(
                            colourName(rgb),
                            color = if (hsv[2] >= 0.85f) Color.Black else Color.White,
                            fontSize = 15.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
