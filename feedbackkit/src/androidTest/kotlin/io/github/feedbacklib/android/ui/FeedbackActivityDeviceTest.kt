package io.github.feedbacklib.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.ExtendedBugReport
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.LocalReportSender
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.invoke.InvocationSource
import io.github.feedbacklib.android.internal.invoke.LaunchRequest
import io.github.feedbacklib.android.internal.ui.FeedbackActivity
import io.github.feedbacklib.android.internal.ui.screens.ReportTestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/** The real report screen end to end on a device (English locale): draft → queue → zip. */
@RunWith(AndroidJUnit4::class)
class FeedbackActivityDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zipDir: File get() = app.getExternalFilesDir(LocalReportSender.DIRECTORY_NAME)!!
    private val dismissals = CopyOnWriteArrayList<Pair<DismissType, ReportType>>()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        File(app.filesDir, "feedbackkit").deleteRecursively()
        zipDir.deleteRecursively()
        FeedbackKit.Builder(app, "ui-cid").build()
        FeedbackKit.identifyUser("tester@example.com", null)
        BugReporting.setOnDismissCallback { type, reportType -> dismissals += type to reportType }
    }

    @After
    fun tearDown() = FeedbackKit.resetForTests()

    private fun launch(request: LaunchRequest): ActivityScenario<FeedbackActivity> =
        ActivityScenario.launch(FeedbackActivity.intent(app, request))

    private fun screenshot(): File = File(app.cacheDir, "feedbackkit/capture/device-test.png").apply {
        parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail(message)
            Thread.sleep(100)
        }
    }

    @Test
    fun aBugGoesThroughMenuFormAndExtendedStepIntoAZip() {
        BugReporting.setExtendedBugReportState(ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS)
        val scenario = launch(LaunchRequest(screenshot(), false, "DeviceTestHost", InvocationSource.MANUAL))

        compose.onNodeWithText("Report a bug").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(ReportTestTags.attachment("screenshot.png")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).performTextInput("Saving crashes")
        compose.onNodeWithText("Next").performClick()
        // On a slow emulator the step change can trail the click (IME animations): type only once it is there.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ReportTestTags.STEPS_FIELD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ReportTestTags.STEPS_FIELD).performTextInput("Tap save")
        compose.onNodeWithTag(ReportTestTags.ACTUAL_FIELD).performTextInput("App closes")
        compose.onNodeWithTag(ReportTestTags.EXPECTED_FIELD).performTextInput("Changes saved")
        compose.onNodeWithText("Send").performClick()

        // The thanks screen lasts 1.5 s and closes itself; on a slow emulator the first poll can come
        // after it is gone, so the flow is proven by the SUBMIT close. ReportScreenTest covers that screen.
        waitUntil("the screen did not close") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(listOf(DismissType.SUBMIT to ReportType.BUG), dismissals.toList())

        waitUntil("the report was not delivered") { zipDir.listFiles().orEmpty().any { it.extension == "zip" } }
        val zip = zipDir.listFiles()!!.single { it.extension == "zip" }
        val json = ZipFile(zip).use { file -> String(file.getInputStream(file.getEntry("report.json")).readBytes()) }
        assertTrue(json, json.contains("\"comment\":\"Saving crashes\""))
        assertTrue(json, json.contains("\"steps\":\"Tap save\""))
        assertTrue(json, json.contains("\"email\":\"tester@example.com\""))
        assertTrue(json, json.contains("\"kind\":\"SCREENSHOT\""))
        waitUntil("the draft was not deleted") { File(app.filesDir, "feedbackkit/drafts").listFiles().isNullOrEmpty() }
    }

    @Test
    fun cancellingTypedTextAsksFirstAndReportsCancel() {
        BugReporting.setReportTypes(ReportType.FEEDBACK)
        val scenario = launch(LaunchRequest(null, true, "DeviceTestHost", InvocationSource.SHAKE))

        compose.onNodeWithText("Screenshot unavailable for this screen").assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).performTextInput("Half a thought")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Discard the report?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()

        waitUntil("the screen did not close") { scenario.state == Lifecycle.State.DESTROYED }
        assertEquals(listOf(DismissType.CANCEL to ReportType.FEEDBACK), dismissals.toList())
    }

    @Test
    fun rotationKeepsTheTypedText() {
        BugReporting.setReportTypes(ReportType.QUESTION)
        val scenario = launch(LaunchRequest(null, false, "DeviceTestHost", InvocationSource.MANUAL))
        compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).performTextInput("Where is export?")
        // Typed in before the rotation: a failure below is then about keeping the text, not entering it.
        compose.waitForIdle()
        compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).assertTextContains("Where is export?")

        // An open keyboard can send a stale edit into the recreated field (the working hypothesis).
        Espresso.closeSoftKeyboard()
        scenario.recreate()

        compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).assertTextContains("Where is export?")
        scenario.close()
    }
}
