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

    @Test
    fun classifyHttpFailureDistinguishesRateLimitsFromGeneric403And429() {
        val now = 1_700_000_000_000L
        val resetIn15mSec = (now / 1000L) + 15L * 60L

        val primaryRateLimit = AppUpdater.classifyHttpFailure(
            AppUpdater.HttpStringResponse(
                code = 403,
                body = """{"message":"API rate limit exceeded"}""",
                rateLimitRemaining = "0",
                rateLimitReset = resetIn15mSec.toString(),
            ),
            nowMs = now,
        )
        assertTrue(primaryRateLimit.isRateLimited)
        assertEquals(resetIn15mSec * 1000L, primaryRateLimit.retryAtMs)
        assertEquals(
            "GitHub rate limit reached. Try again in about 15 minutes or open GitHub Releases.",
            primaryRateLimit.message,
        )

        val bodyOnlyRateLimit = AppUpdater.classifyHttpFailure(
            AppUpdater.HttpStringResponse(
                code = 403,
                body = "You have exceeded a secondary rate limit.",
            ),
            nowMs = now,
        )
        assertTrue(bodyOnlyRateLimit.isRateLimited)
        assertNull(bodyOnlyRateLimit.retryAtMs)
        assertEquals("GitHub rate limit reached. Try again later or open GitHub Releases.", bodyOnlyRateLimit.message)

        val generic403 = AppUpdater.classifyHttpFailure(
            AppUpdater.HttpStringResponse(
                code = 403,
                body = "Forbidden by corporate proxy",
                rateLimitRemaining = "54",
            ),
            nowMs = now,
        )
        assertFalse(generic403.isRateLimited)
        assertNull(generic403.retryAtMs)
        assertFalse(generic403.message.contains("rate limit", ignoreCase = true))
        assertTrue(generic403.message.contains("403"))

        val http429Minutes = AppUpdater.classifyHttpFailure(
            AppUpdater.HttpStringResponse(
                code = 429,
                body = "Too Many Requests",
                retryAfter = "90",
            ),
            nowMs = now,
        )
        assertTrue(http429Minutes.isRateLimited)
        assertEquals(now + 90_000L, http429Minutes.retryAtMs)
        assertEquals(
            "GitHub rate limit reached. Try again in about 2 minutes or open GitHub Releases.",
            http429Minutes.message,
        )

        val http429OneMinute = AppUpdater.classifyHttpFailure(
            AppUpdater.HttpStringResponse(
                code = 429,
                body = "",
                retryAfter = "25",
            ),
            nowMs = now,
        )
        assertTrue(http429OneMinute.isRateLimited)
        assertEquals(
            "GitHub rate limit reached. Try again in about 1 minute or open GitHub Releases.",
            http429OneMinute.message,
        )
    }

    @Test
    fun backoffCalculationAndAutoCheckRespectFailureWindow() {
        val now = 2_000_000_000_000L
        val knownReset = now + 20L * 60L * 1000L
        val rateLimited = UpdateCheckResult.Failure("limit", isRateLimited = true, retryAtMs = knownReset)
        assertEquals(knownReset, AppUpdater.nextBackoffUntilMs(rateLimited, now))

        val unknownFailure = UpdateCheckResult.Failure("offline", isRateLimited = false, retryAtMs = null)
        assertEquals(now + AppUpdater.FAILURE_BACKOFF_MS, AppUpdater.nextBackoffUntilMs(unknownFailure, now))

        // Auto-check stays paused until nextAllowedAtMs even if lastCheckedAtMs was never recorded.
        assertFalse(
            AppUpdater.shouldAutoCheck(
                autoCheckEnabled = true,
                lastCheckedAtMs = 0L,
                nowMs = now,
                nextAllowedAtMs = knownReset,
            ),
        )
        assertTrue(
            AppUpdater.shouldAutoCheck(
                autoCheckEnabled = true,
                lastCheckedAtMs = 0L,
                nowMs = knownReset,
                nextAllowedAtMs = knownReset,
            ),
        )
    }

    @Test
    fun atomFeedParsingDetectsPreReleasesDerivesAssetsAndCleansHtml() {
        val atomXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Release notes from procraft</title>
              <entry>
                <id>tag:github.com,2008:Repository/999/v0.0.5-rc.3</id>
                <updated>2026-11-22T14:00:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/ogibinedi/procraft/releases/tag/v0.0.5-rc.3"/>
                <title>ProCraft v0.0.5-rc.3 (Android Smartphone)</title>
                <content type="html">&lt;h2&gt;What&#39;s Changed&lt;/h2&gt;
                &lt;ul&gt;
                &lt;li&gt;Atom feed updater &amp;amp; backoff&lt;/li&gt;
                &lt;li&gt;Polished icon &amp;mdash; ready&lt;/li&gt;
                &lt;/ul&gt;
                &lt;p&gt;Full Changelog: https://github.com/ogibinedi/procraft/compare/v0.0.5-rc.2...v0.0.5-rc.3&lt;/p&gt;</content>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/999/v0.0.4</id>
                <updated>2026-11-22T09:00:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/ogibinedi/procraft/releases/tag/v0.0.4"/>
                <title>ProCraft v0.0.4</title>
                <content type="html">&lt;p&gt;Stable release&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        // Stable channel on 0.0.4 ignores v0.0.5-rc.3 (contains '-').
        val stableParsed = AppUpdater.parseAtomFeed(
            atomXml,
            currentVersion = "0.0.4",
            includePreReleases = false,
            supportedAbis = listOf("arm64-v8a"),
        )
        assertTrue(stableParsed is AppUpdater.AtomParseResult.Parsed)
        assertNull((stableParsed as AppUpdater.AtomParseResult.Parsed).release)

        // Pre-release channel on 0.0.5-rc.2 picks v0.0.5-rc.3 and derives arm64 APK + SHA256SUMS.txt.
        val rcParsed = AppUpdater.parseAtomFeed(
            atomXml,
            currentVersion = "0.0.5-rc.2",
            includePreReleases = true,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        ) as AppUpdater.AtomParseResult.Parsed
        val release = rcParsed.release
        assertNotNull(release)
        assertEquals("v0.0.5-rc.3", release!!.tagName)
        assertTrue(release.isPreRelease)
        assertEquals("procraft-0.0.5-rc.3-android-arm64.apk", release.apkAsset?.name)
        assertEquals(
            "https://github.com/ogibinedi/procraft/releases/download/v0.0.5-rc.3/procraft-0.0.5-rc.3-android-arm64.apk",
            release.apkAsset?.downloadUrl,
        )
        assertEquals(
            "https://github.com/ogibinedi/procraft/releases/download/v0.0.5-rc.3/SHA256SUMS.txt",
            release.checksumAsset?.downloadUrl,
        )
        assertTrue(release.notes.contains("What's Changed"))
        assertTrue(release.notes.contains("• Atom feed updater & backoff"))
        assertTrue(release.notes.contains("• Polished icon — ready"))
        assertFalse(release.notes.contains("Full Changelog:"))

        // 32-bit device gets legacy-armeabi-v7a APK; pure x86_64 device gets null apkAsset cleanly.
        val arm32 = (AppUpdater.parseAtomFeed(atomXml, "0.0.5-rc.2", true, listOf("armeabi-v7a")) as AppUpdater.AtomParseResult.Parsed).release
        assertEquals("procraft-0.0.5-rc.3-android-legacy-armeabi-v7a.apk", arm32?.apkAsset?.name)
        val x86 = (AppUpdater.parseAtomFeed(atomXml, "0.0.5-rc.2", true, listOf("x86_64")) as AppUpdater.AtomParseResult.Parsed).release
        assertNull(x86?.apkAsset)

        // Captive portal / HTML error page must be treated as Invalid, not UpToDate.
        assertEquals(
            AppUpdater.AtomParseResult.Invalid,
            AppUpdater.parseAtomFeed("<html><body>Sign in</body></html>", "0.0.5-rc.2", true, listOf("arm64-v8a")),
        )
    }

    @Test
    fun checkForUpdatesUsesAtomFirstAndFallsBackToRestApi() {
        val atomXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/999/v0.0.5</id>
                <updated>2026-11-22T15:00:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/ogibinedi/procraft/releases/tag/v0.0.5"/>
                <title>ProCraft v0.0.5</title>
                <content type="html">&lt;p&gt;Stable launch&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        // 1. Primary path: Atom succeeds -> REST API is never called.
        val primaryCalls = mutableListOf<String>()
        val primaryResult = AppUpdater.checkForUpdatesWithTransport(
            currentVersion = "0.0.5-rc.2",
            includePreReleases = true,
            supportedAbis = listOf("arm64-v8a"),
        ) { url, _ ->
            primaryCalls.add(url)
            AppUpdater.HttpStringResponse(200, atomXml)
        }
        assertEquals(listOf(AppUpdater.releasesAtomUrl()), primaryCalls)
        assertTrue(primaryResult is UpdateCheckResult.UpdateAvailable)
        assertEquals("v0.0.5", (primaryResult as UpdateCheckResult.UpdateAvailable).release.tagName)

        // 2. Fallback path: Atom returns 500 -> falls back to REST API and returns release.
        val fallbackCalls = mutableListOf<String>()
        val restJson = """
            {
              "tag_name": "v0.0.5",
              "name": "ProCraft v0.0.5",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/ogibinedi/procraft/releases/tag/v0.0.5",
              "body": "- Final stable",
              "assets": [
                {
                  "name": "procraft-0.0.5-android-arm64.apk",
                  "browser_download_url": "https://github.com/ogibinedi/procraft/releases/download/v0.0.5/procraft-0.0.5-android-arm64.apk",
                  "size": 25000000
                }
              ]
            }
        """.trimIndent()
        val fallbackResult = AppUpdater.checkForUpdatesWithTransport(
            currentVersion = "0.0.5-rc.2",
            includePreReleases = false,
            supportedAbis = listOf("arm64-v8a"),
        ) { url, _ ->
            fallbackCalls.add(url)
            if (url == AppUpdater.releasesAtomUrl()) AppUpdater.HttpStringResponse(500, "Server Error")
            else AppUpdater.HttpStringResponse(200, restJson)
        }
        assertEquals(listOf(AppUpdater.releasesAtomUrl(), AppUpdater.latestReleaseUrl()), fallbackCalls)
        assertTrue(fallbackResult is UpdateCheckResult.UpdateAvailable)
        assertEquals(25_000_000L, (fallbackResult as UpdateCheckResult.UpdateAvailable).release.apkAsset?.sizeBytes)

        // 3. Both Atom and REST fail with REST rate limit -> returns structured rate-limit failure with reset time.
        val now = 1_700_000_000_000L
        val resetSec = (now / 1000L) + 10L * 60L
        val rateLimitedResult = AppUpdater.checkForUpdatesWithTransport(
            currentVersion = "0.0.5-rc.2",
            includePreReleases = true,
            supportedAbis = listOf("arm64-v8a"),
            nowMs = now,
        ) { url, _ ->
            if (url == AppUpdater.releasesAtomUrl()) AppUpdater.HttpStringResponse(502, "Bad Gateway")
            else AppUpdater.HttpStringResponse(
                code = 403,
                body = """{"message":"API rate limit exceeded"}""",
                rateLimitRemaining = "0",
                rateLimitReset = resetSec.toString(),
            )
        }
        assertTrue(rateLimitedResult is UpdateCheckResult.Failure)
        val failure = rateLimitedResult as UpdateCheckResult.Failure
        assertTrue(failure.isRateLimited)
        assertEquals(resetSec * 1000L, failure.retryAtMs)
        assertTrue(failure.message.contains("about 10 minutes"))
    }
}
