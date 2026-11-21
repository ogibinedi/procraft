//! The canvas on the GPU: a texture per layer, the stroke being drawn, the composite, and the
//! undo history (pixels and document structure).
//!
//! Calls queue work; [`Canvas::render`] records and submits it in order, recomposites what
//! changed and, given a target, draws the canvas to it.

use std::collections::{HashMap, HashSet};
use std::num::NonZeroU64;
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, PoisonError, mpsc};

use procraft_engine::animation::{self, FrameLook};
use procraft_engine::brush::{BRUSH_BEHIND, Brush, GRAIN_LINEAR_HEIGHT, GrainMode, Rendering, Source, run_out};
use procraft_engine::document::{BlendMode, Document, Layer, LayerId, LayerKind};
use procraft_engine::geom::{Affine, IRect};
use procraft_engine::stroke::{MOST_ROUNDNESS, PickUp, Stamp};
use procraft_engine::textures::{self, GrayImage, TEXTURE_SIZE};

use wgpu::util::DeviceExt;

use crate::{Gpu, GpuError};

mod fragments;
pub(crate) mod plugin_filter;
pub(crate) mod plugin_picture;
mod rewind;
mod smudge;
mod store;

pub use store::{LayerStore, STORE_LIT};

const LAYER_FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba8Unorm;

/// The id the next canvas made takes (see [`Canvas::id`]).
static NEXT_CANVAS: AtomicU64 = AtomicU64::new(1);

/// The most textures the canvas's size kept for reuse (see `Canvas::spares`).
const SPARES_KEPT: usize = 1;
/// How many pixels of a row or column each pixel of a layer's bounds lines stands for (as
/// `bounds.wgsl` has it).
const BOUNDS_STRETCH: u32 = 512;
/// Half floats, so a stroke's build-up from many faint stamps doesn't band (where the GPU draws
/// into them: see [`Abilities::stroke_format`]).
const STROKE_FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Rgba16Float;
/// Every draw gets its own slot in the uniform buffer (dynamic offsets are 256-byte aligned).
const SLOT: u64 = 256;
/// The Multiply blend mode's number.
const MULTIPLY_MODE: u32 = 1;
/// Set in a layer uniform's `stroke_blend` (over the blend mode's number): luminance blending.
const STROKE_LUMINANCE: u32 = 0x100;

/// The largest uniform block a shader reads.
const UNIFORM_SIZE: u64 = 160;
const _: () = assert!(
    size_of::<LayerUniform>() as u64 <= UNIFORM_SIZE
        && size_of::<StampUniform>() as u64 <= UNIFORM_SIZE
        && size_of::<FilterUniform>() as u64 <= UNIFORM_SIZE
        && size_of::<DisplayUniform>() as u64 <= UNIFORM_SIZE,
    "a uniform block outgrew its binding"
);

/// `s` given in another canvas's pixels, as drawn on one `k` times as big whose top left is at
/// `origin` of those: where it is, and its moving grain with it (its size is scaled as it is drawn,
/// see [`scaled_radius`]).
fn scaled_stamp(s: &Stamp, origin: [f32; 2], k: f32) -> Stamp {
    let mut s = *s;
    s.center = procraft_engine::geom::Vec2::new((s.center.x - origin[0]) * k, (s.center.y - origin[1]) * k);
    s.grain_offset = s.grain_offset * k;
    s.grain_size *= k;
    s.distance *= k;
    s
}

/// How far past its radius a stamp's shape reaches where it is drawn: half a pixel of the round
/// tip's rim, or the shape's quad's reach (as `stamp.wgsl` has them).
fn stamp_rim(style: &StrokeStyle) -> f32 {
    if style.shape.is_some() { std::f32::consts::FRAC_1_SQRT_2 } else { 0.5 }
}

/// A stamp drawn at `radius` (its rim past that) on a canvas, as drawn `k` times as big: its edge,
/// rim and all, as far out, the rim itself a pixel of the bigger one.
fn scaled_radius(style: &StrokeStyle, radius: f32, k: f32) -> f32 {
    let rim = stamp_rim(style);
    ((radius + rim) * k - rim).max(0.0)
}

/// The vertex layout of a warp mesh corner: where it goes, and where it came from (pixels).
const MESH_ATTRIBUTES: [wgpu::VertexAttribute; 2] = wgpu::vertex_attr_array![0 => Float32x2, 1 => Float32x2];

/// The vertex layout of a stamp instance (stroke stamps and the predicted tail).
const STAMP_ATTRIBUTES: [wgpu::VertexAttribute; 13] = wgpu::vertex_attr_array![
    0 => Float32x2, 1 => Float32, 2 => Float32, 3 => Float32, 4 => Float32, 5 => Float32x2, 6 => Float32x2, 7 => Float32x4,
    8 => Float32x4, 9 => Float32x4, 10 => Float32, 11 => Float32, 12 => Float32x4
];

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct OverlayUniform {
    m0: [f32; 4],
    m1: [f32; 4],
    color: [f32; 4],
    shape: i32,
    srgb: u32,
    shape_lod_cap: f32,
    _pad: u32,
}
/// The most painting sent to the GPU as one piece of work, by [`stamp_work`]. A driver stops work
/// that runs too long and takes the device, and all it holds, with it: an Adreno 750 did at under
/// three seconds, which a long stroke's redrawing at its end took as one piece, and a slower GPU
/// is there with a much shorter stroke. This much is a thousandth of a second on that Adreno, so
/// a fair margin even at a hundredth of its speed. Much less costs more than it saves: a tiled
/// GPU can read and write the whole stroke for each piece.
const WORK_AT_ONCE: u64 = 64 << 20;
/// The most of an open stroke's stamps kept as laid (see [`Canvas::replace_stamps`]): past it, a
/// stroke redrawn is laid whole. A stamp is about 140 bytes.
const LAID_KEPT_MOST: usize = 1 << 17;
/// The most stamps one render uploads (the rest in renders after it, at once): a long stroke's end
/// redraws all of it, and its millions of stamps in one buffer could pass the most the GPU makes,
/// leaving no buffer to draw this or any later stroke with. 1M stamps take about 100 MB.
const STAMPS_AT_ONCE: u64 = 1 << 20;
/// How long a render that shows the canvas spends laying a stroke laid again whole as it ends
/// (its end taper reaching back over paint laid in place) before leaving the rest to the next, the
/// picture shown as it was until it is all laid: laid in one render, a long stroke of wet paint
/// held the canvas, and every touch waiting on it, for a tenth of a second or more.
const RELAY_AT_ONCE: std::time::Duration = std::time::Duration::from_millis(6);
/// How much GPU time a render that shows the canvas gives paint laid in place (smudging, wet paint),
/// ms, by how fast the GPU got through such work before: the rest waits for the renders after. Laid
/// all at once, a stroke whose GPU fell behind the pen laid more in each frame, so each frame took
/// longer and laid more again, until one held the GPU past its timeout (the device lost, the app
/// gone) or the canvas for seconds: now it falls behind the pen evenly instead, and catches up.
const IN_PLACE_FRAME_MS: f64 = 33.0;
/// The least work of paint laid in place a render lays however slow the GPU seems (see
/// [`WORK_AT_ONCE`]): a few stamps of a big wet brush (a GPU's speed seen late, or with other work,
/// can seem slower than it is).
const IN_PLACE_LEAST: u64 = WORK_AT_ONCE;
/// What a stamp counts for besides the pixels it covers; and a stamp painted in place, a compute
/// pass for each, for each pixel and for itself.
const STAMP_WORK: u64 = 1 << 10;
const IN_PLACE_PIXEL_WORK: u64 = 16;
const IN_PLACE_STAMP_WORK: u64 = 256 << 10;
/// The most painting a paced canvas (see [`Canvas::pace`]) sends as one piece, by [`stamp_work`]:
/// a sixteenth of [`WORK_AT_ONCE`], short on a weak GPU even where a wet brush's paint costs far
/// more than its share. A weak GPU took seconds over a wet brush's sample stroke sent as a few
/// pieces at once, and the window's frames waited behind them. (Cut by the work alone, not by how
/// long pieces take, the pieces and what they lay are the same each time.)
const PACED_WORK: u64 = WORK_AT_ONCE / 16;
/// The longest a paced canvas waits for a piece (a device lost or stopped never finishes it).
const PACED_WAIT_MOST: std::time::Duration = std::time::Duration::from_secs(5);

/// Undo steps kept, and the most memory they may hold (their pixels and the selections they put
/// back): no more than half the layers' textures' budget, where there is one (a device of little
/// memory), so the history never leaves the layers worked on too little room to stay on the GPU;
/// but never less than the least (a budget that small isn't a device's).
const MAX_UNDO: usize = 100;
const MAX_UNDO_BYTES: u64 = 768 << 20;
const LEAST_UNDO_BYTES: u64 = 256 << 20;

/// A device of little memory (3 or 4 GB): its layers' textures given at most this much (a
/// quarter of its memory). There the history keeps its older steps' pixels compressed off the GPU
/// (see [`Canvas::pack_history`]), and the composite's caches have no second textures of their
/// own (see [`CachePicture`]).
const LOW_MEMORY_BUDGET: u64 = 1280 << 20;
/// On such a device, the newest steps the history keeps on the GPU, so undoing and redoing them
/// is as quick as ever: those whose pixels come to a quarter of the history's room, and two at
/// least.
const RECENT_HISTORY_SHARE: u64 = 4;
const RECENT_STEPS: usize = 2;
/// The most pixels one lot of the history's packing reads back, and the most regions (at least
/// one).
const PACK_AT_ONCE: u64 = 32 << 20;
const PACK_MOST_READS: usize = 16;

/// How a transformed layer is resampled.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Resample {
    Nearest,
    Bilinear,
    Bicubic,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StrokeTool {
    Paint,
    Erase,
    /// Drags colour along: the layer itself changes as the stroke goes.
    Smudge,
    /// The stroke's pixels take the layer's place where they reach (their alpha marked by 1): what
    /// a change made elsewhere brings back to the layer. Drawn in, never stamped.
    Laid,
}

/// How a stroke is drawn.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct StrokeStyle {
    pub tool: StrokeTool,
    /// sRGB, 0..1.
    pub color: [f32; 3],
    /// The stroke's opacity: its stamps build up to at most this.
    pub opacity: f32,
    /// The round tip's hardness, 0..1.
    pub hardness: f32,
    /// The layer's alpha lock when the stroke began: paint only recolours existing pixels.
    pub preserve_alpha: bool,
    /// A shape image, or none for the round tip.
    pub shape: Option<u32>,
    /// A grain image, or none.
    pub grain: Option<u32>,
    pub grain_mode: GrainMode,
    /// One grain tile on the canvas, pixels.
    pub grain_scale: f32,
    pub grain_depth: f32,
    pub rendering: Rendering,
    /// Smudge: the brush's smudge pull, 0..1. At each stamp the brush keeps this to the power
    /// 1/8 of the colour it carries (times its pressure curve), and picks up the rest.
    pub pull: f32,
    /// How much the stroke's paint pools at its edges (wet edges), and burns the paint it goes
    /// over where it is thin (burnt edges), 0..1.
    pub wet_edges: f32,
    pub burnt_edges: f32,
    /// Where the stroke's coverage turns fully on or off (0..1), or none if negative.
    pub alpha_threshold: f32,
    /// The grain made lighter or darker, and of more or less contrast, −1..1.
    pub grain_brightness: f32,
    pub grain_contrast: f32,
    /// Wet mix: how much of the colour under the brush mixes into its paint, 0..1; none paints
    /// plainly.
    pub wet_pull: Option<f32>,
    /// Wet paint mixes with the layer as it was before the stroke (a glaze), not with the stroke
    /// as it goes (a blending mode).
    pub wet_from_before: bool,
    /// A smudge picks up from the image as last shown (every layer), not from its layer alone.
    pub from_image: bool,
    /// Wet mix: how many stamps' worth of paint the brush holds; none never runs out.
    pub load: f32,
    /// Wet mix: water in the paint, 0..1: it goes on thinner.
    pub dilution: f32,
    /// The brush's fall off, 0..1: the paint it mixes in runs out along the stroke as its stamps
    /// fade.
    pub falloff: f32,
    /// Wet mix: how hard the paint goes on over what is there (less of it pulled in), its grade
    /// (how much of its mixture each stamp lays down, -1..1), how blurred what it lays down is,
    /// and how much the pull varies from stamp to stamp; each 0..1 but the grade.
    pub attack: f32,
    /// How far wet paint's stamps lay, by the opacity slider (see [`StrokeStyle::wetness_at`]);
    /// 1 at full.
    pub wetness: f32,
    pub grade: f32,
    pub blur: f32,
    pub wet_jitter: f32,
    /// How the stroke blends into its layer: a blend mode's number (0 is Normal, in the order of
    /// `BlendMode`).
    pub blend: u32,
    /// Wet mix: how much the blur varies from stamp to stamp, 0..1.
    pub blur_jitter: f32,
    /// How the grain combines with the shape, and the burnt edges with the stroke (blend modes'
    /// numbers; both multiply as they start).
    pub grain_blend: u32,
    pub burnt_blend: u32,
    /// The grain turned, radians.
    pub grain_rotation: f32,
    /// No grain filtering: the grain read pixel by pixel, sharp, as from a finer image.
    pub grain_sharp: bool,
    /// A brush file's grain to read in place of the brush images (see
    /// [`Canvas::use_full_grain`]), and pixels a side of the image the grain is read from.
    pub full_grain: Option<u64>,
    pub grain_side: f32,
    /// The brush file's shape it reads at its own size, if it does (see
    /// [`Canvas::use_full_shape`]), and what its shapes are.
    pub full_shape: Option<u64>,
    pub tip_kind: TipKind,
    /// No shape filtering: the shape image read pixel by pixel, its edges and specks hard.
    pub shape_sharp: bool,
    /// The coarsest mip level a small stamp reads its shape from, so a thin shape keeps its
    /// strength however small the stamp.
    pub shape_lod_cap: f32,
    /// The secondary colour (sRGB, 0..1), mixed in as far as each stamp says.
    pub secondary: [f32; 3],
    /// Drawn as a brush preview (the studio's drawing pad, the library's strokes), which burns
    /// nothing and, on the pad, draws wet edges only as a firmer rim.
    pub preview: bool,
    /// Drawn as the library's sample stroke (a preview, not the pad): its wet edges pool as they
    /// do on the canvas.
    pub sample: bool,
    /// Luminance blending: the stroke mixed into its layer by its coverage in squared colour
    /// (nearer to light), not as the colour's numbers are.
    pub luminance: bool,
    /// Dry paint laid in place, each pixel taking paint only as far as it has room left (see
    /// `Brush::laid_in_place`).
    pub laid_in_place: bool,
    /// What a smudge's stroke keeps with its layer between strokes, painting into that rather than
    /// the layer (see [`LayerStore`]): a store's kind ([`STORE_LIT`]), or 0 for none.
    pub store: u32,
}

impl StrokeStyle {
    /// Whether the stroke changes its layer as it goes (smudging, painting wet) rather than
    /// building up in the stroke texture.
    pub fn in_place(&self) -> bool {
        self.tool == StrokeTool::Smudge || self.paints_in_place()
    }

    /// Whether a dual brush's second can go with the stroke: not with a smudge (paint laid in
    /// place, dry or wet, is put together with it, see `cs_synthesize`).
    pub fn takes_second(&self) -> bool {
        self.tool != StrokeTool::Smudge
    }

    /// The style as a dual brush's second: only coverage, drawn apart into its own texture, so
    /// never laid in place or mixed wet, whatever its own settings say.
    pub fn as_second(self) -> StrokeStyle {
        StrokeStyle { laid_in_place: false, wet_pull: None, wet_from_before: false, ..self }
    }

    /// The style without its wet mix: dry, as the brush would be with none set (a preview that
    /// shows it dry).
    pub fn dry(self) -> StrokeStyle {
        StrokeStyle {
            wet_pull: None,
            wet_from_before: false,
            load: 0.0,
            dilution: 0.0,
            attack: 0.0,
            grade: 0.0,
            blur: 0.0,
            wet_jitter: 0.0,
            blur_jitter: 0.0,
            ..self
        }
    }

    /// Whether the stroke paints its layer as it goes: wet paint, or dry paint laid in place.
    fn paints_in_place(&self) -> bool {
        self.tool == StrokeTool::Paint && (self.wet_pull.is_some() || self.laid_in_place)
    }

    /// A plain hard round stroke.
    pub fn new(tool: StrokeTool, color: [f32; 3], opacity: f32) -> StrokeStyle {
        StrokeStyle {
            tool,
            color,
            opacity,
            hardness: 1.0,
            preserve_alpha: false,
            shape: None,
            grain: None,
            grain_mode: GrainMode::Texturized,
            grain_scale: 256.0,
            grain_depth: 0.0,
            rendering: Rendering::BuildUp,
            pull: NEW_SMUDGE_PULL,
            wet_edges: 0.0,
            burnt_edges: 0.0,
            alpha_threshold: -1.0,
            grain_brightness: 0.0,
            grain_contrast: 0.0,
            wet_pull: None,
            wet_from_before: false,
            from_image: false,
            load: 0.0,
            dilution: 0.0,
            falloff: 0.0,
            attack: 0.0,
            wetness: 1.0,
            grade: 0.0,
            blur: 0.0,
            wet_jitter: 0.0,
            blend: 0,
            blur_jitter: 0.0,
            grain_blend: MULTIPLY_MODE,
            burnt_blend: MULTIPLY_MODE,
            grain_rotation: 0.0,
            grain_sharp: false,
            full_grain: None,
            grain_side: TEXTURE_SIZE as f32,
            full_shape: None,
            tip_kind: TipKind::Mask,
            shape_sharp: false,
            shape_lod_cap: SHAPE_LOD_CAP,
            secondary: [1.0, 1.0, 1.0],
            preview: false,
            sample: false,
            luminance: false,
            laid_in_place: false,
            store: 0,
        }
    }

    /// A stroke drawn with `brush`'s shape, grain and rendering.
    pub fn for_brush(
        brush: &Brush,
        tool: StrokeTool,
        color: [f32; 3],
        opacity: f32,
        preserve_alpha: bool,
    ) -> StrokeStyle {
        let wet = brush.mixes_wet();
        StrokeStyle {
            tool,
            color,
            opacity,
            hardness: brush.hardness,
            preserve_alpha,
            shape: brush.shape.image,
            grain: brush.grain.image.filter(|_| brush.grain.depth > 0.0),
            // a grain fixed to the canvas going on stamp by stamp goes the moving grain's way, each
            // stamp reading it where it lands
            grain_mode: if brush.grain_each_stamp() { GrainMode::Moving } else { brush.grain.mode },
            grain_scale: brush.grain_tile().max(1.0),
            grain_depth: brush.grain.depth.clamp(0.0, 1.0),
            rendering: brush.rendering,
            pull: brush.setting("smudge_pull").clamp(0.0, 1.0),
            wet_edges: brush.setting("wet_edges").clamp(0.0, 1.0),
            burnt_edges: brush.setting("burnt_edges").clamp(0.0, 1.0),
            alpha_threshold: if brush.setting("alpha_threshold") >= 0.5 {
                brush.setting("alpha_threshold_amount").clamp(0.0, 1.0)
            } else {
                -1.0
            },
            grain_brightness: brush.setting("grain_brightness").clamp(-1.0, 1.0),
            grain_contrast: brush.setting("grain_contrast").clamp(-1.0, 1.0),
            // wet mix once any of its settings is set
            wet_pull: wet.then(|| brush.setting("wet_pull").clamp(0.0, 1.0)),
            wet_from_before: wet && !brush.mixes_with_stroke(),
            from_image: false,
            load: if wet { brush.setting("wet_charge").clamp(0.0, 1.0) } else { 0.0 },
            dilution: if wet { brush.setting("wet_dilution").clamp(0.0, 1.0) } else { 0.0 },
            falloff: brush.setting("falloff").clamp(0.0, 1.0),
            attack: if wet { brush.setting("wet_attack").clamp(0.0, 1.0) } else { 0.0 },
            wetness: 1.0,
            // the grade goes both ways (below 0 the stamps lay down less than their mixture)
            grade: if wet { brush.setting("wet_grade").clamp(-1.0, 1.0) } else { 0.0 },
            blur: if wet { brush.setting("wet_blur").clamp(0.0, 1.0) } else { 0.0 },
            wet_jitter: if wet { brush.setting("wet_jitter").clamp(0.0, 1.0) } else { 0.0 },
            blend: brush.setting("blend_mode").round().clamp(0.0, BRUSH_BEHIND as f32) as u32,
            blur_jitter: if wet { brush.setting("wet_blur_jitter").clamp(0.0, 1.0) } else { 0.0 },
            grain_blend: brush.setting("grain_blend").round().clamp(0.0, GRAIN_LINEAR_HEIGHT as f32) as u32,
            burnt_blend: brush.setting("burnt_blend").round().clamp(0.0, BRUSH_BEHIND as f32) as u32,
            // a brush's rotation turns its grain the other way to the shaders' angle
            // the editors' turn of the image, the whole grain with it (the rotation control is
            // each stamp's own: `Stamp::grain_turn`)
            grain_rotation: brush.setting("grain_turn").clamp(-1.0, 1.0) * std::f32::consts::PI,
            grain_sharp: brush.setting("grain_filtering") < 0.5,
            full_grain: None,
            grain_side: TEXTURE_SIZE as f32,
            full_shape: None,
            tip_kind: TipKind::Mask,
            shape_sharp: brush.setting("shape_filtering") < 0.5,
            shape_lod_cap: if brush.setting("shape_filtering").round() == 1.0 {
                SHAPE_LOD_CAP_CLASSIC
            } else {
                SHAPE_LOD_CAP
            },
            secondary: [1.0, 1.0, 1.0],
            preview: false,
            sample: false,
            luminance: brush.setting("luminance_blending") >= 0.5,
            laid_in_place: tool == StrokeTool::Paint && brush.laid_in_place(),
            store: 0,
        }
    }

    /// How far wet paint's stamps lay at the opacity slider's `opacity`.
    pub fn wetness_at(opacity: f32) -> f32 {
        1.0 - (1.0 - opacity.clamp(0.0, 1.0)).powf(2.2)
    }

    /// The grain applied to each stamp (moving), as (layer, scale, depth).
    fn moving_grain(&self) -> Option<(u32, f32, f32)> {
        self.grain.filter(|_| self.grain_mode == GrainMode::Moving).map(|g| (g, self.grain_scale, self.grain_depth))
    }

    /// The grain applied to the whole stroke, fixed to the canvas (texturized).
    fn canvas_grain(&self) -> Option<(u32, f32, f32)> {
        self.grain.filter(|_| self.grain_mode == GrainMode::Texturized).map(|g| (g, self.grain_scale, self.grain_depth))
    }
}

/// A paper a plugin gives the canvas ([`Canvas::set_paper`]): a tile, `TEXTURE_SIZE` square, each
/// byte how high the paper stands there (rows from the top), repeated over the canvas, each of its
/// pixels `scale` canvas pixels across (0.25 to 16). Every stroke's paint is taken away where the
/// paper is low, as far as `strength` says (0 to 1: a hollow takes `strength` of it); the
/// background shows `colour` (none: the artwork's own), its hollows darker as far as `depth` says
/// (0 to 1).
#[derive(Clone, Debug, PartialEq)]
pub struct CanvasPaper {
    pub tile: Vec<u8>,
    pub strength: f32,
    pub scale: f32,
    pub depth: f32,
    pub colour: Option<[f32; 3]>,
}

/// Where [`Canvas::render`] draws the canvas.
pub struct Target<'a> {
    pub view: &'a wgpu::TextureView,
    pub format: wgpu::TextureFormat,
    /// Target size in pixels.
    pub size: (u32, u32),
    /// Canvas pixels to target pixels.
    pub to_screen: Affine,
    /// Colour around the canvas, sRGB 0..1.
    pub surround: [f32; 3],
    /// Lines over that colour, if any.
    pub grid: Option<SurroundGrid>,
    /// How the selection shows, while there is one.
    pub selection: Option<SelectionLook>,
    /// Frosted panels over the target, if any.
    pub backdrop: Option<Backdrop<'a>>,
    /// The canvas's colours to the target's colour space: a matrix in linear light (none when
    /// they are the same space).
    pub convert: Option<[[f32; 3]; 3]>,
    /// Or a table taking the canvas's colours to the target's (made by [`Canvas::display_lut`]),
    /// in place of the matrix.
    pub lut: Option<&'a DisplayLut>,
    /// Zoomed in to this many target pixels to a canvas pixel or more, each canvas pixel shows as
    /// a square of its own colour; less, smoothed between them. The screen's pixels per point
    /// makes them crisp from 100%; infinity keeps them smoothed.
    pub crisp_from: f32,
    /// A matrix the canvas's colours go through as the target shows them, in linear light (rows
    /// red, green and blue: three weights and what is added), such as [`GREY_LOOK`] for its values
    /// alone: the target's view only, never what is read or recorded from the canvas. None: as
    /// they are.
    pub look: Option<ViewLook>,
}

/// A look a target shows the canvas through ([`Target::look`]): rows red, green and blue, each
/// three weights for a colour's linear red, green and blue, then what is added.
pub type ViewLook = [[f32; 4]; 3];

/// The canvas in perceptual greys: each colour as the grey of its Rec. 709 luminance (in linear
/// light, as the colour panel's HSY' luma is, so colours of one Y' show as one grey), as a soft
/// proof to a grey profile shows it, to judge its values.
pub const GREY_LOOK: ViewLook = [[0.2126, 0.7152, 0.0722, 0.0]; 3];

/// A colour table on the GPU for [`Target::lut`].
pub struct DisplayLut {
    bind: wgpu::BindGroup,
    /// Its own among the tables made while the app runs.
    id: u64,
}

/// Panels of frosted glass over the target: what is behind each region is blurred (Gaussian,
/// `sigma` target pixels) and drawn in its place, for a translucent panel to be drawn over.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Backdrop<'a> {
    /// Rounded rectangles in target pixels: left, top, right, bottom, corner radius, and how much
    /// the region shows (0..1, as its panel fades).
    pub regions: &'a [[f32; 6]],
    /// Regions likewise, drawn over those, where the glass is thin: what is behind only softens
    /// (about a tenth of `sigma`).
    pub windows: &'a [[f32; 6]],
    pub sigma: f32,
}

/// Draws a scene for [`Canvas::frost_over`] into the view given (cleared first), at the size
/// given.
pub type SmallScene<'s> = &'s mut dyn FnMut(&mut wgpu::CommandEncoder, &wgpu::TextureView, (u32, u32));

/// The backdrop's blur is this many texels wide (sigma) where it is worked out: the scene is
/// drawn that much smaller, and drawn back up as it is that's a window's softening. On a weak GPU
/// half that: a quarter of the texels, each blurred over half the taps (the blur as wide, its
/// picture coarser under it, which so wide a blur hides).
const BACKDROP_TEXELS: f32 = 5.0;
const BACKDROP_TEXELS_WEAK: f32 = 2.5;

/// The scene drawn small for frosted panels, half blurred, and blurred; and what it was drawn
/// for, while it still shows that (see [`Canvas::frost_ready`]).
struct BackdropGpu {
    size: (u32, u32),
    format: wgpu::TextureFormat,
    views: [wgpu::TextureView; 3],
    binds: [wgpu::BindGroup; 3],
    made: Option<FrostMade>,
}

/// What the glass's blurred scene was drawn for: the canvas where the target had it, its look and
/// the blur. While these stay and the composite hasn't changed near the glass, the scene is as it
/// would be drawn again, and is kept: a frame that only moves a stroke elsewhere on the canvas
/// draws no glass of its own.
#[derive(Clone, Copy, Debug, PartialEq)]
struct FrostMade {
    to_screen: Affine,
    k: f32,
    sigma: f32,
    surround: [f32; 3],
    convert: Option<[[f32; 3]; 3]>,
    /// The colour table's (see [`DisplayLut::id`]).
    lut: Option<u64>,
    look: Option<ViewLook>,
}

impl BackdropGpu {
    fn new(
        device: &wgpu::Device,
        layout: &wgpu::BindGroupLayout,
        sampler: &wgpu::Sampler,
        size: (u32, u32),
        format: wgpu::TextureFormat,
    ) -> BackdropGpu {
        let view = || {
            device
                .create_texture(&wgpu::TextureDescriptor {
                    label: Some("backdrop"),
                    size: extent(size.0, size.1),
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format,
                    usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING,
                    view_formats: &[],
                })
                .create_view(&wgpu::TextureViewDescriptor::default())
        };
        let views = [view(), view(), view()];
        let binds = views.each_ref().map(|v| sampled_bind(device, layout, v, sampler));
        BackdropGpu { size, format, views, binds, made: None }
    }
}

/// Whether a change to the composite inside `changed` (canvas pixels), seen through `to_screen`,
/// shows in any of the glass's regions or windows that show: within `reach` target pixels of one
/// (how far the blur and its sampling spread it).
fn reaches_glass(changed: IRect, to_screen: Affine, reach: f32, backdrop: Backdrop<'_>) -> bool {
    if changed.is_empty() {
        return false;
    }
    let corners =
        [(changed.x0, changed.y0), (changed.x1, changed.y0), (changed.x0, changed.y1), (changed.x1, changed.y1)]
            .map(|(x, y)| to_screen.apply(procraft_engine::geom::Vec2::new(x as f32, y as f32)));
    let (mut x0, mut y0, mut x1, mut y1) = (f32::INFINITY, f32::INFINITY, f32::NEG_INFINITY, f32::NEG_INFINITY);
    for p in corners {
        x0 = x0.min(p.x);
        y0 = y0.min(p.y);
        x1 = x1.max(p.x);
        y1 = y1.max(p.y);
    }
    // a map that can't be followed reaches everywhere
    if ![x0, y0, x1, y1, reach].iter().all(|v| v.is_finite()) {
        return true;
    }
    let (x0, y0, x1, y1) = (x0 - reach, y0 - reach, x1 + reach, y1 + reach);
    backdrop.regions.iter().chain(backdrop.windows).any(|&[rx0, ry0, rx1, ry1, _, alpha]| {
        // one that can't be read is drawn as nothing (see `draw_glass`)
        alpha > 0.0 && rx0 < x1 && x0 < rx1 && ry0 < y1 && y0 < ry1
    })
}

/// The glass [`Canvas::frost_ready`] worked out, `k` times smaller than the target of `size`, drawn
/// into `pass`: blurred into each region, and the small scene as it is into the windows.
#[allow(clippy::too_many_arguments)]
fn draw_glass(
    pass: &mut wgpu::RenderPass<'_>,
    frost: &wgpu::RenderPipeline,
    small: &BackdropGpu,
    uniform_bind: &wgpu::BindGroup,
    uniforms: &mut UniformData,
    backdrop: Backdrop<'_>,
    size: (u32, u32),
    k: f32,
) {
    let (tw, th) = (size.0.max(1) as f32, size.1.max(1) as f32);
    pass.set_pipeline(frost);
    for (bind, regions, window) in [(&small.binds[2], backdrop.regions, 0.0), (&small.binds[0], backdrop.windows, 1.0)]
    {
        pass.set_bind_group(0, bind, &[]);
        for &[x0, y0, x1, y1, corner, alpha] in regions {
            let usable = [x0, y0, x1, y1, corner, alpha].iter().all(|v| v.is_finite());
            if !usable || x1 <= x0 || y1 <= y0 || alpha <= 0.0 {
                continue;
            }
            let offset = uniforms.push(&BackdropUniform {
                v0: [x0, y0, x1, y1],
                v1: [corner, tw, th, k],
                v2: [alpha.min(1.0), window, 0.0, 0.0],
            });
            pass.set_bind_group(1, uniform_bind, &[offset]);
            pass.draw(0..6, 0..1);
        }
    }
}

/// How the selection shows over the canvas.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum SelectionLook {
    /// Bands over what is not selected.
    Stripes(Stripes),
    /// What is selected is multiplied by a colour (sRGB).
    Tint([f32; 3]),
    /// What is selected is covered by a colour (sRGB).
    Fill([f32; 3]),
}

/// Bands over the canvas outside the selection, dark and light by turns: one pair every `period`
/// target pixels (measured across), shifted `drift` pixels, at `coverage`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Stripes {
    pub period: f32,
    pub drift: f32,
    pub coverage: f32,
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct SelectionUniform {
    m0: [f32; 4],
    m1: [f32; 4],
    v: [f32; 4],
    mode: [f32; 4],
}

/// The selection on the GPU: its coverage, and that bound for drawing the stripes.
struct SelectionGpu {
    coverage: Vec<u8>,
    texture: wgpu::Texture,
    view: wgpu::TextureView,
    bind: wgpu::BindGroup,
    /// Holds every pixel it has selected since it was made (it may hold more).
    bounds: IRect,
}

/// A colour shown filling what is selected on a layer, in that layer's place among the others
/// (those above it cover it, its opacity, blend mode and mask apply), its pixels left as they are
/// (see [`Canvas::set_fill_preview`]).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FillPreview {
    pub layer: LayerId,
    /// The colour in the canvas's own colours (straight, 0..1).
    pub colour: [f32; 3],
    /// Only over the layer's paint, its alpha kept (as an alpha-locked layer is filled).
    pub alpha_lock: bool,
}

/// Grid lines around the canvas, fixed to the target: `width` pixels wide (their edges smoothed),
/// every `spacing` pixels across and down from one whose middle is at `origin`, none above `top`,
/// in `color` (sRGB 0..1).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SurroundGrid {
    pub spacing: f32,
    pub origin: [f32; 2],
    pub top: f32,
    pub width: f32,
    pub color: [f32; 3],
}

/// A small premultiplied RGBA8 image (rows top to bottom).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Thumbnail {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

/// A [`Thumbnail`] on its way back from the GPU: its copy is queued (under whatever lock guards
/// the canvas), its pixels waited for later, the lock let go ([`PendingThumbnail::pixels`]).
pub struct PendingThumbnail {
    width: u32,
    height: u32,
    read: Readback,
}

impl PendingThumbnail {
    /// The picture, once the GPU has copied it.
    pub fn pixels(self) -> Result<Thumbnail, GpuError> {
        Ok(Thumbnail { width: self.width, height: self.height, rgba: self.read.pixels()? })
    }
}

/// A layer thumbnail begun ([`Canvas::start_layer_thumbnail`]): the picture already, or the coarse
/// copy it is found in on its way back.
pub enum LayerThumbnailStep {
    Ready(Thumbnail),
    Coarse { pending: PendingThumbnail, mask: bool },
}

/// A layer thumbnail's region on its way back ([`Canvas::start_layer_thumbnail_fine`]), and what
/// to cut it to once it is.
pub struct FineThumbnail {
    pending: PendingThumbnail,
    /// Cut to what is painted once back (a mask's ink, else coverage), or left whole.
    cut_mask: Option<bool>,
}

impl FineThumbnail {
    /// The thumbnail, once the GPU has copied its region: cut to what is painted in it.
    pub fn pixels(self) -> Result<Thumbnail, GpuError> {
        let fine = self.pending.pixels()?;
        let painted = self.cut_mask.and_then(|mask| if mask { ink_bounds(&fine) } else { alpha_bounds(&fine) });
        Ok(match painted {
            Some(b) => crop(&fine, b),
            None => fine,
        })
    }
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct StampInstance {
    center: [f32; 2],
    radius: f32,
    alpha: f32,
    angle: f32,
    roundness: f32,
    grain_offset: [f32; 2],
    /// −1 where the shape is mirrored on that axis, else 1.
    flip: [f32; 2],
    /// The tint (see [`stamp_extras`]): the hue's turn, the saturation's keep and add, the
    /// brightness's keep.
    tint: [f32; 4],
    /// A moving grain's depth and tile size as multiples of the brush's (the size below 0 where
    /// the grain is read mirrored, see `Stamp::grain_mirrored`), the share of the secondary colour,
    /// and how far it fades down its shape's height (tilt gradation).
    look: [f32; 4],
    /// How hard the paint is pressed into a moving grain and how much that counts, and room for
    /// more.
    more: [f32; 4],
    /// The tint's brightness add.
    brightness_add: f32,
    /// How far the grain's patch turns (see `Stamp::grain_turn`).
    grain_turn: f32,
    /// Which shape image it is, counted on from the stroke's (see `Stamp::tip`), how strongly a
    /// lightness tip shows (`Stamp::lightness`) and its sharpness threshold (`Stamp::threshold`).
    tip: f32,
    lightness: f32,
    threshold: [f32; 2],
}

// (read as 28 floats a stamp by the shader laying stamps by their stroke's rule)
const _: () = assert!(size_of::<StampInstance>() == 28 * 4);

/// How much work stamp `s` is on a canvas `full`: the pixels it covers there, more for one
/// painted `in_place` (see [`WORK_AT_ONCE`]).
fn stamp_work(s: &StampInstance, full: IRect, in_place: bool) -> u64 {
    let at = procraft_engine::geom::Vec2::new(s.center[0], s.center[1]);
    let reach = IRect::around(at, s.radius * s.roundness.max(1.0) * std::f32::consts::SQRT_2).intersect(full);
    let pixels = u64::from(reach.width()) * u64::from(reach.height());
    if in_place { pixels * IN_PLACE_PIXEL_WORK + IN_PLACE_STAMP_WORK } else { pixels + STAMP_WORK }
}

/// A stamp's (alpha, transfer) as its instance takes them: a capped stroke keeps its transfer
/// apart for its rule (see [`Canvas::lay_by_rule`]), or (drawn "over", `capped` false) takes it
/// into the alpha. A gradual stroke's stamp keeps its own alpha where its stroke takes the rule
/// `per_pixel`, and stands in for that rule at full cover anywhere else (over the canvas).
fn capped_alpha(style: &StrokeStyle, capped: bool, s: &Stamp, per_pixel: bool) -> (f32, f32) {
    let alpha = if per_pixel { s.alpha } else { s.over_alpha() }.clamp(0.0, 1.0);
    let transfer = if s.transfer.is_finite() { s.transfer.clamp(0.0, 1.0) } else { 1.0 };
    match (style.rendering, capped) {
        (Rendering::Capped, true) => (alpha, transfer),
        (Rendering::Capped, false) => (alpha * transfer, 1.0),
        _ => (alpha, 1.0),
    }
}

/// The pixels a side of a part of the canvas stamps laid by their stroke's rule are laid in at a
/// time (see [`Canvas::lay_by_rule`]), and of each of its tiles (as the shader takes them).
const RULE_PART: u32 = 1024;
const RULE_TILE: u32 = 16;

/// The rule a stroke's stamps take pixel by pixel, as the rule shader numbers it, where drawing
/// them "over" or keeping the strongest can't follow it: building up gradually (1), or towards
/// the transfer and never past it nor back (2).
fn by_rule(rendering: Rendering) -> Option<u32> {
    match rendering {
        Rendering::Gradual => Some(1),
        Rendering::Capped => Some(2),
        Rendering::BuildUp | Rendering::Strongest => None,
    }
}

/// How far a stamp's quad reaches on the canvas: a pixel past the stamp, turned any way.
fn quad_reach(s: &StampInstance) -> IRect {
    let at = procraft_engine::geom::Vec2::new(s.center[0], s.center[1]);
    let r = s.radius.max(0.25) * s.roundness.max(1.0);
    IRect::around(at, (r + 1.0) * std::f32::consts::SQRT_2 + 1.0)
}

/// The lists of tiles the rule's passes read (see [`Canvas::lay_by_rule`]), kept from render to
/// render: each part of a render's work reads one of its own (the queue writes them all before
/// that work runs), taken in turn from `next`, each grown to the most a part has listed in it; and
/// each one's bind group, kept with what it binds. Past [`RULE_LISTS_KEPT`] a render makes the rest
/// for itself.
#[derive(Default)]
struct RuleLists {
    kept: Vec<RuleList>,
    next: usize,
    /// Whether a list (or what the rule's passes write to) that couldn't be made has been said.
    told: bool,
    /// The most words a list has held.
    largest: usize,
}

impl RuleLists {
    /// A render begins: each list is free for its work again, as many kept as are to be.
    fn begin(&mut self) {
        self.kept.truncate(RULE_LISTS_KEPT);
        self.next = 0;
    }
}

/// A list of the rule's tiles kept (see [`RuleLists`]), and its bind group with what it binds.
struct RuleList {
    buffer: wgpu::Buffer,
    bind: Option<(RuleBinds, wgpu::BindGroup)>,
}

/// What a rule pass's bind group binds besides its list (made again when any of it changes): the
/// uniforms, the frame's stamps, the stroke as it stands and where the part goes.
#[derive(Clone, PartialEq)]
struct RuleBinds {
    uniforms: wgpu::Buffer,
    stamps: wgpu::Buffer,
    target: wgpu::TextureView,
    out: wgpu::Buffer,
}

/// The most lists of the rule's tiles kept between renders (see [`RuleLists`]).
const RULE_LISTS_KEPT: usize = 8;

/// The most words a list of the rule's tiles holds (8 MB): a part of the canvas whose list would
/// hold more is laid in smaller parts (see [`rule_lists`]). A sample stroke of thousands of big
/// stamps took hundreds of MB in one, past what a phone could make.
const RULE_LIST_MOST: usize = 1 << 21;

/// The fewest bytes a list of the rule's tiles is made with.
const RULE_LIST_LEAST: u64 = 64 << 10;

/// A buffer for a list of the rule's tiles, `size` bytes.
fn rule_list_buffer(device: &wgpu::Device, size: u64) -> wgpu::Buffer {
    #[cfg(test)]
    let size = if tests::fails("stamp rule tiles") { device.limits().max_buffer_size.saturating_add(4) } else { size };
    device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("stamp rule tiles"),
        size,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    })
}

/// The lists of the rule's tiles (see [`rule_tiles`]) for part `rect` of the canvas, each with
/// the part it is for: as many as it takes for none to hold more than `most` words, a part whose
/// list would be cut in four on its tiles' edges. Every stamp over a pixel is in its part's list,
/// so a pixel comes out the same however its part is cut.
fn rule_lists(instances: &[StampInstance], first: u32, rect: IRect, rule: u32, most: usize) -> Vec<(IRect, Vec<u32>)> {
    let mut lists = Vec::new();
    let mut todo = vec![rect];
    while let Some(r) = todo.pop() {
        let Some(list) = rule_tiles(instances, first, r, rule) else { continue };
        let (wide, tall) = (r.width() > RULE_TILE, r.height() > RULE_TILE);
        if list.len() <= most || !(wide || tall) {
            lists.push((r, list));
            continue;
        }
        let half = |from: i32, n: u32| from + i32::try_from((n / 2).div_ceil(RULE_TILE) * RULE_TILE).unwrap_or(0);
        let mx = if wide { half(r.x0, r.width()) } else { r.x1 };
        let my = if tall { half(r.y0, r.height()) } else { r.y1 };
        let quarters = [
            IRect::new(mx, my, r.x1, r.y1),
            IRect::new(r.x0, my, mx, r.y1),
            IRect::new(mx, r.y0, r.x1, my),
            IRect::new(r.x0, r.y0, mx, my),
        ];
        todo.extend(quarters.into_iter().filter(|q| !q.is_empty()));
    }
    lists
}

/// What the rule shader is given for a part `part` of the canvas: its corner, tiles across and
/// the rule, then each tile's first stamp and count in the list after the table, then the list:
/// the stamps (as their places among the frame's, `first` on) whose quads reach each tile, in
/// order. None if none reach the part.
fn rule_tiles(instances: &[StampInstance], first: u32, part: IRect, rule: u32) -> Option<Vec<u32>> {
    let (across, down) = (part.width().div_ceil(RULE_TILE), part.height().div_ceil(RULE_TILE));
    let tiles = (across * down) as usize;
    if tiles == 0 {
        return None;
    }
    let tile = |v: i32, from: i32| (v - from).max(0) as u32 / RULE_TILE;
    // each stamp's tiles: first and last across and down
    let reaches: Vec<Option<[u32; 4]>> = instances
        .iter()
        .map(|s| {
            let b = quad_reach(s).intersect(part);
            let finite = s.radius.is_finite() && s.center.iter().all(|v| v.is_finite());
            (finite && !b.is_empty()).then(|| {
                let [x0, y0] = [tile(b.x0, part.x0), tile(b.y0, part.y0)];
                [x0, y0, tile(b.x1 - 1, part.x0).min(across - 1), tile(b.y1 - 1, part.y0).min(down - 1)]
            })
        })
        .collect();
    let mut counts = vec![0u32; tiles];
    for &[x0, y0, x1, y1] in reaches.iter().flatten() {
        for y in y0..=y1 {
            for x in x0..=x1 {
                if let Some(c) = counts.get_mut((y * across + x) as usize) {
                    *c += 1;
                }
            }
        }
    }
    let listed: u32 = counts.iter().sum();
    if listed == 0 {
        return None;
    }
    let head = 4 + 2 * tiles as u32;
    let mut out = Vec::with_capacity((head + listed) as usize);
    out.extend_from_slice(&[part.x0 as u32, part.y0 as u32, across, rule]);
    // each tile's first place in the list, and how many
    let mut next = vec![0u32; tiles];
    let mut at = head;
    for (i, &c) in counts.iter().enumerate() {
        out.extend_from_slice(&[at, c]);
        if let Some(n) = next.get_mut(i) {
            *n = at;
        }
        at += c;
    }
    out.resize((head + listed) as usize, 0);
    for (k, r) in reaches.iter().enumerate() {
        let Some([x0, y0, x1, y1]) = *r else { continue };
        for y in y0..=y1 {
            for x in x0..=x1 {
                let Some(n) = next.get_mut((y * across + x) as usize) else { continue };
                if let Some(slot) = out.get_mut(*n as usize) {
                    *slot = first + k as u32;
                }
                *n += 1;
            }
        }
    }
    Some(out)
}

/// How far past its size a round tip of radius `r` reaches, by its hardness: (scale, growth) for
/// its drawn radius, scale·r + growth. Measured for a hard tip (0.95: a stroke of diameter d is
/// 0.971·d + 1.87 px across) and a medium hard one (0.75: 1.043·d + 1.44 px), whose soft rim
/// builds up past the size along a stroke; between them in between, and no further either way.
/// Those were measured from 5 px across up. Below about 6 px a hard tip reaches less far, down to
/// 0.55 of it for the least tip: a shape so small is laid on a quad only 0.71 px past it, from
/// its coarsest image, so a thin line keeps close to its size (a medium hard tip's softer rim
/// already does).
fn round_tip_reach(hardness: f32, r: f32) -> (f32, f32) {
    let t = if hardness.is_finite() { ((hardness - 0.75) / 0.2).clamp(0.0, 1.0) } else { 1.0 };
    let small = 1.0 - t * (0.45 - 0.14 * r).max(0.0);
    (1.096 + (0.971 - 1.096) * t, (0.36 + (0.735 - 0.36) * t) * small)
}

/// The radius a stamp of `radius` is drawn at in `style`, thinned `taper` of the way by a taper
/// (the round tip's rim with it: measured, a taper narrows a thin brush and a wide one to the same
/// shares of their widths, so its reach goes by the stamp's size before the taper).
fn drawn_radius(style: &StrokeStyle, radius: f32, taper: f32) -> f32 {
    let r = radius.max(0.0);
    if style.shape.is_some() || r <= 0.0 {
        return r;
    }
    let taper = if taper.is_finite() { taper.clamp(0.0, 1.0) } else { 1.0 };
    let (scale, growth) = round_tip_reach(style.hardness, if taper > 0.0 { r / taper } else { r });
    r * scale + growth * taper
}

/// A stamp's moving grain's depth and tile size, its share of the secondary colour and its tilt
/// gradation, as its instance takes them: the tile size below 0 for a grain read mirrored.
fn stamp_look(s: &Stamp) -> [f32; 4] {
    let [depth, size, secondary, gradation] = [s.grain_depth, s.grain_size, s.secondary, s.gradation]
        .map(|v| if v.is_finite() { v.clamp(0.0, 4096.0) } else { 0.0 });
    let size = if s.grain_mirrored { -size.max(1e-3) } else { size };
    [depth, size, secondary, gradation]
}

/// A stamp's mirroring and colour shift for the GPU, kept finite.
/// A stamp's flips (each 1 or -1), and its tint as the shader takes it: the hue's turn, the
/// saturation's keep and add, the brightness's keep, then apart its add.
fn stamp_extras(s: &Stamp) -> ([f32; 2], [f32; 4], f32) {
    let flip = s.flip.map(|f| if f { -1.0 } else { 1.0 });
    let t = s.tint;
    let ok = |v: f32, none: f32| if v.is_finite() { v.clamp(-4.0, 4.0) } else { none };
    let [sk, sa] = t.saturation;
    let [bk, ba] = t.brightness;
    (flip, [ok(t.hue, 0.0), ok(sk, 1.0), ok(sa, 0.0), ok(bk, 1.0)], ok(ba, 0.0))
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct StampUniform {
    canvas_size: [f32; 2],
    hardness: f32,
    shape: i32,
    color: [f32; 4],
    grain: i32,
    grain_scale: f32,
    grain_depth: f32,
    grain_brightness: f32,
    grain_contrast: f32,
    grain_rotation: f32,
    /// How the grain combines with the shape (a blend mode's number).
    grain_blend: u32,
    /// 1 when the grain is read unfiltered (sharp).
    grain_sharp: u32,
    /// The secondary colour (straight sRGB), which stamps mix in as far as they say.
    secondary: [f32; 4],
    /// 1 when the shape is read unfiltered (sharp).
    shape_sharp: u32,
    /// The coarsest mip level the shape is read from.
    shape_lod_cap: f32,
    /// Pixels a side of the image a moving grain is read from.
    grain_side: f32,
    /// What its shapes are (see [`TipKind`]).
    tip_kind: u32,
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct LayerUniform {
    opacity: f32,
    stroke_mode: u32,
    stroke_opacity: f32,
    blend: u32,
    clipped: u32,
    grain: i32,
    grain_scale: f32,
    grain_depth: f32,
    solid: [f32; 4],
    wet: f32,
    burnt: f32,
    threshold: f32,
    grain_brightness: f32,
    grain_contrast: f32,
    /// 1 when the layer shows through its mask, and when the stroke being drawn is on the mask.
    masked: f32,
    stroke_on_mask: f32,
    /// How the stroke blends into the layer (a blend mode's number).
    stroke_blend: u32,
    /// An onion skin's colour, screened over the layer, and how much (none at 0).
    tint: [f32; 4],
    /// The stroke's texturized grain turned, radians, and how it and the burnt edges combine
    /// with the stroke (blend modes' numbers).
    grain_rotation: f32,
    grain_blend: u32,
    burnt_blend: u32,
    /// 1 when the grain is read unfiltered (sharp).
    grain_sharp: f32,
    /// A dual brush's second stroke: how its coverage combines with the first's (a blend mode's
    /// number + 1, with [`DUAL_CLASSIC`]; 0: no second), and where it is cut first (0..1;
    /// negative: not cut).
    dual: u32,
    dual_threshold: f32,
    /// 1 when the clip base shows through its own mask, which clipping then follows.
    clip_masked: f32,
    /// Pixels a side of the image the grain is read from.
    grain_side: f32,
    /// How many of this canvas's pixels a pixel of the canvas strokes are given in takes, and
    /// where its top left is in those (see [`Canvas::draw_scaled`]): what grain fixed to the
    /// canvas is read by.
    scaled: [f32; 4],
    /// The paper (see [`CanvasPaper`]): how strongly its hollows take the stroke's paint away (0:
    /// the stroke takes no paper), how many canvas pixels a pixel of its tile covers, and how deep
    /// it shows in the background (0: a flat colour).
    paper: [f32; 4],
}

impl Default for LayerUniform {
    fn default() -> LayerUniform {
        LayerUniform {
            opacity: 1.0,
            stroke_mode: STROKE_NONE,
            stroke_opacity: 0.0,
            blend: 0,
            clipped: 0,
            grain: -1,
            grain_scale: 1.0,
            grain_depth: 0.0,
            solid: [0.0; 4],
            wet: 0.0,
            burnt: 0.0,
            threshold: -1.0,
            grain_brightness: 0.0,
            grain_contrast: 0.0,
            masked: 0.0,
            stroke_on_mask: 0.0,
            stroke_blend: 0,
            tint: [0.0; 4],
            grain_rotation: 0.0,
            grain_blend: MULTIPLY_MODE,
            burnt_blend: MULTIPLY_MODE,
            grain_sharp: 0.0,
            dual: 0,
            dual_threshold: -1.0,
            clip_masked: 0.0,
            grain_side: TEXTURE_SIZE as f32,
            scaled: [1.0, 0.0, 0.0, 0.0],
            paper: [0.0, 1.0, 0.0, 0.0],
        }
    }
}

/// How a layer shows besides its own settings: Animation Assist's onion skins.
#[derive(Clone, Copy)]
struct Look {
    tint: [f32; 4],
    opacity: f32,
}

impl Look {
    const PLAIN: Look = Look { tint: [0.0; 4], opacity: 1.0 };
}

impl LayerUniform {
    /// With the stroke's second, if it has one (a dual brush's).
    fn with_dual(self, stroke: Option<&ActiveStroke>) -> LayerUniform {
        match stroke.and_then(|s| s.second) {
            Some(d) => LayerUniform { dual: d.uniform_mode(), dual_threshold: d.threshold, ..self },
            None => self,
        }
    }

    /// With a fill shown over what is selected, if there is one: into the layer, or into its
    /// mask when that is `fill`'s layer.
    fn with_fill(self, fill: Option<FillPreview>, mask: Option<LayerId>) -> LayerUniform {
        let Some(f) = fill else { return self };
        let [r, g, b] = f.colour.map(|c| if c.is_finite() { c.clamp(0.0, 1.0) } else { 0.0 });
        LayerUniform {
            stroke_mode: if f.alpha_lock { STROKE_FILL_LOCKED } else { STROKE_FILL },
            stroke_opacity: 1.0,
            stroke_on_mask: if Some(f.layer) == mask { 1.0 } else { 0.0 },
            solid: [r, g, b, 1.0],
            ..self
        }
    }

    /// With the paper `paper` ([`Canvas::paper_look`]) the stroke, if there is one, takes.
    fn with_paper(self, stroke: bool, paper: [f32; 4]) -> LayerUniform {
        if stroke { LayerUniform { paper, ..self } } else { self }
    }

    /// With the stroke's look: its canvas grain, if it has one, and its edges and threshold.
    fn with_grain(self, style: Option<&StrokeStyle>) -> LayerUniform {
        let Some(s) = style else { return self };
        let look = LayerUniform {
            wet: if s.preview && !s.sample { -s.wet_edges } else { s.wet_edges },
            burnt: if s.preview { 0.0 } else { s.burnt_edges },
            threshold: s.alpha_threshold,
            grain_brightness: s.grain_brightness,
            grain_contrast: s.grain_contrast,
            stroke_blend: s.blend | if s.luminance { STROKE_LUMINANCE } else { 0 },
            grain_rotation: s.grain_rotation,
            grain_blend: s.grain_blend,
            burnt_blend: s.burnt_blend,
            grain_sharp: if s.grain_sharp { 1.0 } else { 0.0 },
            grain_side: s.grain_side,
            ..self
        };
        match s.canvas_grain() {
            Some((g, scale, depth)) => LayerUniform { grain: g as i32, grain_scale: scale, grain_depth: depth, ..look },
            None => look,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct DisplayUniform {
    m0: [f32; 4],
    m1: [f32; 4],
    /// A colour matrix's rows (linear light), the first's last number 1 to apply it.
    c0: [f32; 4],
    c1: [f32; 4],
    c2: [f32; 4],
    /// How far the quad reaches past the canvas's sides (its 0..1 across and down), then 1 to
    /// smooth the edge over that; all 0 for a quad drawn exactly.
    edge: [f32; 4],
    /// The look's rows (see [`Target::look`]), applied while `m1`'s last number is 1.
    look: [[f32; 4]; 3],
}

impl DisplayUniform {
    /// Showing the canvas through `look`, if there is one.
    fn looking(mut self, look: Option<ViewLook>) -> DisplayUniform {
        if let Some(look) = look {
            self.look = look;
            self.m1[3] = 1.0;
        }
        self
    }

    /// The map `m` and target encoding `srgb`, colours moved by `convert` if there is one, or by
    /// the bound table if `lut`.
    fn new(m0: [f32; 4], m1: [f32; 4], convert: Option<[[f32; 3]; 3]>, lut: bool) -> DisplayUniform {
        let row = |r: [f32; 3], on: f32| [r[0], r[1], r[2], on];
        let table = if lut { 1.0 } else { 0.0 };
        match convert.filter(|_| !lut) {
            Some([r0, r1, r2]) => DisplayUniform {
                m0,
                m1,
                c0: row(r0, 1.0),
                c1: row(r1, 0.0),
                c2: row(r2, 0.0),
                edge: [0.0; 4],
                look: [[0.0; 4]; 3],
            },
            None => DisplayUniform {
                m0,
                m1,
                c0: [0.0; 4],
                c1: [0.0, 0.0, 0.0, table],
                c2: [0.0; 4],
                edge: [0.0; 4],
                look: [[0.0; 4]; 3],
            },
        }
    }

    /// With the canvas's edge smoothed, for the map `m` (the unit square to clip space) onto a
    /// target `size` pixels: the quad reaches a target pixel past each side, and each pixel along
    /// the edge shows as much of the canvas as covers it, not all or none (steps along a turned
    /// canvas's sides).
    fn smoothed(mut self, m: &Affine, size: (f32, f32)) -> DisplayUniform {
        let across = (m.a * size.0 / 2.0).hypot(m.b * size.1 / 2.0);
        let down = (m.c * size.0 / 2.0).hypot(m.d * size.1 / 2.0);
        if across.is_finite() && down.is_finite() && across > 0.0 && down > 0.0 {
            self.edge = [1.0 / across, 1.0 / down, 1.0, 0.0];
        }
        self
    }
}

/// The most points a veil's hole has (see [`Canvas::veil_hole`]).
pub const VEIL_POINTS: usize = 64;

/// The veil is drawn a cell at a time, this many across and down its rectangle: only the cells
/// the hole's edge passes near test their pixels against the hole; the rest are wholly veiled,
/// or wholly in the hole and not drawn (see [`VeilCells`]).
pub const VEIL_GRID: usize = 64;
/// The words the cells' kinds take, two bits a cell.
const VEIL_CELL_WORDS: usize = VEIL_GRID * VEIL_GRID / 16;

/// What the veil's shader is told: its rectangle on the canvas (left, top, width, height), the
/// colour round the canvas as the target takes it and how many points the hole has, the hole's
/// outline (two points an entry), canvas pixels, and how each cell is drawn ([`VeilCells`]).
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct VeilUniform {
    rect: [f32; 4],
    surround: [f32; 4],
    points: [[f32; 4]; VEIL_POINTS / 2],
    cells: [[u32; 4]; VEIL_CELL_WORDS / 4],
}

/// How one of a veil's cells is drawn.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VeilCell {
    /// Wholly in the hole: not drawn.
    Open = 0,
    /// Wholly veiled: drawn without looking at the hole.
    Veiled = 1,
    /// The hole's edge passes through it or near it: each pixel tested against the hole.
    Edge = 2,
}

/// How each of a veil's [`VEIL_GRID`] x [`VEIL_GRID`] cells is drawn, row by row, two bits a cell.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VeilCells([u32; VEIL_CELL_WORDS]);

impl VeilCells {
    /// The cells of a veil over `rect` (canvas pixels) with a hole outlined by `hole` (canvas
    /// pixels; fewer than three points: no hole) whose edge is smoothed over `margin` canvas
    /// pixels each way: a cell within `margin` of the outline is an edge cell; every other is
    /// wholly in the hole or out of it, as its middle is (by the crossings to its right, as the
    /// shader tells inside from outside).
    pub fn of(rect: IRect, hole: &[[f32; 2]], margin: f32) -> VeilCells {
        let mut cells = VeilCells([0; VEIL_CELL_WORDS]);
        let points = hole.get(..hole.len().min(VEIL_POINTS)).unwrap_or_default();
        let g = VEIL_GRID;
        let (x, y) = (rect.x0 as f32, rect.y0 as f32);
        let (cw, ch) = (rect.width() as f32 / g as f32, rect.height() as f32 / g as f32);
        if points.len() < 3 || cw <= 0.0 || ch <= 0.0 {
            for i in 0..g * g {
                cells.set(i, VeilCell::Veiled);
            }
            return cells;
        }
        let margin = if margin.is_nan() { f32::MAX } else { margin.max(0.0) };
        let n = points.len();
        let segment = |k: usize| (points.get(k).copied(), points.get((k + 1) % n).copied());
        // the cells each piece of the outline passes within the margin of
        let mut edge = vec![false; g * g];
        // the cells from `lo` to `hi` along one side, any of them on the veil
        let span = |lo: f32, hi: f32, origin: f32, side: f32| {
            let (a, b) = (((lo - origin) / side).floor(), ((hi - origin) / side).floor());
            (b >= 0.0 && a < g as f32).then(|| (a.max(0.0) as usize)..=(b.min(g as f32 - 1.0) as usize))
        };
        for k in 0..n {
            let (Some(a), Some(b)) = segment(k) else { continue };
            let across = span(a[0].min(b[0]) - margin, a[0].max(b[0]) + margin, x, cw);
            let down = span(a[1].min(b[1]) - margin, a[1].max(b[1]) + margin, y, ch);
            let (Some(across), Some(down)) = (across, down) else { continue };
            for cy in down {
                for cx in across.clone() {
                    let i = cy * g + cx;
                    if edge.get(i) == Some(&false) {
                        let grown = [
                            x + cx as f32 * cw - margin,
                            y + cy as f32 * ch - margin,
                            x + (cx + 1) as f32 * cw + margin,
                            y + (cy + 1) as f32 * ch + margin,
                        ];
                        if let Some(e) = edge.get_mut(i) {
                            *e = segment_meets_box(a, b, grown);
                        }
                    }
                }
            }
        }
        // the rest by where the outline crosses the middle of each row of cells
        let mut crossings: Vec<f32> = Vec::with_capacity(n);
        for cy in 0..g {
            let py = y + (cy as f32 + 0.5) * ch;
            crossings.clear();
            for k in 0..n {
                let (Some(a), Some(b)) = segment(k) else { continue };
                if (a[1] > py) != (b[1] > py) {
                    crossings.push(a[0] + (py - a[1]) / (b[1] - a[1]) * (b[0] - a[0]));
                }
            }
            for cx in 0..g {
                let i = cy * g + cx;
                let kind = if edge.get(i) == Some(&true) {
                    VeilCell::Edge
                } else {
                    let px = x + (cx as f32 + 0.5) * cw;
                    let right = crossings.iter().filter(|&&c| px < c).count();
                    if right % 2 == 1 { VeilCell::Open } else { VeilCell::Veiled }
                };
                cells.set(i, kind);
            }
        }
        cells
    }

    fn set(&mut self, i: usize, kind: VeilCell) {
        if let Some(word) = self.0.get_mut(i / 16) {
            let shift = (i % 16) * 2;
            *word = (*word & !(3 << shift)) | ((kind as u32) << shift);
        }
    }

    /// How cell (`x`, `y`) is drawn (counted from the rectangle's top left).
    pub fn get(&self, x: usize, y: usize) -> VeilCell {
        let i = y * VEIL_GRID + x;
        match self.0.get(i / 16).map_or(1, |w| (w >> ((i % 16) * 2)) & 3) {
            0 => VeilCell::Open,
            2 => VeilCell::Edge,
            _ => VeilCell::Veiled,
        }
    }

    /// The share of the cells (so of the veil's pixels) that are `kind`.
    pub fn share(&self, kind: VeilCell) -> f32 {
        let n =
            (0..VEIL_GRID).flat_map(|y| (0..VEIL_GRID).map(move |x| (x, y))).filter(|&(x, y)| self.get(x, y) == kind);
        n.count() as f32 / (VEIL_GRID * VEIL_GRID) as f32
    }

    /// As the shader reads them: four words an entry.
    fn packed(&self) -> [[u32; 4]; VEIL_CELL_WORDS / 4] {
        let mut out = [[0; 4]; VEIL_CELL_WORDS / 4];
        for (entry, words) in out.iter_mut().zip(self.0.as_chunks::<4>().0) {
            *entry = *words;
        }
        out
    }
}

/// Whether the segment from `a` to `b` meets the box (left, top, right, bottom).
fn segment_meets_box(a: [f32; 2], b: [f32; 2], r: [f32; 4]) -> bool {
    let [x0, y0, x1, y1] = r;
    if a[0].max(b[0]) < x0 || a[0].min(b[0]) > x1 || a[1].max(b[1]) < y0 || a[1].min(b[1]) > y1 {
        return false;
    }
    // the box's corners all on one side of the line: it passes by
    let side = |x: f32, y: f32| (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    let s = [side(x0, y0), side(x1, y0), side(x0, y1), side(x1, y1)];
    !(s.iter().all(|v| *v > 0.0) || s.iter().all(|v| *v < 0.0))
}

/// Part of the composite as it was before a change, drawn over the canvas but for a hole that
/// grows: the change shown spreading in from a point (see [`Canvas::veil_begin`]).
struct Veil {
    rect: IRect,
    /// Its picture, as it is shown smoothed or crisp (see [`Target::crisp_from`]).
    _texture: wgpu::Texture,
    bind: wgpu::BindGroup,
    crisp: wgpu::BindGroup,
    uniform: wgpu::Buffer,
    uniform_bind: wgpu::BindGroup,
    /// The hole this frame, canvas pixels.
    hole: Vec<[f32; 2]>,
}

/// Part of the canvas drawn again at the target's resolution, shown over it in its place (see
/// [`Canvas::set_sharp`]).
struct Sharp {
    /// Where on the canvas: left, top, width, height (canvas pixels, fractions allowed).
    rect: [f32; 4],
    _texture: wgpu::Texture,
    bind: wgpu::BindGroup,
}

/// An encoded colour moved by a linear-light matrix (clipped), as the display shader does.
fn convert_colour(c: [f32; 3], m: Option<[[f32; 3]; 3]>) -> [f32; 3] {
    let Some(m) = m else { return c };
    use procraft_engine::profile::{apply, decode, encode};
    apply(m, c.map(decode)).map(|v| encode(v.clamp(0.0, 1.0)))
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct BackdropUniform {
    v0: [f32; 4],
    v1: [f32; 4],
    v2: [f32; 4],
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct TransformUniform {
    r0: [f32; 4],
    r1: [f32; 4],
    r2: [f32; 4],
    p: [f32; 4],
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct SurroundUniform {
    v0: [f32; 4],
    v1: [f32; 4],
    v2: [f32; 4],
}

const STROKE_NONE: u32 = 0;
const STROKE_PAINT: u32 = 1;
const STROKE_ERASE: u32 = 2;
const STROKE_PAINT_LOCKED: u32 = 3;
const STROKE_LAID: u32 = 4;
/// As `STROKE_LAID`, in a stroke of 8 bits a channel: its alpha marked within it (see the shader).
const STROKE_LAID_HALVED: u32 = 5;
/// A colour filling what is selected (the selection bound as the stroke, the colour as `solid`):
/// over the layer, or with its alpha kept (see [`FillPreview`]).
const STROKE_FILL: u32 = 6;
const STROKE_FILL_LOCKED: u32 = 7;

/// How a paint layer is clipped as it composites (a layer uniform's `clipped`): not at all;
/// through the alpha of the base it is clipped to, drawn without that base (shown alone, or the
/// base not shown); in its clipping group, onto the base (the backdrop) as if that were opaque,
/// shown as much as the base is; or as that group's base, written as it is.
const CLIP_NONE: u32 = 0;
const CLIP_BY_BASE: u32 = 1;
const CLIP_ATOP: u32 = 2;
const CLIP_BASE: u32 = 3;

/// A new brush's smudge pull (see [`StrokeStyle::pull`]).
const NEW_SMUDGE_PULL: f32 = 0.75;
/// A brush's pull (wet paint's or the smudge's) to this power is how much of what it carries the
/// brush keeps at each stamp.
const PULL_POWER: f32 = 0.125;
/// A number in 0..1 that looks random, from `n`.
fn unit_hash(n: u32) -> f32 {
    let mut x = n.wrapping_mul(0x9e37_79b9) ^ 0x85eb_ca6b;
    x ^= x >> 15;
    x = x.wrapping_mul(0x2c1b_3c6d);
    x ^= x >> 12;
    (x & 0xffff) as f32 / 65535.0
}

/// What a smudge brush carries: a grid fixed to the canvas's axes, this many cells on a side, a
/// canvas pixel a cell unless the stroke's first stamp is wider than it (see the shader).
const SMUDGE_GRID: u32 = 256;
/// The most cells a stamp reaches from the grid's middle.
const SMUDGE_REACH: i32 = SMUDGE_GRID as i32 / 2 - 1;
const SMUDGE_UNIFORM_SIZE: u64 = std::mem::size_of::<SmudgeUniform>() as u64;

/// A wet stamp's (mixture, pull) once the stroke has gone `length` canvas pixels: the fresh paint
/// it mixes into what it carries is its charge (running out by the cube root of the length) and
/// what its dilution leaves ((1 - dilution)^2.4, as far as the fall off has not run it out), at
/// least its attack (the stamp's `attack` share of it: its pressure), times `jitter`; the pull is
/// how much of what it carries the brush keeps at each stamp (the brush's pull to the power 1/8).
/// The stamp's pressure scales the dilution and the charge by `dilution_by` and `charge_by` (their
/// curves).
fn wet_mixture(
    style: &StrokeStyle,
    length: f32,
    jitter: f32,
    attack: f32,
    dilution_by: f32,
    charge_by: f32,
) -> (f32, f32) {
    let charge = if style.load > 0.0 { 1.0 - run_out(1.0 - style.load, length) } else { 0.0 } * charge_by;
    let diluted =
        (1.0 - run_out(style.falloff, length)) * (1.0 - style.dilution.clamp(0.0, 1.0) * dilution_by).powf(2.4);
    let attack = style.attack.clamp(0.0, 1.0) * attack.clamp(0.0, 1.0);
    let mixture = ((charge + diluted).max(attack) * jitter).clamp(0.0, 1.0);
    (mixture, style.wet_pull.unwrap_or(0.0).clamp(0.0, 1.0).powf(PULL_POWER))
}

/// How much of what a wet stamp carries it lays over what was under its pass's first stamp: all
/// of it when it neither picks up nor mixes (a plain smudge), its mixture on the stroke's first
/// pass, else its mixture ramping up through the pass (stamp `k` of `n`) from the grade's share.
fn final_mixture(mixture: f32, pull: f32, grade: f32, (k, n): (usize, usize), first: bool) -> f32 {
    if pull * mixture <= 0.0 {
        1.0
    } else if first || n == 0 {
        mixture
    } else {
        (mixture * (grade + (1.0 - grade) * k as f32 / n as f32)).clamp(0.0, 1.0)
    }
}

/// Where a stamp comes in its stroke: when its input came (ms), how far along the path it is
/// (canvas pixels), and which of a symmetry's copies of the stroke it is on (see
/// [`Stamp::copy`]).
#[derive(Clone, Copy)]
struct Paced {
    time_ms: f64,
    distance: f32,
    copy: usize,
}

/// The pass wet paint input at `time_ms` is laid in: a display frame's input a pass, or (a brush's
/// preview, its stroke drawn all at once) each input sample's stamps a pass of their own.
fn wet_pass(time_ms: f64, preview: bool) -> i64 {
    if preview {
        return (time_ms * 1024.0).round() as i64;
    }
    (time_ms / WET_FRAME_MS).floor() as i64
}

/// The colour `c` tinted (see [`stamp_extras`]), as the stamp shader does it.
fn tinted(c: [f32; 3], [turn, keep_s, add_s, keep_b]: [f32; 4], add_b: f32) -> [f32; 3] {
    if [turn, keep_s, add_s, keep_b, add_b] == [0.0, 1.0, 0.0, 1.0, 0.0] {
        return c;
    }
    let [r, g, b] = c;
    let high = r.max(g).max(b);
    let range = high - r.min(g).min(b);
    let (hue, saturation) = if range <= 1e-5 {
        (0.0, 0.0)
    } else if high == r {
        ((g - b) / range, range / high)
    } else if high == g {
        ((b - r) / range + 2.0, range / high)
    } else {
        ((r - g) / range + 4.0, range / high)
    };
    let hue = (hue / 6.0 + turn).rem_euclid(1.0);
    let saturation = (saturation * keep_s + add_s).clamp(0.0, 1.0);
    let brightness = (high * keep_b + add_b).clamp(0.0, 1.0);
    [5.0, 3.0, 1.0].map(|n: f32| {
        let k = (hue + n / 6.0).rem_euclid(1.0) * 6.0;
        brightness - brightness * saturation * k.min(4.0 - k).clamp(0.0, 1.0)
    })
}

/// What the stamp `s` paints with: the stroke's colour as it tints it, mixed towards the secondary
/// colour as far as it says.
fn stamp_paint(s: &StampInstance, style: &StrokeStyle) -> [f32; 3] {
    let [r, g, b] = tinted(style.color, s.tint, s.brightness_add);
    let [other_r, other_g, other_b] = style.secondary;
    let towards = |from: f32, to: f32| from + (to - from) * s.look[2];
    [towards(r, other_r), towards(g, other_g), towards(b, other_b)]
}

/// A smudge stamp's uniform: what every pass reads of the stamp `s` and the stroke's `style`
/// (the rest as the pass sets it).
fn smudge_uniform(s: &StampInstance, style: &StrokeStyle, bbox: IRect, (width, height): (u32, u32)) -> SmudgeUniform {
    let moving = style.grain_mode == GrainMode::Moving;
    SmudgeUniform {
        center: s.center,
        radius: s.radius.max(0.5),
        angle: s.angle,
        roundness: s.roundness,
        alpha: (s.alpha * style.opacity).clamp(0.0, 1.0),
        pull: 1.0,
        hardness: style.hardness.clamp(0.0, 1.0),
        // each stamp its own of a set of shapes (see `Stamp::tip`)
        shape: style.shape.map_or(-1, |shape| shape as i32 + s.tip as i32),
        tip_kind: style.tip_kind as u32,
        paint_rate: 0.0,
        pick: s.center,
        dull: 0.0,
        dull_weighted: 0,
        smear_over: 0,
        passed: 0,
        pass_origin: [0; 2],
        source_image: u32::from(style.from_image),
        warp_kind: 0,
        warp_shift: [0.0; 2],
        warp_amount: 0.0,
        sharpen: 0.0,
        kept: -1,
        preserve_alpha: u32::from(style.preserve_alpha),
        // the window's, filled in where it is laid
        stride: 0,
        bbox: [bbox.x0, bbox.y0, bbox.x1, bbox.y1],
        size: [width, height],
        grid: SMUDGE_GRID,
        rule: wet_rule(style),
        paint: [style.color[0], style.color[1], style.color[2], 1.0],
        mode: 0,
        transfer: if s.more[2].is_finite() { s.more[2].clamp(0.0, 1.0) } else { 1.0 },
        final_mixture: 1.0,
        grade: style.grade,
        blur: 0.0,
        grain: style.grain.map_or(-1, |g| g as i32),
        // a moving grain sized and as deep as this stamp says
        grain_scale: if moving { -(style.grain_scale * s.look[1].abs()).max(1.0) } else { style.grain_scale },
        grain_depth: if moving { (style.grain_depth * s.look[0]).clamp(0.0, 1.0) } else { style.grain_depth },
        // only the patch turns by the stamp's own turn, where it is read from staying put: the
        // wet shader turns all it reads, so that is turned back first
        grain_offset: {
            let (sin, cos) = s.grain_turn.sin_cos();
            let [x, y] = s.grain_offset;
            [x * cos + y * sin, y * cos - x * sin]
        },
        grain_rotation: style.grain_rotation + s.grain_turn,
        grain_sharp: if style.grain_sharp { 1.0 } else { 0.0 },
        shape_sharp: if style.shape_sharp { 1.0 } else { 0.0 },
        shape_lod_cap: style.shape_lod_cap,
        grain_press: s.more[0],
        grain_bleed: s.more[1],
        grain_once: 0.0,
        anchor: s.center,
        scale: 1.0,
        half: 1,
        count: 0,
        plot: 0,
        index: 0,
        carried: 0,
        modulated: 0,
        grain_blend: style.grain_blend,
        dual: 0,
        dual_threshold: -1.0,
        laid_apart: 0,
        source_before: u32::from(style.wet_from_before),
        grain_brightness: style.grain_brightness,
        grain_contrast: style.grain_contrast,
        wet_edges: if style.preview { 0.0 } else { style.wet_edges },
        luminance: match (style.luminance && style.wet_pull.is_some(), style.wet_from_before) {
            (false, _) => 0,
            (true, false) => 1,
            (true, true) => 2,
        },
        grain_side: style.grain_side,
        burnt: if style.preview { 0.0 } else { style.burnt_edges },
        burnt_blend: style.burnt_blend,
        flip: s.flip,
        rows: 0,
        origin: [0, 0],
        part: smudge::WHOLE,
        run: [0; 4],
        grain_mirrored: u32::from(moving && s.look[1] < 0.0),
        dither: 0,
        room: [0; 2],
    }
}

/// What a stamp laid in place rounds its paint to 8 bits by (see the shader's `deposit`): the
/// how-many-th stamp of its copy of the stroke it is (`count`, from 1), the same for each of a
/// symmetry's copies of a stamp, so that their pixels round alike.
fn dither_key(count: u32) -> u32 {
    count.wrapping_mul(0x9e37_79b9)
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct SmudgeUniform {
    center: [f32; 2],
    radius: f32,
    angle: f32,
    roundness: f32,
    alpha: f32,
    pull: f32,
    hardness: f32,
    shape: i32,
    /// The cells carried over from the stamp before (those within this many of the middle; -1
    /// at the stroke's first stamp).
    kept: i32,
    preserve_alpha: u32,
    /// u32 words a row of the buffers of the window the pass works in (see `smudge.rs`).
    stride: u32,
    bbox: [i32; 4],
    size: [u32; 2],
    grid: u32,
    /// How much of wet paint shows at a pixel, by its glaze (see [`wet_rule`]).
    rule: u32,
    /// Wet mix: the paint (straight sRGB), how much of it the brush mixes into what it carries at
    /// this stamp, and how much of what it carries it lays down.
    paint: [f32; 4],
    /// 0 smudges; 1 paints wet.
    mode: u32,
    /// What light pressure lets the stamp through to, for wet paint building up towards it.
    transfer: f32,
    final_mixture: f32,
    grade: f32,
    blur: f32,
    /// The grain: a layer (-1 for none), a tile's size in pixels (negative when it moves with the
    /// stamp) and its depth.
    grain: i32,
    grain_scale: f32,
    grain_depth: f32,
    /// Where a moving grain is read from at the stamp's middle (see `Stamp::grain_offset`), and
    /// the grain turned (radians), as the stamp shader takes them.
    grain_offset: [f32; 2],
    grain_rotation: f32,
    /// 1 when the grain is read unfiltered (sharp).
    grain_sharp: f32,
    /// 1 when the shape is read unfiltered (sharp).
    shape_sharp: f32,
    /// How hard the paint is pressed into a moving grain and how much that counts (see
    /// `Stamp::grain_press`).
    grain_press: f32,
    grain_bleed: f32,
    /// 1 when the texturized grain is laid over the wet stroke once (see `grain_once`).
    grain_once: f32,
    /// Where the grid's middle cell is (a whole pixel when a cell is one), canvas pixels a cell,
    /// and the cells this stamp reaches from the middle.
    anchor: [f32; 2],
    scale: f32,
    half: i32,
    /// Wet paint (see `cs_wet_plots`): the stamps in the pass, their plots' texels a side, this
    /// stamp's place in the pass, and the texels a side of the plot the pass starts from.
    count: u32,
    plot: u32,
    index: u32,
    carried: u32,
    /// 1 when wet paint builds up by what is left at each pixel (the modulated mode).
    modulated: u32,
    /// How the moving grain combines with the shape (a blend mode), for paint laid in place.
    grain_blend: u32,
    /// A dual brush's paint laid in place (see `cs_synthesize`): its dual mode + 1 (0: none) and
    /// its second's threshold (negative: none).
    dual: u32,
    dual_threshold: f32,
    /// 1 when the stamp only adds to the paint laid so far, which `cs_synthesize` lays down; 2
    /// when it is wet paint, which keeps what it lays as well.
    laid_apart: u32,
    /// The coarsest mip level the shape is read from.
    shape_lod_cap: f32,
    /// 1 when wet paint mixes with the layer as it was before the stroke (a glaze).
    source_before: u32,
    /// The grain's levels, as the stamp shader takes them.
    grain_brightness: f32,
    grain_contrast: f32,
    /// How far paint laid in place pools, as `cs_synthesize` lays it.
    wet_edges: f32,
    /// Wet paint with luminance blending: 1 when its colours mix squared.
    luminance: u32,
    /// Pixels a side of the image the grain is read from.
    grain_side: f32,
    /// How far paint laid apart burns the paint it goes over, and by what blend mode.
    burnt: f32,
    burnt_blend: u32,
    /// −1 where the stamp's shape is mirrored on that axis, else 1.
    flip: [f32; 2],
    /// The window's rows, and the canvas pixel at its first word (with `stride`).
    rows: u32,
    origin: [i32; 2],
    /// The part of a dispatch's cells or plot texels it does (see the shader).
    part: u32,
    /// What its shapes are (see [`TipKind`]): pictures cover as far as they are opaque.
    tip_kind: u32,
    /// What the brush carries has this share of the paint mixed in (a stamp that picks up behind
    /// it: see `Stamp::pick_up`).
    paint_rate: f32,
    /// Where the grid's middle cell picks up from (the anchor, or the stroke's last stamp's).
    pick: [f32; 2],
    /// Above 0: what it carries made one colour, the average of the cells within this share of its
    /// reach of the middle; weighed by the stamp's shape (1) or not (0).
    dull: f32,
    dull_weighted: u32,
    /// 1: what it carries is laid over what is there (else in place of it, as far as it lays).
    smear_over: u32,
    /// 1: a smudge picks up from the layer as the last pass left it (see [`Canvas::lay_smudge`]),
    /// kept from canvas pixel `pass_origin` on; 0: from the layer as it is.
    passed: u32,
    pass_origin: [i32; 2],
    /// 1: it picks up from the image as last shown (see [`StrokeStyle::from_image`]).
    source_image: u32,
    /// What it picks up comes through a warp (see `procraft_engine::stroke::Warp`): its kind (0
    /// none; 16 added: the nearest pixel), its shift and its amount; and how much what it picks up
    /// is sharpened against its blur.
    warp_kind: u32,
    warp_shift: [f32; 2],
    warp_amount: f32,
    sharpen: f32,
    /// A run of a pass's stamps laid at once (see `cs_deposit_run`): its first and its end among
    /// them, as [`WetPass`] lists them (`bbox` then all of theirs).
    run: [u32; 4],
    /// 1 where a moving grain is read mirrored (see `Stamp::grain_mirrored`); which of its copy's
    /// stamps it is (see [`dither_key`]); and room for more.
    grain_mirrored: u32,
    dither: u32,
    room: [u32; 2],
}

const _: () = assert!(size_of::<SmudgeUniform>().is_multiple_of(16));

/// The coarsest mip level of our brush images (256 texels a side) a small stamp reads its shape
/// from: 8 texels a side, so that a thin tip keeps its strength.
const SHAPE_LOD_CAP: f32 = 5.0;

/// As [`SHAPE_LOD_CAP`], for classic filtering (a filtering setting of 1): 16 texels a side.
const SHAPE_LOD_CAP_CLASSIC: f32 = 4.0;

/// How far a stamp's quad reaches past the stamp each way (pixels): its shape fills all of it.
const QUAD_REACH: f32 = std::f32::consts::FRAC_1_SQRT_2;

/// Wet paint is laid a display frame's stamps at a time, the frames this long (ms).
const WET_FRAME_MS: f64 = 1000.0 / 60.0;
/// The most stamps one pass of wet paint takes (a frame with more is laid in several).
const WET_PASS_MOST: usize = 64;
/// A wet stamp's plot's most texels a side, and the texels the plots of one pass share between
/// them (a pass of many stamps takes smaller plots).
const WET_PLOT_MOST: u32 = 128;
const WET_PLOTS_TEXELS: u64 = 1024 * 1024;

/// The stamps of one pass of wet paint (see `cs_wet_plots`), each as [`wet_stamp`] lists it.
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct WetPass {
    stamps: [[f32; WET_STAMP_FLOATS]; WET_PASS_MOST],
}

/// The floats a stamp of a pass takes (as the shader's `WetStamp`).
const WET_STAMP_FLOATS: usize = 32;

/// How a pass of wet paint lists its stamp `u` (laid with `pull`, `mixture` and `final_mixture`,
/// painting with `paint`): its middle, radius and turn; roundness and those three; what it paints
/// with (each as the plots read them); and what it lays by itself, as its own uniform has it (see
/// the shader's `Dab`): its alpha, transfer, grain turn and shape; its grain's tile size and depth,
/// press and bleed; where its moving grain is read from and its mirroring; its bounds (as integers);
/// and whether its moving grain is read mirrored (1 or 0) and its dither key (as bits), with room
/// for more.
fn wet_stamp(
    u: &SmudgeUniform,
    [pull, mixture, final_mixture]: [f32; 3],
    [r, g, b]: [f32; 3],
) -> [f32; WET_STAMP_FLOATS] {
    let int = |v: i32| f32::from_bits(v.cast_unsigned());
    let [x0, y0, x1, y1] = u.bbox;
    [
        u.center[0],
        u.center[1],
        u.radius,
        u.angle,
        u.roundness,
        pull,
        mixture,
        final_mixture,
        r,
        g,
        b,
        1.0,
        u.alpha,
        u.transfer,
        u.grain_rotation,
        u.shape as f32,
        u.grain_scale,
        u.grain_depth,
        u.grain_press,
        u.grain_bleed,
        u.grain_offset[0],
        u.grain_offset[1],
        u.flip[0],
        u.flip[1],
        int(x0),
        int(y0),
        int(x1),
        int(y1),
        if u.grain_mirrored != 0 { 1.0 } else { 0.0 },
        f32::from_bits(u.dither),
        0.0,
        0.0,
    ]
}

/// `boxes` (stamps' places in their pass and bounds, in order) cut into runs laid at once (see
/// `cs_deposit_run`): each the longest whose bounds together come to little more than theirs one
/// by one (each pixel of a run goes through all its stamps). Each run's first and end, and bounds.
fn deposit_runs(boxes: &[(usize, IRect)]) -> Vec<(usize, usize, IRect)> {
    let area = |r: IRect| u64::from(r.width()) * u64::from(r.height());
    let mut runs: Vec<(usize, usize, IRect)> = Vec::new();
    let mut summed = 0u64;
    for &(k, b) in boxes {
        if b.is_empty() {
            continue;
        }
        if let Some((_, end, union)) = runs.last_mut() {
            let joined = union.union(b);
            if area(joined) <= 2 * (summed + area(b)) + RUN_SPARE {
                *end = k + 1;
                *union = joined;
                summed += area(b);
                continue;
            }
        }
        runs.push((k, k + 1, b));
        summed = area(b);
    }
    runs
}

/// The pixels a run of stamps' bounds may cover together past twice theirs (see [`deposit_runs`]).
const RUN_SPARE: u64 = 64 * 64;
const WET_PASS_SIZE: u64 = std::mem::size_of::<WetPass>() as u64;

/// A smudge stroke in progress: what the brush carries, and what the stroke keeps of its layer and
/// the buffers its compute passes work in (see `smudge.rs`).
struct Smudge {
    layer: LayerId,
    /// What it works in: the layer, or the paint of the layer's store it paints into (`store`).
    into: LayerId,
    store: Option<store::StoreStroke>,
    /// What the brush carries as the passes work in it: a buffer for the compute passes, or the
    /// textures the fragment passes keep it in (see `fragments`). Each of a symmetry's copies of the
    /// stroke carries its own (see [`Carry`]): the copy whose it holds, and what of it the copy's
    /// next stamp reads (kept when another copy's comes in).
    carry: Option<wgpu::Buffer>,
    held: Option<fragments::Held>,
    carries: Vec<Carry>,
    resident: Option<(usize, Kept)>,
    /// It paints wet: what it carries is a pass's plots and the plot carried over between passes.
    wet: bool,
    /// What it keeps besides the layer as it was: paint laid apart, the stroke as laid (see
    /// `grain_once`) and how much of the paint shows at each pixel; wet paint that builds up by
    /// what is left (the modulated mode), how much each pixel has taken so far, 0..1.
    keeps: smudge::Keeps,
    /// A dual brush's paint laid in place: laid apart (into `amount`'s second half) and put
    /// together with its second where either goes (`cs_synthesize`), with the stroke's look and
    /// its second's dual mode + 1 and threshold (the mode 0 once the second is dropped).
    apart: Option<(StrokeStyle, u32, f32)>,
    /// Wet paint: stamps waiting for the rest of their display frame (with their wet-mix scaling
    /// and input time).
    pending: Vec<(StampInstance, [f32; 8], Paced)>,
    /// A smudge: the pass being laid (its display frame, see [`wet_pass`]) and the part of the
    /// layer it reaches, kept as the last pass left it (where its stamps pick up, see
    /// [`Canvas::lay_smudge`]), from that part's corner on.
    pass: Option<(i64, IRect)>,
    passed: Option<(wgpu::Texture, wgpu::TextureView)>,
    /// The tiles of the layer it has written to, and the buffers its passes work in (or the
    /// textures, in fragment passes).
    tiles: smudge::Tiles,
    window: Option<smudge::Window>,
    frag_window: Option<fragments::FragWindow>,
    /// Whether it has laid anything yet, and whether what it takes couldn't be made since (it lays
    /// nothing more).
    laid: bool,
    stalled: bool,
    /// Where paint laid apart has been put together so far: all of it is put together once more
    /// as the stroke ends, from what the stroke and its second laid in the end.
    synthesized: IRect,
    /// Paint laid in place (not in fragment passes, nor into a store): what it keeps to go back to
    /// as it is laid again (see `rewind.rs`).
    rewind: Option<rewind::Rewind>,
    /// Paint laid apart: where what is put together may no longer be as its paint and its second
    /// now have it (put together there once more as the stroke ends), or (`stale_whole`) anywhere.
    /// Each lot of its stamps and its second's is put together where it went as it is laid.
    stale: IRect,
    stale_whole: bool,
}

/// One copy of a smudge stroke (the stroke itself, or one a symmetry draws of it): each picks up and
/// carries paint along its own path, as the stroke alone would.
#[derive(Default)]
struct Carry {
    started: bool,
    /// Canvas pixels a cell of what the brush carries (see `SMUDGE_GRID`), and the cells the stamp
    /// before reached (-1 before the first).
    scale: f32,
    kept: i32,
    /// Stamps laid so far (for the jitters), and where the last's grid was anchored.
    stamps: u32,
    last_anchor: Option<[f32; 2]>,
    /// Wet paint: the passes laid so far, and the texels a side of the last pass's plots.
    passes: u32,
    plot: u32,
    /// What it carries, put aside while another copy's is in the stroke's carry (made as it is
    /// first put aside), and whether that couldn't be made (it then carries what the others leave).
    aside: Option<Aside>,
    unkept: bool,
}

impl Carry {
    fn new() -> Carry {
        Carry { scale: 1.0, kept: -1, ..Carry::default() }
    }

    /// As it stands, without what it carries put aside.
    fn plain(&self) -> Carry {
        let Carry { started, scale, kept, stamps, last_anchor, passes, plot, aside: _, unkept } = *self;
        Carry { started, scale, kept, stamps, last_anchor, passes, plot, aside: None, unkept }
    }
}

/// Where a copy's carry is put aside (see [`Carry::aside`]): a buffer as big as what it keeps of the
/// stroke's carry buffer, or a texture as big as its grid or carried-over plot (fragment passes).
enum Aside {
    Buffer(wgpu::Buffer),
    Texture(fragments::Tex),
}

/// What of a copy's carry its next stamp reads: a smudge's cells within this many of the grid's
/// middle, or wet paint's carried-over plot (which of the two kept, and its texels), or nothing.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Kept {
    Nothing,
    Cells(i32),
    Plot { which: u32, texels: u32 },
}

/// A stamp painted in place: as the GPU draws it, its wet-mix scaling and where it comes in its
/// stroke.
type PacedStamp = (StampInstance, [f32; 8], Paced);

/// The places of the stamps `paced` tells of, those on each of a symmetry's copies of the stroke
/// together (the stroke's own first, then each copy's in turn), each in its order.
fn by_copy(paced: &[Paced]) -> Vec<usize> {
    let mut order: Vec<usize> = (0..paced.len()).collect();
    order.sort_by_key(|&k| paced.get(k).map_or(0, |p| p.copy));
    order
}

impl Smudge {
    /// A carry for each of a symmetry's copies of the stroke that the stamps `paced` tells of are on.
    fn carries_for(&mut self, paced: &[Paced]) {
        let most = paced.iter().map(|p| p.copy).max().unwrap_or(0);
        if self.carries.len() <= most {
            self.carries.resize_with(most + 1, Carry::new);
        }
    }
}

/// A hand-over of the stroke's carry before a piece of its work (see [`carry_hand_overs`]): the
/// piece's place, the copy whose carry goes aside and what of it is kept, and the copy whose comes
/// back and what of it its piece reads.
type HandOver = (usize, Option<(usize, Kept)>, (usize, Kept));

/// Where the stroke's carry changes hands as `pieces` are laid in order, `resident` the copy whose
/// carry is in it (and what of that is kept): before each piece that works in another copy's
/// carry. The hand-overs, and the copy resident after them all.
fn carry_hand_overs(
    resident: Option<(usize, Kept)>,
    pieces: &[smudge::Piece],
) -> (Vec<HandOver>, Option<(usize, Kept)>) {
    let mut resident = resident;
    let mut overs = Vec::new();
    for (k, piece) in pieces.iter().enumerate() {
        let Some((needs, leaves)) = piece.carry_use() else { continue };
        let kept = match resident {
            Some((copy, kept)) if copy == piece.carry => kept,
            // (none resident yet, or since the stroke began again: nothing to put aside, and a copy
            // that has laid nothing since reads nothing from before)
            None if needs == Kept::Nothing => needs,
            _ => {
                overs.push((k, resident, (piece.carry, needs)));
                needs
            }
        };
        resident = Some((piece.carry, leaves.unwrap_or(kept)));
    }
    (overs, resident)
}

/// How much of a wet stroke's paint shows at a pixel, by its glaze: as far as its stamps took it
/// (0), only as far as the strongest stamp over it (1, the Light Glaze), or towards each stamp's
/// transfer and never back (2, the Uniform Glaze). The paint itself is mixed stamp by stamp all
/// the same; the last two are laid apart and shown as far as their own rule built up.
fn wet_rule(style: &StrokeStyle) -> u32 {
    match (style.wet_pull.is_some(), style.rendering) {
        (true, Rendering::Strongest) => 1,
        (true, Rendering::Capped) => 2,
        _ => 0,
    }
}

/// Whether a stroke's texturized grain is laid over its wet paint once, as it ends up, rather
/// than stamp by stamp (where it would wash out as the stamps build up).
fn grain_once(style: &StrokeStyle) -> bool {
    style.paints_in_place()
        && style.grain.is_some()
        && style.grain_mode == GrainMode::Texturized
        && style.grain_depth > 0.0
}

#[derive(Clone, Copy, Debug)]
struct ActiveStroke {
    layer: LayerId,
    style: StrokeStyle,
    bounds: IRect,
    /// Which stroke ended here it is, counted as [`Canvas::strokes_ended`] (0 while it is open).
    number: u64,
    /// Where its own stamps reach, and a dual brush's second's (`bounds` is where either does).
    first: IRect,
    second_reach: IRect,
    /// Painting an adjustment on: the stamps go into the adjustment's mask, not a layer.
    pencil: bool,
    /// A dual brush's second stroke, if it has one.
    second: Option<SecondStroke>,
    /// Lays its pixels only within this (see [`Canvas::lay_within`]).
    within: Option<IRect>,
}

impl ActiveStroke {
    /// A stroke on `layer` in `style` with nothing drawn yet.
    fn new(layer: LayerId, style: StrokeStyle, pencil: bool, within: Option<IRect>) -> ActiveStroke {
        let (bounds, first, second_reach) = (IRect::EMPTY, IRect::EMPTY, IRect::EMPTY);
        ActiveStroke { layer, style, bounds, number: 0, first, second_reach, pencil, second: None, within }
    }
}

/// A dual brush's second stroke, drawn along the open stroke's path into a texture of its own
/// (in the stroke's colour): where the two meet, its coverage combines with the first's by `mode`
/// (a blend mode's number: by that blend of the two amounts; Normal: the second shows where the
/// first doesn't), cut at `threshold` first if that is 0..1. `classic`: by Normal, the colours
/// meet as the classic combine has them, the first's kept whole beside the second's share.
#[derive(Clone, Copy, Debug)]
pub struct SecondStroke {
    pub style: StrokeStyle,
    pub mode: u32,
    pub threshold: f32,
    pub classic: bool,
}

/// Set in a uniform's `dual` (over the dual mode's number + 1): the classic Normal combine.
const DUAL_CLASSIC: u32 = 1 << 16;

impl SecondStroke {
    /// The dual mode's number + 1, with [`DUAL_CLASSIC`] for the classic Normal combine.
    fn uniform_mode(&self) -> u32 {
        (self.mode + 1) | if self.classic { DUAL_CLASSIC } else { 0 }
    }
}

/// A smudge's stamps as [`Op::Stamps`] holds them: each one, its wet paint, when it was laid and
/// what it picks up.
type SmudgedStamps<'a> = (&'a [StampInstance], &'a [[f32; 8]], &'a [Paced], &'a [Option<PickUp>]);

enum Op {
    /// A plugin's filter, drawn as [`Op::FilterSet`] is, in pieces of its own.
    PluginFilter(plugin_filter::FilterValues),
    /// The open stroke's second cleared (a dual brush's stroke begins, with this second).
    ClearSecond(SecondStroke),
    /// The open stroke goes on without its second.
    DropSecond,
    /// The open stroke painting in place goes back to its mark before its stamp of this place (see
    /// [`Canvas::rewound`]).
    RewindInPlace(usize),
    /// The open stroke's second is laid from scratch next: where it reached before, what was put
    /// together of it there is put together again as the stroke ends.
    SecondReached(IRect),
    /// Stamps added to the stroke (`clear`: the stroke is redrawn from scratch).
    Stamps {
        /// Into the stroke's second (a dual brush's).
        second: bool,
        instances: Vec<StampInstance>,
        /// Each stamp's wet-mix scaling (see [`Stamp::wet`]) and where it comes in the stroke, for
        /// the wet paint drawn in place, and what a smudge stamp picks up (see [`Stamp::pick_up`]).
        wet: Vec<[f32; 8]>,
        picks: Vec<Option<PickUp>>,
        times: Vec<Paced>,
        style: StrokeStyle,
        clear: bool,
        layer: LayerId,
        /// Into the adjustment's mask instead of the stroke.
        pencil: bool,
    },
    Commit(ActiveStroke),
    /// The next stroke committed changed pixels only within these rectangles: its undo step
    /// keeps only them.
    Within(Vec<IRect>),
    Cancel,
    /// What the stroke has under this rectangle is put aside, for a tip drawn over it.
    SaveTip(IRect),
    /// What was put aside under this rectangle comes back (the tip drawn over it goes).
    Untip(IRect),
    Undo,
    Redo,
    /// A layer cleared, as one undo step keeping what was painted (all of it, or the box given
    /// when nothing is painted outside it).
    Clear(LayerId, Option<IRect>),
    /// Clears a layer without an undo step (scratch canvases).
    Wipe(LayerId),
    /// A layer's colours inverted within a rectangle (its coverage kept), as one undo step keeping
    /// that rectangle.
    Invert(LayerId, IRect),
    /// A layer filled all over with an opaque colour (RGB), as one undo step keeping the whole of
    /// it.
    Fill(LayerId, [u8; 3]),
    /// The document changed structurally; `before` is how it was.
    Record(Box<Document>),
    /// Layers were added as copies: (from, to) pairs.
    Duplicate {
        pairs: Vec<(LayerId, LayerId)>,
        before: Box<Document>,
    },
    /// `upper` is blended into `lower` and removed from the document; `within` (where `upper` is
    /// painted, if known) is all of `lower` that can change, and all its undo step keeps. Each
    /// of `masks` (a layer, its mask) is applied to its layer first, in the same step; with
    /// `drop_lower` (it was hidden), `lower`'s own pixels go first.
    MergeDown {
        upper: LayerId,
        lower: LayerId,
        before: Box<Document>,
        within: Option<IRect>,
        masks: Vec<(LayerId, LayerId)>,
        drop_lower: bool,
    },
    /// `uppers` (bottom first; groups drawn first) are blended into `lower` in turn, as one step,
    /// and removed from the document; each of `masks` (a layer, its mask) applied to its layer
    /// first, and with `drop_lower` (it was hidden) `lower`'s own pixels let go first.
    MergeMany {
        /// Where they are painted, if known: all of `lower` that can change, all the step keeps.
        within: Option<IRect>,
        uppers: Vec<LayerId>,
        lower: LayerId,
        before: Box<Document>,
        masks: Vec<(LayerId, LayerId)>,
        drop_lower: bool,
    },
    /// Layer `layer` multiplied by its mask `mask` in place; the caller removes the mask once
    /// this is queued (`before` is the document with it).
    ApplyMask {
        layer: LayerId,
        mask: LayerId,
        before: Box<Document>,
    },
    /// Group `group` of `before` is drawn into `into`, a new layer.
    Flatten {
        group: LayerId,
        into: LayerId,
        before: Box<Document>,
    },
    /// The layer is copied aside to be transformed.
    TransformBegin(LayerId),
    /// Another layer is copied aside to be transformed with the first.
    TransformAlso(LayerId),
    /// The layer is redrawn from its copy through `to_source` (layer pixels to copy pixels).
    TransformSet {
        to_source: [f32; 9],
        resample: Resample,
    },
    /// The layer is redrawn from its copy as triangles, three corners each of (where it goes,
    /// where it came from) in pixels.
    TransformMesh {
        vertices: Vec<[f32; 4]>,
        resample: Resample,
    },
    /// The next step made is named so.
    Name(String),
    /// A step that changes nothing here, named so (see [`Canvas::record_outside_change`]).
    OutsideChange(String),
    /// Where the history is now is marked.
    Mark,
    /// The steps made since the mark become one.
    Collapse,
    /// Layers mirrored, left to right (`horizontal`) or top to bottom, together as one undo step.
    Flip {
        layers: Vec<LayerId>,
        horizontal: bool,
        /// The document as it was, when that changed too.
        before: Option<Box<Document>>,
    },
    /// Layers' pixels are replaced inside a rectangle each (premultiplied RGBA8, the rectangle's
    /// rows), together as one undo step keeping those rectangles, with the document as it was
    /// (`before`) when that changed too.
    Write {
        layers: Vec<(LayerId, IRect, Vec<u8>)>,
        before: Option<Box<Document>>,
    },
    /// A layer's pixels are replaced with no undo step (during an edit held with `Hold`).
    Put(LayerId, Vec<u8>),
    /// Pixels laid over part of a layer (premultiplied RGBA8, `rect`'s rows), source over, as one
    /// undo step keeping what was there.
    Over {
        layer: LayerId,
        rect: IRect,
        /// What of it is laid (within `rect`).
        within: IRect,
        pixels: Vec<u8>,
    },
    /// A layer redrawn with no undo step from another canvas's layer (`source`, `size` pixels),
    /// each pixel from where `to_source` (3 × 3, rows) takes it there, filtered so.
    RedrawFrom {
        layer: LayerId,
        source: wgpu::TextureView,
        size: (u32, u32),
        to_source: [f32; 9],
        resample: Resample,
    },
    /// Part of a layer's pixels replaced so: the rectangle at (`x`, `y`), `width` x `height`.
    PutRegion {
        layer: LayerId,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
        pixels: Vec<u8>,
    },
    /// A layer's pixels are set aside: what an edit made of many changes goes back to.
    Hold(LayerId),
    /// While the layer held stays held, its changes make no steps of their own (its one step is
    /// made as it is released).
    HoldQuietly,
    /// The held pixels, and the document as it was (`before`), become one undo step (`keep`), or
    /// the pixels are put back.
    Release {
        keep: bool,
        before: Option<Box<Document>>,
    },
    /// The layer being edited is redrawn from its copy through an adjustment.
    FilterSet(Filter),
    /// The liquify field made, with no offsets.
    LiquifyBegin,
    /// The adjustment's painted-on mask made, empty.
    PencilBegin,
    /// A step of the liquify brush: its stamps added up, the field carried along by them.
    LiquifyStamps(Vec<LiquifyStamp>),
    /// The liquify field scaled by this much.
    LiquifyScale(f32),
    /// The liquify field back to no offsets.
    LiquifyClear,
    /// The liquify field's open step closed: what it changed kept to undo, nothing left to redo.
    LiquifyMark,
    /// The liquify field's open step taken back: the field as its last closed step left it.
    LiquifyTakeBack,
    /// The liquify field's last closed step undone, or the last undone done again.
    LiquifyUndo,
    LiquifyRedo,
    /// The liquify field set aside with its steps as its edit ends; taken up again by the edit
    /// begun next, or let go.
    LiquifyKeep,
    LiquifyResume,
    LiquifyForget,
    /// The transform ends: kept as one undo step (keeping only the part `within`, when its layers
    /// changed only there), or put back.
    TransformEnd {
        commit: bool,
        within: Option<IRect>,
    },
}

/// A layer being transformed: its pixels from before, which it is redrawn from.
struct Transforming {
    layer: LayerId,
    before: wgpu::Texture,
    bind: wgpu::BindGroup,
    /// Only what the selection takes moves.
    masked: bool,
    /// The copy alone, for an adjustment's last pass to mix with.
    before_bind: wgpu::BindGroup,
}

/// An adjustment drawn into the layer being edited (begun as a transform is), from its copy.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Filter {
    /// In luma and chroma: the chroma turned by `hue` (radians) and scaled by `saturation`, and
    /// `brightness` added to the luma.
    Hsb { hue: f32, saturation: f32, brightness: f32 },
    /// A Gaussian blur of `sigma` canvas pixels.
    Blur { sigma: f32 },
    /// Each pixel pushed away from its neighbours by `amount`.
    Sharpen { amount: f32 },
    /// Fractal noise of `kind` (0 clouds, 1 billows, 2 ridges): `amount`, `frequency` (per canvas
    /// pixel), `octaves` (1..5) and `turbulence` (how much each finer octave keeps); each channel
    /// its own when `multi`; added to the colour, or else mixed in.
    Noise { amount: f32, frequency: f32, octaves: u32, turbulence: f32, kind: u32, multi: bool, added: bool },
    /// Each pixel the mean along `along` (canvas pixels), centred on it.
    Motion { along: [f32; 2] },
    /// Each pixel streaked towards `centre` (canvas pixels) by `amount` of the way. With `toward`
    /// (a unit vector; zero for all round) only the side it points to: all of it straight along,
    /// less the further a pixel's way from the centre turns from it, none from square to it on.
    Zoom { centre: [f32; 2], amount: f32, toward: [f32; 2] },
    /// Red and blue pulled apart: from `centre` (canvas pixels) by `amount` of the distance, less
    /// within `falloff` (0..1 of the half diagonal) of it; or else (`displace`) by `offset` (canvas
    /// pixels) everywhere. `blur` (0..1) smears the fringes along the pull; `transparency` (0..1)
    /// fades the fringes beyond the picture.
    Chromatic {
        centre: [f32; 2],
        amount: f32,
        displace: bool,
        offset: [f32; 2],
        blur: f32,
        falloff: f32,
        transparency: f32,
    },
    /// A halftone of `kind` (0 full colour, 1 screen print, 2 newspaper) with cells `pitch` canvas
    /// pixels across.
    Halftone { pitch: f32, kind: u32 },
    /// The light of a blur (`sigma`) screened over the layer: `amount`, from `threshold` up over
    /// a ramp `transition` (0..1) long, strengthened by `burn` (−0.3..1).
    Bloom { sigma: f32, amount: f32, threshold: f32, transition: f32, burn: f32 },
    /// Each channel through the curves set with [`Canvas::set_table`].
    Curves,
    /// The gradient set with [`Canvas::set_table`] by brightness, `amount` of the way.
    GradientMap { amount: f32 },
    /// Each channel (red, green, blue) shifted in its shadows, midtones and highlights (−1..1).
    ColorBalance { shadows: [f32; 3], midtones: [f32; 3], highlights: [f32; 3] },
    /// The copy moved by the liquify field (begun with [`Canvas::begin_liquify`]), `amount` of it.
    Liquify { amount: f32 },
    /// The copy from `offset` (canvas pixels) away: cloning, painted on.
    Clone { offset: [f32; 2] },
    /// A glitch of `kind` 0 artifact (blocks copied from a few blocks away), 1 wave (rows bent
    /// sideways), 2 signal (bands ghosted sideways), 3 diverge (the channels moved apart in
    /// blocks), `amount` (0..1) of it. `values` are the kind's own: artifact and signal how many and
    /// the block size (0..1), wave the amplitude and frequency (0..1), diverge the red, green and
    /// blue shifts (−1..1). `zoom` (0..1) scales the pattern; `seed` varies it.
    Glitch { kind: u32, amount: f32, values: [f32; 3], zoom: f32, seed: f32 },
}

/// How a liquify stamp moves what is under it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum LiquifyMode {
    Push,
    TwirlRight,
    TwirlLeft,
    Pinch,
    Expand,
    /// Drawn in (a negative amount: out) towards its way only, narrower the nearer to 1 `narrow`.
    Lobe {
        narrow: f32,
    },
    Edge,
    Reconstruct,
}

impl LiquifyMode {
    /// The number the shader knows the mode by, and how narrow a lobe is.
    fn code(self) -> (f32, f32) {
        match self {
            LiquifyMode::Push => (0.0, 0.0),
            LiquifyMode::TwirlRight => (1.0, 0.0),
            LiquifyMode::TwirlLeft => (2.0, 0.0),
            LiquifyMode::Pinch => (3.0, 0.0),
            LiquifyMode::Expand => (4.0, 0.0),
            LiquifyMode::Lobe { narrow } => (5.0, narrow),
            LiquifyMode::Edge => (6.0, 0.0),
            LiquifyMode::Reconstruct => (7.0, 0.0),
        }
    }
}

/// One liquify stamp: where and how wide (canvas pixels), the way it goes (one long, for a push, a
/// lobe or an edge) and how much it does: for a push or an edge how far the brush moved, the rest
/// how long it worked (seconds), each times how hard it presses.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LiquifyStamp {
    pub centre: [f32; 2],
    pub way: [f32; 2],
    pub radius: f32,
    pub amount: f32,
    pub mode: LiquifyMode,
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct LiquifyUniform {
    stamp: [f32; 4],
    way: [f32; 4],
    canvas: [f32; 4],
    area: [f32; 4],
}

/// An adjustment painted on: how much of each pixel the brush has covered (the stroke format, its
/// red channel read).
struct PencilMask {
    texture: wgpu::Texture,
    view: wgpu::TextureView,
    /// Where it has been painted (canvas pixels): all the adjustment can have changed.
    bounds: IRect,
}

/// The liquify field: offsets (canvas pixels) a pixel shows the copy from, kept at half the
/// canvas's size, and that with the copy for the adjustment's last pass.
struct LiquifyField {
    texture: wgpu::Texture,
    view: wgpu::TextureView,
    bind: wgpu::BindGroup,
    /// Field pixels a canvas pixel, across and down.
    scale: [f32; 2],
    /// Where a step works, grown to the widest it has needed (in integers: see `fragments`).
    scratch: Option<LiquifyScratch>,
    /// The field as its last closed step left it, and where it has changed since (field pixels).
    marked: wgpu::Texture,
    changed: IRect,
    /// Its closed steps, oldest first: each as it was on its other side (before it, to undo; after
    /// it, to redo).
    undo: Vec<FieldStep>,
    redo: Vec<FieldStep>,
    bits: Option<fragments::LiquifyBitsScratch>,
}

/// A closed step of the liquify field: the part of the field it changed (field pixels), as that
/// was on the step's other side; nothing, if it changed nothing.
struct FieldStep {
    area: IRect,
    pixels: Option<wgpu::Texture>,
}

impl FieldStep {
    /// What it holds on the GPU, with `per_pixel` bytes a field pixel.
    fn bytes(&self, per_pixel: u64) -> u64 {
        self.pixels.as_ref().map_or(0, |_| u64::from(self.area.width()) * u64::from(self.area.height()) * per_pixel)
    }
}

/// What a step's stamps add up to and the field as they leave it, for a part of the field.
struct LiquifyScratch {
    width: u32,
    height: u32,
    sums: wgpu::TextureView,
    next: wgpu::Texture,
    next_view: wgpu::TextureView,
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct FilterUniform {
    a: [f32; 4],
    b: [f32; 4],
    size: [f32; 4],
    c: [f32; 4],
}

struct LayerGpu {
    texture: wgpu::Texture,
    view: wgpu::TextureView,
}

/// Steps (oldest first) as one: its pixels latest first, so undoing them puts back the oldest
/// last, and the document and selection from before the first that changed them.
fn collapsed(steps: Vec<Step>) -> Step {
    let made = steps.last().map_or(0, |s| s.made);
    let name = steps.iter().rev().find_map(|s| s.name.clone());
    let mut out = Step { made, name, doc: None, pixels: Vec::new(), selection: None, flip: None, stores: Vec::new() };
    for step in steps.into_iter().rev() {
        out.pixels.extend(step.pixels);
        // each layer's store as the first that changed it found it
        for (layer, store) in step.stores {
            out.stores.retain(|(l, _)| *l != layer);
            out.stores.push((layer, store));
        }
        if step.doc.is_some() {
            out.doc = step.doc;
        }
        if step.selection.is_some() {
            out.selection = step.selection;
        }
    }
    out
}

/// Pixels of a layer region from before an edit: on the GPU (the region's size, or the whole
/// layer's for the whole region), or (an older step's on a device of little memory) the region's
/// rows compressed off it until the step is undone.
struct Snapshot {
    layer: LayerId,
    rect: IRect,
    pixels: Held,
}

enum Held {
    Texture(wgpu::Texture),
    /// See [`procraft_engine::applz4`].
    Packed(Vec<u8>),
}

impl Snapshot {
    fn on_gpu(layer: LayerId, rect: IRect, texture: wgpu::Texture) -> Snapshot {
        Snapshot { layer, rect, pixels: Held::Texture(texture) }
    }

    /// Its texture, if it is on the GPU.
    fn texture(&self) -> Option<&wgpu::Texture> {
        match &self.pixels {
            Held::Texture(t) => Some(t),
            Held::Packed(_) => None,
        }
    }

    /// What it takes on the GPU.
    fn bytes(&self) -> u64 {
        match self.pixels {
            Held::Texture(_) => u64::from(self.rect.width()) * u64::from(self.rect.height()) * 4,
            Held::Packed(_) => 0,
        }
    }

    /// What it takes compressed off the GPU.
    fn packed_bytes(&self) -> u64 {
        match &self.pixels {
            Held::Texture(_) => 0,
            Held::Packed(bytes) => bytes.len() as u64,
        }
    }
}

/// A lot of the history's pixels to pack (see [`Canvas::pack_history`]): textures and the region
/// of each to read (from its top left), and where what each packs to goes (none for one that
/// couldn't be read).
type PackLot = (Vec<(wgpu::Texture, IRect)>, mpsc::Sender<Vec<Option<Vec<u8>>>>);

/// The rows read back at once as the history's pixels are packed, bytes (one row at least).
const PACK_BAND: usize = 2 << 20;

/// The thread the history's pixels are packed on, lot after lot, ending once the canvas lets go of
/// where it sends them (see [`pack_region`]). None if it couldn't be made.
fn packer(gpu: &Gpu) -> Option<mpsc::Sender<PackLot>> {
    let (send, lots) = mpsc::channel::<PackLot>();
    let gpu = gpu.clone();
    let made = std::thread::Builder::new().name("history".into()).spawn(move || {
        let mut band = None;
        while let Ok((regions, back)) = lots.recv() {
            let packed = regions.iter().map(|(texture, rect)| pack_region(&gpu, &mut band, texture, *rect)).collect();
            let _ = back.send(packed);
        }
    });
    match made {
        Ok(_) => Some(send),
        Err(e) => {
            log::warn!("the history's pixels can't be packed: {e}");
            None
        }
    }
}

/// `rect` of `texture` compressed (see [`procraft_engine::applz4`]), read back a band of rows at a
/// time through `band` (made, or made bigger, as wanted): neither the whole region nor a buffer
/// its size is ever held (buffers the CPU reads, each of another size, stayed in the process's
/// memory as it went). None if it couldn't be read.
fn pack_region(gpu: &Gpu, band: &mut Option<wgpu::Buffer>, texture: &wgpu::Texture, rect: IRect) -> Option<Vec<u8>> {
    let (width, height) = (rect.width(), rect.height());
    let row = width as usize * 4;
    let padded = row.div_ceil(256) * 256;
    let rows = u32::try_from(PACK_BAND / padded.max(1)).unwrap_or(u32::MAX).clamp(1, height.max(1));
    let size = padded as u64 * u64::from(rows);
    if band.as_ref().is_none_or(|b| b.size() < size) {
        *band = Some(gpu.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("history band"),
            size,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        }));
    }
    let buffer = band.as_ref()?;
    let read = || -> Option<Vec<u8>> {
        let mut packer = procraft_engine::applz4::Packer::default();
        let mut y = 0;
        while y < height {
            let n = rows.min(height - y);
            // a copy that fails (a texture there was no memory for) leaves what the band held
            let invalid = gpu.device.push_error_scope(wgpu::ErrorFilter::Validation);
            let mut encoder =
                gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("history band") });
            gpu.copy_texture_to_buffer(
                &mut encoder,
                copy_at(texture, rect.x0, rect.y0 + y as i32),
                wgpu::TexelCopyBufferInfo {
                    buffer,
                    layout: wgpu::TexelCopyBufferLayout {
                        offset: 0,
                        bytes_per_row: Some(padded as u32),
                        rows_per_image: Some(n),
                    },
                },
                extent(width, n),
            );
            gpu.queue.submit([encoder.finish()]);
            if pollster::block_on(invalid.pop()).is_some() {
                return None;
            }
            let used = padded as u64 * u64::from(n);
            let (sent, mapped) = mpsc::channel();
            buffer.slice(..used).map_async(wgpu::MapMode::Read, move |r| {
                let _ = sent.send(r);
            });
            gpu.wait().ok()?;
            mapped.recv().ok()?.ok()?;
            {
                let view = buffer.slice(..used).get_mapped_range().ok()?;
                for chunk in view.chunks(padded.max(1)) {
                    packer.push(chunk.get(..row).unwrap_or(chunk));
                }
            }
            buffer.unmap();
            y += n;
        }
        Some(packer.finish())
    };
    let packed = read();
    // (one left mapped, or holding what a failed copy left, is made again)
    if packed.is_none() {
        *band = None;
    }
    packed
}

/// Undo steps' pixels being read back to be kept compressed (see [`Canvas::pack_history`]): the
/// textures read, in order, and each one's rows compressed (none if it couldn't be read), sent
/// back from the thread that does it. What comes back is taken only while the history's
/// textures still hold what was read (`generation`).
struct Packing {
    textures: Vec<wgpu::Texture>,
    generation: u64,
    done: mpsc::Receiver<Vec<Option<Vec<u8>>>>,
}

/// One undoable step: the document as it was (for structural changes) and the pixels it changed.
struct Step {
    /// Which step it was ([`Canvas::steps_made`] once it was made); kept through undo and redo.
    made: u64,
    /// What it did, as undo and redo name it ("Paint stroke"), if it was named.
    name: Option<String>,
    doc: Option<Box<Document>>,
    pixels: Vec<Snapshot>,
    /// The selection to put back (its coverage, or none for nothing selected), when the step
    /// changed it.
    selection: Option<Option<Vec<u8>>>,
    /// Layers flipped (across, or down): undone and redone by flipping them again, nothing kept.
    flip: Option<(Vec<LayerId>, bool)>,
    /// The stores of the layers it made, dried or changed, as each was (none: it had none).
    stores: Vec<(LayerId, Option<LayerStore>)>,
}

impl Step {
    /// What it holds: its pixels on the GPU, and the selection it puts back (a byte a canvas
    /// pixel), so many selection changes can't outgrow the history's budget either.
    fn bytes(&self) -> u64 {
        let selection = self.selection.as_ref().and_then(Option::as_ref).map_or(0, |s| s.len() as u64);
        self.pixels.iter().map(Snapshot::bytes).sum::<u64>() + selection
    }

    /// What its pixels kept compressed off the GPU take.
    fn packed_bytes(&self) -> u64 {
        self.pixels.iter().map(Snapshot::packed_bytes).sum()
    }
}

/// What the canvases' pipelines are made for: what the GPU can do past the least (an old GPU's
/// OpenGL ES 3.1 can't draw into half floats, nor run compute passes in storage buffers, on many).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Abilities {
    /// What strokes build up in: half floats, or where the GPU can't draw into them 8 bits a
    /// channel (faint stamps building up band, and the faintest lay nothing).
    pub(crate) stroke_format: wgpu::TextureFormat,
    /// The format liquify's field takes here, where liquify can run (it adds its stamps up in half
    /// floats, and the field's offsets are floats).
    pub(crate) liquify_format: Option<wgpu::TextureFormat>,
    /// Whether the compute passes run (see [`Gpu::computes`]): without them nothing smudges, wet
    /// paint and paint laid in place are laid dry, and no stroke is laid by its rule.
    pub(crate) computes: bool,
}

impl Abilities {
    pub(crate) fn of(gpu: &Gpu) -> Abilities {
        let floats = gpu.draws_half_floats();
        Abilities {
            stroke_format: if floats { STROKE_FORMAT } else { LAYER_FORMAT },
            liquify_format: floats.then(|| liquify_format(&gpu.adapter)),
            computes: gpu.computes(),
        }
    }
}

/// The compute passes that work in storage buffers: a smudge's and wet paint's (see `smudge`),
/// and stamps laid by their stroke's rule pixel by pixel (see `Canvas::lay_by_rule`), each with
/// what it is given.
pub(crate) struct ComputePipelines {
    stamp_rule: wgpu::ComputePipeline,
    rule_layout: wgpu::BindGroupLayout,
    smudge_layout: wgpu::BindGroupLayout,
    smudge_carry: wgpu::ComputePipeline,
    smudge_dull: wgpu::ComputePipeline,
    smudge_blur_down: wgpu::ComputePipeline,
    smudge_blur_across: wgpu::ComputePipeline,
    smudge_deposit: wgpu::ComputePipeline,
    smudge_deposit_run: wgpu::ComputePipeline,
    smudge_synthesize: wgpu::ComputePipeline,
    smudge_wet_plots: wgpu::ComputePipeline,
}

/// Liquify's stamps added up and the field carried along by them, the format the field takes
/// here, and what that second pass reads.
pub(crate) struct LiquifyPipelines {
    stamp: wgpu::RenderPipeline,
    step: wgpu::RenderPipeline,
    format: wgpu::TextureFormat,
    layout: wgpu::BindGroupLayout,
}

/// The pipelines every canvas on a device draws with, made once for the device (see
/// [`crate::Gpu`]): compiling them takes a tablet a second and a half.
pub(crate) struct Pipelines {
    /// A 3D colour table and its sampler, for the display.
    lut_layout: wgpu::BindGroupLayout,
    /// The display's: the canvas, the uniforms and the colour table.
    display_lut_layout: wgpu::PipelineLayout,
    quad_layout: wgpu::BindGroupLayout,
    brush_layout: wgpu::BindGroupLayout,
    sampled_layout: wgpu::BindGroupLayout,
    uniform_layout: wgpu::BindGroupLayout,
    stamp: wgpu::RenderPipeline,
    stamp_max: wgpu::RenderPipeline,
    /// What strokes build up in here (see [`Abilities::stroke_format`]).
    stroke_format: wgpu::TextureFormat,
    /// The compute passes, where the GPU runs them; else the fragment passes in their place.
    compute: Option<ComputePipelines>,
    fragments: Option<fragments::FragmentPipelines>,
    layer: wgpu::RenderPipeline,
    /// A normal layer clipped to its base, drawn onto it in their clipping group.
    layer_atop: wgpu::RenderPipeline,
    blend: wgpu::RenderPipeline,
    solid: wgpu::RenderPipeline,
    commit_paint: wgpu::RenderPipeline,
    commit_erase: wgpu::RenderPipeline,
    commit_locked: wgpu::RenderPipeline,
    thumb: wgpu::RenderPipeline,
    transform: wgpu::RenderPipeline,
    mesh: wgpu::RenderPipeline,
    /// What a transform leaves where it was (with a selection).
    transform_static: wgpu::RenderPipeline,
    transform_layout: wgpu::BindGroupLayout,
    /// Where a layer is painted, across its rows and down its columns; and where it differs from
    /// another.
    bounds_layout: wgpu::BindGroupLayout,
    bounds_rows: wgpu::RenderPipeline,
    bounds_columns: wgpu::RenderPipeline,
    changed_layout: wgpu::BindGroupLayout,
    changed_rows: wgpu::RenderPipeline,
    changed_columns: wgpu::RenderPipeline,
    /// A layer mirrored left to right, and top to bottom.
    flip_across: wgpu::RenderPipeline,
    flip_down: wgpu::RenderPipeline,
    invert: wgpu::RenderPipeline,
    /// A layer of the canvas before a crop, seen through it.
    crop_layout: wgpu::BindGroupLayout,
    crop: wgpu::RenderPipeline,
    /// Adjustments: the copy alone, and the passes.
    before_layout: wgpu::BindGroupLayout,
    /// Adjustment passes by their fragment entry.
    filters: HashMap<&'static str, wgpu::RenderPipeline>,
    /// What every adjustment's passes are laid out by, a plugin's filter too.
    filter_layout: wgpu::PipelineLayout,
    /// Liquify's, where the GPU draws into floats.
    liquify: Option<LiquifyPipelines>,
    selection_module: wgpu::ShaderModule,
    display_module: wgpu::ShaderModule,
    display_pipeline_layout: wgpu::PipelineLayout,
    surround_module: wgpu::ShaderModule,
    surround_pipeline_layout: wgpu::PipelineLayout,
    stamp_layout: wgpu::PipelineLayout,
    overlay_module: wgpu::ShaderModule,
    /// Frosted panels: the blur passes and the regions drawn.
    backdrop_module: wgpu::ShaderModule,
    /// A veil over the canvas (see [`Canvas::veil_begin`]): its uniform, and the display's
    /// groups with it.
    veil_layout: wgpu::BindGroupLayout,
    veil_pipeline_layout: wgpu::PipelineLayout,
    /// The pipeline cache they are made with, kept between runs (see `kept`): the screen's
    /// pipelines, made as a window first shows a canvas, as well.
    cache: Option<wgpu::PipelineCache>,
    /// A layer's store's, made the first time a stroke paints into one.
    store: OnceLock<store::StorePipelines>,
}

/// A canvas's own pipelines for drawing to a window, made for each target format as it comes.
#[derive(Default)]
struct ScreenPipelines {
    display: HashMap<wgpu::TextureFormat, wgpu::RenderPipeline>,
    surround: HashMap<wgpu::TextureFormat, wgpu::RenderPipeline>,
    overlay: HashMap<wgpu::TextureFormat, wgpu::RenderPipeline>,
    /// The selection's look, per target format and whether it multiplies.
    selection: HashMap<(wgpu::TextureFormat, bool), wgpu::RenderPipeline>,
    blur: HashMap<wgpu::TextureFormat, wgpu::RenderPipeline>,
    frost: HashMap<wgpu::TextureFormat, wgpu::RenderPipeline>,
    veil: HashMap<wgpu::TextureFormat, wgpu::RenderPipeline>,
}

pub struct Canvas {
    /// Its own among the canvases made while the app runs.
    id: u64,
    width: u32,
    height: u32,
    pipelines: std::sync::Arc<Pipelines>,
    screen: ScreenPipelines,
    layers: HashMap<LayerId, LayerGpu>,
    stroke: wgpu::Texture,
    stroke_view: wgpu::TextureView,
    /// Where the open stroke's tip is drawn (see [`Canvas::set_tip`]), and what it covered.
    tip: Option<IRect>,
    tip_backup: Option<wgpu::Texture>,
    /// Where stamps laid by their stroke's rule go before their part of the stroke takes them
    /// (see [`Canvas::lay_by_rule`]), made when first wanted.
    rule_out: Option<wgpu::Buffer>,
    /// The lists of tiles the rule's passes read (see [`RuleLists`]), and the most words one may
    /// hold (see [`RULE_LIST_MOST`]).
    rule_lists: RuleLists,
    rule_list_most: usize,
    /// Work was left out since last asked, for want of what it takes on the GPU.
    work_left_out: bool,
    /// What the fragment passes keep, on a GPU that has them in place of the compute passes.
    fragments: fragments::Fragments,
    /// The finished composite, and the other half of its ping-pong pair: where a layer of a
    /// blend mode other than Normal goes, made the first time one does.
    composite: wgpu::Texture,
    composite_view: wgpu::TextureView,
    composite_second: OnDemand,
    /// Where a group's layers are composited before the group goes onto the stack below: a pair
    /// for each depth of group (made when first needed).
    groups: Vec<([wgpu::Texture; 2], [wgpu::TextureView; 2])>,
    /// Where a clipping group (a layer and those clipped to it) is composited before it goes onto
    /// the stack, and the other half of its pair, for a layer of a blend mode in it; each made the
    /// first time it is wanted. One pair serves every depth: a clipping group holds no group.
    clip_group: OnDemand,
    clip_group_second: OnDemand,
    /// Animation Assist playing: the frame shown (alone, no onion skins).
    play_frame: Option<usize>,
    /// Where a stroke of a blend mode, or one on a locked layer, and a merge are drawn before
    /// they go back into the layer; made the first time one is.
    scratch: OnDemand,
    /// A dual brush's second stroke (see [`SecondStroke`]), made when one is first drawn.
    second_stroke: OnDemand,
    /// The second of the stroke being drawn from the ops (told by [`Op::ClearSecond`]), until it
    /// ends.
    queued_second: Option<SecondStroke>,
    /// A blur's working textures by size and part, kept from one step of an adjustment to the
    /// next (made each step, those the canvas's size were most of a narrow blur's cost on a
    /// tablet); let go when the adjustment ends.
    blur_textures: Mutex<HashMap<(u32, u32, u8), wgpu::TextureView>>,
    /// The copy a transform or adjustment kept of its layer, when it ended without it (let go,
    /// or kept only in part): the next one copies into it.
    spare_before: Option<wgpu::Texture>,
    /// Textures the canvas's size let go (an undo step's, redo cleared by a new step or the
    /// oldest dropped; a layer's nothing refers to any more), a few kept for the next new layer or
    /// step that keeps the whole of a layer: making one takes a tablet 15-25 ms.
    spares: Mutex<Vec<wgpu::Texture>>,
    /// Buffers pixels go up to the layers through, kept mapped for the next upload once the GPU
    /// is done with them (see [`Uploads`]).
    uploads: Uploads,
    sampler: wgpu::Sampler,
    grains_view: wgpu::TextureView,
    grain_sampler: wgpu::Sampler,
    /// The paper's tile (see [`Canvas::set_paper`]), which every stroke takes and the background
    /// shows while there is a paper: all peak (taking all of the paint) until a paper is given.
    paper_texture: wgpu::Texture,
    paper_view: wgpu::TextureView,
    /// The paper as it is: none, or its tile as given and how it is taken.
    paper: Option<CanvasPaper>,
    shapes_view: wgpu::TextureView,
    shape_sampler: wgpu::Sampler,
    /// The shape and grain arrays, and which images of the user's own their spare layers hold;
    /// and whether they hold the images yet (else one-texel stand-ins, for a canvas that takes
    /// only drawn strokes until something wants them).
    shapes: wgpu::Texture,
    grains: wgpu::Texture,
    brush_images: bool,
    /// The open stroke's grain fixed to the canvas, from a picture its size in place of a brush
    /// image (see [`Canvas::set_stroke_grain`]); kept until the stroke has ended.
    stroke_grain: Option<wgpu::TextureView>,
    /// Brush files' grains at their own size, and the one the stroke's bind reads.
    full_grains: FullGrains,
    full_shapes: FullGrains,
    bound_full_grain: Option<u64>,
    shape_sources: SourceSlots,
    grain_sources: SourceSlots,
    /// Full coverage, stood in for the selection when there is none.
    everything: wgpu::TextureView,
    /// Where an adjustment is painted on (Pencil), instead of everywhere the selection takes.
    pencil: Option<PencilMask>,
    liquify: Option<LiquifyField>,
    /// A liquify field set aside as its edit ended (see [`Canvas::keep_liquify`]).
    kept_liquify: Option<LiquifyField>,
    /// An adjustment leaves the layer's alpha as it is (the layer's Alpha Lock).
    filter_keeps_alpha: bool,
    /// The curves or gradient adjustments read (256 RGBA8 entries).
    table: wgpu::Texture,
    table_view: wgpu::TextureView,
    selection: Option<SelectionGpu>,
    /// Shapes, grains and the selection for the stamp shader.
    brush_bind: wgpu::BindGroup,
    /// Every texture slot bound to the stroke texture: for draws that only read the stroke.
    stroke_bind: wgpu::BindGroup,
    /// The picture as shown: smoothed between its pixels, or each a square ([`Target::crisp_from`]).
    display_bind: wgpu::BindGroup,
    crisp_bind: wgpu::BindGroup,
    /// The table that leaves colours as they are, bound when a target has none.
    identity_lut: wgpu::BindGroup,
    uniform_buffer: wgpu::Buffer,
    uniform_bind: wgpu::BindGroup,
    uniform_slots: u64,
    stamp_buffer: wgpu::Buffer,
    stamp_capacity: u64,
    /// The most stamps' work sent to the GPU as one piece (see [`WORK_AT_ONCE`]).
    work_at_once: u64,
    /// The most stamps one render uploads (see [`STAMPS_AT_ONCE`]).
    stamps_at_once: u64,
    /// Pieces of work sent early, there being too much for one.
    pieces_sent: u64,
    /// Each piece waited for on the GPU before the next is sent, and pieces of no more than
    /// [`PACED_WORK`] (see [`Canvas::pace`]); and the longest one took since last asked.
    paced: bool,
    paced_longest: std::time::Duration,
    /// Compute dispatches recorded for smudges, paint laid in place and stamps laid by their rule.
    dispatched: AtomicU64,
    /// Wet paint and paint laid in place go down a stamp a dispatch, as they did before runs of
    /// stamps were laid at once (see [`Canvas::lay_stamp_by_stamp`]).
    stamp_by_stamp: bool,
    /// Strokes painting in place keep marks to go back to as they are laid again (see `rewind.rs`),
    /// within this many bytes (none: as the device's memory gives them); and the marks made and
    /// gone back to so far.
    rewinds: bool,
    rewind_most: Option<u64>,
    marks_made: u64,
    rewound: u64,
    /// Whether the open stroke's end may lay again what it has laid (see [`Canvas::may_lay_again`]).
    may_lay_again: bool,
    /// Stamps queued to be laid so far, for tests.
    stamps_queued: u64,
    /// Where a dual brush's second was laid before its paint laid in place began (nothing to put
    /// together with yet), or reached before it was laid from scratch: put together there as the
    /// stroke ends.
    second_unsynthesized: IRect,
    /// How long a render that shows the canvas lays a stroke laid again as it ends (see
    /// [`RELAY_AT_ONCE`]), and whether one is being laid over several (the picture held meanwhile).
    relay_at_once: std::time::Duration,
    relaying: bool,
    /// Paint laid in place left for the next render (see [`IN_PLACE_FRAME_MS`]); the last render
    /// that laid such work, as sent (its submission, when, and how much), until it is seen done; and
    /// how fast the GPU gets through such work, as seen (work a millisecond, at least).
    catching_up: bool,
    in_place_sent: Option<(wgpu::SubmissionIndex, std::time::Instant, u64)>,
    in_place_rate: Option<f64>,
    in_place_paced: bool,
    /// How the last plugin filter's draw went, not yet asked for.
    filter_run: Option<plugin_filter::FilterRun>,
    /// How many times the composite has been redrawn.
    composites: u64,
    active: Option<ActiveStroke>,
    smudge: Option<Smudge>,
    /// The open smudge stroke couldn't be begun (what it takes couldn't be made): it does nothing
    /// until it ends. And whether that (or that it couldn't go on) is still to be told.
    smudge_refused: bool,
    smudge_untold: bool,
    /// A layer's pixels set aside by [`Canvas::hold_layer`], and whether its changes meanwhile make
    /// no steps of their own ([`Canvas::hold_layer_quietly`]).
    held: Option<Snapshot>,
    held_quietly: bool,
    /// Part of the composite kept as it was, drawn over the canvas but for a hole (see
    /// [`Canvas::veil_begin`]).
    veil: Option<Veil>,
    /// A colour shown filling what is selected on a layer (see [`Canvas::set_fill_preview`]).
    fill_preview: Option<FillPreview>,
    /// Counts the undo steps ever made, so that a change since some moment shows.
    steps_made: u64,
    /// The steps made when [`Canvas::mark_steps`] was last done.
    mark: Option<u64>,
    /// The name the next step made takes.
    next_name: Option<String>,
    /// Where the next stroke committed changes pixels, if known more closely than its bounds.
    within: Option<Vec<IRect>>,
    /// Where strokes begun from now on lay their pixels, when only part of a layer is laid again
    /// (see [`Canvas::lay_within`]).
    lay_within: Option<IRect>,
    /// Strokes begun from now on are only measured (see [`Canvas::measure`]).
    measuring: bool,
    /// Where the stroke ended last reached, and whether it changed its layer as it went.
    last_ended: Option<(IRect, bool)>,
    /// Strokes are given in another canvas's pixels, drawn here this many times as big, from this
    /// point of those at the top left (see [`Canvas::draw_scaled`]).
    scaled: Option<([f32; 2], f32)>,
    /// Part of the canvas drawn again sharper, shown over it in its place.
    sharp: Option<Sharp>,
    smudge_params: wgpu::Buffer,
    /// The slots it holds (a smudge stamp's uniform takes two).
    smudge_capacity: u64,
    /// The most a smudge stroke's window may take, bytes (see `smudge.rs`): what a buffer and a
    /// binding may hold.
    smudge_most: u64,
    /// The most a window laid in buffers may take, bytes: what a buffer and a binding may hold
    /// (see `Canvas::smudge_room_for`); no limit in fragment passes, which lay none.
    smudge_room: u64,
    /// The passes of wet paint a render lays (see [`WetPass`]), and how many it holds.
    wet_params: wgpu::Buffer,
    wet_capacity: u64,
    /// Stand-ins for a smudge's grain buffers when its stroke has none.
    smudge_spare: [wgpu::Buffer; 2],
    /// Stamps of input that hasn't happened yet (predicted), drawn over the canvas for one frame.
    prediction: Vec<StampInstance>,
    /// The open stroke's stamps as laid so far, and a dual brush's second's, so that one redrawn
    /// whole with them still at its start lays only what comes after (see
    /// [`Canvas::replace_stamps`]); none past [`LAID_KEPT_MOST`].
    laid: Option<Vec<Stamp>>,
    laid_second: Option<Vec<Stamp>>,
    /// How many of them there are, however many (see [`Canvas::laid_count`]).
    laid_count: usize,
    laid_second_count: usize,
    /// A stroke redrawn whole is laid whole again, whatever it has laid (see
    /// [`Canvas::lay_ends_whole`]).
    ends_whole: bool,
    /// How many strokes have ended here, and the last put into its layer: its number and the
    /// undo step it made (see [`Canvas::step_of_stroke`]).
    strokes_ended: u64,
    stroke_step: Option<(u64, u64)>,
    ops: Vec<Op>,
    dirty: IRect,
    /// Whether the composite is made around pictures of what isn't being worked on, and them.
    cache_composites: bool,
    caches: Option<Caches>,
    /// Caches being remade a little each render (more layers than fit), and the composite left
    /// as it was until they are ready.
    remake: Option<Remake>,
    /// The textures of caches let go, kept to make the next ones in: four the canvas's size are
    /// slow to make on a tablet (a layer added or deleted, a duplicate, a merge each made them).
    spare_caches: Option<Caches>,
    /// Layers' pixels kept compressed off the GPU: those parked (no texture), and copies of
    /// resident ones unchanged since, so that letting them go again costs nothing.
    parked: HashMap<LayerId, Parked>,
    /// Small pictures of the parked layers, so their thumbnails need no texture.
    pictures: HashMap<LayerId, Pictures>,
    /// Layers whose pixels changed since [`Canvas::take_changed`] took them, so a save reads only
    /// those.
    changed: HashSet<LayerId>,
    /// Each layer's pixels' version, from a count that goes up with every change: thumbnails are
    /// read again only when theirs has.
    versions: HashMap<LayerId, u64>,
    version: u64,
    /// Layers past the budget are still to be parked (a render parks only a few).
    parking_left: bool,
    /// How the last remake of the caches went.
    last_remake: Option<RemakeStats>,
    /// What the layers' textures may take before those needed least lately are parked (0: none
    /// are), and when each was last needed, counted in renders.
    resident_budget: u64,
    needed_at: HashMap<LayerId, u64>,
    renders: u64,
    undo: Vec<Step>,
    redo: Vec<Step>,
    /// The history's older pixels being packed (see [`Canvas::pack_history`]), and a count that
    /// goes up whenever its textures' pixels change (a step undone or redone).
    packing: Option<Packing>,
    history_generation: u64,
    /// Where lots to pack are sent (see [`packer`]), made when first wanted.
    packer: Option<mpsc::Sender<PackLot>>,
    /// The bytes of newest steps kept on the GPU, set by tests (else see [`RECENT_HISTORY_SHARE`]).
    recent_history: Option<u64>,
    transforming: Option<Transforming>,
    /// Other layers transformed along with it (layers picked together, the planes of their stores).
    transform_more: Vec<Transforming>,
    /// What layers keep for the brushes that paint into them (see [`LayerStore`]), and the planes
    /// numbered so far.
    stores: HashMap<LayerId, LayerStore>,
    next_plane: u64,
    /// For frosted panels, made when first needed, and how many texels wide their blur is where
    /// it is worked out (see [`BACKDROP_TEXELS`]).
    backdrop: Option<BackdropGpu>,
    backdrop_texels: f32,
    /// Where the composite changed since the glass's scene was drawn (canvas pixels), and how many
    /// times it has been drawn.
    frost_stale: IRect,
    frosts: u64,
}

const PREMULTIPLIED_OVER: wgpu::BlendState = wgpu::BlendState {
    color: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::One,
        dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
        operation: wgpu::BlendOperation::Add,
    },
    alpha: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::One,
        dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
        operation: wgpu::BlendOperation::Add,
    },
};

/// The larger of the two: stamps that don't build up (uniform glaze).
/// The target multiplied by what is drawn (its alpha kept).
const MULTIPLY: wgpu::BlendState = wgpu::BlendState {
    color: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::Dst,
        dst_factor: wgpu::BlendFactor::Zero,
        operation: wgpu::BlendOperation::Add,
    },
    alpha: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::Zero,
        dst_factor: wgpu::BlendFactor::One,
        operation: wgpu::BlendOperation::Add,
    },
};

const MAXIMUM: wgpu::BlendState = wgpu::BlendState {
    color: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::One,
        dst_factor: wgpu::BlendFactor::One,
        operation: wgpu::BlendOperation::Max,
    },
    alpha: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::One,
        dst_factor: wgpu::BlendFactor::One,
        operation: wgpu::BlendOperation::Max,
    },
};

/// `src · dst.a + dst · (1 − src.a)`, the target's alpha kept: what is drawn shows only where the
/// target is, and as much as it is.
const ATOP: wgpu::BlendState = wgpu::BlendState {
    color: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::DstAlpha,
        dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
        operation: wgpu::BlendOperation::Add,
    },
    alpha: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::Zero,
        dst_factor: wgpu::BlendFactor::One,
        operation: wgpu::BlendOperation::Add,
    },
};

/// `dst · (1 − src.a)`: erasing.
const DESTINATION_OUT: wgpu::BlendState = wgpu::BlendState {
    color: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::Zero,
        dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
        operation: wgpu::BlendOperation::Add,
    },
    alpha: wgpu::BlendComponent {
        src_factor: wgpu::BlendFactor::Zero,
        dst_factor: wgpu::BlendFactor::OneMinusSrcAlpha,
        operation: wgpu::BlendOperation::Add,
    },
};

/// A stroke texture canvases of one size take turns to draw their strokes in, so that each
/// needn't hold one of its own: a model's texture sets, one painted at a time. Clones are the
/// same texture.
#[derive(Clone)]
pub struct SharedStroke {
    texture: wgpu::Texture,
    view: wgpu::TextureView,
}

impl SharedStroke {
    pub fn new(gpu: &Gpu, width: u32, height: u32) -> SharedStroke {
        let format = gpu.canvas_pipelines().stroke_format;
        let texture = texture(&gpu.device, "shared stroke", width.max(1), height.max(1), format);
        let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
        SharedStroke { texture, view }
    }

    pub fn texture(&self) -> &wgpu::Texture {
        &self.texture
    }
}

/// An array of brush images (shapes or grains) viewed whole.
fn array_view(texture: &wgpu::Texture) -> wgpu::TextureView {
    texture.create_view(&wgpu::TextureViewDescriptor {
        dimension: Some(wgpu::TextureViewDimension::D2Array),
        ..Default::default()
    })
}

impl Canvas {
    pub fn new(gpu: &Gpu, doc: &Document) -> Result<Canvas, GpuError> {
        let mut canvas = Canvas::unfilled(gpu, doc)?;
        canvas.fill_rest(gpu, doc);
        Ok(canvas)
    }

    /// A canvas with no layer textures yet, for layers about to come from elsewhere (an artwork
    /// opened, a crop): each is made as its layer goes up, those past the budget being parked
    /// instead, so that a canvas of many layers never holds a texture for every one at once.
    /// [`Canvas::fill_rest`] ends it.
    pub fn unfilled(gpu: &Gpu, doc: &Document) -> Result<Canvas, GpuError> {
        Canvas::make(gpu, doc, None, true)
    }

    /// Makes the textures of the layers that have none and aren't parked (clear).
    pub fn fill_rest(&mut self, gpu: &Gpu, doc: &Document) {
        self.sync_layers(gpu, doc);
    }

    /// A canvas whose strokes are drawn into it from elsewhere ([`Canvas::begin_drawn_stroke`]),
    /// as a model's texture sets take them from the screen: it draws them in `stroke` (shared
    /// with other canvases its size, one stroke open among them at a time), and makes its shape
    /// and grain images only once something wants them.
    pub fn for_drawn_strokes(gpu: &Gpu, doc: &Document, stroke: &SharedStroke) -> Result<Canvas, GpuError> {
        let mut canvas = Canvas::make(gpu, doc, Some(stroke), false)?;
        canvas.fill_rest(gpu, doc);
        Ok(canvas)
    }

    fn make(gpu: &Gpu, doc: &Document, stroke: Option<&SharedStroke>, images: bool) -> Result<Canvas, GpuError> {
        let (width, height) = (doc.width(), doc.height());
        let max = gpu.max_texture_size();
        if width > max || height > max {
            return Err(GpuError::TooLarge(width, height, max));
        }
        let device = &gpu.device;
        let pipelines = gpu.canvas_pipelines();
        let stroke_format = pipelines.stroke_format;
        // the most a smudge's window takes: what a binding holds, or in fragment passes their own
        let smudge_most =
            if pipelines.compute.is_some() { Canvas::smudge_room_for(device) } else { fragments::WINDOW_MOST };
        let smudge_room = if pipelines.compute.is_some() { Canvas::smudge_room_for(device) } else { u64::MAX };
        let fits = stroke.filter(|s| (s.texture.width(), s.texture.height()) == (width, height));
        let (stroke_texture, stroke_view) = match fits {
            Some(s) => (s.texture.clone(), s.view.clone()),
            None => {
                let t = texture(device, "stroke", width, height, stroke_format);
                let v = t.create_view(&wgpu::TextureViewDescriptor::default());
                (t, v)
            }
        };
        let composite = texture(device, "composite", width, height, LAYER_FORMAT);
        let composite_view = composite.create_view(&wgpu::TextureViewDescriptor::default());
        let (shape_images, grain_images) =
            if images { (textures::shapes(), textures::grains()) } else { (Vec::new(), Vec::new()) };
        let (shapes, grains) = if images {
            (brush_images(gpu, "shapes", &shape_images), brush_images(gpu, "grains", &grain_images))
        } else {
            (stand_in_images(gpu, "shapes"), stand_in_images(gpu, "grains"))
        };
        let shapes_view = array_view(&shapes);
        let grains_view = array_view(&grains);
        let mipmapped = |label, address| {
            device.create_sampler(&wgpu::SamplerDescriptor {
                label: Some(label),
                address_mode_u: address,
                address_mode_v: address,
                mag_filter: wgpu::FilterMode::Linear,
                min_filter: wgpu::FilterMode::Linear,
                mipmap_filter: wgpu::MipmapFilterMode::Linear,
                ..Default::default()
            })
        };
        let shape_sampler = mipmapped("shape", wgpu::AddressMode::ClampToEdge);
        let grain_sampler = mipmapped("grain", wgpu::AddressMode::Repeat);
        let table = texture(device, "adjustment table", 256, 1, LAYER_FORMAT);
        let table_view = table.create_view(&wgpu::TextureViewDescriptor::default());
        let everything =
            coverage_texture(device, &gpu.queue, 1, 1, &[255]).create_view(&wgpu::TextureViewDescriptor::default());
        let brush_bind = brush_bind(
            device,
            &pipelines.brush_layout,
            [&shapes_view, &grains_view, &everything],
            [&shape_sampler, &grain_sampler],
        );
        let paper_texture = paper_texture(gpu);
        let paper_view = paper_texture.create_view(&wgpu::TextureViewDescriptor::default());
        let stroke_bind =
            quad_bind(device, &pipelines.quad_layout, [&stroke_view; 7], &grains_view, &grain_sampler, &paper_view);
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("linear"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let display_bind = sampled_bind(device, &pipelines.sampled_layout, &composite_view, &sampler);
        let crisp_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("crisp"),
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let crisp_bind = sampled_bind(device, &pipelines.sampled_layout, &composite_view, &crisp_sampler);
        // two points on a side: each corner its own colour
        let corners: Vec<u8> = (0..8u8).flat_map(|i| [(i >> 2) & 1, (i >> 1) & 1, i & 1, 1].map(|c| c * 255)).collect();
        let identity_lut = lut_bind(gpu, &pipelines.lut_layout, 2, &corners);
        let uniform_slots = 64;
        let (uniform_buffer, uniform_bind) = uniforms(device, &pipelines.uniform_layout, uniform_slots);
        let stamp_capacity = 4096;
        let stamp_buffer = stamp_buffer(device, stamp_capacity);
        let canvas = Canvas {
            id: NEXT_CANVAS.fetch_add(1, Ordering::Relaxed),
            width,
            height,
            pipelines,
            screen: ScreenPipelines::default(),
            layers: HashMap::new(),
            stroke: stroke_texture,
            stroke_view,
            tip: None,
            tip_backup: None,
            rule_out: None,
            rule_lists: RuleLists::default(),
            rule_list_most: RULE_LIST_MOST,
            work_left_out: false,
            fragments: fragments::Fragments::default(),
            composite,
            composite_view,
            composite_second: OnDemand::new("composite 2", width, height, LAYER_FORMAT),
            groups: Vec::new(),
            clip_group: OnDemand::new("clip group", width, height, LAYER_FORMAT),
            clip_group_second: OnDemand::new("clip group 2", width, height, LAYER_FORMAT),
            play_frame: None,
            scratch: OnDemand::new("scratch", width, height, LAYER_FORMAT),
            second_stroke: OnDemand::new("second stroke", width, height, stroke_format),
            queued_second: None,
            blur_textures: Mutex::new(HashMap::new()),
            spare_before: None,
            spares: Mutex::new(Vec::new()),
            uploads: Uploads::default(),
            sampler,
            grains_view,
            grain_sampler,
            paper_texture,
            paper_view,
            paper: None,
            shapes_view,
            shape_sampler,
            everything,
            pencil: None,
            liquify: None,
            kept_liquify: None,
            filter_keeps_alpha: false,
            table,
            table_view,
            selection: None,
            brush_bind,
            stroke_bind,
            display_bind,
            crisp_bind,
            identity_lut,
            uniform_buffer,
            uniform_bind,
            uniform_slots,
            stamp_buffer,
            stamp_capacity,
            work_at_once: WORK_AT_ONCE,
            stamps_at_once: STAMPS_AT_ONCE,
            pieces_sent: 0,
            paced: false,
            paced_longest: std::time::Duration::ZERO,
            dispatched: AtomicU64::new(0),
            stamp_by_stamp: false,
            rewinds: true,
            rewind_most: None,
            marks_made: 0,
            rewound: 0,
            may_lay_again: true,
            stamps_queued: 0,
            second_unsynthesized: IRect::EMPTY,
            relay_at_once: RELAY_AT_ONCE,
            relaying: false,
            catching_up: false,
            in_place_sent: None,
            in_place_rate: None,
            in_place_paced: true,
            filter_run: None,
            composites: 0,
            active: None,
            smudge: None,
            smudge_refused: false,
            smudge_untold: false,
            held: None,
            held_quietly: false,
            veil: None,
            fill_preview: None,
            steps_made: 0,
            mark: None,
            next_name: None,
            within: None,
            lay_within: None,
            measuring: false,
            last_ended: None,
            scaled: None,
            sharp: None,
            smudge_params: smudge_params(device, 64),
            smudge_spare: [spare_buffer(device), spare_buffer(device)],
            smudge_capacity: 64,
            smudge_most,
            smudge_room,
            wet_params: wet_params(device, 4),
            wet_capacity: 4,
            prediction: Vec::new(),
            laid: None,
            laid_second: None,
            laid_count: 0,
            laid_second_count: 0,
            ends_whole: false,
            strokes_ended: 0,
            stroke_step: None,
            ops: Vec::new(),
            cache_composites: false,
            caches: None,
            remake: None,
            spare_caches: None,
            parked: HashMap::new(),
            pictures: HashMap::new(),
            changed: HashSet::new(),
            versions: HashMap::new(),
            version: 0,
            parking_left: false,
            last_remake: None,
            resident_budget: 0,
            needed_at: HashMap::new(),
            renders: 0,
            dirty: IRect::from_size(width, height),
            undo: Vec::new(),
            redo: Vec::new(),
            packing: None,
            history_generation: 0,
            packer: None,
            recent_history: None,
            transforming: None,
            transform_more: Vec::new(),
            stores: HashMap::new(),
            next_plane: 0,
            backdrop: None,
            backdrop_texels: if gpu.is_weak() { BACKDROP_TEXELS_WEAK } else { BACKDROP_TEXELS },
            frost_stale: IRect::from_size(width, height),
            frosts: 0,
            shapes,
            grains,
            brush_images: images,
            stroke_grain: None,
            full_grains: FullGrains::default(),
            full_shapes: FullGrains::default(),
            bound_full_grain: None,
            shape_sources: SourceSlots::after(shape_images.len()),
            grain_sources: SourceSlots::after(grain_images.len()),
        };
        Ok(canvas)
    }

    pub fn size(&self) -> (u32, u32) {
        (self.width, self.height)
    }

    /// Its own among the canvases made while the app runs: what was known of another's layers
    /// isn't taken for its own.
    pub fn id(&self) -> u64 {
        self.id
    }

    /// Points `style` at `brush`'s own shape and grain images, if it has them, putting them in
    /// the arrays' spare layers first if they aren't there.
    pub fn use_sources(&mut self, gpu: &Gpu, style: &mut StrokeStyle, brush: &Brush) {
        if brush.shape.source.is_some() || brush.grain.source.is_some() {
            self.prepare_brush_images(gpu);
        }
        if let Some(source) = brush.shape.source.as_ref() {
            style.shape = Some(self.shape_sources.layer(gpu, &self.shapes, source));
        }
        if let Some(source) = brush.grain.source.as_ref() {
            style.grain = Some(self.grain_sources.layer(gpu, &self.grains, source));
        }
    }

    /// Makes the shape and grain images now if the canvas goes without them (see
    /// [`Canvas::for_drawn_strokes`]), so the first stroke that wants them doesn't wait.
    pub fn prepare_brush_images(&mut self, gpu: &Gpu) {
        if self.brush_images {
            return;
        }
        let (shape_images, grain_images) = (textures::shapes(), textures::grains());
        self.shapes = brush_images(gpu, "shapes", &shape_images);
        self.grains = brush_images(gpu, "grains", &grain_images);
        self.shape_sources = SourceSlots::after(shape_images.len());
        self.grain_sources = SourceSlots::after(grain_images.len());
        self.brush_images = true;
        self.bind_brush_images(&gpu.device);
    }

    /// Lets go of what is kept only to be quicker next time: the textures of caches let go, a
    /// transform's copy let go, a blur's working textures, the upload buffers (the system short
    /// of memory, or the app put away). Each is made again when next wanted.
    pub fn let_go_of_spares(&mut self) {
        self.spare_caches = None;
        self.spare_before = None;
        self.spares.lock().unwrap_or_else(PoisonError::into_inner).clear();
        self.blur_textures.lock().unwrap_or_else(PoisonError::into_inner).clear();
        // a dual brush's second stroke's, unless one is open (or queued)
        let dual = self.active.is_some_and(|a| a.second.is_some())
            || self.ops.iter().any(|op| match op {
                Op::ClearSecond(_) | Op::DropSecond | Op::Stamps { second: true, .. } => true,
                Op::Commit(stroke) => stroke.second.is_some(),
                _ => false,
            });
        if !dual {
            self.second_stroke.let_go();
        }
        self.uploads.kept.lock().unwrap_or_else(PoisonError::into_inner).clear();
        // made again when a group composites on its own, or a clipping group
        self.groups.clear();
        self.clip_group.let_go();
        self.clip_group_second.let_go();
    }

    /// Lets go of the shape and grain images until something wants them again (a canvas left
    /// waiting); not while a stroke is open.
    pub fn let_go_of_brush_images(&mut self, gpu: &Gpu) {
        if !self.brush_images || self.active.is_some() || self.smudge.is_some() || !self.ops.is_empty() {
            return;
        }
        self.shapes = stand_in_images(gpu, "shapes");
        self.grains = stand_in_images(gpu, "grains");
        self.shape_sources = SourceSlots::after(0);
        self.grain_sources = SourceSlots::after(0);
        self.brush_images = false;
        self.prediction.clear();
        self.bind_brush_images(&gpu.device);
    }

    /// `style` reads the brush file's grain `id` (`pixels`, `side` square) in place of the brush
    /// images: each stroke its own, kept on the GPU while it is among the last few used.
    pub fn use_full_grain(&mut self, gpu: &Gpu, style: &mut StrokeStyle, id: u64, side: u32, pixels: &[u8]) {
        if self.full_grains.hold(gpu, id, side, pixels) {
            style.grain = Some(0);
            style.full_grain = Some(id);
            style.grain_side = side as f32;
        }
    }

    /// `style` reads the brush file's shape `id` (`pixels`, `side` square) in place of the brush
    /// images: each stroke its own, kept on the GPU while it is among the last few used.
    pub fn use_full_shape(&mut self, gpu: &Gpu, style: &mut StrokeStyle, id: u64, side: u32, pixels: &[u8]) {
        if self.full_shapes.hold(gpu, id, side, pixels) {
            style.shape = Some(0);
            style.full_shape = Some(id);
        }
    }

    /// `style` reads its shapes from the set `id` (`tips`, each `side` square, in order) in place of
    /// the brush images, each stamp the one its `tip` says: kept on the GPU while it is among the
    /// last few used. A grey byte a pixel for masks, else four (RGBA, straight; see [`TipKind`]).
    /// Nothing if they don't fit (see [`MOST_TIPS`]).
    pub fn use_tip_set(
        &mut self,
        gpu: &Gpu,
        style: &mut StrokeStyle,
        id: u64,
        side: u32,
        tips: &[&[u8]],
        kind: TipKind,
    ) {
        if self.full_shapes.hold_set(gpu, id, side, tips, kind.channels()) {
            style.shape = Some(0);
            style.full_shape = Some(id);
            style.tip_kind = kind;
        }
    }

    /// The open stroke's grain fixed to the canvas is read from `grain` from now on, in place of a
    /// brush image: a picture the canvas's size (square), one grey per pixel, viewed as an array
    /// of one, such as 3D painting reads along a model's three axes. How the grain shows (its
    /// depth, brightness, contrast and blend mode) stays the stroke's. Kept until the stroke has
    /// ended; nothing without an open stroke whose grain is fixed to the canvas.
    pub fn set_stroke_grain(&mut self, device: &wgpu::Device, grain: &wgpu::TextureView) {
        let square = self.width == self.height;
        let Some(active) = self.active.as_mut().filter(|a| square && a.style.canvas_grain().is_some()) else {
            return;
        };
        // one tile the canvas, read texel for texel
        active.style.full_grain = None;
        active.style.grain_side = TEXTURE_SIZE as f32;
        active.style.grain = Some(0);
        active.style.grain_scale = self.width as f32;
        active.style.grain_rotation = 0.0;
        active.style.grain_sharp = false;
        self.stroke_grain = Some(grain.clone());
        self.bind_brush_images(device);
    }

    /// The grains the open stroke reads: its own picture, else the brush images.
    fn grain_view(&self) -> &wgpu::TextureView {
        match self.active.as_ref() {
            Some(a) => self.grain_view_of(&a.style),
            None => self.stroke_grain.as_ref().unwrap_or(&self.grains_view),
        }
    }

    /// The grains a stroke in `style` reads, the open one or one still going on to its layer.
    fn grain_view_of(&self, style: &StrokeStyle) -> &wgpu::TextureView {
        match style.full_grain.and_then(|id| self.full_grains.view(id)) {
            Some(view) => view,
            None => self.stroke_grain.as_ref().unwrap_or(&self.grains_view),
        }
    }

    /// The bind of the shape and grain arrays for a stroke in `style` that reads its own grain or
    /// shape.
    fn full_grain_bind(&self, device: &wgpu::Device, style: &StrokeStyle) -> Option<wgpu::BindGroup> {
        let grain = style.full_grain.and_then(|id| self.full_grains.view(id));
        let shape = style.full_shape.and_then(|id| self.full_shapes.view(id));
        if grain.is_none() && shape.is_none() {
            return None;
        }
        let mask = self.selection.as_ref().map_or(&self.everything, |s| &s.view);
        Some(brush_bind(
            device,
            &self.pipelines.brush_layout,
            [shape.unwrap_or(&self.shapes_view), grain.unwrap_or(&self.grains_view), mask],
            [&self.shape_sampler, &self.grain_sampler],
        ))
    }

    /// Whether it holds its shape and grain images (else stand-ins until something wants them).
    pub fn has_brush_images(&self) -> bool {
        self.brush_images
    }

    /// The binds that hold the shape and grain arrays or the stroke, made again.
    fn bind_brush_images(&mut self, device: &wgpu::Device) {
        self.shapes_view = array_view(&self.shapes);
        self.grains_view = array_view(&self.grains);
        let mask = self.selection.as_ref().map_or(&self.everything, |s| &s.view);
        self.brush_bind = brush_bind(
            device,
            &self.pipelines.brush_layout,
            [&self.shapes_view, &self.grains_view, mask],
            [&self.shape_sampler, &self.grain_sampler],
        );
        self.stroke_bind = quad_bind(
            device,
            &self.pipelines.quad_layout,
            [&self.stroke_view; 7],
            self.grain_view(),
            &self.grain_sampler,
            &self.paper_view,
        );
    }

    /// Whether the queued work or the stroke open reads the shape or grain images: stamps, the
    /// predicted tail, a smudge, or a grain fixed to the canvas.
    fn wants_brush_images(&self) -> bool {
        let grained = |s: &StrokeStyle| s.canvas_grain().is_some() && self.stroke_grain.is_none();
        !self.prediction.is_empty()
            || self.smudge.is_some()
            || self.active.is_some_and(|a| grained(&a.style))
            || self.ops.iter().any(|op| match op {
                Op::Stamps { .. } => true,
                Op::Commit(stroke) => grained(&stroke.style),
                _ => false,
            })
    }

    /// From now on draws its strokes in `stroke` (a texture its size that other canvases take
    /// turns with), letting go of its own; whether it does. Not while a stroke is open.
    pub fn draw_strokes_in(&mut self, gpu: &Gpu, stroke: &SharedStroke) -> bool {
        if self.stroke == stroke.texture {
            return true;
        }
        let fits = (stroke.texture.width(), stroke.texture.height()) == (self.width, self.height);
        let busy = self.active.is_some() || self.smudge.is_some() || self.tip.is_some() || !self.ops.is_empty();
        if !fits || busy {
            return false;
        }
        self.stroke = stroke.texture.clone();
        self.stroke_view = stroke.view.clone();
        self.bind_brush_images(&gpu.device);
        true
    }

    fn full(&self) -> IRect {
        IRect::from_size(self.width, self.height)
    }

    pub fn has_stroke(&self) -> bool {
        self.active.is_some()
    }

    /// The open stroke's tool, if a stroke is open.
    pub fn stroke_tool(&self) -> Option<StrokeTool> {
        self.active.map(|a| a.style.tool)
    }

    /// Whether an undo is possible, counting queued work.
    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
            || self.ops.iter().any(|op| {
                matches!(
                    op,
                    Op::Commit(_)
                        | Op::Clear(..)
                        | Op::Invert(..)
                        | Op::Fill(..)
                        | Op::Record(_)
                        | Op::OutsideChange(_)
                        | Op::Duplicate { .. }
                        | Op::MergeDown { .. }
                        | Op::MergeMany { .. }
                        | Op::ApplyMask { .. }
                        | Op::Flatten { .. }
                        | Op::TransformEnd { commit: true, .. }
                        | Op::Write { .. }
                        | Op::Flip { .. }
                        | Op::Release { keep: true, .. }
                )
            })
    }

    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }

    /// Drops what could be redone, as a new step would: the canvas is set aside for something new
    /// (a crop), and what was undone on it can no longer come back.
    pub fn forget_redo(&mut self) {
        for dropped in std::mem::take(&mut self.redo) {
            self.keep_spare_snapshot(dropped);
        }
    }

    /// Names the next step made (what undo and redo call it), queued with the work that makes it.
    pub fn name_next_step(&mut self, name: &str) {
        self.ops.push(Op::Name(name.to_owned()));
    }

    /// The name of the step undo would undo next, and of the step redo would redo, as the
    /// history is after the work run so far.
    pub fn undo_name(&self) -> Option<String> {
        self.undo.last().and_then(|s| s.name.clone())
    }

    pub fn redo_name(&self) -> Option<String> {
        self.redo.last().and_then(|s| s.name.clone())
    }

    /// Which step undo would undo next, and redo would redo, numbered as [`Canvas::steps_made`]
    /// counted them when they were made.
    pub fn undo_step(&self) -> Option<u64> {
        self.undo.last().map(|s| s.made)
    }

    pub fn redo_step(&self) -> Option<u64> {
        self.redo.last().map(|s| s.made)
    }

    /// Whether step `made` (numbered as [`Canvas::steps_made`]) can still be undone or redone here.
    pub fn keeps_step(&self, made: u64) -> bool {
        self.undo.iter().chain(&self.redo).any(|s| s.made == made)
    }

    /// Marks where the history is once the queued work is done, for [`Canvas::collapse_steps`].
    pub fn mark_steps(&mut self) {
        self.end_stroke();
        self.ops.push(Op::Mark);
    }

    /// Makes the steps still to undo that were made since the mark one step, which undoes them
    /// all; the mark is used up.
    pub fn collapse_steps(&mut self) {
        self.end_stroke();
        self.ops.push(Op::Collapse);
    }

    /// How many undo steps have ever been made here (queued work not counted until drawn): it
    /// changes whenever something new is done.
    pub fn steps_made(&self) -> u64 {
        self.steps_made
    }

    /// How many times the composite has been redrawn: it changes whenever the picture does.
    pub fn composites(&self) -> u64 {
        self.composites
    }

    /// How many times the glass's scene has been drawn and blurred (see [`Canvas::frost_ready`]).
    pub fn frosts(&self) -> u64 {
        self.frosts
    }

    /// Starts a stroke on `layer` (finishing one still open).
    pub fn begin_stroke(&mut self, layer: LayerId, style: StrokeStyle) {
        self.end_stroke();
        self.may_lay_again = true;
        self.prediction.clear();
        self.laid = Some(Vec::new());
        (self.laid_count, self.laid_second_count) = (0, 0);
        let within = self.lay_within;
        self.laid_second = None;
        self.active = Some(ActiveStroke::new(layer, style, false, within));
    }

    /// How a laid stroke is marked in this canvas's stroke (see `STROKE_LAID`).
    fn laid_mode(&self) -> u32 {
        if self.pipelines.stroke_format == LAYER_FORMAT { STROKE_LAID_HALVED } else { STROKE_LAID }
    }

    /// What strokes build up in here: half floats, or 8 bits a channel on a GPU that can't draw
    /// into those.
    pub fn stroke_format(&self) -> wgpu::TextureFormat {
        self.pipelines.stroke_format
    }

    /// Begins a stroke as [`Canvas::begin_stroke`] does, with a dual brush's second (its stamps
    /// given with [`Canvas::add_second_stamps`]).
    pub fn begin_dual_stroke(&mut self, layer: LayerId, style: StrokeStyle, second: SecondStroke) {
        self.begin_stroke(layer, style);
        if let Some(active) = self.active.as_mut() {
            active.second = Some(second);
        }
        // (paint laid in place apart only adds to what the stroke keeps, never reading its second,
        // which only putting it together takes: that is done over the whole stroke as it ends, so
        // laying only the rest of either at the end leaves what laying both whole would)
        self.laid_second = Some(Vec::new());
        if !self.measuring {
            self.ops.push(Op::ClearSecond(second));
        }
    }

    /// Draws more of the open stroke's second.
    pub fn add_second_stamps(&mut self, stamps: &[Stamp]) {
        self.push_stamps_to(stamps, false, true);
        keep_laid(&mut self.laid_second, stamps);
        self.laid_second_count += stamps.len();
    }

    /// Redraws the open stroke's second from scratch with `stamps` (its final form): only those
    /// after the stamps it has laid, if they are still at its start (as [`Canvas::replace_stamps`]).
    pub fn replace_second_stamps(&mut self, stamps: &[Stamp]) {
        if let Some(from) = laid_before(self.laid_second.as_deref(), stamps, !self.ends_with_rest(true)) {
            self.add_second_stamps(stamps.get(from..).unwrap_or_default());
            return;
        }
        self.push_stamps_to(stamps, true, true);
        if self.laid_second.is_some() {
            self.laid_second = (stamps.len() <= LAID_KEPT_MOST).then(|| stamps.to_vec());
        }
        self.laid_second_count = stamps.len();
    }

    /// Ends the open stroke's second with `rest`, the stamps of its final form after the
    /// [`Canvas::laid_second_count`] it has laid (as they still are): only those are laid.
    pub fn end_second_with(&mut self, rest: &[Stamp]) {
        self.add_second_stamps(rest);
    }

    /// How many stamps the open stroke's second has laid (none without one, or when a stroke is
    /// laid whole as it ends, see [`Canvas::lay_ends_whole`]).
    pub fn laid_second_count(&self) -> Option<usize> {
        self.ends_with_rest(true).then_some(self.laid_second_count)
    }

    /// Whether the open stroke (`second`: its second) can end laying only what follows the stamps
    /// it has laid and come out as laid whole. Not a dual brush's part laid in the stroke's
    /// textures by a rule that builds up gradually or towards its transfer: that rounds its sums
    /// to half floats once a lot (see `stamp_rule.wgsl`), so lots cut otherwise come out a little
    /// otherwise, and a dual brush's stroke has always been laid whole again at its end. But the
    /// second of paint laid in place does: laid whole again, it is put together with that paint
    /// over the whole stroke, which on a slow GPU held the next stroke for a second or more after a
    /// big brush's lift; what its lots' rounding changes is within a level here and there (see the
    /// tests). Nor when laying whole is asked for (see [`Canvas::lay_ends_whole`]).
    fn ends_with_rest(&self, second: bool) -> bool {
        let Some(active) = self.active.filter(|_| !self.ends_whole) else { return false };
        let Some(dual) = active.second else { return !second };
        let style = if second { dual.style } else { active.style };
        let by_rule_here = self.pipelines.compute.is_some() || self.pipelines.fragments.is_some();
        style.in_place() || (second && active.style.in_place()) || by_rule(style.rendering).is_none() || !by_rule_here
    }

    /// The open stroke goes on without its second (it was made into a shape).
    pub fn drop_second(&mut self) {
        self.laid_second = None;
        if let Some(active) = self.active.as_mut()
            && active.second.take().is_some()
        {
            self.dirty = self.dirty.union(active.bounds);
            self.ops.push(Op::DropSecond);
        }
    }

    /// Begins a stroke whose pixels another pass draws into [`Canvas::stroke_target`] (3D
    /// painting carries a stroke drawn on the screen onto a model's texture); each draw is told
    /// with [`Canvas::stroke_drawn`], and the stroke ends (into `layer`) as any other.
    pub fn begin_drawn_stroke(&mut self, layer: LayerId, style: StrokeStyle) {
        self.begin_stroke(layer, style);
    }

    /// Where a drawn stroke's pixels go: premultiplied, the canvas's size.
    pub fn stroke_target(&self) -> &wgpu::TextureView {
        &self.stroke_view
    }

    /// The open stroke's pixels as drawn so far (premultiplied, the canvas's size): what 3D
    /// painting carries onto a model.
    pub fn stroke_view(&self) -> &wgpu::TextureView {
        &self.stroke_view
    }

    /// The canvas as composited by the last render (premultiplied, its size, the background in
    /// if shown): what 3D painting shows on a model.
    pub fn composite_view(&self) -> &wgpu::TextureView {
        &self.composite_view
    }

    /// A drawn stroke now reaches `bounds` (canvas pixels): it is shown there, and kept there
    /// when it ends.
    pub fn stroke_drawn(&mut self, bounds: IRect) {
        let full = self.full();
        let Some(active) = self.active.as_mut() else { return };
        let bounds = bounds.intersect(full);
        active.bounds = active.bounds.union(bounds);
        self.dirty = self.dirty.union(bounds);
    }

    /// Draws more of the open stroke.
    pub fn add_stamps(&mut self, stamps: &[Stamp]) {
        self.untip();
        self.push_stamps(stamps, false);
        keep_laid(&mut self.laid, stamps);
        self.laid_count += stamps.len();
    }

    /// Whether the open stroke keeps the stamps it has laid (all but a very long one's), so that
    /// [`Canvas::replace_stamps`] lays only what follows those still at its start.
    pub fn keeps_laid(&self) -> bool {
        self.laid.is_some()
    }

    /// How many stamps the open stroke has laid for good (added since it began or was last redrawn
    /// from scratch; none when a stroke is laid whole as it ends, see [`Canvas::lay_ends_whole`]).
    pub fn laid_count(&self) -> Option<usize> {
        self.ends_with_rest(false).then_some(self.laid_count)
    }

    /// Ends the open stroke with `rest`, the stamps of its final form after the
    /// [`Canvas::laid_count`] it has laid (as they still are): only those are laid.
    pub fn end_with(&mut self, rest: &[Stamp]) {
        self.add_stamps(rest);
    }

    /// Redraws the open stroke from scratch with `stamps` (its final form, e.g. with the end taper).
    /// Those laid so far still at its start stay as they are, and only the rest is laid: a long
    /// stroke of wet paint, laid whole again as the pen lifted, took a tenth of a second or more.
    pub fn replace_stamps(&mut self, stamps: &[Stamp]) {
        let laid = laid_before(self.laid.as_deref(), stamps, !self.ends_with_rest(false));
        if let Some(from) = laid {
            self.add_stamps(stamps.get(from..).unwrap_or_default());
            return;
        }
        self.tip = None;
        self.push_stamps(stamps, true);
        if self.laid.is_some() {
            self.laid = (stamps.len() <= LAID_KEPT_MOST).then(|| stamps.to_vec());
        }
        self.laid_count = stamps.len();
    }

    /// Whether the open stroke can show a tip (see [`Canvas::set_tip`]): one built up in the
    /// stroke texture, not painted in place or onto an adjustment, nor a dual brush's: its second
    /// isn't there yet to cut it.
    pub fn shows_tip(&self) -> bool {
        self.active.is_some_and(|a| !a.pencil && !a.style.in_place() && a.second.is_none())
    }

    /// The open stroke's tip, drawn over it until the next stamps or tip: its last stamps as
    /// they'd be drawn were it to end now.
    pub fn set_tip(&mut self, stamps: &[Stamp]) {
        self.untip();
        if !self.shows_tip() || stamps.is_empty() {
            return;
        }
        let at = self.ops.len();
        // (measured, it counts where the stroke reaches, as drawn)
        let bounds = self.push_stamps(stamps, false);
        if bounds.is_empty() || self.measuring {
            return;
        }
        self.ops.insert(at, Op::SaveTip(bounds));
        self.tip = Some(bounds);
    }

    /// Takes the tip off the open stroke.
    fn untip(&mut self) {
        if let Some(rect) = self.tip.take() {
            self.dirty = self.dirty.union(rect);
            self.ops.push(Op::Untip(rect));
        }
    }

    /// Queues `stamps` into the open stroke (`clear`: from scratch); where they reach.
    fn push_stamps(&mut self, stamps: &[Stamp], clear: bool) -> IRect {
        self.push_stamps_to(stamps, clear, false)
    }

    /// As [`Canvas::push_stamps`], into the open stroke's second if `second` (none without one).
    fn push_stamps_to(&mut self, stamps: &[Stamp], clear: bool, second: bool) -> IRect {
        // given in another canvas's pixels: drawn here as much bigger
        let mapped: Vec<Stamp>;
        let stamps = match self.scaled {
            Some((origin, k)) => {
                mapped = stamps.iter().map(|s| scaled_stamp(s, origin, k)).collect();
                &mapped[..]
            }
            None => stamps,
        };
        let full = self.full();
        // where the GPU runs the rule's pass (see `Canvas::lay_by_rule`), or lays every stroke by
        // its rule in fragment passes
        let by_rule_here = self.pipelines.compute.is_some() || self.pipelines.fragments.is_some();
        let Some(active) = self.active.as_mut() else { return IRect::EMPTY };
        let style = match (second, active.second) {
            (false, _) => active.style,
            (true, Some(s)) => s.style,
            (true, None) => return IRect::EMPTY,
        };
        let scale = self.scaled.map(|(_, k)| k);
        let drawn_at = |s: &Stamp| {
            let radius = drawn_radius(&style, s.radius, s.taper);
            scale.map_or(radius, |k| scaled_radius(&style, radius, k))
        };
        let reach = |s: &Stamp| {
            let radius = drawn_at(s);
            let tall = if s.roundness.is_finite() { s.roundness.clamp(1.0, MOST_ROUNDNESS) } else { 1.0 };
            IRect::around(s.center, radius * tall * std::f32::consts::SQRT_2)
        };
        let drawn: Vec<&Stamp> =
            stamps.iter().filter(|s| s.center.is_finite() && s.radius.is_finite() && s.alpha.is_finite()).collect();
        // where all of them reach, though only those reaching where the stroke is laid are drawn
        // (each pixel built up from its own stamps alone: never a stroke laid in place)
        let bounds = drawn.iter().fold(IRect::EMPTY, |b, s| b.union(reach(s))).intersect(full);
        let within = active.within.filter(|_| !style.in_place());
        let drawn: Vec<&Stamp> =
            drawn.into_iter().filter(|s| within.is_none_or(|w| !reach(s).intersect(w).is_empty())).collect();
        if clear && !second {
            if !self.measuring {
                self.dirty = self.dirty.union(active.bounds);
            }
            // (a dual brush's second shows wherever it reaches, past the first's stamps too)
            let past = if active.second.is_some() { active.second_reach } else { IRect::EMPTY };
            active.bounds = bounds.union(past);
        } else {
            active.bounds = active.bounds.union(bounds);
        }
        match (second, clear) {
            (false, true) => active.first = bounds,
            (false, false) => active.first = active.first.union(bounds),
            (true, true) => {
                // (what was put together where it reached is put together again as the stroke ends)
                if !self.measuring && !active.second_reach.is_empty() {
                    self.ops.push(Op::SecondReached(active.second_reach));
                }
                active.second_reach = bounds;
            }
            (true, false) => active.second_reach = active.second_reach.union(bounds),
        }
        if self.measuring {
            return bounds;
        }
        let wet: Vec<[f32; 8]> =
            drawn.iter().map(|s| s.wet.map(|v| if v.is_finite() { v.clamp(0.0, 1.0) } else { 1.0 })).collect();
        let picks: Vec<Option<PickUp>> = drawn.iter().map(|s| s.pick_up).collect();
        let times: Vec<Paced> = drawn
            .iter()
            .map(|s| Paced {
                time_ms: if s.time_ms.is_finite() { s.time_ms } else { 0.0 },
                distance: if s.distance.is_finite() { s.distance } else { 0.0 },
                copy: usize::from(s.copy),
            })
            .collect();
        // laid by the stroke's rule, a gradual stroke builds up pixel by pixel; where the GPU can't
        // run the rule, it is drawn over as its prediction is (each stamp standing in for it)
        let per_pixel = !style.in_place() && by_rule_here;
        let instances: Vec<StampInstance> = drawn
            .iter()
            .map(|s| {
                let radius = drawn_at(s);
                let (alpha, transfer) = capped_alpha(&style, by_rule_here, s, per_pixel);
                StampInstance {
                    center: [s.center.x, s.center.y],
                    radius,
                    alpha,
                    angle: if s.angle.is_finite() { s.angle } else { 0.0 },
                    roundness: if s.roundness.is_finite() { s.roundness.clamp(0.05, MOST_ROUNDNESS) } else { 1.0 },
                    grain_offset: if s.grain_offset.is_finite() {
                        [s.grain_offset.x, s.grain_offset.y]
                    } else {
                        [0.0; 2]
                    },
                    flip: stamp_extras(s).0,
                    tint: stamp_extras(s).1,
                    brightness_add: stamp_extras(s).2,
                    grain_turn: if s.grain_turn.is_finite() { s.grain_turn } else { 0.0 },
                    look: stamp_look(s),
                    // the bleed up to 2 (the pressure's and the lean's), the rest 0..1
                    more: [(s.grain_press, 1.0), (s.grain_bleed, 2.0), (transfer, 1.0), (s.attack, 1.0)]
                        .map(|(v, most)| if v.is_finite() { v.clamp(0.0, most) } else { 0.0 }),
                    tip: s.tip.min(MOST_TIPS - 1) as f32,
                    lightness: if s.lightness.is_finite() { s.lightness.clamp(0.0, 1.0) } else { 1.0 },
                    threshold: s.threshold.map(|v| if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.0 }),
                }
            })
            .collect();
        self.dirty = self.dirty.union(bounds);
        self.stamps_queued += instances.len() as u64;
        if !instances.is_empty() || clear {
            let pencil = active.pencil;
            // drawn from scratch: what is still queued of it would be laid only to be cleared (a
            // shape dragged about draws its stroke again at each move, faster than frames come)
            if clear && !pencil && !second {
                drop_superseded_stamps(&mut self.ops, active.layer);
            }
            self.ops.push(Op::Stamps {
                instances,
                wet,
                picks,
                times,
                style,
                clear: clear && !pencil,
                layer: active.layer,
                pencil,
                second,
            });
        }
        bounds
    }

    /// Puts the open stroke into its layer (undoably).
    /// Stamps for where the input is predicted to go next: drawn over the canvas until replaced,
    /// never into the layer. Only painting strokes show them.
    pub fn set_prediction(&mut self, stamps: &[Stamp]) {
        self.prediction.clear();
        // (a dual brush's not: drawn over the canvas, it couldn't be cut by its second; nor paint
        // laid in place: drawn over every layer, its stamps show none of what it is laid on, a
        // block of colour that the stroke then isn't)
        let Some(style) = self
            .active
            .filter(|a| a.second.is_none())
            .map(|a| a.style)
            .filter(|s| s.tool == StrokeTool::Paint && !s.paints_in_place())
        else {
            return;
        };
        self.prediction.extend(
            stamps.iter().filter(|s| s.center.is_finite() && s.radius.is_finite() && s.alpha.is_finite()).map(|s| {
                // (drawn over the canvas a stamp at a time, by the overlay)
                let (alpha, transfer) = capped_alpha(&style, false, s, false);
                StampInstance {
                    center: [s.center.x, s.center.y],
                    radius: drawn_radius(&style, s.radius, s.taper),
                    alpha,
                    angle: if s.angle.is_finite() { s.angle } else { 0.0 },
                    roundness: if s.roundness.is_finite() { s.roundness.clamp(0.05, MOST_ROUNDNESS) } else { 1.0 },
                    grain_offset: [0.0; 2],
                    flip: stamp_extras(s).0,
                    tint: stamp_extras(s).1,
                    brightness_add: stamp_extras(s).2,
                    grain_turn: 0.0,
                    look: stamp_look(s),
                    // the bleed up to 2 (the pressure's and the lean's), the rest 0..1
                    more: [(s.grain_press, 1.0), (s.grain_bleed, 2.0), (transfer, 1.0), (s.attack, 1.0)]
                        .map(|(v, most)| if v.is_finite() { v.clamp(0.0, most) } else { 0.0 }),
                    tip: s.tip.min(MOST_TIPS - 1) as f32,
                    lightness: if s.lightness.is_finite() { s.lightness.clamp(0.0, 1.0) } else { 1.0 },
                    threshold: s.threshold.map(|v| if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.0 }),
                }
            }),
        );
    }

    pub fn end_stroke(&mut self) {
        self.prediction.clear();
        self.laid = None;
        self.laid_second = None;
        self.untip();
        if let Some(mut active) = self.active.take() {
            self.last_ended = Some((active.bounds, active.style.in_place()));
            if !self.measuring {
                self.strokes_ended += 1;
                active.number = self.strokes_ended;
                self.ops.push(Op::Commit(active));
            }
        }
    }

    /// How many strokes have ended here: the last one's number, for [`Canvas::step_of_stroke`].
    pub fn strokes_ended(&self) -> u64 {
        self.strokes_ended
    }

    /// The undo step stroke `number` (see [`Canvas::strokes_ended`]) made, once it has been put
    /// into its layer, if it is the last stroke put into one and made a step (one painted on an
    /// adjustment, or that laid nothing, makes none).
    pub fn step_of_stroke(&self, number: u64) -> Option<u64> {
        self.stroke_step.filter(|&(n, _)| n == number).map(|(_, step)| step)
    }

    /// Strokes redrawn whole as they end are laid whole again, as if none of their stamps had been
    /// laid: for tests comparing the two.
    #[doc(hidden)]
    pub fn lay_ends_whole(&mut self, on: bool) {
        self.ends_whole = on;
    }

    /// Strokes begun from now on lay their pixels only within `rect` (none: wherever they reach),
    /// for part of a layer laid again: what they reach outside it is left as it is, and their stamps
    /// there aren't drawn. A stroke laid in place (smudging, painting wet) isn't held to it.
    pub fn lay_within(&mut self, rect: Option<IRect>) {
        self.lay_within = rect;
    }

    /// With `on`, strokes begun from now on are only measured: where each would reach is found
    /// ([`Canvas::last_stroke`] as it ends) and nothing is drawn or kept. Without, they are drawn
    /// again.
    pub fn measure(&mut self, on: bool) {
        self.prediction.clear();
        self.measuring = on;
    }

    /// Where the stroke ended last reached (all of it, wherever [`Canvas::lay_within`] laid it),
    /// and whether it changed its layer as it went (smudging, painting wet); none before any.
    pub fn last_stroke(&self) -> Option<(IRect, bool)> {
        self.last_ended
    }

    /// Lays `pixels` (premultiplied RGBA8, the rows of `rect`) over `layer` there, source over, as a
    /// stroke is laid: one undo step keeping what was there. Only within the rectangle
    /// [`Canvas::lay_within`] says, if it says one; nothing while measuring (see
    /// [`Canvas::measure`]). Pixels of the wrong size are left out.
    pub fn lay_over(&mut self, layer: LayerId, rect: IRect, pixels: Vec<u8>) {
        if self.measuring || rect.is_empty() || pixels.len() != rect.width() as usize * rect.height() as usize * 4 {
            return;
        }
        self.end_stroke();
        let within = self.lay_within.map_or(rect, |w| rect.intersect(w));
        if within.is_empty() {
            return;
        }
        self.ops.push(Op::Over { layer, rect, within, pixels });
    }

    /// Whether strokes are only being measured (see [`Canvas::measure`]).
    pub fn measuring(&self) -> bool {
        self.measuring
    }

    /// Strokes given from now on are in another canvas's pixels, `scale` of this one's each, the
    /// point `origin` of those at this one's top left: drawn here as much bigger (their stamps,
    /// their moving grain and the grain fixed to the canvas with them), for part of that canvas
    /// drawn sharper.
    pub fn draw_scaled(&mut self, origin: [f32; 2], scale: f32) {
        let ok = origin.iter().all(|v| v.is_finite()) && scale.is_finite() && scale > 0.0;
        self.scaled = ok.then_some((origin, scale));
    }

    /// Redraws `layer` from another canvas's layer `source` (`size` pixels, premultiplied), each
    /// pixel here from where `to_source` takes it there (pixel middles at halves), filtered as
    /// `resample` says; no undo step.
    pub fn redraw_from(
        &mut self,
        layer: LayerId,
        source: wgpu::TextureView,
        size: (u32, u32),
        to_source: Affine,
        resample: Resample,
    ) {
        let t = to_source;
        let to_source = [t.a, t.c, t.e, t.b, t.d, t.f, 0.0, 0.0, 1.0];
        self.ops.push(Op::RedrawFrom { layer, source, size, to_source, resample });
    }

    /// Layer `layer`'s pixels for another canvas to read (see [`Canvas::redraw_from`]), as they are
    /// once the work queued is done (done here), given their texture again if they were put away.
    pub fn layer_to_read(&mut self, gpu: &Gpu, doc: &mut Document, layer: LayerId) -> Option<wgpu::TextureView> {
        self.render(gpu, doc, None);
        if !self.layers.contains_key(&layer) {
            self.restore(gpu, layer);
        }
        self.layers.get(&layer).map(|l| l.view.clone())
    }
}

mod composite;
mod ops;
mod pipelines;
mod readback;
#[cfg(test)]
mod tests;

pub use self::pipelines::{
    PendingAlphas, Readback, ReadyToPark, RemakeStats, TipKind, most_resident, read_texture,
    start_reading, textures_bytes,
};
#[allow(unused_imports)]
pub(crate) use self::pipelines::*;
