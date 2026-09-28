package io.github.feedbacklib.android.ui

import android.app.Activity
import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.view.ViewGroup
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.LocalReportSender
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.capture.RedActivity
import io.github.feedbacklib.android.capture.SecureActivity
import io.github.feedbacklib.android.internal.invoke.CaptureOverlayView
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

/** The extra screenshot round trip on a device: form → capture mode over the host → form with the screenshot. */
@RunWith(AndroidJUnit4::class)
class ExtraScreenshotDeviceTest {

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
        FeedbackKit.Builder(app, "extra-cid").setInvocationEvents(InvocationEvent.NONE).build()
        FeedbackKit.identifyUser("tester@example.com", null)
        BugReporting.setReportTypes(ReportType.BUG)
        BugReporting.setOnDismissCallback { type, reportType -> dismissals += type to reportType }
    }

    @After
    fun tearDown() = FeedbackKit.resetForTests()

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail(message)
            Thread.sleep(100)
        }
    }

    private fun openFormAndStepAside() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        FeedbackKit.show()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ReportTestTags.COMMENT_FIELD).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).performTextInput("Two screens")
        compose.onNodeWithTag(ReportTestTags.ADD_SCREENSHOT).performScrollTo().performClick()
        waitUntil("the report screen did not step aside") { dismissals.isNotEmpty() }
        assertEquals(listOf(DismissType.ADD_ATTACHMENT to ReportType.BUG), dismissals.toList())
    }

    /** Where the capture controls stand on the host, in window coordinates (those of the screenshot). */
    private fun <A : Activity> controlsBounds(scenario: ActivityScenario<A>): Rect {
        var bounds: Rect? = null
        waitUntil("the capture controls did not appear on the host") {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView as ViewGroup
                val panel = (0 until decor.childCount).map(decor::getChildAt).filterIsInstance<CaptureOverlayView>().singleOrNull()
                bounds = panel?.takeIf { it.isLaidOut && it.width > 0 && it.height > 0 }?.let { view ->
                    val location = IntArray(2).also(view::getLocationInWindow)
                    Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                }
            }
            bounds != null
        }
        return bounds!!
    }

    private fun extraScreenshotFile(): File? =
        File(app.filesDir, "feedbackkit/drafts").listFiles()?.singleOrNull()?.listFiles()?.singleOrNull { it.name.startsWith("extra-") }

    @Test
    fun anExtraScreenshotOfTheHostJoinsTheDraftWithoutTheControls() {
        ActivityScenario.launch(RedActivity::class.java).use { scenario ->
            openFormAndStepAside()
            val controls = controlsBounds(scenario)
            onView(withText("Capture")).perform(click())

            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Additional screenshot").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).assertTextContains("Two screens")
            assertEquals("reopening reports nothing", 1, dismissals.size)

            // The screenshot is the red host: where the dark panel stood, it is still red.
            val shot = BitmapFactory.decodeFile(extraScreenshotFile()!!.path)
            val inset = Rect(controls).apply { inset(controls.width() / 8, controls.height() / 8) }
            val points = listOf(
                controls.centerX() to controls.centerY(),
                inset.left to inset.top,
                inset.right to inset.top,
                inset.left to inset.bottom,
                inset.right to inset.bottom,
            )
            points.forEach { (x, y) ->
                val pixel = shot.getPixel(x, y)
                assertTrue("pixel ($x, $y) under the controls ${controls.toShortString()}: ${Integer.toHexString(pixel)}", Color.red(pixel) > 200 && Color.green(pixel) < 80 && Color.blue(pixel) < 80)
            }

            compose.onNodeWithText("Send").performClick()
            waitUntil("the report was not delivered") { zipDir.listFiles().orEmpty().any { it.extension == "zip" } }
            val zip = zipDir.listFiles()!!.single { it.extension == "zip" }
            val json = ZipFile(zip).use { file -> String(file.getInputStream(file.getEntry("report.json")).readBytes()) }
            assertTrue(json, json.contains("\"kind\":\"EXTRA_SCREENSHOT\""))
            assertTrue(json, json.contains("\"comment\":\"Two screens\""))
            waitUntil("the host did not hear SUBMIT for the same report") { dismissals.size == 2 }
            assertEquals(DismissType.SUBMIT to ReportType.BUG, dismissals[1])
        }
    }

    @Test
    fun aSecureHostGivesTheNoticeInsteadOfAScreenshot() {
        ActivityScenario.launch(SecureActivity::class.java).use {
            openFormAndStepAside()
            onView(withText("Capture")).perform(click())

            compose.waitUntil(10_000) { compose.onAllNodesWithTag(ReportTestTags.ATTACH_NOTICE).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(ReportTestTags.ATTACH_NOTICE).assertTextEquals("Screenshot unavailable for this screen")
            compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).assertTextContains("Two screens")
            assertEquals(null, extraScreenshotFile())
        }
    }

    @Test
    fun cancelReturnsToTheFormWithTheText() {
        ActivityScenario.launch(RedActivity::class.java).use {
            openFormAndStepAside()
            onView(withText("Cancel")).perform(click())

            compose.waitUntil(10_000) { compose.onAllNodesWithTag(ReportTestTags.COMMENT_FIELD).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(ReportTestTags.COMMENT_FIELD).assertTextContains("Two screens")
            compose.onAllNodesWithContentDescription("Additional screenshot").assertCountEquals(0)
            assertEquals(null, extraScreenshotFile())
            assertEquals("reopening reports nothing", 1, dismissals.size)
        }
    }
}
