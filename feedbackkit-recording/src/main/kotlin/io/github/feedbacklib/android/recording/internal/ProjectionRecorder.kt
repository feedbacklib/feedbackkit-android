package io.github.feedbacklib.android.recording.internal

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.feedbacklib.android.spi.AutoRecordingListener
import io.github.feedbacklib.android.spi.AutoRecordingSession
import io.github.feedbacklib.android.spi.ClipCallback
import io.github.feedbacklib.android.spi.RecorderLog
import io.github.feedbacklib.android.spi.RecordingListener
import io.github.feedbacklib.android.spi.RecordingSession
import io.github.feedbacklib.android.spi.RecordingStopReason
import io.github.feedbacklib.android.spi.ScreenRecorder
import io.github.feedbacklib.android.spi.ScreenRecorderProvider
import java.io.File

/**
 * Listed in META-INF/services; ServiceLoader creates it with the public no-argument constructor,
 * and consumer-rules.pro keeps both in a minified host.
 */
internal class RecordingProvider : ScreenRecorderProvider {

    /** The const is inlined here at compile time: the contract this artifact was built against. */
    override val spiVersion: Int
        get() = ScreenRecorderProvider.SPI_VERSION

    override fun create(context: Context, log: RecorderLog): ScreenRecorder {
        RecorderRuntime.log = log
        return ProjectionRecorder(context.applicationContext, log)
    }
}

/** FeedbackKit's screen recorder (spec §7): manual recording to one file, auto recording in segments. */
internal class ProjectionRecorder(
    private val context: Context,
    private val log: RecorderLog,
    private val segmentMillis: Long = SegmentSink.SEGMENT_MILLIS,
) : ScreenRecorder {

    override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession {
        val session = RecorderRuntime.start(host, SingleFileSink(context, output, maxDurationMillis, log), ManualEvents(output, listener, log), log)
        return RecordingSession { session.stop() }
    }

    override fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession {
        val sink = SegmentSink(context, directory, log, segmentMillis)
        val session = RecorderRuntime.start(host, sink, AutoEvents(listener, log), log)
        return AutoSessionHandle(session, sink, log)
    }
}

private class ManualEvents(private val output: File, private val listener: RecordingListener, private val log: RecorderLog) : SessionEvents {
    override fun started(startedAtMillis: Long) = log.guard("report the started recording") { listener.onStarted(startedAtMillis) }

    override fun notStarted(refused: Boolean) = log.guard("report the recording that did not start") { listener.onNotStarted(refused) }

    override fun finished(reason: RecordingStopReason, usable: Boolean) =
        log.guard("report the finished recording") { listener.onFinished(output.takeIf { usable }, reason) }
}

private class AutoEvents(private val listener: AutoRecordingListener, private val log: RecorderLog) : SessionEvents {
    override fun started(startedAtMillis: Long) = log.guard("report the started auto recording") { listener.onStarted() }

    override fun notStarted(refused: Boolean) = log.guard("report the auto recording that did not start") { listener.onNotStarted(refused) }

    override fun finished(reason: RecordingStopReason, usable: Boolean) = log.guard("report the ended auto recording") { listener.onEnded() }
}

/**
 * One Auto Screen Recording session; pause, resume and clip run in order on the session's thread. A
 * failure there (a recorder that does not start again) goes through [ProjectionSession.post] and
 * ends the session; the listener hears onEnded.
 */
internal class AutoSessionHandle(
    val session: ProjectionSession,
    private val sink: SegmentSink,
    private val log: RecorderLog,
) : AutoRecordingSession {

    /** Tests and logs only: the longest seam between two segments so far, in ms. */
    val longestSeamMillis: Long
        get() = sink.longestSeamMillis

    /** Tests only: recorders started so far, the first segment included. */
    val segmentsBegun: Int
        get() = sink.segmentsBegun

    /** Tests and logs only: the last clip's time to finalise the current segment, and then to stitch, in ms. */
    val lastClipFinaliseMillis: Long
        get() = sink.lastClipFinaliseMillis

    val lastClipStitchMillis: Long
        get() = sink.lastClipStitchMillis

    override fun pause() {
        session.post {
            if (session.state == SessionState.RECORDING) {
                session.detachSurface()
                sink.pause()
            }
        }
    }

    override fun resume(): Boolean {
        if (!session.projectionAlive) return false
        session.post {
            if (session.state == SessionState.RECORDING && session.projectionAlive) sink.resume()?.let(session::attachSurface)
        }
        return true
    }

    override fun clip(output: File, windowMillis: Long, callback: ClipCallback) {
        val posted = session.post {
            var file: File? = null
            try {
                if (clipsIn(session.state)) {
                    session.detachSurface()
                    file = sink.clip(output, windowMillis * 1_000)
                }
            } catch (e: Exception) {
                log.w("Could not make the automatic recording clip", e)
            } finally {
                val result = file
                session.onMain { callback.onClip(result) }
            }
        }
        // The session already ended: there is nothing to clip, but the caller still gets its answer.
        if (!posted) Handler(Looper.getMainLooper()).post { log.guard("deliver an empty clip") { callback.onClip(null) } }
    }

    override fun stop() = session.stop()
}

/**
 * Whether [AutoSessionHandle.clip] makes a clip in [state]. FeedbackKit asks for the clip after it
 * paused the session behind its report (the report counts as open before the screenshot is taken),
 * and that works by design: [AutoSessionHandle.pause] only finalises the sink's current segment and
 * detaches the display, and the session machine knows no paused state, so a paused session is still
 * RECORDING. [SegmentSink.clip] pauses first itself, which is a no-op on a paused sink.
 */
internal fun clipsIn(state: SessionState): Boolean = state == SessionState.RECORDING
