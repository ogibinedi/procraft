//! Layer compositing, live caches, rule lists, remaking parked layers, display, and frosted glass on [`Canvas`].

use super::*;

impl Canvas {
    /// Composites the visible layers over the background inside `rect`, into `composite[0]`.
    /// Normal layers blend in place; other modes read what is below and write the other texture.
    pub(super) fn composite(
        &self,
        device: &wgpu::Device,
        doc: &Document,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        rect: IRect,
    ) {
        {
            let offset = uniforms.push(&self.background_uniform(doc));
            let mut pass = render_pass(encoder, "background", &self.composite_view, wgpu::LoadOp::Load);
            scissor(&mut pass, rect);
            pass.set_pipeline(&self.pipelines.solid);
            pass.set_bind_group(0, &self.stroke_bind, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.draw(0..3, 0..1);
        }
        // the stack bottom up; a group's layers go onto its depth's pair of textures first, and
        // the group onto the stack where its entry is
        let main = Stack {
            first: (&self.composite, &self.composite_view),
            second: Second::OnDemand(&self.composite_second, device),
        };
        if let Some(c) = &self.caches {
            // what is below as it was, what is worked on, then what is above as it was
            encoder.copy_texture_to_texture(
                copy_at(&c.below.texture, rect.x0, rect.y0),
                copy_at(&self.composite, rect.x0, rect.y0),
                extent(rect.width(), rect.height()),
            );
            let mut current = 0;
            self.composite_items(device, doc, encoder, uniforms, rect, &c.live, true, &main, &mut current);
            if !c.above_items.is_empty() {
                let uniform = LayerUniform {
                    opacity: 1.0,
                    blend: BlendMode::Normal.id(),
                    tint: Look::PLAIN.tint,
                    ..Default::default()
                };
                let view = &c.above.view;
                self.draw_onto(
                    device,
                    encoder,
                    uniforms,
                    rect,
                    view,
                    None,
                    &self.stroke_view,
                    uniform,
                    BlendMode::Normal,
                    &main,
                    &mut current,
                );
            }
            main.settle(encoder, rect, current);
            return;
        }
        let current = self.composite_members(device, doc, encoder, uniforms, rect, None, &main);
        main.settle(encoder, rect, current);
    }

    /// The top level's items composited live while caches are used (from the one holding the
    /// current layer up to a run of plain items at the top: normal, the first not clipped to what
    /// is below the run), with every layer in them; none while something else is under way
    /// (Animation Assist's frames, an adjustment, liquify, a transform, a stroke elsewhere).
    pub(super) fn live_items(&self, doc: &Document) -> Option<(Vec<LayerId>, std::ops::Range<usize>)> {
        if doc.animation.is_some() {
            return None;
        }
        let tops = doc.members(None);
        let mut top = doc.current();
        if doc.layer(top).is_some_and(|l| l.kind == LayerKind::Mask) {
            top = doc.layer(top)?.parent?;
        }
        for _ in 0..doc.layers().len() {
            match doc.group_of(top) {
                Some(g) => top = g,
                None => break,
            }
        }
        let mut start = tops.iter().position(|t| *t == top)?;
        // a layer clipped to another composites with it, in their clipping group
        if let Some(base) = doc.clip_base(top).filter(|_| doc.layer(top).is_some_and(|l| l.kind == LayerKind::Paint)) {
            start = tops.iter().position(|t| *t == base).unwrap_or(start);
        }
        let layer = |i: usize| tops.get(i).and_then(|t| doc.layer(*t));
        let mut end = tops.len();
        while end > start + 1 && tops.get(end - 1).is_some_and(|t| plain(doc, *t)) {
            end -= 1;
        }
        while end < tops.len() && layer(end).is_some_and(|l| l.clipped) {
            end += 1;
        }
        let live: HashSet<LayerId> = tops.get(start..end)?.iter().flat_map(|t| item_layers(doc, *t)).collect();
        // what is being worked on (a stroke, a smudge, a transform or adjustment, a held edit)
        // must be among them
        let worked = self
            .active
            .map(|a| a.layer)
            .into_iter()
            .chain(self.smudge.as_ref().map(|s| s.layer))
            .chain(self.transforming.iter().chain(&self.transform_more).map(|t| t.layer))
            .chain(self.held.as_ref().map(|h| h.layer))
            .chain(self.fill_preview.filter(|_| self.selection.is_some()).map(|f| f.layer));
        if worked.into_iter().any(|id| !live.contains(&id)) {
            return None;
        }
        Some((tops, start..end))
    }

    /// Makes (or keeps) the caches the composite is made around, while they are used: what is
    /// below the items worked on and what is above them, each remade only when what it was made
    /// from changed.
    pub(super) fn ensure_caches(
        &mut self,
        gpu: &Gpu,
        doc: &Document,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
    ) {
        let device = &gpu.device;
        let live = self.live_items(doc).filter(|_| self.cache_composites);
        // nothing below or above what is worked on: no picture of it would save any compositing
        let alone = live.as_ref().is_some_and(|(tops, live)| live.start == 0 && live.end >= tops.len());
        if alone {
            self.remake = None;
        }
        let Some((tops, live)) = live.filter(|_| !alone) else {
            // composited without them: their textures let go too, not kept for caches that may not
            // come back (a large canvas's four take hundreds of MB)
            self.caches = None;
            self.spare_caches = None;
            // everything shown composites: Animation Assist's frames around the current one, or
            // every layer
            let needed: Vec<LayerId> = match self.shown_frames(doc) {
                Some(shown) => shown.into_iter().collect(),
                None => doc.pixel_layer_ids().collect(),
            };
            self.restore_all(gpu, needed, false);
            return;
        };
        let below_items = tops.get(..live.start).unwrap_or_default().to_vec();
        let above_items = tops.get(live.end..).unwrap_or_default().to_vec();
        let below_key = items_key(doc, &below_items, true);
        let above_key = items_key(doc, &above_items, false);
        // a remake of these same caches goes on; of others, starts again
        let same = |r: &Remake| {
            r.below_items == below_items
                && r.below_key == below_key
                && r.above_items == above_items
                && r.above_key == above_key
        };
        if self.remake.as_ref().is_some_and(same) {
            self.send_now(gpu, encoder, uniforms);
            self.go_on_remaking(gpu, doc);
            return;
        }
        // or made in the textures of those let go or of a remake left off: what they hold is
        // made again
        let stale = self.remake.take().map(|r| r.caches).or_else(|| self.spare_caches.take()).map(|mut c| {
            c.below_items.clear();
            c.below_key = !below_key;
            c.above_items.clear();
            c.above_key = !above_key;
            c
        });
        let mut caches = self.caches.take().or(stale).unwrap_or_else(|| {
            // (on a device of little memory, with no second textures of their own)
            let paired = !self.low_memory();
            let (width, height) = (self.width, self.height);
            Caches {
                live: Vec::new(),
                live_layers: HashSet::new(),
                below: CachePicture::new(device, "below", width, height, paired),
                below_items: Vec::new(),
                // nothing matches until made
                below_key: !below_key,
                above: CachePicture::new(device, "above", width, height, paired),
                above_items: Vec::new(),
                above_key: !above_key,
            }
        });
        let full = self.full();
        let remake_below = caches.below_items != below_items || caches.below_key != below_key;
        let remake_above = caches.above_items != above_items || caches.above_key != above_key;
        // what is composited needs its layers: the items worked on (and what the first is clipped
        // to), and what is cached when it is made again
        let mut needed: Vec<LayerId> =
            tops.get(live.clone()).unwrap_or_default().iter().flat_map(|t| item_layers(doc, *t)).collect();
        needed.extend(tops.get(live.start).into_iter().flat_map(|first| clip_base_and_mask(doc, *first)));
        if remake_below {
            needed.extend(below_items.iter().flat_map(|t| item_layers(doc, *t)));
        }
        if remake_above {
            needed.extend(above_items.iter().flat_map(|t| item_layers(doc, *t)));
        }
        let most = self.most_resident();
        let missing = needed.iter().filter(|id| !self.layers.contains_key(*id)).count();
        if self.layers.len().saturating_add(missing) > most {
            // more than fit: remade a part at a time, a little each render, each part's layers let
            // go before the next comes back; the composite waits for it
            let mut keep: HashSet<LayerId> =
                tops.get(live.clone()).unwrap_or_default().iter().flat_map(|t| item_layers(doc, *t)).collect();
            keep.extend(tops.get(live.start).into_iter().flat_map(|first| clip_base_and_mask(doc, *first)));
            keep.extend(self.active.map(|a| a.layer));
            keep.extend(self.held.as_ref().map(|h| h.layer));
            self.restore_all(gpu, keep.iter().copied().collect::<Vec<_>>(), false);
            // the work so far first, so what is cached is made from it
            self.send_now(gpu, encoder, uniforms);
            let mut start = device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("remake") });
            let mut start_uniforms = UniformData::default();
            if remake_below {
                let offset = start_uniforms.push(&self.background_uniform(doc));
                let mut pass =
                    render_pass(&mut start, "below", &caches.below.view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
                pass.set_pipeline(&self.pipelines.solid);
                pass.set_bind_group(0, &self.stroke_bind, &[]);
                pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                pass.draw(0..3, 0..1);
            }
            if remake_above {
                drop(render_pass(
                    &mut start,
                    "above",
                    &caches.above.view,
                    wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                ));
            }
            self.write_uniforms(gpu, &start_uniforms);
            gpu.queue.submit([start.finish()]);
            caches.live = tops.get(live).unwrap_or_default().to_vec();
            caches.live_layers = caches.live.iter().flat_map(|t| item_layers(doc, *t)).collect();
            self.remake = Some(Remake {
                caches,
                below: remake_below.then_some((0, 0)),
                above: remake_above.then_some((0, 0)),
                below_items,
                below_key,
                above_items,
                above_key,
                keep,
                started: std::time::Instant::now(),
                slices: 0,
                largest_part: 0,
                restored: 0,
                restoring: std::time::Duration::ZERO,
            });
            self.go_on_remaking(gpu, doc);
            return;
        }
        self.restore_all(gpu, needed, false);
        if remake_below {
            let offset = uniforms.push(&self.background_uniform(doc));
            {
                let mut pass =
                    render_pass(encoder, "below", &caches.below.view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
                pass.set_pipeline(&self.pipelines.solid);
                pass.set_bind_group(0, &self.stroke_bind, &[]);
                pass.set_bind_group(1, &self.uniform_bind, &[offset]);
                pass.draw(0..3, 0..1);
            }
            let stack = caches.below.stack(&self.composite_second, device);
            let mut current = 0;
            self.composite_items(device, doc, encoder, uniforms, full, &below_items, true, &stack, &mut current);
            stack.settle(encoder, full, current);
            caches.below_items = below_items;
            caches.below_key = below_key;
        }
        if remake_above {
            drop(render_pass(encoder, "above", &caches.above.view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT)));
            let stack = caches.above.stack(&self.composite_second, device);
            let mut current = 0;
            self.composite_items(device, doc, encoder, uniforms, full, &above_items, true, &stack, &mut current);
            stack.settle(encoder, full, current);
            caches.above_items = above_items;
            caches.above_key = above_key;
        }
        caches.live = tops.get(live).unwrap_or_default().to_vec();
        caches.live_layers = caches.live.iter().flat_map(|t| item_layers(doc, *t)).collect();
        self.caches = Some(caches);
    }

    /// With Animation Assist on (and nothing else under way), the layers of the frames shown: the
    /// current (or played) one, its onion skins, and a background or foreground frame.
    pub(super) fn shown_frames(&self, doc: &Document) -> Option<HashSet<LayerId>> {
        let a = doc.animation.as_ref()?;
        let busy =
            self.transforming.is_some() || self.liquify.is_some() || self.pencil.is_some() || self.held.is_some();
        if busy || self.smudge.is_some() {
            return None;
        }
        let frames = animation::frames(doc);
        let focus = self.play_frame.or_else(|| animation::current_frame(doc))?;
        let shown: HashSet<LayerId> = frames
            .iter()
            .enumerate()
            .filter(|(i, _)| {
                !matches!(animation::look(a, *i, frames.len(), focus, self.play_frame.is_some()), FrameLook::Hidden)
            })
            .flat_map(|(_, f)| item_layers(doc, *f))
            .collect();
        Some(shown)
    }

    /// How many layers' textures fit the budget (any number without one).
    pub fn most_resident(&self) -> usize {
        if self.resident_budget == 0 {
            return usize::MAX;
        }
        // the history takes from the same memory, a layer's worth at a time
        let per = (u64::from(self.width) * u64::from(self.height) * 4).max(1);
        let room = self.resident_budget.saturating_sub(self.history_bytes() / per * per);
        most_resident(room.max(1), self.width, self.height)
    }

    /// Lets go of the textures of layers unchanged since parked (their compressed copies kept),
    /// but those in `keep`, as far as the next part of a remake wants room.
    pub(super) fn let_go_clean(&mut self, keep: &HashSet<LayerId>) {
        // only as many as the next part wants room for, needed least lately first: layers that
        // fit stay (letting many textures go at once holds a frame up)
        let most = self.most_resident();
        let wanted = most.saturating_sub(LET_GO_ROOM);
        if self.layers.len() <= wanted {
            return;
        }
        let mut clean: Vec<LayerId> =
            self.layers.keys().filter(|id| !keep.contains(*id) && self.parked.contains_key(*id)).copied().collect();
        clean.sort_by_key(|id| self.needed_at.get(id).copied().unwrap_or(0));
        for id in clean {
            if self.layers.len() <= wanted {
                break;
            }
            self.layers.remove(&id);
        }
    }

    /// Puts `uniforms` in the uniform buffer for the work about to be sent; one that would not fit
    /// (the buffer sized for less) is said in the log, as the device refuses it.
    pub(super) fn write_uniforms(&self, gpu: &Gpu, uniforms: &UniformData) {
        if uniforms.data.is_empty() {
            return;
        }
        if uniforms.data.len() as u64 > self.uniform_slots * SLOT {
            log::error!("uniforms outgrew their buffer ({} bytes, {} slots)", uniforms.data.len(), self.uniform_slots);
        }
        gpu.queue.write_buffer(&self.uniform_buffer, 0, &uniforms.data);
    }

    /// Sends the work recorded so far (with its uniforms), going on with a new encoder: what it
    /// was sent as.
    pub(super) fn send_now(
        &self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
    ) -> wgpu::SubmissionIndex {
        let fresh = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("canvas") });
        let done = std::mem::replace(encoder, fresh);
        self.write_uniforms(gpu, uniforms);
        let sent = gpu.queue.submit([done.finish()]);
        *uniforms = UniformData::default();
        sent
    }

    /// Lays `instances` (the frame's stamps from `first` on, uploaded already) into `target` by
    /// their stroke's `rule` pixel by pixel (see [`by_rule`]): a part of the canvas at most
    /// [`RULE_PART`] pixels a side at a time (smaller where its list of tiles would hold more than
    /// a list may: see [`rule_lists`]), each pixel going through the stamps over its tile in order
    /// as its own paint stands after each, then the part copied into the stroke. A part whose list
    /// couldn't be made (past the GPU's memory) is left out, and that said once.
    pub(super) fn lay_by_rule(
        &mut self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        (uniform, brush): (u32, &wgpu::BindGroup),
        instances: &[StampInstance],
        first: u32,
        (texture, target): (&wgpu::Texture, &wgpu::TextureView),
        rule: u32,
    ) {
        let Some(out) = self.rule_out.clone() else { return };
        let pipelines = Arc::clone(&self.pipelines);
        let Some(compute) = pipelines.compute.as_ref() else { return };
        let limits = gpu.device.limits();
        let room = limits.max_storage_buffer_binding_size.min(limits.max_buffer_size) / 4;
        let most = self.rule_list_most.min(usize::try_from(room).unwrap_or(usize::MAX));
        let binds = RuleBinds {
            uniforms: self.uniform_buffer.clone(),
            stamps: self.stamp_buffer.clone(),
            target: target.clone(),
            out: out.clone(),
        };
        let bounds = instances.iter().fold(IRect::EMPTY, |b, s| b.union(quad_reach(s))).intersect(self.full());
        let part = RULE_PART as i32;
        let mut y0 = bounds.y0;
        while y0 < bounds.y1 {
            let mut x0 = bounds.x0;
            while x0 < bounds.x1 {
                let whole = IRect { x0, y0, x1: (x0 + part).min(bounds.x1), y1: (y0 + part).min(bounds.y1) };
                x0 += part;
                for (rect, words) in rule_lists(instances, first, whole, rule, most) {
                    let Some(bind) = self.rule_list(gpu, &words, &binds, &compute.rule_layout) else { continue };
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("stamp rule"),
                        timestamp_writes: None,
                    });
                    pass.set_pipeline(&compute.stamp_rule);
                    pass.set_bind_group(0, &bind, &[uniform]);
                    pass.set_bind_group(1, brush, &[]);
                    pass.dispatch_workgroups(rect.width().div_ceil(RULE_TILE), rect.height().div_ceil(RULE_TILE), 1);
                    drop(pass);
                    self.dispatched.fetch_add(1, Ordering::Relaxed);
                    gpu.copy_buffer_to_texture(
                        encoder,
                        wgpu::TexelCopyBufferInfo {
                            buffer: &out,
                            layout: wgpu::TexelCopyBufferLayout {
                                offset: 0,
                                bytes_per_row: Some(RULE_PART * 8),
                                rows_per_image: Some(rect.height()),
                            },
                        },
                        copy_at(texture, rect.x0, rect.y0),
                        extent(rect.width(), rect.height()),
                    );
                }
            }
            y0 += part;
        }
    }

    /// The bind group for the next part of this render's rule passes (see [`RuleLists`]), its list
    /// holding `words` (written through the queue, ahead of the work it is read by): a kept list,
    /// grown if it is too small, bound with `binds`. None if it couldn't be made, said once.
    pub(super) fn rule_list(
        &mut self,
        gpu: &Gpu,
        words: &[u32],
        binds: &RuleBinds,
        layout: &wgpu::BindGroupLayout,
    ) -> Option<wgpu::BindGroup> {
        let device = &gpu.device;
        let bytes = (words.len() as u64 * 4).max(4);
        let at = self.rule_lists.next;
        self.rule_lists.largest = self.rule_lists.largest.max(words.len());
        let roomy = self.rule_lists.kept.get(at).is_some_and(|l| l.buffer.size() >= bytes);
        if !roomy {
            // in steps, so that a list a little longer than the last doesn't make one each frame
            let room = device.limits().max_storage_buffer_binding_size;
            let most = u64::try_from(self.rule_list_most).unwrap_or(u64::MAX).saturating_mul(4).min(room);
            let size = bytes.next_power_of_two().max(RULE_LIST_LEAST).min(most).max(bytes);
            let buffer = self.made_or_told(device, |device| rule_list_buffer(device, size))?;
            let list = RuleList { buffer, bind: None };
            match self.rule_lists.kept.get_mut(at) {
                Some(kept) => *kept = list,
                None => self.rule_lists.kept.push(list),
            }
        }
        self.rule_lists.next += 1;
        let list = self.rule_lists.kept.get_mut(at)?;
        gpu.queue.write_buffer(&list.buffer, 0, bytemuck::cast_slice(words));
        if let Some((bound, bind)) = &list.bind
            && bound == binds
        {
            return Some(bind.clone());
        }
        let room = device.limits().max_storage_buffer_binding_size;
        let stamps = NonZeroU64::new(binds.stamps.size().min(room) / 4 * 4);
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("stamp rule"),
            layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: &binds.uniforms,
                        offset: 0,
                        size: NonZeroU64::new(UNIFORM_SIZE),
                    }),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: &binds.stamps,
                        offset: 0,
                        size: stamps,
                    }),
                },
                wgpu::BindGroupEntry { binding: 2, resource: list.buffer.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 3, resource: wgpu::BindingResource::TextureView(&binds.target) },
                wgpu::BindGroupEntry { binding: 4, resource: binds.out.as_entire_binding() },
            ],
        });
        list.bind = Some((binds.clone(), bind.clone()));
        Some(bind)
    }

    /// What `make` makes, unless the device refuses it (past its memory, or lost): then none, the
    /// work that wanted it left out, and the first such said in the log.
    pub(super) fn made_or_told<T>(
        &mut self,
        device: &wgpu::Device,
        make: impl FnOnce(&wgpu::Device) -> T,
    ) -> Option<T> {
        let memory = device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        let invalid = device.push_error_scope(wgpu::ErrorFilter::Validation);
        let made = make(device);
        let failed = pollster::block_on(invalid.pop()).or(pollster::block_on(memory.pop()));
        let Some(e) = failed else { return Some(made) };
        if !std::mem::replace(&mut self.rule_lists.told, true) {
            log::error!("stamps laid by their rule are left out: {e}");
        }
        self.work_left_out = true;
        None
    }

    /// Whether work has been left out since this was last asked, for want of what it takes on the
    /// GPU (see [`Canvas::lay_by_rule`]): what was drawn then isn't all of it.
    pub fn take_work_left_out(&mut self) -> bool {
        std::mem::take(&mut self.work_left_out)
    }

    /// The most words a list of the rule's tiles is to hold here, in place of [`RULE_LIST_MOST`]
    /// (parts of the canvas that would take more laid in smaller ones): for tests.
    #[doc(hidden)]
    pub fn set_rule_list_most(&mut self, words: usize) {
        self.rule_list_most = words.max(8);
    }

    /// The most words a list of the rule's tiles has held: for tests.
    #[doc(hidden)]
    pub fn largest_rule_list(&self) -> usize {
        self.rule_lists.largest
    }

    /// Sends the work recorded so far with the three kinds of uniforms a render's painting uses,
    /// going on with a new encoder (see [`WORK_AT_ONCE`]).
    pub(super) fn send_piece(
        &mut self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        (uniforms, smudge, wet): (&mut UniformData, &mut UniformData, &mut UniformData),
    ) {
        if !smudge.data.is_empty() {
            gpu.queue.write_buffer(&self.smudge_params, 0, &smudge.data);
        }
        if !wet.data.is_empty() {
            gpu.queue.write_buffer(&self.wet_params, 0, &wet.data);
        }
        let at = std::time::Instant::now();
        let sent = self.send_now(gpu, encoder, uniforms);
        *smudge = UniformData::default();
        *wet = UniformData::default();
        self.pieces_sent = self.pieces_sent.wrapping_add(1);
        self.wait_if_paced(gpu, sent, at);
    }

    /// Goes on remaking the caches for a while (parts of whole items, each part's layers brought
    /// back, composited, sent, then let go before the next part's come back); once both are done,
    /// they are the caches and the whole composite is made again.
    pub(super) fn go_on_remaking(&mut self, gpu: &Gpu, doc: &Document) {
        let Some(mut r) = self.remake.take() else { return };
        let device = &gpu.device;
        let full = self.full();
        let deadline = std::time::Instant::now() + REMAKE_SLICE;
        let (mut restored, mut restoring, mut largest) = (0, std::time::Duration::ZERO, 0);
        loop {
            let (below, items, progress) = match (r.below, r.above) {
                (Some(p), _) => (true, &r.below_items, p),
                (None, Some(p)) => (false, &r.above_items, p),
                (None, None) => break,
            };
            let (mut at, mut current) = progress;
            // as many whole items as fit and the slice's time allows (at least one), a layer with
            // those clipped to it (their clipping group) as one, each with what it is clipped to; an
            // item painted nowhere is passed over, a normal one composites only where it is painted
            let room = self.most_resident().saturating_sub(self.layers.len()).max(1);
            let mut part = Vec::new();
            let mut layers = Vec::new();
            let mut staged = 0;
            while let Some(&item) = items.get(at) {
                let n = 1 + clipped_run(doc, items.get(at + 1..).unwrap_or_default(), item).len();
                let unit = at..at + n;
                let mut more: Vec<LayerId> =
                    items.get(unit.clone()).unwrap_or_default().iter().flat_map(|t| item_layers(doc, *t)).collect();
                more.extend(clip_base_and_mask(doc, item));
                // the time counts bringing layers back, not the compositing after: the count
                // keeps a part whose layers come back quickly from compositing too long
                let late = std::time::Instant::now() >= deadline;
                let full_part = part.len() >= MOST_PART_ITEMS || layers.len() + more.len() > room;
                if !part.is_empty() && (late || full_part) {
                    break;
                }
                at += n;
                let painted = self.painted(&more).intersect(full);
                if painted.is_empty() {
                    continue;
                }
                // a normal item draws in place; another mode writes the stack's other texture, which
                // has to be whole
                let normal = doc.layer(item).is_some_and(|l| l.blend == BlendMode::Normal);
                let rect = if normal { painted } else { full };
                // brought back now (the slow part, so the time taken counts), the staged pixels sent
                // on now and then so they don't pile up
                staged += more.len();
                let restore_started = std::time::Instant::now();
                self.restore_all(gpu, more.iter().copied(), false);
                restoring += restore_started.elapsed();
                restored += more.len();
                if staged >= 8 {
                    gpu.queue.submit(std::iter::empty());
                    staged = 0;
                }
                layers.extend(more);
                part.push((unit, rect));
            }
            largest = largest.max(part.len());
            let picture = if below { &r.caches.below } else { &r.caches.above };
            let stack = picture.stack(&self.composite_second, device);
            let mut encoder =
                device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("cache part") });
            let mut uniforms = UniformData::default();
            for (unit, rect) in &part {
                self.composite_items(
                    device,
                    doc,
                    &mut encoder,
                    &mut uniforms,
                    *rect,
                    items.get(unit.clone()).unwrap_or_default(),
                    true,
                    &stack,
                    &mut current,
                );
            }
            let done = at >= items.len();
            // a second texture borrowed from the composite is given back between parts
            if done || picture.borrows() {
                stack.settle(&mut encoder, full, current);
                current = 0;
            }
            self.write_uniforms(gpu, &uniforms);
            gpu.queue.submit([encoder.finish()]);
            self.let_go_clean(&r.keep);
            gpu.flush();
            let next = (!done).then_some((at, current));
            if below {
                r.below = next;
            } else {
                r.above = next;
            }
            if std::time::Instant::now() >= deadline {
                break;
            }
        }
        r.slices += 1;
        r.restored += restored;
        r.restoring += restoring;
        r.largest_part = r.largest_part.max(largest);
        if r.below.is_some() || r.above.is_some() {
            self.remake = Some(r);
            return;
        }
        log::info!(
            "caches remade in {} ms over {} renders: {} items (at most {} a part), {} layers brought back in {} ms",
            r.started.elapsed().as_millis(),
            r.slices,
            r.below_items.len() + r.above_items.len(),
            r.largest_part,
            r.restored,
            r.restoring.as_millis()
        );
        self.last_remake = Some(RemakeStats { renders: r.slices, largest_part: r.largest_part });
        // made: they are the caches, and the whole picture is composited again around them
        let Remake { mut caches, below_items, below_key, above_items, above_key, .. } = r;
        caches.below_items = below_items;
        caches.below_key = below_key;
        caches.above_items = above_items;
        caches.above_key = above_key;
        self.caches = Some(caches);
        self.dirty = self.full();
    }

    /// Whether another layer's texture fits the budget.
    pub fn has_room(&self) -> bool {
        self.layers.len() < self.most_resident()
    }

    /// Keeps layer `id`'s pixels (premultiplied RGBA, the canvas's size) compressed, without a
    /// texture, as a layer parked: for an artwork opening with more layers than fit.
    pub fn park_pixels(&mut self, id: LayerId, rgba: &[u8]) -> Result<(), GpuError> {
        let expected = self.width as usize * self.height as usize * 4;
        if rgba.len() != expected {
            return Err(GpuError::Readback(format!("layer {id:?}: {} bytes, expected {expected}", rgba.len())));
        }
        self.park_ready(id, ReadyToPark::of(rgba, self.width, self.height));
        Ok(())
    }

    /// As [`Canvas::upload_layer`], keeping `ready` (made from the same pixels by
    /// [`ReadyToPark::of`]) as the layer's compressed copy: letting its texture go later, while
    /// it is unchanged, costs nothing.
    pub fn upload_layer_kept(
        &mut self,
        gpu: &Gpu,
        id: LayerId,
        rgba: &[u8],
        ready: ReadyToPark,
        painted: Option<IRect>,
    ) -> Result<(), GpuError> {
        self.upload_layer_painted(gpu, id, rgba, painted)?;
        self.parked.insert(id, ready.parked);
        self.pictures.insert(id, ready.pictures);
        Ok(())
    }

    /// Parks layer `id` with pixels already made ready (by [`ReadyToPark::of`], with this
    /// canvas's size).
    pub fn park_ready(&mut self, id: LayerId, ready: ReadyToPark) {
        self.layers.remove(&id);
        self.parked.insert(id, ready.parked);
        self.pictures.insert(id, ready.pictures);
        // new pixels, as an upload's: saved next (a canvas made from its files forgets this)
        self.touched(id);
        self.invalidate();
    }

    /// The most uniform slots `op` takes in a render (among `ops`, the render's work): a liquify
    /// drag's stamps one each, a transform one for each layer it moves (those the same render adds
    /// too), a filter one for each halving of a wide blur and its passes; the rest one or none.
    pub(super) fn slots_for(&self, op: &Op, ops: &[Op]) -> usize {
        match op {
            Op::LiquifyStamps(stamps) => stamps.len() + 1,
            Op::TransformSet { .. } | Op::TransformMesh { .. } => {
                let added = ops.iter().filter(|o| matches!(o, Op::TransformAlso(_))).count();
                1 + self.transform_more.len() + added
            }
            Op::FilterSet(_) | Op::PluginFilter(_) => FILTER_SLOTS,
            _ => 2,
        }
    }

    /// Whether work keeps the caches: it changes only the pixels of layers worked on (painting,
    /// transforms and adjustments, held edits, clearing, and undoing or redoing those).
    pub(super) fn keeps_caches(&self, op: &Op, live: &HashSet<LayerId>) -> bool {
        let only_live = |step: Option<&Step>| {
            step.is_some_and(|s| {
                s.doc.is_none() && s.flip.is_none() && s.pixels.iter().all(|p| live.contains(&p.layer))
            })
        };
        let transformed_live = || self.transforming.iter().chain(&self.transform_more).all(|t| live.contains(&t.layer));
        match op {
            Op::Stamps { layer, .. } => live.contains(layer),
            Op::Commit(stroke) => live.contains(&stroke.layer),
            Op::Cancel
            | Op::SaveTip(_)
            | Op::Untip(_)
            | Op::RewindInPlace(_)
            | Op::SecondReached(_)
            | Op::Mark
            | Op::Name(_)
            | Op::OutsideChange(_)
            | Op::Collapse
            | Op::Within(_) => true,
            Op::Undo => only_live(self.undo.last()),
            Op::Redo => only_live(self.redo.last()),
            Op::TransformBegin(id)
            | Op::TransformAlso(id)
            | Op::Hold(id)
            | Op::Put(id, _)
            | Op::PutRegion { layer: id, .. }
            | Op::Clear(id, _)
            | Op::Invert(id, _)
            | Op::Fill(id, _)
            | Op::Wipe(id) => live.contains(id),
            Op::TransformSet { .. }
            | Op::TransformMesh { .. }
            | Op::FilterSet(_)
            | Op::PluginFilter(_)
            | Op::LiquifyBegin
            | Op::PencilBegin
            | Op::LiquifyStamps(_)
            | Op::LiquifyScale(_)
            | Op::LiquifyClear
            | Op::LiquifyMark
            | Op::LiquifyTakeBack
            | Op::LiquifyUndo
            | Op::LiquifyRedo
            | Op::LiquifyKeep
            | Op::LiquifyResume
            | Op::LiquifyForget
            | Op::TransformEnd { .. } => transformed_live(),
            Op::Release { before: None, .. } => self.held.as_ref().is_none_or(|h| live.contains(&h.layer)),
            Op::Write { layers, before: None } => layers.iter().all(|(id, ..)| live.contains(id)),
            _ => false,
        }
    }

    /// The members of `group` (None: the top level) bottom up onto `stack`; returns which of its
    /// textures holds the result.
    #[allow(clippy::too_many_arguments)]
    pub(super) fn composite_members(
        &self,
        device: &wgpu::Device,
        doc: &Document,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        rect: IRect,
        group: Option<LayerId>,
        stack: &Stack<'_>,
    ) -> usize {
        let mut current = 0usize;
        self.composite_items(
            device,
            doc,
            encoder,
            uniforms,
            rect,
            &doc.members(group),
            group.is_none(),
            stack,
            &mut current,
        );
        current
    }

    /// `items` (a level's members, bottom up; `top` the top level's) onto `stack`, whose result
    /// is in the stack's first texture (`current` 0) or its second (1).
    #[allow(clippy::too_many_arguments)]
    pub(super) fn composite_items(
        &self,
        device: &wgpu::Device,
        doc: &Document,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        rect: IRect,
        items: &[LayerId],
        top: bool,
        stack: &Stack<'_>,
        current: &mut usize,
    ) {
        // Animation Assist: the top level's frames show around the current (or played) one; none
        // for a frame not shown
        let animation = if top { doc.animation.as_ref() } else { None };
        let frames = animation.map(|_| animation::frames(doc)).unwrap_or_default();
        let focus = animation.and_then(|_| self.play_frame.or_else(|| animation::current_frame(doc)));
        let look_of = |id: LayerId| match (animation, focus, frames.iter().position(|f| *f == id)) {
            (Some(a), Some(c), Some(i)) => match animation::look(a, i, frames.len(), c, self.play_frame.is_some()) {
                FrameLook::Shown => Some(Look::PLAIN),
                FrameLook::Onion { color, opacity } => {
                    Some(Look { tint: [color[0], color[1], color[2], 1.0], opacity })
                }
                FrameLook::Hidden => None,
            },
            _ => Some(Look::PLAIN),
        };
        let mut at = 0;
        while let Some(&id) = items.get(at) {
            at += 1;
            let Some(layer) = doc.layer(id) else { continue };
            // another layer shown alone
            if doc.soloed_out(id) {
                continue;
            }
            let Some(look) = look_of(id) else { continue };
            match layer.kind {
                LayerKind::Mask => {}
                LayerKind::Paint => {
                    // with the layers clipped to it just above, a clipping group (a layer clipped
                    // to one not shown goes through that one's alpha on its own)
                    let clipped = clipped_run(doc, items.get(at..).unwrap_or_default(), id);
                    if clipped.is_empty() {
                        self.composite_layer(
                            device, doc, encoder, uniforms, rect, layer, look, CLIP_NONE, stack, current,
                        );
                    } else {
                        at += clipped.len();
                        let group = ClipGroup { base: layer, look, clipped, looks: &look_of };
                        self.composite_clip_group(device, doc, encoder, uniforms, rect, group, stack, current);
                    }
                }
                LayerKind::Group => {
                    if !layer.visible || layer.opacity <= 0.0 {
                        continue;
                    }
                    // its members straight onto what is below (an onion skin's frame on its own,
                    // to be tinted whole)
                    if layer.passes_through() && look.tint[3] == 0.0 {
                        let members = doc.members(Some(id));
                        self.composite_items(device, doc, encoder, uniforms, rect, &members, false, stack, current);
                        continue;
                    }
                    let Some(view) = self.composite_group(device, doc, encoder, uniforms, rect, id) else { continue };
                    let uniform = LayerUniform {
                        opacity: layer.opacity.clamp(0.0, 1.0) * look.opacity,
                        blend: layer.blend.id(),
                        tint: look.tint,
                        ..Default::default()
                    };
                    self.draw_onto(
                        device,
                        encoder,
                        uniforms,
                        rect,
                        view,
                        None,
                        &self.stroke_view,
                        uniform,
                        layer.blend,
                        stack,
                        current,
                    );
                }
            }
        }
    }

    /// Group `id`'s members composited over transparency on the textures for its depth; returns
    /// the view holding the result.
    pub(super) fn composite_group(
        &self,
        device: &wgpu::Device,
        doc: &Document,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        rect: IRect,
        id: LayerId,
    ) -> Option<&wgpu::TextureView> {
        let (textures, views) = self.groups.get(doc.depth(id))?;
        let inner = Stack::pair(textures, views);
        drop(render_pass(encoder, "group clear", &views[0], wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT)));
        let at = self.composite_members(device, doc, encoder, uniforms, rect, Some(id), &inner);
        inner.settle(encoder, rect, at);
        Some(&views[0])
    }

    /// A clipping group onto a stack: its base (as its look shows it) and the layers clipped to it
    /// composited on their own, each of those blended onto the base by its mode as if the base
    /// were opaque and shown as much as the base is (as alpha lock paints: no colour of the base
    /// shows at its soft edges), then the group onto the stack by the base's mode and opacity.
    #[allow(clippy::too_many_arguments)]
    pub(super) fn composite_clip_group(
        &self,
        device: &wgpu::Device,
        doc: &Document,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        rect: IRect,
        group: ClipGroup<'_>,
        stack: &Stack<'_>,
        current: &mut usize,
    ) {
        let ClipGroup { base, look, clipped, looks } = group;
        // a hidden base hides what is clipped to it
        let opacity = base.opacity.clamp(0.0, 1.0) * look.opacity;
        if !base.visible || opacity <= 0.0 || !self.layers.contains_key(&base.id) {
            return;
        }
        let (texture, view) = self.clip_group.get(device);
        let inner = Stack { first: (texture, view), second: Second::OnDemand(&self.clip_group_second, device) };
        let mut at = 0;
        // the base at full, with the stroke being drawn on it (or its mask), written over what was
        // there
        self.composite_layer(device, doc, encoder, uniforms, rect, base, Look::PLAIN, CLIP_BASE, &inner, &mut at);
        for &id in clipped {
            let Some(layer) = doc.layer(id) else { continue };
            if doc.soloed_out(id) {
                continue;
            }
            let Some(look) = looks(id) else { continue };
            self.composite_layer(device, doc, encoder, uniforms, rect, layer, look, CLIP_ATOP, &inner, &mut at);
        }
        let view = if at == 0 { view } else { inner.second().1 };
        let uniform = LayerUniform { opacity, blend: base.blend.id(), tint: look.tint, ..Default::default() };
        self.draw_onto(
            device,
            encoder,
            uniforms,
            rect,
            view,
            None,
            &self.stroke_view,
            uniform,
            base.blend,
            stack,
            current,
        );
    }

    /// One paint layer (through its mask, with the stroke being drawn on it or its mask) onto a
    /// stack whose result is in its first texture (`current` 0) or its second (1). `role`: in a
    /// clipping group, its base (CLIP_BASE: at full, the group taking its opacity and mode) or a
    /// layer clipped to it (CLIP_ATOP); else CLIP_NONE, and then a layer clipped to a base not
    /// shown goes through that base's alpha.
    #[allow(clippy::too_many_arguments)]
    pub(super) fn composite_layer(
        &self,
        device: &wgpu::Device,
        doc: &Document,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        rect: IRect,
        layer: &Layer,
        look: Look,
        role: u32,
        stack: &Stack<'_>,
        current: &mut usize,
    ) {
        let clip = doc.clip_base(layer.id).filter(|_| role == CLIP_NONE);
        let clip_visible = clip.is_none_or(|id| doc.layer(id).is_some_and(|l| l.visible));
        let (opacity, blend) =
            if role == CLIP_BASE { (1.0, BlendMode::Normal) } else { (layer.opacity.clamp(0.0, 1.0), layer.blend) };
        if !layer.visible || !clip_visible || opacity <= 0.0 {
            return;
        }
        let Some(gpu_layer) = self.layers.get(&layer.id) else { return };
        let clip_view = clip.and_then(|id| self.layers.get(&id)).map_or(&self.stroke_view, |c| &c.view);
        // clipping follows the base as its mask shows it
        let clip_mask_view = clip
            .and_then(|b| doc.mask_of(b))
            .filter(|m| doc.layer(*m).is_some_and(|l| l.visible))
            .and_then(|m| self.layers.get(&m))
            .map(|m| &m.view);
        let mask = doc.mask_of(layer.id).filter(|m| doc.layer(*m).is_some_and(|l| l.visible));
        let mask_view = mask.and_then(|m| self.layers.get(&m)).map(|m| &m.view);
        // the stroke being drawn shows on its layer, or through the mask it is painting
        let stroke = self.active.filter(|s| (s.layer == layer.id || Some(s.layer) == mask) && !s.pencil);
        // else a fill shown over what is selected, on the layer or its mask
        let fill = self
            .fill_preview
            .filter(|f| stroke.is_none() && self.selection.is_some() && (f.layer == layer.id || Some(f.layer) == mask));
        let uniform = LayerUniform {
            opacity: opacity * look.opacity,
            tint: look.tint,
            stroke_mode: match stroke.map(|s| s.style.tool) {
                None => STROKE_NONE,
                // the stroke changes the layer itself
                _ if stroke.is_some_and(|s| s.style.in_place()) => STROKE_NONE,
                Some(StrokeTool::Paint) if stroke.is_some_and(|s| s.style.preserve_alpha) => STROKE_PAINT_LOCKED,
                Some(StrokeTool::Paint) => STROKE_PAINT,
                Some(StrokeTool::Erase) => STROKE_ERASE,
                Some(StrokeTool::Laid) => self.laid_mode(),
                // the smudge changes the layer itself
                Some(StrokeTool::Smudge) => STROKE_NONE,
            },
            stroke_opacity: stroke.map_or(0.0, |s| s.style.opacity.clamp(0.0, 1.0)),
            blend: blend.id(),
            clipped: if clip.is_some() { CLIP_BY_BASE } else { role },
            clip_masked: if clip_mask_view.is_some() { 1.0 } else { 0.0 },
            masked: if mask_view.is_some() { 1.0 } else { 0.0 },
            stroke_on_mask: if stroke.is_some_and(|s| Some(s.layer) == mask) { 1.0 } else { 0.0 },
            ..Default::default()
        }
        .with_grain(stroke.as_ref().map(|s| &s.style))
        .with_paper(stroke.is_some(), self.paper_look())
        .with_dual(stroke.as_ref())
        .with_fill(fill, mask);
        let mask_view = mask_view.unwrap_or(&self.stroke_view);
        self.draw_onto(
            device,
            encoder,
            uniforms,
            rect,
            &gpu_layer.view,
            Some((clip_view, clip_mask_view.unwrap_or(&self.stroke_view))),
            mask_view,
            uniform,
            blend,
            stack,
            current,
        );
    }

    /// `view` (a layer or a group's result) drawn onto a stack: "over" for normal (atop for a
    /// layer in a clipping group, written as it is for the group's base), else blended with what
    /// is below into the stack's other texture.
    #[allow(clippy::too_many_arguments)]
    pub(super) fn draw_onto(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        rect: IRect,
        view: &wgpu::TextureView,
        clip: Option<(&wgpu::TextureView, &wgpu::TextureView)>,
        mask: &wgpu::TextureView,
        uniform: LayerUniform,
        blend: BlendMode,
        stack: &Stack<'_>,
        current: &mut usize,
    ) {
        let offset = uniforms.push(&uniform);
        // the base clipped to and its mask
        let (clip, clip_mask) = clip.unwrap_or((&self.stroke_view, &self.stroke_view));
        // a fill shown over what is selected reads the selection where a stroke is read
        let stroke = match (uniform.stroke_mode, self.selection.as_ref()) {
            (STROKE_FILL | STROKE_FILL_LOCKED, Some(selection)) => &selection.view,
            _ => &self.stroke_view,
        };
        let first = stack.first.1;
        if blend == BlendMode::Normal || uniform.clipped == CLIP_BASE {
            let bind = self.quad_masked(device, [view, stroke, clip, &self.stroke_view], mask, clip_mask);
            let target = if *current == 0 { first } else { stack.second().1 };
            let mut pass = render_pass(encoder, "layer", target, wgpu::LoadOp::Load);
            scissor(&mut pass, rect);
            pass.set_pipeline(match uniform.clipped {
                // nothing below read (the backdrop bound is any)
                CLIP_BASE => &self.pipelines.blend,
                CLIP_ATOP => &self.pipelines.layer_atop,
                _ => &self.pipelines.layer,
            });
            pass.set_bind_group(0, &bind, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.draw(0..3, 0..1);
        } else {
            let second = stack.second().1;
            let (backdrop, target) = if *current == 0 { (first, second) } else { (second, first) };
            let bind = self.quad_masked(device, [view, stroke, clip, backdrop], mask, clip_mask);
            let mut pass = render_pass(encoder, "blend", target, wgpu::LoadOp::Load);
            scissor(&mut pass, rect);
            pass.set_pipeline(&self.pipelines.blend);
            pass.set_bind_group(0, &bind, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.draw(0..3, 0..1);
            *current = 1 - *current;
        }
    }

    pub(super) fn display(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        target: &Target<'_>,
        predicted: std::ops::Range<u32>,
        glass: Option<(Backdrop<'_>, f32)>,
    ) {
        let srgb = target.format.is_srgb();
        let Pipelines {
            display_module,
            display_pipeline_layout,
            display_lut_layout,
            surround_module,
            surround_pipeline_layout,
            overlay_module,
            stamp_layout,
            selection_module,
            veil_pipeline_layout,
            ..
        } = &*self.pipelines;
        let kept = self.pipelines.cache.as_ref();
        // the images a predicted tail reads, if they are a brush file's own
        let own_images =
            self.active.filter(|_| !predicted.is_empty()).and_then(|a| self.full_grain_bind(device, &a.style));
        let ScreenPipelines {
            display,
            surround: surround_pipelines,
            overlay,
            selection: selection_pipelines,
            frost,
            veil: veil_pipelines,
            ..
        } = &mut self.screen;
        let pipeline = display.entry(target.format).or_insert_with(|| {
            // over the surround, which shows through see-through parts (a hidden background)
            blended_pipeline(
                device,
                kept,
                display_lut_layout,
                display_module,
                "display",
                ("vs_display", "fs_display"),
                target.format,
                Some(PREMULTIPLIED_OVER),
            )
        });
        let (w, h) = (target.size.0.max(1) as f32, target.size.1.max(1) as f32);
        let to_clip = Affine { a: 2.0 / w, b: 0.0, c: 0.0, d: -2.0 / h, e: -1.0, f: 1.0 };
        let unit_to_canvas = Affine { a: self.width as f32, b: 0.0, c: 0.0, d: self.height as f32, e: 0.0, f: 0.0 };
        let m = unit_to_canvas.then(target.to_screen).then(to_clip);
        let offset = uniforms.push(
            &DisplayUniform::new(
                [m.a, m.b, m.c, m.d],
                [m.e, m.f, if srgb { 1.0 } else { 0.0 }, 0.0],
                target.convert,
                target.lut.is_some(),
            )
            .looking(target.look)
            .smoothed(&m, (w, h)),
        );
        let surround = target.surround.map(|c| if srgb { srgb_to_linear(c) } else { c });
        let clear =
            wgpu::Color { r: f64::from(surround[0]), g: f64::from(surround[1]), b: f64::from(surround[2]), a: 1.0 };
        let grid = target.grid.map(|g| {
            let pipeline = surround_pipelines.entry(target.format).or_insert_with(|| {
                sampled_pipeline(
                    device,
                    kept,
                    surround_pipeline_layout,
                    surround_module,
                    "surround",
                    "vs_surround",
                    "fs_surround",
                    target.format,
                )
            });
            let offset = uniforms.push(&SurroundUniform {
                v0: [g.spacing, g.origin[0], g.origin[1], g.width],
                v1: [g.color[0], g.color[1], g.color[2], if srgb { 1.0 } else { 0.0 }],
                v2: [target.surround[0], target.surround[1], target.surround[2], g.top],
            });
            (&*pipeline, offset)
        });
        let mut pass = render_pass(encoder, "display", target.view, wgpu::LoadOp::Clear(clear));
        if let Some((grid_pipeline, offset)) = grid {
            pass.set_pipeline(grid_pipeline);
            pass.set_bind_group(0, &self.uniform_bind, &[offset]);
            pass.draw(0..3, 0..1);
        }
        pass.set_pipeline(pipeline);
        // a little short of the mark still counts, for a zoom that lands just under it
        let crisp = target.to_screen.scale_factor() >= target.crisp_from * 0.999;
        pass.set_bind_group(0, if crisp { &self.crisp_bind } else { &self.display_bind }, &[]);
        pass.set_bind_group(1, &self.uniform_bind, &[offset]);
        pass.set_bind_group(2, target.lut.map_or(&self.identity_lut, |l| &l.bind), &[]);
        pass.draw(0..6, 0..1);

        // part of the canvas drawn again sharper, in its place
        if let Some(sharp) = self.sharp.as_ref() {
            let [x, y, w, h] = sharp.rect;
            let unit_to_rect = Affine { a: w, b: 0.0, c: 0.0, d: h, e: x, f: y };
            let m = unit_to_rect.then(target.to_screen).then(to_clip);
            let offset = uniforms.push(
                &DisplayUniform::new(
                    [m.a, m.b, m.c, m.d],
                    [m.e, m.f, if srgb { 1.0 } else { 0.0 }, 0.0],
                    target.convert,
                    target.lut.is_some(),
                )
                .looking(target.look),
            );
            pass.set_bind_group(0, &sharp.bind, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.draw(0..6, 0..1);
        }

        // part of the canvas as it was, over it but for its hole
        if let Some(veil) = self.veil.as_ref() {
            let pipeline = veil_pipelines.entry(target.format).or_insert_with(|| {
                blended_pipeline(
                    device,
                    kept,
                    veil_pipeline_layout,
                    display_module,
                    "veil",
                    ("vs_veil", "fs_veil"),
                    target.format,
                    Some(PREMULTIPLIED_OVER),
                )
            });
            let r = veil.rect;
            let (x, y, w, h) = (r.x0 as f32, r.y0 as f32, r.width() as f32, r.height() as f32);
            let unit_to_rect = Affine { a: w, b: 0.0, c: 0.0, d: h, e: x, f: y };
            let m = unit_to_rect.then(target.to_screen).then(to_clip);
            let offset = uniforms.push(
                &DisplayUniform::new(
                    [m.a, m.b, m.c, m.d],
                    [m.e, m.f, if srgb { 1.0 } else { 0.0 }, 0.0],
                    target.convert,
                    target.lut.is_some(),
                )
                .looking(target.look),
            );
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, if crisp { &veil.crisp } else { &veil.bind }, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.set_bind_group(2, target.lut.map_or(&self.identity_lut, |l| &l.bind), &[]);
            pass.set_bind_group(3, &veil.uniform_bind, &[]);
            // a cell at a time: those wholly in the hole draw nothing
            pass.draw(0..6, 0..(VEIL_GRID * VEIL_GRID) as u32);
        }

        // the selection: bands over what is not selected, or what is selected tinted or covered
        if let (Some(look), Some(selection), Some(inverse)) =
            (target.selection, &self.selection, unit_to_canvas.then(target.to_screen).inverse())
        {
            let multiply = matches!(look, SelectionLook::Tint(_));
            let pipeline = selection_pipelines.entry((target.format, multiply)).or_insert_with(|| {
                device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                    label: Some("selection"),
                    layout: Some(&*display_pipeline_layout),
                    vertex: wgpu::VertexState {
                        module: selection_module,
                        entry_point: Some("vs_selection"),
                        compilation_options: Default::default(),
                        buffers: &[],
                    },
                    primitive: wgpu::PrimitiveState::default(),
                    depth_stencil: None,
                    multisample: wgpu::MultisampleState::default(),
                    fragment: Some(wgpu::FragmentState {
                        module: selection_module,
                        entry_point: Some("fs_selection"),
                        compilation_options: Default::default(),
                        targets: &[Some(wgpu::ColorTargetState {
                            format: target.format,
                            blend: Some(if multiply { MULTIPLY } else { PREMULTIPLIED_OVER }),
                            write_mask: wgpu::ColorWrites::ALL,
                        })],
                    }),
                    multiview_mask: None,
                    cache: kept,
                })
            });
            let i = inverse;
            let srgb_flag = if srgb { 1.0 } else { 0.0 };
            let (m1_rest, v) = match look {
                SelectionLook::Stripes(s) => ([s.period.max(1.0), s.drift], [0.0, 0.0, 0.0, s.coverage]),
                SelectionLook::Tint(c) => ([1.0, 0.0], [c[0], c[1], c[2], 1.0]),
                SelectionLook::Fill(c) => ([1.0, 0.0], [c[0], c[1], c[2], 1.0]),
            };
            let mode = match look {
                SelectionLook::Stripes(_) => 0.0,
                SelectionLook::Tint(_) => 1.0,
                SelectionLook::Fill(_) => 2.0,
            };
            let offset = uniforms.push(&SelectionUniform {
                m0: [i.a, i.b, i.c, i.d],
                m1: [i.e, i.f, m1_rest[0], m1_rest[1]],
                v,
                mode: [mode, srgb_flag, 0.0, 0.0],
            });
            pass.set_pipeline(pipeline);
            pass.set_bind_group(0, &selection.bind, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.draw(0..3, 0..1);
        }

        // the predicted tail of a painting stroke, over the canvas
        let style = self.active.filter(|a| !a.pencil).map(|a| a.style).filter(|s| s.tool == StrokeTool::Paint);
        if let Some(style) = style.filter(|_| !predicted.is_empty()) {
            let overlay_pipeline = overlay.entry(target.format).or_insert_with(|| {
                device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                    label: Some("prediction"),
                    layout: Some(stamp_layout),
                    vertex: wgpu::VertexState {
                        module: overlay_module,
                        entry_point: Some("vs_overlay"),
                        compilation_options: Default::default(),
                        buffers: &[Some(wgpu::VertexBufferLayout {
                            array_stride: size_of::<StampInstance>() as u64,
                            step_mode: wgpu::VertexStepMode::Instance,
                            attributes: &STAMP_ATTRIBUTES,
                        })],
                    },
                    primitive: wgpu::PrimitiveState::default(),
                    depth_stencil: None,
                    multisample: wgpu::MultisampleState::default(),
                    fragment: Some(wgpu::FragmentState {
                        module: overlay_module,
                        entry_point: Some("fs_overlay"),
                        compilation_options: Default::default(),
                        targets: &[Some(wgpu::ColorTargetState {
                            format: target.format,
                            blend: Some(PREMULTIPLIED_OVER),
                            write_mask: wgpu::ColorWrites::ALL,
                        })],
                    }),
                    multiview_mask: None,
                    cache: kept,
                })
            });
            let c = target.to_screen.then(to_clip);
            let offset = uniforms.push(&OverlayUniform {
                m0: [c.a, c.b, c.c, c.d],
                m1: [c.e, c.f, target.to_screen.scale_factor(), style.hardness.clamp(0.0, 1.0)],
                color: {
                    let [r, g, b] = convert_colour(style.color, target.convert);
                    [r, g, b, style.opacity.clamp(0.0, 1.0)]
                },
                shape: style.shape.map_or(-1, |s| s as i32),
                srgb: u32::from(srgb),
                shape_lod_cap: style.shape_lod_cap,
                _pad: 0,
            });
            let size = size_of::<StampInstance>() as u64;
            pass.set_pipeline(overlay_pipeline);
            pass.set_bind_group(0, &self.uniform_bind, &[offset]);
            pass.set_bind_group(1, own_images.as_ref().unwrap_or(&self.brush_bind), &[]);
            pass.set_vertex_buffer(
                0,
                self.stamp_buffer.slice(u64::from(predicted.start) * size..u64::from(predicted.end) * size),
            );
            pass.draw(0..6, 0..predicted.end - predicted.start);
        }

        // the glass, over everything
        if let (Some((backdrop, k)), Some(small), Some(frost)) =
            (glass, self.backdrop.as_ref(), frost.get(&target.format))
        {
            draw_glass(&mut pass, frost, small, &self.uniform_bind, uniforms, backdrop, target.size, k);
        }
    }

    /// Frosted panels over a picture something else drew on `target` (a model): `scene` draws it
    /// again into the view it is given (of the target's format, cleared, the size given), small,
    /// and that is blurred and drawn into each region of the target.
    #[allow(clippy::too_many_arguments)]
    pub fn frost_over(
        &mut self,
        gpu: &Gpu,
        target: &wgpu::TextureView,
        format: wgpu::TextureFormat,
        size: (u32, u32),
        surround: [f32; 3],
        backdrop: Backdrop<'_>,
        scene: SmallScene<'_>,
    ) {
        if (backdrop.regions.is_empty() && backdrop.windows.is_empty()) || backdrop.sigma <= 0.0 {
            return;
        }
        let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("frost") });
        let mut uniforms = UniformData::default();
        let target = Target {
            view: target,
            format,
            size,
            to_screen: Affine::IDENTITY,
            surround,
            grid: None,
            selection: None,
            backdrop: None,
            convert: None,
            lut: None,
            crisp_from: f32::INFINITY,
            look: None,
        };
        let k = self.frost_ready(&gpu.device, &mut encoder, &mut uniforms, &target, backdrop, Some(scene));
        if let (Some(small), Some(frost)) = (self.backdrop.as_ref(), self.screen.frost.get(&target.format)) {
            let mut pass = render_pass(&mut encoder, "frost", target.view, wgpu::LoadOp::Load);
            draw_glass(&mut pass, frost, small, &self.uniform_bind, &mut uniforms, backdrop, target.size, k);
        }
        self.write_uniforms(gpu, &uniforms);
        gpu.queue.submit([encoder.finish()]);
    }

    /// Frosted panels: the canvas and its surround (or what `other` draws) drawn again, small, and
    /// blurred, for [`draw_glass`]. Returns how many times smaller it is.
    pub(super) fn frost_ready(
        &mut self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        uniforms: &mut UniformData,
        target: &Target<'_>,
        backdrop: Backdrop<'_>,
        other: Option<SmallScene<'_>>,
    ) -> f32 {
        let k = (backdrop.sigma / self.backdrop_texels).floor().clamp(1.0, 16.0);
        let size = ((target.size.0 as f32 / k).ceil() as u32, (target.size.1 as f32 / k).ceil() as u32);
        if self.backdrop.as_ref().is_none_or(|b| b.size != size || b.format != target.format) {
            self.backdrop =
                Some(BackdropGpu::new(device, &self.pipelines.sampled_layout, &self.sampler, size, target.format));
        }
        // blurred across this many target pixels each way (and a window's tent, a texel more)
        let sigma = backdrop.sigma / k;
        let radius = (sigma * 3.0).ceil().min(64.0);
        let made = FrostMade {
            to_screen: target.to_screen,
            k,
            sigma,
            surround: target.surround,
            convert: target.convert,
            lut: target.lut.map(|l| l.id),
            look: target.look,
        };
        // the scene as drawn last still shows where the glass is: kept (another's scene never is)
        let reach = (radius + 2.0) * k;
        let near = self.frost_stale.intersect(self.full());
        let kept = other.is_none()
            && self.backdrop.as_ref().is_some_and(|b| b.made == Some(made))
            && !reaches_glass(near, target.to_screen, reach, backdrop);
        if kept {
            return k;
        }
        let Some(small) = self.backdrop.as_mut() else { return k };
        small.made = other.is_none().then_some(made);
        self.frost_stale = IRect::EMPTY;
        self.frosts += 1;
        let Some(small) = self.backdrop.as_ref() else { return k };
        let srgb = target.format.is_srgb();
        let Pipelines { display_module, display_pipeline_layout, display_lut_layout, backdrop_module, .. } =
            &*self.pipelines;
        let kept = self.pipelines.cache.as_ref();
        let ScreenPipelines { display, blur, frost, .. } = &mut self.screen;
        let display = display.entry(target.format).or_insert_with(|| {
            // over the surround, which shows through see-through parts (a hidden background)
            blended_pipeline(
                device,
                kept,
                display_lut_layout,
                display_module,
                "display",
                ("vs_display", "fs_display"),
                target.format,
                Some(PREMULTIPLIED_OVER),
            )
        });
        let blur = blur.entry(target.format).or_insert_with(|| {
            sampled_pipeline(
                device,
                kept,
                display_pipeline_layout,
                backdrop_module,
                "blur",
                "vs_full",
                "fs_blur",
                target.format,
            )
        });
        frost.entry(target.format).or_insert_with(|| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("frost"),
                layout: Some(&*display_pipeline_layout),
                vertex: wgpu::VertexState {
                    module: backdrop_module,
                    entry_point: Some("vs_frost"),
                    compilation_options: Default::default(),
                    buffers: &[],
                },
                primitive: wgpu::PrimitiveState::default(),
                depth_stencil: None,
                multisample: wgpu::MultisampleState::default(),
                fragment: Some(wgpu::FragmentState {
                    module: backdrop_module,
                    entry_point: Some("fs_frost"),
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: target.format,
                        blend: Some(PREMULTIPLIED_OVER),
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                multiview_mask: None,
                cache: kept,
            })
        });

        // the scene, small: the surround and the canvas (its grid lines and the selection's look
        // would not show through the blur)
        let (w, h) = (size.0.max(1) as f32, size.1.max(1) as f32);
        let to_clip = Affine { a: 2.0 / w, b: 0.0, c: 0.0, d: -2.0 / h, e: -1.0, f: 1.0 };
        let unit_to_canvas = Affine { a: self.width as f32, b: 0.0, c: 0.0, d: self.height as f32, e: 0.0, f: 0.0 };
        let shrink = Affine { a: 1.0 / k, b: 0.0, c: 0.0, d: 1.0 / k, e: 0.0, f: 0.0 };
        let m = unit_to_canvas.then(target.to_screen).then(shrink).then(to_clip);
        let offset = uniforms.push(
            &DisplayUniform::new(
                [m.a, m.b, m.c, m.d],
                [m.e, m.f, if srgb { 1.0 } else { 0.0 }, 0.0],
                target.convert,
                target.lut.is_some(),
            )
            .looking(target.look)
            .smoothed(&m, (w, h)),
        );
        let surround = target.surround.map(|c| if srgb { srgb_to_linear(c) } else { c });
        let clear =
            wgpu::Color { r: f64::from(surround[0]), g: f64::from(surround[1]), b: f64::from(surround[2]), a: 1.0 };
        if let Some(draw) = other {
            draw(encoder, &small.views[0], size);
        } else {
            let mut pass = render_pass(encoder, "backdrop scene", &small.views[0], wgpu::LoadOp::Clear(clear));
            pass.set_pipeline(display);
            pass.set_bind_group(0, &self.display_bind, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.set_bind_group(2, target.lut.map_or(&self.identity_lut, |l| &l.bind), &[]);
            pass.draw(0..6, 0..1);
        }
        // blurred across, then down
        for (from, to, step) in
            [(&small.binds[0], &small.views[1], [1.0 / w, 0.0]), (&small.binds[1], &small.views[2], [0.0, 1.0 / h])]
        {
            let offset =
                uniforms.push(&BackdropUniform { v0: [step[0], step[1], sigma, radius], v1: [0.0; 4], v2: [0.0; 4] });
            let mut pass = render_pass(encoder, "backdrop blur", to, wgpu::LoadOp::Clear(clear));
            pass.set_pipeline(blur);
            pass.set_bind_group(0, from, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.draw(0..3, 0..1);
        }
        k
    }
}
