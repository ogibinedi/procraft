package io.github.artcraftandroid.procraft.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.LocalLibraryStats
import io.github.artcraftandroid.procraft.canvas.cancelBackupWork
import io.github.artcraftandroid.procraft.canvas.createBackupToUri
import io.github.artcraftandroid.procraft.canvas.deleteTrashedPermanently
import io.github.artcraftandroid.procraft.canvas.emptyTrash
import io.github.artcraftandroid.procraft.canvas.inspectBackupUri
import io.github.artcraftandroid.procraft.canvas.readLocalLibraryStats
import io.github.artcraftandroid.procraft.canvas.refreshTrash
import io.github.artcraftandroid.procraft.canvas.restoreBackupFromUri
import io.github.artcraftandroid.procraft.canvas.restoreFromTrash
import io.github.artcraftandroid.procraft.gallery.BackupManifest
import io.github.artcraftandroid.procraft.gallery.GalleryBackup
import io.github.artcraftandroid.procraft.gallery.GallerySort
import io.github.artcraftandroid.procraft.gallery.RestoreMode
import io.github.artcraftandroid.procraft.gallery.TrashedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private val DestructiveRed = Color(0xFFFE3B30)
private val UtilityRadius = 12.dp

/**
 * Compact gallery search, sort, trash, and backup bar directly beneath [GalleryHeader].
 * Keeps every control >= 44.dp and adapts cleanly between narrow phones and wide tablets.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun GalleryUtilityBar(
    query: String,
    stackName: String?,
    sort: GallerySort,
    trashCount: Int,
    screenWidth: Dp,
    onQueryChange: (String) -> Unit,
    onSortClick: () -> Unit,
    onSortCentreChanged: (Dp) -> Unit,
    onTrashClick: () -> Unit,
    onBackupClick: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val keyboard = WindowInsets.isImeVisible
    var hadKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(keyboard) {
        if (keyboard) hadKeyboard = true else if (hadKeyboard) {
            hadKeyboard = false
            focus.clearFocus()
        }
    }
    val showLabels = screenWidth >= 540.dp
    val sidePad = if (screenWidth < 480.dp) 16.dp else 20.dp

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Search field
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(UtilityRadius))
                .background(Pc.Capsule)
                .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(UtilityRadius))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hintInk = Pc.GallerySize
            Canvas(Modifier.size(18.dp)) {
                val c = Offset(size.width * 0.43f, size.height * 0.43f)
                val r = size.minDimension * 0.31f
                drawCircle(hintInk, r, c, style = Stroke(1.8.dp.toPx()))
                val d = r * 0.72f
                drawLine(hintInk, c + Offset(d, d), Offset(size.width * 0.9f, size.height * 0.9f), 2.dp.toPx(), cap = StrokeCap.Round)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        if (stackName != null) "Search in $stackName" else "Search artworks",
                        color = hintInk,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Pc.GalleryTitle, fontSize = 15.sp),
                    cursorBrush = SolidColor(Pc.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PcIcons.Cross, contentDescription = "Clear search", tint = Pc.GalleryText, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Sort button
        val sortActive = sort != GallerySort.CUSTOM
        Row(
            Modifier
                .height(44.dp)
                .widthIn(min = 44.dp)
                .onGloballyPositioned { coords ->
                    val cx = coords.boundsInRoot().center.x
                    onSortCentreChanged(with(density) { cx.toDp() })
                }
                .clip(RoundedCornerShape(UtilityRadius))
                .background(Pc.Capsule)
                .border(1.dp, if (sortActive) Pc.Accent else Pc.CapsuleEdge, RoundedCornerShape(UtilityRadius))
                .clickable(onClick = onSortClick)
                .padding(horizontal = if (showLabels) 12.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ink = if (sortActive) Pc.Accent else Pc.GalleryText
            SortGlyph(ink)
            if (showLabels) {
                Text(sort.shortLabel, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }

        // Recently Deleted (Trash) button
        Box(
            Modifier
                .height(44.dp)
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(UtilityRadius))
                .background(Pc.Capsule)
                .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(UtilityRadius))
                .clickable(onClick = onTrashClick)
                .padding(horizontal = if (showLabels) 12.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(PcIcons.Trash, contentDescription = "Recently Deleted", tint = Pc.GalleryText, modifier = Modifier.size(20.dp))
                if (showLabels) {
                    Text("Trash", color = Pc.GalleryText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                if (trashCount > 0) {
                    Box(
                        Modifier
                            .height(18.dp)
                            .widthIn(min = 18.dp)
                            .clip(CircleShape)
                            .background(Pc.Accent)
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (trashCount > 99) "99+" else "$trashCount",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Backup & Restore button
        Row(
            Modifier
                .height(44.dp)
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(UtilityRadius))
                .background(Pc.Capsule)
                .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(UtilityRadius))
                .clickable(onClick = onBackupClick)
                .padding(horizontal = if (showLabels) 12.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ArchiveGlyph(Pc.GalleryText)
            if (showLabels) {
                Text("Backup", color = Pc.GalleryText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SortGlyph(ink: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = 1.8.dp.toPx()
        val y1 = size.height * 0.25f
        val y2 = size.height * 0.50f
        val y3 = size.height * 0.75f
        drawLine(ink, Offset(size.width * 0.14f, y1), Offset(size.width * 0.86f, y1), w, cap = StrokeCap.Round)
        drawLine(ink, Offset(size.width * 0.14f, y2), Offset(size.width * 0.66f, y2), w, cap = StrokeCap.Round)
        drawLine(ink, Offset(size.width * 0.14f, y3), Offset(size.width * 0.44f, y3), w, cap = StrokeCap.Round)
    }
}

@Composable
private fun ArchiveGlyph(ink: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = 1.7.dp.toPx()
        val r = CornerRadius(2.5.dp.toPx())
        // Lid
        drawRoundRect(ink, Offset(size.width * 0.12f, size.height * 0.16f), Size(size.width * 0.76f, size.height * 0.20f), r, style = Stroke(w))
        // Box body
        drawRoundRect(ink, Offset(size.width * 0.18f, size.height * 0.36f), Size(size.width * 0.64f, size.height * 0.48f), r, style = Stroke(w))
        // Handle slot
        drawLine(ink, Offset(size.width * 0.39f, size.height * 0.56f), Offset(size.width * 0.61f, size.height * 0.56f), w, cap = StrokeCap.Round)
    }
}

/** Sort menu popover matching ProCraft's native gallery popovers. */
@Composable
internal fun GallerySortPopover(
    modifier: Modifier,
    arrowX: Dp,
    current: GallerySort,
    onPick: (GallerySort) -> Unit,
) {
    Popover(240.dp, arrowX, modifier, fill = Pc.GalleryPopover) {
        Text(
            "Sort By",
            color = Pc.PanelTitle,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 10.dp),
        )
        Column(
            Modifier
                .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Pc.ActionsGroup),
        ) {
            GallerySort.entries.forEachIndexed { idx, option ->
                if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable { onPick(option) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.label,
                        color = if (option == current) Pc.Accent else Pc.PanelTitle,
                        fontSize = 16.sp,
                        fontWeight = if (option == current) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (option == current) {
                        Icon(PcIcons.MenuTick, contentDescription = "Selected", tint = Pc.Accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Full-screen Recently Deleted view over the gallery. */
@Composable
internal fun GalleryTrashScreen(
    vm: CanvasViewModel,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { vm.refreshTrash() }

    var deletingOne by remember { mutableStateOf<TrashedEntry?>(null) }
    var emptying by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Pc.GalleryBackground)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val columns = if (maxWidth >= 640.dp) 2 else 1
        val sidePad = if (maxWidth < 480.dp) 16.dp else 24.dp

        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PcIcons.ChevronLeft, contentDescription = "Back", tint = Pc.GalleryTitle, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Recently Deleted",
                    color = Pc.GalleryTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val hasItems = vm.trashEntries.isNotEmpty()
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(UtilityRadius))
                        .clickable(enabled = hasItems) { emptying = true }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Empty Trash",
                        color = if (hasItems) DestructiveRed else Pc.GallerySize,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                "Items stay here for 30 days before permanent removal.",
                color = Pc.GallerySize,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = sidePad, vertical = 4.dp),
            )

            if (vm.trashEntries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(PcIcons.Trash, contentDescription = null, tint = Pc.GallerySize, modifier = Modifier.size(40.dp))
                        Text("Recently Deleted is empty", color = Pc.GalleryTitle, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Deleted artworks and stacks appear here so you can recover them anytime within 30 days.",
                            color = Pc.GallerySize,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = sidePad, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.trashEntries, key = { it.id }) { entry ->
                        TrashedEntryCard(
                            entry = entry,
                            onRestore = { vm.restoreFromTrash(entry) },
                            onDelete = { deletingOne = entry },
                        )
                    }
                }
            }
        }
    }

    Alert(
        deletingOne,
        title = { e -> "Delete “${e.originalName}” permanently?" },
        message = { "This artwork will be removed from your device and can't be recovered." },
        action = "Delete Permanently",
        icon = PcIcons.Trash,
        onCancel = { deletingOne = null },
        onAction = { e ->
            vm.deleteTrashedPermanently(e)
            deletingOne = null
        },
    )

    Alert(
        if (emptying) Unit else null,
        title = { "Empty Trash?" },
        message = { "All ${vm.trashEntries.size} items in Recently Deleted will be permanently removed. This can't be undone." },
        action = "Empty Trash",
        icon = PcIcons.Trash,
        onCancel = { emptying = false },
        onAction = {
            vm.emptyTrash()
            emptying = false
        },
    )
}

@Composable
private fun TrashedEntryCard(
    entry: TrashedEntry,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, entry.id) {
        val path = entry.thumbnail?.path ?: return@produceState
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }
    val days = entry.daysRemaining()
    val daysLabel = when {
        days <= 0 -> "Expires today"
        days == 1 -> "1 day left"
        else -> "$days days left"
    }
    val metaLabel = if (entry.isStack) {
        "Stack · ${entry.artworkCount} artworks · $daysLabel"
    } else if (entry.width > 0 && entry.height > 0) {
        "${entry.width} × ${entry.height}px · $daysLabel"
    } else {
        daysLabel
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.Capsule)
            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Pc.ThumbBackground),
                contentAlignment = Alignment.Center,
            ) {
                val img = thumb
                if (img != null) {
                    Image(img, contentDescription = entry.originalName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(PcIcons.Trash, contentDescription = null, tint = Pc.GallerySize, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.originalName,
                    color = Pc.GalleryTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metaLabel,
                    color = Pc.GallerySize,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Pc.Accent)
                    .clickable(onClick = onRestore),
                contentAlignment = Alignment.Center,
            ) {
                Text("Restore", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Pc.Row)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text("Delete", color = DestructiveRed, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Full-screen Backup & Restore archive manager over the gallery. */
@Composable
internal fun GalleryBackupScreen(
    vm: CanvasViewModel,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<LocalLibraryStats?>(null) }
    var pendingRestore by remember { mutableStateOf<Pair<Uri, BackupManifest>?>(null) }

    LaunchedEffect(vm.galleryVersion, vm.trashCount, vm.backupProgress) {
        stats = vm.readLocalLibraryStats()
    }

    BackHandler(enabled = vm.backupProgress == null, onBack = onClose)

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) vm.createBackupToUri(uri)
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val manifest = vm.inspectBackupUri(uri)
                if (manifest == null) {
                    vm.message = "This file isn't a valid ProCraft backup."
                } else {
                    pendingRestore = uri to manifest
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Pc.GalleryBackground)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val sidePad = if (maxWidth < 480.dp) 16.dp else 24.dp
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(enabled = vm.backupProgress == null, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PcIcons.ChevronLeft, contentDescription = "Back", tint = Pc.GalleryTitle, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Backup & Restore",
                    color = Pc.GalleryTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = sidePad, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Device summary card
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Pc.Capsule)
                            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("On This Device", color = Pc.GalleryTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        val s = stats
                        SummaryRow("Artworks & Stacks", s?.artworkEntries?.toString() ?: "—")
                        SummaryRow("Color Palettes", s?.paletteCount?.toString() ?: "—")
                        SummaryRow("Custom Brushes", if (s?.hasCustomBrushes == true) "Included" else "Default only")
                        SummaryRow("Recently Deleted", if ((s?.trashCount ?: 0) > 0) "${s?.trashCount} (excluded)" else "Empty")
                    }

                    // Create Backup card
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Pc.Capsule)
                            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Create Full Backup", color = Pc.GalleryTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Exports artworks, stacks, color palettes, gradients, custom brushes, fonts, color profiles, and canvas presets into a single .procraft-backup archive that you can store safely in Downloads, SD card, or cloud drive.",
                            color = Pc.GallerySize,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (vm.backupProgress == null) Pc.Accent else Pc.Row)
                                .clickable(enabled = vm.backupProgress == null) {
                                    createBackupLauncher.launch(GalleryBackup.suggestedFileName())
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Create Backup Archive", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Restore Backup card
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Pc.Capsule)
                            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(18.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Restore from Backup", color = Pc.GalleryTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Restores artworks, palettes, brushes, and presets from a saved .procraft-backup file. Existing local artworks are preserved alongside restored works.",
                            color = Pc.GallerySize,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Pc.Row)
                                .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(12.dp))
                                .clickable(enabled = vm.backupProgress == null) {
                                    openBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Choose Backup File", color = Pc.GalleryTitle, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Progress overlay while reading or writing an archive
        vm.backupProgress?.let { busy ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xA6000000))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .widthIn(max = 340.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Pc.GalleryPopover)
                        .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(22.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(busy.label, color = Pc.GalleryTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Pc.Row),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(busy.progress.coerceIn(0.02f, 1f))
                                .clip(CircleShape)
                                .background(Pc.Accent),
                        )
                    }
                    Text("${(busy.progress * 100f).roundToInt()}%", color = Pc.GallerySize, fontSize = 14.sp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Pc.Row)
                            .clickable { vm.cancelBackupWork() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Cancel", color = Pc.GalleryTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    Alert(
        pendingRestore,
        title = { "Restore Backup?" },
        message = { (_, manifest) -> formatBackupSummary(manifest) },
        action = "Restore",
        destructive = false,
        onCancel = { pendingRestore = null },
        onAction = { (uri, _) ->
            pendingRestore = null
            vm.restoreBackupFromUri(uri, RestoreMode.MERGE)
        },
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pc.GallerySize, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Pc.GalleryTitle, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}

internal fun formatBackupSummary(manifest: BackupManifest): String {
    val parts = buildList {
        add("${manifest.artworkEntries} artwork" + if (manifest.artworkEntries == 1) "" else "s")
        if (manifest.hasPalettes) add("palettes")
        if (manifest.hasBrushes) add("brushes")
        if (manifest.hasPresets) add("canvas presets")
    }
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(manifest.createdAtMs))
    return "Backup from $date containing ${parts.joinToString(", ")}. Existing local artworks will be kept."
}
