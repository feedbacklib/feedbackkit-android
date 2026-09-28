package io.github.feedbacklib.android.internal.proactive

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
class MainProcessTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val logger = SdkLogger(LogLevel.NONE)

    @After
    fun tearDown() = ShadowApplication.setProcessName(app.packageName)

    @Test
    fun `the process named after the package is the main one`() {
        ShadowApplication.setProcessName(app.packageName)
        assertTrue(isMainProcess(app, logger))
    }

    @Test
    fun `a process with a suffix or another name is not`() {
        ShadowApplication.setProcessName("${app.packageName}:remote")
        assertFalse(isMainProcess(app, logger))
        ShadowApplication.setProcessName("com.example.other")
        assertFalse(isMainProcess(app, logger))
    }

    @Test
    fun `a name that cannot be read counts as the main process`() {
        ShadowApplication.setProcessName("")
        assertTrue(isMainProcess(app, logger))
    }

    @Test
    fun `the command line keeps only the process name`() {
        assertEquals("com.example", processNameOf("com.example\u0000".toByteArray()))
        assertEquals("com.example:remote", processNameOf("com.example:remote\u0000--arg\u0000".toByteArray()))
        assertEquals(null, processNameOf(ByteArray(0)))
    }
}
