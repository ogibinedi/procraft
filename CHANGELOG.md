# Changelog

All notable changes in this Android Smartphone-focused fork (`ogibinedi/procraft`, maintained by **Igoy Creative Lab.**) are documented in this file.

## [0.0.7] - 2026-11-24 - In-App Changelog, Smartphone QuickShape Polish & Editable Shape Layers (Stable Release)

Promotes all `v0.0.7-rc.1` through `v0.0.7-rc.3` milestones from `development` into `main`, plus the bundled `v0.0.7` stable entry in the offline in-app Changelog viewer.

### Highlights
- **Offline In-App Changelog Viewer (`AppChangelog.kt`, `ChangelogUi.kt`)**: Expandable per-version release history (`v0.0.1`–`v0.0.7`) accessible from **Gallery → About ProCraft** and **Canvas → Actions → Help**, supporting Dark/Light themes (WCAG AA contrast), **Expand all / Collapse all**, and a direct GitHub `CHANGELOG.md` link.
- **Smartphone QuickShape Polish (`QuickShapeUi.kt`, `AppRoot.kt`, `PocketErgonomics.kt`)**: Responsive `>= 44dp` QuickShape bar with an explicit **Edit / Done** pill, horizontally scrollable choice strip that never clips on `320dp–430dp` screens, fixed Light Mode unselected chip contrast, enlarged high-contrast edit handles with dark halos, Android Back commit support, snap haptics, and 2nd-finger QuickShape lock documentation in **Gesture guide**.
- **Insert Shape Composer & Editable Shape Layers (`InsertShapeGeometry.kt`, `InsertShapeRenderer.kt`, `EditableShapeStore.kt`, `InsertShapeUi.kt`, `LayersPanel.kt`, `LayerBlendOptions.kt`)**:
  - 8 geometric shapes (*Rectangle*, *Rounded*, *Circle*, *Triangle*, *Polygon* 5–12 sides, *Star* 4–12 points + inner depth, *Arrow*, *Line*) via **Actions → Add → Insert shape** with live checkerboard preview.
  - Independent **Fill** and **Stroke** toggles and color pickers (*Use Brush Color*, color history, 10 presets, HSV sliders, `#RRGGBB` input, and **Swap colors**), decimal **Stroke size `0.1–500 px`** (log slider, adaptive steppers, and direct decimal entry), and straight-RGBA8 Porter-Duff compositing.
  - Persistent per-artwork `<artworkDir>/shapes.json` metadata with **`Shape`** layer subtitle, **`Edit Shape`** and **`Rasterize Shape`** options in Layers, and automatic **Transform** move/scale/rotate sync.
- **Verification**: 50 unit test suites / 216 unit tests passing with 0 failures.

---

## [0.0.7-rc.3] - 2026-11-23 - Independent Fill/Stroke (0.1–500 px) & Editable Shape Layers

### Added
- **Independent Fill & Stroke Controls + Decimal `0.1–500 px` Stroke Width (`InsertShapeGeometry.kt`, `InsertShapeRenderer.kt`, `InsertShapeUi.kt`)**:
  - Replaced single-style mode with independent **Fill** and **Stroke** toggles and separate color pickers (active brush match, canvas color history, 10 presets, HSV sliders, `#RRGGBB` hex input, and 1-tap **Swap colors**) so shapes can use Fill only, Stroke only, or two-color Fill & Stroke simultaneously.
  - Added exact **Stroke size** control from **`0.1 px` to `500 px`** via a logarithmic slider, adaptive `−` / `+` precision steppers (`0.1 px` steps below `1 px`), and direct decimal keyboard input (supporting `.` and `,`, keyboard-safe via `rememberKeyboardReach()`).
  - Upgraded shape rasterization to straight-alpha Porter-Duff `SRC_OVER` compositing of independent Fill and Stroke alpha masks for fringe-free `0.1 px` hairlines and sRGB / Display P3 channel fidelity.
- **Editable Shape Layers (`EditableShapeStore.kt`, `InsertShapeRenderer.kt`, `LayersPanel.kt`, `LayerBlendOptions.kt`, `SelectionTransformActions.kt`, `AnimationLayerActions.kt`)**:
  - Persisted shape specs and canvas-space placement (`centerX`, `centerY`, `width`, `height`, `rotationDegrees`) per artwork in `<artworkDir>/shapes.json` (included automatically in `.procraft-backup` and artwork duplication).
  - Added a **`Shape`** subtitle badge in `LayersPanel` plus **`Edit Shape`** and **`Rasterize Shape`** options in the layer options menu, allowing Fill, Stroke, decimal size, corner radius, polygon sides, star points, opacity, and rotation to be re-edited sharply in place while preserving layer order, blend mode, opacity, clipping, alpha lock, reference, visibility, and custom layer names.
  - Added Transform delta tracking (`TransformSnapshot` / `ShapePlacement.applyTransformDelta`) so moving, scaling, or rotating an Editable Shape Layer via ProCraft's native Transform tool keeps its placement synced for future edits (and automatically rasterizes metadata if distorted/warped).
- **Unit Tests (`InsertShapeGeometryTest.kt`, `EditableShapeStoreTest.kt`)**:
  - Expanded shape test coverage to 10 tests across both suites verifying logarithmic `0.1–500 px` stroke scaling, decimal dot/comma parsing, two-color straight-RGBA compositing, JSON sidecar persistence, Transform delta math, and rotated bounds (50 suites / 216 tests total, 0 failures).

---

## [0.0.7-rc.2] - 2026-11-23 - Smartphone QuickShape Polish & Insert Shape Composer

### Added
- **Insert Shape Composer (`InsertShapeGeometry.kt`, `InsertShapeRenderer.kt`, `InsertShapeUi.kt`, `ActionsPanel.kt`, `AppRoot.kt`, `CanvasViewModel.kt`)**:
  - Added **Actions → Add → Insert shape** with live checkerboard preview in the active brush color across **8 geometric shapes** (*Rectangle*, *Rounded*, *Circle*, *Triangle*, *Polygon* 5–12 sides, *Star* 4–12 points + inner depth, *Arrow*, and *Line*).
  - Added **Outline / Fill** style toggle, proportion presets (`1:1`, `4:3`, `16:9`, `3:4`), stroke width, corner radius, opacity controls, and **Insert** or **Insert & Transform** actions creating cleanly named layers (e.g., *Hexagon*, *5-Point Star*, *Ellipse*) with straight-RGBA8 anti-aliased rendering (no double-premultiplication fringes; preserves sRGB and Display P3 color channels).
- **Unit Tests (`QuickShapeUiTest.kt`, `InsertShapeGeometryTest.kt`)**:
  - Added 10 unit tests covering smartphone QuickShape layout bounds/tap targets, polygon/star/triangle/arrow/line geometry, target bitmap sizing, straight-RGBA alpha packing, and shape naming (49 suites / 212 tests total, 0 failures).

### Improved & Fixed
- **Smartphone QuickShape Polish (`QuickShapeUi.kt`, `AdjustmentsEffectsUi.kt`, `AppRoot.kt`, `MediaCropReferenceActions.kt`, `PocketErgonomics.kt`)**:
  - Extracted `QuickShapeBar` and `QuickShapeHandles` into `QuickShapeUi.kt` with responsive `>= 44dp` smartphone tap targets, an explicit **Edit / Done** button, and a horizontally scrollable choice pill that never overflows on `320dp–430dp` screens.
  - Fixed unreadable white-on-white unselected QuickShape choice chips in **Light Mode** (all pairings verified WCAG AA).
  - Enlarged smartphone QuickShape edit handles with a high-contrast dark halo for visibility on white and dark canvases, added Android **Back** key handling to commit/exit QuickShape, added `HapticCue.SNAP` on shape switching, and documented the 2nd-finger QuickShape lock gesture in **Gesture guide**.

---

## [0.0.7-rc.1] - 2026-11-23 - Offline Expandable In-App Changelog Viewer

### Added
- **In-App Changelog Viewer (`AppChangelog.kt`, `ChangelogUi.kt`, `AppRoot.kt`, `CanvasViewModel.kt`)**:
  - Added an offline, user-friendly release history catalog covering `v0.0.1` through `v0.0.6` organized by `Added`, `Improved`, and `Fixed` categories.
  - Added a responsive full-screen `ChangelogSheet` supporting both Dark and Light interfaces (WCAG AA verified contrast), expandable/collapsible per-version cards with `Installed` / `Latest` badges, an **Expand all / Collapse all** toggle, and a **View Full Changelog on GitHub** browser link.
  - Added **Changelog** entry points in **Gallery → About ProCraft** (`GalleryPreviewNewCanvas.kt`, `GalleryScreen.kt`) and **Canvas → Actions → Help** (`ActionsPanel.kt`), and guarded canvas volume shortcuts while `ChangelogSheet` or `AppUpdateSheet` is open (`SettingsCursorActions.kt`).
- **Unit Tests (`AppChangelogTest.kt`)**:
  - Added 5 unit tests verifying descending SemVer ordering (`0.0.1`–`0.0.6`), category/item integrity, pre-release installed-badge handling, GitHub URLs, and stable `gradle.properties` sync (47 suites / 202 tests total, 0 failures, clean `:app:lintDebug`).

---

## [0.0.6] - 2026-11-23 - Smartphone Floating Color Button Fix Across All Picker Tabs

### Fixed
- **Floating Color Button Unresponsive on Disc / Classic / Harmony / Value Tabs (`BottomSheetPanel.kt`, `ColorPanel.kt`)**:
  - Confined the `SmartphoneBottomSheet` top drag-handle hitbox strictly to the top `88×20dp` grabber strip (`SheetHandleWidth` / `SheetHandleHeight`) so it no longer intercepts taps on centered header action buttons.
  - Positioned compact smartphone `ColorPanel` header controls cleanly below `y = 20dp` with compact `34dp` color swatches so the **Float** button works reliably across all 5 tabs (**Disc**, **Classic**, **Harmony**, **Value**, and **Palettes**) even on narrow `320dp` screens.
- **Unit Tests (`PocketErgonomicsTest.kt`)**:
  - Added `bottomSheetGrabberStaysClearOfCompactHeaderActions` regression test (46 suites / 197 tests total, 0 failures, clean `:app:lintRelease`).

---

## [0.0.5] - 2026-11-22 - Procreate Pocket Expansion, In-App Updater & Smartphone Polish (Stable Release)

Promotes all `v0.0.5-rc.1` through `v0.0.5-rc.4` milestones from `development` into `main`, plus About attribution for **Igoy Creative Lab.** alongside upstream **ArtCraft Team & contributors** (MIT / Apache-2.0).

### Highlights
- **Gallery Upgrade & Safety**: Multi-token search, 5 persisted sort orders, 30-day **Recently Deleted (Trash)** with one-tap collision-safe restore, full **Backup & Restore** (`.procraft-backup` ZIP via SAF covering artworks, brushes, fonts, ICC profiles, palettes, gradients, and presets), and compact `52×52dp` icon-only gallery swipe actions (*Share*, *Duplicate*, *Delete*).
- **Canvas Feel & Smartphone Ergonomics**: Permission-free **Haptic Feedback**, configurable **Volume buttons** shortcuts (*Undo/Redo*, *Brush Size*, *Flip/Fit*), auto-fading **Zoom % & Rotation ° HUD** + optional pinned **Quick flip button**, **Auto-hide UI while drawing**, `44dp` collapsed **Undo/Redo edge dock**, simultaneous **Color History + Active Palette** in smartphone bottom sheets, smartphone **Floating Color** button, **Recolor** in 2D Adjustments, 1-tap **Save to Gallery** (*PNG*, *JPEG*, transparent lossless *WebP*), and an in-panel **Gesture guide**.
- **Layer & Rename Polish**: Consistent `Trash` icon on default-layer swipe actions, keyboard-aware `SmartphoneBottomSheet` (`rememberKeyboardReach()` on Android 8–16), and a dedicated pinned **Layer / Group / Mask Rename Bar** that stays cleanly above the soft keyboard.
- **GitHub Releases In-App Updater**: Rate-limit-resistant update checking via GitHub's public `releases.atom` feed with automatic REST API fallback, accurate HTTP 403/429 classification with reset countdown, persistent failure backoff, ABI-matched APK download with **SHA-256 verification** (`SHA256SUMS.txt`), `FileProvider` install handoff, and Stable / Pre-release channel toggles.
- **About Attribution (`GalleryPreviewNewCanvas.kt`, `ActionsPanel.kt`)**: Added **Android Smartphone Edition** subtitle and open-source fork credit for **Igoy Creative Lab.** while preserving upstream **ArtCraft Team & contributors (MIT / Apache-2.0)** attribution.
- **Verification**: 46 unit test suites / 196 unit tests passing with 0 failures and clean `:app:lintRelease`.

---

## [0.0.5-rc.4] - 2026-11-22 - Icon-Only Gallery Swipe Actions & Keyboard-Safe Layer/Group Rename

### Changed
- **Gallery Swipe Actions (`GalleryDragShare.kt`)**:
  - Replaced text labels (*Share*, *Duplicate*, *Delete*) on swiped artwork and stack action pills with compact `52×52dp` icon-only pills (`ShareOut`, `Duplicate`, `Trash`) so they fit cleanly over a single smartphone gallery card without overflowing neighboring thumbnails.

### Fixed
- **Layer & Layer Group Rename Covered by Keyboard (`BottomSheetPanel.kt`, `LayersPanel.kt`, `LayerBlendOptions.kt`, `TextUi.kt`)**:
  - Made `SmartphoneBottomSheet` keyboard-aware via `rememberKeyboardReach()` (works on Android 8–16 even with `adjustNothing`), smoothly lifting the bottom sheet above the on-screen keyboard and clamping sheet height within the remaining viewport.
  - Added a dedicated pinned `LayerRenameBar` directly below the `Layers` header (supporting **Rename Layer**, **Rename Group**, and **Rename Mask**) with full-width input, Save/Cancel `44dp` buttons, immediate popup focus handoff (`PopupProperties(focusable = open)`), and automatic scroll-to-item so the input field is never occluded by the keyboard or recycled during scrolling.
- **Unit Tests (`PocketErgonomicsTest.kt`)**:
  - Added `smartphoneBottomSheetLiftsCleanlyAboveKeyboard` test covering no-keyboard, portrait phone keyboard, and landscape phone keyboard layouts (46 suites / 196 tests total).

---

## [0.0.5-rc.3] - 2026-11-22 - Rate-Limit-Free Atom Feed Updater, HTTP 403/429 Classification & Backoff

### Fixed
- **In-App Updater Rate Limit on Shared Mobile Networks (`AppUpdater.kt`, `AppUpdateActions.kt`, `CanvasViewModel.kt`)**:
  - Switched primary release discovery to GitHub's public Atom feed (`https://github.com/ogibinedi/procraft/releases.atom`) to avoid unauthenticated `api.github.com` 60 req/hr IP rate limits on 4G/5G CGNAT networks, deriving ABI-matched APK and `SHA256SUMS.txt` download URLs cleanly with HTML-to-plain-text release notes and automatic fallback to the REST API.
  - Added response header reading (`x-ratelimit-remaining`, `x-ratelimit-reset`, `Retry-After`) and accurate HTTP 403/429 failure classification so generic HTTP 403 responses are no longer mislabeled as rate limits and rate-limit errors display remaining reset time (`Try again in about N minutes`).
  - Persisted failure/rate-limit backoff timestamps (`UPDATE_NEXT_ALLOWED_MS`, `UPDATE_RATE_LIMIT_RESET_MS`) in `interfacePrefs` so failed Gallery checks do not loop on every Gallery open, and manual checks short-circuit cleanly during an active rate-limit window when reset time is known.
- **Unit Tests (`AppUpdaterTest.kt`)**:
  - Added 4 new test methods covering HTTP 403/429 classification, remaining-minute calculation, backoff window enforcement, Atom XML parsing (including BOM/whitespace hardening and HTML entity decoding), and Atom-first with REST API fallback (46 suites / 195 tests total).

---

## [0.0.5-rc.2] - 2026-11-22 - GitHub Releases In-App Updater & Consistent Layer Trash Icon

### Added
- **GitHub Releases In-App Updater (`AppUpdater.kt`, `AppUpdateInstaller.kt`, `AppUpdateActions.kt`, `AppUpdateUi.kt`)**:
  - Added automatic rate-limited (12-hour interval) update checks in the Gallery against GitHub Releases (`ogibinedi/procraft`) with a dismissible update banner and full **Software Update** sheet showing release notes, device ABI (`ARM64` / `ARMv7`), and APK size.
  - Added direct in-app APK download with live progress bar, cancel support, HTTPS GitHub host allowlist validation, **SHA-256 verification** against `SHA256SUMS.txt`, automatic `FileProvider` handoff to the Android installer (plus one-time "Install unknown apps" permission flow with auto-resume), and a browser fallback button (*Open on GitHub*).
  - Added opt-in **Auto-check in Gallery** and **Include pre-release (Beta / RC)** toggles (defaulting to enabled on RC/beta builds and disabled on stable builds) synced across the Software Update sheet and `SettingsActivity`.
  - Added **Check for Updates** entry points in Gallery *More*, Gallery *About ProCraft*, and Canvas *Actions → Help*.
- **Unit Tests (`AppUpdaterTest.kt`)**:
  - Added 7 unit tests covering SemVer ordering, pre-release channel defaults, GitHub Releases JSON parsing, ABI selection, SHA-256 parsing/verification, HTTPS host allowlist enforcement, and auto-check cooldown (46 suites / 191 tests total).

### Changed
- **Default Layer Swipe Icon (`LayersPanel.kt`)**:
  - Replaced the broom (`SwipeClear`) icon on the default/sole layer's swipe action with the standard `Trash` icon so it visually matches additional layers while safely preserving clear-instead-of-delete behavior when only one layer exists.

---

## [0.0.5-rc.1] - 2026-11-22 - Procreate Pocket Feature Expansion (Gallery Safety, Haptics, One-Handed Ergonomics & WebP)

### Added
- **Gallery Search, Sort, 30-Day Recently Deleted & Full Backup/Restore (`GalleryQuery.kt`, `GalleryTrash.kt`, `GalleryBackup.kt`, `GalleryManagementUi.kt`, `GalleryManagementActions.kt`)**:
  - Added multi-token case-insensitive gallery search (including inside stacks) with empty-state reset and 5 persisted sort orders (*Manual Order*, *Recently Modified*, *Name A–Z*, *Name Z–A*, *Largest Canvas*); drag-to-reorder automatically locks when searching/sorting to keep `order.txt` intact.
  - Added **Recently Deleted (Trash)** stored separately in `filesDir/trash` with 30-day automatic expiration, item badge count, one-tap restore back to original stack or root gallery (collision-safe), permanent delete, and Empty Trash.
  - Added **Full Backup & Restore Archive** (`.procraft-backup` ZIP via Android SAF) archiving `artworks/`, `brushes/`, `fonts/`, `icc/`, `palettes.json`, `gradients.json`, and `presets.json` with manifest inspection, Zip-Slip protection, atomic staging, safe Merge restore, and live reload without restarting.
- **Canvas Haptic Feedback, Hardware Volume Shortcuts & Zoom/Flip HUD (`CanvasHaptics.kt`, `VolumeShortcut.kt`, `CanvasViewMetrics.kt`, `CanvasViewHud.kt`)**:
  - Added permission-free tactile haptic feedback (`decorView.performHapticFeedback`) with cooldown & snap-transition tracking for QuickShape snap, Transform guide snap, Slider notch snap, Eyedropper pick, ColorDrop fill, Undo/Redo, and Flip/Fit Canvas (configurable in *Actions → Prefs*).
  - Added configurable **Volume buttons** canvas shortcuts (*Off*, *Undo / Redo*, *Brush Size*, *Flip / Fit Canvas*) in `MainActivity` that automatically yield while typing, in Gallery, in modals/studios, or during Time-lapse Replay.
  - Added auto-fading **Zoom % & Rotation ° HUD** pill with 1-tap Fit-to-Screen and Flip Horizontal, plus an optional pinned **Quick flip button** in *Actions → Prefs*.
- **One-Handed Smartphone Ergonomics & Discoverability (`Chrome.kt`, `ColorPanel.kt`, `AdjustmentsUi.kt`, `ActionsPanel.kt`, `GestureGuideUi.kt`, `PocketErgonomics.kt`)**:
  - Upgraded the collapsed smartphone SideBar handle into a `44dp` edge dock keeping **Expand**, **Undo**, and **Redo** always one tap away.
  - Made **Color History** and **Active Palette** visible together in smartphone Color bottom sheets without toggling behind `"•••"`.
  - Exposed built-in interactive **Recolor** directly in the **Adjustments** menu on 2D canvases.
  - Added 1-tap **Save to Gallery** (*PNG*, *JPEG*, *WebP*) in *Actions → Share* and an in-panel **Gesture guide** under *Actions → Help*.
- **Auto-Hide UI While Drawing, Smartphone Floating Color & Lossless WebP Export (`AutoHideUiPolicy.kt`, `WebpExport.kt`)**:
  - Added **Auto-hide UI while drawing** toggle in *Actions → Prefs* that smoothly tucks top/side chrome while actively stroking and restores after a `550ms` grace period.
  - Added a smartphone **Float** button in the Color Panel header to detach the compact draggable **Floating Color** companion with responsive phone scaling and collapsed-sidebar clearance.
  - Added transparent lossless **WebP** export (`image/webp`) across *Save to Gallery*, *Share Image*, and Gallery batch sharing.
- **CI/Release Pre-release Support (`.github/workflows/ci.yml`, `.github/workflows/release.yml`)**:
  - Enabled CI validation on pushes to `development` and automatic `--prerelease` tagging on GitHub Releases for pre-release tags (`v*-rc.*`, `v*-beta.*`).
- **Unit Tests**:
  - Added 9 new test suites (`GalleryQueryTest`, `GalleryTrashTest`, `GalleryBackupTest`, `CanvasHapticsTest`, `VolumeShortcutTest`, `CanvasViewMetricsTest`, `PocketErgonomicsTest`, `AutoHideAndFloatTest`, `WebpExportTest`) bringing total coverage to 45 suites / 184 unit tests.

---

## [0.0.4] - 2026-11-22 - Drawing Tablet Auto-Gain Pressure Calibration, Navigation Pointer & Multi-Vendor Hardening

### Added
- **Per-Device Pressure Auto-Gain Learner (`PenPressureLearner.kt`)**:
  - Added vendor-agnostic per-device pressure peak learning keyed by USB/Bluetooth `"vvvv:pppp"` (with descriptor/name fallback) and persisted in `interfacePrefs`, rescaling under-reporting tablets (e.g., XP-Pen `28bd:2904` outputting `0.000..0.084` on Android 15 inside a 16-bit HID range) into the full brush dynamic range via `learnedMax` (`ref * 1.15f`) while leaving normal full-range styluses (`ref >= 0.5f`, such as S-Pen) completely untouched.
  - Added per-stroke diagnostic logging (`device`, `axis`, `raw peak`, `learned ref`, `effective max`) alongside `PenStrokesTold` in `CanvasSurfaceView.kt`.
- **One-Tap Pressure Calibration & Navigation Pointer Controls (`ActionsPluginsRows.kt`, `SettingsCursorActions.kt`, `Chrome.kt`, `AppRoot.kt`)**:
  - Added **Calibrate pressure** action in *Actions → Prefs → Drawing Tablet & Pen* so users can press once comfortably on the test pad to calibrate peak pressure immediately; **Reset drawing tablet settings** now clears all learned per-device peaks.
  - Added top-level `TabletPointerOverlay` across all screens (Gallery, TopBar, SideBar, Brush Library, Panels, BottomSheets, Brush Studio, Dialogs) tracked in `MainActivity` with a **Show navigation pointer** toggle, ensuring UI navigation remains clearly visible even on Android ROMs that do not draw an OS pointer for `TOOL_TYPE_STYLUS`.
- **Unit Tests (`PenKeysTest.kt`)**:
  - Expanded `PenKeysTest` to 34 tests covering `PenPressureLearner` update rules, `>= 0.5f` normal-device threshold & decay guard, `calibratePenPressure` with `learnedMax`, already-normalized `0..1` pressure with `axisMax > 1.5f`, `TabletPressureTracker`, USB Vendor ID detection, `resolveTabletPointerIconType`, and `shouldShowBrushCursor`.

### Fixed
- **Pointer Vanishing Over Navigation & Flickering (`CanvasSurfaceView.kt`, `PadSurfaceView.kt`, `Chrome.kt`, `MainActivity.kt`)**:
  - Fixed `CanvasSurfaceView.onResolvePointerIcon` returning `PointerIcon.TYPE_NULL` over TopBar/SideBar gaps, Brush Library, BottomSheets, Panels, Studios, and Dialogs; it now always returns `PointerIcon.TYPE_ARROW` whenever the pointer is not over the open painting canvas with an active brush cursor.
  - Fixed `BrushCursor` disappearing on small brushes (`r < 2f`), disappearing while painting on screenless external drawing tablets (`CursorShows.HOVERING`), and flickering during hover-to-touch / lift-to-hover transitions (deferred `180ms` hide + crisp center pinpoint nib dot).
- **Multi-Vendor Tablet Input & Pressure Hardening (`PenKeys.kt`, `CanvasSurfaceView.kt`, `PadSurfaceView.kt`)**:
  - Prevented `calibratePenPressure` from dividing already-normalized `0..1` pressures by raw `MotionRange.max` (e.g., `8191`) unless raw values `> 1.05` are observed, and guaranteed non-zero active external-tablet contact pressure (`>= 0.02f` or `1.0f` when unreported) even when `buttonState == 0` (`TouchInputMapper`).
  - Added USB Vendor ID detection (`0x28BD` XP-Pen/Ugee/Xencelabs, `0x256C` Huion/Gaomon/Parblo/Artisul, `0x5543` UC-Logic, `0x056A` Wacom, `0x2FEB` Veikk, `0x172F` Waltop, `0x0B57` Hanvon, `0x2179` Bosto, `0x34A0` Xencelabs), tilt/distance capability checks, and single-pointer Android-mode `TOOL_TYPE_FINGER` promotion while excluding dedicated touch overlays/trackpads.
  - Hardened synthetic hover-stroke bridging and `ACTION_BUTTON_PRESS`/`ACTION_SCROLL` handling so Compose UI clicks/scrolls never draw or zoom behind panels and low-IAF hover drift never triggers phantom strokes; exempted external devices from touchscreen palm rejection.

---

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
