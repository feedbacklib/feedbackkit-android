package io.github.feedbacklib.android.internal.proactive

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.ProactiveSettings
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.ActivityTracker
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProactivePrompterTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000_000L
    private val tracker = ActivityTracker(SdkLogger(LogLevel.NONE), clock = { now })
    private var settings = ProactiveSettings(enabled = true)
    private var accept = true
    private val shown = mutableListOf<ProactiveInfo>()

    /** How each offered event was settled: shown at a wall clock time, or dropped (null). */
    private val resolved = mutableListOf<Pair<ProactiveEvent, Long?>>()
    private var onResolved: (ProactiveEvent, Long?) -> Unit = { event, at -> resolved += event to at }
    private val crash = ProactiveInfo(ProactiveTrigger.CRASH, "2026-09-28T10:00:00Z", "java.lang.IllegalStateException", "boom")
    private val prompter = ProactivePrompter(
        tracker = tracker,
        settings = { settings },
        show = { info -> if (accept) shown += info; accept },
        onResolved = { event, at -> onResolved(event, at) },
        logger = SdkLogger(LogLevel.NONE),
        clock = { now },
    )

    @Before
    fun register() {
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.addListener(prompter)
    }

    @After
    fun unregister() {
        prompter.cancel()
        tracker.removeListener(prompter)
        app.unregisterActivityLifecycleCallbacks(tracker)
    }

    private fun event(lastModalAt: Long? = null) = ProactiveEvent(crash, lastModalAt, detectedAt = 1_000L)

    // A host screen up to onResume, where the delay starts: setup()'s visible() moves Robolectric's clock on by frames.
    private fun screen(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create().start().postCreate(null).resume()

    private fun advance(millis: Long) {
        now += millis
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `the prompt shows the delay after the first host screen, and only once`() {
        prompter.offer(event())
        advance(10_000)
        assertTrue("no host screen yet", shown.isEmpty())
        screen()
        advance(1_999)
        assertTrue(shown.isEmpty())
        advance(1)
        assertEquals(listOf(crash), shown)
        assertEquals(listOf(event() to now), resolved)

        screen()
        prompter.offer(event())
        advance(10_000)
        assertEquals(1, shown.size)
        assertEquals(1, resolved.size)
    }

    @Test
    fun `an event known after the first screen waits the delay from then`() {
        screen()
        advance(5_000)
        prompter.offer(event())
        advance(1_999)
        assertTrue(shown.isEmpty())
        advance(1)
        assertEquals(1, shown.size)
    }

    @Test
    fun `switched off before the delay ends, the event is dropped and resolved`() {
        prompter.offer(event())
        val host = screen()
        advance(1_000)
        settings = settings.copy(enabled = false)
        advance(1_000)
        settings = settings.copy(enabled = true)
        host.pause().resume()
        advance(10_000)
        assertTrue(shown.isEmpty())
        assertEquals("dropped: its pending entry goes", listOf(event() to null), resolved)
    }

    @Test
    fun `a prompt shown within the gap keeps the event quiet and resolves it`() {
        val quiet = event(lastModalAt = now - 60_000)
        prompter.offer(quiet)
        screen()
        advance(10_000)
        assertTrue(shown.isEmpty())
        assertEquals(listOf(quiet to null), resolved)
    }

    @Test
    fun `with no host screen the event stays unresolved, as on a background start`() {
        prompter.offer(event())
        advance(60_000)
        assertTrue(shown.isEmpty())
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a zero gap asks right after the last prompt, and a last prompt in the future does not block`() {
        settings = ProactiveSettings(enabled = true, gapMillis = 0)
        prompter.offer(event(lastModalAt = now + 60_000))
        screen()
        advance(2_000)
        assertEquals(1, shown.size)
    }

    @Test
    fun `the delay the host has set at the moment counts`() {
        settings = settings.copy(delayMillis = 500)
        prompter.offer(event())
        screen()
        advance(500)
        assertEquals(1, shown.size)
    }

    @Test
    fun `with no host screen when the delay ends the next one starts it again`() {
        prompter.offer(event())
        val host = screen()
        advance(1_000)
        host.pause().stop()
        advance(5_000)
        assertTrue(shown.isEmpty())
        host.restart().resume()
        advance(1_999)
        assertTrue(shown.isEmpty())
        advance(1)
        assertEquals(1, shown.size)
    }

    @Test
    fun `an SDK screen in the way defers the prompt to the next host screen`() {
        accept = false
        prompter.offer(event())
        val host = screen()
        advance(2_000)
        assertTrue(shown.isEmpty())
        assertTrue("not shown, not dropped: still pending", resolved.isEmpty())
        accept = true
        host.pause().resume()
        advance(2_000)
        assertEquals(1, shown.size)
        assertEquals(1, resolved.size)
    }

    @Test
    fun `a huge delay waits an hour instead of overflowing`() {
        settings = ProactiveSettings(enabled = true, delayMillis = Long.MAX_VALUE)
        prompter.offer(event())
        screen()
        advance(ProactiveSettings.MAX_DELAY_MILLIS - 1)
        assertTrue(shown.isEmpty())
        advance(1)
        assertEquals(1, shown.size)
    }

    /** A force restart this process found at its start, [now] when the test begins: it is never pending on disk. */
    private val startedAt = now
    private val restart = ProactiveInfo(ProactiveTrigger.FORCE_RESTART, "2026-09-28T10:00:00Z", null, null)
    private fun forceRestart() = ProactiveEvent(restart, null, detectedAt = startedAt)

    @Test
    fun `a force restart whose first host screen comes 9 s after the start is offered, the delay after that screen`() {
        prompter.offer(forceRestart())
        advance(9_000)
        screen()
        advance(1_999)
        assertTrue(shown.isEmpty())
        advance(1)
        assertEquals(listOf(restart), shown)
        assertEquals(listOf(forceRestart() to now), resolved)
    }

    @Test
    fun `a force restart known after an early first screen is offered even past the window`() {
        advance(1_000)
        screen()
        advance(11_000) // a slow detection: the screen, not the offer, counts
        prompter.offer(forceRestart())
        advance(2_000)
        assertEquals(listOf(restart), shown)
    }

    @Test
    fun `a force restart whose first host screen comes 11 s after the start is dropped without a resolution`() {
        prompter.offer(forceRestart())
        advance(11_000)
        val host = screen()
        advance(10_000)
        host.pause().resume()
        advance(10_000)
        assertTrue(shown.isEmpty())
        assertTrue("never pending: nothing to resolve", resolved.isEmpty())
    }

    @Test
    fun `a force restart offered after a late first screen is dropped at once`() {
        advance(11_000)
        screen()
        prompter.offer(forceRestart())
        advance(10_000)
        assertTrue(shown.isEmpty())
        assertTrue(resolved.isEmpty())
        prompter.offer(event())
        advance(10_000)
        assertTrue("one event per process", shown.isEmpty())
    }

    @Test
    fun `a force restart with no host screen is never shown or resolved`() {
        prompter.offer(forceRestart())
        advance(60_000)
        assertTrue(shown.isEmpty())
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a failing record of the prompt changes nothing else, and cancel leaves the event pending`() {
        onResolved = { _, _ -> error("disk gone") }
        prompter.offer(event())
        screen()
        advance(2_000)
        assertEquals(1, shown.size)

        val other = ProactivePrompter(tracker, { settings }, { shown += it; true }, { e, at -> resolved += e to at }, SdkLogger(LogLevel.NONE), clock = { now })
        tracker.addListener(other)
        other.offer(event())
        other.cancel()
        advance(10_000)
        tracker.removeListener(other)
        assertEquals(1, shown.size)
        assertTrue("torn down before its prompt: nothing resolved", resolved.isEmpty())
    }
}
