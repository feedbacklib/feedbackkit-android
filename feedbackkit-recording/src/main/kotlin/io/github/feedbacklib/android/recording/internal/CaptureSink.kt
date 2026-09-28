package io.github.feedbacklib.android.recording.internal

import android.view.Surface

/** What a sink's MediaRecorder reports; called on the session thread. */
internal interface SinkEvents {
    fun onLimitReached()
    fun onError(what: Int, extra: Int)

    /** The sink is about to finalise its MediaRecorder: the virtual display stops drawing into it first. */
    fun detachSurface()

    /** The sink moved to a new MediaRecorder: the virtual display must draw into [surface] now. */
    fun onSurfaceChanged(surface: Surface)
}

/**
 * Where the virtual display's frames are encoded. Session thread only.
 *
 * A static or slowly changing screen gives few frames: a VirtualDisplay emits one only when its
 * content changes, and MediaRecorder never repeats the previous frame. An MP4's duration runs from its
 * first frame to its last, so the video can be shorter than the time recorded.
 */
internal interface CaptureSink {
    /**
     * How long the session lets this sink record, by wall clock ([RecordingLimit]); 0 for no limit.
     * The session then stops with RecordingStopReason.LIMIT.
     */
    val limitMillis: Long

    /** Prepares an encoder for [video] and returns the surface the display draws into. */
    fun prepare(video: VideoSpec, events: SinkEvents): Surface

    /** Starts encoding. */
    fun start()

    /** Finalises what was recorded; whether usable video came out. Idempotent; false before [prepare]. */
    fun stop(): Boolean

    /**
     * Deletes whatever [stop] kept: the session ends as "not started" (a stop asked for while it was
     * starting, a start that failed half-way), so nobody takes the output. Idempotent; does nothing
     * before [prepare].
     */
    fun discard()
}
