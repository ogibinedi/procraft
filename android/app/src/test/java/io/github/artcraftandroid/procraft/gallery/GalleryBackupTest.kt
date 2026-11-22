package io.github.artcraftandroid.procraft.gallery

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GalleryBackupTest {
    private lateinit var tempRoot: File
    private lateinit var sourceFilesDir: File
    private lateinit var destFilesDir: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("procraft-backup-test").toFile()
        sourceFilesDir = File(tempRoot, "src-files").apply { mkdirs() }
        destFilesDir = File(tempRoot, "dst-files").apply { mkdirs() }
        cacheDir = File(tempRoot, "cache").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun seedSourceLibrary() {
        val art1 = File(sourceFilesDir, "artworks/art_1").apply { mkdirs() }
        File(art1, "artwork.json").writeText("""{"name":"Sunset"}""", Charsets.UTF_8)
        File(art1, "thumbnail.png").writeBytes(byteArrayOf(9, 8, 7))
        File(sourceFilesDir, "artworks/order.txt").writeText("art_1\n", Charsets.UTF_8)

        val brushes = File(sourceFilesDir, "brushes").apply { mkdirs() }
        File(brushes, "library.json").writeText("""{"sets":[]}""", Charsets.UTF_8)

        val fonts = File(sourceFilesDir, "fonts").apply { mkdirs() }
        File(fonts, "custom.ttf").writeBytes(byteArrayOf(1, 2, 3))

        File(sourceFilesDir, "palettes.json").writeText("""[{"name":"Warm"}]""", Charsets.UTF_8)
        File(sourceFilesDir, "gradients.json").writeText("""[{"name":"Sky"}]""", Charsets.UTF_8)

        // Trash & logs must NOT be included in backups
        val trashArt = File(sourceFilesDir, "trash/slot_1/payload").apply { mkdirs() }
        File(trashArt, "artwork.json").writeText("""{"name":"Deleted"}""", Charsets.UTF_8)
        File(sourceFilesDir, "procraft.log").writeText("secret log", Charsets.UTF_8)
    }

    @Test
    fun createInspectAndRestoreFullBackupExcludesTrashAndLogs() {
        seedSourceLibrary()
        val archiveBytes = ByteArrayOutputStream()
        var lastProgress = 0f

        val created = GalleryBackup.createBackup(
            filesDir = sourceFilesDir,
            output = archiveBytes,
            presetsJson = """[{"name":"Manga"}]""",
            appVersion = "0.0.5",
            nowMs = 1_763_798_400_000L,
            onProgress = { lastProgress = it },
        )
        assertNotNull(created)
        assertEquals(1, created!!.artworkEntries)
        assertEquals(1f, lastProgress, 0.001f)

        val bytes = archiveBytes.toByteArray()
        val manifest = GalleryBackup.inspectBackup(ByteArrayInputStream(bytes))
        assertNotNull(manifest)
        assertEquals(GalleryBackup.FORMAT_VERSION, manifest!!.version)
        assertEquals("0.0.5", manifest.appVersion)
        assertEquals(1, manifest.artworkEntries)
        assertTrue(manifest.hasPalettes)
        assertTrue(manifest.hasGradients)
        assertTrue(manifest.hasBrushes)
        assertTrue(manifest.hasFonts)
        assertTrue(manifest.hasPresets)
        assertFalse(manifest.hasIcc)

        val restored = GalleryBackup.restoreBackup(
            input = ByteArrayInputStream(bytes),
            filesDir = destFilesDir,
            cacheDir = cacheDir,
            mode = RestoreMode.MERGE,
        )
        assertNotNull(restored)
        assertEquals(1, restored!!.artworksRestored)
        assertTrue(restored.palettesRestored)
        assertTrue(restored.gradientsRestored)
        assertTrue(restored.brushesRestored)
        assertEquals("""[{"name":"Manga"}]""", restored.presetsJson)
        assertTrue(File(destFilesDir, "artworks/art_1/artwork.json").isFile)
        assertTrue(File(destFilesDir, "brushes/library.json").isFile)
        assertTrue(File(destFilesDir, "fonts/custom.ttf").isFile)
        assertTrue(File(destFilesDir, "palettes.json").isFile)
        assertFalse(File(destFilesDir, "trash").exists())
        assertFalse(File(destFilesDir, "procraft.log").exists())
    }

    @Test
    fun mergeRestorePreservesExistingArtworksOnFolderNameCollision() {
        seedSourceLibrary()
        val archiveBytes = ByteArrayOutputStream()
        GalleryBackup.createBackup(sourceFilesDir, archiveBytes)

        // Destination already has its own artworks/art_1
        val existing = File(destFilesDir, "artworks/art_1").apply { mkdirs() }
        File(existing, "artwork.json").writeText("""{"name":"Local Work"}""", Charsets.UTF_8)
        File(destFilesDir, "artworks/order.txt").writeText("art_1\n", Charsets.UTF_8)

        val restored = GalleryBackup.restoreBackup(
            input = ByteArrayInputStream(archiveBytes.toByteArray()),
            filesDir = destFilesDir,
            cacheDir = cacheDir,
            mode = RestoreMode.MERGE,
        )
        assertNotNull(restored)
        assertEquals(1, restored!!.artworksRestored)
        // Local artwork untouched
        assertTrue(File(destFilesDir, "artworks/art_1/artwork.json").readText().contains("Local Work"))
        // Restored artwork placed beside it under a non-colliding folder name
        assertTrue(File(destFilesDir, "artworks/art_1_backup_1/artwork.json").readText().contains("Sunset"))
        assertEquals(
            listOf("art_1_backup_1", "art_1"),
            File(destFilesDir, "artworks/order.txt").readLines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun replaceRestoreReplacesLiveArtworksDirectory() {
        seedSourceLibrary()
        val archiveBytes = ByteArrayOutputStream()
        GalleryBackup.createBackup(sourceFilesDir, archiveBytes)

        val stale = File(destFilesDir, "artworks/old_only").apply { mkdirs() }
        File(stale, "artwork.json").writeText("""{"name":"Old"}""", Charsets.UTF_8)

        val restored = GalleryBackup.restoreBackup(
            input = ByteArrayInputStream(archiveBytes.toByteArray()),
            filesDir = destFilesDir,
            cacheDir = cacheDir,
            mode = RestoreMode.REPLACE,
        )
        assertNotNull(restored)
        assertFalse(stale.exists())
        assertTrue(File(destFilesDir, "artworks/art_1/artwork.json").isFile)
    }

    @Test
    fun restoreBlocksZipSlipAndDisallowedEntries() {
        val maliciousZip = ByteArrayOutputStream()
        ZipOutputStream(maliciousZip).use { zip ->
            zip.putNextEntry(ZipEntry("backup-manifest.json"))
            zip.write("""{"version":1,"createdAtMs":1000,"appVersion":"0.0.5","artworkEntries":0}""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("../escaped.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("artworks/../../escaped2.txt"))
            zip.write("evil2".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("plugins/malicious.procraft-plugin"))
            zip.write("evil3".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("palettes.json"))
            zip.write("""[{"name":"Safe"}]""".toByteArray())
            zip.closeEntry()
        }

        val restored = GalleryBackup.restoreBackup(
            input = ByteArrayInputStream(maliciousZip.toByteArray()),
            filesDir = destFilesDir,
            cacheDir = cacheDir,
        )
        assertNotNull(restored)
        assertTrue(restored!!.palettesRestored)
        assertFalse(File(tempRoot, "escaped.txt").exists())
        assertFalse(File(tempRoot, "escaped2.txt").exists())
        assertFalse(File(destFilesDir, "plugins/malicious.procraft-plugin").exists())
    }

    @Test
    fun invalidOrCancelledRestoreLeavesDestinationUntouched() {
        val existing = File(destFilesDir, "artworks/keep_me").apply { mkdirs() }
        File(existing, "artwork.json").writeText("""{"name":"Keep"}""", Charsets.UTF_8)

        // Random zip without manifest is rejected
        val nonBackup = ByteArrayOutputStream()
        ZipOutputStream(nonBackup).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("world".toByteArray())
            zip.closeEntry()
        }
        assertNull(GalleryBackup.inspectBackup(ByteArrayInputStream(nonBackup.toByteArray())))
        assertNull(GalleryBackup.restoreBackup(ByteArrayInputStream(nonBackup.toByteArray()), destFilesDir, cacheDir))
        assertTrue(File(existing, "artwork.json").isFile)

        // Cancelled restore returns null and keeps existing data intact
        seedSourceLibrary()
        val archiveBytes = ByteArrayOutputStream()
        GalleryBackup.createBackup(sourceFilesDir, archiveBytes)
        assertNull(
            GalleryBackup.restoreBackup(
                input = ByteArrayInputStream(archiveBytes.toByteArray()),
                filesDir = destFilesDir,
                cacheDir = cacheDir,
                mode = RestoreMode.REPLACE,
                isCancelled = { true },
            ),
        )
        assertTrue(File(existing, "artwork.json").isFile)
    }

    @Test
    fun fileNameAndByteSizeFormattingAreDeterministic() {
        // 2026-11-22T00:00:00Z = 1795305600000L
        assertEquals("ProCraft-Backup-2026-11-22.procraft-backup", GalleryBackup.suggestedFileName(1_795_305_600_000L))
        assertEquals("512 B", GalleryBackup.formatByteSize(512))
        assertEquals("2.0 KB", GalleryBackup.formatByteSize(2048))
        assertEquals("1.5 MB", GalleryBackup.formatByteSize(1_572_864))
    }
}
