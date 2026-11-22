package io.github.artcraftandroid.procraft.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.artcraftandroid.procraft.AppLog
import java.io.File

/**
 * Android package-installer and browser fallback helpers for ProCraft's GitHub Releases updater.
 */
object AppUpdateInstaller {
    /** True when the user has granted ProCraft permission to launch the Android APK installer. */
    fun canInstallPackages(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Opens the per-app "Install unknown apps" permission screen for ProCraft. */
    fun openUnknownSourcesSettings(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.onFailure {
        AppLog.e("update", "opening install unknown apps settings failed", it)
    }.getOrDefault(false)

    /**
     * Launches the Android system package installer for a verified local [apkFile] served via
     * ProCraft's `FileProvider`.
     */
    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure {
            AppLog.e("update", "launching APK installer failed", it)
        }.getOrDefault(false)
    }

    /** Opens a GitHub Releases page or asset link in the device's default browser. */
    fun openUrlInBrowser(context: Context, url: String): Boolean = runCatching {
        if (!AppUpdater.validateDownloadUrl(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.onFailure {
        AppLog.e("update", "opening release url failed", it)
    }.getOrDefault(false)
}
