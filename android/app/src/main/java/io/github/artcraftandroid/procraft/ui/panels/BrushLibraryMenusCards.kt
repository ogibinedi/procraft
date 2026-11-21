package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.core.BrushInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A button a swiped brush shows: its icon and label, its colours, and what it does. */
internal class BrushSwipeAction(val icon: ImageVector, val label: String, val fill: Color = BrushSwipeFill, val ink: Color = BrushSwipeInk, val onClick: () -> Unit)

/**
 * A brush that slides away to the left, showing its [actions] in its place: Share, Duplicate and
 * Delete; in the Recent set Find, Pin (or Unpin) and Clear. Slid right past half of [BrushPickReach] it
 * springs back and is picked or let go ([onPick]).
 */
@Composable
internal fun SwipeBrush(
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    actions: List<BrushSwipeAction>,
    onPick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    val reach = if (onPick == null) 0f else with(LocalDensity.current) { BrushPickReach.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open, width) { offset.animateTo(if (open) -width.toFloat() else 0f, Motion.swipeSettle()) }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.clipToBounds()) {
        if (offset.value < 0f) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.spacedBy(BrushSwipeGap)) {
                for (a in actions) {
                    SwipeButton(a.icon, a.label, a.fill, a.ink, Modifier.weight(1f)) {
                        onOpen(false)
                        a.onClick()
                    }
                }
            }
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.pointerInput(open, width, reach) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + amount).coerceIn(-width.toFloat(), if (open) 0f else reach)) }
                    },
                    onDragEnd = {
                        if (offset.value > reach / 2f && reach > 0f) onPick?.invoke()
                        val opened = offset.value < -width / 3f
                        if (opened == open) scope.launch { offset.animateTo(if (open) -width.toFloat() else 0f, Motion.swipeSettle()) } else onOpen(opened)
                    },
                    onDragCancel = { scope.launch { offset.animateTo(if (open) -width.toFloat() else 0f, Motion.swipeSettle()) } },
                )
            },
        ) { content() }
    }
}

@Composable
private fun SwipeButton(icon: ImageVector, label: String, fill: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(fill).clickable(onClick = onClick).padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 19 across, as measured, the label where it was
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A menu of the library (and the layers panel's title), placed by [place]. */
@Composable
internal fun LibraryMenu(
    open: Boolean,
    origin: androidx.compose.ui.graphics.TransformOrigin,
    place: PopupPositionProvider,
    onDismiss: () -> Unit,
    overPanel: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    MenuPresence(open, origin) { look ->
        PcPopup(textScale = MATCHED_POPUP_TEXT, popupPositionProvider = place, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            val shape = RoundedCornerShape(16.dp)
            // frosted over the canvas; over the library itself, nearly solid
            val glass = if (overPanel) Modifier.background(Pc.GlassMenuOverPanel) else rememberFrost(16.dp).background(Pc.GlassMenu)
            Column(look.width(LibraryMenuWidth).clip(shape).then(glass).border(1.dp, MenuEdge, shape).padding(vertical = 7.dp), content = content)
        }
    }
}

/** A menu row: its icon (a 40 frame) centred 29.5 in, the label from 52, a chevron 27.5 from the right. */
@Composable
internal fun MenuItem(icon: ImageVector, label: String, ink: Color = Pc.MenuInk, trailing: ImageVector? = null, iconSize: Dp = 40.dp, onClick: () -> Unit) {
    MenuItem(label, ink, trailing, onClick) { Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.requiredSize(iconSize)) }
}

/** A menu row whose glyph [content] draws, centred where an icon's 40 frame sits. */
@Composable
internal fun MenuItem(label: String, ink: Color, trailing: ImageVector?, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 9.5.dp).size(40.dp), contentAlignment = Alignment.Center) { content() }
        // the menus' 17 pt system text is as wide as 18 here
        Text(label, color = ink, fontSize = 18.sp, maxLines = 1, modifier = Modifier.padding(start = 2.5.dp).weight(1f))
        if (trailing != null) Icon(trailing, contentDescription = null, tint = ink, modifier = Modifier.padding(end = 7.5.dp).requiredSize(40.dp))
    }
}

@Composable
internal fun MenuDivider(gap: Dp = 10.dp) {
    Box(Modifier.padding(horizontal = 15.5.dp, vertical = gap).fillMaxWidth().height(1.dp).background(MenuRule))
}

/** Places a menu from its anchor's bounds and its own size, kept in the window below [top]. */
/** The set icon picker: its columns, how far left of the set it starts, and the room it keeps below. */
internal const val PICKER_COLUMNS = 5
internal val LibraryPickerInset = 11.dp
internal val LibraryPickerMargin = 25.dp

internal class MenuPlace(private val top: Int = 0, private val place: (IntRect, IntSize) -> IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val p = place(anchorBounds, popupContentSize)
        return IntOffset(
            p.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            p.y.coerceIn(top, (windowSize.height - popupContentSize.height).coerceAtLeast(top)),
        )
    }
}

/** A library's title menu: rename it, share it, or go back to every library's card. */
@Composable
internal fun LibraryTitleMenu(vm: CanvasViewModel, open: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LibraryMenu(open, androidx.compose.ui.graphics.TransformOrigin(0f, 0f), MenuPlace { a, _ -> IntOffset(a.left, a.bottom) }, onDismiss = onDismiss, overPanel = true) {
        MenuItem(PcIcons.MenuRename, "Rename") {
            onDismiss()
            vm.renamingLibrary = vm.libraryPlace
        }
        MenuItem(PcIcons.MenuShare, "Share") {
            onDismiss()
            scope.launch { vm.libraryFileToShare()?.let { shareFile(context, it, "application/octet-stream") } }
        }
        MenuDivider(11.dp)
        MenuItem(PcIcons.MenuLibraries, "Back to libraries") {
            onDismiss()
            vm.showingLibraries = true
        }
    }
}

/**
 * Two fingers pinched on a library and let go at this much of their spread at the start (or less)
 * go to every library's card; pinched out on a card past this much, that library opens. While they
 * pinch, a library's lists shrink with them no further than the least here, and grow no further
 * than the most.
 */
internal const val PINCH_TO_LIBRARIES = 0.66f
private const val PINCH_TO_LIBRARY = 2f
internal const val PINCH_SHOWN_LEAST = 0.6f
internal const val PINCH_SHOWN_MOST = 1.05f

/**
 * Two fingers pinching here (its corner in the window at [origin]): [onPinch] hears how far (their
 * spread now, a part of what it was) and where they are (window pixels) as they go, and [onLet]
 * both when the first of them lifts. While they pinch, and after until the last lifts, their
 * touches go no further (the lists under them stay, and nothing is tapped or held); a lone
 * finger's go on as ever.
 */
internal suspend fun PointerInputScope.awaitLibraryPinch(
    origin: () -> Offset,
    onPinch: (Float, Offset) -> Unit,
    onLet: (Float, Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        var at: Offset? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.count { it.pressed }
            val pinching = at
            if (down >= 2) {
                zoom *= event.calculateZoom()
                val centre = event.calculateCentroid(useCurrent = true)
                val now = if (centre.isSpecified) origin() + centre else pinching ?: origin()
                at = now
                onPinch(zoom, now)
                event.changes.forEach { it.consume() }
            } else if (pinching != null) {
                event.changes.forEach { it.consume() }
                onLet(zoom, pinching)
                var rest = event
                while (rest.changes.any { it.pressed }) {
                    rest = awaitPointerEvent(PointerEventPass.Initial)
                    rest.changes.forEach { it.consume() }
                }
                break
            } else if (down == 0) {
                break
            }
        }
    }
}

private val CardHeight = 490.dp
private val CardGap = 8.dp
private val CardCorner = 16.dp
private val CardSetRow = 19.dp
private val CardBrushHeight = 30.dp
/** As many brushes as a card has room for under its name, the last cut off by its bottom: only these have their strokes drawn. */
private val CardBrushRows = ((CardHeight - 47.dp) / (CardBrushHeight + 2.dp)).toInt() + 1

/**
 * The library popover showing every library's card (tapped, that library is brought into use;
 * held, its menu), with "+" for a new one or one from a file and the title's menu to put the
 * shipped one back; or a library with no sets yet, with "+" to make one.
 */
@Composable
internal fun LibrariesPanel(vm: CanvasViewModel, arrowX: Dp, modifier: Modifier, floating: FloatingFrame?) {
    val showing = vm.showingLibraries
    var titleMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var cardMenu by remember { mutableStateOf<Int?>(null) }
    // the cards where they show and the grid's corner (window pixels); two fingers pinching out:
    // the card they began on, and how far they have gone
    val cardBounds = remember { mutableStateMapOf<Int, Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var pinchedCard by remember { mutableStateOf<Int?>(null) }
    var cardZoom by remember { mutableFloatStateOf(1f) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importBrushes(it) } }
    Popover(floating?.width ?: LibraryWidth, arrowX, modifier, side = if (floating != null) ArrowSide.NONE else ArrowSide.TOP) {
        floating?.let { FloatingTop(it) }
        val renaming = !showing && vm.renamingLibrary == vm.libraryPlace
        Box {
            PanelHeader(
                when {
                    showing -> "Brush libraries"
                    renaming -> ""
                    else -> vm.libraryName
                },
                start = 20.dp,
                end = 12.dp,
                height = 52.dp,
                chevronOn = titleMenu,
                onTitle = { titleMenu = true },
                menu = {
                    if (renaming) InlineName(vm.libraryName, Pc.PanelTitle, 24.sp) { vm.renameLibrary(vm.libraryPlace, it) }
                    if (showing) {
                        LibraryMenu(titleMenu, androidx.compose.ui.graphics.TransformOrigin(0f, 0f), MenuPlace { a, _ -> IntOffset(a.left, a.bottom) }, onDismiss = { titleMenu = false }, overPanel = true) {
                            MenuItem(PcIcons.MenuRestore, "Restore default library") {
                                titleMenu = false
                                vm.restoreLibraries()
                            }
                        }
                    } else {
                        LibraryTitleMenu(vm, titleMenu) { titleMenu = false }
                    }
                },
            ) {
                Box(Modifier.padding(top = 12.dp).size(41.dp, 40.dp).clickable { adding = true }, contentAlignment = Alignment.Center) {
                    Icon(PcIcons.PlusMedium, contentDescription = "Add", tint = Pc.PanelGlyph, modifier = Modifier.requiredSize(41.dp))
                    LibraryMenu(adding, androidx.compose.ui.graphics.TransformOrigin(1f, 0f), MenuPlace { a, size -> IntOffset(a.right - size.width, a.bottom) }, onDismiss = { adding = false }, overPanel = true) {
                        if (showing) {
                            MenuItem(PcIcons.MenuNewLibrary, "Create new library") {
                                adding = false
                                vm.newLibrary()
                            }
                            MenuItem(PcIcons.MenuFolder, "Import from Files") {
                                adding = false
                                pick.launch(arrayOf("*/*"))
                            }
                        } else {
                            MenuItem(PcIcons.MenuNewSet, "Create new set") {
                                adding = false
                                vm.newBrushSet()
                            }
                            MenuDivider(11.dp)
                            MenuItem(PcIcons.MenuFolder, "Import from Files") {
                                adding = false
                                pick.launch(arrayOf("*/*"))
                            }
                        }
                    }
                }
            }
            if (!LocalCompactSheet.current) LibraryGrabber(vm, floating)
        }
        if (showing) {
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.weight(1f, fill = floating != null).onGloballyPositioned { gridOrigin = it.positionInWindow() }.pointerInput(Unit) {
                    // two fingers pinched out on a card: it grows with them, and let go far enough apart it opens
                    awaitLibraryPinch({ gridOrigin }, { zoom, at ->
                        if (pinchedCard == null) pinchedCard = cardBounds.entries.firstOrNull { it.value.contains(at) }?.key
                        cardZoom = zoom
                    }) { zoom, _ ->
                        val card = pinchedCard
                        pinchedCard = null
                        cardZoom = 1f
                        if (zoom > PINCH_TO_LIBRARY && card != null) vm.openLibrary(card)
                    }
                },
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                items(vm.brushLibraries.size) { i ->
                    val library = vm.brushLibraries[i]
                    androidx.compose.runtime.DisposableEffect(i) { onDispose { cardBounds.remove(i) } }
                    Box(
                        Modifier.onGloballyPositioned { cardBounds[i] = it.boundsInWindow() }.graphicsLayer {
                            val k = if (pinchedCard == i) cardZoom.coerceIn(1f, PINCH_TO_LIBRARY) else 1f
                            scaleX = k
                            scaleY = k
                        },
                    ) {
                        LibraryCard(vm, i, library, onOpen = { vm.openLibrary(i) }, onMenu = { cardMenu = i })
                        LibraryMenu(cardMenu == i, androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f), MenuPlace { a, size -> IntOffset(a.left + (a.width - size.width) / 2, a.top + a.height / 4) }, onDismiss = { cardMenu = null }) {
                            MenuItem(PcIcons.MenuRename, "Rename") {
                                cardMenu = null
                                vm.renamingLibrary = i
                            }
                            MenuItem(PcIcons.MenuDuplicate, "Duplicate") {
                                cardMenu = null
                                vm.duplicateLibrary(i)
                            }
                            if (vm.brushLibraries.size > 1) {
                                MenuDivider(11.dp)
                                MenuItem(PcIcons.MenuTrash, "Delete", Pc.MenuDanger) {
                                    cardMenu = null
                                    vm.deletingLibrary = i
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // a library with no sets yet
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This library has no brushes yet", color = Pc.PanelTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(
                    "Make a set with +, then brushes in it, or bring brushes in from your files.",
                    color = Pc.PanelGlyph,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        floating?.let { FloatingBottom(it) }
    }
    Alert(
        vm.deletingLibrary,
        title = { "Delete this library?" },
        message = { "Its sets and brushes go with it." },
        action = "Delete",
        onCancel = { vm.deletingLibrary = null },
        onAction = { vm.deleteLibrary(it) },
    )
}

/**
 * A library's card: its name over a small likeness of it, its sets down the left and the brushes
 * of one of them beside them; the one in use outlined in blue.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryCard(vm: CanvasViewModel, i: Int, library: io.github.artcraftandroid.procraft.core.BrushLibraryInfo, onOpen: () -> Unit, onMenu: () -> Unit) {
    val tints = remember(vm.colorRgb(), vm.pluginLooks) { vm.previewTints() }
    val shape = RoundedCornerShape(CardCorner)
    Column(
        Modifier.fillMaxWidth().height(CardHeight).clip(shape).background(Pc.SetCell)
            .then(if (library.current) Modifier.border(2.dp, Pc.Accent, shape) else Modifier)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(10.dp),
    ) {
        if (vm.renamingLibrary == i) {
            InlineName(library.name, Pc.PanelTitle, 14.sp) { vm.renameLibrary(i, it) }
        } else {
            Text(library.name, color = Pc.PanelTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // the one in use's shown set; another's first with a brush chosen in it
        val chosen = if (library.current) vm.shownSet else library.sets.indexOfFirst { it.chosen != null }.coerceAtLeast(0)
        Row(Modifier.padding(top = 8.dp).fillMaxSize()) {
            Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                library.sets.forEachIndexed { s, set ->
                    Row(
                        Modifier.fillMaxWidth().height(CardSetRow).clip(RoundedCornerShape(5.dp))
                            .background(if (s == chosen) Pc.RowSelected else Pc.PopoverFill.copy(alpha = 0.35f)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val ink = if (s == chosen) Color.White else Pc.PanelTitle
                        Icon(PcIcons.SetIcons.getOrElse(set.icon.toInt()) { PcIcons.BrushSet }, contentDescription = null, tint = ink, modifier = Modifier.requiredSize(18.dp))
                        Text(set.name, color = ink, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(Modifier.weight(0.55f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                library.sets.getOrNull(chosen)?.brushes?.take(CardBrushRows)?.forEach { b ->
                    // its name over its stroke, drawn small
                    val key = "$i-${b.set}-${b.index}"
                    val stroke = vm.cardPreviews[key]
                    LaunchedEffect(key, stroke == null) { if (stroke == null) vm.loadCardPreview(i, b.set.toInt(), b.index.toInt()) }
                    Box(Modifier.fillMaxWidth().height(CardBrushHeight)) {
                        stroke?.let { androidx.compose.foundation.Image(it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, colorFilter = pluginFilter(tints.first), modifier = Modifier.fillMaxSize()) }
                        Text(b.name, color = Pc.PanelTitle, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 3.dp, top = 1.dp))
                    }
                }
            }
        }
    }
}

/**
 * A brush held in the library, as the finger goes on: held still its menu shows; moved on from
 * there past [slop] (pixels, from where it took hold) the menu goes and the brush is dragged, if it
 * is [movable] (the Recent set's brushes stay where they are).
 */
internal class BrushHold(private val slop: Float, private val movable: Boolean) {
    /** Whether the brush follows the finger, rather than its menu showing. */
    var dragging = false
        private set

    /** The finger is [moved] from where it took hold: true when that starts the drag. */
    fun move(moved: Offset): Boolean {
        if (dragging || !movable || moved.getDistance() <= slop) return false
        dragging = true
        return true
    }
}

/**
 * Where a brush from place [from] (set, index) goes, let go at [at] (window pixels): onto a set of
 * [sets] (by place), to its top; onto one of the shown set's [brushes] (by place), to its place;
 * nowhere when that is where it is already, or when it is over neither.
 */
internal fun brushDropPlace(from: Pair<Int, Int>, at: Offset, sets: Map<Int, Rect>, brushes: List<Pair<Pair<Int, Int>, Rect>>): Pair<Int, Int>? =
    brushesDropPlace(listOf(from), at, sets, brushes)

/**
 * Where brushes from places [from] (set, index; all of one set) dragged together go, let go at
 * [at] (window pixels): onto another set of [sets] (by place), to its top; onto one of the shown
 * set's [brushes] (by place) not among them, to its place (the first of them taking it, the rest
 * after it); nowhere over their own set, over one of them, or over neither.
 */
internal fun brushesDropPlace(from: List<Pair<Int, Int>>, at: Offset, sets: Map<Int, Rect>, brushes: List<Pair<Pair<Int, Int>, Rect>>): Pair<Int, Int>? {
    val own = from.firstOrNull()?.first ?: return null
    sets.entries.firstOrNull { it.value.contains(at) }?.let { return if (it.key == own) null else it.key to 0 }
    return brushes.firstOrNull { it.second.contains(at) }?.first?.takeIf { it !in from }
}

/**
 * The brushes dragged with [held]: those picked ([picked], by place "set-index") in its set and it,
 * in their order in its set ([brushes]), when it is one of them (the current brush, [selected],
 * counts as picked); else it alone.
 */
internal fun draggedTogether(held: BrushInfo, picked: List<String>, selected: Boolean, brushes: List<BrushInfo>): List<BrushInfo> {
    val key = "${held.set}-${held.index}"
    if (picked.isEmpty() || (key !in picked && !selected)) return listOf(held)
    val going = brushes.filter { b -> b.set == held.set && ("${b.set}-${b.index}" in picked || b.index == held.index) }
    return going.ifEmpty { listOf(held) }
}

/** How long a dragged brush is held over a set before it opens (as the system's own drags do). */
internal const val SPRING_LOAD_MS = 500L

/** Hanging from its button, the library's grabber tears it off into a window of its own, while a plugin lets it ([floating]: it floats). */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.LibraryGrabber(vm: CanvasViewModel, floating: FloatingFrame?) {
    if (floating == null && io.github.artcraftandroid.procraft.core.FloatingPanel.BRUSHES in Pc.floatingPanels) {
        PanelGrabber(vm, io.github.artcraftandroid.procraft.core.FloatingPanel.BRUSHES)
    }
}
