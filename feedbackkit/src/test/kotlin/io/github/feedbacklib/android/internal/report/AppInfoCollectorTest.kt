package io.github.feedbacklib.android.internal.report

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.FeedbackKitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppInfoCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `reports the host package and the sdk version`() {
        val info = AppInfoCollector(context).collect()
        assertEquals(context.packageName, info.packageName)
        assertEquals(FeedbackKitInfo.VERSION, info.sdkVersion)
        assertTrue(info.versionCode >= 0)
    }
}
