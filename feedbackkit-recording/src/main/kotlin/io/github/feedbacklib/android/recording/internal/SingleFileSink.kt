package io.github.feedbacklib.android.recording.internal

import android.content.Context
import android.media.MediaRecorder
import android.view.Surface
import io.github.feedbacklib.android.spi.RecorderLog
import java.io.File

/**
 * Manual recording (spec §7): one MP4 of at most [maxDurationMillis]. The session stops it at that
 * time by wall clock ([limitMillis], [RecordingLimit]). MediaRecorder's own setMaxDuration is only a
 * second guard, set [RECORDER_LIMIT_MARGIN_MILLIS] later ([recorderMaxDurationMillis]): at the same
 * value it would close the file a few milliseconds before the timer's stop on a busy screen, and
 * MediaRecorder.stop() would then throw and lose a complete recording. It counts media time and fires
 * late when frames are sparse. The file can play shorter than the recording on a still screen (see
 * [CaptureSink]).
 */
internal class SingleFileSink(
    private val context: Context,
    private val output: File,
    private val maxDurationMillis: Long,
    private val log: RecorderLog,
) : CaptureSink {

    override val limitMillis: Long
        get() = maxDurationMillis

    private var recorder: MediaRecorder? = null
    private var prepared = false
    private var started = false
    private var limitReached = false

    override fun prepare(video: VideoSpec, events: SinkEvents): Surface {
        prepared = true // from here on the output path holds only what this sink writes
        output.parentFile?.mkdirs()
        output.delete()
        val r = newMediaRecorder(context)
        recorder = r
        r.configure(video, output, recorderMaxDurationMillis(maxDurationMillis))
        r.setOnInfoListener { _, what, _ ->
            log.guard("handle a recorder event") {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    limitReached = true
                    events.onLimitReached()
                }
            }
        }
        r.setOnErrorListener { _, what, extra -> log.guard("handle a recorder error") { events.onError(what, extra) } }
        try {
            r.prepare()
        } catch (error: Exception) {
            log.w("The screen recorder did not prepare for ${video.width}x${video.height}", error)
            throw error
        }
        return r.surface
    }

    override fun start() {
        checkNotNull(recorder).start()
        started = true
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        val stopped = try {
            if (started) {
                r.stop()
                true
            } else {
                false
            }
        } catch (e: RuntimeException) {
            // Thrown when no frame arrived — and by some devices once the limit already closed the file.
            if (!limitReached) log.w("The screen recording holds no usable video", e)
            false
        }
        try {
            r.release()
        } catch (e: Exception) {
            log.w("Could not release the recorder", e)
        }
        val usable = (stopped || limitReached) && output.length() > 0
        if (!usable) output.delete()
        return usable
    }

    override fun discard() {
        if (prepared) output.delete()
    }
}

/** How much later than the session's wall-clock limit MediaRecorder's own limit fires. */
internal const val RECORDER_LIMIT_MARGIN_MILLIS: Long = 2_000L

/** MediaRecorder's max duration for a session limit of [limitMillis]; 0 (none) stays 0. */
internal fun recorderMaxDurationMillis(limitMillis: Long): Long =
    if (limitMillis > 0) limitMillis + RECORDER_LIMIT_MARGIN_MILLIS else 0L
