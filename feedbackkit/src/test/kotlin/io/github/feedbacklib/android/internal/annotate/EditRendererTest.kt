package io.github.feedbacklib.android.internal.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditRendererTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val gray = 0xFF808080.toInt()
    private val pen = PenColor.RED.argb

    private fun surface(width: Int, height: Int) = IntArraySurface(width, height).apply { fill(white) }

    private val line = EditOp.Stroke(pen, 4f, listOf(0f, 16.5f, 32f, 16.5f))
    private val everything = EditOp.Blur(0f, 0f, 32f, 32f)

    @Test
    fun `a blur after a stroke hides it for good`() {
        val s = surface(32, 32)
        EditRenderer.render(s, listOf(line, everything), 1f)
        assertFalse(s.pixels.any { it == pen })
        for (y in 0 until 16) for (x in 0 until 16) assertEquals(s[0, 0], s[x, y])
    }

    @Test
    fun `a stroke after a blur stays on top`() {
        val s = surface(32, 32)
        EditRenderer.render(s, listOf(everything, line), 1f)
        assertEquals(pen, s[8, 16])
    }

    @Test
    fun `a half-size preview blurs the same area in 8 px blocks`() {
        // The preview stands for a 32×32 image: columns 0..3 black, the rest white.
        val s = surface(16, 16)
        for (y in 0 until 16) for (x in 0 until 4) s[x, y] = black
        EditRenderer.render(s, listOf(everything), 0.5f)
        assertEquals(gray, s[0, 0]) // 4 black + 4 white in the first 8 px block
        assertEquals(white, s[8, 0])
    }

    @Test
    fun `scale shrinks stroke width and position with the preview`() {
        val s = surface(16, 16)
        EditRenderer.render(s, listOf(line), 0.5f)
        assertEquals(pen, s[4, 8])
        assertEquals(white, s[4, 12])
    }

    @Test
    fun `a magnifier renders through the same surface`() {
        val s = surface(64, 64)
        EditRenderer.render(s, listOf(EditOp.Magnifier(32f, 32f, 20f)), 1f)
        assertTrue(s.pixels.any { it != white }, "the ring is drawn")
    }
}
