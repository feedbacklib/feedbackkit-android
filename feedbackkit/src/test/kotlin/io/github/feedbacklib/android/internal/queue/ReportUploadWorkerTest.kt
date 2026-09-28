package io.github.feedbacklib.android.internal.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.PreparedReport
import io.github.feedbacklib.android.ReportSender
import io.github.feedbacklib.android.SendResult
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.samplePayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReportUploadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logger = SdkLogger(LogLevel.NONE)

    @After
    fun unbind() {
        UploadWorkerBinding.runnerProvider = null
    }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ReportUploadWorker>(context).build().doWork()
    }

    private fun bindRunner(result: SendResult) {
        val store = ReportStore(File(context.filesDir, "worker-test-${System.nanoTime()}"), logger)
        store.enqueue(samplePayload("r-1"), emptyList())
        val sender = object : ReportSender {
            override suspend fun send(report: PreparedReport): SendResult = result
        }
        UploadWorkerBinding.runnerProvider = { UploadRunner(store, sender, logger) }
    }

    @Test
    fun `without an initialised sdk the worker has nothing to do`() {
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @Test
    fun `delivered queue finishes with success`() {
        bindRunner(SendResult.Success)
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    @Test
    fun `retryable failure asks WorkManager to retry`() {
        bindRunner(SendResult.RetryableFailure())
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun `unexpected failure is logged and retried instead of failing the work`() {
        UploadWorkerBinding.runnerProvider = { throw IllegalStateException("broken runtime") }
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }
}
