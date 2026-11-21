package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.core.FloatingPanel
import io.github.artcraftandroid.procraft.core.FloatingRoom
import io.github.artcraftandroid.procraft.core.InterfacePart
import io.github.artcraftandroid.procraft.core.floatingFitted
import kotlin.math.roundToInt

// The Layers panel and the brush library torn off into windows of their own, while a plugin lets
// them (the floating_panels hook): each dragged out by a grabber at its top as the colour panel is,
// then a window over the canvas that stays open while one draws, moved by the grabber strip at its
// top, sized from its lower right corner and closed by its Close (its button then hangs it from the
// bar again). Inside, the panel is the same as where it hangs; the window is kept where it was left.

/** The strip a floating panel's window adds above the panel: its grabber, and Close at its right. */
private val FloatingTopHeight = 24.dp

/** How far down a floating window's grabber is (dp, at the window's own size): what is held when it is dragged out. */
const val FloatingGrabY = 12f

/** The strip a floating panel's window adds under the panel, where its corner is sized from. */
private val FloatingBottomHeight = 20.dp

/** How far a panel's grabber is dragged before the panel floats, as the colour panel's. */
private val PanelUndockDrag = 30.dp

/**
 * What a panel's window adds to it while it floats: how wide it shows the panel, and what its grabber strip ([onMove],
 * by a drag in window pixels, then [onLetGo]), its corner ([onResize], likewise) and its Close ([onClose]) do.
 */
class FloatingFrame(
    val width: Dp,
    val onMove: (Offset) -> Unit,
    val onResize: (Offset) -> Unit,
    val onLetGo: () -> Unit,
    val onClose: () -> Unit,
)

/** The strip over a floating panel: its grabber in the middle (the whole strip drags the window), Close at its right. */
@Composable
internal fun FloatingTop(frame: FloatingFrame) {
    // (the window moving under the drag mustn't start it again)
    val now by rememberUpdatedState(frame)
    Box(
        Modifier.fillMaxWidth().height(FloatingTopHeight).pointerInput(Unit) {
            detectDragGestures(onDragEnd = { now.onLetGo() }, onDragCancel = { now.onLetGo() }) { change, delta ->
                change.consume()
                now.onMove(delta)
            }
        },
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(40.dp, 4.dp).clip(CircleShape).background(Pc.Grabber))
        FloatingClose(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 14.dp)) { now.onClose() }
    }
}

/** The room a floating panel's window leaves under the panel, for its corner, which sizes it ([FloatingCorner]). */
@Composable
internal fun FloatingBottom(@Suppress("UNUSED_PARAMETER") frame: FloatingFrame) {
    androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(FloatingBottomHeight))
}

/** A floating panel's lower right corner, two short lines across it: dragged, it sizes the window. */
@Composable
private fun FloatingCorner(frame: FloatingFrame, modifier: Modifier) {
    // (the window growing under the drag mustn't start it again)
    val now by rememberUpdatedState(frame)
    Box(
        modifier.padding(end = 6.dp).size(36.dp, FloatingBottomHeight)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { now.onLetGo() }, onDragCancel = { now.onLetGo() }) { change, delta ->
                    change.consume()
                    now.onResize(delta)
                }
            }
            .drawBehind {
                // two lines across the corner, the nearer shorter
                val ink = Pc.Grabber
                val w = 2.dp.toPx()
                val end = Offset(size.width - 12.dp.toPx(), size.height - 6.dp.toPx())
                for (reach in listOf(10.dp.toPx(), 5.dp.toPx())) {
                    drawLine(ink, end + Offset(-reach, 0f), end + Offset(0f, -reach), w, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            },
    )
}

/**
 * The grabber at the top of a panel hanging from its button, while a plugin lets it float: dragged out past
 * [PanelUndockDrag], the panel goes unseen and its window follows the finger; let go, it floats there.
 */
@Composable
internal fun BoxScope.PanelGrabber(vm: CanvasViewModel, panel: FloatingPanel) {
    Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(40.dp, 4.dp).clip(CircleShape).background(Pc.Grabber))
    var origin by remember { mutableStateOf(Offset.Zero) }
    // the interface's dp, not this panel's: a plugin may show the panels at a size of their own
    val dp = LocalDensity.current.density / LocalPartScale.current
    // where it is in the interface: in the window, less what the interface keeps clear of
    val corner = LocalClearance.current.corner
    Box(
        // (narrower than the colour panel's: clear of a title beside it)
        Modifier.align(Alignment.TopCenter).size(100.dp, 30.dp).onGloballyPositioned { origin = it.positionInRoot() - corner }.pointerInput(vm, panel) {
            var total = Offset.Zero
            detectDragGestures(
                onDragStart = { total = Offset.Zero },
                onDragEnd = { vm.undockedPanel(panel) },
                onDragCancel = { vm.undockedPanel(panel) },
            ) { change, delta ->
                change.consume()
                total += delta
                if (vm.panelUndocking == panel || total.getDistance() > PanelUndockDrag.toPx()) {
                    val at = origin + change.position
                    vm.undockPanel(panel, at.x / dp, at.y / dp)
                }
            }
        },
    )
}

/**
 * The panels floating in windows of their own, each where it was left, at the floating windows' size: over the canvas
 * (which takes no touch under them, and every touch around them), under the panels hanging from the bar. [maxWidth] by
 * [maxHeight] is the window; they keep clear of the side bar as the panels do.
 */
@Composable
fun FloatingPanels(vm: CanvasViewModel, maxWidth: Dp, maxHeight: Dp) {
    val clear = if (vm.showSideBar) Pc.sideBarShown + Pc.PopoverGap else 0.dp
    val room = if (vm.rightHanded) {
        FloatingRoom(0f, 0f, (maxWidth - clear).value, maxHeight.value)
    } else {
        FloatingRoom(clear.value, 0f, maxWidth.value, maxHeight.value)
    }
    SideEffect { vm.floatingRoom = room }
    for (panel in FloatingPanel.entries) {
        val window = vm.floatingWindow(panel) ?: continue
        key(panel) { FloatingPanelWindow(vm, panel, window, room) }
    }
}

@Composable
private fun FloatingPanelWindow(vm: CanvasViewModel, panel: FloatingPanel, kept: io.github.artcraftandroid.procraft.core.FloatingWindow, room: FloatingRoom) {
    val k = Pc.partScale(InterfacePart.FLOATING)
    // inside the window as it is now, which may have shrunk since it was left
    val window = floatingFitted(panel, kept, room, k)
    val dp = LocalDensity.current.density
    // what shows when it opens: the layers' thumbnails, the latest brushes used
    when (panel) {
        FloatingPanel.LAYERS -> LaunchedEffect(Unit) { vm.refreshThumbnailsShown() }
        FloatingPanel.BRUSHES -> LaunchedEffect(vm.currentBrush) { vm.readRecentBrushes() }
    }
    // the same while the window only moves, so the panel in it isn't composed again for that
    val frame = remember(vm, panel, dp, window.width) {
        FloatingFrame(
            width = window.width.dp,
            onMove = { px -> vm.moveFloating(panel, px.x / dp, px.y / dp) },
            onResize = { px -> vm.resizeFloating(panel, px.x / dp, px.y / dp) },
            onLetGo = { vm.keepFloatingWindow(panel) },
            onClose = { vm.closeFloating(panel) },
        )
    }
    // laid out at the floating windows' size, placed in the window's pixels
    PartScaled(InterfacePart.FLOATING) {
        val at = Modifier.offset { IntOffset((window.x * dp).roundToInt(), (window.y * dp).roundToInt()) }
        Box(
            at.size(window.width.dp, window.height.dp).pointerInput(Unit) {
                // the canvas under it takes no touch
                awaitEachGesture { awaitFirstDown().consume() }
            },
        ) {
            val fills = Modifier.fillMaxSize()
            when (panel) {
                FloatingPanel.LAYERS -> LayersPanel(vm, 0.dp, fills, floating = frame)
                FloatingPanel.BRUSHES -> BrushLibrary(vm, 0.dp, fills, floating = frame)
            }
            FloatingCorner(frame, Modifier.align(Alignment.BottomEnd))
        }
    }
}
