package io.github.feedbacklib.android.internal.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VideoFrameSizeTest {

    @Test
    fun `a video frame is decoded at the tile height with its own aspect`() {
        assertEquals(144 to 320, videoFrameSize(720, 1600, 320))
        assertEquals(711 to 320, videoFrameSize(1600, 720, 320))
    }

    @Test
    fun `a frame is never scaled up and a size it cannot read stays as it is`() {
        assertEquals(96 to 160, videoFrameSize(96, 160, 320))
        assertEquals(0 to 0, videoFrameSize(0, 0, 320))
    }
}
