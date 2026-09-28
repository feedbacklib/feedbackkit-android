package io.github.feedbacklib.android.internal.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * APPEND_OR_REPLACE chains a fresh pass after a running one, so a report queued while the
 * worker is busy is never left behind until the next start.
 *
 * While a predecessor waits for a network or a backoff, each `schedule()` appends another link;
 * the first link that runs delivers the whole queue and the rest are cheap empty passes.
 */
internal class WorkManagerUploadScheduler(private val context: Context) : UploadScheduler {

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    internal companion object {
        const val UNIQUE_WORK_NAME: String = "feedbackkit-upload"
        private const val BACKOFF_SECONDS = 30L
    }
}
