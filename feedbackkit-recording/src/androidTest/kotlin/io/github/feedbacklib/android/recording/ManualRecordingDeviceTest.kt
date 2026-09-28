package io.github.feedbacklib.android.recording

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.feedbacklib.android.recording.internal.ProjectionSession
import io.github.feedbacklib.android.recording.internal.RecorderRuntime
import io.github.feedbacklib.android.recording.internal.SessionEvents
import io.github.feedbacklib.android.recording.internal.SingleFileSink
import io.github.feedbacklib.android.spi.RecordingStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A real MediaProjection session on the device: the privacy notice and the system consent tapped
 * through UiAutomator, a real MP4 out.
 */
@RunWith(AndroidJUnit4::class)
class ManualRecordingDeviceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val output = File(app.cacheDir, "feedbackkit/recording/test-manual.mp4")

    private class Events : SessionEvents {
        val started = CountDownLatch(1)
        val ended = CountDownLatch(1)
        @Volatile var refused: Boolean? = null
        @Volatile var reason: RecordingStopReason? = null
        @Volatile var usable = false
        @Volatile var startedAt = 0L
        @Volatile var finishedAt = 0L

        override fun started(startedAtMillis: Long) {
            startedAt = startedAtMillis
            started.countDown()
        }

        override fun notStarted(refused: Boolean) {
            this.refused = refused
            ended.countDown()
        }

        override fun finished(reason: RecordingStopReason, usable: Boolean) {
            this.reason = reason
            this.usable = usable
            finishedAt = SystemClock.elapsedRealtime()
            ended.countDown()
        }
    }

    @Before
    fun setUp() {
        RecorderRuntime.log = testLog
        ScreenCaptureConsent.prepareDevice()
        output.delete()
    }

    /** The session of the running test: stopped in [tearDown] whatever the test did. */
    private var live: ProjectionSession? = null

    /** A failed test must not leave a session or the service running into the next one. */
    @After
    fun tearDown() {
        live?.stop()
        live = null
        waitUntilReleased()
    }

    private fun start(scenario: ActivityScenario<AnimatedActivity>, events: Events, maxMillis: Long = 60_000): ProjectionSession {
        var session: ProjectionSession? = null
        scenario.onActivity { session = RecorderRuntime.start(it, SingleFileSink(app, output, maxMillis, testLog), events, testLog) }
        live = session
        return session!!
    }

    private fun awaitStarted(events: Events) {
        assertTrue("the recording did not start", events.started.await(20, TimeUnit.SECONDS))
        assertTrue("the mediaProjection service must run while recording", RecorderRuntime.holdsService)
    }

    /**
     * A valid video, not its length: a virtual display emits frames only on change and MediaRecorder
     * never repeats one, so on a slow encoder (the emulator's software one runs at ~4 fps) the file's
     * first-to-last frame span is shorter than the recording. [maxUs] still catches a runaway file.
     */
    private fun assertPlayable(maxUs: Long) {
        val info = Mp4Probe.read(output)
        assertEquals(1, info.videoTracks)
        assertEquals("no sound (spec §7)", 0, info.audioTracks)
        assertEquals("video/avc", info.videoMime)
        assertTrue("${info.videoSamples} video samples", info.videoSamples >= 2)
        assertTrue("duration ${info.durationUs} µs", info.durationUs in 1..maxUs)
        assertTrue("short side ${minOf(info.width, info.height)}", minOf(info.width, info.height) <= 720)
    }

    /**
     * Two separate times: how long the session recorded before it decided to stop (onStarted to
     * StopCapture), and how long the recorder then took to finalise the file (StopCapture to the
     * reported end). On the emulator's software encoder finalising alone can take seconds (the codec
     * stop stalls, then the writer fsyncs), so it has its own generous bound: a hung stop still fails.
     */
    private fun assertStopTimes(session: ProjectionSession, events: Events, minMillis: Long, maxMillis: Long) {
        val stopAt = session.stopIssuedAtMillis
        assertTrue("the session never issued a stop", stopAt > 0)
        val recorded = stopAt - events.startedAt
        assertTrue("recorded for $recorded ms before the stop", recorded in minMillis..maxMillis)
        val finalised = events.finishedAt - stopAt
        assertTrue("finalised in $finalised ms", finalised in 0..MAX_FINALISE_MILLIS)
    }

    private fun waitUntilReleased() {
        val deadline = System.currentTimeMillis() + 10_000
        while (RecorderRuntime.holdsService) {
            if (System.currentTimeMillis() > deadline) fail("the recording service outlived its session")
            Thread.sleep(100)
        }
    }

    @Test
    fun recordsAPlayableVideoWithoutSound() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val events = Events()
            val session = start(scenario, events)
            ScreenCaptureConsent.accept()
            awaitStarted(events)
            Thread.sleep(3_000)
            session.stop()
            assertTrue(events.ended.await(15, TimeUnit.SECONDS))
            assertEquals(RecordingStopReason.REQUESTED, events.reason)
            assertTrue(events.usable)
            assertPlayable(maxUs = 10_000_000)
            assertStopTimes(session, events, minMillis = 2_500, maxMillis = 10_000)
            waitUntilReleased()
        }
    }

    @Test
    fun aStopFromTheSystemFinalisesTheFile() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val events = Events()
            val session = start(scenario, events)
            ScreenCaptureConsent.accept()
            awaitStarted(events)
            Thread.sleep(2_000)
            session.stopProjectionForTest() // what the shade or the status bar chip does
            assertTrue(events.ended.await(15, TimeUnit.SECONDS))
            assertEquals(RecordingStopReason.SYSTEM, events.reason)
            assertTrue(events.usable)
            assertPlayable(maxUs = 10_000_000)
            assertStopTimes(session, events, minMillis = 1_500, maxMillis = 10_000)
            waitUntilReleased()
        }
    }

    @Test
    fun theLimitStopsTheFileByItself() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val events = Events()
            val session = start(scenario, events, maxMillis = 3_000)
            ScreenCaptureConsent.accept()
            awaitStarted(events)
            assertTrue("the limit did not stop the recording", events.ended.await(15, TimeUnit.SECONDS))
            assertEquals(RecordingStopReason.LIMIT, events.reason)
            assertTrue(events.usable)
            // The writer stops on the first frame past the limit: at 30 fps that is < 33 ms over, but a
            // software encoder on an emulator can leave a second between frames, so the bound is loose.
            assertPlayable(maxUs = 5_000_000)
            // The session's own wall-clock timer decides the stop: within a second of the limit.
            assertStopTimes(session, events, minMillis = 2_900, maxMillis = 4_000)
            waitUntilReleased()
        }
    }

    @Test
    fun aDeclinedConsentRecordsNothing() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val events = Events()
            start(scenario, events)
            ScreenCaptureConsent.decline()
            assertTrue(events.ended.await(15, TimeUnit.SECONDS))
            assertEquals(true, events.refused)
            assertFalse(output.exists())
            assertFalse(RecorderRuntime.holdsService)
        }
    }

    @Test
    fun thePrivacyNoticeComesFirstAndItsCancelRefuses() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val events = Events()
            start(scenario, events)
            ScreenCaptureConsent.awaitNotice()
            assertEquals("the notice waits for an answer", 1L, events.ended.count)
            assertFalse("no service before the notice is answered", RecorderRuntime.holdsService)
            ScreenCaptureConsent.cancelNotice()
            assertTrue(events.ended.await(15, TimeUnit.SECONDS))
            assertEquals(true, events.refused)
            assertEquals(1L, events.started.count)
            assertFalse(output.exists())
            assertFalse(RecorderRuntime.holdsService)
        }
    }

    private companion object {
        /** Stop to finalised file; seconds on an emulator's software encoder. */
        const val MAX_FINALISE_MILLIS = 8_000L
    }
}
