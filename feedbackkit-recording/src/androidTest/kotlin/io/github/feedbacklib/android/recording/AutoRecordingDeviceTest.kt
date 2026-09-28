package io.github.feedbacklib.android.recording

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.feedbacklib.android.recording.internal.AutoSessionHandle
import io.github.feedbacklib.android.recording.internal.ProjectionRecorder
import io.github.feedbacklib.android.recording.internal.RecorderRuntime
import io.github.feedbacklib.android.spi.AutoRecordingListener
import io.github.feedbacklib.android.spi.AutoRecordingSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Auto Screen Recording on a device: real segments, a real stitch, consent through UiAutomator. */
@RunWith(AndroidJUnit4::class)
class AutoRecordingDeviceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val segments = File(app.cacheDir, "feedbackkit/recording/test-segments")
    private val clipFile = File(app.cacheDir, "feedbackkit/recording/test-clip.mp4")
    private var session: AutoRecordingSession? = null

    private class Events : AutoRecordingListener {
        val started = CountDownLatch(1)
        val ended = CountDownLatch(1)
        @Volatile var startedAt = 0L
        override fun onStarted() {
            startedAt = SystemClock.elapsedRealtime()
            started.countDown()
        }
        override fun onNotStarted(refused: Boolean) = ended.countDown()
        override fun onEnded() = ended.countDown()
    }

    @Before
    fun setUp() {
        RecorderRuntime.log = testLog
        ScreenCaptureConsent.prepareDevice()
        clipFile.delete()
    }

    /** A failed test must not leave a session or the service running into the next one. */
    @After
    fun tearDown() {
        session?.stop()
        session = null
        val deadline = System.currentTimeMillis() + 10_000
        while (RecorderRuntime.holdsService) {
            if (System.currentTimeMillis() > deadline) fail("the recording service outlived its session")
            Thread.sleep(100)
        }
    }

    private fun start(scenario: ActivityScenario<AnimatedActivity>, events: Events, segmentMillis: Long): AutoSessionHandle {
        val recorder = ProjectionRecorder(app, testLog, segmentMillis)
        scenario.onActivity { session = recorder.startAuto(it, segments, events) }
        ScreenCaptureConsent.accept()
        assertTrue("auto recording did not start", events.started.await(20, TimeUnit.SECONDS))
        return session as AutoSessionHandle
    }

    private fun clip(handle: AutoSessionHandle, windowMillis: Long, awaitSeconds: Long = 30): File {
        val done = CountDownLatch(1)
        var result: File? = null
        val askedAt = SystemClock.elapsedRealtime()
        handle.clip(clipFile, windowMillis) {
            result = it
            done.countDown()
        }
        assertTrue("the clip did not come", done.await(awaitSeconds, TimeUnit.SECONDS))
        // Data for the device table.
        Log.i(
            "FeedbackKitTest",
            "clip of $windowMillis ms made in ${SystemClock.elapsedRealtime() - askedAt} ms " +
                "(finalising the current segment ${handle.lastClipFinaliseMillis} ms, stitching ${handle.lastClipStitchMillis} ms); " +
                "longest segment seam ${handle.longestSeamMillis} ms",
        )
        assertNotNull("no clip was made", result)
        return result!!
    }

    /**
     * A valid video and an upper bound on its length, not an exact length: a virtual display emits
     * frames only on change, so on a slow encoder (the emulator's software one runs at ~4 fps) the
     * clip's first-to-last frame span is shorter than the time recorded. [maxUs] allows
     * [CLIP_TOLERANCE_US] over what may be in the clip: MP4 gives the last frame the length of the gap
     * before it.
     */
    private fun assertClip(file: File, maxUs: Long) {
        val info = Mp4Probe.read(file)
        assertEquals(1, info.videoTracks)
        assertEquals("no sound (spec §7)", 0, info.audioTracks)
        assertEquals("video/avc", info.videoMime)
        assertTrue("${info.videoSamples} video samples", info.videoSamples >= 2)
        assertTrue("clip lasts ${info.durationUs} µs, at most $maxUs µs", info.durationUs in 1..maxUs + CLIP_TOLERANCE_US)
    }

    private fun segmentFiles(): Int = segments.listFiles().orEmpty().count { it.name.startsWith("segment-") }

    @Test
    fun aClipOfManySegmentsKeepsOnlyTheWindow() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val handle = start(scenario, Events(), segmentMillis = 2_000)
            var most = 0
            repeat(22) { // 11 s: five segments and more, the ring has dropped the oldest
                most = maxOf(most, segmentFiles())
                Thread.sleep(500)
            }
            assertTrue("the ring keeps four segments, found $most", most in 1..4)
            assertClip(clip(handle, windowMillis = 5_000), maxUs = 5_000_000)
            assertEquals("a clip takes the segments it used (the next report never repeats them)", 0, segmentFiles())
        }
    }

    @Test
    fun theAutomaticClipIsAtMostThirtySeconds() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val handle = start(scenario, Events(), segmentMillis = 10_000)
            Thread.sleep(42_000)
            // Finalising the current segment and stitching took 14 s on the emulator's software encoder.
            assertClip(clip(handle, windowMillis = 30_000, awaitSeconds = 60), maxUs = 30_000_000)
        }
    }

    @Test
    fun pausedTimeIsNotInTheClip() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val events = Events()
            // Segments longer than the whole test: the ring never caps the clip, only the pause could lengthen it.
            val handle = start(scenario, events, segmentMillis = 20_000)
            Thread.sleep(4_000)
            handle.pause()
            val pausedAt = SystemClock.elapsedRealtime()
            Thread.sleep(6_000)
            val begunBeforeResume = handle.segmentsBegun
            val resumedAt = SystemClock.elapsedRealtime()
            assertTrue("the projection is alive, so recording goes on", handle.resume())
            Thread.sleep(4_000)
            assertEquals("resume started a new segment", begunBeforeResume + 1, handle.segmentsBegun)
            assertEquals("the paused segment and the resumed one", 2, segmentFiles())
            val clipAt = SystemClock.elapsedRealtime()
            val recordedUs = ((pausedAt - events.startedAt) + (clipAt - resumedAt)) * 1_000
            val pausedUs = (resumedAt - pausedAt) * 1_000
            val maxUs = recordedUs + HANDOFF_SLACK_US
            assertTrue("the bound ($maxUs µs) must leave the pause ($pausedUs µs) out", maxUs + CLIP_TOLERANCE_US < recordedUs + pausedUs)
            // About 8 s recorded over 14 s: the clip holds no more than the recorded time.
            assertClip(clip(handle, windowMillis = 60_000), maxUs = maxUs)
        }
    }

    @Test
    fun aProjectionStoppedByTheSystemCannotResume() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            val events = Events()
            val handle = start(scenario, events, segmentMillis = 2_000)
            handle.pause()
            handle.session.stopProjectionForTest()
            assertTrue("the end was not reported", events.ended.await(15, TimeUnit.SECONDS))
            assertFalse(handle.resume())
            assertEquals("the ended session deleted its segments", 0, segmentFiles())
            session = null
        }
    }

    private companion object {
        /** P11: what MP4 adds past the window — the last frame lasts as long as the gap before it. */
        const val CLIP_TOLERANCE_US = 200_000L

        /**
         * The recorded time is measured on the test thread: onStarted comes after the recorder started,
         * and pause and clip reach the session thread a moment after they are called.
         */
        const val HANDOFF_SLACK_US = 300_000L
    }
}
