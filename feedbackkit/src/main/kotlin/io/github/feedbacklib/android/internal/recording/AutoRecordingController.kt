package io.github.feedbacklib.android.internal.recording

import android.app.Activity
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.ActivityTracker
import io.github.feedbacklib.android.internal.invoke.AutoClipSource
import io.github.feedbacklib.android.spi.AutoRecordingListener
import io.github.feedbacklib.android.spi.AutoRecordingSession
import io.github.feedbacklib.android.spi.ScreenRecorder
import java.io.File

/**
 * Auto Screen Recording in the SDK (spec §7): feeds [AutoRecordingPolicy] from the activity tracker and
 * the report screen, carries its commands out on the recorder, and hands an invocation the last 30 s.
 * Main thread. Answers from a session already stopped or replaced are ignored. Nothing here throws
 * into the host.
 *
 * [segmentsDir] and [newClipFile] are paths only, called just before the recorder gets them: no disk
 * I/O on the main thread (the directory under them was resolved on the startup pass).
 */
internal class AutoRecordingController(
    private val recorder: () -> ScreenRecorder?,
    /** Whether the recorder lookup ran: before it, a missing recorder is no reason to warn. */
    private val recorderKnown: () -> Boolean,
    private val tracker: ActivityTracker,
    private val segmentsDir: () -> File,
    private val newClipFile: () -> File,
    private val logger: SdkLogger,
    private val policy: AutoRecordingPolicy = AutoRecordingPolicy(),
) : ActivityTracker.Listener, AutoClipSource {

    private var session: AutoRecordingSession? = null
    private var warnedMissing = false

    /**
     * Whether the host wants it and FeedbackKit and bug reporting are on; called with every settings
     * change. Without the artifact: a warning, once per switch-on, once the lookup has run.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && recorder() == null) {
            if (recorderKnown() && !warnedMissing) {
                warnedMissing = true
                logger.w("setAutoScreenRecordingEnabled(true) has no effect: add the feedbackkit-recording artifact")
            }
            perform(policy.setEnabled(false))
            return
        }
        if (!enabled) warnedMissing = false
        if (enabled && policy.phase == AutoPhase.OFF) {
            // What the tracker saw while this was off: the policy asks at once over a screen already shown.
            policy.onForeground(tracker.isForeground)
            policy.onHost(resumed = tracker.currentActivity != null)
        }
        perform(policy.setEnabled(enabled))
    }

    /** FeedbackKit's screen opened (or stepped aside) and closed again. */
    fun onUiOpenChanged(open: Boolean) = perform(policy.onUiOpen(open))

    /**
     * A manual recording is about to ask for its own projection (spec §7): one projection at a time,
     * and a paused session still holds its own, so the session stops. The next consent waits for the
     * next foreground.
     */
    fun onManualRecording() {
        perform(policy.onEnded())
        stopSession()
    }

    override fun onForegroundChanged(foreground: Boolean) = perform(policy.onForeground(foreground))

    override fun onHostActivityResumed(activity: Activity) = perform(policy.onHost(resumed = true))

    override fun onHostActivityPaused(activity: Activity) = perform(policy.onHost(resumed = tracker.currentActivity != null))

    override fun clip(callback: (File?) -> Unit): Boolean {
        val current = session ?: return false
        if (policy.phase != AutoPhase.RECORDING && policy.phase != AutoPhase.PAUSED) return false
        return try {
            current.clip(newClipFile(), RecordingLimits.AUTO_CLIP_WINDOW_MILLIS) { file -> callback(file) }
            true
        } catch (e: Exception) {
            logger.e("Could not ask for the automatic screen recording", e)
            false
        }
    }

    private fun perform(commands: List<AutoCommand>) = commands.forEach(::execute)

    private fun execute(command: AutoCommand) {
        try {
            when (command) {
                AutoCommand.ASK -> ask()
                AutoCommand.PAUSE -> session?.pause()
                AutoCommand.RESUME -> resume()
                AutoCommand.STOP -> stopSession()
            }
        } catch (e: Exception) {
            logger.e("Automatic screen recording failed", e)
        }
    }

    private fun ask() {
        val host = tracker.currentActivity
        val screenRecorder = recorder()
        if (host == null || screenRecorder == null) {
            perform(policy.onAskFailed())
            return
        }
        lateinit var owner: AutoRecordingSession
        val listener = object : AutoRecordingListener {
            override fun onStarted() = ifCurrent(owner) { perform(policy.onStarted()) }

            override fun onNotStarted(refused: Boolean) = ifCurrent(owner) {
                session = null
                if (refused) logger.i("Automatic screen recording was declined; it is not asked again until the app restarts")
                perform(policy.onNotStarted(refused))
            }

            override fun onEnded() = ifCurrent(owner) {
                session = null
                perform(policy.onEnded())
            }
        }
        try {
            owner = screenRecorder.startAuto(host, segmentsDir(), listener)
            session = owner
        } catch (e: Exception) {
            logger.e("Could not start the automatic screen recording", e)
            perform(policy.onNotStarted(refused = false))
        }
    }

    private fun resume() {
        val current = session
        if (current != null && current.resume()) return
        session = null
        current?.stop()
        perform(policy.onResumeFailed())
    }

    private fun stopSession() {
        val current = session
        session = null
        current?.stop()
    }

    private inline fun ifCurrent(owner: AutoRecordingSession, block: () -> Unit) {
        // Before startAuto returned (the SPI never answers inside it) owner is not set yet: not current.
        if (session == null || session !== owner) return
        try {
            block()
        } catch (e: Exception) {
            logger.e("Automatic screen recording failed", e)
        }
    }
}
