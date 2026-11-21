package io.github.artcraftandroid.procraft.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.procraft.canvas.*
import kotlin.math.roundToInt

/**
 * Procreate Pocket-inspired Bottom Sheet host for smartphone screens.
 *
 * Keeps the upper portion of the canvas visible while adjusting brushes, layers, colours,
 * actions, or adjustments. Users can tap or drag the top handle pill (44.dp touch target) to
 * toggle between peek height and expanded height, or swipe down to close.
 */
@Composable
fun BoxScope.SmartphoneBottomSheet(
    vm: CanvasViewModel,
    shown: Panel,
    alpha: Float,
    scale: Float,
    screenWidth: Dp,
    screenHeight: Dp,
    content: @Composable (sheetModifier: Modifier, sheetRoom: Dp) -> Unit,
) {
    val isLandscape = screenWidth > screenHeight
    // On landscape phones, constrain sheet width to a comfortable 420.dp centered card
    val sheetWidth = if (isLandscape) minOf(screenWidth - 16.dp, 420.dp) else screenWidth
    var expanded by remember(shown) { mutableStateOf(false) }
    var dragDeltaPx by remember(shown) { mutableFloatStateOf(0f) }

    LaunchedEffect(shown) {
        dragDeltaPx = 0f
    }

    val targetFraction = ResponsiveLayout.sheetFraction(shown, expanded, isLandscape)
    val targetHeight by animateDpAsState(
        targetValue = (screenHeight * targetFraction).coerceAtLeast(240.dp),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 520f),
        label = "bottomSheetHeight",
    )

    val density = LocalDensity.current
    val dragOffsetDp = with(density) { dragDeltaPx.toDp() }
    val effectiveHeight = (targetHeight - dragOffsetDp.coerceAtMost(0.dp)).coerceIn(180.dp, screenHeight - Pc.barShown - 8.dp)
    val slideDownPx = ((1f - alpha) * with(density) { effectiveHeight.toPx() }) + dragDeltaPx.coerceAtLeast(0f)
    val pivot = with(density) {
        Offset(screenWidth.toPx() / 2f, screenHeight.toPx())
    }

    val sheetModifier = Modifier
        .align(Alignment.BottomCenter)
        .width(sheetWidth)
        .heightIn(max = effectiveHeight)
        .offset { IntOffset(0, slideDownPx.roundToInt()) }
        .graphicsLayer {
            this.alpha = alpha
            scaleY = 0.94f + 0.06f * scale
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
        .then(if (vm.panel == Panel.NONE) Modifier.takingNoTouches() else Modifier)

    CompositionLocalProvider(
        LocalCompactSheet provides true,
        LocalSheetWidth provides sheetWidth,
    ) {
        ProvidePanelLook(PanelLook(alpha, 1f, pivot)) {
            Box(sheetModifier) {
                content(Modifier.fillMaxWidth(), effectiveHeight + Pc.BarHeight + 25.dp)
                // Interactive 44.dp touch target drag/tap handle at the top center of the sheet
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(120.dp)
                        .height(ResponsiveLayout.MinTouchTarget)
                        .pointerInput(shown, expanded) {
                            detectTapGestures(onTap = { expanded = !expanded })
                        }
                        .pointerInput(shown, expanded) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    val dismissThreshold = with(density) { 88.dp.toPx() }
                                    val expandThreshold = with(density) { -48.dp.toPx() }
                                    when {
                                        dragDeltaPx > dismissThreshold -> {
                                            if (expanded) {
                                                expanded = false
                                            } else {
                                                vm.closePanels()
                                            }
                                        }
                                        dragDeltaPx < expandThreshold -> expanded = true
                                    }
                                    dragDeltaPx = 0f
                                },
                                onDragCancel = { dragDeltaPx = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDeltaPx = (dragDeltaPx + dragAmount).coerceAtLeast(with(density) { -180.dp.toPx() })
                                },
                            )
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(40.dp, 4.5.dp)
                            .clip(CircleShape)
                            .background(Pc.Grabber),
                    )
                }
            }
        }
    }
}
