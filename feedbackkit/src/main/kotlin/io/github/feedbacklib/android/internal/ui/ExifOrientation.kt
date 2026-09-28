package io.github.feedbacklib.android.internal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import java.io.File

/**
 * Camera photos from the gallery carry their rotation in EXIF (tag 274); BitmapFactory ignores it.
 * The strip, the editor and the edited PNG show the photo the way the gallery does.
 */
internal object ExifOrientation {
    const val NORMAL: Int = 1
    const val FLIP_HORIZONTAL: Int = 2
    const val ROTATE_180: Int = 3
    const val FLIP_VERTICAL: Int = 4
    const val TRANSPOSE: Int = 5
    const val ROTATE_90: Int = 6
    const val TRANSVERSE: Int = 7
    const val ROTATE_270: Int = 8

    private val EXIF_EXTENSIONS = setOf("jpg", "jpeg", "webp", "heic", "heif")

    fun swapsSides(orientation: Int): Boolean = orientation in TRANSPOSE..ROTATE_270

    /** Width and height as the image is shown. */
    fun orientedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (swapsSides(orientation)) height to width else width to height

    /** The orientation a photo asks for; [NORMAL] for screenshots, PNGs and anything unreadable. Disk I/O. */
    fun read(file: File): Int {
        if (file.extension.lowercase() !in EXIF_EXTENSIONS) return NORMAL
        return try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, NORMAL).takeIf { it in NORMAL..ROTATE_270 } ?: NORMAL
        } catch (e: Exception) {
            NORMAL
        }
    }

    /** The turn [orientation] asks for, about the origin; null when there is nothing to turn. */
    private fun matrix(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ROTATE_180 -> matrix.setRotate(180f)
            FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ROTATE_90 -> matrix.setRotate(90f)
            TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ROTATE_270 -> matrix.setRotate(-90f)
            else -> return null
        }
        return matrix
    }

    /** [bitmap] turned as [orientation] asks; the original is recycled when a new bitmap is made. May throw OutOfMemoryError. */
    fun apply(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = matrix(orientation) ?: return bitmap
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    /**
     * [bitmap] turned as [orientation] asks, drawn straight into a new mutable ARGB_8888 bitmap, so
     * the editor never holds a turned immutable copy beside a mutable one. Recycles the original
     * once turned; returns [bitmap] itself when nothing needs turning, and null (the original kept)
     * when the new bitmap could not be allocated.
     */
    fun applyMutable(bitmap: Bitmap, orientation: Int): Bitmap? {
        val matrix = matrix(orientation) ?: return bitmap
        val (width, height) = orientedSize(bitmap.width, bitmap.height, orientation)
        val turned = allocate(width, height) ?: return null
        // Turned about the origin, the image lands at negative coordinates: move it back into view.
        val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).also { matrix.mapRect(it) }
        matrix.postTranslate(-bounds.left, -bounds.top)
        Canvas(turned).drawBitmap(bitmap, matrix, null) // quarter turns and flips: pixel for pixel, no filtering
        bitmap.recycle()
        return turned
    }

    /**
     * A new mutable bitmap, or null when there is no memory for it. With native pixels (API 26+) the
     * platform reports that as a null from its native allocation, which [Bitmap.createBitmap] then
     * dereferences: a NullPointerException rather than an OutOfMemoryError.
     */
    private fun allocate(width: Int, height: Int): Bitmap? =
        try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: NullPointerException) {
            null
        }
}
