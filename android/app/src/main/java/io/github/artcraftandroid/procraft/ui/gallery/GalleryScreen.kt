package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.activity.compose.BackHandler
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.gallery.Artwork
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.artcraftandroid.procraft.core.GalleryAction

/**
 * A gallery cell's sizes: its pitch both ways (the screen's width over the columns, three upright
 * and four on its side, two on a phone: [galleryColumns]), the content's width (40 less) and the
 * thumbnail box's height (82 less).
 */
internal data class CellSize(val pitch: Dp, val width: Dp, val thumb: Dp)

private fun cellSize(screenWidth: Dp, columns: Int): CellSize {
    val pitch = screenWidth / columns
    return CellSize(pitch, pitch - 40.dp, pitch - 82.dp)
}

internal val LocalCell = androidx.compose.runtime.staticCompositionLocalOf { cellSize(747.dp, 3) }
internal val NewCanvasWidth = 347.dp

/** How long an artwork can take to open before the gallery says it is opening. */
private const val SLOW_OPENING_MS = 500L

/** The gallery: every artwork as a thumbnail; + makes a new canvas. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(vm: CanvasViewModel) {
    var newCanvas by remember { mutableStateOf(false) }
    // where each stack's cell is (window pixels), for its artworks to fly out of as it opens
    val stackCellAt = remember { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var renaming by remember { mutableStateOf<Artwork?>(null) }
    // a stack renamed as an artwork is: its name tapped
    var renamingStack by remember { mutableStateOf<io.github.artcraftandroid.procraft.gallery.Stack?>(null) }
    // the app's name tapped: what it is and its version, over the gallery
    var about by remember { mutableStateOf(false) }
    // Gallery Recovery's card: how far its pie has come while it shows, and whether it is done
    var recovery by remember { mutableStateOf<Float?>(null) }
    var recovered by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Artwork?>(null) }
    // the artwork (by folder) whose actions a swipe left is showing
    var swiped by remember { mutableStateOf<String?>(null) }
    // a drag in the gallery: the artwork dragged, and the cell it would be stacked onto (by folder);
    // where it can go, and the way back out of a stack lit while it rests there
    var dragging by remember { mutableStateOf<String?>(null) }
    var dropOnto by remember { mutableStateOf<String?>(null) }
    val dropPlaces = remember { DropPlaces() }
    var backLit by remember { mutableStateOf(false) }
    // Select: the artworks and stacks picked, by folder
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var moreOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var sortCentre by remember { mutableStateOf(240.dp) }
    // Share's formats, open for these artworks
    var sharing by remember { mutableStateOf<GalleryShare?>(null) }
    // artworks waiting for their PDF's quality (several go into one, named as the share says)
    var pdfFor by remember { mutableStateOf<GalleryShare?>(null) }
    // a stack swiped to, asked about deleting
    var deletingStack by remember { mutableStateOf<io.github.artcraftandroid.procraft.gallery.Stack?>(null) }
    // what Select's Delete is asking about
    var deletingSelected by remember { mutableStateOf<io.github.artcraftandroid.procraft.gallery.Pick?>(null) }
    var previewing by remember { mutableStateOf<List<Artwork>?>(null) }
    // which of them Preview starts on
    var previewFrom by remember { mutableIntStateOf(0) }
    // an artwork two fingers are spreading open into Preview
    var opening by remember { mutableStateOf<PinchOpening?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // what Select's actions act on now (the shared core's rules)
    fun picked() = vm.store.pick(vm.galleryEntries, selected, vm.openStack != null)

    // several shared as one PDF are named after the stack open, or the one stack picked alone
    fun shareName(p: io.github.artcraftandroid.procraft.gallery.Pick) =
        (p.entries.singleOrNull() as? io.github.artcraftandroid.procraft.gallery.Stack)?.name ?: galleryShareName(vm.openStack?.name)
    fun endSelecting() {
        selecting = false
        selected.clear()
        moreOpen = false
    }
    // several at once, as a model's material file and images come with it
    val importFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val photo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::newFromPicture)
    }
    if (vm.openStack != null && !selecting && !vm.trashOpen && !vm.backupOpen && !vm.updateSheetOpen && !vm.changelogSheetOpen) BackHandler { vm.showStack(null) }
    LaunchedEffect(Unit) { vm.maybeAutoCheckForUpdates() }
    val dragAllowed = io.github.artcraftandroid.procraft.gallery.canDragReorderGallery(vm.galleryQuery, vm.gallerySort)
    // the band kept clear of the status bar and camera in the gallery's own colour
    ClearBandShows(Pc.GalleryBackground)
    BoxWithConstraints(Modifier.fillMaxSize().background(Pc.GalleryBackground)) {
        val (screenWidth, screenHeight) = maxWidth to maxHeight
        vm.windowPx = androidx.compose.ui.unit.IntSize(constraints.maxWidth, constraints.maxHeight)
        // out of focus while an artwork is being renamed
        val blur by androidx.compose.animation.core.animateDpAsState(
            if (renaming != null || renamingStack != null || about) RenameBlur else 0.dp,
            if (renaming != null || renamingStack != null || about) tween(300) else tween(150),
            label = "blur",
        )
        // the shared core's columns: by the way the screen is turned, or in a narrow window by its width
        val columns = io.github.artcraftandroid.procraft.core.galleryColumns(screenWidth.value, screenWidth > screenHeight).toInt()
        val dropCell = cellSize(screenWidth, columns)
        // the title row laid out for the window (the shared core's rules): the buttons that fit,
        // the rest under More, and the title on one line, shrunk to fit beside them
        val titleText = vm.openStack?.name ?: "ProCraft"
        val titleStyle = androidx.compose.material3.LocalTextStyle.current.merge(androidx.compose.ui.text.TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold))
        val titleMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val titleSize = remember(titleText, titleStyle) { titleMeasurer.measure(titleText, titleStyle, softWrap = false, maxLines = 1).size }
        val headerDensity = LocalDensity.current
        val headerConfig = androidx.compose.ui.platform.LocalConfiguration.current
        val fit = io.github.artcraftandroid.procraft.core.galleryHeaderFit(
            screenWidth.value,
            // in a stack the name follows the way back; selecting, it stands alone
            if (vm.openStack != null && !selecting) 60f else 32f,
            with(headerDensity) { titleSize.width.toDp().value },
            selecting,
            headerConfig.screenWidthDp > headerConfig.screenHeightDp,
            vm.openStack != null,
        )
        val titleHeight = with(headerDensity) { titleSize.height.toDp() }
        // the middle of More in the title row, for its list to hang from
        val moreCentre = fit.capsuleLeft.dp + fit.buttons.takeWhile { it.action != GalleryAction.MORE }.sumOf { it.width.toDouble() }.toFloat().dp +
            (fit.buttons.firstOrNull { it.action == GalleryAction.MORE }?.width ?: 0f).dp / 2
        Column(
            Modifier.fillMaxSize().then(if (blur > 0.dp) Modifier.blur(blur) else Modifier).then(
                galleryDrop(
                    dropPlaces, vm.galleryEntries, dropCell, stacking = vm.openStack == null,
                    onBack = { vm.showStack(null) },
                    onOpen = vm::showStack,
                    onBackLit = { backLit = it },
                    onOver = { dropOnto = it },
                    onEnded = {
                        dragging = null
                        dropOnto = null
                    },
                    onMove = vm::moveInGallery,
                    onTakeOut = vm::takeOutOfStack,
                    onStack = vm::dropOnto,
                    onFiles = vm::importFiles,
                ),
            ),
        ) {
            if (selecting) {
                val pick = picked()
                SelectHeader(
                    title = vm.openStack?.name ?: "ProCraft",
                    fit = fit,
                    titleHeight = titleHeight,
                    count = pick.entries.size,
                    canStack = pick.canStack,
                    onStack = {
                        vm.stackPicked(picked().entries)
                        endSelecting()
                    },
                    onPreview = {
                        previewFrom = 0
                        previewing = picked().artworks
                    },
                    onShare = { picked().let { p -> sharing = GalleryShare(p.artworks, null, shareName(p)) } },
                    onDuplicate = {
                        vm.duplicateEntries(picked().entries)
                        endSelecting()
                    },
                    onDelete = { deletingSelected = picked() },
                    onMore = { moreOpen = !moreOpen },
                    onDone = ::endSelecting,
                )
            } else {
                GalleryHeader(
                    stack = vm.openStack?.name,
                    fit = fit,
                    titleHeight = titleHeight,
                    onMore = { moreOpen = !moreOpen },
                    backLit = backLit,
                    onBackPlaced = { dropPlaces.back = it },
                    onBack = { vm.showStack(null) },
                    onAbout = { about = true },
                    onSelect = { selecting = true },
                    onNew = { newCanvas = !newCanvas },
                    onImport = { importFile.launch(arrayOf("*/*")) },
                    onPhoto = { photo.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                GalleryUtilityBar(
                    query = vm.galleryQuery,
                    stackName = vm.openStack?.name,
                    sort = vm.gallerySort,
                    trashCount = vm.trashCount,
                    screenWidth = screenWidth,
                    onQueryChange = { vm.galleryQuery = it },
                    onSortClick = { sortOpen = !sortOpen },
                    onSortCentreChanged = { sortCentre = it },
                    onTrashClick = {
                        moreOpen = false
                        sortOpen = false
                        vm.trashOpen = true
                    },
                    onBackupClick = {
                        moreOpen = false
                        sortOpen = false
                        vm.backupOpen = true
                    },
                )
                androidx.compose.animation.AnimatedVisibility(visible = vm.shouldShowUpdateBanner()) {
                    GalleryUpdateBanner(
                        vm = vm,
                        screenWidth = screenWidth,
                        onOpen = {
                            moreOpen = false
                            sortOpen = false
                            vm.openUpdateSheet(autoCheckIfIdle = false)
                        },
                        onDismiss = vm::dismissUpdateBanner,
                    )
                }
            }
            // into a stack the gallery goes at once and the stack's artworks fly out of its cell to
            // their places; back out, they fly back into it as the gallery grows back into place
            // under them, fading in
            val shownEntries = remember(vm.galleryEntries, vm.galleryQuery, vm.gallerySort) {
                io.github.artcraftandroid.procraft.gallery.filterAndSortGallery(vm.galleryEntries, vm.galleryQuery, vm.gallerySort)
            }
            androidx.compose.animation.AnimatedContent(
                targetState = vm.openStack to shownEntries,
                contentKey = { it.first?.dir?.path },
                transitionSpec = {
                    if (targetState.first != null && initialState.first == null) {
                        androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.fadeOut(snap())
                    } else {
                        // the stack's artworks fly over the gallery coming back
                        androidx.compose.animation.ContentTransform(
                            androidx.compose.animation.fadeIn(Motion.stackPop()) +
                                androidx.compose.animation.scaleIn(Motion.stackPop(), initialScale = Motion.STACK_POP_SCALE),
                            androidx.compose.animation.ExitTransition.KeepUntilTransitionsFinished,
                            targetContentZIndex = -1f,
                            sizeTransform = null,
                        )
                    }
                },
                label = "stack",
            ) { (stack, entries) ->
                // where the stack's cell was, for its artworks to fly out of (and back into)
                val stackFrom = remember(stack) { stack?.let { stackCellAt[it.dir.name] } }
                val leaving = transition.targetState == androidx.compose.animation.EnterExitState.PostExit
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        if (vm.galleryQuery.isNotBlank()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                Text("No artworks match “${vm.galleryQuery.trim()}”", color = Pc.GalleryText, fontSize = 17.sp)
                                Box(
                                    Modifier
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Pc.Capsule)
                                        .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(12.dp))
                                        .clickable { vm.galleryQuery = "" }
                                        .padding(horizontal = 18.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Clear search", color = Pc.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            Text("Tap + to start a new canvas", color = Pc.GalleryText, fontSize = 17.sp)
                        }
                    }
                } else {
                    val cell = cellSize(screenWidth, columns)
                    androidx.compose.runtime.CompositionLocalProvider(LocalCell provides cell) {
                    // the whole screen under the header, so artworks flying in from a stack lower down
                    // aren't clipped by a grid of one short row
                    val grid = if (stack == null) vm.galleryGrid else androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    LazyVerticalGrid(
                        // the grid showing (not one going) is where drags land
                        modifier = Modifier.fillMaxSize().onGloballyPositioned {
                            if (stack?.dir == vm.openStack?.dir) {
                                dropPlaces.grid = grid
                                dropPlaces.gridAt = it.positionInRoot()
                                dropPlaces.gridHeight = it.size.height.toFloat()
                            }
                        },
                        state = grid,
                        columns = GridCells.Fixed(columns),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(entries, key = { it.dir.name }) { entry ->
                            if (entry is io.github.artcraftandroid.procraft.gallery.Stack) {
                                val faint = androidx.compose.animation.core.animateFloatAsState(
                                    if (dragging == entry.dir.name) DRAGGED_ALPHA else 1f, Motion.artworkLift(), label = "dragged",
                                )
                                val view = LocalView.current
                                val density = androidx.compose.ui.platform.LocalDensity.current
                                // swiped open, it fades back and its actions grow in over it, as an artwork's
                                val open = androidx.compose.animation.core.animateFloatAsState(
                                    if (swiped == entry.dir.name) 1f else 0f, Motion.swipeActions(), label = "swipe",
                                ).value
                                // selecting, a tap picks it (or lets it go), as an artwork's
                                val stackChosen = entry.dir.name in selected
                                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                                    StackCell(
                                        entry, vm.galleryVersion, chosen = if (selecting) stackChosen else null, wash = dropOnto == entry.dir.name,
                                        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = faint.value * (1f - SWIPED_FADE * open.coerceIn(0f, 1f)) },
                                        onPlaced = { stackCellAt[entry.dir.name] = it },
                                        onSwipe = { dx, dy -> swipeOpens(dx, dy, selecting)?.let { opens -> swiped = if (opens) entry.dir.name else null } },
                                        onDrag = { touch ->
                                            // its top artwork's picture, lifted, held where the finger is on it
                                            val placed = stackCellAt[entry.dir.name]
                                            val top = entry.artworks.firstOrNull()
                                            if (!selecting && dragAllowed && placed != null && top != null) {
                                                val box = with(density) {
                                                    androidx.compose.ui.geometry.Rect(
                                                        placed.center.x - cell.width.toPx() / 2f, placed.top + 39.dp.toPx(),
                                                        placed.center.x + cell.width.toPx() / 2f, placed.top + 39.dp.toPx() + cell.thumb.toPx(),
                                                    )
                                                }
                                                val picture = fitted(box, top)
                                                val held = (touch - picture.topLeft) * Motion.ARTWORK_LIFTED
                                                val w = (picture.width * Motion.ARTWORK_LIFTED).toInt()
                                                val h = (picture.height * Motion.ARTWORK_LIFTED).toInt()
                                                if (io.github.artcraftandroid.procraft.gallery.startStackDrag(view, entry, w, h, android.graphics.Point(held.x.toInt(), held.y.toInt()))) {
                                                    dragging = entry.dir.name
                                                }
                                            }
                                        },
                                        onRename = {
                                            // a tap puts away the actions showing; selecting, it picks the stack
                                            when {
                                                swiped != null -> swiped = null
                                                !selecting -> renamingStack = entry
                                                stackChosen -> selected.remove(entry.dir.name)
                                                else -> selected.add(entry.dir.name)
                                            }
                                        },
                                    ) {
                                        when {
                                            swiped != null -> swiped = null
                                            !selecting -> vm.showStack(entry)
                                            stackChosen -> selected.remove(entry.dir.name)
                                            else -> selected.add(entry.dir.name)
                                        }
                                    }
                                    if (open > 0f) {
                                        // a stack's act on every artwork in it
                                        SwipeActions(
                                            open,
                                            Modifier.align(Alignment.TopCenter).padding(top = 39.dp + cell.thumb / 2 - SwipePillHeight / 2),
                                            onShare = { at -> sharing = GalleryShare(entry.artworks, at, entry.name) },
                                            onDuplicate = {
                                                swiped = null
                                                vm.duplicateStack(entry)
                                            },
                                            onDelete = {
                                                swiped = null
                                                deletingStack = entry
                                            },
                                        )
                                    }
                                }
                                return@items
                            }
                            val a = entry as Artwork
                            val chosen = a.dir.name in selected
                            val view = LocalView.current
                            // swiped open, it fades back and its actions grow in over it
                            val open = androidx.compose.animation.core.animateFloatAsState(
                                if (swiped == a.dir.name) 1f else 0f, Motion.swipeActions(), label = "swipe",
                            ).value
                            // held, its thumbnail shrinks, then lifts (to be dragged), and settles when let go
                            val lift = remember { androidx.compose.animation.core.Animatable(1f) }
                            val cellScope = rememberCoroutineScope()
                            // dragged, it stays behind faint
                            val faint = androidx.compose.animation.core.animateFloatAsState(
                                if (dragging == a.dir.name) DRAGGED_ALPHA else 1f, Motion.artworkLift(), label = "dragged",
                            )
                            // where the cell is in the window, for where the drag picture is held
                            val cellAt = remember { arrayOf(Offset.Zero) }
                            Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).flyIn(stackFrom, Motion.stackPushMove()).flyOut(stackFrom, leaving, Motion.stackPop())) {
                                // across the whole column, so the thumbnail sits in its middle as a stack's does
                                Column(
                                    Modifier.fillMaxWidth().height(cell.pitch).onGloballyPositioned { cellAt[0] = it.positionInWindow() }.graphicsLayer {
                                        alpha = (1f - SWIPED_FADE * open.coerceIn(0f, 1f)) * faint.value *
                                            (if (opening?.artwork?.dir?.name == a.dir.name) 0f else 1f)
                                    }.pointerInput(a, selecting, chosen) {
                                        // a tap opens it (or picks it), and on its name renames it; swiped left, its
                                        // actions show (right, they go); held and moved, it is dragged: within the
                                        // gallery to move it or stack it, or out to other apps; two fingers spread
                                        // on it open it in Preview
                                        awaitEachGesture {
                                            val down = awaitFirstDown()
                                            // a moment held before it shrinks, so a scroll starting on it leaves it be
                                            val press = cellScope.launch {
                                                kotlinx.coroutines.delay(Motion.ARTWORK_PRESS_DELAY_MS)
                                                lift.animateTo(Motion.ARTWORK_PRESSED, Motion.artworkPress())
                                            }
                                            try {
                                                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                                var moved: Offset? = null
                                                var pinched = false
                                                val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                    while (up == null && moved == null) {
                                                        val e = awaitPointerEvent()
                                                        if (e.changes.count { it.pressed } >= 2) {
                                                            pinched = true
                                                            break
                                                        }
                                                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                                        val d = c.position - down.position
                                                        if (!c.pressed) up = c else if (d.getDistance() > viewConfiguration.touchSlop) moved = d
                                                    }
                                                    false
                                                } ?: true
                                                if (pinched) {
                                                    // the picture comes out of its place and follows the fingers, the
                                                    // gallery darkening behind it; let go, it opens or goes back
                                                    press.cancel()
                                                    val shown = vm.thumbAt[a.dir.name]
                                                    if (selecting || shown == null) return@awaitEachGesture
                                                    val o = PinchOpening(a, shown).also { opening = it }
                                                    var from: Float? = null
                                                    var middle: Offset? = null
                                                    while (true) {
                                                        val e = awaitPointerEvent()
                                                        val fingers = e.changes.filter { it.pressed }
                                                        if (fingers.size < 2) break
                                                        e.changes.forEach { it.consume() }
                                                        val apart = (fingers[0].position - fingers[1].position).getDistance()
                                                        val between = (fingers[0].position + fingers[1].position) / 2f
                                                        val start = from ?: apart.also { from = it }
                                                        val first = middle ?: between.also { middle = it }
                                                        o.scale = (apart / start.coerceAtLeast(1f)).coerceAtLeast(PINCH_LEAST)
                                                        o.moved = between - first
                                                    }
                                                    o.letGo = true
                                                    return@awaitEachGesture
                                                }
                                                val d = moved
                                                if (d != null) {
                                                    swipeOpens(d.x, d.y, selecting)?.let { opens -> swiped = if (opens) a.dir.name else null }
                                                    return@awaitEachGesture
                                                }
                                                if (!held) {
                                                    up?.let {
                                                        it.consume()
                                                        when {
                                                            // a tap puts away the actions showing
                                                            swiped != null -> swiped = null
                                                            !selecting && it.position.y > (39.dp + cell.thumb + 8.dp).toPx() -> renaming = a
                                                            !selecting -> vm.openArtwork(a)
                                                            chosen -> selected.remove(a.dir.name)
                                                            else -> selected.add(a.dir.name)
                                                        }
                                                    }
                                                    return@awaitEachGesture
                                                }
                                                if (selecting || !dragAllowed) return@awaitEachGesture
                                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                                cellScope.launch { lift.animateTo(Motion.ARTWORK_LIFTED, Motion.artworkLift()) }
                                                while (true) {
                                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                    c.consume()
                                                    if (!c.pressed) break
                                                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                                        // the picture as big as the lifted thumbnail, held where the finger is on it
                                                        val shown = vm.thumbAt[a.dir.name]
                                                        val scale = Motion.ARTWORK_LIFTED / lift.value
                                                        val w = ((shown?.width ?: cell.width.toPx()) * scale).toInt()
                                                        val h = ((shown?.height ?: cell.thumb.toPx()) * scale).toInt()
                                                        val touch = cellAt[0] + c.position - (shown?.topLeft ?: cellAt[0])
                                                        val held = android.graphics.Point((touch.x * scale).toInt(), (touch.y * scale).toInt())
                                                        if (io.github.artcraftandroid.procraft.gallery.startArtworkDrag(view, a, vm.store.root, w, h, held)) dragging = a.dir.name
                                                        break
                                                    }
                                                }
                                            } finally {
                                                press.cancel()
                                                cellScope.launch { lift.animateTo(1f, Motion.artworkDrop()) }
                                            }
                                        }
                                    },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(
                                        Modifier.padding(top = 39.dp).size(cell.width, cell.thumb)
                                            // the box it is fitted in, for the cell it will be once its canvas is turned
                                            .onGloballyPositioned { if (vm.galleryZoom == null) vm.thumbBoxAt[a.dir.name] = it.boundsInWindow() }
                                            .graphicsLayer {
                                                scaleX = lift.value
                                                scaleY = lift.value
                                            },
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        // where it is, for zooming into it, unless the gallery is zooming itself
                                        Thumbnail(a, vm.galleryVersion, if (selecting) chosen else null, wash = dropOnto == a.dir.name) {
                                            if (vm.galleryZoom == null) vm.thumbAt[a.dir.name] = it
                                        }
                                    }
                                    Text(
                                        a.name,
                                        color = if (selecting && chosen) Color.White else Pc.GalleryText,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 12.dp),
                                    )
                                    Text("${a.width} × ${a.height}px", color = Pc.GallerySize, fontSize = 10.sp)
                                }
                                if (open > 0f) {
                                    SwipeActions(
                                        open,
                                        // centred on the thumbnail's frame
                                        Modifier.align(Alignment.TopCenter).padding(top = 39.dp + cell.thumb / 2 - SwipePillHeight / 2),
                                        onShare = { at -> sharing = GalleryShare(listOf(a), at, a.name) },
                                        onDuplicate = {
                                            swiped = null
                                            vm.duplicateArtwork(a)
                                        },
                                        onDelete = {
                                            swiped = null
                                            deleting = a
                                        },
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }

        if (newCanvas) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { newCanvas = false } })
        // hangs from the + button, 2 dp from the screen's edge, and grows out of its arrow
        val newCanvasMenuWidth = minOf(NewCanvasWidth, maxWidth - 12.dp)
        val newLeft = (maxWidth - 2.dp - newCanvasMenuWidth).coerceAtLeast(6.dp)
        val newArrow = maxWidth - 49.dp - newLeft
        MenuPresence(newCanvas, androidx.compose.ui.graphics.TransformOrigin(newArrow / newCanvasMenuWidth, 0f)) { look ->
            NewCanvasPanel(
                Modifier.offset(x = newLeft, y = 56.5.dp).then(look), arrowX = newArrow, presets = vm.canvasPresets, width = newCanvasMenuWidth,
                // Custom Canvas opens over the list, which is still there when it closes
                onCustom = { vm.openCustomCanvas() },
                onEdit = { vm.openCustomCanvas(it) },
                onDelete = vm::deletePreset,
                onPick = {
                    newCanvas = false
                    vm.newPresetArtwork(it)
                },
                kinds = vm.plugins.kinds(),
                onKind = { kind, w, h ->
                    newCanvas = false
                    vm.newArtwork(w, h, kind.key)
                },
            ) { w, h ->
                newCanvas = false
                vm.newArtwork(w, h)
            }
        }
        if (moreOpen) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { moreOpen = false } })
        if (sortOpen) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { sortOpen = false } })
        // under More, kept 4 from the screen's edge: what the title row has no room for
        val morePlace = popoverPlace(250.dp, moreCentre, maxWidth, minRight = 4.dp)
        val sortPlace = popoverPlace(240.dp, sortCentre, maxWidth, minRight = 4.dp)
        MenuPresence(sortOpen && !selecting, androidx.compose.ui.graphics.TransformOrigin(sortPlace.arrowX / 240.dp, 0f)) { look ->
            GallerySortPopover(
                Modifier.offset(x = sortPlace.left, y = 112.dp).then(look),
                sortPlace.arrowX,
                vm.gallerySort,
            ) { pickedSort ->
                sortOpen = false
                vm.setGallerySort(pickedSort)
            }
        }
        // Share's formats under Select's Share, or under More when Share is in its list
        val shareCentre = if (fit.buttons.any { it.action == GalleryAction.SHARE }) selectShareCentre(maxWidth) else moreCentre
        MenuPresence(moreOpen && fit.more.isNotEmpty(), androidx.compose.ui.graphics.TransformOrigin(morePlace.arrowX / 250.dp, 0f)) { look ->
            val place = morePlace
            val pick = picked()
            val any = pick.entries.isNotEmpty()
            val extraRows = if (!selecting) listOf(
                MoreRow(
                    if (vm.trashCount > 0) "Recently Deleted (${vm.trashCount})" else "Recently Deleted",
                    true,
                ) {
                    moreOpen = false
                    vm.trashOpen = true
                },
                MoreRow("Backup & Restore", true) {
                    moreOpen = false
                    vm.backupOpen = true
                },
                MoreRow(
                    vm.activeUpdateRelease()?.let { "Update Available (${it.tagName})" } ?: "Check for Updates",
                    true,
                ) {
                    moreOpen = false
                    vm.openUpdateSheet()
                },
            ) else emptyList()
            val rows = fit.more.map { action ->
                val label = when (action) {
                    GalleryAction.SELECT -> "Select"
                    GalleryAction.IMPORT -> "Import"
                    GalleryAction.PHOTO -> "Photo"
                    GalleryAction.STACK -> "Stack"
                    GalleryAction.PREVIEW -> "Preview"
                    GalleryAction.SHARE -> "Share"
                    GalleryAction.DUPLICATE -> "Duplicate"
                    GalleryAction.DELETE -> "Delete"
                    GalleryAction.MORE -> "More"
                }
                val enabled = when (action) {
                    GalleryAction.SELECT, GalleryAction.IMPORT, GalleryAction.PHOTO -> true
                    GalleryAction.STACK -> pick.canStack
                    else -> any
                }
                MoreRow(label, enabled) {
                    moreOpen = false
                    when (action) {
                        GalleryAction.SELECT -> selecting = true
                        GalleryAction.IMPORT -> importFile.launch(arrayOf("*/*"))
                        GalleryAction.PHOTO -> photo.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        GalleryAction.STACK -> {
                            vm.stackPicked(picked().entries)
                            endSelecting()
                        }
                        GalleryAction.PREVIEW -> {
                            previewFrom = 0
                            previewing = picked().artworks
                        }
                        GalleryAction.SHARE -> picked().let { p -> sharing = GalleryShare(p.artworks, null, shareName(p)) }
                        GalleryAction.DUPLICATE -> {
                            vm.duplicateEntries(picked().entries)
                            endSelecting()
                        }
                        GalleryAction.DELETE -> deletingSelected = picked()
                        GalleryAction.MORE -> {}
                    }
                }
            } + extraRows
            MoreOptions(Modifier.offset(x = place.left, y = 60.dp).then(look), place.arrowX, rows)
        }
        // Share's formats: beside a swiped artwork's Share, or under Select's
        var shown by remember { mutableStateOf<GalleryShare?>(null) }
        sharing?.let { shown = it }
        if (sharing != null) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { sharing = null } })
        shown?.let { share ->
            val density = LocalDensity.current.density
            val from = share.from?.let { r -> androidx.compose.ui.geometry.Rect(r.left / density, r.top / density, r.right / density, r.bottom / density) }
            val place = formatsPlace(from, maxWidth, maxHeight, shareCentre)
            MenuPresence(sharing != null, place.origin) { look ->
                ImageFormats(Modifier.offset(x = place.left, y = place.top).then(look), place.side, place.arrowAt) { format ->
                    sharing = null
                    swiped = null
                    val animated = CanvasViewModel.Animated.entries.firstOrNull { it.title == format }
                    if (animated != null) {
                        share.artworks.firstOrNull()?.let { vm.openForAnimatedExport(it, animated) }
                        if (selecting) endSelecting()
                    } else if (format == "PDF") {
                        pdfFor = share
                    } else {
                        // with the export card; a failure is told, a cancel shares nothing
                        vm.viewModelScope.launch {
                            vm.shareFromGallery(share.artworks, format)?.let { (files, mime) -> runCatching { shareFiles(context, files, mime) } }
                        }
                    }
                }
            }
        }
        PdfQualityAlert(pdfFor != null, onCancel = { pdfFor = null }) { quality ->
            val share = pdfFor ?: return@PdfQualityAlert
            pdfFor = null
            vm.viewModelScope.launch {
                vm.shareFromGallery(share.artworks, "PDF", quality.jpeg, share.group)?.let { (files, mime) -> runCatching { shareFiles(context, files, mime) } }
            }
        }
        opening?.let { o ->
            PinchOpeningOverlay(o, vm.galleryVersion) { opened ->
                if (opened) {
                    val all = vm.artworks
                    previewFrom = all.indexOfFirst { it.dir.name == o.artwork.dir.name }.coerceAtLeast(0)
                    previewing = all
                }
                opening = null
            }
        }
        previewing?.let { list -> ArtworkPreview(list, vm.galleryVersion, previewFrom, playing = vm.inFront) { previewing = null } }
        // a sheet sliding up over the gallery, and back down
        androidx.compose.animation.AnimatedVisibility(
            vm.customCanvasOpen,
            enter = androidx.compose.animation.slideInVertically(Motion.studioIn()) { it },
            exit = androidx.compose.animation.slideOutVertically(Motion.studioOut()) { it },
        ) { CustomCanvasScreen(vm) { newCanvas = false } }
        // only a slow artwork (a big one) shows it is being opened; a quick one just zooms in
        var slowOpening by remember { mutableStateOf(false) }
        LaunchedEffect(vm.opening) {
            slowOpening = false
            if (vm.opening) {
                delay(SLOW_OPENING_MS)
                slowOpening = true
            }
        }
        if (vm.opening && slowOpening) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Text("Opening…", color = Pc.Text, fontSize = 17.sp)
            }
        }
        vm.message?.let { text ->
            LaunchedEffect(text) {
                delay(3000)
                vm.message = null
            }
        }
        FadingValue(renaming, Motion.renameFade()) { a, shown ->
            RenameArtwork(a, vm.galleryVersion, vm.thumbAt[a.dir.name], closing = renaming == null, shown = shown) { name ->
                if (name != a.name) vm.renameArtwork(a, name)
                renaming = null
            }
        }
        // a stack: its top artwork shown, under the stack's name
        FadingValue(renamingStack, Motion.renameFade()) { st, shown ->
            val top = st.artworks.firstOrNull()
            if (top == null) {
                renamingStack = null
                return@FadingValue
            }
            RenameArtwork(top.copy(name = st.name), vm.galleryVersion, vm.thumbAt[top.dir.name], closing = renamingStack == null, shown = shown) { name ->
                if (name != st.name) vm.renameStack(st, name)
                renamingStack = null
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            about,
            enter = androidx.compose.animation.fadeIn(tween(300)),
            exit = androidx.compose.animation.fadeOut(tween(150)),
        ) {
            AboutApp(
                onRecover = {
                    if (recovery == null) {
                        scope.launch {
                            // the card shows long enough to be read, however quick the search
                            recovery = 0f
                            val pie = launch { androidx.compose.animation.core.animate(0f, 0.95f, animationSpec = tween(RECOVERY_SEARCH_MS)) { v, _ -> recovery = v } }
                            vm.recoverLostArtworks()
                            pie.join()
                            recovered = true
                            kotlinx.coroutines.delay(RECOVERY_DONE_MS)
                            recovery = null
                            recovered = false
                            about = false
                        }
                    }
                },
                onBackup = {
                    about = false
                    vm.backupOpen = true
                },
                onCheckUpdates = {
                    about = false
                    vm.openUpdateSheet()
                },
                onChangelog = {
                    vm.openChangelogSheet()
                },
            ) { about = false }
        }
        androidx.compose.animation.AnimatedVisibility(
            vm.trashOpen,
            enter = androidx.compose.animation.slideInVertically(Motion.studioIn()) { it },
            exit = androidx.compose.animation.slideOutVertically(Motion.studioOut()) { it },
        ) { GalleryTrashScreen(vm) { vm.trashOpen = false } }
        androidx.compose.animation.AnimatedVisibility(
            vm.backupOpen,
            enter = androidx.compose.animation.slideInVertically(Motion.studioIn()) { it },
            exit = androidx.compose.animation.slideOutVertically(Motion.studioOut()) { it },
        ) { GalleryBackupScreen(vm) { vm.backupOpen = false } }
        FadingValue(vm.message, Motion.message()) { text, shown ->
            Text(
                text,
                color = Pc.Text,
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = shown }.padding(32.dp).clip(RoundedCornerShape(10.dp))
                    .background(Pc.Panel).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        RecoveryProgress(recovery, recovered)
    }

    // moving what Select picked (a stack with its artworks), or one swiped to, into Recently Deleted
    Alert(
        deletingSelected,
        title = { p ->
            val n = p.entries.size
            if (n == 1) "Move “${p.entries.single().name}” to Trash?" else "Move $n items to Trash?"
        },
        message = { "You can restore them from Recently Deleted within 30 days." },
        action = "Move to Trash",
        icon = PcIcons.Trash,
        onCancel = { deletingSelected = null },
        onAction = { p ->
            vm.deleteEntries(p.entries)
            deletingSelected = null
            endSelecting()
        },
    )
    Alert(
        deletingStack,
        title = { st -> "Move “${st.name}” to Trash?" },
        message = { st -> "Its ${st.artworks.size} artworks will stay in Recently Deleted for 30 days." },
        action = "Move to Trash",
        icon = PcIcons.Trash,
        onCancel = { deletingStack = null },
        onAction = { st ->
            vm.deleteStack(st)
            deletingStack = null
        },
    )
    Alert(
        deleting,
        title = { a -> "Move “${a.name}” to Trash?" },
        message = { "You can restore it from Recently Deleted within 30 days." },
        action = "Move to Trash",
        icon = PcIcons.Trash,
        onCancel = { deleting = null },
        onAction = { a ->
            vm.deleteArtwork(a)
            deleting = null
        },
    )
}

/**
 * The title row: the app's name (in a stack, the way back and its name), the capsule of Select,
 * Import and Photo and the new-canvas button, laid out by [fit]: in a narrow window some or all of
 * the buttons go under More ([onMore]) and the title shrinks to fit ([titleHeight] its height at
 * its own size), never wrapping.
 */
@Composable
private fun GalleryHeader(
    stack: String?,
    fit: io.github.artcraftandroid.procraft.core.GalleryHeaderFit,
    titleHeight: Dp,
    onMore: () -> Unit,
    backLit: Boolean,
    onBackPlaced: (androidx.compose.ui.geometry.Rect) -> Unit,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onSelect: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onPhoto: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(55.dp)) {
        // into a stack the title changes at once; back out, the stack's name fades out as the
        // gallery's fades in
        androidx.compose.animation.AnimatedContent(
            stack,
            transitionSpec = {
                if (targetState == null) {
                    androidx.compose.animation.ContentTransform(
                        androidx.compose.animation.fadeIn(Motion.titleIn()),
                        androidx.compose.animation.fadeOut(Motion.titleOut()),
                        sizeTransform = null,
                    )
                } else {
                    androidx.compose.animation.ContentTransform(
                        androidx.compose.animation.EnterTransition.None,
                        androidx.compose.animation.ExitTransition.None,
                        sizeTransform = null,
                    )
                }
            },
            label = "title",
        ) { name ->
            if (name != null) {
                // in a stack: back to the gallery (blue while touched, or while an artwork dragged
                // rests on it to go back), and the stack's name
                val touch = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val density = LocalDensity.current
                val ink = if (heldOf(touch) || backLit) Pc.Accent else Pc.GalleryTitle
                Row(
                    Modifier.padding(start = 20.dp, top = 12.dp).height(41.dp).onGloballyPositioned {
                        // a drag takes the way back as the title row's whole height there, from the edge
                        val b = it.boundsInRoot()
                        val pad = with(density) { 12.dp.toPx() to 20.dp.toPx() }
                        onBackPlaced(androidx.compose.ui.geometry.Rect(b.left - pad.second, b.top - pad.first, b.right, b.top - pad.first + with(density) { 55.dp.toPx() }))
                    }
                        .clickable(touch, indication = null, onClick = onBack),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                        Icon(PcIcons.ChevronLeft, contentDescription = "Back", tint = ink, modifier = Modifier.size(28.dp))
                    }
                    GalleryTitle(name, ink, fit, Modifier)
                }
            } else {
                // as wide as there is room beside the capsule, the name on one line in it
                Box(Modifier.padding(start = 32.dp, top = 11.dp).size(fit.titleRoom.dp, titleHeight), contentAlignment = Alignment.CenterStart) {
                    GalleryTitle(
                        "ProCraft",
                        Pc.GalleryTitle,
                        fit,
                        Modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onAbout),
                    )
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(45.dp).clip(CircleShape).background(Pc.Capsule).border(1.dp, Pc.CapsuleEdge, CircleShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (b in fit.buttons) {
                    val action: () -> Unit = when (b.action) {
                        GalleryAction.IMPORT -> onImport
                        GalleryAction.PHOTO -> onPhoto
                        GalleryAction.MORE -> onMore
                        else -> onSelect
                    }
                    Box(Modifier.width(b.width.dp).height(45.dp).clickable(onClick = action), contentAlignment = Alignment.Center) {
                        CapsuleLabel(b, fit, Pc.GalleryText)
                    }
                }
            }
            Box(Modifier.padding(start = 12.dp).size(44.dp, 45.dp).clickable(onClick = onNew), contentAlignment = Alignment.Center) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Pc.Capsule).border(1.dp, Pc.CapsuleEdge, CircleShape))
                Icon(PcIcons.Add, contentDescription = "New canvas", tint = Pc.GalleryText, modifier = Modifier.requiredSize(40.dp))
            }
        }
    }
}

/** The title's name ([text]), on one line at its size shrunk as [fit] says, cut short if even then too long. */
@Composable
private fun GalleryTitle(text: String, ink: Color, fit: io.github.artcraftandroid.procraft.core.GalleryHeaderFit, modifier: Modifier) {
    Text(
        text,
        color = ink,
        fontSize = 36.sp * fit.titleScale,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = fit.titleRoom.dp).then(modifier),
    )
}

/** A capsule button's face: its name, or, More alone in the capsule, its glyph. */
@Composable
private fun CapsuleLabel(b: io.github.artcraftandroid.procraft.core.GalleryButton, fit: io.github.artcraftandroid.procraft.core.GalleryHeaderFit, ink: Color) {
    if (b.action == GalleryAction.MORE && fit.buttons.size == 1) {
        Icon(PcIcons.More, contentDescription = b.label, tint = ink, modifier = Modifier.size(24.dp))
    } else {
        Text(b.label, color = ink, fontSize = 16.sp)
    }
}

/**
 * Select's title row: upright, Share, Delete and More; on a wide screen Stack, Preview, Share,
 * Duplicate and Delete side by side (in a stack no Stack); in a narrow window More alone, as [fit]
 * lays it out. Each is dimmed until it can act on the [count] picked artworks and stacks (Stack on
 * two or more, [canStack]; More always opens, its rows greyed). Then the blue round button that
 * ends selecting.
 */
@Composable
private fun SelectHeader(
    title: String,
    fit: io.github.artcraftandroid.procraft.core.GalleryHeaderFit,
    titleHeight: Dp,
    count: Int,
    canStack: Boolean,
    onStack: () -> Unit,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
    val any = count > 0
    Box(Modifier.fillMaxWidth().height(55.dp)) {
        Box(Modifier.padding(start = 32.dp, top = 11.dp).size(fit.titleRoom.dp, titleHeight), contentAlignment = Alignment.CenterStart) {
            GalleryTitle(title, Pc.GalleryTitle, fit, Modifier)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(45.dp).clip(CircleShape).background(Pc.Capsule).border(1.dp, Pc.CapsuleEdge, CircleShape), verticalAlignment = Alignment.CenterVertically) {
                for (b in fit.buttons) {
                    val (enabled, action) = when (b.action) {
                        GalleryAction.STACK -> canStack to onStack
                        GalleryAction.PREVIEW -> any to onPreview
                        GalleryAction.SHARE -> any to onShare
                        GalleryAction.DUPLICATE -> any to onDuplicate
                        GalleryAction.DELETE -> any to onDelete
                        else -> true to onMore
                    }
                    Box(Modifier.width(b.width.dp).height(45.dp).clickable(enabled = enabled, onClick = action), contentAlignment = Alignment.Center) {
                        CapsuleLabel(b, fit, if (enabled) Pc.GalleryTitle else Pc.GallerySize)
                    }
                }
            }
            Box(Modifier.padding(start = 13.dp).size(44.dp, 45.dp).clip(CircleShape).background(Pc.tinted(Color(0xFF0190FF))).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
                Icon(PcIcons.DoneTick, contentDescription = "Done", tint = Color.White, modifier = Modifier.requiredSize(40.dp))
            }
        }
    }
}

private val RenameBlur = 20.dp

/** Where a renamed artwork rises to: fitted in a 281 x 280 frame 20 from the top, the field 20 under it. */
private val RenameFrameWidth = 281.dp
private val RenameFrameHeight = 280.dp
private val RenameTop = 20.dp
private val RenameArtMargin = 12.dp
private val RenameFieldGap = 20.dp
private val RenameFieldWidth = 262.dp
private val RenameFieldHeight = 38.dp
private val RenameFieldFill = Color(0x14FFFFFF)

/**
 * Renaming an artwork: it rises from its cell ([from], window pixels) to the top middle, enlarged,
 * with its name in a field under it and the keyboard up. Done, or tapped away, it calls [onDone]
 * with the name and fades away ([closing], [shown]).
 */
@Composable
private fun RenameArtwork(a: Artwork, version: Int, from: androidx.compose.ui.geometry.Rect?, closing: Boolean, shown: Float, onDone: (String) -> Unit) {
    // the last one decoded shows while it loads again (an artwork opened or closed, saved meanwhile),
    // so a canvas of another colour never flashes white
    val image by produceState(ThumbnailsShown.get(a.thumbnail.path), a.dir.path, a.modified, version) {
        val decoded = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(a.thumbnail.path)?.asImageBitmap() }
        if (decoded != null) ThumbnailsShown.put(a.thumbnail.path, decoded)
        value = decoded ?: value
    }
    var text by remember(a) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(a.name, androidx.compose.ui.text.TextRange(0, a.name.length))) }
    val rise = remember(a) { androidx.compose.animation.core.Animatable(if (from == null) 1f else 0f) }
    LaunchedEffect(a) { rise.animateTo(1f, Motion.renameRise()) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(a) { focus.requestFocus() }
    var done by remember(a) { mutableStateOf(false) }
    // losing the focus it had (not the lack of it before it is first given) ends the renaming
    var focused by remember(a) { mutableStateOf(false) }
    fun finish() {
        if (done) return
        done = true
        onDone(text.text.trim().ifEmpty { a.name })
    }
    BackHandler(enabled = !closing) { finish() }
    val ratio = a.ratio
    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = if (closing) shown else 1f }
            .pointerInput(a) { detectTapGestures { finish() } },
    ) {
        val density = LocalDensity.current
        var origin by remember { mutableStateOf(Offset.Zero) }
        // the art fitted in its frame, centred across the screen
        val (fw, fh) = (RenameFrameWidth - RenameArtMargin * 2) to (RenameFrameHeight - RenameArtMargin * 2)
        val (aw, ah) = if (ratio > fw / fh) fw to fw / ratio else fh * ratio to fh
        val to = with(density) {
            val left = (maxWidth - aw).toPx() / 2f
            val top = (RenameTop + (RenameFrameHeight - ah) / 2).toPx()
            androidx.compose.ui.geometry.Rect(left, top, left + aw.toPx(), top + ah.toPx())
        }
        val start = from?.translate(-origin) ?: to
        Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
            val at = androidx.compose.ui.geometry.lerp(start, to, rise.value)
            val corner = 8.dp.toPx()
            val outline = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(at, androidx.compose.ui.geometry.CornerRadius(corner)))
            }
            drawPath(outline, Color.White)
            image?.let { img ->
                clipPath(outline) {
                    drawImage(
                        img,
                        dstOffset = IntOffset(at.left.toInt(), at.top.toInt()),
                        dstSize = IntSize(at.width.toInt().coerceAtLeast(1), at.height.toInt().coerceAtLeast(1)),
                        filterQuality = thumbnailFilter(img, at.width, density.density),
                    )
                }
            }
        }
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(color = Pc.Text, fontSize = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Pc.Accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { finish() }),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = RenameTop + RenameFrameHeight + RenameFieldGap)
                .size(RenameFieldWidth, RenameFieldHeight).graphicsLayer { alpha = rise.value }
                .clip(RoundedCornerShape(10.dp)).background(RenameFieldFill).focusRequester(focus)
                .onFocusChanged {
                    if (it.isFocused) focused = true else if (focused) finish()
                },
            decorationBox = { field -> Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { field() } },
        )
    }
}

/** How far a swiped artwork fades back under its actions. */
private const val SWIPED_FADE = 0.64f

/** How faint an artwork's cell stays while it is dragged. */
private const val DRAGGED_ALPHA = 0.3f

/** The blue over an artwork (or stack) a dragged artwork would be stacked with. */
internal val DropWash = Color(0x800064DC)

/** How long a dragged artwork rests on a stack (or a stack's way back) before it opens (the gallery): the shared core's. */
internal val SPRING_MS = io.github.artcraftandroid.procraft.core.gallerySpringMs()
