package io.github.feedbacklib.android.internal.invoke

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenshotHeuristicsTest {

    private val now = 1_700_000_000_000L
    private val justNow = now / 1_000 - 2

    @Test
    fun `a fresh file in a Screenshots folder is a screenshot`() {
        assertTrue(ScreenshotHeuristics.isScreenshot("IMG_1.png", "Pictures/Screenshots/", justNow, now))
    }

    @Test
    fun `a fresh file named like a screenshot is a screenshot`() {
        assertTrue(ScreenshotHeuristics.isScreenshot("Screenshot_20260926.png", "DCIM/", justNow, now))
    }

    @Test
    fun `an ordinary photo is not a screenshot`() {
        assertFalse(ScreenshotHeuristics.isScreenshot("IMG_2.jpg", "DCIM/Camera/", justNow, now))
    }

    @Test
    fun `an old screenshot re-indexed now is ignored`() {
        assertFalse(ScreenshotHeuristics.isScreenshot("Screenshot_old.png", "Pictures/Screenshots/", now / 1_000 - 60, now))
    }
}
