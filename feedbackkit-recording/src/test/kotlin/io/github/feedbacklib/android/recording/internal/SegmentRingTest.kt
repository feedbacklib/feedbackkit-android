package io.github.feedbacklib.android.recording.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SegmentRingTest {

    private val a = File("segment-0.mp4")
    private val b = File("segment-1.mp4")
    private val c = File("segment-2.mp4")
    private val d = File("segment-3.mp4")
    private val e = File("segment-4.mp4")

    @Test
    fun `the segment being written counts, so three finished ones are kept beside it`() {
        val ring = SegmentRing(capacity = 4)
        assertTrue(ring.begin(a).isEmpty())
        assertTrue(ring.begin(b).isEmpty())
        assertTrue(ring.begin(c).isEmpty())
        assertTrue(ring.begin(d).isEmpty())
        assertEquals(listOf(a), ring.begin(e))
        assertEquals(listOf(b, c, d), ring.segments())
        assertEquals(e, ring.current)
    }

    @Test
    fun `a finished current segment stays as the newest one`() {
        val ring = SegmentRing(capacity = 4)
        ring.begin(a)
        ring.begin(b)
        assertTrue(ring.finishCurrent(usable = true).isEmpty())
        assertEquals(listOf(a, b), ring.segments())
        assertNull(ring.current)
    }

    @Test
    fun `an unusable current segment is dropped`() {
        val ring = SegmentRing(capacity = 4)
        ring.begin(a)
        ring.begin(b)
        assertEquals(listOf(b), ring.finishCurrent(usable = false))
        assertEquals(listOf(a), ring.segments())
    }

    @Test
    fun `after a pause four finished segments are kept and the next one pushes the oldest out`() {
        val ring = SegmentRing(capacity = 4)
        listOf(a, b, c, d).forEach { ring.begin(it) }
        ring.finishCurrent(usable = true)
        assertEquals(listOf(a, b, c, d), ring.segments())
        assertEquals(listOf(a), ring.begin(e))
        assertEquals(listOf(b, c, d), ring.segments())
    }

    @Test
    fun `a segment name already known to the ring is refused`() {
        val ring = SegmentRing(capacity = 4)
        ring.begin(a)
        assertThrows(IllegalArgumentException::class.java) { ring.begin(a) }
        ring.begin(b)
        assertThrows(IllegalArgumentException::class.java) { ring.begin(a) }
    }

    @Test
    fun `clear hands back every file, the current one too`() {
        val ring = SegmentRing(capacity = 4)
        ring.begin(a)
        ring.begin(b)
        assertEquals(listOf(a, b), ring.clear())
        assertTrue(ring.segments().isEmpty())
        assertNull(ring.current)
        assertThrows(IllegalArgumentException::class.java) { SegmentRing(capacity = 0) }
    }
}
