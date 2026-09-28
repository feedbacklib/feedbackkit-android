package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.OnReportSubmitHandler
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.queue.ReportStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReportSubmitterTest {

    @TempDir
    lateinit var dir: File

    private val logger = SdkLogger(LogLevel.NONE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { ReportStore(File(dir, "reports"), logger, clock = { 1_000L }) }
    private var enabled = true
    private var scheduled = 0
    private var handler: OnReportSubmitHandler? = null
    private var appFiles: List<ReportStore.NewAttachment> = emptyList()
    private var ids = 0

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun submitter(
        timeoutMillis: Long = 5_000,
        schedule: () -> Unit = { scheduled++ },
        deviceInfo: () -> DeviceInfo = ::sampleDevice,
        appInfo: () -> AppInfo = ::sampleApp,
    ) = ReportSubmitter(
        cid = "cid-1",
        isEnabled = { enabled },
        store = store,
        scheduler = { schedule() },
        deviceInfo = deviceInfo,
        appInfo = appInfo,
        appFiles = { appFiles },
        submitHandler = { handler },
        handlerScope = scope,
        logger = logger,
        clock = { 1_000L },
        newId = { "id-${++ids}" },
        handlerTimeoutMillis = timeoutMillis,
    )

    private fun draft(attachments: List<DraftAttachment> = emptyList()) = ReportDraft(
        type = ReportType.BUG,
        email = "user@example.com",
        comment = "Broken",
        extended = ExtendedFields("steps", "actual", "expected"),
        attachments = attachments,
        currentScreen = "com.example.MainActivity",
    )

    private fun storedPayload(): ReportPayload =
        ReportJson.decode(store.pending().single().toPrepared().reportJson)

    @Test
    fun `queues the draft with context and schedules an upload`() = runBlocking {
        val id = submitter().submit(draft())

        assertEquals("id-1", id)
        val payload = storedPayload()
        assertEquals("cid-1", payload.cid)
        assertEquals("1970-01-01T00:00:01Z", payload.createdAt)
        assertEquals(ReportType.BUG, payload.type)
        assertEquals("user@example.com", payload.email)
        assertEquals("Broken", payload.comment)
        assertEquals(ExtendedFields("steps", "actual", "expected"), payload.extended)
        assertEquals(sampleDevice(), payload.device)
        assertEquals(sampleApp(), payload.app)
        assertEquals("com.example.MainActivity", payload.currentScreen)
        assertEquals(1, scheduled)
    }

    @Test
    fun `handler throwing a CancellationException does not drop the report`() = runBlocking {
        handler = OnReportSubmitHandler { throw java.util.concurrent.CancellationException("host future was cancelled") }
        val id = submitter().submit(draft())
        assertEquals("id-1", id)
        assertTrue(storedPayload().tags.isEmpty())
    }

    @Test
    fun `handler additions land in the report`() = runBlocking {
        val extra = File(dir, "extra.log").apply { writeText("extra") }
        handler = OnReportSubmitHandler { report ->
            report.addTag("beta")
            report.appendConsoleLog("state=ok")
            report.setUserAttribute("plan", "pro")
            report.setUserData("data")
            report.addFile(extra)
        }

        submitter().submit(draft())

        val payload = storedPayload()
        assertEquals(listOf("beta"), payload.tags)
        assertEquals(listOf("state=ok"), payload.consoleLog)
        assertEquals(mapOf("plan" to "pro"), payload.userAttributes)
        assertEquals("data", payload.userData)
        assertEquals(listOf("extra.log" to AttachmentKind.APP_FILE), payload.attachments.map { it.fileName to it.kind })
    }

    @Test
    fun `slow handler is abandoned at the timeout and the report is queued without its changes`() = runBlocking {
        handler = OnReportSubmitHandler { report ->
            Thread.sleep(2_000)
            report.addTag("too-late")
        }
        val started = System.nanoTime()

        submitter(timeoutMillis = 100).submit(draft())

        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 1_500, "submit took $elapsedMillis ms")
        assertTrue(storedPayload().tags.isEmpty())
    }

    @Test
    fun `throwing handler does not stop the report`() = runBlocking {
        handler = OnReportSubmitHandler { error("host bug") }
        submitter().submit(draft())
        assertTrue(storedPayload().tags.isEmpty())
    }

    @Test
    fun `disabled sdk queues nothing`() = runBlocking {
        enabled = false
        assertNull(submitter().submit(draft()))
        assertTrue(store.pending().isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun `draft attachments and host files are all stored, missing ones are skipped`() = runBlocking {
        val shot = File(dir, "shot.png").apply { writeBytes(byteArrayOf(1, 2)) }
        val gone = File(dir, "gone.png")
        appFiles = listOf(ReportStore.NewAttachment(AttachmentMeta("host", AttachmentKind.APP_FILE, "host.txt", "text/plain", 0)) { "h".byteInputStream() })

        submitter().submit(
            draft(
                listOf(
                    DraftAttachment(AttachmentKind.SCREENSHOT, shot, "shot.png", "image/png"),
                    DraftAttachment(AttachmentKind.EXTRA_SCREENSHOT, gone, "gone.png", "image/png"),
                ),
            ),
        )

        assertEquals(listOf("shot.png", "host.txt"), storedPayload().attachments.map { it.fileName })
    }

    @Test
    fun `a 30 MB screen recording is queued whole as mp4 video`() = runBlocking {
        val chunk = ByteArray(1024 * 1024) { it.toByte() }
        val video = File(dir, "recording-1.mp4").apply { outputStream().use { out -> repeat(30) { out.write(chunk) } } }

        submitter().submit(draft(listOf(DraftAttachment(AttachmentKind.SCREEN_RECORDING, video, video.name, "video/mp4"))))

        val stored = storedPayload().attachments.single()
        assertEquals(AttachmentKind.SCREEN_RECORDING, stored.kind)
        assertEquals("video/mp4", stored.mimeType)
        assertEquals(30L * 1024 * 1024, stored.sizeBytes)
        val file = store.pending().single().toPrepared().attachments.single().file
        assertEquals(video.length(), file.length())
    }

    @Test
    fun `failing scheduler does not lose the queued report`() = runBlocking {
        val id = submitter(schedule = { throw IllegalStateException("WorkManager is not initialized") }).submit(draft())
        assertEquals("id-1", id)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `context collection failures still queue the report`() = runBlocking {
        val id = submitter(
            deviceInfo = { throw RuntimeException("binder call failed") },
            appInfo = { throw RuntimeException("package manager call failed") },
        ).submit(draft())

        assertNotNull(id)
        val payload = storedPayload()
        assertEquals(DeviceInfo.UNKNOWN, payload.device)
        assertEquals("", payload.app.packageName)
    }

    @Test
    fun `four user attachments and three host files all reach the stored report`() = runBlocking {
        fun file(name: String) = File(dir, name).apply { writeBytes(byteArrayOf(1)) }
        appFiles = (1..3).map { n ->
            ReportStore.NewAttachment(AttachmentMeta("host-$n", AttachmentKind.APP_FILE, "host-$n.txt", "text/plain", 0)) { "h$n".byteInputStream() }
        }

        submitter().submit(
            draft(
                listOf(
                    DraftAttachment(AttachmentKind.SCREENSHOT, file("screenshot.png"), "screenshot.png", "image/png"),
                    DraftAttachment(AttachmentKind.EXTRA_SCREENSHOT, file("extra-1.png"), "extra-1.png", "image/png"),
                    DraftAttachment(AttachmentKind.EXTRA_SCREENSHOT, file("extra-2.png"), "extra-2.png", "image/png"),
                    DraftAttachment(AttachmentKind.GALLERY_IMAGE, file("gallery-1.jpg"), "gallery-1.jpg", "image/jpeg"),
                ),
            ),
        )

        assertEquals(
            listOf(
                AttachmentKind.SCREENSHOT,
                AttachmentKind.EXTRA_SCREENSHOT,
                AttachmentKind.EXTRA_SCREENSHOT,
                AttachmentKind.GALLERY_IMAGE,
                AttachmentKind.APP_FILE,
                AttachmentKind.APP_FILE,
                AttachmentKind.APP_FILE,
            ),
            storedPayload().attachments.map { it.kind },
        )
    }
}
