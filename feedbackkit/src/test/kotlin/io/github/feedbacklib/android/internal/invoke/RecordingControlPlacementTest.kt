package io.github.feedbacklib.android.internal.invoke

import io.github.feedbacklib.android.RecordingButtonPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecordingControlPlacementTest {

    private val insets = BarInsets(left = 10, top = 60, right = 20, bottom = 120)

    @Test
    fun `each corner keeps clear of the system bars on its own sides`() {
        assertEquals(ControlPlacement(right = false, bottom = false, marginLeft = 26, marginTop = 76, marginRight = 0, marginBottom = 0), RecordingControlPlacement.of(RecordingButtonPosition.TOP_LEFT, insets, 16))
        assertEquals(ControlPlacement(right = true, bottom = false, marginLeft = 0, marginTop = 76, marginRight = 36, marginBottom = 0), RecordingControlPlacement.of(RecordingButtonPosition.TOP_RIGHT, insets, 16))
        assertEquals(ControlPlacement(right = false, bottom = true, marginLeft = 26, marginTop = 0, marginRight = 0, marginBottom = 136), RecordingControlPlacement.of(RecordingButtonPosition.BOTTOM_LEFT, insets, 16))
        assertEquals(ControlPlacement(right = true, bottom = true, marginLeft = 0, marginTop = 0, marginRight = 36, marginBottom = 136), RecordingControlPlacement.of(RecordingButtonPosition.BOTTOM_RIGHT, insets, 16))
    }

    @Test
    fun `elapsed time reads as minutes and seconds`() {
        assertEquals("0:00", RecordingControlPlacement.elapsedText(0))
        assertEquals("0:00", RecordingControlPlacement.elapsedText(-5))
        assertEquals("0:05", RecordingControlPlacement.elapsedText(5_999))
        assertEquals("0:59", RecordingControlPlacement.elapsedText(59_999))
        assertEquals("1:00", RecordingControlPlacement.elapsedText(60_000))
    }
}
