package io.github.artcraftandroid.procraft.gallery

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GalleryTrashTest {
    private lateinit var tempRoot: File
    private lateinit var artworksRoot: File
    private lateinit var trashRoot: File
    private lateinit var trash: GalleryTrash

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("procraft-trash-test").toFile()
        artworksRoot = File(tempRoot, "artworks").apply { mkdirs() }
        trashRoot = File(tempRoot, "trash")
        trash = GalleryTrash(trashRoot, artworksRoot)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun createArtworkDir(parent: File, folder: String, name: String, withThumb: Boolean = true): Artwork {
        val dir = File(parent, folder).apply { mkdirs() }
        File(dir, "artwork.json").writeText("""{"name":"$name"}""", Charsets.UTF_8)
        if (withThumb) File(dir, "thumbnail.png").writeBytes(byteArrayOf(1, 2, 3, 4))
        return Artwork(dir, name, 2048, 1536, 1_000L)
    }

    @Test
    fun moveToTrashAndRestoreTopLevelArtworkPreservesFilesAndOrder() {
        val art = createArtworkDir(artworksRoot, "art_01", """Quote "Sketch" \ Line""")
        File(artworksRoot, "order.txt").writeText("art_01\nart_02\n", Charsets.UTF_8)

        val now = 10_000_000L
        val trashed = trash.moveToTrash(art, nowMs = now)
        assertNotNull(trashed)
        assertFalse(art.dir.exists())
        assertTrue(trashed!!.payloadDir.isDirectory)
        assertNotNull(trashed.thumbnail)
        assertEquals("art_02\n", File(artworksRoot, "order.txt").readText(Charsets.UTF_8))

        val listed = trash.list(nowMs = now)
        assertEquals(1, listed.size)
        assertEquals("""Quote "Sketch" \ Line""", listed.single().originalName)
        assertEquals(30, listed.single().daysRemaining(now))

        val restored = trash.restore(trashed.id)
        assertNotNull(restored)
        assertEquals(File(artworksRoot, "art_01"), restored)
        assertTrue(File(restored!!, "artwork.json").isFile)
        assertTrue(File(restored, "thumbnail.png").isFile)
        assertEquals(listOf("art_01", "art_02"), File(artworksRoot, "order.txt").readLines().filter { it.isNotBlank() })
        assertEquals(0, trash.list(nowMs = now).size)
    }

    @Test
    fun restoreInsideStackReturnsToStackIfPresentOrRootIfStackDeleted() {
        val stackDir = File(artworksRoot, "stack_a").apply { mkdirs() }
        File(stackDir, "stack.json").writeText("""{"name":"Comics"}""", Charsets.UTF_8)
        val page1 = createArtworkDir(stackDir, "page_1", "Page 1")
        val page2 = createArtworkDir(stackDir, "page_2", "Page 2")

        val t1 = trash.moveToTrash(page1, nowMs = 1_000L)!!
        val t2 = trash.moveToTrash(page2, nowMs = 2_000L)!!
        assertEquals("stack_a", t1.originalParentRel)

        // While stack still exists, page1 restores back into stack_a
        val r1 = trash.restore(t1.id)
        assertEquals(File(stackDir, "page_1"), r1)

        // If stack_a is later removed, page2 gracefully restores to root gallery instead of failing
        stackDir.deleteRecursively()
        val r2 = trash.restore(t2.id)
        assertEquals(File(artworksRoot, "page_2"), r2)
        assertTrue(File(r2!!, "artwork.json").isFile)
    }

    @Test
    fun restoreAvoidsOverwritingCollidingFolderName() {
        val original = createArtworkDir(artworksRoot, "sketch", "Old Sketch")
        val trashed = trash.moveToTrash(original, nowMs = 1_000L)!!

        // A brand-new artwork now occupies artworks/sketch
        val newer = createArtworkDir(artworksRoot, "sketch", "New Sketch")

        val restored = trash.restore(trashed.id)!!
        assertEquals("sketch_restored_1", restored.name)
        assertTrue(newer.dir.isDirectory)
        assertTrue(File(newer.dir, "artwork.json").readText().contains("New Sketch"))
        assertTrue(File(restored, "artwork.json").readText().contains("Old Sketch"))
    }

    @Test
    fun stackMoveToTrashTracksCountAndFirstArtworkThumbnail() {
        val stackDir = File(artworksRoot, "stack_1").apply { mkdirs() }
        File(stackDir, "stack.json").writeText("""{"name":"Storyboard"}""", Charsets.UTF_8)
        val a1 = createArtworkDir(stackDir, "f1", "Frame 1", withThumb = true)
        val a2 = createArtworkDir(stackDir, "f2", "Frame 2", withThumb = false)
        File(stackDir, "order.txt").writeText("f1\nf2\n", Charsets.UTF_8)

        val trashed = trash.moveToTrash(Stack(stackDir, "Storyboard", listOf(a1, a2)), nowMs = 5_000L)!!
        assertTrue(trashed.isStack)
        assertEquals(2, trashed.artworkCount)
        assertEquals("f1", trashed.thumbnail?.parentFile?.name)

        val restored = trash.restore(trashed.id)!!
        assertTrue(File(restored, "stack.json").isFile)
        assertTrue(File(restored, "f1/artwork.json").isFile)
        assertTrue(File(restored, "f2/artwork.json").isFile)
    }

    @Test
    fun autoPurgeRemovesExpiredItemsAfter30Days() {
        val oldArt = createArtworkDir(artworksRoot, "old", "Old")
        val freshArt = createArtworkDir(artworksRoot, "fresh", "Fresh")

        val day0 = 1_000_000L
        val oldTrashed = trash.moveToTrash(oldArt, nowMs = day0)!!
        val freshTrashed = trash.moveToTrash(freshArt, nowMs = day0 + 15 * GalleryTrash.DAY_MS)!!

        val day30 = day0 + 30 * GalleryTrash.DAY_MS
        assertTrue(oldTrashed.isExpired(day30))
        assertEquals(0, oldTrashed.daysRemaining(day30))
        assertFalse(freshTrashed.isExpired(day30))
        assertEquals(15, freshTrashed.daysRemaining(day30))

        val remaining = trash.list(nowMs = day30, autoPurge = true)
        assertEquals(listOf("Fresh"), remaining.map { it.originalName })
        assertNull(trash.restore(oldTrashed.id))
    }

    @Test
    fun permanentDeleteAndEmptyTrashRemoveSlotsCompletely() {
        val a = createArtworkDir(artworksRoot, "a", "A")
        val b = createArtworkDir(artworksRoot, "b", "B")
        val c = createArtworkDir(artworksRoot, "c", "C")

        val ta = trash.moveToTrash(a, nowMs = 1L)!!
        trash.moveToTrash(b, nowMs = 2L)!!
        trash.moveToTrash(c, nowMs = 3L)!!

        assertTrue(trash.deletePermanently(ta.id))
        assertEquals(2, trash.count(nowMs = 10L))

        assertEquals(2, trash.emptyTrash())
        assertEquals(0, trash.count(nowMs = 10L))
    }
}
