package io.github.feedbacklib.android.recording

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Manual recording end to end: the form's tile → consent → Stop over the host → the form with the video. */
@RunWith(AndroidJUnit4::class)
class RecordingFlowDeviceTest {

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

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail(message)
            Thread.sleep(100)
        }
    }

    /** Waits for [tag]; until the report screen exists there is no Compose hierarchy and fetching throws. */
    private fun waitForTag(tag: String) = compose.waitUntil(10_000) {
        runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }

    private fun openFormWithTile() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        FeedbackKit.show()
        waitForTag(TestSdk.COMMENT)
        compose.onNodeWithTag(TestSdk.COMMENT).performTextInput("Recorded")
        // The recorder is found in the background right after build(): the tile follows.
        waitForTag(TestSdk.RECORD_SCREEN)
    }

    @Test
    fun aRecordingFromTheFormComesBackAsAnAttachment() {
        ActivityScenario.launch(AnimatedActivity::class.java).use {
            openFormWithTile()
            compose.onNodeWithTag(TestSdk.RECORD_SCREEN).performScrollTo().performClick()
            ScreenCaptureConsent.accept()
            waitUntil("the report did not step aside") { TestSdk.dismissals.isNotEmpty() }
            assertEquals(listOf(DismissType.ADD_ATTACHMENT to ReportType.BUG), TestSdk.dismissals.toList())

            val stop = device.wait(Until.findObject(By.text("Stop")), 10_000)
            assertNotNull("no Stop control over the host", stop)
            Thread.sleep(2_000)
            stop.click()

            // By the strip's remove button, not the thumbnail: its frame may still be loading.
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(TestSdk.REMOVE_RECORDING, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(TestSdk.COMMENT).assertTextContains("Recorded")
            assertEquals("reopening reports nothing", 1, TestSdk.dismissals.size)

            val video = TestSdk.draftFiles(app).single { it.name.startsWith("recording-") && it.name.endsWith(".mp4") }
            compose.onNodeWithTag(TestSdk.attachment(video.name)).assertExists()
            // A valid video, not its duration: an emulator's software encoder yields sparse frames.
            val info = Mp4Probe.read(video)
            assertEquals(1, info.videoTracks)
            assertEquals(0, info.audioTracks)
            assertTrue("samples ${info.videoSamples}", info.videoSamples >= 2)
            assertTrue("duration ${info.durationUs}", info.durationUs in 1..60_000_000)

            TestSdk.discardReport(compose)
        }
    }

    @Test
    fun aDeclinedConsentKeepsTheFormAsItWas() {
        ActivityScenario.launch(AnimatedActivity::class.java).use {
            openFormWithTile()
            compose.onNodeWithTag(TestSdk.RECORD_SCREEN).performScrollTo().performClick()
            ScreenCaptureConsent.decline()
            // The tile comes back enabled once the refusal reached the form.
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag(TestSdk.RECORD_SCREEN) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(TestSdk.COMMENT).assertTextContains("Recorded")
            assertTrue("the report never stepped aside", TestSdk.dismissals.isEmpty())
            assertTrue(TestSdk.draftFiles(app).none { it.name.startsWith("recording-") })

            TestSdk.discardReport(compose)
        }
    }
}
