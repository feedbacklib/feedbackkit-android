package io.github.feedbacklib.android.internal.invoke

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlin.math.abs

/**
 * The classic "speed of change" shake detector (spec §5): at most every [sampleIntervalMillis],
 * speed = |Δ(x+y+z)| / Δt(ms) × 10 000; a shake is speed above [threshold], at most once per
 * [cooldownMillis]. A higher threshold means a less sensitive device.
 */
internal class ShakeAlgorithm(
    threshold: Int,
    private val cooldownMillis: Long = 1_500,
    private val sampleIntervalMillis: Long = 100,
) {
    @Volatile
    var threshold: Int = threshold

    private var lastSampleAt = NO_SAMPLE
    private var lastSum = 0f
    private var lastShakeAt = NO_SAMPLE

    fun onSample(x: Float, y: Float, z: Float, timeMillis: Long): Boolean {
        val sum = x + y + z
        if (lastSampleAt == NO_SAMPLE) {
            lastSampleAt = timeMillis
            lastSum = sum
            return false
        }
        val elapsed = timeMillis - lastSampleAt
        if (elapsed < sampleIntervalMillis) return false
        val speed = abs(sum - lastSum) / elapsed * SPEED_SCALE
        lastSampleAt = timeMillis
        lastSum = sum
        val cooledDown = lastShakeAt == NO_SAMPLE || timeMillis - lastShakeAt >= cooldownMillis
        if (speed > threshold && cooledDown) {
            lastShakeAt = timeMillis
            return true
        }
        return false
    }

    fun reset() {
        lastSampleAt = NO_SAMPLE
        lastShakeAt = NO_SAMPLE
    }

    private companion object {
        const val NO_SAMPLE = Long.MIN_VALUE
        const val SPEED_SCALE = 10_000f
    }
}

/**
 * Shake invocation (spec §5) from the accelerometer. The sensor service is looked up at the first
 * [start], not when the detector is made: build() on the main thread stays clear of it. Main thread.
 */
internal class ShakeDetector(
    private val sensorManagerSupplier: () -> SensorManager?,
    private val algorithm: ShakeAlgorithm,
    private val onShake: () -> Unit,
    private val logger: SdkLogger,
) : ProcessDetector, SensorEventListener {

    private val sensorManager: SensorManager? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            sensorManagerSupplier()
        } catch (e: Exception) {
            logger.w("Could not reach the sensor service", e)
            null
        }
    }

    private var listening = false

    override fun start() {
        if (listening) return
        val manager = sensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            logger.w("Shake invocation unavailable: no accelerometer")
            return
        }
        algorithm.reset()
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            val values = event.values
            if (values.size < 3) return
            if (algorithm.onSample(values[0], values[1], values[2], event.timestamp / NANOS_IN_MILLI)) onShake()
        } catch (e: Exception) {
            logger.e("Shake detection failed", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val NANOS_IN_MILLI = 1_000_000L
    }
}
