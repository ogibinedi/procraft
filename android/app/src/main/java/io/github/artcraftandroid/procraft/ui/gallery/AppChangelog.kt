package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.update.AppUpdater
import io.github.artcraftandroid.procraft.update.AppVersion

enum class ChangelogCategory(val label: String) {
    ADDED("Added"),
    FIXED("Fixed"),
    IMPROVED("Improved"),
}

data class ChangelogSection(
    val category: ChangelogCategory,
    val items: List<String>,
)

data class ChangelogRelease(
    val version: String,
    val date: String,
    val title: String,
    val sections: List<ChangelogSection>,
) {
    val tagName: String get() = "v$version"
    val totalItems: Int get() = sections.sumOf { it.items.size }
}

/**
 * Curated, offline release history shown in About ProCraft and Actions -> Help.
 * Keeps user-facing notes concise while linking to the full technical CHANGELOG.md on GitHub.
 */
object AppChangelog {
    val releases: List<ChangelogRelease> = listOf(
        ChangelogRelease(
            version = "0.0.7",
            date = "Nov 24, 2026",
            title = "In-App Changelog, QuickShape Polish & Editable Shape Layers",
            sections = listOf(
                ChangelogSection(
                    ChangelogCategory.ADDED,
                    listOf(
                        "Offline expandable Changelog viewer (v0.0.1 to v0.0.7) accessible from Gallery About ProCraft and Canvas Actions -> Help.",
                        "Insert Shape composer (Actions -> Add -> Insert shape) with live preview for 8 shapes: Rectangle, Rounded, Circle, Triangle, Polygon (5 to 12 sides), Star (4 to 12 points), Arrow, and Line.",
                        "Independent Fill and Stroke toggles and color pickers (active brush match, color history, presets, HSV sliders, Hex input, and Swap colors) plus decimal stroke size from 0.1 px to 500 px.",
                        "Editable Shape Layers with a Shape label in Layers, Edit Shape and Rasterize Shape options, persistent per-artwork metadata, and Transform position/size/rotation sync.",
                    ),
                ),
                ChangelogSection(
                    ChangelogCategory.IMPROVED,
                    listOf(
                        "Made QuickShape controls responsive for narrow smartphones (320dp to 430dp) with 44dp touch targets, an explicit Edit / Done pill, larger high-contrast edit handles, Android Back commit support, and 2nd-finger lock notes in the Gesture guide.",
                    ),
                ),
                ChangelogSection(
                    ChangelogCategory.FIXED,
                    listOf(
                        "Fixed unreadable white-on-white unselected QuickShape choice chips in Light Mode.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.0.6",
            date = "Nov 23, 2026",
            title = "Floating Color Button Fix",
            sections = listOf(
                ChangelogSection(
                    ChangelogCategory.FIXED,
                    listOf(
                        "Fixed the smartphone Floating Color button not responding on the Disc, Classic, Harmony, and Value tabs by keeping the bottom-sheet drag handle clear of header controls.",
                        "Adjusted compact smartphone Color header spacing so the Float button and swatches remain easy to tap on narrow 320dp screens.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.0.5",
            date = "Nov 22, 2026",
            title = "Gallery Safety, Smartphone Controls & In-App Updater",
            sections = listOf(
                ChangelogSection(
                    ChangelogCategory.ADDED,
                    listOf(
                        "Gallery search (including inside stacks) and 5 saved sort orders: Manual, Recently Modified, Name A to Z, Name Z to A, and Largest Canvas.",
                        "Recently Deleted (Trash) with 30-day retention, one-tap restore back to the original stack, and Empty Trash.",
                        "Full Backup & Restore archives (.procraft-backup) covering artworks, brushes, fonts, ICC profiles, palettes, gradients, and presets.",
                        "GitHub Releases in-app updater with rate-limit-resistant Atom feed checks, SHA-256 verification, and Stable or Pre-release channels.",
                        "Tactile haptic feedback for snaps, Eyedropper, ColorDrop, Undo/Redo, and Flip/Fit, plus configurable Volume button canvas shortcuts.",
                        "Auto-fading Zoom & Rotation HUD, optional pinned Quick flip button, Auto-hide UI while drawing, and an in-panel Gesture guide.",
                        "Smartphone Floating Color button, direct Recolor access in 2D Adjustments, 1-tap Save to Gallery, and transparent lossless WebP export.",
                    ),
                ),
                ChangelogSection(
                    ChangelogCategory.IMPROVED,
                    listOf(
                        "Upgraded the collapsed smartphone sidebar into an edge dock keeping Expand, Undo, and Redo one tap away.",
                        "Displayed Color History and Active Palette together inside smartphone Color bottom sheets.",
                        "Switched gallery swipe actions to compact icon-only pills and matched the default layer swipe icon to Trash.",
                        "Added Smartphone Edition branding and Igoy Creative Lab. fork attribution alongside upstream ArtCraft Team credits.",
                    ),
                ),
                ChangelogSection(
                    ChangelogCategory.FIXED,
                    listOf(
                        "Lifted smartphone bottom sheets above the on-screen keyboard and added a pinned rename bar for layers, groups, and masks.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.0.4",
            date = "Nov 22, 2026",
            title = "Auto-Gain Pen Pressure & Navigation Pointer",
            sections = listOf(
                ChangelogSection(
                    ChangelogCategory.ADDED,
                    listOf(
                        "Automatic per-device pen pressure peak learning for external tablets that under-report pressure on Android, without affecting standard full-range styluses.",
                        "One-tap Calibrate pressure pad in Drawing Tablet & Pen settings and a top-level navigation pointer overlay across all screens and menus.",
                    ),
                ),
                ChangelogSection(
                    ChangelogCategory.FIXED,
                    listOf(
                        "Fixed the pointer disappearing over toolbars, panels, and dialogs, and eliminated brush cursor flicker during hover-to-touch transitions.",
                        "Hardened multi-vendor USB and Bluetooth tablet detection (XP-Pen, Huion, Wacom, Gaomon, Veikk, Xencelabs, and others) and prevented hover drift or UI clicks from drawing behind panels.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.0.3",
            date = "Nov 21, 2026",
            title = "External Drawing Tablet Support & Compact Layer Swipes",
            sections = listOf(
                ChangelogSection(
                    ChangelogCategory.ADDED,
                    listOf(
                        "Universal external USB OTG and Bluetooth drawing tablet support with persistent Drawing Tablet Mode, synthetic pen-button stroke handling, and wheel or dial zoom.",
                        "Drawing Tablet & Pen settings page with live device detection, real-time pen pressure test bar, and sensitivity, minimum pressure, and smoothing sliders.",
                    ),
                ),
                ChangelogSection(
                    ChangelogCategory.IMPROVED,
                    listOf(
                        "Replaced text labels on layer swipe-left actions with compact icon buttons so more of each layer row remains visible on narrow phone screens.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.0.2",
            date = "Nov 21, 2026",
            title = "Bottom Sheet Smoothness & Cleanup",
            sections = listOf(
                ChangelogSection(
                    ChangelogCategory.IMPROVED,
                    listOf(
                        "Eliminated panel recomposition during downward bottom-sheet swipe gestures for smoother dismissal of Brush, Layer, and Color panels.",
                        "Cleaned up internal Rust and Kotlin module visibility and unused imports for leaner builds.",
                    ),
                ),
            ),
        ),
        ChangelogRelease(
            version = "0.0.1",
            date = "Nov 21, 2026",
            title = "Initial Android Smartphone Edition",
            sections = listOf(
                ChangelogSection(
                    ChangelogCategory.ADDED,
                    listOf(
                        "Smartphone-first responsive layout scaling toolbars, Brush Library, and Color pickers cleanly down to 320dp screens.",
                        "Peekable and expandable bottom sheets for Brushes, Layers, Color, Actions, and Adjustments on phones.",
                        "Collapsible canvas sidebar and horizontal smartphone Brush Studio tabs.",
                        "Signed ARM64 and legacy 32-bit ARMv7 Android APK releases published on GitHub Releases.",
                    ),
                ),
                ChangelogSection(
                    ChangelogCategory.IMPROVED,
                    listOf(
                        "Adapted secondary tools and floating editors (Crop, Text, Reference PiP, QuickMenu, Lighting Studio, Canvas Info, and Gesture Controls) for portrait and landscape phones.",
                    ),
                ),
            ),
        ),
    )

    fun changelogUrl(
        owner: String = AppUpdater.GITHUB_OWNER,
        repo: String = AppUpdater.GITHUB_REPO,
    ): String = "https://github.com/$owner/$repo/blob/main/CHANGELOG.md"

    fun releaseTagUrl(
        version: String,
        owner: String = AppUpdater.GITHUB_OWNER,
        repo: String = AppUpdater.GITHUB_REPO,
    ): String = "https://github.com/$owner/$repo/releases/tag/v${version.trim().removePrefix("v").removePrefix("V")}"

    fun baseVersion(versionName: String): String {
        val parsed = AppVersion.parse(versionName)
        if (parsed != null) return "${parsed.major}.${parsed.minor}.${parsed.patch}"
        return versionName.trim().removePrefix("v").removePrefix("V").substringBefore('-')
    }

    fun isInstalledRelease(release: ChangelogRelease, installedVersion: String): Boolean {
        val parsed = AppVersion.parse(installedVersion)
        if (parsed != null && parsed.isPreRelease) return false
        return release.version == baseVersion(installedVersion)
    }

    fun defaultExpandedVersions(
        items: List<ChangelogRelease> = releases,
        installedVersion: String,
    ): Set<String> {
        if (items.isEmpty()) return emptySet()
        val matched = items.firstOrNull { isInstalledRelease(it, installedVersion) } ?: items.first()
        return setOf(matched.version)
    }
}
