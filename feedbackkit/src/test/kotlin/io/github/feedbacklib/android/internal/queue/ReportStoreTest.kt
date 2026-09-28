package io.github.feedbacklib.android.internal.queue

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.AttachmentMeta
import io.github.feedbacklib.android.internal.report.ReportJson
import io.github.feedbacklib.android.internal.report.samplePayload
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReportStoreTest {

    @TempDir
    lateinit var root: File

    private var now = 1_000L
    private val logger = SdkLogger(LogLevel.NONE)

    private fun store(limits: ReportStore.Limits = ReportStore.Limits()) =
        ReportStore(root, logger, clock = { now }, limits = limits)

    private fun meta(id: String, name: String = "$id.txt") =
        AttachmentMeta(id, AttachmentKind.APP_FILE, name, "text/plain", sizeBytes = 0)

    private fun attachment(id: String, bytes: ByteArray, maxBytes: Long = Long.MAX_VALUE) =
        ReportStore.NewAttachment(meta(id), maxBytes) { bytes.inputStream() }

    @Test
    fun `enqueued report is pending with its attachments copied and sized`() {
        val store = store()
        store.enqueue(samplePayload("r-1"), listOf(attachment("a-1", byteArrayOf(1, 2, 3))))

        val pending = store.pending()
        assertEquals(listOf("r-1"), pending.map { it.id })
        val prepared = pending.single().toPrepared()
        assertEquals("r-1", prepared.id)
        assertEquals("cid-1", prepared.cid)
        val stored = prepared.attachments.single()
        assertEquals(3L, stored.sizeBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), stored.file.readBytes())
        assertEquals(3L, ReportJson.decode(prepared.reportJson).attachments.single().sizeBytes)
    }

    @Test
    fun `attachment whose source is gone is skipped and the report is still queued`() {
        val store = store()
        store.enqueue(
            samplePayload("r-1"),
            listOf(
                ReportStore.NewAttachment(meta("gone")) { null },
                ReportStore.NewAttachment(meta("broken")) { throw IOException("no access") },
                attachment("ok", byteArrayOf(7)),
            ),
        )
        assertEquals(listOf("ok"), store.pending().single().toPrepared().attachments.map { it.id })
    }

    @Test
    fun `attachment larger than its limit is skipped without leaving a partial file`() {
        val store = store()
        store.enqueue(samplePayload("r-1"), listOf(attachment("big", ByteArray(11), maxBytes = 10)))
        val report = store.pending().single()
        assertTrue(report.toPrepared().attachments.isEmpty())
        assertFalse(File(report.directory, "${ReportStore.ATTACHMENTS_DIR}/big").exists())
    }

    @Test
    fun `interrupted write is never pending and is removed by cleanUp`() {
        val interrupted = File(root, "500-r-0${ReportStore.TMP_SUFFIX}").apply { mkdirs() }
        File(interrupted, ReportStore.REPORT_FILE).writeText("{")
        val store = store()
        assertTrue(store.pending().isEmpty())
        store.cleanUp()
        assertFalse(interrupted.exists())
    }

    @Test
    fun `pending lists reports oldest first`() {
        val store = store()
        now = 3_000; store.enqueue(samplePayload("late"), emptyList())
        now = 2_000; store.enqueue(samplePayload("early"), emptyList())
        assertEquals(listOf("early", "late"), store.pending().map { it.id })
    }

    @Test
    fun `failed report leaves pending and delete removes a report`() {
        val store = store()
        store.enqueue(samplePayload("r-1"), emptyList())
        now += 1; store.enqueue(samplePayload("r-2"), emptyList())
        store.markFailed("r-1")
        assertEquals(listOf("r-2"), store.pending().map { it.id })
        store.delete("r-2")
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `queue over the report count drops the oldest`() {
        val store = store(ReportStore.Limits(maxReports = 2))
        listOf("r-1", "r-2", "r-3").forEach { now += 1; store.enqueue(samplePayload(it), emptyList()) }
        assertEquals(listOf("r-2", "r-3"), store.pending().map { it.id })
    }

    @Test
    fun `queue over the byte budget drops the oldest`() {
        // 2 000-byte attachment + a report.json well under 1 000 bytes: one report fits, two do not.
        val store = store(ReportStore.Limits(maxTotalBytes = 3_000))
        listOf("r-1", "r-2").forEach {
            now += 1
            store.enqueue(samplePayload(it), listOf(attachment("a-$it", ByteArray(2_000))))
        }
        assertEquals(listOf("r-2"), store.pending().map { it.id })
    }

    @Test
    fun `cleanUp drops reports older than the age limit`() {
        val store = store(ReportStore.Limits(maxAgeMillis = 10_000))
        store.enqueue(samplePayload("old"), emptyList())
        now += 10_001
        store.cleanUp()
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `source that fails mid-read is skipped and the report is still queued`() {
        val store = store()
        val failing = object : java.io.InputStream() {
            private var sent = false
            override fun read(): Int = if (!sent) { sent = true; 1 } else throw SecurityException("permission revoked")
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (!sent) { sent = true; b[off] = 1; 1 } else throw SecurityException("permission revoked")
        }
        store.enqueue(samplePayload("r-1"), listOf(ReportStore.NewAttachment(meta("bad")) { failing }, attachment("ok", byteArrayOf(7))))
        val report = store.pending().single()
        assertEquals(listOf("ok"), report.toPrepared().attachments.map { it.id })
        assertFalse(File(report.directory, "${ReportStore.ATTACHMENTS_DIR}/bad").exists())
    }

    @Test
    fun `the report just committed is kept even when it alone exceeds the byte budget`() {
        val warnings = mutableListOf<String>()
        val store = ReportStore(
            root,
            SdkLogger(LogLevel.WARNING) { level, _, message, _ -> if (level == LogLevel.WARNING) warnings += message },
            clock = { now },
            limits = ReportStore.Limits(maxTotalBytes = 3_000),
        )
        store.enqueue(samplePayload("r-1"), listOf(attachment("a-1", ByteArray(1_000))))
        now += 1
        store.enqueue(samplePayload("r-2"), listOf(attachment("a-2", ByteArray(5_000))))

        assertEquals(listOf("r-2"), store.pending().map { it.id })
        assertTrue(warnings.any { "r-2" in it && "kept" in it }, warnings.toString())
    }

    @Test
    fun `the report just committed is kept when the count limit is hit with an earlier clock`() {
        val store = store(ReportStore.Limits(maxReports = 1))
        now = 3_000; store.enqueue(samplePayload("r-1"), emptyList())
        now = 2_000; store.enqueue(samplePayload("r-2"), emptyList()) // the clock went back
        assertEquals(listOf("r-2"), store.pending().map { it.id })
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a slow attachment copy does not hold up the rest of the store`() {
        val store = store()
        store.enqueue(samplePayload("r-0"), emptyList())
        val copying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = ReportStore.NewAttachment(meta("slow")) {
            copying.countDown()
            release.await()
            byteArrayOf(1).inputStream()
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val queued = executor.submit<StoredReport> { store.enqueue(samplePayload("r-1"), listOf(slow)) }
            assertTrue(copying.await(5, TimeUnit.SECONDS))

            // The copy is stuck in a content provider; everything else still answers at once.
            assertEquals(listOf("r-0"), store.pending().map { it.id })
            store.markFailed("r-0")
            val leftover = File(root, "500-r-9${ReportStore.TMP_SUFFIX}").apply { mkdirs() }
            val inFlight = root.listFiles()!!.single { it.name.endsWith("-r-1${ReportStore.TMP_SUFFIX}") }
            store.cleanUp() // must not delete the half-written report
            assertFalse(leftover.exists(), "an interrupted write of an earlier run is removed")
            assertTrue(inFlight.exists(), "the write in progress is left alone")
            release.countDown()

            assertEquals("r-1", queued.get(5, TimeUnit.SECONDS).id)
            assertEquals(listOf("r-1"), store.pending().map { it.id })
            assertEquals(1L, store.pending().single().toPrepared().attachments.single().sizeBytes)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
