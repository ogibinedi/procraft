//! Tool selection, brush sliders, colour, undo/redo, and session info on a [`CanvasSession`].

use super::*;

#[uniffi::export]
impl CanvasSession {
    /// The tool chosen; the one it replaces is kept as the last used (a pen's "switch to the
    /// previous tool" goes back to it).
    pub fn set_tool(&self, tool: ToolKind) {
        let mut st = self.shared.lock();
        if st.tool != tool {
            st.previous_tool = Some(st.tool);
        }
        st.tool = tool;
        crate::brushes::warm_pictures([st.brush(tool)]);
        st.warm_sharper();
    }

    /// The paint colour, sRGB 0..1 (red, green, blue).
    pub fn color(&self) -> Vec<f32> {
        self.shared.lock().color.to_vec()
    }

    /// The current tool's size slider, 0..1.
    pub fn set_brush_size(&self, slider: f32) {
        if slider.is_finite() {
            let mut st = self.shared.lock();
            st.sliders_mut().size = slider.clamp(0.0, 1.0);
            st.warm_sharper();
        }
    }

    /// The current tool's opacity slider, 0..1.
    pub fn set_brush_opacity(&self, slider: f32) {
        if slider.is_finite() {
            self.shared.lock().sliders_mut().opacity = slider.clamp(0.0, 1.0);
        }
    }

    /// The paint colour, sRGB 0..1. While text is edited, a colour chosen is also what is typed
    /// from then on, and the characters chosen (if any are) change to it: the app reads the text
    /// again ([`CanvasSession::layer_text`]) and draws it.
    pub fn set_color(&self, r: f32, g: f32, b: f32) {
        let c = [r, g, b].map(|v| if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.0 });
        let changed = {
            let mut st = self.shared.lock();
            let changed = std::mem::replace(&mut st.color, c) != c;
            // what Color Fill will fill shows in the colour
            if changed && st.selection_display == selection::SelectionDisplay::ColourFill && st.selection.is_some() {
                self.shared.frame(&mut st);
            }
            changed
        };
        if changed {
            self.text_colour_chosen(c);
        }
    }

    /// The app's colour history: each paint stroke's colour is added to it as the stroke ends.
    pub fn use_colour_history(&self, history: std::sync::Arc<ColourHistory>) {
        self.shared.lock().colour_history = Some(history);
    }

    /// The secondary colour, which brushes with secondary colour dynamics mix in.
    pub fn set_secondary_color(&self, r: f32, g: f32, b: f32) {
        let c = [r, g, b].map(|v| if v.is_finite() { v.clamp(0.0, 1.0) } else { 1.0 });
        self.shared.lock().secondary = c;
    }

    /// Undoes the last step; returns what it was called, if it was named.
    pub fn undo(&self) -> Option<String> {
        // (nothing while a plugin changes the artwork: its step isn't one yet)
        if self.shared.lock().plugin_stepping > 0 {
            return None;
        }
        // a plugin's own artwork goes back through what it kept; the canvas's own steps (an action's,
        // say) once it has none
        if self.plugin_undo(true) == Some(true) {
            return None;
        }
        self.await_pour();
        self.shared.lock().end_reveal();
        // transforming, the transform's own last change goes back: the canvas's steps wait until
        // it ends (one taken back under it would leave the box over what is no longer there)
        if self.shared.lock().transform.is_some() {
            self.transform_apply(crate::transform::TransformAction::Undo);
            return None;
        }
        // a picture being placed on a model goes first
        if self.model_picture_box().is_some() {
            self.model_picture_end(false);
            return Some("new layer".to_owned());
        }
        // editing text, a change to its box's width goes back first, the text still edited
        if let Some(name) = self.text_box_undo() {
            return Some(name);
        }
        if self.swap_crop(true) {
            return Some("Crop canvas".to_owned());
        }
        // recoloring, the fill open under the reticle is taken back first
        if self.recolor_step_aside() {
            return Some(recolor::STEP.to_owned());
        }
        // Liquify open: its last step taken back (past its first, it closes)
        if let Some(name) = self.step_liquify(true) {
            return name;
        }
        // an adjustment open on the layer: its changes taken back, or the step before it
        if let Some(name) = self.step_adjusting(Self::undo_step, true) {
            return name;
        }
        if self.quickshape_undo() {
            return None;
        }
        // the step that kept Liquify's work: taken back, Liquify opens again with its steps
        if let Some(name) = self.liquify_again(true) {
            return name;
        }
        self.undo_step()
    }

    /// Undoes the last step (cloning, the source going back too); returns its name.
    pub(crate) fn undo_step(&self) -> Option<String> {
        // cloning, the source goes back to where it was before the stroke
        self.shared.lock().adjust.puck_step(true);
        self.edit(Canvas::undo);
        self.shared.lock().canvas.as_ref().and_then(Canvas::redo_name)
    }

    /// Redoes the last step undone; returns what it was called, if it was named.
    pub fn redo(&self) -> Option<String> {
        if self.shared.lock().plugin_stepping > 0 {
            return None;
        }
        if self.plugin_undo(false) == Some(true) {
            return None;
        }
        self.await_pour();
        self.shared.lock().end_reveal();
        // transforming, nothing is redone until it ends
        if self.shared.lock().transform.is_some() {
            return None;
        }
        if let Some(name) = self.text_box_redo() {
            return Some(name);
        }
        if self.swap_crop(false) {
            return Some("Crop canvas".to_owned());
        }
        if let Some(name) = self.step_liquify(false) {
            return name;
        }
        // an adjustment open on the layer, with nothing changed yet: the step after it
        if let Some(name) = self.step_adjusting(Self::redo_step, false) {
            return name;
        }
        if self.quickshape_redo() {
            return None;
        }
        // Liquify undone past its first step, and nothing since: it opens again
        if let Some(name) = self.liquify_again(false) {
            return name;
        }
        // recoloring, the open fill steps aside and is made again over what comes back
        let recoloring = self.recolor_step_aside();
        let name = self.redo_step();
        if recoloring {
            self.recolor_again();
        }
        name
    }

    /// Redoes the last step undone (cloning, the source going forward too); returns its name.
    pub(crate) fn redo_step(&self) -> Option<String> {
        self.shared.lock().adjust.puck_step(false);
        self.edit(Canvas::redo);
        self.shared.lock().canvas.as_ref().and_then(Canvas::undo_name)
    }

    pub fn clear_layer(&self) {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let current = st.doc.current();
        st.finish_stroke();
        st.settle();
        // a plugin's layer: the strokes the selection takes (every one, with nothing selected) go
        if st.plugin_layer_kind(current).is_some() {
            if st.delete_taken_items(current) {
                self.shared.frame(st);
            }
            return;
        }
        if st.doc.is_locked(current) || st.doc.layer(current).is_none_or(|l| l.drawn_from_data()) {
            return;
        }
        // with a selection, only what is selected is cleared
        if let Some(mask) = st.reach_selection() {
            let Some(rect) = layer_ops::mask_rect(&mask) else { return };
            let pixels = st.layer_part(current, rect);
            if let (Some(mut pixels), Some(canvas)) = (pixels, st.canvas.as_mut()) {
                for (px, &m) in pixels.as_chunks_mut::<4>().0.iter_mut().zip(&layer_ops::mask_part(&mask, rect)) {
                    let keep = 255 - u16::from(m);
                    px.iter_mut().for_each(|c| *c = ((u16::from(*c) * keep + 127) / 255) as u8);
                }
                canvas.write_layer_part(current, rect, pixels);
                self.shared.frame(st);
            }
            return;
        }
        // the undo step keeps only what was painted, where the GPU can tell
        st.flush();
        let painted = match (st.canvas.as_ref(), st.gpu.as_ref()) {
            (Some(canvas), Some(gpu)) => canvas.painted_boxes(gpu, &[current]).pop().flatten(),
            _ => None,
        };
        if let Some(canvas) = st.canvas.as_mut() {
            canvas.name_next_step("clear layer");
            match painted {
                Some(painted) => canvas.clear_layer_within(current, painted),
                None => canvas.clear_layer(current),
            }
            self.shared.frame(st);
        }
    }

    pub fn info(&self) -> CanvasInfo {
        let st = self.shared.lock();
        let sliders = st.shown_sliders();
        CanvasInfo {
            width: st.doc.width(),
            dpi: st.doc.dpi,
            height: st.doc.height(),
            tool: st.tool,
            size: sliders.size,
            opacity: sliders.opacity,
            brush_diameter: st.brush(st.tool).diameter(sliders.size) * st.brush_scale(),
            // an open shape is kept, then undone; a plugin's artwork goes by its own steps
            // Liquify open: its first step undone closes it
            can_undo: st.plugin_steps().is_some_and(|(undo, _)| undo)
                || st.liquify_open()
                || st.canvas.as_ref().is_some_and(Canvas::can_undo)
                || st.crop_undo.is_some()
                || st.shape.is_some()
                || st.adjust.touched()
                || st.text_edit.as_ref().is_some_and(|e| e.has_step(true)),
            // an open shape has only its own drags to redo
            // Liquify open: only its own steps (and the step that kept it, once it came back by an
            // undo) to redo
            can_redo: if st.liquify_open() {
                st.liquify_can_redo()
            } else {
                st.shape.as_ref().map_or_else(
                    || {
                        st.canvas.as_ref().is_some_and(Canvas::can_redo)
                            || st.crop_redo_ready()
                            || st.shape_to_redo()
                            || st.liquify_redo_ready()
                    },
                    quickshape::ShapeEdit::can_redo,
                ) || st.text_edit.as_ref().is_some_and(|e| e.has_step(false))
                    || st.plugin_steps().is_some_and(|(_, redo)| redo)
            },
            zoom: st.view.map_or(1.0, |v| v.zoom()),
            frames: st.frames,
            frame_interval_ms: st.frame_interval_ms,
            error: st.error.clone(),
            revision: st.revision,
        }
    }
}

impl CanvasSession {
    /// Undo or redo, after finishing any open stroke; done at once, so the document is current
    /// when this returns.
    pub(crate) fn edit(&self, f: fn(&mut Canvas)) {
        // text being edited is kept first: it is the step undone
        self.text_end(true);
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        st.finish_stroke();
        // painting an adjustment on, undo and redo step through the strokes and it stays open
        if st.adjusting && st.pencil {
            st.restart_painted_on(Some(f));
            st.flush();
            st.revision += 1;
            self.shared.frame(st);
            return;
        }
        st.settle();
        st.flush();
        let before = st.canvas.as_ref().map(|c| (c.undo_step(), c.redo_step()));
        if let Some(canvas) = st.canvas.as_mut() {
            f(canvas);
            st.flush();
            // a stroke on a model's colour painted its roughness and metalness too; a tile's choice
            // chooses again what was before (or after) it
            if let Some(before) = before {
                st.follow_joint_step(before);
                st.follow_tile_step(before);
                st.follow_look_step(before);
                st.follow_layer_step(before);
            }
            st.sync_selection();
            st.revision += 1;
            self.shared.frame(st);
        }
    }
}
