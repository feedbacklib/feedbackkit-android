package io.github.feedbacklib.android.recording.internal

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import io.github.feedbacklib.android.spi.RecorderLog
import java.io.File

/**
 * Auto Screen Recording's encoder (spec §7): segments of [segmentMillis] in [dir], the last
 * [capacity] kept (the one being written included). Every segment is its own MediaRecorder on the
 * same virtual display: MediaRecorder switches to a queued next file only at a size
 * limit, never at a duration limit, so the sink restarts the recorder itself.
 *
 * - The segment ends by wall clock: a timer on the session thread, because MediaRecorder's own
 *   setMaxDuration counts media time, which runs behind the clock on a static screen or a slow
 *   encoder ([RecordingLimit] has the same reason). That own limit stays as a second guard, set
 *   [RECORDER_LIMIT_MARGIN_MILLIS] later.
 * - A restart detaches the display, finalises the segment, and attaches a fresh recorder; the seam
 *   is the time that takes. A restart that fails fails the session ([SinkEvents.onError]).
 * - A segment whose recorder did not stop cleanly holds no usable video and is dropped at once;
 *   segment names are never reused.
 * - No wall-clock limit on the whole recording ([limitMillis] 0): it runs until stopped.
 *
 * Session thread only.
 */
internal class SegmentSink(
    private val context: Context,
    private val dir: File,
    private val log: RecorderLog,
    private val segmentMillis: Long = SEGMENT_MILLIS,
    capacity: Int = CAPACITY,
) : CaptureSink {

    override val limitMillis: Long
        get() = 0L

    private val ring = SegmentRing(capacity)
    private var handler: Handler? = null
    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var recorderLimitReached = false
    private var video: VideoSpec? = null
    private var events: SinkEvents? = null
    private var sequence = 0
    private var paused = false

    /** The segment's time is up: the next one starts in a fresh recorder. */
    private val rollOver = Runnable {
        try {
            restartSegment()
        } catch (e: Exception) {
            log.e("Could not start the next automatic recording segment", e)
            log.guard("report the failed segment") { events?.onError(MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, 0) }
        }
    }

    /** Tests and logs only: the longest time from one segment's last frame to the next one's recorder. */
    @Volatile
    var longestSeamMillis: Long = 0L
        private set

    /** Tests and logs only: segments begun so far; it grows when a resume or a new segment really starts a recorder. */
    @Volatile
    var segmentsBegun: Int = 0
        private set

    /** Tests and logs only: how long the last [clip] took to finalise the current segment, and then to stitch. */
    @Volatile
    var lastClipFinaliseMillis: Long = 0L
        private set

    @Volatile
    var lastClipStitchMillis: Long = 0L
        private set

    override fun prepare(video: VideoSpec, events: SinkEvents): Surface {
        handler = Handler(checkNotNull(Looper.myLooper()) { "SegmentSink runs on the session thread" })
        this.video = video
        this.events = events
        dir.mkdirs()
        dir.listFiles()?.forEach(::delete) // what a previous session or process left behind
        return openRecorder()
    }

    override fun start() = startRecorder()

    /** Background, or FeedbackKit opened: the current segment is finalised and stays in the ring. Idempotent. */
    fun pause() {
        if (paused) return
        paused = true
        closeRecorder()
    }

    /** Records on in a new segment; the surface the display must draw into now, or null when not paused. */
    fun resume(): Surface? {
        if (!paused) return null
        paused = false
        val surface = openRecorder()
        startRecorder()
        return surface
    }

    /**
     * The last [windowUs] of what was recorded, in [output], or null; leaves the sink paused. The
     * segments go with the clip: the ring is emptied before the stitch, so the
     * segment a resume begins is the first of the next report, and the files are deleted after it.
     */
    fun clip(output: File, windowUs: Long): File? {
        val asked = SystemClock.elapsedRealtime()
        pause() // finishes the current segment
        val finalised = SystemClock.elapsedRealtime()
        lastClipFinaliseMillis = finalised - asked
        val taken = ring.clear()
        try {
            return Mp4Stitcher.stitch(taken, output, windowUs, log)
        } finally {
            lastClipStitchMillis = SystemClock.elapsedRealtime() - finalised
            log.d("Automatic recording clip: the current segment finalised in $lastClipFinaliseMillis ms, stitched in $lastClipStitchMillis ms")
            taken.forEach(::delete)
        }
    }

    /** Auto recording keeps nothing past its session: the segments are deleted. */
    override fun stop(): Boolean {
        paused = true
        closeRecorder()
        ring.clear().forEach(::delete)
        return true
    }

    override fun discard() {
        ring.clear().forEach(::delete)
    }

    /**
     * The segment is in the ring before the recorder writes it (the oldest one deleted first), so
     * [dir] never holds more than the ring's capacity; a recorder that does not prepare drops it again.
     */
    private fun openRecorder(): Surface {
        val file = nextFile()
        ring.begin(file).forEach(::delete)
        val r = newMediaRecorder(context)
        try {
            r.configure(checkNotNull(video), file, recorderMaxDurationMillis(segmentMillis))
            r.setOnInfoListener { mr, what, _ ->
                log.guard("handle a recorder event") {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && mr === recorder) onRecorderLimit()
                }
            }
            r.setOnErrorListener { _, what, extra -> log.guard("handle a recorder error") { events?.onError(what, extra) } }
            r.prepare()
        } catch (e: Exception) {
            log.guard("release the recorder that did not prepare") { r.release() }
            ring.finishCurrent(usable = false).forEach(::delete)
            throw e
        }
        recorder = r
        recorderStarted = false
        recorderLimitReached = false
        return r.surface
    }

    private fun startRecorder() {
        checkNotNull(recorder).start()
        recorderStarted = true
        segmentsBegun++
        checkNotNull(handler).postDelayed(rollOver, segmentMillis)
    }

    /**
     * MediaRecorder's own limit came first (it closed the file already): the next segment starts now.
     * Posted, not run here: this is inside the recorder's own listener, no place to release it.
     */
    private fun onRecorderLimit() {
        recorderLimitReached = true
        val h = checkNotNull(handler)
        h.removeCallbacks(rollOver)
        h.post(rollOver)
    }

    /** The display stops drawing first, then the segment is finalised and a fresh recorder goes on. */
    private fun restartSegment() {
        if (paused || recorder == null) return
        val sink = checkNotNull(events)
        val from = SystemClock.elapsedRealtime()
        sink.detachSurface()
        closeRecorder()
        val surface = openRecorder()
        sink.onSurfaceChanged(surface)
        startRecorder()
        val seam = SystemClock.elapsedRealtime() - from
        if (seam > longestSeamMillis) longestSeamMillis = seam
        log.d("Automatic recording segment ${sequence - 1} started; the seam took $seam ms")
    }

    /**
     * Finalises the current recorder. Its segment stays in the ring only when stop() succeeded, or
     * when MediaRecorder's own limit had already closed the file (stop() may throw then); otherwise
     * it is dropped before any next segment begins.
     */
    private fun closeRecorder() {
        handler?.removeCallbacks(rollOver)
        val r = recorder ?: return
        recorder = null
        val stopped = try {
            if (recorderStarted) {
                r.stop()
                true
            } else {
                false
            }
        } catch (e: RuntimeException) {
            // No frame in the segment (a still screen), or the recorder's own limit closed the file.
            if (!recorderLimitReached) log.d("An automatic recording segment holds no usable video", e)
            false
        }
        log.guard("release the recorder") { r.release() }
        val usable = stopped || (recorderLimitReached && (ring.current?.length() ?: 0L) > 0)
        ring.finishCurrent(usable).forEach(::delete)
    }

    private fun nextFile(): File = File(dir, "segment-${sequence++}.mp4")

    private fun delete(file: File) {
        if (file.exists() && !file.delete()) log.w("Could not delete a recording segment")
    }

    companion object {
        const val SEGMENT_MILLIS: Long = 10_000
        const val CAPACITY: Int = 4
    }
}
