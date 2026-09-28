package io.github.feedbacklib.android.internal.invoke

import android.view.MotionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TwoFingerSwipeRecognizerTest {

    private val width = 1_000

    private fun two(x: Float, y: Float = 500f) = listOf(Pointer(0, x, y), Pointer(1, x, y + 100f))

    private fun TwoFingerSwipeRecognizer.swipe(toX: Float, toY: Float = 500f, durationMillis: Long = 200): List<Boolean> = listOf(
        onEvent(MotionEvent.ACTION_DOWN, listOf(Pointer(0, 900f, 500f)), 0, width),
        onEvent(MotionEvent.ACTION_POINTER_DOWN, two(900f), 10, width),
        onEvent(MotionEvent.ACTION_MOVE, two(700f, (500f + toY) / 2), 10 + durationMillis / 2, width),
        onEvent(MotionEvent.ACTION_MOVE, two(toX, toY), 10 + durationMillis, width),
        onEvent(MotionEvent.ACTION_UP, listOf(Pointer(0, toX, toY)), 20 + durationMillis, width),
    )

    @Test
    fun `a quick two-finger swipe to the left fires exactly once`() {
        assertEquals(1, TwoFingerSwipeRecognizer().swipe(toX = 500f).count { it })
    }

    @Test
    fun `a swipe shorter than a quarter of the width does not fire`() {
        assertFalse(TwoFingerSwipeRecognizer().swipe(toX = 700f).any { it })
    }

    @Test
    fun `a slow swipe does not fire`() {
        assertFalse(TwoFingerSwipeRecognizer().swipe(toX = 500f, durationMillis = 900).any { it })
    }

    @Test
    fun `a mostly vertical move does not fire`() {
        assertFalse(TwoFingerSwipeRecognizer().swipe(toX = 650f, toY = 900f).any { it })
    }

    @Test
    fun `a swipe to the right does not fire`() {
        val recognizer = TwoFingerSwipeRecognizer()
        recognizer.onEvent(MotionEvent.ACTION_DOWN, listOf(Pointer(0, 100f, 500f)), 0, width)
        recognizer.onEvent(MotionEvent.ACTION_POINTER_DOWN, two(100f), 10, width)
        assertFalse(recognizer.onEvent(MotionEvent.ACTION_MOVE, two(600f), 100, width))
    }

    @Test
    fun `a one-finger swipe does not fire`() {
        val recognizer = TwoFingerSwipeRecognizer()
        recognizer.onEvent(MotionEvent.ACTION_DOWN, listOf(Pointer(0, 900f, 500f)), 0, width)
        assertFalse(recognizer.onEvent(MotionEvent.ACTION_MOVE, listOf(Pointer(0, 100f, 500f)), 100, width))
    }

    @Test
    fun `one finger dragging while the other stays put does not fire`() {
        val recognizer = TwoFingerSwipeRecognizer()
        recognizer.onEvent(MotionEvent.ACTION_DOWN, listOf(Pointer(0, 900f, 500f)), 0, width)
        recognizer.onEvent(MotionEvent.ACTION_POINTER_DOWN, two(900f), 10, width)
        // The average travel is 300 px (> 25 % of 1 000), but the second finger never moved.
        assertFalse(recognizer.onEvent(MotionEvent.ACTION_MOVE, listOf(Pointer(0, 300f, 500f), Pointer(1, 900f, 600f)), 200, width))
    }

    @Test
    fun `lifting a finger ends the gesture even if two fingers move on`() {
        val recognizer = TwoFingerSwipeRecognizer()
        recognizer.onEvent(MotionEvent.ACTION_DOWN, listOf(Pointer(0, 900f, 500f)), 0, width)
        recognizer.onEvent(MotionEvent.ACTION_POINTER_DOWN, two(900f), 10, width)
        recognizer.onEvent(MotionEvent.ACTION_POINTER_UP, two(850f), 50, width)
        assertFalse(recognizer.onEvent(MotionEvent.ACTION_MOVE, two(500f), 150, width))
    }
}
