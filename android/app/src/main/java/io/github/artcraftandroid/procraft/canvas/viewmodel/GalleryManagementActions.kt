package io.github.artcraftandroid.procraft.canvas

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.AppLog
import io.github.artcraftandroid.procraft.gallery.BackupManifest
import io.github.artcraftandroid.procraft.gallery.CanvasPreset
import io.github.artcraftandroid.procraft.gallery.GalleryBackup
import io.github.artcraftandroid.procraft.gallery.GallerySort
import io.github.artcraftandroid.procraft.gallery.RestoreMode
import io.github.artcraftandroid.procraft.gallery.TrashedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Snapshot of local library counts shown in Backup & Restore. */
data class LocalLibraryStats(
    val artworkEntries: Int,
    val paletteCount: Int,
    val hasCustomBrushes: Boolean,
    val trashCount: Int,
)

fun CanvasViewModel.setGallerySort(sort: GallerySort) {
    gallerySort = sort
    interfacePrefs.edit().putString(CanvasViewModel.GALLERY_SORT, sort.key).apply()
}

fun CanvasViewModel.refreshTrash() {
    viewModelScope.launch {
        val entries = withContext(Dispatchers.IO) { trash.list(autoPurge = true) }
        trashEntries = entries
        trashCount = entries.size
    }
}

fun CanvasViewModel.restoreFromTrash(entry: TrashedEntry) {
    viewModelScope.launch {
        val restored = withContext(Dispatchers.IO) { trash.restore(entry.id) }
        refreshGallery()
        refreshTrash()
        galleryVersion++
        message = if (restored != null) "Restored “${entry.originalName}”" else "Couldn't restore “${entry.originalName}”."
    }
}

fun CanvasViewModel.deleteTrashedPermanently(entry: TrashedEntry) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) { trash.deletePermanently(entry.id) }
        refreshTrash()
    }
}

fun CanvasViewModel.emptyTrash() {
    viewModelScope.launch {
        val count = withContext(Dispatchers.IO) { trash.emptyTrash() }
        refreshTrash()
        if (count > 0) message = "Trash emptied"
    }
}

fun CanvasViewModel.cancelBackupWork() {
    backupCancelled = true
}

suspend fun CanvasViewModel.readLocalLibraryStats(): LocalLibraryStats = withContext(Dispatchers.IO) {
    val files = app.filesDir
    val artworks = GalleryBackup.countTopLevelArtworks(File(files, "artworks"))
    val hasBrushes = brushesDir.isDirectory && brushesDir.listFiles().orEmpty().isNotEmpty()
    LocalLibraryStats(
        artworkEntries = artworks,
        paletteCount = palettes.size,
        hasCustomBrushes = hasBrushes,
        trashCount = trashCount,
    )
}

fun CanvasViewModel.createBackupToUri(uri: Uri) {
    if (backupProgress != null) return
    backupCancelled = false
    backupProgress = CanvasViewModel.BackupProgress("Creating backup…", 0f, restoring = false)
    val presetsSnapshot = runCatching { CanvasPreset.toJson(canvasPresets) }.getOrNull()
    val appVersion = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    GalleryBackup.createBackup(
                        filesDir = app.filesDir,
                        output = out,
                        presetsJson = presetsSnapshot,
                        appVersion = appVersion,
                        onProgress = { p ->
                            viewModelScope.launch(Dispatchers.Main) {
                                backupProgress = backupProgress?.copy(progress = p)
                            }
                        },
                        isCancelled = { backupCancelled },
                    )
                }
            }.onFailure { AppLog.e("backup", "creating backup archive failed", it) }.getOrNull()
        }
        val cancelled = backupCancelled
        backupProgress = null
        backupCancelled = false
        message = when {
            cancelled -> "Backup cancelled"
            result == null -> "Backup couldn't be saved."
            else -> "Backup saved (${result.artworkEntries} items, ${GalleryBackup.formatByteSize(result.bytesWritten)})"
        }
    }
}

suspend fun CanvasViewModel.inspectBackupUri(uri: Uri): BackupManifest? = withContext(Dispatchers.IO) {
    runCatching {
        app.contentResolver.openInputStream(uri)?.use { GalleryBackup.inspectBackup(it) }
    }.onFailure { AppLog.e("backup", "inspecting backup archive failed", it) }.getOrNull()
}

fun CanvasViewModel.restoreBackupFromUri(uri: Uri, mode: RestoreMode = RestoreMode.MERGE) {
    if (backupProgress != null) return
    backupCancelled = false
    backupProgress = CanvasViewModel.BackupProgress("Restoring backup…", 0f, restoring = true)

    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    GalleryBackup.restoreBackup(
                        input = input,
                        filesDir = app.filesDir,
                        cacheDir = app.cacheDir,
                        mode = mode,
                        onProgress = { p ->
                            viewModelScope.launch(Dispatchers.Main) {
                                backupProgress = backupProgress?.copy(progress = p)
                            }
                        },
                        isCancelled = { backupCancelled },
                    )
                }
            }.onFailure { AppLog.e("backup", "restoring backup archive failed", it) }.getOrNull()
        }

        val cancelled = backupCancelled
        if (!cancelled && result != null) {
            reloadAfterBackupRestore(result.presetsJson)
        }
        backupProgress = null
        backupCancelled = false
        message = when {
            cancelled -> "Restore cancelled"
            result == null -> "This file isn't a valid ProCraft backup."
            else -> "Restored ${result.artworksRestored} artworks from backup"
        }
    }
}

private suspend fun CanvasViewModel.reloadAfterBackupRestore(presetsJson: String?) {
    withContext(Dispatchers.IO) {
        io.github.artcraftandroid.procraft.core.prepareBrushes(brushesDir.path)
        TextRenderer.prepare(app.assets, File(app.filesDir, "fonts"))
        io.github.artcraftandroid.procraft.core.setProfileFolder(File(app.filesDir, "icc").path)
    }
    paletteStore = io.github.artcraftandroid.procraft.core.Palettes.open(File(app.filesDir, "palettes.json").path)
    refreshPalettes()
    gradientLibrary = io.github.artcraftandroid.procraft.core.Gradients.open(File(app.filesDir, "gradients.json").path)
    gradients = readGradients()
    if (!presetsJson.isNullOrBlank()) {
        runCatching { CanvasPreset.fromJson(presetsJson) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::savePresets)
    }
    openStack = null
    galleryVersion++
    refreshGallery()
    refreshTrash()
    viewModelScope.launch(Dispatchers.IO) {
        io.github.artcraftandroid.procraft.gallery.RecentArtworkWidget.refresh(getApplication<Application>())
    }
}
