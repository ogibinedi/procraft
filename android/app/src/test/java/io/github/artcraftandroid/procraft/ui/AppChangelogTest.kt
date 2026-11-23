package io.github.artcraftandroid.procraft.ui

import io.github.artcraftandroid.procraft.update.AppVersion
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppChangelogTest {
    @Test
    fun catalogCoversInitialToLatestInDescendingSemVerOrder() {
        val releases = AppChangelog.releases
        assertTrue("Expected at least 6 releases (v0.0.1 to v0.0.6)", releases.size >= 6)
        assertEquals("0.0.1", releases.last().version)
        assertEquals("0.0.6", releases.first().version)

        val seenVersions = mutableSetOf<String>()
        var previous: AppVersion? = null
        for (release in releases) {
            assertTrue("Duplicate version ${release.version}", seenVersions.add(release.version))
            val parsed = AppVersion.parse(release.version)
            assertNotNull("Invalid SemVer ${release.version}", parsed)
            assertFalse("Bundled changelog lists stable releases only: ${release.version}", parsed!!.isPreRelease)
            if (previous != null) {
                assertTrue("Releases must be strictly descending: $previous vs $parsed", previous > parsed)
            }
            previous = parsed

            assertTrue(release.date.isNotBlank())
            assertTrue(release.title.isNotBlank())
            assertTrue(release.sections.isNotEmpty())
            val categories = release.sections.map { it.category }
            assertEquals("Duplicate categories in ${release.version}", categories.size, categories.distinct().size)
            for (section in release.sections) {
                assertTrue(section.items.isNotEmpty())
                for (item in section.items) {
                    assertTrue(item.isNotBlank())
                }
            }
        }
    }

    @Test
    fun catalogIncludesCurrentStableGradleVersion() {
        val propsFile = listOf(File("../gradle.properties"), File("gradle.properties")).firstOrNull { it.isFile }
        assertNotNull("Could not locate android/gradle.properties", propsFile)
        val props = Properties().apply { propsFile!!.inputStream().use(::load) }
        val configured = props.getProperty("procraft.version")?.trim().orEmpty()
        val parsed = AppVersion.parse(configured)
        assertNotNull("Invalid procraft.version in gradle.properties: $configured", parsed)
        if (!parsed!!.isPreRelease) {
            assertEquals(
                "Add a user-facing AppChangelog entry for stable version $configured",
                configured,
                AppChangelog.releases.first().version,
            )
        }
    }

    @Test
    fun defaultExpandedPrefersInstalledVersionAndFallsBackToLatest() {
        assertEquals(
            setOf("0.0.4"),
            AppChangelog.defaultExpandedVersions(installedVersion = "0.0.4"),
        )
        assertEquals(
            setOf("0.0.6"),
            AppChangelog.defaultExpandedVersions(installedVersion = "0.0.7-rc.1"),
        )
        assertEquals(
            setOf("0.0.6"),
            AppChangelog.defaultExpandedVersions(installedVersion = ""),
        )
        assertEquals(
            emptySet<String>(),
            AppChangelog.defaultExpandedVersions(items = emptyList(), installedVersion = "0.0.6"),
        )
    }

    @Test
    fun preReleaseBuildsDoNotMislabelStableReleaseAsInstalled() {
        val v005 = AppChangelog.releases.first { it.version == "0.0.5" }
        assertTrue(AppChangelog.isInstalledRelease(v005, "0.0.5"))
        assertTrue(AppChangelog.isInstalledRelease(v005, "v0.0.5"))
        assertFalse(AppChangelog.isInstalledRelease(v005, "0.0.5-rc.4"))
    }

    @Test
    fun githubUrlsAndCopyFormattingStayValid() {
        assertEquals(
            "https://github.com/ogibinedi/procraft/blob/main/CHANGELOG.md",
            AppChangelog.changelogUrl(),
        )
        assertEquals(
            "https://github.com/ogibinedi/procraft/releases/tag/v0.0.6",
            AppChangelog.releaseTagUrl("0.0.6"),
        )
        for (release in AppChangelog.releases) {
            assertFalse("Avoid em dash in title: ${release.title}", release.title.contains('—'))
            for (section in release.sections) {
                for (item in section.items) {
                    assertFalse("Avoid em dash in item: $item", item.contains('—'))
                }
            }
        }
    }
}
