package io.github.artcraftandroid.procraft.gallery

import java.io.File
import java.util.UUID
import kotlin.math.ceil

/**
 * An artwork or stack kept in Recently Deleted for up to [GalleryTrash.RETENTION_DAYS] days.
 */
data class TrashedEntry(
    val id: String,
    val originalName: String,
    val originalDirName: String,
    /** Folder name of the stack this artwork came from, or null if deleted from the root gallery. */
    val originalParentRel: String?,
    val isStack: Boolean,
    val artworkCount: Int,
    val width: Int,
    val height: Int,
    val deletedAtMs: Long,
    val payloadDir: File,
    val thumbnail: File?,
) {
    /** Whole days left before automatic permanent deletion (0..[GalleryTrash.RETENTION_DAYS]). */
    fun daysRemaining(nowMs: Long = System.currentTimeMillis()): Int {
        val leftMs = (deletedAtMs + GalleryTrash.RETENTION_MS) - nowMs
        if (leftMs <= 0L) return 0
        return ceil(leftMs.toDouble() / GalleryTrash.DAY_MS.toDouble()).toInt().coerceIn(1, GalleryTrash.RETENTION_DAYS)
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - deletedAtMs >= GalleryTrash.RETENTION_MS
}

/**
 * Recently Deleted store kept outside [artworksRoot] so the native Rust gallery store never scans
 * trashed folders as live or recovered artworks.
 */
class GalleryTrash(
    val trashRoot: File,
    val artworksRoot: File,
) {
    /**
     * Moves [entry] (artwork or stack) into trash, records its origin metadata, and tidies its
     * parent `order.txt`. Returns null if the folder could not be moved.
     */
    fun moveToTrash(entry: GalleryEntry, nowMs: Long = System.currentTimeMillis()): TrashedEntry? {
        val src = entry.dir
        if (!src.exists() || !src.isDirectory) return null
        trashRoot.mkdirs()

        val id = "${nowMs}_${UUID.randomUUID().toString().take(8)}"
        val slot = File(trashRoot, id)
        val payload = File(slot, PAYLOAD_DIR)
        if (!slot.mkdirs()) return null

        val parentFile = src.parentFile
        val parentRel = runCatching {
            val rootCanon = artworksRoot.canonicalFile
            val parentCanon = parentFile?.canonicalFile
            if (parentCanon != null && parentCanon != rootCanon && parentCanon.parentFile == rootCanon) {
                parentCanon.name
            } else {
                null
            }
        }.getOrNull()

        if (!moveDirectory(src, payload)) {
            slot.deleteRecursively()
            return null
        }

        // Remove moved folder name from parent's order.txt so ordering stays tidy.
        parentFile?.let { removeFromOrderFile(File(it, ORDER_FILE), src.name) }

        val isStack = entry is Stack
        val topArt = when (entry) {
            is Artwork -> entry
            is Stack -> entry.artworks.firstOrNull()
        }
        val artworkCount = when (entry) {
            is Artwork -> 1
            is Stack -> entry.artworks.size.coerceAtLeast(1)
        }
        val trashed = TrashedEntry(
            id = id,
            originalName = entry.name,
            originalDirName = src.name,
            originalParentRel = parentRel,
            isStack = isStack,
            artworkCount = artworkCount,
            width = topArt?.width ?: 0,
            height = topArt?.height ?: 0,
            deletedAtMs = nowMs,
            payloadDir = payload,
            thumbnail = findThumbnail(payload, isStack),
        )
        writeMeta(File(slot, META_FILE), trashed)
        return trashed
    }

    /** Lists trashed entries newest-deleted first; purges items past [RETENTION_DAYS] when [autoPurge] is true. */
    fun list(nowMs: Long = System.currentTimeMillis(), autoPurge: Boolean = true): List<TrashedEntry> {
        if (!trashRoot.isDirectory) return emptyList()
        val items = mutableListOf<TrashedEntry>()
        for (slot in trashRoot.listFiles().orEmpty()) {
            if (!slot.isDirectory) continue
            val parsed = readSlot(slot)
            if (parsed == null) {
                continue
            }
            if (autoPurge && parsed.isExpired(nowMs)) {
                slot.deleteRecursively()
                continue
            }
            items.add(parsed)
        }
        items.sortByDescending { it.deletedAtMs }
        return items
    }

    /** Number of active (non-expired) trashed entries. */
    fun count(nowMs: Long = System.currentTimeMillis()): Int = list(nowMs, autoPurge = true).size

    /** Permanently removes entries older than [RETENTION_DAYS]; returns count removed. */
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        if (!trashRoot.isDirectory) return 0
        var removed = 0
        for (slot in trashRoot.listFiles().orEmpty()) {
            if (!slot.isDirectory) continue
            val parsed = readSlot(slot) ?: continue
            if (parsed.isExpired(nowMs) && slot.deleteRecursively()) {
                removed++
            }
        }
        return removed
    }

    /**
     * Restores trashed item [id] back to its original stack (if that stack still exists) or to the
     * root gallery. Assigns a unique folder name if a collision exists so live artworks are never
     * overwritten, and prepends the restored folder to the destination's `order.txt`.
     */
    fun restore(id: String): File? {
        val slot = File(trashRoot, id)
        val entry = readSlot(slot) ?: return null
        if (!entry.payloadDir.isDirectory) {
            slot.deleteRecursively()
            return null
        }
        artworksRoot.mkdirs()

        val targetParent = entry.originalParentRel
            ?.let { File(artworksRoot, it) }
            ?.takeIf { !entry.isStack && it.isDirectory && File(it, STACK_FILE).isFile }
            ?: artworksRoot

        val targetDir = uniqueChildDir(targetParent, entry.originalDirName, "restored")
        if (!moveDirectory(entry.payloadDir, targetDir)) return null

        prependToOrderFile(File(targetParent, ORDER_FILE), targetDir.name)
        slot.deleteRecursively()
        return targetDir
    }

    /** Permanently deletes a single trashed item by [id]. */
    fun deletePermanently(id: String): Boolean {
        val slot = File(trashRoot, id)
        return slot.exists() && slot.deleteRecursively()
    }

    /** Permanently deletes all trashed items; returns how many slots were removed. */
    fun emptyTrash(): Int {
        if (!trashRoot.isDirectory) return 0
        var removed = 0
        for (slot in trashRoot.listFiles().orEmpty()) {
            if (slot.isDirectory && slot.deleteRecursively()) removed++
        }
        return removed
    }

    private fun readSlot(slot: File): TrashedEntry? {
        val metaFile = File(slot, META_FILE)
        val payload = File(slot, PAYLOAD_DIR)
        if (!metaFile.isFile || !payload.isDirectory) return null
        val fields = runCatching { parseFlatJson(metaFile.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val originalName = fields["originalName"] ?: return null
        val originalDirName = fields["originalDirName"] ?: return null
        val isStack = fields["isStack"] == "true"
        val artworkCount = fields["artworkCount"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val width = fields["width"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val height = fields["height"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val deletedAtMs = fields["deletedAtMs"]?.toLongOrNull() ?: return null
        val originalParentRel = fields["originalParentRel"]?.takeIf { it.isNotEmpty() }
        return TrashedEntry(
            id = slot.name,
            originalName = originalName,
            originalDirName = originalDirName,
            originalParentRel = originalParentRel,
            isStack = isStack,
            artworkCount = artworkCount,
            width = width,
            height = height,
            deletedAtMs = deletedAtMs,
            payloadDir = payload,
            thumbnail = findThumbnail(payload, isStack),
        )
    }

    private fun writeMeta(file: File, entry: TrashedEntry) {
        val json = encodeFlatJson(
            linkedMapOf(
                "version" to 1,
                "originalName" to entry.originalName,
                "originalDirName" to entry.originalDirName,
                "originalParentRel" to (entry.originalParentRel ?: ""),
                "isStack" to entry.isStack,
                "artworkCount" to entry.artworkCount,
                "width" to entry.width,
                "height" to entry.height,
                "deletedAtMs" to entry.deletedAtMs,
            ),
        )
        file.writeText(json, Charsets.UTF_8)
    }

    private fun findThumbnail(payload: File, isStack: Boolean): File? {
        if (!isStack) {
            return File(payload, THUMB_FILE).takeIf { it.isFile }
        }
        // Prefer the first artwork listed in the stack's order.txt.
        val order = File(payload, ORDER_FILE)
        if (order.isFile) {
            for (line in order.readLines(Charsets.UTF_8)) {
                val name = line.trim()
                if (name.isEmpty()) continue
                val thumb = File(File(payload, name), THUMB_FILE)
                if (thumb.isFile) return thumb
            }
        }
        return payload.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, THUMB_FILE).isFile }
            ?.let { File(it, THUMB_FILE) }
    }

    companion object {
        const val RETENTION_DAYS = 30
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val RETENTION_MS = RETENTION_DAYS * DAY_MS

        private const val META_FILE = "meta.json"
        private const val PAYLOAD_DIR = "payload"
        private const val ORDER_FILE = "order.txt"
        private const val STACK_FILE = "stack.json"
        private const val THUMB_FILE = "thumbnail.png"
    }
}

/** Moves [src] directory to [dst], falling back to copy + delete if rename fails across mounts. */
internal fun moveDirectory(src: File, dst: File): Boolean {
    if (!src.exists()) return false
    dst.parentFile?.mkdirs()
    if (src.renameTo(dst)) return true
    val copied = runCatching { src.copyRecursively(dst, overwrite = true) }.getOrDefault(false)
    if (!copied || !dst.exists()) {
        dst.deleteRecursively()
        return false
    }
    src.deleteRecursively()
    return true
}

/** Picks [preferredName] inside [parent] if free, else `<preferredName>_<suffix>_N`. */
internal fun uniqueChildDir(parent: File, preferredName: String, suffix: String): File {
    val clean = preferredName.ifBlank { "artwork" }.replace('/', '_').replace('\\', '_')
    val direct = File(parent, clean)
    if (!direct.exists()) return direct
    var n = 1
    while (true) {
        val candidate = File(parent, "${clean}_${suffix}_$n")
        if (!candidate.exists()) return candidate
        n++
    }
}

internal fun prependToOrderFile(orderFile: File, name: String) {
    val existing = if (orderFile.isFile) {
        orderFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && it != name }
    } else {
        emptyList()
    }
    val updated = listOf(name) + existing
    orderFile.parentFile?.mkdirs()
    orderFile.writeText(updated.joinToString("\n") + "\n", Charsets.UTF_8)
}

internal fun removeFromOrderFile(orderFile: File, name: String) {
    if (!orderFile.isFile) return
    val remaining = orderFile.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && it != name }
    orderFile.writeText(if (remaining.isEmpty()) "" else remaining.joinToString("\n") + "\n", Charsets.UTF_8)
}

/** Minimal dependency-free flat JSON object encoder (strings, numbers, booleans) safe for pure JVM tests. */
internal fun encodeFlatJson(fields: Map<String, Any>): String {
    val sb = StringBuilder()
    sb.append("{\n")
    val entries = fields.entries.toList()
    entries.forEachIndexed { idx, (k, v) ->
        sb.append("  \"").append(escapeJson(k)).append("\": ")
        when (v) {
            is Number, is Boolean -> sb.append(v.toString())
            else -> sb.append('"').append(escapeJson(v.toString())).append('"')
        }
        if (idx < entries.lastIndex) sb.append(',')
        sb.append('\n')
    }
    sb.append("}\n")
    return sb.toString()
}

/** Minimal dependency-free flat JSON object parser matching [encodeFlatJson]. */
internal fun parseFlatJson(text: String): Map<String, String>? {
    var i = 0
    val n = text.length
    fun skipWs() {
        while (i < n && text[i].isWhitespace()) i++
    }
    fun parseString(): String? {
        if (i >= n || text[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < n) {
            val c = text[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= n) return null
                    when (val esc = text[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > n) return null
                            val hex = text.substring(i, i + 4)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                }
                else -> sb.append(c)
            }
        }
        return null
    }
    fun parsePrimitive(): String? {
        val start = i
        while (i < n && text[i] != ',' && text[i] != '}' && !text[i].isWhitespace()) i++
        return if (i > start) text.substring(start, i) else null
    }

    skipWs()
    if (i >= n || text[i] != '{') return null
    i++
    val out = linkedMapOf<String, String>()
    while (true) {
        skipWs()
        if (i >= n) return null
        if (text[i] == '}') {
            i++
            return out
        }
        val key = parseString() ?: return null
        skipWs()
        if (i >= n || text[i] != ':') return null
        i++
        skipWs()
        val value = if (i < n && text[i] == '"') parseString() else parsePrimitive()
        if (value == null) return null
        out[key] = value
        skipWs()
        if (i < n && text[i] == ',') {
            i++
        } else if (i < n && text[i] == '}') {
            i++
            return out
        } else {
            return null
        }
    }
}

private fun escapeJson(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) {
                sb.append("\\u%04x".format(c.code))
            } else {
                sb.append(c)
            }
        }
    }
    return sb.toString()
}
