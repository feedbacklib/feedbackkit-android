package io.github.feedbacklib.android.recording.internal

import io.github.feedbacklib.android.recording.internal.SessionCommand.NotifyFinished
import io.github.feedbacklib.android.recording.internal.SessionCommand.NotifyNotStarted
import io.github.feedbacklib.android.recording.internal.SessionCommand.NotifyStarted
import io.github.feedbacklib.android.recording.internal.SessionCommand.ReleaseService
import io.github.feedbacklib.android.recording.internal.SessionCommand.StartCapture
import io.github.feedbacklib.android.recording.internal.SessionCommand.StartForegroundService
import io.github.feedbacklib.android.recording.internal.SessionCommand.StopCapture
import io.github.feedbacklib.android.spi.RecordingStopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionMachineTest {

    private val machine = SessionMachine()

    private fun recording(): SessionMachine = machine.apply {
        consentGranted()
        serviceReady()
        captureStarted()
    }

    @Test
    fun `consent, the foreground service, the projection, then a stop that finalises the file`() {
        assertEquals(SessionState.CONSENTING, machine.state)
        assertEquals(listOf(StartForegroundService), machine.consentGranted())
        assertEquals(listOf(StartCapture), machine.serviceReady())
        assertEquals(listOf(NotifyStarted), machine.captureStarted())
        assertEquals(SessionState.RECORDING, machine.state)
        assertEquals(listOf(StopCapture), machine.stopRequested())
        assertEquals(listOf(ReleaseService, NotifyFinished(RecordingStopReason.REQUESTED)), machine.captureStopped(usable = true))
        assertEquals(SessionState.DONE, machine.state)
    }

    @Test
    fun `the projection is asked for only once the service runs in the foreground`() {
        val beforeService = machine.consentGranted() + machine.captureStarted() + machine.limitReached() + machine.captureStopped(true)
        assertFalse(StartCapture in beforeService)
        assertEquals(listOf(StartCapture), machine.serviceReady())
        assertTrue(machine.serviceReady().isEmpty(), "a second ready asks for no second projection")
    }

    @Test
    fun `a declined or unavailable consent starts nothing`() {
        assertEquals(listOf(NotifyNotStarted(refused = true)), machine.consentRefused())
        assertEquals(SessionState.DONE, machine.state)

        val other = SessionMachine()
        assertEquals(listOf(NotifyNotStarted(refused = false)), other.consentUnavailable())
    }

    @Test
    fun `a stop while the user decides wins over a later consent`() {
        assertTrue(machine.stopRequested().isEmpty())
        assertEquals(listOf(NotifyNotStarted(refused = false)), machine.consentGranted())
        assertEquals(SessionState.DONE, machine.state)
    }

    @Test
    fun `a stop while the service starts releases it without a projection`() {
        machine.consentGranted()
        assertTrue(machine.stopRequested().isEmpty())
        assertEquals(listOf(ReleaseService, NotifyNotStarted(refused = false)), machine.serviceReady())
    }

    @Test
    fun `a stop between asking for the projection and its first frame finalises and reports not started`() {
        machine.consentGranted()
        machine.serviceReady()
        machine.stopRequested()
        assertEquals(listOf(StopCapture), machine.captureStarted())
        assertEquals(listOf(ReleaseService, NotifyNotStarted(refused = false)), machine.captureStopped(usable = true))
    }

    @Test
    fun `a service or capture that fails to start cleans up what it opened`() {
        machine.consentGranted()
        assertEquals(listOf(ReleaseService, NotifyNotStarted(refused = false)), machine.serviceFailed())

        val capture = SessionMachine().apply { consentGranted(); serviceReady() }
        assertEquals(listOf(StopCapture, ReleaseService, NotifyNotStarted(refused = false)), capture.captureFailed())

        val stoppedEarly = SessionMachine().apply { consentGranted(); serviceReady() }
        assertEquals(listOf(StopCapture, ReleaseService, NotifyNotStarted(refused = false)), stoppedEarly.projectionStopped())
    }

    @Test
    fun `a stop from the system, the limit or a recorder error finalise the file with their reason`() {
        assertEquals(listOf(StopCapture), recording().projectionStopped())
        assertEquals(listOf(ReleaseService, NotifyFinished(RecordingStopReason.SYSTEM)), machine.captureStopped(usable = true))

        val limited = SessionMachine().apply { consentGranted(); serviceReady(); captureStarted() }
        assertEquals(listOf(StopCapture), limited.limitReached())
        assertEquals(listOf(ReleaseService, NotifyFinished(RecordingStopReason.LIMIT)), limited.captureStopped(usable = true))

        val broken = SessionMachine().apply { consentGranted(); serviceReady(); captureStarted() }
        assertEquals(listOf(StopCapture), broken.captureFailed())
        assertEquals(listOf(ReleaseService, NotifyFinished(RecordingStopReason.FAILED)), broken.captureStopped(usable = true))
    }

    @Test
    fun `an unusable file is reported as failed whatever stopped it`() {
        recording().stopRequested()
        assertEquals(listOf(ReleaseService, NotifyFinished(RecordingStopReason.FAILED)), machine.captureStopped(usable = false))
    }

    @Test
    fun `a stray captureFailed before the projection is asked for does not abort a starting session`() {
        machine.consentGranted()
        assertTrue(machine.captureFailed().isEmpty())
        assertEquals(SessionState.STARTING, machine.state)
        assertEquals(listOf(StartCapture), machine.serviceReady())
    }

    @Test
    fun `a stray projectionStopped before the projection is asked for does not abort a starting session`() {
        machine.consentGranted()
        assertTrue(machine.projectionStopped().isEmpty())
        assertEquals(SessionState.STARTING, machine.state)
        assertEquals(listOf(StartCapture), machine.serviceReady())
    }

    @Test
    fun `a stop while the user decides does not swallow the dialog's own refusal`() {
        assertTrue(machine.stopRequested().isEmpty())
        assertEquals(listOf(NotifyNotStarted(refused = true)), machine.consentRefused())
        assertEquals(SessionState.DONE, machine.state)
    }

    @Test
    fun `a stop before the service starts still lets serviceFailed clean up`() {
        machine.consentGranted()
        assertTrue(machine.stopRequested().isEmpty())
        assertEquals(listOf(ReleaseService, NotifyNotStarted(refused = false)), machine.serviceFailed())
    }

    @Test
    fun `repeated and late events change nothing`() {
        recording()
        assertEquals(listOf(StopCapture), machine.stopRequested())
        assertTrue(machine.stopRequested().isEmpty())
        assertTrue(machine.limitReached().isEmpty())
        assertTrue(machine.projectionStopped().isEmpty())
        machine.captureStopped(usable = true)
        val late = machine.consentGranted() + machine.serviceReady() + machine.captureStarted() + machine.stopRequested() +
            machine.captureFailed() + machine.captureStopped(true) + machine.consentRefused()
        assertTrue(late.isEmpty())
        assertEquals(SessionState.DONE, machine.state)
    }
}
