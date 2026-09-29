package io.github.feedbacklib.android.internal.report

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.lang.reflect.Modifier

// The platform ExifInterface is plain Java in android-all: it reads and saves real files here.
@RunWith(RobolectricTestRunner::class)
class ExifLocationStripperTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val logger = SdkLogger(LogLevel.NONE)

    private fun jpeg(name: String = "photo.tmp", gps: Boolean = true): File = temp.newFile(name).apply {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKE, "Maker")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:09:29 12:00:00")
            if (gps) {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "55/1,45/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "37/1,37/1,12/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "150/1")
                setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "0")
                setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:09:29")
                setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
            }
            saveAttributes()
        }
    }

    private fun gpsTags(file: File): Map<String, String> =
        ExifInterface(file.path).let { exif -> ImageLocation.GPS_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap() }

    @Test
    fun `the list covers every gps tag the platform knows`() {
        val platform = ExifInterface::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TAG_GPS_") }
            .map { it.get(null) as String }
        assertTrue(platform.isNotEmpty())
        assertTrue(platform.filterNot(ImageLocation.GPS_TAGS::contains).toString(), ImageLocation.GPS_TAGS.containsAll(platform))
    }

    @Test
    fun `gps tags leave a jpeg while its orientation and other exif stay`() {
        val file = jpeg()
        assertTrue("the fixture has a location", gpsTags(file).isNotEmpty())
        assertTrue(ExifLocationStripper(logger, sdkInt = 26).strip(file, "jpg"))
        assertEquals(emptyMap<String, String>(), gpsTags(file))
        val exif = ExifInterface(file.path)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("Maker", exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("2026:09:29 12:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertFalse(exif.getLatLong(FloatArray(2)))
    }

    @Test
    fun `gps tags leave a png where the platform rewrites it`() {
        val file = temp.newFile("photo.png").apply {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ExifInterface(path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "55/1,45/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }
        }
        assertTrue("the fixture has a location", gpsTags(file).isNotEmpty())
        assertTrue(ExifLocationStripper(logger, sdkInt = 30).strip(file, "png"))
        assertEquals(emptyMap<String, String>(), gpsTags(file))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }

    @Test
    fun `a jpeg without exif is kept byte for byte`() {
        val file = temp.newFile("plain.tmp").apply {
            val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val before = file.readBytes()
        assertTrue(ExifLocationStripper(logger).strip(file, "jpg"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `a jpeg whose exif the platform cannot parse is refused`() {
        // An Exif APP1 segment holding no TIFF header: nothing the platform can read or save.
        val broken = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 12) +
            "Exif".toByteArray() + byteArrayOf(0, 0) + byteArrayOf(1, 2, 3, 4) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val file = temp.newFile("broken.tmp").apply { writeBytes(broken) }
        assertFalse(ExifLocationStripper(logger).strip(file, "jpg"))
    }

    @Test
    fun `a png on an api that cannot rewrite it is refused only when it has exif`() {
        val plain = temp.newFile("plain.png").apply {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(ExifLocationStripper(logger, sdkInt = 29).strip(plain, "png"))
        val withExif = temp.newFile("exif.png").apply {
            val bytes = plain.readBytes()
            // Right after IHDR (signature 8 + length 4 + type 4 + data 13 + CRC 4 = 33): an eXIf chunk.
            writeBytes(bytes.copyOf(33) + byteArrayOf(0, 0, 0, 0) + "eXIf".toByteArray() + ByteArray(4) + bytes.copyOfRange(33, bytes.size))
        }
        assertFalse(ExifLocationStripper(logger, sdkInt = 29).strip(withExif, "png"))
    }

    @Test
    fun `a gif is kept as it is and a heif is refused where nothing can read it`() {
        val gif = temp.newFile("a.gif").apply { writeBytes("GIF89a".toByteArray()) }
        assertTrue(ExifLocationStripper(logger).strip(gif, "gif"))
        assertArrayEquals("GIF89a".toByteArray(), gif.readBytes())
        assertFalse(ExifLocationStripper(logger, sdkInt = 27).strip(temp.newFile("a.heic"), "heic"))
    }

    @Test
    fun `a heif the platform cannot read is refused`() {
        val file = temp.newFile("b.heic").apply { writeBytes(ByteArray(64)) }
        assertFalse(ExifLocationStripper(logger, sdkInt = 35).strip(file, "heic"))
    }

    @Test
    fun `a failure drops the image instead of throwing`() {
        assertFalse(ExifLocationStripper(logger).strip(File(temp.root, "missing.tmp"), "jpg"))
    }
}
