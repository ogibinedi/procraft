package io.github.artcraftandroid.procraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.UpdateUiState
import io.github.artcraftandroid.procraft.canvas.activeUpdateRelease
import io.github.artcraftandroid.procraft.canvas.cancelUpdateDownload
import io.github.artcraftandroid.procraft.canvas.changeUpdateAutoCheck
import io.github.artcraftandroid.procraft.canvas.changeUpdateIncludePreReleases
import io.github.artcraftandroid.procraft.canvas.checkForUpdates
import io.github.artcraftandroid.procraft.canvas.dismissUpdateBanner
import io.github.artcraftandroid.procraft.canvas.startUpdateDownload
import io.github.artcraftandroid.procraft.update.AppUpdateInstaller
import io.github.artcraftandroid.procraft.update.AppUpdater
import io.github.artcraftandroid.procraft.update.UpdateRelease
import kotlin.math.roundToInt

private val UpdateBannerRadius = 14.dp
private val VerifiedGreen = Color(0xFF34C759)
private val PreReleaseAmber = Color(0xFFFF9F0A)

/**
 * Compact, unobtrusive update banner shown beneath [GalleryUtilityBar] only when a newer
 * GitHub Release is available, downloading, or ready to install.
 */
@Composable
internal fun GalleryUpdateBanner(
    vm: CanvasViewModel,
    screenWidth: Dp,
    onOpen: () -> Unit,
    onDismiss: (String) -> Unit,
) {
    val release = vm.activeUpdateRelease() ?: return
    val sidePad = if (screenWidth < 480.dp) 16.dp else 20.dp
    val state = vm.updateState
    val title = when (state) {
        is UpdateUiState.Downloading -> "Downloading ${release.tagName} · ${(state.progress * 100f).roundToInt()}%"
        is UpdateUiState.Verifying -> "Verifying ${release.tagName}…"
        is UpdateUiState.ReadyToInstall -> "${release.tagName} ready to install"
        else -> "Update available: ${release.tagName}"
    }
    val subtitle = when (state) {
        is UpdateUiState.Downloading -> "${AppUpdater.formatBytes(state.downloadedBytes)} / ${AppUpdater.formatBytes(state.totalBytes)}"
        is UpdateUiState.Verifying -> "Checking SHA-256 signature"
        is UpdateUiState.ReadyToInstall -> if (state.sha256Verified) "SHA-256 verified · Tap to install" else "Tap to install"
        else -> {
            val asset = release.apkAsset
            if (asset != null && asset.sizeBytes > 0L) {
                "${AppUpdater.formatBytes(asset.sizeBytes)} · ${AppUpdater.abiDisplayLabel(asset.name)}"
            } else {
                "Tap to view release notes"
            }
        }
    }
    val buttonLabel = when (state) {
        is UpdateUiState.Downloading -> "Progress"
        is UpdateUiState.Verifying -> "Verifying"
        is UpdateUiState.ReadyToInstall -> "Install"
        else -> "Update"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad, vertical = 4.dp)
            .clip(RoundedCornerShape(UpdateBannerRadius))
            .background(Pc.Capsule)
            .border(1.dp, Pc.Accent.copy(alpha = 0.45f), RoundedCornerShape(UpdateBannerRadius))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Pc.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            UpdateArrowGlyph(Pc.Accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                color = Pc.GalleryTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = Pc.GallerySize,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Pc.Accent)
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(buttonLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (state is UpdateUiState.Available) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss(release.tagName) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(PcIcons.Cross, contentDescription = "Dismiss update", tint = Pc.GallerySize, modifier = Modifier.size(13.dp))
            }
        }
    }
}

/**
 * Full-screen responsive Software Update sheet for GitHub Releases: shows release notes,
 * device ABI & size, live verified APK download progress, Android unknown-sources permission
 * handoff, browser fallback, and auto-check / pre-release channel settings.
 */
@Composable
internal fun AppUpdateSheet(vm: CanvasViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentVersion = remember { versionName(context).ifBlank { "0.0.0" } }

    var canInstall by remember { mutableStateOf(AppUpdateInstaller.canInstallPackages(context)) }
    var awaitingInstallPermission by remember { mutableStateOf(false) }

    // Re-check install permission when returning from Android's "Install unknown apps" screen.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val allowed = AppUpdateInstaller.canInstallPackages(context)
                canInstall = allowed
                if (allowed && awaitingInstallPermission) {
                    awaitingInstallPermission = false
                    val ready = vm.updateState as? UpdateUiState.ReadyToInstall
                    if (ready != null) {
                        AppUpdateInstaller.launchApkInstaller(context, ready.apkFile)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Auto-launch the Android installer immediately once a download finishes and permission is already granted.
    val state = vm.updateState
    var autoLaunchedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, canInstall) {
        if (state is UpdateUiState.ReadyToInstall && canInstall && autoLaunchedPath != state.apkFile.path) {
            autoLaunchedPath = state.apkFile.path
            AppUpdateInstaller.launchApkInstaller(context, state.apkFile)
        }
    }

    fun triggerInstall(ready: UpdateUiState.ReadyToInstall) {
        if (AppUpdateInstaller.canInstallPackages(context)) {
            canInstall = true
            awaitingInstallPermission = false
            val launched = AppUpdateInstaller.launchApkInstaller(context, ready.apkFile)
            if (!launched) {
                vm.message = "Couldn't open Android package installer."
            }
        } else {
            awaitingInstallPermission = true
            AppUpdateInstaller.openUnknownSourcesSettings(context)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pc.GalleryBackground)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // Header bar
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Software Update",
                color = Pc.GalleryTitle,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(Pc.Accent)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Done", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val release = vm.activeUpdateRelease()
                when (state) {
                    is UpdateUiState.Idle, is UpdateUiState.Checking -> {
                        UpdateStatusCard(
                            heading = if (state is UpdateUiState.Checking) "Checking for updates…" else "Installed version",
                            badge = "v$currentVersion",
                            badgeColor = Pc.Capsule,
                            badgeInk = Pc.GalleryTitle,
                            detail = if (state is UpdateUiState.Checking) {
                                "Contacting GitHub Releases for ${AppUpdater.GITHUB_OWNER}/${AppUpdater.GITHUB_REPO}."
                            } else {
                                "Check GitHub Releases for newer ProCraft builds."
                            },
                        ) {
                            UpdatePrimaryButton(
                                label = if (state is UpdateUiState.Checking) "Checking…" else "Check Now",
                                enabled = state !is UpdateUiState.Checking,
                                onClick = { vm.checkForUpdates(manual = true) },
                            )
                        }
                    }

                    is UpdateUiState.UpToDate -> {
                        UpdateStatusCard(
                            heading = "ProCraft is up to date",
                            badge = "v${state.currentVersion}",
                            badgeColor = VerifiedGreen.copy(alpha = 0.18f),
                            badgeInk = VerifiedGreen,
                            detail = "You already have the latest available release on this update channel.",
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                UpdatePrimaryButton(
                                    label = "Check Again",
                                    modifier = Modifier.weight(1f),
                                    onClick = { vm.checkForUpdates(manual = true) },
                                )
                                UpdateSecondaryButton(
                                    label = "GitHub",
                                    onClick = { AppUpdateInstaller.openUrlInBrowser(context, AppUpdater.releasesPageUrl()) },
                                )
                            }
                        }
                    }

                    is UpdateUiState.Error -> {
                        UpdateStatusCard(
                            heading = "Update check couldn't complete",
                            badge = "v$currentVersion",
                            badgeColor = Pc.Capsule,
                            badgeInk = Pc.GalleryTitle,
                            detail = state.message,
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                UpdatePrimaryButton(
                                    label = if (release != null) "Retry Download" else "Try Again",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (release != null) vm.startUpdateDownload(release)
                                        else vm.checkForUpdates(manual = true)
                                    },
                                )
                                UpdateSecondaryButton(
                                    label = "Open GitHub",
                                    onClick = {
                                        AppUpdateInstaller.openUrlInBrowser(
                                            context,
                                            release?.htmlUrl ?: AppUpdater.releasesPageUrl(),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    is UpdateUiState.Available,
                    is UpdateUiState.Downloading,
                    is UpdateUiState.Verifying,
                    is UpdateUiState.ReadyToInstall -> {
                        if (release != null) {
                            ReleaseCard(
                                release = release,
                                currentVersion = currentVersion,
                                state = state,
                                canInstall = canInstall,
                                onDownload = { vm.startUpdateDownload(release) },
                                onCancel = { vm.cancelUpdateDownload() },
                                onInstall = {
                                    (state as? UpdateUiState.ReadyToInstall)?.let(::triggerInstall)
                                },
                                onOpenGitHub = { AppUpdateInstaller.openUrlInBrowser(context, release.htmlUrl) },
                                onSkip = {
                                    vm.dismissUpdateBanner(release.tagName)
                                    onClose()
                                },
                            )
                        }
                    }
                }

                // Release notes card when an update release is present
                release?.let { rel ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Pc.GalleryPopover)
                            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(18.dp))
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("What's New", color = Pc.GalleryTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            rel.notes,
                            color = Pc.GalleryText,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp,
                        )
                    }
                }

                // Preferences card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Pc.GalleryPopover)
                        .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(18.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Update Preferences", color = Pc.GalleryTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    UpdateToggleRow(
                        label = "Auto-check in Gallery",
                        detail = "Quietly checks GitHub Releases up to twice a day when opening Gallery.",
                        checked = vm.updateAutoCheck,
                        onChange = vm::changeUpdateAutoCheck,
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.CapsuleEdge))
                    UpdateToggleRow(
                        label = "Include pre-release (Beta / RC)",
                        detail = "Receive release candidates for early testing before they reach stable.",
                        checked = vm.updateIncludePreReleases,
                        onChange = vm::changeUpdateIncludePreReleases,
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.CapsuleEdge))
                    Text(
                        "Artworks, brushes, palettes, and settings are preserved when installing signed ProCraft updates.",
                        color = Pc.GallerySize,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun UpdateStatusCard(
    heading: String,
    badge: String,
    badgeColor: Color,
    badgeInk: Color,
    detail: String,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Pc.GalleryPopover)
            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(heading, color = Pc.GalleryTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(badgeColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(badge, color = badgeInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Text(detail, color = Pc.GallerySize, fontSize = 14.sp, lineHeight = 20.sp)
        actions()
    }
}

@Composable
private fun ReleaseCard(
    release: UpdateRelease,
    currentVersion: String,
    state: UpdateUiState,
    canInstall: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onOpenGitHub: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Pc.GalleryPopover)
            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ProCraft ${release.tagName}",
                color = Pc.GalleryTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (release.isPreRelease) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(PreReleaseAmber.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Pre-release", color = PreReleaseAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        val asset = release.apkAsset
        val metaParts = buildList {
            add("Installed v$currentVersion")
            if (asset != null) {
                add(AppUpdater.abiDisplayLabel(asset.name))
                if (asset.sizeBytes > 0L) add(AppUpdater.formatBytes(asset.sizeBytes))
            }
            if (release.checksumAsset != null) add("SHA-256")
        }
        Text(metaParts.joinToString(" · "), color = Pc.GallerySize, fontSize = 13.5.sp)

        when (state) {
            is UpdateUiState.Downloading -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Pc.Row),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(state.progress.coerceIn(0.03f, 1f))
                                .clip(CircleShape)
                                .background(Pc.Accent),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Downloading… ${(state.progress * 100f).roundToInt()}%",
                            color = Pc.GalleryTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${AppUpdater.formatBytes(state.downloadedBytes)} / ${AppUpdater.formatBytes(state.totalBytes)}",
                            color = Pc.GallerySize,
                            fontSize = 13.sp,
                        )
                    }
                    UpdateSecondaryButton(
                        label = "Cancel Download",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCancel,
                    )
                }
            }

            is UpdateUiState.Verifying -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Pc.Accent.copy(alpha = 0.35f)),
                    )
                    Text(
                        "Verifying SHA-256 checksum…",
                        color = Pc.GalleryTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            is UpdateUiState.ReadyToInstall -> {
                if (state.sha256Verified) {
                    Text(
                        "APK verified against GitHub SHA256SUMS.txt.",
                        color = VerifiedGreen,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (!canInstall) {
                    Text(
                        "Android requires one-time permission to install updates from ProCraft. Tap below to allow, then return here.",
                        color = Pc.GallerySize,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                    )
                }
                UpdatePrimaryButton(
                    label = if (canInstall) "Install Update" else "Allow & Install Update",
                    onClick = onInstall,
                )
            }

            else -> {
                if (asset != null) {
                    UpdatePrimaryButton(
                        label = "Download & Install",
                        onClick = onDownload,
                    )
                } else {
                    Text(
                        "No APK matching this device's CPU architecture was attached to this release.",
                        color = Pc.GallerySize,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                    )
                    UpdatePrimaryButton(
                        label = "Open on GitHub",
                        onClick = onOpenGitHub,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (asset != null) {
                        UpdateSecondaryButton(
                            label = "Open on GitHub",
                            modifier = Modifier.weight(1f),
                            onClick = onOpenGitHub,
                        )
                    }
                    UpdateSecondaryButton(
                        label = "Skip Version",
                        modifier = Modifier.weight(1f),
                        onClick = onSkip,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatePrimaryButton(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Pc.Accent else Pc.Accent.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun UpdateSecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Pc.Row)
            .border(1.dp, Pc.CapsuleEdge, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Pc.GalleryTitle, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun UpdateToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Pc.GalleryTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Pc.GallerySize, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Switch(checked, enabled = true)
    }
}

@Composable
private fun UpdateArrowGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        val cx = size.width * 0.5f
        drawLine(tint, Offset(cx, size.height * 0.16f), Offset(cx, size.height * 0.68f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.28f, size.height * 0.48f), Offset(cx, size.height * 0.72f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.72f, size.height * 0.48f), Offset(cx, size.height * 0.72f), stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(size.width * 0.22f, size.height * 0.88f), Offset(size.width * 0.78f, size.height * 0.88f), stroke, cap = StrokeCap.Round)
    }
}
