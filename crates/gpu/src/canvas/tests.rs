use super::*;

/// The stroke's carry goes from one of a symmetry's copies to another only before a piece that
/// works in another's: what the next stamp of the copy going out reads of it goes aside, and what
/// the coming copy's piece reads from its stamps before comes back; a piece that works in no
/// carry changes nothing.
#[test]
fn a_symmetry_s_copies_take_turns_in_the_stroke_s_carry() {
    let piece = |copy: usize, uniform: SmudgeUniform, pipes: &[smudge::Pipe]| smudge::Piece {
        uniforms: vec![uniform],
        dispatches: pipes.iter().map(|&pipe| (pipe, 0, [1, 1])).collect(),
        listed: 0,
        reach: IRect::EMPTY,
        writes: IRect::EMPTY,
        work: true,
        kept: false,
        carry: copy,
    };
    let zeroed: SmudgeUniform = bytemuck::Zeroable::zeroed();
    // a smudge stamp: the cells it reads from the stamp before (-1: none), and those it reaches
    let smudged = |copy, kept, half| {
        piece(copy, SmudgeUniform { kept, half, ..zeroed }, &[smudge::Pipe::Carry, smudge::Pipe::Deposit])
    };
    let put_together = piece(0, zeroed, &[smudge::Pipe::Synthesize]);
    let pieces =
        [smudged(0, -1, 5), smudged(0, 5, 6), smudged(1, -1, 5), put_together, smudged(1, 5, 7), smudged(0, 6, 6)];
    let (overs, resident) = carry_hand_overs(None, &pieces);
    assert_eq!(
        overs,
        [(2, Some((0, Kept::Cells(6))), (1, Kept::Nothing)), (5, Some((1, Kept::Cells(7))), (0, Kept::Cells(6)))]
    );
    assert_eq!(resident, Some((0, Kept::Cells(6))));
    // going on in a later render, and after the stroke began again (nothing to put aside)
    let (overs, _) = carry_hand_overs(resident, &[smudged(1, 7, 7)]);
    assert_eq!(overs, [(0, Some((0, Kept::Cells(6))), (1, Kept::Cells(7)))]);
    assert!(carry_hand_overs(None, &[smudged(1, -1, 4)]).0.is_empty());
    // wet paint's passes: the plot each carried over, the two kept plots taking turns
    let plots = |copy, first: bool, index, carried, plot| {
        let kept = if first { -1 } else { 0 };
        let u = SmudgeUniform { mode: 1, kept, index, carried, plot, count: 3, ..zeroed };
        piece(copy, u, &[smudge::Pipe::WetPlots, smudge::Pipe::DepositRun])
    };
    let (overs, resident) =
        carry_hand_overs(None, &[plots(0, true, 0, 1, 16), plots(1, true, 0, 1, 16), plots(0, false, 1, 16, 32)]);
    let carried = Kept::Plot { which: 1, texels: 256 };
    assert_eq!(overs, [(1, Some((0, carried)), (1, Kept::Nothing)), (2, Some((1, carried)), (0, carried))]);
    assert_eq!(resident, Some((0, Kept::Plot { which: 0, texels: 1024 })));
}

/// Pictures of every size up to a few dozen, painted in patches with clear between (as a
/// layer is), the shrunken ones and the painted bounds as the old pixel-at-a-time ways give.
#[test]
fn a_parked_layers_pictures_are_made_as_they_were() {
    let mut seed = 12345u32;
    let mut next = move || {
        seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345);
        (seed >> 16) as u8
    };
    for (w, h) in [(1, 1), (3, 7), (40, 9), (64, 64), (193, 50), (37, 410), (500, 300)] {
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for y in 0..h {
            for x in 0..w {
                // patches: clear where (x / 5 + y / 3) is odd
                if (x / 5 + y / 3) % 2 == 0 && (x + y) % 11 != 0 {
                    let i = ((y * w + x) * 4) as usize;
                    let a = next();
                    rgba[i..i + 4].copy_from_slice(&[next().min(a), next().min(a), next().min(a), a]);
                }
            }
        }
        let t = Thumbnail { width: w, height: h, rgba };
        for side in [1, 5, 16, 64, 192, 1000] {
            let fast = shrink(&t, side);
            let slow = shrink_slowly(&t, side);
            assert_eq!((fast.width, fast.height), (slow.width, slow.height), "{w}x{h} to {side}");
            assert!(fast.rgba == slow.rgba, "{w}x{h} to {side}: pixels differ");
        }
        let alpha = |p: [u8; 4]| p[3] > 0;
        let bounds = painted_bounds(&t, alpha);
        assert_eq!(bounds, painted_bounds_slowly(&t, alpha), "{w}x{h} bounds");
        if let Some(b) = bounds {
            let fast = shrink_part(&t.rgba, w, b, 64);
            let slow = shrink_slowly(&crop(&t, b), 64);
            assert!(fast.rgba == slow.rgba && fast.width == slow.width, "{w}x{h} part");
        }
    }
}

std::thread_local! {
    /// The label of textures this thread can't make, as past the GPU's memory.
    static FAILING: std::cell::Cell<Option<&'static str>> = const { std::cell::Cell::new(None) };
}

/// Whether a texture labelled `label` is to fail on this thread.
pub(super) fn fails(label: &str) -> bool {
    FAILING.with(|f| f.get() == Some(label))
}

#[test]
fn a_canvas_made_for_filling_holds_no_layer_textures_until_they_come() {
    use procraft_engine::document::Document;
    let Ok(gpu) = Gpu::headless() else { return };
    let mut doc = Document::new(512, 512);
    for _ in 0..7 {
        doc.add_layer();
    }
    let held = || {
        gpu.flush();
        gpu.device.generate_allocator_report().map_or(0, |r| r.total_allocated_bytes)
    };
    let before = held();
    let mut canvas = Canvas::unfilled(&gpu, &doc).unwrap();
    let unfilled = held() - before;
    canvas.fill_rest(&gpu, &doc);
    // where the device can't say, there is nothing to compare
    let layers = 8 * 512 * 512 * 4;
    assert!(before == 0 || held() - before - unfilled >= layers);
}

#[test]
fn the_history_takes_from_the_room_for_layers() {
    use procraft_engine::document::Document;
    use procraft_engine::stroke::Stamp;
    let Ok(gpu) = Gpu::headless() else { return };
    let mut doc = Document::new(64, 64);
    let per = 64 * 64 * 4;
    let mut canvas = Canvas::new(&gpu, &doc).unwrap();
    canvas.set_resident_budget(4 * per);
    assert_eq!(canvas.most_resident(), 4);
    let id = doc.current();
    // each stroke over the whole layer keeps a layer's worth to undo
    for _ in 0..2 {
        canvas.begin_stroke(id, StrokeStyle::new(StrokeTool::Paint, [0.0; 3], 1.0));
        canvas.add_stamps(&[Stamp::round(procraft_engine::geom::Vec2::new(32.0, 32.0), 64.0, 1.0)]);
        canvas.end_stroke();
        canvas.render(&gpu, &mut doc, None);
    }
    assert!(canvas.history_bytes() >= 2 * per);
    assert_eq!(canvas.most_resident(), 2);
    // never none
    canvas.set_resident_budget(per);
    assert_eq!(canvas.most_resident(), 1);
}

#[test]
fn with_little_memory_the_history_keeps_to_half_the_room_for_layers() {
    use procraft_engine::document::Document;
    let Ok(gpu) = Gpu::headless() else { return };
    let doc = Document::new(64, 64);
    let mut canvas = Canvas::new(&gpu, &doc).unwrap();
    // no budget: the history's own bound
    assert_eq!(canvas.history_most(), MAX_UNDO_BYTES);
    // a tablet of 4 GB (a quarter of it for the layers' textures): half that; of 3 GB likewise
    canvas.set_resident_budget(1 << 30);
    assert_eq!(canvas.history_most(), 512 << 20);
    canvas.set_resident_budget(750 << 20);
    assert_eq!(canvas.history_most(), 375 << 20);
    // a device of plenty keeps the history's own bound, and none goes below the least
    canvas.set_resident_budget(3 << 30);
    assert_eq!(canvas.history_most(), MAX_UNDO_BYTES);
    canvas.set_resident_budget(64 << 20);
    assert_eq!(canvas.history_most(), LEAST_UNDO_BYTES);
}

#[test]
fn a_texture_the_gpu_couldnt_make_reads_as_a_failure_not_as_old_pixels() {
    let Ok(gpu) = Gpu::headless() else { return };
    // a read that leaves its pixels in the buffer kept for the next
    let filled = texture(&gpu.device, "filled", 64, 64, LAYER_FORMAT);
    gpu.queue.write_texture(
        copy_at(&filled, 0, 0),
        &[200; 64 * 64 * 4],
        wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(64 * 4), rows_per_image: Some(64) },
        extent(64, 64),
    );
    assert_eq!(read_texture(&gpu, &filled, 64, 64).unwrap()[0], 200);
    FAILING.with(|f| f.set(Some("empty")));
    let scope = gpu.device.push_error_scope(wgpu::ErrorFilter::Validation);
    let empty = texture(&gpu.device, "empty", 64, 64, LAYER_FORMAT);
    assert!(pollster::block_on(scope.pop()).is_some());
    FAILING.with(|f| f.set(None));
    assert!(read_texture(&gpu, &empty, 64, 64).is_err());
}

#[test]
fn a_smudge_the_gpu_has_no_memory_for_draws_on_and_makes_no_step() {
    use procraft_engine::document::Document;
    use procraft_engine::stroke::Stamp;
    let Ok(gpu) = Gpu::headless() else { return };
    let dot = |x: f32, radius: f32| Stamp::round(procraft_engine::geom::Vec2::new(x, 32.0), radius, 1.0);
    let mut doc = Document::new(64, 64);
    let mut canvas = Canvas::new(&gpu, &doc).unwrap();
    let id = doc.current();
    canvas.begin_stroke(id, StrokeStyle::new(StrokeTool::Paint, [0.0; 3], 1.0));
    canvas.add_stamps(&[dot(20.0, 12.0)]);
    canvas.end_stroke();
    canvas.render(&gpu, &mut doc, None);
    let before = canvas.read_layer(&gpu, id).unwrap();
    let steps = canvas.steps_made();
    let smudge = || StrokeStyle::new(StrokeTool::Smudge, [0.0; 3], 1.0);
    let stamps: Vec<Stamp> = (0..8).map(|i| dot(20.0 + i as f32 * 3.0, 10.0)).collect();
    // refused: frames go on being drawn, the layer as it was, nothing to undo, told once
    FAILING.with(|f| f.set(Some("smudge before")));
    canvas.begin_stroke(id, smudge());
    canvas.add_stamps(&stamps);
    canvas.render(&gpu, &mut doc, None);
    canvas.end_stroke();
    canvas.render(&gpu, &mut doc, None);
    FAILING.with(|f| f.set(None));
    assert_eq!(gpu.errors(), 0);
    assert!(canvas.take_smudge_refused());
    assert!(!canvas.take_smudge_refused());
    assert_eq!(canvas.steps_made(), steps);
    assert_eq!(canvas.read_layer(&gpu, id).unwrap(), before);
    // the next, with room, smudges as ever
    canvas.begin_stroke(id, smudge());
    canvas.add_stamps(&stamps);
    canvas.end_stroke();
    canvas.render(&gpu, &mut doc, None);
    assert_eq!(gpu.errors(), 0);
    assert!(!canvas.take_smudge_refused());
    assert_eq!(canvas.steps_made(), steps + 1);
    assert_ne!(canvas.read_layer(&gpu, id).unwrap(), before);
}

/// How far two pictures differ: the most any channel does, and how many channels differ at all.
fn differ(a: &[u8], b: &[u8]) -> (u8, usize) {
    assert_eq!(a.len(), b.len());
    a.iter().zip(b).fold((0, 0), |(most, n), (x, y)| (most.max(x.abs_diff(*y)), n + usize::from(x != y)))
}

/// Colour patches as `patched` paints them, worked out here (so that two GPUs start the same):
/// three soft discs, premultiplied, each over those before.
fn patches(width: u32, height: u32) -> Vec<u8> {
    let mut px = vec![0.0_f32; (width * height * 4) as usize];
    for (k, color) in [[0.9, 0.1, 0.1], [0.1, 0.3, 0.9], [0.2, 0.8, 0.2]].into_iter().enumerate() {
        let (cx, cy, alpha) = (20.0 + 45.0 * k as f32, 18.0 + 22.0 * k as f32, 0.5 + 0.25 * k as f32);
        for y in 0..height {
            for x in 0..width {
                let d = ((x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2)).sqrt();
                let a = (22.0 - d + 0.5).clamp(0.0, 1.0) * alpha;
                let i = ((y * width + x) * 4) as usize;
                for c in 0..3 {
                    px[i + c] = color[c] * a + px[i + c] * (1.0 - a);
                }
                px[i + 3] = a + px[i + 3] * (1.0 - a);
            }
        }
    }
    px.iter().map(|v| (v * 255.0).round() as u8).collect()
}

/// The layer of colour patches (see `patches`) on `gpu` once `style`'s stroke of `stamps` has
/// gone over it, a few stamps a frame, its smudge windows held to `most` bytes if given.
fn stroked(gpu: &Gpu, style: StrokeStyle, stamps: &[Stamp], most: Option<u64>) -> Vec<u8> {
    let mut doc = procraft_engine::document::Document::new(150, 110);
    let mut canvas = Canvas::new(gpu, &doc).unwrap();
    let id = doc.current();
    canvas.upload_layer(gpu, id, &patches(150, 110)).unwrap();
    if let Some(most) = most {
        canvas.smudge_most = most;
    }
    canvas.begin_stroke(id, style);
    for lot in stamps.chunks(4) {
        canvas.add_stamps(lot);
        canvas.render(gpu, &mut doc, None);
    }
    canvas.end_stroke();
    canvas.render(gpu, &mut doc, None);
    assert!(!canvas.take_smudge_refused());
    canvas.read_layer(gpu, id).unwrap()
}

/// On an old GPU (no half floats to draw into, no storage buffers: see `Gpu::headless_old`)
/// every kind of stroke is laid as on a full one, in fragment passes in place of the compute
/// passes: built up by each rendering's rule, wet paint and its glazes, paint laid in place,
/// smudges (pulled, blurred) and stamps picking up their own way (made one colour, sharpened,
/// warped, mixing paint in). Each comes out within a step of 8 bits a channel here and there,
/// the same whether its windows are roomy or small.
#[test]
fn an_old_gpu_lays_every_kind_of_stroke_as_a_full_one_does() {
    use procraft_engine::geom::Vec2;
    use procraft_engine::stroke::{PickUp, Warp, WarpKind};
    let (Ok(full), Ok(old)) = (Gpu::headless(), Gpu::headless_old()) else { return };
    assert!(!old.draws_half_floats() && !old.computes());
    assert_eq!(
        Canvas::new(&old, &procraft_engine::document::Document::new(8, 8)).unwrap().stroke_format(),
        LAYER_FORMAT
    );
    let stamps: Vec<Stamp> = (0..18)
        .map(|i| Stamp {
            time_ms: 20.0 * f64::from(i),
            distance: 7.0 * i as f32,
            angle: 0.3 * i as f32,
            roundness: if i % 2 == 0 { 1.0 } else { 0.5 },
            transfer: 0.5 + 0.03 * i as f32,
            ..Stamp::round(
                Vec2::new(-4.0 + 9.0 * i as f32, 10.0 + 4.5 * i as f32),
                if i == 2 { 40.0 } else { 9.0 },
                0.9,
            )
        })
        .collect();
    let plain = StrokeStyle { hardness: 0.3, ..StrokeStyle::new(StrokeTool::Paint, [0.9, 0.6, 0.1], 0.8) };
    let wet = StrokeStyle {
        wet_pull: Some(0.6),
        load: 0.4,
        dilution: 0.3,
        rendering: Rendering::Gradual,
        ..StrokeStyle::new(StrokeTool::Paint, [0.2, 0.3, 0.8], 1.0)
    };
    let smudge = StrokeStyle::new(StrokeTool::Smudge, [0.0; 3], 1.0);
    let styles = [
        ("over", plain),
        ("strongest", StrokeStyle { rendering: Rendering::Strongest, ..plain }),
        ("gradual", StrokeStyle { rendering: Rendering::Gradual, ..plain }),
        ("capped", StrokeStyle { rendering: Rendering::Capped, ..plain }),
        ("wet", wet),
        (
            "wet, grained, pooling",
            StrokeStyle { wet_edges: 0.5, grain: Some(0), grain_depth: 0.8, grain_scale: 32.0, ..wet },
        ),
        ("laid in place, burnt", StrokeStyle { wet_pull: None, laid_in_place: true, burnt_edges: 0.5, ..wet }),
        ("light glaze", StrokeStyle { rendering: Rendering::Strongest, wet_edges: 0.3, ..wet }),
        ("uniform glaze", StrokeStyle { rendering: Rendering::Capped, ..wet }),
        ("wet, luminance", StrokeStyle { luminance: true, ..wet }),
        ("smudge", smudge),
        ("smudge, pulled", StrokeStyle { pull: 0.5, ..smudge }),
        ("smudge, blurred", StrokeStyle { blur: 0.7, ..smudge }),
    ];
    let mut cases: Vec<(&str, StrokeStyle, Vec<Stamp>)> =
        styles.into_iter().map(|(name, style)| (name, style, stamps.clone())).collect();
    // stamps picking up their own way, as a brush engine's dabs do
    let picking = |pick: PickUp| stamps.iter().map(|s| Stamp { pick_up: Some(pick), ..*s }).collect::<Vec<_>>();
    let paint = PickUp { paint_share: 0.3, paint: Some([0.9, 0.9, 0.1]), here: true, ..PickUp::default() };
    let warp = Warp { kind: WarpKind::Turn, amount: 0.8, shift: [0.0; 2], nearest: false };
    cases.extend([
        ("picking up, mixing paint in", smudge, picking(paint)),
        (
            "picking up behind, made one colour",
            smudge,
            picking(PickUp { dull: Some((0.6, true)), ..PickUp::default() }),
        ),
        (
            "picking up, blurred and sharpened",
            smudge,
            picking(PickUp { blur: 0.5, sharpen: 0.4, here: true, ..PickUp::default() }),
        ),
        ("picking up, turned", smudge, picking(PickUp { warp: Some(warp), here: true, ..PickUp::default() })),
        ("picking up, laid over", smudge, picking(PickUp { over: true, here: true, ..paint })),
    ]);
    for (name, style, stamps) in &cases {
        let on_full = stroked(&full, *style, stamps, None);
        let on_old = stroked(&old, *style, stamps, None);
        let (most, n) = differ(&on_full, &on_old);
        eprintln!("{name}: {most} at most, {n} of {} channels differ", on_full.len());
        assert!(most <= 2, "{name}: {most}");
        // a layer of 150 x 110: windows of at most 8 kB, so each stroke takes many
        let small = |gpu| stroked(gpu, *style, stamps, Some(8 << 10));
        let (most, _) = differ(&small(&full), &small(&old));
        assert!(most <= 2, "{name}: in small windows, {most}");
    }
    assert_eq!(full.errors(), 0);
    assert_eq!(old.errors(), 0);
}

/// Wet paint and paint laid in place, a pass's stamps laid in runs at once (each pixel going
/// through those over it in order), come out pixel for pixel as laid a stamp a dispatch, on a
/// full GPU and in an old one's fragment passes alike, in roomy windows and small ones; in far
/// fewer dispatches.
#[test]
fn wet_paint_laid_in_runs_comes_out_as_laid_stamp_by_stamp() {
    use procraft_engine::geom::Vec2;
    let (Ok(full), Ok(old)) = (Gpu::headless(), Gpu::headless_old()) else { return };
    // overlapping stamps a few milliseconds apart (many to a pass), turning, of two sizes, a
    // few off the canvas's edge
    let stamps: Vec<Stamp> = (0..48)
        .map(|i| Stamp {
            time_ms: 2.5 * f64::from(i),
            distance: 3.0 * i as f32,
            angle: 0.2 * i as f32,
            roundness: if i % 3 == 0 { 0.6 } else { 1.0 },
            transfer: 0.4 + 0.01 * i as f32,
            ..Stamp::round(
                Vec2::new(-6.0 + 3.4 * i as f32, 20.0 + 1.7 * i as f32 + 9.0 * (i as f32 * 0.4).sin()),
                if i % 7 == 3 { 22.0 } else { 11.0 },
                0.3 + 0.012 * i as f32,
            )
        })
        .collect();
    let wet = StrokeStyle {
        wet_pull: Some(0.6),
        load: 0.4,
        dilution: 0.3,
        hardness: 0.4,
        ..StrokeStyle::new(StrokeTool::Paint, [0.2, 0.3, 0.8], 1.0)
    };
    let grained = StrokeStyle { grain: Some(0), grain_depth: 0.8, grain_scale: 32.0, ..wet };
    let styles = [
        ("wet", wet),
        ("wet, modulated", StrokeStyle { rendering: Rendering::Gradual, ..wet }),
        ("wet, grained, pooling", StrokeStyle { wet_edges: 0.5, ..grained }),
        ("wet, moving grain", StrokeStyle { grain_mode: GrainMode::Moving, ..grained }),
        ("light glaze", StrokeStyle { rendering: Rendering::Strongest, wet_edges: 0.3, ..wet }),
        ("uniform glaze", StrokeStyle { rendering: Rendering::Capped, ..wet }),
        ("wet, luminance", StrokeStyle { luminance: true, ..wet }),
        ("laid in place", StrokeStyle { wet_pull: None, laid_in_place: true, ..wet }),
        ("laid in place, burnt", StrokeStyle { wet_pull: None, laid_in_place: true, burnt_edges: 0.5, ..wet }),
    ];
    let laid = |gpu: &Gpu, style: StrokeStyle, one_by_one: bool, most: Option<u64>| {
        let mut doc = procraft_engine::document::Document::new(150, 110);
        let mut canvas = Canvas::new(gpu, &doc).unwrap();
        let id = doc.current();
        canvas.upload_layer(gpu, id, &patches(150, 110)).unwrap();
        canvas.lay_stamp_by_stamp(one_by_one);
        if let Some(most) = most {
            canvas.smudge_most = most;
        }
        canvas.begin_stroke(id, style);
        for lot in stamps.chunks(12) {
            canvas.add_stamps(lot);
            canvas.render(gpu, &mut doc, None);
        }
        canvas.end_stroke();
        canvas.render(gpu, &mut doc, None);
        assert!(!canvas.take_smudge_refused());
        (canvas.read_layer(gpu, id).unwrap(), canvas.compute_dispatches())
    };
    for (name, style) in styles {
        for gpu in [&full, &old] {
            for most in [None, Some(8 << 10)] {
                let (runs, fewer) = laid(gpu, style, false, most);
                let (one_by_one, more) = laid(gpu, style, true, most);
                let (worst, n) = differ(&runs, &one_by_one);
                assert!(
                    n == 0,
                    "{name} (old GPU {}, windows {most:?}): {n} channels differ, by {worst} at most",
                    gpu.old
                );
                assert_ne!(runs, patches(150, 110), "{name}: painted");
                if gpu.computes() && most.is_none() {
                    assert!(fewer < more, "{name}: {fewer} dispatches against {more}");
                }
            }
        }
    }
    assert_eq!(full.errors(), 0);
    assert_eq!(old.errors(), 0);
}

/// A pass's stamps are cut into runs that each reach little further together than one by one:
/// a pass's stamps close together are one run, a pass jumping across the canvas several, each
/// in order and none left out.
#[test]
fn a_pass_is_laid_in_runs_of_stamps_close_together() {
    let at = |x: i32, y: i32| IRect::new(x, y, x + 20, y + 20);
    let close: Vec<(usize, IRect)> = (0..10).map(|k| (k, at(3 * k as i32, 2 * k as i32))).collect();
    assert_eq!(deposit_runs(&close), [(0, 10, IRect::new(0, 0, 47, 38))]);
    // far apart, then back together; an empty one (a stamp of no size) passed over
    let jumping = [(0, at(0, 0)), (1, at(2, 1)), (2, IRect::EMPTY), (3, at(900, 700)), (4, at(903, 701))];
    assert_eq!(deposit_runs(&jumping), [(0, 2, IRect::new(0, 0, 22, 21)), (3, 5, IRect::new(900, 700, 923, 721))]);
    assert!(deposit_runs(&[(0, IRect::EMPTY)]).is_empty());
}

/// Stamps laid by their rule come out pixel for pixel the same however the parts of the canvas
/// they are laid in are cut (each part's list of tiles held to a few words, as a sample stroke of
/// thousands of big stamps is held to what a list may be), on a full GPU and an old one; the
/// lists stay within that, and are made once and kept from frame to frame.
#[test]
fn stamps_laid_by_their_rule_come_out_alike_however_their_parts_are_cut() {
    use procraft_engine::geom::Vec2;
    let (Ok(full), Ok(old)) = (Gpu::headless(), Gpu::headless_old()) else { return };
    let stamps: Vec<Stamp> = (0..60)
        .map(|i| Stamp {
            transfer: 0.3 + 0.01 * i as f32,
            ..Stamp::round(Vec2::new(20.0 + 21.0 * i as f32, 60.0 + 30.0 * (i as f32 * 0.2).sin()), 40.0, 0.3)
        })
        .collect();
    let plain = StrokeStyle { hardness: 0.3, ..StrokeStyle::new(StrokeTool::Paint, [0.9, 0.6, 0.1], 0.8) };
    for rendering in [Rendering::Gradual, Rendering::Capped] {
        for gpu in [&full, &old] {
            let laid = |most: Option<usize>| {
                let mut doc = procraft_engine::document::Document::new(1300, 140);
                let mut canvas = Canvas::new(gpu, &doc).unwrap();
                let id = doc.current();
                if let Some(most) = most {
                    canvas.set_rule_list_most(most);
                }
                canvas.begin_stroke(id, StrokeStyle { rendering, ..plain });
                for lot in stamps.chunks(15) {
                    canvas.add_stamps(lot);
                    canvas.render(gpu, &mut doc, None);
                }
                let kept = canvas.rule_lists.kept.len() + canvas.fragments.rule_lists.len();
                canvas.end_stroke();
                canvas.render(gpu, &mut doc, None);
                assert!(!canvas.take_work_left_out());
                (canvas.read_layer(gpu, id).unwrap(), canvas.largest_rule_list(), kept)
            };
            let (whole, longest, kept) = laid(None);
            let (cut, held, _) = laid(Some(700));
            assert_ne!(whole, vec![0; whole.len()], "{rendering:?}: painted");
            assert!(longest > 700 && held <= 700, "{rendering:?}: lists of {longest}, then {held} words");
            assert!(kept <= RULE_LISTS_KEPT, "{rendering:?}: {kept} lists kept");
            let (worst, n) = differ(&whole, &cut);
            assert!(n == 0, "{rendering:?} (old GPU {}): {n} channels differ, by {worst} at most", gpu.old);
        }
    }
    assert_eq!(full.errors(), 0);
    assert_eq!(old.errors(), 0);
}

/// Where the list a rule's pass reads can't be made (past the GPU's memory, as on a phone short
/// of it; the device lost), the frame goes on without that part, nothing panics, the GPU's
/// error is caught and the work left out said; the next frame with room lays it as ever. A
/// buffer made with what it holds that can't be made is said, not fatal (wgpu's own way to make
/// one panicked there, which closed the app as a brush's sample stroke was drawn).
#[test]
fn stamps_laid_by_their_rule_go_on_when_their_list_cant_be_made() {
    use procraft_engine::document::Document;
    use procraft_engine::geom::Vec2;
    let Ok(gpu) = Gpu::headless() else { return };
    if !gpu.computes() {
        return;
    }
    let stamps: Vec<Stamp> =
        (0..12).map(|i| Stamp::round(Vec2::new(10.0 + 6.0 * i as f32, 32.0), 9.0, 0.5)).collect();
    let glaze =
        StrokeStyle { rendering: Rendering::Capped, ..StrokeStyle::new(StrokeTool::Paint, [0.1, 0.5, 0.9], 1.0) };
    let laid = |failing: bool| {
        let mut doc = Document::new(96, 64);
        let mut canvas = Canvas::new(&gpu, &doc).unwrap();
        let id = doc.current();
        if failing {
            // a frame without room for the list, the stroke then let go
            FAILING.with(|f| f.set(Some("stamp rule tiles")));
            canvas.begin_stroke(id, glaze);
            canvas.add_stamps(&stamps);
            canvas.render(&gpu, &mut doc, None);
            FAILING.with(|f| f.set(None));
            assert!(canvas.take_work_left_out());
            assert!(!canvas.take_work_left_out(), "said once");
            canvas.cancel_stroke();
            canvas.render(&gpu, &mut doc, None);
        }
        canvas.begin_stroke(id, glaze);
        canvas.add_stamps(&stamps);
        canvas.end_stroke();
        canvas.render(&gpu, &mut doc, None);
        assert!(!canvas.take_work_left_out());
        canvas.read_layer(&gpu, id).unwrap()
    };
    let after = laid(true);
    let roomy = laid(false);
    assert_ne!(roomy, vec![0; roomy.len()], "painted");
    assert_eq!(after, roomy, "laid as ever once there was room");
    assert_eq!(gpu.errors(), 0, "the error was caught, not left to the device");
    // a buffer made with its contents
    FAILING.with(|f| f.set(Some("crop")));
    let scope = gpu.device.push_error_scope(wgpu::ErrorFilter::Validation);
    let _empty = buffer_init(&gpu.device, "crop", &[1, 2, 3, 4, 5], wgpu::BufferUsages::UNIFORM);
    assert!(pollster::block_on(scope.pop()).is_some());
    FAILING.with(|f| f.set(None));
    let made = buffer_init(&gpu.device, "crop", &[1, 2, 3, 4, 5], wgpu::BufferUsages::COPY_SRC);
    assert_eq!(made.size(), 8);
    assert_eq!(gpu.errors(), 0);
}

/// On an old GPU a stroke builds up in half floats as on a full one, though it shows in 8 bits:
/// a soft stamp far fainter than an 8-bit step, laid over and over, comes to the strength it
/// does there (in 8 bits alone it would lay nothing at all), and as smoothly out to its rim
/// (more so: a frame's stamps go into the half floats together, where a full GPU's blend rounds
/// each one's, which stalls in bands where the stamps are faintest).
#[test]
fn on_an_old_gpu_faint_soft_stamps_build_up_as_in_half_floats() {
    use procraft_engine::document::Document;
    use procraft_engine::geom::Vec2;
    let (Ok(full), Ok(old)) = (Gpu::headless(), Gpu::headless_old()) else { return };
    let built = |gpu: &Gpu, rendering: Rendering| {
        let mut doc = Document::new(96, 96);
        let mut canvas = Canvas::new(gpu, &doc).unwrap();
        let id = doc.current();
        let style =
            StrokeStyle { hardness: 0.0, rendering, ..StrokeStyle::new(StrokeTool::Paint, [0.1, 0.2, 0.7], 1.0) };
        canvas.begin_stroke(id, style);
        for frame in 0..25 {
            let stamps: Vec<Stamp> = (0..16)
                .map(|i| Stamp::round(Vec2::new(48.0 + 0.05 * (frame * 16 + i) as f32, 48.0), 40.0, 0.0015))
                .collect();
            canvas.add_stamps(&stamps);
            canvas.render(gpu, &mut doc, None);
        }
        canvas.end_stroke();
        canvas.render(gpu, &mut doc, None);
        canvas.read_layer(gpu, id).unwrap()
    };
    for rendering in [Rendering::BuildUp, Rendering::Gradual] {
        let (on_full, on_old) = (built(&full, rendering), built(&old, rendering));
        let (most, n) = differ(&on_full, &on_old);
        let alpha = |px: &[u8], x: usize, y: usize| px[(y * 96 + x) * 4 + 3];
        let mean =
            on_full.iter().zip(&on_old).map(|(a, b)| f64::from(a.abs_diff(*b))).sum::<f64>() / on_full.len() as f64;
        eprintln!(
            "{rendering:?}: {most} at most ({mean:.3} on average), {n} channels differ; the middle {} (full {})",
            alpha(&on_old, 48, 48),
            alpha(&on_full, 48, 48)
        );
        // 400 stamps of 0.0015 build up to about a third in the middle
        assert!(alpha(&on_old, 48, 48) > 80, "{rendering:?}: {}", alpha(&on_old, 48, 48));
        assert!(alpha(&on_old, 48, 48).abs_diff(alpha(&on_full, 48, 48)) <= 1);
        assert!(mean < 0.5, "{rendering:?}: {mean}");
        // and fall off smoothly: no step from one pixel to the next bigger than on a full GPU's
        let steepest =
            |px: &[u8]| (9..90).map(|y| alpha(px, 65, y - 1).abs_diff(alpha(px, 65, y))).max().unwrap_or(0);
        assert!(steepest(&on_old) <= steepest(&on_full) + 1, "{} {}", steepest(&on_old), steepest(&on_full));
    }
    assert_eq!(old.errors(), 0);
}

/// On an old GPU (no float targets: see `Gpu::headless_old`) liquify keeps its field in integers
/// holding each float's bits, its stamps' sums added up a lot at a time as half floats are: a
/// layer pushed, twirled, pinched, part rebuilt and the whole eased back shows as on a full GPU,
/// within a few steps of 8 bits at pushed edges.
#[test]
fn on_an_old_gpu_liquify_moves_paint_as_on_a_full_one() {
    let (Ok(full), Ok(old)) = (Gpu::headless(), Gpu::headless_old()) else { return };
    let liquified = |gpu: &Gpu| {
        let mut doc = procraft_engine::document::Document::new(150, 110);
        let mut canvas = Canvas::new(gpu, &doc).unwrap();
        let id = doc.current();
        canvas.upload_layer(gpu, id, &patches(150, 110)).unwrap();
        canvas.begin_transform(id);
        canvas.begin_liquify();
        let stamp =
            |centre: [f32; 2], radius, mode| LiquifyStamp { centre, way: [6.0, 2.0], radius, amount: 0.8, mode };
        let steps = [
            (0..6).map(|i| stamp([30.0 + 6.0 * i as f32, 40.0], 25.0, LiquifyMode::Push)).collect::<Vec<_>>(),
            vec![stamp([90.0, 60.0], 30.0, LiquifyMode::TwirlRight); 3],
            vec![stamp([60.0, 80.0], 20.0, LiquifyMode::Pinch), stamp([110.0, 30.0], 18.0, LiquifyMode::Expand)],
            vec![stamp([40.0, 40.0], 15.0, LiquifyMode::Reconstruct)],
        ];
        for step in &steps {
            canvas.liquify_stamps(step);
            canvas.set_filter(Filter::Liquify { amount: 1.0 });
            canvas.render(gpu, &mut doc, None);
        }
        canvas.liquify_scale(0.7);
        canvas.set_filter(Filter::Liquify { amount: 0.9 });
        canvas.render(gpu, &mut doc, None);
        assert_eq!(gpu.errors(), 0);
        canvas.read_composite(gpu).unwrap()
    };
    let (on_full, on_old) = (liquified(&full), liquified(&old));
    let (most, n) = differ(&on_full, &on_old);
    eprintln!("liquify: {most} at most, {n} of {} channels differ", on_full.len());
    // (where an edge is pushed, the half floats' rounding of the sums shifts it a fraction of a pixel)
    assert!(most <= 6 && n * 100 < on_full.len(), "{most} {n}");
    // and it moved paint
    let mut doc = procraft_engine::document::Document::new(150, 110);
    let mut plain = Canvas::new(&old, &doc).unwrap();
    plain.upload_layer(&old, doc.current(), &patches(150, 110)).unwrap();
    plain.render(&old, &mut doc, None);
    assert_ne!(plain.read_composite(&old).unwrap(), on_old);
}

/// A layer of colour patches, `width` by `height`, to smudge and paint over.
fn patched(gpu: &Gpu, width: u32, height: u32) -> (procraft_engine::document::Document, Canvas) {
    let mut doc = procraft_engine::document::Document::new(width, height);
    let mut canvas = Canvas::new(gpu, &doc).unwrap();
    let id = doc.current();
    for (k, color) in [[0.9, 0.1, 0.1], [0.1, 0.3, 0.9], [0.2, 0.8, 0.2]].into_iter().enumerate() {
        let at = procraft_engine::geom::Vec2::new(20.0 + 45.0 * k as f32, 18.0 + 22.0 * k as f32);
        canvas.begin_stroke(id, StrokeStyle::new(StrokeTool::Paint, color, 0.5 + 0.25 * k as f32));
        canvas.add_stamps(&[Stamp::round(at, 22.0, 1.0)]);
        canvas.end_stroke();
        canvas.render(gpu, &mut doc, None);
    }
    (doc, canvas)
}

#[test]
fn smudges_and_paint_laid_in_place_past_what_a_binding_holds_come_out_as_with_room() {
    use procraft_engine::geom::Vec2;
    let Ok(gpu) = Gpu::headless() else { return };
    // stamps across the canvas, the third wider than a small binding holds; wet paint a pass a
    // stamp (a pass of more is laid as several where it reaches past a binding)
    let stamps: Vec<Stamp> = (0..18)
        .map(|i| Stamp {
            time_ms: 20.0 * f64::from(i),
            distance: 7.0 * i as f32,
            angle: 0.3 * i as f32,
            roundness: if i % 2 == 0 { 1.0 } else { 0.5 },
            transfer: 0.5 + 0.03 * i as f32,
            ..Stamp::round(
                Vec2::new(-4.0 + 9.0 * i as f32, 10.0 + 4.5 * i as f32),
                if i == 2 { 40.0 } else { 9.0 },
                0.9,
            )
        })
        .collect();
    let wet = StrokeStyle {
        wet_pull: Some(0.6),
        load: 0.4,
        dilution: 0.3,
        rendering: Rendering::Gradual,
        ..StrokeStyle::new(StrokeTool::Paint, [0.2, 0.3, 0.8], 1.0)
    };
    let smudge = StrokeStyle::new(StrokeTool::Smudge, [0.0; 3], 1.0);
    let styles = [
        smudge,
        StrokeStyle { blur: 0.7, ..smudge },
        wet,
        StrokeStyle { wet_edges: 0.5, grain: Some(0), grain_depth: 0.8, grain_scale: 32.0, ..wet },
        StrokeStyle { wet_pull: None, laid_in_place: true, burnt_edges: 0.5, ..wet },
        // the Light and Uniform Glazes, shown as far as their own build up
        StrokeStyle { rendering: Rendering::Strongest, wet_edges: 0.3, ..wet },
        StrokeStyle { rendering: Rendering::Capped, ..wet },
    ];
    // a stroke drawn a few stamps a frame, then its layer, and undone
    let drawn = |style: StrokeStyle, most: Option<u64>, big_first: bool| {
        let (mut doc, mut canvas) = patched(&gpu, 150, 110);
        let id = doc.current();
        if let Some(most) = most {
            canvas.smudge_most = most;
        }
        let mut stamps = stamps.clone();
        if big_first {
            stamps.swap(0, 2);
        }
        canvas.begin_stroke(id, style);
        for lot in stamps.chunks(4) {
            canvas.add_stamps(lot);
            canvas.render(&gpu, &mut doc, None);
        }
        canvas.end_stroke();
        canvas.render(&gpu, &mut doc, None);
        assert!(!canvas.take_smudge_refused());
        let done = canvas.read_layer(&gpu, id).unwrap();
        canvas.undo();
        canvas.render(&gpu, &mut doc, None);
        (done, canvas.read_layer(&gpu, id).unwrap())
    };
    // a layer of 150 x 110 takes 84 kB: windows of at most 8 kB (2,048 pixels, 1,024 for paint
    // that keeps both amounts), so each stroke takes many and its widest stamps are cut
    for (k, style) in styles.into_iter().enumerate() {
        for big_first in [false, true] {
            let (roomy, undone) = drawn(style, None, big_first);
            assert_ne!(roomy, undone, "style {k}");
            assert!(drawn(style, Some(8 << 10), big_first) == (roomy, undone), "style {k} {big_first}");
        }
    }
    assert_eq!(gpu.errors(), 0);
}

/// Wet paint that keeps what it has laid tile by tile, its work cut into windows close to what a
/// binding holds, is laid whole, as in roomy ones, and alike where a copy from a texture can put
/// its rows in a buffer only at multiples of 512 bytes (see `crate::placed`). Cut by its pixels
/// alone, a window laid in buffers took up to twice what it was cut to, and work cut close to
/// what a binding holds wasn't laid; laid as it reaches (too big once laid out from a column
/// copies can start at), a tile's part copied in at once wrote over its neighbours' rows.
#[test]
fn wet_paint_cut_close_to_what_a_binding_holds_is_laid_whole_and_alike_where_copies_are_placed() {
    use procraft_engine::geom::Vec2;
    let Ok(gpu) = Gpu::headless() else { return };
    if !gpu.computes() {
        return;
    }
    let (width, height) = (700, 200);
    // every pixel its own colour, opaque
    let picture: Vec<u8> = (0..width * height)
        .flat_map(|i| {
            let (x, y) = (i % width, i / width);
            [(x * 3 % 256) as u8, (y * 5 % 256) as u8, ((x / 7 + y / 3) * 11 % 256) as u8, 255]
        })
        .collect();
    // to and fro across the tiles, stamps of a few sizes
    let stamps: Vec<Stamp> = (0..90)
        .map(|i| {
            let t = i as f32 / 89.0;
            let x = if i < 45 { -10.0 + 1440.0 * t } else { 1430.0 - 1440.0 * t };
            Stamp {
                time_ms: 15.0 * f64::from(i),
                distance: 16.0 * i as f32,
                transfer: 0.5 + 0.004 * i as f32,
                ..Stamp::round(
                    Vec2::new(x, 100.0 + 60.0 * (i as f32 * 0.37).sin()),
                    [9.0, 13.0, 17.0][i as usize % 3],
                    0.8,
                )
            }
        })
        .collect();
    let wet = StrokeStyle {
        wet_pull: Some(0.6),
        load: 0.4,
        dilution: 0.3,
        rendering: Rendering::Gradual,
        ..StrokeStyle::new(StrokeTool::Paint, [0.2, 0.3, 0.8], 1.0)
    };
    // keeping how much each pixel has taken; and laid apart, the stroke as laid and how much of
    // its paint shows besides
    let styles = [wet, StrokeStyle { wet_pull: None, laid_in_place: true, burnt_edges: 0.5, ..wet }];
    // the layer once a stroke has gone over it a few stamps a frame, its windows (and what a
    // binding holds) `room` bytes if given
    let drawn = |style: StrokeStyle, room: Option<u64>, placed: bool| {
        gpu.emulate_placed_copies(placed);
        let mut doc = procraft_engine::document::Document::new(width, height);
        let mut canvas = Canvas::new(&gpu, &doc).unwrap();
        let id = doc.current();
        canvas.upload_layer(&gpu, id, &picture).unwrap();
        if let Some(room) = room {
            (canvas.smudge_most, canvas.smudge_room) = (room, room);
        }
        canvas.begin_stroke(id, style);
        for lot in stamps.chunks(3) {
            canvas.add_stamps(lot);
            canvas.render(&gpu, &mut doc, None);
        }
        canvas.end_stroke();
        canvas.render(&gpu, &mut doc, None);
        assert!(!canvas.take_smudge_refused());
        gpu.emulate_placed_copies(false);
        canvas.read_layer(&gpu, id).unwrap()
    };
    // windows and bindings of at most 64 kB: several stamps together, each stamp's own fitting
    const MOST: u64 = 64 << 10;
    for (k, style) in styles.into_iter().enumerate() {
        let roomy = drawn(style, None, false);
        assert_ne!(roomy, picture, "style {k}");
        assert!(drawn(style, Some(MOST), false) == roomy, "style {k}");
        assert!(drawn(style, Some(MOST), true) == roomy, "style {k}, placed");
    }
    assert_eq!(gpu.errors(), 0);
}

/// What the shaders' `pooled_over` gives for each of `cs` on this GPU.
fn pooled_on_gpu(gpu: &Gpu, cs: &[f32]) -> Vec<f32> {
    let inputs: Vec<[f32; 4]> = cs.iter().map(|&c| [c, 0.0, 0.0, 0.0]).collect();
    probe_on_gpu(gpu, "pooled_over(v.x)", &inputs)
}

/// What the WGSL expression `expr` gives on this GPU for each of `inputs`, as `v` (a vec4),
/// with the shaders' blend functions at hand.
fn probe_on_gpu(gpu: &Gpu, expr: &str, inputs: &[[f32; 4]]) -> Vec<f32> {
    let device = &gpu.device;
    let probe = [
        "@group(0) @binding(0) var<storage, read_write> values: array<vec4<f32>>;",
        "@compute @workgroup_size(64) fn probe(@builtin(global_invocation_id) id: vec3<u32>) {",
        "    if (id.x < arrayLength(&values)) {",
        "        let v = values[id.x];",
        &format!("        values[id.x] = vec4<f32>({expr}, 0.0, 0.0, 0.0);"),
        "    }",
        "}",
    ];
    let source = format!("{}\n{}\n", include_str!("shaders/blend.wgsl"), probe.join("\n"));
    let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("shader probe"),
        source: wgpu::ShaderSource::Wgsl(source.into()),
    });
    let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
        label: Some("shader probe"),
        layout: None,
        module: &module,
        entry_point: Some("probe"),
        compilation_options: wgpu::PipelineCompilationOptions::default(),
        cache: None,
    });
    let bytes: Vec<u8> = inputs.iter().flatten().flat_map(|c| c.to_le_bytes()).collect();
    let size = bytes.len() as u64;
    let values = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("shader probe"),
        size,
        usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    gpu.queue.write_buffer(&values, 0, &bytes);
    let read = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("shader probe read"),
        size,
        usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });
    let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("shader probe"),
        layout: &pipeline.get_bind_group_layout(0),
        entries: &[wgpu::BindGroupEntry { binding: 0, resource: values.as_entire_binding() }],
    });
    let mut encoder =
        device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("shader probe") });
    {
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor::default());
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bind, &[]);
        pass.dispatch_workgroups((inputs.len() as u32).div_ceil(64), 1, 1);
    }
    encoder.copy_buffer_to_buffer(&values, 0, &read, 0, size);
    gpu.queue.submit([encoder.finish()]);
    read.slice(..).map_async(wgpu::MapMode::Read, |_| {});
    device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    let mapped = read.slice(..).get_mapped_range().unwrap();
    mapped.as_chunks::<16>().0.iter().map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect()
}

#[test]
fn wet_edges_pool_thin_paint_as_far_as_it_goes_not_by_the_rounding_of_a_sine() {
    let Ok(gpu) = Gpu::headless() else { return };
    if !gpu.computes() {
        return eprintln!("skipped: the shader is probed in a compute pass");
    }
    // the scale wet edges give paint showing c: (1 - |sin(x) / x|) / c at x = 4.5c, worked out
    // where it doesn't cancel away (the series for a thin layer)
    let exact = |c: f64| {
        let x = 4.5 * c;
        if c <= 0.0 {
            0.0
        } else if x < 0.1 {
            4.5 * x * (1.0 / 6.0 - x * x / 120.0 + x.powi(4) / 5040.0)
        } else {
            (1.0 - (x.sin() / x).abs()) / c
        }
    };
    let mut cs =
        vec![0.0, 1e-7, 1e-6, 1e-5, 1e-4, 4e-4, 1e-3, 0.004, 0.01, 0.05, 0.11, 0.112, 0.3, 0.698, 0.7, 0.9, 1.0];
    cs.extend((0..=1000).map(|i| i as f32 / 1000.0));
    let got = pooled_on_gpu(&gpu, &cs);
    for (&c, &g) in cs.iter().zip(&got) {
        let want = exact(f64::from(c));
        // the paint's own scale, near enough, and the paint never past full for it
        assert!((f64::from(g) - want).abs() <= 1e-6 + 1e-3 * want, "{c}: {g}, not {want}");
        assert!(g * c <= 1.0 + 1e-5, "{c}: {g}");
    }
}

#[test]
fn coverage_divided_by_next_to_nothing_rises_only_as_far_as_the_least_half_float_lets_it() {
    let Ok(gpu) = Gpu::headless() else { return };
    if !gpu.computes() {
        return eprintln!("skipped: the shader is probed in a compute pass");
    }
    // the smallest normal half float
    const LEAST: f32 = 1.0 / 16384.0;
    // coverage, the other amount (a grain, a second), the mode (8 colour dodge, 17 hard mix,
    // 21 divide), and what they come to
    let cases: [(f32, f32, u32, f32); 14] = [
        // divide: faint coverage over nothing rises only as far as the floor lets it, none
        // stays none, stronger coverage is full
        (3.0e-5, 0.0, 21, 3.0e-5 / LEAST),
        (0.0, 0.0, 21, 0.0),
        (0.02, 0.0, 21, 1.0),
        (0.2, 0.4, 21, 0.5),
        // colour dodge the same over all
        (3.0e-5, 1.0, 8, 3.0e-5 / LEAST),
        (0.0, 1.0, 8, 0.0),
        (0.2, 0.6, 8, 0.5),
        // hard mix: none (or next to none) over all stays none; full where vivid light comes
        // to half or more
        (0.0, 1.0, 17, 0.0),
        (1.0e-5, 1.0, 17, 0.0),
        (0.55, 0.5, 17, 1.0),
        (0.45, 0.6, 17, 1.0),
        (0.35, 0.6, 17, 0.0),
        (1.0, 0.0, 17, 1.0),
        (0.9, 0.0, 17, 0.0),
    ];
    let inputs: Vec<[f32; 4]> = cases.iter().map(|&(a, b, mode, _)| [a, b, mode as f32, 0.0]).collect();
    let got = probe_on_gpu(&gpu, "blend_amount(v.x, v.y, u32(v.z))", &inputs);
    for (&(a, b, mode, want), &g) in cases.iter().zip(&got) {
        assert!((g - want).abs() < 1e-3, "mode {mode}: {a} with {b} came to {g}, not {want}");
    }
    // a stroke's second combines by it too
    let second = probe_on_gpu(&gpu, "dual_combine(v.x, v.y, u32(v.z))", &[[1.0e-5, 1.0, 8.0, 0.0]]);
    assert!((second[0] - 1.0e-5 / LEAST).abs() < 1e-3, "{second:?}");
}

#[test]
fn a_stamp_s_grain_raises_faint_coverage_whole_and_a_stroke_s_holds_the_rise_to_what_was_laid() {
    let Ok(gpu) = Gpu::headless() else { return };
    if !gpu.computes() {
        return eprintln!("skipped: the shader is probed in a compute pass");
    }
    // (coverage, grain, depth, mode) through grain_combine: Normal lays the coverage over a
    // grain of a half however faint it is, none stays none, and a falling mode falls
    let combined = probe_on_gpu(
        &gpu,
        "grain_combine(v.x, v.y, v.z, u32(v.w))",
        &[[0.004, 0.5, 1.0, 0.0], [0.0, 0.5, 1.0, 0.0], [0.004, 0.9, 1.0, 8.0], [0.5, 0.5, 1.0, 1.0]],
    );
    let want = [0.004 + 0.5 - 0.002, 0.0, 0.04, 0.25];
    for (g, w) in combined.iter().zip(want) {
        assert!((g - w).abs() < 1e-4, "{combined:?}");
    }
    // (raised, laid) through rise_held: paint laid under a sixty-fourth takes that share of the
    // rise, more takes all of it, none takes none, and a fall is kept
    let full = 0.015_686_275f32;
    let held = probe_on_gpu(
        &gpu,
        "rise_held(v.x, v.y)",
        &[[0.5, 0.004, 0.0, 0.0], [0.5, 0.02, 0.0, 0.0], [0.5, 0.0, 0.0, 0.0], [0.001, 0.004, 0.0, 0.0]],
    );
    let want = [0.004 + 0.004 / full * 0.496, 0.5, 0.0, 0.001];
    for (g, w) in held.iter().zip(want) {
        assert!((g - w).abs() < 1e-4, "{held:?}");
    }
    // a second held the same, but for Normal, which shows it where the first laid nothing
    let dual = probe_on_gpu(&gpu, "dual_rise(v.x, v.y, u32(v.z))", &[[0.5, 0.0, 0.0, 0.0], [0.5, 0.0, 1.0, 0.0]]);
    assert!((dual[0] - 0.5).abs() < 1e-6 && dual[1] == 0.0, "{dual:?}");
}

#[test]
fn wet_edges_where_strokes_cross_stay_the_paint_s_own_colour() {
    use procraft_engine::geom::Vec2;
    let Ok(gpu) = Gpu::headless() else { return };
    let colour = [0.1, 0.5, 0.8];
    let dry = StrokeStyle { wet_edges: 1.0, ..StrokeStyle::new(StrokeTool::Paint, colour, 1.0) };
    // wet paint thinned by its water, as a brush with wet mix laid in place has it
    let wet = StrokeStyle { wet_pull: Some(0.6), load: 0.4, dilution: 0.3, rendering: Rendering::Gradual, ..dry };
    let line = |from: Vec2, to: Vec2, alpha: f32| -> Vec<Stamp> {
        (0..40)
            .map(|i| {
                let f = i as f32 / 39.0;
                Stamp {
                    time_ms: 16.0 * f64::from(i),
                    distance: from.distance(to) * f,
                    ..Stamp::round(from.lerp(to, f), 9.0, alpha)
                }
            })
            .collect()
    };
    for (k, style) in [wet, StrokeStyle { rendering: Rendering::Strongest, ..wet }, dry].into_iter().enumerate() {
        let mut doc = procraft_engine::document::Document::new(120, 120);
        let mut canvas = Canvas::new(&gpu, &doc).unwrap();
        let id = doc.current();
        // across at full, then down, corner to corner and across again lighter, each over the last
        let strokes = [
            (Vec2::new(10.0, 40.0), Vec2::new(110.0, 45.0), 1.0),
            (Vec2::new(40.0, 10.0), Vec2::new(45.0, 110.0), 0.8),
            (Vec2::new(10.0, 10.0), Vec2::new(110.0, 110.0), 0.5),
            (Vec2::new(10.0, 70.0), Vec2::new(110.0, 60.0), 0.3),
        ];
        for (from, to, alpha) in strokes {
            canvas.begin_stroke(id, style);
            for lot in line(from, to, alpha).chunks(5) {
                canvas.add_stamps(lot);
                canvas.render(&gpu, &mut doc, None);
            }
            canvas.end_stroke();
            canvas.render(&gpu, &mut doc, None);
        }
        assert_eq!(gpu.errors(), 0);
        // one colour laid over itself: every pixel is it, as far as it shows (premultiplied,
        // give or take the rounding of each laying)
        let pixels = canvas.read_layer(&gpu, id).unwrap();
        let mut painted = 0;
        for (i, px) in pixels.as_chunks::<4>().0.iter().enumerate() {
            let a = f32::from(px[3]) / 255.0;
            painted += usize::from(a > 0.5);
            for c in 0..3 {
                let want = colour[c] * a;
                let got = f32::from(px[c]) / 255.0;
                assert!(
                    (got - want).abs() <= 6.0 / 255.0,
                    "style {k}: pixel ({}, {}) is {px:?}, not the paint's colour at {a}",
                    i % 120,
                    i / 120,
                );
            }
        }
        assert!(painted > 400, "style {k}: {painted}");
    }
}

#[test]
fn a_smudge_that_runs_out_of_memory_part_way_stops_and_undoes_whole() {
    use procraft_engine::geom::Vec2;
    let Ok(gpu) = Gpu::headless() else { return };
    // (two tiles across: the second where it smudges next)
    let (mut doc, mut canvas) = patched(&gpu, smudge::TILE + 100, 120);
    let id = doc.current();
    let before = canvas.read_layer(&gpu, id).unwrap();
    let steps = canvas.steps_made();
    let dot = |x: f32| Stamp::round(Vec2::new(x, 40.0), 12.0, 1.0);
    canvas.begin_stroke(id, StrokeStyle::new(StrokeTool::Smudge, [0.0; 3], 1.0));
    canvas.add_stamps(&(0..8).map(|i| dot(20.0 + 4.0 * i as f32)).collect::<Vec<_>>());
    canvas.render(&gpu, &mut doc, None);
    let begun = canvas.read_layer(&gpu, id).unwrap();
    assert_ne!(begun, before);
    // the next tiles it reaches can't be made: it lays no more, and says so once
    FAILING.with(|f| f.set(Some("smudge before")));
    let next = smudge::TILE as f32 + 20.0;
    canvas.add_stamps(&(0..8).map(|i| dot(next + 4.0 * i as f32)).collect::<Vec<_>>());
    canvas.render(&gpu, &mut doc, None);
    canvas.end_stroke();
    canvas.render(&gpu, &mut doc, None);
    FAILING.with(|f| f.set(None));
    assert_eq!(gpu.errors(), 0);
    assert!(canvas.take_smudge_refused());
    assert!(!canvas.take_smudge_refused());
    assert_eq!(canvas.read_layer(&gpu, id).unwrap(), begun);
    // what it laid is one step, undone whole
    assert_eq!(canvas.steps_made(), steps + 1);
    canvas.undo();
    canvas.render(&gpu, &mut doc, None);
    assert_eq!(canvas.read_layer(&gpu, id).unwrap(), before);
}

#[test]
fn a_smudge_takes_its_brush_s_smudge_pull() {
    let style = |brush: &Brush| StrokeStyle::for_brush(brush, StrokeTool::Smudge, [0.0; 3], 1.0, false).pull;
    let mut brush = Brush::round_pen();
    // a new brush's
    assert_eq!(style(&brush), 0.75);
    assert_eq!(StrokeStyle::new(StrokeTool::Smudge, [0.0; 3], 1.0).pull, 0.75);
    brush.set_setting("smudge_pull", 0.3);
    assert_eq!(style(&brush), 0.3);
}

#[test]
fn a_stamps_pressure_scales_the_wet_mixes_dilution_and_charge() {
    let style = StrokeStyle {
        dilution: 0.5,
        load: 0.5,
        wet_pull: Some(0.5),
        ..StrokeStyle::new(StrokeTool::Paint, [0.0; 3], 1.0)
    };
    // no dilution at no pressure: the paint goes on whole
    assert_eq!(wet_mixture(&style, 0.0, 1.0, 0.0, 0.0, 1.0).0, 1.0);
    // diluted by half: what it leaves, by 2.4, with no charge
    let (mixed, ..) = wet_mixture(&style, 33.0, 1.0, 0.0, 1.0, 0.0);
    assert!((mixed - 0.5f32.powf(2.4)).abs() < 1e-5);
    // the charge, half run out by 33 px, as far as its curve lets it, goes into the fresh
    // paint with the dilution's; the pull is the brush's to the power 1/8
    let (mixed, pull) = wet_mixture(&style, 33.0, 1.0, 0.0, 1.0, 1.0);
    assert!((mixed - (0.5f32.powf(2.4) + 0.5)).abs() < 1e-5);
    assert!((pull - 0.5f32.powf(0.125)).abs() < 1e-6);
    // at least the attack
    let attacked = StrokeStyle { attack: 0.9, ..style };
    assert!((wet_mixture(&attacked, 1000.0, 1.0, 1.0, 1.0, 0.0).0 - 0.9).abs() < 1e-5);
}

#[test]
fn the_fall_off_runs_the_wet_mixes_paint_out_along_the_stroke() {
    let style = StrokeStyle {
        dilution: 0.5,
        falloff: 0.5,
        wet_pull: Some(0.5),
        ..StrokeStyle::new(StrokeTool::Paint, [0.0; 3], 1.0)
    };
    let diluted = 0.5f32.powf(2.4);
    // whole where the stroke starts, half gone by 33 px, all gone by 264
    assert!((wet_mixture(&style, 0.0, 1.0, 0.0, 1.0, 1.0).0 - diluted).abs() < 1e-5);
    assert!((wet_mixture(&style, 33.0, 1.0, 0.0, 1.0, 1.0).0 - diluted * 0.5).abs() < 1e-5);
    assert!(wet_mixture(&style, 264.0, 1.0, 0.0, 1.0, 1.0).0.abs() < 1e-5);
}

#[test]
fn stamps_laid_by_their_rule_are_listed_in_order_for_each_tile_they_reach() {
    let stamp = |x: f32, y: f32, radius: f32| StampInstance {
        center: [x, y],
        radius,
        roundness: 1.0,
        ..bytemuck::Zeroable::zeroed()
    };
    // one over the first tile, one over the first two across and down, one past the part
    let stamps = [stamp(5.0, 5.0, 2.0), stamp(16.0, 14.0, 3.0), stamp(500.0, 500.0, 2.0)];
    let part = IRect { x0: 0, y0: 0, x1: 40, y1: 20 };
    let t = rule_tiles(&stamps, 7, part, 2).unwrap();
    // the corner, three tiles across (40 px), the rule
    assert_eq!(t[..4], [0, 0, 3, 2]);
    let tile = |i: usize| {
        let (first, count) = (t[4 + 2 * i] as usize, t[5 + 2 * i] as usize);
        t[first..first + count].to_vec()
    };
    // two rows of three tiles: the first two stamps (as the frame's 7th and 8th) in order
    assert_eq!(tile(0), [7, 8]);
    assert_eq!(tile(1), [8]);
    assert!(tile(2).is_empty());
    assert_eq!(tile(3), [8]);
    assert_eq!(tile(4), [8]);
    assert!(tile(5).is_empty());
    // and none reaching the part: nothing to lay
    assert!(rule_tiles(&stamps[2..], 0, part, 1).is_none());
    // the strokes that take their rule this way
    assert_eq!(by_rule(Rendering::Gradual), Some(1));
    assert_eq!(by_rule(Rendering::Capped), Some(2));
    assert_eq!(by_rule(Rendering::BuildUp), None);
    assert_eq!(by_rule(Rendering::Strongest), None);
}

#[test]
fn a_pass_lays_its_stamps_up_to_their_mixture_as_the_grade_says() {
    // the stroke's first pass lays the whole mixture, stamp by stamp
    assert_eq!(final_mixture(0.4, 0.9, 0.0, (0, 4), true), 0.4);
    // later ones ramp up through the pass from the grade's share
    assert_eq!(final_mixture(0.4, 0.9, 0.0, (0, 4), false), 0.0);
    assert!((final_mixture(0.4, 0.9, 0.0, (2, 4), false) - 0.2).abs() < 1e-6);
    assert!((final_mixture(0.4, 0.9, 0.5, (2, 4), false) - 0.3).abs() < 1e-6);
    assert_eq!(final_mixture(0.4, 0.9, 1.0, (0, 4), false), 0.4);
    // neither picking up nor mixing: what it carries, whole
    assert_eq!(final_mixture(0.0, 0.9, 0.0, (1, 4), false), 1.0);
}

#[test]
fn wet_paint_is_laid_a_frame_at_a_time_and_a_preview_a_sample_at_a_time() {
    // a pen's samples (240 a second) within one display frame share a pass on the canvas, and
    // each take their own in a preview
    let times = [1.0, 1.0 + 1000.0 / 240.0, 1.0 + 2000.0 / 240.0, 1.0 + 3000.0 / 240.0];
    assert!(times.iter().all(|&t| wet_pass(t, false) == wet_pass(times[0], false)));
    let preview: Vec<i64> = times.iter().map(|&t| wet_pass(t, true)).collect();
    assert!(preview.windows(2).all(|w| w[0] != w[1]));
    // the next frame's input, the next pass
    assert_ne!(wet_pass(15.0, false), wet_pass(18.0, false));
}
