package io.github.feedbacklib.android.internal.annotate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.feedbacklib.android.internal.core.SdkLog
import io.github.feedbacklib.android.internal.ui.AttachmentRules
import io.github.feedbacklib.android.internal.ui.ExifOrientation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** [PixelSurface] over a mutable Bitmap: getPixels/setPixels give straight ARGB, as Raster expects. */
internal class BitmapSurface(private val bitmap: Bitmap) : PixelSurface {
    override val width: Int
        get() = bitmap.width
    override val height: Int
        get() = bitmap.height

    override fun read(rect: PixelRect): PixelRegion =
        PixelRegion(rect).also { bitmap.getPixels(it.pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height) }

    override fun write(region: PixelRegion) {
        val rect = region.rect
        bitmap.setPixels(region.pixels, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
    }
}

/**
 * The steps of opening and saving an image that allocate pixels, and so can run out of memory —
 * by throwing OutOfMemoryError or, for native pixels (API 26+), by returning null; a seam, so
 * tests can make each of them fail.
 */
internal open class BitmapSteps {

    /** Width and height as stored (before EXIF); null when [file] is not a readable image. Disk I/O. */
    open fun bounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** [file] at 1/[sample] of its size, ARGB_8888, mutable where the decoder can; null when it could not. Disk I/O. */
    open fun decode(file: File, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    /** [bitmap] turned per EXIF into a mutable bitmap, recycling [bitmap] when it makes a new one; null (and [bitmap] kept) when out of memory. */
    open fun orient(bitmap: Bitmap, orientation: Int): Bitmap? = ExifOrientation.applyMutable(bitmap, orientation)

    open fun render(bitmap: Bitmap, ops: List<EditOp>, scale: Float) = EditRenderer.render(BitmapSurface(bitmap), ops, scale)

    open fun compress(bitmap: Bitmap, out: OutputStream): Boolean = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
}

/**
 * The editor's images on Android bitmaps (spec §6). Big images must not run out of memory: the
 * preview decodes only as large as the screen needs, and a save that runs out of memory anywhere
 * — decoding, drawing the ops or encoding — starts over at 1/2, then at 1/4 of the size, with a
 * warning in the log; only when 1/4 fails too is the save a failure. Every bitmap a failed attempt
 * made is recycled. Never throws.
 *
 * The saved PNG is held to [maxBytes], the gallery limit, like every image the user attaches: an
 * edited 12 MP photo would otherwise come out at 20–30 MB and crowd older reports out of the queue.
 * An encoding over it starts over at the next smaller size exactly as running out of memory does,
 * and fails the save when even 1/4 is over. Bytes past the limit are never written to the output.
 *
 * Once [BitmapSteps.bounds] has read the image, a bitmap that comes back null counts as memory
 * running out: API 26+ allocates pixels natively, and the decoder and Bitmap.copy report a failed
 * allocation that way rather than with an OutOfMemoryError.
 */
internal class BitmapAnnotationImages(
    private val steps: BitmapSteps = BitmapSteps(),
    private val maxBytes: Long = AttachmentRules.GALLERY_MAX_BYTES,
) : AnnotationImages {

    override fun load(file: File, maxWidth: Int, maxHeight: Int): LoadedImage? =
        try {
            open(file, maxWidth, maxHeight)
        } catch (e: Exception) {
            SdkLog.logger.w("Could not open the image for editing", e)
            null
        }

    override fun render(image: LoadedImage, ops: List<EditOp>): ImageBitmap? {
        val base = image.base ?: return null
        var canvas: Bitmap? = null
        return try {
            val copy = base.copy(Bitmap.Config.ARGB_8888, true)
            if (copy == null) {
                SdkLog.logger.w("Not enough memory to draw the annotation preview")
                return null
            }
            canvas = copy
            steps.render(copy, ops, copy.width / image.width.toFloat())
            copy.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            canvas?.recycle()
            SdkLog.logger.w("Not enough memory to draw the annotation preview")
            null
        } catch (e: Exception) {
            canvas?.recycle()
            SdkLog.logger.w("Could not draw the annotation preview", e)
            null
        }
    }

    override fun writeEdited(file: File, ops: List<EditOp>, out: OutputStream): Boolean =
        try {
            save(file, ops, RestartableOutput(out, maxBytes))
        } catch (e: Exception) {
            SdkLog.logger.w("Could not save the edited image", e)
            false
        }

    private fun open(file: File, maxWidth: Int, maxHeight: Int): LoadedImage? {
        val (rawWidth, rawHeight) = steps.bounds(file) ?: return null
        val orientation = ExifOrientation.read(file)
        val (width, height) = ExifOrientation.orientedSize(rawWidth, rawHeight, orientation)
        var sample = previewSampleSize(width, height, maxWidth, maxHeight)
        repeat(PREVIEW_ATTEMPTS) { attempt ->
            val base = try {
                decodeOriented(file, orientation, sample)
            } catch (e: OutOfMemoryError) {
                null
            }
            if (base != null) {
                if (attempt > 0) SdkLog.logger.w("Memory was short; the image is previewed at 1/$sample of its size")
                return LoadedImage(width, height, base)
            }
            sample *= 2
        }
        // bounds() read the header, yet no decode came back: short of memory, or a damaged body.
        SdkLog.logger.w("Could not decode the image for editing, even at 1/${sample / 2} of its size")
        return null
    }

    private fun save(file: File, ops: List<EditOp>, out: RestartableOutput): Boolean {
        val (rawWidth, rawHeight) = steps.bounds(file) ?: return false
        val orientation = ExifOrientation.read(file)
        val (width, _) = ExifOrientation.orientedSize(rawWidth, rawHeight, orientation)
        var retried: Attempt? = null
        for (sample in SAVE_SAMPLES) {
            when (val attempt = saveAt(file, orientation, width, sample, ops, out)) {
                Attempt.SAVED -> {
                    if (sample > 1) SdkLog.logger.w("${retried?.reason}; the edited image is saved at 1/$sample of its size")
                    return true
                }
                Attempt.FAILED -> return false
                Attempt.OUT_OF_MEMORY, Attempt.TOO_LARGE -> {
                    retried = attempt
                    if (sample != SAVE_SAMPLES.last() && !out.restart()) {
                        SdkLog.logger.w("${attempt.reason}, and the output of the edited image cannot start over")
                        return false
                    }
                }
            }
        }
        SdkLog.logger.w("${retried?.reason} even at 1/${SAVE_SAMPLES.last()} of its size; the edited image is not saved")
        return false
    }

    /** One pass of the whole pipeline at 1/[sample]: decode, draw the ops at that scale, encode. */
    private fun saveAt(file: File, orientation: Int, width: Int, sample: Int, ops: List<EditOp>, out: RestartableOutput): Attempt {
        var bitmap: Bitmap? = null
        return try {
            val decoded = decodeOriented(file, orientation, sample) ?: return Attempt.OUT_OF_MEMORY
            bitmap = decoded
            // The ops are in full-size pixels; they scale with whatever size was decoded.
            steps.render(decoded, ops, decoded.width / width.toFloat())
            when {
                !steps.compress(decoded, out) -> Attempt.FAILED
                out.overflowed -> Attempt.TOO_LARGE
                else -> Attempt.SAVED
            }
        } catch (e: OutOfMemoryError) {
            Attempt.OUT_OF_MEMORY
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Mutable ARGB_8888 at 1/[sample], turned per EXIF; null when a bitmap could not be allocated
     * (the image itself was readable: bounds() said so). Recycles what it made before failing.
     */
    private fun decodeOriented(file: File, orientation: Int, sample: Int): Bitmap? {
        var current: Bitmap? = null
        try {
            val raw = steps.decode(file, sample) ?: return null
            current = raw
            val turned = steps.orient(raw, orientation)
            if (turned == null) {
                raw.recycle()
                return null
            }
            current = turned
            if (turned.isMutable) return turned
            // Only a decoder that ignored inMutable gets here; a null copy is memory running out too.
            val mutable = turned.copy(Bitmap.Config.ARGB_8888, true)
            turned.recycle()
            current = null
            return mutable
        } catch (e: Throwable) {
            current?.recycle()
            throw e
        }
    }

    private enum class Attempt(val reason: String) {
        SAVED(""),
        FAILED(""),

        /** Also a decode that returned nothing after bounds() read the header: most likely memory, possibly a damaged body. */
        OUT_OF_MEMORY("Memory was short or the image could not be decoded"),
        TOO_LARGE("The edited image was over the size limit"),
    }

    private companion object {
        /** The preview's own sample, then twice and four times that after an OutOfMemoryError. */
        const val PREVIEW_ATTEMPTS = 3

        /** Full size, then 1/2, then 1/4 after an OutOfMemoryError or an encoding over the size limit. */
        val SAVE_SAMPLES = intArrayOf(1, 2, 4)
    }
}

/**
 * Lets a save that ran out of memory half-way through encoding, or whose encoding went over
 * [maxBytes], start its output over: a file is truncated, an in-memory buffer reset. Anything else
 * can start over only if nothing was written. Nothing past [maxBytes] reaches [out]: the rest of an
 * encoding that went over is dropped and [overflowed] set.
 */
private class RestartableOutput(private val out: OutputStream, private val maxBytes: Long) : OutputStream() {
    private var written = 0L

    /** The encoding went over [maxBytes]; what [out] holds is cut short and must not be kept. */
    var overflowed = false
        private set

    override fun write(b: Int) {
        if (fits(1)) {
            out.write(b)
            written++
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (fits(len)) {
            out.write(b, off, len)
            written += len
        }
    }

    private fun fits(len: Int): Boolean {
        if (!overflowed && written + len > maxBytes) overflowed = true
        return !overflowed
    }

    override fun flush() = out.flush()

    /** Empties what was written so far; false when [out] cannot do that. */
    fun restart(): Boolean {
        when {
            written == 0L -> Unit
            out is FileOutputStream -> out.channel.truncate(0).position(0)
            out is ByteArrayOutputStream -> out.reset()
            else -> return false
        }
        written = 0L
        overflowed = false
        return true
    }
}

/** The editor's images in the SDK: real decoding, EXIF and PNG encoding. */
internal object AndroidAnnotationImages : AnnotationImages by BitmapAnnotationImages()
