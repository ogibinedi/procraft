//! 3D painting in the session: a model open in place of a flat canvas. Each texture set (the part
//! of the model one material covers) is a document of its own; the current one is the session's
//! own document and canvas, so layers, undo and adjustments work on it as on any canvas, and the
//! others wait here. Strokes are drawn on the screen and carried onto the model every frame.
//!
//! Saved, a model's artwork folder also holds its file as opened (`model.obj` with `model.mtl`,
//! `model.usdz` or `model.usdc`), and the
//! waiting texture sets' layers in `sets/<part>/<id>.png`; `artwork.json` holds their documents.

#[allow(unused_imports)]
use std::collections::HashMap;
#[allow(unused_imports)]
use std::fs;
#[allow(unused_imports)]
use std::path::Path;
#[allow(unused_imports)]
use std::sync::Arc;

#[allow(unused_imports)]
use procraft_engine::document::{Document, LayerId, LayerKind};
#[allow(unused_imports)]
use procraft_engine::geom::{IRect, Vec2};
#[allow(unused_imports)]
use procraft_engine::input::Sample;
#[allow(unused_imports)]
use procraft_engine::mesh::{MAX_TRIANGLES, Mesh, MeshError, PartImages, SurfaceHit, read_obj};
#[allow(unused_imports)]
use procraft_engine::stroke::StrokeBuilder;
#[allow(unused_imports)]
use procraft_engine::studio::MAX_GRAIN_SCALE;
#[allow(unused_imports)]
use procraft_engine::usd::{read_usd, read_usdc, read_usdz};
#[allow(unused_imports)]
use procraft_engine::view::View;
#[allow(unused_imports)]
use procraft_gpu::wgpu::util::DeviceExt as _;
#[allow(unused_imports)]
use procraft_gpu::{
    Canvas, Gpu, Light, Lighting, MOST_LIGHTS, MeshView, Orbit, SharedStroke, ShownSelection, StrokeStyle, StrokeTool,
    wgpu,
};

#[allow(unused_imports)]
use crate::project::{THUMBNAIL_SIDE, file_error, pixels_hash, read_png, write_atomically, write_png};
#[allow(unused_imports)]
use crate::{CanvasSession, MobileError, PenSample, PointerKind, State, ToolKind};

/// How far a one-finger drag turns the model: this many radians across the window's shorter
/// side, wherever the finger is and however fast it goes (measured: 0.0122 rad a point on a 744
/// point side).
const TURN_PER_SIDE: f32 = 9.08;
/// How far a drag across the Lighting Studio takes its camera round: radians across the shorter
/// side.
const STUDIO_TURN_PER_SIDE: f32 = 2.93;
/// The nearest and farthest the camera comes, in the framing's units (the model's largest side
/// counted as 4).
const NEAREST: f32 = 0.05;
const FARTHEST: f32 = 30.0;
/// The brightest the model's light can be taken.
const MOST_EXPOSURE: f32 = 5.0;
/// Where a light added in the Lighting Studio stands: just above and behind the model's middle
/// (framing units).
const NEW_LIGHT: [f32; 3] = [0.8, 2.6, -0.6];
/// How far a duplicated light stands from the one it copies (framing units).
const DUPLICATE_OFFSET: [f32; 3] = [1.0, 0.0, 0.0];
/// How far round a drag across the studio carries its camera (as the model is turned), and the
/// lowest and highest it looks from (radians up from level).
const STUDIO_LOWEST: f32 = 0.05;
const STUDIO_HIGHEST: f32 = 1.5;
/// A light is touched within this many pixels of its cube.
const LIGHT_SLACK: f32 = 24.0;
/// The hue bar's colours (sRGB), evenly spaced from its start to its end.
const HUE_STOPS: [[f32; 3]; 8] = [
    [1.0, 0.0, 0.0],
    [1.0, 110.0 / 255.0, 0.0],
    [1.0, 220.0 / 255.0, 0.0],
    [53.0 / 255.0, 1.0, 0.0],
    [0.0, 1.0, 236.0 / 255.0],
    [0.0, 52.0 / 255.0, 1.0],
    [170.0 / 255.0, 0.0, 1.0],
    [1.0, 0.0, 0.0],
];
/// The texture sets' side when none is asked for, and the most there can be.
const DEFAULT_TEXTURE_SIZE: u32 = 2048;
const MOST_TEXTURE_SIZE: u32 = 4096;

/// Layers' pixels (premultiplied RGBA8), by layer.
pub(crate) type Pixels = Vec<(LayerId, Vec<u8>)>;

impl CanvasSession {
    /// Moves the picture being placed as `change` says.
    fn change_picture(&self, change: impl FnOnce(&mut PicturePlace)) {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        if let Some(placing) = st.scene.as_mut().and_then(|s| s.placing.as_mut()) {
            change(&mut placing.place);
            self.shared.frame(st);
        }
    }

    /// `flags` of the model's look, one for each of `count`'s (false where none was set).
    fn look_flags(&self, flags: impl Fn(&MeshLook) -> &Vec<bool>, count: impl Fn(&Mesh) -> usize) -> Vec<bool> {
        let st = self.shared.lock();
        let Some(scene) = st.scene.as_ref() else { return Vec::new() };
        let set = flags(&scene.look);
        (0..count(&scene.mesh)).map(|i| set.get(i).copied().unwrap_or(false)).collect()
    }

    /// Changes the model's look as an undo step named `name` in the canvas painted on.
    fn change_look(&self, name: &str, change: impl FnOnce(&mut MeshLook, &Mesh)) {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let Some(scene) = st.scene.as_mut() else { return };
        // a picture being placed holds the canvas's stroke open
        if scene.placing.is_some() {
            return;
        }
        let before = scene.look.clone();
        change(&mut scene.look, &scene.mesh);
        if scene.look == before {
            return;
        }
        let after = scene.look.clone();
        let (part, channel) = (scene.current, scene.channel);
        if st.scene_shown() {
            st.scene_stroke_cancel();
        } else {
            st.finish_stroke();
        }
        st.settle();
        if let Some(c) = st.canvas.as_mut() {
            c.record_outside_change(name);
        }
        st.flush();
        let step = st.canvas.as_ref().and_then(Canvas::undo_step);
        if let (Some(step), Some(scene)) = (step, st.scene.as_mut()) {
            if scene.look_steps.len() >= MOST_JOINT_STEPS {
                scene.look_steps.remove(0);
            }
            scene.look_steps.push(LookStep { part, channel, step, before, after });
        }
        st.revision += 1;
        self.shared.frame(st);
    }
}

/// A texture set waiting while another is painted on: its document, its canvas once the model
/// has been drawn, and until then the pixels it was opened with.
pub(crate) struct TextureSet {
    doc: Document,
    canvas: Option<Canvas>,
    pending: Pixels,
    /// A layer to fill with one colour (premultiplied RGBA8) when its canvas is made, unless
    /// pixels wait for it: a new set's Base Layer, with what the model's material gives.
    fill: Option<(LayerId, [u8; 4])>,
}

impl TextureSet {
    fn empty(side: u32) -> TextureSet {
        TextureSet { doc: empty_texture(side), canvas: None, pending: Vec::new(), fill: None }
    }

    /// A new set with `structure`'s layers, its Base Layer (if it still has it) filled with
    /// `rgba` once it is drawn.
    fn filled(side: u32, structure: &Document, rgba: [u8; 4]) -> TextureSet {
        let mut set = TextureSet::empty(side);
        follow_layers(&mut set, structure);
        let base = base_layer();
        set.fill = set.doc.layer(base).is_some().then_some((base, rgba));
        set
    }

    /// The pixels waiting for its canvas, its fill made into pixels (taken: it is given once).
    fn take_pending(&mut self) -> Pixels {
        let mut out = std::mem::take(&mut self.pending);
        if let Some((id, rgba)) = self.fill.take()
            && self.doc.layer(id).is_some()
            && !out.iter().any(|(l, _)| *l == id)
        {
            out.insert(0, (id, solid(self.doc.width(), rgba)));
        }
        out
    }

    /// The pixels waiting for its canvas with its fill, kept waiting.
    fn pending_with_fill(&self) -> Pixels {
        let mut out = self.pending.clone();
        if let Some((id, rgba)) = self.fill
            && self.doc.layer(id).is_some()
            && !out.iter().any(|(l, _)| *l == id)
        {
            out.insert(0, (id, solid(self.doc.width(), rgba)));
        }
        out
    }
}

/// Whether a stroke laying down `amount` (0..1) leaves a texel of `value` as it is: the same
/// value, and one a byte holds exactly (so the texel, the material's own value, is the same).
fn same_texel(amount: f32, value: f32) -> bool {
    let byte = value * 255.0;
    (amount - value).abs() < 1e-6 && (byte - byte.round()).abs() < 1e-3
}

/// The layer every texture set starts with: its Base Layer.
fn base_layer() -> LayerId {
    empty_texture(1).layers().first().map_or(LayerId(1), |l| l.id)
}

/// A `side` square of one colour (premultiplied RGBA8).
fn solid(side: u32, rgba: [u8; 4]) -> Vec<u8> {
    rgba.repeat((side as usize) * (side as usize))
}

/// A colour, roughness or metalness (0..1) as a Base Layer is filled with it: opaque.
fn opaque(rgb: [f32; 3]) -> [u8; 4] {
    let [r, g, b] = rgb.map(|v| (v.clamp(0.0, 1.0) * 255.0).round() as u8);
    [r, g, b, 255]
}

/// A new texture set's document: `side` square, its one layer the Base Layer, nothing painted (the
/// part's base colour shows).
fn empty_texture(side: u32) -> Document {
    let mut doc = Document::new(side, side);
    doc.background_visible = false;
    if let Some(id) = doc.layers().first().map(|l| l.id) {
        doc.rename(id, "Base Layer");
    }
    doc
}

/// The model's files as they were opened, kept to be saved with the artwork: the model, its
/// kind (its file name's extension) and an OBJ's material file.
pub(crate) struct ModelSource {
    bytes: Vec<u8>,
    extension: String,
    mtl: Option<String>,
}

/// A feature of a model that painting on it may not go well with.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ModelWarning {
    /// Texels several surfaces share: paint on one shows on the others.
    OverlappingUvs,
    /// Texture coordinates outside the texture: paint shows on every repeat.
    RepeatingTextures,
}

/// How much of a texture set may be covered twice before it's worth a warning.
const OVERLAP_WARNED: f32 = 0.01;

/// What a newly opened model warns of.
fn warnings_of(mesh: &Mesh) -> Vec<ModelWarning> {
    let mut out = Vec::new();
    if (0..mesh.parts.len()).any(|i| mesh.uv_overlap(i, 256) > OVERLAP_WARNED) {
        out.push(ModelWarning::OverlappingUvs);
    }
    if mesh.repeats_textures() {
        out.push(ModelWarning::RepeatingTextures);
    }
    out
}

/// A file picked along with a model: an OBJ's material file, or an image its materials map.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ModelFile {
    pub name: String,
    pub bytes: Vec<u8>,
}

/// What a model takes from the files picked with it: an OBJ the material file it names (or the
/// only one), and the rest as the images its materials may map. A USDZ packs its own.
fn companions(model: &[u8], extension: &str, files: Vec<ModelFile>) -> (Option<String>, Vec<ModelFile>) {
    if extension == "usdz" {
        return (None, Vec::new());
    }
    if extension != "obj" {
        return (None, files);
    }
    let (libraries, images): (Vec<ModelFile>, Vec<ModelFile>) =
        files.into_iter().partition(|f| extension_of(&f.name) == "mtl");
    let named = String::from_utf8_lossy(model).lines().find_map(|line| {
        let rest = line.trim_start().strip_prefix("mtllib")?;
        rest.starts_with(char::is_whitespace).then(|| rest.trim().to_owned())
    });
    let only = if libraries.len() == 1 { libraries.first() } else { None };
    let chosen = named.and_then(|n| companion(&libraries, &n)).or(only);
    (chosen.map(|f| String::from_utf8_lossy(&f.bytes).into_owned()), images)
}

/// The file among `files` that `path` (as a model's file gives it, relative to the model) names:
/// the one at that path, else the one with its name, ignoring case either way.
fn companion<'a>(files: &'a [ModelFile], path: &str) -> Option<&'a ModelFile> {
    let wanted = tidy_path(path);
    files.iter().find(|f| tidy_path(&f.name) == wanted).or_else(|| files.iter().find(|f| same_file(&f.name, path)))
}

/// A relative path as compared: forward slashes, no `./` parts, lower case.
fn tidy_path(path: &str) -> String {
    let path = path.trim().replace('\\', "/").to_lowercase();
    path.split('/').filter(|p| !p.is_empty() && *p != ".").collect::<Vec<_>>().join("/")
}

/// Whether two paths name the same file: their last parts, ignoring case.
fn same_file(a: &str, b: &str) -> bool {
    let last = |p: &str| p.trim().rsplit(['/', '\\']).next().unwrap_or("").to_lowercase();
    last(a) == last(b)
}

/// A model zipped up with the files it needs, as a folder of them would be: an OBJ with its
/// material file and images, a USD with its images.
struct Zipped {
    /// The model's path in the zip.
    name: String,
    model: Vec<u8>,
    /// Every other file in the zip, named by its path from the model's folder.
    files: Vec<ModelFile>,
}

/// Whether a file's name is one of a model's kinds that open.
fn is_model_name(name: &str) -> bool {
    matches!(extension_of(name).as_str(), "obj" | "usdz" | "usdc" | "usda" | "usd")
}

/// The model in zip `bytes` (the first of those nearest its top), if it holds one and isn't a
/// package of another kind (an artwork or brushes). Folders, and the hidden files another system
/// adds when it zips (`__MACOSX/`, `._` files), are passed over.
fn zipped_model_name(zip: &procraft_engine::zip::ZipReader<'_>) -> Option<String> {
    model_name_among(zip.names())
}

/// The model a zip holding `names` holds (see [`zipped_model_name`]).
pub(crate) fn model_name_among<'n>(names: impl Iterator<Item = &'n str>) -> Option<String> {
    let files: Vec<&str> = names
        .filter(|n| {
            let last = n.rsplit('/').next().unwrap_or(n);
            !n.ends_with('/') && !n.starts_with("__MACOSX/") && !last.starts_with("._") && !last.is_empty()
        })
        .collect();
    let package = files.iter().any(|n| {
        let last = n.rsplit('/').next().unwrap_or(n);
        matches!(last, "Document.archive" | "artwork.json" | "Brush.archive" | "brushset.plist")
    });
    if package {
        return None;
    }
    let depth = |n: &str| n.matches('/').count();
    let models = files.iter().filter(|n| is_model_name(n));
    let nearest = models.clone().map(|n| depth(n)).min()?;
    models.into_iter().find(|n| depth(n) == nearest).map(|n| (*n).to_owned())
}

/// The model in a zip and the other files beside it (see [`zipped_model_name`]).
fn unzip_model(bytes: &[u8]) -> Option<Zipped> {
    let zip = procraft_engine::zip::ZipReader::new(bytes)?;
    let name = zipped_model_name(&zip)?;
    let model = zip.read(&name)?;
    let folder = name.rsplit_once('/').map_or("", |(f, _)| f);
    let mut files = Vec::new();
    for entry in zip.names() {
        let last = entry.rsplit('/').next().unwrap_or(entry);
        if entry == name || entry.ends_with('/') || entry.starts_with("__MACOSX/") || last.starts_with("._") {
            continue;
        }
        let Some(bytes) = zip.read(entry) else { continue };
        let relative =
            if folder.is_empty() { Some(entry) } else { entry.strip_prefix(folder).and_then(|r| r.strip_prefix('/')) };
        files.push(ModelFile { name: relative.unwrap_or(entry).to_owned(), bytes });
    }
    Some(Zipped { name, model, files })
}

/// Whether a zip holding `names` is a model's artwork: its folder, as
/// [`CanvasSession::export_model_artwork`] writes it.
pub(crate) fn is_model_artwork<'n>(mut names: impl Iterator<Item = &'n str>) -> bool {
    let (mut artwork, mut model) = (false, false);
    names.any(|n| {
        artwork |= n == "artwork.json";
        model |= !n.contains('/') && n.starts_with("model.") && is_model_name(n);
        artwork && model
    })
}

/// Every file under `dir` into `zip`, named by its path from `root`.
fn zip_folder(zip: &mut procraft_engine::zip::ZipWriter, root: &Path, dir: &Path) -> std::io::Result<()> {
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            zip_folder(zip, root, &path)?;
        } else if let Ok(name) = path.strip_prefix(root) {
            let name: Vec<String> = name.iter().map(|p| p.to_string_lossy().into_owned()).collect();
            zip.add(&name.join("/"), &fs::read(&path)?);
        }
    }
    Ok(())
}

/// Unpacks a model's artwork (see [`CanvasSession::export_model_artwork`]) from file `path` into
/// the empty folder `dir`: false, with nothing kept, if it isn't one. Nothing goes outside `dir`.
#[uniffi::export]
pub fn unpack_model_artwork(path: String, dir: String) -> bool {
    let Ok(bytes) = fs::read(&path) else { return false };
    let Some(zip) = procraft_engine::zip::ZipReader::new(&bytes) else { return false };
    if !is_model_artwork(zip.names()) {
        return false;
    }
    let root = Path::new(&dir);
    let inside = |name: &str| {
        let parts = || name.split('/');
        !name.contains('\\') && !name.contains(':') && parts().all(|p| !p.is_empty() && p != "." && p != "..")
    };
    let wrote = zip.names().filter(|n| !n.ends_with('/') && inside(n)).try_for_each(|name| {
        let bytes = zip.read(name).ok_or(())?;
        let to = root.join(name);
        to.parent().map_or(Ok(()), fs::create_dir_all).map_err(|_| ())?;
        fs::write(to, bytes).map_err(|_| ())
    });
    if wrote.is_err() {
        let _ = fs::remove_dir_all(root);
    }
    wrote.is_ok()
}

/// The model a zip of one holds (its path in the zip), as the gallery's Import opens it; none if
/// it holds none or is an artwork, a layered package or brushes.
#[uniffi::export]
pub fn zip_model_name(bytes: Vec<u8>) -> Option<String> {
    zipped_model_name(&procraft_engine::zip::ZipReader::new(&bytes)?)
}

/// Sets flag `index` of `flags`, `count` of them.
fn set_flag(flags: &mut Vec<bool>, count: usize, index: u32, value: bool) {
    flags.resize(count, false);
    if let Some(f) = flags.get_mut(index as usize) {
        *f = value;
    }
}

/// The places of the flags set.
fn places(flags: &[bool]) -> Vec<usize> {
    flags.iter().enumerate().filter(|(_, f)| **f).map(|(i, _)| i).collect()
}

/// The kinds of model file that open: OBJ (with its MTL), USDZ, and USD binary or text.
fn read_model(bytes: &[u8], extension: &str, mtl: Option<&str>) -> Result<Mesh, MeshError> {
    match extension {
        "obj" => read_obj(&String::from_utf8_lossy(bytes), mtl),
        "usdz" => read_usdz(bytes),
        "usdc" => read_usdc(bytes),
        "usda" | "usd" => read_usd(bytes),
        _ => Err(MeshError::Unreadable),
    }
}

/// A model file's kind from its name, lower case.
fn extension_of(name: &str) -> String {
    name.rsplit_once('.').map_or("", |(_, e)| e).to_ascii_lowercase()
}

pub(crate) struct Scene {
    mesh: Mesh,
    source: Arc<ModelSource>,
    view: Option<MeshView>,
    /// How the model was last seen, until it is drawn again.
    orbit: Option<Orbit>,
    /// The texture sets by part; the current one's place is empty (it is the session's own).
    sets: Vec<Option<TextureSet>>,
    current: usize,
    /// Each texture set's side (pixels).
    texture_size: u32,
    /// The stroke as drawn on the screen: a canvas the window's size, and what it covers.
    screen: Option<(Document, Canvas)>,
    bounds: IRect,
    /// Each set's picture as last shown on the model: its canvas's composite count, and whether
    /// it was the session's canvas then (its levels are made again when either changes).
    filtered: Vec<Option<(u64, bool)>>,
    /// Strokes reach every surface under them, not only the nearest.
    paint_through: bool,
    /// The current texture set is shown flat, as a canvas, in place of the model.
    flat: bool,
    /// An environment to light the model by, waiting for the next frame to be set up.
    environment: Option<procraft_engine::environment::Equirect>,
    /// The environment's name, kept with the model (none: the app's default).
    environment_name: Option<String>,
    /// How bright the light is taken (1 as it is; 0 to 5).
    exposure: f32,
    /// The environment shows behind the model (else the canvas's surround); and whether one has
    /// been set up yet.
    environment_shown: bool,
    lit: bool,
    /// The lights it is lit by, as the interface sets them.
    lights: Vec<ModelLight>,
    /// The Lighting Studio is showing, seen through its own camera (kept while the model is open).
    studio_open: bool,
    studio: Option<Orbit>,
    /// The texture shows flat only for a tool that works on it, back to the model when it ends.
    flat_for_tool: bool,
    /// A picture being placed on the model.
    placing: Option<PlacedPicture>,
    /// What a model opened from its file warns of, until it has been said.
    warnings: Vec<ModelWarning>,
    /// What fills and adjustments reach while meshes are picked: the meshes it was made for, and
    /// where they lie in the texture.
    reach: Option<(Vec<usize>, procraft_engine::selection::Mask)>,
    /// The selection as it was before an adjustment was kept to the meshes picked.
    held_selection: Option<Option<procraft_engine::selection::Mask>>,
    /// How the Reference window sees the model, from when it first has.
    reference_orbit: Option<Orbit>,
    /// The window as the eyedropper last saw it.
    seen: Option<SeenFrame>,
    /// What of the model is hidden, locked and picked.
    look: MeshLook,
    /// The hide, lock and pick changes, each an undo step in the canvas it was made on.
    look_steps: Vec<LookStep>,
    /// Each part's roughness and metalness documents, once made; the place of the channel painted
    /// is empty (it is the session's own).
    channels: Vec<[Option<TextureSet>; 2]>,
    /// The channel painted on the current part: 0 its colour, 1 roughness, 2 metalness. While it
    /// isn't the colour, the part's colour document waits in [`Scene::sets`].
    channel: usize,
    /// A channel's tile is chosen under the current layer (shown blue, painting going to that
    /// channel), not the layer's own row.
    tile_chosen: bool,
    /// The tiles chosen (and rows chosen again), each an undo step in the channels it was made
    /// between.
    tile_steps: Vec<TileStep>,
    /// Each part's channel maps as last given to the view (as [`Scene::filtered`]).
    channel_shown: Vec<[Option<(u64, bool)>; 2]>,
    /// The images picked with an OBJ, until its materials' maps have been taken from them.
    picked: Vec<ModelFile>,
    /// Each part's normal and occlusion maps from the model's file (RGBA8, the texture size
    /// square), shown as they are; and whether the view has them as they are now.
    surface_maps: Vec<[Option<Arc<Vec<u8>>>; 2]>,
    surface_shown: bool,
    /// The stroke under way changes what's under it (a smudge, wet paint): the layer as the camera
    /// saw it as it began, on the screen's canvas, and as it was.
    laying: Option<wgpu::TextureView>,
    /// A stroke is being carried onto the model each frame; `ending`: once more, then it ends.
    pub(crate) stroking: bool,
    ending: bool,
    /// What the stroke on the current part's colour lays down in its roughness and metalness as
    /// well (a stroke on the colour paints all three), while it goes on.
    material_paint: Option<[MaterialPaint; 2]>,
    /// The brush's roughness and metalness sources as textures, with their keys.
    material_sources: [Option<(u64, wgpu::TextureView)>; 2],
    /// Strokes that painted the colour, roughness and metalness together: the step each made in
    /// each one's canvas, so undo and redo take them together.
    joint_steps: Vec<JointStep>,
    /// The brush's grain as a texture, with its key (its own image's, or its built-in one's with
    /// the top bit set), for a stroke's grain read along the model's axes.
    grain_source: Option<(u64, wgpu::TextureView)>,
    /// The stroke textures the texture sets' canvases draw their strokes in by turns, made when
    /// first wanted: the colours', and the roughness and metalness's (a stroke on a colour draws
    /// in one of each at once, one channel after the other).
    strokes: [Option<SharedStroke>; 2],
}

/// What a stroke on a part's colour lays down in its roughness or metalness: the brush's amount
/// (0..1), one tile of its source every so many canvas pixels, and the source's key (none: the
/// amount all over).
#[derive(Clone, Copy, Debug, PartialEq)]
struct MaterialPaint {
    amount: f32,
    tile: f32,
    source: Option<u64>,
    /// The source is read about the camera, as the brush's grain is, not fixed to the model.
    follows_camera: bool,
}

/// A stroke that painted part `part`'s colour, roughness and metalness: the step it made in each
/// (as [`Canvas::undo_step`] numbers them), if it made one there.
#[derive(Clone, Copy, Debug, PartialEq)]
struct JointStep {
    part: usize,
    steps: [Option<u64>; 3],
}

/// The most strokes remembered as painting every channel (older ones are long out of the undo
/// history).
const MOST_JOINT_STEPS: usize = 512;

/// A tile chosen under part `part`'s current layer, or its row chosen again: the channel painted
/// and whether a tile was chosen, before and after, and the step it left in the canvas of each
/// channel it was made between (as [`Canvas::undo_step`] numbers them), for undo and redo to
/// follow.
#[derive(Clone, Copy, Debug, PartialEq)]
struct TileStep {
    part: usize,
    from: (usize, bool),
    to: (usize, bool),
    steps: [Option<u64>; 3],
}

/// What of a model is hidden or locked, and which of its meshes strokes reach.
#[derive(Clone, Debug, Default, PartialEq)]
struct MeshLook {
    /// By submesh: hidden ones aren't drawn or painted, locked ones are drawn but take no paint.
    hidden: Vec<bool>,
    locked: Vec<bool>,
    /// By texture set, the same for all its meshes.
    sets_hidden: Vec<bool>,
    sets_locked: Vec<bool>,
    /// The meshes picked, in the texture set painted on: strokes reach only them. None picked:
    /// the whole set.
    active: Vec<usize>,
}

/// A change to [`MeshLook`] kept in `part`'s `channel` canvas as `step`, with the look before and
/// after it, for undo and redo to follow.
#[derive(Clone, Debug, PartialEq)]
struct LookStep {
    part: usize,
    channel: usize,
    step: u64,
    before: MeshLook,
    after: MeshLook,
}

/// A picture being placed on a model, shown as it lands, a stroke on its own new layer until it
/// is kept.
struct PlacedPicture {
    view: wgpu::TextureView,
    size: (u32, u32),
    place: PicturePlace,
    first: PicturePlace,
    /// Where it was last carried onto the model, and how the model was seen then.
    carried: Option<(PicturePlace, Orbit)>,
}

/// Where a picture lies on the window: its middle, how far it reaches from there along its own
/// sides (one negative: it is flipped), and its turn (radians, clockwise on the window).
#[derive(Clone, Copy, Debug, PartialEq)]
struct PicturePlace {
    middle: [f32; 2],
    half: [f32; 2],
    turn: f32,
}

impl PicturePlace {
    /// A `size` picture first placed on a `window`: in its middle, its long side this share of
    /// the window's shorter side.
    const FIRST_SHARE: f32 = 0.7;

    fn first(size: (u32, u32), window: (u32, u32)) -> PicturePlace {
        let long = size.0.max(size.1).max(1) as f32;
        let reach = window.0.min(window.1) as f32 * Self::FIRST_SHARE / 2.0;
        PicturePlace {
            middle: [window.0 as f32 / 2.0, window.1 as f32 / 2.0],
            half: [size.0 as f32 / long * reach, size.1 as f32 / long * reach],
            turn: 0.0,
        }
    }

    /// Its four corners on the window, clockwise from its top left.
    fn corners(&self) -> [[f32; 2]; 4] {
        let (s, c) = self.turn.sin_cos();
        [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)].map(|(u, v)| {
            let (x, y) = (u * self.half[0], v * self.half[1]);
            [self.middle[0] + x * c - y * s, self.middle[1] + x * s + y * c]
        })
    }

    /// Where window point `p` lies across it: -1 to 1 along each of its sides.
    fn across(&self, p: [f32; 2]) -> [f32; 2] {
        let (s, c) = self.turn.sin_cos();
        let (x, y) = (p[0] - self.middle[0], p[1] - self.middle[1]);
        [(x * c + y * s) / self.half[0], (y * c - x * s) / self.half[1]]
    }

    fn holds(&self, p: [f32; 2]) -> bool {
        self.across(p).iter().all(|t| t.abs() <= 1.0)
    }

    /// A `size` picture's pixels from the window's, x then y (a·x + b·y + c).
    fn to_picture(self, size: (u32, u32)) -> [[f32; 3]; 2] {
        let (s, c) = self.turn.sin_cos();
        let row = |extent: u32, half: f32, a: f32, b: f32| {
            let k = extent as f32 / (2.0 * half);
            [a * k, b * k, extent as f32 / 2.0 - (a * self.middle[0] + b * self.middle[1]) * k]
        };
        [row(size.0, self.half[0], c, s), row(size.1, self.half[1], -s, c)]
    }
}

/// The window as last drawn for the eyedropper, and what it was drawn from.
struct SeenFrame {
    size: (u32, u32),
    orbit: Orbit,
    revision: u64,
    pixels: Vec<u8>,
}

/// The `side` square of RGBA8 `pixels` (a window `size`) centred on `at`: what lies off the window
/// clear.
fn window_round(pixels: &[u8], size: (u32, u32), at: (f32, f32), side: u32) -> Vec<u8> {
    let (w, h) = (size.0 as i64, size.1 as i64);
    let (x0, y0) = (at.0 as i64 - i64::from(side) / 2, at.1 as i64 - i64::from(side) / 2);
    let mut out = vec![0u8; side as usize * side as usize * 4];
    for (row, line) in out.chunks_exact_mut(side as usize * 4).enumerate() {
        let y = y0 + row as i64;
        if !(0..h).contains(&y) {
            continue;
        }
        for (col, px) in line.as_chunks_mut::<4>().0.iter_mut().enumerate() {
            let x = x0 + col as i64;
            if !(0..w).contains(&x) {
                continue;
            }
            let at = ((y * w + x) * 4) as usize;
            if let Some(from) = pixels.get(at..at + 4) {
                px.copy_from_slice(from);
            }
        }
    }
    out
}

/// How long a model takes to go round in the gallery's Preview.
const MODEL_PREVIEW_FRAMES: u32 = 72;
const MODEL_PREVIEW_FPS: u32 = 18;

/// What undo and redo call a channel's content replaced.
const MAP_REPLACED: &str = "replace material map content";

/// `image` (premultiplied RGBA8) stretched over a `side` square, read between its pixels. None if
/// it holds nothing.
fn stretched(image: &crate::layers::ThumbnailImage, side: u32) -> Option<Vec<u8>> {
    let (w, h) = (image.width as usize, image.height as usize);
    if w == 0 || h == 0 || image.rgba.len() != w * h * 4 {
        return None;
    }
    let at = |x: usize, y: usize, c: usize| f32::from(image.rgba.get((y * w + x) * 4 + c).copied().unwrap_or(0));
    let mut out = Vec::with_capacity(side as usize * side as usize * 4);
    for y in 0..side {
        let fy = ((y as f32 + 0.5) * h as f32 / side as f32 - 0.5).clamp(0.0, (h - 1) as f32);
        let (y0, ty) = (fy as usize, fy.fract());
        let y1 = (y0 + 1).min(h - 1);
        for x in 0..side {
            let fx = ((x as f32 + 0.5) * w as f32 / side as f32 - 0.5).clamp(0.0, (w - 1) as f32);
            let (x0, tx) = (fx as usize, fx.fract());
            let x1 = (x0 + 1).min(w - 1);
            for c in 0..4 {
                let top = at(x0, y0, c) * (1.0 - tx) + at(x1, y0, c) * tx;
                let low = at(x0, y1, c) * (1.0 - tx) + at(x1, y1, c) * tx;
                out.push((top * (1.0 - ty) + low * ty).round() as u8);
            }
        }
    }
    Some(out)
}

/// How far past the meshes picked fills and adjustments reach, texels: as far as strokes do.
const REACH_GROWN: usize = 2;

/// What undo and redo call a mesh or texture set hidden, shown, locked or unlocked, or meshes
/// picked.
const MESHES_SELECTED: &str = "select meshes";

/// Which part a texel of a `side` square texture holds texture coordinates `uv`: the coordinates
/// wrapped into the texture, rows running down from v = 1.
fn texel_of(uv: [f32; 2], side: u32) -> (u32, u32) {
    let wrap = |t: f32| t - t.floor();
    let along = |t: f32| ((t * side as f32) as u32).min(side.saturating_sub(1));
    (along(wrap(uv[0])), along(1.0 - wrap(uv[1])))
}

/// What undo and redo call a tile chosen, and the layer's row chosen again.
const TILE_CHOSEN: &str = "select material layer";
const ROW_CHOSEN: &str = "deselect material layer";

/// The step undone (true) or redone by work on `canvas`, from its next undo and redo steps
/// `before` it.
pub(crate) fn stepped(before: (Option<u64>, Option<u64>), canvas: &Canvas) -> Option<(u64, bool)> {
    match (before, (canvas.undo_step(), canvas.redo_step())) {
        ((Some(s), _), (u, Some(r))) if r == s && u != Some(s) => Some((s, true)),
        ((_, Some(s)), (Some(u), r)) if u == s && r != Some(s) => Some((s, false)),
        _ => None,
    }
}

impl Scene {
    /// `mesh` with a texture set `texture_size` square for each of its parts, set `current` the
    /// session's own; the others are `waiting` where given, else new.
    fn new(
        mesh: Mesh,
        source: ModelSource,
        texture_size: u32,
        current: usize,
        mut waiting: Vec<Option<TextureSet>>,
    ) -> Scene {
        let current = current.min(mesh.parts.len().saturating_sub(1));
        let sets = (0..mesh.parts.len())
            .map(|i| {
                (i != current).then(|| {
                    waiting.get_mut(i).and_then(Option::take).unwrap_or_else(|| TextureSet::empty(texture_size))
                })
            })
            .collect();
        Scene {
            mesh,
            source: Arc::new(source),
            view: None,
            orbit: None,
            sets,
            current,
            texture_size,
            screen: None,
            bounds: IRect::EMPTY,
            filtered: Vec::new(),
            paint_through: false,
            flat: false,
            environment: None,
            environment_name: None,
            exposure: 1.0,
            environment_shown: true,
            lit: false,
            lights: Light::DEFAULTS.iter().map(ModelLight::from_gpu).collect(),
            studio_open: false,
            studio: None,
            flat_for_tool: false,
            placing: None,
            warnings: Vec::new(),
            reach: None,
            held_selection: None,
            reference_orbit: None,
            seen: None,
            look: MeshLook::default(),
            look_steps: Vec::new(),
            channels: Vec::new(),
            channel: 0,
            tile_chosen: false,
            tile_steps: Vec::new(),
            channel_shown: Vec::new(),
            picked: Vec::new(),
            surface_maps: Vec::new(),
            surface_shown: false,
            laying: None,
            stroking: false,
            ending: false,
            material_paint: None,
            material_sources: [None, None],
            joint_steps: Vec::new(),
            grain_source: None,
            strokes: [None, None],
        }
    }

    /// The stroke texture the canvases of the texture sets' colours (`material` false), or of
    /// their roughness and metalness (true), draw in by turns.
    fn stroke(&mut self, gpu: &Gpu, material: bool) -> SharedStroke {
        let side = self.texture_size;
        let [colour, materials] = &mut self.strokes;
        let slot = if material { materials } else { colour };
        slot.get_or_insert_with(|| SharedStroke::new(gpu, side, side)).clone()
    }

    /// Names of the texture sets: their materials', else numbered.
    pub(crate) fn set_names(&self) -> Vec<String> {
        let parts = self.mesh.parts.iter().enumerate();
        parts
            .map(|(i, p)| if p.material.is_empty() { format!("Texture Set {}", i + 1) } else { p.material.clone() })
            .collect()
    }

    /// A picture of the model through `orbit`, `side` square, on the surround, with its environment
    /// behind it if one shows. None until it has been drawn.
    fn picture_through(&mut self, gpu: &Gpu, orbit: Orbit, side: u32) -> Option<crate::layers::ThumbnailImage> {
        let s = crate::SURROUND;
        let clear = [f64::from(s[0]), f64::from(s[1]), f64::from(s[2]), 1.0];
        let environment = self.lit && self.environment_shown;
        let rgba = self.view.as_mut()?.picture_from(gpu, orbit, side, side, clear, environment);
        match rgba {
            Ok(rgba) => Some(crate::layers::ThumbnailImage { width: side, height: side, rgba }),
            Err(e) => {
                log::warn!("a picture of the model: {e}");
                None
            }
        }
    }

    /// How the model is seen now.
    fn orbit(&self) -> Option<Orbit> {
        self.view.as_ref().map(|v| v.orbit).or(self.orbit)
    }

    /// Submesh `index`'s texture set.
    fn set_of(&self, index: usize) -> Option<usize> {
        self.mesh.submeshes.get(index).map(|m| m.part)
    }

    /// Whether submesh `index` is hidden, itself or with its texture set.
    fn mesh_hidden(&self, index: usize) -> bool {
        let set = self.set_of(index).and_then(|p| self.look.sets_hidden.get(p)).copied();
        self.look.hidden.get(index).copied().unwrap_or(false) || set.unwrap_or(false)
    }

    /// Whether submesh `index` is locked, itself or with its texture set.
    fn mesh_locked(&self, index: usize) -> bool {
        let set = self.set_of(index).and_then(|p| self.look.sets_locked.get(p)).copied();
        self.look.locked.get(index).copied().unwrap_or(false) || set.unwrap_or(false)
    }

    /// The meshes a stroke is meant for: those picked, else the texture set's.
    fn meant_meshes(&self) -> Vec<usize> {
        if !self.look.active.is_empty() {
            return self.look.active.clone();
        }
        (0..self.mesh.submeshes.len()).filter(|&i| self.set_of(i) == Some(self.current)).collect()
    }

    /// Whether submesh `index` takes strokes now.
    fn mesh_painted(&self, index: usize) -> bool {
        let meant = self.look.active.is_empty() || self.look.active.contains(&index);
        meant && !self.mesh_hidden(index) && !self.mesh_locked(index)
    }

    /// Draws `layer` of the current part as the camera sees it (a window `size`) onto the screen's
    /// canvas, and gives that picture back. None before the model has been drawn.
    fn see_layer(&mut self, gpu: &Gpu, layer: &wgpu::TextureView, size: (u32, u32)) -> Option<wgpu::TextureView> {
        self.tell_view_what_takes_paint();
        let part = self.current;
        let (doc, screen) = self.screen.as_mut()?;
        let view = self.view.as_mut()?;
        screen.render(gpu, doc, None);
        let seen = gpu.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("layer as seen"),
            size: wgpu::Extent3d { width: size.0.max(1), height: size.1.max(1), depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT
                | wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let target = seen.create_view(&wgpu::TextureViewDescriptor::default());
        let mut encoder =
            gpu.device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("layer as seen") });
        view.draw_seen(gpu, &mut encoder, part, layer, &target, size.0, size.1);
        gpu.queue.submit([encoder.finish()]);
        screen.copy_to_layer(gpu, doc.current(), &seen).then_some(target)
    }

    /// Gives the view the meshes that show.
    fn tell_view_what_shows(&mut self) {
        let hidden: Vec<bool> = (0..self.mesh.submeshes.len()).map(|i| self.mesh_hidden(i)).collect();
        if let Some(view) = self.view.as_mut() {
            for (i, hide) in hidden.into_iter().enumerate() {
                view.set_hidden(i, hide);
            }
        }
    }

    /// Gives the view the meshes strokes reach.
    fn tell_view_what_takes_paint(&mut self) {
        let painted: Vec<bool> = (0..self.mesh.submeshes.len()).map(|i| self.mesh_painted(i)).collect();
        if let Some(view) = self.view.as_mut() {
            for (i, takes) in painted.into_iter().enumerate() {
                view.set_painted(i, takes);
            }
        }
    }

    /// Where fills and adjustments stay on the current texture set while some of its meshes are
    /// picked, or locked or hidden; none while they reach all of it.
    fn reach(&mut self) -> Option<procraft_engine::selection::Mask> {
        let all: Vec<usize> =
            (0..self.mesh.submeshes.len()).filter(|&i| self.set_of(i) == Some(self.current)).collect();
        let mut picked: Vec<usize> = all.iter().copied().filter(|&i| self.mesh_painted(i)).collect();
        picked.sort_unstable();
        if picked.is_empty() || picked.len() >= all.len() {
            return None;
        }
        if self.reach.as_ref().is_none_or(|(made, _)| *made != picked) {
            let side = self.texture_size;
            let data = self.mesh.submesh_coverage(&picked, side as usize, REACH_GROWN);
            self.reach = Some((picked, procraft_engine::selection::Mask { width: side, height: side, data }));
        }
        self.reach.as_ref().map(|(_, m)| m.clone())
    }

    /// Why strokes can't land: every mesh they're meant for is hidden, or hidden or locked.
    fn why_blocked(&self) -> Option<crate::layers::Blocked> {
        let meant = self.meant_meshes();
        if self.flat || meant.is_empty() {
            return None;
        }
        if meant.iter().all(|&i| self.mesh_hidden(i)) {
            return Some(crate::layers::Blocked::MeshHidden);
        }
        meant.iter().all(|&i| self.mesh_hidden(i) || self.mesh_locked(i)).then_some(crate::layers::Blocked::MeshLocked)
    }

    /// The surface under window point (`x`, `y`) of a `width` × `height` window, among what shows.
    fn surface_at(&self, x: f32, y: f32, (width, height): (u32, u32)) -> Option<SurfaceHit> {
        let (from, along) = self.orbit()?.sight(x, y, width, height);
        self.mesh.first_surface(from, along, |i| self.mesh_hidden(i))
    }
}

#[uniffi::export]
impl CanvasSession {
    /// Opens a model to paint on: `file` the model file named `file_name` (OBJ, USDZ or USDC, by
    /// its extension), with the `files` picked along with it (an OBJ's material file and the
    /// images it maps). A zip (`.zip`) brings the model and those files in one: as a folder
    /// holding them, its model the one nearest its top. Each texture set is `texture_size` pixels
    /// square (0: the default).
    #[uniffi::constructor]
    pub fn open_model(
        file: Vec<u8>,
        file_name: String,
        files: Vec<ModelFile>,
        name: String,
        texture_size: u32,
    ) -> Result<Arc<CanvasSession>, MobileError> {
        let (file, file_name, files) = if extension_of(&file_name) == "zip" {
            let zipped = unzip_model(&file).ok_or_else(|| file_error("opening the model", "no model in the zip"))?;
            let mut all = zipped.files;
            all.extend(files);
            (zipped.model, zipped.name, all)
        } else {
            (file, file_name, files)
        };
        let extension = extension_of(&file_name);
        let (mtl, picked) = companions(&file, &extension, files);
        let mesh = read_model(&file, &extension, mtl.as_deref())
            .map_err(|e| file_error("opening the model", mesh_error_text(&e)))?;
        let side = if texture_size == 0 { DEFAULT_TEXTURE_SIZE } else { texture_size.clamp(64, MOST_TEXTURE_SIZE) };
        let warnings = warnings_of(&mesh);
        let mut scene = Scene::new(mesh, ModelSource { bytes: file, extension, mtl }, side, 0, Vec::new());
        scene.picked = picked;
        scene.warnings = warnings;
        // each texture set's Base Layer the material's colour, where the model brings no image
        let brings = scene.brings_colour();
        let colour =
            |part: usize| (!brings.get(part).copied().unwrap_or(false)).then(|| opaque(scene.mesh.surface(part).0));
        let own = colour(0).map(|rgba| vec![(base_layer(), solid(side, rgba))]).unwrap_or_default();
        for part in 1..scene.sets.len() {
            let fill = colour(part).map(|rgba| (base_layer(), rgba));
            if let Some(set) = scene.sets.get_mut(part).and_then(Option::as_mut) {
                set.fill = fill;
            }
        }
        let session = CanvasSession::with_document(empty_texture(side), name, own);
        session.shared.lock().scene = Some(scene);
        Ok(session)
    }

    /// The model painted on, if this is one.
    pub fn model_info(&self) -> Option<ModelInfo> {
        self.shared.lock().scene.as_ref().map(ModelInfo::of)
    }

    /// Each texture set's layers, in order, whichever is painted on; none unless a model is open.
    pub fn texture_set_stats(&self) -> Vec<TextureSetStats> {
        let st = self.shared.lock();
        let Some(scene) = st.scene.as_ref() else { return Vec::new() };
        let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
        let row = |(name, doc): (String, &Document)| TextureSetStats {
            name,
            used: count(doc.pixel_layer_ids().count()),
            maximum: count(doc.layer_cap()),
        };
        scene.set_names().into_iter().zip(st.colour_docs()).map(row).collect()
    }

    /// The texture set painted on (the layers show its layers).
    pub fn texture_set(&self) -> u32 {
        self.shared.lock().scene.as_ref().map_or(0, |s| u32::try_from(s.current).unwrap_or(0))
    }

    /// Paints on texture set `index` from now on.
    pub fn choose_texture_set(&self, index: u32) {
        let mut st = self.shared.lock();
        st.choose_texture_set(index as usize);
        self.shared.frame(&mut st);
    }

    /// Starts placing `image` (RGBA8, `straight` or premultiplied) on the shown model, on a new layer: it shows as it
    /// lands, taken from the camera, until [`CanvasSession::model_picture_end`]. False if it can't
    /// be (no model shown, no room for a layer, nothing to place).
    pub fn model_picture_begin(&self, image: crate::layers::ThumbnailImage, straight: bool) -> bool {
        let image = if straight { crate::clipboard::premultiplied(image) } else { image };
        let (w, h) = (image.width, image.height);
        if w == 0 || h == 0 || image.rgba.len() != w as usize * h as usize * 4 {
            return false;
        }
        if !self.shared.lock().scene_shown() || self.add_layer_quietly() == 0 {
            return false;
        }
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let layer = st.doc.current();
        let size = st.window_size;
        let (Some(gpu), Some(scene), Some(canvas)) = (st.gpu.as_ref(), st.scene.as_mut(), st.canvas.as_mut()) else {
            return false;
        };
        let texture = gpu.device.create_texture_with_data(
            &gpu.queue,
            &wgpu::TextureDescriptor {
                label: Some("picture placed on the model"),
                size: wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8Unorm,
                usage: wgpu::TextureUsages::TEXTURE_BINDING,
                view_formats: &[],
            },
            wgpu::util::TextureDataOrder::LayerMajor,
            &image.rgba,
        );
        let first = PicturePlace::first((w, h), size);
        scene.placing = Some(PlacedPicture {
            view: texture.create_view(&wgpu::TextureViewDescriptor::default()),
            size: (w, h),
            place: first,
            first,
            carried: None,
        });
        canvas.begin_drawn_stroke(layer, StrokeStyle::new(StrokeTool::Paint, [0.0; 3], 1.0));
        self.shared.frame(st);
        true
    }

    /// The corners of the picture being placed on the window (x, y pairs clockwise from its top
    /// left), while one is: for the box round it.
    pub fn model_picture_box(&self) -> Option<Vec<f32>> {
        let st = self.shared.lock();
        let placing = st.scene.as_ref()?.placing.as_ref()?;
        Some(placing.place.corners().into_iter().flatten().collect())
    }

    /// Moves the picture being placed by (`dx`, `dy`) window pixels, scales it by `scale` and turns
    /// it by `turn` radians about its middle.
    pub fn model_picture_move(&self, dx: f32, dy: f32, scale: f32, turn: f32) {
        self.change_picture(|p| {
            p.middle = [p.middle[0] + dx, p.middle[1] + dy];
            if scale.is_finite() && scale > 0.0 {
                p.half = p.half.map(|h| h * scale);
            }
            p.turn += turn;
        });
    }

    /// Flips the picture being placed across (`horizontal`) or up and down.
    pub fn model_picture_flip(&self, horizontal: bool) {
        self.change_picture(|p| {
            let side = usize::from(!horizontal);
            if let Some(h) = p.half.get_mut(side) {
                *h = -*h;
            }
        });
    }

    /// Puts the picture being placed back where it first lay.
    pub fn model_picture_reset(&self) {
        let first = self.shared.lock().scene.as_ref().and_then(|s| s.placing.as_ref()).map(|p| p.first);
        if let Some(first) = first {
            self.change_picture(|p| *p = first);
        }
    }

    /// Ends placing a picture: it stays on its layer (`keep`), or goes with the layer.
    pub fn model_picture_end(&self, keep: bool) {
        let layer = {
            let mut guard = self.shared.lock();
            let st = &mut *guard;
            let Some(scene) = st.scene.as_mut() else { return };
            if scene.placing.take().is_none() {
                return;
            }
            let (Some(gpu), Some(canvas)) = (st.gpu.as_ref(), st.canvas.as_mut()) else { return };
            if keep {
                canvas.end_stroke();
                canvas.render(gpu, &mut st.doc, None);
            } else {
                canvas.cancel_stroke();
            }
            st.revision += 1;
            self.shared.frame(st);
            st.doc.current()
        };
        if !keep {
            self.delete_layer(layer.0);
        }
    }

    /// The model as the Reference window shows it, `side` square: through a camera of its own, at
    /// first where the model is seen from, its textures as they are now (shown flat or not).
    pub fn reference_model_picture(&self, side: u32) -> Option<crate::layers::ThumbnailImage> {
        let side = side.clamp(8, 2048);
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        st.scene.as_ref()?;
        st.scene_frame(None, wgpu::TextureFormat::Rgba8Unorm, st.window_size, None);
        let (Some(gpu), Some(scene)) = (st.gpu.as_ref(), st.scene.as_mut()) else { return None };
        let orbit = scene.reference_orbit.or_else(|| scene.orbit())?;
        scene.reference_orbit = Some(orbit);
        scene.picture_through(gpu, orbit, side)
    }

    /// A finger dragged by (`dx`, `dy`) pixels across the Reference window, `side` pixels across,
    /// turns its model.
    pub fn reference_model_turn(&self, dx: f32, dy: f32, side: f32) {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return };
        let Some(mut orbit) = scene.reference_orbit.or_else(|| scene.orbit()) else { return };
        let length = (dx * dx + dy * dy).sqrt();
        orbit.turn(dx, dy, length / side.max(1.0) * TURN_PER_SIDE);
        scene.reference_orbit = Some(orbit);
    }

    /// How many frames a model turns through in the gallery's Preview, and at how many a second.
    pub fn model_preview_frames(&self) -> u32 {
        MODEL_PREVIEW_FRAMES
    }

    pub fn model_preview_fps(&self) -> u32 {
        MODEL_PREVIEW_FPS
    }

    /// Frame `index` of a model turning in the gallery's Preview, `side` square, opened without a
    /// window ([`CanvasSession::draw_without_window`]).
    pub fn model_preview_picture(&self, index: u32, side: u32) -> Option<crate::layers::ThumbnailImage> {
        let side = side.clamp(8, 2048);
        {
            let mut st = self.shared.lock();
            if index == 0 || st.scene.as_ref().is_none_or(|s| s.view.is_none()) {
                st.window_size = (side, side);
                st.scene_frame(None, wgpu::TextureFormat::Rgba8Unorm, (side, side), None);
            }
        }
        let seconds = MODEL_PREVIEW_FRAMES as f32 / MODEL_PREVIEW_FPS as f32;
        let turntable = Turntable { swing: false, seconds, zoom: 0.0, ease: 0.0, environment: false };
        self.model_turntable_picture(index, MODEL_PREVIEW_FRAMES, turntable, side, side, false)
    }

    /// Replaces layer `id`'s content in `channel` (0 its colour, 1 roughness, 2 metalness) with
    /// `image` (straight RGBA8) stretched over the texture, as an undo step; that channel is
    /// painted from now on. False if it can't be.
    pub fn replace_material_map(&self, id: u64, channel: u32, image: crate::layers::ThumbnailImage) -> bool {
        let layer = LayerId(id);
        {
            let st = self.shared.lock();
            if channel > 2 || st.scene.is_none() || st.doc.layer(layer).is_none_or(|l| st.doc.is_locked(l.id)) {
                return false;
            }
        }
        let side = self.shared.lock().doc.width();
        let Some(pixels) = stretched(&crate::clipboard::premultiplied(image), side) else { return false };
        self.choose_channel_tile(channel);
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        st.settle();
        let Some(canvas) = st.canvas.as_mut() else { return false };
        canvas.name_next_step(MAP_REPLACED);
        canvas.write_layer(layer, pixels);
        st.flush();
        st.revision += 1;
        self.shared.frame(st);
        true
    }

    /// Whether a selection drawn on a model reaches every surface under it.
    pub fn selection_x_ray(&self) -> bool {
        self.shared.lock().x_ray
    }

    /// Sets whether a selection drawn on a model reaches every surface under it (X-Ray).
    pub fn set_selection_x_ray(&self, on: bool) {
        self.shared.lock().x_ray = on;
    }

    /// Whether strokes paint through the model: every surface under them, not only the
    /// nearest.
    pub fn paint_through(&self) -> bool {
        self.shared.lock().scene.as_ref().is_some_and(|s| s.paint_through)
    }

    /// Lights the model by the environment `name` whose panorama is `hdr` (a Radiance `.hdr`)
    /// and shows it behind the model; false if it can't be read.
    pub fn set_model_environment(&self, name: String, hdr: Vec<u8>) -> bool {
        let Some(panorama) = procraft_engine::environment::read_hdr(&hdr) else { return false };
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return false };
        scene.environment = Some(panorama);
        scene.environment_name = Some(name);
        self.shared.frame(&mut st);
        true
    }

    /// The name of the environment the model was last lit by, if one was set.
    pub fn model_environment(&self) -> Option<String> {
        self.shared.lock().scene.as_ref().and_then(|s| s.environment_name.clone())
    }

    /// How bright the model's light is taken: 1 as it is, 0 to [`MOST_EXPOSURE`].
    pub fn model_exposure(&self) -> f32 {
        self.shared.lock().scene.as_ref().map_or(1.0, |s| s.exposure)
    }

    pub fn set_model_exposure(&self, exposure: f32) {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return };
        if exposure.is_finite() {
            scene.exposure = exposure.clamp(0.0, MOST_EXPOSURE);
        }
        self.shared.frame(&mut st);
    }

    /// The lights the model is lit by.
    pub fn model_lights(&self) -> Vec<ModelLight> {
        self.shared.lock().scene.as_ref().map_or_else(Vec::new, |s| s.lights.clone())
    }

    /// Lights the model by `lights` (the first [`MOST_LIGHTS`]).
    pub fn set_model_lights(&self, lights: Vec<ModelLight>) {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return };
        scene.lights = lights.into_iter().take(MOST_LIGHTS).map(ModelLight::sane).collect();
        self.shared.frame(&mut st);
    }

    /// Changes light `index` to `light` (its place, colour and intensity).
    pub fn set_model_light(&self, index: u32, light: ModelLight) {
        let mut st = self.shared.lock();
        let Some(slot) = st.scene.as_mut().and_then(|s| s.lights.get_mut(index as usize)) else { return };
        *slot = light.sane();
        self.shared.frame(&mut st);
    }

    /// Adds a white light above and behind the model; false when it has as many as it can take.
    pub fn add_model_light(&self) -> bool {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return false };
        if scene.lights.len() >= MOST_LIGHTS {
            return false;
        }
        let [x, y, z] = NEW_LIGHT;
        scene.lights.push(ModelLight { x, y, z, hue: 0.0, saturation: 0.0, intensity: DEFAULT_INTENSITY });
        self.shared.frame(&mut st);
        true
    }

    /// Adds a copy of light `index` beside it; false when there are as many as can be.
    pub fn duplicate_model_light(&self, index: u32) -> bool {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return false };
        let Some(light) = scene.lights.get(index as usize).copied() else { return false };
        if scene.lights.len() >= MOST_LIGHTS {
            return false;
        }
        let [dx, dy, dz] = DUPLICATE_OFFSET;
        scene.lights.push(ModelLight { x: light.x + dx, y: light.y + dy, z: light.z + dz, ..light });
        self.shared.frame(&mut st);
        true
    }

    pub fn delete_model_light(&self, index: u32) {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return };
        if (index as usize) < scene.lights.len() {
            scene.lights.remove(index as usize);
        }
        self.shared.frame(&mut st);
    }

    /// The channel painted on: 0 colour, 1 roughness, 2 metalness.
    pub fn model_channel(&self) -> u32 {
        self.shared.lock().scene.as_ref().map_or(0, |s| u32::try_from(s.channel).unwrap_or(0))
    }

    /// Paints on `channel` of the current texture set from now on (0 colour, 1 roughness, 2
    /// metalness): its layers are the colour's layers, each with its own pixels.
    pub fn set_model_channel(&self, channel: u32) {
        let mut st = self.shared.lock();
        st.choose_channel(channel as usize);
        self.shared.frame(&mut st);
    }

    /// Whether a channel's tile is chosen under the current layer (Color, Roughness or Metallic,
    /// shown blue), not the layer's own row.
    pub fn channel_tile_chosen(&self) -> bool {
        self.shared.lock().scene.as_ref().is_some_and(|s| s.tile_chosen)
    }

    /// Chooses `channel`'s tile under the current layer (0 Color, 1 Roughness, 2 Metallic), its
    /// channel painted on from now on: an undo step, which chooses again what was before.
    pub fn choose_channel_tile(&self, channel: u32) {
        let mut st = self.shared.lock();
        st.choose_tile(Some(channel as usize));
        self.shared.frame(&mut st);
    }

    /// Chooses the current layer's own row again (its colour painted, no tile chosen), as an undo
    /// step.
    pub fn leave_channel_tiles(&self) {
        let mut st = self.shared.lock();
        st.choose_tile(None);
        self.shared.frame(&mut st);
    }

    /// Layer `id`'s picture in `channel` of the current texture set (as layer thumbnails are),
    /// for the channel tiles; none until it is drawn.
    pub fn channel_layer_thumbnail(
        &self,
        channel: u32,
        id: u64,
        max_side: u32,
    ) -> Option<crate::layers::ThumbnailImage> {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let channel = channel as usize;
        let current = st.scene.as_ref().map(|s| s.channel)?;
        if channel == current {
            drop(guard);
            return self.layer_thumbnail(id, max_side);
        }
        let gpu = st.gpu.as_ref()?;
        let scene = st.scene.as_mut()?;
        let stroke = scene.stroke(gpu, channel != 0);
        let set = scene.slot(scene.current, channel)?.as_mut()?;
        set_canvas(gpu, set, &stroke).ok()?;
        let TextureSet { canvas: Some(canvas), doc, .. } = set else { return None };
        let t = canvas.layer_thumbnail(gpu, doc, LayerId(id), max_side).ok()?;
        Some(crate::layers::ThumbnailImage { width: t.width, height: t.height, rgba: t.rgba })
    }

    /// The images the model brings for its texture sets (a USDZ's packed images, or those
    /// picked or zipped with an OBJ or USD that its materials map): to be decoded and given back
    /// with [`CanvasSession::set_model_texture`]. Its normal and occlusion maps come as channels
    /// 3 and 4. Picked images are given once.
    pub fn model_texture_files(&self) -> Vec<ModelTextureFile> {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return Vec::new() };
        let picked = std::mem::take(&mut scene.picked);
        let zip = (scene.source.extension == "usdz")
            .then(|| procraft_engine::zip::ZipReader::new(&scene.source.bytes))
            .flatten();
        let found = |path: &str| match &zip {
            Some(zip) => packed_file(zip, path),
            None => companion(&picked, path).map(|f| f.bytes.clone()),
        };
        let mut out = Vec::new();
        for (set, part) in scene.mesh.parts.iter().enumerate() {
            let Some(m) = scene.mesh.materials.iter().find(|m| m.name == part.material) else { continue };
            let maps = [
                (0, m.color_map.as_deref(), None),
                (1, m.roughness_map.as_deref(), m.roughness_component),
                (2, m.metallic_map.as_deref(), m.metallic_component),
                (3, m.normal_map.as_deref(), None),
                (4, m.occlusion_map.as_deref(), m.occlusion_component),
            ];
            for (channel, map, component) in maps {
                let Some(bytes) = map.and_then(found) else { continue };
                out.push(ModelTextureFile {
                    texture_set: u32::try_from(set).unwrap_or(0),
                    channel,
                    component: component.and_then(|c| i32::try_from(c).ok()).unwrap_or(-1),
                    bytes,
                });
            }
        }
        out
    }

    /// The images the model brings ([`CanvasSession::model_texture_files`]) decoded (PNG or
    /// JPEG) and scaled to the texture sets' side into them: colour as it is, roughness,
    /// metalness and occlusion from the component they name, as grey. An image that can't be read
    /// is left out, the rest still come in. How many came in.
    pub fn load_model_textures(&self) -> u32 {
        let Some(side) = self.model_info().map(|m| m.texture_size) else { return 0 };
        let mut loaded = 0;
        for f in self.model_texture_files() {
            let Ok(decoded) = image::load_from_memory(&f.bytes) else { continue };
            let picture = decoded.to_rgba8();
            let mut picture = if picture.dimensions() == (side, side) {
                picture
            } else {
                image::imageops::resize(&picture, side, side, image::imageops::FilterType::Triangle)
            };
            for px in picture.pixels_mut() {
                let [r, g, b, a] = px.0;
                px.0 = match usize::try_from(f.component).ok().and_then(|c| px.0.get(c).copied()) {
                    // one component, as grey
                    Some(v) => [v, v, v, 255],
                    // premultiplied
                    None => {
                        let k = u16::from(a);
                        let mul = |c: u8| ((u16::from(c) * k + 127) / 255) as u8;
                        [mul(r), mul(g), mul(b), a]
                    }
                };
            }
            if self.set_model_texture(f.texture_set, f.channel, picture.into_raw()) {
                loaded += 1;
            }
        }
        loaded
    }

    /// Puts `rgba` (premultiplied, the texture size square) into texture set `texture_set`'s
    /// Base Layer in `channel` (0 colour, 1 roughness, 2 metalness), with no undo step; or, as
    /// channel 3 or 4, makes it the set's normal or occlusion map. False if it doesn't fit.
    pub fn set_model_texture(&self, texture_set: u32, channel: u32, rgba: Vec<u8>) -> bool {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let (set, channel) = (texture_set as usize, channel as usize);
        let Some(scene) = st.scene.as_mut() else { return false };
        let side = scene.texture_size;
        if channel > 4 || set >= scene.mesh.parts.len() || rgba.len() != (side as usize) * (side as usize) * 4 {
            return false;
        }
        // the maps shown as they are
        if channel >= 3 {
            scene.surface_maps.resize(scene.mesh.parts.len(), [None, None]);
            if let Some(slot) = scene.surface_maps.get_mut(set).and_then(|m| m.get_mut(channel - 3)) {
                *slot = Some(Arc::new(rgba));
            }
            scene.surface_shown = false;
            self.shared.frame(st);
            return true;
        }
        // the colour's layers say which is the Base Layer
        let colour_is_session = set == scene.current && scene.channel == 0;
        let structure = if colour_is_session {
            st.doc.clone()
        } else {
            match scene.sets.get(set).and_then(Option::as_ref) {
                Some(s) => s.doc.clone(),
                None => return false,
            }
        };
        let Some(base) = structure.layers().first().map(|l| l.id) else { return false };
        if set == scene.current && channel == scene.channel {
            match st.canvas.as_mut() {
                Some(canvas) => canvas.put_layer(base, rgba),
                None => {
                    st.pending_pixels.retain(|(id, _)| *id != base);
                    st.pending_pixels.push((base, crate::project::Opened::Pixels(rgba)));
                }
            }
        } else {
            let Some(slot) = scene.slot(set, channel) else { return false };
            let target = slot.get_or_insert_with(|| {
                let mut made = TextureSet::empty(side);
                follow_layers(&mut made, &structure);
                made
            });
            match target.canvas.as_mut() {
                Some(canvas) => canvas.put_layer(base, rgba),
                None => {
                    target.pending.retain(|(id, _)| *id != base);
                    target.pending.push((base, rgba));
                }
            }
        }
        self.shared.frame(st);
        true
    }

    /// Which of the model's submeshes are hidden, in order (themselves, not by their texture set).
    pub fn submeshes_hidden(&self) -> Vec<bool> {
        self.look_flags(|l| &l.hidden, |m| m.submeshes.len())
    }

    /// Hides submesh `index` (not drawn or painted), or shows it again: an undo step.
    pub fn set_submesh_hidden(&self, index: u32, hidden: bool) {
        let name = if hidden { "Hide Submesh" } else { "Show Submesh" };
        self.change_look(name, |look, mesh| set_flag(&mut look.hidden, mesh.submeshes.len(), index, hidden));
    }

    /// Which of the model's submeshes are locked (themselves, not by their texture set).
    pub fn submeshes_locked(&self) -> Vec<bool> {
        self.look_flags(|l| &l.locked, |m| m.submeshes.len())
    }

    /// Locks submesh `index` (drawn, but taking no paint), or unlocks it: an undo step.
    pub fn set_submesh_locked(&self, index: u32, locked: bool) {
        let name = if locked { "Lock mesh" } else { "Unlock mesh" };
        self.change_look(name, |look, mesh| set_flag(&mut look.locked, mesh.submeshes.len(), index, locked));
    }

    /// Which texture sets are hidden.
    pub fn texture_sets_hidden(&self) -> Vec<bool> {
        self.look_flags(|l| &l.sets_hidden, |m| m.parts.len())
    }

    /// Hides texture set `index` with all its meshes, or shows it again: an undo step.
    pub fn set_texture_set_hidden(&self, index: u32, hidden: bool) {
        let name = if hidden { "Hide Texture Set" } else { "Show Texture Set" };
        self.change_look(name, |look, mesh| set_flag(&mut look.sets_hidden, mesh.parts.len(), index, hidden));
    }

    /// Which texture sets are locked.
    pub fn texture_sets_locked(&self) -> Vec<bool> {
        self.look_flags(|l| &l.sets_locked, |m| m.parts.len())
    }

    /// Locks texture set `index` with all its meshes, or unlocks it: an undo step.
    pub fn set_texture_set_locked(&self, index: u32, locked: bool) {
        let name = if locked { "Lock Texture Set" } else { "Unlock Texture Set" };
        self.change_look(name, |look, mesh| set_flag(&mut look.sets_locked, mesh.parts.len(), index, locked));
    }

    /// The meshes picked, which alone take strokes (none: the whole texture set does).
    pub fn active_submeshes(&self) -> Vec<u32> {
        let st = self.shared.lock();
        st.scene.as_ref().map_or_else(Vec::new, |s| s.look.active.iter().map(|&i| i as u32).collect())
    }

    /// Picks mesh `index` alone, its texture set the one painted on.
    pub fn pick_submesh(&self, index: u32) {
        let index = index as usize;
        let set = {
            let st = self.shared.lock();
            let Some(scene) = st.scene.as_ref() else { return };
            let Some(set) = scene.set_of(index) else { return };
            if scene.look.active == [index] && scene.current == set {
                return;
            }
            set
        };
        self.choose_texture_set(set as u32);
        self.change_look(MESHES_SELECTED, |look, _| look.active = vec![index]);
    }

    /// Adds mesh `index` to those picked, or takes it away (the last one stays). False, with
    /// nothing changed, for a mesh of a texture set other than the one painted on: meshes picked
    /// together share a texture set.
    pub fn pick_more_submeshes(&self, index: u32) -> bool {
        let index = index as usize;
        {
            let st = self.shared.lock();
            let Some(scene) = st.scene.as_ref() else { return false };
            if scene.set_of(index) != Some(scene.current) {
                return false;
            }
        }
        self.change_look(MESHES_SELECTED, |look, _| {
            if !look.active.contains(&index) {
                look.active.push(index);
            } else if look.active.len() > 1 {
                look.active.retain(|&i| i != index);
            }
        });
        true
    }

    /// The mesh shown under window point (`x`, `y`), if there is one.
    pub fn submesh_at(&self, x: f32, y: f32) -> Option<u32> {
        let st = self.shared.lock();
        let hit = st.scene.as_ref()?.surface_at(x, y, st.window_size)?;
        hit.submesh.map(|i| i as u32)
    }

    /// Picks the mesh under window point (`x`, `y`), its texture set the one painted on, and says
    /// what is painted on now. None off the model.
    pub fn pick_submesh_at(&self, x: f32, y: f32) -> Option<String> {
        let index = self.submesh_at(x, y)?;
        self.pick_submesh(index);
        let st = self.shared.lock();
        let mesh = st.scene.as_ref()?.mesh.submeshes.get(index as usize)?.name.clone();
        let layer = st.doc.layer(st.doc.current())?.name.clone();
        Some(format!("{layer} on {mesh}"))
    }

    /// Shows and unlocks the meshes strokes are meant for, and their texture set: what the
    /// dialog over a stroke that couldn't land offers.
    pub fn free_meant_meshes(&self) {
        let meant = {
            let st = self.shared.lock();
            let Some(scene) = st.scene.as_ref() else { return };
            scene.meant_meshes()
        };
        self.change_look("Show Submesh", |look, mesh| {
            look.hidden.resize(mesh.submeshes.len(), false);
            look.locked.resize(mesh.submeshes.len(), false);
            for i in meant {
                if let Some(h) = look.hidden.get_mut(i) {
                    *h = false;
                }
                if let Some(l) = look.locked.get_mut(i) {
                    *l = false;
                }
                let set = mesh.submeshes.get(i).map(|m| m.part).unwrap_or(0);
                if let Some(h) = look.sets_hidden.get_mut(set) {
                    *h = false;
                }
                if let Some(l) = look.sets_locked.get_mut(set) {
                    *l = false;
                }
            }
        });
    }

    /// The name of the first of the meshes strokes are meant for, for a dialog about them.
    pub fn meant_mesh_name(&self) -> Option<String> {
        let st = self.shared.lock();
        let scene = st.scene.as_ref()?;
        let first = *scene.meant_meshes().first()?;
        Some(scene.mesh.submeshes.get(first)?.name.clone())
    }

    /// What a model newly opened from its file warns of, once.
    pub fn take_model_warnings(&self) -> Vec<ModelWarning> {
        let mut st = self.shared.lock();
        st.scene.as_mut().map_or_else(Vec::new, |s| std::mem::take(&mut s.warnings))
    }

    /// Submesh `index` on its own, seen as the model is now, filling a `side` square on nothing:
    /// its row's picture. None until the model has been drawn.
    pub fn submesh_thumbnail(&self, index: u32, side: u32) -> Option<crate::layers::ThumbnailImage> {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let side = side.clamp(8, 1024);
        let (Some(gpu), Some(view)) = (st.gpu.as_ref(), st.scene.as_mut().and_then(|s| s.view.as_mut())) else {
            return None;
        };
        match view.submesh_picture(gpu, index as usize, side) {
            Ok(rgba) => Some(crate::layers::ThumbnailImage { width: side, height: side, rgba }),
            Err(e) => {
                log::warn!("submesh picture: {e}");
                None
            }
        }
    }

    /// Shows the Lighting Studio (the model on its floor among its lights) or the model again.
    pub fn set_lighting_studio(&self, open: bool) {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return };
        scene.studio_open = open;
        self.shared.frame(&mut st);
    }

    /// A drag of (`dx`, `dy`) window pixels across the studio: its camera goes round the model
    /// (across) and up or down (along), the model upright.
    pub fn studio_turn(&self, dx: f32, dy: f32) {
        let mut st = self.shared.lock();
        let side = st.window_size.0.min(st.window_size.1).max(1) as f32;
        let Some(scene) = st.scene.as_mut() else { return };
        let Some(view) = scene.view.as_ref() else { return };
        let orbit = scene.studio.get_or_insert_with(|| view.studio_orbit());
        let (yaw, pitch) = yaw_pitch(orbit);
        let yaw = yaw - dx / side * STUDIO_TURN_PER_SIDE;
        let pitch = (pitch + dy / side * STUDIO_TURN_PER_SIDE).clamp(STUDIO_LOWEST, STUDIO_HIGHEST);
        orbit.rotation = Orbit::rotation_of(yaw, pitch);
        self.shared.frame(&mut st);
    }

    /// Two fingers pinching the studio by `scale`: its camera nearer or farther.
    pub fn studio_zoom(&self, scale: f32) {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return };
        let unit = Orbit::unit(&scene.mesh);
        let Some(view) = scene.view.as_ref() else { return };
        let orbit = scene.studio.get_or_insert_with(|| view.studio_orbit());
        if scale.is_finite() && scale > 0.0 {
            orbit.distance = (orbit.distance / scale).clamp(NEAREST * unit * 20.0, FARTHEST * unit * 2.0);
        }
        self.shared.frame(&mut st);
    }

    /// The light whose cube is under window point (`x`, `y`) in the studio, the nearest if more.
    pub fn model_light_at(&self, x: f32, y: f32) -> Option<u32> {
        let st = self.shared.lock();
        let (w, h) = st.window_size;
        let scene = st.scene.as_ref()?;
        let view = scene.view.as_ref()?;
        let orbit = scene.studio.unwrap_or_else(|| view.studio_orbit());
        let gpu_view = scene.lights_view(view);
        (0..gpu_view.lights.len())
            .filter_map(|i| {
                let [lx, ly, reach] = gpu_view.light_on_screen(&orbit, i, w, h)?;
                let d = ((lx - x).powi(2) + (ly - y).powi(2)).sqrt();
                (d <= reach + LIGHT_SLACK).then_some((i, d))
            })
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .and_then(|(i, _)| u32::try_from(i).ok())
    }

    /// Light `index` in the studio: its middle on the window and how far its cube reaches (pixels).
    pub fn model_light_on_screen(&self, index: u32) -> Option<Vec<f32>> {
        let st = self.shared.lock();
        let (w, h) = st.window_size;
        let scene = st.scene.as_ref()?;
        let view = scene.view.as_ref()?;
        let orbit = scene.studio.unwrap_or_else(|| view.studio_orbit());
        scene.lights_view(view).light_on_screen(&orbit, index as usize, w, h).map(|p| p.to_vec())
    }

    /// Drags light `index` by (`dx`, `dy`) window pixels across the studio, at its depth.
    pub fn move_model_light(&self, index: u32, dx: f32, dy: f32) {
        let mut st = self.shared.lock();
        let (w, h) = st.window_size;
        let Some(scene) = st.scene.as_mut() else { return };
        let Some(view) = scene.view.as_ref() else { return };
        let orbit = scene.studio.unwrap_or_else(|| view.studio_orbit());
        let mut moved = scene.lights_view(view);
        moved.move_light(&orbit, index as usize, dx, dy, w, h);
        if let (Some(light), Some(m)) = (scene.lights.get_mut(index as usize), moved.lights.get(index as usize)) {
            [light.x, light.y, light.z] = m.place;
        }
        self.shared.frame(&mut st);
    }

    /// Whether the environment shows behind the model (else the canvas's surround).
    pub fn model_environment_shown(&self) -> bool {
        self.shared.lock().scene.as_ref().is_none_or(|s| s.environment_shown)
    }

    pub fn set_model_environment_shown(&self, shown: bool) {
        let mut st = self.shared.lock();
        let Some(scene) = st.scene.as_mut() else { return };
        scene.environment_shown = shown;
        self.shared.frame(&mut st);
    }

    pub fn set_paint_through(&self, on: bool) {
        if let Some(scene) = self.shared.lock().scene.as_mut() {
            scene.paint_through = on;
        }
    }

    /// Whether the texture set painted on is shown flat (its 2D texture) in place of the model.
    pub fn model_flat(&self) -> bool {
        self.shared.lock().scene.as_ref().is_some_and(|s| s.flat)
    }

    /// Shows the texture set painted on flat, a canvas drawn and painted on as any other, or the
    /// model again.
    pub fn set_model_flat(&self, on: bool) {
        let mut st = self.shared.lock();
        let Some(flat) = st.scene.as_ref().map(|s| s.flat) else { return };
        if flat == on {
            return;
        }
        // the stroke under way ends where it was drawn
        if flat {
            st.finish_stroke();
        } else {
            st.scene_stroke_cancel();
        }
        if let Some(scene) = st.scene.as_mut() {
            scene.flat = on;
        }
        if on {
            st.view = Some(st.fitted_view());
        }
        self.shared.frame(&mut st);
    }

    /// One finger dragged by (`dx`, `dy`) window pixels across a model: it turns.
    pub fn turn_model(&self, dx: f32, dy: f32) {
        let mut st = self.shared.lock();
        st.scene_turn(dx, dy);
        self.shared.frame(&mut st);
    }
}

#[uniffi::export]
impl CanvasSession {
    /// Writes the model as OBJ into a zip at `path`: the model, its material file, and each
    /// texture set's picture as a PNG (over the part's base colour, white), named after the
    /// artwork.
    pub fn export_model_obj(&self, path: String) -> Result<(), MobileError> {
        let files = self.model_files()?;
        let base = &files.base;
        let materials: Vec<(&str, &PartImages)> = files.names.iter().map(String::as_str).zip(&files.images).collect();
        let mut zip = procraft_engine::zip::ZipWriter::new();
        zip.add(
            &format!("{base}.obj"),
            procraft_engine::mesh::write_obj(&files.mesh, &format!("{base}.mtl"), &files.names).as_bytes(),
        );
        zip.add(&format!("{base}.mtl"), procraft_engine::mesh::write_mtl(&materials).as_bytes());
        for (file, png) in files.images.iter().flat_map(PartImages::files) {
            zip.add(file, png);
        }
        let bytes = zip.finish();
        write_atomically(Path::new(&path), |p| fs::write(p, &bytes).map_err(|e| file_error("exporting the model", e)))
    }

    /// The artwork, saved to `dir` first, as one file at `path` that comes back whole in another
    /// copy of the app: its folder zipped, the model, every texture set's layers and how it was
    /// seen.
    pub fn export_model_artwork(&self, dir: String, path: String) -> Result<(), MobileError> {
        self.save(dir.clone())?;
        let root = Path::new(&dir);
        let mut zip = procraft_engine::zip::ZipWriter::new();
        zip_folder(&mut zip, root, root).map_err(|e| file_error("saving", e))?;
        let bytes = zip.finish();
        write_atomically(Path::new(&path), |p| fs::write(p, &bytes).map_err(|e| file_error("saving", e)))
    }

    /// Writes the model as a glTF binary at `path`, for a viewer that shows it in the room: as
    /// for [`CanvasSession::export_model_usdz`], each set's roughness and metalness in one image
    /// (green and blue).
    pub fn export_model_glb(&self, path: String) -> Result<(), MobileError> {
        let ModelPixels { mesh, names, pixels, maps, side, .. } = self.model_pixels()?;
        let png = |px: &[u8]| crate::project::rgba_png(side, side, px);
        let encoded: Vec<[Vec<u8>; 4]> = pixels
            .iter()
            .enumerate()
            .map(|(part, [colour, roughness, metallic])| {
                let mut both = roughness.clone();
                for (p, m) in both.as_chunks_mut::<4>().0.iter_mut().zip(metallic.as_chunks::<4>().0) {
                    *p = [255, p[1], m[2], 255];
                }
                let [normal, occlusion] = maps.get(part).cloned().unwrap_or_default();
                let map = |m: Option<Arc<Vec<u8>>>| m.map_or_else(Vec::new, |px| png(&px));
                [png(colour), png(&both), map(normal), map(occlusion)]
            })
            .collect();
        let parts: Vec<procraft_engine::gltf::GlbPart<'_>> = names
            .iter()
            .zip(&encoded)
            .map(|(name, [colour, both, normal, occlusion])| procraft_engine::gltf::GlbPart {
                name,
                color: colour,
                metallic_roughness: both,
                normal: (!normal.is_empty()).then_some(normal.as_slice()),
                occlusion: (!occlusion.is_empty()).then_some(occlusion.as_slice()),
            })
            .collect();
        let bytes = procraft_engine::gltf::write_glb(&mesh, &parts);
        write_atomically(Path::new(&path), |p| fs::write(p, &bytes).map_err(|e| file_error("exporting the model", e)))
    }

    /// Writes the model as a USDZ package at `path`: the mesh with a preview-surface material
    /// for each texture set, reading its images (as for [`CanvasSession::export_model_obj`]).
    pub fn export_model_usdz(&self, path: String) -> Result<(), MobileError> {
        let files = self.model_files()?;
        let bytes = procraft_engine::usd::write_usdz(&files.mesh, &files.images);
        write_atomically(Path::new(&path), |p| fs::write(p, &bytes).map_err(|e| file_error("exporting the model", e)))
    }
}

#[uniffi::export]
impl CanvasSession {
    /// Each texture set's colour, roughness and metalness (and its normal and occlusion maps, if
    /// it has them) as PNGs in folder `dir` (made if missing; as for
    /// [`CanvasSession::export_model_obj`]); returns the files' paths.
    pub fn export_model_textures(&self, dir: String) -> Result<Vec<String>, MobileError> {
        let files = self.model_files()?;
        let dir = Path::new(&dir);
        fs::create_dir_all(dir).map_err(|e| file_error("making the folder", e))?;
        let mut out = Vec::new();
        for (name, images) in files.names.iter().zip(&files.images) {
            let [colour, roughness, metallic] = images.maps.each_ref().map(|(_, png)| png);
            let named = [("Color", colour), ("Roughness", roughness), ("Metallic", metallic)]
                .into_iter()
                .chain(images.normal.iter().map(|(_, png)| ("Normal", png)))
                .chain(images.occlusion.iter().map(|(_, png)| ("AmbientOcclusion", png)));
            for (channel, png) in named {
                let path = dir.join(format!("{name}-{channel}.png"));
                write_atomically(&path, |p| fs::write(p, png).map_err(|e| file_error("exporting the textures", e)))?;
                out.push(path.to_string_lossy().into_owned());
            }
        }
        Ok(out)
    }

    /// The model as the window shows it, at the window's size, as a PNG at `path`: on the
    /// canvas's surround, or with `transparent`, on nothing.
    pub fn export_model_render(&self, path: String, transparent: bool) -> Result<(), MobileError> {
        let (w, h, rgba) = self.model_picture(transparent)?;
        let png = crate::project::rgba_png(w, h, &rgba);
        write_atomically(Path::new(&path), |p| fs::write(p, &png).map_err(|e| file_error("rendering the model", e)))
    }

    /// The model as the window shows it, on the canvas's surround, as a JPEG at `path`.
    pub fn export_model_jpeg(&self, path: String) -> Result<(), MobileError> {
        let (w, h, rgba) = self.model_picture(false)?;
        let rgb = crate::project::onto_white(&crate::project::straight_alpha(&rgba));
        let bytes = procraft_engine::jpeg::encode(&rgb, w, h, crate::project::JPEG_QUALITY);
        write_atomically(Path::new(&path), |p| fs::write(p, &bytes).map_err(|e| file_error("rendering the model", e)))
    }

    /// The model as the window shows it, on the canvas's surround, as a TIFF at `path`.
    pub fn export_model_tiff(&self, path: String) -> Result<(), MobileError> {
        let (w, h, rgba) = self.model_picture(false)?;
        let bytes = procraft_engine::formats::tiff(w, h, 72.0, &[], &rgba);
        write_atomically(Path::new(&path), |p| fs::write(p, &bytes).map_err(|e| file_error("rendering the model", e)))
    }
}

impl CanvasSession {
    /// The model as the window shows it, at the window's size: its width, height and pixels
    /// (premultiplied RGBA8), on the canvas's surround or, `transparent`, on nothing.
    fn model_picture(&self, transparent: bool) -> Result<(u32, u32, Vec<u8>), MobileError> {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        st.finish_stroke();
        st.settle();
        let (w, h) = st.window_size;
        let (Some(gpu), Some(view)) = (st.gpu.as_ref(), st.scene.as_mut().and_then(|s| s.view.as_mut())) else {
            return Err(file_error("rendering the model", "the model is not shown yet"));
        };
        let s = crate::SURROUND;
        let clear = if transparent { [0.0; 4] } else { [f64::from(s[0]), f64::from(s[1]), f64::from(s[2]), 1.0] };
        let rgba = view.picture(gpu, w, h, clear).map_err(|e| file_error("rendering the model", e))?;
        Ok((w, h, rgba))
    }
}

/// How far a turntable's swing goes each way (radians).
const SWING: f32 = std::f32::consts::PI / 6.0;
/// The turntable's camera: this far above level (radians) and back (framing units), the model
/// about four fifths of the picture's height; at its farthest it is this much further back for
/// each share of Zoom distance (the model then about 1.78 times smaller at 100 %).
const TURNTABLE_PITCH: f32 = 0.1;
const TURNTABLE_DISTANCE: f32 = 6.5;
const TURNTABLE_PULL: f32 = 0.65;

/// How a model's turntable goes: once round (or swinging each way and back) every `seconds`,
/// the camera pulled back by up to `zoom` (0 to 1) half way through, the turn eased in and out at
/// the loop by `ease` (0 to 1), the environment behind it or not.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct Turntable {
    pub swing: bool,
    pub seconds: f32,
    pub zoom: f32,
    pub ease: f32,
    pub environment: bool,
}

impl Turntable {
    /// The camera at `phase` (0 to 1) through the loop.
    fn orbit(&self, view: &MeshView, phase: f32) -> Orbit {
        let tau = std::f32::consts::TAU;
        let p = phase.rem_euclid(1.0);
        let ease = self.ease.clamp(0.0, 1.0);
        // slowed to a stop at the loop's ends by the ease
        let eased = p - ease * (tau * p).sin() / tau;
        let yaw = if self.swing { SWING * (tau * eased).sin() } else { tau * eased };
        let pull = (1.0 - (tau * p).cos()) / 2.0;
        let distance = TURNTABLE_DISTANCE * (1.0 + TURNTABLE_PULL * self.zoom.clamp(0.0, 1.0) * pull);
        view.upright_orbit(yaw, TURNTABLE_PITCH, distance)
    }
}

#[uniffi::export]
impl CanvasSession {
    /// Frame `index` of `count` of the model's turntable as `turntable` sets it, at `width` ×
    /// `height`: RGBA8, or with `bgra` opaque blue, green, red, alpha (an ARGB integer each,
    /// little-endian, ready for a video frame). None if the model isn't shown yet.
    pub fn model_turntable_picture(
        &self,
        index: u32,
        count: u32,
        turntable: Turntable,
        width: u32,
        height: u32,
        bgra: bool,
    ) -> Option<crate::layers::ThumbnailImage> {
        let mut guard = self.shared.lock();
        let st = &mut *guard;
        let (w, h) = (width.max(1), height.max(1));
        let (Some(gpu), Some(view)) = (st.gpu.as_ref(), st.scene.as_mut().and_then(|s| s.view.as_mut())) else {
            return None;
        };
        let orbit = turntable.orbit(view, index as f32 / count.max(1) as f32);
        let s = crate::SURROUND;
        let clear = [f64::from(s[0]), f64::from(s[1]), f64::from(s[2]), 1.0];
        match view.picture_from(gpu, orbit, w, h, clear, turntable.environment) {
            Ok(mut rgba) => {
                if bgra {
                    for p in rgba.as_chunks_mut::<4>().0 {
                        *p = [p[2], p[1], p[0], 255];
                    }
                }
                Some(crate::layers::ThumbnailImage { width: w, height: h, rgba })
            }
            Err(e) => {
                log::warn!("turntable picture: {e}");
                None
            }
        }
    }

    /// The model's turntable as a looping GIF at `path`: `fps` frames a second for its
    /// `turntable.seconds`, `width` × `height`.
    pub fn export_model_gif(
        &self,
        path: String,
        turntable: Turntable,
        width: u32,
        height: u32,
        fps: f32,
    ) -> Result<(), MobileError> {
        let count = turntable_frames(turntable, fps);
        let delay = (100.0 / fps.clamp(1.0, 100.0)).round() as u16;
        let mut gif: Option<procraft_engine::formats::GifWriter> = None;
        // the frames made take it to 0.95, the file written to 1
        self.exported(0.0);
        for i in 0..count {
            let t = self
                .model_turntable_picture(i, count, turntable, width, height, false)
                .ok_or_else(|| file_error("saving", "the model is not shown yet"))?;
            gif.get_or_insert_with(|| procraft_engine::formats::GifWriter::new(t.width, t.height)).frame(
                &t.rgba,
                delay.max(1),
                true,
            );
            self.exported(0.95 * (i + 1) as f32 / count as f32);
        }
        let bytes = gif.ok_or_else(|| file_error("saving", "there are no frames"))?.finish();
        write_atomically(Path::new(&path), |f| fs::write(f, &bytes).map_err(|e| file_error("saving", e)))?;
        self.exported(1.0);
        Ok(())
    }

    /// The model's turntable as a looping animated PNG at `path` (as for GIF).
    pub fn export_model_apng(
        &self,
        path: String,
        turntable: Turntable,
        width: u32,
        height: u32,
        fps: f32,
    ) -> Result<(), MobileError> {
        let count = turntable_frames(turntable, fps);
        let delay = (1000.0 / fps.clamp(1.0, 1000.0)).round().clamp(1.0, 65535.0) as u16;
        let (w, h) = (width.max(1), height.max(1));
        self.exported(0.0);
        write_atomically(Path::new(&path), |f| {
            let out = fs::File::create(f).map_err(|e| file_error("saving", e))?;
            let mut encoder = png::Encoder::new(std::io::BufWriter::new(out), w, h);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            encoder.set_compression(png::Compression::Fast);
            encoder.set_animated(count, 0).map_err(|e| file_error("saving", e))?;
            encoder.set_frame_delay(delay, 1000).map_err(|e| file_error("saving", e))?;
            let mut writer = encoder.write_header().map_err(|e| file_error("saving", e))?;
            for i in 0..count {
                let t = self
                    .model_turntable_picture(i, count, turntable, w, h, false)
                    .ok_or_else(|| file_error("saving", "the model is not shown yet"))?;
                writer.write_image_data(&t.rgba).map_err(|e| file_error("saving", e))?;
                self.exported(0.95 * (i + 1) as f32 / count as f32);
            }
            writer.finish().map_err(|e| file_error("saving", e))
        })?;
        self.exported(1.0);
        Ok(())
    }
}

/// How many frames a turntable takes at `fps`: its seconds' worth, at least one.
fn turntable_frames(turntable: Turntable, fps: f32) -> u32 {
    (turntable.seconds.clamp(0.1, 60.0) * fps.clamp(1.0, 120.0)).round().max(1.0) as u32
}

/// What a model's exports are made of: the model, its texture sets' names (safe as file names,
/// different from each other), each set's images as PNGs (its colour, roughness and metalness
/// over the part's own, as the model shows them, and its normal and occlusion maps) named as
/// models name them, and the artwork's name.
struct ModelFiles {
    mesh: Mesh,
    names: Vec<String>,
    images: Vec<PartImages>,
    base: String,
}

/// The images as a model's exports name them (`<set>-<channel>.png`).
const MODEL_CHANNEL_FILES: [&str; 3] = ["color", "roughness", "metallic"];
const MODEL_NORMAL_FILE: &str = "normal";
const MODEL_OCCLUSION_FILE: &str = "ambient_occlusion";

impl CanvasSession {
    /// The model's texture sets' pixels as exports take them: each set's colour, roughness and
    /// metalness over the part's own (RGBA8, the texture size square), with its maps and the
    /// sets' names safe as file names.
    fn model_pixels(&self) -> Result<ModelPixels, MobileError> {
        let (mesh, set_names, pictures, maps, side, base) = {
            let mut guard = self.shared.lock();
            let st = &mut *guard;
            st.finish_stroke();
            st.settle();
            let pictures = st.channel_pictures()?;
            let Some(scene) = st.scene.as_ref() else { return Err(file_error("exporting the model", "no model")) };
            let maps = scene.surface_maps.clone();
            (scene.mesh.clone(), scene.set_names(), pictures, maps, scene.texture_size, file_stem(&st.name, "Model"))
        };
        let mut names: Vec<String> = Vec::new();
        for name in set_names {
            let mut n = file_stem(&name, "Material");
            if names.contains(&n) {
                n = format!("{n}_{}", names.len() + 1);
            }
            names.push(n);
        }
        let pixels = pictures
            .into_iter()
            .enumerate()
            .map(|(part, [color, roughness, metallic])| {
                let (under, r, m) = mesh.surface(part);
                [
                    flattened(side, color, under, false),
                    flattened(side, roughness, [r; 3], true),
                    flattened(side, metallic, [m; 3], true),
                ]
            })
            .collect();
        Ok(ModelPixels { mesh, names, pixels, maps, side, base })
    }

    fn model_files(&self) -> Result<ModelFiles, MobileError> {
        let ModelPixels { mesh, names, pixels, maps, side, base } = self.model_pixels()?;
        let png = |px: &[u8]| crate::project::rgba_png(side, side, px);
        let images = pixels
            .iter()
            .zip(&names)
            .enumerate()
            .map(|(part, ([cp, rp, mp], n))| {
                let [c, r, m] = MODEL_CHANNEL_FILES.map(|k| format!("{n}-{k}.png"));
                let [normal, occlusion] = maps.get(part).cloned().unwrap_or_default();
                let map = |rgba: Option<Arc<Vec<u8>>>, k: &str| rgba.map(|px| (format!("{n}-{k}.png"), png(&px)));
                PartImages {
                    maps: [(c, png(cp)), (r, png(rp)), (m, png(mp))],
                    normal: map(normal, MODEL_NORMAL_FILE),
                    occlusion: map(occlusion, MODEL_OCCLUSION_FILE),
                }
            })
            .collect();
        Ok(ModelFiles { mesh, names, images, base })
    }
}

/// What [`CanvasSession::model_pixels`] gives.
struct ModelPixels {
    mesh: Mesh,
    names: Vec<String>,
    pixels: Vec<[Vec<u8>; 3]>,
    maps: Vec<[Option<Arc<Vec<u8>>>; 2]>,
    side: u32,
    base: String,
}

/// A channel's picture (premultiplied RGBA8, `side` square; none if never painted) over what the
/// part shows under it (`under`, 0 to 1), opaque: as it is, or as the grey the model reads from
/// it.
fn flattened(side: u32, picture: Option<Vec<u8>>, under: [f32; 3], grey: bool) -> Vec<u8> {
    let under = under.map(|v| (v.clamp(0.0, 1.0) * 255.0).round() as u16);
    let mut px = picture.unwrap_or_else(|| vec![0; (side as usize) * (side as usize) * 4]);
    let [ur, ug, ub] = under;
    for p in px.as_chunks_mut::<4>().0 {
        let [r, g, b, a] = *p;
        let clear = 255 - u16::from(a);
        let over = |c: u8, u: u16| (u16::from(c) + (u * clear + 127) / 255).min(255) as u8;
        let [r, g, b] = [over(r, ur), over(g, ug), over(b, ub)];
        *p = if grey {
            // the model's own weights for a painted grey
            let v = (0.2126 * f32::from(r) + 0.7152 * f32::from(g) + 0.0722 * f32::from(b)).round() as u8;
            [v, v, v, 255]
        } else {
            [r, g, b, 255]
        };
    }
    px
}

/// A file packed in a USDZ by the path its layer gives (relative to the package; `./` and folders
/// allowed for), else by its name alone.
fn packed_file(zip: &procraft_engine::zip::ZipReader<'_>, path: &str) -> Option<Vec<u8>> {
    zip.read(&packed_name(zip, path)?)
}

/// The entry [`packed_file`] reads for `path`, if there is one.
fn packed_name(zip: &procraft_engine::zip::ZipReader<'_>, path: &str) -> Option<String> {
    let path = path.trim_start_matches("./");
    if zip.names().any(|n| n == path) {
        return Some(path.to_owned());
    }
    let name = path.rsplit('/').next().unwrap_or(path);
    zip.names().find(|n| n.rsplit('/').next() == Some(name)).map(str::to_owned)
}

/// A name made safe as part of a file name: letters, digits, `-` and `_` kept, the rest `_`.
fn file_stem(name: &str, otherwise: &str) -> String {
    let kept: String =
        name.trim().chars().map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '_' }).collect();
    if kept.is_empty() { otherwise.to_owned() } else { kept }
}

impl State {
    /// Each texture set's colour, roughness and metalness as painted (premultiplied RGBA8, the
    /// texture size square), drawn now: none for a channel never painted.
    fn channel_pictures(&mut self) -> Result<Vec<[Option<Vec<u8>>; 3]>, MobileError> {
        let failed = |e: String| file_error("reading the model's textures", e);
        let gpu = self.gpu.as_ref().ok_or_else(|| failed("the model is not shown yet".to_owned()))?;
        let Some(scene) = self.scene.as_mut() else { return Ok(Vec::new()) };
        let (current, painted) = (scene.current, scene.channel);
        let mut out = Vec::new();
        for part in 0..scene.mesh.parts.len() {
            let mut pictures: [Option<Vec<u8>>; 3] = [None, None, None];
            for (channel, picture) in pictures.iter_mut().enumerate() {
                // the channel painted on is the session's; the others wait in the scene
                *picture = if part == current && channel == painted {
                    let canvas = self.canvas.as_mut().ok_or_else(|| failed("the model is not shown yet".to_owned()))?;
                    canvas.render_finished(gpu, &mut self.doc);
                    Some(canvas.read_composite(gpu).map_err(|e| failed(e.to_string()))?)
                } else {
                    let stroke = scene.stroke(gpu, channel != 0);
                    match scene.slot(part, channel).and_then(Option::as_mut) {
                        Some(set) => Some(waiting_picture(gpu, set, &stroke).map_err(failed)?),
                        None => None,
                    }
                };
            }
            out.push(pictures);
        }
        Ok(out)
    }
}

/// A waiting texture set's picture, drawn now (its canvas made first, drawing in `stroke`, if
/// the model hasn't been drawn since it was opened).
fn waiting_picture(gpu: &Gpu, set: &mut TextureSet, stroke: &SharedStroke) -> Result<Vec<u8>, String> {
    set_canvas(gpu, set, stroke)?;
    let TextureSet { canvas: Some(canvas), doc, .. } = set else { return Err("no canvas".to_owned()) };
    canvas.render_finished(gpu, doc);
    canvas.read_composite(gpu).map_err(|e| e.to_string())
}

/// Makes `set`'s canvas if it has none (with the pixels it waits with): one that takes the
/// strokes carried onto the model, drawing them in `stroke`, and goes without brush images.
fn set_canvas(gpu: &Gpu, set: &mut TextureSet, stroke: &SharedStroke) -> Result<(), String> {
    if set.canvas.is_none() {
        let mut c = Canvas::for_drawn_strokes(gpu, &set.doc, stroke).map_err(|e| e.to_string())?;
        for (id, pixels) in set.take_pending() {
            if let Err(e) = c.upload_layer(gpu, id, &pixels) {
                log::error!("opening a texture set's layer: {e}");
            }
        }
        set.canvas = Some(c);
    }
    Ok(())
}

/// A texture set's canvas left waiting while another is painted: its work done, it keeps no
/// pictures to composite around and lets go of its brush images.
fn to_waiting(gpu: &Gpu, set: &mut TextureSet) {
    if let Some(canvas) = set.canvas.as_mut() {
        canvas.render(gpu, &mut set.doc, None);
        canvas.set_resident_budget(0);
        canvas.let_go_of_brush_images(gpu);
    }
}

/// A texture set's canvas becoming the session's: paged as the session's canvases are, its brush
/// images made now so the first stroke doesn't wait for them.
fn to_session(gpu: &Gpu, canvas: &mut Canvas) {
    let (w, h) = canvas.size();
    canvas.set_resident_budget(crate::layers::canvas_budget(w, h));
    canvas.prepare_brush_images(gpu);
}

/// Why a model can't be painted on, in words.
fn mesh_error_text(e: &MeshError) -> String {
    match e {
        MeshError::NoFaces => "it has no faces".to_owned(),
        MeshError::PointCloud => "it is a point cloud, with no faces to paint".to_owned(),
        MeshError::NoTextureCoordinates => "it has no texture coordinates".to_owned(),
        MeshError::TooLarge => format!("it has more than {MAX_TRIANGLES} triangles"),
        MeshError::Malformed(line) => format!("line {line} can't be read"),
        MeshError::Unreadable => "it isn't a model file that can be read".to_owned(),
    }
}

/// A model kept in `artwork.json`: its texture sets but the current one (the artwork's own
/// document), and how it was last seen.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub(crate) struct SavedModel {
    texture_size: u32,
    current: usize,
    /// The other texture sets' documents, by part (none in the current one's place).
    sets: Vec<Option<Document>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    orbit: Option<SavedOrbit>,
    /// The model file's kind: it is `model.<this>`.
    #[serde(default = "obj")]
    extension: String,
    #[serde(default)]
    paint_through: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    environment: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    exposure: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    environment_shown: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    lights: Option<Vec<ModelLight>>,
    /// The submeshes hidden, by place.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    hidden: Vec<usize>,
    /// The submeshes locked, the texture sets hidden and locked, the meshes picked, by place.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    locked: Vec<usize>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    sets_hidden: Vec<usize>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    sets_locked: Vec<usize>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    active: Vec<usize>,
    /// Each part's roughness and metalness documents, by part, if made.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    channels: Vec<[Option<Document>; 2]>,
}

fn obj() -> String {
    "obj".to_owned()
}

#[derive(Clone, Copy, Debug, serde::Serialize, serde::Deserialize)]
struct SavedOrbit {
    target: [f32; 3],
    /// Older saves: the turn about the upright axis and the tilt (radians).
    #[serde(default)]
    yaw: f32,
    #[serde(default)]
    pitch: f32,
    /// The camera's turn (a quaternion: i, j, k, real).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    rotation: Option<[f32; 4]>,
    distance: f32,
    fov_y: f32,
}

/// What a save of a model writes besides the artwork's own, read under the lock.
pub(crate) struct ModelSnapshot {
    pub(crate) saved: SavedModel,
    source: Arc<ModelSource>,
    /// The waiting texture sets' layers, by part, and the parts' channels' (part, channel 1 or 2).
    layers: Vec<(usize, Pixels)>,
    channel_layers: Vec<(usize, usize, Pixels)>,
    /// The parts' normal and occlusion maps (part, its file's name, RGBA8).
    maps: Vec<(usize, &'static str, Arc<Vec<u8>>)>,
    /// The model as last shown (premultiplied RGBA8, its size), for the gallery.
    pub(crate) picture: Option<(u32, u32, Vec<u8>)>,
}

/// What the last saves of a model wrote: a hash of each waiting texture set's layers, by part,
/// channel (0 its colour) and layer.
pub(crate) type SavedSets = HashMap<(usize, usize, LayerId), u64>;

/// A waiting texture set's layers' pixels: read from its canvas, or as opened if not drawn yet.
fn set_pixels(set: &mut TextureSet, gpu: Option<&procraft_gpu::Gpu>) -> Result<Pixels, MobileError> {
    match (set.canvas.as_mut(), gpu) {
        (Some(canvas), Some(gpu)) => {
            canvas.render(gpu, &mut set.doc, None);
            let mut pixels = Vec::new();
            // the layers with pixels (a group has none of its own)
            for id in set.doc.pixel_layer_ids() {
                let rgba = canvas.read_layer(gpu, id).map_err(|e| file_error("reading a layer", e))?;
                pixels.push((id, rgba));
            }
            Ok(pixels)
        }
        _ => Ok(set.pending_with_fill()),
    }
}

/// A light on the model as the interface sets it: its place (in the framing's units from the
/// model's middle: x right, y up, z toward the front), its hue (a place along the hue bar, 0 to 1),
/// how much of that colour it has (0 white, 1 the hue's own), and its intensity (0 to 1).
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct ModelLight {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub hue: f32,
    pub saturation: f32,
    pub intensity: f32,
}

/// A light added in the studio shines this strongly.
const DEFAULT_INTENSITY: f32 = 0.35;

impl ModelLight {
    fn from_gpu(l: &Light) -> ModelLight {
        let [x, y, z] = l.place;
        ModelLight { x, y, z, hue: 0.0, saturation: 0.0, intensity: l.intensity }
    }

    /// Kept within its ranges, any value that isn't a number reset.
    fn sane(self) -> ModelLight {
        let f = |v: f32, lo: f32, hi: f32, or: f32| if v.is_finite() { v.clamp(lo, hi) } else { or };
        ModelLight {
            x: f(self.x, -1e4, 1e4, 0.0),
            y: f(self.y, -1e4, 1e4, 0.0),
            z: f(self.z, -1e4, 1e4, 0.0),
            hue: f(self.hue, 0.0, 1.0, 0.0),
            saturation: f(self.saturation, 0.0, 1.0, 0.0),
            intensity: f(self.intensity, 0.0, 1.0, DEFAULT_INTENSITY),
        }
    }

    /// The light as the renderer takes it: its colour the hue bar's at its hue, mixed with white
    /// by its saturation, in linear light.
    fn to_gpu(self) -> Light {
        let srgb = hue_color(self.hue).map(|c| 1.0 + (c - 1.0) * self.saturation.clamp(0.0, 1.0));
        Light { place: [self.x, self.y, self.z], color: srgb.map(srgb_to_linear), intensity: self.intensity }
    }
}

/// The hue bar's colour (sRGB) at `t` along it (0 to 1).
fn hue_color(t: f32) -> [f32; 3] {
    let at = t.clamp(0.0, 1.0) * (HUE_STOPS.len() - 1) as f32;
    let i = (at.floor() as usize).min(HUE_STOPS.len() - 2);
    let f = at - i as f32;
    let (a, b) = (HUE_STOPS.get(i).copied().unwrap_or([1.0; 3]), HUE_STOPS.get(i + 1).copied().unwrap_or([1.0; 3]));
    [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f]
}

fn srgb_to_linear(c: f32) -> f32 {
    if c <= 0.04045 { c / 12.92 } else { ((c + 0.055) / 1.055).powf(2.4) }
}

/// An orbit's turn about the upright and its tilt up (radians), its camera held level.
fn yaw_pitch(orbit: &Orbit) -> (f32, f32) {
    let back = {
        let e = orbit.eye();
        let t = orbit.target;
        let v = [e[0] - t[0], e[1] - t[1], e[2] - t[2]];
        let l = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-9);
        [v[0] / l, v[1] / l, v[2] / l]
    };
    (back[0].atan2(back[2]), back[1].clamp(-1.0, 1.0).asin())
}

impl Scene {
    /// A copy of the view's lights as the scene's lights set them (to project and move them).
    fn lights_view<'a>(&self, view: &'a MeshView) -> LightsView<'a> {
        LightsView { view, lights: self.lights.iter().map(|l| l.to_gpu()).collect() }
    }
}

/// The view's way of placing lights on the screen, for lights of the scene's own.
struct LightsView<'a> {
    view: &'a MeshView,
    lights: Vec<Light>,
}

impl LightsView<'_> {
    fn light_on_screen(&self, orbit: &Orbit, index: usize, width: u32, height: u32) -> Option<[f32; 3]> {
        let light = self.lights.get(index)?;
        self.view.place_on_screen(orbit, light.place, width, height)
    }

    fn move_light(&mut self, orbit: &Orbit, index: usize, dx: f32, dy: f32, width: u32, height: u32) {
        if let Some(l) = self.lights.get_mut(index) {
            l.place = self.view.moved_place(orbit, l.place, dx, dy, width, height);
        }
    }
}

/// A model and its texture sets, as the interface sees them.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ModelInfo {
    pub texture_sets: Vec<String>,
    pub triangles: u32,
    /// The model's meshes, each in its texture set, in order.
    pub submeshes: Vec<SubmeshInfo>,
    /// Each texture set's side, pixels.
    pub texture_size: u32,
}

/// A texture set's share of a model's layers, as Canvas information lists it.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct TextureSetStats {
    pub name: String,
    /// Its layers with pixels, and how many it can hold.
    pub used: u32,
    pub maximum: u32,
}

/// An image a model's file brings for a texture set's channel (0 colour, 1 roughness, 2
/// metalness, 3 its normal map, 4 its occlusion): its file's bytes, and which of its components
/// to take (-1: as it is, or its grey).
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ModelTextureFile {
    pub texture_set: u32,
    pub channel: u32,
    pub component: i32,
    pub bytes: Vec<u8>,
}

/// One of a model's meshes: its name and the texture set it is painted in.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SubmeshInfo {
    pub name: String,
    pub texture_set: u32,
}

impl ModelInfo {
    pub(crate) fn of(scene: &Scene) -> ModelInfo {
        ModelInfo {
            texture_sets: scene.set_names(),
            triangles: u32::try_from(scene.mesh.triangles.len()).unwrap_or(u32::MAX),
            submeshes: scene
                .mesh
                .submeshes
                .iter()
                .map(|m| SubmeshInfo { name: m.name.clone(), texture_set: u32::try_from(m.part).unwrap_or(0) })
                .collect(),
            texture_size: scene.texture_size,
        }
    }
}

/// What each warning is called, as a list in a dialog shows it.
#[uniffi::export]
pub fn model_warning_name(warning: ModelWarning) -> String {
    match warning {
        ModelWarning::OverlappingUvs => "Overlapping UV Coordinates",
        ModelWarning::RepeatingTextures => "Repeating Textures",
    }
    .to_owned()
}


mod painting;
#[allow(unused_imports)]
pub(crate) use self::painting::{follow_layers, open_saved_model, write_model};

#[cfg(test)]
mod tests;
