<p align="center">
  <img src="assets/app-icon/procraft-1024.png" alt="ProCraft app icon" width="128">
</p>

<h1 align="center">ProCraft (Android Smartphone Edition)</h1>

<p align="center">
  <b>A dedicated fork of ProCraft engineered specifically for Android smartphones (inspired by Procreate Pocket), powered by a Rust painting engine, a wgpu GPU renderer, and a clean, modular Kotlin / Jetpack Compose UI.</b><br>
  Pressure-sensitive brushes, layers with blend modes, live adjustments, selection and transform, animation assist, and 3D painting, adapted to run comfortably on every Android phone screen.
</p>

<p align="center">
  <a href="https://github.com/ogibinedi/procraft/releases"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="60"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/ogibinedi/procraft"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60"></a>
</p>

<p align="center">
  <img alt="Platform: Android Smartphones" src="https://img.shields.io/badge/platform-Android%20Smartphones-3DDC84?style=flat&logo=android&logoColor=white">
  <img alt="Rust engine" src="https://img.shields.io/badge/engine-Rust-lightblue?style=flat&logo=rust&logoColor=white">
  <img alt="GPU renderer: wgpu" src="https://img.shields.io/badge/renderer-wgpu-lightblue?style=flat">
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/UI-Kotlin%20%C2%B7%20Jetpack%20Compose-blue?style=flat">
  <img alt="Minimum: Android 8 (API 26)" src="https://img.shields.io/badge/min-Android%208%20%28API%2026%29-blue?style=flat">
  <img alt="Target: Android 16 (API 36)" src="https://img.shields.io/badge/target-Android%2016%20%28API%2036%29-blue?style=flat">
  <img alt="License: MIT OR Apache-2.0" src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue?style=flat">
</p>

<p align="center">
  <a href="#screenshots">Screenshots</a> ·
  <a href="#about-this-fork">About This Fork</a> ·
  <a href="#smartphone-ui--architecture">Smartphone UI &amp; Architecture</a> ·
  <a href="#features">Features</a> ·
  <a href="#project-structure">Project Structure</a> ·
  <a href="#building--releases">Building &amp; Releases</a> ·
  <a href="PROGRESS.md">Progress</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

## Screenshots

<table>
  <tr>
    <td align="center"><img src="screenshot/sc_04.png" alt="Canvas Workspace with Collapsible SideBar" width="210"><br><sub><b>Canvas &amp; Collapsible SideBar</b></sub></td>
    <td align="center"><img src="screenshot/sc_12.png" alt="Brush Library Bottom Sheet" width="210"><br><sub><b>Brush Library</b></sub></td>
    <td align="center"><img src="screenshot/sc_11.png" alt="Layers Bottom Sheet" width="210"><br><sub><b>Layers</b></sub></td>
    <td align="center"><img src="screenshot/sc_10.png" alt="Color Disc Picker" width="210"><br><sub><b>Color Disc</b></sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshot/sc_13.png" alt="Selection Toolbar" width="210"><br><sub><b>Selection Toolbar</b></sub></td>
    <td align="center"><img src="screenshot/sc_14.png" alt="Adjustments Bottom Sheet" width="210"><br><sub><b>Adjustments</b></sub></td>
    <td align="center"><img src="screenshot/sc_15.png" alt="Actions &amp; Drawing Tablet Preferences" width="210"><br><sub><b>Actions &amp; Prefs</b></sub></td>
    <td align="center"><img src="screenshot/sc_01.png" alt="Smartphone Gallery Grid" width="210"><br><sub><b>Gallery</b></sub></td>
  </tr>
</table>

<details>
<summary><b>More UI Screenshots</b> (Gallery Options, New Canvas Presets, Classic/Harmony/Value Color Pickers &amp; Palettes)</summary>
<br>
<table>
  <tr>
    <td align="center"><img src="screenshot/sc_02.png" alt="Gallery More Options" width="210"><br><sub><b>Gallery Options</b></sub></td>
    <td align="center"><img src="screenshot/sc_03.png" alt="New Canvas Presets" width="210"><br><sub><b>New Canvas Presets</b></sub></td>
    <td align="center"><img src="screenshot/sc_09.png" alt="Color Classic Picker" width="210"><br><sub><b>Colors · Classic</b></sub></td>
    <td align="center"><img src="screenshot/sc_08.png" alt="Color Harmony Wheel" width="210"><br><sub><b>Colors · Harmony</b></sub></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshot/sc_07.png" alt="Color Value Sliders" width="210"><br><sub><b>Colors · Value (HSB/RGB/Hex)</b></sub></td>
    <td align="center"><img src="screenshot/sc_05.png" alt="Color Palettes Compact View" width="210"><br><sub><b>Palettes · Compact</b></sub></td>
    <td align="center"><img src="screenshot/sc_06.png" alt="Color Palettes Cards View" width="210"><br><sub><b>Palettes · Cards</b></sub></td>
    <td></td>
  </tr>
</table>
</details>

---

## About This Fork

Upstream ProCraft was originally designed purely for large-screen tablets and desktop platforms. **This repository (`ogibinedi/procraft`) is a dedicated fork refactored and redesigned to focus 100% on Android smartphones**:

1. **Smartphone-First Navigation & Ergonomics (Procreate Pocket Style)**:
   - **Draggable & Peekable Bottom Sheets**: Heavy tablet popovers (Brush Library, Layers, Color Picker, Actions, and Adjustments) automatically transform into responsive bottom sheets (`SmartphoneBottomSheet`) on smartphone screens (`width < 600dp` or landscape `height < 500dp`).
   - **Collapsible SideBar**: The brush size/opacity and undo/redo sidebar can collapse to a subtle edge pull-tab so 100% of the phone viewport remains open for painting, and automatically hides while a bottom sheet is open.
   - **Smartphone Brush Studio**: Full-width Drawing Pad on top, horizontally scrollable pill tabs (`>= 44dp` touch targets, including a dual-brush part switcher) in the middle, and full-width settings below, with smart left-opening numeric/curve keypads (`SliderKeypad`) that never clip off the right edge of the phone screen.
   - **Compact Secondary Tools**: Picture-in-Picture (PiP) default sizing for the Reference Window (`210dp x 170dp`), responsive radial QuickMenu, responsive Shape/Grain Source Editor & Source Library, responsive 3D Lighting Studio, and responsive Canvas Info & Gesture Controls.
2. **Android-Only Workspace**:
   - Removed legacy `desktop/`, `ios/`, and desktop `packaging/` directories so build times, dependencies, and maintenance focus exclusively on Android + Rust.
3. **Clean, Modular, DRY Codebase**:
   - Split monolithic Kotlin God Files (`CanvasViewModel.kt` from 9,287 lines into 14 focused domain action files, plus 7 large Compose files into 16 modular UI files) and organized packages into clean domain folders.
   - Split monolithic Rust modules (`crates/gpu/src/canvas.rs`, `crates/core/src/lib.rs`, `crates/engine/src/stroke.rs`, `crates/core/src/scene3d.rs`, and `crates/core/src/touch.rs`) into cohesive submodules.

See [PROGRESS.md](PROGRESS.md) and [CHANGELOG.md](CHANGELOG.md) for the complete milestone breakdown.

---

## Smartphone UI & Architecture

| Smartphone Adaptation | Implementation Details |
|---|---|
| **Responsive Breakpoints** | `ResponsiveLayout.isSmartphoneLayout(maxWidth, maxHeight)` detects portrait phones (`320dp..599dp`), landscape phones (`height < 500dp`), and foldable cover screens. |
| **Bottom Sheet Panels** | `SmartphoneBottomSheet` supports drag-to-expand, peek mode (keeping the canvas visible while tweaking colors or layers), and drag-down to dismiss. |
| **Adaptive Pickers & Lists** | `ResponsiveLayout.brushSetsColumnWidth` and `ResponsiveLayout.colorPickerScale` scale the Brush Library sidebar and 364dp Color Disc/Classic square to fit `320dp..430dp` phones without horizontal clipping. |
| **Gallery Grid** | Uses a 2-column grid on portrait smartphones (`< 600dp`) with readable artwork titles and dimensions, and a compact top bar on narrow screens (`< 430dp`). |
| **Touch Target Hygiene** | Interactive buttons, tabs, and close targets adhere to a `>= 44dp` touch target budget. |

---

## Features

| Area | Status on Android Smartphone |
|---|---|
| **Canvas & Gestures**: adaptive top bar, collapsible sidebar with `44dp` Undo/Redo edge dock, auto-hide UI while drawing, haptic feedback, hardware volume button shortcuts, auto-fading Zoom % / Rotation ° HUD & optional Quick Flip button, multi-touch gestures, and built-in Gesture Guide | ✅ Ready |
| **Drawing Tablets & Styluses**: universal USB OTG & Bluetooth drawing tablet support (XP-Pen, Huion, Wacom, Gaomon, Veikk, Ugee, Parblo, Xencelabs, etc.), per-device auto-gain pressure calibration (`PenPressureLearner`), one-tap **Calibrate pressure**, live pressure test bar, and top-level navigation pointer overlay | ✅ Ready |
| **Brush Engine & Brush Studio**: shape and grain sources, stroke path, stabilization, taper, rendering and glazes, wet mix, colour dynamics, pressure and tilt; horizontal-tab smartphone Brush Studio and responsive Source Editor | ✅ Ready |
| **Layers**: blend modes, opacity, alpha lock, clipping masks, layer masks, groups, merge, reference layer, keyboard-safe pinned rename bar (layers/groups/masks), icon-only compact swipe actions, cross-artwork drag & drop, and off-GPU LZ4 layer parking for large artworks | ✅ Ready |
| **Colour**: responsive disc, classic, harmony, value sliders (with smart-positioned hex keypad), simultaneous Color History + Active Palette on smartphone sheets, draggable smartphone **Floating Color** companion, eyedropper loupe, and ColorDrop / Recolor fill | ✅ Ready |
| **Adjustments**: HSB, colour balance, curves, gradient map, Gaussian/motion/perspective blurs, noise, sharpen, bloom, glitch, halftone, chromatic aberration, liquify, clone, and interactive **Recolor** | ✅ Ready |
| **Selection & Transform**: automatic, freehand, rectangle, ellipse selection with feather; freeform, uniform, distort, and warp transform with snapping and numeric keypad | ✅ Ready |
| **Guides, QuickShape, Editable Shapes, & Text**: responsive smartphone QuickShape (`Edit`/`Done`, high-contrast handles), **Insert Shape** composer & persistent **Editable Shape Layers** (8 shapes, independent Fill & Stroke colors, `0.1–500 px` decimal stroke width, Transform sync, `Edit Shape` / `Rasterize Shape`), 2D/isometric/perspective/symmetry drawing guides with Assisted Drawing, and rich on-canvas text | ✅ Ready |
| **Animation Assist, Page Assist, & PiP Reference**: frame timeline with onion skins, animated GIF/PNG/MP4/HEVC export, and compact Picture-in-Picture Reference Window | ✅ Ready |
| **Gallery, Backup & Time-Lapse**: 2-column smartphone grid, search & 5 sort modes, icon-only swipe actions, 30-day **Recently Deleted (Trash)**, full `.procraft-backup` ZIP backup/restore, stacks, custom presets, 1-tap Save to Gallery (PNG/JPEG/lossless WebP), import/share (ProCraft, Procreate, PSD, PDF, PNG, JPEG, WebP, TIFF, GIF, MP4), and 1080p/4K time-lapse replay/export | ✅ Ready |
| **In-App Updates & Changelog**: rate-limit-resistant GitHub Releases updater (Atom feed + REST fallback), SHA-256 verification (`SHA256SUMS.txt`), ABI auto-detection (`ARM64` / `ARMv7`), optional Beta/RC channel, and offline expandable **Changelog** viewer in About & Help | ✅ Ready |
| **3D Painting**: OBJ and USDZ models, texture sets, mesh painting, responsive Lighting Studio, and animated turntable export | ✅ Ready |
| **Plugins**: `.procraft-plugin` WGSL filters, sandboxed WebAssembly hooks, and custom brush engines ([docs/plugins.md](docs/plugins.md)) | ✅ Ready |

---

## Project Structure

```text
procraft/
├── android/
│   ├── bindgen/                  # UniFFI Kotlin bindings generator
│   └── app/src/main/java/io/github/artcraftandroid/procraft/
│       ├── canvas/
│       │   ├── input/            # Pen/touch prediction and motion event bridging
│       │   ├── media/            # Time-lapse encoder, stroke audio, and video export
│       │   ├── surface/          # Vulkan/GLES canvas & drawing pad SurfaceViews
│       │   ├── util/             # Async polling and color space utilities
│       │   └── viewmodel/        # Modularized CanvasViewModel + 13 domain action files
│       ├── gallery/              # ArtworkStore, search/sort, 30-day trash, backup/restore, WebP, presets, palettes
│       ├── update/               # GitHub Releases in-app updater (Atom feed + REST fallback, SHA-256, installer)
│       └── ui/
│           ├── chrome/           # AppRoot, TopBar, collapsible SideBar, QuickMenu, Companion
│           ├── components/       # SmartphoneBottomSheet, Popover, ToolHeader, AlertUi
│           ├── export/           # Animation timeline, animated export, time-lapse replay
│           ├── gallery/          # Gallery grid, drag/share, custom canvas, canvas info, gestures
│           ├── panels/           # Brushes, Layers, Color, Actions, and Adjustments panels
│           ├── studio/           # BrushStudio, StudioWidgets, SourceEditor, LightingStudio
│           ├── theme/            # ResponsiveLayout, Theme, Motion, Icons, Frost
│           └── tools/            # Selection, Transform, Crop, Guides, Text, Reference, Eyedropper
└── crates/
    ├── engine/                   # Pure Rust painting engine: document, brushes, stroke/, view
    ├── gpu/                      # wgpu GPU renderer: canvas/ (mod, ops, composite, readback, pipelines)
    └── core/                     # Session state, scene3d/, touch/, project I/O, and UniFFI exports
```

---

## Building & Releases

### Option 1: Download Signed Release APK (Recommended)
Signed release APKs (`arm64-v8a` for modern smartphones and `armeabi-v7a` for legacy 32-bit devices) along with `SHA256SUMS.txt` are published directly to [GitHub Releases](https://github.com/ogibinedi/procraft/releases) whenever a version tag (`v*`) is pushed.

### Option 2: GitHub Actions CI/CD
- **Release workflow (`.github/workflows/release.yml`)**: Triggered on version tags (`v0.0.1`, `v0.0.2`, ...). Compiles `procraft-core` via `cargo-ndk`, builds and signs the release APKs using repository secrets, verifies APK signatures and SHA-256 checksums, and publishes directly to GitHub Releases without using Actions Artifact storage quota.
- **CI workflow (`.github/workflows/ci.yml`)**: Runs Rust `clippy` & unit tests, Android lint, Kotlin unit tests, release APK build, and 16 KB page-size alignment verification on pull requests and manual triggers (**Actions > CI > Run workflow**).

### Option 3: Local Build
*One Gradle command builds the whole app: it compiles the Rust engine with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) and generates the Kotlin bindings.*

1. Clone this repository:
   ```bash
   git clone git@github.com:ogibinedi/procraft.git
   cd procraft
   ```
2. Make sure you have at least 6 GB of free disk space and the required toolchain:
   - `rustup` with targets `aarch64-linux-android` and `x86_64-linux-android`
   - `cargo install cargo-ndk --version "^4" --locked`
   - Android SDK (API 36/37) and NDK `29.0.14206865`
   - JDK 17 or newer
3. Build the release APK:
   ```bash
   cd android
   ./gradlew assembleRelease -Pprocraft.abis=arm64-v8a
   ```

Run `cargo test --workspace` to execute the Rust unit test suites across `procraft-engine`, `procraft-gpu`, and `procraft-core`.

---

## License

[MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE), at your option.

- **Smartphone Fork:** Maintained by **Igoy Creative Lab.** Based on upstream ProCraft by **ArtCraft Team & contributors**.
- **Third-party material:** the Inter font, Lucide icons, and bundled lighting panoramas are listed in [NOTICE](NOTICE) and [ATTRIBUTION.md](ATTRIBUTION.md).
