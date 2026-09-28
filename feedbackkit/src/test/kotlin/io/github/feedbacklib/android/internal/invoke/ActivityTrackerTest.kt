package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.spi.SdkActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

class FakeSdkActivity : Activity(), SdkActivity

@RunWith(RobolectricTestRunner::class)
class ActivityTrackerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val tracker = ActivityTracker(SdkLogger(LogLevel.NONE))
    private val events = mutableListOf<String>()

    @Before
    fun register() {
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.addListener(object : ActivityTracker.Listener {
            override fun onHostActivityResumed(activity: Activity) { events += "resumed" }
            override fun onHostActivityPaused(activity: Activity) { events += "paused" }
            override fun onForegroundChanged(foreground: Boolean) { events += "foreground=$foreground" }
        })
    }

    @After
    fun unregister() = app.unregisterActivityLifecycleCallbacks(tracker)

    @Test
    fun `tracks the resumed host activity and foreground`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertSame(controller.get(), tracker.currentActivity)
        assertTrue(tracker.isForeground)

        controller.pause().stop()
        assertNull(tracker.currentActivity)
        assertFalse(tracker.isForeground)
        assertEquals(listOf("foreground=true", "resumed", "paused", "foreground=false"), events)
    }

    @Test
    fun `sdk activities keep the app in foreground but are never the host activity`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        host.pause()
        Robolectric.buildActivity(FakeSdkActivity::class.java).setup()
        host.stop()

        assertNull(tracker.currentActivity)
        assertTrue(tracker.isForeground)
        assertEquals(listOf("foreground=true", "resumed", "paused"), events)
    }

    @Test
    fun `the first host screen's resume time is kept, and an SDK screen or a later one does not move it`() {
        var now = 1_000L
        val timed = ActivityTracker(SdkLogger(LogLevel.NONE), clock = { now })
        app.registerActivityLifecycleCallbacks(timed)
        try {
            assertNull("no host screen yet", timed.firstHostResumedAt)
            Robolectric.buildActivity(FakeSdkActivity::class.java).setup()
            assertNull(timed.firstHostResumedAt)
            now = 2_000L
            val host = Robolectric.buildActivity(Activity::class.java).setup()
            now = 3_000L
            host.pause().resume()
            Robolectric.buildActivity(Activity::class.java).setup()
            assertEquals(2_000L, timed.firstHostResumedAt)
        } finally {
            app.unregisterActivityLifecycleCallbacks(timed)
        }
    }

    @Test
    fun `a failing listener does not stop the others`() {
        tracker.addListener(object : ActivityTracker.Listener {
            override fun onHostActivityResumed(activity: Activity) = error("host bug")
        })
        var reached = false
        tracker.addListener(object : ActivityTracker.Listener {
            override fun onHostActivityResumed(activity: Activity) { reached = true }
        })
        Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(reached)
    }

    @Test
    fun `an activity started before registration does not fake a background on the next transition`() {
        app.unregisterActivityLifecycleCallbacks(tracker)
        val early = Robolectric.buildActivity(Activity::class.java).setup()
        app.registerActivityLifecycleCallbacks(tracker)
        early.pause()
        val next = Robolectric.buildActivity(Activity::class.java).setup()
        early.stop()

        assertTrue(tracker.isForeground)
        assertSame(next.get(), tracker.currentActivity)
        assertFalse(events.contains("foreground=false"))
    }
    @Test
    fun `when the current host pauses while another host is still resumed, that one becomes current`() {
        val left = Robolectric.buildActivity(Activity::class.java).setup()
        val right = Robolectric.buildActivity(Activity::class.java).setup()
        events.clear()

        right.pause()

        assertSame(left.get(), tracker.currentActivity)
        // Seen as a resume of the fallback, so activity detectors re-attach to it.
        assertEquals(listOf("paused", "resumed"), events)
    }

    @Test
    fun `pausing a host that is not the current one keeps the current one`() {
        val left = Robolectric.buildActivity(Activity::class.java).setup()
        val right = Robolectric.buildActivity(Activity::class.java).setup()
        events.clear()

        left.pause()

        assertSame(right.get(), tracker.currentActivity)
        assertEquals(listOf("paused"), events)
    }

    @Test
    fun `a destroyed fallback is never reported as current`() {
        val left = Robolectric.buildActivity(Activity::class.java).setup()
        val right = Robolectric.buildActivity(Activity::class.java).setup()
        left.pause().stop().destroy()
        events.clear()

        right.pause()

        assertNull(tracker.currentActivity)
        assertEquals(listOf("paused"), events)
    }

    @Test
    fun `knows whether an sdk activity is alive`() {
        assertFalse(tracker.hasLiveSdkActivity)
        val sdk = Robolectric.buildActivity(FakeSdkActivity::class.java).setup()
        assertTrue(tracker.hasLiveSdkActivity)
        sdk.pause().stop().destroy()
        assertFalse(tracker.hasLiveSdkActivity)
    }
}
