package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InvocationManagerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val tracker = ActivityTracker(SdkLogger(LogLevel.NONE))
    private var events = setOf(InvocationEvent.SHAKE, InvocationEvent.FLOATING_BUTTON)
    private var enabled = true

    private class FakeProcess : ProcessDetector {
        var running = false
        override fun start() { running = true }
        override fun stop() { running = false }
    }

    private class FakeActivity : ActivityDetector {
        val attached = mutableSetOf<Activity>()
        override fun attach(activity: Activity) { attached += activity }
        override fun detach(activity: Activity) { attached -= activity }
    }

    private val shake = FakeProcess()
    private val button = FakeActivity()
    private val manager = InvocationManager(
        tracker, { events }, { enabled },
        mapOf(InvocationEvent.SHAKE to shake), mapOf(InvocationEvent.FLOATING_BUTTON to button),
        SdkLogger(LogLevel.NONE),
    )

    @Before
    fun register() {
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.addListener(manager)
    }

    @After
    fun unregister() = app.unregisterActivityLifecycleCallbacks(tracker)

    @Test
    fun `detectors follow foreground and the resumed host activity`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(shake.running)
        assertEquals(setOf(controller.get()), button.attached)

        controller.pause().stop()
        assertFalse(shake.running)
        assertTrue(button.attached.isEmpty())
    }

    @Test
    fun `activity detectors move to a host that stays resumed when the current one pauses`() {
        val left = Robolectric.buildActivity(Activity::class.java).setup()
        val right = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(setOf(right.get()), button.attached)

        right.pause()

        assertEquals(setOf(left.get()), button.attached)
    }

    @Test
    fun `turning an event off at runtime stops its detector immediately`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        events = setOf(InvocationEvent.SHAKE)
        manager.refresh()
        assertTrue(shake.running)
        assertFalse(activity in button.attached)
    }

    @Test
    fun `disabling the sdk stops everything`() {
        Robolectric.buildActivity(Activity::class.java).setup()
        enabled = false
        manager.refresh()
        assertFalse(shake.running)
        assertTrue(button.attached.isEmpty())
    }
}
