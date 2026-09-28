package io.github.feedbacklib.android.recording.internal

import io.github.feedbacklib.android.spi.RecordingStopReason

internal enum class SessionState { CONSENTING, STARTING, RECORDING, STOPPING, DONE }

/** What ProjectionSession must do next, in order. */
internal sealed interface SessionCommand {
    /** startForegroundService(): the mediaProjection service must run before the projection exists. */
    data object StartForegroundService : SessionCommand

    /** getMediaProjection(), registerCallback(), createVirtualDisplay(), then the recorder. */
    data object StartCapture : SessionCommand

    /** Finalise the recorder, release the display, stop the projection; answered by captureStopped(). */
    data object StopCapture : SessionCommand

    data object ReleaseService : SessionCommand
    data object NotifyStarted : SessionCommand
    data class NotifyNotStarted(val refused: Boolean) : SessionCommand
    data class NotifyFinished(val reason: RecordingStopReason) : SessionCommand
}

/**
 * The order of one MediaProjection session (spec §7), pure so every path is tested on the JVM:
 * consent → foreground service → projection and recorder → stop → release. The projection is asked
 * for only after the service reports it runs in the foreground (Android 14+ throws otherwise). A stop
 * asked for before recording began wins over whatever arrives later. Every event after DONE, and
 * every event that does not fit the state, returns nothing — including a failure or a system stop
 * reported for a projection that was never asked for (a stray callback from a previous session must
 * not abort a session that is still starting).
 *
 * Not thread-safe: every method must be called from the session's own thread (the one
 * `ProjectionSession` runs on), one event at a time, never concurrently. [abortStart] can return both
 * [SessionCommand.StopCapture] and [SessionCommand.ReleaseService] in the same list; the executor must
 * run `StopCapture` to completion before it starts `ReleaseService` — releasing the foreground service
 * while the recorder or the virtual display is still being torn down leaves them attached to a service
 * that is already gone.
 */
internal class SessionMachine {

    var state: SessionState = SessionState.CONSENTING
        private set

    private var cancelled = false
    private var captureOpen = false
    private var notStarted = false
    private var reason = RecordingStopReason.REQUESTED

    fun consentGranted(): List<SessionCommand> {
        if (state != SessionState.CONSENTING) return NONE
        if (cancelled) {
            state = SessionState.DONE
            return listOf(SessionCommand.NotifyNotStarted(refused = false))
        }
        state = SessionState.STARTING
        return listOf(SessionCommand.StartForegroundService)
    }

    fun consentRefused(): List<SessionCommand> = endConsent(refused = true)

    /** The dialog went away unanswered or could not open. */
    fun consentUnavailable(): List<SessionCommand> = endConsent(refused = false)

    fun serviceReady(): List<SessionCommand> {
        if (state != SessionState.STARTING || captureOpen) return NONE
        if (cancelled) return abortStart()
        captureOpen = true
        return listOf(SessionCommand.StartCapture)
    }

    fun serviceFailed(): List<SessionCommand> = if (state == SessionState.STARTING) abortStart() else NONE

    fun captureStarted(): List<SessionCommand> {
        if (state != SessionState.STARTING || !captureOpen) return NONE
        if (cancelled) {
            state = SessionState.STOPPING
            notStarted = true
            return listOf(SessionCommand.StopCapture)
        }
        state = SessionState.RECORDING
        return listOf(SessionCommand.NotifyStarted)
    }

    /** In STARTING this only fits once the projection was actually asked for ([captureOpen]); a failure
     * reported before that (or a stray one from an earlier session) changes nothing. */
    fun captureFailed(): List<SessionCommand> = when (state) {
        SessionState.STARTING -> if (captureOpen) abortStart() else NONE
        SessionState.RECORDING -> stopWith(RecordingStopReason.FAILED)
        else -> NONE
    }

    fun stopRequested(): List<SessionCommand> = when (state) {
        SessionState.CONSENTING, SessionState.STARTING -> {
            cancelled = true
            NONE
        }
        SessionState.RECORDING -> stopWith(RecordingStopReason.REQUESTED)
        else -> NONE
    }

    fun limitReached(): List<SessionCommand> = if (state == SessionState.RECORDING) stopWith(RecordingStopReason.LIMIT) else NONE

    /**
     * MediaProjection.Callback.onStop: the shade, the status bar chip, a lock, another app's
     * projection. Same guard as [captureFailed]: in STARTING this only fits once the projection was
     * actually asked for ([captureOpen]).
     */
    fun projectionStopped(): List<SessionCommand> = when (state) {
        SessionState.STARTING -> if (captureOpen) abortStart() else NONE
        SessionState.RECORDING -> stopWith(RecordingStopReason.SYSTEM)
        else -> NONE
    }

    fun captureStopped(usable: Boolean): List<SessionCommand> {
        if (state != SessionState.STOPPING) return NONE
        state = SessionState.DONE
        captureOpen = false
        val commands = mutableListOf<SessionCommand>(SessionCommand.ReleaseService)
        commands += if (notStarted) {
            SessionCommand.NotifyNotStarted(refused = false)
        } else {
            SessionCommand.NotifyFinished(if (usable) reason else RecordingStopReason.FAILED)
        }
        return commands
    }

    private fun endConsent(refused: Boolean): List<SessionCommand> {
        if (state != SessionState.CONSENTING) return NONE
        state = SessionState.DONE
        return listOf(SessionCommand.NotifyNotStarted(refused))
    }

    private fun stopWith(stopReason: RecordingStopReason): List<SessionCommand> {
        state = SessionState.STOPPING
        reason = stopReason
        return listOf(SessionCommand.StopCapture)
    }

    /**
     * Reached only once [consentGranted] has moved to STARTING, so the foreground service is always
     * held here — [SessionCommand.ReleaseService] is unconditional. [SessionCommand.StopCapture] is
     * added first, only when the projection was actually asked for ([captureOpen]); the executor runs
     * it to completion before [SessionCommand.ReleaseService] (see the class doc).
     */
    private fun abortStart(): List<SessionCommand> {
        val commands = mutableListOf<SessionCommand>()
        if (captureOpen) {
            captureOpen = false
            commands += SessionCommand.StopCapture
        }
        commands += SessionCommand.ReleaseService
        state = SessionState.DONE
        commands += SessionCommand.NotifyNotStarted(refused = false)
        return commands
    }

    private companion object {
        val NONE: List<SessionCommand> = emptyList()
    }
}
