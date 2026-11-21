package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.canvas.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.PadSurfaceView
import io.github.artcraftandroid.procraft.core.SourceKind
import io.github.artcraftandroid.procraft.core.sliderExtras
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity

private val StudioTabs get() = if (Pc.light) Color(0xFFEBEBEB) else Color(0xFF272727)
private val StudioBack get() = if (Pc.light) Color(0xFFF3F3F3) else Color(0xFF1F1F1F)
private val StudioLine get() = if (Pc.light) Color(0xFFCFCFCF) else Color(0xFF303030)
private val StudioHeader get() = if (Pc.light) Color(0xFF818181) else Color(0xFF9C9C9C)
internal val StudioLabel get() = if (Pc.light) Color(0xFF343434) else Color(0xFFEDEDED)
private val StudioDim get() = if (Pc.light) Color(0xFF9A9A9A) else Color(0xFF8E8E8E)
// a control that can't be used now
private val StudioOff get() = if (Pc.light) Color(0xFFD5D5D5) else Color(0xFF505050)
/** Landscape: the pages' card's edge and title, the pages' names, and the settings column's width. */
private val StudioCardEdge get() = if (Pc.light) Color(0xFFDADADA) else Color(0xFF373737)
private val StudioCardTitle get() = if (Pc.light) Color(0xFF4A4A4A) else Color(0xFFC1C1C1)
private val StudioPageInk get() = if (Pc.light) Color(0xFF3E3E3E) else Color(0xFF797979)
private val SettingsWidth = 323.dp
/** A switch that can't be changed here: the usual one, faded. */
private val StudioSwitchFixed = Color(0xFF515153)
private val StudioSwitchFixedKnob = Color(0xFF9B9B9B)
/** A slider that can't be changed here: its value and knob faded into the panel. */
internal val StudioPillOff get() = if (Pc.light) Color(0xFFEDEDED) else Color(0xFF222222)
internal val StudioValueOff get() = if (Pc.light) Color(0xFFA0A0A0) else Color(0xFF646464)
internal val StudioKnobOff get() = if (Pc.light) Color(0xFFD0D0D0) else Color(0xFF353535)
private val StudioList get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF242424)
private val StudioListLine get() = if (Pc.light) Color(0xFFE5E5E5) else Color(0xFF202020)
private val StudioSegments get() = if (Pc.light) Color(0xFFDCDCDC) else Color(0xFF3C3C3C)
private val StudioMaterial get() = if (Pc.light) Color(0xFFECECEC) else Color(0xFF232323)
private val StudioMaterialInk get() = if (Pc.light) Color(0xFF4A4A4A) else Color(0xFFB1B1B1)
internal val StudioPill get() = if (Pc.light) Color(0xFFE5E5E5) else Color(0xFF292929)
internal val StudioTrack get() = if (Pc.light) Color(0xFFE5E5E5) else Color(0xFF333333)
private val StudioTabIcon get() = if (Pc.light) Color(0xFF3D3D3D) else Color(0xFFAAAAAA)
private val StudioButton get() = if (Pc.light) Color(0xFFFBFBFB) else Color(0xFF1C1C1C)
private val StudioButtonEdge get() = if (Pc.light) Color(0xFFE0E0E0) else Color(0xFF383838)
// the pad's buttons' text and icon: grey, not the labels' white
private val StudioButtonInk get() = if (Pc.light) Color(0xFF585858) else Color(0xFFAAAAAA)
private val StudioCard get() = if (Pc.light) Color(0xFFE5E5E5) else Color(0xFF313131)
private val PreviewCardFill get() = if (Pc.light) Color(0xFFE8E8E8) else Color(0xFF2A2A2A)
private val PreviewCardEdge get() = if (Pc.light) Color(0xFFD0D0D0) else Color(0xFF4A4A4A)
private val PreviewCardName get() = if (Pc.light) Color(0xFF3A3A3A) else Color(0xFFC5C5C5)
private val StudioDone get() = Pc.tinted(Color(0xFF0190FF))

/** Where the settings start below the drawing pad (points from the top). */
private val PadHeight = 510.dp
private val TabsWidth = 81.dp

/** The landscape studio's pages card (with its margin), its settings and the line after them. */
private val PagesAndSettings = 330.dp + SettingsWidth + 1.dp

/** The least the pad beside them needs: its Drawing Pad button and Cancel and Done, apart. */
internal val PadLeast = 360.dp

/**
 * Whether the studio in a window [width] wide lays its pages, settings and pad side by side: in
 * landscape where the pad still has room for its buttons (on a phone held sideways it had about 80
 * across, and Done was squeezed to nothing), else the pages as a rail beside the pad over the
 * settings, as upright.
 */
internal fun studioSideBySide(landscape: Boolean, width: Dp) = landscape && width >= PagesAndSettings + PadLeast

/**
 * How a slider reads; [SIZE] reads the size the control sets (the brush's most size × place^2.2) as a
 * percent (Max at the control's end), [BOTH] goes both ways from 0% in its middle, -100% to Max (and
 * [FOLLOW] and [GRADE] to a name of their own), [DOUBLE] reads 100% at its middle; [METAL] and
 * [GLOSS] name their ends as a material's metalness and roughness.
 */
enum class Shown { PERCENT, BOTH, COUNT, TIP, MOVEMENT, ZOOM, CHARGE, SIZE, FOLLOW, GRADE, DOUBLE, TWICE, METAL, GLOSS }

/** One control in the brush studio. */
sealed class StudioControl {
    /** A slider; with [enabledBy] (a control's key and the choice it must be on), greyed out otherwise. */
    class Slider(val key: String, val label: String, val shown: Shown = Shown.PERCENT, val enabledBy: Pair<String, Int>? = null) : StudioControl()
    /** A switch; with [enabledBy] (a control's key and the choice it must be on), greyed out otherwise. */
    class Toggle(val key: String, val label: String, val enabledBy: Pair<String, Int>? = null) : StudioControl()
    class Choice(val key: String, val options: List<String>) : StudioControl()
    class Kinds(val key: String, val options: List<Pair<ImageVector, String>>) : StudioControl()
    /** The taper's two ends on a bar, with the switch linking their sizes under it. */
    class TaperBar(val start: String, val end: String, val link: String) : StudioControl()
    data object PressureCurve : StudioControl()
    /** The tilt angle on its quarter-circle graph. */
    data object TiltAngle : StudioControl()
    /** The shape's roundness and angle on an ellipse with handles. */
    data object Roundness : StudioControl()
    /** The brush's [kind] of source image, opening its editor. */
    class Source(val label: String, val kind: SourceKind) : StudioControl()
    /** A blend mode, kept by the engine's number for it, picked from a list. */
    class Picker(val key: String, val label: String) : StudioControl()
    /** A card that a tap on its title opens to show [controls] inside it. */
    class Disclosure(val label: String, val controls: List<StudioControl>) : StudioControl()
    data object PreviewCard : StudioControl()
    data object About : StudioControl()
}

class StudioSection(val title: String?, val controls: List<StudioControl>)

/** A page of the studio: its name, its icon (shown [iconSize] across) and its sections. */
class StudioTab(val name: String, val icon: ImageVector, val iconSize: Dp, val sections: List<StudioSection>)

internal fun slider(key: String, label: String, shown: Shown = Shown.PERCENT, enabledBy: Pair<String, Int>? = null) =
    StudioControl.Slider(key, label, shown, enabledBy)
private fun toggle(key: String, label: String, enabledBy: Pair<String, Int>? = null) = StudioControl.Toggle(key, label, enabledBy)
private val FILTERING = listOf("No filtering", "Classic filtering", "Improved filtering")
private fun colorJitter(prefix: String, both: Boolean, brightness: String) = listOf(
    slider("${prefix}_hue", "Hue", if (both) Shown.BOTH else Shown.PERCENT),
    slider("${prefix}_saturation", "Saturation", if (both) Shown.BOTH else Shown.PERCENT),
    slider("${prefix}_brightness", brightness, if (both) Shown.BOTH else Shown.PERCENT),
)

/** The studio's tabs, in order, with what each shows. */
val STUDIO_TABS: List<StudioTab> by lazy {
    listOf(
        StudioTab("Stroke path", PcIcons.StudioStroke, 33.dp, listOf(
            StudioSection("Stroke properties", listOf(
                slider("spacing", "Spacing"), slider("spacing_jitter", "Spacing Jitter"), slider("jitter_lateral", "Jitter Lateral"),
                slider("jitter_linear", "Jitter Linear"), slider("falloff", "Fall off"),
            )),
        )),
        StudioTab("Stabilization", PcIcons.StudioStabilize, 45.dp, listOf(
            StudioSection("StreamLine", listOf(slider("streamline", "Amount"), slider("streamline_pressure", "Pressure"))),
            StudioSection("Stabilization", listOf(slider("stabilization", "Amount"))),
            StudioSection("Motion filtering", listOf(slider("motion_filtering", "Amount"), slider("motion_expression", "Expression"))),
        )),
        StudioTab("Taper", PcIcons.StudioTaper, 41.dp, listOf(
            StudioSection("Pressure taper", listOf(
                StudioControl.TaperBar("pen_taper_start", "pen_taper_end", "pen_taper_link"),
                slider("pen_taper_size", "Size"), slider("pen_taper_opacity", "Opacity"), slider("pen_taper_pressure", "Pressure"),
                slider("pen_taper_tip", "Tip", Shown.TIP), toggle("pen_tip_animation", "Tip animation"),
            )),
            StudioSection("Touch taper", listOf(
                StudioControl.TaperBar("touch_taper_start", "touch_taper_end", "touch_taper_link"),
                slider("touch_taper_size", "Size"), slider("touch_taper_opacity", "Opacity"), slider("touch_taper_tip", "Tip", Shown.TIP),
            )),
            StudioSection("Taper properties", listOf(toggle("classic_taper", "Classic taper"))),
        )),
        StudioTab("Shape", PcIcons.StudioShape, 45.dp, listOf(
            StudioSection(null, listOf(StudioControl.Source("Shape Source", SourceKind.SHAPE))),
            StudioSection("Input style", listOf(
                StudioControl.Choice("input_style", listOf("Touch only", "Azimuth", "Azimuth and barrel roll")),
                // only with the barrel roll to follow
                toggle("relative_to_stroke", "Relative to stroke", enabledBy = "input_style" to 2),
            )),
            StudioSection("Touch properties", listOf(slider("shape_rotation", "Rotation", Shown.FOLLOW))),
            StudioSection("Shape properties", listOf(
                slider("scatter", "Scatter", Shown.TWICE), slider("count", "Count", Shown.COUNT), slider("count_jitter", "Count jitter"),
                toggle("randomized", "Randomized"), toggle("flip_x", "Flip X"), toggle("flip_y", "Flip Y"),
                StudioControl.Roundness, slider("pressure_roundness", "Pressure Roundness", Shown.BOTH),
                slider("tilt_roundness", "Tilt roundness", Shown.BOTH), slider("roundness_jitter_y", "Roundness Vertical Jitter"),
                slider("roundness_jitter_x", "Roundness Horizontal Jitter"),
            )),
            StudioSection("Shape filtering", listOf(StudioControl.Choice("shape_filtering", FILTERING))),
        )),
        StudioTab("Grain", PcIcons.StudioGrain, 45.dp, listOf(
            StudioSection(null, listOf(StudioControl.Source("Grain Source", SourceKind.GRAIN))),
            StudioSection("Grain behavior", listOf(
                StudioControl.Kinds("grain_behavior", listOf(PcIcons.GrainMoving to "Moving", PcIcons.GrainTexturized to "Texturized")),
                slider("grain_movement", "Movement", Shown.MOVEMENT), slider("grain_scale", "Scale"), slider("grain_zoom", "Zoom", Shown.ZOOM),
                slider("grain_rotation", "Rotation", Shown.FOLLOW), slider("grain_depth", "Depth"), slider("grain_depth_min", "Depth minimum"),
                slider("grain_depth_jitter", "Depth jitter"), toggle("grain_offset_jitter", "Offset jitter"),
                StudioControl.Picker("grain_blend", "Blend mode"), slider("grain_brightness", "Brightness", Shown.BOTH),
                slider("grain_contrast", "Contrast", Shown.BOTH),
            )),
            StudioSection("Grain filtering", listOf(StudioControl.Choice("grain_filtering", FILTERING))),
            StudioSection("3D grain behaviour", listOf(toggle("grain_follows_camera", "Grain follows camera"))),
        )),
        StudioTab("Rendering", PcIcons.StudioRendering, 37.dp, listOf(
            StudioSection("Rendering mode", listOf(
                StudioControl.Choice("rendering_mode", listOf("Light Glaze", "Uniformed Glaze", "Intense Glaze", "Heavy Glaze", "Uniform Blending", "Intense Blending")),
            )),
            StudioSection("Blending", listOf(
                slider("flow", "Flow"), slider("wet_edges", "Wet edges"), slider("burnt_edges", "Burnt edges"),
                StudioControl.Picker("burnt_blend", "Burnt edges mode"), StudioControl.Picker("blend_mode", "Blend mode"),
                toggle("luminance_blending", "Luminance blending"), toggle("alpha_threshold", "Alpha Threshold"),
                slider("alpha_threshold_amount", "Threshold Amount", enabledBy = "alpha_threshold" to 1),
                // only for a brush paired with another, which none are yet
                toggle("classic_combine", "Classic Normal Combine mode", enabledBy = "dual_brush" to 1),
            )),
        )),
        StudioTab("Wet Mix", PcIcons.StudioWet, 45.dp, listOf(
            StudioSection("Wet mix", listOf(
                slider("wet_dilution", "Dilution"), slider("wet_charge", "Charge", Shown.CHARGE), slider("wet_attack", "Attack"),
                slider("wet_pull", "Pull"), slider("wet_grade", "Grade", Shown.GRADE), slider("wet_blur", "Blur"),
                slider("wet_blur_jitter", "Blur jitter"), slider("wet_jitter", "Wetness jitter"),
            )),
        )),
        StudioTab("Color dynamics", PcIcons.StudioColor, 41.dp, listOf(
            StudioSection("Stamp color jitter", listOf(
                slider("stamp_hue", "Hue"), slider("stamp_saturation", "Saturation"), slider("stamp_lightness", "Lightness"),
                slider("stamp_darkness", "Darkness"), slider("stamp_secondary", "Secondary color"),
            )),
            StudioSection("Stroke color jitter", listOf(
                slider("stroke_hue", "Hue"), slider("stroke_saturation", "Saturation"), slider("stroke_lightness", "Lightness"),
                slider("stroke_darkness", "Darkness"), slider("stroke_secondary", "Secondary color"),
            )),
            StudioSection("Color pressure", colorJitter("pressure", true, "Brightness") + slider("pressure_secondary", "Secondary Color")),
            StudioSection("Color tilt", colorJitter("tilt", true, "Brightness") + slider("tilt_secondary", "Secondary Color")),
            StudioSection("Color barrel roll", colorJitter("roll", false, "Brightness") + slider("roll_secondary", "Secondary Color")),
        )),
        StudioTab("Dynamics", PcIcons.StudioDynamics, 37.dp, listOf(
            StudioSection("Speed", listOf(slider("speed_size", "Size", Shown.BOTH), slider("speed_opacity", "Opacity", Shown.BOTH), slider("speed_spacing", "Spacing"))),
            StudioSection("Jitter", listOf(slider("jitter_size", "Size"), slider("jitter_opacity", "Opacity"))),
        )),
        StudioTab("Stylus", PcIcons.StudioStylus, 43.dp, listOf(
            StudioSection("Pressure", listOf(
                StudioControl.PressureCurve, slider("pressure_size", "Size", Shown.BOTH), slider("pressure_flow", "Opacity"),
                slider("pressure_opacity", "Flow", Shown.BOTH), slider("pressure_bleed", "Bleed"),
            )),
            StudioSection("Tilt", listOf(
                StudioControl.TiltAngle, slider("tilt_opacity", "Opacity"), slider("tilt_gradation", "Gradation"),
                slider("tilt_bleed", "Bleed"), slider("tilt_size", "Size"), toggle("tilt_size_compression", "Size compression"),
            )),
            StudioSection("Barrel roll", listOf(slider("roll_size", "Size", Shown.BOTH), slider("roll_opacity", "Opacity"), slider("roll_bleed", "Bleed"))),
            StudioSection("Cursor outline", listOf(StudioControl.Choice("cursor_outline", listOf("None", "Contrast", "Active color")))),
            StudioSection("Hover", listOf(slider("hover_pressure", "Estimated pressure"))),
            StudioSection("Hover fill", listOf(StudioControl.Choice("hover_fill", listOf("None", "Shape", "All")))),
        )),
        StudioTab("Properties", PcIcons.StudioProperties, 49.dp, listOf(
            StudioSection("Brush properties", listOf(toggle("orient_to_screen", "Orient to screen"), slider("smudge_pull", "Smudge Pull"))),
            StudioSection("Brush behavior", listOf(
                slider("max_size", "Maximum size", Shown.SIZE), slider("min_size", "Minimum size", Shown.SIZE),
                slider("max_opacity", "Maximum opacity"), slider("min_opacity", "Minimum opacity"),
            )),
        )),
        StudioTab("Materials", PcIcons.StudioMaterials, 29.dp, listOf(
            StudioSection("3D Materials", listOf(
                StudioControl.Disclosure("Metallic", listOf(
                    slider("metallic_amount", "Amount", Shown.METAL), StudioControl.Source("Metallic Source", SourceKind.METALLIC),
                    slider("metallic_scale", "Scale"),
                )),
                StudioControl.Disclosure("Roughness", listOf(
                    slider("roughness_amount", "Amount", Shown.GLOSS), StudioControl.Source("Roughness Source", SourceKind.ROUGHNESS),
                    slider("roughness_scale", "Scale"),
                )),
            )),
        )),
        StudioTab("Preview", PcIcons.StudioPreview, 45.dp, listOf(
            StudioSection("Preview render", listOf(StudioControl.PreviewCard)),
            StudioSection("Preview properties", listOf(
                toggle("preview_stamp", "Use stamp preview"), slider("preview_size", "Size"), slider("preview_pressure_min", "Pressure Minimum"),
                slider("preview_pressure_scale", "Pressure Scale", Shown.DOUBLE), toggle("preview_wet_mix", "Wet Mix"),
                slider("preview_tilt_offset", "Tilt angle", Shown.BOTH),
            )),
        )),
        StudioTab("About this brush", PcIcons.StudioAbout, 45.dp, listOf(StudioSection(null, listOf(StudioControl.About)))),
    )
}

/** Every key the studio's controls read. */
val STUDIO_KEYS: List<String> by lazy {
    STUDIO_TABS.flatMap { t -> t.sections.flatMap { s -> s.controls } }.flatMap { c -> if (c is StudioControl.Disclosure) c.controls else listOf(c) }.flatMap { c ->
        when (c) {
            // a slider's own tilt switch and angle, which its keypad shows
            is StudioControl.Slider -> sliderExtras(c.key).let { e -> listOf(c.key, e.tiltSwitch, e.tiltAngle).filter { it.isNotEmpty() } }
            is StudioControl.Toggle -> listOf(c.key)
            is StudioControl.Choice -> listOf(c.key)
            is StudioControl.Kinds -> listOf(c.key)
            is StudioControl.Picker -> listOf(c.key)
            is StudioControl.TaperBar -> listOf(c.start, c.end, c.link)
            StudioControl.TiltAngle -> listOf("tilt_angle")
            StudioControl.Roundness -> listOf("roundness", "shape_angle")
            else -> emptyList()
        }
    }.plus("most_size").distinct()
}

/** The space above a control: after the section's title (when [titled]) or the control before it. */
/** What a space in the studio's column can follow or come before, besides a control. */
private enum class Edge { TOP, TITLE }

/**
 * The space between [above] (a control, a section's title or the column's top) and [below] (a
 * control or a title), as measured. Titles here are 23 tall (24 there), slider rows
 * 55, switch and picker rows 47, list rows 44 with a line between.
 */
private fun gap(above: Any, below: Any): Dp {
    // switch and picker rows
    fun line(c: Any) = c is StudioControl.Toggle || c is StudioControl.Picker
    return when {
        above == Edge.TOP -> 8.dp
        above == Edge.TITLE -> when {
            line(below) -> 6.dp
            below == StudioControl.PressureCurve -> 27.5.dp
            below == StudioControl.TiltAngle -> 26.5.dp
            below == StudioControl.About -> 27.5.dp
            below is StudioControl.Disclosure -> 39.5.dp
            else -> 21.5.dp
        }
        below == Edge.TITLE -> when {
            line(above) -> 49.5.dp
            above is StudioControl.Slider -> 45.5.dp
            above == StudioControl.PreviewCard -> 47.dp
            else -> 46.dp
        }
        line(above) -> if (line(below)) 4.dp else 19.5.dp
        above is StudioControl.Slider || above is StudioControl.Choice -> if (line(below)) 0.dp else 15.5.dp
        above is StudioControl.Kinds -> 16.dp
        above is StudioControl.TaperBar -> 23.5.dp
        above == StudioControl.PressureCurve -> 24.5.dp
        above == StudioControl.TiltAngle -> 21.5.dp
        above == StudioControl.Roundness -> 16.5.dp
        above is StudioControl.Disclosure -> 29.dp
        else -> 15.5.dp
    }
}

/**
 * A blend mode setting: its mode's name, in blue, opens the modes as a list over it (the grain's
 * own list for the grain, the layers' and behind for the brush and its burnt edges).
 */
@Composable
private fun BlendPicker(vm: CanvasViewModel, c: StudioControl.Picker) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableFloatStateOf(0f) }
    val current = vm.studio(c.key).roundToInt()
    val modes = if (c.key == "grain_blend") vm.grainBlendModeList else vm.brushBlendModeList
    Row(
        Modifier.offset(y = 2.dp).fillMaxWidth().height(47.dp).padding(start = 8.dp, end = 8.dp).onGloballyPositioned { at = it.boundsInWindow().center.y },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.label, color = StudioDim, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        // a file may give the grain a layer mode the list leaves out
        val name = (modes + vm.blendModeList).firstOrNull { it.id.toInt() == current }?.name ?: "Normal"
        Text(name, color = Pc.Accent, fontSize = 17.sp, maxLines = 1, modifier = Modifier.tap { open = true })
    }
    if (open) {
        BlendModeList(modes, current, at) { picked ->
            open = false
            picked?.let { vm.setStudio(c.key, it.toFloat()) }
        }
    }
}

/** The pages a dual brush's second has: the first alone has the rest, which go for the pair. */
private val SecondPages = setOf("Stroke path", "Stabilization", "Taper", "Shape", "Grain", "Rendering", "Dynamics", "Stylus", "Properties")
/** The two parts heading the pages' card: each this tall, this far apart; the bar by the one shown. */
private val PartHeight = 61.dp
private val PartGap = 3.dp
private val PartBar = 5.dp
/** The room they take before the pages (with the list's own 4 gap). */
private val PartsHeight = 146.dp
/** Tapped, the part shown opens both as cards, Combine Mode between. */
private val PartCardWidth = 340.dp
private val PartCardHeight = 111.dp
private val PartCardFill = Color(0xFF212121)
private val CombineRow = 51.dp
private val CombineListLeft = 368.dp

/** A dual brush's two parts: the one shown barred; tapped, it opens their cards, and the other shows. */
@Composable
private fun StudioParts(vm: CanvasViewModel) {
    var cards by remember { mutableStateOf(false) }
    // Uncombine, asking first
    var parting by remember { mutableStateOf<Unit?>(null) }
    Alert(
        parting,
        title = { "Uncombine these brushes?" },
        message = { "The pair becomes two brushes again, side by side in the set. It can't be undone." },
        action = "Uncombine",
        destructive = false,
        icon = PcIcons.AlertQuestion,
        onCancel = { parting = null },
        onAction = {
            parting = null
            vm.uncombineStudio()
        },
    )
    var list by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(IntOffset.Zero) }
    var rowY by remember { mutableFloatStateOf(0f) }
    // the Secondary card held: where the press was (in the window), the Uncombine callout under it
    var callout by remember { mutableStateOf<Offset?>(null) }
    Column(
        Modifier.fillMaxWidth().height(PartsHeight).onGloballyPositioned {
            val p = it.positionInWindow()
            at = IntOffset(p.x.roundToInt(), p.y.roundToInt())
        },
        verticalArrangement = Arrangement.spacedBy(PartGap),
    ) {
        for ((i, second) in listOf(false, true).withIndex()) {
            Box(Modifier.fillMaxWidth().height(PartHeight).tap { if (second == vm.studioSecond) cards = true else vm.showStudioPart(second) }) {
                vm.studioParts.getOrNull(i)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (second == vm.studioSecond) Box(Modifier.size(PartBar, PartHeight - 1.dp).clip(RoundedCornerShape(2.dp)).background(Pc.Accent))
            }
        }
    }
    if (cards) {
        val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val cardW = minOf(PartCardWidth, (screenW - 16.dp).coerceAtLeast(260.dp))
        val place = remember(at) {
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                    IntOffset(
                        at.x.coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)),
                        at.y.coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8)),
                    )
            }
        }
        // a tap outside closes them, but for one on Combine Mode's own list
        PcPopup(
            textScale = MATCHED_POPUP_TEXT,
            popupPositionProvider = place,
            onDismissRequest = { if (!list && callout == null) cards = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = !list),
        ) {
            Column(Modifier.width(cardW).clip(RoundedCornerShape(12.dp)).background(StudioTabs), verticalArrangement = Arrangement.spacedBy(PartGap)) {
                PartCard(vm, 0, "Primary", second = false)
                Row(
                    Modifier.fillMaxWidth().height(CombineRow).onGloballyPositioned { rowY = it.boundsInWindow().center.y }.tap { list = true }.padding(start = 11.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Combine Mode", color = StudioDim, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    val name = vm.combineBlendModeList.firstOrNull { it.id.toInt() == vm.studioCombineMode }?.name.orEmpty()
                    Text(name, color = Pc.Accent, fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Icon(PcIcons.ChevronRight, contentDescription = null, tint = StudioDim, modifier = Modifier.size(24.dp))
                }
                PartCard(vm, 1, "Secondary", second = true, onHold = { callout = it })
            }
        }
    }
    callout?.let { press ->
        val density = LocalDensity.current
        val place = remember(press) {
            object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                    IntOffset((press.x - popupContentSize.width / 2f).roundToInt(), (press.y + with(density) { CalloutDrop.toPx() }).roundToInt())
            }
        }
        PcPopup(
            textScale = MATCHED_POPUP_TEXT,
            popupPositionProvider = place,
            onDismissRequest = { callout = null },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            Box(
                Modifier.size(CalloutWidth, CalloutHeight).clip(RoundedCornerShape(CalloutHeight / 2)).background(CalloutFill).tap {
                    callout = null
                    cards = false
                    parting = Unit
                },
                contentAlignment = Alignment.Center,
            ) {
                Text("Uncombine", color = CalloutInk, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
    if (list) {
        BlendModeList(vm.combineBlendModeList, vm.studioCombineMode, rowY, CombineListLeft) { picked ->
            list = false
            picked?.let { vm.changeStudioCombineMode(it) }
        }
    }
}

/** The Uncombine callout a held Secondary card shows: its size, how far under the press it hangs, its light fill and dark word. */
private val CalloutWidth = 104.dp
private val CalloutHeight = 36.dp
private val CalloutDrop = 10.dp
private val CalloutFill = Color(0xFFE9E9E9)
private val CalloutInk = Color(0xFF1C1C1C)

/** A part as a card: blue when it shows; tapped, it shows, the cards staying; held, [onHold] gets where (in the window). */
@Composable
private fun PartCard(vm: CanvasViewModel, i: Int, name: String, second: Boolean, onHold: ((Offset) -> Unit)? = null) {
    val on = second == vm.studioSecond
    var corner by remember { mutableStateOf(Offset.Zero) }
    val pressing = if (onHold == null) {
        Modifier.tap { vm.showStudioPart(second) }
    } else {
        Modifier.onGloballyPositioned { corner = it.positionInWindow() }
            .pointerInput(Unit) { detectTapGestures(onTap = { vm.showStudioPart(second) }, onLongPress = { onHold(corner + it) }) }
    }
    Box(Modifier.fillMaxWidth().height(PartCardHeight).clip(RoundedCornerShape(12.dp)).background(if (on) Pc.Accent else PartCardFill).then(pressing)) {
        vm.studioParts.getOrNull(i)?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 20.dp))
        }
        Text(name, color = if (on) Color.White else StudioPageInk, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 11.dp, top = 7.dp))
    }
}

private val ModeRow = 38.dp
private val ModeListWidth = 250.dp
/** At most this many rows show at once; the rest scroll. */
private const val MODE_ROWS_SHOWN = 11

/**
 * The blend modes in the layer panel's order, as a light list 10 from the window's right (or [left]
 * from its left) with the current one ([current], an engine number) over the window height
 * [centreY]; a tap picks one, a tap outside it closes it ([onDone] with null).
 */
@Composable
private fun BlendModeList(modes: List<io.github.artcraftandroid.procraft.core.BlendModeInfo>, current: Int, centreY: Float, left: Dp? = null, onDone: (Int?) -> Unit) {
    val density = LocalDensity.current
    val chosen = modes.indexOfFirst { it.id.toInt() == current }.coerceAtLeast(0)
    val shown = minOf(MODE_ROWS_SHOWN, modes.size)
    val first = (chosen - shown / 2).coerceIn(0, (modes.size - shown).coerceAtLeast(0))
    val pitch = with(density) { (ModeRow + 1.dp).toPx() }
    val height = ModeRow * shown + 1.dp * (shown - 1)
    val list = rememberLazyListState(first)
    val place = remember(centreY, chosen, first) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val margin = with(density) { 10.dp.roundToPx() }
                val top = (centreY - (chosen - first + 0.5f) * pitch).roundToInt()
                val maxLeft = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
                return IntOffset(
                    (left?.let { with(density) { it.roundToPx() } } ?: maxLeft).coerceIn(margin, maxLeft),
                    top.coerceIn(margin, (windowSize.height - margin - popupContentSize.height).coerceAtLeast(margin)),
                )
            }
        }
    }
    PcPopup(textScale = MATCHED_POPUP_TEXT, popupPositionProvider = place, onDismissRequest = { onDone(null) }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.size(ModeListWidth, height).shadow(24.dp, RoundedCornerShape(13.dp)).clip(RoundedCornerShape(13.dp)).background(ModeListFill),
            state = list,
        ) {
            items(modes.size) { i ->
                val m = modes[i]
                val picked = i == chosen
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(ModeListLine))
                Row(
                    Modifier.fillMaxWidth().height(ModeRow).background(if (picked) Pc.RowSelected else Color.Transparent).tap { onDone(m.id.toInt()) }
                        .padding(start = 12.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m.name, color = if (picked) Color.White else ModeListInk, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    // its 26 frame centred 31 in from the right
                    PcIcons.BlendGlyphs[m.name]?.let { Icon(it, contentDescription = null, tint = if (picked) Color.White else ModeListGlyph, modifier = Modifier.size(26.dp)) }
                }
            }
        }
    }
}

private val ModeListFill = Color(0xFFE1E1E1)
private val ModeListLine = Color(0xFFCDCDCD)
private val ModeListInk = Color(0xFF393939)
private val ModeListGlyph = Color(0xFF7F7F7F)

/** A slider's value as it reads. */
internal fun shownValue(v: Float, shown: Shown, most: Float = 16f): String {
    // between ends that read by name, a value never rounds onto an end (0.996 reads 99%)
    val between = "${(v * 100).roundToInt().coerceIn(1, 99)}%"
    val belowTop = "${(v * 100).roundToInt().coerceAtMost(99)}%"
    return when (shown) {
        Shown.PERCENT -> when {
            v <= 0f -> "None"
            v >= 1f -> "Max"
            else -> between
        }
        Shown.BOTH -> if (v >= 1f) "Max" else belowTop
        // smoothing all the way
        Shown.GRADE -> if (v >= 1f) "Smooth" else belowTop
        // the control's middle reads as 100%
        Shown.DOUBLE -> "${(v * 200).roundToInt()}%"
        // as a percent, with the control's middle at 100%
        Shown.TWICE -> when {
            v <= 0f -> "None"
            v >= 1f -> "Max"
            else -> "${(v * 200).roundToInt().coerceIn(1, 199)}%"
        }
        // turning with the stroke: all the way reads as following it
        Shown.FOLLOW -> if (v >= 1f) "Follow stroke" else belowTop
        Shown.COUNT -> "${1 + (v * 15).roundToInt()}"
        Shown.TIP -> if (v <= 0f) "Sharp" else if (v >= 1f) "Blunt" else between
        Shown.MOVEMENT -> if (v <= 0f) "Stamp" else if (v >= 1f) "Rolling" else between
        Shown.ZOOM -> if (v <= 0f) "Follow size" else if (v >= 1f) "Cropped" else between
        Shown.CHARGE -> if (v <= 0f) "Disabled" else if (v >= 1f) "Max" else between
        Shown.METAL -> if (v <= 0f) "Non-Metallic" else if (v >= 1f) "Metallic" else between
        Shown.GLOSS -> if (v <= 0f) "Glossy" else if (v >= 1f) "Matte" else between
        Shown.SIZE -> {
            val size = most * v.coerceIn(0f, 1f).toDouble().pow(2.2).toFloat()
            // Max only at the control's end: a size of more than a unit reads as its percent
            if (v >= 1f) "Max" else if (size <= 0f) "None" else "${(size * 100).roundToInt()}%"
        }
    }
}

private val PadBehind get() = if (Pc.light) Color(0xFFF3F3F3) else Color(0xFF1F1F1F)

/**
 * The brush studio: tabs down the left, the drawing pad above, the tab's settings below. It slides up
 * from the bottom of the screen, and back down when it closes.
 */
@Composable
fun BrushStudio(vm: CanvasViewModel) {
    androidx.compose.animation.AnimatedVisibility(
        vm.studioOpen,
        enter = androidx.compose.animation.slideInVertically(Motion.studioIn()) { it },
        exit = androidx.compose.animation.slideOutVertically(Motion.studioOut()) { it },
    ) { Studio(vm) }
}

@Composable
private fun Studio(vm: CanvasViewModel) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(
        Modifier.fillMaxSize().background(StudioBack).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
    ) {
        if (studioSideBySide(landscape, maxWidth)) {
            // the pages listed by name on a card, the settings beside them, the pad on the right
            Row(Modifier.fillMaxSize()) {
                StudioPagesCard(vm)
                StudioSettings(vm, Modifier.width(SettingsWidth).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(StudioLine))
                StudioPad(vm, Modifier.fillMaxSize())
            }
        } else if (maxWidth < ResponsiveLayout.CompactBreakpoint) {
            // Smartphone layout (Procreate Pocket style): full-width drawing pad on top,
            // horizontal scrollable category tabs below it, and full-width settings underneath
            val padHeight = (maxHeight * 0.34f).coerceIn(190.dp, 280.dp)
            Column(Modifier.fillMaxSize()) {
                StudioPad(vm, Modifier.fillMaxWidth().height(padHeight), compact = true)
                Box(Modifier.fillMaxWidth().height(1.dp).background(StudioLine))
                StudioHorizontalTabs(vm)
                Box(Modifier.fillMaxWidth().height(1.dp).background(StudioLine))
                StudioSettings(vm, Modifier.fillMaxSize())
            }
        } else {
            // a short screen (a split window, or a tablet held upright) gives the pad at most 45%
            // of its height, so the settings under it always have room to show and scroll
            val pad = if (maxHeight * 0.45f < PadHeight) maxHeight * 0.45f else PadHeight
            Row(Modifier.fillMaxSize()) {
                StudioRail(vm)
                Column(Modifier.fillMaxSize()) {
                    StudioPad(vm, Modifier.fillMaxWidth().height(pad))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(StudioLine))
                    StudioSettings(vm, Modifier.fillMaxSize())
                }
            }
        }
    }
    SourceEditor(vm)
}

/** Portrait tablet: the pages as icons down the left. */
@Composable
private fun StudioRail(vm: CanvasViewModel) {
    // scrolling where the window is too short for all of them (a phone held sideways)
    Column(Modifier.width(TabsWidth).fillMaxHeight().background(StudioTabs).verticalScroll(rememberScrollState()).padding(top = 46.dp)) {
        STUDIO_TABS.forEachIndexed { i, tab ->
            val on = vm.studioTab == i
            Box(
                Modifier.padding(start = 8.dp, bottom = 3.dp).size(65.dp, 45.dp).clip(RoundedCornerShape(22.5.dp))
                    .background(if (on) Pc.Accent else Color.Transparent).tap { vm.studioTab = i },
                contentAlignment = Alignment.Center,
            ) {
                // drawn in a 50 frame, fitted to the tab's own size
                Icon(tab.icon, contentDescription = tab.name, tint = if (on) Color.White else StudioTabIcon, modifier = Modifier.requiredSize(tab.iconSize))
            }
        }
    }
}

/**
 * Smartphone horizontal tab bar: scrollable pill tabs with icon + label and >= 44.dp touch target
 * so the Drawing Pad and Settings can both use 100% of the phone's screen width.
 */
@Composable
private fun StudioHorizontalTabs(vm: CanvasViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(StudioTabs)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        STUDIO_TABS.forEachIndexed { i, tab ->
            if (i == 0 && vm.studioDual) {
                for ((partIdx, second) in listOf(false, true).withIndex()) {
                    val activePart = vm.studioSecond == second
                    Row(
                        Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (activePart) Color(0xFF333333) else Color(0xFF1F1F1F))
                            .border(1.5.dp, if (activePart) Pc.Accent else StudioButtonEdge, RoundedCornerShape(22.dp))
                            .tap { vm.showStudioPart(second) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (partIdx == 0) "1st Brush" else "2nd Brush",
                            color = if (activePart) Color.White else StudioPageInk,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (vm.studioSecond && tab.name !in SecondPages) return@forEachIndexed
            val on = vm.studioTab == i
            Row(
                Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (on) Pc.Accent else Color.Transparent)
                    .tap { vm.studioTab = i }
                    .padding(start = 10.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.name,
                    tint = if (on) Color.White else StudioTabIcon,
                    modifier = Modifier.requiredSize(tab.iconSize * 0.78f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    tab.name,
                    color = if (on) Color.White else StudioPageInk,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Landscape: the pages by icon and name on a card headed "Brush Studio", the list scrolling under the title. */
@Composable
private fun StudioPagesCard(vm: CanvasViewModel) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp).width(320.dp).fillMaxHeight().clip(shape).background(StudioTabs)
            .border(1.dp, StudioCardEdge, shape),
    ) {
        Text("Brush Studio", color = StudioCardTitle, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 24.dp, top = 19.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 17.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (vm.studioDual) StudioParts(vm)
            STUDIO_TABS.forEachIndexed { i, tab ->
                if (vm.studioSecond && tab.name !in SecondPages) return@forEachIndexed
                val on = vm.studioTab == i
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (on) Pc.Accent else Color.Transparent).tap { vm.studioTab = i },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                        Icon(tab.icon, contentDescription = null, tint = if (on) Color.White else StudioTabIcon, modifier = Modifier.requiredSize(tab.iconSize))
                    }
                    Text(tab.name, color = if (on) Color.White else StudioPageInk, fontSize = 15.5.sp, maxLines = 1)
                }
            }
        }
    }
}

/** The drawing pad with its buttons: Drawing Pad (its menu), Cancel and Done. */
@Composable
private fun StudioPad(vm: CanvasViewModel, modifier: Modifier, compact: Boolean = false) {
    // the pad's own colour behind its surface, so the canvas doesn't show through before the
    // pad's first frame (as the studio slides up)
    // a sphere showing the brush's materials on their page, or on every page with the 3D preview on
    val sphere = STUDIO_TABS.getOrNull(vm.studioTab)?.name == "Materials" || vm.pad3d
    androidx.compose.runtime.LaunchedEffect(sphere) { vm.showPadSphere(sphere) }
    val inset = if (compact) 12.dp else 20.dp
    Box(modifier.background(PadBehind)) {
        // in a layout of its own, which clips it: Android 10 and 11 clear a surface's hole as far as
        // its view is clipped, and the Compose holder doesn't clip, so all the window drawn so far went
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { android.widget.FrameLayout(it).apply { addView(PadSurfaceView(it, vm)) } },
            modifier = Modifier.fillMaxSize(),
        )
        // drawn over the surface's hole until the pad has a frame of its own
        if (!vm.padShown) Box(Modifier.fillMaxSize().background(PadBehind))
        Row(Modifier.offset(inset, inset).height(44.dp).onGloballyPositioned { vm.padButtonCentre = it.boundsInWindow().center.x }
            .clip(CircleShape).background(StudioButton).border(1.dp, StudioButtonEdge, CircleShape)
            .tap { vm.padMenuOpen = !vm.padMenuOpen }.padding(start = 9.dp, end = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(PcIcons.StudioPad, contentDescription = null, tint = StudioButtonInk, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (compact && vm.randomisesBrushes) "Pad" else "Drawing Pad", color = StudioButtonInk, fontSize = if (compact) 15.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = inset, end = inset), verticalAlignment = Alignment.CenterVertically) {
            // a plugin's: the brush set at random
            if (vm.randomisesBrushes) {
                Box(Modifier.height(44.dp).clip(CircleShape).background(StudioButton).border(1.dp, StudioButtonEdge, CircleShape)
                    .tap { vm.randomiseStudio() }.padding(horizontal = if (compact) 12.dp else 16.dp), contentAlignment = Alignment.Center) {
                    Text("Randomise", color = StudioButtonInk, fontSize = if (compact) 15.sp else 17.sp, maxLines = 1)
                }
                Spacer(Modifier.width(if (compact) 8.dp else 13.dp))
            }
            Box(Modifier.size(73.dp, 44.dp).clip(CircleShape).background(StudioButton).border(1.dp, StudioButtonEdge, CircleShape).tap { vm.closeStudio(keep = false) },
                contentAlignment = Alignment.Center) {
                Text("Cancel", color = StudioButtonInk, fontSize = if (compact) 15.sp else 17.sp, maxLines = 1)
            }
            Spacer(Modifier.width(if (compact) 8.dp else 13.dp))
            Box(Modifier.size(44.dp).clip(CircleShape).background(StudioDone).tap { vm.closeStudio(keep = true) }, contentAlignment = Alignment.Center) {
                Icon(PcIcons.DoneTick, contentDescription = "Done", tint = Color.White, modifier = Modifier.requiredSize(40.dp))
            }
        }
    }
}

/** The open page's settings, in sections, spaced as measured. */
@Composable
private fun StudioSettings(vm: CanvasViewModel, modifier: Modifier) {
    val tab = STUDIO_TABS.getOrNull(vm.studioTab) ?: return
    // each section opens at its top
    Column(modifier.verticalScroll(remember(vm.studioTab) { androidx.compose.foundation.ScrollState(0) }).padding(start = 20.dp, end = 20.dp, bottom = 77.dp)) {
        // what the space above each thing follows: a control, a title, or the top
        var above: Any = Edge.TOP
        tab.sections.forEachIndexed { k, section ->
            // the About page is headed by the brush's name
            val about = section.controls.firstOrNull() == StudioControl.About
            val title = section.title ?: vm.studioName.takeIf { about }
            title?.let {
                Spacer(Modifier.height(if (k == 0) 25.dp else gap(above, Edge.TITLE)))
                if (about) {
                    BrushNameHeading(vm)
                } else {
                    Text(it, color = StudioHeader, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
                above = Edge.TITLE
            }
            for (control in section.controls) {
                Spacer(Modifier.height(gap(above, control)))
                StudioControlView(vm, control)
                above = control
            }
        }
        // the rows plugins that are on add to Properties, each under its own label
        if (tab.name == "Properties") for (row in vm.pluginBrushControls()) {
            Spacer(Modifier.height(gap(above, Edge.TITLE)))
            Text(row.label, color = StudioHeader, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            val control = StudioControl.Choice(row.key, row.choices)
            Spacer(Modifier.height(gap(Edge.TITLE, control)))
            StudioControlView(vm, control)
            above = control
        }
    }
}

@Composable
private fun StudioControlView(vm: CanvasViewModel, c: StudioControl) {
    when (c) {
        is StudioControl.Slider -> {
            val enabled = c.enabledBy?.let { (key, choice) -> vm.studio(key).roundToInt() == choice } ?: true
            val extras = remember(c.key) { keypadExtras(vm, c.key) }
            StudioSlider(c.label, vm.studio(c.key), c.shown, most = if (c.shown == Shown.SIZE) vm.studio("most_size") else 16f, enabled = enabled, extras = extras) { vm.setStudio(c.key, it) }
        }
        // a long name runs on under the switch, which is drawn over it
        is StudioControl.Toggle -> Box(Modifier.fillMaxWidth().height(47.dp).padding(start = 8.dp)) {
            val enabled = c.enabledBy?.let { (key, choice) -> vm.studio(key).roundToInt() == choice } ?: true
            Text(c.label, color = if (enabled) StudioDim else StudioOff, fontSize = 17.sp, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.CenterStart))
            val on = vm.studio(c.key) >= 0.5f
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).then(if (enabled) Modifier.tap { vm.setStudio(c.key, if (on) 0f else 1f) } else Modifier)) {
                Switch(on, enabled = enabled, fixedTrack = StudioSwitchFixed, fixedKnob = StudioSwitchFixedKnob)
            }
        }
        is StudioControl.Choice -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(StudioList)) {
            val chosen = vm.studio(c.key).roundToInt()
            c.options.forEachIndexed { i, label ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(StudioListLine))
                Row(
                    Modifier.fillMaxWidth().height(44.dp).background(if (i == chosen) Pc.Accent else Color.Transparent).tap { vm.setStudio(c.key, i.toFloat()) }
                        .padding(start = 16.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = if (i == chosen) Color.White else StudioDim, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    if (i == chosen) Icon(PcIcons.MenuTick, contentDescription = null, tint = Color.White, modifier = Modifier.requiredSize(34.dp))
                }
            }
        }
        is StudioControl.Kinds -> {
            val chosen = vm.studio(c.key).roundToInt()
            Row(
                Modifier.padding(start = 8.dp, end = 8.dp).fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp)).background(StudioSegments).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                c.options.forEachIndexed { i, (icon, label) ->
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(15.dp)).background(if (i == chosen) Pc.Accent else Color.Transparent).tap { vm.setStudio(c.key, i.toFloat()) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // drawn in a 50 frame, shown 30 across, the name 2 after
                        Icon(icon, contentDescription = null, tint = if (i == chosen) Color.White else StudioDim, modifier = Modifier.requiredSize(30.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(label, color = if (i == chosen) Color.White else StudioDim, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        is StudioControl.TaperBar -> Column {
            // linked, the two ends move together
            val linked = vm.studio(c.link) >= 0.5f
            TaperBar(
                vm.studio(c.start), vm.studio(c.end),
                { vm.setStudio(c.start, it); if (linked) vm.setStudio(c.end, it) },
            ) { vm.setStudio(c.end, it); if (linked) vm.setStudio(c.start, it) }
            Spacer(Modifier.height(4.5.dp))
            StudioControlView(vm, StudioControl.Toggle(c.link, "Link tip sizes"))
        }
        StudioControl.PressureCurve -> PressureCurve(
            vm.studioCurve, edge = StudioCurveEdge, fill = StudioCurveFill, corner = 8.dp, line = StudioCurveLine, lineWidth = 1.dp, handle = 5.5.dp to 3.5.dp,
            onChange = vm::changeStudioCurve,
        )
        StudioControl.TiltAngle -> TiltAngle(vm.studio("tilt_angle")) { vm.setStudio("tilt_angle", it) }
        StudioControl.Roundness -> RoundnessGraph(vm.studio("roundness"), vm.studio("shape_angle"), onRoundness = { vm.setStudio("roundness", it) }, onAngle = { vm.setStudio("shape_angle", it) })
        is StudioControl.Source -> if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) SourceBox(vm, c) else Row(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).background(StudioCard)
                .tap { vm.sourceEditing = c.kind }.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.label, color = StudioHeader, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            // the image it uses: the shape white on the row (the round tip a white disc), the grain as its greys, a
            // material's amount all over as white
            val image = vm.studioSources[c.kind]
            val plain = if (c.kind.material()) Modifier.background(Color.White) else Modifier.clip(CircleShape).background(Color.White)
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).then(if (image == null) plain else Modifier)) {
                image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        is StudioControl.Picker -> BlendPicker(vm, c)
        is StudioControl.Disclosure -> DisclosureCard(vm, c)
        // the brush's stroke filling the card's height, its name over it on a patch of the card's own colour
        StudioControl.PreviewCard -> Box(
            Modifier.fillMaxWidth()
                .aspectRatio(if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) 623f / 200f else 278.5f / 91.5f)
                .clip(RoundedCornerShape(12.dp))
                .background(PreviewCardFill).border(1.dp, PreviewCardEdge, RoundedCornerShape(12.dp)),
        ) {
            vm.studioPreview?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier.padding(start = 15.5.dp, top = 14.5.dp).height(37.dp).clip(RoundedCornerShape(10.dp)).background(PreviewCardFill)
                    .padding(horizontal = 17.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(vm.studioName, color = PreviewCardName, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        StudioControl.About -> AboutBrush(vm)
    }
}

/** The About page's heading: the brush's name, tapped to change it there. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BrushNameHeading(vm: CanvasViewModel) {
    var renaming by remember { mutableStateOf(false) }
    if (!renaming) {
        Text(vm.studioName, color = StudioHeader, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 8.dp).tap { renaming = true })
        return
    }
    // the keyboard put away ends it, the name as typed
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    var hadKeyboard by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(keyboard) {
        if (keyboard) hadKeyboard = true else if (hadKeyboard) focus.clearFocus()
    }
    // named as it is typed, so that Done with the keyboard up keeps it
    InlineName(vm.studioName, StudioHeader, 21.sp, Modifier.padding(start = 8.dp).fillMaxWidth(), FontWeight.Bold, vm::renameStudio) {
        renaming = false
    }
}

/**
 * A brush's About page: a card with its maker's picture, name and signature (ProCraft's own for
 * ours), then making a new reset point and resetting to it, with when it was made.
 */
@Composable
private fun AboutBrush(vm: CanvasViewModel) {
    val about = vm.studioAbout
    // each asks first: neither can be undone (true a new reset point, false a reset)
    var asking by remember { mutableStateOf<Boolean?>(null) }
    Alert(
        asking?.takeIf { it },
        title = { "New reset point?" },
        message = { "Resetting the brush will bring it back to how it is now. It can't be undone." },
        action = "Make",
        destructive = false,
        onCancel = { asking = null },
        onAction = {
            asking = null
            vm.newResetPoint()
        },
    )
    Alert(
        asking?.takeIf { !it },
        title = { "Reset this brush?" },
        message = { "It goes back to its last reset point, or to how it came. It can't be undone." },
        action = "Reset",
        onCancel = { asking = null },
        onAction = {
            asking = null
            vm.resetStudio()
        },
    )
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().height(295.dp).clip(RoundedCornerShape(20.dp)).background(AboutCard), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(30.dp))
            Box(Modifier.size(96.dp).clip(CircleShape).background(AboutFace), contentAlignment = Alignment.Center) {
                val picture = about?.picture
                if (picture != null) {
                    Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(PcIcons.Brush, contentDescription = null, tint = StudioDim, modifier = Modifier.size(56.dp))
                }
            }
            Spacer(Modifier.height(21.dp))
            Text("Made by ${about?.author?.takeIf { it.isNotBlank() } ?: "ProCraft"}", color = AboutName, fontSize = 20.sp, maxLines = 1)
            Spacer(Modifier.height(25.dp))
            // the signature in grey, whatever its own colour
            about?.signature?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(AboutSignature), modifier = Modifier.size(300.dp, 64.dp))
            }
        }
        Spacer(Modifier.height(25.dp))
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(AboutCard).tap { asking = true }.padding(start = 15.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Create new reset point", color = AboutRowInk, fontSize = 17.sp, maxLines = 1)
        }
        Spacer(Modifier.height(11.dp))
        val made = about?.resetMade
        Column(
            Modifier.fillMaxWidth().height(if (made != null) 69.dp else 44.dp).clip(RoundedCornerShape(22.dp)).background(AboutCard).tap { asking = false }
                .padding(start = 15.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Reset brush", color = AboutRowInk, fontSize = 17.sp, maxLines = 1)
            if (made != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(17.dp).clip(CircleShape).background(Pc.Accent), contentAlignment = Alignment.Center) {
                        Icon(PcIcons.MenuTick, contentDescription = null, tint = Color.White, modifier = Modifier.requiredSize(20.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    // the short date and time with a comma between
                    val day = java.util.Date(made * 1000)
                    val at = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(day) + ", " +
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(day)
                    Text("Last reset point: $at", color = AboutRowInk, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Whether this is a material's source (white all over when it has no image). */
private fun SourceKind.material() = this == SourceKind.METALLIC || this == SourceKind.ROUGHNESS

/**
 * A card titled [StudioControl.Disclosure.label] that a tap on its title opens, its chevron turned
 * down, to show its controls inside it, 8 in from its sides and spaced as a page's are; the page
 * opens with it closed.
 */
@Composable
private fun DisclosureCard(vm: CanvasViewModel, c: StudioControl.Disclosure) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(StudioMaterial)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).tap { open = !open }.padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.label, color = StudioMaterialInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Box(Modifier.size(30.dp).clip(CircleShape).background(StudioBack), contentAlignment = Alignment.Center) {
                Icon(PcIcons.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f))
            }
        }
        if (open) Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            c.controls.forEachIndexed { i, control ->
                c.controls.getOrNull(i - 1)?.let { Spacer(Modifier.height(gap(it, control))) }
                StudioControlView(vm, control)
            }
        }
    }
}

/**
 * Landscape: a source as a title with an Edit button, over a square of its image as wide as the
 * rows (the shape white on dark, the grain as its greys, a material's amount all over white).
 */
@Composable
private fun SourceBox(vm: CanvasViewModel, c: StudioControl.Source) {
    val open = { vm.sourceEditing = c.kind }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.5.dp).height(45.dp)) {
            // where a section's title sits
            Text(c.label, color = StudioHeader, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 9.5.dp))
            Box(Modifier.align(Alignment.CenterEnd).offset(x = 4.dp).size(54.dp, 45.dp).clip(CircleShape).background(StudioButton).tap { open() },
                contentAlignment = Alignment.Center) {
                Text("Edit", color = StudioButtonInk, fontSize = 17.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.5.dp))
        val image = vm.studioSourcesBig[c.kind]
        val white = image == null && c.kind.material()
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).background(if (white) Color.White else StudioButton).tap { open() }) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            // the round tip: a white disc
            if (image == null && !white) Box(Modifier.fillMaxSize(0.7f).align(Alignment.Center).clip(CircleShape).background(Color.White))
        }
    }
}

private val AboutCard get() = if (Pc.light) Color(0xFFEBEBEB) else Color(0xFF252525)
private val AboutFace get() = if (Pc.light) Color(0xFFD8D8D8) else Color(0xFF3A3A3A)
private val AboutName get() = if (Pc.light) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
private val AboutRowInk get() = if (Pc.light) Color(0xFF4A4A4A) else Color(0xFFBBBBBB)
private val AboutSignature get() = if (Pc.light) Color(0xFFA8A8A8) else Color(0xFF595959)

internal val PadMenuFill = Color(0xFFC1C1C1)
internal val PadMenuGroup = Color(0xFFD3D3D3)
internal val PadMenuText = Color(0xFF393939)
internal val PadMenuLine = Color(0xFFBDBDBD)
