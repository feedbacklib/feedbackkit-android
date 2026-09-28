package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class CaptureOverlayTest {

    private val taps = mutableListOf<String>()
    private val logger = SdkLogger(LogLevel.NONE)
    private val overlay = CaptureOverlay({ 0xFF1565C0.toInt() }, { taps += "capture" }, { taps += "cancel" }, logger)

    private fun panels(activity: Activity): List<CaptureOverlayView> {
        val decor = activity.window.decorView as ViewGroup
        return (0 until decor.childCount).map { decor.getChildAt(it) }.filterIsInstance<CaptureOverlayView>()
    }

    private fun host(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `the controls sit once on the host screen and report their taps`() {
        val activity = host()
        overlay.show(activity)
        overlay.show(activity)
        val panel = panels(activity).single()
        panel.findViewWithTag<Button>(CaptureOverlayView.TAG_CAPTURE).performClick()
        panel.findViewWithTag<Button>(CaptureOverlayView.TAG_CANCEL).performClick()
        assertEquals(listOf("capture", "cancel"), taps)
    }

    @Test
    fun `the buttons are labelled from resources and large enough to tap`() {
        val activity = host()
        overlay.show(activity)
        val panel = panels(activity).single()
        val capture = panel.findViewWithTag<Button>(CaptureOverlayView.TAG_CAPTURE)
        val cancel = panel.findViewWithTag<Button>(CaptureOverlayView.TAG_CANCEL)
        assertEquals(activity.getString(R.string.feedbackkit_capture_take), capture.text.toString())
        assertEquals(activity.getString(R.string.feedbackkit_capture_cancel), cancel.text.toString())
        assertEquals(activity.getString(R.string.feedbackkit_capture_take), capture.contentDescription)
        assertEquals(activity.getString(R.string.feedbackkit_capture_cancel), cancel.contentDescription)
        val min = 48 * activity.resources.displayMetrics.density
        assertTrue(capture.minimumHeight >= min && cancel.minimumHeight >= min)
    }

    @Test
    fun `hide removes the controls of one screen and hideAll of every screen`() {
        val first = host()
        val second = host()
        overlay.show(first)
        overlay.show(second)
        overlay.hide(first)
        assertEquals(0, panels(first).size)
        assertEquals(1, panels(second).size)
        overlay.hideAll()
        assertEquals(0, panels(second).size)
    }

    @Test
    fun `a throwing tap handler does not reach the host`() {
        val errors = mutableListOf<String>()
        val recording = SdkLogger(LogLevel.ERROR) { level, _, message, _ -> if (level == LogLevel.ERROR) errors += message }
        val throwing = CaptureOverlay({ 0xFF1565C0.toInt() }, { error("host bug") }, { error("host bug") }, recording)
        val activity = host()
        throwing.show(activity)
        val panel = panels(activity).single()
        panel.findViewWithTag<Button>(CaptureOverlayView.TAG_CAPTURE).performClick()
        panel.findViewWithTag<Button>(CaptureOverlayView.TAG_CANCEL).performClick()
        assertEquals("the controls stay, exactly once", 1, panels(activity).size)
        assertEquals(2, errors.size)
    }

    @Test
    fun `the controls are hidden for the screenshot and shown again after it`() {
        val activity = host()
        overlay.show(activity)
        val panel = panels(activity).single()
        var visibilityAtCapture: Int? = null
        val results = mutableListOf<ScreenCapturer.Result>()

        HidingCapture(overlay, { _, callback ->
            visibilityAtCapture = panel.visibility
            callback(ScreenCapturer.Result.Failed)
        }, logger).capture(activity) { results += it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(View.INVISIBLE, visibilityAtCapture)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(listOf(ScreenCapturer.Result.Failed), results)
    }

    @Test
    fun `the controls follow a layout the host handles itself and stop following once hidden`() {
        val activity = host()
        overlay.show(activity)
        ShadowLooper.idleMainLooper()
        val panel = panels(activity).single()
        val params = panel.layoutParams as FrameLayout.LayoutParams
        val placed = params.bottomMargin
        assertTrue(placed > 0)

        // configChanges="orientation|screenSize": no new activity, only a new layout of the window.
        params.bottomMargin = 0
        val decor = activity.window.decorView
        decor.layout(0, 0, decor.width + 1, decor.height)
        ShadowLooper.idleMainLooper()
        assertEquals(placed, (panel.layoutParams as FrameLayout.LayoutParams).bottomMargin)

        overlay.hide(activity)
        params.bottomMargin = 0
        decor.layout(0, 0, decor.width + 2, decor.height)
        ShadowLooper.idleMainLooper()
        assertEquals("a removed panel is left alone", 0, params.bottomMargin)
    }
}
