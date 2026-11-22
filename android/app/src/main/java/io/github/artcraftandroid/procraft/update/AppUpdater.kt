package io.github.artcraftandroid.procraft.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Semantic version matching ProCraft's `gradle.properties` and GitHub tag format
 * (`0.0.5`, `v0.0.5-rc.1`, `0.1.0-beta.2`, etc.).
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preLabel: String? = null,
    val preNumber: Int? = null,
    val raw: String = formatRaw(major, minor, patch, preLabel, preNumber),
) : Comparable<AppVersion> {

    val isPreRelease: Boolean get() = preLabel != null

    val displayName: String
        get() = formatRaw(major, minor, patch, preLabel, preNumber)

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // Stable release is always newer than any pre-release of the same major.minor.patch.
        val aPre = preLabel
        val bPre = other.preLabel
        if (aPre == null && bPre == null) return 0
        if (aPre == null) return 1
        if (bPre == null) return -1
        val labelCmp = preRank(aPre).compareTo(preRank(bPre))
        if (labelCmp != 0) return labelCmp
        val alphaCmp = aPre.lowercase(Locale.ROOT).compareTo(bPre.lowercase(Locale.ROOT))
        if (alphaCmp != 0) return alphaCmp
        return (preNumber ?: 1).compareTo(other.preNumber ?: 1)
    }

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+?)(?:\.(\d+))?)?$""")

        fun parse(text: String?): AppVersion? {
            val cleaned = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val m = PATTERN.matchEntire(cleaned) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].toIntOrNull() ?: return null
            val preLabel = m.groupValues[4].takeIf { it.isNotEmpty() }
            val preNumber = m.groupValues[5].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return AppVersion(major, minor, patch, preLabel, preNumber, cleaned.removePrefix("v").removePrefix("V"))
        }

        private fun preRank(label: String): Int = when (label.lowercase(Locale.ROOT)) {
            "dev", "snapshot" -> 0
            "alpha", "a" -> 1
            "beta", "b" -> 2
            "rc" -> 3
            else -> 2
        }

        private fun formatRaw(major: Int, minor: Int, patch: Int, preLabel: String?, preNumber: Int?): String =
            buildString {
                append(major).append('.').append(minor).append('.').append(patch)
                if (preLabel != null) {
                    append('-').append(preLabel)
                    if (preNumber != null) append('.').append(preNumber)
                }
            }
    }
}

/** One downloadable file attached to a GitHub Release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/** Parsed update candidate from GitHub Releases. */
data class UpdateRelease(
    val tagName: String,
    val version: AppVersion,
    val title: String,
    val notes: String,
    val htmlUrl: String,
    val publishedAt: String,
    val isPreRelease: Boolean,
    val apkAsset: ReleaseAsset?,
    val checksumAsset: ReleaseAsset?,
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: UpdateRelease) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Failure(
        val message: String,
        val isRateLimited: Boolean = false,
        val retryAtMs: Long? = null,
    ) : UpdateCheckResult
}

sealed interface ApkDownloadResult {
    data class Success(val apkFile: File, val sha256Verified: Boolean) : ApkDownloadResult
    data object Cancelled : ApkDownloadResult
    data class Failure(val message: String) : ApkDownloadResult
}

/**
 * Pure-Kotlin GitHub Releases update checker and verified APK downloader.
 * Zero third-party networking or JSON dependencies so version/parsing/checksum logic stays
 * completely unit-testable on the JVM.
 */
object AppUpdater {
    const val GITHUB_OWNER = "ogibinedi"
    const val GITHUB_REPO = "procraft"

    /** Minimum interval between automatic background update checks (12 hours). */
    const val AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

    /** Fallback backoff interval after a failed update check when no reset timestamp is available (1 hour). */
    const val FAILURE_BACKOFF_MS = 60L * 60L * 1000L

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val METADATA_READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    fun latestReleaseUrl(owner: String = GITHUB_OWNER, repo: String = GITHUB_REPO): String =
        "https://api.github.com/repos/$owner/$repo/releases/latest"

    fun allReleasesUrl(owner: String = GITHUB_OWNER, repo: String = GITHUB_REPO): String =
        "https://api.github.com/repos/$owner/$repo/releases?per_page=15"

    fun releasesAtomUrl(owner: String = GITHUB_OWNER, repo: String = GITHUB_REPO): String =
        "https://github.com/$owner/$repo/releases.atom"

    fun releasesPageUrl(owner: String = GITHUB_OWNER, repo: String = GITHUB_REPO): String =
        "https://github.com/$owner/$repo/releases"

    /** Users already running a pre-release build (RC/beta) opt into pre-releases by default. */
    fun defaultIncludePreReleases(currentVersion: String?): Boolean =
        AppVersion.parse(currentVersion)?.isPreRelease == true

    /** Rate-limits background checks and honors any active failure or rate-limit backoff window. */
    fun shouldAutoCheck(
        autoCheckEnabled: Boolean,
        lastCheckedAtMs: Long,
        nowMs: Long,
        minIntervalMs: Long = AUTO_CHECK_INTERVAL_MS,
        nextAllowedAtMs: Long = 0L,
    ): Boolean {
        if (!autoCheckEnabled) return false
        if (nextAllowedAtMs > 0L && nowMs < nextAllowedAtMs) return false
        if (lastCheckedAtMs <= 0L) return true
        return (nowMs - lastCheckedAtMs) >= minIntervalMs
    }

    /** Computes the epoch timestamp until which automatic checks should back off after [failure]. */
    fun nextBackoffUntilMs(
        failure: UpdateCheckResult.Failure,
        nowMs: Long,
        fallbackBackoffMs: Long = FAILURE_BACKOFF_MS,
    ): Long = failure.retryAtMs?.takeIf { it > nowMs } ?: (nowMs + fallbackBackoffMs)

    /** Ceiling remaining wait time in minutes (>= 1) until [resetAtMs]. */
    fun remainingMinutes(resetAtMs: Long, nowMs: Long): Long =
        ((resetAtMs - nowMs).coerceAtLeast(1L) + 59_999L) / 60_000L

    /** Formats a user-facing GitHub rate-limit message, including wait time when reset is known. */
    fun formatRateLimitMessage(resetAtMs: Long?, nowMs: Long): String {
        if (resetAtMs != null && resetAtMs > nowMs) {
            val minutes = remainingMinutes(resetAtMs, nowMs)
            val unit = if (minutes == 1L) "minute" else "minutes"
            return "GitHub rate limit reached. Try again in about $minutes $unit or open GitHub Releases."
        }
        return "GitHub rate limit reached. Try again later or open GitHub Releases."
    }

    /**
     * Parses either a single GitHub Release JSON object (`/releases/latest`) or a JSON array
     * (`/releases`), returning the newest eligible release strictly greater than [currentVersion].
     */
    fun parseReleasePayload(
        json: String,
        currentVersion: String,
        includePreReleases: Boolean,
        supportedAbis: List<String>,
    ): UpdateRelease? {
        val installed = AppVersion.parse(currentVersion) ?: return null
        val root = SimpleJson.parse(json) ?: return null
        val releaseObjects: List<Map<String, Any?>> = when (root) {
            is List<*> -> root.mapNotNull { it as? Map<String, Any?> }
            is Map<*, *> -> listOfNotNull(root as? Map<String, Any?>)
            else -> return null
        }

        var best: UpdateRelease? = null
        for (obj in releaseObjects) {
            val draft = obj["draft"] as? Boolean ?: false
            if (draft) continue
            val tagName = (obj["tag_name"] as? String)?.trim().orEmpty()
            val version = AppVersion.parse(tagName) ?: continue
            val prerelease = (obj["prerelease"] as? Boolean ?: false) || version.isPreRelease
            if (prerelease && !includePreReleases) continue
            if (version <= installed) continue

            val rawAssets = (obj["assets"] as? List<*>)
                ?.mapNotNull { it as? Map<String, Any?> }
                .orEmpty()
                .mapNotNull { asset ->
                    val name = (asset["name"] as? String)?.trim().orEmpty()
                    val url = (asset["browser_download_url"] as? String)?.trim().orEmpty()
                    val size = (asset["size"] as? Number)?.toLong() ?: 0L
                    if (name.isEmpty() || !validateDownloadUrl(url)) null
                    else ReleaseAsset(name, url, size)
                }

            val apk = selectApkAsset(rawAssets, supportedAbis)
            val checksum = rawAssets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
            val htmlUrl = (obj["html_url"] as? String)?.trim()
                ?.takeIf { validateDownloadUrl(it) }
                ?: releasesPageUrl()
            val title = (obj["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "ProCraft $tagName"
            val body = (obj["body"] as? String).orEmpty()
            val publishedAt = (obj["published_at"] as? String)?.trim().orEmpty()

            val candidate = UpdateRelease(
                tagName = tagName,
                version = version,
                title = title,
                notes = formatReleaseNotes(body),
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                isPreRelease = prerelease,
                apkAsset = apk,
                checksumAsset = checksum,
            )
            if (best == null || candidate.version > best.version) {
                best = candidate
            }
        }
        return best
    }

    /** Picks the best matching APK asset for the device's ordered ABI preference list. */
    fun selectApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
        if (apks.isEmpty()) return null

        for (rawAbi in supportedAbis) {
            val abi = rawAbi.lowercase(Locale.ROOT)
            val match = when {
                abi == "arm64-v8a" -> apks.firstOrNull { a ->
                    val n = a.name.lowercase(Locale.ROOT)
                    (n.contains("arm64") || n.contains("aarch64")) && !n.contains("armeabi")
                }
                abi == "armeabi-v7a" -> apks.firstOrNull { a ->
                    val n = a.name.lowercase(Locale.ROOT)
                    n.contains("armeabi-v7a") || n.contains("armv7") || n.contains("v7a")
                }
                abi == "x86_64" -> apks.firstOrNull { a ->
                    a.name.lowercase(Locale.ROOT).contains("x86_64")
                }
                else -> null
            }
            if (match != null) return match
        }

        // Fall back only to a universal APK (one that does not target a specific CPU architecture).
        return apks.firstOrNull { a ->
            val n = a.name.lowercase(Locale.ROOT)
            !n.contains("arm64") && !n.contains("aarch64") &&
                !n.contains("armeabi") && !n.contains("armv7") &&
                !n.contains("x86") && !n.contains("mips")
        }
    }

    /** Human-readable architecture tag for an APK filename. */
    fun abiDisplayLabel(assetName: String): String {
        val n = assetName.lowercase(Locale.ROOT)
        return when {
            n.contains("arm64") || n.contains("aarch64") -> "ARM64"
            n.contains("armeabi") || n.contains("armv7") || n.contains("v7a") -> "ARMv7"
            n.contains("x86_64") -> "x86_64"
            else -> "Universal"
        }
    }

    /** Extracts the expected lowercase SHA-256 hex digest for [fileName] from `SHA256SUMS.txt`. */
    fun parseSha256Sums(content: String, fileName: String): String? {
        val target = fileName.trim()
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split(Regex("""\s+"""), limit = 2)
            if (parts.size < 2) continue
            val hash = parts[0].lowercase(Locale.ROOT)
            if (hash.length != 64 || !hash.all { it in '0'..'9' || it in 'a'..'f' }) continue
            val listedName = parts[1].trim().removePrefix("*").removePrefix("./")
            if (listedName.equals(target, ignoreCase = true) || listedName.substringAfterLast('/').equals(target, ignoreCase = true)) {
                return hash
            }
        }
        return null
    }

    /** Streams [file] and computes its lowercase SHA-256 hex digest. */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Only permits HTTPS downloads from GitHub and its official asset CDN hosts. */
    fun validateDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == "github.com" ||
            host == "api.github.com" ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    /** Cleans GitHub Markdown release notes into concise plain text suitable for a mobile sheet. */
    fun formatReleaseNotes(raw: String): String {
        if (raw.isBlank()) return "Bug fixes, stability improvements, and updated features."
        val cleaned = raw
            .replace("\r\n", "\n")
            .lineSequence()
            .map { line ->
                val t = line.trimEnd()
                when {
                    t.startsWith("### ") -> t.removePrefix("### ").trim()
                    t.startsWith("## ") -> t.removePrefix("## ").trim()
                    t.startsWith("# ") -> t.removePrefix("# ").trim()
                    t.startsWith("- ") -> "• " + t.removePrefix("- ").trim()
                    t.startsWith("* ") -> "• " + t.removePrefix("* ").trim()
                    else -> t
                }
            }
            .map { it.replace(Regex("""\*\*(.+?)\*\*"""), "$1").replace(Regex("""`([^`]+)`"""), "$1") }
            .filterNot { it.startsWith("**Full Changelog**") || it.startsWith("Full Changelog:") }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return cleaned.ifEmpty { "Bug fixes, stability improvements, and updated features." }
    }

    /** Formats a byte count compactly (e.g. `24.3 MB`). */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    /** Cleans HTML release notes (from GitHub's Atom feed) into readable plain text. */
    fun formatHtmlReleaseNotes(html: String): String {
        if (html.isBlank()) return "Bug fixes, stability improvements, and updated features."
        val withBreaks = html
            .replace("\r\n", "\n")
            .replace(Regex("""(?is)<(script|style)\b[^>]*>.*?</\1>"""), "")
            .replace(Regex("""(?i)<\s*br\s*/?\s*>"""), "\n")
            .replace(Regex("""(?i)<\s*li\b[^>]*>"""), "\n• ")
            .replace(Regex("""(?i)</\s*(li|p|div|ul|ol|h[1-6]|tr|blockquote|pre)\s*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
        val decoded = decodeHtmlEntities(withBreaks)
        val cleaned = decoded
            .lineSequence()
            .map { line -> line.trim().replace(Regex("""[ \t]{2,}"""), " ") }
            .filterNot { it.startsWith("Full Changelog:", ignoreCase = true) }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return cleaned.ifEmpty { "Bug fixes, stability improvements, and updated features." }
    }

    private fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return Regex("""&(?:#x([0-9a-fA-F]+)|#(\d+)|([a-zA-Z]+));""").replace(text) { m ->
            val hex = m.groupValues[1]
            val dec = m.groupValues[2]
            val name = m.groupValues[3]
            when {
                hex.isNotEmpty() -> hex.toIntOrNull(16)?.let { codePointToString(it) } ?: m.value
                dec.isNotEmpty() -> dec.toIntOrNull()?.let { codePointToString(it) } ?: m.value
                else -> when (name.lowercase(Locale.ROOT)) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    "mdash" -> "—"
                    "ndash" -> "–"
                    "bull" -> "•"
                    else -> m.value
                }
            }
        }
    }

    private fun codePointToString(cp: Int): String =
        if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else ""

    /**
     * Parses a GitHub `releases.atom` feed and returns the newest eligible release strictly
     * greater than [currentVersion]. Pre-releases are detected from tags containing `-`
     * (matching `.github/workflows/release.yml`), and APK/SHA256SUMS URLs are derived from the
     * workflow's deterministic naming convention.
     */
    internal sealed interface AtomParseResult {
        data class Parsed(val release: UpdateRelease?) : AtomParseResult
        data object Invalid : AtomParseResult
    }

    internal fun parseAtomFeed(
        xml: String,
        currentVersion: String,
        includePreReleases: Boolean,
        supportedAbis: List<String>,
        owner: String = GITHUB_OWNER,
        repo: String = GITHUB_REPO,
    ): AtomParseResult {
        val installed = AppVersion.parse(currentVersion) ?: return AtomParseResult.Invalid
        val trimmedXml = xml.removePrefix("\uFEFF").trimStart()
        val doc = runCatching {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            dbf.newDocumentBuilder().parse(ByteArrayInputStream(trimmedXml.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return AtomParseResult.Invalid

        val root = doc.documentElement ?: return AtomParseResult.Invalid
        if (!root.localName.equals("feed", ignoreCase = true) && !root.nodeName.equals("feed", ignoreCase = true)) {
            return AtomParseResult.Invalid
        }

        val entries = root.getElementsByTagNameNS("*", "entry")
        var best: UpdateRelease? = null
        for (i in 0 until entries.length) {
            val entry = entries.item(i) as? Element ?: continue
            val linkHref = firstChildAttr(entry, "link", "href").orEmpty().trim()
            val entryId = firstChildText(entry, "id").orEmpty().trim()
            val tagName = extractTagFromAtomEntry(linkHref, entryId) ?: continue
            val version = AppVersion.parse(tagName) ?: continue
            val isPreRelease = tagName.contains('-') || version.isPreRelease
            if (isPreRelease && !includePreReleases) continue
            if (version <= installed) continue

            val ver = tagName.removePrefix("v").removePrefix("V")
            val downloadBase = "https://github.com/$owner/$repo/releases/download/$tagName"
            val candidateAssets = listOf(
                ReleaseAsset("procraft-$ver-android-arm64.apk", "$downloadBase/procraft-$ver-android-arm64.apk", 0L),
                ReleaseAsset("procraft-$ver-android-legacy-armeabi-v7a.apk", "$downloadBase/procraft-$ver-android-legacy-armeabi-v7a.apk", 0L),
            ).filter { validateDownloadUrl(it.downloadUrl) }
            val apk = selectApkAsset(candidateAssets, supportedAbis)
            val checksumUrl = "$downloadBase/SHA256SUMS.txt"
            val checksum = if (validateDownloadUrl(checksumUrl)) ReleaseAsset("SHA256SUMS.txt", checksumUrl, 0L) else null

            val htmlUrl = linkHref.takeIf { validateDownloadUrl(it) }
                ?: "https://github.com/$owner/$repo/releases/tag/$tagName".takeIf { validateDownloadUrl(it) }
                ?: releasesPageUrl(owner, repo)
            val title = firstChildText(entry, "title")?.trim()?.takeIf { it.isNotEmpty() } ?: "ProCraft $tagName"
            val rawContent = firstChildText(entry, "content").orEmpty()
            val updated = firstChildText(entry, "updated")?.trim().orEmpty()

            val candidate = UpdateRelease(
                tagName = tagName,
                version = version,
                title = title,
                notes = formatHtmlReleaseNotes(rawContent),
                htmlUrl = htmlUrl,
                publishedAt = updated,
                isPreRelease = isPreRelease,
                apkAsset = apk,
                checksumAsset = checksum,
            )
            if (best == null || candidate.version > best.version) {
                best = candidate
            }
        }
        return AtomParseResult.Parsed(best)
    }

    private fun extractTagFromAtomEntry(linkHref: String, entryId: String): String? {
        val marker = "/releases/tag/"
        val idx = linkHref.indexOf(marker)
        if (idx >= 0) {
            val rawTag = linkHref.substring(idx + marker.length)
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')
            if (rawTag.isNotEmpty()) {
                return runCatching { URLDecoder.decode(rawTag, "UTF-8") }.getOrDefault(rawTag)
            }
        }
        val fromId = entryId.substringAfterLast('/', "").trim()
        return fromId.takeIf { it.isNotEmpty() }
    }

    private fun firstChildText(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return if (nodes.length > 0) nodes.item(0)?.textContent else null
    }

    private fun firstChildAttr(parent: Element, localName: String, attr: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val value = el.getAttribute(attr)
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /** Classifies non-200 HTTP responses from GitHub, distinguishing true rate limits from generic errors. */
    internal fun classifyHttpFailure(
        response: HttpStringResponse,
        nowMs: Long = System.currentTimeMillis(),
    ): UpdateCheckResult.Failure {
        val isRateLimit = response.code == 429 ||
            (response.code == 403 && (
                response.rateLimitRemaining?.trim() == "0" ||
                    response.body.contains("rate limit", ignoreCase = true)
                ))
        if (isRateLimit) {
            val resetAtMs = parseRateLimitResetAtMs(response.rateLimitReset, response.retryAfter, nowMs)
            return UpdateCheckResult.Failure(
                message = formatRateLimitMessage(resetAtMs, nowMs),
                isRateLimited = true,
                retryAtMs = resetAtMs,
            )
        }
        if (response.code == 403) {
            return UpdateCheckResult.Failure(
                message = "GitHub denied the update request (HTTP 403). Try again later or open GitHub Releases.",
                isRateLimited = false,
                retryAtMs = null,
            )
        }
        return UpdateCheckResult.Failure(
            message = "Couldn't reach GitHub (HTTP ${response.code}).",
            isRateLimited = false,
            retryAtMs = null,
        )
    }

    private fun parseRateLimitResetAtMs(
        rateLimitResetHeader: String?,
        retryAfterHeader: String?,
        nowMs: Long,
    ): Long? {
        retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { seconds ->
            return nowMs + seconds * 1000L
        }
        rateLimitResetHeader?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { epochSeconds ->
            val resetMs = epochSeconds * 1000L
            if (resetMs > nowMs) return resetMs
        }
        return null
    }

    /**
     * Queries GitHub Releases for a version newer than [currentVersion].
     * Uses GitHub's public `releases.atom` feed first to avoid unauthenticated REST API
     * rate limits on shared mobile networks, falling back to the REST API if needed.
     * Must run off the main thread.
     */
    fun checkForUpdates(
        currentVersion: String,
        includePreReleases: Boolean,
        supportedAbis: List<String>,
        owner: String = GITHUB_OWNER,
        repo: String = GITHUB_REPO,
    ): UpdateCheckResult = checkForUpdatesWithTransport(
        currentVersion = currentVersion,
        includePreReleases = includePreReleases,
        supportedAbis = supportedAbis,
        owner = owner,
        repo = repo,
        nowMs = System.currentTimeMillis(),
        fetch = { url, accept -> httpGetString(url, currentVersion, accept) },
    )

    internal fun checkForUpdatesWithTransport(
        currentVersion: String,
        includePreReleases: Boolean,
        supportedAbis: List<String>,
        owner: String = GITHUB_OWNER,
        repo: String = GITHUB_REPO,
        nowMs: Long = System.currentTimeMillis(),
        fetch: (url: String, accept: String) -> HttpStringResponse,
    ): UpdateCheckResult {
        var atomRateLimitFailure: UpdateCheckResult.Failure? = null
        try {
            val atomResp = fetch(releasesAtomUrl(owner, repo), "application/atom+xml, application/xml;q=0.9")
            when (atomResp.code) {
                200 -> {
                    when (val parsed = parseAtomFeed(atomResp.body, currentVersion, includePreReleases, supportedAbis, owner, repo)) {
                        is AtomParseResult.Parsed -> {
                            return if (parsed.release != null) UpdateCheckResult.UpdateAvailable(parsed.release)
                            else UpdateCheckResult.UpToDate(currentVersion)
                        }
                        AtomParseResult.Invalid -> Unit // Fall back to REST API
                    }
                }
                404 -> return UpdateCheckResult.UpToDate(currentVersion)
                else -> {
                    val failure = classifyHttpFailure(atomResp, nowMs)
                    if (failure.isRateLimited) atomRateLimitFailure = failure
                }
            }
        } catch (_: Throwable) {
            // Fall through to REST API fallback
        }

        val apiUrl = if (includePreReleases) allReleasesUrl(owner, repo) else latestReleaseUrl(owner, repo)
        return try {
            val response = fetch(apiUrl, "application/vnd.github+json")
            when (response.code) {
                200 -> {
                    val release = parseReleasePayload(
                        json = response.body,
                        currentVersion = currentVersion,
                        includePreReleases = includePreReleases,
                        supportedAbis = supportedAbis,
                    )
                    if (release != null) UpdateCheckResult.UpdateAvailable(release)
                    else UpdateCheckResult.UpToDate(currentVersion)
                }
                404 -> UpdateCheckResult.UpToDate(currentVersion)
                else -> {
                    val apiFailure = classifyHttpFailure(response, nowMs)
                    if (apiFailure.isRateLimited || atomRateLimitFailure == null) apiFailure else atomRateLimitFailure
                }
            }
        } catch (_: IOException) {
            atomRateLimitFailure ?: UpdateCheckResult.Failure("Couldn't check for updates. Check your internet connection.")
        } catch (t: Throwable) {
            atomRateLimitFailure ?: UpdateCheckResult.Failure(t.message?.takeIf { it.isNotBlank() } ?: "Update check failed.")
        }
    }

    /**
     * Downloads the release's matching APK into [updatesDir], verifies its SHA-256 hash when
     * `SHA256SUMS.txt` is available on the release, and atomically finalizes the APK file.
     */
    fun downloadAndVerifyApk(
        release: UpdateRelease,
        updatesDir: File,
        currentVersion: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit,
        isCancelled: () -> Boolean,
    ): ApkDownloadResult {
        val asset = release.apkAsset ?: return ApkDownloadResult.Failure("No compatible APK asset found for this device.")
        if (!validateDownloadUrl(asset.downloadUrl)) {
            return ApkDownloadResult.Failure("Blocked download from an untrusted host.")
        }
        val safeName = asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            return ApkDownloadResult.Failure("Couldn't prepare temporary update storage.")
        }
        cleanupUpdatesDir(updatesDir, keepFileName = safeName)

        val finalFile = File(updatesDir, safeName)
        val partFile = File(updatesDir, "$safeName.part")
        if (partFile.exists()) partFile.delete()

        return try {
            // Re-use already downloaded APK only if we can re-verify its checksum or size matches.
            val expectedHash = release.checksumAsset?.let { sumAsset ->
                runCatching {
                    val sumResp = httpGetString(sumAsset.downloadUrl, currentVersion)
                    if (sumResp.code == 200) parseSha256Sums(sumResp.body, asset.name) else null
                }.getOrNull()
            }

            if (finalFile.isFile && expectedHash != null && (asset.sizeBytes <= 0L || finalFile.length() == asset.sizeBytes)) {
                onVerifying()
                if (computeSha256(finalFile).equals(expectedHash, ignoreCase = true)) {
                    return ApkDownloadResult.Success(finalFile, sha256Verified = true)
                }
                finalFile.delete()
            }

            val conn = openValidatedConnection(asset.downloadUrl, currentVersion, DOWNLOAD_READ_TIMEOUT_MS, accept = "application/octet-stream")
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return ApkDownloadResult.Failure("Download failed (HTTP ${conn.responseCode}).")
                }
                val total = conn.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes
                var downloaded = 0L
                val buf = ByteArray(32 * 1024)
                var lastReportMs = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            if (isCancelled()) {
                                partFile.delete()
                                return ApkDownloadResult.Cancelled
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastReportMs >= 80L || (total > 0L && downloaded >= total)) {
                                lastReportMs = now
                                onProgress(downloaded, total)
                            }
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (isCancelled()) {
                partFile.delete()
                return ApkDownloadResult.Cancelled
            }

            var verified = false
            if (expectedHash != null) {
                onVerifying()
                val actualHash = computeSha256(partFile)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    partFile.delete()
                    return ApkDownloadResult.Failure("APK checksum verification failed. Please retry or download from GitHub.")
                }
                verified = true
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            ApkDownloadResult.Success(finalFile, sha256Verified = verified)
        } catch (_: IOException) {
            partFile.delete()
            ApkDownloadResult.Failure("Download interrupted. Check your connection and try again.")
        } catch (t: Throwable) {
            partFile.delete()
            ApkDownloadResult.Failure(t.message?.takeIf { it.isNotBlank() } ?: "Couldn't download update.")
        }
    }

    /** Removes stale APK and partial files from [updatesDir]. */
    fun cleanupUpdatesDir(updatesDir: File, keepFileName: String? = null) {
        if (!updatesDir.isDirectory) return
        updatesDir.listFiles().orEmpty().forEach { f ->
            if (keepFileName == null || f.name != keepFileName) {
                runCatching { f.delete() }
            }
        }
    }

    internal data class HttpStringResponse(
        val code: Int,
        val body: String,
        val rateLimitRemaining: String? = null,
        val rateLimitReset: String? = null,
        val retryAfter: String? = null,
    )

    private fun httpGetString(
        url: String,
        currentVersion: String,
        accept: String = "application/vnd.github+json",
    ): HttpStringResponse {
        val conn = openValidatedConnection(url, currentVersion, METADATA_READ_TIMEOUT_MS, accept = accept)
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpStringResponse(
                code = code,
                body = body,
                rateLimitRemaining = conn.getHeaderField("x-ratelimit-remaining"),
                rateLimitReset = conn.getHeaderField("x-ratelimit-reset"),
                retryAfter = conn.getHeaderField("Retry-After"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun openValidatedConnection(
        initialUrl: String,
        currentVersion: String,
        readTimeoutMs: Int,
        accept: String,
    ): HttpURLConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            if (!validateDownloadUrl(current)) {
                throw SecurityException("Blocked redirect to untrusted host.")
            }
            val host = runCatching { URI(current).host?.lowercase(Locale.ROOT) }.getOrNull()
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                this.readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", "ProCraft/$currentVersion (Android)")
                setRequestProperty("Accept", accept)
                if (host == "api.github.com") {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Empty redirect location")
                current = URI(current).resolve(location).toString()
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects")
    }
}

/**
 * Minimal RFC 8259 JSON parser in pure Kotlin with zero Android dependencies so GitHub release
 * payloads can be parsed identically in app runtime and JVM unit tests.
 */
internal object SimpleJson {
    fun parse(input: String): Any? {
        val parser = Parser(input)
        val value = parser.parseValue()
        parser.skipWhitespace()
        return if (parser.isAtEnd()) value else null
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos] <= ' ') pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (pos < s.length) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') throw IllegalArgumentException("Expected key at $pos")
                val key = parseString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == '}') {
                    pos++
                    return map
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated object")
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (pos < s.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == ']') {
                    pos++
                    return list
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
            throw IllegalArgumentException("Unterminated array")
        }

        private fun parseString(): String {
            pos++ // opening '"'
            val out = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= s.length) throw IllegalArgumentException("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalArgumentException("Short unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                out.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val token = s.substring(start, pos)
            return if (isFloat) token.toDouble() else token.toLongOrNull() ?: token.toDouble()
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!s.startsWith(expected, pos)) throw IllegalArgumentException("Expected $expected at $pos")
            pos += expected.length
            return value
        }
    }
}
