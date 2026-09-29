package io.github.feedbacklib.android.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.AddImageResult
import io.github.feedbacklib.android.internal.report.DraftStore
import io.github.feedbacklib.android.internal.report.PlatformImageSanitizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A gallery photo with a location goes through the real import path and reaches the draft
 * re-encoded, with no metadata (spec §6): on this device's decoder (ImageDecoder from API 28) and on
 * the BitmapFactory path older devices take.
 */
@RunWith(AndroidJUnit4::class)
class GalleryLocationDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "gallery-location-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val logger = SdkLogger(LogLevel.NONE)
    private val sanitizers = listOf(PlatformImageSanitizer(logger), PlatformImageSanitizer(logger, sdkInt = 27))

    /**
     * A 64×32 JPEG stored sideways (orientation 6) that carries a location three ways: Exif GPS
     * tags, an XMP APP1 segment with exif:GPSLatitude, and a second JPEG with its own Exif after
     * the end of the image.
     */
    private fun photoWithLocation(): File = File(dir, "photo-${System.nanoTime()}.jpg").apply {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "55/1,45/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "37/1,37/1,12/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description exif:GPSLatitude=\"55,45N\"/></x:xmpmeta>"
            .toByteArray(Charsets.ISO_8859_1)
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((xmp.size + 2) shr 8).toByte(), (xmp.size + 2).toByte()) + xmp
        val trailer = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 8) +
            "Exif".toByteArray() + byteArrayOf(0, 0) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val bytes = readBytes()
        writeBytes(bytes.copyOf(2) + app1 + bytes.copyOfRange(2, bytes.size) + trailer)
        assertTrue("the fixture has a location", ExifInterface(path).getLatLong(FloatArray(2)))
    }

    private fun import(sanitizer: PlatformImageSanitizer, source: File): File {
        val store = DraftStore({ File(dir, "drafts") }, logger, sanitizer)
        val result = runBlocking {
            store.onQueue("d1") { addImage("d1", { "image/jpeg" }, { source.inputStream() }, 100, 10_000_000) }
        }
        return (result as AddImageResult.Added).file.file
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1

    @Test
    fun aPhotoEntersTheDraftWithNoMetadataAndItsOrientationBakedIn() {
        for (sanitizer in sanitizers) {
            val imported = import(sanitizer, photoWithLocation())
            assertEquals("jpg", imported.extension)
            val bytes = imported.readBytes()
            assertEquals(-1, bytes.indexOf("Exif".toByteArray() + byteArrayOf(0, 0)))
            assertEquals(-1, bytes.indexOf("http://ns.adobe.com/xap".toByteArray()))
            assertEquals("the file ends at its first end-of-image marker", bytes.size - 2, bytes.indexOf(byteArrayOf(0xFF.toByte(), 0xD9.toByte())))
            assertFalse(ExifInterface(imported.path).getLatLong(FloatArray(2)))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(imported.path, it) }
            assertEquals("turned upright", 32 to 64, bounds.outWidth to bounds.outHeight)
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(imported.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            assertTrue("no temporary file stays", imported.parentFile!!.list()!!.none { it.endsWith(".tmp") })
        }
    }
}
