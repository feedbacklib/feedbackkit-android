package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProactiveDetectorTest {

    private val start = 1_000_000L
    private val previousStart = start - 60_000
    private val restart = Detection(ProactiveTrigger.FORCE_RESTART, null, null)
    private val marker = CrashMarker(start - 5_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom")
    private val below30 = ExitHistory.UNSUPPORTED
    private val noExits = ExitHistory(supported = true, records = emptyList())

    /** In the foreground to the end: the last heartbeat at [aliveAt]. */
    private fun foreground(aliveAt: Long = start - 3_000) =
        SessionState("previous", previousStart, aliveAt, wentBackground = false, lastBackgroundAt = 0, lastAliveAt = aliveAt)

    /** Left the foreground at [leftAt]; the heartbeat's tail went on to [aliveAt]. */
    private fun background(leftAt: Long, aliveAt: Long = leftAt) =
        SessionState("previous", previousStart, leftAt, wentBackground = true, lastBackgroundAt = leftAt, lastAliveAt = aliveAt)

    /** Went to the background long before this start, and its tail ended 10 s later. */
    private val longGone = background(leftAt = previousStart + 1_000, aliveAt = previousStart + 11_000)

    private fun exit(reason: ExitReason, at: Long = start - 2_000, description: String? = null) =
        ExitHistory(supported = true, records = listOf(ExitRecord(reason, at, description)))

    private fun detect(previous: SessionState? = foreground(), crash: CrashMarker? = null, exits: ExitHistory = below30, lastProcessedExitAt: Long? = null) =
        ProactiveDetector.detect(previous, crash, exits, lastProcessedExitAt, start)

    @Test
    fun `a first run, with nothing left behind, detects nothing`() {
        assertNull(detect(previous = null))
        assertNull(detect(previous = null, exits = noExits))
    }

    @Test
    fun `a crash marker is a crash with its exception and stack trace, whatever else is true`() {
        val expected = Detection(ProactiveTrigger.CRASH, marker.exception, marker.stacktrace)
        assertEquals(expected, detect(crash = marker))
        assertEquals(expected, detect(previous = null, crash = marker))
        assertEquals(expected, detect(previous = longGone, crash = marker))
        assertEquals(expected, detect(crash = marker, exits = exit(ExitReason.ANR)))
    }

    @Test
    fun `on API 30 and later an ANR, a native or a Java crash of the previous run is a crash`() {
        assertEquals(Detection(ProactiveTrigger.CRASH, "ANR", "Input dispatching timed out"), detect(exits = exit(ExitReason.ANR, description = "Input dispatching timed out")))
        assertEquals(Detection(ProactiveTrigger.CRASH, "Native crash", null), detect(previous = longGone, exits = exit(ExitReason.CRASH_NATIVE)))
        assertEquals(Detection(ProactiveTrigger.CRASH, "Crash", null), detect(exits = exit(ExitReason.CRASH)))
    }

    @Test
    fun `an exit description is cut like a marker, to 50 lines of at most 500 characters`() {
        val description = (1..200).joinToString("\n") { "line $it " + "x".repeat(1_000) }
        val trace = detect(exits = exit(ExitReason.ANR, description = description))?.stacktrace!!
        val lines = trace.lines()
        assertEquals(CrashHandler.MAX_STACK_LINES, lines.size)
        assertTrue(lines.all { it.length == CrashHandler.MAX_LINE_LENGTH })
        assertTrue(lines.first().startsWith("line 1 "), lines.first())
        assertTrue(lines.last().startsWith("line 50 "), lines.last())
    }

    @Test
    fun `an exit not later than the previous run's start belongs to an earlier run`() {
        assertNull(detect(previous = longGone, exits = exit(ExitReason.ANR, at = previousStart - 1)))
        assertNull(detect(previous = longGone, exits = exit(ExitReason.ANR, at = previousStart)))
        assertNull(detect(previous = null, exits = exit(ExitReason.CRASH)), "no previous run and no exit processed before: nothing bounds it")
    }

    @Test
    fun `exits already looked at are not detected again, and later ones are`() {
        val anr = exit(ExitReason.ANR, at = start - 2_000)
        assertNull(detect(previous = longGone, exits = anr, lastProcessedExitAt = start - 2_000))
        assertEquals(ProactiveTrigger.CRASH, detect(previous = longGone, exits = anr, lastProcessedExitAt = start - 2_001)?.trigger)
        assertEquals(ProactiveTrigger.CRASH, detect(previous = null, exits = anr, lastProcessedExitAt = start - 10_000)?.trigger, "the bound needs no previous session")
    }

    @Test
    fun `the processed bound wins over the previous run's start, unless it is in the future`() {
        val beforePreviousStart = exit(ExitReason.CRASH, at = previousStart - 1_000)
        assertEquals(ProactiveTrigger.CRASH, detect(previous = longGone, exits = beforePreviousStart, lastProcessedExitAt = previousStart - 5_000)?.trigger)
        assertNull(detect(previous = longGone, exits = beforePreviousStart, lastProcessedExitAt = start + 1), "a bound in the future falls back to the previous start")
    }

    @Test
    fun `a crash among the new exits counts even when a later exit is no crash`() {
        val history = ExitHistory(supported = true, records = listOf(ExitRecord(ExitReason.UNLISTED, start - 1_000, null), ExitRecord(ExitReason.CRASH_NATIVE, start - 30_000, null)))
        assertEquals(Detection(ProactiveTrigger.CRASH, "Native crash", null), detect(previous = longGone, exits = history))
    }

    @Test
    fun `below API 30 a run that died in the foreground and restarted within 5 s plus a heartbeat is a force restart`() {
        assertEquals(restart, detect(previous = foreground(aliveAt = start - 3_000)))
        assertEquals(restart, detect(previous = foreground(aliveAt = start - 7_000)))
        assertEquals(restart, detect(previous = foreground(aliveAt = start)))
        assertNull(detect(previous = foreground(aliveAt = start - 7_001)))
    }

    @Test
    fun `below API 30 a run that died within 10 s after leaving the foreground is a force restart`() {
        // A swipe from Recents: onStop first, then the process dies during the heartbeat's tail.
        assertEquals(restart, detect(previous = background(leftAt = start - 6_000, aliveAt = start - 2_000)))
        assertEquals(restart, detect(previous = background(leftAt = start - 11_999, aliveAt = start - 2_000)))
        assertNull(detect(previous = background(leftAt = start - 12_000, aliveAt = start - 2_000)), "a beat at the tail's end: it outlived the window")
        assertNull(detect(previous = background(leftAt = start - 12_001, aliveAt = start - 2_000)), "alive for more than 10 s after leaving")
        assertNull(detect(previous = longGone), "the tail ended long before this start")
    }

    @Test
    fun `a run never in the foreground is no force restart`() {
        assertNull(detect(previous = SessionState("previous", previousStart, 0, wentBackground = true)))
        assertNull(detect(previous = SessionState("previous", previousStart, 0, wentBackground = false)))
        assertNull(detect(previous = SessionState("previous", previousStart, 0, wentBackground = true), exits = exit(ExitReason.USER_REQUESTED)))
    }

    @Test
    fun `a session file of an older version, without the new fields, still reads`() {
        // There lastForegroundAt is both the last heartbeat and, once in the background, the moment of leaving.
        assertEquals(restart, detect(previous = SessionState("previous", previousStart, start - 3_000, wentBackground = false)))
        assertEquals(restart, detect(previous = SessionState("previous", previousStart, start - 3_000, wentBackground = true)))
        assertNull(detect(previous = SessionState("previous", previousStart, start - 30_000, wentBackground = true)))
    }

    @Test
    fun `a death later than this start, the clock moved back, is no force restart`() {
        assertNull(detect(previous = foreground(aliveAt = start + 1)))
        assertNull(detect(exits = exit(ExitReason.USER_REQUESTED, at = start + 1)))
    }

    @Test
    fun `on API 30 and later a force restart needs an exit a user kill gives, at most 5 s before this start`() {
        listOf(ExitReason.USER_REQUESTED, ExitReason.OTHER, ExitReason.SIGNALED).forEach { reason ->
            assertEquals(restart, detect(exits = exit(reason)), reason.name)
        }
        assertEquals(restart, detect(exits = exit(ExitReason.USER_REQUESTED, at = start - 5_000)))
        assertNull(detect(exits = exit(ExitReason.USER_REQUESTED, at = start - 5_001)), "an exit record gets no heartbeat allowance")
        assertNull(detect(exits = exit(ExitReason.UNLISTED)))
        assertNull(detect(exits = noExits), "no exit record")
        assertNull(detect(exits = exit(ExitReason.USER_REQUESTED, at = previousStart - 1)), "the exit of an earlier run")
        assertNull(detect(exits = exit(ExitReason.USER_REQUESTED), lastProcessedExitAt = start - 2_000), "an exit already processed")
    }

    @Test
    fun `on API 30 and later the exit time says whether the run died within 10 s after leaving the foreground`() {
        val left = start - 12_000
        assertEquals(restart, detect(previous = background(leftAt = left), exits = exit(ExitReason.USER_REQUESTED, at = left + 10_000)))
        assertNull(detect(previous = background(leftAt = left), exits = exit(ExitReason.USER_REQUESTED, at = left + 10_001)))
        assertNull(detect(previous = background(leftAt = start - 3_000), exits = exit(ExitReason.USER_REQUESTED, at = start - 4_000)), "an exit before leaving is not this session's")
    }

    @Test
    fun `on API 30 and later a force restart looks at the newest new exit only`() {
        val history = ExitHistory(supported = true, records = listOf(ExitRecord(ExitReason.USER_REQUESTED, start - 40_000, null), ExitRecord(ExitReason.UNLISTED, start - 2_000, null)))
        assertNull(detect(exits = history))
    }

    @Test
    fun `a crash wins over the signs of a force restart`() {
        assertEquals(ProactiveTrigger.CRASH, detect(exits = exit(ExitReason.CRASH))?.trigger)
        assertEquals(ProactiveTrigger.CRASH, detect(crash = marker, exits = exit(ExitReason.USER_REQUESTED))?.trigger)
    }

    @Test
    fun `the processed bound moves to the newest exit and never back`() {
        val history = ExitHistory(supported = true, records = listOf(ExitRecord(ExitReason.OTHER, start - 2_000, null), ExitRecord(ExitReason.CRASH, start - 9_000, null)))
        assertEquals(start - 2_000, ProactiveDetector.processedUpTo(null, history, start))
        assertEquals(start - 2_000, ProactiveDetector.processedUpTo(start - 50_000, history, start))
        assertEquals(start - 1_000, ProactiveDetector.processedUpTo(start - 1_000, history, start))
        assertEquals(start - 1_000, ProactiveDetector.processedUpTo(start - 1_000, below30, start))
        assertNull(ProactiveDetector.processedUpTo(null, below30, start))
        assertEquals(start - 2_000, ProactiveDetector.processedUpTo(start + 60_000, history, start), "a bound in the future is dropped")
    }

    @Test
    fun `a new event replaces the waiting one, and one waiting longer than 72 h is dropped`() {
        val ttl = ProactiveDetector.PENDING_TTL_MILLIS
        val waiting = PendingEvent(start - ttl, ProactiveTrigger.FORCE_RESTART)
        val detected = PendingEvent(start, ProactiveTrigger.CRASH, marker.exception, marker.stacktrace)
        assertEquals(detected, ProactiveDetector.nextPending(waiting, detected, start))
        assertEquals(waiting, ProactiveDetector.nextPending(waiting, null, start), "exactly 72 h still waits")
        assertNull(ProactiveDetector.nextPending(waiting.copy(detectedAt = start - ttl - 1), null, start))
        assertNull(ProactiveDetector.nextPending(null, null, start))
        val future = waiting.copy(detectedAt = start + 60_000)
        assertEquals(future, ProactiveDetector.nextPending(future, null, start), "the clock moved back: it still waits")
    }

    @Test
    fun `the gap is passed only when strictly longer, and neither no prompt yet nor one in the future blocks`() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(ProactiveDetector.gapPassed(null, 10, day))
        assertFalse(ProactiveDetector.gapPassed(lastModalAt = 0, now = 1_000, gapMillis = 1_000))
        assertTrue(ProactiveDetector.gapPassed(lastModalAt = 0, now = 1_001, gapMillis = 1_000))
        assertTrue(ProactiveDetector.gapPassed(lastModalAt = 5_000, now = 1_000, gapMillis = day), "the clock moved back")
        assertTrue(ProactiveDetector.gapPassed(lastModalAt = 0, now = 1, gapMillis = 0), "a zero gap")
        assertFalse(ProactiveDetector.gapPassed(lastModalAt = 0, now = day, gapMillis = Long.MAX_VALUE))
    }
}
