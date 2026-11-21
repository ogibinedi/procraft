package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.activity.compose.BackHandler
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.gallery.Artwork
import io.github.artcraftandroid.procraft.gallery.GalleryEntry
import io.github.artcraftandroid.procraft.gallery.CanvasPreset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.artcraftandroid.procraft.core.GalleryAction

/**
 * An artwork two fingers are spreading open: its thumbnail where it showed (window pixels), how far
 * the fingers have spread it and moved, and whether they have let go.
 */
internal class PinchOpening(val artwork: Artwork, val from: androidx.compose.ui.geometry.Rect) {
    var scale by mutableFloatStateOf(1f)
    var moved by mutableStateOf(Offset.Zero)
    var letGo by mutableStateOf(false)
}

/** Pinched in, an artwork being spread open shrinks no further than this. */
internal const val PINCH_LEAST = 0.6f

/**
 * An artwork spread open by two fingers ([o]): its picture follows them out of its thumbnail's
 * place, the gallery darkening behind it as it nears the whole screen; let go, it settles fitted
 * over black and [done] is told true (Preview takes over), or, spread too little, back into its
 * place and [done] is told false.
 */
@Composable
internal fun PinchOpeningOverlay(o: PinchOpening, version: Int, done: (Boolean) -> Unit) {
    val image by produceState<ImageBitmap?>(null, o.artwork.dir.path, version) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(o.artwork.thumbnail.path)?.asImageBitmap() }
    }
    // after the fingers let go: from where they left it (0) to where it settles (1)
    val settle = remember { androidx.compose.animation.core.Animatable(0f) }
    var held by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var opens by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = with(density) { androidx.compose.ui.geometry.Size(maxWidth.toPx(), maxHeight.toPx()) }
        val from = o.from
        // fitted to the screen, as Preview shows it
        val fit = minOf(screen.width / from.width, screen.height / from.height)
        val full = androidx.compose.ui.geometry.Rect(
            Offset((screen.width - from.width * fit) / 2f, (screen.height - from.height * fit) / 2f),
            androidx.compose.ui.geometry.Size(from.width * fit, from.height * fit),
        )
        // following the fingers: grown about the thumbnail's middle and moved with them
        val following = androidx.compose.ui.geometry.Rect(
            from.center + o.moved - Offset(from.width * o.scale / 2f, from.height * o.scale / 2f),
            androidx.compose.ui.geometry.Size(from.width * o.scale, from.height * o.scale),
        )
        LaunchedEffect(o.letGo) {
            if (!o.letGo) return@LaunchedEffect
            held = following
            opens = o.scale >= Motion.ARTWORK_PINCH_OPEN
            settle.animateTo(1f, Motion.pinchSettle())
            done(opens)
        }
        val rect = held?.let { start -> androidx.compose.ui.geometry.lerp(start, if (opens) full else from, settle.value) } ?: following
        // how near the whole screen it is: the gallery darkens as far
        val near = ((rect.width - from.width) / (full.width - from.width).coerceAtLeast(1f)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = near }.background(Color.Black))
        Box(
            Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .clip(RoundedCornerShape(8.dp * (1f - near))),
        ) {
            image?.let { ThumbnailImage(it, o.artwork.name, ContentScale.FillBounds) }
        }
    }
}

/**
 * Artworks one at a time over the whole screen, from the one at [start]: the picked ones, or the
 * gallery's from a thumbnail spread open; swiped through, closed by its cross (pinching in doesn't
 * close it, as measured).
 */
@Composable
internal fun ArtworkPreview(list: List<Artwork>, version: Int, start: Int, playing: Boolean, onClose: () -> Unit) {
    var index by remember(list, start) { mutableIntStateOf(start) }
    // a tap shows (or hides) the close button and the arrows to the other picked artworks
    var controls by remember { mutableStateOf(false) }
    val a = list.getOrNull(index) ?: return onClose()
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(list) {
            var moved = 0f
            detectHorizontalDragGestures(
                onDragStart = { moved = 0f },
                onDragEnd = {
                    if (moved < -60f && index < list.size - 1) index++ else if (moved > 60f && index > 0) index--
                },
            ) { c, dx ->
                c.consume()
                moved += dx
            }
        }.pointerInput(Unit) { detectTapGestures { controls = !controls } },
        contentAlignment = Alignment.Center,
    ) {
        val image by produceState<ImageBitmap?>(null, a.dir.path, version) {
            value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(a.thumbnail.path)?.asImageBitmap() }
        }
        // an animated artwork plays as its settings say, its thumbnail showing until its frames are read
        val side = androidx.compose.ui.platform.LocalConfiguration.current.let { with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(it.screenWidthDp, it.screenHeightDp).dp.roundToPx() } }
        val played by produceState<PlayedFrames?>(null, a.dir.path, version) {
            value = null
            value = withContext(Dispatchers.Default) { readFrames(a.dir.path, side) }
        }
        var frame by remember(played) { mutableStateOf<ImageBitmap?>(null) }
        // (held while the app is away)
        androidx.compose.runtime.LaunchedEffect(played, playing) {
            val p = played ?: return@LaunchedEffect
            var at = 0u
            var step = 1
            while (playing) {
                frame = p.pictures.getOrNull(p.order.getOrElse(at.toInt()) { 0 })
                kotlinx.coroutines.delay(1000L / p.fps.coerceAtLeast(1))
                // round again, turned back, or (a single pass) staying on its last frame
                val next = io.github.artcraftandroid.procraft.core.animationNextStep(p.playback, p.order.size.toUInt(), at, step) ?: break
                at = next.at
                step = next.step
            }
        }
        (frame ?: image)?.let { ThumbnailImage(it, a.name, ContentScale.Fit) }
        androidx.compose.animation.AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // a light disc with a black cross, 41 in from the top right
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-41).dp + 22.dp, y = 22.dp - 22.dp).size(44.dp).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(23.5.dp).clip(CircleShape).background(PreviewClose), contentAlignment = Alignment.Center) {
                        Icon(PcIcons.Cross, contentDescription = "Close", tint = Color.Black, modifier = Modifier.size(15.dp))
                    }
                }
                PreviewArrow(left = true, Modifier.align(Alignment.CenterStart)) { if (index > 0) index-- }
                PreviewArrow(left = false, Modifier.align(Alignment.CenterEnd)) { if (index < list.size - 1) index++ }
            }
        }
    }
}

/** An artwork's frames as read for Preview, and how they play. */
private class PlayedFrames(val pictures: List<ImageBitmap>, val order: List<Int>, val fps: Int, val playback: io.github.artcraftandroid.procraft.core.AnimationPlayback)

/** The most memory (bytes) an animation's frames take in Preview: past it they are smaller. */
private const val PLAYED_MOST = 256e6

/** The frames of the artwork in [dir] if it is animated, at no more than [side] pixels. Slow. */
private fun readFrames(dir: String, side: Int): PlayedFrames? {
    if (!io.github.artcraftandroid.procraft.core.artworkPlays(dir)) return null
    val s = runCatching { io.github.artcraftandroid.procraft.core.CanvasSession.open(dir) }.getOrNull() ?: return null
    try {
        if (s.modelInfo() != null) return readTurntable(s, side)
        val settings = s.animationSettings()?.takeIf { !it.pages } ?: return null
        if (!s.drawWithoutWindow()) return null
        val count = s.animationFrameCount()
        val fit = kotlin.math.sqrt(PLAYED_MOST / 4.0 / count.coerceAtLeast(1u).toDouble()).toInt()
        val pictures = (0u until count).mapNotNull { i ->
            s.animationFrame(i, false, minOf(side, fit).toUInt())?.let { t ->
                val bitmap = CanvasSpace.tag(android.graphics.Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888))
                bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(t.rgba))
                bitmap.asImageBitmap()
            }
        }
        return PlayedFrames(pictures, s.animationPlayOrder().map { it.toInt() }, settings.fps.toInt(), settings.playback)
    } finally {
        s.destroy()
    }
}

/** A model going round, opened in [s], at no more than [side] pixels. */
private fun readTurntable(s: io.github.artcraftandroid.procraft.core.CanvasSession, side: Int): PlayedFrames? {
    if (!s.drawWithoutWindow()) return null
    val count = s.modelPreviewFrames()
    val fit = kotlin.math.sqrt(PLAYED_MOST / 4.0 / count.coerceAtLeast(1u).toDouble()).toInt()
    val pictures = (0u until count).mapNotNull { i ->
        s.modelPreviewPicture(i, minOf(side, fit).toUInt())?.let { t ->
            val bitmap = CanvasSpace.tag(android.graphics.Bitmap.createBitmap(t.width.toInt(), t.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888))
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(t.rgba))
            bitmap.asImageBitmap()
        }
    }
    return PlayedFrames(pictures, pictures.indices.toList(), s.modelPreviewFps().toInt(), io.github.artcraftandroid.procraft.core.AnimationPlayback.LOOP)
}

private val PreviewClose = Color(0xFFD7D7D7)
private val PreviewArrowInk = Color(0xFFB4B4B4)

/** One of Preview's arrows: a 13.5 x 23.5 chevron 40 in from its side, in a 44 wide touch area. */
@Composable
private fun PreviewArrow(left: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(start = if (left) 18.dp else 0.dp, end = if (left) 0.dp else 18.dp).size(44.dp, 60.dp).clickable(onClick = onClick)
            .drawBehind {
                // drawn a little larger than the 13.5 x 23.5 it shows (the stroke's edges soften)
                val w = 14.5.dp.toPx()
                val h = 25.5.dp.toPx()
                val stroke = 4.dp.toPx()
                val cx = size.width / 2f
                val tip = if (left) cx - w / 2f + stroke / 2f else cx + w / 2f - stroke / 2f
                val back = if (left) cx + w / 2f - stroke / 2f else cx - w / 2f + stroke / 2f
                val top = size.height / 2f - h / 2f + stroke / 2f
                val bottom = size.height / 2f + h / 2f - stroke / 2f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(back, top)
                    lineTo(tip, size.height / 2f)
                    lineTo(back, bottom)
                }
                drawPath(path, PreviewArrowInk, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Miter))
            },
    )
}

/**
 * How a thumbnail drawn [shown] pixels wide is filtered: at 100% or more (each of its pixels a
 * point or larger) a square of its own colour, as the canvas shows them; smaller, smoothed.
 */
internal fun thumbnailFilter(image: ImageBitmap, shown: Float, density: Float) =
    if (shown >= image.width * density * 0.999f) FilterQuality.None else FilterQuality.Low

/** An artwork's thumbnail fitted in its box (or filling it, by [scale]), filtered by [thumbnailFilter]. */
@Composable
private fun ThumbnailImage(image: ImageBitmap, name: String, scale: ContentScale) {
    val density = LocalDensity.current.density
    var shown by remember { mutableFloatStateOf(0f) }
    // made again when the filter changes (Image(bitmap) keeps its first)
    val filter = thumbnailFilter(image, shown, density)
    val painter = remember(image, filter) { androidx.compose.ui.graphics.painter.BitmapPainter(image, filterQuality = filter) }
    Image(
        painter,
        contentDescription = name,
        contentScale = scale,
        modifier = Modifier.fillMaxSize().onSizeChanged {
            // fitted, as wide as the box lets its height
            shown = minOf(it.width.toFloat(), it.height * image.width / image.height.coerceAtLeast(1).toFloat())
        },
    )
}

// as measured: a thumbnail's shadow dropped below it, and the rim round it
private val ThumbShadowDrop = 5.dp
private val ThumbShadow get() = if (Pc.light) Color(0x1D000000) else Color(0x45000000)
private val ThumbRim get() = if (Pc.light) Color(0xFFBEBEBE) else Color(0xFF424242)

/** The thumbnails last decoded, by file: shown while one loads again. */
internal val ThumbnailsShown = android.util.LruCache<String, ImageBitmap>(64)

/**
 * An artwork's thumbnail, as large as fits in its cell's box, with a soft shadow; while selecting,
 * a circle at its top right ([chosen]: filled blue with a tick).
 */
@Composable
internal fun Thumbnail(a: Artwork, version: Int, chosen: Boolean? = null, wash: Boolean = false, onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    // the last one decoded shows while it loads again (an artwork opened or closed, saved meanwhile),
    // so a canvas of another colour never flashes white
    val image by produceState(ThumbnailsShown.get(a.thumbnail.path), a.dir.path, a.modified, version) {
        val decoded = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(a.thumbnail.path)?.asImageBitmap() }
        if (decoded != null) ThumbnailsShown.put(a.thumbnail.path, decoded)
        value = decoded ?: value
    }
    val ratio = a.ratio
    val cell = LocalCell.current
    val tall = ratio <= cell.width / cell.thumb
    Box(
        Modifier.then(if (tall) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
            .aspectRatio(ratio, matchHeightConstraintsFirst = tall)
            .onGloballyPositioned { onPlaced(it.boundsInWindow()) }
            .drawBehind {
                drawIntoCanvas { canvas ->
                    val shadow = Paint().apply {
                        color = ThumbShadow
                        // a Gaussian 2 across (the blur's radius as the framework takes it)
                        asFrameworkPaint().maskFilter = BlurMaskFilter((2.dp.toPx() - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
                    }
                    val drop = ThumbShadowDrop.toPx()
                    canvas.drawRoundRect(0f, drop, size.width, size.height + drop, 8.dp.toPx(), 8.dp.toPx(), shadow)
                }
            }
            .clip(RoundedCornerShape(8.dp))
            // a picture with its background hidden shows the gallery through it (white while it loads)
            .background(if (image == null) Color.White else Pc.GalleryBackground)
            .border(1.dp, ThumbRim, RoundedCornerShape(8.dp)),
    ) {
        image?.let { ThumbnailImage(it, a.name, ContentScale.FillBounds) }
        // blue while an artwork dragged over it would be stacked with it
        if (wash) Box(Modifier.matchParentSize().background(DropWash))
        // the marks fade in as Select starts, and out as it ends
        FadingValue(chosen, Motion.selectMode()) { chosen, shown ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(14.dp).graphicsLayer { alpha = shown }.clip(CircleShape)
                    // unpicked, a thin light ring
                    .background(if (chosen) Color(0xFF0F7EFD) else Color.Transparent)
                    .then(if (chosen) Modifier else Modifier.border(1.dp, Color(0xFFCCCCCC), CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (chosen) Icon(PcIcons.MenuTick, contentDescription = "Picked", tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
    }
}

/**
 * The new-canvas list: the screen's size first, then the presets, each with its colour profile and
 * size; a preset row swiped left shows Edit and Delete. Under them, a row for each kind of artwork a
 * plugin on makes, the screen's size.
 */
@Composable
internal fun NewCanvasPanel(
    modifier: Modifier,
    arrowX: Dp,
    presets: List<CanvasPreset>,
    width: Dp = NewCanvasWidth,
    onCustom: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onPick: (CanvasPreset) -> Unit,
    kinds: List<io.github.artcraftandroid.procraft.core.PluginKind> = emptyList(),
    onKind: (io.github.artcraftandroid.procraft.core.PluginKind, Int, Int) -> Unit = { _, _, _ -> },
    onScreenSize: (Int, Int) -> Unit,
) {
    // the screen as it is turned now (2560 x 1600 in landscape, 1600 x 2560 upright)
    val root = LocalView.current.rootView
    val (sw, sh) = root.width.coerceAtLeast(1) to root.height.coerceAtLeast(1)
    // the row swiped open, if one is
    var opened by remember { mutableStateOf<Int?>(null) }
    Popover(width, arrowX, modifier, fill = Pc.LightPanel, corner = 32.dp) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                "New canvas",
                color = Pc.LightText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 23.dp, top = 21.dp, bottom = 20.dp),
            )
            // the button that makes a custom canvas
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 13.dp).size(44.dp, 34.dp).clickable(onClick = onCustom),
                contentAlignment = Alignment.Center,
            ) {
            Box(
                Modifier.size(27.dp, 16.dp).drawBehind {
                    drawRoundRect(Pc.LightText, cornerRadius = CornerRadius(4.dp.toPx()))
                    val w = 1.6.dp.toPx()
                    val c = Offset(8.dp.toPx(), size.height / 2f)
                    val arm = 3.6.dp.toPx()
                    drawLine(Pc.LightPanel, c - Offset(arm, 0f), c + Offset(arm, 0f), w, StrokeCap.Round)
                    drawLine(Pc.LightPanel, c - Offset(0f, arm), c + Offset(0f, arm), w, StrokeCap.Round)
                },
            )
            }
        }
        PresetRow("Screen Size", "$sw × ${sh}px", CanvasPreset.P3, Modifier.padding(horizontal = 9.dp).height(38.dp).clip(CircleShape).background(Pc.LightRowStrong)) { onScreenSize(sw, sh) }
        // the presets scroll past 376 tall
        Column(
            Modifier.padding(start = 9.dp, end = 9.dp, top = 9.5.dp, bottom = if (kinds.isEmpty()) 30.dp else 0.dp).heightIn(max = 376.dp).clip(RoundedCornerShape(24.dp)).background(Pc.LightRow)
                .verticalScroll(rememberScrollState()),
        ) {
            presets.forEachIndexed { i, p ->
                if (i > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Pc.LightSeparator))
                SwipeRow(
                    p, Modifier.height(if (i > 0) 44.dp else 45.dp), open = opened == i, onOpen = { opened = if (it) i else null },
                    onEdit = {
                        opened = null
                        onEdit(i)
                    },
                    onDelete = {
                        opened = null
                        onDelete(i)
                    },
                ) {
                    if (opened != null) opened = null else onPick(p)
                }
            }
        }
        // the plugins' kinds of artwork, each the screen's size
        kinds.forEachIndexed { k, kind ->
            val bottom = if (k == kinds.lastIndex) 30.dp else 0.dp
            PresetRow(
                kind.label, "$sw × ${sh}px", CanvasPreset.P3,
                Modifier.padding(start = 9.dp, end = 9.dp, top = 9.5.dp, bottom = bottom).height(38.dp).clip(CircleShape).background(Pc.LightRowStrong),
            ) { onKind(kind, sw, sh) }
        }
    }
}

// A swiped preset row: how far it slides, and the two buttons it shows
private val SwipeReveal = 159.dp
private val SwipeBehind = Color(0xFFD6D6D6)
private val EditFill = Color(0xFFC6C6C6)
private val EditInk = Color(0xFF575757)
private val DeleteFill = Color(0xFFFE604D)

/** A preset row that slides left under a finger to show Edit and Delete; a tap picks it (or closes it, open). */
@Composable
private fun SwipeRow(p: CanvasPreset, modifier: Modifier, open: Boolean, onOpen: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    val reveal = with(LocalDensity.current) { SwipeReveal.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val x by animateFloatAsState(if (dragging) dragX else if (open) -reveal else 0f, if (dragging) snap() else Motion.swipeSettle(), label = "swipe")
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.matchParentSize().background(SwipeBehind).padding(end = 13.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwipeButton("Edit", EditFill, EditInk, onEdit)
            Spacer(Modifier.width(3.dp))
            SwipeButton("Delete", DeleteFill, Color.White, onDelete)
        }
        PresetRow(
            p.name, p.label, p.profile,
            Modifier.fillMaxSize().offset { IntOffset(x.roundToInt(), 0) }.background(Pc.LightRow).pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragX = x
                        dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        onOpen(dragX < -reveal / 2f)
                    },
                    onDragCancel = { dragging = false },
                ) { change, dx ->
                    change.consume()
                    dragX = (dragX + dx).coerceIn(-reveal, 0f)
                }
            },
            onClick,
        )
    }
}

@Composable
private fun SwipeButton(label: String, fill: Color, ink: Color, onClick: () -> Unit) {
    Box(Modifier.size(69.dp, 33.dp).clip(RoundedCornerShape(7.dp)).background(fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PresetRow(name: String, label: String, profile: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 17.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Pc.LightText, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Chip(CanvasPreset.chip(profile))
        Chip(label, Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.height(18.dp).clip(CircleShape).background(Pc.LightChip).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Pc.LightText, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * What the app is, over the gallery (blurred and dimmed under it): its icon 300 across, its name,
 * its version; a round button at the top right closes it, as back does.
 */
@Composable
internal fun AboutApp(onRecover: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "Version ${info.versionName} Build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}"
        }.getOrDefault("")
    }
    Box(Modifier.fillMaxSize().background(AboutVeil).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }) {
        Column(Modifier.fillMaxWidth().padding(top = 95.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // the launcher icon: its field, and its picture drawn at the adaptive icon's full size
            Box(Modifier.size(300.dp).clip(RoundedCornerShape(67.dp)).background(AboutIconField), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(io.github.artcraftandroid.procraft.R.drawable.procraft_mark),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(450.dp),
                )
            }
            Text("ProCraft", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
            Text(version, color = AboutInk, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 14.dp))
        }
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = AboutButtonTop).size(220.dp, 38.dp).clip(RoundedCornerShape(8.dp))
                .background(AboutButtonFill).clickable(onClick = onRecover),
            contentAlignment = Alignment.Center,
        ) {
            Text("Start Gallery Recovery", color = AboutButtonInk, fontSize = 15.sp, maxLines = 1)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 31.dp, end = 31.5.dp).size(22.5.dp).clip(CircleShape).background(AboutClose)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
                val w = 1.8.dp.toPx()
                drawLine(AboutCloseInk, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(AboutCloseInk, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

// as measured: the gallery under a near-black veil, the light round close button
private val AboutVeil = Color(0xD90B0B0B)
private val AboutIconField = Color(0xFF0B0B0C)
private val AboutInk = Color(0xFFBDBDBD)
private val AboutClose = Color(0xFFE3E3E3)
private val AboutCloseInk = Color(0xFF1C1C1C)
// the button 520 under the icon's top, 220 x 38
private val AboutButtonTop = 615.dp
private val AboutButtonFill = Color(0xFF1A1A1A)
private val AboutButtonInk = Color(0xFFC4C4C4)
internal const val RECOVERY_SEARCH_MS = 700
internal const val RECOVERY_DONE_MS = 900L
