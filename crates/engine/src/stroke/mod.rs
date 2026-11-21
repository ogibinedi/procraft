//! From input samples to stamps: stabilisation and streamline smoothing, pressure, tilt and speed
//! response, spacing, the shape's placement (rotation, scatter, count, jitter, roundness) and
//! taper.

#[allow(unused_imports)]
use crate::brush::{
    Brush, CLASSIC_TAPER_SPAN, FLOW_POWER, PressureCurve, Rendering, TAPER_TIME_MS, Taper, Tilt, run_out,
};
#[allow(unused_imports)]
use crate::curve::Curve;
#[allow(unused_imports)]
use crate::geom::{IRect, Vec2};
#[allow(unused_imports)]
use crate::input::{Pointer, Sample};
#[allow(unused_imports)]
use crate::studio::{SPACING_FLOOR, SPACING_SPAN};
#[allow(unused_imports)]
use std::collections::VecDeque;
#[allow(unused_imports)]
use std::f64::consts::{PI, TAU};
#[allow(unused_imports)]
use std::sync::Arc;

/// One dab of the brush tip, in canvas pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Stamp {
    pub center: Vec2,
    /// Half the stamp's width.
    pub radius: f32,
    /// 0..1, before the stroke's opacity.
    pub alpha: f32,
    /// Rotation, radians.
    pub angle: f32,
    /// 1 = as drawn; lower squashes the stamp across its rotation.
    pub roundness: f32,
    /// Where a moving grain is read from: the stamp's middle in the grain (canvas pixels at this
    /// stamp's tile), each point of the stamp read that far from it as on the canvas (the grain
    /// doesn't squash with the stamp). A rolling grain goes on from stamp to stamp by as
    /// far as they moved, so it stays put as the stamps move over it, whatever their tile; a
    /// static one moves along with each stamp.
    pub grain_offset: Vec2,
    /// How far (radians) the grain's patch under this stamp turns about the stamp's middle, where
    /// it is read from staying put: the grain's rotation control's share of the stamp's own turn.
    pub grain_turn: f32,
    /// How hard the pen presses the paint into a grain (the pressure, 0..1), and how much that
    /// counts (the brush's pressure bleed and its tilt bleed as far as the pen leans, 0..2):
    /// pressed harder, the paint takes on more of the stamp and grain, its strongest parts first.
    /// The lean's share counts in full whatever the pressure, so the press is what the pressure
    /// keeps of the whole.
    pub grain_press: f32,
    pub grain_bleed: f32,
    /// The shape mirrored across its own vertical and horizontal axes.
    pub flip: [bool; 2],
    /// Its moving grain read mirrored: each point's way from the middle turned over top to bottom
    /// on the canvas before the grain's patch turns. A symmetry's mirrored copy is so, its grain
    /// mirroring with its shape.
    pub grain_mirrored: bool,
    /// How its colour moves from the brush's.
    pub tint: Tint,
    /// How much of the secondary colour it paints with, 0..1.
    pub secondary: f32,
    /// A moving grain's depth and tile size here, as multiples of the brush's.
    pub grain_depth: f32,
    pub grain_size: f32,
    /// How far it fades down its shape's height, from the top (a leaning pen's shading), 0..1.
    pub gradation: f32,
    /// How far a taper has thinned it (its size multiplier, 1 = not at all): a round tip's rim
    /// thins with it, so a tapered stamp is drawn as the whole stamp scaled down.
    pub taper: f32,
    /// What light pressure lets through (the brush's opacity transfer), 0..1: for a capped
    /// rendering the most the stamp builds up to, else already in its alpha.
    pub transfer: f32,
    /// How much of the brush's wet-mix attack this stamp has: the pressure (as opacity follows
    /// it), as far as the attack's tilt switch lets it, 0..1.
    pub attack: f32,
    /// The wet mix as this stamp's pressure scales it, by the brush's own pressure curves: its
    /// grade, blur, blur jitter, wetness jitter and charge (1 each without a curve), its
    /// dilution, wet pull and smudge pull (the pressure as it is without one).
    pub wet: [f32; 8],
    /// When the input that placed it came (ms, the newest sample's time): wet paint is laid a
    /// display frame's stamps at a time.
    pub time_ms: f64,
    /// How far along the path it is (canvas pixels), whatever jitter threw it from there: wet
    /// paint runs out with this.
    pub distance: f32,
    /// For a stroke that builds up gradually (each stamp adding its alpha of what is left,
    /// squared), how many stamps go over a point of its path in one pass; 0 for any other. Each
    /// pixel builds up by how much of it the stamp covers (see [`Stamp::over_alpha`]).
    pub overlap: f32,
    /// Which of its stroke's shape images it is, counted from the stroke's own (0): a brush
    /// engine outside the app picks one for each stamp from the set its brush has.
    pub tip: u32,
    /// How strongly a lightness tip moves the colour's lightness (0..1; see the GPU's tip kinds).
    pub lightness: f32,
    /// A smudge stamp that picks up what lies under where its stroke's last stamp lay (not under
    /// itself), as a colour smudge brush's dabs do.
    pub pick_up: Option<PickUp>,
    /// Its shape made sharp: what is over the first (0..1; 0: as it is) paints fully, what is
    /// under that share of it (the second, 0..1) of it not at all, what lies between as it is.
    pub threshold: [f32; 2],
    /// Which of a symmetry's copies of its stroke it is on (0: the stroke itself): each copy picks
    /// up and carries paint along its own path, as the stroke does.
    pub copy: u8,
}

impl Stamp {
    /// A plain round stamp.
    pub fn round(center: Vec2, radius: f32, alpha: f32) -> Stamp {
        Stamp {
            center,
            radius,
            alpha,
            angle: 0.0,
            roundness: 1.0,
            grain_offset: Vec2::ZERO,
            grain_turn: 0.0,
            grain_press: 1.0,
            grain_bleed: 0.0,
            flip: [false; 2],
            grain_mirrored: false,
            tint: Tint::NONE,
            secondary: 0.0,
            grain_depth: 1.0,
            grain_size: 1.0,
            gradation: 0.0,
            taper: 1.0,
            transfer: 1.0,
            attack: 1.0,
            wet: [1.0; 8],
            time_ms: 0.0,
            distance: 0.0,
            overlap: 0.0,
            tip: 0,
            lightness: 1.0,
            pick_up: None,
            threshold: [0.0; 2],
            copy: 0,
        }
    }

    /// The opacity the stamp needs drawn "over" so that, in a stroke that builds up gradually, a
    /// point it covers fully reaches after its [`overlap`](Stamp::overlap) of stamps what the rule
    /// gives (its alpha in any other stroke).
    pub fn over_alpha(&self) -> f32 {
        let alpha = self.alpha.clamp(0.0, 1.0);
        if self.overlap <= 1.0 || !self.overlap.is_finite() {
            return alpha;
        }
        let n = self.overlap.min(512.0).ceil();
        let mut built = 0.0f32;
        for _ in 0..n as u32 {
            built += alpha * (1.0 - built) * (1.0 - built);
        }
        1.0 - (1.0 - built.min(1.0)).powf(1.0 / n)
    }

    /// Pixels the stamp can touch: its square shape at any rotation.
    pub fn bounds(&self) -> IRect {
        IRect::around(self.center, self.radius * self.roundness.clamp(1.0, MOST_ROUNDNESS) * std::f32::consts::SQRT_2)
    }
}

/// How a stamp's colour moves from the brush's, in hue, saturation and brightness: the hue
/// turned by `hue` (turns), the saturation and the brightness each taken to `x keep + add`
/// (`[keep, add]`), then held to 0..1.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Tint {
    pub hue: f32,
    pub saturation: [f32; 2],
    pub brightness: [f32; 2],
}

impl Tint {
    pub const NONE: Tint = Tint { hue: 0.0, saturation: [1.0, 0.0], brightness: [1.0, 0.0] };

    /// This and `other` together: what each moves the colour by, added.
    pub fn plus(self, other: Tint) -> Tint {
        let [sk, sa] = self.saturation;
        let [ok, oa] = other.saturation;
        let [bk, ba] = self.brightness;
        let [pk, pa] = other.brightness;
        Tint { hue: self.hue + other.hue, saturation: [sk + ok - 1.0, sa + oa], brightness: [bk + pk - 1.0, ba + pa] }
    }

    /// This, then `other` on the colour it gives.
    fn then(self, other: Tint) -> Tint {
        let [sk, sa] = self.saturation;
        let [ok, oa] = other.saturation;
        let [bk, ba] = self.brightness;
        let [pk, pa] = other.brightness;
        Tint { hue: self.hue + other.hue, saturation: [sk * ok, sa * ok + oa], brightness: [bk * pk, ba * pk + pa] }
    }

    /// The hue, saturation and brightness moved on by as much again, whatever the colour.
    fn shifted(self, [hue, saturation, brightness]: [f32; 3]) -> Tint {
        let [sk, sa] = self.saturation;
        let [bk, ba] = self.brightness;
        Tint { hue: self.hue + hue, saturation: [sk, sa + saturation], brightness: [bk, ba + brightness] }
    }
}

/// What the spacing is a share of, for a stamp's square of 1 (measured).
const SPACED_SQUARE: f32 = 262.0 / crate::brushfile::PIXELS_PER_SIZE;

/// A stamp before taper: where it sits on the path, and its size and alpha from the input.
#[derive(Clone, Copy, Debug)]
struct Placed {
    center: Vec2,
    /// How far the lateral and linear jitter threw it from the path, of a reach this far: the
    /// reach goes with the size, so a taper draws it back in.
    thrown: Vec2,
    reach: f32,
    distance: f32,
    diameter: f32,
    alpha: f32,
    angle: f32,
    roundness: f32,
    flip: [bool; 2],
    tint: Tint,
    secondary: f32,
    grain_depth: f32,
    grain_press: f32,
    /// The tilt bleed as far as the pen leans (it adds to the pressure bleed).
    bleed_lean: f32,
    /// Where its moving grain is read from, in tiles (see [`Stamp::grain_offset`]), and how far
    /// the patch read turns (see [`Stamp::grain_turn`]).
    grain_read: Vec2,
    grain_turn: f32,
    /// The square its grain's tile goes by (as far as it follows the stamp's size), and the
    /// grain's zoom and what the scale's pressure curve leaves of its scale.
    grain_square: f32,
    grain_zoom: f32,
    grain_scale_by: f32,
    gradation: f32,
    transfer: f32,
    attack: f32,
    wet: [f32; 8],
    time_ms: f64,
    /// The pressure as the size, opacity, bleed and roundness took it (and the stamp's own).
    pressed: [f32; 5],
    /// How much of the stamp's roundness is the drawn shape's, which the pressure squeezes (the
    /// rest is what leaning adds).
    drawn_share: f32,
    /// What the input's speed left of the size (see [`StrokeBuilder::sized`]).
    speed_size: f32,
    /// See [`Stamp::overlap`].
    overlap: f32,
}

/// Where the walk along the path stood as a piece of it began: enough to walk on again from there.
#[derive(Clone, Copy, Debug)]
struct WalkState {
    head: Option<PathPoint>,
    length: f32,
    carry: f32,
    pending_gap: Option<f32>,
    last_seg: f32,
    heading: Vec2,
    direction: f32,
    random: Random,
    grain_last: Option<(Vec2, Vec2)>,
    dab_turn: f32,
    /// How many stamps the stroke had, counting those a copy leaves out.
    stamps: usize,
}

/// A piece of the path as it was walked (see [`StrokeBuilder::walk_piece`]), when, and the walk
/// as it stood before it.
#[derive(Clone, Copy, Debug)]
struct Walked {
    to: PathPoint,
    whole: Option<f32>,
    now_ms: f64,
    before: WalkState,
}

/// What the tapers leave of a stamp at a place along the path (see [`StrokeBuilder::tapered`]).
#[derive(Clone, Copy, Debug)]
struct Tapered {
    /// The shares of its size (what leaning grows it by aside) and of its alpha they leave.
    size: f32,
    alpha: f32,
    /// The share of its whole width left: what leaning grows it by stays.
    thinned: f32,
    /// Its roundness after over before, where they change it.
    round: Option<(f32, f32)>,
    /// The pressure its grain takes, where the pressure fades into them.
    grain_press: Option<f32>,
}

#[derive(Clone, Copy, Debug)]
struct PathPoint {
    pos: Vec2,
    pressure: f32,
    /// The pressure as size, opacity, bleed, roundness and the stamp's other controls each
    /// follow it (see `soften`).
    soft: [f32; 5],
    tilt: f32,
    azimuth: f32,
    /// How far the barrel is turned, radians.
    roll: f32,
    /// How fast the input moves, 0..1 of [`FAST`].
    speed: f32,
}

impl PathPoint {
    fn lerp(self, to: PathPoint, t: f32) -> PathPoint {
        PathPoint {
            pos: self.pos.lerp(to.pos, t),
            pressure: self.pressure + (to.pressure - self.pressure) * t,
            soft: [0, 1, 2, 3, 4].map(|k| {
                let (a, b) = (self.soft.get(k).copied().unwrap_or(1.0), to.soft.get(k).copied().unwrap_or(1.0));
                a + (b - a) * t
            }),
            tilt: self.tilt + (to.tilt - self.tilt) * t,
            azimuth: to.azimuth,
            roll: to.roll,
            speed: self.speed + (to.speed - self.speed) * t,
        }
    }
}

/// Speed for the speed dynamics, from how far the pen moves on screen in a frame of a 60 Hz
/// display (points): none up to `SLOW`, then rising (as the square root) to full `SPAN` further
/// on. Both scale with the input's speed distance.
const SLOW: f32 = 25.0;
const SPAN: f32 = 100.0;
/// How many frames the speed is smoothed over.
const SPEED_FRAMES: f32 = 3.0;
/// The frames between two samples with the same time: a sample's at the input's rate (60 over
/// the points a second, a pen's 240 and a finger's 120).
fn sample_frames(pen: bool) -> f32 {
    (60.0 / if pen { PEN_RATE } else { FINGER_RATE }) as f32
}
/// The input rates stabilisation, motion filtering and pressure smoothing are tuned for, samples a
/// second: a pen's and a finger's. They work per sample at these rates; at others each sample
/// counts for the time it covers, so they feel the same however fast the input comes.
const PEN_RATE: f64 = 240.0;
const FINGER_RATE: f64 = 120.0;
/// Samples that come closer together than this (milliseconds) came in one batch, timed as they
/// were handed over rather than as they were made (a desktop's pointer history): each stands for
/// a sample at the rates above, and those streamline still holds share the time since the sample
/// before them evenly, as the input they stand for came at a steady rate.
const BATCHED_MS: f64 = 0.5;

/// How much of where it was the path keeps at each point at the rates above, for an `amount` of
/// streamline: strong from the first, as measured (a corner at 600 points a second is cut 18 at
/// 60%, 52 at the most).
fn streamline_keep(amount: f32) -> f32 {
    0.96 * amount.clamp(0.0, 1.0).powf(0.125)
}

/// Streamline keeps a stroke's newest points this long (milliseconds) before they are settled: over
/// them it comes up to the input, more the newer they are, so the path curves in to the pen.
const STREAMLINE_WINDOW_MS: f64 = 50.0;

/// The same for the pressure, `amount` of its streamline.
fn pressure_keep(amount: f32) -> f32 {
    0.999 * amount.clamp(0.0, 1.0).powf(0.0625)
}

/// Motion filtering's weights for a point and those either side of it (an odd count, adding up to
/// one), `amount` and `expression` 0..1, at `rate` samples a second. A windowed low-pass, but only
/// how much of each frequency it lets through is kept, so nothing is moved along: the path doesn't
/// lag or cut inside a curve.
fn motion_weights(amount: f32, expression: f32, rate: f64) -> Vec<f32> {
    let strength = f64::from(amount.clamp(0.0, 1.0)).powf(MOTION_POWER);
    let cut = rate * (1.0 - strength) + MOTION_LOWEST_CUT * strength;
    let longer = 1.0 + (MOTION_EXPRESSION_LONGEST - 1.0) * f64::from(expression.clamp(0.0, 1.0));
    let wanted = rate / cut * longer;
    let size = (wanted.ceil() as usize).next_power_of_two().clamp(2, MOTION_MOST_SAMPLES);
    let span = wanted.min((size - 1) as f64);
    let band = 2.0 * cut / rate;
    let filter: Vec<f64> = (0..span as usize)
        .map(|i| {
            let x = (i as f64 + 1.0 - span / 2.0) * band * PI;
            let sinc = if x == 0.0 { 1.0 } else { x.sin() / x };
            // a Blackman-Harris window
            let t = TAU * i as f64 / span;
            band * sinc * (0.35875 - 0.48829 * t.cos() + 0.14128 * (2.0 * t).cos() - 0.01168 * (3.0 * t).cos())
        })
        .collect();
    let circle: Vec<(f64, f64)> = (0..size).map(|k| (TAU * k as f64 / size as f64).sin_cos()).collect();
    let turn = |k: usize| circle.get(k & (size - 1)).copied().unwrap_or((0.0, 1.0));
    let through: Vec<f64> = (0..=size / 2)
        .map(|k| {
            let (mut re, mut im) = (0.0, 0.0);
            for (n, h) in filter.iter().enumerate() {
                let (sin, cos) = turn(k * n);
                re += h * cos;
                im += h * sin;
            }
            f64::hypot(re, im)
        })
        .collect();
    // back from frequencies to samples, each way from the point
    let side: Vec<f64> = (0..=size / 2)
        .map(|j| {
            let wave = |(k, t): (usize, &f64)| t * turn(j * k).1 * if k == 0 || k == size / 2 { 1.0 } else { 2.0 };
            through.iter().enumerate().map(wave).sum()
        })
        .collect();
    let least = side.first().map_or(0.0, |w| w.abs() * 1e-5);
    let reach = side.iter().rposition(|w| w.abs() > least).unwrap_or(0);
    let all: Vec<f64> = side.iter().take(reach + 1).rev().chain(side.iter().skip(1).take(reach)).copied().collect();
    let sum: f64 = all.iter().sum();
    all.iter().map(|w| (w / sum) as f32).collect()
}

/// What a speed control of `amount` leaves of a size or opacity at `speed` (0..1): faster input
/// takes away as far as the amount says; an amount below 0, slower input does (as measured).
fn by_speed(amount: f32, speed: f32) -> f32 {
    let speed = speed.clamp(0.0, 1.0);
    if amount >= 0.0 { 1.0 - amount * speed } else { 1.0 + amount * (1.0 - speed) }
}

/// Stabilisation averages each point with those either side of it: at full strength this many
/// samples' worth each way (at the rate above), fewer as the strength to this power falls.
const MOST_AVERAGED: f32 = 120.0;
const STABILIZATION_POWER: f32 = 2.2;
/// Motion filtering lets through what moves slower than its cut and takes out what shakes faster:
/// at full strength the cut is this, in hertz. Below that it rises to the input's own rate with
/// the strength to this power, so a little is strong already.
const MOTION_LOWEST_CUT: f64 = 1.0 / 16.0;
const MOTION_POWER: f64 = 0.01;
/// How many times longer full expression makes the filter: longer cuts sharper, so more of the
/// hand's own motion under the cut comes through.
const MOTION_EXPRESSION_LONGEST: f64 = 8.0;
/// The most samples the filter may span.
const MOTION_MOST_SAMPLES: usize = 1024;
/// Brush files keep the spacing jitter and the speed's spacing at ten times the studio's
/// controls: the share of a gap they widen it by.
const SPACING_FILE_SCALE: f32 = 10.0;

/// The most a stamp's height may be of its width (a roundness jitter can make it taller than
/// wide).
pub const MOST_ROUNDNESS: f32 = 20.0;

/// The least a brush's roundness goes, as its control sets it: its stamps are never thinner.
pub const LEAST_ROUNDNESS: f32 = 0.05;

/// The lateral and linear jitter as brush files keep them: the control to the power 2.2, times
/// this; each moves a stamp up to that many times half its diagonal (plus a pixel).
pub const JITTER_FILE_SCALE: f32 = 3.0;

/// The brush's controls the builder reads beyond its own fields (see `studio`), read once.
#[derive(Clone, Copy, Debug, Default)]
struct Extras {
    spacing_jitter: f32,
    jitter_lateral: f32,
    jitter_linear: f32,
    falloff: f32,
    count_jitter: f32,
    pressure_roundness: f32,
    tilt_roundness: f32,
    /// The roundness jitter along the stamp's height and across its width.
    roundness_jitter: [f32; 2],
    speed_size: f32,
    speed_opacity: f32,
    speed_spacing: f32,
    stabilization: f32,
    flip_x: bool,
    flip_y: bool,
    /// Colour jitter per stamp and per stroke (hue, saturation, lightness, darkness), and the
    /// colour's response to pressure and tilt (hue, saturation, brightness, −1..1).
    stamp_color: [f32; 4],
    stroke_color: [f32; 4],
    pressure_color: [f32; 3],
    tilt_color: [f32; 3],
    /// How far a moving grain rolls along with the stroke, 0 (static) to 1 (rolling), and whether
    /// each stroke starts somewhere else in the grain.
    grain_movement: f32,
    grain_offset_jitter: bool,
    /// How much of a stamp's own turn a moving grain's patch takes, −1..1.
    grain_rotation: f32,
    /// Light pressure lowering (or, negative, raising) each stamp's flow, −1..1.
    pressure_flow: f32,
    /// How slowly size, opacity, bleed and roundness follow the pressure when the pen moves fast.
    pressure_response: [f32; 5],
    /// Light pressure, and leaning, letting more of a moving grain through (bleed), 0..1.
    pressure_bleed: f32,
    tilt_bleed: f32,
    /// A moving grain's depth varied from stamp to stamp, and the least it goes to, 0..1.
    grain_depth_jitter: f32,
    grain_depth_min: f32,
    /// A moving grain sized with the stamp (0) or fixed (1, cropped).
    grain_zoom: f32,
    /// A grain fixed to the canvas going on stamp by stamp (see [`Brush::grain_each_stamp`]).
    grain_each_stamp: bool,
    /// How blunt the pen's and the finger's taper ends are, 0 (sharp) to 1.
    pen_taper_tip: f32,
    touch_taper_tip: f32,
    /// How far a pen's pressure fades into its tapers, 0..1.
    taper_pressure: f32,
    /// A pen's stroke shows its end tapered as it is drawn.
    tip_animation: bool,
    /// The tapers are the classic kind, as the studio's switch shows (off unless the brush says).
    classic_taper: bool,
    /// The secondary colour mixed in per stamp and per stroke (jitter, 0..1), and by light
    /// pressure and by leaning (−1..1).
    stamp_secondary: f32,
    stroke_secondary: f32,
    pressure_secondary: f32,
    tilt_secondary: f32,
    /// How a leaning pen shades the stamp across its width, 0..1.
    tilt_gradation: f32,
    /// How much a lean's growth depends on the stroke running across the lean (none along it),
    /// 0..1.
    tilt_compression: f32,
    /// The stamp's roundness upright and at the most lean (the roundness tilt), 0..1.
    tilt_roundness_max: f32,
    /// Where each tilt control starts (see [`Tilt::reach`]).
    tilt_angles: TiltAngles,
    /// The tilt switches: leaning (from each one's angle) scales the lateral and linear jitter
    /// and the shape count, pulls the grain depth down to its least, and drives the stamp colour
    /// jitter (hue, saturation, lightness, darkness, secondary) in place of the stamp's own.
    tilt_switches: TiltSwitches,
    /// The brush's own motion filtering and expression, with the app's.
    motion_filtering: f32,
    motion_expression: f32,
    /// The gap per unit of stamp size: the older spacing (version 0) puts stamps 1/√2 as far
    /// apart as the newer.
    spacing_stride: f32,
}

/// Each tilt switch's angle (its dial's share of a quarter turn), none where the switch is off.
#[derive(Clone, Copy, Debug, Default)]
struct TiltSwitches {
    jitter_lateral: Option<f32>,
    jitter_linear: Option<f32>,
    count: Option<f32>,
    grain_depth: Option<f32>,
    /// Hue, saturation, lightness, darkness and secondary.
    color: [Option<f32>; 5],
    /// The wet mix's attack (for a finger, too: as a pen lying flat).
    attack: Option<f32>,
}

/// Each tilt control's angle (its dial's share of a quarter turn).
#[derive(Clone, Copy, Debug, Default)]
struct TiltAngles {
    opacity: f32,
    size: f32,
    gradation: f32,
    bleed: f32,
    roundness: f32,
    /// Hue, saturation and brightness.
    color: [f32; 3],
    secondary: f32,
}

impl Extras {
    fn of(b: &Brush) -> Extras {
        let unit = |k: &str| b.setting(k).clamp(0.0, 1.0);
        let both = |k: &str| b.setting(k).clamp(-1.0, 1.0);
        let switch = |k: &str| (b.setting(k) >= 0.5).then(|| unit(&format!("{k}_angle")));
        Extras {
            spacing_jitter: unit("spacing_jitter"),
            jitter_lateral: unit("jitter_lateral"),
            jitter_linear: unit("jitter_linear"),
            falloff: unit("falloff"),
            count_jitter: unit("count_jitter"),
            pressure_roundness: both("pressure_roundness"),
            tilt_roundness: both("tilt_roundness"),
            roundness_jitter: [unit("roundness_jitter_y"), unit("roundness_jitter_x")],
            speed_size: both("speed_size"),
            speed_opacity: both("speed_opacity"),
            speed_spacing: unit("speed_spacing"),
            stabilization: unit("stabilization"),
            flip_x: unit("flip_x") >= 0.5,
            flip_y: unit("flip_y") >= 0.5,
            stamp_color: ["stamp_hue", "stamp_saturation", "stamp_lightness", "stamp_darkness"].map(unit),
            stroke_color: ["stroke_hue", "stroke_saturation", "stroke_lightness", "stroke_darkness"].map(unit),
            pressure_color: ["pressure_hue", "pressure_saturation", "pressure_brightness"].map(both),
            tilt_color: ["tilt_hue", "tilt_saturation", "tilt_brightness"].map(both),
            grain_movement: unit("grain_movement"),
            grain_offset_jitter: b.setting("grain_offset_jitter") >= 0.5,
            grain_each_stamp: b.grain_each_stamp(),
            grain_rotation: both("grain_rotation"),
            pressure_flow: both("pressure_flow"),
            // the stamp's own pressure follows as the roundness does
            pressure_response: [
                "pressure_size_response",
                "pressure_opacity_response",
                "pressure_bleed_response",
                "pressure_roundness_response",
                "pressure_roundness_response",
            ]
            .map(unit),
            // the control's place to the power 2.2
            pressure_bleed: unit("pressure_bleed").powf(2.2),
            tilt_bleed: unit("tilt_bleed").powf(2.2),
            grain_depth_jitter: unit("grain_depth_jitter"),
            grain_depth_min: unit("grain_depth_min"),
            grain_zoom: unit("grain_zoom"),
            pen_taper_tip: unit("pen_taper_tip"),
            touch_taper_tip: unit("touch_taper_tip"),
            taper_pressure: unit("pen_taper_pressure"),
            tip_animation: b.setting("pen_tip_animation") >= 0.5,
            classic_taper: b.setting("classic_taper") >= 0.5,
            stamp_secondary: unit("stamp_secondary"),
            stroke_secondary: unit("stroke_secondary"),
            pressure_secondary: both("pressure_secondary"),
            tilt_secondary: both("tilt_secondary"),
            tilt_gradation: unit("tilt_gradation"),
            tilt_compression: unit("tilt_size_compression"),
            tilt_roundness_max: unit("tilt_roundness_max"),
            tilt_angles: TiltAngles {
                opacity: unit("opacity_tilt_angle"),
                size: unit("size_tilt_angle"),
                gradation: unit("gradation_tilt_angle"),
                bleed: unit("bleed_tilt_angle"),
                roundness: unit("roundness_tilt_angle"),
                color: ["hue_tilt_angle", "saturation_tilt_angle", "brightness_tilt_angle"].map(unit),
                secondary: unit("secondary_tilt_angle"),
            },
            tilt_switches: TiltSwitches {
                jitter_lateral: switch("jitter_lateral_tilt"),
                jitter_linear: switch("jitter_linear_tilt"),
                count: switch("count_tilt"),
                grain_depth: switch("grain_depth_tilt"),
                color: [
                    "stamp_hue_tilt",
                    "stamp_saturation_tilt",
                    "stamp_lightness_tilt",
                    "stamp_darkness_tilt",
                    "stamp_secondary_tilt",
                ]
                .map(switch),
                attack: switch("attack_tilt"),
            },
            motion_filtering: unit("motion_filtering"),
            motion_expression: unit("motion_expression"),
            spacing_stride: if b.setting("spacing_version") < 0.5 { std::f32::consts::FRAC_1_SQRT_2 } else { 1.0 },
        }
    }
}

/// The path curves through its points in chords this long at most (canvas pixels), and at most
/// this many to a stretch: input this close together walks straight, as it comes.
const CURVE_STEP: f32 = 4.0;
const MOST_PIECES: usize = 64;
/// How close (screen points) a path point's neighbours come for it to be evened out with them in
/// full, and how far for it not to be at all (see [`StrokeBuilder::blended`]): a slow hand's
/// points, a pixel or so apart, even out; a quick stroke's, far apart, keep its curve.
const BLEND_NEAR: f32 = 2.0;
const BLEND_FAR: f32 = 4.0;
/// Input read in whole screen pixels is evened out over this far (screen pixels) of its path
/// either way of each point (see [`StrokeBuilder::destepped`]): far enough for a slow line's steps
/// to even out into its slope, not so far that a slow small curve is flattened.
const STEPPED_REACH: f32 = 12.0;
/// The most a point read in whole pixels is moved (screen pixels) across the way the path goes,
/// and along it: half a pixel, as far as the pen can have been from where it was read either way
/// of a pixel's side, and half a pixel's diagonal. Across is what a line's steps show; at a slow
/// corner it is what rounds it.
const STEPPED_ACROSS: f32 = 0.5;
const STEPPED_ALONG: f32 = std::f32::consts::FRAC_1_SQRT_2;
/// The most points read in whole pixels wait to be evened out, however close together they come.
const STEPPED_WAITING: usize = 64;

/// The shares four points in a row give a place `t` of the way from the second to the third:
/// a quarter, a half and a quarter of the first three at the second (`t` 0), of the last three at
/// the third (1), turning from one to the other over a quarter turn, so a value eased by them
/// changes smoothly from one stretch to the next.
fn knot_weights(t: f32) -> [f32; 4] {
    let (s, c) = (t * std::f32::consts::FRAC_PI_2).sin_cos();
    [(1.0 - s) * 0.25, (1.0 + c) * 0.25, (1.0 + s) * 0.25, (1.0 - c) * 0.25]
}

/// The point at `t` (0 at `p1`, 1 at `p2`) of the centripetal Catmull–Rom curve through four
/// points: through each of them, with no loops or cusps where they bunch up. With no point
/// before `p1` (or after `p2`) it is the parabola through the three there are, so the first and
/// last stretches bend as the path does on from them; with neither, the straight line.
fn catmull_rom(p0: Option<Vec2>, p1: Vec2, p2: Vec2, p3: Option<Vec2>, t: f32) -> Vec2 {
    let knot = |a: Vec2, b: Vec2| a.distance(b).sqrt().max(1e-4);
    let t2 = knot(p1, p2);
    let u = t2 * t;
    let mix = |a: Vec2, b: Vec2, from: f32, to: f32| a * ((to - u) / (to - from)) + b * ((u - from) / (to - from));
    let a2 = mix(p1, p2, 0.0, t2);
    let before = p0.map(|p0| {
        let t0 = -knot(p0, p1);
        mix(mix(p0, p1, t0, 0.0), a2, t0, t2)
    });
    let after = p3.map(|p3| {
        let t3 = t2 + knot(p2, p3);
        mix(a2, mix(p2, p3, t2, t3), 0.0, t3)
    });
    match (before, after) {
        (Some(b1), Some(b2)) => mix(b1, b2, 0.0, t2),
        (Some(b), None) | (None, Some(b)) => b,
        (None, None) => a2,
    }
}

/// What motion filtering holds of the input, or stabilisation of the path streamline has settled:
/// its points, evenly a sample apart at the tuned rate however they came, each to be averaged with
/// those either side once they are known. Past either end they carry on turned half way round
/// about that end, so the average starts and ends where they do, however few points there are.
#[derive(Clone, Debug, Default)]
struct Averaged {
    /// The points from the `gone`th on (those before are out of reach of what is still to come).
    points: Vec<PathPoint>,
    gone: usize,
    /// How many have been averaged and gone on to the path.
    settled: usize,
    /// The input as it last came, and how many samples at the tuned rate it is past the last point.
    input: Option<PathPoint>,
    past: f64,
    /// Past the ends the input comes back the way it went, so the average falls short of them,
    /// instead of going on as it went.
    mirrored: bool,
}

/// Points kept past those stabilisation can still reach before they are let go, to do it seldom.
const AVERAGED_SLACK: usize = 256;

impl Averaged {
    /// All the points so far.
    fn count(&self) -> usize {
        self.gone + self.points.len()
    }

    /// The input came on to `to`, `steps` samples after it last did.
    fn add(&mut self, to: PathPoint, steps: f64) {
        let Some(from) = self.input.replace(to) else {
            self.points.push(to);
            return;
        };
        let mut next = 1.0 - self.past;
        while next <= steps {
            self.points.push(from.lerp(to, (next / steps) as f32));
            next += 1.0;
        }
        self.past = steps - (next - 1.0);
    }

    /// The input ends where it is: that is its last point.
    fn close(&mut self) {
        if self.past > 1e-3
            && let Some(input) = self.input
        {
            self.points.push(input);
            self.past = 0.0;
        }
    }

    /// Point `i`'s position, pressure and softened pressures (of those held, counting on past
    /// either end).
    fn lanes(&self, i: isize) -> [f32; 8] {
        let lanes = |p: &PathPoint| {
            let [a, b, c, d, e] = p.soft;
            [p.pos.x, p.pos.y, p.pressure, a, b, c, d, e]
        };
        let (Some(first), Some(end)) = (self.points.first().map(lanes), self.points.last().map(lanes)) else {
            return [0.0; 8];
        };
        let last = self.points.len() as isize - 1;
        if last == 0 {
            return first;
        }
        if self.mirrored {
            let at = i.rem_euclid(2 * last);
            return self.points.get(at.min(2 * last - at) as usize).map_or(first, lanes);
        }
        // each length of the input past an end goes the way it did, on from where the last ended
        let (laps, at) = (i.div_euclid(last), i.rem_euclid(last));
        let forward = laps.rem_euclid(2) == 0;
        let mut there = self.points.get(if forward { at } else { last - at } as usize).map_or(first, lanes);
        let whole = if forward { laps } else { laps + 1 } as f32;
        for ((v, f), e) in there.iter_mut().zip(first).zip(end) {
            *v = (e - f) * whole + if forward { *v } else { 2.0 * f - *v };
        }
        there
    }

    /// Point `i` (of them all) as held.
    fn place(&self, i: usize) -> isize {
        i.saturating_sub(self.gone) as isize
    }

    /// Point `i` and the `half` either side of it, added up.
    fn sum(&self, i: usize, half: usize) -> [f32; 8] {
        let (at, reach) = (self.place(i), half as isize);
        let mut sum = [0.0f32; 8];
        for j in at - reach..=at + reach {
            for (s, v) in sum.iter_mut().zip(self.lanes(j)) {
                *s += v;
            }
        }
        sum
    }

    /// `sum`, point `i`'s, as the next point's.
    fn slide(&self, sum: &mut [f32; 8], i: usize, half: usize) {
        let (at, reach) = (self.place(i), half as isize);
        for ((s, new), old) in sum.iter_mut().zip(self.lanes(at + reach + 1)).zip(self.lanes(at - reach)) {
            *s += new - old;
        }
    }

    /// Point `i` averaged with the `half` either side of it, `sum` being them all added up.
    fn mean(&self, i: usize, half: usize, sum: [f32; 8]) -> Option<PathPoint> {
        self.at_lanes(i, sum.map(|v| v / (2 * half + 1) as f32))
    }

    /// Point `i` with its position and pressures as `lanes` has them.
    fn at_lanes(&self, i: usize, lanes: [f32; 8]) -> Option<PathPoint> {
        let [x, y, pressure, a, b, c, d, e] = lanes;
        let held = *self.points.get(self.place(i) as usize)?;
        let soft = [a, b, c, d, e].map(|v| v.clamp(0.0, 1.0));
        Some(PathPoint { pos: Vec2::new(x, y), pressure: pressure.clamp(0.0, 1.0), soft, ..held })
    }

    /// Lets go of the points no average still to come reaches.
    fn forget(&mut self, half: usize) {
        let over = (self.settled.saturating_sub(half + 1)).saturating_sub(self.gone);
        if over > AVERAGED_SLACK {
            self.points.drain(..over);
            self.gone += over;
        }
    }
}

/// Streamline is tuned for input at this interval; other rates are scaled to match.
const REFERENCE_INTERVAL_MS: f64 = 1000.0 / 120.0;
/// The most samples at the tuned rate one sample stands for (after a pause, the filters catch up
/// rather than wait).
const MOST_STEPS: f64 = 24.0;
/// When a classic taper ends, the stroke runs on past the last point by this many steps (touch,
/// pen), each this much of the one before and turning this much more than it, and the end taper
/// comes to its tip over them.
const RUN_ON_TOUCH: usize = 4;
const RUN_ON_PEN: usize = 1;
const RUN_ON_SHRINK: f32 = 0.66;
const RUN_ON_TURN: f32 = 1.125;
/// Stamps a stroke may hold (a guard against runaway input, far above any real stroke).
const MAX_STAMPS: usize = 400_000;
/// The pieces of path kept to walk again (see [`StrokeBuilder::walk_again`]) are looked through
/// for those no taper can reach any more once there are this many, and again each time as many
/// more have come as were kept.
const WALKED_LOOKED_THROUGH: usize = 256;
/// How far the path goes before the stroke's direction (which stamps following it turn to) has
/// turned the way it now goes, a share of the brush's size, and at the least in canvas pixels.
const TURN_REACH: f32 = 0.25;
const TURN_REACH_LEAST: f32 = 1.5;

/// A small deterministic random source (xorshift), so a stroke replays identically.
#[derive(Clone, Copy, Debug)]
struct Random(u32);

impl Random {
    /// 0..1.
    fn next(&mut self) -> f32 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        self.0 = x;
        (x >> 8) as f32 / (1u32 << 24) as f32
    }
}

/// What a smudge stamp that picks up behind it does with what it picks up: lays it with a share of
/// its colour mixed in (the stroke's, or its own), or first makes it one colour, the average of
/// what lies within a share of its reach of the middle (weighed by its shape, or not). A clear
/// colour (an erasing brush's) takes that share of the paint away instead; a colour `thin` see-
/// through (0 not at all, 1 clear) mixes in as far as it is opaque. It lays what it carries
/// in place of what is there, or (`over`) over it, so that it never thins what is there. `here`: it
/// picks up where it lies itself, not behind it; `from`: that far from where it lies (canvas
/// pixels) instead, as a clone does; `before`: from the layer as it was before the stroke. What it
/// picks up may come through a `warp` (each pixel from elsewhere under it), and be blurred (`blur`,
/// 0..1 as a smudge's blur) and then sharpened (`sharpen`, how much of the difference from the blur
/// is added) before it is laid. On a stroke that keeps paint with its layer (see the canvas's layer
/// stores), `height` lays the stamp's tip's grey over the height kept there, that opaque (0..1).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct PickUp {
    pub paint_share: f32,
    pub paint: Option<[f32; 3]>,
    pub dull: Option<(f32, bool)>,
    pub clear: bool,
    pub thin: f32,
    pub height: Option<f32>,
    pub over: bool,
    pub here: bool,
    pub from: Option<[f32; 2]>,
    pub before: bool,
    pub warp: Option<Warp>,
    pub blur: f32,
    pub sharpen: f32,
}

/// How a picking-up stamp moves what it picks up: each pixel under it within its shape's outline
/// (`r` from 0 at its middle to 1 at its rim, across and down its own turn and squash) taken from
/// elsewhere, by `kind`: scaled by `amount`, fading to none at the rim (1 / (amount (1 - r) + r)
/// times as far from the middle: above 1 it grows, below 1 it shrinks); turned by `amount` radians
/// at the middle, none at the rim; moved by `shift` (canvas pixels) at the middle, none at the rim;
/// through a lens, `amount` its strength (out: from r (1 + amount r^2) of the radius out, in: from
/// r / (1 + amount r^2)); or each pixel from up to `amount` pixels away at random. `nearest`: the
/// pixel nearest where it comes from, not a mix of the four around it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Warp {
    pub kind: WarpKind,
    pub amount: f32,
    pub shift: [f32; 2],
    pub nearest: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WarpKind {
    Scale = 1,
    Turn = 2,
    Shift = 3,
    LensOut = 4,
    LensIn = 5,
    Scatter = 6,
}

/// A brush engine outside the app's own (a plugin's): it lays a stroke's stamps from its input
/// itself, in place of the brush's settings (see [`StrokeBuilder::with_engine`]).
pub trait OutsideEngine: Send + std::fmt::Debug {
    /// The stamps `samples` add (canvas pixels, each finite; a pen's pressure through the app's
    /// pressure curve, a finger's 1 with no lean).
    fn add(&mut self, samples: &[Sample]) -> Vec<Stamp>;
    /// The stroke's last stamps, as it ends.
    fn finish(&mut self) -> Vec<Stamp>;
}

/// A stroke's outside engine and the stamps it has laid. A copy of the builder (to see how the
/// stroke would go on, or to draw it again as a shape) has none, and lays as the brush says.
#[derive(Debug, Default)]
struct OutsideSlot(Option<Outside>);

#[derive(Debug)]
struct Outside {
    engine: Box<dyn OutsideEngine>,
    stamps: Vec<Stamp>,
    /// The path's length so far (canvas pixels), and its last point.
    length: f32,
    last: Option<Vec2>,
    ended: bool,
}

impl Clone for OutsideSlot {
    fn clone(&self) -> Self {
        OutsideSlot(None)
    }
}

/// Builds one stroke. Feed it samples in canvas pixels as they arrive ([`StrokeBuilder::add`]
/// returns the stamps to draw now), then [`StrokeBuilder::finish`] for the final stamps, which
/// include the end taper and the catch-up to where the pen lifted.
#[derive(Clone, Debug)]
pub struct StrokeBuilder {
    brush: Brush,
    diameter: f32,
    pointer: Pointer,
    /// The pressure (smoothed) and where the path was at the last point all smoothing settled.
    pressure: Option<f32>,
    pressed_from: Option<Vec2>,
    smoothed: Option<PathPoint>,
    /// The points streamline hasn't settled yet, each with how long (milliseconds) it came after
    /// the one before; and how many of the newest came in one batch (see [`BATCHED_MS`]).
    streaming: VecDeque<(PathPoint, f64)>,
    batch: usize,
    last_sample: Option<Sample>,
    /// The time of the sample being added (see [`Stamp::time_ms`]).
    now_ms: f64,
    head: Option<PathPoint>,
    /// The path point before the head, and the newest one not yet walked to: the path curves
    /// through them, each stretch walked once the point after it is known.
    behind: Option<PathPoint>,
    ahead: Option<PathPoint>,
    /// Where the point before the newest was, as it came (see [`StrokeBuilder::blended`]).
    before_ahead: Option<Vec2>,
    /// The canvas shown at this many screen pixels a canvas pixel, if the input comes in whole
    /// screen pixels (see [`StrokeBuilder::read_in_whole_pixels`]).
    whole_pixels: Option<f32>,
    /// The input turned out to come finer than whole pixels: read so from the next samples on (see
    /// [`StrokeBuilder::read_finer`]).
    finer_next: bool,
    /// The path's points as they came in whole pixels, before they are evened out: the first
    /// `steps_settled` gone on to the curve (kept while those still waiting reach them), the rest
    /// waiting for the path to go far enough on past them; and where the last gone on went.
    steps: VecDeque<PathPoint>,
    steps_settled: usize,
    steps_at: Option<Vec2>,
    direction: f32,
    /// The direction a dab has, the path not having gone anywhere (see
    /// [`StrokeBuilder::dab_way`]).
    dab_direction: f32,
    /// The way the path has been going, smoothed over [`TURN_REACH`] of the brush (unit length
    /// once settled; zero before the path has moved), which `direction` is the angle of.
    heading: Vec2,
    /// The length of the last stretch of path walked (canvas pixels), none before the first.
    last_seg: f32,
    /// How much the brush is scaled on this canvas (the size the stroke was asked for over the
    /// brush's own), which leaning's growth takes too.
    scale: f32,
    carry: f32,
    length: f32,
    placed: Vec<Placed>,
    bounds: IRect,
    random: Random,
    extras: Extras,
    /// The input motion filtering holds, and the path stabilisation holds (after streamline); the
    /// speed, smoothed.
    averaged: Averaged,
    stable: Averaged,
    speed: f32,
    /// Whether the speed has been measured (the first move is taken as it is).
    speed_known: bool,
    /// The pressure as each use follows it, at the last point all smoothing settled; and how many
    /// such points so far.
    soft: Option<[f32; 5]>,
    segments: u32,
    /// A sample stroke drawn for show: no speed, and the pressure taken as it comes.
    steady: bool,
    /// The samples are the path itself, pressed as they say: nothing smooths either.
    exact: bool,
    /// Drawn on the studio's pad, and whether the brush previews tilt there.
    pad: Option<bool>,
    /// The samples say nothing of how the pen is turned (the library's sample stroke).
    no_azimuth: bool,
    /// A gap drawn that the last input segment ended before reaching: the next one goes on
    /// with it.
    pending_gap: Option<f32>,
    /// The whole stroke's colour shift, drawn once.
    stroke_shift: Tint,
    /// The stroke's share of the secondary colour, picked as it starts.
    stroke_secondary: f32,
    /// The random turn every stamp of the stroke takes (a randomised shape), picked as it starts.
    stroke_turn: f32,
    /// How far the canvas is turned on screen, for a brush whose shapes keep to the screen.
    screen_turn: f32,
    /// The brush's input style is azimuth and barrel roll: its stamps turn as the pen's barrel
    /// does; set relative to the stroke, from how it was turned as the stroke began, else from
    /// the barrel's own zero (a turn held from before the stroke counts).
    roll_turns: bool,
    roll_relative: bool,
    roll_from: Option<f32>,
    /// Screen points a canvas pixel spans, for the speed dynamics.
    points_per_pixel: f32,
    input: InputSettings,
    /// What the input's points are averaged with: motion filtering's weights. Made as the stroke
    /// starts; empty if there is no motion filtering.
    weights: Option<Arc<[f32]>>,
    /// Where in a moving grain the stroke starts (tiles), and where the last stamp was read from
    /// there and lay on the canvas.
    grain_start: Vec2,
    grain_last: Option<(Vec2, Vec2)>,
    /// When each sample came (milliseconds, always later than the one before) and how far the
    /// input had gone by then (canvas pixels, as it came), for the tapers that last a time.
    history: Vec<(f64, f32)>,
    input_length: f32,
    /// The two input points before the last, for running a classic taper's end on past it.
    before_last: [Option<Vec2>; 2],
    /// How far a finished stroke ran on past its last point (see [`StrokeBuilder::run_on`]).
    ran_on: Option<f32>,
    /// How many stamps have been drawn for good, for a stroke shown with its tip.
    kept: usize,
    /// How many of the stroke's stamps before `placed` a copy leaves out (see
    /// [`StrokeBuilder::open_copy`]).
    left_out: usize,
    /// How far the start taper reaches, once its time has passed: from then on it is what the
    /// stroke keeps (so what is drawn for good stays as it is when the stroke ends).
    start_settled: Option<f32>,
    /// How far along the path each point it has walked through lies (canvas pixels, rising): the
    /// tapers are worked out at them and eased between them (see [`StrokeBuilder::eased`]).
    knots: Vec<f32>,
    /// The engine laying its stamps in place of the brush's settings, if one does.
    outside: OutsideSlot,
    /// The pieces of path walked that a taper may yet reach, to walk again once it is known (see
    /// [`StrokeBuilder::walk_again`]); and how many were kept when last looked through.
    walked: Vec<Walked>,
    walked_kept: usize,
    /// How far the stamps placed before the path had a direction were turned once it had one.
    dab_turn: f32,
    /// The end taper the spacing goes by while the path is walked again: the path's length and
    /// the taper's span (none while it is drawn).
    spacing_end: Option<(f32, f32)>,
    /// The stamps laid as the stroke is drawn stay as they are when it ends (see
    /// [`StrokeBuilder::hold_laid`]).
    laid_held: bool,
    /// The stamps placed before the path had a direction turned with it after they were handed
    /// out (see [`StrokeBuilder::take_turned`]).
    turned: bool,
    /// The stamps placed are kept, not made into the stamps handed out: nothing takes those (a
    /// copy that works out the tip, the path walked again, the walk on to where the pen lifted).
    quiet: bool,
    /// Drawn with its tip ([`StrokeBuilder::add_kept`]): what it has handed out for good is its
    /// first `kept` stamps, else every stamp placed. And the first of those changed since it was
    /// handed out (`usize::MAX`: none), for [`StrokeBuilder::finish_after`].
    tipped: bool,
    changed: usize,
    /// Once it has ended: the path's length and the end taper's span its stamps take.
    ended: Option<(f32, f32)>,
    /// The input's pressure counts though its pointer reports none (see
    /// [`StrokeBuilder::with_given_pressure`]).
    given_pressure: bool,
    /// How far the pressure grows from nothing at the start and falls to nothing at the end
    /// (canvas pixels; see [`StrokeBuilder::with_pressure_tapers`]).
    pressure_tapers: [f32; 2],
}

/// Smoothing and pressure set for every stroke, whatever the brush, on top of the brush's own.
#[derive(Clone, Debug, PartialEq)]
pub struct InputSettings {
    /// The average of the latest points, 0..1 (as the brush's stabilisation).
    pub stabilization: f32,
    /// Shaky movement filtered out, 0..1, and how much of the hand's own motion comes back, 0..1.
    pub motion_filtering: f32,
    pub motion_expression: f32,
    /// Pressure changes smoothed, 0..1.
    pub pressure_smoothing: f32,
    /// What pen pressure becomes before the brush's own curve.
    pub pressure_curve: Curve,
    /// How far input must move for the speed dynamics to count it as fast, as a part of the
    /// usual distance (0.25..1): less makes slower movement fast.
    pub speed_distance: f32,
    /// Motion filtering keeps the stroke's end on the pen; off, the end falls short of it as much
    /// as the filter smooths.
    pub tip_attached: bool,
    /// The pen says how it leans. One that doesn't draws as a finger does: no lean at all (not
    /// even upright's least), and its brush's tilt switches out of the way.
    pub tilt_known: bool,
}

impl Default for InputSettings {
    fn default() -> Self {
        InputSettings {
            stabilization: 0.0,
            motion_filtering: 0.0,
            motion_expression: 0.0,
            pressure_smoothing: 0.0,
            pressure_curve: Curve::linear(),
            speed_distance: 1.0,
            tip_attached: true,
            tilt_known: true,
        }
    }
}

/// `v` turned `by` radians.
fn turned_by(v: Vec2, by: f32) -> Vec2 {
    if by == 0.0 {
        return v;
    }
    let (sin, cos) = by.sin_cos();
    Vec2::new(v.x * cos - v.y * sin, v.x * sin + v.y * cos)
}

/// `angle` (radians) from −π up to π.
fn wrapped(angle: f32) -> f32 {
    use std::f32::consts::{PI, TAU};
    (angle + PI).rem_euclid(TAU) - PI
}

/// Two amounts, 0..1, together: each takes away what the other leaves.
fn together(a: f32, b: f32) -> f32 {
    1.0 - (1.0 - a.clamp(0.0, 1.0)) * (1.0 - b.clamp(0.0, 1.0))
}


mod builder;

#[cfg(test)]
mod tests;
