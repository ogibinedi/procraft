use super::*;
use crate::brush::{Shape, Taper, Tilt};

fn plain() -> Brush {
    Brush {
        streamline: 0.0,
        pressure_smoothing: 0.0,
        spacing: 0.1,
        min_size: 0.0,
        pen_taper: Taper::NONE,
        touch_taper: Taper::NONE,
        ..Brush::round_pen()
    }
}

fn line(n: usize, len: f32) -> Vec<Sample> {
    (0..=n).map(|i| Sample::at(Vec2::new(len * i as f32 / n as f32, 50.0), 1.0, i as f64 * 4.0)).collect()
}

#[test]
fn a_stroke_whose_stamps_follow_its_way_is_laid_as_it_ends_with_its_first_turned() {
    // drawn sample by sample, as the canvas lays them: what is handed out, laid again once
    // its first stamps turn to the way it goes, is how it starts as it ends, so its end lays
    // only the rest. A plain stamp that follows the stroke's way, and stock paint that does.
    let stock = |name: &str| crate::library::library().into_iter().flat_map(|s| s.brushes).find(|b| b.name == name);
    let follows = Brush { shape: crate::brush::Shape { follow: 1.0, ..crate::brush::Shape::default() }, ..plain() };
    let brushes: Vec<Brush> =
        [Some(follows), stock("Palette Knife"), stock("Filbert")].into_iter().flatten().collect();
    assert_eq!(brushes.len(), 3);
    for brush in brushes {
        let name = brush.name.clone();
        let mut stroke = StrokeBuilder::new(brush, 40.0, Pointer::Stylus);
        let (mut laid, mut turned) = (Vec::new(), 0);
        // up the canvas and over: its first stamp turns from how it began
        for i in 0..200 {
            let t = i as f32 / 200.0;
            let at = Vec2::new(50.0 + t * 600.0, 400.0 - (t * 9.0).sin() * 300.0);
            let s = Sample::at(at, 0.3 + 0.6 * (t * 20.0).sin().abs(), f64::from(i) * 4.0);
            let added = stroke.add(&[s]);
            if stroke.take_turned() {
                turned += 1;
                laid = stroke.placed_stamps();
            } else {
                laid.extend(added);
            }
        }
        let all = stroke.finish();
        assert_eq!(turned, 1, "{name}");
        assert!(all.len() > laid.len() && all.starts_with(&laid), "{name}: {} of {}", laid.len(), all.len());
    }
}

#[test]
fn tap_leaves_one_stamp() {
    let mut b = StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    assert_eq!(b.add(&[Sample::at(Vec2::new(3.0, 4.0), 0.5, 0.0)]).len(), 1);
    let all = b.finish();
    assert_eq!(all.len(), 1);
    assert_eq!(all[0].center, Vec2::new(3.0, 4.0));
}

#[test]
fn a_tap_turns_a_stamp_that_follows_the_stroke_as_if_it_went_up_the_screen() {
    use std::f32::consts::{FRAC_PI_2, FRAC_PI_4};
    let tap = |follow: f32, randomise: bool, seed: u32| {
        let mut b = plain();
        b.set_setting("shape_rotation", follow);
        b.shape.randomise = randomise;
        let mut s = StrokeBuilder::with_seed(b, 10.0, Pointer::Finger, seed);
        s.add(&[Sample::at(Vec2::new(3.0, 4.0), 1.0, 0.0)]);
        s.finish()[0].angle
    };
    assert!((tap(1.0, false, 1) + FRAC_PI_2).abs() < 1e-5, "{}", tap(1.0, false, 1));
    // whatever the stroke's random turn
    let off = |a: f32| (a + FRAC_PI_2).sin().abs();
    assert!(off(tap(1.0, true, 1)) < 1e-4, "{}", tap(1.0, true, 1));
    assert!(off(tap(1.0, true, 9)) < 1e-4, "{}", tap(1.0, true, 9));
    // half of it: half of three eighths of a turn
    assert!((tap(0.5, false, 1) + 0.5 * 1.5 * FRAC_PI_4).abs() < 1e-5, "{}", tap(0.5, false, 1));
    // none, and turned the other way, don't turn it from where it was
    assert_eq!(tap(0.0, false, 1), 0.0);
    assert_eq!(tap(-1.0, false, 1), 0.0);
    // a stroke's first stamp turns to the way the stroke goes, not from that
    let mut b = plain();
    b.set_setting("shape_rotation", 1.0);
    let mut s = StrokeBuilder::new(b, 10.0, Pointer::Finger);
    s.add(&line(10, 100.0));
    assert!(s.finish()[0].angle.abs() < 1e-4);
}

#[test]
fn a_stamp_s_colour_jitter_goes_from_the_colour_the_stroke_s_left() {
    // the stroke darkens a colour of brightness 0.8 to 0.4; a stamp lightening by half goes half
    // way from there to white, darkening by half half way to black
    let stroke = Tint { brightness: [0.5, 0.0], ..Tint::NONE };
    let lighter = Tint { brightness: [0.5, 0.5], ..Tint::NONE };
    let darker = Tint { brightness: [0.5, 0.0], ..Tint::NONE };
    let at = |t: Tint, v: f32| v * t.brightness[0] + t.brightness[1];
    assert!((at(stroke.then(lighter), 0.8) - 0.7).abs() < 1e-6);
    assert!((at(stroke.then(darker), 0.8) - 0.2).abs() < 1e-6);
    // and the saturation likewise, towards where the stamp's jitter draws it
    let stroke = Tint { saturation: [0.5, 0.5], ..Tint::NONE };
    let stamp = Tint { saturation: [0.5, 0.1], ..Tint::NONE };
    let both = stroke.then(stamp);
    assert!((0.2 * both.saturation[0] + both.saturation[1] - ((0.2 * 0.5 + 0.5) * 0.5 + 0.1)).abs() < 1e-6);
    assert_eq!(Tint::NONE.then(Tint::NONE), Tint::NONE);
}

#[test]
fn pressure_is_evened_out_unless_the_stroke_is_steady() {
    let b = Brush { pressure_size: 1.0, ..plain() };
    // light, then full pressure halfway along, from a pen reporting 240 times a second
    let samples: Vec<Sample> = (0..40)
        .map(|i| {
            let pressure = if i < 20 { 0.2 } else { 1.0 };
            Sample::at(Vec2::new(i as f32 * 4.0, 50.0), pressure, f64::from(i) * 1000.0 / 240.0)
        })
        .collect();
    let radius_at = |mut s: StrokeBuilder, x: f32| {
        s.add(&samples);
        s.finish().iter().find(|st| st.center.x >= x).map_or(0.0, |st| st.radius)
    };
    // just after the jump the stroke is still on its way up; drawn steady it is there at once
    let soft = radius_at(StrokeBuilder::new(b.clone(), 10.0, Pointer::Stylus), 84.0);
    let steady = radius_at(StrokeBuilder::new(b.clone(), 10.0, Pointer::Stylus).steady(), 84.0);
    assert!(soft < steady * 0.8, "{soft} {steady}");
    assert!((steady - 5.0).abs() < 1e-3);
    // and it gets there
    let late = radius_at(StrokeBuilder::new(b, 10.0, Pointer::Stylus), 150.0);
    assert!(late > 4.5, "{late}");
}

#[test]
fn smoothed_pressure_catches_up_with_the_pen_as_it_lifts() {
    // full pressure, then light for the last few samples, from a pen reporting 240 times a
    // second: smoothed, the pressure comes up to the pen over streamline's window as the
    // stroke ends, as it does unsmoothed, rather than being left behind
    let samples: Vec<Sample> = (0..60)
        .map(|i| {
            let pressure = if i < 54 { 1.0 } else { 0.2 };
            Sample::at(Vec2::new(i as f32 * 4.0, 50.0), pressure, f64::from(i) * 1000.0 / 240.0)
        })
        .collect();
    let ends = |smoothing: f32| {
        let b = Brush { pressure_size: 1.0, pressure_smoothing: smoothing, ..plain() };
        let mut s = StrokeBuilder::new(b, 20.0, Pointer::Stylus);
        s.add(&samples);
        // what is settled as the stroke goes, and the end as it lifts
        let settled = s.stamps().last().map_or(0.0, |st| st.radius);
        (settled, s.finish().last().map_or(0.0, |st| st.radius))
    };
    let ((plain_settled, plain_end), (held, end)) = (ends(0.0), ends(0.5));
    // (still in the window as the stroke goes, the light samples aren't laid yet)
    assert!(held > plain_settled, "{held} {plain_settled}");
    // as it ends, near what the pen pressed (left behind, it would be half as wide again)
    assert!(end < plain_end * 1.2, "{end} {plain_end}");
}

#[test]
fn the_brushs_pressure_curves_take_the_pressure_stabilisation_has_averaged() {
    // a pen pressing fully and lightly by turns, stabilised: its pressure averages to a half
    // before the size curve takes it (a tenth there), rather than the curve's ends averaging
    // to a half
    let samples: Vec<Sample> = (0..200)
        .map(|i| {
            let pressure = if i % 2 == 0 { 1.0 } else { 0.0 };
            Sample::at(Vec2::new(i as f32 * 2.0, 50.0), pressure, f64::from(i) * 1000.0 / 240.0)
        })
        .collect();
    let mut b = Brush { pressure_size: 1.0, ..plain() };
    b.size_curve = Curve::new(&[Vec2::new(0.0, 0.0), Vec2::new(0.5, 0.1), Vec2::new(1.0, 1.0)]);
    b.set_setting("stabilization", 0.5);
    let mut s = StrokeBuilder::new(b, 20.0, Pointer::Stylus);
    s.add(&samples);
    let stamps = s.finish();
    let middle = stamps.get(stamps.len() / 2).map_or(0.0, |st| st.radius);
    assert!(middle < 10.0 * 0.2, "{middle}");
}

#[test]
fn a_steady_stroke_goes_where_its_samples_are() {
    // a stroke drawn for show keeps its path where the pen went, however much streamline,
    // stabilisation and motion filtering the brush has: a wave stays a wave and the pressure
    // swell lands where the pen pressed
    let mut b = Brush { streamline: 0.9, pressure_size: 1.0, ..plain() };
    b.set_setting("stabilization", 0.6);
    b.set_setting("motion_filtering", 0.6);
    let samples: Vec<Sample> = (0..36)
        .map(|i| {
            let t = i as f32 / 35.0;
            let pressure = (-9.0 * (t - 0.5) * (t - 0.5)).exp();
            let at = Vec2::new(100.0 + t * 800.0, 150.0 + (t * 12.0).sin() * 40.0);
            Sample::at(at, pressure, f64::from(i) * 1000.0 / 240.0)
        })
        .collect();
    let drawn = |s: StrokeBuilder| {
        let mut s = s;
        let mut stamps = s.add(&samples);
        stamps.extend(s.finish());
        stamps
    };
    let off_path = |stamps: &[Stamp]| {
        stamps
            .iter()
            .map(|st| (st.center.y - 150.0 - ((st.center.x - 100.0) / 800.0 * 12.0).sin() * 40.0).abs())
            .fold(0.0, f32::max)
    };
    let widest = |stamps: &[Stamp]| {
        stamps.iter().fold((0.0f32, 0.0f32), |m, st| if st.radius > m.0 { (st.radius, st.center.x) } else { m }).1
    };
    let steady = drawn(StrokeBuilder::new(b.clone(), 20.0, Pointer::Stylus).steady());
    let held = drawn(StrokeBuilder::new(b, 20.0, Pointer::Stylus));
    assert!(off_path(&steady) < 3.0, "{}", off_path(&steady));
    assert!(off_path(&held) > 10.0, "{}", off_path(&held));
    assert!((widest(&steady) - 500.0).abs() < 30.0, "{}", widest(&steady));
}

#[test]
fn a_gradual_brush_reaches_in_one_pass_what_its_rule_says() {
    // ten stamps over a point, each adding 0.168 of what is left, squared
    let mut built = 0.0f32;
    for _ in 0..10 {
        built += 0.168 * (1.0 - built) * (1.0 - built);
    }
    let stamp = |alpha: f32| Stamp { overlap: 10.0, ..Stamp::round(Vec2::ZERO, 5.0, alpha) };
    let alpha = stamp(0.168).over_alpha();
    assert!(alpha < 0.168);
    assert!((1.0 - (1.0 - alpha).powi(10) - built).abs() < 1e-4);
    // one stamp at full opacity covers fully either way
    assert!((stamp(1.0).over_alpha() - 1.0).abs() < 1e-6);
    // and a stroke that doesn't build up gradually takes its alpha as it is
    assert_eq!(Stamp::round(Vec2::ZERO, 5.0, 0.168).over_alpha(), 0.168);
}

#[test]
fn a_gradual_brushs_stamps_keep_their_own_alpha_and_say_how_many_go_over_a_point() {
    let stamps = |rendering: Rendering| {
        let b = Brush { rendering, max_opacity: 0.8, ..plain() };
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus);
        s.add(&line(50, 200.0));
        s.finish()
    };
    let (gradual, build_up) = (stamps(Rendering::Gradual), stamps(Rendering::BuildUp));
    assert_eq!(gradual.len(), build_up.len());
    for (g, b) in gradual.iter().zip(&build_up) {
        // the same alpha as any other stroke's: the renderer builds it up pixel by pixel
        assert!((g.alpha - b.alpha).abs() < 1e-6, "{} {}", g.alpha, b.alpha);
        assert_eq!(b.overlap, 0.0);
    }
    // as many as fit across a stamp at the gap between them
    for pair in gradual.windows(2).skip(5).take(20) {
        let [g, next] = pair else { continue };
        let across = 2.0 * g.radius / (next.center - g.center).length();
        assert!(g.overlap > 1.0 && (g.overlap / across - 1.0).abs() < 0.1, "{} {across}", g.overlap);
    }
}

#[test]
fn only_a_round_tip_stops_at_a_least_size() {
    let least = |image: Option<u32>| {
        let mut brush = plain();
        brush.shape.image = image;
        let light: Vec<Sample> = line(20, 200.0).iter().map(|s| Sample { pressure: 0.02, ..*s }).collect();
        let stamps = StrokeBuilder::new(brush, 1.0, Pointer::Stylus).add(&light);
        stamps.iter().map(|s| s.radius).fold(f32::MAX, f32::min)
    };
    assert_eq!(least(None), 0.25);
    assert!(least(Some(0)) < 0.1, "{}", least(Some(0)));
}

#[test]
fn stamps_are_evenly_spaced() {
    let mut b = StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    b.add(&line(37, 100.0));
    let all = b.finish();
    // spacing 0.1 of the round tip's square (10 px is 0.797 of it), half a pixel, and a little
    // with the spacing: about 1.8 px, over 100 px, plus the first stamp
    let square = 10.0 / crate::brushfile::ROUND_TIP_SHARE * SPACED_SQUARE;
    let gap = 0.1 * square + 0.5 + (0.1 - SPACING_FLOOR) / (SPACING_SPAN / 2.0);
    assert_eq!(all.len(), (100.0 / gap) as usize + 1);
    for w in all.windows(2) {
        let d = w[0].center.distance(w[1].center);
        assert!((d - gap).abs() < 1e-3, "gap {d}");
    }
    assert!(b.bounds().x0 <= -5 && b.bounds().x1 >= 105);
}

#[test]
fn a_flat_stamp_lies_as_close_as_its_shorter_side_says() {
    let gap_of = |brush: Brush, sample: &dyn Fn(usize) -> Sample| {
        let mut b = StrokeBuilder::new(brush, 40.0, Pointer::Stylus);
        b.add(&(0..=100).map(sample).collect::<Vec<_>>());
        let all = b.finish();
        let mid = all.len() / 2;
        all[mid].center.distance(all[mid + 1].center)
    };
    let along = |i: usize| Sample::at(Vec2::new(i as f32 * 4.0, 50.0), 1.0, i as f64 * 4.0);
    let square = 40.0 / crate::brushfile::ROUND_TIP_SHARE * SPACED_SQUARE;
    let extra = 0.5 + (0.1 - SPACING_FLOOR) / (SPACING_SPAN / 2.0);
    let round = gap_of(plain(), &along);
    assert!((round - (0.1 * square + extra)).abs() < 1e-3, "{round}");
    // a quarter as tall: a quarter of the size's part of the gap
    let flat = Brush { shape: Shape { roundness: 0.25, ..Shape::default() }, ..plain() };
    let close = gap_of(flat, &along);
    assert!((close - (0.025 * square + extra)).abs() < 1e-3, "{close}");
    // light pressure squashing it brings the stamps closer too
    let mut squashed = Brush { pressure_size: 0.0, ..plain() };
    squashed.set_setting("pressure_roundness", 1.0);
    let light = |i: usize| Sample { pressure: 0.5, ..along(i) };
    let pressed = gap_of(squashed, &light);
    assert!((pressed - (0.05 * square + extra)).abs() < 1e-3, "{pressed}");
    // and a lean that flattens it
    let mut leaning = plain();
    leaning.set_setting("tilt_roundness", 0.5);
    let leant = |i: usize| Sample { tilt: 1.2, ..along(i) };
    let upright = gap_of(leaning.clone(), &along);
    assert!(gap_of(leaning, &leant) < upright * 0.95, "{upright}");
}

#[test]
fn a_tapered_tips_stamps_lie_as_close_as_they_are_small() {
    // tapers at both ends, the end's known as the pen lifts or shown as it is drawn: thinning,
    // or only fading (which leaves the spacing as it is)
    let laid = |size: f32, animated: f32| {
        let mut brush = Brush { spacing: 0.5, ..plain() };
        brush.pen_taper = Taper { start: 0.5, end: 0.5, size, opacity: 1.0 - size };
        brush.set_setting("pen_tip_animation", animated);
        let mut stroke = StrokeBuilder::new(brush, 20.0, Pointer::Stylus);
        stroke.add_tipped(&line(100, 600.0));
        stroke.finish()
    };
    let within =
        |all: &[Stamp], from: f32, to: f32| all.iter().filter(|s| (from..to).contains(&s.center.x)).count();
    for animated in [0.0, 1.0] {
        let (faded, thinned) = (laid(0.0, animated), laid(1.0, animated));
        let gaps: Vec<f32> = faded.windows(2).map(|w| w[0].center.distance(w[1].center)).collect();
        assert!(gaps.iter().all(|g| (g - gaps[0]).abs() < 1e-3), "{gaps:?}");
        for (from, to) in [(0.0, 40.0), (560.0, 601.0)] {
            let (few, many) = (within(&faded, from, to), within(&thinned, from, to));
            assert!(many >= 3 * few, "{animated}: {from}..{to}: {many} {few}");
        }
        // between the tips they lie as far apart as ever
        let middle: Vec<&Stamp> = thinned.iter().filter(|s| (200.0..400.0).contains(&s.center.x)).collect();
        for w in middle.windows(2) {
            assert!((w[0].center.distance(w[1].center) - gaps[0]).abs() < 1e-3);
        }
    }
    // a stroke whose stamps were laid as they came keeps them where they lie as it ends
    let mut brush = Brush { spacing: 0.5, ..plain() };
    brush.pen_taper = Taper { end: 0.5, size: 1.0, ..Taper::NONE };
    let mut stroke = StrokeBuilder::new(brush, 20.0, Pointer::Stylus);
    let laid = stroke.add(&line(100, 600.0));
    stroke.hold_laid();
    let all = stroke.finish();
    assert!(laid.len() > 10 && all.len() >= laid.len());
    assert!(laid.iter().zip(&all).all(|(a, b)| a.center == b.center));
}

#[test]
fn spacing_carries_across_samples() {
    let mut few = StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    few.add(&line(3, 100.0));
    let mut many = StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    many.add(&line(97, 100.0));
    assert_eq!(few.finish().len(), many.finish().len());
}

/// Speed dynamics as measured: the pen's speed on screen (points a frame of a 60 Hz display,
/// whatever the zoom), nothing up to 25 and full 100 further on, rising as the square root,
/// both scaled by the maximum speed distance; Max (1) takes a full-speed stroke down to the
/// brush's least size, -100% (-1) a still one. Straight strokes at steady speeds, slow and
/// fast, at several amounts, zooms and distances: the stamps' size and opacity against the
/// size and opacity worked out from the formula.
#[test]
fn speed_thins_and_fades_strokes_by_their_pace_on_screen_as_measured() {
    // what a pace of `v` points a frame leaves at `amount` (`distance` the maximum speed
    // distance), worked out apart from the engine
    let expected = |amount: f32, v: f32, distance: f32| {
        let s = ((v - 25.0 * distance) / (100.0 * distance)).max(0.0).sqrt().min(1.0);
        if amount >= 0.0 { 1.0 - amount * s } else { 1.0 + amount * (1.0 - s) }
    };
    // a 240 Hz pen at `v` points a frame, the canvas seen at `zoom` points a pixel
    let drawn = |control: &str, amount: f32, v: f32, zoom: f32, distance: f32| {
        let mut b = Brush { min_size: 0.0, ..plain() };
        b.set_setting(control, amount);
        let step = v / 4.0 / zoom;
        let samples: Vec<Sample> = (0..=60)
            .map(|i| Sample::at(Vec2::new(20.0 + step * i as f32, 50.0), 1.0, f64::from(i) * 1000.0 / 240.0))
            .collect();
        let mut s = StrokeBuilder::new(b, 40.0, Pointer::Stylus)
            .seen_at(zoom)
            .with_input(InputSettings { speed_distance: distance, ..InputSettings::default() });
        s.add(&samples);
        let stamps = s.finish();
        // a stamp well into the stroke, the speed settled
        let mid = samples.get(40).map_or(0.0, |s| s.pos.x);
        let at = stamps.iter().min_by(|a, b| (a.center.x - mid).abs().total_cmp(&(b.center.x - mid).abs()));
        at.map_or((0.0, 0.0), |s| (s.radius * 2.0, s.alpha))
    };
    let mut worst: f32 = 0.0;
    for (amount, v, zoom, distance) in [
        // slow (none), fast (half, 0.71) and past full speed, at Max
        (1.0, 20.0, 1.0, 1.0),
        (1.0, 50.0, 1.0, 1.0),
        (1.0, 75.0, 1.0, 1.0),
        (1.0, 160.0, 1.0, 1.0),
        // half, and below 0 (slow strokes thinned), at -50% and -100%
        (0.5, 50.0, 1.0, 1.0),
        (-0.5, 20.0, 1.0, 1.0),
        (-0.5, 75.0, 1.0, 1.0),
        (-1.0, 20.0, 1.0, 1.0),
        (-1.0, 160.0, 1.0, 1.0),
        // the pace is the screen's: zoomed in 3 times or out to a quarter, the same pace on
        // screen goes as far
        (1.0, 50.0, 3.0, 1.0),
        (1.0, 75.0, 0.25, 1.0),
        // the shortest maximum speed distance: a quarter the pace for as much
        (1.0, 12.5, 1.0, 0.25),
        (1.0, 40.0, 1.0, 0.25),
    ] {
        let want = expected(amount, v, distance);
        let (size, _) = drawn("speed_size", amount, v, zoom, distance);
        let (base, _) = drawn("speed_size", 0.0, v, zoom, distance);
        // (a round tip is never drawn less than half a pixel across)
        let off = (size - (want * base).max(0.5)).abs() / base;
        worst = worst.max(off);
        assert!(
            off < 0.01,
            "size at {amount} by {v} pt a frame (zoom {zoom}, distance {distance}): {size} of {base} for {want}"
        );
        // the opacity alike, through the brush's opacity range and its easing
        let (_, alpha) = drawn("speed_opacity", amount, v, zoom, distance);
        let (_, plain_alpha) = drawn("speed_opacity", 0.0, v, zoom, distance);
        let want_alpha = want.max(0.01).powf(crate::brush::FLOW_POWER);
        let ratio = alpha / plain_alpha;
        assert!((ratio - want_alpha).abs() < 0.01, "opacity at {amount} by {v}: {ratio} for {want_alpha}");
    }
    eprintln!("speed size against the formula: off by at most {worst}");
    // Max at full speed: the brush's least size, as the tip of a taper
    let (fastest, _) = drawn("speed_size", 1.0, 200.0, 1.0, 1.0);
    assert!(fastest < 0.05 * 40.0, "{fastest}");
}

#[test]
fn a_speed_amount_below_zero_thins_slow_input() {
    // above 0 faster input takes away; below, slower input does
    assert_eq!(by_speed(0.5, 0.0), 1.0);
    assert_eq!(by_speed(0.5, 1.0), 0.5);
    assert_eq!(by_speed(-0.5, 0.0), 0.5);
    assert_eq!(by_speed(-0.5, 1.0), 1.0);
}

#[test]
fn samples_with_one_time_are_a_sample_apart_at_the_input_s_rate() {
    // a fast stroke thinned by speed: samples all with one time count as a pen's (240 a
    // second) or a finger's (120 a second) apart, as those timed so do
    let radii = |pointer: Pointer, ms: f64| {
        let mut b = plain();
        b.set_setting("speed_size", 1.0);
        let samples: Vec<Sample> =
            (0..40).map(|i| Sample::at(Vec2::new(i as f32 * 20.0, 50.0), 1.0, i as f64 * ms)).collect();
        let mut s = StrokeBuilder::new(b, 10.0, pointer);
        s.add(&samples);
        s.finish().iter().map(|s| s.radius).collect::<Vec<f32>>()
    };
    for (pointer, rate) in [(Pointer::Stylus, 240.0), (Pointer::Finger, 120.0)] {
        let (at_once, timed) = (radii(pointer, 0.0), radii(pointer, 1000.0 / rate));
        assert_eq!(at_once.len(), timed.len(), "{pointer:?}");
        let off = at_once.iter().zip(&timed).map(|(a, b)| (a - b).abs()).fold(0.0, f32::max);
        assert!(off < 1e-3, "{pointer:?}: {off}");
    }
    // (and the two differ: a finger's samples are twice as far apart in time)
    assert_ne!(radii(Pointer::Finger, 0.0), radii(Pointer::Finger, 1000.0 / 240.0));
}

#[test]
fn pressure_scales_stamps_but_not_fingers() {
    let samples = [Sample::at(Vec2::new(0.0, 0.0), 0.0, 0.0)];
    let mut pen = StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    let light = pen.add(&samples);
    let mut finger = StrokeBuilder::new(plain(), 10.0, Pointer::Finger);
    let touch = finger.add(&samples);
    assert!(light[0].radius < touch[0].radius);
    assert_eq!(touch[0].radius, 5.0);
    // a brush that thins as it is pressed: a finger leaves it whole too
    let thinning = Brush { pressure_size: -0.75, pressure_opacity: -0.5, ..plain() };
    let mut pen = StrokeBuilder::new(thinning.clone(), 10.0, Pointer::Stylus);
    let pressed = pen.add(&[Sample::at(Vec2::new(0.0, 0.0), 1.0, 0.0)]);
    let mut finger = StrokeBuilder::new(thinning, 10.0, Pointer::Finger);
    let touch = finger.add(&samples);
    assert!(pressed[0].radius < touch[0].radius && pressed[0].alpha < touch[0].alpha);
    assert_eq!(touch[0].radius, 5.0);
}

#[test]
fn a_finger_given_a_pressure_draws_with_it_as_a_pen_does() {
    let radius = |mut b: StrokeBuilder, pressure: f32| b.add(&[Sample::at(Vec2::ZERO, pressure, 0.0)])[0].radius;
    let finger = || StrokeBuilder::new(plain(), 10.0, Pointer::Finger);
    let pen = || StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    // a finger's pressure doesn't count, unless it is given
    assert_eq!(radius(finger(), 0.25), 5.0);
    let given = radius(finger().with_given_pressure(), 0.25);
    assert!(given < 4.0, "{given}");
    assert!((given - radius(pen(), 0.25)).abs() < 1e-5, "{given} {}", radius(pen(), 0.25));
    assert_eq!(radius(finger().with_given_pressure(), 1.0), 5.0);
    // the app's pressure curve is the pen's: a pressure given goes on as it was given
    let curve = Curve::new(&[Vec2::ZERO, Vec2::new(1.0, 0.5)]);
    let input = InputSettings { pressure_curve: curve, ..InputSettings::default() };
    assert!(radius(pen().with_input(input.clone()), 1.0) < 4.0);
    assert_eq!(radius(finger().with_given_pressure().with_input(input), 1.0), 5.0);
    // and its opacity, by a brush that lightens as it is pressed less
    let lightens = Brush { pressure_opacity: 1.0, ..plain() };
    let alpha = |pointer: Pointer, given: bool| {
        let b = StrokeBuilder::new(lightens.clone(), 10.0, pointer);
        let mut b = if given { b.with_given_pressure() } else { b };
        b.add(&[Sample::at(Vec2::ZERO, 0.3, 0.0)])[0].alpha
    };
    assert!(alpha(Pointer::Finger, false) > alpha(Pointer::Stylus, false));
    assert!(alpha(Pointer::Finger, true) < alpha(Pointer::Finger, false));
    assert!((alpha(Pointer::Finger, true) - alpha(Pointer::Stylus, false)).abs() < 1e-5);
}

#[test]
fn a_pressure_s_tapers_grow_it_from_nothing_and_let_it_fall_to_nothing() {
    // pressed fully along 200 px, the pressure tapering in over 40 and out over 60
    let stroke = |b: StrokeBuilder| {
        let mut b = b;
        b.add(&line(50, 200.0));
        b.finish()
    };
    let tapered = |pointer: Pointer| StrokeBuilder::new(plain(), 10.0, pointer).with_pressure_tapers(40.0, 60.0);
    let given = stroke(tapered(Pointer::Finger).with_given_pressure());
    let length = given.last().unwrap().distance;
    let at = |stamps: &[Stamp], d: f32| {
        stamps.iter().min_by(|a, b| (a.distance - d).abs().total_cmp(&(b.distance - d).abs())).unwrap().radius
    };
    let shown: Vec<String> =
        [0.0, 10.0, 20.0, 30.0, 40.0, 100.0].iter().map(|&d| format!("{d}: {:.2}", at(&given, d))).collect();
    let fall: Vec<String> = [60.0, 45.0, 30.0, 15.0, 0.0]
        .iter()
        .map(|&left| format!("{left} before the end: {:.2}", at(&given, length - left)))
        .collect();
    println!("radius along a 10 px stroke tapered over 40 and 60: {shown:?}, {fall:?}");
    // at each end the size the brush gives no pressure (15% of it), the full size between,
    // growing and falling evenly: half way, half way between
    let (least, half) = (5.0 * 0.15, 5.0 * (0.15 + 0.85 / 2.0));
    assert!(
        (at(&given, 0.0) - least).abs() < 0.05 && (at(&given, length) - least).abs() < 0.05,
        "{shown:?} {fall:?}"
    );
    assert_eq!(at(&given, 100.0), 5.0);
    assert!((at(&given, 20.0) - half).abs() < 0.1 && (at(&given, length - 30.0) - half).abs() < 0.1);
    let rising = given.iter().filter(|s| s.distance <= 40.0).map(|s| s.radius);
    assert!(rising.clone().zip(rising.skip(1)).all(|(a, b)| b >= a - 1e-4));
    let falling = given.iter().filter(|s| s.distance >= length - 60.0).map(|s| s.radius);
    assert!(falling.clone().zip(falling.skip(1)).all(|(a, b)| b <= a + 1e-4));
    // tapered closer together, as the brush's own tapers lie
    assert!(given.len() > stroke(StrokeBuilder::new(plain(), 10.0, Pointer::Finger)).len());
    // a pen takes them too; a finger whose pressure doesn't count, none
    let pen = stroke(tapered(Pointer::Stylus));
    assert!((at(&pen, 0.0) - least).abs() < 0.05 && at(&pen, 100.0) == 5.0);
    assert!(stroke(tapered(Pointer::Finger)).iter().all(|s| s.radius == 5.0));
    // and with none, a pen's stroke is as it always was
    let plain_pen = stroke(StrokeBuilder::new(plain(), 10.0, Pointer::Stylus));
    assert_eq!(
        stroke(StrokeBuilder::new(plain(), 10.0, Pointer::Stylus).with_pressure_tapers(0.0, 0.0)),
        plain_pen
    );
    // a tap keeps its whole pressure
    let mut tap = tapered(Pointer::Finger).with_given_pressure();
    tap.add(&[Sample::at(Vec2::new(5.0, 5.0), 1.0, 0.0)]);
    assert_eq!(tap.finish().iter().map(|s| s.radius).collect::<Vec<_>>(), [5.0]);
}

#[test]
fn a_pressure_s_fall_shows_as_the_stroke_is_drawn_and_its_end_lays_the_rest() {
    let mut brush = plain();
    brush.set_setting("pen_tip_animation", 1.0);
    let make = |brush: &Brush| {
        StrokeBuilder::new(brush.clone(), 10.0, Pointer::Finger)
            .with_given_pressure()
            .with_pressure_tapers(0.0, 50.0)
    };
    assert!(make(&brush).shows_tip());
    let mut b = make(&brush);
    let samples = line(50, 200.0);
    let mut kept = Vec::new();
    let mut tip = Vec::new();
    for s in samples.chunks(5) {
        let (k, t) = b.add_tipped(s);
        kept.extend(k);
        tip = t;
    }
    // drawn for good up to where the fall would begin, the rest the tip, falling to its end
    assert!(kept.iter().all(|s| s.radius == 5.0) && !kept.is_empty());
    let (Some(first), Some(last)) = (tip.first(), tip.last()) else { panic!("no tip") };
    assert!(first.radius > 4.0 && last.radius < 1.0, "{} {}", first.radius, last.radius);
    // its end lays only what wasn't drawn for good
    let laid = kept.len();
    let rest = b.finish_after(laid).expect("laid as it was drawn");
    assert!(!rest.is_empty() && rest.last().unwrap().radius < 1.0);
    // off, the fall is laid once it ends: what was laid at full size is laid again
    brush.set_setting("pen_tip_animation", 0.0);
    let mut b = make(&brush);
    assert!(!b.shows_tip());
    let laid = b.add(&samples).len();
    assert!(b.finish_after(laid).is_none());
}

#[test]
fn a_finger_presses_fully_whatever_the_brush_s_curves() {
    use crate::curve::Curve;
    // a pressure response and a bleed curve that never reach full: a pen pressed all the way
    // reads them so, a finger (no pressure) as full
    let mut brush = plain();
    brush.pressure_curve = Curve::new(&[Vec2::new(0.0, 0.0), Vec2::new(1.0, 0.5)]);
    brush.pressure_curves.set(PressureCurve::Bleed, Some(Curve::new(&[Vec2::ZERO, Vec2::new(1.0, 0.8)])));
    brush.set_setting("pressure_bleed", 0.5);
    let at = Sample::at(Vec2::new(0.0, 0.0), 1.0, 0.0);
    let pen = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Stylus).add(&[at]);
    let finger = StrokeBuilder::new(brush, 10.0, Pointer::Finger).add(&[at]);
    assert!((pen[0].grain_press - 0.4).abs() < 0.01, "{}", pen[0].grain_press);
    assert_eq!(finger[0].grain_press, 1.0);
}

#[test]
fn a_pressure_transfer_below_none_lets_everything_through() {
    // the transfer's amount takes away what the pressure's curve leaves; below 0 it would
    // add past full, so everything goes through
    let transfer = |amount: f32, pressure: f32| {
        let mut brush = plain();
        brush.set_setting("pressure_flow", amount);
        let at = Sample::at(Vec2::new(0.0, 0.0), pressure, 0.0);
        StrokeBuilder::new(brush, 10.0, Pointer::Stylus).add(&[at])[0].transfer
    };
    assert!((transfer(0.6, 0.25) - (1.0 - 0.6 * 0.75)).abs() < 1e-4);
    assert_eq!(transfer(-0.6, 0.25), 1.0);
    assert_eq!(transfer(-0.6, 1.0), 1.0);
}

#[test]
fn pressure_and_speed_take_the_size_down_to_the_brush_s_least() {
    let brush = Brush { pressure_size: 1.0, min_size: 6.0, ..plain() };
    let radius = |brush: &Brush, pressure: f32| {
        let mut stroke = StrokeBuilder::new(brush.clone(), 26.0, Pointer::Stylus);
        stroke.add(&[Sample::at(Vec2::new(0.0, 0.0), pressure, 0.0)])[0].radius
    };
    // no pressure leaves the least, half of it half way from there to the size
    assert!((radius(&brush, 0.0) - 3.0).abs() < 1e-4);
    assert!((radius(&brush, 0.5) - 8.0).abs() < 1e-4);
    assert!((radius(&brush, 1.0) - 13.0).abs() < 1e-4);
    // a size at the least doesn't follow the pressure at all
    let thin = |pressure: f32| {
        let mut stroke = StrokeBuilder::new(brush.clone(), 6.0, Pointer::Stylus);
        stroke.add(&[Sample::at(Vec2::new(0.0, 0.0), pressure, 0.0)])[0].radius
    };
    assert_eq!(thin(0.1), thin(1.0));
    // the least goes with the brush's scale on the canvas
    let mut scaled = StrokeBuilder::new(brush.clone(), 52.0, Pointer::Stylus).scaled(2.0);
    assert!((scaled.add(&[Sample::at(Vec2::ZERO, 0.0, 0.0)])[0].radius - 6.0).abs() < 1e-4);
    // the shape's height goes there too: a squashed stamp fills out as the least holds it up
    let flat = Brush { shape: Shape { roundness: 0.5, ..Shape::default() }, ..brush.clone() };
    let round = |pressure: f32| {
        let mut stroke = StrokeBuilder::new(flat.clone(), 26.0, Pointer::Stylus);
        stroke.add(&[Sample::at(Vec2::ZERO, pressure, 0.0)])[0].roundness
    };
    assert!((round(1.0) - 16.0 / 26.0).abs() < 1e-4, "{}", round(1.0));
    assert!((round(0.0) - 1.0).abs() < 1e-4, "{}", round(0.0));
    // a pen's pressure faded into a taper thins it as far as the least too
    let mut faded = Brush { pen_taper: Taper { start: 0.0, end: 0.0, size: 0.0, opacity: 0.0 }, ..brush };
    faded.pen_taper.start = 1.0;
    faded.set_setting("pen_taper_pressure", 1.0);
    let mut stroke = StrokeBuilder::new(faded, 26.0, Pointer::Stylus);
    let samples: Vec<Sample> =
        (0..40).map(|i| Sample::at(Vec2::new(i as f32 * 10.0, 0.0), 1.0, f64::from(i) * 8.0)).collect();
    stroke.add(&samples);
    let all = stroke.finish();
    assert!((all[0].radius - 3.0).abs() < 1e-3, "{}", all[0].radius);
    assert!((all.last().unwrap().radius - 13.0).abs() < 1e-3);
}

#[test]
fn streamline_is_strong_from_the_first_and_none_at_none() {
    assert_eq!(streamline_keep(0.0), 0.0);
    assert!((streamline_keep(0.5) - 0.8803).abs() < 1e-3);
    assert!((streamline_keep(1.0) - 0.96).abs() < 1e-6);
    // a corner taken at a steady 600 a second rounds more the more there is, a good way off it
    // at the top
    let cut = |streamline: f32| {
        let brush = Brush { streamline, ..plain() };
        let mut b = StrokeBuilder::new(brush, 4.0, Pointer::Finger);
        let n = 60;
        let at = |x: f32, y: f32, i: usize| Sample::at(Vec2::new(x, y), 1.0, i as f64 * 1000.0 / 120.0);
        let mut samples: Vec<Sample> = (0..=n).map(|i| at(i as f32 * 5.0, 0.0, i)).collect();
        samples.extend((1..=n).map(|i| at(300.0, i as f32 * 5.0, n + i)));
        b.add(&samples);
        let corner = Vec2::new(300.0, 0.0);
        b.finish().iter().map(|s| s.center.distance(corner)).fold(f32::MAX, f32::min)
    };
    assert!(cut(0.0) < 1.0);
    assert!(cut(0.6) > 15.0 && cut(0.6) < 35.0, "{}", cut(0.6));
    assert!(cut(1.0) > 45.0, "{}", cut(1.0));
}

#[test]
fn streamline_smooths_a_zigzag() {
    let zigzag: Vec<Sample> = (0..200)
        .map(|i| Sample::at(Vec2::new(i as f32, if i % 2 == 0 { 0.0 } else { 10.0 }), 1.0, i as f64 * 8.0))
        .collect();
    let spread = |streamline: f32| {
        let mut b = StrokeBuilder::new(Brush { streamline, ..plain() }, 4.0, Pointer::Stylus);
        b.add(&zigzag);
        let s = b.stamps();
        let ys: Vec<f32> = s[s.len() / 2..].iter().map(|s| s.center.y).collect();
        ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min)
    };
    assert!(spread(0.9) < spread(0.0) * 0.5);
}

#[test]
fn an_exact_path_is_not_smoothed() {
    let zigzag: Vec<Sample> = (0..200)
        .map(|i| Sample::at(Vec2::new(i as f32, if i % 2 == 0 { 0.0 } else { 10.0 }), 1.0, i as f64 * 8.0))
        .collect();
    let brush = Brush { streamline: 0.9, ..plain() };
    let mut b = StrokeBuilder::new(brush, 4.0, Pointer::Stylus).exact_path();
    b.add(&zigzag);
    let ys: Vec<f32> = b.stamps().iter().map(|s| s.center.y).collect();
    assert!(ys.iter().any(|&y| y > 9.9) && ys.iter().any(|&y| y < 0.1));
}

#[test]
fn finish_catches_up_and_tapers_the_end() {
    let brush =
        Brush { streamline: 0.9, pen_taper: Taper { start: 0.0, end: 0.5, size: 1.0, opacity: 0.0 }, ..plain() };
    let mut b = StrokeBuilder::new(brush, 4.0, Pointer::Stylus);
    b.add(&line(50, 300.0));
    let live_end = b.stamps().last().unwrap().center;
    let all = b.finish();
    let end = all.last().unwrap();
    assert!(end.center.distance(Vec2::new(300.0, 50.0)) < 1.0);
    assert!(live_end.x < 299.0, "streamline lags while drawing");
    assert!(end.radius < 0.5);
    assert!(all[all.len() / 2].radius > 1.9);
}

#[test]
fn the_stroke_shown_curves_in_to_the_input_as_it_will_be_kept() {
    let brush = Brush { streamline: 0.9, ..plain() };
    // along, then turning sharply away: the path falls well behind the input
    let mut samples = line(40, 200.0);
    let corner = samples.last().unwrap().pos;
    samples.extend((1..=10).map(|i| {
        let pos = corner + Vec2::new(0.0, i as f32 * 8.0);
        Sample::at(pos, 1.0, 160.0 + f64::from(i) * 4.0)
    }));
    let input = samples.last().unwrap().pos;
    let mut b = StrokeBuilder::new(brush, 4.0, Pointer::Finger);
    let kept = b.add(&samples);
    let (count, length) = (b.stamp_count(), b.length());
    assert!(kept.last().unwrap().center.distance(input) > 20.0, "the path lags");
    let ahead = b.preview_to_input(&[]);
    // to the input, as near as the stamps' spacing goes
    let reached = ahead.last().unwrap().center;
    assert!(reached.distance(input) < 2.0, "{reached:?}");
    // the stroke itself left as it was
    assert_eq!((b.stamp_count(), b.length()), (count, length));
    // and what shows is what lifting there would leave
    let kept_at_lift = b.clone().finish();
    let shown: Vec<Vec2> = kept.iter().chain(&ahead).map(|s| s.center).collect();
    assert!(shown.len().abs_diff(kept_at_lift.len()) <= 1, "{} and {}", shown.len(), kept_at_lift.len());
    for (a, b) in shown.iter().zip(&kept_at_lift) {
        assert!(a.distance(b.center) < 1.0, "{a:?} and {:?}", b.center);
    }
    // with nothing to catch up, nothing more is shown
    let mut plain_stroke = StrokeBuilder::new(plain(), 4.0, Pointer::Finger);
    plain_stroke.add(&line(10, 50.0));
    let end = plain_stroke.preview_to_input(&[]).last().map(|s| s.center);
    assert!(end.is_none_or(|e| e.distance(Vec2::new(50.0, 50.0)) < 2.0), "{end:?}");
}

#[test]
fn motion_filtering_keeps_the_stroke_on_the_pen_only_with_the_tip_attached() {
    let along: Vec<Sample> =
        (0..200).map(|i| Sample::at(Vec2::new(i as f32 * 4.0, 50.0), 1.0, i as f64 * 8.0)).collect();
    let pen = along.last().unwrap().pos;
    let stroke = |tip_attached: bool| {
        let input = InputSettings { motion_filtering: 0.3, tip_attached, ..InputSettings::default() };
        let mut b = StrokeBuilder::new(plain(), 4.0, Pointer::Finger).with_input(input);
        b.add(&along);
        let shown = b.preview_to_input(&[]);
        (shown.last().map(|s| s.center), b.finish().last().map(|s| s.center))
    };
    let (shown, kept) = stroke(true);
    assert!(shown.is_some_and(|s| s.distance(pen) < 2.0) && kept.is_some_and(|k| k.distance(pen) < 2.0));
    let (shown, kept) = stroke(false);
    assert!(shown.is_some_and(|s| s.distance(pen) > 20.0), "{shown:?}");
    assert!(kept.is_some_and(|k| k.distance(pen) > 20.0), "{kept:?}");
    // without a filter it has nothing to do
    let input = InputSettings { tip_attached: false, ..InputSettings::default() };
    let mut b = StrokeBuilder::new(plain(), 4.0, Pointer::Finger).with_input(input);
    b.add(&along);
    assert!(b.finish().last().is_some_and(|s| s.center.distance(pen) < 2.0));
}

/// Each point of `input` followed by the path, the newest of `window` followed more and more:
/// what a stroke of it that ends there looks like, point by point.
fn streamlined(input: &[Vec2], amount: f32, window: usize) -> Vec<Vec2> {
    let strength = 1.0 - streamline_keep(amount);
    let mut at = input[0];
    input
        .iter()
        .enumerate()
        .map(|(i, p)| {
            let left = input.len() - i;
            let near = (window as f32 - left as f32).max(0.0) / (window - 1) as f32;
            let near = near.powf(1.0 - strength);
            at = at.lerp(*p, strength + near * (near - strength));
            at
        })
        .collect()
}

#[test]
fn streamline_comes_up_to_the_input_over_its_window() {
    // a finger's points at their own rate, turning: the window is 6 of them, a pen's 12
    for (pointer, ms, window) in [(Pointer::Finger, 1000.0 / 120.0, 6), (Pointer::Stylus, 1000.0 / 240.0, 12)] {
        let curve: Vec<Vec2> = (0..200)
            .map(|i| {
                let t = i as f32 * 0.03 * ms as f32 / 8.0;
                Vec2::new(300.0 + 200.0 * t.cos(), 300.0 + 200.0 * t.sin())
            })
            .collect();
        let samples: Vec<Sample> =
            curve.iter().enumerate().map(|(i, p)| Sample::at(*p, 1.0, i as f64 * ms)).collect();
        let brush = Brush { streamline: 0.66, ..plain() };
        let mut b = StrokeBuilder::new(brush, 2.0, pointer);
        b.add(&samples);
        let stamps = b.finish();
        // the last stretch of the stroke lies on those points, and ends on the input's last
        let wanted = streamlined(&curve, 0.66, window);
        for p in wanted.iter().rev().take(window + 20) {
            let off = stamps.iter().map(|s| s.center.distance(*p)).fold(f32::MAX, f32::min);
            assert!(off < 1.5, "{pointer:?}: {p:?} is {off} from the stroke");
        }
        let end = stamps.last().unwrap().center;
        assert!(end.distance(*curve.last().unwrap()) < 2.0, "{pointer:?}: {end:?}");
    }
}

#[test]
fn streamline_comes_up_to_the_input_alike_at_any_rate() {
    let at = |t: f32| Vec2::new(300.0 + 200.0 * t.cos(), 300.0 + 200.0 * t.sin());
    // the same curve in the same time at twice and half a finger's rate
    let ends = [60.0, 120.0, 240.0].map(|rate: f64| {
        let count = (rate * 0.8) as usize;
        let samples: Vec<Sample> = (0..=count)
            .map(|i| Sample::at(at(i as f32 / count as f32 * 3.0), 1.0, i as f64 * 1000.0 / rate))
            .collect();
        let mut b = StrokeBuilder::new(Brush { streamline: 0.66, ..plain() }, 2.0, Pointer::Finger);
        b.add(&samples);
        b.finish()
    });
    // 100 ms before the lift the three lie together, whatever the rate
    let at_share = |stamps: &Vec<Stamp>| stamps[stamps.len() * 7 / 8].center;
    let [a, b, c] = [at_share(&ends[0]), at_share(&ends[1]), at_share(&ends[2])];
    assert!(a.distance(b) < 12.0 && b.distance(c) < 12.0, "{a:?} {b:?} {c:?}");
}

/// Three quarters of a circle `radius` across, drawn in `ms` as `count` samples after the
/// first, handed over `batch` at a time each batch timed as one (a hundredth of a millisecond
/// apart, as a desktop stamps a pointer history as it hands it on).
fn c_curve(radius: f32, count: usize, ms: f64, batch: usize) -> Vec<Sample> {
    let step = ms / count as f64;
    (0..=count)
        .map(|i| {
            let a = 0.25 * PI as f32 + 1.5 * PI as f32 * i as f32 / count as f32;
            let at = Vec2::new(300.0 + radius * a.cos(), 300.0 + radius * a.sin());
            let time = (i / batch) as f64 * step * batch as f64 + 0.01 * (i % batch) as f64;
            Sample::at(at, 1.0, time)
        })
        .collect()
}

/// The largest turn (degrees) between neighbouring stretches of the stamps' path, taken in
/// stretches of at least `least` canvas pixels.
fn sharpest_turn(stamps: &[Stamp], least: f32) -> f32 {
    let mut path: Vec<Vec2> = Vec::new();
    for s in stamps {
        if path.last().is_none_or(|l| l.distance(s.center) >= least) {
            path.push(s.center);
        }
    }
    path.windows(3)
        .map(|w| {
            let (a, b) = (w[1] - w[0], w[2] - w[1]);
            (a.x * b.y - a.y * b.x).atan2(a.x * b.x + a.y * b.y).abs().to_degrees()
        })
        .fold(0.0, f32::max)
}

/// `samples` drawn `batch` at a time, as a host hands them on.
fn drawn_in_batches(brush: Brush, pointer: Pointer, samples: &[Sample], batch: usize) -> Vec<Stamp> {
    let mut b = StrokeBuilder::new(brush, 6.0, pointer);
    for some in samples.chunks(batch) {
        b.add(some);
    }
    b.finish()
}

#[test]
fn a_fast_stroke_curves_through_its_samples_with_no_corners() {
    // a C drawn in 150 ms: 9 samples (a mouse at 60 Hz), and a pen's 36 handed over 4 a frame
    for (pointer, count, batch) in [(Pointer::Mouse, 9, 1), (Pointer::Finger, 9, 1), (Pointer::Stylus, 36, 4)] {
        let samples = c_curve(100.0, count, 150.0, batch);
        let stamps = drawn_in_batches(plain(), pointer, &samples, batch);
        // a 3 px stretch of the circle turns 1.7 degrees: the path bends no sharper than about that
        let turn = sharpest_turn(&stamps, 3.0);
        assert!(turn < 4.0, "{pointer:?}: turns {turn} degrees at once");
        // and goes through every sample
        for s in &samples {
            let off = stamps.iter().map(|t| t.center.distance(s.pos)).fold(f32::MAX, f32::min);
            assert!(off < 1.0, "{pointer:?}: {:?} is {off} from the stroke", s.pos);
        }
    }
}

#[test]
fn a_streamlined_stroke_handed_over_in_batches_bends_smoothly() {
    // a pen's samples at 240 Hz, four a frame each frame timed as one: timed as they came, all
    // but the first of each would hardly move the path, so it went on a frame at a time, turning
    // 19 to 31 degrees at each
    for streamline in [0.1, 0.35, 0.6] {
        let brush = Brush { streamline, ..plain() };
        let samples = c_curve(100.0, 36, 150.0, 4);
        let turn = sharpest_turn(&drawn_in_batches(brush.clone(), Pointer::Stylus, &samples, 4), 3.0);
        let even = sharpest_turn(&drawn_in_batches(brush, Pointer::Stylus, &c_curve(100.0, 36, 150.0, 1), 1), 3.0);
        assert!(
            turn < 7.0 && turn < even * 1.6,
            "{streamline}: turns {turn} degrees at once ({even} timed evenly)"
        );
    }
}

#[test]
fn streamline_takes_samples_handed_over_together_as_if_each_came_in_its_turn() {
    // the per-sample model at the tuned rates (as `streamline_comes_up_to_the_input_over_its_window`),
    // the samples coming four at a time with one time each
    for (pointer, ms, window) in [(Pointer::Finger, 1000.0 / 120.0, 6), (Pointer::Stylus, 1000.0 / 240.0, 12)] {
        let curve: Vec<Vec2> = (0..160)
            .map(|i| {
                let t = i as f32 * 0.03 * ms as f32 / 8.0;
                Vec2::new(300.0 + 200.0 * t.cos(), 300.0 + 200.0 * t.sin())
            })
            .collect();
        let samples: Vec<Sample> = curve
            .iter()
            .enumerate()
            .map(|(i, p)| Sample::at(*p, 1.0, (i / 4) as f64 * 4.0 * ms + 0.01 * (i % 4) as f64))
            .collect();
        let stamps = drawn_in_batches(Brush { streamline: 0.66, ..plain() }, pointer, &samples, 4);
        let wanted = streamlined(&curve, 0.66, window);
        for p in wanted.iter().skip(8) {
            let off = stamps.iter().map(|s| s.center.distance(*p)).fold(f32::MAX, f32::min);
            assert!(off < 1.5, "{pointer:?}: {p:?} is {off} from the stroke");
        }
    }
}

#[test]
fn with_no_smoothing_a_slow_line_read_in_whole_pixels_evens_its_steps_out_and_read_finer_goes_straight() {
    // a pen drawn slowly across at 30 degrees, 100 px a second, read at 240 Hz and handed over
    // four a frame, each frame's with one time
    let (start, way) = (Vec2::new(100.3, 100.6), Vec2::new(30f32.to_radians().cos(), 30f32.to_radians().sin()));
    let read = |whole: bool| -> Vec<Sample> {
        (0..=240)
            .map(|i| {
                let p = start + way * (100.0 * i as f32 / 240.0);
                let p = if whole { Vec2::new(p.x.round(), p.y.round()) } else { p };
                Sample::at(p, 0.6, (i / 4) as f64 * 1000.0 / 60.0 + 0.01 * (i % 4) as f64)
            })
            .collect()
    };
    // how far the stroke strays across the line, its ends left out
    let strays = |samples: &[Sample]| {
        let stamps = drawn_in_batches(plain(), Pointer::Stylus, samples, 4);
        let across: Vec<f32> = stamps.iter().map(|s| (s.center - start).dot(Vec2::new(-way.y, way.x))).collect();
        let middle = &across[across.len() / 10..across.len() * 9 / 10];
        let mean = middle.iter().sum::<f32>() / middle.len() as f32;
        middle.iter().map(|a| (a - mean).abs()).fold(0.0, f32::max)
    };
    // StreamLine off, read finer than a pixel: straight
    let straight = strays(&read(false));
    assert!(straight < 0.05, "{straight}");
    // read in whole pixels, a staircase: through every step the stroke strayed 0.59 px; its
    // points evened out with their neighbours, 0.25, though still more than read finer
    let stepped = strays(&read(true));
    assert!(stepped < 0.3 && stepped > straight, "{stepped}");
}

#[test]
fn with_no_smoothing_quick_circles_read_in_whole_pixels_stay_round() {
    // circles drawn quickly with a pen read at 240 Hz in whole pixels, handed over four a frame,
    // each frame's with one time: their radius, how many samples go round and in how long
    for (radius, count, ms) in
        [(100.0f32, 36usize, 150.0f64), (60.0, 80, 333.0), (10.0, 24, 100.0), (5.0, 12, 50.0)]
    {
        let centre = Vec2::new(200.3, 200.6);
        let samples: Vec<Sample> = (0..=count)
            .map(|i| {
                let a = TAU as f32 * i as f32 / count as f32;
                let p = centre + Vec2::new(a.cos(), a.sin()) * radius;
                let frame = (i / 4) as f64 * ms * 4.0 / count as f64;
                Sample::at(Vec2::new(p.x.round(), p.y.round()), 0.6, frame + 0.01 * (i % 4) as f64)
            })
            .collect();
        let stamps = drawn_in_batches(plain(), Pointer::Stylus, &samples, 4);
        // as round as whole pixels let them be: no point of the stroke further in or out than
        // the input's rounding (a little over half a pixel), the small ones too, whose points
        // are close enough to be evened out with their neighbours
        let off = stamps.iter().map(|s| (s.center.distance(centre) - radius).abs()).fold(0.0, f32::max);
        assert!(off < 0.75, "{radius}: {off} px off round");
    }
}

/// How a pen's position is read: finer than a pixel, in whole screen pixels as the stroke
/// takes any input, or in whole pixels the stroke is told of.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Read {
    Finer,
    Whole,
    WholeTold,
}

/// A pen drawn along `path` (screen pixels, from 0 to 1 along it) in `ms`, read `n` times at
/// 240 Hz as `read` says and handed over four a frame (each frame's with one time), with the
/// canvas shown at `zoom` screen pixels a canvas pixel: the samples, in canvas pixels.
fn read_at(zoom: f32, read: Read, n: usize, ms: f64, path: impl Fn(f32) -> Vec2) -> Vec<Sample> {
    (0..=n)
        .map(|i| {
            let p = path(i as f32 / n as f32);
            let p = if read == Read::Finer { p } else { Vec2::new(p.x.round(), p.y.round()) };
            let frame = (i / 4) as f64 * 4.0 * ms / n as f64;
            Sample::at(p * (1.0 / zoom), 0.6, frame + 0.01 * (i % 4) as f64)
        })
        .collect()
}

/// A stroke 6 screen pixels wide as the canvas begins one shown at `zoom`, told of input read
/// in whole pixels if `read` says.
fn stroke_at(zoom: f32, read: Read) -> StrokeBuilder {
    let b = StrokeBuilder::new(plain(), 6.0 / zoom, Pointer::Stylus).seen_at(zoom);
    if read == Read::WholeTold { b.read_in_whole_pixels(zoom) } else { b }
}

/// The stamps of `samples` drawn four at a time (see [`stroke_at`]).
fn drawn_at(zoom: f32, read: Read, samples: &[Sample]) -> Vec<Stamp> {
    let mut b = stroke_at(zoom, read);
    for some in samples.chunks(4) {
        b.add(some);
    }
    b.finish()
}

/// How far the middle eight tenths of a stroke's stamps stray (screen pixels) across the line
/// from `start` going `way`: at most, and the root mean square.
fn strays_across(stamps: &[Stamp], zoom: f32, start: Vec2, way: Vec2) -> [f32; 2] {
    let across: Vec<f32> = stamps.iter().map(|s| (s.center * zoom - start).dot(Vec2::new(-way.y, way.x))).collect();
    let middle = &across[across.len() / 10..across.len() * 9 / 10];
    let mean = middle.iter().sum::<f32>() / middle.len() as f32;
    let most = middle.iter().map(|a| (a - mean).abs()).fold(0.0, f32::max);
    let squares = middle.iter().map(|a| (a - mean) * (a - mean)).sum::<f32>();
    [most, (squares / middle.len() as f32).sqrt()]
}

/// How much nearer to `centre` the nearest of the middle eight tenths of a stroke's stamps
/// comes than the furthest (screen pixels): 0 for a round one.
fn strays_round(stamps: &[Stamp], zoom: f32, centre: Vec2) -> f32 {
    let out: Vec<f32> = stamps.iter().map(|s| (s.center * zoom).distance(centre)).collect();
    let middle = &out[out.len() / 10..out.len() * 9 / 10];
    let (lo, hi) = middle.iter().fold((f32::MAX, f32::MIN), |(lo, hi), r| (lo.min(*r), hi.max(*r)));
    hi - lo
}

/// The point `angle` radians round from the right, a unit from the middle.
fn towards(angle: f32) -> Vec2 {
    Vec2::new(angle.cos(), angle.sin())
}

#[test]
fn slow_lines_and_small_circles_read_in_whole_pixels_stray_alike_on_screen_at_any_zoom() {
    // the canvas zoomed out (a big canvas fitted to the screen), at 100% and zoomed in
    let zooms = [0.5f32, 1.0, 3.0];
    let mut strays = Vec::new();
    for read in [Read::Finer, Read::Whole, Read::WholeTold] {
        for zoom in zooms {
            // slow lines, 100 px a second and 150 long, from a shallow slope to near diagonal
            let line = [8.0f32, 20.0, 30.0, 40.0]
                .map(|degrees| {
                    let (start, way) = (Vec2::new(100.3, 100.6), towards(degrees.to_radians()));
                    let samples = read_at(zoom, read, 360, 1500.0, |t| start + way * (150.0 * t));
                    strays_across(&drawn_at(zoom, read, &samples), zoom, start, way)
                })
                .into_iter()
                .fold([0.0f32; 2], |[m, r], [most, rms]| [m.max(most), r.max(rms)]);
            // small circles drawn slowly, 60 px a second
            let round = [6.0f32, 12.0, 24.0]
                .map(|radius| {
                    let centre = Vec2::new(200.3, 200.6);
                    let ms = f64::from(TAU as f32 * radius / 60.0) * 1000.0;
                    let samples = read_at(zoom, read, (ms * 0.24) as usize, ms, |t| {
                        centre + towards(TAU as f32 * t) * radius
                    });
                    strays_round(&drawn_at(zoom, read, &samples), zoom, centre)
                })
                .into_iter()
                .fold(0.0, f32::max);
            strays.push((read, zoom, line, round));
        }
    }
    let seen = |read| strays.iter().filter(move |s| s.0 == read).map(|s| (s.1, s.2, s.3));
    // read finer than a pixel: straight and round at every zoom
    for (zoom, [most, _], round) in seen(Read::Finer) {
        assert!(most < 0.01 && round < 0.01, "zoom {zoom}: {most} {round}");
    }
    // read in whole pixels, evened out with the neighbours alone, at each zoom the same on
    // screen (a step of a pixel is two canvas pixels zoomed out, a third of one zoomed in: what
    // changes is how wide the brush is on screen): up to 0.35 to 0.37 px off straight (0.20
    // root mean square), 0.80 to 0.82 off round; evened out over the steps, up to 0.10 to 0.17
    // (0.046 at each zoom) and under 0.49
    for (zoom, [most, rms], round) in seen(Read::Whole) {
        assert!(most > 0.3 && most < 0.4 && rms > 0.1 && round > 0.75, "zoom {zoom}: {most} {rms} {round}");
    }
    for (zoom, [most, rms], round) in seen(Read::WholeTold) {
        assert!(most < 0.2 && rms < 0.06 && round < 0.5, "zoom {zoom}: {most} {rms} {round}");
    }
    for read in [Read::Whole, Read::WholeTold] {
        let rms: Vec<f32> = seen(read).map(|s| s.1[1]).collect();
        let spread = rms.iter().fold(0.0f32, |m, l| m.max(*l)) - rms.iter().fold(1.0f32, |m, l| m.min(*l));
        assert!(spread < 0.01, "{read:?}: {rms:?}");
    }
}

#[test]
fn with_no_smoothing_what_is_laid_keeps_a_sample_behind_the_pen_unless_it_evens_out_whole_pixels() {
    // a pen drawn across at 200 px a second, read at 240 Hz finer than a pixel (its first
    // position on whole pixels, by chance) and handed over four a frame: how far behind the
    // pen what is laid for good is, once it is under way (screen pixels, the canvas at 100%)
    let samples: Vec<Sample> = (0..=240)
        .map(|i| {
            let p = Vec2::new(100.0 + 200.0 * i as f32 / 240.0, 100.0 + 0.3 * (i as f32 * 0.2).sin());
            Sample::at(p, 0.6, (i / 4) as f64 * 1000.0 / 60.0 + 0.01 * (i % 4) as f64)
        })
        .collect();
    let behind = |mut b: StrokeBuilder, finer_after_first: bool| {
        let mut laid = f32::MIN;
        let mut most = 0.0f32;
        for (k, some) in samples.chunks(4).enumerate() {
            if finer_after_first && k == 1 {
                b.read_finer();
            }
            laid = b.add(some).iter().map(|s| s.center.x).fold(laid, f32::max);
            if k >= 10 {
                most = most.max(some.last().map_or(0.0, |s| s.pos.x) - laid);
            }
        }
        most
    };
    let stroke = || StrokeBuilder::new(plain(), 6.0, Pointer::Stylus);
    // read finer: a sample and a little more (the curve through the points waits for the next)
    let finer = behind(stroke(), false);
    assert!(finer < 2.0, "{finer}");
    // taken for whole pixels, each point waits for the path to go on up to 12 px past it
    let whole = behind(stroke().read_in_whole_pixels(1.0), false);
    assert!(whole > 10.0, "{whole}");
    // found to be read finer at its next samples: as read finer from there
    let found = behind(stroke().read_in_whole_pixels(1.0), true);
    assert!((found - finer).abs() < 0.01, "{found} {finer}");
}

#[test]
fn whole_pixels_evened_out_keep_a_quick_strokes_corners_and_a_slow_ones_nearly() {
    let corner = Vec2::new(140.3, 100.6);
    // a square's corner: along and then down, 40 px each way, in whole pixels
    let path = |t: f32| {
        let along = 80.0 * t;
        if along < 40.0 { corner - Vec2::new(40.0 - along, 0.0) } else { corner + Vec2::new(0.0, along - 40.0) }
    };
    for zoom in [0.5f32, 1.0, 3.0] {
        // drawn quickly (1.2 px a millisecond, 5 px between samples): the same as not told
        let quick = read_at(zoom, Read::Whole, 16, 66.0, path);
        let centres = |read| drawn_at(zoom, read, &quick).iter().map(|s| s.center).collect::<Vec<_>>();
        assert_eq!(centres(Read::WholeTold), centres(Read::Whole), "zoom {zoom}");
        // drawn slowly (100 px a second): rounded off little more than the neighbours alone
        // round it (0.87 to 1.14 px from the corner, 1.02 to 1.31 evened out over the steps)
        let slow = read_at(zoom, Read::Whole, 192, 800.0, path);
        let nearest = |read| {
            drawn_at(zoom, read, &slow).iter().map(|s| (s.center * zoom).distance(corner)).fold(f32::MAX, f32::min)
        };
        let (before, after) = (nearest(Read::Whole), nearest(Read::WholeTold));
        assert!(after < before + 0.25 && after < 1.4, "zoom {zoom}: {before} {after}");
    }
}

#[test]
fn whole_pixels_evened_out_show_up_to_the_input_and_end_where_it_lifts() {
    // a slow line in whole pixels, handed over a sample at a time: what shows reaches the
    // newest sample however many points wait to be evened out, and the stroke ends at the last
    let start = Vec2::new(100.3, 100.6);
    let samples = read_at(1.0, Read::Whole, 120, 500.0, |t| start + towards(0.3) * (50.0 * t));
    let mut b = stroke_at(1.0, Read::WholeTold);
    for s in &samples {
        b.add(std::slice::from_ref(s));
        let shown = b.preview_to_input(&[]);
        let end = shown.last().map_or_else(|| b.stamps().last().unwrap().center, |t| t.center);
        assert!(end.distance(s.pos) < 2.0, "shown up to {end:?}, the input at {:?}", s.pos);
    }
    let end = b.finish().last().unwrap().center;
    assert!(end.distance(samples.last().unwrap().pos) < 2.0, "{end:?}");
    // and a tap in whole pixels leaves its dot where it was read
    let mut tap = stroke_at(0.5, Read::WholeTold);
    tap.add(&[Sample::at(Vec2::new(60.0, 80.0), 0.5, 0.0)]);
    let dots = tap.finish();
    assert_eq!(dots.iter().map(|s| s.center).collect::<Vec<_>>(), [Vec2::new(60.0, 80.0)]);
}

#[test]
fn with_no_smoothing_the_stroke_shows_and_ends_where_the_input_is() {
    // a quick pen stroke at 120 Hz, no StreamLine, stabilisation or motion filtering
    let samples = c_curve(100.0, 18, 150.0, 1);
    let mut b = StrokeBuilder::new(plain(), 6.0, Pointer::Stylus);
    for s in &samples {
        b.add(std::slice::from_ref(s));
        // what shows reaches the newest sample
        let shown = b.preview_to_input(&[]);
        let end = shown.last().map_or_else(|| b.stamps().last().unwrap().center, |t| t.center);
        assert!(end.distance(s.pos) < 2.0, "shown up to {end:?}, the input at {:?}", s.pos);
    }
    // and the stroke ends on the last (a sample's step is 26 px here: within a stamp's gap)
    let end = b.finish().last().unwrap().center;
    assert!(end.distance(samples.last().unwrap().pos) < 2.0, "{end:?}");
}

#[test]
fn a_pens_tip_reaches_the_input_past_the_smoothed_path() {
    let taper = Taper { start: 0.0, end: 0.5, size: 1.0, opacity: 0.0 };
    let brush = Brush { pen_taper: taper, streamline: 0.9, ..plain() };
    let samples = line(50, 300.0);
    let input = samples.last().unwrap().pos;
    let mut b = StrokeBuilder::new(brush, 4.0, Pointer::Stylus);
    assert!(b.shows_tip());
    let (_, tip) = b.add_tipped(&samples);
    let end = tip.last().unwrap();
    assert!(end.center.distance(input) < 2.0 && end.radius < 0.5, "{:?} {}", end.center, end.radius);
}

#[test]
fn a_fingers_tapers_show_as_it_is_drawn_and_what_is_kept_stays_at_the_end() {
    let taper = Taper { start: 0.8, end: 0.5, size: 1.0, opacity: 0.0 };
    let brush = Brush { touch_taper: taper, streamline: 0.3, ..plain() };
    // 4 px every 8 ms along, then lifted
    let samples: Vec<Sample> =
        (0..=80).map(|i| Sample::at(Vec2::new(20.0 + i as f32 * 4.0, 50.0), 1.0, i as f64 * 8.0)).collect();
    let mut b = StrokeBuilder::new(brush, 20.0, Pointer::Finger);
    assert!(b.shows_tip());
    let mut kept = Vec::new();
    let mut first_tip = None;
    for (k, chunk) in samples.chunks(2).enumerate() {
        let (more, tip) = b.add_tipped(chunk);
        // within the start taper's time (67 ms) nothing is drawn for good, and the tip shows
        // the start tapered
        if k < 4 {
            assert!(more.is_empty(), "kept at {k}");
        }
        if k == 3 {
            first_tip = Some(tip.clone());
        }
        kept.extend(more);
        // the head thinned as it goes
        if k > 12 {
            let head = tip.last().unwrap();
            assert!(head.radius < 3.0, "head {} at {k}", head.radius);
        }
    }
    let first_tip = first_tip.unwrap();
    assert!(first_tip[0].radius < 3.0 && first_tip.last().unwrap().radius < 3.0, "{first_tip:?}");
    assert!(kept.len() > 20, "{}", kept.len());
    // what was drawn for good is the stroke as it ends
    let all = b.finish();
    for (i, (k, a)) in kept.iter().zip(&all).enumerate() {
        assert!(k.center.distance(a.center) < 1e-3 && (k.radius - a.radius).abs() < 1e-3, "{i}: {k:?} {a:?}");
    }
    assert!(all[0].radius < 2.0 && all.last().unwrap().radius < 2.0);
}

#[test]
fn a_taper_s_end_shows_as_it_is_drawn_only_with_the_tip_animation_on() {
    let taper = Taper { start: 0.0, end: 0.5, size: 1.0, opacity: 0.0 };
    let shows = |pointer: Pointer, animated: f32| {
        let mut brush = Brush { touch_taper: taper.clone(), pen_taper: taper.clone(), ..plain() };
        brush.set_setting("pen_tip_animation", animated);
        StrokeBuilder::new(brush, 20.0, pointer).shows_tip()
    };
    assert!(shows(Pointer::Finger, 1.0) && shows(Pointer::Stylus, 1.0));
    assert!(!shows(Pointer::Finger, 0.0) && !shows(Pointer::Stylus, 0.0));
}

#[test]
fn a_start_taper_reaches_what_the_input_covered_though_the_path_waits_a_point_behind() {
    let taper = Taper { start: 0.5, end: 0.0, size: 1.0, opacity: 0.0 };
    let brush = Brush { touch_taper: taper, ..plain() };
    // the first move comes after the taper's 42 ms, 60 px on: the path hasn't walked any of it
    let mut samples =
        vec![Sample::at(Vec2::new(0.0, 50.0), 1.0, 0.0), Sample::at(Vec2::new(60.0, 50.0), 1.0, 100.0)];
    samples.extend(
        (1..40).map(|i| Sample::at(Vec2::new(60.0 + i as f32 * 10.0, 50.0), 1.0, 100.0 + f64::from(i) * 8.0)),
    );
    let mut b = StrokeBuilder::new(brush, 20.0, Pointer::Finger);
    b.add(&samples);
    let all = b.finish();
    let at = |x: f32| {
        all.iter().min_by(|a, b| (a.center.x - x).abs().total_cmp(&(b.center.x - x).abs())).unwrap().radius
    };
    assert!(at(5.0) < 3.0, "{}", at(5.0));
    assert!((at(30.0) - 5.0).abs() < 1.0, "{}", at(30.0));
    assert_eq!(at(80.0), 10.0);
}

#[test]
fn held_still_then_lifted_the_end_keeps_the_taper_it_showed() {
    let taper = Taper { start: 0.0, end: 0.5, size: 1.0, opacity: 0.0 };
    let brush = Brush { touch_taper: taper, ..plain() };
    let samples: Vec<Sample> =
        (0..=60).map(|i| Sample::at(Vec2::new(i as f32 * 10.0, 50.0), 1.0, i as f64 * 8.0)).collect();
    let radii = |lift_ms: Option<f64>| {
        let mut b = StrokeBuilder::new(brush.clone(), 20.0, Pointer::Finger);
        let (_, tip) = b.add_tipped(&samples);
        // the lift, where it last was, after the wait
        if let Some(ms) = lift_ms {
            b.add(&[Sample::at(Vec2::new(600.0, 50.0), 1.0, 480.0 + ms)]);
        }
        let all = b.finish();
        (tip.last().unwrap().radius, all.iter().rev().take(6).map(|s| s.radius).collect::<Vec<f32>>())
    };
    let (shown, straight_away) = radii(None);
    let (_, after_a_hold) = radii(Some(1500.0));
    assert!(shown < 3.0, "{shown}");
    assert!(straight_away[0] < 3.0, "{straight_away:?}");
    assert_eq!(straight_away, after_a_hold);
}

#[test]
fn a_touch_stroke_tapers_over_what_it_covers_in_the_taper_s_time() {
    let brush = Brush { touch_taper: Taper { start: 0.5, end: 0.5, size: 1.0, opacity: 0.0 }, ..plain() };
    // 600 px drawn in samples `ms` apart: the end taper covers the input since the last
    // sample before its time (0.5 x 83 ms), so the faster, the longer it is
    let near_end = |ms: f64| {
        let mut b = StrokeBuilder::new(brush.clone(), 20.0, Pointer::Finger);
        let samples: Vec<Sample> =
            (0..=60).map(|i| Sample::at(Vec2::new(i as f32 * 10.0, 50.0), 1.0, i as f64 * ms)).collect();
        b.add(&samples);
        let all = b.finish();
        let at = |x: f32| {
            all.iter().min_by(|a, b| (a.center.x - x).abs().total_cmp(&(b.center.x - x).abs())).unwrap().radius
        };
        (at(600.0 - 30.0), at(300.0))
    };
    let (fast, full) = near_end(8.0);
    let (slow, _) = near_end(32.0);
    assert!(fast < full * 0.8, "{fast} {full}");
    assert!((slow - full).abs() < 1e-3, "{slow} {full}");
}

#[test]
fn a_classic_end_runs_on_past_the_lift_and_tapers_there() {
    let mut brush = Brush { touch_taper: Taper { start: 0.0, end: 1.0, size: 1.0, opacity: 0.0 }, ..plain() };
    brush.pen_taper = brush.touch_taper.clone();
    brush.set_setting("classic_taper", 1.0);
    // 300 px in 10 px steps
    let ran_to = |pointer| {
        let mut b = StrokeBuilder::new(brush.clone(), 10.0, pointer);
        b.add(&line(30, 300.0));
        let all = b.finish();
        let last = all.last().unwrap();
        let at_lift = all.iter().min_by(|a, b| (a.center.x - 300.0).abs().total_cmp(&(b.center.x - 300.0).abs()));
        (last.center.x, last.radius, at_lift.unwrap().radius)
    };
    // a finger runs on four shrinking steps (0.66, 0.44, 0.29, 0.19 of the last), a pen one
    let (touch_end, touch_tip, touch_at_lift) = ran_to(Pointer::Finger);
    assert!((touch_end - (300.0 + 15.73)).abs() < 1.0, "{touch_end}");
    // (eased in over the points either side, the taper has just begun at the lift)
    assert!(touch_tip < 0.5 && touch_at_lift > 4.3, "{touch_tip} {touch_at_lift}");
    let (pen_end, pen_tip, _) = ran_to(Pointer::Stylus);
    assert!((pen_end - 306.6).abs() < 1.0, "{pen_end}");
    assert!(pen_tip < 1.0, "{pen_tip}");
}

#[test]
fn a_shape_s_line_is_smoothed_by_stabilisation_but_not_streamline() {
    // a wavy 600 px line, 3 px either way every 20 px
    let wave: Vec<Sample> = (0..=300)
        .map(|i| {
            let x = i as f32 * 2.0;
            Sample::at(Vec2::new(x, 50.0 + 3.0 * (x / 20.0 * std::f32::consts::TAU).sin()), 1.0, i as f64 * 4.0)
        })
        .collect();
    let line = |brush: Brush| StrokeBuilder::new(brush, 4.0, Pointer::Stylus).shape_line(&wave);
    let spread = |line: &[Vec2]| {
        let middle = line.iter().filter(|p| (100.0..500.0).contains(&p.x));
        middle.fold((f32::MAX, f32::MIN), |(lo, hi), p| (lo.min(p.y), hi.max(p.y)))
    };
    let bare = line(plain());
    let (lo, hi) = spread(&bare);
    assert!(hi - lo > 5.0, "the wave is there: {lo}..{hi}");
    // streamline leaves it as it is
    assert_eq!(line(Brush { streamline: 0.9, ..plain() }), bare);
    // stabilisation evens it out
    let mut steady = plain();
    steady.set_setting("stabilization", 1.0);
    let (lo, hi) = spread(&line(steady));
    assert!(hi - lo < 2.0, "stabilised: {lo}..{hi}");
    // a classic end taper runs nothing on past the lift
    let mut classic = Brush { pen_taper: Taper { start: 0.0, end: 1.0, size: 1.0, opacity: 0.0 }, ..plain() };
    classic.set_setting("classic_taper", 1.0);
    let end = line(classic).iter().map(|p| p.x).fold(f32::MIN, f32::max);
    assert!(end <= 600.5, "ran on to {end}");
}

#[test]
fn taper_pressure_fades_a_pens_pressure_into_its_tapers() {
    // a taper that thins nothing by itself, a size that follows the pressure in full
    let taper = Taper { start: 0.5, end: 0.5, size: 0.0, opacity: 0.0 };
    let mut brush = Brush { pen_taper: taper.clone(), touch_taper: taper, pressure_size: 1.0, ..plain() };
    let stamp = |brush: &Brush, pointer, at: usize| {
        let mut b = StrokeBuilder::new(brush.clone(), 10.0, pointer);
        let samples: Vec<Sample> =
            (0..=100).map(|i| Sample::at(Vec2::new(i as f32 * 2.0, 50.0), 1.0, i as f64 * 4.0)).collect();
        b.add(&samples);
        b.finish().get(at).cloned().unwrap()
    };
    let near_start = |brush: &Brush, pointer| stamp(brush, pointer, 5).radius;
    assert_eq!(near_start(&brush, Pointer::Stylus), 5.0);
    brush.set_setting("pen_taper_pressure", 1.0);
    assert!(near_start(&brush, Pointer::Stylus) < 3.0, "{}", near_start(&brush, Pointer::Stylus));
    // a finger has no pressure to fade
    assert_eq!(near_start(&brush, Pointer::Finger), 5.0);
    // the bleed and the roundness take the faded pressure too, and are whole further in
    brush.set_setting("pressure_bleed", 1.0);
    brush.set_setting("pressure_roundness", 1.0);
    let (tip, inside) = (stamp(&brush, Pointer::Stylus, 5), stamp(&brush, Pointer::Stylus, 40));
    assert!(tip.grain_press < 0.6 && tip.roundness < 0.6, "{} {}", tip.grain_press, tip.roundness);
    assert!(inside.grain_press > 0.99 && inside.roundness > 0.99, "{} {}", inside.grain_press, inside.roundness);
}

#[test]
fn a_tip_worked_out_on_what_is_open_is_the_whole_strokes() {
    let taper = Taper { start: 0.2, end: 0.5, size: 1.0, opacity: 0.3 };
    let brush = Brush { pen_taper: taper.clone(), touch_taper: taper, streamline: 0.4, ..plain() };
    let samples: Vec<Sample> = (0..=600)
        .map(|i| {
            let t = i as f32 * 0.05;
            let pos = Vec2::new(i as f32 * 1.5, 50.0 + 30.0 * t.sin());
            Sample::at(pos, 0.4 + 0.5 * (t * 0.7).cos().abs(), i as f64 * 4.0)
        })
        .collect();
    let mut b = StrokeBuilder::new(brush, 10.0, Pointer::Stylus);
    assert!(b.shows_tip());
    // as the whole stroke copied works them out
    let whole_tip = |b: &StrokeBuilder, ahead: &[Sample]| {
        let mut copy = b.clone();
        copy.add(ahead);
        copy.reach_input(&mut Vec::new());
        copy.respace_tip();
        copy.tip_from(b.kept)
    };
    let mut checked = 0;
    for (k, chunk) in samples.chunks(3).enumerate() {
        let ahead = [Sample::at(chunk[0].pos + Vec2::new(6.0, 2.0), 0.5, chunk[0].time_ms + 8.0)];
        let (count, kept_before) = (b.stamp_count(), b.kept);
        assert_eq!(b.preview_tip(&ahead), whole_tip(&b, &ahead), "predicted at {k}");
        let mut whole = b.clone();
        let mut ahead_stamps = whole.add(&ahead);
        whole.stream_on(&mut ahead_stamps);
        whole.flush(&mut ahead_stamps);
        assert_eq!(b.preview(&ahead), ahead_stamps, "previewed at {k}");
        whole.walk_to(whole.input_point().unwrap(), &mut ahead_stamps);
        assert_eq!(b.preview_to_input(&ahead), ahead_stamps, "previewed to the input at {k}");
        // the stroke left as it was
        assert_eq!((b.stamp_count(), b.kept), (count, kept_before));
        let (_, tip) = b.add_tipped(chunk);
        assert_eq!(tip, whole_tip(&b, &[]), "tip at {k}");
        checked += tip.len();
    }
    assert!(checked > 1000 && b.kept > 100, "{checked} {}", b.kept);
}

#[test]
fn a_pens_tip_shows_tapered_as_it_is_drawn() {
    let taper = Taper { start: 0.0, end: 0.5, size: 1.0, opacity: 0.0 };
    let brush = Brush { pen_taper: taper.clone(), touch_taper: taper, ..plain() };
    // 2 px every 4 ms: the last 42 ms are the last 20 px or so
    let samples: Vec<Sample> =
        (0..=100).map(|i| Sample::at(Vec2::new(i as f32 * 2.0, 50.0), 1.0, i as f64 * 4.0)).collect();
    let mut b = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Stylus);
    assert!(b.shows_tip());
    let (kept, tip) = b.add_tipped(samples.get(..60).unwrap());
    assert!(kept.iter().all(|s| s.radius == 5.0));
    assert!(tip.len() < 30 && tip.last().unwrap().radius < 1.0, "{} {}", tip.len(), tip.last().unwrap().radius);
    // the tip reaches the newest point, past the stamps placed for good (the path waits a point
    // to curve)
    assert!((tip.last().unwrap().center.x - 118.0).abs() < 3.0, "{}", tip.last().unwrap().center.x);
    assert!(kept.len() + tip.len() >= b.stamp_count());
    // drawn on, the old tip is kept whole and a new one shown
    let (more, tip) = b.add_tipped(samples.get(60..).unwrap());
    assert!(more.iter().all(|s| s.radius == 5.0) && more.first().unwrap().center.x < 118.0);
    assert!(kept.len() + more.len() + tip.len() >= b.stamp_count());
    // a predicted tip runs on to where the input is predicted to go, the stroke left as it is
    let ahead = b.preview_tip(&[Sample::at(Vec2::new(240.0, 50.0), 1.0, 420.0)]);
    assert!(ahead.last().unwrap().center.x > 230.0 && ahead.last().unwrap().radius < 1.0);
    assert!(kept.len() + more.len() + tip.len() >= b.stamp_count());
    // a finger's end shows tapered whatever the animation; a classic taper or the animation
    // off show no tip
    assert!(StrokeBuilder::new(brush.clone(), 10.0, Pointer::Finger).shows_tip());
    let mut classic = brush.clone();
    classic.set_setting("classic_taper", 1.0);
    assert!(!StrokeBuilder::new(classic, 10.0, Pointer::Stylus).shows_tip());
    let mut off = brush;
    off.set_setting("pen_tip_animation", 0.0);
    assert!(!StrokeBuilder::new(off, 10.0, Pointer::Stylus).shows_tip());
}

#[test]
fn a_newer_taper_on_a_pen_lasts_its_time() {
    let brush = Brush { pen_taper: Taper { start: 0.5, end: 0.0, size: 1.0, opacity: 0.0 }, ..plain() };
    // 2 px every 4 ms: half of 83 ms is reached by the eleventh sample, 22 px in
    let mut b = StrokeBuilder::new(brush, 10.0, Pointer::Stylus);
    let samples: Vec<Sample> =
        (0..=100).map(|i| Sample::at(Vec2::new(i as f32 * 2.0, 50.0), 1.0, i as f64 * 4.0)).collect();
    b.add(&samples);
    let all = b.finish();
    let at = |x: f32| all.iter().min_by(|a, b| (a.center.x - x).abs().total_cmp(&(b.center.x - x).abs())).unwrap();
    assert!((at(11.0).radius - 2.5).abs() < 0.3, "{}", at(11.0).radius);
    assert_eq!(at(30.0).radius, 5.0);
    // no end taper set: the end stays whole
    assert_eq!(all.last().unwrap().radius, 5.0);
}

#[test]
fn a_taper_bends_into_the_stroke_where_it_ends_rather_than_turning_a_corner() {
    // a full, sharp start and end on a pen pressed evenly, 4 px every 4 ms: each taper's 25 ms
    // reaches the eighth point, 28 px in (or back)
    let taper = Taper { start: 0.3, end: 0.3, size: 1.0, opacity: 0.0 };
    let brush = Brush { pen_taper: taper, spacing: 0.02, ..plain() };
    let samples: Vec<Sample> =
        (0..=60).map(|i| Sample::at(Vec2::new(10.0 + i as f32 * 4.0, 50.0), 1.0, i as f64 * 4.0)).collect();
    let mut b = StrokeBuilder::new(brush, 20.0, Pointer::Stylus);
    b.add(&samples);
    let all = b.finish();
    let widths: Vec<(f32, f32)> = all.iter().map(|s| (s.distance, 2.0 * s.radius)).collect();
    let at = |d: f32| widths.iter().min_by(|a, b| (a.0 - d).abs().total_cmp(&(b.0 - d).abs())).unwrap().1;
    // from a point to the whole width and back, growing evenly halfway in
    assert!(at(0.0) < 1.0 && at(240.0) < 1.0, "{} {}", at(0.0), at(240.0));
    assert!((at(14.0) - 10.0).abs() < 0.5 && (at(226.0) - 10.0).abs() < 0.5, "{} {}", at(14.0), at(226.0));
    assert!(at(120.0) > 19.9, "{}", at(120.0));
    // where each taper meets the stroke, its slope turns a little from stamp to stamp, never
    // all at once from the taper's (20 px over 28) to the stroke's (none); the points
    // themselves aside, where the least stamp holds
    let inner: Vec<(f32, f32)> = widths.iter().copied().filter(|w| w.0 > 4.0 && w.0 < 236.0).collect();
    let slopes: Vec<f32> = inner.windows(2).map(|w| (w[1].1 - w[0].1) / (w[1].0 - w[0].0).max(1e-3)).collect();
    let turn = slopes.windows(2).map(|s| (s[1] - s[0]).abs()).fold(0.0, f32::max);
    assert!(turn < 0.25 * 20.0 / 28.0, "{turn}");
}

#[test]
fn bad_samples_are_skipped() {
    let mut b = StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    assert!(b.add(&[Sample::at(Vec2::new(f32::NAN, 0.0), 1.0, 0.0)]).is_empty());
    assert_eq!(b.finish().len(), 0);
}

#[test]
fn scatter_turns_each_stamp_at_random_in_place() {
    let shape = Shape { scatter: 1.0, ..Shape::default() };
    let mut b = StrokeBuilder::new(Brush { shape, ..plain() }, 10.0, Pointer::Stylus);
    let stamps = b.add(&line(20, 200.0));
    assert!(stamps.iter().all(|s| (s.center.y - 50.0).abs() < 1e-3), "on the path");
    let turned = stamps.iter().filter(|s| s.angle.abs() > 0.5).count();
    assert!(turned > stamps.len() / 3, "{turned} of {}", stamps.len());
    assert!(stamps.iter().all(|s| s.angle.abs() <= std::f32::consts::PI + 1e-3));
}

#[test]
fn shape_placement_rotates_scatters_and_counts() {
    let shape = Shape { follow: 1.0, throw: 1.0, count: 3, size_jitter: 0.5, roundness: 0.4, ..Shape::default() };
    let mut b = StrokeBuilder::new(Brush { shape, ..plain() }, 10.0, Pointer::Stylus);
    // a vertical line: the stroke heads +y, so the stamps turn a quarter
    let down: Vec<Sample> =
        (0..=10).map(|i| Sample::at(Vec2::new(50.0, i as f32 * 10.0), 1.0, i as f64 * 4.0)).collect();
    let stamps = b.add(&down);
    assert_eq!(stamps.len() % 3, 0, "three per step");
    let later = &stamps[stamps.len() - 1];
    assert!((later.angle - std::f32::consts::FRAC_PI_2).abs() < 1e-3);
    assert_eq!(later.roundness, 0.4);
    assert!(stamps.iter().any(|s| (s.center.x - 50.0).abs() > 0.5), "thrown off the path");
    assert!(
        stamps.iter().all(|s| s.radius <= 5.0 + 1e-4 && s.radius >= 2.5 - 1e-4),
        "jittered smaller, never larger"
    );
    // the same seed replays the same stroke
    let mut again = StrokeBuilder::new(Brush { shape: b.brush().shape.clone(), ..plain() }, 10.0, Pointer::Stylus);
    assert_eq!(again.add(&down), stamps);
}

#[test]
fn a_stroke_s_end_leaves_what_no_taper_reaches_as_it_was_drawn() {
    let ended = |taper: Taper, animated: f32| {
        let mut brush = plain();
        brush.pen_taper = taper;
        brush.set_setting("pen_tip_animation", animated);
        let mut stroke = StrokeBuilder::new(brush, 10.0, Pointer::Stylus);
        stroke.add_tipped(&line(40, 400.0));
        let drawn = stroke.drawn_for_good().unwrap();
        let all = stroke.finish();
        assert!(stroke.drawn_for_good().is_none(), "ended, what was drawn is no longer known");
        (drawn.len(), all.starts_with(&drawn), all.len())
    };
    let (drawn, same, all) = ended(Taper::NONE, 1.0);
    assert!(drawn > 0 && same && all >= drawn, "{drawn} {same} {all}");
    // an end taper shown as it is drawn is on the tip, past what was kept
    let thinning = Taper { end: 0.5, size: 1.0, ..Taper::NONE };
    let (drawn, same, all) = ended(thinning.clone(), 1.0);
    assert!(drawn > 0 && same && all > drawn, "{drawn} {same} {all}");
    // one that doesn't show thins what is there already
    let (drawn, same, _) = ended(thinning, 0.0);
    assert!(drawn > 0 && !same);
}

#[test]
fn a_stamp_knows_how_far_along_the_path_it_is_wherever_jitter_threw_it() {
    let mut brush = plain();
    brush.set_setting("jitter_lateral", 1.0);
    let stamps = StrokeBuilder::new(brush, 10.0, Pointer::Stylus).add(&line(20, 200.0));
    let thrown: f32 = stamps.windows(2).map(|p| p[0].center.distance(p[1].center)).sum();
    let last = stamps.last().unwrap();
    assert!(thrown > 2.0 * last.distance, "{thrown} {}", last.distance);
    assert!(stamps.windows(2).all(|p| p[1].distance >= p[0].distance));
    assert!(last.distance <= 200.0, "{}", last.distance);
}

#[test]
fn jitter_moves_a_stamp_within_half_its_diagonal_times_the_files_amount() {
    let jittered = |lateral: f32, linear: f32| {
        let mut brush = plain();
        brush.set_setting("jitter_lateral", lateral);
        brush.set_setting("jitter_linear", linear);
        // a horizontal line at y = 50, stamps every pixel
        StrokeBuilder::new(brush, 10.0, Pointer::Stylus).add(&line(20, 200.0))
    };
    // a round 10 px tip fills 10 / 0.797 of its square: half its diagonal and a pixel is 9.87;
    // full lateral jitter is 3 of those across the stroke, none along it
    let reach = 10.0 / crate::brushfile::ROUND_TIP_SHARE * 0.5 * std::f32::consts::SQRT_2 + 1.0;
    let across: Vec<f32> = jittered(1.0, 0.0).iter().map(|s| (s.center.y - 50.0).abs()).collect();
    assert!(across.iter().all(|&d| d <= 3.0 * reach + 1e-3), "{across:?}");
    assert!(across.iter().any(|&d| d > 1.5 * reach));
    // both: one random point a stamp, inside the circle of that reach
    let both = jittered(1.0, 1.0);
    let straight = jittered(0.0, 0.0);
    for (j, s) in both.iter().zip(&straight) {
        assert!(j.center.distance(s.center) <= 3.0 * reach + 1e-3);
    }
}

#[test]
fn roundness_jitter_squeezes_width_and_height_apart() {
    let stamps = |key: &str| {
        let mut brush = plain();
        brush.set_setting(key, 0.8);
        StrokeBuilder::new(brush, 10.0, Pointer::Stylus).add(&line(20, 200.0))
    };
    // the width squeezed: narrower and taller than wide, the height whole
    let narrow = stamps("roundness_jitter_x");
    assert!(narrow.iter().any(|s| s.roundness > 1.5));
    assert!(narrow.iter().all(|s| (s.radius * s.roundness - 5.0).abs() < 1e-3 && s.radius <= 5.0));
    // the height squeezed: flatter, the width whole
    let flat = stamps("roundness_jitter_y");
    assert!(flat.iter().any(|s| s.roundness < 0.6));
    assert!(flat.iter().all(|s| s.radius == 5.0 && s.roundness <= 1.0));
    // a stamp taller than wide is held in its bounds
    let tall = narrow.iter().max_by(|a, b| a.roundness.total_cmp(&b.roundness)).unwrap();
    assert!(tall.bounds().height() as f32 >= 2.0 * tall.radius * tall.roundness);
}

#[test]
fn a_randomised_shape_turns_the_whole_stroke_one_way() {
    let shape = Shape { randomise: true, follow: 0.0, ..Shape::default() };
    let angles = |seed: u32| -> Vec<f32> {
        let mut b =
            StrokeBuilder::with_seed(Brush { shape: shape.clone(), ..plain() }, 10.0, Pointer::Stylus, seed);
        b.add(&line(20, 200.0)).iter().map(|s| s.angle).collect()
    };
    let one = angles(1);
    assert!(one.iter().all(|&a| a == one[0]), "{one:?}");
    assert_ne!(angles(2)[0], one[0]);
}

#[test]
fn a_still_tap_stays_a_tap_with_its_points_averaged() {
    let at = Vec2::new(1234.567, 987.654);
    for (setting, amount) in [("motion_filtering", 0.25), ("motion_filtering", 0.8), ("stabilization", 0.5)] {
        let mut brush = Brush { shape: Shape { count: 7, ..Shape::default() }, ..plain() };
        brush.set_setting(setting, amount);
        let mut tap = StrokeBuilder::new(brush, 10.0, Pointer::Stylus);
        tap.add(&[Sample::at(at, 1.0, 0.0), Sample::at(at, 1.0, 40.0), Sample::at(at, 1.0, 80.0)]);
        assert_eq!(tap.finish().len(), 1, "{setting} {amount}");
    }
}

#[test]
fn a_tap_lays_one_stamp_whatever_the_brush_s_count() {
    let brush = Brush { shape: Shape { count: 7, ..Shape::default() }, ..plain() };
    let mut tap = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Stylus);
    tap.add(&[Sample::at(Vec2::new(50.0, 50.0), 1.0, 0.0), Sample::at(Vec2::new(50.0, 50.0), 1.0, 80.0)]);
    assert_eq!(tap.finish().len(), 1);
    // a stroke lays all of each place's
    let mut stroke = StrokeBuilder::new(brush, 10.0, Pointer::Stylus);
    stroke.add(&line(10, 100.0));
    assert!(stroke.finish().len() >= 14);
}

#[test]
fn count_jitter_takes_a_share_off_the_stamps_beyond_the_first() {
    let shape = Shape { count: 16, ..Shape::default() };
    let mut brush = Brush { shape, ..plain() };
    brush.set_setting("count_jitter", 0.5);
    let stamps = StrokeBuilder::new(brush, 10.0, Pointer::Stylus).add(&line(40, 400.0));
    // stamps of one place share its centre
    let mut counts: Vec<usize> = Vec::new();
    for (i, s) in stamps.iter().enumerate() {
        if i == 0 || stamps[i - 1].center != s.center {
            counts.push(0);
        }
        *counts.last_mut().unwrap() += 1;
    }
    // 15 beyond the first, less up to half of them: 8 to 16, and not always all
    assert!(counts.iter().all(|&n| (8..=16).contains(&n)), "{counts:?}");
    assert!(counts.iter().any(|&n| n < 16) && counts.iter().any(|&n| n > 12), "{counts:?}");
}

#[test]
fn a_brushs_own_pressure_curves_scale_its_count_and_jitters() {
    use crate::brush::PressureCurve;
    use crate::curve::Curve;
    let shape = Shape { count: 5, size_jitter: 0.8, ..Shape::default() };
    let mut brush = Brush { shape, pressure_size: 0.0, ..plain() };
    brush.pressure_curves.set(PressureCurve::Count, Some(Curve::linear()));
    brush.pressure_curves.set(PressureCurve::SizeJitter, Some(Curve::linear()));
    brush.pressure_curves.set(PressureCurve::WetBlur, Some(Curve::linear()));
    let stroke = |brush: &Brush, pointer, pressure: f32| {
        let samples: Vec<Sample> =
            (0..=10).map(|i| Sample::at(Vec2::new(i as f32 * 10.0, 50.0), pressure, i as f64 * 4.0)).collect();
        StrokeBuilder::new(brush.clone(), 10.0, pointer).add(&samples)
    };
    let spread = |stamps: &[Stamp]| {
        let (least, most) = stamps.iter().fold((f32::MAX, 0.0f32), |(l, m), s| (l.min(s.radius), m.max(s.radius)));
        most / least
    };
    // pressing lightly: one stamp a place, hardly jittered
    let light = stroke(&brush, Pointer::Stylus, 0.05);
    assert!(spread(&light) < 1.05, "{}", spread(&light));
    // the wet blur scaled the same way, the rest of the wet mix untouched
    assert!(light.iter().all(|s| s.wet[1] < 0.1 && s.wet[0] == 1.0), "{:?}", light[0].wet);
    // pressing fully, or a finger: five a place, jittered as far as the brush says
    for full in [stroke(&brush, Pointer::Stylus, 1.0), stroke(&brush, Pointer::Finger, 0.0)] {
        assert_eq!(full.len(), 5 * light.len());
        assert!(spread(&full) > 2.0, "{}", spread(&full));
    }
    // without the curves, light pressure jitters and counts in full
    brush.pressure_curves = Default::default();
    let plain_light = stroke(&brush, Pointer::Stylus, 0.05);
    assert_eq!(plain_light.len(), 5 * light.len());
}

#[test]
fn tilt_widens_the_pen_but_not_fingers() {
    let angle = 0.1 * std::f32::consts::FRAC_PI_2;
    let brush =
        Brush { tilt: Tilt { angle, size: 0.1, opacity: 0.0 }, pressure_size: 0.0, max_size: 500.0, ..plain() };
    let mut tilted = Sample::at(Vec2::new(0.0, 0.0), 1.0, 0.0);
    tilted.tilt = crate::brush::FLAT_TILT;
    let pen = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Stylus).preview(&[tilted]);
    let finger = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Finger).preview(&[tilted]);
    // a dab takes all of the lean's growth: the tilt size, as far as the lean has gone
    let grown = 0.1 * Tilt::reach(crate::brush::FLAT_TILT, 0.1) * brush.size_unit();
    assert!((pen[0].radius - (10.0 + grown) / 2.0).abs() < 1e-3, "{}", pen[0].radius);
    assert_eq!(finger[0].radius, 5.0);
    // upright, next to nothing
    tilted.tilt = 0.0;
    let upright = StrokeBuilder::new(brush, 10.0, Pointer::Stylus).preview(&[tilted]);
    assert!(upright[0].radius < 5.1);
}

#[test]
fn a_leaning_pen_moves_its_stamps_toward_the_cap() {
    let angle = 0.1 * std::f32::consts::FRAC_PI_2;
    let brush = Brush { tilt: Tilt { angle, size: 0.0, opacity: 0.0 }, pressure_size: 0.0, ..plain() };
    // leaning with the pen pointing right on the screen
    let mut s = Sample::at(Vec2::new(100.0, 100.0), 1.0, 0.0);
    s.tilt = crate::brush::FLAT_TILT;
    s.azimuth = std::f32::consts::FRAC_PI_2;
    let pen = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Stylus).preview(&[s]);
    let shift = Tilt::reach(crate::brush::FLAT_TILT, 0.1) * std::f32::consts::FRAC_1_SQRT_2 * 5.0;
    assert!(
        (pen[0].center.x - (100.0 + shift)).abs() < 1e-3 && (pen[0].center.y - 100.0).abs() < 1e-3,
        "{:?}",
        pen[0].center
    );
    // fingers, upright pens and the studio's pad stay put
    let finger = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Finger).preview(&[s]);
    assert_eq!(finger[0].center, Vec2::new(100.0, 100.0));
    let pad = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Stylus).on_pad(true).preview(&[s]);
    assert_eq!(pad[0].center, Vec2::new(100.0, 100.0));
    s.tilt = 0.0;
    let upright = StrokeBuilder::new(brush, 10.0, Pointer::Stylus).preview(&[s]);
    assert!((upright[0].center.x - 100.0).abs() < 0.01);
}

#[test]
fn spacing_jitter_and_speed_only_widen_the_gaps() {
    // 600 px in 200 ms: fast input
    let gaps = |settings: &[(&str, f32)]| {
        let mut b = plain();
        for &(k, v) in settings {
            b.set_setting(k, v);
        }
        let samples: Vec<Sample> =
            (0..=50).map(|i| Sample::at(Vec2::new(i as f32 * 12.0, 50.0), 1.0, i as f64 * 4.0)).collect();
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus);
        let all = s.add(&samples);
        all.windows(2).map(|w| w[1].center.x - w[0].center.x).collect::<Vec<f32>>()
    };
    let mean = |g: &[f32]| g.iter().sum::<f32>() / g.len() as f32;
    let plain_gaps = gaps(&[]);
    let least = plain_gaps.iter().copied().fold(f32::MAX, f32::min);
    let jittered = gaps(&[("spacing_jitter", 0.1)]);
    assert!(jittered.iter().all(|&g| g > least - 1e-3));
    assert!(mean(&jittered) > 1.2 * mean(&plain_gaps), "{} {}", mean(&jittered), mean(&plain_gaps));
    let sped = gaps(&[("speed_spacing", 0.05)]);
    assert!(mean(&sped) > 1.2 * mean(&plain_gaps), "{} {}", mean(&sped), mean(&plain_gaps));
}

#[test]
fn flow_bleed_and_roundness_follow_their_own_curves() {
    use crate::curve::Curve;
    let middle = |settings: &[(&str, f32)], level: Option<PressureCurve>| {
        let mut b = Brush { pressure_size: 0.0, ..plain() };
        for &(k, v) in settings {
            b.set_setting(k, v);
        }
        // a curve that holds full pressure throughout
        if let Some(kind) = level {
            b.pressure_curves.set(kind, Some(Curve::new(&[Vec2::new(0.0, 1.0), Vec2::new(1.0, 1.0)])));
        }
        let samples: Vec<Sample> =
            (0..=30).map(|i| Sample::at(Vec2::new(i as f32 * 4.0, 50.0), 0.3, i as f64 * 4.0)).collect();
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus);
        s.add(&samples);
        let all = s.finish();
        all[all.len() / 2]
    };
    let flow = [("pressure_flow", 1.0)];
    assert!(middle(&flow, None).alpha < 0.5 * middle(&flow, Some(PressureCurve::Flow)).alpha);
    let bleed = [("pressure_bleed", 1.0)];
    assert!(middle(&bleed, None).grain_press < 0.4);
    assert!(middle(&bleed, Some(PressureCurve::Bleed)).grain_press > 0.99);
    let round = [("pressure_roundness", 1.0)];
    assert!(middle(&round, None).roundness < 0.4);
    assert!(middle(&round, Some(PressureCurve::Roundness)).roundness > 0.99);
}

#[test]
fn the_wet_attack_follows_the_pressure_and_its_tilt_switch() {
    let attack = |pressure: f32, tilt: f32, switch: bool, pointer: Pointer| {
        let mut b = Brush { pressure_size: 0.0, ..plain() };
        if switch {
            b.set_setting("attack_tilt", 1.0);
        }
        let mut s = Sample::at(Vec2::new(10.0, 10.0), pressure, 0.0);
        s.tilt = tilt;
        StrokeBuilder::new(b, 10.0, pointer).preview(&[s])[0].attack
    };
    assert!((attack(1.0, 0.0, false, Pointer::Stylus) - 1.0).abs() < 1e-4);
    // the pressure counts twice: as the opacity takes it, and through the attack's own curve
    assert!((attack(0.4, 0.0, false, Pointer::Stylus) - 0.16).abs() < 0.03);
    // switched on: an upright pen next to none, a leaning one more
    assert!(attack(1.0, 0.0, true, Pointer::Stylus) < 0.01);
    let flat = Tilt::reach(crate::brush::FLAT_TILT, 0.0);
    assert!((attack(1.0, crate::brush::FLAT_TILT, true, Pointer::Stylus) - flat).abs() < 1e-3);
    // a finger as a flat pen, at full pressure
    let finger = Tilt::reach(std::f32::consts::FRAC_PI_2, 0.0);
    assert!((attack(1.0, 0.0, true, Pointer::Finger) - finger).abs() < 1e-3);
}

#[test]
fn a_finger_s_wet_attack_follows_the_opacity_slider() {
    let attack = |pointer: Pointer| {
        let b = Brush { pressure_size: 0.0, touch_opacity: 0.5, ..plain() };
        StrokeBuilder::new(b, 10.0, pointer).preview(&[Sample::at(Vec2::new(10.0, 10.0), 1.0, 0.0)])[0].attack
    };
    assert!((attack(Pointer::Finger) - 0.5).abs() < 1e-4);
    // a pen's follows its pressure instead
    assert!((attack(Pointer::Stylus) - 1.0).abs() < 1e-4);
}

#[test]
fn a_finger_s_wet_mix_takes_full_pressure_whatever_the_sample_says() {
    let wet = |pointer: Pointer, pressure: f32| {
        let b = Brush { pressure_size: 0.0, ..plain() };
        StrokeBuilder::new(b, 10.0, pointer).preview(&[Sample::at(Vec2::new(10.0, 10.0), pressure, 0.0)])[0].wet
    };
    // without curves the dilution and the pull follow the pressure: a finger has none, so full
    let finger = wet(Pointer::Finger, 0.3);
    assert!((finger[5] - 1.0).abs() < 1e-4 && (finger[6] - 1.0).abs() < 1e-4, "{finger:?}");
    // a pen takes them at its own
    let pen = wet(Pointer::Stylus, 0.5);
    assert!((pen[5] - pen[6]).abs() < 1e-4 && pen[6] < 0.9, "{pen:?}");
}

#[test]
fn a_leaning_pen_adds_its_tilt_bleed_in_full_whatever_the_pressure() {
    let bleed = |tilt: f32| {
        let mut b = Brush { pressure_size: 0.0, ..plain() };
        b.set_setting("pressure_bleed", 1.0);
        b.set_setting("tilt_bleed", 1.0);
        let mut s = Sample::at(Vec2::new(10.0, 10.0), 0.3, 0.0);
        s.tilt = tilt;
        let stamp = StrokeBuilder::new(b, 10.0, Pointer::Stylus).preview(&[s])[0];
        (stamp.grain_bleed, stamp.grain_press)
    };
    // upright: the pressure bleed alone (a lean's least is 2^-10), through the pressure
    let (upright, press) = bleed(0.0);
    assert!((upright - 1.0).abs() < 2e-3 && (press - 0.3).abs() < 0.05, "{upright} {press}");
    // leaning flat: the tilt bleed adds to it, and counts whatever the pressure
    let (flat, press) = bleed(crate::brush::FLAT_TILT);
    let lean = flat - 1.0;
    assert!(lean > 0.5, "{flat}");
    assert!((press - (0.3 + lean) / (1.0 + lean)).abs() < 0.05, "{press} {lean}");
}

#[test]
fn a_grain_s_least_depth_above_its_depth_draws_it_up() {
    use crate::curve::Curve;
    // depth 0.5, least 0.8, the depth following the pressure straight: pressed fully, the
    // depth; lightly, from it towards the least as far as the pressure leaves it
    let depth_at = |pressure: f32| {
        let mut b = plain();
        b.grain.depth = 0.5;
        b.set_setting("grain_depth_min", 0.8);
        b.pressure_curves.set(PressureCurve::GrainDepth, Some(Curve::linear()));
        let at = Sample::at(Vec2::new(0.0, 0.0), pressure, 0.0);
        // (a stamp's depth is a share of the brush's)
        StrokeBuilder::new(b, 10.0, Pointer::Stylus).add(&[at])[0].grain_depth * 0.5
    };
    assert!((depth_at(1.0) - 0.5).abs() < 1e-4, "{}", depth_at(1.0));
    assert!((depth_at(0.25) - (0.8 - 0.3 * 0.25)).abs() < 1e-3, "{}", depth_at(0.25));
}

#[test]
fn a_pen_that_doesnt_say_how_it_leans_has_no_lean_at_all() {
    // fading fully as it leans: upright, a pen still fades by the root of the lean's floor
    // (1/32, eased as the opacity is); one that doesn't say how it leans, not at all
    let alpha = |known: bool| {
        let mut b = Brush { pressure_size: 0.0, pressure_opacity: 0.0, ..plain() };
        b.tilt.opacity = 1.0;
        let input = InputSettings { tilt_known: known, ..InputSettings::default() };
        let s = Sample::at(Vec2::new(50.0, 50.0), 1.0, 0.0);
        StrokeBuilder::new(b, 10.0, Pointer::Stylus)
            .with_input(input)
            .preview(&[s])
            .first()
            .map_or(0.0, |s| s.alpha)
    };
    let (upright, unknown) = (alpha(true), alpha(false));
    assert!((upright / unknown - (1.0f32 - 1.0 / 32.0).powf(FLOW_POWER)).abs() < 1e-3, "{upright} {unknown}");
}

#[test]
fn tilt_switches_hold_their_controls_back_until_the_pen_leans() {
    let make = |tilt: f32, switches: bool| {
        let mut b = Brush { pressure_size: 0.0, ..plain() };
        b.shape.count = 8;
        b.grain.depth = 1.0;
        b.set_setting("jitter_lateral", 1.0);
        b.set_setting("grain_depth_min", 0.2);
        if switches {
            for k in ["count_tilt", "jitter_lateral_tilt", "grain_depth_tilt"] {
                b.set_setting(k, 1.0);
            }
        }
        let mut s = Sample::at(Vec2::new(50.0, 50.0), 1.0, 0.0);
        s.tilt = tilt;
        StrokeBuilder::new(b, 10.0, Pointer::Stylus).preview(&[s])
    };
    // off: all eight stamps, jittered off the point, the grain at full depth
    let off = make(0.0, false);
    assert_eq!(off.len(), 8);
    assert!(off.iter().any(|s| (s.center.y - 50.0).abs() > 0.5));
    // on and upright: one stamp, no jitter, the grain still at full depth
    let upright = make(0.0, true);
    assert_eq!(upright.len(), 1);
    // (the lean's floor, 1/1024, still lets a trace through)
    assert!((upright[0].center.y - 50.0).abs() < 0.05);
    assert!(upright[0].grain_depth > 0.99, "{}", upright[0].grain_depth);
    // a finger has no lean to wait for, so the switches aren't there: all of it, and the pad
    // without the brush's tilt preview likewise, and a pen that doesn't say how it leans
    let touched = |how: usize| {
        let mut b = Brush { pressure_size: 0.0, ..plain() };
        b.shape.count = 8;
        b.set_setting("jitter_lateral", 1.0);
        b.set_setting("count_tilt", 1.0);
        b.set_setting("jitter_lateral_tilt", 1.0);
        b.set_setting("stamp_hue", 1.0);
        b.set_setting("stamp_hue_tilt", 1.0);
        let s = Sample::at(Vec2::new(50.0, 50.0), 1.0, 0.0);
        let unknown = InputSettings { tilt_known: false, ..InputSettings::default() };
        match how {
            0 => StrokeBuilder::new(b, 10.0, Pointer::Finger).preview(&[s]),
            1 => StrokeBuilder::new(b, 10.0, Pointer::Stylus).on_pad(false).preview(&[s]),
            _ => StrokeBuilder::new(b, 10.0, Pointer::Stylus).with_input(unknown).preview(&[s]),
        }
    };
    for stamps in [touched(0), touched(1), touched(2)] {
        assert_eq!(stamps.len(), 8);
        assert!(stamps.iter().any(|s| (s.center.y - 50.0).abs() > 0.5));
        assert!(stamps.iter().any(|s| s.tint.hue.abs() > 0.05));
    }
    // on and leaning flat: more stamps, jittered, the grain pulled toward its least
    let flat = make(crate::brush::FLAT_TILT, true);
    assert!(flat.len() > 1);
    // (its angle the brush's tilt angle, 0 here)
    let reach = Tilt::reach(crate::brush::FLAT_TILT, 0.0);
    assert!((flat[0].grain_depth - (0.2 + 0.8 * (1.0 - reach))).abs() < 1e-3, "{}", flat[0].grain_depth);
}

#[test]
fn a_leaning_pen_grows_across_the_stroke_shades_and_rounds_the_stamp() {
    let flat = |x: f32, azimuth: f32| {
        let mut s = Sample::at(Vec2::new(x, 0.0), 1.0, f64::from(x));
        s.tilt = crate::brush::FLAT_TILT;
        s.azimuth = azimuth;
        s
    };
    let stamps = |settings: &[(&str, f32)], azimuth: f32| {
        let angle = 0.1 * std::f32::consts::FRAC_PI_2;
        let mut b =
            Brush { tilt: Tilt { angle, size: 0.1, opacity: 0.0 }, pressure_size: 0.0, max_size: 500.0, ..plain() };
        for (k, v) in settings {
            b.set_setting(k, *v);
        }
        let stroke = [flat(0.0, azimuth), flat(20.0, azimuth), flat(40.0, azimuth)];
        StrokeBuilder::new(b, 10.0, Pointer::Stylus).preview(&stroke)
    };
    // drawn along x: leaning along it (orientation pointing right) grows nothing, leaning
    // across it (pointing up) all of it; without compression either way
    let along = stamps(&[], std::f32::consts::FRAC_PI_2);
    let across = stamps(&[], 0.0);
    let last = |v: &[Stamp]| v.last().map_or(0.0, |s| s.radius);
    assert!((last(&along) - 5.0).abs() < 0.01, "{}", last(&along));
    assert!(last(&across) > 10.0, "{}", last(&across));
    let loose = stamps(&[("tilt_size_compression", 0.0)], std::f32::consts::FRAC_PI_2);
    assert!((last(&loose) - last(&across)).abs() < 0.01);
    // shading by the square root of the gradation as far as the lean has gone; the
    // roundness tilt takes the stamp to its leaning roundness as far
    let reach = Tilt::reach(crate::brush::FLAT_TILT, 0.1);
    let shaded = stamps(&[("tilt_gradation", 0.6), ("tilt_size", 0.0)], 0.0);
    assert!((shaded[0].gradation - (0.6 * reach).sqrt()).abs() < 1e-4, "{}", shaded[0].gradation);
    let rounded = stamps(&[("tilt_roundness", 0.5), ("tilt_size", 0.0)], 0.0);
    assert!((rounded[0].roundness - (1.0 - 0.5 * reach)).abs() < 1e-4, "{}", rounded[0].roundness);
}

#[test]
fn the_tilt_s_roundness_holds_only_where_the_tilt_acts() {
    // a brush squeezed to 0.4 of its height until it leans: so drawn upright with a pen, as
    // a finger draws it; on the pad (and in a preview) only if the brush previews tilt
    let mut b = plain();
    b.set_setting("tilt_roundness_max", 0.4);
    let s = Sample::at(Vec2::new(50.0, 50.0), 1.0, 0.0);
    let roundness = |mut builder: StrokeBuilder| builder.preview(&[s])[0].roundness;
    assert!((roundness(StrokeBuilder::new(b.clone(), 10.0, Pointer::Stylus)) - 0.4).abs() < 0.01);
    assert!((roundness(StrokeBuilder::new(b.clone(), 10.0, Pointer::Finger)) - 0.4).abs() < 0.01);
    assert!((roundness(StrokeBuilder::new(b.clone(), 10.0, Pointer::Stylus).on_pad(true)) - 0.4).abs() < 0.01);
    assert_eq!(roundness(StrokeBuilder::new(b, 10.0, Pointer::Stylus).on_pad(false)), 1.0);
}

#[test]
fn a_taper_thins_what_the_stamp_is_drawn_at_and_a_lean_s_growth_stays() {
    let mut b = Brush { pen_taper: Taper { start: 0.0, end: 0.5, size: 1.0, opacity: 0.0 }, ..plain() };
    b.tilt.size = 0.1;
    b.set_setting("size_tilt_angle", 0.1);
    b.set_setting("tilt_size_compression", 0.0);
    let leaning = |i: usize| {
        let at = Sample::at(Vec2::new(i as f32 * 8.0, 50.0), 1.0, i as f64 * 8.0);
        Sample { tilt: crate::brush::FLAT_TILT, ..at }
    };
    let mut stroke = StrokeBuilder::new(b.clone(), 10.0, Pointer::Stylus);
    stroke.add(&(0..=60).map(leaning).collect::<Vec<_>>());
    let all = stroke.finish();
    let growth = 0.1 * Tilt::reach(crate::brush::FLAT_TILT, 0.1) * b.size_unit();
    let (middle, end) = (&all[all.len() / 2], all.last().unwrap());
    assert!((middle.radius - (10.0 + growth) / 2.0).abs() < 1e-3, "{}", middle.radius);
    // the end, thinned to nothing, is what the lean adds
    assert!((end.radius - growth / 2.0).abs() < 0.2, "{} {growth}", end.radius);
    // and its height stays by it: round where only the growth is left of a flat stamp
    let flat = Brush { shape: Shape { roundness: 0.2, ..Shape::default() }, ..b };
    let mut stroke = StrokeBuilder::new(flat, 10.0, Pointer::Stylus);
    stroke.add(&(0..=60).map(leaning).collect::<Vec<_>>());
    let all = stroke.finish();
    assert!(all[all.len() / 2].roundness < 0.9, "{}", all[all.len() / 2].roundness);
    assert!(all.last().unwrap().roundness > 0.97, "{}", all.last().unwrap().roundness);
}

#[test]
fn a_taper_draws_the_jitter_in_with_the_size() {
    let taper = Taper { start: 1.0, end: 0.0, size: 1.0, opacity: 0.0 };
    let mut b = Brush { pen_taper: taper, ..plain() };
    b.set_setting("jitter_lateral", 1.0);
    let mut stroke = StrokeBuilder::new(b, 40.0, Pointer::Stylus);
    stroke.add(&line(100, 1200.0));
    let all = stroke.finish();
    // the first stamps, thinned almost to nothing, stay by the path; past the taper they don't
    let off = |s: &Stamp| (s.center.y - 50.0).abs();
    let tips = all.iter().take_while(|s| s.radius < 1.0).map(off).fold(0.0, f32::max);
    let whole = all.iter().rev().take(100).map(off).fold(0.0, f32::max);
    assert!(tips < 10.0, "{tips}");
    assert!(whole > 30.0, "{whole}");
}

#[test]
fn a_wobble_as_the_pen_slows_doesnt_swing_stamps_that_follow_the_stroke() {
    let mut b = plain();
    b.set_setting("shape_rotation", 1.0);
    // along x, then a pen slowing to lift: steps of a fraction of a pixel, every which way
    let mut samples: Vec<Sample> =
        (0..=20).map(|i| Sample::at(Vec2::new(i as f32 * 10.0, 0.0), 1.0, f64::from(i) * 8.0)).collect();
    for (k, (dx, dy)) in
        [(0.3, 0.6), (-0.2, -0.7), (0.4, 0.5), (-0.5, 0.2), (0.1, -0.6), (0.2, 0.7)].iter().enumerate()
    {
        let x = 200.0 + dx * (k + 1) as f32 / 3.0;
        samples.push(Sample::at(Vec2::new(x, *dy), 1.0, 168.0 + k as f64 * 8.0));
    }
    let stamps = StrokeBuilder::new(b, 40.0, Pointer::Stylus).preview(&samples);
    let off = |a: f32| (a + std::f32::consts::PI).rem_euclid(std::f32::consts::TAU) - std::f32::consts::PI;
    let worst = stamps.iter().skip(1).map(|s| off(s.angle).abs()).fold(0.0, f32::max);
    assert!(worst < 0.3, "a stamp turned {worst} radians off the stroke");
}

#[test]
fn a_pen_turns_the_stamp_as_held_in_place_of_following_the_stroke() {
    let angle = |pointer: Pointer, relative: f32| {
        let mut b = plain();
        b.set_setting("shape_rotation", 1.0);
        b.set_setting("input_style", 1.0);
        b.set_setting("relative_to_stroke", relative);
        // down the screen (a quarter turn), the pen leaning 0.3 radians
        let at = |y: f32| Sample { azimuth: 0.3, ..Sample::at(Vec2::new(0.0, y), 1.0, f64::from(y)) };
        let stamps = StrokeBuilder::new(b, 10.0, pointer).preview(&[at(0.0), at(20.0), at(40.0)]);
        stamps.last().map_or(f32::NAN, |s| s.angle)
    };
    let quarter = std::f32::consts::FRAC_PI_2;
    // the pen as held; touch follows the stroke (and has no lean); relative to the stroke goes
    // by which barrel roll counts, so it changes neither
    assert!((angle(Pointer::Stylus, 0.0) - 0.3).abs() < 1e-4, "{}", angle(Pointer::Stylus, 0.0));
    assert!((angle(Pointer::Finger, 0.0) - quarter).abs() < 1e-4, "{}", angle(Pointer::Finger, 0.0));
    assert!((angle(Pointer::Stylus, 1.0) - 0.3).abs() < 1e-4, "{}", angle(Pointer::Stylus, 1.0));
}

/// The library's sample stroke tells no pen's turn: a brush turned as the pen is held turns its
/// stamps along the stroke there, as a finger does, each stamp with the stroke's way where it is.
#[test]
fn a_sample_stroke_without_the_pen_s_turn_turns_stamps_along_its_way() {
    let angles = |azimuth: bool, follow: f32| {
        let mut b = plain();
        b.set_setting("shape_rotation", follow);
        b.set_setting("input_style", if azimuth { 1.0 } else { 0.0 });
        // a quarter circle from heading right to heading down, the pen held turned 0.3 radians
        let at = |i: u32| {
            let a = i as f32 / 20.0 * std::f32::consts::FRAC_PI_2;
            let p = Vec2::new(200.0 * a.sin(), 200.0 * (1.0 - a.cos()));
            Sample { azimuth: 0.3, ..Sample::at(p, 1.0, f64::from(i) * 4.0) }
        };
        let samples: Vec<Sample> = (0..=20).map(at).collect();
        // the stroke as it ends, its first stamps turned to the way it went
        let mut stroke = StrokeBuilder::new(b, 10.0, Pointer::Stylus).steady().on_pad(false).without_azimuth();
        stroke.add(&samples);
        let stamps = stroke.finish();
        (stamps.first().map_or(f32::NAN, |s| s.angle), stamps.last().map_or(f32::NAN, |s| s.angle))
    };
    let quarter = std::f32::consts::FRAC_PI_2;
    for azimuth in [true, false] {
        let (first, last) = angles(azimuth, 1.0);
        assert!(first.abs() < 0.2, "azimuth {azimuth}: the first stamp heads right, {first}");
        assert!((last - quarter).abs() < 0.2, "azimuth {azimuth}: the last heads down, {last}");
    }
    // a fixed one stays as it is set, the pen's turn not added
    let (first, last) = angles(true, 0.0);
    assert!(first.abs() < 1e-4 && last.abs() < 1e-4, "{first} {last}");
}

#[test]
fn the_studios_dynamics_shape_the_stroke() {
    let with = |key: &str, v: f32| {
        let mut b = plain();
        b.set_setting(key, v);
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus);
        s.add(&line(50, 200.0));
        s.finish()
    };
    let base = with("spacing", 0.1);
    // jitter moves stamps off the line
    assert!(with("jitter_lateral", 1.0).iter().any(|s| (s.center.y - 50.0).abs() > 2.0));
    assert!(base.iter().all(|s| (s.center.y - 50.0).abs() < 1e-3));
    // spacing jitter changes how many stamps there are
    assert_ne!(with("spacing_jitter", 1.0).len(), base.len());
    // fall off fades the far end: by 33 px a fall off of 1 has faded it out, a half by 264
    let faded = with("falloff", 1.0);
    assert!(faded.last().unwrap().alpha < 1e-6 && faded[0].alpha > 0.5);
    let half = with("falloff", 0.5);
    let near = |x: f32| half.iter().min_by(|a, b| (a.center.x - x).abs().total_cmp(&(b.center.x - x).abs()));
    let (start, by_33) = (near(0.0).map_or(0.0, |s| s.alpha), near(33.0).map_or(0.0, |s| s.alpha));
    assert!((by_33 / start - 0.5).abs() < 0.05, "{start} {by_33}");
    // fast input (600 px in 200 ms: 50 px a frame) thins the stroke with speed size
    let fast = |b: Brush| {
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus);
        s.add(&line(50, 600.0));
        s.finish()
    };
    let base = fast(plain());
    let mut b = plain();
    b.set_setting("speed_size", 1.0);
    let thin = fast(b);
    assert!(thin[thin.len() / 2].radius < base[base.len() / 2].radius);
    // and thinner still when a shorter distance counts as fast
    let mut b = plain();
    b.set_setting("speed_size", 1.0);
    let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus)
        .with_input(InputSettings { speed_distance: 0.25, ..InputSettings::default() });
    s.add(&line(50, 600.0));
    let thinner = s.finish();
    // and not at all when slow (200 px in 200 ms) or seen small (a quarter point a pixel)
    let mut b = plain();
    b.set_setting("speed_size", 1.0);
    let mut s = StrokeBuilder::new(b.clone(), 10.0, Pointer::Stylus);
    s.add(&line(50, 200.0));
    assert!((s.finish()[25].radius - base[base.len() / 2].radius).abs() < 1e-4);
    let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus).seen_at(0.25);
    s.add(&line(50, 600.0));
    assert!((s.finish()[40].radius - base[base.len() / 2].radius).abs() < 1e-4);
    assert!(thinner[thinner.len() / 2].radius < thin[thin.len() / 2].radius);
    // roundness falls with light pressure
    let mut b = plain();
    b.set_setting("pressure_roundness", 1.0);
    let light = StrokeBuilder::new(b, 10.0, Pointer::Stylus).preview(&[Sample::at(Vec2::new(0.0, 0.0), 0.2, 0.0)]);
    assert!(light[0].roundness < 0.3);
    // stabilisation averages a zigzag away
    let zigzag: Vec<Sample> = (0..100)
        .map(|i| Sample::at(Vec2::new(i as f32 * 2.0, if i % 2 == 0 { 0.0 } else { 20.0 }), 1.0, i as f64 * 8.0))
        .collect();
    let mut b = plain();
    b.set_setting("stabilization", 1.0);
    let mut s = StrokeBuilder::new(b, 4.0, Pointer::Stylus);
    s.add(&zigzag);
    let ys: Vec<f32> = s.stamps()[40..].iter().map(|s| s.center.y).collect();
    let spread = ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min);
    assert!(spread < 5.0, "{spread}");
}

#[test]
fn more_of_the_studios_dynamics_shape_the_stroke() {
    let stroke = |settings: &[(&str, f32)], pressure: f32, taper: Taper| {
        let mut b = Brush { pen_taper: taper, ..plain() };
        b.grain.image = Some(0);
        b.grain.mode = crate::brush::GrainMode::Moving;
        b.grain.depth = 0.5;
        for &(k, v) in settings {
            b.set_setting(k, v);
        }
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus);
        let samples: Vec<Sample> =
            (0..=50).map(|i| Sample::at(Vec2::new(i as f32 * 4.0, 50.0), pressure, i as f64 * 4.0)).collect();
        s.add(&samples);
        s.finish()
    };
    let mid = |v: &[Stamp]| v[v.len() / 2];
    // flow falls with light pressure
    let plain_light = mid(&stroke(&[], 0.3, Taper::NONE));
    let flow_light = mid(&stroke(&[("pressure_flow", 1.0)], 0.3, Taper::NONE));
    assert!(flow_light.alpha < plain_light.alpha * 0.5, "{} {}", flow_light.alpha, plain_light.alpha);
    // a blunt tip keeps width at the stroke's ends (all but the very first stamp)
    let taper = Taper { start: 0.3, end: 0.3, size: 1.0, opacity: 0.0 };
    let past = |v: Vec<Stamp>| v.iter().find(|s| s.distance > 4.0).map_or(0.0, |s| s.radius);
    let sharp = past(stroke(&[], 1.0, taper.clone()));
    let blunt = past(stroke(&[("pen_taper_tip", 1.0)], 1.0, taper));
    assert!(blunt > sharp + 1.0, "{blunt} {sharp}");
    // the secondary colour: by pressure, as its curve says, and jittered per stamp
    assert_eq!(mid(&stroke(&[], 0.3, Taper::NONE)).secondary, 0.0);
    assert!((mid(&stroke(&[("pressure_secondary", 1.0)], 0.3, Taper::NONE)).secondary - 0.3).abs() < 0.05);
    let jittered = stroke(&[("stamp_secondary", 1.0)], 1.0, Taper::NONE);
    assert!(jittered.iter().any(|s| s.secondary > 0.5) && jittered.iter().any(|s| s.secondary < 0.5));
    // a moving grain: filled in by pressing harder, never under its least, jittered
    assert!((mid(&stroke(&[], 0.3, Taper::NONE)).grain_depth - 1.0).abs() < 1e-5);
    let light = mid(&stroke(&[("pressure_bleed", 1.0)], 0.3, Taper::NONE));
    let hard = mid(&stroke(&[("pressure_bleed", 1.0)], 0.9, Taper::NONE));
    assert!((light.grain_press - 0.3).abs() < 0.05 && (hard.grain_press - 0.9).abs() < 0.05);
    assert!((hard.grain_depth - 1.0).abs() < 1e-5 && (hard.grain_bleed - 1.0).abs() < 1e-5);
    assert_eq!(mid(&stroke(&[], 0.9, Taper::NONE)).grain_bleed, 0.0);
    // jittered between its least and its depth (0.4 to 0.5: 0.8 to 1 of it), not floored
    let jittered = stroke(&[("grain_depth_jitter", 1.0), ("grain_depth_min", 0.4)], 1.0, Taper::NONE);
    assert!(jittered.iter().all(|s| s.grain_depth >= 0.8 - 1e-4));
    assert!(jittered.iter().any(|s| s.grain_depth > 0.81 && s.grain_depth < 0.99));
    // its tile: cropped, the same whatever the stamp's size; following the size, sized with
    // the stamp (a lighter, smaller stamp, a smaller grain)
    let tile = |zoom: f32, pressure: f32| {
        let mut b = plain();
        b.pressure_size = 1.0;
        b.set_setting("grain_zoom", zoom);
        // a file's scale of 1
        b.grain.scale = crate::studio::MAX_GRAIN_SCALE / 4.0;
        let scale = b.grain.scale;
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus);
        s.add(&[Sample::at(Vec2::new(0.0, 0.0), pressure, 0.0)]);
        let stamp = s.finish()[0];
        (stamp.grain_size * scale, stamp.radius)
    };
    let ((cropped_light, _), (cropped_hard, _)) = (tile(1.0, 0.5), tile(1.0, 1.0));
    assert!((cropped_light - cropped_hard).abs() < 1e-3, "{cropped_light} {cropped_hard}");
    let ((light, light_r), (hard, hard_r)) = (tile(0.0, 0.5), tile(0.0, 1.0));
    assert!(light < hard * 0.8 && (light / hard - light_r / hard_r).abs() < 0.02, "{light} {hard}");
    // the stamp's square it goes by, which a round tip covers only part of
    let diagonal = 10.0 / crate::brushfile::ROUND_TIP_SHARE * std::f32::consts::SQRT_2;
    assert!((hard - diagonal).abs() < 1e-3, "{hard}");
    // a taper's thinning too
    let mut b = Brush { pressure_size: 0.0, pen_taper: Taper { start: 1.0, ..Taper::NONE }, ..plain() };
    b.pen_taper.size = 1.0;
    b.set_setting("grain_zoom", 0.0);
    b.grain.scale = crate::studio::MAX_GRAIN_SCALE / 4.0;
    let mut s = StrokeBuilder::new(b, 40.0, Pointer::Stylus);
    s.add(&line(100, 1200.0));
    let all = s.finish();
    let (thin, whole) = (all[2], all[all.len() - 1]);
    assert!(thin.radius < whole.radius * 0.2, "{} {}", thin.radius, whole.radius);
    assert!((thin.grain_size / whole.grain_size - thin.radius / whole.radius).abs() < 0.02);
    // the brush's own motion filtering smooths as the app's does
    let zigzag: Vec<Sample> = (0..60)
        .map(|i| Sample::at(Vec2::new(i as f32 * 2.0, if i % 2 == 0 { 0.0 } else { 20.0 }), 1.0, i as f64 * 8.0))
        .collect();
    let spread = |filtering: f32| {
        let mut b = plain();
        b.set_setting("motion_filtering", filtering);
        let mut s = StrokeBuilder::new(b, 4.0, Pointer::Stylus);
        s.add(&zigzag);
        let stamps = s.finish();
        let third = stamps.len() / 3;
        let ys: Vec<f32> = stamps.iter().skip(third).take(third).map(|s| s.center.y).collect();
        ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min)
    };
    assert!(spread(1.0) < spread(0.0) * 0.5, "{} {}", spread(1.0), spread(0.0));
}

#[test]
fn sparse_input_curves_through_its_points_and_an_exact_path_keeps_its_corners() {
    // a finger round a circle of 100 px in 8 samples: straight between them, the middles
    // would fall 7.6 px inside it
    let round: Vec<Sample> = (0..=8)
        .map(|i| {
            let a = i as f32 / 8.0 * std::f32::consts::TAU;
            Sample::at(Vec2::new(200.0 + 100.0 * a.cos(), 200.0 + 100.0 * a.sin()), 1.0, f64::from(i) * 8.0)
        })
        .collect();
    let mut s = StrokeBuilder::new(plain(), 4.0, Pointer::Finger);
    s.add(&round);
    let stamps = s.finish();
    let off = stamps.iter().map(|p| (p.center.distance(Vec2::new(200.0, 200.0)) - 100.0).abs()).fold(0.0, f32::max);
    assert!(off < 1.5, "{off} px off the circle");
    // a square drawn exactly: its corner is a corner, its sides straight
    let square = [(0.0, 0.0), (100.0, 0.0), (100.0, 100.0)];
    let samples: Vec<Sample> = square
        .iter()
        .enumerate()
        .map(|(i, &(x, y))| Sample::at(Vec2::new(x + 50.0, y + 50.0), 1.0, i as f64 * 8.0))
        .collect();
    let mut s = StrokeBuilder::new(plain(), 4.0, Pointer::Finger).exact_path();
    s.add(&samples);
    let stamps = s.finish();
    assert!(stamps.iter().all(|p| (p.center.y - 50.0).abs() < 1e-3 || (p.center.x - 150.0).abs() < 1e-3));
    assert!(stamps.iter().any(|p| p.center.distance(Vec2::new(150.0, 50.0)) < 1.0));
}

/// A pen half round a circle of 150 px about (400, 400) in 150 ms, reported 60 times a
/// second: 20° between samples.
fn half_circle() -> Vec<Sample> {
    (0..=9)
        .map(|i| {
            let a = std::f32::consts::PI * (1.0 - i as f32 / 9.0);
            let at = Vec2::new(400.0 + 150.0 * a.cos(), 400.0 - 150.0 * a.sin());
            Sample::at(at, 1.0, f64::from(i) * 1000.0 / 60.0)
        })
        .collect()
}

/// How far the furthest of `stamps` is off the circle of [`half_circle`].
fn off_the_circle(stamps: &[Stamp]) -> f32 {
    stamps.iter().map(|s| (s.center.distance(Vec2::new(400.0, 400.0)) - 150.0).abs()).fold(0.0, f32::max)
}

#[test]
fn a_strokes_first_and_last_stretches_bend_as_it_does() {
    let drawn = |samples: &[Sample]| {
        let mut s = StrokeBuilder::new(plain(), 6.0, Pointer::Stylus);
        s.add(samples);
        s.finish()
    };
    // straight from one sample to the next the stamps would fall 2.3 px inside the circle;
    // the first and last stretches, with nothing before or after them, bend as the rest do
    // (walked as if their end point came twice, they fell 1.1 px inside)
    let stamps = drawn(&half_circle());
    assert!(off_the_circle(&stamps) < 0.2, "{} px off the circle", off_the_circle(&stamps));
    // starting where the pen landed and ending where it lifted
    assert!(stamps.first().is_some_and(|s| s.center.distance(Vec2::new(250.0, 400.0)) < 1e-3));
    assert!(stamps.last().is_some_and(|s| s.center.distance(Vec2::new(550.0, 400.0)) < 1.5));
    // the lift reported where the pen last was, and a landing reported twice, change nothing
    let mut twice = half_circle();
    twice.insert(1, Sample { time_ms: 1.0, ..twice[0] });
    if let Some(&last) = twice.last() {
        twice.push(Sample { time_ms: last.time_ms + 1000.0 / 60.0, ..last });
    }
    let again = drawn(&twice);
    assert_eq!(again.len(), stamps.len());
    assert!(again.iter().zip(&stamps).all(|(a, b)| a.center.distance(b.center) < 1e-3));
}

/// The most the stroke turns (degrees) from the chord over `k` stamps to the next, anywhere
/// after its first `from` stamps.
fn most_turn(stamps: &[Stamp], k: usize, from: usize) -> f32 {
    let at = |i: usize| stamps.get(i).map_or(Vec2::ZERO, |s| s.center);
    (from.max(k)..stamps.len().saturating_sub(k))
        .map(|i| {
            let (u, v) = (at(i) - at(i - k), at(i + k) - at(i));
            (u.x * v.y - u.y * v.x).atan2(u.x * v.x + u.y * v.y).abs().to_degrees()
        })
        .fold(0.0, f32::max)
}

#[test]
fn the_smoothed_path_catches_up_with_the_pen_along_the_curve() {
    let drawn = |input: InputSettings| {
        let mut s =
            StrokeBuilder::new(Brush { streamline: 0.015, ..plain() }, 6.0, Pointer::Stylus).with_input(input);
        s.add(&half_circle());
        s.finish()
    };
    // streamline: the path lags inside the circle, and at the lift it comes back out to where
    // the pen is along the curve, turning no more sharply there than anywhere (a straight run
    // to the lift point broke off from the curve by 12°)
    let stamps = drawn(InputSettings::default());
    let late = stamps.len() * 2 / 3;
    let turn = most_turn(&stamps, 3, late);
    assert!(turn < 4.0, "the end turns {turn}°");
    assert!(stamps.last().is_some_and(|s| s.center.distance(Vec2::new(550.0, 400.0)) < 1.5));
    assert!(off_the_circle(&stamps) < 3.0, "{} px off the circle", off_the_circle(&stamps));
}

/// A quick slash, curved as a C, `ms` long and sampled 120 times a second: down the left of an
/// ellipse, slowly at its ends.
fn slash(ms: f64) -> Vec<Sample> {
    let n = (ms * 0.12) as usize;
    (0..=n)
        .map(|i| {
            let u = i as f32 / n as f32;
            let a = std::f32::consts::PI * (0.15 + 0.8 * u * u * (3.0 - 2.0 * u));
            Sample::at(
                Vec2::new(600.0 - 420.0 * a.sin(), 400.0 - 320.0 * a.cos()),
                0.7,
                f64::from(i as u32) * 25.0 / 3.0,
            )
        })
        .collect()
}

/// A stroke through `samples` with stabilisation at `amount`, whole.
fn stabilised(amount: f32, samples: &[Sample]) -> Vec<Stamp> {
    let mut b = plain();
    b.set_setting("stabilization", amount);
    let mut s = StrokeBuilder::new(b, 6.0, Pointer::Stylus);
    s.add(samples);
    s.finish()
}

#[test]
fn a_stabilised_slash_bends_as_a_whole_and_keeps_both_its_ends() {
    for ms in [150.0, 400.0, 900.0] {
        let input = slash(ms);
        let (start, end) = (input[0].pos, input[input.len() - 1].pos);
        for amount in [0.2, 0.5, 0.75, 1.0] {
            let stamps = stabilised(amount, &input);
            assert!(stamps[0].center.distance(start) < 1.0, "{ms} ms at {amount}: starts off the input");
            let last = stamps[stamps.len() - 1].center;
            assert!(last.distance(end) < 2.0, "{ms} ms at {amount}: ends {last:?}, not {end:?}");
            // nowhere does it break off into a straight run to the lift: from one 20 px stretch
            // to the next it turns no more than the slash does (an average trailing the pen
            // and catching up at the lift turned 18°)
            let k = (20.0 / stamps[0].center.distance(stamps[1].center)).ceil() as usize;
            let turn = most_turn(&stamps, k, k);
            assert!(turn < 8.0, "{ms} ms at {amount}: turns {turn}° at once");
        }
    }
}

#[test]
fn stabilisation_averages_about_each_point_so_a_stroke_comes_out_the_same_either_way() {
    // drawn back the way it came, at the same pace, it lies on the same curve
    let there = slash(400.0);
    let end = there[there.len() - 1].time_ms;
    let mut back: Vec<Sample> = there.iter().map(|s| Sample { time_ms: end - s.time_ms, ..*s }).collect();
    back.reverse();
    let (a, b) = (stabilised(0.75, &there), stabilised(0.75, &back));
    for p in a.iter().step_by(10) {
        let near = b.iter().map(|q| q.center.distance(p.center)).fold(f32::MAX, f32::min);
        assert!(near < 2.5, "{near} px off at {:?}", p.center);
    }
    // and with the average the width of the whole stroke, it leaves the curve for the chord
    let input = slash(150.0);
    let (from, to) = (input[0].pos, input[input.len() - 1].pos);
    let along = (to - from).normalized_or(Vec2::new(1.0, 0.0));
    let across = |p: Vec2| ((p - from).x * along.y - (p - from).y * along.x).abs();
    let bow = |s: &[Stamp]| s.iter().map(|s| across(s.center)).fold(0.0, f32::max);
    assert!(bow(&stabilised(1.0, &input)) < bow(&stabilised(0.2, &input)) * 0.5);
}

#[test]
fn a_stabilised_stroke_shows_as_it_will_end_and_keeps_what_it_has_drawn() {
    let mut b = plain();
    b.set_setting("stabilization", 0.5);
    let input = slash(600.0);
    let mut live = StrokeBuilder::new(b.clone(), 6.0, Pointer::Stylus);
    let mut drawn = Vec::new();
    for chunk in input.chunks(3) {
        drawn.extend(live.add(chunk));
        // what shows reaches the input, whatever the average still has to take in
        let ahead = live.preview_to_input(&[]);
        let end = ahead.last().or(drawn.last()).map_or(Vec2::ZERO, |s| s.center);
        let at = chunk[chunk.len() - 1].pos;
        assert!(end.distance(at) < 2.0, "{end:?} behind the input at {at:?}");
    }
    // shown just before the lift, the stroke is the stroke as it ends
    let shown: Vec<Vec2> = drawn.iter().chain(&live.preview_to_input(&[])).map(|s| s.center).collect();
    let done: Vec<Vec2> = live.finish().iter().map(|s| s.center).collect();
    assert_eq!(shown.len(), done.len());
    assert!(shown.iter().zip(&done).all(|(a, b)| a.distance(*b) < 0.01));
}

#[test]
fn stabilisation_keeps_a_tap_and_a_stroke_all_inside_its_reach() {
    let mut b = plain();
    b.set_setting("stabilization", 1.0);
    let mut tap = StrokeBuilder::new(b.clone(), 6.0, Pointer::Stylus);
    tap.add(&[Sample::at(Vec2::new(10.0, 10.0), 0.5, 0.0)]);
    let tapped = tap.finish();
    assert_eq!(tapped.len(), 1);
    assert_eq!(tapped[0].center, Vec2::new(10.0, 10.0));
    // a few points, far fewer than it averages at full strength
    let few: Vec<Sample> =
        (0..4).map(|i| Sample::at(Vec2::new(i as f32 * 30.0, 0.0), 0.5, f64::from(i) * 8.0)).collect();
    let stamps = stabilised(1.0, &few);
    assert!(stamps.iter().all(|s| s.center.is_finite()));
    assert!(stamps[0].center.distance(few[0].pos) < 1.0);
    assert!(stamps[stamps.len() - 1].center.distance(few[3].pos) < 2.0);
}

#[test]
fn smoothing_takes_the_same_time_at_any_input_rate() {
    // a pen moving 1 px a millisecond for 800 ms, pressure rising from 0.2 to 1, sampled at
    // the tuned rate and at three times it
    let path = |rate: f64| -> Vec<Sample> {
        let n = (rate * 0.8) as usize;
        (0..=n)
            .map(|i| {
                let t = i as f64 * 1000.0 / rate;
                Sample::at(Vec2::new(t as f32, 50.0), 0.2 + 0.8 * (t / 800.0) as f32, t)
            })
            .collect()
    };
    // how far the stroke's end is behind the pen's, and the last stamp's size
    let end = |b: &Brush, rate: f64| {
        let mut s = StrokeBuilder::new(b.clone(), 4.0, Pointer::Stylus);
        s.add(&path(rate));
        let last = *s.stamps().last().unwrap();
        (800.0 - last.center.x, last.radius)
    };
    let close = |a: f32, b: f32, share: f32| (a - b).abs() <= share * a.abs().max(b.abs());
    for (setting, amount, behind) in [("stabilization", 1.0, 200.0), ("motion_filtering", 0.25, 15.0)] {
        let mut b = plain();
        b.set_setting(setting, amount);
        let (slow, fast) = (end(&b, PEN_RATE).0, end(&b, PEN_RATE * 3.0).0);
        assert!(slow > behind, "{setting} lags {slow}");
        assert!(close(slow, fast, 0.1), "{setting}: {slow} behind at 240 Hz, {fast} at 720 Hz");
    }
    let b = Brush { pressure_smoothing: 0.9, pressure_size: 1.0, ..plain() };
    let unsmoothed = end(&Brush { pressure_smoothing: 0.0, ..b.clone() }, PEN_RATE).1;
    let (slow, fast) = (end(&b, PEN_RATE).1, end(&b, PEN_RATE * 3.0).1);
    assert!(slow < unsmoothed, "{slow} vs {unsmoothed}");
    assert!(
        close(unsmoothed - slow, unsmoothed - fast, 0.15),
        "pressure: {slow} at 240 Hz, {fast} at 720 Hz, {unsmoothed} unsmoothed"
    );
}

#[test]
fn a_stroke_leaves_where_the_pen_landed_without_a_hook() {
    // the half circle, as it is and with a pen's streamline: the first 6 px head down the
    // circle's tangent (the chord that far along is 1.2° off it; the first stretch walked as
    // if its first point came twice headed 4° to 6° off)
    for streamline in [0.0, 0.015] {
        let mut s = StrokeBuilder::new(Brush { streamline, ..plain() }, 6.0, Pointer::Stylus);
        s.add(&half_circle());
        let stamps = s.finish();
        let landed = Vec2::new(250.0, 400.0);
        let on = stamps.iter().find(|st| st.center.distance(landed) >= 6.0).map_or(landed, |st| st.center);
        let heading = (on - landed).y.atan2((on - landed).x).to_degrees();
        assert!((heading + 90.0).abs() < 2.5, "streamline {streamline}: heads {heading}°");
    }
    // a pen landing 2 px off the line it then draws, 240 times a second at 2000 px/s: the
    // stroke comes back to the line without swinging past it
    let samples: Vec<Sample> = (0..40)
        .map(|i| {
            let y = if i == 1 { 52.0 } else { 50.0 };
            Sample::at(Vec2::new(100.0 + i as f32 * 25.0 / 3.0, y), 1.0, f64::from(i) * 1000.0 / 240.0)
        })
        .collect();
    let mut s = StrokeBuilder::new(plain(), 6.0, Pointer::Stylus);
    s.add(&samples);
    let stamps = s.finish();
    let lowest = stamps.iter().map(|st| st.center.y).fold(f32::MAX, f32::min);
    let highest = stamps.iter().map(|st| st.center.y).fold(f32::MIN, f32::max);
    assert!(lowest > 49.75 && highest < 52.01, "{lowest}..{highest}");
    assert!(stamps.iter().filter(|st| st.center.x > 130.0).all(|st| (st.center.y - 50.0).abs() < 0.01));
}

#[test]
fn the_first_stamp_turns_with_the_stroke_as_the_rest_do() {
    let brush = Brush { shape: Shape { follow: 1.0, ..Shape::default() }, ..plain() };
    // straight down the canvas: every stamp turned a quarter, the one where the pen landed too
    let down: Vec<Sample> =
        (0..=10).map(|i| Sample::at(Vec2::new(50.0, 4.0 * i as f32), 1.0, f64::from(i) * 4.0)).collect();
    let mut s = StrokeBuilder::new(brush.clone(), 10.0, Pointer::Stylus);
    s.add(&down);
    let stamps = s.finish();
    assert!(stamps.len() > 10);
    assert!(stamps.iter().all(|st| (st.angle - std::f32::consts::FRAC_PI_2).abs() < 1e-4), "{:?}", stamps[0]);
    // a tap goes nowhere: its dot turns as if it went up the screen
    let mut s = StrokeBuilder::new(brush, 10.0, Pointer::Stylus);
    s.add(&down[..1]);
    assert!((s.finish()[0].angle + std::f32::consts::FRAC_PI_2).abs() < 1e-5);
}

#[test]
fn a_brush_oriented_to_the_screen_turns_back_with_the_canvas() {
    let turned = |orient: f32| {
        let mut b = plain();
        b.set_setting("orient_to_screen", orient);
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Stylus).on_view_turned(0.5);
        s.add(&[Sample::at(Vec2::new(0.0, 0.0), 1.0, 0.0)]);
        s.finish()[0].angle
    };
    assert_eq!(turned(0.0), 0.0);
    assert!((turned(1.0) + 0.5).abs() < 1e-6);
}

#[test]
fn a_brush_set_to_azimuth_and_barrel_roll_turns_its_stamps_as_the_barrel_turns() {
    let quarter = std::f32::consts::FRAC_PI_2;
    let angles_from = |style: f32, relative: f32, pointer: Pointer| {
        let mut b = plain();
        b.set_setting("input_style", style);
        b.set_setting("relative_to_stroke", relative);
        let mut s = StrokeBuilder::new(b, 10.0, pointer);
        let samples: Vec<Sample> = (0..=20u8)
            .map(|i| {
                let at = Sample::at(Vec2::new(f32::from(i) * 5.0, 0.0), 1.0, f64::from(i) * 8.0);
                Sample { roll: 1.0 + f32::from(i) * quarter / 20.0, ..at }
            })
            .collect();
        s.add(&samples);
        let stamps = s.finish();
        (stamps.first().map(|s| s.angle), stamps.last().map(|s| s.angle))
    };
    // relative to the stroke: from how the barrel was turned as the stroke began, as far as it
    // turns on
    let angles = |style: f32, pointer: Pointer| angles_from(style, 1.0, pointer);
    let (first, last) = angles(2.0, Pointer::Stylus);
    assert!(first.is_some_and(|a| a.abs() < 1e-3), "{first:?}");
    assert!(last.is_some_and(|a| (a - quarter).abs() < 0.05), "{last:?}");
    // not: as the barrel is turned from its zero, the turn it was held at from the first stamp
    let (first, last) = angles_from(2.0, 0.0, Pointer::Stylus);
    assert!(first.is_some_and(|a| (a - 1.0).abs() < 1e-3), "{first:?}");
    assert!(last.is_some_and(|a| (a - 1.0 - quarter).abs() < 0.05), "{last:?}");
    assert_eq!(angles_from(2.0, 0.0, Pointer::Finger), (Some(0.0), Some(0.0)));
    // the azimuth alone, or a finger: no turn
    assert_eq!(angles(1.0, Pointer::Stylus), (Some(0.0), Some(0.0)));
    assert_eq!(angles(2.0, Pointer::Finger), (Some(0.0), Some(0.0)));
}

#[test]
fn preview_does_not_change_the_stroke() {
    let mut b = StrokeBuilder::new(plain(), 10.0, Pointer::Stylus);
    b.add(&line(10, 50.0));
    let before = b.stamp_count();
    let ahead = b.preview(&[Sample::at(Vec2::new(80.0, 50.0), 1.0, 100.0)]);
    assert!(!ahead.is_empty());
    assert_eq!(b.stamp_count(), before);
}

#[test]
fn motion_filtering_is_a_low_pass_that_moves_nothing_along() {
    let weights = motion_weights(0.25, 0.0, PEN_RATE);
    assert_eq!(weights.len() % 2, 1);
    assert!((weights.iter().sum::<f32>() - 1.0).abs() < 1e-4);
    assert!(weights.iter().zip(weights.iter().rev()).all(|(a, b)| (a - b).abs() < 1e-6));
    assert!(motion_weights(0.75, 0.0, PEN_RATE).len() > weights.len());
    assert!(motion_weights(0.25, 1.0, PEN_RATE).len() > weights.len());
    assert!(motion_weights(1.0, 1.0, PEN_RATE).len() <= MOTION_MOST_SAMPLES + 1);
    // how much of a wave at `hertz` comes through
    let through = |hertz: f64| {
        let reach = (weights.len() / 2) as f64;
        let at = |(n, w): (usize, &f32)| f64::from(*w) * (TAU * hertz * (n as f64 - reach) / PEN_RATE).cos();
        weights.iter().enumerate().map(at).sum::<f64>()
    };
    assert!(through(1.0) > 0.9, "{}", through(1.0));
    assert!(through(30.0).abs() < 0.02, "{}", through(30.0));
    // a slow circle keeps its size, where following behind the pen would cut inside it; both
    // ends stay where the input's are
    let circle: Vec<Sample> = (0..=480)
        .map(|i| {
            let t = f64::from(i) / PEN_RATE;
            let (y, x) = (TAU * 0.5 * t).sin_cos();
            Sample::at(Vec2::new(300.0 + 200.0 * x as f32, 300.0 + 200.0 * y as f32), 1.0, t * 1000.0)
        })
        .collect();
    let mut b = plain();
    b.set_setting("motion_filtering", 0.25);
    let mut s = StrokeBuilder::new(b, 4.0, Pointer::Stylus);
    s.add(&circle);
    let stamps = s.finish();
    let centre = Vec2::new(300.0, 300.0);
    let third = stamps.len() / 3;
    for stamp in stamps.iter().skip(third).take(third) {
        let off = stamp.center.distance(centre) - 200.0;
        assert!(off.abs() < 3.0, "{off}");
    }
    assert!(stamps[0].center.distance(circle[0].pos) < 3.0, "{:?}", stamps[0].center);
}

#[test]
fn the_apps_input_settings_smooth_and_curve_pressure() {
    let brush = Brush::round_pen();
    let samples: Vec<Sample> = (0..120)
        .map(|i| {
            let x = i as f32 * 4.0;
            // a shake up and down every other point
            let y = if i % 2 == 0 { 0.0 } else { 6.0 };
            let mut s = Sample::at(Vec2::new(x, y), 0.5, f64::from(i) * 8.0);
            s.tilt = 0.0;
            s
        })
        .collect();
    // a line waving at `hertz`: how far it waves in the middle of the stroke
    let spread = |hertz: f64, input: InputSettings| {
        let wave: Vec<Sample> = (0..=480)
            .map(|i| {
                let t = f64::from(i) / PEN_RATE;
                let y = 6.0 * (TAU * hertz * t).sin() as f32;
                let mut s = Sample::at(Vec2::new(i as f32 * 2.0, y), 0.5, t * 1000.0);
                s.tilt = 0.0;
                s
            })
            .collect();
        let mut b = StrokeBuilder::new(brush.clone(), 4.0, Pointer::Stylus).with_input(input);
        b.add(&wave);
        let stamps = b.finish();
        let third = stamps.len() / 3;
        let ys: Vec<f32> = stamps.iter().skip(third).take(third).map(|s| s.center.y).collect();
        ys.iter().cloned().fold(f32::MIN, f32::max) - ys.iter().cloned().fold(f32::MAX, f32::min)
    };
    let filtering = InputSettings { motion_filtering: 0.1, ..InputSettings::default() };
    let expressive = InputSettings { motion_expression: 1.0, ..filtering.clone() };
    let (plain, filtered) = (spread(30.0, InputSettings::default()), spread(30.0, filtering.clone()));
    assert!(filtered < plain * 0.1, "{filtered} vs {plain}");
    // the hand's own motion, under the cut, comes through more with expression
    let (kept, more) = (spread(4.0, filtering), spread(4.0, expressive));
    assert!(more > kept, "{more} vs {kept}");
    // a curve that doubles light pressure makes bigger stamps
    let curved = InputSettings {
        pressure_curve: Curve::new(&[Vec2::new(0.0, 0.0), Vec2::new(0.5, 1.0), Vec2::new(1.0, 1.0)]),
        ..InputSettings::default()
    };
    let mut a = StrokeBuilder::new(brush.clone(), 20.0, Pointer::Stylus);
    let mut b = StrokeBuilder::new(brush, 20.0, Pointer::Stylus).with_input(curved);
    let (sa, sb) = (a.add(&samples[..1]), b.add(&samples[..1]));
    assert!(sb[0].radius >= sa[0].radius, "{} vs {}", sb[0].radius, sa[0].radius);
}

#[test]
fn a_moving_grain_rolls_as_far_as_its_movement_says_and_may_start_anywhere() {
    let offsets = |movement: f32, jitter: f32, seed: u32| {
        let mut brush = Brush::round_pen();
        brush.set_setting("grain_movement", movement);
        brush.set_setting("grain_offset_jitter", jitter);
        let mut b = StrokeBuilder::with_seed(brush, 4.0, Pointer::Stylus, seed);
        let mut stamps = b.add(&line(20, 100.0));
        stamps.extend(b.finish());
        stamps.iter().map(|s| s.grain_offset).collect::<Vec<_>>()
    };
    // rolling: the grain moves with the stroke; static: it stays put under the tip
    let rolling = offsets(1.0, 0.0, 7);
    let first = rolling.first().copied().unwrap_or(Vec2::ZERO);
    let last = rolling.last().copied().unwrap_or(Vec2::ZERO);
    assert!(last.x - first.x > 50.0, "{first:?} {last:?}");
    assert!(offsets(0.0, 0.0, 7).iter().all(|o| *o == Vec2::ZERO));
    // offset jitter starts each stroke somewhere else in the grain
    let a = offsets(0.0, 1.0, 7)[0];
    let b = offsets(0.0, 1.0, 8)[0];
    assert_ne!(a, b);
    // (a tile's worth at most; the stamps are 4 px, the grain following them by default)
    assert!(a.x >= 0.0 && a.x <= 8.0 && a.y >= 0.0 && a.y <= 8.0, "{a:?}");
}

#[test]
fn a_grain_s_patch_takes_the_rotation_s_share_of_the_stamp_s_turn() {
    use std::f32::consts::{FRAC_PI_2, FRAC_PI_4};
    let turn = |rotation: f32, follow: f32, angle: f32| {
        let mut b = plain();
        b.set_setting("grain_rotation", rotation);
        b.set_setting("shape_rotation", follow);
        b.shape.rotation = angle;
        let mut s = StrokeBuilder::new(b, 10.0, Pointer::Finger);
        // down the screen
        let down: Vec<Sample> =
            (0..20).map(|i| Sample::at(Vec2::new(50.0, i as f32 * 8.0), 1.0, f64::from(i) * 8.0)).collect();
        s.add(&down);
        s.finish()[5].grain_turn
    };
    // none of it: the grain holds still whatever the stamp does
    assert_eq!(turn(0.0, 1.0, 0.3), 0.0);
    // a stamp that holds still still counts from its eighth of a turn
    assert!((turn(1.0, 0.0, 0.0) - FRAC_PI_4).abs() < 1e-5);
    assert!((turn(-1.0, 0.0, 0.0) + FRAC_PI_4).abs() < 1e-5);
    assert!((turn(0.5, 0.0, 0.2) - 0.5 * (FRAC_PI_4 + 0.2)).abs() < 1e-5);
    // following the stroke, it goes round with the stamp
    assert!((turn(1.0, 1.0, 0.0) - (FRAC_PI_4 + FRAC_PI_2)).abs() < 1e-4);
    // a brush kept to the screen keeps its grain to it, read and all
    let mut b = plain();
    b.set_setting("orient_to_screen", 1.0);
    b.set_setting("grain_movement", 1.0);
    let turned = |view: f32| {
        let mut s = StrokeBuilder::new(b.clone(), 10.0, Pointer::Finger).on_view_turned(view);
        s.add(&line(10, 100.0));
        let all = s.finish();
        (all[0].grain_turn, all[0].grain_offset, all.last().unwrap().grain_offset)
    };
    let (patch, first, last) = turned(FRAC_PI_2);
    assert!((patch - FRAC_PI_2).abs() < 1e-5);
    // along x on the canvas, down the screen
    let went = last - first;
    assert!(went.x.abs() < 1e-2 && went.y > 1.0, "{went:?}");
}

#[test]
fn a_rolling_grain_goes_on_from_stamp_to_stamp_whatever_its_tile_does() {
    // a tile that follows a stamp whose size follows the pressure, far from the canvas's corner
    let mut brush = Brush { pressure_size: 1.0, ..plain() };
    brush.set_setting("grain_movement", 1.0);
    brush.set_setting("grain_zoom", 0.0);
    brush.grain.scale = crate::studio::MAX_GRAIN_SCALE / 4.0;
    let scale = brush.grain.scale;
    let samples: Vec<Sample> = (0..=200)
        .map(|i| {
            let pressure = 0.6 + 0.4 * (i as f32 / 12.0).sin();
            Sample::at(Vec2::new(5000.0 + i as f32 * 2.0, 3000.0), pressure, i as f64 * 8.0)
        })
        .collect();
    let mut stroke = StrokeBuilder::new(brush, 30.0, Pointer::Stylus);
    stroke.add(&samples);
    let stamps = stroke.finish();
    // each stamp's place in the grain, in tiles, is the last's and what it moved over its tile:
    // however much the tile changes it never jumps
    let reads: Vec<(Vec2, Vec2)> = stamps
        .iter()
        .map(|s| {
            let tile = s.grain_size * scale;
            (s.grain_offset * (1.0 / tile), s.center)
        })
        .collect();
    for pair in stamps.windows(2).zip(reads.windows(2)) {
        let (s, r) = pair;
        let moved = s[1].center.distance(s[0].center) / (s[1].grain_size * scale);
        let went = r[1].0.distance(r[0].0);
        assert!((went - moved).abs() < 1e-3, "{went} {moved}");
    }
    assert!(stamps[10].grain_size != stamps[40].grain_size);
}
