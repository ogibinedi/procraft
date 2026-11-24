# ProCraft Smartphone Transformation & Refactoring Progress

Dokumen ini mencatat progres pengembangan fork **ProCraft (`ogibinedi/procraft`)** dari aplikasi khusus tablet/desktop menjadi aplikasi melukis khusus **Smartphone Android** dengan arsitektur yang bersih, modular, dan menerapkan prinsip DRY.

---

## Ringkasan Status Fase

| Fase | Fokus Utama | Status | Commit |
|---|---|---|---|
| **Fase 1** | Pembersihan Platform Non-Android (`desktop/`, `ios/`, `packaging/`), Restrukturisasi Folder `canvas/` & `ui/`, dan Pemecahan God File `CanvasViewModel.kt` | ✅ Selesai | `58f1334` |
| **Fase 2** | Desain Ulang Navigasi & Panel Utama Smartphone (*Procreate Pocket* Bottom Sheets, Collapsible SideBar, Responsive Pickers, Brush Studio Horizontal Tabs) + Pemecahan File UI & Crate `core`/`gpu` | ✅ Selesai | `a25d5ca`, `ef874b6` |
| **Fase 3** | Adaptasi Secondary Tools & Editors untuk Semua Ukuran Smartphone, Modularisasi Crate Rust (`stroke/`, `scene3d/`, `touch/`), dan Rilis Signed APK `v0.0.1` ke GitHub Releases (0 Kuota Artifact) | ✅ Selesai | `e28d701`..`1052f52` |
| **Fase 4** | Rapi-Rapi & Optimasi (`v0.0.2`): Eliminasi Warning Visibilitas `procraft-gpu`, Pembersihan 1.489 Import Kotlin Tak Terpakai, Optimasi Zero-Recomposition Swipe-Down `SmartphoneBottomSheet`, & Pembaruan Dokumentasi | ✅ Selesai | `eb223f9`, `961ad12` |
| **Fase 5** | Dukungan Universal Drawing Tablet (XP-Pen, Huion, Wacom, Gaomon, Veikk, dll.), Pengaturan Kalibrasi Tekanan Pen (*Live Pressure Test*, Sensitivitas, Minimum Pressure, Smoothing), & Tombol Swipe Layer Berbasis Ikon (`v0.0.3`) | ✅ Selesai | `ce80c10` |
| **Fase 6** | Perbaikan Drawing Tablet & Pointer Navigasi (`v0.0.4`): *Per-Device Auto-Gain Pressure Learner* (`PenPressureLearner`), Tombol *Calibrate Pressure*, Pointer Navigasi Top-Level (`TabletPointerOverlay`), Anti-Flicker `BrushCursor`, & Hardening Multi-Vendor (USB VID + Kapabilitas) | ✅ Selesai | `857aeab` |
| **Fase 7** | Ekspansi Fitur *Procreate Pocket*, In-App Updater & Rilis Stabil **`v0.0.5`–`v0.0.6`**: Gallery Search/Sort/Trash 30 Hari/Full Backup-Restore & Icon-Only Swipe Actions, Haptic Feedback Kanvas, Volume Key Shortcuts, Zoom/Rotasi HUD & Quick Flip, Collapsed Undo/Redo Dock, Color History Smartphone, Recolor di Adjustments, 1-Tap Save to Gallery & Lossless WebP, Gesture Guide, Auto-Hide UI, Smartphone Floating Color (diperbaiki di seluruh 5 tab Color pada `v0.0.6`), **GitHub Releases In-App Updater (Atom Feed + REST Fallback + SHA-256 + Backoff)**, Ikon Trash Konsisten pada Layer Default, **Keyboard-Safe Layer/Group Rename**, & Atribusi About **Igoy Creative Lab.** | ✅ Selesai | `v0.0.6` |
| **Fase 8** | **In-App Changelog, QuickShape Polish & Editable Shape Layers (`v0.0.7`)**: Katalog riwayat rilis *offline* `v0.0.1`–`v0.0.7` (`AppChangelog.kt`, `ChangelogUi.kt`) di **About ProCraft** & **Actions → Help**; poles **QuickShape** responsif smartphone (`QuickShapeUi.kt`, fix kontras Light Mode, Back handler, halo handle, Gesture guide); serta **Insert Shape Composer & Editable Shape Layers** 8 bentuk dengan **Fill & Stroke** independen, stroke desimal **`0.1–500 px`**, persistensi `shapes.json`, pelacakan *Transform*, dan menu **`Edit Shape` / `Rasterize Shape`** di panel Layers (50 suites / 216 tests) | ✅ Selesai | `v0.0.7` |

---

## Detail Pekerjaan yang Telah Diselesaikan

### Fase 1: Clean Architecture & Pembersihan Workspace
- [x] Menghapus direktori `desktop/`, `ios/`, `packaging/`, dead code `crates/core/src/layer.rs`, dan `tools/icons/rust_icons.py`.
- [x] Memperbarui `Cargo.toml`, `crates/core/Cargo.toml`, workflow CI/Release, dan skrip lisensi agar 100% berfokus pada Android + Rust Engine.
- [x] Mengorganisir paket Kotlin Android ke dalam subfolder berdasarkan domain:
  - `canvas/surface/`, `canvas/input/`, `canvas/media/`, `canvas/util/`, `canvas/viewmodel/`
  - `ui/theme/`, `ui/components/`, `ui/chrome/`, `ui/panels/`, `ui/studio/`, `ui/tools/`, `ui/gallery/`, `ui/export/`
- [x] Memecah `CanvasViewModel.kt` (9.287 baris) menjadi 14 file modular di `canvas/viewmodel/`:
  - `CanvasModels.kt`, `CanvasViewModel.kt`, `AdjustmentsActions.kt`, `AnimationLayerActions.kt`, `BrushLibraryActions.kt`, `BrushStudioActions.kt`, `ColorPaletteExportActions.kt`, `GalleryImportActions.kt`, `InputGuideQuickMenuActions.kt`, `MediaCropReferenceActions.kt`, `Scene3dActions.kt`, `SelectionTransformActions.kt`, `SettingsCursorActions.kt`, dan `TextBlockedActions.kt`.

### Fase 2: UI/UX Smartphone Utama (Gaya Procreate Pocket) & Modularisasi UI/GPU
- [x] Membuat `ResponsiveLayout.kt` dan `SmartphoneBottomSheet` (`BottomSheetPanel.kt`) dengan fitur *peek mode* dan *drag-to-expand/dismiss*.
- [x] Mengintegrasikan `SmartphoneBottomSheet` ke `AppRoot.kt` dan `Popover.kt` untuk panel **Brushes**, **Layers**, **Color**, **Actions**, dan **Adjustments**.
- [x] Menambahkan fitur *collapsible sidebar* dengan *pull-tab* di tepi layar pada `Chrome.kt`.
- [x] Menyesuaikan lebar kolom kategori kuas di `BrushLibrary.kt`, skala *Color Disc* / *Classic Square* di `ColorPanel.kt` & `ColorDiscHarmony.kt`, serta grid 2 kolom di `GalleryScreen.kt`.
- [x] Memecah 7 file Compose UI terbesar menjadi 16 file modular yang rapi.
- [x] Menambahkan *Horizontal Scrollable Pill Tabs* (`StudioHorizontalTabs`) pada `BrushStudio.kt` untuk layar smartphone.
- [x] Memecah `crates/core/src/lib.rs` (menjadi `session_strokes.rs`, `session_view.rs`, `session_controls.rs`) dan `crates/gpu/src/canvas.rs` (14.907 baris menjadi `mod.rs`, `ops.rs`, `composite.rs`, `readback.rs`, `pipelines.rs`, `tests.rs`).

### Fase 3: Secondary Tools Smartphone, Modularisasi Rust Lanjutan, & CI/CD APK
- [x] Menyesuaikan seluruh *secondary tools* dan dialog untuk semua ukuran layar smartphone (`320dp - 430dp`, portrait & landscape):
  - `ToolHeader.kt`, `SourceEditorUi.kt`, `LightingStudioUi.kt`, `ModelPictureUi.kt`
  - `ReferenceUi.kt` (mode *Picture-in-Picture* kompak di kanan atas kanvas)
  - `CropUi.kt`, `GuideUi.kt`, `TextUi.kt`, `TransformUi.kt`
  - `AdjustmentsUi.kt`, `AdjustmentsEffectsUi.kt`, `QuickMenuUi.kt`, `CompanionUi.kt`, `CopyPasteUi.kt`, `AlertUi.kt`
  - `CanvasInfoUi.kt`, `GestureControlsUi.kt`, `GalleryPreviewNewCanvas.kt` (`AboutApp`), `AnimatedExportUi.kt`, `ColorValueHistory.kt` (`ValueRow` & `BesidePanel` HexPad), dan `BrushStudioWidgets.kt` (`SliderKeypad` otomatis membuka ke arah kiri di layar smartphone).
- [x] Memecah 3 modul Rust besar yang tersisa menjadi submodul:
  - `crates/engine/src/stroke/` (`mod.rs`, `builder.rs`, `tests.rs`)
  - `crates/core/src/scene3d/` (`mod.rs`, `painting.rs`, `tests.rs`)
  - `crates/core/src/touch/` (`mod.rs`, `modes.rs`, `tests.rs`, `box_pinch.rs`, `pen.rs`)
- [x] Mengonfigurasi *release keystore* (`.jks`), versi awal **`v0.0.1`** (`versionCode=199`), dan workflow `.github/workflows/release.yml` agar langsung menerbitkan APK bertanda tangan ke halaman **GitHub Releases** tanpa membebani kuota penyimpanan *Actions Artifact*.
- [x] Menyelesaikan seluruh perbaikan kompilasi Rust & Kotlin hingga APK **`v0.0.1`** berhasil terbit dan berjalan lancar di smartphone Android.

### Fase 4: Rapi-Rapi & Optimasi Pasca-Rilis `v0.0.1`
- [x] Mengoptimalkan `SmartphoneBottomSheet` (`BottomSheetPanel.kt`): memisahkan state *drag-down* (`dragDownPx`) agar hanya dibaca pada fase *layout placement* (`.offset { ... }`) sehingga gestur *swipe-down* untuk menutup panel tidak memicu rekomposisi berulang pada daftar kuas/layer/warna, serta men-*cache* ambang gestur dan *spring spec* dengan `remember`.
- [x] Menghilangkan ratusan peringatan kompiler Rust (`private_interfaces`) pada `crates/gpu/src/canvas/` dengan menurunkan 238 item internal `pub(crate)` menjadi `pub(super)` dan menjaga hanya `Pipelines::new` serta `buffer_init` sebagai `pub(crate)`.
- [x] Membersihkan **1.489 baris import Kotlin** yang tidak terpakai/duplikat pada 30 file hasil modularisasi, `MainActivity.kt`, dan `BottomSheetPanel.kt`.
- [x] Menghapus file log diagnostik sementara lokal (`ci_log.txt`, `temuan.txt`), menambahkan `temuan.txt` ke `.gitignore`, serta menyinkronkan `README.md`, `CHANGELOG.md`, dan `PROGRESS.md`.

### Fase 5: Dukungan Universal Drawing Tablet & Ikon Swipe Layer (`v0.0.3`)
- [x] Menambahkan dukungan universal drawing tablet USB OTG & Bluetooth (*Drawing Tablet Mode*), jembatan *synthetic stroke* & zoom dial (`ACTION_SCROLL`), halaman pengaturan **Drawing Tablet & Pen** dengan *Live Pen Pressure Test*, serta mengubah tombol *swipe-left* layer menjadi tombol ikon ringkas (`56dp`).

### Fase 6: Auto-Gain Tekanan Per-Perangkat, Pointer Navigasi, & Hardening Multi-Vendor (`v0.0.4`)
- [x] Menambahkan `PenPressureLearner` (auto-gain per-perangkat berbasis `"vvvv:pppp"` tersimpan di `interfacePrefs`) dan parameter `learnedMax` pada `calibratePenPressure` agar tablet yang melaporkan tekanan rendah (seperti XP-Pen `28bd:2904` dengan puncak `0.084` di Android 15) langsung menghasilkan goresan proporsional tanpa merusak perangkat normal (`ref >= 0.5f`).
- [x] Menambahkan tombol **Calibrate pressure** di halaman **Drawing Tablet & Pen**, pembaruan *Live Pen Pressure Test* berbasis `pointerInteropFilter`, logging puncak raw per-stroke (`AppLog`), serta reset nilai terkalibrasi melalui **Reset drawing tablet settings**.
- [x] Memperbaiki `CanvasSurfaceView.onResolvePointerIcon` agar selalu menampilkan `PointerIcon.TYPE_ARROW` di atas TopBar, SideBar, Brush Library, Panel, BottomSheet, Studio, dan Dialog, serta menambahkan `TabletPointerOverlay` di level tertinggi `AppRoot` + jeda anti-flicker `180ms` + titik pusat pena pada `BrushCursor`.
- [x] Memperkuat kompatibilitas lintas vendor (XP-Pen, Huion, Wacom, Gaomon, Veikk, Ugee, Parblo, Xencelabs, Waltop, Hanvon, Bosto) via deteksi USB Vendor ID & kapabilitas sumbu, `TabletPressureTracker` (anti-pembagian `axisMax > 1.5f` pada pressure ternormalisasi & anti-drift hover), serta 34 unit test lulus (`PenKeysTest`, total 143 unit test & `lintDebug` lulus).

### Fase 7: Ekspansi Fitur Procreate Pocket di Branch `development` (`v0.0.5-rc.1`)
- [x] **Gallery Upgrade & Keamanan Karya**: pencarian multi-kata & 5 mode sortir (`GalleryQuery.kt`), **Recently Deleted** 30 hari terpisah di `filesDir/trash` dengan *restore* aman (`GalleryTrash.kt`), serta **Full Backup & Restore** `.procraft-backup` via Android SAF dengan validasi manifest & proteksi Zip-Slip (`GalleryBackup.kt`, `GalleryManagementUi.kt`).
- [x] **Canvas Feel & Shortcut HP**: haptic feedback bebas izin ekstra dengan *rate-limiter* & *snap tracker* (`CanvasHaptics.kt`), shortcut tombol volume HP (`VolumeShortcut.kt`, `MainActivity.kt`), serta HUD **Zoom % & Rotasi °** *auto-fade* + tombol opsional **Quick Flip** (`CanvasViewMetrics.kt`, `CanvasViewHud.kt`).
- [x] **One-Handed Pocket Polish & Discoverability**: *edge dock* Undo/Redo saat SideBar collapse (`Chrome.kt`), **Color History** + **Active Palette** tampil bersamaan di bottom sheet HP (`ColorPanel.kt`), ekspos **Recolor** di menu Adjustments (`AdjustmentsUi.kt`), **1-Tap Save to Gallery** (PNG/JPEG/WebP), dan **Gesture guide** di menu Help (`GestureGuideUi.kt`).
- [x] **Pocket Immersion & WebP Export**: **Auto-hide UI while drawing** (`AutoHideUiPolicy.kt`), tombol **Float** untuk *Floating Color Companion* di smartphone (`ColorPanel.kt`), export **WebP** transparan lossless (`WebpExport.kt`), serta integrasi CI branch `development` & dukungan `--prerelease` otomatis di GitHub Releases.
