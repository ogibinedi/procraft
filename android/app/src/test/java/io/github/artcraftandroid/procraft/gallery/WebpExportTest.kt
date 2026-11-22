package io.github.artcraftandroid.procraft.gallery

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class WebpExportTest {
    @Test
    fun stillImageFormatsExposeConsistentMetadata() {
        assertEquals("png", StillImageFormat.PNG.extension)
        assertEquals("image/png", StillImageFormat.PNG.mime)
        assertEquals("jpg", StillImageFormat.JPEG.extension)
        assertEquals("image/jpeg", StillImageFormat.JPEG.mime)
        assertEquals("webp", StillImageFormat.WEBP.extension)
        assertEquals("image/webp", StillImageFormat.WEBP.mime)
    }

    @Test
    @Suppress("DEPRECATION")
    fun webpCompressFormatSelectsLosslessOnApi30Plus() {
        assertEquals(Bitmap.CompressFormat.WEBP, WebpExport.compressFormat(sdkInt = 28))
        assertEquals(Bitmap.CompressFormat.WEBP, WebpExport.compressFormat(sdkInt = 29))
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSLESS, WebpExport.compressFormat(sdkInt = 30))
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSLESS, WebpExport.compressFormat(sdkInt = 35))
    }

    @Test
    fun transcodeRejectsMissingSourceFileSafely() {
        val missing = File("/tmp/nonexistent_procraft_webp_src.png")
        val out = File("/tmp/nonexistent_procraft_webp_out.webp")
        assertFalse(WebpExport.transcodePngToWebp(missing, out))
        assertFalse(out.exists())
    }
}
