package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.LayerOption
import io.github.artcraftandroid.procraft.core.LayerType
import io.github.artcraftandroid.procraft.core.blendCoast
import io.github.artcraftandroid.procraft.core.blendCoastAt
import io.github.artcraftandroid.procraft.core.blendTap
import io.github.artcraftandroid.procraft.core.blendTapAt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A model's layer's channels under its row: Color, Roughness and Metallic, each its picture of
 * the layer; the one painted on (of the current layer) blue. A tap paints on it.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ChannelTray(vm: CanvasViewModel, l: LayerInfo) {
    Row(Modifier.fillMaxWidth().height(159.dp).background(TrayFill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Color", "Roughness", "Metallic").forEachIndexed { c, label ->
            val chosen = l.current && vm.channelTileChosen && vm.modelChannel == c
            // held, a picture replaces what the channel holds
            val replace = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
            ) { uri -> uri?.let { vm.replaceMaterialMap(l, c, it) } }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(if (chosen) Pc.Accent else TileFill)
                    .combinedClickable(
                        onLongClick = {
                            val images = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            replace.launch(androidx.activity.result.PickVisualMediaRequest(images))
                        },
                    ) {
                        if (!l.current) vm.selectLayer(l.id)
                        vm.chooseChannelTile(c)
                    }
                    .padding(6.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(102.dp).clip(RoundedCornerShape(3.dp)).background(Pc.ThumbBackground)) {
                    vm.channelThumbnails[c to l.id]?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Text(label, color = if (chosen) Color.White else Pc.PanelTitle, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp, top = 9.dp))
            }
        }
    }
}

/** A layer's visibility box: filled with a check when shown, an outline when hidden. */
@Composable
internal fun CheckBox(checked: Boolean, fill: Color) {
    val shape = RoundedCornerShape(5.dp)
    val tick = androidx.compose.ui.graphics.vector.rememberVectorPainter(PcIcons.Tick)
    Box(
        Modifier.size(18.dp)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .clip(shape)
            .then(if (checked) Modifier.background(fill) else Modifier.border(1.5.dp, fill, shape))
            .drawWithContent {
                drawContent()
                if (!checked) return@drawWithContent
                // the tick is cut out of the box: the row shows through it
                val side = 14.dp.toPx()
                val inset = (size.width - side) / 2f
                val cut = androidx.compose.ui.graphics.Paint().apply { blendMode = androidx.compose.ui.graphics.BlendMode.DstOut }
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), cut)
                translate(inset, inset) { with(tick) { draw(Size(side, side)) } }
                drawContext.canvas.restore()
            },
    )
}

@Composable
internal fun Thumb(vm: CanvasViewModel, l: LayerInfo, shape: Shape, held: Boolean = false) {
    Box(
        Modifier.size(width = 93.dp, height = 61.dp).clip(shape)
            // held over by a dragged row long enough to take it
            .then(if (held) Modifier.border(2.dp, HeldOutline, shape) else Modifier)
            // a mask's thumbnail sits on white
            .then(
                when {
                    l.alphaLock -> Modifier.drawBehind { checker() }
                    l.kind == LayerType.MASK -> Modifier.background(Color.White)
                    else -> Modifier.background(Pc.ThumbBackground)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // the thumbnail is what is drawn, fitted; small drawings show their pixels; a text layer's
        // is its glyph
        if (l.text) {
            Icon(PcIcons.TextLayer, contentDescription = null, tint = Pc.TextLayerGlyph, modifier = Modifier.fillMaxSize())
        } else {
            vm.thumbnails[l.id]?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            }
        }
        // a plugin's layer: its kind's badge in its corner, as its manifest draws it
        l.pluginBadge?.let { badge ->
            androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp, 14.dp)) {
                drawRoundRect(PluginBadgeBehind, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                badgeDrawing(badge, Color.White, 1.5.dp.toPx())
            }
        }
    }
}

/** How wide the colour a plugin labels a layer with shows, down the left edge of its row. */
private val LabelWidth = 5.dp

/** The colour a plugin labels [l] with, if one does, down the left edge of its row. */
internal fun Modifier.labelled(l: LayerInfo): Modifier {
    val label = l.label ?: return this
    val colour = Color(0xFF000000L or label.toLong())
    return drawBehind { drawRect(colour, size = Size(LabelWidth.toPx(), size.height)) }
}

/** The badge behind a plugin's layer's glyph on its thumbnail. */
private val PluginBadgeBehind = Color(0x99000000)

/**
 * A plugin's [badge]'s lines and dots in [ink] over the whole of this scope (its box, 0 to 1 across and down): its lines
 * [width] thick, its dots squares twice that across.
 */
internal fun DrawScope.badgeDrawing(badge: io.github.artcraftandroid.procraft.core.PluginBadge, ink: Color, width: Float) {
    val (w, h) = size.width to size.height
    for (line in badge.lines) {
        val p = line.points
        if (p.size < 4) continue
        val path = Path().apply {
            moveTo(w * p[0], h * p[1])
            var i = 2
            while (i + 1 < p.size) {
                if (line.curved && i + 5 < p.size) {
                    cubicTo(w * p[i], h * p[i + 1], w * p[i + 2], h * p[i + 3], w * p[i + 4], h * p[i + 5])
                    i += 6
                } else {
                    lineTo(w * p[i], h * p[i + 1])
                    i += 2
                }
            }
        }
        drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width))
    }
    val node = width * 2
    for (i in 0 until badge.dots.size / 2) {
        val (x, y) = w * badge.dots[2 * i] to h * badge.dots[2 * i + 1]
        drawRect(ink, Offset(x - node / 2, y - node / 2), Size(node, node))
    }
}

/** The transparency checkerboard behind thumbnails. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.checker() {
    val cell = 6.dp.toPx()
    drawRect(Color(0xFFFFFFFF))
    var y = 0
    while (y * cell < size.height) {
        var x = 0
        while (x * cell < size.width) {
            if ((x + y) % 2 == 1) drawRect(Color(0xFFD6D6D6), Offset(x * cell, y * cell), Size(cell, cell))
            x++
        }
        y++
    }
}

/**
 * The letters on a layer's row for its mode: a two-word name's initials (the second small), a
 * one-word name's first letter, or its first two where one letter is already taken.
 */
internal fun blendCode(vm: CanvasViewModel, blend: UInt): String {
    val name = vm.blendModeList.firstOrNull { it.id == blend }?.name ?: return "N"
    if (name in TWO_LETTER_BLENDS) return name.take(2)
    val words = name.split(' ')
    return if (words.size == 1) name.take(1) else words[0].take(1) + words[1].take(1).lowercase()
}

private val TWO_LETTER_BLENDS = setOf("Darken", "Shade", "Lighten", "Subtract", "Divide", "Saturation")

@Composable
internal fun BlendEditor(vm: CanvasViewModel, l: LayerInfo, open: Boolean) {
    var opacity by remember(l.id) { mutableFloatStateOf(l.opacity) }
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 15.dp)) {
        Row(Modifier.padding(horizontal = 13.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", color = Pc.PanelTitle, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(if (opacity >= 0.995f) "Max" else "${(opacity * 100).roundToInt()}%", color = Pc.PanelTitle, fontSize = 17.sp)
        }
        OpacitySlider(
            opacity,
            onChange = {
                opacity = it
                vm.setLayerOpacity(l, it, done = false)
            },
            onDone = { vm.setLayerOpacity(l, opacity, done = true) },
        )
        // a picker: the rows slide under a band fixed in the middle, the row there the layer's mode
        // as it moves (one undo step a movement); let go, the list slows straight onto a row; a
        // tap on another row makes it the mode at once and scrolls it to the band, untouchable
        val modes = vm.blendModeList
        val owner = vm.session
        var shownMode by remember(owner, l.id) { mutableStateOf(l.blend) }
        var tapping by remember(owner, l.id) { mutableStateOf(false) }
        val list = rememberLazyListState(modes.indexOfFirst { it.id == l.blend }.coerceAtLeast(0))
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density
        val (rowPx, before, after) = with(LocalDensity.current) {
            val height = BlendRow.roundToPx()
            val padding = BlendListHeight.roundToPx() - height
            // Divide the padding in pixels so the last row can center at every density.
            Triple(height, (padding / 2).toDp(), (padding - padding / 2).toDp())
        }
        val bandTop = with(LocalDensity.current) { before.roundToPx() }
        val position by remember(list, rowPx) {
            derivedStateOf { list.firstVisibleItemIndex * rowPx + list.firstVisibleItemScrollOffset }
        }
        val centered by remember(rowPx) { derivedStateOf { (position + rowPx / 2) / rowPx } }
        val fling = remember(rowPx, density, modes.size) { BlendFling(rowPx, density, modes.size) { position } }

        fun preview(index: Int) {
            val mode = modes.getOrNull(index) ?: return
            if (tapping || mode.id == shownMode) return
            shownMode = mode.id
            vm.previewBlend(owner, l.id, mode.id)
        }

        LaunchedEffect(open, owner, list, modes, rowPx) {
            if (!open) return@LaunchedEffect
            // the mode under the band as the list moves, by finger or by itself
            launch { snapshotFlow { centered }.collect { preview(it) } }
            snapshotFlow { if (list.isScrollInProgress) null else position }.collect { idlePosition ->
                if (idlePosition == null) return@collect
                // Give a fling starting as the drag ends a frame to take over the list.
                withFrameNanos { }
                if (list.isScrollInProgress || position != idlePosition || tapping) return@collect
                if (position % rowPx != 0) {
                    // stopped between rows (a touch caught it moving): onto the nearest
                    val coast = blendCoast(position / density, 0f, rowPx / density, modes.size.toUInt())
                    coroutineScope { launch { list.scroll { followBlend(rowPx, density, { position }) { blendCoastAt(coast, it) } } } }
                } else {
                    preview(centered)
                    vm.finishBlend(owner)
                }
            }
        }
        DisposableEffect(open, owner, l.id) {
            onDispose { if (open) vm.finishBlend(owner) }
        }
        LaunchedEffect(open, l.blend, rowPx) {
            if (!open || l.blend == shownMode) return@LaunchedEffect
            // Undo and redo can change the mode while the picker is open.
            shownMode = l.blend
            val index = modes.indexOfFirst { it.id == l.blend }
            if (index >= 0) list.scrollToItem(index)
        }

        val highlight = Pc.RowSelected
        Box(
            Modifier.padding(top = 11.dp).fillMaxWidth().height(BlendListHeight)
                .clip(RoundedCornerShape(12.dp)).background(Pc.LayerRow)
                .drawBehind { drawRect(highlight, Offset(0f, bandTop.toFloat()), Size(size.width, rowPx.toFloat())) },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(top = before, bottom = after),
                flingBehavior = fling,
                userScrollEnabled = !tapping,
            ) {
                itemsIndexed(modes) { i, m ->
                    Box(
                        Modifier.fillMaxWidth().height(BlendRow)
                            .clickable(enabled = !tapping) {
                                val from = position / density
                                val to = blendTap(i.toUInt(), from, rowPx / density, modes.size.toUInt()) ?: return@clickable
                                shownMode = m.id
                                vm.setBlend(l, m.id)
                                tapping = true
                                scope.launch {
                                    try {
                                        list.scroll(MutatePriority.PreventUserInput) {
                                            followBlend(rowPx, density, { position }) { blendTapAt(from, to, it) }
                                        }
                                    } finally {
                                        tapping = false
                                    }
                                }
                            },
                    ) {
                        // grey outside the band and white inside it: a name half under it is half and half
                        BlendLabel(
                            m.name, Pc.PanelTitle, BlendGlyphInk,
                            Modifier.drawWithContent {
                                val (top, bottom) = blendBandInRow(position, i, rowPx)
                                clipRect(top = top, bottom = bottom, clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
                            },
                        )
                        BlendLabel(
                            m.name, Color.White, Color.White,
                            // (read out once, from the grey copy)
                            Modifier.clearAndSetSemantics { }.drawWithContent {
                                val (top, bottom) = blendBandInRow(position, i, rowPx)
                                clipRect(top = top, bottom = bottom) { this@drawWithContent.drawContent() }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A blend mode's name and glyph, in [ink] and [glyphInk]. */
@Composable
private fun BlendLabel(name: String, ink: Color, glyphInk: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().padding(start = 12.dp, end = 18.5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = ink, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        PcIcons.BlendGlyphs[name]?.let { Icon(it, contentDescription = null, tint = glyphInk, modifier = Modifier.size(26.dp)) }
    }
}

/** The layer's blend list: nine rows of [BlendRow] showing; its glyphs a darker grey than its names. */
private val BlendRow = 38.8.dp
private val BlendListHeight = 349.dp
private val BlendGlyphInk = Color(0xFF808080)

/** A thin blue slider with a white knob, set by dragging anywhere along it. */
@Composable
internal fun OpacitySlider(value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Box(
        Modifier.padding(horizontal = 13.dp, vertical = 2.dp).fillMaxWidth().height(16.dp)
            .pointerInput(Unit) {
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
                    done()
                }
            }
            .drawBehind {
                val y = size.height / 2f
                val h = 3.dp.toPx()
                drawRoundRect(Pc.SliderRest, Offset(0f, y - h / 2f), Size(size.width, h), CornerRadius(h / 2f))
                drawRoundRect(Pc.Accent, Offset(0f, y - h / 2f), Size(size.width * value, h), CornerRadius(h / 2f))
                val knob = Size(26.dp.toPx(), 15.dp.toPx())
                val x = (size.width * value).coerceIn(knob.width / 2f, size.width - knob.width / 2f)
                drawRoundRect(Color.White, Offset(x - knob.width / 2f, y - knob.height / 2f), knob, CornerRadius(knob.height / 2f))
            },
    )
}
/**
 * The current layer's options, left of the panel and centred on its row. What doesn't apply to
 * it is left out; lock, duplicate and delete are on the row's swipe.
 */
@Composable
internal fun LayerOptions(open: Boolean, vm: CanvasViewModel, l: LayerInfo, rowY: Float, onRename: () -> Unit, onDone: () -> Unit) {
    val density = LocalDensity.current
    val place = remember(rowY, density) {
        with(density) { OptionsPlace((-183).dp.roundToPx(), rowY, OptionsTop.roundToPx(), OptionsShadowRoom.roundToPx()) }
    }
    MenuPresence(open, androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)) { look ->
        PcPopup(textScale = MATCHED_POPUP_TEXT, popupPositionProvider = place, onDismissRequest = onDone, properties = PopupProperties(focusable = open)) {
            // frosted over the canvas, as the library's menus are; empty (a locked group's or
            // mask's), as measured, a pill 64 tall with the frost alone, untinted
            val rows = vm.layerOptions(l)
            // a plugin's actions for the layer, after the app's own
            val actions = remember { vm.plugins.actionsIn(io.github.artcraftandroid.procraft.core.PluginPlace.LAYER) }
            val tint = if (rows.isEmpty() && actions.isEmpty()) Color.Transparent else Pc.GlassMenu
            val room = with(density) { OptionsShadowRoom.toPx() }
            // the room round it for its shadow: a tap there closes it, as one further out does
            Box(
                look.pointerInput(Unit) {
                    detectTapGestures { p ->
                        if (p.x < room || p.y < room || p.x > size.width - room || p.y > size.height - room) onDone()
                    }
                }.padding(OptionsShadowRoom).drawBehind { optionsShadow() },
            ) {
            Column(Modifier.width(194.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(24.dp)).then(rememberFrost(24.dp)).background(tint).padding(vertical = 7.dp)) {
                // the core's rows: each closes the menu, a switch's too (it shows ticked next time)
                val editableShape = vm.isEditableShapeLayer(l.id)
                for (r in rows) {
                    val act: () -> Unit = when (r.option) {
                        LayerOption.RENAME -> onRename
                        LayerOption.UNGROUP -> { { vm.ungroup(l) } }
                        LayerOption.FLATTEN -> { { vm.flattenGroup(l) } }
                        LayerOption.SELECT -> { { vm.selectLayerContents(l) } }
                        LayerOption.COPY -> { { vm.copyLayerContents(l) } }
                        LayerOption.FILL -> { { vm.fillLayer(l) } }
                        LayerOption.CLEAR -> { { vm.clear(l) } }
                        LayerOption.ALPHA_LOCK -> { { vm.toggleAlphaLock(l) } }
                        LayerOption.MASK -> { { vm.addMask(l) } }
                        LayerOption.CLIPPING_MASK -> { { vm.toggleClipped(l) } }
                        LayerOption.DRAWING_ASSIST -> { { vm.setGuideAssisted(!l.assisted) } }
                        LayerOption.INVERT -> { { vm.invertLayer(l) } }
                        LayerOption.REFERENCE -> { { vm.toggleReference(l) } }
                        LayerOption.EDIT_TEXT -> { { vm.editText(l) } }
                        LayerOption.RASTERIZE -> { { vm.rasterize(l) } }
                        LayerOption.RECOLOUR -> { { vm.restyleStrokes(l, colour = true) } }
                        LayerOption.APPLY_BRUSH -> { { vm.restyleStrokes(l, colour = false) } }
                        LayerOption.MERGE_DOWN -> { { vm.mergeDown(l) } }
                        LayerOption.COMBINE_DOWN -> { { vm.combineDown(l) } }
                        LayerOption.MERGE_MASK -> { { vm.mergeMask(l) } }
                    }
                    Option(r.label, checked = r.switchOn, enabled = r.enabled, onClick = { act(); onDone() })
                    if (editableShape && r.option == LayerOption.RENAME) {
                        Option("Edit Shape", enabled = !l.locked, onClick = { onDone(); vm.openEditShape(l) })
                        Option("Rasterize Shape", enabled = !l.locked, onClick = { vm.rasterizeShapeLayer(l); onDone() })
                    }
                }
                for (action in actions) {
                    Option(action.label, onClick = { onDone(); vm.runPluginAction(action.key) })
                }
            }
            }
        }
    }
}

/** Room round the options for their shadow, which has faded out by then. */
private val OptionsShadowRoom = 52.dp

/**
 * The options' shadow round this (their size), as measured: black at 14%, blurred by 15 (its
 * sigma) and 6 lower. None of it under them: they show the frost alone when empty.
 */
private fun DrawScope.optionsShadow() {
    val outline = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(24.dp.toPx())))
    }
    val shadow = Path().apply {
        addRoundRect(RoundRect(0f, 6.dp.toPx(), size.width, size.height + 6.dp.toPx(), CornerRadius(24.dp.toPx())))
    }
    // the blur's radius for that sigma, as the platform's blur takes it
    val radius = (15.dp.toPx() - 0.5f) / 0.57735f
    drawIntoCanvas { canvas ->
        val paint = androidx.compose.ui.graphics.Paint().apply {
            color = Color.Black.copy(alpha = 0.14f)
            asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.clipPath(outline, ClipOp.Difference)
        canvas.drawPath(shadow, paint)
        canvas.restore()
    }
}

/**
 * Puts the options left of the panel, centred on the row tapped and kept below the top bar; the
 * popup is [room] bigger all round for their shadow.
 */
private class OptionsPlace(private val left: Int, private val rowY: Float, private val top: Int, private val room: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val bottom = (windowSize.height - popupContentSize.height + room).coerceAtLeast(top - room)
        val y = (rowY - popupContentSize.height / 2f).roundToInt().coerceIn(top - room, bottom)
        return IntOffset(anchorBounds.left + left - room, y)
    }
}

@Composable
private fun Option(label: String, checked: Boolean? = null, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).clickable(enabled = enabled, onClick = onClick).padding(start = 19.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when {
            !enabled -> Pc.MenuDisabled
            destructive -> Color(0xFFFF453A)
            else -> Pc.MenuInk
        }
        Text(label, color = color, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (checked == true) Icon(PcIcons.Tick, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}
/** A model's texture set's name, heading its meshes: 41 tall, 16 under the set before it. */
@Composable
internal fun SetHeader(name: String, first: Boolean) {
    Column {
        if (!first) Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(41.dp), contentAlignment = Alignment.TopStart) {
            Text(name, color = Pc.PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        }
    }
}

/** A model's mesh opened in the layers panel: its row darkened blue. */
private val SubmeshOpen = Color(0xFF25344A)
private val SubmeshTile = Color(0xFF434246)

/**
 * One of a model's meshes: its picture, its name, the arrow that opens it (its texture set's
 * layers under it) and its box (shown or hidden). A tap anywhere but the box opens or closes it.
 */
@Composable
internal fun SubmeshRow(vm: CanvasViewModel, index: Int, name: String, open: Boolean, shape: Shape) {
    val hidden = vm.submeshesHidden.getOrNull(index) == true
    val locked = vm.submeshesLocked.getOrNull(index) == true
    val picked = index in vm.activeSubmeshes
    // slid left it shows Lock (or Unlock); a little right, it joins the meshes picked
    val reach = with(LocalDensity.current) { (SwipeButton + 6.dp).toPx() }
    val pickReach = with(LocalDensity.current) { PickReach.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(false) }
    Box(Modifier.clipToBounds()) {
    if (offset.value < 0f) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            SwipeAction(
                icon = if (locked) PcIcons.Unlock else PcIcons.Lock,
                contentDescription = if (locked) "Unlock" else "Lock",
                fill = SwipeFill,
                ink = SwipeInk,
            ) {
                vm.toggleSubmeshLocked(index)
                opened = false
                scope.launch { offset.animateTo(0f, Motion.swipeSettle()) }
            }
        }
    }
    Row(
        Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
            .pointerInput(index, opened, reach) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + amount).coerceIn(-reach, if (opened) 0f else pickReach)) }
                    },
                    onDragEnd = {
                        if (offset.value > pickReach / 2f) {
                            vm.pickMoreSubmeshes(index)
                            opened = false
                        } else {
                            opened = offset.value < -reach / 2f
                        }
                        scope.launch { offset.animateTo(if (opened) -reach else 0f, Motion.swipeSettle()) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(if (opened) -reach else 0f, Motion.swipeSettle()) } },
                )
            }
            .fillMaxWidth().height(RowHeight).clip(shape).background(if (open || picked) SubmeshOpen else Pc.LayerRow)
            .clickable { vm.openSubmesh(index) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 4.dp).size(93.dp, 61.dp).clip(RoundedCornerShape(4.dp)).background(SubmeshTile), contentAlignment = Alignment.Center) {
            vm.submeshThumbnails[index]?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp)) }
        }
        Text(name, color = Pc.PanelTitle, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 29.dp, end = 6.dp))
        if (locked) Icon(PcIcons.PageLocked, contentDescription = "Locked", tint = Pc.PanelTitle, modifier = Modifier.size(16.dp))
        Box(Modifier.offset(x = 8.dp).size(44.dp, 51.dp).clickable { vm.openSubmesh(index) }, contentAlignment = Alignment.Center) {
            Icon(if (open) PcIcons.ChevronDown else PcIcons.ChevronRight, contentDescription = if (open) "Close" else "Open", tint = Pc.PanelTitle, modifier = Modifier.size(16.dp))
        }
        Box(Modifier.size(44.dp, 47.dp).clickable { vm.toggleSubmeshHidden(index) }, contentAlignment = Alignment.Center) {
            CheckBox(!hidden, Pc.CheckFill)
        }
        Spacer(Modifier.width(8.dp))
    }
    }
}

@Composable
internal fun BackgroundRow(vm: CanvasViewModel) {
    val bg = vm.background
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(17.dp)).background(Pc.BackgroundRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).fillMaxHeight().clickable { vm.pickBackground() }, verticalAlignment = Alignment.CenterVertically) {
            // hidden, the swatch shows no colour: a faint white over the row
            val swatch = if (bg.visible) Color(bg.r, bg.g, bg.b) else Color.White.copy(alpha = 0.06f)
            Box(Modifier.padding(start = 4.dp).size(92.dp, 42.dp).clip(RoundedCornerShape(14.dp)).background(swatch))
            Text("Background color", color = Pc.PanelTitle, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 14.dp))
        }
        Box(Modifier.size(44.dp, 41.dp).clickable { vm.toggleBackground() }, contentAlignment = Alignment.Center) {
            CheckBox(bg.visible, Pc.CheckFill)
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** A layer's visibility box: tapped, shown or hidden (or, with a layer shown alone, every layer again); held, shown alone (or every layer again). */
internal fun Modifier.visibilityTouch(vm: CanvasViewModel, l: LayerInfo): Modifier =
    pointerInput(l) { detectTapGestures(onTap = { vm.toggleVisible(l) }, onLongPress = { vm.toggleSolo(l) }) }

/**
 * Two fingers on a row: tapping (down and up together, still) do [onTap], swiped right together do
 * [onSwipeRight], held still do [onHold]. One finger's touches go on to the rest as they are; once
 * a second is down they are the two fingers' alone.
 */
internal fun Modifier.twoFingers(enabled: Boolean, onTap: () -> Unit, onSwipeRight: () -> Unit, onHold: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            var two: Long? = null
            var middle: Offset? = null
            var moved = false
            var done = false
            while (true) {
                val e = withTimeoutOrNull(TWO_FINGER_HOLD_MS / 4) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                }
                // the touches' clock (uptime), also while no event comes
                val now = e?.changes?.maxOfOrNull { it.uptimeMillis } ?: android.os.SystemClock.uptimeMillis()
                if (e != null) {
                    val fingers = e.changes.filter { it.pressed }
                    if (fingers.size >= 2) {
                        val between = (fingers[0].position + fingers[1].position) / 2f
                        val start = middle ?: between.also { middle = it }
                        if (two == null) two = now
                        e.changes.forEach { it.consume() }
                        val d = between - start
                        if (d.getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!done && d.x > TWO_FINGER_SWIPE.toPx() && d.x > 2f * kotlin.math.abs(d.y)) {
                            done = true
                            onSwipeRight()
                        }
                    } else if (two == null) {
                        e.changes.firstOrNull { it.id == first.id }?.let {
                            if ((it.position - first.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        }
                    }
                    if (e.changes.none { it.pressed }) {
                        val since = two
                        if (!done && since != null && !moved && now - first.uptimeMillis <= TWO_FINGER_TAP_MS) onTap()
                        break
                    }
                }
                // two fingers kept still long enough: held
                val since = two
                if (!done && !moved && since != null && now - since >= TWO_FINGER_HOLD_MS) {
                    done = true
                    onHold()
                }
            }
        }
    }

/** How long two fingers may stay down and still tap, milliseconds. */
private const val TWO_FINGER_TAP_MS = 400L

/** How long two fingers kept still on a row hold it, milliseconds. */
private const val TWO_FINGER_HOLD_MS = 500L

/** How far two fingers go right together to swipe a row. */
private val TWO_FINGER_SWIPE = 40.dp

/** Two rows being pinched: each moved a share of its finger's way ([SQUEEZE_FOLLOW]), the upper on top. */
internal class Squeeze(val upper: ULong, val lower: ULong, val upperBy: Float, val lowerBy: Float) {
    fun offset(id: ULong): Float? = when (id) {
        upper -> upperBy * SQUEEZE_FOLLOW
        lower -> lowerBy * SQUEEZE_FOLLOW
        else -> null
    }

    fun above(id: ULong): Float? = if (id == upper) 1f else null
}

/**
 * Two fingers put on two rows of the list (their layers by [rowAt], from a window y; [origin] the
 * list's place in the window) and pinched together to [PINCH_MERGE] of how far apart they were:
 * the list stays put from then, and as a finger lets go [onPinched] gets the two layers. Meanwhile
 * [onSqueeze] follows the two rows' fingers (none once they let go).
 */
internal fun Modifier.pinchRows(
    origin: () -> Offset,
    rowAt: (Float) -> ULong?,
    onSqueeze: (Squeeze?) -> Unit,
    onPinched: (ULong, ULong) -> Unit,
): Modifier = this.pointerInput(Unit) {
    val initial = androidx.compose.ui.input.pointer.PointerEventPass.Initial
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = initial)
        var ends: Pair<ULong, ULong>? = null
        // the fingers on the upper and lower rows, and where they went down
        var fingers: Pair<androidx.compose.ui.input.pointer.PointerId, androidx.compose.ui.input.pointer.PointerId>? = null
        var starts = 0f to 0f
        var apart = 0f
        var pinched = false
        while (true) {
            val e = awaitPointerEvent(initial)
            val down = e.changes.filter { it.pressed }
            if (ends == null && down.size == 2) {
                val (a, b) = down.sortedBy { it.position.y }
                val ra = rowAt(origin().y + a.position.y)
                val rb = rowAt(origin().y + b.position.y)
                if (ra != null && rb != null && ra != rb) {
                    ends = ra to rb
                    fingers = a.id to b.id
                    starts = a.position.y to b.position.y
                    apart = b.position.y - a.position.y
                }
            }
            val pair = ends
            val ids = fingers
            if (pair != null && ids != null && down.size >= 2) {
                val upper = down.firstOrNull { it.id == ids.first }?.position?.y ?: starts.first
                val lower = down.firstOrNull { it.id == ids.second }?.position?.y ?: starts.second
                if (kotlin.math.abs(lower - upper) <= apart * PINCH_MERGE) pinched = true
                if (pinched) e.changes.forEach { it.consume() }
                onSqueeze(Squeeze(pair.first, pair.second, upper - starts.first, lower - starts.second))
            }
            if (pair != null && down.size < 2) {
                onSqueeze(null)
                if (pinched) onPinched(pair.first, pair.second)
                break
            }
            if (down.isEmpty()) {
                onSqueeze(null)
                break
            }
        }
    }
}

/** How much of its finger's way a pinched row moves (as measured, about a quarter to a third). */
private const val SQUEEZE_FOLLOW = 0.3f

/** Two rows pinched to this share of how far apart they were merge (as measured: a pinch a third closed merges, opened again or not). */
private const val PINCH_MERGE = 0.65f
