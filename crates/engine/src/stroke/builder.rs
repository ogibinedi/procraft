use super::*;

impl StrokeBuilder {
    /// The stroke with the app's own smoothing and pressure as well as the brush's.
    pub fn with_input(mut self, input: InputSettings) -> StrokeBuilder {
        // the stronger of the app's and the brush's
        self.extras.stabilization = self.extras.stabilization.max(input.stabilization.clamp(0.0, 1.0));
        self.input = input;
        self
    }

    /// The stroke drawn on a canvas turned `rotation` radians on screen: a brush oriented to the
    /// screen turns its shapes back by as much, so they keep their angle on screen.
    pub fn on_view_turned(mut self, rotation: f32) -> StrokeBuilder {
        if self.brush.setting("orient_to_screen") >= 0.5 && rotation.is_finite() {
            self.screen_turn = rotation;
        }
        self
    }

    /// The stroke drawn with the canvas shown at `points_per_pixel` screen points a canvas pixel:
    /// the speed dynamics go by how fast the pen moves on screen.
    pub fn seen_at(mut self, points_per_pixel: f32) -> StrokeBuilder {
        if points_per_pixel.is_finite() && points_per_pixel > 0.0 {
            self.points_per_pixel = points_per_pixel;
        }
        self
    }

    /// The input comes in whole screen pixels (a mouse, or a tablet's pen read no finer than the
    /// pointer it moves), the canvas shown at `zoom` screen pixels a canvas pixel: a slow line
    /// read so goes in steps of a pixel, which its points are evened out over (see
    /// [`StrokeBuilder::destepped`]) before the path curves through them.
    pub fn read_in_whole_pixels(mut self, zoom: f32) -> StrokeBuilder {
        if zoom.is_finite() && zoom > 0.0 {
            self.whole_pixels = Some(zoom);
        }
        self
    }

    /// Whether the input is taken as read in whole screen pixels (see
    /// [`StrokeBuilder::read_in_whole_pixels`]).
    pub fn reads_whole_pixels(&self) -> bool {
        self.whole_pixels.is_some() && !self.finer_next
    }

    /// The input taken as read in whole pixels turned out to come finer (a position between them;
    /// one read finer lands on whole pixels now and then): as the next samples are added, the
    /// points still waiting to be evened out go on as they are, and the path is read finer from
    /// there.
    pub fn read_finer(&mut self) {
        self.finer_next = self.whole_pixels.is_some();
    }

    /// Reads the input finer from here, if it was found to be (see [`StrokeBuilder::read_finer`]).
    fn take_finer(&mut self, out: &mut Vec<Stamp>) {
        if std::mem::take(&mut self.finer_next) {
            self.settle_steps(true, out);
            self.whole_pixels = None;
            self.steps.clear();
            self.steps_settled = 0;
            self.steps_at = None;
        }
    }

    /// The stroke drawn for show (the library's sample): it never counts as fast, each use of the
    /// pressure takes it as it comes, and no streamline, stabilisation or motion filtering holds
    /// its path back (it still curves through its points).
    pub fn steady(mut self) -> StrokeBuilder {
        self.steady = true;
        self
    }

    /// The samples are the path to draw, exactly (the drawing pad's sample scribble): no
    /// streamline, stabilisation or motion filtering moves it, and each use of the pressure takes
    /// it as it comes.
    /// The brush scaled `scale` times on this canvas (as its diameter already is), for what
    /// leaning adds to it.
    pub fn scaled(mut self, scale: f32) -> StrokeBuilder {
        if scale.is_finite() && scale > 0.0 {
            self.scale = scale;
        }
        self
    }

    /// The stroke is drawn on the brush studio's pad: the pen's lean never moves its stamps
    /// toward the cap there, and the tilt switches act only when the brush previews tilt.
    pub fn on_pad(mut self, preview_tilt: bool) -> StrokeBuilder {
        self.pad = Some(preview_tilt);
        self
    }

    /// The samples say nothing of how the pen is turned (the library's sample stroke): a brush
    /// whose stamps turn as the pen is held turns them with the stroke instead, as under a finger.
    pub fn without_azimuth(mut self) -> StrokeBuilder {
        self.no_azimuth = true;
        self
    }

    /// The samples' pressure counts as a pen's does, though the pointer reports none (a finger
    /// given a pressure, by a plugin): the brush's pressure settings act on it, and nothing else
    /// changes. Its tapers stay the pointer's (a finger's touch taper), and it neither leans nor
    /// turns.
    pub fn with_given_pressure(mut self) -> StrokeBuilder {
        self.given_pressure = true;
        self
    }

    /// Input whose pressure counts has it grow from nothing over the first `start` canvas pixels
    /// of the path and fall to nothing over the last `end`, as a pen's does pressed down and lifted
    /// off, on top of the brush's own tapers: the brush's pressure settings say what that does to
    /// its stamps. A tap keeps its whole pressure.
    pub fn with_pressure_tapers(mut self, start: f32, end: f32) -> StrokeBuilder {
        let span = |v: f32| if v.is_finite() { v.max(0.0) } else { 0.0 };
        self.pressure_tapers = [span(start), span(end)];
        self
    }

    /// Whether the samples' pressure was given (see [`StrokeBuilder::with_given_pressure`]), and
    /// the pressure's tapers (see [`StrokeBuilder::with_pressure_tapers`]): for a stroke laid
    /// again as this one was.
    pub fn pressing(&self) -> (bool, [f32; 2]) {
        (self.given_pressure, self.pressure_tapers)
    }

    pub fn exact_path(mut self) -> StrokeBuilder {
        self.exact = true;
        self
    }

    /// The stroke's stamps laid by `engine` from its input, in place of the brush's settings: it
    /// is handed the samples as they come, and nothing predicts or previews ahead of them.
    pub fn with_engine(mut self, engine: Box<dyn OutsideEngine>) -> StrokeBuilder {
        let outside = Outside { engine, stamps: Vec::new(), length: 0.0, last: None, ended: false };
        self.outside = OutsideSlot(Some(outside));
        self
    }

    /// Whether an outside engine lays its stamps (see [`StrokeBuilder::with_engine`]).
    pub fn is_outside(&self) -> bool {
        self.outside.0.is_some()
    }

    /// `samples` handed to the outside engine as it takes them, and the stamps it answers; none
    /// without one.
    fn add_outside(&mut self, samples: &[Sample]) -> Option<Vec<Stamp>> {
        let pen = self.pointer.has_pressure();
        let given = self.given_pressure;
        let curve = &self.input.pressure_curve;
        let outside = self.outside.0.as_mut()?;
        if outside.ended {
            return Some(Vec::new());
        }
        let taken: Vec<Sample> = samples
            .iter()
            .filter(|s| s.is_finite())
            .map(|s| {
                let mut s = *s;
                if pen {
                    s.pressure = curve.eval(s.pressure.clamp(0.0, 1.0)).clamp(0.0, 1.0);
                    for v in [&mut s.tilt, &mut s.azimuth, &mut s.roll] {
                        if !v.is_finite() {
                            *v = 0.0;
                        }
                    }
                } else {
                    // a given pressure as it was given (no pen's curve), and no lean or turn
                    let pressure = if given { s.pressure.clamp(0.0, 1.0) } else { 1.0 };
                    (s.pressure, s.tilt, s.azimuth, s.roll) = (pressure, 0.0, 0.0, 0.0);
                }
                s
            })
            .collect();
        for s in &taken {
            if let Some(last) = outside.last {
                outside.length += s.pos.distance(last);
            }
            outside.last = Some(s.pos);
        }
        if taken.is_empty() {
            return Some(Vec::new());
        }
        let stamps = outside.engine.add(&taken);
        outside.stamps.extend_from_slice(&stamps);
        self.length = outside.length;
        self.bounds = stamps.iter().fold(self.bounds, |b, s| b.union(s.bounds()));
        Some(stamps)
    }

    /// A stroke with `brush` at `diameter` canvas pixels (the size slider's value).
    pub fn new(brush: Brush, diameter: f32, pointer: Pointer) -> StrokeBuilder {
        StrokeBuilder::with_seed(brush, diameter, pointer, 0x9e37_79b9)
    }

    /// As [`StrokeBuilder::new`], with the seed for scatter, jitter and random rotation.
    pub fn with_seed(brush: Brush, diameter: f32, pointer: Pointer, seed: u32) -> StrokeBuilder {
        let diameter = if diameter.is_finite() { diameter.max(0.5) } else { 1.0 };
        let roll_turns = brush.setting("input_style") >= 1.5;
        let roll_relative = brush.setting("relative_to_stroke") >= 0.5;
        StrokeBuilder {
            roll_turns,
            roll_relative,
            roll_from: None,
            brush,
            diameter,
            pointer,
            pressure: None,
            pressed_from: None,
            smoothed: None,
            streaming: VecDeque::new(),
            batch: 0,
            last_sample: None,
            now_ms: 0.0,
            head: None,
            direction: 0.0,
            dab_direction: 0.0,
            heading: Vec2::ZERO,
            last_seg: 0.0,
            behind: None,
            ahead: None,
            before_ahead: None,
            whole_pixels: None,
            finer_next: false,
            steps: VecDeque::new(),
            steps_settled: 0,
            steps_at: None,
            scale: 1.0,
            carry: 0.0,
            length: 0.0,
            placed: Vec::new(),
            bounds: IRect::EMPTY,
            random: Random(seed | 1),
            extras: Extras::default(),
            averaged: Averaged::default(),
            stable: Averaged::default(),
            speed: 0.0,
            speed_known: false,
            soft: None,
            segments: 0,
            steady: false,
            exact: false,
            pad: None,
            no_azimuth: false,
            pending_gap: None,
            stroke_shift: Tint::NONE,
            stroke_secondary: 0.0,
            stroke_turn: 0.0,
            screen_turn: 0.0,
            points_per_pixel: 1.0,
            input: InputSettings::default(),
            weights: None,
            grain_start: Vec2::ZERO,
            grain_last: None,
            history: Vec::new(),
            input_length: 0.0,
            before_last: [None; 2],
            ran_on: None,
            kept: 0,
            left_out: 0,
            start_settled: None,
            knots: Vec::new(),
            outside: OutsideSlot(None),
            walked: Vec::new(),
            walked_kept: 0,
            dab_turn: 0.0,
            spacing_end: None,
            laid_held: false,
            turned: false,
            quiet: false,
            tipped: false,
            changed: usize::MAX,
            ended: None,
            given_pressure: false,
            pressure_tapers: [0.0; 2],
        }
        .reading_extras()
    }

    fn reading_extras(mut self) -> StrokeBuilder {
        self.extras = Extras::of(&self.brush);
        let jitter = self.extras.stroke_color;
        self.stroke_shift = self.color_jitter(jitter);
        if self.extras.stroke_secondary > 0.0 {
            self.stroke_secondary = self.extras.stroke_secondary * self.random.next();
        }
        if self.extras.grain_offset_jitter {
            self.grain_start = Vec2::new(self.random.next(), self.random.next());
        }
        if self.brush.shape.randomise {
            self.stroke_turn = self.random.next() * std::f32::consts::TAU;
        }
        self.dab_direction = self.dab_way();
        self.direction = self.dab_direction;
        self
    }

    /// The way a stamp that turns with the stroke goes before the stroke goes anywhere: up the
    /// screen for one that follows it, less the less it does, and on by the stroke's random turn
    /// (so a stamp that follows the stroke has none).
    pub(super) fn dab_way(&self) -> f32 {
        -wrapped(std::f32::consts::FRAC_PI_4 * (1.0 + self.brush.shape.follow) + self.stroke_turn)
    }

    /// A random colour change by `jitter` (hue, saturation, lightness, darkness): the hue turned
    /// either way, the saturation drawn toward a random one, the colour lightened by a share of
    /// what is left to white and darkened by a share of what is left to black.
    fn color_jitter(&mut self, [hue, saturation, lightness, darkness]: [f32; 4]) -> Tint {
        if hue == 0.0 && saturation == 0.0 && lightness == 0.0 && darkness == 0.0 {
            return Tint::NONE;
        }
        let turn = (self.random.next() - 0.5) * hue;
        let toward = self.random.next() * saturation;
        let (lighter, darker) = (self.random.next() * lightness, self.random.next() * darkness);
        Tint { hue: turn, saturation: [1.0 - saturation, toward], brightness: [1.0 - lighter - darker, lighter] }
    }

    pub fn pointer(&self) -> Pointer {
        self.pointer
    }

    /// The stamp diameter at full pressure (the size slider's value), canvas pixels.
    pub fn diameter(&self) -> f32 {
        self.diameter
    }

    pub fn brush(&self) -> &Brush {
        &self.brush
    }

    /// Pixels the stroke covers so far.
    pub fn bounds(&self) -> IRect {
        self.bounds
    }

    /// Path length so far, canvas pixels.
    pub fn length(&self) -> f32 {
        self.length
    }

    pub fn stamp_count(&self) -> usize {
        if let Some(outside) = &self.outside.0 {
            return outside.stamps.len();
        }
        self.placed.len()
    }

    /// Adds samples (canvas pixels) and returns the stamps they add. Non-finite samples are skipped.
    pub fn add(&mut self, samples: &[Sample]) -> Vec<Stamp> {
        if let Some(stamps) = self.add_outside(samples) {
            return stamps;
        }
        let mut out = Vec::new();
        self.take_finer(&mut out);
        for s in samples.iter().filter(|s| s.is_finite()) {
            self.push(*s, &mut out);
        }
        out
    }

    /// The stamps `samples` would add, without adding them (for drawing predicted input): the
    /// path walked on to the last of them.
    pub fn preview(&mut self, samples: &[Sample]) -> Vec<Stamp> {
        if self.is_outside() {
            return Vec::new();
        }
        // none of the stamps so far are needed
        let mut ahead = self.open_copy(self.placed.len());
        let mut out = ahead.add(samples);
        ahead.average_on(&mut out);
        ahead.stream_on(&mut out);
        ahead.stabilise_on(&mut out);
        ahead.flush(&mut out);
        out
    }

    /// As [`StrokeBuilder::preview`], and on from the path to the input itself: all the stroke
    /// shows ahead of what is drawn for good.
    pub fn preview_to_input(&mut self, samples: &[Sample]) -> Vec<Stamp> {
        if self.is_outside() {
            return Vec::new();
        }
        let mut ahead = self.open_copy(self.placed.len());
        let mut out = ahead.add(samples);
        ahead.reach_input(&mut out);
        out
    }

    /// Walks the stretch to the newest path point, as if nothing came after it.
    pub(super) fn flush(&mut self, out: &mut Vec<Stamp>) {
        self.settle_steps(true, out);
        if let (Some(c), Some(b)) = (self.ahead.take(), self.head) {
            self.walk_curve(self.behind, b, c, None, out);
            self.behind = Some(b);
        }
    }

    /// The stroke as it shows while drawn: stabilisation's average on to the input, streamline up
    /// to it, the stretch to the newest path point, then on to the input if that is still ahead.
    pub(super) fn reach_input(&mut self, out: &mut Vec<Stamp>) {
        self.average_on(out);
        self.stream_on(out);
        self.stabilise_on(out);
        self.flush(out);
        if let Some(to) = self.input_point().filter(|_| !self.averaged.mirrored) {
            self.walk_to(to, out);
        }
    }

    /// The newest input as a point of the path, smoothed by nothing.
    pub(super) fn input_point(&self) -> Option<PathPoint> {
        let (last, head) = (self.last_sample?, self.head?);
        let waiting = self.steps.back().filter(|_| self.steps_settled < self.steps.len());
        let newest = waiting.copied().or(self.ahead).unwrap_or(head);
        Some(PathPoint {
            pos: last.pos,
            pressure: self.pressure.unwrap_or(1.0),
            soft: self.soft.unwrap_or(newest.soft),
            tilt: newest.tilt,
            azimuth: newest.azimuth,
            roll: newest.roll,
            speed: newest.speed,
        })
    }

    /// Whether its end may change only its last stamps handed out, so that those are laid again as
    /// it ends: an end taper, or pressure that falls over one. Not with a newer start taper, whose
    /// reach, known only once the stroke is under way, changes its first stamps as it ends (laid
    /// again from its start), nor a brush engine's stroke, whose stamps stay as they came.
    pub fn end_may_change_laid(&self) -> bool {
        let taper = self.taper();
        !self.is_outside()
            && (taper.start <= 0.0 || self.classic())
            && (taper.end > 0.0 || self.pressure_taper(1) > 0.0)
    }

    /// Whether the stroke shows its tapers as it is drawn, through a tip redrawn as it goes: one
    /// that lasts a time at its start (until that time has passed), and at its end with the tip
    /// animation on.
    pub fn shows_tip(&self) -> bool {
        !self.is_outside() && !self.classic() && (self.taper().start > 0.0 || self.end_shows())
    }

    /// Adds samples to a stroke shown with its tip (see [`StrokeBuilder::shows_tip`]): returns
    /// the stamps to draw for good now, those past the start taper's time that the end taper no
    /// longer reaches, and the tip, the stamps after them as they would be drawn were the stroke
    /// to end here.
    pub fn add_tipped(&mut self, samples: &[Sample]) -> (Vec<Stamp>, Vec<Stamp>) {
        let kept = self.add_kept(samples);
        (kept, self.tip_now())
    }

    /// The first half of [`StrokeBuilder::add_tipped`]: the stamps to draw for good now, the tip
    /// left to [`StrokeBuilder::tip_now`] (or [`StrokeBuilder::preview_tip`]) when it is wanted.
    pub fn add_kept(&mut self, samples: &[Sample]) -> Vec<Stamp> {
        self.tipped = true;
        // (what `add` would hand out isn't wanted: those drawn for good are made below)
        let quiet = std::mem::replace(&mut self.quiet, true);
        self.add(samples);
        self.quiet = quiet;
        let start = self.start_span();
        let reach = match self.start_settled {
            // the start's reach still moving: nothing is drawn for good yet
            None => f32::NEG_INFINITY,
            Some(_) if self.end_shows() => self.length - self.end_reach(0.0),
            Some(_) => f32::INFINITY,
        };
        // and only as far as the tapers, eased over the points around, no longer change
        let reach = self.eased_before(reach);
        // the stamps lie in order along the path: the first the taper reaches is after those kept
        let open = self.placed.get(self.kept..).unwrap_or_default();
        let to = open.iter().position(|p| p.distance >= reach).map_or(self.placed.len(), |i| self.kept + i);
        let kept = self.placed.get(self.kept..to).unwrap_or_default();
        let kept = kept.iter().map(|p| self.finish_stamp(p, start, None)).collect();
        self.kept = to;
        kept
    }

    /// The tip of a stroke shown with its tip, as if it ended here: the stamps after those drawn
    /// for good, walked on to the input.
    pub fn tip_now(&mut self) -> Vec<Stamp> {
        let mut ended = self.open_copy(self.kept);
        ended.quiet = true;
        ended.reach_input(&mut Vec::new());
        ended.respace_tip();
        ended.tip_from(0)
    }

    /// The tip as it would be with `samples` added (for drawing predicted input), the stroke
    /// left as it is.
    pub fn preview_tip(&mut self, samples: &[Sample]) -> Vec<Stamp> {
        if self.is_outside() {
            return Vec::new();
        }
        let mut ahead = self.open_copy(self.kept);
        ahead.quiet = true;
        ahead.add(samples);
        ahead.reach_input(&mut Vec::new());
        ahead.respace_tip();
        ahead.tip_from(0)
    }

    /// A copy of the stroke holding only its stamps from `from` on, to see how it would go on:
    /// copying every stamp each time cost more the longer the stroke. Those before are set aside
    /// while it is made, not copied.
    pub(super) fn open_copy(&mut self, from: usize) -> StrokeBuilder {
        let from = from.min(self.placed.len());
        let open = self.placed.split_off(from);
        let before = std::mem::replace(&mut self.placed, open);
        // (and of the pieces of path walked, those it may walk again)
        let least = self.left_out + from;
        let cut = self.walked.partition_point(|w| w.before.stamps < least);
        let open_walked = self.walked.split_off(cut);
        let walked_before = std::mem::replace(&mut self.walked, open_walked);
        let mut copy = self.clone();
        copy.left_out += from;
        copy.kept = 0;
        let open = std::mem::replace(&mut self.placed, before);
        self.placed.extend(open);
        let open_walked = std::mem::replace(&mut self.walked, walked_before);
        self.walked.extend(open_walked);
        copy
    }

    /// Walks again what the tip's tapers reach, as they would be were the stroke to end here.
    pub(super) fn respace_tip(&mut self) {
        let end = self.end_shows().then(|| (self.length, self.end_span(0.0)));
        self.respace(end);
    }

    /// The stamps laid as the stroke is drawn stay as they are when it ends (a smudge's, which
    /// has moved the paint already): its end isn't walked again, only tapered.
    pub fn hold_laid(&mut self) {
        self.laid_held = true;
    }

    /// Whether the stamps placed before the path had a direction (a stroke's first) turned with it
    /// since this was last asked, after they were handed out: laid as they were, the stroke is
    /// laid again with them as they are now ([`StrokeBuilder::placed_stamps`]), while it is a few
    /// stamps long, or the stroke's end would find them changed and lay all of it again.
    pub fn take_turned(&mut self) -> bool {
        let turned = std::mem::take(&mut self.turned);
        // (laid again as they are now, they are handed out anew)
        if turned && !self.tipped {
            self.changed = usize::MAX;
        }
        turned
    }

    /// The stamps placed so far, as they are handed out while the stroke is drawn.
    pub fn placed_stamps(&self) -> Vec<Stamp> {
        if let Some(outside) = &self.outside.0 {
            return outside.stamps.clone();
        }
        let start = self.start_span();
        self.placed.iter().map(|p| self.finish_stamp(p, start, None)).collect()
    }

    /// The stamps from `from` on as they would be drawn were the stroke to end here (its end
    /// tapered only where that shows as it is drawn).
    pub(super) fn tip_from(&self, from: usize) -> Vec<Stamp> {
        let start = self.start_span();
        let end = self.end_shows().then(|| (self.length, self.end_span(0.0)));
        let tip = self.placed.get(from..).unwrap_or_default();
        tip.iter().map(|p| self.finish_stamp(p, start, end)).collect()
    }

    /// Every stamp so far as currently drawn (no end taper yet).
    pub fn stamps(&self) -> Vec<Stamp> {
        if let Some(outside) = &self.outside.0 {
            return outside.stamps.clone();
        }
        let start = self.start_span();
        self.placed.iter().map(|p| self.finish_stamp(p, start, None)).collect()
    }

    /// The stamps drawn for good so far, of a stroke drawn stamp by stamp (with a tip, where it
    /// shows one) and not yet ended: if ending it leaves them as they are, only the rest are to draw.
    pub fn drawn_for_good(&self) -> Option<Vec<Stamp>> {
        if let Some(outside) = &self.outside.0 {
            return (!outside.ended).then(|| outside.stamps.clone());
        }
        if self.ran_on.is_some() {
            return None;
        }
        let start = self.start_span();
        let drawn = if self.shows_tip() { self.kept } else { self.placed.len() };
        Some(self.placed.iter().take(drawn).map(|p| self.finish_stamp(p, start, None)).collect())
    }

    /// The line a shape is fitted to, from this builder as its stroke began and the stroke's
    /// input: stabilisation and motion filtering smooth it as they smooth the stroke, streamline
    /// doesn't, no taper changes how its stamps are spaced and nothing runs on past the lift. Each
    /// stamp's place on the path, before any jitter threw it.
    pub fn shape_line(&self, samples: &[Sample]) -> Vec<Vec2> {
        let mut line = self.clone();
        line.outside = OutsideSlot(None);
        line.brush.streamline = 0.0;
        line.brush.pen_taper = Taper::NONE;
        line.brush.touch_taper = Taper::NONE;
        line.quiet = true;
        line.add(samples);
        line.finish();
        line.placed.iter().map(|p| p.center - p.thrown).collect()
    }

    /// Ends the stroke: walks on to where the pen lifted and applies the end taper. Returns all of
    /// the stroke's stamps, as they should finally be drawn.
    pub fn finish(&mut self) -> Vec<Stamp> {
        let stamps = self.finish_from(0);
        self.bounds = stamps.iter().fold(IRect::EMPTY, |b, s| b.union(s.bounds()));
        stamps
    }

    /// Ends the stroke as [`StrokeBuilder::finish`] does, returning its final stamps from the
    /// `from`th on (none past its last).
    pub fn finish_from(&mut self, from: usize) -> Vec<Stamp> {
        if let Some(outside) = self.outside.0.as_mut() {
            if !outside.ended {
                outside.ended = true;
                let last = outside.engine.finish();
                outside.stamps.extend(last);
            }
            return outside.stamps.get(from..).unwrap_or_default().to_vec();
        }
        self.walk_to_end();
        let Some(end) = self.ended else { return Vec::new() };
        let start = self.start_span();
        let rest = self.placed.get(from..).unwrap_or_default();
        rest.iter().map(|p| self.finish_stamp(p, start, Some(end))).collect()
    }

    /// Ends the stroke as [`StrokeBuilder::finish`] does, returning only its final stamps after the
    /// first `laid`, if those are sure to be as they were handed out for good (by
    /// [`StrokeBuilder::add`], or [`StrokeBuilder::add_kept`] for a stroke drawn with its tip): a
    /// long stroke's end then makes only the rest. None if they may not be (the stroke is then laid
    /// whole: [`StrokeBuilder::finish_from`] gives it, ended).
    pub fn finish_after(&mut self, laid: usize) -> Option<Vec<Stamp>> {
        if self.outside.0.is_some() {
            return Some(self.finish_from(laid));
        }
        self.walk_to_end();
        let (length, end) = self.ended?;
        // a tap's stamp is drawn whole; and a start taper changes stamps handed out while its reach
        // grew, or (handed out as they were placed) before the points either side of them came
        let start_kept = match self.tipped {
            true => self.start_settled.is_some() || self.taper().start <= 0.0,
            false => self.taper().start <= 0.0,
        };
        let unchanged = laid <= self.placed.len() && self.changed >= laid && length > 0.0 && start_kept;
        // nor does the end taper, or the pressure's fall (eased over the points around), reach the
        // last of them
        let end = end.max(self.pressure_taper(1));
        let reach = self.eased_before(length - end);
        let last = laid.checked_sub(1).and_then(|i| self.placed.get(i));
        let untapered = end <= 0.0 || last.is_none_or(|p| p.distance < reach);
        (unchanged && untapered).then(|| self.finish_from(laid))
    }

    /// Walks on to where the pen lifted, as the stroke ends (once): the path catches up with the
    /// pen, a classic taper runs on, and what the tapers reach is walked again now they are known.
    fn walk_to_end(&mut self) {
        if self.ended.is_some() {
            return;
        }
        // nothing walked here is handed out: the stamps are made once it has all been walked
        let quiet = std::mem::replace(&mut self.quiet, true);
        let mut run_on = 0.0;
        let mut ignored = Vec::new();
        self.average_on(&mut ignored);
        self.stream_on(&mut ignored);
        self.stabilise_on(&mut ignored);
        if let Some(to) = self.input_point().filter(|_| !self.averaged.mirrored) {
            // the path comes up to the pen along the curve, where it lifted the last point it
            // curves through
            self.path_to(to, &mut ignored);
            self.flush(&mut ignored);
            run_on = match self.ran_on {
                Some(r) => r,
                None => self.run_on(to, &mut ignored),
            };
        } else {
            self.flush(&mut ignored);
        }
        self.ran_on = Some(run_on);
        let length = self.length;
        // a tap, which never went anywhere, lays one stamp whatever the brush's count, as a dot
        if length <= 0.0 && run_on <= 0.0 {
            self.placed.truncate(1);
            self.touched(0);
        }
        let end = self.end_span(run_on);
        // what the tapers reach walked again now they are known, so its stamps lie as close as
        // they are small
        if length > 0.0 && !self.laid_held {
            self.respace(Some((length, end)));
        }
        self.quiet = quiet;
        self.ended = Some((length, end));
    }

    /// The stamps from the `from`th on have changed since they were placed: of those handed out
    /// for good (see [`StrokeBuilder::finish_after`]), the first such is noted.
    pub(super) fn touched(&mut self, from: usize) {
        let handed = if self.tipped { self.kept } else { self.placed.len() };
        if from < handed {
            self.changed = self.changed.min(from);
        }
    }

    pub(super) fn push(&mut self, s: Sample, out: &mut Vec<Stamp>) {
        self.now_ms = s.time_ms;
        let pen = self.pointer.has_pressure();
        // the samples at the tuned rate this one stands for (one if it came with the last's time,
        // or in a batch with it)
        let interval = 1000.0 / if pen { PEN_RATE } else { FINGER_RATE };
        let since = self.last_sample.map_or(0.0, |last| (s.time_ms - last.time_ms).abs());
        let batched = self.last_sample.is_some() && since < BATCHED_MS;
        let steps = if batched || since <= 0.0 { 1.0 } else { (since / interval).min(MOST_STEPS) as f32 };
        // a pen's pressure through the app's curve; one given as it was given (the curve is the
        // pen's); a finger's full
        let raw = match (pen, self.given_pressure) {
            (true, _) => self.input.pressure_curve.eval(s.pressure.clamp(0.0, 1.0)).clamp(0.0, 1.0),
            (false, true) => s.pressure.clamp(0.0, 1.0),
            (false, false) => 1.0,
        };
        // the pressure is smoothed with the path by streamline; a stroke drawn for show or an
        // exact path, which no streamline holds back, smooths it as it comes
        let pressure = match self.pressure.filter(|_| self.steady || self.exact) {
            Some(p) => p + (raw - p) * (1.0 - pressure_keep(self.pressure_smoothing()).powf(steps)),
            None => raw,
        };
        if self.steady || self.exact {
            self.pressure = Some(pressure);
        }
        let (tilt, azimuth) = if pen { (s.tilt, s.azimuth) } else { (0.0, 0.0) };
        let roll = if pen && s.roll.is_finite() { s.roll } else { 0.0 };
        // a brush turned by the barrel notes where it was as the stroke began
        if self.roll_turns && self.roll_from.is_none() {
            self.roll_from = Some(roll);
        }
        if self.weights.is_none() {
            let weights = self.made_weights();
            self.averaged.mirrored = !self.input.tip_attached && !weights.is_empty();
            self.weights = Some(weights.into());
        }
        // (each use's pressure, and the speed, follow once all smoothing has settled it, see
        // `pressed_on`)
        let target = PathPoint { pos: s.pos, pressure, soft: [1.0; 5], tilt, azimuth, roll, speed: 0.0 };
        // (none for the first sample; nothing of its own for one in a batch with the last)
        let dt = self.last_sample.map(|last| if batched { 0.0 } else { (s.time_ms - last.time_ms).clamp(0.5, 50.0) });
        self.keep_time(s);
        if let Some(last) = self.last_sample {
            self.before_last = [self.before_last[1], Some(last.pos)];
        }
        self.last_sample = Some(s);
        // the samples at the tuned rate it stands for, as motion filtering and stabilisation count
        let times = self.history.iter().rev().map(|h| h.0);
        let gap = times.clone().next().zip(times.clone().nth(1)).map_or(0.0, |(now, before)| now - before);
        let samples = if batched { 1.0 } else { (gap / interval).min(MOST_STEPS) };
        let half = self.averaged();
        if half > 0 {
            // motion filtering first: each point waits until those after it within reach of its
            // filter are known
            self.averaged.add(target, samples);
            self.average_to(self.averaged.count().saturating_sub(half), half, out);
            self.averaged.forget(half);
        } else {
            self.go_on(target, dt, batched, samples, out);
        }
        let open = self.start_open();
        self.settle_start();
        // the start taper's reach known: the stroke so far walked again, spaced as it tapers
        if open && !self.start_open() && self.respaces() {
            self.walk_again(f32::NEG_INFINITY);
        }
        self.forget_walked();
    }

    /// Takes the path on to `target`, `dt` milliseconds after the last (none: it is the first;
    /// `batched`, it came in a batch with the last, see [`BATCHED_MS`]; `samples` at the tuned
    /// rate), streamlined: it goes into the window, and those it pushes out are settled and go on
    /// to stabilisation.
    fn go_on(&mut self, target: PathPoint, dt: Option<f64>, batched: bool, samples: f64, out: &mut Vec<Stamp>) {
        let streams = self.brush.streamline > 0.0 || self.pressure_streams();
        let Some(dt) = dt.filter(|_| !self.exact && !self.steady && streams) else {
            self.smoothed = Some(target);
            self.stabilise_to(target, dt, samples, out);
            return;
        };
        self.streaming.push_back((target, dt));
        self.share_batch(batched);
        let mut age: f64 = self.streaming.iter().skip(1).map(|p| p.1).sum();
        while age >= self.stream_reach() {
            let Some((next, dt)) = self.streaming.pop_front() else { break };
            age -= self.streaming.front().map_or(0.0, |p| p.1);
            self.stream_to(next, dt, [0.0; 2], out);
        }
    }

    /// The newest point of streamline's window came in a batch with those before it, or began one:
    /// the batch's points still in the window share the time since the point before it evenly
    /// (each a sample at the tuned rate where there is none to share). Timed as one, all but the
    /// first would hardly move the path and the first would take it the whole way, so the path
    /// would go on in steps of a batch and bend sharply at each.
    fn share_batch(&mut self, batched: bool) {
        self.batch = if batched { self.batch.saturating_add(1) } else { 1 };
        let n = self.batch.min(self.streaming.len());
        if n < 2 {
            return;
        }
        let total: f64 = self.streaming.iter().rev().take(n).map(|p| p.1).sum();
        let each = if total > 0.0 { total / n as f64 } else { self.tuned_interval() };
        for point in self.streaming.iter_mut().rev().take(n) {
            point.1 = each;
        }
    }

    /// How old (milliseconds) a point of streamline's window is when it stops following any better
    /// than the rest of the path: as old as the last of the window's samples at the tuned rate.
    fn stream_reach(&self) -> f64 {
        STREAMLINE_WINDOW_MS - self.tuned_interval()
    }

    /// The milliseconds between samples at the rate smoothing is tuned for.
    fn tuned_interval(&self) -> f64 {
        1000.0 / if self.pointer.has_pressure() { PEN_RATE } else { FINGER_RATE }
    }

    /// Takes the smoothed path and pressure to `to`, `dt` milliseconds on, `near` how close each
    /// is to the input (0 for a point out of the window, 1 for the newest, which they reach).
    fn stream_to(&mut self, to: PathPoint, dt: f64, near: [f32; 2], out: &mut Vec<Stamp>) {
        let steps = (dt / self.tuned_interval()) as f32;
        // how far towards the input each goes: none held back where its smoothing is off
        let follow = |strength: f32, near: f32| {
            let follows = strength + near * (near - strength);
            1.0 - (1.0 - follows).max(0.0).powf(steps)
        };
        let [path, pressure] = self.stream_strengths();
        let smoothed = match self.smoothed {
            Some(prev) => PathPoint {
                pos: if path < 1.0 { prev.pos.lerp(to.pos, follow(path, near[0])) } else { to.pos },
                pressure: if pressure < 1.0 {
                    prev.pressure + (to.pressure - prev.pressure) * follow(pressure, near[1])
                } else {
                    to.pressure
                },
                ..to
            },
            None => to,
        };
        self.smoothed = Some(smoothed);
        self.stabilise_to(smoothed, Some(dt), (dt / self.tuned_interval()).min(MOST_STEPS), out);
    }

    /// Takes the path and pressure on through streamline's window to the input, as if no more
    /// were to come.
    pub(super) fn stream_on(&mut self, out: &mut Vec<Stamp>) {
        let strengths = self.stream_strengths();
        let reach = self.stream_reach();
        let mut age: f64 = self.streaming.iter().skip(1).map(|p| p.1).sum();
        while let Some((next, dt)) = self.streaming.pop_front() {
            let base = (1.0 - age / reach).max(0.0);
            let near = strengths.map(|strength| base.powf(1.0 - f64::from(strength)) as f32);
            self.stream_to(next, dt, near, out);
            age -= self.streaming.front().map_or(0.0, |p| p.1);
        }
    }

    /// How much of the way to each new point streamline takes the path and the pressure at the
    /// tuned rate (1: all of it, not smoothed).
    fn stream_strengths(&self) -> [f32; 2] {
        let pressure = if self.pressure_streams() { pressure_keep(self.pressure_smoothing()) } else { 0.0 };
        [1.0 - streamline_keep(self.brush.streamline), 1.0 - pressure]
    }

    /// How much a pen's pressure changes are smoothed: the brush's and the app's together, each
    /// taking away what the other leaves.
    pub(super) fn pressure_smoothing(&self) -> f32 {
        together(self.brush.pressure_smoothing, self.input.pressure_smoothing)
    }

    /// Whether streamline smooths the pressure (a pen's, or one given, its smoothing on).
    fn pressure_streams(&self) -> bool {
        self.pressure_counts() && self.pressure_smoothing() > 0.0
    }

    /// Whether the samples' pressure counts: a pen's, or one given (see
    /// [`StrokeBuilder::with_given_pressure`]). A finger's otherwise doesn't.
    fn pressure_counts(&self) -> bool {
        self.pointer.has_pressure() || self.given_pressure
    }

    /// How far the pressure grows from nothing at the start (`end` 0) or falls to nothing at the
    /// end (1), canvas pixels: none where the pressure doesn't count.
    fn pressure_taper(&self, end: usize) -> f32 {
        if self.pressure_counts() { self.pressure_tapers.get(end).copied().unwrap_or(0.0) } else { 0.0 }
    }

    /// How far back from the end its tapers reach (see [`StrokeBuilder::end_span`]): the end
    /// taper's, or the pressure's fall where that is longer.
    fn end_reach(&self, run_on: f32) -> f32 {
        self.end_span(run_on).max(self.pressure_taper(1))
    }

    /// `p`, a point motion filtering, streamline and stabilisation have all settled (`dt`
    /// milliseconds after the last; none: the first), with each use's pressure as the brush's
    /// curves take its pressure (a finger's full, whatever they say): followed as they lag and
    /// even out over the way the path went from the last (see [`StrokeBuilder::soften`]), unless
    /// the stroke is drawn for show or is an exact path; and the speed the path went there at.
    fn pressed_on(&mut self, p: PathPoint, dt: Option<f64>) -> PathPoint {
        let pen = self.pointer.has_pressure();
        let curved = if self.pressure_counts() { self.brush.pressures(p.pressure) } else { [1.0; 5] };
        // frames of a 60 Hz display since the last point (a sample's worth at the input's rate,
        // if it came with it)
        let gone = self.pressed_from.zip(dt).map(|(from, dt)| {
            let frames = if dt > 0.0 { (dt * 0.06) as f32 } else { sample_frames(pen) };
            (p.pos.distance(from), frames)
        });
        if let Some((moved, frames)) = gone {
            let moved = moved * self.points_per_pixel / frames;
            let scale = self.input.speed_distance.clamp(0.25, 1.0);
            let over = (moved - SLOW * scale) / (SPAN * scale);
            let now = if over > 0.0 && !self.steady { over.sqrt().min(1.0) } else { 0.0 };
            // the first move as it is; later ones smoothed over a few frames
            let held = if self.speed_known { SPEED_FRAMES / frames } else { 0.0 };
            self.speed = ((now + held * self.speed) / (held + 1.0)).clamp(0.0, 1.0);
            self.speed_known = true;
        }
        let soft = match (gone, self.soft) {
            (Some((moved, frames)), Some(before)) if !self.steady && !self.exact => {
                self.soften(curved, before, frames, moved)
            }
            _ => curved,
        };
        self.soft = Some(soft);
        self.pressure = Some(p.pressure);
        self.pressed_from = Some(p.pos);
        self.segments = self.segments.saturating_add(1);
        PathPoint { soft, speed: self.speed, ..p }
    }

    /// Takes the input on along motion filtering's points up to the `to`th, each filtered with
    /// the `half` either side of it, into streamline.
    fn average_to(&mut self, to: usize, half: usize, out: &mut Vec<Stamp>) {
        let interval = self.tuned_interval();
        let mut i = self.averaged.settled;
        let Some(weights) = self.weights.clone().filter(|w| !w.is_empty() && i < to) else { return };
        let (from, end) = (self.averaged.place(i) - half as isize, self.averaged.place(to - 1) + half as isize);
        let lanes: Vec<[f32; 8]> = (from..=end).map(|j| self.averaged.lanes(j)).collect();
        for around in lanes.windows(weights.len()) {
            let mut sum = [0.0f32; 8];
            for (w, point) in weights.iter().zip(around) {
                for (s, v) in sum.iter_mut().zip(point) {
                    *s += w * v;
                }
            }
            if let Some(next) = self.averaged.at_lanes(i, sum) {
                let next = self.still_where_it_was(&self.averaged, next);
                self.go_on(next, Some(interval), false, 1.0, out);
            }
            i += 1;
        }
        self.averaged.settled = i;
    }

    /// Takes the path on to `to`, a point streamline settled `dt` milliseconds (`samples` samples
    /// at the tuned rate) after the last, through stabilisation: each point waits until those
    /// after it within reach of its average are known.
    fn stabilise_to(&mut self, to: PathPoint, dt: Option<f64>, samples: f64, out: &mut Vec<Stamp>) {
        let half = self.stabilised();
        if half == 0 {
            let pressed = self.pressed_on(to, dt);
            self.path_to(pressed, out);
            return;
        }
        self.stable.add(to, samples);
        self.stable_to(self.stable.count().saturating_sub(half), half, out);
        self.stable.forget(half);
    }

    /// Takes the path on along stabilisation's points up to the `to`th, each averaged with the
    /// `half` either side of it.
    fn stable_to(&mut self, to: usize, half: usize, out: &mut Vec<Stamp>) {
        let mut i = self.stable.settled;
        if i >= to {
            return;
        }
        let mut sum = self.stable.sum(i, half);
        while i < to {
            let next = self.stable.mean(i, half, sum);
            self.stable.slide(&mut sum, i, half);
            i += 1;
            if let Some(next) = next {
                // (its points a sample apart at the tuned rate)
                let next = self.still_where_it_was(&self.stable, next);
                let dt = self.pressed_from.map(|_| self.tuned_interval());
                let pressed = self.pressed_on(next, dt);
                self.path_to(pressed, out);
            }
        }
        self.stable.settled = i;
    }

    /// `next`, an average of the points `held` has: where the input hasn't gone anywhere it stays
    /// exactly there, or the average's rounding would read as a move and a tap as a stroke.
    fn still_where_it_was(&self, held: &Averaged, next: PathPoint) -> PathPoint {
        match held.points.first().filter(|_| self.input_length <= 0.0) {
            Some(first) => PathPoint { pos: first.pos, ..next },
            None => next,
        }
    }

    /// Takes the input on along motion filtering's points to its end, as if no more were to come.
    fn average_on(&mut self, out: &mut Vec<Stamp>) {
        let half = self.averaged();
        if half > 0 {
            self.averaged.close();
            self.average_to(self.averaged.count(), half, out);
        }
    }

    /// Takes the path on along stabilisation's points to the end of what streamline has given it,
    /// as if no more were to come: where the stroke ends as it now stands, or would.
    fn stabilise_on(&mut self, out: &mut Vec<Stamp>) {
        let half = self.stabilised();
        if half > 0 {
            self.stable.close();
            self.stable_to(self.stable.count(), half, out);
        }
    }

    /// How many samples (at the tuned rate) each way motion filtering takes in: none when it is
    /// off (and on an exact path).
    pub(super) fn averaged(&self) -> usize {
        self.weights.as_deref().map_or(0, |weights| weights.len() / 2)
    }

    /// Motion filtering's weights, the stronger of the app's and the brush's.
    fn made_weights(&self) -> Vec<f32> {
        let amount = self.extras.motion_filtering.max(self.input.motion_filtering);
        if self.exact || self.steady || amount <= 0.0 {
            return Vec::new();
        }
        let expression = self.extras.motion_expression.max(self.input.motion_expression);
        let rate = if self.pointer.has_pressure() { PEN_RATE } else { FINGER_RATE };
        motion_weights(amount, expression, rate)
    }

    /// How many samples each way stabilisation averages.
    pub(super) fn stabilised(&self) -> usize {
        if self.exact || self.steady {
            return 0;
        }
        (self.extras.stabilization.clamp(0.0, 1.0).powf(STABILIZATION_POWER) * MOST_AVERAGED) as usize
    }

    /// Takes the path on to `to`: input read in whole pixels evened out first (see
    /// [`StrokeBuilder::destepped`]), as soon as the path has gone far enough on past each point.
    fn path_to(&mut self, to: PathPoint, out: &mut Vec<Stamp>) {
        if self.whole_pixels.is_none() || self.exact {
            self.path_on(to, out);
            return;
        }
        // a point where the newest one is only brings that one up to date: still waiting, as it
        // waits; gone on, where it went
        if self.steps.back().is_some_and(|n| n.pos.distance(to.pos) <= 1e-4) {
            if self.steps_settled < self.steps.len() {
                if let Some(newest) = self.steps.back_mut() {
                    *newest = PathPoint { pos: newest.pos, ..to };
                }
            } else if let Some(at) = self.steps_at {
                self.path_on(PathPoint { pos: at, ..to }, out);
            }
            return;
        }
        self.steps.push_back(to);
        self.settle_steps(false, out);
    }

    /// Takes the points read in whole pixels that are waiting on to the curve, evened out, as far
    /// as the path has gone on past them (`all`: every one, as if no more were to come); and lets
    /// go of those further behind than the next can reach.
    fn settle_steps(&mut self, all: bool, out: &mut Vec<Stamp>) {
        let Some(zoom) = self.whole_pixels else { return };
        let gap = |steps: &VecDeque<PathPoint>, j: usize| match (steps.get(j), steps.get(j + 1)) {
            (Some(a), Some(b)) => a.pos.distance(b.pos) * zoom,
            _ => 0.0,
        };
        while let Some(&p) = self.steps.get(self.steps_settled) {
            let i = self.steps_settled;
            if !all && self.steps.len() - i < STEPPED_WAITING {
                // it waits until the path goes on past it as far as it reaches back (and the reach
                // allows), or the next point came too far off to be evened out with
                let behind: f32 = (0..i).map(|j| gap(&self.steps, j)).sum();
                let ahead: f32 = (i..self.steps.len()).map(|j| gap(&self.steps, j)).sum();
                if ahead < behind.min(STEPPED_REACH) && gap(&self.steps, i) < BLEND_FAR {
                    break;
                }
            }
            let at = self.destepped(i, zoom);
            self.steps_settled += 1;
            self.steps_at = Some(at);
            self.path_on(PathPoint { pos: at, ..p }, out);
        }
        // those out of reach of the first still to come (or of the last, all having gone on)
        loop {
            let to = self.steps_settled.min(self.steps.len().saturating_sub(1));
            let reach: f32 = (1..to).map(|j| gap(&self.steps, j)).sum();
            if to < 2 || reach <= STEPPED_REACH {
                break;
            }
            self.steps.pop_front();
            self.steps_settled -= 1;
        }
    }

    /// Where the path goes through the `i`th of the points read in whole pixels: on the curve
    /// that best fits those as far either way of it along the path as it reaches both ways (up to
    /// [`STEPPED_REACH`]), a parabola by how far along they are, nearer ones counting more. A slow
    /// line then goes on at its slope, not in the steps of a pixel it was read in; a small curve
    /// keeps its bend. It moves the point no further than the pen can have been from it
    /// ([`STEPPED_ACROSS`], [`STEPPED_ALONG`]), and only as far as its neighbours are close (as
    /// [`StrokeBuilder::blended`] does): a quick stroke's corners stay.
    pub(super) fn destepped(&self, i: usize, zoom: f32) -> Vec2 {
        let Some(at) = self.steps.get(i).map(|p| p.pos) else { return Vec2::ZERO };
        // the points either way, each with how far along the path it is from this one (screen
        // pixels), as far as the reach and as the path goes
        let side = |ahead: bool| {
            let mut found = Vec::new();
            let (mut along, mut last, mut j) = (0.0f32, at, i);
            loop {
                let next = if ahead { j.checked_add(1) } else { j.checked_sub(1) };
                let Some((n, p)) = next.and_then(|n| self.steps.get(n).map(|p| (n, p.pos))) else {
                    return (found, along);
                };
                along += last.distance(p) * zoom;
                if along > STEPPED_REACH {
                    return (found, STEPPED_REACH);
                }
                found.push((if ahead { along } else { -along }, p));
                (last, j) = (p, n);
            }
        };
        let (before, back) = side(false);
        let (after, on) = side(true);
        let reach = back.min(on);
        let near: Vec<(f32, Vec2)> = before
            .into_iter()
            .chain(std::iter::once((0.0, at)))
            .chain(after)
            .filter(|(along, _)| along.abs() <= reach)
            .collect();
        if near.len() < 4 {
            return at;
        }
        // x and y each fitted by least squares as a + b s + c s^2, s how far along, nearer
        // points weighing more: a is where the curve goes through here, b the way it goes
        let mut sums = [0.0f64; 5];
        let (mut xs, mut ys) = ([0.0f64; 3], [0.0f64; 3]);
        for &(along, p) in &near {
            let w = f64::from(1.0 - along.abs() / (reach + 1.0));
            let s = f64::from(along);
            let d = p - at;
            let powers = [w, w * s, w * s * s, w * s * s * s, w * s * s * s * s];
            for (sum, power) in sums.iter_mut().zip(powers) {
                *sum += power;
            }
            for ((x, y), power) in xs.iter_mut().zip(ys.iter_mut()).zip(powers) {
                *x += power * f64::from(d.x);
                *y += power * f64::from(d.y);
            }
        }
        let [s0, s1, s2, s3, s4] = sums;
        let det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2);
        if det.abs() < 1e-9 {
            return at;
        }
        // (by Cramer's rule)
        let through = |[v0, v1, v2]: [f64; 3]| {
            ((v0 * (s2 * s4 - s3 * s3) - s1 * (v1 * s4 - s3 * v2) + s2 * (v1 * s3 - s2 * v2)) / det) as f32
        };
        let way = |[v0, v1, v2]: [f64; 3]| {
            ((s0 * (v1 * s4 - s3 * v2) - v0 * (s1 * s4 - s3 * s2) + s2 * (s1 * v2 - v1 * s2)) / det) as f32
        };
        let moved = Vec2::new(through(xs), through(ys)) * zoom;
        let way = Vec2::new(way(xs), way(ys));
        if !moved.is_finite() || !way.is_finite() || way.length() <= 1e-6 {
            return at;
        }
        let way = way * (1.0 / way.length());
        let across = Vec2::new(-way.y, way.x);
        let moved = way * moved.dot(way).clamp(-STEPPED_ALONG, STEPPED_ALONG)
            + across * moved.dot(across).clamp(-STEPPED_ACROSS, STEPPED_ACROSS);
        // as far as its neighbours are close
        let apart = [i.checked_sub(1), i.checked_add(1)]
            .into_iter()
            .filter_map(|j| self.steps.get(j?))
            .map(|p| p.pos.distance(at) * zoom)
            .fold(0.0, f32::max);
        let share = ((BLEND_FAR - apart) / (BLEND_FAR - BLEND_NEAR)).clamp(0.0, 1.0);
        at + moved * (share / zoom)
    }

    /// Takes the path on to `to`: the stretch before it walked along the curve through the points
    /// around it (an exact path goes straight, keeping its corners).
    fn path_on(&mut self, to: PathPoint, out: &mut Vec<Stamp>) {
        let Some(head) = self.head.filter(|_| !self.exact) else {
            self.walk_to(to, out);
            return;
        };
        // a point where the newest one is (the pen held still, or let go where it last was) adds
        // nothing to bend by: it only brings that one's pressure and the rest up to date
        let newest = self.ahead.unwrap_or(head);
        if newest.pos.distance(to.pos) <= 1e-4 {
            let held = Some(PathPoint { pos: newest.pos, ..to });
            if self.ahead.is_some() {
                self.ahead = held;
            } else {
                self.head = held;
            }
            return;
        }
        let Some(next) = self.ahead.replace(to) else {
            self.before_ahead = Some(head.pos);
            return;
        };
        // (input read in whole pixels was evened out over more already)
        let at = if self.whole_pixels.is_some() { next.pos } else { self.blended(self.before_ahead, next.pos, to.pos) };
        self.before_ahead = Some(next.pos);
        self.walk_curve(self.behind, head, PathPoint { pos: at, ..next }, Some(to), out);
        self.behind = Some(head);
    }

    /// Where the path goes through the point at `at`, the one before it at `before` and the one
    /// after it at `after`: evened out with them, a quarter each to its half, where they are close
    /// (see [`BLEND_NEAR`]), so a slow line read in whole pixels or points goes on smoothly rather
    /// than through every step; where they are far apart, at it, so a quick stroke read sparsely
    /// keeps its curve. The first and last points are where they came.
    pub(super) fn blended(&self, before: Option<Vec2>, at: Vec2, after: Vec2) -> Vec2 {
        let Some(before) = before else { return at };
        let apart = before.distance(at).max(after.distance(at)) * self.points_per_pixel;
        let share = ((BLEND_FAR - apart) / (BLEND_FAR - BLEND_NEAR)).clamp(0.0, 1.0);
        at + ((before + after) * 0.25 - at * 0.5) * share
    }

    /// Walks from the head `b` to `c` along the centripetal Catmull–Rom curve through `a`, `b`,
    /// `c` and `d` (the parabola through three where the path has no point before or after), in
    /// chords of at most [`CURVE_STEP`] (one, straight, if it is that short); the pressure and
    /// the rest change evenly along it.
    fn walk_curve(
        &mut self,
        a: Option<PathPoint>,
        b: PathPoint,
        c: PathPoint,
        d: Option<PathPoint>,
        out: &mut Vec<Stamp>,
    ) {
        let whole = b.pos.distance(c.pos);
        let pieces = if whole.is_finite() { ((whole / CURVE_STEP).ceil() as usize).clamp(1, MOST_PIECES) } else { 1 };
        for i in 1..=pieces {
            let t = i as f32 / pieces as f32;
            let pos =
                if i == pieces { c.pos } else { catmull_rom(a.map(|a| a.pos), b.pos, c.pos, d.map(|d| d.pos), t) };
            self.walk_piece(PathPoint { pos, ..b.lerp(c, t) }, Some(whole), out);
        }
        self.note_knot();
    }

    /// Notes how far the input has come with `s`, and when (samples without times of their own
    /// count as coming at the usual rate).
    fn keep_time(&mut self, s: Sample) {
        self.input_length += self.last_sample.map_or(0.0, |l| s.pos.distance(l.pos));
        let at = match self.history.last() {
            Some(&(before, _)) if s.time_ms <= before => before + REFERENCE_INTERVAL_MS,
            _ => s.time_ms,
        };
        self.history.push((at, self.input_length));
    }

    /// The input's length (as it came) put on the path the stamps follow, which smoothing
    /// shortens.
    fn on_path(&self, input: f32) -> f32 {
        if self.input_length > 0.0 { input * (self.length / self.input_length).min(1.0) } else { input }
    }

    /// How far stamps turn with the stroke: a pen turns them as it is held (its azimuth) in place
    /// of following the stroke, which is for touch.
    pub(super) fn follow(&self) -> f32 {
        if self.turned_by_pen() { 0.0 } else { self.brush.shape.follow }
    }

    /// The stamps turn as the pen is held, not with the stroke: a brush that says so, under a pen
    /// that tells how it is turned.
    fn turned_by_pen(&self) -> bool {
        self.brush.shape.follow_azimuth && self.pointer.has_pressure() && !self.no_azimuth
    }

    pub(super) fn classic(&self) -> bool {
        self.extras.classic_taper
    }

    /// How far along the path the start taper grows to full (0: none): the classic one over its
    /// length in canvas pixels; the newer one over the input up to the first point past its time
    /// (see [`StrokeBuilder::settle_start`]).
    fn start_span(&self) -> f32 {
        if let Some(span) = self.start_settled {
            return span;
        }
        let taper = self.taper();
        if taper.start <= 0.0 {
            return 0.0;
        }
        if self.classic() {
            return taper.start * CLASSIC_TAPER_SPAN;
        }
        self.on_path(self.start_input())
    }

    /// How far the input had come at the first point past the newer start taper's time.
    fn start_input(&self) -> f32 {
        let Some(&(t0, _)) = self.history.first() else { return 0.0 };
        let time = f64::from(self.taper().start) * TAPER_TIME_MS;
        self.history.iter().find(|(t, _)| t - t0 > time).map_or(self.input_length, |&(_, l)| l)
    }

    /// Whether the start taper's reach is known: none, the classic one's (a length), or the input
    /// has gone on past the newer one's time.
    fn start_known(&self) -> bool {
        let taper = self.taper();
        if taper.start <= 0.0 || self.classic() {
            return true;
        }
        let time = f64::from(taper.start) * TAPER_TIME_MS;
        matches!((self.history.first(), self.history.last()), (Some(&(t0, _)), Some(&(t, _))) if t - t0 > time)
    }

    /// Fixes the start taper's reach as the input passes its time, at how far the input had come
    /// (the path, waiting a point behind it, may not have got there yet): later input doesn't
    /// move it, so the stroke's start drawn for good stays as it is.
    fn settle_start(&mut self) {
        if self.start_settled.is_some() || !self.start_known() {
            return;
        }
        let timed = self.taper().start > 0.0 && !self.classic();
        if timed {
            // (the stamps drawn while it grew took its reach as it then stood)
            self.touched(0);
        }
        self.start_settled = Some(if timed { self.start_input() } else { self.start_span() });
    }

    /// Whether the newer start taper's reach is still to be known (the input not yet past its
    /// time).
    fn start_open(&self) -> bool {
        self.start_settled.is_none() && self.taper().start > 0.0 && !self.classic()
    }

    /// Whether the end taper shows while the stroke is drawn: with the tip animation on, a
    /// finger's as a pen's (a classic taper's only as it ends, where it runs on).
    fn end_shows(&self) -> bool {
        !self.classic() && (self.taper().end > 0.0 || self.pressure_taper(1) > 0.0) && self.extras.tip_animation
    }

    /// How far back from the end the end taper starts (0: none): the classic one over the run-on
    /// (`run_on` canvas pixels) up to its length; the newer one over the input since the last
    /// point before its time, counted back from the last move: held still, then lifted, the end
    /// keeps the taper it showed.
    fn end_span(&self, run_on: f32) -> f32 {
        let taper = self.taper();
        if taper.end <= 0.0 {
            return 0.0;
        }
        if self.classic() {
            return run_on.min(taper.end * CLASSIC_TAPER_SPAN);
        }
        let Some(&(_, total)) = self.history.last() else { return 0.0 };
        let moved = self.history.iter().find(|(_, l)| *l >= total).map_or(0.0, |&(t, _)| t);
        let time = f64::from(taper.end) * TAPER_TIME_MS;
        let first = self.history.iter().position(|(t, _)| *t >= moved - time).unwrap_or(0);
        let before = first.checked_sub(1).and_then(|i| self.history.get(i)).map_or(0.0, |&(_, l)| l);
        self.on_path(total - before)
    }

    /// A classic taper's end runs the stroke on past `last` (where the input ended) as it was
    /// going, a few shrinking steps that turn on as it turned; how far it went (canvas pixels).
    pub(super) fn run_on(&mut self, last: PathPoint, out: &mut Vec<Stamp>) -> f32 {
        let (Some(before), Some(last_input)) = (self.before_last[1], self.last_sample) else { return 0.0 };
        if !self.classic() || self.taper().end <= 0.0 {
            return 0.0;
        }
        let step = last_input.pos - before;
        let mut reach = step.length();
        if reach.is_nan() || reach <= 1e-3 {
            return 0.0;
        }
        let mut heading = step.y.atan2(step.x);
        let mut turn = self.before_last[0].map_or(0.0, |b| {
            let earlier = before - b;
            let d = heading - earlier.y.atan2(earlier.x);
            (d + std::f32::consts::PI).rem_euclid(std::f32::consts::TAU) - std::f32::consts::PI
        });
        let steps = if self.pointer.has_pressure() { RUN_ON_PEN } else { RUN_ON_TOUCH };
        let (start, mut at) = (self.length, last);
        for _ in 0..steps {
            reach *= RUN_ON_SHRINK;
            turn *= RUN_ON_TURN;
            heading += turn;
            at = PathPoint { pos: at.pos + Vec2::new(heading.cos(), heading.sin()) * reach, ..at };
            self.walk_to(at, out);
        }
        self.length - start
    }

    /// The path went `d` (`seg` long): the direction turns towards it as far as its length is of
    /// the reach, so the pen's wobbles shorter than that (slowing at a stroke's ends) don't swing
    /// stamps that follow the stroke out across it.
    fn turn_towards(&mut self, d: Vec2, seg: f32) {
        let reach = (self.diameter * TURN_REACH).max(TURN_REACH_LEAST);
        let k = (seg / reach).min(1.0);
        self.heading = self.heading * (1.0 - k) + d * (k / seg);
        if self.heading.length() > 1e-6 {
            self.direction = self.heading.y.atan2(self.heading.x);
        }
    }

    /// Walks the path from its head to `to`, placing stamps at the brush's spacing.
    pub(super) fn walk_to(&mut self, to: PathPoint, out: &mut Vec<Stamp>) {
        self.walk_piece(to, None, out);
    }

    /// As [`walk_to`](Self::walk_to), a piece of a stretch of input `whole` long (for how much
    /// a lean compresses), else a stretch of its own.
    pub(super) fn walk_piece(&mut self, to: PathPoint, whole: Option<f32>, out: &mut Vec<Stamp>) {
        if self.respaces() {
            let before = self.walk_state();
            self.walked.push(Walked { to, whole, now_ms: self.now_ms, before });
        }
        let Some(from) = self.head else {
            // the first point always gets a stamp, so a tap leaves a dot
            self.head = Some(to);
            self.note_knot();
            self.place(to, 0.0, out);
            return;
        };
        let seg = from.pos.distance(to.pos);
        if seg.is_nan() || seg <= 1e-4 {
            self.head = Some(PathPoint { pos: from.pos, ..to });
            return;
        }
        self.turn_towards(to.pos - from.pos, seg);
        // the stamps where the path starts were placed before it had a direction: they turn with
        // it now, as the rest will
        if self.length <= 0.0 {
            let turn = self.follow() * (self.direction - self.dab_direction);
            if turn != 0.0 {
                self.touched(0);
            }
            for p in &mut self.placed {
                p.angle += turn;
            }
            self.dab_turn = turn;
            self.turned |= turn != 0.0 && !self.placed.is_empty();
        }
        self.last_seg = whole.unwrap_or(seg);
        let mut done = 0.0f32;
        // each input segment goes on from the plain gap where it starts
        let mut last = self.spaced(from, self.length);
        while self.left_out + self.placed.len() < MAX_STAMPS {
            let gap = match self.pending_gap.take() {
                Some(gap) => gap,
                None => {
                    last = self.gap(from.lerp(to, done / seg), self.length + done, last);
                    last
                }
            };
            let need = gap - self.carry;
            if done + need > seg {
                self.carry += seg - done;
                self.pending_gap = Some(gap);
                break;
            }
            done += need.max(0.0);
            self.carry = 0.0;
            self.place(from.lerp(to, done / seg), self.length + done, out);
        }
        self.length += seg;
        self.head = Some(to);
        // a stretch of its own ends at a point of the path; a piece of a curve, at the curve's end
        if whole.is_none() {
            self.note_knot();
        }
    }

    /// Notes that the path's head is one of its points (once it has moved on from the last).
    fn note_knot(&mut self) {
        if self.knots.last().is_none_or(|&k| self.length > k + 1e-3) {
            self.knots.push(self.length);
        }
    }

    /// The (size, opacity) multipliers the pressure gives a point: none for a finger, which has
    /// no pressure (a brush that thins as it is pressed keeps its full size under a finger), unless
    /// it is given one.
    pub(super) fn pressed(&self, p: PathPoint) -> (f32, f32) {
        if self.pressure_counts() { self.brush.factors(p.soft[0], p.soft[1]) } else { (1.0, 1.0) }
    }

    /// The stamp's size with `by` of it left (0..1, what the pressure and the speed leave): they
    /// take it down to the brush's least, not to nothing.
    pub(super) fn sized(&self, by: f32) -> f32 {
        let least = (self.brush.min_size.min(self.brush.biggest()) * self.scale).min(self.diameter);
        least + (self.diameter - least) * by.max(0.0)
    }

    /// What faster input leaves of the size at a point (an amount below 0: slower input).
    pub(super) fn speed_size(&self, p: PathPoint) -> f32 {
        by_speed(self.extras.speed_size, p.speed).max(0.0)
    }

    /// The stamp diameter at a point of the path, before jitter and taper.
    fn diameter_at(&self, p: PathPoint) -> f32 {
        let (pressure, _) = self.pressed(p);
        let drawn = self.sized(pressure * self.speed_size(p));
        // leaning adds to it, up to the most the brush may be
        let most = (self.brush.biggest() * self.scale).max(drawn);
        (drawn + self.tilt_growth(p)).min(most)
    }

    /// How far the pen leans past where a tilt control at `theta` starts (see [`Tilt::reach`]);
    /// nothing for a finger, or a pen that doesn't say how it leans.
    pub(super) fn leaning(&self, p: PathPoint, theta: f32) -> f32 {
        if self.lean_known() { Tilt::reach(p.tilt, theta) } else { 0.0 }
    }

    /// Whether the input says how it leans: a pen that does.
    fn lean_known(&self) -> bool {
        self.pointer.has_pressure() && self.input.tilt_known
    }

    /// What leaning adds to the stamp's diameter (canvas pixels): the tilt size, as far as the
    /// lean has gone, the more the stroke runs across the lean.
    fn tilt_growth(&self, p: PathPoint) -> f32 {
        let size = self.brush.tilt.size;
        if size == 0.0 {
            return 0.0;
        }
        let reach = self.leaning(p, self.extras.tilt_angles.size);
        (size * reach * self.across_lean(p) * self.brush.size_unit() * self.scale).max(0.0)
    }

    /// How much of a lean's growth the stamp takes: with compression, none while the stroke runs
    /// along the lean, all of it across; without, all of it. A dab on its own takes it all.
    fn across_lean(&self, p: PathPoint) -> f32 {
        let compression = self.extras.tilt_compression;
        if compression <= 0.0 || self.last_seg <= 0.0 {
            return 1.0;
        }
        // the lean's direction on the canvas: the pen's orientation is clockwise from up on the screen
        let lean = p.azimuth - std::f32::consts::FRAC_PI_2 - self.screen_turn;
        let quarters = (self.direction - lean) / std::f32::consts::FRAC_PI_2;
        let across = ((quarters + 1.0).rem_euclid(2.0) - 1.0).abs();
        // a short step between inputs counts for less
        let long = 1.0 - 1.0 / (self.last_seg * self.last_seg);
        ((1.0 - compression) + compression * across * (across + long).max(0.0).sqrt()).clamp(0.0, 1.0)
    }

    /// The plain distance to the next stamp: the spacing's share of the stamp's square by its
    /// shorter side (a flat stamp lies as close as its height says), and half a pixel more (and a
    /// little with the spacing).
    pub(super) fn step(&self, p: PathPoint) -> f32 {
        self.step_as(p, 1.0, self.roundness_at(p))
    }

    /// As [`step`](Self::step), for a stamp with `thinned` of its width left and round `roundness`.
    fn step_as(&self, p: PathPoint, thinned: f32, roundness: f32) -> f32 {
        let spacing = self.brush.spacing.max(0.005);
        let extra = 0.5 + (spacing - SPACING_FLOOR).max(0.0) / (SPACING_SPAN / 2.0);
        let shorter = self.square_at(p) * thinned * SPACED_SQUARE * roundness.min(1.0);
        (spacing * shorter * self.extras.spacing_stride + extra).max(0.25)
    }

    /// The plain distance to the next stamp at `p`, `at` along the path, for the stamp as the
    /// tapers leave it there: a tapered tip's smaller stamps lie closer. The start taper as it
    /// stands, the end taper only once known (see [`StrokeBuilder::walk_again`]).
    fn spaced(&self, p: PathPoint, at: f32) -> f32 {
        if !self.respaces() {
            return self.step(p);
        }
        let share = self.drawn_share(p);
        let left = self.tapered(at, p.soft, self.speed_size(p), share, self.start_span(), self.spacing_end);
        let roundness = match left.round {
            Some((after, before)) => (self.roundness_at(p) * after / before).clamp(0.05, MOST_ROUNDNESS),
            None => self.roundness_at(p),
        };
        self.step_as(p, left.thinned, roundness)
    }

    /// Whether the tapers change the stamps' size, so how far apart they lie: the pieces of path
    /// walked are kept to walk again once the tapers are known.
    fn respaces(&self) -> bool {
        let t = self.taper();
        let fades = self.pointer.has_pressure() && self.extras.taper_pressure > 0.0;
        let pressure_tapers = self.pressure_taper(0) > 0.0 || self.pressure_taper(1) > 0.0;
        let tapers = (t.start > 0.0 || t.end > 0.0) && (t.size > 0.0 || fades);
        self.outside.0.is_none() && (tapers || pressure_tapers)
    }

    /// Where the walk along the path stands.
    fn walk_state(&self) -> WalkState {
        WalkState {
            head: self.head,
            length: self.length,
            carry: self.carry,
            pending_gap: self.pending_gap,
            last_seg: self.last_seg,
            heading: self.heading,
            direction: self.direction,
            random: self.random,
            grain_last: self.grain_last,
            dab_turn: self.dab_turn,
            stamps: self.left_out + self.placed.len(),
        }
    }

    /// Walks the path again from the last piece of it walked that began at or before `from` along
    /// it (never before the stamps drawn for good, nor those a copy leaves out), laying its stamps
    /// anew: their spacing then follows the tapers as they now stand.
    pub(super) fn walk_again(&mut self, from: f32) {
        let least = self.laid_for_good();
        let Some(first) = self.walked.iter().position(|w| w.before.stamps >= least) else { return };
        let at = self.walked.iter().rposition(|w| w.before.length <= from).filter(|&i| i >= first).unwrap_or(first);
        let again = self.walked.split_off(at);
        let Some(&Walked { before: b, .. }) = again.first() else { return };
        let keep = b.stamps.saturating_sub(self.left_out);
        // the stamps placed before the path had a direction turn with it again
        let undo = b.dab_turn - self.dab_turn;
        self.touched(if undo != 0.0 { 0 } else { keep });
        self.placed.truncate(keep);
        if undo != 0.0 {
            for p in &mut self.placed {
                p.angle += undo;
            }
        }
        (self.head, self.length, self.carry, self.pending_gap, self.last_seg) =
            (b.head, b.length, b.carry, b.pending_gap, b.last_seg);
        (self.heading, self.direction, self.random, self.grain_last, self.dab_turn) =
            (b.heading, b.direction, b.random, b.grain_last, b.dab_turn);
        let now = self.now_ms;
        // (nothing walked again is handed out here)
        let quiet = std::mem::replace(&mut self.quiet, true);
        let mut ignored = Vec::new();
        for w in again {
            self.now_ms = w.now_ms;
            self.walk_piece(w.to, w.whole, &mut ignored);
        }
        self.quiet = quiet;
        self.now_ms = now;
    }

    /// Walks again what the tapers reach that was walked before they were known: from the start
    /// while the start taper still grows, else from where the end taper begins (`end`: the path's
    /// length and the end taper's span, where it shows).
    fn respace(&mut self, end: Option<(f32, f32)>) {
        if !self.respaces() {
            return;
        }
        let fall = self.pressure_taper(1);
        let end = end.filter(|&(_, span)| span > 0.0 || fall > 0.0);
        let from = match end {
            _ if self.start_open() => f32::NEG_INFINITY,
            Some((length, span)) => self.eased_before(length - span.max(fall)),
            None => return,
        };
        self.spacing_end = end;
        self.walk_again(from);
        self.spacing_end = None;
    }

    /// How many of the stroke's stamps (counting those a copy leaves out) are laid as they'll stay:
    /// those drawn for good where the end taper shows as the stroke is drawn (where it doesn't,
    /// it thins them once known).
    fn laid_for_good(&self) -> usize {
        self.left_out + if self.end_shows() { self.kept } else { 0 }
    }

    /// Lets go of the pieces of path walked that can't be walked again: those before the stamps
    /// drawn for good and, the start taper's reach known, those well before where the end taper
    /// would begin were the stroke to end now (it begins further on as the stroke goes on).
    fn forget_walked(&mut self) {
        if self.walked.len() < WALKED_LOOKED_THROUGH.max(2 * self.walked_kept) {
            return;
        }
        let least = self.laid_for_good();
        let mut cut = self.walked.iter().position(|w| w.before.stamps >= least).unwrap_or(self.walked.len());
        if !self.start_open() {
            let from = self.eased_before(self.length - 2.0 * self.end_reach(0.0));
            if let Some(i) = self.walked.iter().rposition(|w| w.before.length <= from) {
                cut = cut.max(i);
            }
        }
        self.walked.drain(..cut);
        self.walked_kept = self.walked.len();
    }

    /// The gap to the next stamp at `p`, `at` along the path, the one before it `last`: the two
    /// gaps' mean, widened by the spacing jitter (a random share of it, as far as its curve lets
    /// it) and by the input's speed. Gaps only ever widen.
    pub(super) fn gap(&mut self, p: PathPoint, at: f32, last: f32) -> f32 {
        let x = self.extras;
        let mut widen = p.speed * x.speed_spacing * SPACING_FILE_SCALE;
        if x.spacing_jitter > 0.0 {
            let by = self.brush.pressure_curves.at(PressureCurve::SpacingJitter, p.soft[4].clamp(0.0, 1.0));
            widen += by * x.spacing_jitter * SPACING_FILE_SCALE * self.random.next();
        }
        ((1.0 + widen.max(0.0)) * (last + self.spaced(p, at)) * 0.5).max(0.25)
    }

    /// Each use's pressure followed from `before` towards `now` over `frames` (of a 60 Hz display)
    /// in which the pen moved `moved` canvas pixels: when the pen moves fast, a use with a response
    /// speed lags (to (frames / pixels, at least 0.2) to the power twice its response, each
    /// sample), and every use is evened out with what it was, more so the more often the pen
    /// reports (eased in over the stroke's first frames).
    pub(super) fn soften(&self, now: [f32; 5], before: [f32; 5], frames: f32, moved: f32) -> [f32; 5] {
        let pace = if moved > 0.0 { (frames / moved).clamp(0.2, 1.0) } else { 1.0 };
        let held = 2.0 / frames.max(1e-3);
        let ramp = (self.segments as f32 / held).clamp(0.0, 1.0) * held;
        [0, 1, 2, 3, 4].map(|k| {
            let (n, b) = (now.get(k).copied().unwrap_or(1.0), before.get(k).copied().unwrap_or(1.0));
            let response = self.extras.pressure_response.get(k).copied().unwrap_or(0.0);
            let followed = b + (n - b) * pace.powf(2.0 * response);
            ((followed + b * ramp) / (ramp + 1.0)).clamp(0.0, 1.0)
        })
    }

    /// The square a stamp's shape fills at a point (a round tip covers only part of it).
    fn square_at(&self, p: PathPoint) -> f32 {
        self.diameter_at(p) / if self.brush.round_tip() { crate::brushfile::ROUND_TIP_SHARE } else { 1.0 }
    }

    /// How much of the stamp at a point is its drawn shape, not what leaning grows it by.
    pub(super) fn drawn_share(&self, p: PathPoint) -> f32 {
        let grown = self.tilt_growth(p);
        let drawn = (self.diameter_at(p) - grown).max(1e-3);
        drawn / (drawn + grown)
    }

    /// The stamp's height over its width at a point, before jitter and taper: leaning adds the same
    /// to its height as to its width, then the roundness tilt takes it from its upright roundness
    /// towards its leaning one.
    fn roundness_at(&self, p: PathPoint) -> f32 {
        let x = &self.extras;
        let leant = self.leaning(p, x.tilt_angles.roundness);
        // (the tilt's roundness, even upright, only where the tilt acts: not on the pad or in a
        // preview unless the brush previews tilt)
        let tilted = if self.pad == Some(false) {
            1.0
        } else {
            x.tilt_roundness_max * (1.0 - leant) + (1.0 - x.tilt_roundness) * leant
        };
        let size_left = self.pressed(p).0 * self.speed_size(p);
        self.pressed_roundness(self.drawn_share(p), p.soft[3], size_left) * tilted
    }

    /// The tilt switches act: on the lean of a pen that says it, and on the pad only if the brush
    /// previews tilt. Otherwise they aren't there, so what they would hold back isn't.
    fn switches_act(&self) -> bool {
        self.lean_known() && self.pad != Some(false)
    }

    /// How far a tilt switch at `theta` lets its control act: all of it when the switch is off or
    /// doesn't act, as far as the pen leans when it is on.
    pub(super) fn switched(&self, p: PathPoint, theta: Option<f32>) -> f32 {
        match theta {
            Some(theta) if self.switches_act() => self.leaning(p, theta),
            _ => 1.0,
        }
    }

    /// How far the pen's lean moves a stamp toward the cap, as a share of its radius: the tilt
    /// angle's reach over the square root of two (a pen only, never on the pad).
    fn toward_cap(&self, p: PathPoint) -> Vec2 {
        if self.pad.is_some() || !self.pointer.has_pressure() {
            return Vec2::ZERO;
        }
        let reach = self.leaning(p, self.brush.tilt.angle / std::f32::consts::FRAC_PI_2);
        // the ramp's floor (an upright pen) moves nothing
        if reach <= 1.0 / 1024.0 {
            return Vec2::ZERO;
        }
        let k = reach * std::f32::consts::FRAC_1_SQRT_2;
        // the lean's direction on the canvas, as for the size compression
        let lean = p.azimuth - std::f32::consts::FRAC_PI_2 - self.screen_turn;
        Vec2::new(lean.cos(), lean.sin()) * k
    }

    pub(super) fn place(&mut self, p: PathPoint, distance: f32, out: &mut Vec<Stamp>) {
        let x = self.extras;
        let start_span = self.start_span();
        let sw = x.tilt_switches;
        let diameter = self.diameter_at(p);
        let (_, pressure_alpha) = self.pressed(p);
        // leaning fades the stamp by the square root of how far it has gone
        let fade = self.leaning(p, x.tilt_angles.opacity).sqrt() * self.brush.tilt.opacity;
        let tilt_alpha = if fade >= 0.0 { 1.0 - fade } else { 1.0 - (fade + self.brush.tilt.opacity) };
        let speed_alpha = by_speed(x.speed_opacity, p.speed).clamp(0.0, 1.0);
        // the fall off fades the stroke out by the cube root of how far it has gone
        let falloff = 1.0 - run_out(x.falloff, distance);
        use PressureCurve as PC;
        // the brush's own pressure curves for its other controls, at the stamp's pressure (a
        // finger's is full): each control scaled by its curve, or left as it is without one
        let pressure = p.soft[4].clamp(0.0, 1.0);
        let curves = &self.brush.pressure_curves;
        let by = |k: PressureCurve| curves.at(k, pressure);
        // the flow lets through as much as its pressure amount and curve say (an amount below 0
        // lets it all through)
        let flowing = curves.through(PC::Flow, pressure);
        let pressure_flow = (1.0 - x.pressure_flow * (1.0 - flowing)).clamp(0.0, 1.0);
        // flow, pressure, tilt and speed together through the opacity range, eased; then what
        // pressure lets through (the brush's pressure flow) and the fall off
        let b = &self.brush;
        let control = b.flow.clamp(0.0, 1.0).powf(1.0 / FLOW_POWER) * pressure_alpha * tilt_alpha * speed_alpha;
        let ranged = (b.min_opacity + (b.max_opacity - b.min_opacity) * control).clamp(0.01, 1.0);
        // a capped stroke builds up towards what the pressure lets through rather than taking it
        let transfer = pressure_flow;
        let through = if self.brush.rendering == Rendering::Capped { 1.0 } else { pressure_flow };
        let alpha = ranged.powf(FLOW_POWER) * through * falloff;
        // a gradual stroke's pixels each build up by their own share of the stamp (wet paint is
        // laid stamp by stamp as it is mixed, and a stroke laid in place takes the rule pixel by
        // pixel itself)
        let gradual =
            self.brush.rendering == Rendering::Gradual && !self.brush.mixes_wet() && !self.brush.laid_in_place();
        let overlap = if gradual { diameter / self.step(p) } else { 0.0 };
        let shape = self.brush.shape.clone();
        let [count_by, count_jitter_by, lateral_by, linear_by, size_jitter_by, opacity_jitter_by, scatter_by] = [
            PC::Count,
            PC::CountJitter,
            PC::LateralJitter,
            PC::LinearJitter,
            PC::SizeJitter,
            PC::OpacityJitter,
            PC::Scatter,
        ]
        .map(by);
        let [roundness_y_by, roundness_x_by, movement_by, grain_scale_by, zoom_by, depth_by, depth_jitter_by] = [
            PC::RoundnessJitterY,
            PC::RoundnessJitterX,
            PC::GrainMovement,
            PC::GrainScale,
            PC::GrainZoom,
            PC::GrainDepth,
            PC::GrainDepthJitter,
        ]
        .map(by);
        let along = |k: PressureCurve| curves.through(k, pressure);
        // the wet mix follows the stamp's own pressure, a finger's full
        let wet = [
            by(PC::WetGrade),
            by(PC::WetBlur),
            by(PC::WetBlurJitter),
            by(PC::WetnessJitter),
            by(PC::Charge),
            along(PC::Dilution),
            along(PC::WetPull),
            along(PC::SmudgePull),
        ];
        let color_jitter_by =
            [PC::HueJitter, PC::SaturationJitter, PC::LightnessJitter, PC::DarknessJitter, PC::SecondaryJitter];
        let color_has_curve = color_jitter_by.map(|k| curves.get(k).is_some());
        let color_jitter_by = color_jitter_by.map(by);
        // the colour moves with the pressure as its curves say (without one, as far as at full)
        let color_by = [PC::Hue, PC::Saturation, PC::Brightness, PC::Secondary].map(by);
        // as many beyond the first as the count tilt, the count's own curve and its jitter let
        // through, rounded down once
        let leaning = if sw.count.is_some() { self.switched(p, sw.count) } else { 1.0 };
        let beyond = (shape.count.clamp(1, 16) - 1) as f32 * leaning * count_by;
        let beyond = beyond * (1.0 - self.random.next() * x.count_jitter * count_jitter_by);
        let count = 1 + beyond.clamp(0.0, 15.0).floor() as u32;
        let (along, across) = (
            Vec2::new(self.direction.cos(), self.direction.sin()),
            Vec2::new(-self.direction.sin(), self.direction.cos()),
        );
        let drawn_share = self.drawn_share(p);
        let speed_size = self.speed_size(p);
        let base_roundness = self.roundness_at(p);
        // the jitter reaches half the stamp's diagonal (its square as round as it is here), and a
        // pixel more
        let half = self.square_at(p) * 0.5;
        let jitter_reach = half * (1.0 + base_roundness * base_roundness).sqrt() + 1.0;
        // leaning moves the stamps toward the pen's cap, by up to their radius
        let cap = self.toward_cap(p) * (diameter * 0.5);
        let file = |c: f32| JITTER_FILE_SCALE * c.powf(2.2);
        let jitter_lateral = file(x.jitter_lateral) * self.switched(p, sw.jitter_lateral) * lateral_by;
        let jitter_linear = file(x.jitter_linear) * self.switched(p, sw.jitter_linear) * linear_by;
        // a colour jitter's tilt switch drives it by the lean in place of the pressure, and with a
        // curve of its own the pressure's share comes on top
        let color_switches = if self.switches_act() { sw.color } else { [None; 5] };
        let color_tilt = color_switches.map(|theta| self.switched(p, theta));
        let (mut color_first, mut color_more) = (color_jitter_by, [0.0f32; 5]);
        for ((((first, more), tilt), on), has_curve) in
            color_first.iter_mut().zip(color_more.iter_mut()).zip(color_tilt).zip(color_switches).zip(color_has_curve)
        {
            if on.is_some() {
                *more = if has_curve { *first } else { 0.0 };
                *first = tilt;
            }
        }
        // the wet attack follows the pressure, and the lean when its switch is on (a finger, or a
        // pen that doesn't say how it leans, as a pen lying flat)
        let attack_tilt = match sw.attack {
            None => 1.0,
            Some(theta) if self.lean_known() => Tilt::reach(p.tilt, theta),
            Some(theta) => Tilt::reach(std::f32::consts::FRAC_PI_2, theta),
        };
        let pressed = if self.pressure_counts() { p.soft[1] } else { self.brush.touch_opacity };
        let attack = (pressed.clamp(0.0, 1.0) * attack_tilt * curves.through(PC::Attack, pressure)).clamp(0.0, 1.0);
        let [hue_by, saturation_by, lightness_by, darkness_by, secondary_by] = color_first;
        let [hue_more, saturation_more, lightness_more, darkness_more, secondary_more] = color_more;
        // a pen turns the stamp as it is held (its azimuth, and roll), in place of following the
        // stroke, which is for touch; relative to the stroke, it turns from the stroke's way
        let follow = self.follow();
        for _ in 0..count {
            let size = 1.0 - shape.size_jitter.clamp(0.0, 1.0) * size_jitter_by * self.random.next();
            let fade = 1.0 - shape.opacity_jitter.clamp(0.0, 1.0) * opacity_jitter_by * self.random.next();
            let mut center = p.pos + cap;
            let mut thrown = Vec2::ZERO;
            // one random point a stamp: as far as a share of the reach, in a random direction, the
            // reach across and along the stroke each its own
            if x.jitter_lateral > 0.0 || x.jitter_linear > 0.0 {
                let far = self.random.next();
                let towards = (self.random.next() * 2.0 - 1.0) * std::f32::consts::PI;
                thrown = across * (far * towards.sin() * jitter_lateral * jitter_reach)
                    + along * (far * towards.cos() * jitter_linear * jitter_reach);
                center = center + thrown;
            }
            if shape.throw > 0.0 {
                let a = self.random.next() * std::f32::consts::TAU;
                let r = self.random.next().sqrt() * shape.throw * diameter;
                center = center + Vec2::new(a.cos() * r, a.sin() * r);
            }
            let turned = self.stroke_turn - self.screen_turn + follow * self.direction;
            let mut own = shape.rotation;
            if shape.follow_azimuth && !self.no_azimuth {
                own += p.azimuth;
            }
            if let Some(from) = self.roll_from {
                own += if self.roll_relative { p.roll - from } else { p.roll };
            }
            if shape.scatter > 0.0 {
                own += (self.random.next() * 2.0 - 1.0) * shape.scatter * scatter_by * std::f32::consts::PI;
            }
            let angle = turned + own;
            // the roundness jitter squeezes the width and the height apart, and the stamp is scaled
            // back so the larger of them stays whole
            let [jitter_y, jitter_x] = x.roundness_jitter;
            let squeezed = |jitter: f32, rng: &mut Random| if jitter > 0.0 { 1.0 - jitter * rng.next() } else { 1.0 };
            let wide = squeezed(jitter_x * roundness_x_by, &mut self.random);
            let tall = squeezed(jitter_y * roundness_y_by, &mut self.random);
            let most = wide.max(tall).max(1e-3);
            let (wide, tall) = ((wide / most).max(0.05), tall / most);
            let roundness = (base_roundness * tall / wide).clamp(LEAST_ROUNDNESS, MOST_ROUNDNESS);
            let flip = [x.flip_x && self.random.next() < 0.5, x.flip_y && self.random.next() < 0.5];
            let [h, s, l, d] = x.stamp_color;
            let mut stamp_tint = self.color_jitter([h * hue_by, s * saturation_by, l * lightness_by, d * darkness_by]);
            let more = [h * hue_more, s * saturation_more, l * lightness_more, d * darkness_more];
            if more.iter().any(|&m| m > 0.0) {
                stamp_tint = stamp_tint.plus(self.color_jitter(more));
            }
            // pressure and leaning move the colour on as far as their controls say
            let lean = x.tilt_angles.color.map(|theta| self.leaning(p, theta));
            let moved = [0, 1, 2].map(|k| {
                let by = |a: &[f32; 3]| a.get(k).copied().unwrap_or(0.0);
                by(&x.pressure_color) * color_by.get(k).copied().unwrap_or(1.0) + by(&x.tilt_color) * by(&lean)
            });
            // the stamp's jitter goes from the colour the stroke's left
            let tint = self.stroke_shift.then(stamp_tint).shifted(moved);
            // random numbers only for the controls in use, so strokes without them replay as before
            let mut stamp_secondary = 0.0;
            if x.stamp_secondary > 0.0 {
                stamp_secondary = x.stamp_secondary * secondary_by * self.random.next();
                if secondary_more > 0.0 {
                    stamp_secondary += x.stamp_secondary * secondary_more * self.random.next();
                }
            }
            let secondary = (stamp_secondary
                + self.stroke_secondary
                + x.pressure_secondary * color_by[3]
                + x.tilt_secondary * self.leaning(p, x.tilt_angles.secondary))
            .clamp(0.0, 1.0);
            // a moving grain: from its least to its depth, as far as the pressure's curve, the
            // jitter (shallower from stamp to stamp) and the depth tilt (leaning takes it down to
            // its least) leave it; a least above the depth draws it up towards that the same way
            let base = self.brush.grain.depth.max(1e-3);
            let jitter = if x.grain_depth_jitter > 0.0 {
                x.grain_depth_jitter * depth_jitter_by * self.random.next()
            } else {
                0.0
            };
            let least = x.grain_depth_min.clamp(0.0, 1.0);
            let tilted = sw.grain_depth.filter(|_| self.switches_act()).map_or(0.0, |theta| self.leaning(p, theta));
            let depth = (least + (base - least) * (1.0 - jitter) * depth_by * (1.0 - tilted)).clamp(0.0, 1.0);
            let grain_press = p.soft[2].clamp(0.0, 1.0);
            // its tile under this stamp (following the stamp's size, or cropped), as a multiple of
            // the grain's own scale, which the renderer takes it times
            let (grain_square, grain_zoom) = (self.square_at(p) * size, x.grain_zoom * zoom_by);
            let tile = self.brush.grain.moving_tile(grain_square, grain_zoom, grain_scale_by);
            // it rolls on by as far as the stamp moved, at this stamp's tile and movement: so a
            // tile that follows the size (or a movement that follows the pressure) changes how
            // fast it rolls and doesn't send it elsewhere
            let rolled = x.grain_movement * movement_by / tile;
            let moved = match self.grain_last {
                Some((_, from)) => center - from,
                None => center,
            };
            let start = self.grain_last.map_or(self.grain_start, |(read, _)| read);
            let grain_read = start + turned_by(moved, self.screen_turn) * rolled;
            self.grain_last = Some((grain_read, center));
            // the patch read takes the rotation control's share of the stamp's whole turn, which
            // counts from an eighth of a turn, from a pen's lean the other way (kept to the
            // screen, the grain is too)
            let held = if self.turned_by_pen() { std::f32::consts::PI } else { 0.0 };
            let from_eighth = wrapped(std::f32::consts::FRAC_PI_4 + turned) + own - held;
            let grain_turn = x.grain_rotation * from_eighth + self.screen_turn;
            let placed = Placed {
                center,
                thrown,
                reach: jitter_reach,
                distance,
                diameter: diameter * size * wide,
                alpha: alpha * fade,
                angle,
                roundness,
                flip,
                tint,
                secondary,
                grain_depth: depth / base,
                grain_press,
                bleed_lean: (x.tilt_bleed * self.leaning(p, x.tilt_angles.bleed)).clamp(0.0, 1.0),
                grain_read,
                grain_turn,
                grain_square,
                grain_zoom,
                grain_scale_by,
                gradation: (x.tilt_gradation * self.leaning(p, x.tilt_angles.gradation)).max(0.0).sqrt().min(1.0),
                transfer,
                attack,
                wet,
                time_ms: self.now_ms,
                pressed: p.soft,
                drawn_share,
                speed_size,
                overlap,
            };
            self.placed.push(placed);
            if !self.quiet {
                let stamp = self.finish_stamp(&placed, start_span, None);
                self.bounds = self.bounds.union(stamp.bounds());
                out.push(stamp);
            }
        }
    }

    /// The stamp's roundness before leaning and jitter: the shape's, squeezed as light pressure
    /// `pressure` says, on the `drawn_share` of the stamp that isn't grown by leaning. Its height
    /// goes to the brush's least as its width does: with `size_left` of the width left, the less
    /// of it there is, the rounder a stamp held up by the least.
    fn pressed_roundness(&self, drawn_share: f32, pressure: f32, size_left: f32) -> f32 {
        let squeezed = 1.0 - self.extras.pressure_roundness * (1.0 - pressure.clamp(0.0, 1.0));
        let round = self.brush.shape.roundness.clamp(0.05, 1.0) * squeezed;
        let wide = self.sized(size_left).max(1e-3);
        drawn_share * self.sized(size_left * round) / wide + (1.0 - drawn_share)
    }

    pub(super) fn taper(&self) -> &Taper {
        if self.pointer.has_pressure() { &self.brush.pen_taper } else { &self.brush.touch_taper }
    }

    /// `f` (what the tapers leave at a place along the path) at `at`, worked out at the path's
    /// points and eased between them: at each point a quarter of each neighbour's and a half of
    /// its own, and between two points a blend of the four around that turns from one point's
    /// share to the next's over a quarter turn. So where a taper ends the width bends into what
    /// the pressure gives over a point either side, not at a corner; where it grows evenly it
    /// grows as it did. The path's ends keep what they have (a missing neighbour taken as far on
    /// the other side); past its newest point, `f` as it is.
    pub(super) fn eased(&self, at: f32, f: impl Fn(f32) -> [f32; 3]) -> [f32; 3] {
        let k = &self.knots;
        let i = k.partition_point(|&x| x <= at).saturating_sub(1).min(k.len().saturating_sub(2));
        let (Some(&b), Some(&c)) = (k.get(i), k.get(i + 1)) else { return f(at) };
        if at > c {
            return f(at);
        }
        let (fb, fc) = (f(b), f(c));
        let beyond = |near: [f32; 3], far: [f32; 3]| {
            let mut out = near;
            for (o, far) in out.iter_mut().zip(far) {
                *o = 2.0 * *o - far;
            }
            out
        };
        let fa = i.checked_sub(1).and_then(|h| k.get(h)).map_or_else(|| beyond(fb, fc), |&a| f(a));
        let fd = k.get(i + 2).map_or_else(|| beyond(fc, fb), |&d| f(d));
        // (nothing to ease where all four agree, as along most of a stroke)
        if fa == fb && fb == fc && fc == fd {
            return fb;
        }
        let t = if c > b { ((at - b) / (c - b)).clamp(0.0, 1.0) } else { 0.0 };
        let [wa, wb, wc, wd] = knot_weights(t);
        let mut out = [0.0; 3];
        for (o, ((a, b), (c, d))) in out.iter_mut().zip(fa.into_iter().zip(fb).zip(fc.into_iter().zip(fd))) {
            *o = (wa * a + wb * b + wc * c + wd * d).clamp(0.0, 1.0);
        }
        out
    }

    /// The first place along the path from which the eased tapers (see [`StrokeBuilder::eased`])
    /// of stamps may still change, for those up to `reach` settled: a stamp's taper takes the two
    /// points either side of the stretch it lies on, so it is settled once both after it are
    /// known and within `reach`.
    fn eased_before(&self, reach: f32) -> f32 {
        let m = self.knots.partition_point(|&x| x <= reach);
        // (the stretch ending at the point before the last within reach)
        m.checked_sub(2).and_then(|j| self.knots.get(j)).copied().unwrap_or(f32::NEG_INFINITY)
    }

    /// The stamp `p` as drawn, its start taper growing over `start_span` canvas pixels of the
    /// path and, once the stroke has ended, its end taper over the last `end` of its length:
    /// `(length, end)`.
    fn finish_stamp(&self, p: &Placed, start_span: f32, end: Option<(f32, f32)>) -> Stamp {
        let Tapered { size, alpha, thinned, round, grain_press } =
            self.tapered(p.distance, p.pressed, p.speed_size, p.drawn_share, start_span, end);
        let mut grain_press = grain_press.unwrap_or(p.grain_press);
        let roundness = match round {
            Some((after, before)) => (p.roundness * after / before).clamp(0.05, MOST_ROUNDNESS),
            None => p.roundness,
        };
        // the bleed: the pressure's, through the pressure, and the lean's, whatever the pressure
        let pressed_bleed = self.extras.pressure_bleed.clamp(0.0, 1.0);
        let grain_bleed = pressed_bleed + p.bleed_lean;
        if grain_bleed > 0.0 {
            grain_press = (pressed_bleed * grain_press + p.bleed_lean) / grain_bleed;
        }
        // the jitter's reach (half the stamp's diagonal and a pixel) goes with what is left of it
        let reach_left = ((p.reach - 1.0) * thinned + 1.0) / p.reach.max(1.0);
        // a grain that follows the stamp's size follows what is left of it
        let tile = self.brush.grain.moving_tile(p.grain_square * thinned, p.grain_zoom, p.grain_scale_by);
        let center = p.center - p.thrown * (1.0 - reach_left);
        // a grain fixed to the canvas going on stamp by stamp is read where the stamp lands, at the
        // brush's own tile and unturned, as it is read over a whole stroke
        let (grain_offset, grain_turn, grain_size) = if self.extras.grain_each_stamp {
            (center, 0.0, 1.0)
        } else {
            (p.grain_read * tile, p.grain_turn, (tile / self.brush.grain.scale.max(1.0)).max(1e-3))
        };
        Stamp {
            center,
            // a shape goes on showing on the quad that reaches past it however small it gets
            radius: (p.diameter * thinned * 0.5).max(if self.brush.round_tip() { 0.25 } else { 0.01 }),
            alpha: (p.alpha * alpha).clamp(0.0, 1.0),
            angle: p.angle,
            roundness,
            grain_offset,
            grain_turn,
            grain_press,
            grain_bleed,
            flip: p.flip,
            grain_mirrored: false,
            tint: p.tint,
            secondary: p.secondary,
            grain_depth: p.grain_depth,
            grain_size,
            gradation: p.gradation,
            taper: size.clamp(0.0, 1.0),
            transfer: p.transfer,
            attack: p.attack,
            wet: p.wet,
            time_ms: p.time_ms,
            distance: p.distance,
            overlap: p.overlap,
            tip: 0,
            lightness: 1.0,
            pick_up: None,
            threshold: [0.0; 2],
            copy: 0,
        }
    }

    /// What the tapers leave of a stamp `at` along the path, the start taper growing over
    /// `start_span` canvas pixels and the end taper as `end` says (see
    /// [`StrokeBuilder::finish_stamp`]): a stamp pressed `pressed`, with `speed_size` of its size
    /// left by the speed and `share` of it drawn (not grown by leaning).
    pub(super) fn tapered(
        &self,
        at: f32,
        pressed: [f32; 5],
        speed_size: f32,
        share: f32,
        start_span: f32,
        end: Option<(f32, f32)>,
    ) -> Tapered {
        let grown = |gone: f32, span: f32| if span > 0.0 { (gone / span).clamp(0.0, 1.0) } else { 1.0 };
        let tip = if self.pointer.has_pressure() { self.extras.pen_taper_tip } else { self.extras.touch_taper_tip };
        // a pen's pressure fades into the tapers as far as the brush says, and pressure that
        // counts grows and falls over its own tapers: the size, opacity, bleed and roundness it
        // gave scaled to what the faded pressure gives
        let fade = if self.pointer.has_pressure() { self.extras.taper_pressure } else { 0.0 };
        let (rise, fall) = (self.pressure_taper(0), self.pressure_taper(1));
        let fading = self.pressure_counts() && (fade > 0.0 || rise > 0.0 || fall > 0.0);
        // what the tapers leave at a place along the path: of the size, the opacity and the
        // pressure faded into them
        let tapered = |at: f32| {
            let (start, finish, risen, fallen) = match end {
                // a tap keeps its whole size, and its whole pressure
                Some((length, _)) if length <= 0.0 => (1.0, 1.0, 1.0, 1.0),
                Some((length, span)) => {
                    (grown(at, start_span), grown(length - at, span), grown(at, rise), grown(length - at, fall))
                }
                None => (grown(at, start_span), 1.0, grown(at, rise), 1.0),
            };
            let (size, alpha) = self.taper().applied(start, finish, tip);
            let faded = (1.0 - fade) + fade * (start * finish).powf(Taper::tip_power(tip));
            [size, alpha, faded * risen * fallen]
        };
        let [mut size, mut alpha, kept] = self.eased(at, tapered);
        let mut grain_press = None;
        let [s, o, b, r, _] = pressed;
        let size_left = if self.pressure_counts() { self.brush.factors(s, o).0 } else { 1.0 } * speed_size;
        let (mut faded_left, mut faded_round) = (size_left, r);
        if fading {
            let (_, o0) = self.brush.factors(s, o);
            let (s1, o1) = self.brush.factors(s * kept, o * kept);
            (faded_left, faded_round) = (s1 * speed_size, r * kept);
            let sized = self.sized(size_left);
            if sized > 0.0 {
                size *= self.sized(faded_left) / sized;
            }
            if o0 > 0.0 {
                alpha *= (o1 / o0).powf(FLOW_POWER);
            }
            grain_press = Some((b * kept).clamp(0.0, 1.0));
        }
        // a taper thins what the stamp is drawn at; what leaning grows it by comes after, so a
        // leaning pen's tips keep that
        let thinned = share * size + (1.0 - share);
        let mut round = None;
        if fading || share < 1.0 {
            let before = self.pressed_roundness(share, r, size_left);
            let left_share = if thinned > 0.0 { share * size / thinned } else { share };
            if before > 0.0 {
                round = Some((self.pressed_roundness(left_share, faded_round, faded_left), before));
            }
        }
        Tapered { size, alpha, thinned, round, grain_press }
    }
}
