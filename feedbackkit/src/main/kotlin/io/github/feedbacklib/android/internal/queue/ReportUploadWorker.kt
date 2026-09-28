package io.github.feedbacklib.android.internal.queue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.feedbacklib.android.internal.core.SdkLog
import kotlinx.coroutines.CancellationException

internal class ReportUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        try {
            val runner = UploadWorkerBinding.runnerProvider?.invoke()
            when (runner?.run()) {
                null, UploadRunner.Outcome.DONE -> Result.success()
                UploadRunner.Outcome.RETRY -> Result.retry()
            }
        } catch (e: CancellationException) {   // kotlinx.coroutines — the worker is being stopped
            throw e
        } catch (e: Exception) {
            SdkLog.logger.e("Report delivery failed unexpectedly; will retry", e)
            Result.retry()
        }
}
