package io.github.artcraftandroid.procraft.gallery

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File

/** Still raster image export formats supported for 1-tap Gallery save and sharing. */
enum class StillImageFormat(val label: String, val extension: String, val mime: String) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
}

/**
 * Native Android lossless WebP transcoding helper (preserves full ARGB transparency without
 * requiring Rust codec changes).
 */
object WebpExport {
    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    fun compressFormat(sdkInt: Int = Build.VERSION.SDK_INT): Bitmap.CompressFormat =
        if (sdkInt >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP

    /**
     * Decodes a rendered PNG at [pngFile] and writes a lossless WebP file to [outWebp].
     * Deletes any partial output on failure and returns whether writing succeeded.
     */
    fun transcodePngToWebp(pngFile: File, outWebp: File, quality: Int = 100): Boolean {
        if (!pngFile.isFile) return false
        val bitmap = BitmapFactory.decodeFile(pngFile.path) ?: return false
        return try {
            outWebp.parentFile?.mkdirs()
            val ok = outWebp.outputStream().buffered().use { out ->
                bitmap.compress(compressFormat(), quality.coerceIn(0, 100), out)
            }
            if (!ok) outWebp.delete()
            ok
        } catch (_: Exception) {
            outWebp.delete()
            false
        } finally {
            bitmap.recycle()
        }
    }
}
