package io.github.feedbacklib.android.recording.internal

import io.github.feedbacklib.android.recording.internal.SessionCommand.StopCapture
import io.github.feedbacklib.android.spi.RecordingStopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The session's wall-clock limit driven by the real [SessionMachine], as ProjectionSession wires it. */
class RecordingLimitTest {

    private class FakeRunner : DelayedRunner {
        val pending = mutableListOf<Pair<Runnable, Long>>()
        var posts = 0

        override fun postDelayed(action: Runnable, delayMillis: Long) {
            posts++
            pending += action to delayMillis
        }

        override fun remove(action: Runnable) {
            pending.removeAll { it.first === action }
        }

        /** The delay passes: what is still pending runs. */
        fun elapse() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it.first.run() }
        }
    }

    private val runner = FakeRunner()
    private val machine = SessionMachine()
    private val executed = mutableListOf<SessionCommand>()
    private lateinit var limit: RecordingLimit

    private fun session(limitMillis: Long = 60_000) {
        limit = RecordingLimit(limitMillis, runner) { perform(machine.limitReached()) }
    }

    /** What ProjectionSession.execute does with each command, before carrying it out. */
    private fun perform(commands: List<SessionCommand>) {
        commands.forEach {
            limit.follow(it)
            executed += it
        }
    }

    private fun recording() {
        perform(machine.consentGranted())
        perform(machine.serviceReady())
        perform(machine.captureStarted())
    }

    @Test
    fun `the limit is armed when the recording starts, not before`() {
        session()
        perform(machine.consentGranted())
        perform(machine.serviceReady())
        assertTrue(runner.pending.isEmpty(), "no timer while starting")
        perform(machine.captureStarted())
        assertEquals(listOf(60_000L), runner.pending.map { it.second })
    }

    @Test
    fun `the limit stops the recording by wall clock, whatever the frames do`() {
        session(3_000)
        recording()
        runner.elapse()
        assertEquals(listOf(StopCapture), executed.filter { it == StopCapture })
        assertEquals(SessionState.STOPPING, machine.state)
        perform(machine.captureStopped(usable = true))
        assertEquals(SessionCommand.NotifyFinished(RecordingStopReason.LIMIT), executed.last())
    }

    @Test
    fun `the recorder's own limit first cancels the timer, and a late timer changes nothing`() {
        session(3_000)
        recording()
        val timer = runner.pending.single().first
        perform(machine.limitReached()) // MediaRecorder's MAX_DURATION_REACHED
        assertTrue(runner.pending.isEmpty(), "the stop cancelled the timer")
        timer.run() // one already dequeued when the stop came: it must not stop twice
        assertEquals(1, executed.count { it == StopCapture })
    }

    @Test
    fun `every stop path cancels the timer`() {
        val stops: List<SessionMachine.() -> List<SessionCommand>> = listOf(
            { stopRequested() },
            { projectionStopped() },
            { captureFailed() },
        )
        for (stop in stops) {
            val machine = SessionMachine()
            val runner = FakeRunner()
            lateinit var limit: RecordingLimit
            fun perform(commands: List<SessionCommand>) = commands.forEach { limit.follow(it) }
            limit = RecordingLimit(60_000, runner) { perform(machine.limitReached()) }
            perform(machine.consentGranted())
            perform(machine.serviceReady())
            perform(machine.captureStarted())
            assertEquals(1, runner.pending.size)
            perform(machine.stop())
            assertTrue(runner.pending.isEmpty())
        }
    }

    @Test
    fun `an aborted start and a stop before recording leave no timer`() {
        session()
        perform(machine.consentGranted())
        perform(machine.serviceReady())
        perform(machine.captureFailed()) // abortStart
        assertEquals(0, runner.posts)

        val other = SessionMachine()
        val otherRunner = FakeRunner()
        val otherLimit = RecordingLimit(60_000, otherRunner) { }
        other.stopRequested()
        (other.consentGranted() + other.serviceReady() + other.captureStarted()).forEach(otherLimit::follow)
        assertEquals(0, otherRunner.posts, "a cancelled start never records, so never arms")
    }

    @Test
    fun `no limit means no timer, and a second start arms once`() {
        session(0)
        recording()
        assertEquals(0, runner.posts)

        val runner2 = FakeRunner()
        val twice = RecordingLimit(1_000, runner2) { }
        twice.follow(SessionCommand.NotifyStarted)
        twice.follow(SessionCommand.NotifyStarted)
        assertEquals(1, runner2.posts)
        twice.cancel()
        twice.cancel()
        assertTrue(runner2.pending.isEmpty())
    }

    @Test
    fun `the recorder's own limit comes after the session's, so it never cuts the timer's stop short`() {
        assertTrue(recorderMaxDurationMillis(60_000) > 60_000)
        assertEquals(60_000 + RECORDER_LIMIT_MARGIN_MILLIS, recorderMaxDurationMillis(60_000))
        assertTrue(recorderMaxDurationMillis(3_000) > 3_000)
        assertEquals(0L, recorderMaxDurationMillis(0), "no limit stays no limit")
    }
}
