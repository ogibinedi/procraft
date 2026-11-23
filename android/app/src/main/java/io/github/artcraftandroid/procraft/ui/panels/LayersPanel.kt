package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.core.LayerInfo
import io.github.artcraftandroid.procraft.core.LayerType
import io.github.artcraftandroid.procraft.core.RowDrop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LayersWidth = 384.dp
internal val RowHeight = 69.dp
/** A layer with a mask, and the mask, are a little shorter. */
private val MaskedRowHeight = 65.dp
private val RowGap = 1.dp
/** A dragged row this near an end of the list scrolls it, at most this far a second. */
private val DragEdge = 56.dp
private val DragScroll = 480.dp
/** A dragged row this far out past the panel's side is lifted out of it, to drop elsewhere. */
private val LiftReach = 24.dp
private val ListCorner = 8.dp
/** A group's row height, and how far in each level of group sits. */
private val GroupHeight = 44.dp
private val GroupIndent = 21.dp
private val GroupLine = Color(0xFF4E4E4E)
/**
 * A closed group's row is taller: its line (20 under the row's top and 20 over its bottom) opens
 * into a window 4 in from them, on its layers' rows drawn small; opening or closing, over this long.
 */
private val GroupClosedHeight = 82.dp
private val GroupLineInset = 20.dp
private val GroupWindowInset = 4.dp
private const val GROUP_TURN_MS = 150
/** The rows' width in the list, which a closed group's window draws its layers' rows to fit. */
private val ListWidth = 360.dp
/** A row's thumbnail (a group's line) held over by a dragged row long enough to take it. */
internal val HeldOutline = Color.White
private val MaskOwnerRow = Color(0xFF2C5281)
/** Swiping a row left shows these buttons, each this wide, this far apart. */
/** Compact icon-only swipe action button width (>= 44.dp touch target for smartphones). */
internal val SwipeButton = 56.dp
private val SwipeGap = 4.dp
internal val SwipeFill = Color(0xFF3F3F3F)
internal val SwipeInk = Color(0xFFC1C1C1)
private val SwipeDelete = Color(0xFFFF473D)
/** How far right a row slides to be picked. */
internal val PickReach = 72.dp
/** A picked row (the current one stays blue). */
private val PickedRow = Color(0xFF2C5281)
private val LockedLetter = Color(0xFF808080)
/** The options never go higher than this from the window's top (the top bar and a gap). */
internal val OptionsTop = 57.dp

/**
 * The layers panel: top layer first. Tap a layer to make it current, tap the current one for its
 * options, tap the blend letter for blend mode and opacity, hold and drag to reorder (out past the
 * panel's side, it is lifted out to drop on the canvas or, through the gallery, into another
 * artwork), swipe left for lock, duplicate and delete, swipe right to pick it to act on with the
 * current one (the title's menu then groups, merges, shares, duplicates or deletes them).
 */
@Composable
fun LayersPanel(vm: CanvasViewModel, arrowX: Dp, modifier: Modifier = Modifier, floating: FloatingFrame? = null) {
    var options by remember { mutableStateOf<ULong?>(null) }
    var blendFor by remember { mutableStateOf<ULong?>(null) }
    // blend modes opening under a layer: the rows under it go at once and the panel grows over where
    // they were; closing, it shrinks back to them and they come back a moment later. [blendAt] is
    // the row they open under (-1 when nothing is moving), [belowShown] the rows under it.
    var blendAt by remember { mutableIntStateOf(-1) }
    var belowShown by remember { mutableFloatStateOf(1f) }
    val blendScope = rememberCoroutineScope()
    var blendSettled by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun changeBlend(id: ULong?) {
        if (id == blendFor) return
        val shown = vm.layers.filter { !vm.hidden(it) }
        val at = listOfNotNull(blendFor, id).map { b -> shown.indexOfFirst { it.id == b } }.filter { it >= 0 }.minOrNull() ?: -1
        val opening = id != null
        blendFor = id
        blendAt = at
        belowShown = 0f
        blendSettled?.cancel()
        blendSettled = blendScope.launch {
            kotlinx.coroutines.delay(if (opening) Motion.BLEND_OPEN_MS else Motion.BLEND_CLOSE_MS + Motion.BLEND_ROWS_WAIT_MS)
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = Motion.blendRowsBack()) { v, _ -> belowShown = v }
            blendAt = -1
        }
    }
    var renaming by remember { mutableStateOf<ULong?>(null) }
    var swiped by remember { mutableStateOf<ULong?>(null) }
    var titleMenu by remember { mutableStateOf(false) }
    // the + offering plugins' kinds of layer beside a plain one
    var addMenu by remember { mutableStateOf(false) }
    var deletingChosen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var dragId by remember { mutableStateOf<ULong?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // the rows that go with the dragged one (its mask, a group's layers)
    var dragUnit by remember { mutableStateOf(emptySet<ULong>()) }
    // where the dragged row lands if let go now, and the row it has been held over long enough to
    // go onto it (its thumbnail outlined)
    var dropAt by remember { mutableStateOf<RowDrop?>(null) }
    var heldOver by remember { mutableStateOf<ULong?>(null) }
    // where each row is in the window: for dropping a dragged row and placing the options
    val rows = remember { mutableMapOf<ULong, Rect>() }
    // where layers held out of a panel drop: on its rows, within its box
    DisposableEffect(rows) {
        vm.layerRows = rows
        onDispose {
            // (a panel torn off shows floating as it goes from where it hung: that one's rows stay)
            if (vm.layerRows === rows) {
                vm.layerRows = emptyMap()
                vm.layersBox = null
            }
        }
    }

    // a model's meshes are pictured as the model is seen when the panel opens
    if (vm.modelOpen) LaunchedEffect(Unit) { vm.refreshSubmeshThumbnails() }
    // floating in a window of its own: as wide as the window, no arrow, a strip over it and under it
    val side = if (floating != null) ArrowSide.NONE else ArrowSide.TOP
    Popover(floating?.width ?: LayersWidth, arrowX, modifier.onGloballyPositioned { vm.layersBox = it.boundsInWindow() }, side = side) {
        floating?.let { FloatingTop(it) }
        // kept while they fade out
        val open = options?.let { id -> vm.layers.firstOrNull { it.id == id }?.let { l -> rows[id]?.let { l to it } } }
        val last = remember { arrayOfNulls<Pair<LayerInfo, Rect>>(1) }
        if (open != null) last[0] = open
        last[0]?.let { (l, row) -> LayerOptions(open != null, vm, l, row.center.y, onRename = { renaming = l.id }, onDone = { options = null }) }
        Box {
            PanelHeader(
                "Layers",
                chevronOn = vm.anyPicked,
                onTitle = { titleMenu = !titleMenu },
                menu = {
                    LayersTitleMenu(titleMenu, vm, onDismiss = { titleMenu = false }, onShare = {
                        scope.launch {
                            val files = vm.chosenLayerFiles()
                            if (files.isNotEmpty()) shareFiles(context, files, "image/png")
                        }
                    }, onDelete = { deletingChosen = true })
                },
            ) {
                val kinds = vm.layerKinds()
                Box(
                    Modifier.padding(top = 12.dp).size(40.dp, 41.dp).clickable { if (kinds.isEmpty()) vm.addLayer() else addMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PcIcons.PlusMedium, contentDescription = "New layer", tint = Pc.PanelGlyph, modifier = Modifier.requiredSize(41.dp))
                    NewLayerMenu(addMenu && kinds.isNotEmpty(), kinds, vm, onDismiss = { addMenu = false })
                }
            }
            // hanging from its button, its grabber tears it off into a window of its own, while a plugin lets it
            if (!LocalCompactSheet.current && floating == null && io.github.artcraftandroid.procraft.core.FloatingPanel.LAYERS in Pc.floatingPanels) {
                PanelGrabber(vm, io.github.artcraftandroid.procraft.core.FloatingPanel.LAYERS)
            }
        }
        // Pinned rename bar directly beneath the header so renaming any layer or layer group
        // stays full-width, focus-stable, and cleanly above the soft keyboard.
        val renamingLayer = renaming?.let { id -> vm.layers.firstOrNull { it.id == id } }
        if (renamingLayer != null) {
            LayerRenameBar(
                layer = renamingLayer,
                onCancel = { renaming = null },
                onConfirm = { newName ->
                    vm.rename(renamingLayer, newName)
                    renaming = null
                },
            )
        }
        // a layer added at the top of what shows comes in there, the rows under it sliding down,
        // rather than the list holding its place and leaving the new row out of sight above
        val list = rememberLazyListState()
        val ids = vm.layers.filter { !vm.hidden(it) }.map { it.id }
        LaunchedEffect(renaming) {
            val targetId = renaming ?: return@LaunchedEffect
            val idx = ids.indexOf(targetId)
            if (idx >= 0 && !vm.modelOpen) {
                runCatching { list.animateScrollToItem(idx) }
            }
        }
        val lastIds = remember { arrayOfNulls<List<ULong>>(1) }
        val before = lastIds[0]
        if (before != null && ids != before) {
            val added = ids.indices.firstOrNull { ids[it] !in before }
            if (added != null && added <= list.firstVisibleItemIndex) list.requestScrollToItem(added)
        }
        lastIds[0] = ids
        var listAt by remember { mutableStateOf(Offset.Zero) }
        val listBox = remember { arrayOf(Rect.Zero) }
        // held near an end of the list it scrolls, faster the nearer, the row staying under the finger
        val rowGap = with(LocalDensity.current) { RowGap.toPx() }
        val dragEdge = with(LocalDensity.current) { DragEdge.toPx() }
        val dragScroll = with(LocalDensity.current) { DragScroll.toPx() }
        val liftReach = with(LocalDensity.current) { LiftReach.toPx() }
        LaunchedEffect(dragId) {
            val id = dragId ?: return@LaunchedEffect
            var last = androidx.compose.runtime.withFrameNanos { it }
            // the row under the dragged one's middle, and since when
            var over: ULong? = null
            var overSince = last
            // placed again only when it moves or a hold comes due
            var placedFor: Pair<Float, ULong?>? = null
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val seconds = (now - last) / 1e9f
                last = now
                val y = (rows[id] ?: continue).center.y + dragOffset
                // held over one row a moment, it goes onto it when let go
                val under = rows.entries.firstOrNull { (k, r) -> k !in dragUnit && y >= r.top && y < r.bottom }?.key
                if (under != over) {
                    over = under
                    overSince = now
                }
                heldOver = over.takeIf { (now - overSince) / 1e9f >= vm.rowHoldSeconds }
                if (placedFor != y to heldOver) {
                    placedFor = y to heldOver
                    dropAt = vm.rowDrop(id, rows, y, heldOver)
                }
                val box = listBox[0]
                val towards = when {
                    y < box.top + dragEdge -> (y - box.top - dragEdge) / dragEdge
                    y > box.bottom - dragEdge -> (y - box.bottom + dragEdge) / dragEdge
                    else -> 0f
                }.coerceIn(-1f, 1f)
                if (towards != 0f) dragOffset += list.scrollBy(towards * dragScroll * seconds)
            }
        }
        // two rows being pinched, each following its finger part of the way
        var squeeze by remember { mutableStateOf<Squeeze?>(null) }
        LazyColumn(
            // (floating, it fills its window)
            Modifier.weight(1f, fill = floating != null).padding(horizontal = 12.dp)
                .onGloballyPositioned {
                    listAt = it.positionInWindow()
                    listBox[0] = it.boundsInWindow()
                }
                // two rows pinched together merge with every layer between
                .pinchRows({ listAt }, { y -> rows.entries.firstOrNull { y >= it.value.top && y <= it.value.bottom }?.key }, { squeeze = it }) { a, b ->
                    vm.mergeBetween(a, b)
                },
            state = list,
            verticalArrangement = Arrangement.spacedBy(RowGap),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            // a closed group's layers are left out
            val shown = vm.layers.filter { !vm.hidden(it) }
            // a layer's row: in the list, or on a model under its submesh, indented
            val layerItem: @Composable LazyItemScope.(Int, LayerInfo, Shape, Dp, String?) -> Unit = { index, l, shape, indent, subtitle ->
                val dragging = l.id in dragUnit
                DisposableEffect(l.id) { onDispose { rows.remove(l.id) } }
                // scrolled out of the list as it is dragged, the row stays, its gesture going on
                val pinnable = androidx.compose.ui.layout.LocalPinnableContainer.current
                val pinned = remember { arrayOfNulls<androidx.compose.ui.layout.PinnableContainer.PinnedHandle>(1) }
                val aside by androidx.compose.animation.core.animateFloatAsState(
                    remember(l.id) { androidx.compose.runtime.derivedStateOf { if (dragId == null) 0f else makingWay(l.id, dragUnit, dropAt, rows, rowGap) } }.value,
                    Motion.rowFade(),
                )
                Column(
                    Modifier.animateItem(Motion.rowFade(), if (dragging || blendAt >= 0) null else Motion.rowMove(), Motion.rowFade())
                        .onGloballyPositioned { rows[l.id] = it.boundsInWindow() }
                        .zIndex(if (dragging) 1f else squeeze?.above(l.id) ?: 0f).graphicsLayer {
                            translationY = if (dragging) dragOffset else (squeeze?.offset(l.id) ?: 0f) + aside
                            alpha = if (blendAt in 0 until index) belowShown else 1f
                        }
                        .twoFingers(
                            l.kind == LayerType.PAINT,
                            onTap = {
                                // two fingers tapped on a layer (not a group or mask): its opacity, dragged across the canvas
                                vm.selectLayer(l.id)
                                vm.startLayerOpacity()
                            },
                            // swiped right, its Alpha Lock on or off; held, its contents selected
                            onSwipeRight = { vm.toggleAlphaLock(l) },
                            onHold = { vm.selectLayerContents(l) },
                        )
                        .pointerInput(l.id) {
                            if (l.kind == LayerType.MASK) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragId = l.id
                                    dragOffset = 0f
                                    dragUnit = vm.movingWith(l) + l.id
                                    dropAt = null
                                    heldOver = null
                                    swiped = null
                                    pinned[0] = pinnable?.pin()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    // dragged out past the panel's side, the row (with what goes with it) is
                                    // lifted out, held by the finger from then on wherever it goes
                                    val at = vm.fingersAt[change.id]
                                    val box = vm.layersBox
                                    if (at != null && box != null && (at.x < box.left - liftReach || at.x > box.right + liftReach)) {
                                        val corner = (rows[l.id]?.topLeft ?: at) + Offset(0f, dragOffset)
                                        vm.liftLayers(l, change.id, at, corner)
                                    }
                                },
                                onDragEnd = {
                                    // where its middle came to rest, placed as the core places it
                                    rows[l.id]?.let { vm.dropRow(l, vm.rowDrop(l.id, rows, it.center.y + dragOffset, heldOver)) }
                                    dragId = null
                                    dragOffset = 0f
                                    dragUnit = emptySet()
                                    dropAt = null
                                    heldOver = null
                                    pinned[0]?.release()
                                    pinned[0] = null
                                },
                                onDragCancel = {
                                    dragId = null
                                    dragOffset = 0f
                                    dragUnit = emptySet()
                                    dropAt = null
                                    heldOver = null
                                    pinned[0]?.release()
                                    pinned[0] = null
                                },
                            )
                        },
                ) {
                    val onTap = {
                        swiped = null
                        // on a model the row means the layer itself: its colour, no channel's tile
                        if (vm.modelOpen && vm.channelTileChosen && l.kind != LayerType.GROUP) {
                            vm.leaveChannelTiles()
                            if (!l.current) vm.selectLayer(l.id)
                        } else if (vm.anyPicked) {
                            // picks give way to the layer tapped, the current one too
                            vm.selectLayer(l.id)
                            options = null
                        } else if (l.current) {
                            options = if (options == l.id) null else l.id
                            changeBlend(null)
                        } else {
                            vm.selectLayer(l.id)
                            options = null
                        }
                    }
                    val name: @Composable (Color, Modifier) -> Unit = { ink, m ->
                        Text(l.name, color = ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
                    }
                    SwipeRow(vm, l, open = swiped == l.id, enabled = dragId == null, onOpen = { swiped = if (it) l.id else null }, onPick = { vm.pickLayer(l) }) {
                        // held over long enough to go onto it: its thumbnail (a group's line) outlined
                        val held = (dropAt as? RowDrop.Onto)?.target == l.id
                        if (l.kind == LayerType.GROUP) {
                            GroupRow(vm, l, shape, name, onTap, indent, held) { vm.toggleCollapsed(l) }
                        } else LayerRow(
                            vm,
                            l,
                            shape,
                            name,
                            // the current mask's layer, or the current layer's mask
                            owner = vm.layers.any {
                                it.current && (it.kind == LayerType.MASK && it.parent == l.id || l.kind == LayerType.MASK && l.parent == it.id)
                            },
                            onTap = onTap,
                            onBlend = {
                                changeBlend(if (blendFor == l.id) null else l.id)
                                options = null
                            },
                            indent = indent,
                            subtitle = subtitle,
                            // on a model: its channels, and the row giving its blue to the channel painted
                            onCube = if (vm.modelOpen && l.kind != LayerType.MASK) ({ vm.toggleChannelTray(l.id) }) else null,
                            cubeLit = vm.modelOpen && l.current && vm.channelTileChosen,
                            selected = l.current && !(vm.modelOpen && vm.channelTileChosen),
                            held = held,
                        )
                    }
                    if (vm.modelOpen && vm.channelTrayFor == l.id) ChannelTray(vm, l)
                    // the blend modes open under the row, the panel growing as they fade in, and close back
                    androidx.compose.animation.AnimatedVisibility(
                        blendFor == l.id,
                        // held at the top, uncovered as the panel grows and covered as it shrinks
                        enter = androidx.compose.animation.expandVertically(Motion.blendOpenSize(), expandFrom = Alignment.Top) +
                            androidx.compose.animation.fadeIn(Motion.blendOpen()),
                        exit = androidx.compose.animation.shrinkVertically(Motion.blendCloseSize(), shrinkTowards = Alignment.Top) +
                            androidx.compose.animation.fadeOut(Motion.blendClose()),
                    ) { BlendEditor(vm, l, open = blendFor == l.id) }
                }
            }
            if (!vm.modelOpen) {
                itemsIndexed(shown, key = { _, l -> l.id.toLong() }) { index, l ->
                    // the list's ends round; the rows between are square
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) ListCorner else 0.dp,
                        topEnd = if (index == 0) ListCorner else 0.dp,
                        bottomStart = if (index == shown.size - 1) ListCorner else 0.dp,
                        bottomEnd = if (index == shown.size - 1) ListCorner else 0.dp,
                    )
                    layerItem(index, l, shape, 0.dp, if (vm.isEditableShapeLayer(l.id)) "Shape" else null)
                }
            } else {
                // on a model: each texture set headed by its name, its meshes under it; the open
                // mesh's texture set's layers under that, shared by every mesh in the set
                vm.textureSets.forEachIndexed { set, setName ->
                    val meshes = vm.submeshes.withIndex().filter { it.value.textureSet.toInt() == set }
                    if (meshes.isEmpty()) return@forEachIndexed
                    item(key = "set $set") { SetHeader(setName, first = set == 0) }
                    meshes.forEachIndexed { k, (m, info) ->
                        val open = vm.openSubmesh == m
                        val lastMesh = k == meshes.size - 1
                        val layersUnder = open && set == vm.textureSet && shown.isNotEmpty()
                        item(key = "mesh $m") {
                            SubmeshRow(
                                vm,
                                m,
                                info.name,
                                open = layersUnder,
                                shape = RoundedCornerShape(
                                    topStart = if (k == 0) ListCorner else 0.dp,
                                    topEnd = if (k == 0) ListCorner else 0.dp,
                                    bottomStart = if (lastMesh && !layersUnder) ListCorner else 0.dp,
                                    bottomEnd = if (lastMesh && !layersUnder) ListCorner else 0.dp,
                                ),
                            )
                        }
                        if (layersUnder) {
                            itemsIndexed(shown, key = { _, l -> "layer ${l.id}" }) { index, l ->
                                val end = lastMesh && index == shown.size - 1
                                val shape = RoundedCornerShape(bottomStart = if (end) ListCorner else 0.dp, bottomEnd = if (end) ListCorner else 0.dp)
                                layerItem(index, l, shape, GroupIndent, if (meshes.size > 1) "Shared" else null)
                            }
                        }
                    }
                }
            }
            // it moves down and up with the rows above it
            if (!vm.modelOpen) item(key = "background") {
                Box(
                    Modifier.animateItem(null, if (dragId != null || blendAt >= 0) null else Motion.rowMove(), null)
                        .padding(top = 10.dp).graphicsLayer { alpha = if (blendAt >= 0) belowShown else 1f },
                ) { BackgroundRow(vm) }
            }
        }
        floating?.let { FloatingBottom(it) }
    }
    // Delete on a locked layer's row: why not, OK alone
    Alert(
        if (vm.lockedDeleteTold) Unit else null,
        title = { "Locked layer selection" },
        message = { "A locked layer can’t be deleted. Unlock it first." },
        action = "OK",
        icon = PcIcons.PageLocked,
        destructive = false,
        alone = true,
        onCancel = { vm.lockedDeleteTold = false },
        onAction = { vm.lockedDeleteTold = false },
    )
    // the title menu's Delete asks first
    Alert(
        if (deletingChosen) Unit else null,
        title = { "Delete these layers?" },
        message = { "Undo brings them back." },
        action = "Delete",
        icon = PcIcons.Trash,
        onCancel = { deletingChosen = false },
        onAction = {
            // as measured, a locked one among them stops it, said why
            if (vm.chosenLocked()) vm.lockedDeleteTold = true else vm.deletePicked()
            deletingChosen = false
        },
    )
}

/**
 * The title's menu, under it: Select All; Group and Merge (with layers picked); Share and
 * Duplicate; Delete.
 */
@Composable
private fun LayersTitleMenu(open: Boolean, vm: CanvasViewModel, onDismiss: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val picked = vm.anyPicked
    // mostly over the panel, whose rows the canvas's frosting doesn't blur: nearly solid
    LibraryMenu(
        open,
        androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f),
        MenuPlace { a, size -> IntOffset(a.center.x - size.width / 2, a.bottom) },
        onDismiss,
        overPanel = true,
    ) {
        MenuItem(PcIcons.MenuSelectAll, "Select All") {
            onDismiss()
            vm.pickAllLayers()
        }
        MenuDivider()
        if (picked) {
            MenuItem(PcIcons.MenuGroup, "Group") {
                onDismiss()
                vm.groupPicked()
            }
            MenuItem(PcIcons.MenuMerge, "Merge") {
                onDismiss()
                vm.mergePicked()
            }
            MenuDivider()
        }
        MenuItem(PcIcons.MenuShare, "Share") {
            onDismiss()
            onShare()
        }
        MenuItem(PcIcons.MenuDuplicate, "Duplicate") {
            onDismiss()
            vm.duplicatePicked()
        }
        MenuDivider()
        MenuItem(PcIcons.MenuTrash, "Delete", Pc.MenuDanger) {
            onDismiss()
            onDelete()
        }
    }
}

/** The + with plugins' kinds of layer on: a plain layer, or one of each kind, named as the plugin names it. */
@Composable
private fun NewLayerMenu(open: Boolean, kinds: List<io.github.artcraftandroid.procraft.core.PluginKind>, vm: CanvasViewModel, onDismiss: () -> Unit) {
    LibraryMenu(
        open,
        androidx.compose.ui.graphics.TransformOrigin(1f, 0f),
        MenuPlace { a, size -> IntOffset(a.right - size.width, a.bottom) },
        onDismiss,
        overPanel = true,
    ) {
        MenuItem(PcIcons.MenuNewLayer, "Layer") {
            onDismiss()
            vm.addLayer()
        }
        for (kind in kinds) {
            // beside its badge, as its layers' thumbnails show it (a plug when it draws none)
            val badge = remember(kind) { io.github.artcraftandroid.procraft.core.pluginKindBadge(kind) }
            val ink = Pc.MenuInk
            MenuItem(kind.label, ink, null, onClick = {
                onDismiss()
                vm.addPluginLayer(kind.key)
            }) {
                androidx.compose.foundation.Canvas(Modifier.size(MenuBadgeWidth, MenuBadgeHeight)) { badgeDrawing(badge, ink, MenuBadgeLine.toPx()) }
            }
        }
    }
}

/** A kind's badge as a menu row's glyph: its box 1.6 times a thumbnail's (18 by 14), its lines as thick as the menu glyphs'. */
private val MenuBadgeWidth = 28.8.dp
private val MenuBadgeHeight = 22.4.dp
private val MenuBadgeLine = 2.4.dp

/**
 * How far row [id] stands aside while the rows of [unit] are dragged: the rows between where they
 * were and where they land ([place]) take the room they left, so the gap shows where they will
 * land; held onto a row ([RowDrop.Onto]), or over their own place, none moves.
 */
internal fun makingWay(id: ULong, unit: Set<ULong>, place: RowDrop?, rows: Map<ULong, Rect>, gap: Float): Float {
    if (id in unit) return 0f
    val row = rows[id] ?: return 0f
    val moved = unit.mapNotNull { rows[it] }
    if (moved.isEmpty()) return 0f
    val (top, bottom) = moved.minOf { it.top } to moved.maxOf { it.bottom }
    val room = bottom - top + gap
    // the line between the rows they land between
    val slot = when (place) {
        is RowDrop.Above -> rows[place.target]?.top ?: return 0f
        is RowDrop.Below -> rows[place.target]?.bottom ?: return 0f
        is RowDrop.Bottom -> Float.POSITIVE_INFINITY
        else -> return 0f
    }
    return when {
        row.bottom <= top && row.top >= slot -> room
        row.top >= bottom && row.bottom <= slot -> -room
        else -> 0f
    }
}

/**
 * A row that slides left to show Lock, Duplicate (not for a mask) and Delete, or a little right to
 * pick it ([onPick]), springing back.
 */
@Composable
private fun SwipeRow(
    vm: CanvasViewModel,
    l: LayerInfo,
    open: Boolean,
    enabled: Boolean,
    onOpen: (Boolean) -> Unit,
    onPick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val buttons = if (l.kind == LayerType.MASK) 2 else 3
    // the row slides 6 past the buttons
    val reach = with(LocalDensity.current) { (SwipeButton * buttons + SwipeGap * (buttons - 1) + 6.dp).toPx() }
    val pickReach = with(LocalDensity.current) { PickReach.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open, reach) { offset.animateTo(if (open) -reach else 0f, Motion.swipeSettle()) }
    // the list only clips top and bottom; a slid row stops at its edge
    Box(Modifier.clipToBounds()) {
        if (offset.value < 0f) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.spacedBy(SwipeGap, Alignment.End)) {
                SwipeAction(
                    icon = if (l.locked) PcIcons.Unlock else PcIcons.Lock,
                    contentDescription = if (l.locked) "Unlock" else "Lock",
                    fill = SwipeFill,
                    ink = SwipeInk,
                ) {
                    vm.toggleLocked(l)
                    onOpen(false)
                }
                if (l.kind != LayerType.MASK) {
                    SwipeAction(
                        icon = PcIcons.Duplicate,
                        contentDescription = "Duplicate",
                        fill = SwipeFill,
                        ink = SwipeInk,
                    ) {
                        vm.duplicate(l)
                        onOpen(false)
                    }
                }
                // the only layer can't go: it is cleared instead, using the same Trash icon as other layers
                val alone = vm.layers.count { it.kind != LayerType.MASK } == 1
                SwipeAction(
                    icon = PcIcons.Trash,
                    contentDescription = if (alone) "Clear" else "Delete",
                    fill = SwipeDelete,
                    ink = Color.White,
                ) {
                    if (alone) vm.clear(l) else vm.deleteAsking(l)
                    onOpen(false)
                }
            }
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.pointerInput(l.id, open, enabled, reach) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        // closed, it goes right as far as picking takes
                        scope.launch { offset.snapTo((offset.value + amount).coerceIn(-reach, if (open) 0f else pickReach)) }
                    },
                    onDragEnd = {
                        if (offset.value > pickReach / 2f) {
                            onPick()
                            scope.launch { offset.animateTo(0f, Motion.swipeSettle()) }
                            return@detectHorizontalDragGestures
                        }
                        val opened = offset.value < -reach / 2f
                        if (opened == open) scope.launch { offset.animateTo(if (open) -reach else 0f, Motion.swipeSettle()) } else onOpen(opened)
                    },
                    onDragCancel = { scope.launch { offset.animateTo(if (open) -reach else 0f, Motion.swipeSettle()) } },
                )
            },
        ) { content() }
    }
}

@Composable
internal fun SwipeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    fill: Color,
    ink: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier.width(SwipeButton).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(fill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = ink, modifier = Modifier.size(22.dp))
    }
}

/** A panel's title row, [height] tall: the title [start] in, its menu chevron, and [action] [end] from the right. */
@Composable
internal fun PanelHeader(
    title: String,
    start: Dp = 24.dp,
    end: Dp = 18.dp,
    height: Dp = 64.dp,
    chevron: Boolean = true,
    chevronOn: Boolean = false,
    onTitle: (() -> Unit)? = null,
    menu: @Composable () -> Unit = {},
    action: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Row(
            Modifier.align(Alignment.TopStart).padding(start = start, top = 19.dp)
                .then(if (onTitle != null) Modifier.clickable(interactionSource = null, indication = null, onClick = onTitle) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Pc.PanelTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            menu()
            if (chevron) {
                Box(
                    Modifier.padding(start = 10.dp).offset(y = (-1.5).dp).size(17.dp).clip(CircleShape)
                        .background(if (chevronOn) Pc.RowSelected else Pc.ChevronDisc),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PcIcons.ChevronDown, contentDescription = null, tint = Pc.ChevronGlyph, modifier = Modifier.size(13.dp))
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = end)) { action() }
    }
}

/**
 * Pinned rename editor shown directly beneath the Layers header for layers, layer groups, and
 * masks so the input field never hides behind the on-screen keyboard or gets recycled by scrolling.
 */
@Composable
private fun LayerRenameBar(
    layer: LayerInfo,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    BackHandler(onBack = onCancel)
    val label = when (layer.kind) {
        LayerType.GROUP -> "Rename Group"
        LayerType.MASK -> "Rename Mask"
        else -> "Rename Layer"
    }
    var value by remember(layer.id) { mutableStateOf(TextFieldValue(layer.name, TextRange(0, layer.name.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        keyboard?.hide()
        onConfirm(value.text.trim().ifEmpty { layer.name })
    }
    fun cancel() {
        keyboard?.hide()
        onCancel()
    }
    LaunchedEffect(layer.id) {
        kotlinx.coroutines.delay(60)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val selection = TextSelectionColors(handleColor = Pc.Accent, backgroundColor = Pc.Accent.copy(alpha = 0.3f))
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            color = Pc.TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Pc.Row)
                    .border(1.5.dp, Pc.Accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                CompositionLocalProvider(LocalTextSelectionColors provides selection) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Pc.PanelTitle, fontSize = 16.sp),
                        cursorBrush = SolidColor(Pc.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                }
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Pc.Row)
                    .clickable(onClick = ::cancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PcIcons.Cross, contentDescription = "Cancel rename", tint = Pc.PanelTitle, modifier = Modifier.size(15.dp))
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Pc.Accent)
                    .clickable(onClick = ::submit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PcIcons.DoneTick, contentDescription = "Save name", tint = Color.White, modifier = Modifier.requiredSize(34.dp))
            }
        }
    }
}

/** A row's name, where it always is; locked, a small padlock in the gap before it. */
@Composable
private fun LockedName(l: LayerInfo, ink: Color, name: @Composable (Color, Modifier) -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        // the padlock's glyph ends 12 short of the name
        if (l.locked) Icon(PcIcons.LockRow, contentDescription = "Locked", tint = ink, modifier = Modifier.offset(x = 3.dp).size(17.dp))
        name(ink, Modifier.padding(start = 29.dp, end = 6.dp))
    }
}

/**
 * A group's row: where a thumbnail would be, a line while it is open or, closed (the row taller),
 * a window on its layers' rows drawn small; its name, its open or closed arrow and its box. Held
 * over by a dragged row long enough to take it ([held]), the line or window is outlined.
 */
@Composable
private fun GroupRow(
    vm: CanvasViewModel,
    l: LayerInfo,
    shape: Shape,
    name: @Composable (Color, Modifier) -> Unit,
    onTap: () -> Unit,
    indent: Dp,
    held: Boolean,
    onToggle: () -> Unit,
) {
    val height by androidx.compose.animation.core.animateDpAsState(
        if (l.collapsed) GroupClosedHeight else GroupHeight,
        androidx.compose.animation.core.tween(GROUP_TURN_MS, easing = androidx.compose.animation.core.LinearEasing),
        label = "group row",
    )
    // 0 open, 1 closed: the line's 20 above and below become 4, the line the window between
    val closed = ((height - GroupHeight) / (GroupClosedHeight - GroupHeight)).coerceIn(0f, 1f)
    val inset = androidx.compose.ui.unit.lerp(GroupLineInset, GroupWindowInset, closed)
    val window = RoundedCornerShape(androidx.compose.ui.unit.lerp(2.dp, 4.dp, closed))
    Row(
        Modifier.fillMaxWidth().height(height).clip(shape)
            .background(if (l.current) Pc.RowSelected else if (l.picked) PickedRow else Pc.LayerRow)
            .labelled(l)
            .clickable(onClick = onTap)
            .padding(start = indent + GroupIndent * l.depth.toInt()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (l.current || l.picked) Color.White else Pc.PanelTitle
        Box(
            Modifier.padding(start = 4.dp).size(93.dp, height - inset * 2).clip(window).background(GroupLine)
                .then(if (held) Modifier.border(2.dp, HeldOutline, window) else Modifier),
        ) {
            if (closed > 0f) GroupContents(vm, l)
        }
        LockedName(l, ink, name, Modifier.weight(1f))
        Box(Modifier.offset(x = 8.dp).size(44.dp, GroupHeight).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Icon(if (l.collapsed) PcIcons.ChevronRight else PcIcons.ChevronDown, contentDescription = if (l.collapsed) "Open" else "Close", tint = ink, modifier = Modifier.size(16.dp))
        }
        Box(Modifier.size(44.dp, GroupHeight).visibilityTouch(vm, l), contentAlignment = Alignment.Center) {
            CheckBox(l.visible && !l.soloedOut, if (l.current || l.picked) Color.White else Pc.CheckFill)
        }
        Spacer(Modifier.width(8.dp))
    }
}

/**
 * A closed group's layers' rows as the panel would list them open, drawn small to fit its window's
 * width less 1 on each side, from its top: their fills, thumbnails, names, blend letters and boxes.
 */
@Composable
private fun GroupContents(vm: CanvasViewModel, group: LayerInfo) {
    // a group closed inside it keeps its own layers out
    val inside = vm.layers.filter { l ->
        vm.isInside(l, group.id) && vm.layers.none { it.kind == LayerType.GROUP && it.collapsed && it.id != group.id && vm.isInside(l, it.id) && vm.isInside(it, group.id) }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = (maxWidth - 2.dp) / ListWidth
        Column(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredWidth(ListWidth)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    translationX = 1.dp.toPx()
                    translationY = 1.dp.toPx()
                },
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            inside.forEach { MiniRow(vm, it, (it.depth.toInt() - group.depth.toInt() - 1).coerceAtLeast(0)) }
        }
    }
}

/** A row of a closed group's window, as its row in the list looks, without its controls. */
@Composable
private fun MiniRow(vm: CanvasViewModel, l: LayerInfo, depth: Int) {
    val mask = l.kind == LayerType.MASK
    val masked = vm.hasMask(l)
    val group = l.kind == LayerType.GROUP
    val ink = if (l.current || l.picked) Color.White else Pc.PanelTitle
    Row(
        Modifier.fillMaxWidth().height(if (group) GroupHeight else if (mask || masked) MaskedRowHeight else RowHeight)
            .background(if (l.current) Pc.RowSelected else if (l.picked) PickedRow else Pc.LayerRow)
            .labelled(l)
            .padding(start = GroupIndent * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group) {
            Box(Modifier.padding(start = 4.dp).size(93.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(GroupLine))
        } else {
            val corner = 4.dp
            val thumbShape = when {
                mask -> RoundedCornerShape(topStart = corner, topEnd = corner)
                masked -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
                else -> RoundedCornerShape(corner)
            }
            Box(Modifier.padding(start = 4.dp).offset(y = if (mask) 2.dp else if (masked) (-2).dp else 0.dp)) { Thumb(vm, l, thumbShape) }
        }
        Text(l.name, color = ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 29.dp, end = 6.dp))
        if (group) {
            Box(Modifier.offset(x = 8.dp).size(44.dp, GroupHeight), contentAlignment = Alignment.Center) {
                Icon(if (l.collapsed) PcIcons.ChevronRight else PcIcons.ChevronDown, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            }
        } else if (mask) {
            Spacer(Modifier.width(36.dp))
        } else {
            Box(Modifier.offset(x = 8.dp).size(44.dp, 47.dp), contentAlignment = Alignment.Center) {
                Text(blendCode(vm, l.blend), color = if (l.locked && !l.current && !l.picked) LockedLetter else ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.size(44.dp, 47.dp), contentAlignment = Alignment.Center) {
            CheckBox(l.visible && !l.soloedOut, if (l.current || l.picked) Color.White else Pc.CheckFill)
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
internal fun LayerRow(
    vm: CanvasViewModel,
    l: LayerInfo,
    shape: Shape,
    name: @Composable (Color, Modifier) -> Unit,
    owner: Boolean,
    onTap: () -> Unit,
    onBlend: () -> Unit,
    indent: Dp = 0.dp,
    subtitle: String? = null,
    onCube: (() -> Unit)? = null,
    cubeLit: Boolean = false,
    selected: Boolean = l.current,
    held: Boolean = false,
) {
    val mask = l.kind == LayerType.MASK
    val masked = vm.hasMask(l)
    Row(
        Modifier.fillMaxWidth().height(if (mask || masked) MaskedRowHeight else RowHeight).clip(shape)
            // the layer a selected mask belongs to shows a darker blue
            .background(if (selected) Pc.RowSelected else if (l.picked) PickedRow else if (owner) MaskOwnerRow else Pc.LayerRow)
            .labelled(l)
            .clickable(onClick = onTap)
            .padding(start = indent + (if (l.clipped) 18.dp else 0.dp) + GroupIndent * l.depth.toInt()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (selected || l.picked) Color.White else Pc.PanelTitle
        if (l.clipped) Icon(PcIcons.Clip, contentDescription = "Clipped", tint = ink, modifier = Modifier.size(16.dp))
        // a mask's thumbnail and its layer's join into one, rounded only at the outer corners
        val corner = 4.dp
        val thumbShape = when {
            mask -> RoundedCornerShape(topStart = corner, topEnd = corner)
            masked -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
            else -> RoundedCornerShape(corner)
        }
        val shift = when {
            mask -> 2.dp
            masked -> (-2).dp
            else -> 0.dp
        }
        Box(Modifier.padding(start = 4.dp).offset(y = shift)) { Thumb(vm, l, thumbShape, held) }
        // what the layer is, under its name (which stays in the middle): the reference layer, one
        // drawing assist is on for, a private one, a model's layer shared by its texture set's meshes
        val tag = when {
            l.reference && l.assisted -> "Assisted Reference"
            l.reference -> subtitle?.let { "$it Reference" } ?: "Reference"
            subtitle != null -> subtitle
            l.assisted -> "Assisted"
            l.private -> "Private"
            else -> null
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            LockedName(l, ink, name, Modifier)
            if (tag != null) {
                Text(tag, color = ink, fontSize = 13.sp, maxLines = 1, modifier = Modifier.offset(y = 17.5.dp).padding(start = 29.dp))
            }
        }
        // a model's layer: the button that shows its channels, blue while one of them is painted;
        // laid out narrower than it is touched (36 from the blend letter, as before), so the name
        // keeps its room up to the icon
        if (onCube != null) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(12.dp).height(47.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(44.dp, 47.dp).clickable(onClick = onCube), contentAlignment = Alignment.Center) {
                    Icon(PcIcons.Tab3D, contentDescription = "Channels", tint = if (cubeLit) Pc.Accent else ink, modifier = Modifier.requiredSize(48.dp))
                }
            }
        }
        // the blend and visibility buttons overlap by 8, as measured; a mask has no blend
        if (mask) {
            Spacer(Modifier.width(36.dp))
        } else {
            Box(Modifier.offset(x = 8.dp).size(44.dp, 47.dp).clickable(onClick = onBlend), contentAlignment = Alignment.Center) {
                // a locked layer's mode is greyed
                val letter = if (l.locked && !l.current && !l.picked) LockedLetter else ink
                Text(blendCode(vm, l.blend), color = letter, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.size(44.dp, 47.dp).visibilityTouch(vm, l), contentAlignment = Alignment.Center) {
            CheckBox(l.visible && !l.soloedOut, if (selected || l.picked) Color.White else Pc.CheckFill)
        }
        Spacer(Modifier.width(8.dp))
    }
}

internal val TrayFill = Color(0xFF303032)
internal val TileFill = Color(0xFF3B3B3D)
