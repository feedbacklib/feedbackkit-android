package io.github.feedbacklib.android.spi

import android.app.Activity
import android.content.Context
import androidx.annotation.RestrictTo
import io.github.feedbacklib.android.LogLevel
import java.io.File

/**
 * Where `feedbackkit-recording` hands FeedbackKit its recorder (spec §3): listed in
 * `META-INF/services/io.github.feedbacklib.android.spi.ScreenRecorderProvider` and found with
 * ServiceLoader. Not for apps.
 */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface ScreenRecorderProvider {

    /** The [SPI_VERSION] the implementation was compiled against; FeedbackKit ignores any other. */
    public val spiVersion: Int

    /** Called once, off the main thread; [log] goes to FeedbackKit's logcat output at its level. */
    public fun create(context: Context, log: RecorderLog): ScreenRecorder

    public companion object {
        /** Raised with every incompatible change of this package. */
        public const val SPI_VERSION: Int = 1
    }
}

/** FeedbackKit's logger for the recorder; never throws. Not for apps. */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun interface RecorderLog {
    public fun log(level: LogLevel, message: String, error: Throwable?)
}

/**
 * Screen recording through MediaProjection (spec §7). Every call on the main thread; every listener
 * and callback is called on the main thread, and exactly one final call ends each session.
 * Not for apps.
 */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface ScreenRecorder {

    /**
     * Manual recording: asks the user's consent over [host] (every time), then records the whole
     * display into [output], video only, until [RecordingSession.stop], [maxDurationMillis] or the
     * system stops the projection. [listener] gets onStarted and then onFinished, or only onNotStarted —
     * always later, never synchronously inside this call.
     */
    public fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession

    /**
     * Auto Screen Recording [beta]: asks the consent over [host], then records rolling segments into
     * [directory] (it owns the directory and clears it first). [listener] gets onStarted and later
     * onEnded, or only onNotStarted.
     */
    public fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession
}

/** Why a manual recording ended. Not for apps. */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class RecordingStopReason { REQUESTED, LIMIT, SYSTEM, FAILED }

/** Events of a manual recording, on the main thread. Not for apps. */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface RecordingListener {
    /** Consent given; frames are being written. [startedAtMillis] is `SystemClock.elapsedRealtime()`. */
    public fun onStarted(startedAtMillis: Long)

    /** Declined ([refused]) or could not start; nothing was written. */
    public fun onNotStarted(refused: Boolean)

    /** Finished and finalised; [file] is null when nothing usable was recorded. */
    public fun onFinished(file: File?, reason: RecordingStopReason)
}

/** A manual recording in progress. Not for apps. */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun interface RecordingSession {
    /** Idempotent; also ends a session still waiting for consent. */
    public fun stop()
}

/** Events of Auto Screen Recording, on the main thread. Not for apps. */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface AutoRecordingListener {
    public fun onStarted()

    /** Declined ([refused]) or could not start. */
    public fun onNotStarted(refused: Boolean)

    /** The projection ended (stopped from the system, revoked, failed); the segments are gone. */
    public fun onEnded()
}

/** Receives the clip of [AutoRecordingSession.clip], on the main thread. Not for apps. */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun interface ClipCallback {
    public fun onClip(file: File?)
}

/** An Auto Screen Recording session: rolling segments while the app is in the foreground. Not for apps. */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface AutoRecordingSession {
    /** The app went to background or opened FeedbackKit: the current segment is finalised, the projection kept. */
    public fun pause()

    /** Records on; false when the projection is gone and a new consent is needed. */
    public fun resume(): Boolean

    /** Writes the last [windowMillis] into [output] and leaves the session paused; [callback] gets null when nothing usable. */
    public fun clip(output: File, windowMillis: Long, callback: ClipCallback)

    /** Ends the session and deletes its segments. Idempotent. */
    public fun stop()
}
