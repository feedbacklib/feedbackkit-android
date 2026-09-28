package io.github.feedbacklib.android.internal.recording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoRecordingPolicyTest {

    private val policy = AutoRecordingPolicy()
    private val ask = listOf(AutoCommand.ASK)
    private val pause = listOf(AutoCommand.PAUSE)
    private val resume = listOf(AutoCommand.RESUME)
    private val stop = listOf(AutoCommand.STOP)

    private fun onScreen() {
        policy.onForeground(true)
        policy.onHost(resumed = true)
    }

    private fun recording() {
        onScreen()
        policy.setEnabled(true)
        policy.onStarted()
        assertEquals(AutoPhase.RECORDING, policy.phase)
    }

    @Test
    fun `off by default, whatever the app does`() {
        assertEquals(AutoPhase.OFF, policy.phase)
        val all = policy.onForeground(true) + policy.onHost(true) + policy.onUiOpen(true) + policy.onUiOpen(false) + policy.onForeground(false)
        assertTrue(all.isEmpty())
    }

    @Test
    fun `switched on while a screen shows, it asks at once`() {
        onScreen()
        assertEquals(ask, policy.setEnabled(true))
        assertEquals(AutoPhase.ASKING, policy.phase)
    }

    @Test
    fun `switched on before any screen, it asks on the first one`() {
        assertTrue(policy.setEnabled(true).isEmpty())
        assertTrue(policy.onForeground(true).isEmpty(), "a started screen is not yet a resumed one")
        assertEquals(ask, policy.onHost(true))
    }

    @Test
    fun `switched on in the background, it asks on the next foreground's first screen`() {
        onScreen()
        policy.onForeground(false)
        assertTrue(policy.setEnabled(true).isEmpty())
        assertTrue(policy.onForeground(true).isEmpty())
        assertEquals(ask, policy.onHost(true))
    }

    @Test
    fun `it never asks over FeedbackKit's own screen and asks once that closes`() {
        onScreen()
        policy.onUiOpen(true)
        assertTrue(policy.setEnabled(true).isEmpty())
        assertEquals(ask, policy.onUiOpen(false))
    }

    @Test
    fun `asked once, more screens ask nothing more`() {
        onScreen()
        policy.setEnabled(true)
        assertTrue(policy.onHost(true).isEmpty())
        assertTrue(policy.onUiOpen(false).isEmpty())
    }

    @Test
    fun `declined, it is not asked again in this process whatever the host toggles`() {
        onScreen()
        policy.setEnabled(true)
        policy.onNotStarted(refused = true)
        assertTrue(policy.refused)
        val later = policy.setEnabled(false) + policy.setEnabled(true) + policy.onForeground(false) + policy.onForeground(true) + policy.onHost(true)
        assertTrue(later.isEmpty())
        assertEquals(AutoPhase.IDLE, policy.phase)
    }

    @Test
    fun `a consent that failed is asked again on the next foreground only`() {
        onScreen()
        policy.setEnabled(true)
        policy.onNotStarted(refused = false)
        assertFalse(policy.refused)
        assertTrue(policy.onHost(true).isEmpty())
        policy.onForeground(false)
        policy.onForeground(true)
        assertEquals(ask, policy.onHost(true))
    }

    @Test
    fun `the background pauses and the foreground resumes`() {
        recording()
        assertEquals(pause, policy.onForeground(false))
        assertEquals(AutoPhase.PAUSED, policy.phase)
        assertEquals(resume, policy.onForeground(true))
        assertEquals(AutoPhase.RECORDING, policy.phase)
    }

    @Test
    fun `FeedbackKit's screen pauses and its close resumes, but not in the background`() {
        recording()
        assertEquals(pause, policy.onUiOpen(true))
        assertTrue(policy.onForeground(false).isEmpty())
        assertTrue(policy.onUiOpen(false).isEmpty())
        assertEquals(resume, policy.onForeground(true))
    }

    @Test
    fun `a consent given behind FeedbackKit's screen starts paused`() {
        onScreen()
        policy.setEnabled(true)
        policy.onUiOpen(true)
        assertEquals(pause, policy.onStarted())
        assertEquals(AutoPhase.PAUSED, policy.phase)
        assertEquals(resume, policy.onUiOpen(false))
    }

    @Test
    fun `a projection gone on return is asked for again on the next screen`() {
        recording()
        policy.onForeground(false)
        assertEquals(resume, policy.onForeground(true))
        assertTrue(policy.onResumeFailed().isEmpty(), "started, not resumed yet: no screen to ask over")
        assertEquals(AutoPhase.IDLE, policy.phase)
        assertEquals(ask, policy.onHost(true))
    }

    @Test
    fun `an ended projection waits for the next foreground`() {
        recording()
        assertTrue(policy.onEnded().isEmpty())
        assertEquals(AutoPhase.IDLE, policy.phase)
        assertTrue((policy.onHost(true) + policy.onUiOpen(true) + policy.onUiOpen(false)).isEmpty())
        policy.onForeground(false)
        policy.onForeground(true)
        assertEquals(ask, policy.onHost(true))
    }

    @Test
    fun `a manual recording before the first consent also puts the question off to the next foreground`() {
        onScreen()
        policy.onUiOpen(true)
        policy.setEnabled(true) // waits for FeedbackKit's screen to close
        assertTrue(policy.onEnded().isEmpty())
        assertTrue(policy.onUiOpen(false).isEmpty(), "not right after the report that recorded closes")
        policy.onForeground(false)
        policy.onForeground(true)
        assertEquals(ask, policy.onHost(true))
    }

    @Test
    fun `switching off stops a session in any phase and nothing otherwise`() {
        onScreen()
        policy.setEnabled(true)
        assertEquals(stop, policy.setEnabled(false))
        assertEquals(AutoPhase.OFF, policy.phase)

        val idle = AutoRecordingPolicy().apply { setEnabled(true) }
        assertTrue(idle.setEnabled(false).isEmpty())

        val paused = AutoRecordingPolicy().apply {
            onForeground(true)
            onHost(true)
            setEnabled(true)
            onStarted()
            onForeground(false)
        }
        assertEquals(AutoPhase.PAUSED, paused.phase)
        assertEquals(stop, paused.setEnabled(false))
    }

    @Test
    fun `a screen gone by the time of asking is retried on the next one`() {
        onScreen()
        policy.setEnabled(true)
        assertTrue(policy.onAskFailed().isEmpty())
        assertEquals(AutoPhase.IDLE, policy.phase)
        assertEquals(ask, policy.onHost(true))
    }

    @Test
    fun `answers that come after switching off change nothing`() {
        onScreen()
        policy.setEnabled(true)
        policy.setEnabled(false)
        val late = policy.onStarted() + policy.onNotStarted(refused = true) + policy.onEnded() + policy.onResumeFailed() + policy.onAskFailed()
        assertTrue(late.isEmpty())
        assertFalse(policy.refused)
        assertEquals(AutoPhase.OFF, policy.phase)
    }
}
