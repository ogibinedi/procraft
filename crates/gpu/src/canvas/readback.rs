//! Thumbnails, alpha sampling, layer upload/copy/resample, and GPU readback on [`Canvas`].

use super::*;

impl Canvas {
    /// A layer scaled to fit `max_side` pixels (premultiplied). Runs what is queued first, then
    /// waits for the GPU (see [`Canvas::start_layer_thumbnail`] for the same with the waits left
    /// to the caller).
    pub fn layer_thumbnail(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        id: LayerId,
        max_side: u32,
    ) -> Result<Thumbnail, GpuError> {
        match self.start_layer_thumbnail(gpu, doc, id, max_side)? {
            LayerThumbnailStep::Ready(t) => Ok(t),
            LayerThumbnailStep::Coarse { pending, mask } => {
                let coarse = pending.pixels()?;
                self.start_layer_thumbnail_fine(gpu, id, &coarse, mask, max_side)?.pixels()
            }
        }
    }

    /// [`Canvas::layer_thumbnail`]'s first step: the picture already (a parked layer's), or a
    /// coarse copy of the layer on its way back (8 pixels to one, fine enough not to miss a dot),
    /// for the caller to wait for with whatever guards the canvas let go, then hand to
    /// [`Canvas::start_layer_thumbnail_fine`].
    pub fn start_layer_thumbnail(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        id: LayerId,
        max_side: u32,
    ) -> Result<LayerThumbnailStep, GpuError> {
        self.render(gpu, doc, None);
        let mask = doc.layer(id).is_some_and(|l| l.kind == LayerKind::Mask);
        if let Some(p) = self.parked_pictures(id, max_side).filter(|_| !mask) {
            return Ok(LayerThumbnailStep::Ready(shrink(p.drawn.as_ref().unwrap_or(&p.whole), max_side)));
        }
        self.restore_all(gpu, [id], false);
        let layer = self.layers.get(&id).ok_or_else(|| GpuError::Readback(format!("no layer {id:?}")))?;
        let side = (self.width.max(self.height) / 8).clamp(64, 2048);
        Ok(LayerThumbnailStep::Coarse { pending: self.start_sample_view(gpu, &layer.view, self.full(), side), mask })
    }

    /// [`Canvas::layer_thumbnail`]'s second step, with the coarse copy back: the read of the
    /// region it is painted in (or all of it, if nothing showed), cut to what is painted there
    /// once it is back.
    pub fn start_layer_thumbnail_fine(
        &mut self,
        gpu: &Gpu,
        id: LayerId,
        coarse: &Thumbnail,
        mask: bool,
        max_side: u32,
    ) -> Result<FineThumbnail, GpuError> {
        // parked again meanwhile, perhaps
        self.restore_all(gpu, [id], false);
        let full = self.full();
        let layer = self.layers.get(&id).ok_or_else(|| GpuError::Readback(format!("no layer {id:?}")))?;
        // a mask is white where nothing is painted on it
        let bounds: fn(&Thumbnail) -> Option<[u32; 4]> = if mask { ink_bounds } else { alpha_bounds };
        let Some([x0, y0, x1, y1]) = bounds(coarse) else {
            return Ok(FineThumbnail {
                pending: self.start_sample_view(gpu, &layer.view, full, max_side),
                cut_mask: None,
            });
        };
        let (sx, sy) =
            (self.width as f32 / coarse.width.max(1) as f32, self.height as f32 / coarse.height.max(1) as f32);
        let rect = IRect {
            x0: ((x0 as f32 - 1.0) * sx).floor() as i32,
            y0: ((y0 as f32 - 1.0) * sy).floor() as i32,
            x1: ((x1 as f32 + 1.0) * sx).ceil() as i32,
            y1: ((y1 as f32 + 1.0) * sy).ceil() as i32,
        }
        .intersect(full);
        Ok(FineThumbnail { pending: self.start_sample_view(gpu, &layer.view, rect, max_side), cut_mask: Some(mask) })
    }

    /// `rect` of a layer box-filtered to fit `max_side` pixels (never enlarged), read back.
    pub(super) fn sample_layer(&self, gpu: &Gpu, id: LayerId, rect: IRect, max_side: u32) -> Result<Thumbnail, GpuError> {
        let layer = self.layers.get(&id).ok_or_else(|| GpuError::Readback(format!("no layer {id:?}")))?;
        self.sample_view(gpu, &layer.view, rect, max_side)
    }

    /// Each paint layer's coverage (alpha, 0 to 255) at canvas pixel (`x`, `y`), in the
    /// document's order; none for a point off the canvas.
    pub fn alphas_at(&mut self, gpu: &Gpu, doc: &mut Document, x: u32, y: u32) -> Result<Vec<(LayerId, u8)>, GpuError> {
        self.start_alphas_at(gpu, doc, x, y).alphas()
    }

    /// [`Canvas::alphas_at`] started: the layers' pixels there are copied back while the canvas is
    /// let go, and waited for by [`PendingAlphas::alphas`] (a touch picking a layer needn't hold the
    /// canvas while the GPU finishes what it was given before).
    pub fn start_alphas_at(&mut self, gpu: &Gpu, doc: &mut Document, x: u32, y: u32) -> PendingAlphas {
        if x >= self.width || y >= self.height {
            return PendingAlphas::default();
        }
        self.render(gpu, doc, None);
        let paint: Vec<LayerId> = doc.layers().iter().filter(|l| l.kind == LayerKind::Paint).map(|l| l.id).collect();
        let ids: Vec<LayerId> = paint.iter().copied().filter(|id| self.layers.contains_key(id)).collect();
        // a parked layer's from its small picture
        let parked: HashMap<LayerId, u8> = paint
            .iter()
            .filter(|id| !self.layers.contains_key(*id))
            .filter_map(|id| {
                let w = &self.pictures.get(id)?.whole;
                let px = (u64::from(x) * u64::from(w.width) / u64::from(self.width.max(1))) as usize;
                let py = (u64::from(y) * u64::from(w.height) / u64::from(self.height.max(1))) as usize;
                Some((*id, *w.rgba.get((py * w.width as usize + px) * 4 + 3)?))
            })
            .collect();
        if ids.is_empty() {
            return PendingAlphas { paint, ids, parked, read: None };
        }
        // one pixel of each layer, each in its own 256-byte row of a buffer
        let size = 256 * ids.len() as u64;
        let buffer = gpu.readback_buffer(size);
        let mut encoder =
            gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("layer pixels") });
        for (i, id) in ids.iter().enumerate() {
            let Some(layer) = self.layers.get(id) else { continue };
            gpu.copy_texture_to_buffer(
                &mut encoder,
                copy_at(&layer.texture, i32::try_from(x).unwrap_or(0), i32::try_from(y).unwrap_or(0)),
                wgpu::TexelCopyBufferInfo {
                    buffer: &buffer,
                    layout: wgpu::TexelCopyBufferLayout {
                        offset: 256 * i as u64,
                        bytes_per_row: Some(256),
                        rows_per_image: Some(1),
                    },
                },
                extent(1, 1),
            );
        }
        gpu.queue.submit([encoder.finish()]);
        let (tx, rx) = mpsc::channel();
        buffer.slice(..size).map_async(wgpu::MapMode::Read, move |r| {
            let _ = tx.send(r);
        });
        // each layer's pixel the first 4 bytes of its row
        let read = Readback::Copying {
            device: gpu.device.clone(),
            lost: gpu.lost.clone(),
            buffer,
            size,
            kept: gpu.readbacks.clone(),
            row: 4,
            padded: 256,
            mapped: rx,
        };
        PendingAlphas { paint, ids, parked, read: Some(read) }
    }

    /// Layer `id` over the whole canvas (not cropped to what is drawn) fitted to `max_side`, as
    /// Animation Assist's timeline shows a frame.
    pub fn layer_picture(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        id: LayerId,
        max_side: u32,
    ) -> Result<Thumbnail, GpuError> {
        self.render(gpu, doc, None);
        if let Some(p) = self.parked_pictures(id, max_side) {
            return Ok(shrink(&p.whole, max_side));
        }
        self.restore_all(gpu, [id], false);
        let full = self.full();
        self.sample_layer(gpu, id, full, max_side)
    }

    /// The picture as it shows (while the caches are remade, as it was), scaled to fit
    /// `max_side` pixels: quick enough to ask for while it is drawn on.
    pub fn composite_thumbnail(&mut self, gpu: &Gpu, doc: &mut Document, max_side: u32) -> Result<Thumbnail, GpuError> {
        // while the caches are remade a render leaves the picture as it is: none, so this never
        // does that work (on the main thread, as the artwork closes)
        if !self.busy() {
            self.render(gpu, doc, None);
        }
        self.sample_view(gpu, &self.composite_view, self.full(), max_side)
    }

    /// [`Canvas::composite_thumbnail`] without the layers `left_out` (a time-lapse frame leaves
    /// private layers out), as [`Canvas::read_composite_without`] makes the picture; the canvas's
    /// own composite is made again after, whole (a frame is taken after every change: left to
    /// later renders, a large canvas would show without those layers for a moment each time).
    pub fn composite_thumbnail_without(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        max_side: u32,
        left_out: &[LayerId],
    ) -> Result<Thumbnail, GpuError> {
        if left_out.is_empty() {
            return self.composite_thumbnail(gpu, doc, max_side);
        }
        // the work queued done first, as it may change `doc`
        self.render(gpu, doc, None);
        let mut shown = doc.clone();
        for &id in left_out {
            shown.set_visible(id, false);
        }
        self.invalidate();
        self.render_finished(gpu, &mut shown);
        let thumbnail = self.start_sample_view(gpu, &self.composite_view, self.full(), max_side);
        self.invalidate();
        self.render_finished(gpu, doc);
        thumbnail.pixels()
    }

    /// [`Canvas::composite_thumbnail_without`] started: the picture's copy queued, its pixels to be
    /// waited for once whatever guards the canvas is let go (a time-lapse frame, taken after each
    /// change, would otherwise hold the next touches up while the GPU copies it).
    pub fn start_composite_thumbnail_without(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        max_side: u32,
        left_out: &[LayerId],
    ) -> PendingThumbnail {
        if left_out.is_empty() {
            if !self.busy() {
                self.render(gpu, doc, None);
            }
            return self.start_sample_view(gpu, &self.composite_view, self.full(), max_side);
        }
        self.render(gpu, doc, None);
        let mut shown = doc.clone();
        for &id in left_out {
            shown.set_visible(id, false);
        }
        self.invalidate();
        self.render_finished(gpu, &mut shown);
        let thumbnail = self.start_sample_view(gpu, &self.composite_view, self.full(), max_side);
        self.invalidate();
        self.render_finished(gpu, doc);
        thumbnail
    }

    /// `rect` of a texture box-filtered to fit `max_side` pixels (never enlarged), read back.
    pub(super) fn sample_view(
        &self,
        gpu: &Gpu,
        source: &wgpu::TextureView,
        rect: IRect,
        max_side: u32,
    ) -> Result<Thumbnail, GpuError> {
        self.start_sample_view(gpu, source, rect, max_side).pixels()
    }

    /// [`Canvas::sample_view`] started: the copy back queued, the pixels waited for later.
    pub(super) fn start_sample_view(&self, gpu: &Gpu, source: &wgpu::TextureView, rect: IRect, max_side: u32) -> PendingThumbnail {
        let side = max_side.clamp(1, 2048) as f32;
        let (rw, rh) = (rect.width().max(1) as f32, rect.height().max(1) as f32);
        let scale = (side / rw).min(side / rh).min(1.0);
        let (w, h) = (((rw * scale).round() as u32).max(1), ((rh * scale).round() as u32).max(1));
        let small = texture(&gpu.device, "thumbnail", w, h, LAYER_FORMAT);
        let view = small.create_view(&wgpu::TextureViewDescriptor::default());
        let bind = sampled_bind(&gpu.device, &self.pipelines.sampled_layout, source, &self.sampler);
        let mut data = UniformData::default();
        // the target size and the source pixels each target pixel covers; the source rectangle
        let (cw, ch) = (self.width.max(1) as f32, self.height.max(1) as f32);
        let offset = data.push(&DisplayUniform::new(
            [w as f32, h as f32, rw / w as f32, rh / h as f32],
            [rect.x0 as f32 / cw, rect.y0 as f32 / ch, rw / cw, rh / ch],
            None,
            false,
        ));
        let mut encoder =
            gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("thumbnail") });
        {
            let mut pass = render_pass(&mut encoder, "thumbnail", &view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
            pass.set_pipeline(&self.pipelines.thumb);
            pass.set_bind_group(0, &bind, &[]);
            pass.set_bind_group(1, &self.uniform_bind, &[offset]);
            pass.draw(0..3, 0..1);
        }
        self.write_uniforms(gpu, &data);
        gpu.queue.submit([encoder.finish()]);
        PendingThumbnail { width: w, height: h, read: start_reading(gpu, &small, w, h) }
    }

    /// Layer `id` of canvas `from` (on the same device) as this canvas's, its pixels moved by
    /// whole pixels: this canvas's (x, y) is `from`'s (x + `offset.0`, y + `offset.1`), clear
    /// where that is off `from`. Copied on the GPU; false (nothing done) if `from` has no texture
    /// for it (a parked layer). Not an undo step.
    pub fn copy_layer_from(&mut self, gpu: &Gpu, from: &Canvas, id: LayerId, offset: (i64, i64)) -> bool {
        let Some(source) = from.layers.get(&id) else { return false };
        if !self.layers.contains_key(&id) {
            let texture = texture(&gpu.device, "layer", self.width, self.height, LAYER_FORMAT);
            let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
            self.layers.insert(id, LayerGpu { texture, view });
        }
        self.touched(id);
        self.let_go_caches(false);
        self.parked.remove(&id);
        self.pictures.remove(&id);
        let Some(layer) = self.layers.get(&id) else { return false };
        // the part of this canvas that falls on `from`
        let clamp = |v: i64, hi: u32| v.clamp(0, i64::from(hi));
        let (x0, x1) = (clamp(-offset.0, self.width), clamp(i64::from(from.width) - offset.0, self.width));
        let (y0, y1) = (clamp(-offset.1, self.height), clamp(i64::from(from.height) - offset.1, self.height));
        let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("crop") });
        // what was there first cleared (a layer made just now is clear already)
        drop(render_pass(&mut encoder, "crop clear", &layer.view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT)));
        if x1 > x0 && y1 > y0 {
            let at = |v: i64| i32::try_from(v).unwrap_or(0);
            encoder.copy_texture_to_texture(
                copy_at(&source.texture, at(x0 + offset.0), at(y0 + offset.1)),
                copy_at(&layer.texture, at(x0), at(y0)),
                extent(u32::try_from(x1 - x0).unwrap_or(0), u32::try_from(y1 - y0).unwrap_or(0)),
            );
        }
        gpu.queue.submit([encoder.finish()]);
        self.invalidate();
        true
    }

    /// Layer `id` of canvas `from` (on the same device) seen through a crop as this canvas's:
    /// each pixel's middle mapped onto `from` by `to_old` (a, b, c, d, e, f: x' = a x + c y + e,
    /// y' = b x + d y + f), bilinear, clear past its edges, as the engine's crop resamples on the
    /// CPU. Drawn on the GPU; false (nothing done) if `from` has no texture for it (a parked
    /// layer). Not an undo step.
    pub fn resample_layer_from(&mut self, gpu: &Gpu, from: &Canvas, id: LayerId, to_old: [f32; 6]) -> bool {
        let Some(source) = from.layers.get(&id) else { return false };
        if !self.layers.contains_key(&id) {
            let texture = texture(&gpu.device, "layer", self.width, self.height, LAYER_FORMAT);
            let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
            self.layers.insert(id, LayerGpu { texture, view });
        }
        self.touched(id);
        self.let_go_caches(false);
        self.parked.remove(&id);
        self.pictures.remove(&id);
        let Some(layer) = self.layers.get(&id) else { return false };
        let [a, b, c, d, e, f] = to_old;
        let map = buffer_init(
            &gpu.device,
            "crop",
            bytemuck::cast_slice(&[a, b, c, d, e, f, 0.0, 0.0]),
            wgpu::BufferUsages::UNIFORM,
        );
        let bind = gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("crop"),
            layout: &self.pipelines.crop_layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&source.view) },
                wgpu::BindGroupEntry { binding: 1, resource: map.as_entire_binding() },
            ],
        });
        let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("crop") });
        let mut pass = render_pass(&mut encoder, "crop", &layer.view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
        pass.set_pipeline(&self.pipelines.crop);
        pass.set_bind_group(0, &bind, &[]);
        pass.draw(0..3, 0..1);
        drop(pass);
        gpu.queue.submit([encoder.finish()]);
        self.invalidate();
        true
    }

    /// Replaces a layer's pixels (premultiplied RGBA8, the canvas's size, rows top to bottom), for
    /// opening a saved artwork. Not an undo step.
    pub fn upload_layer(&mut self, gpu: &Gpu, id: LayerId, rgba: &[u8]) -> Result<(), GpuError> {
        self.upload_layer_part(gpu, id, self.full(), rgba)
    }

    /// [`Canvas::upload_layer`] for the pixels inside `rect` only (`part`, its rows): for a layer
    /// with nothing on it elsewhere (a new one). Not an undo step.
    pub fn upload_layer_part(&mut self, gpu: &Gpu, id: LayerId, rect: IRect, part: &[u8]) -> Result<(), GpuError> {
        let expected = rect.width() as usize * rect.height() as usize * 4;
        if rect.is_empty() || rect.intersect(self.full()) != rect || part.len() != expected {
            return Err(GpuError::Readback(format!("layer {id:?}: {} bytes, expected {expected}", part.len())));
        }
        self.put_layer_pixels(gpu, id, Some(rect), part, 0, rect.width() * 4);
        Ok(())
    }

    /// [`Canvas::upload_layer`] for a layer as just made (clear): of `rgba`, the canvas's size,
    /// only `painted` is sent (where it isn't clear; none: nothing is). Not an undo step.
    pub fn upload_layer_painted(
        &mut self,
        gpu: &Gpu,
        id: LayerId,
        rgba: &[u8],
        painted: Option<IRect>,
    ) -> Result<(), GpuError> {
        let full = self.full();
        let expected = full.width() as usize * full.height() as usize * 4;
        if rgba.len() != expected {
            return Err(GpuError::Readback(format!("layer {id:?}: {} bytes, expected {expected}", rgba.len())));
        }
        let rect = painted.map(|r| r.intersect(full)).filter(|r| !r.is_empty());
        // the box's first pixel, read with the whole picture's rows
        let offset = rect
            .map_or(0, |r| (u64::from(r.y0.max(0) as u32) * u64::from(self.width) + u64::from(r.x0.max(0) as u32)) * 4);
        self.put_layer_pixels(gpu, id, rect, rgba, offset, self.width * 4);
        Ok(())
    }

    /// Writes `data` (from `offset`, rows `row` bytes apart) into layer `id` within `rect` (none:
    /// nothing written, the layer made if it wasn't), the layer made if it wasn't, its compressed
    /// copy and the caches let go.
    pub(super) fn put_layer_pixels(&mut self, gpu: &Gpu, id: LayerId, rect: Option<IRect>, data: &[u8], offset: u64, row: u32) {
        if !self.layers.contains_key(&id) {
            let texture = texture(&gpu.device, "layer", self.width, self.height, LAYER_FORMAT);
            let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
            self.layers.insert(id, LayerGpu { texture, view });
        }
        self.touched(id);
        self.let_go_caches(false);
        // its compressed copy no longer what it holds
        self.parked.remove(&id);
        self.pictures.remove(&id);
        self.invalidate();
        let (Some(layer), Some(rect)) = (self.layers.get(&id), rect) else { return };
        // sent now, so many layers in a row (an artwork opening, a moving picture pasted) go up
        // through the same buffer
        self.upload_now(gpu, &layer.texture, rect, data, offset as usize, row as usize);
    }

    /// Copies `rect` of `texture` from `data` (from `offset`, rows `row` bytes apart) in
    /// `encoder`, through an upload buffer: returns it, to give back once `encoder` is sent.
    #[allow(clippy::too_many_arguments)]
    pub(super) fn upload(
        &self,
        device: &wgpu::Device,
        encoder: &mut wgpu::CommandEncoder,
        texture: &wgpu::Texture,
        rect: IRect,
        data: &[u8],
        offset: usize,
        row: usize,
    ) -> Option<wgpu::Buffer> {
        let (w, h) = (rect.width(), rect.height());
        let last = offset + (h as usize).saturating_sub(1) * row + w as usize * 4;
        if w == 0 || h == 0 || data.len() < last {
            log::error!("an upload of {w} x {h} given {} bytes", data.len());
            return None;
        }
        // copies from a buffer take rows 256 bytes apart
        let stride = (w * 4).div_ceil(256) * 256;
        let size = u64::from(stride) * u64::from(h);
        let buffer = self.uploads.take(device, size);
        {
            let Ok(mut view) = buffer.slice(..size).get_mapped_range_mut() else {
                log::error!("an upload buffer wasn't mapped");
                return None;
            };
            let (wide, stride) = (w as usize * 4, stride as usize);
            for y in 0..h as usize {
                let from = offset + y * row;
                if let Some(src) = data.get(from..from + wide) {
                    view.slice(y * stride..y * stride + wide).copy_from_slice(src);
                }
            }
        }
        buffer.unmap();
        encoder.copy_buffer_to_texture(
            wgpu::TexelCopyBufferInfo {
                buffer: &buffer,
                layout: wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(stride), rows_per_image: Some(h) },
            },
            copy_at(texture, rect.x0, rect.y0),
            extent(w, h),
        );
        Some(buffer)
    }

    /// [`Canvas::upload`] sent at once, ahead of whatever is recorded but not yet sent.
    pub(super) fn upload_now(
        &self,
        gpu: &Gpu,
        texture: &wgpu::Texture,
        rect: IRect,
        data: &[u8],
        offset: usize,
        row: usize,
    ) {
        let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("upload") });
        let used = self.upload(&gpu.device, &mut encoder, texture, rect, data, offset, row);
        gpu.queue.submit([encoder.finish()]);
        if let Some(buffer) = used {
            self.uploads.give_back(buffer);
        }
    }

    /// A colour table for [`Target::lut`] from `lut` (its first three outputs, 0..1 encoded).
    pub fn display_lut(&self, gpu: &Gpu, lut: &procraft_engine::cms::Lut) -> DisplayLut {
        let rgba: Vec<u8> = lut
            .values
            .chunks(lut.outputs.max(1))
            .flat_map(|v| {
                let c = |k: usize| (v.get(k).copied().unwrap_or(0.0).clamp(0.0, 1.0) * 255.0).round() as u8;
                [c(0), c(1), c(2), 255]
            })
            .collect();
        static MADE: AtomicU64 = AtomicU64::new(0);
        let id = MADE.fetch_add(1, Ordering::Relaxed);
        DisplayLut { bind: lut_bind(gpu, &self.pipelines.lut_layout, lut.grid as u32, &rgba), id }
    }

    /// The composite's pixels (premultiplied RGBA8, rows top to bottom) as last rendered: after
    /// [`Canvas::render_finished`], the picture as it is. Waits for the GPU.
    pub fn read_composite(&self, gpu: &Gpu) -> Result<Vec<u8>, GpuError> {
        read_texture(gpu, &self.composite, self.width, self.height)
    }

    /// The composite's pixel at canvas pixel (`x`, `y`) as last rendered (premultiplied RGBA8);
    /// none off the canvas. Waits for the GPU.
    pub fn read_composite_pixel(&self, gpu: &Gpu, x: u32, y: u32) -> Option<[u8; 4]> {
        if x >= self.width || y >= self.height {
            return None;
        }
        let (x, y) = (i32::try_from(x).ok()?, i32::try_from(y).ok()?);
        let px = start_reading_part(gpu, &self.composite, IRect::new(x, y, x + 1, y + 1)).pixels().ok()?;
        Some([*px.first()?, *px.get(1)?, *px.get(2)?, *px.get(3)?])
    }

    /// The composite's pixels as they would be without the layers `left_out` (the gallery's
    /// thumbnail leaves private layers out); the canvas's own composite is made again after.
    pub fn read_composite_without(
        &mut self,
        gpu: &Gpu,
        doc: &mut Document,
        left_out: &[LayerId],
    ) -> Result<Vec<u8>, GpuError> {
        self.render_finished(gpu, doc);
        if left_out.is_empty() {
            return self.read_composite(gpu);
        }
        let mut shown = doc.clone();
        for &id in left_out {
            shown.set_visible(id, false);
        }
        self.invalidate();
        self.render_finished(gpu, &mut shown);
        let read = self.read_composite(gpu);
        self.invalidate();
        self.render(gpu, doc, None);
        read
    }

    /// The composite's pixels as they would be without the background (see-through where no
    /// layer is painted): what the layers seen hold together. Made as
    /// [`Canvas::read_composite_without`] makes its own.
    pub fn read_composite_without_background(&mut self, gpu: &Gpu, doc: &mut Document) -> Result<Vec<u8>, GpuError> {
        self.render_finished(gpu, doc);
        if !doc.background_visible {
            return self.read_composite(gpu);
        }
        let mut shown = doc.clone();
        shown.background_visible = false;
        self.invalidate();
        self.render_finished(gpu, &mut shown);
        let read = self.read_composite(gpu);
        self.invalidate();
        self.render(gpu, doc, None);
        read
    }

    /// Starts reading a layer's pixels back (premultiplied RGBA8): see [`Readback`].
    pub fn start_reading_layer(&self, gpu: &Gpu, id: LayerId) -> Result<Readback, GpuError> {
        if self.parked.contains_key(&id) && !self.layers.contains_key(&id) {
            return self.read_layer(gpu, id).map(Readback::Ready);
        }
        let layer = self.layers.get(&id).ok_or_else(|| GpuError::Readback(format!("no layer {id:?}")))?;
        Ok(start_reading(gpu, &layer.texture, self.width, self.height))
    }

    /// Starts reading the composite back as it would be without the layers `left_out`, as
    /// [`Canvas::read_composite_without`] does: see [`Readback`].
    pub fn start_reading_composite_without(&mut self, gpu: &Gpu, doc: &mut Document, left_out: &[LayerId]) -> Readback {
        self.render_finished(gpu, doc);
        if left_out.is_empty() {
            return start_reading(gpu, &self.composite, self.width, self.height);
        }
        let mut shown = doc.clone();
        for &id in left_out {
            shown.set_visible(id, false);
        }
        self.invalidate();
        self.render_finished(gpu, &mut shown);
        // copied before the composite is made again below
        let read = start_reading(gpu, &self.composite, self.width, self.height);
        self.invalidate();
        self.render(gpu, doc, None);
        read
    }

    /// Where each of `layers` is painted: the box around its pixels that aren't clear, none for one
    /// with nothing on it (or not on the canvas). Found on the GPU, which sends back only a few
    /// bytes a row and column; a parked layer's is known already. Waits for the GPU.
    pub fn painted_boxes(&self, gpu: &Gpu, layers: &[LayerId]) -> Vec<Option<IRect>> {
        self.painted_boxes_or_why(gpu, layers).into_iter().map(|b| b.ok().flatten()).collect()
    }

    /// [`Canvas::painted_boxes`], an error where the GPU didn't say (rather than nothing painted).
    pub fn painted_boxes_or_why(&self, gpu: &Gpu, layers: &[LayerId]) -> Vec<Result<Option<IRect>, GpuError>> {
        let sources = layers
            .iter()
            .map(|id| match self.layers.get(id) {
                Some(layer) => Ok(gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("bounds"),
                    layout: &self.pipelines.bounds_layout,
                    entries: &[wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(&layer.view),
                    }],
                })),
                None => Err(self.parked.get(id).and_then(|p| p.rect)),
            })
            .collect();
        self.boxes(gpu, sources, [&self.pipelines.bounds_rows, &self.pipelines.bounds_columns])
    }

    /// Where the layers being transformed or adjusted differ from their copies from when it
    /// began, all drawn so far: the box around the pixels changed, inner none if none did; none
    /// if it can't be told (nothing open, or the GPU didn't say). Waits for the GPU.
    pub fn changed_box(&self, gpu: &Gpu) -> Option<Option<IRect>> {
        let open: Vec<&Transforming> = self.transforming.iter().chain(&self.transform_more).collect();
        if open.is_empty() {
            return None;
        }
        let mut sources = Vec::new();
        for t in open {
            let layer = self.layers.get(&t.layer)?;
            let before = t.before.create_view(&wgpu::TextureViewDescriptor::default());
            sources.push(Ok(gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("changed"),
                layout: &self.pipelines.changed_layout,
                entries: &[
                    wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&layer.view) },
                    wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::TextureView(&before) },
                ],
            })));
        }
        let boxes = self.boxes(gpu, sources, [&self.pipelines.changed_rows, &self.pipelines.changed_columns]);
        let boxes: Vec<Option<IRect>> = boxes.into_iter().collect::<Result<_, _>>().ok()?;
        Some(boxes.into_iter().flatten().reduce(IRect::union))
    }

    /// The boxes the GPU's passes (`pipelines`: across rows, down columns) find for each of
    /// `sources` (a bind group for them, or a box already known), all drawn and read back
    /// together: none where nothing is marked, an error where the GPU didn't say.
    pub(super) fn boxes(
        &self,
        gpu: &Gpu,
        sources: Vec<Result<wgpu::BindGroup, Option<IRect>>>,
        [rows_pass, columns_pass]: [&wgpu::RenderPipeline; 2],
    ) -> Vec<Result<Option<IRect>, GpuError>> {
        let (w, h) = (self.width, self.height);
        let (across, down) = (w.div_ceil(BOUNDS_STRETCH).max(1), h.div_ceil(BOUNDS_STRETCH).max(1));
        let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("bounds") });
        // each source's rows (h x across) and columns (w x down)
        let lines: Vec<_> = sources
            .into_iter()
            .map(|source| {
                source.map(|bind| {
                    [(rows_pass, (h, across)), (columns_pass, (w, down))].map(|(pipeline, size)| {
                        let target = texture(&gpu.device, "bounds", size.0, size.1, LAYER_FORMAT);
                        let view = target.create_view(&wgpu::TextureViewDescriptor::default());
                        let mut pass =
                            render_pass(&mut encoder, "bounds", &view, wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT));
                        pass.set_pipeline(pipeline);
                        pass.set_bind_group(0, &bind, &[]);
                        pass.draw(0..3, 0..1);
                        drop(pass);
                        (target, size)
                    })
                })
            })
            .collect();
        gpu.queue.submit([encoder.finish()]);
        // all copied back together, then each read
        let reads: Vec<_> =
            lines.into_iter().map(|l| l.map(|t| t.map(|(t, (a, b))| start_reading(gpu, &t, a, b)))).collect();
        // a line marked if any of its stretches is
        let span = |pixels: &[u8], n: u32| -> Option<(i32, i32)> {
            let n = n as usize;
            let marked =
                |i: usize| pixels.chunks_exact(4 * n).any(|stretch| stretch.get(4 * i).is_some_and(|&v| v > 0));
            let first = (0..n).find(|&i| marked(i))?;
            let last = (0..n).rev().find(|&i| marked(i))?;
            Some((i32::try_from(first).ok()?, i32::try_from(last + 1).ok()?))
        };
        reads
            .into_iter()
            .map(|read| match read {
                Err(known) => Ok(known),
                Ok([rows, columns]) => {
                    let (rows, columns) = (rows.pixels()?, columns.pixels()?);
                    let spans = span(&rows, h).zip(span(&columns, w));
                    Ok(spans.map(|((y0, y1), (x0, x1))| IRect::new(x0, y0, x1, y1)))
                }
            })
            .collect()
    }

    /// A layer's pixels inside `rect` (on the canvas), rows top to bottom; none if it is off the
    /// canvas. Waits for the GPU.
    pub fn read_layer_part(&self, gpu: &Gpu, id: LayerId, rect: IRect) -> Result<Vec<u8>, GpuError> {
        let rect = rect.intersect(self.full());
        if rect.is_empty() {
            return Ok(Vec::new());
        }
        if rect == self.full() || (self.parked.contains_key(&id) && !self.layers.contains_key(&id)) {
            return self
                .read_layer(gpu, id)
                .map(|p| if rect == self.full() { p } else { cropped(&p, self.width, rect) });
        }
        let layer = self.layers.get(&id).ok_or_else(|| GpuError::Readback(format!("no layer {id:?}")))?;
        start_reading_part(gpu, &layer.texture, rect).pixels()
    }

    /// A layer's pixels (premultiplied RGBA8). Waits for the GPU.
    pub fn read_layer(&self, gpu: &Gpu, id: LayerId) -> Result<Vec<u8>, GpuError> {
        if let Some(parked) = self.parked.get(&id).filter(|_| !self.layers.contains_key(&id)) {
            return parked
                .pixels(self.width, self.height)
                .ok_or_else(|| GpuError::Readback(format!("layer {id:?} parked damaged")));
        }
        let layer = self.layers.get(&id).ok_or_else(|| GpuError::Readback(format!("no layer {id:?}")))?;
        read_texture(gpu, &layer.texture, self.width, self.height)
    }

    /// A bind group of four textures (layer, stroke, clip base, backdrop) plus the grains.
    pub(super) fn quad(&self, device: &wgpu::Device, views: [&wgpu::TextureView; 4]) -> wgpu::BindGroup {
        let [a, b, c, d] = views;
        self.quad_masked(device, [a, b, c, d], &self.stroke_view, &self.stroke_view)
    }

    /// As [`Self::quad`], with the layer's mask and the mask of the base it is clipped to.
    pub(super) fn quad_masked(
        &self,
        device: &wgpu::Device,
        views: [&wgpu::TextureView; 4],
        mask: &wgpu::TextureView,
        clip_mask: &wgpu::TextureView,
    ) -> wgpu::BindGroup {
        let [a, b, c, d] = views;
        quad_bind(
            device,
            &self.pipelines.quad_layout,
            [a, b, c, d, mask, self.second_view(device), clip_mask],
            self.grain_view(),
            &self.grain_sampler,
            &self.paper_view,
        )
    }

    /// The open stroke's second's texture while it has one (else any, unread).
    pub(super) fn second_view(&self, device: &wgpu::Device) -> &wgpu::TextureView {
        if self.active.is_some_and(|a| a.second.is_some()) {
            self.second_stroke.get(device).1
        } else {
            &self.stroke_view
        }
    }

    /// Layer textures held (the document's and those the history still needs).
    pub fn layer_texture_count(&self) -> usize {
        self.layers.len()
    }

    /// Every texture the canvas holds, each once: its layers, stroke, composite and what that is
    /// made with, its brush images, the undo history's pixels and what any edit under way keeps.
    pub fn textures(&self) -> Vec<wgpu::Texture> {
        let mut all: Vec<&wgpu::Texture> = vec![&self.stroke, &self.composite, &self.shapes, &self.grains, &self.table];
        all.extend(self.layers.values().map(|l| &l.texture));
        all.extend(self.tip_backup.as_ref());
        all.extend(self.fragments.textures());
        all.extend(self.composite_second.made());
        all.extend(self.scratch.made());
        all.extend(self.second_stroke.made());
        all.extend(self.groups.iter().flat_map(|(pair, _)| pair));
        all.extend(self.clip_group.made().into_iter().chain(self.clip_group_second.made()));
        all.push(self.everything.texture());
        all.extend(self.pencil.as_ref().map(|p| p.view.texture()));
        all.extend(self.liquify.as_ref().map(|l| l.view.texture()));
        all.extend(self.selection.as_ref().map(|s| s.view.texture()));
        all.extend(self.held.as_ref().and_then(Snapshot::texture));
        all.extend(self.smudge.iter().flat_map(Smudge::textures));
        for caches in self.caches.iter().chain(self.remake.as_ref().map(|r| &r.caches)) {
            all.extend(caches.below.textures().chain(caches.above.textures()));
        }
        all.extend(self.undo.iter().chain(&self.redo).flat_map(|s| s.pixels.iter().filter_map(Snapshot::texture)));
        all.extend(self.transforming.iter().chain(&self.transform_more).map(|t| &t.before));
        all.extend(self.backdrop.iter().flat_map(|b| b.views.iter().map(wgpu::TextureView::texture)));
        let mut out: Vec<wgpu::Texture> = Vec::with_capacity(all.len());
        for t in all {
            if !out.contains(t) {
                out.push(t.clone());
            }
        }
        out
    }

    /// What the canvas's textures take, bytes (see [`Canvas::textures`]).
    pub fn texture_bytes(&self) -> u64 {
        textures_bytes(&self.textures())
    }
}
