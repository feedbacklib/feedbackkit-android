package io.github.feedbacklib.android.recording.internal

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.feedbacklib.android.spi.RecorderLog
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins Auto Screen Recording segments into one MP4 of at most the window (spec §7), copying samples
 * without re-encoding. Blocking — the session thread.
 *
 * - Every time is measured from each segment's own first sample: a segment's length is its first to
 *   its latest sample plus one frame, and the plan's cut ([ClipMath.plan]) is an offset from that
 *   first sample.
 * - [SegmentCut] drops what lies before the cut and opens each segment on a key frame, so the clip is
 *   never longer than the window; segments are laid end to end one frame apart, a pause never shows.
 * - A segment a player could not read, or with no video sample, is skipped. Segments whose track
 *   format (size, SPS/PPS) differs from the newest one are left out, oldest first
 *   ([ClipMath.newestRunStart]): one MP4 track has one codec configuration.
 * - The muxer is created on the first sample to write: with none there is no clip. A muxer that
 *   started but did not finish (a write failed, even the first) is closed by [close]; on API 26–29
 *   that can still leak the native muxer and its file (see [close]).
 */
internal object Mp4Stitcher {

    private const val FRAME_US = 1_000_000L / VideoSpec.FRAME_RATE
    /** The smallest sample buffer; a larger KEY_MAX_INPUT_SIZE of the run's formats wins. */
    private const val MIN_BUFFER_BYTES = 2 * 1024 * 1024

    /** A readable segment: its video format and the span of its samples' presentation times. */
    private class Segment(val file: File, val format: MediaFormat, val key: TrackKey, val firstUs: Long, val latestUs: Long) {
        val durationUs: Long
            get() = latestUs - firstUs + FRAME_US
    }

    /** What must match for samples of two segments to go into one track. */
    private data class TrackKey(val mime: String?, val width: Int, val height: Int, val config: List<List<Byte>>)

    /** The last [windowUs] of [segments] (oldest first) in [output]; null, and no file, when nothing usable. */
    fun stitch(segments: List<File>, output: File, windowUs: Long, log: RecorderLog): File? {
        output.parentFile?.mkdirs()
        output.delete()
        val readable = segments.mapNotNull { probe(it, log) }
        val run = readable.drop(ClipMath.newestRunStart(readable.map { it.key }))
        if (run.size < readable.size) {
            log.w("${readable.size - run.size} older automatic recording segments have another video format; the clip starts after them")
        }
        val plan = ClipMath.plan(run.map { it.durationUs }, windowUs) ?: return null
        var muxer: MediaMuxer? = null
        var complete = false
        try {
            val buffer = ByteBuffer.allocateDirect(bufferBytes(run.drop(plan.firstSegment).map { it.format }))
            val info = MediaCodec.BufferInfo()
            var track = -1
            var written = 0
            var base = 0L
            for (index in plan.firstSegment until run.size) {
                val segment = run[index]
                val fromUs = segment.firstUs + if (index == plan.firstSegment) plan.startOffsetUs else 0L
                val cut = SegmentCut(base, fromUs)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.file.path)
                    extractor.selectTrack(videoTrack(extractor) ?: continue)
                    // To the key frame before the cut; SegmentCut drops what lies before it.
                    if (fromUs > segment.firstUs) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        val at = cut.place(extractor.sampleTime, sync)
                        if (at != null) {
                            val mux = muxer ?: MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                                muxer = it
                                track = it.addTrack(segment.format)
                                it.start()
                            }
                            info.set(0, size, at, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            mux.writeSampleData(track, buffer, info)
                            written++
                        }
                        extractor.advance()
                    }
                } finally {
                    extractor.release()
                }
                base = cut.nextBase(FRAME_US)
            }
            val mux = muxer ?: return null // not one sample: no clip
            mux.stop()
            complete = true
            muxer = null
            log.guard("release the muxer") { mux.release() }
            log.d("Joined $written samples of ${run.size - plan.firstSegment} automatic recording segments")
            return output
        } catch (e: Exception) {
            log.w("Could not join the automatic recording", e)
            return null
        } finally {
            muxer?.let { close(it, log) }
            if (!complete) output.delete()
        }
    }

    /**
     * Frees a muxer that did not finish. MediaMuxer.release() on a started muxer calls stop() first,
     * and when that throws (no sample, a broken write) release() ends before it frees the native
     * muxer. From API 30 stop() marks the muxer stopped in a `finally` even when it throws, so it is
     * called on its own first and the release after it only frees. On API 26–29 stop() has no such
     * `finally`: the muxer stays "started", release() throws again and the native muxer and its file
     * leak. There is no public way around it; the case needs a write to fail after the muxer started,
     * which is rare, and stays a known deferred issue.
     */
    private fun close(muxer: MediaMuxer, log: RecorderLog) {
        try {
            muxer.stop()
        } catch (e: Exception) {
            log.d("The unfinished clip could not be finalised", e)
        }
        log.guard("release the muxer") { muxer.release() }
    }

    /** Room for the largest sample the run's tracks declare, at least [MIN_BUFFER_BYTES]. */
    private fun bufferBytes(formats: List<MediaFormat>): Int {
        val declared = formats.maxOfOrNull { format ->
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        } ?: 0
        return maxOf(declared, MIN_BUFFER_BYTES)
    }

    /** The segment's video format and sample span, or null for a file a player could not read. */
    private fun probe(file: File, log: RecorderLog): Segment? {
        if (!file.exists() || file.length() == 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val track = videoTrack(extractor) ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            var first = -1L
            var latest = -1L
            while (extractor.sampleTrackIndex >= 0) {
                val time = extractor.sampleTime
                if (first < 0) first = time
                latest = maxOf(latest, time)
                extractor.advance()
            }
            if (first < 0) null else Segment(file, format, keyOf(format), first, latest)
        } catch (e: Exception) {
            log.d("Skipped an unreadable automatic recording segment", e)
            null
        } finally {
            extractor.release()
        }
    }

    private fun keyOf(format: MediaFormat) = TrackKey(
        mime = format.getString(MediaFormat.KEY_MIME),
        width = format.getInteger(MediaFormat.KEY_WIDTH),
        height = format.getInteger(MediaFormat.KEY_HEIGHT),
        config = CODEC_CONFIG.map { name -> format.getByteBuffer(name)?.let(::bytesOf).orEmpty() },
    )

    private fun bytesOf(buffer: ByteBuffer): List<Byte> {
        val copy = buffer.duplicate()
        copy.rewind()
        return List(copy.remaining()) { copy.get() }
    }

    /** The codec configuration MediaExtractor gives an H.264 track: csd-0 the SPS, csd-1 the PPS. */
    private val CODEC_CONFIG = listOf("csd-0", "csd-1")

    private fun videoTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
}
