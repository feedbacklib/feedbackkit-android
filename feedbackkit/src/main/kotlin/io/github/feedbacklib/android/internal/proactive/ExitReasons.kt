package io.github.feedbacklib.android.internal.proactive

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.feedbacklib.android.internal.core.SdkLogger

/** Why the previous run ended, from the system (spec §8); a seam for tests. Blocking — background only. */
internal fun interface ExitReasons {
    fun history(): ExitHistory
}

/**
 * ApplicationExitInfo on API 30+: the exits of this very process, newest first — a `:remote` process of the
 * same app has exits of its own. Below API 30 there are none. Never throws: a failed read is an empty
 * history, which detects no crash and no force restart from the system's side.
 */
internal class AndroidExitReasons(private val app: Application, private val logger: SdkLogger) : ExitReasons {

    override fun history(): ExitHistory {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ExitHistory.UNSUPPORTED
        return try {
            ExitHistory(supported = true, records = exits())
        } catch (e: Exception) {
            logger.w("Could not read why the previous run ended", e)
            ExitHistory(supported = true, records = emptyList())
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exits(): List<ExitRecord> {
        val manager = app.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val process = Application.getProcessName()
        return manager.getHistoricalProcessExitReasons(app.packageName, 0, MAX_RECORDS)
            .filter { it.processName == process }
            .sortedByDescending { it.timestamp }
            // An empty description says nothing: it is none, not an empty stack trace in the report.
            .map { ExitRecord(reasonOf(it.reason), it.timestamp, it.description?.takeUnless(String::isEmpty)) }
    }

    internal companion object {
        /** Enough to find this process's exits among those of the app's other processes. */
        const val MAX_RECORDS: Int = 16

        fun reasonOf(reason: Int): ExitReason = when (reason) {
            ApplicationExitInfo.REASON_CRASH -> ExitReason.CRASH
            ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReason.CRASH_NATIVE
            ApplicationExitInfo.REASON_ANR -> ExitReason.ANR
            ApplicationExitInfo.REASON_USER_REQUESTED -> ExitReason.USER_REQUESTED
            ApplicationExitInfo.REASON_OTHER -> ExitReason.OTHER
            ApplicationExitInfo.REASON_SIGNALED -> ExitReason.SIGNALED
            else -> ExitReason.UNLISTED
        }
    }
}
