//! View gestures, orientation, colour space, backdrops, and settling on a [`CanvasSession`].

use super::*;

#[uniffi::export]
impl CanvasSession {
    /// A step of a two-finger gesture (see `View::gesture`).
    pub fn view_gesture(&self, pivot_x: f32, pivot_y: f32, pan_x: f32, pan_y: f32, scale: f32, rotation: f32) {
        let mut st = self.shared.lock();
        // the fingers take the canvas from wherever it was settling to
        st.view_motion = None;
        if st.scene_picture_under((pivot_x, pivot_y)) == Some(true) {
            drop(st);
            self.model_picture_move(pan_x, pan_y, scale, rotation);
            return;
        }
        if st.scene_shown() {
            st.scene_gesture(Vec2::new(pan_x, pan_y), scale);
            self.shared.frame(&mut st);
            return;
        }
        if let Some(view) = st.view.as_mut() {
            view.gesture(Vec2::new(pivot_x, pivot_y), Vec2::new(pan_x, pan_y), scale, rotation);
            self.shared.frame(&mut st);
        }
    }

    /// Shows the canvas in perceptual greys (`on`), to judge its values, or in colour: only as
    /// the window shows it, not as it is exported, recorded or picked from.
    pub fn set_grey_view(&self, on: bool) {
        let mut st = self.shared.lock();
        if st.grey_view != on {
            st.grey_view = on;
            self.shared.frame(&mut st);
        }
    }

    pub fn grey_view(&self) -> bool {
        self.shared.lock().grey_view
    }

    /// Whether the system asks for less motion (Reduce Motion, animations off): a dropped colour's
    /// fill then shows at once instead of spreading in.
    pub fn set_reduce_motion(&self, reduce: bool) {
        self.shared.lock().reduce_motion = reduce;
    }

    /// How many frames a second the screen shows at most (its fastest refresh): frames are drawn
    /// no more often than that (a little more, so none is missed), however fast input comes.
    pub fn set_refresh_rate(&self, hertz: f32) {
        self.shared.lock().frame_spacing = crate::frame::frame_spacing(hertz);
    }

    /// The app shows (`shown`), or has gone away (into the background, its window out of sight):
    /// while away nothing is drawn, even if the window lingers, and the autosave rests (the app
    /// saves as it goes); shown again, a frame is drawn at once.
    pub fn set_shown(&self, shown: bool) {
        let mut st = self.shared.lock();
        if st.hidden == !shown {
            return;
        }
        st.hidden = !shown;
        if shown {
            self.shared.frame(&mut st);
        }
    }

    /// What the session has done by itself since it began: frames drawn, the render thread's
    /// wakeups and retries, and the autosave's looks for changes. Unchanged over a time nothing
    /// changed on screen, or the app was away, means it did nothing meanwhile.
    pub fn activity(&self) -> Activity {
        self.shared.counts.now()
    }

    /// How many window pixels make one interface point (the screen's density).
    pub fn set_display_scale(&self, pixels_per_point: f32) {
        if pixels_per_point.is_finite() && pixels_per_point > 0.0 {
            let mut st = self.shared.lock();
            st.display_scale = pixels_per_point;
            self.shared.frame(&mut st);
        }
    }

    /// How big the interface shows beside the screen's density (1: as measured). The canvas, its
    /// brushes and how far a gesture goes stay as they are: only the handles the interface draws
    /// over the canvas take touches where they show.
    pub fn set_interface_size(&self, size: f32) {
        if size.is_finite() && size > 0.0 {
            let mut st = self.shared.lock();
            if st.interface_size != size {
                st.interface_size = size;
                self.shared.frame(&mut st);
            }
        }
    }

    /// The surround's grid is fixed to the glass as the canvas is: its lines run from the panel's
    /// own corner, so the window gives where the middle of one of them lands (window pixels) as
    /// the display turns.
    pub fn set_grid_origin(&self, x: f32, y: f32) {
        if x.is_finite() && y.is_finite() {
            let mut st = self.shared.lock();
            st.grid_origin = Some([x, y]);
            self.shared.frame(&mut st);
        }
    }

    /// The light interface: the surround and its grid go light.
    pub fn set_light_interface(&self, on: bool) {
        let mut st = self.shared.lock();
        st.light = on;
        self.shared.frame(&mut st);
    }

    /// Whether the window may show the canvas in wide colour (Display P3) where the screen takes
    /// it. Off, every canvas shows in sRGB (a Display P3 one clipped to it): for a screen whose
    /// compositor blurs or dulls a wide-colour window.
    pub fn set_wide_colour(&self, allowed: bool) {
        let mut st = self.shared.lock();
        if st.wide_colour_allowed != allowed {
            st.wide_colour_allowed = allowed;
            self.shared.frame(&mut st);
        }
    }

    /// Whether the canvas shows in wide colour now (see [`State::wide_colour`]): the app's window
    /// follows, so it is wide only while the canvas is.
    pub fn shows_wide_colour(&self) -> bool {
        self.shared.lock().wide_colour()
    }

    /// Fits the whole canvas in the window again, at once.
    pub fn fit_view(&self) {
        let mut st = self.shared.lock();
        st.view_motion = None;
        st.view = Some(st.fitted_view());
        self.shared.frame(&mut st);
    }

    /// Fits the whole canvas in the window again, settling into place on a spring over
    /// [`CanvasSession::view_settle_seconds`] (the view read meanwhile is where it has got to).
    pub fn settle_to_fit(&self) {
        let mut st = self.shared.lock();
        let to = st.fitted_view();
        st.view_motion =
            st.view.map(|from| ViewMotion { from, to, start: std::time::Instant::now(), curve: ViewCurve::Settle });
        if st.view_motion.is_none() {
            st.view = Some(to);
        }
        self.shared.frame(&mut st);
    }

    /// Zooms to the canvas's actual size (a canvas pixel to a window pixel) about the middle of
    /// the window, keeping its turn, settling into place as [`CanvasSession::settle_to_fit`] does.
    pub fn settle_to_actual_size(&self) {
        let mut st = self.shared.lock();
        let Some(from) = st.view else { return };
        let middle = Vec2::new(st.window_size.0 as f32 / 2.0, st.window_size.1 as f32 / 2.0);
        let Some(to) =
            from.screen_to_canvas(middle).and_then(|c| View::centred(c, 1.0, from.rotation(), st.window_size))
        else {
            return;
        };
        st.view_motion = Some(ViewMotion { from, to, start: std::time::Instant::now(), curve: ViewCurve::Settle });
        self.shared.frame(&mut st);
    }

    /// Turns the canvas back upright about the middle of the window, keeping its zoom and what is
    /// in the middle, settling into place as [`CanvasSession::settle_to_fit`] does. False if it
    /// already was upright.
    pub fn settle_upright(&self) -> bool {
        let mut st = self.shared.lock();
        let Some(from) = st.view.filter(|v| v.rotation().abs() > 1e-5) else { return false };
        let middle = Vec2::new(st.window_size.0 as f32 / 2.0, st.window_size.1 as f32 / 2.0);
        let Some(to) = from.screen_to_canvas(middle).and_then(|c| View::centred(c, from.zoom(), 0.0, st.window_size))
        else {
            return false;
        };
        st.view_motion = Some(ViewMotion { from, to, start: std::time::Instant::now(), curve: ViewCurve::Settle });
        self.shared.frame(&mut st);
        true
    }

    /// How far the canvas is turned on the screen, clockwise, in radians (-π to π): where a turn
    /// under way is going.
    pub fn view_turn(&self) -> f32 {
        let st = self.shared.lock();
        let view = st.view_motion.as_ref().map(|m| m.to).or(st.view);
        view.map_or(0.0, |v| v.rotation())
    }

    /// Turns the canvas to `radians` (clockwise) about the middle of the window, keeping its zoom
    /// and what is in the middle, settling into place as [`CanvasSession::settle_to_fit`] does.
    /// False if it already was turned so, or there's no canvas to turn (a model shown).
    pub fn turn_view_to(&self, radians: f32) -> bool {
        let mut st = self.shared.lock();
        if !radians.is_finite() || st.scene_shown() {
            return false;
        }
        // turned about where a settling under way goes, from where it has got to
        let Some(going) = st.view_motion.take().map(|m| m.to).or(st.view) else { return false };
        let middle = Vec2::new(st.window_size.0 as f32 / 2.0, st.window_size.1 as f32 / 2.0);
        let centred =
            going.screen_to_canvas(middle).and_then(|c| View::centred(c, going.zoom(), radians, st.window_size));
        let Some(to) = centred else { return false };
        let from = st.view.unwrap_or(going);
        if from == to {
            return false;
        }
        st.view_motion = Some(ViewMotion { from, to, start: std::time::Instant::now(), curve: ViewCurve::Settle });
        self.shared.frame(&mut st);
        true
    }

    /// Whether a plugin on the gesture lock's hook keeps two-finger gestures, and a trackpad's
    /// turning, from turning the canvas now (the canvas then doesn't snap onto a right angle as
    /// they let go either).
    pub fn gesture_turn_locked(&self) -> bool {
        let hooks = self.shared.lock().hooks.clone();
        hooks.is_some_and(|hooks| hooks.gesture_lock(crate::hooks::GESTURE_TURN))
    }

    /// The canvas let go turned within 5° of a right angle turns the rest of the way onto it about
    /// the middle of the window, slowing to a stop: the less it moves the quicker (see
    /// [`View::move_seconds`]), and at once with less motion asked for. True if it turns.
    pub fn settle_turn(&self) -> bool {
        let mut st = self.shared.lock();
        if st.scene_shown() || st.view_motion.is_some() {
            return false;
        }
        let Some(from) = st.view else { return false };
        let middle = Vec2::new(st.window_size.0 as f32 / 2.0, st.window_size.1 as f32 / 2.0);
        let (Some(to), Some(about)) = (from.snapped_turn(middle), from.screen_to_canvas(middle)) else {
            return false;
        };
        let Some(seconds) = from.move_seconds(&to) else { return false };
        let seconds = if st.reduce_motion { REDUCED_TURN_SECONDS } else { seconds };
        st.view_motion =
            Some(ViewMotion { from, to, start: std::time::Instant::now(), curve: ViewCurve::Turn { seconds, about } });
        self.shared.frame(&mut st);
        true
    }

    /// Fits the whole canvas in the window again, growing back into place over 0.3 s, slowing to
    /// a stop (as leaving crop without cropping does).
    pub fn ease_to_fit(&self) {
        let mut st = self.shared.lock();
        let to = st.fitted_view();
        st.view_motion = st.view.map(|from| ViewMotion {
            from,
            to,
            start: std::time::Instant::now(),
            curve: ViewCurve::EaseOut(VIEW_EASE_SECONDS),
        });
        if st.view_motion.is_none() {
            st.view = Some(to);
        }
        self.shared.frame(&mut st);
    }

    /// How long the view takes to settle into place.
    pub fn view_settle_seconds(&self) -> f32 {
        VIEW_SETTLE_SECONDS
    }

    /// Whether the view is still moving into place (what is drawn over the canvas follows it).
    pub fn view_moving(&self) -> bool {
        self.shared.lock().view_motion.is_some()
    }

    /// Whether the canvas is made (once the artwork is shown): until then nothing can read its
    /// pictures (an animation's frames).
    pub fn canvas_made(&self) -> bool {
        self.shared.lock().canvas.is_some()
    }

    /// The frosted panels open over the canvas, five numbers each (left, top, right, bottom and
    /// corner radius, window pixels): what is behind them is drawn blurred.
    pub fn set_backdrops(&self, regions: Vec<f32>) {
        self.set_glass(regions, Vec::new());
    }

    /// The same, with `windows` drawn over them, where the glass is thin: what is behind only
    /// softens there.
    pub fn set_glass(&self, regions: Vec<f32>, windows: Vec<f32>) {
        let ((regions, _), (windows, _)) = (regions.as_chunks::<6>(), windows.as_chunks::<6>());
        let mut st = self.shared.lock();
        if st.backdrops != regions || st.backdrop_windows != windows {
            st.backdrops = regions.to_vec();
            st.backdrop_windows = windows.to_vec();
            self.shared.frame(&mut st);
        }
    }

    /// What fitting the canvas keeps clear of from now on (left, top, right, bottom, window
    /// pixels): the interface, with Fit Canvas Inside Interface on.
    pub fn set_fit_insets(&self, left: f32, top: f32, right: f32, bottom: f32) {
        self.shared.lock().fit_insets = [left, top, right, bottom];
    }

    /// Lets go of the canvas and everything it holds, on the GPU and off it (its layers, parked
    /// or not, and a model's), once the artwork is saved and left: a large artwork's memory is
    /// free at once rather than whenever this object is collected. Nothing is drawn after.
    pub fn release_canvas(&self) {
        let mut st = self.shared.lock();
        st.closed = true;
        st.canvas = None;
        st.pending_pixels.clear();
        st.scene = None;
    }

    /// Canvas Orientation Memory: on, the quarter turn the artwork is kept at (which it opens
    /// at, and the gallery shows it at) follows its canvas's; off, it stays as it is. On at first.
    pub fn set_orientation_memory(&self, on: bool) {
        self.shared.lock().turns_follow_view = on;
    }

    /// How many quarter turns clockwise the gallery shows the artwork turned by, as it would be
    /// kept now (see [`CanvasSession::save`]): as its canvas is turned, to the nearest.
    pub fn shown_turns(&self) -> u32 {
        u32::from(self.shared.lock().shown_turns())
    }

    /// The window turned with the device: the view turns by `rotation` (radians) about the
    /// window's top-left corner and moves by `pan`, so the canvas stays where it was on the glass.
    /// As [`CanvasSession::view_gesture`], but not counted as a turn of the canvas.
    pub fn follow_screen_turn(&self, pan_x: f32, pan_y: f32, rotation: f32) {
        {
            let mut st = self.shared.lock();
            if rotation.is_finite() && st.view.is_some() && !st.scene_shown() {
                st.screen_follow = (st.screen_follow + rotation).rem_euclid(std::f32::consts::TAU);
            }
        }
        self.view_gesture(0.0, 0.0, pan_x, pan_y, 1.0, rotation);
    }
}
