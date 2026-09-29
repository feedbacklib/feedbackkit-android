package io.github.feedbacklib.android.internal.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class ImageLocationTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `each gallery type gets the handling its platform support allows`() {
        val table = listOf(
            Triple("jpg", 26, LocationHandling.STRIP),
            Triple("jpg", 35, LocationHandling.STRIP),
            Triple("png", 29, LocationHandling.REJECT_IF_EXIF),
            Triple("png", 30, LocationHandling.STRIP),
            Triple("webp", 26, LocationHandling.REJECT_IF_EXIF),
            Triple("webp", 30, LocationHandling.STRIP),
            Triple("heic", 27, LocationHandling.REJECT),
            Triple("heic", 28, LocationHandling.REJECT_IF_GPS),
            Triple("heif", 35, LocationHandling.REJECT_IF_GPS),
            Triple("gif", 26, LocationHandling.KEEP),
            Triple("bmp", 35, LocationHandling.REJECT),
        )
        for ((extension, sdk, expected) in table) assertEquals(expected, ImageLocation.handling(extension, sdk), "$extension on $sdk")
    }

    @Test
    fun `the gps tag list is the whole gps ifd and nothing else`() {
        assertTrue(ImageLocation.GPS_TAGS.all { it.startsWith("GPS") })
        assertEquals(32, ImageLocation.GPS_TAGS.toSet().size)
        assertTrue(ImageLocation.GPS_TAGS.containsAll(listOf("GPSLatitude", "GPSLongitude", "GPSAltitude", "GPSHPositioningError")))
    }

    private fun file(name: String, bytes: ByteArray): File = File(temp, name).apply { writeBytes(bytes) }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()

    private fun jpeg(vararg segments: Pair<Int, ByteArray>): ByteArray = bytes {
        writeShort(0xFFD8)
        for ((marker, data) in segments) {
            writeShort(0xFF00 or marker)
            writeShort(data.size + 2)
            write(data)
        }
        writeShort(0xFFDA) // start of scan: no metadata after it
        writeShort(4)
        writeShort(0)
    }

    private val exifId = "Exif".toByteArray() + byteArrayOf(0, 0) + ByteArray(8)

    @Test
    fun `a jpeg has an exif block only with an exif app1 segment`() {
        assertTrue(ImageLocation.hasExifBlock(file("a.jpg", jpeg(0xE0 to ByteArray(14), 0xE1 to exifId)), "jpg"))
        assertFalse(ImageLocation.hasExifBlock(file("b.jpg", jpeg(0xE0 to ByteArray(14))), "jpg"))
        val xmp = "http://ns.adobe.com/xap/1.0/".toByteArray() + byteArrayOf(0)
        assertFalse(ImageLocation.hasExifBlock(file("c.jpg", jpeg(0xE1 to xmp)), "jpeg"), "an xmp app1 is not exif")
    }

    private fun pngChunk(type: String, data: ByteArray = ByteArray(0)): ByteArray = bytes {
        writeInt(data.size)
        write(type.toByteArray(Charsets.ISO_8859_1))
        write(data)
        writeInt(0) // CRC: not checked
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `a png has an exif block only with an eXIf chunk`() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val idat = pngChunk("IDAT", ByteArray(5))
        val iend = pngChunk("IEND")
        assertFalse(ImageLocation.hasExifBlock(file("a.png", pngSignature + ihdr + idat + iend), "png"))
        assertTrue(ImageLocation.hasExifBlock(file("b.png", pngSignature + ihdr + pngChunk("eXIf", ByteArray(8)) + idat + iend), "png"))
    }

    private fun webp(vararg chunks: Pair<String, ByteArray>): ByteArray = bytes {
        write("RIFF".toByteArray())
        writeInt(0)
        write("WEBP".toByteArray())
        for ((type, data) in chunks) {
            write(type.toByteArray())
            writeInt(Integer.reverseBytes(data.size))
            write(data)
            if (data.size % 2 == 1) write(0)
        }
    }

    @Test
    fun `a webp has an exif block only with an EXIF chunk`() {
        assertFalse(ImageLocation.hasExifBlock(file("a.webp", webp("VP8X" to ByteArray(10), "VP8 " to ByteArray(7))), "webp"))
        assertTrue(ImageLocation.hasExifBlock(file("b.webp", webp("VP8X" to ByteArray(10), "VP8 " to ByteArray(7), "EXIF" to ByteArray(9))), "webp"))
    }

    @Test
    fun `a file that cannot be walked counts as having an exif block`() {
        assertTrue(ImageLocation.hasExifBlock(file("a.png", pngSignature + pngChunk("IHDR", ByteArray(13)).copyOf(10)), "png"), "truncated")
        assertTrue(ImageLocation.hasExifBlock(file("b.png", byteArrayOf(1, 2, 3)), "png"), "not a png")
        assertTrue(ImageLocation.hasExifBlock(file("c.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())), "jpg"), "truncated")
        assertTrue(ImageLocation.hasExifBlock(file("d.webp", webp("VP8 " to ByteArray(7)).copyOf(20)), "webp"), "truncated")
        assertTrue(ImageLocation.hasExifBlock(file("e.gif", byteArrayOf(1)), "gif"), "not a probed format")
    }
}
