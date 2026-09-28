package io.github.feedbacklib.android.recording

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.recording.internal.RecorderRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Auto Screen Recording through FeedbackKit: the privacy notice and the consent over a showing screen,
 * an invocation whose report opens at once and gets the clip later, and a manual recording taking the
 * one projection. Name order: a declined consent holds for the rest of the process, so that test runs last.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AutoRecordingFlowDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        ScreenCaptureConsent.prepareDevice()
        TestSdk.ensureBuilt(app)
        TestSdk.dismissals.clear()
        File(app.filesDir, "feedbackkit/drafts").deleteRecursively()
    }

    /** Switched off, the session stops: no service may be left for the next test. */
    @After
    fun tearDown() {
        BugReporting.setAutoScreenRecordingEnabled(false)
        val deadline = System.currentTimeMillis() + 10_000
        while (RecorderRuntime.holdsService) {
            if (System.currentTimeMillis() > deadline) fail("the recording service outlived auto recording")
            Thread.sleep(100)
        }
    }

    /** Until the report screen exists there is no Compose hierarchy, and fetching throws. */
    private fun has(tag: String): Boolean = runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    /**
     * The app leaves the screen and comes back. Home, not moveToState(CREATED): ActivityScenario stops the
     * activity by starting its own empty activity in this process, so the app would never leave foreground.
     * Back through the shell: Android 10+ refuses an activity start from an app in background.
     */
    private fun toBackgroundAndBack(scenario: ActivityScenario<AnimatedActivity>) {
        device.pressHome()
        awaitState(scenario, Lifecycle.State.CREATED)
        val started = device.executeShellCommand("am start -W --activity-reorder-to-front -n ${app.packageName}/${AnimatedActivity::class.java.name}")
        // ActivityScenario matches its activity by the launch intent, so it misses this start: watch the screen.
        assertTrue("the app did not come back: $started", device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 10_000))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitState(scenario: ActivityScenario<AnimatedActivity>, state: Lifecycle.State) {
        val deadline = System.currentTimeMillis() + 10_000
        while (scenario.state != state) {
            if (System.currentTimeMillis() > deadline) fail("the app did not reach $state")
            Thread.sleep(100)
        }
    }

    private fun startAutoRecording() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        BugReporting.setAutoScreenRecordingEnabled(true)
        ScreenCaptureConsent.accept() // asked at once, the notice first: a screen is showing
    }

    /** Opens the report and waits for its clip: the placeholder first or not at all, then the attachment. */
    private fun invokeAndAwaitTheClip(): File {
        FeedbackKit.show()
        compose.waitUntil(10_000) { has(TestSdk.COMMENT) }
        // Stitching 30 s took up to 14 s on the emulator; the report is open all that time.
        compose.waitUntil(60_000) { !has(TestSdk.AUTO_CLIP_PENDING) }
        compose.waitUntil(5_000) { has(TestSdk.attachment(TestSdk.AUTO_RECORDING_FILE)) }
        return TestSdk.draftFiles(app).single { it.name == TestSdk.AUTO_RECORDING_FILE }
    }

    @Test
    fun anInvocationAttachesTheLastSecondsOfTheAutomaticRecording() {
        ActivityScenario.launch(AnimatedActivity::class.java).use {
            startAutoRecording()
            Thread.sleep(12_000)

            val clip = invokeAndAwaitTheClip()
            // A valid video, not its duration: an emulator's software encoder yields sparse frames.
            val info = Mp4Probe.read(clip)
            assertEquals(1, info.videoTracks)
            assertEquals(0, info.audioTracks)
            assertTrue("samples ${info.videoSamples}", info.videoSamples >= 2)
            assertTrue("clip lasts ${info.durationUs} µs", info.durationUs in 1..30_200_000)

            TestSdk.discardReport(compose)
        }
    }

    @Test
    fun aManualRecordingEndsTheAutomaticOneWhichAsksAgainOnTheNextForeground() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            startAutoRecording()
            // Long enough for frames: an emulator's software encoder can emit none in the first seconds.
            Thread.sleep(10_000)
            invokeAndAwaitTheClip()
            compose.onNodeWithTag(TestSdk.COMMENT).performTextInput("Both")

            // The manual recording asks for its own projection while the automatic one is released.
            compose.onNodeWithTag(TestSdk.RECORD_SCREEN).performScrollTo().performClick()
            ScreenCaptureConsent.accept()
            val stop = device.wait(Until.findObject(By.text("Stop")), 15_000)
            assertNotNull("the manual recording did not start", stop)
            Thread.sleep(2_000)
            stop.click()
            compose.waitUntil(20_000) { TestSdk.draftFiles(app).any { it.name.startsWith("recording-") } && has(TestSdk.COMMENT) }
            val manual = TestSdk.draftFiles(app).single { it.name.startsWith("recording-") && it.name.endsWith(".mp4") }
            compose.waitUntil(5_000) { has(TestSdk.attachment(manual.name)) }
            assertTrue(Mp4Probe.read(manual).videoSamples >= 2)
            TestSdk.discardReport(compose)

            assertFalse("not asked again right after the report closes", ScreenCaptureConsent.noticeAppears(3_000))
            toBackgroundAndBack(scenario)
            ScreenCaptureConsent.accept()
            Thread.sleep(10_000)
            val info = Mp4Probe.read(invokeAndAwaitTheClip())
            assertEquals(1, info.videoTracks)
            TestSdk.discardReport(compose)
        }
    }

    @Test
    fun theNoticeCancelledIsARefusalForTheRestOfTheProcess() {
        ActivityScenario.launch(AnimatedActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            BugReporting.setAutoScreenRecordingEnabled(true)
            ScreenCaptureConsent.cancelNotice()

            BugReporting.setAutoScreenRecordingEnabled(false)
            BugReporting.setAutoScreenRecordingEnabled(true)
            assertFalse("switched off and on: still declined", ScreenCaptureConsent.noticeAppears(3_000))
            toBackgroundAndBack(scenario)
            assertFalse("the next foreground: still declined", ScreenCaptureConsent.noticeAppears(3_000))
            assertFalse(RecorderRuntime.holdsService)
        }
    }
}
