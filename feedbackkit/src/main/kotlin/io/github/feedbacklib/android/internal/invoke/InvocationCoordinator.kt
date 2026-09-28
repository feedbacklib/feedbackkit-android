package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.os.Handler
import android.os.Looper
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.recording.PendingClips
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.spi.ScreenRecorder
import java.io.File

internal enum class InvocationSource { SHAKE, SCREENSHOT, FLOATING_BUTTON, TWO_FINGER_SWIPE, MANUAL, PROACTIVE }

internal data class LaunchRequest(
    val screenshot: File?,
    val screenshotSecure: Boolean,
    val currentScreen: String,
    val source: InvocationSource,
    val reportType: ReportType? = null,
    val screenshotRequested: Boolean = true,
    /** Set when the report reopens after capture mode (spec §6). */
    val resume: ResumeRequest? = null,
    /** Auto Screen Recording's last seconds (spec §7), on their way through [PendingClips]. */
    val autoRecordingToken: String? = null,
    /**
     * Proactive reporting's prompt (spec §8): what happened to the previous run; the type is then FRUSTRATING_EXPERIENCE.
     * See [io.github.feedbacklib.android.internal.ui.FeedbackLaunchArgs.proactive] for what `detectedAt` holds.
     */
    val proactive: ProactiveInfo? = null,
)

internal fun interface ScreenCapture {
    fun capture(activity: Activity, callback: (ScreenCapturer.Result) -> Unit)
}

/** Auto Screen Recording's clip for an invocation (spec §7); a seam for tests. Main thread. */
internal fun interface AutoClipSource {
    /** Starts a clip and returns true; [callback] then gets the file, or null, once, on the main thread. False when there is none to make. */
    fun clip(callback: (File?) -> Unit): Boolean

    companion object {
        val NONE: AutoClipSource = AutoClipSource { false }
    }
}

/**
 * The single entry point every invocation goes through (spec §5): one SDK screen at a time,
 * screenshot first, then the host's onInvoke callback, then the SDK screen. Main thread.
 *
 * Registered as a tracker listener: a host screen resuming while [isUiOpen] is set, with no SDK
 * activity alive and no capture in flight, means the SDK screen never opened (Android 10+ drops a
 * start from the background silently) or went away without finishing, so the flag is cleared there.
 *
 * Capture mode (spec §6, extra screenshot): the report screen closed so the user can capture another
 * screen of the app. The report stays "open" — [isUiOpen] is kept and every invocation ignored —
 * while [overlay] shows Capture / Cancel on every resumed host screen (several in multi-window).
 * Either reopens the report; Capture takes the screenshot of the window whose Capture was tapped.
 * Capture mode ends only through Capture, Cancel or [abandonExtraCapture] (FeedbackKit.disable()):
 * with the SDK enabled but bug reporting switched off, the mode stays and both buttons still
 * reopen the report that is already open — only new invocations are refused.
 *
 * Recording mode (spec §7, manual recording) lives in [RecordingModeController]: from the consent over
 * the report screen until the report reopens with the video, the report stays open and invocations
 * are ignored, like in capture mode. The two modes exclude each other.
 *
 * Auto Screen Recording (spec §7): an invocation asks [autoClip] for the last seconds and opens the
 * report at once with a token of [autoClips]; the clip follows when the recorder has stitched it,
 * or never — after [clipCapMillis] the report stops waiting and a clip that comes later is deleted.
 * Every change of [isUiOpen] goes to [onUiOpenChanged]: the recording pauses behind the report.
 *
 * Files are never deleted here: [deleteFile] takes them off the main thread.
 */
internal class InvocationCoordinator(
    private val tracker: ActivityTracker,
    private val capture: ScreenCapture,
    private val isEnabled: () -> Boolean,
    private val onInvoke: () -> Unit,
    private val launcher: (Activity, LaunchRequest) -> Unit,
    private val logger: SdkLogger,
    private val deleteFile: (File) -> Unit,
    private val takeScreenshot: () -> Boolean = { true },
    private val overlay: CaptureOverlayHost = CaptureOverlayHost.NONE,
    private val extraCapture: ScreenCapture = capture,
    private val onCaptureModeChanged: () -> Unit = {},
    /**
     * Whether a report already open may step aside for an extra screenshot: the SDK is on. Not
     * [isEnabled] — that is a new invocation, which bug reporting switched off refuses.
     */
    private val canCapture: () -> Boolean = isEnabled,
    /** Capture mode dropped for a report screen the system restored; its saved state is spent. */
    private val onCaptureDropped: (CaptureRequest) -> Unit = {},
    /** The optional screen recorder (spec §3); null while unknown or absent. */
    private val recorder: () -> ScreenRecorder? = { null },
    private val recordingOverlay: RecordingOverlayHost = RecordingOverlayHost.NONE,
    /** Where a manual recording is written before it joins its draft; a path only, no I/O. */
    private val newRecordingFile: () -> File = { File("recording.mp4") },
    /** A report that stepped aside for a recording and will not come back: dropped, the host hears a cancel. */
    private val onRecordingAbandoned: (CaptureRequest) -> Unit = {},
    /** Ends Auto Screen Recording, if it runs, before a manual recording asks for its own projection. */
    private val stopAutoRecording: () -> Unit = {},
    /** Auto Screen Recording's last seconds for each invocation (spec §7). */
    private val autoClip: AutoClipSource = AutoClipSource.NONE,
    /** Every change of [isUiOpen]: auto recording pauses while FeedbackKit's screen is open or stepped aside. */
    private val onUiOpenChanged: (Boolean) -> Unit = {},
    /** Where an invocation's clip travels to its report, which opens without waiting for it. */
    private val autoClips: PendingClips = PendingClips(deleteFile, logger),
    private val clipCapMillis: Long = CLIP_CAP_MILLIS,
) : ActivityTracker.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isUiOpen: Boolean = false
        private set(value) {
            if (field == value) return
            field = value
            try {
                onUiOpenChanged(value)
            } catch (e: Exception) {
                logger.e("Could not apply the report screen state", e)
            }
        }

    // Main thread only: the clip token of the last report launched, until its screen opens and so owns
    // it. A launch that never opened a screen (the watchdog in onHostActivityResumed) gives it up.
    private var unclaimedClipToken: String? = null

    // Main thread only: set from an invocation's capture request until its result arrives. The
    // extra screenshot never touches it (it has extraAttempt), so a late extra result can never
    // release the watchdog in onHostActivityResumed while an invocation's capture runs.
    private var captureInFlight = false

    // Main thread only: the report waiting in capture mode.
    private var captureMode: CaptureRequest? = null

    // Main thread only: the extra screenshot in flight, if any. Its callback acts only while it is
    // still this attempt; abandoning capture mode forgets it, so a late result is just deleted.
    private var extraAttempt: ExtraAttempt? = null

    /** One tap on Capture: the window it captures, and whether that window paused meanwhile. */
    private class ExtraAttempt(val activity: Activity) {
        var hostLeft = false
    }

    private val recordingMode = RecordingModeController(
        tracker = tracker,
        recorder = recorder,
        overlay = recordingOverlay,
        newRecordingFile = newRecordingFile,
        onAbandoned = onRecordingAbandoned,
        stopAutoRecording = stopAutoRecording,
        deleteFile = deleteFile,
        logger = logger,
        owner = object : RecordingModeController.Owner {
            override fun setUiOpen(open: Boolean) {
                isUiOpen = open
            }

            override fun modeChanged() = this@InvocationCoordinator.modeChanged()

            override fun launchResumed(activity: Activity, request: CaptureRequest, resume: ResumeRequest) =
                this@InvocationCoordinator.launchResumed(activity, request, resume)

            override fun post(block: () -> Unit) {
                mainHandler.post { block() }
            }
        },
    )

    /** Whether a manual recording runs or waits to return to its report. Main thread. */
    val isRecording: Boolean
        get() = recordingMode.isRecording

    /** The report stepped aside for a screenshot or a recording: detectors stay silent. Main thread. */
    val isSteppedAside: Boolean
        get() = captureMode != null || recordingMode.isRecording

    /** `SystemClock.elapsedRealtime()` of the recording's first frame; 0 before it. Main thread. */
    val recordingStartedAt: Long
        get() = recordingMode.startedAt

    /** Whether a report waits for an extra screenshot, with the controls over the host. Main thread. */
    val isCapturing: Boolean
        get() = captureMode != null

    fun invoke(source: InvocationSource, reportType: ReportType? = null) {
        mainHandler.post { invokeOnMain(source, reportType) }
    }

    /**
     * Proactive reporting's prompt (spec §8) over the current host screen: the report screen opens on
     * its prompt, without a screenshot, an Auto Screen Recording clip or the host's onInvoke — the user
     * asked for nothing. False, with nothing opened, while bug reporting or the SDK is off, an SDK
     * screen is open or stepped aside, or no host screen is resumed. Main thread.
     */
    fun showProactive(info: ProactiveInfo): Boolean {
        if (!isEnabled() || isUiOpen) return false
        val activity = tracker.currentActivity ?: return false
        isUiOpen = true
        return try {
            launcher(
                activity,
                LaunchRequest(
                    screenshot = null,
                    screenshotSecure = false,
                    currentScreen = activity.javaClass.name,
                    source = InvocationSource.PROACTIVE,
                    reportType = ReportType.FRUSTRATING_EXPERIENCE,
                    screenshotRequested = false,
                    proactive = info,
                ),
            )
            true
        } catch (e: Exception) {
            isUiOpen = false
            logger.e("Could not open the proactive reporting prompt", e)
            false
        }
    }

    /**
     * The SDK screen was created. Already set when an invocation launched it; a screen the system
     * recreated after process death was launched by nobody, and without this a second one could
     * open beside it in multi-window. The screen's one close report clears it either way.
     */
    fun onUiOpened() {
        isUiOpen = true
        unclaimedClipToken = null // the screen's ViewModel awaits it now
        // A report screen the system restored while capture mode came back from a previous process
        // (restoreExtraCapture ran first): the open screen wins, as it does in the other order.
        // The report's own reopen clears captureMode before it launches, so it never comes here.
        val dropped = captureMode ?: return
        logger.d("A report screen opened in capture mode; the saved screenshot capture mode is dropped")
        captureMode = null
        extraAttempt = null
        overlay.hideAll()
        modeChanged()
        try {
            onCaptureDropped(dropped)
        } catch (e: Exception) {
            logger.e("Could not clear the dropped screenshot capture mode", e)
        }
    }

    /**
     * The SDK screen closed with [dismissType]. The close that starts capture mode
     * ([DismissType.ADD_ATTACHMENT]) keeps the report open: the report lives on in capture mode and
     * later in the screen reopened from it, which reports its own close. In multi-window that
     * reopened screen can open before the one that stepped aside is gone, so that late close must
     * not free it either. A report screen closed any other way during the recording's consent, or
     * before it stepped aside, takes the recording with it.
     */
    fun onUiClosed(dismissType: DismissType) {
        if (recordingMode.onUiClosed(dismissType)) return
        if (captureMode == null && !recordingMode.isRecording) isUiOpen = false
    }

    /**
     * The report screen is closing so the user can capture another screen (spec §6): invocations
     * stay ignored, detectors stop and the controls show on every resumed host screen (several in
     * multi-window), and on each one that resumes later, until Capture or Cancel reopens the
     * report. The screen's own close that follows ([DismissType.ADD_ATTACHMENT]) keeps the report
     * open. False while the SDK is off ([canCapture]) or capture mode already runs; bug reporting
     * switched off does not refuse it, as the report is already open.
     */
    fun beginExtraCapture(request: CaptureRequest): Boolean {
        if (!canCapture() || captureMode != null || recordingMode.isRecording) return false
        captureMode = request
        isUiOpen = true
        modeChanged()
        tracker.resumedActivities.forEach(overlay::show)
        return true
    }

    /** Capture mode a previous process left behind; refused while a report screen is open. */
    fun restoreExtraCapture(request: CaptureRequest): Boolean {
        if (isUiOpen || tracker.hasLiveSdkActivity) {
            logger.d("A report screen is open; the saved screenshot capture mode is dropped")
            return false
        }
        return beginExtraCapture(request)
    }

    /** FeedbackKit.disable() in capture mode: the report is dropped and handed back for cleanup. */
    fun abandonExtraCapture(): CaptureRequest? {
        val request = captureMode ?: return null
        captureMode = null
        extraAttempt = null
        isUiOpen = false
        overlay.hideAll()
        modeChanged()
        return request
    }

    /** "Capture" on [activity]: its screenshot, without the controls, then back to the report. */
    fun onCaptureTapped(activity: Activity) {
        val request = captureMode ?: return
        if (extraAttempt != null) return
        val attempt = ExtraAttempt(activity)
        extraAttempt = attempt
        try {
            extraCapture.capture(activity) { result ->
                if (extraAttempt !== attempt) {
                    // Capture mode was abandoned while the screenshot was taken.
                    deleteSaved(result)
                    return@capture
                }
                extraAttempt = null
                finishExtraCapture(attempt, request, result)
            }
        } catch (e: Exception) {
            if (extraAttempt === attempt) extraAttempt = null
            logger.e("Could not take the extra screenshot", e)
        }
    }

    /** "Cancel" on [activity]: back to the report without a screenshot. */
    fun onCaptureCancelled(activity: Activity) {
        val request = captureMode ?: return
        if (extraAttempt != null) return
        reopen(activity, request, ResumeRequest(request.state, null, extraScreenshotFailed = false))
    }

    /** "Record screen" in the report (spec §7); see [RecordingModeController.begin]. False while the SDK is off or capture mode runs. */
    fun beginRecording(host: Activity, request: CaptureRequest, onStarted: () -> Unit, onNotStarted: (refused: Boolean) -> Unit): Boolean {
        if (!canCapture() || captureMode != null) return false
        return recordingMode.begin(host, request, onStarted, onNotStarted)
    }

    /** "Stop" on [activity]; see [RecordingModeController.onStopTapped]. */
    fun onRecordingStopTapped(activity: Activity) = recordingMode.onStopTapped(activity)

    /** FeedbackKit.disable() in recording mode; see [RecordingModeController.abandon]. */
    fun abandonRecording(): CaptureRequest? = recordingMode.abandon()

    override fun onHostActivityResumed(activity: Activity) {
        if (recordingMode.onHostShown(activity)) return
        if (captureMode != null) {
            overlay.show(activity)
            return
        }
        if (isUiOpen && !captureInFlight && !tracker.hasLiveSdkActivity) {
            logger.d("The FeedbackKit screen is not open after all; invocations are accepted again")
            isUiOpen = false
            // Nobody will await the clip of the launch that never opened.
            unclaimedClipToken?.let(autoClips::abandon)
            unclaimedClipToken = null
        }
    }

    // In multi-window, the launching host pauses (and another resumed host takes over) before the
    // SDK activity is created; that is not a sign of a dropped launch. In capture mode [activity]
    // was already resumed and so already has the controls; show() does nothing for a host that has
    // them and only covers one that somehow lost them.
    override fun onHostActivityBecameCurrent(activity: Activity) {
        if (captureMode != null) overlay.show(activity)
        recordingMode.onHostShown(activity)
    }

    override fun onHostActivityPaused(activity: Activity) {
        // Only the captured window leaving spoils its screenshot; another window resuming or
        // pausing in multi-window does not.
        extraAttempt?.takeIf { it.activity === activity }?.hostLeft = true
        overlay.hide(activity)
        recordingMode.onHostPaused(activity)
    }

    private fun invokeOnMain(source: InvocationSource, reportType: ReportType?) {
        try {
            if (!isEnabled()) {
                logger.d("Invocation ($source) ignored: FeedbackKit is disabled")
                return
            }
            if (isUiOpen) {
                logger.d("Invocation ($source) ignored: the FeedbackKit screen is already open")
                return
            }
            val activity = tracker.currentActivity
            if (activity == null) {
                logger.w("Invocation ($source) ignored: no host screen is visible")
                return
            }
            isUiOpen = true
            // setAttachmentTypesEnabled(initialScreenshot = false): no capture, straight to the screen.
            if (!takeScreenshot()) {
                launch(activity, source, reportType, null)
                return
            }
            captureInFlight = true
            capture.capture(activity) { result ->
                captureInFlight = false
                launch(activity, source, reportType, result)
            }
        } catch (e: Exception) {
            isUiOpen = false
            captureInFlight = false
            logger.e("Invocation failed", e)
        }
    }

    private fun launch(activity: Activity, source: InvocationSource, reportType: ReportType?, result: ScreenCapturer.Result?) {
        // The capture is asynchronous: the SDK may have been disabled, or the host may have left the
        // screen, while it was in flight. A start from a host that is no longer resumed is dropped
        // silently on Android 10+ and the SDK screen would never report closing. Neither onInvoke
        // nor the SDK screen must run then, and a saved screenshot that will never be shown must not
        // linger.
        if (cancelled(activity, source, result)) return
        // Before onInvoke: the last seconds are those before the invocation, not the host's reaction to it.
        val clipToken = requestAutoClip()
        try {
            onInvoke()
        } catch (e: Exception) {
            logger.e("onInvokeCallback threw; continuing", e)
        }
        // The host's onInvoke may have called FeedbackKit.disable() or navigated away.
        if (cancelled(activity, source, result)) {
            clipToken?.let(autoClips::abandon)
            return
        }
        try {
            launcher(
                activity,
                LaunchRequest(
                    screenshot = (result as? ScreenCapturer.Result.Saved)?.file,
                    screenshotSecure = result == ScreenCapturer.Result.Secure,
                    currentScreen = activity.javaClass.name,
                    source = source,
                    reportType = reportType,
                    screenshotRequested = result != null,
                    autoRecordingToken = clipToken,
                ),
            )
            unclaimedClipToken = clipToken
        } catch (e: Exception) {
            isUiOpen = false
            deleteSaved(result)
            clipToken?.let(autoClips::abandon)
            logger.e("Could not open the FeedbackKit screen", e)
        }
    }

    /**
     * Asks Auto Screen Recording for its last seconds (spec §7); the token its report awaits them by,
     * or null when there is no recording. The report does not wait: finalising and stitching can take
     * many seconds. After [clipCapMillis] it gets null, and a clip that comes later is deleted.
     */
    private fun requestAutoClip(): String? {
        val token = autoClips.open()
        val cap = Runnable {
            if (autoClips.deliver(token, null)) logger.w("The automatic screen recording was not ready in time; the report goes on without it")
        }
        var answered = false
        val clipping = try {
            autoClip.clip { file ->
                answered = true
                mainHandler.removeCallbacks(cap)
                autoClips.deliver(token, file)
            }
        } catch (e: Exception) {
            logger.e("Could not prepare the automatic screen recording", e)
            false
        }
        if (!clipping) {
            autoClips.abandon(token)
            return null
        }
        if (!answered) mainHandler.postDelayed(cap, clipCapMillis)
        return token
    }

    private fun finishExtraCapture(attempt: ExtraAttempt, request: CaptureRequest, result: ScreenCapturer.Result) {
        val activity = attempt.activity
        if (attempt.hostLeft || activity.isFinishing || activity.isDestroyed) {
            logger.d("The host screen changed during the extra screenshot; it can be taken again")
            deleteSaved(result)
            return
        }
        val shot = (result as? ScreenCapturer.Result.Saved)?.file
        reopen(activity, request, ResumeRequest(request.state, shot, extraScreenshotFailed = shot == null))
    }

    private fun reopen(activity: Activity, request: CaptureRequest, resume: ResumeRequest) {
        captureMode = null
        overlay.hideAll()
        modeChanged()
        try {
            launchResumed(activity, request, resume)
        } catch (e: Exception) {
            logger.e("Could not reopen the report after the extra screenshot", e)
            resume.extraScreenshot?.let { deleteSaved(ScreenCapturer.Result.Saved(it)) }
            captureMode = request
            modeChanged()
            tracker.resumedActivities.forEach(overlay::show)
        }
    }

    /** Reopens [request]'s report after capture or recording mode. isUiOpen stays set: the reopened screen reports its own close. */
    private fun launchResumed(activity: Activity, request: CaptureRequest, resume: ResumeRequest) {
        launcher(
            activity,
            LaunchRequest(
                screenshot = null,
                screenshotSecure = false,
                currentScreen = request.currentScreen ?: activity.javaClass.name,
                source = InvocationSource.MANUAL,
                reportType = request.reportType,
                screenshotRequested = false,
                resume = resume,
            ),
        )
    }

    private fun cancelled(activity: Activity, source: InvocationSource, result: ScreenCapturer.Result?): Boolean {
        val stillOnScreen = !activity.isFinishing && !activity.isDestroyed && tracker.currentActivity === activity
        if (isEnabled() && stillOnScreen) return false
        logger.d("Invocation ($source) cancelled: FeedbackKit was disabled or the host screen went away")
        isUiOpen = false
        deleteSaved(result)
        return true
    }

    private fun deleteSaved(result: ScreenCapturer.Result?) {
        (result as? ScreenCapturer.Result.Saved)?.file?.let { file ->
            try {
                deleteFile(file)
            } catch (e: Exception) {
                logger.w("Could not delete an abandoned screenshot", e)
            }
        }
    }

    private fun modeChanged() {
        try {
            onCaptureModeChanged()
        } catch (e: Exception) {
            logger.e("Could not apply the screenshot capture mode", e)
        }
    }

    private companion object {
        /** How long a report waits for its clip at most: stitching 30 s took up to 14 s on an emulator. */
        const val CLIP_CAP_MILLIS = 60_000L
    }
}

/** Lets FeedbackActivity tell the coordinator it opened and closed without reaching the runtime. */
internal object InvocationHooks {
    @Volatile
    var onUiOpened: (() -> Unit)? = null

    @Volatile
    var onUiClosed: ((DismissType) -> Unit)? = null
}
