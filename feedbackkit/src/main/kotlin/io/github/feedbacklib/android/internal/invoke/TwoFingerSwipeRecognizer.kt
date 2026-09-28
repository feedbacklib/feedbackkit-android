package io.github.feedbacklib.android.internal.invoke

import android.view.MotionEvent
import kotlin.math.abs

internal data class Pointer(val id: Int, val x: Float, val y: Float)

/**
 * Two fingers each moving right-to-left by more than [minDistanceFraction] of the window width
 * within [maxDurationMillis], mostly horizontally (spec §5). Fires once per gesture.
 */
internal class TwoFingerSwipeRecognizer(
    private val minDistanceFraction: Float = 0.25f,
    private val maxDurationMillis: Long = 600,
) {
    private var start: Map<Int, Pointer>? = null
    private var startTime = 0L
    private var fired = false

    fun onEvent(actionMasked: Int, pointers: List<Pointer>, timeMillis: Long, windowWidth: Int): Boolean {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> reset()
            MotionEvent.ACTION_POINTER_DOWN ->
                if (pointers.size == 2) {
                    start = pointers.associateBy { it.id }
                    startTime = timeMillis
                    fired = false
                } else {
                    start = null
                }
            MotionEvent.ACTION_MOVE -> return onMove(pointers, timeMillis, windowWidth)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> start = null
        }
        return false
    }

    private fun onMove(pointers: List<Pointer>, timeMillis: Long, windowWidth: Int): Boolean {
        val origin = start ?: return false
        if (fired || pointers.size != 2 || windowWidth <= 0) return false
        if (timeMillis - startTime > maxDurationMillis) {
            start = null
            return false
        }
        // Every finger must travel far enough, mostly horizontally: one finger dragging while the
        // other rests (a pinch, a scroll with a resting thumb) is not a swipe.
        val minDistance = minDistanceFraction * windowWidth
        for (pointer in pointers) {
            val from = origin[pointer.id] ?: return false
            val dx = pointer.x - from.x
            val dy = pointer.y - from.y
            if (dx >= -minDistance || abs(dy) >= abs(dx) / 2) return false
        }
        fired = true
        return true
    }

    private fun reset() {
        start = null
        fired = false
    }
}
