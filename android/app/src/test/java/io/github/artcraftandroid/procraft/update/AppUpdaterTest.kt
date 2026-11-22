package io.github.artcraftandroid.procraft.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AppUpdaterTest {

    @Test
    fun semverOrderingHandlesStableAndPreReleases() {
        val versions = listOf(
            "0.0.4",
            "0.0.5-alpha.1",
            "0.0.5-beta.1",
            "0.0.5-rc.1",
            "0.0.5-rc.2",
            "0.0.5-rc.10",
            "v0.0.5",
            "0.1.0-rc.1",
            "0.1.0",
            "1.0.0",
        ).map { requireNotNull(AppVersion.parse(it)) }

        for (i in 0 until versions.lastIndex) {
            assertTrue("${versions[i]} should be < ${versions[i + 1]}", versions[i] < versions[i + 1])
        }
        assertEquals(AppVersion.parse("v0.0.5"), AppVersion.parse("0.0.5"))
        assertNull(AppVersion.parse("not-a-version"))
    }

    @Test
    fun defaultPreReleaseChannelFollowsInstalledBuild() {
        assertTrue(AppUpdater.defaultIncludePreReleases("0.0.5-rc.1"))
        assertTrue(AppUpdater.defaultIncludePreReleases("0.1.0-beta"))
        assertFalse(AppUpdater.defaultIncludePreReleases("0.0.5"))
        assertFalse(AppUpdater.defaultIncludePreReleases("0.0.4"))
    }

    @Test
    fun parseReleasePayloadRespectsPreReleaseFlagAndSelectsDeviceAbi() {
        val json = """
            [
              {
                "tag_name": "v0.0.6-rc.1",
                "name": "ProCraft v0.0.6-rc.1",
                "draft": true,
                "prerelease": true,
                "html_url": "https://github.com/ogibinedi/procraft/releases/tag/v0.0.6-rc.1",
                "body": "Draft release should be ignored",
                "assets": []
              },
              {
                "tag_name": "v0.0.5-rc.2",
                "name": "ProCraft v0.0.5-rc.2 (Android Smartphone)",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/ogibinedi/procraft/releases/tag/v0.0.5-rc.2",
                "published_at": "2026-11-22T12:00:00Z",
                "body": "## What's Changed\r\n- Added in-app updater\r\n- **Faster** gallery search\r\n**Full Changelog**: https://github.com/ogibinedi/procraft/compare/v0.0.5-rc.1...v0.0.5-rc.2",
                "assets": [
                  {
                    "name": "procraft-0.0.5-rc.2-android-arm64.apk",
                    "browser_download_url": "https://github.com/ogibinedi/procraft/releases/download/v0.0.5-rc.2/procraft-0.0.5-rc.2-android-arm64.apk",
                    "size": 25165824
                  },
                  {
                    "name": "procraft-0.0.5-rc.2-android-legacy-armeabi-v7a.apk",
                    "browser_download_url": "https://github.com/ogibinedi/procraft/releases/download/v0.0.5-rc.2/procraft-0.0.5-rc.2-android-legacy-armeabi-v7a.apk",
                    "size": 22020096
                  },
                  {
                    "name": "SHA256SUMS.txt",
                    "browser_download_url": "https://github.com/ogibinedi/procraft/releases/download/v0.0.5-rc.2/SHA256SUMS.txt",
                    "size": 210
                  }
                ]
              },
              {
                "tag_name": "v0.0.4",
                "name": "ProCraft v0.0.4",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/ogibinedi/procraft/releases/tag/v0.0.4",
                "body": "Stable release",
                "assets": []
              }
            ]
        """.trimIndent()

        // Stable channel on 0.0.4 ignores v0.0.5-rc.2 pre-release and draft v0.0.6-rc.1.
        assertNull(AppUpdater.parseReleasePayload(json, "0.0.4", includePreReleases = false, supportedAbis = listOf("arm64-v8a")))

        // Pre-release channel on 0.0.5-rc.1 picks v0.0.5-rc.2 and arm64 APK.
        val arm64Update = AppUpdater.parseReleasePayload(json, "0.0.5-rc.1", includePreReleases = true, supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))
        assertNotNull(arm64Update)
        assertEquals("v0.0.5-rc.2", arm64Update!!.tagName)
        assertTrue(arm64Update.isPreRelease)
        assertEquals("procraft-0.0.5-rc.2-android-arm64.apk", arm64Update.apkAsset?.name)
        assertEquals("SHA256SUMS.txt", arm64Update.checksumAsset?.name)
        assertTrue(arm64Update.notes.contains("• Added in-app updater"))
        assertTrue(arm64Update.notes.contains("• Faster gallery search"))
        assertFalse(arm64Update.notes.contains("Full Changelog"))

        // 32-bit device picks legacy armeabi-v7a APK.
        val arm32Update = AppUpdater.parseReleasePayload(json, "0.0.5-rc.1", includePreReleases = true, supportedAbis = listOf("armeabi-v7a"))
        assertEquals("procraft-0.0.5-rc.2-android-legacy-armeabi-v7a.apk", arm32Update?.apkAsset?.name)

        // Device already on v0.0.5-rc.2 gets no update.
        assertNull(AppUpdater.parseReleasePayload(json, "0.0.5-rc.2", includePreReleases = true, supportedAbis = listOf("arm64-v8a")))
    }

    @Test
    fun parseSingleLatestReleaseObjectMatchesStableUpgradeFromRc() {
        val latestJson = """
            {
              "tag_name": "v0.0.5",
              "name": "ProCraft v0.0.5",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/ogibinedi/procraft/releases/tag/v0.0.5",
              "body": "Final release\u2014ready for everyone",
              "assets": [
                {
                  "name": "procraft-0.0.5-android-arm64.apk",
                  "browser_download_url": "https://github.com/ogibinedi/procraft/releases/download/v0.0.5/procraft-0.0.5-android-arm64.apk",
                  "size": 26000000
                }
              ]
            }
        """.trimIndent()

        val update = AppUpdater.parseReleasePayload(latestJson, "0.0.5-rc.1", includePreReleases = false, supportedAbis = listOf("arm64-v8a"))
        assertNotNull(update)
        assertEquals("v0.0.5", update!!.tagName)
        assertFalse(update.isPreRelease)
        assertEquals("Final release—ready for everyone", update.notes)
    }

    @Test
    fun sha256ParsingAndComputationDetectIntegrity() {
        val dir = createTempDirectory("procraft-update-test").toFile()
        try {
            val apk = File(dir, "procraft-0.0.5-android-arm64.apk")
            apk.writeText("synthetic-apk-payload")
            val actualHash = AppUpdater.computeSha256(apk)
            val sums = """
                $actualHash  procraft-0.0.5-android-arm64.apk
                0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  procraft-0.0.5-android-legacy-armeabi-v7a.apk
            """.trimIndent()

            assertEquals(actualHash, AppUpdater.parseSha256Sums(sums, "procraft-0.0.5-android-arm64.apk"))
            assertNull(AppUpdater.parseSha256Sums(sums, "missing.apk"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadUrlValidationEnforcesHttpsAndGitHubHosts() {
        assertTrue(AppUpdater.validateDownloadUrl("https://github.com/ogibinedi/procraft/releases/download/v0.0.5/app.apk"))
        assertTrue(AppUpdater.validateDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(AppUpdater.validateDownloadUrl("https://release-assets.githubusercontent.com/abc"))
        assertTrue(AppUpdater.validateDownloadUrl("https://api.github.com/repos/ogibinedi/procraft/releases/latest"))

        assertFalse(AppUpdater.validateDownloadUrl("http://github.com/ogibinedi/procraft/releases/download/v0.0.5/app.apk"))
        assertFalse(AppUpdater.validateDownloadUrl("https://github.com.evil.example/app.apk"))
        assertFalse(AppUpdater.validateDownloadUrl("https://evilgithubusercontent.com/app.apk"))
        assertFalse(AppUpdater.validateDownloadUrl("file:///sdcard/app.apk"))
    }

    @Test
    fun autoCheckRespectsToggleAndCooldownInterval() {
        val now = 1_000_000_000L
        assertFalse(AppUpdater.shouldAutoCheck(autoCheckEnabled = false, lastCheckedAtMs = 0L, nowMs = now))
        assertTrue(AppUpdater.shouldAutoCheck(autoCheckEnabled = true, lastCheckedAtMs = 0L, nowMs = now))
        assertFalse(AppUpdater.shouldAutoCheck(autoCheckEnabled = true, lastCheckedAtMs = now - 60_000L, nowMs = now))
        assertTrue(
            AppUpdater.shouldAutoCheck(
                autoCheckEnabled = true,
                lastCheckedAtMs = now - AppUpdater.AUTO_CHECK_INTERVAL_MS,
                nowMs = now,
            ),
        )
    }
}
