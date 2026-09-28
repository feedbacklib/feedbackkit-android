package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

class TouchCountingActivity : Activity() {
    var touches = 0
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touches++
        return super.dispatchTouchEvent(ev)
    }
}

@RunWith(RobolectricTestRunner::class)
class TwoFingerSwipeDetectorTest {

    private var enabled = true
    private var swipes = 0
    private val detector = TwoFingerSwipeDetector({ enabled }, { swipes++ }, SdkLogger(LogLevel.NONE))

    private fun event(action: Int, vararg xs: Float, time: Long): MotionEvent {
        val properties = Array(xs.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(xs.size) { i -> MotionEvent.PointerCoords().apply { x = xs[i]; y = 400f + i * 50f } }
        val base = SystemClock.uptimeMillis()
        return MotionEvent.obtain(base, base + time, action, xs.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun swipeOn(window: Window) {
        val width = window.decorView.width.takeIf { it > 0 } ?: 1_000
        val start = width * 0.9f
        val end = width * 0.3f
        val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        listOf(
            event(MotionEvent.ACTION_DOWN, start, time = 0),
            event(pointerDown, start, start, time = 10),
            event(MotionEvent.ACTION_MOVE, end, end, time = 150),
            event(MotionEvent.ACTION_UP, end, time = 200),
        ).forEach { window.callback.dispatchTouchEvent(it) }
    }

    @Test
    fun `swipe on a plain activity invokes and every event still reaches the activity`() {
        val activity = Robolectric.buildActivity(TouchCountingActivity::class.java).setup().get()
        detector.attach(activity)
        swipeOn(activity.window)
        assertEquals(1, swipes)
        assertEquals(4, activity.touches)
    }

    @Test
    fun `attaching twice wraps once`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        val wrapped = activity.window.callback
        detector.attach(activity)
        assertSame(wrapped, activity.window.callback)
    }

    @Test
    fun `host wrapping after us still delivers events and swipes`() {
        val activity = Robolectric.buildActivity(TouchCountingActivity::class.java).setup().get()
        detector.attach(activity)
        val ours = activity.window.callback
        var hostSaw = 0
        activity.window.callback = object : Window.Callback by ours {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                hostSaw++
                return ours.dispatchTouchEvent(event)
            }
        }
        swipeOn(activity.window)
        assertEquals(4, hostSaw)
        assertEquals(4, activity.touches)
        assertEquals(1, swipes)
    }

    @Test
    fun `disabled swipe invocation never fires but events flow`() {
        enabled = false
        val activity = Robolectric.buildActivity(TouchCountingActivity::class.java).setup().get()
        detector.attach(activity)
        swipeOn(activity.window)
        assertEquals(0, swipes)
        assertTrue(activity.touches == 4)
    }
    @Test
    fun `a detached detector no longer sees swipes and events still flow`() {
        val activity = Robolectric.buildActivity(TouchCountingActivity::class.java).setup().get()
        detector.attach(activity)
        detector.detach(activity)
        swipeOn(activity.window)
        assertEquals(0, swipes)
        assertEquals(4, activity.touches)
    }

    @Test
    fun `a new detector rebinds the existing wrapper and the stopped one stays silent`() {
        val activity = Robolectric.buildActivity(TouchCountingActivity::class.java).setup().get()
        detector.attach(activity)
        val wrapped = activity.window.callback
        detector.detach(activity)

        // A new InvocationSystem after reset + build.
        var nextSwipes = 0
        val next = TwoFingerSwipeDetector({ true }, { nextSwipes++ }, SdkLogger(LogLevel.NONE))
        next.attach(activity)

        assertSame(wrapped, activity.window.callback)
        swipeOn(activity.window)
        assertEquals(0, swipes)
        assertEquals(1, nextSwipes)
        assertEquals(4, activity.touches)
    }

    @Test
    fun `a late detach from an old detector leaves the new binding alone`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        var nextSwipes = 0
        val next = TwoFingerSwipeDetector({ true }, { nextSwipes++ }, SdkLogger(LogLevel.NONE))
        next.attach(activity)

        detector.detach(activity)
        swipeOn(activity.window)

        assertEquals(0, swipes)
        assertEquals(1, nextSwipes)
    }

    @Test
    fun `re-attaching after a pause does not wrap again when the host wrapped after us`() {
        val activity = Robolectric.buildActivity(TouchCountingActivity::class.java).setup().get()
        detector.attach(activity)
        val ours = activity.window.callback
        val host = object : Window.Callback by ours {}
        activity.window.callback = host

        detector.detach(activity)
        detector.attach(activity)

        assertSame(host, activity.window.callback)
        swipeOn(activity.window)
        assertEquals(1, swipes)
        assertEquals(4, activity.touches)
    }
}
