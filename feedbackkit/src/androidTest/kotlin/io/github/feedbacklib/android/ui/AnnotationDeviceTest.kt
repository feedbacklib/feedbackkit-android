package io.github.feedbacklib.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.invoke.InvocationSource
import io.github.feedbacklib.android.internal.invoke.LaunchRequest
import io.github.feedbacklib.android.internal.ui.AnnotationUiState
import io.github.feedbacklib.android.internal.ui.FeedbackActivity
import io.github.feedbacklib.android.internal.ui.ReportDraftViewModel
import io.github.feedbacklib.android.internal.ui.screens.ReportTestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The editor end to end on a device: thumbnail → edit → Done → the draft file is the edited PNG. */
@RunWith(AndroidJUnit4::class)
class AnnotationDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        File(app.filesDir, "feedbackkit").deleteRecursively()
        TestImageProvider.dir(app.cacheDir).deleteRecursively()
        FeedbackKit.Builder(app, "annotate-cid").build()
        FeedbackKit.identifyUser("tester@example.com", null)
        BugReporting.setReportTypes(ReportType.BUG)
    }

    @After
    fun tearDown() = FeedbackKit.resetForTests()

    private fun checkerboardScreenshot(): File = File(app.cacheDir, "feedbackkit/capture/annotate.png").apply {
        parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        for (y in 0 until 400) for (x in 0 until 200) bitmap.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun draftDir(): File = File(app.filesDir, "feedbackkit/drafts").listFiles()!!.single()

    private fun savedScreenshot(): Bitmap = BitmapFactory.decodeFile(File(draftDir(), "screenshot.png").path)

    private fun openEditor(): ActivityScenario<FeedbackActivity> {
        val scenario = ActivityScenario.launch<FeedbackActivity>(
            FeedbackActivity.intent(app, LaunchRequest(checkerboardScreenshot(), false, "DeviceTestHost", InvocationSource.MANUAL, ReportType.BUG)),
        )
        editAttachment("screenshot.png")
        return scenario
    }

    /** Taps the thumbnail by its tag: its content description only appears once the thumbnail is decoded. */
    private fun editAttachment(fileName: String) {
        waitForTag(ReportTestTags.attachment(fileName))
        compose.onNodeWithTag(ReportTestTags.attachment(fileName)).performClick()
        waitForTag(ReportTestTags.ANNOTATION_CANVAS)
    }

    /** Right after the launch the screen may have no Compose hierarchy yet: that counts as "not there yet". */
    private fun waitForTag(tag: String, timeoutMillis: Long = 5_000) = compose.waitUntil(timeoutMillis) {
        try {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        } catch (e: IllegalStateException) {
            false // "No compose hierarchies found in the app"
        }
    }

    private fun saveAndWaitForTheForm() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Undo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ReportTestTags.ANNOTATION_DONE).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ReportTestTags.COMMENT_FIELD).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ActivityScenario<FeedbackActivity>.viewModel(): ReportDraftViewModel {
        var vm: ReportDraftViewModel? = null
        onActivity { vm = ViewModelProvider(it)[ReportDraftViewModel::class.java] }
        return vm!!
    }

    private fun ActivityScenario<FeedbackActivity>.editor(): AnnotationUiState? {
        var editor: AnnotationUiState? = null
        onActivity { editor = ViewModelProvider(it)[ReportDraftViewModel::class.java].uiState.annotation }
        return editor
    }

    @Test
    fun aPenStrokeIsSavedIntoTheScreenshotAtFullSize() {
        openEditor().use {
            compose.onNodeWithTag(ReportTestTags.ANNOTATION_CANVAS).performTouchInput {
                swipe(Offset(centerX - width / 8f, centerY), Offset(centerX + width / 8f, centerY), 500)
            }
            saveAndWaitForTheForm()

            val saved = savedScreenshot()
            assertEquals(200, saved.width)
            assertEquals(400, saved.height)
            val reds = (190..210).sumOf { y -> (0 until 200).count { x -> saved.getPixel(x, y) == PenColor.RED.argb } }
            assertTrue("red pixels around the middle row: $reds", reds >= 20)
        }
    }

    @Test
    fun aBlurTurnsTheCheckerboardIntoGreyBlocks() {
        openEditor().use {
            compose.onNodeWithContentDescription("Blur").performClick()
            compose.onNodeWithTag(ReportTestTags.ANNOTATION_CANVAS).performTouchInput {
                swipe(Offset(centerX - width / 4f, centerY - height / 8f), Offset(centerX + width / 4f, centerY + height / 8f), 500)
            }
            saveAndWaitForTheForm()

            val saved = savedScreenshot()
            for (y in 195..205) for (x in 95..105) {
                val red = Color.red(saved.getPixel(x, y))
                assertTrue("($x, $y) = $red", red in 110..146)
            }
        }
    }

    /**
     * A camera photo stored sideways (EXIF orientation 6, ROTATE_90) from the gallery: the editor
     * shows it upright and Done saves it upright, as a PNG in place of the JPEG.
     */
    @Test
    fun aSidewaysGalleryJpegOpensUprightAndIsSavedUprightAsAPng() {
        // 200×100 as stored: red left half, blue right half. Upright (turned clockwise): red on top.
        File(TestImageProvider.dir(app.cacheDir).apply { mkdirs() }, "turned.jpg").apply {
            val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            Canvas(bitmap).drawRect(0f, 0f, 100f, 100f, Paint().apply { color = Color.RED })
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
        }
        val scenario = ActivityScenario.launch<FeedbackActivity>(
            FeedbackActivity.intent(app, LaunchRequest(null, false, "DeviceTestHost", InvocationSource.MANUAL, ReportType.BUG, screenshotRequested = false)),
        )
        scenario.use {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(ReportTestTags.COMMENT_FIELD).fetchSemanticsNodes().isNotEmpty() }
            val vm = scenario.viewModel()
            scenario.onActivity { vm.onGalleryImagePicked(TestImageProvider.uri("turned.jpg")) }
            var jpegName: String? = null
            compose.waitUntil(5_000) {
                scenario.onActivity { jpegName = vm.uiState.attachments.singleOrNull()?.fileName }
                jpegName != null
            }
            assertTrue(jpegName!!, jpegName!!.matches(Regex("gallery-\\d+\\.jpg")))

            editAttachment(jpegName!!)
            compose.waitUntil(5_000) { scenario.editor()?.preview != null }
            val editor = scenario.editor()!!
            assertEquals(100, editor.imageWidth)
            assertEquals(200, editor.imageHeight)
            val preview = editor.preview!!
            assertUpright(preview.asAndroidBitmap(), preview.width / 100f)

            // One short stroke across the middle, away from the pixels checked below.
            compose.onNodeWithTag(ReportTestTags.ANNOTATION_CANVAS).performTouchInput {
                swipe(Offset(centerX - width / 8f, centerY), Offset(centerX + width / 8f, centerY), 500)
            }
            saveAndWaitForTheForm()

            val pngName = jpegName!!.substringBeforeLast('.') + ".png"
            val files = draftDir().listFiles()!!.filter { it.isFile }.map { it.name }
            assertEquals(listOf(pngName), files)
            val png = File(draftDir(), pngName)
            val signature = png.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            assertTrue("saved as PNG", signature.contentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
            val saved = BitmapFactory.decodeFile(png.path)
            assertEquals(100, saved.width)
            assertEquals(200, saved.height)
            assertUpright(saved, 1f)
        }
    }

    /** Red on top, blue below; [scale] is bitmap pixels per image pixel. JPEG colours: approximately. */
    private fun assertUpright(bitmap: Bitmap, scale: Float) {
        fun pixel(x: Int, y: Int) = bitmap.getPixel((x * scale).toInt(), (y * scale).toInt())
        for ((x, y) in listOf(50 to 20, 20 to 60, 80 to 80)) {
            val p = pixel(x, y)
            assertTrue("($x, $y) should be red: ${Integer.toHexString(p)}", Color.red(p) > 200 && Color.blue(p) < 60)
        }
        for ((x, y) in listOf(50 to 180, 20 to 140, 80 to 120)) {
            val p = pixel(x, y)
            assertTrue("($x, $y) should be blue: ${Integer.toHexString(p)}", Color.blue(p) > 200 && Color.red(p) < 60)
        }
    }
}
