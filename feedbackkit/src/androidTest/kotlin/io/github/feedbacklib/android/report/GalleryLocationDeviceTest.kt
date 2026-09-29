package io.github.feedbacklib.android.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.AddImageResult
import io.github.feedbacklib.android.internal.report.DraftStore
import io.github.feedbacklib.android.internal.report.PlatformImageSanitizer
import io.github.feedbacklib.android.internal.report.Reencoding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A gallery photo goes through the real import path and reaches the draft re-encoded (spec §6):
 * upright by its EXIF orientation, with no metadata. Each case runs on this device's decoder
 * (ImageDecoder from API 28) and on the BitmapFactory path older devices take.
 */
@RunWith(AndroidJUnit4::class)
class GalleryLocationDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "gallery-location-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val logger = SdkLogger(LogLevel.NONE)
    private val sanitizers = mapOf(
        "this device's decoder" to PlatformImageSanitizer(logger),
        "the BitmapFactory path" to PlatformImageSanitizer(logger, sdkInt = 27),
    )

    /**
     * A [width]×[height] JPEG as stored: its top-left quarter red, the rest blue, with EXIF
     * [orientation]. It also carries a location three ways: Exif GPS tags, an XMP APP1 segment with
     * exif:GPSLatitude, and a second JPEG with its own Exif after the end of the image.
     */
    private fun photo(orientation: Int, width: Int = 64, height: Int = 32): File = File(dir, "photo-${System.nanoTime()}.jpg").apply {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        Canvas(bitmap).drawRect(0f, 0f, width / 2f, height / 2f, Paint().apply { color = Color.RED })
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
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

    private fun assertNoMetadata(path: String, file: File) {
        assertEquals("$path: jpg", "jpg", file.extension)
        val bytes = file.readBytes()
        assertEquals("$path: no Exif", -1, bytes.indexOf("Exif".toByteArray() + byteArrayOf(0, 0)))
        assertEquals("$path: no XMP", -1, bytes.indexOf("http://ns.adobe.com/xap".toByteArray()))
        assertEquals("$path: ends at its first end-of-image marker", bytes.size - 2, bytes.indexOf(byteArrayOf(0xFF.toByte(), 0xD9.toByte())))
        val exif = ExifInterface(file.path)
        assertFalse("$path: no location", exif.getLatLong(FloatArray(2)))
        // With no EXIF at all, newer platforms answer "0" (ORIENTATION_UNDEFINED) instead of null.
        val orientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION)
        assertTrue("$path: no orientation tag, was $orientation", orientation == null || orientation == "0")
        assertTrue("$path: no temporary file stays", file.parentFile!!.list()!!.none { it.endsWith(".tmp") })
    }

    private fun isRed(pixel: Int) = Color.red(pixel) > 200 && Color.blue(pixel) < 60

    private fun isBlue(pixel: Int) = Color.blue(pixel) > 200 && Color.red(pixel) < 60

    /** Imports a 64×32 photo with [orientation] and checks size and which corner is red (JPEG colours: approximately). */
    private fun assertTurned(orientation: Int, width: Int, height: Int, red: Corner) {
        for ((path, sanitizer) in sanitizers) {
            val imported = import(sanitizer, photo(orientation))
            assertNoMetadata(path, imported)
            val bitmap = BitmapFactory.decodeFile(imported.path)
            assertEquals("$path: size", width to height, bitmap.width to bitmap.height)
            for (corner in Corner.entries) {
                val pixel = bitmap.getPixel(corner.x(width), corner.y(height))
                if (corner == red) {
                    assertTrue("$path: $corner is red, was ${Integer.toHexString(pixel)}", isRed(pixel))
                } else {
                    assertTrue("$path: $corner is blue, was ${Integer.toHexString(pixel)}", isBlue(pixel))
                }
            }
            bitmap.recycle()
        }
    }

    /** A point well inside each quarter, away from the JPEG blur along the edges between them. */
    private enum class Corner(val fx: Float, val fy: Float) {
        TOP_LEFT(0.25f, 0.25f),
        TOP_RIGHT(0.75f, 0.25f),
        BOTTOM_LEFT(0.25f, 0.75f),
        BOTTOM_RIGHT(0.75f, 0.75f),
        ;

        fun x(width: Int) = (width * fx).toInt()

        fun y(height: Int) = (height * fy).toInt()
    }

    @Test
    fun orientation6TurnsClockwise() = assertTurned(ExifInterface.ORIENTATION_ROTATE_90, 32, 64, Corner.TOP_RIGHT)

    @Test
    fun orientation8TurnsCounterclockwise() = assertTurned(ExifInterface.ORIENTATION_ROTATE_270, 32, 64, Corner.BOTTOM_LEFT)

    @Test
    fun orientation2FlipsHorizontally() = assertTurned(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 64, 32, Corner.TOP_RIGHT)

    @Test
    fun aLargeTurnedPhotoIsBroughtDownWithItsAspectKept() {
        for ((path, sanitizer) in sanitizers) {
            val imported = import(sanitizer, photo(ExifInterface.ORIENTATION_ROTATE_90, width = 6000, height = 2000))
            assertNoMetadata(path, imported)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(imported.path, it) }
            val (width, height) = bounds.outWidth to bounds.outHeight
            assertTrue("$path: ${width}x$height upright", height > width)
            assertTrue("$path: long side $height", height <= Reencoding.MAX_SIDE)
            assertEquals("$path: 1:3 kept (${width}x$height)", 3.0, height.toDouble() / width, 0.01)
        }
    }
}
