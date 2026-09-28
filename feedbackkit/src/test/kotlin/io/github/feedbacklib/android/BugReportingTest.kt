package io.github.feedbacklib.android

import android.app.Activity
import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.feedbacklib.android.internal.capture.PrivateViewRegistry
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.ProactiveSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BugReportingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        FeedbackKit.resetForTests()
        PrivateViewRegistry.clearForTests()
        // Leaves no open WorkManager database behind for CloseGuard to report in a later test.
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun config() = FeedbackKitRuntime.current!!.config.value

    @Test
    fun `builder invocation events reach the runtime and NONE means manual only`() {
        FeedbackKit.Builder(app, "cid").setInvocationEvents(InvocationEvent.SHAKE, InvocationEvent.FLOATING_BUTTON).build()
        assertEquals(setOf(InvocationEvent.SHAKE, InvocationEvent.FLOATING_BUTTON), config().invocationEvents)

        BugReporting.setInvocationEvents(InvocationEvent.NONE)
        assertTrue(config().invocationEvents.isEmpty())
    }

    @Test
    fun `default invocation is shake`() {
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(setOf(InvocationEvent.SHAKE), config().invocationEvents)
    }

    @Test
    fun `a BugReporting call before build applies when the Builder never calls setInvocationEvents`() {
        BugReporting.setInvocationEvents(InvocationEvent.FLOATING_BUTTON)
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(setOf(InvocationEvent.FLOATING_BUTTON), config().invocationEvents)
    }

    @Test
    fun `an explicit Builder call wins over an earlier BugReporting call`() {
        BugReporting.setInvocationEvents(InvocationEvent.FLOATING_BUTTON)
        FeedbackKit.Builder(app, "cid").setInvocationEvents(InvocationEvent.TWO_FINGER_SWIPE_LEFT).build()
        assertEquals(setOf(InvocationEvent.TWO_FINGER_SWIPE_LEFT), config().invocationEvents)
    }

    @Test
    fun `settings before build are applied at build`() {
        val callback = OnInvokeCallback { }
        BugReporting.setShakingThreshold(900)
        BugReporting.setFloatingButtonEdge(FloatingButtonEdge.LEFT)
        BugReporting.setFloatingButtonOffset(120)
        BugReporting.setOnInvokeCallback(callback)
        FeedbackKit.Builder(app, "cid").build()

        assertEquals(900, config().shakingThreshold)
        assertEquals(FloatingButtonEdge.LEFT, config().floatingButtonEdge)
        assertEquals(120, config().floatingButtonOffsetDp)
        assertSame(callback, FeedbackKitRuntime.current!!.onInvokeCallback)
    }

    @Test
    fun `invalid threshold and negative offset are corrected, not thrown`() {
        FeedbackKit.Builder(app, "cid").build()
        BugReporting.setShakingThreshold(0)
        BugReporting.setFloatingButtonOffset(-5)
        assertEquals(650, config().shakingThreshold)
        assertEquals(0, config().floatingButtonOffsetDp)
    }

    @Test
    fun `private views registered before build are masked until removed`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = View(activity)
        activity.setContentView(view)
        ShadowLooper.idleMainLooper()
        val decor = activity.window.decorView

        FeedbackKit.addPrivateViews(view)
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(1, PrivateViewRegistry.regionsFor(decor).size)

        FeedbackKit.removePrivateViews(view)
        assertTrue(PrivateViewRegistry.regionsFor(decor).isEmpty())
    }

    @Test
    fun `bug reporting switched off before build stays off and only gates invocation`() {
        BugReporting.setState(FeatureState.DISABLED)
        FeedbackKit.Builder(app, "cid").build()
        assertFalse(config().bugReportingEnabled)
        assertFalse(config().canInvoke)
        assertTrue(config().enabled)
    }

    @Test
    fun `the recording button sits bottom right and auto recording is off until asked`() {
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(RecordingButtonPosition.BOTTOM_RIGHT, config().recordingButtonPosition)
        assertFalse(config().autoScreenRecording)
    }

    @Test
    fun `the recording button position is remembered before build and applies at once after it`() {
        BugReporting.setVideoRecordingButtonPosition(RecordingButtonPosition.TOP_LEFT)
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(RecordingButtonPosition.TOP_LEFT, config().recordingButtonPosition)

        BugReporting.setVideoRecordingButtonPosition(RecordingButtonPosition.BOTTOM_LEFT)
        assertEquals(RecordingButtonPosition.BOTTOM_LEFT, config().recordingButtonPosition)
    }

    @Test
    fun `auto screen recording is remembered before build and switched at runtime`() {
        BugReporting.setAutoScreenRecordingEnabled(true)
        FeedbackKit.Builder(app, "cid").build()
        assertTrue(config().autoScreenRecording)

        BugReporting.setAutoScreenRecordingEnabled(false)
        assertFalse(config().autoScreenRecording)
    }

    @Test
    fun `proactive reporting settings are remembered before build and applied after it`() {
        BugReporting.setProactiveReportingConfigurations(ProactiveReportingConfigs.Builder().isEnabled(true).build())
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(ProactiveSettings(enabled = true), config().proactive)

        BugReporting.setProactiveReportingConfigurations(
            ProactiveReportingConfigs.Builder().isEnabled(true).setGapBetweenModals(0, TimeUnit.SECONDS).setModalDelayAfterDetection(1, TimeUnit.SECONDS).build(),
        )
        assertEquals(ProactiveSettings(enabled = true, gapMillis = 0, delayMillis = 1_000), config().proactive)
    }
}
