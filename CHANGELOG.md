# Changelog

All notable changes in this Android Smartphone-focused fork (`ogibinedi/procraft`) are documented in this file.

## [Unreleased] - Android Smartphone Edition

### Added
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
- **CI/CD Automatic APK Artifact Upload (`.github/workflows/ci.yml`)**:
  - Configured GitHub Actions CI to run on every `push` to `main`, `pull_request`, and `workflow_dispatch`, uploading `app-release.apk` as the `procraft-android-apk` build artifact.
- **Unit Tests (`android/app/src/test/.../ui/SmartphoneResponsiveLayoutTest.kt`)**:
  - Added unit tests covering smartphone posture detection, adaptive brush set column widths, color picker scaling, and bottom sheet height fractions.

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
