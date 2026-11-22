package io.github.artcraftandroid.procraft.gallery

/**
 * Sort orders available in the gallery.
 *
 * [CUSTOM] preserves the manual drag-and-drop order stored in `order.txt` by the native store.
 */
enum class GallerySort(val key: String, val label: String, val shortLabel: String) {
    CUSTOM("custom", "Manual Order", "Manual"),
    RECENT("recent", "Recently Modified", "Recent"),
    NAME_ASC("name_asc", "Name (A–Z)", "A–Z"),
    NAME_DESC("name_desc", "Name (Z–A)", "Z–A"),
    SIZE_DESC("size_desc", "Largest Canvas", "Size");

    companion object {
        fun fromKey(key: String?): GallerySort =
            entries.firstOrNull { it.key == key } ?: CUSTOM
    }
}

/** Pixel area used when sorting entries by canvas size (for a stack, its largest artwork). */
internal fun entryPixelArea(entry: GalleryEntry): Long = when (entry) {
    is Artwork -> entry.width.toLong().coerceAtLeast(0L) * entry.height.toLong().coerceAtLeast(0L)
    is Stack -> entry.artworks.maxOfOrNull { it.width.toLong().coerceAtLeast(0L) * it.height.toLong().coerceAtLeast(0L) } ?: 0L
}

/**
 * Whether drag-to-reorder and drag-to-stack are enabled: only in manual order without an active
 * search filter, so grid indices always match the native store's `order.txt`.
 */
fun canDragReorderGallery(query: String, sort: GallerySort): Boolean =
    query.isBlank() && sort == GallerySort.CUSTOM

/**
 * Filters [entries] by [query] (whitespace-separated tokens, all matched case-insensitively) and
 * sorts the result according to [sort].
 *
 * When a [Stack]'s own name matches, the full stack is kept; otherwise, if any child artworks
 * inside the stack match, a narrowed view of that stack containing the matching artworks is kept.
 */
fun filterAndSortGallery(
    entries: List<GalleryEntry>,
    query: String,
    sort: GallerySort,
): List<GalleryEntry> {
    val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = if (tokens.isEmpty()) {
        entries
    } else {
        entries.mapNotNull { entry ->
            when (entry) {
                is Artwork -> entry.takeIf { matchesTokens(it.name, tokens) }
                is Stack -> {
                    if (matchesTokens(entry.name, tokens)) {
                        entry
                    } else {
                        val matched = entry.artworks.filter { matchesTokens(it.name, tokens) }
                        if (matched.isNotEmpty()) entry.copy(artworks = matched) else null
                    }
                }
            }
        }
    }
    return when (sort) {
        GallerySort.CUSTOM -> filtered
        GallerySort.RECENT -> filtered.sortedWith(
            compareByDescending<GalleryEntry> { it.modified }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        GallerySort.NAME_ASC -> filtered.sortedWith(
            compareBy<GalleryEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenByDescending { it.modified },
        )
        GallerySort.NAME_DESC -> filtered.sortedWith(
            compareByDescending<GalleryEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenByDescending { it.modified },
        )
        GallerySort.SIZE_DESC -> filtered.sortedWith(
            compareByDescending<GalleryEntry> { entryPixelArea(it) }
                .thenByDescending { it.modified }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }
}

private fun matchesTokens(text: String, tokens: List<String>): Boolean =
    tokens.all { token -> text.contains(token, ignoreCase = true) }
