package io.github.artcraftandroid.procraft.canvas

import android.os.Build
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.AppLog
import io.github.artcraftandroid.procraft.update.ApkDownloadResult
import io.github.artcraftandroid.procraft.update.AppUpdater
import io.github.artcraftandroid.procraft.update.UpdateCheckResult
import io.github.artcraftandroid.procraft.update.UpdateRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Live state machine for ProCraft's GitHub Releases in-app updater. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val currentVersion: String, val checkedAtMs: Long) : UpdateUiState
    data class Available(val release: UpdateRelease) : UpdateUiState
    data class Downloading(
        val release: UpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }
    data class Verifying(val release: UpdateRelease) : UpdateUiState
    data class ReadyToInstall(
        val release: UpdateRelease,
        val apkFile: File,
        val sha256Verified: Boolean,
    ) : UpdateUiState
    data class Error(val message: String, val release: UpdateRelease? = null) : UpdateUiState
}

internal fun CanvasViewModel.updatesCacheDir(): File = File(app.cacheDir, "updates")

/** Returns the update release currently available, downloading, verifying, or ready to install. */
fun CanvasViewModel.activeUpdateRelease(): UpdateRelease? = when (val s = updateState) {
    is UpdateUiState.Available -> s.release
    is UpdateUiState.Downloading -> s.release
    is UpdateUiState.Verifying -> s.release
    is UpdateUiState.ReadyToInstall -> s.release
    is UpdateUiState.Error -> s.release
    else -> null
}

/** True when the Gallery screen should display the subtle update banner. */
fun CanvasViewModel.shouldShowUpdateBanner(): Boolean {
    val release = activeUpdateRelease() ?: return false
    if (updateState is UpdateUiState.Error) return false
    return dismissedUpdateTag != release.tagName ||
        updateState is UpdateUiState.Downloading ||
        updateState is UpdateUiState.Verifying ||
        updateState is UpdateUiState.ReadyToInstall
}

/**
 * Runs a quiet, rate-limited update check when the Gallery opens and cleans up stale cached APKs.
 * Never interrupts an active download or verified ready-to-install APK.
 */
fun CanvasViewModel.maybeAutoCheckForUpdates() {
    if (updateState is UpdateUiState.Downloading ||
        updateState is UpdateUiState.Verifying ||
        updateState is UpdateUiState.ReadyToInstall ||
        updateState is UpdateUiState.Checking
    ) {
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        AppUpdater.cleanupUpdatesDir(updatesCacheDir())
    }
    val now = System.currentTimeMillis()
    val lastChecked = interfacePrefs.getLong(CanvasViewModel.UPDATE_LAST_CHECK_MS, 0L)
    val nextAllowed = interfacePrefs.getLong(CanvasViewModel.UPDATE_NEXT_ALLOWED_MS, 0L)
    if (!AppUpdater.shouldAutoCheck(updateAutoCheck, lastChecked, now, nextAllowedAtMs = nextAllowed)) return
    checkForUpdates(manual = false)
}

/** Queries GitHub Releases for a newer ProCraft version. */
fun CanvasViewModel.checkForUpdates(manual: Boolean = true) {
    if (updateState is UpdateUiState.Checking ||
        updateState is UpdateUiState.Downloading ||
        updateState is UpdateUiState.Verifying
    ) {
        return
    }
    val now = System.currentTimeMillis()
    val rateLimitResetAt = interfacePrefs.getLong(CanvasViewModel.UPDATE_RATE_LIMIT_RESET_MS, 0L)
    if (rateLimitResetAt > now) {
        if (manual) {
            updateState = UpdateUiState.Error(AppUpdater.formatRateLimitMessage(rateLimitResetAt, now))
        }
        return
    }
    val installed = appVersion().ifBlank { "0.0.0" }
    val includePre = updateIncludePreReleases
    val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
    if (manual) {
        updateState = UpdateUiState.Checking
    }
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            AppUpdater.checkForUpdates(
                currentVersion = installed,
                includePreReleases = includePre,
                supportedAbis = abis,
            )
        }
        val checkedAt = System.currentTimeMillis()
        when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                interfacePrefs.edit()
                    .putLong(CanvasViewModel.UPDATE_LAST_CHECK_MS, checkedAt)
                    .remove(CanvasViewModel.UPDATE_NEXT_ALLOWED_MS)
                    .remove(CanvasViewModel.UPDATE_RATE_LIMIT_RESET_MS)
                    .apply()
                updateState = UpdateUiState.Available(result.release)
            }
            is UpdateCheckResult.UpToDate -> {
                interfacePrefs.edit()
                    .putLong(CanvasViewModel.UPDATE_LAST_CHECK_MS, checkedAt)
                    .remove(CanvasViewModel.UPDATE_NEXT_ALLOWED_MS)
                    .remove(CanvasViewModel.UPDATE_RATE_LIMIT_RESET_MS)
                    .apply()
                if (manual || updateState !is UpdateUiState.Available) {
                    updateState = UpdateUiState.UpToDate(result.currentVersion, checkedAt)
                }
            }
            is UpdateCheckResult.Failure -> {
                AppLog.e("update", "update check failed: ${result.message}")
                val nextAllowedAt = AppUpdater.nextBackoffUntilMs(result, checkedAt)
                interfacePrefs.edit().apply {
                    putLong(CanvasViewModel.UPDATE_NEXT_ALLOWED_MS, nextAllowedAt)
                    val resetAt = result.retryAtMs
                    if (result.isRateLimited && resetAt != null && resetAt > checkedAt) {
                        putLong(CanvasViewModel.UPDATE_RATE_LIMIT_RESET_MS, resetAt)
                    } else {
                        remove(CanvasViewModel.UPDATE_RATE_LIMIT_RESET_MS)
                    }
                }.apply()
                if (manual) {
                    updateState = UpdateUiState.Error(result.message)
                }
            }
        }
    }
}

/** Downloads the release's matching APK, verifies its SHA-256 digest, and prepares installation. */
fun CanvasViewModel.startUpdateDownload(release: UpdateRelease) {
    if (updateState is UpdateUiState.Downloading || updateState is UpdateUiState.Verifying) return
    updateDownloadCancelled = false
    val totalHint = release.apkAsset?.sizeBytes ?: 0L
    updateState = UpdateUiState.Downloading(release, 0L, totalHint)
    val installed = appVersion().ifBlank { "0.0.0" }

    updateDownloadJob = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            AppUpdater.downloadAndVerifyApk(
                release = release,
                updatesDir = updatesCacheDir(),
                currentVersion = installed,
                onProgress = { downloaded, total ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (updateState is UpdateUiState.Downloading) {
                            updateState = UpdateUiState.Downloading(release, downloaded, total)
                        }
                    }
                },
                onVerifying = {
                    viewModelScope.launch(Dispatchers.Main) {
                        updateState = UpdateUiState.Verifying(release)
                    }
                },
                isCancelled = { updateDownloadCancelled },
            )
        }
        updateDownloadJob = null
        updateDownloadCancelled = false
        updateState = when (result) {
            is ApkDownloadResult.Success -> UpdateUiState.ReadyToInstall(release, result.apkFile, result.sha256Verified)
            ApkDownloadResult.Cancelled -> UpdateUiState.Available(release)
            is ApkDownloadResult.Failure -> UpdateUiState.Error(result.message, release)
        }
    }
}

/** Cancels an active APK download and cleans up the partial file. */
fun CanvasViewModel.cancelUpdateDownload() {
    updateDownloadCancelled = true
    updateDownloadJob?.cancel()
    updateDownloadJob = null
    val active = activeUpdateRelease()
    updateState = if (active != null) UpdateUiState.Available(active) else UpdateUiState.Idle
}

/** Hides the Gallery banner for [tagName] until a newer tag is released. */
fun CanvasViewModel.dismissUpdateBanner(tagName: String) {
    dismissedUpdateTag = tagName
    interfacePrefs.edit().putString(CanvasViewModel.UPDATE_DISMISSED_TAG, tagName).apply()
}

fun CanvasViewModel.changeUpdateAutoCheck(on: Boolean) {
    updateAutoCheck = on
    interfacePrefs.edit().putBoolean(CanvasViewModel.UPDATE_AUTO_CHECK, on).apply()
}

fun CanvasViewModel.changeUpdateIncludePreReleases(on: Boolean) {
    updateIncludePreReleases = on
    interfacePrefs.edit().putBoolean(CanvasViewModel.UPDATE_INCLUDE_PRE, on).apply()
    // Re-check immediately when changing channels inside the Update sheet.
    if (updateSheetOpen &&
        updateState !is UpdateUiState.Downloading &&
        updateState !is UpdateUiState.Verifying
    ) {
        checkForUpdates(manual = true)
    }
}

fun CanvasViewModel.openUpdateSheet(autoCheckIfIdle: Boolean = true) {
    updateSheetOpen = true
    if (autoCheckIfIdle && updateState is UpdateUiState.Idle) {
        checkForUpdates(manual = true)
    }
}

fun CanvasViewModel.closeUpdateSheet() {
    updateSheetOpen = false
}
