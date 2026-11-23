package io.github.artcraftandroid.procraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.update.AppUpdateInstaller

private val ChangelogCardRadius = 16.dp
private val ChangelogCardFill get() = if (Pc.light) Color(0xFFFFFFFF) else Color(0xFF262626)
private val ChangelogCardBorder get() = if (Pc.light) Color(0xFFD4D4D4) else Color(0xFF393939)
private val ChangelogDivider get() = if (Pc.light) Color(0xFFE5E5E5) else Color(0xFF353535)
private val ChangelogPrimaryInk get() = if (Pc.light) Color(0xFF222222) else Color(0xFFF0F0F0)
private val ChangelogBodyInk get() = if (Pc.light) Color(0xFF3C3C3C) else Color(0xFFC8C8C8)
private val ChangelogMutedInk get() = if (Pc.light) Color(0xFF595959) else Color(0xFFAAAAAA)
private val ChangelogDoneFill get() = Pc.tinted(Color(0xFF0067DF))
private val ChangelogBadgeFill get() = if (Pc.light) Color(0xFFE3F0FF) else Color(0xFF1B334D)
private val ChangelogBadgeInk get() = if (Pc.light) Color(0xFF0055B8) else Color(0xFF78B8FF)

private fun categoryInk(category: ChangelogCategory): Color = when (category) {
    ChangelogCategory.ADDED -> if (Pc.light) Color(0xFF166E2E) else Color(0xFF5AD27A)
    ChangelogCategory.FIXED -> if (Pc.light) Color(0xFF0055B8) else Color(0xFF6EB2FF)
    ChangelogCategory.IMPROVED -> if (Pc.light) Color(0xFF8A5200) else Color(0xFFF5B942)
}

/**
 * Full-screen responsive Changelog sheet accessible from About ProCraft and Actions -> Help.
 * Displays expandable release cards from v0.0.1 through the newest bundled version.
 */
@Composable
internal fun ChangelogSheet(
    releases: List<ChangelogRelease> = AppChangelog.releases,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val installedVersion = remember { versionName(context) }
    var expandedVersions by remember(releases, installedVersion) {
        mutableStateOf(AppChangelog.defaultExpandedVersions(releases, installedVersion))
    }
    val allExpanded = releases.isNotEmpty() && expandedVersions.size >= releases.size

    Column(
        Modifier
            .fillMaxSize()
            .background(Pc.GalleryBackground)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Changelog",
                color = ChangelogPrimaryInk,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Box(
                Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(ChangelogDoneFill)
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Done", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (releases.isEmpty()) {
                    ChangelogEmptyCard(
                        onOpenGitHub = { AppUpdateInstaller.openUrlInBrowser(context, AppChangelog.changelogUrl()) },
                    )
                } else {
                    val rangeLabel = if (releases.size == 1) {
                        releases.first().tagName
                    } else {
                        "${releases.last().tagName} to ${releases.first().tagName} (${releases.size} releases)"
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Android Smartphone Edition",
                                color = ChangelogPrimaryInk,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                rangeLabel,
                                color = ChangelogMutedInk,
                                fontSize = 13.sp,
                            )
                        }
                        Box(
                            Modifier
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ChangelogCardFill)
                                .border(1.dp, ChangelogCardBorder, RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) {
                                    expandedVersions = if (allExpanded) {
                                        emptySet()
                                    } else {
                                        releases.map { it.version }.toSet()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (allExpanded) "Collapse all" else "Expand all",
                                color = ChangelogPrimaryInk,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }

                    val newestVersion = releases.first().version
                    for (release in releases) {
                        val isInstalled = AppChangelog.isInstalledRelease(release, installedVersion)
                        val statusBadge = when {
                            isInstalled -> "Installed"
                            release.version == newestVersion -> "Latest"
                            else -> null
                        }
                        val expanded = release.version in expandedVersions
                        ChangelogReleaseCard(
                            release = release,
                            statusBadge = statusBadge,
                            expanded = expanded,
                            onToggle = {
                                expandedVersions = if (expanded) {
                                    expandedVersions - release.version
                                } else {
                                    expandedVersions + release.version
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ChangelogCardFill)
                            .border(1.dp, ChangelogCardBorder, RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) {
                                AppUpdateInstaller.openUrlInBrowser(context, AppChangelog.changelogUrl())
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "View Full Changelog on GitHub",
                            color = ChangelogPrimaryInk,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ChangelogReleaseCard(
    release: ChangelogRelease,
    statusBadge: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "changelogChevron",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChangelogCardRadius))
            .background(ChangelogCardFill)
            .border(1.dp, ChangelogCardBorder, RoundedCornerShape(ChangelogCardRadius)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        release.tagName,
                        color = ChangelogPrimaryInk,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (statusBadge != null) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ChangelogBadgeFill)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                statusBadge,
                                color = ChangelogBadgeInk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        release.date,
                        color = ChangelogMutedInk,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    release.title,
                    color = ChangelogBodyInk,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 19.sp,
                )
            }
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(
                    PcIcons.ChevronDown,
                    contentDescription = if (expanded) "Collapse ${release.tagName}" else "Expand ${release.tagName}",
                    tint = ChangelogMutedInk,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation },
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(ChangelogDivider))
                for (section in release.sections) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            section.category.label,
                            color = categoryInk(section.category),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        for (item in section.items) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "•",
                                    color = ChangelogMutedInk,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                )
                                Text(
                                    item,
                                    color = ChangelogBodyInk,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogEmptyCard(onOpenGitHub: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChangelogCardRadius))
            .background(ChangelogCardFill)
            .border(1.dp, ChangelogCardBorder, RoundedCornerShape(ChangelogCardRadius))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Release history is not bundled in this build",
            color = ChangelogPrimaryInk,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Open the ProCraft repository on GitHub to read the complete list of version changes.",
            color = ChangelogBodyInk,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ChangelogDoneFill)
                .clickable(role = Role.Button, onClick = onOpenGitHub)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Open Changelog on GitHub", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
