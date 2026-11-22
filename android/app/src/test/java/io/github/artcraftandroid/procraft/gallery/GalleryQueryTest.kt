package io.github.artcraftandroid.procraft.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GalleryQueryTest {
    private fun art(name: String, w: Int, h: Int, modified: Long) =
        Artwork(File("/tmp/$name"), name, w, h, modified)

    private fun stack(name: String, vararg items: Artwork) =
        Stack(File("/tmp/stack_$name"), name, items.toList())

    @Test
    fun sortKeyRoundTripsAndFallsBackToCustom() {
        for (sort in GallerySort.entries) {
            assertEquals(sort, GallerySort.fromKey(sort.key))
        }
        assertEquals(GallerySort.CUSTOM, GallerySort.fromKey(null))
        assertEquals(GallerySort.CUSTOM, GallerySort.fromKey("unknown"))
    }

    @Test
    fun customSortPreservesOriginalManualOrder() {
        val a = art("Zebra", 1000, 1000, 100L)
        val b = art("Alpha", 3000, 3000, 300L)
        val c = art("Middle", 2000, 2000, 200L)

        val result = filterAndSortGallery(listOf(a, b, c), "", GallerySort.CUSTOM)
        assertEquals(listOf("Zebra", "Alpha", "Middle"), result.map { it.name })
    }

    @Test
    fun recentSortOrdersByModifiedDescendingThenName() {
        val old = art("Old", 1000, 1000, 100L)
        val newerB = art("Beta", 1000, 1000, 500L)
        val newerA = art("alpha", 1000, 1000, 500L)
        val stackEntry = stack("Comics", art("Page 1", 1000, 1000, 900L))

        val result = filterAndSortGallery(listOf(old, newerB, newerA, stackEntry), "", GallerySort.RECENT)
        assertEquals(listOf("Comics", "alpha", "Beta", "Old"), result.map { it.name })
    }

    @Test
    fun nameSortsAreCaseInsensitive() {
        val entries = listOf(
            art("charlie", 1000, 1000, 100L),
            art("Alpha", 1000, 1000, 200L),
            art("bravo", 1000, 1000, 300L),
        )
        assertEquals(
            listOf("Alpha", "bravo", "charlie"),
            filterAndSortGallery(entries, "", GallerySort.NAME_ASC).map { it.name },
        )
        assertEquals(
            listOf("charlie", "bravo", "Alpha"),
            filterAndSortGallery(entries, "", GallerySort.NAME_DESC).map { it.name },
        )
    }

    @Test
    fun sizeSortUsesPixelAreaAndLargestChildForStacks() {
        val small = art("Icon", 512, 512, 300L)
        val medium = art("Poster", 2048, 2048, 100L)
        val hugeStack = stack("Print", art("Draft", 500, 500, 50L), art("Final 4K", 4096, 3000, 200L))

        val result = filterAndSortGallery(listOf(small, medium, hugeStack), "", GallerySort.SIZE_DESC)
        assertEquals(listOf("Print", "Poster", "Icon"), result.map { it.name })
    }

    @Test
    fun searchSupportsMultiTokenCaseInsensitiveAndStackChildMatching() {
        val portrait = art("Portrait Study 02", 2000, 2500, 100L)
        val landscape = art("Mountain Sunset", 3000, 2000, 200L)
        val matchingStackName = stack("Character Sketches", art("Hero", 1000, 1000, 10L), art("Villain", 1000, 1000, 20L))
        val matchingChildOnly = stack("Client Work", art("Dragon Portrait", 1000, 1000, 30L), art("Logo", 1000, 1000, 40L))

        // Multi-token query matches across words regardless of case
        val study = filterAndSortGallery(listOf(portrait, landscape), "  study   PORTRAIT ", GallerySort.CUSTOM)
        assertEquals(listOf("Portrait Study 02"), study.map { it.name })

        // Matching stack name keeps all child artworks in that stack
        val byStackName = filterAndSortGallery(listOf(matchingStackName, matchingChildOnly), "character", GallerySort.CUSTOM)
        assertEquals(1, byStackName.size)
        assertEquals(2, (byStackName.single() as Stack).artworks.size)

        // Matching only a child inside a stack keeps a narrowed stack containing the matching child
        val byChildName = filterAndSortGallery(listOf(portrait, matchingChildOnly), "portrait", GallerySort.CUSTOM)
        assertEquals(listOf("Portrait Study 02", "Client Work"), byChildName.map { it.name })
        val narrowed = byChildName[1] as Stack
        assertEquals(listOf("Dragon Portrait"), narrowed.artworks.map { it.name })
    }

    @Test
    fun dragReorderIsAllowedOnlyInManualOrderWithoutSearch() {
        assertTrue(canDragReorderGallery("", GallerySort.CUSTOM))
        assertTrue(canDragReorderGallery("   ", GallerySort.CUSTOM))
        assertFalse(canDragReorderGallery("cat", GallerySort.CUSTOM))
        assertFalse(canDragReorderGallery("", GallerySort.RECENT))
        assertFalse(canDragReorderGallery("", GallerySort.NAME_ASC))
    }
}
