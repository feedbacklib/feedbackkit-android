package io.github.feedbacklib.android

import io.github.feedbacklib.android.internal.core.ProactiveSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class ProactiveReportingConfigsTest {

    @Test
    fun `off by default, 24 hours between prompts and 2 seconds of delay`() {
        val configs = ProactiveReportingConfigs.Builder().build()
        assertFalse(configs.isEnabled)
        assertEquals(Duration.ofHours(24), configs.gapBetweenModals)
        assertEquals(Duration.ofSeconds(2), configs.modalDelayAfterDetection)
    }

    @Test
    fun `the TimeUnit overloads set the same durations`() {
        val configs = ProactiveReportingConfigs.Builder()
            .isEnabled(true)
            .setGapBetweenModals(3, TimeUnit.HOURS)
            .setModalDelayAfterDetection(500, TimeUnit.MILLISECONDS)
            .build()
        assertTrue(configs.isEnabled)
        assertEquals(Duration.ofHours(3), configs.gapBetweenModals)
        assertEquals(Duration.ofMillis(500), configs.modalDelayAfterDetection)
    }

    @Test
    fun `a negative duration is ignored and zero is taken`() {
        val configs = ProactiveReportingConfigs.Builder()
            .setGapBetweenModals(Duration.ofMinutes(10))
            .setGapBetweenModals(Duration.ofMinutes(-1))
            .setModalDelayAfterDetection(-5, TimeUnit.SECONDS)
            .build()
        assertEquals(Duration.ofMinutes(10), configs.gapBetweenModals)
        assertEquals(Duration.ofSeconds(2), configs.modalDelayAfterDetection)
        assertEquals(Duration.ZERO, ProactiveReportingConfigs.Builder().setGapBetweenModals(Duration.ZERO).build().gapBetweenModals)
    }

    @Test
    fun `the runtime settings are the same values in milliseconds`() {
        val configs = ProactiveReportingConfigs.Builder().isEnabled(true).setGapBetweenModals(Duration.ofMinutes(1)).setModalDelayAfterDetection(Duration.ofSeconds(3)).build()
        assertEquals(ProactiveSettings(enabled = true, gapMillis = 60_000, delayMillis = 3_000), ProactiveSettings.from(configs))
        assertEquals(ProactiveSettings(), ProactiveSettings.from(ProactiveReportingConfigs.Builder().build()))
    }

    @Test
    fun `a huge gap saturates and a huge delay is capped at an hour`() {
        val configs = ProactiveReportingConfigs.Builder()
            .setGapBetweenModals(Duration.ofSeconds(Long.MAX_VALUE))
            .setModalDelayAfterDetection(Duration.ofDays(400))
            .build()
        val settings = ProactiveSettings.from(configs)
        assertEquals(Long.MAX_VALUE, settings.gapMillis)
        assertEquals(ProactiveSettings.MAX_DELAY_MILLIS, settings.delayMillis)
    }
}
