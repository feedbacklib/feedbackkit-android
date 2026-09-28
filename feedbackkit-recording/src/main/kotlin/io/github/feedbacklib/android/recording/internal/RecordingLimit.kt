package io.github.feedbacklib.android.recording.internal

/** Delayed work on the session thread; a Handler on the device, a fake in JVM tests. */
internal interface DelayedRunner {
    fun postDelayed(action: Runnable, delayMillis: Long)
    fun remove(action: Runnable)
}

/**
 * The session's own limit on a recording's length, by wall clock (spec §7: 60 s for manual
 * recording). MediaRecorder's setMaxDuration counts media time, which on a static screen or a slow
 * encoder runs far behind the clock (a VirtualDisplay emits frames only on change), so it alone lets a
 * recording overrun; it stays as a second guard, and whichever fires first stops the session — the
 * machine ignores the second limitReached.
 *
 * Armed on NotifyStarted, cancelled by every command that ends the recording (StopCapture, and the
 * notifications that end the session), so a requested, system, failed or aborted stop leaves no timer
 * behind. [onLimit] runs on [runner]'s thread, the session thread; not thread-safe otherwise.
 * [limitMillis] ≤ 0 means no limit (auto recording).
 */
internal class RecordingLimit(
    private val limitMillis: Long,
    private val runner: DelayedRunner,
    private val onLimit: () -> Unit,
) {
    private var armed = false

    private val fire = Runnable {
        if (armed) {
            armed = false
            onLimit()
        }
    }

    /** Called with each session command before it is carried out. */
    fun follow(command: SessionCommand) {
        when (command) {
            SessionCommand.NotifyStarted -> arm()
            SessionCommand.StopCapture, is SessionCommand.NotifyNotStarted, is SessionCommand.NotifyFinished -> cancel()
            else -> Unit
        }
    }

    fun cancel() {
        if (!armed) return
        armed = false
        runner.remove(fire)
    }

    private fun arm() {
        if (limitMillis <= 0 || armed) return
        armed = true
        runner.postDelayed(fire, limitMillis)
    }
}
