package io.github.artcraftandroid.procraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.InsertShapeAspect
import io.github.artcraftandroid.procraft.canvas.InsertShapeGeometry
import io.github.artcraftandroid.procraft.canvas.InsertShapeKind
import io.github.artcraftandroid.procraft.canvas.InsertShapeSpec
import io.github.artcraftandroid.procraft.canvas.ShapePlacement
import io.github.artcraftandroid.procraft.canvas.ShapePoint
import io.github.artcraftandroid.procraft.canvas.canvasSize
import io.github.artcraftandroid.procraft.canvas.colorRgb
import io.github.artcraftandroid.procraft.canvas.insertShape
import io.github.artcraftandroid.procraft.canvas.updateShapeLayer
import kotlin.math.min
import kotlin.math.roundToInt

private enum class ActiveColorTarget { FILL, STROKE }

private val ShapeCardRadius = 16.dp
private val ShapeCardFill get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF262626)
private val ShapeCardBorder get() = if (Pc.light) Color(0xFFD4D4D4) else Color(0xFF393939)
private val ShapeDivider get() = if (Pc.light) Color(0xFFE5E5E5) else Color(0xFF353535)
private val ShapeSubtleFill get() = if (Pc.light) Color(0xFFEBEBEB) else Color(0xFF303030)
private val ShapePrimaryInk get() = if (Pc.light) Color(0xFF222222) else Color(0xFFF0F0F0)
private val ShapeMutedInk get() = if (Pc.light) Color(0xFF595959) else Color(0xFFAAAAAA)
private val ShapeAccentFill get() = Pc.tinted(Color(0xFF0067DF))
private val ShapeSelectedTint get() = if (Pc.light) Color(0xFFE3F0FF) else Color(0xFF1B334D)
private val ShapeSelectedInk get() = if (Pc.light) Color(0xFF0055B8) else Color(0xFF78B8FF)
private val CheckerLight get() = if (Pc.light) Color(0xFFF2F2F2) else Color(0xFF2E2E2E)
private val CheckerDark get() = if (Pc.light) Color(0xFFE0E0E0) else Color(0xFF242424)

private val QuickPresetSwatches = listOf(
    0x141414, 0x595959, 0xFFFFFF,
    0xE84F51, 0xF59E0B, 0xFACC15,
    0x22C55E, 0x06B6D4, 0x2563EB, 0x9333EA,
)

private val HueGradient = Brush.horizontalGradient(
    listOf(
        Color(0xFFFF0000),
        Color(0xFFFFFF00),
        Color(0xFF00FF00),
        Color(0xFF00FFFF),
        Color(0xFF0000FF),
        Color(0xFFFF00FF),
        Color(0xFFFF0000),
    ),
)

/**
 * Responsive full-screen Insert Shape composer with independent Fill & Stroke toggles/colors,
 * decimal 0.1-500 px stroke width control (log slider, fine steppers, and direct numeric entry),
 * 8 geometric shapes, and live preview.
 */
@Composable
internal fun InsertShapeSheet(vm: CanvasViewModel, onClose: () -> Unit) {
    BackHandler(enabled = !vm.insertShapeBusy, onBack = onClose)
    val editingLayerId = vm.editingShapeLayerId
    val editingRecord = vm.editingShapeRecord
    val isEditing = editingLayerId != null && editingRecord != null

    var spec by remember(editingLayerId) { mutableStateOf(editingRecord?.spec ?: vm.lastInsertShape) }
    var placement by remember(editingLayerId) { mutableStateOf(editingRecord?.placement) }
    var colorTarget by remember(editingLayerId) { mutableStateOf<ActiveColorTarget?>(null) }
    var editingStrokeNumber by remember(editingLayerId) { mutableStateOf(false) }
    var strokeInputText by remember(editingLayerId) { mutableStateOf(InsertShapeGeometry.formatStrokePx(spec.clampedStrokeWidthPx)) }

    val activeRgb = vm.colorRgb()
    val resolvedFillRgb = spec.resolvedFillRgb(activeRgb)
    val resolvedStrokeRgb = spec.resolvedStrokeRgb(activeRgb)
    val fillColor = remember(resolvedFillRgb) { CanvasSpace.color(resolvedFillRgb) }
    val strokeColor = remember(resolvedStrokeRgb) { CanvasSpace.color(resolvedStrokeRgb) }

    val (cw, ch) = remember(vm.session) { vm.canvasSize() }
    val effectiveAspect = if (spec.supportsAspect) spec.aspect else InsertShapeAspect.WIDE_16_9
    val (targetW, targetH) = remember(cw, ch, effectiveAspect, placement) {
        val p = placement
        if (p != null) {
            p.width.roundToInt().coerceAtLeast(32) to p.height.roundToInt().coerceAtLeast(32)
        } else {
            InsertShapeGeometry.targetBitmapSize(cw, ch, effectiveAspect)
        }
    }
    val previewRotation = placement?.normalizedRotation() ?: 0f

    if (colorTarget == ActiveColorTarget.FILL && !spec.effectiveFillEnabled) {
        colorTarget = null
    } else if (colorTarget == ActiveColorTarget.STROKE && !spec.effectiveStrokeEnabled) {
        colorTarget = null
    }

    val keyboardDp = with(LocalDensity.current) { rememberKeyboardReach().toDp() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Pc.GalleryBackground)
            .padding(bottom = keyboardDp)
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
                if (isEditing) "Edit Shape" else "Insert Shape",
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
                // Live preview card
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .clip(RoundedCornerShape(ShapeCardRadius))
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(ShapeCardRadius)),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCheckerboard(14.dp.toPx())
                        drawShapePreview(spec, fillColor, strokeColor, targetW, targetH, previewRotation)
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
                            append(spec.styleSummary)
                            if (spec.effectiveStrokeEnabled) {
                                append(" · ")
                                append(InsertShapeGeometry.formatStrokePx(spec.clampedStrokeWidthPx))
                                append(" px")
                            }
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
                    for (rowKinds in InsertShapeKind.entries.chunked(4)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    Canvas(Modifier.size(22.dp)) { drawShapeGlyph(kind, glyphColor) }
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

                // Independent Fill & Stroke card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ShapeCardRadius))
                        .background(ShapeCardFill)
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(ShapeCardRadius))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Fill & Stroke", color = ShapeMutedInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (spec.effectiveFillEnabled && spec.effectiveStrokeEnabled) {
                            Box(
                                Modifier
                                    .heightIn(min = 36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ShapeSubtleFill)
                                    .border(1.dp, ShapeCardBorder, RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) { spec = spec.withSwappedColors(activeRgb) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Swap colors", color = ShapePrimaryInk, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }

                    if (spec.supportsFill) {
                        AppearanceRow(
                            label = "Fill",
                            detail = if (spec.effectiveFillEnabled) InsertShapeGeometry.formatHexRgb(resolvedFillRgb) else "Off",
                            enabled = spec.effectiveFillEnabled,
                            showToggle = true,
                            swatchColor = fillColor,
                            pickerOpen = colorTarget == ActiveColorTarget.FILL,
                            onToggle = {
                                val next = spec.withFillToggled(!spec.effectiveFillEnabled)
                                spec = next
                                if (!next.effectiveFillEnabled && colorTarget == ActiveColorTarget.FILL) colorTarget = null
                            },
                            onSwatchClick = {
                                if (!spec.effectiveFillEnabled) spec = spec.withFillToggled(true)
                                colorTarget = if (colorTarget == ActiveColorTarget.FILL) null else ActiveColorTarget.FILL
                            },
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(ShapeDivider))
                    }

                    AppearanceRow(
                        label = "Stroke",
                        detail = if (spec.effectiveStrokeEnabled) {
                            "${InsertShapeGeometry.formatStrokePx(spec.clampedStrokeWidthPx)} px · ${InsertShapeGeometry.formatHexRgb(resolvedStrokeRgb)}"
                        } else {
                            "Off"
                        },
                        enabled = spec.effectiveStrokeEnabled,
                        showToggle = spec.supportsFill,
                        swatchColor = strokeColor,
                        pickerOpen = colorTarget == ActiveColorTarget.STROKE,
                        onToggle = {
                            val next = spec.withStrokeToggled(!spec.effectiveStrokeEnabled)
                            spec = next
                            if (!next.effectiveStrokeEnabled && colorTarget == ActiveColorTarget.STROKE) colorTarget = null
                        },
                        onSwatchClick = {
                            if (!spec.effectiveStrokeEnabled) spec = spec.withStrokeToggled(true)
                            colorTarget = if (colorTarget == ActiveColorTarget.STROKE) null else ActiveColorTarget.STROKE
                        },
                    )

                    AnimatedVisibility(
                        visible = colorTarget != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        val target = colorTarget
                        if (target != null) {
                            val currentRgb = if (target == ActiveColorTarget.FILL) resolvedFillRgb else resolvedStrokeRgb
                            InlineColorPicker(
                                title = if (target == ActiveColorTarget.FILL) "Fill Color" else "Stroke Color",
                                currentRgb = currentRgb,
                                activeBrushRgb = activeRgb,
                                recentHistory = vm.history,
                                onSelectRgb = { picked ->
                                    spec = if (target == ActiveColorTarget.FILL) {
                                        spec.copy(fillRgb = picked and 0xFFFFFF)
                                    } else {
                                        spec.copy(strokeRgb = picked and 0xFFFFFF)
                                    }
                                },
                                onResetToBrush = {
                                    spec = if (target == ActiveColorTarget.FILL) {
                                        spec.copy(fillRgb = null)
                                    } else {
                                        spec.copy(strokeRgb = activeRgb)
                                    }
                                },
                            )
                        }
                    }

                    // Decimal Stroke Size controls (0.1 px to 500 px)
                    if (spec.effectiveStrokeEnabled) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(ShapeDivider))
                        StrokeSizeControl(
                            strokePx = spec.clampedStrokeWidthPx,
                            editingNumber = editingStrokeNumber,
                            inputText = strokeInputText,
                            onInputTextChange = { strokeInputText = it },
                            onToggleEditNumber = {
                                if (!editingStrokeNumber) {
                                    strokeInputText = InsertShapeGeometry.formatStrokePx(spec.clampedStrokeWidthPx)
                                }
                                editingStrokeNumber = !editingStrokeNumber
                            },
                            onCommitInput = {
                                InsertShapeGeometry.parseStrokeInput(strokeInputText)?.let { parsed ->
                                    spec = spec.copy(strokeWidthPx = parsed)
                                    strokeInputText = InsertShapeGeometry.formatStrokePx(parsed)
                                }
                                editingStrokeNumber = false
                            },
                            onChangePx = { nextPx ->
                                spec = spec.copy(strokeWidthPx = nextPx)
                                if (editingStrokeNumber) {
                                    strokeInputText = InsertShapeGeometry.formatStrokePx(nextPx)
                                }
                            },
                        )
                    }
                }

                // Proportion & Shape Geometry card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ShapeCardRadius))
                        .background(ShapeCardFill)
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(ShapeCardRadius))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                                            .clickable(role = Role.Button) {
                                                spec = spec.copy(aspect = aspect)
                                                placement = placement?.withAspect(aspect)
                                            },
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

                    if (isEditing && placement != null) {
                        val currentRot = placement!!.normalizedRotation()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Rotation", color = ShapePrimaryInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text("${currentRot.roundToInt()}°", color = ShapeMutedInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                if (kotlin.math.abs(currentRot) >= 0.5f) {
                                    Box(
                                        Modifier
                                            .padding(start = 8.dp)
                                            .heightIn(min = 32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(ShapeSubtleFill)
                                            .border(1.dp, ShapeCardBorder, RoundedCornerShape(8.dp))
                                            .clickable(role = Role.Button) { placement = placement?.copy(rotationDegrees = 0f) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Reset", color = ShapePrimaryInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            ShapeControlSlider(
                                label = null,
                                valueText = null,
                                fraction = ((currentRot + 180f) / 360f).coerceIn(0f, 1f),
                                onFractionChange = { f ->
                                    val deg = ((f * 360f - 180f)).roundToInt().toFloat()
                                    placement = placement?.copy(rotationDegrees = deg)
                                },
                            )
                        }
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

        // Pinned action bar outside scroll container
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
                fun commit(startTransform: Boolean) {
                    val editId = editingLayerId
                    val editPlace = placement
                    if (editId != null && editPlace != null) {
                        vm.updateShapeLayer(editId, spec, editPlace, startTransform)
                    } else {
                        vm.insertShape(spec, startTransform)
                    }
                }
                Box(
                    Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ShapeSubtleFill)
                        .border(1.dp, ShapeCardBorder, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy, role = Role.Button) { commit(startTransform = false) }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isEditing) "Apply" else "Insert", color = ShapePrimaryInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (busy) ShapeAccentFill.copy(alpha = 0.5f) else ShapeAccentFill)
                        .clickable(enabled = !busy, role = Role.Button) { commit(startTransform = true) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            busy && isEditing -> "Updating…"
                            busy -> "Inserting…"
                            isEditing -> "Apply & Transform"
                            else -> "Insert & Transform"
                        },
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
private fun AppearanceRow(
    label: String,
    detail: String,
    enabled: Boolean,
    showToggle: Boolean,
    swatchColor: Color,
    pickerOpen: Boolean,
    onToggle: () -> Unit,
    onSwatchClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showToggle) {
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Switch, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Switch(enabled, enabled = true)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onSwatchClick),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = ShapePrimaryInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = ShapeMutedInk, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (pickerOpen) 2.dp else 1.dp,
                    color = if (pickerOpen) ShapeSelectedInk else ShapeCardBorder,
                    shape = RoundedCornerShape(10.dp),
                )
                .clickable(role = Role.Button, onClick = onSwatchClick)
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (enabled) swatchColor else ShapeSubtleFill)
                    .border(1.dp, ShapeCardBorder, RoundedCornerShape(7.dp)),
            )
        }
    }
}

@Composable
private fun StrokeSizeControl(
    strokePx: Float,
    editingNumber: Boolean,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onToggleEditNumber: () -> Unit,
    onCommitInput: () -> Unit,
    onChangePx: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Stroke size", color = ShapePrimaryInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (editingNumber) ShapeSelectedTint else ShapeSubtleFill)
                    .border(1.dp, if (editingNumber) ShapeSelectedInk else ShapeCardBorder, RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onToggleEditNumber)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${InsertShapeGeometry.formatStrokePx(strokePx)} px",
                    color = if (editingNumber) ShapeSelectedInk else ShapePrimaryInk,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ShapeSubtleFill)
                    .border(1.dp, ShapeCardBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = strokePx > InsertShapeSpec.MIN_STROKE_PX, role = Role.Button) {
                        onChangePx(InsertShapeGeometry.stepStrokePx(strokePx, increase = false))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("−", color = ShapePrimaryInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ShapeSubtleFill)
                    .border(1.dp, ShapeCardBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = strokePx < InsertShapeSpec.MAX_STROKE_PX, role = Role.Button) {
                        onChangePx(InsertShapeGeometry.stepStrokePx(strokePx, increase = true))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = ShapePrimaryInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        AnimatedVisibility(visible = editingNumber, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ShapeSubtleFill)
                        .border(1.dp, ShapeSelectedInk, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = { raw ->
                            if (raw.length <= 7) onInputTextChange(raw)
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = ShapePrimaryInk, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(ShapeSelectedInk),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommitInput() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ShapeAccentFill)
                        .clickable(role = Role.Button, onClick = onCommitInput)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Set", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        ShapeControlSlider(
            label = null,
            valueText = null,
            fraction = InsertShapeGeometry.strokePxToSlider(strokePx),
            onFractionChange = { f -> onChangePx(InsertShapeGeometry.sliderToStrokePx(f)) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0.1 px", color = ShapeMutedInk, fontSize = 11.5.sp)
            Text("1 px", color = ShapeMutedInk, fontSize = 11.5.sp)
            Text("10 px", color = ShapeMutedInk, fontSize = 11.5.sp)
            Text("100 px", color = ShapeMutedInk, fontSize = 11.5.sp)
            Text("500 px", color = ShapeMutedInk, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun InlineColorPicker(
    title: String,
    currentRgb: Int,
    activeBrushRgb: Int,
    recentHistory: List<Int>,
    onSelectRgb: (Int) -> Unit,
    onResetToBrush: () -> Unit,
) {
    val hsv = remember(currentRgb) { rgbToHsv(currentRgb) }
    var hexText by remember(currentRgb) { mutableStateOf(InsertShapeGeometry.formatHexRgb(currentRgb)) }
    val swatches = remember(activeBrushRgb, recentHistory) {
        buildList {
            add(activeBrushRgb and 0xFFFFFF)
            for (c in recentHistory.take(5)) {
                val rgb = c and 0xFFFFFF
                if (rgb !in this) add(rgb)
            }
            for (c in QuickPresetSwatches) {
                if (c !in this) add(c)
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ShapeSubtleFill)
            .border(1.dp, ShapeCardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = ShapePrimaryInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ShapeCardFill)
                    .border(1.dp, ShapeCardBorder, RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onResetToBrush)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Use Brush Color", color = ShapePrimaryInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (rgb in swatches) {
                val chosen = (currentRgb and 0xFFFFFF) == rgb
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(if (chosen) 2.5.dp else 1.dp, if (chosen) ShapeSelectedInk else ShapeCardBorder, CircleShape)
                        .clickable(role = Role.Button) { onSelectRgb(rgb) }
                        .padding(3.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(CanvasSpace.color(rgb))
                            .border(1.dp, ShapeCardBorder, CircleShape),
                    )
                }
            }
        }

        ShapeControlSlider(
            label = "Hue",
            valueText = "${(hsv[0] * 360f).roundToInt()}°",
            fraction = hsv[0],
            trackBrush = HueGradient,
            onFractionChange = { h -> onSelectRgb(hsvToRgb(h, hsv[1].coerceAtLeast(0.15f), hsv[2].coerceAtLeast(0.15f))) },
        )
        ShapeControlSlider(
            label = "Saturation",
            valueText = "${(hsv[1] * 100f).roundToInt()}%",
            fraction = hsv[1],
            onFractionChange = { s -> onSelectRgb(hsvToRgb(hsv[0], s, hsv[2])) },
        )
        ShapeControlSlider(
            label = "Brightness",
            valueText = "${(hsv[2] * 100f).roundToInt()}%",
            fraction = hsv[2],
            onFractionChange = { v -> onSelectRgb(hsvToRgb(hsv[0], hsv[1], v)) },
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Hex", color = ShapeMutedInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ShapeCardFill)
                    .border(1.dp, ShapeCardBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        if (raw.length <= 7) {
                            hexText = raw.uppercase()
                            InsertShapeGeometry.parseHexRgb(raw)?.let(onSelectRgb)
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = ShapePrimaryInk, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(ShapeSelectedInk),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { InsertShapeGeometry.parseHexRgb(hexText)?.let(onSelectRgb) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ShapeControlSlider(
    label: String?,
    valueText: String?,
    fraction: Float,
    trackBrush: Brush? = null,
    onFractionChange: (Float) -> Unit,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val latestOnChange by rememberUpdatedState(onFractionChange)
    var trackWidth by remember { mutableFloatStateOf(1f) }
    var dragFraction by remember { mutableFloatStateOf(clamped) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null || valueText != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    Text(label, color = ShapePrimaryInk, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (valueText != null) {
                    Text(valueText, color = ShapeMutedInk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
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
                if (trackBrush != null) {
                    drawRoundRect(
                        brush = trackBrush,
                        topLeft = Offset(thumbR, cy - trackH * 0.5f),
                        size = Size(usableW, trackH),
                        cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f),
                    )
                } else {
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
                }
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

private fun DrawScope.drawShapePreview(
    spec: InsertShapeSpec,
    fillColor: Color,
    strokeColor: Color,
    targetW: Int,
    targetH: Int,
    rotationDegrees: Float = 0f,
) {
    val padX = 28.dp.toPx()
    val padTop = 18.dp.toPx()
    val padBottom = 38.dp.toPx()
    val availW = (size.width - padX * 2f).coerceAtLeast(24f)
    val availH = (size.height - padTop - padBottom).coerceAtLeast(24f)
    val effectiveAspect = if (spec.supportsAspect) spec.aspect else InsertShapeAspect.WIDE_16_9
    val ratio = if (targetW > 0 && targetH > 0) targetW.toFloat() / targetH.toFloat() else effectiveAspect.widthRatio / effectiveAspect.heightRatio
    val boxW: Float
    val boxH: Float
    if (availW / availH >= ratio) {
        boxH = availH
        boxW = boxH * ratio
    } else {
        boxW = availW
        boxH = boxW / ratio
    }
    val rawStroke = spec.clampedStrokeWidthPx
    val scale = min(boxW, boxH) / min(targetW, targetH).coerceAtLeast(1).toFloat()
    val scaledStroke = (rawStroke * scale).coerceAtMost(min(boxW, boxH) * 0.44f)
    // Keep sub-pixel 0.1..1 px hairlines visible in the preview by drawing a thin tinted line
    val previewStrokePx = if (rawStroke < 1f) maxOf(scaledStroke, 0.8f) else maxOf(scaledStroke, 1f)
    val hairlineAlpha = if (rawStroke < 1f) (0.35f + 0.65f * rawStroke) else 1f
    val inset = if (spec.effectiveStrokeEnabled) (previewStrokePx * 0.5f).coerceAtMost(min(boxW, boxH) * 0.44f) else 0f
    val left = (size.width - boxW) * 0.5f + inset
    val top = padTop + (availH - boxH) * 0.5f + inset
    val right = (size.width + boxW) * 0.5f - inset
    val bottom = padTop + (availH + boxH) * 0.5f - inset
    if (right <= left || bottom <= top) return
    val pivot = Offset((left + right) * 0.5f, (top + bottom) * 0.5f)

    rotate(degrees = rotationDegrees, pivot = pivot) {
        if (spec.effectiveFillEnabled) {
            drawComposeShape(
                spec = spec,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                color = fillColor.copy(alpha = spec.clampedFillOpacity * spec.clampedOpacity),
                style = Fill,
                strokePx = 0f,
            )
        }
        if (spec.effectiveStrokeEnabled) {
            drawComposeShape(
                spec = spec,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                color = strokeColor.copy(alpha = spec.clampedStrokeOpacity * spec.clampedOpacity * hairlineAlpha),
                style = Stroke(width = previewStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                strokePx = previewStrokePx,
            )
        }
    }
}

private fun DrawScope.drawComposeShape(
    spec: InsertShapeSpec,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    style: androidx.compose.ui.graphics.drawscope.DrawStyle,
    strokePx: Float,
) {
    when (spec.kind) {
        InsertShapeKind.RECTANGLE -> drawRect(color, Offset(left, top), Size(right - left, bottom - top), style = style)
        InsertShapeKind.ROUNDED_RECT -> {
            val r = min(right - left, bottom - top) * spec.clampedCornerRadiusFraction
            drawRoundRect(color, Offset(left, top), Size(right - left, bottom - top), CornerRadius(r, r), style = style)
        }
        InsertShapeKind.CIRCLE -> drawOval(color, Offset(left, top), Size(right - left, bottom - top), style = style)
        InsertShapeKind.TRIANGLE -> drawPath(composePolygonPath(InsertShapeGeometry.triangleVertices(left, top, right, bottom)), color, style = style)
        InsertShapeKind.POLYGON -> {
            val cx = (left + right) * 0.5f
            val cy = (top + bottom) * 0.5f
            drawPath(
                composePolygonPath(InsertShapeGeometry.polygonVertices(cx, cy, (right - left) * 0.5f, (bottom - top) * 0.5f, spec.clampedPolygonSides)),
                color,
                style = style,
            )
        }
        InsertShapeKind.STAR -> {
            val cx = (left + right) * 0.5f
            val cy = (top + bottom) * 0.5f
            drawPath(
                composePolygonPath(InsertShapeGeometry.starVertices(cx, cy, (right - left) * 0.5f, (bottom - top) * 0.5f, spec.clampedStarPoints, spec.clampedStarInnerRatio)),
                color,
                style = style,
            )
        }
        InsertShapeKind.ARROW -> drawPath(composePolygonPath(InsertShapeGeometry.arrowVertices(left, top, right, bottom)), color, style = style)
        InsertShapeKind.LINE -> {
            val (s, e) = InsertShapeGeometry.lineEndpoints(left, top, right, bottom)
            drawLine(color, Offset(s.x, s.y), Offset(e.x, e.y), strokeWidth = strokePx, cap = StrokeCap.Round)
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
