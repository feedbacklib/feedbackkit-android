package io.github.feedbacklib.android.internal.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.feedbacklib.android.internal.core.RestartableOutput
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.ui.AttachmentRules
import io.github.feedbacklib.android.internal.ui.ExifOrientation
import java.io.File

/**
 * [ImageSanitizer] over the platform codecs: `ImageDecoder` from API 28 (it turns a photo upright
 * by its EXIF orientation itself), `BitmapFactory` before (turned here by [ExifOrientation]; on
 * API 26–27 the platform ExifInterface reads no EXIF from WebP or PNG, so those stay as stored).
 * Both tell the format from the bytes, never from a provider's MIME type; an animated GIF gives
 * its first frame. Both decode at a power-of-two sample that brings the long side down to
 * [Reencoding.MAX_SIDE], so memory stays bounded.
 *
 * The encoding is held to [maxBytes], the gallery limit: a transparent or photographic PNG can
 * come out several times larger than its source. Over it, an image with no transparent pixel is
 * written as JPEG instead, then the image is tried again at half its size; still over, it is
 * [SanitizeResult.TooLarge]. Nothing past [maxBytes] is ever written. Never throws.
 */
internal class PlatformImageSanitizer(
    private val logger: SdkLogger,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val maxBytes: Long = AttachmentRules.GALLERY_MAX_BYTES,
) : ImageSanitizer {

    override fun sanitize(source: File, target: File): SanitizeResult {
        var bitmap: Bitmap? = null
        return try {
            // [sdkInt] lets tests take the older path; the platform check keeps the newer one where it exists.
            val decoded = if (sdkInt >= Build.VERSION_CODES.P && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(source)
            } else {
                decodeWithBitmapFactory(source)
            }
            if (decoded == null) {
                logger.w("A gallery image could not be decoded")
                return SanitizeResult.Failed
            }
            bitmap = decoded
            val plain = plainArgb(decoded)
            bitmap = plain
            if (plain == null) {
                logger.w("Not enough memory to re-encode a gallery image")
                SanitizeResult.Failed
            } else {
                target.outputStream().use { encode(plain, RestartableOutput(it, maxBytes)) }
            }
        } catch (e: Exception) {
            logger.w("Could not re-encode a gallery image", e)
            SanitizeResult.Failed
        } catch (e: OutOfMemoryError) {
            logger.w("Could not re-encode a gallery image", e)
            SanitizeResult.Failed
        } catch (e: StackOverflowError) {
            logger.w("Could not re-encode a gallery image", e)
            SanitizeResult.Failed
        } finally {
            bitmap?.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(source: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // encoded right after: no hardware bitmap
            // A sample, not a target size: a size sampling cannot reach is decoded larger, then scaled.
            decoder.setTargetSampleSize(Reencoding.sampleSize(info.size.width, info.size.height))
        }

    private fun decodeWithBitmapFactory(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = Reencoding.sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: return null
        return try {
            ExifOrientation.apply(decoded, ExifOrientation.readByContent(source))
        } catch (e: Throwable) {
            decoded.recycle()
            throw e
        }
    }

    /**
     * [bitmap] as plain 8-bit ARGB with no gain map, recycling it when a copy was needed; null when
     * the copy could not be allocated ([bitmap] then recycled too). Wide-gamut and HDR configs would
     * otherwise be encoded as such, and a gain map is written as Ultra HDR metadata and a second image.
     */
    private fun plainArgb(bitmap: Bitmap): Bitmap? {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() } ?: return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && argb.hasGainmap()) argb.gainmap = null
        return argb
    }

    private fun encode(bitmap: Bitmap, out: RestartableOutput): SanitizeResult {
        var format = Reencoding.formatFor(bitmap.hasAlpha())
        if (write(bitmap, format, out) ?: return SanitizeResult.Failed) return SanitizeResult.Written(format)
        if (format == ReencodedFormat.PNG && isOpaque(bitmap)) {
            format = ReencodedFormat.JPEG
            if (!out.restart()) return SanitizeResult.Failed
            if (write(bitmap, format, out) ?: return SanitizeResult.Failed) return SanitizeResult.Written(format)
        }
        if (!out.restart()) return SanitizeResult.Failed
        val half = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width / 2), maxOf(1, bitmap.height / 2), true)
        try {
            if (write(half, format, out) ?: return SanitizeResult.Failed) {
                logger.w("A gallery image was over the size limit and is attached at half its size")
                return SanitizeResult.Written(format)
            }
        } finally {
            if (half !== bitmap) half.recycle()
        }
        logger.w("A gallery image is over the size limit even at half its size")
        return SanitizeResult.TooLarge
    }

    /** true when the encoding fits, false when it went over the limit, null when the encoder failed. */
    private fun write(bitmap: Bitmap, format: ReencodedFormat, out: RestartableOutput): Boolean? {
        val compressFormat = when (format) {
            ReencodedFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ReencodedFormat.PNG -> Bitmap.CompressFormat.PNG
        }
        if (!bitmap.compress(compressFormat, Reencoding.JPEG_QUALITY, out)) {
            // An encoder that stops on its own is not the limit: overflowed bytes are dropped, not refused.
            return if (out.overflowed) false else null
        }
        return !out.overflowed
    }

    private fun isOpaque(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (!Reencoding.opaque(row)) return false
        }
        return true
    }
}
