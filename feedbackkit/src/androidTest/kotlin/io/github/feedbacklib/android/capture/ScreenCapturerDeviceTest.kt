package io.github.feedbacklib.android.capture

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.feedbackKitPrivate
import io.github.feedbacklib.android.internal.capture.PrivateViewRegistry
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.graphics.Color as ComposeColor

@RunWith(AndroidJUnit4::class)
class ScreenCapturerDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val capturer = ScreenCapturer({ File(context.cacheDir, "capture-test") }, SdkLogger(LogLevel.VERBOSE))

    @After
    fun clear() = PrivateViewRegistry.clearForTests()

    private fun captureOn(activity: android.app.Activity): ScreenCapturer.Result {
        val latch = CountDownLatch(1)
        var result: ScreenCapturer.Result = ScreenCapturer.Result.Failed
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            capturer.capture(activity) { result = it; latch.countDown() }
        }
        assertTrue("capture did not finish", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test
    fun privateViewIsBlackAndTheRestIsNot() {
        ActivityScenario.launch(RedActivity::class.java).use { scenario ->
            var activity: RedActivity? = null
            scenario.onActivity { activity = it; PrivateViewRegistry.add(it.secret) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            val result = captureOn(activity!!)
            assertTrue("expected Saved but was $result", result is ScreenCapturer.Result.Saved)
            val saved = result as ScreenCapturer.Result.Saved
            val bitmap = BitmapFactory.decodeFile(saved.file.absolutePath)
            val location = IntArray(2).also { l -> scenario.onActivity { it.secret.getLocationInWindow(l) } }

            assertEquals(Color.BLACK, bitmap.getPixel(location[0] + 100, location[1] + 100))
            assertEquals(Color.RED, bitmap.getPixel(location[0] + 350, location[1] + 100))
        }
    }

    @Test
    fun secureWindowIsNeverCaptured() {
        ActivityScenario.launch(SecureActivity::class.java).use { scenario ->
            var activity: SecureActivity? = null
            scenario.onActivity { activity = it }
            assertEquals(ScreenCapturer.Result.Secure, captureOn(activity!!))
        }
    }

    @Test
    fun privateComposableIsBlack() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ComposeColor.Red)) {
                Box(Modifier.offset(40.dp, 200.dp).size(80.dp).background(ComposeColor.Blue).feedbackKitPrivate())
            }
        }
        compose.waitForIdle()
        val result = captureOn(compose.activity)
        assertTrue("expected Saved but was $result", result is ScreenCapturer.Result.Saved)
        val saved = result as ScreenCapturer.Result.Saved
        val bitmap = BitmapFactory.decodeFile(saved.file.absolutePath)
        val density = context.resources.displayMetrics.density
        val centerX = ((40 + 40) * density).toInt()
        val centerY = ((200 + 40) * density).toInt()
        val contentLocation = IntArray(2).also {
            compose.activity.window.decorView.findViewById<android.view.View>(android.R.id.content).getLocationInWindow(it)
        }
        val offsetX = contentLocation[0]
        val offsetY = contentLocation[1]
        assertEquals(Color.BLACK, bitmap.getPixel(centerX + offsetX, centerY + offsetY))
        val tenDp = (10 * density).toInt()
        assertEquals(Color.RED, bitmap.getPixel(offsetX + tenDp, offsetY + tenDp))
    }
}
