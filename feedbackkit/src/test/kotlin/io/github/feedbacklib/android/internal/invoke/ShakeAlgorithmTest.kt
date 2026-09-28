package io.github.feedbacklib.android.internal.invoke

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShakeAlgorithmTest {

    private val gravity = 9.8f

    @Test
    fun `a device lying still never shakes`() {
        val algorithm = ShakeAlgorithm(threshold = 650)
        assertFalse((0L..2_000L step 100).any { algorithm.onSample(0f, 0f, gravity, it) })
    }

    @Test
    fun `a sharp jerk above the threshold shakes`() {
        val algorithm = ShakeAlgorithm(threshold = 650)
        algorithm.onSample(0f, 0f, gravity, 0)
        // |20 - 9.8| / 100 ms * 10 000 = 1 020 > 650
        assertTrue(algorithm.onSample(5f, 5f, 10f, 100))
    }

    @Test
    fun `a gentle move below the threshold does not shake`() {
        val algorithm = ShakeAlgorithm(threshold = 650)
        algorithm.onSample(0f, 0f, gravity, 0)
        // |14.8 - 9.8| / 100 * 10 000 = 500 < 650
        assertFalse(algorithm.onSample(2f, 3f, gravity, 100))
    }

    @Test
    fun `a higher threshold makes the same jerk too weak`() {
        val algorithm = ShakeAlgorithm(threshold = 1_100)
        algorithm.onSample(0f, 0f, gravity, 0)
        assertFalse(algorithm.onSample(5f, 5f, 10f, 100))
    }

    @Test
    fun `samples closer than the interval are ignored`() {
        val algorithm = ShakeAlgorithm(threshold = 650)
        algorithm.onSample(0f, 0f, gravity, 0)
        assertFalse(algorithm.onSample(5f, 5f, 10f, 50))
    }

    @Test
    fun `a second shake inside the cooldown is swallowed`() {
        val algorithm = ShakeAlgorithm(threshold = 650)
        algorithm.onSample(0f, 0f, gravity, 0)
        assertTrue(algorithm.onSample(5f, 5f, 10f, 100))
        assertFalse(algorithm.onSample(0f, 0f, gravity, 200))
        assertFalse(algorithm.onSample(5f, 5f, 10f, 300))
        algorithm.onSample(0f, 0f, gravity, 1_700)
        assertTrue(algorithm.onSample(5f, 5f, 10f, 1_800))
    }
}
