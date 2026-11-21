use super::*;
use procraft_engine::geom::Vec2;

fn at(x: f32, y: f32, ms: f64) -> Vec<PenSample> {
    vec![PenSample { x, y, pressure: 1.0, tilt: 0.0, orientation: 0.0, time_ms: ms, roll: 0.0 }]
}

fn session() -> std::sync::Arc<CanvasSession> {
    CanvasSession::new(64, 64)
}

/// What the touches cost a pen stroke's events, the router and the stroke's own work (no GPU):
/// run on a device with `--ignored --nocapture`.
#[test]
#[ignore]
fn touch_cost() {
    let s = CanvasSession::new(2048, 2048);
    let sample = |i: usize, ms: f64| {
        let u = i as f32 * 0.5;
        PenSample {
            x: 100.0 + u % 1800.0,
            y: 600.0 + (u * 0.02).sin() * 400.0,
            pressure: 0.3 + 0.6 * (u * 0.01).sin().abs(),
            tilt: 0.4,
            orientation: 1.0,
            time_ms: ms,
            roll: 0.0,
        }
    };
    s.touch_down(1, PointerKind::Stylus, vec![sample(0, 0.0)]);
    // a 240 Hz pen read at 120 frames a second: two samples an event
    let mut took: Vec<f64> = (1..=4000)
        .map(|k| {
            let samples = vec![sample(2 * k - 1, k as f64 * 8.33 - 4.0), sample(2 * k, k as f64 * 8.33)];
            let start = std::time::Instant::now();
            s.touch_move(1, samples);
            start.elapsed().as_secs_f64() * 1000.0
        })
        .collect();
    let start = std::time::Instant::now();
    s.touch_up(1, vec![sample(8001, 4000.0 * 8.33 + 4.0)]);
    let up = start.elapsed().as_secs_f64() * 1000.0;
    let (first, last) = (took[..400].iter().sum::<f64>() / 400.0, took[3600..].iter().sum::<f64>() / 400.0);
    took.sort_by(f64::total_cmp);
    let at = |q: f64| took[((took.len() - 1) as f64 * q) as usize];
    println!(
        "pen event ms: median {:.4}, 90th {:.4}, 99th {:.4}, most {:.4}; first 400 {first:.4}, last 400 {last:.4}; up {up:.4}",
        at(0.5),
        at(0.9),
        at(0.99),
        at(1.0)
    );
}

#[test]
fn two_fingers_tapped_undo_and_three_redo() {
    let s = session();
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, 10.0));
    // as the first lifts
    let out = s.touch_up(2, at(300.0, 100.0, 100.0));
    assert!(out.contains(&TouchCommand::Undo), "{out:?}");
    assert!(!s.touch_up(1, at(100.0, 100.0, 110.0)).contains(&TouchCommand::Undo));

    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 1000.0));
    s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, 1005.0));
    s.touch_down(3, PointerKind::Finger, at(500.0, 100.0, 1010.0));
    let mut out = s.touch_up(3, at(500.0, 100.0, 1100.0));
    assert!(out.contains(&TouchCommand::Redo), "{out:?}");
    out.extend(s.touch_up(2, at(300.0, 100.0, 1100.0)));
    out.extend(s.touch_up(1, at(100.0, 100.0, 1110.0)));
    assert!(redid_once(&out), "{out:?}");
}

#[test]
fn four_fingers_tapped_do_what_four_finger_tap_is_set_to() {
    let s = session();
    let mut out = Vec::new();
    for i in 0..4u8 {
        out.extend(s.touch_down(
            u64::from(i),
            PointerKind::Finger,
            at(100.0 + 80.0 * f32::from(i), 300.0, 10.0 * f64::from(i)),
        ));
    }
    for i in 0..4u8 {
        out.extend(s.touch_up(u64::from(i), at(100.0 + 80.0 * f32::from(i), 300.0, 120.0 + 10.0 * f64::from(i))));
    }
    assert_eq!(out.iter().filter(|c| **c == TouchCommand::ToggleBars).count(), 1, "{out:?}");
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::Undo | TouchCommand::Redo)), "{out:?}");
}

#[test]
fn the_first_lift_decides_and_the_fingers_left_do_nothing_more() {
    let s = shown(1.0);
    // the first lifts in time: the redo is done there, though the others then wander off,
    // and a fourth landing meanwhile changes nothing
    s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(200.0, 300.0, 20.0));
    s.touch_down(3, PointerKind::Finger, at(300.0, 300.0, 40.0));
    let view = s.view_matrix();
    let mut out = s.touch_up(1, at(100.0, 300.0, 140.0));
    assert!(redid_once(&out), "{out:?}");
    for k in 1..=10 {
        let d = 12.0 * k as f32;
        out.extend(s.touch_moves(vec![
            TouchMove { id: 2, samples: at(200.0 - d, 300.0, 140.0 + 8.0 * f64::from(k)) },
            TouchMove { id: 3, samples: at(300.0 + d, 300.0 + d, 140.0 + 8.0 * f64::from(k)) },
        ]));
    }
    out.extend(s.touch_down(4, PointerKind::Finger, at(500.0, 300.0, 240.0)));
    for id in [2, 4, 3] {
        out.extend(s.touch_up(id, at(500.0, 300.0, 300.0)));
    }
    assert!(redid_once(&out), "{out:?}");
    assert!(!out.contains(&TouchCommand::ViewMoved), "{out:?}");
    assert_eq!(s.view_matrix(), view);
    // the next touch is a touch again
    s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 1000.0));
    s.touch_down(2, PointerKind::Finger, at(200.0, 300.0, 1010.0));
    assert!(s.touch_up(1, at(100.0, 300.0, 1100.0)).contains(&TouchCommand::Undo));
}

#[test]
fn fingers_held_until_they_repeat_are_no_tap_as_they_lift() {
    let s = session();
    // repeating from 100 ms on, then lifted well within a tap's time: the repeat's undo only
    s.set_touch_settings(TouchSettings { rapid_undo_ms: 100.0, ..TouchSettings::default() });
    let mut out = s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 0.0));
    out.extend(s.touch_down(2, PointerKind::Finger, at(200.0, 300.0, 10.0)));
    out.extend(s.touch_tick(110.0));
    out.extend(s.touch_up(1, at(100.0, 300.0, 150.0)));
    out.extend(s.touch_up(2, at(200.0, 300.0, 160.0)));
    assert_eq!(out.iter().filter(|c| **c == TouchCommand::Undo).count(), 1, "{out:?}");
}

#[test]
fn with_undo_taps_off_they_still_undo_in_a_transform_or_the_selection_tool() {
    let s = session();
    s.set_touch_settings(TouchSettings { undo_taps: false, ..TouchSettings::default() });
    let tap = |s: &CanvasSession, ms: f64| {
        let mut out = s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, ms));
        out.extend(s.touch_down(2, PointerKind::Finger, at(200.0, 300.0, ms + 10.0)));
        out.extend(s.touch_up(1, at(100.0, 300.0, ms + 100.0)));
        out.extend(s.touch_up(2, at(200.0, 300.0, ms + 110.0)));
        out.contains(&TouchCommand::Undo)
    };
    assert!(!tap(&s, 0.0));
    let how = SelectTouch { kind: SelectKind::Freehand, join: crate::SelectionJoin::Add, threshold: 0.5 };
    s.set_touch_selecting(Some(how));
    assert!(tap(&s, 1000.0));
    s.set_touch_selecting(None);
    s.set_touch_transforming(Some(TransformTouch { advanced_mesh: false }));
    assert!(tap(&s, 2000.0));
    s.set_touch_transforming(None);
    assert!(!tap(&s, 3000.0));
}

/// Two fingers tapped on a screen that reports their wobble: each comes down, wanders `wobble`
/// pixels either way about where it landed at `every_ms`, and lifts. Times: the second lands
/// at `landed`, the first lifts at `lifted` and the second at `gone`.
fn wobbling_tap(s: &CanvasSession, wobble: f32, every_ms: f64, landed: f64, lifted: f64, gone: f64) -> bool {
    let (a, b) = ((200.0, 300.0), (260.0, 310.0));
    let shaken = |p: (f32, f32), k: usize| {
        let (u, v) = ((k as f32 * 2.4).sin(), (k as f32 * 3.7).cos());
        (p.0 + wobble * u, p.1 + wobble * v)
    };
    let mut out = s.touch_down(1, PointerKind::Finger, at(a.0, a.1, 0.0));
    let mut ms = every_ms;
    let mut k = 0;
    while ms < gone {
        k += 1;
        if ms >= landed && !s.touches().down.iter().any(|d| d.id == 2) {
            out.extend(s.touch_down(2, PointerKind::Finger, at(b.0, b.1, ms)));
        }
        if ms >= lifted && s.touches().down.iter().any(|d| d.id == 1) {
            out.extend(s.touch_up(1, at(a.0, a.1, ms)));
        }
        let p = shaken(a, k);
        out.extend(s.touch_move(1, at(p.0, p.1, ms)));
        let p = shaken(b, k + 1);
        out.extend(s.touch_move(2, at(p.0, p.1, ms + every_ms / 2.0)));
        ms += every_ms;
    }
    out.extend(s.touch_up(2, at(b.0, b.1, gone)));
    out.contains(&TouchCommand::Undo)
}

#[test]
fn two_fingers_tapped_on_a_screen_that_wobbles_undo() {
    let s = session();
    // a laptop's touch screen: a point a pixel, every wobble of the fingers reported 120 times
    // a second; none has gone more than a pixel or two from where it landed
    s.set_display_scale(1.0);
    for wobble in [0.5, 1.0, 2.0] {
        assert!(wobbling_tap(&s, wobble, 8.0, 30.0, 150.0, 190.0), "{wobble} px");
    }
}

#[test]
fn two_fingers_tapped_loosely_undo_as_long_as_they_were_together_a_tap_s_time() {
    let s = session();
    // down over 80 ms and up over 110: 340 ms in all, 150 of them together
    assert!(wobbling_tap(&s, 0.0, 8.0, 80.0, 230.0, 340.0));
    // together longer than a tap is a hold, not a tap
    assert!(!wobbling_tap(&s, 0.0, 8.0, 30.0, 380.0, 400.0));
    // the first lift decides: one finger left down long after the other is a tap still
    assert!(wobbling_tap(&s, 0.0, 8.0, 30.0, 150.0, 1000.0));
    // nor does it matter how long one was down before the other, as measured
    assert!(wobbling_tap(&s, 0.0, 8.0, 5000.0, 5100.0, 5110.0));
}

#[test]
fn fingers_that_went_somewhere_are_no_tap() {
    let s = session();
    s.set_display_scale(1.0);
    // two fingers slid together past the tap's reach, quickly: the canvas moved, no undo
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(200.0, 100.0, 10.0));
    for k in 1..=5 {
        let d = k as f32 * 5.0;
        s.touch_move(1, at(100.0 + d, 100.0, 10.0 + k as f64 * 8.0));
        s.touch_move(2, at(200.0 + d, 100.0, 14.0 + k as f64 * 8.0));
    }
    let mut out = s.touch_up(1, at(125.0, 100.0, 60.0));
    out.extend(s.touch_up(2, at(225.0, 100.0, 70.0)));
    assert!(!out.contains(&TouchCommand::Undo), "{out:?}");
    // nor fingers gone exactly the tap's reach, where they begin to move the canvas, together
    // or pinched
    for dx in [15.0, -15.0] {
        s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 700.0));
        s.touch_down(2, PointerKind::Finger, at(200.0, 100.0, 710.0));
        s.touch_move(1, at(100.0 + dx, 100.0, 730.0));
        s.touch_move(2, at(200.0 + dx.abs(), 100.0, 734.0));
        let mut out = s.touch_up(1, at(100.0 + dx, 100.0, 760.0));
        out.extend(s.touch_up(2, at(200.0 + dx.abs(), 100.0, 770.0)));
        assert!(!out.contains(&TouchCommand::Undo), "{dx}: {out:?}");
    }
    // nor one that had moved before the other landed: a stroke, taken back by a second finger
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 1000.0));
    s.touch_move(1, at(140.0, 100.0, 1050.0));
    s.touch_down(2, PointerKind::Finger, at(200.0, 100.0, 1060.0));
    let mut out = s.touch_up(2, at(200.0, 100.0, 1100.0));
    out.extend(s.touch_up(1, at(140.0, 100.0, 1110.0)));
    assert!(!out.contains(&TouchCommand::Undo), "{out:?}");
}

#[test]
fn fingers_pinched_or_turned_a_little_are_still_a_tap() {
    let s = session();
    s.set_display_scale(1.0);
    // each short of the tap's reach by itself, though they part by more: a tap, as each
    // finger alone decides
    for (dx, dy) in [(-8.0, 0.0), (0.0, 8.0), (10.0, 0.0)] {
        s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 500.0));
        s.touch_down(2, PointerKind::Finger, at(200.0, 100.0, 510.0));
        s.touch_move(1, at(100.0 + dx, 100.0 + dy, 530.0));
        s.touch_move(2, at(200.0 - dx, 100.0 - dy, 534.0));
        let mut out = s.touch_up(1, at(100.0 + dx, 100.0 + dy, 560.0));
        out.extend(s.touch_up(2, at(200.0 - dx, 100.0 - dy, 570.0)));
        assert!(out.contains(&TouchCommand::Undo), "{dx} {dy}: {out:?}");
    }
    // three, spreading a little each its own way
    let fingers: Vec<Finger> = (0..3)
        .map(|i| Finger { lands: 20.0 * f64::from(i), lifts: 200.0, drift: 9.0, towards: 2.1 * i as f32 })
        .collect();
    assert!(redid_once(&real_tap(&s, &fingers, 1.5, 8.0, true)));
}

#[test]
fn two_fingers_held_on_a_screen_that_wobbles_keep_undoing() {
    let s = session();
    s.set_display_scale(1.0);
    s.touch_down(1, PointerKind::Finger, at(200.0, 300.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(260.0, 310.0, 20.0));
    for k in 0..80 {
        let (u, v) = ((k as f32 * 2.4).sin(), (k as f32 * 3.7).cos());
        s.touch_move(1, at(200.0 + u, 300.0 + v, 20.0 + k as f64 * 8.0));
        s.touch_move(2, at(260.0 + v, 310.0 + u, 24.0 + k as f64 * 8.0));
    }
    let due = s.touch_deadline().unwrap_or(f64::MAX);
    assert!(s.touch_tick(due).contains(&TouchCommand::Undo));
}

/// The pen as if it had left long enough ago that fingers are fingers again.
fn pen_long_gone(s: &CanvasSession) {
    let mut t = s.touches();
    t.pen_left = None;
    t.pen_lifted_ms = None;
}

#[test]
fn a_hand_by_the_pen_does_nothing_and_fingers_together_still_undo() {
    let s = session();
    s.set_touch_pen_hovering(true);
    // a hand landing alone while the pen hovers: nothing, moved or lifted
    assert!(s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 0.0)).is_empty());
    assert!(s.touch_move(1, at(160.0, 320.0, 30.0)).is_empty());
    assert!(!painting(&s));
    // more of the hand landing later: nothing either
    assert!(s.touch_down(3, PointerKind::Finger, at(200.0, 300.0, 300.0)).is_empty());
    assert!(s.touch_up(3, at(200.0, 300.0, 350.0)).is_empty());
    assert!(s.touch_up(1, at(160.0, 320.0, 400.0)).is_empty());
    // two fingers tapped together: undo, as ever
    assert!(s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 1000.0)).is_empty());
    s.touch_down(2, PointerKind::Finger, at(200.0, 100.0, 1040.0));
    let mut out = s.touch_up(2, at(200.0, 100.0, 1100.0));
    out.extend(s.touch_up(1, at(100.0, 100.0, 1110.0)));
    assert!(out.contains(&TouchCommand::Undo), "{out:?}");
    // the pen drawing: a finger landing does nothing, the stroke goes on
    s.touch_down(5, PointerKind::Stylus, at(300.0, 100.0, 2000.0));
    assert!(s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 2010.0)).is_empty());
    s.touch_move(5, at(340.0, 120.0, 2030.0));
    assert!(s.touch_up(5, at(360.0, 130.0, 2050.0)).contains(&TouchCommand::StrokeEnded));
    assert!(s.touch_up(1, at(100.0, 300.0, 2060.0)).is_empty());
    // just lifted (and no hover told): a finger alone is still the hand's
    s.set_touch_pen_hovering(false);
    assert!(s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 2100.0)).is_empty());
    assert!(s.touch_up(1, at(100.0, 300.0, 2150.0)).is_empty());
    // gone a while: a finger paints
    pen_long_gone(&s);
    s.touch_down(1, PointerKind::Finger, at(10.0, 10.0, 3000.0));
    s.touch_move(1, at(60.0, 10.0, 3020.0));
    assert!(s.touch_up(1, at(60.0, 10.0, 3040.0)).contains(&TouchCommand::StrokeEnded));
}

#[test]
fn two_fingers_tapped_as_the_pen_s_time_runs_out_still_undo() {
    let s = session();
    s.touch_down(5, PointerKind::Stylus, at(300.0, 100.0, 0.0));
    s.touch_move(5, at(340.0, 120.0, 20.0));
    s.touch_up(5, at(360.0, 130.0, 50.0));
    // the first finger lands while the pen counts as near, the second together with it just
    // after that has run out
    let gone = 50.0 + f64::from(PEN_GONE_S) * 1000.0;
    let mut out = s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, gone - 40.0));
    out.extend(s.touch_down(2, PointerKind::Finger, at(200.0, 100.0, gone + 40.0)));
    out.extend(s.touch_up(2, at(200.0, 100.0, gone + 100.0)));
    out.extend(s.touch_up(1, at(100.0, 100.0, gone + 110.0)));
    assert!(out.contains(&TouchCommand::Undo), "{out:?}");
    // one landing well after a finger left waiting: that one was the hand's, this one paints
    let s = session();
    s.touch_down(5, PointerKind::Stylus, at(300.0, 100.0, 0.0));
    s.touch_up(5, at(360.0, 130.0, 50.0));
    assert!(s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, gone - 40.0)).is_empty());
    s.touch_down(2, PointerKind::Finger, at(10.0, 10.0, gone + 200.0));
    s.touch_move(2, at(60.0, 10.0, gone + 220.0));
    assert!(s.touch_up(2, at(60.0, 10.0, gone + 240.0)).contains(&TouchCommand::StrokeEnded));
    assert!(s.touch_up(1, at(100.0, 300.0, gone + 260.0)).is_empty());
}

#[test]
fn a_hand_down_when_the_pen_comes_near_is_set_aside() {
    let s = session();
    // a palm painting before the pen is seen
    s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 0.0));
    s.touch_move(1, at(140.0, 320.0, 20.0));
    assert!(painting(&s));
    // the pen held over the canvas: the palm's stroke is taken back and it does nothing more
    let out = s.set_touch_pen_hovering(true);
    assert!(out.contains(&TouchCommand::CursorGone), "{out:?}");
    assert!(!painting(&s));
    assert!(s.touch_move(1, at(180.0, 340.0, 40.0)).is_empty());
    assert!(s.touch_up(1, at(180.0, 340.0, 60.0)).is_empty());
    // told again as it moves: nothing more to take back
    assert!(s.set_touch_pen_hovering(true).is_empty());
}

#[test]
fn the_pen_landing_on_a_resting_hand_draws_and_the_hand_does_nothing() {
    let s = session();
    // the hand touches first: a finger stroke begins (fingers paint as the pen does)
    s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 0.0));
    s.touch_move(1, at(104.0, 302.0, 20.0));
    // the pen lands: it draws, the hand's stroke taken back
    let out = s.touch_down(2, PointerKind::Stylus, at(300.0, 100.0, 40.0));
    assert!(out.contains(&TouchCommand::Cursor { x: 300.0, y: 100.0 }), "{out:?}");
    assert!(s.touch_move(1, at(140.0, 320.0, 60.0)).is_empty());
    s.touch_move(2, at(340.0, 120.0, 80.0));
    let out = s.touch_up(2, at(360.0, 130.0, 100.0));
    assert!(out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    assert!(s.touch_up(1, at(140.0, 320.0, 120.0)).is_empty());
    // two fingers resting, then the pen tapped: a dot, no undo for the fingers lifted quickly
    s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 1000.0));
    s.touch_down(3, PointerKind::Finger, at(160.0, 300.0, 1005.0));
    s.touch_down(2, PointerKind::Stylus, at(300.0, 100.0, 1010.0));
    let out = s.touch_up(2, at(300.0, 100.0, 1040.0));
    assert!(out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    let mut out = s.touch_up(3, at(160.0, 300.0, 1050.0));
    out.extend(s.touch_up(1, at(100.0, 300.0, 1060.0)));
    assert!(!out.contains(&TouchCommand::Undo), "{out:?}");
}

/// Whether a touch is painting (no GPU here, so no stroke to look at).
fn painting(s: &CanvasSession) -> bool {
    matches!(s.touches().mode, Mode::Pen | Mode::Finger)
}

fn pressed(x: f32, y: f32, pressure: f32, ms: f64) -> PenSample {
    PenSample { x, y, pressure, tilt: 0.0, orientation: 0.0, time_ms: ms, roll: 0.0 }
}

#[test]
fn a_pen_known_to_give_a_pressure_hovers_at_none_until_it_presses() {
    let s = session();
    s.touches().pen_pressure = false;
    // a stroke with a pressure: pens are known to give one from now on
    s.touch_down(1, PointerKind::Stylus, vec![pressed(10.0, 10.0, 0.4, 0.0)]);
    s.touch_up(1, vec![pressed(30.0, 10.0, 0.2, 20.0)]);
    assert!(s.touches().pen_pressure);
    // held near the glass, touching at none: it hovers, its cursor following, and nothing paints
    let out = s.touch_down(2, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 1000.0)]);
    assert_eq!(out, vec![TouchCommand::PenHovers { x: 100.0, y: 100.0 }]);
    assert!(!painting(&s));
    let out = s.touch_move(2, vec![pressed(120.0, 100.0, 0.0, 1010.0)]);
    assert_eq!(out, vec![TouchCommand::PenHovers { x: 120.0, y: 100.0 }]);
    assert!(!painting(&s));
    assert!(s.touches().pen_hovering.is_some());
    // it presses partway through an event: its stroke begins where it pressed and goes on
    let out = s.touch_move(
        2,
        vec![
            pressed(130.0, 100.0, 0.0, 1020.0),
            pressed(140.0, 100.0, 0.3, 1030.0),
            pressed(150.0, 100.0, 0.5, 1040.0),
        ],
    );
    assert!(painting(&s));
    assert_eq!(s.touches().down_at, (140.0, 100.0));
    assert!(out.contains(&TouchCommand::Cursor { x: 140.0, y: 100.0 }), "{out:?}");
    assert!(out.contains(&TouchCommand::Cursor { x: 150.0, y: 100.0 }), "{out:?}");
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::PenHovers { .. })), "{out:?}");
    // back to none as it rises: the stroke goes on, to end (and taper) as it lifts
    s.touch_move(2, vec![pressed(160.0, 100.0, 0.0, 1050.0)]);
    assert!(painting(&s));
    assert!(s.touch_up(2, vec![pressed(165.0, 100.0, 0.0, 1060.0)]).contains(&TouchCommand::StrokeEnded));
    // touching at none and lifting away unpressed: its cursor goes, and no stroke ends
    s.touch_down(3, PointerKind::Stylus, vec![pressed(200.0, 100.0, 0.0, 2000.0)]);
    assert_eq!(s.touch_up(3, vec![pressed(210.0, 100.0, 0.0, 2010.0)]), vec![TouchCommand::CursorGone]);
    let t = s.touches();
    assert!(t.pen_hovering.is_none() && t.pen_unpressed.is_none());
    drop(t);
    // the eraser end tapped, its pressure coming only as it lifts: a dot
    s.touch_down(4, PointerKind::StylusEraser, vec![pressed(250.0, 100.0, 0.0, 3000.0)]);
    assert!(s.touch_up(4, vec![pressed(250.0, 100.0, 0.6, 3010.0)]).contains(&TouchCommand::StrokeEnded));
    // called off while it hovers: its cursor goes
    s.touch_down(5, PointerKind::Stylus, vec![pressed(300.0, 100.0, 0.0, 4000.0)]);
    assert_eq!(s.touch_cancel(), vec![TouchCommand::CursorGone]);
    assert!(s.touches().pen_unpressed.is_none());
}

/// The pressures the stroke being drawn was handed (canvas samples, in order).
fn stroke_pressures(s: &CanvasSession) -> Vec<f32> {
    s.shared.lock().drawn_samples.iter().map(|p| p.pressure).collect()
}

/// A session showing its canvas, with no pen known to give a pressure yet.
fn no_pen_yet() -> std::sync::Arc<CanvasSession> {
    let s = shown(1.0);
    let mut t = s.touches();
    t.pen_pressure = false;
    t.pen_no_pressure = false;
    drop(t);
    s
}

#[test]
fn a_pen_never_known_to_give_a_pressure_waits_a_moment_then_paints_at_full_pressure() {
    let s = no_pen_yet();
    // touching at none: it waits for a pressure, its cursor showing, nothing painted yet
    let out = s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 0.0)]);
    assert_eq!(out, vec![TouchCommand::PenHovers { x: 100.0, y: 100.0 }]);
    assert!(!painting(&s));
    assert_eq!(s.touch_deadline(), Some(PEN_PRESSURE_WAIT_MS));
    let out = s.touch_move(1, vec![pressed(110.0, 100.0, 0.0, 50.0), pressed(120.0, 100.0, 0.0, 100.0)]);
    assert_eq!(out, vec![TouchCommand::PenHovers { x: 120.0, y: 100.0 }]);
    assert!(!painting(&s));
    // none by the wait's end: a pen that gives none; its stroke begins where it touched and
    // takes in all it did meanwhile, at full pressure
    let out = s.touch_move(1, vec![pressed(140.0, 100.0, 0.0, 160.0)]);
    assert!(painting(&s), "{out:?}");
    assert_eq!(s.touches().down_at, (100.0, 100.0));
    assert!(out.contains(&TouchCommand::Cursor { x: 140.0, y: 100.0 }), "{out:?}");
    assert!(s.touches().pen_no_pressure && s.touches().pen_wait_due.is_none());
    let pressures = stroke_pressures(&s);
    assert_eq!(pressures.len(), 4, "{pressures:?}");
    assert!(pressures.iter().all(|p| *p == 1.0), "{pressures:?}");
    // what runs ahead of it too
    assert_eq!(s.pen_predicted(vec![pressed(150.0, 100.0, 0.0, 170.0)])[0].pressure, 1.0);
    assert!(s.touch_up(1, vec![pressed(160.0, 100.0, 0.0, 200.0)]).contains(&TouchCommand::StrokeEnded));
    // its next touch paints as it lands, at full pressure, the eraser end's alike
    s.touch_down(2, PointerKind::StylusEraser, vec![pressed(100.0, 200.0, 0.0, 1000.0)]);
    assert!(painting(&s));
    assert_eq!(stroke_pressures(&s), vec![1.0]);
    s.touch_up(2, vec![pressed(120.0, 200.0, 0.0, 1020.0)]);
    // a pressure at last: pens give one, painted as it comes, and a touch at none hovers again
    s.touch_down(3, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.5, 2000.0)]);
    assert_eq!(stroke_pressures(&s), vec![0.5]);
    s.touch_up(3, vec![pressed(120.0, 100.0, 0.3, 2020.0)]);
    assert!(s.touches().pen_pressure && !s.touches().pen_no_pressure);
    assert_eq!(s.pen_predicted(vec![pressed(0.0, 0.0, 0.0, 0.0)])[0].pressure, 0.0);
    let out = s.touch_down(4, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 3000.0)]);
    assert_eq!(out, vec![TouchCommand::PenHovers { x: 100.0, y: 100.0 }]);
    assert!(!painting(&s));
    assert_eq!(s.touch_deadline(), None);
}

#[test]
fn a_pen_that_gives_no_pressure_held_still_paints_once_its_wait_runs_out() {
    let s = no_pen_yet();
    s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 1000.0)]);
    // the wait not yet out: nothing
    assert!(s.touch_tick(1100.0).is_empty());
    assert!(!painting(&s));
    // out, the pen not having moved: its stroke begins there
    let out = s.touch_tick(1000.0 + PEN_PRESSURE_WAIT_MS);
    assert!(out.contains(&TouchCommand::Cursor { x: 100.0, y: 100.0 }), "{out:?}");
    assert!(painting(&s));
    assert_eq!(stroke_pressures(&s), vec![1.0]);
    s.touch_move(1, vec![pressed(130.0, 100.0, 0.0, 1200.0)]);
    assert_eq!(stroke_pressures(&s), vec![1.0, 1.0]);
    assert!(s.touch_up(1, vec![pressed(140.0, 100.0, 0.0, 1220.0)]).contains(&TouchCommand::StrokeEnded));
}

#[test]
fn a_tap_of_a_pen_that_gives_no_pressure_is_a_dot() {
    let s = no_pen_yet();
    s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 0.0)]);
    // lifted before the wait is out, never pressing: a dot where it touched
    let out = s.touch_up(1, vec![pressed(101.0, 100.0, 0.0, 40.0)]);
    assert!(out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    assert!(s.touches().pen_no_pressure && s.touches().pen_unpressed.is_none());
    // called off while it waits: nothing, and not taken for one that gives none
    let s = no_pen_yet();
    s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 0.0)]);
    assert_eq!(s.touch_cancel(), vec![TouchCommand::CursorGone]);
    let t = s.touches();
    assert!(!t.pen_no_pressure && t.pen_waiting.is_empty() && t.pen_wait_due.is_none());
}

#[test]
fn a_pen_never_known_to_give_a_pressure_pressing_within_the_wait_begins_where_it_pressed() {
    // its first touch began above the glass, at none: it hovers until it presses, as a pen
    // known to give a pressure does
    let s = no_pen_yet();
    s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 0.0)]);
    s.touch_move(1, vec![pressed(110.0, 100.0, 0.0, 40.0), pressed(120.0, 100.0, 0.4, 60.0)]);
    assert!(painting(&s));
    assert_eq!(s.touches().down_at, (120.0, 100.0));
    assert_eq!(stroke_pressures(&s), vec![0.4]);
    let t = s.touches();
    assert!(t.pen_pressure && !t.pen_no_pressure && t.pen_wait_due.is_none() && t.pen_waiting.is_empty());
    drop(t);
    // pressing as it lands: painted as it comes from the first
    let s = no_pen_yet();
    s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.0, 0.0), pressed(105.0, 100.0, 0.2, 5.0)]);
    assert_eq!(s.touches().down_at, (105.0, 100.0));
    assert_eq!(stroke_pressures(&s), vec![0.2]);
}

#[test]
fn a_pen_s_pressure_that_is_no_number_is_none() {
    let s = shown(1.0);
    s.touches().pen_pressure = true;
    // a driver's pressure that is no number: no press, so it hovers, as at none
    let out = s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, f32::NAN, 0.0)]);
    assert_eq!(out, vec![TouchCommand::PenHovers { x: 100.0, y: 100.0 }]);
    // an endless one is full, and what is painted is a number
    s.touch_move(1, vec![pressed(110.0, 100.0, f32::INFINITY, 10.0), pressed(120.0, 100.0, f32::NAN, 20.0)]);
    assert!(painting(&s));
    assert_eq!(stroke_pressures(&s), vec![1.0, 0.0]);
    // from a pen never known to give one, it waits as at none, then paints at full pressure
    let s = no_pen_yet();
    s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, f32::NAN, 0.0)]);
    assert!(!painting(&s));
    s.touch_move(1, vec![pressed(110.0, 100.0, f32::NAN, PEN_PRESSURE_WAIT_MS)]);
    assert_eq!(stroke_pressures(&s), vec![1.0, 1.0]);
}

#[test]
fn a_pen_pressing_as_it_touches_paints_as_ever() {
    // the same stroke from a pen known to give a pressure and from one not yet: the same touches
    let stroke = |known: bool| {
        let s = session();
        s.touches().pen_pressure = known;
        let mut out = s.touch_down(1, PointerKind::Stylus, vec![pressed(100.0, 100.0, 0.2, 0.0)]);
        let began = painting(&s);
        out.extend(s.touch_move(1, vec![pressed(120.0, 100.0, 0.6, 10.0), pressed(130.0, 100.0, 0.0, 15.0)]));
        out.extend(s.touch_move(1, vec![pressed(140.0, 100.0, 0.8, 20.0)]));
        let went_on = painting(&s);
        out.extend(s.touch_up(1, vec![pressed(150.0, 100.0, 0.0, 30.0)]));
        (began && went_on, out)
    };
    let (painted, out) = stroke(true);
    assert!(painted);
    assert!(out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    assert_eq!((painted, out), stroke(false));
    // a finger or the mouse (which give no pressure of their own) paint at none, as ever
    for kind in [PointerKind::Finger, PointerKind::Mouse] {
        let s = session();
        s.touches().pen_pressure = true;
        s.touch_down(1, kind, vec![pressed(100.0, 100.0, 0.0, 0.0)]);
        assert!(painting(&s), "{kind:?}");
    }
}

#[test]
fn a_tap_with_a_panel_open_only_closes_it() {
    let s = session();
    s.set_touch_panel_open(true);
    for kind in [PointerKind::Finger, PointerKind::Stylus, PointerKind::Mouse] {
        assert!(s.touch_down(1, kind, at(100.0, 100.0, 0.0)).is_empty());
        assert!(!painting(&s));
        // moving a little is still a tap
        assert!(s.touch_move(1, at(105.0, 100.0, 20.0)).is_empty());
        let out = s.touch_up(1, at(105.0, 100.0, 40.0));
        assert!(out.contains(&TouchCommand::PanelTapped), "{out:?}");
        assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
        assert!(!painting(&s));
    }
    // the platform calling it off closes nothing
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 100.0));
    assert!(!s.touch_cancel().contains(&TouchCommand::PanelTapped));
}

#[test]
fn a_drag_with_a_panel_open_fades_it_and_paints_from_where_it_came_down() {
    let s = session();
    s.set_touch_panel_open(true);
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    s.touch_move(1, at(115.0, 100.0, 10.0));
    assert!(!painting(&s));
    let out = s.touch_move(1, at(125.0, 100.0, 20.0));
    assert_eq!(out.first(), Some(&TouchCommand::PanelFades), "{out:?}");
    assert!(out.contains(&TouchCommand::Cursor { x: 125.0, y: 100.0 }), "{out:?}");
    assert!(painting(&s));
    // the panel goes only once
    assert!(!s.touch_move(1, at(130.0, 100.0, 30.0)).contains(&TouchCommand::PanelFades));
    let out = s.touch_up(1, at(140.0, 100.0, 40.0));
    assert!(out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    assert!(!out.contains(&TouchCommand::PanelTapped), "{out:?}");
}

#[test]
fn a_finger_held_still_with_a_panel_open_picks_and_leaves_the_panel() {
    let s = session();
    s.set_touch_panel_open(true);
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    assert_eq!(s.touch_deadline(), Some(400.0));
    let out = s.touch_tick(400.0);
    assert!(out.contains(&TouchCommand::Pick { x: 100.0, y: 100.0 }), "{out:?}");
    assert!(!out.contains(&TouchCommand::PanelFades), "{out:?}");
    assert!(!painting(&s));
    let out = s.touch_up(1, at(100.0, 100.0, 500.0));
    assert!(out.contains(&TouchCommand::PickDone), "{out:?}");
    assert!(!out.contains(&TouchCommand::PanelTapped), "{out:?}");
}

#[test]
fn fingers_with_a_panel_open_move_the_canvas_and_leave_the_panel() {
    let s = session();
    s.set_touch_panel_open(true);
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, 10.0));
    let mut out = s.touch_move(2, at(340.0, 100.0, 30.0));
    assert!(out.contains(&TouchCommand::ViewMoved), "{out:?}");
    out.extend(s.touch_up(2, at(340.0, 100.0, 60.0)));
    out.extend(s.touch_up(1, at(100.0, 100.0, 70.0)));
    assert!(!out.contains(&TouchCommand::PanelFades) && !out.contains(&TouchCommand::PanelTapped), "{out:?}");
    assert!(!painting(&s));
}

#[test]
fn a_pen_waiting_for_a_panel_draws_on_past_a_finger_landing() {
    let s = session();
    s.set_touch_panel_open(true);
    s.touch_down(1, PointerKind::Stylus, at(100.0, 100.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, 10.0));
    let out = s.touch_move(1, at(130.0, 100.0, 20.0));
    assert_eq!(out.first(), Some(&TouchCommand::PanelFades), "{out:?}");
    assert!(painting(&s));
    s.touch_cancel();
    // and a pen landing on a finger waiting takes over from it, waiting in its turn
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 100.0));
    s.touch_down(2, PointerKind::Stylus, at(300.0, 100.0, 110.0));
    let out = s.touch_up(2, at(300.0, 100.0, 130.0));
    assert!(out.contains(&TouchCommand::PanelTapped), "{out:?}");
}

#[test]
fn with_finger_painting_off_fingers_only_move_the_canvas() {
    let s = session();
    let settings = TouchSettings { finger_paint: false, one_finger_pan: true, ..TouchSettings::default() };
    s.set_touch_settings(TouchSettings { undo_taps: false, ..settings });
    // one finger
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    assert!(!painting(&s));
    assert!(s.touch_move(1, at(200.0, 100.0, 20.0)).contains(&TouchCommand::ViewMoved));
    assert!(!painting(&s));
    assert!(!s.touch_up(1, at(200.0, 100.0, 40.0)).contains(&TouchCommand::StrokeEnded));
    // two, one after the other and both at once
    for (gap, ms) in [(30.0, 1000.0), (0.0, 2000.0)] {
        s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, ms));
        s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, ms + gap));
        assert!(!painting(&s));
        let moved = s.touch_move(2, at(340.0, 100.0, ms + 60.0));
        assert!(moved.contains(&TouchCommand::ViewMoved), "{moved:?}");
        assert!(!painting(&s));
        let mut out = s.touch_up(2, at(340.0, 100.0, ms + 80.0));
        out.extend(s.touch_up(1, at(100.0, 100.0, ms + 90.0)));
        assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    }
}

#[test]
fn a_mouse_following_touch_does_what_a_finger_does_and_is_never_a_resting_hand() {
    let s = session();
    let off = TouchSettings { finger_paint: false, one_finger_pan: true, ..TouchSettings::default() };
    // a tablet's: with painting with a finger off, the mouse moves the canvas as a finger does
    s.set_touch_settings(TouchSettings { pointer_follows_touch: true, ..off.clone() });
    s.touch_down(1, PointerKind::Mouse, at(100.0, 100.0, 0.0));
    assert!(!painting(&s));
    assert!(s.touch_move(1, at(200.0, 100.0, 20.0)).contains(&TouchCommand::ViewMoved));
    assert!(!s.touch_up(1, at(200.0, 100.0, 40.0)).contains(&TouchCommand::StrokeEnded));
    // painting with a finger on, it paints, at full pressure, the pen just gone
    s.set_touch_settings(TouchSettings { pointer_follows_touch: true, ..TouchSettings::default() });
    s.touch_down(2, PointerKind::Stylus, vec![pressed(10.0, 10.0, 0.5, 100.0)]);
    s.touch_up(2, vec![pressed(10.0, 10.0, 0.5, 120.0)]);
    s.touch_down(1, PointerKind::Mouse, at(100.0, 100.0, 150.0));
    assert!(painting(&s), "a mouse is no hand resting by the pen");
    s.touch_move(1, at(160.0, 100.0, 170.0));
    assert!(s.touch_up(1, at(160.0, 100.0, 190.0)).contains(&TouchCommand::StrokeEnded));
    // a desktop's: it paints as the pen does, whatever a finger does
    s.set_touch_settings(off);
    s.touch_down(1, PointerKind::Mouse, at(100.0, 100.0, 1000.0));
    assert!(painting(&s));
    s.touch_move(1, at(160.0, 100.0, 1020.0));
    assert!(s.touch_up(1, at(160.0, 100.0, 1040.0)).contains(&TouchCommand::StrokeEnded));
}

#[test]
fn a_pointer_the_platform_takes_back_makes_no_tap_and_no_stroke() {
    let s = session();
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, 10.0));
    s.touch_cancel_one(2);
    let out = s.touch_up(1, at(100.0, 100.0, 110.0));
    assert!(!out.contains(&TouchCommand::Undo), "{out:?}");
    // a finger stroke taken back ends without being kept
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 1000.0));
    s.touch_move(1, at(140.0, 100.0, 1020.0));
    let out = s.touch_cancel_one(1);
    assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
}

#[test]
fn the_pen_button_switches_to_the_eraser_and_back_unless_set_otherwise() {
    let s = session();
    s.set_tool(ToolKind::Smudge);
    assert!(s.touch_pen_button(false, 10.0, 10.0).is_empty());
    assert_eq!(s.info().tool, ToolKind::Erase);
    // letting go leaves it: the next press goes back
    assert!(!s.touch_pen_button_up(false));
    assert_eq!(s.info().tool, ToolKind::Erase);
    s.touch_pen_button(false, 10.0, 10.0);
    assert!(!s.touch_pen_button_up(false));
    assert_eq!(s.info().tool, ToolKind::Smudge);
    // the second button, set to nothing, does nothing
    assert!(s.touch_pen_button(true, 10.0, 10.0).is_empty());
    assert_eq!(s.info().tool, ToolKind::Smudge);
    // set to the quick menu, the first opens it where the pen is, the tool left alone
    s.set_touch_settings(TouchSettings { pen_button: Some(GestureAction::QuickMenu), ..TouchSettings::default() });
    assert_eq!(s.touch_pen_button(false, 30.0, 40.0), vec![TouchCommand::QuickMenu { x: 30.0, y: 40.0 }]);
    assert_eq!(s.info().tool, ToolKind::Smudge);
    // set to the eyedropper, the next touch picks
    s.set_touch_settings(TouchSettings {
        pen_second_button: Some(GestureAction::Eyedropper),
        ..TouchSettings::default()
    });
    s.touch_pen_button(true, 0.0, 0.0);
    assert!(s.pick_next());
}

#[test]
fn a_pen_button_set_to_undo_in_pen_buttons_undoes_and_the_other_still_swaps_the_eraser() {
    // one button swapping brush and eraser, the other undoing, as a pen with two buttons is
    // often set up: press, let go, press, hovering and touching
    let s = session();
    s.set_tool(ToolKind::Paint);
    s.set_touch_settings(TouchSettings {
        pen_second_button_does: Some(crate::QuickAction::Undo),
        ..TouchSettings::default()
    });
    for _ in 0..3 {
        assert_eq!(s.touch_pen_button(true, 10.0, 10.0), vec![TouchCommand::Undo]);
        assert!(!s.touch_pen_button_up(true));
    }
    assert_eq!(s.info().tool, ToolKind::Paint);
    assert!(s.touch_pen_button(false, 10.0, 10.0).is_empty());
    assert_eq!(s.info().tool, ToolKind::Erase);
    assert_eq!(s.touch_pen_button(true, 10.0, 10.0), vec![TouchCommand::Undo]);
    assert_eq!(s.info().tool, ToolKind::Erase);
    // set in Pen buttons, the button does that whatever Gesture controls say: redo, no action,
    // or another of the QuickMenu's actions for the app, where the pen is
    s.set_touch_settings(TouchSettings {
        pen_button: Some(GestureAction::Erase),
        pen_button_does: Some(crate::QuickAction::Redo),
        pen_second_button: Some(GestureAction::QuickMenu),
        pen_second_button_does: Some(crate::QuickAction::None),
        ..TouchSettings::default()
    });
    assert_eq!(s.touch_pen_button(false, 10.0, 10.0), vec![TouchCommand::Redo]);
    assert!(!s.touch_pen_button_up(false), "not held to erase");
    assert_eq!(s.info().tool, ToolKind::Erase);
    assert!(s.touch_pen_button(true, 10.0, 10.0).is_empty());
    s.set_touch_settings(TouchSettings {
        pen_button_does: Some(crate::QuickAction::NewLayer),
        ..TouchSettings::default()
    });
    assert_eq!(
        s.touch_pen_button(false, 30.0, 40.0),
        vec![TouchCommand::Run { action: crate::QuickAction::NewLayer, x: 30.0, y: 40.0 }]
    );
    // a pen that double-taps in place of a button: as the first
    s.set_touch_settings(TouchSettings {
        pen_button_does: Some(crate::QuickAction::Undo),
        ..TouchSettings::default()
    });
    assert_eq!(s.touch_pen_double_tap(PenPreferred::SwitchEraser, 5.0, 5.0), vec![TouchCommand::Undo]);
    assert_eq!(s.info().tool, ToolKind::Erase);
}

#[test]
fn two_fingers_held_still_keep_undoing() {
    let s = session();
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, 10.0));
    assert_eq!(s.touch_deadline(), Some(660.0));
    assert_eq!(s.touch_tick(660.0), vec![TouchCommand::Undo]);
    assert_eq!(s.touch_tick(780.0), vec![TouchCommand::Undo]);
}

/// A 400 x 300 canvas in an 800 x 600 window, shown `zoom` times as big as it fits.
fn shown(zoom: f32) -> std::sync::Arc<CanvasSession> {
    let s = CanvasSession::new(400, 300);
    {
        let mut st = s.shared.lock();
        st.window_size = (800, 600);
        let mut view = st.fitted_view();
        view.gesture(Vec2::new(400.0, 300.0), Vec2::new(0.0, 0.0), zoom, 0.0);
        st.view = Some(view);
    }
    s
}

/// Two fingers 600 apart pinched at `speed` (their spread, a part of where it began, a
/// second) for `ms` at 200 Hz, then held still for `still` before they lift: what the lifts
/// gave.
fn pinch(s: &CanvasSession, speed: f32, ms: f64, still: f64) -> Vec<TouchCommand> {
    s.touch_down(1, PointerKind::Finger, at(100.0, 300.0, 0.0));
    s.touch_down(2, PointerKind::Finger, at(700.0, 300.0, 0.0));
    let (mut t, mut half) = (0.0, 300.0);
    while t + 5.0 <= ms {
        t += 5.0;
        half = 300.0 * (1.0 + speed * t as f32 / 1000.0);
        s.touch_move(1, at(400.0 - half, 300.0, t));
        s.touch_move(2, at(400.0 + half, 300.0, t));
    }
    let mut out = s.touch_up(2, at(400.0 + half, 300.0, t + still));
    out.extend(s.touch_up(1, at(400.0 - half, 300.0, t + still + 8.0)));
    out
}

fn zoom(s: &CanvasSession) -> f32 {
    s.shared.lock().view.map_or(0.0, |v| v.zoom())
}

#[test]
fn fingers_moving_in_one_event_never_show_a_spread_they_didn_t_have() {
    let s = shown(1.0);
    let before = zoom(&s);
    let (a, b) = ((200.0, 300.0), (400.0, 300.0));
    s.touch_down(1, PointerKind::Finger, at(a.0, a.1, 0.0));
    s.touch_down(2, PointerKind::Finger, at(b.0, b.1, 5.0));
    // panned: both go the same way, so the zoom never moves
    for i in 1..=20 {
        let (x, ms) = (i as f32 * 6.0, f64::from(i) * 8.0 + 400.0);
        let step = |id: u64, p: (f32, f32)| TouchMove { id, samples: at(p.0 + x, p.1, ms) };
        s.touch_moves(vec![step(1, a), step(2, b)]);
        assert!((zoom(&s) / before - 1.0).abs() < 1e-5, "{} at {i}", zoom(&s));
    }
    assert!(!s.touches().more_to_come);
    // an event's last pointer may be one no longer followed: the view still goes by the others
    let zoomed = zoom(&s);
    let step = |id: u64, x: f32, ms: f64| TouchMove { id, samples: at(x, 300.0, ms) };
    s.touch_moves(vec![step(1, 200.0, 700.0), step(2, 520.0, 700.0), step(9, 0.0, 700.0)]);
    assert!(zoom(&s) > zoomed * 1.1, "{} from {zoomed}", zoom(&s));
}

#[test]
fn fingers_leave_the_view_as_it_is_until_they_have_gone_a_little_way() {
    let s = shown(1.0);
    let before = zoom(&s);
    let (a, b) = ((200.0, 300.0), (400.0, 300.0));
    s.touch_down(1, PointerKind::Finger, at(a.0, a.1, 0.0));
    s.touch_down(2, PointerKind::Finger, at(b.0, b.1, 5.0));
    // as measured: each 4 out leaves it, each 6 moves it
    s.touch_move(1, at(a.0 - 4.0, a.1, 400.0));
    s.touch_move(2, at(b.0 + 4.0, b.1, 405.0));
    assert_eq!(zoom(&s), before);
    s.touch_move(1, at(a.0 - 6.0, a.1, 450.0));
    s.touch_move(2, at(b.0 + 6.0, b.1, 455.0));
    // on from there, so with no jump: only what came after the first step past it
    let started = zoom(&s);
    assert!(started >= before && started < before * 1.02, "{started} from {before}");
    s.touch_move(1, at(a.0 - 56.0, a.1, 500.0));
    s.touch_move(2, at(b.0 + 56.0, b.1, 505.0));
    assert!(zoom(&s) > started * 1.3, "{}", zoom(&s));
}

#[test]
fn a_quick_pinch_in_fits_the_canvas() {
    let s = shown(1.0);
    let fitted = zoom(&s);
    let out = pinch(&s, -5.0, 120.0, 0.0);
    assert!(zoom(&s) < fitted * 0.5);
    assert!(out.contains(&TouchCommand::Fit), "{out:?}");
    // from fitted, a small flick in fits it again
    let s = shown(1.0);
    let out = pinch(&s, -6.9, 32.0, 0.0);
    assert!(out.contains(&TouchCommand::Fit), "{out:?}");
    // closing a little faster than 2.5 a second fits, a little slower doesn't
    assert!(pinch(&shown(1.0), -2.8, 150.0, 0.0).contains(&TouchCommand::Fit));
    assert!(!pinch(&shown(1.0), -2.2, 150.0, 0.0).contains(&TouchCommand::Fit));
}

/// A list with a plugin on the gesture lock's hook that locks part `part` of the view.
fn locking(part: i32) -> std::sync::Arc<crate::hooks::Hooks> {
    let code = format!(
        r#"(module (func (export "procraft_gesture_lock") (param i32) (result i32)
            (i32.eq (local.get 0) (i32.const {part}))))"#
    );
    let running = crate::plugin_run::Running::start(&wat::parse_str(code).unwrap(), &[]).unwrap();
    let hooks = std::sync::Arc::new(crate::hooks::Hooks::default());
    hooks.put_on("org.example.lock".to_owned(), running, &[crate::plugin_run::Hook::GestureLock]);
    hooks
}

#[test]
fn a_quick_pinch_in_zooms_as_any_pinch_while_a_plugin_keeps_the_fit() {
    let s = shown(1.0);
    s.shared.lock().hooks = Some(locking(crate::hooks::GESTURE_FIT));
    let fitted = zoom(&s);
    let out = pinch(&s, -5.0, 120.0, 0.0);
    assert!(!out.contains(&TouchCommand::Fit), "{out:?}");
    assert!(zoom(&s) < fitted * 0.5, "zoomed out as the fingers went: {} from {fitted}", zoom(&s));
    // from fitted, the small flick in that fits it leaves it as the fingers left it
    let s = shown(1.0);
    s.shared.lock().hooks = Some(locking(crate::hooks::GESTURE_FIT));
    assert!(!pinch(&s, -6.9, 32.0, 0.0).contains(&TouchCommand::Fit));
    // a plugin keeping the turn alone leaves the fit as it is, and one on no part neither
    for part in [crate::hooks::GESTURE_TURN, 7] {
        let s = shown(1.0);
        s.shared.lock().hooks = Some(locking(part));
        assert!(pinch(&s, -5.0, 120.0, 0.0).contains(&TouchCommand::Fit), "{part}");
    }
    // asked as the fingers land, so a gesture under way goes on as it started
    let hooks = locking(crate::hooks::GESTURE_FIT);
    let t = std::time::Instant::now();
    for _ in 0..1000 {
        assert!(hooks.gesture_lock(crate::hooks::GESTURE_FIT));
    }
    let asked = t.elapsed() / 1000;
    println!("the fit's lock asked in {asked:?} as a finger lands");
    assert!(asked < std::time::Duration::from_micros(200), "{asked:?}");
}

#[test]
fn a_pinch_that_stops_before_lifting_doesn_t_fit() {
    // however quick and far it went, fingers still as they lift aren't a flick
    for (speed, ms, still) in [(-5.0, 100.0, 60.0), (-3.3, 150.0, 45.0)] {
        let out = pinch(&shown(1.0), speed, ms, still);
        assert!(!out.contains(&TouchCommand::Fit), "{speed}: {out:?}");
    }
    // and a slow zoom out to well under the fit never is
    for (speed, ms) in [(-1.5, 300.0), (-1.0, 450.0), (-0.6, 800.0)] {
        let out = pinch(&shown(1.0), speed, ms, 0.0);
        assert!(!out.contains(&TouchCommand::Fit), "{speed}: {out:?}");
    }
}

/// The view turned by `deg` about the window's middle.
fn turned(s: &CanvasSession, deg: f32) {
    let mut st = s.shared.lock();
    if let Some(v) = st.view.as_mut() {
        v.gesture(Vec2::new(400.0, 300.0), Vec2::ZERO, 1.0, deg.to_radians());
    }
}

/// Two fingers 400 apart, about (400, 300), turned by `deg` over 200 ms and moved by `shift`,
/// held still a moment and lifted (or taken back, if `cancel`): what came of it.
fn two_fingers(s: &CanvasSession, deg: f32, shift: f32, cancel: bool) -> Vec<TouchCommand> {
    let end = |a: f32, side: f32, x: f32| (400.0 + x + side * 200.0 * a.cos(), 300.0 + side * 200.0 * a.sin());
    let mut out = s.touch_down(1, PointerKind::Finger, at(200.0, 300.0, 0.0));
    out.extend(s.touch_down(2, PointerKind::Finger, at(600.0, 300.0, 0.0)));
    let (mut a, mut b) = ((200.0, 300.0), (600.0, 300.0));
    for i in 1..=20 {
        let (k, ms) = (i as f32 / 20.0, f64::from(i) * 10.0);
        let angle = (deg * k).to_radians();
        (a, b) = (end(angle, -1.0, shift * k), end(angle, 1.0, shift * k));
        out.extend(s.touch_move(1, at(a.0, a.1, ms)));
        out.extend(s.touch_move(2, at(b.0, b.1, ms)));
    }
    if cancel {
        out.extend(s.touch_cancel());
        return out;
    }
    out.extend(s.touch_up(2, at(b.0, b.1, 400.0)));
    out.extend(s.touch_up(1, at(a.0, a.1, 410.0)));
    out
}

/// The view's turn once it has settled, degrees.
fn settled(s: &CanvasSession) -> f32 {
    let mut st = s.shared.lock();
    st.view = st.view_motion.take().map(|m| m.to).or(st.view);
    st.view.map_or(f32::NAN, |v| v.rotation().to_degrees())
}

#[test]
fn a_canvas_let_go_near_a_right_angle_turns_onto_it() {
    // turned a little by the fingers themselves: straight again
    let s = shown(1.0);
    let out = two_fingers(&s, 4.5, 0.0, false);
    assert!(out.contains(&TouchCommand::ViewSettles), "{out:?}");
    assert!(settled(&s).abs() < 1e-3, "{}", settled(&s));
    // left near a right angle, and only moved this time: onto it, either side
    for (from, onto) in [(3.0, 0.0), (-4.0, 0.0), (87.0, 90.0), (-93.0, -90.0), (182.0, 180.0)] {
        let s = shown(2.0);
        turned(&s, from);
        let middle = s.shared.lock().view.and_then(|v| v.screen_to_canvas(Vec2::new(400.0, 300.0))).unwrap();
        let out = two_fingers(&s, 0.0, 60.0, false);
        assert!(out.contains(&TouchCommand::ViewSettles), "{from}: {out:?}");
        // about the middle of the window, slowing to a stop along a quarter sine, quickly
        let m = s.shared.lock().view_motion.unwrap();
        let crate::ViewCurve::Turn { seconds, about } = m.curve else { panic!("{from}") };
        assert!((0.134..0.2).contains(&seconds), "{from}: {seconds}");
        let kept = m.from.to_screen().apply(about);
        for along in [0.3, 0.7] {
            let mid = procraft_engine::view::View::between(&m.from, &m.to, about, along);
            assert!(mid.to_screen().apply(about).distance(kept) < 1e-2, "{from}");
        }
        assert!(about.distance(middle) > 1.0, "{from}: the fingers moved it");
        let off = (settled(&s) - onto + 180.0).rem_euclid(360.0) - 180.0;
        assert!(off.abs() < 1e-3, "{from}: {}", settled(&s));
    }
    // farther from one, it stays as it is
    let s = shown(1.0);
    turned(&s, 20.0);
    let out = two_fingers(&s, 0.0, 60.0, false);
    assert!(!out.contains(&TouchCommand::ViewSettles), "{out:?}");
    assert!((settled(&s) - 20.0).abs() < 1e-3, "{}", settled(&s));
}

#[test]
fn a_canvas_taken_back_tapped_or_fitted_doesn_t_turn_onto_a_right_angle() {
    // the platform took the fingers back
    let s = shown(1.0);
    turned(&s, 3.0);
    let out = two_fingers(&s, 0.0, 60.0, true);
    assert!(!out.contains(&TouchCommand::ViewSettles), "{out:?}");
    assert!((settled(&s) - 3.0).abs() < 1e-3);
    // two fingers tapped: undo, the canvas left alone
    s.touch_down(1, PointerKind::Finger, at(200.0, 300.0, 1000.0));
    s.touch_down(2, PointerKind::Finger, at(600.0, 300.0, 1000.0));
    let mut out = s.touch_up(2, at(600.0, 300.0, 1080.0));
    out.extend(s.touch_up(1, at(200.0, 300.0, 1090.0)));
    assert!(out.contains(&TouchCommand::Undo) && !out.contains(&TouchCommand::ViewSettles), "{out:?}");
    assert!((settled(&s) - 3.0).abs() < 1e-3);
    // a quick pinch in fits it, straight, with no turn of its own
    let s = shown(1.0);
    turned(&s, 3.0);
    let out = pinch(&s, -5.0, 120.0, 0.0);
    assert!(out.contains(&TouchCommand::Fit) && !out.contains(&TouchCommand::ViewSettles), "{out:?}");
    // with less motion asked for, it is there at once
    let s = shown(1.0);
    s.set_reduce_motion(true);
    turned(&s, 3.0);
    two_fingers(&s, 0.0, 60.0, false);
    let m = s.shared.lock().view_motion.unwrap();
    assert!(matches!(m.curve, crate::ViewCurve::Turn { seconds, .. } if seconds == 0.01));
}

#[test]
fn a_flick_that_leaves_the_canvas_bigger_than_it_fits_doesn_t_fit() {
    let s = shown(3.0);
    let fitted = zoom(&shown(1.0));
    let out = pinch(&s, -6.9, 70.0, 0.0);
    assert!(zoom(&s) > fitted * 1.3, "{} {fitted}", zoom(&s));
    assert!(!out.contains(&TouchCommand::Fit), "{out:?}");
}

#[test]
fn in_the_guide_editor_a_touch_drags_a_handle_and_fingers_only_move_the_view() {
    let s = shown(1.0);
    let fitted = zoom(&s);
    // fitting keeps clear of the interface on the canvas; the editor's fills the window
    s.set_fit_insets(0.0, 100.0, 0.0, 100.0);
    s.set_guide_on(true);
    s.guide_edit_begin();
    {
        // where it is moving to: the canvas fitted in the middle 0.6 of the window
        let mut st = s.shared.lock();
        st.view = st.view_motion.take().map(|m| m.to).or(st.view);
    }
    assert!((zoom(&s) / fitted - 0.6).abs() < 1e-4, "{} {fitted}", zoom(&s));
    s.set_touch_guide_editing(true);
    // the centre (the canvas's middle, the window's) dragged
    let out = s.touch_down(1, PointerKind::Finger, at(400.0, 300.0, 0.0));
    assert!(out.contains(&TouchCommand::GuideTouched), "{out:?}");
    let out = s.touch_move(1, at(450.0, 290.0, 16.0));
    assert!(out.contains(&TouchCommand::GuideChanged), "{out:?}");
    s.touch_up(1, at(450.0, 290.0, 32.0));
    let centre = s.guide().centre;
    assert!((centre[0] - 450.0).abs() < 1e-3 && (centre[1] - 290.0).abs() < 1e-3, "{centre:?}");
    // a touch away from the handles takes nothing, and paints nothing
    s.touch_down(1, PointerKind::Finger, at(200.0, 200.0, 100.0));
    let out = s.touch_move(1, at(260.0, 230.0, 116.0));
    assert!(!out.contains(&TouchCommand::GuideChanged), "{out:?}");
    s.touch_up(1, at(260.0, 230.0, 132.0));
    assert!(s.shared.lock().stroke.is_none());
    assert_eq!(s.guide().centre, centre);
    // two fingers held still don't undo again and again, nor tapped once
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 1000.0));
    s.touch_down(2, PointerKind::Finger, at(300.0, 100.0, 1010.0));
    assert_eq!(s.touch_deadline(), None);
    let mut out = s.touch_up(2, at(300.0, 100.0, 1080.0));
    out.extend(s.touch_up(1, at(100.0, 100.0, 1090.0)));
    assert!(!out.contains(&TouchCommand::Undo), "{out:?}");
    // a slow pinch zooms the canvas out, and it stays so
    let before = zoom(&s);
    let out = pinch(&s, -1.0, 450.0, 0.0);
    assert!(zoom(&s) < before * 0.7, "{} from {before}", zoom(&s));
    assert!(!out.contains(&TouchCommand::Fit), "{out:?}");
    // a quick one fits it, to the whole window
    let out = pinch(&s, -5.0, 120.0, 0.0);
    assert!(out.contains(&TouchCommand::Fit), "{out:?}");
    s.fit_view();
    assert!((zoom(&s) - fitted).abs() < 1e-4, "{} {fitted}", zoom(&s));
    // closed, the canvas fits clear of the interface again
    s.set_touch_guide_editing(false);
    s.guide_edit_end(true);
    s.fit_view();
    assert!(zoom(&s) < fitted * 0.9, "{} {fitted}", zoom(&s));
}

#[test]
fn in_the_guide_editor_a_tap_adds_a_point_and_a_handle_tapped_shows_its_menu() {
    let s = shown(1.0);
    s.set_guide_on(true);
    s.set_guide_kind(crate::GuideType::Perspective);
    s.guide_edit_begin();
    {
        let mut st = s.shared.lock();
        st.view = st.view_motion.take().map(|m| m.to).or(st.view);
    }
    s.set_touch_guide_editing(true);
    let points = |s: &CanvasSession| s.guide().vanishing.len() / 2;
    // a pinch adds no point, not even where its first finger lands
    pinch(&s, -1.0, 200.0, 0.0);
    assert_eq!(points(&s), 0);
    // each touch a second after the last
    let clock = std::cell::Cell::new(1000.0);
    let next = || {
        clock.set(clock.get() + 1000.0);
        clock.get()
    };
    let tap = |x: f32, y: f32, held: f64| {
        let ms = next();
        let mut out = s.touch_down(1, PointerKind::Finger, at(x, y, ms));
        out.extend(s.touch_up(1, at(x, y, ms + held)));
        out
    };
    // a touch held longer than a tap, or dragged, adds none either
    tap(200.0, 300.0, 600.0);
    assert_eq!(points(&s), 0);
    let ms = next();
    s.touch_down(1, PointerKind::Finger, at(200.0, 300.0, ms));
    assert_eq!(points(&s), 0);
    s.touch_move(1, at(260.0, 300.0, ms + 16.0));
    s.touch_up(1, at(260.0, 300.0, ms + 32.0));
    assert_eq!(points(&s), 0);
    // a tap adds one where it lands, up to three
    let out = tap(200.0, 300.0, 50.0);
    assert!(out.contains(&TouchCommand::GuideChanged), "{out:?}");
    assert_eq!(points(&s), 1);
    let v = s.guide().vanishing;
    assert!((v[0] - 200.0).abs() < 1e-2 && (v[1] - 300.0).abs() < 1e-2, "{v:?}");
    tap(600.0, 300.0, 50.0);
    tap(400.0, 100.0, 50.0);
    assert_eq!(points(&s), 3);
    let out = tap(400.0, 500.0, 50.0);
    assert_eq!(points(&s), 3);
    assert!(!out.contains(&TouchCommand::GuideChanged), "{out:?}");
    // a tap on a point shows its menu, there, and adds nothing
    let out = tap(604.0, 303.0, 50.0);
    assert_eq!(points(&s), 3);
    let menu = out.iter().find_map(|c| match c {
        TouchCommand::GuideNodeTapped { point, x, y } => Some((*point, *x, *y)),
        _ => None,
    });
    assert!(
        matches!(menu, Some((Some(1), x, y)) if (x - 600.0).abs() < 1e-2 && (y - 300.0).abs() < 1e-2),
        "{out:?}"
    );
    // a touch on it wobbling a little leaves it; dragged, it follows
    let ms = next();
    s.touch_down(1, PointerKind::Finger, at(600.0, 300.0, ms));
    s.touch_move(1, at(605.0, 302.0, ms + 16.0));
    let still = s.guide().vanishing;
    assert!((still[2] - 600.0).abs() < 1e-2 && (still[3] - 300.0).abs() < 1e-2, "{still:?}");
    let out = s.touch_move(1, at(700.0, 320.0, ms + 32.0));
    assert!(out.contains(&TouchCommand::GuideChanged), "{out:?}");
    let out = s.touch_up(1, at(700.0, 320.0, ms + 48.0));
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::GuideNodeTapped { .. })), "{out:?}");
    let v = s.guide().vanishing;
    assert!((v[2] - 700.0).abs() < 1e-2 && (v[3] - 320.0).abs() < 1e-2, "{v:?}");
    // far off the canvas too
    let ms = next();
    s.touch_down(1, PointerKind::Finger, at(700.0, 320.0, ms));
    s.touch_move(1, at(790.0, 590.0, ms + 16.0));
    s.touch_up(1, at(790.0, 590.0, ms + 32.0));
    let far = s.guide().vanishing;
    assert!((far[2] - 790.0).abs() < 1e-2 && (far[3] - 590.0).abs() < 1e-2, "{far:?}");
    // Delete takes it away, and the others stay
    assert_eq!(s.remove_vanishing_point(1).vanishing, [v[0], v[1], v[4], v[5]]);
    // the other kinds: the centre tapped offers Reset, the turn handle nothing
    s.set_guide_kind(crate::GuideType::Grid);
    s.move_guide_centre(300.0, 250.0);
    s.turn_guide(400.0, 100.0);
    let g = s.guide();
    let out = tap(g.centre[0] + 3.0, g.centre[1], 50.0);
    assert!(out.iter().any(|c| matches!(c, TouchCommand::GuideNodeTapped { point: None, .. })), "{out:?}");
    let out = tap(g.handle[0], g.handle[1], 50.0);
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::GuideNodeTapped { .. })), "{out:?}");
    assert_eq!(s.guide().centre, g.centre);
    // Reset: back in the middle of the canvas (where the pinch left it), upright
    let back = s.reset_guide_place();
    assert!((back.centre[0] - 400.0).abs() < 1.0 && (back.centre[1] - 300.0).abs() < 1.0, "{back:?}");
    assert!((back.handle[0] - back.centre[0]).abs() < 1e-2 && back.handle[1] < back.centre[1], "{back:?}");
    // and a tap elsewhere adds nothing to them
    tap(100.0, 100.0, 50.0);
    assert_eq!(s.guide().centre, back.centre);
    s.set_touch_guide_editing(false);
    s.guide_edit_end(true);
}

#[test]
fn a_finger_held_still_picks_a_colour() {
    let s = session();
    s.touch_down(1, PointerKind::Finger, at(200.0, 200.0, 0.0));
    let out = s.touch_tick(399.0);
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::Pick { .. })));
    let out = s.touch_tick(400.0);
    assert!(out.contains(&TouchCommand::Pick { x: 200.0, y: 200.0 }), "{out:?}");
    let out = s.touch_move(1, at(210.0, 200.0, 450.0));
    assert!(out.contains(&TouchCommand::Pick { x: 210.0, y: 200.0 }));
    let out = s.touch_up(1, at(210.0, 200.0, 500.0));
    assert!(out.contains(&TouchCommand::PickDone));
}

#[test]
fn three_fingers_scrubbed_clear_and_swiped_down_open_copy_and_paste() {
    let s = session();
    for (id, x) in [(1, 100.0), (2, 200.0), (3, 300.0)] {
        s.touch_down(id, PointerKind::Finger, at(x, 300.0, 0.0));
    }
    let mut cleared = false;
    for (leg, dx) in [100.0f32, -100.0, 100.0, -100.0, 100.0].into_iter().enumerate() {
        for step in 1..=5 {
            let shift = if leg % 2 == 0 { 0.0 } else { 100.0 } + dx * step as f32 / 5.0;
            for (id, x) in [(1, 100.0), (2, 200.0), (3, 300.0)] {
                let out = s.touch_move(id, at(x + shift, 300.0, 10.0 * (leg * 5 + step) as f64));
                cleared |= out.contains(&TouchCommand::ClearLayer);
            }
        }
    }
    assert!(cleared);
    for id in 1..=3 {
        s.touch_up(id, at(0.0, 0.0, 1000.0));
    }

    for (id, x) in [(1, 100.0), (2, 200.0), (3, 300.0)] {
        s.touch_down(id, PointerKind::Finger, at(x, 100.0, 2000.0));
    }
    let mut copy_paste = false;
    for step in 1..=10 {
        for (id, x) in [(1, 100.0), (2, 200.0), (3, 300.0)] {
            let out = s.touch_move(id, at(x, 100.0 + 20.0 * step as f32, 2000.0 + 10.0 * f64::from(step)));
            copy_paste |= out.contains(&TouchCommand::CopyPaste);
        }
    }
    assert!(copy_paste);
}

/// As a hand does them on a tablet at twice the pixels a point: the fingers landing and moving
/// one after another, a swipe down wobbling sideways, a scrub drifting up and down.
#[test]
fn a_hand_s_swipe_down_never_clears_and_its_scrub_does() {
    let fingers = [(1, 400.0f32), (2, 600.0), (3, 800.0)];
    let s = session();
    s.set_display_scale(2.0);
    for (k, (id, x)) in fingers.into_iter().enumerate() {
        s.touch_down(id, PointerKind::Finger, at(x, 300.0, 5.0 * k as f64));
    }
    let (mut cleared, mut copy_paste) = (false, false);
    for step in 1..=25 {
        for (k, (id, x)) in fingers.into_iter().enumerate() {
            // 12 down a step, each finger swaying 15 to the sides out of step with the others
            let sway = 15.0 * (step as f32 * 0.9 + k as f32 * 2.1).sin();
            let out = s.touch_move(id, at(x + sway, 300.0 + 12.0 * step as f32, 10.0 + 8.0 * f64::from(step)));
            cleared |= out.contains(&TouchCommand::ClearLayer);
            copy_paste |= out.contains(&TouchCommand::CopyPaste);
        }
    }
    for (id, x) in fingers {
        s.touch_up(id, at(x, 600.0, 300.0));
    }
    assert!(copy_paste && !cleared, "swipe: copy and paste {copy_paste}, cleared {cleared}");

    let s = session();
    s.set_display_scale(2.0);
    for (k, (id, x)) in fingers.into_iter().enumerate() {
        s.touch_down(id, PointerKind::Finger, at(x, 300.0, 5.0 * k as f64));
    }
    let (mut cleared, mut copy_paste) = (false, false);
    // four legs 90 across (45 points), 6 moves each, drifting 20 up and down
    for step in 1..=24 {
        let leg = (step - 1) / 6;
        let along = ((step - 1) % 6 + 1) as f32 * 15.0;
        let dx = if leg % 2 == 0 { along } else { 90.0 - along };
        let drift = 20.0 * (step as f32 * 0.5).sin();
        for (id, x) in fingers {
            let out = s.touch_move(id, at(x + dx, 300.0 + drift, 10.0 + 12.0 * f64::from(step)));
            cleared |= out.contains(&TouchCommand::ClearLayer);
            copy_paste |= out.contains(&TouchCommand::CopyPaste);
        }
    }
    assert!(cleared && !copy_paste, "scrub: cleared {cleared}, copy and paste {copy_paste}");
}

#[test]
fn the_modify_button_picks_with_the_next_touch_or_while_held() {
    let s = session();
    s.set_pick_next(true);
    let out = s.touch_down(1, PointerKind::Stylus, at(50.0, 60.0, 0.0));
    assert!(out.contains(&TouchCommand::Pick { x: 50.0, y: 60.0 }));
    let out = s.touch_up(1, at(50.0, 60.0, 50.0));
    assert!(out.contains(&TouchCommand::PickDone));
    assert!(!s.pick_next());

    s.set_modify_held(true);
    let out = s.touch_down(1, PointerKind::Finger, at(70.0, 80.0, 100.0));
    assert!(out.contains(&TouchCommand::Pick { x: 70.0, y: 80.0 }));
    let out = s.touch_up(1, at(70.0, 80.0, 150.0));
    assert!(out.contains(&TouchCommand::PickDone));
    // a touch used it: its tap does nothing
    assert!(s.set_modify_held(false));
    assert!(!s.set_modify_held(false));
}

#[test]
fn the_loupe_sits_on_a_pens_tip_and_clear_above_a_finger() {
    let s = session();
    // the modify button tapped, the next touch picks: a finger's loupe above it, a pen's on its tip
    s.set_pick_next(true);
    assert!(
        s.touch_down(1, PointerKind::Finger, at(50.0, 60.0, 0.0))
            .contains(&TouchCommand::Pick { x: 50.0, y: 60.0 })
    );
    assert_eq!(s.pick_loupe_lift(), 40.0);
    s.touch_up(1, at(50.0, 60.0, 50.0));
    s.set_pick_next(true);
    assert!(
        s.touch_down(1, PointerKind::Stylus, at(50.0, 60.0, 100.0))
            .contains(&TouchCommand::Pick { x: 50.0, y: 60.0 })
    );
    assert_eq!(s.pick_loupe_lift(), 0.0);
    s.touch_up(1, at(50.0, 60.0, 150.0));
    // the modify button held, the pen picking
    s.set_modify_held(true);
    assert!(
        s.touch_down(1, PointerKind::Stylus, at(70.0, 80.0, 200.0))
            .contains(&TouchCommand::Pick { x: 70.0, y: 80.0 })
    );
    assert_eq!(s.pick_loupe_lift(), 0.0);
    s.touch_up(1, at(70.0, 80.0, 250.0));
    s.set_modify_held(false);
    // the pen long gone, a finger held still: above it
    s.touches().pen_left = None;
    s.touch_down(1, PointerKind::Finger, at(200.0, 200.0, 1000.0));
    assert!(s.touch_tick(1400.0).contains(&TouchCommand::Pick { x: 200.0, y: 200.0 }));
    assert_eq!(s.pick_loupe_lift(), 40.0);
    s.touch_up(1, at(200.0, 200.0, 1500.0));
}

#[test]
fn an_adjustments_amount_is_swiped_and_a_tap_asks_for_its_menu() {
    let s = session();
    s.shared.lock().adjust = crate::adjusting::Adjusting::opened(Adjustment::GaussianBlur);
    let out = s.touch_down(1, PointerKind::Finger, at(10.0, 30.0, 0.0));
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::Cursor { .. })), "{out:?}");
    let out = s.touch_move(1, at(60.0, 30.0, 30.0));
    assert!(out.contains(&TouchCommand::AdjustChanged), "{out:?}");
    let swiped = s.adjustment_shown().amount;
    assert!(swiped > 0.0, "{swiped}");
    let out = s.touch_up(1, at(60.0, 30.0, 40.0));
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::AdjustMenu { .. })), "{out:?}");
    // back the other way, the amount goes down
    s.touch_down(1, PointerKind::Finger, at(60.0, 30.0, 100.0));
    s.touch_move(1, at(20.0, 30.0, 130.0));
    s.touch_up(1, at(20.0, 30.0, 140.0));
    assert!(s.adjustment_shown().amount < swiped);
    // a tap asks for the menu where it was
    s.touch_down(1, PointerKind::Finger, at(30.0, 30.0, 300.0));
    let out = s.touch_up(1, at(31.0, 30.0, 350.0));
    assert!(out.contains(&TouchCommand::AdjustMenu { x: 31.0, y: 30.0 }), "{out:?}");
}

/// A session with perspective blur open on a 1000 × 800 canvas seen one to one, its puck in
/// the middle (two window pixels a point).
fn perspective_session(kind: PerspectiveKind) -> std::sync::Arc<CanvasSession> {
    let s = CanvasSession::new(1000, 800);
    {
        let mut st = s.shared.lock();
        st.view = Some(procraft_engine::view::View::from_affine(procraft_engine::geom::Affine::IDENTITY));
        st.adjust = crate::adjusting::Adjusting::opened(Adjustment::PerspectiveBlur);
    }
    s.adjustment_perspective_kind(kind);
    s
}

/// Perspective blur's puck and the point along its arrow, in the window.
fn puck(s: &CanvasSession) -> Vec<f32> {
    s.adjustment_shown().perspective_puck.unwrap_or_default().iter().map(|v| (v * 100.0).round() / 100.0).collect()
}

#[test]
fn perspective_blurs_puck_is_dragged_about_and_undo_steps_it_back() {
    let s = perspective_session(PerspectiveKind::Positional);
    assert_eq!(puck(&s), [500.0, 400.0, 500.0, 300.0]);
    // a touch on the puck: it waits for the slop, then the puck follows where it was held
    s.touch_down(1, PointerKind::Finger, at(520.0, 400.0, 0.0));
    let out = s.touch_move(1, at(525.0, 402.0, 10.0));
    assert!(!out.contains(&TouchCommand::AdjustChanged), "{out:?}");
    let out = s.touch_move(1, at(620.0, 450.0, 40.0));
    assert!(out.contains(&TouchCommand::AdjustChanged), "{out:?}");
    let out = s.touch_up(1, at(620.0, 450.0, 60.0));
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::AdjustMenu { .. })), "{out:?}");
    assert_eq!(puck(&s), [600.0, 450.0, 600.0, 350.0]);
    assert_eq!(s.adjustment_shown().amount, 0.0);
    // away from it the amount is swiped, the puck staying
    s.touch_down(1, PointerKind::Finger, at(900.0, 400.0, 100.0));
    s.touch_move(1, at(980.0, 400.0, 130.0));
    s.touch_up(1, at(980.0, 400.0, 140.0));
    let amount = s.adjustment_shown().amount;
    assert!(amount > 0.0);
    assert_eq!(puck(&s), [600.0, 450.0, 600.0, 350.0]);
    // undo puts the puck back, the amount staying; redo moves it again
    assert_eq!(s.undo().as_deref(), Some("Perspective Blur"));
    assert_eq!(puck(&s), [500.0, 400.0, 500.0, 300.0]);
    assert_eq!(s.adjustment_shown().amount, amount);
    s.redo();
    assert_eq!(puck(&s), [600.0, 450.0, 600.0, 350.0]);
}

#[test]
fn directional_perspective_blurs_arrow_turns_from_its_side_and_the_puck_moves_from_the_other() {
    let s = perspective_session(PerspectiveKind::Directional);
    assert_eq!(s.adjustment_shown().perspective_kind, PerspectiveKind::Directional);
    // a tap on it asks for the menu
    s.touch_down(1, PointerKind::Finger, at(505.0, 400.0, 0.0));
    let out = s.touch_up(1, at(505.0, 400.0, 50.0));
    assert!(out.contains(&TouchCommand::AdjustMenu { x: 505.0, y: 400.0 }), "{out:?}");
    // from the arrow's side (60 points up) a drag turns the arrow to point at it
    s.touch_down(1, PointerKind::Finger, at(500.0, 280.0, 100.0));
    s.touch_move(1, at(650.0, 400.0, 130.0));
    s.touch_up(1, at(650.0, 400.0, 140.0));
    assert_eq!(puck(&s), [500.0, 400.0, 600.0, 400.0]);
    // from behind it a drag moves the puck, the arrow keeping its way
    s.touch_down(1, PointerKind::Finger, at(380.0, 400.0, 200.0));
    s.touch_move(1, at(410.0, 440.0, 230.0));
    s.touch_up(1, at(410.0, 440.0, 240.0));
    assert_eq!(puck(&s), [530.0, 440.0, 630.0, 440.0]);
    // beyond its reach (150 points) the amount is swiped
    s.touch_down(1, PointerKind::Finger, at(530.0, 140.0, 300.0));
    s.touch_move(1, at(600.0, 140.0, 330.0));
    s.touch_up(1, at(600.0, 140.0, 340.0));
    assert!(s.adjustment_shown().amount > 0.0);
    assert_eq!(puck(&s), [530.0, 440.0, 630.0, 440.0]);
    // undo steps back the move, then the turn
    s.undo();
    assert_eq!(puck(&s), [500.0, 400.0, 600.0, 400.0]);
    s.undo();
    assert_eq!(puck(&s), [500.0, 400.0, 500.0, 300.0]);
}

#[test]
fn the_selection_tool_drags_out_shapes_and_slides_a_colour_selections_threshold() {
    let s = session();
    let tool = |kind| Some(SelectTouch { kind, join: crate::SelectionJoin::Add, threshold: 0.5 });
    s.set_touch_selecting(tool(SelectKind::Rectangle));
    s.touch_down(1, PointerKind::Stylus, at(10.0, 10.0, 0.0));
    let out = s.touch_move(1, at(120.0, 90.0, 20.0));
    assert!(out.contains(&TouchCommand::SelectionDrag { x0: 10.0, y0: 10.0, x1: 120.0, y1: 90.0 }), "{out:?}");
    let out = s.touch_up(1, at(120.0, 90.0, 40.0));
    assert!(out.contains(&TouchCommand::SelectionChanged), "{out:?}");
    assert!(!out.contains(&TouchCommand::StrokeEnded));

    s.set_touch_selecting(tool(SelectKind::Automatic));
    s.touch_down(1, PointerKind::Finger, at(50.0, 50.0, 1000.0));
    let out = s.touch_move(1, at(120.0, 50.0, 1020.0));
    let slid = out.iter().find_map(|c| match c {
        TouchCommand::SelectionThreshold { threshold, done: false, .. } => Some(*threshold),
        _ => None,
    });
    assert!(slid.is_some_and(|t| t > 0.5), "{out:?}");
    let out = s.touch_up(1, at(120.0, 50.0, 1040.0));
    assert!(out.iter().any(|c| matches!(c, TouchCommand::SelectionThreshold { done: true, .. })), "{out:?}");
    s.set_touch_selecting(None);
}

#[test]
fn a_transform_takes_the_touches_and_paints_nothing() {
    let s = session();
    s.set_touch_transforming(Some(TransformTouch { advanced_mesh: false }));
    let out = s.touch_down(1, PointerKind::Stylus, at(20.0, 20.0, 0.0));
    assert!(out.contains(&TouchCommand::TransformTouched), "{out:?}");
    // a tap off the nodes nudges (no box here: it says so)
    let out = s.touch_up(1, at(20.0, 20.0, 50.0));
    assert!(out.contains(&TouchCommand::TransformMoved { transform: None }), "{out:?}");
    assert!(!out.contains(&TouchCommand::StrokeEnded));
    s.set_touch_transforming(None);
    let out = s.touch_down(1, PointerKind::Stylus, at(20.0, 20.0, 100.0));
    assert!(!out.contains(&TouchCommand::TransformTouched));
}

#[test]
fn after_a_warp_the_other_kinds_drag_the_box_s_nodes() {
    use procraft_engine::transform::{Transform, TransformMode};
    let s = session();
    {
        let mut st = s.shared.lock();
        st.view = Some(procraft_engine::view::View::from_affine(procraft_engine::geom::Affine::IDENTITY));
        let mut t = Transform::new(100.0, 100.0, 200.0, 100.0, Vec::new());
        t.start_warp();
        st.transform = Some(t);
        st.transform_mode = TransformMode::Distort;
    }
    s.set_touch_transforming(Some(TransformTouch { advanced_mesh: false }));
    let corner = |s: &CanvasSession| {
        let b = s.shared.lock().transform_box().map(|b| b.corners).unwrap_or_default();
        (b.first().copied(), b.get(1).copied())
    };
    // the top left corner dragged up and left goes with the touch from where the drag began
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    s.touch_move(1, at(70.0, 80.0, 16.0));
    s.touch_move(1, at(50.0, 60.0, 32.0));
    s.touch_up(1, at(50.0, 60.0, 48.0));
    assert_eq!(corner(&s), (Some(80.0), Some(80.0)));
    s.set_touch_transforming(None);
}

#[test]
fn layer_opacity_follows_a_drag_across_and_paints_nothing() {
    let s = session();
    let layer = s.layers().into_iter().find(|l| l.current).unwrap();
    s.set_touch_layer_opacity(Some(layer.id));
    assert_eq!(s.touch_layer_opacity(), Some(layer.opacity));
    let opacity = |s: &CanvasSession| s.layers().into_iter().find(|l| l.id == layer.id).unwrap().opacity;
    // a tap changes nothing
    s.touch_down(1, PointerKind::Finger, at(400.0, 100.0, 0.0));
    let out = s.touch_up(1, at(400.0, 100.0, 50.0));
    assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    assert_eq!(opacity(&s), layer.opacity);
    // a drag to the left lowers it, as it goes
    s.touch_down(1, PointerKind::Finger, at(400.0, 100.0, 100.0));
    for k in 1..=10 {
        s.touch_move(1, at(400.0 - 20.0 * k as f32, 100.0, 100.0 + 16.0 * f64::from(k)));
    }
    let out = s.touch_up(1, at(200.0, 100.0, 300.0));
    assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    let dragged = s.touch_layer_opacity().unwrap();
    assert!(dragged < layer.opacity, "{dragged}");
    assert_eq!(opacity(&s), dragged);
    s.set_touch_layer_opacity(None);
    assert_eq!(s.touch_layer_opacity(), None);
}

#[test]
fn hover_s_finger_drags_the_opacity_and_two_pinch_the_size() {
    let s = session();
    s.shared.lock().window_size = (1000, 800);
    s.set_brush_size(0.5);
    s.set_brush_opacity(0.5);
    s.set_touch_settings(TouchSettings { hover_touch: true, ..TouchSettings::default() });
    // the pen held over the canvas: a finger dragged a fifth of the window across adds a fifth
    s.set_touch_pen_hovering(true);
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    let out = s.touch_move(1, at(300.0, 100.0, 16.0));
    assert_eq!(out, vec![TouchCommand::BrushChanged]);
    assert!((s.info().opacity - 0.7).abs() < 1e-4, "{}", s.info().opacity);
    // a second finger: twice as far apart, twice the size
    s.touch_down(2, PointerKind::Finger, at(400.0, 100.0, 20.0));
    s.touch_move(2, at(500.0, 100.0, 30.0));
    assert!((s.info().size - 1.0).abs() < 1e-4, "{}", s.info().size);
    // the first finger moved in the pinch drags on from where it went
    s.touch_move(1, at(200.0, 100.0, 32.0));
    s.touch_up(2, at(500.0, 100.0, 40.0));
    s.touch_move(1, at(250.0, 100.0, 45.0));
    assert!((s.info().opacity - 0.75).abs() < 1e-4, "{}", s.info().opacity);
    let out = s.touch_up(1, at(250.0, 100.0, 50.0));
    assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    // the pen gone, a finger paints again
    s.set_touch_pen_hovering(false);
    s.touch_down(1, PointerKind::Finger, at(10.0, 10.0, 100.0));
    s.touch_move(1, at(60.0, 10.0, 120.0));
    assert!(s.touch_up(1, at(60.0, 10.0, 140.0)).contains(&TouchCommand::StrokeEnded));
    // and when it went without a word (not heard from for a while)
    s.set_touch_pen_hovering(true);
    s.touches().pen_hovering = std::time::Instant::now().checked_sub(std::time::Duration::from_secs(1));
    s.touch_down(1, PointerKind::Finger, at(10.0, 30.0, 200.0));
    s.touch_move(1, at(60.0, 30.0, 220.0));
    assert!(s.touch_up(1, at(60.0, 30.0, 240.0)).contains(&TouchCommand::StrokeEnded));
}

#[test]
fn recolor_s_touch_drags_its_reticle_or_taps_it_elsewhere_and_paints_nothing() {
    let s = session();
    s.shared.lock().view = Some(procraft_engine::view::View::from_affine(procraft_engine::geom::Affine::IDENTITY));
    s.recolor_start(32.0, 32.0, 0.0, 0.0, 1.0, 0.0);
    // the app moves the reticle where the touches say
    let obey = |out: Vec<TouchCommand>| {
        for c in &out {
            match *c {
                TouchCommand::RecolorMoved { x, y } => s.recolor_move_to(x, y),
                TouchCommand::RecolorTapped { x, y } => s.recolor_tap(x, y),
                _ => {}
            }
        }
        out
    };
    // a drag past the slop moves it as far as the touch went from where it came down
    obey(s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0)));
    obey(s.touch_move(1, at(200.0, 100.0, 16.0)));
    let out = obey(s.touch_move(1, at(210.0, 110.0, 32.0)));
    assert_eq!(out, vec![TouchCommand::RecolorMoved { x: 142.0, y: 42.0 }]);
    let out = obey(s.touch_up(1, at(210.0, 110.0, 48.0)));
    assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    assert_eq!(s.recolor_point(), Some(vec![142.0, 42.0]));
    // a tap puts it where it lands
    obey(s.touch_down(1, PointerKind::Finger, at(10.0, 12.0, 100.0)));
    obey(s.touch_up(1, at(11.0, 12.0, 150.0)));
    assert_eq!(s.recolor_point(), Some(vec![11.0, 12.0]));
    s.recolor_end();
}

#[test]
fn the_companion_s_move_takes_a_lone_press_and_moves_the_view_without_painting() {
    let s = session();
    s.shared.lock().view = Some(procraft_engine::view::View::from_affine(procraft_engine::geom::Affine::IDENTITY));
    s.set_touch_companion(Some(CompanionMode::Move));
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    let out = s.touch_move(1, at(130.0, 120.0, 16.0));
    assert!(out.contains(&TouchCommand::ViewMoved), "{out:?}");
    // a second finger meanwhile does nothing
    assert!(s.touch_down(2, PointerKind::Finger, at(300.0, 300.0, 20.0)).is_empty());
    s.touch_up(2, at(300.0, 300.0, 30.0));
    let out = s.touch_up(1, at(130.0, 120.0, 40.0));
    assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    let m = s.view_matrix();
    assert_eq!((m.get(4).copied(), m.get(5).copied()), (Some(30.0), Some(20.0)));
    // off, a press paints again
    s.set_touch_companion(None);
    s.touch_down(1, PointerKind::Finger, at(10.0, 10.0, 100.0));
    s.touch_move(1, at(60.0, 10.0, 120.0));
    let out = s.touch_up(1, at(60.0, 10.0, 140.0));
    assert!(out.contains(&TouchCommand::StrokeEnded), "{out:?}");
}

#[test]
fn one_finger_set_to_move_the_canvas_moves_it_and_the_pen_still_paints() {
    let s = session();
    s.shared.lock().view = Some(procraft_engine::view::View::from_affine(procraft_engine::geom::Affine::IDENTITY));
    let g = crate::gestures::GestureSettings::new(crate::gestures::KeptGestures::default());
    let changed = g.set(crate::gestures::GesturePurpose::General, "ONE_FINGER_PAN".into(), true);
    // painting with a finger goes off with it, and back on turns it off again
    assert_eq!(changed.get("GENERAL.FINGER_PAINT"), Some(&false));
    let settings = g.touch_settings(0.0);
    assert!(settings.one_finger_pan && !settings.finger_paint && settings.touch.is_none());
    s.set_touch_settings(settings);
    // a drag moves the canvas with the finger, painting nothing
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 0.0));
    let out = s.touch_move(1, at(120.0, 110.0, 8.0));
    assert!(!out.contains(&TouchCommand::ViewMoved), "within a tap's slop: {out:?}");
    let out = s.touch_move(1, at(160.0, 140.0, 16.0));
    assert!(out.contains(&TouchCommand::ViewMoved), "{out:?}");
    s.touch_move(1, at(170.0, 145.0, 24.0));
    let out = s.touch_up(1, at(170.0, 145.0, 40.0));
    assert!(!out.contains(&TouchCommand::StrokeEnded), "{out:?}");
    assert!(!s.info().can_undo);
    let m = s.view_matrix();
    assert_eq!((m.get(4).copied(), m.get(5).copied()), (Some(70.0), Some(45.0)));
    // held still, it still picks a colour
    s.touch_down(1, PointerKind::Finger, at(200.0, 200.0, 1000.0));
    assert!(s.touch_tick(1450.0).contains(&TouchCommand::Pick { x: 200.0, y: 200.0 }));
    s.touch_up(1, at(200.0, 200.0, 1500.0));
    // two fingers tapped still undo
    s.touch_down(1, PointerKind::Finger, at(100.0, 100.0, 2000.0));
    s.touch_down(2, PointerKind::Finger, at(200.0, 100.0, 2010.0));
    assert!(s.touch_up(1, at(100.0, 100.0, 2050.0)).contains(&TouchCommand::Undo));
    s.touch_up(2, at(200.0, 100.0, 2060.0));
    // the pen paints, and so does a mouse (a desktop's drawing tool)
    for kind in [PointerKind::Stylus, PointerKind::Mouse] {
        s.touch_down(3, kind, at(10.0, 10.0, 3000.0));
        s.touch_move(3, at(60.0, 10.0, 3020.0));
        assert!(s.touch_up(3, at(60.0, 10.0, 3040.0)).contains(&TouchCommand::StrokeEnded), "{kind:?}");
    }
    // painting with a finger back on: the finger paints again
    assert_eq!(
        g.set(crate::gestures::GesturePurpose::General, "FINGER_PAINT".into(), true).get("GENERAL.ONE_FINGER_PAN"),
        Some(&false)
    );
    s.set_touch_settings(g.touch_settings(0.0));
    s.touch_down(1, PointerKind::Finger, at(10.0, 30.0, 4000.0));
    s.touch_move(1, at(60.0, 30.0, 4020.0));
    assert!(s.touch_up(1, at(60.0, 30.0, 4040.0)).contains(&TouchCommand::StrokeEnded));
}

#[test]
fn continue_filling_fills_each_touch_and_a_slide_moves_its_threshold() {
    let s = session();
    s.set_touch_filling(Some(Fill { r: 1.0, g: 0.0, b: 0.0, threshold: 0.5 }));
    // off the canvas (no view yet) nothing fills, and the touch does nothing else
    let out = s.touch_down(1, PointerKind::Finger, at(10.0, 10.0, 0.0));
    assert!(!out.iter().any(|c| matches!(c, TouchCommand::Pick { .. })), "{out:?}");
    let out = s.touch_up(1, at(10.0, 10.0, 50.0));
    assert!(!out.contains(&TouchCommand::Filled), "{out:?}");

    // a slide's threshold, as the touch's own
    let mut t = s.touches();
    t.mode = Mode::Pour;
    t.down_at = (100.0, 100.0);
    t.pour_last = (100.0, 0.0);
    t.pour_moved = false;
    assert_eq!(CanvasSession::pour_slide(&mut t, (110.0, 100.0), 16.0), None, "within the slop");
    let up = CanvasSession::pour_slide(&mut t, (160.0, 100.0), 32.0).unwrap();
    assert!(up > 0.5 && up < 1.0, "{up}");
    let down = CanvasSession::pour_slide(&mut t, (100.0, 100.0), 48.0).unwrap();
    assert!(down < up, "{down} < {up}");
    t.mode = Mode::None;
    drop(t);
    s.set_touch_filling(None);
    let out = s.touch_down(1, PointerKind::Finger, at(10.0, 10.0, 100.0));
    s.touch_up(1, at(10.0, 10.0, 150.0));
    assert!(!out.contains(&TouchCommand::Filled));
}

/// One finger of a tap on a real screen: when it lands and lifts (ms), and how far (points)
/// it drifts from where it landed by the time it lifts, which way (radians).
#[derive(Clone, Copy, Debug)]
struct Finger {
    lands: f64,
    lifts: f64,
    drift: f32,
    towards: f32,
}

/// Fingers tapped as a real screen reports them: each wobbles `wobble` points about its
/// drifting path, all the fingers' moves reported every `every` ms, in one event (as Android
/// gives them) or each its own (as the desktop does). The touches' waits run as they come due,
/// as the apps run them. What the touches asked for.
fn real_tap(s: &CanvasSession, fingers: &[Finger], wobble: f32, every: f64, batched: bool) -> Vec<TouchCommand> {
    let scale = s.shared.lock().display_scale;
    let at_time = |i: usize, ms: f64, k: usize| {
        let f = fingers[i];
        let gone = ((ms - f.lands) / (f.lifts - f.lands).max(1.0)).clamp(0.0, 1.0) as f32;
        let base = (200.0 + 140.0 * i as f32, 300.0 + 15.0 * (i * i) as f32);
        let shake = if ms <= f.lands {
            (0.0, 0.0)
        } else {
            ((k as f32 * 2.4 + i as f32).sin(), (k as f32 * 3.7 + i as f32).cos())
        };
        let x = base.0 + scale * (f.drift * gone * f.towards.cos() + wobble * shake.0);
        let y = base.1 + scale * (f.drift * gone * f.towards.sin() + wobble * shake.1);
        at(x, y, ms)
    };
    // downs, then moves, then lifts, at a time
    let mut events: Vec<(f64, u8, usize)> = Vec::new();
    for (i, f) in fingers.iter().enumerate() {
        events.push((f.lands, 0, i));
        events.push((f.lifts, 2, i));
    }
    let start = fingers.iter().map(|f| f.lands).fold(f64::MAX, f64::min);
    let end = fingers.iter().map(|f| f.lifts).fold(0.0, f64::max);
    let mut ms = start + every;
    while ms < end {
        events.push((ms, 1, 0));
        ms += every;
    }
    events.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)));
    let mut out = Vec::new();
    let mut down = vec![false; fingers.len()];
    for (k, (ms, what, i)) in events.into_iter().enumerate() {
        while let Some(due) = s.touch_deadline().filter(|due| *due <= ms) {
            out.extend(s.touch_tick(due));
        }
        match what {
            0 => {
                down[i] = true;
                out.extend(s.touch_down(i as u64 + 1, PointerKind::Finger, at_time(i, ms, k)));
            }
            1 => {
                let moves: Vec<TouchMove> = (0..fingers.len())
                    .filter(|j| down[*j])
                    .map(|j| TouchMove { id: j as u64 + 1, samples: at_time(j, ms, k) })
                    .collect();
                if batched {
                    out.extend(s.touch_moves(moves));
                } else {
                    for m in moves {
                        out.extend(s.touch_move(m.id, m.samples));
                    }
                }
            }
            _ => {
                down[i] = false;
                out.extend(s.touch_up(i as u64 + 1, at_time(i, ms, k)));
            }
        }
    }
    out
}

/// Three fingers tapped as hands tap them (ms): landing up to 100 apart and lifting up to 100
/// apart, in any order, each drifting a few points from where it landed as it presses and
/// lifts (the first, painting until the second lands, too).
fn hands_three_finger_taps() -> Vec<Vec<Finger>> {
    let finger = |lands, lifts, drift, towards| Finger { lands, lifts, drift, towards };
    vec![
        // nearly together
        vec![finger(0.0, 100.0, 0.0, 0.0), finger(8.0, 100.0, 1.0, 2.0), finger(16.0, 108.0, 1.0, 4.0)],
        // one after another, lifting in the order they landed
        vec![finger(0.0, 150.0, 3.0, 0.0), finger(30.0, 170.0, 3.0, 2.1), finger(60.0, 200.0, 3.0, 4.2)],
        // 100 apart down and up, the first lifting last
        vec![finger(0.0, 290.0, 5.0, 1.0), finger(50.0, 190.0, 4.0, 3.0), finger(100.0, 230.0, 5.0, 5.0)],
        // the last landing just before the first lifts
        vec![finger(0.0, 180.0, 2.0, 0.5), finger(90.0, 260.0, 4.0, 2.5), finger(100.0, 280.0, 2.0, 4.5)],
        // slow: all three down most of a tap's time
        vec![finger(0.0, 380.0, 6.0, 0.0), finger(60.0, 420.0, 6.0, 2.1), finger(100.0, 400.0, 6.0, 4.2)],
    ]
}

/// Whether what the touches asked for is one redo and nothing else a tap could do.
fn redid_once(out: &[TouchCommand]) -> bool {
    let redone = out.iter().filter(|c| **c == TouchCommand::Redo).count();
    redone == 1
        && !out
            .iter()
            .any(|c| matches!(c, TouchCommand::Undo | TouchCommand::StrokeEnded | TouchCommand::ToggleBars))
}

#[test]
fn three_fingers_tapped_as_hands_tap_them_redo_once() {
    // a desktop's screen at a point a pixel, a tablet's at two
    for scale in [1.0, 2.0] {
        for finger_paint in [true, false] {
            // Android hands every finger's move in one event; the desktop each its own
            for batched in [true, false] {
                for (k, fingers) in hands_three_finger_taps().iter().enumerate() {
                    let s = session();
                    s.set_display_scale(scale);
                    s.set_touch_settings(TouchSettings { finger_paint, ..TouchSettings::default() });
                    let out = real_tap(&s, fingers, 1.0, 8.0, batched);
                    assert!(
                        redid_once(&out),
                        "tap {k} at {scale}, painting {finger_paint}, batched {batched}: {out:?}"
                    );
                }
            }
        }
    }
}

#[test]
fn three_fingers_tapped_with_the_pen_near_or_a_panel_open_redo_once() {
    for (k, fingers) in hands_three_finger_taps().iter().enumerate() {
        // the pen held over the screen
        let s = session();
        s.set_touch_pen_hovering(true);
        assert!(redid_once(&real_tap(&s, fingers, 1.0, 8.0, true)), "tap {k}, the pen near");
        // a panel open: the first finger waits to be a tap or a drag, then the others land
        let s = session();
        s.set_touch_panel_open(true);
        let out = real_tap(&s, fingers, 1.0, 8.0, true);
        assert!(redid_once(&out) && !out.contains(&TouchCommand::PanelTapped), "tap {k}, a panel open: {out:?}");
    }
}

#[test]
fn three_fingers_that_went_somewhere_or_stayed_are_no_redo() {
    let finger = |lands, lifts, drift, towards| Finger { lands, lifts, drift, towards };
    // each gone past a tap's reach
    let s = session();
    let slid: Vec<Finger> = (0..3).map(|i| finger(30.0 * f64::from(i), 200.0, 20.0, 0.0)).collect();
    assert!(!real_tap(&s, &slid, 0.0, 8.0, true).contains(&TouchCommand::Redo));
    // all three down longer than a tap
    let s = session();
    let held: Vec<Finger> =
        (0..3).map(|i| finger(30.0 * f64::from(i), 400.0 + 10.0 * f64::from(i), 0.0, 0.0)).collect();
    assert!(!real_tap(&s, &held, 0.0, 8.0, true).contains(&TouchCommand::Redo));
    // turned off
    let s = session();
    s.set_touch_settings(TouchSettings { undo_taps: false, ..TouchSettings::default() });
    assert!(!real_tap(&s, &hands_three_finger_taps()[1], 1.0, 8.0, true).contains(&TouchCommand::Redo));
}
