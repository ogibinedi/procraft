use super::*;

impl CanvasSession {
    /// What a drag from window point `at` does to perspective blur's puck, if it takes it at all
    /// (`scale`: window pixels a point).
    pub(super) fn perspective_drag_at(&self, at: (f32, f32), scale: f32) -> Option<PuckDrag> {
        let (kind, puck, arrow) = self.perspective_puck_at()?;
        let (dx, dy) = (at.0 - puck.0, at.1 - puck.1);
        let far = dx.hypot(dy) / scale.max(1e-6);
        let moves = Some(PuckDrag::Move(dx, dy));
        match kind {
            PerspectiveKind::Positional => moves.filter(|_| far < POSITIONAL_GRAB),
            PerspectiveKind::Directional if far <= DIRECTIONAL_INNER => moves,
            // the arrow's side (within a right angle of it) turns it
            PerspectiveKind::Directional if far <= DIRECTIONAL_REACH => {
                if dx * arrow.cos() + dy * arrow.sin() > 0.0 {
                    Some(PuckDrag::Turn)
                } else {
                    moves
                }
            }
            PerspectiveKind::Directional => None,
        }
    }

    /// Layer Opacity's touch lifted (or another came down): what it dragged is one undo step.
    pub(super) fn opacity_lifted(&self, t: &mut Touches) {
        if let Some((layer, opacity)) = t.layer_opacity.filter(|_| std::mem::take(&mut t.opacity_moved)) {
            self.set_layer_opacity(layer, opacity, true);
        }
    }

    /// The first pointer down with a panel open: it waits to be a tap or a drag.
    pub(super) fn wait_for_panel(t: &mut Touches, kind: PointerKind, first: PenSample, samples: Vec<PenSample>) {
        t.down_at = (first.x, first.y);
        t.pan_last = None;
        t.tap_start = first.time_ms;
        t.tap_lifted = None;
        t.tap_pointers = 1;
        t.tap_moved = 0.0;
        t.tap_done = false;
        t.stroke_pointer = t.down.first().map_or(0, |d| d.id);
        t.held = Some(Held { kind, first: samples, moves: Vec::new() });
        t.mode = Mode::Held;
        if !is_pen(kind) && t.settings.hold.is_some() {
            t.hold_due = Some(first.time_ms + t.settings.hold_ms);
        }
    }

    /// The touch waiting for a panel is no tap: it does what it would have done from where it came
    /// down, then its input since; a drag first fades the panel (`fades`), a hold leaves it up.
    pub(super) fn held_goes_on(
        &self,
        mut t: std::sync::MutexGuard<'_, Touches>,
        fades: bool,
        out: &mut Vec<TouchCommand>,
    ) {
        let Some(held) = t.held.take() else { return };
        let Some(first) = last_of(&held.first).copied() else { return };
        let id = t.stroke_pointer;
        t.mode = Mode::None;
        t.hold_due = None;
        if fades {
            out.push(TouchCommand::PanelFades);
        }
        self.first_down(&mut t, held.kind, first, &held.first, out);
        drop(t);
        if !held.moves.is_empty() {
            out.extend(self.touch_move(id, held.moves));
        }
    }

    pub(super) fn first_down(
        &self,
        t: &mut Touches,
        kind: PointerKind,
        first: PenSample,
        samples: &[PenSample],
        out: &mut Vec<TouchCommand>,
    ) {
        let at = (first.x, first.y);
        let now = first.time_ms;
        t.down_at = at;
        t.pan_last = None;
        t.tap_start = now;
        t.tap_lifted = None;
        t.tap_pointers = 1;
        t.tap_moved = 0.0;
        t.tap_done = false;
        let id = t.down.first().map_or(0, |d| d.id);
        // the guide editor: the touch takes the handle it lands on, and nothing else (a tap adds a
        // perspective guide's point when it lifts)
        if t.guide_editing {
            t.stroke_pointer = id;
            t.mode = Mode::Guide;
            t.guide_moved = false;
            t.guide_grab = self.guide_grab(at.0, at.1, t.handle_scale);
            out.push(TouchCommand::GuideTouched);
            return;
        }
        // the modify button was tapped (or the pen is squeezed for the eyedropper, and touches):
        // this touch picks a colour
        if t.pick_next || (is_pen(kind) && t.pen.squeeze_takes_touch()) {
            t.mode = Mode::Pick;
            t.pick_pen = is_pen(kind);
            out.push(TouchCommand::Pick { x: at.0, y: at.1 });
            return;
        }
        // Hover: with the pen held over the canvas, a finger drags the opacity and a pinch the size
        let hovering = t.pen_hovering.is_some_and(|at| at.elapsed().as_secs_f32() < PEN_HOVER_STALE_S);
        if kind == PointerKind::Finger && hovering && t.settings.hover_touch && self.shared.lock().shape.is_none() {
            t.stroke_pointer = id;
            t.hover_x = at.0;
            t.hover_spread = 0.0;
            t.mode = Mode::Hover;
            return;
        }
        if t.layer_opacity.is_some() {
            // Layer Opacity: the touch drags the layer's opacity
            t.stroke_pointer = id;
            t.pour_last = (at.0, now);
            t.opacity_moved = false;
            t.mode = Mode::Opacity;
            return;
        }
        if self.shared.lock().recolor.is_some() {
            // Recolor: the touch moves its reticle, or a tap starts it again
            t.stroke_pointer = id;
            t.recolor_dragged = false;
            t.recolor_from = self.recolor_point().and_then(|p| Some((*p.first()?, *p.get(1)?))).unwrap_or(at);
            t.mode = Mode::Recolor;
            return;
        }
        // a snapped shape's handle is dragged; while it is edited, a drag on its outline turns and
        // resizes it, one elsewhere moves it (a tap ends the editing); anything else keeps the
        // shape and draws on
        let grab = QUICKSHAPE_GRAB * t.handle_scale;
        if t.shape_editing
            && let Some(info) = self.quickshape_info()
        {
            let handle = info
                .handles
                .as_chunks::<2>()
                .0
                .iter()
                .enumerate()
                .map(|(i, &[x, y])| (i, (x, y), dist((x, y), at)))
                .filter(|(.., d)| *d <= grab)
                .min_by(|a, b| a.2.total_cmp(&b.2));
            if let Some((i, node, _)) = handle {
                t.mode = Mode::Shape(i as u32);
                t.shape_dragged = false;
                t.shape_last = node;
                return;
            }
            t.stroke_pointer = id;
            t.shape_dragged = false;
            t.shape_last = at;
            if self.quickshape_near_edge(at.0, at.1, grab) {
                t.mode = Mode::ShapePin;
                self.quickshape_pin(at.0, at.1, true);
                out.push(TouchCommand::ShapeChanged);
            } else {
                t.mode = Mode::ShapeMove;
            }
            return;
        }
        self.end_shape_with(t, out);
        let adjusting = self.shared.lock().adjust.open();
        let draws = is_pen(kind) || (kind == PointerKind::Mouse && !t.settings.pointer_follows_touch);
        if let Some((open, pencil)) = adjusting
            && (open == Adjustment::Clone || (pencil && draws))
        {
            t.stroke_pointer = id;
            if let Some(puck) = self.clone_puck_at()
                && dist(puck, at) <= (CLONE_PUCK_RADIUS + CLONE_PUCK_SLOP) * t.handle_scale
            {
                // the clone source is dragged
                t.puck_grab = (at.0 - puck.0, at.1 - puck.1);
                t.mode = Mode::Puck;
                return;
            }
            // cloning, and the pen (or a mouse, a desktop's drawing tool) painting an adjustment
            // on, paint (no eyedropper); the clone source follows the brush
            t.mode = if is_pen(kind) { Mode::Pen } else { Mode::Finger };
            t.hold_at = at;
            self.clone_stroke_at(at, true);
            self.stroke_begin(kind, samples.to_vec());
            return;
        }
        // an adjustment open otherwise: the touch drags it (or liquifies) and nothing paints
        if let Some((open, _)) = adjusting {
            t.stroke_pointer = id;
            t.adjust_moved = false;
            t.adjust_displacing = false;
            t.pour_last = (at.0, now);
            t.mode = Mode::Adjust;
            // perspective blur: a touch on its puck moves the puck or turns its arrow
            t.puck_drag = self.perspective_drag_at(at, t.handle_scale);
            t.puck_dragging = false;
            if open == Adjustment::Liquify {
                t.liquify_ms = now;
                t.liquify_tick_ms = now;
                t.liquify_pen = is_pen(kind);
                t.liquify_rest = at;
                t.liquify_rest_ms = now;
                t.liquify_held = 0.0;
                t.liquify_due = Some(now + LIQUIFY_HELD_STEP_MS);
                self.adjustment_liquify_stroke(liquify_points(t.liquify_pen, samples, now), true, t.liquify_pen);
                out.push(TouchCommand::AdjustChanged);
            }
            return;
        }
        // a picture being placed on a model is moved by what lands on it (a pen or mouse, anywhere);
        // otherwise a finger turns the model under it
        let placing = self.shared.lock().scene_picture_under(at);
        if placing == Some(true) || (placing.is_some() && !as_finger(kind, &t.settings)) {
            t.stroke_pointer = id;
            t.turn_last = at;
            t.mode = Mode::Picture;
            return;
        }
        // on a shown model a finger still turns it while a selection is drawn with the pen
        if t.selecting.is_some() && !self.finger_turns_model(kind, t) {
            t.stroke_pointer = id;
            t.select_moved = false;
            t.lasso_snapped = false;
            t.hold_at = at;
            t.pour_last = (at.0, now);
            t.select_threshold = t.selecting.map_or(0.5, |s| s.threshold);
            t.mode = Mode::Select;
            return;
        }
        // a transform: the touch takes the node it lands on (or the content), nothing paints
        if let Some(how) = t.transforming {
            t.stroke_pointer = id;
            t.transform_grabbed = false;
            t.transform_handle = self.transform_handle_at(at, how);
            t.mode = Mode::Transform;
            out.push(TouchCommand::TransformTouched);
            return;
        }
        // Continue filling: the touch fills where it lands; slid, it changes the threshold
        if let Some(fill) = t.filling {
            t.stroke_pointer = id;
            t.pour_moved = false;
            t.pour_last = (at.0, now);
            if self.current_is_text() {
                out.push(TouchCommand::TextLayer);
                t.mode = Mode::Idle;
            } else {
                let poured = self.pour_begin(at.0, at.1, fill.r, fill.g, fill.b, fill.threshold);
                t.mode = if poured { Mode::Pour } else { Mode::Idle };
            }
            return;
        }
        // on a shown model a finger turns it, unless set to paint; the pen paints. Held still, it
        // does what a hold does anywhere
        let on_model = as_finger(kind, &t.settings) && self.shared.lock().scene_shown();
        if on_model && !t.settings.model_finger_paint {
            t.stroke_pointer = id;
            t.turn_last = at;
            t.mode = Mode::Turn;
            if t.settings.hold.is_some() {
                t.hold_due = Some(now + t.settings.hold_ms);
            }
            return;
        }
        let pen = is_pen(kind);
        // what this kind of touch is set to do; the pen's button held to erase, the pen erases
        // with the eraser it borrowed whatever its strokes would borrow otherwise
        let mut does = if pen { t.settings.pen } else { t.settings.touch };
        if pen && t.held_eraser.is_some() && matches!(does, Some(GestureAction::Smudge | GestureAction::Erase)) {
            does = None;
        }
        if !pen {
            match does {
                Some(GestureAction::Eyedropper) => {
                    t.mode = Mode::Pick;
                    t.pick_pen = false;
                    out.push(TouchCommand::Pick { x: at.0, y: at.1 });
                    return;
                }
                Some(
                    a @ (GestureAction::QuickMenu
                    | GestureAction::CopyPaste
                    | GestureAction::FullScreen
                    | GestureAction::LayerSelect
                    | GestureAction::QuickShape),
                ) => {
                    t.mode = Mode::Idle;
                    Self::act(a, at, out);
                    return;
                }
                _ => {}
            }
        }
        // a finger set not to paint (a mouse, a desktop's drawing tool, paints as the pen does
        // unless it follows Touch)
        if as_finger(kind, &t.settings) && does.is_none() && !t.settings.finger_paint && !on_model {
            t.mode = Mode::Idle;
            // set to move the canvas: it does once it moves past a tap's slop
            if t.settings.one_finger_pan {
                t.stroke_pointer = id;
                t.pan_last = Some(at);
                t.panned = false;
            }
            if t.settings.hold.is_some() {
                t.hold_due = Some(now + t.settings.hold_ms);
            }
            return;
        }
        t.stroke_pointer = id;
        t.mode = if pen { Mode::Pen } else { Mode::Finger };
        let other = match does {
            Some(GestureAction::Erase) => Some(ToolKind::Erase),
            Some(GestureAction::Smudge) => Some(ToolKind::Smudge),
            _ => None,
        };
        if let Some(tool) = other {
            t.tool_before = Some(self.swap_tool(tool));
        }
        t.assisted = does == Some(GestureAction::Assisted);
        if t.assisted {
            self.set_stroke_assist(true);
        }
        if self.current_is_text() {
            out.push(TouchCommand::TextLayer);
        }
        self.stroke_begin(kind, samples.to_vec());
        out.push(TouchCommand::Cursor { x: at.0, y: at.1 });
        if t.mode == Mode::Finger && t.settings.hold.is_some() {
            t.hold_due = Some(now + t.settings.hold_ms);
        }
        t.hold_at = at;
        if t.settings.draw_and_hold {
            t.quickshape_due = Some(now + t.settings.quickshape_ms);
        }
    }

    /// Whether a touch of `kind` on the screen turns a shown model.
    pub(super) fn finger_turns_model(&self, kind: PointerKind, t: &Touches) -> bool {
        as_finger(kind, &t.settings) && !t.settings.model_finger_paint && self.shared.lock().scene_shown()
    }

    pub(super) fn another_down(&self, t: &mut Touches, kind: PointerKind, now: f64, out: &mut Vec<TouchCommand>) {
        // Hover's second finger: the two pinch the brush's size from how far apart they now are
        if t.mode == Mode::Hover {
            if let (Some(a), Some(b)) = (t.down.first(), t.down.get(1)) {
                t.hover_spread = dist(a.at, b.at);
            }
            return;
        }
        // fingers landing after the others tapped or repeated do nothing until all are up
        if t.tap_done {
            t.mode = Mode::Idle;
            t.rapid_due = None;
            return;
        }
        t.hold_due = None;
        t.quickshape_due = None;
        t.pan_last = None;
        // a finger down while the stroke is held as a snapped shape snaps it, until lifted
        let snapped = self.shared.lock().shape.is_some();
        if matches!(t.mode, Mode::Pen | Mode::Finger | Mode::ShapePin) && snapped {
            if kind == PointerKind::Finger {
                self.quickshape_finger(true);
                out.push(TouchCommand::ShapeChanged);
            }
            return;
        }
        // (a pen waiting for a panel is a pen: fingers landing leave it to draw)
        let pen_held = t.held.as_ref().is_some_and(|h| is_pen(h.kind));
        if matches!(t.mode, Mode::Pen | Mode::Pick) || pen_held {
            return;
        }
        t.held = None;
        // a second finger takes back the fill the first one made, and a transform's drag (a move
        // of what the box holds is kept when the fingers go on to take it); it keeps the opacity
        // dragged so far
        if t.mode == Mode::Pour {
            self.pour_end(false, None);
        }
        if t.mode == Mode::Opacity {
            self.opacity_lifted(t);
        }
        // a colour selection under way is kept as it was slid; a shape being dragged out goes
        if t.mode == Mode::Select {
            let start = t.down_at;
            let (moved, how, threshold) = (t.select_moved, t.selecting, t.select_threshold);
            if let Some(how) = how {
                match how.kind {
                    SelectKind::Automatic if moved => {
                        self.select_colour_at(start, threshold, how.join, false);
                        out.push(TouchCommand::SelectionThreshold { x: start.0, y: start.1, threshold, done: true });
                        out.push(TouchCommand::SelectionChanged);
                    }
                    SelectKind::Rectangle | SelectKind::Ellipse => out.push(TouchCommand::SelectionChanged),
                    _ => {}
                }
            }
        }
        self.second_finger_on_box(t, out);
        out.push(TouchCommand::CursorGone);
        if t.mode == Mode::Finger {
            self.stroke_cancel();
        }
        // so is a liquify stroke under way, what it did and all: two fingers tapped undo the last
        // stroke before it, three redo, and nothing undone is lost to it
        if t.mode == Mode::Adjust && self.take_back_liquify() {
            t.liquify_due = None;
            out.push(TouchCommand::AdjustChanged);
        }
        self.end_stroke_tools(t);
        if matches!(
            t.mode,
            Mode::Finger
                | Mode::Adjust
                | Mode::Idle
                | Mode::Pour
                | Mode::Transform
                | Mode::Select
                | Mode::Recolor
                | Mode::Turn
                | Mode::Picture
                | Mode::Held
                | Mode::Guide
        ) {
            t.gesture_scale = 1.0;
            t.pinch.clear();
            t.pinch.push((now, 1.0));
        }
        if t.mode != Mode::Gesture {
            t.view_moving = false;
        }
        t.mode = Mode::Gesture;
        // a tap's time counts from when its last finger landed; those down already have gone as
        // far as they have
        if t.down.len() > t.tap_pointers {
            t.tap_start = now;
            t.tap_pointers = t.down.len();
        }
        Self::note_fingers_moved(t);
        Self::follow_fingers(t);
        if t.down.len() == 3 {
            let c = Self::centre(t);
            t.swipe_start = c;
            t.swiped = false;
            t.scrub_x = c.0;
            t.scrub_direction = 0;
            t.scrub_travel = 0.0;
            t.scrub_turns = if t.settings.scrub { 0 } else { -1 };
        }
        t.rapid_due = Some(now + t.settings.rapid_undo_ms);
        // in the guide editor the fingers only move the view: the handle taken stays where it was
        // dragged, and they neither repeat, clear the layer nor swipe
        if t.guide_editing {
            t.guide_grab = None;
            t.rapid_due = None;
            t.scrub_turns = -1;
            t.swiped = true;
        }
    }

    pub(super) fn lifted(&self, id: u64, samples: Vec<PenSample>, cancelled: bool) -> Vec<TouchCommand> {
        let mut out = Vec::new();
        let mut t = self.touches();
        // the pen touching at no pressure, taken back: it hovers no more
        if t.pen_unpressed.is_some_and(|(unpressed, _)| unpressed == id) {
            t.pen_unpressed = None;
            t.pen_waiting.clear();
            t.pen_wait_due = None;
            drop(t);
            return self.pen_hover_ends();
        }
        if t.pen_pointer == Some(id) {
            t.pen_pointer = None;
            match last_of(&samples) {
                Some(last) => t.pen_lifted_ms = Some(last.time_ms),
                None => t.pen_left = Some(std::time::Instant::now()),
            }
        }
        // the hand's touches lift as they landed: doing nothing
        if t.palm_wait.as_ref().is_some_and(|(waiting, _, _)| *waiting == id) {
            t.palm_wait = None;
            return out;
        }
        if let Some(i) = t.palms.iter().position(|palm| *palm == id) {
            t.palms.remove(i);
            return out;
        }
        let Some(i) = t.down.iter().position(|d| d.id == id) else { return out };
        t.down.remove(i);
        let now = last_of(&samples).map_or(t.tap_start, |s| s.time_ms);
        // the touch made while the modify button is held ends
        if let Some((pointer, purpose)) = t.modify_pointer
            && pointer == id
        {
            t.modify_pointer = None;
            if t.down.is_empty() {
                t.mode = Mode::None;
            }
            drop(t);
            match purpose {
                GestureAction::Eyedropper => {
                    out.push(if cancelled { TouchCommand::PickCancel } else { TouchCommand::PickDone })
                }
                GestureAction::Smudge | GestureAction::Erase | GestureAction::Assisted => {
                    if cancelled {
                        self.stroke_cancel();
                    } else {
                        self.stroke_end(samples);
                    }
                    self.set_stroke_assist(false);
                    if let Some(before) = self.touches().tool_before.take() {
                        self.put_tool(before);
                    }
                    out.push(TouchCommand::StrokeEnded);
                }
                _ => {}
            }
            return out;
        }
        if !t.down.is_empty() {
            // one pointer taken back: a stroke of its goes, and what is left is no tap
            if cancelled {
                t.tap_moved = f32::INFINITY;
                if t.mode == Mode::Finger && id == t.stroke_pointer {
                    t.mode = Mode::Idle;
                    t.hold_due = None;
                    t.quickshape_due = None;
                    self.end_stroke_tools(&mut t);
                    drop(t);
                    self.stroke_cancel();
                    out.push(TouchCommand::CursorGone);
                    return out;
                }
            }
            if t.mode == Mode::Gesture {
                let first = t.tap_lifted.is_none();
                t.tap_lifted.get_or_insert(now);
                let parted = t.gesture_pair.is_some_and(|(a, b)| a == id || b == id);
                let pinched = parted && self.end_box_pinch(&mut t, cancelled, &mut out);
                Self::follow_fingers(&mut t);
                // two fingers parting as they close fast fit a canvas pinched smaller than it
                // fits, settling into place (a tap's fingers aren't a pinch), unless a plugin keeps
                // the fit
                let tap = Self::fingers_tapped(&t, now);
                let fits = !t.fit_locked && t.tap_pointers == 2 && t.gesture_pair.is_none();
                if parted && !pinched && !cancelled && !tap && fits {
                    let flicked = Self::pinch_speed(&t, now) < QUICK_PINCH_SPEED;
                    if flicked && self.pinched_smaller_than_fit() {
                        out.push(TouchCommand::Fit);
                    }
                }
                // let go turned within 5° of a right angle, the canvas turns onto it (a fit
                // straightens it anyway)
                if parted
                    && !pinched
                    && !cancelled
                    && !tap
                    && t.view_moving
                    && !t.turn_locked
                    && t.gesture_pair.is_none()
                    && !out.contains(&TouchCommand::Fit)
                    && self.settle_turn()
                {
                    out.push(TouchCommand::ViewSettles);
                }
                // the first lift decides a tap, and it does what it does there: the fingers left
                // down, wherever they go, do nothing more
                if first && tap && !cancelled {
                    self.fingers_did_tap(&mut t, &mut out);
                }
            }
            // the last finger other than the stroke's lifted from a held snapped shape
            let stroke = t.stroke_pointer;
            if matches!(t.mode, Mode::Pen | Mode::Finger | Mode::ShapePin)
                && id != stroke
                && t.down.iter().all(|d| d.id == stroke)
                && self.shared.lock().shape.is_some()
            {
                drop(t);
                self.quickshape_finger(false);
                out.push(TouchCommand::ShapeChanged);
            }
            return out;
        }
        let mode = t.mode;
        t.mode = Mode::None;
        t.hold_due = None;
        t.quickshape_due = None;
        t.rapid_due = None;
        out.push(TouchCommand::CursorGone);
        match mode {
            Mode::Pen | Mode::Finger => {
                self.end_stroke_tools(&mut t);
                drop(t);
                if cancelled {
                    self.stroke_cancel();
                } else {
                    self.stroke_end(samples);
                    // a snapped shape the pen moved: its handles where it now is
                    out.push(TouchCommand::StrokeEnded);
                }
            }
            Mode::Pick => {
                t.pick_next = false;
                out.push(if cancelled { TouchCommand::PickCancel } else { TouchCommand::PickDone });
            }
            Mode::Adjust => {
                let at = last_of(&samples).map_or(t.down_at, |s| (s.x, s.y));
                let liquifying = self.shared.lock().adjust.open().is_some_and(|o| o.0 == Adjustment::Liquify);
                if liquifying {
                    // still moving when it lifts, liquify carries on; called off, its stroke is
                    // taken back
                    drop(t);
                    if cancelled && self.take_back_liquify() {
                        out.push(TouchCommand::AdjustChanged);
                    }
                    let going = !cancelled && self.adjustment_liquify_lift();
                    self.touches().liquify_due = going.then_some(now + LIQUIFY_HELD_STEP_MS);
                } else if !cancelled && !t.adjust_moved && now - t.tap_start <= TAP_MS {
                    out.push(TouchCommand::AdjustMenu { x: at.0, y: at.1 });
                }
            }
            Mode::Select => {
                let (how, start) = (t.selecting, t.down_at);
                drop(t);
                if !cancelled {
                    let at = last_of(&samples).map(|s| ((s.x, s.y), s.time_ms));
                    if let Some((at, ms)) = at {
                        self.select_step(at, ms, true, &mut out);
                    }
                } else if let Some(how) = how.filter(|how| how.kind == SelectKind::Automatic)
                    && self.take_back_colour_drag()
                {
                    // a colour selection being slid goes with its touch, its threshold left as it
                    // began; the app's showing of it ends
                    let (x, y) = start;
                    out.push(TouchCommand::SelectionThreshold { x, y, threshold: how.threshold, done: true });
                    out.push(TouchCommand::SelectionChanged);
                }
            }
            Mode::Transform => {
                let (handle, grabbed, tapped) = (t.transform_handle, t.transform_grabbed, now - t.tap_start <= TAP_MS);
                let at = last_of(&samples).map_or(t.down_at, |s| (s.x, s.y));
                let how = t.transforming;
                t.transform_grabbed = false;
                drop(t);
                if cancelled {
                    if grabbed {
                        out.push(TouchCommand::TransformMoved { transform: self.transform_cancel_drag() });
                    }
                } else if grabbed {
                    out.push(TouchCommand::TransformMoved { transform: self.transform_release() });
                    out.push(TouchCommand::TransformLetGo { handle, x: at.0, y: at.1 });
                } else if tapped && handle == crate::TransformHandle::Move {
                    // a tap off the nodes nudges the content a pixel towards it
                    out.push(TouchCommand::TransformMoved { transform: self.transform_nudge(at.0, at.1) });
                } else if tapped && how.is_some() {
                    // a tap on a node types its numbers beside it: the rotate node its angle, the
                    // others the size
                    if let Some(command) = self.numeric_at(handle) {
                        out.push(command);
                    }
                }
            }
            Mode::Opacity => self.opacity_lifted(&mut t),
            Mode::Held => {
                t.held = None;
                if !cancelled {
                    out.push(TouchCommand::PanelTapped);
                }
            }
            // a tap starts the reticle again where it lands
            Mode::Recolor if !t.recolor_dragged && !cancelled => {
                let at = last_of(&samples).map_or(t.down_at, |s| (s.x, s.y));
                out.push(TouchCommand::RecolorTapped { x: at.0, y: at.1 });
            }
            Mode::Pour => {
                let slid = last_of(&samples).and_then(|s| Self::pour_slide(&mut t, (s.x, s.y), s.time_ms));
                drop(t);
                if let Some(threshold) = slid.filter(|_| !cancelled) {
                    self.pour_threshold(threshold);
                    out.push(TouchCommand::FillThreshold { threshold });
                }
                self.pour_end(!cancelled, None);
                out.push(TouchCommand::Filled);
            }
            // a tap in the guide editor: on a handle, its menu (the turn handle has none); elsewhere a
            // perspective guide's new point, where it came down
            Mode::Guide if !cancelled && !t.guide_moved && now - t.tap_start <= TAP_MS => {
                let (grab, at) = (t.guide_grab.take(), t.down_at);
                drop(t);
                match grab {
                    Some(crate::guide::GuideGrab::Turn) => {}
                    Some(grab) => {
                        if let Some((x, y)) = self.guide_handle_at(grab) {
                            let point = match grab {
                                crate::guide::GuideGrab::Point(i) => Some(i),
                                _ => None,
                            };
                            out.push(TouchCommand::GuideNodeTapped { point, x, y });
                        }
                    }
                    None => {
                        if self.guide_tap(at.0, at.1) {
                            out.push(TouchCommand::GuideChanged);
                        }
                    }
                }
            }
            Mode::Guide => t.guide_grab = None,
            // a tap on a handle rather than a drag of it: a curve's anchor steps to its next state
            Mode::Shape(i) => {
                let tapped = !t.shape_dragged && now - t.tap_start <= TAP_MS && !cancelled;
                drop(t);
                if tapped && self.quickshape_cycle_anchor(i).is_some() {
                    out.push(TouchCommand::ShapeChanged);
                }
            }
            // a tap, on its outline or elsewhere, ends the editing; a drag only turned or moved it
            Mode::ShapePin | Mode::ShapeMove => {
                let tapped = !t.shape_dragged && now - t.tap_start <= TAP_MS;
                drop(t);
                if mode == Mode::ShapePin {
                    self.quickshape_unpin();
                    out.push(TouchCommand::ShapeChanged);
                }
                if tapped {
                    self.end_shape(&mut out);
                }
            }
            _ => {}
        }
        out
    }

    /// Lets a snapped shape go as it is (a touch elsewhere).
    pub(super) fn end_shape(&self, out: &mut Vec<TouchCommand>) {
        let mut t = self.touches();
        self.end_shape_with(&mut t, out);
    }

    pub(super) fn end_shape_with(&self, t: &mut Touches, out: &mut Vec<TouchCommand>) {
        if self.shared.lock().shape.is_some() {
            self.quickshape_done();
            t.shape_editing = false;
            out.push(TouchCommand::ShapeEnded);
        }
    }

    /// The touch made while the modify button is held begins.
    pub(super) fn modify_begins(
        &self,
        purpose: GestureAction,
        kind: PointerKind,
        samples: &[PenSample],
        out: &mut Vec<TouchCommand>,
    ) {
        let Some(first) = samples.last() else { return };
        let at = (first.x, first.y);
        match purpose {
            GestureAction::Eyedropper => {
                self.touches().pick_pen = is_pen(kind);
                out.push(TouchCommand::Pick { x: at.0, y: at.1 });
            }
            GestureAction::Smudge | GestureAction::Erase | GestureAction::Assisted => {
                match purpose {
                    GestureAction::Erase => self.touches().tool_before = Some(self.swap_tool(ToolKind::Erase)),
                    GestureAction::Smudge => self.touches().tool_before = Some(self.swap_tool(ToolKind::Smudge)),
                    _ => self.set_stroke_assist(true),
                }
                if self.current_is_text() {
                    out.push(TouchCommand::TextLayer);
                }
                self.stroke_begin(kind, samples.to_vec());
            }
            a => Self::act(a, at, out),
        }
    }

    /// The tool and drawing assist as they were before a stroke that used others.
    pub(super) fn end_stroke_tools(&self, t: &mut Touches) {
        if let Some(before) = t.tool_before.take() {
            self.put_tool(before);
        }
        if std::mem::take(&mut t.assisted) {
            self.set_stroke_assist(false);
        }
    }

    /// Paints with `tool` for a stroke; returns the tool chosen before, to put back after (as for
    /// the stroke only, neither counts as the tool chosen last).
    pub(super) fn swap_tool(&self, tool: ToolKind) -> ToolKind {
        let before = self.shared.lock().tool;
        self.put_tool(tool);
        before
    }

    /// A step of the selection tool's touch at `at` (`done` as it lifts): a rectangle or ellipse
    /// dragged out; a freehand path drawn (or tapped point by point, closed by a tap on its first
    /// point; held still, it snaps to a shape); a colour picked where it went down, its threshold
    /// slid left and right, faster the faster it goes.
    pub(super) fn select_step(&self, at: (f32, f32), ms: f64, done: bool, out: &mut Vec<TouchCommand>) {
        let mut t = self.touches();
        let Some(how) = t.selecting else { return };
        let start = t.down_at;
        if dist(at, start) > TRANSFORM_SLOP * t.scale {
            t.select_moved = true;
        }
        let moved = t.select_moved;
        // on a plugin's layer a tap takes the item under it, or on nothing lets the selection go
        // (but for a freehand path, tapped out point by point: on nothing that goes on)
        let freehand = how.kind == SelectKind::Freehand;
        if done && !moved && !(freehand && !t.freehand.is_empty()) {
            let reach = TAP_REACH * t.scale;
            drop(t);
            let tapped = {
                let mut st = self.shared.lock();
                let current = st.doc.current();
                let zoom = st.view.map_or(1.0, |v| v.zoom()).max(1e-6);
                st.window_to_canvas(at.0, at.1)
                    .is_some_and(|p| st.tap_items(current, p, reach / zoom, how.join, !freehand))
            };
            if tapped {
                out.push(TouchCommand::SelectionChanged);
                return;
            }
            t = self.touches();
        }
        match how.kind {
            SelectKind::Rectangle | SelectKind::Ellipse => {
                drop(t);
                if done {
                    if dist(at, start) >= 1.0 {
                        let ellipse = how.kind == SelectKind::Ellipse;
                        self.select_shape(ellipse, start.0, start.1, at.0, at.1, how.join);
                    }
                    out.push(TouchCommand::SelectionChanged);
                } else if moved {
                    out.push(TouchCommand::SelectionDrag { x0: start.0, y0: start.1, x1: at.0, y1: at.1 });
                }
            }
            SelectKind::Freehand => {
                // snapped: the shape is kept as it is, and the lift selects it
                if t.lasso_snapped {
                    if done {
                        t.lasso_snapped = false;
                        drop(t);
                        self.close_freehand();
                        out.push(TouchCommand::FreehandChanged);
                        out.push(TouchCommand::SelectionChanged);
                    }
                    return;
                }
                if done {
                    t.quickshape_due = None;
                } else if moved && dist(at, t.hold_at) > QUICKSHAPE_STILL * t.scale {
                    // still for the QuickShape delay, it snaps
                    t.hold_at = at;
                    t.quickshape_due = t.settings.draw_and_hold.then_some(ms + t.settings.quickshape_ms);
                }
                let scale = t.scale;
                let last = t.freehand.last().copied();
                drop(t);
                let (to_canvas, to_window) = {
                    let st = self.shared.lock();
                    let view = st.view;
                    (
                        st.window_to_canvas(at.0, at.1).map(|p| (p.x, p.y)),
                        last.and_then(|(x, y)| {
                            view.map(|v| v.to_screen().apply(procraft_engine::geom::Vec2::new(x, y)))
                        }),
                    )
                };
                let first_canvas = self.shared.lock().window_to_canvas(start.0, start.1).map(|p| (p.x, p.y));
                if done && !moved {
                    // a tap: a point of the path, or, by its first point, the path closed
                    let mut t = self.touches();
                    let first = t.freehand.first().copied();
                    let near_first = first.zip(self.shared.lock().view).is_some_and(|((x, y), v)| {
                        let p = v.to_screen().apply(procraft_engine::geom::Vec2::new(x, y));
                        dist((p.x, p.y), at) <= FREEHAND_CLOSE * scale
                    });
                    if t.freehand.len() > 2 && near_first {
                        drop(t);
                        self.close_freehand();
                        out.push(TouchCommand::SelectionChanged);
                    } else if let Some(p) = to_canvas {
                        t.freehand.push(p);
                    }
                    out.push(TouchCommand::FreehandChanged);
                } else if moved && to_window.is_none_or(|w| dist((w.x, w.y), at) >= FREEHAND_STEP * scale) {
                    let mut t = self.touches();
                    if t.freehand.is_empty()
                        && let Some(first) = first_canvas
                    {
                        t.freehand.push(first);
                    }
                    if let Some(p) = to_canvas {
                        t.freehand.push(p);
                    }
                    out.push(TouchCommand::FreehandChanged);
                }
            }
            SelectKind::Automatic => {
                // across the screen the threshold follows the drag, faster the faster it goes
                let (last_x, last_ms) = t.pour_last;
                t.pour_last = (at.0, ms);
                let dx = (at.0 - last_x) / t.scale;
                t.select_threshold = (t.select_threshold + drag_step(dx, ms - last_ms)).clamp(0.0, 1.0);
                let threshold = t.select_threshold;
                drop(t);
                if done {
                    self.select_colour_at(start, threshold, how.join, false);
                    out.push(TouchCommand::SelectionThreshold { x: start.0, y: start.1, threshold, done });
                    out.push(TouchCommand::SelectionChanged);
                } else if moved {
                    // shown by the host once a frame: a selection by colour takes too long on a big
                    // canvas to redo at every move
                    out.push(TouchCommand::SelectionThreshold { x: start.0, y: start.1, threshold, done });
                }
            }
        }
    }

    /// Selects by colour at `at` with `threshold`: `live` while it is being slid (shown filled),
    /// else as it stays (tinted).
    pub(super) fn select_colour_at(&self, at: (f32, f32), threshold: f32, join: crate::SelectionJoin, live: bool) {
        let display = if live { crate::SelectionDisplay::Fill } else { crate::SelectionDisplay::Tint };
        self.set_selection_display(display);
        self.select_by_colour(at.0, at.1, threshold, join, live);
    }

    /// What a transform's touch at `at` takes: a warp's shown control point, or the box's nearest
    /// node, else the content.
    pub(super) fn transform_handle_at(&self, at: (f32, f32), how: TransformTouch) -> crate::TransformHandle {
        let (shown, view, density, warping) = {
            let st = self.shared.lock();
            let warping = st.transform_mode == procraft_engine::transform::TransformMode::Warp;
            (st.transform_box(), st.view.map(|v| v.to_screen()), st.interface_scale(), warping)
        };
        let (Some(b), Some(m)) = (shown, view) else { return crate::TransformHandle::Move };
        let view = vec![m.a, m.b, m.c, m.d, m.e, m.f];
        // a warp's mesh stays with the content after it, but only a warp shows its points: the
        // other kinds take the box's nodes
        if warping && !b.mesh.is_empty() {
            return crate::mesh_handle_at(b.mesh, view, at.0, at.1, density, how.advanced_mesh);
        }
        crate::screen_box(b.corners, view, density)
            .map_or(crate::TransformHandle::Move, |s| crate::handle_at(s, at.0, at.1, density))
    }

    /// Typing a tapped node's numbers beside it: where the node is, and which side of the box.
    pub(super) fn numeric_at(&self, handle: crate::TransformHandle) -> Option<TouchCommand> {
        let (b, m, density) = {
            let st = self.shared.lock();
            (st.transform_box()?, st.view?.to_screen(), st.interface_scale())
        };
        let s = crate::screen_box(b.corners, vec![m.a, m.b, m.c, m.d, m.e, m.f], density)?;
        let point = |v: &[f32], i: usize| Some((*v.get(2 * i)?, *v.get(2 * i + 1)?));
        let node = match handle {
            crate::TransformHandle::Rotate => point(&s.rotate, 0)?,
            crate::TransformHandle::Corner { index } => point(&s.corners, usize::from(index))?,
            crate::TransformHandle::Edge { index } => point(&s.edges, usize::from(index))?,
            _ => return None,
        };
        let middle = s.corners.as_chunks::<2>().0.iter().map(|[x, _]| x).sum::<f32>() / 4.0;
        Some(TouchCommand::TransformNumeric {
            angle: handle == crate::TransformHandle::Rotate,
            x: node.0,
            y: node.1,
            right: node.0 > middle + 1.0,
        })
    }

    /// Whether the current layer is a text layer (it takes no paint until rasterized).
    pub(super) fn current_is_text(&self) -> bool {
        let st = self.shared.lock();
        st.doc.layer(st.doc.current()).is_some_and(|l| l.text.is_some())
    }

    /// What a gesture set to `action` does at `at`, for the app.
    pub(super) fn act(action: GestureAction, at: (f32, f32), out: &mut Vec<TouchCommand>) {
        out.push(match action {
            GestureAction::Eyedropper => TouchCommand::Pick { x: at.0, y: at.1 },
            GestureAction::QuickShape => TouchCommand::QuickShapeLast,
            GestureAction::QuickMenu => TouchCommand::QuickMenu { x: at.0, y: at.1 },
            GestureAction::FullScreen => TouchCommand::ToggleBars,
            GestureAction::ClearLayer => TouchCommand::ClearLayer,
            GestureAction::CopyPaste => TouchCommand::CopyPaste,
            GestureAction::LayerSelect => TouchCommand::LayerSelect { x: at.0, y: at.1 },
            // strokes of another kind aren't things to do
            GestureAction::Smudge | GestureAction::Erase | GestureAction::Assisted => return,
        });
    }

    /// Keeps how far any finger down has gone from where it landed, each by itself: fingers
    /// pinched or turned a little are still a tap, and a finger's wobble, which a touch screen
    /// reports move by move, takes it nowhere.
    pub(super) fn note_fingers_moved(t: &mut Touches) {
        t.tap_moved = t.down.iter().map(|d| dist(d.at, d.from)).fold(t.tap_moved, f32::max);
    }

    /// Whether fingers lifting at `now` (the first of them) were a tap: down together for no
    /// longer than a tap takes, none gone away, and not held until they repeated. It goes by the
    /// first lift: a finger left down after it counts for nothing, as measured.
    pub(super) fn fingers_tapped(t: &Touches, now: f64) -> bool {
        let lifted = t.tap_lifted.unwrap_or(now);
        lifted - t.tap_start <= TAP_MS && t.tap_moved < FINGERS_TAP_SLOP * t.scale && !t.tap_done
    }

    /// The fingers were a tap: two undo, three redo, four do what Four-finger tap is set to (none
    /// of them in the guide editor). Those left down do nothing more until all are up.
    pub(super) fn fingers_did_tap(&self, t: &mut Touches, out: &mut Vec<TouchCommand>) {
        let undo_taps = self.undo_taps_allowed(t);
        match t.tap_pointers {
            _ if t.guide_editing => {}
            2 if undo_taps => out.push(TouchCommand::Undo),
            3 if undo_taps => out.push(TouchCommand::Redo),
            4 => {
                if let Some(a) = t.settings.four_tap {
                    // in the middle of the screen, not under the fingers
                    t.pick_pen &= a != GestureAction::Eyedropper;
                    Self::act(a, t.down_at, out);
                }
            }
            _ => {}
        }
        // two on a transform's box let go of what it holds
        self.end_box_pinch(t, false, out);
        t.tap_done = true;
        t.mode = Mode::Idle;
        t.rapid_due = None;
    }

    /// Whether two and three fingers undo and redo: unless turned off, and even then while a
    /// transform, the selection tool or liquify is open.
    pub(super) fn undo_taps_allowed(&self, t: &Touches) -> bool {
        t.settings.undo_taps
            || t.transforming.is_some()
            || t.selecting.is_some()
            || self.shared.lock().adjust.open().is_some_and(|(open, _)| open == Adjustment::Liquify)
    }

    pub(super) fn follow_fingers(t: &mut Touches) {
        match t.down.as_slice() {
            [a, b, ..] => {
                t.gesture_pair = Some((a.id, b.id));
                t.last = pair(a.at, b.at);
            }
            _ => t.gesture_pair = None,
        }
    }

    /// How fast two fingers' spread changed (a part of where it started, a second) over the
    /// [`QUICK_PINCH_WINDOW_MS`] before `now`: nothing heard meanwhile counts as still.
    pub(super) fn pinch_speed(t: &Touches, now: f64) -> f32 {
        let Some(&(_, scale)) = t.pinch.last() else { return 0.0 };
        let from = now - QUICK_PINCH_WINDOW_MS;
        let before = t.pinch.iter().rev().find(|(ms, _)| *ms <= from).or(t.pinch.first());
        match before {
            Some(&(ms, was)) if now - ms > 0.5 => (scale - was) / ((now - ms) / 1000.0) as f32,
            _ => 0.0,
        }
    }

    /// The canvas as pinched is smaller on the screen than it is fitted.
    pub(super) fn pinched_smaller_than_fit(&self) -> bool {
        let st = self.shared.lock();
        st.view.is_some_and(|v| v.zoom() < st.fitted_view().zoom())
    }

    /// One step of two fingers at `ms`: the view follows them. True if there were two to follow.
    pub(super) fn move_gesture(&self, t: &mut Touches, ms: f64) -> bool {
        let Some((a, b)) = t.gesture_pair else { return false };
        let at = |id: u64| t.down.iter().find(|d| d.id == id).map(|d| d.at);
        let (Some(a), Some(b)) = (at(a), at(b)) else { return false };
        let (x, y, d, angle) = pair(a, b);
        let (lx, ly, ld, la) = t.last;
        let scale = if ld > 1.0 && d > 1.0 { d / ld } else { 1.0 };
        let mut turn = angle - la;
        if turn > std::f32::consts::PI {
            turn -= std::f32::consts::TAU;
        }
        if turn < -std::f32::consts::PI {
            turn += std::f32::consts::TAU;
        }
        if !t.settings.rotate || t.turn_locked {
            turn = 0.0;
        }
        t.gesture_scale *= scale;
        // the spread's recent values, one a moment, and the last from before the speed's window
        let spread = t.gesture_scale;
        match t.pinch.last_mut() {
            Some(last) if last.0 >= ms => last.1 = spread,
            _ => t.pinch.push((ms, spread)),
        }
        let from = ms - QUICK_PINCH_WINDOW_MS;
        let old = t.pinch.iter().skip(1).take_while(|(at, _)| *at <= from).count();
        t.pinch.drain(..old);
        t.last = (x, y, d, angle);
        let gone: f32 = t.down.iter().map(|d| dist(d.at, d.from)).sum();
        t.view_moving |= gone >= VIEW_SLOP * t.scale;
        // from where they are when it starts, so it doesn't jump
        if t.view_moving {
            self.view_gesture(lx, ly, x - lx, y - ly, scale, turn);
        }
        true
    }

    /// Three fingers rubbed back and forth (three turns) clear the current layer, once a gesture;
    /// swiped down, they do what Three-finger swipe is set to.
    pub(super) fn scrub(t: &mut Touches, out: &mut Vec<TouchCommand>) {
        let c = Self::centre(t);
        if !t.swiped && t.down.len() == 3 {
            let (dx, dy) = (c.0 - t.swipe_start.0, c.1 - t.swipe_start.1);
            if dy >= SWIPE_DOWN * t.scale && dx.abs() <= dy / 2.0 {
                t.swiped = true;
                t.scrub_turns = -1;
                if let Some(a) = t.settings.three_swipe {
                    t.pick_pen &= a != GestureAction::Eyedropper;
                    Self::act(a, c, out);
                }
            }
        }
        let dx = c.0 - t.scrub_x;
        t.scrub_x = c.0;
        if dx == 0.0 || t.scrub_turns < 0 {
            return;
        }
        let direction = if dx > 0.0 { 1 } else { -1 };
        if direction != t.scrub_direction {
            if t.scrub_direction != 0 && t.scrub_travel >= SCRUB_STEP * t.scale {
                t.scrub_turns += 1;
            }
            t.scrub_direction = direction;
            t.scrub_travel = 0.0;
        }
        t.scrub_travel += dx.abs();
        if t.scrub_turns >= SCRUB_TURNS {
            t.scrub_turns = -1;
            out.push(TouchCommand::ClearLayer);
        }
    }

    /// Continue filling's touch at `at`: slid past the slop, it moves the fill's threshold as a
    /// selection's follows a drag, only more gently. The threshold, when it moved.
    pub(super) fn pour_slide(t: &mut Touches, at: (f32, f32), ms: f64) -> Option<f32> {
        if dist(at, t.down_at) > POUR_SLOP * t.scale {
            t.pour_moved = true;
        }
        let (last_x, last_ms) = t.pour_last;
        t.pour_last = (at.0, ms);
        let dx = (at.0 - last_x) / t.scale;
        let moved = t.pour_moved;
        let fill = t.filling.as_mut().filter(|_| moved && dx != 0.0)?;
        fill.threshold = (fill.threshold + drag_step(dx, ms - last_ms) / POUR_SLIDE_GENTLER).clamp(0.0, 1.0);
        Some(fill.threshold)
    }

    pub(super) fn centre(t: &Touches) -> (f32, f32) {
        let n = t.down.len().max(1) as f32;
        let (x, y) = t.down.iter().fold((0.0, 0.0), |(x, y), d| (x + d.at.0, y + d.at.1));
        (x / n, y / n)
    }
}
