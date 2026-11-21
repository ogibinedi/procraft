# Changelog

All notable changes in this Android Smartphone-focused fork (`ogibinedi/procraft`) are documented in this file.

## [0.0.3] - 2026-11-21 - Universal Drawing Tablet Support, Pen Pressure Calibration & Icon-Only Layer Actions

### Added
- **Universal External Drawing Tablet Support (`PenKeys.kt`, `CanvasSurfaceView.kt`, `PadSurfaceView.kt`)**:
  - Added brand-agnostic hardware detection (via `SOURCE_STYLUS`, external `AXIS_PRESSURE` motion range, and brand/firmware fallback covering XP-Pen, Huion, Wacom, Gaomon, Veikk, Ugee, Parblo, Xencelabs, Bosto, Artisul, Acepen, Hanvon, UC-Logic, etc.) plus a persistent **Drawing Tablet Mode** so external USB OTG and Bluetooth pen tablets always paint as pressure-sensitive styluses even when Android's HID driver reports mouse mode or when finger painting is off.
  - Added a synthetic stroke bridge for Android kernels/tablets that report pen nib clicks via `ACTION_BUTTON_PRESS(BUTTON_PRIMARY)` + `ACTION_HOVER_MOVE` instead of `ACTION_DOWN`/`ACTION_MOVE`, plus `ACTION_SCROLL` dial/wheel canvas zooming.
  - Added `onResolvePointerIcon` override on the canvas and Brush Studio pad to replace the system mouse arrow cursor with ProCraft's brush cursor or a precision crosshair while preserving the arrow over toolbars and menus.
- **Drawing Tablet & Pen Pressure Calibration Settings (`Actions > Prefs > Drawing Tablet & Pen` & `Pressure & Smoothing`)**:
  - Added `DrawingTabletPage` with a live connected device & real-time **Live Pen Pressure Test** bar (`0%..100%`), toggles for **Drawing tablet mode**, **Pen pressure sensitivity**, **Hide system arrow on canvas**, and **Pan & Zoom companion bar**.
  - Added calibrated hardware pressure normalization (handles non-0..1 HID ranges such as 8192-level tablets and missing pressure-axis fallbacks) plus user-adjustable **Pressure sensitivity** (`25%..300%`), **Minimum pressure** (`0%..50%`), and **Pressure smoothing** (`0%..100%`) sliders in both `Drawing Tablet & Pen` and `Pressure & Smoothing`.
- **Unit Tests (`PenKeysTest.kt`)**:
  - Added unit tests covering universal drawing tablet detection, multi-range HID pressure normalization, sensitivity/floor calibration, pressureless fallback, and drawing tablet touch settings.

### Changed
- **Icon-Only Layer Swipe Actions (`LayersPanel.kt`, `LayerBlendOptions.kt`, `Icons.kt`)**:
  - Replaced text labels (`Lock`/`Unlock`, `Duplicate`, `Clear`/`Delete`) on layer swipe-left buttons with compact `56dp` rounded icon buttons (`PcIcons.Lock`, new `PcIcons.Unlock`, `PcIcons.Duplicate`, `PcIcons.SwipeClear`, `PcIcons.Trash`), preserving full `contentDescription` labels and leaving more of the layer row visible on narrow smartphone screens.

---

## [0.0.2] - 2026-11-21 - Cleanup & Smartphone Optimization

### Changed & Optimized
- **Zero-Recomposition Bottom Sheet Swipe-Down (`ui/components/BottomSheetPanel.kt`)**:
  - Separated upward height-expansion state (`dragUpPx`) from downward dismiss translation (`dragDownPx`) so swiping down to dismiss a bottom sheet updates only Compose's layout placement phase (`.offset { ... }`) without recomposing heavy Brush/Layer/Color panel trees.
  - Cached density-derived drag thresholds, pivot offsets, and spring specs with `remember`.
- **Rust GPU Visibility Hygiene (`crates/gpu/src/canvas/`)**:
  - Downgraded 238 internal `pub(crate)` helpers/methods in `pipelines.rs`, `ops.rs`, `composite.rs`, and `readback.rs` to `pub(super)` (keeping `Pipelines::new` and `buffer_init` as `pub(crate)`) and narrowed `ReadyToPark` internal fields, eliminating `private_interfaces` compiler warnings in `procraft-gpu`.
- **Kotlin Import Hygiene**:
  - Removed 1,489 unused and duplicate explicit imports across the 30 modularized Kotlin files, `MainActivity.kt`, and `BottomSheetPanel.kt`.
- **Workspace Hygiene**:
  - Cleaned up temporary local CI log notes and added `temuan.txt` to `.gitignore`.

---

## [0.0.1] - 2026-11-21 - Initial Android Smartphone Release

### Added
- **Signed Release Pipeline & GitHub Releases (`.github/workflows/release.yml`)**:
  - Configured single-job release builds publishing `procraft-0.0.1-android-arm64.apk`, `procraft-0.0.1-android-legacy-armeabi-v7a.apk`, and `SHA256SUMS.txt` directly to GitHub Releases with zero GitHub Actions artifact storage overhead.
- **Smartphone Responsive Layout System (`ui/theme/ResponsiveLayout.kt`)**:
  - Added `ResponsiveLayout.isSmartphoneLayout(maxWidth, maxHeight)` to detect portrait smartphones (`< 600dp`), landscape smartphones (`height < 500dp`), and compact split-screen/foldable viewports.
  - Added `ResponsiveLayout.brushSetsColumnWidth(panelWidth)` and `ResponsiveLayout.colorPickerScale(panelWidth)` to scale the Brush Library sidebar and 364dp Color Disc/Classic square cleanly down to `320dp` screens.
  - Added `ResponsiveLayout.sheetFraction(panel, expanded, isLandscape)` for peekable and expandable bottom sheet heights.
- **Procreate Pocket-Style Bottom Sheet Navigation (`ui/components/BottomSheetPanel.kt`)**:
  - Created `SmartphoneBottomSheet` with drag handle, peek/expanded states, drag-to-dismiss, and optional scrim so canvas tools (`Brushes`, `Layers`, `Color`, `Actions`, `Adjustments`) open from the bottom of the phone screen instead of overflowing as tablet popovers.
- **Collapsible Canvas SideBar (`ui/chrome/Chrome.kt`)**:
  - Added a slide-out collapse toggle and edge pull-tab for the brush size/opacity/undo/redo sidebar on smartphones, plus automatic hiding while a bottom sheet panel is open.
- **Smartphone Brush Studio Horizontal Tabs (`ui/studio/BrushStudio.kt`)**:
  - Added `StudioHorizontalTabs` (`54dp` bar with `44dp` pill tabs and a `1st Brush / 2nd Brush` switcher for dual brushes) placed between a full-width Drawing Pad and full-width Settings column on smartphones.
- **Unit Tests (`android/app/src/test/.../ui/SmartphoneResponsiveLayoutTest.kt`)**:
  - Added unit tests covering smartphone posture detection, adaptive brush set column widths, color picker scaling, and bottom sheet height fractions.

### Fixed
- Resolved Rust shader relative `include_str!("../shaders/...")` paths and redundant re-exports in `crates/gpu/src/canvas/`.
- Fixed `follow_layers` visibility (`pub(crate)`) for `scene3d` re-export in `crates/core/src/scene3d/painting.rs`.
- Resolved Kotlin cross-file visibility (`StudioKept`, `enumPref`, `FormatsPlace`, `awaitLibraryPinch`, `track`), package-scope collisions (`BrushSwipeGap`, `LibraryPickerInset`, `LibraryPickerMargin`, `CanvasViewModel.PEN_BUTTONS`), explicit `CanvasViewModel.Companion` imports across all 12 action files, bound references inside `withContext`, and `Modifier.horizontalScroll` chaining in `BrushStudio.kt`.

### Changed
- **Focused Repository Exclusively on Android Smartphone + Rust Engine**:
  - Removed `desktop/`, `ios/`, `packaging/`, unused `crates/core/src/layer.rs`, and desktop icon generators.
  - Updated `Cargo.toml`, `crates/core/Cargo.toml`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`, and `tools/licenses.py`.
- **Adapted Secondary Tools, Editors, and Floating Panels for All Smartphone Sizes**:
  - `ToolHeader.kt`: Responsive header layout on screens `< 540dp` so multi-button capsules (`Cancel`, `Settings`, `Reset`) and `Done` never collide with the header title.
  - `SourceEditorUi.kt`: Responsive preview square, 2-column `RepeatPanel`, and 2-row `SourceLibrary` header with adaptive 3-column grid cells on smartphones.
  - `LightingStudioUi.kt` & `ModelPictureUi.kt`: Responsive top action bar, clamped `EnvironmentPopover`, and adaptive 6-button 3D picture toolbar.
  - `ReferenceUi.kt` & `CanvasViewModel.kt`: Compact Picture-in-Picture (`210dp x 170dp`) default size and top-right placement for the Reference Window on smartphones.
  - `CropUi.kt`, `GuideUi.kt`, `TextUi.kt`, `AdjustmentsUi.kt`, `AdjustmentsEffectsUi.kt`: Responsive panel widths (`toolbarWidth`), `Modifier.fitWidth()` on `KindsSwitch`, and adaptive `TextStyleSheet` height and card proportions.
  - `QuickMenuUi.kt`, `CompanionUi.kt`, `CopyPasteUi.kt`, `AlertUi.kt`: Clamped radial QuickMenu button reach, single-touch companion bar, 3-finger Copy & Paste bar, and alert/export progress dialogs to fit `320dp..430dp` screens.
  - `CanvasInfoUi.kt`, `GestureControlsUi.kt`, `GalleryPreviewNewCanvas.kt`, `AnimatedExportUi.kt`, `ColorValueHistory.kt`, `BrushStudioWidgets.kt`: Scrollable `64dp` compact icon rail, responsive signature pad and clear button, scrollable `AboutApp` screen, flexible `ValueRow` sliders, smart `BesidePanel` hex pad placement, and left-opening `SliderKeypad` on smartphones.
- **Modularized Kotlin & Rust Codebase (DRY & Clean Architecture)**:
  - Reorganized `android/app/src/main/java/io/github/artcraftandroid/procraft/` into `canvas/{input,media,surface,util,viewmodel}` and `ui/{chrome,components,export,gallery,panels,studio,theme,tools}`.
  - Split `CanvasViewModel.kt` (9,287 lines) into 14 focused files in `canvas/viewmodel/`.
  - Split the 7 largest Compose UI files into 16 modular files across `ui/gallery/`, `ui/panels/`, and `ui/studio/`.
  - Split `crates/gpu/src/canvas.rs` (14,907 lines) into `crates/gpu/src/canvas/{mod.rs,ops.rs,composite.rs,readback.rs,pipelines.rs,tests.rs}`.
  - Split `crates/core/src/lib.rs` into `session_strokes.rs`, `session_view.rs`, and `session_controls.rs`.
  - Split `crates/engine/src/stroke.rs` (6,317 lines) into `crates/engine/src/stroke/{mod.rs,builder.rs,tests.rs}`.
  - Split `crates/core/src/scene3d.rs` (6,209 lines) into `crates/core/src/scene3d/{mod.rs,painting.rs,tests.rs}`.
  - Split `crates/core/src/touch.rs` (4,911 lines) into `crates/core/src/touch/{mod.rs,modes.rs,tests.rs}`.
