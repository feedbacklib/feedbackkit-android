package io.github.feedbacklib.android.internal.recording

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.LogSink
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.ActivityTracker
import io.github.feedbacklib.android.spi.AutoRecordingListener
import io.github.feedbacklib.android.spi.AutoRecordingSession
import io.github.feedbacklib.android.spi.ClipCallback
import io.github.feedbacklib.android.spi.RecordingListener
import io.github.feedbacklib.android.spi.RecordingSession
import io.github.feedbacklib.android.spi.ScreenRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AutoRecordingControllerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lines = mutableListOf<String>()
    private val logger = SdkLogger(LogLevel.VERBOSE, LogSink { level, _, message, _ -> lines += "$level $message" })
    private val tracker = ActivityTracker(logger)
    private val fake = FakeAutoRecorder()
    private var available: ScreenRecorder? = fake
    private var known = true
    private val controller = AutoRecordingController({ available }, { known }, tracker, { File(app.cacheDir, "segments") }, { File(app.cacheDir, "clip.mp4") }, logger)

    private class FakeSession : AutoRecordingSession {
        val calls = mutableListOf<String>()
        var alive = true
        var clipResult: File? = null
        var window = 0L

        override fun pause() { calls += "pause" }
        override fun resume(): Boolean { calls += "resume"; return alive }
        override fun clip(output: File, windowMillis: Long, callback: ClipCallback) {
            calls += "clip"
            window = windowMillis
            callback.onClip(clipResult)
        }
        override fun stop() { calls += "stop" }
    }

    private class FakeAutoRecorder : ScreenRecorder {
        val hosts = mutableListOf<Activity>()
        val listeners = mutableListOf<AutoRecordingListener>()
        val sessions = mutableListOf<FakeSession>()

        override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession = error("not used")

        override fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession {
            hosts += host
            listeners += listener
            return FakeSession().also { sessions += it }
        }
    }

    @Before
    fun register() {
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.addListener(controller)
    }

    @After
    fun unregister() {
        tracker.removeListener(controller)
        app.unregisterActivityLifecycleCallbacks(tracker)
    }

    private fun screen(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()

    private fun ActivityController<Activity>.toBackgroundAndBack() {
        pause().stop()
        restart().resume()
    }

    @Test
    fun `switched on while a screen shows, it asks over that screen`() {
        val activity = screen().get()
        controller.setEnabled(true)
        assertEquals(listOf(activity), fake.hosts)
    }

    @Test
    fun `a missing recording artifact is reported once, and only once it is known`() {
        available = null
        known = false
        screen()
        controller.setEnabled(true)
        assertTrue(lines.none { it.contains("feedbackkit-recording") })
        known = true
        controller.setEnabled(true)
        controller.setEnabled(true)
        assertEquals(1, lines.count { it.startsWith("WARNING") && it.contains("feedbackkit-recording") })
    }

    @Test
    fun `the background pauses and the next foreground resumes`() {
        val activity = screen()
        controller.setEnabled(true)
        fake.listeners.single().onStarted()
        activity.toBackgroundAndBack()
        assertEquals(listOf("pause", "resume"), fake.sessions.single().calls)
    }

    @Test
    fun `a projection gone on return is replaced through a new consent`() {
        val activity = screen()
        controller.setEnabled(true)
        fake.listeners.single().onStarted()
        activity.pause().stop()
        fake.sessions.single().alive = false
        activity.restart().resume()
        assertEquals(listOf("pause", "resume", "stop"), fake.sessions[0].calls)
        assertEquals(2, fake.hosts.size)
    }

    @Test
    fun `the clip is the last thirty seconds, and there is none while the user decides`() {
        screen()
        controller.setEnabled(true)
        assertFalse(controller.clip { })
        fake.listeners.single().onStarted()
        val file = File(app.cacheDir, "made.mp4")
        fake.sessions.single().clipResult = file
        var got: File? = null
        assertTrue(controller.clip { got = it })
        assertEquals(30_000L, fake.sessions.single().window)
        assertEquals(file, got)
    }

    @Test
    fun `FeedbackKit's screen pauses the recording and its close resumes it`() {
        screen()
        controller.setEnabled(true)
        fake.listeners.single().onStarted()
        controller.onUiOpenChanged(true)
        controller.onUiOpenChanged(false)
        assertEquals(listOf("pause", "resume"), fake.sessions.single().calls)
    }

    @Test
    fun `answers from a session already stopped are ignored`() {
        screen()
        controller.setEnabled(true)
        val stale = fake.listeners.single()
        controller.setEnabled(false)
        assertEquals(listOf("stop"), fake.sessions[0].calls)
        controller.setEnabled(true)
        assertEquals(2, fake.hosts.size)
        stale.onStarted()
        stale.onEnded()
        assertTrue(fake.sessions[1].calls.isEmpty())
        fake.listeners[1].onStarted()
        assertTrue("the live session records", controller.clip { })
    }

    @Test
    fun `a declined consent is logged and never asked again`() {
        val activity = screen()
        controller.setEnabled(true)
        fake.listeners.single().onNotStarted(refused = true)
        activity.toBackgroundAndBack()
        controller.setEnabled(false)
        controller.setEnabled(true)
        assertEquals(1, fake.hosts.size)
        assertTrue(lines.any { it.startsWith("INFO") && it.contains("declined") })
    }

    @Test
    fun `a manual recording stops the automatic one, which asks again only on the next foreground`() {
        val activity = screen()
        controller.setEnabled(true)
        fake.listeners.single().onStarted()
        controller.onUiOpenChanged(true)
        controller.onManualRecording()
        assertEquals("stopped, not paused: a paused session still holds the projection", listOf("pause", "stop"), fake.sessions.single().calls)
        assertFalse(controller.clip { })
        fake.listeners.single().onEnded() // the recorder's own answer to the stop changes nothing
        controller.onUiOpenChanged(false)
        activity.pause().resume()
        assertEquals("not right after the report closes", 1, fake.hosts.size)
        activity.toBackgroundAndBack()
        assertEquals(2, fake.hosts.size)
    }

    @Test
    fun `a recorder that throws is logged and never reaches the host`() {
        val throwing = object : ScreenRecorder {
            override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession = error("not used")
            override fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession = error("recorder bug")
        }
        available = throwing
        screen()
        controller.setEnabled(true)
        assertTrue(lines.any { it.startsWith("ERROR") && it.contains("automatic screen recording") })
    }
}
