package io.github.feedbacklib.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.report.DraftAttachment
import io.github.feedbacklib.android.internal.report.ExtendedFields
import io.github.feedbacklib.android.internal.report.ReportDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * The whole pipeline on a real device: draft → host handler → disk queue → WorkManager →
 * LocalReportSender → zip in the app's external files. No fakes below the facade.
 */
@RunWith(AndroidJUnit4::class)
class ReportPipelineDeviceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zipDir: File get() = app.getExternalFilesDir(LocalReportSender.DIRECTORY_NAME)!!

    @Before
    fun cleanState() {
        FeedbackKit.resetForTests()
        WorkManager.getInstance(app).cancelAllWork().result.get()
        File(app.filesDir, "feedbackkit").deleteRecursively()
        zipDir.deleteRecursively()
    }

    @After
    fun tearDown() = FeedbackKit.resetForTests()

    @Test
    fun submittedReportIsDeliveredAsAZipAndLeavesTheQueue() = runBlocking {
        FeedbackKit.onReportSubmitHandler { report ->
            report.addTag("e2e")
            report.appendConsoleLog("handler ran on ${Thread.currentThread().name}")
        }
        FeedbackKit.Builder(app, "e2e-cid").setSdkDebugLogsLevel(LogLevel.VERBOSE).build()
        FeedbackKit.addFileAttachment("host log line".toByteArray(), "host.log")
        val screenshot = File(app.cacheDir, "e2e-shot.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        val runtime = FeedbackKitRuntime.current!!

        val id = runtime.submitReport(
            ReportDraft(
                type = ReportType.BUG,
                email = "tester@example.com",
                comment = "Pipeline check",
                extended = ExtendedFields("open app", "nothing", "zip"),
                attachments = listOf(DraftAttachment(AttachmentKind.SCREENSHOT, screenshot, "shot.png", "image/png")),
                currentScreen = "ReportPipelineDeviceTest",
            ),
        ).await()
        assertNotNull("report was not queued", id)

        val zip = File(zipDir, "$id.zip")
        waitUntil("zip was not delivered by WorkManager") { zip.exists() }
        waitUntil("delivered report is still queued") { runtime.store.pending().isEmpty() }

        val entries = ZipFile(zip).use { file ->
            file.entries().toList().associate { it.name to file.getInputStream(it).readBytes() }
        }
        val json = String(entries.getValue("report.json"))
        assertTrue(json, json.contains("\"id\":\"$id\""))
        assertTrue(json, json.contains("\"cid\":\"e2e-cid\""))
        assertTrue(json, json.contains("\"tags\":[\"e2e\"]"))
        assertTrue(json, json.contains("\"steps\":\"open app\""))
        assertTrue(json, json.contains("\"apiLevel\":"))
        // Entry names are "attachments/<uuid>-<fileName>"; a UUID is 36 characters long.
        val attachmentNames = entries.keys
            .filter { it.startsWith("attachments/") }
            .map { it.removePrefix("attachments/").drop(UUID_LENGTH + 1) }
            .sorted()
        assertEquals(listOf("host.log", "shot.png"), attachmentNames)
        val hostLog = entries.entries.single { it.key.endsWith("-host.log") }.value
        assertEquals("host log line", String(hostLog))
    }

    private fun waitUntil(message: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(message)
            Thread.sleep(200)
        }
    }

    private companion object {
        const val UUID_LENGTH = 36
    }
}
