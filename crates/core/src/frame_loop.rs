//! The render thread's loop, apart from the window it draws into: it sleeps until a frame is asked
//! for (or one is due), draws it, and presents it. Nothing changing on screen, it draws nothing and
//! doesn't wake; the app away, it draws nothing at all; a window that gives no image is asked again
//! less and less often, never in a tight loop.
// (only the Android render thread and the tests use it)
#![cfg_attr(not(any(target_os = "android", test)), allow(dead_code))]

use std::time::{Duration, Instant};

use procraft_engine::profile::Profile;
use procraft_gpu::{Gpu, wgpu};

use crate::Shared;
use crate::activity::Counts;

/// What the render loop draws into: the window's surface, or a test's stand-in for one.
pub(crate) trait Screen {
    /// An image of the window, drawn into and then presented.
    type Image;

    /// Configures for `size` pixels (asking for wide colour if `wide`): the format drawn in and the
    /// colours the screen shows, or none if it can't be (yet).
    fn configure(&mut self, gpu: &Gpu, size: (u32, u32), wide: bool) -> Option<(wgpu::TextureFormat, Profile)>;

    /// The next image to draw into (waiting for one, if the window has none free).
    fn acquire(&mut self) -> Acquired<Self::Image>;

    /// Where `image` is drawn.
    fn view(&self, image: &Self::Image) -> wgpu::TextureView;

    /// Shows `image`.
    fn present(&mut self, image: Self::Image);
}

/// What asking the window for an image gave.
pub(crate) enum Acquired<T> {
    Ready(T),
    /// One that shows, though the surface would do better configured again.
    Suboptimal(T),
    /// None: the surface must be configured again first.
    Outdated,
    /// None for now (a timeout, the window out of sight).
    Unavailable,
}

/// How long the loop waits before asking again after `failures` asks in a row gave nothing: a few
/// milliseconds at first, doubling to a quarter of a second, so a window that gives no image for
/// a while (or a surface that can't be configured) is asked four times a second, not 250.
pub(crate) fn retry_wait(failures: u32) -> Duration {
    (RETRY_FIRST * 2u32.saturating_pow(failures.min(8))).min(RETRY_MOST)
}

const RETRY_FIRST: Duration = Duration::from_millis(4);
const RETRY_MOST: Duration = Duration::from_millis(250);

/// A frame that holds the canvas longer than this is logged: touches wait on it.
const SLOW_FRAME: Duration = Duration::from_millis(200);

/// Draws frames into `screen` as they are asked for until the session says stop; `presented` is
/// called after each frame is shown.
pub(crate) fn draw_loop<S: Screen>(shared: &Shared, screen: &mut S, mut presented: impl FnMut()) {
    // the format and colours the surface was last configured for, and whether it asked for wide
    // colour
    let mut configured: Option<(wgpu::TextureFormat, Profile)> = None;
    let mut wide = false;
    let mut last_present: Option<Instant> = None;
    // when the last frame was begun
    let mut last_frame: Option<Instant> = None;
    let started = Instant::now();
    // asks in a row that gave no image (or no configured surface)
    let mut failures = 0u32;
    // the size the surface was configured again at for an image that said it would do better: a
    // window whose images keep saying so (one turned from its natural way on some systems) isn't
    // configured again every frame
    let mut suboptimal_at: Option<(u32, u32)> = None;
    loop {
        let (size, reconfigure, wants_wide) = {
            let mut st = shared.lock();
            loop {
                // nothing is drawn while the app is away, and a device lost draws nothing again:
                // there is only its stopping to wait for
                // (pen input waiting for the canvas asks for one)
                let wanted = |st: &crate::State| st.needs_frame || shared.input_waiting();
                while !st.stop && (st.hidden || !wanted(&st) || st.gpu.as_ref().is_some_and(Gpu::is_lost)) {
                    // a frame due later (the selection's bands drifting) comes then, unless asked
                    // sooner
                    let due = st.frame_due.filter(|_| !st.hidden && !st.gpu.as_ref().is_some_and(Gpu::is_lost));
                    match due {
                        Some(at) => {
                            if shared.wake.wait_until(&mut st, at).timed_out() {
                                st.frame_due = None;
                                st.needs_frame = true;
                            }
                        }
                        None => shared.wake.wait(&mut st),
                    }
                    Counts::add(&shared.counts.wakeups);
                }
                // no sooner after the last than the screen shows a new one: a frame drawn sooner
                // would never be seen (while input comes faster than the screen refreshes), and on
                // a slow GPU it would hold up the one that is. What comes meanwhile goes in it.
                // (Work done a part a render, remaking the caches, goes on as fast as it can.)
                let working = st.canvas.as_ref().is_some_and(procraft_gpu::Canvas::wants_render);
                let spacing = st.frame_spacing.filter(|_| !working);
                let next = last_frame.zip(spacing).map(|(at, spacing)| at + spacing);
                match next {
                    Some(at) if !st.stop && Instant::now() < at => _ = shared.wake.wait_until(&mut st, at),
                    _ => break,
                }
            }
            last_frame = Some(Instant::now());
            shared.took_back();
            if st.stop {
                break;
            }
            (st.window_size, std::mem::replace(&mut st.reconfigure, false), st.wide_colour())
        };
        // configured again too when the canvas comes to want wide colour or stops (its profile
        // changed, or the app's setting)
        if reconfigure || configured.is_none() || wants_wide != wide {
            let st = shared.lock();
            let Some(gpu) = st.gpu.as_ref() else { break };
            configured = screen.configure(gpu, size, wants_wide);
            wide = wants_wide;
        }
        let Some((format, profile)) = configured else {
            retry(shared, &mut failures);
            continue;
        };
        // wait for the next image before looking at the input, so the frame shows the latest
        let image = match screen.acquire() {
            Acquired::Ready(image) => image,
            Acquired::Suboptimal(image) => {
                if suboptimal_at != Some(size) {
                    suboptimal_at = Some(size);
                    shared.lock().reconfigure = true;
                }
                image
            }
            Acquired::Outdated => {
                shared.lock().reconfigure = true;
                retry(shared, &mut failures);
                continue;
            }
            Acquired::Unavailable => {
                retry(shared, &mut failures);
                continue;
            }
        };
        failures = 0;
        let view = screen.view(&image);
        {
            let mut guard = shared.lock();
            let st = &mut *guard;
            let held = Instant::now();
            // the window changed size while the image was awaited (the device turned): the view is
            // already for the new size, so this image is let go and the surface configured again
            if st.window_size != size {
                continue;
            }
            // the pen input that came while the canvas was held goes in this frame
            shared.take_queued_input(st);
            st.draw_frame(&view, format, size, profile, started);
            let now = Instant::now();
            if let Some(prev) = last_present {
                st.frame_interval_ms = now.duration_since(prev).as_secs_f32() * 1000.0;
            }
            last_present = Some(now);
            // everything else waits on the lock meanwhile
            if held.elapsed() > SLOW_FRAME {
                log::warn!("a frame held the canvas {} ms", held.elapsed().as_millis());
            }
            // a second display showing the canvas draws it again now
            shared.wake.notify_all();
            // whoever waited on the lock meanwhile (a touch) has it next, before this thread's
            // next frame
            parking_lot::MutexGuard::unlock_fair(guard);
        }
        screen.present(image);
        Counts::add(&shared.counts.frames);
        // what the frame laid (a stroke committed) may be new to save
        shared.nudge.nudge();
        presented();
    }
}

/// Waits before the next ask after one that gave nothing (see [`retry_wait`]); a frame asked for
/// or the loop's stop ends the wait sooner.
fn retry(shared: &Shared, failures: &mut u32) {
    Counts::add(&shared.counts.retries);
    let wait = retry_wait(*failures);
    *failures = failures.saturating_add(1);
    if *failures == RETRIES_LOGGED {
        log::warn!(
            "the window has given no image {RETRIES_LOGGED} times in a row: asking every {} ms",
            RETRY_MOST.as_millis()
        );
    }
    let mut st = shared.lock();
    if !st.stop {
        let _ = shared.wake.wait_for(&mut st, wait);
    }
}

/// Asks in a row that gave nothing after which the log says so (once each run of them).
const RETRIES_LOGGED: u32 = 50;
