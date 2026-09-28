package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.recording.PendingClips
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import io.github.feedbacklib.android.spi.AutoRecordingListener
import io.github.feedbacklib.android.spi.AutoRecordingSession
import io.github.feedbacklib.android.spi.RecordingListener
import io.github.feedbacklib.android.spi.RecordingSession
import io.github.feedbacklib.android.spi.RecordingStopReason
import io.github.feedbacklib.android.spi.ScreenRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class InvocationCoordinatorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val tracker = ActivityTracker(SdkLogger(LogLevel.NONE))
    private var enabled = true
    private val calls = mutableListOf<String>()
    private val launches = mutableListOf<LaunchRequest>()
    private val launchedFrom = mutableListOf<Activity>()
    private val extraCapturedFrom = mutableListOf<Activity>()
    private var captureResult: ScreenCapturer.Result = ScreenCapturer.Result.Saved(File("shot.png"))
    private var onInvoke: () -> Unit = { calls += "onInvoke" }
    private var holdCapture = false
    private var captureCallback: ((ScreenCapturer.Result) -> Unit)? = null
    private var launcherThrows = false
    private var takeScreenshot = true
    private val deleted = mutableListOf<File>()
    private val overlayCalls = mutableListOf<String>()
    private val fakeOverlay = object : CaptureOverlayHost {
        override fun show(activity: Activity) { overlayCalls += "show" }
        override fun hide(activity: Activity) { overlayCalls += "hide" }
        override fun hideAll() { overlayCalls += "hideAll" }
    }
    private var extraResult: ScreenCapturer.Result = ScreenCapturer.Result.Saved(File("extra.png"))
    private var holdExtra = false
    private var extraCallback: ((ScreenCapturer.Result) -> Unit)? = null
    private var modeChanges = 0
    private var captureAllowed = true
    private val dropped = mutableListOf<CaptureRequest>()
    private val request = CaptureRequest("draft-1", ReportType.BUG, mapOf("feedbackkit.comment" to "Two screens"), "com.example.Host")
    private val fakeRecorder = FakeRecorder()
    private var recorderPresent = true
    private val recordingOverlayCalls = mutableListOf<String>()
    private val fakeRecordingOverlay = object : RecordingOverlayHost {
        override fun show(activity: Activity) { recordingOverlayCalls += "show" }
        override fun hide(activity: Activity) { recordingOverlayCalls += "hide" }
        override fun hideAll() { recordingOverlayCalls += "hideAll" }
        override fun relayout() { recordingOverlayCalls += "relayout" }
    }
    private val recordingAbandoned = mutableListOf<CaptureRequest>()
    private val recordingEvents = mutableListOf<String>()
    private var recordingFiles = 0
    private var autoStops = 0
    private var autoRecordingOn = false
    private var clipFile: File? = null
    private var holdClip = false
    private var clipCallback: ((File?) -> Unit)? = null
    private val uiOpenChanges = mutableListOf<Boolean>()
    private val clips = PendingClips({ deleted += it; it.delete() }, SdkLogger(LogLevel.NONE))

    private class FakeRecorder : ScreenRecorder {
        val hosts = mutableListOf<Activity>()
        val listeners = mutableListOf<RecordingListener>()
        var maxDuration = 0L
        var stops = 0
        var onRecord: () -> Unit = {}

        override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession {
            onRecord()
            hosts += host
            listeners += listener
            maxDuration = maxDurationMillis
            return RecordingSession { stops++ }
        }

        override fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession = error("not used")
    }

    private val coordinator by lazy {
        InvocationCoordinator(
            tracker = tracker,
            capture = { _, callback ->
                calls += "capture"
                if (holdCapture) captureCallback = callback else callback(captureResult)
            },
            isEnabled = { enabled },
            onInvoke = { onInvoke() },
            launcher = { activity, request ->
                if (launcherThrows) {
                    launcherThrows = false
                    error("launcher bug")
                }
                calls += "launch"
                launches += request
                launchedFrom += activity
            },
            logger = SdkLogger(LogLevel.NONE),
            // The real one deletes on the SDK's background scope; synchronous here so tests can look.
            deleteFile = { file ->
                deleted += file
                file.delete()
            },
            takeScreenshot = { takeScreenshot },
            overlay = fakeOverlay,
            extraCapture = { activity, callback ->
                calls += "extraCapture"
                extraCapturedFrom += activity
                if (holdExtra) extraCallback = callback else callback(extraResult)
            },
            onCaptureModeChanged = { modeChanges++ },
            canCapture = { captureAllowed },
            onCaptureDropped = { dropped += it },
            recorder = { fakeRecorder.takeIf { recorderPresent } },
            recordingOverlay = fakeRecordingOverlay,
            newRecordingFile = { File(app.cacheDir, "rec-${++recordingFiles}.mp4") },
            onRecordingAbandoned = { recordingAbandoned += it },
            stopAutoRecording = { autoStops++ },
            autoClip = AutoClipSource { callback ->
                if (!autoRecordingOn) {
                    false
                } else {
                    calls += "clip"
                    if (holdClip) clipCallback = callback else callback(clipFile)
                    true
                }
            },
            onUiOpenChanged = { uiOpenChanges += it },
            autoClips = clips,
        ).also { tracker.addListener(it) }
    }

    @Before
    fun register() = app.registerActivityLifecycleCallbacks(tracker)

    @After
    fun unregister() = app.unregisterActivityLifecycleCallbacks(tracker)

    private fun invoke(source: InvocationSource = InvocationSource.MANUAL) {
        coordinator.invoke(source)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `captures, calls the host callback, then launches with the screen name`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        invoke(InvocationSource.SHAKE)
        assertEquals(listOf("capture", "onInvoke", "launch"), calls)
        assertEquals(activity.javaClass.name, launches.single().currentScreen)
        assertEquals(File("shot.png"), launches.single().screenshot)
        assertEquals(InvocationSource.SHAKE, launches.single().source)
    }

    @Test
    fun `with auto recording the report opens at once and its clip follows through a token`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        autoRecordingOn = true
        holdClip = true
        invoke()
        assertEquals(listOf("capture", "clip", "onInvoke", "launch"), calls)
        val token = launches.single().autoRecordingToken
        assertNotNull("the report does not wait for the clip", token)
        val clip = video("auto.mp4")
        clipCallback!!(clip)
        assertEquals(clip, runBlocking { clips.await(token!!) })
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `without auto recording the report opens without a clip`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        assertEquals(listOf("capture", "onInvoke", "launch"), calls)
        assertNull(launches.single().autoRecordingToken)
    }

    @Test
    fun `without the invocation screenshot the clip is still asked for`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        takeScreenshot = false
        autoRecordingOn = true
        clipFile = video("auto.mp4")
        invoke()
        assertEquals(listOf("clip", "onInvoke", "launch"), calls)
        assertEquals(clipFile, runBlocking { clips.await(launches.single().autoRecordingToken!!) })
    }

    @Test
    fun `a clip not ready within a minute is given up and deleted when it comes`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        autoRecordingOn = true
        holdClip = true
        invoke()
        val token = launches.single().autoRecordingToken!!
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS)
        assertNull(runBlocking { clips.await(token) })
        val late = video("late-clip.mp4")
        clipCallback!!(late)
        assertTrue(deleted.contains(late))
    }

    @Test
    fun `a slow clip within the minute still reaches its report`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        autoRecordingOn = true
        holdClip = true
        invoke()
        ShadowLooper.idleMainLooper(59, TimeUnit.SECONDS)
        val clip = video("slow-clip.mp4")
        clipCallback!!(clip)
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertEquals(clip, runBlocking { clips.await(launches.single().autoRecordingToken!!) })
        assertFalse(deleted.contains(clip))
    }

    @Test
    fun `a report that could not open gives its clip up`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        autoRecordingOn = true
        holdClip = true
        launcherThrows = true
        invoke()
        assertTrue(launches.isEmpty())
        assertFalse(coordinator.isUiOpen)
        val clip = video("orphan-clip.mp4")
        clipCallback!!(clip)
        assertTrue(deleted.contains(clip))
    }

    @Test
    fun `an invocation the host cancelled in onInvoke gives its clip up with the screenshot`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        autoRecordingOn = true
        clipFile = video("cancelled-clip.mp4")
        onInvoke = { enabled = false }
        invoke()
        assertTrue(launches.isEmpty())
        assertTrue(deleted.contains(clipFile))
        assertTrue(deleted.contains(File("shot.png")))
    }

    @Test
    fun `a clip source that throws opens the report without a clip`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        val throwing = InvocationCoordinator(
            tracker = tracker,
            capture = { _, callback -> callback(captureResult) },
            isEnabled = { true },
            onInvoke = {},
            launcher = { _, request -> launches += request },
            logger = SdkLogger(LogLevel.NONE),
            deleteFile = { deleted += it },
            autoClip = AutoClipSource { error("recorder bug") },
        )
        throwing.invoke(InvocationSource.MANUAL)
        ShadowLooper.idleMainLooper()
        assertNull(launches.single().autoRecordingToken)
    }

    @Test
    fun `opening and closing the report screen is reported once each way`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        coordinator.onUiOpened()
        assertEquals(listOf(true), uiOpenChanges)
        coordinator.onUiClosed(DismissType.CANCEL)
        assertEquals(listOf(true, false), uiOpenChanges)
    }

    @Test
    fun `a second invocation while the sdk screen is open is ignored until it closes`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        invoke()
        assertEquals(1, launches.size)
        coordinator.onUiClosed(DismissType.CANCEL)
        invoke()
        assertEquals(2, launches.size)
    }

    @Test
    fun `a screen the system restored after process death counts as open until it closes`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        coordinator.onUiOpened() // FeedbackActivity recreated from saved state: no invocation opened it
        invoke()
        assertTrue("a second screen must not open beside it (multi-window)", launches.isEmpty())

        coordinator.onUiClosed(DismissType.CANCEL)
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `the launched screen reporting itself open changes nothing and one close resets it`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        assertTrue(coordinator.isUiOpen)
        coordinator.onUiOpened()
        invoke()
        assertEquals(1, launches.size)

        coordinator.onUiClosed(DismissType.CANCEL)
        assertFalse(coordinator.isUiOpen)
        invoke()
        assertEquals(2, launches.size)
    }

    @Test
    fun `nothing happens without a host activity or while disabled`() {
        invoke()
        Robolectric.buildActivity(Activity::class.java).setup()
        enabled = false
        invoke()
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `a secure window launches without a screenshot`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        captureResult = ScreenCapturer.Result.Secure
        invoke()
        assertEquals(null, launches.single().screenshot)
        assertTrue(launches.single().screenshotSecure)
    }

    @Test
    fun `a throwing host callback does not stop the launch`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        onInvoke = { error("host bug") }
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `a launcher that throws once deletes the screenshot and the next invoke launches`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        val shot = File.createTempFile("feedbackkit-shot", ".png")
        captureResult = ScreenCapturer.Result.Saved(shot)
        launcherThrows = true

        invoke()
        assertTrue(launches.isEmpty())
        assertFalse(shot.exists())

        val secondShot = File.createTempFile("feedbackkit-shot2", ".png")
        captureResult = ScreenCapturer.Result.Saved(secondShot)
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `the sdk being disabled while capture is in flight cancels the launch and deletes the screenshot`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        val shot = File.createTempFile("feedbackkit-shot3", ".png")
        captureResult = ScreenCapturer.Result.Saved(shot)
        holdCapture = true

        invoke()
        assertEquals(listOf("capture"), calls)

        enabled = false
        captureCallback?.invoke(captureResult)
        ShadowLooper.idleMainLooper()

        assertTrue(launches.isEmpty())
        assertFalse(calls.contains("onInvoke"))
        assertFalse(shot.exists())

        // The open flag was reset: a later, enabled invocation is not stuck ignored.
        enabled = true
        holdCapture = false
        captureResult = ScreenCapturer.Result.Saved(File.createTempFile("feedbackkit-shot4", ".png"))
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `the host activity finishing while capture is in flight cancels the launch and deletes the screenshot`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val shot = File.createTempFile("feedbackkit-shot5", ".png")
        captureResult = ScreenCapturer.Result.Saved(shot)
        holdCapture = true

        invoke()
        assertEquals(listOf("capture"), calls)

        activity.finish()
        captureCallback?.invoke(captureResult)
        ShadowLooper.idleMainLooper()

        assertTrue(launches.isEmpty())
        assertFalse(calls.contains("onInvoke"))
        assertFalse(shot.exists())
    }
    private fun captures() = calls.count { it == "capture" }

    @Test
    fun `a host paused while the capture is in flight cancels the launch and the next invoke launches`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val shot = File.createTempFile("feedbackkit-shot6", ".png")
        captureResult = ScreenCapturer.Result.Saved(shot)
        holdCapture = true
        invoke()

        // Home pressed or the screenshot editor opened: a start from the background would be dropped.
        host.pause()
        captureCallback?.invoke(captureResult)
        ShadowLooper.idleMainLooper()

        assertTrue(launches.isEmpty())
        assertFalse(calls.contains("onInvoke"))
        assertFalse(shot.exists())

        host.resume()
        holdCapture = false
        captureResult = ScreenCapturer.Result.Failed
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `a host stopped while the capture is in flight cancels the launch and the next invoke launches`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        holdCapture = true
        captureResult = ScreenCapturer.Result.Failed
        invoke()

        host.pause().stop()
        captureCallback?.invoke(captureResult)
        ShadowLooper.idleMainLooper()
        assertTrue(launches.isEmpty())

        host.start().resume()
        holdCapture = false
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `a launch that never creates the sdk screen is forgotten once the host resumes`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        invoke()
        assertEquals(1, launches.size)

        // The start was dropped: no SDK activity ever appears, the user comes back to the host.
        host.pause().resume()
        invoke()
        assertEquals(2, launches.size)
    }

    @Test
    fun `a launch that never created its screen gives its clip up`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        autoRecordingOn = true
        holdClip = true
        invoke()
        host.pause().resume() // the start was dropped
        assertFalse(coordinator.isUiOpen)
        val clip = video("dropped-clip.mp4")
        clipCallback!!(clip)
        assertTrue("nobody will await it", deleted.contains(clip))
    }

    @Test
    fun `a screen that opened owns its clip, even when the watchdog frees the invocation`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        autoRecordingOn = true
        holdClip = true
        invoke()
        coordinator.onUiOpened() // created: its ViewModel awaits the token (and may be restored later)
        host.pause().resume()
        val clip = video("owned-clip.mp4")
        clipCallback!!(clip)
        assertFalse(deleted.contains(clip))
        assertEquals(clip, runBlocking { clips.await(launches.single().autoRecordingToken!!) })
    }

    @Test
    fun `the host resuming while the sdk screen is alive keeps further invocations ignored`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        host.pause()
        Robolectric.buildActivity(FakeSdkActivity::class.java).setup()
        host.resume()

        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `the host resuming while the capture is in flight does not start a second capture`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        holdCapture = true
        invoke()
        host.pause().resume()

        invoke()
        assertEquals(1, captures())
    }

    @Test
    fun `the host callback disabling the sdk cancels the launch and deletes the screenshot`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        val shot = File.createTempFile("feedbackkit-shot7", ".png")
        captureResult = ScreenCapturer.Result.Saved(shot)
        onInvoke = { calls += "onInvoke"; enabled = false }

        invoke()

        assertTrue(launches.isEmpty())
        assertFalse(shot.exists())

        enabled = true
        onInvoke = {}
        captureResult = ScreenCapturer.Result.Failed
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `the requested report type travels to the launch`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        coordinator.invoke(InvocationSource.MANUAL, ReportType.QUESTION)
        ShadowLooper.idleMainLooper()
        assertEquals(ReportType.QUESTION, launches.single().reportType)
    }

    @Test
    fun `an invocation screenshot is requested by default`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        assertTrue(launches.single().screenshotRequested)
    }

    @Test
    fun `with the invocation screenshot switched off nothing is captured and the screen opens at once`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        takeScreenshot = false
        invoke()
        assertEquals(listOf("onInvoke", "launch"), calls)
        assertNull(launches.single().screenshot)
        assertFalse(launches.single().screenshotRequested)
        assertTrue(coordinator.isUiOpen)
    }

    @Test
    fun `capture mode keeps the report open, ignores invocations and outlives the screen's close`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        assertTrue(coordinator.beginExtraCapture(request))
        coordinator.onUiClosed(DismissType.ADD_ATTACHMENT) // the close of the report screen stepping aside
        assertTrue(coordinator.isUiOpen)
        assertTrue(coordinator.isCapturing)
        assertEquals(1, modeChanges)

        invoke()
        assertEquals("a shake in capture mode opens nothing", 1, launches.size)
    }

    @Test
    fun `the controls follow the resumed host and the watchdog leaves capture mode alone`() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        coordinator.beginExtraCapture(request)
        first.pause()
        Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(listOf("show", "hide", "show"), overlayCalls)
        assertTrue("no SDK activity is alive, and still capture mode goes on", coordinator.isUiOpen)
        assertTrue(coordinator.isCapturing)
    }

    @Test
    fun `capture takes the host screenshot and reopens the report with it`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(host)

        val reopened = launches.single()
        assertEquals(ReportType.BUG, reopened.reportType)
        assertEquals("com.example.Host", reopened.currentScreen)
        assertNull(reopened.screenshot)
        assertFalse(reopened.screenshotRequested)
        assertEquals(ResumeRequest(request.state, File("extra.png"), extraScreenshotFailed = false), reopened.resume)
        assertEquals(listOf("extraCapture", "launch"), calls)
        assertFalse(coordinator.isCapturing)
        assertTrue("the reopened screen reports its own close", coordinator.isUiOpen)
        assertTrue(overlayCalls.contains("hideAll"))
        assertEquals(2, modeChanges)
    }

    @Test
    fun `a secure or failed capture reopens the report with the placeholder instead`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        extraResult = ScreenCapturer.Result.Secure
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(host)
        assertEquals(ResumeRequest(request.state, null, extraScreenshotFailed = true), launches.single().resume)
    }

    @Test
    fun `cancel reopens the report without a screenshot`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureCancelled(host)
        assertEquals(ResumeRequest(request.state, null, extraScreenshotFailed = false), launches.single().resume)
        assertFalse(coordinator.isCapturing)
    }

    @Test
    fun `taps while the capture is in flight are ignored`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        holdExtra = true
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(host)
        coordinator.onCaptureTapped(host)
        coordinator.onCaptureCancelled(host)
        assertEquals(listOf("extraCapture"), calls)

        extraCallback!!(ScreenCapturer.Result.Saved(File("late.png")))
        assertEquals(1, launches.size)
    }

    @Test
    fun `a host that left during the capture keeps capture mode and the screenshot is deleted`() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val shot = File.createTempFile("extra", ".png")
        extraResult = ScreenCapturer.Result.Saved(shot)
        holdExtra = true
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(first.get())
        first.pause()
        Robolectric.buildActivity(Activity::class.java).setup()

        extraCallback!!(extraResult)
        assertTrue(launches.isEmpty())
        assertTrue(coordinator.isCapturing)
        assertFalse(shot.exists())
        assertEquals("deleted through the injected background delete", listOf(shot), deleted)
    }

    @Test
    fun `abandoning drops capture mode, a late capture is deleted and invocations work again`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val shot = File.createTempFile("extra", ".png")
        extraResult = ScreenCapturer.Result.Saved(shot)
        holdExtra = true
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(host)

        assertEquals(request, coordinator.abandonExtraCapture())
        assertNull(coordinator.abandonExtraCapture())
        assertFalse(coordinator.isUiOpen)
        assertFalse(coordinator.isCapturing)
        extraCallback!!(extraResult)
        assertFalse(shot.exists())
        assertTrue(launches.isEmpty())

        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `a late extra screenshot never releases the watchdog of the invocation that followed`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        holdExtra = true
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(host.get())
        coordinator.abandonExtraCapture() // FeedbackKit.disable() while the extra screenshot runs

        holdCapture = true // enabled again; the next invocation's screenshot is still being taken
        invoke()
        assertTrue(coordinator.isUiOpen)

        extraCallback!!(ScreenCapturer.Result.Failed)
        host.pause().resume()
        assertTrue("the invocation's capture is still in flight", coordinator.isUiOpen)
        invoke()
        assertEquals("no second capture beside the one in flight", 1, captures())
    }

    @Test
    fun `capture mode is restored only when no report screen is open`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        coordinator.onUiOpened() // a report screen the system restored
        assertFalse(coordinator.restoreExtraCapture(request))
        coordinator.onUiClosed(DismissType.CANCEL)
        assertTrue(coordinator.restoreExtraCapture(request))
        assertTrue(coordinator.isCapturing)
    }

    @Test
    fun `a reopen that fails keeps capture mode and shows the controls again`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        coordinator.beginExtraCapture(request)
        launcherThrows = true
        coordinator.onCaptureCancelled(host)
        assertTrue(coordinator.isCapturing)
        assertEquals("show", overlayCalls.last())
    }

    @Test
    fun `an open report steps aside with invocations off, but not while capture is refused`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        captureAllowed = false // the SDK is disabled
        assertFalse(coordinator.beginExtraCapture(request))
        assertFalse(coordinator.restoreExtraCapture(request))
        assertFalse(coordinator.isCapturing)

        captureAllowed = true
        enabled = false // only bug reporting is off: no new invocation, yet the open report may step aside
        assertTrue(coordinator.beginExtraCapture(request))
        assertTrue(coordinator.isCapturing)
    }

    @Test
    fun `a report screen the system restored after capture mode came back drops the mode`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(coordinator.restoreExtraCapture(request))
        overlayCalls.clear()

        coordinator.onUiOpened() // the restored screen is created after the restore ran
        assertFalse(coordinator.isCapturing)
        assertTrue("the restored screen is open", coordinator.isUiOpen)
        assertEquals(listOf("hideAll"), overlayCalls)
        assertEquals(listOf(request), dropped)
        assertEquals(2, modeChanges)
    }

    @Test
    fun `the report reopened by Cancel opening itself drops nothing`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureCancelled(host)
        coordinator.onUiOpened()
        assertTrue(dropped.isEmpty())
        assertTrue(coordinator.isUiOpen)
    }

    @Test
    fun `in multi-window every resumed host gets the controls and Capture takes the window it was tapped on`() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        Robolectric.buildActivity(Activity::class.java).setup() // resumed beside it, and the current one
        coordinator.beginExtraCapture(request)
        assertEquals(listOf("show", "show"), overlayCalls)

        coordinator.onCaptureTapped(first)
        assertEquals(ResumeRequest(request.state, File("extra.png"), extraScreenshotFailed = false), launches.single().resume)
        assertEquals("the window whose Capture was tapped is the one captured", listOf(first), extraCapturedFrom)
        assertEquals("and the report reopens from it", listOf(first), launchedFrom)
    }

    @Test
    fun `in multi-window the stepped-aside screen closing late does not free a report reopened from another window`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        invoke()
        assertTrue(coordinator.beginExtraCapture(request))
        coordinator.onCaptureCancelled(other) // a quick Cancel in the other window, before the old screen is gone
        coordinator.onUiOpened() // the reopened screen

        coordinator.onUiClosed(DismissType.ADD_ATTACHMENT) // the old screen's close arrives only now
        assertTrue("the reopened report is still open", coordinator.isUiOpen)
        invoke()
        assertEquals("no second report beside the reopened one", 2, launches.size)

        coordinator.onUiClosed(DismissType.SUBMIT) // the reopened screen's own close
        assertFalse(coordinator.isUiOpen)
    }

    @Test
    fun `another window resuming during the capture keeps the shot`() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val shot = File.createTempFile("extra", ".png")
        holdExtra = true
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(first)
        Robolectric.buildActivity(Activity::class.java).setup() // multi-window: the first one stays resumed

        extraCallback!!(ScreenCapturer.Result.Saved(shot))
        assertEquals(shot, launches.single().resume?.extraScreenshot)
        assertTrue(shot.exists())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `the captured window pausing and coming back during the capture still spoils the shot`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val shot = File.createTempFile("extra", ".png")
        holdExtra = true
        coordinator.beginExtraCapture(request)
        coordinator.onCaptureTapped(host.get())
        host.pause().resume()

        extraCallback!!(ScreenCapturer.Result.Saved(shot))
        assertTrue(launches.isEmpty())
        assertEquals(listOf(shot), deleted)
        assertTrue(coordinator.isCapturing)
    }

    /** A report open over the host, which the report screen now covers (paused). */
    private fun openReport(): ActivityController<Activity> {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        invoke()
        host.pause()
        return host
    }

    /** The report screen, the consent's host: an SDK activity, never a host screen. */
    private fun form(): Activity = Robolectric.buildActivity(FakeSdkActivity::class.java).setup().get()

    private fun beginRecording(form: Activity): Boolean =
        coordinator.beginRecording(form, request, onStarted = { recordingEvents += "started" }, onNotStarted = { recordingEvents += "notStarted:$it" })

    private fun video(name: String = "video.mp4"): File = File(app.cacheDir, name).apply { writeBytes(byteArrayOf(1)) }

    /** Consent given, the report screen stepped aside, the host back on screen. */
    private fun recordingOverHost(): Pair<ActivityController<Activity>, RecordingListener> {
        val host = openReport()
        assertTrue(beginRecording(form()))
        val listener = fakeRecorder.listeners.single()
        listener.onStarted(1_000)
        coordinator.onUiClosed(DismissType.ADD_ATTACHMENT)
        host.resume()
        return host to listener
    }

    @Test
    fun `a recording asks the recorder over the report screen for at most a minute and silences invocations`() {
        openReport()
        val form = form()
        val changes = modeChanges
        assertTrue(beginRecording(form))
        assertEquals(listOf(form), fakeRecorder.hosts)
        assertEquals(60_000L, fakeRecorder.maxDuration)
        assertTrue(coordinator.isRecording)
        assertTrue(coordinator.isSteppedAside)
        assertTrue(modeChanges > changes)
        invoke()
        assertEquals(1, launches.size)
    }

    @Test
    fun `a declined consent ends the mode and leaves the report screen open`() {
        openReport()
        beginRecording(form())
        fakeRecorder.listeners.single().onNotStarted(refused = true)
        assertEquals(listOf("notStarted:true"), recordingEvents)
        assertFalse(coordinator.isSteppedAside)
        assertTrue("the report screen is still open", coordinator.isUiOpen)
        assertTrue(recordingOverlayCalls.none { it == "show" })
    }

    @Test
    fun `a started recording steps the report aside and Stop reopens it with the video`() {
        val (host, listener) = recordingOverHost()
        assertEquals(listOf("started"), recordingEvents)
        assertEquals(1_000L, coordinator.recordingStartedAt)
        assertEquals(1, recordingOverlayCalls.count { it == "show" })
        invoke()
        assertEquals("no second report while recording", 1, launches.size)

        coordinator.onRecordingStopTapped(host.get())
        coordinator.onRecordingStopTapped(host.get())
        assertEquals("a double tap stops once", 1, fakeRecorder.stops)
        val file = video()
        listener.onFinished(file, RecordingStopReason.REQUESTED)

        val reopen = launches.last()
        assertEquals(2, launches.size)
        val resume = reopen.resume!!
        assertEquals(file, resume.recording)
        assertFalse(resume.recordingFailed)
        assertEquals(request.state, resume.state)
        assertEquals(ReportType.BUG, reopen.reportType)
        assertEquals(host.get(), launchedFrom.last())
        assertTrue(recordingOverlayCalls.contains("hideAll"))
        assertFalse(coordinator.isSteppedAside)
        assertTrue("the reopened report counts as open", coordinator.isUiOpen)
    }

    @Test
    fun `a recording finished in background comes back on the next host screen`() {
        val (host, listener) = recordingOverHost()
        host.pause()
        assertTrue(recordingOverlayCalls.contains("hide"))
        host.stop()
        listener.onFinished(video(), RecordingStopReason.LIMIT)
        assertEquals("never a start from the background", 1, launches.size)
        host.restart()
        host.resume()
        assertEquals(2, launches.size)
        assertNotNull(launches.last().resume!!.recording)
    }

    @Test
    fun `a recording the system stopped before the report stepped aside still comes back`() {
        val host = openReport()
        beginRecording(form())
        val listener = fakeRecorder.listeners.single()
        listener.onStarted(1_000)
        listener.onFinished(video(), RecordingStopReason.SYSTEM)
        assertEquals(1, launches.size)
        coordinator.onUiClosed(DismissType.ADD_ATTACHMENT)
        host.resume()
        assertEquals(2, launches.size)
        assertNotNull(launches.last().resume!!.recording)
    }

    @Test
    fun `a recording that produced nothing reopens the report with the failure`() {
        val (_, listener) = recordingOverHost()
        listener.onFinished(null, RecordingStopReason.FAILED)
        val resume = launches.last().resume!!
        assertNull(resume.recording)
        assertTrue(resume.recordingFailed)
    }

    @Test
    fun `a report screen closed during the consent stops the recording and drops what comes late`() {
        openReport()
        beginRecording(form())
        coordinator.onUiClosed(DismissType.CANCEL)
        assertEquals(1, fakeRecorder.stops)
        assertFalse(coordinator.isUiOpen)
        assertFalse(coordinator.isRecording)

        val late = video("late.mp4")
        fakeRecorder.listeners.single().onStarted(2_000)
        fakeRecorder.listeners.single().onFinished(late, RecordingStopReason.REQUESTED)
        assertTrue(deleted.contains(late))
        assertEquals(1, launches.size)
        assertTrue(recordingEvents.isEmpty())
    }

    @Test
    fun `disabling FeedbackKit during a recording drops it and hands the report back for a cancel`() {
        val (_, listener) = recordingOverHost()
        assertEquals(request, coordinator.abandonRecording())
        assertEquals(1, fakeRecorder.stops)
        assertFalse(coordinator.isUiOpen)
        assertTrue(recordingOverlayCalls.contains("hideAll"))
        val late = video("late.mp4")
        listener.onFinished(late, RecordingStopReason.REQUESTED)
        assertTrue(deleted.contains(late))
        assertEquals(1, launches.size)
    }

    @Test
    fun `disabling FeedbackKit during the consent leaves the open report screen to close itself`() {
        openReport()
        beginRecording(form())
        assertNull(coordinator.abandonRecording())
        assertEquals(1, fakeRecorder.stops)
        assertTrue("the report screen reports its own close", coordinator.isUiOpen)
        assertEquals("the waiting report screen hears it at once", listOf("notStarted:false"), recordingEvents)
        fakeRecorder.listeners.single().onNotStarted(refused = false) // the recorder's own late answer
        assertEquals("and only once", listOf("notStarted:false"), recordingEvents)
    }

    @Test
    fun `a report that cannot reopen after the recording is dropped as a cancel`() {
        val (_, listener) = recordingOverHost()
        launcherThrows = true
        val file = video()
        listener.onFinished(file, RecordingStopReason.REQUESTED)
        assertTrue(deleted.contains(file))
        assertEquals(listOf(request), recordingAbandoned)
        assertFalse(coordinator.isUiOpen)
    }

    @Test
    fun `no recording without a recorder, with the SDK off or beside the screenshot capture mode`() {
        openReport()
        val form = form()
        recorderPresent = false
        assertFalse(beginRecording(form))
        recorderPresent = true
        captureAllowed = false
        assertFalse(beginRecording(form))
        captureAllowed = true
        assertTrue(coordinator.beginExtraCapture(request))
        assertFalse(beginRecording(form))
        assertTrue(fakeRecorder.hosts.isEmpty())
    }

    @Test
    fun `no extra screenshot while a recording runs`() {
        openReport()
        beginRecording(form())
        assertFalse(coordinator.beginExtraCapture(request))
    }

    @Test
    fun `auto recording ends before a manual recording asks for its projection`() {
        openReport()
        var autoStopsAtRecord = -1
        fakeRecorder.onRecord = { autoStopsAtRecord = autoStops }
        assertTrue(beginRecording(form()))
        assertEquals("one projection at a time", 1, autoStopsAtRecord)
    }

    @Test
    fun `without a recorder auto recording is left running`() {
        openReport()
        recorderPresent = false
        assertFalse(beginRecording(form()))
        assertEquals("auto recording keeps running when nothing replaces it", 0, autoStops)
    }

    @Test
    fun `disabling FeedbackKit while the report steps aside hands it back once it has closed`() {
        openReport()
        beginRecording(form())
        fakeRecorder.listeners.single().onStarted(1_000)
        assertNull("the report screen is closing with ADD_ATTACHMENT", coordinator.abandonRecording())
        assertEquals(1, fakeRecorder.stops)
        assertTrue(recordingAbandoned.isEmpty())

        coordinator.onUiClosed(DismissType.ADD_ATTACHMENT)
        assertTrue("after the host has heard ADD_ATTACHMENT", recordingAbandoned.isEmpty())
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(request), recordingAbandoned)
        assertFalse(coordinator.isUiOpen)
        val late = video("late.mp4")
        fakeRecorder.listeners.single().onFinished(late, RecordingStopReason.REQUESTED)
        assertTrue(deleted.contains(late))
        assertEquals(1, launches.size)
    }

    @Test
    fun `a report closed as a cancel after its recording ended deletes the video`() {
        openReport()
        beginRecording(form())
        val listener = fakeRecorder.listeners.single()
        listener.onStarted(1_000)
        val file = video()
        listener.onFinished(file, RecordingStopReason.SYSTEM)
        coordinator.onUiClosed(DismissType.CANCEL)
        assertTrue(deleted.contains(file))
        assertFalse(coordinator.isRecording)
        assertFalse(coordinator.isUiOpen)
    }

    @Test
    fun `a recording that ended before the report stepped aside reopens at once beside another resumed host`() {
        val host = openReport()
        beginRecording(form())
        val listener = fakeRecorder.listeners.single()
        listener.onStarted(1_000)
        listener.onFinished(video(), RecordingStopReason.SYSTEM)
        host.resume() // multi-window: a host screen is already resumed when the report closes
        assertEquals("no reopen while the report screen is still open", 1, launches.size)
        coordinator.onUiClosed(DismissType.ADD_ATTACHMENT)
        assertEquals(2, launches.size)
        assertEquals(host.get(), launchedFrom.last())
        assertNotNull(launches.last().resume!!.recording)
    }

    @Test
    fun `an ADD_ATTACHMENT close before the recording started ends the mode like any other close`() {
        openReport()
        beginRecording(form())
        coordinator.onUiClosed(DismissType.ADD_ATTACHMENT)
        assertEquals(1, fakeRecorder.stops)
        assertFalse(coordinator.isRecording)
        assertFalse("never stuck open", coordinator.isUiOpen)
        fakeRecorder.listeners.single().onStarted(2_000)
        assertTrue(recordingOverlayCalls.none { it == "show" })
    }

    @Test
    fun `the host that becomes current in multi-window gets the Stop control`() {
        val (host, _) = recordingOverHost()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        val shown = recordingOverlayCalls.count { it == "show" }
        second.pause() // the first host, still resumed, becomes current again
        assertEquals(listOf("hide", "show"), recordingOverlayCalls.takeLast(2))
        assertEquals(shown + 1, recordingOverlayCalls.count { it == "show" })
        assertEquals(host.get(), tracker.currentActivity)
    }

    @Test
    fun `a recorder that answers inside record is not reported as a started recording`() {
        openReport()
        val recorder = object : ScreenRecorder by fakeRecorder {
            override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession {
                listener.onNotStarted(refused = false)
                return RecordingSession {}
            }
        }
        val sync = InvocationCoordinator(
            tracker = tracker,
            capture = { _, callback -> callback(captureResult) },
            isEnabled = { true },
            onInvoke = {},
            launcher = { _, _ -> },
            logger = SdkLogger(LogLevel.NONE),
            deleteFile = {},
            recorder = { recorder },
        )
        assertFalse(sync.beginRecording(form(), request, {}, { recordingEvents += "notStarted:$it" }))
        assertEquals(listOf("notStarted:false"), recordingEvents)
        assertFalse(sync.isRecording)
    }

    private val crash = ProactiveInfo(ProactiveTrigger.CRASH, "2026-09-28T10:00:00Z", "java.lang.IllegalStateException", "boom")

    @Test
    fun `the proactive prompt opens over the current host screen, without a screenshot, a clip or onInvoke`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        autoRecordingOn = true
        assertTrue(coordinator.showProactive(crash))
        assertEquals(listOf("launch"), calls)
        assertTrue(launchedFrom.single() === activity)
        val launch = launches.single()
        assertEquals(InvocationSource.PROACTIVE, launch.source)
        assertEquals(ReportType.FRUSTRATING_EXPERIENCE, launch.reportType)
        assertEquals(crash, launch.proactive)
        assertNull(launch.screenshot)
        assertFalse(launch.screenshotRequested)
        assertNull(launch.autoRecordingToken)
        assertTrue(coordinator.isUiOpen)
        assertEquals(listOf(true), uiOpenChanges)
    }

    @Test
    fun `an invocation while the prompt is open is ignored until it closes`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        coordinator.showProactive(crash)
        invoke()
        assertEquals(1, launches.size)
        coordinator.onUiOpened()
        coordinator.onUiClosed(DismissType.CANCEL)
        invoke()
        assertEquals(2, launches.size)
    }

    @Test
    fun `the prompt waits while no host screen shows, bug reporting is off or a report is open`() {
        assertFalse("no host screen", coordinator.showProactive(crash))
        Robolectric.buildActivity(Activity::class.java).setup()
        enabled = false
        assertFalse(coordinator.showProactive(crash))
        enabled = true
        invoke()
        assertFalse("a report is open", coordinator.showProactive(crash))
        assertEquals(1, launches.size)
    }

    @Test
    fun `the prompt waits while a report stepped aside for a screenshot`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(coordinator.beginExtraCapture(request))
        assertFalse(coordinator.showProactive(crash))
        assertTrue(launches.isEmpty())
    }

    @Test
    fun `a launcher that throws leaves the next invocation accepted`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        launcherThrows = true
        assertFalse(coordinator.showProactive(crash))
        assertFalse(coordinator.isUiOpen)
        invoke()
        assertEquals(1, launches.size)
    }
}
