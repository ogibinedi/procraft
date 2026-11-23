package io.github.artcraftandroid.procraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.InsertShapeAspect
import io.github.artcraftandroid.procraft.canvas.InsertShapeGeometry
import io.github.artcraftandroid.procraft.canvas.InsertShapeKind
import io.github.artcraftandroid.procraft.canvas.InsertShapeSpec
import io.github.artcraftandroid.procraft.canvas.InsertShapeStyle
import io.github.artcraftandroid.procraft.canvas.ShapePoint
import io.github.artcraftandroid.procraft.canvas.canvasSize
import io.github.artcraftandroid.procraft.canvas.colorRgb
import io.github.artcraftandroid.procraft.canvas.insertShape
import kotlin.math.min
import kotlin.math.roundToInt

private val ShapeCardRadius = 16.dp
private val ShapeCardFill get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF262626)
private val ShapeCardBorder get() = if (Pc.light) Color(0xFFD4D4D4) else Color(0xFF393939)
private val ShapeSubtleFill get() = if (Pc.light) Color(0xFFEBEBEB) else Color(0xFF303030)
private val ShapePrimaryInk get() = if (Pc.light) Color(0xFF222222) else Color(0xFFF0F0F0)
private val ShapeMutedInk get() = if (Pc.light) Color(0xFF595959) else Color(0xFFAAAAAA)
private val ShapeAccentFill get() = Pc.tinted(Color(0xFF0067DF))
private val ShapeSelectedTint get() = if (Pc.light) Color(0xFFE3F0FF) else Color(0xFF1B334D)
private val ShapeSelectedInk get() = if (Pc.light) Color(0xFF0055B8) else Color(0xFF78B8FF)
private val CheckerLight get() = if (Pc.light) Color(0xFFF2F2F2) else Color(0xFF2E2E2E)
private val CheckerDark get() = if (Pc.light) Color(0xFFE0E0E0) else Color(0xFF242424)

/**
 * Responsive full-screen Insert Shape composer: live preview in the active brush color,
 * 8 geometric shapes, contextual sliders/steppers, and direct Insert or Insert & Transform.
 */
@Composable
internal fun InsertShapeSheet(vm: CanvasViewModel, onClose: () -> Unit) {
    BackHandler(enabled = !vm.insertShapeBusy, onBack = onClose)
    var spec by remember { mutableStateOf(vm.lastInsertShape) }
    val activeColor = remember(vm.colorRgb()) { CanvasSpace.color(vm.colorRgb()) }
    val (cw, ch) = remember(vm.session) { vm.canvasSize() }
    val effectiveAspect = if (spec.supportsAspect) spec.aspect else InsertShapeAspect.WIDE_16_9
    val (targetW, targetH) = remember(cw, ch, effectiveAspect) {
        InsertShapeGeometry.targetBitmapSize(cw, ch, effectiveAspect)
    }
    val strokePx = remember(targetW, targetH, spec.clampedStrokeFraction) {
        InsertShapeGeometry.strokeWidthPx(targetW, targetH, spec.clampedStrokeFraction).roundToInt()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pc.GalleryBackground)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Insert Shape",
                color = ShapePrimaryInk,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Box(
                Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(ShapeCardFill)
                    .border(1.dp, ShapeCardBorder, CircleShape)
                    .clickable(enabled = !vm.insertShapeBusy, role = Role.Button, onClick = onClose)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", color = ShapePrimaryInk, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val compact = maxWidth < 460.dp
            val previewHeight = if (maxHeight < 520.dp) 136.dp else if (compact) 172.dp else 204.dp
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Live preview card with transparency checkerboard
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .clip(RoundedCornerShape(ShapeCardRadius))
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(ShapeCardRadius)),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCheckerboard(14.dp.toPx())
                        drawShapePreview(spec, activeColor)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ShapeCardFill.copy(alpha = 0.92f))
                            .border(1.dp, ShapeCardBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        val meta = buildString {
                            append(spec.layerName)
                            append(" · ")
                            append(spec.effectiveStyle.label)
                            append(" · ")
                            append("${targetW}×${targetH} px")
                        }
                        Text(meta, color = ShapePrimaryInk, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // 2x4 shape grid
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ShapeCardRadius))
                        .background(ShapeCardFill)
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(ShapeCardRadius))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val rows = InsertShapeKind.entries.chunked(4)
                    for (rowKinds in rows) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (kind in rowKinds) {
                                val selected = spec.kind == kind
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) ShapeSelectedTint else ShapeSubtleFill)
                                        .border(
                                            width = if (selected) 1.5.dp else 1.dp,
                                            color = if (selected) ShapeSelectedInk else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        .clickable(role = Role.Button) { spec = spec.copy(kind = kind) }
                                        .padding(horizontal = 4.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                                ) {
                                    val glyphColor = if (selected) ShapeSelectedInk else ShapePrimaryInk
                                    Canvas(Modifier.size(22.dp)) {
                                        drawShapeGlyph(kind, glyphColor)
                                    }
                                    Text(
                                        kind.label,
                                        color = glyphColor,
                                        fontSize = if (compact) 11.5.sp else 12.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // Style & Aspect controls (hidden cleanly for Line where only Outline applies)
                if (spec.supportsStyle || spec.supportsAspect) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ShapeCardRadius))
                            .background(ShapeCardFill)
                            .border(1.dp, ShapeCardBorder, RoundedCornerShape(ShapeCardRadius))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (spec.supportsStyle) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Style", color = ShapeMutedInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (style in InsertShapeStyle.entries) {
                                        val chosen = spec.style == style
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (chosen) ShapeAccentFill else ShapeSubtleFill)
                                                .clickable(role = Role.Button) { spec = spec.copy(style = style) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                style.label,
                                                color = if (chosen) Color.White else ShapePrimaryInk,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (spec.supportsAspect) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Proportion", color = ShapeMutedInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (aspect in InsertShapeAspect.entries) {
                                        val chosen = spec.aspect == aspect
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (chosen) ShapeAccentFill else ShapeSubtleFill)
                                                .clickable(role = Role.Button) { spec = spec.copy(aspect = aspect) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                aspect.label,
                                                color = if (chosen) Color.White else ShapePrimaryInk,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Contextual geometry controls
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ShapeCardRadius))
                        .background(ShapeCardFill)
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(ShapeCardRadius))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (spec.effectiveStyle == InsertShapeStyle.OUTLINE) {
                        ShapeControlSlider(
                            label = "Stroke width",
                            valueText = "$strokePx px",
                            fraction = (spec.clampedStrokeFraction - InsertShapeSpec.MIN_STROKE_FRACTION) /
                                (InsertShapeSpec.MAX_STROKE_FRACTION - InsertShapeSpec.MIN_STROKE_FRACTION),
                            onFractionChange = { f ->
                                val next = InsertShapeSpec.MIN_STROKE_FRACTION +
                                    f * (InsertShapeSpec.MAX_STROKE_FRACTION - InsertShapeSpec.MIN_STROKE_FRACTION)
                                spec = spec.copy(strokeFraction = next)
                            },
                        )
                    }

                    if (spec.kind == InsertShapeKind.ROUNDED_RECT) {
                        ShapeControlSlider(
                            label = "Corner radius",
                            valueText = "${(spec.clampedCornerRadiusFraction * 200f).roundToInt()}%",
                            fraction = spec.clampedCornerRadiusFraction / 0.5f,
                            onFractionChange = { f -> spec = spec.copy(cornerRadiusFraction = f * 0.5f) },
                        )
                    }

                    if (spec.kind == InsertShapeKind.POLYGON) {
                        ShapeIntegerStepper(
                            label = "Sides",
                            valueText = "${spec.clampedPolygonSides} (${InsertShapeSpec.polygonName(spec.clampedPolygonSides)})",
                            canDecrease = spec.clampedPolygonSides > InsertShapeSpec.MIN_POLYGON_SIDES,
                            canIncrease = spec.clampedPolygonSides < InsertShapeSpec.MAX_POLYGON_SIDES,
                            onDecrease = { spec = spec.copy(polygonSides = spec.clampedPolygonSides - 1) },
                            onIncrease = { spec = spec.copy(polygonSides = spec.clampedPolygonSides + 1) },
                        )
                    }

                    if (spec.kind == InsertShapeKind.STAR) {
                        ShapeIntegerStepper(
                            label = "Star points",
                            valueText = "${spec.clampedStarPoints}",
                            canDecrease = spec.clampedStarPoints > InsertShapeSpec.MIN_STAR_POINTS,
                            canIncrease = spec.clampedStarPoints < InsertShapeSpec.MAX_STAR_POINTS,
                            onDecrease = { spec = spec.copy(starPoints = spec.clampedStarPoints - 1) },
                            onIncrease = { spec = spec.copy(starPoints = spec.clampedStarPoints + 1) },
                        )
                        ShapeControlSlider(
                            label = "Inner depth",
                            valueText = "${(spec.clampedStarInnerRatio * 100f).roundToInt()}%",
                            fraction = (spec.clampedStarInnerRatio - InsertShapeSpec.MIN_STAR_INNER_RATIO) /
                                (InsertShapeSpec.MAX_STAR_INNER_RATIO - InsertShapeSpec.MIN_STAR_INNER_RATIO),
                            onFractionChange = { f ->
                                val next = InsertShapeSpec.MIN_STAR_INNER_RATIO +
                                    f * (InsertShapeSpec.MAX_STAR_INNER_RATIO - InsertShapeSpec.MIN_STAR_INNER_RATIO)
                                spec = spec.copy(starInnerRatio = next)
                            },
                        )
                    }

                    ShapeControlSlider(
                        label = "Opacity",
                        valueText = "${(spec.clampedOpacity * 100f).roundToInt()}%",
                        fraction = (spec.clampedOpacity - InsertShapeSpec.MIN_OPACITY) / (1f - InsertShapeSpec.MIN_OPACITY),
                        onFractionChange = { f ->
                            val next = InsertShapeSpec.MIN_OPACITY + f * (1f - InsertShapeSpec.MIN_OPACITY)
                            spec = spec.copy(opacity = next)
                        },
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
        }

        // Pinned action bar outside scroll container so controls never cover scrollable content
        Box(
            Modifier
                .fillMaxWidth()
                .background(ShapeCardFill)
                .border(1.dp, ShapeCardBorder),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val busy = vm.insertShapeBusy
                Box(
                    Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ShapeSubtleFill)
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy, role = Role.Button) { vm.insertShape(spec, startTransform = false) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Insert", color = ShapePrimaryInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (busy) ShapeAccentFill.copy(alpha = 0.5f) else ShapeAccentFill)
                        .clickable(enabled = !busy, role = Role.Button) { vm.insertShape(spec, startTransform = true) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "Inserting…" else "Insert & Transform",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShapeControlSlider(
    label: String,
    valueText: String,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val latestOnChange by rememberUpdatedState(onFractionChange)
    var trackWidth by remember { mutableFloatStateOf(1f) }
    var dragFraction by remember { mutableFloatStateOf(clamped) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ShapePrimaryInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueText, color = ShapeMutedInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val next = (pos.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        dragFraction = next
                        latestOnChange(next)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            trackWidth = size.width.coerceAtLeast(1).toFloat()
                            val next = (pos.x / trackWidth).coerceIn(0f, 1f)
                            dragFraction = next
                            latestOnChange(next)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (dragFraction + dragAmount / trackWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            dragFraction = next
                            latestOnChange(next)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val trackH = 6.dp.toPx()
                val cy = size.height * 0.5f
                val thumbR = 11.dp.toPx()
                val usableW = (size.width - thumbR * 2f).coerceAtLeast(1f)
                val cx = thumbR + usableW * clamped
                drawRoundRect(
                    color = ShapeSubtleFill,
                    topLeft = Offset(thumbR, cy - trackH * 0.5f),
                    size = Size(usableW, trackH),
                    cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
                )
                drawRoundRect(
                    color = ShapeAccentFill,
                    topLeft = Offset(thumbR, cy - trackH * 0.5f),
                    size = Size((cx - thumbR).coerceAtLeast(0f), trackH),
                    cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
                )
                drawCircle(color = ShapeCardBorder, radius = thumbR + 1.dp.toPx(), center = Offset(cx, cy))
                drawCircle(color = Color.White, radius = thumbR, center = Offset(cx, cy))
            }
        }
    }
}

@Composable
private fun ShapeIntegerStepper(
    label: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = ShapePrimaryInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(valueText, color = ShapeMutedInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (canDecrease) ShapeSubtleFill else ShapeSubtleFill.copy(alpha = 0.45f))
                .border(1.dp, ShapeCardBorder, RoundedCornerShape(10.dp))
                .clickable(enabled = canDecrease, role = Role.Button, onClick = onDecrease),
            contentAlignment = Alignment.Center,
        ) {
            Text("−", color = if (canDecrease) ShapePrimaryInk else ShapeMutedInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (canIncrease) ShapeSubtleFill else ShapeSubtleFill.copy(alpha = 0.45f))
                .border(1.dp, ShapeCardBorder, RoundedCornerShape(10.dp))
                .clickable(enabled = canIncrease, role = Role.Button, onClick = onIncrease),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = if (canIncrease) ShapePrimaryInk else ShapeMutedInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun DrawScope.drawCheckerboard(cellPx: Float) {
    val cols = (size.width / cellPx).toInt() + 1
    val rows = (size.height / cellPx).toInt() + 1
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            drawRect(
                color = if ((r + c) % 2 == 0) CheckerLight else CheckerDark,
                topLeft = Offset(c * cellPx, r * cellPx),
                size = Size(cellPx, cellPx),
            )
        }
    }
}

private fun DrawScope.drawShapePreview(spec: InsertShapeSpec, color: Color) {
    val padX = 28.dp.toPx()
    val padTop = 18.dp.toPx()
    val padBottom = 38.dp.toPx()
    val availW = (size.width - padX * 2f).coerceAtLeast(24f)
    val availH = (size.height - padTop - padBottom).coerceAtLeast(24f)
    val effectiveAspect = if (spec.supportsAspect) spec.aspect else InsertShapeAspect.WIDE_16_9
    val ratio = effectiveAspect.widthRatio / effectiveAspect.heightRatio
    val boxW: Float
    val boxH: Float
    if (availW / availH >= ratio) {
        boxH = availH
        boxW = boxH * ratio
    } else {
        boxW = availW
        boxH = boxW / ratio
    }
    val strokePx = InsertShapeGeometry.strokeWidthPx(boxW.roundToInt(), boxH.roundToInt(), spec.clampedStrokeFraction)
    val inset = if (spec.effectiveStyle == InsertShapeStyle.OUTLINE) strokePx * 0.5f else 0f
    val left = (size.width - boxW) * 0.5f + inset
    val top = padTop + (availH - boxH) * 0.5f + inset
    val right = (size.width + boxW) * 0.5f - inset
    val bottom = padTop + (availH + boxH) * 0.5f - inset
    if (right <= left || bottom <= top) return

    val paintColor = color.copy(alpha = spec.clampedOpacity)
    val style = if (spec.effectiveStyle == InsertShapeStyle.OUTLINE) {
        Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    } else {
        Fill
    }

    when (spec.kind) {
        InsertShapeKind.RECTANGLE -> drawRect(paintColor, Offset(left, top), Size(right - left, bottom - top), style = style)
        InsertShapeKind.ROUNDED_RECT -> {
            val r = min(right - left, bottom - top) * spec.clampedCornerRadiusFraction
            drawRoundRect(paintColor, Offset(left, top), Size(right - left, bottom - top), CornerRadius(r, r), style = style)
        }
        InsertShapeKind.CIRCLE -> drawOval(paintColor, Offset(left, top), Size(right - left, bottom - top), style = style)
        InsertShapeKind.TRIANGLE -> drawPath(composePolygonPath(InsertShapeGeometry.triangleVertices(left, top, right, bottom)), paintColor, style = style)
        InsertShapeKind.POLYGON -> {
            val cx = (left + right) * 0.5f
            val cy = (top + bottom) * 0.5f
            drawPath(
                composePolygonPath(InsertShapeGeometry.polygonVertices(cx, cy, (right - left) * 0.5f, (bottom - top) * 0.5f, spec.clampedPolygonSides)),
                paintColor,
                style = style,
            )
        }
        InsertShapeKind.STAR -> {
            val cx = (left + right) * 0.5f
            val cy = (top + bottom) * 0.5f
            drawPath(
                composePolygonPath(InsertShapeGeometry.starVertices(cx, cy, (right - left) * 0.5f, (bottom - top) * 0.5f, spec.clampedStarPoints, spec.clampedStarInnerRatio)),
                paintColor,
                style = style,
            )
        }
        InsertShapeKind.ARROW -> drawPath(composePolygonPath(InsertShapeGeometry.arrowVertices(left, top, right, bottom)), paintColor, style = style)
        InsertShapeKind.LINE -> {
            val (s, e) = InsertShapeGeometry.lineEndpoints(left, top, right, bottom)
            drawLine(paintColor, Offset(s.x, s.y), Offset(e.x, e.y), strokeWidth = strokePx, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawShapeGlyph(kind: InsertShapeKind, color: Color) {
    val strokePx = 1.8.dp.toPx()
    val inset = strokePx
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    val style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        InsertShapeKind.RECTANGLE -> drawRect(color, Offset(left, top + 2f), Size(right - left, bottom - top - 4f), style = style)
        InsertShapeKind.ROUNDED_RECT -> drawRoundRect(color, Offset(left, top + 2f), Size(right - left, bottom - top - 4f), CornerRadius(5.dp.toPx(), 5.dp.toPx()), style = style)
        InsertShapeKind.CIRCLE -> drawOval(color, Offset(left, top), Size(right - left, bottom - top), style = style)
        InsertShapeKind.TRIANGLE -> drawPath(composePolygonPath(InsertShapeGeometry.triangleVertices(left, top, right, bottom)), color, style = style)
        InsertShapeKind.POLYGON -> drawPath(composePolygonPath(InsertShapeGeometry.polygonVertices(size.width * 0.5f, size.height * 0.5f, (right - left) * 0.5f, (bottom - top) * 0.5f, 6)), color, style = style)
        InsertShapeKind.STAR -> drawPath(composePolygonPath(InsertShapeGeometry.starVertices(size.width * 0.5f, size.height * 0.5f, (right - left) * 0.5f, (bottom - top) * 0.5f, 5, 0.45f)), color, style = style)
        InsertShapeKind.ARROW -> drawPath(composePolygonPath(InsertShapeGeometry.arrowVertices(left, top + 2f, right, bottom - 2f)), color, style = style)
        InsertShapeKind.LINE -> drawLine(color, Offset(left, bottom - 2f), Offset(right, top + 2f), strokeWidth = strokePx, cap = StrokeCap.Round)
    }
}

private fun composePolygonPath(vertices: List<ShapePoint>): Path = Path().apply {
    if (vertices.isEmpty()) return@apply
    moveTo(vertices[0].x, vertices[0].y)
    for (i in 1 until vertices.size) {
        lineTo(vertices[i].x, vertices[i].y)
    }
    close()
}
