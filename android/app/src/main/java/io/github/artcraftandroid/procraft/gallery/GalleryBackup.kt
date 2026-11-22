package io.github.artcraftandroid.procraft.gallery

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** How [GalleryBackup.restoreBackup] applies archived artworks onto the device. */
enum class RestoreMode {
    /** Keeps all existing local artworks and merges archived artworks safely (renaming collisions). */
    MERGE,
    /** Replaces the local `artworks/` folder with the backup's contents after staging succeeds. */
    REPLACE,
}

/** Metadata stored in `backup-manifest.json` at the root of a `.procraft-backup` archive. */
data class BackupManifest(
    val version: Int,
    val createdAtMs: Long,
    val appVersion: String,
    val artworkEntries: Int,
    val hasPalettes: Boolean,
    val hasGradients: Boolean,
    val hasBrushes: Boolean,
    val hasFonts: Boolean,
    val hasIcc: Boolean,
    val hasPresets: Boolean,
)

/** Summary returned after writing a backup archive. */
data class BackupResult(
    val filesWritten: Int,
    val bytesWritten: Long,
    val artworkEntries: Int,
)

/** Summary returned after restoring a backup archive. */
data class RestoreResult(
    val artworksRestored: Int,
    val palettesRestored: Boolean,
    val gradientsRestored: Boolean,
    val brushesRestored: Boolean,
    val presetsJson: String?,
    val filesRestored: Int,
)

/**
 * Full-library archive backup and restore (`.procraft-backup`, standard ZIP format).
 *
 * Includes `artworks/`, `brushes/`, `fonts/`, `icc/`, `palettes.json`, `gradients.json`, and
 * canvas `presets.json`. Excludes `trash/`, caches, logs, and bundled plugins.
 */
object GalleryBackup {
    const val FORMAT_VERSION = 1
    const val EXTENSION = "procraft-backup"
    private const val MANIFEST_NAME = "backup-manifest.json"
    private const val PRESETS_NAME = "presets.json"
    private const val PALETTES_NAME = "palettes.json"
    private const val GRADIENTS_NAME = "gradients.json"
    private const val ORDER_FILE = "order.txt"
    private const val ARTWORK_FILE = "artwork.json"
    private const val STACK_FILE = "stack.json"

    private val BACKUP_DIRS = listOf("artworks", "brushes", "fonts", "icc")
    private val BACKUP_FILES = listOf(PALETTES_NAME, GRADIENTS_NAME)

    /** Suggested filename such as `ProCraft-Backup-2026-11-22.procraft-backup`. */
    fun suggestedFileName(nowMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "ProCraft-Backup-${fmt.format(Date(nowMs))}.$EXTENSION"
    }

    /** Human-readable byte size (e.g., `1.4 MB`, `512 KB`). */
    fun formatByteSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /** Counts top-level artwork and stack folders inside [artworksDir]. */
    fun countTopLevelArtworks(artworksDir: File): Int {
        if (!artworksDir.isDirectory) return 0
        return artworksDir.listFiles().orEmpty().count { child ->
            child.isDirectory && (File(child, ARTWORK_FILE).isFile || File(child, STACK_FILE).isFile)
        }
    }

    /**
     * Writes a complete ProCraft backup ZIP from [filesDir] to [output].
     * Returns null if cancelled.
     */
    fun createBackup(
        filesDir: File,
        output: OutputStream,
        presetsJson: String? = null,
        appVersion: String = "",
        nowMs: Long = System.currentTimeMillis(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): BackupResult? {
        val items = mutableListOf<Pair<String, File>>()
        for (dirName in BACKUP_DIRS) {
            val dir = File(filesDir, dirName)
            if (dir.isDirectory) collectFiles(dir, dirName, items)
        }
        for (fileName in BACKUP_FILES) {
            val f = File(filesDir, fileName)
            if (f.isFile) items.add(fileName to f)
        }

        val artworkCount = countTopLevelArtworks(File(filesDir, "artworks"))
        val manifest = BackupManifest(
            version = FORMAT_VERSION,
            createdAtMs = nowMs,
            appVersion = appVersion,
            artworkEntries = artworkCount,
            hasPalettes = File(filesDir, PALETTES_NAME).isFile,
            hasGradients = File(filesDir, GRADIENTS_NAME).isFile,
            hasBrushes = items.any { it.first.startsWith("brushes/") },
            hasFonts = items.any { it.first.startsWith("fonts/") },
            hasIcc = items.any { it.first.startsWith("icc/") },
            hasPresets = !presetsJson.isNullOrBlank(),
        )

        val totalSteps = (items.size + 1 + if (manifest.hasPresets) 1 else 0).coerceAtLeast(1)
        var done = 0
        var writtenFiles = 0
        var writtenBytes = 0L

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            if (isCancelled()) return null
            val manifestBytes = encodeManifest(manifest).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifestBytes)
            zip.closeEntry()
            writtenBytes += manifestBytes.size
            done++
            onProgress(done.toFloat() / totalSteps)

            if (!presetsJson.isNullOrBlank()) {
                if (isCancelled()) return null
                val presetBytes = presetsJson.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(PRESETS_NAME))
                zip.write(presetBytes)
                zip.closeEntry()
                writtenBytes += presetBytes.size
                writtenFiles++
                done++
                onProgress(done.toFloat() / totalSteps)
            }

            val buffer = ByteArray(16 * 1024)
            for ((relPath, file) in items) {
                if (isCancelled()) return null
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(relPath))
                file.inputStream().use { input ->
                    while (true) {
                        if (isCancelled()) return null
                        val read = input.read(buffer)
                        if (read <= 0) break
                        zip.write(buffer, 0, read)
                        writtenBytes += read
                    }
                }
                zip.closeEntry()
                writtenFiles++
                done++
                onProgress((done.toFloat() / totalSteps).coerceIn(0f, 1f))
            }
        }
        onProgress(1f)
        return BackupResult(filesWritten = writtenFiles, bytesWritten = writtenBytes, artworkEntries = artworkCount)
    }

    /**
     * Reads [BackupManifest] from a backup stream without extracting files.
     * Returns null if [input] is not a valid ProCraft backup archive.
     */
    fun inspectBackup(input: InputStream): BackupManifest? = runCatching {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == MANIFEST_NAME) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    return@use decodeManifest(text)
                }
            }
            null
        }
    }.getOrNull()

    /**
     * Extracts and validates a backup archive into a temporary staging folder inside [cacheDir]
     * first, then applies it to [filesDir]. If validation fails or [isCancelled] triggers, live
     * user data in [filesDir] is left untouched and null is returned.
     */
    fun restoreBackup(
        input: InputStream,
        filesDir: File,
        cacheDir: File,
        mode: RestoreMode = RestoreMode.MERGE,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RestoreResult? {
        val staging = File(cacheDir, "backup-restore-${System.currentTimeMillis()}-${System.nanoTime()}")
        staging.mkdirs()
        try {
            val stagingCanon = staging.canonicalFile
            var hasManifest = false
            var extracted = 0
            val buffer = ByteArray(16 * 1024)

            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    if (isCancelled()) return null
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || !isAllowedArchiveEntry(name)) continue
                    val target = File(stagingCanon, name).canonicalFile
                    if (!target.path.startsWith(stagingCanon.path + File.separator)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        while (true) {
                            if (isCancelled()) return null
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                    if (name == MANIFEST_NAME) hasManifest = true else extracted++
                    // Unpack stage advances progress asymptotically up to 0.7f
                    onProgress((0.7f * (1f - 1f / (1f + extracted / 20f))).coerceIn(0f, 0.7f))
                }
            }

            if (isCancelled() || !hasManifest) return null
            val manifest = runCatching { decodeManifest(File(staging, MANIFEST_NAME).readText(Charsets.UTF_8)) }.getOrNull()
                ?: return null
            if (manifest.version > FORMAT_VERSION) return null

            filesDir.mkdirs()
            var artworksRestored = 0
            var palettesRestored = false
            var gradientsRestored = false
            var brushesRestored = false

            // 1. Artworks
            val stagedArtworks = File(staging, "artworks")
            val liveArtworks = File(filesDir, "artworks")
            if (stagedArtworks.isDirectory) {
                artworksRestored = applyArtworks(stagedArtworks, liveArtworks, mode)
            }
            onProgress(0.82f)
            if (isCancelled()) return null

            // 2. Palettes & Gradients
            val stagedPalettes = File(staging, PALETTES_NAME)
            if (stagedPalettes.isFile) {
                stagedPalettes.copyTo(File(filesDir, PALETTES_NAME), overwrite = true)
                palettesRestored = true
            }
            val stagedGradients = File(staging, GRADIENTS_NAME)
            if (stagedGradients.isFile) {
                stagedGradients.copyTo(File(filesDir, GRADIENTS_NAME), overwrite = true)
                gradientsRestored = true
            }
            onProgress(0.88f)

            // 3. Brushes, Fonts, ICC profiles
            val stagedBrushes = File(staging, "brushes")
            if (stagedBrushes.isDirectory) {
                copyDirectoryOverlay(stagedBrushes, File(filesDir, "brushes"))
                brushesRestored = true
            }
            for (extra in listOf("fonts", "icc")) {
                val stagedExtra = File(staging, extra)
                if (stagedExtra.isDirectory) {
                    copyDirectoryOverlay(stagedExtra, File(filesDir, extra))
                }
            }

            // 4. Canvas presets
            val presetsJson = File(staging, PRESETS_NAME).takeIf { it.isFile }?.readText(Charsets.UTF_8)

            onProgress(1f)
            return RestoreResult(
                artworksRestored = artworksRestored,
                palettesRestored = palettesRestored,
                gradientsRestored = gradientsRestored,
                brushesRestored = brushesRestored,
                presetsJson = presetsJson,
                filesRestored = extracted,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    internal fun isAllowedArchiveEntry(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains("..")) return false
        if (name == MANIFEST_NAME || name == PRESETS_NAME || name == PALETTES_NAME || name == GRADIENTS_NAME) return true
        return BACKUP_DIRS.any { prefix -> name.startsWith("$prefix/") && name.length > prefix.length + 1 }
    }

    private fun applyArtworks(stagedArtworks: File, liveArtworks: File, mode: RestoreMode): Int {
        if (mode == RestoreMode.REPLACE) {
            liveArtworks.deleteRecursively()
            liveArtworks.mkdirs()
            val count = countTopLevelArtworks(stagedArtworks)
            copyDirectoryOverlay(stagedArtworks, liveArtworks)
            return count
        }

        // MERGE mode: never overwrite existing live artworks; preserve backup order for restored entries.
        liveArtworks.mkdirs()
        val orderedNames = readOrderNames(File(stagedArtworks, ORDER_FILE))
        val allDirs = stagedArtworks.listFiles().orEmpty()
            .filter { it.isDirectory && (File(it, ARTWORK_FILE).isFile || File(it, STACK_FILE).isFile) }
            .associateBy { it.name }

        val inOrder = buildList {
            for (n in orderedNames) allDirs[n]?.let(::add)
            for ((n, d) in allDirs) if (n !in orderedNames) add(d)
        }

        val addedNames = mutableListOf<String>()
        for (srcDir in inOrder) {
            val destDir = uniqueChildDir(liveArtworks, srcDir.name, "backup")
            if (moveDirectory(srcDir, destDir)) {
                addedNames.add(destDir.name)
            }
        }
        // Prepend restored entries in their original relative order to live order.txt.
        val orderFile = File(liveArtworks, ORDER_FILE)
        for (name in addedNames.asReversed()) {
            prependToOrderFile(orderFile, name)
        }
        return addedNames.size
    }

    private fun readOrderNames(orderFile: File): List<String> =
        if (orderFile.isFile) orderFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        else emptyList()

    private fun copyDirectoryOverlay(src: File, dst: File) {
        dst.mkdirs()
        for (child in src.listFiles().orEmpty()) {
            val target = File(dst, child.name)
            if (child.isDirectory) {
                copyDirectoryOverlay(child, target)
            } else if (child.isFile) {
                target.parentFile?.mkdirs()
                child.copyTo(target, overwrite = true)
            }
        }
    }

    private fun collectFiles(dir: File, prefix: String, out: MutableList<Pair<String, File>>) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            val rel = "$prefix/${child.name}"
            if (child.isDirectory) {
                collectFiles(child, rel, out)
            } else if (child.isFile) {
                out.add(rel to child)
            }
        }
    }

    private fun encodeManifest(m: BackupManifest): String = encodeFlatJson(
        linkedMapOf(
            "version" to m.version,
            "createdAtMs" to m.createdAtMs,
            "appVersion" to m.appVersion,
            "artworkEntries" to m.artworkEntries,
            "hasPalettes" to m.hasPalettes,
            "hasGradients" to m.hasGradients,
            "hasBrushes" to m.hasBrushes,
            "hasFonts" to m.hasFonts,
            "hasIcc" to m.hasIcc,
            "hasPresets" to m.hasPresets,
        ),
    )

    private fun decodeManifest(text: String): BackupManifest? {
        val map = parseFlatJson(text) ?: return null
        val version = map["version"]?.toIntOrNull() ?: return null
        val createdAtMs = map["createdAtMs"]?.toLongOrNull() ?: return null
        return BackupManifest(
            version = version,
            createdAtMs = createdAtMs,
            appVersion = map["appVersion"].orEmpty(),
            artworkEntries = map["artworkEntries"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            hasPalettes = map["hasPalettes"] == "true",
            hasGradients = map["hasGradients"] == "true",
            hasBrushes = map["hasBrushes"] == "true",
            hasFonts = map["hasFonts"] == "true",
            hasIcc = map["hasIcc"] == "true",
            hasPresets = map["hasPresets"] == "true",
        )
    }
}
