package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.gallery.Artwork
import io.github.artcraftandroid.procraft.gallery.GalleryEntry
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

/** Where the gallery's own drags land: the grid showing (its state, and its place and height in the root), and a stack's way back. */
internal class DropPlaces {
    var grid: androidx.compose.foundation.lazy.grid.LazyGridState? = null
    var gridAt = Offset.Zero
    var gridHeight = 0f
    var back: androidx.compose.ui.geometry.Rect? = null
}

/** How near the grid's top or bottom a drag scrolls it, and how far each frame. */
private val DragScrollEdge = 60.dp
private val DragScrollStep = 12.dp

/** Artwork [a]'s picture as it sits in its thumbnail's [box]: fitted, standing on the box's bottom. */
internal fun fitted(box: androidx.compose.ui.geometry.Rect, a: Artwork): androidx.compose.ui.geometry.Rect = fittedIn(box, a.ratio)

/** A picture [ratio] wide to its height as it sits in a thumbnail's [box]: fitted, standing on the box's bottom. */
fun fittedIn(box: androidx.compose.ui.geometry.Rect, ratio: Float): androidx.compose.ui.geometry.Rect {
    val w = if (ratio <= box.width / box.height) box.height * ratio else box.width
    val h = w / ratio
    return androidx.compose.ui.geometry.Rect(box.center.x - w / 2f, box.bottom - h, box.center.x + w / 2f, box.bottom)
}

/** Where a drag over the gallery's grid is: the cell it would be stacked onto, or else the slot it would go in. */
private data class DropAt(val onto: String?, val slot: Int)

/**
 * Where point [p] (the grid's pixels) is over [entries] laid out in [grid]: over another's picture
 * (the thumbnail as it sits in its box, or a stack's box) it is that one, when [stacking]; anywhere
 * else the slot between cells it is nearest, before a cell's middle or after it.
 */
private fun dropAt(grid: androidx.compose.foundation.lazy.grid.LazyGridState, entries: List<GalleryEntry>, dragged: String, p: Offset, top: Float, box: androidx.compose.ui.geometry.Size, stacking: Boolean): DropAt? {
    val items = grid.layoutInfo.visibleItemsInfo
    for (info in items) {
        val x = info.offset.x.toFloat()
        val y = info.offset.y.toFloat()
        if (p.x < x || p.x >= x + info.size.width || p.y < y || p.y >= y + info.size.height) continue
        val e = entries.getOrNull(info.index) ?: return null
        val left = x + (info.size.width - box.width) / 2f
        val inBox = androidx.compose.ui.geometry.Rect(left, y + top, left + box.width, y + top + box.height)
        val picture = if (e is Artwork) fitted(inBox, e) else inBox
        if (stacking && e.dir.name != dragged && picture.contains(p)) return DropAt(e.dir.name, info.index)
        return DropAt(null, if (p.x < x + info.size.width / 2f) info.index else info.index + 1)
    }
    // past the last cell: last
    val last = items.lastOrNull() ?: return null
    return if (last.index == entries.size - 1 && (p.y >= last.offset.y + last.size.height || (p.y >= last.offset.y && p.x >= last.offset.x + last.size.width))) DropAt(null, entries.size) else null
}

/**
 * The gallery taking its own drags: let go over another artwork's picture (or a stack's), the
 * dragged artwork is stacked with it ([onOver] names it meanwhile), anywhere else on the grid it
 * goes where it is let go. Near the grid's top or bottom the grid scrolls. An artwork resting on a
 * stack opens it ([onOpen]) and, in a stack, on the way back ([onBackLit] meanwhile) opens the
 * gallery ([onBack]), the drag going on, so it can be put in a stack where it is let go, or taken
 * out of one; let go on the way back at once, it is taken out to the gallery's front ([onTakeOut]).
 */
@Composable
internal fun galleryDrop(
    places: DropPlaces,
    entries: List<GalleryEntry>,
    cell: CellSize,
    stacking: Boolean,
    onBack: () -> Unit,
    onOpen: (io.github.artcraftandroid.procraft.gallery.Stack) -> Unit,
    onBackLit: (Boolean) -> Unit,
    onOver: (String?) -> Unit,
    onEnded: () -> Unit,
    onMove: (GalleryEntry, Int) -> Unit,
    onTakeOut: (GalleryEntry) -> Unit,
    onStack: (Artwork, GalleryEntry) -> Unit,
    onFiles: (List<android.net.Uri>) -> Unit,
): Modifier {
    val now = androidx.compose.runtime.rememberUpdatedState(entries)
    val inGallery = androidx.compose.runtime.rememberUpdatedState(stacking)
    val back = androidx.compose.runtime.rememberUpdatedState(onBack)
    val open = androidx.compose.runtime.rememberUpdatedState(onOpen)
    val backLit = androidx.compose.runtime.rememberUpdatedState(onBackLit)
    val over = androidx.compose.runtime.rememberUpdatedState(onOver)
    val ended = androidx.compose.runtime.rememberUpdatedState(onEnded)
    val move = androidx.compose.runtime.rememberUpdatedState(onMove)
    val takeOut = androidx.compose.runtime.rememberUpdatedState(onTakeOut)
    val stack = androidx.compose.runtime.rememberUpdatedState(onStack)
    // files dragged in from another app are imported, as Import's
    val files = androidx.compose.runtime.rememberUpdatedState(onFiles)
    val activity = androidx.compose.ui.platform.LocalContext.current.activity()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizes = androidx.compose.runtime.rememberUpdatedState(
        with(density) { Triple(39.dp.toPx(), androidx.compose.ui.geometry.Size(cell.width.toPx(), cell.thumb.toPx()), DragScrollEdge.toPx()) },
    )
    val scope = rememberCoroutineScope()
    // -1 scrolling up, 1 down
    var edge by remember { mutableIntStateOf(0) }
    LaunchedEffect(edge) {
        val step = with(density) { DragScrollStep.toPx() }
        while (edge != 0) {
            places.grid?.scrollBy(edge * step)
            androidx.compose.runtime.withFrameNanos { }
        }
    }
    val target = remember {
        object : androidx.compose.ui.draganddrop.DragAndDropTarget {
            var at: DropAt? = null
            var spring: kotlinx.coroutines.Job? = null

            // what it rests on: "" the way back, else a stack (by folder)
            var springOn: String? = null

            // over the way back out of the stack open
            var onBackNow = false

            fun stopSpring() {
                spring?.cancel()
                spring = null
                springOn = null
                backLit.value(false)
            }

            /** After a moment resting on [on] (the same each time it moves), [then]. */
            fun springOn(on: String, then: () -> Unit) {
                if (springOn == on) return
                stopSpring()
                springOn = on
                backLit.value(on.isEmpty())
                spring = scope.launch {
                    kotlinx.coroutines.delay(SPRING_MS)
                    stopSpring()
                    then()
                }
            }

            fun dragged(e: androidx.compose.ui.draganddrop.DragAndDropEvent) = (e.toAndroidDragEvent().localState as? io.github.artcraftandroid.procraft.gallery.GalleryDrag)?.entry

            fun track(e: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                val d = e.toAndroidDragEvent()
                val entry = dragged(e) ?: return
                val r = Offset(d.x, d.y)
                onBackNow = !inGallery.value && places.back?.contains(r) == true
                if (onBackNow) {
                    at = null
                    edge = 0
                    over.value(null)
                    springOn("") { back.value() }
                    return
                }
                val p = r - places.gridAt
                val (top, box, near) = sizes.value
                edge = when {
                    p.y < near -> -1
                    p.y > places.gridHeight - near -> 1
                    else -> 0
                }
                at = places.grid?.let { dropAt(it, now.value, entry.dir.name, p, top, box, inGallery.value && entry is Artwork) }
                over.value(at?.onto)
                val stack = at?.onto?.let { name -> now.value.firstOrNull { it.dir.name == name } } as? io.github.artcraftandroid.procraft.gallery.Stack
                if (stack != null) springOn(stack.dir.name) { open.value(stack) } else stopSpring()
            }

            override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = track(event)

            override fun onMoved(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = track(event)

            override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                at = null
                onBackNow = false
                edge = 0
                stopSpring()
                over.value(null)
            }

            override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                val e = dragged(event) ?: run {
                    val uris = event.droppedFiles(activity)
                    if (uris.isNotEmpty()) files.value(uris)
                    return uris.isNotEmpty()
                }
                // let go on the way back before it opened the gallery: out to the gallery's front
                if (onBackNow && !inGallery.value) {
                    takeOut.value(e)
                    return true
                }
                val drop = at ?: return true
                val list = now.value
                val onto = drop.onto?.let { name -> list.firstOrNull { it.dir.name == name } }
                if (onto != null && e is Artwork) {
                    stack.value(e, onto)
                } else {
                    // from a stack it comes in at the slot; from here, the slot counted without it
                    val from = list.indexOfFirst { it.dir.name == e.dir.name }
                    val to = if (from >= 0 && drop.slot > from) drop.slot - 1 else drop.slot
                    if (from < 0 || to != from) move.value(e, to)
                }
                return true
            }

            override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) {
                at = null
                onBackNow = false
                edge = 0
                stopSpring()
                ended.value()
            }
        }
    }
    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { it.toAndroidDragEvent().localState is io.github.artcraftandroid.procraft.gallery.GalleryDrag || it.fromElsewhere() },
        target = target,
    )
}
private val SwipePillWidth = 94.dp
internal val SwipePillHeight = 56.dp
private val SwipePillFill get() = if (Pc.light) Color(0xFFF5F5F5) else Color(0xDB1C1C1C)
private val SwipePillInk get() = if (Pc.light) Color(0xFF575757) else Color(0xFFA9A9A9)
private val SwipeDeleteFill = Color(0xFFFE604D)

/**
 * An artwork's actions, Share, Duplicate and Delete, in a row over it: they grow in from the right
 * as it is swiped open ([open], 0..1, springing), and shrink back as it closes.
 */
@Composable
internal fun SwipeActions(open: Float, modifier: Modifier, onShare: (androidx.compose.ui.geometry.Rect) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var shareAt by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Row(
        modifier.requiredWidth(SwipePillWidth * 3 + 6.dp).height(SwipePillHeight).graphicsLayer {
            val k = 0.2f + 0.8f * open
            scaleX = k
            scaleY = k
            alpha = open.coerceIn(0f, 1f)
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((label, delete, action) in listOf(Triple("Share", false, { onShare(shareAt) }), Triple("Duplicate", false, onDuplicate), Triple("Delete", true, onDelete))) {
            Box(
                Modifier.size(SwipePillWidth, SwipePillHeight)
                    .then(if (label == "Share") Modifier.onGloballyPositioned { shareAt = it.boundsInWindow() } else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (delete) SwipeDeleteFill else SwipePillFill).clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (delete) Color.White else SwipePillInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/**
 * Artworks to share, the swiped Share button's place (window pixels; none from Select), and what
 * several of them shared as one PDF are named.
 */
internal class GalleryShare(val artworks: List<Artwork>, val from: androidx.compose.ui.geometry.Rect?, val group: String)

private val FormatsWidth = 260.dp
private val FormatsTitleHeight = 55.dp
private val FormatsRow = 40.dp
private val FormatsHeight = FormatsTitleHeight + FormatsRow * CanvasViewModel.SHARE_FORMATS.size + (CanvasViewModel.SHARE_FORMATS.size - 1).dp + 10.dp

/** Where Share's formats go: their top left, the arrow's side and place, and where they grow from. */
internal class FormatsPlace(val left: Dp, val top: Dp, val side: ArrowSide, val arrowAt: Dp, val origin: androidx.compose.ui.graphics.TransformOrigin)

/** Select's Share button's centre: left of Delete and More, in the capsule 77 from the right. */
internal fun selectShareCentre(screenWidth: Dp): Dp = screenWidth - 20.dp - 44.dp - 13.dp - 61.dp - 70.dp - 65.dp / 2

/**
 * Beside a swiped artwork's Share ([from], dp), pointing at it, on its right where they fit and
 * else on its left, kept 2 inside the screen; with no button, under Select's Share, centred on it
 * and 15 below it, kept 4 inside.
 */
internal fun formatsPlace(from: androidx.compose.ui.geometry.Rect?, screenWidth: Dp, screenHeight: Dp, selectShare: Dp): FormatsPlace {
    if (from == null) {
        val left = (selectShare - FormatsWidth / 2).coerceIn(4.dp, (screenWidth - 4.dp - FormatsWidth).coerceAtLeast(4.dp))
        return FormatsPlace(left, 69.dp, ArrowSide.NONE, 0.dp, androidx.compose.ui.graphics.TransformOrigin((selectShare - left) / FormatsWidth, 0f))
    }
    val gap = 2.dp
    val centreY = from.center.y.dp
    val top = (centreY - FormatsHeight / 2).coerceIn(2.dp, (screenHeight - 2.dp - FormatsHeight).coerceAtLeast(2.dp))
    val arrowAt = (centreY - top).coerceIn(Pc.PopoverCorner + Pc.ArrowHeight, FormatsHeight - Pc.PopoverCorner - Pc.ArrowHeight)
    val total = FormatsWidth + Pc.ArrowHeight
    val y = arrowAt / FormatsHeight
    // on the side of the pill with more room (right of a pill left of the middle), the other if
    // that side is too narrow
    val roomRight = screenWidth - 2.dp - from.right.dp - gap
    val roomLeft = from.left.dp - gap - 2.dp
    val right = if (roomRight >= roomLeft) roomRight >= total || roomLeft < total else roomLeft < total && roomRight >= total
    return if (right) {
        FormatsPlace(from.right.dp + gap, top, ArrowSide.LEFT, arrowAt, androidx.compose.ui.graphics.TransformOrigin(0f, y))
    } else {
        FormatsPlace((from.left.dp - gap - total).coerceAtLeast(2.dp), top, ArrowSide.RIGHT, arrowAt, androidx.compose.ui.graphics.TransformOrigin(1f, y))
    }
}

/** Share's formats, the animated ones last: a tap on one shares in it. */
@Composable
internal fun ImageFormats(modifier: Modifier, side: ArrowSide, arrowAt: Dp, onPick: (String) -> Unit) {
    Popover(FormatsWidth, arrowAt, modifier, fill = Pc.GalleryPopover, side = side) {
        Box(Modifier.height(FormatsTitleHeight).padding(start = 22.dp), contentAlignment = Alignment.CenterStart) {
            Text("Image Format", color = Pc.PanelTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(18.dp)).background(Pc.ActionsGroup)) {
            for ((i, format) in CanvasViewModel.SHARE_FORMATS.withIndex()) {
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
                Box(Modifier.fillMaxWidth().height(FormatsRow).clickable { onPick(format) }.padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text(format, color = Pc.PanelTitle, fontSize = 17.sp)
                }
            }
        }
    }
}

/** A row of More's list: its name, whether it can act now (else greyed), and what it does. */
internal class MoreRow(val label: String, val enabled: Boolean, val onClick: () -> Unit)

/**
 * More's list, of what the title row has no room for: selecting, Preview, Stack (two or more;
 * none in a stack) and Duplicate, and in a narrow window Share and Delete too; else Import and
 * Photo, and in a narrow window Select too.
 */
@Composable
internal fun MoreOptions(modifier: Modifier, arrowX: Dp, rows: List<MoreRow>) {
    Popover(250.dp, arrowX, modifier, fill = Pc.GalleryPopover) {
        Text("More Options", color = Pc.PanelTitle, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 12.dp))
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(Pc.ActionsGroup)) {
            for ((i, row) in rows.withIndex()) {
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
                Box(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = row.enabled, onClick = row.onClick).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text(row.label, color = if (row.enabled) Pc.PanelTitle else Pc.ActionsDisabled, fontSize = 17.sp)
                }
            }
        }
    }
}

/**
 * A stack: its newest artwork on two more cards, the name, and how many it holds; a tap opens it.
 * While selecting ([chosen] not null) its top card has an artwork's mark, filled when picked.
 */
@Composable
internal fun StackCell(
    s: io.github.artcraftandroid.procraft.gallery.Stack,
    version: Int,
    chosen: Boolean?,
    wash: Boolean,
    modifier: Modifier,
    onPlaced: (androidx.compose.ui.geometry.Rect) -> Unit,
    onSwipe: (Float, Float) -> Unit,
    onDrag: (Offset) -> Unit,
    onRename: () -> Unit,
    onOpen: () -> Unit,
) {
    val top = s.artworks.firstOrNull() ?: return
    val cell = LocalCell.current
    val view = LocalView.current
    // where the cell is in the window, for where the drag picture is held
    val cellAt = remember { arrayOf(Offset.Zero) }
    Column(
        modifier.height(cell.pitch).onGloballyPositioned {
            onPlaced(it.boundsInWindow())
            cellAt[0] = it.positionInWindow()
        }.pointerInput(s, chosen) {
            // a tap opens it (on its name, renames it); moved across, it is swiped ([onSwipe], how
            // far across and down); held and moved, it is dragged ([onDrag], with the finger's
            // place in the window)
            awaitEachGesture {
                val down = awaitFirstDown()
                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                var moved: Offset? = null
                val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (up == null && moved == null) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        val d = c.position - down.position
                        if (!c.pressed) up = c else if (d.getDistance() > viewConfiguration.touchSlop) moved = d
                    }
                    false
                } ?: true
                moved?.let { d ->
                    onSwipe(d.x, d.y)
                    return@awaitEachGesture
                }
                if (!held) {
                    up?.let {
                        it.consume()
                        if (it.position.y > (39.dp + cell.thumb + 8.dp).toPx()) onRename() else onOpen()
                    }
                    return@awaitEachGesture
                }
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) break
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        onDrag(cellAt[0] + c.position)
                        break
                    }
                }
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 39.dp).size(cell.width, cell.thumb), contentAlignment = Alignment.BottomCenter) {
            // the top artwork drawn again on two cards under it, a little turned (none for one)
            if (s.artworks.size > 1) {
                for (turn in listOf(5f, -4f)) {
                    Box(Modifier.graphicsLayer { rotationZ = turn }, contentAlignment = Alignment.BottomCenter) { Thumbnail(top, version) }
                }
            }
            Thumbnail(top, version, chosen, wash = wash)
        }
        Text(s.name, color = if (chosen == true) Color.White else Pc.GalleryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
        Text("${s.artworks.size} artworks", color = Pc.GallerySize, fontSize = 10.sp)
    }
}
