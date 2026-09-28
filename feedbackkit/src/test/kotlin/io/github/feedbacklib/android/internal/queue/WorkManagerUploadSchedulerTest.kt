package io.github.feedbacklib.android.internal.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkManagerUploadSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun closeWorkDatabase() {
        // Leaves no open WorkManager database behind for CloseGuard to report in a later test.
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `schedules one unique upload that needs a network`() {
        WorkManagerUploadScheduler(context).schedule()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerUploadScheduler.UNIQUE_WORK_NAME).get()
        assertTrue(infos.isNotEmpty())
        assertEquals(NetworkType.CONNECTED, infos.first().constraints.requiredNetworkType)
    }
}
