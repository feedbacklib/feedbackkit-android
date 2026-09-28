package io.github.feedbacklib.android.internal.annotate

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.io.OutputStream

/** An image opened for editing: its size as shown (after EXIF) and a downsampled [base] for the preview. */
internal class LoadedImage(val width: Int, val height: Int, val base: Bitmap?)

/** Decoding and drawing for the editor; a seam, so ViewModel tests need no real bitmaps. */
internal interface AnnotationImages {

    /** Opens [file] with a preview no smaller than needed for [maxWidth]×[maxHeight]; null when unreadable. Disk I/O. */
    fun load(file: File, maxWidth: Int, maxHeight: Int): LoadedImage?

    /** A copy of the preview with [ops] drawn at its scale; null when memory ran out. CPU: off the main thread. */
    fun render(image: LoadedImage, ops: List<EditOp>): ImageBitmap?

    /** [file] with [ops] applied at full resolution, as PNG into [out]; false on failure. Disk I/O and CPU. */
    fun writeEdited(file: File, ops: List<EditOp>, out: OutputStream): Boolean
}
