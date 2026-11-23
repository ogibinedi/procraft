package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.chooseQuickShape
import io.github.artcraftandroid.procraft.canvas.endQuickShape
import io.github.artcraftandroid.procraft.core.QuickShapeAnchor
import io.github.artcraftandroid.procraft.core.QuickShapeKind

internal data class QuickShapeBarMetrics(
    val compact: Boolean,
    val headerHeightDp: Float,
    val chipHeightDp: Float,
    val horizontalMarginDp: Float,
    val topGapDp: Float,
    val maxChoiceStripWidthDp: Float,
    val handleOuterRadiusDp: Float,
    val handleInnerRadiusDp: Float,
    val useCompactLabels: Boolean,
)

/** Pure responsive metrics for QuickShape chrome so choices never clip on 320dp-430dp phones. */
internal object QuickShapeLayout {
    fun metrics(screenWidthDp: Float, choiceCount: Int): QuickShapeBarMetrics {
        val compact = screenWidthDp < 600f
        val margin = if (compact) 12f else 24f
        return QuickShapeBarMetrics(
            compact = compact,
            headerHeightDp = if (compact) 44f else 36f,
            chipHeightDp = if (compact) 40f else 32f,
            horizontalMarginDp = margin,
            topGapDp = if (compact) 10f else 16f,
            maxChoiceStripWidthDp = (screenWidthDp - margin * 2f).coerceAtLeast(160f),
            handleOuterRadiusDp = if (compact) 8.5f else 6.5f,
            handleInnerRadiusDp = if (compact) 6f else 5f,
            useCompactLabels = compact && screenWidthDp < 420f && choiceCount >= 3,
        )
    }

    fun label(kind: QuickShapeKind, compact: Boolean = false): String = if (compact) {
        when (kind) {
            QuickShapeKind.LINE -> "Line"
            QuickShapeKind.POLYLINE -> "Poly"
            QuickShapeKind.ARC -> "Arc"
            QuickShapeKind.CURVE -> "Curve"
            QuickShapeKind.ELLIPSE -> "Ellipse"
            QuickShapeKind.CIRCLE -> "Circle"
            QuickShapeKind.TRIANGLE -> "Triangle"
            QuickShapeKind.EQUILATERAL_TRIANGLE -> "Equilat."
            QuickShapeKind.QUADRILATERAL -> "Quad"
            QuickShapeKind.RECTANGLE -> "Rect"
            QuickShapeKind.SQUARE -> "Square"
        }
    } else {
        kind.label()
    }
}

private val QuickBarBg get() = if (Pc.light) Color(0xEBEDEDED) else Color(0xE6262626)
private val QuickBarTitle get() = if (Pc.light) Color(0xFF222222) else Color(0xFFF0F0F0)
private val QuickBarSub get() = if (Pc.light) Color(0xFF4A4A4A) else Color(0xFFB8B8B8)
private val QuickPillFill get() = if (Pc.light) Color(0xF5FFFFFF) else Color(0xEB262626)
private val QuickPillBorder get() = if (Pc.light) Color(0xFFD0D0D0) else Color(0xFF454545)
private val QuickChipIdleInk get() = if (Pc.light) Color(0xFF262626) else Color(0xFFE0E0E0)
private val QuickActionFill get() = Pc.tinted(Color(0xFF0067DF))
private val QuickHandleHalo = Color(0x73000000)

internal fun QuickShapeKind.label() = when (this) {
    QuickShapeKind.LINE -> "Line"
    QuickShapeKind.POLYLINE -> "Polyline"
    QuickShapeKind.ARC -> "Arc"
    QuickShapeKind.CURVE -> "Curve"
    QuickShapeKind.ELLIPSE -> "Ellipse"
    QuickShapeKind.CIRCLE -> "Circle"
    QuickShapeKind.TRIANGLE -> "Triangle"
    QuickShapeKind.EQUILATERAL_TRIANGLE -> "Equilateral"
    QuickShapeKind.QUADRILATERAL -> "Quadrilateral"
    QuickShapeKind.RECTANGLE -> "Rectangle"
    QuickShapeKind.SQUARE -> "Square"
}

internal fun QuickShapeKind.icon() = when (this) {
    QuickShapeKind.LINE -> PcIcons.ShapeLine
    QuickShapeKind.POLYLINE -> PcIcons.ShapePolyline
    QuickShapeKind.ARC -> PcIcons.ShapeArc
    QuickShapeKind.CURVE -> PcIcons.ShapeArc
    QuickShapeKind.ELLIPSE -> PcIcons.ShapeEllipse
    QuickShapeKind.CIRCLE -> PcIcons.ShapeCircle
    QuickShapeKind.TRIANGLE -> PcIcons.ShapeTriangle
    QuickShapeKind.EQUILATERAL_TRIANGLE -> PcIcons.ShapeEquilateral
    QuickShapeKind.QUADRILATERAL -> PcIcons.ShapeQuad
    QuickShapeKind.RECTANGLE -> PcIcons.ShapeRect
    QuickShapeKind.SQUARE -> PcIcons.ShapeSquare
}

/**
 * The bar a snapped stroke shows under the top bar: tapping enters handle editing, the choice
 * strip lets users switch recognized geometry without clipping on phones, and Done commits.
 */
@Composable
fun QuickShapeBar(vm: CanvasViewModel, screenWidth: Dp, modifier: Modifier = Modifier) {
    val info = vm.quickShape ?: return
    val m = QuickShapeLayout.metrics(screenWidth.value, info.choices.size)
    val editing = vm.quickShapeEditing
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = m.headerHeightDp.dp)
                .background(QuickBarBg)
                .clickable(enabled = !editing, role = Role.Button) { vm.quickShapeEditing = true }
                .padding(horizontal = m.horizontalMarginDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(end = 68.dp),
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Icon(info.kind.icon(), contentDescription = null, tint = QuickBarTitle, modifier = Modifier.requiredSize(24.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    if (editing) "Editing" else info.kind.label(),
                    color = QuickBarTitle,
                    fontSize = if (m.compact) 15.5.sp else 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (editing) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        QuickShapeLayout.label(info.kind, compact = m.useCompactLabels),
                        color = QuickBarSub,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .height(if (m.compact) 34.dp else 28.dp)
                    .clip(CircleShape)
                    .background(QuickActionFill)
                    .clickable(role = Role.Button) {
                        if (editing) vm.endQuickShape() else vm.quickShapeEditing = true
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (editing) "Done" else "Edit",
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        if (editing && info.choices.size > 1) {
            Spacer(Modifier.height(m.topGapDp.dp))
            Row(
                Modifier
                    .widthIn(max = m.maxChoiceStripWidthDp.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(QuickPillFill)
                    .border(1.dp, QuickPillBorder, RoundedCornerShape(22.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (kind in info.choices) {
                    val chosen = kind == info.kind
                    val ink = if (chosen) Color.White else QuickChipIdleInk
                    Row(
                        Modifier
                            .height(m.chipHeightDp.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(if (chosen) QuickActionFill else Color.Transparent)
                            .clickable(role = Role.Button) { vm.chooseQuickShape(kind) }
                            .padding(horizontal = if (m.compact) 11.dp else 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(kind.icon(), contentDescription = null, tint = ink, modifier = Modifier.requiredSize(22.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            QuickShapeLayout.label(kind, compact = m.useCompactLabels),
                            color = ink,
                            fontSize = if (m.compact) 13.sp else 14.sp,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The snapped shape's handles while editing: high-contrast blue dots ringed in white with a dark halo. */
@Composable
fun QuickShapeHandles(vm: CanvasViewModel, compact: Boolean = false) {
    val info = vm.quickShape ?: return
    if (!vm.quickShapeEditing) return
    val metrics = QuickShapeLayout.metrics(if (compact) 360f else 800f, info.choices.size)
    Canvas(Modifier.fillMaxSize()) {
        val h = info.handles
        val points = List(h.size / 2) { i -> Offset(h[i * 2], h[i * 2 + 1]) }
        val curve = info.kind == QuickShapeKind.CURVE
        val control = Color(0xFF60E078)
        fun owner(i: Int): Int? = when (i % 3) { 1 -> i - 1; 2 -> i + 1; else -> null }
        fun folded(i: Int) = curve && owner(i)?.let { info.anchors.getOrNull(it / 3) == QuickShapeAnchor.CORNER } == true
        if (curve) {
            points.forEachIndexed { i, at ->
                val anchor = owner(i)?.takeIf { !folded(i) }?.let { points.getOrNull(it) } ?: return@forEachIndexed
                drawLine(QuickHandleHalo, anchor, at, 3.dp.toPx())
                drawLine(control, anchor, at, 1.5.dp.toPx())
            }
        }
        val outerR = metrics.handleOuterRadiusDp.dp.toPx()
        val innerR = metrics.handleInnerRadiusDp.dp.toPx()
        val haloPx = 1.5.dp.toPx()
        points.forEachIndexed { i, at ->
            if (folded(i)) return@forEachIndexed
            val ink = if (curve && i % 3 != 0) control else Pc.Accent
            when (if (curve && i % 3 == 0) info.anchors.getOrNull(i / 3) else null) {
                QuickShapeAnchor.BROKEN -> {
                    val r = outerR + 1.dp.toPx()
                    val tri = Path().apply {
                        moveTo(at.x, at.y - r)
                        lineTo(at.x - r * 0.87f, at.y + r * 0.5f)
                        lineTo(at.x + r * 0.87f, at.y + r * 0.5f)
                        close()
                    }
                    drawPath(tri, QuickHandleHalo, style = Stroke(3.5.dp.toPx()))
                    drawPath(tri, ink)
                    drawPath(tri, Color.White, style = Stroke(1.5.dp.toPx()))
                }
                QuickShapeAnchor.CORNER -> {
                    val outer = outerR * 2f
                    val inner = innerR * 2f
                    drawRect(QuickHandleHalo, Offset(at.x - (outer / 2f + haloPx), at.y - (outer / 2f + haloPx)), Size(outer + haloPx * 2f, outer + haloPx * 2f))
                    drawRect(Color.White, Offset(at.x - outer / 2f, at.y - outer / 2f), Size(outer, outer))
                    drawRect(ink, Offset(at.x - inner / 2f, at.y - inner / 2f), Size(inner, inner))
                }
                else -> {
                    drawCircle(QuickHandleHalo, outerR + haloPx, at)
                    drawCircle(Color.White, outerR, at)
                    drawCircle(ink, innerR, at)
                }
            }
        }
    }
}
