package io.github.feedbacklib.android.recording.internal

import io.github.feedbacklib.android.spi.ScreenRecorderProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class ServiceRegistrationTest {

    @Test
    fun `feedbackkit finds this module's recorder through ServiceLoader, built for the current contract`() {
        val providers = ServiceLoader.load(ScreenRecorderProvider::class.java).toList()
        assertEquals(listOf(RecordingProvider::class.java), providers.map { it.javaClass })
        assertEquals(ScreenRecorderProvider.SPI_VERSION, providers.single().spiVersion)
    }
}
