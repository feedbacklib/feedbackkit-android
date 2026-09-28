package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.RecordingButtonPosition
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class RecordingOverlayTest {

    private val stops = mutableListOf<Activity>()
    private var position = RecordingButtonPosition.BOTTOM_RIGHT
    private var startedAt = 0L
    private val overlay = RecordingOverlay({ position }, { 0xFF1565C0.toInt() }, { startedAt }, { stops += it }, SdkLogger(LogLevel.NONE))

    private fun controls(activity: Activity): List<RecordingControlView> {
        val decor = activity.window.decorView as ViewGroup
        return (0 until decor.childCount).map(decor::getChildAt).filterIsInstance<RecordingControlView>()
    }

    private fun host(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `the control sits once on the host and reports Stop`() {
        val activity = host()
        overlay.show(activity)
        overlay.show(activity)
        controls(activity).single().findViewWithTag<Button>(RecordingControlView.TAG_STOP).performClick()
        assertEquals(listOf(activity), stops)
    }

    @Test
    fun `Stop is labelled and large enough to tap`() {
        val activity = host()
        overlay.show(activity)
        val stop = controls(activity).single().findViewWithTag<Button>(RecordingControlView.TAG_STOP)
        assertEquals(activity.getString(R.string.feedbackkit_record_stop), stop.text.toString())
        assertEquals(activity.getString(R.string.feedbackkit_record_stop_description), stop.contentDescription)
        assertTrue(stop.minimumHeight >= 48 * activity.resources.displayMetrics.density)
    }

    @Test
    fun `the timer counts from the start of the recording`() {
        startedAt = SystemClock.elapsedRealtime()
        val activity = host()
        overlay.show(activity)
        val timer = controls(activity).single().findViewWithTag<TextView>(RecordingControlView.TAG_TIMER)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("0:00", timer.text.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("0:05", timer.text.toString())
    }

    @Test
    fun `the control goes to the chosen corner and moves when the setting changes`() {
        val activity = host()
        overlay.show(activity)
        val params = { controls(activity).single().layoutParams as FrameLayout.LayoutParams }
        assertEquals(Gravity.BOTTOM or Gravity.RIGHT, params().gravity)
        position = RecordingButtonPosition.TOP_LEFT
        overlay.relayout()
        assertEquals(Gravity.TOP or Gravity.LEFT, params().gravity)
    }

    @Test
    fun `hideAll removes every control`() {
        val first = host()
        val second = host()
        overlay.show(first)
        overlay.show(second)
        overlay.hideAll()
        assertTrue(controls(first).isEmpty() && controls(second).isEmpty())
    }

    @Test
    fun `the timer is read out with its label`() {
        startedAt = SystemClock.elapsedRealtime()
        val activity = host()
        overlay.show(activity)
        val timer = controls(activity).single().findViewWithTag<TextView>(RecordingControlView.TAG_TIMER)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(activity.getString(R.string.feedbackkit_record_timer_description, "0:05"), timer.contentDescription)
    }

    @Test
    fun `a hidden control stops ticking`() {
        startedAt = SystemClock.elapsedRealtime()
        val first = host()
        val second = host()
        overlay.show(first)
        overlay.show(second)
        val timers = listOf(first, second).map { controls(it).single().findViewWithTag<TextView>(RecordingControlView.TAG_TIMER) }
        shadowOf(Looper.getMainLooper()).idle()
        overlay.hide(first)
        overlay.hideAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no tick is left pending", Duration.ZERO, shadowOf(Looper.getMainLooper()).nextScheduledTaskTime)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf("0:00", "0:00"), timers.map { it.text.toString() })
    }
}
