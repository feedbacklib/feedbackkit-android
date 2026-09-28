package io.github.feedbacklib.android.recording.internal

/** Where a clip starts: [startOffsetUs] into segment [firstSegment]; everything after it is kept. */
internal data class ClipPlan(val firstSegment: Int, val startOffsetUs: Long)

/**
 * The arithmetic of the Auto Screen Recording clip (spec §7: the last 30 s), pure. The stitcher cuts
 * at the first key frame at or after [ClipPlan.startOffsetUs], so the clip is never longer than the
 * window, and lays the kept segments end to end one frame apart: a pause (background) or a frame gap
 * at a segment boundary never shows up as frozen time.
 *
 * [plan] returns null only when there is nothing to plan over at all — [durationsUs] empty, or
 * [windowUs] not positive. Segments that exist but hold zero recorded time (spec §7 review focus 5: a
 * corrupt segment, or no frames on a static screen) still produce a plan, [ClipPlan(0, 0)] among them
 * — [plan] only lays out where segments would start, it never inspects how many samples they actually
 * hold. When the stitcher then ends up writing zero samples for such a plan, it is the stitcher, not
 * [plan], that treats that as no clip at all (`onClip(null)`).
 */
internal object ClipMath {

    /**
     * The start of the last [windowUs] of segments lasting [durationsUs] each, oldest first; null only
     * when [durationsUs] is empty or [windowUs] is not positive — never merely because every duration
     * in [durationsUs] is zero (see the class doc).
     */
    fun plan(durationsUs: List<Long>, windowUs: Long): ClipPlan? {
        if (durationsUs.isEmpty() || windowUs <= 0) return null
        var remaining = windowUs
        for (index in durationsUs.indices.reversed()) {
            val duration = durationsUs[index].coerceAtLeast(0)
            if (duration >= remaining) return ClipPlan(index, duration - remaining)
            remaining -= duration
        }
        return ClipPlan(0, 0)
    }

    /**
     * Output time of a sample of a segment whose first kept sample was at [segmentStartUs] and is laid
     * at [baseUs]. A sample earlier than [segmentStartUs] belongs before the window the plan kept: the
     * stitcher drops that sample itself rather than passing it here — the `coerceAtLeast(0)` below is
     * only rounding-safety at the exact boundary, not a substitute for that filtering (relying on it to
     * clamp earlier samples would stack them all onto [baseUs] and duplicate the frame at the cut).
     */
    fun outputTime(baseUs: Long, segmentStartUs: Long, sampleTimeUs: Long): Long =
        baseUs + (sampleTimeUs - segmentStartUs).coerceAtLeast(0)

    /**
     * Where the next segment is laid: one frame after this segment's last sample. [lastSampleUs] is the
     * largest presentation timestamp the stitcher wrote for this segment, not necessarily the last one
     * it decoded — a stream can carry samples out of presentation order.
     */
    fun nextBase(baseUs: Long, segmentStartUs: Long, lastSampleUs: Long, frameUs: Long): Long =
        outputTime(baseUs, segmentStartUs, lastSampleUs) + frameUs

    /**
     * Where the newest run of segments of one track format begins in [formats] (oldest first); 0 for
     * none. Samples of one MP4 track share one codec configuration (for H.264 the SPS/PPS), and a new
     * recorder instance may configure its encoder differently: a clip that crossed such a change would
     * decode with artefacts, so it keeps only the newest run — the seconds right before the report.
     */
    fun <T> newestRunStart(formats: List<T>): Int {
        if (formats.isEmpty()) return 0
        var start = formats.lastIndex
        while (start > 0 && formats[start - 1] == formats.last()) start--
        return start
    }
}

/**
 * Which samples of one segment go into the clip and at what output time, pure. A sample before
 * [fromUs] (the plan's cut, or the segment's own first sample) belongs outside the clip and is
 * dropped, never clamped onto the cut. The first sample written is a key frame at or after [fromUs]
 * — a decoder can start nowhere else — and it is laid at [baseUs]; a sample presented before that key
 * frame is dropped too. Samples are fed in the file's (decode) order.
 */
internal class SegmentCut(private val baseUs: Long, private val fromUs: Long) {

    private var anchorUs = -1L
    private var latestUs = -1L

    /** Whether any sample of this segment went into the clip. */
    val wroteAny: Boolean
        get() = anchorUs >= 0

    /** The output time of the sample presented at [timeUs], or null when it is dropped. */
    fun place(timeUs: Long, sync: Boolean): Long? {
        if (timeUs < fromUs) return null
        if (anchorUs < 0) {
            if (!sync) return null
            anchorUs = timeUs
        }
        if (timeUs < anchorUs) return null
        latestUs = maxOf(latestUs, timeUs)
        return ClipMath.outputTime(baseUs, anchorUs, timeUs)
    }

    /** Where the next segment is laid: one frame after the latest sample written; [baseUs] when none was. */
    fun nextBase(frameUs: Long): Long = if (wroteAny) ClipMath.nextBase(baseUs, anchorUs, latestUs, frameUs) else baseUs
}
