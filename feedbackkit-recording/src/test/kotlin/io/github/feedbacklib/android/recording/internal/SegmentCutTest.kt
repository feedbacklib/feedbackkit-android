package io.github.feedbacklib.android.recording.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SegmentCutTest {

    private val frame = 33_333L

    @Test
    fun `samples before the cut are dropped, not stacked onto its start`() {
        val cut = SegmentCut(baseUs = 0, fromUs = 1_000_000)
        assertNull(cut.place(900_000, sync = true))
        assertNull(cut.place(966_000, sync = false))
        assertEquals(0L, cut.place(1_000_000, sync = true))
        assertEquals(33_000L, cut.place(1_033_000, sync = false))
    }

    @Test
    fun `the first sample written is a key frame`() {
        val cut = SegmentCut(baseUs = 500_000, fromUs = 0)
        assertNull(cut.place(0, sync = false))
        assertNull(cut.place(33_000, sync = false))
        assertEquals(500_000L, cut.place(66_000, sync = true))
        assertEquals(533_000L, cut.place(99_000, sync = false))
    }

    @Test
    fun `a sample earlier than the key frame the segment opened on is dropped`() {
        val cut = SegmentCut(baseUs = 0, fromUs = 0)
        cut.place(100_000, sync = true)
        assertNull(cut.place(66_000, sync = false))
    }

    @Test
    fun `the next segment is laid one frame after the latest sample written`() {
        val cut = SegmentCut(baseUs = 1_000_000, fromUs = 0)
        cut.place(10_000, sync = true)
        cut.place(310_000, sync = false)
        cut.place(210_000, sync = false) // out of presentation order
        assertTrue(cut.wroteAny)
        assertEquals(1_000_000 + 300_000 + frame, cut.nextBase(frame))
    }

    @Test
    fun `a segment that wrote nothing leaves the base where it was`() {
        val cut = SegmentCut(baseUs = 700_000, fromUs = 0)
        assertNull(cut.place(0, sync = false))
        assertFalse(cut.wroteAny)
        assertEquals(700_000L, cut.nextBase(frame))
    }
}
