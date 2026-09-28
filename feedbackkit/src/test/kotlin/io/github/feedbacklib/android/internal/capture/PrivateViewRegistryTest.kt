package io.github.feedbacklib.android.internal.capture

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateViewRegistryTest {

    @After
    fun clear() = PrivateViewRegistry.clearForTests()

    private fun activityWith(child: View): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val root = FrameLayout(controller.get())
        root.addView(child, FrameLayout.LayoutParams(100, 50).apply { leftMargin = 10; topMargin = 20 })
        controller.get().setContentView(root)
        controller.visible()
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        return controller.get()
    }

    @Test
    fun `a registered view of this window yields its window rectangle`() {
        val secret = View(Robolectric.buildActivity(Activity::class.java).get())
        val activity = activityWith(secret)
        PrivateViewRegistry.add(secret)

        val regions = PrivateViewRegistry.regionsFor(activity.window.decorView)

        assertEquals(1, regions.size)
        val region = regions.single()
        val location = IntArray(2)
        secret.getLocationInWindow(location)
        assertEquals(location[0], region.left)
        assertEquals(location[1], region.top)
        assertEquals(100, region.width())
        assertEquals(50, region.height())
    }

    @Test
    fun `views of another window and removed views are not masked`() {
        val secret = View(Robolectric.buildActivity(Activity::class.java).get())
        activityWith(secret)
        val other = Robolectric.buildActivity(Activity::class.java).setup().get()
        PrivateViewRegistry.add(secret)
        assertTrue(PrivateViewRegistry.regionsFor(other.window.decorView).isEmpty())

        PrivateViewRegistry.remove(secret)
        assertTrue(PrivateViewRegistry.regionsFor(secret.rootView).isEmpty())
    }

    @Test
    fun `compose regions follow their root view and go away on removal`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val key = Any()
        PrivateViewRegistry.updateCompose(key, activity.window.decorView, 1, 2, 31, 42)
        assertEquals(listOf(Rect(1, 2, 31, 42)), PrivateViewRegistry.regionsFor(activity.window.decorView))

        PrivateViewRegistry.removeCompose(key)
        assertTrue(PrivateViewRegistry.regionsFor(activity.window.decorView).isEmpty())
    }
}
