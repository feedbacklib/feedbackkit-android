package io.github.feedbacklib.android.internal.report

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * Takes the location out of an image copied into a draft, in place (spec §6: GPS EXIF tags are
 * removed on every API level, everything else — above all the orientation — stays). Returns false
 * when the image may still carry a location: it must then not enter the draft. Blocking disk I/O.
 */
internal fun interface LocationStripper {
    fun strip(file: File, extension: String): Boolean
}

/** What a gallery image of a draft [extension] needs so that no location reaches the report. */
internal enum class LocationHandling {
    /** No EXIF in the format (GIF). */
    KEEP,

    /**
     * The platform rewrites the EXIF in place: a file with an EXIF block is saved again without the
     * GPS tags (the save doubles as proof the platform understood the block); one without is kept.
     */
    STRIP,

    /** The platform reads the EXIF but cannot rewrite it (HEIF): refused only when it has a GPS tag. */
    REJECT_IF_GPS,

    /** The platform can neither read nor rewrite the EXIF (PNG, WebP before API 30): refused when it has any. */
    REJECT_IF_EXIF,

    /** Nothing on this API level can tell whether the file has a location (HEIF before API 28). */
    REJECT,
}

internal object ImageLocation {

    /** API 30: the platform ExifInterface reads and saves PNG and WebP. */
    private const val PNG_WEBP_EXIF_SDK = 30

    /** API 28: the platform ExifInterface reads HEIF. */
    private const val HEIF_EXIF_SDK = 28

    /**
     * Every tag of the EXIF GPS IFD (EXIF 2.32). A name the platform of a given API level does not
     * know is simply absent there: the platform drops unknown tags when it rewrites the EXIF.
     */
    val GPS_TAGS: List<String> = listOf(
        "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude",
        "GPSAltitudeRef", "GPSAltitude", "GPSTimeStamp", "GPSSatellites", "GPSStatus",
        "GPSMeasureMode", "GPSDOP", "GPSSpeedRef", "GPSSpeed", "GPSTrackRef", "GPSTrack",
        "GPSImgDirectionRef", "GPSImgDirection", "GPSMapDatum", "GPSDestLatitudeRef",
        "GPSDestLatitude", "GPSDestLongitudeRef", "GPSDestLongitude", "GPSDestBearingRef",
        "GPSDestBearing", "GPSDestDistanceRef", "GPSDestDistance", "GPSProcessingMethod",
        "GPSAreaInformation", "GPSDateStamp", "GPSDifferential", "GPSHPositioningError",
    )

    fun handling(extension: String, sdkInt: Int): LocationHandling = when (extension.lowercase()) {
        "jpg", "jpeg" -> LocationHandling.STRIP
        "png", "webp" -> if (sdkInt >= PNG_WEBP_EXIF_SDK) LocationHandling.STRIP else LocationHandling.REJECT_IF_EXIF
        "heic", "heif" -> if (sdkInt >= HEIF_EXIF_SDK) LocationHandling.REJECT_IF_GPS else LocationHandling.REJECT
        "gif" -> LocationHandling.KEEP
        else -> LocationHandling.REJECT
    }

    /**
     * Whether a JPEG, PNG or WebP [file] has an EXIF block (a JPEG `Exif` APP1 segment, a PNG
     * `eXIf` chunk, a WebP `EXIF` chunk).
     * Anything it cannot walk — another format, a truncated or malformed file — counts as having
     * one. Disk I/O; throws only what reading the file throws.
     */
    fun hasExifBlock(file: File, extension: String): Boolean =
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                when (extension.lowercase()) {
                    "jpg", "jpeg" -> jpegHasExif(input)
                    "png" -> pngHasExif(input)
                    "webp" -> webpHasExif(input)
                    else -> true
                }
            }
        } catch (e: EOFException) {
            true
        }

    private const val JPEG_SOI = 0xFFD8
    private const val JPEG_APP1 = 0xE1
    private const val JPEG_SOS = 0xDA
    private const val JPEG_EOI = 0xD9
    private val JPEG_EXIF_ID = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    /** Walks the segments before the image data, where a JPEG keeps its metadata. */
    private fun jpegHasExif(input: DataInputStream): Boolean {
        if (input.readUnsignedShort() != JPEG_SOI) return true
        while (true) {
            if (input.readUnsignedByte() != 0xFF) return true
            var marker = input.readUnsignedByte()
            while (marker == 0xFF) marker = input.readUnsignedByte() // fill bytes
            when (marker) {
                JPEG_SOS, JPEG_EOI -> return false
                0x01, in 0xD0..0xD7 -> continue // markers without a length
            }
            val length = input.readUnsignedShort() - 2
            if (length < 0) return true
            if (marker == JPEG_APP1 && length >= JPEG_EXIF_ID.size) {
                if (ByteArray(JPEG_EXIF_ID.size).also(input::readFully).contentEquals(JPEG_EXIF_ID)) return true
                skipFully(input, (length - JPEG_EXIF_ID.size).toLong())
            } else {
                skipFully(input, length.toLong())
            }
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private fun pngHasExif(input: DataInputStream): Boolean {
        if (!ByteArray(PNG_SIGNATURE.size).also(input::readFully).contentEquals(PNG_SIGNATURE)) return true
        while (true) {
            val length = input.readInt().toLong() and 0xFFFFFFFFL
            val type = fourCc(input)
            if (type == "eXIf") return true
            if (type == "IEND") return false
            skipFully(input, length + 4) // data and CRC
        }
    }

    private fun webpHasExif(input: DataInputStream): Boolean {
        if (fourCc(input) != "RIFF") return true
        skipFully(input, 4) // RIFF size
        if (fourCc(input) != "WEBP") return true
        while (true) {
            val first = input.read()
            if (first < 0) return false // the last chunk ended exactly at the end of the file
            val type = first.toChar() + String(ByteArray(3).also(input::readFully), Charsets.ISO_8859_1)
            if (type == "EXIF") return true
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xFFFFFFFFL
            skipFully(input, size + (size and 1)) // chunks are padded to an even size
        }
    }

    private fun fourCc(input: DataInputStream): String =
        String(ByteArray(4).also(input::readFully), Charsets.ISO_8859_1)

    /** Reads past [count] bytes: `skip` may go beyond the end of a file without telling. */
    private fun skipFully(input: DataInputStream, count: Long) {
        val scratch = ByteArray(SKIP_BUFFER)
        var left = count
        while (left > 0) {
            val read = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (read < 0) throw EOFException()
            left -= read
        }
    }

    private const val SKIP_BUFFER = 8 * 1024
}
