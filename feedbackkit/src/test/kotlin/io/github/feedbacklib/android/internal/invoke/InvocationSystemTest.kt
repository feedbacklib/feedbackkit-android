package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.RecordingButtonPosition
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.Config
import io.github.feedbacklib.android.internal.core.ProactiveSettings
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.proactive.ProactiveEvent
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import io.github.feedbacklib.android.internal.ui.FeedbackActivity
import io.github.feedbacklib.android.spi.AutoRecordingListener
import io.github.feedbacklib.android.spi.AutoRecordingSession
import io.github.feedbacklib.android.spi.RecordingListener
import io.github.feedbacklib.android.spi.RecordingSession
import io.github.feedbacklib.android.spi.ScreenRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class InvocationSystemTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val config = Config(enabled = true, logLevel = LogLevel.NONE, userEmail = null, userName = null)
    private val deleteFile: (File) -> Unit = { it.delete() }

    private val invocationSystem = InvocationSystem(
        app = app,
        config = { config },
        onInvoke = {},
        logger = SdkLogger(LogLevel.NONE),
        deleteFile = deleteFile,
    )

    @Test
    fun `purgeStaleCaptures deletes only files older than 24 hours`() {
        val dir = File(app.cacheDir, "feedbackkit/capture")
        dir.mkdirs()
        // A fresh file: process death can recreate FeedbackActivity pointing at exactly this file,
        // or its capture/report submission can still be in flight — it must survive.
        val fresh = File(dir, "fresh.png").apply { writeBytes(byteArrayOf(1)) }
        val old = File(dir, "old.png").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25))
        }

        invocationSystem.purgeStaleCaptures()

        assertTrue(fresh.exists())
        assertFalse(old.exists())
    }

    @Test
    fun `purgeStaleCaptures does not throw when the capture directory does not exist`() {
        invocationSystem.purgeStaleCaptures()
    }

    @Test
    fun `a refresh still queued when stop runs does nothing`() {
        var configReads = 0
        val system = InvocationSystem(app, { configReads++; config }, {}, SdkLogger(LogLevel.NONE), deleteFile)

        system.refresh()
        system.stop()
        ShadowLooper.idleMainLooper()

        assertEquals(0, configReads)
    }

    private fun children(activity: Activity): List<android.view.View> {
        val decor = activity.window.decorView as ViewGroup
        return (0 until decor.childCount).map { decor.getChildAt(it) }
    }

    @Test
    fun `capture mode puts the controls in place of the floating button until it ends`() {
        val withButton = config.copy(invocationEvents = setOf(InvocationEvent.FLOATING_BUTTON))
        val system = InvocationSystem(app, { withButton }, {}, SdkLogger(LogLevel.NONE), deleteFile)
        system.start()
        try {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            ShadowLooper.idleMainLooper()
            assertEquals(1, children(activity).filterIsInstance<FloatingButtonView>().size)

            assertTrue(system.beginExtraCapture(CaptureRequest("draft-1", ReportType.BUG, emptyMap(), null)))
            assertEquals(0, children(activity).filterIsInstance<FloatingButtonView>().size)
            assertEquals(1, children(activity).filterIsInstance<CaptureOverlayView>().size)

            system.coordinator.abandonExtraCapture()
            assertEquals(1, children(activity).filterIsInstance<FloatingButtonView>().size)
            assertEquals(0, children(activity).filterIsInstance<CaptureOverlayView>().size)
        } finally {
            system.stop()
        }
    }

    @Test
    fun `disabling FeedbackKit ends capture mode and hands the report back`() {
        var enabled = true
        val abandoned = mutableListOf<CaptureRequest>()
        val system = InvocationSystem(app, { config.copy(enabled = enabled) }, {}, SdkLogger(LogLevel.NONE), deleteFile, onCaptureAbandoned = { abandoned += it })
        system.start()
        try {
            Robolectric.buildActivity(Activity::class.java).setup()
            val request = CaptureRequest("draft-1", ReportType.BUG, emptyMap(), null)
            system.beginExtraCapture(request)

            enabled = false
            system.refresh()
            ShadowLooper.idleMainLooper()
            assertEquals(listOf(request), abandoned)
            assertFalse(system.coordinator.isCapturing)
        } finally {
            system.stop()
        }
    }

    @Test
    fun `the extra screenshot hides both the controls and the floating button and brings both back`() {
        val logger = SdkLogger(LogLevel.NONE)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = CaptureOverlay({ 0xFF1565C0.toInt() }, {}, {}, logger)
        val button = FloatingButtonDetector(FloatingButtonState(), {}, logger)
        overlay.show(activity)
        button.attach(activity)
        val panelView = children(activity).filterIsInstance<CaptureOverlayView>().single()
        val buttonView = children(activity).filterIsInstance<FloatingButtonView>().single()
        var atCapture: Pair<Int, Int>? = null
        val results = mutableListOf<ScreenCapturer.Result>()

        extraScreenshotCapture(overlay, button, { _, callback ->
            atCapture = panelView.visibility to buttonView.visibility
            callback(ScreenCapturer.Result.Failed)
        }, logger).capture(activity) { results += it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(View.INVISIBLE to View.INVISIBLE, atCapture)
        assertEquals(View.VISIBLE, panelView.visibility)
        assertEquals(View.VISIBLE, buttonView.visibility)
        assertEquals(listOf(ScreenCapturer.Result.Failed), results)
    }

    @Test
    fun `a host recreated for a rotation in capture mode gets the controls on its new instance`() {
        val system = InvocationSystem(app, { config }, {}, SdkLogger(LogLevel.NONE), deleteFile)
        system.start()
        try {
            val host = Robolectric.buildActivity(Activity::class.java).setup()
            val old = host.get()
            assertTrue(system.beginExtraCapture(CaptureRequest("draft-1", ReportType.BUG, emptyMap(), null)))
            assertEquals(1, children(old).filterIsInstance<CaptureOverlayView>().size)

            host.recreate() // pause, stop, destroy, then the new instance is created and resumed
            ShadowLooper.idleMainLooper()
            assertEquals(0, children(old).filterIsInstance<CaptureOverlayView>().size)
            assertEquals(1, children(host.get()).filterIsInstance<CaptureOverlayView>().size)
            assertTrue(system.coordinator.isCapturing)
        } finally {
            system.stop()
        }
    }

    @Test
    fun `purgeStaleCaptures also deletes old recordings that never reached a draft, not the auto recording segments`() {
        val dir = File(app.cacheDir, "feedbackkit/recording").apply { mkdirs() }
        val old = File(dir, "manual-old.mp4").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        }
        val fresh = File(dir, "manual-new.mp4").apply { writeBytes(byteArrayOf(1)) }
        val segments = File(dir, "segments").apply {
            mkdirs()
            setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        }
        invocationSystem.purgeStaleCaptures()
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(segments.isDirectory)
    }

    private class ListeningRecorder : ScreenRecorder {
        val listeners = mutableListOf<RecordingListener>()
        var stops = 0

        override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession {
            listeners += listener
            return RecordingSession { stops++ }
        }

        override fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession = error("not used")
    }

    @Test
    fun `the Stop control follows the position setting live and bug reporting switched off keeps the recording`() {
        var current = config
        val abandoned = mutableListOf<CaptureRequest>()
        val recorder = ListeningRecorder()
        val system = InvocationSystem(app, { current }, {}, SdkLogger(LogLevel.NONE), deleteFile, onCaptureAbandoned = { abandoned += it }, recorder = { recorder })
        system.start()
        try {
            val host = Robolectric.buildActivity(Activity::class.java).setup().get()
            val form = Robolectric.buildActivity(FakeSdkActivity::class.java).setup().get()
            val request = CaptureRequest("draft-1", ReportType.BUG, emptyMap(), null)
            assertTrue(system.beginRecording(form, request, {}, {}))
            recorder.listeners.single().onStarted(0)
            system.coordinator.onUiClosed(DismissType.ADD_ATTACHMENT)
            val control = { children(host).filterIsInstance<RecordingControlView>().single() }
            assertTrue("never on the SDK's own screen", children(form).none { it is RecordingControlView })
            assertEquals(Gravity.BOTTOM or Gravity.RIGHT, (control().layoutParams as FrameLayout.LayoutParams).gravity)

            current = current.copy(recordingButtonPosition = RecordingButtonPosition.TOP_LEFT)
            system.refresh()
            ShadowLooper.idleMainLooper()
            assertEquals(Gravity.TOP or Gravity.LEFT, (control().layoutParams as FrameLayout.LayoutParams).gravity)

            current = current.copy(bugReportingEnabled = false)
            system.refresh()
            ShadowLooper.idleMainLooper()
            assertTrue(system.coordinator.isRecording)
            assertEquals(0, recorder.stops)

            current = current.copy(enabled = false)
            system.refresh()
            ShadowLooper.idleMainLooper()
            assertEquals(listOf(request), abandoned)
            assertEquals(1, recorder.stops)
            assertTrue(children(host).none { it is RecordingControlView })
        } finally {
            system.stop()
        }
    }

    @Test
    fun `stop ends a recording and hands its report back for a cancel`() {
        val abandoned = mutableListOf<CaptureRequest>()
        val recorder = ListeningRecorder()
        val system = InvocationSystem(app, { config }, {}, SdkLogger(LogLevel.NONE), deleteFile, onCaptureAbandoned = { abandoned += it }, recorder = { recorder })
        system.start()
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val form = Robolectric.buildActivity(FakeSdkActivity::class.java).setup().get()
        val request = CaptureRequest("draft-1", ReportType.BUG, emptyMap(), null)
        assertTrue(system.beginRecording(form, request, {}, {}))
        recorder.listeners.single().onStarted(0)
        system.coordinator.onUiClosed(DismissType.ADD_ATTACHMENT)

        system.stop()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(request), abandoned)
        assertEquals(1, recorder.stops)
        assertFalse(system.coordinator.isRecording)
        assertTrue(children(host).none { it is RecordingControlView })
    }

    /** Auto Screen Recording's sessions, each with what was done to it. */
    private class AutoRecorder : ScreenRecorder {
        val listeners = mutableListOf<AutoRecordingListener>()
        val calls = mutableListOf<MutableList<String>>()

        override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession = error("not used")

        override fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession {
            listeners += listener
            val log = mutableListOf<String>().also { calls += it }
            return object : AutoRecordingSession {
                override fun pause() { log += "pause" }
                override fun resume(): Boolean { log += "resume"; return true }
                override fun clip(output: File, windowMillis: Long, callback: io.github.feedbacklib.android.spi.ClipCallback) = callback.onClip(null)
                override fun stop() { log += "stop" }
            }
        }
    }

    /** Auto recording on and recording over a host screen; [switchOff] then changes the config. */
    private fun autoRecordingSwitchedOffBy(switchOff: (Config) -> Config) {
        var current = config.copy(autoScreenRecording = true)
        val recorder = AutoRecorder()
        val system = InvocationSystem(app, { current }, {}, SdkLogger(LogLevel.NONE), deleteFile, recorder = { recorder }, recorderKnown = { true })
        system.resolveDirectories()
        system.start()
        try {
            Robolectric.buildActivity(Activity::class.java).setup()
            system.refresh()
            ShadowLooper.idleMainLooper()
            assertEquals("asked over the showing screen", 1, recorder.listeners.size)
            recorder.listeners.single().onStarted()

            current = switchOff(current)
            system.refresh()
            ShadowLooper.idleMainLooper()
            assertEquals(listOf("stop"), recorder.calls.single())

            current = current.copy(enabled = true, bugReportingEnabled = true, autoScreenRecording = true)
            system.refresh()
            ShadowLooper.idleMainLooper()
            assertEquals("on again: asked again", 2, recorder.listeners.size)
        } finally {
            system.stop()
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun `BugReporting setState DISABLED stops auto recording`() = autoRecordingSwitchedOffBy { it.copy(bugReportingEnabled = false) }

    @Test
    fun `FeedbackKit disable stops auto recording`() = autoRecordingSwitchedOffBy { it.copy(enabled = false) }

    @Test
    fun `switching auto recording off stops it`() = autoRecordingSwitchedOffBy { it.copy(autoScreenRecording = false) }

    private val crash = ProactiveInfo(ProactiveTrigger.CRASH, "2026-09-28T10:00:00Z", "java.lang.IllegalStateException", "boom")

    @Test
    fun `an offered event opens the prompt over the host after the delay and says it showed`() {
        val resolved = mutableListOf<Long?>()
        val system = InvocationSystem(app, { config.copy(proactive = ProactiveSettings(enabled = true)) }, {}, SdkLogger(LogLevel.NONE), deleteFile, onProactiveResolved = { _, at -> resolved += at })
        system.start()
        try {
            val host = Robolectric.buildActivity(Activity::class.java).setup()
            system.offerProactive(ProactiveEvent(crash, null, detectedAt = 1L))
            ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
            assertEquals(FeedbackActivity::class.java.name, shadowOf(host.get()).nextStartedActivity?.component?.className)
            assertEquals(1, resolved.filterNotNull().size)
        } finally {
            system.stop()
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun `an event offered after stop opens nothing`() {
        val system = InvocationSystem(app, { config.copy(proactive = ProactiveSettings(enabled = true, delayMillis = 0)) }, {}, SdkLogger(LogLevel.NONE), deleteFile)
        system.start()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        system.stop()
        system.offerProactive(ProactiveEvent(crash, null, detectedAt = 1L))
        ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
        assertNull(shadowOf(host.get()).nextStartedActivity)
    }

    @Test
    fun `a delay that ends after stop, before its cancel runs, opens nothing`() {
        val system = InvocationSystem(app, { config.copy(proactive = ProactiveSettings(enabled = true, delayMillis = 1_000)) }, {}, SdkLogger(LogLevel.NONE), deleteFile)
        system.start()
        val host = Robolectric.buildActivity(Activity::class.java).create().start().postCreate(null).resume()
        system.offerProactive(ProactiveEvent(crash, null, detectedAt = 1L))
        ShadowLooper.idleMainLooper() // the offer runs; the prompt's timer is queued
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2)) // the timer is due, nothing has run it yet
        system.stop() // its cancel is queued behind the timer
        ShadowLooper.idleMainLooper()
        assertNull(shadowOf(host.get()).nextStartedActivity)
    }
}
