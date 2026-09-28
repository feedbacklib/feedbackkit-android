package io.github.feedbacklib.android.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.LocalReportSender
import io.github.feedbacklib.android.ProactiveReportingConfigs
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.capture.RedActivity
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.proactive.CrashMarker
import io.github.feedbacklib.android.internal.proactive.SessionStore
import io.github.feedbacklib.android.internal.ui.screens.ReportTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Proactive reporting on a device (English locale): a crash of the "previous run", the prompt, the report in the zip. */
@RunWith(AndroidJUnit4::class)
class ProactiveDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zipDir: File get() = app.getExternalFilesDir(LocalReportSender.DIRECTORY_NAME)!!
    private val store = SessionStore({ File(app.filesDir, "feedbackkit/session") }, SdkLogger())
    private val dismissals = CopyOnWriteArrayList<Pair<DismissType, ReportType>>()
    private val noDelay = ProactiveReportingConfigs.Builder().isEnabled(true).setModalDelayAfterDetection(0, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        File(app.filesDir, "feedbackkit").deleteRecursively()
        zipDir.deleteRecursively()
    }

    @After
    fun tearDown() = FeedbackKit.resetForTests()

    /** What a crash of the previous run leaves behind, then a build() as the host's Application would do it. */
    private fun startAfterACrash(configs: ProactiveReportingConfigs) {
        store.writeCrash(
            CrashMarker(System.currentTimeMillis() - 10_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: device test\n\tat com.example.Host.save(Host.kt:12)"),
        )
        BugReporting.setProactiveReportingConfigurations(configs)
        FeedbackKit.Builder(app, "proactive-cid").setInvocationEvents(InvocationEvent.NONE).build()
        FeedbackKit.identifyUser("tester@example.com", null)
        BugReporting.setOnDismissCallback { type, reportType -> dismissals += type to reportType }
    }

    /** For a wait that expects [tag]: before any Compose screen exists there is no hierarchy to look in yet. */
    private fun waitForTag(tag: String, timeoutMillis: Long = 10_000) = compose.waitUntil(timeoutMillis) {
        try {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        } catch (e: IllegalStateException) {
            false // "No compose hierarchies found in the app"
        }
    }

    /** For a check that nothing shows: no Compose hierarchy at all means no prompt. */
    private fun promptShown(): Boolean =
        try {
            compose.onAllNodesWithTag(ReportTestTags.PROACTIVE_PROMPT).fetchSemanticsNodes().isNotEmpty()
        } catch (e: IllegalStateException) {
            false // "No compose hierarchies found in the app"
        }

    /** The session files as the runtime has them, read on its serial sessionIo after its own writes. */
    private fun <T> onSessionIo(read: () -> T): T = runBlocking { FeedbackKitRuntime.current!!.onSessionIo(read) }

    private fun waitUntil(message: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail(message)
            Thread.sleep(100)
        }
    }

    @Test
    fun aCrashOfThePreviousRunAsksAndTheStoryReachesTheZip() {
        startAfterACrash(noDelay)
        ActivityScenario.launch(RedActivity::class.java).use {
            waitForTag(ReportTestTags.PROACTIVE_PROMPT)
            compose.onNodeWithText("Looks like something went wrong. Would you tell us what happened?").assertIsDisplayed()
            compose.onNodeWithTag(ReportTestTags.PROACTIVE_ACCEPT).performClick()
            waitForTag(ReportTestTags.COMMENT_FIELD, 5_000)
            compose.onNodeWithText("What happened?").assertIsDisplayed()
            compose.onNodeWithTag(ReportTestTags.ADD_SCREENSHOT).assertDoesNotExist()
            compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).performTextInput("It closed when I saved")
            compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).performClick()

            waitUntil("the report did not close with SUBMIT") { dismissals.toList() == listOf(DismissType.SUBMIT to ReportType.FRUSTRATING_EXPERIENCE) }
            waitUntil("the report was not delivered", 30_000) { zipDir.listFiles().orEmpty().any { it.extension == "zip" } }
            val zip = zipDir.listFiles()!!.single { it.extension == "zip" }
            val json = ZipFile(zip).use { file -> String(file.getInputStream(file.getEntry("report.json")).readBytes()) }
            assertTrue(json, json.contains("\"type\":\"FRUSTRATING_EXPERIENCE\""))
            assertTrue(json, json.contains("\"trigger\":\"CRASH\""))
            assertTrue(json, json.contains("\"exception\":\"java.lang.IllegalStateException\""))
            assertTrue(json, json.contains("\"comment\":\"It closed when I saved\""))
            assertTrue(json, json.contains("\"extended\":null"))
            assertFalse(json, json.contains("\"kind\":\"SCREENSHOT\""))
        }
    }

    @Test
    fun notNowClosesAsACancelAndTheNextCrashWithinTheGapStaysQuiet() {
        startAfterACrash(noDelay)
        ActivityScenario.launch(RedActivity::class.java).use { scenario ->
            waitForTag(ReportTestTags.PROACTIVE_PROMPT)
            compose.onNodeWithTag(ReportTestTags.PROACTIVE_DECLINE).performClick()
            waitUntil("the prompt did not close with CANCEL") { dismissals.toList() == listOf(DismissType.CANCEL to ReportType.FRUSTRATING_EXPERIENCE) }
            waitUntil("the prompt was not recorded") { onSessionIo { store.readProactive() }?.lastModalAt != null }
            val firstPrompt = onSessionIo { store.readProactive() }!!.lastModalAt

            // A new run within 24 hours, after another crash: the default gap keeps it quiet.
            FeedbackKit.resetForTests()
            startAfterACrash(ProactiveReportingConfigs.Builder().isEnabled(true).setModalDelayAfterDetection(0, TimeUnit.SECONDS).build())
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitUntil("the second marker was not consumed") { onSessionIo { store.readCrash() } == null }
            Thread.sleep(3_000)
            assertFalse("no second prompt within the gap", promptShown())
            waitUntil("the event the gap dropped is still pending") { onSessionIo { store.readProactive() }?.pending == null }
            // The gap kept it quiet: the second run detected its crash, yet no prompt was recorded after the first.
            assertEquals(firstPrompt, onSessionIo { store.readProactive() }?.lastModalAt)
        }
    }
}
