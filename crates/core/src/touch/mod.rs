//! The canvas's touches, the same for every app: what a pen, a finger or the mouse does on the
//! canvas, fed the platform's pointers and handing back what the app should do about them.
//!
//! A pen, a finger or the mouse paints (or does what Touch and Pen are set to); a finger held
//! still picks a colour (or does what Touch and hold is set to); a stroke held still snaps to a
//! shape, and a finger down meanwhile makes it perfect; two and three fingers tapped undo and redo,
//! and held still keep going; two fingers move, zoom and turn the canvas, and a quick pinch in fits
//! it; three fingers scrubbed clear the layer and swiped down do what Three-finger swipe is set to;
//! four fingers tapped do what Four-finger tap is. The modify button tapped makes the next touch
//! pick a colour; held, a touch does what □ + Touch (□ + Pen) is set to. A snapped shape being
//! edited has its handles, outline and body dragged. With a panel open over the canvas a touch waits:
//! a tap only closes the panel; a drag fades it and goes on from where it came down.
//!
//! Some pens' touches begin while they are still held near the glass, at no pressure: once a pen
//! has given a pressure, one touching at none hovers (its cursor follows it) until it presses, and
//! its touch begins there. One not yet known to give a pressure that touches at none waits a moment
//! for one; none coming, it is taken for a pen that gives none, and paints at full pressure.
//!
//! Positions are window pixels and times the platform's event clock (milliseconds).

#[allow(unused_imports)]
use std::sync::Mutex;
#[allow(unused_imports)]
use std::sync::atomic::{AtomicBool, Ordering};

mod box_pinch;
mod modes;
mod pen;

pub use pen::{HoverCursor, PenHover, PenPreferred, SqueezePhase};

#[allow(unused_imports)]
use crate::adjust::PerspectiveKind;
#[allow(unused_imports)]
use crate::adjust_menu::Adjustment;
#[allow(unused_imports)]
use crate::adjusting::{CLONE_PUCK_RADIUS, CLONE_PUCK_SLOP, DIRECTIONAL_INNER, DIRECTIONAL_REACH, POSITIONAL_GRAB};
#[allow(unused_imports)]
use crate::{CanvasSession, PenSample, PointerKind, ToolKind};

/// A tap: down and up within this, moving no further than [`TAP_SLOP`] points.
const TAP_MS: f64 = 300.0;
/// Liquify held still works on from this long after it stops, as measured; moved less than this
/// (points) it counts as still. Time passes for it, and for what it set going, this often.
const LIQUIFY_HELD_WAIT_MS: f64 = 110.0;
const LIQUIFY_HELD_STEP_MS: f64 = 16.0;
const LIQUIFY_HELD_SLOP: f32 = 3.0;
const TAP_SLOP: f32 = 24.0;
/// How far from an item (points) a tap of the selection tool on a plugin's layer still takes it.
const TAP_REACH: f32 = 6.0;
/// Each of the fingers tapped together stays short of this (points) from where it landed: this
/// far, they are moving the canvas.
const FINGERS_TAP_SLOP: f32 = 15.0;
/// A touch on the canvas with a panel open moved this far (points) is a drag, not a tap.
const PANEL_SLOP: f32 = 10.0;
/// Fingers together on the canvas leave the view as it is until they have gone this far between
/// them (points) from where they landed, so a tap doesn't nudge it: measured over 8, under 12.
const VIEW_SLOP: f32 = 10.0;
/// The single touch companion's Zoom: a drag this far (points) up zooms the canvas in e times.
pub const COMPANION_ZOOM_DISTANCE: f32 = 180.0;

/// The single touch companion's mode: a lone press dragged on the canvas zooms it (up in, about
/// where it went down) or moves it, instead of painting.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum CompanionMode {
    Zoom,
    Move,
}
/// Two (three) fingers held still go on undoing (redoing) every this often.
const RAPID_REPEAT_MS: f64 = 120.0;
/// A pen held over the canvas and not heard from for this long (seconds) has gone.
const PEN_HOVER_STALE_S: f32 = 0.5;
/// After the pen leaves (lifts, or stops being held over the canvas), a finger landing alone is
/// still taken for the hand that held it for this long (seconds).
const PEN_GONE_S: f32 = 0.5;
/// A pen has given a pressure over nothing since the app started: each artwork's touches begin
/// knowing it.
static PEN_PRESSURE: AtomicBool = AtomicBool::new(false);
/// A pen not yet known to give a pressure that touches at none waits this long (its input's
/// milliseconds) for one: one coming then begins its stroke there, as for a pen known to give one
/// (some pens' touches begin above the glass); none by then, or none as it lifts, and it is taken
/// for a pen that gives none, which paints at full pressure from where it touched.
const PEN_PRESSURE_WAIT_MS: f64 = 150.0;
/// While the pen is near, fingers landing within this long (milliseconds) of each other make a
/// gesture; one landing alone is a hand resting on the screen.
const TOGETHER_MS: f64 = 100.0;
/// Two fingers parting while they close faster than this (their spread, as a part of where it
/// started, a second) fit the canvas if it is pinched smaller than it fits; the speed is taken
/// over this long (milliseconds) before they part.
const QUICK_PINCH_SPEED: f32 = -2.5;
const QUICK_PINCH_WINDOW_MS: f64 = 40.0;
/// Three fingers turning back and forth this often clear the layer, each leg this far (points).
const SCRUB_TURNS: i32 = 3;
const SCRUB_STEP: f32 = 24.0;
/// Three fingers swiped down this far (points), mostly straight.
const SWIPE_DOWN: f32 = 70.0;
/// A stroke counts as still within this many points.
const QUICKSHAPE_STILL: f32 = 6.0;
/// A snapped shape being edited: a handle within this takes a drag, and its outline within this.
const QUICKSHAPE_GRAB: f32 = 22.0;
/// A shape's node stays where it is until the finger has gone this far (points), and then goes as
/// it does, that far behind it.
const NODE_SLOP: f32 = 10.0;
/// A fill's touch slid past this (points) changes its threshold, this much more gently than a
/// selection's.
const POUR_SLOP: f32 = 15.0;
pub(crate) const POUR_SLIDE_GENTLER: f32 = 2.7;

/// A transform's touch moved this far (points) drags what it took (a selection's too).
const TRANSFORM_SLOP: f32 = 15.0;
/// A touch on perspective blur's puck moved this far (points) drags it.
const PUCK_SLOP: f32 = 10.0;
/// A touch on the guide editor's canvas moved this far (points) drags the handle it took; less,
/// and it was a tap (on a handle, its menu; elsewhere, a perspective guide's new point).
const GUIDE_SLOP: f32 = 10.0;
/// A tap this near a freehand selection's first point closes it; its points are added this far
/// apart (points).
const FREEHAND_CLOSE: f32 = 22.0;
const FREEHAND_STEP: f32 = 2.0;
/// How far above a finger (or a mouse) the eyedropper's loupe is centred (points), so the finger
/// doesn't hide it; a pen's is centred on its tip (see [`CanvasSession::pick_loupe_lift`]).
pub const LOUPE_LIFT: f32 = 40.0;

/// How the selection tool selects.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SelectKind {
    Automatic,
    Freehand,
    Rectangle,
    Ellipse,
}

/// The selection tool is open: how it selects, whether a new selection adds to the one there is
/// or takes away from it, and where a colour selection's threshold starts (0..1).
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct SelectTouch {
    pub kind: SelectKind,
    pub join: crate::SelectionJoin,
    pub threshold: f32,
}

/// A transform is open: touches work its box (a warp's control points shown as the advanced mesh
/// has them, or only its corners).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TransformTouch {
    pub advanced_mesh: bool,
}

/// What Continue filling fills with: a colour (sRGB, 0..1) and the threshold it starts at.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct Fill {
    pub r: f32,
    pub g: f32,
    pub b: f32,
    pub threshold: f32,
}

/// What a way of touching is set to do (Gesture controls).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum GestureAction {
    Smudge,
    Erase,
    Assisted,
    Eyedropper,
    QuickShape,
    QuickMenu,
    FullScreen,
    ClearLayer,
    CopyPaste,
    LayerSelect,
}

/// A pointer's move within an input event: which, and its input since the last.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TouchMove {
    pub id: u64,
    pub samples: Vec<PenSample>,
}

/// The gesture settings the touches follow: what each way of touching does, and the delays.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct TouchSettings {
    /// One finger paints as the pen does.
    pub finger_paint: bool,
    /// One finger, not painting, moves the canvas once past a tap's slop.
    pub one_finger_pan: bool,
    /// Two- and three-finger taps undo and redo (off, they still do while a transform, the
    /// selection tool or liquify is open).
    pub undo_taps: bool,
    /// Two fingers turn the canvas as they zoom it.
    pub rotate: bool,
    /// One finger paints a model, and turns it with the modify button held (else it turns it).
    pub model_finger_paint: bool,
    /// Pausing at the end of a stroke snaps it to a shape.
    pub draw_and_hold: bool,
    pub touch: Option<GestureAction>,
    pub pen: Option<GestureAction>,
    pub hold: Option<GestureAction>,
    pub modify_touch: Option<GestureAction>,
    pub modify_pen: Option<GestureAction>,
    pub four_tap: Option<GestureAction>,
    pub three_swipe: Option<GestureAction>,
    /// The pen's first and second buttons (the first, set to nothing, switches to the eraser and
    /// back).
    pub pen_button: Option<GestureAction>,
    pub pen_second_button: Option<GestureAction>,
    /// Three fingers rubbed back and forth clear the layer.
    pub scrub: bool,
    /// While the pen hovers over the canvas, a finger dragged across moves the brush's opacity and
    /// two pinched its size.
    pub hover_touch: bool,
    pub hold_ms: f64,
    pub quickshape_ms: f64,
    pub rapid_undo_ms: f64,
    /// A mouse, or a touchpad moving the pointer, does what a finger is set to do (a tablet's
    /// way): with painting with a finger off it paints nothing, on a shown model it turns the
    /// model, and an adjustment's Pencil leaves it to drag the amount. Off, it paints as the pen
    /// does (a desktop's drawing tool). Either way it has no pressure and is never taken for a
    /// hand resting by the pen.
    #[uniffi(default = false)]
    pub pointer_follows_touch: bool,
    /// What the pen's first and second buttons are set to do in Pen buttons, a QuickMenu action
    /// each, in place of what Gesture controls say; `None`, what Gesture controls say.
    #[uniffi(default = None)]
    pub pen_button_does: Option<crate::QuickAction>,
    #[uniffi(default = None)]
    pub pen_second_button_does: Option<crate::QuickAction>,
}

impl Default for TouchSettings {
    /// As the app ships.
    fn default() -> TouchSettings {
        TouchSettings {
            finger_paint: true,
            one_finger_pan: false,
            undo_taps: true,
            rotate: true,
            model_finger_paint: false,
            draw_and_hold: true,
            touch: None,
            pen: None,
            hold: Some(GestureAction::Eyedropper),
            modify_touch: Some(GestureAction::Eyedropper),
            modify_pen: Some(GestureAction::Eyedropper),
            four_tap: Some(GestureAction::FullScreen),
            three_swipe: Some(GestureAction::CopyPaste),
            pen_button: None,
            pen_second_button: None,
            scrub: true,
            hover_touch: false,
            hold_ms: 400.0,
            quickshape_ms: 650.0,
            rapid_undo_ms: 650.0,
            pointer_follows_touch: false,
            pen_button_does: None,
            pen_second_button_does: None,
        }
    }
}

/// What the touches ask of the app (the canvas itself they paint and move on their own).
#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum TouchCommand {
    Undo,
    Redo,
    /// A pen button set to a QuickMenu action in Pen buttons (other than Undo and Redo, which come
    /// as themselves) was pressed with the pen at (x, y): the app does it, as the QuickMenu's
    /// button would.
    Run {
        action: crate::QuickAction,
        x: f32,
        y: f32,
    },
    ClearLayer,
    ToggleBars,
    /// The canvas settles to fit the window.
    Fit,
    CopyPaste,
    QuickMenu {
        x: f32,
        y: f32,
    },
    LayerSelect {
        x: f32,
        y: f32,
    },
    /// The last stroke becomes a shape.
    QuickShapeLast,
    /// The eyedropper is under (x, y); then it lets go (its colour taken) or is called off.
    Pick {
        x: f32,
        y: f32,
    },
    PickDone,
    PickCancel,
    /// The stroke held still snapped to a shape; the shape changed (dragged, a finger down); the
    /// shape was let go of as it is.
    ShapeSnapped,
    ShapeChanged,
    ShapeEnded,
    /// A stroke finished.
    StrokeEnded,
    /// The view moved (two fingers).
    ViewMoved,
    /// The view moves into place on its own for a moment (let go near a right angle, it turns onto
    /// it): what is drawn over the canvas follows it.
    ViewSettles,
    /// The brush's outline is at (x, y), or goes.
    Cursor {
        x: f32,
        y: f32,
    },
    CursorGone,
    /// A pen known to give a pressure touches at none: it is held near the glass, hovering at
    /// (x, y) until it presses (one not known to give a pressure waits so for a moment, then
    /// paints at full pressure). The app shows it as it shows the pen hovering (the touches have
    /// been told it hovers); [`TouchCommand::CursorGone`] follows if it lifts away unpressed.
    PenHovers {
        x: f32,
        y: f32,
    },
    /// A stroke began on a text layer, which takes no paint until it is rasterized.
    TextLayer,
    /// The brush's size or opacity changed (Hover's touch): its sliders show it again.
    BrushChanged,
    /// Recolor's reticle dragged to window pixel (`x`, `y`)
    /// ([`CanvasSession::recolor_move_to`]), or a tap there starting it again
    /// ([`CanvasSession::recolor_tap`]): the app moves it, at once or on its fill thread.
    RecolorMoved {
        x: f32,
        y: f32,
    },
    RecolorTapped {
        x: f32,
        y: f32,
    },
    /// Continue filling: a touch slid the fill's threshold to this (keep it for the next, and redo
    /// the fill with it, `pour_threshold`, when next drawing: it redoes the whole fill, so once a
    /// frame at most), and the fill was let go of (kept, at the threshold it was let go at).
    FillThreshold {
        threshold: f32,
    },
    Filled,
    /// A transform's touch came down (its popovers close); the box as it now is; the node held and
    /// where (window pixels), and let go of there (a rotate or box-turning node springs back); a
    /// node tapped, whose numbers are typed beside it (`angle` for the rotate node, else the size),
    /// at (`x`, `y`), on the box's `right` side or its left.
    TransformTouched,
    TransformMoved {
        transform: Option<crate::TransformBox>,
    },
    TransformHeld {
        handle: crate::TransformHandle,
        x: f32,
        y: f32,
    },
    TransformLetGo {
        handle: crate::TransformHandle,
        x: f32,
        y: f32,
    },
    TransformNumeric {
        angle: bool,
        x: f32,
        y: f32,
        right: bool,
    },
    /// The selection tool: a rectangle or ellipse being dragged out between two window points; a
    /// colour selection's threshold slid (shown with [`CanvasSession::show_colour_selection`]
    /// while not `done`, kept for the next when it is); the freehand path changed (see
    /// [`CanvasSession::freehand_path`]); a selection made.
    SelectionDrag {
        x0: f32,
        y0: f32,
        x1: f32,
        y1: f32,
    },
    SelectionThreshold {
        x: f32,
        y: f32,
        threshold: f32,
        done: bool,
    },
    FreehandChanged,
    SelectionChanged,
    /// An adjustment open: its amount changed (see [`CanvasSession::adjustment_shown`]); a tap at
    /// window point (`x`, `y`) asks for its menu.
    AdjustChanged,
    AdjustMenu {
        x: f32,
        y: f32,
    },
    /// The colour panel opens (or closes); the brush library: what the pen's own settings on the
    /// platform ask its double-tap or squeeze to show ([`crate::PenPreferred`]).
    ShowColours,
    ShowBrushes,
    /// A touch held for an open panel ([`CanvasSession::set_touch_panel_open`]) became a drag: the
    /// panel fades away, and the touch goes on. It was a tap: the panel closes, and nothing else
    /// happens. (Held still, it does what a hold does, the panel staying.)
    PanelFades,
    PanelTapped,
    /// The guide editor ([`CanvasSession::set_touch_guide_editing`]): a touch came down on its
    /// canvas (its popovers close); what it took was dragged, or a tap gave a perspective guide a
    /// new point. Either way the guide reads anew.
    GuideTouched,
    GuideChanged,
    /// A tap on one of the guide editor's handles, at window point (`x`, `y`): a perspective
    /// guide's point (`point`, its place) offers Delete ([`CanvasSession::remove_vanishing_point`]),
    /// the centre of the others Reset ([`CanvasSession::reset_guide_place`]).
    GuideNodeTapped {
        point: Option<u32>,
        x: f32,
        y: f32,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Mode {
    None,
    /// A picture being placed on a model follows the touch.
    Picture,
    Pen,
    Finger,
    /// Down, doing nothing (a touch that only waits for a hold, or did its thing).
    Idle,
    Gesture,
    Pick,
    /// An edited shape's handle dragged; its outline dragged (turning and resizing it); itself moved.
    Shape(u32),
    ShapePin,
    ShapeMove,
    /// Continue filling: the touch filled where it landed, and slides the threshold.
    Pour,
    /// A transform's touch: it took a node (or the content), and drags it once past the slop.
    Transform,
    /// The selection tool's touch.
    Select,
    /// An adjustment open: the touch drags its amount (or a motion blur, or chromatic aberration's
    /// fringes) across the canvas; a tap opens its menu.
    Adjust,
    /// Cloning, the source dragged.
    Puck,
    /// Layer Opacity: the touch drags the layer's opacity across the canvas.
    Opacity,
    /// Recolor is on: the touch drags its reticle about, or (a tap) starts it again where it lands.
    Recolor,
    /// The companion's Zoom or Move: the press zooms or moves the canvas; others meanwhile do
    /// nothing.
    Companion,
    /// A finger on a shown model: it turns the model.
    Turn,
    /// Hover's touch: a finger dragging the opacity, two pinching the size.
    Hover,
    /// A panel is open: the touch waits to be a tap or a drag, doing nothing yet.
    Held,
    /// The guide editor's touch: it drags the guide's handle it took, if any.
    Guide,
}

/// A drag on perspective blur's puck: it moves the puck (held this far from its centre, window
/// pixels) or turns its arrow.
#[derive(Clone, Copy, Debug, PartialEq)]
enum PuckDrag {
    Move(f32, f32),
    Turn,
}

#[derive(Clone, Copy, Debug)]
struct Down {
    id: u64,
    at: (f32, f32),
    /// Where it landed.
    from: (f32, f32),
}

/// A touch waiting for an open panel: its kind, its first input, and its input since.
struct Held {
    kind: PointerKind,
    first: Vec<PenSample>,
    moves: Vec<PenSample>,
}

/// The touches' state: kept by the session, apart from the canvas's (they call into it).
pub(crate) struct Touches {
    settings: TouchSettings,
    mode: Mode,
    down: Vec<Down>,
    stroke_pointer: u64,
    /// The tool chosen before this stroke used another (Touch set to erase), to put back after.
    tool_before: Option<ToolKind>,
    /// The tool the pen's button left for the eraser, to go back to.
    tool_before_eraser: Option<ToolKind>,
    /// The pen's button held down to erase (Erase's Pen button): see [`pen::HeldEraser`].
    held_eraser: Option<pen::HeldEraser>,
    assisted: bool,
    down_at: (f32, f32),
    /// When the last finger of a tap landed (the only one, until another does), when the first
    /// of them lifted, how many there were, and how far they have gone (see
    /// [`CanvasSession::note_fingers_moved`]).
    tap_start: f64,
    tap_lifted: Option<f64>,
    tap_pointers: usize,
    tap_moved: f32,
    /// The fingers down have tapped, or held still until they repeat: nothing more until all
    /// are up.
    tap_done: bool,
    /// The two fingers' spread as a part of where it started, and its recent values with their
    /// times (the first at or before the speed's window).
    gesture_scale: f32,
    /// The fingers have gone far enough to move the view ([`VIEW_SLOP`]).
    view_moving: bool,
    /// Others of the fingers moving in this event are still to be told of.
    more_to_come: bool,
    pinch: Vec<(f64, f32)>,
    /// A plugin keeps two-finger gestures from turning the canvas, as it said when the last finger
    /// landed (the gesture lock's hook).
    turn_locked: bool,
    /// A plugin keeps a quick pinch in from fitting the canvas, as it said then too.
    fit_locked: bool,
    gesture_pair: Option<(u64, u64)>,
    last: (f32, f32, f32, f32),
    scrub_x: f32,
    scrub_direction: i32,
    scrub_travel: f32,
    scrub_turns: i32,
    swipe_start: (f32, f32),
    swiped: bool,
    hold_at: (f32, f32),
    hold_due: Option<f64>,
    quickshape_due: Option<f64>,
    rapid_due: Option<f64>,
    /// The modify button: tapped, the next touch picks a colour; held, touches do what it sets.
    pick_next: bool,
    /// The eyedropper open now was opened by a pen (its loupe on the pen's tip).
    pick_pen: bool,
    modify_held: bool,
    modify_used: bool,
    /// The touch made while the modify button is held, and what it does.
    modify_pointer: Option<(u64, GestureAction)>,
    /// A snapped shape's handles show.
    shape_editing: bool,
    shape_dragged: bool,
    shape_last: (f32, f32),
    /// Continue filling is on: each touch fills; the fill's threshold as slid, and the touch's
    /// last place and time.
    filling: Option<Fill>,
    /// The selection tool is open; its touch has moved past the slop; a freehand path held still
    /// snapped to a shape; the colour selection's threshold as slid; the freehand path (canvas
    /// pixels).
    selecting: Option<SelectTouch>,
    select_moved: bool,
    lasso_snapped: bool,
    /// An adjustment's touch went past the slop (it is not a tap), and has begun displacing;
    /// liquify's brush, when it last moved and when time last passed for it, and whether a pen
    /// holds it.
    adjust_moved: bool,
    adjust_displacing: bool,
    liquify_ms: f64,
    liquify_tick_ms: f64,
    liquify_pen: bool,
    /// Where liquify's brush came to rest and when, how long (seconds) it has worked on there, and
    /// when it next does.
    liquify_rest: (f32, f32),
    liquify_rest_ms: f64,
    liquify_held: f32,
    liquify_due: Option<f64>,
    /// Where on the clone source it was taken (window pixels from its centre).
    puck_grab: (f32, f32),
    /// What the touch does to perspective blur's puck, and whether it has started.
    puck_drag: Option<PuckDrag>,
    puck_dragging: bool,
    select_threshold: f32,
    freehand: Vec<(f32, f32)>,
    /// A transform is open; what its touch took, and whether it has started dragging it.
    transforming: Option<TransformTouch>,
    transform_handle: crate::TransformHandle,
    transform_grabbed: bool,
    /// Two fingers came down with one on the box: what it holds is theirs, once they've gone a
    /// little way (until then they may yet be a tap); then where they took it.
    box_pinch: Option<Option<[(f32, f32); 2]>>,
    pour_moved: bool,
    pour_last: (f32, f64),
    /// Layer Opacity is on: the layer a drag changes and its opacity as dragged; whether the
    /// touch has changed it yet.
    layer_opacity: Option<(u64, f32)>,
    opacity_moved: bool,
    /// Recolor's touch: past a tap's slop, and where the reticle was (window pixels) as it went down.
    recolor_dragged: bool,
    recolor_from: (f32, f32),
    /// The companion's Zoom or Move, while on, and where its press last was.
    companion: Option<CompanionMode>,
    companion_last: (f32, f32),
    /// Where the finger turning a model last was.
    turn_last: (f32, f32),
    /// The lone finger set to move the canvas: where it last was, and whether it has moved past
    /// a tap's slop (it moves the canvas from then on).
    pan_last: Option<(f32, f32)>,
    panned: bool,
    /// When the pen was last told held over the canvas (none: it has left), and Hover's touch:
    /// where its finger last was (window x) and how far apart two fingers last were.
    pen_hovering: Option<std::time::Instant>,
    /// The pen on the canvas, when it last lifted (its input's time, milliseconds), and when it was
    /// last no longer held over it.
    pen_pointer: Option<u64>,
    pen_lifted_ms: Option<f64>,
    pen_left: Option<std::time::Instant>,
    /// While the pen is near: a finger that landed alone, waiting for another to land with it (its
    /// kind and first input), and the hand's touches, which do nothing until they lift.
    palm_wait: Option<(u64, PointerKind, Vec<PenSample>)>,
    palms: Vec<u64>,
    /// A pen has given a pressure over nothing (in this artwork, or another since the app
    /// started): from then on a pen touching at none is held near the glass, not pressing. One
    /// not yet known to waits [`PEN_PRESSURE_WAIT_MS`] for one (see `pen_waiting`).
    pen_pressure: bool,
    /// A pen not known to give a pressure touched at none for [`PEN_PRESSURE_WAIT_MS`]: pens give
    /// none, and paint at full pressure, until one gives a pressure.
    pen_no_pressure: bool,
    /// The pen touching at no pressure, hovering until it presses: its pointer and kind.
    pen_unpressed: Option<(u64, PointerKind)>,
    /// That pen not known to give a pressure: its input while it waits for one, and when the wait
    /// runs out (its input's milliseconds).
    pen_waiting: Vec<PenSample>,
    pen_wait_due: Option<f64>,
    /// The log has said a pen's touch lifted without ever pressing.
    unpressed_told: bool,
    hover_x: f32,
    hover_spread: f32,
    /// The pen's own gestures: where it hovers, and a squeeze held.
    pen: pen::PenState,
    /// A panel is open over the canvas, and the touch waiting for it to go.
    panel_open: bool,
    held: Option<Held>,
    /// The guide editor is open, what its touch took, and whether the touch has gone past
    /// [`GUIDE_SLOP`].
    guide_editing: bool,
    guide_grab: Option<crate::guide::GuideGrab>,
    guide_moved: bool,
    /// Window pixels a point: how far a touch has to go.
    scale: f32,
    /// Window pixels a point of the interface: how near a touch has to be to a handle it draws.
    handle_scale: f32,
}

impl Default for Touches {
    fn default() -> Touches {
        Touches {
            settings: TouchSettings::default(),
            mode: Mode::None,
            down: Vec::new(),
            stroke_pointer: 0,
            tool_before: None,
            tool_before_eraser: None,
            held_eraser: None,
            assisted: false,
            down_at: (0.0, 0.0),
            tap_start: 0.0,
            tap_lifted: None,
            tap_pointers: 0,
            tap_moved: 0.0,
            tap_done: false,
            gesture_scale: 1.0,
            view_moving: false,
            more_to_come: false,
            pinch: Vec::new(),
            turn_locked: false,
            fit_locked: false,
            gesture_pair: None,
            last: (0.0, 0.0, 0.0, 0.0),
            scrub_x: 0.0,
            scrub_direction: 0,
            scrub_travel: 0.0,
            scrub_turns: 0,
            swipe_start: (0.0, 0.0),
            swiped: false,
            hold_at: (0.0, 0.0),
            hold_due: None,
            quickshape_due: None,
            rapid_due: None,
            pick_next: false,
            pick_pen: false,
            modify_held: false,
            modify_used: false,
            modify_pointer: None,
            shape_editing: false,
            shape_dragged: false,
            shape_last: (0.0, 0.0),
            filling: None,
            selecting: None,
            select_moved: false,
            lasso_snapped: false,
            adjust_moved: false,
            adjust_displacing: false,
            liquify_ms: 0.0,
            liquify_tick_ms: 0.0,
            liquify_pen: false,
            liquify_rest: (0.0, 0.0),
            liquify_rest_ms: 0.0,
            liquify_held: 0.0,
            liquify_due: None,
            puck_grab: (0.0, 0.0),
            puck_drag: None,
            puck_dragging: false,
            select_threshold: 0.5,
            freehand: Vec::new(),
            transforming: None,
            transform_handle: crate::TransformHandle::Move,
            transform_grabbed: false,
            box_pinch: None,
            pour_moved: false,
            pour_last: (0.0, 0.0),
            layer_opacity: None,
            opacity_moved: false,
            recolor_dragged: false,
            recolor_from: (0.0, 0.0),
            companion: None,
            companion_last: (0.0, 0.0),
            turn_last: (0.0, 0.0),
            pan_last: None,
            panned: false,
            pen_hovering: None,
            pen_pointer: None,
            pen_lifted_ms: None,
            pen_left: None,
            palm_wait: None,
            palms: Vec::new(),
            pen_pressure: PEN_PRESSURE.load(Ordering::Relaxed),
            pen_no_pressure: false,
            pen_unpressed: None,
            pen_waiting: Vec::new(),
            pen_wait_due: None,
            unpressed_told: false,
            hover_x: 0.0,
            hover_spread: 0.0,
            pen: pen::PenState::default(),
            panel_open: false,
            held: None,
            guide_editing: false,
            guide_grab: None,
            guide_moved: false,
            scale: 1.0,
            handle_scale: 1.0,
        }
    }
}

/// Where a pointer's samples end up, and when.
fn last_of(samples: &[PenSample]) -> Option<&PenSample> {
    samples.last()
}

fn dist(a: (f32, f32), b: (f32, f32)) -> f32 {
    (a.0 - b.0).hypot(a.1 - b.1)
}

/// Centre, distance and angle of two points.
fn pair(a: (f32, f32), b: (f32, f32)) -> (f32, f32, f32, f32) {
    ((a.0 + b.0) / 2.0, (a.1 + b.1) / 2.0, dist(a, b), (b.1 - a.1).atan2(b.0 - a.0))
}

/// How much an amount dragged across the screen changes for a step of `dx` points over `ms`: more
/// for faster drags (the speed taken per 60 Hz frame), 200 points at a steady pace moving it most
/// of the way.
pub(crate) fn drag_step(dx: f32, ms: f64) -> f32 {
    let frames = (ms.max(1.0) / 1000.0 * 60.0).max(0.06) as f32;
    let speed = dx.abs() / frames;
    (speed / 30.0).powf(0.86) * dx / 200.0
}

/// A liquify stroke's points, (x, y, pressure, milliseconds since the last) each, the first since
/// `since`: a finger's (or a mouse's) at full pressure.
fn liquify_points(pen: bool, samples: &[PenSample], mut since: f64) -> Vec<f32> {
    let mut points = Vec::with_capacity(samples.len() * 4);
    for s in samples {
        points.extend([s.x, s.y, if pen { s.pressure } else { 1.0 }, (s.time_ms - since) as f32]);
        since = s.time_ms;
    }
    points
}

fn is_pen(kind: PointerKind) -> bool {
    matches!(kind, PointerKind::Stylus | PointerKind::StylusEraser)
}

/// Whether a touch of `kind` does what a finger is set to: a finger's, and the mouse's where it
/// follows Touch (see [`TouchSettings::pointer_follows_touch`]).
fn as_finger(kind: PointerKind, settings: &TouchSettings) -> bool {
    kind == PointerKind::Finger || (kind == PointerKind::Mouse && settings.pointer_follows_touch)
}

impl CanvasSession {
    fn touches(&self) -> std::sync::MutexGuard<'_, Touches> {
        self.shared.touches.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Whether the pen is near enough that a hand's touches are taken for its: on the canvas, held
    /// over it (unless Hover's touch takes fingers then), or just gone (lifted before `now_ms`, an
    /// input's time, or no longer held over it).
    fn pen_near(t: &Touches, now_ms: Option<f64>) -> bool {
        let hovering = t.pen_hovering.is_some_and(|at| at.elapsed().as_secs_f32() < PEN_HOVER_STALE_S);
        let left = t.pen_left.is_some_and(|at| at.elapsed().as_secs_f32() < PEN_GONE_S);
        let gone_ms = f64::from(PEN_GONE_S) * 1000.0;
        let lifted = now_ms.zip(t.pen_lifted_ms).is_some_and(|(now, lifted)| (0.0..gone_ms).contains(&(now - lifted)));
        t.pen_pointer.is_some() || (!t.settings.hover_touch && (hovering || left || lifted))
    }

    /// What the touches down are doing is taken back (a hand resting on the screen, the pen come
    /// to it), and they are left alone until they lift; `keep` stays.
    fn hand_aside<'a>(
        &'a self,
        mut t: std::sync::MutexGuard<'a, Touches>,
        keep: Option<u64>,
        out: &mut Vec<TouchCommand>,
    ) -> std::sync::MutexGuard<'a, Touches> {
        let mode = std::mem::replace(&mut t.mode, Mode::None);
        t.held = None;
        let hand: Vec<u64> = t.down.iter().map(|d| d.id).filter(|id| Some(*id) != keep).collect();
        t.palms.extend(hand);
        t.down.retain(|d| Some(d.id) == keep);
        t.hold_due = None;
        t.quickshape_due = None;
        t.rapid_due = None;
        match mode {
            Mode::Finger => {
                self.end_stroke_tools(&mut t);
                drop(t);
                self.stroke_cancel();
                t = self.touches();
            }
            Mode::Pick => {
                t.pick_next = false;
                out.push(TouchCommand::PickCancel);
            }
            _ => {}
        }
        out.push(TouchCommand::CursorGone);
        t
    }

    /// The tool a stroke borrows (or the one it gives back): not counted as one chosen.
    fn put_tool(&self, tool: ToolKind) {
        self.shared.lock().tool = tool;
    }

    /// Where a pen's `samples` (as [`Self::pen_input`] left them) press: the first with a pressure
    /// over nothing, none while it touches at none.
    fn pen_presses(samples: &[PenSample]) -> Option<usize> {
        samples.iter().position(|s| s.pressure > 0.0)
    }

    /// A pen's `samples` as the touches take them. A pressure that is no number is none (or full,
    /// for an endless one). One with a pressure over nothing makes pens known to give one, from
    /// now on and in each artwork opened after. From a pen taken for one that gives none, those at
    /// none are at full pressure, as a finger's are.
    fn pen_input(t: &mut Touches, samples: &mut [PenSample]) {
        for s in samples.iter_mut().filter(|s| !s.pressure.is_finite()) {
            s.pressure = if s.pressure > 0.0 { 1.0 } else { 0.0 };
        }
        if !t.pen_pressure && samples.iter().any(|s| s.pressure > 0.0) {
            t.pen_pressure = true;
            t.pen_no_pressure = false;
            PEN_PRESSURE.store(true, Ordering::Relaxed);
        }
        if t.pen_no_pressure && !t.pen_pressure {
            // (none that is no number is left by now)
            for s in samples.iter_mut().filter(|s| s.pressure <= 0.0) {
                s.pressure = 1.0;
            }
        }
    }

    /// The pen not known to give a pressure, touching at none, has waited
    /// [`PEN_PRESSURE_WAIT_MS`] for one (or lifted first, `lifts`): it is taken for a pen that
    /// gives none. Its touch begins where it came down, at full pressure, and goes on with what it
    /// did meanwhile.
    fn pen_without_pressure(&self, id: u64, kind: PointerKind, lifts: bool) -> Vec<TouchCommand> {
        let mut held = {
            let mut t = self.touches();
            t.pen_unpressed = None;
            t.pen_wait_due = None;
            t.pen_no_pressure = true;
            std::mem::take(&mut t.pen_waiting)
        };
        log::info!(
            "a pen touched at no pressure for {PEN_PRESSURE_WAIT_MS} ms (or lifted first) and none has given one: \
             taken for a pen that gives none, it paints at full pressure"
        );
        if held.is_empty() {
            return self.pen_hover_ends();
        }
        let rest = held.split_off(1);
        let mut out = self.touch_down(id, kind, held.clone());
        if lifts {
            // a tap: a dot where it touched
            out.extend(self.touch_up(id, if rest.is_empty() { held } else { rest }));
        } else if !rest.is_empty() {
            out.extend(self.touch_move(id, rest));
        }
        out
    }

    /// The predicted samples of the stroke (see [`CanvasSession::stroke_predict`]): a pen's taken
    /// for one that gives no pressure go at full pressure, as its own do.
    pub(crate) fn pen_predicted(&self, mut samples: Vec<PenSample>) -> Vec<PenSample> {
        let mut t = self.touches();
        if t.pen_pointer.is_some() && t.pen_no_pressure && !t.pen_pressure {
            Self::pen_input(&mut t, &mut samples);
        }
        samples
    }

    /// The pen touching at no pressure is at `at`: it hovers there, as one held over the canvas.
    fn pen_hovers_at(&self, at: (f32, f32)) -> Vec<TouchCommand> {
        let mut out = self.set_touch_pen_hovering(true);
        out.push(TouchCommand::PenHovers { x: at.0, y: at.1 });
        out
    }

    /// The pen touching at no pressure lifted (or was taken back) without pressing: it no longer
    /// hovers, and its cursor goes.
    fn pen_hover_ends(&self) -> Vec<TouchCommand> {
        let mut out = self.set_touch_pen_hovering(false);
        out.push(TouchCommand::CursorGone);
        out
    }

    /// More input from pen `id` touching at no pressure (`lifts`: its last). Where it presses its
    /// touch begins, as if it had come down there, and goes on with the rest; until then it hovers.
    /// A pen not known to give a pressure that waits past [`PEN_PRESSURE_WAIT_MS`] (or lifts) is
    /// taken for one that gives none (see [`Self::pen_without_pressure`]).
    fn unpressed_input(
        &self,
        id: u64,
        kind: PointerKind,
        mut samples: Vec<PenSample>,
        lifts: bool,
    ) -> Vec<TouchCommand> {
        let mut t = self.touches();
        Self::pen_input(&mut t, &mut samples);
        let Some(from) = Self::pen_presses(&samples) else {
            if let Some(due) = t.pen_wait_due {
                t.pen_waiting.extend(samples);
                let at = t.pen_waiting.last().map(|s| (s.x, s.y, s.time_ms));
                drop(t);
                if lifts || at.is_some_and(|(_, _, ms)| ms >= due) {
                    return self.pen_without_pressure(id, kind, lifts);
                }
                return at.map_or_else(Vec::new, |(x, y, _)| self.pen_hovers_at((x, y)));
            }
            if lifts {
                t.pen_unpressed = None;
                // (held near the glass and taken away; or a pen that gives none after one that did)
                if !std::mem::replace(&mut t.unpressed_told, true) {
                    log::info!("a pen's touch lifted without ever pressing: taken for the pen held near the glass");
                }
                drop(t);
                return self.pen_hover_ends();
            }
            drop(t);
            return samples.last().map_or_else(Vec::new, |s| self.pen_hovers_at((s.x, s.y)));
        };
        t.pen_unpressed = None;
        t.pen_waiting.clear();
        t.pen_wait_due = None;
        drop(t);
        let rest = samples.split_off(from + 1);
        let pressed = samples.split_off(from);
        let mut out = self.touch_down(id, kind, pressed.clone());
        if lifts {
            // a tap whose pressure came only as it lifted: a dot where it pressed
            out.extend(self.lifted(id, if rest.is_empty() { pressed } else { rest }, false));
        } else if !rest.is_empty() {
            out.extend(self.touch_move(id, rest));
        }
        out
    }
}

#[uniffi::export]
impl CanvasSession {
    /// The gesture settings the touches follow from now on.
    pub fn set_touch_settings(&self, settings: TouchSettings) {
        self.touches().settings = settings;
    }

    /// The modify button tapped: whether the next touch picks a colour.
    pub fn set_pick_next(&self, on: bool) {
        self.touches().pick_next = on;
    }

    pub fn pick_next(&self) -> bool {
        self.touches().pick_next
    }

    /// How far above the point it picks the eyedropper's loupe is centred (points), for the one
    /// just opened: opened by a pen, none (the colour aimed at is the one in its middle, under
    /// the tip); by a finger or a mouse, [`LOUPE_LIFT`], clear of it.
    pub fn pick_loupe_lift(&self) -> f32 {
        if self.touches().pick_pen { 0.0 } else { LOUPE_LIFT }
    }

    /// The modify button is held (or let go). Let go, true if a touch used it meanwhile (so its
    /// tap does nothing).
    pub fn set_modify_held(&self, held: bool) -> bool {
        let mut t = self.touches();
        t.modify_held = held;
        let used = t.modify_used;
        if !held {
            t.modify_used = false;
        }
        used
    }

    /// The selection tool open (each touch selects, as `how` says) or not.
    pub fn set_touch_selecting(&self, how: Option<SelectTouch>) {
        let mut t = self.touches();
        t.selecting = how;
        if how.is_none() {
            t.freehand.clear();
        }
    }

    /// The colour selection being slid, shown at `threshold` from window point (`x`, `y`) where it
    /// went down: called once a frame with the last [`TouchCommand::SelectionThreshold`] not
    /// `done`. Once the touch has lifted it does nothing, as the lift made the selection.
    pub fn show_colour_selection(&self, x: f32, y: f32, threshold: f32) {
        let join = {
            let t = self.touches();
            if t.mode != Mode::Select {
                return;
            }
            match t.selecting {
                Some(how) if how.kind == SelectKind::Automatic => how.join,
                _ => return,
            }
        };
        self.select_colour_at((x, y), threshold, join, true);
    }

    /// The freehand selection's path drawn so far, in window pixels (x, y pairs).
    pub fn freehand_path(&self) -> Vec<f32> {
        let points = self.touches().freehand.clone();
        let st = self.shared.lock();
        let Some(view) = st.view else { return Vec::new() };
        points
            .iter()
            .flat_map(|&(x, y)| {
                let p = view.to_screen().apply(procraft_engine::geom::Vec2::new(x, y));
                [p.x, p.y]
            })
            .collect()
    }

    /// The selection tool closes: a freehand path still open is closed into the selection. With
    /// Color Fill on (`colour_fill`), what is selected is then filled with the paint colour and the
    /// selection ends with it, as one undo step that brings the selection back when undone; but a
    /// path that had to be closed here is left unfilled, the selection kept. True if the selection
    /// or the layer changed (the app reads them again).
    pub fn close_selection_tool(&self, colour_fill: bool) -> bool {
        let open = self.touches().freehand.len() >= 3;
        self.close_freehand();
        if !colour_fill || open {
            return open;
        }
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let filled = st.colour_fill_and_end();
        if filled {
            self.shared.frame(st);
        }
        filled
    }

    /// Closes the freehand path into a selection (fewer than three points are dropped).
    pub fn close_freehand(&self) {
        let (path, join) = {
            let mut t = self.touches();
            (std::mem::take(&mut t.freehand), t.selecting.map_or(crate::SelectionJoin::Add, |s| s.join))
        };
        if path.len() < 3 {
            return;
        }
        let window: Vec<f32> = {
            let st = self.shared.lock();
            let Some(view) = st.view else { return };
            path.iter()
                .flat_map(|&(x, y)| {
                    let p = view.to_screen().apply(procraft_engine::geom::Vec2::new(x, y));
                    [p.x, p.y]
                })
                .collect()
        };
        self.select_polygon(window, join);
    }

    /// A transform open (each touch works its box) or not.
    pub fn set_touch_transforming(&self, transforming: Option<TransformTouch>) {
        self.touches().transforming = transforming;
    }

    /// The guide editor open (or not): a touch drags the guide's handle it lands on, and a tap on
    /// one shows its menu; a tap elsewhere gives a perspective guide a point (up to three); two
    /// fingers move, zoom and turn the canvas as ever (a quick pinch in fitting it to the whole
    /// window), and nothing else: nothing paints, and fingers tapped, held, scrubbed or swiped do
    /// nothing.
    pub fn set_touch_guide_editing(&self, editing: bool) {
        let mut t = self.touches();
        t.guide_editing = editing;
        t.guide_grab = None;
    }

    /// Continue filling on (each touch on the canvas fills with `fill`) or off.
    pub fn set_touch_filling(&self, fill: Option<Fill>) {
        self.touches().filling = fill;
    }

    /// Layer Opacity on for layer `id` (a drag across the canvas changes its opacity, each drag
    /// one undo step) or off.
    pub fn set_touch_layer_opacity(&self, id: Option<u64>) {
        let start = id.and_then(|id| self.layers().into_iter().find(|l| l.id == id)).map(|l| (l.id, l.opacity));
        self.touches().layer_opacity = start;
    }

    /// The pen is held over the canvas (or not): with Hover's touch on, a finger then sets the
    /// brush rather than painting. Told again with each of the pen's moves: a pen not heard from
    /// for [`PEN_HOVER_STALE_S`] has gone (a system may not say when it leaves).
    ///
    /// The pen coming near a hand already on the screen (a finger alone, painting or waiting) takes
    /// back what it was doing and leaves it alone until it lifts, as a tablet made for a pen does.
    pub fn set_touch_pen_hovering(&self, hovering: bool) -> Vec<TouchCommand> {
        let mut out = Vec::new();
        let mut t = self.touches();
        let was_near = Self::pen_near(&t, None);
        let now = std::time::Instant::now();
        if hovering {
            t.pen_hovering = Some(now);
        } else if t.pen_hovering.take().is_some() {
            t.pen_left = Some(now);
        }
        let lone = matches!(t.mode, Mode::Finger | Mode::Idle | Mode::Pick | Mode::Held);
        if hovering && !was_near && Self::pen_near(&t, None) && !t.down.is_empty() && t.modify_pointer.is_none() && lone
        {
            drop(self.hand_aside(t, None, &mut out));
        }
        out
    }

    /// The single touch companion's Zoom or Move on (a lone press dragged on the canvas zooms or
    /// moves it), or off.
    pub fn set_touch_companion(&self, mode: Option<CompanionMode>) {
        self.touches().companion = mode;
    }

    /// A panel is open over the canvas (or not): a touch landing on the canvas waits, until a tap
    /// only closes the panel ([`TouchCommand::PanelTapped`]) or a drag fades it
    /// ([`TouchCommand::PanelFades`]) and does what it would have from where it came down. Held
    /// still, it does what a hold does; more fingers landing move the canvas: both leave the
    /// panel up.
    pub fn set_touch_panel_open(&self, open: bool) {
        self.touches().panel_open = open;
    }

    /// Layer Opacity's layer's opacity as dragged, while it is on.
    pub fn touch_layer_opacity(&self) -> Option<f32> {
        self.touches().layer_opacity.map(|(_, opacity)| opacity)
    }

    /// The snapped shape's handles show (its bar tapped), or not.
    pub fn set_shape_editing(&self, editing: bool) {
        self.touches().shape_editing = editing;
    }

    /// When the touches next need [`CanvasSession::touch_tick`] (a hold, a repeat, an airbrush's
    /// next dab), if ever.
    pub fn touch_deadline(&self) -> Option<f64> {
        let due = {
            let t = self.touches();
            [t.hold_due, t.quickshape_due, t.rapid_due, t.liquify_due, t.pen_wait_due]
                .into_iter()
                .flatten()
                .reduce(f64::min)
        };
        let airbrush = self.shared.lock().airbrush.map(|(_, _, next)| next);
        [due, airbrush].into_iter().flatten().reduce(f64::min)
    }

    /// An airbrush's stroke whose pen holds still is handed the pen again, as it last was, now.
    fn airbrush_tick(&self, now_ms: f64) {
        let again = {
            let mut st = self.shared.lock();
            match st.airbrush.as_mut() {
                Some((every, last, next)) if *next <= now_ms => {
                    *next = now_ms + *every;
                    Some(PenSample { time_ms: now_ms, ..*last })
                }
                _ => None,
            }
        };
        if let Some(sample) = again {
            self.stroke_add(vec![sample]);
        }
    }

    /// A pointer goes down on the canvas (`samples` its first input).
    ///
    /// While the pen is near (on the canvas, held over it, or just gone), a finger landing alone
    /// is a hand resting on the screen and does nothing until it lifts; fingers landing within
    /// [`TOGETHER_MS`] of each other make a gesture as ever.
    ///
    /// A pen known to give a pressure that touches at none is held near the glass: it hovers
    /// ([`TouchCommand::PenHovers`]) until it presses, and its touch begins there. One not known to
    /// give a pressure does so for [`PEN_PRESSURE_WAIT_MS`]; none coming, it paints from where it
    /// touched, at full pressure.
    pub fn touch_down(&self, id: u64, kind: PointerKind, mut samples: Vec<PenSample>) -> Vec<TouchCommand> {
        let Some(first) = last_of(&samples).copied() else { return Vec::new() };
        let mut t = self.touches();
        if is_pen(kind) {
            Self::pen_input(&mut t, &mut samples);
            t.pen_unpressed = None;
            t.pen_waiting.clear();
            t.pen_wait_due = None;
            let Some(from) = Self::pen_presses(&samples) else {
                t.pen_unpressed = Some((id, kind));
                // one not known to give a pressure waits a moment for one, keeping what it does
                if !t.pen_pressure {
                    t.pen_wait_due = samples.first().map(|s| s.time_ms + PEN_PRESSURE_WAIT_MS);
                    t.pen_waiting = samples;
                }
                drop(t);
                return self.pen_hovers_at((first.x, first.y));
            };
            samples.drain(..from);
            t.pen_pointer = Some(id);
            // a finger waiting for another is the hand's
            if let Some((waiting, _, _)) = t.palm_wait.take() {
                t.palms.push(waiting);
            }
        } else if kind == PointerKind::Finger
            && !t.modify_held
            && Self::pen_near(&t, Some(first.time_ms))
            && (t.pen_pointer.is_some() || t.down.is_empty())
        {
            let waiting = t.palm_wait.take();
            match waiting {
                _ if t.pen_pointer.is_some() => t.palms.push(id),
                None => t.palm_wait = Some((id, kind, samples)),
                Some((other, other_kind, other_samples)) => {
                    let landed = last_of(&other_samples).map_or(f64::MIN, |s| s.time_ms);
                    if first.time_ms - landed <= TOGETHER_MS {
                        drop(t);
                        let mut out = self.touch_down_now(other, other_kind, other_samples);
                        out.extend(self.touch_down_now(id, kind, samples));
                        return out;
                    }
                    t.palms.extend([other, id]);
                }
            }
            return Vec::new();
        } else if let Some((other, other_kind, other_samples)) = t.palm_wait.take() {
            // the pen gone meanwhile (its time run out): a finger left waiting still makes a
            // gesture with one landing together with it, else it was the hand's
            let landed = last_of(&other_samples).map_or(f64::MIN, |s| s.time_ms);
            if kind == PointerKind::Finger && first.time_ms - landed <= TOGETHER_MS {
                drop(t);
                let mut out = self.touch_down_now(other, other_kind, other_samples);
                out.extend(self.touch_down_now(id, kind, samples));
                return out;
            }
            t.palms.push(other);
        }
        drop(t);
        self.touch_down_now(id, kind, samples)
    }

    /// A pointer goes down on the canvas, taken as a touch.
    fn touch_down_now(&self, id: u64, kind: PointerKind, samples: Vec<PenSample>) -> Vec<TouchCommand> {
        let Some(first) = last_of(&samples).copied() else { return Vec::new() };
        let mut out = Vec::new();
        let mut t = self.touches();
        let hooks;
        (t.scale, t.handle_scale, hooks) = {
            let st = self.shared.lock();
            (st.display_scale, st.interface_scale(), st.hooks.clone())
        };
        let at = (first.x, first.y);
        t.down.retain(|d| d.id != id);
        t.down.push(Down { id, at, from: at });
        // a plugin locking the turn or the fit: the fingers landing now don't turn the canvas, or
        // fit it with a quick pinch in
        let locked = |part| hooks.as_ref().is_some_and(|hooks| hooks.gesture_lock(part));
        (t.turn_locked, t.fit_locked) = (locked(crate::hooks::GESTURE_TURN), locked(crate::hooks::GESTURE_FIT));
        // the guide editor: a touch takes a handle, more fingers move the view
        if t.guide_editing {
            if t.down.len() == 1 {
                self.first_down(&mut t, kind, first, &samples, &mut out);
            } else {
                self.another_down(&mut t, kind, first.time_ms, &mut out);
            }
            return out;
        }
        // a plugin pinning panels open: the canvas takes the touch as if none were
        let pinned = t.panel_open && hooks.is_some_and(|hooks| hooks.pin_panels());
        // the companion's Zoom or Move takes a lone press, and others while it is held do nothing
        if t.mode == Mode::Companion {
            return out;
        }
        if t.companion.is_some() && t.down.len() == 1 {
            t.stroke_pointer = id;
            t.down_at = at;
            t.companion_last = at;
            t.mode = Mode::Companion;
            return out;
        }
        // a touch made while the modify button is held does what □ + Touch (□ + Pen) is set to
        if t.modify_held && t.modify_pointer.is_none() && (t.down.len() == 1 || t.mode == Mode::None) {
            // on a model painted with a finger, the button held makes a finger turn it
            if as_finger(kind, &t.settings) && t.settings.model_finger_paint && self.shared.lock().scene_shown() {
                t.modify_used = true;
                t.stroke_pointer = id;
                t.turn_last = (first.x, first.y);
                t.down_at = t.turn_last;
                t.mode = Mode::Turn;
                return out;
            }
            let purpose = if is_pen(kind) { t.settings.modify_pen } else { t.settings.modify_touch };
            if let Some(purpose) = purpose {
                t.modify_used = true;
                t.modify_pointer = Some((id, purpose));
                drop(t);
                self.end_shape(&mut out);
                self.modify_begins(purpose, kind, &samples, &mut out);
                return out;
            }
        }
        // the pen landing while only fingers are down (a hand resting on the screen) draws as it
        // would alone: what the fingers were doing is taken back, and they are left alone until
        // lifted
        if is_pen(kind)
            && t.down.len() > 1
            && t.modify_pointer.is_none()
            && matches!(t.mode, Mode::Finger | Mode::Idle | Mode::Gesture | Mode::Pick | Mode::Held)
        {
            t = self.hand_aside(t, Some(id), &mut out);
        }
        if t.down.len() == 1 && t.panel_open && !pinned {
            Self::wait_for_panel(&mut t, kind, first, samples);
        } else if t.down.len() == 1 {
            self.first_down(&mut t, kind, first, &samples, &mut out);
        } else {
            self.another_down(&mut t, kind, first.time_ms, &mut out);
        }
        out
    }

    /// Pointers down move together, as one input event has them: the view goes by all of them at
    /// once, never by a spread that only some of them have got to.
    pub fn touch_moves(&self, moves: Vec<TouchMove>) -> Vec<TouchCommand> {
        let mut out = Vec::new();
        // the last that counts: a resting finger the pen put aside moves in the event too
        let last = {
            let t = self.touches();
            moves.iter().rposition(|m| t.down.iter().any(|d| d.id == m.id)).unwrap_or(0)
        };
        for (i, step) in moves.into_iter().enumerate() {
            self.touches().more_to_come = i < last;
            out.extend(self.touch_move(step.id, step.samples));
        }
        self.touches().more_to_come = false;
        out
    }

    /// A pointer down moves (`samples` its input since the last).
    pub fn touch_move(&self, id: u64, mut samples: Vec<PenSample>) -> Vec<TouchCommand> {
        let Some(last) = last_of(&samples).copied() else { return Vec::new() };
        let mut out = Vec::new();
        let mut t = self.touches();
        // the pen touching at no pressure hovers on, or presses
        if let Some((unpressed, kind)) = t.pen_unpressed
            && unpressed == id
        {
            drop(t);
            return self.unpressed_input(id, kind, samples, false);
        }
        if t.pen_pointer == Some(id) {
            Self::pen_input(&mut t, &mut samples);
        }
        let at = (last.x, last.y);
        let Some(d) = t.down.iter_mut().find(|d| d.id == id) else { return out };
        d.at = at;
        if let Some((pointer, purpose)) = t.modify_pointer {
            if pointer == id {
                match purpose {
                    GestureAction::Eyedropper => out.push(TouchCommand::Pick { x: at.0, y: at.1 }),
                    GestureAction::Smudge | GestureAction::Erase | GestureAction::Assisted => self.stroke_add(samples),
                    _ => {}
                }
            }
            return out;
        }
        let slop = TAP_SLOP * t.scale;
        match t.mode {
            Mode::Pen | Mode::Finger if id == t.stroke_pointer => {
                if t.mode == Mode::Finger && dist(at, t.down_at) > slop {
                    t.hold_due = None;
                }
                // moving on restarts the wait for a hold
                if dist(at, t.hold_at) > QUICKSHAPE_STILL * t.scale {
                    t.hold_at = at;
                    let snapped = self.shared.lock().shape.is_some();
                    t.quickshape_due =
                        (!snapped && t.settings.draw_and_hold).then_some(last.time_ms + t.settings.quickshape_ms);
                }
                drop(t);
                self.clone_stroke_at(at, false);
                self.stroke_add(samples);
                out.push(TouchCommand::Cursor { x: at.0, y: at.1 });
            }
            Mode::Held if id == t.stroke_pointer => {
                if let Some(held) = t.held.as_mut() {
                    held.moves.extend(samples);
                }
                if dist(at, t.down_at) > PANEL_SLOP * t.scale {
                    self.held_goes_on(t, true, &mut out);
                }
            }
            Mode::Puck if id == t.stroke_pointer => {
                let (gx, gy) = t.puck_grab;
                drop(t);
                self.adjustment_clone_move(at.0 - gx, at.1 - gy);
                out.push(TouchCommand::AdjustChanged);
            }
            Mode::Pick => out.push(TouchCommand::Pick { x: at.0, y: at.1 }),
            Mode::Companion if id == t.stroke_pointer => {
                let last = std::mem::replace(&mut t.companion_last, at);
                let (dx, dy) = (at.0 - last.0, at.1 - last.1);
                let (from, zoom, scale) = (t.down_at, t.companion == Some(CompanionMode::Zoom), t.scale);
                drop(t);
                if zoom {
                    let k = (-dy / (COMPANION_ZOOM_DISTANCE * scale)).exp();
                    self.view_gesture(from.0, from.1, 0.0, 0.0, k, 0.0);
                } else {
                    self.view_gesture(at.0, at.1, dx, dy, 1.0, 0.0);
                }
                out.push(TouchCommand::ViewMoved);
            }
            Mode::Hover => {
                if let (Some(a), Some(b)) = (t.down.first(), t.down.get(1)) {
                    // pinched: the size grows and shrinks with how far apart they are (the first
                    // finger followed, so it drags the opacity on from where it is once alone)
                    let spread = dist(a.at, b.at);
                    if id == t.stroke_pointer {
                        t.hover_x = at.0;
                    }
                    let last = std::mem::replace(&mut t.hover_spread, spread);
                    if last > 1.0 && spread > 1.0 {
                        drop(t);
                        let mut st = self.shared.lock();
                        let sliders = st.sliders_mut();
                        sliders.size = (sliders.size * spread / last).clamp(0.0, 1.0);
                        out.push(TouchCommand::BrushChanged);
                    }
                } else if id == t.stroke_pointer {
                    // across the window, from none to full opacity
                    let dx = at.0 - std::mem::replace(&mut t.hover_x, at.0);
                    drop(t);
                    let mut st = self.shared.lock();
                    let width = st.window_size.0.max(1) as f32;
                    let sliders = st.sliders_mut();
                    sliders.opacity = (sliders.opacity + dx / width).clamp(0.0, 1.0);
                    out.push(TouchCommand::BrushChanged);
                }
            }
            Mode::Picture if id == t.stroke_pointer => {
                let last = std::mem::replace(&mut t.turn_last, at);
                drop(t);
                self.model_picture_move(at.0 - last.0, at.1 - last.1, 1.0, 0.0);
            }
            Mode::Turn if id == t.stroke_pointer => {
                let last = std::mem::replace(&mut t.turn_last, at);
                if dist(at, t.down_at) > TAP_SLOP * t.scale {
                    t.hold_due = None;
                }
                drop(t);
                self.turn_model(at.0 - last.0, at.1 - last.1);
            }
            Mode::Recolor if id == t.stroke_pointer => {
                // past a tap's slop, the reticle follows the touch
                if !t.recolor_dragged && dist(at, t.down_at) > TAP_SLOP * t.scale {
                    t.recolor_dragged = true;
                }
                if t.recolor_dragged {
                    // as far from where it was as the touch went from where it came down
                    let (from, down) = (t.recolor_from, t.down_at);
                    out.push(TouchCommand::RecolorMoved { x: from.0 + at.0 - down.0, y: from.1 + at.1 - down.1 });
                }
            }
            Mode::Opacity if id == t.stroke_pointer => {
                // the opacity follows the drag across, faster the faster it goes
                let (last_x, last_ms) = t.pour_last;
                t.pour_last = (at.0, last.time_ms);
                let step = drag_step((at.0 - last_x) / t.scale, last.time_ms - last_ms);
                if let Some((layer, opacity)) = t.layer_opacity.filter(|_| step != 0.0) {
                    let opacity = (opacity + step).clamp(0.0, 1.0);
                    t.layer_opacity = Some((layer, opacity));
                    t.opacity_moved = true;
                    drop(t);
                    self.set_layer_opacity(layer, opacity, false);
                }
            }
            Mode::Select if id == t.stroke_pointer => {
                drop(t);
                self.select_step(at, last.time_ms, false, &mut out);
            }
            Mode::Adjust if id == t.stroke_pointer => {
                if dist(at, t.down_at) > TAP_SLOP * t.scale {
                    t.adjust_moved = true;
                }
                // a drag on perspective blur's puck moves it or turns its arrow, once past the slop
                if let Some(drag) = t.puck_drag {
                    let begins = !t.puck_dragging && dist(at, t.down_at) > PUCK_SLOP * t.scale;
                    if begins {
                        t.puck_dragging = true;
                        t.adjust_moved = true;
                    }
                    let dragging = t.puck_dragging;
                    drop(t);
                    if begins {
                        self.perspective_grabbed();
                    }
                    if dragging {
                        match drag {
                            PuckDrag::Move(gx, gy) => self.perspective_move(at.0 - gx, at.1 - gy),
                            PuckDrag::Turn => self.perspective_turn(at.0, at.1),
                        }
                        out.push(TouchCommand::AdjustChanged);
                    }
                    return out;
                }
                let (from, (last_x, last_ms)) = (t.down_at, t.pour_last);
                t.pour_last = (at.0, last.time_ms);
                let start = !std::mem::replace(&mut t.adjust_displacing, true);
                let step = drag_step((at.0 - last_x) / t.scale, last.time_ms - last_ms);
                drop(t);
                let (open, displacing) = {
                    let st = self.shared.lock();
                    (st.adjust.open().map(|o| o.0), st.adjust.displacing())
                };
                if open == Some(Adjustment::Liquify) {
                    // the brush liquifies along the drag
                    let mut t = self.touches();
                    if dist(at, t.liquify_rest) > LIQUIFY_HELD_SLOP * t.scale {
                        t.liquify_rest = at;
                        t.liquify_rest_ms = last.time_ms;
                        t.liquify_held = 0.0;
                    }
                    let pen = t.liquify_pen;
                    let points = liquify_points(pen, &samples, t.liquify_ms);
                    t.liquify_ms = last.time_ms;
                    drop(t);
                    self.adjustment_liquify_stroke(points, false, pen);
                    out.push(TouchCommand::AdjustChanged);
                    return out;
                }
                match open {
                    // a motion blur follows the drag: along it, longer the further it goes
                    Some(Adjustment::MotionBlur) => self.adjustment_motion(at.0 - from.0, at.1 - from.1),
                    // displacing, the drag moves the fringes
                    Some(_) if displacing => self.adjustment_chroma_drag(at.0 - from.0, at.1 - from.1, start),
                    // across the canvas the amount follows the drag, faster the faster it goes
                    Some(a) if crate::adjust_menu::adjustment_swiped(a) => self.adjustment_amount_by(step),
                    _ => return out,
                }
                out.push(TouchCommand::AdjustChanged);
            }
            Mode::Transform if id == t.stroke_pointer => {
                let handle = t.transform_handle;
                if !t.transform_grabbed && dist(at, t.down_at) > TRANSFORM_SLOP * t.scale {
                    // the drag counts from where it was recognised, as the slop is used up
                    t.transform_grabbed = true;
                    self.transform_grab(handle, at.0, at.1);
                }
                if t.transform_grabbed {
                    drop(t);
                    let transform = self.transform_drag(at.0, at.1);
                    out.push(TouchCommand::TransformMoved { transform });
                    out.push(TouchCommand::TransformHeld { handle, x: at.0, y: at.1 });
                }
            }
            Mode::Pour if id == t.stroke_pointer => {
                if let Some(threshold) = Self::pour_slide(&mut t, at, last.time_ms) {
                    out.push(TouchCommand::FillThreshold { threshold });
                }
            }
            Mode::Guide if id == t.stroke_pointer => {
                // the handle stays put until the touch has gone far enough not to be a tap
                if !t.guide_moved && dist(at, t.down_at) > GUIDE_SLOP * t.scale {
                    t.guide_moved = true;
                }
                if let Some(grab) = t.guide_grab.filter(|_| t.guide_moved) {
                    drop(t);
                    self.guide_drag(grab, at.0, at.1);
                    out.push(TouchCommand::GuideChanged);
                }
            }
            Mode::Idle => {
                if dist(at, t.down_at) > slop {
                    t.hold_due = None;
                }
                // the lone finger set to move the canvas moves it, from where it went down
                if let Some(last) = t.pan_last.filter(|_| id == t.stroke_pointer && t.down.len() == 1)
                    && (t.panned || dist(at, t.down_at) > slop)
                {
                    t.panned = true;
                    t.pan_last = Some(at);
                    drop(t);
                    self.view_gesture(at.0, at.1, at.0 - last.0, at.1 - last.1, 1.0, 0.0);
                    out.push(TouchCommand::ViewMoved);
                }
            }
            Mode::Gesture if t.more_to_come => {}
            Mode::Gesture => {
                Self::note_fingers_moved(&mut t);
                if t.down.len() >= 3 {
                    Self::scrub(&mut t, &mut out);
                } else if t.box_pinch.is_some() {
                    self.pinch_box(&mut t, &mut out);
                } else if self.move_gesture(&mut t, last.time_ms) {
                    out.push(TouchCommand::ViewMoved);
                }
            }
            Mode::Shape(i) => {
                let began = !t.shape_dragged && dist(at, t.down_at) > NODE_SLOP * t.scale;
                if began {
                    // from here on, the node's way from the finger
                    t.shape_dragged = true;
                    t.shape_last = (t.shape_last.0 - at.0, t.shape_last.1 - at.1);
                }
                if t.shape_dragged {
                    let grip = t.shape_last;
                    drop(t);
                    if began {
                        self.quickshape_step();
                    }
                    self.quickshape_move_handle(i, at.0 + grip.0, at.1 + grip.1);
                    out.push(TouchCommand::ShapeChanged);
                }
            }
            Mode::ShapePin | Mode::ShapeMove if id == t.stroke_pointer => {
                // only a drag past a tap's slop moves it, then from where it began
                let began = !t.shape_dragged && dist(at, t.down_at) > slop;
                t.shape_dragged |= began;
                if t.shape_dragged {
                    let (pin, from) = (t.mode == Mode::ShapePin, t.shape_last);
                    t.shape_last = at;
                    drop(t);
                    if began {
                        self.quickshape_step();
                    }
                    if pin {
                        self.quickshape_pin(at.0, at.1, false);
                    } else {
                        self.quickshape_move(from.0, from.1, at.0, at.1);
                    }
                    out.push(TouchCommand::ShapeChanged);
                }
            }
            _ => {}
        }
        out
    }

    /// A pointer lifts (`samples` its last input).
    pub fn touch_up(&self, id: u64, mut samples: Vec<PenSample>) -> Vec<TouchCommand> {
        let unpressed = self.touches().pen_unpressed.filter(|(unpressed, _)| *unpressed == id);
        if let Some((_, kind)) = unpressed {
            return self.unpressed_input(id, kind, samples, true);
        }
        {
            let mut t = self.touches();
            if t.pen_pointer == Some(id) {
                Self::pen_input(&mut t, &mut samples);
            }
        }
        self.lifted(id, samples, false)
    }

    /// The platform took back one pointer (it was a hand resting on the screen): what it alone
    /// was doing is taken back, and the touch it was part of is no tap.
    pub fn touch_cancel_one(&self, id: u64) -> Vec<TouchCommand> {
        self.lifted(id, Vec::new(), true)
    }

    /// The platform called the touches off: what they were doing is taken back.
    pub fn touch_cancel(&self) -> Vec<TouchCommand> {
        let (ids, unpressed): (Vec<u64>, bool) = {
            let mut t = self.touches();
            t.palms.clear();
            t.palm_wait = None;
            if t.pen_pointer.take().is_some() {
                t.pen_left = Some(std::time::Instant::now());
            }
            t.pen_waiting.clear();
            t.pen_wait_due = None;
            (t.down.iter().map(|d| d.id).collect(), t.pen_unpressed.take().is_some())
        };
        let mut out = if unpressed { self.pen_hover_ends() } else { Vec::new() };
        for id in ids {
            out.extend(self.lifted(id, Vec::new(), true));
        }
        out
    }

    /// Runs the waits (a hold, a repeat, an airbrush's next dab) that have run out by `now_ms`.
    pub fn touch_tick(&self, now_ms: f64) -> Vec<TouchCommand> {
        self.airbrush_tick(now_ms);
        let mut out = Vec::new();
        // a pen not known to give a pressure, held still at none past its wait: one that gives none
        let waited = {
            let mut t = self.touches();
            match (t.pen_wait_due, t.pen_unpressed) {
                (Some(due), Some(pen)) if due <= now_ms => Some(pen),
                (Some(_), None) => {
                    t.pen_wait_due = None;
                    None
                }
                _ => None,
            }
        };
        if let Some((id, kind)) = waited {
            out.extend(self.pen_without_pressure(id, kind, false));
        }
        let mut t = self.touches();
        // a finger waiting for a panel held still: the hold goes on below, the panel staying
        if t.mode == Mode::Held && t.hold_due.is_some_and(|due| due <= now_ms) {
            self.held_goes_on(t, false, &mut out);
            t = self.touches();
        }
        // liquify held still works on as the time passes, and what it set going goes on
        if t.liquify_due.is_some_and(|due| due <= now_ms) {
            t.liquify_due = None;
            let passed = ((now_ms - t.liquify_tick_ms) / 1000.0) as f32;
            t.liquify_tick_ms = now_ms;
            let held = (t.mode == Mode::Adjust).then(|| {
                let to = ((now_ms - t.liquify_rest_ms - LIQUIFY_HELD_WAIT_MS) / 1000.0).max(0.0) as f32;
                (t.liquify_rest, std::mem::replace(&mut t.liquify_held, to), to)
            });
            drop(t);
            let worked =
                held.is_some_and(|(at, from, to)| to > from && self.adjustment_liquify_held(at.0, at.1, from, to));
            let going = self.adjustment_liquify_tick(passed);
            t = self.touches();
            if going || held.is_some() {
                t.liquify_due = Some(now_ms + LIQUIFY_HELD_STEP_MS);
            }
            if worked || going {
                out.push(TouchCommand::AdjustChanged);
            }
        }
        // a freehand selection held still snaps to a shape, which the lift then selects
        if t.mode == Mode::Select
            && t.quickshape_due.is_some_and(|due| due <= now_ms)
            && t.selecting.is_some_and(|s| s.kind == SelectKind::Freehand)
        {
            t.quickshape_due = None;
            if t.select_moved && t.settings.draw_and_hold {
                let flat: Vec<f32> = t.freehand.iter().flat_map(|&(x, y)| [x, y]).collect();
                if let Some(outline) = crate::quickshape::quickshape_outline(flat).filter(|o| o.len() >= 6) {
                    t.freehand = outline.as_chunks::<2>().0.iter().map(|&[x, y]| (x, y)).collect();
                    t.lasso_snapped = true;
                    out.push(TouchCommand::FreehandChanged);
                }
            }
        }
        if t.quickshape_due.is_some_and(|due| due <= now_ms) {
            t.quickshape_due = None;
            if matches!(t.mode, Mode::Pen | Mode::Finger) && t.settings.draw_and_hold {
                t.hold_due = None;
                drop(t);
                if self.quickshape_hold().is_some() {
                    out.push(TouchCommand::ShapeSnapped);
                }
                t = self.touches();
            }
        }
        if t.hold_due.is_some_and(|due| due <= now_ms) {
            t.hold_due = None;
            if matches!(t.mode, Mode::Finger | Mode::Idle | Mode::Turn)
                && let Some(purpose) = t.settings.hold
            {
                let painting = t.mode == Mode::Finger;
                let at = t.down_at;
                t.mode = if purpose == GestureAction::Eyedropper { Mode::Pick } else { Mode::Idle };
                t.pick_pen = false;
                let tool = t.tool_before.take();
                drop(t);
                if painting {
                    self.stroke_cancel();
                    out.push(TouchCommand::CursorGone);
                }
                if let Some(before) = tool {
                    self.put_tool(before);
                }
                Self::act(purpose, at, &mut out);
                t = self.touches();
            }
        }
        if t.rapid_due.is_some_and(|due| due <= now_ms) {
            t.rapid_due = None;
            // two fingers held still keep undoing, three redoing; held so, they are no tap
            let still = t.tap_moved < FINGERS_TAP_SLOP * t.scale;
            if t.mode == Mode::Gesture && still && t.down.len() == t.tap_pointers && self.undo_taps_allowed(&t) {
                match t.down.len() {
                    2 => out.push(TouchCommand::Undo),
                    3 => out.push(TouchCommand::Redo),
                    _ => return out,
                }
                t.tap_done = true;
                t.rapid_due = Some(now_ms + RAPID_REPEAT_MS);
            }
        }
        out
    }
}

/// The touches' state, kept with the session's.
pub(crate) type TouchState = Mutex<Touches>;


#[cfg(test)]
mod tests;
