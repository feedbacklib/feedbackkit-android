package io.github.feedbacklib.android.internal.queue

import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.PreparedReport
import io.github.feedbacklib.android.ReportSender
import io.github.feedbacklib.android.SendResult
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.samplePayload
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UploadRunnerTest {

    @TempDir
    lateinit var root: File

    private val logger = SdkLogger(LogLevel.NONE)
    private var now = 1_000L
    private val store by lazy { ReportStore(root, logger, clock = { now }) }
    private val sent = mutableListOf<String>()

    private fun enqueue(vararg ids: String) = ids.forEach { now += 1; store.enqueue(samplePayload(it), emptyList()) }

    private fun sender(result: (String) -> SendResult) = object : ReportSender {
        override suspend fun send(report: PreparedReport): SendResult {
            sent += report.id
            return result(report.id)
        }
    }

    @Test
    fun `delivered reports are removed, oldest first`() = runBlocking {
        enqueue("r-1", "r-2")
        val outcome = UploadRunner(store, sender { SendResult.Success }, logger).run()
        assertEquals(UploadRunner.Outcome.DONE, outcome)
        assertEquals(listOf("r-1", "r-2"), sent)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `permanent failure marks the report failed and moves on`() = runBlocking {
        enqueue("r-1", "r-2")
        val outcome = UploadRunner(store, sender { if (it == "r-1") SendResult.PermanentFailure() else SendResult.Success }, logger).run()
        assertEquals(UploadRunner.Outcome.DONE, outcome)
        assertEquals(listOf("r-1", "r-2"), sent)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `retryable failure stops the run and keeps the rest queued`() = runBlocking {
        enqueue("r-1", "r-2")
        val outcome = UploadRunner(store, sender { SendResult.RetryableFailure() }, logger).run()
        assertEquals(UploadRunner.Outcome.RETRY, outcome)
        assertEquals(listOf("r-1"), sent)
        assertEquals(listOf("r-1", "r-2"), store.pending().map { it.id })
    }

    @Test
    fun `sender that throws is treated as retryable`() = runBlocking {
        enqueue("r-1")
        val throwing = object : ReportSender {
            override suspend fun send(report: PreparedReport): SendResult = throw IllegalStateException("host bug")
        }
        assertEquals(UploadRunner.Outcome.RETRY, UploadRunner(store, throwing, logger).run())
        assertEquals(listOf("r-1"), store.pending().map { it.id })
    }

    @Test
    fun `unreadable report is set aside and the next one is still delivered`() = runBlocking {
        enqueue("broken", "r-2")
        File(store.pending().first().directory, ReportStore.REPORT_FILE).writeText("{not json")
        val outcome = UploadRunner(store, sender { SendResult.Success }, logger).run()
        assertEquals(UploadRunner.Outcome.DONE, outcome)
        assertEquals(listOf("r-2"), sent)
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `sender throwing a CancellationException is retryable, not a cancelled worker`() = runBlocking {
        enqueue("r-1")
        val cancelling = object : ReportSender {
            override suspend fun send(report: PreparedReport): SendResult =
                throw java.util.concurrent.CancellationException("host future was cancelled")
        }
        assertEquals(UploadRunner.Outcome.RETRY, UploadRunner(store, cancelling, logger).run())
        assertEquals(listOf("r-1"), store.pending().map { it.id })
    }
}
