package io.github.feedbacklib.android.recording.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClipMathTest {

    private val s = 1_000_000L

    @Test
    fun `the window starts inside the segment where the last thirty seconds begin`() {
        assertEquals(ClipPlan(0, 3 * s), ClipMath.plan(listOf(10 * s, 10 * s, 10 * s, 3 * s), 30 * s))
        assertEquals(ClipPlan(1, 0), ClipMath.plan(listOf(10 * s, 10 * s, 10 * s), 20 * s))
    }

    @Test
    fun `less recorded than the window keeps everything`() {
        assertEquals(ClipPlan(0, 0), ClipMath.plan(listOf(5 * s, 5 * s), 30 * s))
    }

    @Test
    fun `an empty segment in the middle is stepped over`() {
        assertEquals(ClipPlan(0, 5 * s), ClipMath.plan(listOf(10 * s, 0, 10 * s), 15 * s))
    }

    @Test
    fun `nothing recorded or no window gives no plan`() {
        assertNull(ClipMath.plan(emptyList(), 30 * s))
        assertNull(ClipMath.plan(listOf(10 * s), 0))
    }

    @Test
    fun `segments that exist but hold zero recorded time still produce a plan, not null`() {
        assertEquals(ClipPlan(0, 0), ClipMath.plan(listOf(0L, 0L), 30 * s))
    }

    @Test
    fun `what the plan keeps is never longer than the window`() {
        val cases = listOf(listOf(10 * s, 10 * s, 10 * s, 10 * s), listOf(1 * s, 29 * s, 7 * s), listOf(31 * s), listOf(3 * s, 3 * s))
        cases.forEach { durations ->
            val plan = ClipMath.plan(durations, 30 * s)!!
            val kept = durations.drop(plan.firstSegment).sum() - plan.startOffsetUs
            assertEquals(minOf(30 * s, durations.sum()), kept, "durations $durations")
        }
    }

    @Test
    fun `segments are laid end to end, one frame apart`() {
        assertEquals(0L, ClipMath.outputTime(baseUs = 0, segmentStartUs = 1_000, sampleTimeUs = 1_000))
        assertEquals(33_333L, ClipMath.outputTime(baseUs = 0, segmentStartUs = 1_000, sampleTimeUs = 34_333))
        assertEquals(500_000L, ClipMath.outputTime(baseUs = 500_000, segmentStartUs = 1_000, sampleTimeUs = 0))
        assertEquals(333_333L, ClipMath.nextBase(baseUs = 0, segmentStartUs = 1_000, lastSampleUs = 301_000, frameUs = 33_333))
    }

    @Test
    fun `only the newest run of segments of one format is kept`() {
        assertEquals(0, ClipMath.newestRunStart(listOf("a", "a", "a")))
        assertEquals(2, ClipMath.newestRunStart(listOf("a", "a", "b", "b")))
        assertEquals(3, ClipMath.newestRunStart(listOf("a", "b", "a", "c")))
        assertEquals(0, ClipMath.newestRunStart(emptyList<String>()))
    }
}
