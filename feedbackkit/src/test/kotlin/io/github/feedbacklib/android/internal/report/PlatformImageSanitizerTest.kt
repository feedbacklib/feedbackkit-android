package io.github.feedbacklib.android.internal.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Real codecs (Robolectric's native graphics, not the default stand-ins) and a real ExifInterface:
// the bytes written are checked.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformImageSanitizerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val logger = SdkLogger(LogLevel.NONE)

    /**
     * The BitmapFactory path (before API 28). Robolectric's native ImageDecoder refuses file
     * sources, so the ImageDecoder path is covered by GalleryLocationDeviceTest on a device.
     */
    private val sanitizers = listOf(PlatformImageSanitizer(logger, sdkInt = 27))

    private fun sanitize(sanitizer: PlatformImageSanitizer, source: File): Pair<ReencodedFormat?, File> {
        val target = File(temp.root, "out-${System.nanoTime()}")
        return (sanitizer.sanitize(source, target) as? SanitizeResult.Written)?.format to target
    }

    private fun bitmapFile(name: String, width: Int, height: Int, format: Bitmap.CompressFormat, color: Int = Color.RED): File =
        temp.newFile(name).apply {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            outputStream().use { bitmap.compress(format, 90, it) }
        }

    /**
     * A 64×32 JPEG stored sideways (orientation 6) that carries a location three ways: Exif GPS
     * tags, an XMP APP1 segment with exif:GPSLatitude, and a second JPEG with its own Exif after
     * the end of the image (the way MPF frames and motion photos trail a photo).
     */
    private fun photoWithLocation(): File {
        val file = bitmapFile("photo.tmp", 64, 32, Bitmap.CompressFormat.JPEG)
        ExifInterface(file.path).apply {
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
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(2) + app1 + bytes.copyOfRange(2, bytes.size) + trailer)
        assertTrue("the fixture has a location", ExifInterface(file.path).getLatLong(FloatArray(2)))
        return file
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { start -> needle.indices.all { this[start + it] == needle[it] } } ?: -1

    private fun size(file: File): Pair<Int, Int> =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.let { it.outWidth to it.outHeight }

    @Test
    fun `a photo comes out with no exif, no xmp and nothing after its end`() {
        for (sanitizer in sanitizers) {
            val (format, out) = sanitize(sanitizer, photoWithLocation())
            assertEquals(ReencodedFormat.JPEG, format)
            val bytes = out.readBytes()
            assertEquals(-1, bytes.indexOf("Exif".toByteArray() + byteArrayOf(0, 0)))
            assertEquals(-1, bytes.indexOf("http://ns.adobe.com/xap".toByteArray()))
            assertEquals(-1, bytes.indexOf("GPS".toByteArray()))
            assertEquals("the file ends at its first end-of-image marker", bytes.size - 2, bytes.indexOf(byteArrayOf(0xFF.toByte(), 0xD9.toByte())))
            assertTrue(!ExifInterface(out.path).getLatLong(FloatArray(2)))
        }
    }

    @Test
    fun `the orientation is baked into the pixels`() {
        for (sanitizer in sanitizers) {
            val (_, out) = sanitize(sanitizer, photoWithLocation())
            assertEquals(32 to 64, size(out))
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(out.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        }
    }

    @Test
    fun `transparency is written as png and an opaque image as jpeg`() {
        for (sanitizer in sanitizers) {
            val transparent = bitmapFile("clear-${System.nanoTime()}.png", 8, 8, Bitmap.CompressFormat.PNG, Color.TRANSPARENT)
            assertEquals(ReencodedFormat.PNG, sanitize(sanitizer, transparent).first)
            val opaque = bitmapFile("opaque-${System.nanoTime()}.webp", 8, 8, Bitmap.CompressFormat.WEBP_LOSSLESS, Color.BLUE)
            val (format, out) = sanitize(sanitizer, opaque)
            assertEquals(ReencodedFormat.JPEG, format)
            assertEquals(8 to 8, size(out))
        }
    }

    @Test
    fun `a large image is brought down to the cap on its long side`() {
        for (sanitizer in sanitizers) {
            val (_, out) = sanitize(sanitizer, bitmapFile("wide-${System.nanoTime()}.jpg", 5000, 40, Bitmap.CompressFormat.JPEG))
            val (width, height) = size(out)
            assertTrue("$width", width in 1..Reencoding.MAX_SIDE)
            assertTrue("$height", height in 1..40)
        }
    }

    @Test
    fun `a gif becomes its first frame, whatever it was called`() {
        // A 1×1 GIF89a with a transparent pixel.
        val gif = "47494638396101000100800000000000ffffff21f90401000000002c00000000010001000002024401003b"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        for (sanitizer in sanitizers) {
            val source = temp.newFile("mislabelled-${System.nanoTime()}.jpg").apply { writeBytes(gif) }
            val (format, out) = sanitize(sanitizer, source)
            assertEquals(ReencodedFormat.PNG, format)
            assertEquals(1 to 1, size(out))
        }
    }

    @Test
    fun `anything that does not decode is refused`() {
        for (sanitizer in sanitizers) {
            assertNull(sanitize(sanitizer, temp.newFile("garbage-${System.nanoTime()}").apply { writeBytes(ByteArray(64) { it.toByte() }) }).first)
            assertNull(sanitize(sanitizer, temp.newFile("empty-${System.nanoTime()}")).first)
            assertNull(sanitize(sanitizer, File(temp.root, "missing")).first)
        }
    }

    /** A [width]×[height] PNG of random pixels, which compress badly; transparent in places when [alpha]. */
    private fun noisePng(name: String, width: Int, height: Int, alpha: Boolean): File = temp.newFile(name).apply {
        val random = java.util.Random(42)
        val pixels = IntArray(width * height) { (if (alpha) random.nextInt(256) else 0xFF) shl 24 or (random.nextInt() and 0xFFFFFF) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        assertTrue("an RGBA PNG either way", bitmap.hasAlpha())
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `an opaque png over the limit is written as jpeg instead`() {
        val source = noisePng("opaque.png", 256, 256, alpha = false)
        val limit = source.length() - 1 // the re-encoded PNG holds the same pixels: just as large
        val target = File(temp.root, "out.jpg")
        val result = PlatformImageSanitizer(logger, sdkInt = 27, maxBytes = limit).sanitize(source, target)
        assertEquals(SanitizeResult.Written(ReencodedFormat.JPEG), result)
        assertTrue(target.length() <= limit)
        assertEquals(256 to 256, size(target))
    }

    @Test
    fun `a transparent png over the limit is written at half its size`() {
        val source = noisePng("clear.png", 256, 256, alpha = true)
        val limit = source.length() - 1
        val target = File(temp.root, "out.png")
        val result = PlatformImageSanitizer(logger, sdkInt = 27, maxBytes = limit).sanitize(source, target)
        assertEquals(SanitizeResult.Written(ReencodedFormat.PNG), result)
        assertTrue(target.length() <= limit)
        assertEquals(128 to 128, size(target))
    }

    @Test
    fun `an image over the limit even at half its size is too large, and nothing past the limit is written`() {
        for (alpha in listOf(false, true)) {
            val source = noisePng("big-$alpha.png", 256, 256, alpha)
            val target = File(temp.root, "out-$alpha")
            assertEquals(SanitizeResult.TooLarge, PlatformImageSanitizer(logger, sdkInt = 27, maxBytes = 1_000).sanitize(source, target))
            assertTrue(target.length() <= 1_000)
        }
    }
}
