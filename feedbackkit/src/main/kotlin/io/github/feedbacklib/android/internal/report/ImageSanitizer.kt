package io.github.feedbacklib.android.internal.report

import java.io.File

/**
 * Re-encodes a gallery image before it enters a draft (spec §6): decoded by content, written afresh,
 * so no EXIF, XMP, IPTC or trailing data can reach the report. Blocking disk I/O.
 */
internal fun interface ImageSanitizer {
    /** Writes [source] anew into [target]; the format written, or null when [source] is not an image it can decode. */
    fun sanitize(source: File, target: File): ReencodedFormat?
}

/** What a re-encoded gallery image is written as. */
internal enum class ReencodedFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
}

/** The size and format decisions of re-encoding, apart from the platform codecs. */
internal object Reencoding {

    /** The longest side a re-encoded image keeps. */
    const val MAX_SIDE: Int = 4096

    const val JPEG_QUALITY: Int = 90

    /** Transparency needs PNG; anything opaque is a photo as far as the report goes. */
    fun formatFor(hasAlpha: Boolean): ReencodedFormat = if (hasAlpha) ReencodedFormat.PNG else ReencodedFormat.JPEG

    /** The smallest power-of-two decoder sample size that brings the longer side down to [maxSide]; 1 for unknown sizes. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        val longer = maxOf(width, height)
        var sample = 1
        while ((longer + sample - 1) / sample > maxSide) sample *= 2 // decoders round a sampled side up
        return sample
    }

    /** [width] × [height] scaled down, aspect kept, so the longer side is at most [maxSide]; never below 1 px. */
    fun targetSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longer = maxOf(width, height)
        if (longer <= maxSide) return width to height
        val scale = maxSide.toDouble() / longer
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}
