package io.github.feedbacklib.android.internal.invoke

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FloatingButtonGeometryTest {

    private val noInsets = BarInsets.NONE

    @Test
    fun `right edge sits a margin away from the right side`() {
        val (x, y) = FloatingButtonGeometry.position(ButtonEdge.RIGHT, 300, 1_000, 2_000, 150, 20, noInsets)
        assertEquals(1_000f - 150 - 20, x)
        assertEquals(300f, y)
    }

    @Test
    fun `left edge respects the left inset`() {
        val (x, _) = FloatingButtonGeometry.position(ButtonEdge.LEFT, 0, 1_000, 2_000, 150, 20, BarInsets(30, 0, 0, 0))
        assertEquals(50f, x)
    }

    @Test
    fun `offset is measured below the status bar and clamped above the navigation bar`() {
        val insets = BarInsets(0, 80, 0, 120)
        assertEquals(80f + 100, FloatingButtonGeometry.position(ButtonEdge.RIGHT, 100, 1_000, 2_000, 150, 20, insets).second)
        assertEquals(2_000f - 120 - 150 - 20, FloatingButtonGeometry.position(ButtonEdge.RIGHT, 5_000, 1_000, 2_000, 150, 20, insets).second)
    }

    @Test
    fun `dropping snaps to the nearest edge`() {
        assertEquals(ButtonEdge.LEFT, FloatingButtonGeometry.nearestEdge(300f, 1_000))
        assertEquals(ButtonEdge.RIGHT, FloatingButtonGeometry.nearestEdge(700f, 1_000))
    }
}
