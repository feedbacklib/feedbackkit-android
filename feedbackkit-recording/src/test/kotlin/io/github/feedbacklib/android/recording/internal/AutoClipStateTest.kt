package io.github.feedbacklib.android.recording.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FeedbackKit asks for the clip of a session it paused behind its report: that must still clip. */
class AutoClipStateTest {

    @Test
    fun `the session machine knows no paused state, so a paused auto session still clips`() {
        // A new state here (a paused one, say) must decide whether FeedbackKit's clip of a paused session still works.
        assertEquals(
            listOf(SessionState.CONSENTING, SessionState.STARTING, SessionState.RECORDING, SessionState.STOPPING, SessionState.DONE),
            SessionState.entries.toList(),
        )
        val machine = SessionMachine().apply {
            consentGranted()
            serviceReady()
            captureStarted()
        }
        // pause() touches only the sink: the machine stays where a clip is made.
        assertEquals(SessionState.RECORDING, machine.state)
        assertTrue(clipsIn(machine.state))
    }

    @Test
    fun `no clip before the recording started or once it is stopping`() {
        val machine = SessionMachine()
        assertFalse(clipsIn(machine.state))
        machine.consentGranted()
        assertFalse(clipsIn(machine.state))
        machine.serviceReady()
        machine.captureStarted()
        machine.stopRequested()
        assertFalse(clipsIn(machine.state))
        assertFalse(clipsIn(SessionState.DONE))
    }
}
