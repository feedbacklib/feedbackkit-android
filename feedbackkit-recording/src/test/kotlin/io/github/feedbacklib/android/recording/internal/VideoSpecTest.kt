package io.github.feedbacklib.android.recording.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class VideoSpecTest {

    @Test
    fun `a full hd phone records at 720 on the short side with the density scaled alike`() {
        assertEquals(VideoSpec(720, 1600, 280), VideoSpec.fit(1080, 2400, 420))
        assertEquals(VideoSpec(1600, 720, 280), VideoSpec.fit(2400, 1080, 420))
    }

    @Test
    fun `sides are rounded down to whole macroblocks`() {
        // 1440x3120 halves to 720x1560; 1560 is not a multiple of 16.
        assertEquals(VideoSpec(720, 1552, 280), VideoSpec.fit(1440, 3120, 560))
    }

    @Test
    fun `a small display is never scaled up, only aligned`() {
        assertEquals(VideoSpec(720, 1280, 320), VideoSpec.fit(720, 1280, 320))
        assertEquals(VideoSpec(688, 1488, 240), VideoSpec.fit(700, 1500, 240))
        assertEquals(VideoSpec(16, 16, 160), VideoSpec.fit(10, 10, 160))
    }

    @Test
    fun `thirty frames a second at about four megabits and no display size is refused`() {
        val spec = VideoSpec.fit(1080, 2400, 420)
        assertEquals(30, spec.frameRate)
        assertEquals(4_000_000, spec.bitRate)
        assertThrows(IllegalArgumentException::class.java) { VideoSpec.fit(0, 2400, 420) }
    }
}

class VideoSpecEncoderFitTest {

    private val fullHd = VideoSpec.fit(1080, 2400, 420) // 720x1600 at 280 dpi

    @Test
    fun `a size the encoder takes is kept`() {
        assertEquals(fullHd, fullHd.fitEncoder { _, _ -> true })
    }

    @Test
    fun `an encoder limited to 1088 lines gets the largest smaller size of the same aspect`() {
        // 640 -> 640x1408 and 576 -> 576x1280 are still too tall; 480 -> 480x1056 fits.
        val fitted = fullHd.fitEncoder { width, height -> width <= 1920 && height <= 1088 }
        assertEquals(VideoSpec(480, 1056, 186), fitted)
    }

    @Test
    fun `smaller sizes keep whole macroblocks, the frame rate and the bit rate`() {
        val tried = mutableListOf<Pair<Int, Int>>()
        fullHd.fitEncoder { width, height -> tried += width to height; false }
        assertEquals(720 to 1600, tried.first())
        assertEquals(tried.sortedByDescending { it.first }, tried)
        tried.forEach { (width, height) -> assertEquals(0, width % 16); assertEquals(0, height % 16) }
        val fitted = fullHd.fitEncoder { width, _ -> width < 400 }!! // 360 aligns down to 352
        assertEquals(30, fitted.frameRate)
        assertEquals(4_000_000, fitted.bitRate)
    }

    @Test
    fun `no size at all gives null, and a small display is never scaled up`() {
        assertEquals(null, fullHd.fitEncoder { _, _ -> false })
        val tried = mutableListOf<Int>()
        VideoSpec.fit(480, 854, 240).fitEncoder { width, _ -> tried += width; false }
        assertEquals(480, tried.first())
        assertEquals(true, tried.drop(1).all { it < 480 })
    }
}
