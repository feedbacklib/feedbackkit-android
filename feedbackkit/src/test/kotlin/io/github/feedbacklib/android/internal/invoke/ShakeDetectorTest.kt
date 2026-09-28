package io.github.feedbacklib.android.internal.invoke

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
class ShakeDetectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var shakes = 0

    private fun detector(manager: SensorManager? = sensorManager) =
        ShakeDetector({ manager }, ShakeAlgorithm(650), { shakes++ }, SdkLogger(LogLevel.NONE))

    private fun addAccelerometer(): Sensor =
        ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER).also { shadowOf(sensorManager).addSensor(it) }

    @Test
    fun `listens to the accelerometer only between start and stop`() {
        addAccelerometer()
        val detector = detector()
        detector.start()
        assertTrue(shadowOf(sensorManager).hasListener(detector))
        detector.stop()
        assertFalse(shadowOf(sensorManager).hasListener(detector))
    }

    @Test
    fun `a jerk reported by the sensor invokes`() {
        val sensor = addAccelerometer()
        val detector = detector()
        detector.start()
        detector.onSensorChanged(SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(0f, 0f, 9.8f)).setTimestamp(0).build())
        detector.onSensorChanged(SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(5f, 5f, 10f)).setTimestamp(100_000_000).build())
        assertEquals(1, shakes)
    }

    @Test
    fun `a device without an accelerometer or sensor service does not crash`() {
        detector().start()
        detector(manager = null).start()
        assertEquals(0, shakes)
    }

    @Test
    fun `the sensor service is looked up at the first start, once, not when the detector is made`() {
        addAccelerometer()
        var lookups = 0
        val detector = ShakeDetector({ lookups++; sensorManager }, ShakeAlgorithm(650), { shakes++ }, SdkLogger(LogLevel.NONE))
        assertEquals(0, lookups)
        detector.start()
        detector.stop()
        detector.start()
        assertEquals(1, lookups)
        assertTrue(shadowOf(sensorManager).hasListener(detector))
        detector.stop()
    }

    @Test
    fun `a failing sensor service lookup leaves shake off and does not crash`() {
        val detector = ShakeDetector({ throw SecurityException("no sensors") }, ShakeAlgorithm(650), { shakes++ }, SdkLogger(LogLevel.NONE))
        detector.start()
        detector.stop()
        assertEquals(0, shakes)
    }
}
