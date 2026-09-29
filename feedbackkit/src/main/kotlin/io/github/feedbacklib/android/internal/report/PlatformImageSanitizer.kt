package io.github.feedbacklib.android.internal.report

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.ui.ExifOrientation
import java.io.File

/**
 * [ImageSanitizer] over the platform codecs: `ImageDecoder` from API 28 (it turns a photo upright
 * by its EXIF orientation itself), `BitmapFactory` before (turned here by [ExifOrientation]). Both
 * tell the format from the bytes, never from a provider's MIME type; an animated GIF gives its first
 * frame. The long side is brought down to [Reencoding.MAX_SIDE] while decoding, so memory stays
 * bounded. Never throws: whatever goes wrong, the image is refused.
 */
internal class PlatformImageSanitizer(
    private val logger: SdkLogger,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ImageSanitizer {

    override fun sanitize(source: File, target: File): ReencodedFormat? {
        var bitmap: Bitmap? = null
        return try {
            // [sdkInt] lets tests take the older path; the platform check keeps the newer one where it exists.
            val decoded = if (sdkInt >= Build.VERSION_CODES.P && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(source)
            } else {
                decodeWithBitmapFactory(source)
            }
            bitmap = decoded
            if (decoded == null) {
                logger.w("A gallery image could not be decoded")
                null
            } else {
                val format = Reencoding.formatFor(decoded.hasAlpha())
                val written = target.outputStream().use { decoded.compress(format.compressFormat(), Reencoding.JPEG_QUALITY, it) }
                if (written) format else null.also { logger.w("A gallery image could not be encoded") }
            }
        } catch (e: Exception) {
            logger.w("Could not re-encode a gallery image", e)
            null
        } catch (e: OutOfMemoryError) {
            logger.w("Could not re-encode a gallery image", e)
            null
        } catch (e: StackOverflowError) {
            logger.w("Could not re-encode a gallery image", e)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(source: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // encoded right after: no hardware bitmap
            val (width, height) = Reencoding.targetSize(info.size.width, info.size.height)
            if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
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

    private fun ReencodedFormat.compressFormat(): Bitmap.CompressFormat = when (this) {
        ReencodedFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ReencodedFormat.PNG -> Bitmap.CompressFormat.PNG
    }
}
