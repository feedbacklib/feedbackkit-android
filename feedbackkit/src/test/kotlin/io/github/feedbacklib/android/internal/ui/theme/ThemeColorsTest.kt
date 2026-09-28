package io.github.feedbacklib.android.internal.ui.theme

import io.github.feedbacklib.android.ColorTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeColorsTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `light and dark are fixed and system follows the device`() {
        assertFalse(isDark(ColorTheme.LIGHT, systemDark = true))
        assertTrue(isDark(ColorTheme.DARK, systemDark = false))
        assertTrue(isDark(ColorTheme.SYSTEM, systemDark = true))
        assertFalse(isDark(ColorTheme.SYSTEM, systemDark = false))
    }

    @Test
    fun `content on a primary colour is whichever of black and white reads better`() {
        assertEquals(black, contentColorOn(0xFFFFEB3B.toInt())) // yellow
        assertEquals(white, contentColorOn(0xFF1565C0.toInt())) // dark blue
        assertEquals(black, contentColorOn(white))
        assertEquals(white, contentColorOn(black))
    }

    @Test
    fun `relative luminance spans black to white`() {
        assertEquals(0.0, relativeLuminance(black), 1e-9)
        assertEquals(1.0, relativeLuminance(white), 1e-9)
    }
}
