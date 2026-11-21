//! Stroke input, queueing, prediction, and completion on a [`CanvasSession`].

use super::*;

#[uniffi::export]
impl CanvasSession {
    /// A stroke starts: `samples` are its first input.
    pub fn stroke_begin(&self, pointer: PointerKind, samples: Vec<PenSample>) {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        // (input left waiting by a stroke that has gone is for no stroke now)
        self.shared.drop_queued_input();
        // pressed as plugins say, before anything takes them
        let mut samples = samples;
        st.press(Some(pointer), &mut samples);
        // a plugin's own artwork takes its strokes itself
        if st.plugin_touch(plugin_artwork::Phase::Down, &samples) {
            self.shared.frame(st);
            return;
        }
        // a plugin changing the artwork: this stroke would join its step
        if st.plugin_stepping > 0 {
            return;
        }
        // radial symmetry in as many parts as a plugin says, for this stroke
        st.doc.guide.radial = st.hooks.as_ref().and_then(|hooks| hooks.radial_segments());
        // a fill still spreading in shows all of itself before anything is drawn over it
        st.end_reveal();
        // the graphics stopped (perhaps while the app was away, its notice long gone): nothing
        // drawn will show, so each stroke tells it again
        if st.gpu.as_ref().is_some_and(Gpu::is_lost) {
            st.crop_edit.lost_told = false;
        }
        if let Some(kept) = st.pad_strokes.as_mut() {
            kept.push((pointer, samples.clone()));
        }
        // a layer being transformed or adjusted takes no paint, unless the adjustment is painted on
        if st.transform.is_some() || (st.adjusting && !st.pencil) {
            return;
        }
        if st.adjusting {
            st.pencil_stroke(pointer, &samples);
            self.shared.frame(st);
            return;
        }
        // on a model the stroke is drawn on the screen and carried onto it
        if st.scene_shown() {
            st.scene_stroke_begin(pointer, &samples);
            self.shared.frame(st);
            return;
        }
        let current = st.doc.current();
        st.finish_stroke();
        st.settle();
        // locked layers, those that don't show (hidden themselves or by a group, or left out
        // while another is shown alone), groups, text layers and plugins' layers their plugins
        // can't take strokes on take no paint
        if st.doc.is_locked(current)
            || st.plugin_layer_off(current)
            || st.doc.unseen(current).is_some()
            || st
                .doc
                .layer(current)
                .is_none_or(|l| l.kind == procraft_engine::document::LayerKind::Group || l.text.is_some())
        {
            return;
        }
        let tool = if pointer == PointerKind::StylusEraser { ToolKind::Erase } else { st.tool };
        let (brush, style, diameter) = st.stroke_brush(tool, current);
        let second = st.second_brush(tool, current, &brush, &style);
        let diameter = diameter * st.brush_scale();
        st.strokes = st.strokes.wrapping_add(1);
        // the tool's brush is one of the Recent set's now (unless pinned there)
        st.note_recent(st.brush_ref(tool));
        st.info.strokes = st.info.strokes.saturating_add(1);
        let seed = st.strokes.wrapping_mul(0x9e37_79b9);
        // input read in whole pixels is evened out over the steps a slow line goes in, which keeps
        // what is laid for good a stretch behind the pen: only for a stroke the canvas shows on to
        // the pen meanwhile (its tip), not for paint laid in place (a smudge, wet paint) or a dual
        // brush, which show no more than is laid
        let shown_ahead = !style.in_place() && second.is_none();
        let whole_pixels = st.whole_pixels(&samples).filter(|_| shown_ahead);
        let make = |st: &State, brush: Brush, diameter: f32, seed: u32| {
            let preview_tilt = brush.setting("preview_tilt") >= 0.5;
            let builder = StrokeBuilder::with_seed(brush, diameter, pointer.into(), seed)
                .scaled(st.brush_scale())
                .with_input(st.input.clone())
                .on_view_turned(st.view.map_or(0.0, |v| v.rotation()))
                .seen_at(st.view.map_or(1.0, |v| v.zoom()) / st.display_scale);
            let mut builder = st.pressing.applied(builder, st.canvas_per_point());
            if let Some(zoom) = whole_pixels {
                builder = builder.read_in_whole_pixels(zoom);
            }
            if st.pad_strokes.is_some() {
                // on the studio's pad the lean moves no stamps, and the tilt switches act only
                // when the brush previews tilt
                builder = builder.on_pad(preview_tilt);
            }
            if st.pad_sample {
                // the pad's sample scribble is drawn as given: its path whatever the brush's
                // streamline, its pressure as it comes (its pace still counts)
                builder = builder.exact_path();
            }
            builder
        };
        let mut builder = make(st, brush.clone(), diameter, seed);
        let mut second_builder =
            second.as_ref().map(|(b, _, d)| make(st, b.clone(), d * st.brush_scale(), seed ^ 0x5eed_0002));
        // a brush a plugin's brush engine made is drawn by it while it is on: its stamps, how they
        // are laid and its second stroke are the engine's
        let first = st.to_canvas(samples.get(..1).unwrap_or_default()).first().map(|s| (s.pos.x, s.pos.y));
        let outside = st.outside_stroke(&brush, tool, current, pointer, diameter, seed, first);
        st.airbrush = outside.as_ref().and_then(|o| o.airbrush).zip(samples.last()).map(|(ms, last)| {
            let every = f64::from(ms) * 0.5;
            (every, *last, last.time_ms + every)
        });
        let laid = match outside {
            Some(o) => {
                builder = builder.with_engine(Box::new(o.first));
                second_builder = o.second.as_ref().map(|_| {
                    make(st, brush.clone(), diameter, seed ^ 0x5eed_0002).with_engine(Box::new(o.second_half))
                });
                Some((o.style, o.second))
            }
            None => None,
        };
        let canvas_samples = st.to_canvas(&samples);
        st.assist_from = canvas_samples.first().map(|s| s.pos);
        st.assist_angle = None;
        let canvas_samples = st.assisted(canvas_samples);
        // on a plugin's layer it is recorded as it goes (not on the brush studio's pad)
        st.recording = None;
        if st.pad_strokes.is_none() {
            st.begin_recording(current, tool, pointer, seed, whole_pixels, &canvas_samples);
        }
        st.drawn.clear();
        st.drawn_moving = 0.0;
        st.drawn_samples.clear();
        st.remember_input(&canvas_samples);
        // painted and erased strokes can be made shapes once they end
        st.last_stroke = None;
        st.kept_shape = None;
        st.unkept_shape = None;
        st.began =
            matches!(tool.stroke_tool(), StrokeTool::Paint | StrokeTool::Erase).then(|| quickshape::LastStroke {
                layer: current,
                // as it was laid: a brush engine's, its tips
                style: laid.as_ref().map_or(style, |(laid, _)| *laid),
                brush: brush.clone(),
                diameter,
                pointer,
                drawn: Vec::new(),
                pace: quickshape::SAMPLE_PACE,
                stroke: 0,
                fresh: second.is_none().then(|| builder.clone()),
                samples: Vec::new(),
                line: builder.clone(),
                seed,
            });
        let Some(canvas) = st.canvas.as_mut() else { return };
        let (style, second) = match laid {
            Some(laid) => laid,
            None => (style, second.map(|(_, s, _)| s)),
        };
        match second {
            Some(second) => canvas.begin_dual_stroke(current, style, second),
            None => canvas.begin_stroke(current, style),
        }
        // (paint laid in place keeps marks to go back to only if its end may lay it again)
        canvas.may_lay_again(builder.end_may_change_laid());
        draw_samples(canvas, &mut builder, second_builder.as_mut(), &canvas_samples, &st.doc.stroke_guide(), false);
        st.stroke = Some(builder);
        st.second = second_builder;
        st.ahead = false;
        self.shared.frame(st);
    }

    /// More input for the stroke (including the samples batched in between). While something holds
    /// the canvas (a frame laying a stroke's paint, which on a slow GPU takes a while) the samples
    /// wait in a queue of their own, taken all together by the next frame or the next input: the
    /// pen is never held up by the GPU's work, nor does each event's work pile up behind it.
    pub fn stroke_add(&self, samples: Vec<PenSample>) {
        let Some(mut guard) = self.shared.try_lock() else {
            self.shared.queue_input(samples, false);
            return;
        };
        let st = &mut *guard;
        let samples = self.shared.with_queued(samples);
        self.shared.add_samples(st, samples);
    }

    /// Where the input is predicted to go next: drawn ahead of the stroke until the next input,
    /// never kept. Waiting for the canvas, only the newest is kept (see
    /// [`CanvasSession::stroke_add`]).
    pub fn stroke_predict(&self, samples: Vec<PenSample>) {
        let samples = self.pen_predicted(samples);
        let Some(mut guard) = self.shared.try_lock() else {
            self.shared.queue_input(samples, true);
            return;
        };
        let st = &mut *guard;
        self.shared.take_queued_input(st);
        self.shared.predict_samples(st, samples);
    }
}

impl Shared {
    /// `samples` after the samples waiting for the canvas (taken with them, their prediction
    /// dropped).
    pub(crate) fn with_queued(&self, samples: Vec<PenSample>) -> Vec<PenSample> {
        if !self.input_waiting() {
            return samples;
        }
        let mut queued = self.queued.lock();
        self.input_queued.store(false, std::sync::atomic::Ordering::Release);
        let mut all = std::mem::take(&mut queued.samples);
        queued.predicted = None;
        all.extend(samples);
        all
    }

    /// More input for the stroke, the canvas held (see [`CanvasSession::stroke_add`]).
    pub(crate) fn add_samples(&self, st: &mut State, samples: Vec<PenSample>) {
        let mut samples = samples;
        st.press(None, &mut samples);
        if st.plugin_touch(plugin_artwork::Phase::Moved, &samples) {
            self.frame(st);
            return;
        }
        if st.plugin_stepping > 0 {
            return;
        }
        if st.scene_shown() {
            st.scene_stroke_add(&samples, false);
            self.frame(st);
            return;
        }
        st.keep_pad_input(&samples);
        if let (Some(airbrush), Some(last)) = (st.airbrush.as_mut(), samples.last()) {
            airbrush.1 = *last;
        }
        let canvas_samples = st.assisted(st.to_canvas(&samples));
        // a snapped shape turns and grows with the pen still holding it
        if st.shape.is_some() {
            if let Some(to) = canvas_samples.iter().rev().find(|s| s.is_finite()).map(|s| s.pos)
                && st.follow_shape(to)
            {
                self.frame(st);
            }
            return;
        }
        st.remember_input(&canvas_samples);
        st.read_finer(&samples);
        if let Some(recording) = st.recording.as_mut().filter(|_| st.stroke.is_some()) {
            recording.add(&canvas_samples);
        }
        // a brush engine told the colours under the dabs it asked about before its next samples
        st.tell_under();
        let (Some(builder), Some(canvas)) = (st.stroke.as_mut(), st.canvas.as_mut()) else { return };
        // while the platform predicts input, what runs ahead waits for the frame (its prediction
        // follows these samples): on to the input, until a prediction comes
        let later = st.predicting;
        if draw_samples(canvas, builder, st.second.as_mut(), &canvas_samples, &st.doc.stroke_guide(), later) {
            st.ahead = later;
            st.redraw_painted_on();
            self.frame(st);
        }
    }

    /// Where the input is predicted to go next, the canvas held (see
    /// [`CanvasSession::stroke_predict`]).
    pub(crate) fn predict_samples(&self, st: &mut State, samples: Vec<PenSample>) {
        let mut samples = samples;
        st.press_predicted(&mut samples);
        if st.scene_shown() {
            return;
        }
        // a snapped shape is drawn whole: nothing runs ahead of it
        if st.shape.is_some() {
            return;
        }
        let canvas_samples = st.to_canvas(&samples);
        // (what the samples before left to run ahead is drawn again here)
        st.predicting = !canvas_samples.is_empty();
        st.ahead = false;
        let (Some(builder), Some(canvas)) = (st.stroke.as_mut(), st.canvas.as_mut()) else { return };
        let guide = st.doc.stroke_guide();
        // a stroke shown with its tip ends it where the input is predicted to go
        if canvas.shows_tip() && builder.shows_tip() {
            canvas.set_tip(&guide::mirrored(&guide, &builder.preview_tip(&canvas_samples)));
        } else {
            show_ahead(canvas, builder, &canvas_samples, &guide);
        }
        self.frame(st);
    }
}

#[uniffi::export]
impl CanvasSession {
    /// The stroke ends (the pen or finger lifted) after `samples`.
    pub fn stroke_end(&self, samples: Vec<PenSample>) {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        // (the input waiting for the canvas came before)
        self.shared.take_queued_input(st);
        let mut samples = samples;
        st.press(None, &mut samples);
        st.airbrush = None;
        if st.plugin_touch(plugin_artwork::Phase::Up, &samples) {
            self.shared.frame(st);
            return;
        }
        if st.plugin_stepping > 0 {
            return;
        }
        // a paint stroke's colour goes into the history (painting an adjustment on uses none)
        if st.tool == ToolKind::Paint
            && !st.adjusting
            && let Some(history) = &st.colour_history
        {
            history.painted(history::hex(st.color));
        }
        if st.scene_shown() {
            st.scene_stroke_add(&samples, true);
            self.shared.frame(st);
            return;
        }
        st.keep_pad_input(&samples);
        let canvas_samples = st.assisted(st.to_canvas(&samples));
        // a snapped shape stays open to be edited, until the next thing done keeps it
        if st.shape.is_some() {
            if let Some(to) = canvas_samples.iter().rev().find(|s| s.is_finite()).map(|s| s.pos) {
                st.follow_shape(to);
            }
            st.let_go_shape();
            self.shared.frame(st);
            return;
        }
        // (painted into an open adjustment, it tells no plugin: what one did would end it)
        let tells = !st.adjusting;
        st.tell_under();
        st.engine_asking = plugin_brushes::Asking::default();
        st.read_finer(&samples);
        st.ahead = false;
        let (Some(mut builder), Some(canvas)) = (st.stroke.take(), st.canvas.as_mut()) else { return };
        let mut second = st.second.take();
        lift_samples(canvas, &mut builder, second.as_mut(), &canvas_samples, &st.doc.stroke_guide());
        match st.recording.take() {
            // on a plugin's layer, handed to the plugin
            Some(mut recording) => {
                recording.lift(&canvas_samples);
                st.end_recorded(recording, builder, second);
            }
            None => close_stroke(canvas, builder, second, &st.doc.stroke_guide()),
        }
        st.redraw_painted_on();
        st.bake_painted_on();
        st.remember_last_stroke();
        // plugins told it was kept, before the next touch is taken: whether any listens is asked
        // now, as taking the canvas again after would wait on the frame laying the stroke
        let told = tells && st.hooks.as_ref().is_some_and(|h| h.listens(plugin_run::Hook::StrokeKept));
        self.shared.frame(st);
        drop(guard);
        if told {
            self.tell_plugins(plugin_run::Hook::StrokeKept);
        }
    }

    /// Drops the stroke (a second finger turned it into a gesture).
    pub fn stroke_cancel(&self) {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        self.shared.drop_queued_input();
        st.airbrush = None;
        if st.plugin_touch(plugin_artwork::Phase::Off, &[]) {
            self.shared.frame(st);
            return;
        }
        if st.scene_shown() {
            st.scene_stroke_cancel();
            self.shared.frame(st);
            return;
        }
        if let Some(kept) = st.pad_strokes.as_mut() {
            kept.pop();
        }
        st.stroke = None;
        st.ahead = false;
        st.second = None;
        st.shape = None;
        st.began = None;
        st.recording = None;
        if let Some(canvas) = st.canvas.as_mut() {
            canvas.cancel_stroke();
            // what it painted on is in the adjustment's mask: start that again
            st.restart_painted_on(None);
            self.shared.frame(st);
        }
    }
}
