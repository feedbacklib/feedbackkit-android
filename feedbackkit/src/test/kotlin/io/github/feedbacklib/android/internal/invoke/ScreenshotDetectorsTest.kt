package io.github.feedbacklib.android.internal.invoke

import android.Manifest
import android.app.Activity
import android.app.Application
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ScreenshotDetectorsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val logger = SdkLogger(LogLevel.NONE)
    private var screenshots = 0

    private class RecordingRegistrar : ScreenCaptureRegistrar {
        val registered = mutableListOf<Activity.ScreenCaptureCallback>()
        override fun register(activity: Activity, callback: Activity.ScreenCaptureCallback) { registered += callback }
        override fun unregister(activity: Activity, callback: Activity.ScreenCaptureCallback) { registered -= callback }
    }

    @Test
    fun `api 34 detector registers on attach, fires, and unregisters on detach`() {
        val registrar = RecordingRegistrar()
        val detector = ScreenCaptureCallbackDetector(registrar, { screenshots++ }, logger)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        detector.attach(activity)
        registrar.registered.single().onScreenCaptured()
        detector.detach(activity)

        assertEquals(1, screenshots)
        assertTrue(registrar.registered.isEmpty())
    }

    @Test
    fun `api 34 detector survives a registrar that throws`() {
        val throwing = object : ScreenCaptureRegistrar {
            override fun register(activity: Activity, callback: Activity.ScreenCaptureCallback) = throw SecurityException("no permission")
            override fun unregister(activity: Activity, callback: Activity.ScreenCaptureCallback) = throw SecurityException("no permission")
        }
        val detector = ScreenCaptureCallbackDetector(throwing, { screenshots++ }, logger)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        detector.attach(activity)
        detector.detach(activity)
        assertEquals(0, screenshots)
    }

    @Test
    @Config(sdk = [33])
    fun `media store detector without the media permission stays off`() {
        val detector = MediaStoreScreenshotDetector(app, { screenshots++ }, logger)
        detector.start()
        assertTrue(shadowOf(app.contentResolver).getContentObservers(MediaStore.Images.Media.EXTERNAL_CONTENT_URI).isEmpty())
    }

    @Test
    @Config(sdk = [33])
    fun `media store detector with the permission observes images until stopped`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        val detector = MediaStoreScreenshotDetector(app, { screenshots++ }, logger)
        detector.start()
        assertEquals(1, shadowOf(app.contentResolver).getContentObservers(MediaStore.Images.Media.EXTERNAL_CONTENT_URI).size)
        detector.stop()
        assertTrue(shadowOf(app.contentResolver).getContentObservers(MediaStore.Images.Media.EXTERNAL_CONTENT_URI).isEmpty())

        detector.start()
        detector.stop()
        assertTrue(shadowOf(app.contentResolver).getContentObservers(MediaStore.Images.Media.EXTERNAL_CONTENT_URI).isEmpty())
    }
}
