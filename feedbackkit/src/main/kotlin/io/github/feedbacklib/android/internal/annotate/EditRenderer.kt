package io.github.feedbacklib.android.internal.annotate

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Applies an edit's ops, in order, to a surface. The same code draws the editor's preview (a
 * downsampled surface, `scale < 1`) and the saved PNG (`scale = 1`), so what the user sees is what
 * the report gets.
 */
internal object EditRenderer {

    const val BLUR_BLOCK_PX: Int = 16
    const val MAGNIFIER_ZOOM: Float = 2f
    const val MAGNIFIER_RING_COLOR: Int = 0xFF9E9E9E.toInt()

    /** Ring width as a share of the radius; at least one surface pixel. */
    const val MAGNIFIER_RING_RATIO: Float = 0.04f

    /** [scale] turns image pixels into [surface] pixels. */
    fun render(surface: PixelSurface, ops: List<EditOp>, scale: Float) {
        ops.forEach { apply(surface, it, scale) }
    }

    fun apply(surface: PixelSurface, op: EditOp, scale: Float) {
        when (op) {
            is EditOp.Stroke -> Raster.drawStroke(
                surface,
                FloatArray(op.points.size) { op.points[it] * scale },
                max(1f, op.width * scale),
                op.color,
            )
            is EditOp.Blur -> Raster.pixelate(
                surface,
                PixelRect.covering(op.left * scale, op.top * scale, op.right * scale, op.bottom * scale),
                max(1, (BLUR_BLOCK_PX * scale).roundToInt()),
            )
            is EditOp.Magnifier -> {
                val radius = op.radius * scale
                Raster.magnify(surface, op.centerX * scale, op.centerY * scale, radius, MAGNIFIER_ZOOM, max(1f, radius * MAGNIFIER_RING_RATIO), MAGNIFIER_RING_COLOR)
            }
        }
    }
}
