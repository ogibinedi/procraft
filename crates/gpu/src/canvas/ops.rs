//! Layer operations, transforms, filters, adjustments, blur, liquify, and undo/redo steps on [`Canvas`].

use super::*;

impl Canvas {
    /// Shows `picture` (its top left `size` pixels, premultiplied, as composited) over the canvas
    /// rectangle `rect` (left, top, width, height in canvas pixels, fractions allowed) in place of
    /// the canvas there: part of it drawn again at the target's resolution, copied now. None takes
    /// it away.
    pub fn set_sharp(&mut self, gpu: &Gpu, picture: Option<(&wgpu::Texture, (u32, u32), [f32; 4])>) {
        self.sharp = None;
        let Some((source, (w, h), rect)) = picture else { return };
        let fits = w > 0 && h > 0 && w <= source.width() && h <= source.height();
        if !fits || !rect.iter().all(|v| v.is_finite()) || rect[2] <= 0.0 || rect[3] <= 0.0 {
            return;
        }
        let device = &gpu.device;
        let texture = texture(device, "sharp", w, h, LAYER_FORMAT);
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("sharp") });
        encoder.copy_texture_to_texture(copy_at(source, 0, 0), copy_at(&texture, 0, 0), extent(w, h));
        gpu.queue.submit([encoder.finish()]);
        let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
        let bind = sampled_bind(device, &self.pipelines.sampled_layout, &view, &self.sampler);
        self.sharp = Some(Sharp { rect, _texture: texture, bind });
    }

    /// The composite's texture (see [`Canvas::composite_view`]).
    pub fn composite_texture(&self) -> &wgpu::Texture {
        &self.composite
    }

    /// Whether work is queued, or the picture is to be composited again: what shows is about to
    /// change.
    pub fn changes_queued(&self) -> bool {
        !self.ops.is_empty() || !self.dirty.is_empty() || self.active.is_some()
    }

    /// Whether part of the canvas shows drawn again sharper (see [`Canvas::set_sharp`]).
    pub fn shows_sharp(&self) -> bool {
        self.sharp.is_some()
    }

    /// Whether work queued and not yet done changes `layer`'s pixels (a stroke open on it counts).
    pub fn writes_queued(&self, layer: LayerId) -> bool {
        self.ops_layers().1.contains(&layer)
    }

    /// Ends the open stroke as [`Canvas::end_stroke`] does, knowing that it changes pixels only
    /// within `rects` (a stroke drawn in from elsewhere, whose reach is known more closely than
    /// its bounds): its undo step keeps only what is under them, and none if they are empty.
    pub fn end_stroke_within(&mut self, rects: Vec<IRect>) {
        self.prediction.clear();
        self.laid = None;
        self.laid_second = None;
        self.untip();
        if let Some(mut active) = self.active.take() {
            self.strokes_ended += 1;
            active.number = self.strokes_ended;
            self.ops.push(Op::Within(rects));
            self.ops.push(Op::Commit(active));
        }
    }

    /// Sends stamps to the GPU in pieces of at most this much work, in place of [`WORK_AT_ONCE`]:
    /// for tests.
    #[doc(hidden)]
    pub fn set_work_at_once(&mut self, work: u64) {
        self.work_at_once = work;
    }

    /// Uploads at most this many stamps in a render, in place of [`STAMPS_AT_ONCE`]: for tests.
    #[doc(hidden)]
    pub fn set_stamps_at_once(&mut self, stamps: u64) {
        self.stamps_at_once = stamps.max(1);
    }

    /// How many stamps have been queued to be laid so far (each lot of a stroke's, and its
    /// second's): for tests counting what an end lays.
    #[doc(hidden)]
    pub fn stamps_queued(&self) -> u64 {
        self.stamps_queued
    }

    /// Whether the open stroke's end may lay again stamps it has laid (its tapers): painting in
    /// place, it keeps marks to go back to then (see `rewind.rs`), which take copies of what it
    /// writes as it goes. Told after it begins (it may, until told otherwise).
    pub fn may_lay_again(&mut self, may: bool) {
        self.may_lay_again = may;
    }

    /// Whether strokes painting in place keep marks to go back to as they are laid again (see
    /// `rewind.rs`), and within how many bytes (none: as the device's memory gives them): for tests
    /// comparing them with strokes laid again from scratch.
    #[doc(hidden)]
    pub fn set_rewinds(&mut self, on: bool, most: Option<u64>) {
        self.rewinds = on;
        self.rewind_most = most;
    }

    /// The marks strokes painting in place have made to go back to so far, and how many times they
    /// went back to one; and the bytes the open stroke's marks take on the GPU.
    pub fn rewinds_made(&self) -> (u64, u64, u64) {
        let bytes = self.smudge.as_ref().and_then(|s| s.rewind.as_ref()).map_or(0, rewind::Rewind::bytes);
        (self.marks_made, self.rewound, bytes)
    }

    /// Lays a stroke laid again as it ends for at most this long in a render that shows the canvas,
    /// in place of [`RELAY_AT_ONCE`]: for tests (none, a piece of its work a render).
    #[doc(hidden)]
    pub fn set_relay_at_once(&mut self, most: std::time::Duration) {
        self.relay_at_once = most;
    }

    /// Whether a stroke laid again as it ends is still being laid, a share at each render that shows
    /// the canvas (the picture held as it was until it is all laid).
    pub fn relaying(&self) -> bool {
        self.relaying
    }

    /// Whether paint laid in place is waiting for the GPU to get through what came before (see
    /// [`IN_PLACE_FRAME_MS`]): the stroke shown so far as laid, and more laid at each render.
    pub fn catching_up(&self) -> bool {
        self.catching_up
    }

    /// The most work of paint laid in place a render lays (see [`IN_PLACE_FRAME_MS`]): as much as
    /// the GPU gets through in that while, by the fastest it was seen to (each sighting counting a
    /// little less as more come, so that a GPU slowing down is followed), and [`IN_PLACE_LEAST`] at
    /// least; all of it before that is known, or in a render that doesn't show the canvas. The last
    /// render that laid such work is seen here if it is done (its time from being sent to being seen
    /// done is at least what the GPU took: the GPU is at least that fast).
    pub(crate) fn in_place_most(&mut self, gpu: &Gpu, shown: bool) -> u64 {
        if let Some((index, sent, work)) = self.in_place_sent.take() {
            let now = wgpu::PollType::Wait {
                submission_index: Some(index.clone()),
                timeout: Some(std::time::Duration::ZERO),
            };
            if gpu.device.poll(now).is_ok() {
                let rate = work as f64 / (sent.elapsed().as_secs_f64() * 1000.0).max(0.5);
                self.in_place_rate = Some(self.in_place_rate.map_or(rate, |r| (r * 0.9).max(rate)));
            } else {
                self.in_place_sent = Some((index, sent, work));
            }
        }
        match self.in_place_rate {
            Some(rate) if shown && self.in_place_paced => ((rate * IN_PLACE_FRAME_MS) as u64).max(IN_PLACE_LEAST),
            _ => u64::MAX,
        }
    }

    /// Whether paint laid in place is laid only as fast as the GPU gets through it (see
    /// [`IN_PLACE_FRAME_MS`]): for tests comparing it with all of it laid in each render.
    #[doc(hidden)]
    pub fn set_in_place_paced(&mut self, on: bool) {
        self.in_place_paced = on;
    }

    /// The most stamps one buffer of this device holds.
    pub(crate) fn stamps_in_a_buffer(device: &wgpu::Device) -> u64 {
        (device.limits().max_buffer_size / size_of::<StampInstance>() as u64).max(1)
    }

    /// How many compute dispatches smudges, paint laid in place and stamps laid by their rule have
    /// recorded: for tests.
    #[doc(hidden)]
    pub fn compute_dispatches(&self) -> u64 {
        self.dispatched.load(Ordering::Relaxed)
    }

    /// Lays wet paint and paint laid in place a stamp a dispatch (or fragment pass), as before a
    /// pass's stamps went down in runs at once: for tests, which compare the two.
    #[doc(hidden)]
    pub fn lay_stamp_by_stamp(&mut self, on: bool) {
        self.stamp_by_stamp = on;
    }

    /// How many pieces of work were sent early, there being too much for one: for tests.
    #[doc(hidden)]
    pub fn pieces_sent(&self) -> u64 {
        self.pieces_sent
    }

    /// Paces the canvas's work on the GPU (`on`): pieces of no more than [`PACED_WORK`], each
    /// waited for before the next is sent. For a canvas drawn on a thread of its own beside the
    /// window's frames (a brush's sample stroke): a frame then never queues behind more than a short
    /// piece of its work, and no piece runs long enough for a driver to stop it. What is laid is the
    /// same but for a level here and there where pieces meet, as with any stroke sent in pieces.
    pub fn pace(&mut self, on: bool) {
        self.paced = on;
        self.work_at_once = if on { PACED_WORK } else { WORK_AT_ONCE };
    }

    /// The longest a paced piece of work took on the GPU, from being sent to being done, since
    /// last asked (see [`Canvas::pace`]).
    pub fn take_paced_longest(&mut self) -> std::time::Duration {
        std::mem::take(&mut self.paced_longest)
    }

    /// Waits for the piece of work sent as `index` at `sent`, if the canvas is paced (see
    /// [`Canvas::pace`]).
    pub(crate) fn wait_if_paced(&mut self, gpu: &Gpu, index: wgpu::SubmissionIndex, sent: std::time::Instant) {
        if !self.paced {
            return;
        }
        let wait = wgpu::PollType::Wait { submission_index: Some(index), timeout: Some(PACED_WAIT_MOST) };
        if let Err(e) = gpu.device.poll(wait) {
            log::warn!("waiting for a paced piece of work: {e}");
        }
        self.paced_longest = self.paced_longest.max(sent.elapsed());
    }

    /// What the undo and redo history's pixels take on the GPU, bytes.
    pub fn history_bytes(&self) -> u64 {
        self.undo.iter().chain(&self.redo).map(Step::bytes).sum()
    }

    /// The most the undo history's pixels may take (see [`MAX_UNDO_BYTES`]).
    pub fn history_most(&self) -> u64 {
        if self.resident_budget == 0 {
            MAX_UNDO_BYTES
        } else {
            (self.resident_budget / 2).clamp(LEAST_UNDO_BYTES, MAX_UNDO_BYTES)
        }
    }

    /// What the undo history's pixels kept compressed off the GPU take, bytes.
    pub fn history_packed_bytes(&self) -> u64 {
        self.undo.iter().chain(&self.redo).map(Step::packed_bytes).sum()
    }

    /// Whether the device has little memory (see [`LOW_MEMORY_BUDGET`]).
    pub(crate) fn low_memory(&self) -> bool {
        self.resident_budget != 0 && self.resident_budget <= LOW_MEMORY_BUDGET
    }

    /// Keeps the newest undo steps whose pixels come to `bytes` on the GPU (two at least), the
    /// rest packed on a device of little memory: for tests. None: as the device's room has it.
    #[doc(hidden)]
    pub fn keep_recent_history(&mut self, bytes: Option<u64>) {
        self.recent_history = bytes;
    }

    /// On a device of little memory, keeps the history's older steps' pixels compressed off the
    /// GPU, a lot at a time: read back (the copies queued now, the waiting and compressing on a
    /// thread of its own), then put in place of their textures in a later render. The newest
    /// steps stay on the GPU (see [`RECENT_HISTORY_SHARE`]), so undoing and redoing them is as
    /// quick as ever; an older one is uploaded again as it is undone (see [`Canvas::unpacked`]).
    /// Nothing new is started while an edit is under way.
    pub(crate) fn pack_history(&mut self, gpu: &Gpu) {
        if let Some(packing) = self.packing.take() {
            match packing.done.try_recv() {
                Ok(packed) => self.take_packed(packing, packed),
                Err(mpsc::TryRecvError::Empty) => {
                    self.packing = Some(packing);
                    return;
                }
                // the thread stopped: the textures stay as they are
                Err(mpsc::TryRecvError::Disconnected) => {}
            }
        }
        let busy = self.active.is_some()
            || self.held.is_some()
            || self.smudge.is_some()
            || self.transforming.is_some()
            || self.liquify.is_some();
        if !self.low_memory() || busy {
            return;
        }
        // the newest steps that fit the room kept for them stay
        let recent = self.recent_history.unwrap_or(self.history_most() / RECENT_HISTORY_SHARE);
        let mut kept = 0u64;
        let mut older = self.undo.len();
        for (n, step) in self.undo.iter().rev().enumerate() {
            if n >= RECENT_STEPS && kept + step.bytes() > recent {
                break;
            }
            kept += step.bytes();
            older -= 1;
        }
        // the oldest of the rest on the GPU, up to a lot's worth
        let mut lot: Vec<wgpu::Texture> = Vec::new();
        let mut regions = Vec::new();
        let mut bytes = 0u64;
        'steps: for step in self.undo.get(..older).unwrap_or_default() {
            for s in &step.pixels {
                let Held::Texture(texture) = &s.pixels else { continue };
                if !lot.is_empty() && (bytes + s.bytes() > PACK_AT_ONCE || lot.len() >= PACK_MOST_READS) {
                    break 'steps;
                }
                bytes += s.bytes();
                regions.push((texture.clone(), IRect::from_size(s.rect.width(), s.rect.height())));
                lot.push(texture.clone());
            }
        }
        if lot.is_empty() {
            return;
        }
        if self.packer.is_none() {
            self.packer = packer(gpu);
        }
        let (sent, done) = mpsc::channel();
        match self.packer.as_ref().map(|packer| packer.send((regions, sent))) {
            Some(Ok(())) => self.packing = Some(Packing { textures: lot, generation: self.history_generation, done }),
            // its thread gone: another made next time
            _ => self.packer = None,
        }
    }

    /// Puts what `packing` compressed in place of the textures it read, where the history still
    /// has them as they were read; the textures let go (those the canvas's size kept as spares).
    pub(crate) fn take_packed(&mut self, packing: Packing, packed: Vec<Option<Vec<u8>>>) {
        if packing.generation != self.history_generation {
            return;
        }
        let mut gone = Vec::new();
        for (texture, bytes) in packing.textures.iter().zip(packed) {
            let Some(bytes) = bytes else { continue };
            let held = self.undo.iter_mut().flat_map(|s| s.pixels.iter_mut()).find(|s| s.texture() == Some(texture));
            if let Some(s) = held
                && let Held::Texture(old) = std::mem::replace(&mut s.pixels, Held::Packed(bytes))
            {
                gone.push(old);
            }
        }
        drop(packing);
        for texture in gone {
            self.keep_spare(texture);
        }
    }

    /// Packs what there is of the history to pack now, waiting for each lot (see
    /// [`Canvas::pack_history`]): for tests and measuring.
    #[doc(hidden)]
    pub fn pack_history_now(&mut self, gpu: &Gpu) {
        for _ in 0..10_000 {
            self.pack_history(gpu);
            let Some(packing) = self.packing.take() else { return };
            match packing.done.recv() {
                Ok(packed) => self.take_packed(packing, packed),
                Err(_) => return,
            }
        }
    }

    /// `step` with any of its pixels kept compressed (see [`Canvas::pack_history`]) back in
    /// textures, uploaded in `encoder` before what is recorded after: the upload buffers go in
    /// `uploaded`, to be given back once it is sent.
    pub(crate) fn unpacked(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        mut step: Step,
        uploaded: &mut Vec<wgpu::Buffer>,
    ) -> Step {
        for s in &mut step.pixels {
            let Held::Packed(bytes) = &s.pixels else { continue };
            let (w, h) = (s.rect.width(), s.rect.height());
            let size = w as usize * h as usize * 4;
            let Some(rows) = procraft_engine::applz4::decode(bytes, size).filter(|r| r.len() == size) else {
                log::error!("an undo step's kept pixels are damaged");
                continue;
            };
            // a whole layer's into a texture the canvas's size, as it was kept, else the region's
            let texture = if s.rect == self.full() {
                self.take_spare().unwrap_or_else(|| texture(device, "undo", self.width, self.height, LAYER_FORMAT))
            } else {
                device.create_texture(&wgpu::TextureDescriptor {
                    label: Some("undo"),
                    size: extent(w, h),
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: LAYER_FORMAT,
                    usage: wgpu::TextureUsages::COPY_SRC | wgpu::TextureUsages::COPY_DST,
                    view_formats: &[],
                })
            };
            uploaded.extend(self.upload(device, encoder, &texture, IRect::from_size(w, h), &rows, 0, w as usize * 4));
            s.pixels = Held::Texture(texture);
        }
        step
    }

    /// Drops the open stroke.
    pub fn cancel_stroke(&mut self) {
        self.prediction.clear();
        self.laid = None;
        self.laid_second = None;
        self.tip = None;
        if let Some(active) = self.active.take()
            && !self.measuring
        {
            self.dirty = self.dirty.union(active.bounds);
            self.ops.push(Op::Cancel);
        }
    }

    /// Undoes the last step; a structural step puts the document back during the next render.
    pub fn undo(&mut self) {
        self.end_stroke();
        self.ops.push(Op::Undo);
    }

    pub fn redo(&mut self) {
        self.end_stroke();
        self.ops.push(Op::Redo);
    }

    pub fn clear_layer(&mut self, layer: LayerId) {
        self.end_stroke();
        self.ops.push(Op::Clear(layer, None));
    }

    /// Inverts a layer's colours within `rect` (where it is painted), as one undo step; on the
    /// GPU, nothing read back.
    pub fn invert_layer(&mut self, layer: LayerId, rect: IRect) {
        self.end_stroke();
        self.ops.push(Op::Invert(layer, rect));
    }

    /// Fills a layer all over with the opaque colour `rgb`, as one undo step; on the GPU, nothing
    /// sent.
    pub fn fill_layer(&mut self, layer: LayerId, rgb: [u8; 3]) {
        self.end_stroke();
        self.ops.push(Op::Fill(layer, rgb));
    }

    /// Clears a layer painted only within `painted` (as [`Canvas::painted_boxes`] finds), the undo
    /// step keeping only that.
    pub fn clear_layer_within(&mut self, layer: LayerId, painted: IRect) {
        self.end_stroke();
        self.ops.push(Op::Clear(layer, Some(painted)));
    }

    /// Replaces a layer's pixels (premultiplied RGBA8, the canvas's size) as one undo step; pixels
    /// of the wrong size are ignored.
    pub fn write_layer(&mut self, layer: LayerId, pixels: Vec<u8>) {
        self.write_layers(vec![(layer, pixels)]);
    }

    /// Replaces a layer's pixels with no undo step; a picture of the wrong size is left out.
    pub fn put_layer(&mut self, layer: LayerId, pixels: Vec<u8>) {
        if pixels.len() == self.width as usize * self.height as usize * 4 {
            self.ops.push(Op::Put(layer, pixels));
            self.invalidate();
        }
    }

    /// Replaces the rectangle of a layer's pixels at (`x`, `y`), `width` x `height` (`pixels` its
    /// RGBA8, row by row) with no undo step, as [`Canvas::put_layer`] does the whole of it. Left
    /// out if it isn't on the canvas or `pixels` isn't its size. Only that rectangle composites
    /// again, around the pictures of the rest when the layer is the one worked on (text typed, a
    /// fill dragged).
    pub fn put_layer_region(&mut self, layer: LayerId, x: u32, y: u32, width: u32, height: u32, pixels: Vec<u8>) {
        let on = width > 0
            && height > 0
            && x.checked_add(width).is_some_and(|r| r <= self.width)
            && y.checked_add(height).is_some_and(|b| b <= self.height);
        if on && pixels.len() == width as usize * height as usize * 4 {
            self.ops.push(Op::PutRegion { layer, x, y, width, height, pixels });
        }
    }

    /// Sets a layer's pixels aside, for an edit made of many changes (with [`Canvas::put_layer`])
    /// that ends with [`Canvas::release_layer`].
    pub fn hold_layer(&mut self, layer: LayerId) {
        self.end_stroke();
        self.ops.push(Op::Hold(layer));
    }

    /// Sets a layer's pixels aside as [`Canvas::hold_layer`] does, for an edit made of strokes and
    /// clearing (laying a layer's strokes again, say): until [`Canvas::release_layer`], what changes
    /// it makes no undo step of its own, so the edit keeps one copy of the layer, not one for each.
    pub fn hold_layer_quietly(&mut self, layer: LayerId) {
        self.hold_layer(layer);
        self.ops.push(Op::HoldQuietly);
    }

    /// Ends a held edit: `keep` makes it one undo step back to the held pixels and `before` (the
    /// document as the edit began); otherwise the held pixels are put back.
    pub fn release_layer(&mut self, keep: bool, before: Option<Document>) {
        self.ops.push(Op::Release { keep, before: before.map(Box::new) });
        self.invalidate();
    }

    /// Keeps the composite inside `rect` as it is now (copied at once, before any work still
    /// queued), to show over the canvas but for a hole given each frame ([`Canvas::veil_hole`]),
    /// until [`Canvas::veil_end`]: a change queued after this then shows only through the hole.
    /// Kept at `scale` target pixels to a canvas pixel where that is under one (box-filtered), so
    /// a large canvas zoomed out keeps no more than the screen shows. A veil already kept goes;
    /// nothing is kept for a rectangle off the canvas.
    pub fn veil_begin(&mut self, gpu: &Gpu, rect: IRect, scale: f32) {
        self.veil = None;
        let rect = rect.intersect(self.full());
        if rect.is_empty() {
            return;
        }
        let device = &gpu.device;
        let scale = if scale.is_finite() { scale.clamp(1e-3, 1.0) } else { 1.0 };
        let (rw, rh) = (rect.width(), rect.height());
        let fit = |side: u32| ((side as f32 * scale).ceil() as u32).clamp(1, side);
        let (w, h) = (fit(rw), fit(rh));
        let texture = texture(device, "veil", w, h, LAYER_FORMAT);
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("veil") });
        if (w, h) == (rw, rh) {
            encoder.copy_texture_to_texture(
                copy_at(&self.composite, rect.x0, rect.y0),
                copy_at(&texture, 0, 0),
                extent(rw, rh),
            );
        } else {
            // box-filtered down, as a thumbnail is
            let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
            let bind = sampled_bind(device, &self.pipelines.sampled_layout, &self.composite_view, &self.sampler);
            let mut data = UniformData::default();
            let (cw, ch) = (self.width.max(1) as f32, self.height.max(1) as f32);
            let (fw, fh) = (rw as f32, rh as f32);
            let offset = data.push(&DisplayUniform::new(
                [w as f32, h as f32, fw / w as f32, fh / h as f32],
                [rect.x0 as f32 / cw, rect.y0 as f32 / ch, fw / cw, fh / ch],
                None,
                false,
            ));
            {
                let mut pass = render_pass(&mut encoder, "veil", &view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
                pass.set_pipeline(&self.pipelines.thumb);
                pass.set_bind_group(0, &bind, &[]);
                pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                pass.draw(0..3, 0..1);
            }
            self.write_uniforms(gpu, &data);
        }
        gpu.queue.submit([encoder.finish()]);
        let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
        let crisp_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("veil crisp"),
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let layout = &self.pipelines.sampled_layout;
        let bind = sampled_bind(device, layout, &view, &self.sampler);
        let crisp = sampled_bind(device, layout, &view, &crisp_sampler);
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("veil"),
            size: size_of::<VeilUniform>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let uniform_bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("veil"),
            layout: &self.pipelines.veil_layout,
            entries: &[wgpu::BindGroupEntry { binding: 0, resource: uniform.as_entire_binding() }],
        });
        self.veil = Some(Veil { rect, _texture: texture, bind, crisp, uniform, uniform_bind, hole: Vec::new() });
    }

    /// The veil's hole from now on: a closed outline in canvas pixels, its first
    /// [`VEIL_POINTS`] points (fewer than three: no hole). Where the hole is the canvas shows as
    /// it is now.
    pub fn veil_hole(&mut self, hole: &[[f32; 2]]) {
        if let Some(veil) = self.veil.as_mut() {
            veil.hole.clear();
            veil.hole.extend(hole.iter().take(VEIL_POINTS).filter(|p| p[0].is_finite() && p[1].is_finite()));
        }
    }

    /// The veil goes: the canvas shows as it is.
    pub fn veil_end(&mut self) {
        self.veil = None;
    }

    /// The rectangle a veil is kept for, while there is one.
    pub fn veil_rect(&self) -> Option<IRect> {
        self.veil.as_ref().map(|v| v.rect)
    }

    /// Replaces a layer's pixels inside `rect` with `part` (premultiplied RGBA8, the rectangle's
    /// rows) as one undo step keeping only that part. Left out if `rect` isn't wholly on the
    /// canvas or `part` isn't its size.
    pub fn write_layer_part(&mut self, layer: LayerId, rect: IRect, part: Vec<u8>) {
        let fits = !rect.is_empty() && rect.intersect(self.full()) == rect;
        if !fits || part.len() != rect.width() as usize * rect.height() as usize * 4 {
            return;
        }
        self.end_stroke();
        self.ops.push(Op::Write { layers: vec![(layer, rect, part)], before: None });
    }

    /// Mirrors `layers`, left to right (`horizontal`) or top to bottom, as one undo step; on the
    /// GPU, nothing read back.
    pub fn flip_layers(&mut self, layers: Vec<LayerId>, horizontal: bool) {
        self.end_stroke();
        self.ops.push(Op::Flip { layers, horizontal, before: None });
    }

    /// [`Canvas::flip_layers`], changing the document together: one undo step back to the layers
    /// as they were and to `before` (the document as it was).
    pub fn flip_layers_and_document(&mut self, layers: Vec<LayerId>, horizontal: bool, before: Document) {
        self.end_stroke();
        self.ops.push(Op::Flip { layers, horizontal, before: Some(Box::new(before)) });
        self.invalidate();
    }

    /// Replaces several layers' pixels at once, as one undo step; pictures of the wrong size are
    /// left out.
    pub fn write_layers(&mut self, layers: Vec<(LayerId, Vec<u8>)>) {
        let (size, full) = (self.width as usize * self.height as usize * 4, self.full());
        let layers: Vec<(LayerId, IRect, Vec<u8>)> =
            layers.into_iter().filter(|(_, p)| p.len() == size).map(|(id, p)| (id, full, p)).collect();
        if !layers.is_empty() {
            self.end_stroke();
            self.ops.push(Op::Write { layers, before: None });
        }
    }

    /// Replaces a rectangle of several layers' pixels (premultiplied RGBA8, the rectangle's rows),
    /// the rest of each as it was, and changes the document together, as one undo step back to
    /// the pixels and to `before` (the document as it was); parts that don't fit their rectangle,
    /// or the canvas, are left out.
    pub fn write_layer_parts_and_document(&mut self, layers: Vec<(LayerId, IRect, Vec<u8>)>, before: Document) {
        let full = self.full();
        let fits = |rect: &IRect, part: &[u8]| {
            !rect.is_empty()
                && rect.intersect(full) == *rect
                && part.len() == rect.width() as usize * rect.height() as usize * 4
        };
        let layers: Vec<(LayerId, IRect, Vec<u8>)> = layers.into_iter().filter(|(_, r, p)| fits(r, p)).collect();
        self.end_stroke();
        self.ops.push(Op::Write { layers, before: Some(Box::new(before)) });
        self.invalidate();
    }

    /// Clears a layer without recording it (for scratch canvases such as brush previews).
    pub fn wipe_layer(&mut self, layer: LayerId) {
        self.end_stroke();
        self.ops.push(Op::Wipe(layer));
    }

    /// Drops the undo history (scratch canvases).
    pub fn forget_history(&mut self) {
        self.undo.clear();
        self.redo.clear();
    }

    /// Drops the steps (to undo and to redo) that change the document's layers, keeping those
    /// that only put pixels back: the document now follows another's layers (a model's channels
    /// share theirs), which its own such steps would undo and redo out of step with.
    pub fn forget_document_steps(&mut self) {
        self.undo.retain(|s| s.doc.is_none());
        self.redo.retain(|s| s.doc.is_none());
    }

    /// An undo step that changes nothing here, named `name`: a change kept outside the canvas
    /// (which of a model's channels is painted), which its owner follows as the step is undone
    /// and redone.
    pub fn record_outside_change(&mut self, name: &str) {
        self.end_stroke();
        self.ops.push(Op::OutsideChange(name.to_owned()));
    }

    /// The document was changed (layers added, removed, reordered, their settings...): `before`
    /// is how it was, for undo.
    pub fn record_document(&mut self, before: Document) {
        self.end_stroke();
        self.ops.push(Op::Record(Box::new(before)));
        self.invalidate();
    }

    /// `to` (already in the document) was made as a copy of `from`; `before` is the document
    /// without it.
    pub fn duplicate_layer(&mut self, from: LayerId, to: LayerId, before: Document) {
        self.duplicate_layers(vec![(from, to)], before);
    }

    /// Layers (already in the document) were made as copies, each pair (from, to), as one undo
    /// step; `before` is the document without them.
    pub fn duplicate_layers(&mut self, pairs: Vec<(LayerId, LayerId)>, before: Document) {
        self.end_stroke();
        self.ops.push(Op::Duplicate { pairs, before: Box::new(before) });
        self.invalidate();
    }

    /// Blends `upper` into `lower`; the caller removes `upper` from the document once this is
    /// queued (`before` is the document with it). `within`: where `upper` is painted, if known
    /// (the undo step keeps only that much of `lower`).
    pub fn merge_down(&mut self, upper: LayerId, lower: LayerId, before: Document, within: Option<IRect>) {
        self.merge_down_with_masks(upper, lower, Vec::new(), false, before, within);
    }

    /// [`Canvas::merge_down`], each of `masks` (`upper` or `lower`, and its mask) applied to its
    /// layer first in the same undo step, and with `drop_lower` (it is hidden: only what shows is
    /// kept) `lower`'s own pixels let go first; the caller removes the masks with `upper`.
    pub fn merge_down_with_masks(
        &mut self,
        upper: LayerId,
        lower: LayerId,
        masks: Vec<(LayerId, LayerId)>,
        drop_lower: bool,
        before: Document,
        within: Option<IRect>,
    ) {
        self.end_stroke();
        self.ops.push(Op::MergeDown { upper, lower, before: Box::new(before), within, masks, drop_lower });
        self.invalidate();
    }

    /// Layer `layer` takes on its mask `mask` (shown as much as the mask is light) as one undo
    /// step; the caller removes the mask once this is queued (`before` is the document with it).
    pub fn apply_mask(&mut self, layer: LayerId, mask: LayerId, before: Document) {
        self.end_stroke();
        self.ops.push(Op::ApplyMask { layer, mask, before: Box::new(before) });
        self.invalidate();
    }

    /// Blends `uppers` (bottom first: layers, or groups composited first) into `lower` in turn as
    /// one undo step; the caller removes them from the document once this is queued (`before` is
    /// the document with them).
    /// `within`: where they are painted, if known (the undo step keeps only that much of `lower`).
    pub fn merge_layers(&mut self, uppers: Vec<LayerId>, lower: LayerId, before: Document, within: Option<IRect>) {
        self.merge_layers_with_masks(uppers, lower, Vec::new(), false, before, within);
    }

    /// [`Canvas::merge_layers`], each of `masks` (`lower` or one of `uppers`, and its mask)
    /// applied to its layer first in the same undo step, and with `drop_lower` (it is hidden)
    /// `lower`'s own pixels let go first; the caller removes the masks too.
    pub fn merge_layers_with_masks(
        &mut self,
        uppers: Vec<LayerId>,
        lower: LayerId,
        masks: Vec<(LayerId, LayerId)>,
        drop_lower: bool,
        before: Document,
        within: Option<IRect>,
    ) {
        self.end_stroke();
        self.ops.push(Op::MergeMany { uppers, lower, before: Box::new(before), within, masks, drop_lower });
        self.invalidate();
    }

    /// Draws group `group` into `into`, a new layer already in the document. `before` is the
    /// document with the group; the caller changes the document once this is queued.
    pub fn flatten_group(&mut self, group: LayerId, into: LayerId, before: Document) {
        self.end_stroke();
        self.ops.push(Op::Flatten { group, into, before: Box::new(before) });
        self.invalidate();
    }

    /// Recomposite everything next frame (after a change to visibility, opacity, order...).
    /// Whether the composite is made around pictures of the top level's items that aren't being
    /// worked on, kept until something changes them, so that only the items worked on composite
    /// each time (and the others' layers needn't be at hand): for artworks of more layers than
    /// fit at once. Off, every layer composites every time.
    pub fn set_cache_composites(&mut self, on: bool) {
        self.cache_composites = on;
        self.let_go_caches(true);
        if !on {
            self.spare_caches = None;
        }
    }

    /// Lets the caches go (and any remake of them, with `remake`), their textures kept for the
    /// next.
    pub(crate) fn let_go_caches(&mut self, remake: bool) {
        let remade = if remake { self.remake.take().map(|r| r.caches) } else { None };
        if let Some(c) = self.caches.take().or(remade) {
            self.spare_caches = Some(c);
        }
    }

    /// Whether the canvas is still remaking its caches (a little each render): it wants another
    /// render soon, and the composite shows as it was until then.
    pub fn busy(&self) -> bool {
        self.remake.is_some()
    }

    /// Renders until the caches are remade, so the composite is the picture as it is (to read it
    /// back: a save's, an export's).
    pub fn render_finished(&mut self, gpu: &Gpu, doc: &mut Document) {
        self.render(gpu, doc, None);
        // each render remakes at least one item, so this ends well before the bound
        for _ in 0..100_000 {
            if !self.busy() {
                break;
            }
            self.render(gpu, doc, None);
        }
    }

    /// Whether the canvas has work that more renders do (caches being remade, layers being
    /// parked, the history's pixels being packed): it wants another render soon.
    pub fn wants_render(&self) -> bool {
        self.busy() || self.parking_left || self.packing.is_some() || self.relaying || self.catching_up
    }

    /// How the last remake of the caches went (none made yet).
    pub fn last_remake(&self) -> Option<RemakeStats> {
        self.last_remake
    }

    /// Whether the composite is being made around pictures of what isn't worked on.
    pub fn composites_cached(&self) -> bool {
        self.caches.is_some()
    }

    /// Whether a smudge stroke couldn't be begun, or couldn't go on, since this was last asked (what
    /// it takes couldn't be made on the GPU: past its memory), once.
    pub fn take_smudge_refused(&mut self) -> bool {
        std::mem::take(&mut self.smudge_untold)
    }

    /// What the layers' textures and the history may take, in bytes: past it, the layers needed
    /// least lately that aren't being worked on are parked (kept compressed off the GPU, and the
    /// composite made around pictures of them) until needed again. 0 keeps every layer on the GPU.
    pub fn set_resident_budget(&mut self, bytes: u64) {
        self.resident_budget = bytes;
        self.set_cache_composites(bytes > 0);
    }

    /// What was set with [`Canvas::set_resident_budget`].
    pub fn resident_budget(&self) -> u64 {
        self.resident_budget
    }

    /// A parked layer's small pictures, if they are big enough for a thumbnail `max_side` across.
    pub(crate) fn parked_pictures(&self, id: LayerId, max_side: u32) -> Option<&Pictures> {
        if self.layers.contains_key(&id) || max_side > PARKED_PICTURE_SIDE {
            return None;
        }
        self.pictures.get(&id)
    }

    /// The layers whose pixels changed since this was last done (by painting, undo, uploads and
    /// every other change), forgotten here: a save reads only these.
    pub fn take_changed(&mut self) -> HashSet<LayerId> {
        std::mem::take(&mut self.changed)
    }

    /// Layer `id`'s pixels are changing: counted for the next save, and given a new version.
    pub(crate) fn touched(&mut self, id: LayerId) {
        self.changed.insert(id);
        self.version += 1;
        self.versions.insert(id, self.version);
    }

    /// Layer `id`'s pixels' version as of the last render: it is new whenever they may have
    /// changed (painting, undo, uploads, every other change), so a thumbnail of the same version
    /// is still right.
    pub fn layer_version(&self, id: LayerId) -> u64 {
        self.versions.get(&id).copied().unwrap_or(0)
    }

    /// Layers to count as changed again (a save of them that failed).
    pub fn mark_changed(&mut self, ids: impl IntoIterator<Item = LayerId>) {
        self.changed.extend(ids);
    }

    /// How many layers are parked (kept compressed, without a texture).
    pub fn parked_layers(&self) -> usize {
        self.parked.keys().filter(|id| !self.layers.contains_key(*id)).count()
    }

    /// Gives layer `id` its texture again from its compressed pixels, if it is parked; whether
    /// it has one now. Its pixels go up before any work recorded in this render.
    pub(crate) fn restore(&mut self, gpu: &Gpu, id: LayerId) -> bool {
        if self.layers.contains_key(&id) {
            return true;
        }
        let Some((painted, rows)) = self.parked.get(&id).and_then(|p| Some((p.rect, p.rows()?))) else {
            return false;
        };
        let texture = texture(&gpu.device, "layer", self.width, self.height, LAYER_FORMAT);
        // only where it is painted (a new texture is clear): nothing at all for an empty layer
        if let Some(b) = painted {
            self.upload_now(gpu, &texture, b, &rows, 0, b.width() as usize * 4);
        }
        let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
        self.layers.insert(id, LayerGpu { texture, view });
        true
    }

    /// Where an item's layers are painted, as far as is known (the whole canvas for a layer
    /// with a texture of its own; empty if every one is parked and empty).
    pub(crate) fn painted(&self, layers: &[LayerId]) -> IRect {
        layers.iter().fold(IRect::EMPTY, |r, id| {
            match self.pictures.get(id).filter(|_| !self.layers.contains_key(id)) {
                Some(p) => p.bounds.map_or(r, |b| r.union(b)),
                None => self.full(),
            }
        })
    }

    /// Every layer in `ids` given its texture again; those about to be written lose their
    /// compressed copies.
    pub(crate) fn restore_all(&mut self, gpu: &Gpu, ids: impl IntoIterator<Item = LayerId>, written: bool) {
        for (n, id) in ids.into_iter().enumerate() {
            let restored = !self.layers.contains_key(&id) && self.restore(gpu, id);
            if written {
                self.parked.remove(&id);
                self.pictures.remove(&id);
                self.touched(id);
            }
            self.needed_at.insert(id, self.renders);
            // the staged pixels sent on now and then, so many in a row don't pile up
            if restored && n % 8 == 7 {
                gpu.queue.submit(std::iter::empty());
            }
        }
    }

    /// The layers the queued work reads and those it writes (which need their textures and lose
    /// their compressed copies).
    pub(crate) fn ops_layers(&self) -> (Vec<LayerId>, Vec<LayerId>) {
        let (mut read, mut written) = (Vec::new(), Vec::new());
        let contents = |d: &Document, id: LayerId| -> Vec<LayerId> {
            if d.layer(id).is_some_and(|l| l.kind == LayerKind::Group) { d.contents(id) } else { vec![id] }
        };
        for op in &self.ops {
            match op {
                Op::Commit(s) => written.push(s.layer),
                Op::Clear(id, _)
                | Op::Invert(id, _)
                | Op::Fill(id, _)
                | Op::Wipe(id)
                | Op::TransformBegin(id)
                | Op::TransformAlso(id)
                | Op::Put(id, _)
                | Op::PutRegion { layer: id, .. }
                | Op::RedrawFrom { layer: id, .. }
                | Op::Over { layer: id, .. }
                | Op::Hold(id) => written.push(*id),
                Op::Write { layers, .. } => written.extend(layers.iter().map(|(id, ..)| *id)),
                Op::Flip { layers, .. } => written.extend(self.with_planes(layers)),
                Op::Duplicate { pairs, .. } => {
                    read.extend(pairs.iter().map(|(from, _)| *from));
                    written.extend(pairs.iter().map(|(_, to)| *to));
                }
                Op::MergeDown { upper, lower, before, masks, .. } => {
                    read.extend(contents(before, *upper));
                    read.extend(masks.iter().map(|(_, m)| *m));
                    written.extend(masks.iter().map(|(l, _)| *l));
                    written.push(*lower);
                }
                Op::MergeMany { uppers, lower, before, masks, .. } => {
                    read.extend(uppers.iter().flat_map(|u| contents(before, *u)));
                    read.extend(masks.iter().map(|(_, m)| *m));
                    written.extend(masks.iter().map(|(l, _)| *l));
                    written.push(*lower);
                }
                Op::ApplyMask { layer, mask, .. } => {
                    read.extend([*layer, *mask]);
                    written.push(*layer);
                }
                Op::Flatten { group, into, before, .. } => {
                    read.extend(before.contents(*group));
                    written.push(*into);
                }
                Op::Release { .. } => written.extend(self.held.as_ref().map(|h| h.layer)),
                _ => {}
            }
        }
        written.extend(self.active.map(|a| a.layer));
        // a stroke into a store and a transform (as far as it moves them) write its planes too
        let moved = self.ops.iter().filter_map(|op| match op {
            Op::TransformBegin(id) | Op::TransformAlso(id) => Some(*id),
            _ => None,
        });
        let planes: Vec<LayerId> = self.active.map(|a| a.layer).into_iter().chain(moved).collect();
        written.extend(self.with_planes(&planes).into_iter().skip(planes.len()));
        (read, written)
    }

    /// Parks the layers needed least lately until the textures fit the budget, keeping those
    /// worked on (and what they are clipped to); none while the composite isn't made around
    /// pictures of the others.
    pub(crate) fn park_extra(&mut self, gpu: &Gpu, doc: &Document) {
        self.parking_left = false;
        let most = self.most_resident();
        if self.resident_budget == 0 || self.layers.len() <= most {
            return;
        }
        let mut keep = match (&self.caches, self.shown_frames(doc)) {
            (Some(c), _) => {
                let mut keep = c.live_layers.clone();
                keep.extend(c.live.first().into_iter().flat_map(|first| clip_base_and_mask(doc, *first)));
                keep
            }
            // the frames shown, while Animation Assist is on
            (None, Some(shown)) => shown,
            (None, None) => return,
        };
        keep.extend(self.active.map(|a| a.layer));
        keep.extend(self.active.and_then(|a| self.stores.get(&a.layer)).into_iter().flat_map(|s| s.planes.clone()));
        keep.extend(self.held.as_ref().map(|h| h.layer));
        let mut gone: Vec<LayerId> = self.layers.keys().filter(|id| !keep.contains(*id)).copied().collect();
        gone.sort_by_key(|id| self.needed_at.get(id).copied().unwrap_or(0));
        // a layer with no compressed copy yet is read back first, the slow part: a few a render
        // (at least one), the rest left for the renders after
        let started = std::time::Instant::now();
        let mut reads = 0;
        for id in gone {
            if self.layers.len() <= most {
                break;
            }
            if !self.parked.contains_key(&id) {
                if reads > 0 && (reads >= MOST_PARK_READS || started.elapsed() >= PARK_SLICE) {
                    self.parking_left = true;
                    break;
                }
                reads += 1;
                let Ok(pixels) = self.read_layer(gpu, id) else { continue };
                let pictures = Pictures::of(&pixels, self.width, self.height);
                self.parked.insert(id, Parked::of(&pixels, self.width, pictures.bounds));
                self.pictures.insert(id, pictures);
            }
            self.layers.remove(&id);
        }
    }

    pub fn invalidate(&mut self) {
        self.let_go_caches(true);
        self.dirty = self.full();
    }

    /// The paper every stroke takes from now on, and the background shows (see [`CanvasPaper`]);
    /// none: none, every stroke laid as it would be without one. A tile not `TEXTURE_SIZE` square
    /// is none.
    pub fn set_paper(&mut self, gpu: &Gpu, paper: Option<&CanvasPaper>) {
        let paper = paper.filter(|p| p.tile.len() == TEXTURE_SIZE * TEXTURE_SIZE);
        if self.paper.as_ref() == paper {
            return;
        }
        if let Some(p) = paper
            && self.paper.as_ref().is_none_or(|was| was.tile != p.tile)
        {
            write_image_levels(gpu, &self.paper_texture, 0, TEXTURE_SIZE as u32, &p.tile);
        }
        self.paper = paper.cloned();
        // the background and what strokes are shown with, again
        self.invalidate();
    }

    /// The paper as a stroke's and the background's uniform take it ([`LayerUniform::paper`]).
    pub(crate) fn paper_look(&self) -> [f32; 4] {
        match &self.paper {
            Some(p) => [p.strength.clamp(0.0, 1.0), p.scale.clamp(0.25, 16.0), p.depth.clamp(0.0, 1.0), 0.0],
            None => [0.0, 1.0, 0.0, 0.0],
        }
    }

    /// What draws the background: its colour (the paper's, if it has one) where it shows, with
    /// the paper's depth.
    pub(crate) fn background_uniform(&self, doc: &Document) -> LayerUniform {
        let [r, g, b] = self.paper.as_ref().and_then(|p| p.colour).unwrap_or(doc.background);
        let solid = if doc.background_visible { [r, g, b, 1.0] } else { [0.0; 4] };
        let scaled = self.scaled.map_or([1.0, 0.0, 0.0, 0.0], |([x, y], k)| [k, x, y, 0.0]);
        LayerUniform { opacity: 1.0, solid, scaled, paper: self.paper_look(), ..Default::default() }
    }

    /// The frame Animation Assist is playing, if it is.
    pub fn play_frame(&self) -> Option<usize> {
        self.play_frame
    }

    /// Animation Assist playing: frame `frame` shows alone (none: back to drawing).
    pub fn set_play_frame(&mut self, frame: Option<usize>) {
        if self.play_frame != frame {
            self.play_frame = frame;
            self.invalidate();
        }
    }

    /// Sets the selection: one byte of coverage per canvas pixel, rows top to bottom; none (or a
    /// buffer of the wrong size) selects everything. Painting and erasing keep inside it.
    pub fn set_selection(&mut self, gpu: &Gpu, coverage: Option<&[u8]>) {
        self.put_selection(&gpu.device, &gpu.queue, coverage.map(std::borrow::Cow::Borrowed));
    }

    /// Sets the selection as [`Self::set_selection`] does, as one undo step that puts `before` back
    /// (the coverage it had, or none). Runs what is queued first, so the step falls in order.
    pub fn change_selection(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        coverage: Option<&[u8]>,
        before: Option<Vec<u8>>,
    ) {
        self.render(gpu, doc, None);
        self.push_step(Step {
            made: 0,
            name: None,
            doc: None,
            pixels: Vec::new(),
            selection: Some(before),
            flip: None,
            stores: Vec::new(),
        });
        self.set_selection(gpu, coverage);
    }

    /// Sets the selection as [`Self::change_selection`] does, but as part of step `made` (as
    /// [`Self::undo_step`] numbers it): undoing that step then puts `before` back too, and redoing
    /// it changes the selection again. Runs what is queued first; false, changing nothing, if step
    /// `made` isn't then the last or already changed the selection.
    pub fn change_selection_with_step(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        coverage: Option<&[u8]>,
        before: Option<Vec<u8>>,
        made: u64,
    ) -> bool {
        self.render(gpu, doc, None);
        match self.undo.last_mut() {
            Some(step) if step.made == made && step.selection.is_none() => {
                step.selection = Some(before);
                self.set_selection(gpu, coverage);
                true
            }
            _ => false,
        }
    }

    /// Layer `layer`'s pixels as they are on the GPU, if it has any there.
    pub fn layer_view(&self, layer: LayerId) -> Option<&wgpu::TextureView> {
        self.layers.get(&layer).map(|l| &l.view)
    }

    /// Replaces layer `layer`'s pixels with `source` (premultiplied, the layer's format and the
    /// canvas's size), on the GPU, with no undo step. False if the layer has none there yet.
    pub fn copy_to_layer(&mut self, gpu: &Gpu, layer: LayerId, source: &wgpu::Texture) -> bool {
        let Some(target) = self.layers.get(&layer) else { return false };
        let mut encoder =
            gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("to layer") });
        encoder.copy_texture_to_texture(
            source.as_image_copy(),
            target.texture.as_image_copy(),
            wgpu::Extent3d { width: self.width, height: self.height, depth_or_array_layers: 1 },
        );
        gpu.queue.submit([encoder.finish()]);
        self.invalidate();
        true
    }

    /// The selection's texture (coverage in its red), or none when nothing is selected.
    pub fn selection_view(&self) -> Option<&wgpu::TextureView> {
        self.selection.as_ref().map(|s| &s.view)
    }

    /// The selection's coverage, one byte a canvas pixel, or none when nothing is selected.
    pub fn selection(&self) -> Option<&[u8]> {
        self.selection.as_ref().map(|s| s.coverage.as_slice())
    }

    pub(crate) fn put_selection(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        coverage: Option<std::borrow::Cow<'_, [u8]>>,
    ) {
        let size = self.width as usize * self.height as usize;
        let coverage = coverage.filter(|c| c.len() == size);
        // a selection already shown takes the new coverage in its own texture (a slider drags it
        // a frame at a time), its bindings as they are; only the rows that changed are sent
        if let (Some(coverage), Some(shown)) = (&coverage, self.selection.as_mut()) {
            let row = (self.width as usize).max(1);
            let differs = |(a, b): (&[u8], &[u8])| a != b;
            let rows = || shown.coverage.chunks(row).zip(coverage.chunks(row));
            let first = rows().position(differs);
            let last = rows().rposition(differs);
            if let (Some(first), Some(last)) = (first, last) {
                let changed = coverage.get(first * row..(last + 1) * row).unwrap_or_default();
                // what it selected before in those rows, and what it selects now
                let before = shown.coverage.get(first * row..(last + 1) * row).unwrap_or_default();
                let rows =
                    coverage_bounds(changed, self.width, first).union(coverage_bounds(before, self.width, first));
                shown.bounds = shown.bounds.union(rows);
                // a fill shown over what is selected follows it
                if self.fill_preview.is_some() {
                    self.dirty = self.dirty.union(rows);
                }
                queue.write_texture(
                    copy_at(&shown.texture, 0, i32::try_from(first).unwrap_or(0)),
                    changed,
                    wgpu::TexelCopyBufferLayout {
                        offset: 0,
                        bytes_per_row: Some(self.width),
                        rows_per_image: Some(u32::try_from(last + 1 - first).unwrap_or(0)),
                    },
                    extent(self.width, u32::try_from(last + 1 - first).unwrap_or(0)),
                );
                if let Some(kept) = shown.coverage.get_mut(first * row..(last + 1) * row) {
                    kept.copy_from_slice(changed);
                }
            }
            return;
        }
        let width = self.width;
        let selection = coverage.map(|coverage| {
            let texture = coverage_texture(device, queue, self.width, self.height, &coverage);
            let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
            let bind = sampled_bind(device, &self.pipelines.sampled_layout, &view, &self.sampler);
            let bounds = coverage_bounds(&coverage, width, 0);
            SelectionGpu { coverage: coverage.into_owned(), texture, view, bind, bounds }
        });
        // a fill shown over what was selected goes from there, and shows over what is now
        if self.fill_preview.is_some() {
            for s in self.selection.iter().chain(selection.iter()) {
                self.dirty = self.dirty.union(s.bounds);
            }
        }
        self.selection = selection;
        let mask = self.selection.as_ref().map_or(&self.everything, |s| &s.view);
        self.brush_bind = brush_bind(
            device,
            &self.pipelines.brush_layout,
            [&self.shapes_view, &self.grains_view, mask],
            [&self.shape_sampler, &self.grain_sampler],
        );
    }

    /// The 256 entries (RGBA8, 1024 bytes) the curves and gradient map adjustments read: for curves
    /// the red, green and blue curves and the curve for all three; for a gradient map its colours
    /// from dark to light. Other lengths are ignored.
    pub fn set_table(&mut self, gpu: &Gpu, entries: &[u8]) {
        if entries.len() == 1024 {
            gpu.queue.write_texture(
                copy_at(&self.table, 0, 0),
                entries,
                wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(1024), rows_per_image: Some(1) },
                extent(256, 1),
            );
        }
    }

    pub fn has_selection(&self) -> bool {
        self.selection.is_some()
    }

    /// Shows `preview`'s colour filling what is selected on its layer, in that layer's place
    /// among the others, from the next render on (none: the layers as they are). The layer's
    /// pixels don't change. Shown only while something is selected; follows the selection as it
    /// changes.
    pub fn set_fill_preview(&mut self, preview: Option<FillPreview>) {
        if self.fill_preview == preview {
            return;
        }
        // composited again where it showed and will show
        if let Some(selection) = self.selection.as_ref() {
            self.dirty = self.dirty.union(selection.bounds);
        }
        self.fill_preview = preview;
    }

    /// The fill shown while something is selected (see [`Canvas::set_fill_preview`]).
    pub fn fill_preview(&self) -> Option<FillPreview> {
        self.fill_preview
    }

    /// Copies `layer` aside to transform it (ending a transform still open, kept).
    pub fn begin_transform(&mut self, layer: LayerId) {
        self.end_stroke();
        if self.is_transforming() {
            self.end_transform(true);
        }
        self.ops.push(Op::TransformBegin(layer));
    }

    /// Starts transforming `layers` together (the first as [`Self::begin_transform`] does).
    pub fn begin_transform_layers(&mut self, layers: &[LayerId]) {
        let Some((first, rest)) = layers.split_first() else { return };
        self.begin_transform(*first);
        self.ops.extend(rest.iter().map(|id| Op::TransformAlso(*id)));
    }

    /// Redraws the layer being transformed from its copy: `to_source` maps layer pixel
    /// coordinates to copy pixel coordinates (3 × 3, row-major).
    pub fn set_transform(&mut self, to_source: [f32; 9], resample: Resample) {
        if to_source.iter().all(|v| v.is_finite()) {
            self.replace_placement(Op::TransformSet { to_source, resample });
        }
    }

    /// Redraws the layer being transformed from its copy as a warp: `vertices` are triangles, three
    /// corners each of (layer x, y, copy x, y) in pixels.
    pub fn set_transform_mesh(&mut self, vertices: &[[f32; 4]], resample: Resample) {
        if vertices.iter().flatten().all(|v| v.is_finite()) {
            self.replace_placement(Op::TransformMesh { vertices: vertices.to_vec(), resample });
        }
    }

    /// Starts liquifying the layer being edited (begun with [`Self::begin_transform`]): a field of
    /// no offsets, which [`Self::liquify_stamps`] draws into and [`Filter::Liquify`] shows.
    pub fn begin_liquify(&mut self) {
        self.ops.push(Op::LiquifyBegin);
    }

    /// Layer `layer` is edited along with the one begun with [`Self::begin_transform`]: liquify
    /// pushes it the same way (a layer's mask), and the edit's end keeps or puts back both.
    pub fn transform_also(&mut self, layer: LayerId) {
        self.ops.push(Op::TransformAlso(layer));
    }

    /// The adjustment being edited shows only where it is painted on from now on: strokes begun with
    /// [`Self::begin_pencil_stroke`] paint where (within the selection).
    pub fn begin_pencil(&mut self) {
        self.ops.push(Op::PencilBegin);
    }

    /// Whether an adjustment is being painted on, counting queued work.
    pub fn is_pencil(&self) -> bool {
        let mut on = self.pencil.is_some();
        for op in &self.ops {
            match op {
                Op::PencilBegin => on = true,
                Op::TransformEnd { .. } => on = false,
                _ => {}
            }
        }
        on
    }

    /// A stroke that paints the adjustment on (see [`Self::begin_pencil`]); stamps are added as for
    /// any stroke and [`Self::end_stroke`] ends it.
    pub fn begin_pencil_stroke(&mut self, layer: LayerId, style: StrokeStyle) {
        self.end_stroke();
        self.prediction.clear();
        (self.laid_count, self.laid_second_count) = (0, 0);
        self.active = Some(ActiveStroke::new(layer, style, true, None));
    }

    /// A step of the liquify brush: `stamps` added up, then the field carried along by them.
    pub fn liquify_stamps(&mut self, stamps: &[LiquifyStamp]) {
        if !stamps.is_empty() {
            self.ops.push(Op::LiquifyStamps(stamps.to_vec()));
        }
    }

    /// The liquify field kept at `amount` of itself.
    pub fn liquify_scale(&mut self, amount: f32) {
        self.ops.push(Op::LiquifyScale(amount));
    }

    /// The liquify field back to no offsets.
    pub fn liquify_clear(&mut self) {
        self.ops.push(Op::LiquifyClear);
    }

    /// Closes the liquify field's open step: what changed since the last is kept to undo (a step
    /// that changed nothing too), and nothing is left to redo. The oldest steps go once they hold
    /// more than the history's share.
    pub fn liquify_mark(&mut self) {
        self.ops.push(Op::LiquifyMark);
    }

    /// Takes the liquify field's open step back: the field as its last closed step left it (see
    /// [`Self::liquify_mark`]), its steps to undo and redo as they were.
    pub fn liquify_take_back(&mut self) {
        self.ops.push(Op::LiquifyTakeBack);
    }

    /// The liquify field as it was before its last closed step (see [`Self::liquify_mark`]), or
    /// as the last one undone left it. The caller closes a step still open first.
    pub fn liquify_undo(&mut self) {
        self.ops.push(Op::LiquifyUndo);
    }

    pub fn liquify_redo(&mut self) {
        self.ops.push(Op::LiquifyRedo);
    }

    /// How many closed steps the liquify field can undo and redo, as the work run so far leaves it
    /// (the oldest go when they hold too much).
    pub fn liquify_steps(&self) -> (usize, usize) {
        self.liquify.as_ref().map_or((0, 0), |f| (f.undo.len(), f.redo.len()))
    }

    /// Sets the liquify field aside with its steps, before the edit it is in ends: the next edit
    /// begun can take it up again ([`Self::resume_liquify`]); a new field lets it go.
    pub fn keep_liquify(&mut self) {
        self.ops.push(Op::LiquifyKeep);
    }

    /// Takes up the liquify field set aside, for the edit just begun (with [`Self::begin_transform`]).
    pub fn resume_liquify(&mut self) {
        self.ops.push(Op::LiquifyResume);
    }

    /// Lets the liquify field set aside go.
    pub fn forget_liquify(&mut self) {
        self.ops.push(Op::LiquifyForget);
    }

    /// Whether a liquify field is set aside that fits this canvas, as the work run so far leaves
    /// it.
    pub fn keeps_liquify(&self) -> bool {
        let (w, h) = (self.width.div_ceil(2), self.height.div_ceil(2));
        self.kept_liquify.as_ref().is_some_and(|f| f.texture.width() == w && f.texture.height() == h)
    }

    /// Layer `id` copied aside to be transformed (or adjusted) from.
    pub(crate) fn copy_aside(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        id: LayerId,
    ) -> Option<Transforming> {
        let layer = self.layers.get(&id)?;
        let before = self.spare_before.take().unwrap_or_else(|| {
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some("transform source"),
                size: extent(self.width, self.height),
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: LAYER_FORMAT,
                usage: wgpu::TextureUsages::COPY_SRC
                    | wgpu::TextureUsages::COPY_DST
                    | wgpu::TextureUsages::TEXTURE_BINDING,
                view_formats: &[],
            })
        });
        encoder.copy_texture_to_texture(
            copy_at(&layer.texture, 0, 0),
            copy_at(&before, 0, 0),
            extent(self.width, self.height),
        );
        let view = before.create_view(&wgpu::TextureViewDescriptor::default());
        let masked = self.selection.is_some();
        let chosen = self.selection.as_ref().map_or(&self.everything, |s| &s.view);
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("transform source"),
            layout: &self.pipelines.transform_layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&view) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&self.sampler) },
                wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(chosen) },
            ],
        });
        let before_bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("adjustment copy"),
            layout: &self.pipelines.before_layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&view) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(&self.table_view) },
            ],
        });
        Some(Transforming { layer: id, before, bind, masked, before_bind })
    }

    /// The liquify field's bind group with the edit's copy: its bits where the field is kept in
    /// integers, else as the copy's second texture.
    pub(crate) fn liquify_bind(&self, device: &wgpu::Device, field: &wgpu::TextureView) -> wgpu::BindGroup {
        match self.liquify_field_bind(device, field) {
            Some(bind) if self.liquifies_in_integers() => bind,
            _ => self.copy_bind_with(device, field),
        }
    }

    /// The edit's copy and `second` bound as an adjustment's last-pass group.
    pub(crate) fn copy_bind_with(&self, device: &wgpu::Device, second: &wgpu::TextureView) -> wgpu::BindGroup {
        let fallback;
        let copy = match &self.transforming {
            Some(t) => {
                fallback = t.before.create_view(&wgpu::TextureViewDescriptor::default());
                &fallback
            }
            None => &self.everything,
        };
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("adjustment copy"),
            layout: &self.pipelines.before_layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(copy) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(second) },
            ],
        })
    }

    /// Redraws the layer being edited (begun with [`Self::begin_transform`]) from its copy through
    /// `filter`; [`Self::end_transform`] keeps it or puts the layer back.
    pub fn set_filter(&mut self, filter: Filter) {
        self.replace_placement(Op::FilterSet(filter));
    }

    /// Whether adjustments leave the layer's alpha as it is, as its Alpha Lock asks.
    pub fn set_filter_keeps_alpha(&mut self, on: bool) {
        self.filter_keeps_alpha = on;
    }

    /// Queues a redraw of the layer being transformed; only the latest needs drawing.
    pub(crate) fn replace_placement(&mut self, op: Op) {
        if let Some(Op::TransformSet { .. } | Op::TransformMesh { .. } | Op::FilterSet(_) | Op::PluginFilter(_)) =
            self.ops.last()
        {
            self.ops.pop();
        }
        self.ops.push(op);
    }

    /// Ends the transform: `commit` keeps it as one undo step, otherwise the layer is put back.
    pub fn end_transform(&mut self, commit: bool) {
        self.ops.push(Op::TransformEnd { commit, within: None });
    }

    /// Ends the transform as [`Canvas::end_transform`] does, the layers changed only inside
    /// `within`: an undo step keeps only that part of them.
    pub fn end_transform_within(&mut self, commit: bool, within: IRect) {
        self.ops.push(Op::TransformEnd { commit, within: Some(within) });
    }

    /// Whether a transform is open, counting queued work.
    pub fn is_transforming(&self) -> bool {
        let mut open = self.transforming.is_some();
        for op in &self.ops {
            match op {
                Op::TransformBegin(_) => open = true,
                Op::TransformEnd { .. } => open = false,
                _ => {}
            }
        }
        open
    }

    /// Creates textures for layers that have none yet: the document's and those queued work
    /// refers to (a layer can be added, painted and merged away before a frame is drawn).
    pub(crate) fn sync_layers(&mut self, gpu: &Gpu, doc: &Document) {
        let mut ids: Vec<LayerId> = doc.pixel_layer_ids().collect();
        for op in &self.ops {
            match op {
                Op::Record(d)
                | Op::Duplicate { before: d, .. }
                | Op::MergeDown { before: d, .. }
                | Op::MergeMany { before: d, .. }
                | Op::ApplyMask { before: d, .. }
                | Op::Flatten { before: d, .. } => ids.extend(d.pixel_layer_ids()),
                Op::Commit(s) => ids.push(s.layer),
                Op::Clear(id, _)
                | Op::Invert(id, _)
                | Op::Fill(id, _)
                | Op::Wipe(id)
                | Op::TransformBegin(id)
                | Op::TransformAlso(id) => {
                    ids.push(*id);
                }
                Op::Write { layers, .. } => ids.extend(layers.iter().map(|(id, ..)| *id)),
                Op::Flip { layers, .. } => ids.extend(layers.iter().copied()),
                Op::Put(id, _)
                | Op::PutRegion { layer: id, .. }
                | Op::Hold(id)
                | Op::RedrawFrom { layer: id, .. }
                | Op::Over { layer: id, .. } => {
                    ids.push(*id);
                }
                Op::Release { before: Some(d), .. } => ids.extend(d.pixel_layer_ids()),
                _ => {}
            }
            if let Op::Duplicate { pairs, .. } = op {
                ids.extend(pairs.iter().flat_map(|(from, to)| [*from, *to]));
            }
            if let Op::MergeDown { upper, lower, .. } = op {
                ids.extend([*upper, *lower]);
            }
            if let Op::MergeMany { uppers, lower, .. } = op {
                ids.extend(uppers.iter().copied().chain([*lower]));
            }
            if let Op::Flatten { into, .. } = op {
                ids.push(*into);
            }
        }
        if let Some(s) = self.active {
            ids.push(s.layer);
        }
        let (read, written) = self.ops_layers();
        self.restore_all(gpu, read, false);
        self.restore_all(gpu, written, true);
        let mut added = false;
        // spares cleared before this render's work
        let mut clears: Option<wgpu::CommandEncoder> = None;
        for id in ids {
            if self.layers.contains_key(&id) || self.parked.contains_key(&id) {
                continue;
            }
            // a kept texture, cleared, else a new one (new textures are zeroed: transparent)
            let spare = self.take_spare();
            let reused = spare.is_some();
            let texture = spare.unwrap_or_else(|| texture(&gpu.device, "layer", self.width, self.height, LAYER_FORMAT));
            let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
            if reused {
                let encoder = clears.get_or_insert_with(|| gpu.device.create_command_encoder(&Default::default()));
                drop(render_pass(encoder, "spare cleared", &view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT)));
            }
            self.layers.insert(id, LayerGpu { texture, view });
            added = true;
        }
        if let Some(encoder) = clears {
            gpu.queue.submit([encoder.finish()]);
        }
        if added {
            self.invalidate();
        }
    }

    /// Drops the textures of layers that neither the document nor the history refers to.
    pub(crate) fn collect_layers(&mut self, doc: &Document) {
        let mut keep: HashSet<LayerId> = doc.layer_ids().collect();
        for step in self.undo.iter().chain(self.redo.iter()) {
            if let Some(d) = &step.doc {
                keep.extend(d.layer_ids());
            }
            keep.extend(step.pixels.iter().map(|s| s.layer));
        }
        for op in &self.ops {
            if let Op::Record(d)
            | Op::Duplicate { before: d, .. }
            | Op::MergeDown { before: d, .. }
            | Op::MergeMany { before: d, .. }
            | Op::ApplyMask { before: d, .. }
            | Op::Flatten { before: d, .. }
            | Op::Release { before: Some(d), .. }
            | Op::Write { before: Some(d), .. } = op
            {
                keep.extend(d.layer_ids());
            }
        }
        // and the planes of the stores they keep, and of those the history keeps
        let planes = self.planes_kept(&keep);
        keep.extend(planes);
        if let Some(store) = self.smudge.as_ref().and_then(|s| s.store.as_ref()) {
            keep.extend(store.planes);
        }
        self.stores.retain(|layer, _| keep.contains(layer));
        let gone: Vec<LayerId> = self.layers.keys().filter(|id| !keep.contains(id)).copied().collect();
        for id in gone {
            if let Some(layer) = self.layers.remove(&id) {
                self.keep_spare(layer.texture);
            }
        }
        self.parked.retain(|id, _| keep.contains(id));
        self.pictures.retain(|id, _| keep.contains(id));
        self.needed_at.retain(|id, _| keep.contains(id));
    }

    /// Records and submits everything queued, recomposites what changed and draws to `target`.
    /// Undoing a structural step puts the earlier document into `doc`.
    pub fn render(&mut self, gpu: &Gpu, doc: &mut Document, target: Option<Target<'_>>) {
        let started = std::time::Instant::now();
        self.sync_layers(gpu, doc);
        // the stroke's bind goes with the open stroke's grain
        let full_grain = self.active.as_ref().and_then(|a| a.style.full_grain);
        if full_grain != self.bound_full_grain {
            self.bound_full_grain = full_grain;
            self.bind_brush_images(&gpu.device);
        }
        if !self.brush_images && self.wants_brush_images() {
            self.prepare_brush_images(gpu);
        }
        let device = &gpu.device;
        // groups flattened or merged are composited on their own, whatever they are
        let flattening = self.ops.iter().filter_map(|op| match op {
            Op::Flatten { before, .. } | Op::MergeMany { before, .. } => Some(before.max_depth()),
            _ => None,
        });
        let depth = flattening.max().unwrap_or(0);
        self.make_groups(device, depth);
        // the rule's lists free for this render's work (what read them before has been sent)
        self.rule_lists.begin();
        self.fragments.rule_lists_begin();
        // no more stamps than one upload takes (room left for the predicted tail): the rest wait for
        // a render after this one's
        let most_stamps = self.stamps_at_once.min(Self::stamps_in_a_buffer(device) / 2).max(1);
        // (a stroke painting in place laid again from scratch goes back to a mark instead, where it can)
        let ops = without_passing_tips(std::mem::take(&mut self.ops));
        let ops = self.rewound(ops);
        let ops = in_pieces(ops, self.full(), self.work_at_once, most_stamps);
        let (ops, held_back) = {
            let (mut taken, mut count) = (Vec::with_capacity(ops.len()), 0u64);
            let mut ops = ops.into_iter();
            let mut held_back = false;
            while let Some(op) = ops.next() {
                let n = if let Op::Stamps { instances, .. } = &op { instances.len() as u64 } else { 0 };
                if count > 0 && count.saturating_add(n) > most_stamps {
                    let rest: Vec<Op> = std::iter::once(op).chain(ops.by_ref()).collect();
                    self.ops.splice(0..0, rest);
                    held_back = true;
                    break;
                }
                count = count.saturating_add(n);
                taken.push(op);
            }
            (taken, held_back)
        };

        // this frame's stamps in one upload
        let mut instances: Vec<StampInstance> = Vec::new();
        for op in &ops {
            if let Op::Stamps { instances: i, .. } = op {
                instances.extend_from_slice(i);
            }
        }
        // the predicted tail goes last, drawn with the canvas
        let predicted_first = u32::try_from(instances.len()).unwrap_or(u32::MAX);
        if target.is_some() {
            instances.extend_from_slice(&self.prediction);
        }
        let predicted = predicted_first..u32::try_from(instances.len()).unwrap_or(u32::MAX);
        let needed = instances.len() as u64;
        if needed > self.stamp_capacity {
            // doubled for the strokes to come, as far as a buffer goes
            self.stamp_capacity = needed.next_power_of_two().min(Self::stamps_in_a_buffer(device)).max(needed);
            self.stamp_buffer = stamp_buffer(device, self.stamp_capacity);
        }
        if !instances.is_empty() {
            gpu.queue.write_buffer(&self.stamp_buffer, 0, bytemuck::cast_slice(&instances));
        }

        // the display takes four slots, and frosted panels three and one for each
        let frosted = target.as_ref().and_then(|t| t.backdrop).map_or(0, |b| b.regions.len() + b.windows.len() + 3);
        // remaking the caches composites the layers once more
        let cached = if self.cache_composites { doc.layers().len() + 2 } else { 0 };
        let worked: usize = ops.iter().map(|op| self.slots_for(op, &ops)).sum();
        let veiled = usize::from(self.veil.is_some());
        let slots = (worked + doc.layers().len() + 4 + veiled + frosted + cached) as u64;
        if slots > self.uniform_slots {
            self.uniform_slots = slots.next_power_of_two();
            let (buffer, bind) = uniforms(device, &self.pipelines.uniform_layout, self.uniform_slots);
            self.uniform_buffer = buffer;
            self.uniform_bind = bind;
        }
        // the uniforms stamps painted in place take: a stamp's own and, for wet paint, its pass's
        // (at most one a stamp, the stamps held back from before among them). Paint laid apart is
        // put together after each lot of stamps, each lot of its second's, and twice as a stroke
        // ends. Too few passes and none of it is written: the stroke, started again, comes out
        // with nothing (a stamp cut into windows takes more uniforms: `smudge.rs` makes room)
        let held = self.smudge.as_ref().map_or(0, |s| s.pending.len() as u64);
        let (smudge_count, wet_count) = ops.iter().fold((2 * held + 1, held + 1), |(slots, passes), op| match op {
            Op::Stamps { second: true, .. } => (slots + 1, passes),
            Op::Stamps { instances, style, .. } if style.in_place() => {
                let stamps = instances.len() as u64;
                (slots + 2 * stamps + 1, passes + stamps)
            }
            Op::Commit(stroke) if stroke.style.in_place() => (slots + 2, passes),
            _ => (slots, passes),
        });
        let smudge_count = smudge_count * SMUDGE_UNIFORM_SIZE.div_ceil(SLOT);
        if smudge_count > self.smudge_capacity {
            self.smudge_capacity = smudge_count.next_power_of_two();
            self.smudge_params = smudge_params(device, self.smudge_capacity);
        }
        if wet_count > self.wet_capacity {
            self.wet_capacity = wet_count.next_power_of_two();
            self.wet_params = wet_params(device, self.wet_capacity);
        }
        let mut uniforms = UniformData::default();
        let mut smudge_uniforms = UniformData::default();
        let mut wet_uniforms = UniformData::default();
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("canvas") });

        let mut next_instance = 0u32;
        let mut history_changed = false;
        // the upload buffers this render's work copies from, given back once it is sent
        let mut uploaded: Vec<wgpu::Buffer> = Vec::new();
        // what is worked on, for the caches: other work drops them
        let live = self.caches.as_ref().or(self.remake.as_ref().map(|r| &r.caches)).map(|c| c.live_layers.clone());
        // the layers a stroke was put into in this render's work so far, and whether the rest of
        // the work waits for a render of its own
        let mut committed: Vec<LayerId> = Vec::new();
        let mut deferred = held_back;
        // stamps' work recorded and not sent yet
        let mut recorded = 0u64;
        // a stroke laid again whole as it ends (or from a mark it went back to), in a render that
        // shows the canvas: laid for so long (see `RELAY_AT_ONCE`), the rest left to the renders
        // after, until its end is reached
        let rewinds = ops.iter().any(|op| matches!(op, Op::RewindInPlace(_)));
        let mut relay_until = target
            .as_ref()
            .filter(|_| self.relaying || rewinds || lays_ended_stroke_again(&ops))
            .map(|_| started + self.relay_at_once);
        let mut relaid = false;
        self.relaying = false;
        // paint laid in place, in a render that shows the canvas: as much as the GPU gets through in
        // a little while (see `IN_PLACE_FRAME_MS`), the rest left to the renders after
        let in_place_most = self.in_place_most(gpu, target.is_some());
        let mut in_place_laid = 0u64;
        self.catching_up = false;
        let full = self.full();
        let mut ops = ops.into_iter();
        while let Some(op) = ops.next() {
            if let (Some(until), Op::Stamps { .. }) = (relay_until, &op)
                && relaid
                && std::time::Instant::now() >= until
            {
                let rest: Vec<Op> = std::iter::once(op).chain(ops.by_ref()).collect();
                self.ops.splice(0..0, rest);
                self.relaying = true;
                break;
            }
            if let Op::Stamps { instances, style, second: false, pencil: false, .. } = &op
                && style.in_place()
            {
                let work = stamps_work(instances, full, true);
                if in_place_laid > 0 && in_place_laid.saturating_add(work) > in_place_most {
                    let rest: Vec<Op> = std::iter::once(op).chain(ops.by_ref()).collect();
                    self.ops.splice(0..0, rest);
                    // (a stroke being laid again holds the picture as it was meanwhile)
                    match relay_until {
                        Some(_) => self.relaying = true,
                        None => self.catching_up = true,
                    }
                    break;
                }
                in_place_laid = in_place_laid.saturating_add(work);
            }
            match &op {
                Op::Stamps { .. } => relaid = true,
                Op::Commit(_) | Op::Cancel => relay_until = None,
                _ => {}
            }
            if live.as_ref().is_some_and(|live| !self.keeps_caches(&op, live)) {
                self.let_go_caches(true);
            }
            // on OpenGL (ES) a stroke painting in place, which starts by copying its layer, waits
            // for a render of its own when a stroke went into that layer in this one: some
            // drivers' copy reads the layer as it was before (wet paint then picks up nothing)
            if gpu.is_gl()
                && let Op::Stamps { style, layer, .. } = &op
                && style.in_place()
                && self.smudge.as_ref().is_none_or(|s| s.layer != *layer)
                && committed.contains(layer)
            {
                let rest: Vec<Op> = std::iter::once(op).chain(ops.by_ref()).collect();
                self.ops.splice(0..0, rest);
                deferred = true;
                break;
            }
            // what is recorded goes first if this would make it too much for one piece
            if let Op::Stamps { instances, style, second, .. } = &op {
                let work = stamps_work(instances, self.full(), style.in_place() && !second);
                if recorded > 0 && recorded.saturating_add(work) > self.work_at_once {
                    self.send_piece(gpu, &mut encoder, (&mut uniforms, &mut smudge_uniforms, &mut wet_uniforms));
                    recorded = 0;
                }
                recorded = recorded.saturating_add(work);
            }
            // a paced canvas sends a stroke's end as a piece of its own: no stamps' work, and on a
            // slow GPU among its slowest
            if self.paced && recorded > 0 && matches!(op, Op::Commit(_)) {
                self.send_piece(gpu, &mut encoder, (&mut uniforms, &mut smudge_uniforms, &mut wet_uniforms));
                recorded = 0;
            }
            if let Op::Commit(stroke) = &op {
                committed.push(stroke.layer);
            }
            // the step a stroke put into its layer makes, noted for `step_of_stroke`
            let ending = match &op {
                Op::Commit(stroke) => Some((stroke.number, self.steps_made)),
                _ => None,
            };
            // a stroke ends: the next has a second only if it says so
            if matches!(op, Op::Commit(_) | Op::Cancel) {
                self.queued_second = None;
                self.smudge_refused = false;
            }
            match op {
                Op::Stamps { instances, wet, picks, times, style, layer, clear, second: false, .. }
                    if style.in_place() =>
                {
                    next_instance = next_instance.saturating_add(u32::try_from(instances.len()).unwrap_or(u32::MAX));
                    let stamps = (instances.as_slice(), wet.as_slice(), times.as_slice(), picks.as_slice());
                    let uniforms = (&mut smudge_uniforms, &mut wet_uniforms);
                    self.smudge_stamps(gpu, &mut encoder, uniforms, stamps, &style, layer, clear);
                }
                Op::RewindInPlace(at) => self.rewind_in_place(&mut encoder, at),
                Op::SecondReached(rect) => match self.smudge.as_mut().filter(|s| s.apart.is_some()) {
                    Some(smudge) => smudge.stale = smudge.stale.union(rect),
                    None => self.second_unsynthesized = self.second_unsynthesized.union(rect),
                },
                Op::DropSecond => {
                    self.queued_second = None;
                    // paint laid apart is laid alone from here (and put together so all over again)
                    if let Some(smudge) = self.smudge.as_mut()
                        && let Some(apart) = smudge.apart.as_mut()
                    {
                        apart.1 = 0;
                        smudge.stale_whole = true;
                    }
                }
                Op::ClearSecond(second) => {
                    self.queued_second = Some(second);
                    self.second_unsynthesized = IRect::EMPTY;
                    let (texture, view) = self.second_stroke.get(device);
                    drop(render_pass(
                        &mut encoder,
                        "second clear",
                        view,
                        wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                    ));
                    self.clear_built_up(&mut encoder, texture);
                }
                Op::Stamps { instances, style, clear, pencil, second, .. } => {
                    let count = u32::try_from(instances.len()).unwrap_or(u32::MAX);
                    let first = next_instance;
                    next_instance = next_instance.saturating_add(count);
                    let (grain, grain_scale, grain_depth) =
                        style.moving_grain().map_or((-1, 1.0, 0.0), |(g, s, d)| (g as i32, s, d));
                    let own_grain = self.full_grain_bind(device, &style);
                    let offset = uniforms.push(&StampUniform {
                        canvas_size: [self.width as f32, self.height as f32],
                        hardness: style.hardness.clamp(0.0, 1.0),
                        shape: style.shape.map_or(-1, |s| s as i32),
                        color: [style.color[0], style.color[1], style.color[2], 1.0],
                        grain,
                        grain_scale,
                        grain_depth,
                        grain_brightness: style.grain_brightness,
                        grain_contrast: style.grain_contrast,
                        grain_rotation: style.grain_rotation,
                        grain_blend: style.grain_blend,
                        grain_sharp: u32::from(style.grain_sharp),
                        shape_sharp: u32::from(style.shape_sharp),
                        shape_lod_cap: style.shape_lod_cap,
                        grain_side: style.grain_side,
                        tip_kind: style.tip_kind as u32,
                        secondary: [style.secondary[0], style.secondary[1], style.secondary[2], 1.0],
                    });
                    let load = if clear { wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT) } else { wgpu::LoadOp::Load };
                    // a stroke building up gradually or towards its transfer takes its rule pixel
                    // by pixel, where the GPU runs it; without compute passes every stroke is laid
                    // so, in fragment passes (see `fragments`)
                    let in_fragments = self.pipelines.fragments.is_some();
                    let rule = by_rule(style.rendering).filter(|_| self.pipelines.compute.is_some());
                    if rule.is_some() && count > 0 && self.rule_out.is_none() {
                        self.rule_out = self.made_or_told(device, |device| {
                            device.create_buffer(&wgpu::BufferDescriptor {
                                label: Some("stamp rule"),
                                size: u64::from(RULE_PART * RULE_PART) * 8,
                                usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
                                mapped_at_creation: false,
                            })
                        });
                    }
                    let (texture, target) = match (&self.pencil, pencil) {
                        _ if second => self.second_stroke.get(device),
                        (Some(mask), true) => (&mask.texture, &mask.view),
                        _ => (&self.stroke, &self.stroke_view),
                    };
                    let (texture, target) = (texture.clone(), target.clone());
                    if in_fragments {
                        if clear {
                            drop(render_pass(&mut encoder, "stamps clear", &target, load));
                            self.clear_built_up(&mut encoder, &texture);
                        }
                        if count > 0 {
                            let brush = own_grain.clone().unwrap_or_else(|| self.brush_bind.clone());
                            let rule = Canvas::fragment_rule(style.rendering);
                            self.lay_by_rule_in_fragments(
                                gpu,
                                &mut encoder,
                                (offset, &brush),
                                &instances,
                                (&texture, &target),
                                rule,
                            );
                        }
                    } else if let (Some(rule), true) = (rule, count > 0) {
                        if clear {
                            drop(render_pass(&mut encoder, "stamps clear", &target, load));
                        }
                        let brush = own_grain.clone().unwrap_or_else(|| self.brush_bind.clone());
                        self.lay_by_rule(
                            gpu,
                            &mut encoder,
                            (offset, &brush),
                            &instances,
                            first,
                            (&texture, &target),
                            rule,
                        );
                    } else {
                        let mut pass = render_pass(&mut encoder, "stamps", &target, load);
                        if count > 0 {
                            pass.set_pipeline(match style.rendering {
                                Rendering::Strongest => &self.pipelines.stamp_max,
                                _ => &self.pipelines.stamp,
                            });
                            pass.set_bind_group(0, &self.uniform_bind, &[offset]);
                            pass.set_bind_group(1, own_grain.as_ref().unwrap_or(&self.brush_bind), &[]);
                            let start = u64::from(first) * size_of::<StampInstance>() as u64;
                            let end = u64::from(next_instance) * size_of::<StampInstance>() as u64;
                            pass.set_vertex_buffer(0, self.stamp_buffer.slice(start..end));
                            pass.draw(0..6, 0..count);
                        }
                        drop(pass);
                    }
                    // a dual brush's paint laid in place: put together again where the second went,
                    // or where it went before the paint began as the stroke ends
                    if second {
                        let rect = instances.iter().fold(IRect::EMPTY, |r, s| {
                            let at = procraft_engine::geom::Vec2::new(s.center[0], s.center[1]);
                            r.union(IRect::around(at, s.radius * s.roundness.max(1.0) * std::f32::consts::SQRT_2))
                        });
                        match self.smudge.as_ref().filter(|s| s.apart.is_some()) {
                            Some(_) => self.synthesize(gpu, &mut encoder, &mut smudge_uniforms, rect),
                            None => self.second_unsynthesized = self.second_unsynthesized.union(rect),
                        }
                    }
                }
                Op::Commit(stroke) if stroke.style.in_place() => {
                    self.within = None;
                    // one that couldn't be begun made no step, nor takes the name of one
                    if self.smudge.is_none() {
                        self.next_name = None;
                    }
                    // wet paint's last frame, held back for the rest of it
                    let held = self.smudge.as_mut().map(|s| std::mem::take(&mut s.pending)).unwrap_or_default();
                    let uniforms = (&mut smudge_uniforms, &mut wet_uniforms);
                    self.lay_wet(gpu, &mut encoder, uniforms, &held, &stroke.style);
                    // a dual brush's paint laid apart, put together once more where it may not be as
                    // its paint and second now have it: where its second's first stamps came before
                    // the paint laid apart began, and what the stroke went back over (or over the
                    // whole stroke, wherever it was put together before, past its stamps where its
                    // second's reached, if its second went or was laid from scratch); each lot of
                    // either was put together where it went as it was laid
                    let rect = stroke.bounds.union(self.smudge.as_ref().map_or(IRect::EMPTY, |s| s.synthesized));
                    let stale = match self.smudge.as_ref() {
                        Some(s) if !s.stale_whole => s.stale.union(self.second_unsynthesized),
                        _ => rect,
                    };
                    self.second_unsynthesized = IRect::EMPTY;
                    self.synthesize(gpu, &mut encoder, &mut smudge_uniforms, stale);
                    self.finish_smudge(device, &mut encoder, rect, false);
                    history_changed = true;
                }
                // painting an adjustment on leaves the layer to the adjustment
                Op::Commit(stroke) if stroke.pencil => {
                    self.within = None;
                    if let Some(mask) = self.pencil.as_mut() {
                        mask.bounds = mask.bounds.union(stroke.bounds);
                    }
                }
                Op::Commit(stroke) => {
                    self.commit(device, &mut encoder, &mut uniforms, stroke);
                    history_changed = true;
                }
                Op::Cancel if self.smudge.is_some() => {
                    self.finish_smudge(device, &mut encoder, IRect::EMPTY, true);
                }
                Op::Cancel => {
                    drop(render_pass(
                        &mut encoder,
                        "cancel",
                        &self.stroke_view,
                        wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                    ));
                    self.clear_built_up(&mut encoder, &self.stroke);
                }
                Op::SaveTip(rect) => {
                    let (width, height) = (rect.width(), rect.height());
                    let roomy = self.tip_backup.as_ref().is_some_and(|b| b.width() >= width && b.height() >= height);
                    if !roomy {
                        // grown in steps, so a tip that grows doesn't make one every frame
                        let room = |n: u32, most: u32| n.max(1).next_multiple_of(256).min(most);
                        let (w, h) = self.tip_backup.as_ref().map_or((0, 0), |b| (b.width(), b.height()));
                        let size = (room(width.max(w), self.width), room(height.max(h), self.height));
                        let format = self.pipelines.stroke_format;
                        self.tip_backup = Some(texture(device, "tip backup", size.0, size.1, format));
                    }
                    if let Some(backup) = &self.tip_backup {
                        encoder.copy_texture_to_texture(
                            copy_at(&self.stroke, rect.x0, rect.y0),
                            copy_at(backup, 0, 0),
                            extent(width, height),
                        );
                    }
                    self.save_tip_built_up(device, &mut encoder, rect);
                }
                Op::Untip(rect) => {
                    if let Some(backup) = &self.tip_backup {
                        encoder.copy_texture_to_texture(
                            copy_at(backup, 0, 0),
                            copy_at(&self.stroke, rect.x0, rect.y0),
                            extent(rect.width(), rect.height()),
                        );
                    }
                    self.untip_built_up(&mut encoder, rect);
                }
                Op::Undo => {
                    if let Some(step) = self.undo.pop() {
                        let step = self.unpacked(device, &mut encoder, step, &mut uploaded);
                        let inverse = self.apply_step(gpu, &mut encoder, doc, step);
                        self.redo.push(inverse);
                        history_changed = true;
                    }
                }
                Op::Redo => {
                    if let Some(step) = self.redo.pop() {
                        let step = self.unpacked(device, &mut encoder, step, &mut uploaded);
                        let inverse = self.apply_step(gpu, &mut encoder, doc, step);
                        self.undo.push(inverse);
                        history_changed = true;
                    }
                }
                Op::Within(rects) => self.within = Some(rects),
                Op::Mark => self.mark = Some(self.steps_made),
                Op::Name(name) => self.next_name = Some(name),
                Op::Collapse => {
                    let since = self.mark.take().unwrap_or(u64::MAX);
                    let first = self.undo.iter().position(|s| s.made > since).unwrap_or(self.undo.len());
                    let flips = self.undo.get(first..).is_some_and(|steps| steps.iter().any(|s| s.flip.is_some()));
                    if self.undo.len() - first >= 2 && !flips {
                        let steps = self.undo.split_off(first);
                        self.undo.push(collapsed(steps));
                        history_changed = true;
                    }
                }
                Op::Invert(id, rect) => {
                    let rect = rect.intersect(self.full());
                    if !rect.is_empty()
                        && let Some(snapshot) = self.snapshot(device, &mut encoder, id, rect)
                    {
                        self.push_step(Step {
                            made: 0,
                            name: None,
                            doc: None,
                            pixels: vec![snapshot],
                            selection: None,
                            flip: None,
                            stores: Vec::new(),
                        });
                        self.encode_invert(device, &mut encoder, id, rect);
                        self.dirty = self.dirty.union(rect);
                        history_changed = true;
                    }
                }
                Op::Fill(id, rgb) => {
                    let full = self.full();
                    if let Some(snapshot) = self.snapshot(device, &mut encoder, id, full) {
                        if let Some(layer) = self.layers.get(&id) {
                            let [r, g, b] = rgb.map(|c| f64::from(c) / 255.0);
                            let colour = wgpu::Color { r, g, b, a: 1.0 };
                            drop(render_pass(&mut encoder, "fill", &layer.view, wgpu::LoadOp::Clear(colour)));
                        }
                        self.push_step(Step {
                            made: 0,
                            name: None,
                            doc: None,
                            pixels: vec![snapshot],
                            selection: None,
                            flip: None,
                            stores: Vec::new(),
                        });
                        self.dirty = full;
                        history_changed = true;
                    }
                }
                Op::Clear(id, painted) => {
                    let full = self.full();
                    let kept = painted.map_or(full, |r| r.intersect(full));
                    if let Some(snapshot) = self.snapshot(device, &mut encoder, id, kept) {
                        self.push_step(Step {
                            made: 0,
                            name: None,
                            doc: None,
                            pixels: vec![snapshot],
                            selection: None,
                            flip: None,
                            stores: Vec::new(),
                        });
                    }
                    if let Some(layer) = self.layers.get(&id) {
                        drop(render_pass(
                            &mut encoder,
                            "clear",
                            &layer.view,
                            wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                        ));
                    }
                    self.dirty = full;
                    history_changed = true;
                }
                Op::Over { layer: id, rect, within, pixels } => {
                    let full = self.full();
                    let kept = within.intersect(rect).intersect(full);
                    if !kept.is_empty()
                        && self.layers.contains_key(&id)
                        && let Some(snapshot) = self.snapshot(device, &mut encoder, id, kept)
                    {
                        self.push_step(Step {
                            made: 0,
                            name: None,
                            doc: None,
                            pixels: vec![snapshot],
                            selection: None,
                            flip: None,
                            stores: Vec::new(),
                        });
                        let (w, h) = (rect.width(), rect.height());
                        let source = device.create_texture(&wgpu::TextureDescriptor {
                            label: Some("laid over"),
                            size: extent(w, h),
                            mip_level_count: 1,
                            sample_count: 1,
                            dimension: wgpu::TextureDimension::D2,
                            format: LAYER_FORMAT,
                            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                            view_formats: &[],
                        });
                        gpu.queue.write_texture(
                            source.as_image_copy(),
                            &pixels,
                            wgpu::TexelCopyBufferLayout {
                                offset: 0,
                                bytes_per_row: Some(w * 4),
                                rows_per_image: Some(h),
                            },
                            extent(w, h),
                        );
                        let view = source.create_view(&wgpu::TextureViewDescriptor::default());
                        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
                            label: Some("laid over"),
                            layout: &self.pipelines.transform_layout,
                            entries: &[
                                wgpu::BindGroupEntry {
                                    binding: 0,
                                    resource: wgpu::BindingResource::TextureView(&view),
                                },
                                wgpu::BindGroupEntry {
                                    binding: 1,
                                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                                },
                                wgpu::BindGroupEntry {
                                    binding: 2,
                                    resource: wgpu::BindingResource::TextureView(&self.everything),
                                },
                            ],
                        });
                        // each pixel from the same one of the picture, nearest
                        let (x, y) = (rect.x0 as f32, rect.y0 as f32);
                        let offset = uniforms.push(&TransformUniform {
                            r0: [1.0, 0.0, -x, 0.0],
                            r1: [0.0, 1.0, -y, 0.0],
                            r2: [0.0, 0.0, 1.0, 0.0],
                            p: [0.0, w as f32, h as f32, 0.0],
                        });
                        if let Some(layer) = self.layers.get(&id) {
                            let mut pass = render_pass(&mut encoder, "laid over", &layer.view, wgpu::LoadOp::Load);
                            pass.set_pipeline(&self.pipelines.transform);
                            scissor(&mut pass, kept);
                            pass.set_bind_group(0, &bind, &[]);
                            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                            pass.draw(0..3, 0..1);
                        }
                        self.dirty = self.dirty.union(kept);
                    }
                }
                Op::RedrawFrom { layer: id, source, size, to_source: m, resample } => {
                    self.dry(id);
                    if let Some(layer) = self.layers.get(&id) {
                        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
                            label: Some("redrawn from"),
                            layout: &self.pipelines.transform_layout,
                            entries: &[
                                wgpu::BindGroupEntry {
                                    binding: 0,
                                    resource: wgpu::BindingResource::TextureView(&source),
                                },
                                wgpu::BindGroupEntry {
                                    binding: 1,
                                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                                },
                                wgpu::BindGroupEntry {
                                    binding: 2,
                                    resource: wgpu::BindingResource::TextureView(&self.everything),
                                },
                            ],
                        });
                        let filter = match resample {
                            Resample::Nearest => 0.0,
                            Resample::Bilinear => 1.0,
                            Resample::Bicubic => 2.0,
                        };
                        let [a, b, c, d, e, f, g, h, i] = m;
                        let offset = uniforms.push(&TransformUniform {
                            r0: [a, b, c, 0.0],
                            r1: [d, e, f, 0.0],
                            r2: [g, h, i, 0.0],
                            p: [filter, size.0 as f32, size.1 as f32, 0.0],
                        });
                        let clear = wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT);
                        let mut pass = render_pass(&mut encoder, "redrawn from", &layer.view, clear);
                        pass.set_pipeline(&self.pipelines.transform);
                        pass.set_bind_group(0, &bind, &[]);
                        pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                        pass.draw(0..3, 0..1);
                    }
                    self.dirty = self.full();
                }
                Op::Put(id, pixels) => {
                    if let Some(layer) = self.layers.get(&id) {
                        let (full, row) = (self.full(), self.width as usize * 4);
                        uploaded.extend(self.upload(device, &mut encoder, &layer.texture, full, &pixels, 0, row));
                    }
                    self.dirty = self.full();
                }
                Op::PutRegion { layer: id, x, y, width, height, pixels } => {
                    self.dry(id);
                    let at = |v: u32| i32::try_from(v).unwrap_or(i32::MAX);
                    let rect = IRect::new(at(x), at(y), at(x.saturating_add(width)), at(y.saturating_add(height)));
                    if let Some(layer) = self.layers.get(&id) {
                        let row = width as usize * 4;
                        uploaded.extend(self.upload(device, &mut encoder, &layer.texture, rect, &pixels, 0, row));
                    }
                    self.dirty = self.dirty.union(rect.intersect(self.full()));
                }
                Op::Hold(id) => {
                    let full = self.full();
                    self.held = self.snapshot(device, &mut encoder, id, full);
                    self.held_quietly = false;
                }
                Op::HoldQuietly => self.held_quietly = self.held.is_some(),
                Op::Release { keep, before } => {
                    let held = self.held.take();
                    self.held_quietly = false;
                    if keep {
                        if held.is_some() || before.is_some() {
                            self.push_step(Step {
                                made: 0,
                                name: None,
                                doc: before,
                                pixels: held.into_iter().collect(),
                                selection: None,
                                flip: None,
                                stores: Vec::new(),
                            });
                            history_changed = true;
                        }
                    } else if let Some(s) = held
                        && let Some(layer) = self.layers.get(&s.layer)
                        && let Some(texture) = s.texture()
                    {
                        encoder.copy_texture_to_texture(
                            copy_at(texture, 0, 0),
                            copy_at(&layer.texture, s.rect.x0, s.rect.y0),
                            extent(s.rect.width(), s.rect.height()),
                        );
                    }
                    self.dirty = self.full();
                }
                Op::Flip { layers, horizontal, before } => {
                    // undone by flipping again: nothing kept (but the document as it was, if it
                    // changed too); the planes of their stores flipped with them
                    let layers = self.with_planes(&layers);
                    let step = Step {
                        made: 0,
                        name: None,
                        doc: before,
                        pixels: Vec::new(),
                        selection: None,
                        flip: Some((layers.clone(), horizontal)),
                        stores: Vec::new(),
                    };
                    self.push_step(step);
                    self.encode_flip(device, &mut encoder, &layers, horizontal);
                    self.dirty = self.full();
                    history_changed = true;
                }
                Op::Write { layers, before } => {
                    let snapshots: Vec<Snapshot> = layers
                        .iter()
                        .filter_map(|(id, rect, _)| self.snapshot(device, &mut encoder, *id, *rect))
                        .collect();
                    let document_changed = before.is_some();
                    if !snapshots.is_empty() || document_changed {
                        self.push_step(Step {
                            made: 0,
                            name: None,
                            doc: before,
                            pixels: snapshots,
                            selection: None,
                            flip: None,
                            stores: Vec::new(),
                        });
                    }
                    for (id, rect, pixels) in layers {
                        let Some(layer) = self.layers.get(&id) else { continue };
                        // copied in the encoder's order, so the snapshot above is taken first
                        let row = rect.width() as usize * 4;
                        uploaded.extend(self.upload(device, &mut encoder, &layer.texture, rect, &pixels, 0, row));
                        self.dirty = self.dirty.union(rect);
                    }
                    if document_changed {
                        self.dirty = self.full();
                    }
                    history_changed = true;
                }
                Op::TransformBegin(id) => {
                    self.transform_more.clear();
                    if let Some(t) = self.copy_aside(device, &mut encoder, id) {
                        self.transforming = Some(t);
                    }
                }
                Op::TransformAlso(id) => {
                    if let Some(t) = self.copy_aside(device, &mut encoder, id) {
                        self.transform_more.push(t);
                    }
                }
                Op::TransformSet { to_source: m, resample } => {
                    let lit = self.transform_stores_too(device, &mut encoder);
                    let mut drew = false;
                    for t in self.transforming.iter().chain(&self.transform_more) {
                        let Some(layer) = self.layers.get(&t.layer) else { continue };
                        let filter = match resample {
                            Resample::Nearest => 0.0,
                            Resample::Bilinear => 1.0,
                            Resample::Bicubic => 2.0,
                        };
                        let [a, b, c, d, e, f, g, h, i] = m;
                        let offset = uniforms.push(&TransformUniform {
                            r0: [a, b, c, 0.0],
                            r1: [d, e, f, 0.0],
                            r2: [g, h, i, 0.0],
                            p: [filter, self.width as f32, self.height as f32, if t.masked { 1.0 } else { 0.0 }],
                        });
                        let mut pass = render_pass(
                            &mut encoder,
                            "transform",
                            &layer.view,
                            wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                        );
                        if t.masked {
                            pass.set_pipeline(&self.pipelines.transform_static);
                            pass.set_bind_group(0, &t.bind, &[]);
                            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                            pass.draw(0..3, 0..1);
                        }
                        pass.set_pipeline(&self.pipelines.transform);
                        pass.set_bind_group(0, &t.bind, &[]);
                        pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                        pass.draw(0..3, 0..1);
                        drop(pass);
                        drew = true;
                    }
                    for (layer, planes) in lit {
                        self.draw_lit(device, &mut encoder, layer, planes, &[self.full()]);
                    }
                    if drew {
                        self.dirty = self.full();
                    }
                }
                Op::TransformMesh { vertices, resample } => {
                    let filter = match resample {
                        Resample::Nearest => 0.0,
                        Resample::Bilinear => 1.0,
                        Resample::Bicubic => 2.0,
                    };
                    let bytes: &[u8] = bytemuck::cast_slice(&vertices);
                    let count = u32::try_from(vertices.len()).unwrap_or(0);
                    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
                        label: Some("warp mesh"),
                        size: (bytes.len() as u64).max(16),
                        usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
                        mapped_at_creation: false,
                    });
                    // written before this frame's commands run
                    gpu.queue.write_buffer(&buffer, 0, bytes);
                    let lit = self.transform_stores_too(device, &mut encoder);
                    let mut drew = false;
                    for t in self.transforming.iter().chain(&self.transform_more) {
                        let Some(layer) = self.layers.get(&t.layer) else { continue };
                        let offset = uniforms.push(&TransformUniform {
                            r0: [0.0; 4],
                            r1: [0.0; 4],
                            r2: [0.0; 4],
                            p: [filter, self.width as f32, self.height as f32, if t.masked { 1.0 } else { 0.0 }],
                        });
                        let mut pass = render_pass(
                            &mut encoder,
                            "warp",
                            &layer.view,
                            wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                        );
                        if t.masked {
                            pass.set_pipeline(&self.pipelines.transform_static);
                            pass.set_bind_group(0, &t.bind, &[]);
                            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                            pass.draw(0..3, 0..1);
                        }
                        if count > 0 {
                            pass.set_pipeline(&self.pipelines.mesh);
                            pass.set_bind_group(0, &t.bind, &[]);
                            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                            pass.set_vertex_buffer(0, buffer.slice(..));
                            pass.draw(0..count, 0..1);
                        }
                        drop(pass);
                        drew = true;
                    }
                    for (layer, planes) in lit {
                        self.draw_lit(device, &mut encoder, layer, planes, &[self.full()]);
                    }
                    if drew {
                        self.dirty = self.full();
                    }
                }
                Op::FilterSet(filter) => {
                    self.filter(gpu, &mut encoder, &mut uniforms, filter);
                }
                Op::PluginFilter(filter) => {
                    self.plugin_filter(gpu, &mut encoder, &mut uniforms, &filter);
                }
                Op::LiquifyBegin => {
                    // in floats, or in integers holding their bits where the GPU can't draw into floats
                    let format = self.pipelines.liquify.as_ref().map_or(fragments::LIQUIFY_FIELD_FORMAT, |l| l.format);
                    let (width, height) = (self.width.div_ceil(2), self.height.div_ceil(2));
                    let field = texture(device, "liquify field", width, height, format);
                    let view = field.create_view(&wgpu::TextureViewDescriptor::default());
                    let marked = texture(device, "liquify marked", width, height, format);
                    let clear = wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT);
                    drop(render_pass(&mut encoder, "liquify clear", &view, clear));
                    let marked_view = marked.create_view(&wgpu::TextureViewDescriptor::default());
                    drop(render_pass(&mut encoder, "liquify clear", &marked_view, clear));
                    // a new field: the one set aside goes
                    self.kept_liquify = None;
                    let bind = self.liquify_bind(device, &view);
                    self.liquify = Some(LiquifyField {
                        bind,
                        view,
                        texture: field,
                        scale: [width as f32 / self.width as f32, height as f32 / self.height as f32],
                        scratch: None,
                        marked,
                        changed: IRect::EMPTY,
                        undo: Vec::new(),
                        redo: Vec::new(),
                        bits: None,
                    });
                }
                Op::PencilBegin => {
                    let mask = texture(device, "pencil mask", self.width, self.height, self.pipelines.stroke_format);
                    let view = mask.create_view(&wgpu::TextureViewDescriptor::default());
                    drop(render_pass(
                        &mut encoder,
                        "pencil clear",
                        &view,
                        wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                    ));
                    self.pencil = Some(PencilMask { texture: mask, view, bounds: IRect::EMPTY });
                    // the edit's source now takes the mask where it took the selection
                    let source = self
                        .transforming
                        .as_ref()
                        .map(|t| t.before.create_view(&wgpu::TextureViewDescriptor::default()));
                    if let Some(view) = source {
                        let bind = self.adjustment_bind(device, &view, true);
                        if let Some(t) = self.transforming.as_mut() {
                            t.bind = bind;
                            t.masked = true;
                        }
                    }
                }
                Op::LiquifyStamps(stamps) => self.liquify_step(gpu, &mut encoder, &mut uniforms, &stamps, 1.0),
                Op::LiquifyScale(amount) => self.liquify_step(gpu, &mut encoder, &mut uniforms, &[], amount),
                Op::LiquifyClear => {
                    if let Some(field) = &mut self.liquify {
                        drop(render_pass(
                            &mut encoder,
                            "liquify clear",
                            &field.view,
                            wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                        ));
                        field.changed = IRect::from_size(field.texture.width(), field.texture.height());
                    }
                }
                Op::LiquifyMark => self.mark_field(device, &mut encoder),
                Op::LiquifyTakeBack => self.take_back_field(&mut encoder),
                Op::LiquifyUndo => self.liquify_history(device, &mut encoder, true),
                Op::LiquifyRedo => self.liquify_history(device, &mut encoder, false),
                Op::LiquifyKeep => self.kept_liquify = self.liquify.take(),
                Op::LiquifyResume => {
                    if self.keeps_liquify()
                        && let Some(mut field) = self.kept_liquify.take()
                    {
                        // read with the copy of the edit just begun
                        field.bind = self.liquify_bind(device, &field.view);
                        self.liquify = Some(field);
                    }
                }
                Op::LiquifyForget => self.kept_liquify = None,
                Op::TransformEnd { commit, within } => {
                    self.liquify = None;
                    self.blur_textures.lock().unwrap_or_else(PoisonError::into_inner).clear();
                    let painted = self.pencil.take().map(|p| p.bounds.intersect(self.full()));
                    let more = std::mem::take(&mut self.transform_more);
                    // a save made while it was open read them as they showed then
                    let ended: Vec<LayerId> = self.transforming.iter().chain(&more).map(|t| t.layer).collect();
                    for id in ended {
                        self.touched(id);
                    }
                    if let Some(t) = self.transforming.take() {
                        if commit && let Some(rect) = painted {
                            // painted on: only where it was painted changed, and nothing if nowhere
                            if !rect.is_empty() {
                                let texture = region_copy(device, &mut encoder, &t.before, rect);
                                self.push_step(Step {
                                    made: 0,
                                    name: None,
                                    doc: None,
                                    pixels: vec![Snapshot::on_gpu(t.layer, rect, texture)],
                                    selection: None,
                                    flip: None,
                                    stores: Vec::new(),
                                });
                                history_changed = true;
                            }
                            self.spare_before = Some(t.before);
                        } else if commit {
                            let full = self.full();
                            // only the part changed, when that is known
                            let part = within.map(|r| r.intersect(full)).filter(|r| !r.is_empty() && *r != full);
                            let mut spare = None;
                            let pixels = std::iter::once(t)
                                .chain(more)
                                .map(|t| match part {
                                    Some(rect) => {
                                        let texture = region_copy(device, &mut encoder, &t.before, rect);
                                        spare = Some(t.before);
                                        Snapshot::on_gpu(t.layer, rect, texture)
                                    }
                                    None => Snapshot::on_gpu(t.layer, full, t.before),
                                })
                                .collect();
                            if spare.is_some() {
                                self.spare_before = spare;
                            }
                            self.push_step(Step {
                                made: 0,
                                name: None,
                                doc: None,
                                pixels,
                                selection: None,
                                flip: None,
                                stores: Vec::new(),
                            });
                            history_changed = true;
                        } else {
                            for t in std::iter::once(t).chain(more) {
                                if let Some(layer) = self.layers.get(&t.layer) {
                                    encoder.copy_texture_to_texture(
                                        copy_at(&t.before, 0, 0),
                                        copy_at(&layer.texture, 0, 0),
                                        extent(self.width, self.height),
                                    );
                                }
                                self.spare_before = Some(t.before);
                            }
                        }
                        self.dirty = self.full();
                    }
                }
                Op::Wipe(id) => {
                    self.dry(id);
                    if let Some(layer) = self.layers.get(&id) {
                        drop(render_pass(
                            &mut encoder,
                            "wipe",
                            &layer.view,
                            wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                        ));
                    }
                    self.dirty = self.full();
                }
                Op::Record(before) => {
                    self.push_step(Step {
                        made: 0,
                        name: None,
                        doc: Some(before),
                        pixels: Vec::new(),
                        selection: None,
                        flip: None,
                        stores: Vec::new(),
                    });
                    self.dirty = self.full();
                    history_changed = true;
                }
                Op::OutsideChange(name) => {
                    self.push_step(Step {
                        made: 0,
                        name: Some(name),
                        doc: None,
                        pixels: Vec::new(),
                        selection: None,
                        flip: None,
                        stores: Vec::new(),
                    });
                    history_changed = true;
                }
                Op::Duplicate { pairs, before } => {
                    for (from, to) in pairs {
                        if let (Some(src), Some(dst)) = (self.layers.get(&from), self.layers.get(&to)) {
                            encoder.copy_texture_to_texture(
                                copy_at(&src.texture, 0, 0),
                                copy_at(&dst.texture, 0, 0),
                                extent(self.width, self.height),
                            );
                        }
                    }
                    self.push_step(Step {
                        made: 0,
                        name: None,
                        doc: Some(before),
                        pixels: Vec::new(),
                        selection: None,
                        flip: None,
                        stores: Vec::new(),
                    });
                    self.dirty = self.full();
                    history_changed = true;
                }
                Op::MergeDown { upper, lower, before, within, masks, drop_lower } => {
                    let opacity = before.layer(lower).map_or(1.0, |l| l.opacity);
                    let pixels = self.before_merge(
                        device,
                        &mut encoder,
                        &mut uniforms,
                        (lower, opacity),
                        &masks,
                        drop_lower,
                        within,
                    );
                    self.blend_layer_into(device, &mut encoder, &mut uniforms, &before, upper, lower);
                    self.push_step(Step {
                        made: 0,
                        name: None,
                        doc: Some(before),
                        pixels,
                        selection: None,
                        flip: None,
                        stores: Vec::new(),
                    });
                    self.dirty = self.full();
                    history_changed = true;
                }
                Op::MergeMany { uppers, lower, before, within, masks, drop_lower } => {
                    let opacity = before.layer(lower).map_or(1.0, |l| l.opacity);
                    let pixels = self.before_merge(
                        device,
                        &mut encoder,
                        &mut uniforms,
                        (lower, opacity),
                        &masks,
                        drop_lower,
                        within,
                    );
                    let mut rest = uppers.as_slice();
                    while let Some((&upper, after)) = rest.split_first() {
                        rest = after;
                        // a layer with those clipped to it just above (and merged with it): their
                        // clipping group
                        let clipped = clipped_run(&before, rest, upper);
                        if before.layer(upper).is_some_and(|l| l.kind == LayerKind::Group) {
                            self.draw_group_onto(device, &mut encoder, &mut uniforms, &before, upper, lower);
                        } else if clipped.is_empty() {
                            self.blend_layer_into(device, &mut encoder, &mut uniforms, &before, upper, lower);
                        } else {
                            rest = rest.get(clipped.len()..).unwrap_or_default();
                            self.draw_clip_group_onto(
                                device,
                                &mut encoder,
                                &mut uniforms,
                                &before,
                                upper,
                                clipped,
                                lower,
                            );
                        }
                    }
                    self.push_step(Step {
                        made: 0,
                        name: None,
                        doc: Some(before),
                        pixels,
                        selection: None,
                        flip: None,
                        stores: Vec::new(),
                    });
                    self.dirty = self.full();
                    history_changed = true;
                }
                Op::ApplyMask { layer, mask, before } => {
                    let pixels =
                        self.masked_in_place(device, &mut encoder, &mut uniforms, layer, mask).into_iter().collect();
                    self.push_step(Step {
                        made: 0,
                        name: None,
                        doc: Some(before),
                        pixels,
                        selection: None,
                        flip: None,
                        stores: Vec::new(),
                    });
                    self.dirty = self.full();
                    history_changed = true;
                }
                Op::Flatten { group, into, before } => {
                    // a new layer: no pixels of its own to keep
                    self.flatten(device, &mut encoder, &mut uniforms, &before, group, into);
                    let pixels = Vec::new();
                    self.push_step(Step {
                        made: 0,
                        name: None,
                        doc: Some(before),
                        pixels,
                        selection: None,
                        flip: None,
                        stores: Vec::new(),
                    });
                    self.dirty = self.full();
                    history_changed = true;
                }
            }
            if let Some((number, before)) = ending
                && self.steps_made > before
            {
                self.stroke_step = Some((number, self.steps_made));
            }
        }
        if history_changed {
            self.collect_layers(doc);
        }
        // a stroke's own grain goes with it once it has ended
        if self.active.is_none() && self.stroke_grain.take().is_some() {
            self.bind_brush_images(device);
        }

        if !smudge_uniforms.data.is_empty() {
            gpu.queue.write_buffer(&self.smudge_params, 0, &smudge_uniforms.data);
        }
        if !wet_uniforms.data.is_empty() {
            gpu.queue.write_buffer(&self.wet_params, 0, &wet_uniforms.data);
        }
        self.make_groups(device, group_levels(doc));
        self.ensure_caches(gpu, doc, &mut encoder, &mut uniforms);
        // after the caches: caches just made want the whole picture composited again
        let dirty = self.dirty.intersect(self.full());
        let device = &gpu.device;
        // while caches are remade, or a stroke laid again over several renders, the composite stays
        // as it was, what changed kept for after
        if self.remake.is_none() && !self.relaying {
            if !dirty.is_empty() {
                self.composite(device, doc, &mut encoder, &mut uniforms, dirty);
                self.composites = self.composites.wrapping_add(1);
                self.frost_stale = self.frost_stale.union(dirty);
            }
            self.dirty = IRect::EMPTY;
        }

        // the veil's rectangle, the colour round the canvas and its hole, for its shader
        if let (Some(target), Some(veil)) = (target.as_ref(), self.veil.as_ref()) {
            let srgb = target.format.is_srgb();
            let around = target.surround.map(|c| if srgb { srgb_to_linear(c) } else { c });
            let mut points = [[0.0; 4]; VEIL_POINTS / 2];
            for (entry, pair) in points.iter_mut().zip(veil.hole.chunks(2)) {
                match pair {
                    [a, b] => *entry = [a[0], a[1], b[0], b[1]],
                    [a] => *entry = [a[0], a[1], 0.0, 0.0],
                    _ => {}
                }
            }
            let r = veil.rect;
            // the hole's edge is smoothed over a target pixel: that many canvas pixels
            let margin = 1.0 / target.to_screen.scale_factor().max(1e-6);
            let uniform = VeilUniform {
                rect: [r.x0 as f32, r.y0 as f32, r.width() as f32, r.height() as f32],
                surround: [around[0], around[1], around[2], veil.hole.len() as f32],
                points,
                cells: VeilCells::of(r, &veil.hole, margin).packed(),
            };
            gpu.queue.write_buffer(&veil.uniform, 0, bytemuck::bytes_of(&uniform));
        }
        if let Some(target) = target {
            // what the glass shows is worked out first: drawn in the display's own pass, a tiled
            // GPU needn't write the whole target out and read it back for a pass of its own
            let shows = |b: &Backdrop<'_>| !(b.regions.is_empty() && b.windows.is_empty()) && b.sigma > 0.0;
            let backdrop = target.backdrop.filter(shows);
            let glass = backdrop.map(|b| (b, self.frost_ready(device, &mut encoder, &mut uniforms, &target, b, None)));
            self.display(device, &mut encoder, &mut uniforms, &target, predicted, glass);
        }
        self.write_uniforms(gpu, &uniforms);
        let at = std::time::Instant::now();
        let sent = gpu.queue.submit([encoder.finish()]);
        for buffer in uploaded {
            self.uploads.give_back(buffer);
        }
        // (how fast the GPU gets through paint laid in place is seen once this is done: from when
        // this began, as OpenGL does much of the work as it is sent)
        if in_place_laid > 0 && self.in_place_sent.is_none() {
            self.in_place_sent = Some((sent.clone(), started, in_place_laid));
        }
        self.wait_if_paced(gpu, sent, at);
        self.renders = self.renders.wrapping_add(1);
        self.park_extra(gpu, doc);
        self.pack_history(gpu);
        // the work held back, in a submission after this one's (a stroke being laid again goes on in
        // the next render that shows the canvas)
        if deferred && !self.relaying && !self.catching_up {
            self.render(gpu, doc, None);
        }
    }

    /// Smudges `instances` into the stroke's layer: per stamp, the brush picks up colour and lays
    /// down what it carries, a window at a time (see `smudge.rs`), the changed pixels going back
    /// into the layer texture. Wet paint is laid a pass at a time (a display frame's input, see
    /// [`Canvas::lay_wet`]), the newest pass's stamps held back until the rest of it comes.
    pub(crate) fn smudge_stamps(
        &mut self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        (params, passes): (&mut UniformData, &mut UniformData),
        (instances, wet, times, picks): SmudgedStamps<'_>,
        style: &StrokeStyle,
        target: LayerId,
        restart: bool,
    ) {
        let device = &gpu.device;
        // redrawn from scratch: the stroke starts again on the layer as it was before it
        if restart && self.smudge.as_ref().is_some_and(|s| s.layer == target) {
            self.smudge_put_back(encoder);
            if let Some(smudge) = self.smudge.as_mut() {
                if let Some(rewind) = smudge.rewind.as_mut() {
                    rewind.restart();
                }
                // (what was put together where its second reaches past the paint is gone too)
                smudge.stale_whole = true;
                smudge.tiles.clear();
                smudge.stalled = false;
                for carry in &mut smudge.carries {
                    *carry = Carry { aside: carry.aside.take(), unkept: carry.unkept, ..Carry::new() };
                }
                smudge.resident = None;
                smudge.pending.clear();
                smudge.pass = None;
            }
            self.dirty = self.full();
        }
        if self.smudge.as_ref().is_none_or(|s| s.layer != target) {
            if self.smudge_refused || !self.layers.contains_key(&target) {
                return;
            }
            let wet_paint = style.paints_in_place();
            // paint laid in place with a second, a texturized grain, wet or burnt edges, or a
            // glaze of its own over its wet paint is laid apart, and put together with them
            let second = self.queued_second.map(|d| (d.uniform_mode(), d.threshold));
            let alone = grain_once(style) || style.wet_edges > 0.0 || style.burnt_edges > 0.0 || wet_rule(style) != 0;
            let apart = second
                .or(alone.then_some((0, -1.0)))
                .filter(|_| wet_paint)
                .map(|(mode, threshold)| (*style, mode, threshold));
            // and keeps the stroke as it was laid, each stamp's colour in it (and wet paint to go on
            // mixing with), and how much of its paint shows; wet paint in the modulated mode or a
            // glaze of its own keeps how much each pixel has taken, or how far its glaze shows
            let keeps = smudge::Keeps {
                grained: apart.is_some(),
                amount: wet_paint && (style.rendering == Rendering::Gradual || wet_rule(style) != 0),
                shown: apart.is_some(),
            };
            // what it carries made first, and the stroke not begun if it can't be (past the GPU's
            // memory): begun, each frame it is in would go undrawn (the screen flashing)
            let memory = device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
            let invalid = device.push_error_scope(wgpu::ErrorFilter::Validation);
            let in_fragments = self.pipelines.compute.is_none();
            let carry = (!in_fragments).then(|| {
                device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("smudge carry"),
                    // the carry, and the blur's two passes over it (see the shader's cs_blur_down);
                    // or wet paint's two carried-over plots and a pass's plots (see cs_wet_plots)
                    size: if wet_paint {
                        (2 * u64::from(WET_PLOT_MOST * WET_PLOT_MOST) + WET_PLOTS_TEXELS) * 16
                    } else {
                        u64::from(SMUDGE_GRID * SMUDGE_GRID) * 16 * 3
                    },
                    // (copied aside and back where a symmetry's copies take turns in it)
                    usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                })
            });
            let held = in_fragments.then(|| fragments::Held::new(device, wet_paint));
            if let Some(why) = pollster::block_on(invalid.pop()).or(pollster::block_on(memory.pop())) {
                log::error!("smudging: {why}");
                self.smudge_refused = true;
                self.smudge_untold = true;
                return;
            }
            // painting into the layer's store: what it works in is the store's paint
            let store =
                (style.store != 0).then(|| self.store_for_stroke(device, encoder, target, style.store)).flatten();
            let rewind = (wet_paint && self.rewinds && self.may_lay_again && !in_fragments && store.is_none())
                .then(|| rewind::Rewind::new(self.low_memory(), self.rewind_most));
            self.smudge = Some(Smudge {
                layer: target,
                into: store.as_ref().map_or(target, |s| s.planes[0]),
                store,
                carry,
                held,
                carries: vec![Carry::new()],
                resident: None,
                wet: wet_paint,
                keeps,
                apart,
                pending: Vec::new(),
                pass: None,
                passed: None,
                tiles: smudge::Tiles::new(),
                window: None,
                frag_window: None,
                laid: false,
                stalled: false,
                synthesized: IRect::EMPTY,
                rewind,
                stale: IRect::EMPTY,
                stale_whole: false,
            });
        }
        if style.paints_in_place() {
            let Some(smudge) = self.smudge.as_mut() else { return };
            let given = instances.iter().zip(wet).zip(times).map(|((s, w), t)| (*s, *w, *t));
            if let Some(rewind) = smudge.rewind.as_mut() {
                rewind.give(given.clone());
            }
            smudge.pending.extend(given);
            let key = |s: &(StampInstance, [f32; 8], Paced)| wet_pass(s.2.time_ms, style.preview);
            let newest = smudge.pending.last().map(key);
            let ready = smudge.pending.iter().take_while(|s| Some(key(s)) != newest).count();
            let ready: Vec<_> = smudge.pending.drain(..ready).collect();
            // a mark to go back to before them (see `rewind.rs`)
            if let Some(first) = ready.first() {
                self.mark_in_place(encoder, device, first.2.time_ms);
            }
            self.lay_wet(gpu, encoder, (params, passes), &ready, style);
            if let Some(rewind) = self.smudge.as_mut().and_then(|s| s.rewind.as_mut()) {
                rewind.laid(ready.len());
            }
            return;
        }
        // a smudge of its own is laid a pass a display frame, as wet paint is; one picking up
        // behind it (a plugin brush engine's dabs) stamp by stamp from the layer as it is
        if picks.iter().all(Option::is_none) {
            let stamps: Vec<_> = instances.iter().zip(wet).zip(times).map(|((s, w), t)| (*s, *w, *t)).collect();
            self.lay_smudge(gpu, encoder, params, &stamps, style);
            return;
        }
        let brush = self.full_grain_bind(device, style).unwrap_or_else(|| self.brush_bind.clone());
        let full = IRect::from_size(self.width, self.height);
        let size = (self.width, self.height);
        let Some(smudge) = self.smudge.as_mut() else { return };
        let mut laid = Vec::new();
        // each of a symmetry's copies of the stroke in turn, carrying on from its own last stamp
        let order = by_copy(times);
        smudge.carries_for(times);
        for k in order {
            let (Some(s), Some(copy)) = (instances.get(k), times.get(k).map(|t| t.copy)) else { continue };
            let Some(carry) = smudge.carries.get_mut(copy) else { continue };
            let center = procraft_engine::geom::Vec2::new(s.center[0], s.center[1]);
            let bbox = IRect::around(center, (s.radius + QUAD_REACH) * s.roundness.max(1.0) * std::f32::consts::SQRT_2)
                .intersect(full);
            if bbox.is_empty() || s.radius <= 0.0 {
                continue;
            }
            // the blur and the pull as far as this stamp's pressure takes them
            let [_, blur_by, blur_jitter_by, _, _, _, _, smudge_pull_by] = wet.get(k).copied().unwrap_or([1.0; 8]);
            carry.stamps = carry.stamps.saturating_add(1);
            // the blur, jitter only ever lowering it; a stamp picking up its own way, its own
            let pick_up = picks.get(k).copied().flatten();
            let blur = match pick_up {
                Some(p) if p.blur > 0.0 || p.sharpen > 0.0 => p.blur.clamp(0.0, 1.0),
                _ => {
                    style.blur
                        * blur_by
                        * (1.0 - style.blur_jitter * blur_jitter_by * unit_hash(carry.stamps.wrapping_add(7919)))
                }
            };
            // what the brush carries a pixel a cell, unless the stroke's first stamp is wider than
            // the grid; its middle at the stamp's pixel, and the cells the stamp reaches around it
            let reach = s.radius.max(0.5) + 1.0;
            if !carry.started {
                carry.scale = (reach / SMUDGE_REACH as f32).max(1.0);
            }
            let anchor = if carry.scale <= 1.0 { s.center.map(f32::floor) } else { s.center };
            let half = ((reach / carry.scale).ceil() as i32).clamp(1, SMUDGE_REACH);
            // a stamp picking up behind it reads where the last stamp lay, keeping nothing older;
            // one picking up from elsewhere, that far from its own place
            let pick = match pick_up {
                Some(PickUp { from: Some([x, y]), .. }) => [anchor[0] + x, anchor[1] + y],
                Some(p) if !p.here => carry.last_anchor.unwrap_or(anchor),
                _ => anchor,
            };
            let warp = pick_up.and_then(|p| p.warp);
            carry.last_anchor = Some(anchor);
            let (paint_rate, paint, dull, dull_weighted) = pick_up.map_or((0.0, style.color, 0.0, 0), |p| {
                let (dull, weighed) = p.dull.map_or((0.0, false), |(r, w)| (r.clamp(1e-3, 3.0), w));
                (p.paint_share.clamp(0.0, 1.0), p.paint.unwrap_or(style.color), dull, u32::from(weighed))
            });
            let uniform = SmudgeUniform {
                pull: if pick_up.is_some() {
                    0.0
                } else {
                    style.pull.clamp(0.0, 1.0).powf(PULL_POWER) * smudge_pull_by
                },
                pick,
                paint_rate,
                // an erasing brush mixes in clear paint, taking paint away; see-through paint as far
                // as it is opaque
                paint: match pick_up {
                    Some(p) if p.clear => [0.0; 4],
                    Some(p) if p.thin.is_finite() => [paint[0], paint[1], paint[2], 1.0 - p.thin.clamp(0.0, 1.0)],
                    _ => [paint[0], paint[1], paint[2], 1.0],
                },
                dull,
                dull_weighted,
                smear_over: u32::from(pick_up.is_some_and(|p| p.over)),
                kept: carry.kept,
                blur,
                anchor,
                scale: carry.scale,
                half,
                source_before: u32::from(style.wet_from_before || pick_up.is_some_and(|p| p.before)),
                warp_kind: warp.map_or(0, |w| w.kind as u32 + if w.nearest { 16 } else { 0 }),
                warp_shift: warp.map_or([0.0; 2], |w| w.shift),
                warp_amount: warp.map_or(0.0, |w| if w.amount.is_finite() { w.amount } else { 0.0 }),
                sharpen: pick_up.map_or(0.0, |p| if p.sharpen.is_finite() { p.sharpen.max(0.0) } else { 0.0 }),
                dither: dither_key(carry.stamps),
                ..smudge_uniform(s, style, bbox, size)
            };
            carry.started = true;
            carry.kept = if pick_up.is_some() { -1 } else { half };
            laid.push((uniform, bbox, blur > 0.0 || uniform.sharpen > 0.0, copy));
        }
        let pieces = self.smudged_pieces(laid);
        self.lay_pieces(gpu, encoder, params, &pieces, (&brush, None));
        // into a store: the stamps' heights after their paint, and the layer drawn from them
        if self.smudge.as_ref().is_some_and(|s| s.store.is_some()) {
            self.lay_store(device, encoder, &brush, (instances, picks), style);
        }
    }

    /// Smudges `stamps` (with their wet-mix scaling and places in the stroke) into the stroke's
    /// layer, a pass a display frame as wet paint is laid (see [`wet_pass`]): each stamp in turn
    /// picks up colour into what the brush carries and lays that down, picking up from the layer
    /// as the last pass left it, so that the stamps of one pass see each other only through what
    /// the brush carries. A pass goes on from one render to the next as its frame's stamps come.
    /// Each of a symmetry's copies of the stroke carries its own, its stamps of a pass laid in
    /// turn, all picking up from the layer as the last pass left it.
    pub(crate) fn lay_smudge(
        &mut self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        params: &mut UniformData,
        stamps: &[(StampInstance, [f32; 8], Paced)],
        style: &StrokeStyle,
    ) {
        if stamps.is_empty() {
            return;
        }
        let brush = self.full_grain_bind(&gpu.device, style).unwrap_or_else(|| self.brush_bind.clone());
        let full = IRect::from_size(self.width, self.height);
        let size = (self.width, self.height);
        let mut from = 0;
        while let Some(head) = stamps.get(from) {
            let key = wet_pass(head.2.time_ms, style.preview);
            let count = stamps.iter().skip(from).take_while(|s| wet_pass(s.2.time_ms, style.preview) == key).count();
            let group = stamps.get(from..from + count.max(1)).unwrap_or_default();
            from += group.len().max(1);
            let Some(smudge) = self.smudge.as_mut() else { return };
            let mut laid = Vec::new();
            let paced: Vec<Paced> = group.iter().map(|s| s.2).collect();
            smudge.carries_for(&paced);
            for (s, w, paced) in by_copy(&paced).into_iter().filter_map(|k| group.get(k)) {
                let Some(carry) = smudge.carries.get_mut(paced.copy) else { continue };
                let center = procraft_engine::geom::Vec2::new(s.center[0], s.center[1]);
                let bbox =
                    IRect::around(center, (s.radius + QUAD_REACH) * s.roundness.max(1.0) * std::f32::consts::SQRT_2)
                        .intersect(full);
                if bbox.is_empty() || s.radius <= 0.0 {
                    continue;
                }
                // the blur and the pull as far as this stamp's pressure takes them
                let [_, blur_by, blur_jitter_by, _, _, _, _, smudge_pull_by] = *w;
                carry.stamps = carry.stamps.saturating_add(1);
                // the blur, jitter only ever lowering it
                let blur = style.blur
                    * blur_by
                    * (1.0 - style.blur_jitter * blur_jitter_by * unit_hash(carry.stamps.wrapping_add(7919)));
                // what the brush carries a pixel a cell, unless the stroke's first stamp is wider
                // than the grid; its middle at the stamp's pixel, and the cells the stamp reaches
                // around it
                let reach = s.radius.max(0.5) + 1.0;
                if !carry.started {
                    carry.scale = (reach / SMUDGE_REACH as f32).max(1.0);
                }
                let anchor = if carry.scale <= 1.0 { s.center.map(f32::floor) } else { s.center };
                let half = ((reach / carry.scale).ceil() as i32).clamp(1, SMUDGE_REACH);
                carry.last_anchor = Some(anchor);
                let uniform = SmudgeUniform {
                    pull: style.pull.clamp(0.0, 1.0).powf(PULL_POWER) * smudge_pull_by,
                    pick: anchor,
                    kept: carry.kept,
                    blur,
                    anchor,
                    scale: carry.scale,
                    half,
                    dither: dither_key(carry.stamps),
                    ..smudge_uniform(s, style, bbox, size)
                };
                carry.started = true;
                carry.kept = half;
                laid.push((uniform, bbox, blur > 0.0, paced.copy));
            }
            let mut pieces = self.smudged_pieces(laid);
            // the layer as the last pass left it where this one picks up (or, if that couldn't be
            // kept, as it is)
            let reach = pieces.iter().fold(IRect::EMPTY, |r, p| r.union(p.reach)).intersect(full);
            if let Some(origin) = self.smudge_pass_start(&gpu.device, encoder, key, reach) {
                for u in pieces.iter_mut().flat_map(|p| p.uniforms.iter_mut()) {
                    u.passed = 1;
                    u.pass_origin = origin;
                }
            }
            self.lay_pieces(gpu, encoder, params, &pieces, (&brush, None));
        }
    }

    /// Readies what pass `key`'s stamps pick up from, as far as `reach`: the layer as it is now, for
    /// a pass begun here; for one going on, as much more of it as the pass now reaches (none of it
    /// laid on yet: what the pass lays lies within what it reached). Where it is kept from, or none
    /// if it couldn't be (they then pick up from the layer as it is).
    pub(crate) fn smudge_pass_start(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        key: i64,
        reach: IRect,
    ) -> Option<[i32; 2]> {
        let (width, height) = (self.width, self.height);
        let smudge = self.smudge.as_mut()?;
        let layer = self.layers.get(&smudge.into)?;
        let kept = smudge.pass.filter(|p| p.0 == key && smudge.passed.is_some()).map(|p| p.1);
        let rect = kept.map_or(reach, |k| k.union(reach));
        if rect.is_empty() {
            return None;
        }
        if kept == Some(rect) {
            return Some([rect.x0, rect.y0]);
        }
        smudge.pass = None;
        let (w, h) = (rect.width(), rect.height());
        let fits = |t: &wgpu::Texture| t.width() >= w && t.height() >= h;
        // a pass going on keeps what it had where it had it; else the texture is kept for the next
        let mut into = None;
        if kept.is_some() || smudge.passed.as_ref().is_none_or(|(t, _)| !fits(t)) {
            // grown in steps, so a stroke that reaches further doesn't make one every frame
            let memory = device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
            let invalid = device.push_error_scope(wgpu::ErrorFilter::Validation);
            let side = |n: u32, of: u32| n.next_multiple_of(256).min(of).max(n);
            let made = texture(device, "smudge pass", side(w, width), side(h, height), LAYER_FORMAT);
            if let Some(e) = pollster::block_on(invalid.pop()).or(pollster::block_on(memory.pop())) {
                log::error!("smudging: {e}");
                smudge.passed = None;
                return None;
            }
            let view = made.create_view(&wgpu::TextureViewDescriptor::default());
            into = Some((made, view));
        }
        let copy = |encoder: &mut wgpu::CommandEncoder, to: &wgpu::Texture| {
            encoder.copy_texture_to_texture(copy_at(&layer.texture, rect.x0, rect.y0), copy_at(to, 0, 0), extent(w, h));
        };
        match (into, kept) {
            (Some(made), Some(k)) => {
                copy(encoder, &made.0);
                if let Some((was, _)) = &smudge.passed {
                    let at = copy_at(&made.0, k.x0 - rect.x0, k.y0 - rect.y0);
                    encoder.copy_texture_to_texture(copy_at(was, 0, 0), at, extent(k.width(), k.height()));
                }
                smudge.passed = Some(made);
            }
            (Some(made), None) => {
                copy(encoder, &made.0);
                smudge.passed = Some(made);
            }
            (None, _) => copy(encoder, &smudge.passed.as_ref()?.0),
        }
        smudge.pass = Some((key, rect));
        Some([rect.x0, rect.y0])
    }

    /// Paint laid apart has been put together over `rect` (see [`Smudge::synthesized`]).
    pub(crate) fn note_synthesized(&mut self, rect: IRect) {
        if let Some(smudge) = self.smudge.as_mut() {
            smudge.synthesized = smudge.synthesized.union(rect.intersect(IRect::from_size(self.width, self.height)));
        }
    }

    /// A dual brush's paint laid in place put together with its second over `rect` (see
    /// `cs_synthesize`), and shown.
    pub(crate) fn synthesize(&mut self, gpu: &Gpu, encoder: &mut wgpu::CommandEncoder, params: &mut UniformData, rect: IRect) {
        let device = &gpu.device;
        let Some(&(style, dual, _)) = self.smudge.as_ref().and_then(|s| s.apart.as_ref()) else { return };
        let brush = self.full_grain_bind(device, &style).unwrap_or_else(|| self.brush_bind.clone());
        let pieces = self.synthesis(rect);
        if pieces.is_empty() {
            return;
        }
        self.note_synthesized(rect);
        let second = (dual != 0).then(|| self.second_stroke.get(device).1.clone());
        self.lay_pieces(gpu, encoder, params, &pieces, (&brush, second.as_ref()));
    }

    /// The pieces of [`Canvas::synthesize`] over `rect`: none without paint laid apart.
    pub(crate) fn synthesis(&self, rect: IRect) -> Vec<smudge::Piece> {
        let rect = rect.intersect(IRect::from_size(self.width, self.height));
        let Some(&(style, dual, dual_threshold)) = self.smudge.as_ref().and_then(|s| s.apart.as_ref()) else {
            return Vec::new();
        };
        if rect.is_empty() {
            return Vec::new();
        }
        let uniform = SmudgeUniform {
            mode: 1,
            dual,
            dual_threshold,
            grain_once: if grain_once(&style) { 1.0 } else { 0.0 },
            laid_apart: 2,
            ..smudge_uniform(&bytemuck::Zeroable::zeroed(), &style, rect, (self.width, self.height))
        };
        self.bands(smudge::Pipe::Synthesize, uniform, rect, (true, false), 0)
    }

    /// Lays wet paint, a pass a display frame (see [`wet_pass`]; at most [`WET_PASS_MOST`] stamps):
    /// `stamps` in order, with their wet-mix scaling and places in the stroke. Each pass works out
    /// what each of its stamps lays down from the layer as the pass finds it (see the shader's
    /// `cs_wet_plots`), then lays the stamps, each through its shape and grain.
    pub(crate) fn lay_wet(
        &mut self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        (params, passes): (&mut UniformData, &mut UniformData),
        stamps: &[(StampInstance, [f32; 8], Paced)],
        style: &StrokeStyle,
    ) {
        if stamps.is_empty() {
            return;
        }
        let device = &gpu.device;
        let size = (self.width, self.height);
        let full = IRect::from_size(self.width, self.height);
        let brush = self.full_grain_bind(device, style).unwrap_or_else(|| self.brush_bind.clone());
        let dual = self.smudge.as_ref().and_then(|s| s.apart.as_ref()).is_some_and(|a| a.1 != 0);
        let second = dual.then(|| self.second_stroke.get(device).1.clone());
        let Some(smudge) = self.smudge.as_ref() else { return };
        let grain_once = if grain_once(style) { 1.0 } else { 0.0 };
        let modulated = u32::from(smudge.keeps.amount && style.rendering == Rendering::Gradual);
        let laid_apart = u32::from(smudge.apart.is_some());
        // what its stamps change: the layer, unless laid apart (put together after), and what the
        // stroke keeps when it keeps any
        let changes = (laid_apart == 0, modulated == 1 || laid_apart == 1);
        let bbox_of = |s: &StampInstance| {
            let center = procraft_engine::geom::Vec2::new(s.center[0], s.center[1]);
            let bbox = IRect::around(center, (s.radius + QUAD_REACH) * s.roundness.max(1.0) * std::f32::consts::SQRT_2)
                .intersect(full);
            if s.radius <= 0.0 { IRect::EMPTY } else { bbox }
        };
        let mut changed = IRect::EMPTY;
        let mut pieces = Vec::new();
        let groups = |r: IRect| [r.width().div_ceil(8), r.height().div_ceil(8)];
        if style.wet_pull.is_none() {
            // dry paint laid in place: each stamp lays the paint itself, no pass of plots; listed a
            // lot at a time as a pass's stamps are, and laid in runs at once (each rounding as the
            // stamp it is of its copy of the stroke)
            let paced: Vec<Paced> = stamps.iter().map(|s| s.2).collect();
            let keys: Vec<u32> = self.smudge.as_mut().map_or_else(Vec::new, |smudge| {
                smudge.carries_for(&paced);
                let mut key = |copy: usize| {
                    let carry = smudge.carries.get_mut(copy)?;
                    carry.stamps = carry.stamps.saturating_add(1);
                    Some(dither_key(carry.stamps))
                };
                paced.iter().map(|p| key(p.copy).unwrap_or(0)).collect()
            });
            for (at, lot) in stamps.chunks(WET_PASS_MOST).enumerate() {
                let mut list: WetPass = bytemuck::Zeroable::zeroed();
                let mut own = Vec::with_capacity(lot.len());
                for (k, (s, _, _)) in lot.iter().enumerate() {
                    let bbox = bbox_of(s);
                    changed = changed.union(bbox);
                    let [r, g, b] = stamp_paint(s, style);
                    let uniform = SmudgeUniform {
                        mode: 1,
                        modulated,
                        grain_once,
                        count: 0,
                        laid_apart: laid_apart * 2,
                        paint: [r, g, b, 1.0],
                        dither: keys.get(at * WET_PASS_MOST + k).copied().unwrap_or(0),
                        ..smudge_uniform(s, style, bbox, size)
                    };
                    if let Some(slot) = list.stamps.get_mut(k) {
                        *slot = wet_stamp(&uniform, [0.0; 3], [r, g, b]);
                    }
                    own.push((uniform, bbox));
                }
                // stamps laid one by one (each a dispatch of its own), a band of rows at a time
                let one_by_one = |canvas: &Self, laid: &[(SmudgeUniform, IRect)], listed: u32| -> Vec<smudge::Piece> {
                    laid.iter()
                        .filter(|(_, bbox)| !bbox.is_empty())
                        .flat_map(|&(u, bbox)| canvas.bands(smudge::Pipe::Deposit, u, bbox, changes, listed))
                        .collect()
                };
                if self.stamp_by_stamp {
                    pieces.extend(one_by_one(self, &own, 0));
                    continue;
                }
                let listed = passes.push(&list);
                let boxes: Vec<(usize, IRect)> = own.iter().map(|(_, b)| *b).enumerate().collect();
                for (start, end, union) in deposit_runs(&boxes) {
                    let Some(&(head, _)) = own.get(start) else { continue };
                    if !self.fits_window(union) {
                        // a run reaching further than a window holds
                        pieces.extend(one_by_one(self, own.get(start..end).unwrap_or_default(), listed));
                        continue;
                    }
                    let run = [u32::try_from(start).unwrap_or(0), u32::try_from(end).unwrap_or(0), 0, 0];
                    pieces.push(smudge::Piece {
                        uniforms: vec![SmudgeUniform { run, bbox: [union.x0, union.y0, union.x1, union.y1], ..head }],
                        dispatches: vec![(smudge::Pipe::DepositRun, 0, groups(union))],
                        listed,
                        reach: union,
                        writes: union,
                        work: changes.0,
                        kept: changes.1,
                        // (dry paint carries nothing)
                        carry: 0,
                    });
                }
            }
            // paint laid apart put together right after, in the same window where it fits (the
            // layer, the paint as laid and the layer as it was are then copied in once a frame)
            if laid_apart == 1 {
                pieces.extend(self.synthesis(changed));
                self.note_synthesized(changed);
            }
            self.lay_pieces(gpu, encoder, params, &pieces, (&brush, second.as_ref()));
            return;
        }
        // what a pass of `group` reads and writes
        let pass_reach = |group: &[(StampInstance, [f32; 8], Paced)]| {
            group
                .iter()
                .fold(IRect::EMPTY, |r, (s, ..)| r.union(smudge::plot_reach(s, 1, None, full)).union(bbox_of(s)))
        };
        let mut from = 0;
        while let Some(head) = stamps.get(from) {
            // a display frame's stamps, each of a symmetry's copies' (the stroke's own first) laid
            // as passes of its own, the copies' passes in turn: each carries its own paint
            let key = wet_pass(head.2.time_ms, style.preview);
            let frame = stamps.iter().skip(from).take_while(|s| wet_pass(s.2.time_ms, style.preview) == key).count();
            let frame = stamps.get(from..from + frame.max(1)).unwrap_or_default();
            from += frame.len().max(1);
            let paced: Vec<Paced> = frame.iter().map(|s| s.2).collect();
            if let Some(smudge) = self.smudge.as_mut() {
                smudge.carries_for(&paced);
            }
            let mut copies: Vec<(usize, Vec<PacedStamp>)> = Vec::new();
            for k in by_copy(&paced) {
                let Some(stamp) = frame.get(k) else { continue };
                match copies.last_mut() {
                    Some((copy, list)) if *copy == stamp.2.copy => list.push(*stamp),
                    _ => copies.push((stamp.2.copy, vec![*stamp])),
                }
            }
            let mut laid = vec![0usize; copies.len()];
            while laid.iter().zip(&copies).any(|(done, (_, list))| *done < list.len()) {
                for ((copy, list), done) in copies.iter().zip(laid.iter_mut()) {
                    let Some(rest) = list.get(*done..).filter(|rest| !rest.is_empty()) else { continue };
                    // at most the most a pass takes, and a pass whose stamps reach further than a
                    // window holds laid as several
                    let count = rest.len().min(WET_PASS_MOST);
                    let count = smudge::longest(0, u32::try_from(count).unwrap_or(1), |_, n| {
                        self.fits_window(pass_reach(rest.get(..n as usize).unwrap_or_default()))
                    }) as usize;
                    let group = rest.get(..count.max(1)).unwrap_or_default();
                    *done += group.len().max(1);
                    let n = group.len();
                    let Some(carry) = self.smudge.as_mut().and_then(|s| s.carries.get_mut(*copy)) else { continue };
                    let first = carry.passes == 0;
                    // each plot as many texels a side as the pass's largest stamp is pixels across,
                    // halved until the pass's plots fit
                    let largest = group.iter().map(|s| s.0.radius).fold(0.0f32, f32::max);
                    let mut plot = ((2.0 * largest).ceil().max(4.0) as u32).min(WET_PLOT_MOST);
                    while plot > 4 && u64::from(plot * plot) * n as u64 > WET_PLOTS_TEXELS {
                        plot /= 2;
                    }
                    let carried = carry.plot.max(1);
                    let turn = carry.passes % 2;
                    let wet = |s: &StampInstance, bbox: IRect, index: u32| SmudgeUniform {
                        mode: 1,
                        modulated,
                        kept: if first { -1 } else { 0 },
                        grain_once,
                        laid_apart: laid_apart * 2,
                        alpha: (s.alpha * style.opacity * style.wetness).clamp(0.0, 1.0),
                        count: u32::try_from(n).unwrap_or(0),
                        plot,
                        index,
                        carried,
                        ..smudge_uniform(s, style, bbox, size)
                    };
                    // each stamp as the plots read it and as it lays itself, and its own uniform
                    let mut list: WetPass = bytemuck::Zeroable::zeroed();
                    let mut own = Vec::with_capacity(n);
                    for (k, (s, w, paced)) in group.iter().enumerate() {
                        let [grade_by, _, _, wet_jitter_by, charge_by, dilution_by, wet_pull_by, _] = *w;
                        // the mixture varies from stamp to stamp by the wetness jitter
                        let jitter = 1.0 - style.wet_jitter * wet_jitter_by * unit_hash(carry.stamps);
                        carry.stamps = carry.stamps.saturating_add(1);
                        // the paint runs out with the length the stroke has gone
                        let (mixture, pull) =
                            wet_mixture(style, paced.distance, jitter, s.more[3], dilution_by, charge_by);
                        let pull = pull * wet_pull_by;
                        let grade = (style.grade * grade_by).clamp(-1.0, 1.0);
                        let final_mixture = final_mixture(mixture, pull, grade, (k, n), first);
                        let [r, g, b] = stamp_paint(s, style);
                        let bbox = bbox_of(s);
                        let uniform = SmudgeUniform {
                            dither: dither_key(carry.stamps),
                            ..wet(s, bbox, u32::try_from(k).unwrap_or(0))
                        };
                        if let Some(slot) = list.stamps.get_mut(k) {
                            *slot = wet_stamp(&uniform, [pull, mixture, final_mixture], [r, g, b]);
                        }
                        own.push((uniform, bbox));
                    }
                    let listed = passes.push(&list);
                    carry.passes = carry.passes.saturating_add(1);
                    carry.plot = plot;
                    let Some(head) = group.first() else { continue };
                    // the pass's plots, carrying over from the plot the last pass kept (the two kept
                    // plots take turns), then each stamp laid from its plot
                    let plots = wet(&head.0, IRect::EMPTY, turn);
                    let mut piece = smudge::Piece {
                        uniforms: vec![plots],
                        dispatches: vec![(smudge::Pipe::WetPlots, 0, [plot.div_ceil(8), plot.div_ceil(8)])],
                        listed,
                        reach: pass_reach(group),
                        writes: IRect::EMPTY,
                        work: changes.0,
                        kept: changes.1,
                        carry: *copy,
                    };
                    for &(_, bbox) in &own {
                        changed = changed.union(bbox);
                        piece.writes = piece.writes.union(bbox);
                    }
                    if self.stamp_by_stamp {
                        for &(u, bbox) in own.iter().filter(|(_, bbox)| !bbox.is_empty()) {
                            piece.dispatches.push((smudge::Pipe::Deposit, piece.uniforms.len(), groups(bbox)));
                            piece.uniforms.push(u);
                        }
                    } else {
                        // the stamps laid from their plots in runs, each run at once
                        let boxes: Vec<(usize, IRect)> = own.iter().map(|(_, b)| *b).enumerate().collect();
                        for (start, end, union) in deposit_runs(&boxes) {
                            let Some(&(head, _)) = own.get(start) else { continue };
                            let run = [u32::try_from(start).unwrap_or(0), u32::try_from(end).unwrap_or(0), 0, 0];
                            let bbox = [union.x0, union.y0, union.x1, union.y1];
                            piece.dispatches.push((smudge::Pipe::DepositRun, piece.uniforms.len(), groups(union)));
                            piece.uniforms.push(SmudgeUniform { run, bbox, ..head });
                        }
                    }
                    if self.fits_window(piece.reach) {
                        pieces.push(piece);
                    } else {
                        // one stamp reaching further than a window holds (a pass of more is cut): its
                        // plot made a block of texels at a time, then the stamp laid a band of rows at
                        // a time
                        pieces.extend(self.wet_stamp_pieces(&head.0, piece, changes));
                    }
                }
            }
        }
        if laid_apart == 1 {
            pieces.extend(self.synthesis(changed));
            self.note_synthesized(changed);
        }
        self.lay_pieces(gpu, encoder, params, &pieces, (&brush, second.as_ref()));
    }

    /// Ends a smudge stroke: one undo step with the pixels it changed, or (`cancel`) puts the
    /// layer back as it was.
    pub(crate) fn finish_smudge(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        bounds: IRect,
        cancel: bool,
    ) {
        if self.smudge.as_ref().is_none_or(|s| !self.layers.contains_key(&s.layer)) {
            self.smudge = None;
            return;
        }
        let rect = bounds.intersect(self.full());
        if cancel || rect.is_empty() {
            if cancel {
                self.smudge_put_back(encoder);
                self.dirty = self.full();
            }
            // a store it made for nothing goes again
            if let Some(smudge) = self.smudge.take()
                && let Some(made) = smudge.store.and_then(|s| s.made)
            {
                self.swap_stores(vec![(smudge.layer, made)]);
            }
            return;
        }
        let undo = || {
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some("undo"),
                size: extent(rect.width(), rect.height()),
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: LAYER_FORMAT,
                usage: wgpu::TextureUsages::COPY_SRC | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            })
        };
        let texture = undo();
        self.smudge_before(encoder, rect, &texture);
        // into a store: the layer and the height as they were too
        let also: Vec<(LayerId, wgpu::Texture)> = self
            .smudge_also()
            .into_iter()
            .enumerate()
            .map(|(k, id)| {
                let texture = undo();
                self.smudge_before_of(encoder, rect, &texture, Some(k));
                (id, texture)
            })
            .collect();
        let Some(smudge) = self.smudge.take() else { return };
        let mut pixels = vec![Snapshot::on_gpu(smudge.into, rect, texture)];
        pixels.extend(also.into_iter().map(|(id, texture)| Snapshot::on_gpu(id, rect, texture)));
        let stores = smudge.store.and_then(|s| s.made).map(|before| (smudge.layer, before)).into_iter().collect();
        self.push_step(Step { made: 0, name: None, doc: None, pixels, selection: None, flip: None, stores });
    }

    pub(crate) fn commit(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        stroke: ActiveStroke,
    ) {
        let rect = stroke.bounds.intersect(self.full());
        // laid only within part of the layer (never one laid in place: it has changed it already)
        let rect = stroke.within.filter(|_| !stroke.style.in_place()).map_or(rect, |w| rect.intersect(w));
        let within = self.within.take();
        if !rect.is_empty() && self.layers.contains_key(&stroke.layer) {
            // what the stroke can change: its bounds, or only the rectangles it was said to reach
            let parts = within.unwrap_or_else(|| vec![rect]);
            let pixels: Vec<Snapshot> =
                parts.iter().filter_map(|r| self.snapshot(device, encoder, stroke.layer, r.intersect(rect))).collect();
            if !pixels.is_empty() {
                self.push_step(Step {
                    made: 0,
                    name: None,
                    doc: None,
                    pixels,
                    selection: None,
                    flip: None,
                    stores: Vec::new(),
                });
            }
            let opacity = stroke.style.opacity.clamp(0.0, 1.0);
            let scaled = self.scaled.map_or([1.0, 0.0, 0.0, 0.0], |([x, y], k)| [k, x, y, 0.0]);
            let paper = self.paper_look();
            let locked = stroke.style.preserve_alpha;
            let burns = stroke.style.burnt_edges > 0.0 && !stroke.style.preview;
            // a dual brush's: its second's texture read too (the stroke has ended: told here)
            let second = if stroke.second.is_some() { self.second_stroke.get(device).1 } else { &self.stroke_view };
            let stroke_view = &self.stroke_view;
            let quad = |layer: &wgpu::TextureView| {
                let views = [layer, stroke_view, stroke_view, stroke_view, stroke_view, second, stroke_view];
                let grain = self.grain_view_of(&stroke.style);
                quad_bind(device, &self.pipelines.quad_layout, views, grain, &self.grain_sampler, &self.paper_view)
            };
            let laid = stroke.style.tool == StrokeTool::Laid;
            match (stroke.style.tool, laid || locked || stroke.style.blend != 0 || burns || stroke.style.luminance) {
                (StrokeTool::Paint | StrokeTool::Laid, true) => {
                    // recolour where the layer has alpha, or blend by a mode or burn edges (which
                    // read the layer): into scratch, then back
                    let offset = uniforms.push(
                        &LayerUniform {
                            opacity: 1.0,
                            stroke_mode: match (laid, locked) {
                                (true, _) => self.laid_mode(),
                                (_, true) => STROKE_PAINT_LOCKED,
                                _ => STROKE_PAINT,
                            },
                            stroke_opacity: opacity,
                            scaled,
                            ..Default::default()
                        }
                        .with_grain(Some(&stroke.style))
                        .with_paper(true, paper)
                        .with_dual(Some(&stroke)),
                    );
                    if let Some(layer) = self.layers.get(&stroke.layer) {
                        let bind = quad(&layer.view);
                        let (scratch, scratch_view) = self.scratch.get(device);
                        let mut pass = render_pass(encoder, "commit locked", scratch_view, wgpu::LoadOp::Load);
                        pass.set_pipeline(&self.pipelines.commit_locked);
                        scissor(&mut pass, rect);
                        pass.set_bind_group(0, &bind, &[]);
                        pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                        pass.draw(0..3, 0..1);
                        drop(pass);
                        encoder.copy_texture_to_texture(
                            copy_at(scratch, rect.x0, rect.y0),
                            copy_at(&layer.texture, rect.x0, rect.y0),
                            extent(rect.width(), rect.height()),
                        );
                    }
                }
                (tool, _) => {
                    let offset = uniforms.push(
                        &LayerUniform { opacity: 1.0, stroke_opacity: opacity, scaled, ..Default::default() }
                            .with_grain(Some(&stroke.style))
                            .with_paper(true, paper)
                            .with_dual(Some(&stroke)),
                    );
                    let dual =
                        (stroke.second.is_some() || stroke.style.full_grain.is_some()).then(|| quad(&self.stroke_view));
                    if let Some(layer) = self.layers.get(&stroke.layer) {
                        let mut pass = render_pass(encoder, "commit", &layer.view, wgpu::LoadOp::Load);
                        pass.set_pipeline(match tool {
                            StrokeTool::Paint | StrokeTool::Smudge | StrokeTool::Laid => &self.pipelines.commit_paint,
                            StrokeTool::Erase => &self.pipelines.commit_erase,
                        });
                        scissor(&mut pass, rect);
                        pass.set_bind_group(0, dual.as_ref().unwrap_or(&self.stroke_bind), &[]);
                        pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                        pass.draw(0..3, 0..1);
                    }
                }
            }
        }
        drop(render_pass(encoder, "stroke clear", &self.stroke_view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT)));
        self.clear_built_up(encoder, &self.stroke);
        self.dirty = self.dirty.union(rect);
    }

    /// Before layers are blended into `lower` (the layer, and its opacity): each of `masks`
    /// applied to its layer, and `lower` made as it showed: through its mask and at its opacity,
    /// or, with `drop_lower` (it was hidden), its pixels let go. Returns what the step keeps: each
    /// layer changed so whole as it was (once each: a second copy would put back a changed one),
    /// else `lower` where the merge can change it (`within`; nothing for an empty box).
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn before_merge(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        (lower, opacity): (LayerId, f32),
        masks: &[(LayerId, LayerId)],
        drop_lower: bool,
        within: Option<IRect>,
    ) -> Vec<Snapshot> {
        let mut pixels: Vec<Snapshot> = masks
            .iter()
            .filter(|&&(layer, _)| layer != lower)
            .filter_map(|&(layer, mask)| self.masked_in_place(device, encoder, uniforms, layer, mask))
            .collect();
        let lower_mask = masks.iter().find(|&&(layer, _)| layer == lower).map(|&(_, mask)| mask);
        let opacity = opacity.clamp(0.0, 1.0);
        if drop_lower || lower_mask.is_some() || opacity < 1.0 {
            pixels.extend(self.snapshot(device, encoder, lower, self.full()));
            if drop_lower {
                if let Some(layer) = self.layers.get(&lower) {
                    let clear = wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT);
                    drop(render_pass(encoder, "hidden let go", &layer.view, clear));
                }
            } else {
                self.redraw_in_place(device, encoder, uniforms, lower, lower_mask, opacity);
            }
        } else {
            pixels.extend(self.snapshot(device, encoder, lower, within.unwrap_or(self.full())));
        }
        pixels
    }

    /// Layer `upper` of `doc` blended into layer `lower` with its opacity, mode and clipping: a
    /// layer clipped to `lower` as in their clipping group (onto it as if it were opaque, its
    /// alpha kept), one clipped to the same base as `lower` as it is (clipped with `lower` after),
    /// one clipped elsewhere through its base's alpha.
    pub(crate) fn blend_layer_into(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        doc: &Document,
        upper: LayerId,
        lower: LayerId,
    ) -> Option<()> {
        let settings = doc.layer(upper)?;
        let base = doc.clip_base(upper);
        let clipped = match base {
            None => CLIP_NONE,
            Some(b) if b == lower => CLIP_ATOP,
            Some(b) if doc.clip_base(lower) == Some(b) => CLIP_NONE,
            Some(_) => CLIP_BY_BASE,
        };
        let clip = base.filter(|_| clipped == CLIP_BY_BASE).and_then(|id| self.layers.get(&id));
        let up = self.layers.get(&upper)?;
        let uniform = LayerUniform {
            opacity: if settings.visible { settings.opacity.clamp(0.0, 1.0) } else { 0.0 },
            blend: settings.blend.id(),
            clipped,
            ..Default::default()
        };
        self.blend_down(device, encoder, uniforms, &up.view, clip.map(|c| &c.view), uniform, lower)
    }

    /// The clipping group of `base` of `doc` and the layers clipped to it (`clipped`, their masks
    /// applied already) composited as they show, and blended into layer `into` with the base's
    /// opacity and mode.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn draw_clip_group_onto(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        doc: &Document,
        base: LayerId,
        clipped: &[LayerId],
        into: LayerId,
    ) -> Option<()> {
        let settings = doc.layer(base)?;
        let base_view = &self.layers.get(&base)?.view;
        let full = self.full();
        let none = &self.stroke_view;
        let (texture, view) = self.clip_group.get(device);
        let inner = Stack { first: (texture, view), second: Second::OnDemand(&self.clip_group_second, device) };
        let mut at = 0;
        let written = LayerUniform { clipped: CLIP_BASE, ..Default::default() };
        self.draw_onto(
            device,
            encoder,
            uniforms,
            full,
            base_view,
            None,
            none,
            written,
            BlendMode::Normal,
            &inner,
            &mut at,
        );
        for &id in clipped {
            let Some((layer, gpu_layer)) = doc.layer(id).zip(self.layers.get(&id)) else { continue };
            if !layer.visible {
                continue;
            }
            let uniform = LayerUniform {
                opacity: layer.opacity.clamp(0.0, 1.0),
                blend: layer.blend.id(),
                clipped: CLIP_ATOP,
                ..Default::default()
            };
            self.draw_onto(
                device,
                encoder,
                uniforms,
                full,
                &gpu_layer.view,
                None,
                none,
                uniform,
                layer.blend,
                &inner,
                &mut at,
            );
        }
        let view = if at == 0 { view } else { inner.second().1 };
        let uniform = LayerUniform {
            opacity: if settings.visible { settings.opacity.clamp(0.0, 1.0) } else { 0.0 },
            blend: settings.blend.id(),
            ..Default::default()
        };
        self.blend_down(device, encoder, uniforms, view, None, uniform, into)
    }

    /// `upper` blended into layer `lower` (through `clip`, as `uniform` says), by way of the
    /// scratch texture.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn blend_down(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        upper: &wgpu::TextureView,
        clip: Option<&wgpu::TextureView>,
        uniform: LayerUniform,
        lower: LayerId,
    ) -> Option<()> {
        let low = self.layers.get(&lower)?;
        let offset = uniforms.push(&uniform);
        let bind = self.quad(device, [upper, &self.stroke_view, clip.unwrap_or(&self.stroke_view), &low.view]);
        let (scratch, scratch_view) = self.scratch.get(device);
        let mut pass = render_pass(encoder, "merge", scratch_view, wgpu::LoadOp::Load);
        pass.set_pipeline(&self.pipelines.blend);
        pass.set_bind_group(0, &bind, &[]);
        pass.set_bind_group(1, &self.uniform_bind, &[offset]);
        pass.draw(0..3, 0..1);
        drop(pass);
        encoder.copy_texture_to_texture(
            copy_at(scratch, 0, 0),
            copy_at(&low.texture, 0, 0),
            extent(self.width, self.height),
        );
        Some(())
    }

    /// Layer `layer` multiplied by mask `mask` (its brightness), by way of the scratch texture;
    /// returns what the layer was.
    pub(crate) fn masked_in_place(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        layer: LayerId,
        mask: LayerId,
    ) -> Option<Snapshot> {
        let snapshot = self.snapshot(device, encoder, layer, self.full());
        self.redraw_in_place(device, encoder, uniforms, layer, Some(mask), 1.0)?;
        snapshot
    }

    /// Layer `layer` drawn again in place through `mask` (its brightness), if any, and at
    /// `opacity`, by way of the scratch texture.
    pub(crate) fn redraw_in_place(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        layer: LayerId,
        mask: Option<LayerId>,
        opacity: f32,
    ) -> Option<()> {
        let target = self.layers.get(&layer)?;
        let none = &self.stroke_view;
        let mask_view = match mask {
            Some(m) => Some(&self.layers.get(&m)?.view),
            None => None,
        };
        let masked = if mask_view.is_some() { 1.0 } else { 0.0 };
        let offset = uniforms.push(&LayerUniform { opacity, masked, ..Default::default() });
        let bind = self.quad_masked(device, [&target.view, none, none, none], mask_view.unwrap_or(none), none);
        let (scratch, scratch_view) = self.scratch.get(device);
        let mut pass = render_pass(encoder, "redraw", scratch_view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
        pass.set_pipeline(&self.pipelines.layer);
        pass.set_bind_group(0, &bind, &[]);
        pass.set_bind_group(1, &self.uniform_bind, &[offset]);
        pass.draw(0..3, 0..1);
        drop(pass);
        encoder.copy_texture_to_texture(
            copy_at(scratch, 0, 0),
            copy_at(&target.texture, 0, 0),
            extent(self.width, self.height),
        );
        Some(())
    }

    /// Group `group` of `doc` composited and copied into layer `into` (a new layer).
    pub(crate) fn flatten(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        doc: &Document,
        group: LayerId,
        into: LayerId,
    ) -> Option<()> {
        let full = self.full();
        self.composite_group(device, doc, encoder, uniforms, full, group)?;
        let (textures, _) = self.groups.get(doc.depth(group))?;
        let target = self.layers.get(&into)?;
        encoder.copy_texture_to_texture(
            copy_at(&textures[0], 0, 0),
            copy_at(&target.texture, 0, 0),
            extent(self.width, self.height),
        );
        Some(())
    }

    /// Group `group` of `doc` composited and blended into layer `into` with the group's opacity
    /// and mode.
    pub(crate) fn draw_group_onto(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        doc: &Document,
        group: LayerId,
        into: LayerId,
    ) -> Option<()> {
        let settings = doc.layer(group)?;
        let view = self.composite_group(device, doc, encoder, uniforms, self.full(), group)?;
        let uniform = LayerUniform {
            opacity: if settings.visible { settings.opacity.clamp(0.0, 1.0) } else { 0.0 },
            blend: settings.blend.id(),
            ..Default::default()
        };
        self.blend_down(device, encoder, uniforms, view, None, uniform, into)
    }

    /// Makes sure there is a pair of group textures for each of `depth` levels.
    pub(crate) fn make_groups(&mut self, device: &wgpu::Device, depth: usize) {
        while self.groups.len() < depth {
            let textures = [
                texture(device, "group", self.width, self.height, LAYER_FORMAT),
                texture(device, "group 2", self.width, self.height, LAYER_FORMAT),
            ];
            let views = [
                textures[0].create_view(&wgpu::TextureViewDescriptor::default()),
                textures[1].create_view(&wgpu::TextureViewDescriptor::default()),
            ];
            self.groups.push((textures, views));
        }
    }

    /// Copies a region of a layer aside.
    pub(crate) fn snapshot(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        id: LayerId,
        rect: IRect,
    ) -> Option<Snapshot> {
        let layer = self.layers.get(&id)?;
        let rect = rect.intersect(self.full());
        if rect.is_empty() {
            return None;
        }
        // the whole layer: into a texture let go, if one is kept, else one that can be kept when
        // this step is let go in turn
        if rect == self.full() {
            let texture =
                self.take_spare().unwrap_or_else(|| texture(device, "undo", self.width, self.height, LAYER_FORMAT));
            encoder.copy_texture_to_texture(
                copy_at(&layer.texture, 0, 0),
                copy_at(&texture, 0, 0),
                extent(self.width, self.height),
            );
            return Some(Snapshot::on_gpu(id, rect, texture));
        }
        Some(Snapshot::on_gpu(id, rect, region_copy(device, encoder, &layer.texture, rect)))
    }

    /// Applies an undo or redo step and returns its inverse: the pixels and document it replaced.
    pub(crate) fn apply_step(&mut self, gpu: &Gpu, encoder: &mut wgpu::CommandEncoder, doc: &mut Document, step: Step) -> Step {
        let device = &gpu.device;
        let made = step.made;
        let name = step.name.clone();
        let step_flip = step.flip;
        let stores = self.swap_stores(step.stores);
        let mut pixels = Vec::with_capacity(step.pixels.len());
        // what the history's textures hold changes: packing under way reads what they held
        self.history_generation = self.history_generation.wrapping_add(1);
        for s in step.pixels {
            // (a step's pixels kept compressed are back on the GPU by now: see `Canvas::unpacked`)
            let Some(texture) = s.texture().cloned() else {
                log::error!("an undo step's pixels couldn't be brought back");
                continue;
            };
            self.restore_all(gpu, [s.layer], true);
            let rect = s.rect.intersect(self.full());
            if let Some(layer) = self.layers.get(&s.layer).filter(|_| rect == s.rect && !rect.is_empty()) {
                // swapped through the scratch texture: the layer's pixels there go into the step's
                // own texture as the step back, nothing made
                let (scratch, _) = self.scratch.get(device);
                let size = extent(rect.width(), rect.height());
                encoder.copy_texture_to_texture(
                    copy_at(&layer.texture, rect.x0, rect.y0),
                    copy_at(scratch, 0, 0),
                    size,
                );
                encoder.copy_texture_to_texture(
                    copy_at(&texture, 0, 0),
                    copy_at(&layer.texture, rect.x0, rect.y0),
                    size,
                );
                encoder.copy_texture_to_texture(copy_at(scratch, 0, 0), copy_at(&texture, 0, 0), size);
                pixels.push(s);
            } else if let Some(layer) = self.layers.get(&s.layer) {
                if let Some(current) = self.snapshot(device, encoder, s.layer, s.rect) {
                    pixels.push(current);
                }
                encoder.copy_texture_to_texture(
                    copy_at(&texture, 0, 0),
                    copy_at(&layer.texture, s.rect.x0, s.rect.y0),
                    extent(s.rect.width(), s.rect.height()),
                );
            }
            self.dirty = self.dirty.union(rect);
        }
        let replaced = step.doc.map(|before| Box::new(std::mem::replace(doc, *before)));
        if let Some(replaced) = &replaced {
            // no layer it numbered numbered again: its pixels may still be held for redo
            doc.keep_ids_from(replaced);
            // the layer shown alone as it was (showing one alone is a step of its own), if it
            // is still there
            doc.solo = doc.solo.filter(|s| doc.layer(*s).is_some());
            self.dirty = self.full();
        }
        let selection = step.selection.map(|before| {
            let now = self.selection.take().map(|s| s.coverage);
            self.put_selection(device, &gpu.queue, before.map(std::borrow::Cow::Owned));
            now
        });
        // put back last first, so pixels that several snapshots cover end as the first left them
        pixels.reverse();
        if let Some((layers, horizontal)) = &step_flip {
            self.restore_all(gpu, layers.iter().copied(), true);
            self.encode_flip(device, encoder, layers, *horizontal);
            self.dirty = self.full();
        }
        Step { made, name, doc: replaced, pixels, selection, flip: step_flip, stores }
    }

    /// Draws layer `id` inverted within `rect`, from its copy there in the scratch texture.
    pub(crate) fn encode_invert(&self, device: &wgpu::Device, encoder: &mut wgpu::CommandEncoder, id: LayerId, rect: IRect) {
        let Some(layer) = self.layers.get(&id) else { return };
        let (scratch, scratch_view) = self.scratch.get(device);
        encoder.copy_texture_to_texture(
            copy_at(&layer.texture, rect.x0, rect.y0),
            copy_at(scratch, rect.x0, rect.y0),
            extent(rect.width(), rect.height()),
        );
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("invert"),
            layout: &self.pipelines.bounds_layout,
            entries: &[wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(scratch_view) }],
        });
        let mut pass = render_pass(encoder, "invert", &layer.view, wgpu::LoadOp::Load);
        scissor(&mut pass, rect);
        pass.set_pipeline(&self.pipelines.invert);
        pass.set_bind_group(0, &bind, &[]);
        pass.draw(0..3, 0..1);
    }

    /// Draws `layers` mirrored (across when `horizontal`, else down) into themselves.
    pub(crate) fn encode_flip(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        layers: &[LayerId],
        horizontal: bool,
    ) {
        let pipeline = if horizontal { &self.pipelines.flip_across } else { &self.pipelines.flip_down };
        // each layer drawn mirrored from a copy of itself in the scratch texture (a pass
        // can't read what it draws into), one after another
        let (scratch, scratch_view) = self.scratch.get(device);
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("flip"),
            layout: &self.pipelines.bounds_layout,
            entries: &[wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(scratch_view) }],
        });
        for id in layers {
            let Some(layer) = self.layers.get(id) else { continue };
            encoder.copy_texture_to_texture(
                copy_at(&layer.texture, 0, 0),
                copy_at(scratch, 0, 0),
                extent(self.width, self.height),
            );
            let mut pass = render_pass(encoder, "flip", &layer.view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, &bind, &[]);
            pass.draw(0..3, 0..1);
        }
    }

    /// Draws the layer being edited from its copy through `filter`, kept to the selection.
    pub(crate) fn filter(&mut self, gpu: &Gpu, encoder: &mut wgpu::CommandEncoder, uniforms: &mut UniformData, filter: Filter) {
        let Some(t) = &self.transforming else { return };
        let Some(layer) = self.layers.get(&t.layer) else { return };
        let (w, h) = (self.width as f32, self.height as f32);
        let full = [w, h, if t.masked { 1.0 } else { 0.0 }, if self.filter_keeps_alpha { 1.0 } else { 0.0 }];
        let values = |a: [f32; 4], b: [f32; 4]| FilterUniform { a, b, size: full, c: [0.0; 4] };
        // most adjustments are one pass from the copy into the layer
        let (pass, uniform) = match filter {
            Filter::Hsb { hue, saturation, brightness } => {
                ("fs_hsb", values([hue, saturation, brightness, 0.0], [0.0; 4]))
            }
            Filter::Sharpen { amount } => ("fs_sharpen", values([amount, 0.0, 0.0, 0.0], [0.0; 4])),
            Filter::Noise { amount, frequency, octaves, turbulence, kind, multi, added } => {
                let flags = [kind.min(2) as f32, f32::from(u8::from(multi)), f32::from(u8::from(added)), 0.0];
                ("fs_noise", values([amount, frequency, octaves.clamp(1, 8) as f32, turbulence], flags))
            }
            Filter::Motion { along: [x, y] } => {
                let count = (x.hypot(y)).clamp(1.0, 128.0);
                ("fs_streak", values([x, y, count, 1.0], [0.0; 4]))
            }
            Filter::Zoom { centre: [x, y], amount, toward: [tx, ty] } => {
                ("fs_zoom", values([amount, 0.0, 0.0, 0.0], [x, y, tx, ty]))
            }
            Filter::Chromatic { centre: [x, y], amount, displace, offset: [ox, oy], blur, falloff, transparency } => {
                // displaced, the centre's place carries the offset and the amount says so
                let (b, a) = if displace { ([ox, oy, -1.0, 0.0], 0.0) } else { ([x, y, 0.0, 0.0], amount) };
                ("fs_chroma", values([a, blur, falloff, transparency], b))
            }
            Filter::Halftone { pitch, kind } => {
                ("fs_halftone", values([pitch.max(2.0), kind.min(2) as f32, 0.0, 0.0], [0.0; 4]))
            }
            Filter::Blur { sigma } => ("fs_up", values([0.0; 4], [sigma, 0.0, 0.0, 0.0])),
            Filter::Bloom { sigma, amount, threshold, transition, burn } => {
                ("fs_bloom", values([amount, threshold, transition, burn], [sigma, 0.0, 0.0, 0.0]))
            }
            Filter::Curves => ("fs_curves", values([0.0; 4], [0.0; 4])),
            Filter::Liquify { amount } => {
                let [across, down] = self.liquify.as_ref().map_or([1.0; 2], |l| l.scale);
                ("fs_liquified", values([amount, across, down, 0.0], [0.0; 4]))
            }
            Filter::Clone { offset: [x, y] } => ("fs_clone", values([x, y, 0.0, 0.0], [0.0; 4])),
            Filter::Glitch { kind, amount, values: [v0, v1, v2], zoom, seed } => {
                ("fs_glitch", values([kind.min(3) as f32, amount, v0, v1], [v2, zoom, seed, 0.0]))
            }
            Filter::GradientMap { amount } => ("fs_gradient", values([amount, 0.0, 0.0, 0.0], [0.0; 4])),
            Filter::ColorBalance { shadows: [s0, s1, s2], midtones: [m0, m1, m2], highlights: [h0, h1, h2] } => (
                "fs_balance",
                FilterUniform { a: [s0, s1, s2, 0.0], b: [m0, m1, m2, 0.0], size: full, c: [h0, h1, h2, 0.0] },
            ),
        };
        let blurred = match filter {
            Filter::Blur { sigma } | Filter::Bloom { sigma, .. } => self.blur_copy(gpu, encoder, uniforms, sigma),
            _ => None,
        };
        // liquify in integers shows the layer through its field's bits
        let bits = self.pipelines.fragments.as_ref().and_then(|f| f.liquify.as_ref()).map(|l| &l.shown);
        let pipeline = match (filter, bits) {
            (Filter::Liquify { .. }, Some(shown)) if self.liquifies_in_integers() => Some(shown),
            _ => self.pipelines.filters.get(pass),
        };
        let Some(pipeline) = pipeline else { return };
        let offset = uniforms.push(&uniform);
        // the last pass reads the copy, or the blurred copy, and takes the selection from the edit
        let source = blurred.as_ref().map(|view| self.adjustment_bind(&gpu.device, view, t.masked));
        let mut out = render_pass(encoder, "adjustment", &layer.view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
        out.set_pipeline(pipeline);
        out.set_bind_group(0, source.as_ref().unwrap_or(&t.bind), &[]);
        out.set_bind_group(1, &self.uniform_bind, &[offset]);
        // liquify reads its field where the others read the table
        let last = match (filter, &self.liquify) {
            (Filter::Liquify { .. }, Some(field)) => &field.bind,
            _ => &t.before_bind,
        };
        out.set_bind_group(2, last, &[]);
        out.draw(0..3, 0..1);
        drop(out);
        // liquify pushes what is edited along with it (a layer's mask) the same way
        if let (Filter::Liquify { .. }, Some(field)) = (filter, &self.liquify) {
            for more in &self.transform_more {
                let Some(target) = self.layers.get(&more.layer) else { continue };
                let clear = wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT);
                let mut along = render_pass(encoder, "liquify along", &target.view, clear);
                along.set_pipeline(pipeline);
                along.set_bind_group(0, &more.bind, &[]);
                along.set_bind_group(1, &self.uniform_bind, &[offset]);
                along.set_bind_group(2, &field.bind, &[]);
                along.draw(0..3, 0..1);
            }
        }
        self.dirty = self.full();
    }

    /// Closes the liquify field's open step (see [`Canvas::liquify_mark`]).
    pub(crate) fn mark_field(&mut self, device: &wgpu::Device, encoder: &mut wgpu::CommandEncoder) {
        let most = self.history_most() / 2;
        let Some(field) = self.liquify.as_mut() else { return };
        let (w, h) = (field.texture.width(), field.texture.height());
        let area = std::mem::replace(&mut field.changed, IRect::EMPTY).intersect(IRect::from_size(w, h));
        let format = field.texture.format();
        // what was there before is the marked field's; the marked field then follows the field
        let pixels = (!area.is_empty()).then(|| {
            let before = texture(device, "liquify step", area.width(), area.height(), format);
            let size = extent(area.width(), area.height());
            encoder.copy_texture_to_texture(copy_at(&field.marked, area.x0, area.y0), before.as_image_copy(), size);
            encoder.copy_texture_to_texture(
                copy_at(&field.texture, area.x0, area.y0),
                copy_at(&field.marked, area.x0, area.y0),
                size,
            );
            before
        });
        field.undo.push(FieldStep { area, pixels });
        field.redo.clear();
        let per_pixel = u64::from(format.block_copy_size(None).unwrap_or(8));
        let mut bytes: u64 = field.undo.iter().map(|s| s.bytes(per_pixel)).sum();
        while field.undo.len() > MAX_UNDO || (bytes > most && field.undo.len() > 1) {
            let dropped = field.undo.remove(0);
            bytes = bytes.saturating_sub(dropped.bytes(per_pixel));
        }
    }

    /// Takes the liquify field's open step back (see [`Canvas::liquify_take_back`]): where it
    /// changed, the field as it was marked.
    pub(crate) fn take_back_field(&mut self, encoder: &mut wgpu::CommandEncoder) {
        let Some(field) = self.liquify.as_mut() else { return };
        let (w, h) = (field.texture.width(), field.texture.height());
        let area = std::mem::replace(&mut field.changed, IRect::EMPTY).intersect(IRect::from_size(w, h));
        if !area.is_empty() {
            encoder.copy_texture_to_texture(
                copy_at(&field.marked, area.x0, area.y0),
                copy_at(&field.texture, area.x0, area.y0),
                extent(area.width(), area.height()),
            );
        }
        self.dirty = self.full();
    }

    /// The liquify field's last closed step undone (`undo`), or the last undone done again: the
    /// part it changed swapped with how it was on the step's other side.
    pub(crate) fn liquify_history(&mut self, device: &wgpu::Device, encoder: &mut wgpu::CommandEncoder, undo: bool) {
        let Some(field) = self.liquify.as_mut() else { return };
        let step = if undo { field.undo.pop() } else { field.redo.pop() };
        let Some(FieldStep { area, pixels }) = step else { return };
        let format = field.texture.format();
        let pixels = pixels.map(|other| {
            let now = texture(device, "liquify step", area.width(), area.height(), format);
            let size = extent(area.width(), area.height());
            encoder.copy_texture_to_texture(copy_at(&field.texture, area.x0, area.y0), now.as_image_copy(), size);
            encoder.copy_texture_to_texture(other.as_image_copy(), copy_at(&field.texture, area.x0, area.y0), size);
            encoder.copy_texture_to_texture(other.as_image_copy(), copy_at(&field.marked, area.x0, area.y0), size);
            now
        });
        let swapped = FieldStep { area, pixels };
        if undo {
            field.redo.push(swapped);
        } else {
            field.undo.push(swapped);
        }
        self.dirty = self.full();
    }

    /// A step of the liquify brush: `stamps` added up over the part of the field they reach, the
    /// field there carried along by that and `keep` of it left. No stamps: the whole field.
    pub(crate) fn liquify_step(
        &mut self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        stamps: &[LiquifyStamp],
        keep: f32,
    ) {
        // the part the stamps reach is the open undo step's, in floats or integers alike
        let to_i32 = |v: u32| i32::try_from(v).unwrap_or(i32::MAX);
        if let Some(field) = self.liquify.as_mut() {
            let size = (field.texture.width(), field.texture.height());
            let [x0, y0, x1, y1] = liquify_reach(stamps, size, field.scale);
            if x1 > x0 && y1 > y0 {
                field.changed = field.changed.union(IRect::new(to_i32(x0), to_i32(y0), to_i32(x1), to_i32(y1)));
            }
        }
        // where the GPU can't draw into floats, in integers holding their bits
        if self.liquifies_in_integers() {
            self.liquify_step_in_integers(gpu, encoder, uniforms, stamps, keep);
            return;
        }
        let device = &gpu.device;
        let (canvas_w, canvas_h) = (self.width as f32, self.height as f32);
        let chosen = self.selection.as_ref().map(|s| &s.view);
        let pipelines = Arc::clone(&self.pipelines);
        let Some(pipes) = pipelines.liquify.as_ref() else { return };
        let Some(field) = self.liquify.as_mut() else { return };
        let (width, height) = (field.texture.width(), field.texture.height());
        let [across, down] = field.scale;
        let [x0, y0, x1, y1] = liquify_reach(stamps, (width, height), field.scale);
        let (area_w, area_h) = (x1.saturating_sub(x0), y1.saturating_sub(y0));
        if area_w == 0 || area_h == 0 {
            return;
        }
        let scratch = match field.scratch.take() {
            Some(s) if s.width >= area_w && s.height >= area_h => s,
            old => {
                // each way up to the next size a little over, to do this seldom
                let wide = old.as_ref().map_or(0, |s| s.width).max(area_w.next_multiple_of(128)).min(width);
                let high = old.as_ref().map_or(0, |s| s.height).max(area_h.next_multiple_of(128)).min(height);
                let sums = texture(device, "liquify sums", wide, high, LIQUIFY_SUMS_FORMAT);
                let next = texture(device, "liquify step", wide, high, pipes.format);
                LiquifyScratch {
                    width: wide,
                    height: high,
                    sums: sums.create_view(&wgpu::TextureViewDescriptor::default()),
                    next_view: next.create_view(&wgpu::TextureViewDescriptor::default()),
                    next,
                }
            }
        };
        let area = [x0 as f32, y0 as f32, area_w as f32, area_h as f32];
        let canvas = [canvas_w, canvas_h, across, down];
        let clear = wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT);
        let mut pass = render_pass(encoder, "liquify stamps", &scratch.sums, clear);
        pass.set_viewport(0.0, 0.0, area[2], area[3], 0.0, 1.0);
        pass.set_pipeline(&pipes.stamp);
        for st in stamps {
            let (mode, narrow) = st.mode.code();
            let offset = uniforms.push(&LiquifyUniform {
                stamp: [st.centre[0], st.centre[1], st.radius.max(LIQUIFY_LEAST_RADIUS), st.amount],
                way: [st.way[0], st.way[1], mode, narrow],
                canvas,
                area,
            });
            pass.set_bind_group(0, &self.uniform_bind, &[offset]);
            pass.draw(0..6, 0..1);
        }
        drop(pass);
        let reads = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("liquify step"),
            layout: &pipes.layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&scratch.sums) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(&field.view) },
                wgpu::BindGroupEntry {
                    binding: 2,
                    resource: wgpu::BindingResource::TextureView(chosen.unwrap_or(&self.everything)),
                },
            ],
        });
        let offset = uniforms.push(&LiquifyUniform {
            stamp: [keep, 0.0, 0.0, 0.0],
            way: [0.0, 0.0, 0.0, if chosen.is_some() { 1.0 } else { 0.0 }],
            canvas,
            area,
        });
        let mut pass = render_pass(encoder, "liquify step", &scratch.next_view, clear);
        pass.set_viewport(0.0, 0.0, area[2], area[3], 0.0, 1.0);
        pass.set_pipeline(&pipes.step);
        pass.set_bind_group(0, &self.uniform_bind, &[offset]);
        pass.set_bind_group(1, &reads, &[]);
        pass.draw(0..3, 0..1);
        drop(pass);
        encoder.copy_texture_to_texture(
            scratch.next.as_image_copy(),
            wgpu::TexelCopyTextureInfo {
                texture: &field.texture,
                mip_level: 0,
                origin: wgpu::Origin3d { x: x0, y: y0, z: 0 },
                aspect: wgpu::TextureAspect::All,
            },
            extent(area_w, area_h),
        );
        field.scratch = Some(scratch);
    }

    /// `view` bound as an adjustment's source, with where it shows when `masked`: what is painted
    /// on, or else the selection.
    pub(crate) fn adjustment_bind(&self, device: &wgpu::Device, view: &wgpu::TextureView, masked: bool) -> wgpu::BindGroup {
        let painted = self.pencil.as_ref().map(|p| &p.view);
        let chosen =
            painted.or(self.selection.as_ref().map(|s| &s.view)).filter(|_| masked).unwrap_or(&self.everything);
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("adjustment pass"),
            layout: &self.pipelines.transform_layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(view) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&self.sampler) },
                wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(chosen) },
            ],
        })
    }

    /// The edit's copy blurred by `sigma` canvas pixels, at a smaller size for wide blurs (halved
    /// until the blur is a few pixels wide where it runs); none when the blur is too small to do.
    pub(crate) fn blur_copy(
        &self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        sigma: f32,
    ) -> Option<wgpu::TextureView> {
        let device = &gpu.device;
        let t = self.transforming.as_ref()?;
        let (w, h) = (self.width, self.height);
        let mut scale = 1u32;
        while sigma / scale as f32 > 8.0 && scale < 32 && w / (scale * 2) > 0 && h / (scale * 2) > 0 {
            scale *= 2;
        }
        let small = |s: u32| (w.div_ceil(s).max(1), h.div_ceil(s).max(1));
        let mut pass = |encoder: &mut wgpu::CommandEncoder,
                        target: &wgpu::TextureView,
                        fs: &str,
                        source: &wgpu::BindGroup,
                        uniform: FilterUniform| {
            let Some(pipeline) = self.pipelines.filters.get(fs) else { return };
            let offset = uniforms.push(&uniform);
            let mut p = render_pass(encoder, "adjustment", target, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
            p.set_pipeline(pipeline);
            p.set_bind_group(0, source, &[]);
            p.set_bind_group(1, &self.uniform_bind, &[offset]);
            p.set_bind_group(2, &t.before_bind, &[]);
            p.draw(0..3, 0..1);
        };
        let mut current: Option<wgpu::TextureView> = None;
        let mut level = 1u32;
        while level < scale {
            level *= 2;
            let (lw, lh) = small(level);
            let next = self.blur_texture(device, "adjustment level", lw, lh, 0);
            let uniform =
                FilterUniform { a: [0.0; 4], b: [0.0; 4], size: [lw as f32, lh as f32, 0.0, 0.0], c: [0.0; 4] };
            let source = current.as_ref().map(|v| self.adjustment_bind(device, v, false));
            pass(encoder, &next, "fs_down", source.as_ref().unwrap_or(&t.bind), uniform);
            current = Some(next);
        }
        let s_sigma = sigma / scale as f32;
        if s_sigma < 0.3 {
            return current;
        }
        let (lw, lh) = small(scale);
        let reach = (s_sigma * 3.0).ceil().min(64.0);
        let size = [lw as f32, lh as f32, 0.0, 0.0];
        // the canvas's size: the first pass's into the scratch texture, read straight back (one
        // texture the canvas's size fewer to make: a tablet takes 15-25 ms for each)
        let across = if (lw, lh) == (w, h) {
            self.scratch.get(device).1.clone()
        } else {
            self.blur_texture(device, "adjustment across", lw, lh, 1)
        };
        let down = self.blur_texture(device, "adjustment down", lw, lh, 2);
        let source = current.as_ref().map(|v| self.adjustment_bind(device, v, false));
        pass(
            encoder,
            &across,
            "fs_blur",
            source.as_ref().unwrap_or(&t.bind),
            FilterUniform { a: [1.0, 0.0, s_sigma, reach], b: [0.0; 4], size, c: [0.0; 4] },
        );
        let source = self.adjustment_bind(device, &across, false);
        pass(
            encoder,
            &down,
            "fs_blur",
            &source,
            FilterUniform { a: [0.0, 1.0, s_sigma, reach], b: [0.0; 4], size, c: [0.0; 4] },
        );
        Some(down)
    }

    /// A blur's working texture `width` x `height` for `part` (0 a halving, 1 across, 2 down),
    /// made the first time and kept until the adjustment ends.
    pub(crate) fn blur_texture(&self, device: &wgpu::Device, label: &str, width: u32, height: u32, part: u8) -> wgpu::TextureView {
        let mut kept = self.blur_textures.lock().unwrap_or_else(PoisonError::into_inner);
        kept.entry((width, height, part))
            .or_insert_with(|| {
                texture(device, label, width, height, LAYER_FORMAT).create_view(&wgpu::TextureViewDescriptor::default())
            })
            .clone()
    }

    /// A new step: it joins the history and anything undone can no longer be redone.
    pub(crate) fn push_step(&mut self, mut step: Step) {
        // part of an edit held quietly: the step made as it is released holds it
        let held = self.held.as_ref().filter(|_| self.held_quietly).map(|h| h.layer);
        if let Some(layer) = held
            && step.doc.is_none()
            && step.selection.is_none()
            && step.flip.is_none()
            && step.pixels.iter().all(|s| s.layer == layer)
        {
            self.keep_spare_snapshot(step);
            return;
        }
        // what it changes keeping a store, other than with it, dries
        self.dry_changed(&mut step);
        self.steps_made += 1;
        step.made = self.steps_made;
        if step.name.is_none() {
            step.name = self.next_name.take();
        }
        for dropped in std::mem::take(&mut self.redo) {
            self.keep_spare_snapshot(dropped);
        }
        self.undo.push(step);
        // what it holds on the GPU and what it keeps compressed alike
        let held = |s: &Step| s.bytes() + s.packed_bytes();
        let mut bytes: u64 = self.undo.iter().map(held).sum();
        let most = self.history_most();
        while self.undo.len() > MAX_UNDO || (bytes > most && self.undo.len() > 1) {
            let dropped = self.undo.remove(0);
            bytes = bytes.saturating_sub(held(&dropped));
            self.keep_spare_snapshot(dropped);
        }
    }

    /// A step let go: its textures the canvas's size kept as spares.
    pub(crate) fn keep_spare_snapshot(&self, step: Step) {
        for s in step.pixels {
            if let Held::Texture(texture) = s.pixels {
                self.keep_spare(texture);
            }
        }
    }

    /// `texture` kept for reuse if it is the canvas's size and can be a layer, and fewer than
    /// [`SPARES_KEPT`] are kept.
    pub(crate) fn keep_spare(&self, texture: wgpu::Texture) {
        let layer_like = wgpu::TextureUsages::TEXTURE_BINDING
            | wgpu::TextureUsages::RENDER_ATTACHMENT
            | wgpu::TextureUsages::COPY_SRC
            | wgpu::TextureUsages::COPY_DST;
        let mut spares = self.spares.lock().unwrap_or_else(PoisonError::into_inner);
        if (texture.width(), texture.height()) == (self.width, self.height)
            && texture.format() == LAYER_FORMAT
            && texture.usage().contains(layer_like)
            && spares.len() < SPARES_KEPT
        {
            spares.push(texture);
        }
    }

    /// A kept texture the canvas's size, if there is one (its pixels whatever they were).
    pub(crate) fn take_spare(&self) -> Option<wgpu::Texture> {
        self.spares.lock().unwrap_or_else(PoisonError::into_inner).pop()
    }
}
