package io.github.feedbacklib.android.internal.proactive

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

@RunWith(RobolectricTestRunner::class)
class AndroidExitReasonsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: ActivityManager = app.getSystemService(ActivityManager::class.java)
    private val exits = AndroidExitReasons(app, SdkLogger(LogLevel.NONE))

    private fun add(reason: Int, timestamp: Long, process: String? = Application.getProcessName(), description: String? = null) {
        shadowOf(manager).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(process)
                .setReason(reason)
                .setTimestamp(timestamp)
                .setDescription(description)
                .build(),
        )
    }

    @Test
    fun `no exit recorded yet is a supported but empty history`() {
        assertEquals(ExitHistory(supported = true, records = emptyList()), exits.history())
    }

    @Test
    fun `every exit of this process comes, newest first, and another process's do not`() {
        add(ApplicationExitInfo.REASON_CRASH, 1_000)
        add(ApplicationExitInfo.REASON_ANR, 3_000, description = "Input dispatching timed out")
        add(ApplicationExitInfo.REASON_USER_REQUESTED, 5_000, process = "${app.packageName}:remote")
        val history = exits.history()
        val anr = ExitRecord(ExitReason.ANR, 3_000, "Input dispatching timed out")
        assertEquals(listOf(anr, ExitRecord(ExitReason.CRASH, 1_000, null)), history.records)
        assertEquals(anr, history.latest)
    }

    @Test
    fun `every reason the heuristic knows is mapped and the rest are unlisted`() {
        mapOf(
            ApplicationExitInfo.REASON_CRASH to ExitReason.CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE to ExitReason.CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR to ExitReason.ANR,
            ApplicationExitInfo.REASON_USER_REQUESTED to ExitReason.USER_REQUESTED,
            ApplicationExitInfo.REASON_OTHER to ExitReason.OTHER,
            ApplicationExitInfo.REASON_SIGNALED to ExitReason.SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY to ExitReason.UNLISTED,
            ApplicationExitInfo.REASON_EXIT_SELF to ExitReason.UNLISTED,
            ApplicationExitInfo.REASON_USER_STOPPED to ExitReason.UNLISTED,
        ).forEach { (code, reason) -> assertEquals("reason $code", reason, AndroidExitReasons.reasonOf(code)) }
    }
}
