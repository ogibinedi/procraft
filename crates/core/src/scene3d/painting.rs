use super::*;

impl State {
    /// The model's part of a save, if this is a model.
    pub(crate) fn model_snapshot(&mut self) -> Result<Option<ModelSnapshot>, MobileError> {
        let (Some(scene), gpu) = (self.scene.as_mut(), self.gpu.as_ref()) else { return Ok(None) };
        let mut docs = Vec::new();
        let mut layers = Vec::new();
        for (i, set) in scene.sets.iter_mut().enumerate() {
            let Some(set) = set else {
                docs.push(None);
                continue;
            };
            let pixels = set_pixels(set, gpu)?;
            docs.push(Some(set.doc.clone()));
            layers.push((i, pixels));
        }
        // the channels (saved with the colour painted on, so none is the session's own)
        let mut channels = Vec::new();
        let mut channel_layers = Vec::new();
        for (part, pair) in scene.channels.iter_mut().enumerate() {
            let mut docs_of = [None, None];
            for (k, set) in pair.iter_mut().enumerate() {
                let Some(set) = set else { continue };
                channel_layers.push((part, k + 1, set_pixels(set, gpu)?));
                if let Some(d) = docs_of.get_mut(k) {
                    *d = Some(set.doc.clone());
                }
            }
            channels.push(docs_of);
        }
        let orbit = scene.orbit().map(|o| SavedOrbit {
            target: o.target,
            yaw: 0.0,
            pitch: 0.0,
            rotation: Some(o.rotation),
            distance: o.distance,
            fov_y: o.fov,
        });
        // the model as last seen, square as the gallery shows a texture set, on the surround
        let side = THUMBNAIL_SIDE;
        let s = crate::SURROUND;
        let clear = [f64::from(s[0]), f64::from(s[1]), f64::from(s[2]), 1.0];
        let picture = match (scene.view.as_mut(), gpu) {
            (Some(view), Some(gpu)) => view.picture(gpu, side, side, clear).ok().map(|px| (side, side, px)),
            _ => None,
        };
        Ok(Some(ModelSnapshot {
            saved: SavedModel {
                texture_size: scene.texture_size,
                current: scene.current,
                sets: docs,
                orbit,
                extension: scene.source.extension.clone(),
                paint_through: scene.paint_through,
                environment: scene.environment_name.clone(),
                exposure: (scene.exposure != 1.0).then_some(scene.exposure),
                environment_shown: (!scene.environment_shown).then_some(false),
                lights: Some(scene.lights.clone()),
                hidden: places(&scene.look.hidden),
                locked: places(&scene.look.locked),
                sets_hidden: places(&scene.look.sets_hidden),
                sets_locked: places(&scene.look.sets_locked),
                active: scene.look.active.clone(),
                channels,
            },
            source: scene.source.clone(),
            layers,
            channel_layers,
            maps: scene
                .surface_maps
                .iter()
                .enumerate()
                .flat_map(|(part, maps)| {
                    maps.iter().zip(MAP_FILES).filter_map(move |(m, name)| m.as_ref().map(|m| (part, name, m.clone())))
                })
                .collect(),
            picture,
        }))
    }
}

/// Writes a model's files into its artwork folder `dir`: the model (once), and the waiting texture
/// sets' layers that changed since the last save there.
pub(crate) fn write_model(saved: &mut SavedSets, dir: &Path, model: &ModelSnapshot) -> Result<(), MobileError> {
    let file = dir.join(format!("model.{}", model.source.extension));
    if !file.exists() {
        write_atomically(&file, |p| fs::write(p, &model.source.bytes).map_err(|e| file_error("saving the model", e)))?;
    }
    let mtl = dir.join("model.mtl");
    if let Some(text) = model.source.mtl.as_ref()
        && !mtl.exists()
    {
        write_atomically(&mtl, |p| fs::write(p, text).map_err(|e| file_error("saving the model", e)))?;
    }
    let side = model.saved.texture_size;
    // the maps, written once: they don't change
    for (part, name, rgba) in &model.maps {
        let path = dir.join("maps").join(format!("{part}-{name}.png"));
        if !path.exists() {
            fs::create_dir_all(dir.join("maps")).map_err(|e| file_error("making the folder", e))?;
            write_atomically(&path, |p| write_png(p, side, side, rgba, &[]))?;
        }
    }
    let colour = model.layers.iter().map(|(part, pixels)| (*part, 0, pixels));
    let channels = model.channel_layers.iter().map(|(part, k, pixels)| (*part, *k, pixels));
    for (part, channel, pixels) in colour.chain(channels) {
        let mut folder = dir.join("sets").join(part.to_string());
        if channel > 0 {
            folder = folder.join(format!("c{channel}"));
        }
        fs::create_dir_all(&folder).map_err(|e| file_error("making the folder", e))?;
        for (id, rgba) in pixels {
            let hash = pixels_hash(rgba);
            let path = folder.join(format!("{}.png", id.0));
            if saved.get(&(part, channel, *id)) == Some(&hash) && path.exists() {
                continue;
            }
            write_atomically(&path, |p| write_png(p, side, side, rgba, &[]))?;
            saved.insert((part, channel, *id), hash);
        }
        // layer files the set no longer has
        if let Ok(entries) = fs::read_dir(&folder) {
            for entry in entries.flatten() {
                let path = entry.path();
                if !pixels.iter().any(|(id, _)| path == folder.join(format!("{}.png", id.0))) {
                    let _ = fs::remove_file(path);
                }
            }
        }
    }
    saved.retain(|(part, channel, id), _| {
        let has = |px: &Pixels| px.iter().any(|(i, _)| i == id);
        if *channel == 0 {
            model.layers.iter().any(|(p, px)| p == part && has(px))
        } else {
            model.channel_layers.iter().any(|(p, k, px)| p == part && k == channel && has(px))
        }
    });
    Ok(())
}

/// The names of a part's normal and occlusion maps' files in the artwork's `maps` folder
/// (`<part>-<name>.png`).
const MAP_FILES: [&str; 2] = ["normal", "occlusion"];

/// The layers of `doc` saved in `folder` (each `<id>.png`, `side` square); a layer with no file is
/// empty.
fn read_set_layers(folder: &Path, doc: &Document, side: u32) -> Pixels {
    let mut pending = Vec::new();
    for layer in doc.layers() {
        let path = folder.join(format!("{}.png", layer.id.0));
        if !path.exists() {
            continue;
        }
        match read_png(&path, side, side) {
            Ok(rgba) => pending.push((layer.id, rgba)),
            Err(e) => log::error!("{} layer {:?}: {e}", folder.display(), layer.id),
        }
    }
    pending
}

/// The model saved in the artwork folder `dir`, as `saved` describes it.
pub(crate) fn open_saved_model(dir: &Path, saved: &SavedModel) -> Result<Scene, MobileError> {
    let extension = extension_of(&format!("model.{}", saved.extension));
    let bytes = fs::read(dir.join(format!("model.{extension}"))).map_err(|e| file_error("opening the model", e))?;
    let mtl = fs::read_to_string(dir.join("model.mtl")).ok();
    let mesh = read_model(&bytes, &extension, mtl.as_deref())
        .map_err(|e| file_error("opening the model", mesh_error_text(&e)))?;
    let side = saved.texture_size.clamp(64, MOST_TEXTURE_SIZE);
    let waiting = saved
        .sets
        .iter()
        .enumerate()
        .map(|(part, doc)| {
            let doc = doc.as_ref()?.clone().repaired();
            if (doc.width(), doc.height()) != (side, side) {
                return None;
            }
            let pending = read_set_layers(&dir.join("sets").join(part.to_string()), &doc, side);
            Some(TextureSet { doc, canvas: None, pending, fill: None })
        })
        .collect();
    let channels = saved
        .channels
        .iter()
        .enumerate()
        .map(|(part, pair)| {
            let mut out: [Option<TextureSet>; 2] = [None, None];
            for (k, doc) in pair.iter().enumerate() {
                let Some(doc) =
                    doc.as_ref().map(|d| d.clone().repaired()).filter(|d| (d.width(), d.height()) == (side, side))
                else {
                    continue;
                };
                let folder = dir.join("sets").join(part.to_string()).join(format!("c{}", k + 1));
                let pending = read_set_layers(&folder, &doc, side);
                if let Some(slot) = out.get_mut(k) {
                    *slot = Some(TextureSet { doc, canvas: None, pending, fill: None });
                }
            }
            out
        })
        .collect();
    let mut scene = Scene::new(mesh, ModelSource { bytes, extension, mtl }, side, saved.current, waiting);
    scene.paint_through = saved.paint_through;
    scene.environment_name = saved.environment.clone();
    scene.exposure = saved.exposure.filter(|e| e.is_finite()).map_or(1.0, |e| e.clamp(0.0, MOST_EXPOSURE));
    scene.environment_shown = saved.environment_shown.unwrap_or(true);
    if let Some(lights) = &saved.lights {
        scene.lights = lights.iter().take(MOST_LIGHTS).map(|l| l.sane()).collect();
    }
    let (meshes, sets) = (scene.mesh.submeshes.len(), scene.mesh.parts.len());
    let flags = |places: &[usize], count: usize| (0..count).map(|i| places.contains(&i)).collect();
    let in_set = |i: &usize| scene.set_of(*i) == Some(saved.current.min(sets.saturating_sub(1)));
    scene.look = MeshLook {
        hidden: flags(&saved.hidden, meshes),
        locked: flags(&saved.locked, meshes),
        sets_hidden: flags(&saved.sets_hidden, sets),
        sets_locked: flags(&saved.sets_locked, sets),
        active: saved.active.iter().copied().filter(in_set).collect(),
    };
    scene.channels = channels;
    scene.surface_maps = (0..scene.mesh.parts.len())
        .map(|part| {
            MAP_FILES.map(|name| {
                let path = dir.join("maps").join(format!("{part}-{name}.png"));
                path.exists().then(|| read_png(&path, side, side).ok()).flatten().map(Arc::new)
            })
        })
        .collect();
    scene.orbit = saved.orbit.map(|o| Orbit {
        target: o.target,
        rotation: o.rotation.unwrap_or_else(|| Orbit::rotation_of(o.yaw, o.pitch)),
        distance: o.distance,
        fov: o.fov_y,
    });
    Ok(scene)
}

impl Scene {
    /// What part `part`'s `channel` (1 roughness, 2 metalness) starts as, where the model's
    /// material brings no image for it: the material's value, else the plain one.
    fn channel_value(&self, part: usize, channel: usize) -> f32 {
        let (_, roughness, metallic) = self.mesh.surface(part);
        if channel == 1 { roughness } else { metallic }
    }

    /// Whether the model brings an image for each part's colour: a USDZ's packed one, or one
    /// picked or zipped with it, that its material maps.
    pub(super) fn brings_colour(&self) -> Vec<bool> {
        let zip = (self.source.extension == "usdz")
            .then(|| procraft_engine::zip::ZipReader::new(&self.source.bytes))
            .flatten();
        let found = |path: &str| match &zip {
            Some(zip) => packed_name(zip, path).is_some(),
            None => companion(&self.picked, path).is_some(),
        };
        let material = |p: &procraft_engine::mesh::Part| self.mesh.materials.iter().find(|m| m.name == p.material);
        self.mesh.parts.iter().map(|p| material(p).and_then(|m| m.color_map.as_deref()).is_some_and(found)).collect()
    }

    /// Where part `part`'s `channel` document waits (0 colour, 1 roughness, 2 metalness).
    pub(super) fn slot(&mut self, part: usize, channel: usize) -> Option<&mut Option<TextureSet>> {
        if channel == 0 {
            return self.sets.get_mut(part);
        }
        let count = self.mesh.parts.len();
        if self.channels.len() < count {
            self.channels.resize_with(count, || [None, None]);
        }
        self.channels.get_mut(part).and_then(|c| c.get_mut(channel - 1))
    }
}

/// `set`'s document made to have `structure`'s layers (their ids, order and settings, with its
/// own pixels): layers new to it start empty, and its undo history goes if the layers changed.
pub(super) fn follow_layers(set: &mut TextureSet, structure: &Document) {
    let ids = |d: &Document| d.layers().iter().map(|l| (l.id, l.kind, l.parent)).collect::<Vec<_>>();
    let changed = ids(&set.doc) != ids(structure);
    let had: Vec<LayerId> = set.doc.layers().iter().map(|l| l.id).collect();
    let side = set.doc.width();
    let mut doc = Document::from_layers(side, side, structure.layers().to_vec(), structure.current());
    doc.background_visible = false;
    // no id handed out in any channel handed out again: a layer new here is clear, or holds what
    // this channel's own steps left on it (undone away and back on the other channel)
    doc.keep_ids_from(structure);
    doc.keep_ids_from(&set.doc);
    set.doc = doc;
    if !changed {
        return;
    }
    set.pending.retain(|(id, _)| had.contains(id) && structure.layer(*id).is_some());
    if let Some(canvas) = set.canvas.as_mut() {
        // its strokes' steps stay, to be undone with the colour's
        canvas.forget_document_steps();
        // composited again with the layers it has now
        canvas.invalidate();
    }
}

impl State {
    /// Paints on `channel` of the current part from now on: its document and canvas become the
    /// session's, with the layers of the document painted on until now.
    pub(crate) fn choose_channel(&mut self, channel: usize) {
        if channel > 2 || self.scene.as_ref().is_none_or(|s| s.channel == channel) {
            return;
        }
        if self.scene_shown() {
            self.scene_stroke_cancel();
        } else {
            self.finish_stroke();
        }
        self.settle();
        let side = self.doc.width();
        let Some(scene) = self.scene.as_mut() else { return };
        let (part, from) = (scene.current, scene.channel);
        let mut old = TextureSet {
            doc: std::mem::replace(&mut self.doc, Document::new(1, 1)),
            canvas: self.canvas.take(),
            pending: crate::project::opened_pixels(std::mem::take(&mut self.pending_pixels), side, side).0,
            fill: None,
        };
        if let Some(gpu) = self.gpu.as_ref() {
            to_waiting(gpu, &mut old);
        }
        let structure = old.doc.clone();
        if let Some(slot) = scene.slot(part, from) {
            *slot = Some(old);
        }
        let value = scene.channel_value(part, channel);
        let mut new = scene
            .slot(part, channel)
            .and_then(Option::take)
            .unwrap_or_else(|| TextureSet::filled(side, &structure, opaque([value; 3])));
        follow_layers(&mut new, &structure);
        self.pending_pixels = crate::project::opened(new.take_pending());
        self.doc = new.doc;
        self.canvas = new.canvas;
        if let (Some(gpu), Some(canvas)) = (self.gpu.as_ref(), self.canvas.as_mut()) {
            to_session(gpu, canvas);
        }
        scene.channel = channel;
        self.ensure_canvas();
    }

    /// Chooses a tile (`Some` channel) under the current layer, or the layer's own row, as an
    /// undo step: one left in the canvas of the channel painted until now and one in the
    /// chosen channel's (one step when the channel stays the same).
    pub(crate) fn choose_tile(&mut self, tile: Option<usize>) {
        let Some(scene) = self.scene.as_ref() else { return };
        let to = match tile {
            Some(c) if c <= 2 => (c, true),
            Some(_) => return,
            None => (0, false),
        };
        let (from, part) = ((scene.channel, scene.tile_chosen), scene.current);
        if from == to {
            return;
        }
        let name = if to.1 { TILE_CHOSEN } else { ROW_CHOSEN };
        let mut steps = [None; 3];
        if from.0 == to.0 {
            if self.scene_shown() {
                self.scene_stroke_cancel();
            } else {
                self.finish_stroke();
            }
            self.settle();
        } else {
            self.choose_channel(to.0);
            // the step in the channel left, waiting now
            let set = self.scene.as_mut().and_then(|s| s.slot(part, from.0)).and_then(Option::as_mut);
            if let (Some(gpu), Some(set)) = (self.gpu.as_ref(), set)
                && let Some(c) = set.canvas.as_mut()
            {
                c.record_outside_change(name);
                c.render(gpu, &mut set.doc, None);
                if let Some(s) = steps.get_mut(from.0) {
                    *s = c.undo_step();
                }
            }
        }
        if let Some(c) = self.canvas.as_mut() {
            c.record_outside_change(name);
            self.flush();
            if let Some(s) = steps.get_mut(to.0) {
                *s = self.canvas.as_ref().and_then(Canvas::undo_step);
            }
        }
        let Some(scene) = self.scene.as_mut() else { return };
        scene.tile_chosen = to.1;
        if steps.iter().any(Option::is_some) {
            if scene.tile_steps.len() >= MOST_JOINT_STEPS {
                scene.tile_steps.remove(0);
            }
            scene.tile_steps.push(TileStep { part, from, to, steps });
        }
        self.revision += 1;
    }

    /// After an undo or redo (`before`: the session canvas's next undo and redo steps before
    /// it), a tile's choice undone or redone chooses again what was chosen before or after it:
    /// its channel painted, and its step in that channel's canvas taken with it.
    pub(crate) fn follow_tile_step(&mut self, before: (Option<u64>, Option<u64>)) {
        let Some((step, undone)) = self.canvas.as_ref().and_then(|c| stepped(before, c)) else { return };
        let Some(scene) = self.scene.as_ref() else { return };
        let (part, channel) = (scene.current, scene.channel);
        let found = scene.tile_steps.iter().rev().find(|t| t.part == part && t.steps.get(channel) == Some(&Some(step)));
        let Some(&TileStep { from, to, steps, .. }) = found else { return };
        let chosen = if undone { from } else { to };
        let follow = |c: &mut Canvas, want: Option<u64>| {
            let next = if undone { c.undo_step() } else { c.redo_step() };
            if want.is_some() && next == want {
                if undone {
                    c.undo();
                } else {
                    c.redo();
                }
            }
        };
        // the other channel it was made between: chosen again, or its step taken where it waits
        if let Some(other) = [from.0, to.0].into_iter().find(|&c| c != channel) {
            let want = steps.get(other).copied().flatten();
            if chosen.0 == other {
                self.choose_channel(other);
                if let Some(c) = self.canvas.as_mut() {
                    follow(c, want);
                    self.flush();
                }
            } else {
                let set = self.scene.as_mut().and_then(|s| s.slot(part, other)).and_then(Option::as_mut);
                if let (Some(gpu), Some(set)) = (self.gpu.as_ref(), set)
                    && let Some(c) = set.canvas.as_mut()
                {
                    follow(c, want);
                    c.render(gpu, &mut set.doc, None);
                }
            }
        }
        if let Some(scene) = self.scene.as_mut() {
            scene.tile_chosen = chosen.1;
        }
    }

    /// After an undo or redo (`before`: the session canvas's next undo and redo steps before it),
    /// a hide, lock or pick undone or redone puts the model's look back.
    pub(crate) fn follow_look_step(&mut self, before: (Option<u64>, Option<u64>)) {
        let Some((step, undone)) = self.canvas.as_ref().and_then(|c| stepped(before, c)) else { return };
        let Some(scene) = self.scene.as_mut() else { return };
        let (part, channel) = (scene.current, scene.channel);
        let found = scene.look_steps.iter().rev().find(|t| t.part == part && t.channel == channel && t.step == step);
        if let Some(found) = found {
            scene.look = if undone { found.before.clone() } else { found.after.clone() };
        }
    }

    /// The eyedropper on a shown model: the window as it shows round (`x`, `y`), and the colour
    /// painted there, in the channel being painted, on whichever texture set is under the point.
    pub(crate) fn scene_pick(&mut self, x: f32, y: f32, side: u32) -> Option<crate::pick::Pick> {
        let size = self.window_size;
        let s = crate::SURROUND;
        let clear = [f64::from(s[0]), f64::from(s[1]), f64::from(s[2]), 1.0];
        let revision = self.revision;
        let gpu = self.gpu.as_ref()?;
        let scene = self.scene.as_mut()?;
        let orbit = scene.orbit()?;
        let hit = scene.surface_at(x, y, size);
        // the window drawn once for all the moves of one pick
        let stale = scene.seen.as_ref().is_none_or(|f| f.size != size || f.orbit != orbit || f.revision != revision);
        if stale {
            let view = scene.view.as_mut()?;
            let pixels = view.picture(gpu, size.0, size.1, clear).ok()?;
            scene.seen = Some(SeenFrame { size, orbit, revision, pixels });
        }
        let pixels = scene.seen.as_ref().map(|f| window_round(&f.pixels, size, (x, y), side))?;
        let channel = scene.channel;
        let current = scene.current;
        let rgb = hit.and_then(|hit| {
            let (tx, ty) = texel_of(hit.uv, scene.texture_size);
            let canvas = if hit.part == current {
                self.canvas.as_mut()
            } else {
                scene.slot(hit.part, channel)?.as_mut().and_then(|s| s.canvas.as_mut())
            }?;
            let [r, g, b, _] = canvas.read_composite_pixel(gpu, tx, ty)?;
            Some(u32::from(r) << 16 | u32::from(g) << 8 | u32::from(b))
        });
        Some(crate::pick::Pick { rgb, side, pixels })
    }

    /// A tool that works on the texture, not through the camera (a transform, Liquify, Clone), is
    /// opening: a shown model gives way to its texture, flat, until the tool ends.
    pub(crate) fn scene_flat_for_tool(&mut self) {
        if !self.scene_shown() {
            return;
        }
        self.scene_stroke_cancel();
        if let Some(scene) = self.scene.as_mut() {
            scene.flat = true;
            scene.flat_for_tool = true;
        }
        self.view = Some(self.fitted_view());
    }

    /// The tool that had the texture shown flat has ended: the model shows again.
    pub(crate) fn scene_back_from_tool(&mut self) {
        if let Some(scene) = self.scene.as_mut().filter(|s| s.flat_for_tool) {
            scene.flat = false;
            scene.flat_for_tool = false;
        }
    }

    /// Whether a picture is being placed on the model, and whether window point `at` is on it.
    pub(crate) fn scene_picture_under(&self, at: (f32, f32)) -> Option<bool> {
        let placing = self.scene.as_ref()?.placing.as_ref()?;
        Some(placing.place.holds([at.0, at.1]))
    }

    /// Carries the picture being placed onto the model, if it has moved or the model has.
    fn carry_picture(&mut self) {
        let size = self.window_size;
        let (Some(gpu), Some(scene), Some(canvas)) = (self.gpu.as_ref(), self.scene.as_mut(), self.canvas.as_mut())
        else {
            return;
        };
        scene.tell_view_what_takes_paint();
        let (part, through) = (scene.current, scene.paint_through);
        let (Some(placing), Some(view)) = (scene.placing.as_mut(), scene.view.as_mut()) else { return };
        let now = (placing.place, view.orbit);
        if placing.carried == Some(now) {
            return;
        }
        placing.carried = Some(now);
        view.paint_through = through;
        view.within = canvas.selection_view().cloned();
        let mut encoder =
            gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("picture on the model") });
        view.project_picture(
            gpu,
            &mut encoder,
            part,
            &placing.view,
            placing.place.to_picture(placing.size),
            size.0,
            size.1,
            canvas.stroke_target(),
        );
        gpu.queue.submit([encoder.finish()]);
        canvas.stroke_drawn(IRect::from_size(view.texture_size(), view.texture_size()));
    }

    /// The documents holding the artwork's layers, each once: the canvas's, or on a model its
    /// texture sets' colours in order, whichever is painted on.
    pub(crate) fn colour_docs(&self) -> Vec<&Document> {
        let Some(scene) = self.scene.as_ref() else { return vec![&self.doc] };
        let own = (scene.channel == 0).then_some(scene.current);
        let waiting = scene.sets.iter().map(|set| set.as_ref().map(|s| &s.doc));
        waiting.enumerate().filter_map(|(i, doc)| if Some(i) == own { Some(&self.doc) } else { doc }).collect()
    }

    /// The selection as fills take it: on a model, kept to the meshes picked.
    pub(crate) fn reach_selection(&mut self) -> Option<procraft_engine::selection::Mask> {
        let selection = self.selection.clone();
        let Some(reach) = self.scene.as_mut().and_then(Scene::reach) else { return selection };
        let Some(mut within) = selection else { return Some(reach) };
        for (c, r) in within.data.iter_mut().zip(&reach.data) {
            *c = ((u16::from(*c) * u16::from(*r) + 127) / 255) as u8;
        }
        Some(within)
    }

    /// An adjustment opens: on a model with meshes picked, it keeps to them, as it does to a
    /// selection, until [`State::scene_selection_back`].
    pub(crate) fn scene_selection_for_adjustment(&mut self) {
        if self.scene.as_mut().and_then(Scene::reach).is_none() {
            return;
        }
        let narrowed = self.reach_selection();
        let held = self.selection.clone();
        if let Some(scene) = self.scene.as_mut() {
            scene.held_selection = Some(held);
        }
        if let (Some(canvas), Some(gpu), Some(mask)) = (self.canvas.as_mut(), self.gpu.as_ref(), narrowed.as_ref()) {
            canvas.set_selection(gpu, Some(&mask.data));
        }
    }

    /// The adjustment has ended: the selection is what it was.
    pub(crate) fn scene_selection_back(&mut self) {
        let Some(held) = self.scene.as_mut().and_then(|s| s.held_selection.take()) else { return };
        self.flush();
        self.selection = held;
        if let (Some(canvas), Some(gpu)) = (self.canvas.as_mut(), self.gpu.as_ref()) {
            canvas.set_selection(gpu, self.selection.as_ref().map(|m| m.data.as_slice()));
        }
    }

    /// How the selection shows on the model from now on, if there is one.
    pub(crate) fn scene_selection_look(&mut self, look: Option<procraft_gpu::SelectionLook>) {
        let mask = self.canvas.as_ref().and_then(Canvas::selection_view).cloned();
        let Some(scene) = self.scene.as_mut() else { return };
        let part = scene.current;
        let Some(view) = scene.view.as_mut() else { return };
        view.selection = mask.zip(look).map(|(mask, look)| ShownSelection { mask, part, look });
    }

    /// The window polygon `window` carried onto the texture set painted on, as a selection: its
    /// surfaces under it nearest the camera, or with X-Ray all of them.
    pub(crate) fn scene_mask(&mut self, window: &[Vec2]) -> Option<procraft_engine::selection::Mask> {
        let size = self.window_size;
        let shape = procraft_engine::selection::Mask::polygon(size.0, size.1, window);
        let x_ray = self.x_ray;
        let gpu = self.gpu.as_ref()?;
        let scene = self.scene.as_mut()?;
        scene.tell_view_what_takes_paint();
        let (part, side) = (scene.current, scene.texture_size);
        let view = scene.view.as_mut()?;
        let through = std::mem::replace(&mut view.paint_through, x_ray);
        let data = view.project_coverage(gpu, part, &shape.data, size.0, size.1);
        view.paint_through = through;
        match data {
            Ok(data) => Some(procraft_engine::selection::Mask { width: side, height: side, data }),
            Err(e) => {
                log::error!("a selection on the model: {e}");
                None
            }
        }
    }

    /// Why a stroke on the model can't land, if it can't.
    pub(crate) fn scene_blocked(&self) -> Option<crate::layers::Blocked> {
        self.scene.as_ref().filter(|_| self.scene_shown()).and_then(Scene::why_blocked)
    }

    /// The texel of the texture set painted on under window point (`x`, `y`), on a shown model:
    /// none off the model, or on another texture set's surface.
    pub(crate) fn scene_texel(&self, x: f32, y: f32) -> Option<(u32, u32)> {
        let scene = self.scene.as_ref()?;
        let hit = scene.surface_at(x, y, self.window_size)?;
        (hit.part == scene.current).then(|| texel_of(hit.uv, scene.texture_size))
    }

    /// Runs `f` with the colour of the current part as the session's document (as saves need),
    /// the channel painted on put back after.
    pub(crate) fn with_colour<T>(&mut self, f: impl FnOnce(&mut State) -> T) -> T {
        let channel = self.scene.as_ref().map_or(0, |s| s.channel);
        self.choose_channel(0);
        let out = f(self);
        self.choose_channel(channel);
        out
    }

    /// Whether a model is shown (and strokes and gestures go to it), not a flat canvas: a model
    /// open and not showing its 2D texture.
    pub(crate) fn scene_shown(&self) -> bool {
        self.scene.as_ref().is_some_and(|s| !s.flat)
    }

    /// Makes texture set `index` the current one: the session's document and canvas.
    pub(crate) fn choose_texture_set(&mut self, index: usize) {
        if self.scene.as_ref().is_none_or(|s| index == s.current || index >= s.sets.len()) {
            return;
        }
        self.choose_channel(0);
        self.scene_stroke_cancel();
        self.settle();
        let Some(scene) = self.scene.as_mut() else { return };
        // opened on its colour, no tile chosen
        scene.tile_chosen = false;
        let Some(mut set) = scene.sets.get_mut(index).and_then(Option::take) else { return };
        let (w, h) = (self.doc.width(), self.doc.height());
        let pending = std::mem::replace(&mut self.pending_pixels, crate::project::opened(set.take_pending()));
        let mut old = TextureSet {
            doc: std::mem::replace(&mut self.doc, set.doc),
            canvas: std::mem::replace(&mut self.canvas, set.canvas),
            pending: crate::project::opened_pixels(pending, w, h).0,
            fill: None,
        };
        if let Some(gpu) = self.gpu.as_ref() {
            to_waiting(gpu, &mut old);
            if let Some(canvas) = self.canvas.as_mut() {
                to_session(gpu, canvas);
            }
        }
        if let Some(slot) = scene.sets.get_mut(scene.current) {
            *slot = Some(old);
        }
        scene.current = index;
        // meshes picked together share a texture set
        scene.look.active.clear();
        self.ensure_canvas();
    }

    /// A stroke begins on the model: built in window pixels, drawn on the screen's canvas and
    /// carried onto the current texture set's layer.
    pub(crate) fn scene_stroke_begin(&mut self, pointer: PointerKind, samples: &[PenSample]) {
        let current = self.doc.current();
        if self.doc.unseen(current).is_some()
            || self
                .doc
                .layer(current)
                .is_none_or(|l| self.doc.is_locked(l.id) || l.kind == LayerKind::Group || l.text.is_some())
        {
            return;
        }
        let tool = if pointer == PointerKind::StylusEraser { ToolKind::Erase } else { self.tool };
        if self.scene.as_ref().is_some_and(|s| s.why_blocked().is_some() || s.placing.is_some()) {
            return;
        }
        let (brush, mut style, diameter) = self.stroke_brush(tool, current);
        // the brush shows as big as on the texture fitted to the window
        let size = self.window_size;
        let zoom = View::fit((self.doc.width(), self.doc.height()), size).zoom();
        let diameter = diameter * zoom;
        self.strokes = self.strokes.wrapping_add(1);
        self.info.strokes = self.info.strokes.saturating_add(1);
        let seed = self.strokes.wrapping_mul(0x9e37_79b9);
        let builder =
            StrokeBuilder::with_seed(brush.clone(), diameter, pointer.into(), seed).with_input(self.input.clone());
        // (its samples in window pixels)
        let mut builder = self.pressing.applied(builder.scaled(zoom).seen_at(1.0), self.display_scale);
        // a brush engine's, drawn by it (on the screen's canvas, where it gets its tips)
        let engine = self.hooks.clone().filter(|_| brush.engine.is_some()).map(|hooks| {
            let starting = crate::plugin_brushes::Starting {
                colour: self.color,
                secondary: self.secondary,
                diameter,
                start_diameter: brush.diameter(crate::starting_sliders_of(&brush, tool).size),
                opacity: self.sliders(tool).opacity.clamp(0.0, 1.0),
                tool: match tool.stroke_tool() {
                    StrokeTool::Erase => 1,
                    StrokeTool::Smudge => 2,
                    _ => 0,
                },
                pointer: match pointer {
                    PointerKind::Finger => 0,
                    PointerKind::Mouse => 2,
                    _ => 1,
                },
                seed,
                under_at: None,
                turn: 0.0,
            };
            (hooks, starting)
        });
        let (Some(gpu), Some(scene)) = (self.gpu.as_ref(), self.scene.as_mut()) else { return };
        // the screen's canvas, the window's size
        let stale = scene.screen.as_ref().is_none_or(|(d, _)| (d.width(), d.height()) != size);
        if stale {
            let doc = Document::new(size.0.max(1), size.1.max(1));
            match Canvas::new(gpu, &doc) {
                Ok(c) => scene.screen = Some((doc, c)),
                Err(e) => return log::error!("3D stroke canvas: {e}"),
            }
        }
        // a grain fixed to the canvas is read along the model's three axes (moving grain stays in
        // the stamps drawn on the screen), and shows in the colour's coverage alone
        let grain = stroke_grain(gpu, scene, &brush, style.grain_scale);
        let mut canvas_style = style;
        if grain.is_some() {
            canvas_style.grain = Some(0);
            canvas_style.grain_mode = procraft_engine::brush::GrainMode::Texturized;
        } else if canvas_style.grain_mode == procraft_engine::brush::GrainMode::Texturized {
            canvas_style.grain = None;
        }
        // a stroke changing what's under it works on the layer as the camera sees it, there
        let laid = style.in_place();
        scene.laying = None;
        if laid {
            let Some(canvas) = self.canvas.as_mut() else { return };
            canvas.render(gpu, &mut self.doc, None);
            let Some(layer) = canvas.layer_view(current) else { return };
            let Some(seen) = scene.see_layer(gpu, layer, size) else { return };
            scene.laying = Some(seen);
            canvas_style = StrokeStyle::new(StrokeTool::Laid, [0.0; 3], 1.0);
        }
        let Some((screen_doc, screen)) = scene.screen.as_mut() else { return };
        // the screen's canvas holds its own copies of the brush's shape and grain
        screen.use_sources(gpu, &mut style, &brush);
        if let Some((hooks, starting)) = engine
            && let Some(o) =
                crate::plugin_brushes::engine_stroke(&hooks, &brush, style.tool, starting, (false, false), screen, gpu)
        {
            style = o.style;
            builder = builder.with_engine(Box::new(o.first));
        }
        let stamps = builder.add(&window_samples(samples));
        screen.begin_stroke(screen_doc.current(), style);
        screen.add_stamps(&stamps);
        scene.bounds = bounds_of(&stamps);
        scene.stroking = true;
        scene.ending = false;
        if let Some(canvas) = self.canvas.as_mut() {
            canvas.begin_drawn_stroke(current, canvas_style);
            if let Some(grain) = grain.as_ref().filter(|_| !laid) {
                canvas.set_stroke_grain(&gpu.device, grain);
            }
        }
        self.stroke = Some(builder);
        if !laid {
            self.begin_material_strokes(&brush, canvas_style, current);
        }
    }

    /// A stroke on a part's colour paints its roughness and metalness too, on the same layer and
    /// with the same coverage: the brush's amount of each through its source (an eraser takes
    /// all three away). A stroke on one of those channels paints only it.
    fn begin_material_strokes(&mut self, brush: &procraft_engine::brush::Brush, style: StrokeStyle, layer: LayerId) {
        let structure = self.doc.clone();
        let (Some(gpu), Some(scene)) = (self.gpu.as_ref(), self.scene.as_mut()) else { return };
        scene.material_paint = None;
        if scene.channel != 0 {
            return;
        }
        let (part, side) = (scene.current, scene.texture_size);
        let channels = [
            (brush.setting("roughness_amount"), brush.setting("roughness_scale"), &brush.roughness),
            (brush.setting("metallic_amount"), brush.setting("metallic_scale"), &brush.metallic),
        ];
        let follows_camera = brush.setting("grain_follows_camera") >= 0.5;
        let mut paint = [MaterialPaint { amount: 0.0, tile: 1.0, source: None, follows_camera }; 2];
        for (k, ((amount, scale, source), out)) in channels.into_iter().zip(&mut paint).enumerate() {
            let value = scene.channel_value(part, k + 1);
            let amount = amount.clamp(0.0, 1.0);
            let key = crate::studio::source_key(source);
            *out = MaterialPaint { amount, tile: (scale * MAX_GRAIN_SCALE).max(4.0), source: key, follows_camera };
            // a channel never painted is the material's value all over (its Base Layer filled with
            // it): a plain stroke of that very value onto that layer changes nothing, and the
            // channel waits to be made until one does
            let made = scene.slot(part, k + 1).is_some_and(|slot| slot.is_some());
            let into_base = layer == base_layer() && structure.layer(layer).is_some();
            if !made && into_base && key.is_none() && style.tool == StrokeTool::Paint && same_texel(amount, value) {
                continue;
            }
            // the channel's document, made now if it never was, with the colour's layers
            let stroke = scene.stroke(gpu, true);
            let Some(slot) = scene.slot(part, k + 1) else { return };
            let set = slot.get_or_insert_with(|| TextureSet::filled(side, &structure, opaque([value; 3])));
            follow_layers(set, &structure);
            if set_canvas(gpu, set, &stroke).is_err() {
                return;
            }
            let Some(canvas) = set.canvas.as_mut() else { return };
            // the colour's coverage, laid down plainly: no blend mode, grain or wet paint of its own
            let plain =
                StrokeStyle { color: [amount; 3], blend: 0, burnt_edges: 0.0, grain: None, wet_pull: None, ..style };
            canvas.begin_drawn_stroke(layer, plain);
            let cached = scene.material_sources.get(k).and_then(|c| c.as_ref()).map(|(key, _)| *key);
            if key.is_some() && cached != key {
                let made = crate::brush_files::material_pixels(source).and_then(|pixels| {
                    procraft_gpu::material_source(gpu, procraft_engine::textures::TEXTURE_SIZE as u32, &pixels)
                });
                if let Some(slot) = scene.material_sources.get_mut(k) {
                    *slot = key.zip(made);
                }
            }
        }
        scene.material_paint = Some(paint);
    }

    pub(crate) fn scene_stroke_add(&mut self, samples: &[PenSample], last: bool) {
        let Some(builder) = self.stroke.as_mut() else { return };
        let stamps = builder.add(&window_samples(samples));
        let drawn = builder.stamp_count();
        let all = if last { self.stroke.take().map(|mut b| b.finish()) } else { None };
        let Some(scene) = self.scene.as_mut() else { return };
        scene.bounds = scene.bounds.union(bounds_of(&stamps));
        if let Some((_, screen)) = scene.screen.as_mut() {
            screen.add_stamps(&stamps);
            if let Some(all) = all {
                scene.bounds = scene.bounds.union(bounds_of(&all));
                // a smudge has already changed what's under it: only the rest of it goes on
                if screen.stroke_tool() == Some(StrokeTool::Smudge) {
                    screen.add_stamps(all.get(drawn..).unwrap_or(&[]));
                } else {
                    screen.replace_stamps(&all);
                }
            }
        }
        if last {
            // carried on at once and kept, so undo sees it as soon as the pen lifts
            scene.ending = true;
            self.carry_stroke();
        }
    }

    pub(crate) fn scene_stroke_cancel(&mut self) {
        self.stroke = None;
        let Some(scene) = self.scene.as_mut() else { return };
        scene.stroking = false;
        scene.ending = false;
        scene.laying = None;
        if let Some((_, screen)) = scene.screen.as_mut() {
            screen.cancel_stroke();
        }
        if scene.material_paint.take().is_some()
            && let Some(channels) = scene.channels.get_mut(scene.current)
        {
            for canvas in channels.iter_mut().flatten().filter_map(|s| s.canvas.as_mut()) {
                canvas.cancel_stroke();
            }
        }
        if let Some(canvas) = self.canvas.as_mut() {
            canvas.cancel_stroke();
        }
    }

    /// One finger dragged by (`dx`, `dy`) window pixels: the model turns under it.
    pub(crate) fn scene_turn(&mut self, dx: f32, dy: f32) {
        let side = self.window_size.0.min(self.window_size.1).max(1) as f32;
        let Some(view) = self.scene.as_mut().and_then(|s| s.view.as_mut()) else { return };
        let o = &mut view.orbit;
        let length = (dx * dx + dy * dy).sqrt();
        o.turn(dx, dy, length / side * TURN_PER_SIDE);
    }

    /// Two fingers: pinching brings the model nearer, moving them moves it across the screen.
    pub(crate) fn scene_gesture(&mut self, pan: Vec2, scale: f32) {
        let side = self.window_size.0.min(self.window_size.1).max(1) as f32;
        let Some(scene) = self.scene.as_mut() else { return };
        let unit = Orbit::unit(&scene.mesh);
        let Some(view) = scene.view.as_mut() else { return };
        let o = &mut view.orbit;
        if scale.is_finite() && scale > 0.0 {
            o.distance = (o.distance / scale).clamp(NEAREST * unit, FARTHEST * unit);
        }
        // the target moves in the screen's plane, as far as the fingers did at its distance
        let per_pixel = 2.0 * o.distance * (o.fov / 2.0).tan() / side;
        let (right, up) = (o.right(), o.up());
        for k in 0..3 {
            if let (Some(t), Some(r), Some(u)) = (o.target.get_mut(k), right.get(k), up.get(k)) {
                *t += (-pan.x * r + pan.y * u) * per_pixel;
            }
        }
    }

    /// Carries the stroke drawn on the screen so far onto the current texture set; once the pen
    /// has lifted, the stroke ends there. Not before the model has been drawn once.
    fn carry_stroke(&mut self) {
        let size = self.window_size;
        let (Some(gpu), Some(scene)) = (self.gpu.as_ref(), self.scene.as_mut()) else { return };
        if !scene.stroking {
            return;
        }
        scene.tell_view_what_takes_paint();
        let (Some((screen_doc, screen)), Some(view), Some(canvas)) =
            (scene.screen.as_mut(), scene.view.as_mut(), self.canvas.as_mut())
        else {
            return;
        };
        screen.render(gpu, screen_doc, None);
        view.paint_through = scene.paint_through;
        view.within = canvas.selection_view().cloned();
        let mut encoder =
            gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("3D stroke") });
        let seen = screen.layer_view(screen_doc.current());
        let reach = match (scene.laying.as_ref(), seen) {
            (Some(was), Some(now)) => view.project_laid(
                gpu,
                &mut encoder,
                scene.current,
                now,
                was,
                scene.bounds,
                size.0,
                size.1,
                canvas.stroke_target(),
            ),
            _ => view.project_stroke(
                gpu,
                &mut encoder,
                scene.current,
                screen.stroke_view(),
                scene.bounds,
                size.0,
                size.1,
                canvas.stroke_target(),
            ),
        };
        gpu.queue.submit([encoder.finish()]);
        canvas.stroke_drawn(reach);
        // as the pen lifts, where the stroke really reached in the texture (the colour, roughness
        // and metalness the same): each canvas's undo step keeps only that, not all the texels
        // whose triangles lie under it on the screen (every one of them, where faces share a
        // texture)
        let within = if scene.ending { view.footprint(gpu, canvas.stroke_view(), reach) } else { None };
        // each canvas's step made as the pen lifts, to be undone and redone together
        let made = |c: &mut Canvas, doc: &mut Document| {
            let before = c.steps_made();
            match &within {
                Some(rects) => c.end_stroke_within(rects.clone()),
                None => c.end_stroke(),
            }
            c.render(gpu, doc, None);
            (c.steps_made() > before).then(|| c.undo_step()).flatten()
        };
        // the roughness and metalness take the colour's coverage as it is now, one after the
        // other through the stroke texture they share: each drawn there, then shown (or kept)
        let part = scene.current;
        let mut steps = [None, None, None];
        if let Some((paint, channels)) = scene.material_paint.zip(scene.channels.get_mut(part)) {
            for (k, ((look, set), step)) in
                paint.iter().zip(channels.iter_mut()).zip(steps.iter_mut().skip(1)).enumerate()
            {
                let Some(set) = set.as_mut() else { continue };
                let Some(target) = set.canvas.as_mut() else { continue };
                let source = scene
                    .material_sources
                    .get(k)
                    .and_then(Option::as_ref)
                    .filter(|(key, _)| Some(*key) == look.source)
                    .map(|(_, view)| view);
                let look = procraft_gpu::MaterialLook {
                    amount: look.amount,
                    source,
                    tile: look.tile,
                    follows_camera: look.follows_camera,
                };
                let mut encoder =
                    gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("3D material") });
                view.project_material(gpu, &mut encoder, part, k, canvas.stroke_view(), &look, target.stroke_target());
                gpu.queue.submit([encoder.finish()]);
                target.stroke_drawn(reach);
                if scene.ending {
                    *step = made(target, &mut set.doc);
                } else {
                    target.render(gpu, &mut set.doc, None);
                }
            }
        }
        if scene.ending {
            // the colour last: its stroke is what the others were drawn from
            steps[0] = made(canvas, &mut self.doc);
            if scene.material_paint.take().is_some() && steps.iter().flatten().count() > 1 {
                if scene.joint_steps.len() >= MOST_JOINT_STEPS {
                    scene.joint_steps.remove(0);
                }
                scene.joint_steps.push(JointStep { part, steps });
            }
            screen.cancel_stroke();
            screen.render(gpu, screen_doc, None);
            scene.stroking = false;
            scene.ending = false;
            scene.laying = None;
        }
    }

    /// After an undo or redo (`before`: the session canvas's next undo and redo steps before
    /// it), the rest of a stroke that painted every channel follows: the step it made in each
    /// other channel is undone or redone too, if that is that channel's next step.
    pub(crate) fn follow_joint_step(&mut self, before: (Option<u64>, Option<u64>)) {
        let Some((step, undone)) = self.canvas.as_ref().and_then(|c| stepped(before, c)) else { return };
        let (Some(gpu), Some(scene)) = (self.gpu.as_ref(), self.scene.as_mut()) else { return };
        let (part, channel) = (scene.current, scene.channel);
        let Some(joint) =
            scene.joint_steps.iter().find(|j| j.part == part && j.steps.get(channel) == Some(&Some(step))).copied()
        else {
            return;
        };
        for (k, want) in joint.steps.iter().enumerate() {
            let Some(want) = *want else { continue };
            if k == channel {
                continue;
            }
            let Some(set) = scene.slot(part, k).and_then(Option::as_mut) else { continue };
            let Some(c) = set.canvas.as_mut() else { continue };
            if undone && c.undo_step() == Some(want) {
                c.undo();
            } else if !undone && c.redo_step() == Some(want) {
                c.redo();
            } else {
                continue;
            }
            c.render(gpu, &mut set.doc, None);
        }
    }

    /// Draws a frame of the model into `target` (the window's size): the stroke carried onto it,
    /// the current texture set composited, the model drawn with each set showing.
    #[cfg_attr(not(any(target_os = "android", test)), allow(dead_code))]
    pub(crate) fn render_scene(
        &mut self,
        target: &wgpu::TextureView,
        format: wgpu::TextureFormat,
        size: (u32, u32),
        convert: Option<[[f32; 3]; 3]>,
    ) {
        self.scene_frame(Some(target), format, size, convert);
    }

    /// Brings the model up to date with its textures and draws it into `target` if there is one.
    pub(super) fn scene_frame(
        &mut self,
        target: Option<&wgpu::TextureView>,
        format: wgpu::TextureFormat,
        size: (u32, u32),
        convert: Option<[[f32; 3]; 3]>,
    ) {
        {
            let (Some(gpu), Some(scene)) = (self.gpu.as_ref(), self.scene.as_mut()) else { return };
            if scene.view.is_none() {
                let mut view = MeshView::new(gpu, &scene.mesh, scene.texture_size, format);
                if let Some(orbit) = scene.orbit.take() {
                    view.orbit = orbit;
                }
                // the surround behind the model until an environment comes
                view.show_environment(gpu, false);
                scene.view = Some(view);
                scene.surface_shown = false;
            }
            // the waiting texture sets' canvases (and channels), with the pixels they opened with,
            // drawing in the stroke textures of their kind
            let unmade = |s: &Option<TextureSet>| s.as_ref().is_some_and(|s| s.canvas.is_none());
            let colour = scene.sets.iter().any(unmade).then(|| scene.stroke(gpu, false));
            let material = scene.channels.iter().flatten().any(unmade).then(|| scene.stroke(gpu, true));
            let colours = scene.sets.iter_mut().flatten().map(|s| (s, &colour));
            let channels = scene.channels.iter_mut().flatten().flatten().map(|s| (s, &material));
            for (set, stroke) in colours.chain(channels) {
                if let (None, Some(stroke)) = (set.canvas.as_ref(), stroke) {
                    if let Err(e) = set_canvas(gpu, set, stroke) {
                        log::error!("a texture set's canvas: {e}");
                    }
                    if let TextureSet { canvas: Some(c), doc, .. } = set {
                        c.render(gpu, doc, None);
                    }
                }
            }
        }
        self.carry_stroke();
        self.carry_picture();
        let grid = self.surround_grid(Vec2::ZERO);
        let (Some(gpu), Some(scene)) = (self.gpu.as_ref(), self.scene.as_mut()) else { return };
        // a new environment: prefiltered now, shown behind the model unless that's turned off
        if let (Some(view), Some(panorama)) = (scene.view.as_mut(), scene.environment.take()) {
            view.set_lighting(gpu, Lighting::new(gpu, &panorama));
            scene.lit = true;
        }
        if let Some(view) = scene.view.as_mut() {
            view.show_environment(gpu, scene.lit && scene.environment_shown);
            view.set_exposure(scene.exposure);
        }
        if let Some(canvas) = self.canvas.as_mut() {
            canvas.render(gpu, &mut self.doc, None);
            // the session's canvas draws its strokes in the texture its kind shares, as the others
            let stroke = scene.stroke(gpu, scene.channel != 0);
            canvas.draw_strokes_in(gpu, &stroke);
        }
        scene.tell_view_what_shows();
        let Some(view) = scene.view.as_mut() else { return };
        view.set_convert(convert);
        view.set_grid(Some(grid));
        let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("3D view") });
        // each set's texture, its smaller levels made again when its picture has changed (the
        // current part's colour is the session's own unless a channel is painted on)
        scene.filtered.resize(scene.sets.len(), None);
        let painting_colour = scene.channel == 0;
        for (i, (set, filtered)) in scene.sets.iter().zip(scene.filtered.iter_mut()).enumerate() {
            let canvas = if i == scene.current && painting_colour {
                self.canvas.as_ref()
            } else {
                set.as_ref().and_then(|s| s.canvas.as_ref())
            };
            if let Some(c) = canvas
                && *filtered != Some((c.composites(), i == scene.current))
            {
                view.set_albedo_filtered(gpu, &mut encoder, i, c.composite_view());
                *filtered = Some((c.composites(), i == scene.current));
            }
        }
        // each set's roughness and metalness, as painted
        scene.channel_shown.resize(scene.mesh.parts.len(), [None, None]);
        for part in 0..scene.mesh.parts.len() {
            for k in 1..=2usize {
                let session_own = part == scene.current && scene.channel == k;
                let canvas = if session_own {
                    self.canvas.as_ref()
                } else {
                    scene
                        .channels
                        .get(part)
                        .and_then(|c| c.get(k - 1))
                        .and_then(|s| s.as_ref())
                        .and_then(|s| s.canvas.as_ref())
                };
                let key = canvas.map(|c| (c.composites(), session_own));
                let Some(shown) = scene.channel_shown.get_mut(part).and_then(|c| c.get_mut(k - 1)) else { continue };
                if *shown != key {
                    view.set_channel_view(gpu, part, k, canvas.map(Canvas::composite_view));
                    *shown = key;
                }
            }
        }
        // the model's own normal and occlusion maps, given again when they change
        if !scene.surface_shown {
            let side = scene.texture_size;
            for (part, maps) in scene.surface_maps.iter().enumerate() {
                for (map, kind) in
                    maps.iter().zip([procraft_gpu::SurfaceMap::Normal, procraft_gpu::SurfaceMap::Occlusion])
                {
                    view.set_surface_map(gpu, part, kind, map.as_deref().map(|m| (side, m.as_slice())));
                }
            }
            scene.surface_shown = true;
        }
        view.lights = scene.lights.iter().map(|l| l.to_gpu()).collect();
        let s = crate::SURROUND;
        let clear = [f64::from(s[0]), f64::from(s[1]), f64::from(s[2]), 1.0];
        let studio = scene.studio_open.then(|| *scene.studio.get_or_insert_with(|| view.studio_orbit()));
        let Some(target) = target else {
            gpu.queue.submit([encoder.finish()]);
            return;
        };
        match studio {
            Some(orbit) => view.render_studio(gpu, &mut encoder, target, size.0, size.1, clear, orbit),
            None => view.render(gpu, &mut encoder, target, size.0, size.1, clear),
        }
        gpu.queue.submit([encoder.finish()]);
        // frosted panels over the model: it drawn again, small, and blurred under each
        let glass = !self.backdrops.is_empty() || !self.backdrop_windows.is_empty();
        if let Some(canvas) = self.canvas.as_mut().filter(|_| glass) {
            let backdrop = procraft_gpu::Backdrop {
                regions: &self.backdrops,
                windows: &self.backdrop_windows,
                sigma: crate::BACKDROP_SIGMA_PT * self.display_scale,
            };
            canvas.frost_over(gpu, target, format, size, s, backdrop, &mut |encoder, small, (w, h)| match studio {
                Some(orbit) => view.render_studio(gpu, encoder, small, w, h, clear, orbit),
                None => view.render(gpu, encoder, small, w, h, clear),
            });
        }
    }
}

/// A stroke's grain fixed to the canvas read along the model's three axes into the current
/// part's texture space, one tile every `tile` canvas pixels, if the brush has one and the model
/// has been drawn: for [`Canvas::set_stroke_grain`].
fn stroke_grain(
    gpu: &Gpu,
    scene: &mut Scene,
    brush: &procraft_engine::brush::Brush,
    tile: f32,
) -> Option<wgpu::TextureView> {
    let g = &brush.grain;
    if g.mode != procraft_engine::brush::GrainMode::Texturized || g.depth <= 0.0 {
        return None;
    }
    let key = g.source.as_ref().map(|s| s.id).or_else(|| g.image.map(|i| (1 << 63) | u64::from(i)))?;
    if scene.grain_source.as_ref().map(|(k, _)| *k) != Some(key) {
        let pixels = crate::brush_files::grain_pixels(brush)?;
        let made = procraft_gpu::material_source(gpu, procraft_engine::textures::TEXTURE_SIZE as u32, &pixels);
        scene.grain_source = made.map(|v| (key, v));
    }
    let source = scene.grain_source.as_ref().map(|(_, v)| v)?;
    let look = procraft_gpu::GrainLook {
        source,
        tile: tile.max(1.0),
        follows_camera: brush.setting("grain_follows_camera") >= 0.5,
    };
    let view = scene.view.as_mut()?;
    let mut encoder = gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("3D grain") });
    let grain = view.project_grain(gpu, &mut encoder, scene.current, &look);
    gpu.queue.submit([encoder.finish()]);
    grain
}

/// Input as window pixels (3D strokes are built on the screen).
fn window_samples(samples: &[PenSample]) -> Vec<Sample> {
    samples
        .iter()
        .map(|s| Sample {
            pos: Vec2::new(s.x, s.y),
            pressure: s.pressure,
            tilt: s.tilt,
            azimuth: s.orientation,
            roll: s.roll,
            time_ms: s.time_ms,
        })
        .collect()
}

/// The pixels stamps can touch.
fn bounds_of(stamps: &[procraft_engine::stroke::Stamp]) -> IRect {
    stamps.iter().fold(IRect::EMPTY, |r, s| r.union(IRect::around(s.center, s.radius * std::f32::consts::SQRT_2)))
}
