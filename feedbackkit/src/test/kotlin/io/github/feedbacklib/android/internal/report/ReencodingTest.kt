package io.github.feedbacklib.android.internal.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReencodingTest {

    @Test
    fun `transparency is kept as png and anything opaque becomes jpeg`() {
        assertEquals(ReencodedFormat.PNG, Reencoding.formatFor(hasAlpha = true))
        assertEquals(ReencodedFormat.JPEG, Reencoding.formatFor(hasAlpha = false))
        assertEquals("png", ReencodedFormat.PNG.extension)
        assertEquals("jpg", ReencodedFormat.JPEG.extension)
        assertEquals("image/jpeg", MimeTypes.guess("gallery-1.${ReencodedFormat.JPEG.extension}"))
        assertEquals("image/png", MimeTypes.guess("gallery-1.${ReencodedFormat.PNG.extension}"))
    }

    @Test
    fun `the sample size is the smallest power of two that fits the longer side`() {
        val table = listOf(
            Triple(4096, 3072, 1),
            Triple(4097, 100, 2),
            Triple(100, 8192, 2),
            Triple(8193, 10, 4), // sampled by 2 it would round up to 4097
            Triple(12_000, 9_000, 4),
            Triple(0, 0, 1),
        )
        for ((width, height, expected) in table) assertEquals(expected, Reencoding.sampleSize(width, height), "${width}x$height")
        assertEquals(8, Reencoding.sampleSize(800, 10, maxSide = 100))
    }

    @Test
    fun `a row is opaque only when every pixel has full alpha`() {
        assertTrue(Reencoding.opaque(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF123456.toInt())))
        assertFalse(Reencoding.opaque(intArrayOf(0xFF000000.toInt(), 0xFE000000.toInt())))
        assertFalse(Reencoding.opaque(intArrayOf(0x00000000)))
        assertTrue(Reencoding.opaque(intArrayOf(0xFF000000.toInt(), 0x00000000), length = 1), "only the first [length] pixels count")
    }
}
