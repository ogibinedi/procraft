use procraft_gpu::{Gpu, wgpu};

use super::ModelFile;
use crate::{CanvasSession, PenSample, PointerKind};

/// A cube whose every face covers the whole texture.
const CUBE: &str = "v -1 -1 -1\nv 1 -1 -1\nv 1 1 -1\nv -1 1 -1\nv -1 -1 1\nv 1 -1 1\nv 1 1 1\nv -1 1 1\n\
                    vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n\
                    f 1/1 4/4 3/3 2/2\nf 5/1 6/2 7/3 8/4\nf 4/1 8/2 7/3 3/4\n\
                    f 1/1 2/2 6/3 5/4\nf 2/1 3/2 7/3 6/4\nf 1/1 5/2 8/3 4/4\n";

fn sample(x: f32, y: f32, t: f64) -> PenSample {
    PenSample { x, y, pressure: 1.0, tilt: 0.0, orientation: 0.0, time_ms: t, roll: 0.0 }
}

/// Draws a frame of the model into a `side` square texture.
fn frame(s: &CanvasSession, side: u32) {
    let mut guard = s.shared.lock();
    let st = &mut *guard;
    let format = wgpu::TextureFormat::Rgba8Unorm;
    let target = st.gpu.as_ref().unwrap().device.create_texture(&wgpu::TextureDescriptor {
        label: Some("test frame"),
        size: wgpu::Extent3d { width: side, height: side, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
        view_formats: &[],
    });
    let view = target.create_view(&wgpu::TextureViewDescriptor::default());
    st.render_scene(&view, format, (side, side), None);
    assert_eq!(st.gpu.as_ref().unwrap().errors(), 0, "GPU validation errors");
}

/// A frame of `s`'s model, `side` square, read back (RGBA8).
fn frame_pixels(s: &CanvasSession, side: u32) -> Vec<u8> {
    let mut guard = s.shared.lock();
    let st = &mut *guard;
    let format = wgpu::TextureFormat::Rgba8Unorm;
    let target = st.gpu.as_ref().unwrap().device.create_texture(&wgpu::TextureDescriptor {
        label: Some("test frame"),
        size: wgpu::Extent3d { width: side, height: side, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let view = target.create_view(&wgpu::TextureViewDescriptor::default());
    st.render_scene(&view, format, (side, side), None);
    let gpu = st.gpu.as_ref().unwrap();
    assert_eq!(gpu.errors(), 0, "GPU validation errors");
    procraft_gpu::read_texture(gpu, &target, side, side).unwrap()
}

#[test]
fn panels_over_a_model_blur_it_under_them() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let plain = frame_pixels(&s, 200);
    // a panel over the left half
    s.shared.lock().backdrops = vec![[0.0, 0.0, 100.0, 200.0, 0.0, 1.0]];
    let frosted = frame_pixels(&s, 200);
    let at = |p: &[u8], x: usize, y: usize| p[(y * 200 + x) * 4..][..4].to_vec();
    // the model's edge softened under it, and nothing changed beside it
    let under = (0..200).flat_map(|y| (0..100).map(move |x| (x, y)));
    let changed = under.filter(|&(x, y)| at(&plain, x, y) != at(&frosted, x, y)).count();
    assert!(changed > 200, "{changed}");
    assert!((0..200).all(|y| (100..200).all(|x| at(&plain, x, y) == at(&frosted, x, y))));
}

/// What the current channel of the current texture set starts as (its Base Layer's fill).
fn fresh(s: &CanvasSession) -> [u8; 4] {
    let st = s.shared.lock();
    let scene = st.scene.as_ref().unwrap();
    let (colour, roughness, metallic) = scene.mesh.surface(scene.current);
    super::opaque(match scene.channel {
        0 => colour,
        1 => [roughness; 3],
        _ => [metallic; 3],
    })
}

/// How many of the current texture set's pixels have been painted: changed from what the
/// model's material gave them.
fn painted(s: &CanvasSession) -> usize {
    let fresh = fresh(s);
    let mut guard = s.shared.lock();
    let st = &mut *guard;
    let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
    canvas.render(gpu, &mut st.doc, None);
    canvas.read_composite(gpu).unwrap().chunks(4).filter(|p| *p != fresh).count()
}

#[test]
fn a_stroke_across_a_model_paints_its_texture() {
    let gpu = match Gpu::headless() {
        Ok(g) => g,
        Err(e) => return eprintln!("skipped: {e}"),
    };
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 256).unwrap();
    let info = s.model_info().unwrap();
    assert_eq!((info.texture_sets.len(), info.triangles), (1, 12));
    {
        let mut st = s.shared.lock();
        st.window_size = (200, 200);
        st.gpu = Some(gpu);
        st.ensure_canvas();
    }
    // lit by an environment, shown behind it (set up on the next frame)
    let sky = procraft_engine::environment::Equirect::uniform([0.8, 0.9, 1.0]);
    assert!(s.set_model_environment("sky".into(), procraft_engine::environment::write_hdr(&sky)));
    assert!(!s.set_model_environment("bad".into(), b"not a picture".to_vec()));
    assert_eq!(s.model_environment().as_deref(), Some("sky"));
    frame(&s, 200);
    assert_eq!(painted(&s), 0);
    // across the middle of the window, where the cube faces the camera
    s.stroke_begin(PointerKind::Stylus, vec![sample(70.0, 100.0, 0.0)]);
    frame(&s, 200);
    s.stroke_add((1..16).map(|i| sample(70.0 + i as f32 * 4.0, 100.0, f64::from(i) * 8.0)).collect());
    frame(&s, 200);
    s.stroke_end(vec![sample(134.0, 100.0, 140.0)]);
    // kept as the pen lifts, before the next frame
    assert!(s.info().can_undo);
    frame(&s, 200);
    let kept = painted(&s);
    assert!(kept > 100, "{kept} pixels painted");
    assert!(s.info().can_undo);
    s.undo();
    assert_eq!(painted(&s), 0);

    // turning the model and painting again reaches another face
    s.turn_model(100.0, 0.0);
    s.stroke_begin(PointerKind::Stylus, vec![sample(100.0, 70.0, 0.0)]);
    s.stroke_end(vec![sample(100.0, 130.0, 100.0)]);
    frame(&s, 200);
    assert!(painted(&s) > 100);
}

#[test]
fn a_finger_on_the_model_turns_it_and_the_pen_paints() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let orbit = |s: &CanvasSession| s.shared.lock().scene.as_ref().and_then(|c| c.view.as_ref()).map(|v| v.orbit);
    let before = orbit(&s);
    assert!(before.is_some());
    s.touch_down(1, PointerKind::Finger, vec![sample(70.0, 100.0, 0.0)]);
    s.touch_move(1, vec![sample(130.0, 100.0, 16.0)]);
    let out = s.touch_up(1, vec![sample(130.0, 100.0, 32.0)]);
    assert!(!out.contains(&crate::TouchCommand::StrokeEnded), "{out:?}");
    assert_ne!(orbit(&s), before);
    frame(&s, 200);
    assert_eq!(painted(&s), 0);
    // the pen paints on it
    s.touch_down(2, PointerKind::Stylus, vec![sample(70.0, 100.0, 100.0)]);
    frame(&s, 200);
    s.touch_move(2, (1..16).map(|i| sample(70.0 + i as f32 * 4.0, 100.0, 100.0 + f64::from(i) * 8.0)).collect());
    frame(&s, 200);
    s.touch_up(2, vec![sample(134.0, 100.0, 240.0)]);
    frame(&s, 200);
    let kept = painted(&s);
    assert!(kept > 100);
    // a second finger hands both to the gestures: spread apart, they bring the model nearer
    let distance = |s: &CanvasSession| orbit(s).map_or(0.0, |o| o.distance);
    let far = distance(&s);
    s.touch_down(3, PointerKind::Finger, vec![sample(80.0, 100.0, 300.0)]);
    s.touch_move(3, vec![sample(90.0, 100.0, 308.0)]);
    s.touch_down(4, PointerKind::Finger, vec![sample(120.0, 100.0, 316.0)]);
    s.touch_move(3, vec![sample(60.0, 100.0, 332.0)]);
    s.touch_move(4, vec![sample(150.0, 100.0, 332.0)]);
    let mut out = s.touch_up(4, vec![sample(150.0, 100.0, 348.0)]);
    out.extend(s.touch_up(3, vec![sample(60.0, 100.0, 348.0)]));
    assert!(!out.contains(&crate::TouchCommand::Undo), "{out:?}");
    assert!(distance(&s) < far, "{} from {far}", distance(&s));
    frame(&s, 200);
    assert_eq!(painted(&s), kept);
}

#[test]
fn a_mouse_following_touch_turns_a_model_as_a_finger_does() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let orbit = |s: &CanvasSession| s.shared.lock().scene.as_ref().and_then(|c| c.view.as_ref()).map(|v| v.orbit);
    let drag = |s: &CanvasSession, t: f64| {
        s.touch_down(1, PointerKind::Mouse, vec![sample(70.0, 100.0, t)]);
        frame(s, 200);
        s.touch_move(1, (1..16).map(|i| sample(70.0 + i as f32 * 4.0, 100.0, t + f64::from(i) * 8.0)).collect());
        frame(s, 200);
        let out = s.touch_up(1, vec![sample(134.0, 100.0, t + 140.0)]);
        frame(s, 200);
        out
    };
    // a tablet's: the mouse turns it, painting nothing
    s.set_touch_settings(crate::TouchSettings { pointer_follows_touch: true, ..Default::default() });
    let before = orbit(&s);
    let out = drag(&s, 0.0);
    assert!(!out.contains(&crate::TouchCommand::StrokeEnded), "{out:?}");
    assert_ne!(orbit(&s), before);
    assert_eq!(painted(&s), 0);
    // set to paint the model with a finger, it paints it as a finger would
    s.set_touch_settings(crate::TouchSettings {
        pointer_follows_touch: true,
        model_finger_paint: true,
        ..Default::default()
    });
    let turned = orbit(&s);
    drag(&s, 1000.0);
    assert_eq!(orbit(&s), turned);
    let kept = painted(&s);
    assert!(kept > 100, "{kept}");
    // a desktop's: the mouse paints it as the pen does
    s.set_touch_settings(crate::TouchSettings::default());
    let _ = s.undo();
    assert_eq!(painted(&s), 0);
    drag(&s, 2000.0);
    assert_eq!(orbit(&s), turned);
    assert!(painted(&s) > 100);
}

/// Two squares facing the camera side by side, Left and Right, one material: Left on the left
/// half of the texture, Right on the right.
const TWO_MESHES: &str = "v -2 -1 0\nv -0.2 -1 0\nv -0.2 1 0\nv -2 1 0\n\
                          v 0.2 -1 0\nv 2 -1 0\nv 2 1 0\nv 0.2 1 0\n\
                          vt 0 0\nvt 0.5 0\nvt 0.5 1\nvt 0 1\nvt 0.5 0\nvt 1 0\nvt 1 1\nvt 0.5 1\n\
                          o Left\nf 1/1 2/2 3/3 4/4\no Right\nf 5/5 6/6 7/7 8/8\n";

/// A pen stroke from (`x0`, `y`) to (`x1`, `y`), each step drawn.
fn pen_across(s: &CanvasSession, x0: f32, x1: f32, y: f32, t: f64) {
    s.touch_down(7, PointerKind::Stylus, vec![sample(x0, y, t)]);
    frame(s, 200);
    let at = |i: u32| x0 + (x1 - x0) * i as f32 / 20.0;
    s.touch_move(7, (1..20).map(|i| sample(at(i), y, t + f64::from(i) * 8.0)).collect());
    frame(s, 200);
    s.touch_up(7, vec![sample(x1, y, t + 170.0)]);
    frame(s, 200);
}

/// How many texels of the current set's left and right halves are painted.
fn painted_halves(s: &CanvasSession) -> (usize, usize) {
    let fresh = fresh(s);
    let mut guard = s.shared.lock();
    let st = &mut *guard;
    let side = st.doc.width() as usize;
    let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
    canvas.render(gpu, &mut st.doc, None);
    let pixels = canvas.read_composite(gpu).unwrap();
    let on = pixels.chunks(4).enumerate().filter(|(_, p)| *p != fresh).map(|(i, _)| i % side);
    on.fold((0, 0), |(l, r), x| if x < side / 2 { (l + 1, r) } else { (l, r + 1) })
}

#[test]
fn a_stroke_reaches_the_mesh_picked_and_spares_a_locked_one() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    assert_eq!(s.model_info().unwrap().submeshes.len(), 2);
    // a mesh under each side of the window, nothing in the gap or round the edge
    assert_eq!((s.submesh_at(60.0, 100.0), s.submesh_at(140.0, 100.0)), (Some(0), Some(1)));
    assert_eq!((s.submesh_at(100.0, 100.0), s.submesh_at(100.0, 10.0)), (None, None));
    // the left picked: a stroke across both stays on it
    s.pick_submesh(0);
    assert_eq!(s.active_submeshes(), [0]);
    pen_across(&s, 40.0, 160.0, 100.0, 0.0);
    let (left, right) = painted_halves(&s);
    assert!(left > 50 && right == 0, "{left} {right}");
    // both picked, it reaches both
    assert!(s.pick_more_submeshes(1));
    pen_across(&s, 40.0, 160.0, 80.0, 500.0);
    let (more, right) = painted_halves(&s);
    assert!(more > left && right > 50, "{more} {right}");
    // the left locked: it stays as it is, the right goes on taking paint
    s.set_submesh_locked(0, true);
    assert_eq!(s.submeshes_locked(), [true, false]);
    pen_across(&s, 40.0, 160.0, 120.0, 1000.0);
    let (still, grown) = painted_halves(&s);
    assert!(still == more && grown > right, "{still} {grown}");
    // the locks and picks undo as steps of their own
    s.undo();
    assert!(s.submeshes_locked() == [true, false], "the stroke goes first");
    s.undo();
    assert_eq!(s.submeshes_locked(), [false, false]);
    s.redo();
    assert_eq!(s.submeshes_locked(), [true, false]);
    // every mesh meant locked or hidden, a stroke can't land and the session says why
    s.set_submesh_locked(1, true);
    assert_eq!(s.why_blocked(), Some(crate::Blocked::MeshLocked));
    s.set_submesh_hidden(0, true);
    s.set_submesh_hidden(1, true);
    assert_eq!(s.why_blocked(), Some(crate::Blocked::MeshHidden));
    assert_eq!(s.submesh_at(60.0, 100.0), None);
    s.set_submesh_hidden(0, false);
    s.set_submesh_hidden(1, false);
    s.set_submesh_locked(0, false);
    s.set_submesh_locked(1, false);
    assert_eq!(s.why_blocked(), None);
    // a texture set locked or hidden takes its meshes with it
    s.set_texture_set_locked(0, true);
    assert_eq!(s.why_blocked(), Some(crate::Blocked::MeshLocked));
    s.undo();
    assert_eq!(s.texture_sets_locked(), [false]);
    s.set_texture_set_hidden(0, true);
    assert_eq!(s.why_blocked(), Some(crate::Blocked::MeshHidden));
    assert_eq!(s.submesh_at(60.0, 100.0), None);
}

/// A pen stroke on the model with `tool`, from (`x0`, `y`) to (`x1`, `y`).
fn tool_across(s: &CanvasSession, tool: crate::ToolKind, x0: f32, x1: f32, y: f32, t: f64) {
    s.shared.lock().tool = tool;
    pen_across(s, x0, x1, y, t);
}

/// The texels of the current set that aren't `like`, with the left half of the texture's
/// colour most changed, red first.
fn texels(s: &CanvasSession) -> Vec<[u8; 4]> {
    let mut guard = s.shared.lock();
    let st = &mut *guard;
    let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
    canvas.render(gpu, &mut st.doc, None);
    canvas.read_composite(gpu).unwrap().as_chunks::<4>().0.to_vec()
}

#[test]
fn a_smudge_on_a_model_only_drags_paint_the_way_it_goes() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    s.set_color(1.0, 0.0, 0.0);
    pen_across(&s, 40.0, 80.0, 100.0, 0.0);
    let before = texels(&s);
    let side = 128usize;
    let band = (0..side).find(|y| before[y * side..(y + 1) * side].iter().any(|p| p[1] < 200)).unwrap();
    assert!(band > 30, "{band}");
    // down the model, across the band
    s.shared.lock().tool = crate::ToolKind::Smudge;
    let (x, top, bottom, t) = (60.0, 70.0, 130.0, 1000.0);
    s.touch_down(7, PointerKind::Stylus, vec![sample(x, top, t)]);
    frame(&s, 200);
    let at = |i: u32| top + (bottom - top) * i as f32 / 20.0;
    s.touch_move(7, (1..20).map(|i| sample(x, at(i), t + f64::from(i) * 8.0)).collect());
    frame(&s, 200);
    s.touch_up(7, vec![sample(x, bottom, t + 170.0)]);
    frame(&s, 200);
    let after = texels(&s);
    // what lies before the band stays bare, and what lies past it takes some on
    let upper = (band - 4) * side;
    assert_eq!(before[..upper], after[..upper]);
    let last_row = &after[(side - 8) * side..(side - 7) * side];
    assert!(last_row.iter().any(|p| p[1] < 250), "nothing was dragged past the band");
}

#[test]
fn a_smudge_on_a_model_drags_what_is_painted_along_its_surface() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    s.set_color(1.0, 0.0, 0.0);
    // a red band on the left mesh, then a smudge across it, on and off it
    pen_across(&s, 55.0, 80.0, 100.0, 0.0);
    let before = texels(&s);
    let red = |t: &[[u8; 4]]| t.iter().filter(|p| p[0] > 200 && p[1] < 120).count();
    let pink = |t: &[[u8; 4]]| t.iter().filter(|p| p[1] < 245 && p[1] >= 40).count();
    tool_across(&s, crate::ToolKind::Smudge, 65.0, 88.0, 100.0, 1000.0);
    let after = texels(&s);
    assert_ne!(before, after);
    assert!(pink(&after) > pink(&before), "{} from {}", pink(&after), pink(&before));
    assert!(after.iter().all(|p| p[3] == 255), "the base layer stays opaque");
    // one undo step, whole
    s.undo();
    assert_eq!(texels(&s), before);
    s.redo();
    assert_eq!(texels(&s), after);
    assert!(red(&after) > 0);
    // the right mesh, and what the smudge didn't reach, are as they were
    let side = 128usize;
    let right = |t: &[[u8; 4]]| {
        t.iter().enumerate().filter(|(i, _)| i % side >= side / 2).map(|(_, p)| *p).collect::<Vec<_>>()
    };
    assert_eq!(right(&before), right(&after));
}

#[test]
fn fills_and_adjustments_keep_to_the_mesh_picked() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let base = s.layers().first().unwrap().id;
    s.set_color(1.0, 0.0, 0.0);
    // with nothing picked a fill reaches the whole set
    assert!(s.fill_layer(base));
    frame(&s, 200);
    let (left, right) = painted_halves(&s);
    assert!(left > 500 && right > 500, "{left} {right}");
    s.undo();
    // the left mesh picked, the right stays as it was
    s.pick_submesh(0);
    assert!(s.fill_layer(base));
    frame(&s, 200);
    let (left, right) = painted_halves(&s);
    // (it reaches two texels past its edge, as a stroke does: two columns, here)
    assert!(left > 500 && right <= 2 * 64, "{left} {right}");
    s.undo();
    // an adjustment too, and it leaves no selection behind
    let paint = s.add_layer();
    assert!(s.fill_layer(paint));
    s.pick_submesh(1);
    frame(&s, 200);
    assert!(s.adjustment_start(crate::Adjustment::Hsb));
    s.adjustment_hsb(0.7, 0.5, 0.5);
    frame(&s, 200);
    assert!(s.adjustment_end(true));
    frame(&s, 200);
    assert!(!s.has_selection());
    let t = texels(&s);
    let (red, turned) =
        (t.iter().filter(|p| p[0] > 200 && p[2] < 40).count(), t.iter().filter(|p| p[2] > 100).count());
    assert!(red > 500 && turned > 500, "{red} {turned}");
    // a clear keeps to it as well
    s.clear_layer();
    frame(&s, 200);
    assert!(texels(&s).iter().filter(|p| p[0] > 200 && p[2] < 40).count() > 500);
}

#[test]
fn a_channels_content_is_replaced_by_a_picture_as_an_undo_step() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let base = s.layers().first().unwrap().id;
    let grey = crate::layers::ThumbnailImage { width: 2, height: 2, rgba: [128u8, 128, 128, 255].repeat(4) };
    assert!(s.replace_material_map(base, 1, grey));
    assert_eq!((s.model_channel(), s.channel_tile_chosen()), (1, true));
    frame(&s, 200);
    assert!(texels(&s).iter().all(|p| p[0] == 128 && p[3] == 255));
    s.undo();
    assert!(texels(&s).iter().all(|p| p[0] != 128));
}

#[test]
fn the_reference_window_has_a_camera_of_its_own_on_the_model() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let first = s.reference_model_picture(64).unwrap();
    let seen = s.shared.lock().scene.as_ref().unwrap().orbit();
    s.reference_model_turn(30.0, 0.0, 300.0);
    let turned = s.reference_model_picture(64).unwrap();
    assert_ne!(first.rgba, turned.rgba);
    assert_eq!(s.shared.lock().scene.as_ref().unwrap().orbit(), seen);
    // shown flat, it still shows what is painted
    s.set_model_flat(true);
    s.set_color(1.0, 0.0, 0.0);
    let base = s.layers().first().unwrap().id;
    assert!(s.fill_layer(base));
    let painted = s.reference_model_picture(64).unwrap();
    assert_ne!(painted.rgba, turned.rgba);
}

#[test]
fn a_model_left_showing_itself_in_the_reference_window_opens_on_it_again() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    let mut window = s.reference_window();
    assert_eq!(window.shows, crate::ReferenceShows::Canvas);
    window.shown = true;
    window.shows = crate::ReferenceShows::Model;
    s.set_reference_window(window);
    assert_eq!(s.reference_window(), window);
    let dir = std::env::temp_dir().join(format!("procraft-model-reference-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let path = dir.to_string_lossy().into_owned();
    s.save(path.clone()).unwrap();
    assert_eq!(CanvasSession::open(path.clone()).unwrap().reference_window(), window);
    // and in the model's own file
    let shared = std::env::temp_dir().join(format!("procraft-model-reference-{}.procraft", std::process::id()));
    let unpacked = std::env::temp_dir().join(format!("procraft-model-reference-in-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&unpacked);
    s.export_model_artwork(path.clone(), shared.to_string_lossy().into_owned()).unwrap();
    assert!(crate::unpack_model_artwork(
        shared.to_string_lossy().into_owned(),
        unpacked.to_string_lossy().into_owned()
    ));
    let opened = CanvasSession::open(unpacked.to_string_lossy().into_owned()).unwrap();
    assert_eq!(opened.reference_window().shows, crate::ReferenceShows::Model);
    let _ = std::fs::remove_dir_all(&dir);
    let _ = std::fs::remove_dir_all(&unpacked);
    let _ = std::fs::remove_file(&shared);
}

#[test]
fn a_saved_model_turns_in_the_gallerys_preview_without_a_window() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let dir = std::env::temp_dir().join(format!("procraft-model-preview-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let path = dir.to_string_lossy().into_owned();
    s.save(path.clone()).unwrap();
    assert!(crate::project::artwork_plays(path.clone()));
    let opened = CanvasSession::open(path).unwrap();
    assert!(opened.draw_without_window());
    let a = opened.model_preview_picture(0, 64).unwrap();
    let b = opened.model_preview_picture(opened.model_preview_frames() / 4, 64).unwrap();
    assert_eq!(a.rgba.len(), 64 * 64 * 4);
    assert_ne!(a.rgba, b.rgba);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_new_model_warns_once_of_what_painting_it_may_not_go_well_with() {
    use super::ModelWarning::{OverlappingUvs, RepeatingTextures};
    let open = |text: &str| {
        CanvasSession::open_model(text.as_bytes().to_vec(), "m.obj".into(), Vec::new(), "M".into(), 64).unwrap()
    };
    let s = open(TWO_MESHES);
    assert_eq!(s.take_model_warnings(), []);
    // every face of the cube covers the whole texture
    let s = open(CUBE);
    assert_eq!(s.take_model_warnings(), [OverlappingUvs]);
    assert_eq!(s.take_model_warnings(), []);
    // coordinates past the texture's edge
    let tiled = TWO_MESHES.replace("vt 1 1", "vt 2 1");
    let s = open(&tiled);
    assert!(s.take_model_warnings().contains(&RepeatingTextures));
}

#[test]
fn a_models_artwork_goes_out_as_one_file_and_comes_back_whole() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    pen_across(&s, 40.0, 160.0, 100.0, 0.0);
    s.set_submesh_locked(1, true);
    let painted = painted_halves(&s);
    let work = std::env::temp_dir().join(format!("procraft-model-artwork-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&work);
    let (dir, file, again) = (work.join("artwork"), work.join("Two.procraft"), work.join("again"));
    std::fs::create_dir_all(&work).unwrap();
    let path = |p: &std::path::Path| p.to_string_lossy().into_owned();
    s.export_model_artwork(path(&dir), path(&file)).unwrap();
    assert_eq!(crate::zip_file_kind(path(&file)), crate::ZipKind::ModelArtwork);
    assert!(super::unpack_model_artwork(path(&file), path(&again)));
    let opened = CanvasSession::open(path(&again)).unwrap();
    assert!(shown(&opened));
    frame(&opened, 200);
    assert_eq!(opened.model_info().unwrap().submeshes.len(), 2);
    assert_eq!(opened.submeshes_locked(), [false, true]);
    assert_eq!(painted_halves(&opened), painted);
    // a file that isn't one is left alone, and nothing leaves the folder given
    let mut zip = procraft_engine::zip::ZipWriter::new();
    zip.add("artwork.json", b"{}");
    zip.add("model.obj", b"");
    zip.add("../outside.txt", b"x");
    std::fs::write(work.join("sly.procraft"), zip.finish()).unwrap();
    assert!(super::unpack_model_artwork(path(&work.join("sly.procraft")), path(&work.join("sly"))));
    assert!(!work.join("outside.txt").exists());
    assert!(!super::unpack_model_artwork(path(&work.join("artwork/artwork.json")), path(&work.join("no"))));
    let _ = std::fs::remove_dir_all(&work);
}

const TWO_SETS: &str = "v -2 -1 0\nv -0.2 -1 0\nv -0.2 1 0\nv -2 1 0\nv 0.2 -1 0\nv 2 -1 0\nv 2 1 0\nv 0.2 1 0\n\
                        vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n\
                        usemtl a\no A\nf 1/1 2/2 3/3 4/4\nusemtl b\no B\nf 5/1 6/2 7/3 8/4\n";

#[test]
fn canvas_information_counts_a_models_layers_by_texture_set() {
    let s = CanvasSession::open_model(TWO_SETS.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let most = s.layer_stats().maximum;
    let used = |s: &CanvasSession| s.texture_set_stats().iter().map(|t| t.used).collect::<Vec<_>>();
    let names = s.texture_set_stats().into_iter().map(|t| t.name).collect::<Vec<_>>();
    assert_eq!(names, s.model_info().unwrap().texture_sets);
    assert!(s.texture_set_stats().iter().all(|t| t.maximum == most));
    assert_eq!((used(&s), s.layer_stats().used), (vec![1, 1], 2));
    // a layer on the first, counted where it is whichever set or channel is painted on
    assert_ne!(s.add_layer(), 0);
    assert_eq!((used(&s), s.layer_stats().used), (vec![2, 1], 3));
    s.set_model_channel(1);
    assert_eq!((used(&s), s.layer_stats().used), (vec![2, 1], 3));
    s.set_model_channel(0);
    s.choose_texture_set(1);
    assert_eq!((used(&s), s.layer_stats().used), (vec![2, 1], 3));
    assert_eq!(s.layer_stats().maximum, most);
}

#[test]
fn meshes_of_another_texture_set_cant_be_picked_together() {
    let s = CanvasSession::open_model(TWO_SETS.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    assert_eq!(s.model_info().unwrap().texture_sets.len(), 2);
    s.pick_submesh(0);
    assert!(!s.pick_more_submeshes(1));
    assert_eq!(s.active_submeshes(), [0]);
    // picking it alone moves to its texture set
    s.pick_submesh(1);
    assert_eq!((s.active_submeshes(), s.texture_set()), (vec![1], 1));
}

#[test]
fn a_picture_placed_on_a_model_lands_on_a_layer_of_its_own() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let layers = s.layers().len();
    let rgba = [255u8, 0, 0, 255].repeat(16 * 16);
    assert!(s.model_picture_begin(crate::layers::ThumbnailImage { width: 16, height: 16, rgba }, true));
    assert_eq!(s.layers().len(), layers + 1);
    // centred on the window, it starts as a square: over the gap and both meshes
    let corners = s.model_picture_box().unwrap();
    assert_eq!(corners.len(), 8);
    assert!((corners[0] - 30.0).abs() < 1.0 && (corners[2] - 170.0).abs() < 1.0, "{corners:?}");
    frame(&s, 200);
    let (left, right) = painted_halves(&s);
    assert!(left > 50 && right > 50, "{left} {right}");
    // moved over the left mesh it leaves the right one
    s.model_picture_move(-40.0, 0.0, 0.3, 0.0);
    frame(&s, 200);
    let (left, right) = painted_halves(&s);
    assert!(left > 10 && right == 0, "{left} {right}");
    // kept, it stays and the box goes; undo takes it back
    s.model_picture_end(true);
    assert_eq!(s.model_picture_box(), None);
    frame(&s, 200);
    assert_eq!(painted_halves(&s), (left, 0));
    s.undo();
    frame(&s, 200);
    assert_eq!(painted_halves(&s), (0, 0));
    // cancelled, the layer goes too
    s.redo();
    let rgba = [0u8, 255, 0, 255].repeat(4);
    assert!(s.model_picture_begin(crate::layers::ThumbnailImage { width: 2, height: 2, rgba }, true));
    frame(&s, 200);
    s.model_picture_end(false);
    assert_eq!(s.layers().len(), layers + 1);
    frame(&s, 200);
    assert_eq!(painted_halves(&s), (left, 0));
}

#[test]
fn a_transform_on_a_model_shows_its_texture_flat_until_it_ends() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    pen_across(&s, 40.0, 160.0, 100.0, 0.0);
    assert!(!s.model_flat());
    assert!(s.transform_begin().is_some());
    assert!(s.model_flat());
    s.transform_end(false);
    assert!(!s.model_flat());
    // shown flat by the switch, it stays so
    s.set_model_flat(true);
    assert!(s.transform_begin().is_some());
    s.transform_end(false);
    assert!(s.model_flat());
}

#[test]
fn a_placed_pictures_place_maps_the_window_onto_the_picture() {
    let place =
        super::PicturePlace { middle: [100.0, 50.0], half: [40.0, 20.0], turn: std::f32::consts::FRAC_PI_2 };
    // turned a quarter clockwise: its right side points down the window
    let [_, top_right, ..] = place.corners();
    assert!((top_right[0] - 120.0).abs() < 1e-3 && (top_right[1] - 90.0).abs() < 1e-3, "{top_right:?}");
    let [x, y] = place.to_picture((80, 40));
    let at = |p: [f32; 2]| (x[0] * p[0] + x[1] * p[1] + x[2], y[0] * p[0] + y[1] * p[1] + y[2]);
    let (px, py) = at(top_right);
    assert!((px - 80.0).abs() < 1e-3 && py.abs() < 1e-3, "{px} {py}");
    assert!(place.holds([100.0, 50.0]) && !place.holds([50.0, 50.0]));
    // flipped, the same corner reads the other side of the picture
    let flipped = super::PicturePlace { half: [-40.0, 20.0], ..place };
    let [x, _] = flipped.to_picture((80, 40));
    assert!((x[0] * 120.0 + x[1] * 90.0 + x[2]).abs() < 1e-3);
}

#[test]
fn the_eyedropper_reads_what_is_painted_on_the_model_under_it() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    s.set_color(1.0, 0.0, 0.0);
    frame(&s, 200);
    pen_across(&s, 40.0, 160.0, 100.0, 0.0);
    let pick = s.pick(60.0, 100.0, 9).unwrap();
    let rgb = pick.rgb.unwrap();
    assert!(rgb >> 16 > 200 && rgb & 0xff < 60, "{rgb:06x}");
    assert_eq!(pick.pixels.len(), 9 * 9 * 4);
    // what shows there is the model, lit, in the picture round the point
    let middle = &pick.pixels[(4 * 9 + 4) * 4..][..4];
    assert!(middle[0] > middle[2], "{middle:?}");
    // off the model, no colour, but still a picture
    let pick = s.pick(100.0, 10.0, 9).unwrap();
    assert_eq!(pick.rgb, None);
    // where nothing is painted, the model's own white
    let top = (0..100).find(|&y| s.submesh_at(60.0, y as f32).is_some()).unwrap() as f32;
    assert_eq!(s.pick(60.0, top + 1.0, 1).unwrap().rgb, Some(0xffffff));
}

/// One square behind another, both filling the same place on the screen: the near one on the
/// left half of the texture, the far one on the right.
const FRONT_AND_BACK: &str = "v -2 -2 0\nv 2 -2 0\nv 2 2 0\nv -2 2 0\nv -2 -2 -1\nv 2 -2 -1\nv 2 2 -1\nv -2 2 -1\n\
                              vt 0 0\nvt 0.5 0\nvt 0.5 1\nvt 0 1\nvt 0.5 0\nvt 1 0\nvt 1 1\nvt 0.5 1\n\
                              o Near\nf 1/1 2/2 3/3 4/4\no Far\nf 5/5 6/6 7/7 8/8\n";

/// How much of the selection lies on each half of the texture.
fn selected_halves(s: &CanvasSession) -> (usize, usize) {
    let st = s.shared.lock();
    let Some(m) = st.selection.as_ref() else { return (0, 0) };
    let side = m.width as usize;
    let on = m.data.iter().enumerate().filter(|(_, c)| **c > 127).map(|(i, _)| i % side);
    on.fold((0, 0), |(l, r), x| if x < side / 2 { (l + 1, r) } else { (l, r + 1) })
}

#[test]
fn a_selection_is_drawn_on_the_screen_and_x_ray_reaches_through_the_model() {
    use crate::SelectionJoin::Add;
    let s =
        CanvasSession::open_model(FRONT_AND_BACK.into(), "two.obj".into(), Vec::new(), "Two".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    // only the near surface takes it
    s.select_shape(false, 80.0, 80.0, 120.0, 120.0, Add);
    let (near, far) = selected_halves(&s);
    assert!(near > 100 && far == 0, "{near} {far}");
    // a stroke across the window stays inside it
    pen_across(&s, 20.0, 180.0, 100.0, 0.0);
    let (left, right) = painted_halves(&s);
    assert!(left > 10 && left <= near && right == 0, "{left} {right} of {near}");
    {
        let st = s.shared.lock();
        let selection = st.selection.as_ref().unwrap();
        let side = selection.width as usize;
        drop(st);
        let fresh = fresh(&s);
        let mut guard = s.shared.lock();
        let st = &mut *guard;
        let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
        canvas.render(gpu, &mut st.doc, None);
        let pixels = canvas.read_composite(gpu).unwrap();
        let selection = st.selection.as_ref().unwrap();
        let outside = pixels.chunks(4).zip(&selection.data).filter(|(p, c)| *p != fresh && **c == 0).count();
        assert_eq!(outside, 0, "{outside} texels painted outside, of {side} square");
    }
    // the selection shows on the model
    let plain = frame_pixels(&s, 200);
    {
        let mut guard = s.shared.lock();
        guard.scene_selection_look(Some(procraft_gpu::SelectionLook::Fill([1.0, 0.0, 0.0])));
    }
    let covered = frame_pixels(&s, 200);
    let at = |p: &[u8], x: usize, y: usize| p[(y * 200 + x) * 4..][..3].to_vec();
    assert_ne!(at(&plain, 100, 100), at(&covered, 100, 100));
    assert_eq!(at(&covered, 100, 100), [255, 0, 0]);
    assert_eq!(at(&plain, 60, 60), at(&covered, 60, 60));
    s.select_none();
    // with X-Ray, the surface behind as well
    s.set_selection_x_ray(true);
    s.select_shape(false, 80.0, 80.0, 120.0, 120.0, Add);
    let (near, far) = selected_halves(&s);
    assert!(near > 100 && far > 100, "{near} {far}");
    // tapped with Automatic, the texel under the touch is what the flood starts from
    s.select_none();
    s.select_by_colour(100.0, 100.0, 0.1, Add, false);
    let (near, far) = selected_halves(&s);
    assert!(near > 0 && far == 0, "{near} {far}");
}

#[test]
fn a_finger_held_still_on_a_model_picks_and_set_to_paint_it_paints() {
    let s = CanvasSession::open_model(TWO_MESHES.into(), "two.obj".into(), Vec::new(), "Two".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    s.touch_down(1, PointerKind::Finger, vec![sample(60.0, 100.0, 0.0)]);
    let out = s.touch_tick(10_000.0);
    assert!(out.iter().any(|c| matches!(c, crate::TouchCommand::Pick { .. })), "{out:?}");
    s.touch_up(1, vec![sample(60.0, 100.0, 10_016.0)]);
    // set to paint, a finger paints and with the modify button held it turns the model
    s.set_touch_settings(crate::TouchSettings { model_finger_paint: true, ..Default::default() });
    s.touch_down(2, PointerKind::Finger, vec![sample(40.0, 100.0, 20_000.0)]);
    frame(&s, 200);
    s.touch_move(2, (1..20).map(|i| sample(40.0 + i as f32 * 6.0, 100.0, 20_000.0 + f64::from(i) * 8.0)).collect());
    frame(&s, 200);
    s.touch_up(2, vec![sample(160.0, 100.0, 20_200.0)]);
    frame(&s, 200);
    assert!(painted(&s) > 50);
    let orbit = |s: &CanvasSession| s.shared.lock().scene.as_ref().and_then(|c| c.view.as_ref()).map(|v| v.orbit);
    let kept = (painted(&s), orbit(&s));
    s.set_modify_held(true);
    s.touch_down(3, PointerKind::Finger, vec![sample(70.0, 100.0, 30_000.0)]);
    s.touch_move(3, vec![sample(130.0, 100.0, 30_016.0)]);
    s.touch_up(3, vec![sample(130.0, 100.0, 30_032.0)]);
    s.set_modify_held(false);
    frame(&s, 200);
    assert_eq!(painted(&s), kept.0);
    assert_ne!(orbit(&s), kept.1);
}

#[test]
fn the_2d_texture_shows_and_paints_flat() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 128).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    assert!(!s.model_flat());
    s.set_model_flat(true);
    assert!(s.model_flat() && !s.shared.lock().scene_shown());
    // the texture fills the window: a stroke across it paints there as on any canvas
    s.stroke_begin(PointerKind::Stylus, vec![sample(20.0, 20.0, 0.0)]);
    s.stroke_add((1..20).map(|i| sample(20.0 + i as f32 * 8.0, 20.0, f64::from(i) * 8.0)).collect());
    s.stroke_end(vec![sample(180.0, 20.0, 170.0)]);
    let flat = painted(&s);
    assert!(flat > 100, "{flat}");
    // the model again: the turn still works and the paint stays
    s.set_model_flat(false);
    assert!(!s.model_flat() && s.shared.lock().scene_shown());
    s.turn_model(50.0, 0.0);
    frame(&s, 200);
    assert_eq!(painted(&s), flat);
}

#[test]
fn the_studio_adds_moves_and_keeps_lights() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    // two white lights to begin with; four at most
    assert_eq!(s.model_lights().len(), 2);
    assert!(s.model_lights().iter().all(|l| l.saturation == 0.0));
    assert!(s.add_model_light());
    assert!(s.duplicate_model_light(0));
    assert!(!s.add_model_light() && !s.duplicate_model_light(1));
    assert_eq!(s.model_lights().len(), 4);
    let copy = s.model_lights()[3];
    assert_eq!((copy.y, copy.intensity), (s.model_lights()[0].y, s.model_lights()[0].intensity));
    s.delete_model_light(3);
    s.delete_model_light(9);
    assert_eq!(s.model_lights().len(), 3);
    let red = super::ModelLight { hue: 0.0, saturation: 1.0, intensity: 2.0, ..s.model_lights()[2] };
    s.set_model_light(2, red);
    assert_eq!(s.model_lights()[2].intensity, 1.0);
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    s.set_lighting_studio(true);
    frame(&s, 200);
    // the new light, picked where its cube shows, dragged across the screen
    let at = s.model_light_on_screen(2).unwrap();
    assert_eq!(s.model_light_at(at[0] + 2.0, at[1] - 2.0), Some(2));
    assert_eq!(s.model_light_at(at[0] + 150.0, at[1] + 150.0), None);
    s.move_model_light(2, 20.0, 0.0);
    let moved = s.model_light_on_screen(2).unwrap();
    assert!((moved[0] - at[0] - 20.0).abs() < 0.5 && (moved[1] - at[1]).abs() < 0.5, "{at:?} -> {moved:?}");
    // the camera goes round and nearer; the light goes with the view
    s.studio_turn(40.0, 0.0);
    s.studio_zoom(1.5);
    frame(&s, 200);
    assert_ne!(s.model_light_on_screen(2).unwrap(), moved);
    s.set_lighting_studio(false);
    frame(&s, 200);
    // its one mesh, hidden and kept so
    let info = s.model_info().unwrap();
    assert_eq!(info.submeshes, [super::SubmeshInfo { name: "Mesh 1".into(), texture_set: 0 }]);
    assert!(s.submesh_thumbnail(0, 32).unwrap().rgba.chunks(4).any(|p| p[3] == 255));
    s.set_submesh_hidden(0, true);
    assert_eq!(s.submeshes_hidden(), [true]);
    frame(&s, 200);
    // kept with the model
    let dir = std::env::temp_dir().join(format!("procraft-lights-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    s.save(dir.to_string_lossy().into_owned()).unwrap();
    let again = CanvasSession::open(dir.to_string_lossy().into_owned()).unwrap();
    assert_eq!(again.model_lights(), s.model_lights());
    assert_eq!(again.submeshes_hidden(), [true]);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_turntable_pulls_back_half_way_and_exports_as_gif_and_png() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let turntable = super::Turntable { swing: false, seconds: 1.0, zoom: 1.0, ease: 1.0, environment: false };
    let covered = |t: super::Turntable, i: u32| {
        let p = s.model_turntable_picture(i, 8, t, 100, 100, false).unwrap();
        let s = crate::SURROUND.map(|c| (c * 255.0).round() as i32);
        p.rgba.chunks(4).filter(|px| (0..3).any(|k| (i32::from(px[k]) - s[k]).abs() > 12)).count()
    };
    let (near, far) = (covered(turntable, 0), covered(turntable, 4));
    assert!(far * 2 < near, "pulled back: {near} -> {far}");
    let still = super::Turntable { zoom: 0.0, ..turntable };
    let (a, b) = (covered(still, 0), covered(still, 4));
    assert!(a.abs_diff(b) * 10 < a, "no pull: {a} vs {b}");
    let dir = std::env::temp_dir();
    let gif = dir.join(format!("procraft-turn-{}.gif", std::process::id()));
    s.export_model_gif(gif.to_string_lossy().into_owned(), turntable, 40, 30, 8.0).unwrap();
    assert!(std::fs::read(&gif).unwrap().starts_with(b"GIF89a"));
    let png = dir.join(format!("procraft-turn-{}.png", std::process::id()));
    s.export_model_apng(png.to_string_lossy().into_owned(), turntable, 40, 30, 8.0).unwrap();
    assert!(std::fs::read(&png).unwrap().get(1..4) == Some(b"PNG".as_slice()));
    let _ = (std::fs::remove_file(&gif), std::fs::remove_file(&png));
}

#[test]
fn roughness_and_metalness_are_painted_in_their_own_layers() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    dab(&s);
    let colour = painted(&s);
    assert!(colour > 20, "{colour}");
    // roughness: the same layers, the dab on the colour laid down there too
    assert_eq!(s.model_channel(), 0);
    s.set_model_channel(1);
    assert_eq!(s.model_channel(), 1);
    assert!(painted(&s).abs_diff(colour) * 10 < colour, "{} {colour}", painted(&s));
    s.stroke_begin(PointerKind::Stylus, vec![sample(70.0, 100.0, 0.0)]);
    s.stroke_end(vec![sample(130.0, 100.0, 50.0)]);
    frame(&s, 200);
    let rough = painted(&s);
    assert!(rough > colour, "{rough}");
    // a layer added here is a layer of the colour too
    let added = s.add_layer();
    assert!(added != 0);
    s.set_model_channel(0);
    assert_eq!(painted(&s), colour);
    assert!(s.layers().iter().any(|l| l.id == added));
    // exports take every channel whichever is painted on: the colour and roughness as
    // painted, the metalness painted with the brush's (none, as the plain one)
    s.set_model_channel(2);
    assert_eq!(painted(&s), 0);
    let dir = std::env::temp_dir().join(format!("procraft-channels-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let pngs = s.export_model_textures(dir.join("t").to_string_lossy().into_owned()).unwrap();
    let names: Vec<String> =
        pngs.iter().filter_map(|p| std::path::Path::new(p).file_name()?.to_str().map(str::to_owned)).collect();
    let set = super::file_stem(&s.model_info().unwrap().texture_sets[0], "Material");
    assert_eq!(names, ["Color", "Roughness", "Metallic"].map(|c| format!("{set}-{c}.png")));
    let read = |p: &str| {
        let mut reader =
            png::Decoder::new(std::io::BufReader::new(std::fs::File::open(p).unwrap())).read_info().unwrap();
        let mut px = vec![0; reader.output_buffer_size().unwrap()];
        reader.next_frame(&mut px).unwrap();
        px.chunks(4).map(|p| (p[0], p[3])).collect::<Vec<_>>()
    };
    let plain = (255, 255);
    assert!(read(&pngs[0]).iter().any(|&p| p != plain));
    assert!(read(&pngs[1]).iter().any(|&p| p != plain));
    assert!(read(&pngs[2]).iter().all(|&p| p == (0, 255)));
    // kept with the model, saved while painting metalness
    s.set_model_channel(1);
    s.save(dir.join("a").to_string_lossy().into_owned()).unwrap();
    assert_eq!(s.model_channel(), 1);
    assert_eq!(painted(&s), rough);
    assert!(dir.join("a").join("sets").join("0").join("c1").exists());
    let again = CanvasSession::open(dir.join("a").to_string_lossy().into_owned()).unwrap();
    assert!(shown(&again));
    assert_eq!(again.model_channel(), 0);
    assert_eq!(painted(&again), colour);
    again.set_model_channel(1);
    assert_eq!(painted(&again), rough);
    frame(&again, 200);
    let _ = std::fs::remove_dir_all(&dir);
}

/// The current channel's texels as painted: each one's grey (unpremultiplied) and alpha.
fn channel_texels(s: &CanvasSession) -> Vec<(u8, u8)> {
    let mut guard = s.shared.lock();
    let st = &mut *guard;
    let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
    canvas.render(gpu, &mut st.doc, None);
    let px = canvas.read_composite(gpu).unwrap();
    px.chunks(4)
        .map(|p| (if p[3] == 0 { 0 } else { (u16::from(p[0]) * 255 / u16::from(p[3])) as u8 }, p[3]))
        .collect()
}

/// The grey most of the current channel's painted texels have (the middle of a dab).
fn covered_grey(s: &CanvasSession) -> Option<u8> {
    let fresh = fresh(s)[0];
    let mut counts = [0usize; 256];
    for (v, a) in channel_texels(s) {
        if a == 255 && v != fresh {
            counts[usize::from(v)] += 1;
        }
    }
    let (grey, &n) = counts.iter().enumerate().max_by_key(|&(_, n)| *n)?;
    (n > 0).then_some(grey as u8)
}

/// Sets the paint brush's material controls as the studio does.
fn materials(s: &CanvasSession, roughness: f32, metallic: f32) {
    s.studio_begin();
    s.set_studio_setting("roughness_amount".into(), roughness);
    s.set_studio_setting("metallic_amount".into(), metallic);
    s.studio_end(true);
}

#[test]
fn a_stroke_on_the_colour_lays_down_the_brushs_roughness_and_metalness() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    materials(&s, 0.2, 0.8);
    frame(&s, 200);
    dab(&s);
    let colour = painted(&s);
    assert!(colour > 20, "{colour}");
    // each the brush's amount where the stroke covers, nothing elsewhere
    s.set_model_channel(1);
    assert_eq!(covered_grey(&s), Some(51));
    assert!(painted(&s).abs_diff(colour) * 10 < colour, "{} {colour}", painted(&s));
    s.set_model_channel(2);
    assert_eq!(covered_grey(&s), Some(204));
    // a stroke on a channel paints that channel alone, with the channel's own colour
    s.set_color(1.0, 1.0, 1.0);
    s.stroke_begin(PointerKind::Stylus, vec![sample(60.0, 60.0, 0.0)]);
    s.stroke_end(vec![sample(140.0, 60.0, 50.0)]);
    frame(&s, 200);
    let metal = painted(&s);
    assert!(metal > colour);
    s.set_model_channel(1);
    assert!(painted(&s).abs_diff(colour) * 10 < colour);
    s.set_model_channel(0);
    assert_eq!(painted(&s), colour);
}

#[test]
fn the_brushs_material_source_shapes_what_it_lays_down() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    let size = crate::studio::source_size() as usize;
    s.studio_begin();
    // the metalness through a mid grey, the roughness through black
    assert!(s.set_studio_source(crate::studio::SourceKind::Metallic, vec![128; size * size]));
    assert!(s.set_studio_source(crate::studio::SourceKind::Roughness, vec![0; size * size]));
    s.set_studio_setting("metallic_amount".into(), 1.0);
    s.set_studio_setting("roughness_amount".into(), 1.0);
    s.studio_end(true);
    frame(&s, 200);
    dab(&s);
    s.set_model_channel(2);
    let grey = covered_grey(&s).unwrap();
    assert!(grey.abs_diff(128) <= 2, "{grey}");
    s.set_model_channel(1);
    assert_eq!(covered_grey(&s), Some(0));
}

#[test]
fn a_grain_fixed_to_the_canvas_shows_in_the_colour_read_along_the_models_axes() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    // stripes down the grain image, eight to a tile, fully deep and fixed to the canvas
    let n = crate::studio::source_size() as usize;
    let stripes: Vec<u8> =
        (0..n * n).map(|i| if ((i % n) / (n / 8)).is_multiple_of(2) { 0 } else { 255 }).collect();
    s.studio_begin();
    assert!(s.set_studio_source(crate::studio::SourceKind::Grain, stripes));
    s.set_studio_setting("grain_behavior".into(), 1.0);
    s.set_studio_setting("grain_depth".into(), 1.0);
    s.set_studio_setting("grain_scale".into(), 0.5);
    s.set_studio_setting("roughness_amount".into(), 0.4);
    s.studio_end(true);
    frame(&s, 200);
    s.stroke_begin(PointerKind::Stylus, vec![sample(60.0, 100.0, 0.0)]);
    s.stroke_add((1..20).map(|i| sample(60.0 + i as f32 * 4.0, 100.0, f64::from(i) * 8.0)).collect());
    s.stroke_end(vec![sample(140.0, 100.0, 170.0)]);
    frame(&s, 200);
    // the colour has gaps where the grain is dark; the roughness takes the whole stroke
    let colour = painted(&s);
    s.set_model_channel(1);
    let rough = painted(&s);
    s.set_model_channel(0);
    assert!(rough > 100, "{rough}");
    assert!(colour > 20 && colour * 10 < rough * 8, "colour {colour}, roughness {rough}");
    // undone together
    s.undo();
    assert_eq!(painted(&s), 0);
}

#[test]
fn undo_and_redo_take_a_strokes_channels_together() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    materials(&s, 0.4, 0.6);
    frame(&s, 200);
    dab(&s);
    let colour = painted(&s);
    let all = |s: &CanvasSession| {
        let counts: Vec<usize> = (0..3)
            .map(|c| {
                s.set_model_channel(c);
                painted(s)
            })
            .collect();
        s.set_model_channel(0);
        counts
    };
    let painted_all = all(&s);
    assert!(painted_all.iter().all(|&n| n > 20), "{painted_all:?}");
    s.undo();
    assert_eq!(all(&s), [0, 0, 0]);
    s.redo();
    assert_eq!(all(&s), painted_all);
    // undone from the roughness, the colour and metalness go too
    s.set_model_channel(1);
    s.undo();
    assert_eq!(painted(&s), 0);
    assert_eq!(all(&s), [0, 0, 0]);
    // an eraser on the colour takes all three away
    s.redo();
    assert_eq!(all(&s), painted_all);
    let cover = |s: &CanvasSession| {
        let sums: Vec<u32> = (0..3)
            .map(|c| {
                s.set_model_channel(c);
                channel_texels(s).iter().map(|t| u32::from(t.1)).sum()
            })
            .collect();
        s.set_model_channel(0);
        sums
    };
    let before = cover(&s);
    s.stroke_begin(PointerKind::StylusEraser, vec![sample(80.0, 100.0, 0.0)]);
    s.stroke_end(vec![sample(120.0, 100.0, 50.0)]);
    frame(&s, 200);
    let after = cover(&s);
    assert!(after.iter().zip(&before).all(|(a, b)| a * 10 < b * 9), "{after:?} {before:?}");
    assert_eq!(colour, painted_all[0]);
}

#[test]
fn an_obj_takes_its_material_file_and_images_from_the_files_picked_with_it() {
    let obj = format!("mtllib Cube Parts.mtl\nusemtl body\n{CUBE}");
    let file = |name: &str, bytes: &[u8]| ModelFile { name: name.into(), bytes: bytes.to_vec() };
    let mtl = b"newmtl body\nKd 1 0 0\nmap_Kd -bm 1 textures/Body.png\nmap_Pr rough.png\n";
    let files = vec![
        file("other.mtl", b"newmtl body\nKd 0 0 1\n"),
        file("cube parts.MTL", mtl),
        file("body.png", b"colour"),
        file("rough.png", b"rough"),
        file("unused.png", b"unused"),
    ];
    let s = CanvasSession::open_model(obj.clone().into(), "cube.obj".into(), files, "Cube".into(), 64).unwrap();
    // the one it names, whatever the case; its maps found by their names alone
    let found: Vec<(u32, Vec<u8>)> = s.model_texture_files().into_iter().map(|f| (f.channel, f.bytes)).collect();
    assert_eq!(found, [(0, b"colour".to_vec()), (1, b"rough".to_vec())]);
    // given once
    assert!(s.model_texture_files().is_empty());
    // without the one it names, the only one picked
    let only = vec![file("whatever.mtl", mtl), file("rough.png", b"rough")];
    let s = CanvasSession::open_model(obj.into(), "cube.obj".into(), only, "Cube".into(), 64).unwrap();
    assert_eq!(s.model_texture_files().len(), 1);
}

/// A zip of `files` (name, contents), in order.
fn zipped(files: &[(&str, &[u8])]) -> Vec<u8> {
    let mut zip = procraft_engine::zip::ZipWriter::new();
    for (name, bytes) in files {
        zip.add(name, bytes);
    }
    zip.finish()
}

#[test]
fn a_zipped_obj_comes_in_with_its_material_file_and_images() {
    let obj = format!("mtllib cube.mtl\nusemtl body\n{CUBE}");
    let mtl =
        b"newmtl body\nmap_Kd textures/Body.png\nmap_roughness ./rough.png\nmap_tangentSpaceNormal flat.png\n";
    let zip = zipped(&[
        ("Cube Folder/", b""),
        ("__MACOSX/Cube Folder/._cube.obj", b"junk"),
        ("Cube Folder/cube.obj", obj.as_bytes()),
        ("Cube Folder/cube.mtl", mtl),
        ("Cube Folder/other/body.png", b"not this one"),
        ("Cube Folder/textures/body.png", b"colour"),
        ("Cube Folder/rough.png", b"rough"),
        ("Cube Folder/flat.png", b"normal"),
    ]);
    assert_eq!(super::zip_model_name(zip.clone()).as_deref(), Some("Cube Folder/cube.obj"));
    let s = CanvasSession::open_model(zip, "Cube Folder.zip".into(), Vec::new(), "Cube".into(), 64).unwrap();
    let info = s.model_info().unwrap();
    assert_eq!((info.texture_sets.as_slice(), info.triangles), (["body".to_owned()].as_slice(), 12));
    // each map found by its path from the model, ignoring case; the normal map as channel 3
    let found: Vec<(u32, Vec<u8>)> = s.model_texture_files().into_iter().map(|f| (f.channel, f.bytes)).collect();
    assert_eq!(found, [(0, b"colour".to_vec()), (1, b"rough".to_vec()), (3, b"normal".to_vec())]);
    // kept as the model it holds
    let source = s.shared.lock().scene.as_ref().map(|sc| (sc.source.extension.clone(), sc.source.mtl.is_some()));
    assert_eq!(source, Some(("obj".to_owned(), true)));
}

#[test]
fn a_zips_model_is_the_one_nearest_its_top() {
    let deep = zipped(&[("parts/inner.obj", CUBE.as_bytes()), ("outer.OBJ", CUBE.as_bytes())]);
    assert_eq!(super::zip_model_name(deep).as_deref(), Some("outer.OBJ"));
    let usd = zipped(&[("readme.txt", b"hi"), ("scene/model.usdz", b"x")]);
    assert_eq!(super::zip_model_name(usd).as_deref(), Some("scene/model.usdz"));
    // nothing to paint, or another kind of package: not a model
    assert_eq!(super::zip_model_name(zipped(&[("picture.png", b"p")])), None);
    assert_eq!(super::zip_model_name(zipped(&[("Document.archive", b"d"), ("mesh.obj", CUBE.as_bytes())])), None);
    assert_eq!(super::zip_model_name(zipped(&[("artwork.json", b"{}"), ("model.obj", CUBE.as_bytes())])), None);
    assert_eq!(super::zip_model_name(b"not a zip".to_vec()), None);
    let none = CanvasSession::open_model(zipped(&[("a.png", b"p")]), "a.zip".into(), Vec::new(), "A".into(), 64);
    assert!(none.is_err());
}

#[test]
fn a_models_own_obj_zip_opens_again_textured() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    dab(&s);
    let path = std::env::temp_dir().join(format!("procraft-obj-zip-{}.zip", std::process::id()));
    s.export_model_obj(path.to_string_lossy().into_owned()).unwrap();
    let bytes = std::fs::read(&path).unwrap();
    let again = CanvasSession::open_model(bytes, "Cube.zip".into(), Vec::new(), "Again".into(), 64).unwrap();
    // its colour, roughness and metalness, as its material file maps them
    let channels: Vec<u32> = again.model_texture_files().iter().map(|f| f.channel).collect();
    assert_eq!(channels, [0, 1, 2]);
    let _ = std::fs::remove_file(&path);
}

#[test]
fn a_packaged_models_textures_come_in_as_its_base_layers() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    assert_eq!(s.layers().first().map(|l| l.name.clone()).as_deref(), Some("Base Layer"));
    assert!(s.model_texture_files().is_empty());
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    dab(&s);
    let path = std::env::temp_dir().join(format!("procraft-in-{}.usdz", std::process::id()));
    s.export_model_usdz(path.to_string_lossy().into_owned()).unwrap();
    // opened again, its textures come back as their images' files: the colour, and the
    // roughness and metalness from their red
    let bytes = std::fs::read(&path).unwrap();
    let again = CanvasSession::open_model(bytes, "cube.usdz".into(), Vec::new(), "Again".into(), 64).unwrap();
    let files = again.model_texture_files();
    let kinds: Vec<(u32, u32, i32)> = files.iter().map(|f| (f.texture_set, f.channel, f.component)).collect();
    assert_eq!(kinds, [(0, 0, -1), (0, 1, 0), (0, 2, 0)]);
    // decoded into the Base Layer, each channel's
    let mut reader = png::Decoder::new(std::io::Cursor::new(files[0].bytes.clone())).read_info().unwrap();
    let mut px = vec![0; reader.output_buffer_size().unwrap()];
    reader.next_frame(&mut px).unwrap();
    assert_eq!(px.len(), 64 * 64 * 4);
    assert!(!again.set_model_texture(0, 0, vec![0; 8]));
    assert_eq!(again.load_model_textures(), 3);
    assert!(shown(&again));
    frame(&again, 200);
    // the exported picture (opaque, over white) in place of the material's colour
    let shown_px = {
        let mut guard = again.shared.lock();
        let st = &mut *guard;
        let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
        canvas.render(gpu, &mut st.doc, None);
        canvas.read_composite(gpu).unwrap()
    };
    assert!(shown_px == px);
    assert!(painted(&again) > 20);
    let _ = std::fs::remove_file(&path);
}

#[test]
fn a_models_base_layers_start_as_its_materials_colour_roughness_and_metalness() {
    let two = format!(
        "mtllib m.mtl
usemtl red
{}",
        CUBE.replacen(
            "f 4/1",
            "usemtl mapped
f 4/1",
            1
        )
    );
    let mtl = b"newmtl red
Kd 1 0 0
Pr 0.25
Pm 0.5
newmtl mapped
Kd 0 0 1
map_Kd blue.png
";
    let files = vec![
        ModelFile { name: "m.mtl".into(), bytes: mtl.to_vec() },
        ModelFile { name: "blue.png".into(), bytes: b"an image".to_vec() },
    ];
    let s = CanvasSession::open_model(two.into_bytes(), "two.obj".into(), files, "Two".into(), 16).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let layer = |s: &CanvasSession| {
        let mut guard = s.shared.lock();
        let st = &mut *guard;
        let id = st.doc.layers().first().unwrap().id;
        let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
        canvas.render(gpu, &mut st.doc, None);
        canvas.read_layer(gpu, id).unwrap()
    };
    // the Base Layer itself, opaque: the colour, and each channel's own value
    assert!(layer(&s).chunks(4).all(|p| p == [255, 0, 0, 255]));
    s.set_model_channel(1);
    assert!(layer(&s).chunks(4).all(|p| p == [64, 64, 64, 255]));
    s.set_model_channel(2);
    assert!(layer(&s).chunks(4).all(|p| p == [128, 128, 128, 255]));
    // a set whose material brings an image waits for it, its Base Layer clear
    s.set_model_channel(0);
    s.choose_texture_set(1);
    assert!(layer(&s).chunks(4).all(|p| p[3] == 0));
    // kept so when saved before it is drawn, and opened again so
    let dir = std::env::temp_dir().join(format!("procraft-fill-{}", std::process::id()));
    s.choose_texture_set(0);
    s.save(dir.to_string_lossy().into_owned()).unwrap();
    let again = CanvasSession::open(dir.to_string_lossy().into_owned()).unwrap();
    assert!(shown(&again));
    frame(&again, 200);
    assert!(layer(&again).chunks(4).all(|p| p == [255, 0, 0, 255]));
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn two_fingers_bring_the_model_nearer_and_move_it() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let orbit = |s: &CanvasSession| s.shared.lock().scene.as_ref().and_then(|sc| sc.orbit()).unwrap();
    let before = orbit(&s);
    // spread to twice as far apart: half as far away
    s.view_gesture(100.0, 100.0, 0.0, 0.0, 2.0, 0.0);
    let nearer = orbit(&s);
    assert!((nearer.distance - before.distance / 2.0).abs() < 1e-4);
    // moved right together: the model goes right, so the camera's target goes left of it
    s.view_gesture(100.0, 100.0, 50.0, 0.0, 1.0, 0.0);
    let moved = orbit(&s);
    assert!(moved.target[0] < nearer.target[0] - 1e-3, "{:?}", moved.target);
    assert!((moved.target[1] - nearer.target[1]).abs() < 1e-4);
    // the cube's middle is now right of the window's
    let (gpu_ok, middle_x) = {
        let st = s.shared.lock();
        let o = st.scene.as_ref().and_then(|sc| sc.orbit()).unwrap();
        let m = o.view_proj(1.0);
        let clip_x = m[3][0];
        let w = m[3][3];
        (st.gpu.is_some(), clip_x / w)
    };
    assert!(gpu_ok && middle_x > 0.0, "{middle_x}");
}

#[test]
fn a_model_exports_as_obj_with_its_textures() {
    let two = CUBE
        .replacen(
            "f 1/1",
            "usemtl front
f 1/1",
            1,
        )
        .replacen(
            "f 1/1 2/2",
            "usemtl side wall
f 1/1 2/2",
            1,
        );
    let s =
        CanvasSession::open_model(two.into_bytes(), "two.obj".into(), Vec::new(), "Two cubes".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    dab(&s);
    let path = std::env::temp_dir().join(format!("procraft-obj-{}.zip", std::process::id()));
    s.export_model_obj(path.to_string_lossy().into_owned()).unwrap();
    let bytes = std::fs::read(&path).unwrap();
    let zip = procraft_engine::zip::ZipReader::new(&bytes).unwrap();
    let mut names: Vec<&str> = zip.names().collect();
    names.sort_unstable();
    assert_eq!(
        names,
        [
            "Two_cubes.mtl",
            "Two_cubes.obj",
            "front-color.png",
            "front-metallic.png",
            "front-roughness.png",
            "side_wall-color.png",
            "side_wall-metallic.png",
            "side_wall-roughness.png"
        ]
    );
    let obj = String::from_utf8(zip.read("Two_cubes.obj").unwrap()).unwrap();
    let mtl = String::from_utf8(zip.read("Two_cubes.mtl").unwrap()).unwrap();
    let mesh = procraft_engine::mesh::read_obj(&obj, Some(&mtl)).unwrap();
    assert_eq!(mesh.triangles.len(), 12);
    assert_eq!(mesh.materials[1].color_map.as_deref(), Some("side_wall-color.png"));
    assert_eq!(mesh.materials[1].roughness_map.as_deref(), Some("side_wall-roughness.png"));
    assert_eq!(mesh.materials[1].metallic_map.as_deref(), Some("side_wall-metallic.png"));
    // the painted set's picture has the stroke on white
    let png = zip.read("front-color.png").unwrap();
    let mut reader = png::Decoder::new(std::io::Cursor::new(png)).read_info().unwrap();
    let mut px = vec![0; reader.output_buffer_size().unwrap()];
    reader.next_frame(&mut px).unwrap();
    assert!(px.chunks(4).all(|p| p[3] == 255));
    assert!(px.chunks(4).any(|p| p[0] < 128) && px.chunks(4).any(|p| p[0] == 255));
    let _ = std::fs::remove_file(&path);
    // the pictures on their own, and the model as shown
    let dir = std::env::temp_dir().join(format!("procraft-textures-{}", std::process::id()));
    let pngs = s.export_model_textures(dir.to_string_lossy().into_owned()).unwrap();
    assert_eq!(pngs.len(), 6);
    assert!(pngs.iter().all(|p| std::path::Path::new(p).exists()));
    assert!(pngs.iter().any(|p| p.ends_with("side_wall-Roughness.png")));
    let _ = std::fs::remove_dir_all(&dir);
    let render = std::env::temp_dir().join(format!("procraft-render-{}.png", std::process::id()));
    s.export_model_render(render.to_string_lossy().into_owned(), true).unwrap();
    let mut reader =
        png::Decoder::new(std::io::BufReader::new(std::fs::File::open(&render).unwrap())).read_info().unwrap();
    assert_eq!((reader.info().width, reader.info().height), (200, 200));
    let mut px = vec![0; reader.output_buffer_size().unwrap()];
    reader.next_frame(&mut px).unwrap();
    // the model in the middle, nothing at the corner
    assert_eq!(px[3], 0);
    assert_eq!(px[(100 * 200 + 100) * 4 + 3], 255);
    let _ = std::fs::remove_file(&render);
    let tiff = std::env::temp_dir().join(format!("procraft-render-{}.tif", std::process::id()));
    s.export_model_tiff(tiff.to_string_lossy().into_owned()).unwrap();
    assert!(
        std::fs::read(&tiff).unwrap().starts_with(b"II*\0") || std::fs::read(&tiff).unwrap().starts_with(b"MM\0*")
    );
    let _ = std::fs::remove_file(&tiff);
    // a turntable's frames: the model turned, half way round the back, and round again
    let turntable = super::Turntable { swing: false, seconds: 1.0, zoom: 0.0, ease: 0.0, environment: false };
    let first = s.model_turntable_picture(0, 4, turntable, 100, 100, true).unwrap();
    let half = s.model_turntable_picture(2, 4, turntable, 100, 100, true).unwrap();
    assert_eq!((first.width, first.height, first.rgba.len()), (100, 100, 100 * 100 * 4));
    assert!(first.rgba.chunks(4).all(|p| p[3] == 255));
    assert_ne!(first.rgba, half.rgba);
    let again = s.model_turntable_picture(4, 4, turntable, 100, 100, true).unwrap();
    assert_eq!(first.rgba, again.rgba);
    // as a USDZ: the layer and both sets' images
    s.export_model_usdz(path.to_string_lossy().into_owned()).unwrap();
    let bytes = std::fs::read(&path).unwrap();
    let zip = procraft_engine::zip::ZipReader::new(&bytes).unwrap();
    assert_eq!(
        zip.names().collect::<Vec<_>>(),
        [
            "model.usda",
            "textures/front-color.png",
            "textures/front-roughness.png",
            "textures/front-metallic.png",
            "textures/side_wall-color.png",
            "textures/side_wall-roughness.png",
            "textures/side_wall-metallic.png"
        ]
    );
    let _ = std::fs::remove_file(&path);
}

/// Gives `s` a headless GPU and a 200 px square window; false without a GPU.
fn shown(s: &CanvasSession) -> bool {
    let gpu = match Gpu::headless() {
        Ok(g) => g,
        Err(e) => {
            eprintln!("skipped: {e}");
            return false;
        }
    };
    let mut st = s.shared.lock();
    st.window_size = (200, 200);
    st.gpu = Some(gpu);
    st.ensure_canvas();
    true
}

/// What the current part's roughness and metalness canvases hold on the GPU, bytes (a texture
/// they share counted once), apart from their undo history; what their histories hold; and
/// which of the two are made.
fn channel_bytes(s: &CanvasSession) -> (u64, u64, [bool; 2]) {
    let st = s.shared.lock();
    let scene = st.scene.as_ref().unwrap();
    let pair = scene.channels.get(scene.current).unwrap();
    let mut all: Vec<wgpu::Texture> = Vec::new();
    let mut history = 0;
    for c in pair.iter().flatten().filter_map(|set| set.canvas.as_ref()) {
        history += c.history_bytes();
        for t in c.textures() {
            if !all.contains(&t) {
                all.push(t);
            }
        }
    }
    (procraft_gpu::textures_bytes(&all) - history, history, [pair[0].is_some(), pair[1].is_some()])
}

#[test]
fn a_models_roughness_and_metalness_hold_their_layers_and_composites_and_share_a_stroke() {
    let side = 512u64;
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 512).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    // the plain brush on a plain material: rough as the brush, as metal as the material
    // (none), so the metalness isn't made
    dab(&s);
    assert_eq!(channel_bytes(&s).2, [true, false]);
    // a stroke under way on both: each its layer and composite (RGBA8) and a few small
    // textures, one stroke texture (half floats) between them, no brush images
    materials(&s, 0.2, 0.8);
    s.stroke_begin(PointerKind::Stylus, vec![sample(90.0, 100.0, 0.0)]);
    frame(&s, 200);
    // the two stand-ins for brush images a texel a layer
    let layers = s.shared.lock().gpu.as_ref().map_or(1, |g| u64::from(g.array_layers_least()));
    let small = 1024 + 1 + 2 * layers;
    let (bytes, _, made) = channel_bytes(&s);
    assert_eq!(made, [true, true]);
    // (on a GPU that can't draw into half floats a stroke keeps more: counted for half floats)
    if s.shared.lock().gpu.as_ref().is_some_and(procraft_gpu::Gpu::draws_half_floats) {
        assert_eq!(bytes, 2 * (side * side * 8 + small) + side * side * 8);
    }
    s.stroke_end(vec![sample(110.0, 100.0, 50.0)]);
    frame(&s, 200);
    assert_eq!(channel_bytes(&s).0, bytes);
    // painting the roughness itself gives it its brush images, and back on the colour it
    // lets them go
    s.set_model_channel(1);
    assert!(s.shared.lock().canvas.as_ref().unwrap().has_brush_images());
    s.set_model_channel(0);
    assert_eq!(channel_bytes(&s).0, bytes);
}

/// The undo history's bytes of the current part's colour, roughness and metalness.
fn history_bytes(s: &CanvasSession) -> [u64; 3] {
    let st = s.shared.lock();
    let scene = st.scene.as_ref().unwrap();
    let pair = scene.channels.get(scene.current).unwrap();
    let of = |k: usize| pair[k].as_ref().and_then(|set| set.canvas.as_ref()).map_or(0, |c| c.history_bytes());
    [st.canvas.as_ref().unwrap().history_bytes(), of(0), of(1)]
}

#[test]
fn a_strokes_undo_step_keeps_only_the_texels_it_reached() {
    // every face of the cube covers the whole texture: a dab on the front lies under all of
    // it on the screen, and once took a step of the whole texture in each channel
    let side = 512u32;
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), side).unwrap();
    if !shown(&s) {
        return;
    }
    materials(&s, 0.2, 0.8);
    frame(&s, 200);
    let fresh = fresh(&s);
    dab(&s);
    // the texels it changed, and the tiles (16 texels, a 32nd of the side) they lie in
    let changed: Vec<usize> = {
        let mut guard = s.shared.lock();
        let st = &mut *guard;
        let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
        canvas.render(gpu, &mut st.doc, None);
        let px = canvas.read_composite(gpu).unwrap();
        px.chunks(4).enumerate().filter(|(_, p)| *p != fresh).map(|(i, _)| i).collect()
    };
    assert!(changed.len() > 100, "{}", changed.len());
    let tile = 16usize;
    let mut tiles: Vec<usize> = changed
        .iter()
        .map(|i| (i / side as usize / tile) * (side as usize / tile) + (i % side as usize) / tile)
        .collect();
    tiles.sort_unstable();
    tiles.dedup();
    let at_least = (tiles.len() * tile * tile * 4) as u64;
    // each channel's step the same tiles, a few more than those it visibly changed at most
    // (where the stroke is too faint to change a byte), far from the whole texture
    let [colour, rough, metal] = history_bytes(&s);
    assert_eq!((colour, metal), (rough, rough));
    assert!(colour >= at_least && colour <= at_least * 2, "{colour} for {at_least}");
    assert!(colour * 8 < u64::from(side * side * 4), "{colour}");
    // undone and redone as exactly as before
    let all = |s: &CanvasSession| {
        let counts: Vec<usize> = (0..3)
            .map(|c| {
                s.set_model_channel(c);
                painted(s)
            })
            .collect();
        s.set_model_channel(0);
        counts
    };
    let before = all(&s);
    s.undo();
    assert_eq!(all(&s), [0, 0, 0]);
    s.redo();
    assert_eq!(all(&s), before);
}

/// A dab in the middle of the window.
fn dab(s: &CanvasSession) {
    s.stroke_begin(PointerKind::Stylus, vec![sample(90.0, 100.0, 0.0)]);
    s.stroke_end(vec![sample(110.0, 100.0, 50.0)]);
    frame(s, 200);
}

#[test]
fn a_models_normal_and_occlusion_maps_are_kept_and_exported() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    // channels 3 and 4: the normal and occlusion maps, the texture size square
    assert!(!s.set_model_texture(0, 3, vec![0; 8]));
    assert!(!s.set_model_texture(0, 5, [128, 128, 255, 255].repeat(64 * 64)));
    assert!(s.set_model_texture(0, 3, [128, 128, 255, 255].repeat(64 * 64)));
    assert!(s.set_model_texture(0, 4, [200, 200, 200, 255].repeat(64 * 64)));
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let set = super::file_stem(&s.model_info().unwrap().texture_sets[0], "Material");
    let dir = std::env::temp_dir().join(format!("procraft-maps-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    // in the OBJ's zip, named in its material file
    let obj = dir.join("cube.zip");
    s.export_model_obj(obj.to_string_lossy().into_owned()).unwrap();
    let bytes = std::fs::read(&obj).unwrap();
    let zip = procraft_engine::zip::ZipReader::new(&bytes).unwrap();
    assert!(zip.read(&format!("{set}-normal.png")).is_some());
    assert!(zip.read(&format!("{set}-ambient_occlusion.png")).is_some());
    let mtl = String::from_utf8(zip.read("Cube.mtl").unwrap()).unwrap();
    assert!(mtl.contains(&format!("map_tangentSpaceNormal {set}-normal.png")), "{mtl}");
    assert!(mtl.contains(&format!("map_ao {set}-ambient_occlusion.png")), "{mtl}");
    // shared as textures
    let pngs = s.export_model_textures(dir.join("t").to_string_lossy().into_owned()).unwrap();
    assert_eq!(pngs.len(), 5);
    assert!(pngs.iter().any(|p| p.ends_with(&format!("{set}-AmbientOcclusion.png"))));
    // kept with the artwork, and back when it opens
    s.save(dir.join("a").to_string_lossy().into_owned()).unwrap();
    assert!(dir.join("a").join("maps").join("0-normal.png").exists());
    let again = CanvasSession::open(dir.join("a").to_string_lossy().into_owned()).unwrap();
    let kept = again.shared.lock().scene.as_ref().unwrap().surface_maps.clone();
    assert_eq!(kept[0][0].as_deref().map(|m| m[..4].to_vec()), Some(vec![128, 128, 255, 255]));
    assert_eq!(kept[0][1].as_deref().map(|m| m[..4].to_vec()), Some(vec![200, 200, 200, 255]));
    // a USDZ carries them, and brings them in again
    let usdz = dir.join("cube.usdz");
    s.export_model_usdz(usdz.to_string_lossy().into_owned()).unwrap();
    let back =
        CanvasSession::open_model(std::fs::read(&usdz).unwrap(), "cube.usdz".into(), Vec::new(), "Back".into(), 64)
            .unwrap();
    let channels: Vec<u32> = back.model_texture_files().iter().map(|f| f.channel).collect();
    assert_eq!(channels, [0, 1, 2, 3, 4]);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_model_exports_as_a_glb_for_viewers() {
    let two = CUBE.replacen("f 1/1", "usemtl front\nf 1/1", 1).replacen("f 1/1 2/2", "usemtl sides\nf 1/1 2/2", 1);
    let s = CanvasSession::open_model(two.into_bytes(), "two.obj".into(), Vec::new(), "Two".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let path = std::env::temp_dir().join(format!("procraft-glb-{}.glb", std::process::id()));
    s.export_model_glb(path.to_string_lossy().into_owned()).unwrap();
    let glb = std::fs::read(&path).unwrap();
    let _ = std::fs::remove_file(&path);
    assert_eq!(&glb[0..4], b"glTF");
    let json_len = u32::from_le_bytes(glb[12..16].try_into().unwrap()) as usize;
    let doc: serde_json::Value = serde_json::from_slice(&glb[20..20 + json_len]).unwrap();
    let bin = &glb[20 + json_len + 8..];
    assert_eq!(doc["materials"].as_array().unwrap().len(), 2);
    assert_eq!(doc["materials"][0]["name"], "front");
    // the roughness (1, as nothing says otherwise) in green and the metalness (0) in blue
    let texture =
        doc["materials"][0]["pbrMetallicRoughness"]["metallicRoughnessTexture"]["index"].as_u64().unwrap();
    let source = doc["textures"][texture as usize]["source"].as_u64().unwrap() as usize;
    let view = &doc["bufferViews"][doc["images"][source]["bufferView"].as_u64().unwrap() as usize];
    let offset = view["byteOffset"].as_u64().unwrap() as usize;
    let png = &bin[offset..offset + view["byteLength"].as_u64().unwrap() as usize];
    let mut reader = png::Decoder::new(std::io::Cursor::new(png)).read_info().unwrap();
    let mut px = vec![0; reader.output_buffer_size().unwrap()];
    reader.next_frame(&mut px).unwrap();
    assert!(px.chunks(4).all(|p| p[1] == 255 && p[2] == 0), "{:?}", &px[..4]);
}

#[test]
fn a_saved_model_exports_once_opened_again() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    dab(&s);
    let dir = std::env::temp_dir().join(format!("procraft-reopen-model-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    s.save(dir.join("a").to_string_lossy().into_owned()).unwrap();
    let again = CanvasSession::open(dir.join("a").to_string_lossy().into_owned()).unwrap();
    assert!(shown(&again));
    frame(&again, 200);
    let zip = dir.join("cube.zip").to_string_lossy().into_owned();
    again.export_model_obj(zip).unwrap();
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_model_saves_with_each_texture_set_and_how_it_was_seen() {
    // the cube's front, top and back in one material, its bottom and sides in another
    let two = CUBE.replacen("f 1/1", "usemtl front\nf 1/1", 1).replacen("f 1/1 2/2", "usemtl sides\nf 1/1 2/2", 1);
    let s = CanvasSession::open_model(two.into_bytes(), "two.OBJ".into(), Vec::new(), "Two".into(), 128).unwrap();
    assert_eq!(s.model_info().unwrap().texture_sets, ["front", "sides"]);
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    dab(&s);
    let front = painted(&s);
    assert!(front > 20, "{front}");
    // the right side turned to face the camera, painted in its own set
    s.choose_texture_set(1);
    assert_eq!(s.texture_set(), 1);
    assert_eq!(painted(&s), 0);
    s.turn_model(-100.0, 0.0);
    frame(&s, 200);
    dab(&s);
    let side = painted(&s);
    assert!(side > 20, "{side}");

    let dir = std::env::temp_dir().join(format!("procraft-model-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    assert!(!s.paint_through());
    s.set_paint_through(true);
    let sky = procraft_engine::environment::write_hdr(&procraft_engine::environment::Equirect::uniform([0.5; 3]));
    assert!(s.set_model_environment("coast".into(), sky));
    assert_eq!(s.model_exposure(), 1.0);
    s.set_model_exposure(9.0);
    assert_eq!(s.model_exposure(), 5.0);
    s.set_model_exposure(0.4);
    assert!(s.model_environment_shown());
    s.set_model_environment_shown(false);
    frame(&s, 200);
    s.save(dir.to_string_lossy().into_owned()).unwrap();
    for file in ["model.obj", "thumbnail.png", "artwork.json", "sets/0"] {
        assert!(dir.join(file).exists(), "{file}");
    }
    let again = CanvasSession::open(dir.to_string_lossy().into_owned()).unwrap();
    assert_eq!(again.model_info().unwrap().texture_sets, ["front", "sides"]);
    assert_eq!(again.texture_set(), 1);
    assert!(shown(&again));
    assert_eq!(painted(&again), side);
    assert!(again.paint_through());
    assert_eq!(again.model_environment().as_deref(), Some("coast"));
    assert_eq!(again.model_exposure(), 0.4);
    assert!(!again.model_environment_shown());
    // seen as it was left: the side still faces the camera
    let turn = |s: &CanvasSession| s.shared.lock().scene.as_ref().and_then(|sc| sc.orbit()).unwrap().rotation;
    assert!(turn(&again).iter().zip(turn(&s)).all(|(a, b)| (a - b).abs() < 1e-6));
    frame(&again, 200);
    // and painted on again, elsewhere
    again.stroke_begin(PointerKind::Stylus, vec![sample(80.0, 80.0, 0.0)]);
    again.stroke_end(vec![sample(120.0, 80.0, 50.0)]);
    frame(&again, 200);
    let more = painted(&again);
    assert!(more > side, "{more} after {side}");
    again.choose_texture_set(0);
    assert_eq!(painted(&again), front);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn choosing_a_tile_is_an_undo_step_that_chooses_again_what_was_before() {
    let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
    if !shown(&s) {
        return;
    }
    frame(&s, 200);
    let chosen = |s: &CanvasSession| (s.model_channel(), s.channel_tile_chosen());
    assert_eq!(chosen(&s), (0, false));
    assert!(!s.info().can_undo);
    // the Roughness tile, a stroke on it, the Color tile, the layer's row again
    s.choose_channel_tile(1);
    assert_eq!(chosen(&s), (1, true));
    assert!(s.info().can_undo);
    dab(&s);
    let rough = painted(&s);
    assert!(rough > 20, "{rough}");
    s.choose_channel_tile(0);
    assert_eq!(chosen(&s), (0, true));
    s.leave_channel_tiles();
    assert_eq!(chosen(&s), (0, false));
    // undone one by one, each choice taken back to what was chosen before it
    assert_eq!(s.undo().as_deref(), Some(super::ROW_CHOSEN));
    assert_eq!(chosen(&s), (0, true));
    assert_eq!(s.undo().as_deref(), Some(super::TILE_CHOSEN));
    assert_eq!(chosen(&s), (1, true));
    assert_eq!(painted(&s), rough);
    s.undo();
    assert_eq!((chosen(&s), painted(&s)), ((1, true), 0));
    assert_eq!(s.undo().as_deref(), Some(super::TILE_CHOSEN));
    assert_eq!(chosen(&s), (0, false));
    assert!(!s.info().can_undo);
    // and redone the same way
    s.redo();
    assert_eq!((chosen(&s), painted(&s)), ((1, true), 0));
    s.redo();
    assert_eq!((chosen(&s), painted(&s)), ((1, true), rough));
    s.redo();
    assert_eq!(chosen(&s), (0, true));
    s.redo();
    assert_eq!(chosen(&s), (0, false));
    assert!(!s.info().can_redo);
    // a channel switched to for a moment (as a save does) leaves the steps in order
    s.choose_channel_tile(2);
    s.set_model_channel(0);
    s.set_model_channel(2);
    assert_eq!(s.undo().as_deref(), Some(super::TILE_CHOSEN));
    assert_eq!(chosen(&s), (0, false));
    s.redo();
    assert_eq!(chosen(&s), (2, true));
}

#[test]
fn model_painting_undoes_redoes_and_saves_whole() {
    for seed in [2u32, 30] {
        let s = CanvasSession::open_model(CUBE.into(), "cube.obj".into(), Vec::new(), "Cube".into(), 64).unwrap();
        if !shown(&s) {
            return;
        }
        frame(&s, 200);
        let mut state = seed;
        let mut next = |n: u32| {
            state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            (state >> 8) % n.max(1)
        };
        // every channel's texture as composited, the channel painted on kept
        let all = |s: &CanvasSession| {
            let kept = s.model_channel();
            let out: Vec<Vec<u8>> = (0..3)
                .map(|c| {
                    s.set_model_channel(c);
                    let mut guard = s.shared.lock();
                    let st = &mut *guard;
                    let (canvas, gpu) = (st.canvas.as_mut().unwrap(), st.gpu.as_ref().unwrap());
                    canvas.render_finished(gpu, &mut st.doc);
                    assert_eq!(gpu.errors(), 0, "GPU validation errors");
                    canvas.read_composite(gpu).unwrap()
                })
                .collect();
            s.set_model_channel(kept);
            out
        };
        let can = |s: &CanvasSession, undo: bool| {
            let info = s.info();
            if undo { info.can_undo } else { info.can_redo }
        };
        // each channel keeps its own history (a stroke on the colour steps in all three):
        // every channel's undone (or redone) in turn until none has any left; how many
        let through = |s: &CanvasSession, undo: bool| {
            let mut count = 0;
            loop {
                let before = count;
                for c in 0..3 {
                    s.set_model_channel(c);
                    while can(s, undo) && count < 400 {
                        if undo {
                            s.undo();
                        } else {
                            s.redo();
                        }
                        count += 1;
                    }
                }
                if count == before {
                    break count;
                }
            }
        };
        let start = all(&s);
        let mut done = Vec::new();
        let mut t = 0.0;
        for _ in 0..30 {
            let op = next(10);
            done.push(op);
            let (x, y) = (60.0 + next(80) as f32, 60.0 + next(80) as f32);
            let (dx, dy) = (next(60) as f32 - 30.0, next(60) as f32 - 30.0);
            t += 1000.0;
            match op {
                0..=3 => {
                    s.set_tool(
                        [
                            crate::ToolKind::Paint,
                            crate::ToolKind::Paint,
                            crate::ToolKind::Erase,
                            crate::ToolKind::Smudge,
                        ][next(4) as usize],
                    );
                    s.set_brush_size(0.2 + next(60) as f32 / 100.0);
                    s.set_color(next(255) as f32 / 255.0, next(255) as f32 / 255.0, next(255) as f32 / 255.0);
                    s.stroke_begin(PointerKind::Stylus, vec![sample(x, y, t)]);
                    frame(&s, 200);
                    s.stroke_add(
                        (1..8)
                            .map(|k| {
                                sample(x + dx * k as f32 / 7.0, y + dy * k as f32 / 7.0, t + f64::from(k) * 8.0)
                            })
                            .collect(),
                    );
                    s.stroke_end(vec![]);
                }
                4 => {
                    // a finger turns it
                    s.touch_down(1, PointerKind::Finger, vec![sample(x, y, t)]);
                    s.touch_move(1, vec![sample(x + dx, y + dy, t + 16.0)]);
                    s.touch_up(1, vec![sample(x + dx, y + dy, t + 32.0)]);
                }
                5 => match next(3) {
                    0 => s.set_model_channel(next(3)),
                    1 => s.choose_channel_tile(next(3)),
                    _ => s.leave_channel_tiles(),
                },
                // from whichever channel is painted on (the others follow its layers)
                6 => _ = s.add_layer(),
                7 => {
                    let layers = s.layers();
                    if let Some(l) = layers.get(next(layers.len() as u32) as usize) {
                        _ = s.select_layer(l.id);
                    }
                }
                8 => materials(&s, next(100) as f32 / 100.0, next(100) as f32 / 100.0),
                _ => {
                    if next(2) == 0 {
                        _ = s.undo();
                    } else {
                        _ = s.redo();
                    }
                }
            }
            frame(&s, 200);
        }
        through(&s, false);
        let end = all(&s);
        let steps = through(&s, true);
        assert!(all(&s) == start, "seed {seed}: undone {steps} steps of {done:?}");
        let again = through(&s, false);
        assert!(all(&s) == end, "seed {seed}: redone {again} steps of {done:?}");
        // saved and opened again
        let dir = std::env::temp_dir().join(format!("procraft-model-work-{}-{seed}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        s.save(dir.to_string_lossy().into_owned()).unwrap();
        let opened = CanvasSession::open(dir.to_string_lossy().into_owned()).unwrap();
        assert!(shown(&opened));
        let _ = std::fs::remove_dir_all(&dir);
        assert!(all(&opened) == end, "seed {seed}: reopened, after {done:?}");
    }
}

/// Models of every awkward shape (no texture coordinates, none of a triangle's area, a flat
/// one, ones far too big or small, coordinates that are no numbers, texture coordinates far
/// outside the texture, several materials with no file for them) opened and then turned,
/// pinched, held, painted with the pen, a finger, the mouse and the eraser, on each channel and
/// texture set, undone, redone and saved, a frame drawn after each: on this GPU, and as an old
/// GPU's. Each either opens and takes all of it, or is refused as it opens; none fails.
#[test]
fn models_of_every_shape_take_turning_and_painting_without_failing() {
    const SQUARE: &str = "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n";
    let models: Vec<(&str, String)> = vec![
        ("cube", CUBE.to_owned()),
        ("no texture coordinates", "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nf 1 2 3 4\n".to_owned()),
        ("normals", format!("{SQUARE}vn 0 0 1\nf 1/1/1 2/2/1 3/3/1 4/4/1\n")),
        ("facing away", format!("{SQUARE}f 4/4 3/3 2/2 1/1\n")),
        ("one point", "v 0 0 0\nv 0 0 0\nv 0 0 0\nvt 0 0\nvt 0 0\nvt 0 0\nf 1/1 2/2 3/3\n".to_owned()),
        ("a line", "v 0 0 0\nv 1 0 0\nv 2 0 0\nvt 0 0\nvt 1 0\nvt 1 1\nf 1/1 2/2 3/3\n".to_owned()),
        (
            "huge",
            "v -1e30 -1e30 0\nv 1e30 -1e30 0\nv 1e30 1e30 0\nvt 0 0\nvt 1 0\nvt 1 1\nf 1/1 2/2 3/3\n".to_owned(),
        ),
        ("past f32", "v -1e39 -1 0\nv 1e39 -1 0\nv 1 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nf 1/1 2/2 3/3\n".to_owned()),
        (
            "tiny",
            "v -1e-30 -1e-30 0\nv 1e-30 -1e-30 0\nv 1e-30 1e-30 0\nvt 0 0\nvt 1 0\nvt 1 1\nf 1/1 2/2 3/3\n"
                .to_owned(),
        ),
        ("no numbers", format!("{SQUARE}v nan inf 0\nvt nan inf\nf 1/1 2/2 3/3 4/4\nf 5/5 1/1 2/2\n")),
        (
            "far texture coordinates",
            "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nvt -3 -2\nvt 5 -2\nvt 5 7\nvt -3 7\nf 1/1 2/2 3/3 4/4\n"
                .to_owned(),
        ),
        (
            "huge texture coordinates",
            "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nvt -1e30 0\nvt 1e30 0\nvt 0 1e30\nf 1/1 2/2 3/3\n".to_owned(),
        ),
        (
            "two materials",
            format!("{SQUARE}v -1 -1 1\nv 1 -1 1\nv 1 1 1\nusemtl a\nf 1/1 2/2 3/3 4/4\nusemtl b\nf 5/1 6/2 7/3\n"),
        ),
        ("no faces", "v 0 0 0\nv 1 0 0\nv 0 1 0\n".to_owned()),
        ("nothing", String::new()),
    ];
    let (Ok(_), Ok(_)) = (Gpu::headless(), Gpu::headless_old()) else { return eprintln!("skipped: no GPU") };
    let mut failed = Vec::new();
    for old in [false, true] {
        for (name, text) in &models {
            for side in [64, 2048].into_iter().filter(|&side| side == 64 || *name == "cube") {
                let gpu = if old { Gpu::headless_old() } else { Gpu::headless() }.unwrap();
                let run = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    work_a_model(text, gpu.clone(), side);
                }));
                let errors = gpu.errors();
                let what = format!("{name} at {side}{}", if old { ", old GPU" } else { "" });
                if let Err(panic) = run {
                    failed.push(format!("{what}: {}", procraft_gpu::panic_message(panic.as_ref())));
                } else if errors > 0 {
                    failed.push(format!("{what}: {errors} GPU errors"));
                }
            }
        }
    }
    assert!(failed.is_empty(), "{failed:#?}");
}

/// `text`, an OBJ, opened with `side` texture sets on `gpu` and put through what a hand does
/// to a model (see `models_of_every_shape_take_turning_and_painting_without_failing`).
fn work_a_model(text: &str, gpu: Gpu, side: u32) {
    let Ok(s) = CanvasSession::open_model(text.into(), "model.obj".into(), Vec::new(), "Model".into(), side) else {
        return;
    };
    {
        let mut st = s.shared.lock();
        st.window_size = (200, 200);
        st.gpu = Some(gpu);
        st.ensure_canvas();
    }
    let draw = |s: &CanvasSession| {
        let mut guard = s.shared.lock();
        let st = &mut *guard;
        let format = wgpu::TextureFormat::Rgba8Unorm;
        let target = st.gpu.as_ref().unwrap().device.create_texture(&wgpu::TextureDescriptor {
            label: Some("test frame"),
            size: wgpu::Extent3d { width: 200, height: 200, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            view_formats: &[],
        });
        let view = target.create_view(&wgpu::TextureViewDescriptor::default());
        st.render_scene(&view, format, (200, 200), None);
    };
    let settings =
        crate::TouchSettings { hold: Some(crate::GestureAction::Eyedropper), ..crate::TouchSettings::default() };
    s.set_touch_settings(settings.clone());
    draw(&s);
    let mut t = 0.0;
    // a finger turns it, a long way
    s.touch_down(1, PointerKind::Finger, vec![sample(70.0, 100.0, t)]);
    for i in 1..12 {
        t += 16.0;
        s.touch_move(1, vec![sample(70.0 + i as f32 * 25.0, 100.0 + i as f32 * 9.0, t)]);
        draw(&s);
    }
    t += 16.0;
    s.touch_up(1, vec![sample(345.0, 199.0, t)]);
    s.turn_model(5000.0, -3000.0);
    draw(&s);
    // two fingers pinch it nearer, then far away, and move it
    for (from, to) in [(40.0, 5.0), (5.0, 400.0)] {
        t += 100.0;
        s.touch_down(2, PointerKind::Finger, vec![sample(100.0 - from, 100.0, t)]);
        t += 5.0;
        s.touch_down(3, PointerKind::Finger, vec![sample(100.0 + from, 100.0, t)]);
        for i in 1..=8 {
            t += 16.0;
            let gap = from + (to - from) * i as f32 / 8.0;
            s.touch_moves(vec![
                crate::touch::TouchMove { id: 2, samples: vec![sample(100.0 - gap, 100.0 + i as f32, t)] },
                crate::touch::TouchMove { id: 3, samples: vec![sample(100.0 + gap, 100.0 + i as f32, t)] },
            ]);
            draw(&s);
        }
        t += 16.0;
        s.touch_up(3, vec![sample(100.0 + to, 108.0, t)]);
        s.touch_up(2, vec![sample(100.0 - to, 108.0, t)]);
    }
    draw(&s);
    // a finger held still picks a colour off it
    t += 100.0;
    s.touch_down(4, PointerKind::Finger, vec![sample(100.0, 100.0, t)]);
    t += settings.hold_ms + 10.0;
    for c in s.touch_tick(t) {
        if let crate::TouchCommand::Pick { x, y } = c {
            let _ = s.pick(x, y, 11);
        }
    }
    t += 16.0;
    s.touch_up(4, vec![sample(100.0, 100.0, t)]);
    draw(&s);
    // painted across the middle by the pen (pausing long enough to snap a shape), a finger set
    // to paint the model, the mouse, and the pen's eraser end, on each channel
    let across = |s: &CanvasSession, id: u64, kind: PointerKind, t: &mut f64| {
        s.touch_down(id, kind, vec![sample(20.0, 100.0, *t)]);
        draw(s);
        for i in 1..20 {
            *t += 8.0;
            s.touch_move(id, vec![sample(20.0 + i as f32 * 8.0, 100.0 + (i % 5) as f32 * 4.0, *t)]);
            if i == 10 {
                *t += 1000.0;
                let _ = s.touch_tick(*t);
            }
            draw(s);
        }
        *t += 8.0;
        s.touch_up(id, vec![sample(180.0, 100.0, *t)]);
        draw(s);
    };
    // (a panel open over half of it, frosted)
    s.shared.lock().backdrops = vec![[0.0, 0.0, 100.0, 200.0, 0.0, 1.0]];
    for channel in 0..3 {
        s.set_model_channel(channel);
        across(&s, 5, PointerKind::Stylus, &mut t);
        across(&s, 6, PointerKind::Mouse, &mut t);
        s.set_touch_settings(crate::TouchSettings { model_finger_paint: true, ..settings.clone() });
        across(&s, 7, PointerKind::Finger, &mut t);
        s.set_touch_settings(settings.clone());
        across(&s, 8, PointerKind::StylusEraser, &mut t);
        s.set_tool(crate::ToolKind::Smudge);
        across(&s, 9, PointerKind::Stylus, &mut t);
        s.set_tool(crate::ToolKind::Paint);
    }
    s.set_model_channel(0);
    // on each texture set
    let sets = s.model_info().map_or(0, |info| info.texture_sets.len());
    for set in 0..sets {
        s.choose_texture_set(set as u32);
        draw(&s);
        across(&s, 10, PointerKind::Stylus, &mut t);
    }
    for _ in 0..4 {
        let _ = s.undo();
        draw(&s);
    }
    for _ in 0..2 {
        let _ = s.redo();
        draw(&s);
    }
    // saved (as the autosave does) and opened again
    let dir = std::env::temp_dir().join(format!("procraft-awkward-model-{}-{side}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let gpu = s.shared.lock().gpu.clone();
    if s.save(dir.to_string_lossy().into_owned()).is_ok()
        && let (Ok(opened), Some(gpu)) = (CanvasSession::open(dir.to_string_lossy().into_owned()), gpu)
    {
        // and turned and painted there too
        {
            let mut st = opened.shared.lock();
            st.window_size = (200, 200);
            st.gpu = Some(gpu);
            st.ensure_canvas();
        }
        opened.set_touch_settings(settings.clone());
        draw(&opened);
        t += 100.0;
        opened.touch_down(1, PointerKind::Finger, vec![sample(70.0, 100.0, t)]);
        t += 16.0;
        opened.touch_move(1, vec![sample(150.0, 130.0, t)]);
        draw(&opened);
        t += 16.0;
        opened.touch_up(1, vec![sample(150.0, 130.0, t)]);
        across(&opened, 11, PointerKind::Stylus, &mut t);
    }
    let _ = std::fs::remove_dir_all(&dir);
}

/// What a model holds on the GPU as it is turned and painted (two texture sets at the default
/// size, in a window of a tablet's size): turning it a hundred frames takes nothing more after
/// the first few (a frame left anything behind, a tablet would soon run out as a finger turns
/// it); the first stroke makes the screen's canvas and the roughness and metalness it paints,
/// and later strokes keep only their undo steps. How much each takes is printed.
#[test]
fn turning_a_model_holds_no_more_gpu_memory_and_painting_it_little() {
    let Ok(gpu) = Gpu::headless() else { return eprintln!("skipped: no GPU") };
    let held = || {
        gpu.flush();
        gpu.device.generate_allocator_report().map_or(0, |r| r.total_allocated_bytes)
    };
    if held() == 0 {
        return eprintln!("skipped: the device can't say what it holds");
    }
    const TWO_PARTS: &str = "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nv -1 -1 1\nv 1 -1 1\nv 1 1 1\n\
                             vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n\
                             usemtl a\nf 1/1 2/2 3/3 4/4\nusemtl b\nf 5/1 6/2 7/3\n";
    let s = CanvasSession::open_model(TWO_PARTS.into(), "two.obj".into(), Vec::new(), "Two".into(), 0).unwrap();
    let window = (1600u32, 1000u32);
    let format = wgpu::TextureFormat::Rgba8Unorm;
    let target = gpu.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("test window"),
        size: wgpu::Extent3d { width: window.0, height: window.1, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
        view_formats: &[],
    });
    let view = target.create_view(&wgpu::TextureViewDescriptor::default());
    {
        let mut st = s.shared.lock();
        st.window_size = window;
        st.gpu = Some(gpu.clone());
        st.ensure_canvas();
    }
    let draw = || s.shared.lock().render_scene(&view, format, window, None);
    let mut t = 0.0;
    let turn = |frames: usize, t: &mut f64| {
        *t += 100.0;
        s.touch_down(1, PointerKind::Finger, vec![sample(600.0, 500.0, *t)]);
        for i in 0..frames {
            *t += 16.0;
            s.touch_move(1, vec![sample(600.0 + (i % 40) as f32 * 10.0, 500.0 + (i % 7) as f32 * 3.0, *t)]);
            draw();
        }
        s.touch_up(1, vec![sample(600.0, 500.0, *t + 16.0)]);
    };
    let stroke = |t: &mut f64| {
        *t += 100.0;
        s.touch_down(2, PointerKind::Stylus, vec![sample(500.0, 500.0, *t)]);
        for i in 1..30 {
            *t += 8.0;
            s.touch_move(2, vec![sample(500.0 + i as f32 * 8.0, 500.0, *t)]);
            if i % 5 == 0 {
                draw();
            }
        }
        s.touch_up(2, vec![sample(740.0, 500.0, *t + 8.0)]);
        draw();
    };
    let mb = |b: u64| b as f64 / 1e6;
    let more = |after: u64, before: u64| (after as f64 - before as f64) / 1e6;
    draw();
    let opened = held();
    turn(10, &mut t);
    let settled = held();
    turn(100, &mut t);
    let turned = held();
    stroke(&mut t);
    let first_stroke = held();
    turn(100, &mut t);
    let turned_after = held();
    for _ in 0..5 {
        stroke(&mut t);
    }
    let five_more = held();
    eprintln!(
        "model on the GPU: opened {:.1} MB, turned {:+.1}, then 100 frames {:+.1}; first stroke {:+.1}, 100 frames {:+.1}, five strokes {:+.1}",
        mb(opened),
        more(settled, opened),
        more(turned, settled),
        more(first_stroke, turned),
        more(turned_after, first_stroke),
        more(five_more, turned_after),
    );
    assert!(turned <= settled, "turning held {:.1} MB more", more(turned, settled));
    assert!(
        turned_after <= first_stroke,
        "turning after a stroke held {:.1} MB more",
        more(turned_after, first_stroke)
    );
    assert_eq!(gpu.errors(), 0);
}
