package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class FloatingButtonDetectorTest {

    private var taps = 0
    private val detector = FloatingButtonDetector(FloatingButtonState(), { taps++ }, SdkLogger(LogLevel.NONE))

    private fun buttons(activity: Activity): List<FloatingButtonView> {
        val decor = activity.window.decorView as ViewGroup
        return (0 until decor.childCount).map { decor.getChildAt(it) }.filterIsInstance<FloatingButtonView>()
    }

    @Test
    fun `a plain activity gets exactly one button that invokes on tap`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        detector.attach(activity)
        val button = buttons(activity).single()
        button.performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `the button is described from a string resource`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        assertEquals(activity.getString(R.string.feedbackkit_floating_button_description), buttons(activity).single().contentDescription)
    }

    @Test
    fun `detach removes the button`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        detector.detach(activity)
        assertEquals(0, buttons(activity).size)
    }

    @Test
    fun `a throwing tap handler does not reach the host`() {
        val throwing = FloatingButtonDetector(FloatingButtonState(), { error("host bug") }, SdkLogger(LogLevel.NONE))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        throwing.attach(activity)
        buttons(activity).single().performClick()
    }

    @Test
    fun `a cancelled drag returns the button to where it started`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        val button = buttons(activity).single()
        button.x = 100f
        button.y = 200f
        val time = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, y: Float) = MotionEvent.obtain(time, time, action, x, y, 0).also {
            button.onTouchEvent(it)
            it.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 10f, 10f)
        touch(MotionEvent.ACTION_MOVE, 400f, 900f)
        touch(MotionEvent.ACTION_CANCEL, 400f, 900f)
        assertEquals(100f, button.x)
        assertEquals(200f, button.y)
        assertEquals(0, taps)
    }
    private var visibilityAtCapture: Int? = null
    private var results = mutableListOf<ScreenCapturer.Result>()

    private fun hidingCapture(button: () -> View?, delegate: (callback: (ScreenCapturer.Result) -> Unit) -> Unit) =
        HidingCapture(
            detector,
            { _, callback ->
                visibilityAtCapture = button()?.visibility
                delegate(callback)
            },
            SdkLogger(LogLevel.NONE),
        )

    private fun idleUpToASecond() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Test
    fun `the button is hidden for the capture and shown again after it`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        val button = buttons(activity).single()

        hidingCapture({ button }) { it(ScreenCapturer.Result.Failed) }.capture(activity) { results += it }
        // The capture waits for a frame drawn without the button.
        assertNull(visibilityAtCapture)
        assertEquals(View.INVISIBLE, button.visibility)

        idleUpToASecond()

        assertEquals(View.INVISIBLE, visibilityAtCapture)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(listOf(ScreenCapturer.Result.Failed), results)
    }

    @Test
    fun `the button is shown again and the failure reported when the capture throws`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        val button = buttons(activity).single()

        hidingCapture({ button }) { error("capture bug") }.capture(activity) { results += it }
        idleUpToASecond()

        assertEquals(View.INVISIBLE, visibilityAtCapture)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(listOf(ScreenCapturer.Result.Failed), results)
    }

    @Test
    fun `the button is shown again when the capture ends later`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        val button = buttons(activity).single()
        var pending: ((ScreenCapturer.Result) -> Unit)? = null

        hidingCapture({ button }) { pending = it }.capture(activity) { results += it }
        idleUpToASecond()
        assertEquals(View.INVISIBLE, button.visibility)

        pending!!(ScreenCapturer.Result.Secure)
        assertEquals(View.VISIBLE, button.visibility)
        assertEquals(listOf(ScreenCapturer.Result.Secure), results)
    }

    @Test
    fun `without a button the capture runs straight away`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        hidingCapture({ null }) { it(ScreenCapturer.Result.Failed) }.capture(activity) { results += it }

        assertEquals(listOf(ScreenCapturer.Result.Failed), results)
    }
    @Test
    fun `the capture starts as soon as a frame without the button is drawn`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        val button = buttons(activity).single()

        hidingCapture({ button }) { it(ScreenCapturer.Result.Failed) }.capture(activity) { results += it }
        ShadowLooper.idleMainLooper()
        assertNull(visibilityAtCapture)

        // Robolectric never draws the window on its own; stand in for the frame the INVISIBLE
        // change schedules, well before the fallback wait.
        ReflectionHelpers.callInstanceMethod<Unit>(button.viewTreeObserver, "dispatchOnDraw")
        ShadowLooper.idleMainLooper()

        assertEquals(View.INVISIBLE, visibilityAtCapture)
        assertEquals(View.VISIBLE, button.visibility)
    }

    @Test
    fun `the button follows a layout the host handles itself and stops following once detached`() {
        val state = FloatingButtonState(edge = ButtonEdge.RIGHT)
        val following = FloatingButtonDetector(state, {}, SdkLogger(LogLevel.NONE))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        following.attach(activity)
        ShadowLooper.idleMainLooper()
        val button = buttons(activity).single()
        val decor = activity.window.decorView
        assertTrue(button.x > decor.width / 2f)

        // A rotation the host handles itself changes the window size without a new activity.
        state.edge = ButtonEdge.LEFT
        decor.layout(0, 0, decor.width + 1, decor.height)
        ShadowLooper.idleMainLooper()
        assertTrue(button.x < decor.width / 2f)

        following.detach(activity)
        state.edge = ButtonEdge.RIGHT
        decor.layout(0, 0, decor.width + 2, decor.height)
        ShadowLooper.idleMainLooper()
        assertTrue("a removed button is left alone", button.x < decor.width / 2f)
    }
}
