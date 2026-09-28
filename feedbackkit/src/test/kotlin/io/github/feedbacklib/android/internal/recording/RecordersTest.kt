package io.github.feedbacklib.android.internal.recording

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.LogSink
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.spi.AutoRecordingListener
import io.github.feedbacklib.android.spi.AutoRecordingSession
import io.github.feedbacklib.android.spi.RecorderLog
import io.github.feedbacklib.android.spi.RecordingListener
import io.github.feedbacklib.android.spi.RecordingSession
import io.github.feedbacklib.android.spi.ScreenRecorder
import io.github.feedbacklib.android.spi.ScreenRecorderProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.ServiceConfigurationError

@RunWith(RobolectricTestRunner::class)
class RecordersTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val lines = mutableListOf<String>()
    private val logger = SdkLogger(LogLevel.VERBOSE, LogSink { level, _, message, _ -> lines += "$level $message" })

    private class FakeRecorder : ScreenRecorder {
        override fun record(host: Activity, output: File, maxDurationMillis: Long, listener: RecordingListener): RecordingSession = RecordingSession {}
        override fun startAuto(host: Activity, directory: File, listener: AutoRecordingListener): AutoRecordingSession = error("not used")
    }

    private class FakeProvider(
        override val spiVersion: Int = ScreenRecorderProvider.SPI_VERSION,
        private val failure: Throwable? = null,
    ) : ScreenRecorderProvider {
        val recorder = FakeRecorder()
        var log: RecorderLog? = null
        var created = 0

        override fun create(context: Context, log: RecorderLog): ScreenRecorder {
            created++
            failure?.let { throw it }
            this.log = log
            return recorder
        }
    }

    /** A ServiceLoader-like iterator: each entry is loaded on next(), and a failing entry throws there. */
    private fun recorders(vararg entries: () -> ScreenRecorderProvider) = Recorders(app, logger) {
        val source = entries.iterator()
        object : Iterator<ScreenRecorderProvider> {
            override fun hasNext() = source.hasNext()
            override fun next() = source.next().invoke()
        }
    }

    @Test
    fun `without the recording artifact nothing is found and recording stays unavailable`() {
        // The real ServiceLoader: feedbackkit's own test classpath has no provider.
        val recorders = Recorders(app, logger)
        assertFalse(recorders.discovered)
        recorders.discover()
        assertTrue(recorders.discovered)
        assertNull(recorders.recorder)
        assertFalse(recorders.available.value)
    }

    @Test
    fun `the first usable provider wins and its log reaches the sdk logger`() {
        val first = FakeProvider()
        val second = FakeProvider()
        val recorders = recorders({ first }, { second })
        recorders.discover()
        assertSame(first.recorder, recorders.recorder)
        assertTrue(recorders.available.value)
        assertEquals(0, second.created)

        first.log!!.log(LogLevel.WARNING, "from the recorder", null)
        assertTrue(lines.contains("WARNING from the recorder"))
    }

    @Test
    fun `a provider built for another contract version is skipped with a warning`() {
        val stale = FakeProvider(spiVersion = ScreenRecorderProvider.SPI_VERSION + 1)
        val recorders = recorders({ stale })
        recorders.discover()
        assertNull(recorders.recorder)
        assertEquals(0, stale.created)
        assertTrue(lines.any { it.startsWith("WARNING") && it.contains("same version") })
    }

    @Test
    fun `a provider that throws, or a broken services entry, is skipped and the next one is used`() {
        val good = FakeProvider()
        val recorders = recorders(
            { FakeProvider(failure = IllegalStateException("no display")) },
            { throw ServiceConfigurationError("bad entry") },
            { good },
        )
        recorders.discover()
        assertSame(good.recorder, recorders.recorder)
        assertEquals(2, lines.count { it.startsWith("WARNING") })
    }

    @Test
    fun `a class missing from a mismatched artifact is skipped and the next entry is used`() {
        val good = FakeProvider()
        val recorders = recorders(
            { throw NoClassDefFoundError("io/github/feedbacklib/android/spi/Gone") },
            { FakeProvider(failure = NoClassDefFoundError("io/github/feedbacklib/android/spi/Gone")) },
            { good },
        )
        recorders.discover()
        assertSame(good.recorder, recorders.recorder)
        assertEquals(2, lines.count { it.startsWith("WARNING") })
    }

    @Test
    fun `a provider source that fails to link turns recording off with a warning and never throws`() {
        val recorders = Recorders(app, logger) { throw NoClassDefFoundError("java/util/ServiceLoader") }
        recorders.discover()
        assertTrue(recorders.discovered)
        assertNull(recorders.recorder)
        assertFalse(recorders.available.value)
        assertEquals(1, lines.count { it.startsWith("WARNING") })
    }

    @Test
    fun `a provider constructor that throws inside the lookup, as after R8, turns recording off with a warning`() {
        // R8 rewrites ServiceLoader.load(...).iterator() into direct construction: the constructor runs in providers().
        val recorders = Recorders(app, logger) { throw IllegalStateException("provider constructor failed") }
        recorders.discover()
        assertTrue(recorders.discovered)
        assertNull(recorders.recorder)
        assertFalse(recorders.available.value)
        assertEquals(1, lines.count { it.startsWith("WARNING") })
    }

    @Test
    fun `an iterator whose hasNext keeps failing ends the lookup instead of spinning`() {
        val recorders = Recorders(app, logger) {
            object : Iterator<ScreenRecorderProvider> {
                override fun hasNext(): Boolean = throw NoClassDefFoundError("io/github/feedbacklib/android/spi/Gone")
                override fun next(): ScreenRecorderProvider = error("never reached")
            }
        }
        recorders.discover()
        assertTrue(recorders.discovered)
        assertNull(recorders.recorder)
        // MAX_ENTRIES: one warning per attempt, then the lookup gives up.
        assertEquals(16, lines.count { it.startsWith("WARNING") })
    }

    @Test
    fun `discovery runs once`() {
        val provider = FakeProvider()
        val recorders = recorders({ provider })
        recorders.discover()
        recorders.discover()
        assertEquals(1, provider.created)
    }
}
