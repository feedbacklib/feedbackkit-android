package io.github.feedbacklib.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.ui.FeedbackActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File
import java.time.Duration

/** FeedbackKit.show() through the public facade, down to the SDK screen and back (spec §5). */
@RunWith(RobolectricTestRunner::class)
class FeedbackKitShowTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        FeedbackKit.resetForTests()
        // Leaves no open WorkManager database behind for CloseGuard to report in a later test.
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /**
     * Calls show() and waits for the SDK screen to be started. The main looper first runs 2 s of
     * fake time, enough for a capture that fails at once or times out after 1 s; a successful
     * capture then writes its PNG on the capture thread in real time and posts the launch back, so
     * the main looper is drained until that arrives (at most [WAIT_MILLIS]).
     */
    private fun show(host: ActivityController<out Activity>, call: () -> Unit = { FeedbackKit.show() }): Intent? {
        call()
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofSeconds(2))
        val deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000
        while (true) {
            looper.idle()
            shadowOf(host.get()).nextStartedActivity?.let { return it }
            if (FeedbackKitRuntime.current?.invocation?.coordinator?.isUiOpen != true || System.nanoTime() > deadline) return null
            Thread.sleep(10)
        }
    }

    /** show() while the SDK screen is open: nothing may start, and nothing is in flight to wait for. */
    private fun showWhileOpen(host: ActivityController<out Activity>): Intent? {
        FeedbackKit.show()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        return shadowOf(host.get()).nextStartedActivity
    }

    private fun assertOpensFeedbackActivity(intent: Intent?) {
        assertNotNull("the SDK screen was not started", intent)
        assertEquals(FeedbackActivity::class.java.name, intent!!.component?.className)
    }

    @Test
    fun `show opens the sdk screen, and again after it closed`() {
        FeedbackKit.Builder(app, "cid").build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()

        val first = show(host)
        assertOpensFeedbackActivity(first)
        // Robolectric's PixelCopy shadow draws the window, so the capture path runs for real: the
        // screenshot is saved and handed to the SDK screen.
        val screenshot = first!!.getStringExtra("feedbackkit.screenshot")
        assertNotNull(screenshot)
        assertTrue(File(screenshot!!).length() > 0)
        assertFalse(first.getBooleanExtra("feedbackkit.screenshotSecure", true))

        // While it is open, another show() is ignored.
        host.pause()
        val sdk = Robolectric.buildActivity(FeedbackActivity::class.java, first).setup()
        assertNull(showWhileOpen(host))

        // Closed the way the system does it: the SDK screen pauses, the host resumes, then the
        // SDK screen is stopped and destroyed, reporting the close.
        sdk.get().finish()
        sdk.pause()
        host.resume()
        sdk.stop().destroy()

        assertOpensFeedbackActivity(show(host))
    }

    @Test
    fun `a start that never creates the sdk screen does not block the next show`() {
        FeedbackKit.Builder(app, "cid").build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        assertOpensFeedbackActivity(show(host))

        // The start was dropped; the user leaves and comes back to the host.
        host.pause().resume()

        assertOpensFeedbackActivity(show(host))
    }

    @Test
    fun `show before build does nothing and after a late build works from the next host screen`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        assertNull(show(host))

        FeedbackKit.Builder(app, "cid").build()
        assertNull(show(host))

        host.pause().resume()
        assertOpensFeedbackActivity(show(host))
    }

    @Test
    fun `BugReporting show opens the form for that type`() {
        FeedbackKit.Builder(app, "cid").build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val intent = show(host) { BugReporting.show(ReportType.FEEDBACK) }
        assertOpensFeedbackActivity(intent)
        assertEquals("FEEDBACK", intent!!.getStringExtra("feedbackkit.reportType"))
    }

    @Test
    fun `the proactive-only type is never opened by show`() {
        FeedbackKit.Builder(app, "cid").build()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        assertNull(show(host) { BugReporting.show(ReportType.FRUSTRATING_EXPERIENCE) })
    }

    @Test
    fun `bug reporting switched off ignores every show until it is switched back on`() {
        FeedbackKit.Builder(app, "cid").build()
        BugReporting.setState(FeatureState.DISABLED)
        val host = Robolectric.buildActivity(Activity::class.java).setup()

        assertNull(show(host))
        assertNull(show(host) { BugReporting.show(ReportType.BUG) })
        assertTrue(FeedbackKit.isEnabled)

        BugReporting.setState(FeatureState.ENABLED)
        assertOpensFeedbackActivity(show(host))
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
