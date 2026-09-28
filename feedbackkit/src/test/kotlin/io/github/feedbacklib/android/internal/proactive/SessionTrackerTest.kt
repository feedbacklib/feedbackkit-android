package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.LogSink
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SessionTrackerTest {

    private var now = 10_000L
    private val written = mutableListOf<SessionState>()
    private var failWrites = false
    private val lines = mutableListOf<String>()
    private val tracker = SessionTracker(
        startedAt = 9_000L,
        write = { state ->
            if (failWrites) throw IOException("disk full")
            written += state
        },
        io = { it.run() },
        logger = SdkLogger(LogLevel.VERBOSE, LogSink { level, _, message, _ -> lines += "$level $message" }),
        clock = { now },
    )

    @After
    fun stop() = tracker.stop()

    private fun advance(millis: Long) {
        now += millis
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
    }

    /** One heartbeat interval at a time, so each beat reads its own wall clock. */
    private fun beats(count: Int) = repeat(count) { advance(SessionTracker.HEARTBEAT_MILLIS) }

    private fun state(foregroundAt: Long, wentBackground: Boolean, backgroundAt: Long, aliveAt: Long) =
        SessionState("s-1", 9_000, foregroundAt, wentBackground, lastBackgroundAt = backgroundAt, lastAliveAt = aliveAt)

    @Test
    fun `nothing is written before the previous run was read`() {
        tracker.onForegroundChanged(true)
        advance(10_000)
        tracker.onForegroundChanged(false)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `in the foreground it writes at once and then every two seconds`() {
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        assertEquals(listOf(state(10_000, false, 0, 10_000)), written)
        advance(1_999)
        assertEquals(1, written.size)
        advance(1)
        assertEquals(state(12_000, false, 0, 12_000), written.last())
        advance(4_000)
        assertEquals(4, written.size)
    }

    @Test
    fun `leaving the foreground is written at once, the heartbeat goes on for 10 s, then stops`() {
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        advance(500)
        tracker.onForegroundChanged(false)
        assertEquals(state(10_500, true, 10_500, 10_500), written.last())
        beats(5)
        assertEquals(listOf(12_500L, 14_500L, 16_500L, 18_500L, 20_500L), written.drop(2).map { it.lastAliveAt })
        assertTrue("the tail keeps the time of leaving", written.drop(2).all { it == state(10_500, true, 10_500, it.lastAliveAt) })
        val count = written.size
        advance(30_000)
        assertEquals(count, written.size)
    }

    @Test
    fun `coming back to the foreground beats again`() {
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        tracker.onForegroundChanged(false)
        beats(15)
        tracker.onForegroundChanged(true)
        assertEquals(state(40_000, false, 10_000, 40_000), written.last())
        advance(2_000)
        assertEquals(state(42_000, false, 10_000, 42_000), written.last())
    }

    @Test
    fun `leaving the foreground again starts a new 10 s tail`() {
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        tracker.onForegroundChanged(false)
        beats(4)
        tracker.onForegroundChanged(true)
        tracker.onForegroundChanged(false)
        beats(5)
        assertEquals(state(18_000, true, 18_000, 28_000), written.last())
        val count = written.size
        advance(30_000)
        assertEquals(count, written.size)
    }

    @Test
    fun `a session that starts in the background counts as background until it comes forward`() {
        tracker.begin("s-1")
        assertEquals(listOf(SessionState("s-1", 9_000, 0, wentBackground = true)), written)
        advance(10_000)
        assertEquals(1, written.size)
        tracker.onForegroundChanged(true)
        assertEquals(state(20_000, false, 0, 20_000), written.last())
    }

    @Test
    fun `a failing write is logged and the heartbeat goes on`() {
        failWrites = true
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        advance(2_000)
        assertEquals(2, lines.count { it.startsWith("WARNING") && it.contains("session state") })
        failWrites = false
        advance(2_000)
        assertEquals(1, written.size)
    }

    @Test
    fun `a second begin changes nothing and stop ends the heartbeat`() {
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        tracker.begin("s-2")
        advance(2_000)
        assertEquals(listOf("s-1", "s-1"), written.map { it.sessionId })
        tracker.stop()
        advance(10_000)
        tracker.onForegroundChanged(false)
        assertEquals(2, written.size)
    }

    @Test
    fun `stop during the background tail ends it`() {
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        tracker.onForegroundChanged(false)
        beats(1)
        tracker.stop()
        beats(4)
        assertEquals(3, written.size)
    }

    @Test
    fun `stop from another thread ends the heartbeat`() {
        tracker.onForegroundChanged(true)
        tracker.begin("s-1")
        Thread { tracker.stop() }.apply { start() }.join()
        advance(10_000)
        assertEquals(1, written.size)
    }
}
