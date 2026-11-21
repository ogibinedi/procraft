package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
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
import io.github.artcraftandroid.procraft.canvas.RECENT_SET
import io.github.artcraftandroid.procraft.core.BrushInfo
import io.github.artcraftandroid.procraft.core.ToolKind
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LibraryWidth = 440.dp
private val SetsWidth = 168.dp
private val BrushHeight = 80.dp
private val BrushGap = 8.dp
/** The library's menus: dark glass with a light edge, rows 40 high. */
internal val LibraryMenuWidth = 253.dp
private val MenuFill = Color(0xFA303335)
internal val MenuEdge = Color(0xFF5A5D5F)
internal val MenuRule = Color(0xFF3F4347)
/** Menus stay below the top bar. */
private val MenuTop = 57.dp
/** A brush swiped left shows three buttons filling its place, this far apart. */
internal val BrushSwipeGap = 5.5.dp
internal val BrushSwipeFill = Color(0xFF3F3F3F)
internal val BrushSwipeInk = Color(0xFFC5C5C5)
private val SwipeDelete = Color(0xFFFF3A46)
/** A brush swiped right is picked, this fill over its row; it follows the finger this far. */
private val PickedBrush = Color(0xFF2C5281)
internal val BrushPickReach = 72.dp

/** A sample stroke tinted with [tint], the colour a plugin gives it; as drawn (white) without one. */
internal fun pluginFilter(tint: Color?): androidx.compose.ui.graphics.ColorFilter? =
    tint?.let { androidx.compose.ui.graphics.ColorFilter.tint(it, androidx.compose.ui.graphics.BlendMode.Modulate) }

/**
 * A sample stroke on a cell, the brush in use's if [selected]: in the colour a plugin gives in [tints] (each brush's, then
 * the brush in use's), else dark on the light interface's cells.
 */
private fun previewFilter(tints: Pair<Color?, Color?>, selected: Boolean): androidx.compose.ui.graphics.ColorFilter? =
    pluginFilter((if (selected) tints.second else tints.first) ?: Color.Black.takeIf { Pc.light && !selected })

/** A brush's name on its cell: on a dark pill where its sample stroke runs under it. */
@Composable
private fun BrushName(name: String, selected: Boolean, shaded: Boolean, modifier: Modifier) {
    Text(
        name,
        color = if (selected) Color.White else Pc.PanelTitle,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.offset(x = (-6).dp, y = (-2).dp)
            .then(if (shaded) Modifier.clip(RoundedCornerShape(50)).background(NamePill) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private val NamePill = Color(0x80000000)
/** The search field with the space above and below it, which scrolls away with the lists. */
private val SearchBlock = 60.dp
private val SearchFill = Color(0x1F767680)
private val SearchHint = Color(0x4DEBEBF5)
private val ResultFill = Color(0x14FFFFFF)

/**
 * The search field under the library's header: a magnifier, the text and, once typed, a clear
 * button. Putting the keyboard away leaves the field.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LibrarySearch(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = WindowInsets.isImeVisible
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(keyboard) {
        if (keyboard) hadKeyboard = true else if (hadKeyboard) {
            hadKeyboard = false
            focus.clearFocus()
        }
    }
    Row(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp).fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(10.dp)).background(SearchFill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(41.dp, 40.dp)) {
            // a magnifier: a ring and its handle
            val c = Offset(size.width * 0.47f, size.height * 0.46f)
            val r = 7.dp.toPx()
            drawCircle(SearchHint, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            val d = r * 0.7071f
            drawLine(SearchHint, c + Offset(d, d), c + Offset(d + 5.dp.toPx(), d + 5.dp.toPx()), 2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search", color = SearchHint, fontSize = 17.sp, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Pc.Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocus(it.isFocused) },
            )
        }
        if (query.isNotEmpty()) {
            // a filled circle with a cross cut out of it
            Canvas(Modifier.padding(end = 5.dp).size(19.dp, 18.dp).clip(CircleShape).clickable { onQuery("") }) {
                val c = center
                drawCircle(SearchHint, size.minDimension / 2f, c)
                val k = 3.5.dp.toPx()
                for (s in listOf(1f, -1f)) {
                    drawLine(Color.Black, c + Offset(-k, -k * s), c + Offset(k, k * s), 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                }
            }
        }
    }
}

/** The brushes of every set whose name holds the search, two to a row; tapping one chooses it. */
@Composable
private fun SearchResults(vm: CanvasViewModel, query: String) {
    val found = if (query.isBlank()) emptyList() else vm.brushSets.flatMap { it.brushes }.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val tints = remember(vm.colorRgb(), vm.pluginLooks) { vm.previewTints() }
    if (found.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
            Text("No results", color = Pc.PanelTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 11.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(found, key = { "${it.set}-${it.index}" }) { b ->
            val key = "${b.set}-${b.index}"
            val preview = vm.previews[key]
            LaunchedEffect(key, preview == null, vm.previewRound) { if (preview == null) vm.loadPreview(b) }
            val selected = vm.highlighted(b)
            Box(
                Modifier.fillMaxWidth().height(81.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Pc.RowSelected else ResultFill)
                    .clickable { vm.chooseBrush(b) },
            ) {
                preview?.let {
                    val tint = pluginFilter(if (selected) tints.second else tints.first)
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = tint, modifier = Modifier.fillMaxSize())
                }
                BrushName(b.name, selected, vm.shadedNames[key] == true, Modifier.padding(start = 14.dp, top = 11.dp, end = 10.dp))
            }
        }
    }
}

/**
 * The brush library: sets on the left, the set's brushes with a sample stroke on the right. Tap
 * the chosen set for its menu, swipe a brush left to share, duplicate or delete it, hold it for
 * its menu (and drag it on from there onto a set, which opens after a moment over it, or to
 * another place in its own; brushes picked go with it), pinch the lists in for every library's
 * card, and add brushes and sets with the plus.
 */
@Composable
fun BrushLibrary(vm: CanvasViewModel, arrowX: Dp, modifier: Modifier = Modifier, floating: FloatingFrame? = null) {
    val sets = vm.brushSets
    val tints = remember(vm.colorRgb(), vm.pluginLooks) { vm.previewTints() }
    // the Recent set, or one of the library's
    val recent = vm.shownSet == RECENT_SET
    val shown = sets.getOrNull(vm.shownSet)
    // every library's card, or a library with no sets yet: a panel of their own
    if (vm.showingLibraries || sets.isEmpty() || (shown == null && !recent)) {
        LibrariesPanel(vm, arrowX, modifier, floating)
        return
    }
    // the brushes shown, and whether each is pinned in the Recent set
    val listed = if (recent) {
        vm.recentBrushes.mapNotNull { r -> sets.getOrNull(r.set.toInt())?.brushes?.getOrNull(r.index.toInt())?.let { it to r.pinned } }
    } else {
        shown?.brushes.orEmpty().map { it to false }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var adding by remember { mutableStateOf(false) }
    // the title's menu: the library renamed, or every library's card
    var titleMenu by remember { mutableStateOf(false) }
    var setMenu by remember { mutableStateOf<Int?>(null) }
    var recentMenu by remember { mutableStateOf(false) }
    // the set menu's icon grid is open; where its Customize icon row and the set are on screen
    var iconPicker by remember { mutableStateOf(false) }
    var customizeRow by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var setScreenTop by remember { mutableStateOf(0f) }
    // the search: what is typed, and whether the field is in use
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    // a brush held: its menu (by place, "set-index")
    var brushMenu by remember { mutableStateOf<String?>(null) }
    // two fingers pinching on the lists: how far (their spread, a part of what it was); the lists
    // follow, as far as the pinch can take them, and spring back when it isn't taken
    var pinched by remember { mutableStateOf<Float?>(null) }
    var listsOrigin by remember { mutableStateOf(Offset.Zero) }
    val pinchShown = androidx.compose.animation.core.animateFloatAsState(
        pinched?.coerceIn(PINCH_SHOWN_LEAST, PINCH_SHOWN_MOST) ?: 1f,
        if (pinched != null) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(),
        label = "library pinch",
    )
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            vm.pickedBrushes.clear()
            vm.renamingBrush = null
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importBrushes(it) } }
    // a brush held and dragged: which, where the finger is and where on the brush it took hold
    // (window pixels); and where the sets, the brushes and the lists are on the screen
    var dragged by remember { mutableStateOf<BrushInfo?>(null) }
    // the brushes going with it (it among them, those picked in its set), in their order; the
    // finger dragging them, and the dragged cell's size (its row may go as another set opens)
    var draggedWith by remember { mutableStateOf<List<BrushInfo>>(emptyList()) }
    var dragPointer by remember { mutableStateOf<androidx.compose.ui.input.pointer.PointerId?>(null) }
    var dragSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    // or a set held and dragged (its place)
    var draggedSet by remember { mutableStateOf<Int?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    val setBounds = remember { mutableStateMapOf<Int, Rect>() }
    val brushBounds = remember { mutableStateMapOf<String, Rect>() }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    // the set the dragged brush or set is over, if any
    val overSet = if (dragged == null && draggedSet == null) null else setBounds.entries.firstOrNull { it.value.contains(dragAt) }?.key?.takeIf { it != draggedSet }
    fun drop() {
        val b = dragged ?: return
        val moving = draggedWith.ifEmpty { listOf(b) }
        dragged = null
        draggedWith = emptyList()
        dragPointer = null
        // onto a set: to its top; onto a brush of the set shown: to its place
        val brushes = shown?.brushes.orEmpty().mapNotNull { t -> brushBounds["${t.set}-${t.index}"]?.let { (t.set.toInt() to t.index.toInt()) to it } }
        val (set, index) = brushesDropPlace(moving.map { it.set.toInt() to it.index.toInt() }, dragAt, setBounds, brushes) ?: return
        vm.moveBrushes(moving, set, index)
    }
    // held over another set a moment, the dragged brush opens it, to be let go among its brushes
    LaunchedEffect(overSet, dragged != null) {
        val over = overSet ?: return@LaunchedEffect
        if (dragged == null || over == vm.shownSet) return@LaunchedEffect
        kotlinx.coroutines.delay(SPRING_LOAD_MS)
        if (dragged != null) vm.showSet(over)
    }
    // (for a hold that began before the library last changed)
    val dropNow by androidx.compose.runtime.rememberUpdatedState(::drop)
    // a set dropped onto another takes its place
    fun dropSet() {
        val from = draggedSet ?: return
        draggedSet = null
        setBounds.entries.firstOrNull { it.value.contains(dragAt) }?.key?.let { vm.moveBrushSet(from, it) }
    }
    fun share(b: BrushInfo?) {
        scope.launch { vm.brushFileToShare(b)?.let { shareFile(context, it, "application/octet-stream") } }
    }
    // how far the search field (and the lists under it) has scrolled up out of sight: all of it
    // when the library opens
    val searchBlock = with(density) { SearchBlock.toPx() }
    var searchAway by remember { mutableFloatStateOf(searchBlock) }
    val searchScroll = remember(searchBlock) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // scrolling up, the field goes first
                if (available.y >= 0f || searchAway >= searchBlock) return Offset.Zero
                val taken = maxOf(available.y, searchAway - searchBlock)
                searchAway -= taken
                return Offset(0f, taken)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // pulled down past a list's top (not flung), the field comes back
                if (available.y <= 0f || searchAway <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val taken = minOf(available.y, searchAway)
                searchAway -= taken
                return Offset(0f, taken)
            }
        }
    }

    Popover(floating?.width ?: LibraryWidth, arrowX, modifier, side = if (floating != null) ArrowSide.NONE else ArrowSide.TOP) {
        floating?.let { FloatingTop(it) }
        val renaming = vm.renamingLibrary == vm.libraryPlace
        Box {
            PanelHeader(
                if (renaming) "" else vm.libraryName,
                start = 20.dp,
                end = 12.dp,
                height = 52.dp,
                chevronOn = titleMenu,
                onTitle = { titleMenu = !titleMenu },
                menu = {
                    if (renaming) InlineName(vm.libraryName, Pc.PanelTitle, 24.sp) { vm.renameLibrary(vm.libraryPlace, it) }
                    LibraryTitleMenu(vm, titleMenu) { titleMenu = false }
                },
            ) {
                // brushes picked: Delete in the plus's place, and Combine for one with the current brush
                if (vm.pickedBrushes.isNotEmpty()) Row(Modifier.padding(top = 16.dp)) {
                    if (vm.canCombinePicked) {
                        Box(Modifier.size(80.dp, 32.dp).clickable { vm.combinePicked() }, contentAlignment = Alignment.Center) {
                            Text("Combine", color = BrushSwipeInk, fontSize = 15.sp, maxLines = 1)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(
                        Modifier.size(63.dp, 32.dp).clickable { deleting = "Delete these brushes?" to { vm.deletePickedBrushes() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Delete", color = BrushSwipeInk, fontSize = 15.sp, maxLines = 1)
                    }
                } else Box(Modifier.padding(top = 12.dp).size(41.dp, 40.dp).clickable { adding = true }, contentAlignment = Alignment.Center) {
                    Icon(PcIcons.PlusMedium, contentDescription = "Add", tint = Pc.PanelGlyph, modifier = Modifier.requiredSize(41.dp))
                    // hangs below the plus, their right edges in line
                    LibraryMenu(adding, androidx.compose.ui.graphics.TransformOrigin(1f, 0f), MenuPlace { a, size -> IntOffset(a.right - size.width, a.bottom) }, onDismiss = { adding = false }, overPanel = true) {
                        // the Recent set holds no brushes of its own to add one to
                        if (!recent) {
                            MenuItem(PcIcons.MenuNewBrush, "Create new brush") {
                                adding = false
                                vm.newBrush()
                            }
                        }
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
            if (!LocalCompactSheet.current) LibraryGrabber(vm, floating)
        }
        // the search field scrolls away with the lists: out of sight at first, it comes down as a
        // list is pulled down past its top, and goes again as the lists scroll up. Two fingers
        // pinched in on them shrink them, and let go small enough show every library's card
        Box(
            Modifier.weight(1f).clipToBounds().nestedScroll(searchScroll)
                .onGloballyPositioned { listsOrigin = it.positionInWindow() }
                .pointerInput(Unit) {
                    awaitLibraryPinch({ listsOrigin }, { zoom, _ -> pinched = zoom }) { zoom, _ ->
                        pinched = null
                        if (zoom <= PINCH_TO_LIBRARIES) {
                            brushMenu = null
                            vm.renamingBrush = null
                            vm.showingLibraries = true
                        }
                    }
                }
                .graphicsLayer {
                    val k = pinchShown.value
                    scaleX = k
                    scaleY = k
                },
        ) {
        Column(
            Modifier.layout { measurable, constraints ->
                val away = if (searching || query.isNotEmpty()) 0 else searchAway.roundToInt()
                val tall = constraints.maxHeight + away
                val placeable = measurable.measure(constraints.copy(minHeight = tall, maxHeight = tall))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -away) }
            },
        ) {
        LibrarySearch(query, onQuery = { query = it }, onFocus = { searching = it })
        Box(
            Modifier.weight(1f).onGloballyPositioned { listBounds = it.boundsInWindow() }
                // a dragged brush is followed here, over both lists, once its row has begun the drag:
                // on as its set's list gives way to another's
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val held = dragPointer
                            if (dragged != null && held != null) {
                                val change = event.changes.firstOrNull { it.id == held }
                                if (change == null) {
                                    // the finger gone without lifting (the gesture taken away)
                                    dragged = null
                                    draggedWith = emptyList()
                                    dragPointer = null
                                } else {
                                    change.consume()
                                    dragAt = listBounds.topLeft + change.position
                                    if (!change.pressed) dropNow()
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        ) {
        if (searching || query.isNotEmpty()) {
            SearchResults(vm, query)
        } else {
        val adaptiveSetsWidth = ResponsiveLayout.brushSetsColumnWidth(LocalSheetWidth.current)
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.width(adaptiveSetsWidth).fillMaxHeight().background(Pc.SetColumn),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // the brushes last painted with, and those pinned there, above the sets
                item {
                    Box {
                        Row(
                            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (recent) Pc.RowSelected else Pc.SetCell)
                                .clickable { if (recent) recentMenu = true else vm.showSet(RECENT_SET) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ink = if (recent) Color.White else Pc.PanelTitle
                            Icon(PcIcons.RecentSet, contentDescription = null, tint = ink, modifier = Modifier.padding(start = 4.dp).requiredSize(40.dp))
                            Text("Recent", color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 1.dp, end = 8.dp))
                        }
                        // as a set's menu, of one item and 65 tall, as measured
                        val left = with(density) { 6.dp.roundToPx() }
                        val below = with(density) { MenuTop.roundToPx() }
                        LibraryMenu(recentMenu, androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f), MenuPlace(below) { a, size -> IntOffset(a.left - size.width - left, a.center.y - size.height / 2) }, onDismiss = { recentMenu = false }) {
                            Box(Modifier.padding(vertical = 5.5.dp)) {
                                MenuItem(PcIcons.MenuClear, "Clear") {
                                    recentMenu = false
                                    vm.clearRecentBrushes()
                                }
                            }
                        }
                    }
                }
                itemsIndexed(sets) { i, set ->
                    val selected = i == vm.shownSet
                    // gone from the list, it is nowhere: where it last was may be under another row
                    androidx.compose.runtime.DisposableEffect(i) { onDispose { setBounds.remove(i) } }
                    Box {
                        Row(
                            Modifier.fillMaxWidth().height(50.dp).onGloballyPositioned {
                                setBounds[i] = it.boundsInWindow()
                                if (selected) setScreenTop = it.positionOnScreen().y
                            }
                                // held, it lifts and goes where it is dragged; faint where it was
                                .alpha(if (draggedSet == i) 0.3f else 1f)
                                .pointerInput(i) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            setMenu = null
                                            focus.clearFocus()
                                            grab = at
                                            dragAt = (setBounds[i]?.topLeft ?: Offset.Zero) + at
                                            draggedSet = i
                                        },
                                        onDrag = { change, by ->
                                            change.consume()
                                            dragAt += by
                                        },
                                        onDragEnd = { dropSet() },
                                        onDragCancel = { draggedSet = null },
                                    )
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (overSet == i) Pc.Accent else if (selected) Pc.RowSelected else Pc.SetCell)
                                .clickable { if (selected) setMenu = i else vm.showSet(i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ink = if (selected) Color.White else Pc.PanelTitle
                            // a 40 frame centred 24 in
                            Icon(PcIcons.SetIcons.getOrElse(set.icon.toInt()) { PcIcons.BrushSet }, contentDescription = null, tint = ink, modifier = Modifier.padding(start = 4.dp).requiredSize(40.dp))
                            val nameModifier = Modifier.padding(start = 1.dp, end = 8.dp)
                            if (vm.renamingSet == i) {
                                InlineName(set.name, ink, 14.sp, nameModifier) { vm.renameBrushSet(i, it) }
                            } else {
                                Text(
                                    set.name,
                                    color = ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = nameModifier,
                                )
                            }
                        }
                        // centred on the set, a little left of it
                        val left = with(density) { 6.dp.roundToPx() }
                        val below = with(density) { MenuTop.roundToPx() }
                        // the icons to choose from, right of the menu over the library: under the Customize icon
                        // row, or over it when there is no room below; scrolled to the set's own icon
                        MenuPresence(setMenu == i && iconPicker, androidx.compose.ui.graphics.TransformOrigin(0f, 0f)) { look ->
                            PcPopup(
                                popupPositionProvider = remember(customizeRow, setScreenTop, density) {
                                    MenuPlace(below) { a, size ->
                                        val x = a.left - with(density) { LibraryPickerInset.roundToPx() }
                                        val row = customizeRow ?: return@MenuPlace IntOffset(x, a.top)
                                        val top = a.top + (row.first - setScreenTop).roundToInt()
                                        val bottom = a.top + (row.second - setScreenTop).roundToInt()
                                        val room = (vm.windowSize.y - with(density) { LibraryPickerMargin.toPx() }).roundToInt()
                                        IntOffset(x, if (bottom + size.height <= room) bottom else top - size.height)
                                    }
                                },
                                onDismissRequest = { iconPicker = false },
                            ) {
                                val shape = RoundedCornerShape(26.dp)
                                // an icon that isn't one of the picker's starts it at the top
                                val own = set.icon.toInt().takeIf { it in 0 until PcIcons.PICKED_SET_ICONS }
                                val first = if (own == null) 0 else (own / PICKER_COLUMNS - 3).coerceAtLeast(0) * PICKER_COLUMNS
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(PICKER_COLUMNS),
                                    state = androidx.compose.foundation.lazy.grid.rememberLazyGridState(initialFirstVisibleItemIndex = first),
                                    modifier = look.size(250.dp, 325.dp).clip(shape).background(MenuFill).border(1.dp, MenuEdge, shape).padding(3.dp),
                                ) {
                                    items(PcIcons.PICKED_SET_ICONS) { k ->
                                        val chosen = k == set.icon.toInt()
                                        Box(
                                            Modifier.size(48.dp).clip(RoundedCornerShape(21.dp)).background(if (chosen) Pc.Accent else Color.Transparent).clickable {
                                                vm.changeBrushSetIcon(i, k)
                                                iconPicker = false
                                                setMenu = null
                                            },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(PcIcons.SetIcons[k], contentDescription = PcIcons.SetIconNames[k], tint = Color.White, modifier = Modifier.requiredSize(40.dp))
                                        }
                                    }
                                }
                            }
                        }
                        // left of the set, 6 clear of it, centred on it
                        LibraryMenu(setMenu == i, androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f), MenuPlace(below) { a, size -> IntOffset(a.left - size.width - left, a.center.y - size.height / 2) }, onDismiss = { setMenu = null }) {
                            MenuItem(PcIcons.MenuRename, "Rename") {
                                setMenu = null
                                vm.renamingSet = i
                            }
                            MenuItem(PcIcons.MenuDuplicate, "Duplicate") {
                                setMenu = null
                                vm.duplicateBrushSet(i)
                            }
                            Box(Modifier.onGloballyPositioned { c -> c.positionOnScreen().y.let { customizeRow = it to it + c.size.height } }) {
                                MenuItem(PcIcons.SetIcons.getOrElse(set.icon.toInt()) { PcIcons.BrushSet }, "Customize icon", trailing = PcIcons.MenuChevron) {
                                    iconPicker = !iconPicker
                                }
                            }
                            MenuDivider()
                            MenuItem(PcIcons.MenuShare, "Share") {
                                setMenu = null
                                share(null)
                            }
                            // the only set too: the library is then left with none
                            MenuDivider()
                            MenuItem(PcIcons.MenuTrash, "Delete", Pc.MenuDanger) {
                                setMenu = null
                                deleting = "Delete this set and its brushes?" to { vm.deleteBrushSet(i) }
                            }
                        }
                    }
                }
            }
            // another set's brushes replace these at once (rows fade only as brushes come and go)
            androidx.compose.runtime.key(vm.shownSet) {
                // a brush found from the Recent set is scrolled to in its own
                val listState = rememberLazyListState()
                LaunchedEffect(vm.foundBrush) {
                    val found = vm.foundBrush ?: return@LaunchedEffect
                    vm.foundBrush = null
                    listed.indexOfFirst { (b, _) -> "${b.set}-${b.index}" == found }.takeIf { it >= 0 }?.let { listState.scrollToItem(it) }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight().background(Pc.BrushColumn),
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, top = 9.75.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(BrushGap),
                ) {
                    items(listed, key = { (b, _) -> "${b.set}-${b.index}" }) { (b, pinned) ->
                        val key = "${b.set}-${b.index}"
                        // the brush in use alone (a set opened makes its last choice the brush)
                        val selected = vm.highlighted(b)
                        val picked = key in vm.pickedBrushes
                        // previews go by place, and are dropped when the library changes
                        val preview = vm.previews[key]
                        LaunchedEffect(key, preview == null, vm.previewRound) { if (preview == null) vm.loadPreview(b) }
                        // gone from the list, it is nowhere: where it last was may be under another row
                        androidx.compose.runtime.DisposableEffect(key) { onDispose { brushBounds.remove(key) } }
                        // the brush in this place now, for a hold that began before it moved here
                        val brushNow by androidx.compose.runtime.rememberUpdatedState(b)
                        Box(Modifier.animateItem(Motion.rowFade(), Motion.rowMove(), Motion.rowFade())) {
                            SwipeBrush(
                                open = vm.swipedBrush == key,
                                onOpen = { vm.swipedBrush = if (it) key else null },
                                // the current brush counts as picked already
                                onPick = if (recent || selected) null else ({ vm.togglePickedBrush(b) }),
                                actions = if (recent) {
                                    listOf(
                                        BrushSwipeAction(PcIcons.SwipeFind, "Find") { vm.findBrush(b) },
                                        if (pinned) {
                                            BrushSwipeAction(PcIcons.SwipeUnpin, "Unpin") { vm.pinRecentBrush(b, false) }
                                        } else {
                                            BrushSwipeAction(PcIcons.SwipePin, "Pin") { vm.pinRecentBrush(b, true) }
                                        },
                                        BrushSwipeAction(PcIcons.SwipeClear, "Clear") { vm.clearRecentBrush(b) },
                                    )
                                } else {
                                    listOf(
                                        BrushSwipeAction(PcIcons.SwipeShare, "Share") { share(b) },
                                        BrushSwipeAction(PcIcons.SwipeDuplicate, "Duplicate") { vm.duplicateBrush(b) },
                                        BrushSwipeAction(PcIcons.SwipeTrash, "Delete", SwipeDelete, Color.White) { deleting = "Delete this brush?" to { vm.deleteBrush(b) } },
                                    )
                                },
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().height(BrushHeight).onGloballyPositioned { brushBounds[key] = it.boundsInWindow() }
                                        .alpha(if (draggedWith.any { d -> d.set == b.set && d.index == b.index }) 0.3f else 1f)
                                        .pointerInput(key, recent) {
                                            // held still, its menu shows (and stays when the finger lifts); moved on
                                            // from there, the menu goes and the brush follows the finger (the Recent
                                            // set's brushes stay where they are). One hold for both: a tap's own
                                            // hold would keep the rest of the press from the drag
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                                vm.swipedBrush = null
                                                focus.clearFocus()
                                                brushMenu = key
                                                val hold = BrushHold(viewConfiguration.touchSlop, movable = !recent)
                                                // the rest of the press is the hold's, taken before the tap under it sees
                                                // it (letting go is no tap), until it drags the brush: the lists' box
                                                // follows it from there (this row may go, as another set opens)
                                                while (true) {
                                                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == long.id }
                                                    if (change == null || change.isConsumed || !change.pressed) {
                                                        change?.consume()
                                                        break
                                                    }
                                                    change.consume()
                                                    if (hold.move(change.position - long.position)) {
                                                        brushMenu = null
                                                        vm.swipedBrush = null
                                                        val now = brushNow
                                                        val bounds = brushBounds[key]
                                                        grab = long.position
                                                        dragAt = (bounds?.topLeft ?: Offset.Zero) + change.position
                                                        dragSize = bounds?.size ?: androidx.compose.ui.geometry.Size.Zero
                                                        draggedWith = draggedTogether(now, vm.pickedBrushes, vm.highlighted(now), vm.brushSets.getOrNull(now.set.toInt())?.brushes.orEmpty())
                                                        dragPointer = long.id
                                                        dragged = now
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                        .semantics {
                                            onLongClick {
                                                brushMenu = key
                                                true
                                            }
                                        }
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) Pc.RowSelected else if (picked) PickedBrush else Color.Transparent)
                                        .clickable {
                                            // with a brush swiped open, a tap only closes it
                                            if (vm.swipedBrush != null) {
                                                vm.swipedBrush = null
                                                return@clickable
                                            }
                                            // and with brushes picked, only lets them go
                                            if (vm.pickedBrushes.isNotEmpty()) {
                                                vm.pickedBrushes.clear()
                                                return@clickable
                                            }
                                            // the brush in use opens in the studio; another becomes the brush
                                            val inUse = vm.highlighted(b)
                                            vm.chooseBrush(b)
                                            if (inUse) vm.openStudio()
                                        },
                                ) {
                                    // the sample stroke filling the cell's height (its ends past the cell's sides),
                                    // under the name
                                    preview?.let {
                                        // the samples are drawn white: dark on the light interface's cells, or in
                                        // the colour a plugin gives
                                        Image(
                                            it, contentDescription = null, contentScale = ContentScale.Crop,
                                            colorFilter = previewFilter(tints, selected),
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    if (vm.renamingBrush == key) {
                                        InlineName(b.name, if (selected || picked) Color.White else Pc.PanelTitle, 14.sp, Modifier.padding(start = 18.dp, top = 11.75.dp, end = 12.dp), FontWeight.SemiBold) {
                                            vm.renameBrush(b, it)
                                        }
                                    } else {
                                        BrushName(b.name, selected || picked, vm.shadedNames[key] == true, Modifier.padding(start = 18.dp, top = 11.75.dp))
                                    }
                                    // a pinned brush's mark, at the cell's top right
                                    if (pinned) {
                                        Icon(
                                            PcIcons.PinMark, contentDescription = "Pinned", tint = if (selected) Color.White else Pc.PanelTitle,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.5.dp, end = 11.dp).requiredSize(16.dp),
                                        )
                                    }
                                }
                            }
                            // held, its menu under it, their left edges in line: in a set Rename, Duplicate,
                            // Share and Delete; in the Recent set Find, Pin or Unpin, Share and Clear
                            LibraryMenu(
                                brushMenu == key, androidx.compose.ui.graphics.TransformOrigin(0f, 0f),
                                MenuPlace(with(density) { MenuTop.roundToPx() }) { a, _ -> IntOffset(a.left, a.bottom) },
                                onDismiss = { brushMenu = null }, overPanel = true,
                            ) {
                                fun pick(action: () -> Unit) {
                                    brushMenu = null
                                    action()
                                }
                                if (recent) {
                                    MenuItem(PcIcons.SwipeFind, "Find", iconSize = 26.dp) { pick { vm.findBrush(b) } }
                                    if (pinned) {
                                        MenuItem(PcIcons.SwipeUnpin, "Unpin", iconSize = 26.dp) { pick { vm.pinRecentBrush(b, false) } }
                                    } else {
                                        MenuItem(PcIcons.SwipePin, "Pin", iconSize = 26.dp) { pick { vm.pinRecentBrush(b, true) } }
                                    }
                                } else {
                                    MenuItem(PcIcons.MenuRename, "Rename") { pick { vm.renamingBrush = key } }
                                    MenuItem(PcIcons.MenuDuplicate, "Duplicate") { pick { vm.duplicateBrush(b) } }
                                }
                                MenuDivider()
                                MenuItem(PcIcons.MenuShare, "Share") { pick { share(b) } }
                                MenuDivider()
                                if (recent) {
                                    MenuItem(PcIcons.MenuClear, "Clear") { pick { vm.clearRecentBrush(b) } }
                                } else {
                                    MenuItem(PcIcons.MenuTrash, "Delete", Pc.MenuDanger) { pick { deleting = "Delete this brush?" to { vm.deleteBrush(b) } } }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
            // the dragged brush follows the finger
            dragged?.let { d ->
                val at = dragAt - grab - listBounds.topLeft
                Box(
                    Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                        .size(with(density) { dragSize.width.toDp() }, BrushHeight)
                        .clip(RoundedCornerShape(12.dp)).background(Pc.RowSelected.copy(alpha = 0.9f)),
                ) {
                    vm.previews["${d.set}-${d.index}"]?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = pluginFilter(tints.second), modifier = Modifier.fillMaxSize())
                    }
                    Text(d.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 18.dp, top = 11.dp))
                    // several going together: how many
                    if (draggedWith.size > 1) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(24.dp).clip(CircleShape).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${draggedWith.size}", color = Pc.RowSelected, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
            // and the dragged set
            draggedSet?.let { i ->
                val set = sets.getOrNull(i) ?: return@let
                val at = dragAt - grab - listBounds.topLeft
                Row(
                    Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                        .size(with(density) { (setBounds[i]?.width ?: 0f).toDp() }, 50.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Pc.RowSelected.copy(alpha = 0.9f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PcIcons.SetIcons.getOrElse(set.icon.toInt()) { PcIcons.BrushSet }, contentDescription = null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).requiredSize(40.dp))
                    Text(
                        set.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 1.dp, end = 8.dp),
                    )
                }
            }
        }
        }
        }
        floating?.let { FloatingBottom(it) }
    }

    // while the question is up, brushes read from a file wait to join the library
    LaunchedEffect(deleting != null) { vm.brushQuestionOpen = deleting != null }
    Alert(
        deleting,
        title = { (title, _) -> title },
        message = { "It can't be brought back." },
        action = "Delete",
        icon = PcIcons.Trash,
        onCancel = { deleting = null },
        onAction = { (_, action) ->
            deleting = null
            action()
        },
    )
}
