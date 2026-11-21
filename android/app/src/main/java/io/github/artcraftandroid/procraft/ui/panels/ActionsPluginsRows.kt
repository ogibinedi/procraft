package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.label
import io.github.artcraftandroid.procraft.core.PluginControl
import io.github.artcraftandroid.procraft.core.PluginFile
import io.github.artcraftandroid.procraft.core.PluginInfo
import io.github.artcraftandroid.procraft.core.PluginSlider
import io.github.artcraftandroid.procraft.core.PluginSource
import io.github.artcraftandroid.procraft.core.pluginSliderAlong
import io.github.artcraftandroid.procraft.core.pluginSliderAt
import io.github.artcraftandroid.procraft.core.pluginSliderChoice
import io.github.artcraftandroid.procraft.core.pluginSliderChoices
import io.github.artcraftandroid.procraft.core.pluginSliderChosen
import io.github.artcraftandroid.procraft.core.pluginSliderShown
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.io.File
import io.github.artcraftandroid.procraft.core.PanelRow

/**
 * The plugins, a page of the panel: a card each, drawn from what the list says of them, in the
 * order they were put in. A card held by its name lifts and follows the finger or pen, faint where
 * it was; let go over another card, it takes that one's place. Remove asks first.
 */
@Composable
internal fun PluginsPage(vm: CanvasViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.readPlugins() }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::offerPlugin)
    }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp, 34.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(PcIcons.ChevronLeft, contentDescription = "Back", tint = Pc.PanelTitle, modifier = Modifier.size(22.dp))
        }
        Text("Plugins", color = Pc.PanelTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        Box(Modifier.height(34.dp).clickable { importer.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text("Import", color = Pc.Accent, fontSize = 17.sp, maxLines = 1)
        }
    }
    val shown = vm.pluginList
    // the plugin whose Remove was tapped, while it is asked about
    var removing by remember { mutableStateOf<PluginInfo?>(null) }
    // a card held and dragged: whose, where the finger is and where on the card it took hold
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    // where the cards are (window pixels), for a dragged one to land, and how tall their heads are
    val cardBounds = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val headHeights = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    var listBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // let go over another card: to its place
    fun drop() {
        val id = dragged ?: return
        dragged = null
        val to = shown.indexOfFirst { cardBounds[it.id]?.contains(dragAt) == true }
        if (to >= 0 && shown.getOrNull(to)?.id != id) vm.movePlugin(id, to)
    }
    // (for a hold that began before the list last changed)
    val dropNow by rememberUpdatedState(::drop)
    Box(Modifier.onGloballyPositioned { listBounds = it.boundsInWindow() }) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 13.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
        ) {
            if (shown.isEmpty()) Text("Nothing imported yet. Import a .procraft-plugin file to add one.", color = Pc.ActionsRow, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            for (plugin in shown) androidx.compose.runtime.key(plugin.id) {
                val id = plugin.id
                // gone from the list, it is nowhere: where it last was may be under another card
                androidx.compose.runtime.DisposableEffect(id) {
                    onDispose {
                        cardBounds.remove(id)
                        headHeights.remove(id)
                    }
                }
                PluginCard(
                    plugin,
                    vm,
                    Modifier.onGloballyPositioned { cardBounds[id] = it.boundsInWindow() }.alpha(if (dragged == id) 0.3f else 1f),
                    head = Modifier.onSizeChanged { headHeights[id] = it.height }.pointerInput(id) {
                        // held still, the card lifts; the rest of the press is its drag's, taken before
                        // the list under it sees it (moved first, the list scrolls instead)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            grab = long.position
                            dragAt = (cardBounds[id]?.topLeft ?: Offset.Zero) + long.position
                            dragged = id
                            try {
                                while (true) {
                                    val change = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull { it.id == long.id }
                                    if (change == null || change.isConsumed) {
                                        dragged = null
                                        break
                                    }
                                    val by = change.positionChangeIgnoreConsumed()
                                    change.consume()
                                    if (!change.pressed) {
                                        dropNow()
                                        break
                                    }
                                    dragAt += by
                                }
                            } catch (gone: kotlinx.coroutines.CancellationException) {
                                dragged = null
                                throw gone
                            }
                        }
                    },
                    onRemove = { removing = plugin },
                )
            }
        }
        // the dragged card follows the finger: its name and what it does
        dragged?.let { id ->
            val plugin = shown.firstOrNull { it.id == id } ?: return@let
            val width = cardBounds[id]?.width ?: 0f
            val density = androidx.compose.ui.platform.LocalDensity.current
            val at = dragAt - grab - listBounds.topLeft
            Box(
                Modifier.offset { androidx.compose.ui.unit.IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .size(with(density) { width.toDp() }, with(density) { (headHeights[id] ?: 0).toDp() })
                    .clip(RoundedCornerShape(22.dp)).background(Pc.RowSelected.copy(alpha = 0.9f)),
            ) {
                PluginHead(plugin, lifted = true)
            }
        }
    }
    Alert(
        removing,
        title = { "Remove this plugin?" },
        message = { "“${it.name}” goes, and its settings with it." },
        action = "Remove",
        icon = PcIcons.Trash,
        onCancel = { removing = null },
        onAction = {
            removing = null
            vm.removePlugin(it)
        },
    )
}

/** A plugin card's head: its name, what it does and what is the matter with it; all in white while it is [lifted] and dragged. */
@Composable
private fun PluginHead(plugin: PluginInfo, modifier: Modifier = Modifier, lifted: Boolean = false) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(plugin.name, color = if (lifted) Color.White else Pc.PanelTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(plugin.description, color = if (lifted) Color.White else Pc.ActionsRow, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        plugin.problem?.let { Text(it, color = if (lifted) Color.White else Pc.SlideValue, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/**
 * A plugin: its head ([head] where it is held to drag it), its controls whatever they are, what it
 * does when tapped, and the rows that turn it on or off and remove it ([onRemove], which asks).
 */
@Composable
private fun PluginCard(plugin: PluginInfo, vm: CanvasViewModel, modifier: Modifier, head: Modifier, onRemove: () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pc.ActionsGroup)) {
        PluginHead(plugin, modifier = head)
        if (plugin.on && plugin.problem == null) {
            for (control in plugin.controls) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
                PluginControlRow(control, vm)
            }
            for (action in plugin.actions) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
                PluginActionRow(action.label) { vm.runPluginAction(action.key) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
        ActionRowView(Act(if (plugin.on) "Turn off" else "Turn on") { vm.setPluginOn(plugin, !plugin.on) })
        if (plugin.source == PluginSource.FILE) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
            ActionRowView(Act("Remove", onClick = onRemove))
        }
    }
}

/** A plugin's control as its card shows it, whatever it is: a switch, a slider, a setting named at every step, a file. */
@Composable
private fun PluginControlRow(control: PluginControl, vm: CanvasViewModel) {
    when (control) {
        is PluginControl.Switch -> ActionRowView(Toggle(control.control.label, control.control.on) { vm.setPluginSwitch(control.control.key, it) })
        is PluginControl.Slider -> {
            val choices = pluginSliderChoices(control.control)
            if (choices.isEmpty()) PluginSliderRow(control.control, vm::setPluginSlider)
            else PluginChoiceRow(control.control, choices, vm::setPluginSlider)
        }
        is PluginControl.File -> PluginFileRow(control.control, vm)
    }
}

/**
 * The controls and actions the plugins that are on put in the Canvas tab, after its own rows: a
 * group of their own, each row as on its plugin's card.
 */
@Composable
internal fun PluginCanvasRows(vm: CanvasViewModel) {
    val list = vm.pluginList
    val controls = remember(list) { vm.plugins.controlsIn(io.github.artcraftandroid.procraft.core.PluginPlace.CANVAS) }
    val actions = remember(list) { vm.plugins.actionsIn(io.github.artcraftandroid.procraft.core.PluginPlace.CANVAS) }
    if (controls.isEmpty() && actions.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pc.ActionsGroup)) {
        controls.forEachIndexed { i, control ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
            PluginControlRow(control, vm)
        }
        actions.forEachIndexed { i, action ->
            if (i > 0 || controls.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
            PluginActionRow(action.label) { vm.runPluginAction(action.key) }
        }
    }
}

/**
 * A plugin's own panel, while its action keeps it open: floating under the bar at the right, its
 * title and Done, then its rows on a card as the Actions panel's are. A slider is told as it lets go,
 * anything else at once.
 */
@Composable
fun PluginPanelView(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val panel = vm.pluginPanel ?: return
    Popover(340.dp, 0.dp, modifier, side = ArrowSide.NONE) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(panel.title, color = Pc.PanelTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            Text("Done", color = Pc.Accent, fontSize = 17.sp, maxLines = 1, modifier = Modifier.clickable { vm.closePluginPanel() })
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 10.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Pc.ActionsGroup)) {
                panel.rows.forEachIndexed { i, row ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
                    when (row) {
                        is PanelRow.Text -> Text(row.text, color = Pc.ActionsRow, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
                        is PanelRow.Slider -> PanelSliderRow(row) { vm.touchPluginPanel(i, it) }
                        is PanelRow.Switch -> ActionRowView(Toggle(row.label, row.on) { vm.touchPluginPanel(i, if (it) 1f else 0f) })
                        is PanelRow.Choice -> PanelChoiceRow(row) { vm.touchPluginPanel(i, it.toFloat()) }
                        is PanelRow.Button -> PluginActionRow(row.label) { vm.touchPluginPanel(i, 1f) }
                        is PanelRow.Colours -> PanelColoursRow(row) { vm.touchPluginPanel(i, it.toFloat()) }
                        is PanelRow.Keys -> PanelKeysRow(row) { vm.touchPluginPanel(i, 1f) }
                    }
                }
            }
        }
    }
}

/** A slider on a plugin's panel: its value whole with whole steps, else to two places, then its unit. */
@Composable
private fun PanelSliderRow(row: PanelRow.Slider, onSet: (Float) -> Unit) {
    var moved by remember(row) { mutableStateOf<Float?>(null) }
    val value = moved ?: row.value
    val whole = row.step >= 1f && row.step % 1f == 0f
    val shown = (if (whole) value.roundToInt().toString() else String.format(java.util.Locale.ROOT, "%.2f", value)) + row.unit
    SlideRow(
        Slide(
            row.label,
            shown,
            (value - row.least) / (row.most - row.least),
            onDone = {
                moved?.let(onSet)
                moved = null
            },
            onChange = { along ->
                val to = row.least + along * (row.most - row.least)
                val stepped = if (row.step > 0f) row.least + ((to - row.least) / row.step).roundToInt() * row.step else to
                moved = stepped.coerceIn(row.least, row.most)
            },
        ),
    )
}

/** A choice on a plugin's panel: its label, the one picked in blue; a tap picks the next. */
@Composable
private fun PanelChoiceRow(row: PanelRow.Choice, onPick: (Int) -> Unit) {
    val count = row.options.size.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable { onPick((row.chosen.toInt() + 1) % count) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.label, color = Pc.ActionsRow, fontSize = 17.sp, maxLines = 1)
        Box(Modifier.weight(1f))
        Text(row.options.getOrElse(row.chosen.toInt()) { "" }, color = Pc.Accent, fontSize = 17.sp, maxLines = 1)
    }
}

/** A row of colours on a plugin's panel: its label, then each colour a disc, the one chosen ringed; a tap picks one. */
@Composable
private fun PanelColoursRow(row: PanelRow.Colours, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.label, color = Pc.ActionsRow, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
        Box(Modifier.weight(1f))
        row.colours.forEachIndexed { at, colour ->
            val chosen = row.chosen?.toInt() == at
            Box(Modifier.size(30.dp).clip(CircleShape).clickable { onPick(at) }, contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape)
                        .then(if (chosen) Modifier.border(2.dp, Pc.ActionsRow, CircleShape) else Modifier)
                        .padding(if (chosen) 4.dp else 0.dp).clip(CircleShape)
                        .background(Color(0xFF000000L or colour.toLong())),
                )
            }
        }
    }
}

/** A row of keys on a plugin's panel: its label, the keys at its end as a file control's name is; tapped as a button is. */
@Composable
private fun PanelKeysRow(row: PanelRow.Keys, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.label, color = Pc.ActionsRow, fontSize = 17.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.weight(1f))
        Text(row.keys, color = Pc.SlideValue, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

/** One of a plugin's actions on its card: its words in blue, run when tapped. */
@Composable
private fun PluginActionRow(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, color = Pc.Accent, fontSize = 17.sp, maxLines = 1)
    }
}

/** A file control on a card (a sound): its label, then the name of what was picked and Clear, or Import; a tap on the row picks another. */
@Composable
private fun PluginFileRow(file: PluginFile, vm: CanvasViewModel) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.pickPluginSound(file.key, it) }
    }
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable { picker.launch(arrayOf("audio/*")) }.padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(file.label, color = Pc.ActionsRow, fontSize = 17.sp, maxLines = 1)
        Box(Modifier.weight(1f))
        val chosen = file.chosen
        if (chosen != null) {
            Text(chosen, color = Pc.SlideValue, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
            Text("Clear", color = Pc.Accent, fontSize = 17.sp, maxLines = 1, modifier = Modifier.clickable { vm.clearPluginFile(file.key) }.padding(start = 12.dp))
        } else {
            Text("Import", color = Pc.Accent, fontSize = 17.sp, maxLines = 1)
        }
    }
}

/** A setting on a card named at every step: its label, the one chosen in blue; a tap opens a menu of them. */
@Composable
private fun PluginChoiceRow(slider: PluginSlider, choices: List<String>, onSet: (PluginSlider, Float) -> Unit) {
    var open by remember(slider.key) { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable { open = true }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(slider.label, color = Pc.ActionsRow, fontSize = 17.sp, maxLines = 1)
            Box(Modifier.weight(1f))
            Text(pluginSliderShown(slider, slider.value), color = Pc.Accent, fontSize = 17.sp, maxLines = 1)
        }
        MenuPresence(open, androidx.compose.ui.graphics.TransformOrigin(0f, 0f)) { look ->
            ChoiceMenu(
                look,
                choices, pluginSliderChosen(slider, slider.value).toInt(), offsetY = (-7).dp,
                onPick = {
                    onSet(slider, pluginSliderChoice(slider, it.toUInt()))
                    open = false
                },
                onDismiss = { open = false },
            )
        }
    }
}

/** A slider on a card: the row follows the finger, and the value goes in as it lets go, as what it sets may move the row itself, unless it is live. */
@Composable
private fun PluginSliderRow(slider: PluginSlider, onSet: (PluginSlider, Float) -> Unit) {
    var moved by remember(slider.key) { mutableStateOf<Float?>(null) }
    val value = moved ?: slider.value
    SlideRow(
        Slide(
            slider.label,
            pluginSliderShown(slider, value),
            pluginSliderAlong(slider, value),
            onDone = {
                moved?.let { onSet(slider, it) }
                moved = null
            },
            onChange = {
                val to = pluginSliderAt(slider, it)
                if (slider.live) onSet(slider, to) else moved = to
            },
        ),
    )
}

/** The one screen that asks whether to import a plugin file: what it is, and what it can do. Also where a setting that has moved to a plugin is said, once. */
@Composable
fun PluginOfferAlert(vm: CanvasViewModel) {
    Alert(
        vm.movedNotice,
        title = { "Now a plugin" },
        message = { it },
        action = "OK",
        onCancel = vm::movedNoticeRead,
        onAction = { vm.movedNoticeRead() },
        destructive = false,
        alone = true,
    )
    Alert(
        vm.pluginOffer,
        title = { it.name },
        message = { it.message },
        action = "Import",
        onCancel = vm::declinePlugin,
        onAction = { vm.acceptPlugin() },
        destructive = false,
    )
}

/** The 32-bit build on a tablet that runs the 64-bit one: which to install instead, once a version. */
@Composable
fun WrongBuildAlert(vm: CanvasViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Alert(
        Unit.takeIf { vm.wrongBuildNotice },
        title = { "Use the 64-bit version" },
        message = {
            "This is ProCraft's 32-bit version, for older tablets. This tablet runs the 64-bit one, which newer " +
                "Android expects: install the file ending in arm64.apk over this one, and your artworks stay."
        },
        action = "Releases",
        cancel = "Later",
        onCancel = vm::wrongBuildNoticeRead,
        onAction = {
            vm.wrongBuildNoticeRead()
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(RELEASES_PAGE))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        destructive = false,
    )
}

/** Where each release's files are. */
private const val RELEASES_PAGE = "https://github.com/artcraft-android/procraft/releases/latest"

/** Choices as rows of a rounded group, the chosen one a full blue row. */
@Composable
internal fun ChoiceGroup(choices: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Pc.ActionsGroup)) {
        choices.forEachIndexed { i, label ->
            if (i > 0 && i != chosen && i - 1 != chosen) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
            Box(
                Modifier.fillMaxWidth().height(42.dp).background(if (i == chosen) Pc.Accent else Color.Transparent).clickable { onPick(i) }.padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, color = if (i == chosen) Color.White else Pc.PanelTitle, fontSize = 17.sp, maxLines = 1)
            }
        }
    }
}

/** Rows in a rounded group, a line between them (not between sliders). */
@Composable
internal fun Group(rows: List<ActionRow>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Pc.ActionsGroup)) {
        rows.forEachIndexed { i, row ->
            if (i > 0 && !(row is Slide && rows[i - 1] is Slide)) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
            if (row is Slide) SlideRow(row) else ActionRowView(row)
        }
    }
}

/**
 * Pressure and Smoothing, a page of the panel: the app's stabilization, motion filtering and its
 * expression, Tip Attachment, the pressure curve every stroke goes through, and Reset all.
 */
@Composable
internal fun PressurePage(vm: CanvasViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Row(Modifier.padding(start = 8.dp, top = 12.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp, 34.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(PcIcons.ChevronLeft, contentDescription = "Back", tint = Pc.PanelTitle, modifier = Modifier.size(22.dp))
        }
        Text("Pressure & Smoothing", color = Pc.PanelTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
    Column(Modifier.heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 13.dp)) {
        Group(
            listOf(
                Slide("Stabilization", percent(vm.inputStabilization), vm.inputStabilization, 64.dp) { vm.changeInput(stabilization = it) },
                Slide("Motion Filtering", percent(vm.inputMotionFiltering), vm.inputMotionFiltering, 64.dp) { vm.changeInput(motion = it) },
                Slide("Motion Filtering Expression", percent(vm.inputMotionExpression), vm.inputMotionExpression, 64.dp) { vm.changeInput(expression = it) },
            ),
        )
        Spacer(Modifier.height(16.dp))
        // with no filtering it has nothing to do
        val tipChange: ((Boolean) -> Unit)? = if (vm.inputMotionFiltering > 0f) ({ on -> vm.changeInput(tipAttached = on) }) else null
        Group(listOf(Toggle("Tip Attachment", vm.inputTipAttached, onChange = tipChange)))
        Spacer(Modifier.height(24.dp))
        Text("App Pressure Sensitivity", color = Pc.SlideLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 13.dp).height(19.dp))
        Box(Modifier.padding(horizontal = 7.dp).fillMaxWidth().aspectRatio(1f).background(CurveFill)) {
            PressureCurve(vm.inputCurve, Modifier.fillMaxSize(), grid = CurveGrid, edge = CurveEdge, line = CurveLine) { vm.changeInput(curve = it) }
        }
        Spacer(Modifier.height(27.dp))
        Group(listOf(Act("Reset all") { vm.resetInput() }))
    }
}

/** A slider row: the name and value on top, the bar under them; drag or tap along it. */
@Composable
private fun SlideRow(row: Slide) {
    val change by rememberUpdatedState(row.onChange)
    val done by rememberUpdatedState(row.onDone)
    Box(
        Modifier.fillMaxWidth().height(row.tall)
            .pointerInput(Unit) {
                val inset = 16.dp.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2f * inset)).coerceIn(0f, 1f)
                detectTapGestures {
                    change(at(it.x))
                    done()
                }
            }
            .pointerInput(Unit) {
                val inset = 16.dp.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2f * inset)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(onDragStart = { change(at(it.x)) }, onDragEnd = { done() }, onDragCancel = { done() }) { c, _ ->
                    c.consume()
                    change(at(c.position.x))
                }
            }
            .drawBehind {
                val inset = 16.dp.toPx()
                val y = size.height - 17.5.dp.toPx()
                val h = 3.dp.toPx()
                val x = inset + row.fraction.coerceIn(0f, 1f) * (size.width - 2f * inset)
                drawRect(Pc.SlideTrack, Offset(inset, y - h / 2f), Size(size.width - 2f * inset, h))
                drawRect(Pc.Accent, Offset(inset, y - h / 2f), Size(x - inset, h))
                val knob = Size(16.dp.toPx(), 10.dp.toPx())
                drawRoundRect(Color.White, Offset(x - knob.width / 2f, y - knob.height / 2f), knob, CornerRadius(knob.height / 2f))
            },
    ) {
        Text(row.label, color = Pc.SlideLabel, fontSize = 17.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp, top = 9.dp))
        Text(row.value, color = Pc.SlideValue, fontSize = 17.sp, maxLines = 1, modifier = Modifier.align(Alignment.TopEnd).padding(end = 19.dp, top = 9.dp))
    }
}

/** How far a row with a private action slides open, that action's button and its look. */
private val PrivateSlide = 248.dp
private val PrivateWidth = 245.dp
private val PrivateFill = Color(0xFF454545)
private val PrivateInk = Color(0xFFA8A8A8)

/**
 * A row that a swipe to the left slides open, its private action's button coming in from the
 * right; let go past halfway it stays open. A tap on the row closes it again.
 */
@Composable
private fun PrivateSwipe(row: Act, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val slide = remember { androidx.compose.animation.core.Animatable(0f) }
    val full = with(androidx.compose.ui.platform.LocalDensity.current) { PrivateSlide.toPx() }
    fun settle(open: Boolean) {
        scope.launch { slide.animateTo(if (open) full else 0f, Motion.swipeSettle()) }
    }
    Box(
        Modifier.fillMaxWidth().height(44.dp).clipToBounds()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { settle(slide.value > full / 2f) },
                    onDragCancel = { settle(false) },
                ) { c, dx ->
                    c.consume()
                    scope.launch { slide.snapTo((slide.value - dx).coerceIn(0f, full)) }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -slide.value }) { content() }
        if (slide.value > 0f) {
            Box(Modifier.matchParentSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { settle(false) })
            Box(
                Modifier.align(Alignment.CenterEnd).width(PrivateWidth).fillMaxHeight()
                    .graphicsLayer { translationX = full - slide.value }
                    .clip(RoundedCornerShape(13.dp)).background(PrivateFill)
                    .then(
                        row.onPrivate?.let { act ->
                            Modifier.clickable {
                                scope.launch { slide.snapTo(0f) }
                                act()
                            }
                        } ?: Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(row.private.orEmpty(), color = if (row.onPrivate != null) PrivateInk else Pc.ActionsDisabled, fontSize = 17.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActionRowView(row: ActionRow) {
    if (row is Act && row.private != null) {
        PrivateSwipe(row) { ActionRowContent(row) }
    } else {
        ActionRowContent(row)
    }
}

@Composable
private fun ActionRowContent(row: ActionRow) {
    val enabled = when (row) {
        is Act -> row.onClick != null
        is Toggle -> row.onChange != null
        is Heading, is Slide -> true
    }
    // a button row turns blue while it is held, its text white
    val touch = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val lit = animateHighlight(heldOf(touch))
    val ink = androidx.compose.ui.graphics.lerp(if (enabled || (row is Toggle && row.fixed)) Pc.ActionsRow else Pc.ActionsDisabled, Color.White, lit)
    Row(
        Modifier.fillMaxWidth().height(44.dp)
            .then(if (lit > 0f) Modifier.background(Pc.Accent.copy(alpha = lit)) else Modifier)
            .then(if (row is Act && row.onClick != null) Modifier.clickable(touch, indication = null, onClick = row.onClick) else Modifier)
            .then(if (row is Toggle && row.onChange != null) Modifier.clickable { row.onChange.invoke(!row.checked) } else Modifier)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (row) {
            is Heading -> Text(row.label, color = Pc.PanelTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            is Act -> {
                Text(row.label, color = ink, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                row.value?.let { Text(it, color = androidx.compose.ui.graphics.lerp(Pc.TabIdle, Color.White, lit), fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp)) }
            }
            is Toggle -> {
                Text(row.label, color = ink, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Switch(row.checked, enabled)
            }
            is Slide -> SlideRow(row)
        }
    }
}

/**
 * A 63 × 28 switch: a blue track when on, grey when off, with a white knob. Flipped, the knob swells
 * into a clear lens as it slides across, the track's colour fading under it, then settles white.
 */
@Composable
internal fun Switch(checked: Boolean, enabled: Boolean, fixedTrack: Color = Pc.SwitchFixed, fixedKnob: Color = Pc.SwitchFixedKnob, offTrack: Color = Pc.SwitchOff) {
    val on = androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, Motion.switchKnob(), label = "knob").value
    val lens = remember { androidx.compose.animation.core.Animatable(0f) }
    var flipped by remember { mutableStateOf(false) }
    LaunchedEffect(checked) {
        // not when it is first shown
        if (!flipped) {
            flipped = true
            return@LaunchedEffect
        }
        lens.animateTo(1f, Motion.switchSwell())
        kotlinx.coroutines.delay(Motion.SWITCH_HOLD_MS)
        lens.animateTo(0f, Motion.switchSettle())
    }
    val g = lens.value
    val track = if (!enabled) fixedTrack else androidx.compose.ui.graphics.lerp(offTrack, Pc.Accent, on)
    // not clipped to the track: the swollen knob spills over it
    Box(Modifier.size(63.dp, 28.dp)) {
        Box(Modifier.matchParentSize().clip(CircleShape).background(track))
        Box(
            Modifier.offset(x = 2.dp + 22.dp * on, y = 2.dp).size(37.dp, 24.dp)
                .graphicsLayer {
                    scaleX = 1f + Motion.SWITCH_SWELL_X * g
                    scaleY = 1f + Motion.SWITCH_SWELL_Y * g
                }
                .clip(CircleShape)
                .background((if (enabled) Color.White else fixedKnob).copy(alpha = 1f - 0.7f * g))
                .border(1.dp, Color.White.copy(alpha = 0.55f * g), CircleShape),
        )
    }
}


internal fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

internal fun licences(context: Context): String {
    fun asset(name: String) = runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
    return "The interface font is Inter, by The Inter Project Authors.\n\n${asset("fonts/Inter-OFL.txt")}\n\n" +
        "The app keeps Lucide icons (lucide.dev), by Lucide Icons and Contributors.\n\n${asset("icons/LICENSE-lucide.txt")}"
}

/** Offers [files] to other apps through the share sheet. */
internal fun shareFiles(context: Context, files: List<File>, mime: String) {
    if (files.size == 1) return shareFile(context, files[0], mime)
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(shareSheet(context, send, files, mime))
}

/** Offers [file] to other apps through the share sheet. */
internal fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(shareSheet(context, send, listOf(file), mime))
}

/**
 * The share sheet for [send], with saving [files] to the device always among its choices, and to
 * the gallery too for pictures and videos.
 */
private fun shareSheet(context: Context, send: Intent, files: List<File>, mime: String): Intent {
    fun ours(intent: Intent, label: String) =
        android.content.pm.LabeledIntent(intent, context.packageName, label, io.github.artcraftandroid.procraft.R.mipmap.ic_launcher)
    val gallery = ours(io.github.artcraftandroid.procraft.SaveToGalleryActivity.intent(context, files, mime), "Save to Gallery")
        .takeIf { io.github.artcraftandroid.procraft.savesToGallery(mime) }
    val save = ours(io.github.artcraftandroid.procraft.SaveToDeviceActivity.intent(context, files, mime), "Save to device")
    val choices = listOfNotNull(gallery, save).toTypedArray<android.os.Parcelable>()
    return Intent.createChooser(send, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, choices)
}
