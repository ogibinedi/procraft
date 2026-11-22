package io.github.artcraftandroid.procraft.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewMetrics
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.Panel
import io.github.artcraftandroid.procraft.canvas.extractCanvasViewMetrics
import io.github.artcraftandroid.procraft.canvas.flipCanvas
import io.github.artcraftandroid.procraft.canvas.hasViewMetricsChanged
import io.github.artcraftandroid.procraft.canvas.settleToFit
import kotlinx.coroutines.delay

private const val VIEW_HUD_HOLD_MS = 2200L

private val HudFill get() = if (Pc.light) Color(0xE8EFEFEF) else Color(0xDC222222)
private val HudEdge get() = if (Pc.light) Color(0xFFD0D0D0) else Color(0x40FFFFFF)
private val HudInk get() = if (Pc.light) Color(0xFF222222) else Color(0xFFF0F0F0)
private val HudDivider get() = if (Pc.light) Color(0x26000000) else Color(0x2EFFFFFF)

/**
 * Auto-fading translucent zoom % & rotation angle HUD pill shown when pinching/zooming/rotating
 * the canvas, with 1-tap Fit-to-Screen and Flip Horizontal shortcuts.
 */
@Composable
fun BoxScope.CanvasViewHud(vm: CanvasViewModel) {
    val metrics = remember(vm.viewMatrix) { extractCanvasViewMetrics(vm.viewMatrix) }
    var lastMetrics by remember { mutableStateOf<CanvasViewMetrics?>(null) }
    var visible by remember { mutableStateOf(false) }

    val suppressed = !vm.canvasViewHud ||
        vm.barsHidden ||
        vm.panel != Panel.NONE ||
        vm.transforming ||
        vm.selecting ||
        vm.adjustment != null ||
        vm.opacityLayer != null ||
        vm.recoloring ||
        vm.guideEditing ||
        vm.cropRect != null ||
        vm.textInfo != null ||
        vm.quickShape != null ||
        vm.dropBand != CanvasViewModel.DropBand.NONE ||
        vm.picking != null

    LaunchedEffect(metrics, vm.canvasViewHud) {
        val prev = lastMetrics
        lastMetrics = metrics
        if (!vm.canvasViewHud || suppressed) {
            visible = false
            return@LaunchedEffect
        }
        if (hasViewMetricsChanged(prev, metrics)) {
            visible = true
            delay(VIEW_HUD_HOLD_MS)
            visible = false
        }
    }

    LaunchedEffect(suppressed) {
        if (suppressed) visible = false
    }

    AnimatedVisibility(
        visible = visible && !suppressed,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = Pc.barShown + 10.dp),
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(220)),
    ) {
        Row(
            Modifier
                .height(44.dp)
                .clip(CircleShape)
                .background(HudFill)
                .border(1.dp, HudEdge, CircleShape)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Zoom & Rotation indicator — tapping resets / fits the canvas to screen
            Box(
                Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .clickable {
                        vm.settleToFit()
                        visible = false
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    metrics.label(),
                    color = HudInk,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Box(Modifier.size(1.dp, 20.dp).background(HudDivider))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { vm.flipCanvas(horizontal = true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PcIcons.FlipHorizontal,
                    contentDescription = "Flip Horizontal",
                    tint = HudInk,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/**
 * Optional pinned 44.dp Quick Flip Horizontal button positioned in the bottom corner opposite the
 * sidebar (when enabled in Prefs), hidden automatically during toolbars, panels, or full screen.
 */
@Composable
fun BoxScope.QuickFlipButton(vm: CanvasViewModel) {
    val shown = vm.quickFlipButton &&
        !vm.barsHidden &&
        vm.panel == Panel.NONE &&
        !vm.transforming &&
        !vm.selecting &&
        vm.adjustment == null &&
        vm.opacityLayer == null &&
        !vm.recoloring &&
        !vm.guideEditing &&
        vm.cropRect == null &&
        vm.textInfo == null &&
        vm.animation == null &&
        !vm.modelOpen

    AnimatedVisibility(
        visible = shown,
        modifier = Modifier
            .align(if (vm.rightHanded) Alignment.BottomStart else Alignment.BottomEnd)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(220)),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HudFill)
                .border(1.dp, HudEdge, RoundedCornerShape(14.dp))
                .clickable { vm.flipCanvas(horizontal = true) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PcIcons.FlipHorizontal,
                contentDescription = "Flip Horizontal",
                tint = HudInk,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
