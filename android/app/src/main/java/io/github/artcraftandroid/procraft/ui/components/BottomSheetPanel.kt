package io.github.artcraftandroid.procraft.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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

/** Top center grabber hitbox for [SmartphoneBottomSheet]; kept compact so it never overlaps header buttons below y = 20.dp. */
internal val SheetHandleWidth = 88.dp
internal val SheetHandleHeight = 20.dp

/**
 * Computes `(effectiveHeightDp, bottomPaddingDp)` for [SmartphoneBottomSheet] so the sheet
 * lifts cleanly above an active on-screen keyboard and fits within the remaining viewport.
 */
internal fun sheetKeyboardLayout(
    screenHeightDp: Float,
    targetHeightDp: Float,
    dragUpDp: Float,
    topBarDp: Float,
    keyboardDp: Float,
): Pair<Float, Float> {
    if (keyboardDp <= 0f) {
        val maxHeight = (screenHeightDp - topBarDp - 8f).coerceAtLeast(180f)
        val effective = (targetHeightDp - dragUpDp).coerceIn(minOf(180f, maxHeight), maxHeight)
        return effective to 0f
    }
    val maxAboveKeyboard = (screenHeightDp - keyboardDp - 8f).coerceAtLeast(136f)
    val effective = (targetHeightDp - dragUpDp).coerceIn(minOf(180f, maxAboveKeyboard), maxAboveKeyboard)
    return effective to keyboardDp
}

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
    // Keep upward drag (height growth) and downward drag (translation offset) separate so
    // swiping down to dismiss updates only the layout placement phase with zero recompositions.
    var dragUpPx by remember(shown) { mutableFloatStateOf(0f) }
    var dragDownPx by remember(shown) { mutableFloatStateOf(0f) }

    LaunchedEffect(shown) {
        dragUpPx = 0f
        dragDownPx = 0f
    }

    val targetFraction = ResponsiveLayout.sheetFraction(shown, expanded, isLandscape)
    val sheetSpring = remember { spring<Dp>(dampingRatio = 0.85f, stiffness = 520f) }
    val targetHeight by animateDpAsState(
        targetValue = (screenHeight * targetFraction).coerceAtLeast(240.dp),
        animationSpec = sheetSpring,
        label = "bottomSheetHeight",
    )

    val density = LocalDensity.current
    val dismissThresholdPx = remember(density) { with(density) { 88.dp.toPx() } }
    val expandThresholdPx = remember(density) { with(density) { -48.dp.toPx() } }
    val maxDragUpPx = remember(density) { with(density) { -180.dp.toPx() } }
    val dragUpDp = with(density) { dragUpPx.toDp() }
    val keyboardDp = with(density) { rememberKeyboardReach().toDp() }
    val (effectiveHeightVal, bottomPadVal) = sheetKeyboardLayout(
        screenHeightDp = screenHeight.value,
        targetHeightDp = targetHeight.value,
        dragUpDp = dragUpDp.value,
        topBarDp = Pc.barShown.value,
        keyboardDp = keyboardDp.value,
    )
    val effectiveHeight = effectiveHeightVal.dp
    val bottomPad = bottomPadVal.dp
    val effectiveHeightPx = with(density) { effectiveHeight.toPx() }
    val pivot = remember(density, screenWidth, screenHeight) {
        with(density) { Offset(screenWidth.toPx() / 2f, screenHeight.toPx()) }
    }

    val sheetModifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = bottomPad)
        .width(sheetWidth)
        .heightIn(max = effectiveHeight)
        .offset { IntOffset(0, (((1f - alpha) * effectiveHeightPx) + dragDownPx).roundToInt()) }
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
                // Compact drag/tap handle confined strictly to the top 20.dp grabber strip so it
                // never intercepts clicks on header action buttons (e.g. ColorPanel's Float button).
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .width(SheetHandleWidth)
                        .height(SheetHandleHeight)
                        .pointerInput(shown, expanded) {
                            detectTapGestures(onTap = { expanded = !expanded })
                        }
                        .pointerInput(shown, expanded, dismissThresholdPx, expandThresholdPx, maxDragUpPx) {
                            var rawDelta = 0f
                            detectVerticalDragGestures(
                                onDragStart = { rawDelta = 0f },
                                onDragEnd = {
                                    when {
                                        rawDelta > dismissThresholdPx -> {
                                            if (expanded) {
                                                expanded = false
                                            } else {
                                                vm.closePanels()
                                            }
                                        }
                                        rawDelta < expandThresholdPx -> expanded = true
                                    }
                                    rawDelta = 0f
                                    dragUpPx = 0f
                                    dragDownPx = 0f
                                },
                                onDragCancel = {
                                    rawDelta = 0f
                                    dragUpPx = 0f
                                    dragDownPx = 0f
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    rawDelta = (rawDelta + dragAmount).coerceAtLeast(maxDragUpPx)
                                    if (rawDelta >= 0f) {
                                        if (dragUpPx != 0f) dragUpPx = 0f
                                        dragDownPx = rawDelta
                                    } else {
                                        if (dragDownPx != 0f) dragDownPx = 0f
                                        dragUpPx = rawDelta
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(40.dp, 4.5.dp)
                            .clip(CircleShape)
                            .background(Pc.Grabber),
                    )
                }
            }
        }
    }
}
