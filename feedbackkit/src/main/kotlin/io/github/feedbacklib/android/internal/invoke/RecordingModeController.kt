package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.recording.RecordingLimits
import io.github.feedbacklib.android.spi.RecordingListener
import io.github.feedbacklib.android.spi.RecordingSession
import io.github.feedbacklib.android.spi.RecordingStopReason
import io.github.feedbacklib.android.spi.ScreenRecorder
import java.io.File

/**
 * Recording mode of [InvocationCoordinator] (spec §7, manual recording): from the consent over the
 * report screen until the report reopens with the video, the report stays open and invocations are
 * ignored; once recording, the report screen has stepped aside and [overlay] shows Stop with a timer
 * on every resumed host screen. A recording that ends while no host screen is resumed comes back on
 * the next resume. A manual recording first ends Auto Screen Recording ([stopAutoRecording]): one
 * MediaProjection at a time, which the recorder does not enforce.
 *
 * The coordinator keeps the one "report open" flag, capture mode and the relaunch of a report; this
 * class reaches them through [Owner]. Main thread. Files are never deleted here: [deleteFile] takes
 * them off the main thread.
 */
internal class RecordingModeController(
    private val tracker: ActivityTracker,
    /** The optional screen recorder (spec §3); null while unknown or absent. */
    private val recorder: () -> ScreenRecorder?,
    private val overlay: RecordingOverlayHost,
    /** Where a manual recording is written before it joins its draft; a path only, no I/O. */
    private val newRecordingFile: () -> File,
    /** A report that stepped aside for a recording and will not come back: dropped, the host hears a cancel. */
    private val onAbandoned: (CaptureRequest) -> Unit,
    /** Ends Auto Screen Recording, if it runs, before a manual recording asks for its own projection. */
    private val stopAutoRecording: () -> Unit,
    private val deleteFile: (File) -> Unit,
    private val logger: SdkLogger,
    private val owner: Owner,
) {
    /** What recording mode needs from [InvocationCoordinator]. Main thread. */
    interface Owner {
        /** Sets [InvocationCoordinator.isUiOpen]. */
        fun setUiOpen(open: Boolean)

        /** The detectors follow the mode (the coordinator's onCaptureModeChanged, guarded). */
        fun modeChanged()

        /** Reopens [request]'s report on [activity]; throws what the launcher throws. */
        fun launchResumed(activity: Activity, request: CaptureRequest, resume: ResumeRequest)

        /** Runs [block] on the main thread after the current callbacks. */
        fun post(block: () -> Unit)
    }

    /** A manual recording from the consent until its report reopens (spec §7). Main thread only. */
    private class Recording(
        val request: CaptureRequest,
        val onStarted: () -> Unit,
        val onNotStarted: (Boolean) -> Unit,
    ) {
        var session: RecordingSession? = null
        var started = false
        var startedAt = 0L

        /** The report screen closed with ADD_ATTACHMENT: it comes back only through this mode. */
        var steppedAside = false
        var stopping = false
        var finished = false
        var result: File? = null
    }

    // Main thread only.
    private var recording: Recording? = null

    // Main thread only: a report FeedbackKit.disable() dropped after its recording started but before
    // its screen closed with ADD_ATTACHMENT; handed back for a cancel once that close is reported.
    private var abandonedBeforeStepAside: CaptureRequest? = null

    /** Whether a manual recording runs or waits to return to its report. */
    val isRecording: Boolean
        get() = recording != null

    /** `SystemClock.elapsedRealtime()` of the recording's first frame; 0 before it. */
    val startedAt: Long
        get() = recording?.startedAt ?: 0L

    /**
     * "Record screen" in the report (spec §7): the recorder asks the consent over [host] — the report
     * screen itself. [onStarted] once frames are written (the screen then closes with ADD_ATTACHMENT),
     * [onNotStarted] when declined or failed (the screen stays). False, with nothing started, when
     * there is no recorder or recording mode already runs; the coordinator checks the SDK and capture
     * mode first. Auto Screen Recording ends first: its projection and this one must never run together.
     */
    fun begin(host: Activity, request: CaptureRequest, onStarted: () -> Unit, onNotStarted: (refused: Boolean) -> Unit): Boolean {
        if (recording != null) return false
        val screenRecorder = recorder() ?: return false
        val mode = Recording(request, onStarted, onNotStarted)
        recording = mode
        abandonedBeforeStepAside = null
        owner.setUiOpen(true)
        owner.modeChanged()
        try {
            stopAutoRecording()
        } catch (e: Exception) {
            logger.e("Could not stop Auto Screen Recording before the screen recording", e)
        }
        mode.session = try {
            screenRecorder.record(host, newRecordingFile(), RecordingLimits.MAX_MANUAL_RECORDING_MILLIS, listenerFor(mode))
        } catch (e: Exception) {
            logger.e("Could not start the screen recording", e)
            if (recording === mode) {
                recording = null
                owner.modeChanged()
            }
            return false
        }
        // The SPI never answers inside record(); should a recorder do it anyway, the mode has already ended.
        return recording === mode
    }

    /** "Stop" on [activity]. A second tap, or one after the end, does nothing. */
    fun onStopTapped(activity: Activity) {
        val mode = recording ?: return
        if (!mode.started || mode.finished || mode.stopping) return
        mode.stopping = true
        logger.d("Screen recording stopped on ${activity.javaClass.name}")
        stopQuietly(mode)
    }

    /**
     * FeedbackKit.disable() in recording mode: the recording stops and its file goes. The report is
     * handed back for cleanup only if it already stepped aside. One still open closes itself — except
     * one whose recording already started: it is closing with ADD_ATTACHMENT, which no longer cancels
     * anything, so it goes to [onAbandoned] once that close is reported. A report screen still waiting
     * for the consent hears onNotStarted(refused = false) here, as the recorder's own answer is dropped
     * once the mode is gone.
     */
    fun abandon(): CaptureRequest? {
        val mode = recording ?: return null
        recording = null
        overlay.hideAll()
        owner.modeChanged()
        stopQuietly(mode)
        mode.result?.let(::deleteQuietly)
        if (!mode.steppedAside) {
            if (mode.started) {
                abandonedBeforeStepAside = mode.request
            } else {
                try {
                    mode.onNotStarted(false)
                } catch (e: Exception) {
                    logger.e("Could not tell the report that its recording was dropped", e)
                }
            }
            return null
        }
        owner.setUiOpen(false)
        return mode.request
    }

    /**
     * The report screen closed with [dismissType]; true when the report stays open — it stepped aside
     * for the recording that started, or for capture mode (no recording at all). In multi-window the
     * screen reopened from the mode can open before the one that stepped aside is gone, so that late
     * close must not free it either. A report screen closed any other way during the consent, or
     * before it stepped aside, takes the recording with it; false then, and the coordinator decides.
     */
    fun onUiClosed(dismissType: DismissType): Boolean {
        val abandoned = abandonedBeforeStepAside
        abandonedBeforeStepAside = null
        val mode = recording
        if (dismissType == DismissType.ADD_ATTACHMENT) {
            if (abandoned != null) {
                // Posted: the host hears this screen's ADD_ATTACHMENT first, then the cancel.
                owner.post { handOverAbandoned(abandoned) }
                return false
            }
            if (mode == null || mode.started) {
                // The report screen stepped aside for the recording that started (or for capture mode).
                if (mode != null) {
                    mode.steppedAside = true
                    // Ended before the close (the system stopped it): back at once on a host already on screen.
                    if (mode.finished) tracker.currentActivity?.let { reopen(it, mode) }
                }
                return true
            }
            // Only a started recording steps the report aside: this screen is simply gone, as with any other close.
            logger.w("The report screen closed with ADD_ATTACHMENT before its recording started; the recording is dropped")
        }
        if (mode != null && !mode.steppedAside) {
            // Closed during the consent or right after the recording began: there is nothing to return to.
            recording = null
            overlay.hideAll()
            owner.modeChanged()
            stopQuietly(mode)
            mode.result?.let(::deleteQuietly)
        }
        return false
    }

    /**
     * A host screen is on screen (resumed, or current again in multi-window): Stop goes on it while
     * recording, and a finished recording reopens its report there. False when there is no recording.
     */
    fun onHostShown(activity: Activity): Boolean {
        val mode = recording ?: return false
        when {
            mode.finished && mode.steppedAside -> reopen(activity, mode)
            mode.started && !mode.finished -> overlay.show(activity)
        }
        return true
    }

    fun onHostPaused(activity: Activity) = overlay.hide(activity)

    private fun listenerFor(mode: Recording) = object : RecordingListener {
        override fun onStarted(startedAtMillis: Long) = guarded { onRecordingStarted(mode, startedAtMillis) }
        override fun onNotStarted(refused: Boolean) = guarded { onRecordingNotStarted(mode, refused) }
        override fun onFinished(file: File?, reason: RecordingStopReason) = guarded { onRecordingFinished(mode, file, reason) }
    }

    private fun onRecordingStarted(mode: Recording, startedAtMillis: Long) {
        if (recording !== mode) {
            // The mode ended while the user was deciding: its onFinished deletes the file.
            stopQuietly(mode)
            return
        }
        mode.started = true
        mode.startedAt = startedAtMillis
        tracker.resumedActivities.forEach(overlay::show)
        try {
            mode.onStarted()
        } catch (e: Exception) {
            logger.e("Could not step the report aside for the recording", e)
        }
    }

    private fun onRecordingNotStarted(mode: Recording, refused: Boolean) {
        if (recording !== mode) return
        recording = null
        owner.modeChanged()
        // isUiOpen stays: the report screen is still open and reports its own close.
        try {
            mode.onNotStarted(refused)
        } catch (e: Exception) {
            logger.e("Could not return to the report after the recording did not start", e)
        }
    }

    private fun onRecordingFinished(mode: Recording, file: File?, reason: RecordingStopReason) {
        if (recording !== mode) {
            file?.let(::deleteQuietly) // abandoned: nobody will attach it
            return
        }
        logger.d("Screen recording finished ($reason)")
        mode.finished = true
        mode.result = file
        overlay.hideAll()
        // Before the report stepped aside (stopped at once), or with no host screen resumed
        // (background): it comes back on the next resumed host screen.
        if (!mode.steppedAside) return
        tracker.currentActivity?.let { reopen(it, mode) }
    }

    private fun reopen(activity: Activity, mode: Recording) {
        recording = null
        overlay.hideAll()
        owner.modeChanged()
        val request = mode.request
        try {
            owner.launchResumed(activity, request, ResumeRequest(request.state, null, extraScreenshotFailed = false, recording = mode.result, recordingFailed = mode.result == null))
        } catch (e: Exception) {
            logger.e("Could not reopen the report after the screen recording", e)
            mode.result?.let(::deleteQuietly)
            owner.setUiOpen(false)
            handOverAbandoned(request)
        }
    }

    private fun handOverAbandoned(request: CaptureRequest) {
        try {
            onAbandoned(request)
        } catch (e: Exception) {
            logger.e("Could not drop the report of a lost recording", e)
        }
    }

    private fun stopQuietly(mode: Recording) {
        try {
            mode.session?.stop()
        } catch (e: Exception) {
            logger.e("Could not stop the screen recording", e)
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            deleteFile(file)
        } catch (e: Exception) {
            logger.w("Could not delete an abandoned recording", e)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.e("Screen recording handling failed", e)
        }
    }
}
