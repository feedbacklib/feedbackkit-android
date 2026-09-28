package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.app.Application
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import io.github.feedbacklib.android.FloatingButtonEdge
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.Config
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.proactive.ProactiveEvent
import io.github.feedbacklib.android.internal.proactive.ProactivePrompter
import io.github.feedbacklib.android.internal.recording.AutoRecordingController
import io.github.feedbacklib.android.internal.recording.PendingClips
import io.github.feedbacklib.android.internal.recording.RecordingLimits
import io.github.feedbacklib.android.internal.ui.FeedbackActivity
import io.github.feedbacklib.android.spi.ScreenRecorder
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The extra screenshot (spec §6): [capture] with the capture controls and the floating button both
 * hidden. The button is normally already gone in capture mode (its detector is off); hiding it too
 * keeps it out of the shot should the detectors not have caught up yet.
 */
internal fun extraScreenshotCapture(controls: CaptureHider, button: CaptureHider, capture: ScreenCapture, logger: SdkLogger): ScreenCapture =
    HidingCapture(controls, HidingCapture(button, capture, logger), logger)

/**
 * Owns everything that opens the SDK screen (spec §5), the extra screenshot's capture mode
 * (spec §6), manual recording's recording mode and Auto Screen Recording (spec §7). Created in
 * build(); no disk I/O here — [deleteFile] deletes off the main thread, and the recording directory
 * (auto recording's segments and clips included) is resolved by [resolveDirectories] on the startup
 * pass.
 *
 * Auto Screen Recording follows [Config.canInvoke] as well as its own switch: only an invocation
 * uses its clip.
 *
 * Only FeedbackKit.disable() ends capture or recording mode from outside ([onCaptureAbandoned]).
 * With the SDK enabled but bug reporting switched off, the mode stays: no new report can be invoked,
 * yet Capture, Cancel and Stop still return to the report the user already has open.
 */
internal class InvocationSystem(
    private val app: Application,
    private val config: () -> Config,
    onInvoke: () -> Unit,
    private val logger: SdkLogger,
    deleteFile: (File) -> Unit,
    private val onCaptureAbandoned: (CaptureRequest) -> Unit = {},
    /** Capture mode dropped for a report screen the system restored (main thread); see [InvocationCoordinator.onUiOpened]. */
    onCaptureDropped: (CaptureRequest) -> Unit = {},
    /** The optional screen recorder (spec §3); null while unknown or absent. */
    private val recorder: () -> ScreenRecorder? = { null },
    /** Whether the recorder lookup ran (auto recording warns about a missing artifact only then). */
    recorderKnown: () -> Boolean = { false },
    /** Where an invocation's automatic recording travels to its report (spec §7). */
    autoClips: PendingClips = PendingClips(deleteFile, logger),
    /** Proactive reporting's prompt for this event is settled: shown at this wall clock time, or dropped (null) (spec §8). */
    private val onProactiveResolved: (ProactiveEvent, Long?) -> Unit = { _, _ -> },
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopped = false
    private val tracker = ActivityTracker(logger)
    private val capturer = ScreenCapturer({ File(app.cacheDir, CAPTURE_DIR) }, logger)

    // getCacheDir() can create the directory: first touched by resolveDirectories() off the main
    // thread, before the recorder is known, so a recording's path never costs disk I/O on main.
    private val recordingDirPath = lazy { File(app.cacheDir, RecordingLimits.CACHE_DIR) }
    private val recordingDir: File by recordingDirPath

    /** Whether [resolveDirectories] (or anything after it) resolved the recording directory. */
    val isRecordingDirResolved: Boolean
        get() = recordingDirPath.isInitialized()
    private val shakeAlgorithm = ShakeAlgorithm(Config.DEFAULT_SHAKING_THRESHOLD)
    private val buttonState = FloatingButtonState()

    // Declared before the coordinator that captures through it; its tap handler reaches the
    // coordinator lazily, at tap time.
    private val floatingButton = FloatingButtonDetector(buttonState, { coordinator.invoke(InvocationSource.FLOATING_BUTTON) }, logger)

    private val overlay = CaptureOverlay(
        accentColor = { config().ui.primaryColor ?: DEFAULT_ACCENT },
        onCapture = { coordinator.onCaptureTapped(it) },
        onCancel = { coordinator.onCaptureCancelled(it) },
        logger = logger,
    )

    private val recordingOverlay = RecordingOverlay(
        position = { config().recordingButtonPosition },
        accentColor = { config().ui.primaryColor ?: DEFAULT_ACCENT },
        startedAt = { coordinator.recordingStartedAt },
        onStop = { coordinator.onRecordingStopTapped(it) },
        logger = logger,
    )

    // Paths under recordingDir: resolved on the startup pass, and no recorder (so no session) exists before it.
    private val autoRecording = AutoRecordingController(
        recorder = recorder,
        recorderKnown = recorderKnown,
        tracker = tracker,
        segmentsDir = { File(recordingDir, RecordingLimits.SEGMENTS_SUBDIR) },
        newClipFile = { File(recordingDir, "auto-${UUID.randomUUID()}.mp4") },
        logger = logger,
    )

    val coordinator: InvocationCoordinator = InvocationCoordinator(
        tracker = tracker,
        capture = HidingCapture(floatingButton, capturer::capture, logger),
        isEnabled = { config().canInvoke },
        onInvoke = onInvoke,
        launcher = { activity, request -> activity.startActivity(FeedbackActivity.intent(activity, request)) },
        logger = logger,
        deleteFile = deleteFile,
        takeScreenshot = { config().ui.attachmentTypes.initialScreenshot },
        overlay = overlay,
        // The same ScreenCapturer (masking, FLAG_SECURE), with the controls hidden as well.
        extraCapture = extraScreenshotCapture(overlay, floatingButton, capturer::capture, logger),
        onCaptureModeChanged = { manager.refresh() },
        // An open report steps aside for a screenshot whenever the SDK is on, bug reporting or not.
        canCapture = { config().enabled },
        onCaptureDropped = onCaptureDropped,
        recorder = recorder,
        recordingOverlay = recordingOverlay,
        newRecordingFile = { File(recordingDir, "manual-${UUID.randomUUID()}.mp4") },
        onRecordingAbandoned = onCaptureAbandoned,
        stopAutoRecording = { autoRecording.onManualRecording() },
        autoClip = autoRecording,
        onUiOpenChanged = { autoRecording.onUiOpenChanged(it) },
        autoClips = autoClips,
    )

    /**
     * Proactive reporting's prompt (spec §8): after the delay, over the current host screen, through the
     * coordinator. Made by the first offer, on the main thread, so build() makes none; written there only,
     * volatile for [stop] from another thread.
     */
    @Volatile
    private var proactivePrompter: ProactivePrompter? = null

    // Tracker listeners added after start(), such as the session heartbeat (spec §8); removed at stop().
    private val lateListeners = CopyOnWriteArrayList<ActivityTracker.Listener>()

    private val screenshot = screenshotDetectors(app, { coordinator.invoke(InvocationSource.SCREENSHOT) }, logger)

    private val manager = InvocationManager(
        tracker = tracker,
        events = { config().invocationEvents },
        // The detectors are silent while a report waits in capture or recording mode.
        isEnabled = { config().canInvoke && !coordinator.isSteppedAside },
        processDetectors = buildMap {
            put(InvocationEvent.SHAKE, ShakeDetector({ app.getSystemService(SensorManager::class.java) }, shakeAlgorithm, { coordinator.invoke(InvocationSource.SHAKE) }, logger))
            screenshot.first?.let { put(InvocationEvent.SCREENSHOT, it) }
        },
        activityDetectors = buildMap {
            put(InvocationEvent.FLOATING_BUTTON, floatingButton)
            put(InvocationEvent.TWO_FINGER_SWIPE_LEFT, TwoFingerSwipeDetector({ InvocationEvent.TWO_FINGER_SWIPE_LEFT in config().invocationEvents && config().canInvoke }, { coordinator.invoke(InvocationSource.TWO_FINGER_SWIPE) }, logger))
            screenshot.second?.let { put(InvocationEvent.SCREENSHOT, it) }
        },
        logger = logger,
    )

    fun start() {
        syncSettings()
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.addListener(manager)
        tracker.addListener(coordinator)
        tracker.addListener(autoRecording)
        InvocationHooks.onUiOpened = { coordinator.onUiOpened() }
        InvocationHooks.onUiClosed = { dismissType -> coordinator.onUiClosed(dismissType) }
    }

    /** Applies the current config; safe from any thread. A no-op once [stop] has run. */
    fun refresh() {
        mainHandler.post {
            if (stopped) return@post
            // FeedbackKit.disable() drops a report waiting in capture or recording mode (spec §6, §7);
            // bug reporting switched off does not. Its own guard: a failed hand-off must not keep the
            // new settings from being applied.
            try {
                if (!config().enabled) {
                    coordinator.abandonExtraCapture()?.let(onCaptureAbandoned)
                    coordinator.abandonRecording()?.let(onCaptureAbandoned)
                }
            } catch (e: Exception) {
                logger.e("Could not end the screenshot capture or recording mode", e)
            }
            try {
                syncSettings()
                manager.refresh()
                floatingButton.relayout()
                // setVideoRecordingButtonPosition applies to a recording in progress too.
                recordingOverlay.relayout()
                // Only an invocation uses the clip: off with FeedbackKit or bug reporting off (spec §7).
                val current = config()
                autoRecording.setEnabled(current.canInvoke && current.autoScreenRecording)
            } catch (e: Exception) {
                logger.e("Could not apply invocation settings", e)
            }
        }
    }

    fun show(reportType: ReportType? = null) = coordinator.invoke(InvocationSource.MANUAL, reportType)

    /** A crash or force restart of the previous run (spec §8); safe from any thread. A no-op once [stop] has run. */
    fun offerProactive(event: ProactiveEvent) {
        mainHandler.post {
            if (stopped) return@post
            try {
                prompter().offer(event)
            } catch (e: Exception) {
                logger.e("Could not queue the proactive reporting prompt", e)
            }
        }
    }

    /**
     * Whether the app is in the foreground, as the activity tracker has seen since [start]: for a listener
     * added later, which hears only the changes after. Main thread.
     */
    val isForeground: Boolean
        get() = tracker.isForeground

    /** Adds [listener] to the activity tracker from now on (see [isForeground]). Main thread; false once [stop] has run. */
    fun addTrackerListener(listener: ActivityTracker.Listener): Boolean {
        if (stopped) return false
        lateListeners += listener
        tracker.addListener(listener)
        return true
    }

    // Main thread. Listens after the others, as the coordinator's resume handling comes first.
    private fun prompter(): ProactivePrompter = proactivePrompter ?: ProactivePrompter(
        tracker = tracker,
        settings = { config().proactive },
        // A delay that ends between stop() and its cancel on the main thread opens nothing.
        show = { info -> !stopped && coordinator.showProactive(info) },
        onResolved = onProactiveResolved,
        logger = logger,
    ).also {
        proactivePrompter = it
        tracker.addListener(it)
    }

    /** See [InvocationCoordinator.beginExtraCapture]. Main thread. */
    fun beginExtraCapture(request: CaptureRequest): Boolean = !stopped && coordinator.beginExtraCapture(request)

    /** See [InvocationCoordinator.restoreExtraCapture]. Main thread. */
    fun restoreExtraCapture(request: CaptureRequest): Boolean = !stopped && coordinator.restoreExtraCapture(request)

    /** See [InvocationCoordinator.beginRecording]. Main thread. */
    fun beginRecording(host: Activity, request: CaptureRequest, onStarted: () -> Unit, onNotStarted: (refused: Boolean) -> Unit): Boolean =
        !stopped && coordinator.beginRecording(host, request, onStarted, onNotStarted)

    fun stop() {
        stopped = true
        app.unregisterActivityLifecycleCallbacks(tracker)
        tracker.removeListener(manager)
        tracker.removeListener(coordinator)
        tracker.removeListener(autoRecording)
        lateListeners.forEach(tracker::removeListener)
        proactivePrompter?.let(tracker::removeListener)
        mainHandler.post {
            // First: ending the mode refreshes the detectors, which stopAll() then stops for good.
            try {
                coordinator.abandonRecording()?.let(onCaptureAbandoned)
            } catch (e: Exception) {
                logger.e("Could not end the recording mode", e)
            }
            try {
                autoRecording.setEnabled(false)
            } catch (e: Exception) {
                logger.e("Could not stop Auto Screen Recording", e)
            }
            // Again here: a prompter made by an offer that raced this stop() is let go as well.
            proactivePrompter?.let {
                tracker.removeListener(it)
                it.cancel()
            }
            manager.stopAll()
            overlay.hideAll()
        }
        InvocationHooks.onUiOpened = null
        InvocationHooks.onUiClosed = null
    }

    /**
     * Deletes anything left in the capture directory from a previous process that is old enough to
     * be certain it was abandoned (spec §5): a screenshot whose SDK screen never opened, e.g. the
     * host was killed between capture and launch — and recordings that never reached a draft
     * (spec §7). Only files: the Auto Screen Recording segments directory is not touched. A file younger than [STALE_CAPTURE_MAX_AGE_MILLIS]
     * is left alone — process death can recreate [FeedbackActivity] with the same intent pointing at
     * a screenshot this same startup is purging, and a fresh capture can still be mid-flight (an
     * early [show] or shake) or its attachment still being copied by a report submission. Disk I/O —
     * call only from a background thread, never from [android.app.Application.onCreate]. Failure
     * only logs (never crashes the host).
     */
    fun purgeStaleCaptures() {
        try {
            val cutoff = System.currentTimeMillis() - STALE_CAPTURE_MAX_AGE_MILLIS
            listOf(File(app.cacheDir, CAPTURE_DIR), recordingDir).forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    if (!file.isFile || file.lastModified() >= cutoff) return@forEach
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        logger.w("Could not delete a stale capture", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Could not purge stale captures", e)
        }
    }

    /**
     * Resolves the recording directory's path (getCacheDir() may create the cache directory). Disk
     * I/O — background only, on the startup pass before the recorder is looked up, so no recording
     * can start before it ran. Never throws.
     */
    fun resolveDirectories() {
        try {
            recordingDir
        } catch (e: Exception) {
            logger.w("Could not resolve the recording directory", e)
        }
    }

    private fun syncSettings() {
        val current = config()
        shakeAlgorithm.threshold = current.shakingThreshold
        buttonState.edge = if (current.floatingButtonEdge == FloatingButtonEdge.LEFT) ButtonEdge.LEFT else ButtonEdge.RIGHT
        buttonState.offsetDp = current.floatingButtonOffsetDp
    }

    private companion object {
        const val CAPTURE_DIR = "feedbackkit/capture"
        const val STALE_CAPTURE_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        const val DEFAULT_ACCENT = 0xFF1565C0.toInt() // the floating button's blue
    }
}
