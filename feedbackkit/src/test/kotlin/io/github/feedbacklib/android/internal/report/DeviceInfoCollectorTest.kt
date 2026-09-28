package io.github.feedbacklib.android.internal.report

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DeviceInfoCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun `reports the api level and the current locale tag`() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        val info = DeviceInfoCollector(context).collect()
        assertEquals(35, info.apiLevel)
        assertEquals("ru-RU", info.locale)
    }

    @Test
    @Config(qualifiers = "land")
    fun `reports landscape orientation`() {
        assertEquals("landscape", DeviceInfoCollector(context).collect().orientation)
    }

    @Test
    fun `screen is width x height at density`() {
        val screen = DeviceInfoCollector(context).collect().screen
        assertTrue(screen, Regex("""\d+x\d+@\d+""").matches(screen))
    }

    @Test
    fun `network type is one of the documented values`() {
        val type = DeviceInfoCollector(context).collect().networkType
        assertTrue(type, type in setOf("wifi", "cellular", "ethernet", "vpn", "other", "none", "unknown"))
    }

    @Test
    fun `memory and disk are reported in megabytes or -1`() {
        val info = DeviceInfoCollector(context).collect()
        assertTrue(info.freeMemoryMb >= -1)
        assertTrue(info.freeDiskMb >= -1)
    }
}
