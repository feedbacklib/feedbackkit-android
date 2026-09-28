package io.github.feedbacklib.android.recording.internal

import kotlin.math.max
import kotlin.math.min

/**
 * What the screen is recorded at (spec §7): H.264 in MP4, the short side at most [MAX_SHORT_SIDE] px,
 * [FRAME_RATE] fps, about [BIT_RATE] bit/s, no sound. Pure, so the size arithmetic is tested on the JVM.
 */
internal data class VideoSpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val frameRate: Int = FRAME_RATE,
    val bitRate: Int = BIT_RATE,
) {
    /**
     * The first size [isSupported] takes: this one, then ever smaller ones of the same aspect (short
     * side down the [FALLBACK_SHORT_SIDES]), density scaled alike; null when none fits. An encoder that
     * takes only the other orientation is not offered it: the display would be squeezed into the frame.
     */
    fun fitEncoder(isSupported: (width: Int, height: Int) -> Boolean): VideoSpec? {
        val shortSide = min(width, height)
        val candidates = sequenceOf(this) + FALLBACK_SHORT_SIDES.asSequence().filter { it < shortSide }.map { scaledTo(it, shortSide) }
        return candidates.firstOrNull { isSupported(it.width, it.height) }
    }

    private fun scaledTo(target: Int, shortSide: Int): VideoSpec {
        fun scaled(value: Int): Int = (value.toLong() * target / shortSide).toInt()
        return copy(width = align(scaled(width)), height = align(scaled(height)), densityDpi = max(1, scaled(densityDpi)))
    }

    companion object {
        const val MAX_SHORT_SIDE: Int = 720
        const val FRAME_RATE: Int = 30
        const val BIT_RATE: Int = 4_000_000

        /** Short sides tried, in order, when the encoder refuses the display's own size. */
        private val FALLBACK_SHORT_SIDES = listOf(640, 576, 480, 432, 360, 320, 240, 176)

        /** Every H.264 encoder takes sides in whole macroblocks. */
        private const val ALIGNMENT = 16

        /**
         * The recording size of a [width]×[height] display at [densityDpi]: scaled down, never up, so the
         * short side fits [MAX_SHORT_SIDE], each side rounded down to a multiple of 16; the density
         * scales alike, so the mirrored layout looks the same. Integer arithmetic: no rounding drift.
         */
        fun fit(width: Int, height: Int, densityDpi: Int): VideoSpec {
            require(width > 0 && height > 0) { "No display size: ${width}x$height" }
            val shortSide = min(width, height)
            fun scaled(value: Int): Int = if (shortSide > MAX_SHORT_SIDE) (value.toLong() * MAX_SHORT_SIDE / shortSide).toInt() else value
            return VideoSpec(
                width = align(scaled(width)),
                height = align(scaled(height)),
                densityDpi = max(1, scaled(densityDpi)),
            )
        }

        private fun align(side: Int): Int = max(ALIGNMENT, side / ALIGNMENT * ALIGNMENT)
    }
}
