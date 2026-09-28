package io.github.feedbacklib.android.internal.annotate

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** How the image sits in the editor canvas: scaled to fit and centred. */
internal data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toImageX(viewX: Float): Float = (viewX - offsetX) / scale
    fun toImageY(viewY: Float): Float = (viewY - offsetY) / scale
    fun toViewX(imageX: Float): Float = imageX * scale + offsetX
    fun toViewY(imageY: Float): Float = imageY * scale + offsetY

    companion object {
        fun fit(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): FitTransform {
            if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return FitTransform(1f, 0f, 0f)
            val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
            return FitTransform(scale, (viewWidth - imageWidth * scale) / 2f, (viewHeight - imageHeight * scale) / 2f)
        }
    }
}

/**
 * The largest power-of-two sample size at which the image, fitted into [maxWidth]×[maxHeight], is
 * still decoded at least as large as it is drawn: a 12 MP photo on a phone decodes at 1/2, not
 * at full size (spec §6: big bitmaps must not run out of memory).
 */
internal fun previewSampleSize(imageWidth: Int, imageHeight: Int, maxWidth: Int, maxHeight: Int): Int {
    if (imageWidth <= 0 || imageHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) return 1
    val fit = min(maxWidth.toFloat() / imageWidth, maxHeight.toFloat() / imageHeight)
    var sample = 1
    while (sample * 2 * fit <= 1f) sample *= 2
    return sample
}

/** A magnifier under the finger: its index in the ops, and whether the finger is on its resize handle. */
internal data class MagnifierHit(val index: Int, val onHandle: Boolean)

internal object MagnifierGeometry {
    private const val DEFAULT_RADIUS_RATIO = 0.15f
    private const val MIN_RADIUS_RATIO = 0.05f
    private const val MAX_RADIUS_RATIO = 0.45f
    private const val DIAGONAL = 0.70710677f

    fun defaultRadius(imageWidth: Int, imageHeight: Int): Float = min(imageWidth, imageHeight) * DEFAULT_RADIUS_RATIO

    fun clampRadius(radius: Float, imageWidth: Int, imageHeight: Int): Float {
        val side = min(imageWidth, imageHeight).toFloat()
        return radius.coerceIn(side * MIN_RADIUS_RATIO, max(side * MIN_RADIUS_RATIO, side * MAX_RADIUS_RATIO))
    }

    /** The resize handle sits on the ring, down and to the right. */
    fun handle(op: EditOp.Magnifier): Pair<Float, Float> = (op.centerX + op.radius * DIAGONAL) to (op.centerY + op.radius * DIAGONAL)

    /** The topmost magnifier under ([x], [y]) in image pixels; its handle, within [handleReach], wins over its inside. */
    fun hit(ops: List<EditOp>, x: Float, y: Float, handleReach: Float): MagnifierHit? {
        for (index in ops.indices.reversed()) {
            val op = ops[index] as? EditOp.Magnifier ?: continue
            val (hx, hy) = handle(op)
            if (hypot(x - hx, y - hy) <= handleReach) return MagnifierHit(index, onHandle = true)
            if (hypot(x - op.centerX, y - op.centerY) <= op.radius) return MagnifierHit(index, onHandle = false)
        }
        return null
    }
}

/** Turning touches into ops; image pixels throughout. */
internal object AnnotationGestures {

    /** Points per stroke, far beyond one finger movement; keeps the saved steps small. */
    const val MAX_STROKE_POINTS: Int = 1_000

    /** Whether ([x], [y]) is far enough from the last point of [points] (x0, y0, …) to add. */
    fun shouldAdd(points: List<Float>, x: Float, y: Float, minDistance: Float): Boolean {
        if (points.size >= MAX_STROKE_POINTS * 2) return false
        if (points.size < 2) return true
        return hypot(x - points[points.size - 2], y - points[points.size - 1]) >= minDistance
    }

    /** The blur a drag from ([x0], [y0]) to ([x1], [y1]) makes: normalised, clipped to the image; null when smaller than [minSize]. */
    fun blurRect(x0: Float, y0: Float, x1: Float, y1: Float, imageWidth: Int, imageHeight: Int, minSize: Float): EditOp.Blur? {
        val left = min(x0, x1).coerceIn(0f, imageWidth.toFloat())
        val right = max(x0, x1).coerceIn(0f, imageWidth.toFloat())
        val top = min(y0, y1).coerceIn(0f, imageHeight.toFloat())
        val bottom = max(y0, y1).coerceIn(0f, imageHeight.toFloat())
        return if (right - left < minSize || bottom - top < minSize) null else EditOp.Blur(left, top, right, bottom)
    }
}
